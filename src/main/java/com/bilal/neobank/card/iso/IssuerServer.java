package com.bilal.neobank.card.iso;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.neobank.card.CardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The issuer's ISO 8583 interface: a TCP server (loopback only, no TLS: a simulation) with one virtual thread per
 * connection and plain blocking I/O. Frames are a 2-byte length plus the message ({@link Frame}); the codec is strict,
 * so a malformed frame or message closes the connection instead of being guessed at. Nothing about a message (and so
 * no PAN) is ever logged: only counts and exception class names.
 */
@Component
public class IssuerServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IssuerServer.class);

    private final CardProperties.Listener config;
    private final IssuerMessageHandler handler;
    private final Packager packager = new Packager();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final AtomicLong closedForBadInput = new AtomicLong();
    private final AtomicLong refusedOverCapacity = new AtomicLong();
    private Semaphore capacity;
    private volatile ServerSocket server;

    public IssuerServer(CardProperties properties, IssuerMessageHandler handler) {
        this.config = properties.listener();
        this.handler = handler;
    }

    @Override
    public synchronized void start() {
        if (!config.enabled() || server != null) {
            return;
        }
        try {
            capacity = new Semaphore(config.maxConnections());
            server = new ServerSocket(config.port(), 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("The issuer interface could not listen on port " + config.port(), e);
        }
        Thread.ofVirtual().name("issuer-accept").start(this::acceptLoop);
        log.info("Issuer ISO 8583 interface listening on 127.0.0.1:{}", server.getLocalPort());
    }

    @Override
    public synchronized void stop() {
        ServerSocket s = server;
        server = null;
        if (s == null) {
            return;
        }
        closeQuietly(s);
        open.forEach(IssuerServer::closeQuietly);
        log.info("Issuer ISO 8583 interface stopped");
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    /** The port actually bound (useful when the configured port is 0). */
    public int port() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    /** Connections closed because the peer sent a malformed frame or a message that cannot be handled. */
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
                    closeQuietly(socket);
                    continue;
                }
                open.add(socket);
                Thread.ofVirtual().name("issuer-conn").start(() -> serve(socket));
            } catch (IOException e) {
                if (!s.isClosed()) {
                    log.warn("Issuer accept failed ({})", e.getClass().getSimpleName());
                }
            }
        }
    }

    private void serve(Socket socket) {
        IssuerMessageHandler.Connection connection = new IssuerMessageHandler.Connection();
        try (socket) {
            socket.setSoTimeout((int) config.idleTimeout().toMillis());
            var in = new BufferedInputStream(socket.getInputStream());
            var out = new BufferedOutputStream(socket.getOutputStream());
            while (true) {
                byte[] frame = Frame.read(in);
                if (frame == null) {
                    return; // the peer closed between frames: normal
                }
                Message request = packager.unpack(frame);
                Optional<Message> response = handler.handle(request, connection);
                if (response.isEmpty()) {
                    closedForBadInput.incrementAndGet();
                    return;
                }
                Frame.write(out, packager.pack(response.get()));
            }
        } catch (Iso8583Exception e) {
            closedForBadInput.incrementAndGet();
            log.warn("Closed an issuer connection after bad input ({})", e.getClass().getSimpleName());
        } catch (SocketTimeoutException e) {
            log.debug("Closed an idle issuer connection");
        } catch (IOException e) {
            log.debug("Issuer connection ended ({})", e.getClass().getSimpleName());
        } finally {
            open.remove(socket);
            capacity.release();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}
