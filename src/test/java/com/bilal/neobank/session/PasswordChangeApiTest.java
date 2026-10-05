package com.bilal.neobank.session;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.auth.RefreshTokenService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changing the password: validation, what it ends, what it keeps, and that it is all-or-nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class PasswordChangeApiTest {

    private static final String OLD = "S3curepass12";
    private static final String NEW = "Brand-new-pass-77";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DatabaseCleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    JwtService jwtService;

    @MockitoSpyBean
    RefreshTokenService refreshTokens;

    private record Login(String accessToken, String refreshToken) {
        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        cleaner.clean();
        register("alice@example.com");
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, OLD))).andExpect(status().isCreated());
    }

    private MvcResult loginAttempt(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password))).andReturn();
    }

    private Login login(String email, String password) throws Exception {
        MvcResult result = loginAttempt(email, password);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return new Login(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"),
                result.getResponse().getCookie("neobank_refresh").getValue());
    }

    private int refreshStatus(Login login) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "neobank")
                .cookie(new Cookie("neobank_refresh", login.refreshToken()))).andReturn().getResponse().getStatus();
    }

    private ResultActions change(String bearer, String current, String next) throws Exception {
        var request = post("/api/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return mockMvc.perform(request);
    }

    private String hashOf(String email) {
        return jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
    }

    // ---- success ----------------------------------------------------------------------------------------------

    @Test
    void theNewPasswordWorksTheOldOneStopsAndOnlyAHashIsStored() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        String before = hashOf("alice@example.com");

        change(desktop.bearer(), OLD, NEW).andExpect(status().isNoContent());

        assertThat(loginAttempt("alice@example.com", NEW).getResponse().getStatus()).isEqualTo(200);
        assertThat(loginAttempt("alice@example.com", OLD).getResponse().getStatus()).isEqualTo(401);
        String after = hashOf("alice@example.com");
        assertThat(after).isNotEqualTo(before).startsWith("$2").doesNotContain(NEW);
    }

    @Test
    void everyOtherLoginEndsButTheDeviceThatChangedItStaysSignedIn() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        Login phone = login("alice@example.com", OLD);
        Login tablet = login("alice@example.com", OLD);

        change(desktop.bearer(), OLD, NEW).andExpect(status().isNoContent());

        assertThat(refreshStatus(phone)).isEqualTo(401);
        assertThat(refreshStatus(tablet)).isEqualTo(401);
        assertThat(refreshStatus(desktop)).isEqualTo(200); // this device carries on
    }

    @Test
    void otherCustomersAreNotAffected() throws Exception {
        register("bob@example.com");
        Login alice = login("alice@example.com", OLD);
        Login bob = login("bob@example.com", OLD);

        change(alice.bearer(), OLD, NEW).andExpect(status().isNoContent());

        assertThat(refreshStatus(bob)).isEqualTo(200);
        assertThat(loginAttempt("bob@example.com", OLD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anAccessTokenWithoutASessionIdEndsEveryLoginBecauseThereIsNoDeviceToKeep() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        String legacyBearer = "Bearer " + jwtService.issue(users.findByEmail("alice@example.com").orElseThrow(), null).accessToken();

        change(legacyBearer, OLD, NEW).andExpect(status().isNoContent());

        assertThat(refreshStatus(desktop)).isEqualTo(401);
    }

    // ---- refusals ---------------------------------------------------------------------------------------------

    @Test
    void aWrongCurrentPasswordIsA422NotA401SoTheClientDoesNotThinkTheSessionExpired() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        Login phone = login("alice@example.com", OLD);
        String before = hashOf("alice@example.com");

        change(desktop.bearer(), "Not-my-password-1", NEW).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WRONG_CURRENT_PASSWORD"));

        assertThat(hashOf("alice@example.com")).isEqualTo(before);
        assertThat(refreshStatus(phone)).isEqualTo(200); // nothing was ended
        assertThat(loginAttempt("alice@example.com", OLD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theNewPasswordMustDifferFromTheCurrentOne() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        Login phone = login("alice@example.com", OLD);

        change(desktop.bearer(), OLD, OLD).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_PASSWORD"));

        assertThat(refreshStatus(phone)).isEqualTo(200);
    }

    @Test
    void weakOrMalformedNewPasswordsAreRefusedWithTheSameRulesAsRegistration() throws Exception {
        Login desktop = login("alice@example.com", OLD);

        change(desktop.bearer(), OLD, "short1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.newPassword").exists());
        change(desktop.bearer(), OLD, "onlyletterspassword").andExpect(status().isBadRequest());
        change(desktop.bearer(), OLD, "1234567890123").andExpect(status().isBadRequest());
        change(desktop.bearer(), OLD, "a1" + "x".repeat(71)).andExpect(status().isBadRequest()); // 73 characters
        change(desktop.bearer(), "", NEW).andExpect(status().isBadRequest());
        change(desktop.bearer(), OLD, "").andExpect(status().isBadRequest());

        assertThat(loginAttempt("alice@example.com", OLD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void itNeedsASignedInUser() throws Exception {
        change(null, OLD, NEW).andExpect(status().isUnauthorized());
        assertThat(loginAttempt("alice@example.com", OLD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- all or nothing ---------------------------------------------------------------------------------------

    @Test
    void ifEndingTheOtherLoginsFailsThePasswordIsNotChanged() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        Login phone = login("alice@example.com", OLD);
        String before = hashOf("alice@example.com");
        doThrow(new IllegalStateException("token store down")).when(refreshTokens).revokeAllExcept(any(), any());

        assertThatThrownBy(() -> change(desktop.bearer(), OLD, NEW)).hasRootCauseMessage("token store down");

        // a password changed while the stolen session stayed alive would defeat the whole point
        assertThat(hashOf("alice@example.com")).isEqualTo(before);
        assertThat(refreshStatus(phone)).isEqualTo(200);
        assertThat(loginAttempt("alice@example.com", OLD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theChangeIsVisibleInTheSessionListAfterwards() throws Exception {
        Login desktop = login("alice@example.com", OLD);
        login("alice@example.com", OLD);
        login("alice@example.com", OLD);

        change(desktop.bearer(), OLD, NEW).andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/sessions")
                .header("Authorization", desktop.bearer())).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].current").value(true));
        String payload = new String(Base64.getUrlDecoder().decode(desktop.accessToken().split("\\.")[1]));
        assertThat((String) JsonPath.read(payload, "$.sid")).isNotBlank();
    }
}
