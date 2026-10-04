package com.bilal.neobank.demo;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo generator must never exist in a normal (non-"demo") run. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DemoSeederInactiveTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theSeederBeanDoesNotExistWithoutTheDemoProfile() {
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
    }
}
