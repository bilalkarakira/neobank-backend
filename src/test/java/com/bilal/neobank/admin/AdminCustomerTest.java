package com.bilal.neobank.admin;

import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.bilal.neobank.ledger.Fingerprint;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What an administrator can see about one customer, and closing an account for good. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminCustomerTest extends AnalyticsTestBase {

    private static final String REASON = "{\"reason\":\"Customer asked to close it\"}";

    @MockitoSpyBean
    AdminRepository adminRepository;

    private String adminToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Ada","lastName":"Admin","email":"ada@example.com","password":"%s"}
                """.formatted(PASSWORD))).andExpect(status().isCreated());
        jdbc.update("update users set role = 'ADMIN' where email = 'ada@example.com'");
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"ada@example.com","password":"%s"}
                """.formatted(PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) {
            request.header("Authorization", token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions close(String admin, Acc account) throws Exception {
        return send(post("/api/admin/accounts/" + account.id() + "/close"), admin, REASON);
    }

    private String statusOf(Acc account) {
        return jdbc.queryForObject("select status from accounts where id = ?::uuid", String.class, account.id());
    }

    private int auditRows() {
        return jdbc.queryForObject("select count(*) from admin_actions", Integer.class);
    }

    private void standingOrder(String token, Acc from, Acc to) throws Exception {
        send(post("/api/recurring-payments"), token, """
                {"sourceAccountId":"%s","destinationIban":"%s","amount":5.00,"frequency":"MONTHLY","startDate":"%s"}
                """.formatted(from.id(), to.iban(), today())).andExpect(status().isCreated());
    }

    // ---- access -----------------------------------------------------------------------------------------------

    @Test
    void bothEndpointsRejectAnonymousCallersAndCustomers() throws Exception {
        String customer = tokenFor("alice@example.com");
        Acc a = open(customer, "CHECKING");
        String id = a.userId();

        send(get("/api/admin/customers/" + id), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/customers/" + id), customer, null).andExpect(status().isForbidden());
        send(post("/api/admin/accounts/" + a.id() + "/close"), null, REASON).andExpect(status().isUnauthorized());
        send(post("/api/admin/accounts/" + a.id() + "/close"), customer, REASON).andExpect(status().isForbidden());

        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        assertThat(auditRows()).isZero();
    }

    // ---- customer detail --------------------------------------------------------------------------------------

    @Test
    void theDetailShowsTheProfileAccountsActivityAndStanding() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(checking, "500.00", Instant.now().minus(3, ChronoUnit.DAYS));
        transferOn(checking, bob, "120.00", Instant.now().minus(2, ChronoUnit.DAYS), null);
        standingOrder(alice, checking, bob);
        standingOrder(alice, savings, bob);
        tokenForLogin("alice@example.com"); // a second login: two active sessions now

        send(get("/api/admin/customers/" + checking.userId()), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value("alice@example.com"))
                .andExpect(jsonPath("$.profile.firstName").value("Test"))
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].iban").value(checking.iban()))
                .andExpect(jsonPath("$.accounts[0].balance").value("380.00"))
                .andExpect(jsonPath("$.accounts[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.accounts[1].type").value("SAVINGS"))
                .andExpect(jsonPath("$.recentActivity.length()").value(2))
                .andExpect(jsonPath("$.recentActivity[0].amount").value("-120.00")) // newest first, signed
                .andExpect(jsonPath("$.recentActivity[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.recentActivity[0].accountIban").value(checking.iban()))
                .andExpect(jsonPath("$.recentActivity[1].amount").value("500.00"))
                .andExpect(jsonPath("$.recentActivity[1].balanceAfter").value("500.00"))
                .andExpect(jsonPath("$.activeSessions").value(2))
                .andExpect(jsonPath("$.loginLockedUntil").doesNotExist())
                .andExpect(jsonPath("$.activeOrders").value(2))
                .andExpect(jsonPath("$.pausedOrders").value(0));
    }

    private void tokenForLogin(String email) throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void theActivityIsTheNewestTwentyEntriesAndNeverAnotherCustomers() throws Exception {
        String admin = adminToken();
        Acc alice = open(tokenFor("alice@example.com"), "CHECKING");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        for (int i = 1; i <= 25; i++) {
            depositOn(alice, "%d.00".formatted(i), Instant.now().minus(30 - i, ChronoUnit.HOURS));
        }
        depositOn(bob, "999.00", Instant.now());

        MvcResult result = send(get("/api/admin/customers/" + alice.userId()), admin, null).andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        List<String> amounts = JsonPath.read(body, "$.recentActivity[*].amount");
        assertThat(amounts).hasSize(20).first().isEqualTo("25.00");
        assertThat(amounts).last().isEqualTo("6.00");
        assertThat(body).doesNotContain("999.00").doesNotContain("bob@example.com").doesNotContain(bob.iban());
    }

    @Test
    void aLockedLoginAndPausedOrdersAreShown() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        standingOrder(alice, a, bob);
        String orderId = jdbc.queryForObject("select id::text from recurring_payments", String.class);
        send(post("/api/recurring-payments/" + orderId + "/pause"), alice, null).andExpect(status().isOk());
        jdbc.update("insert into login_failures (email_hash, failures, first_failure_at, locked_until, updated_at) "
                + "values (?, 5, now(), now() + interval '10 minutes', now())", Fingerprint.sha256("alice@example.com"));

        send(get("/api/admin/customers/" + a.userId()), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.loginLockedUntil").exists())
                .andExpect(jsonPath("$.activeOrders").value(0)).andExpect(jsonPath("$.pausedOrders").value(1));
    }

    @Test
    void everyViewIsAuditedWithWhoAndWhichCustomer() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");

        send(get("/api/admin/customers/" + a.userId()), admin, null).andExpect(status().isOk());
        send(get("/api/admin/customers/" + a.userId()), admin, null).andExpect(status().isOk());

        send(get("/api/admin/audit"), admin, null).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("VIEW_CUSTOMER"))
                .andExpect(jsonPath("$[0].detail").value("email=alice@example.com"))
                .andExpect(jsonPath("$[0].adminEmail").value("ada@example.com"));
    }

    @Test
    void unknownIdsAndAdministratorsAreNotFoundAndLeaveNoAuditTrace() throws Exception {
        String admin = adminToken();
        tokenFor("alice@example.com");
        String adminId = jdbc.queryForObject("select id::text from users where email = 'ada@example.com'", String.class);

        send(get("/api/admin/customers/" + UUID.randomUUID()), admin, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        send(get("/api/admin/customers/" + adminId), admin, null).andExpect(status().isNotFound()); // admins are not browsable
        send(get("/api/admin/customers/not-a-uuid"), admin, null).andExpect(status().isBadRequest());

        assertThat(auditRows()).isZero();
    }

    @Test
    void ifTheViewCannotBeAuditedNoPersonalDataIsReturned() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        doThrow(new IllegalStateException("audit store down")).when(adminRepository)
                .insertAction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> send(get("/api/admin/customers/" + a.userId()), admin, null))
                .hasRootCauseMessage("audit store down");
    }

    // ---- closing an account -----------------------------------------------------------------------------------

    @Test
    void anEmptyActiveOrFrozenAccountCanBeClosedAndTheCloseIsAudited() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc active = open(alice, "CHECKING");
        Acc frozen = open(alice, "SAVINGS");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", frozen.id());

        close(admin, active).andExpect(status().isNoContent());
        close(admin, frozen).andExpect(status().isNoContent());

        assertThat(statusOf(active)).isEqualTo("CLOSED");
        assertThat(statusOf(frozen)).isEqualTo("CLOSED");
        send(get("/api/admin/audit"), admin, null).andExpect(jsonPath("$[0].action").value("CLOSE_ACCOUNT"))
                .andExpect(jsonPath("$[0].reason").value("Customer asked to close it"))
                .andExpect(jsonPath("$[0].detail").value("cancelledOrders=0"))
                .andExpect(jsonPath("$[0].accountIban").value(frozen.iban()));
    }

    @Test
    void anAccountWithMoneyInItCannotBeClosedNotEvenForOneCent() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        depositOn(a, "0.01", Instant.now().minus(1, ChronoUnit.DAYS));

        close(admin, a).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_NOT_EMPTY"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("0.01")));

        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        assertThat(auditRows()).isZero();
    }

    @Test
    void anAccountEmptiedByTransfersCanBeClosed() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        Acc b = open(tokenFor("bob@example.com"), "CHECKING");
        depositOn(a, "50.00", Instant.now().minus(2, ChronoUnit.DAYS));
        transferOn(a, b, "50.00", Instant.now().minus(1, ChronoUnit.DAYS), null);

        close(admin, a).andExpect(status().isNoContent());

        assertThat(statusOf(a)).isEqualTo("CLOSED");
    }

    @Test
    void closingTwiceBankAccountsAndBadInputsAreRefused() throws Exception {
        String admin = adminToken();
        Acc a = open(tokenFor("alice@example.com"), "CHECKING");
        close(admin, a).andExpect(status().isNoContent());

        close(admin, a).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ADMIN_INVALID_STATE"));
        send(post("/api/admin/accounts/00000000-0000-0000-0000-000000000001/close"), admin, REASON)
                .andExpect(status().isNotFound()); // the bank's own accounts are invisible here
        send(post("/api/admin/accounts/" + UUID.randomUUID() + "/close"), admin, REASON).andExpect(status().isNotFound());
        send(post("/api/admin/accounts/" + a.id() + "/close"), admin, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        send(post("/api/admin/accounts/" + a.id() + "/close"), admin, "{}").andExpect(status().isBadRequest());

        assertThat(auditRows()).isEqualTo(1); // only the one real close
    }

    @Test
    void aClosedAccountCannotReceiveOrSendMoneyButItsHistoryStaysReadable() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        Acc closed = open(alice, "CHECKING");
        Acc other = open(bob, "CHECKING");
        close(admin, closed).andExpect(status().isNoContent());

        send(post("/api/accounts/" + closed.id() + "/deposits").header("Idempotency-Key", "closed-dep-0001"), alice,
                "{\"amount\":5.00}").andExpect(status().isUnprocessableEntity());
        send(post("/api/transfers").header("Idempotency-Key", "closed-trf-0001"), bob,
                "{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":1.00}".formatted(other.id(), closed.iban()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECIPIENT_UNAVAILABLE"));
        api(alice, "/api/accounts/" + closed.id() + "/transactions").andExpect(status().isOk());
        api(alice, "/api/accounts").andExpect(jsonPath("$[0].status").value("CLOSED"));
    }

    @Test
    void closingFreesTheAccountSlotSoTheCustomerCanOpenANewOne() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc first = open(alice, "CHECKING");
        send(post("/api/accounts"), alice, "{\"type\":\"CHECKING\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED")); // one checking account at most

        close(admin, first).andExpect(status().isNoContent());

        send(post("/api/accounts"), alice, "{\"type\":\"CHECKING\"}").andExpect(status().isCreated());
    }

    @Test
    void closingCancelsOnlyTheStandingOrdersThatPayOutOfThatAccount() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc checking = open(alice, "CHECKING");
        Acc savings = open(alice, "SAVINGS");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        standingOrder(alice, checking, bob);
        standingOrder(alice, checking, savings);
        standingOrder(alice, savings, bob); // pays out of the OTHER account

        close(admin, checking).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("select status from recurring_payments where source_account_id = ?::uuid", String.class,
                checking.id())).containsExactly("CANCELLED", "CANCELLED");
        assertThat(jdbc.queryForObject("select status from recurring_payments where source_account_id = ?::uuid",
                String.class, savings.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from recurring_payments where status = 'CANCELLED' and next_run_date is not null",
                Integer.class)).isZero();
        send(get("/api/admin/audit"), admin, null).andExpect(jsonPath("$[0].detail").value("cancelledOrders=2"));
    }

    @Test
    void ifTheAuditRowCannotBeWrittenTheAccountStaysOpenWithItsOrders() throws Exception {
        String admin = adminToken();
        String alice = tokenFor("alice@example.com");
        Acc a = open(alice, "CHECKING");
        Acc bob = open(tokenFor("bob@example.com"), "CHECKING");
        standingOrder(alice, a, bob);
        doThrow(new IllegalStateException("audit store down")).when(adminRepository)
                .insertAction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> close(admin, a)).hasRootCauseMessage("audit store down");

        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from recurring_payments", String.class)).isEqualTo("ACTIVE");
    }


    @Test
    void aDepositRacingTheCloseNeverLeavesAClosedAccountHoldingMoney() throws Exception {
        String admin = adminToken();
        int rounds = 10;
        int closed = 0;
        int refused = 0;
        for (int round = 0; round < rounds; round++) {
            final int r = round;
            String owner = tokenFor("racer" + round + "@example.com"); // a fresh customer and an empty account per round
            Acc account = open(owner, "CHECKING");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> closing = pool.submit(() -> {
                start.await();
                return close(admin, account).andReturn().getResponse().getStatus();
            });
            Future<Integer> depositing = pool.submit(() -> {
                start.await();
                return send(post("/api/accounts/" + account.id() + "/deposits").header("Idempotency-Key", "race-dep-%04d-xx".formatted(r)), owner, "{\"amount\":10.00}").andReturn().getResponse().getStatus();
            });
            start.countDown();
            int closeStatus = closing.get(60, TimeUnit.SECONDS);
            int depositStatus = depositing.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            BigDecimal balance = jdbc.queryForObject("select balance from accounts where id = ?::uuid", BigDecimal.class, account.id());
            // the two outcomes that are allowed: the deposit landed first (so the close was refused and the money is
            // there), or the close landed first (so the deposit was refused and the account is empty and closed)
            if (statusOf(account).equals("CLOSED")) {
                assertThat(balance).as("round %d: a closed account must be empty", round).isEqualByComparingTo("0.00");
                assertThat(depositStatus).as("round %d: its deposit must have been refused", round).isEqualTo(422);
                closed++;
            } else {
                assertThat(closeStatus).as("round %d: the close must have been refused", round).isEqualTo(422);
                assertThat(balance).as("round %d: the money that arrived is still there", round).isEqualByComparingTo("10.00");
                refused++;
            }
        }

        assertThat(closed + refused).isEqualTo(rounds);
        assertBooksBalance();
    }
}
