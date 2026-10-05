package com.bilal.cardnet.shared;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Where a message that contains a card number is kept when it has to be rebuilt later (a reversal that is retried, a
 * stand-in advice). AES-256-GCM with a fresh random nonce per value. The row id is bound in as associated data, so a
 * ciphertext copied onto another row refuses to decrypt. Also gives a keyed fingerprint of a PAN, to group one card's
 * stand-in total without storing the number.
 *
 * <p>Honest limits: the key is a configured string (no HSM, no rotation, not PCI), and anyone with the key and the table
 * can read the numbers.
 */
public final class PanVault {

    /** Thrown when a value was altered, moved to another row, or encrypted under another key. */
    public static final class VaultException extends RuntimeException {
        VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec macKey;

    public PanVault(String key) {
        if (key == null || key.length() < 32) {
            throw new IllegalArgumentException("the vault key must be at least 32 characters");
        }
        byte[] material = key.getBytes(StandardCharsets.UTF_8);
        this.aesKey = new SecretKeySpec(sha256("aes:".getBytes(StandardCharsets.UTF_8), material), "AES");
        this.macKey = new SecretKeySpec(sha256("mac:".getBytes(StandardCharsets.UTF_8), material), "HmacSHA256");
    }

    /** nonce (12 bytes) followed by the ciphertext and its tag. */
    public byte[] encrypt(byte[] plain, String rowId) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(rowId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] out = new byte[NONCE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(encrypted, 0, out, NONCE_BYTES, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] decrypt(byte[] stored, String rowId) {
        if (stored == null || stored.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new VaultException("the stored value is too short to be one of ours", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(stored, 0, NONCE_BYTES)));
            cipher.updateAAD(rowId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new VaultException("the stored value was altered, belongs to another row, or uses another key", e);
        }
    }

    /** HMAC-SHA256 of the PAN, hex. Not reversible without brute force, and useless without the key. */
    public String fingerprint(String pan) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            return HexFormat.of().formatHex(mac.doFinal(pan.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] prefix, byte[] material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix);
            return digest.digest(material);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
