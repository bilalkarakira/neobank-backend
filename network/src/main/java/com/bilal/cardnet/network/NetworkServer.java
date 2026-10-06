package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.Messages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The side acquirers connect to: loopback only, no TLS, one virtual thread per connection. Same rules as the issuer's
 * interface: a sign-on (0800/001) first, strict codec (junk closes that one connection), a connection cap. The network's
 * own answers can be held back by the chaos switch to simulate a slow network.
 */
public final class NetworkServer {

    private static final System.Logger LOG = System.getLogger(NetworkServer.class.getName());

    private final NetworkConfig config;
    private final PaymentRouter router;
    private final Chaos chaos;
    private final Packager packager = new Packager();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final AtomicLong closedForBadInput = new AtomicLong();
    private final AtomicLong refusedOverCapacity = new AtomicLong();
    private Semaphore capacity;
    private volatile ServerSocket server;

    public NetworkServer(NetworkConfig config, PaymentRouter router, Chaos chaos) {
        this.config = config;
        this.router = router;
        this.chaos = chaos;
    }

    /** The address the acquirer port is bound to. */
    public synchronized InetAddress boundAddress() {
        return server.getInetAddress();
    }

    public synchronized void start() throws IOException {
        capacity = new Semaphore(config.maxConnections);
        server = new ServerSocket(config.acquirerPort, 50, InetAddress.getByName(config.bindAddress));
        Thread.ofVirtual().name("network-accept").start(this::acceptLoop);
        LOG.log(System.Logger.Level.INFO, "Network listening for acquirers on 127.0.0.1:{0}", server.getLocalPort());
    }

    public synchronized void stop() {
        ServerSocket s = server;
        server = null;
        if (s == null) {
            return;
        }
        close(s);
        open.forEach(NetworkServer::close);
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public long closedForBadInput() {
        return closedForBadInput.get();
    }

    public long refusedOverCapacity() {
        return refusedOverCapacity.get();
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket socket = s.accept();
                if (!capacity.tryAcquire()) {
                    refusedOverCapacity.incrementAndGet();
                    close(socket);
                    continue;
                }
                open.add(socket);
                Thread.ofVirtual().name("network-conn").start(() -> serve(socket));
            } catch (IOException e) {
                if (!s.isClosed()) {
                    LOG.log(System.Logger.Level.WARNING, "Accept failed ({0})", e.getClass().getSimpleName());
                }
            }
        }
    }

    private void serve(Socket socket) {
        boolean[] signedOn = { false };
        try (socket) {
            socket.setSoTimeout((int) config.idleTimeout.toMillis());
            var in = new BufferedInputStream(socket.getInputStream());
            var out = new BufferedOutputStream(socket.getOutputStream());
            while (true) {
                Message request;
                try {
                    byte[] frame = Frame.read(in);
                    if (frame == null) {
                        return;
                    }
                    request = packager.unpack(frame);
                } catch (Iso8583Exception e) {
                    // counted BEFORE the socket closes, so anyone who sees the close also sees the count
                    closedForBadInput.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING, "Closed an acquirer connection after bad input ({0})", e.getClass().getSimpleName());
                    return;
                }
                Optional<Message> response = handle(request, signedOn);
                if (response.isEmpty()) {
                    closedForBadInput.incrementAndGet();
                    return;
                }
                long hold = chaos.acquirerResponseDelayMs();
                if (hold > 0 && !response.get().mti().equals("0810")) {
                    sleep(hold); // a slow network: the answer is ready but sits here
                }
                Frame.write(out, packager.pack(response.get()));
            }
        } catch (SocketTimeoutException e) {
            LOG.log(System.Logger.Level.DEBUG, "Closed an idle acquirer connection");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Acquirer connection ended ({0})", e.getClass().getSimpleName());
        } finally {
            open.remove(socket);
            capacity.release();
        }
    }

    private Optional<Message> handle(Message request, boolean[] signedOn) {
        switch (request.mti()) {
            case "0800" -> {
                Message response = Message.of("0810");
                for (int field : List.of(7, 11, 70)) {
                    if (request.has(field)) {
                        response.set(field, request.get(field));
                    }
                }
                String code = request.get(70);
                if (code == null) {
                    return Optional.of(response.set(39, "30"));
                }
                switch (code) {
                    case "001" -> signedOn[0] = true;
                    case "002" -> signedOn[0] = false;
                    case "301" -> {
                        // echo test: nothing to do
                    }
                    default -> {
                        return Optional.of(response.set(39, "12"));
                    }
                }
                return Optional.of(response.set(39, "00"));
            }
            case "0100", "0200" -> {
                return Optional.of(signedOn[0] ? router.authorize(request) : Messages.response(request, "91"));
            }
            case "0400" -> {
                return Optional.of(signedOn[0] ? router.reverse(request) : Messages.response(request, "91"));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
