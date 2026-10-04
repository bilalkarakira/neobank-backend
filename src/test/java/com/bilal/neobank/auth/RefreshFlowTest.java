package com.bilal.neobank.auth;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.ledger.Fingerprint;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Refresh tokens and logout through the real HTTP layer, with a movable clock for expiry and the reuse window. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class RefreshFlowTest {

    private static final String PASSWORD = "S3curepass12";
    private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RefreshTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();
        clock.set(START);
        register("alice@example.com");
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isCreated());
    }

    /** What a browser holds after a login or refresh. */
    private record Session(String accessToken, String refreshToken, String setCookieHeader) {
        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    private Session login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn();
        return session(result);
    }

    private static Session session(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Cookie cookie = result.getResponse().getCookie(RefreshCookies.NAME);
        return new Session(JsonPath.read(body, "$.accessToken"), cookie == null ? null : cookie.getValue(),
                result.getResponse().getHeader("Set-Cookie"));
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        var request = post("/api/auth/refresh").header("X-Requested-With", "neobank");
        if (refreshToken != null) {
            request.cookie(new Cookie(RefreshCookies.NAME, refreshToken));
        }
        return mockMvc.perform(request).andReturn();
    }

    private Session refreshOk(String refreshToken) throws Exception {
        MvcResult result = refresh(refreshToken);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return session(result);
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        MvcResult result = refresh(refreshToken);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("INVALID_REFRESH_TOKEN");
    }

    private void logout(String refreshToken) throws Exception {
        var request = post("/api/auth/logout").header("X-Requested-With", "neobank");
        if (refreshToken != null) {
            request.cookie(new Cookie(RefreshCookies.NAME, refreshToken));
        }
        mockMvc.perform(request).andExpect(status().isNoContent());
    }

    private void after(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    // ---- login hands out a safe cookie ------------------------------------------------------------------------

    @Test
    void loginSetsAnHttpOnlyStrictCookieScopedToTheAuthPathAndStoresOnlyAHash() throws Exception {
        Session session = login("alice@example.com");

        assertThat(session.setCookieHeader()).contains("neobank_refresh=" + session.refreshToken())
                .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth")
                .contains("Max-Age=604800") // 7 days
                .doesNotContain("Secure"); // off by default so http://localhost works; production sets it
        assertThat(session.refreshToken()).hasSizeGreaterThanOrEqualTo(40); // 256 random bits, base64url
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                Fingerprint.sha256(session.refreshToken()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                session.refreshToken())).isZero(); // the raw value is never stored
    }

    @Test
    void theAccessTokenStaysInTheBodyAndTheCookieIsNotReadableFromIt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"alice@example.com","password":"%s"}
                """.formatted(PASSWORD))).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("neobank_refresh");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(
                result.getResponse().getCookie(RefreshCookies.NAME).getValue());
    }

    @Test
    void aFailedLoginSetsNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"alice@example.com","password":"wrong-password-1"}
                """)).andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isZero();
    }

    // ---- refreshing -------------------------------------------------------------------------------------------

    @Test
    void refreshGivesAWorkingAccessTokenAndAReplacementCookie() throws Exception {
        Session first = login("alice@example.com");

        Session second = refreshOk(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.setCookieHeader()).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
        mockMvc.perform(get("/api/me").header("Authorization", second.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where rotated_at is not null", Integer.class))
                .isEqualTo(1); // the used token is retired, not deleted
    }

    @Test
    void aRefreshChainKeepsWorkingAsLongAsEachNewestTokenIsUsed() throws Exception {
        Session session = login("alice@example.com");
        for (int i = 0; i < 5; i++) {
            after(Duration.ofMinutes(15));
            session = refreshOk(session.refreshToken());
        }
        assertThat(jdbc.queryForObject("select count(distinct family_id) from refresh_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(6);
    }

    @Test
    void refreshWithoutTheCustomHeaderIsRefusedAndDoesNotConsumeTheToken() throws Exception {
        Session session = login("alice@example.com");

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, session.refreshToken())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_HEADER_REQUIRED"));
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "something-else")
                .cookie(new Cookie(RefreshCookies.NAME, session.refreshToken()))).andExpect(status().isForbidden());

        refreshOk(session.refreshToken()); // still valid
    }

    @Test
    void aMissingOrGarbageCookieIsRefusedAndTheBrowserIsToldToDropIt() throws Exception {
        MvcResult none = refresh(null);
        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        assertThat(none.getResponse().getHeader("Set-Cookie")).contains("neobank_refresh=").contains("Max-Age=0");

        assertRefreshRejected("not-a-real-token");
        assertRefreshRejected("");
    }

    @Test
    void theRoleIsReadFreshAtEveryRefreshSoAChangeAppliesWithoutANewLogin() throws Exception {
        Session session = login("alice@example.com");
        mockMvc.perform(get("/api/admin/overview").header("Authorization", session.bearer()))
                .andExpect(status().isForbidden());

        jdbc.update("update users set role = 'ADMIN' where email = 'alice@example.com'");
        Session refreshed = refreshOk(session.refreshToken());

        mockMvc.perform(get("/api/admin/overview").header("Authorization", refreshed.bearer()))
                .andExpect(status().isOk());
    }

    // ---- reuse detection --------------------------------------------------------------------------------------

    @Test
    void replayingAnOldTokenAfterTheGraceWindowEndsTheWholeLoginIncludingTheNewestToken() throws Exception {
        Session first = login("alice@example.com");
        Session second = refreshOk(first.refreshToken());
        after(Duration.ofSeconds(11)); // outside the 10 s grace window

        assertRefreshRejected(first.refreshToken()); // an old token is shown again: theft or a replay

        assertRefreshRejected(second.refreshToken()); // so the legitimate newest token is dead too
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null", Integer.class))
                .isZero();
    }

    @Test
    void presentingTheOldTokenInsideTheGraceWindowIsRefusedButTheLoginSurvives() throws Exception {
        Session first = login("alice@example.com");
        Session second = refreshOk(first.refreshToken());
        after(Duration.ofSeconds(5)); // a second tab that raced the first one

        assertRefreshRejected(first.refreshToken());

        refreshOk(second.refreshToken()); // the winner is untouched
    }

    @Test
    void oneLoginBeingRevokedDoesNotAffectAnotherLoginOfTheSameUser() throws Exception {
        Session phone = login("alice@example.com");
        Session laptop = login("alice@example.com");
        Session phoneNext = refreshOk(phone.refreshToken());
        after(Duration.ofSeconds(30));
        assertRefreshRejected(phone.refreshToken()); // replay on the phone's login: that family is revoked

        assertRefreshRejected(phoneNext.refreshToken());
        refreshOk(laptop.refreshToken()); // the laptop's login is a different family
    }

    @Test
    void manyTabsRefreshingAtOnceProduceExactlyOneWinnerAndDoNotEndTheLogin() throws Exception {
        Session session = login("alice@example.com");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return refresh(session.refreshToken());
            }));
        }
        start.countDown();
        int ok = 0;
        Session winner = null;
        for (Future<MvcResult> result : results) {
            MvcResult r = result.get(60, TimeUnit.SECONDS);
            if (r.getResponse().getStatus() == 200) {
                ok++;
                winner = session(r);
            } else {
                assertThat(r.getResponse().getStatus()).isEqualTo(401);
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(2); // not 1 + 6
        refreshOk(winner.refreshToken()); // the login is still alive
    }

    // ---- lifetime ---------------------------------------------------------------------------------------------

    @Test
    void aTokenNotUsedForSevenDaysExpires() throws Exception {
        Session session = login("alice@example.com");
        after(Duration.ofDays(7).plusSeconds(1));
        assertRefreshRejected(session.refreshToken());
    }

    @Test
    void usingTheTokenKeepsTheLoginAliveButNeverPastThirtyDays() throws Exception {
        Session session = login("alice@example.com");
        for (int day = 6; day <= 24; day += 6) { // days 6, 12, 18, 24: each within 7 days of the last
            after(Duration.ofDays(6));
            session = refreshOk(session.refreshToken());
        }
        after(Duration.ofDays(5)); // day 29
        session = refreshOk(session.refreshToken());

        // the newest token would normally last 7 more days, but is cut off at the 30-day limit of the login
        assertThat(jdbc.queryForObject("select family_expires_at - expires_at from refresh_tokens "
                + "order by created_at desc limit 1", String.class)).isEqualTo("00:00:00");
        after(Duration.ofDays(1).plusSeconds(1)); // day 30 and a second
        assertRefreshRejected(session.refreshToken());
    }

    @Test
    void purgeRemovesOnlyTokensThatExpiredMoreThanADayAgo() throws Exception {
        Session old = login("alice@example.com");
        after(Duration.ofDays(8)); // old expired a day ago (7 days + 1 day)
        Session fresh = login("alice@example.com");
        after(Duration.ofDays(7).plusHours(2)); // fresh expired two hours ago: still kept

        assertThat(service.purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(1);
        assertThat(old.refreshToken()).isNotNull();
        assertThat(fresh.refreshToken()).isNotNull();
    }

    // ---- logout -----------------------------------------------------------------------------------------------

    @Test
    void logoutEndsThisLoginAndClearsTheCookie() throws Exception {
        Session first = login("alice@example.com");
        Session second = refreshOk(first.refreshToken());

        MvcResult result = mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", "neobank")
                .cookie(new Cookie(RefreshCookies.NAME, second.refreshToken()))).andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("neobank_refresh=").contains("Max-Age=0");
        assertRefreshRejected(second.refreshToken());
        assertRefreshRejected(first.refreshToken()); // the whole login is gone, not just the newest token
    }

    @Test
    void logoutLeavesOtherDevicesAlone() throws Exception {
        Session phone = login("alice@example.com");
        Session laptop = login("alice@example.com");

        logout(phone.refreshToken());

        assertRefreshRejected(phone.refreshToken());
        refreshOk(laptop.refreshToken());
    }

    @Test
    void logoutAlwaysSucceedsEvenWithoutAValidCookie() throws Exception {
        logout(null);
        logout("garbage");
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden()); // but still needs the header
    }

    @Test
    void logoutEverywhereEndsEveryLoginOfThatUserOnly() throws Exception {
        register("bob@example.com");
        Session phone = login("alice@example.com");
        Session laptop = login("alice@example.com");
        Session bob = login("bob@example.com");

        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", phone.bearer()))
                .andExpect(status().isNoContent());

        assertRefreshRejected(phone.refreshToken());
        assertRefreshRejected(laptop.refreshToken());
        refreshOk(bob.refreshToken());
    }
}
