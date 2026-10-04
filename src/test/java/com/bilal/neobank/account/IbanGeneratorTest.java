package com.bilal.neobank.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IbanGeneratorTest {

    @Test
    void generatesExpectedShape() {
        String iban = IbanGenerator.generate(1000000001L);

        assertThat(iban).matches("XX\\d{2}NEOB\\d{10}");
        assertThat(iban).hasSize(18).endsWith("NEOB1000000001");
    }

    @Test
    void generatedIbansPassIsoMod97ValidationForManyAccountNumbers() {
        for (long n = 1000000001L; n < 1000000001L + 500; n++) {
            assertThat(IbanGenerator.isValid(IbanGenerator.generate(n))).as("account %d", n).isTrue();
        }
    }

    @Test
    void normalizeStripsSpacesAndUppercases() {
        assertThat(IbanGenerator.normalize(" xx65 neob 1000\t0000 01 ")).isEqualTo("XX65NEOB1000000001");
        assertThat(IbanGenerator.normalize(null)).isEmpty();
    }

    @Test
    void validatorAcceptsKnownRealIbansAndRejectsCorruptedOnes() {
        // published example IBANs
        assertThat(IbanGenerator.isValid("GB82WEST12345698765432")).isTrue();
        assertThat(IbanGenerator.isValid("DE89370400440532013000")).isTrue();

        assertThat(IbanGenerator.isValid("GB82WEST12345698765433")).isFalse();
        assertThat(IbanGenerator.isValid(IbanGenerator.generate(1000000001L).replace("NEOB1", "NEOB2"))).isFalse();
        assertThat(IbanGenerator.isValid(null)).isFalse();
        assertThat(IbanGenerator.isValid("gb82west12345698765432")).isFalse();
    }
}
