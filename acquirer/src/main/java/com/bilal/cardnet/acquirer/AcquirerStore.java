package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.DataAccessException;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.Pans;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The acquirer's payments. The request is kept encrypted and without PIN data (see V2__acquirer.sql). */
public final class AcquirerStore {

    /** A payment as the console lists it. */
    public record Row(UUID id, Instant createdAt, String stan, String rrn, String merchantName, String maskedPan, BigDecimal amount,
            String status, String responseCode, String authCode, String reason, boolean timedOut, boolean pinUsed,
            String kind, BigDecimal finalAmount, UUID batchId, UUID refundOf, boolean cash, BigDecimal incrementTotal) {

        /** What the bank has authorized for this payment in all: the first amount and every approved addition. */
        public BigDecimal authorizedTotal() {
            return amount.add(incrementTotal);
        }

        /** What will clear: the final amount when there is one (a tip), else the authorized amount. */
        public BigDecimal clearingAmount() {
            return finalAmount != null ? finalAmount : authorizedTotal();
        }

        public boolean isRefund() {
            return "REFUND".equals(kind);
        }
    }

    private final DataSource ds;
    private final PanVault vault;
    private final Packager packager = new Packager();

    public AcquirerStore(DataSource ds, PanVault vault) {
        this.ds = ds;
        this.vault = vault;
    }

    /** Records a payment about to be sent (status PENDING). {@code request} is stored without its PIN data. */
    public void insert(UUID id, Message request, String merchantName, boolean pinUsed, Instant now) {
        Message withoutPin = request.copyWithMti(request.mti()).remove(52);
        String sql = """
                insert into acq_transactions (id, created_at, updated_at, stan, rrn, terminal_id, merchant_id, merchant_name, mcc, masked_pan,
                                              last4, amount, currency, de7, pin_used, status, request_ciphertext)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setString(4, request.get(11));
            ps.setString(5, request.get(37));
            ps.setString(6, request.get(41).strip());
            ps.setString(7, request.get(42).strip());
            ps.setString(8, merchantName);
            ps.setString(9, request.get(18));
            ps.setString(10, Pans.mask(request.get(2)));
            ps.setString(11, Pans.last4(request.get(2)));
            ps.setBigDecimal(12, Messages.amount(request));
            ps.setString(13, request.get(49));
            ps.setString(14, request.get(7));
            ps.setBoolean(15, pinUsed);
            ps.setBytes(16, vault.encrypt(packager.pack(withoutPin), id.toString()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not record a payment", e);
        }
    }

    public void outcome(UUID id, String status, String responseCode, String authCode, String reason, boolean timedOut, Instant now) {
        exec("update acq_transactions set status = ?, response_code = ?, auth_code = ?, reason = ?, timed_out = ?, updated_at = ? where id = ?",
                status, responseCode, authCode, reason, timedOut, Timestamp.from(now), id);
    }

    public void status(UUID id, String status, String reason, Instant now) {
        exec("update acq_transactions set status = ?, reason = coalesce(?, reason), updated_at = ? where id = ?", status, reason, Timestamp.from(now), id);
    }

    /** Moves a payment from one status to another only if it is in the first one. @return whether it moved */
    public boolean transition(UUID id, String from, String to, String reason, Instant now) {
        return exec("update acq_transactions set status = ?, reason = ?, updated_at = ? where id = ? and status = ?",
                to, reason, Timestamp.from(now), id, from) == 1;
    }

    public Optional<Row> find(UUID id) {
        List<Row> rows = query("select * from acq_transactions where id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Row> recent(int limit) {
        return query("select * from acq_transactions order by created_at desc, id desc limit ?", limit);
    }

    /** The latest PAYMENT (a refund is not something a terminal can send again). */
    public Optional<Row> latest() {
        List<Row> rows = query("select * from acq_transactions where kind = 'PURCHASE' order by created_at desc, id desc limit 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Sets the amount that will clear for an approved payment that is not in a batch yet.
     * @return whether it was changed (false: not approved any more, already batched, or not a purchase)
     */
    public boolean adjust(UUID id, BigDecimal finalAmount, Instant now) {
        return exec("update acq_transactions set final_amount = ?, updated_at = ? where id = ? and status = 'APPROVED' and kind = 'PURCHASE' and batch_id is null",
                finalAmount, Timestamp.from(now), id) == 1;
    }

    /** Records a refund (the presentment it will clear as) on {@code c}, inside the caller's transaction. */
    public void insertRefund(Connection c, UUID id, UUID refundOf, Message presentment, String merchantName, BigDecimal amount, Instant now)
            throws SQLException {
        String sql = """
                insert into acq_transactions (id, created_at, updated_at, stan, rrn, terminal_id, merchant_id, merchant_name, mcc, masked_pan,
                                              last4, amount, currency, de7, pin_used, status, kind, refund_of, request_ciphertext)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, 'REFUND_DUE', 'REFUND', ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setTimestamp(3, Timestamp.from(now));
            ps.setString(4, presentment.get(11));
            ps.setString(5, presentment.get(37));
            ps.setString(6, presentment.get(41).strip());
            ps.setString(7, presentment.get(42).strip());
            ps.setString(8, merchantName);
            ps.setString(9, presentment.get(18));
            ps.setString(10, Pans.mask(presentment.get(2)));
            ps.setString(11, Pans.last4(presentment.get(2)));
            ps.setBigDecimal(12, amount);
            ps.setString(13, presentment.get(49));
            ps.setString(14, presentment.get(7));
            ps.setObject(15, refundOf);
            ps.setBytes(16, vault.encrypt(packager.pack(presentment), id.toString()));
            ps.executeUpdate();
        }
    }

    /** The stored request of a payment, without PIN data (for a refund: the presentment it clears as). */
    public Message request(UUID id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select request_ciphertext from acq_transactions where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown payment");
                }
                return packager.unpack(vault.decrypt(rs.getBytes(1), id.toString()));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read a payment", e);
        }
    }

    // ---- additions to an authorization (a pump that tops up) ----

    /** One request for more on a payment, as the console shows it. */
    public record Increment(UUID id, UUID paymentId, Instant createdAt, String stan, String rrn, BigDecimal amount, String status,
            String responseCode, String reason) {
    }

    /** Records a request for more, before it is sent (PENDING). The stored copy never has PIN data. */
    public void insertIncrement(UUID id, UUID paymentId, Message request, Instant now) {
        Message withoutPin = request.copyWithMti(request.mti()).remove(52);
        exec("insert into acq_increments (id, payment_id, created_at, updated_at, stan, rrn, amount, status, request_ciphertext) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                id, paymentId, Timestamp.from(now), Timestamp.from(now), request.get(11), request.get(37), Messages.amount(request),
                vault.encrypt(packager.pack(withoutPin), id.toString()));
    }

    public void incrementOutcome(UUID id, String status, String responseCode, String reason, boolean timedOut, Instant now) {
        exec("update acq_increments set status = ?, response_code = ?, reason = ?, timed_out = ?, updated_at = ? where id = ?",
                status, responseCode, reason, timedOut, Timestamp.from(now), id);
    }

    public void incrementStatus(UUID id, String status, String reason, Instant now) {
        exec("update acq_increments set status = ?, reason = coalesce(?, reason), updated_at = ? where id = ?", status, reason, Timestamp.from(now), id);
    }

    /** @return whether it moved: only from the given status */
    public boolean transitionIncrement(UUID id, String from, String to, String reason, Instant now) {
        return exec("update acq_increments set status = ?, reason = ?, updated_at = ? where id = ? and status = ?", to, reason, Timestamp.from(now), id, from) == 1;
    }

    /**
     * Adds an approved increase to what the payment will clear for. Only while the payment is still open: approved, not in a batch,
     * with no final amount chosen yet, and not cash. One statement, so a day that closes at the same moment either includes it or not.
     * @return whether it was added
     */
    public boolean addToPayment(UUID paymentId, BigDecimal amount, Instant now) {
        return exec("update acq_transactions set increment_total = increment_total + ?, updated_at = ? where id = ? and status = 'APPROVED' "
                + "and kind = 'PURCHASE' and batch_id is null and final_amount is null and mcc <> '6011'", amount, Timestamp.from(now), paymentId) == 1;
    }

    /** The stored request of an increase (without PIN data): what a reversal of it is built from. */
    public Message incrementRequest(UUID id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select request_ciphertext from acq_increments where id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown increase");
                }
                return packager.unpack(vault.decrypt(rs.getBytes(1), id.toString()));
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read an increase", e);
        }
    }

    /** The increases of the given payments, oldest first, by payment. */
    public java.util.Map<UUID, List<Increment>> incrementsOf(List<UUID> paymentIds) {
        java.util.Map<UUID, List<Increment>> map = new java.util.HashMap<>();
        if (paymentIds.isEmpty()) {
            return map;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, payment_id, created_at, stan, rrn, amount, status, response_code, reason from acq_increments "
                        + "where payment_id = any (?) order by created_at, id")) {
            ps.setArray(1, c.createArrayOf("uuid", paymentIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Increment i = new Increment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant(), rs.getString(4),
                            rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9));
                    map.computeIfAbsent(i.paymentId(), k -> new ArrayList<>()).add(i);
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read increases", e);
        }
        return map;
    }

    public List<Increment> incrementsOf(UUID paymentId) {
        return incrementsOf(List.of(paymentId)).getOrDefault(paymentId, List.of());
    }

    private List<Row> query(String sql, Object... args) {
        List<Row> rows = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getString("stan"),
                            rs.getString("rrn"), rs.getString("merchant_name"), rs.getString("masked_pan"), rs.getBigDecimal("amount"),
                            rs.getString("status"), rs.getString("response_code"), rs.getString("auth_code"), rs.getString("reason"),
                            rs.getBoolean("timed_out"), rs.getBoolean("pin_used"), rs.getString("kind"), rs.getBigDecimal("final_amount"),
                            rs.getObject("batch_id", UUID.class), rs.getObject("refund_of", UUID.class), "6011".equals(rs.getString("mcc")),
                            rs.getBigDecimal("increment_total")));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read payments", e);
        }
        return rows;
    }

    private int exec(String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not update a payment", e);
        }
    }
}
