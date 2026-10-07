package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.card.clearing.ClearingProperties;
import com.bilal.neobank.card.clearing.SettlementIngestService;
import com.bilal.neobank.card.clearing.SettlementPoster;
import com.bilal.neobank.card.clearing.SettlementReconciliation;
import com.bilal.neobank.card.dispute.DisputeCloser;
import com.bilal.neobank.card.dispute.DisputeFileWriter;
import com.bilal.neobank.card.dispute.DisputeIngest;
import com.bilal.neobank.card.dispute.DisputePostings;
import com.bilal.neobank.card.dispute.RepresentmentPoster;
import com.bilal.neobank.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Card disputes at the issuer (SPEC-P6a-chargebacks.md): filing, the chargeback file, the network's answer, representments, WON, settlement and the books. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
class DisputeTest extends ClearingTestBase {

    static final String SECRET = "dispute-test-secret";
    static final UUID BANK = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @TempDir
    Path root;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    DisputePostings postings;

    @Autowired
    NotificationService notifications;

    @Autowired
    RepresentmentPoster representmentPoster;

    @Autowired
    DisputeCloser closer;

    @Autowired
    SettlementPoster settlementPoster;

    @Autowired
    SettlementReconciliation settlementReconciliation;

    ClearingFileCodec codec;
    ClearingProperties properties;
    DisputeFileWriter writer;
    DisputeIngest ingest;
    Path out;
    Path in;
    private int forwardNumber;

    @BeforeEach
    void setUp() {
        codec = ClearingFileCodec.withSecret(SECRET);
        properties = new ClearingProperties(true, root.toString(), "999900", "NET", SECRET, Duration.ofSeconds(5), new BigDecimal("0.0120"), new BigDecimal("0.0005"));
        writer = new DisputeFileWriter(properties, jdbcClient, tx, postings, notifications, clock);
        ingest = new DisputeIngest(properties, jdbcClient, representmentPoster, clock);
        out = root.resolve("issuer-out");
        in = root.resolve("issuer-in");
    }

    // ---- building the world ----

    /** A payment that was authorized and cleared on {@link #DAY}. */
    private record Paid(UUID authorizationId, Message request, String amount) {
    }

    private Paid paid(Fixture f, String amount) throws Exception {
        Message request = authorized(f, amount);
        present(presentment(request, amount));
        UUID id = jdbc.queryForObject("select id from authorizations where stan = ? and rrn = ?", UUID.class, request.get(11), request.get(37));
        return new Paid(id, request, amount);
    }

    private ResultActions dispute(Fixture f, UUID authorizationId, String json) throws Exception {
        return mockMvc.perform(post("/api/cards/payments/" + authorizationId + "/dispute").header("Authorization", f.token())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID filed(Fixture f, Paid p, String amount) throws Exception {
        mutableClock.set(mutableClock.instant().plusSeconds(1));
        dispute(f, p.authorizationId(), "{\"reason\":\"UNAUTHORIZED\",\"note\":\"I did not buy this\",\"amount\":\"%s\"}".formatted(amount)).andExpect(status().isCreated());
        return jdbc.queryForObject("select id from card_disputes where authorization_id = ?", UUID.class, p.authorizationId());
    }

    private int sinks;

    /** The customer sends money away (a real ledger transfer), so the account no longer holds the credit. */
    private void spend(Fixture f, String amount) throws Exception {
        Fixture sink = fixture("sink" + (++sinks) + "@example.com", "0.00");
        transferOn(f.account(), sink.account(), amount, mutableClock.instant(), null);
    }

    private String disputeState(UUID id) {
        return jdbc.queryForObject("select status || '/' || coalesce(outcome, '-') from card_disputes where id = ?", String.class, id);
    }

    private String stateOf(UUID id) {
        return jdbc.queryForObject("select status from card_disputes where id = ?", String.class, id);
    }

    private String itemResult(UUID disputeId) {
        return jdbc.queryForObject("select i.result from card_disputes d join clearing_items i on i.id = d.chargeback_item_id where d.id = ?", String.class, disputeId);
    }

    private List<Path> chargebackFiles() throws Exception {
        if (!Files.exists(out)) {
            return List.of();
        }
        try (var s = Files.list(out)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".dsp")).sorted().toList();
        }
    }

    private void acknowledge(String fileId, ClearingAck ack) {
        codec.writeAck(in, fileId + ".ack", ack);
    }

    private int recordOf(UUID disputeId) {
        return jdbc.queryForObject("select file_record from card_disputes where id = ?", Integer.class, disputeId);
    }

    private void acceptAll(String fileId, int count) {
        List<ClearingAck.Item> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(new ClearingAck.Item(i, "CHARGEBACK_ACCEPTED", null));
        }
        acknowledge(fileId, ClearingAck.accepted(fileId, items));
    }

    /** The disputed payment goes all the way: filed, written and accepted by the network. */
    private UUID openDispute(Fixture f, Paid p, String amount) throws Exception {
        UUID id = filed(f, p, amount);
        writer.run();
        String fileId = fileIdOf(id);
        acceptAll(fileId, 1);
        writer.run();
        assertThat(stateOf(id)).isEqualTo("OPEN");
        return id;
    }

    private String fileIdOf(UUID disputeId) {
        return jdbc.queryForObject("select f.file_id from card_disputes d join dispute_files f on f.id = d.dispute_file_id where d.id = ?", String.class, disputeId);
    }

    /** The shop's bank answers: a representment forwarded by the network. */
    private String representment(Paid p, String amount, String note) {
        String id = "X%07d".formatted(++forwardNumber);
        Message m = presentment(p.request(), amount).set(48, "REP|" + note);
        codec.write(in, id + ".dsp", new ClearingFile(id, today(), "NET", "999900", "USD", List.of(m)));
        return id;
    }

    private ClearingAck representmentAck(String id) {
        return codec.readAck(out.resolve(id + ".ack"));
    }

    private String adminToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Ada","lastName":"Admin","email":"ada@example.com","password":"%s"}
                """.formatted(PASSWORD))).andExpect(status().isCreated());
        jdbc.update("update users set role = 'ADMIN' where email = 'ada@example.com'");
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"ada@example.com","password":"%s"}
                """.formatted(PASSWORD))).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private void assertBooksMatch() throws Exception {
        String admin = adminTokenOnce();
        mockMvc.perform(get("/api/admin/clearing").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.booksMatch").value(true));
        assertThat(settlementReconciliation.report(null).totals().ledgerAgrees()).as("the reconciliation agrees with the books").isTrue();
        assertBooksBalance();
    }

    private String cachedAdmin;

    private String adminTokenOnce() throws Exception {
        if (cachedAdmin == null) {
            cachedAdmin = adminToken();
        }
        return cachedAdmin;
    }

    // ---- filing ----

    @Test
    void aCustomerDisputesAClearedPaymentAndGetsProvisionalCreditAtOnce() throws Exception {
        Fixture f = fixture("dsp1@example.com", "500.00");
        Paid p = paid(f, "100.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("400.00");

        dispute(f, p.authorizationId(), "{\"reason\":\"UNAUTHORIZED\",\"note\":\"I did not buy this\",\"amount\":\"40.00\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FILED")).andExpect(jsonPath("$.amount").value("40.00"))
                .andExpect(jsonPath("$.reason").value("UNAUTHORIZED")).andExpect(jsonPath("$.merchantName").value("Corner Cafe Springfield"))
                .andExpect(jsonPath("$.last4").value(f.pan().substring(12)));

        assertThat(balanceOf(f)).as("the credit is paid at once").isEqualByComparingTo("440.00");
        assertThat(balanceOf(SETTLEMENT)).isEqualByComparingTo("60.00");
        assertThat(cardTransactions()).contains("CARD_PROVISIONAL 40.00");
        assertThat(jdbc.queryForObject("select kind || '/' || result || '/' || amount || '/' || business_date from clearing_items where kind = 'CHARGEBACK'", String.class))
                .isEqualTo("CHARGEBACK/CHARGEBACK_SENT/40.00/" + today());
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'DISPUTE_FILED'", Integer.class)).isEqualTo(1);
        assertBooksMatch();

        mockMvc.perform(get("/api/cards/disputes").header("Authorization", f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].authorizationId").value(p.authorizationId().toString()))
                .andExpect(jsonPath("$[0].status").value("FILED")).andExpect(jsonPath("$[0].note").value("I did not buy this"));
        mockMvc.perform(get("/api/accounts/" + f.account().id() + "/transactions").header("Authorization", f.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("CARD_PROVISIONAL")).andExpect(jsonPath("$.items[0].counterparty.label").value("Card dispute credit"));
    }

    @Test
    void onlyTheOwnerCanDisputeAndOnlyAClearedPayment() throws Exception {
        Fixture owner = fixture("dsp2@example.com", "200.00");
        Fixture other = fixture("dsp3@example.com", "200.00");
        Paid p = paid(owner, "30.00");
        Message notCleared = authorized(owner, "10.00");
        UUID pending = jdbc.queryForObject("select id from authorizations where stan = ? and rrn = ?", UUID.class, notCleared.get(11), notCleared.get(37));

        dispute(other, p.authorizationId(), "{\"reason\":\"OTHER\"}").andExpect(status().isNotFound());
        dispute(owner, UUID.randomUUID(), "{\"reason\":\"OTHER\"}").andExpect(status().isNotFound());
        dispute(owner, pending, "{\"reason\":\"OTHER\"}").andExpect(status().isNotFound());
        mockMvc.perform(post("/api/cards/payments/" + p.authorizationId() + "/dispute").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select count(*) from card_disputes", Integer.class)).isZero();
        assertThat(balanceOf(other)).isEqualByComparingTo("200.00");
        mockMvc.perform(get("/api/cards/disputes").header("Authorization", other.token())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theAgeLimitIsExactlyOneHundredAndTwentyDays() throws Exception {
        Fixture f = fixture("dsp4@example.com", "500.00");
        LocalDate today = today();
        Message first = authorized(f, "10.00");
        present(presentment(first, "10.00"), today.minusDays(120));
        Message second = authorized(f, "10.00");
        present(presentment(second, "10.00"), today.minusDays(121));
        UUID a = jdbc.queryForObject("select id from authorizations where stan = ? and rrn = ?", UUID.class, first.get(11), first.get(37));
        UUID b = jdbc.queryForObject("select id from authorizations where stan = ? and rrn = ?", UUID.class, second.get(11), second.get(37));

        dispute(f, a, "{\"reason\":\"OTHER\"}").andExpect(status().isCreated());
        dispute(f, b, "{\"reason\":\"OTHER\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("120 days")));
    }

    @Test
    void theAmountIsTheWholePaymentByDefaultAtMostThePaymentAndNeverZero() throws Exception {
        Fixture f = fixture("dsp5@example.com", "900.00");
        Paid whole = paid(f, "25.00");
        Paid exact = paid(f, "25.00");
        Paid tooMuch = paid(f, "25.00");
        Paid zero = paid(f, "25.00");
        Paid negative = paid(f, "25.00");
        Paid cents = paid(f, "25.00");

        dispute(f, whole.authorizationId(), "{\"reason\":\"OTHER\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value("25.00"));
        dispute(f, exact.authorizationId(), "{\"reason\":\"OTHER\",\"amount\":\"25.00\"}").andExpect(status().isCreated());
        dispute(f, tooMuch.authorizationId(), "{\"reason\":\"OTHER\",\"amount\":\"25.01\"}").andExpect(status().isUnprocessableEntity());
        dispute(f, zero.authorizationId(), "{\"reason\":\"OTHER\",\"amount\":\"0.00\"}").andExpect(status().isBadRequest());
        dispute(f, negative.authorizationId(), "{\"reason\":\"OTHER\",\"amount\":\"-1.00\"}").andExpect(status().isBadRequest());
        dispute(f, cents.authorizationId(), "{\"reason\":\"OTHER\",\"amount\":\"1.005\"}").andExpect(status().isBadRequest());
        dispute(f, cents.authorizationId(), "{\"reason\":\"OTHER\",\"amount\":\"0.01\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value("0.01"));

        assertThat(jdbc.queryForObject("select count(*) from card_disputes", Integer.class)).isEqualTo(3);
    }

    @Test
    void theReasonMustBeOneOfTheFiveAndTheNoteIsCleanedAndLimited() throws Exception {
        Fixture f = fixture("dsp6@example.com", "300.00");
        Paid a = paid(f, "10.00");
        Paid b = paid(f, "10.00");
        Paid c = paid(f, "10.00");

        dispute(f, a.authorizationId(), "{\"reason\":\"BORED\"}").andExpect(status().isUnprocessableEntity());
        dispute(f, a.authorizationId(), "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        dispute(f, a.authorizationId(), "{}").andExpect(status().isBadRequest());
        dispute(f, a.authorizationId(), "{\"reason\":\"OTHER\",\"note\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
        dispute(f, b.authorizationId(), "{\"reason\":\"OTHER\",\"note\":\"" + "x".repeat(200) + "\"}").andExpect(status().isCreated());
        dispute(f, c.authorizationId(), "{\"reason\":\"NOT_RECEIVED\",\"note\":\"a|b\\u00e9c\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.note").value("a b c"));

        assertThat(jdbc.queryForObject("select count(*) from card_disputes", Integer.class)).isEqualTo(2);
        for (String reason : List.of("UNAUTHORIZED", "NOT_RECEIVED", "DUPLICATE", "WRONG_AMOUNT", "OTHER")) {
            Paid d = paid(f, "1.00");
            dispute(f, d.authorizationId(), "{\"reason\":\"" + reason + "\"}").andExpect(status().isCreated());
        }
    }

    @Test
    void aPaymentIsDisputedOnceEvenWhenTwoRequestsArriveTogether() throws Exception {
        Fixture f = fixture("dsp7@example.com", "300.00");
        Paid p = paid(f, "50.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        Runnable click = () -> {
            try {
                barrier.await();
                statuses.add(dispute(f, p.authorizationId(), "{\"reason\":\"OTHER\"}").andReturn().getResponse().getStatus());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Thread a = new Thread(click);
        Thread b = new Thread(click);
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(balanceOf(f)).as("the credit was paid once").isEqualByComparingTo("300.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'CARD_PROVISIONAL'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where kind = 'CHARGEBACK'", Integer.class)).isEqualTo(1);
        assertBooksMatch();
    }

    @Test
    void aFrozenAccountCannotFileADispute() throws Exception {
        Fixture f = fixture("dsp8@example.com", "100.00");
        Paid p = paid(f, "20.00");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", f.account().id());

        // the dispute's own rule answers, before the ledger would have refused the credit
        dispute(f, p.authorizationId(), "{\"reason\":\"OTHER\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("a dispute cannot be filed now")));

        assertThat(jdbc.queryForObject("select count(*) from card_disputes", Integer.class)).isZero();
    }

    @Test
    void aCustomerWhoOwesTheBankHasTheCreditPaidOffAgainstTheDebtFirst() throws Exception {
        Fixture f = fixture("dsp9@example.com", "20.00");
        // authorized for 20.00 and cleared for 50.00 (a tip): 20.00 is covered and 30.00 becomes a debt
        Message request = authorized(f, "20.00");
        present(presentment(request, "50.00"));
        UUID authorization = jdbc.queryForObject("select id from authorizations where stan = ? and rrn = ?", UUID.class, request.get(11), request.get(37));
        assertThat(owed(f)).isEqualByComparingTo("30.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("0.00");

        dispute(f, authorization, "{\"reason\":\"WRONG_AMOUNT\"}").andExpect(status().isCreated());

        assertThat(owed(f)).as("the debt is paid off first").isEqualByComparingTo("0.00");
        assertThat(balanceOf(f)).as("then the rest goes to the customer").isEqualByComparingTo("20.00");
        assertThat(cardTransactions()).contains("CARD_PROVISIONAL 30.00", "CARD_PROVISIONAL 20.00");
        assertBooksMatch();
    }

    // ---- the chargeback file ----

    @Test
    void theFiledDisputeIsWrittenInAChargebackFileWithTheRightRecord() throws Exception {
        Fixture f = fixture("dsp10@example.com", "300.00");
        Paid p = paid(f, "80.00");
        UUID id = filed(f, p, "30.00");

        DisputeFileWriter.Summary summary = writer.run();

        assertThat(summary).isEqualTo(new DisputeFileWriter.Summary(1, 1, 0));
        assertThat(chargebackFiles()).hasSize(1);
        ClearingFile file = codec.read(chargebackFiles().get(0));
        assertThat(file.fileId()).matches("C\\d{7}").isEqualTo(jdbc.queryForObject("select file_id from dispute_files", String.class));
        assertThat(file.sender()).isEqualTo("999900");
        assertThat(file.receiver()).isEqualTo("NET");
        assertThat(file.businessDate()).isEqualTo(today());
        assertThat(file.presentments()).hasSize(1);
        Message m = file.presentments().get(0);
        assertThat(m.mti()).isEqualTo("1442");
        assertThat(m.get(3)).isEqualTo("000000");
        assertThat(m.get(4)).isEqualTo("000000003000");
        assertThat(m.get(48)).isEqualTo("CB|UNAUTHORIZED|I did not buy this");
        for (int de : new int[] {11, 13, 32, 37, 41, 42, 49}) {
            assertThat(m.get(de).strip()).as("DE %d", de).isEqualTo(de == 32 ? "00000" + p.request().get(32) : p.request().get(de).strip());
        }
        assertThat(m.get(2)).as("NeoBank keeps no card number: BIN, zeros, last four, which masks the same way").isEqualTo(f.pan().substring(0, 6) + "000000" + f.pan().substring(12));
        assertThat(m.get(2)).isNotEqualTo(f.pan());
        assertThat(jdbc.queryForObject("select status from dispute_files", String.class)).isEqualTo("WRITTEN");
        assertThat(jdbc.queryForObject("select dispute_file_id is not null and file_record = 1 from card_disputes where id = ?", Boolean.class, id)).isTrue();
        assertThat(stateOf(id)).as("still waiting for the network").isEqualTo("FILED");

        assertThat(writer.run()).as("nothing more to do").isEqualTo(new DisputeFileWriter.Summary(0, 0, 0));
        assertThat(chargebackFiles()).hasSize(1);
    }

    @Test
    void disputesOfTheSameDayShareOneFileInOrderAndOtherDaysGetTheirOwn() throws Exception {
        Fixture f = fixture("dsp11@example.com", "500.00");
        Paid a = paid(f, "10.00");
        Paid b = paid(f, "20.00");
        UUID first = filed(f, a, "10.00");
        UUID second = filed(f, b, "20.00");
        UUID tomorrow;
        writer.run();
        // a dispute filed on the next UTC day goes in a file of its own
        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(1)));
        Paid c = paid(f, "5.00");
        tomorrow = filed(f, c, "5.00");

        writer.run();

        assertThat(chargebackFiles()).hasSize(2);
        assertThat(codec.read(chargebackFiles().get(0)).presentments()).extracting(m -> m.get(4)).containsExactly("000000001000", "000000002000");
        assertThat(codec.read(chargebackFiles().get(1)).presentments()).extracting(m -> m.get(4)).containsExactly("000000000500");
        assertThat(jdbc.queryForObject("select file_record from card_disputes where id = ?", Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select file_record from card_disputes where id = ?", Integer.class, second)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select file_record from card_disputes where id = ?", Integer.class, tomorrow)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(distinct file_id) from dispute_files", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select item_count || '/' || control_total from dispute_files order by file_id limit 1", String.class)).isEqualTo("2/3000");
    }

    @Test
    void aCrashBeforeTheFileWasWrittenIsRepairedWithTheSameContent() throws Exception {
        Fixture f = fixture("dsp12@example.com", "300.00");
        Paid p = paid(f, "40.00");
        UUID id = filed(f, p, "40.00");
        writer.run();
        ClearingFile before = codec.read(chargebackFiles().get(0));
        Files.delete(chargebackFiles().get(0));
        jdbc.update("update dispute_files set status = 'CREATED'");

        DisputeFileWriter.Summary summary = writer.run();

        assertThat(summary.filesWritten()).isEqualTo(1);
        assertThat(summary.filesCreated()).isZero();
        assertThat(codec.read(chargebackFiles().get(0))).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from dispute_files", Integer.class)).isEqualTo(1);
        assertThat(stateOf(id)).isEqualTo("FILED");
    }

    // ---- the network's answer ----

    @Test
    void anAcceptedChargebackOpensTheDisputeAndIsSettledLater() throws Exception {
        Fixture f = fixture("dsp13@example.com", "300.00");
        Paid p = paid(f, "40.00");
        UUID id = filed(f, p, "40.00");
        writer.run();
        acceptAll(fileIdOf(id), 1);

        DisputeFileWriter.Summary summary = writer.run();

        assertThat(summary.filesAcknowledged()).isEqualTo(1);
        assertThat(stateOf(id)).isEqualTo("OPEN");
        assertThat(itemResult(id)).isEqualTo("CHARGEBACK_POSTED");
        assertThat(jdbc.queryForObject("select opened_at is not null from card_disputes where id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select status from dispute_files", String.class)).isEqualTo("ACKED");
        assertThat(balanceOf(f)).as("the credit stays").isEqualByComparingTo("300.00");
        assertThat(writer.run().filesAcknowledged()).as("an answer is applied once").isZero();
        mockMvc.perform(get("/api/cards/disputes").header("Authorization", f.token())).andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].answerDueAt").isNotEmpty());
        assertBooksMatch();
    }

    @Test
    void aRefusedChargebackTakesTheCreditBackAndTheDisputeIsLost() throws Exception {
        Fixture f = fixture("dsp14@example.com", "300.00");
        Paid good = paid(f, "40.00");
        Paid bad = paid(f, "20.00");
        UUID accepted = filed(f, good, "40.00");
        UUID refused = filed(f, bad, "20.00");
        writer.run();
        String fileId = fileIdOf(accepted);
        assertThat(balanceOf(f)).as("300 - 40 - 20 + the two credits").isEqualByComparingTo("300.00");
        acknowledge(fileId, ClearingAck.accepted(fileId, List.of(new ClearingAck.Item(recordOf(accepted), "CHARGEBACK_ACCEPTED", null),
                new ClearingAck.Item(recordOf(refused), "REFUSED", "TOO_OLD: the payment cleared more than 120 days ago"))));

        writer.run();

        assertThat(stateOf(accepted)).isEqualTo("OPEN");
        assertThat(disputeState(refused)).isEqualTo("LOST/The card network refused the chargeback: TOO_OLD: the payment cleared more than 120 days ago");
        assertThat(itemResult(refused)).isEqualTo("CHARGEBACK_REFUSED");
        assertThat(balanceOf(f)).as("300 after both credits, less the refused 20.00 taken back").isEqualByComparingTo("280.00");
        assertThat(cardTransactions()).contains("CARD_CREDIT_REVERSED 20.00");
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'DISPUTE_LOST'", Integer.class)).isEqualTo(1);
        assertBooksMatch();
    }

    @Test
    void aWholeFileRefusedOrAnAnswerForAnotherFileLosesEveryDisputeInIt() throws Exception {
        Fixture f = fixture("dsp15@example.com", "300.00");
        Paid a = paid(f, "10.00");
        Paid b = paid(f, "15.00");
        UUID one = filed(f, a, "10.00");
        UUID two = filed(f, b, "15.00");
        writer.run();
        String fileId = fileIdOf(one);
        acknowledge(fileId, ClearingAck.rejected(fileId, "UNKNOWN_SENDER: nobody"));

        writer.run();

        assertThat(stateOf(one)).isEqualTo("LOST");
        assertThat(stateOf(two)).isEqualTo("LOST");
        assertThat(disputeState(one)).contains("UNKNOWN_SENDER");
        assertThat(jdbc.queryForObject("select status from dispute_files", String.class)).isEqualTo("REJECTED");
        assertThat(balanceOf(f)).as("300 - 10 - 15: both credits were taken back").isEqualByComparingTo("275.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'CARD_CREDIT_REVERSED'", Integer.class)).isEqualTo(2);
        assertBooksMatch();
    }

    @Test
    void anAnswerForAnotherFileAndAnItemTheAnswerLeavesOutBothLoseTheDispute() throws Exception {
        Fixture f = fixture("dsp32@example.com", "300.00");
        Paid a = paid(f, "10.00");
        Paid b = paid(f, "15.00");
        UUID one = filed(f, a, "10.00");
        UUID two = filed(f, b, "15.00");
        writer.run();
        String fileId = fileIdOf(one);
        // an accepted answer, but for another file: it must not be trusted
        acknowledge(fileId, ClearingAck.accepted("C9999999", List.of(new ClearingAck.Item(1, "CHARGEBACK_ACCEPTED", null), new ClearingAck.Item(2, "CHARGEBACK_ACCEPTED", null))));

        writer.run();

        assertThat(disputeState(one)).startsWith("LOST/");
        assertThat(disputeState(two)).startsWith("LOST/");
        assertThat(disputeState(one)).contains("another file");
        assertThat(balanceOf(f)).isEqualByComparingTo("275.00");

        // an answer that names only the first item: the other one has no answer and is not assumed accepted
        Paid c = paid(f, "20.00");
        Paid d = paid(f, "25.00");
        UUID three = filed(f, c, "20.00");
        UUID four = filed(f, d, "25.00");
        writer.run();
        String second = fileIdOf(three);
        acknowledge(second, ClearingAck.accepted(second, List.of(new ClearingAck.Item(recordOf(three), "CHARGEBACK_ACCEPTED", null))));

        writer.run();

        assertThat(stateOf(three)).isEqualTo("OPEN");
        assertThat(disputeState(four)).isEqualTo("LOST/The card network refused the chargeback: no answer for this item");
        assertBooksMatch();
    }

    @Test
    void aRepresentmentForADisputeThatIsAlreadyLostIsNotPosted() throws Exception {
        Fixture f = fixture("dsp33@example.com", "300.00");
        Paid p = paid(f, "50.00");
        UUID id = filed(f, p, "50.00");
        writer.run();
        String fileId = fileIdOf(id);
        acknowledge(fileId, ClearingAck.rejected(fileId, "WRONG_RECEIVER: x"));
        writer.run();
        assertThat(stateOf(id)).isEqualTo("LOST");
        BigDecimal balance = balanceOf(f);
        String file = representment(p, "50.00", "too late to matter");

        ingest.ingest();

        assertThat(representmentAck(file).items().get(0).result()).isEqualTo("REJECTED");
        assertThat(representmentAck(file).items().get(0).reason()).contains("already closed as lost");
        assertThat(balanceOf(f)).isEqualByComparingTo(balance);
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'CARD_REPRESENTED'", Integer.class)).isZero();
    }

    @Test
    void aRepresentmentIsMatchedByTheCardAndByBothReferenceNumbers() throws Exception {
        Fixture f = fixture("dsp34@example.com", "500.00");
        Fixture other = fixture("dsp35@example.com", "500.00");
        Paid p = paid(f, "50.00");
        openDispute(f, p, "50.00");
        BigDecimal balance = balanceOf(f);
        LocalDate day = today();
        Message right = presentment(p.request(), "50.00").set(48, "REP|x");
        Message wrongCard = Message.of("1240").set(2, other.pan()).set(3, "000000").set(4, right.get(4)).set(7, right.get(7)).set(11, right.get(11))
                .set(12, right.get(12)).set(13, right.get(13)).set(14, right.get(14)).set(18, right.get(18)).set(32, right.get(32)).set(37, right.get(37))
                .set(38, right.get(38)).set(41, right.get(41)).set(42, right.get(42)).set(43, right.get(43)).set(49, right.get(49)).set(48, "REP|x");
        Message wrongRrn = Message.of("1240").set(2, right.get(2)).set(3, "000000").set(4, right.get(4)).set(7, right.get(7)).set(11, right.get(11))
                .set(12, right.get(12)).set(13, right.get(13)).set(14, right.get(14)).set(18, right.get(18)).set(32, right.get(32)).set(37, "%012d".formatted(7))
                .set(38, right.get(38)).set(41, right.get(41)).set(42, right.get(42)).set(43, right.get(43)).set(49, right.get(49)).set(48, "REP|x");
        codec.write(in, "X0000080.dsp", new ClearingFile("X0000080", day, "NET", "999900", "USD", List.of(wrongCard, wrongRrn)));

        ingest.ingest();

        assertThat(codec.readAck(out.resolve("X0000080.ack")).items()).extracting(ClearingAck.Item::result).containsExactly("REJECTED", "REJECTED");
        assertThat(balanceOf(f)).isEqualByComparingTo(balance);
        assertThat(balanceOf(other)).isEqualByComparingTo("500.00");
    }

    @Test
    void whenTheCustomerAlreadySpentTheCreditARefusedChargebackLeavesADebt() throws Exception {
        Fixture f = fixture("dsp16@example.com", "100.00");
        Paid p = paid(f, "100.00");
        UUID id = filed(f, p, "100.00");
        spend(f, "100.00");
        writer.run();
        String fileId = fileIdOf(id);
        acknowledge(fileId, ClearingAck.rejected(fileId, "WRONG_RECEIVER: x"));

        writer.run();

        assertThat(stateOf(id)).isEqualTo("LOST");
        assertThat(owed(f)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(f)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(RECEIVABLE)).isEqualByComparingTo("-100.00");
    }

    // ---- representments ----

    @Test
    void aRepresentmentDebitsTheCustomerAgainAndTheDisputeIsLost() throws Exception {
        Fixture f = fixture("dsp17@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        assertThat(balanceOf(f)).as("300 - 100 + the credit").isEqualByComparingTo("240.00");
        String file = representment(p, "40.00", "signed receipt 1234");

        DisputeIngest.Summary summary = ingest.ingest();

        assertThat(summary).isEqualTo(new DisputeIngest.Summary(1, 0, 1, 1));
        assertThat(disputeState(id)).isEqualTo("LOST/The shop answered with evidence: signed receipt 1234");
        assertThat(balanceOf(f)).as("240 less the 40.00 debited again").isEqualByComparingTo("200.00");
        assertThat(cardTransactions()).contains("CARD_REPRESENTED 40.00");
        assertThat(jdbc.queryForObject("select kind || '/' || result || '/' || covered || '/' || shortfall || '/' || business_date from clearing_items where kind = 'REPRESENTMENT'", String.class))
                .isEqualTo("REPRESENTMENT/REPRESENTMENT_POSTED/40.00/0.00/" + today());
        assertThat(jdbc.queryForObject("select late_representment from card_disputes where id = ?", Boolean.class, id)).isFalse();
        assertThat(representmentAck(file).items()).extracting(ClearingAck.Item::result).containsExactly("REPRESENTMENT_ACCEPTED");
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'DISPUTE_LOST'", Integer.class)).isEqualTo(1);
        assertBooksMatch();

        assertThat(ingest.ingest()).as("a file is posted once").isEqualTo(new DisputeIngest.Summary(0, 0, 0, 0));
        assertThat(balanceOf(f)).isEqualByComparingTo("200.00");
    }

    @Test
    void aRepresentmentThatTheAccountCannotCoverLeavesADebtAndAFrozenAccountOwesAll() throws Exception {
        Fixture f = fixture("dsp18@example.com", "100.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "100.00");
        spend(f, "70.00");
        representment(p, "100.00", "delivered");

        ingest.ingest();

        assertThat(stateOf(id)).isEqualTo("LOST");
        assertThat(balanceOf(f)).isEqualByComparingTo("0.00");
        assertThat(owed(f)).isEqualByComparingTo("70.00");
        assertThat(jdbc.queryForObject("select covered || '/' || shortfall from clearing_items where kind = 'REPRESENTMENT'", String.class)).isEqualTo("30.00/70.00");
        assertBooksMatch();
    }

    @Test
    void aFrozenAccountIsNotTouchedButOwesTheWholeAmount() throws Exception {
        Fixture f = fixture("dsp19@example.com", "100.00");
        Paid p = paid(f, "50.00");
        UUID id = openDispute(f, p, "50.00");
        jdbc.update("update accounts set status = 'FROZEN' where id = ?::uuid", f.account().id());
        BigDecimal before = balanceOf(f);
        representment(p, "50.00", "delivered");

        ingest.ingest();

        assertThat(stateOf(id)).isEqualTo("LOST");
        assertThat(balanceOf(f)).isEqualByComparingTo(before);
        assertThat(owed(f)).isEqualByComparingTo("50.00");
        assertBooksMatch();
    }

    @Test
    void aPartialRepresentmentDebitsOnlyThatAmount() throws Exception {
        Fixture f = fixture("dsp20@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        representment(p, "15.00", "partial proof");

        ingest.ingest();

        assertThat(stateOf(id)).isEqualTo("LOST");
        assertThat(balanceOf(f)).as("300 - 100 + 40 credit - 15 re-debit").isEqualByComparingTo("225.00");
        assertBooksMatch();
    }

    @Test
    void aRepresentmentForMoreThanWasDisputedOrForNoDisputeOrASecondOneIsNotPosted() throws Exception {
        Fixture f = fixture("dsp21@example.com", "500.00");
        Paid p = paid(f, "100.00");
        Paid unknown = paid(f, "10.00");
        UUID id = openDispute(f, p, "40.00");
        BigDecimal balance = balanceOf(f);
        String tooMuch = representment(p, "40.01", "x");
        String none = representment(unknown, "10.00", "x");

        ingest.ingest();

        assertThat(representmentAck(tooMuch).items().get(0).result()).isEqualTo("REJECTED");
        assertThat(representmentAck(tooMuch).items().get(0).reason()).contains("more than the 40.00");
        assertThat(representmentAck(none).items().get(0).result()).isEqualTo("REJECTED");
        assertThat(stateOf(id)).isEqualTo("OPEN");
        assertThat(balanceOf(f)).isEqualByComparingTo(balance);

        String good = representment(p, "40.00", "ok");
        String again = representment(p, "40.00", "again");
        ingest.ingest();

        assertThat(representmentAck(good).items().get(0).result()).isEqualTo("REPRESENTMENT_ACCEPTED");
        assertThat(representmentAck(again).items().get(0).result()).isEqualTo("DUPLICATE");
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'CARD_REPRESENTED'", Integer.class)).isEqualTo(1);
        assertBooksMatch();
    }

    @Test
    void twoPostersAtOnceOfTheSameRepresentmentRecordPostItOnce() throws Exception {
        Fixture f = fixture("dsp22@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        // the same record of the same file reaches two workers at the same moment (two ingest passes that overlap)
        UUID fileRow = UUID.randomUUID();
        jdbc.update("insert into dispute_files (id, file_name, file_id, direction, business_date, status, created_at) values (?, 'X0000050.dsp', 'X0000050', 'IN', ?, 'RECEIVED', now())",
                fileRow, java.sql.Date.valueOf(today()));
        Message m = presentment(p.request(), "40.00").set(48, "REP|ok");
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        Runnable run = () -> {
            try {
                barrier.await();
                results.add(representmentPoster.post(fileRow, today(), 1, m).result());
            } catch (Throwable e) {
                failures.add(e);
            }
        };
        Thread a = new Thread(run);
        Thread b = new Thread(run);
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(failures).isEmpty();
        assertThat(results).containsExactly("REPRESENTMENT_POSTED", "REPRESENTMENT_POSTED");
        assertThat(stateOf(id)).isEqualTo("LOST");
        assertThat(jdbc.queryForObject("select count(*) from transactions where type = 'CARD_REPRESENTED'", Integer.class)).as("debited once").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where kind = 'REPRESENTMENT'", Integer.class)).isEqualTo(1);
        assertBooksMatch();
    }

    @Test
    void aFileThatCannotBeTrustedIsRefusedWholeAndAnswered() throws Exception {
        Fixture f = fixture("dsp23@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        Message m = presentment(p.request(), "40.00").set(48, "REP|x");
        LocalDate day = today();
        codec.write(in, "X0000090.dsp", new ClearingFile("X0000090", day, "NET", "654321", "USD", List.of(m)));           // wrong receiver
        codec.write(in, "X0000091.dsp", new ClearingFile("X0000091", day, "666666", "999900", "USD", List.of(m)));        // wrong sender
        codec.write(in, "X0000093.dsp", new ClearingFile("X0000092", day, "NET", "999900", "USD", List.of(m)));           // the id inside differs from the name
        Message chargeback = Message.of("1442").set(2, m.get(2)).set(3, "000000").set(4, m.get(4)).set(7, m.get(7)).set(11, m.get(11)).set(12, m.get(12))
                .set(13, m.get(13)).set(32, m.get(32)).set(37, m.get(37)).set(41, m.get(41)).set(42, m.get(42)).set(49, m.get(49)).set(48, "CB|OTHER|x");
        codec.write(in, "X0000094.dsp", new ClearingFile("X0000094", day, "NET", "999900", "USD", List.of(chargeback)));  // wrong direction
        Files.writeString(in.resolve("X0000095.dsp"), "not a file at all");

        DisputeIngest.Summary summary = ingest.ingest();

        assertThat(summary.filesRefused()).isEqualTo(5);
        assertThat(summary.itemsPosted()).isZero();
        assertThat(stateOf(id)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select count(*) from dispute_files where direction = 'IN' and status = 'REJECTED'", Integer.class)).isEqualTo(5);
        assertThat(codec.readAck(out.resolve("X0000090.ack")).reason()).startsWith("WRONG_RECEIVER");
        assertThat(codec.readAck(out.resolve("X0000091.ack")).reason()).startsWith("UNKNOWN_SENDER");
        assertThat(codec.readAck(out.resolve("X0000093.ack")).reason()).startsWith("FILE_NAME_MISMATCH");
        assertThat(codec.readAck(out.resolve("X0000094.ack")).reason()).startsWith("WRONG_DIRECTION");
        assertThat(codec.readAck(out.resolve("X0000095.ack")).status()).isEqualTo(ClearingAck.Status.REJECTED);
        assertThat(ingest.ingest()).as("refused files are not looked at again").isEqualTo(new DisputeIngest.Summary(0, 0, 0, 0));
        assertThat(balanceOf(f)).as("nothing was debited again").isEqualByComparingTo("240.00");
    }

    @Test
    void aRepresentmentThatArrivesBeforeTheNetworksAnswerStillLosesTheDisputeAndTheAnswerDoesNotReopenIt() throws Exception {
        Fixture f = fixture("dsp24@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = filed(f, p, "40.00");
        writer.run();
        representment(p, "40.00", "fast shop");
        ingest.ingest();
        assertThat(stateOf(id)).isEqualTo("LOST");

        acceptAll(fileIdOf(id), 1);
        writer.run();

        assertThat(stateOf(id)).as("the late acknowledgement does not reopen it").isEqualTo("LOST");
        assertThat(itemResult(id)).isEqualTo("CHARGEBACK_POSTED");
        assertThat(balanceOf(f)).isEqualByComparingTo("200.00");
        assertBooksMatch();
    }

    // ---- WON ----

    @Test
    void aDisputeNobodyAnsweredIsWonAfterTheWindowAndNotBefore() throws Exception {
        Fixture f = fixture("dsp25@example.com", "300.00");
        Paid p = paid(f, "100.00");
        Paid filedOnly = paid(f, "10.00");
        UUID open = openDispute(f, p, "40.00");
        UUID stillFiled = filed(f, filedOnly, "10.00");
        Instant opened = jdbc.queryForObject("select opened_at from card_disputes where id = ?", java.sql.Timestamp.class, open).toInstant();
        BigDecimal balance = balanceOf(f);

        mutableClock.set(opened.plus(Duration.ofDays(14)).minusSeconds(1));
        assertThat(closer.closeDue()).isZero();
        assertThat(stateOf(open)).isEqualTo("OPEN");

        mutableClock.set(opened.plus(Duration.ofDays(14)));
        assertThat(closer.closeDue()).as("exactly at the end of the window").isEqualTo(1);
        assertThat(disputeState(open)).isEqualTo("WON/The shop did not answer in time: your credit is final.");
        assertThat(stateOf(stillFiled)).as("a dispute the network has not taken is not won").isEqualTo("FILED");
        assertThat(balanceOf(f)).as("nothing moves when it is won").isEqualByComparingTo(balance);
        assertThat(jdbc.queryForObject("select count(*) from notifications where kind = 'DISPUTE_WON'", Integer.class)).isEqualTo(1);
        assertThat(closer.closeDue()).as("closed once").isZero();
        assertBooksMatch();
    }

    @Test
    void aLateRepresentmentAfterWonDebitsTheCustomerLosesTheDisputeAndIsAFinding() throws Exception {
        Fixture f = fixture("dsp26@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(15)));
        closer.closeDue();
        assertThat(stateOf(id)).isEqualTo("WON");
        assertThat(balanceOf(f)).isEqualByComparingTo("240.00");
        representment(p, "40.00", "we were late");

        ingest.ingest();

        assertThat(stateOf(id)).isEqualTo("LOST");
        assertThat(jdbc.queryForObject("select late_representment from card_disputes where id = ?", Boolean.class, id)).isTrue();
        assertThat(balanceOf(f)).isEqualByComparingTo("200.00");
        assertThat(settlementReconciliation.report(null).findings()).extracting(SettlementReconciliation.Finding::kind).contains("LATE_REPRESENTMENT");
        assertBooksMatch();
    }

    @Test
    void theAdminCanCloseDueDisputesNowAndItIsAudited() throws Exception {
        Fixture f = fixture("dsp27@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(15)));
        String admin = adminTokenOnce();

        mockMvc.perform(post("/api/admin/disputes/close-due").header("Authorization", f.token())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/disputes/close-due").header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(1));

        assertThat(stateOf(id)).isEqualTo("WON");
        assertThat(jdbc.queryForObject("select count(*) from admin_actions where action = 'CLOSE_DISPUTES' and detail = 'closed=1'", Integer.class)).isEqualTo(1);
        mockMvc.perform(get("/api/admin/disputes").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.won").value(1)).andExpect(jsonPath("$.disputes[0].email").value("dsp27@example.com"))
                .andExpect(jsonPath("$.disputes[0].last4").value(f.pan().substring(12)));
        mockMvc.perform(get("/api/admin/disputes").header("Authorization", f.token())).andExpect(status().isForbidden());
    }

    // ---- settlement and the books ----

    private String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private void statement(String run, LocalDate business, LocalDate value, String purchases, String refunds, String interchange, String scheme, String net,
            int items) throws Exception {
        String head = "SETTLEMENT," + run + "," + business + "," + value + ",999900,ISSUER\n"
                + "DAY," + business + "," + purchases + "," + refunds + "," + interchange + "," + scheme + "," + net + "\n";
        Path folder = root.resolve("settlement-out");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("SETL-" + run + "-999900.csv"), head + "TRAILER," + items + "," + sha(head) + "\n", StandardCharsets.US_ASCII);
    }

    @Test
    void anAcceptedChargebackIsSettledLikeARefundAndNeoBanksOwnFiguresMatch() throws Exception {
        Fixture f = fixture("dsp28@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = filed(f, p, "40.00");
        LocalDate day = today();
        SettlementIngestService settlement = new SettlementIngestService(properties, jdbcClient, settlementPoster);
        statement("S0000001", day, day.plusDays(1), "0.00", "40.00", "-0.48", "-0.02", "39.54", 1);

        // before the network took it the chargeback is not settled: NeoBank's own figures count no item, so the statement is disputed
        settlement.ingest();
        assertThat(jdbc.queryForObject("select status from settlement_runs", String.class)).isEqualTo("DISPUTED");
        jdbc.update("delete from settlement_runs");
        Files.delete(root.resolve("settlement-out").resolve("SETL-S0000001-999900.csv"));

        writer.run();
        acceptAll(fileIdOf(id), 1);
        writer.run();
        statement("S0000002", day, day.plusDays(1), "0.00", "40.00", "-0.48", "-0.02", "39.54", 1);
        settlement.ingest();

        assertThat(jdbc.queryForObject("select status from settlement_runs", String.class)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("select settlement_run_id is not null from clearing_items where kind = 'CHARGEBACK'", Boolean.class)).isTrue();
        assertThat(balanceOf(BANK)).as("the account counts what NeoBank paid out, so receiving 39.54 (40.00 less 0.48 interchange given back plus 0.02 scheme fee refunded) is minus 39.54")
                .isEqualByComparingTo("-39.54");
        assertBooksMatch();
        assertThat(settlementReconciliation.report(day).allAgree()).isTrue();
    }

    @Test
    void aRepresentmentIsSettledLikeAPurchase() throws Exception {
        Fixture f = fixture("dsp29@example.com", "300.00");
        Paid p = paid(f, "100.00");
        UUID id = openDispute(f, p, "40.00");
        representment(p, "40.00", "ok");
        ingest.ingest();
        LocalDate day = today();
        SettlementIngestService settlement = new SettlementIngestService(properties, jdbcClient, settlementPoster);
        // the day holds the chargeback (refund-like) and the representment (purchase-like): purchases 40.00, refunds 40.00, fees cancel out
        statement("S0000003", day, day.plusDays(1), "40.00", "40.00", "0.00", "0.00", "0.00", 2);

        settlement.ingest();

        assertThat(jdbc.queryForObject("select status from settlement_runs", String.class)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("select count(*) from clearing_items where settlement_run_id is not null and kind in ('CHARGEBACK', 'REPRESENTMENT')", Integer.class)).isEqualTo(2);
        assertThat(stateOf(id)).isEqualTo("LOST");
        assertBooksMatch();
    }

    // ---- findings ----

    @Test
    void theReconciliationShowsARefusedChargebackAndADisputeStuckTooLong() throws Exception {
        Fixture f = fixture("dsp30@example.com", "300.00");
        Paid refusedPayment = paid(f, "20.00");
        Paid stuck = paid(f, "30.00");
        UUID refused = filed(f, refusedPayment, "20.00");
        UUID stuckId = filed(f, stuck, "30.00");
        writer.run();
        String fileId = fileIdOf(refused);
        acknowledge(fileId, ClearingAck.accepted(fileId, List.of(new ClearingAck.Item(1, "REFUSED", "TOO_OLD: x"), new ClearingAck.Item(2, "REFUSED", "TOO_OLD: x"))));
        writer.run();
        // the second dispute is put back as if the network never answered
        jdbc.update("update card_disputes set status = 'FILED', outcome = null, closed_at = null where id = ?", stuckId);
        jdbc.update("update clearing_items set result = 'CHARGEBACK_SENT' where id = (select chargeback_item_id from card_disputes where id = ?)", stuckId);

        List<String> early = settlementReconciliation.report(null).findings().stream().map(SettlementReconciliation.Finding::kind).toList();
        assertThat(early).doesNotContain("DISPUTE_OPEN_TOO_LONG");

        mutableClock.set(mutableClock.instant().plus(Duration.ofDays(14)).plusSeconds(1));
        List<String> kinds = settlementReconciliation.report(null).findings().stream().map(SettlementReconciliation.Finding::kind).toList();

        assertThat(kinds).contains("CHARGEBACK_REFUSED", "DISPUTE_OPEN_TOO_LONG");
    }

    // ---- secrets ----

    @Test
    void noCardNumberIsKeptInTheDisputeTablesOrWrittenInTheFiles() throws Exception {
        Fixture f = fixture("dsp31@example.com", "300.00");
        Paid p = paid(f, "100.00");
        openDispute(f, p, "40.00");
        representment(p, "40.00", "ok");
        ingest.ingest();

        for (String table : List.of("card_disputes", "dispute_files", "clearing_items")) {
            String text = jdbc.queryForObject("select coalesce(string_agg(t::text, ' '), '') from " + table + " t", String.class);
            assertThat(text).as(table).doesNotContain(f.pan());
        }
        for (Path file : chargebackFiles()) {
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain(f.pan());
            assertThat(codec.read(file).presentments()).allSatisfy(m -> assertThat(m.get(2)).isNotEqualTo(f.pan()));
        }
    }
}
