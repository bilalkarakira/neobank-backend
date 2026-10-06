package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.clearing.ClearingIngestService;
import com.bilal.neobank.card.clearing.ClearingProperties;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Clearing files end to end at the issuer: ingest, acknowledgement, refusals, resuming, collection, the admin and customer views. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class ClearingIngestTest extends ClearingTestBase {

    static final String SECRET = "ingest-test-secret";

    @TempDir
    Path root;

    @Autowired
    JdbcClient jdbcClient;

    ClearingFileCodec codec;
    ClearingIngestService ingest;
    Path in;
    Path out;
    private int fileNumber;

    @BeforeEach
    void setUp() {
        codec = ClearingFileCodec.withSecret(SECRET);
        ingest = new ClearingIngestService(new ClearingProperties(true, root.toString(), "999900", "NET", SECRET, Duration.ofSeconds(5), new BigDecimal("0.0120"), new BigDecimal("0.0005")),
                jdbcClient, poster, clock);
        in = root.resolve("issuer-in");
        out = root.resolve("issuer-out");
    }

    private String put(Message... presentments) {
        String id = "N%07d".formatted(++fileNumber);
        codec.write(in, id + ".clr", new ClearingFile(id, LocalDate.of(2026, 10, 6), "NET", "999900", "USD", List.of(presentments)));
        return id;
    }

    private ClearingAck ack(String id) {
        return codec.readAck(out.resolve(id + ".ack"));
    }

    // ---- a whole file ----

    @Test
    void aFileIsIngestedItsItemsPostedAndTheFileAcknowledged() throws Exception {
        Fixture f = fixture("ing1@example.com", "200.00");
        Message a = authorized(f, "30.00");
        Message b = authorized(f, "50.00");
        String id = put(presentment(a, "30.00"), presentment(b, "57.50"), refundPresentment(f, "5.00"));

        ClearingIngestService.Summary summary = ingest.ingest();

        assertThat(summary.filesProcessed()).isEqualTo(1);
        assertThat(summary.filesRefused()).isZero();
        assertThat(summary.itemsPosted()).isEqualTo(3);
        assertThat(summary.acknowledgementsWritten()).isEqualTo(1);
        ClearingAck ack = ack(id);
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
        assertThat(ack.items()).extracting(ClearingAck.Item::record).containsExactly(1, 2, 3);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "POSTED", "REFUND_POSTED");
        assertThat(balanceOf(f)).isEqualByComparingTo("117.50");
        assertThat(jdbc.queryForObject("select status || '/' || item_count || '/' || control_total || '/' || ack_written from clearing_files", String.class))
                .isEqualTo("DONE/3/9250/true");
        assertBooksBalance();

        // nothing more to do the second time
        ClearingIngestService.Summary again = ingest.ingest();
        assertThat(again.filesProcessed() + again.itemsPosted() + again.acknowledgementsWritten()).isZero();
        assertThat(cardTransactions()).hasSize(3);
    }

    @Test
    void everyKindOfResultIsCarriedInTheAcknowledgement() throws Exception {
        Fixture f = fixture("ing2@example.com", "200.00");
        Message captured = authorized(f, "10.00");
        present(presentment(captured, "10.00"));
        Message reversed = authorized(f, "11.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            terminal.exchange(reversalOf(reversed));
        }
        String id = put(presentment(captured, "10.00"), presentment(reversed, "11.00"), presentment(purchase(f, "12.00"), "12.00"),
                presentment(purchase(f, "13.00").set(2, "9999000000000079"), "13.00"));

        ingest.ingest();

        ClearingAck ack = ack(id);
        assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("DUPLICATE", "REJECTED", "FORCE_POSTED", "REJECTED");
        assertThat(ack.items().get(1).reason()).isEqualTo("the authorization was reversed");
        assertThat(ack.items().get(3).reason()).isEqualTo("unknown card");
        assertThat(ack.items()).extracting(ClearingAck.Item::cleared).containsExactly(true, false, true, false);
    }

    // ---- files refused whole ----

    private void assertRefused(String name, String reasonStart) {
        assertThat(jdbc.queryForObject("select status from clearing_files where file_name = ?", String.class, name)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select reason from clearing_files where file_name = ?", String.class, name)).startsWith(reasonStart);
        ClearingAck ack = codec.readAck(out.resolve(name.replace(".clr", ".ack")));
        assertThat(ack.status()).isEqualTo(ClearingAck.Status.REJECTED);
        assertThat(ack.reason()).startsWith(reasonStart);
        assertThat(jdbc.queryForObject("select count(*) from clearing_items", Integer.class)).as("a refused file posts nothing").isZero();
        assertThat(cardTransactions()).isEmpty();
    }

    @Test
    void aFileWithTheWrongKeyOrTamperedOrNotAClearingFileIsRefusedAndAnswered() throws Exception {
        Fixture f = fixture("ing3@example.com", "100.00");
        Message request = authorized(f, "30.00");
        Files.createDirectories(in);
        ClearingFileCodec other = ClearingFileCodec.withSecret("somebody else");
        other.write(in, "N0000001.clr", new ClearingFile("N0000001", LocalDate.of(2026, 10, 6), "NET", "999900", "USD", List.of(presentment(request, "30.00"))));
        Files.writeString(in.resolve("N0000002.clr"), "hello", StandardCharsets.US_ASCII);
        fileNumber = 2;
        put(presentment(request, "30.00")); // N0000003, then damaged
        Path damaged = in.resolve("N0000003.clr");
        byte[] bytes = Files.readAllBytes(damaged);
        bytes[bytes.length / 2] ^= 1;
        Files.write(damaged, bytes);

        ClearingIngestService.Summary summary = ingest.ingest();

        assertThat(summary.filesRefused()).isEqualTo(3);
        assertRefused("N0000001.clr", "DECRYPT_FAILED");
        assertRefused("N0000002.clr", "NOT_A_CLEARING_FILE");
        assertRefused("N0000003.clr", "DECRYPT_FAILED");
        assertThat(balanceOf(f)).isEqualByComparingTo("100.00");
    }

    @Test
    void aFileFromTheWrongSenderToTheWrongReceiverOrUnderTheWrongNameIsRefused() throws Exception {
        Fixture f = fixture("ing4@example.com", "100.00");
        Message request = authorized(f, "30.00");
        List<Message> items = List.of(presentment(request, "30.00"));
        codec.write(in, "N0000001.clr", new ClearingFile("N0000001", LocalDate.of(2026, 10, 6), "SOMEONE", "999900", "USD", items));
        codec.write(in, "N0000002.clr", new ClearingFile("N0000002", LocalDate.of(2026, 10, 6), "NET", "888800", "USD", items));
        codec.write(in, "N0000003.clr", new ClearingFile("N0000009", LocalDate.of(2026, 10, 6), "NET", "999900", "USD", items));

        ingest.ingest();

        assertRefused("N0000001.clr", "UNKNOWN_SENDER");
        assertRefused("N0000002.clr", "WRONG_RECEIVER");
        assertRefused("N0000003.clr", "FILE_NAME_MISMATCH");
    }

    @Test
    void aRefusedFileIsNotLookedAtAgain() throws Exception {
        Files.createDirectories(in);
        Files.writeString(in.resolve("N0000001.clr"), "hello", StandardCharsets.US_ASCII);
        ingest.ingest();
        byte[] first = Files.readAllBytes(out.resolve("N0000001.ack"));

        ClearingIngestService.Summary again = ingest.ingest();

        assertThat(again.filesRefused() + again.filesProcessed() + again.acknowledgementsWritten()).isZero();
        assertThat(Files.readAllBytes(out.resolve("N0000001.ack"))).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from clearing_files", Integer.class)).isEqualTo(1);
    }

    // ---- crash: resuming ----

    @Test
    void aFileLeftHalfDoneIsFinishedWithoutPostingTheDoneItemsAgain() throws Exception {
        Fixture f = fixture("ing5@example.com", "200.00");
        Message a = authorized(f, "30.00");
        Message b = authorized(f, "40.00");
        String id = put(presentment(a, "30.00"), presentment(b, "40.00"));
        // what a crash after the first item leaves behind: the file row (RECEIVED) and the first item posted
        UUID fileRow = UUID.randomUUID();
        jdbc.update("insert into clearing_files (id, file_name, network_file_id, status, item_count, control_total, received_at) values (?, ?, ?, 'RECEIVED', 2, 7000, now())",
                fileRow, id + ".clr", id);
        poster.post(fileRow, 1, presentment(a, "30.00"));
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00");

        ClearingIngestService.Summary summary = ingest.ingest();

        assertThat(summary.acknowledgementsWritten()).isEqualTo(1);
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 30.00", "CARD_PURCHASE 40.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("130.00");
        assertThat(ack(id).items()).extracting(ClearingAck.Item::result).containsExactly("POSTED", "POSTED");
        assertThat(jdbc.queryForObject("select status from clearing_files", String.class)).isEqualTo("DONE");
    }

    @Test
    void aCrashBeforeTheAcknowledgementWasWrittenIsRepaired() throws Exception {
        Fixture f = fixture("ing6@example.com", "100.00");
        String id = put(presentment(authorized(f, "30.00"), "30.00"));
        ingest.ingest();
        Files.delete(out.resolve(id + ".ack"));
        jdbc.update("update clearing_files set ack_written = false");

        ingest.ingest();

        assertThat(ack(id).items()).extracting(ClearingAck.Item::result).containsExactly("POSTED");
        assertThat(cardTransactions()).hasSize(1);
    }

    // ---- collection and the authorization rule ----

    private Fixture owing(String email) throws Exception {
        Fixture f = fixture(email, "52.00");
        present(presentment(authorized(f, "50.00"), "57.50")); // owes 5.50
        assertThat(owed(f)).isEqualByComparingTo("5.50");
        return f;
    }

    private BigDecimal depositViaApi(Fixture f, String amount) throws Exception {
        String json = mockMvc.perform(post("/api/accounts/" + f.account().id() + "/deposits").header("Authorization", f.token())
                .header("Idempotency-Key", "dep-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"%s\"}".formatted(amount))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new BigDecimal(json.replaceAll(".*\"balance\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void aDepositPaysOffWhatIsOwedFirstAndTheResponseShowsTheBalanceAfterThat() throws Exception {
        Fixture f = owing("ing7@example.com");

        BigDecimal shown = depositViaApi(f, "100.00");

        assertThat(shown).isEqualByComparingTo("94.50");
        assertThat(balanceOf(f)).isEqualByComparingTo("94.50");
        assertThat(owed(f)).isEqualByComparingTo("0");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 52.00", "CARD_SHORTFALL 5.50", "CARD_COLLECTION 5.50");
        assertThat(balanceOf(RECEIVABLE)).isEqualByComparingTo("0");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("57.50");
        assertThat(jdbc.queryForObject("select count(*) from card_receivables where outstanding = 0 and settled_at is not null", Integer.class)).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void aSmallDepositPaysOffWhatItCanAndTheRestIsStillOwed() throws Exception {
        Fixture f = owing("ing8@example.com");

        BigDecimal shown = depositViaApi(f, "2.00");

        assertThat(shown).isEqualByComparingTo("0.00");
        assertThat(owed(f)).isEqualByComparingTo("3.50");
        assertThat(cardTransactions()).containsExactly("CARD_PURCHASE 52.00", "CARD_SHORTFALL 5.50", "CARD_COLLECTION 2.00");
        assertThat(balanceOf(RECEIVABLE)).isEqualByComparingTo("-3.50");
        assertBooksBalance();
    }

    @Test
    void aDepositWithNothingOwedIsAnOrdinaryDeposit() throws Exception {
        Fixture f = fixture("ing9@example.com", "10.00");

        assertThat(depositViaApi(f, "5.00")).isEqualByComparingTo("15.00");
        assertThat(cardTransactions()).isEmpty();
    }

    @Test
    void collectionNeverSpendsMoneyThatAnotherHoldReserves() throws Exception {
        Fixture f = fixture("ing10@example.com", "100.00");
        Message a = authorized(f, "50.00");
        authorized(f, "40.00"); // stays held
        present(presentment(a, "90.00")); // 60 coverable (100 minus the other hold), 30 owed
        assertThat(owed(f)).isEqualByComparingTo("30.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("40.00");

        depositViaApi(f, "20.00");

        assertThat(balanceOf(f)).as("20 arrived, 20 of the 30 owed was collected, the 40 held is untouched").isEqualByComparingTo("40.00");
        assertThat(owed(f)).isEqualByComparingTo("10.00");
        assertThat(available(f)).isEqualByComparingTo("0.00");
        assertThat(reconciliation.check().accountsHeldAboveBalance()).isEmpty();
        assertBooksBalance();
    }

    @Test
    void whileMoneyIsOwedNewCardPaymentsAreDeclinedUntilARefundPaysItOff() throws Exception {
        Fixture f = owing("ing11@example.com");
        depositOn(f.account(), "100.00", mutableClock.instant()); // money arrives some other way: no collection

        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(purchase(f, "5.00")))).as("owes the bank").isEqualTo("05");

            present(refundPresentment(f, "10.00")); // 5.50 pays off the debt, 4.50 to the customer

            assertThat(owed(f)).isEqualByComparingTo("0");
            assertThat(code(terminal.exchange(purchase(f, "5.00")))).isEqualTo("00");
        }
    }

    // ---- what people see ----

    private String adminToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Ada","lastName":"Admin","email":"ada-clr@example.com","password":"%s"}
                """.formatted(PASSWORD))).andExpect(status().isCreated());
        jdbc.update("update users set role = 'ADMIN' where email = 'ada-clr@example.com'");
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"ada-clr@example.com","password":"%s"}
                """.formatted(PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void theOwedEndpointShowsTheCustomerWhatTheyOwe() throws Exception {
        Fixture f = owing("ing12@example.com");
        Fixture other = fixture("ing12b@example.com", "10.00");

        mockMvc.perform(get("/api/cards/owed").header("Authorization", f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("5.50")).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].outstanding").value("5.50")).andExpect(jsonPath("$.items[0].amount").value("5.50"));
        mockMvc.perform(get("/api/cards/owed").header("Authorization", other.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0.00")).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/cards/owed")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCardActivityShowsWhatWasActuallyPostedAndWhatWasForced() throws Exception {
        Fixture f = fixture("ing13@example.com", "200.00");
        present(presentment(authorized(f, "50.00"), "57.50"));
        present(presentment(purchase(f, "5.00"), "5.00"));

        String json = mockMvc.perform(get("/api/cards/" + f.cardId() + "/authorizations").header("Authorization", f.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json).contains("\"status\":\"CAPTURED\"").contains("\"amount\":\"50.00\"").contains("\"capturedAmount\":\"57.50\"")
                .contains("\"capturedAmount\":\"5.00\"").contains("\"forced\":true").contains("\"forced\":false");
    }

    @Test
    void theHistoryShowsCardPaymentsAndRefundsWithTheShopAndFiltersByType() throws Exception {
        Fixture f = fixture("ing17@example.com", "100.00");
        present(presentment(authorized(f, "30.00"), "30.00"));
        present(refundPresentment(f, "12.00"));

        String all = api(f.token(), "/api/accounts/" + f.account().id() + "/transactions").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("\"type\":\"CARD_PURCHASE\"").contains("\"type\":\"CARD_REFUND\"").contains("Card payment").contains("Card refund")
                .contains("Corner Cafe").contains("\"amount\":\"-30.00\"").contains("\"amount\":\"12.00\"");
        api(f.token(), "/api/accounts/" + f.account().id() + "/transactions?type=CARD_REFUND").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].counterparty.label").value("Card refund"));
        api(f.token(), "/api/accounts/" + f.account().id() + "/transactions?type=CARD_PURCHASE").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].counterparty.label").value("Card payment"));
    }

    @Test
    void aShortfallRepaidByADepositShowsInTheHistoryAsRepaid() throws Exception {
        Fixture f = owing("ing18@example.com");
        depositViaApi(f, "100.00");

        String json = api(f.token(), "/api/accounts/" + f.account().id() + "/transactions?type=CARD_COLLECTION").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("Card payment repaid").contains("\"amount\":\"-5.50\"");
    }

    @Test
    void theAdminSeesFilesItemsAndWhetherTheBooksAgree() throws Exception {
        Fixture f = owing("ing14@example.com");
        Fixture g = fixture("ing14b@example.com", "100.00");
        String id = put(presentment(authorized(g, "1.00"), "1.00"), refundPresentment(f, "2.00"));
        ingest.ingest();
        String admin = adminToken();

        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files[?(@.fileName=='" + id + ".clr')].status").value(org.hamcrest.Matchers.contains("DONE")))
                .andExpect(jsonPath("$.files[?(@.fileName=='" + id + ".clr')].controlTotal").value(org.hamcrest.Matchers.contains("3.00")))
                .andExpect(jsonPath("$.files[?(@.fileName=='" + id + ".clr')].ackWritten").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.receivables[0].email").value("ing14@example.com"))
                .andExpect(jsonPath("$.receivables[0].outstanding").value("3.50"))
                .andExpect(jsonPath("$.totals.receivableOutstanding").value("3.50"))
                .andExpect(jsonPath("$.totals.postedNet").value("56.50")).andExpect(jsonPath("$.totals.settlementPayable").value("56.50"))
                .andExpect(jsonPath("$.totals.receivableAccount").value("-3.50")).andExpect(jsonPath("$.totals.booksMatch").value(true));
        mockMvc.perform(get("/api/admin/clearing?result=REFUND_POSTED").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].kind").value("REFUND"));
        mockMvc.perform(get("/api/admin/clearing?result=NOPE").header("Authorization", admin)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where last4 not in (?, ?)", Integer.class, f.pan().substring(12), g.pan().substring(12))).isZero();
    }

    @Test
    void theBooksCheckFailsWhenTheSettlementAccountDisagreesWithTheItems() throws Exception {
        Fixture f = fixture("ing19@example.com", "100.00");
        present(presentment(authorized(f, "30.00"), "30.00"));
        String admin = adminToken();
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(jsonPath("$.totals.booksMatch").value(true));

        jdbc.update("update accounts set balance = balance + 1 where id = ?", SETTLEMENT);
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(jsonPath("$.totals.booksMatch").value(false));
        jdbc.update("update accounts set balance = balance - 1 where id = ?", SETTLEMENT);
        jdbc.update("update accounts set balance = balance - 1 where id = ?", RECEIVABLE);
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(jsonPath("$.totals.booksMatch").value(false));
    }

    @Test
    void aDebtThatWasPaidOffNoLongerShowsAnywhere() throws Exception {
        Fixture f = owing("ing20@example.com");
        present(refundPresentment(f, "10.00")); // pays it off completely
        String admin = adminToken();

        mockMvc.perform(get("/api/cards/owed").header("Authorization", f.token())).andExpect(jsonPath("$.total").value("0.00"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(jsonPath("$.receivables.length()").value(0))
                .andExpect(jsonPath("$.totals.receivableOutstanding").value("0.00"));
    }

    @Test
    void theSummaryCountsOnlyItemsThatWerePosted() throws Exception {
        Fixture f = fixture("ing21@example.com", "100.00");
        Message captured = authorized(f, "10.00");
        present(presentment(captured, "10.00"));
        put(presentment(captured, "10.00"), presentment(authorized(f, "20.00"), "20.00"), presentment(purchase(f, "1.00").set(2, "9999000000000079"), "1.00"));

        ClearingIngestService.Summary summary = ingest.ingest();

        assertThat(summary.itemsPosted()).as("one duplicate and one rejected do not count").isEqualTo(1);
    }

    @Test
    void onlyAdministratorsSeeClearingAndRunningItIsAudited() throws Exception {
        Fixture f = fixture("ing15@example.com", "100.00");
        String admin = adminToken();
        put(presentment(authorized(f, "30.00"), "30.00"));

        mockMvc.perform(get("/api/admin/clearing")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", f.token())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/clearing/ingest").header("Authorization", f.token())).andExpect(status().isForbidden());
        // the admin button uses the application's own ingest (another folder, empty): it still works and is audited
        mockMvc.perform(post("/api/admin/clearing/ingest").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.filesProcessed").value(0));
        assertThat(jdbc.queryForObject("select count(*) from admin_actions where action = 'RUN_CLEARING_INGEST'", Integer.class)).isEqualTo(1);
    }

    @Test
    void noCardNumberIsKeptInTheClearingTablesOrFiles() throws Exception {
        Fixture f = owing("ing16@example.com");
        Fixture g = fixture("ing16b@example.com", "100.00");
        put(presentment(authorized(g, "1.00"), "1.00"), refundPresentment(f, "2.00"));
        ingest.ingest();

        for (String table : List.of("clearing_files", "clearing_items", "card_receivables")) {
            List<String> rows = jdbc.queryForList("select t::text from " + table + " t", String.class);
            assertThat(rows).as(table).isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain(f.pan()).doesNotContain(g.pan()));
        }
        try (var all = Files.walk(root)) {
            for (Path p : all.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                assertThat(content).as(p.toString()).doesNotContain(f.pan()).doesNotContain(g.pan()).doesNotContain("FILEHDR").doesNotContain("ACKHDR");
            }
        }
    }
}
