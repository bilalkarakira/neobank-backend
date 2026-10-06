package com.bilal.cardnet.shared.testing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException;
import com.bilal.cardnet.shared.ClearingDirs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * For tests only: an issuer that reads the files in {@code issuer-in/} and answers each in {@code issuer-out/}, deciding
 * every item with {@link #decision} (default: purchases POSTED, refunds REFUND_POSTED). Call {@link #tick} to let it work.
 */
public final class FakeIssuerClearing {

    /** What the fake says about one item. */
    public record Verdict(String result, String reason) {
    }

    public volatile Function<Message, Verdict> decision = m ->
            new Verdict(ClearingFile.REFUND.equals(m.get(3)) ? "REFUND_POSTED" : "POSTED", "");
    /** When set, every file is refused whole with this reason. */
    public volatile String rejectFiles;
    /** When true the fake does not answer at all (the issuer is down). */
    public volatile boolean silent;
    /** When true it writes an acknowledgement for the wrong file id. */
    public volatile boolean answerWithWrongFileId;

    public final List<ClearingFile> received = Collections.synchronizedList(new ArrayList<>());

    private final ClearingDirs dirs;
    private final ClearingFileCodec codec;
    private final Set<String> done = new HashSet<>();

    public FakeIssuerClearing(Path root, String secret) {
        this.dirs = new ClearingDirs(root);
        this.codec = ClearingFileCodec.withSecret(secret);
    }

    /** Answers every file that has no answer yet. Returns how many it answered. */
    public synchronized int tick() {
        if (silent) {
            return 0;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dirs.issuerIn())) {
            list.filter(p -> p.getFileName().toString().endsWith(ClearingDirs.FILE_SUFFIX)).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int answered = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            if (!done.add(name)) {
                continue;
            }
            String base = name.substring(0, name.length() - ClearingDirs.FILE_SUFFIX.length());
            ClearingAck ack;
            try {
                ClearingFile file = codec.read(path);
                received.add(file);
                if (rejectFiles != null) {
                    ack = ClearingAck.rejected(base, rejectFiles);
                } else {
                    List<ClearingAck.Item> items = new ArrayList<>();
                    int number = 1;
                    for (Message m : file.presentments()) {
                        Verdict v = decision.apply(m);
                        items.add(new ClearingAck.Item(number++, v.result(), v.reason()));
                    }
                    ack = ClearingAck.accepted(base, items);
                }
            } catch (ClearingFileException e) {
                ack = ClearingAck.rejected(base, e.reason() + ": " + e.getMessage());
            }
            if (answerWithWrongFileId) {
                ack = ack.status() == ClearingAck.Status.REJECTED ? ClearingAck.rejected("WRONG", ack.reason())
                        : ClearingAck.accepted("WRONG", ack.items());
            }
            codec.writeAck(dirs.issuerOut(), ClearingDirs.ackName(name), ack);
            answered++;
        }
        return answered;
    }
}
