package com.bilal.neobank.card;

import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** The connection cap of the listener. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0",
        "app.cards.listener.max-connections=2" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IssuerCapacityTest extends IssuerTestBase {

    @Test
    void aThirdConnectionIsRefusedWhileTwoAreOpenAndAllowedWhenOneCloses() throws Exception {
        Fixture f = fixture("cap@example.com", "100.00");
        try (Terminal first = new Terminal().signedOn(); Terminal second = new Terminal().signedOn()) {
            try (Terminal third = new Terminal()) {
                assertThat(third.closedByIssuer()).isTrue();
            }
            assertThat(server.refusedOverCapacity()).isGreaterThanOrEqualTo(1);
            assertThat(code(first.exchange(purchase(f, "1.00")))).as("the open ones are unaffected").isEqualTo("00");
            assertThat(code(second.exchange(purchase(f, "1.00")))).isEqualTo("00");
        }
        // both closed: a slot frees up (the server releases it after noticing the close)
        Terminal fresh = null;
        for (int attempt = 0; attempt < 50 && fresh == null; attempt++) {
            try {
                fresh = new Terminal().signedOn();
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        assertThat(fresh).as("a new connection is accepted again").isNotNull();
        fresh.close();
    }
}
