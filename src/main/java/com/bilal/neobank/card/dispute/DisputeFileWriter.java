package com.bilal.neobank.card.dispute;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException;
import com.bilal.neobank.card.clearing.ClearingProperties;
import com.bilal.neobank.ledger.TransactionType;
import com.bilal.neobank.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The issuer's side of sending chargebacks (SPEC-P6a-chargebacks.md, section 3). One pass does three things, each safe to repeat
 * after a crash because its state is in the database:
 * <ol>
 *   <li>put the disputes that are FILED and in no file yet into dispute files, one per business date (the file row and the
 *       record numbers are decided in one transaction, so the file content is fixed before it is written);</li>
 *   <li>write the files that are on the books but not on disk (the same content every time);</li>
 *   <li>read the network's acknowledgement of each written file: an accepted chargeback opens the dispute, a refused one (or a
 *       whole refused file) ends it as LOST and takes the provisional credit back.</li>
 * </ol>
 */
@Service
public class DisputeFileWriter {

    private static final Logger log = LoggerFactory.getLogger(DisputeFileWriter.class);
    private static final DateTimeFormatter MMDDHHMMSS = DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);

    /** What one pass did. */
    public record Summary(int filesCreated, int filesWritten, int filesAcknowledged) {
    }

    private final ClearingProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DisputePostings postings;
    private final NotificationService notifications;
    private final Clock clock;

    public DisputeFileWriter(ClearingProperties properties, JdbcClient jdbc, TransactionTemplate tx, DisputePostings postings,
            NotificationService notifications, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.tx = tx;
        this.postings = postings;
        this.notifications = notifications;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public synchronized Summary run() {
        Path out = Path.of(properties.dir(), "issuer-out");
        Path in = Path.of(properties.dir(), "issuer-in");
        try {
            Files.createDirectories(out);
            Files.createDirectories(in);
        } catch (IOException e) {
            log.warn("cannot create the clearing folders ({})", e.getClass().getSimpleName());
            return new Summary(0, 0, 0);
        }
        ClearingFileCodec codec = ClearingFileCodec.withSecret(properties.fileSecret());
        int created = createFiles();
        int written = writeFiles(codec, out);
        int acknowledged = readAcknowledgements(codec, in);
        return new Summary(created, written, acknowledged);
    }

    // ---- 1. decide the files ----------------------------------------------------------------------------------

    private int createFiles() {
        List<LocalDate> dates = jdbc.sql("""
                select distinct i.business_date from card_disputes d join clearing_items i on i.id = d.chargeback_item_id
                where d.status = 'FILED' and d.dispute_file_id is null order by i.business_date
                """).query(LocalDate.class).list();
        int created = 0;
        for (LocalDate date : dates) {
            Integer made = tx.execute(status -> {
                record Row(UUID id, BigDecimal amount) {
                }
                List<Row> rows = jdbc.sql("""
                        select d.id, d.amount from card_disputes d join clearing_items i on i.id = d.chargeback_item_id
                        where d.status = 'FILED' and d.dispute_file_id is null and i.business_date = :date order by d.filed_at, d.id for update of d
                        """).param("date", date).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
                if (rows.isEmpty()) {
                    return 0;
                }
                long sequence = jdbc.sql("select nextval('dispute_file_seq')").query(Long.class).single();
                String fileId = "C%07d".formatted(sequence);
                UUID fileRow = UUID.randomUUID();
                long total = rows.stream().mapToLong(r -> r.amount().movePointRight(2).longValueExact()).sum();
                jdbc.sql("""
                        insert into dispute_files (id, file_name, file_id, direction, business_date, status, item_count, control_total, created_at)
                        values (:id, :name, :fid, 'OUT', :date, 'CREATED', :count, :total, :now)
                        """).param("id", fileRow).param("name", fileId + ".dsp").param("fid", fileId).param("date", date).param("count", rows.size())
                        .param("total", total).param("now", Timestamp.from(clock.instant())).update();
                int record = 0;
                for (Row row : rows) {
                    record++;
                    jdbc.sql("update card_disputes set dispute_file_id = :f, file_record = :r where id = :id").param("f", fileRow).param("r", record)
                            .param("id", row.id()).update();
                }
                return 1;
            });
            created += made == null ? 0 : made;
        }
        return created;
    }

    // ---- 2. write them ---------------------------------------------------------------------------------------

    private int writeFiles(ClearingFileCodec codec, Path out) {
        record Pending(UUID id, String name, String fileId, LocalDate date) {
        }
        List<Pending> pending = jdbc.sql("select id, file_name, file_id, business_date from dispute_files where direction = 'OUT' and status = 'CREATED' order by created_at")
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class))).list();
        int written = 0;
        for (Pending p : pending) {
            List<Message> messages = jdbc.sql("""
                    select a.stan, a.rrn, a.terminal_id, a.merchant_id, a.merchant_name, a.mcc, a.acquirer_id, a.local_date, a.currency, c.bin, a.last4,
                           d.amount, d.reason, d.note, d.filed_at
                    from card_disputes d join authorizations a on a.id = d.authorization_id join cards c on c.id = d.card_id
                    where d.dispute_file_id = :f order by d.file_record
                    """).param("f", p.id()).query((rs, n) -> chargeback(rs)).list();
            ClearingFile file = new ClearingFile(p.fileId(), p.date(), properties.issuerId(), properties.networkName(), "USD", messages);
            codec.write(out, p.name(), file);
            jdbc.sql("update dispute_files set status = 'WRITTEN' where id = :id and status = 'CREATED'").param("id", p.id()).update();
            written++;
        }
        return written;
    }

    /**
     * The chargeback record. NeoBank keeps no card number, only its keyed hash, so DE 2 is built from what it does know: the BIN and
     * the last four digits with zeros between. That is all the network ever looks at (it keeps the first six and the last four) and
     * it matches the payment by STAN, RRN and that masked form. A real issuer would send the full number.
     */
    private static Message chargeback(java.sql.ResultSet rs) throws java.sql.SQLException {
        String bin = rs.getString(10);
        String last4 = rs.getString(11);
        String pan = bin + "0".repeat(Math.max(0, 16 - bin.length() - last4.length())) + last4;
        Instant filed = rs.getObject(15, OffsetDateTime.class).toInstant();
        Message m = Message.of("1442").set(2, pan).set(3, ClearingFile.PURCHASE)
                .set(4, "%012d".formatted(rs.getBigDecimal(12).movePointRight(2).longValueExact())).set(7, MMDDHHMMSS.format(filed))
                .set(11, rs.getString(1)).set(12, HHMMSS.format(filed)).set(13, rs.getString(8)).set(32, rs.getString(7)).set(37, rs.getString(2))
                .set(41, "%-8s".formatted(rs.getString(3))).set(42, "%-15s".formatted(rs.getString(4))).set(49, rs.getString(9))
                .set(48, "CB|" + rs.getString(13) + "|" + (rs.getString(14) == null ? "" : rs.getString(14)));
        return m;
    }

    // ---- 3. read the network's answers -----------------------------------------------------------------------

    private int readAcknowledgements(ClearingFileCodec codec, Path in) {
        record Written(UUID id, String name) {
        }
        List<Written> written = jdbc.sql("select id, file_name from dispute_files where direction = 'OUT' and status = 'WRITTEN' order by created_at")
                .query((rs, n) -> new Written(rs.getObject(1, UUID.class), rs.getString(2))).list();
        int done = 0;
        for (Written w : written) {
            Path path = in.resolve(w.name().substring(0, w.name().length() - ".dsp".length()) + ".ack");
            if (!Files.exists(path)) {
                continue;
            }
            ClearingAck ack;
            try {
                ack = codec.readAck(path);
            } catch (ClearingFileException e) {
                log.warn("cannot read the acknowledgement of {} ({}); will retry", w.name(), e.reason());
                continue;
            }
            Boolean applied = tx.execute(status -> apply(w.id(), w.name(), ack));
            if (Boolean.TRUE.equals(applied)) {
                done++;
            }
        }
        return done;
    }

    private boolean apply(UUID fileRow, String name, ClearingAck ack) {
        String fileId = name.substring(0, name.length() - ".dsp".length());
        boolean wholeFileRefused = ack.status() == ClearingAck.Status.REJECTED || !ack.fileId().equals(fileId);
        String fileReason = ack.status() == ClearingAck.Status.REJECTED ? ack.reason() : "the answer is for another file";
        // the status change is the guard: whoever moves WRITTEN on wins, a second pass does nothing
        int moved = jdbc.sql("update dispute_files set status = :s, reason = cast(:r as varchar) where id = :id and status = 'WRITTEN'")
                .param("s", wholeFileRefused ? "REJECTED" : "ACKED").param("r", wholeFileRefused ? truncate(fileReason, 200) : null).param("id", fileRow).update();
        if (moved == 0) {
            return false;
        }
        Map<Integer, ClearingAck.Item> items = new HashMap<>();
        if (!wholeFileRefused) {
            ack.items().forEach(i -> items.put(i.record(), i));
        }
        record Dispute(UUID id, UUID userId, UUID accountId, UUID item, BigDecimal amount, String merchant, int record) {
        }
        List<Dispute> disputes = jdbc.sql("select id, user_id, account_id, chargeback_item_id, amount, merchant_name, file_record from card_disputes where dispute_file_id = :f order by file_record")
                .param("f", fileRow).query((rs, n) -> new Dispute(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getBigDecimal(5), rs.getString(6), rs.getInt(7))).list();
        Instant now = clock.instant();
        for (Dispute d : disputes) {
            ClearingAck.Item item = items.get(d.record());
            boolean accepted = item != null && item.cleared();
            if (accepted) {
                jdbc.sql("update clearing_items set result = 'CHARGEBACK_POSTED' where id = :id and result = 'CHARGEBACK_SENT'").param("id", d.item()).update();
                jdbc.sql("update card_disputes set status = 'OPEN', opened_at = :now where id = :id and status = 'FILED'")
                        .param("now", Timestamp.from(now)).param("id", d.id()).update();
            } else {
                String why = wholeFileRefused ? fileReason : item == null ? "no answer for this item" : item.reason();
                refuse(d.id(), d.userId(), d.accountId(), d.item(), d.amount(), d.merchant(), why == null ? "refused" : why, now);
            }
        }
        return true;
    }

    /** The network refused the chargeback: the dispute ends LOST and the provisional credit is taken back (what the account cannot cover is owed). */
    private void refuse(UUID disputeId, UUID userId, UUID accountId, UUID itemId, BigDecimal amount, String merchant, String why, Instant now) {
        int moved = jdbc.sql("""
                update card_disputes set status = 'LOST', closed_at = :now, outcome = :outcome where id = :id and status = 'FILED'
                """).param("now", Timestamp.from(now)).param("outcome", truncate("The card network refused the chargeback: " + why, 200)).param("id", disputeId).update();
        if (moved == 0) {
            return;
        }
        jdbc.sql("update clearing_items set result = 'CHARGEBACK_REFUSED', reason = :why where id = :id").param("why", truncate(why, 120)).param("id", itemId).update();
        DisputePostings.Debit debit = postings.debit(accountId, userId, itemId, disputeId + "-x", TransactionType.CARD_CREDIT_REVERSED,
                "Card dispute credit reversed: " + merchant, amount, now);
        if (debit.shortfall().signum() > 0) {
            why = why + " (" + DisputeService.money(debit.shortfall()) + " of it is owed to the bank)";
        }
        notifications.disputeLost(userId, merchant, DisputeService.money(amount), "the network could not take the case (" + truncate(why, 120) + ")");
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }
}
