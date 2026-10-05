package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.shared.Messages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * The acquirer's line to the network: one short connection per exchange (connect, sign on, send, read the answer, close).
 * That keeps a stuck request from blocking the next one and the reversal worker from queueing behind a payment. Any failure
 * to get a well-formed answer in time is simply "no answer" (empty): the caller decides what that means.
 */
public final class NetworkClient {

    private final String host;
    private final int port;
    private final Clock clock;
    private final Packager packager = new Packager();

    public NetworkClient(String host, int port, Clock clock) {
        this.host = host;
        this.port = port;
        this.clock = clock;
    }

    /** @param timeout how long to wait for the answer to {@code request} (the sign-on has its own short allowance) */
    public Optional<Message> exchange(Message request, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) Math.min(timeout.toMillis(), 2000));
            socket.setTcpNoDelay(true);
            var in = new BufferedInputStream(socket.getInputStream());
            var out = new BufferedOutputStream(socket.getOutputStream());

            socket.setSoTimeout(2000);
            Frame.write(out, packager.pack(Messages.signOn("000001", Messages.de7(clock.instant()))));
            byte[] signOnAnswer = Frame.read(in);
            if (signOnAnswer == null || !"00".equals(packager.unpack(signOnAnswer).get(39))) {
                return Optional.empty();
            }

            socket.setSoTimeout((int) timeout.toMillis());
            Frame.write(out, packager.pack(request));
            byte[] answer = Frame.read(in);
            return answer == null ? Optional.empty() : Optional.of(packager.unpack(answer));
        } catch (IOException | Iso8583Exception e) {
            return Optional.empty();
        }
    }
}
