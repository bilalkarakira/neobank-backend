package com.bilal.cardnet.network;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Every setting of the network, with the defaults of SPEC-P3-network.md. Tests change the public fields they need. */
public final class NetworkConfig {

    /** Card numbers that start with {@code prefix} are routed to the issuer at host:port. */
    public record Route(String prefix, String host, int port) {
    }

    public int acquirerPort = 8601;
    public int controlPort = 9100;
    public List<Route> routes = List.of(new Route("999900", "127.0.0.1", 8583));

    /** How long the network waits for the issuer before it answers on its own. Shorter than the acquirer's own wait. */
    public Duration issuerTimeout = Duration.ofSeconds(3);
    /** An echo test (0800/301) goes to the issuer this often; two misses in a row drop the link. */
    public Duration echoInterval = Duration.ofSeconds(30);
    public Duration reconnectMin = Duration.ofSeconds(1);
    public Duration reconnectMax = Duration.ofSeconds(30);

    public BigDecimal standInMax = new BigDecimal("50.00");
    public BigDecimal standInDailyCap = new BigDecimal("100.00");
    public Set<String> blockedMcc = Set.of("7995", "4829", "6051");

    /** How often undelivered stand-in advices are tried (and at once whenever the link comes up). */
    public Duration adviceInterval = Duration.ofSeconds(5);
    public Duration reversalPoll = Duration.ofSeconds(1);
    public Duration reversalDeadline = Duration.ofHours(24);
    /** 1.0 is the real schedule (1, 2, 4, 8, 16 s, then 60 s); tests shrink it. */
    public double retryScale = 1.0;

    public Duration idleTimeout = Duration.ofSeconds(120);
    public int maxConnections = 50;

    /** Reads CARDNET_* environment variables over the defaults. */
    public static NetworkConfig fromEnvironment() {
        NetworkConfig c = new NetworkConfig();
        c.acquirerPort = intEnv("CARDNET_ACQUIRER_PORT", c.acquirerPort);
        c.controlPort = intEnv("CARDNET_CONTROL_PORT", c.controlPort);
        c.issuerTimeout = Duration.ofMillis(intEnv("CARDNET_ISSUER_TIMEOUT_MS", (int) c.issuerTimeout.toMillis()));
        c.echoInterval = Duration.ofMillis(intEnv("CARDNET_ECHO_INTERVAL_MS", (int) c.echoInterval.toMillis()));
        String routes = System.getenv("CARDNET_ROUTES"); // "999900=127.0.0.1:8583;123456=127.0.0.1:8584"
        if (routes != null && !routes.isBlank()) {
            List<Route> parsed = new ArrayList<>();
            for (String entry : routes.split(";")) {
                String[] prefixAndTarget = entry.trim().split("=");
                String[] hostAndPort = prefixAndTarget[1].split(":");
                parsed.add(new Route(prefixAndTarget[0], hostAndPort[0], Integer.parseInt(hostAndPort[1])));
            }
            c.routes = List.copyOf(parsed);
        }
        return c;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
