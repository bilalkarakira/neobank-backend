package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.acquirer.AcquirerService.BadRequestException;
import com.bilal.cardnet.acquirer.AcquirerService.ConflictException;
import com.bilal.cardnet.acquirer.AcquirerService.NotFoundException;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.DataAccessException;
import com.bilal.cardnet.shared.Messages;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The acquirer's side of disputes (SPEC-P6a-chargebacks.md, section 3): chargebacks arrive from the network in {@code acquirer-in/*.dsp},
 * are matched to the payment they dispute and acknowledged; the shop may then REPRESENT a chargeback once, which writes a representment
 * file for the network. Money moved already at the network's settlement (a chargeback is refund-like there): this class only keeps
 * the shop's records and carries its answer.
 */
public final class AcquirerDisputes {

    private static final System.Logger LOG = System.getLogger(AcquirerDisputes.class.getName());
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);

    /** A chargeback as the console lists it. */
    public record Row(UUID id, String merchant, String maskedPan, String paymentAmount, String amount, String reason, String note, String status,
            Instant receivedAt, String representmentNote, Instant representedAt, String representmentAnswer) {
    }

    private final AcquirerConfig config;
    private final DataSource ds;
    private final AcquirerStore store;
    private final Clock clock;
    private final ClearingFileCodec codec;
    private final ClearingDirs dirs;

    public AcquirerDisputes(AcquirerConfig config, DataSource ds, AcquirerStore store, Clock clock) {
        this.config = config;
        this.ds = ds;
        this.store = store;
        this.clock = clock;
        this.codec = ClearingFileCodec.withSecret(config.fileSecret);
        this.dirs = config.clearingDir == null ? null : new ClearingDirs(config.clearingDir);
    }

    public boolean enabled() {
        return dirs != null;
    }

    /** One beat: new chargeback files, representment files not written yet, answers to the representments. Returns how much it did. */
    public synchronized int tick() {
        if (dirs == null) {
            return 0;
        }
        return receive() + writeRepresentments() + readAnswers() + writeAcknowledgements();
    }

    // ---- 1. chargebacks from the network ------------------------------------------------------------------

    private int receive() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dirs.acquirerIn())) {
            list.filter(p -> p.getFileName().toString().endsWith(ClearingDirs.DISPUTE_SUFFIX)).sorted().forEach(files::add);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list the acquirer folder ({0})", e.getClass().getSimpleName());
            return 0;
        }
        int did = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            if (count("select count(*) from acq_dispute_files where file_name = ?", name) == 0) {
                handle(path, name);
                did++;
            }
        }
        return did;
    }

    private void handle(Path path, String name) {
        ClearingFile file;
        try {
            file = codec.decode(Files.readAllBytes(path), name);
        } catch (ClearingFileException e) {
            recordFile(name, null, "REJECTED", e.reason() + ": " + e.getMessage());
            return;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read a dispute file ({0}); will retry", e.getClass().getSimpleName());
            return;
        }
        String refusal = null;
        if (!config.networkName.equals(file.sender())) {
            refusal = "UNKNOWN_SENDER: " + file.sender() + " is not the card network";
        } else if (!config.acquirerId.equals(file.receiver())) {
            refusal = "WRONG_RECEIVER: the file is addressed to " + file.receiver();
        } else if (!(file.fileId() + ClearingDirs.DISPUTE_SUFFIX).equals(name)) {
            refusal = "FILE_NAME_MISMATCH: the file id does not match the file name";
        } else if (file.presentments().stream().anyMatch(m -> ClearingFile.kindOf(m) != ClearingFile.RecordKind.CHARGEBACK)) {
            refusal = "WRONG_DIRECTION: only chargebacks come from the network";
        }
        if (refusal != null) {
            recordFile(name, file.fileId(), "REJECTED", refusal);
            return;
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                exec(c, "insert into acq_dispute_files (file_name, file_id, status, received_at) values (?, ?, 'ACCEPTED', ?)", name, file.fileId(), Timestamp.from(clock.instant()));
                int record = 0;
                for (Message m : file.presentments()) {
                    record++;
                    String result = "CHARGEBACK_ACCEPTED";
                    String reason = null;
                    UUID payment = null;
                    BigDecimal amount = Messages.amount(m);
                    try (PreparedStatement ps = c.prepareStatement("select id, coalesce(final_amount, amount) from acq_transactions "
                            + "where stan = ? and rrn = ? and kind = 'PURCHASE' and status = 'CLEARED' for update")) {
                        ps.setString(1, m.get(11));
                        ps.setString(2, m.get(37));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                result = "REFUSED";
                                reason = "UNKNOWN_PAYMENT: no cleared payment of this shop with these identifiers";
                            } else if (amount.compareTo(rs.getBigDecimal(2)) > 0) {
                                result = "REFUSED";
                                reason = "AMOUNT_TOO_LARGE: more than the " + rs.getBigDecimal(2).toPlainString() + " that cleared";
                            } else {
                                payment = rs.getObject(1, UUID.class);
                            }
                        }
                    }
                    if (payment != null && count(c, "select count(*) from acq_disputes where payment_id = ?", payment) > 0) {
                        result = "REFUSED";
                        reason = "ALREADY_DISPUTED: this payment already has a chargeback";
                        payment = null;
                    }
                    exec(c, "insert into acq_dispute_items (file_name, record_no, result, reason) values (?, ?, ?, ?)", name, record, result, reason);
                    if (payment != null) {
                        exec(c, "insert into acq_disputes (id, payment_id, file_name, record_no, amount, reason, note, status, received_at) values (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?)",
                                UUID.randomUUID(), payment, name, record, amount, ClearingFile.reasonOf(m), ClearingFile.noteOf(m), Timestamp.from(clock.instant()));
                    }
                }
                c.commit();
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not record a dispute file", e);
        }
    }

    private void recordFile(String name, String fileId, String status, String reason) {
        try (Connection c = ds.getConnection()) {
            exec(c, "insert into acq_dispute_files (file_name, file_id, status, reason, received_at) values (?, ?, ?, ?, ?) on conflict (file_name) do nothing",
                    name, fileId, status, reason.length() > 200 ? reason.substring(0, 200) : reason, Timestamp.from(clock.instant()));
        } catch (SQLException e) {
            throw new DataAccessException("could not record a refused dispute file", e);
        }
    }

    /** The acknowledgement of a chargeback file goes in the acquirer's OUT folder, named after the file. */
    private int writeAcknowledgements() {
        record Ready(String name, String fileId, String status, String reason) {
        }
        List<Ready> ready = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select file_name, file_id, status, reason from acq_dispute_files where not ack_written order by file_name"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ready.add(new Ready(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the acknowledgements to write", e);
        }
        for (Ready r : ready) {
            String base = r.name().substring(0, r.name().length() - ClearingDirs.DISPUTE_SUFFIX.length());
            ClearingAck ack;
            if (r.status().equals("REJECTED")) {
                ack = ClearingAck.rejected(base, String.valueOf(r.reason()));
            } else {
                List<ClearingAck.Item> items = new ArrayList<>();
                try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                        "select record_no, result, reason from acq_dispute_items where file_name = ? order by record_no")) {
                    ps.setString(1, r.name());
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
            codec.writeAck(dirs.acquirerOut(), ClearingDirs.ackName(r.name()), ack);
            exec1("update acq_dispute_files set ack_written = true where file_name = ?", r.name());
        }
        return ready.size();
    }

    // ---- 2. the shop represents -----------------------------------------------------------------------------

    /** Answers a chargeback with the shop's evidence. Once per chargeback; the file for the network is written right after. */
    public Row represent(UUID disputeId, String note) {
        String clean = cleanNote(note);
        Instant now = clock.instant();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long sequence;
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select nextval('representment_seq')")) {
                    rs.next();
                    sequence = rs.getLong(1);
                }
                String fileId = "R%07d".formatted(sequence);
                int moved = update(c, "update acq_disputes set status = 'REPRESENTED', rep_note = ?, represented_at = ?, rep_file_id = ? where id = ? and status = 'RECEIVED'",
                        clean, Timestamp.from(now), fileId, disputeId);
                if (moved == 0) {
                    c.rollback();
                    if (count("select count(*) from acq_disputes where id = ?", disputeId) == 0) {
                        throw new NotFoundException("No such chargeback.");
                    }
                    throw new ConflictException("This chargeback was already answered.");
                }
                c.commit();
            } catch (RuntimeException | SQLException e) {
                if (!c.isClosed()) {
                    c.rollback();
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not record the representment", e);
        }
        writeRepresentments();
        return find(disputeId);
    }

    /** At most 200 printable characters without the bar that separates the fields of the message; nothing else is accepted. */
    static String cleanNote(String note) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw new BadRequestException("Write a note: the evidence for the bank, for example \"signed receipt number 1234\".");
        }
        StringBuilder out = new StringBuilder();
        for (char ch : text.toCharArray()) {
            out.append(ch >= 0x20 && ch <= 0x7E && ch != '|' ? ch : ' ');
        }
        if (out.length() > ClearingFile.MAX_NOTE) {
            throw new BadRequestException("The note can be at most " + ClearingFile.MAX_NOTE + " characters.");
        }
        return out.toString();
    }

    /** Writes the representment files that are not on disk yet (same name, same content: a crash is repaired by the next beat). */
    private int writeRepresentments() {
        record Pending(UUID id, UUID payment, String fileId, String note, Instant at, BigDecimal amount) {
        }
        List<Pending> todo = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, payment_id, rep_file_id, rep_note, represented_at, amount from acq_disputes where status = 'REPRESENTED' and not rep_written order by represented_at");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                todo.add(new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getBigDecimal(6)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the representments to write", e);
        }
        for (Pending p : todo) {
            Message original = store.request(p.payment());
            Message representment = Message.of("1240").set(2, original.get(2)).set(3, ClearingFile.PURCHASE).set(4, Messages.de4(p.amount()))
                    .set(7, Messages.de7(p.at())).set(11, original.get(11)).set(12, HHMMSS.format(p.at())).set(13, original.get(13))
                    .set(32, original.get(32)).set(37, original.get(37)).set(41, original.get(41)).set(42, original.get(42)).set(49, original.get(49))
                    .set(48, "REP|" + p.note());
            LocalDate date = LocalDate.ofInstant(p.at(), ZoneOffset.UTC);
            codec.write(dirs.acquirerOut(), p.fileId() + ClearingDirs.DISPUTE_SUFFIX,
                    new ClearingFile(p.fileId(), date, config.acquirerId, config.networkName, "USD", List.of(representment)));
            exec1("update acq_disputes set rep_written = true where id = ?", p.id());
        }
        return todo.size();
    }

    // ---- 3. the network's answer to a representment ----------------------------------------------------------

    private int readAnswers() {
        record Waiting(UUID id, String fileId) {
        }
        List<Waiting> waiting = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, rep_file_id from acq_disputes where rep_written and rep_answer is null"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                waiting.add(new Waiting(rs.getObject(1, UUID.class), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list the representments that wait for an answer", e);
        }
        int did = 0;
        for (Waiting w : waiting) {
            Path path = dirs.acquirerIn().resolve(w.fileId() + ClearingDirs.ACK_SUFFIX);
            if (!Files.exists(path)) {
                continue;
            }
            String answer;
            try {
                ClearingAck ack = codec.readAck(path);
                if (!ack.fileId().equals(w.fileId())) {
                    answer = "UNREADABLE: the answer is for another file";
                } else if (ack.status() == ClearingAck.Status.REJECTED) {
                    answer = "REFUSED: " + ack.reason();
                } else if (!ack.items().isEmpty() && ack.items().get(0).cleared()) {
                    answer = "ACCEPTED";
                } else {
                    answer = "REFUSED: " + (ack.items().isEmpty() ? "no result" : ack.items().get(0).reason());
                }
            } catch (ClearingFileException e) {
                answer = "UNREADABLE: " + e.reason();
            }
            exec1("update acq_disputes set rep_answer = ? where id = ?", answer.length() > 200 ? answer.substring(0, 200) : answer, w.id());
            did++;
        }
        return did;
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    public List<Row> list(int limit) {
        List<Row> rows = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SELECT + " order by d.received_at desc, d.id desc limit ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(row(rs));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read the chargebacks", e);
        }
        return rows;
    }

    private Row find(UUID id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SELECT + " where d.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundException("No such chargeback.");
                }
                return row(rs);
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read a chargeback", e);
        }
    }

    private static final String SELECT = "select d.id, t.merchant_name, t.masked_pan, coalesce(t.final_amount, t.amount), d.amount, d.reason, d.note, d.status, d.received_at, "
            + "d.rep_note, d.represented_at, d.rep_answer from acq_disputes d join acq_transactions t on t.id = d.payment_id";

    private static Row row(ResultSet rs) throws SQLException {
        Timestamp represented = rs.getTimestamp(11);
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).setScale(2).toPlainString(),
                rs.getBigDecimal(5).setScale(2).toPlainString(), rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(),
                rs.getString(10), represented == null ? null : represented.toInstant(), rs.getString(12));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private int count(String sql, Object... args) {
        try (Connection c = ds.getConnection()) {
            return count(c, sql, args);
        } catch (SQLException e) {
            throw new DataAccessException("database error in disputes", e);
        }
    }

    private static int count(Connection c, String sql, Object... args) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        update(c, sql, args);
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private void exec1(String sql, Object... args) {
        try (Connection c = ds.getConnection()) {
            update(c, sql, args);
        } catch (SQLException e) {
            throw new DataAccessException("database error in disputes", e);
        }
    }
}
