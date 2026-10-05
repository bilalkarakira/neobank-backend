package com.bilal.neobank.session;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.auth.RefreshTokenService;
import com.bilal.neobank.security.JwtService;
import com.bilal.neobank.user.User;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Where you are logged in": listing, recognising this device, and ending sessions. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class SessionsApiTest {

    private static final String PASSWORD = "S3curepass12";
    private static final Instant START = Instant.parse("2026-10-05T12:00:00Z");
    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    static final String PHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

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
    RefreshTokenService refreshTokens;

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

    /** What one browser holds after a login or refresh. */
    record Login(String accessToken, String refreshToken) {
        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    Login login(String email, String userAgent, String ip) throws Exception {
        var request = post("/api/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD));
        if (userAgent != null) {
            request.header("User-Agent", userAgent);
        }
        return asLogin(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    Login refresh(Login login) throws Exception {
        return asLogin(mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", login.refreshToken()))).andExpect(status().isOk()).andReturn());
    }

    int refreshStatus(Login login) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", login.refreshToken()))).andReturn().getResponse().getStatus();
    }

    private static Login asLogin(MvcResult result) throws Exception {
        return new Login(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"),
                result.getResponse().getCookie("neobank_refresh").getValue());
    }

    /** The "sid" claim of an access token, read straight from its payload. */
    static String sidOf(Login login) {
        String payload = new String(Base64.getUrlDecoder().decode(login.accessToken().split("\\.")[1]));
        return JsonPath.read(payload, "$.sid");
    }

    private void after(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    // ---- listing ----------------------------------------------------------------------------------------------

    @Test
    void listsEachLoginWithItsDeviceAndAddressAndMarksThisDevice() throws Exception {
        Login desktop = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        after(Duration.ofMinutes(5));
        Login phone = login("alice@example.com", PHONE_UA, "198.51.100.7");

        mockMvc.perform(get("/api/sessions").header("Authorization", phone.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].device").value("Safari on iOS")) // newest first
                .andExpect(jsonPath("$[0].ip").value("198.51.100.7"))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].id").value(sidOf(phone)))
                .andExpect(jsonPath("$[1].device").value("Chrome on Windows"))
                .andExpect(jsonPath("$[1].ip").value("203.0.113.5"))
                .andExpect(jsonPath("$[1].current").value(false))
                .andExpect(jsonPath("$[0].startedAt").value("2026-10-05T12:05:00Z"))
                .andExpect(jsonPath("$[0].expiresAt").value("2026-10-12T12:05:00Z"));

        // the same list seen from the other device flips "this device"
        mockMvc.perform(get("/api/sessions").header("Authorization", desktop.bearer()))
                .andExpect(jsonPath("$[0].current").value(false)).andExpect(jsonPath("$[1].current").value(true));
    }

    @Test
    void aRefreshKeepsItsSessionIdAndDeviceAndOnlyMovesTheLastActiveTime() throws Exception {
        Login first = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        after(Duration.ofHours(2));
        Login second = refresh(first);
        after(Duration.ofHours(2));
        Login third = refresh(second);

        assertThat(sidOf(second)).isEqualTo(sidOf(first)).isEqualTo(sidOf(third));
        mockMvc.perform(get("/api/sessions").header("Authorization", third.bearer()))
                .andExpect(jsonPath("$.length()").value(1)) // three tokens, still one login
                .andExpect(jsonPath("$[0].device").value("Chrome on Windows"))
                .andExpect(jsonPath("$[0].ip").value("203.0.113.5"))
                .andExpect(jsonPath("$[0].startedAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$[0].lastActiveAt").value("2026-10-05T16:00:00Z"))
                .andExpect(jsonPath("$[0].expiresAt").value("2026-10-12T16:00:00Z"))
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    void theDeviceAddressAndStartTimeSurviveThePurgeOfTheLoginsOlderTokens() throws Exception {
        Login first = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        after(Duration.ofDays(6));
        Login second = refresh(first);        // the first token (valid 7 days) expires on day 7
        after(Duration.ofDays(3));            // day 9: the daily purge removes tokens that expired more than a day ago

        assertThat(refreshTokens.purgeExpired()).isEqualTo(1);

        // only the newest token is left, yet the login still knows where and when it began
        mockMvc.perform(get("/api/sessions").header("Authorization", second.bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].device").value("Chrome on Windows"))
                .andExpect(jsonPath("$[0].ip").value("203.0.113.5"))
                .andExpect(jsonPath("$[0].startedAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$[0].lastActiveAt").value("2026-10-11T12:00:00Z"));
    }

    @Test
    void aMissingUserAgentIsLabelledUnknownRatherThanBreakingTheList() throws Exception {
        Login quiet = login("alice@example.com", null, "203.0.113.5");
        mockMvc.perform(get("/api/sessions").header("Authorization", quiet.bearer()))
                .andExpect(jsonPath("$[0].device").value("Unknown device"));
    }

    @Test
    void expiredAndEndedSessionsAreNotListed() throws Exception {
        login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        after(Duration.ofDays(8)); // the first login has expired
        Login ended = login("alice@example.com", PHONE_UA, "198.51.100.7");
        Login current = login("alice@example.com", DESKTOP_UA, "203.0.113.9");
        mockMvc.perform(delete("/api/sessions/" + sidOf(ended)).header("Authorization", current.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/sessions").header("Authorization", current.bearer()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(sidOf(current)));
    }

    @Test
    void youOnlySeeYourOwnSessions() throws Exception {
        register("bob@example.com");
        login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        Login bob = login("bob@example.com", PHONE_UA, "198.51.100.7");

        mockMvc.perform(get("/api/sessions").header("Authorization", bob.bearer()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].device").value("Safari on iOS"));
    }

    @Test
    void everySessionEndpointNeedsASignedInUser() throws Exception {
        mockMvc.perform(get("/api/sessions")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/sessions/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sessions/revoke-others")).andExpect(status().isUnauthorized());
    }

    // ---- ending sessions --------------------------------------------------------------------------------------

    @Test
    void endingAnotherSessionStopsItRefreshingAndLeavesThisOneAlone() throws Exception {
        Login desktop = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        Login phone = login("alice@example.com", PHONE_UA, "198.51.100.7");

        mockMvc.perform(delete("/api/sessions/" + sidOf(phone)).header("Authorization", desktop.bearer()))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(phone)).isEqualTo(401);
        assertThat(refreshStatus(desktop)).isEqualTo(200);
        mockMvc.perform(get("/api/sessions").header("Authorization", desktop.bearer()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void endingASessionEndsTheWholeLoginNotJustItsNewestToken() throws Exception {
        Login first = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        Login second = refresh(first);
        Login other = login("alice@example.com", PHONE_UA, "198.51.100.7");

        mockMvc.perform(delete("/api/sessions/" + sidOf(second)).header("Authorization", other.bearer()))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(second)).isEqualTo(401);
        assertThat(refreshStatus(first)).isEqualTo(401);
    }

    @Test
    void endingTheCurrentSessionIsALogout() throws Exception {
        Login desktop = login("alice@example.com", DESKTOP_UA, "203.0.113.5");

        mockMvc.perform(delete("/api/sessions/" + sidOf(desktop)).header("Authorization", desktop.bearer()))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(desktop)).isEqualTo(401);
    }

    @Test
    void anotherCustomersSessionAndUnknownIdsAreNotFoundAndNothingChanges() throws Exception {
        register("bob@example.com");
        Login alice = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        Login bob = login("bob@example.com", PHONE_UA, "198.51.100.7");

        mockMvc.perform(delete("/api/sessions/" + sidOf(bob)).header("Authorization", alice.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(delete("/api/sessions/" + UUID.randomUUID()).header("Authorization", alice.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/sessions/not-a-uuid").header("Authorization", alice.bearer()))
                .andExpect(status().isBadRequest());

        assertThat(refreshStatus(bob)).isEqualTo(200); // Bob's login survived Alice's attempt
    }

    @Test
    void logOutOfAllOtherDevicesKeepsOnlyThisOne() throws Exception {
        register("bob@example.com");
        Login desktop = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        Login phone = login("alice@example.com", PHONE_UA, "198.51.100.7");
        Login tablet = login("alice@example.com", PHONE_UA, "198.51.100.8");
        Login bob = login("bob@example.com", DESKTOP_UA, "203.0.113.6");

        mockMvc.perform(post("/api/sessions/revoke-others").header("Authorization", desktop.bearer()))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(phone)).isEqualTo(401);
        assertThat(refreshStatus(tablet)).isEqualTo(401);
        assertThat(refreshStatus(desktop)).isEqualTo(200);
        assertThat(refreshStatus(bob)).isEqualTo(200); // another customer is never touched
    }

    // ---- the session id in the access token -------------------------------------------------------------------

    @Test
    void anAccessTokenWithoutASessionIdMarksNothingAsThisDeviceAndLogOutOthersEndsEverything() throws Exception {
        Login desktop = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        User alice = users.findByEmail("alice@example.com").orElseThrow();
        String legacyBearer = "Bearer " + jwtService.issue(alice, null).accessToken(); // issued before sessions existed

        mockMvc.perform(get("/api/sessions").header("Authorization", legacyBearer))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].current").value(false));
        mockMvc.perform(post("/api/sessions/revoke-others").header("Authorization", legacyBearer))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(desktop)).isEqualTo(401); // with no known "this device", the safe choice is to end all
    }
}
