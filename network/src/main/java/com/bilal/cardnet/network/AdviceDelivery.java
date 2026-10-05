package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tells the issuer about the payments the network approved on its own, oldest first, one at a time, until each is answered
 * with 0130/00. Runs whenever the link comes up and on a timer. At-least-once: the issuer's duplicate rule makes a repeat
 * harmless. An advice that was not acknowledged goes back to PENDING and the pass stops, so order is kept.
 */
public final class AdviceDelivery {

    private static final System.Logger LOG = System.getLogger(AdviceDelivery.class.getName());

    private final AdviceStore store;
    private final IssuerLink link;
    private final Duration issuerTimeout;
    private final Duration interval;
    private final Clock clock;
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running;
    private Thread thread;

    public AdviceDelivery(AdviceStore store, IssuerLink link, Duration issuerTimeout, Duration interval, Clock clock) {
        this.store = store;
        this.link = link;
        this.issuerTimeout = issuerTimeout;
        this.interval = interval;
        this.clock = clock;
        link.onUp(this::wake);
    }

    public void wake() {
        wake.release();
    }

    /** One pass: delivers while the link is up and advices are waiting. @return how many were delivered */
    public synchronized int deliverPending() {
        int delivered = 0;
        while (link.state() == IssuerLink.State.UP) {
            Optional<AdviceStore.Advice> claimed = store.claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            AdviceStore.Advice advice = claimed.get();
            boolean acknowledged = false;
            try {
                Message message = Messages.advice(store.request(advice), advice.authCode());
                Message answer = link.request(message, issuerTimeout);
                acknowledged = answer.mti().equals("0130") && "00".equals(answer.get(39));
            } catch (TimeoutException | IssuerLink.UnavailableException e) {
                // try again later
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "An advice could not be built ({0})", e.getClass().getSimpleName());
            }
            if (acknowledged) {
                store.markDelivered(advice.id(), clock.instant());
                delivered++;
            } else {
                store.release(advice.id());
                break;
            }
        }
        return delivered;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("advice-delivery").start(() -> {
            while (running) {
                try {
                    deliverPending();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Advice delivery pass failed ({0})", e.getClass().getSimpleName());
                }
                try {
                    wake.tryAcquire(interval.toMillis(), TimeUnit.MILLISECONDS);
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
