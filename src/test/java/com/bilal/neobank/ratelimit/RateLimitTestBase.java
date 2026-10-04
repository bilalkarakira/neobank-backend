package com.bilal.neobank.ratelimit;

import com.bilal.neobank.DatabaseCleaner;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The real (production) limits are put back here: the test configuration lifts them for every other test class.
 * Time is a movable clock that starts on an exact hour, so window arithmetic in the tests is easy to follow.
 */
@SpringBootTest(properties = {
        "app.security.rate-limit.login.max-failures=5",
        "app.security.rate-limit.login.failure-window=15m",
        "app.security.rate-limit.login.lock-duration=15m",
        "app.security.rate-limit.login-per-ip.limit=20",
        "app.security.rate-limit.login-per-ip.window=1m",
        "app.security.rate-limit.refresh-per-ip.limit=30",
        "app.security.rate-limit.refresh-per-ip.window=1m",
        "app.security.rate-limit.register-per-ip.limit=10",
        "app.security.rate-limit.register-per-ip.window=1h",
        "app.security.rate-limit.lookup-per-user.limit=20",
        "app.security.rate-limit.lookup-per-user.window=1m",
        "app.security.rate-limit.probe-misses.limit=10",
        "app.security.rate-limit.probe-misses.window=10m" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
abstract class RateLimitTestBase {

    protected static final String PASSWORD = "S3curepass12";
    protected static final String WRONG = "Wrong-password-1";
    protected static final Instant START = Instant.parse("2026-10-04T12:00:00Z");
    protected static final String IP_A = "10.0.0.1";
    protected static final String IP_B = "10.0.0.2";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected DatabaseCleaner cleaner;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetStateAndClock() {
        cleaner.clean();
        clock.set(START);
    }

    protected void after(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    protected static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected MvcResult register(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/register").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andReturn();
    }

    protected MvcResult login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password))).andReturn();
    }

    protected MvcResult refresh(String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").with(from(ip)).header("X-Requested-With", "neobank")).andReturn();
    }

    protected int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    protected String codeOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    protected String retryAfter(MvcResult result) {
        return result.getResponse().getHeader("Retry-After");
    }

    /** Registers (from a quiet address) and logs in; returns "Bearer ...". */
    protected String bearerFor(String email) throws Exception {
        assertThat(statusOf(register(email, "192.168.50.1"))).isEqualTo(201);
        MvcResult result = login(email, PASSWORD, "192.168.50.2");
        assertThat(statusOf(result)).isEqualTo(200);
        return "Bearer " + JsonPath.<String>read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    protected MvcResult lookup(String bearer, String iban) throws Exception {
        return mockMvc.perform(get("/api/recipients/lookup").param("iban", iban).header("Authorization", bearer)).andReturn();
    }

    protected String openAccount(String bearer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CHECKING\"}")).andReturn();
        assertThat(statusOf(result)).isEqualTo(201);
        return result.getResponse().getContentAsString();
    }
}
