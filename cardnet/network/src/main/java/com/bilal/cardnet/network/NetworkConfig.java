package com.bilal.cardnet.network;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Every setting of the network, with their defaults. Tests change the public fields they need. */
public final class NetworkConfig {

    /** Card numbers that start with {@code prefix} are routed to the issuer at host:port. */
    public record Route(String prefix, String host, int port) {
    }

    /** The address the acquirer port and the control API bind to: loopback unless CARDNET_BIND says otherwise (containers need 0.0.0.0). */
    public String bindAddress = "127.0.0.1";
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

    // ---- clearing (P4) ----

    /** The folder shared with acquirer and issuer (see ClearingDirs). Null switches clearing off (the default in tests). */
    public java.nio.file.Path clearingDir = null;
    public Duration clearingPoll = Duration.ofSeconds(2);
    /** The text the file key is made from; the same in every process that reads or writes clearing files. */
    public String fileSecret = com.bilal.cardnet.iso8583.clearing.ClearingFileCodec.DEFAULT_SECRET;
    /** The name the network puts in file headers and expects as the receiver of an acquirer's file. */
    public String networkName = "NET";
    /** Acquirers whose files are accepted (the sender in the file header). */
    public Set<String> knownAcquirers = Set.of("123456");
    public BigDecimal merchantDiscountRate = new BigDecimal("0.0200");
    public BigDecimal interchangeRate = new BigDecimal("0.0120");
    public BigDecimal schemeFeeRate = new BigDecimal("0.0005");

    /** Days that are not business days besides Saturday and Sunday (CARDNET_HOLIDAYS=2026-12-25,2027-01-01). */
    public Set<java.time.LocalDate> holidays = Set.of();

    public Duration idleTimeout = Duration.ofSeconds(120);
    public int maxConnections = 50;

    /** Reads CARDNET_* environment variables over the defaults. */
    public static NetworkConfig fromEnvironment() {
        NetworkConfig c = new NetworkConfig();
        String bind = System.getenv("CARDNET_BIND");
        if (bind != null && !bind.isBlank()) {
            c.bindAddress = bind.trim();
        }
        c.acquirerPort = intEnv("CARDNET_ACQUIRER_PORT", c.acquirerPort);
        c.controlPort = intEnv("CARDNET_CONTROL_PORT", c.controlPort);
        c.issuerTimeout = Duration.ofMillis(intEnv("CARDNET_ISSUER_TIMEOUT_MS", (int) c.issuerTimeout.toMillis()));
        c.echoInterval = Duration.ofMillis(intEnv("CARDNET_ECHO_INTERVAL_MS", (int) c.echoInterval.toMillis()));
        String dataDir = System.getenv("CARDNET_DATA_DIR");
        c.clearingDir = java.nio.file.Path.of(dataDir == null || dataDir.isBlank() ? "./data/clearing" : dataDir.trim());
        String fileKey = System.getenv("CARDNET_FILE_KEY");
        if (fileKey != null && !fileKey.isBlank()) {
            c.fileSecret = fileKey;
        }
        String holidays = System.getenv("CARDNET_HOLIDAYS");
        if (holidays != null && !holidays.isBlank()) {
            java.util.Set<java.time.LocalDate> parsed = new java.util.HashSet<>();
            for (String day : holidays.split(",")) {
                parsed.add(java.time.LocalDate.parse(day.trim()));
            }
            c.holidays = Set.copyOf(parsed);
        }
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
