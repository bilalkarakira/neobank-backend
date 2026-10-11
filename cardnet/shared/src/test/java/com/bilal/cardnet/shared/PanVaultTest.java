package com.bilal.cardnet.shared;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PanVaultTest {

    private static final String KEY = "k".repeat(40);
    private static final byte[] PLAIN = "0100 with the number 9999000000000012".getBytes(StandardCharsets.US_ASCII);

    private final PanVault vault = new PanVault(KEY);

    @Test
    void roundTripsAndNeverShowsThePlaintext() {
        byte[] stored = vault.encrypt(PLAIN, "row-1");

        assertThat(vault.decrypt(stored, "row-1")).isEqualTo(PLAIN);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("9999000000000012").doesNotContain("0100");
        assertThat(stored.length).isEqualTo(12 + PLAIN.length + 16); // nonce + data + GCM tag
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(java.util.HexFormat.of().formatHex(vault.encrypt(PLAIN, "row-1")));
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void anyAlteredByteIsRefused() {
        byte[] stored = vault.encrypt(PLAIN, "row-1");
        for (int i = 0; i < stored.length; i++) {
            byte[] altered = stored.clone();
            altered[i] ^= 0x01;
            int position = i;
            assertThatThrownBy(() -> vault.decrypt(altered, "row-1")).as("byte " + position).isInstanceOf(PanVault.VaultException.class);
        }
    }

    @Test
    void aValueMovedToAnotherRowIsRefused() {
        byte[] stored = vault.encrypt(PLAIN, "row-1");
        assertThatThrownBy(() -> vault.decrypt(stored, "row-2")).isInstanceOf(PanVault.VaultException.class);
    }

    @Test
    void anotherKeyCannotReadIt() {
        byte[] stored = vault.encrypt(PLAIN, "row-1");
        assertThatThrownBy(() -> new PanVault("z".repeat(40)).decrypt(stored, "row-1")).isInstanceOf(PanVault.VaultException.class);
    }

    @Test
    void truncatedOrEmptyValuesAreRefusedNotCrashing() {
        byte[] stored = vault.encrypt(PLAIN, "row-1");
        assertThatThrownBy(() -> vault.decrypt(Arrays.copyOf(stored, 10), "row-1")).isInstanceOf(PanVault.VaultException.class);
        assertThatThrownBy(() -> vault.decrypt(Arrays.copyOf(stored, stored.length - 1), "row-1")).isInstanceOf(PanVault.VaultException.class);
        assertThatThrownBy(() -> vault.decrypt(new byte[0], "row-1")).isInstanceOf(PanVault.VaultException.class);
        assertThatThrownBy(() -> vault.decrypt(null, "row-1")).isInstanceOf(PanVault.VaultException.class);
    }

    @Test
    void aShortKeyIsRefused() {
        assertThatThrownBy(() -> new PanVault("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanVault(null)).isInstanceOf(IllegalArgumentException.class);
        new PanVault("x".repeat(32)); // exactly 32 is fine
    }

    @Test
    void theFingerprintGroupsOneCardAndHidesTheNumber() {
        String a = vault.fingerprint("9999000000000012");

        assertThat(a).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("9999000000000012");
        assertThat(vault.fingerprint("9999000000000012")).isEqualTo(a);
        assertThat(vault.fingerprint("9999000000000046")).isNotEqualTo(a);
        assertThat(new PanVault("z".repeat(40)).fingerprint("9999000000000012")).as("keyed").isNotEqualTo(a);
    }
}
