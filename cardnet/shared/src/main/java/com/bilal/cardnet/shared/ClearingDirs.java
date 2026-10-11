package com.bilal.cardnet.shared;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared folder through which the acquirer, the network and the issuer pass clearing files (a stand-in for the SFTP
 * servers of real schemes). Each folder has one writer and one reader:
 * <pre>
 *   acquirer-out/  batch files           acquirer -> network
 *   acquirer-in/   acknowledgements      network  -> acquirer
 *   issuer-in/     per-issuer files      network  -> issuer
 *   issuer-out/    acknowledgements      issuer   -> network
 * </pre>
 * Files are written under a temporary name and renamed, so a reader only ever lists finished files.
 */
public final class ClearingDirs {

    public static final String FILE_SUFFIX = ".clr";
    public static final String DISPUTE_SUFFIX = ".dsp";
    public static final String ACK_SUFFIX = ".ack";

    private final Path root;

    public ClearingDirs(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path acquirerOut() {
        return dir("acquirer-out");
    }

    public Path acquirerIn() {
        return dir("acquirer-in");
    }

    public Path issuerIn() {
        return dir("issuer-in");
    }

    public Path issuerOut() {
        return dir("issuer-out");
    }

    /** Settlement statements (CSV and XML, totals only) written by the network for every participant. */
    public Path settlementOut() {
        return dir("settlement-out");
    }

    /** Writes through a temporary name and renames, so a reader only ever lists finished files. */
    public static Path writeAtomically(Path directory, String fileName, byte[] bytes) {
        Path temp = directory.resolve(fileName + ".tmp");
        Path target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "B0001.clr" or "D0001.dsp" -> "B0001.ack" / "D0001.ack": the acknowledgement of a file carries its name. */
    public static String ackName(String fileName) {
        for (String suffix : new String[] {FILE_SUFFIX, DISPUTE_SUFFIX}) {
            if (fileName.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length()) + ACK_SUFFIX;
            }
        }
        return fileName + ACK_SUFFIX;
    }

    private Path dir(String name) {
        Path path = root.resolve(name);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }
}
