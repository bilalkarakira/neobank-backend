package com.bilal.cardnet.network;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The network listens on loopback only unless it is told otherwise (a container needs the wildcard address). */
class BindAddressTest {

    @Test
    void byDefaultTheAcquirerPortAndTheControlApiAreLoopbackOnly() throws Exception {
        try (NetHarness h = new NetHarness()) {
            assertThat(h.config.bindAddress).isEqualTo("127.0.0.1");
            assertThat(h.network.server().boundAddress().isLoopbackAddress()).isTrue();
            assertThat(h.network.control().boundAddress().isLoopbackAddress()).isTrue();
        }
    }

    @Test
    void theBindAddressCanBeChangedForContainers() throws Exception {
        try (NetHarness h = new NetHarness(c -> c.bindAddress = "0.0.0.0")) {
            assertThat(h.network.server().boundAddress().isAnyLocalAddress()).isTrue();
            assertThat(h.network.control().boundAddress().isAnyLocalAddress()).isTrue();
            // and it still answers on the loopback address
            assertThat(h.network.status()).containsKey("issuerLink");
        }
    }
}
