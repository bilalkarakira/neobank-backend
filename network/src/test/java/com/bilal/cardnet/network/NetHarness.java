package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.testing.Await;
import com.bilal.cardnet.shared.testing.FakeIssuer;
import com.bilal.cardnet.shared.testing.TestDatabase;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A network with real sockets, a real database and a fake issuer, tuned to run in milliseconds. Each test makes its own, so
 * tests never share rows, ports or state.
 */
final class NetHarness implements AutoCloseable {

    static final String PAN = "9999000000000012";
    static final String OTHER_PAN = "9999000000000020";

    final FakeIssuer issuer = new FakeIssuer();
    final DataSource ds = TestDatabase.fresh();
    final PanVault vault = new PanVault("t".repeat(40));
    final NetworkConfig config = new NetworkConfig();
    Network network;
    private final AtomicInteger sequence = new AtomicInteger(1000);

    NetHarness() throws IOException {
        this(c -> { });
    }

    NetHarness(Consumer<NetworkConfig> tweak) throws IOException {
        config.acquirerPort = 0;
        config.controlPort = 0;
        config.routes = java.util.List.of(new NetworkConfig.Route("999900", "127.0.0.1", issuer.port()));
        config.issuerTimeout = Duration.ofMillis(500);
        config.echoInterval = Duration.ofMillis(200);
        config.reconnectMin = Duration.ofMillis(30);
        config.reconnectMax = Duration.ofMillis(150);
        config.adviceInterval = Duration.ofMillis(80);
        config.reversalPoll = Duration.ofMillis(40);
        config.retryScale = 0.02;
        config.idleTimeout = Duration.ofSeconds(10);
        tweak.accept(config);
        network = Network.start(config, ds, vault, Clock.systemUTC());
        waitForLink();
    }

    void waitForLink() {
        Await.until("the issuer link is up", () -> network.link().state() == IssuerLink.State.UP);
    }

    void waitForLinkDown() {
        Await.until("the issuer link is down", () -> network.link().state() == IssuerLink.State.DOWN);
    }

    /** Stops the network and starts a new one on the same database (a restart of the process). */
    void restartNetwork() throws IOException {
        network.close();
        network = Network.start(config, ds, vault, Clock.systemUTC());
    }

    Acquirer acquirer() throws IOException {
        return new Acquirer(network.acquirerPort());
    }

    String nextStan() {
        return "%06d".formatted(sequence.incrementAndGet());
    }

    /** A purchase with a fresh STAN and RRN. */
    Message purchase(String pan, String amount) {
        String stan = nextStan();
        return purchase(pan, amount, stan, "%012d".formatted(Long.parseLong(stan)));
    }

    static Message purchase(String pan, String amount, String stan, String rrn) {
        return Message.of("0100").set(2, pan).set(3, "000000").set(4, Messages.de4(new java.math.BigDecimal(amount)))
                .set(7, "1005120000").set(11, stan).set(12, "120000").set(13, "1005").set(14, "2910").set(18, "5814").set(22, "051")
                .set(25, "00").set(32, "123456").set(37, rrn).set(41, "TERM0001").set(42, "MERCHANT0000001")
                .set(43, "%-40s".formatted("Corner Cafe")).set(49, "840");
    }

    static String code(Message response) {
        return response.get(39);
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

    byte[] bytes(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBytes(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    int count(String sql, Object... args) {
        return Integer.parseInt(scalar(sql, args));
    }

    @Override
    public void close() {
        network.close();
        issuer.close();
    }

    /** The acquirer side of one connection to the network. */
    static final class Acquirer implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        private final Packager packager = new Packager();

        Acquirer(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(10_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        Acquirer signedOn() throws IOException {
            Message answer = exchange(Messages.signOn("000001", "1005120000"));
            if (!"00".equals(code(answer))) {
                throw new IllegalStateException("sign-on refused: " + code(answer));
            }
            return this;
        }

        Message exchange(Message request) throws IOException {
            Frame.write(out, packager.pack(request));
            return receive();
        }

        Message receive() throws IOException {
            byte[] frame = Frame.read(in);
            if (frame == null) {
                throw new IOException("closed by the network");
            }
            return packager.unpack(frame);
        }

        boolean closedByNetwork() {
            try {
                return in.read() < 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
