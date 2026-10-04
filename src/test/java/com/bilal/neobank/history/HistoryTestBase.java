package com.bilal.neobank.history;

import com.bilal.neobank.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers: data is always created through the real APIs, exactly as a customer would. */
abstract class HistoryTestBase {

    protected static final String PASSWORD = "S3curepass12";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected DatabaseCleaner cleaner;

    @Autowired
    protected JdbcTemplate jdbc;

    private final AtomicInteger keys = new AtomicInteger();

    @BeforeEach
    void clean() {
        cleaner.clean();
    }

    protected record Acc(String id, String iban) {
    }

    protected String tokenFor(String email, String firstName, String lastName) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"%s","lastName":"%s","email":"%s","password":"%s"}
                """.formatted(firstName, lastName, email, PASSWORD))).andExpect(status().isCreated());
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    protected Acc open(String token, String type, String nickname) throws Exception {
        String nick = nickname == null ? "null" : "\"" + nickname + "\"";
        String json = mockMvc.perform(post("/api/accounts").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"%s\",\"nickname\":%s}".formatted(type, nick)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Acc(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"),
                json.replaceAll(".*\"iban\":\"([^\"]+)\".*", "$1"));
    }

    protected void deposit(String token, Acc account, String amount, String description) throws Exception {
        String note = description == null ? "null" : jsonString(description);
        mockMvc.perform(post("/api/accounts/" + account.id() + "/deposits").header("Authorization", token)
                .header("Idempotency-Key", "hist-dep-" + keys.incrementAndGet() + "-xxxxx")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"%s\",\"description\":%s}".formatted(amount, note)))
                .andExpect(status().isCreated());
    }

    protected void transfer(String token, Acc source, String destinationIban, String amount, String description)
            throws Exception {
        String note = description == null ? "null" : jsonString(description);
        mockMvc.perform(post("/api/transfers").header("Authorization", token)
                .header("Idempotency-Key", "hist-trf-" + keys.incrementAndGet() + "-xxxxx")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceAccountId\":\"%s\",\"destinationIban\":\"%s\",\"amount\":\"%s\",\"description\":%s}"
                        .formatted(source.id(), destinationIban, amount, note)))
                .andExpect(status().isCreated());
    }

    protected ResultActions history(String token, Acc account, String query) throws Exception {
        return mockMvc.perform(get("/api/accounts/" + account.id() + "/transactions" + query)
                .header("Authorization", token));
    }

    /** Sends a search text exactly as given, as a real request parameter (no URL-template encoding). */
    protected ResultActions historySearch(String token, Acc account, String text) throws Exception {
        return mockMvc.perform(get("/api/accounts/" + account.id() + "/transactions").param("q", text)
                .header("Authorization", token));
    }

    protected ResultActions export(String token, Acc account, String query) throws Exception {
        return mockMvc.perform(get("/api/accounts/" + account.id() + "/transactions/export.csv" + query)
                .header("Authorization", token));
    }

    /** Moves a transaction (and its entries) in time. The append-only trigger is lifted just for this helper. */
    protected void backdate(String description, String timestamp) {
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_append_only");
        try {
            jdbc.update("update ledger_entries set created_at = ?::timestamptz where transaction_id in "
                    + "(select id from transactions where description = ?)", timestamp, description);
            jdbc.update("update transactions set created_at = ?::timestamptz where description = ?", timestamp,
                    description);
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_append_only");
        }
    }

    protected static List<String> all(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
