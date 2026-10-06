package com.bilal.cardnet.shared;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs a job again and again on one daemon thread. A job that throws is logged and tried again at the next beat. */
public final class PeriodicTask {

    private static final System.Logger LOG = System.getLogger(PeriodicTask.class.getName());

    private final String name;
    private final Runnable job;
    private ScheduledExecutorService executor;

    public PeriodicTask(String name, Runnable job) {
        this.name = name;
        this.job = job;
    }

    public synchronized void start(Duration interval) {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "{0} failed ({1}); trying again at the next beat", name, e.getClass().getSimpleName());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }
}
