package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.auth.AuthorizationMaintenance;
import com.bilal.neobank.ledger.InsufficientFundsException;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.TransactionType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Duplicates (one answer, one hold), reversals (the hold goes away, the matter is closed) and races over TCP. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IssuerDuplicateReversalTest extends IssuerTestBase {

    @Autowired
    AuthorizationMaintenance expiryScheduler;

    @Autowired
    LedgerService ledger;

    // ---- duplicates ----

    @Test
    void aResendReturnsTheIdenticalAnswerAndHoldsOnlyOnce() throws Exception {
        Fixture f = fixture("dup1@example.com", "100.00");
        Message request = purchase(f, "30.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            Message first = terminal.exchange(request);
            Message second = terminal.exchange(request);
            Message third = terminal.exchange(request);

            assertThat(code(first)).isEqualTo("00");
            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        }
        assertThat(rows("true")).isEqualTo(1);
        assertThat(available(f)).isEqualByComparingTo("70.00");
    }

    @Test
    void aResendWithAnotherAmountIsRefusedWith94AndChangesNothing() throws Exception {
        Fixture f = fixture("dup2@example.com", "100.00");
        Message request = purchase(f, "30.00");
        Message changed = Message.of("0100");
        request.fields().forEach(changed::set);
        changed.set(4, cents("31.00"));

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(request))).isEqualTo("00");
            Message response = terminal.exchange(changed);

            assertThat(code(response)).isEqualTo("94");
            assertThat(response.has(38)).isFalse();
        }
        assertThat(rows("true")).isEqualTo(1);
        assertThat(available(f)).isEqualByComparingTo("70.00");
    }

    @Test
    void aDeclinedRequestIsReplayedAsADeclineEvenIfMoneyArrivedMeanwhile() throws Exception {
        Fixture f = fixture("dup3@example.com", "10.00");
        Message request = purchase(f, "50.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(request))).isEqualTo("51");
            deposit(f.token(), f.account(), "500.00");
            assertThat(code(terminal.exchange(request))).as("same request, same answer").isEqualTo("51");
            assertThat(code(terminal.exchange(purchase(f, "50.00")))).as("a NEW request is decided afresh").isEqualTo("00");
        }
        assertThat(rows("true")).isEqualTo(2);
    }

    @Test
    void theSameStanAtAnotherTerminalOrWithAnotherRrnIsADifferentRequest() throws Exception {
        Fixture f = fixture("dup4@example.com", "100.00");
        Message request = purchase(f, "10.00");
        Message otherTerminal = Message.of("0100");
        request.fields().forEach(otherTerminal::set);
        otherTerminal.set(41, "TERM0002");
        Message otherRrn = Message.of("0100");
        request.fields().forEach(otherRrn::set);
        otherRrn.set(37, "999999999999");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(request))).isEqualTo("00");
            assertThat(code(terminal.exchange(otherTerminal))).isEqualTo("00");
            assertThat(code(terminal.exchange(otherRrn))).isEqualTo("00");
        }
        assertThat(rows("status = 'AUTHORIZED'")).isEqualTo(3);
    }

    @Test
    void duplicatesArrivingAtTheSameMomentMakeOneHoldAndGetOneAnswer() throws Exception {
        Fixture f = fixture("dup5@example.com", "1000.00");
        for (int round = 0; round < 10; round++) {
            Message request = purchase(f, "10.00");
            List<Callable<Message>> senders = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                senders.add(() -> {
                    try (Terminal terminal = new Terminal().signedOn()) {
                        return terminal.exchange(request);
                    }
                });
            }

            List<Message> answers = raceAll(senders);

            assertThat(answers).as("round " + round).allMatch(a -> a.equals(answers.get(0)));
            assertThat(code(answers.get(0))).isEqualTo("00");
            assertThat(rows("stan = ?", request.get(11))).as("round " + round).isEqualTo(1);
        }
        assertThat(available(f)).isEqualByComparingTo("900.00");
    }

    @Test
    void duplicatesOfAnUnknownCardAtTheSameMomentAreHandledToo() throws Exception {
        Fixture f = fixture("dup6@example.com", "10.00");
        String unknown = "999900123456789" + Luhn.checkDigit("999900123456789");
        for (int round = 0; round < 5; round++) {
            Message request = purchaseWith(unknown, f.expiry(), "5.00", nextStan(), "%012d".formatted(500 + round));
            List<Callable<Message>> senders = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                senders.add(() -> {
                    try (Terminal terminal = new Terminal().signedOn()) {
                        return terminal.exchange(request);
                    }
                });
            }

            List<Message> answers = raceAll(senders);

            assertThat(answers).allMatch(a -> code(a).equals("14"));
            assertThat(rows("stan = ?", request.get(11))).isEqualTo(1);
        }
    }

    // ---- reversals ----

    @Test
    void aReversalReleasesTheHoldAndIsIdempotent() throws Exception {
        Fixture f = fixture("rev1@example.com", "100.00");
        Message original = purchase(f, "60.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(original))).isEqualTo("00");
            assertThat(available(f)).isEqualByComparingTo("40.00");

            Message response = terminal.exchange(reversalOf(original));
            assertThat(response.mti()).isEqualTo("0410");
            assertThat(code(response)).isEqualTo("00");
            assertThat(available(f)).isEqualByComparingTo("100.00");

            assertThat(code(terminal.exchange(reversalOf(original)))).as("again").isEqualTo("00");
            assertThat(available(f)).isEqualByComparingTo("100.00");
        }
        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("select released_at is not null from authorizations", Boolean.class)).isTrue();
        assertThat(rows("true")).isEqualTo(1);
    }

    @Test
    void afterAReversalTheMoneyCanBeSpentAgain() throws Exception {
        Fixture f = fixture("rev2@example.com", "100.00");
        Message original = purchase(f, "100.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(original))).isEqualTo("00");
            assertThat(code(terminal.exchange(purchase(f, "1.00")))).isEqualTo("51");
            terminal.exchange(reversalOf(original));
            assertThat(code(terminal.exchange(purchase(f, "100.00")))).isEqualTo("00");
        }
    }

    @Test
    void aReversalWithAnotherAmountIsRefusedAndTheHoldStays() throws Exception {
        Fixture f = fixture("rev3@example.com", "100.00");
        Message original = purchase(f, "60.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            terminal.exchange(original);
            Message partial = reversalOf(original).set(4, cents("20.00"));

            assertThat(code(terminal.exchange(partial))).isEqualTo("30");
        }
        assertThat(available(f)).isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("AUTHORIZED");
    }

    @Test
    void aMalformedReversalIsAFormatError() throws Exception {
        Fixture f = fixture("rev4@example.com", "100.00");
        Message original = purchase(f, "60.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            terminal.exchange(original);
            for (int missing : new int[] { 2, 4, 13, 37, 41, 42, 49, 90 }) {
                assertThat(code(terminal.exchange(reversalOf(original).remove(missing)))).as("without DE " + missing).isEqualTo("30");
            }
            // DE 90 must name an authorization or financial request
            Message wrongType = reversalOf(original).set(90, "0800" + original.get(11) + original.get(7) + "00000123456" + "00000000000");
            assertThat(code(terminal.exchange(wrongType))).isEqualTo("30");
        }
        assertThat(available(f)).isEqualByComparingTo("40.00");
        assertThat(rows("true")).isEqualTo(1);
    }

    @Test
    void reversingADeclinedRequestIsAnsweredOkAndChangesNothing() throws Exception {
        Fixture f = fixture("rev5@example.com", "10.00");
        Message declined = purchase(f, "50.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(declined))).isEqualTo("51");
            assertThat(code(terminal.exchange(reversalOf(declined)))).isEqualTo("00");
        }
        assertThat(rows("true")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("DECLINED");
    }

    @Test
    void aReversalOfAnOriginalNotSeenYetLeavesATombstoneAndTheLateOriginalIsRefused() throws Exception {
        Fixture f = fixture("rev6@example.com", "100.00");
        Message original = purchase(f, "40.00"); // never sent yet: the acquirer timed out and reversed first

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(reversalOf(original)))).isEqualTo("00");
            assertThat(rows("status = 'REVERSED' and response_code = '05'")).as("the tombstone").isEqualTo(1);

            Message late = terminal.exchange(original);

            assertThat(code(late)).isEqualTo("05");
            assertThat(late.has(38)).isFalse();
        }
        assertThat(available(f)).isEqualByComparingTo("100.00");
        assertThat(rows("status = 'AUTHORIZED'")).isZero();
        assertThat(rows("true")).isEqualTo(1);
    }

    @Test
    void theSameTombstoneRuleHoldsForAnUnknownCard() throws Exception {
        Fixture f = fixture("rev7@example.com", "100.00");
        String unknown = "999900123456789" + Luhn.checkDigit("999900123456789");
        Message original = purchaseWith(unknown, f.expiry(), "40.00", nextStan(), "000000000777");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(reversalOf(original)))).isEqualTo("00");
            assertThat(code(terminal.exchange(original))).isEqualTo("05");
        }
        assertThat(rows("card_id is null")).isEqualTo(1);
    }

    @Test
    void aReversalFromTheSameMomentAsItsOriginalNeverLeavesAHoldBehind() throws Exception {
        Fixture f = fixture("rev8@example.com", "1000.00");
        for (int round = 0; round < 10; round++) {
            Message original = purchase(f, "10.00");
            Message reversal = reversalOf(original);

            raceAll(List.of(() -> {
                try (Terminal terminal = new Terminal().signedOn()) {
                    return terminal.exchange(original);
                }
            }, () -> {
                try (Terminal terminal = new Terminal().signedOn()) {
                    return terminal.exchange(reversal);
                }
            }));

            // whichever won, the end state is the same: nothing is held, and the matter is closed
            assertThat(rows("stan = ? and status = 'AUTHORIZED'", original.get(11))).as("round " + round).isZero();
        }
        assertThat(available(f)).isEqualByComparingTo("1000.00");
    }

    // ---- other message types and concurrency ----

    @Test
    void parallelAuthorizationsOverSeveralConnectionsNeverHoldMoreThanTheBalance() throws Exception {
        for (int round = 0; round < 3; round++) {
            clean();
            Fixture f = fixture("race" + round + "@example.com", "100.00");
            List<Callable<Message>> senders = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Message request = purchase(f, "20.00");
                senders.add(() -> {
                    try (Terminal terminal = new Terminal().signedOn()) {
                        return terminal.exchange(request);
                    }
                });
            }

            List<Message> answers = raceAll(senders);

            assertThat(answers.stream().filter(a -> code(a).equals("00")).count()).as("round " + round).isEqualTo(5);
            assertThat(answers.stream().filter(a -> code(a).equals("51")).count()).as("round " + round).isEqualTo(7);
            assertThat(available(f)).isEqualByComparingTo("0.00");
            assertThat(reconciliation.check().isHealthy()).isTrue();
        }
    }

    /**
     * Requests for ONE card queue up on that card's row lock anyway, so the account lock only matters when the money is
     * shared: two cards on one account, or a card and a transfer. These two tests are the ones that need it.
     */
    @Test
    void twoCardsOfOneAccountRacingNeverHoldMoreThanTheBalance() throws Exception {
        for (int round = 0; round < 3; round++) {
            clean();
            Fixture first = fixture("twocards" + round + "@example.com", "100.00");
            Fixture second = card(first.token(), first.account());
            List<Callable<Message>> senders = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Message request = purchase(i % 2 == 0 ? first : second, "20.00");
                senders.add(() -> {
                    try (Terminal terminal = new Terminal().signedOn()) {
                        return terminal.exchange(request);
                    }
                });
            }

            List<Message> answers = raceAll(senders);

            assertThat(answers.stream().filter(a -> code(a).equals("00")).count()).as("round " + round).isEqualTo(5);
            assertThat(available(first)).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void aTransferRacingCardPaymentsOverTcpNeverSpendsTheSameMoneyTwice() throws Exception {
        for (int round = 0; round < 8; round++) {
            clean();
            Fixture f = fixture("xfer" + round + "@example.com", "100.00");
            Acc savings = open(f.token(), "SAVINGS");
            List<Callable<Boolean>> work = new ArrayList<>();
            List<Terminal> terminals = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Message request = purchase(f, "30.00");
                Terminal terminal = new Terminal().signedOn(); // connected BEFORE the race, so only the exchange is raced
                terminals.add(terminal);
                work.add(() -> code(terminal.exchange(request)).equals("00"));
                work.add(() -> {
                    try {
                        ledger.post(TransactionType.TRANSFER, UUID.fromString(f.account().userId()), "x-" + UUID.randomUUID(), "h", null,
                                List.of(new Posting(UUID.fromString(f.account().id()), new BigDecimal("-30.00")),
                                        new Posting(UUID.fromString(savings.id()), new BigDecimal("30.00"))));
                        return true;
                    } catch (InsufficientFundsException e) {
                        return false;
                    }
                });
            }

            long succeeded;
            try {
                succeeded = raceAll(work).stream().filter(Boolean::booleanValue).count();
            } finally {
                for (Terminal terminal : terminals) {
                    terminal.close();
                }
            }

            assertThat(succeeded).as("round " + round + ": 100.00 pays for three 30.00 spends, in any mix").isEqualTo(3);
            assertThat(available(f)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(reconciliation.check().isHealthy()).isTrue();
        }
    }

    @Test
    void holdsMarkedExpiredByTheNightlyJobStayOutOfTheMoneyAndAreShownAsExpired() throws Exception {
        Fixture f = fixture("exp@example.com", "100.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(purchase(f, "60.00")))).isEqualTo("00");
        }
        assertThat(expiryScheduler.expireOldHolds()).as("nothing is due yet").isZero();

        mutableClock.set(Instant.now().plus(Duration.ofDays(8)));
        assertThat(expiryScheduler.expireOldHolds()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select status from authorizations", String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select released_at is not null from authorizations", Boolean.class)).isTrue();
        assertThat(available(f)).isEqualByComparingTo("100.00");
        assertThat(expiryScheduler.expireOldHolds()).as("only once").isZero();
    }

    private <T> List<T> raceAll(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
