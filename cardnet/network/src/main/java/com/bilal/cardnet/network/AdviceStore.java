package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.DataAccessException;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.Pans;
import com.bilal.cardnet.shared.PanVault;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments the network approved by itself, waiting to be told to the issuer. Status flow: PENDING -> SENDING (claimed by the
 * delivery worker) -> DELIVERED, or back to PENDING if the attempt failed; PENDING -> CANCELLED when the payment was reversed
 * before the issuer heard of it, or when the issuer answered the original after all. Only PENDING can be cancelled: once an
 * advice is on its way it is the issuer's to deal with.
 */
public final class AdviceStore {

    public record Advice(UUID id, String fingerprint, String acquirerId, String stan, String rrn, String de7, BigDecimal amount,
            String authCode, byte[] ciphertext, String status) {
    }

    private final DataSource ds;
    private final PanVault vault;
    private final Packager packager = new Packager();

    public AdviceStore(DataSource ds, PanVault vault) {
        this.ds = ds;
        this.vault = vault;
    }

    public Optional<Advice> findByRequest(String acquirerId, String stan, String rrn) {
        return one("select * from net_standin_advices where acquirer_id = ? and stan = ? and rrn = ?", acquirerId, stan, rrn);
    }

    /** Stores the approval. If the same request was already approved (a race), the existing advice is returned. */
    public Advice create(Message request, String fingerprint, String authCode, Instant now) {
        UUID id = UUID.randomUUID();
        String sql = """
                insert into net_standin_advices (id, fingerprint, masked_pan, acquirer_id, stan, rrn, de7, amount, auth_code,
                                                 request_ciphertext, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?) on conflict (acquirer_id, stan, rrn) do nothing
                """;
        String acquirer = Messages.padAcquirer(request.get(32));
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, fingerprint);
            ps.setString(3, Pans.mask(request.get(2)));
            ps.setString(4, acquirer);
            ps.setString(5, request.get(11));
            ps.setString(6, request.get(37));
            ps.setString(7, request.get(7));
            ps.setBigDecimal(8, Messages.amount(request));
            ps.setString(9, authCode);
            ps.setBytes(10, vault.encrypt(packager.pack(request), id.toString()));
            ps.setTimestamp(11, Timestamp.from(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not store a stand-in advice", e);
        }
        return findByRequest(acquirer, request.get(11), request.get(37)).orElseThrow();
    }

    /** What this card has been approved for by stand-in since {@code since} (cancelled advices do not count). */
    public BigDecimal usedSince(String fingerprint, Instant since) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select coalesce(sum(amount), 0) from net_standin_advices where fingerprint = ? and created_at >= ? and status <> 'CANCELLED'")) {
            ps.setString(1, fingerprint);
            ps.setTimestamp(2, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not total the stand-in approvals", e);
        }
    }

    /** Takes the oldest PENDING advice for delivery (PENDING -> SENDING), or empty. Safe with several workers. */
    public Optional<Advice> claimNext() {
        String sql = """
                update net_standin_advices set status = 'SENDING', attempts = attempts + 1
                where id = (select id from net_standin_advices where status = 'PENDING' order by created_at limit 1 for update skip locked)
                returning *
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new DataAccessException("could not claim an advice", e);
        }
    }

    public void markDelivered(UUID id, Instant now) {
        exec("update net_standin_advices set status = 'DELIVERED', delivered_at = ? where id = ? and status = 'SENDING'", Timestamp.from(now), id);
    }

    /** The attempt failed: back to PENDING. */
    public void release(UUID id) {
        exec("update net_standin_advices set status = 'PENDING' where id = ? and status = 'SENDING'", id);
    }

    /** Cancels a still-PENDING advice found by card, acquirer, STAN and DE 7 (how a reversal names its original). */
    public boolean cancelPending(String fingerprint, String acquirerId, String stan, String de7, String reason) {
        return exec("update net_standin_advices set status = 'CANCELLED', cancel_reason = ? "
                + "where fingerprint = ? and acquirer_id = ? and stan = ? and de7 = ? and status = 'PENDING'",
                reason, fingerprint, acquirerId, stan, de7) == 1;
    }

    /** Cancels a still-PENDING advice found by its request key. */
    public boolean cancelPendingByRequest(String acquirerId, String stan, String rrn, String reason) {
        return exec("update net_standin_advices set status = 'CANCELLED', cancel_reason = ? "
                + "where acquirer_id = ? and stan = ? and rrn = ? and status = 'PENDING'", reason, acquirerId, stan, rrn) == 1;
    }

    /** After a crash: advices that were being sent are not known to have arrived, so they go back to PENDING. */
    public int recoverSending() {
        return exec("update net_standin_advices set status = 'PENDING' where status = 'SENDING'");
    }

    public int countPending() {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from net_standin_advices where status in ('PENDING', 'SENDING')"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DataAccessException("could not count advices", e);
        }
    }

    /** The original request of an advice. */
    public Message request(Advice advice) {
        return packager.unpack(vault.decrypt(advice.ciphertext(), advice.id().toString()));
    }

    private Optional<Advice> one(String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read an advice", e);
        }
    }

    private int exec(String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not update an advice", e);
        }
    }

    private static Advice read(ResultSet rs) throws SQLException {
        return new Advice(rs.getObject("id", UUID.class), rs.getString("fingerprint"), rs.getString("acquirer_id"), rs.getString("stan"),
                rs.getString("rrn"), rs.getString("de7"), rs.getBigDecimal("amount"), rs.getString("auth_code"),
                rs.getBytes("request_ciphertext"), rs.getString("status"));
    }
}
