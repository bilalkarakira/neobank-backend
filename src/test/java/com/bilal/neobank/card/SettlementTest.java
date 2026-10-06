package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.clearing.ClearingProperties;
import com.bilal.neobank.card.clearing.SettlementIngestService;
import com.bilal.neobank.card.clearing.SettlementPoster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settlement statements at the issuer: checking the figures, the three ledger moves, disputes, refusals, and the reconciliation report. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class SettlementTest extends ClearingTestBase {

    static final UUID BANK = UUID.fromString("00000000-0000-0000-0000-000000000005");
    static final UUID INCOME = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @TempDir
    Path root;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    SettlementPoster settlementPoster;

    SettlementIngestService ingest;
    Path out;

    @BeforeEach
    void setUp() {
        ingest = new SettlementIngestService(new ClearingProperties(true, root.toString(), "999900", "NET", "x", Duration.ofSeconds(5),
                new BigDecimal("0.0120"), new BigDecimal("0.0005")), jdbcClient, settlementPoster);
        out = root.resolve("settlement-out");
    }

    // ---- building statements ----

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A statement file exactly as the network writes it (the figures are given as text). */
    private String statement(String run, LocalDate business, LocalDate value, String purchases, String refunds, String interchange, String scheme,
            String net, int items) throws Exception {
        String head = "SETTLEMENT," + run + "," + business + "," + value + ",999900,ISSUER\n"
                + "DAY," + business + "," + purchases + "," + refunds + "," + interchange + "," + scheme + "," + net + "\n";
        Files.createDirectories(out);
        String name = "SETL-" + run + "-999900.csv";
        Files.writeString(out.resolve(name), head + "TRAILER," + items + "," + sha(head) + "\n", StandardCharsets.US_ASCII);
        return name;
    }

    /** The cleared day of the examples: 50.00 authorized and cleared with a tip as 57.50, 12.50, and a 5.00 refund. */
    private Fixture clearedDay(String email) throws Exception {
        Fixture f = fixture(email, "500.00");
        present(presentment(authorized(f, "50.00"), "57.50"));
        present(presentment(authorized(f, "12.50"), "12.50"));
        present(refundPresentment(f, "5.00"));
        return f;
    }

    private static final LocalDate VALUE = LocalDate.of(2026, 10, 5);

    private String statementOfTheDay(String run) throws Exception {
        // purchases 70.00, refunds 5.00, interchange 0.69 + 0.15 - 0.06 = 0.78, scheme fee 0.03 + 0.01 + 0.00 = 0.04,
        // the issuer pays 70.00 - 5.00 - 0.78 + 0.04 = 64.26
        return statement(run, DAY, VALUE, "70.00", "5.00", "0.78", "0.04", "-64.26", 3);
    }

    private List<String> settlementTransactions() {
        return jdbc.queryForList("select description || ' ' || round(amount, 2) from transactions where type = 'CARD_SETTLEMENT' order by description", String.class);
    }

    private String runState(String run) {
        return jdbc.queryForObject("select status from settlement_runs where run_number = ? or file_name = ?", String.class, run, run);
    }

    // ---- matched ----

    @Test
    void aStatementThatMatchesPostsThreeMovesAndSettlesTheItems() throws Exception {
        Fixture f = clearedDay("stl1@example.com");
        BigDecimal customerBefore = balanceOf(f);
        statementOfTheDay("S0000001");

        SettlementIngestService.Summary summary = ingest.ingest();

        assertThat(summary).isEqualTo(new SettlementIngestService.Summary(1, 0, 0));
        assertThat(jdbc.queryForObject("select status || '/' || own_net || '/' || own_item_count from settlement_runs", String.class)).isEqualTo("MATCHED/-64.26/3");
        assertThat(settlementTransactions()).containsExactly("Card settlement S0000001 (bank) 64.22", "Card settlement S0000001 (interchange) 0.78",
                "Card settlement S0000001 (scheme fee) 0.04");
        assertThat(balanceOf(SETTLEMENT)).as("the payable is paid down to nothing").isEqualByComparingTo("0.00");
        assertThat(balanceOf(BANK)).as("paid out net: 65.00 - 0.78 + 0.04").isEqualByComparingTo("64.26");
        assertThat(balanceOf(INCOME)).as("interchange earned less scheme fee paid").isEqualByComparingTo("0.74");
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where settlement_run_id is null", Integer.class)).isZero();
        assertThat(balanceOf(f)).as("settlement never touches a customer").isEqualByComparingTo(customerBefore);
        assertBooksBalance();
        // the clearing books check of the admin stays true after settlement (it once forgot that settlement pays the account down)
        String admin = adminToken();
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(jsonPath("$.totals.booksMatch").value(true))
                .andExpect(jsonPath("$.totals.settlementPayable").value("0.00")).andExpect(jsonPath("$.totals.postedNet").value("65.00"));
    }

    @Test
    void aStatementIsPostedOnceEvenWhenIngestedAgain() throws Exception {
        clearedDay("stl2@example.com");
        statementOfTheDay("S0000001");
        ingest.ingest();

        SettlementIngestService.Summary again = ingest.ingest();

        assertThat(again).isEqualTo(new SettlementIngestService.Summary(0, 0, 0));
        assertThat(settlementTransactions()).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from settlement_runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void aRefundHeavyDayReversesTheFlows() throws Exception {
        Fixture f = fixture("stl3@example.com", "100.00");
        present(refundPresentment(f, "40.00"));
        // the issuer RECEIVES: -40.00 - (-0.48) + (-0.02) paid, so net +39.54
        statement("S0000001", DAY, VALUE, "0.00", "40.00", "-0.48", "-0.02", "39.54", 1);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(1, 0, 0));

        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(BANK)).as("money came in").isEqualByComparingTo("-39.54");
        assertThat(balanceOf(INCOME)).as("less interchange and scheme fee income than before").isEqualByComparingTo("-0.46");
        assertBooksBalance();
    }

    @Test
    void anEmptyDayIsAValidStatementAndPostsNothing() throws Exception {
        statement("S0000001", DAY, VALUE, "0.00", "0.00", "0.00", "0.00", "0.00", 0);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(1, 0, 0));
        assertThat(settlementTransactions()).isEmpty();
    }

    @Test
    void aLaterRunForTheSameDaySettlesOnlyTheItemsTheFirstOneLeftOut() throws Exception {
        Fixture f = fixture("stl4@example.com", "200.00");
        present(presentment(authorized(f, "30.00"), "30.00"));
        // statement 1: only the 30.00 purchase (interchange 0.36, scheme fee 0.02 from 0.015)
        statement("S0000001", DAY, VALUE, "30.00", "0.00", "0.36", "0.02", "-29.66", 1);
        ingest.ingest();
        present(presentment(authorized(f, "10.00"), "10.00"));
        statement("S0000002", DAY, VALUE, "10.00", "0.00", "0.12", "0.01", "-9.89", 1);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(1, 0, 0));

        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where settlement_run_id is not null", Integer.class)).isEqualTo(2);
    }

    // ---- disputed ----

    @Test
    void aStatementWhoseFiguresDifferIsDisputedAndNothingIsPosted() throws Exception {
        clearedDay("stl5@example.com");
        statement("S0000001", DAY, VALUE, "70.00", "5.00", "0.79", "0.04", "-64.27", 3); // interchange one cent more

        SettlementIngestService.Summary summary = ingest.ingest();

        assertThat(summary).isEqualTo(new SettlementIngestService.Summary(0, 1, 0));
        String reason = jdbc.queryForObject("select reason from settlement_runs", String.class);
        assertThat(reason).contains("interchange: file 0.79, NeoBank 0.78").contains("net: file -64.27, NeoBank -64.26").doesNotContain("purchases");
        assertThat(settlementTransactions()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where settlement_run_id is not null", Integer.class)).isZero();
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("65.00");

        // a corrected statement under a new run number is accepted
        statementOfTheDay("S0000002");
        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(1, 0, 0));
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("0.00");
    }

    @Test
    void everyFigureIsCheckedOnItsOwn() throws Exception {
        clearedDay("stl6@example.com");
        String[][] wrong = {
                {"S0000001", "71.00", "5.00", "0.78", "0.04", "-64.26", "3", "purchases: file 71.00, NeoBank 70.00"},
                {"S0000002", "70.00", "6.00", "0.78", "0.04", "-64.26", "3", "refunds: file 6.00, NeoBank 5.00"},
                {"S0000003", "70.00", "5.00", "0.78", "0.05", "-64.26", "3", "scheme fee: file 0.05, NeoBank 0.04"},
                {"S0000004", "70.00", "5.00", "0.78", "0.04", "-64.25", "3", "net: file -64.25, NeoBank -64.26"},
                {"S0000005", "70.00", "5.00", "0.78", "0.04", "-64.26", "2", "items: file 2, NeoBank 3"},
        };
        for (String[] w : wrong) {
            statement(w[0], DAY, VALUE, w[1], w[2], w[3], w[4], w[5], Integer.parseInt(w[6]));
        }

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(0, 5, 0));

        for (String[] w : wrong) {
            assertThat(jdbc.queryForObject("select reason from settlement_runs where run_number = ?", String.class, w[0])).contains(w[7]);
        }
        assertThat(settlementTransactions()).isEmpty();
    }

    @Test
    void aStatementForADayWithNoItemsOfOursIsDisputedToo() throws Exception {
        statement("S0000001", DAY, VALUE, "70.00", "0.00", "0.84", "0.04", "-69.20", 2);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(0, 1, 0));
        assertThat(jdbc.queryForObject("select reason from settlement_runs", String.class)).contains("items: file 2, NeoBank 0");
    }

    // ---- refused ----

    @Test
    void aStatementThatCannotBeReadOrIsNotForThisBankIsRefused() throws Exception {
        clearedDay("stl7@example.com");
        Files.createDirectories(out);
        String good = statementOfTheDay("S0000001");
        String text = Files.readString(out.resolve(good));
        // damaged: a changed figure under the old hash
        Files.writeString(out.resolve("SETL-S0000002-999900.csv"), text.replace("S0000001", "S0000002").replace("-64.26", "-60.00"), StandardCharsets.US_ASCII);
        Files.delete(out.resolve(good));
        // other participant
        String head = "SETTLEMENT,S0000003," + DAY + "," + VALUE + ",123456,ACQUIRER\nDAY," + DAY + ",70.00,5.00,0.78,0.04,64.18\n";
        Files.writeString(out.resolve("SETL-S0000003-999900.csv"), head + "TRAILER,3," + sha(head) + "\n", StandardCharsets.US_ASCII);
        // the file name disagrees with the header
        String head4 = "SETTLEMENT,S0000009," + DAY + "," + VALUE + ",999900,ISSUER\nDAY," + DAY + ",0.00,0.00,0.00,0.00,0.00\n";
        Files.writeString(out.resolve("SETL-S0000004-999900.csv"), head4 + "TRAILER,0," + sha(head4) + "\n", StandardCharsets.US_ASCII);
        Files.writeString(out.resolve("SETL-S0000005-999900.csv"), "hello\n", StandardCharsets.US_ASCII);
        Files.writeString(out.resolve("SETL-S0000006-999900.csv"), "no newline at the end", StandardCharsets.US_ASCII);
        String head7 = "SETTLEMENT,S0000007," + DAY + ",not-a-date,999900,ISSUER\nDAY," + DAY + ",0.00,0.00,0.00,0.00,0.00\n";
        Files.writeString(out.resolve("SETL-S0000007-999900.csv"), head7 + "TRAILER,0," + sha(head7) + "\n", StandardCharsets.US_ASCII);
        String head8 = "SETTLEMENT,S0000008," + DAY + "," + VALUE + ",999900,ISSUER\nDAY," + DAY + ",70,0.00,0.00,0.00,0.00\n";
        Files.writeString(out.resolve("SETL-S0000008-999900.csv"), head8 + "TRAILER,0," + sha(head8) + "\n", StandardCharsets.US_ASCII);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(0, 0, 7));

        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000002-999900.csv'", String.class)).contains("hash");
        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000003-999900.csv'", String.class)).contains("not for this bank");
        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000004-999900.csv'", String.class)).contains("file name");
        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000005-999900.csv'", String.class)).contains("exactly three lines");
        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000006-999900.csv'", String.class)).contains("newline");
        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000007-999900.csv'", String.class)).contains("not valid");
        assertThat(jdbc.queryForObject("select reason from settlement_runs where file_name = 'SETL-S0000008-999900.csv'", String.class)).contains("not valid");
        assertThat(settlementTransactions()).isEmpty();
        assertThat(ingest.ingest()).as("a refused file is not looked at again").isEqualTo(new SettlementIngestService.Summary(0, 0, 0));
    }

    @Test
    void aDayLineForAnotherDateThanTheHeaderIsRefused() throws Exception {
        String head = "SETTLEMENT,S0000001," + DAY + "," + VALUE + ",999900,ISSUER\nDAY," + DAY.plusDays(1) + ",0.00,0.00,0.00,0.00,0.00\n";
        Files.createDirectories(out);
        Files.writeString(out.resolve("SETL-S0000001-999900.csv"), head + "TRAILER,0," + sha(head) + "\n", StandardCharsets.US_ASCII);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(0, 0, 1));
        assertThat(jdbc.queryForObject("select reason from settlement_runs", String.class)).contains("DAY line");
    }

    @Test
    void aRunNumberThatIsNotOneOfTheNetworksIsRefused() throws Exception {
        statement("ABC", DAY, VALUE, "0.00", "0.00", "0.00", "0.00", "0.00", 0);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(0, 0, 1));
        assertThat(jdbc.queryForObject("select reason from settlement_runs", String.class)).contains("run number ABC is not valid");
    }

    @Test
    void theInterchangeIsRoundedHalfUpPerItemJustLikeTheNetworkDoesIt() throws Exception {
        Fixture f = fixture("stl17@example.com", "100.00");
        present(presentment(authorized(f, "0.50"), "0.50")); // interchange 0.006 -> 0.01, scheme fee 0.00025 -> 0.00
        statement("S0000001", DAY, VALUE, "0.50", "0.00", "0.01", "0.00", "-0.49", 1);

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(1, 0, 0));
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(INCOME)).isEqualByComparingTo("0.01");
    }

    @Test
    void aRunNumberAlreadyPostedIsRefusedUnderAnotherFileName() throws Exception {
        clearedDay("stl8@example.com");
        String name = statementOfTheDay("S0000001");
        ingest.ingest();

        SettlementPoster.Outcome second = settlementPoster.post(new SettlementPoster.Statement("copy-" + name, "S0000001", DAY, VALUE,
                new BigDecimal("70.00"), new BigDecimal("5.00"), new BigDecimal("0.78"), new BigDecimal("0.04"), new BigDecimal("-64.26"), 3));

        assertThat(second.status()).isEqualTo("REFUSED");
        assertThat(second.reason()).contains("already posted");
        assertThat(settlementTransactions()).hasSize(3);
    }

    @Test
    void theSameStatementDecidedByTwoThreadsAtOnceIsPostedOnce() throws Exception {
        clearedDay("stl9@example.com");
        SettlementPoster.Statement statement = new SettlementPoster.Statement("SETL-S0000001-999900.csv", "S0000001", DAY, VALUE,
                new BigDecimal("70.00"), new BigDecimal("5.00"), new BigDecimal("0.78"), new BigDecimal("0.04"), new BigDecimal("-64.26"), 3);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable run = () -> {
            try {
                barrier.await();
                outcomes.add(settlementPoster.post(statement).status());
            } catch (Exception e) {
                outcomes.add(e);
            }
        };
        Thread a = new Thread(run);
        Thread b = new Thread(run);
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(outcomes).containsOnly("MATCHED");
        assertThat(settlementTransactions()).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from settlement_runs", Integer.class)).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void twoDifferentIssuersFilesAreIgnoredAndNoFolderIsHarmless() throws Exception {
        assertThat(ingest.ingest()).as("no folder at all").isEqualTo(new SettlementIngestService.Summary(0, 0, 0));
        Files.createDirectories(out);
        Files.writeString(out.resolve("SETL-S0000001-123456.csv"), "whatever\n");
        Files.writeString(out.resolve("SETL-S0000001-999900.xml"), "<x/>");
        Files.writeString(out.resolve("notes.txt"), "x");

        assertThat(ingest.ingest()).isEqualTo(new SettlementIngestService.Summary(0, 0, 0));
        assertThat(jdbc.queryForObject("select count(*) from settlement_runs", Integer.class)).isZero();
    }

    // ---- reconciliation ----

    private String adminToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Ada","lastName":"Admin","email":"ada-stl@example.com","password":"%s"}
                """.formatted(PASSWORD))).andExpect(status().isCreated());
        jdbc.update("update users set role = 'ADMIN' where email = 'ada-stl@example.com'");
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"ada-stl@example.com","password":"%s"}
                """.formatted(PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private String cachedAdmin;

    private String report(String query) throws Exception {
        if (cachedAdmin == null) {
            cachedAdmin = adminToken();
        }
        return mockMvc.perform(get("/api/admin/settlement" + query).header("Authorization", cachedAdmin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aCleanDaySaysEverythingAgreesAndNotYetSettledIsNotAnException() throws Exception {
        Fixture f = fixture("stl10@example.com", "200.00");
        present(presentment(authorized(f, "30.00"), "30.00"));

        String before = report("?date=" + DAY);
        assertThat(before).contains("\"allAgree\":true").contains("\"findings\":[]").contains("\"notYetSettled\":1")
                .contains("\"notYetSettledAmount\":\"30.00\"").contains("\"cleared\":1").contains("\"settled\":0").contains("\"ledgerAgrees\":true");

        statement("S0000001", DAY, VALUE, "30.00", "0.00", "0.36", "0.02", "-29.66", 1);
        ingest.ingest();

        String after = report("?date=" + DAY);
        assertThat(after).contains("\"allAgree\":true").contains("\"notYetSettled\":0").contains("\"settled\":1").contains("\"settledAmount\":\"30.00\"")
                .contains("\"status\":\"MATCHED\"").contains("\"settlementPayable\":\"0.00\"");
    }

    @Test
    void everyKindOfDifferenceShowsUpAsAFinding() throws Exception {
        Fixture f = fixture("stl11@example.com", "300.00");
        present(presentment(authorized(f, "50.00"), "57.50"));                    // a tip
        present(presentment(purchase(f, "9.00"), "9.00"));                         // forced
        present(presentment(purchase(f, "4.00").set(2, "9999000000000079"), "4.00")); // rejected (unknown card)
        Message lapsed = authorized(f, "20.00");
        jdbc.update("update authorizations set expires_at = ?, status = 'EXPIRED' where stan = ? and rrn = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-02T10:00:00Z")), lapsed.get(11), lapsed.get(37));

        String json = report("?date=" + DAY);

        assertThat(json).contains("\"allAgree\":false");
        assertThat(json).contains("\"kind\":\"AMOUNT_CHANGED\"").contains("Authorized for 50.00 but cleared for 57.50");
        assertThat(json).contains("\"kind\":\"CLEARED_WITHOUT_AUTHORIZATION\"").contains("never approved");
        assertThat(json).contains("\"kind\":\"REJECTED_AT_ISSUER\"").contains("unknown card");
        assertThat(json).contains("\"kind\":\"NEVER_CLEARED\"").contains("the hold ran out");
        assertThat(json).doesNotContain(f.pan());
    }

    @Test
    void aDisputedStatementIsAFindingAndALedgerThatDisagreesIsToo() throws Exception {
        clearedDay("stl12@example.com");
        statement("S0000001", DAY, VALUE, "70.00", "5.00", "0.79", "0.04", "-64.27", 3);
        ingest.ingest();
        assertThat(report("?date=" + DAY)).contains("\"kind\":\"SETTLEMENT_DISPUTED\"").contains("the figures differ");

        jdbc.update("update accounts set balance = balance + 1 where id = ?", SETTLEMENT);
        assertThat(report("")).contains("\"kind\":\"LEDGER_MISMATCH\"").contains("\"ledgerAgrees\":false");
    }

    @Test
    void aMatchedStatementWhoseLedgerMovesAreMissingIsAFinding() throws Exception {
        Fixture f = fixture("stl13@example.com", "200.00");
        present(presentment(authorized(f, "30.00"), "30.00"));
        statement("S0000001", DAY, VALUE, "30.00", "0.00", "0.36", "0.02", "-29.66", 1);
        ingest.ingest();
        assertThat(report("?date=" + DAY)).contains("\"allAgree\":true");

        jdbc.update("update settlement_runs set purchases = purchases + 1");

        assertThat(report("?date=" + DAY)).contains("\"kind\":\"SETTLEMENT_NOT_IN_LEDGER\"").contains("Run S0000001 says");
    }

    @Test
    void refusedStatementsAreFindingsAndTheDaysOfTheBankAreListed() throws Exception {
        clearedDay("stl14@example.com");
        Files.createDirectories(out);
        Files.writeString(out.resolve("SETL-S0000005-999900.csv"), "hello\n", StandardCharsets.US_ASCII);
        ingest.ingest();

        String json = report("");

        assertThat(json).contains("\"kind\":\"SETTLEMENT_REFUSED\"").contains("exactly three lines").contains("\"businessDates\":[\"" + DAY + "\"]");
    }

    @Test
    void theAdminEndpointsAreForAdministratorsAndTheIngestButtonIsAudited() throws Exception {
        Fixture f = fixture("stl15@example.com", "10.00");
        String admin = adminToken();

        mockMvc.perform(get("/api/admin/settlement")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/settlement").header("Authorization", f.token())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/settlement/ingest").header("Authorization", f.token())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/settlement?date=yesterday").header("Authorization", admin)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/settlement/ingest").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(0)).andExpect(jsonPath("$.disputed").value(0)).andExpect(jsonPath("$.refused").value(0));
        assertThat(jdbc.queryForObject("select count(*) from admin_actions where action = 'RUN_SETTLEMENT_INGEST'", Integer.class)).isEqualTo(1);
    }

    @Test
    void noCardNumberIsKeptInTheSettlementTablesOrStatements() throws Exception {
        Fixture f = clearedDay("stl16@example.com");
        statementOfTheDay("S0000001");
        ingest.ingest();

        for (String row : jdbc.queryForList("select t::text from settlement_runs t", String.class)) {
            assertThat(row).doesNotContain(f.pan());
        }
        try (var all = Files.walk(root)) {
            for (Path p : all.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(p)).as(p.toString()).doesNotContain(f.pan());
            }
        }
    }
}
