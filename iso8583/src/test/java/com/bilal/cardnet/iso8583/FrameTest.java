package com.bilal.cardnet.iso8583;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameTest {

    /** Hands out one byte per read call, the way a slow TCP connection may. */
    private static InputStream trickle(byte[] data) {
        return new InputStream() {
            private int position;

            @Override
            public int read() {
                return position < data.length ? data[position++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                if (length == 0) {
                    return 0;
                }
                int next = read();
                if (next < 0) {
                    return -1;
                }
                target[offset] = (byte) next;
                return 1;
            }
        };
    }

    private static byte[] framed(byte[] message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frame.write(out, message);
        return out.toByteArray();
    }

    private static byte[] readWire(byte[] wire, int max) {
        try {
            return Frame.read(new ByteArrayInputStream(wire), max);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aFrameIsATwoByteBigEndianLengthThenTheMessage() throws IOException {
        byte[] message = new byte[300];
        java.util.Arrays.fill(message, (byte) 'A');

        byte[] wire = framed(message);

        assertThat(wire).hasSize(302);
        assertThat(wire[0]).isEqualTo((byte) 0x01); // 300 = 0x012C
        assertThat(wire[1]).isEqualTo((byte) 0x2C);
        assertThat(Frame.read(new ByteArrayInputStream(wire))).isEqualTo(message);
    }

    @Test
    void severalFramesInARowAreReadOneByOneAndTheEndIsReportedAsNull() throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Frame.write(wire, "first".getBytes());
        Frame.write(wire, "second message".getBytes());
        InputStream in = new ByteArrayInputStream(wire.toByteArray());

        assertThat(Frame.read(in)).isEqualTo("first".getBytes());
        assertThat(Frame.read(in)).isEqualTo("second message".getBytes());
        assertThat(Frame.read(in)).isNull();
    }

    @Test
    void aMessageArrivingOneByteAtATimeIsStillReadWhole() throws IOException {
        byte[] message = "0200 split into pieces by the network".getBytes();

        assertThat(Frame.read(trickle(framed(message)))).isEqualTo(message);
    }

    @Test
    void aStreamThatEndsInsideAHeaderOrABodyIsAnErrorNotANullOrAShortMessage() throws IOException {
        byte[] wire = framed("hello world".getBytes());

        assertThatThrownBy(() -> Frame.read(new ByteArrayInputStream(new byte[] { 0x00 }))).isInstanceOf(EOFException.class);
        for (int cut = 2; cut < wire.length; cut++) {
            byte[] shortWire = java.util.Arrays.copyOf(wire, cut);
            assertThatThrownBy(() -> Frame.read(new ByteArrayInputStream(shortWire))).as("cut at %d", cut).isInstanceOf(EOFException.class);
        }
    }

    @Test
    void aZeroLengthAndAnOversizedLengthAreRefusedBeforeAnythingIsAllocated() throws IOException {
        assertThatThrownBy(() -> Frame.read(new ByteArrayInputStream(new byte[] { 0, 0 }))).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> Frame.read(new ByteArrayInputStream(new byte[] { (byte) 0xFF, (byte) 0xFF, 1, 2, 3 })))
                .isInstanceOf(Iso8583Exception.class).hasMessageContaining("exceeds");
        // the limit is inclusive and configurable
        byte[] exactly = new byte[100];
        java.util.Arrays.fill(exactly, (byte) 'x');
        assertThat(readWire(framed(exactly), 100)).hasSize(100);
        byte[] wire = framed(exactly);
        assertThatThrownBy(() -> readWire(wire, 99)).isInstanceOf(Iso8583Exception.class);
    }

    @Test
    void emptyAndTooLargeMessagesCannotBeWritten() throws IOException {
        assertThatThrownBy(() -> Frame.write(new ByteArrayOutputStream(), new byte[0])).isInstanceOf(Iso8583Exception.class);
        assertThatThrownBy(() -> Frame.write(new ByteArrayOutputStream(), new byte[65536])).isInstanceOf(Iso8583Exception.class);
        assertThat(framed(new byte[65535])).hasSize(65537);
    }
}
