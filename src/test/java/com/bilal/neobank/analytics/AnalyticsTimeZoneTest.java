package com.bilal.neobank.analytics;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The balance series is built from calendar DAYS in UTC. It must not depend on the database session's time zone:
 * a zone with daylight saving makes a "+1 day" step 23 or 25 hours long, which once dropped a day from long windows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnalyticsTimeZoneTest extends AnalyticsTestBase {

    @Autowired
    AnalyticsRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private List<AnalyticsRepository.DailyBalance> seriesIn(String zone, UUID account, LocalDate start, LocalDate end) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local time zone '" + zone + "'");
            return repository.dailyBalances(List.of(account), start, end);
        });
    }

    @Test
    void aFullYearHasExactlyOnePointPerDayInEveryTimeZone() throws Exception {
        String alice = tokenFor("alice@example.com");
        UUID account = UUID.fromString(open(alice, "CHECKING").id());
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);

        for (String zone : List.of("UTC", "America/New_York", "Europe/London", "Australia/Sydney", "Asia/Kolkata")) {
            List<AnalyticsRepository.DailyBalance> points = seriesIn(zone, account, start, end);

            assertThat(points).as("points in %s", zone).hasSize(365);
            assertThat(points.get(0).date()).isEqualTo(start);
            assertThat(points.get(364).date()).isEqualTo(end);
            for (int i = 1; i < points.size(); i++) {
                assertThat(points.get(i).date()).as("%s, day %d", zone, i).isEqualTo(points.get(i - 1).date().plusDays(1));
            }
        }
    }

    @Test
    void balancesStayOnTheirUtcDayAroundADaylightSavingChange() throws Exception {
        String alice = tokenFor("alice@example.com");
        var acc = open(alice, "CHECKING");
        UUID account = UUID.fromString(acc.id());
        // 2026-03-08 is the day New York springs forward; deposits just before and after midnight UTC
        depositOn(acc, "10.00", LocalDate.of(2026, 3, 8).atTime(23, 30).toInstant(java.time.ZoneOffset.UTC));
        depositOn(acc, "5.00", LocalDate.of(2026, 3, 9).atTime(0, 30).toInstant(java.time.ZoneOffset.UTC));

        List<AnalyticsRepository.DailyBalance> points = seriesIn("America/New_York", account,
                LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 10));

        assertThat(points.stream().map(p -> p.date() + "=" + p.balance().setScale(2).toPlainString()).toList())
                .containsExactly("2026-03-07=0.00", "2026-03-08=10.00", "2026-03-09=15.00", "2026-03-10=15.00");
    }
}
