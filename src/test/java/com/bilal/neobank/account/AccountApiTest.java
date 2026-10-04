package com.bilal.neobank.account;

import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiTest {

    private static final String PASSWORD = "S3curepass12";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AccountRepository accountRepository;

    @BeforeEach
    void clean() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** Registers a user and returns a bearer token for them. */
    private String tokenFor(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Test","lastName":"User","email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions open(String token, String type, String nickname) throws Exception {
        String nick = nickname == null ? "null" : "\"" + nickname + "\"";
        return mockMvc.perform(post("/api/accounts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\",\"nickname\":%s}".formatted(type, nick)));
    }

    private String idOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void openingCheckingAccountReturnsActiveUsdAccountWithValidIban() throws Exception {
        String token = tokenFor("a@example.com");

        String body = open(token, "CHECKING", "  Daily  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CHECKING"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.nickname").value("Daily"))
                .andReturn().getResponse().getContentAsString();

        String iban = body.replaceAll(".*\"iban\":\"([^\"]+)\".*", "$1");
        assertThat(IbanGenerator.isValid(iban)).isTrue();
    }

    @Test
    void blankNicknameIsStoredAsNull() throws Exception {
        open(tokenFor("a@example.com"), "SAVINGS", "   ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").doesNotExist());
    }

    @Test
    void ibansAreUniqueAcrossAccounts() throws Exception {
        String token = tokenFor("a@example.com");
        String first = open(token, "SAVINGS", null).andReturn().getResponse().getContentAsString();
        String second = open(token, "SAVINGS", null).andReturn().getResponse().getContentAsString();

        assertThat(first.replaceAll(".*\"iban\":\"([^\"]+)\".*", "$1"))
                .isNotEqualTo(second.replaceAll(".*\"iban\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void secondCheckingAccountIsRejectedButSavingsLimitIsSeparate() throws Exception {
        String token = tokenFor("a@example.com");

        open(token, "CHECKING", null).andExpect(status().isCreated());
        open(token, "CHECKING", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"));

        for (int i = 0; i < 4; i++) {
            open(token, "SAVINGS", null).andExpect(status().isCreated());
        }
        open(token, "SAVINGS", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LIMIT_REACHED"));
    }

    @Test
    void simultaneousRequestsCannotExceedTheLimit() throws Exception {
        String token = tokenFor("a@example.com");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> task = () -> {
                start.await();
                return open(token, "CHECKING", null).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();

        int created = 0;
        for (Future<Integer> result : results) {
            if (result.get() == 201) {
                created++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void listReturnsOnlyMyAccountsNewestFirst() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        open(alice, "CHECKING", "first").andExpect(status().isCreated());
        open(alice, "SAVINGS", "second").andExpect(status().isCreated());
        open(bob, "CHECKING", "bobs").andExpect(status().isCreated());

        mockMvc.perform(get("/api/accounts").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nickname").value("second"))
                .andExpect(jsonPath("$[1].nickname").value("first"));
    }

    @Test
    void anotherUsersAccountLooksLikeItDoesNotExist() throws Exception {
        String alice = tokenFor("alice@example.com");
        String bob = tokenFor("bob@example.com");
        String bobsAccountId = idOf(open(bob, "CHECKING", null));

        mockMvc.perform(get("/api/accounts/" + bobsAccountId).header("Authorization", bob))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts/" + bobsAccountId).header("Authorization", alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void unknownAccountIdReturns404() throws Exception {
        mockMvc.perform(get("/api/accounts/" + java.util.UUID.randomUUID()).header("Authorization",
                tokenFor("a@example.com"))).andExpect(status().isNotFound());
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mockMvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"CHECKING\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTypeAndTooLongNicknameReturn400() throws Exception {
        String token = tokenFor("a@example.com");

        open(token, "BUSINESS", null).andExpect(status().isBadRequest());
        open(token, "CHECKING", "x".repeat(41))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.nickname").exists());
        mockMvc.perform(post("/api/accounts").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }
}
