package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.Messages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The network's one persistent connection to an issuer. It signs on (0800/001), tests the line with an echo (0800/301)
 * and reconnects with a growing pause when anything goes wrong. Requests are matched to answers by (response MTI, STAN,
 * RRN), so several can be in flight at once.
 *
 * <p>An answer that arrives after its request gave up (timed out) is not lost: payment answers nobody is waiting for are
 * handed to {@link #onUnsolicited}, because an approval that came too late must be undone.
 */
public final class IssuerLink {

    public enum State { DOWN, UP }

    /** The link is down (or dropped while the request was waiting). */
    public static final class UnavailableException extends Exception {
        UnavailableException(String message) {
            super(message);
        }
    }

    private static final System.Logger LOG = System.getLogger(IssuerLink.class.getName());

    private final NetworkConfig.Route route;
    private final NetworkConfig config;
    private final Chaos chaos;
    private final Clock clock;
    private final Supplier<String> stans;
    private final Packager packager = new Packager();
    private final ConcurrentHashMap<String, CompletableFuture<Message>> waiting = new ConcurrentHashMap<>();
    private final List<Runnable> upListeners = new CopyOnWriteArrayList<>();
    private volatile Consumer<Message> unsolicited = m -> { };
    private volatile State state = State.DOWN;
    private volatile Conn conn;
    private volatile boolean running;
    private Thread supervisor;

    public IssuerLink(NetworkConfig.Route route, NetworkConfig config, Chaos chaos, Clock clock, Supplier<String> stans) {
        this.route = route;
        this.config = config;
        this.chaos = chaos;
        this.clock = clock;
        this.stans = stans;
    }

    public State state() {
        return state;
    }

    /** Requests currently waiting for an answer. */
    public int inFlight() {
        return waiting.size();
    }

    public void onUp(Runnable listener) {
        upListeners.add(listener);
    }

    public void onUnsolicited(Consumer<Message> handler) {
        this.unsolicited = handler;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        supervisor = Thread.ofVirtual().name("issuer-link").start(this::supervise);
    }

    public synchronized void stop() {
        running = false;
        if (supervisor != null) {
            supervisor.interrupt();
        }
        Conn c = conn;
        if (c != null) {
            c.close();
        }
    }

    /**
     * Sends a request and waits for its answer.
     *
     * @throws UnavailableException the link is down, or dropped while waiting
     * @throws TimeoutException no answer in time (a late answer may still arrive: see {@link #onUnsolicited})
     */
    public Message request(Message request, Duration timeout) throws UnavailableException, TimeoutException {
        Conn c = conn;
        if (state != State.UP || c == null || c.closed) {
            throw new UnavailableException("the issuer link is down");
        }
        try {
            return exchange(c, request, timeout);
        } catch (IOException e) {
            throw new UnavailableException(e.getMessage());
        }
    }

    // ---- one connection ----

    private final class Conn {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        volatile boolean closed;

        Conn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = new BufferedOutputStream(socket.getOutputStream());
        }

        synchronized void write(Message message) throws IOException {
            Frame.write(out, packager.pack(message));
        }

        void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private Message exchange(Conn c, Message request, Duration timeout) throws IOException, TimeoutException {
        String key = Messages.responseKey(request);
        CompletableFuture<Message> future = new CompletableFuture<>();
        if (waiting.putIfAbsent(key, future) != null) {
            throw new IOException("a request with the same key is already waiting");
        }
        try {
            c.write(request);
        } catch (IOException e) {
            waiting.remove(key, future);
            throw e;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            waiting.remove(key, future);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("the issuer link dropped");
        } catch (InterruptedException e) {
            waiting.remove(key, future);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private Conn connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(route.host(), route.port()), 1000);
        socket.setTcpNoDelay(true);
        Conn c = new Conn(socket);
        Thread.ofVirtual().name("issuer-link-reader").start(() -> read(c));
        return c;
    }

    private void read(Conn c) {
        try {
            while (true) {
                byte[] frame = Frame.read(c.in);
                if (frame == null) {
                    break;
                }
                deliver(packager.unpack(frame));
            }
        } catch (IOException | Iso8583Exception ignored) {
            // the line dropped or the issuer sent rubbish: either way this connection is over
        } finally {
            c.close();
        }
    }

    private static boolean isPaymentAnswer(Message m) {
        return m.mti().equals("0110") || m.mti().equals("0210");
    }

    private void deliver(Message answer) {
        long delay = isPaymentAnswer(answer) ? chaos.issuerResponseDelayMs() : 0;
        if (delay > 0) {
            // a slow issuer: it already decided, its answer reaches us late
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
                complete(answer);
            });
        } else {
            complete(answer);
        }
    }

    private void complete(Message answer) {
        CompletableFuture<Message> future = waiting.remove(Messages.keyOfResponse(answer));
        if (future != null) {
            future.complete(answer);
        } else if (isPaymentAnswer(answer)) {
            unsolicited.accept(answer);
        }
    }

    // ---- keeping the line up ----

    private void supervise() {
        Duration backoff = config.reconnectMin;
        while (running) {
            if (chaos.issuerDown()) {
                pause(Duration.ofMillis(50));
                continue;
            }
            Conn c = null;
            try {
                c = connect();
                Message answer = exchange(c, Messages.signOn(stans.get(), Messages.de7(clock.instant())), config.issuerTimeout);
                if (!"00".equals(answer.get(39))) {
                    throw new IOException("the issuer refused the sign-on");
                }
                conn = c;
                state = State.UP;
                backoff = config.reconnectMin;
                LOG.log(System.Logger.Level.INFO, "Issuer link up ({0}:{1})", route.host(), route.port());
                for (Runnable listener : upListeners) {
                    Thread.ofVirtual().start(listener);
                }
                keepUp(c);
            } catch (IOException | TimeoutException e) {
                LOG.log(System.Logger.Level.DEBUG, "Issuer link attempt failed ({0})", e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                return;
            } finally {
                if (state == State.UP) {
                    LOG.log(System.Logger.Level.WARNING, "Issuer link down");
                }
                state = State.DOWN;
                conn = null;
                if (c != null) {
                    c.close();
                }
                failWaiting();
            }
            pause(backoff);
            backoff = backoff.multipliedBy(2).compareTo(config.reconnectMax) > 0 ? config.reconnectMax : backoff.multipliedBy(2);
        }
    }

    /** Watches the line: an echo test every interval; two misses in a row, a closed socket or the chaos switch end it. */
    private void keepUp(Conn c) throws InterruptedException {
        long nextEcho = System.nanoTime() + config.echoInterval.toNanos();
        int misses = 0;
        while (running && !c.closed && !chaos.issuerDown()) {
            Thread.sleep(25);
            if (System.nanoTime() < nextEcho) {
                continue;
            }
            boolean ok;
            try {
                Message answer = exchange(c, Messages.echo(stans.get(), Messages.de7(clock.instant())), config.issuerTimeout);
                ok = "00".equals(answer.get(39));
            } catch (IOException | TimeoutException e) {
                ok = false;
            }
            misses = ok ? 0 : misses + 1;
            if (misses >= 2) {
                return;
            }
            nextEcho = System.nanoTime() + config.echoInterval.toNanos();
        }
    }

    private void failWaiting() {
        for (var entry : waiting.entrySet()) {
            CompletableFuture<Message> future = waiting.remove(entry.getKey());
            if (future != null) {
                future.completeExceptionally(new UnavailableException("the issuer link dropped"));
            }
        }
    }

    private void pause(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        while (running && System.nanoTime() < deadline) {
            try {
                Thread.sleep(Math.min(25, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                return;
            }
            if (chaos.issuerDown()) {
                return;
            }
        }
    }
}
