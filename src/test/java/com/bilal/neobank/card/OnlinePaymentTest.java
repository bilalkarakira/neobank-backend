package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Online payments (card not present): the security code printed on the card replaces the PIN. The order of the rules matters as much as
 * the rules: a wrong code on a frozen card is 57, on an empty account it is N7 (the code is checked before the money).
 */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=50" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class OnlinePaymentTest extends IssuerTestBase {

    @Autowired
    CardCrypto crypto;

    private String answer(Message request) throws Exception {
        try (Terminal terminal = new Terminal().signedOn()) {
            return code(terminal.exchange(request));
        }
    }

    private String wrongCode(Fixture f) {
        return f.cvv().equals("000") ? "111" : "000";
    }

    private int failures(Fixture f) {
        return jdbc.queryForObject("select pin_failures from cards where id = ?", Integer.class, f.cardId());
    }

    // ---- the right code ----

    @Test
    void aRightSecurityCodeApprovesHoldsTheMoneyAndRecordsTheOnlineEntryMode() throws Exception {
        Fixture f = fixture("cnp1@example.com", "100.00");

        assertThat(answer(online(f, "19.99", f.cvv()))).isEqualTo("00");

        assertThat(available(f)).isEqualByComparingTo("80.01");
        assertThat(jdbc.queryForObject("select status || '/' || entry_mode from authorizations", String.class)).isEqualTo("AUTHORIZED/812");
        assertBooksBalance();
    }

    @Test
    void theCodeIsKeptOnlyAsAKeyedHashAndNotInAnyAuthorizationRow() throws Exception {
        Fixture f = fixture("cnp2@example.com", "100.00");

        assertThat(answer(online(f, "10.00", f.cvv()))).isEqualTo("00");
        assertThat(answer(online(f, "10.00", wrongCode(f)))).isEqualTo("N7");

        String hash = jdbc.queryForObject("select cvv_hash from cards where id = ?", String.class, f.cardId());
        assertThat(hash).matches("[0-9a-f]{64}").doesNotContain(f.cvv());
        assertThat(hash).isEqualTo(crypto.hashCvv(f.cardId(), f.cvv()));
        assertThat(hash).as("the card id is part of the hash: the same code on another card hashes differently")
                .isNotEqualTo(crypto.hashCvv(UUID.randomUUID(), f.cvv()));
        String rows = jdbc.queryForObject("select coalesce(string_agg(to_jsonb(a)::text, ' '), '') from authorizations a", String.class);
        assertThat(rows).doesNotContain("\"" + f.cvv() + "\"").doesNotContain("\"" + f.pin() + "\"");
    }

    // ---- the wrong code ----

    @Test
    void aWrongOrMissingCodeIsN7IsRecordedAsDeclinedHoldsNothingAndCounts() throws Exception {
        Fixture f = fixture("cnp3@example.com", "100.00");

        assertThat(answer(online(f, "10.00", wrongCode(f)))).isEqualTo("N7");
        assertThat(failures(f)).isEqualTo(1);
        assertThat(answer(online(f, "10.00", null))).as("an online payment without a code").isEqualTo("N7");
        assertThat(failures(f)).isEqualTo(2);

        assertThat(available(f)).isEqualByComparingTo("100.00");
        assertThat(rows("status = 'DECLINED' and response_code = 'N7'")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from cards", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void theThirdWrongCodeFreezesTheCardAndTellsTheCustomerAndTheCorrectCodeThenDoesNotHelp() throws Exception {
        Fixture f = fixture("cnp4@example.com", "100.00");

        assertThat(answer(online(f, "10.00", wrongCode(f)))).isEqualTo("N7");
        assertThat(answer(online(f, "10.00", wrongCode(f)))).isEqualTo("N7");
        assertThat(answer(online(f, "10.00", wrongCode(f)))).isEqualTo("75");

        assertThat(jdbc.queryForObject("select status || '/' || freeze_reason from cards", String.class)).isEqualTo("FROZEN/PIN_TRIES");
        assertThat(jdbc.queryForObject("select body from notifications where kind = 'CARD_FROZEN_PIN'", String.class))
                .contains("wrong PINs or security codes");
        assertThat(answer(online(f, "10.00", f.cvv()))).as("frozen").isEqualTo("57");
    }

    @Test
    void wrongCodesAndWrongPinsShareOneCounter() throws Exception {
        Fixture f = fixture("cnp5@example.com", "100.00");
        String wrongPin = f.pin().equals("0000") ? "1111" : "0000";

        assertThat(answer(withPin(purchase(f, "5.00"), wrongPin))).isEqualTo("55");
        assertThat(answer(online(f, "5.00", wrongCode(f)))).isEqualTo("N7");
        assertThat(answer(withPin(purchase(f, "5.00"), wrongPin))).as("the third failure, of either kind").isEqualTo("75");
        assertThat(jdbc.queryForObject("select status from cards", String.class)).isEqualTo("FROZEN");
    }

    @Test
    void aRightCodeDoesNotUndoEarlierFailuresButARightPinDoes() throws Exception {
        Fixture f = fixture("cnp6@example.com", "100.00");

        assertThat(answer(online(f, "5.00", wrongCode(f)))).isEqualTo("N7");
        assertThat(answer(online(f, "5.00", wrongCode(f)))).isEqualTo("N7");
        assertThat(answer(online(f, "5.00", f.cvv()))).isEqualTo("00");
        assertThat(failures(f)).as("a right code is not a way out of the counter").isEqualTo(2);
        assertThat(answer(online(f, "5.00", wrongCode(f)))).isEqualTo("75");

        Fixture g = fixture("cnp6b@example.com", "100.00");
        assertThat(answer(online(g, "5.00", wrongCode(g)))).isEqualTo("N7");
        assertThat(answer(online(g, "5.00", wrongCode(g)))).isEqualTo("N7");
        assertThat(answer(withPin(purchase(g, "5.00"), g.pin()))).isEqualTo("00");
        assertThat(failures(g)).as("the PIN, entered in person, does").isZero();
    }

    // ---- what an online payment is not ----

    @Test
    void aPinInAnOnlinePaymentIsRefusedWithoutCountingEvenWithTheRightCode() throws Exception {
        Fixture f = fixture("cnp7@example.com", "100.00");

        assertThat(answer(withPin(online(f, "5.00", f.cvv()), f.pin()))).isEqualTo("57");
        assertThat(answer(withPin(online(f, "5.00", wrongCode(f)), f.pin()))).isEqualTo("57");

        assertThat(failures(f)).isZero();
        assertThat(available(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void aCardWithoutASecurityCodeCannotPayOnlineAndIsNotPunishedForIt() throws Exception {
        Fixture f = fixture("cnp8@example.com", "100.00");
        jdbc.update("update cards set cvv_hash = null where id = ?", f.cardId());

        assertThat(answer(online(f, "5.00", "123"))).isEqualTo("57");

        assertThat(failures(f)).isZero();
        assertThat(answer(purchase(f, "5.00"))).as("in a terminal it still works").isEqualTo("00");
    }

    @Test
    void aSecurityCodeInAMessageThatSaysItIsInATerminalIsStillChecked() throws Exception {
        Fixture f = fixture("cnp9@example.com", "100.00");

        assertThat(answer(purchase(f, "5.00").set(53, wrongCode(f)))).isEqualTo("N7");
        assertThat(answer(purchase(f, "5.00").set(53, f.cvv()))).isEqualTo("00");
    }

    @Test
    void aPaymentInATerminalWithoutACodeNeedsNone() throws Exception {
        Fixture f = fixture("cnp10@example.com", "100.00");

        assertThat(answer(purchase(f, "5.00"))).isEqualTo("00");
        assertThat(answer(purchase(f, "5.00").set(22, "021"))).isEqualTo("00");
        assertThat(failures(f)).isZero();
    }

    // ---- the order of the rules ----

    @Test
    void theCodeIsCheckedAfterTheCardAndAccountButBeforeTheMoney() throws Exception {
        Fixture poor = fixture("cnp11@example.com", "1.00");
        assertThat(answer(online(poor, "25.00", wrongCode(poor)))).as("a wrong code on an empty account").isEqualTo("N7");
        assertThat(answer(online(poor, "25.00", poor.cvv()))).as("a right code on an empty account").isEqualTo("51");

        Fixture over = fixture("cnp12@example.com", "100.00");
        assertThat(answer(online(over, "99999.00", wrongCode(over)))).as("a wrong code on an amount over the cap").isEqualTo("N7");

        Fixture frozen = fixture("cnp13@example.com", "100.00");
        jdbc.update("update cards set status = 'FROZEN', freeze_reason = 'CUSTOMER' where id = ?", frozen.cardId());
        assertThat(answer(online(frozen, "5.00", wrongCode(frozen)))).as("a wrong code on a frozen card").isEqualTo("57");
        assertThat(failures(frozen)).isZero();

        Fixture expired = fixture("cnp14@example.com", "100.00");
        assertThat(answer(online(expired, "5.00", wrongCode(expired)).set(14, "2001"))).as("a wrong code with a wrong expiry").isEqualTo("14");
        assertThat(failures(expired)).isZero();
    }

    // ---- the customer sees it ----

    @Test
    void theCodeIsShownOnceWhenTheCardIsIssuedAndNeverAgain() throws Exception {
        String token = tokenFor("cnp15@example.com");
        Acc account = open(token, "CHECKING");
        deposit(token, account, "100.00");

        String issued = mockMvc.perform(post("/api/cards").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\"}".formatted(account.id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String cvv = JsonPath.read(issued, "$.cvv");
        assertThat(cvv).matches("\\d{3}");
        assertThat(JsonPath.<String>read(issued, "$.pin")).matches("\\d{4}");

        String list = mockMvc.perform(get("/api/cards").header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("cvv").doesNotContain("\"" + cvv + "\"");
        assertThat(list).doesNotContain("pinHash").doesNotContain("cvvHash");

        UUID cardId = UUID.fromString(JsonPath.read(issued, "$.card.id"));
        String pan = JsonPath.read(issued, "$.pan");
        String expiry = jdbc.queryForObject("select expiry from cards where id = ?", String.class, cardId);
        Fixture f = new Fixture(token, account, cardId, pan, JsonPath.read(issued, "$.pin"), expiry, cvv);
        assertThat(answer(online(f, "5.00", cvv))).as("the code shown at issue is the one that works").isEqualTo("00");
    }

    @Test
    void anOnlinePaymentIsMarkedAsOnlineInTheCardsActivity() throws Exception {
        Fixture f = fixture("cnp16@example.com", "100.00");
        assertThat(answer(online(f, "5.00", f.cvv()))).isEqualTo("00");
        assertThat(answer(purchase(f, "6.00"))).isEqualTo("00");
        assertThat(answer(online(f, "7.00", wrongCode(f)))).isEqualTo("N7");

        String body = mockMvc.perform(get("/api/cards/" + f.cardId() + "/authorizations").header("Authorization", f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<Boolean>>read(body, "$.items[?(@.amount=='5.00')].online")).containsExactly(true);
        assertThat(JsonPath.<java.util.List<Boolean>>read(body, "$.items[?(@.amount=='6.00')].online")).containsExactly(false);
        assertThat(JsonPath.<java.util.List<String>>read(body, "$.items[?(@.amount=='7.00')].reason")).containsExactly("The security code was wrong or missing.");
        assertThat(body).as("no field carries the code").doesNotContain("cvv");
    }
}
