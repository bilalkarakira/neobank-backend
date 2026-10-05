package com.bilal.cardnet.shared;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** STAN and RRN values from database sequences, so they stay unique across restarts and across the processes. */
public final class Sequences {

    private Sequences() {
    }

    /** Six digits, 000001 to 999999, then around again (as real STANs do). */
    public static String nextStan(DataSource ds) {
        return "%06d".formatted(next(ds, "stan_seq"));
    }

    /** Twelve digits, unique per call. */
    public static String nextRrn(DataSource ds) {
        return "%012d".formatted(next(ds, "rrn_seq"));
    }

    private static long next(DataSource ds, String sequence) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select nextval('" + sequence + "')")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new DataAccessException("could not read " + sequence, e);
        }
    }
}
