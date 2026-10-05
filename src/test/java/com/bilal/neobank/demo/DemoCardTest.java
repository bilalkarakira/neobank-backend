package com.bilal.neobank.demo;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.Luhn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.bilal.neobank.card.CardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo customer gets one card with a known number, also in a demo database that was seeded before cards existed. */
@SpringBootTest(properties = "app.demo.seed-on-startup=false")
@ActiveProfiles("demo")
@Import(TestcontainersConfiguration.class)
class DemoCardTest {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    CardService cardService;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    @Test
    void theDemoNumberIsAValidTestCardNumber() {
        assertThat(DemoDataSeeder.DEMO_CARD_PAN).hasSize(16).startsWith("999900").isEqualTo("9999000000000012");
        assertThat(Luhn.isValid(DemoDataSeeder.DEMO_CARD_PAN)).isTrue();
    }

    @Test
    void theCardIsCreatedOnceOnTheDemoCheckingAccountAndNotStoredInTheClear() {
        seeder.seed();

        assertThat(seeder.seedDemoCard()).isTrue();
        assertThat(seeder.seedDemoCard()).as("idempotent").isFalse();

        assertThat(jdbc.queryForObject("select count(*) from cards", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select a.type || '/' || c.status || '/' || c.last4 from cards c join accounts a on a.id = c.account_id
                """, String.class)).isEqualTo("CHECKING/ACTIVE/0012");
        assertThat(jdbc.queryForObject("select row_to_json(c)::text from cards c", String.class))
                .doesNotContain(DemoDataSeeder.DEMO_CARD_PAN);
    }

    @Test
    void aDemoDatabaseSeededBeforeCardsExistedStillGetsItsCard() {
        seeder.seed();
        jdbc.update("delete from cards");

        assertThat(seeder.seedDemoCard()).isTrue();
    }

    @Test
    void aDemoCardNeedsATestBinLuhnNumberAndAFourDigitPin() {
        seeder.seed();
        java.util.UUID user = jdbc.queryForObject("select id from users where email = ?", java.util.UUID.class, DemoDataSeeder.DEMO_EMAIL);
        java.util.UUID account = jdbc.queryForObject("select id from accounts where user_id = ? and type = 'CHECKING'", java.util.UUID.class, user);
        String goodPan = "4111111111111111"; // valid Luhn, but not our BIN
        String wrongLuhn = "9999000000000013"; // our BIN, one digit off
        for (String[] bad : new String[][] { { goodPan, "1234" }, { wrongLuhn, "1234" }, { "999900000000011", "1234" },
                { "9999000000000012", "123" }, { "9999000000000012", "12a4" } }) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> cardService.issueDemoCard(user, account, null, bad[0], bad[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from cards", Integer.class)).isZero();
    }

    @Test
    void aDemoCardCannotBeLinkedToASavingsAccount() {
        seeder.seed();
        java.util.UUID user = jdbc.queryForObject("select id from users where email = ?", java.util.UUID.class, DemoDataSeeder.DEMO_EMAIL);
        java.util.UUID savings = jdbc.queryForObject("select id from accounts where user_id = ? and type = 'SAVINGS'", java.util.UUID.class, user);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cardService.issueDemoCard(user, savings, null, DemoDataSeeder.DEMO_CARD_PAN, "1234"))
                .isInstanceOf(com.bilal.neobank.card.CardExceptions.AccountNotEligibleException.class);
        assertThat(jdbc.queryForObject("select count(*) from cards", Integer.class)).isZero();
    }

    @Test
    void withoutADemoCustomerNothingHappens() {
        assertThat(seeder.seedDemoCard()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from cards", Integer.class)).isZero();
    }
}
