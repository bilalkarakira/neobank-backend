package com.bilal.neobank.notification;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security notices: a login from somewhere new, a login lock, a password change, and the inbox that shows them. */
@SpringBootTest(properties = {
        "app.security.rate-limit.login.max-failures=5",
        "app.security.rate-limit.login.failure-window=15m",
        "app.security.rate-limit.login.lock-duration=15m" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class NotificationApiTest {

    private static final String PASSWORD = "S3curepass12";
    private static final String NEW_PASSWORD = "An0therpass34";
    private static final Instant START = Instant.parse("2026-10-05T12:00:00Z");
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String PHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();
        clock.set(START);
        register("alice@example.com");
        register("bob@example.com");
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isCreated());
    }

    private ResultActions attempt(String email, String password, String userAgent, String ip) throws Exception {
        var request = post("/api/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password));
        if (userAgent != null) {
            request.header("User-Agent", userAgent);
        }
        return mockMvc.perform(request);
    }

    private String login(String email, String userAgent, String ip) throws Exception {
        String json = attempt(email, PASSWORD, userAgent, ip).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(json, "$.accessToken");
    }

    private ResultActions inbox(String bearer) throws Exception {
        return mockMvc.perform(get("/api/notifications").header("Authorization", bearer));
    }

    private int countOf(String email, String kind) {
        return jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ? and n.kind = ?",
                Integer.class, email, kind);
    }

    private int totalFor(String email) {
        return jdbc.queryForObject("select count(*) from notifications n join users u on u.id = n.user_id where u.email = ?",
                Integer.class, email);
    }

    // ---- new device ----------------------------------------------------------------------------------------------

    @Test
    void theFirstLoginAndLoginsFromAFamiliarDeviceAndAddressAreNotNews() throws Exception {
        login("alice@example.com", DESKTOP_UA, "203.0.113.5"); // the very first
        login("alice@example.com", DESKTOP_UA, "203.0.113.5"); // the same again
        login("alice@example.com", DESKTOP_UA, "203.0.113.5");

        assertThat(totalFor("alice@example.com")).isZero();
    }

    @Test
    void aNewDeviceOrANewAddressRaisesOneNoticeNamingBothAndTheTime() throws Exception {
        String desktop = login("alice@example.com", DESKTOP_UA, "203.0.113.5");

        login("alice@example.com", PHONE_UA, "203.0.113.5"); // same address, other device
        clock.set(clock.instant().plusSeconds(60));
        login("alice@example.com", DESKTOP_UA, "198.51.100.7"); // same device, other address
        assertThat(countOf("alice@example.com", "NEW_DEVICE_LOGIN")).isEqualTo(2);

        inbox(desktop).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("NEW_DEVICE_LOGIN"))
                .andExpect(jsonPath("$.items[0].body").value(org.hamcrest.Matchers.containsString("Chrome on Windows")))
                .andExpect(jsonPath("$.items[0].body").value(org.hamcrest.Matchers.containsString("198.51.100.7")))
                .andExpect(jsonPath("$.items[0].body").value(org.hamcrest.Matchers.containsString("5 Oct 2026 at 12:01 UTC")))
                .andExpect(jsonPath("$.items[1].body").value(org.hamcrest.Matchers.containsString("Safari on iOS")));

        // once seen, a place is familiar: the same phone again says nothing more
        login("alice@example.com", PHONE_UA, "203.0.113.5");
        assertThat(countOf("alice@example.com", "NEW_DEVICE_LOGIN")).isEqualTo(2);
    }

    @Test
    void aWrongPasswordFromANewPlaceRaisesNothingAndTheNoticeIsOnlyForThatCustomer() throws Exception {
        login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        attempt("alice@example.com", "Wrong-password-1", PHONE_UA, "198.51.100.7").andExpect(status().isUnauthorized());
        login("bob@example.com", PHONE_UA, "198.51.100.7"); // bob's first login

        assertThat(totalFor("alice@example.com")).isZero();
        assertThat(totalFor("bob@example.com")).isZero();
    }

    @Test
    void aFamiliarPlaceStaysFamiliarAfterTheOldLoginWasEndedByLoggingOut() throws Exception {
        String first = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        mockMvc.perform(post("/api/auth/logout-all").header("Authorization", first)).andExpect(status().isNoContent());

        login("alice@example.com", DESKTOP_UA, "203.0.113.5"); // revoked, but still remembered: not new

        assertThat(totalFor("alice@example.com")).isZero();
    }

    // ---- password and lock -----------------------------------------------------------------------------------------

    @Test
    void changingThePasswordRaisesANoticeButAFailedAttemptDoesNot() throws Exception {
        String token = login("alice@example.com", DESKTOP_UA, "203.0.113.5");

        mockMvc.perform(post("/api/me/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Wrong-password-1\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(countOf("alice@example.com", "PASSWORD_CHANGED")).isZero();

        mockMvc.perform(post("/api/me/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        assertThat(countOf("alice@example.com", "PASSWORD_CHANGED")).isEqualTo(1);
        inbox(token).andExpect(jsonPath("$.items[0].kind").value("PASSWORD_CHANGED"));
    }

    @Test
    void theLockRaisesExactlyOneNoticeNoMatterHowManyMoreAttemptsFollow() throws Exception {
        String token = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        for (int i = 0; i < 4; i++) {
            attempt("alice@example.com", "Wrong-password-1", DESKTOP_UA, "203.0.113.5").andExpect(status().isUnauthorized());
        }
        assertThat(countOf("alice@example.com", "LOGIN_LOCKED")).isZero(); // four misses: not locked yet

        attempt("alice@example.com", "Wrong-password-1", DESKTOP_UA, "203.0.113.5").andExpect(status().isUnauthorized());
        assertThat(countOf("alice@example.com", "LOGIN_LOCKED")).isEqualTo(1);

        for (int i = 0; i < 3; i++) { // locked: refused without counting, no more notices
            attempt("alice@example.com", PASSWORD, DESKTOP_UA, "203.0.113.5").andExpect(status().isTooManyRequests());
        }
        assertThat(countOf("alice@example.com", "LOGIN_LOCKED")).isEqualTo(1);
        inbox(token).andExpect(jsonPath("$.items[0].body").value(org.hamcrest.Matchers.containsString("15 minutes")));

        // a second lock later is a second notice
        clock.set(clock.instant().plus(Duration.ofMinutes(16)));
        for (int i = 0; i < 5; i++) {
            attempt("alice@example.com", "Wrong-password-1", DESKTOP_UA, "203.0.113.5");
        }
        assertThat(countOf("alice@example.com", "LOGIN_LOCKED")).isEqualTo(2);
    }

    @Test
    void anAddressWithNoCustomerLocksTheSameWayAndRaisesNothing() throws Exception {
        for (int i = 0; i < 5; i++) {
            attempt("nobody@example.com", "Wrong-password-1", DESKTOP_UA, "203.0.113.5").andExpect(status().isUnauthorized());
        }
        attempt("nobody@example.com", "Wrong-password-1", DESKTOP_UA, "203.0.113.5").andExpect(status().isTooManyRequests());

        assertThat(jdbc.queryForObject("select count(*) from notifications", Integer.class)).isZero();
    }

    @Test
    void wrongCurrentPasswordsThatLockTheLoginAlsoRaiseTheLockNotice() throws Exception {
        String token = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/me/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"Wrong-password-1\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)));
        }

        // the request rolled back each time, yet the notice (written independently) is there
        assertThat(countOf("alice@example.com", "LOGIN_LOCKED")).isEqualTo(1);
    }

    // ---- the inbox -----------------------------------------------------------------------------------------------

    @Test
    void theInboxIsNewestFirstAndCanBeMarkedReadOneByOneOrAllAtOnce() throws Exception {
        String token = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        login("alice@example.com", PHONE_UA, "203.0.113.5");
        clock.set(clock.instant().plusSeconds(60));
        login("alice@example.com", DESKTOP_UA, "198.51.100.7");
        clock.set(clock.instant().plusSeconds(60));
        mockMvc.perform(post("/api/me/password").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        String json = inbox(token).andExpect(jsonPath("$.unreadCount").value(3)).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].kind").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.items[2].body").value(org.hamcrest.Matchers.containsString("Safari on iOS")))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.items[*].id");

        mockMvc.perform(post("/api/notifications/" + ids.get(0) + "/read").header("Authorization", token))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/notifications/" + ids.get(0) + "/read").header("Authorization", token)) // idempotent
                .andExpect(status().isNoContent());
        inbox(token).andExpect(jsonPath("$.unreadCount").value(2)).andExpect(jsonPath("$.items[0].read").value(true))
                .andExpect(jsonPath("$.items[1].read").value(false));

        mockMvc.perform(post("/api/notifications/read-all").header("Authorization", token)).andExpect(status().isNoContent());
        inbox(token).andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void theInboxShowsOnlyTheNewestFiftyButCountsEveryUnreadOne() throws Exception {
        String token = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        for (int i = 0; i < 55; i++) {
            jdbc.update("""
                    insert into notifications (id, user_id, kind, title, body, created_at)
                    values (gen_random_uuid(), (select id from users where email = 'alice@example.com'), 'LOGIN_LOCKED', 't', 'b', ?::timestamptz)
                    """, "2026-10-04T12:%02d:00Z".formatted(i));
        }

        inbox(token).andExpect(jsonPath("$.unreadCount").value(55)).andExpect(jsonPath("$.items.length()").value(50));
    }

    @Test
    void nobodyCanReadOrMarkAnotherCustomersNoticesAndAnonymousCallersAreRefused() throws Exception {
        String alice = login("alice@example.com", DESKTOP_UA, "203.0.113.5");
        login("alice@example.com", PHONE_UA, "203.0.113.5");
        String bob = login("bob@example.com", DESKTOP_UA, "203.0.113.9");
        String aliceNotice = jdbc.queryForObject("select id::text from notifications limit 1", String.class);

        inbox(bob).andExpect(jsonPath("$.unreadCount").value(0)).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(post("/api/notifications/" + aliceNotice + "/read").header("Authorization", bob))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        mockMvc.perform(post("/api/notifications/" + UUID.randomUUID() + "/read").header("Authorization", alice))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/notifications/read-all").header("Authorization", bob)).andExpect(status().isNoContent());
        inbox(alice).andExpect(jsonPath("$.unreadCount").value(1)); // bob's "read all" left alice's alone

        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
    }
}
