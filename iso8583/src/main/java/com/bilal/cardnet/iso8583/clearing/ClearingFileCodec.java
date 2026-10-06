package com.bilal.cardnet.iso8583.clearing;

import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.clearing.ClearingFileException.Reason;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads and writes clearing files.
 *
 * <p>Plain text layout (one ISO 8583 message per line, each line ended by LF):
 * <pre>
 *   1644  DE48 = FILEHDR|fileId|yyyyMMdd|sender|receiver|CUR
 *   1240  ... one first presentment per line ...
 *   1644  DE48 = FILETRL|count|controlTotal|sha256 (of the presentment lines, LF included)
 * </pre>
 * On disk the whole text is AES-256-GCM encrypted: {@code "NCLR1" + 12-byte nonce + ciphertext+tag}, with the file NAME as
 * associated data, so a file renamed or replaced by another one is refused. Nothing here ever keeps a card number in the
 * clear on disk. Honest limit: one symmetric key shared by every party (real schemes use a key pair per party).
 */
public final class ClearingFileCodec {

    public static final String DEFAULT_SECRET = "neobank-dev-clearing-key-change-me";

    private static final byte[] MAGIC = "NCLR1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ACK_MAGIC = "NCLA1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE = 12;
    private static final int TAG_BYTES = 16;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final Packager packager = new Packager();

    /** @param key exactly 32 bytes */
    public ClearingFileCodec(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("the clearing file key must be 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** A codec whose key is the SHA-256 of a secret text. */
    public static ClearingFileCodec withSecret(String secret) {
        return new ClearingFileCodec(sha256(secret.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- write ----------------------------------------------------------------------------------------------

    /** The bytes to store under {@code fileName}. */
    public byte[] encode(ClearingFile file, String fileName) {
        return seal(plainText(file), fileName);
    }

    /** Writes through a temporary name and renames, so a reader never sees half a file. Returns the final path. */
    public Path write(Path directory, String fileName, ClearingFile file) {
        return writeBytes(directory, fileName, encode(file, fileName));
    }

    private static Path writeBytes(Path directory, String fileName, byte[] bytes) {
        Path temp = directory.resolve(fileName + ".tmp");
        Path target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    byte[] plainText(ClearingFile file) {
        List<byte[]> lines = new ArrayList<>();
        for (Message m : file.presentments()) {
            lines.add(packager.pack(m)); // packing checks the values before they are looked at
        }
        ClearingFile.validateAll(file.presentments());
        MessageDigest digest = digest();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        for (byte[] line : lines) {
            digest.update(line);
            digest.update((byte) '\n');
            body.write(line, 0, line.length);
            body.write('\n');
        }
        String header = "FILEHDR|" + file.fileId() + "|" + DATE.format(file.businessDate()) + "|" + file.sender() + "|"
                + file.receiver() + "|" + file.currency();
        String trailer = "FILETRL|" + lines.size() + "|" + file.controlTotal() + "|" + HexFormat.of().formatHex(digest.digest());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeLine(out, packager.pack(Message.of("1644").set(48, header)));
        out.writeBytes(body.toByteArray());
        writeLine(out, packager.pack(Message.of("1644").set(48, trailer)));
        return out.toByteArray();
    }

    private static void writeLine(java.io.ByteArrayOutputStream out, byte[] line) {
        out.write(line, 0, line.length);
        out.write('\n');
    }

    // ---- read -----------------------------------------------------------------------------------------------

    /** Reads the file at {@code path}; its NAME is part of the check. */
    public ClearingFile read(Path path) {
        try {
            return decode(Files.readAllBytes(path), path.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @throws ClearingFileException the first thing wrong with the file, with a stable reason */
    public ClearingFile decode(byte[] bytes, String fileName) {
        return parse(open(bytes, fileName));
    }

    ClearingFile parse(byte[] plain) {
        for (byte b : plain) {
            if (b != '\n' && (b < 0x20 || b > 0x7E)) {
                throw new ClearingFileException(Reason.BAD_RECORD, "the file contains a byte that is not printable ASCII");
            }
        }
        if (plain.length == 0 || plain[plain.length - 1] != '\n') {
            throw new ClearingFileException(Reason.BAD_TRAILER, "the file does not end with a complete record");
        }
        List<byte[]> lines = split(plain);
        if (lines.size() < 2) {
            throw new ClearingFileException(lines.isEmpty() ? Reason.BAD_HEADER : Reason.BAD_TRAILER,
                    "the file needs a header and a trailer");
        }
        String[] header = fixed(lines.get(0), "FILEHDR", 6, Reason.BAD_HEADER);
        String[] trailer = fixed(lines.get(lines.size() - 1), "FILETRL", 4, Reason.BAD_TRAILER);

        int count = lines.size() - 2;
        List<byte[]> items = lines.subList(1, lines.size() - 1);
        if (!Long.toString(count).equals(trailer[1])) {
            throw new ClearingFileException(Reason.COUNT_MISMATCH, "the trailer says " + trailer[1] + " presentments, the file has " + count);
        }
        MessageDigest digest = digest();
        for (byte[] line : items) {
            digest.update(line);
            digest.update((byte) '\n');
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(trailer[3])) {
            throw new ClearingFileException(Reason.HASH_MISMATCH, "the presentments do not match the trailer hash");
        }
        List<Message> presentments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                presentments.add(packager.unpack(items.get(i)));
            } catch (Iso8583Exception e) {
                throw new ClearingFileException(Reason.BAD_RECORD, "record " + (i + 1) + ": " + e.getMessage());
            }
        }
        ClearingFile.validateAll(presentments);

        LocalDate date;
        try {
            date = LocalDate.parse(header[2], DATE);
        } catch (DateTimeParseException e) {
            throw new ClearingFileException(Reason.BAD_HEADER, "the business date is not yyyyMMdd");
        }
        ClearingFile file = new ClearingFile(header[1], date, header[3], header[4], header[5], presentments);
        if (!Long.toString(file.controlTotal()).equals(trailer[2])) {
            throw new ClearingFileException(Reason.TOTAL_MISMATCH,
                    "the trailer says total " + trailer[2] + ", the presentments add up to " + file.controlTotal());
        }
        return file;
    }

    private String[] fixed(byte[] line, String tag, int parts, Reason reason) {
        Message m;
        try {
            m = packager.unpack(line);
        } catch (Iso8583Exception e) {
            throw new ClearingFileException(reason, "not a readable " + tag + " record: " + e.getMessage());
        }
        String text = m.get(48);
        if (!"1644".equals(m.mti()) || m.fields().size() != 1 || text == null || !text.startsWith(tag + "|")) {
            throw new ClearingFileException(reason, "expected a " + tag + " record");
        }
        String[] split = text.split("\\|", -1);
        if (split.length != parts) {
            throw new ClearingFileException(reason, tag + " must have " + parts + " parts");
        }
        return split;
    }

    private static List<byte[]> split(byte[] plain) {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < plain.length; i++) {
            if (plain[i] == '\n') {
                lines.add(Arrays.copyOfRange(plain, start, i));
                start = i + 1;
            }
        }
        return lines;
    }

    // ---- encryption -----------------------------------------------------------------------------------------

    byte[] seal(byte[] plain, String fileName) {
        return seal(MAGIC, plain, fileName);
    }

    byte[] open(byte[] bytes, String fileName) {
        return open(MAGIC, bytes, fileName);
    }

    // ---- acknowledgements -----------------------------------------------------------------------------------

    /** The bytes of an acknowledgement to store under {@code fileName} (encrypted like a clearing file, other magic). */
    public byte[] encodeAck(ClearingAck ack, String fileName) {
        return seal(ACK_MAGIC, ack.plainText(), fileName);
    }

    public Path writeAck(Path directory, String fileName, ClearingAck ack) {
        return writeBytes(directory, fileName, encodeAck(ack, fileName));
    }

    public ClearingAck readAck(Path path) {
        try {
            return decodeAck(Files.readAllBytes(path), path.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ClearingAck decodeAck(byte[] bytes, String fileName) {
        return ClearingAck.parse(open(ACK_MAGIC, bytes, fileName));
    }

    private byte[] seal(byte[] magic, byte[] plain, String fileName) {
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(fileName.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] out = new byte[magic.length + NONCE + encrypted.length];
            System.arraycopy(magic, 0, out, 0, magic.length);
            System.arraycopy(nonce, 0, out, magic.length, NONCE);
            System.arraycopy(encrypted, 0, out, magic.length + NONCE, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] open(byte[] magic, byte[] bytes, String fileName) {
        if (bytes == null || bytes.length < magic.length + NONCE + TAG_BYTES
                || !Arrays.equals(Arrays.copyOf(bytes, magic.length), magic)) {
            throw new ClearingFileException(Reason.NOT_A_CLEARING_FILE, "not a clearing file");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BYTES * 8, bytes, magic.length, NONCE));
            cipher.updateAAD(fileName.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(bytes, magic.length + NONCE, bytes.length - magic.length - NONCE);
        } catch (AEADBadTagException e) {
            throw new ClearingFileException(Reason.DECRYPT_FAILED,
                    "the file cannot be decrypted: wrong key, wrong file name, or its bytes were changed");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }
}
