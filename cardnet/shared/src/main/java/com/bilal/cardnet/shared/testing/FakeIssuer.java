package com.bilal.cardnet.shared.testing;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.Messages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * For tests only: a miniature of the real NeoBank issuer that speaks the same protocol and follows the same rules that
 * matter to the network (sign-on per connection, duplicates replayed, reversals release a hold and leave a tombstone
 * when the original has not arrived, advices recorded as holds and overriding an earlier decline). It does not decide
 * on funds or cards: {@link #decision} says what each payment gets. What it ends up holding is what tests check.
 */
public final class FakeIssuer implements AutoCloseable {

    public enum Status { AUTHORIZED, DECLINED, REVERSED }

    /** What the issuer remembers about one request. */
    public record Entry(String key, String pan, String acquirer, String stan, String de7, BigDecimal amount, Status status,
            String code, String authCode, boolean viaStandIn) {
        Entry with(Status newStatus, String newCode, String newAuth, boolean standIn) {
            return new Entry(key, pan, acquirer, stan, de7, amount, newStatus, newCode, newAuth, standIn);
        }
    }

    private final Packager packager = new Packager();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<Message> received = new CopyOnWriteArrayList<>();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final AtomicInteger authCounter = new AtomicInteger();
    private ServerSocket server;
    private int port;

    /** The response code each payment gets (0100 and 0200). Default: approve everything. */
    public volatile Function<Message, String> decision = m -> "00";
    /** DE 48 of a declined payment's answer (the bank's request for the customer's approval), or null for none. */
    public volatile Function<Message, String> challenge = m -> null;
    /** Payments are answered this long AFTER they were processed (a slow issuer: it did decide). */
    public volatile long responseDelayMs;
    /** Payments are processed but never answered. */
    public volatile boolean dropPaymentResponses;
    /** Advices (0120) are recorded but their answer (0130) never leaves: the sender must try again. */
    public volatile boolean dropAdviceAnswers;
    /** The answers to the next N reversals (0410) are dropped after the reversal was applied: the sender must try again. */
    public volatile int dropReversalAnswers;
    /** Network management messages (echo) are not answered, so the link looks dead. */
    public volatile boolean ignoreNetworkManagement;

    public FakeIssuer() {
        start(0);
    }

    public synchronized void start(int wantedPort) {
        try {
            ServerSocket fresh = new ServerSocket();
            fresh.setReuseAddress(true); // a restart on the same port must not wait for the old connections to time out
            fresh.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), wantedPort), 50);
            server = fresh;
            port = fresh.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ServerSocket s = server;
        Thread.ofVirtual().name("fake-issuer-accept").start(() -> {
            while (!s.isClosed()) {
                try {
                    Socket socket = s.accept();
                    open.add(socket);
                    Thread.ofVirtual().name("fake-issuer-conn").start(() -> serve(socket));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    /** Stops listening and drops every connection (the issuer "goes down"). Its memory of requests stays. */
    public synchronized void stop() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // closing
        }
        open.forEach(s -> {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        });
        open.clear();
    }

    /** Starts again on the same port. */
    public synchronized void restart() {
        start(port);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        stop();
    }

    // ---- what tests look at ----

    public List<Message> received() {
        return List.copyOf(received);
    }

    public List<Message> received(String mti) {
        return received.stream().filter(m -> m.mti().equals(mti)).toList();
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public long holds() {
        return entries.values().stream().filter(e -> e.status() == Status.AUTHORIZED).count();
    }

    public Entry entryForStan(String stan) {
        return entries.values().stream().filter(e -> e.stan().equals(stan)).findFirst().orElse(null);
    }

    // ---- the issuer ----

    private void serve(Socket socket) {
        boolean[] signedOn = { false };
        try (socket) {
            var in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (true) {
                byte[] frame = Frame.read(in);
                if (frame == null) {
                    return;
                }
                Message request = packager.unpack(frame);
                received.add(request);
                Message response = handle(request, signedOn);
                if (response == null) {
                    continue;
                }
                boolean payment = request.mti().equals("0100") || request.mti().equals("0200");
                if (payment && dropPaymentResponses) {
                    continue;
                }
                if (request.mti().equals("0120") && dropAdviceAnswers) {
                    continue;
                }
                if (request.mti().equals("0400") && dropReversalAnswers > 0) {
                    dropReversalAnswers--;
                    continue;
                }
                long delay = payment ? responseDelayMs : 0;
                if (delay > 0) {
                    OutputStream target = out;
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(delay);
                            write(target, response);
                        } catch (InterruptedException | IOException ignored) {
                            // the connection went away meanwhile
                        }
                    });
                } else {
                    write(out, response);
                }
            }
        } catch (IOException | Iso8583Exception ignored) {
            // the peer left or sent rubbish
        } finally {
            open.remove(socket);
        }
    }

    private synchronized void write(OutputStream out, Message response) throws IOException {
        Frame.write(out, packager.pack(response));
    }

    private Message handle(Message request, boolean[] signedOn) {
        switch (request.mti()) {
            case "0800" -> {
                if (ignoreNetworkManagement) {
                    return null;
                }
                Message response = Message.of("0810");
                for (int field : List.of(7, 11, 70)) {
                    if (request.has(field)) {
                        response.set(field, request.get(field));
                    }
                }
                String code = request.get(70);
                if ("001".equals(code)) {
                    signedOn[0] = true;
                } else if ("002".equals(code)) {
                    signedOn[0] = false;
                }
                return response.set(39, "00");
            }
            case "0100", "0200", "0400", "0120" -> {
                if (!signedOn[0]) {
                    return Messages.response(request, "91");
                }
                return switch (request.mti()) {
                    case "0400" -> reversal(request);
                    case "0120" -> advice(request);
                    default -> payment(request);
                };
            }
            default -> {
                return null;
            }
        }
    }

    private static String keyOf(Message m) {
        return String.join("|", m.get(2), Messages.padAcquirer(m.get(32)), m.get(11), m.get(37), m.get(41), m.get(13));
    }

    private synchronized Message payment(Message request) {
        String key = keyOf(request);
        Entry existing = entries.get(key);
        BigDecimal amount = Messages.amount(request);
        if (existing != null) {
            if (existing.amount().compareTo(amount) != 0) {
                return Messages.response(request, "94");
            }
            Message replay = Messages.response(request, existing.code());
            return existing.authCode() == null ? replay : replay.set(38, existing.authCode());
        }
        String code = decision.apply(request);
        String auth = "00".equals(code) ? "FK%04d".formatted(authCounter.incrementAndGet()) : null;
        entries.put(key, new Entry(key, request.get(2), Messages.padAcquirer(request.get(32)), request.get(11), request.get(7),
                amount, auth != null ? Status.AUTHORIZED : Status.DECLINED, code, auth, false));
        Message response = Messages.response(request, code);
        if (auth == null) {
            String data = challenge.apply(request);
            return data == null ? response : response.set(48, data);
        }
        return response.set(38, auth);
    }

    private synchronized Message reversal(Message request) {
        Messages.Original original;
        try {
            original = Messages.original(request);
        } catch (Iso8583Exception e) {
            return Messages.response(request, "30");
        }
        String pan = request.get(2);
        Entry found = entries.values().stream().filter(e -> e.pan().equals(pan) && e.acquirer().equals(original.acquirer())
                && e.stan().equals(original.stan()) && e.de7().equals(original.de7())).findFirst().orElse(null);
        if (found == null) {
            String key = String.join("|", pan, original.acquirer(), original.stan(), request.get(37), request.get(41), request.get(13));
            entries.put(key, new Entry(key, pan, original.acquirer(), original.stan(), original.de7(), Messages.amount(request),
                    Status.REVERSED, "05", null, false)); // the tombstone
        } else if (found.status() == Status.AUTHORIZED) {
            entries.put(found.key(), found.with(Status.REVERSED, found.code(), found.authCode(), found.viaStandIn()));
        }
        return Messages.response(request, "00");
    }

    private synchronized Message advice(Message request) {
        String key = keyOf(request);
        Entry existing = entries.get(key);
        if (existing == null) {
            entries.put(key, new Entry(key, request.get(2), Messages.padAcquirer(request.get(32)), request.get(11), request.get(7),
                    Messages.amount(request), Status.AUTHORIZED, "00", request.get(38), true));
        } else if (existing.status() == Status.DECLINED) { // the network approved, so the issuer carries it
            entries.put(key, existing.with(Status.AUTHORIZED, "00", request.get(38), true));
        }
        return Messages.response(request, "00");
    }
}
