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
import com.bilal.cardnet.shared.Sequences;

import javax.sql.DataSource;
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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The acquirer's side of capture and clearing: change the amount of an approved payment
 * (a tip), refund a cleared one, close the day into one clearing file, and read the network's acknowledgement of it.
 *
 * <p>Closing the day is two steps so that a crash cannot lose or double a payment: first, in ONE database transaction, every
 * approved payment that is not in a batch yet is claimed and the batch row is created (status CREATED); then the file is
 * written and the batch becomes SUBMITTED. A batch that stayed CREATED (a crash in between) has its file written again, with
 * the same name and the same content, when the acquirer starts.
 */
public final class AcquirerClearing {

    private static final System.Logger LOG = System.getLogger(AcquirerClearing.class.getName());
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);

    /** The batch a "close the day" made. */
    public record BatchResult(UUID id, String fileId, String fileName, int count, long controlTotal) {
    }

    /** A batch as the console lists it. */
    public record BatchRow(UUID id, String fileId, String fileName, LocalDate businessDate, int count, long controlTotal, String status,
            Instant createdAt, Instant ackedAt, String ackReason) {
    }

    private final AcquirerConfig config;
    private final DataSource ds;
    private final AcquirerStore store;
    private final Clock clock;
    private final ClearingFileCodec codec;
    private final ClearingDirs dirs;

    public AcquirerClearing(AcquirerConfig config, DataSource ds, AcquirerStore store, Clock clock) {
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

    private void requireEnabled() {
        if (dirs == null) {
            throw new ConflictException("Clearing is switched off: no clearing folder is configured.");
        }
    }

    // ---- the tip ----

    /** Sets the amount that will clear for an approved payment. At most the authorized amount plus the tip limit. */
    public AcquirerStore.Row adjust(UUID id, String amountText) {
        BigDecimal amount = amount(amountText);
        AcquirerStore.Row row = store.find(id).orElseThrow(() -> new NotFoundException("No such payment."));
        if (row.isRefund()) {
            throw new ConflictException("A refund's amount cannot be changed.");
        }
        if (row.cash()) {
            throw new ConflictException("The amount of a cash withdrawal cannot be changed: the machine pays exactly what was asked.");
        }
        BigDecimal limit = row.authorizedTotal().multiply(BigDecimal.ONE.add(config.tipLimit)).setScale(2, java.math.RoundingMode.DOWN);
        if (amount.compareTo(limit) > 0) {
            throw new BadRequestException("The final amount can be at most " + limit.toPlainString() + " (the authorized "
                    + row.authorizedTotal().toPlainString() + " plus " + config.tipLimit.movePointRight(2).stripTrailingZeros().toPlainString() + "%).");
        }
        if (!store.adjust(id, amount, clock.instant())) {
            throw new ConflictException("Only an approved payment that is not in a batch yet can be changed; this one is "
                    + row.status().toLowerCase().replace('_', ' ') + ".");
        }
        return store.find(id).orElseThrow();
    }

    // ---- refunds ----

    /** Refunds all or part of a CLEARED purchase. The refund joins the next batch. */
    public AcquirerStore.Row refund(UUID purchaseId, String amountText) {
        BigDecimal amount = amount(amountText);
        UUID refundId = UUID.randomUUID();
        Instant now = clock.instant();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                String status;
                String kind;
                BigDecimal cleared;
                String merchantName;
                try (PreparedStatement ps = c.prepareStatement(
                        "select status, kind, coalesce(final_amount, amount + increment_total), merchant_name, mcc from acq_transactions where id = ? for update")) {
                    ps.setObject(1, purchaseId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new NotFoundException("No such payment.");
                        }
                        status = rs.getString(1);
                        kind = rs.getString(2);
                        cleared = rs.getBigDecimal(3);
                        merchantName = rs.getString(4);
                        if ("6011".equals(rs.getString(5))) {
                            throw new ConflictException("A cash withdrawal cannot be refunded: the cardholder can dispute it with the bank.");
                        }
                    }
                }
                if (!"PURCHASE".equals(kind) || !"CLEARED".equals(status)) {
                    throw new ConflictException("Only a cleared payment can be refunded; this one is " + status.toLowerCase().replace('_', ' ') + ".");
                }
                BigDecimal refunded;
                try (PreparedStatement ps = c.prepareStatement(
                        "select coalesce(sum(amount), 0) from acq_transactions where refund_of = ? and status <> 'CLEARING_REJECTED'")) {
                    ps.setObject(1, purchaseId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        refunded = rs.getBigDecimal(1);
                    }
                }
                BigDecimal left = cleared.subtract(refunded);
                if (amount.compareTo(left) > 0) {
                    throw new BadRequestException("Only " + left.setScale(2).toPlainString() + " of the " + cleared.setScale(2).toPlainString()
                            + " cleared is left to refund.");
                }
                Message original = store.request(purchaseId);
                Message presentment = Message.of("1240").set(2, original.get(2)).set(3, ClearingFile.REFUND).set(4, Messages.de4(amount))
                        .set(7, Messages.de7(now)).set(11, Sequences.nextStan(ds)).set(12, HHMMSS.format(now)).set(13, MMDD.format(now))
                        .set(14, original.get(14)).set(18, original.get(18)).set(32, original.get(32)).set(37, Sequences.nextRrn(ds))
                        .set(41, original.get(41)).set(42, original.get(42)).set(43, original.get(43)).set(49, original.get(49));
                store.insertRefund(c, refundId, purchaseId, presentment, merchantName, amount, now);
                c.commit();
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not record a refund", e);
        }
        return store.find(refundId).orElseThrow();
    }

    // ---- closing the day ----

    /** Puts every approved payment and due refund that is not in a batch yet into one file. Empty when there is nothing to send. */
    public Optional<BatchResult> closeDay() {
        requireEnabled();
        Instant now = clock.instant();
        UUID batchId = UUID.randomUUID();
        String fileId;
        int count;
        long total;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                List<UUID> claimed = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("select id from acq_transactions where status in ('APPROVED', 'REFUND_DUE') "
                        + "and batch_id is null order by created_at, id for update");
                        ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        claimed.add(rs.getObject(1, UUID.class));
                    }
                }
                if (claimed.isEmpty()) {
                    c.rollback();
                    return Optional.empty();
                }
                long sequence;
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select nextval('batch_seq')")) {
                    rs.next();
                    sequence = rs.getLong(1);
                }
                LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
                fileId = "ACQ" + DATE.format(date) + "-" + "%04d".formatted(sequence);
                int record = 0;
                for (UUID id : claimed) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "update acq_transactions set status = 'BATCHED', batch_id = ?, batch_record = ?, updated_at = ? where id = ?")) {
                        ps.setObject(1, batchId);
                        ps.setInt(2, ++record);
                        ps.setTimestamp(3, Timestamp.from(now));
                        ps.setObject(4, id);
                        ps.executeUpdate();
                    }
                }
                count = claimed.size();
                try (PreparedStatement ps = c.prepareStatement(
                        "select sum((coalesce(final_amount, amount + increment_total) * 100)::bigint) from acq_transactions where batch_id = ?")) {
                    ps.setObject(1, batchId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        total = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("insert into acq_batches (id, file_id, file_name, business_date, item_count, "
                        + "control_total, status, created_at) values (?, ?, ?, ?, ?, ?, 'CREATED', ?)")) {
                    ps.setObject(1, batchId);
                    ps.setString(2, fileId);
                    ps.setString(3, fileId + ClearingDirs.FILE_SUFFIX);
                    ps.setObject(4, date);
                    ps.setInt(5, count);
                    ps.setLong(6, total);
                    ps.setTimestamp(7, Timestamp.from(now));
                    ps.executeUpdate();
                }
                c.commit();
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not close the day", e);
        }
        writeBatch(batchId);
        return Optional.of(new BatchResult(batchId, fileId, fileId + ClearingDirs.FILE_SUFFIX, count, total));
    }

    /** Writes the file of a CREATED batch and marks it SUBMITTED. Writing it twice gives the same file. */
    private void writeBatch(UUID batchId) {
        String fileId;
        LocalDate date;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select file_id, business_date from acq_batches where id = ? and status = 'CREATED'")) {
            ps.setObject(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                fileId = rs.getString(1);
                date = rs.getObject(2, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read a batch", e);
        }
        List<Message> presentments = new ArrayList<>();
        for (AcquirerStore.Row row : rowsOf(batchId)) {
            presentments.add(presentment(row));
        }
        codec.write(dirs.acquirerOut(), fileId + ClearingDirs.FILE_SUFFIX,
                new ClearingFile(fileId, date, config.acquirerId, config.networkName, "USD", presentments));
        exec("update acq_batches set status = 'SUBMITTED', submitted_at = ? where id = ? and status = 'CREATED'", Timestamp.from(clock.instant()), batchId);
    }

    /** The first presentment of a payment: the authorization's own identifiers, the FINAL amount, the authorization code. */
    private Message presentment(AcquirerStore.Row row) {
        Message request = store.request(row.id());
        if (row.isRefund()) {
            return request;
        }
        return Message.of("1240").set(2, request.get(2)).set(3, request.get(3)).set(4, Messages.de4(row.clearingAmount()))
                .set(7, request.get(7)).set(11, request.get(11)).set(12, request.get(12)).set(13, request.get(13)).set(14, request.get(14))
                .set(18, request.get(18)).set(32, request.get(32)).set(37, request.get(37)).set(38, row.authCode()).set(41, request.get(41))
                .set(42, request.get(42)).set(43, request.get(43)).set(49, request.get(49));
    }

    /** Called when the acquirer starts: a batch left CREATED by a crash gets its file. */
    public int repairUnwrittenBatches() {
        if (dirs == null) {
            return 0;
        }
        List<UUID> unwritten = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select id from acq_batches where status = 'CREATED'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                unwritten.add(rs.getObject(1, UUID.class));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not list batches", e);
        }
        unwritten.forEach(this::writeBatch);
        return unwritten.size();
    }

    // ---- the network's acknowledgement ----

    /** One beat: reads the acknowledgement of every submitted batch that has one. Returns how many batches it settled. */
    public synchronized int processAcknowledgements() {
        if (dirs == null) {
            return 0;
        }
        int settled = 0;
        for (BatchRow batch : batches(50)) {
            if (!batch.status().equals("SUBMITTED")) {
                continue;
            }
            Path path = dirs.acquirerIn().resolve(ClearingDirs.ackName(batch.fileName()));
            if (!Files.exists(path)) {
                continue;
            }
            ClearingAck ack;
            try {
                ack = codec.readAck(path);
                if (!ack.fileId().equals(batch.fileId())) {
                    throw new ClearingFileException(ClearingFileException.Reason.BAD_HEADER, "the acknowledgement is for file " + ack.fileId());
                }
            } catch (ClearingFileException e) {
                String why = "unreadable acknowledgement (" + e.reason() + ")";
                if (!why.equals(batch.ackReason())) {
                    LOG.log(System.Logger.Level.WARNING, "batch {0}: {1}", batch.fileId(), why);
                    exec("update acq_batches set ack_reason = ? where id = ?", why, batch.id());
                }
                continue;
            }
            if (apply(batch, ack)) {
                settled++;
            }
        }
        return settled;
    }

    private boolean apply(BatchRow batch, ClearingAck ack) {
        Instant now = clock.instant();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                String batchStatus = ack.status() == ClearingAck.Status.ACCEPTED ? "ACCEPTED" : "REJECTED";
                int moved = update(c, "update acq_batches set status = ?, acked_at = ?, ack_reason = ? where id = ? and status = 'SUBMITTED'",
                        batchStatus, Timestamp.from(now), ack.status() == ClearingAck.Status.REJECTED ? truncate(ack.reason(), 200) : null, batch.id());
                if (moved == 0) {
                    c.rollback();
                    return false;
                }
                if (ack.status() == ClearingAck.Status.REJECTED) {
                    // nothing was processed: every payment is as it was before the batch and can go into the next file
                    update(c, "update acq_transactions set status = case kind when 'PURCHASE' then 'APPROVED' else 'REFUND_DUE' end, batch_id = null, "
                            + "batch_record = null, reason = ?, updated_at = ? where batch_id = ? and status = 'BATCHED'",
                            truncate("the network refused the file: " + ack.reason(), 80), Timestamp.from(now), batch.id());
                } else {
                    for (AcquirerStore.Row row : rowsOf(batch.id())) {
                        if (!row.status().equals("BATCHED")) {
                            continue;
                        }
                        int record = recordOf(c, row.id());
                        ClearingAck.Item item = ack.item(record);
                        String status = item != null && item.cleared() ? "CLEARED" : "CLEARING_REJECTED";
                        String reason = item == null ? "the acknowledgement has no result for this payment"
                                : item.cleared() ? item.result() : item.result() + ": " + item.reason();
                        update(c, "update acq_transactions set status = ?, reason = ?, updated_at = ? where id = ? and status = 'BATCHED'",
                                status, truncate(reason, 80), Timestamp.from(now), row.id());
                    }
                }
                c.commit();
                return true;
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not apply an acknowledgement", e);
        }
    }

    // ---- reading ----

    public List<BatchRow> batches(int limit) {
        List<BatchRow> rows = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, file_id, file_name, business_date, item_count, control_total, status, created_at, acked_at, ack_reason "
                        + "from acq_batches order by created_at desc, id desc limit ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp acked = rs.getTimestamp(9);
                    rows.add(new BatchRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                            rs.getInt(5), rs.getLong(6), rs.getString(7), rs.getTimestamp(8).toInstant(), acked == null ? null : acked.toInstant(),
                            rs.getString(10)));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read batches", e);
        }
        return rows;
    }

    private List<AcquirerStore.Row> rowsOf(UUID batchId) {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id from acq_transactions where batch_id = ? order by batch_record")) {
            ps.setObject(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read a batch", e);
        }
        List<AcquirerStore.Row> rows = new ArrayList<>();
        for (UUID id : ids) {
            store.find(id).ifPresent(rows::add);
        }
        return rows;
    }

    private static int recordOf(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select batch_record from acq_transactions where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ---- helpers ----

    private static BigDecimal amount(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (!AMOUNT.matcher(trimmed).matches() || new BigDecimal(trimmed).signum() <= 0) {
            throw new BadRequestException("The amount must be a positive number with at most two decimals, for example 12.50.");
        }
        return new BigDecimal(trimmed).setScale(2);
    }

    private void exec(String sql, Object... args) {
        try (Connection c = ds.getConnection()) {
            update(c, sql, args);
        } catch (SQLException e) {
            throw new DataAccessException("database error in clearing", e);
        }
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }
}
