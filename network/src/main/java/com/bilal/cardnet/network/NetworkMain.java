package com.bilal.cardnet.network;

import com.bilal.cardnet.shared.Database;
import com.bilal.cardnet.shared.PanVault;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Starts the network switch. Settings come from CARDNET_* environment variables (see NetworkConfig); the database is
 * CARDNET_DB_URL / CARDNET_DB_USER / CARDNET_DB_PASSWORD (default: the cardnet database in the local Postgres container) and
 * the vault key is CARDNET_VAULT_KEY (a development default is built in: change it anywhere that matters).
 */
public final class NetworkMain {

    private static final String DEV_VAULT_KEY = "dev-only-cardnet-vault-key-change-me-0123456789";

    private NetworkMain() {
    }

    public static void main(String[] args) throws Exception {
        NetworkConfig config = NetworkConfig.fromEnvironment();
        DataSource ds = Database.dataSource(
                env("CARDNET_DB_URL", "jdbc:postgresql://localhost:5433/cardnet"),
                env("CARDNET_DB_USER", "neobank"), env("CARDNET_DB_PASSWORD", "neobank"));
        Database.migrate(ds);
        String key = env("CARDNET_VAULT_KEY", DEV_VAULT_KEY);
        if (key.equals(DEV_VAULT_KEY)) {
            System.err.println("WARNING: using the development vault key. Set CARDNET_VAULT_KEY for anything but a demo.");
        }
        Network network = Network.start(config, ds, new PanVault(key), Clock.systemUTC());
        Runtime.getRuntime().addShutdownHook(new Thread(network::close));
        System.out.println("Network up: acquirers connect to 127.0.0.1:" + network.acquirerPort()
                + ", control API on http://127.0.0.1:" + network.controlPort() + ", issuer route " + config.routes);
        Thread.currentThread().join();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
