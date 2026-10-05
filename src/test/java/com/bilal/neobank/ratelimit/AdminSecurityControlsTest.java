package com.bilal.neobank.ratelimit;

import com.bilal.neobank.account.IbanGenerator;
import com.bilal.neobank.admin.AdminRepository;
import com.bilal.neobank.ledger.Fingerprint;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What an administrator sees and can do about login lockouts and IBAN-probing blocks. */
class AdminSecurityControlsTest extends RateLimitTestBase {

    private static final String REASON = "{\"reason\":\"Customer verified by phone\"}";

    @MockitoSpyBean
    AdminRepository adminRepository;

    private String adminBearer() throws Exception {
        assertThat(statusOf(register("ada@example.com", "192.168.60.1"))).isEqualTo(201);
        jdbc.update("update users set role = 'ADMIN' where email = 'ada@example.com'");
        MvcResult result = login("ada@example.com", PASSWORD, "192.168.60.2");
        assertThat(statusOf(result)).isEqualTo(200);
        return "Bearer " + JsonPath.<String>read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions snapshot(String admin) throws Exception {
        return send(get("/api/admin/security"), admin, null);
    }

    private ResultActions unlock(String admin, String email) throws Exception {
        return send(post("/api/admin/security/login-lockouts/unlock"), admin,
                "{\"email\":\"%s\",\"reason\":\"Customer verified by phone\"}".formatted(email));
    }

    private void failLogins(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(statusOf(login(email, WRONG, IP_A))).isEqualTo(401);
        }
    }

    private void lockOut(String email) throws Exception {
        failLogins(email, 5);
        assertThat(statusOf(login(email, PASSWORD, IP_A))).isEqualTo(429);
    }

    private static String missingIban(int n) {
        return IbanGenerator.generate(950_000L + n);
    }

    /** Makes the user miss ten times: now blocked from lookups and transfers. */
    private void blockForProbing(String customerBearer) throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(statusOf(lookup(customerBearer, missingIban(i)))).isEqualTo(404);
        }
        assertThat(statusOf(lookup(customerBearer, missingIban(99)))).isEqualTo(429);
    }

    private String userId(String email) {
        return jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
    }

    // ---- access -----------------------------------------------------------------------------------------------

    @Test
    void everyNewEndpointRejectsAnonymousCallersAndCustomers() throws Exception {
        String customer = bearerFor("alice@example.com");
        String id = userId("alice@example.com");
        String unlockBody = "{\"email\":\"alice@example.com\",\"reason\":\"x\"}";

        send(get("/api/admin/security"), null, null).andExpect(status().isUnauthorized());
        send(get("/api/admin/security"), customer, null).andExpect(status().isForbidden());
        send(post("/api/admin/security/login-lockouts/unlock"), null, unlockBody).andExpect(status().isUnauthorized());
        send(post("/api/admin/security/login-lockouts/unlock"), customer, unlockBody).andExpect(status().isForbidden());
        send(post("/api/admin/security/probing-blocks/" + id + "/clear"), null, REASON).andExpect(status().isUnauthorized());
        send(post("/api/admin/security/probing-blocks/" + id + "/clear"), customer, REASON).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    // ---- login lockouts ---------------------------------------------------------------------------------------

    @Test
    void theSnapshotListsLockedCustomersCountsOtherAddressesAndNeverExposesHashes() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        assertThat(statusOf(register("bob@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");
        lockOut("ghost@example.com");           // no such customer
        failLogins("bob@example.com", 3);       // failing, but not locked

        MvcResult result = snapshot(admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedLogins.length()").value(1))
                .andExpect(jsonPath("$.lockedLogins[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.lockedLogins[0].failures").value(5))
                .andExpect(jsonPath("$.lockedLogins[0].lockedUntil").value("2026-10-04T12:15:00Z"))
                .andExpect(jsonPath("$.otherLockedAddresses").value(1))
                .andExpect(jsonPath("$.probingBlocks.length()").value(0)).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(Fingerprint.sha256("alice@example.com")).doesNotContain("ghost")
                .doesNotContain("hash");
    }

    @Test
    void unlockingLetsTheCustomerLogInAgainAndRecordsWhoWhyAndWhich() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");

        unlock(admin, "alice@example.com").andExpect(status().isNoContent());

        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(200);
        snapshot(admin).andExpect(jsonPath("$.lockedLogins.length()").value(0));
        send(get("/api/admin/audit"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("UNLOCK_LOGIN"))
                .andExpect(jsonPath("$[0].reason").value("Customer verified by phone"))
                .andExpect(jsonPath("$[0].detail").value("email=alice@example.com"))
                .andExpect(jsonPath("$[0].adminEmail").value("ada@example.com"));
    }

    @Test
    void theCustomerStartsFromZeroAfterAnUnlockSoItIsNotLockedAgainByTheNextTypo() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");
        unlock(admin, "alice@example.com").andExpect(status().isNoContent());

        failLogins("alice@example.com", 4); // four fresh wrong passwords: still below five

        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void theEmailIsMatchedTheSameWayLoginMatchesIt() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");

        unlock(admin, "  ALICE@Example.COM ").andExpect(status().isNoContent());

        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(200);
    }

    @Test
    void unlockingSomeoneWhoIsNotLockedIsRefusedAndChangesNothing() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("bob@example.com", IP_A))).isEqualTo(201);
        failLogins("bob@example.com", 3);

        unlock(admin, "bob@example.com").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_LOCKED"));
        unlock(admin, "nobody@example.com").andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select failures from login_failures where email_hash = ?", Integer.class,
                Fingerprint.sha256("bob@example.com"))).isEqualTo(3); // the failure count was left alone
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    @Test
    void anExpiredLockIsNotListedAndNeedsNoUnlock() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");
        after(Duration.ofMinutes(16));

        snapshot(admin).andExpect(jsonPath("$.lockedLogins.length()").value(0)).andExpect(jsonPath("$.otherLockedAddresses").value(0));
        unlock(admin, "alice@example.com").andExpect(status().isNotFound());
    }

    @Test
    void unlockingOneCustomerLeavesEveryoneElseLocked() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        assertThat(statusOf(register("bob@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");
        lockOut("bob@example.com");

        unlock(admin, "alice@example.com").andExpect(status().isNoContent());

        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(200);
        assertThat(statusOf(login("bob@example.com", PASSWORD, IP_A))).isEqualTo(429);
        snapshot(admin).andExpect(jsonPath("$.lockedLogins[0].email").value("bob@example.com"));
    }

    @Test
    void aReasonIsRequiredAndTheEmailMustBeGiven() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");

        send(post("/api/admin/security/login-lockouts/unlock"), admin, "{\"email\":\"alice@example.com\",\"reason\":\"  \"}")
                .andExpect(status().isBadRequest());
        send(post("/api/admin/security/login-lockouts/unlock"), admin, "{\"email\":\"alice@example.com\"}")
                .andExpect(status().isBadRequest());
        send(post("/api/admin/security/login-lockouts/unlock"), admin, "{\"reason\":\"x\"}").andExpect(status().isBadRequest());

        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(429); // still locked
    }

    @Test
    void ifTheAuditRowCannotBeWrittenTheUnlockIsRolledBack() throws Exception {
        String admin = adminBearer();
        assertThat(statusOf(register("alice@example.com", IP_A))).isEqualTo(201);
        lockOut("alice@example.com");
        doThrow(new IllegalStateException("audit store down")).when(adminRepository)
                .insertAction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> unlock(admin, "alice@example.com")).hasRootCauseMessage("audit store down");

        assertThat(statusOf(login("alice@example.com", PASSWORD, IP_A))).isEqualTo(429); // no unaudited unlock exists
    }

    // ---- probing blocks ---------------------------------------------------------------------------------------

    @Test
    void aProbingBlockIsListedUntilTheMissesHaveSlidOutBelowTheLimit() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        blockForProbing(alice);

        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(1))
                .andExpect(jsonPath("$.probingBlocks[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.probingBlocks[0].userId").value(userId("alice@example.com")))
                .andExpect(jsonPath("$.probingBlocks[0].misses").value(10))
                .andExpect(jsonPath("$.probingBlocks[0].blockedUntil").value("2026-10-04T12:10:01Z")); // 600 s + the margin

        after(Duration.ofMinutes(10)); // the misses are only now beginning to slide out: still blocked
        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(1));
        after(Duration.ofSeconds(1));
        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(0));
    }

    @Test
    void clearingTheBlockLetsTheUserLookUpRecipientsAgainAndIsAudited() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        String bobIban = JsonPath.read(openAccount(bearerFor("bob@example.com")), "$.iban");
        blockForProbing(alice);
        assertThat(statusOf(lookup(alice, bobIban))).isEqualTo(429);

        send(post("/api/admin/security/probing-blocks/" + userId("alice@example.com") + "/clear"), admin, REASON)
                .andExpect(status().isNoContent());

        assertThat(statusOf(lookup(alice, bobIban))).isEqualTo(200);
        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(0));
        send(get("/api/admin/audit"), admin, null).andExpect(jsonPath("$[0].action").value("CLEAR_PROBING_BLOCK"))
                .andExpect(jsonPath("$[0].reason").value("Customer verified by phone"))
                .andExpect(jsonPath("$[0].detail").value("user=alice@example.com"));
    }

    @Test
    void clearingAlsoForgetsMissesFromThePreviousWindowBecauseASlidingWindowStillCountsThem() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        String bobIban = JsonPath.read(openAccount(bearerFor("bob@example.com")), "$.iban");
        clock.set(START.plusSeconds(599)); // 12:09:59: the last second of the 10-minute window
        blockForProbing(alice);
        clock.set(START.plusSeconds(600)); // 12:10:00: a new window; the ten misses sit in the previous one, still at full weight
        assertThat(statusOf(lookup(alice, bobIban))).isEqualTo(429);
        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(1));

        send(post("/api/admin/security/probing-blocks/" + userId("alice@example.com") + "/clear"), admin, REASON)
                .andExpect(status().isNoContent());

        assertThat(statusOf(lookup(alice, bobIban))).isEqualTo(200); // really lifted, not just in the current window
        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(0));
    }

    @Test
    void clearingResetsTheLookupSpeedLimitToo() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        blockForProbing(alice); // 11 lookups so far this minute
        String id = userId("alice@example.com");
        assertThat(jdbc.queryForObject("select count(*) from rate_limits where bucket_key = ?", Integer.class, "lookup:" + id))
                .isEqualTo(1);

        send(post("/api/admin/security/probing-blocks/" + id + "/clear"), admin, REASON).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from rate_limits where bucket_key in (?, ?)", Integer.class,
                "lookup:" + id, "probe:" + id)).isZero();
    }

    @Test
    void clearingSomeoneWhoIsNotBlockedIsRefusedAndTheirCountIsKept() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        for (int i = 0; i < 4; i++) {
            assertThat(statusOf(lookup(alice, missingIban(i)))).isEqualTo(404); // 4 misses: below the limit of 10
        }
        String id = userId("alice@example.com");

        send(post("/api/admin/security/probing-blocks/" + id + "/clear"), admin, REASON).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_BLOCKED"));
        send(post("/api/admin/security/probing-blocks/" + UUID.randomUUID() + "/clear"), admin, REASON)
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select hits from rate_limits where bucket_key = ?", Integer.class, "probe:" + id))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    @Test
    void clearingOneUsersBlockLeavesAnotherUsersBlockInPlace() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        String bob = bearerFor("bob@example.com");
        blockForProbing(alice);
        blockForProbing(bob);

        send(post("/api/admin/security/probing-blocks/" + userId("alice@example.com") + "/clear"), admin, REASON)
                .andExpect(status().isNoContent());

        snapshot(admin).andExpect(jsonPath("$.probingBlocks.length()").value(1))
                .andExpect(jsonPath("$.probingBlocks[0].email").value("bob@example.com"));
    }

    @Test
    void ifTheAuditRowCannotBeWrittenTheBlockIsNotCleared() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        blockForProbing(alice);
        doThrow(new IllegalStateException("audit store down")).when(adminRepository)
                .insertAction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> send(post("/api/admin/security/probing-blocks/" + userId("alice@example.com") + "/clear"),
                admin, REASON)).hasRootCauseMessage("audit store down");

        assertThat(statusOf(lookup(alice, missingIban(77)))).isEqualTo(429); // still blocked
    }

    // ---- overview and audit -----------------------------------------------------------------------------------

    @Test
    void theOverviewCountsCurrentLocksAndBlocks() throws Exception {
        String admin = adminBearer();
        String alice = bearerFor("alice@example.com");
        assertThat(statusOf(register("bob@example.com", IP_A))).isEqualTo(201);
        lockOut("bob@example.com");
        lockOut("ghost@example.com"); // not a customer: not counted as a locked login
        blockForProbing(alice);

        send(get("/api/admin/overview"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.security.lockedLogins").value(1))
                .andExpect(jsonPath("$.security.probingBlocks").value(1));
    }
}
