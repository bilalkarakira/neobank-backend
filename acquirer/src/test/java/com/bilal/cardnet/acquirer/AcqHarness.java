package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.network.Network;
import com.bilal.cardnet.network.NetworkConfig;
import com.bilal.cardnet.network.IssuerLink;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import com.bilal.cardnet.shared.testing.Await;
import com.bilal.cardnet.shared.testing.FakeIssuer;
import com.bilal.cardnet.shared.testing.TestDatabase;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * An acquirer talking over real sockets to a real network and a fake issuer, all on one database, tuned to run in milliseconds.
 * The network waits 300 ms for the issuer, the acquirer waits 900 ms for the network (longer, as in the real settings).
 */
final class AcqHarness implements AutoCloseable {

    static final String PAN = "9999000000000012";
    static final String PIN = "4321";
    static final String CAFE = "MERCHANT0000001";

    final FakeIssuer issuer = new FakeIssuer();
    final DataSource ds = TestDatabase.fresh();
    final PanVault vault = new PanVault("t".repeat(40));
    final NetworkConfig networkConfig = new NetworkConfig();
    final AcquirerConfig config = new AcquirerConfig();
    Network network;
    Acquirer acquirer;
    final Path clearingRoot;
    final ClearingDirs dirs;
    final FakeIssuerClearing clearingIssuer;

    AcqHarness() throws IOException {
        this(c -> { });
    }

    AcqHarness(Consumer<AcquirerConfig> tweak) throws IOException {
        this(null, tweak, n -> { });
    }

    /** With clearing switched on: all three parties share {@code clearingRoot}; the network is polled by hand unless the test says otherwise. */
    AcqHarness(Path clearingRoot, Consumer<AcquirerConfig> tweak, Consumer<NetworkConfig> networkTweak) throws IOException {
        this.clearingRoot = clearingRoot;
        this.dirs = clearingRoot == null ? null : new ClearingDirs(clearingRoot);
        this.clearingIssuer = clearingRoot == null ? null : new FakeIssuerClearing(clearingRoot, "acq-test-secret");
        if (clearingRoot != null) {
            networkConfig.clearingDir = clearingRoot;
            networkConfig.fileSecret = "acq-test-secret";
            networkConfig.clearingPoll = Duration.ofHours(1);
            config.clearingDir = clearingRoot;
            config.fileSecret = "acq-test-secret";
            config.clearingPoll = Duration.ofHours(1);
        }
        networkTweak.accept(networkConfig);
        networkConfig.acquirerPort = 0;
        networkConfig.controlPort = 0;
        networkConfig.routes = List.of(new NetworkConfig.Route("999900", "127.0.0.1", issuer.port()));
        networkConfig.issuerTimeout = Duration.ofMillis(300);
        networkConfig.echoInterval = Duration.ofMillis(200);
        networkConfig.reconnectMin = Duration.ofMillis(30);
        networkConfig.reconnectMax = Duration.ofMillis(150);
        networkConfig.adviceInterval = Duration.ofMillis(80);
        networkConfig.reversalPoll = Duration.ofMillis(40);
        networkConfig.retryScale = 0.02;
        network = Network.start(networkConfig, ds, vault, Clock.systemUTC());
        Await.until("the issuer link is up", () -> network.link().state() == IssuerLink.State.UP);

        config.networkHost = "127.0.0.1";
        config.networkPort = network.acquirerPort();
        config.networkControlUrl = "http://127.0.0.1:" + network.controlPort();
        config.httpPort = 0;
        config.networkTimeout = Duration.ofMillis(900);
        config.reversalPoll = Duration.ofMillis(40);
        config.retryScale = 0.02;
        tweak.accept(config);
        acquirer = Acquirer.start(config, ds, vault, Clock.systemUTC());
    }

    /** One round of everything that moves clearing files: network, issuer, network again, acquirer. */
    void pump() {
        network.clearing().tick();
        clearingIssuer.tick();
        network.clearing().tick();
        acquirer.clearing().processAcknowledgements();
    }

    /** Pumps until {@code condition} holds (or fails the test after a few seconds). */
    void pumpUntil(String what, java.util.function.BooleanSupplier condition) {
        Await.until(what, () -> {
            pump();
            return condition.getAsBoolean();
        });
    }

    java.util.UUID payApproved(String amount) {
        AcquirerService.Result result = service().pay(pay(amount));
        if (!result.status().equals("APPROVED")) {
            throw new IllegalStateException("expected an approved payment, got " + result.status());
        }
        return result.id();
    }

    AcquirerService service() {
        return acquirer.service();
    }

    AcquirerService.PayRequest pay(String amount) {
        return new AcquirerService.PayRequest(PAN, "10/29", amount, CAFE, null);
    }

    AcquirerService.PayRequest payWithPin(String amount) {
        return new AcquirerService.PayRequest(PAN, "10/29", amount, CAFE, PIN);
    }

    /** Stops the acquirer and starts a new one on the same database (a restart of the process). */
    void restartAcquirer() throws IOException {
        acquirer.close();
        acquirer = Acquirer.start(config, ds, vault, Clock.systemUTC());
    }

    String status(java.util.UUID id) {
        return scalar("select status from acq_transactions where id = ?", id);
    }

    String scalar(String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    int count(String sql, Object... args) {
        return Integer.parseInt(scalar(sql, args));
    }

    @Override
    public void close() {
        acquirer.close();
        network.close();
        issuer.close();
    }
}
