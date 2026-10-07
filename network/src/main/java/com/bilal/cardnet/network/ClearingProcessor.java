package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.DataAccessException;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.Pans;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The network's side of clearing (SPEC-P4-clearing.md, section 4). One beat does four things, each safe to repeat after a
 * crash because its state is in the database:
 * <ol>
 *   <li>look at each new file in {@code acquirer-out/}: check the file as a whole (any failure refuses it and processes
 *       nothing), check each item, work out its fees, split it per issuer;</li>
 *   <li>write the per-issuer files (the row exists before the file does, so a crash in between is repaired);</li>
 *   <li>read the issuers' acknowledgements from {@code issuer-out/};</li>
 *   <li>write the acquirer's acknowledgement once every issuer has answered (or at once for a refused file).</li>
 * </ol>
 */
public final class ClearingProcessor {

    private static final System.Logger LOG = System.getLogger(ClearingProcessor.class.getName());
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final NetworkConfig config;
    private final DataSource ds;
    private final PanVault vault;
    private final Clock clock;
    private final BinTable bins;
    private final ClearingFileCodec codec;
    private final ClearingDirs dirs;
    private final Packager packager = new Packager();

    public ClearingProcessor(NetworkConfig config, DataSource ds, PanVault vault, Clock clock) {
        this.config = config;
        this.ds = ds;
        this.vault = vault;
        this.clock = clock;
        this.bins = new BinTable(config.routes);
        this.codec = ClearingFileCodec.withSecret(config.fileSecret);
        this.dirs = new ClearingDirs(config.clearingDir);
    }

    /** One beat. Returns how many things it did (new files, files written, acknowledgements read and written). */
    public synchronized int tick() {
        int did = 0;
        did += scanAcquirerFiles();
        did += writeIssuerFiles();
        did += readIssuerAcks();
        did += writeAcquirerAcks();
        return did;
    }

    /** Files that still wait for an acknowledgement to the acquirer (for the status page). */
    public int waiting() {
        return scalarInt("select count(*) from net_clearing_files where not ack_written");
    }

    // ---- 1. new acquirer files ------------------------------------------------------------------------------

    private int scanAcquirerFiles() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dirs.acquirerOut())) {
            list.filter(p -> p.getFileName().toString().endsWith(ClearingDirs.FILE_SUFFIX)).sorted().forEach(files::add);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list the acquirer folder ({0})", e.getClass().getSimpleName());
            return 0;
        }
        int did = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            if (scalarInt("select count(*) from net_clearing_files where file_name = ?", name) == 0) {
                handle(path, name);
                did++;
            }
        }
        return did;
    }

    private void handle(Path path, String name) {
        byte[] bytes;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                insertRejected(name, null, null, "NOT_A_CLEARING_FILE: the file is larger than " + MAX_FILE_BYTES + " bytes");
                return;
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a clearing file ({0}); will retry", e.getClass().getSimpleName());
            return;
        }
        ClearingFile file;
        try {
            file = codec.decode(bytes, name);
        } catch (ClearingFileException e) {
            insertRejected(name, null, null, e.reason() + ": " + e.getMessage());
            return;
        }
        String refusal = fileRefusal(file, name);
        if (refusal != null) {
            insertRejected(name, file.fileId(), file.sender(), refusal);
            return;
        }
        try {
            accept(file, name);
        } catch (DataAccessException e) {
            if (e.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                insertRejected(name, file.fileId(), file.sender(), "DUPLICATE_FILE: this file id was already processed");
                return;
            }
            throw e;
        }
    }

    /** The checks that are about the file as a whole and not about the codec's format. (A file id seen before cannot get here: the file name carries the id and a name is looked at once; a race between two processes is caught by the database.) Null when the file is fine. */
    private String fileRefusal(ClearingFile file, String name) {
        if (!config.knownAcquirers.contains(file.sender())) {
            return "UNKNOWN_SENDER: " + file.sender() + " is not a known acquirer";
        }
        if (!config.networkName.equals(file.receiver())) {
            return "WRONG_RECEIVER: the file is addressed to " + file.receiver();
        }
        if (!(file.fileId() + ClearingDirs.FILE_SUFFIX).equals(name)) {
            return "FILE_NAME_MISMATCH: the file id " + file.fileId() + " does not match the file name";
        }
        return null;
    }

    private record Pending(int record, Message message, String refusal, NetworkConfig.Route route) {
    }

    private void accept(ClearingFile file, String name) {
        Instant now = clock.instant();
        UUID fileRow = UUID.randomUUID();
        List<Pending> pending = new ArrayList<>();
        int number = 1;
        for (Message m : file.presentments()) {
            pending.add(check(number++, m, file));
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, "insert into net_clearing_files (id, file_name, file_id, sender, business_date, item_count, control_total, status, received_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', ?)", fileRow, name, file.fileId(), file.sender(),
                        java.sql.Date.valueOf(file.businessDate()), file.presentments().size(), file.controlTotal(), Timestamp.from(now));
                Map<String, UUID> outfiles = new LinkedHashMap<>();
                Map<UUID, int[]> outCount = new LinkedHashMap<>();
                Map<UUID, long[]> outTotal = new LinkedHashMap<>();
                for (Pending p : pending) {
                    if (p.refusal() != null) {
                        continue;
                    }
                    UUID out = outfiles.computeIfAbsent(p.route().prefix(), prefix -> {
                        UUID id = UUID.randomUUID();
                        String fileId = "N%07d".formatted(nextval(c, "clearing_file_seq"));
                        exec(c, "insert into net_clearing_outfiles (id, file_row, issuer_prefix, file_id, file_name, item_count, control_total) values (?, ?, ?, ?, ?, 0, 0)",
                                id, fileRow, prefix, fileId, fileId + ClearingDirs.FILE_SUFFIX);
                        outCount.put(id, new int[1]);
                        outTotal.put(id, new long[1]);
                        return id;
                    });
                    int outRecord = ++outCount.get(out)[0];
                    outTotal.get(out)[0] += Long.parseLong(p.message().get(4));
                    insertItem(c, fileRow, file, p, out, outRecord);
                }
                for (Pending p : pending) {
                    if (p.refusal() != null) {
                        insertItem(c, fileRow, file, p, null, 0);
                    }
                }
                for (UUID out : outCount.keySet()) {
                    exec(c, "update net_clearing_outfiles set item_count = ?, control_total = ? where id = ?", outCount.get(out)[0], outTotal.get(out)[0], out);
                }
                c.commit();
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not record a clearing file", e);
        }
    }

    /** The checks of one item; the refusal text is null when the item goes on to its issuer. */
    private Pending check(int number, Message m, ClearingFile file) {
        if (!"840".equals(m.get(49))) {
            return new Pending(number, m, "CURRENCY: only 840 (USD) is cleared", null);
        }
        if (file.sender().chars().allMatch(Character::isDigit)
                && !Messages.padAcquirer(file.sender()).equals(Messages.padAcquirer(m.get(32)))) {
            return new Pending(number, m, "ACQUIRER_MISMATCH: the acquirer id of the item is not the sender of the file", null);
        }
        return bins.lookup(m.get(2)).map(route -> new Pending(number, m, null, route))
                .orElseGet(() -> new Pending(number, m, "NO_ROUTE: no issuer for this card number (15)", null));
    }

    private void insertItem(Connection c, UUID fileRow, ClearingFile file, Pending p, UUID outfile, int outRecord) {
        Message m = p.message();
        boolean refund = ClearingFile.REFUND.equals(m.get(3));
        BigDecimal amount = Messages.amount(m);
        Fees fees = p.refusal() == null ? Fees.of(amount, refund, config) : null;
        UUID id = UUID.randomUUID();
        byte[] cipher = p.refusal() == null ? vault.encrypt(packager.pack(m), id.toString()) : null;
        exec(c, "insert into net_clearing_items (id, file_row, record_no, outfile, out_record, kind, stan, rrn, masked_pan, amount, merchant_discount, "
                + "interchange, scheme_fee_acquirer, scheme_fee_issuer, result, reason, presentment_ciphertext, issuer_prefix, acquirer_id, business_date) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, fileRow, p.record(), outfile, outfile == null ? null : outRecord,
                refund ? "REFUND" : "PURCHASE", m.get(11), m.get(37), Pans.mask(m.get(2)), amount,
                fees == null ? BigDecimal.ZERO : fees.merchantDiscount(), fees == null ? BigDecimal.ZERO : fees.interchange(),
                fees == null ? BigDecimal.ZERO : fees.schemeFeeAcquirer(), fees == null ? BigDecimal.ZERO : fees.schemeFeeIssuer(),
                p.refusal() == null ? null : "REFUSED", p.refusal() == null ? null : p.refusal(), cipher,
                p.route() == null ? null : p.route().prefix(), file.sender(), java.sql.Date.valueOf(file.businessDate()));
    }

    private void insertRejected(String name, String fileId, String sender, String reason) {
        try (Connection c = ds.getConnection()) {
            exec(c, "insert into net_clearing_files (id, file_name, file_id, sender, status, reason, received_at) values (?, ?, ?, ?, 'REJECTED', ?, ?) on conflict (file_name) do nothing",
                    UUID.randomUUID(), name, fileId, sender, truncate(reason, 200), Timestamp.from(clock.instant()));
        } catch (SQLException e) {
            throw new DataAccessException("could not record a refused clearing file", e);
        }
    }

    // ---- 2. per-issuer files --------------------------------------------------------------------------------

    private int writeIssuerFiles() {
        int did = 0;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select o.id, o.file_id, o.file_name, o.issuer_prefix, f.business_date from net_clearing_outfiles o "
                        + "join net_clearing_files f on f.id = o.file_row where not o.written order by o.file_name");
                ResultSet rs = ps.executeQuery()) {
            List<Object[]> todo = new ArrayList<>();
            while (rs.next()) {
                todo.add(new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDate(5).toLocalDate()});
            }
            for (Object[] row : todo) {
                writeIssuerFile((UUID) row[0], (String) row[1], (String) row[2], (String) row[3], (LocalDate) row[4]);
                did++;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the issuer files to write", e);
        }
        return did;
    }

    private void writeIssuerFile(UUID outfile, String fileId, String fileName, String prefix, LocalDate businessDate) {
        List<Message> presentments = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, presentment_ciphertext from net_clearing_items where outfile = ? order by out_record")) {
            ps.setObject(1, outfile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    presentments.add(packager.unpack(vault.decrypt(rs.getBytes(2), rs.getObject(1, UUID.class).toString())));
                }
            }
            codec.write(dirs.issuerIn(), fileName, new ClearingFile(fileId, businessDate, config.networkName, prefix, "USD", presentments));
            exec(c, "update net_clearing_outfiles set written = true, written_at = ? where id = ?", Timestamp.from(clock.instant()), outfile);
        } catch (SQLException e) {
            throw new DataAccessException("could not write an issuer file", e);
        }
    }

    // ---- 3. issuers' acknowledgements -----------------------------------------------------------------------

    private int readIssuerAcks() {
        List<Object[]> waiting = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, file_id, file_name from net_clearing_outfiles where written and ack_status is null order by file_name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                waiting.add(new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)});
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the issuer files that wait for an answer", e);
        }
        int did = 0;
        for (Object[] row : waiting) {
            Path ackPath = dirs.issuerOut().resolve(ClearingDirs.ackName((String) row[2]));
            if (Files.exists(ackPath)) {
                applyIssuerAck((UUID) row[0], (String) row[1], ackPath);
                did++;
            }
        }
        return did;
    }

    private void applyIssuerAck(UUID outfile, String fileId, Path ackPath) {
        ClearingAck ack = null;
        String invalid = null;
        try {
            ack = codec.readAck(ackPath);
            if (!ack.fileId().equals(fileId)) {
                invalid = "the acknowledgement is for file " + ack.fileId() + ", not " + fileId;
            }
        } catch (ClearingFileException e) {
            invalid = "the acknowledgement cannot be read (" + e.reason() + ")";
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (invalid != null) {
                    refuseAll(c, outfile, "INVALID", invalid, "the issuer's answer was unusable: " + invalid);
                } else if (ack.status() == ClearingAck.Status.REJECTED) {
                    refuseAll(c, outfile, "REJECTED", ack.reason(), "the issuer rejected the file: " + ack.reason());
                } else {
                    exec(c, "update net_clearing_outfiles set ack_status = 'ACCEPTED', acked_at = ? where id = ?", Timestamp.from(clock.instant()), outfile);
                    List<Object[]> items = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement("select id, out_record from net_clearing_items where outfile = ?")) {
                        ps.setObject(1, outfile);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                items.add(new Object[] {rs.getObject(1, UUID.class), rs.getInt(2)});
                            }
                        }
                    }
                    for (Object[] item : items) {
                        ClearingAck.Item result = ack.item((Integer) item[1]);
                        if (result == null) {
                            exec(c, "update net_clearing_items set result = 'REFUSED', reason = ? where id = ?",
                                    "the issuer's acknowledgement does not mention this item", item[0]);
                        } else {
                            exec(c, "update net_clearing_items set result = ?, reason = ? where id = ?", result.result(), truncate(result.reason(), 120), item[0]);
                        }
                    }
                }
                c.commit();
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not apply an issuer acknowledgement", e);
        }
    }

    private void refuseAll(Connection c, UUID outfile, String status, String reason, String itemReason) {
        exec(c, "update net_clearing_outfiles set ack_status = ?, ack_reason = ?, acked_at = ? where id = ?", status, truncate(reason, 200),
                Timestamp.from(clock.instant()), outfile);
        exec(c, "update net_clearing_items set result = 'REFUSED', reason = ? where outfile = ?", truncate(itemReason, 120), outfile);
    }

    // ---- 4. acknowledgements to the acquirer ----------------------------------------------------------------

    private int writeAcquirerAcks() {
        List<Object[]> ready = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select f.id, f.file_name, f.status, f.reason from net_clearing_files f where not f.ack_written and (f.status = 'REJECTED' or not exists "
                        + "(select 1 from net_clearing_outfiles o where o.file_row = f.id and o.ack_status is null)) order by f.file_name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ready.add(new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)});
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the acknowledgements to write", e);
        }
        for (Object[] row : ready) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            String base = name.substring(0, name.length() - ClearingDirs.FILE_SUFFIX.length());
            ClearingAck ack;
            if ("REJECTED".equals(row[2])) {
                ack = ClearingAck.rejected(base, String.valueOf(row[3]));
            } else {
                List<ClearingAck.Item> items = new ArrayList<>();
                try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                        "select record_no, result, reason from net_clearing_items where file_row = ? order by record_no")) {
                    ps.setObject(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            items.add(new ClearingAck.Item(rs.getInt(1), rs.getString(2), rs.getString(3)));
                        }
                    }
                } catch (SQLException e) {
                    throw new DataAccessException("could not read the results of a clearing file", e);
                }
                ack = ClearingAck.accepted(base, items);
            }
            codec.writeAck(dirs.acquirerIn(), ClearingDirs.ackName(name), ack);
            exec1("update net_clearing_files set ack_written = true, ack_written_at = ? where id = ?", Timestamp.from(clock.instant()), id);
        }
        return ready.size();
    }

    // ---- small JDBC helpers ---------------------------------------------------------------------------------

    private static long nextval(Connection c, String sequence) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select nextval('" + sequence + "')")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new DataAccessException("could not read " + sequence, e);
        }
    }

    private static int exec(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("database error in clearing", e);
        }
    }

    private void exec1(String sql, Object... args) {
        try (Connection c = ds.getConnection()) {
            exec(c, sql, args);
        } catch (SQLException e) {
            throw new DataAccessException("database error in clearing", e);
        }
    }

    private int scalarInt(String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("database error in clearing", e);
        }
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }
}
