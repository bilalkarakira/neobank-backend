package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.Pans;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/** One row per request the network handled. A failure to write the log must never fail a payment, so errors are only logged. */
public final class NetTransactionLog {

    public enum Route { ISSUER, STAND_IN, REFUSED, CANCELLED }

    private static final System.Logger LOG = System.getLogger(NetTransactionLog.class.getName());

    private final DataSource ds;
    private final Clock clock;

    public NetTransactionLog(DataSource ds, Clock clock) {
        this.ds = ds;
        this.clock = clock;
    }

    public void record(Message request, Route route, String issuerCode, String answerCode, long latencyMs, boolean late, String note) {
        String sql = """
                insert into net_transactions (id, created_at, acquirer_id, mti, stan, rrn, masked_pan, amount, route, issuer_code,
                                              answer_code, latency_ms, late, note)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setTimestamp(2, Timestamp.from(clock.instant()));
            ps.setString(3, request.has(32) ? Messages.padAcquirer(request.get(32)) : null);
            ps.setString(4, request.mti());
            ps.setString(5, request.get(11));
            ps.setString(6, request.get(37));
            ps.setString(7, request.has(2) ? Pans.mask(request.get(2)) : null);
            ps.setBigDecimal(8, request.has(4) ? parseAmount(request) : null);
            ps.setString(9, route.name());
            ps.setString(10, issuerCode);
            ps.setString(11, answerCode);
            ps.setInt(12, (int) latencyMs);
            ps.setBoolean(13, late);
            ps.setString(14, note == null ? null : note.substring(0, Math.min(80, note.length())));
            ps.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not write the transaction log ({0})", e.getClass().getSimpleName());
        }
    }

    private static BigDecimal parseAmount(Message request) {
        try {
            return Messages.amount(request);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
