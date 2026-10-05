package com.bilal.neobank.config;

import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.bilal.neobank.interest.InterestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catch-up wired into the real application: with both jobs switched on it does real work (interest for a finished
 * month, a standing order that is due), does it only once, and survives one job failing. The cron expressions are set
 * to a yearly date so the timers themselves never fire during the test.
 */
@SpringBootTest(properties = {
        "app.catch-up.enabled=true",
        "app.interest.scheduler-enabled=true", "app.interest.cron=0 0 0 1 1 *",
        "app.recurring.scheduler-enabled=true", "app.recurring.cron=0 0 0 1 1 *" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class StartupCatchUpIntegrationTest extends AnalyticsTestBase {

    @Autowired
    StartupCatchUp catchUp;

    @Autowired
    MutableClock mutableClock;

    @MockitoSpyBean
    InterestService interestService;

    @BeforeEach
    void waitForTheStartupRunThenFixTheDate() throws Exception {
        // the real startup pass runs on its own thread right after the context is ready: let it finish before the
        // tests start changing data, or it could be mistaken for the work a test triggers itself
        long deadline = System.currentTimeMillis() + 20_000;
        while (catchUp.lastSummary() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(catchUp.lastSummary()).as("the startup pass ran").isNotNull();
        mutableClock.set(Instant.parse("2026-10-15T12:00:00Z")); // September is the last finished month
    }

    private Acc fundedSavingsWithAFinishedMonth() throws Exception {
        Acc savings = open(tokenFor("saver@example.com"), "SAVINGS");
        jdbc.update("update accounts set created_at = ?::timestamptz where id = ?::uuid", "2026-08-01T00:00:00Z", savings.id());
        depositOn(savings, "1000.00", Instant.parse("2026-09-01T00:10:00Z")); // 1000 for all 30 days of September
        return savings;
    }

    private Acc dueStandingOrder() throws Exception {
        String token = tokenFor("payer@example.com");
        Acc from = open(token, "CHECKING");
        Acc to = open(tokenFor("payee@example.com"), "CHECKING");
        depositOn(from, "100.00", Instant.parse("2026-10-14T09:00:00Z"));
        mockMvc.perform(post("/api/recurring-payments").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceAccountId":"%s","destinationIban":"%s","amount":20.00,"frequency":"MONTHLY","startDate":"%s"}
                        """.formatted(from.id(), to.iban(), today()))).andExpect(status().isCreated());
        return to;
    }

    private BigDecimal balance(Acc account) {
        return jdbc.queryForObject("select balance from accounts where id = ?::uuid", BigDecimal.class, account.id());
    }

    @Test
    void catchesUpAFinishedMonthOfInterestAndADueStandingOrderInOnePass() throws Exception {
        Acc savings = fundedSavingsWithAFinishedMonth();
        Acc payee = dueStandingOrder();

        StartupCatchUp.Summary summary = catchUp.runAll();

        // September: 1000 x 0.0028709 = 2.87 (August held nothing, so it is recorded and skipped)
        assertThat(summary.interest()).isEqualTo(new InterestService.RunSummary(false, 1, 1, 0));
        assertThat(balance(savings)).isEqualByComparingTo("1002.87");
        assertThat(summary.recurring().succeeded()).isEqualTo(1);
        assertThat(balance(payee)).isEqualByComparingTo("20.00");
        assertThat(summary.failures()).isEmpty();
        assertBooksBalance();
    }

    @Test
    void aSecondPassDoesNotPayAnythingAgain() throws Exception {
        Acc savings = fundedSavingsWithAFinishedMonth();
        Acc payee = dueStandingOrder();
        catchUp.runAll();

        StartupCatchUp.Summary again = catchUp.runAll();

        assertThat(again.interest().credited()).isZero();
        assertThat(again.recurring().succeeded()).isZero();
        assertThat(balance(savings)).isEqualByComparingTo("1002.87");
        assertThat(balance(payee)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'INTEREST'", Integer.class)).isEqualTo(1);
    }

    @Test
    void aFailingInterestJobStillLetsTheDueStandingOrderThrough() throws Exception {
        Acc payee = dueStandingOrder();
        doThrow(new IllegalStateException("interest broke")).when(interestService).accrueAllDue();

        StartupCatchUp.Summary summary = catchUp.runAll();

        assertThat(summary.failures()).containsExactly("interest");
        assertThat(balance(payee)).isEqualByComparingTo("20.00");
    }

    @Test
    void theSameCatchUpTwiceAtOnceStillPaysOnlyOnce() throws Exception {
        Acc savings = fundedSavingsWithAFinishedMonth();
        Acc payee = dueStandingOrder();

        // two instances booting together: the jobs' own locks and keys make the second pass a no-op
        Thread other = new Thread(catchUp::runAll);
        other.start();
        catchUp.runAll();
        other.join(30_000);

        assertThat(balance(savings)).isEqualByComparingTo("1002.87");
        assertThat(balance(payee)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'TRANSFER'", Integer.class)).isEqualTo(1);
        assertBooksBalance();
        assertThat(UUID.fromString(savings.id())).isNotNull();
    }
}
