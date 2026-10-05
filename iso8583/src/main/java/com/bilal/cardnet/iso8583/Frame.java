package com.bilal.cardnet.iso8583;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * TCP framing: each message is preceded by its length as 2 bytes, big endian. A stream is read to the end of a frame no
 * matter how the bytes arrive (TCP delivers pieces, not messages), and a hostile length cannot make us allocate or wait
 * for more than the configured maximum.
 */
public final class Frame {

    public static final int DEFAULT_MAX = 4096;

    private Frame() {
    }

    public static void write(OutputStream out, byte[] message) throws IOException {
        if (message.length == 0 || message.length > 0xFFFF) {
            throw new Iso8583Exception("a frame carries 1 to 65535 bytes, not " + message.length);
        }
        byte[] framed = new byte[message.length + 2];
        framed[0] = (byte) (message.length >>> 8);
        framed[1] = (byte) message.length;
        System.arraycopy(message, 0, framed, 2, message.length);
        out.write(framed); // one write: the header and the body never travel apart
        out.flush();
    }

    /** The next message, or null when the stream ended cleanly BETWEEN frames. */
    public static byte[] read(InputStream in, int maxLength) throws IOException {
        int high = in.read();
        if (high < 0) {
            return null;
        }
        int low = in.read();
        if (low < 0) {
            throw new EOFException("the stream ended inside a length header");
        }
        int length = (high << 8) | low;
        if (length == 0) {
            throw new Iso8583Exception("a frame of length 0");
        }
        if (length > maxLength) {
            throw new Iso8583Exception("a frame of " + length + " bytes exceeds the maximum of " + maxLength);
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                throw new EOFException("the stream ended " + (length - read) + " byte(s) short of a " + length + "-byte frame");
            }
            read += n;
        }
        return body;
    }

    public static byte[] read(InputStream in) throws IOException {
        return read(in, DEFAULT_MAX);
    }
}
