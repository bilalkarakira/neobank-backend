package com.bilal.cardnet.shared.testing;

import com.bilal.cardnet.shared.Database;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * For tests only: one Postgres container per test JVM, and a brand new migrated database for every call, so tests never
 * see each other's rows. Needs Docker running.
 */
public final class TestDatabase {

    private static PostgreSQLContainer container;

    private TestDatabase() {
    }

    public static synchronized DataSource fresh() {
        if (container == null) {
            container = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
            container.start();
        }
        String name = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = Database.dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()).getConnection();
                Statement s = c.createStatement()) {
            s.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + name;
        DataSource ds = Database.dataSource(url, container.getUsername(), container.getPassword());
        Database.migrate(ds);
        return ds;
    }
}
