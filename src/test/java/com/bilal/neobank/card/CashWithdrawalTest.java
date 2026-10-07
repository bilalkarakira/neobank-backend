package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.clearing.ClearingPoster;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cash withdrawals at a cash machine (SPEC-P6b-atm.md): processing code 01, the PIN, the cash limits, clearing. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class CashWithdrawalTest extends ClearingTestBase {

    /** A withdrawal request: processing code 010000, the machine's category 6011 and the PIN block. */
    private Message cash(Fixture f, String amount) {
        return withPin(purchase(f, amount).set(3, "010000").set(18, "6011"), f.pin());
    }

    private String answer(Message request) throws Exception {
        try (Terminal terminal = new Terminal().signedOn()) {
            return code(terminal.exchange(request));
        }
    }

    private String wrongPin(Fixture f) {
        return f.pin().equals("0000") ? "1111" : "0000";
    }

    // ---- what is approved ----

    @Test
    void aWithdrawalWithThePinIsApprovedAndHoldsTheMoneyAndIsMarkedCashInTheActivity() throws Exception {
        Fixture f = fixture("cash1@example.com", "1000.00");

        assertThat(answer(cash(f, "100.00"))).isEqualTo("00");

        assertThat(available(f)).isEqualByComparingTo("900.00");
        assertThat(jdbc.queryForObject("select processing_code || '/' || status || '/' || mcc from authorizations", String.class)).isEqualTo("010000/AUTHORIZED/6011");
        assertThat(answer(purchase(f, "20.00"))).isEqualTo("00");
        mockMvc.perform(get("/api/cards/" + f.cardId() + "/authorizations").header("Authorization", f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.amount=='100.00')].cash").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.items[?(@.amount=='20.00')].cash").value(org.hamcrest.Matchers.contains(false)));
        assertBooksBalance();
    }

    @Test
    void cashWithoutAPinIsRefusedAndHoldsNothing() throws Exception {
        Fixture f = fixture("cash2@example.com", "1000.00");

        assertThat(answer(purchase(f, "100.00").set(3, "010000").set(18, "6011"))).isEqualTo("57");

        assertThat(available(f)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select status || '/' || response_code from authorizations", String.class)).isEqualTo("DECLINED/57");
        assertThat(answer(purchase(f, "20.00"))).as("a purchase needs no PIN").isEqualTo("00");
    }

    // ---- the cash limits ----

    @Test
    void thePerWithdrawalLimitIsExactly300() throws Exception {
        Fixture f = fixture("cash3@example.com", "2000.00");

        assertThat(answer(cash(f, "300.01"))).isEqualTo("61");
        assertThat(answer(cash(f, "300.00"))).isEqualTo("00");
        assertThat(available(f)).isEqualByComparingTo("1700.00");
    }

    @Test
    void theDailyCashLimitIsExactly500AcrossWithdrawals() throws Exception {
        Fixture f = fixture("cash4@example.com", "2000.00");

        assertThat(answer(cash(f, "300.00"))).isEqualTo("00");
        assertThat(answer(cash(f, "200.01"))).as("500.01 in all").isEqualTo("61");
        assertThat(answer(cash(f, "200.00"))).as("exactly 500.00 in all").isEqualTo("00");
        assertThat(answer(cash(f, "10.00"))).as("anything more").isEqualTo("61");
        assertThat(available(f)).isEqualByComparingTo("1500.00");
    }

    @Test
    void aDeclinedWithdrawalDoesNotUseUpTheDailyCashLimit() throws Exception {
        Fixture f = fixture("cash5@example.com", "2000.00");

        assertThat(answer(cash(f, "400.00"))).isEqualTo("61");
        assertThat(answer(cash(f, "300.00"))).isEqualTo("00");
        assertThat(answer(cash(f, "200.00"))).isEqualTo("00");
    }

    @Test
    void cashCountsTowardTheCardsDailyLimitAndPurchasesDoNotUseTheCashLimit() throws Exception {
        Fixture f = fixture("cash6@example.com", "5000.00");
        jdbc.update("update cards set daily_limit = 400.00 where id = ?", f.cardId());

        assertThat(answer(purchase(f, "150.00"))).isEqualTo("00");
        assertThat(answer(cash(f, "250.01"))).as("150 + 250.01 is over the card's 400.00").isEqualTo("61");
        assertThat(answer(cash(f, "250.00"))).as("exactly the card's limit").isEqualTo("00");

        Fixture g = fixture("cash7@example.com", "5000.00");
        assertThat(answer(purchase(g, "600.00"))).isEqualTo("00");
        assertThat(answer(cash(g, "300.00"))).as("purchases do not eat the cash limit").isEqualTo("00");
        assertThat(answer(cash(g, "100.00"))).as("400 of cash, 1000 spent in all: exactly the card's limit").isEqualTo("00");

        Fixture h = fixture("cash14@example.com", "5000.00");
        assertThat(answer(cash(h, "300.00"))).isEqualTo("00");
        assertThat(answer(cash(h, "200.00"))).isEqualTo("00");
        assertThat(answer(purchase(h, "100.00"))).as("the cash limit is used up but a purchase is not cash").isEqualTo("00");
        assertThat(answer(cash(h, "10.00"))).isEqualTo("61");
    }

    // ---- the PIN, funds and the other processing codes ----

    @Test
    void aWrongPinOnCashIsCountedLikeAnywhereElseAndTheThirdFreezesTheCard() throws Exception {
        Fixture f = fixture("cash8@example.com", "1000.00");

        assertThat(answer(withPin(cash(f, "50.00"), wrongPin(f)))).isEqualTo("55");
        assertThat(answer(withPin(cash(f, "50.00"), wrongPin(f)))).isEqualTo("55");
        assertThat(jdbc.queryForObject("select pin_failures from cards", Integer.class)).isEqualTo(2);
        assertThat(answer(withPin(cash(f, "50.00"), wrongPin(f)))).isEqualTo("75");
        assertThat(jdbc.queryForObject("select status || '/' || freeze_reason from cards", String.class)).isEqualTo("FROZEN/PIN_TRIES");
        assertThat(answer(cash(f, "50.00"))).as("the right PIN meets a frozen card").isEqualTo("57");
    }

    @Test
    void notEnoughMoneyIs51AndTheCashLimitIsCheckedBeforeFunds() throws Exception {
        Fixture f = fixture("cash9@example.com", "100.00");

        assertThat(answer(cash(f, "200.00"))).isEqualTo("51");
        assertThat(answer(cash(f, "400.00"))).as("the per-withdrawal limit comes first").isEqualTo("61");
        assertThat(answer(cash(f, "100.00"))).isEqualTo("00");
        assertThat(available(f)).isEqualByComparingTo("0.00");
    }

    @Test
    void otherProcessingCodesAreStillRefused() throws Exception {
        Fixture f = fixture("cash10@example.com", "1000.00");

        for (String code : new String[] {"200000", "300000", "400000", "990000", "100000", "020000"}) {
            assertThat(answer(withPin(purchase(f, "10.00").set(3, code), f.pin()))).as(code).isEqualTo("57");
        }
        assertThat(answer(withPin(purchase(f, "10.00").set(3, "000000"), f.pin()))).isEqualTo("00");
    }

    @Test
    void aWithdrawalIsSubjectToTheRulesOfAnyPaymentTheCardMustNotBeFrozenOrTheAccountBlocked() throws Exception {
        Fixture f = fixture("cash11@example.com", "1000.00");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", f.account().id());
        assertThat(answer(cash(f, "100.00"))).isEqualTo("05");
        jdbc.update("update accounts set status = 'ACTIVE' where id = ?::uuid", f.account().id());
        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'CUSTOMER' where id = ?", f.cardId());
        assertThat(answer(cash(f, "100.00"))).isEqualTo("57");
    }

    // ---- clearing ----

    @Test
    void aClearedWithdrawalIsPostedOnceReleasesItsHoldAndKeepsTheBooksRight() throws Exception {
        Fixture f = fixture("cash12@example.com", "1000.00");
        Message request = cash(f, "100.00");
        assertThat(answer(request)).isEqualTo("00");
        Message presentment = presentment(request, "100.00");
        assertThat(presentment.get(3)).as("the presentment keeps the cash code").isEqualTo("010000");

        ClearingPoster.Result first = present(presentment);
        ClearingPoster.Result again = present(presentment);

        assertThat(first.result()).isEqualTo("POSTED");
        assertThat(again.result()).as("another file with the same item: already captured").isEqualTo("DUPLICATE");
        assertThat(authorizationState(request)).isEqualTo("CAPTURED/100.00/false");
        assertThat(balanceOf(f)).isEqualByComparingTo("900.00");
        assertThat(available(f)).as("the hold is gone and the money really left").isEqualByComparingTo("900.00");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 100.00");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("100.00");
        assertBooksBalance();
    }

    @Test
    void aWithdrawalCanBeDisputedAsNotReceived() throws Exception {
        Fixture f = fixture("cash13@example.com", "1000.00");
        Message request = cash(f, "100.00");
        assertThat(answer(request)).isEqualTo("00");
        present(presentment(request, "100.00"));
        java.util.UUID authorization = jdbc.queryForObject("select id from authorizations where stan = ? and rrn = ?", java.util.UUID.class, request.get(11), request.get(37));

        mockMvc.perform(post("/api/cards/payments/" + authorization + "/dispute").header("Authorization", f.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"NOT_RECEIVED\",\"note\":\"The machine kept the cash\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("100.00"));

        assertThat(balanceOf(f)).as("900 and the provisional credit").isEqualByComparingTo(new BigDecimal("1000.00"));
    }
}
