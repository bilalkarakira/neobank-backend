package com.bilal.neobank.interest;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** The scheduler, switched on: it must call the service, and be wired to the configured cron in UTC. */
@SpringBootTest(properties = "app.interest.scheduler-enabled=true")
@Import(TestcontainersConfiguration.class)
class InterestSchedulerTest {

    @Autowired
    InterestScheduler scheduler;

    @MockitoBean
    InterestService service;

    @Test
    void theJobCallsTheServiceOnceEachTime() {
        scheduler.run();

        verify(service).accrueAllDue();
    }

    @Test
    void theJobIsScheduledDailyAtHalfPastMidnightUtcFromConfiguration() throws Exception {
        Scheduled schedule = InterestScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(schedule.cron()).isEqualTo("${app.interest.cron}");
        assertThat(schedule.zone()).isEqualTo("UTC");
    }
}
