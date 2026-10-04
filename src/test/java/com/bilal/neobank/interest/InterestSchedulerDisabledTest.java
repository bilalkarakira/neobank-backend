package com.bilal.neobank.interest;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** With the default test configuration the scheduler is off, so tests decide when interest runs. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InterestSchedulerDisabledTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    InterestProperties properties;

    @Test
    void noSchedulerBeanExistsWhenItIsSwitchedOff() {
        assertThat(context.getBeansOfType(InterestScheduler.class)).isEmpty();
    }

    @Test
    void theRateAndCronComeFromConfiguration() {
        assertThat(properties.apy()).isEqualByComparingTo("0.035");
        assertThat(properties.apyPercent()).isEqualTo("3.50");
        assertThat(properties.cron()).isEqualTo("0 30 0 * * *");
    }
}
