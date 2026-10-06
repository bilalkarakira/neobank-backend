package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import com.bilal.cardnet.network.SettlementFiles.Line;
import com.bilal.cardnet.shared.ClearingDirs;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.testing.FakeIssuerClearing;
import com.bilal.cardnet.shared.testing.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;

import static com.bilal.cardnet.network.ClearingProcessorTest.NO_ROUTE_PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.OTHER_ISSUER_PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.PAN;
import static com.bilal.cardnet.network.ClearingProcessorTest.de4;
import static com.bilal.cardnet.network.ClearingProcessorTest.purchase;
import static com.bilal.cardnet.network.ClearingProcessorTest.refund;
import static org.assertj.core.api.Assertions.assertThat;

/** Settlement: netting per participant, value dates, the files, repeated and concurrent runs. */
class SettlementTest {

    static final String SECRET = "settlement-test-secret";
    static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);

    @TempDir
    Path root;

    final DataSource ds = TestDatabase.fresh();
    final PanVault vault = new PanVault("t".repeat(40));
    final NetworkConfig config = new NetworkConfig();
    ClearingDirs dirs;
    ClearingFileCodec codec;
    FakeIssuerClearing issuer;
    ClearingProcessor processor;
    SettlementService settlement;

    @BeforeEach
    void setUp() {
        config.clearingDir = root;
        config.fileSecret = SECRET;
        config.routes = List.of(new NetworkConfig.Route("999900", "127.0.0.1", 1), new NetworkConfig.Route("888800", "127.0.0.1", 2));
        config.knownAcquirers = Set.of("123456", "654321");
        dirs = new ClearingDirs(root);
        codec = ClearingFileCodec.withSecret(SECRET);
        issuer = new FakeIssuerClearing(root, SECRET);
        processor = new ClearingProcessor(config, ds, vault, Clock.systemUTC());
        settlement = new SettlementService(config, ds, Clock.systemUTC());
    }

    // ---- building cleared days ----

    private void clear(String id, LocalDate date, String acquirer, Message... items) {
        codec.write(dirs.acquirerOut(), id + ".clr", new ClearingFile(id, date, acquirer, "NET", "USD", List.of(items)));
        for (int i = 0; i < 4; i++) {
            processor.tick();
            issuer.tick();
        }
        processor.tick();
    }

    private static Message from(Message m, String acquirer) {
        return m.set(32, acquirer);
    }

    private Line line(SettlementService.Run run, String role, String participant) {
        return run.lines().stream().filter(l -> l.role().equals(role) && l.participant().equals(participant)).findFirst().orElseThrow();
    }

    private static BigDecimal sum(SettlementService.Run run) {
        return run.lines().stream().map(Line::net).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- the money ----

    @Test
    void aDayIsNettedPerParticipantAndAddsUpToZero() {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("50.00")), purchase(PAN, 2, de4("12.50")), refund(PAN, 3, de4("5.00")));

        SettlementService.Run run = settlement.run(THURSDAY).orElseThrow();

        // purchases 62.50, refunds 5.00, interchange 0.60 + 0.15 - 0.06 = 0.69, scheme fee per side 0.03 + 0.01 + 0.00 = 0.04
        Line issuerLine = line(run, "ISSUER", "999900");
        assertThat(issuerLine.purchases()).isEqualByComparingTo("62.50");
        assertThat(issuerLine.refunds()).isEqualByComparingTo("5.00");
        assertThat(issuerLine.interchange()).isEqualByComparingTo("0.69");
        assertThat(issuerLine.schemeFee()).isEqualByComparingTo("0.04");
        assertThat(issuerLine.net()).as("pays 62.50 - 5.00 - 0.69 + 0.04").isEqualByComparingTo("-56.85");
        assertThat(issuerLine.items()).isEqualTo(3);
        Line acquirerLine = line(run, "ACQUIRER", "123456");
        assertThat(acquirerLine.net()).as("receives 62.50 - 5.00 - 0.69 - 0.04").isEqualByComparingTo("56.77");
        assertThat(line(run, "NETWORK", "NET").net()).isEqualByComparingTo("0.08");
        assertThat(sum(run)).isEqualByComparingTo("0");
        assertThat(run.itemCount()).isEqualTo(3);
        assertThat(run.businessDate()).isEqualTo(THURSDAY);
        assertThat(run.valueDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(run.runNumber()).isEqualTo("S0000001");
    }

    @Test
    void theFeesAreTheStoredRoundedOnesNeverRecomputedFromTotals() {
        // 0.10 and 0.10 and 0.10: each fee rounds to zero on its own, so the day has no fees even though 0.30 x 2% is 0.006
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("0.10")), purchase(PAN, 2, de4("0.10")), purchase(PAN, 3, de4("0.10")));

        SettlementService.Run run = settlement.run(THURSDAY).orElseThrow();

        assertThat(line(run, "ISSUER", "999900").interchange()).isEqualByComparingTo("0.00");
        assertThat(line(run, "ISSUER", "999900").net()).isEqualByComparingTo("-0.30");
        assertThat(line(run, "NETWORK", "NET").net()).isEqualByComparingTo("0.00");
        assertThat(sum(run)).isEqualByComparingTo("0");
        // a movement that is zero is not written as an entry: only the purchases, and nothing at all for the network
        assertThat(parseQuietly(root.resolve("settlement-out").resolve("SETL-S0000001-999900.xml")).getElementsByTagName("Ntry").getLength()).isEqualTo(1);
        assertThat(parseQuietly(root.resolve("settlement-out").resolve("SETL-S0000001-NET.xml")).getElementsByTagName("Ntry").getLength()).isZero();
    }

    private static Document parseQuietly(Path path) {
        try {
            return parse(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aRefundOnlyDayReversesEveryFlow() {
        clear("B1", THURSDAY, "123456", refund(PAN, 1, de4("40.00")));

        SettlementService.Run run = settlement.run(THURSDAY).orElseThrow();

        // the issuer RECEIVES 40.00 back, less the 0.02 scheme fee it gets back (its interchange 0.48 is returned to the acquirer): 39.54
        assertThat(line(run, "ISSUER", "999900").net()).isEqualByComparingTo("39.54").isPositive();
        assertThat(line(run, "ACQUIRER", "123456").net()).isEqualByComparingTo("-39.50").isNegative();
        assertThat(line(run, "NETWORK", "NET").net()).isEqualByComparingTo("-0.04").isNegative();
        assertThat(sum(run)).isEqualByComparingTo("0");
    }

    @Test
    void severalAcquirersAndIssuersEachGetTheirOwnLine() {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("100.00")), purchase(OTHER_ISSUER_PAN, 2, de4("200.00")));
        clear("B2", THURSDAY, "654321", from(purchase(PAN, 3, de4("30.00")), "654321"), from(refund(OTHER_ISSUER_PAN, 4, de4("10.00")), "654321"));

        SettlementService.Run run = settlement.run(THURSDAY).orElseThrow();

        assertThat(run.lines()).extracting(l -> l.role() + ":" + l.participant())
                .containsExactly("ISSUER:888800", "ISSUER:999900", "ACQUIRER:123456", "ACQUIRER:654321", "NETWORK:NET");
        assertThat(line(run, "ISSUER", "999900").purchases()).isEqualByComparingTo("130.00");
        assertThat(line(run, "ISSUER", "888800").purchases()).isEqualByComparingTo("200.00");
        assertThat(line(run, "ISSUER", "888800").refunds()).isEqualByComparingTo("10.00");
        assertThat(line(run, "ACQUIRER", "123456").purchases()).isEqualByComparingTo("300.00");
        assertThat(line(run, "ACQUIRER", "654321").items()).isEqualTo(2);
        assertThat(sum(run)).isEqualByComparingTo("0");
        assertThat(run.itemCount()).isEqualTo(4);
    }

    @Test
    void onlyWhatTheIssuerPostedIsSettled() {
        issuer.decision = m -> switch (m.get(11)) {
            case "000002" -> new FakeIssuerClearing.Verdict("REJECTED", "reversed");
            case "000003" -> new FakeIssuerClearing.Verdict("DUPLICATE", "already captured");
            case "000004" -> new FakeIssuerClearing.Verdict("FORCE_POSTED", "unknown authorization");
            default -> new FakeIssuerClearing.Verdict("POSTED", "");
        };
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("20.00")), purchase(PAN, 3, de4("40.00")),
                purchase(PAN, 4, de4("80.00")), purchase(NO_ROUTE_PAN, 5, de4("160.00")));

        SettlementService.Run run = settlement.run(THURSDAY).orElseThrow();

        assertThat(run.itemCount()).as("posted and force-posted only").isEqualTo(2);
        assertThat(line(run, "ISSUER", "999900").purchases()).isEqualByComparingTo("90.00");
    }

    // ---- once ----

    @Test
    void aSecondRunSettlesNothingAndItemsAreSettledOnce() {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("10.00")));
        assertThat(settlement.run(THURSDAY)).isPresent();

        assertThat(settlement.run(THURSDAY)).isEmpty();
        assertThat(settlement.run(null)).isEmpty();
        assertThat(sql("select count(*) from net_settlements")).isEqualTo("1");
        assertThat(sql("select count(*) from net_clearing_items where settlement_id is null and result = 'POSTED'")).isEqualTo("0");
    }

    @Test
    void aLaterFileOfTheSameDayIsSettledByTheNextRunOnly() {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("10.00")));
        settlement.run(THURSDAY);
        clear("B2", THURSDAY, "123456", purchase(PAN, 2, de4("30.00")));

        SettlementService.Run second = settlement.run(THURSDAY).orElseThrow();

        assertThat(second.runNumber()).isEqualTo("S0000002");
        assertThat(second.itemCount()).isEqualTo(1);
        assertThat(line(second, "ISSUER", "999900").purchases()).isEqualByComparingTo("30.00");
    }

    @Test
    void withoutADateTheOldestUnsettledDayIsSettledFirst() {
        clear("B1", THURSDAY.plusDays(1), "123456", purchase(PAN, 1, de4("10.00")));
        clear("B2", THURSDAY, "123456", purchase(PAN, 2, de4("20.00")));
        assertThat(settlement.oldestUnsettled()).contains(THURSDAY);
        assertThat(settlement.unsettledItems()).isEqualTo(2);

        assertThat(settlement.run(null).orElseThrow().businessDate()).isEqualTo(THURSDAY);
        assertThat(settlement.run(null).orElseThrow().businessDate()).isEqualTo(THURSDAY.plusDays(1));
        assertThat(settlement.run(null)).isEmpty();
        assertThat(settlement.unsettledItems()).isZero();
    }

    @Test
    void aDateWithNothingClearedMakesNoRun() {
        assertThat(settlement.run(THURSDAY)).isEmpty();
        assertThat(settlement.run(null)).isEmpty();
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("10.00")));
        assertThat(settlement.run(THURSDAY.minusDays(3))).isEmpty();
        assertThat(sql("select count(*) from net_settlements")).isEqualTo("0");
        assertThat(Files.exists(root.resolve("settlement-out")) ? list(root.resolve("settlement-out")) : List.of()).isEmpty();
    }

    @Test
    void twoRunsAtOnceSettleEveryItemOnce() throws Exception {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("10.00")), purchase(PAN, 2, de4("20.00")), purchase(PAN, 3, de4("30.00")));
        SettlementService second = new SettlementService(config, ds, Clock.systemUTC());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Optional<SettlementService.Run>> results = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread a = new Thread(() -> runAfter(barrier, settlement, results));
        Thread b = new Thread(() -> runAfter(barrier, second, results));
        a.start();
        b.start();
        a.join();
        b.join();

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
        assertThat(sql("select count(*) from net_settlements")).isEqualTo("1");
        assertThat(sql("select sum(item_count) from net_settlements")).isEqualTo("3");
    }

    private static void runAfter(CyclicBarrier barrier, SettlementService service, List<Optional<SettlementService.Run>> results) {
        try {
            barrier.await();
            results.add(service.run(THURSDAY));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- value dates ----

    @Test
    void theValueDateIsTheNextBusinessDay() {
        Set<LocalDate> none = Set.of();
        assertThat(ValueDates.next(LocalDate.of(2026, 10, 8), none)).as("Thursday").isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(ValueDates.next(LocalDate.of(2026, 10, 9), none)).as("Friday goes to Monday").isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ValueDates.next(LocalDate.of(2026, 10, 10), none)).as("Saturday").isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ValueDates.next(LocalDate.of(2026, 10, 11), none)).as("Sunday").isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(ValueDates.next(LocalDate.of(2026, 10, 9), Set.of(LocalDate.of(2026, 10, 12)))).as("Monday is a holiday").isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(ValueDates.next(LocalDate.of(2026, 10, 8), Set.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13))))
                .as("a bridge of holidays").isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(ValueDates.next(LocalDate.of(2026, 12, 31), none)).as("over the new year").isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(ValueDates.isBusinessDay(LocalDate.of(2026, 10, 10), none)).isFalse();
        assertThat(ValueDates.isBusinessDay(LocalDate.of(2026, 10, 9), Set.of(LocalDate.of(2026, 10, 9)))).isFalse();
        assertThat(ValueDates.isBusinessDay(LocalDate.of(2026, 10, 9), none)).isTrue();
    }

    @Test
    void aRunUsesTheConfiguredHolidays() {
        config.holidays = Set.of(LocalDate.of(2026, 10, 12));
        clear("B1", LocalDate.of(2026, 10, 9), "123456", purchase(PAN, 1, de4("10.00")));

        assertThat(settlement.run(LocalDate.of(2026, 10, 9)).orElseThrow().valueDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    // ---- files ----

    @Test
    void everyParticipantGetsACsvAndAnXmlStatementWithTheSameTotals() throws Exception {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("50.00")), purchase(PAN, 2, de4("12.50")), refund(PAN, 3, de4("5.00")));
        SettlementService.Run run = settlement.run(THURSDAY).orElseThrow();
        Path out = root.resolve("settlement-out");

        assertThat(list(out)).extracting(p -> p.getFileName().toString()).containsExactlyInAnyOrder(
                "SETL-S0000001-999900.csv", "SETL-S0000001-999900.xml", "SETL-S0000001-123456.csv", "SETL-S0000001-123456.xml",
                "SETL-S0000001-NET.csv", "SETL-S0000001-NET.xml");

        String csv = Files.readString(out.resolve("SETL-S0000001-999900.csv"));
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("SETTLEMENT,S0000001,2026-10-08,2026-10-09,999900,ISSUER");
        assertThat(lines[1]).isEqualTo("DAY,2026-10-08,62.50,5.00,0.69,0.04,-56.85");
        assertThat(lines[2]).isEqualTo("TRAILER,3," + SettlementFiles.sha256(lines[0] + "\n" + lines[1] + "\n"));

        Document xml = parse(out.resolve("SETL-S0000001-999900.xml"));
        assertThat(xml.getDocumentElement().getLocalName()).isEqualTo("Document");
        assertThat(text(xml, "MsgId")).isEqualTo("SETL-S0000001-999900");
        assertThat(balance(xml, "OPBD")).isEqualTo("0.00 CRDT");
        assertThat(balance(xml, "CLBD")).as("the issuer pays 56.85").isEqualTo("56.85 DBIT");
        NodeList entries = xml.getElementsByTagName("Ntry");
        assertThat(entries.getLength()).isEqualTo(4);
        assertThat(entries.item(0).getTextContent()).contains("62.50").contains("DBIT").contains("Purchases cleared").contains("S0000001");
        assertThat(text(xml, "ValDt")).contains("2026-10-09");

        Document acquirer = parse(out.resolve("SETL-S0000001-123456.xml"));
        assertThat(balance(acquirer, "CLBD")).isEqualTo("56.77 CRDT");
        Document network = parse(out.resolve("SETL-S0000001-NET.xml"));
        assertThat(balance(network, "CLBD")).isEqualTo("0.08 CRDT");
        assertThat(network.getElementsByTagName("Ntry").getLength()).isEqualTo(1);
        assertThat(run.lines()).hasSize(3);
    }

    @Test
    void theXmlSumOfItsEntriesIsItsClosingBalanceForEveryRole() throws Exception {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("50.00")), refund(OTHER_ISSUER_PAN, 2, de4("80.00")), purchase(OTHER_ISSUER_PAN, 3, de4("7.77")));
        settlement.run(THURSDAY);

        for (String participant : List.of("999900", "888800", "123456", "NET")) {
            Document xml = parse(root.resolve("settlement-out").resolve("SETL-S0000001-" + participant + ".xml"));
            BigDecimal total = BigDecimal.ZERO;
            NodeList entries = xml.getElementsByTagName("Ntry");
            for (int i = 0; i < entries.getLength(); i++) {
                org.w3c.dom.Element entry = (org.w3c.dom.Element) entries.item(i);
                BigDecimal amount = new BigDecimal(entry.getElementsByTagName("Amt").item(0).getTextContent());
                total = total.add("CRDT".equals(entry.getElementsByTagName("CdtDbtInd").item(0).getTextContent()) ? amount : amount.negate());
            }
            String[] closing = balance(xml, "CLBD").split(" ");
            BigDecimal closingSigned = new BigDecimal(closing[0]).multiply(BigDecimal.valueOf(closing[1].equals("CRDT") ? 1 : -1));
            assertThat(total).as(participant).isEqualByComparingTo(closingSigned);
        }
    }

    @Test
    void theCsvHashDetectsAChangedByte() throws Exception {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("50.00")));
        settlement.run(THURSDAY);
        String csv = Files.readString(root.resolve("settlement-out").resolve("SETL-S0000001-123456.csv"));
        String[] lines = csv.split("\n");
        String trailer = lines[lines.length - 1];

        String tampered = csv.replace("DAY,2026-10-08,50.00", "DAY,2026-10-08,51.00");
        String[] tamperedLines = tampered.split("\n");
        assertThat(trailer).isEqualTo("TRAILER,1," + SettlementFiles.sha256(lines[0] + "\n" + lines[1] + "\n"));
        assertThat(SettlementFiles.sha256(tamperedLines[0] + "\n" + tamperedLines[1] + "\n")).isNotEqualTo(trailer.split(",")[2]);
    }

    @Test
    void noCardNumberOrClearingDataIsInTheStatements() throws Exception {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("50.00")), purchase(OTHER_ISSUER_PAN, 2, de4("20.00")));
        settlement.run(THURSDAY);

        for (Path p : list(root.resolve("settlement-out"))) {
            String content = Files.readString(p);
            assertThat(content).as(p.toString()).doesNotContain(PAN).doesNotContain(OTHER_ISSUER_PAN).doesNotContain("00000000012").doesNotContain("00000000013");
        }
        for (String table : List.of("net_settlements", "net_settlement_lines")) {
            assertThat(allText(table)).as(table).doesNotContain(PAN).doesNotContain(OTHER_ISSUER_PAN);
        }
    }

    @Test
    void aCrashBeforeTheFilesWereWrittenIsRepairedWithTheSameContent() throws Exception {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("50.00")));
        settlement.run(THURSDAY);
        Path csv = root.resolve("settlement-out").resolve("SETL-S0000001-999900.csv");
        String before = Files.readString(csv);
        for (Path p : list(root.resolve("settlement-out"))) {
            Files.delete(p);
        }
        update("update net_settlements set files_written = false");

        assertThat(settlement.writePending()).isEqualTo(1);

        assertThat(Files.readString(csv)).isEqualTo(before);
        assertThat(list(root.resolve("settlement-out"))).hasSize(6);
        assertThat(settlement.writePending()).as("nothing left to repair").isZero();
        assertThat(sql("select files_written from net_settlements")).isIn("t", "true");
    }

    @Test
    void recentRunsAreListedNewestFirstWithTheirLines() {
        clear("B1", THURSDAY, "123456", purchase(PAN, 1, de4("10.00")));
        settlement.run(THURSDAY);
        clear("B2", THURSDAY.plusDays(1), "123456", purchase(PAN, 2, de4("20.00")));
        settlement.run(null);

        List<SettlementService.Run> runs = settlement.recentRuns(10);

        assertThat(runs).extracting(SettlementService.Run::runNumber).containsExactly("S0000002", "S0000001");
        assertThat(runs.get(0).lines()).hasSize(3);
        assertThat(settlement.recentRuns(1)).hasSize(1);
    }

    // ---- helpers ----

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(Files.readAllBytes(path)));
    }

    private static String text(Document doc, String tag) {
        return doc.getElementsByTagName(tag).item(0).getTextContent();
    }

    /** "56.85 DBIT" for the balance with code {@code code} (OPBD, CLBD). */
    private static String balance(Document doc, String code) {
        NodeList balances = doc.getElementsByTagName("Bal");
        for (int i = 0; i < balances.getLength(); i++) {
            org.w3c.dom.Element bal = (org.w3c.dom.Element) balances.item(i);
            if (bal.getElementsByTagName("Cd").item(0).getTextContent().equals(code)) {
                return bal.getElementsByTagName("Amt").item(0).getTextContent() + " " + bal.getElementsByTagName("CdtDbtInd").item(0).getTextContent();
            }
        }
        throw new AssertionError("no balance " + code);
    }

    private static List<Path> list(Path dir) {
        try (var s = Files.list(dir)) {
            return s.sorted().toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sql(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String allText(String table) {
        StringBuilder out = new StringBuilder();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select t::text from " + table + " t"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.append(rs.getString(1)).append('\n');
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }
}
