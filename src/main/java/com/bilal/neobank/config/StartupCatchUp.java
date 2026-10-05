package com.bilal.neobank.config;

import com.bilal.neobank.interest.InterestProperties;
import com.bilal.neobank.interest.InterestService;
import com.bilal.neobank.recurring.RecurringExecutor;
import com.bilal.neobank.recurring.RecurringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Runs the interest job and the due standing orders once when the application is ready.
 *
 * Both jobs run on a timer (interest daily, standing orders hourly). If the app was down when a tick was due, the work
 * would otherwise wait for the next tick: interest by up to a day, a standing order by up to an hour. Both jobs are
 * idempotent and take their own advisory lock, so running them at startup is safe even when several instances boot
 * together or a tick fires at the same moment.
 *
 * It runs on its own thread so a long catch-up never delays startup, and nothing here can fail startup: a job that
 * throws is logged and the other one still runs. A job whose scheduler is switched off is not run either, so the two
 * existing switches keep meaning "this job does not run by itself".
 *
 * Switch the whole thing off with app.catch-up.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "app.catch-up.enabled", havingValue = "true", matchIfMissing = true)
public class StartupCatchUp {

    /** What one pass did: a job that was skipped (switched off) or failed has no summary. */
    public record Summary(InterestService.RunSummary interest, RecurringExecutor.RunSummary recurring,
            List<String> failures) {
    }

    private static final Logger log = LoggerFactory.getLogger(StartupCatchUp.class);

    private final InterestService interestService;
    private final InterestProperties interestProperties;
    private final RecurringExecutor recurringExecutor;
    private final RecurringProperties recurringProperties;
    private final Executor executor;
    private volatile Summary lastSummary;

    /** The constructor Spring uses (with two constructors it cannot choose by itself). */
    @Autowired
    public StartupCatchUp(InterestService interestService, InterestProperties interestProperties,
            RecurringExecutor recurringExecutor, RecurringProperties recurringProperties) {
        this(interestService, interestProperties, recurringExecutor, recurringProperties, StartupCatchUp::ownThread);
    }

    StartupCatchUp(InterestService interestService, InterestProperties interestProperties,
            RecurringExecutor recurringExecutor, RecurringProperties recurringProperties, Executor executor) {
        this.interestService = interestService;
        this.interestProperties = interestProperties;
        this.recurringExecutor = recurringExecutor;
        this.recurringProperties = recurringProperties;
        this.executor = executor;
    }

    private static void ownThread(Runnable work) {
        Thread thread = new Thread(work, "startup-catch-up");
        thread.setDaemon(true); // never keeps the JVM alive on shutdown
        thread.start();
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        executor.execute(this::runAll);
    }

    /** One pass over both jobs. Never throws. */
    public Summary runAll() {
        List<String> failures = new ArrayList<>();
        InterestService.RunSummary interest = null;
        RecurringExecutor.RunSummary recurring = null;

        if (interestProperties.schedulerEnabled()) {
            try {
                interest = interestService.accrueAllDue();
                log.info("Startup catch-up, interest: {}", interest);
            } catch (RuntimeException e) {
                failures.add("interest");
                log.error("Startup catch-up of interest failed: {}", e.toString());
            }
        }
        if (recurringProperties.schedulerEnabled()) {
            try {
                recurring = recurringExecutor.runAllDue();
                log.info("Startup catch-up, recurring payments: {}", recurring);
            } catch (RuntimeException e) {
                failures.add("recurring");
                log.error("Startup catch-up of recurring payments failed: {}", e.toString());
            }
        }
        Summary summary = new Summary(interest, recurring, List.copyOf(failures));
        lastSummary = summary;
        return summary;
    }

    /** The result of the most recent pass, or null before the first one has finished. */
    public Summary lastSummary() {
        return lastSummary;
    }
}
