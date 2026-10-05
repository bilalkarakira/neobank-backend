package com.bilal.neobank.card;

import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.account.AccountNotActiveException;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationRepository;
import com.bilal.neobank.card.auth.HoldService;
import com.bilal.neobank.ledger.InsufficientFundsException;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Available balance = ledger balance - active holds, enforced by the same lock that serialises every money movement. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class HoldsTest extends AnalyticsTestBase {

    @Autowired
    HoldService holdService;

    @Autowired
    LedgerService ledger;

    @Autowired
    AuthorizationRepository authorizations;

    @Autowired
    MutableClock mutableClock;

    private final AtomicInteger stan = new AtomicInteger();

    private Authorization hold(Acc account, String amount) {
        mutableClock.set(Instant.now());
        return hold(account, amount, mutableClock.instant().plus(Duration.ofDays(7)));
    }

    private Authorization hold(Acc account, String amount, Instant expiresAt) {
        int n = stan.incrementAndGet();
        var request = new Authorization.Request("0100", "%06d".formatted(n), "%012d".formatted(n), "TERM0001",
                "MERCHANT0000001", "Test shop", "5411", "00000000001", "000000", "051", "0101", "0101120000",
                new BigDecimal(amount), "840");
        return Authorization.approved(null, UUID.fromString(account.id()), "h" + "0".repeat(63), "0001", request,
                "AUTH%02d".formatted(n % 100), clock.instant(), expiresAt);
    }

    private void deposit(String token, Acc account, String amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + account.id() + "/deposits").header("Authorization", token)
                .header("Idempotency-Key", "dep-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"%s\"}".formatted(amount))).andExpect(status().isCreated());
    }

    private int transfer(String token, Acc from, Acc to, String amount) throws Exception {
        return mockMvc.perform(post("/api/transfers").header("Authorization", token)
                .header("Idempotency-Key", "tr-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":\"%s\"}"
                        .formatted(from.id(), to.iban(), amount)))
                .andReturn().getResponse().getStatus();
    }

    private BigDecimal sumHolds(Acc account) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from authorizations where account_id = ?::uuid "
                + "and status = 'AUTHORIZED'", BigDecimal.class, account.id());
    }

    @Test
    void availableBalanceIsTheBalanceMinusActiveHolds() throws Exception {
        String token = tokenFor("hold1@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "100.00");
        holdService.reserve(hold(account, "30.00"));

        api(token, "/api/accounts/" + account.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("100.00"))
                .andExpect(jsonPath("$.availableBalance").value("70.00"))
                .andExpect(jsonPath("$.heldAmount").value("30.00"));
        api(token, "/api/accounts").andExpect(jsonPath("$[0].availableBalance").value("70.00"))
                .andExpect(jsonPath("$[0].heldAmount").value("30.00"));
        assertBooksBalance();
    }

    @Test
    void anAccountWithoutHoldsHasAvailableEqualToBalance() throws Exception {
        String token = tokenFor("hold2@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "12.50");

        api(token, "/api/accounts").andExpect(jsonPath("$[0].availableBalance").value("12.50"))
                .andExpect(jsonPath("$[0].heldAmount").value("0.00"));
    }

    @Test
    void aTransferCannotSpendHeldMoneyButCanSpendTheRest() throws Exception {
        String token = tokenFor("hold3@example.com");
        Acc from = open(token, "CHECKING");
        Acc to = open(token, "SAVINGS");
        deposit(token, from, "100.00");
        holdService.reserve(hold(from, "30.00"));

        assertThat(transfer(token, from, to, "70.01")).isEqualTo(422);
        assertThat(transfer(token, from, to, "70.00")).isEqualTo(201);

        assertThat(sumHolds(from)).isEqualByComparingTo("30.00");
        api(token, "/api/accounts/" + from.id()).andExpect(jsonPath("$.balance").value("30.00"))
                .andExpect(jsonPath("$.availableBalance").value("0.00"));
        assertBooksBalance();
    }

    @Test
    void theRefusalSaysInsufficientFunds() throws Exception {
        String token = tokenFor("hold3b@example.com");
        Acc from = open(token, "CHECKING");
        Acc to = open(token, "SAVINGS");
        deposit(token, from, "50.00");
        holdService.reserve(hold(from, "50.00"));

        mockMvc.perform(post("/api/transfers").header("Authorization", token)
                .header("Idempotency-Key", "tr-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":\"0.01\"}"
                        .formatted(from.id(), to.iban())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void aReversedHoldFreesTheMoneyAgain() throws Exception {
        String token = tokenFor("hold4@example.com");
        Acc from = open(token, "CHECKING");
        Acc to = open(token, "SAVINGS");
        deposit(token, from, "100.00");
        Authorization held = holdService.reserve(hold(from, "60.00"));
        assertThat(transfer(token, from, to, "50.00")).isEqualTo(422);

        jdbc.update("update authorizations set status = 'REVERSED' where id = ?", held.getId());

        assertThat(transfer(token, from, to, "50.00")).isEqualTo(201);
    }

    @Test
    void anExpiredHoldNoLongerReservesMoneyEvenBeforeAnyJobRuns() throws Exception {
        String token = tokenFor("hold5@example.com");
        Acc from = open(token, "CHECKING");
        Acc to = open(token, "SAVINGS");
        deposit(token, from, "100.00");
        mutableClock.set(Instant.now());
        holdService.reserve(hold(from, "60.00", mutableClock.instant().plus(Duration.ofDays(7))));
        assertThat(transfer(token, from, to, "50.00")).isEqualTo(422);

        mutableClock.set(Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));

        api(token, "/api/accounts/" + from.id()).andExpect(jsonPath("$.availableBalance").value("100.00"));
        assertThat(transfer(token, from, to, "50.00")).isEqualTo(201);
        mutableClock.set(Instant.now());
    }

    @Test
    void aHoldDoesNotBlockMoneyComingIn() throws Exception {
        String token = tokenFor("hold6@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "40.00");
        holdService.reserve(hold(account, "40.00"));

        deposit(token, account, "5.00"); // a credit is never checked against holds

        api(token, "/api/accounts/" + account.id()).andExpect(jsonPath("$.availableBalance").value("5.00"));
    }

    @Test
    void moneyComingInIsAcceptedEvenWhenHoldsAlreadyExceedTheBalance() throws Exception {
        String token = tokenFor("hold6b@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "10.00");
        authorizations.saveAndFlush(hold(account, "50.00")); // an abnormal state, planted: still 40.00 above the balance after +1.00

        deposit(token, account, "1.00");

        api(token, "/api/accounts/" + account.id()).andExpect(jsonPath("$.balance").value("11.00"));
    }

    @Test
    void reservingMoreThanIsAvailableIsRefusedAndLeavesNoRow() throws Exception {
        String token = tokenFor("hold7@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "100.00");
        holdService.reserve(hold(account, "60.00"));

        assertThatThrownBy(() -> holdService.reserve(hold(account, "40.01"))).isInstanceOf(InsufficientFundsException.class);
        holdService.reserve(hold(account, "40.00")); // exactly what is left is allowed

        assertThat(authorizations.count()).isEqualTo(2);
        assertThat(sumHolds(account)).isEqualByComparingTo("100.00");
    }

    @Test
    void aFrozenAccountCannotTakeAHold() throws Exception {
        String token = tokenFor("hold8@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "100.00");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", account.id());

        assertThatThrownBy(() -> holdService.reserve(hold(account, "1.00"))).isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void reconciliationFlagsAHoldLargerThanTheBalance() throws Exception {
        String token = tokenFor("hold9@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "10.00");
        assertThat(reconciliation.check().isHealthy()).isTrue();

        authorizations.saveAndFlush(hold(account, "10.01")); // bypasses the check on purpose

        var report = reconciliation.check();
        assertThat(report.isHealthy()).isFalse();
        assertThat(report.accountsHeldAboveBalance()).containsExactly(UUID.fromString(account.id()));
    }

    /**
     * 20 authorizations of 20.00 race for 100.00: exactly 5 can win. Without the account lock several threads would read
     * the same holds sum and all get approved. Repeated, because a missing lock is only caught when the threads overlap.
     */
    @Test
    void parallelAuthorizationsCanNeverReserveMoreThanTheBalance() throws Exception {
        for (int round = 0; round < 5; round++) {
            clean();
            String token = tokenFor("race" + round + "@example.com");
            Acc account = open(token, "CHECKING");
            deposit(token, account, "100.00");
            mutableClock.set(Instant.now());
            List<Authorization> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                attempts.add(hold(account, "20.00"));
            }

            int approved = race(attempts.stream().<java.util.concurrent.Callable<Boolean>>map(a -> () -> {
                try {
                    holdService.reserve(a);
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            }).toList());

            assertThat(approved).as("round " + round).isEqualTo(5);
            assertThat(sumHolds(account)).as("round " + round).isEqualByComparingTo("100.00");
        }
    }

    /** A transfer and a card authorization compete for the same money: the two doors share one lock. */
    @Test
    void transfersAndAuthorizationsRacingForTheSameMoneyNeverOverspend() throws Exception {
        for (int round = 0; round < 5; round++) {
            clean();
            String token = tokenFor("mix" + round + "@example.com");
            Acc from = open(token, "CHECKING");
            Acc to = open(token, "SAVINGS");
            deposit(token, from, "100.00");
            mutableClock.set(Instant.now());
            List<java.util.concurrent.Callable<Boolean>> work = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                if (i % 2 == 0) {
                    work.add(() -> {
                        try {
                            ledger.post(TransactionType.TRANSFER, UUID.fromString(from.userId()), "mix-" + UUID.randomUUID(), "h",
                                    null, List.of(new Posting(UUID.fromString(from.id()), new BigDecimal("-30.00")),
                                            new Posting(UUID.fromString(to.id()), new BigDecimal("30.00"))));
                            return true;
                        } catch (InsufficientFundsException e) {
                            return false;
                        }
                    });
                } else {
                    Authorization a = hold(from, "30.00");
                    work.add(() -> {
                        try {
                            holdService.reserve(a);
                            return true;
                        } catch (InsufficientFundsException e) {
                            return false;
                        }
                    });
                }
            }

            int succeeded = race(work);

            BigDecimal balance = jdbc.queryForObject("select balance from accounts where id = ?::uuid", BigDecimal.class, from.id());
            assertThat(succeeded).as("round " + round).isEqualTo(3);
            assertThat(balance.subtract(sumHolds(from))).as("available, round " + round).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(reconciliation.check().isHealthy()).isTrue();
        }
    }

    /** Runs all tasks at the same moment; returns how many answered true. */
    private int race(List<java.util.concurrent.Callable<Boolean>> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            int yes = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    yes++;
                }
            }
            return yes;
        } finally {
            pool.shutdownNow();
        }
    }
}
