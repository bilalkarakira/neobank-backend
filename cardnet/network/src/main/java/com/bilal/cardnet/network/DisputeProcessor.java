package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFile.RecordKind;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The network's side of disputes. Like {@link ClearingProcessor} one beat does the same four
 * things, each safe to repeat after a crash: look at new dispute files (chargebacks an issuer left in {@code issuer-out/},
 * representments an acquirer left in {@code acquirer-out/}), check every item against what the network itself cleared, write the
 * file for the other side, and acknowledge the sender.
 *
 * <p>An accepted chargeback or representment becomes an item of its own in {@code net_clearing_items}: a chargeback counts as a
 * refund and a representment as a purchase in the next settlement, with fees worked out the same way. The network never takes the
 * issuer's or the acquirer's word: a chargeback must name a purchase it cleared for that issuer, at most for its amount, at most
 * 120 days old and not charged back before; a representment must answer a chargeback it forwarded, from that acquirer.
 */
public final class DisputeProcessor {

    private static final System.Logger LOG = System.getLogger(DisputeProcessor.class.getName());
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** A purchase can be charged back this many days after its business date. */
    public static final int CHARGEBACK_DAYS = 120;

    /** Which way a dispute file travels. */
    enum Direction {
        CHARGEBACKS, REPRESENTMENTS
    }

    private final NetworkConfig config;
    private final DataSource ds;
    private final PanVault vault;
    private final Clock clock;
    private final ClearingFileCodec codec;
    private final ClearingDirs dirs;
    private final Packager packager = new Packager();

    public DisputeProcessor(NetworkConfig config, DataSource ds, PanVault vault, Clock clock) {
        this.config = config;
        this.ds = ds;
        this.vault = vault;
        this.clock = clock;
        this.codec = ClearingFileCodec.withSecret(config.fileSecret);
        this.dirs = new ClearingDirs(config.clearingDir);
    }

    /** One beat. Returns how many things it did. */
    public synchronized int tick() {
        int did = 0;
        did += scan(Direction.CHARGEBACKS);
        did += scan(Direction.REPRESENTMENTS);
        did += writeForwards();
        did += readForwardAcks();
        did += writeAcknowledgements();
        return did;
    }

    /** Dispute files still waiting for their acknowledgement (for the status page). */
    public int waiting() {
        return scalarInt("select count(*) from net_dispute_files where not ack_written");
    }

    private Path inFolder(Direction d) {
        return d == Direction.CHARGEBACKS ? dirs.issuerOut() : dirs.acquirerOut();
    }

    /** Where the acknowledgement for the SENDER goes: the folder that is for it. */
    private Path ackFolder(Direction d) {
        return d == Direction.CHARGEBACKS ? dirs.issuerIn() : dirs.acquirerIn();
    }

    // ---- 1. new dispute files ------------------------------------------------------------------------------

    private int scan(Direction direction) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(inFolder(direction))) {
            list.filter(p -> p.getFileName().toString().endsWith(".dsp")).sorted().forEach(files::add);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list a dispute folder ({0})", e.getClass().getSimpleName());
            return 0;
        }
        int did = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            if (scalarInt("select count(*) from net_dispute_files where file_name = ?", name) == 0) {
                handle(path, name, direction);
                did++;
            }
        }
        return did;
    }

    private void handle(Path path, String name, Direction direction) {
        byte[] bytes;
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                insertRejected(name, null, null, direction, "NOT_A_CLEARING_FILE: the file is larger than " + MAX_FILE_BYTES + " bytes");
                return;
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a dispute file ({0}); will retry", e.getClass().getSimpleName());
            return;
        }
        ClearingFile file;
        try {
            file = codec.decode(bytes, name);
        } catch (ClearingFileException e) {
            insertRejected(name, null, null, direction, e.reason() + ": " + e.getMessage());
            return;
        }
        String refusal = fileRefusal(file, name, direction);
        if (refusal != null) {
            insertRejected(name, file.fileId(), file.sender(), direction, refusal);
            return;
        }
        try {
            accept(file, name, direction);
        } catch (DataAccessException e) {
            if (e.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                insertRejected(name, file.fileId(), file.sender(), direction, "DUPLICATE_FILE: this file was already processed");
                return;
            }
            throw e;
        }
    }

    private String fileRefusal(ClearingFile file, String name, Direction direction) {
        if (direction == Direction.CHARGEBACKS) {
            if (config.routes.stream().noneMatch(r -> r.prefix().equals(file.sender()))) {
                return "UNKNOWN_SENDER: " + file.sender() + " is not an issuer this network knows";
            }
        } else if (!config.knownAcquirers.contains(file.sender())) {
            return "UNKNOWN_SENDER: " + file.sender() + " is not a known acquirer";
        }
        if (!config.networkName.equals(file.receiver())) {
            return "WRONG_RECEIVER: the file is addressed to " + file.receiver();
        }
        if (!(file.fileId() + ".dsp").equals(name)) {
            return "FILE_NAME_MISMATCH: the file id " + file.fileId() + " does not match the file name";
        }
        RecordKind wanted = direction == Direction.CHARGEBACKS ? RecordKind.CHARGEBACK : RecordKind.REPRESENTMENT;
        for (Message m : file.presentments()) {
            if (ClearingFile.kindOf(m) != wanted) {
                return "WRONG_DIRECTION: a file from " + (direction == Direction.CHARGEBACKS ? "an issuer" : "an acquirer") + " holds only "
                        + wanted.name().toLowerCase() + "s";
            }
        }
        return null;
    }

    /** What the checks made of one record: a refusal, or the item it concerns and who gets it. */
    private record Verdict(String refusal, UUID original, String issuer, String acquirer, String target) {
        static Verdict refused(String reason) {
            return new Verdict(reason, null, null, null, null);
        }
    }

    private void accept(ClearingFile file, String name, Direction direction) {
        Instant now = clock.instant();
        UUID fileRow = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, "insert into net_dispute_files (id, file_name, file_id, sender, business_date, direction, item_count, control_total, status, received_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', ?)", fileRow, name, file.fileId(), file.sender(), java.sql.Date.valueOf(file.businessDate()),
                        direction.name(), file.presentments().size(), file.controlTotal(), Timestamp.from(now));
                Map<String, UUID> forwards = new LinkedHashMap<>();
                Map<UUID, int[]> counts = new LinkedHashMap<>();
                Map<UUID, long[]> totals = new LinkedHashMap<>();
                int record = 0;
                for (Message m : file.presentments()) {
                    record++;
                    Verdict v = direction == Direction.CHARGEBACKS ? judgeChargeback(c, m, file) : judgeRepresentment(c, m, file);
                    boolean chargeback = direction == Direction.CHARGEBACKS;
                    BigDecimal amount = Messages.amount(m);
                    UUID id = UUID.randomUUID();
                    if (v.refusal() != null) {
                        insertItem(c, id, fileRow, record, chargeback, m, amount, null, null, null, file, "REFUSED", v.refusal(), null, null, 0);
                        continue;
                    }
                    UUID forward = forwards.computeIfAbsent(v.target(), target -> {
                        UUID fid = UUID.randomUUID();
                        String forwardId = "X%07d".formatted(nextval(c, "dispute_file_seq"));
                        exec(c, "insert into net_dispute_forwards (id, dispute_file, target, file_id, file_name, item_count, control_total) values (?, ?, ?, ?, ?, 0, 0)",
                                fid, fileRow, target, forwardId, forwardId + ".dsp");
                        counts.put(fid, new int[1]);
                        totals.put(fid, new long[1]);
                        return fid;
                    });
                    int forwardRecord = ++counts.get(forward)[0];
                    totals.get(forward)[0] += Long.parseLong(m.get(4));
                    insertItem(c, id, fileRow, record, chargeback, m, amount, v.original(), v.issuer(), v.acquirer(), file,
                            chargeback ? "CHARGEBACK_ACCEPTED" : "REPRESENTMENT_ACCEPTED", null, forward, vault.encrypt(packager.pack(m), id.toString()), forwardRecord);
                }
                for (UUID forward : counts.keySet()) {
                    exec(c, "update net_dispute_forwards set item_count = ?, control_total = ? where id = ?", counts.get(forward)[0], totals.get(forward)[0], forward);
                }
                c.commit();
            } catch (RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not record a dispute file", e);
        }
    }

    // ---- the checks ------------------------------------------------------------------------------------------

    private Verdict judgeChargeback(Connection c, Message m, ClearingFile file) throws SQLException {
        BigDecimal amount = Messages.amount(m);
        try (PreparedStatement ps = c.prepareStatement("""
                select i.id, i.amount, i.acquirer_id, i.business_date, exists (select 1 from net_clearing_items x where x.kind = 'CHARGEBACK' and x.original_item = i.id)
                from net_clearing_items i
                where i.kind = 'PURCHASE' and i.result in ('POSTED', 'FORCE_POSTED') and i.stan = ? and i.rrn = ? and i.masked_pan = ? and i.issuer_prefix = ?
                for update of i
                """)) {
            ps.setString(1, m.get(11));
            ps.setString(2, m.get(37));
            ps.setString(3, Pans.mask(m.get(2)));
            ps.setString(4, file.sender());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!sameAcquirer(m.get(32), rs.getString(3))) {
                        continue;
                    }
                    BigDecimal cleared = rs.getBigDecimal(2);
                    if (rs.getBoolean(5)) {
                        return Verdict.refused("ALREADY_CHARGED_BACK: this payment was already charged back");
                    }
                    if (amount.compareTo(cleared) > 0) {
                        return Verdict.refused("AMOUNT_TOO_LARGE: more than the " + cleared.toPlainString() + " that cleared");
                    }
                    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
                    if (ChronoUnit.DAYS.between(rs.getObject(4, LocalDate.class), today) > CHARGEBACK_DAYS) {
                        return Verdict.refused("TOO_OLD: the payment cleared more than " + CHARGEBACK_DAYS + " days ago");
                    }
                    return new Verdict(null, rs.getObject(1, UUID.class), file.sender(), rs.getString(3), rs.getString(3));
                }
            }
        }
        return Verdict.refused("NO_SUCH_PAYMENT: no cleared purchase with these identifiers for this issuer");
    }

    private Verdict judgeRepresentment(Connection c, Message m, ClearingFile file) throws SQLException {
        BigDecimal amount = Messages.amount(m);
        try (PreparedStatement ps = c.prepareStatement("""
                select i.id, i.amount, i.issuer_prefix, i.acquirer_id, exists (select 1 from net_clearing_items x where x.kind = 'REPRESENTMENT' and x.original_item = i.id)
                from net_clearing_items i
                where i.kind = 'CHARGEBACK' and i.result = 'CHARGEBACK_ACCEPTED' and i.stan = ? and i.rrn = ? and i.masked_pan = ? and i.acquirer_id = ?
                for update of i
                """)) {
            ps.setString(1, m.get(11));
            ps.setString(2, m.get(37));
            ps.setString(3, Pans.mask(m.get(2)));
            ps.setString(4, file.sender());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!sameAcquirer(m.get(32), rs.getString(4))) {
                        continue;
                    }
                    if (rs.getBoolean(5)) {
                        return Verdict.refused("ALREADY_REPRESENTED: this chargeback was already answered");
                    }
                    if (amount.compareTo(rs.getBigDecimal(2)) > 0) {
                        return Verdict.refused("AMOUNT_TOO_LARGE: more than the " + rs.getBigDecimal(2).toPlainString() + " charged back");
                    }
                    return new Verdict(null, rs.getObject(1, UUID.class), rs.getString(3), file.sender(), rs.getString(3));
                }
            }
        }
        return Verdict.refused("NO_SUCH_CHARGEBACK: this network forwarded no such chargeback to this acquirer");
    }

    /** DE 32 is 1-11 digits and the acquirer id of a file header may be anything: compared padded when it is numeric. */
    private static boolean sameAcquirer(String de32, String acquirerId) {
        return !acquirerId.chars().allMatch(Character::isDigit) || Messages.padAcquirer(acquirerId).equals(Messages.padAcquirer(de32));
    }

    private void insertItem(Connection c, UUID id, UUID fileRow, int record, boolean chargeback, Message m, BigDecimal amount, UUID original, String issuer,
            String acquirer, ClearingFile file, String result, String reason, UUID forward, byte[] cipher, int forwardRecord) {
        Fees fees = result.equals("REFUSED") ? null : Fees.of(amount, chargeback, config);
        BigDecimal zero = BigDecimal.ZERO;
        exec(c, "insert into net_clearing_items (id, file_row, record_no, kind, stan, rrn, masked_pan, amount, merchant_discount, interchange, "
                + "scheme_fee_acquirer, scheme_fee_issuer, result, reason, presentment_ciphertext, issuer_prefix, acquirer_id, business_date, original_item, "
                + "dispute_file, forward, forward_record) values (?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, record, chargeback ? "CHARGEBACK" : "REPRESENTMENT", m.get(11), m.get(37), Pans.mask(m.get(2)), amount,
                fees == null ? zero : fees.merchantDiscount(), fees == null ? zero : fees.interchange(), fees == null ? zero : fees.schemeFeeAcquirer(),
                fees == null ? zero : fees.schemeFeeIssuer(), result, reason, cipher, issuer, acquirer, java.sql.Date.valueOf(file.businessDate()), original,
                fileRow, forward, forward == null ? null : forwardRecord);
    }

    private void insertRejected(String name, String fileId, String sender, Direction direction, String reason) {
        try (Connection c = ds.getConnection()) {
            exec(c, "insert into net_dispute_files (id, file_name, file_id, sender, direction, status, reason, received_at) values (?, ?, ?, ?, ?, 'REJECTED', ?, ?) "
                    + "on conflict (file_name) do nothing", UUID.randomUUID(), name, fileId, sender, direction.name(),
                    reason.length() > 200 ? reason.substring(0, 200) : reason, Timestamp.from(clock.instant()));
        } catch (SQLException e) {
            throw new DataAccessException("could not record a refused dispute file", e);
        }
    }

    // ---- 2. the files for the other side -------------------------------------------------------------------

    private int writeForwards() {
        record Pending(UUID id, String fileId, String name, String target, LocalDate date, String direction) {
        }
        List<Pending> todo = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select f.id, f.file_id, f.file_name, f.target, d.business_date, d.direction from net_dispute_forwards f join net_dispute_files d on d.id = f.dispute_file "
                        + "where not f.written order by f.file_name"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                todo.add(new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the dispute files to write", e);
        }
        for (Pending p : todo) {
            List<Message> messages = new ArrayList<>();
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "select id, presentment_ciphertext from net_clearing_items where forward = ? order by forward_record")) {
                ps.setObject(1, p.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(packager.unpack(vault.decrypt(rs.getBytes(2), rs.getObject(1, UUID.class).toString())));
                    }
                }
                Path folder = p.direction().equals("CHARGEBACKS") ? dirs.acquirerIn() : dirs.issuerIn();
                codec.write(folder, p.name(), new ClearingFile(p.fileId(), p.date(), config.networkName, p.target(), "USD", messages));
                exec(c, "update net_dispute_forwards set written = true, written_at = ? where id = ?", Timestamp.from(clock.instant()), p.id());
            } catch (SQLException e) {
                throw new DataAccessException("could not write a dispute file", e);
            }
        }
        return todo.size();
    }

    // ---- 3. what the other side said about them ------------------------------------------------------------

    private int readForwardAcks() {
        record Waiting(UUID id, String fileId, String name, String direction) {
        }
        List<Waiting> waiting = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select f.id, f.file_id, f.file_name, d.direction from net_dispute_forwards f join net_dispute_files d on d.id = f.dispute_file "
                        + "where f.written and f.ack_status is null order by f.file_name"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                waiting.add(new Waiting(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the dispute files that wait for an answer", e);
        }
        int did = 0;
        for (Waiting w : waiting) {
            // the receiver answers in ITS outgoing folder: an acquirer in acquirer-out, an issuer in issuer-out
            Path ackPath = (w.direction().equals("CHARGEBACKS") ? dirs.acquirerOut() : dirs.issuerOut()).resolve(ClearingDirs.ackName(w.name()));
            if (!Files.exists(ackPath)) {
                continue;
            }
            String status;
            String reason = null;
            try {
                ClearingAck ack = codec.readAck(ackPath);
                if (!ack.fileId().equals(w.fileId())) {
                    status = "INVALID";
                    reason = "the answer is for file " + ack.fileId();
                } else if (ack.status() == ClearingAck.Status.REJECTED) {
                    status = "REJECTED";
                    reason = ack.reason();
                } else {
                    status = "ACCEPTED";
                }
            } catch (ClearingFileException e) {
                status = "INVALID";
                reason = "the answer cannot be read (" + e.reason() + ")";
            }
            exec1("update net_dispute_forwards set ack_status = ?, ack_reason = ?, acked_at = ? where id = ?", status,
                    reason == null ? null : (reason.length() > 200 ? reason.substring(0, 200) : reason), Timestamp.from(clock.instant()), w.id());
            did++;
        }
        return did;
    }

    // ---- 4. the acknowledgement to the sender ---------------------------------------------------------------

    private int writeAcknowledgements() {
        record Ready(UUID id, String name, String status, String reason, String direction) {
        }
        List<Ready> ready = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, file_name, status, reason, direction from net_dispute_files where not ack_written order by file_name"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ready.add(new Ready(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the acknowledgements to write", e);
        }
        for (Ready r : ready) {
            String base = r.name().substring(0, r.name().length() - ".dsp".length());
            ClearingAck ack;
            if (r.status().equals("REJECTED")) {
                ack = ClearingAck.rejected(base, String.valueOf(r.reason()));
            } else {
                List<ClearingAck.Item> items = new ArrayList<>();
                try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                        "select record_no, result, reason from net_clearing_items where dispute_file = ? order by record_no")) {
                    ps.setObject(1, r.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            items.add(new ClearingAck.Item(rs.getInt(1), rs.getString(2), rs.getString(3)));
                        }
                    }
                } catch (SQLException e) {
                    throw new DataAccessException("could not read the results of a dispute file", e);
                }
                ack = ClearingAck.accepted(base, items);
            }
            Direction direction = r.direction() == null ? Direction.CHARGEBACKS : Direction.valueOf(r.direction());
            codec.writeAck(ackFolder(direction), ClearingDirs.ackName(r.name()), ack);
            exec1("update net_dispute_files set ack_written = true, ack_written_at = ? where id = ?", Timestamp.from(clock.instant()), r.id());
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
            throw new DataAccessException("database error in disputes", e);
        }
    }

    private void exec1(String sql, Object... args) {
        try (Connection c = ds.getConnection()) {
            exec(c, sql, args);
        } catch (SQLException e) {
            throw new DataAccessException("database error in disputes", e);
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
            throw new DataAccessException("database error in disputes", e);
        }
    }
}
