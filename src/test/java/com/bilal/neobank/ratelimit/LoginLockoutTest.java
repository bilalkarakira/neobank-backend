package com.bilal.neobank.ratelimit;

import com.bilal.neobank.ledger.Fingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLockoutTest extends RateLimitTestBase {

    private static final String ALICE = "alice@example.com";

    @Autowired
    LoginLockout lockout;

    @BeforeEach
    void alice() throws Exception {
        assertThat(statusOf(register(ALICE, IP_A))).isEqualTo(201);
    }

    private void failTimes(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(statusOf(login(email, WRONG, IP_A))).as("attempt %d", i + 1).isEqualTo(401);
        }
    }

    private int failures(String email) {
        var rows = jdbc.queryForList("select failures from login_failures where email_hash = ?",
                Fingerprint.sha256(email));
        return rows.isEmpty() ? 0 : (Integer) rows.get(0).get("failures");
    }

    @Test
    void fiveWrongPasswordsLockTheEmailAndEvenTheRightPasswordIsRefused() throws Exception {
        failTimes(ALICE, 5);

        MvcResult locked = login(ALICE, PASSWORD, IP_A); // the CORRECT password

        assertThat(statusOf(locked)).isEqualTo(429);
        assertThat(codeOf(locked)).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(retryAfter(locked)).isEqualTo("900"); // 15 minutes
        assertThat(locked.getResponse().getContentAsString()).contains("\"retryAfterSeconds\":900");
        assertThat(locked.getResponse().getCookie("neobank_refresh")).isNull(); // nothing was issued
    }

    @Test
    void fourWrongPasswordsDoNotLockAndTheRightOneStillWorks() throws Exception {
        failTimes(ALICE, 4);
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void theLockEndsByItselfAndTheCounterStartsOver() throws Exception {
        failTimes(ALICE, 5);
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(429);

        after(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
        assertThat(failures(ALICE)).isZero();
        // and a fresh run of failures needs five again, not one: the old count did not carry over
        failTimes(ALICE, 4);
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void failuresAfterALockEndedStartAFreshCountInsteadOfLockingAtOnce() throws Exception {
        failTimes(ALICE, 5);
        after(Duration.ofMinutes(16));

        failTimes(ALICE, 1); // one wrong password after the lock ended

        assertThat(failures(ALICE)).isEqualTo(1);
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void failuresSpreadOutBeyondTheWindowNeverAddUpToALock() throws Exception {
        failTimes(ALICE, 4);
        after(Duration.ofMinutes(16)); // the 15-minute window is over
        failTimes(ALICE, 4);

        assertThat(failures(ALICE)).isEqualTo(4); // not 8
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void aSuccessfulLoginWipesTheCountSoOnlyConsecutiveFailuresLock() throws Exception {
        failTimes(ALICE, 4);
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
        assertThat(failures(ALICE)).isZero();

        failTimes(ALICE, 4);
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void anUnknownEmailLocksExactlyLikeAKnownOneSoTheLockRevealsNothing() throws Exception {
        failTimes("ghost@example.com", 5);
        failTimes(ALICE, 5);

        MvcResult ghost = login("ghost@example.com", WRONG, IP_A);
        MvcResult real = login(ALICE, WRONG, IP_A);

        assertThat(statusOf(ghost)).isEqualTo(429).isEqualTo(statusOf(real));
        assertThat(codeOf(ghost)).isEqualTo(codeOf(real));
        assertThat(retryAfter(ghost)).isEqualTo(retryAfter(real));
    }

    @Test
    void oneLockedEmailDoesNotAffectOthers() throws Exception {
        assertThat(statusOf(register("bob@example.com", IP_A))).isEqualTo(201);
        failTimes(ALICE, 5);

        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(429);
        assertThat(statusOf(login("bob@example.com", PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void capitalsAndSpacesDoNotLetAnAttackerDodgeTheCount() throws Exception {
        String[] spellings = { "ALICE@example.com", " alice@EXAMPLE.com", "Alice@Example.Com ", "alice@example.com", "aLiCe@example.com" };
        for (String spelling : spellings) {
            assertThat(statusOf(login(spelling, WRONG, IP_A))).isEqualTo(401);
        }

        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(429);
        assertThat(jdbc.queryForObject("select count(*) from login_failures", Integer.class)).isEqualTo(1);
    }

    @Test
    void aLockCountsDownAndAttemptsDuringItDoNotExtendIt() throws Exception {
        failTimes(ALICE, 5);
        after(Duration.ofMinutes(10));

        MvcResult during = login(ALICE, WRONG, IP_A); // still locked: refused before the password is even looked at

        assertThat(statusOf(during)).isEqualTo(429);
        assertThat(retryAfter(during)).isEqualTo("300"); // 5 minutes left, not a fresh 15
        assertThat(failures(ALICE)).isEqualTo(5);
    }

    @Test
    void onlyAHashOfTheEmailIsStored() throws Exception {
        failTimes(ALICE, 2);

        assertThat(jdbc.queryForList("select email_hash from login_failures", String.class))
                .containsExactly(Fingerprint.sha256(ALICE));
        assertThat(jdbc.queryForList("select email_hash from login_failures", String.class).get(0)).doesNotContain("alice");
    }

    @Test
    void parallelWrongPasswordsAreCountedExactly() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return login(ALICE, WRONG, IP_A);
            }));
        }
        start.countDown();
        for (Future<MvcResult> result : results) {
            assertThat(statusOf(result.get(60, TimeUnit.SECONDS))).isEqualTo(401);
        }
        pool.shutdown();

        assertThat(failures(ALICE)).isEqualTo(4); // no lost updates
        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void housekeepingRemovesOnlyRecordsThatAreOldAndNoLongerLocked() throws Exception {
        failTimes(ALICE, 5);                     // locked at the start; the lock ended a day ago by the time of the purge
        failTimes("old@example.com", 2);
        after(Duration.ofHours(25));
        failTimes("fresh@example.com", 1);       // recent: must stay
        for (int i = 0; i < 5; i++) {
            login("locked@example.com", WRONG, IP_A);
        }                                        // locked right now: must stay

        assertThat(lockout.purge()).isEqualTo(2);
        assertThat(jdbc.queryForList("select email_hash from login_failures", String.class)).containsExactlyInAnyOrder(
                Fingerprint.sha256("fresh@example.com"), Fingerprint.sha256("locked@example.com"));
    }
}
