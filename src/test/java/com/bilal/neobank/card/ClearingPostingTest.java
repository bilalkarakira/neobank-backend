package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.clearing.ClearingPoster;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/** Posting one cleared item: every outcome of the matching table, the exact ledger moves, and the books. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class ClearingPostingTest extends ClearingTestBase {

    // ---- purchases that match an authorization ----

    @Test
    void aMatchedPurchaseIsPostedTheHoldIsReleasedAndTheAuthorizationIsCaptured() throws Exception {
        Fixture f = fixture("clr1@example.com", "100.00");
        Message request = authorized(f, "30.00");
        assertThat(available(f)).isEqualByComparingTo("70.00");

        ClearingPoster.Result result = present(presentment(request, "30.00"));

        assertThat(result.result()).isEqualTo("POSTED");
        assertThat(authorizationState(request)).isEqualTo("CAPTURED/30.00/false");
        assertThat(balanceOf(f)).isEqualByComparingTo("70.00");
        assertThat(available(f)).as("the hold is gone and the money really left").isEqualByComparingTo("70.00");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("30.00");
        assertThat(jdbc.queryForObject("select count(*) from authorizations where status = 'AUTHORIZED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select covered || '/' || shortfall || '/' || result from clearing_items", String.class)).isEqualTo("30.00/0.00/POSTED");
        assertThat(jdbc.queryForObject("select initiated_by is null and source_account_id = ?::uuid and destination_account_id = ? from transactions where type = 'CARD_PURCHASE'",
                Boolean.class, f.account().id(), SETTLEMENT)).isTrue();
        assertBooksBalance();
    }

    @Test
    void aTipChangesTheAmountPostedNotTheHold() throws Exception {
        Fixture f = fixture("clr2@example.com", "100.00");
        Message request = authorized(f, "50.00");

        ClearingPoster.Result result = present(presentment(request, "57.50"));

        assertThat(result.result()).isEqualTo("POSTED");
        assertThat(authorizationState(request)).isEqualTo("CAPTURED/57.50/false");
        assertThat(balanceOf(f)).isEqualByComparingTo("42.50");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 57.50");
        assertThat(owed(f)).isEqualByComparingTo("0");
        assertBooksBalance();
    }

    @Test
    void aSmallerFinalAmountPostsLessAndFreesTheRestOfTheHold() throws Exception {
        Fixture f = fixture("clr3@example.com", "100.00");
        Message request = authorized(f, "50.00");

        present(presentment(request, "20.00"));

        assertThat(balanceOf(f)).isEqualByComparingTo("80.00");
        assertThat(available(f)).isEqualByComparingTo("80.00");
        assertThat(authorizationState(request)).isEqualTo("CAPTURED/20.00/false");
    }

    @Test
    void whenTheAccountCannotCoverTheTipTheBankPaysAndTheCustomerOwesTheRest() throws Exception {
        Fixture f = fixture("clr4@example.com", "52.00");
        Message request = authorized(f, "50.00");

        ClearingPoster.Result result = present(presentment(request, "57.50"));

        assertThat(result.result()).isEqualTo("POSTED");
        assertThat(result.reason()).contains("shortfall 5.50");
        assertThat(balanceOf(f)).as("the customer pays what the account holds, never below zero").isEqualByComparingTo("0.00");
        assertThat(owed(f)).isEqualByComparingTo("5.50");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 52.00", "CARD_SHORTFALL 5.50");
        assertThat(balanceOf(SETTLEMENT)).as("the network is paid in full").isEqualByComparingTo("57.50");
        assertThat(balanceOf(RECEIVABLE)).isEqualByComparingTo("-5.50");
        assertThat(jdbc.queryForObject("select covered || '/' || shortfall from clearing_items", String.class)).isEqualTo("52.00/5.50");
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?::uuid and kind = 'CARD_SHORTFALL'", Integer.class, f.account().userId())).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void aHoldThatLapsedBeforeClearingDoesNotStopTheDebit() throws Exception {
        Fixture f = fixture("clr5@example.com", "100.00");
        Message request = authorized(f, "30.00");
        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(9)));
        assertThat(available(f)).as("the hold lapsed").isEqualByComparingTo("100.00");

        ClearingPoster.Result result = present(presentment(request, "30.00"));

        assertThat(result.result()).isEqualTo("POSTED");
        assertThat(authorizationState(request)).isEqualTo("CAPTURED/30.00/false");
        assertThat(balanceOf(f)).isEqualByComparingTo("70.00");
    }

    @Test
    void aLapsedHoldWhoseMoneyWasSpentMeanwhileBecomesAReceivable() throws Exception {
        Fixture f = fixture("clr6@example.com", "100.00");
        Message request = authorized(f, "80.00");
        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(9)));
        // the customer spends most of it after the hold lapsed (test helper: straight into the ledger)
        Acc other = open(tokenFor("clr6b@example.com"), "CHECKING");
        transferOn(f.account(), other, "70.00", mutableClock.instant(), null);

        ClearingPoster.Result result = present(presentment(request, "80.00"));

        assertThat(result.result()).isEqualTo("POSTED");
        assertThat(balanceOf(f)).isEqualByComparingTo("0.00");
        assertThat(owed(f)).isEqualByComparingTo("50.00");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00", "CARD_SHORTFALL 50.00");
    }

    @Test
    void otherHoldsOnTheAccountAreNotSpentByTheDebit() throws Exception {
        Fixture f = fixture("clr7@example.com", "100.00");
        Message first = authorized(f, "40.00");
        authorized(f, "50.00"); // stays held
        assertThat(available(f)).isEqualByComparingTo("10.00");

        ClearingPoster.Result result = present(presentment(first, "55.00"));

        // 100 - the other hold of 50 = 50 can be covered; the tip beyond that is owed
        assertThat(result.reason()).contains("shortfall 5.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("50.00");
        assertThat(owed(f)).isEqualByComparingTo("5.00");
        assertBooksBalance();
    }

    // ---- not simply posted ----

    @Test
    void presentingTheSamePaymentAgainPostsNothingTheSecondTime() throws Exception {
        Fixture f = fixture("clr8@example.com", "100.00");
        Message request = authorized(f, "30.00");
        present(presentment(request, "30.00"));

        ClearingPoster.Result again = present(presentment(request, "30.00"));

        assertThat(again.result()).isEqualTo("DUPLICATE");
        assertThat(again.reason()).isEqualTo("already captured");
        assertThat(balanceOf(f)).isEqualByComparingTo("70.00");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00");
    }

    @Test
    void aReversedOrDeclinedAuthorizationCannotBePresented() throws Exception {
        Fixture f = fixture("clr9@example.com", "100.00");
        Message reversed = authorized(f, "30.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(reversalOf(reversed)))).isEqualTo("00");
            Message declined = purchase(f, "9999.00");
            assertThat(code(terminal.exchange(declined))).isNotEqualTo("00");
            ClearingPoster.Result r1 = present(presentment(reversed, "30.00"));
            ClearingPoster.Result r2 = present(presentment(declined, "9999.00"));

            assertThat(r1.result()).isEqualTo("REJECTED");
            assertThat(r1.reason()).isEqualTo("the authorization was reversed");
            assertThat(r2.result()).isEqualTo("REJECTED");
            assertThat(r2.reason()).isEqualTo("the authorization was declined");
        }
        assertThat(balanceOf(f)).isEqualByComparingTo("100.00");
        assertThat(cardTransactions()).isEmpty();
    }

    @Test
    void aPresentmentWithNoAuthorizationIsForcePostedAndFlagged() throws Exception {
        Fixture f = fixture("clr10@example.com", "100.00");
        Message never = purchase(f, "40.00"); // never sent to the issuer

        ClearingPoster.Result result = present(presentment(never, "40.00"));

        assertThat(result.result()).isEqualTo("FORCE_POSTED");
        assertThat(authorizationState(never)).isEqualTo("CAPTURED/40.00/true");
        assertThat(balanceOf(f)).isEqualByComparingTo("60.00");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 40.00");
        assertThat(jdbc.queryForObject("select forced from authorizations where stan = ?", Boolean.class, never.get(11))).isTrue();
        // and a second presentment of the same item is now a duplicate, not a second debit
        assertThat(present(presentment(never, "40.00")).result()).isEqualTo("DUPLICATE");
        assertBooksBalance();
    }

    @Test
    void anUnknownCardIsRejectedNotPosted() throws Exception {
        Fixture f = fixture("clr11@example.com", "100.00");
        Message request = purchase(f, "10.00").set(2, "9999000000000079");

        ClearingPoster.Result result = present(presentment(request, "10.00"));

        assertThat(result.result()).isEqualTo("REJECTED");
        assertThat(result.reason()).isEqualTo("unknown card");
        assertThat(cardTransactions()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from authorizations", Integer.class)).isZero();
    }

    @Test
    void anAccountThatIsNotActiveGetsNothingPostedAndNeedsAPerson() throws Exception {
        Fixture f = fixture("clr12@example.com", "100.00");
        Message request = authorized(f, "30.00");
        jdbc.update("update accounts set status = 'CLOSED' where id = ?::uuid", f.account().id());

        ClearingPoster.Result result = present(presentment(request, "30.00"));

        assertThat(result.result()).isEqualTo("REJECTED");
        assertThat(result.reason()).contains("closed").contains("needs a person");
        assertThat(authorizationState(request)).as("still authorized, not captured").startsWith("AUTHORIZED");
        assertThat(cardTransactions()).isEmpty();
    }

    // ---- refunds ----

    @Test
    void aRefundCreditsTheCustomer() throws Exception {
        Fixture f = fixture("clr13@example.com", "100.00");
        Message request = authorized(f, "30.00");
        present(presentment(request, "30.00"));

        ClearingPoster.Result result = present(refundPresentment(f, "12.00"));

        assertThat(result.result()).isEqualTo("REFUND_POSTED");
        assertThat(balanceOf(f)).isEqualByComparingTo("82.00");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00", "CARD_REFUND 12.00");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("18.00");
        assertBooksBalance();
    }

    @Test
    void aRefundPaysOffWhatTheCustomerOwesBeforeTheyGetAnything() throws Exception {
        Fixture f = fixture("clr14@example.com", "52.00");
        present(presentment(authorized(f, "50.00"), "57.50")); // owes 5.50

        ClearingPoster.Result partial = present(refundPresentment(f, "3.00"));
        assertThat(partial.reason()).contains("applied 3.00");
        assertThat(owed(f)).isEqualByComparingTo("2.50");
        assertThat(balanceOf(f)).as("nothing reaches the customer yet").isEqualByComparingTo("0.00");

        ClearingPoster.Result rest = present(refundPresentment(f, "10.00"));
        assertThat(rest.reason()).contains("applied 2.50");
        assertThat(owed(f)).isEqualByComparingTo("0");
        assertThat(balanceOf(f)).isEqualByComparingTo("7.50");
        assertThat(balanceOf(RECEIVABLE)).as("the receivable account is back to zero").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from card_receivables where outstanding = 0 and settled_at is not null", Integer.class)).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void aRefundForAnUnknownCardOrAClosedAccountIsRejected() throws Exception {
        Fixture f = fixture("clr15@example.com", "100.00");
        Message unknown = refundPresentment(f, "5.00").set(2, "9999000000000079");
        assertThat(present(unknown).result()).isEqualTo("REJECTED");
        jdbc.update("update accounts set status = 'CLOSED' where id = ?::uuid", f.account().id());
        assertThat(present(refundPresentment(f, "5.00")).result()).isEqualTo("REJECTED");
        assertThat(cardTransactions()).isEmpty();
    }

    // ---- the guarantee: once ----

    @Test
    void theSameItemPostedByTwoThreadsAtOnceMovesMoneyOnce() throws Exception {
        Fixture f = fixture("clr16@example.com", "100.00");
        Message request = authorized(f, "30.00");
        Message presentment = presentment(request, "30.00");
        UUID file = newFile();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable run = () -> {
            try {
                barrier.await();
                outcomes.add(poster.post(file, 1, presentment));
            } catch (Exception e) {
                outcomes.add(e);
            }
        };
        Thread a = new Thread(run);
        Thread b = new Thread(run);
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(outcomes).hasSize(2).allSatisfy(o -> assertThat(o).isInstanceOf(ClearingPoster.Result.class));
        assertThat(outcomes.stream().map(o -> ((ClearingPoster.Result) o).result())).containsOnly("POSTED");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("70.00");
        assertThat(jdbc.queryForObject("select count(*) from clearing_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void twoDifferentFilesPresentingTheSamePaymentAtOnceMoveMoneyOnce() throws Exception {
        Fixture f = fixture("clr17@example.com", "100.00");
        Message request = authorized(f, "30.00");
        Message presentment = presentment(request, "30.00");
        UUID file1 = newFile();
        UUID file2 = newFile();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread a = new Thread(() -> post(barrier, file1, presentment, results));
        Thread b = new Thread(() -> post(barrier, file2, presentment, results));
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(results).containsExactlyInAnyOrder("POSTED", "DUPLICATE");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("70.00");
    }

    private void post(CyclicBarrier barrier, UUID file, Message presentment, List<String> results) {
        try {
            barrier.await();
            results.add(poster.post(file, 1, presentment).result());
        } catch (Exception e) {
            results.add("ERROR " + e);
        }
    }

    // ---- amounts, edges ----

    @Test
    void theBooksAgreeWithTheItemsAfterAMixedDay() throws Exception {
        Fixture f = fixture("clr18@example.com", "200.00");
        present(presentment(authorized(f, "30.00"), "30.00"));
        present(presentment(authorized(f, "50.00"), "57.50"));
        present(refundPresentment(f, "10.00"));
        present(presentment(purchase(f, "5.00"), "5.00")); // forced

        BigDecimal posted = jdbc.queryForObject("select sum(case when result in ('POSTED', 'FORCE_POSTED') then amount when result = 'REFUND_POSTED' then -amount else 0 end) from clearing_items", BigDecimal.class);
        assertThat(posted).isEqualByComparingTo("82.50");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo(posted);
        assertThat(balanceOf(f)).isEqualByComparingTo("117.50");
        assertBooksBalance();
    }
}
