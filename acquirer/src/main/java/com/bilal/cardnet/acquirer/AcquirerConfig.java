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

    /** The address the POS console binds to: loopback unless CARDNET_BIND says otherwise (containers need 0.0.0.0). */
    public String bindAddress = "127.0.0.1";
    public int httpPort = 9000;

    // ---- clearing (P4) ----

    /** The folder shared with the network (see ClearingDirs). Null switches clearing off (the default in tests). */
    public java.nio.file.Path clearingDir = null;
    public Duration clearingPoll = Duration.ofSeconds(2);
    /** The text the file key is made from; the same in every process that reads or writes clearing files. */
    public String fileSecret = com.bilal.cardnet.iso8583.clearing.ClearingFileCodec.DEFAULT_SECRET;
    /** The name of the network, the receiver in the header of every batch file. */
    public String networkName = "NET";
    /** How far above the authorized amount a final amount may be (a tip): 0.20 is 20%. */
    public java.math.BigDecimal tipLimit = new java.math.BigDecimal("0.20");

    public List<Merchant> merchants = List.of(
            new Merchant("MERCHANT0000001", "Corner Cafe", "5814"),
            new Merchant("MERCHANT0000002", "Gas Station", "5541"),
            new Merchant("MERCHANT0000003", "Electronics Store", "5732"),
            new Merchant("MERCHANT0000004", "Casino Royale", "7995"));

    /** The cash machine: its own acceptor id and the merchant category 6011 (ATM). Not in the shop list. */
    public Merchant atm = new Merchant("ATM000000000001", "City ATM", "6011");
    /** The most one withdrawal can ask for; the machine pays notes of 10, so amounts are multiples of 10 from 10 to this. */
    public java.math.BigDecimal atmMax = new java.math.BigDecimal("500");

    /**
     * The published demo card of NeoBank's demo data (a test number, not a secret). NeoBank issues a card that expires three years
     * from the month it was issued, so by default the expiry here is three years from now, as MM/YY; CARDNET_DEMO_CARD_EXPIRY
     * overrides it (for a demo database seeded in another month).
     */
    public String demoCardPan = "9999000000000012";
    public String demoCardPin = "4321";
    public String demoCardExpiry = threeYearsAhead();

    /** The expiry NeoBank gives a card issued this month: three years ahead, written MM/YY as it is printed on the card. */
    public static String threeYearsAhead() {
        java.time.YearMonth expiry = java.time.YearMonth.now(java.time.ZoneOffset.UTC).plusYears(3);
        return "%02d/%02d".formatted(expiry.getMonthValue(), expiry.getYear() % 100);
    }

    public static AcquirerConfig fromEnvironment() {
        AcquirerConfig c = new AcquirerConfig();
        c.networkHost = env("CARDNET_NETWORK_HOST", c.networkHost);
        c.networkPort = Integer.parseInt(env("CARDNET_NETWORK_PORT", String.valueOf(c.networkPort)));
        c.networkControlUrl = env("CARDNET_NETWORK_CONTROL_URL", c.networkControlUrl);
        c.bindAddress = env("CARDNET_BIND", c.bindAddress);
        c.httpPort = Integer.parseInt(env("CARDNET_HTTP_PORT", String.valueOf(c.httpPort)));
        c.networkTimeout = Duration.ofMillis(Long.parseLong(env("CARDNET_ACQUIRER_TIMEOUT_MS", String.valueOf(c.networkTimeout.toMillis()))));
        String dataDir = System.getenv("CARDNET_DATA_DIR");
        c.clearingDir = java.nio.file.Path.of(dataDir == null || dataDir.isBlank() ? "./data/clearing" : dataDir.trim());
        c.fileSecret = env("CARDNET_FILE_KEY", c.fileSecret);
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
