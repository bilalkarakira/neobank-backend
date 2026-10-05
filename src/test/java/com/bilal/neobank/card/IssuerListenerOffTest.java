package com.bilal.neobank.card;

import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** With the default (test) configuration no socket is opened. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IssuerListenerOffTest extends IssuerTestBase {

    @Test
    void theListenerIsOffUnlessConfigured() {
        assertThat(server.isRunning()).isFalse();
        assertThat(server.port()).isEqualTo(-1);
    }
}
