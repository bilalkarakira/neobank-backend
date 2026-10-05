package com.bilal.cardnet.shared;

import com.bilal.cardnet.iso8583.Message;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Delivers the queued reversals of one owner until the issuer acknowledges each (0410 with 00). A failed or unanswered
 * attempt is retried on the {@link RetrySchedule}; a reversal that is still unacknowledged at its deadline is marked
 * FAILED (a human has to look at it). State lives in the database, so a restart picks up where it stopped.
 *
 * <p>{@code sender} gets the 0400 to send and returns the answer, or empty when there was none (down, timeout).
 */
public final class ReversalWorker {

    private static final System.Logger LOG = System.getLogger(ReversalWorker.class.getName());

    private final String owner;
    private final PendingReversalStore store;
    private final Function<Message, Optional<Message>> sender;
    private final Supplier<String> stans;
    private final Clock clock;
    private final RetrySchedule schedule;
    private final Consumer<UUID> onDone;
    private final BiConsumer<UUID, String> onFailed;
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running;
    private Thread thread;

    public ReversalWorker(String owner, PendingReversalStore store, Function<Message, Optional<Message>> sender,
            Supplier<String> stans, Clock clock, RetrySchedule schedule, Consumer<UUID> onDone,
            BiConsumer<UUID, String> onFailed) {
        this.owner = owner;
        this.store = store;
        this.sender = sender;
        this.stans = stans;
        this.clock = clock;
        this.schedule = schedule;
        this.onDone = onDone;
        this.onFailed = onFailed;
    }

    /** One pass over what is due now. Safe to call from tests; never overlaps with the background loop. */
    public synchronized int tick() {
        Instant now = clock.instant();
        List<PendingReversalStore.Due> dues = store.due(owner, now, 20);
        for (PendingReversalStore.Due due : dues) {
            if (!due.deadline().isAfter(now)) {
                store.markFailed(due.id(), now, "deadline", null);
                onFailed.accept(due.id(), "deadline");
                continue;
            }
            Message original;
            try {
                original = store.original(due);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "A queued reversal cannot be read ({0}); marking it failed", e.getClass().getSimpleName());
                store.markFailed(due.id(), now, "unreadable", null);
                onFailed.accept(due.id(), "unreadable");
                continue;
            }
            Message reversal = Messages.reversalOf(original, stans.get(), Messages.de7(now));
            Optional<Message> answer;
            try {
                answer = sender.apply(reversal);
            } catch (RuntimeException e) {
                answer = Optional.empty();
            }
            String last = null;
            if (answer.isPresent() && "0410".equals(answer.get().mti())) {
                last = answer.get().get(39);
                if ("00".equals(last)) {
                    store.markDone(due.id(), now, last);
                    onDone.accept(due.id());
                    continue;
                }
                if ("30".equals(last)) { // the issuer will never accept this one (for example a different amount)
                    store.markFailed(due.id(), now, "refused 30", last);
                    onFailed.accept(due.id(), "refused 30");
                    continue;
                }
            }
            int attempts = due.attempts() + 1;
            store.reschedule(due.id(), attempts, now.plus(schedule.delayAfter(attempts)), last);
        }
        return dues.size();
    }

    /** Asks the background loop to look now instead of at its next poll. */
    public void wake() {
        wake.release();
    }

    public synchronized void start(java.time.Duration pollInterval) {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("reversals-" + owner.toLowerCase()).start(() -> {
            while (running) {
                try {
                    tick();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Reversal pass failed ({0})", e.getClass().getSimpleName());
                }
                try {
                    wake.tryAcquire(pollInterval.toMillis(), TimeUnit.MILLISECONDS);
                    wake.drainPermits();
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
