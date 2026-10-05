package com.bilal.cardnet.shared;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.testing.MutableClock;
import com.bilal.cardnet.shared.testing.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The durable reversal queue and its worker, against a real Postgres (Docker). */
class ReversalQueueTest {

    private static final String OWNER = "ACQUIRER";
    private static final Duration DAY = Duration.ofHours(24);

    private DataSource ds;
    private PanVault vault;
    private PendingReversalStore store;
    private MutableClock clock;
    private final List<Message> sent = new CopyOnWriteArrayList<>();
    private final List<UUID> done = new CopyOnWriteArrayList<>();
    private final List<String> failed = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<Message, Optional<Message>>> issuer = new AtomicReference<>();
    private int stan;

    @BeforeEach
    void setUp() {
        ds = TestDatabase.fresh();
        vault = new PanVault("v".repeat(40));
        store = new PendingReversalStore(ds, vault);
        clock = MutableClock.atNow();
        issuer.set(request -> Optional.of(Messages.response(request, "00")));
    }

    private ReversalWorker worker(String owner) {
        return new ReversalWorker(owner, store, request -> {
            sent.add(request);
            return issuer.get().apply(request);
        }, () -> "%06d".formatted(++stan), clock, new RetrySchedule(1.0), done::add, (id, why) -> failed.add(why));
    }

    private static Message original(String stan, String rrn) {
        return Message.of("0100").set(2, "9999000000000012").set(3, "000000").set(4, "000000004250").set(7, "1005120000")
                .set(11, stan).set(12, "120000").set(13, "1005").set(14, "2910").set(18, "5814").set(32, "123456").set(37, rrn)
                .set(41, "TERM0001").set(42, "MERCHANT0000001").set(49, "840");
    }

    private UUID queue(Message original) {
        return store.add(OWNER, original, new BigDecimal("42.50"), "test", clock.instant(), DAY, null);
    }

    private String scalar(String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    // ---- the store ----

    @Test
    void aQueuedReversalIsDueAtOnceAndKeepsNoClearNumber() throws Exception {
        UUID id = queue(original("000501", "000000000501"));

        List<PendingReversalStore.Due> due = store.due(OWNER, clock.instant(), 10);
        assertThat(due).hasSize(1);
        assertThat(due.get(0).id()).isEqualTo(id);
        assertThat(store.original(due.get(0)).get(2)).isEqualTo("9999000000000012");

        assertThat(scalar("select masked_pan from pending_reversals")).isEqualTo("999900******0012");
        assertThat(scalar("select encode(request_ciphertext, 'escape') from pending_reversals")).doesNotContain("9999000000000012");
        assertThat(scalar("select row_to_json(p)::text from pending_reversals p")).doesNotContain("9999000000000012");
    }

    @Test
    void queueingTheSameOriginalTwiceGivesTheSameReversal() {
        UUID first = queue(original("000501", "000000000501"));
        UUID second = queue(original("000501", "000000000501"));

        assertThat(second).isEqualTo(first);
        assertThat(store.countPending(OWNER)).isEqualTo(1);
    }

    @Test
    void reversalsBelongToTheirOwner() {
        queue(original("000501", "000000000501"));
        assertThat(store.due("NETWORK", clock.instant(), 10)).isEmpty();
        store.add("NETWORK", original("000501", "000000000501"), BigDecimal.ONE, "late", clock.instant(), DAY, null);
        assertThat(store.countPending("NETWORK")).isEqualTo(1);
        assertThat(store.countPending(OWNER)).isEqualTo(1);
    }

    @Test
    void aReversalWhoseTimeHasNotComeIsNotDue() {
        UUID id = queue(original("000501", "000000000501"));
        store.reschedule(id, 1, clock.instant().plusSeconds(30), null);

        assertThat(store.due(OWNER, clock.instant(), 10)).isEmpty();
        assertThat(store.due(OWNER, clock.instant().plusSeconds(31), 10)).hasSize(1);
    }

    // ---- the worker ----

    @Test
    void anAcknowledgedReversalIsDoneAndSentExactlyOnce() {
        UUID id = queue(original("000501", "000000000501"));
        ReversalWorker worker = worker(OWNER);

        assertThat(worker.tick()).isEqualTo(1);
        assertThat(worker.tick()).as("nothing left").isZero();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).mti()).isEqualTo("0400");
        assertThat(sent.get(0).get(90)).isEqualTo("0100" + "000501" + "1005120000" + "00000123456" + "00000000000");
        assertThat(sent.get(0).get(11)).as("a fresh STAN of its own").isEqualTo("000001");
        assertThat(done).containsExactly(id);
        assertThat(store.find(OWNER, "000501", "000000000501").orElseThrow().status()).isEqualTo("DONE");
    }

    @Test
    void noAnswerMeansTryAgainOnTheScheduleUntilItIsAcknowledged() {
        queue(original("000501", "000000000501"));
        issuer.set(request -> Optional.empty());
        ReversalWorker worker = worker(OWNER);
        long[] expectedWaits = { 1, 2, 4, 8, 16, 60, 60 };

        for (int attempt = 1; attempt <= expectedWaits.length; attempt++) {
            assertThat(worker.tick()).as("attempt " + attempt).isEqualTo(1);
            assertThat(worker.tick()).as("not due again before " + expectedWaits[attempt - 1] + " s").isZero();
            clock.advance(Duration.ofSeconds(expectedWaits[attempt - 1]).minusMillis(1));
            assertThat(worker.tick()).as("1 ms early").isZero();
            clock.advance(Duration.ofMillis(1));
        }
        issuer.set(request -> Optional.of(Messages.response(request, "00")));
        assertThat(worker.tick()).isEqualTo(1);

        assertThat(sent).hasSize(expectedWaits.length + 1);
        assertThat(done).hasSize(1);
        assertThat(store.find(OWNER, "000501", "000000000501").orElseThrow().attempts()).isEqualTo(expectedWaits.length);
    }

    @Test
    void anIssuerThatIsUnavailableOrErrorsIsRetriedAndTheCodeIsRemembered() {
        queue(original("000501", "000000000501"));
        issuer.set(request -> Optional.of(Messages.response(request, "91")));
        ReversalWorker worker = worker(OWNER);

        worker.tick();

        PendingReversalStore.Status status = store.find(OWNER, "000501", "000000000501").orElseThrow();
        assertThat(status.status()).isEqualTo("PENDING");
        assertThat(status.lastResponse()).isEqualTo("91");
        assertThat(status.attempts()).isEqualTo(1);
        assertThat(done).isEmpty();
    }

    @Test
    void aSenderThatThrowsIsJustAnotherFailedAttempt() {
        queue(original("000501", "000000000501"));
        issuer.set(request -> {
            throw new IllegalStateException("socket closed");
        });
        worker(OWNER).tick();

        assertThat(store.find(OWNER, "000501", "000000000501").orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void aRefusalThatCanNeverChangeEndsTheReversalAsFailed() {
        queue(original("000501", "000000000501"));
        issuer.set(request -> Optional.of(Messages.response(request, "30")));
        ReversalWorker worker = worker(OWNER);

        worker.tick();
        clock.advance(Duration.ofHours(1));
        worker.tick();

        assertThat(sent).hasSize(1);
        assertThat(failed).containsExactly("refused 30");
        assertThat(store.find(OWNER, "000501", "000000000501").orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    void whenTheDeadlinePassesWithoutAnAcknowledgementItFailsInsteadOfTryingForever() {
        queue(original("000501", "000000000501"));
        issuer.set(request -> Optional.empty());
        ReversalWorker worker = worker(OWNER);
        worker.tick();

        clock.advance(DAY.plusSeconds(1));
        worker.tick();

        assertThat(failed).containsExactly("deadline");
        assertThat(store.find(OWNER, "000501", "000000000501").orElseThrow().status()).isEqualTo("FAILED");
        assertThat(sent).as("no attempt after the deadline").hasSize(1);
    }

    @Test
    void aTamperedQueueEntryIsFailedAndDoesNotStopTheOthers() throws Exception {
        queue(original("000501", "000000000501"));
        queue(original("000502", "000000000502"));
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "update pending_reversals set request_ciphertext = overlay(request_ciphertext placing '\\x00'::bytea from 20 for 1) where stan = '000501'")) {
            ps.executeUpdate();
        }

        worker(OWNER).tick();

        assertThat(failed).containsExactly("unreadable");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get(90)).contains("000502");
    }

    @Test
    void aRestartPicksUpWhereItStopped() {
        queue(original("000501", "000000000501"));
        issuer.set(request -> Optional.empty());
        worker(OWNER).tick(); // the first process tries once, then "dies"
        clock.advance(Duration.ofSeconds(2));
        issuer.set(request -> Optional.of(Messages.response(request, "00")));

        ReversalWorker afterRestart = worker(OWNER); // a new process, a new worker, the same database
        assertThat(afterRestart.tick()).isEqualTo(1);

        assertThat(done).hasSize(1);
        assertThat(sent).hasSize(2);
    }

    @Test
    void aWorkerOnlyDeliversItsOwnReversals() {
        queue(original("000501", "000000000501"));
        List<Message> networkSent = new ArrayList<>();
        ReversalWorker network = new ReversalWorker("NETWORK", store, request -> {
            networkSent.add(request);
            return Optional.of(Messages.response(request, "00"));
        }, () -> "000009", clock, RetrySchedule.standard(), done::add, (id, why) -> failed.add(why));

        assertThat(network.tick()).isZero();
        assertThat(networkSent).isEmpty();
    }

    @Test
    void theBackgroundLoopDeliversAsSoonAsItIsWoken() throws Exception {
        UUID id = queue(original("000501", "000000000501"));
        ReversalWorker worker = worker(OWNER);
        worker.start(Duration.ofSeconds(30)); // a long poll: only wake() can make this quick
        try {
            com.bilal.cardnet.shared.testing.Await.until("the reversal is delivered", () -> done.contains(id));
            UUID second = queue(original("000502", "000000000502"));
            worker.wake();
            com.bilal.cardnet.shared.testing.Await.until("the second is delivered", () -> done.contains(second));
        } finally {
            worker.stop();
        }
        assertThat(sent.stream().map(m -> new String(m.get(90).getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII)).toList())
                .hasSize(2);
    }
}
