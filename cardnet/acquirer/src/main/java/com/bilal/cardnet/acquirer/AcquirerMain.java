package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.shared.Database;
import com.bilal.cardnet.shared.PanVault;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Starts the acquirer and the virtual POS console. Settings come from CARDNET_* environment variables (see AcquirerConfig);
 * the database and the vault key are the same as the network's (CARDNET_DB_URL, CARDNET_DB_USER, CARDNET_DB_PASSWORD,
 * CARDNET_VAULT_KEY: the two processes may use the same database, and must use the same vault key).
 */
public final class AcquirerMain {

    private static final String DEV_VAULT_KEY = "dev-only-cardnet-vault-key-change-me-0123456789";

    private AcquirerMain() {
    }

    public static void main(String[] args) throws Exception {
        AcquirerConfig config = AcquirerConfig.fromEnvironment();
        DataSource ds = Database.dataSource(
                env("CARDNET_DB_URL", "jdbc:postgresql://localhost:5433/cardnet"),
                env("CARDNET_DB_USER", "neobank"), env("CARDNET_DB_PASSWORD", "neobank"));
        Database.migrate(ds);
        String key = env("CARDNET_VAULT_KEY", DEV_VAULT_KEY);
        if (key.equals(DEV_VAULT_KEY)) {
            System.err.println("WARNING: using the development vault key. Set CARDNET_VAULT_KEY for anything but a demo.");
        }
        Acquirer acquirer = Acquirer.start(config, ds, new PanVault(key), Clock.systemUTC());
        Runtime.getRuntime().addShutdownHook(new Thread(acquirer::close));
        System.out.println("Acquirer up: virtual POS on http://127.0.0.1:" + acquirer.consolePort() + ", network at "
                + config.networkHost + ":" + config.networkPort);
        Thread.currentThread().join();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
