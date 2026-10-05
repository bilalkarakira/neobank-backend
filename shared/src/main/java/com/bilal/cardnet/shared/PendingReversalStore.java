package com.bilal.cardnet.shared;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable queue of reversals that must reach the issuer. The original request is kept encrypted (PanVault) because
 * rebuilding the reversal needs its card number; the row holds only the masked number.
 */
public final class PendingReversalStore {

    /** A reversal that is due for an attempt. */
    public record Due(UUID id, byte[] ciphertext, int attempts, Instant deadline, UUID acqTransactionId) {
    }

    /** Where a reversal stands, for display. */
    public record Status(UUID id, String status, int attempts, String reason, String lastResponse) {
    }

    private final DataSource ds;
    private final PanVault vault;
    private final Packager packager = new Packager();

    public PendingReversalStore(DataSource ds, PanVault vault) {
        this.ds = ds;
        this.vault = vault;
    }

    /**
     * Queues the reversal of {@code original}, due now. One per original and owner: asking again returns the existing one.
     *
     * @return the id of the queued (or already queued) reversal
     */
    public UUID add(String owner, Message original, BigDecimal amount, String reason, Instant now, Duration deadline,
            UUID acqTransactionId) {
        UUID id = UUID.randomUUID();
        // a reversal never needs PIN data, so none is kept, not even encrypted
        Message withoutPin = original.copyWithMti(original.mti()).remove(52);
        byte[] ciphertext = vault.encrypt(packager.pack(withoutPin), id.toString());
        String sql = """
                insert into pending_reversals (id, owner, stan, rrn, masked_pan, amount, request_ciphertext, status, attempts,
                                               next_attempt_at, deadline_at, reason, acq_transaction_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?, ?)
                on conflict (owner, stan, rrn) do nothing
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, owner);
            ps.setString(3, original.get(11));
            ps.setString(4, original.get(37));
            ps.setString(5, Pans.mask(original.get(2)));
            ps.setBigDecimal(6, amount);
            ps.setBytes(7, ciphertext);
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setTimestamp(9, Timestamp.from(now.plus(deadline)));
            ps.setString(10, reason);
            ps.setObject(11, acqTransactionId);
            ps.setTimestamp(12, Timestamp.from(now));
            if (ps.executeUpdate() == 1) {
                return id;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not queue a reversal", e);
        }
        return find(owner, original.get(11), original.get(37)).orElseThrow().id();
    }

    public List<Due> due(String owner, Instant now, int limit) {
        String sql = """
                select id, request_ciphertext, attempts, deadline_at, acq_transaction_id from pending_reversals
                where owner = ? and status = 'PENDING' and next_attempt_at <= ? order by next_attempt_at limit ?
                """;
        List<Due> result = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setTimestamp(2, Timestamp.from(now));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Due(rs.getObject(1, UUID.class), rs.getBytes(2), rs.getInt(3),
                            rs.getTimestamp(4).toInstant(), rs.getObject(5, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read the reversal queue", e);
        }
        return result;
    }

    /** The original request of a queued reversal. */
    public Message original(Due due) {
        return packager.unpack(vault.decrypt(due.ciphertext(), due.id().toString()));
    }

    public void markDone(UUID id, Instant now, String lastResponse) {
        update("update pending_reversals set status = 'DONE', completed_at = ?, last_response = ? where id = ?", now, lastResponse, id);
    }

    public void markFailed(UUID id, Instant now, String reason, String lastResponse) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "update pending_reversals set status = 'FAILED', completed_at = ?, reason = ?, last_response = ? where id = ?")) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setString(2, reason);
            ps.setString(3, lastResponse);
            ps.setObject(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not fail a reversal", e);
        }
    }

    public void reschedule(UUID id, int attempts, Instant next, String lastResponse) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "update pending_reversals set attempts = ?, next_attempt_at = ?, last_response = ? where id = ?")) {
            ps.setInt(1, attempts);
            ps.setTimestamp(2, Timestamp.from(next));
            ps.setString(3, lastResponse);
            ps.setObject(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not reschedule a reversal", e);
        }
    }

    public Optional<Status> find(String owner, String stan, String rrn) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select id, status, attempts, reason, last_response from pending_reversals where owner = ? and stan = ? and rrn = ?")) {
            ps.setString(1, owner);
            ps.setString(2, stan);
            ps.setString(3, rrn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Status(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not read a reversal", e);
        }
    }

    public int countPending(String owner) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "select count(*) from pending_reversals where owner = ? and status = 'PENDING'")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not count reversals", e);
        }
    }

    private void update(String sql, Instant now, String lastResponse, UUID id) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setString(2, lastResponse);
            ps.setObject(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("could not update a reversal", e);
        }
    }
}
