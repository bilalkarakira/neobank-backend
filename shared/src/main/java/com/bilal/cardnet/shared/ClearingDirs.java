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

    /** "B0001.clr" -> "B0001.ack": the acknowledgement of a file carries its name. */
    public static String ackName(String fileName) {
        return (fileName.endsWith(FILE_SUFFIX) ? fileName.substring(0, fileName.length() - FILE_SUFFIX.length()) : fileName) + ACK_SUFFIX;
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
