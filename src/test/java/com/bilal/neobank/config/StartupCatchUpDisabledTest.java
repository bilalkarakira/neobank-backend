package com.bilal.neobank.config;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** With the test configuration (app.catch-up.enabled=false) no background work starts at all. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StartupCatchUpDisabledTest {

    @Autowired
    ObjectProvider<StartupCatchUp> catchUp;

    @Test
    void theMasterSwitchKeepsTheCatchUpOutOfTheApplication() {
        assertThat(catchUp.getIfAvailable()).isNull();
    }
}
