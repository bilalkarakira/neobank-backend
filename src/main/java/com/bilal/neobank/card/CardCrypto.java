package com.bilal.neobank.card;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Keyed hashes of PANs, PINs and security codes (HMAC-SHA256). A plain hash of a PAN would be brute-forceable (one BIN has only 10^9
 * numbers); with the key it is not. The PIN has only 10^4 values (the security code only 10^3), so with the key it is still guessable: a known limit
 * of this simulation (real issuers derive PINs in an HSM and never store them).
 */
@Component
public class CardCrypto {

    private final byte[] key;

    public CardCrypto(CardProperties properties) {
        String configured = properties.panHmacKey();
        if (configured == null || configured.length() < 32) {
            throw new IllegalStateException("app.cards.pan-hmac-key must be at least 32 characters");
        }
        this.key = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String hashPan(String pan) {
        return hmac("pan:" + pan);
    }

    public String hashPin(UUID cardId, String pin) {
        return hmac("pin:" + cardId + ":" + pin);
    }

    public String hashCvv(UUID cardId, String cvv) {
        return hmac("cvv:" + cardId + ":" + cvv);
    }

    public boolean cvvMatches(UUID cardId, String cvv, String storedHash) {
        return storedHash != null && cvv != null && MessageDigest.isEqual(hashCvv(cardId, cvv).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public boolean pinMatches(UUID cardId, String pin, String storedHash) {
        return MessageDigest.isEqual(hashPin(cardId, pin).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private String hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
