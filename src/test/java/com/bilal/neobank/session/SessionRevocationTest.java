package com.bilal.neobank.session;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.security.JwtService;
import com.bilal.neobank.user.UserRepository;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ending a session must kill its access tokens at once, not after their 15 minutes. Every way a session can end is
 * checked: removal from the list, logout, logout everywhere, password change, and detected token theft.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class SessionRevocationTest {

    private static final String PASSWORD = "S3curepass12";
    private static final Instant START = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtEncoder jwtEncoder;

    private record Login(String accessToken, String refreshToken) {
        String bearer() {
            return "Bearer " + accessToken;
        }
    }

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

    private Login login(String email) throws Exception {
        return asLogin(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn());
    }

    private static Login asLogin(MvcResult result) throws Exception {
        return new Login(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"),
                result.getResponse().getCookie("neobank_refresh").getValue());
    }

    private Login refresh(Login login) throws Exception {
        return asLogin(mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", login.refreshToken())).header("Accept", "application/json"))
                .andExpect(status().isOk()).andReturn());
    }

    private int refreshStatus(Login login) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", login.refreshToken()))).andReturn().getResponse().getStatus();
    }

    private ResultActions me(String bearer) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", bearer));
    }

    private static String sidOf(Login login) {
        String payload = new String(Base64.getUrlDecoder().decode(login.accessToken().split("\\.")[1]));
        return JsonPath.read(payload, "$.sid");
    }

    private String tokenWithSid(String sid) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(users.findByEmail("alice@example.com").orElseThrow().getId().toString())
                .claim("email", "alice@example.com").claim("roles", List.of("CUSTOMER")).claim("sid", sid)
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(15))).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    // ---- every way a session can end -------------------------------------------------------------------------

    @Test
    void aDeviceRemovedFromTheListStopsWorkingAtOnceWhileTheOthersKeepWorking() throws Exception {
        Login desktop = login("alice@example.com");
        Login phone = login("alice@example.com");
        me(phone.bearer()).andExpect(status().isOk());

        mockMvc.perform(delete("/api/sessions/" + sidOf(phone)).header("Authorization", desktop.bearer()))
                .andExpect(status().isNoContent());

        me(phone.bearer()).andExpect(status().isUnauthorized()); // not "in 15 minutes": now
        me(desktop.bearer()).andExpect(status().isOk());
    }

    @Test
    void loggingOutKillsTheAccessTokenImmediately() throws Exception {
        Login desktop = login("alice@example.com");
        me(desktop.bearer()).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", desktop.refreshToken()))).andExpect(status().isNoContent());

        me(desktop.bearer()).andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutEverywhereKillsEveryAccessTokenOfThatUserIncludingTheCallers() throws Exception {
        register("bob@example.com");
        Login desktop = login("alice@example.com");
        Login phone = login("alice@example.com");
        Login bob = login("bob@example.com");

        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", desktop.bearer()))
                .andExpect(status().isNoContent());

        me(desktop.bearer()).andExpect(status().isUnauthorized());
        me(phone.bearer()).andExpect(status().isUnauthorized());
        me(bob.bearer()).andExpect(status().isOk()); // another customer is never touched
    }

    @Test
    void logOutAllOtherDevicesKillsTheirAccessTokensAtOnceAndKeepsThisOne() throws Exception {
        Login desktop = login("alice@example.com");
        Login phone = login("alice@example.com");
        Login tablet = login("alice@example.com");

        mockMvc.perform(post("/api/sessions/revoke-others").header("Authorization", desktop.bearer()))
                .andExpect(status().isNoContent());

        me(phone.bearer()).andExpect(status().isUnauthorized());
        me(tablet.bearer()).andExpect(status().isUnauthorized());
        me(desktop.bearer()).andExpect(status().isOk());
    }

    @Test
    void aPasswordChangeKillsTheOtherDevicesAccessTokensAtOnce() throws Exception {
        Login desktop = login("alice@example.com");
        Login phone = login("alice@example.com");

        mockMvc.perform(post("/api/me/password").header("Authorization", desktop.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"Brand-new-pass-77\"}".formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        me(phone.bearer()).andExpect(status().isUnauthorized()); // a thief holding the old session is out immediately
        me(desktop.bearer()).andExpect(status().isOk());
    }

    @Test
    void replayingAStolenRefreshTokenKillsTheWholeLoginsAccessTokensToo() throws Exception {
        Login first = login("alice@example.com");
        Login second = refresh(first);
        clock.set(clock.instant().plusSeconds(11)); // outside the grace window

        assertThat(refreshStatus(first)).isEqualTo(401); // the old token is shown again: theft or replay

        me(second.bearer()).andExpect(status().isUnauthorized()); // the legitimate holder is signed out at once as well
        me(first.bearer()).andExpect(status().isUnauthorized());
    }

    // ---- what must keep working -------------------------------------------------------------------------------

    @Test
    void aNormalRefreshLeavesTheEarlierAccessTokensOfALiveSessionWorking() throws Exception {
        Login first = login("alice@example.com");
        Login second = refresh(first);

        me(first.bearer()).andExpect(status().isOk());  // same session, still alive
        me(second.bearer()).andExpect(status().isOk());
        assertThat(sidOf(first)).isEqualTo(sidOf(second));
    }

    @Test
    void anAccessTokenWithoutASessionIdKeepsWorkingUntilItExpires() throws Exception {
        login("alice@example.com");
        String legacy = "Bearer " + jwtService.issue(users.findByEmail("alice@example.com").orElseThrow(), null).accessToken();

        me(legacy).andExpect(status().isOk());
    }

    @Test
    void otherCustomersSessionsAreUnaffectedByAnEndedSession() throws Exception {
        register("bob@example.com");
        Login alice = login("alice@example.com");
        Login bob = login("bob@example.com");

        mockMvc.perform(delete("/api/sessions/" + sidOf(alice)).header("Authorization", alice.bearer()))
                .andExpect(status().isNoContent());

        me(alice.bearer()).andExpect(status().isUnauthorized());
        me(bob.bearer()).andExpect(status().isOk());
    }

    // ---- tokens that name a session that is not there ---------------------------------------------------------

    @Test
    void aTokenNamingASessionThatDoesNotExistIsRefused() throws Exception {
        login("alice@example.com");
        me(tokenWithSid(UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithAMalformedSessionIdIsRefusedInsteadOfCrashing() throws Exception {
        login("alice@example.com");
        me(tokenWithSid("not-a-uuid")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRefusalIsTheStandardUnauthenticatedProblem() throws Exception {
        Login desktop = login("alice@example.com");
        mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", desktop.refreshToken()))).andExpect(status().isNoContent());

        me(desktop.bearer()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void theAdminApiIsProtectedByTheSameCheck() throws Exception {
        jdbc.update("update users set role = 'ADMIN' where email = 'alice@example.com'");
        Login admin = login("alice@example.com");
        mockMvc.perform(get("/api/admin/overview").header("Authorization", admin.bearer())).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", admin.refreshToken()))).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/overview").header("Authorization", admin.bearer())).andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionThatEndedStaysEndedForAllItsEarlierTokens() throws Exception {
        Login first = login("alice@example.com");
        Login second = refresh(first);
        Login third = refresh(second);

        mockMvc.perform(delete("/api/sessions/" + sidOf(third)).header("Authorization", third.bearer()))
                .andExpect(status().isNoContent());

        for (Login token : List.of(first, second, third)) {
            me(token.bearer()).andExpect(status().isUnauthorized());
        }
    }
}
