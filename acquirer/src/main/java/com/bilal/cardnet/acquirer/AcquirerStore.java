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
            String status, String responseCode, String authCode, String reason, boolean timedOut, boolean pinUsed) {
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

    public Optional<Row> latest() {
        List<Row> rows = recent(1);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The stored request of a payment, without PIN data. */
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
                            rs.getBoolean("timed_out"), rs.getBoolean("pin_used")));
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
