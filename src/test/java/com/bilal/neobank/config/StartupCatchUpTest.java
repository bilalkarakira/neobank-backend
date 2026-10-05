package com.bilal.neobank.config;

import com.bilal.neobank.interest.InterestProperties;
import com.bilal.neobank.interest.InterestService;
import com.bilal.neobank.recurring.RecurringExecutor;
import com.bilal.neobank.recurring.RecurringProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The control flow of the startup catch-up: what runs, what is skipped, and that nothing can break startup. */
class StartupCatchUpTest {

    private static final InterestService.RunSummary INTEREST_DONE = new InterestService.RunSummary(false, 3, 1, 0);
    private static final RecurringExecutor.RunSummary RECURRING_DONE = new RecurringExecutor.RunSummary(false, 2, 1);

    private final InterestService interest = mock(InterestService.class);
    private final RecurringExecutor recurring = mock(RecurringExecutor.class);

    private StartupCatchUp catchUp(boolean interestOn, boolean recurringOn) {
        return new StartupCatchUp(interest, new InterestProperties(BigDecimal.valueOf(0.035), interestOn, "0 30 0 * * *"),
                recurring, new RecurringProperties(recurringOn, "0 5 * * * *", 10, 3, 31), Runnable::run);
    }

    @Test
    void runsBothJobsAndKeepsTheirResults() {
        when(interest.accrueAllDue()).thenReturn(INTEREST_DONE);
        when(recurring.runAllDue()).thenReturn(RECURRING_DONE);

        StartupCatchUp.Summary summary = catchUp(true, true).runAll();

        assertThat(summary.interest()).isEqualTo(INTEREST_DONE);
        assertThat(summary.recurring()).isEqualTo(RECURRING_DONE);
        assertThat(summary.failures()).isEmpty();
    }

    @Test
    void aJobWhoseSchedulerIsSwitchedOffIsNotRunAtStartupEither() {
        when(recurring.runAllDue()).thenReturn(RECURRING_DONE);

        StartupCatchUp.Summary summary = catchUp(false, true).runAll();

        verify(interest, never()).accrueAllDue();
        assertThat(summary.interest()).isNull();
        assertThat(summary.recurring()).isEqualTo(RECURRING_DONE);
    }

    @Test
    void theOtherJobIsAlsoSkippedWhenItsOwnSchedulerIsOff() {
        when(interest.accrueAllDue()).thenReturn(INTEREST_DONE);

        StartupCatchUp.Summary summary = catchUp(true, false).runAll();

        verify(recurring, never()).runAllDue();
        assertThat(summary.recurring()).isNull();
        assertThat(summary.interest()).isEqualTo(INTEREST_DONE);
    }

    @Test
    void withBothSchedulersOffNothingRuns() {
        StartupCatchUp.Summary summary = catchUp(false, false).runAll();

        verify(interest, never()).accrueAllDue();
        verify(recurring, never()).runAllDue();
        assertThat(summary.failures()).isEmpty();
    }

    @Test
    void aFailingInterestJobIsReportedAndDoesNotStopTheStandingOrders() {
        when(interest.accrueAllDue()).thenThrow(new IllegalStateException("interest broke"));
        when(recurring.runAllDue()).thenReturn(RECURRING_DONE);

        StartupCatchUp.Summary summary = catchUp(true, true).runAll();

        assertThat(summary.failures()).containsExactly("interest");
        assertThat(summary.interest()).isNull();
        assertThat(summary.recurring()).isEqualTo(RECURRING_DONE);
    }

    @Test
    void aFailingStandingOrderRunIsReportedAndKeepsTheInterestResult() {
        when(interest.accrueAllDue()).thenReturn(INTEREST_DONE);
        when(recurring.runAllDue()).thenThrow(new IllegalStateException("recurring broke"));

        StartupCatchUp.Summary summary = catchUp(true, true).runAll();

        assertThat(summary.failures()).containsExactly("recurring");
        assertThat(summary.interest()).isEqualTo(INTEREST_DONE);
        assertThat(summary.recurring()).isNull();
    }

    @Test
    void neverThrowsEvenWhenBothJobsFail() {
        when(interest.accrueAllDue()).thenThrow(new IllegalStateException("a"));
        when(recurring.runAllDue()).thenThrow(new IllegalArgumentException("something unexpected"));

        assertThatCode(() -> catchUp(true, true).runAll()).doesNotThrowAnyException();
        assertThat(catchUp(true, true).runAll().failures()).containsExactly("interest", "recurring");
    }

    @Test
    void theLastResultIsEmptyUntilAPassHasFinished() {
        StartupCatchUp catchUp = catchUp(true, true);
        assertThat(catchUp.lastSummary()).isNull();

        StartupCatchUp.Summary summary = catchUp.runAll();

        assertThat(catchUp.lastSummary()).isSameAs(summary);
    }

    @Test
    void theReadyEventHandsTheWorkToAnExecutorInsteadOfRunningItInline() {
        List<Runnable> queued = new ArrayList<>();
        StartupCatchUp catchUp = new StartupCatchUp(interest,
                new InterestProperties(BigDecimal.valueOf(0.035), true, "0 30 0 * * *"), recurring,
                new RecurringProperties(true, "0 5 * * * *", 10, 3, 31), queued::add);

        catchUp.onReady();

        assertThat(queued).hasSize(1);
        verify(interest, never()).accrueAllDue(); // startup has not waited for, or done, any of the work
        queued.get(0).run();
        verify(interest).accrueAllDue();
        verify(recurring).runAllDue();
    }

    @Test
    void byDefaultTheWorkRunsOnItsOwnDaemonThreadSoStartupIsNeverBlocked() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        when(interest.accrueAllDue()).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            done.countDown();
            return INTEREST_DONE;
        });
        StartupCatchUp catchUp = new StartupCatchUp(interest,
                new InterestProperties(BigDecimal.valueOf(0.035), true, "0 30 0 * * *"), recurring,
                new RecurringProperties(false, "0 5 * * * *", 10, 3, 31));

        catchUp.onReady();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.get()).isNotSameAs(Thread.currentThread());
        assertThat(worker.get().getName()).isEqualTo("startup-catch-up");
        assertThat(worker.get().isDaemon()).isTrue();
    }

}
