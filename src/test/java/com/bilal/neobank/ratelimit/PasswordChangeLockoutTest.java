package com.bilal.neobank.ratelimit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The password-change form must not be a back door for guessing the password with a stolen access token. */
class PasswordChangeLockoutTest extends RateLimitTestBase {

    private static final String ALICE = "alice@example.com";
    private static final String NEW = "Brand-new-pass-77";

    private MvcResult change(String bearer, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/me/password").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next))).andReturn();
    }

    private String signedIn() throws Exception {
        return bearerFor(ALICE);
    }

    @Test
    void fiveWrongCurrentPasswordsLockTheAccountAndEvenTheRightOneIsRefusedAfterwards() throws Exception {
        String bearer = signedIn();
        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(change(bearer, WRONG, NEW))).isEqualTo(422);
        }

        MvcResult locked = change(bearer, PASSWORD, NEW); // the correct current password

        assertThat(statusOf(locked)).isEqualTo(429);
        assertThat(codeOf(locked)).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(retryAfter(locked)).isEqualTo("900");
    }

    @Test
    void theLockIsTheSameOneLoginUsesSoGuessingHereAlsoBlocksGuessingThere() throws Exception {
        String bearer = signedIn();
        for (int i = 0; i < 5; i++) {
            change(bearer, WRONG, NEW);
        }

        assertThat(statusOf(login(ALICE, PASSWORD, IP_A))).isEqualTo(429);
    }

    @Test
    void wrongLoginPasswordsAlsoCountTowardsTheSameLockSoTheTwoDoorsShareOneBudget() throws Exception {
        String bearer = signedIn();
        for (int i = 0; i < 3; i++) {
            assertThat(statusOf(login(ALICE, WRONG, IP_A))).isEqualTo(401);
        }
        change(bearer, WRONG, NEW);
        change(bearer, WRONG, NEW); // 3 + 2 = 5

        assertThat(statusOf(change(bearer, PASSWORD, NEW))).isEqualTo(429);
    }

    @Test
    void theLockEndsAfterFifteenMinutesAndTheChangeWorksAgain() throws Exception {
        String bearer = signedIn();
        for (int i = 0; i < 5; i++) {
            change(bearer, WRONG, NEW);
        }
        after(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(statusOf(change(bearer, PASSWORD, NEW))).isEqualTo(204);
    }

    @Test
    void aSuccessfulChangeClearsTheCountSoFourMoreTyposAreStillFine() throws Exception {
        String bearer = signedIn();
        for (int i = 0; i < 4; i++) {
            change(bearer, WRONG, NEW);
        }
        assertThat(statusOf(change(bearer, PASSWORD, NEW))).isEqualTo(204);

        for (int i = 0; i < 4; i++) {
            assertThat(statusOf(change(bearer, WRONG, "Another-pass-88"))).isEqualTo(422);
        }
        assertThat(statusOf(change(bearer, NEW, "Another-pass-88"))).isEqualTo(204); // still not locked
    }

    @Test
    void theWrongCurrentPasswordResponseNeverEchoesAnyPassword() throws Exception {
        String bearer = signedIn();
        String body = change(bearer, WRONG, NEW).getResponse().getContentAsString();
        assertThat(body).doesNotContain(WRONG).doesNotContain(NEW).doesNotContain(PASSWORD);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("WRONG_CURRENT_PASSWORD");
    }
}
