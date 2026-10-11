package com.bilal.cardnet.shared;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

/**
 * Plain JDBC, no pool: this is a simulation with a handful of requests, and every operation opens its own short connection.
 * Both processes call {@link #migrate} at start; Flyway's own lock makes that safe when they start together.
 */
public final class Database {

    private Database() {
    }

    public static DataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }
}
