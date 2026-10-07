package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BIN routing, the stand-in rule and the chaos switches: pure, no sockets. */
class RulesTest {

    private final NetworkConfig config = new NetworkConfig();

    // ---- BIN routing ----

    @Test
    void aCardIsRoutedByTheLongestMatchingPrefix() {
        BinTable table = new BinTable(List.of(new NetworkConfig.Route("9999", "a", 1), new NetworkConfig.Route("999900", "b", 2),
                new NetworkConfig.Route("4", "c", 3)));

        assertThat(table.lookup("9999000000000012").orElseThrow().host()).isEqualTo("b");
        assertThat(table.lookup("9999110000000000").orElseThrow().host()).isEqualTo("a");
        assertThat(table.lookup("4111111111111111").orElseThrow().host()).isEqualTo("c");
    }

    @Test
    void aCardNobodyOwnsIsNotRouted() {
        BinTable table = new BinTable(List.of(new NetworkConfig.Route("999900", "b", 2)));

        assertThat(table.lookup("5555555555554444")).isEmpty();
        assertThat(table.lookup("99990")).as("shorter than the prefix").isEmpty();
        assertThat(table.lookup("")).isEmpty();
        assertThat(table.lookup(null)).isEmpty();
        assertThat(new BinTable(List.of()).lookup("9999000000000012")).isEmpty();
    }

    // ---- stand-in ----

    private Message purchase(String amount) {
        return com.bilal.cardnet.network.NetHarness.purchase("9999000000000012", amount, "000001", "000000000001");
    }

    private StandIn.Decision decide(Message request, String usedToday) {
        return StandIn.decide(request, new BigDecimal(usedToday), config);
    }

    @Test
    void anOrdinarySmallPurchaseIsApproved() {
        assertThat(decide(purchase("12.50"), "0").approve()).isTrue();
    }

    @Test
    void thePerPaymentLimitIsExact() {
        assertThat(decide(purchase("50.00"), "0").approve()).as("50.00").isTrue();
        StandIn.Decision over = decide(purchase("50.01"), "0");
        assertThat(over.approve()).as("50.01").isFalse();
        assertThat(over.reason()).contains("per-payment");
    }

    @Test
    void theDailyTotalOfACardIsExact() {
        assertThat(decide(purchase("50.00"), "50.00").approve()).as("50 + 50 = 100.00").isTrue();
        assertThat(decide(purchase("0.01"), "100.00").approve()).as("100.00 + 0.01").isFalse();
        StandIn.Decision over = decide(purchase("20.00"), "80.01");
        assertThat(over.approve()).isFalse();
        assertThat(over.reason()).contains("daily");
        assertThat(decide(purchase("19.99"), "80.01").approve()).isTrue();
    }

    @Test
    void aPaymentWithAPinIsNeverStoodInFor() {
        StandIn.Decision decision = decide(purchase("5.00").set(52, "0123456789ABCDEF"), "0");
        assertThat(decision.approve()).isFalse();
        assertThat(decision.reason()).contains("PIN");
    }

    @Test
    void anIncrementIsNeverStoodInFor() {
        // DE 90 names the authorization it adds to: only the bank knows that one
        String reference = "0100" + "000001" + "1005120000" + "00000123456" + "00000000000";
        StandIn.Decision decision = decide(purchase("5.00").set(90, reference), "0");
        assertThat(decision.approve()).isFalse();
        assertThat(decision.reason()).contains("increment");
        assertThat(decide(purchase("5.00"), "0").approve()).as("the same request without the reference").isTrue();
    }

    @Test
    void onlyPurchasesInDollarsAtAllowedMerchantsQualify() {
        assertThat(decide(purchase("5.00").set(3, "010000"), "0").approve()).as("cash").isFalse();
        assertThat(decide(purchase("5.00").set(3, "200000"), "0").approve()).as("refund").isFalse();
        assertThat(decide(purchase("5.00").set(3, "001000"), "0").approve()).as("a purchase from a savings type").isTrue();
        assertThat(decide(purchase("5.00").set(49, "978"), "0").approve()).as("euro").isFalse();
        for (String mcc : List.of("7995", "4829", "6051")) {
            assertThat(decide(purchase("5.00").set(18, mcc), "0").approve()).as("mcc " + mcc).isFalse();
        }
        assertThat(decide(purchase("5.00").set(18, "5411"), "0").approve()).isTrue();
    }

    @Test
    void aZeroAmountIsNotApproved() {
        assertThat(decide(purchase("0.00"), "0").approve()).isFalse();
    }

    @Test
    void theLimitsComeFromTheConfiguration() {
        config.standInMax = new BigDecimal("10.00");
        config.standInDailyCap = new BigDecimal("15.00");
        config.blockedMcc = java.util.Set.of("5411");

        assertThat(decide(purchase("10.00"), "0").approve()).isTrue();
        assertThat(decide(purchase("10.01"), "0").approve()).isFalse();
        assertThat(decide(purchase("10.00"), "5.01").approve()).isFalse();
        assertThat(decide(purchase("5.00").set(18, "5411"), "0").approve()).isFalse();
        assertThat(decide(purchase("5.00").set(18, "7995"), "0").approve()).as("no longer blocked").isTrue();
    }

    // ---- chaos ----

    @Test
    void chaosStartsOffAndAppliesAFlatObject() {
        Chaos chaos = new Chaos();
        assertThat(chaos.issuerDown()).isFalse();
        assertThat(chaos.issuerResponseDelayMs()).isZero();
        assertThat(chaos.acquirerResponseDelayMs()).isZero();

        chaos.apply(Map.of("issuerDown", true, "issuerResponseDelayMs", 4000L));

        assertThat(chaos.issuerDown()).isTrue();
        assertThat(chaos.issuerResponseDelayMs()).isEqualTo(4000);
        assertThat(chaos.acquirerResponseDelayMs()).isZero();
        assertThat(chaos.toMap()).containsEntry("issuerDown", true).containsEntry("issuerResponseDelayMs", 4000L);
    }

    @Test
    void badChaosSettingsAreRefusedAsAWholeAndChangeNothing() {
        Chaos chaos = new Chaos();
        chaos.apply(Map.of("acquirerResponseDelayMs", 100L));

        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("issuerDown", "yes"), Map.of("issuerResponseDelayMs", -1L),
                Map.of("issuerResponseDelayMs", 60_001L), Map.of("issuerResponseDelayMs", 1.5), Map.of("acquirerResponseDelayMs", -1L),
                Map.of("acquirerResponseDelayMs", 60_001L), Map.of("bogus", true))) {
            assertThatThrownBy(() -> chaos.apply(bad)).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        java.util.LinkedHashMap<String, Object> mixed = new java.util.LinkedHashMap<>();
        mixed.put("issuerDown", true);
        mixed.put("issuerResponseDelayMs", -5L);
        assertThatThrownBy(() -> chaos.apply(mixed)).isInstanceOf(IllegalArgumentException.class);

        assertThat(chaos.issuerDown()).as("the valid part of a refused request is not applied").isFalse();
        assertThat(chaos.acquirerResponseDelayMs()).isEqualTo(100);
    }
}
