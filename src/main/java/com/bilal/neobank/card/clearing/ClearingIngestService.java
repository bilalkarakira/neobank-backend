package com.bilal.neobank.card.clearing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The issuer's side of clearing. One pass does three things, each safe to repeat after a
 * crash because its state is in the database:
 * <ol>
 *   <li>look at each new file in {@code issuer-in/}: check it as a whole (a bad one is refused and posts nothing), then post
 *       its items one by one with {@link ClearingPoster};</li>
 *   <li>finish a file that a crash left half done (the items already posted are not posted again);</li>
 *   <li>write the acknowledgement of every finished file to {@code issuer-out/}.</li>
 * </ol>
 * Deliberately NOT transactional: each item is its own transaction, so one bad item never undoes the others.
 */
@Service
public class ClearingIngestService {

    private static final Logger log = LoggerFactory.getLogger(ClearingIngestService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** What one pass did. */
    public record Summary(int filesProcessed, int filesRefused, int itemsPosted, int acknowledgementsWritten) {
    }

    private final ClearingProperties properties;
    private final JdbcClient jdbc;
    private final ClearingPoster poster;
    private final Clock clock;

    public ClearingIngestService(ClearingProperties properties, JdbcClient jdbc, ClearingPoster poster, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.poster = poster;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public synchronized Summary ingest() {
        Path in = Path.of(properties.dir(), "issuer-in");
        Path out = Path.of(properties.dir(), "issuer-out");
        ClearingFileCodec codec = ClearingFileCodec.withSecret(properties.fileSecret());
        int processed = 0;
        int refused = 0;
        int posted = 0;
        try {
            Files.createDirectories(in);
            Files.createDirectories(out);
        } catch (IOException e) {
            log.warn("cannot create the clearing folders ({})", e.getClass().getSimpleName());
            return new Summary(0, 0, 0, 0);
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(in)) {
            list.filter(p -> p.getFileName().toString().endsWith(".clr")).sorted().forEach(files::add);
        } catch (IOException e) {
            log.warn("cannot list the clearing folder ({})", e.getClass().getSimpleName());
            return new Summary(0, 0, 0, 0);
        }
        for (Path path : files) {
            String name = path.getFileName().toString();
            Boolean known = jdbc.sql("select exists (select 1 from clearing_files where file_name = :n)").param("n", name)
                    .query(Boolean.class).single();
            if (!known) {
                int done = receive(codec, path, name);
                if (done < 0) {
                    refused++;
                } else {
                    processed++;
                    posted += done;
                }
            }
        }
        // files a crash left half done
        List<String> unfinished = jdbc.sql("select file_name from clearing_files where status = 'RECEIVED' order by received_at")
                .query(String.class).list();
        for (String name : unfinished) {
            Path path = in.resolve(name);
            if (Files.exists(path)) {
                posted += finish(codec, path, name);
            }
        }
        return new Summary(processed, refused, posted, writeAcknowledgements(codec, out));
    }

    // ---- receiving ----

    /** @return -1 when the file was refused whole, otherwise how many items were posted (rejected and repeated ones not counted) */
    private int receive(ClearingFileCodec codec, Path path, String name) {
        ClearingFile file;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                refuse(name, null, "NOT_A_CLEARING_FILE: the file is larger than " + MAX_FILE_BYTES + " bytes");
                return -1;
            }
            file = codec.decode(Files.readAllBytes(path), name);
        } catch (ClearingFileException e) {
            refuse(name, null, e.reason() + ": " + e.getMessage());
            return -1;
        } catch (IOException e) {
            log.warn("cannot read a clearing file ({}); will retry", e.getClass().getSimpleName());
            return 0;
        }
        String refusal = null;
        if (!properties.networkName().equals(file.sender())) {
            refusal = "UNKNOWN_SENDER: " + file.sender() + " is not the card network";
        } else if (!properties.issuerId().equals(file.receiver())) {
            refusal = "WRONG_RECEIVER: the file is addressed to " + file.receiver();
        } else if (!(file.fileId() + ".clr").equals(name)) {
            refusal = "FILE_NAME_MISMATCH: the file id " + file.fileId() + " does not match the file name";
        }
        if (refusal != null) {
            refuse(name, file.fileId(), refusal);
            return -1;
        }
        UUID fileRow = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into clearing_files (id, file_name, network_file_id, status, item_count, control_total, received_at, business_date)
                    values (:id, :name, :nid, 'RECEIVED', :count, :total, :now, :date)
                    """).param("id", fileRow).param("name", name).param("nid", file.fileId()).param("count", file.presentments().size())
                    .param("total", file.controlTotal()).param("now", Timestamp.from(clock.instant())).param("date", java.sql.Date.valueOf(file.businessDate())).update();
        } catch (DuplicateKeyException e) {
            refuse(name, file.fileId(), "DUPLICATE_FILE: this file id was already processed");
            return -1;
        }
        return postAll(fileRow, file);
    }

    private int finish(ClearingFileCodec codec, Path path, String name) {
        try {
            ClearingFile file = codec.decode(Files.readAllBytes(path), name);
            UUID fileRow = jdbc.sql("select id from clearing_files where file_name = :n").param("n", name).query(UUID.class).single();
            return postAll(fileRow, file);
        } catch (ClearingFileException | IOException e) {
            log.warn("cannot finish clearing file {} ({})", name, e.getClass().getSimpleName());
            return 0;
        }
    }

    private int postAll(UUID fileRow, ClearingFile file) {
        int posted = 0;
        int record = 0;
        for (Message m : file.presentments()) {
            record++;
            try {
                ClearingPoster.Result result = poster.post(fileRow, record, m);
                if (!"REJECTED".equals(result.result()) && !"DUPLICATE".equals(result.result())) {
                    posted++;
                }
            } catch (RuntimeException e) {
                // leave the file RECEIVED: the next pass tries the remaining items again, and the ones done are skipped
                log.error("clearing item {} of file {} failed ({}); the file will be retried", record, file.fileId(), e.getClass().getSimpleName());
                return posted;
            }
        }
        jdbc.sql("update clearing_files set status = 'DONE' where id = :id and status = 'RECEIVED'").param("id", fileRow).update();
        return posted;
    }

    private void refuse(String name, String networkFileId, String reason) {
        jdbc.sql("""
                insert into clearing_files (id, file_name, network_file_id, status, reason, received_at)
                values (:id, :name, :nid, 'REJECTED', :reason, :now) on conflict (file_name) do nothing
                """).param("id", UUID.randomUUID()).param("name", name).param("nid", networkFileId)
                .param("reason", reason.length() > 200 ? reason.substring(0, 200) : reason).param("now", Timestamp.from(clock.instant())).update();
    }

    // ---- acknowledgements ----

    private int writeAcknowledgements(ClearingFileCodec codec, Path out) {
        record Waiting(UUID id, String name, String status, String reason) {
        }
        List<Waiting> waiting = jdbc.sql("""
                select id, file_name, status, reason from clearing_files where not ack_written and status in ('DONE', 'REJECTED')
                order by received_at
                """).query((rs, i) -> new Waiting(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        for (Waiting w : waiting) {
            String base = w.name().substring(0, w.name().length() - ".clr".length());
            ClearingAck ack;
            if (w.status().equals("REJECTED")) {
                ack = ClearingAck.rejected(base, w.reason() == null ? "refused" : w.reason());
            } else {
                List<ClearingAck.Item> items = jdbc.sql("select record_no, result, reason from clearing_items where file_id = :f order by record_no")
                        .param("f", w.id()).query((rs, i) -> new ClearingAck.Item(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
                ack = ClearingAck.accepted(base, items);
            }
            codec.writeAck(out, w.name().substring(0, w.name().length() - ".clr".length()) + ".ack", ack);
            jdbc.sql("update clearing_files set ack_written = true where id = :id").param("id", w.id()).update();
        }
        return waiting.size();
    }
}
