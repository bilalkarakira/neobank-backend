package com.bilal.neobank.card;

import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** The switch that turns authorizations off. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0",
        "app.cards.authorizations-enabled=false" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IssuerSwitchedOffTest extends IssuerTestBase {

    @Test
    void withAuthorizationsOffEveryPaymentGets91AndNothingIsRecorded() throws Exception {
        Fixture f = fixture("off@example.com", "100.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(purchase(f, "1.00")))).isEqualTo("91");
            assertThat(code(terminal.exchange(purchase(f, "1.00").set(14, "3012")))).isEqualTo("91");
        }
        assertThat(rows("true")).isZero();
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void anAdviceIsStillAcceptedBecauseItStatesWhatAlreadyHappened() throws Exception {
        Fixture f = fixture("off2@example.com", "100.00");
        var advice = purchase(f, "25.00").copyWithMti("0120").set(38, "SI4F2K").set(39, "00");
        try (Terminal terminal = new Terminal().signedOn()) {
            var response = terminal.exchange(advice);
            assertThat(response.mti()).isEqualTo("0130");
            assertThat(code(response)).isEqualTo("00");
        }
        assertThat(rows("status = 'AUTHORIZED' and via_stand_in")).isEqualTo(1);
        assertThat(available(f)).isEqualByComparingTo("75.00");
    }
}
