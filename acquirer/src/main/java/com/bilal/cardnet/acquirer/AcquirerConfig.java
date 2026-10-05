package com.bilal.cardnet.acquirer;

import java.time.Duration;
import java.util.List;

/** Every setting of the acquirer and its POS console, with the defaults of SPEC-P3-network.md. */
public final class AcquirerConfig {

    /** A shop the virtual POS can pay at. {@code id} is the 15-character card acceptor id (DE 42). */
    public record Merchant(String id, String name, String mcc) {
    }

    public String networkHost = "127.0.0.1";
    public int networkPort = 8601;
    /** The network's control API, which the console proxies for its network panel. */
    public String networkControlUrl = "http://127.0.0.1:9100";

    public String acquirerId = "123456";
    public String terminalId = "TERM0001";

    /** How long a payment waits for an answer. Longer than the network's own wait for the issuer, on purpose. */
    public Duration networkTimeout = Duration.ofSeconds(5);
    public Duration reversalDeadline = Duration.ofHours(24);
    public Duration reversalPoll = Duration.ofSeconds(1);
    /** 1.0 is the real schedule (1, 2, 4, 8, 16 s, then 60 s); tests shrink it. */
    public double retryScale = 1.0;

    public int httpPort = 9000;

    public List<Merchant> merchants = List.of(
            new Merchant("MERCHANT0000001", "Corner Cafe", "5814"),
            new Merchant("MERCHANT0000002", "Gas Station", "5541"),
            new Merchant("MERCHANT0000003", "Electronics Store", "5732"),
            new Merchant("MERCHANT0000004", "Casino Royale", "7995"));

    /** The published demo card of NeoBank's demo data (a test number, not a secret). The expiry is what the Cards page shows. */
    public String demoCardPan = "9999000000000012";
    public String demoCardPin = "4321";
    public String demoCardExpiry = "10/29";

    public static AcquirerConfig fromEnvironment() {
        AcquirerConfig c = new AcquirerConfig();
        c.networkHost = env("CARDNET_NETWORK_HOST", c.networkHost);
        c.networkPort = Integer.parseInt(env("CARDNET_NETWORK_PORT", String.valueOf(c.networkPort)));
        c.networkControlUrl = env("CARDNET_NETWORK_CONTROL_URL", c.networkControlUrl);
        c.httpPort = Integer.parseInt(env("CARDNET_HTTP_PORT", String.valueOf(c.httpPort)));
        c.networkTimeout = Duration.ofMillis(Long.parseLong(env("CARDNET_ACQUIRER_TIMEOUT_MS", String.valueOf(c.networkTimeout.toMillis()))));
        c.demoCardPan = env("CARDNET_DEMO_CARD_PAN", c.demoCardPan);
        c.demoCardPin = env("CARDNET_DEMO_CARD_PIN", c.demoCardPin);
        c.demoCardExpiry = env("CARDNET_DEMO_CARD_EXPIRY", c.demoCardExpiry);
        return c;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
