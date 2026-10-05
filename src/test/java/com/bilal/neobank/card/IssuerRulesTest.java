package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every authorization rule through a real socket: decoded response code and database state. The ORDER of the rules is
 * tested as much as the rules (a wrong PIN on a frozen card is 57, a wrong PIN on an empty account is 55, and so on).
 */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=5" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class IssuerRulesTest extends IssuerTestBase {

    private String answer(Message request) throws Exception {
        try (Terminal terminal = new Terminal().signedOn()) {
            return code(terminal.exchange(request));
        }
    }

    // ---- the happy path ----

    @Test
    void anApprovedPurchaseAnswersWithAnAuthCodeEchoesTheRequestAndHoldsTheMoney() throws Exception {
        Fixture f = fixture("rule1@example.com", "100.00");
        Message request = purchase(f, "25.50");

        String authCode;
        try (Terminal terminal = new Terminal().signedOn()) {
            Message response = terminal.exchange(request);
            authCode = response.get(38);

            assertThat(response.mti()).isEqualTo("0110");
            assertThat(code(response)).isEqualTo("00");
            assertThat(response.get(38)).matches("[A-Z0-9]{6}");
            for (int echoed : new int[] { 2, 3, 4, 7, 11, 12, 13, 37, 41, 42, 49 }) {
                assertThat(response.get(echoed)).as("DE " + echoed).isEqualTo(request.get(echoed));
            }
            assertThat(response.has(52)).isFalse();
            assertThat(response.has(14)).isFalse();
        }

        assertThat(available(f)).isEqualByComparingTo("74.50");
        assertThat(jdbc.queryForObject("select status || '/' || response_code || '/' || amount || '/' || last4 from authorizations",
                String.class)).isEqualTo("AUTHORIZED/00/25.50/" + f.pan().substring(12));
        assertThat(jdbc.queryForObject("select auth_code from authorizations", String.class)).isEqualTo(authCode);
        assertThat(jdbc.queryForObject("select (expires_at - created_at) = interval '7 days' from authorizations", Boolean.class)).isTrue();
        assertBooksBalance(); // a hold moves no money
    }

    @Test
    void aFinancialMessageIsHandledLikeAnAuthorizationInThisPhase() throws Exception {
        Fixture f = fixture("rule2@example.com", "100.00");
        Message request = Message.of("0200");
        purchase(f, "10.00").fields().forEach(request::set);

        try (Terminal terminal = new Terminal().signedOn()) {
            Message response = terminal.exchange(request);
            assertThat(response.mti()).isEqualTo("0210");
            assertThat(code(response)).isEqualTo("00");
        }
        assertThat(available(f)).isEqualByComparingTo("90.00");
    }

    // ---- structure ----

    @Test
    void aRequestMissingARequiredElementIsAFormatErrorAndLeavesNoTrace() throws Exception {
        Fixture f = fixture("rule3@example.com", "100.00");
        for (int missing : new int[] { 2, 3, 4, 7, 11, 13, 14, 18, 32, 37, 41, 42, 49 }) {
            assertThat(answer(purchase(f, "5.00").remove(missing))).as("without DE " + missing).isEqualTo("30");
        }
        assertThat(rows("true")).isZero();
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void aZeroAmountIsInvalidAndRecorded() throws Exception {
        Fixture f = fixture("rule4@example.com", "100.00");

        assertThat(answer(purchase(f, "0.00"))).isEqualTo("13");

        assertThat(rows("response_code = '13' and status = 'DECLINED'")).isEqualTo(1);
    }

    // ---- the card ----

    @Test
    void anUnknownCardOrANumberThatFailsLuhnIsInvalid() throws Exception {
        Fixture f = fixture("rule5@example.com", "100.00");
        String unknown = "999900123456789" + Luhn.checkDigit("999900123456789");
        String badLuhn = f.pan().substring(0, 15) + (Character.digit(f.pan().charAt(15), 10) + 1) % 10;

        assertThat(answer(purchaseWith(unknown, f.expiry(), "5.00", nextStan(), "000000000001"))).isEqualTo("14");
        assertThat(answer(purchaseWith(badLuhn, f.expiry(), "5.00", nextStan(), "000000000002"))).isEqualTo("14");

        assertThat(rows("card_id is null and response_code = '14'")).isEqualTo(2);
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void aWrongExpiryDateIsInvalidAndAPastOneIsExpired() throws Exception {
        Fixture f = fixture("rule6@example.com", "100.00");
        String wrong = f.expiry().equals("3012") ? "3011" : "3012";

        assertThat(answer(purchaseWith(f.pan(), wrong, "5.00", nextStan(), "000000000001"))).isEqualTo("14");

        jdbc.update("update cards set expiry = '2001'");
        assertThat(answer(purchaseWith(f.pan(), "2001", "5.00", nextStan(), "000000000002"))).isEqualTo("54");
    }

    @Test
    void theLastMonthOfExpiryIsStillValidAndTheNextOneIsNot() throws Exception {
        Fixture f = fixture("rule6b@example.com", "100.00");
        java.time.YearMonth now = java.time.YearMonth.now(mutableClock.withZone(java.time.ZoneOffset.UTC));
        String thisMonth = "%02d%02d".formatted(now.getYear() % 100, now.getMonthValue());
        String lastMonth = "%02d%02d".formatted(now.minusMonths(1).getYear() % 100, now.minusMonths(1).getMonthValue());

        jdbc.update("update cards set expiry = ?", thisMonth);
        assertThat(answer(purchaseWith(f.pan(), thisMonth, "5.00", nextStan(), "000000000001"))).isEqualTo("00");
        jdbc.update("update cards set expiry = ?", lastMonth);
        assertThat(answer(purchaseWith(f.pan(), lastMonth, "5.00", nextStan(), "000000000002"))).isEqualTo("54");
    }

    @Test
    void aCancelledCardIsInvalidAndAFrozenOneIsNotPermitted() throws Exception {
        Fixture f = fixture("rule7@example.com", "100.00");

        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'CUSTOMER'");
        assertThat(answer(purchase(f, "5.00"))).isEqualTo("57");
        jdbc.update("update cards set status = 'CANCELLED'");
        assertThat(answer(purchase(f, "5.00"))).isEqualTo("14");
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void aFrozenOrClosedAccountMeansDoNotHonour() throws Exception {
        Fixture f = fixture("rule8@example.com", "100.00");

        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", f.account().id());
        assertThat(answer(purchase(f, "5.00"))).isEqualTo("05");
        jdbc.update("update accounts set status = 'CLOSED' where id = ?::uuid", f.account().id());
        assertThat(answer(purchase(f, "5.00"))).isEqualTo("05");
    }

    @Test
    void onlyPurchasesInDollarsAtAllowedMerchantsArePermitted() throws Exception {
        Fixture f = fixture("rule9@example.com", "100.00");

        assertThat(answer(purchase(f, "5.00").set(3, "010000"))).as("cash withdrawal").isEqualTo("57");
        assertThat(answer(purchase(f, "5.00").set(3, "200000"))).as("refund").isEqualTo("57");
        assertThat(answer(purchase(f, "5.00").set(49, "978"))).as("euro").isEqualTo("57");
        for (String mcc : new String[] { "7995", "4829", "6051" }) {
            assertThat(answer(purchase(f, "5.00").set(18, mcc))).as("mcc " + mcc).isEqualTo("57");
        }
        assertThat(answer(purchase(f, "5.00").set(3, "001000"))).as("purchase from a savings account type is still a purchase").isEqualTo("00");
    }

    // ---- PIN ----

    @Test
    void aWrongPinIsRefusedAndCountedACorrectOneClearsTheCount() throws Exception {
        Fixture f = fixture("pin1@example.com", "100.00");
        String wrong = f.pin().equals("0000") ? "1111" : "0000";

        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).isEqualTo("55");
        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).isEqualTo("55");
        assertThat(jdbc.queryForObject("select pin_failures from cards", Integer.class)).isEqualTo(2);

        assertThat(answer(withPin(purchase(f, "5.00"), f.pin()))).isEqualTo("00");
        assertThat(jdbc.queryForObject("select pin_failures from cards", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from cards", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void theThirdWrongPinFreezesTheCardAnswers75AndWritesANotice() throws Exception {
        Fixture f = fixture("pin2@example.com", "100.00");
        String wrong = f.pin().equals("0000") ? "1111" : "0000";

        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).isEqualTo("55");
        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).isEqualTo("55");
        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).isEqualTo("75");

        assertThat(jdbc.queryForObject("select status || '/' || freeze_reason from cards", String.class)).isEqualTo("FROZEN/PIN_TRIES");
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'CARD_FROZEN_PIN' and user_id = ?::uuid",
                Integer.class, f.account().userId())).isEqualTo(1);
        // even the right PIN now meets a frozen card
        assertThat(answer(withPin(purchase(f, "5.00"), f.pin()))).isEqualTo("57");
        // the customer can lift a PIN freeze, and the count starts again
        cardService.unfreeze(java.util.UUID.fromString(f.account().userId()), f.cardId());
        assertThat(answer(withPin(purchase(f, "5.00"), f.pin()))).isEqualTo("00");
        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).isEqualTo("55");
    }

    @Test
    void aMalformedPinBlockCountsAsAWrongPin() throws Exception {
        Fixture f = fixture("pin3@example.com", "100.00");

        assertThat(answer(purchase(f, "5.00").set(52, "FFFFFFFFFFFFFFFF"))).isEqualTo("55");
        assertThat(jdbc.queryForObject("select pin_failures from cards", Integer.class)).isEqualTo(1);
    }

    @Test
    void aPurchaseWithoutAPinIsAllowed() throws Exception {
        Fixture f = fixture("pin4@example.com", "100.00");
        assertThat(answer(purchase(f, "5.00"))).isEqualTo("00");
    }

    // ---- order of the rules ----

    @Test
    void theOrderOfTheRulesIsTheOrderOfTheSpec() throws Exception {
        Fixture f = fixture("order@example.com", "10.00");
        String wrong = f.pin().equals("0000") ? "1111" : "0000";

        // PIN comes before funds: a wrong PIN on an account that could not pay anyway is 55, not 51
        assertThat(answer(withPin(purchase(f, "500.00"), wrong))).as("pin before funds").isEqualTo("55");
        // limits come before funds
        assertThat(answer(purchase(f, "2500.01"))).as("cap before funds").isEqualTo("61");
        // permitted-ness comes before the PIN: a blocked merchant with a wrong PIN is 57 and does not count
        int failures = jdbc.queryForObject("select pin_failures from cards", Integer.class);
        assertThat(answer(withPin(purchase(f, "5.00").set(18, "7995"), wrong))).as("mcc before pin").isEqualTo("57");
        assertThat(jdbc.queryForObject("select pin_failures from cards", Integer.class)).isEqualTo(failures);
        // account status before PIN and funds
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", f.account().id());
        assertThat(answer(withPin(purchase(f, "500.00"), wrong))).as("account before pin").isEqualTo("05");
        jdbc.update("update accounts set status = 'ACTIVE' where id = ?::uuid", f.account().id());
        // card status before PIN
        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'CUSTOMER'");
        assertThat(answer(withPin(purchase(f, "5.00"), wrong))).as("frozen before pin").isEqualTo("57");
        // expiry before status
        jdbc.update("update cards set expiry = '2001'");
        assertThat(answer(purchaseWith(f.pan(), "2001", "5.00", nextStan(), "000000000009"))).as("expiry before status").isEqualTo("54");
        // a wrong expiry date before everything about the card's state
        assertThat(answer(purchaseWith(f.pan(), "3012", "5.00", nextStan(), "000000000010"))).as("expiry mismatch").isEqualTo("14");
        // zero amount before an unknown card
        assertThat(answer(purchaseWith("9999001234567891", "3012", "0.00", nextStan(), "000000000011"))).as("amount before card").isEqualTo("13");
    }

    // ---- limits, velocity, funds ----

    @Test
    void thePerTransactionCapAndTheDailyLimitAreEnforced() throws Exception {
        Fixture f = fixture("limit@example.com", "9000.00");
        jdbc.update("update cards set daily_limit = 100.00");

        assertThat(answer(purchase(f, "60.00"))).isEqualTo("00");
        assertThat(answer(purchase(f, "40.01"))).as("60 + 40.01 > 100").isEqualTo("61");
        assertThat(answer(purchase(f, "40.00"))).as("exactly the limit").isEqualTo("00");
        assertThat(answer(purchase(f, "0.01"))).isEqualTo("61");

        jdbc.update("update cards set daily_limit = 5000.00");
        assertThat(answer(purchase(f, "2500.00"))).as("exactly the cap").isEqualTo("00");
        assertThat(answer(purchase(f, "2500.01"))).as("over the cap").isEqualTo("61");
    }

    @Test
    void reversingAnAuthorizationGivesItsAmountBackToTheDailyLimit() throws Exception {
        Fixture f = fixture("limit2@example.com", "9000.00");
        jdbc.update("update cards set daily_limit = 100.00");
        Message first = purchase(f, "100.00");

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(first))).isEqualTo("00");
            assertThat(code(terminal.exchange(purchase(f, "1.00")))).isEqualTo("61");
            assertThat(code(terminal.exchange(reversalOf(first)))).isEqualTo("00");
            assertThat(code(terminal.exchange(purchase(f, "100.00")))).isEqualTo("00");
        }
    }

    @Test
    void tooManyApprovalsInAShortTimeAreRefusedDeclinesDoNotCountAndTheWindowSlides() throws Exception {
        Fixture f = fixture("velocity@example.com", "1000.00");

        for (int i = 0; i < 5; i++) {
            assertThat(answer(purchase(f, "1.00"))).isEqualTo("00");
        }
        assertThat(answer(purchase(f, "1.00"))).isEqualTo("65");
        assertThat(answer(purchase(f, "1.00"))).as("declines are not counted, the count stays at 5").isEqualTo("65");

        mutableClock.set(Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
        assertThat(answer(purchase(f, "1.00"))).isEqualTo("00");
    }

    @Test
    void anApprovalThatWasReversedStillCountsTowardsVelocity() throws Exception {
        Fixture f = fixture("velocity3@example.com", "1000.00");
        Message last = null;
        try (Terminal terminal = new Terminal().signedOn()) {
            for (int i = 0; i < 5; i++) {
                last = purchase(f, "1.00");
                assertThat(code(terminal.exchange(last))).isEqualTo("00");
            }
            assertThat(code(terminal.exchange(reversalOf(last)))).isEqualTo("00");

            assertThat(code(terminal.exchange(purchase(f, "1.00")))).isEqualTo("65");
        }
    }

    @Test
    void velocityIsCheckedBeforeTheLimits() throws Exception {
        Fixture f = fixture("velocity2@example.com", "1000.00");
        for (int i = 0; i < 5; i++) {
            assertThat(answer(purchase(f, "1.00"))).isEqualTo("00");
        }
        assertThat(answer(purchase(f, "2500.01"))).isEqualTo("65");
    }

    @Test
    void fundsAreCheckedAgainstTheAvailableBalanceNotTheBalance() throws Exception {
        Fixture f = fixture("funds@example.com", "50.00");

        assertThat(answer(purchase(f, "50.01"))).isEqualTo("51");
        assertThat(answer(purchase(f, "30.00"))).isEqualTo("00");
        assertThat(answer(purchase(f, "20.01"))).as("only 20.00 is still available").isEqualTo("51");
        assertThat(answer(purchase(f, "20.00"))).isEqualTo("00");
        assertThat(available(f)).isEqualByComparingTo("0.00");
        assertThat(rows("status = 'AUTHORIZED'")).isEqualTo(2);
        assertThat(rows("response_code = '51'")).isEqualTo(2);
    }

    @Test
    void aHoldThatRanOutNoLongerBlocksNewPayments() throws Exception {
        Fixture f = fixture("funds2@example.com", "50.00");
        assertThat(answer(purchase(f, "50.00"))).isEqualTo("00");
        assertThat(answer(purchase(f, "1.00"))).isEqualTo("51");

        mutableClock.set(Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));

        assertThat(answer(purchase(f, "1.00"))).isEqualTo("00");
    }

    @Test
    void theAvailableMoneyOfAnAccountIsSharedByItsCards() throws Exception {
        Fixture first = fixture("funds3@example.com", "50.00");
        Fixture second = card(first.token(), first.account());

        assertThat(answer(purchase(first, "40.00"))).isEqualTo("00");
        assertThat(answer(purchase(second, "40.00"))).as("the other card sees the first card's hold").isEqualTo("51");
        assertThat(answer(purchase(second, "10.00"))).isEqualTo("00");
        assertThat(available(first)).isEqualByComparingTo("0.00");
    }

    @Test
    void aDeclinedRequestNeverChangesTheBalanceOrTheLedger() throws Exception {
        Fixture f = fixture("clean@example.com", "10.00");
        BigDecimal before = jdbc.queryForObject("select count(*) from ledger_entries", BigDecimal.class);

        answer(purchase(f, "99.00"));
        answer(purchase(f, "5.00").set(18, "7995"));
        answer(purchaseWith("9999000000000000", f.expiry(), "5.00", nextStan(), "000000000003"));

        assertThat(jdbc.queryForObject("select count(*) from ledger_entries", BigDecimal.class)).isEqualByComparingTo(before);
        assertThat(rows("status = 'AUTHORIZED'")).isZero();
        assertThat(available(f)).isEqualByComparingTo("10.00");
    }
}
