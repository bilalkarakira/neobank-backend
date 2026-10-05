package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The codec and the framing together over a real TCP connection on this machine. */
class LoopbackTest {

    private final Packager packager = new Packager();

    /** A tiny host: answers every 0100 with a 0110 that echoes the STAN and approves, and every 0800 with a 0810. */
    private CompletableFuture<Void> serve(ServerSocket server, int messages) {
        return CompletableFuture.runAsync(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                for (int i = 0; i < messages; i++) {
                    Message request = packager.unpack(Frame.read(in));
                    Message response = request.copyWithMti(Mti.responseFor(request.mti()));
                    if (request.mti().equals("0100")) {
                        response.remove(2).set(39, "00").set(38, "A1B2C3");
                    }
                    byte[] bytes = packager.pack(response);
                    // deliver the frame in two pieces on purpose: the reader must cope with that
                    byte[] framed = new byte[bytes.length + 2];
                    framed[0] = (byte) (bytes.length >>> 8);
                    framed[1] = (byte) bytes.length;
                    System.arraycopy(bytes, 0, framed, 2, bytes.length);
                    out.write(framed, 0, 3);
                    out.flush();
                    out.write(framed, 3, framed.length - 3);
                    out.flush();
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void anAuthorizationAndAnEchoTravelAcrossARealSocketAndComeBackAnswered() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> host = serve(server, 2);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();

                Message auth = Message.of("0100").set(2, "9999001234567891").set(3, "000000").set(4, "000000002500")
                        .set(11, "000123").set(41, "TERM0001");
                Frame.write(out, packager.pack(auth));
                Message authResponse = packager.unpack(Frame.read(in));

                assertThat(authResponse.mti()).isEqualTo("0110");
                assertThat(authResponse.get(39)).isEqualTo("00");
                assertThat(authResponse.get(38)).isEqualTo("A1B2C3");
                assertThat(authResponse.get(11)).isEqualTo("000123"); // the STAN comes back, so the caller can match it
                assertThat(authResponse.has(2)).isFalse(); // the PAN is not echoed

                Frame.write(out, packager.pack(Message.of("0800").set(7, "1005123045").set(11, "000124").set(70, "301")));
                Message echo = packager.unpack(Frame.read(in));
                assertThat(echo.mti()).isEqualTo("0810");
                assertThat(echo.get(70)).isEqualTo("301");
            }
            host.get(10, TimeUnit.SECONDS);
        }
    }
}
