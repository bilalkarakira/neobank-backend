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
}
