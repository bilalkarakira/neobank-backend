import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One whole card day against the running simulation, told as it happens (JDK only):
 *
 * <pre>
 *   java scenario/Scenario.java              (the addresses below are the defaults of docker compose)
 *   BANK=http://127.0.0.1:8080 POS=http://127.0.0.1:9000 NET=http://127.0.0.1:9100 java scenario/Scenario.java
 * </pre>
 *
 * It pays at a shop, gets declined, mistypes a PIN, sends a payment twice, adds a tip, takes NeoBank "down" and still gets an
 * approval from the card network, voids a payment, closes the day (clearing), refunds, settles the day between the banks, and
 * then looks at what NeoBank itself says about all of it. It also disputes two payments: the shop answers the first
 * (the customer loses), nobody answers the second (the customer wins once the 30 second window of the compose stack has passed). Every step states what it expects; the exit code is 1 if any
 * expectation fails, so it is also an end-to-end test of the packaging. Use it once per ten minutes (NeoBank allows a card ten
 * approvals in ten minutes). Test cards and test numbers only: this is a simulation, not a bank.
 */
public class Scenario {

    static final String BANK = env("BANK", "http://127.0.0.1:8080");
    static final String POS = env("POS", "http://127.0.0.1:9000");
    static final String NET = env("NET", "http://127.0.0.1:9100");
    static final String CAFE = "MERCHANT0000001";
    static final String CASINO = "MERCHANT0000004";
    static final String GAS = "MERCHANT0000002";

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final List<String> failures = new ArrayList<>();
    static String pan;
    static String pin;
    static String cvv;
    static String expiry;
    static String bankToken;
    static String adminToken;
    static String cardId;

    public static void main(String[] args) throws Exception {
        System.out.println("NeoBank card day. A simulation: test cards, no real money, no real card scheme.\n");
        waitForTheStack();
        String demoToken = login("demo@neobank.local", "DemoPassw0rd");
        bankToken = demoToken;
        adminToken = login("admin@neobank.local", "AdminPassw0rd");
        String cards = get(BANK + "/api/cards", demoToken);
        cardId = find(cards, "\"id\":\"([^\"]+)\"");
        String config = get(POS + "/api/config", null);
        pan = find(config, "\"demoCard\":\\{\"pan\":\"(\\d+)\"");
        pin = find(config, "\"pin\":\"(\\d+)\"");
        expiry = find(config, "\"expiry\":\"([0-9/]+)\"");
        cvv = find(config, "\"cvv\":\"(\\d+)\"");
        int heldBefore = holds();
        int capturedBefore = captured();
        System.out.println("The demo card ends in " + pan.substring(12) + ". NeoBank holds " + heldBefore + " payment(s) for it, " + capturedBefore + " are posted.\n");

        step("1. A shop takes a payment of 25.00 (card and PIN)");
        String coffee = pay("25.00", CAFE, pin);
        expect("approved by NeoBank", "APPROVED".equals(field(coffee, "status")));
        String coffeeId = field(coffee, "id");
        System.out.println("   authorization code " + field(coffee, "authCode") + ": NeoBank now HOLDS 25.00, it has not taken it yet");
        expect("the hold shows at NeoBank", holds() == heldBefore + 1);

        step("2. The same card at a casino (a blocked merchant category)");
        String casino = pay("10.00", CASINO, pin);
        expect("declined with code 57 (not permitted)", "DECLINED".equals(field(casino, "status")) && "57".equals(field(casino, "responseCode")));

        step("3. A wrong PIN");
        String wrong = pay("5.00", CAFE, "0000".equals(pin) ? "1111" : "0000");
        expect("declined with code 55 (wrong PIN)", "55".equals(field(wrong, "responseCode")));
        String right = pay("5.00", CAFE, pin);
        expect("the right PIN is accepted and resets the counter", "APPROVED".equals(field(right, "status")));
        String fiveId = field(right, "id");

        step("4. The terminal sends the last payment AGAIN (a network retry): the duplicate rule");
        int before = holds();
        String again = post(POS + "/api/resend", "");
        System.out.println("   " + field(again, "message"));
        expect("the same answer again", field(again, "message") != null && field(again, "message").startsWith("The same answer again"));
        expect("and the bank holds the money once", holds() == before);

        step("5. A restaurant payment of 40.00 gets a tip: the final amount is 46.00 (authorization stays 40.00)");
        String dinner = pay("40.00", CAFE, pin);
        expect("approved", "APPROVED".equals(field(dinner, "status")));
        String dinnerId = field(dinner, "id");
        String tipped = post(POS + "/api/adjust", "{\"id\":\"" + dinnerId + "\",\"amount\":\"46.00\"}");
        expect("the final amount is set", "46.00".equals(field(tipped, "finalAmount")));

        step("6. NeoBank becomes unreachable: the card network approves SMALL payments on its own (stand-in)");
        post(POS + "/api/network/chaos", "{\"issuerDown\":true}");
        waitUntil("the network sees NeoBank down", () -> contains(get(NET + "/status", null), "\"issuerLink\":\"DOWN\""), 30);
        String standIn = pay("20.00", CAFE, null);
        System.out.println("   " + field(standIn, "message"));
        expect("approved by the network itself (authorization code starts with SI)", "APPROVED".equals(field(standIn, "status")) && field(standIn, "authCode") != null && field(standIn, "authCode").startsWith("SI"));
        String big = pay("80.00", CAFE, null);
        expect("a larger payment is refused while NeoBank is out of reach (91)", "91".equals(field(big, "responseCode")));
        String standInId = field(standIn, "id");

        step("7. NeoBank comes back: the network tells it about the payment it approved (an advice, 0120)");
        post(POS + "/api/network/chaos", "{\"issuerDown\":false}");
        waitUntil("the link is up again", () -> contains(get(NET + "/status", null), "\"issuerLink\":\"UP\""), 60);
        waitUntil("the advice was delivered", () -> contains(get(NET + "/status", null), "\"advicesWaiting\":0"), 60);
        expect("NeoBank holds the stand-in payment and marks it", contains(get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", demoToken), "\"standIn\":true"));

        step("8. A payment is cancelled (void)");
        String cancelled = pay("7.00", CAFE, pin);
        post(POS + "/api/void", "{\"id\":\"" + field(cancelled, "id") + "\"}");
        waitUntil("the void is confirmed by NeoBank", () -> "REVERSED".equals(posStatus(field(cancelled, "id"))), 30);
        expect("the cancelled payment is reversed", "REVERSED".equals(posStatus(field(cancelled, "id"))));

        step("9. A cash machine: a withdrawal needs the PIN, and the bank has cash limits");
        String atmBody = "{\"pan\":\"" + pan + "\",\"expiry\":\"" + expiry + "\",\"amount\":\"%s\"%s}";
        String noPinAtm = post(POS + "/api/withdraw", atmBody.formatted("100", ""));
        expect("the machine itself refuses a withdrawal without the PIN", contains(noPinAtm, "needs the PIN"));
        String tooMuch = post(POS + "/api/withdraw", atmBody.formatted("400", ",\"pin\":\"" + pin + "\""));
        expect("the bank refuses 400.00: its limit is 300.00 per withdrawal (code 61)", "DECLINED".equals(field(tooMuch, "status")) && "61".equals(field(tooMuch, "responseCode")));
        String cash = post(POS + "/api/withdraw", atmBody.formatted("100", ",\"pin\":\"" + pin + "\""));
        expect("the bank approved 100.00 in cash", "APPROVED".equals(field(cash, "status")));
        expect("NeoBank marks it as a cash withdrawal", contains(get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", demoToken), "\"cash\":true"));

        step("9b. An online shop: no card in a terminal and no PIN, the security code on the card instead");
        String wrongCode = "000".equals(cvv) ? "111" : "000";
        String wrongOnline = post(POS + "/api/pay-online", onlineBody("20.00", wrongCode));
        expect("a wrong security code is declined with code N7", "DECLINED".equals(field(wrongOnline, "status")) && "N7".equals(field(wrongOnline, "responseCode")));
        String rightOnline = post(POS + "/api/pay-online", onlineBody("20.00", cvv));
        expect("the right security code is approved", "APPROVED".equals(field(rightOnline, "status")));
        String onlineActivity = get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", demoToken);
        expect("NeoBank marks the payment as an online payment", contains(onlineActivity, "\"online\":true"));
        expect("the security code is nowhere in what the shop or the bank shows",
                !wrongOnline.contains("\"" + cvv + "\"") && !rightOnline.contains(": " + cvv) && !contains(onlineActivity, "cvv"));
        System.out.println("   (the wrong code counted like a wrong PIN: the next payment with the PIN resets the counter)\n");

        step("9c. An online payment above 30.00 needs the customer's approval (a simulation of 3-D Secure: the bank asks, the customer answers in the app)");
        String needsApproval = post(POS + "/api/pay-online", onlineBody("80.00", cvv));
        String reference = field(needsApproval, "challenge");
        expect("the bank declines with 1A and a reference for the customer to approve", "DECLINED".equals(field(needsApproval, "status")) && "1A".equals(field(needsApproval, "responseCode")) && reference != null);
        String waiting = get(BANK + "/api/cards/authentications", demoToken);
        expect("the customer sees the request in the app (shop, amount and the same reference)",
                contains(waiting, "\"reference\":\"" + reference + "\"") && contains(waiting, "\"amount\":\"80.00\"") && contains(waiting, "Corner Cafe"));
        String early = post(POS + "/api/pay-online", onlineBody("80.00", cvv, reference));
        expect("trying again before the customer answered is asked again (1A, another reference)",
                "1A".equals(field(early, "responseCode")) && field(early, "challenge") != null && !reference.equals(field(early, "challenge")));
        expect("the customer approves: no content", answer(waiting, reference, "approve", demoToken) == 204);
        String paid = post(POS + "/api/pay-online", onlineBody("80.00", cvv, reference));
        expect("the shop tries again with the reference: approved", "APPROVED".equals(field(paid, "status")));
        String reused = post(POS + "/api/pay-online", onlineBody("80.00", cvv, reference));
        expect("the same approval cannot pay twice (1A again, a new reference)",
                "1A".equals(field(reused, "responseCode")) && field(reused, "challenge") != null && !reference.equals(field(reused, "challenge")));
        String other = post(POS + "/api/pay-online", onlineBody("80.00", cvv));
        String otherRef = field(other, "challenge");
        String waitingAgain = get(BANK + "/api/cards/authentications", demoToken);
        expect("the customer rejects the next one: no content", otherRef != null && answer(waitingAgain, otherRef, "reject", demoToken) == 204);
        String refused = post(POS + "/api/pay-online", onlineBody("80.00", cvv, otherRef));
        expect("a rejected request is refused for good (05)", "DECLINED".equals(field(refused, "status")) && "05".equals(field(refused, "responseCode")));
        expect("an approval for 80.00 does not pay 80.01", "1A".equals(field(post(POS + "/api/pay-online", onlineBody("80.01", cvv, reference)), "responseCode")));

        step("10. A fuel pump: 50.00 is authorized first, the pump asks for 25.00 more, and an addition that is too much is refused");
        String pump = pay("50.00", GAS, pin);
        expect("the pump's first authorization is approved", "APPROVED".equals(field(pump, "status")));
        String pumpId = field(pump, "id");
        String addition = post(POS + "/api/increase", "{\"id\":\"" + pumpId + "\",\"amount\":\"25.00\"}");
        expect("the bank approved 25.00 more: 75.00 is now authorized", "APPROVED".equals(field(addition, "status")) && contains(addition, "75.00 is now authorized"));
        String tooMuchMore = post(POS + "/api/increase", "{\"id\":\"" + pumpId + "\",\"amount\":\"2500.00\"}");
        expect("the bank refuses an addition that would take the payment over its 2,500.00 limit (code 12)",
                "DECLINED".equals(field(tooMuchMore, "status")) && "12".equals(field(tooMuchMore, "responseCode")));
        expect("NeoBank holds the 25.00 addition as a pending hold of its own", contains(get(BANK + "/api/cards/" + cardId + "/authorizations?pending=true", demoToken), "\"amount\":\"25.00\""));
        String pumpFinal = post(POS + "/api/adjust", "{\"id\":\"" + pumpId + "\",\"amount\":\"61.40\"}");
        expect("the pump used less than it asked for: the payment will clear for 61.40", "61.40".equals(field(pumpFinal, "finalAmount")));

        step("11. End of the shop's day: CLEARING. The shop's bank sends one file with the final amounts");
        int postedBefore = captured();
        String close = post(POS + "/api/close-day", "");
        System.out.println("   " + field(close, "message"));
        String batchFile = field(close, "fileName");
        expect("a clearing file was sent", batchFile != null);
        waitUntil("the network accepted the file", () -> "ACCEPTED".equals(batchStatus(batchFile)), 60);
        expect("the network accepted it (and NeoBank answered for every item)", "ACCEPTED".equals(batchStatus(batchFile)));
        waitUntil("NeoBank posted the cleared payments", () -> captured() >= postedBefore + 8, 60);
        expect("NeoBank POSTED the cleared payments (25.00, 5.00, 46.00, the stand-in 20.00, the 100.00 cash, the 20.00 online payment, the approved 80.00 online payment and the 61.40 pump): holds became real debits", captured() >= postedBefore + 8);
        String afterClearing = get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", demoToken);
        expect("the pump cleared for 61.40, the total it really used", contains(afterClearing, "\"capturedAmount\":\"61.40\""));
        expect("the 25.00 addition no longer holds money: it was merged into the pump payment", contains(afterClearing, "\"status\":\"MERGED\""));
        expect("the tip was taken: the dinner posted 46.00", contains(get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", demoToken), "\"capturedAmount\":\"46.00\""));
        waitUntil("the shop sees its payments cleared", () -> "CLEARED".equals(posStatus(coffeeId)) && "CLEARED".equals(posStatus(standInId)), 30);

        step("12. A refund of 10.00 for the 25.00 purchase");
        String refund = post(POS + "/api/refund", "{\"id\":\"" + coffeeId + "\",\"amount\":\"10.00\"}");
        expect("the refund is recorded and goes out with the next clearing file", "REFUND_DUE".equals(field(refund, "status")));

        step("13. END OF DAY: the refund is cleared, then the network SETTLES the day between the banks (net amounts)");
        String close2 = post(POS + "/api/close-day", "");
        String file2 = field(close2, "fileName");
        waitUntil("the refund file was accepted", () -> "ACCEPTED".equals(batchStatus(file2)), 60);
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String run = post(NET + "/settlement/run", "");
            if (!"true".equals(field(run, "settled"))) {
                break;
            }
            runs.add(field(run, "runNumber"));
            System.out.println("   " + field(run, "message"));
            Matcher lines = Pattern.compile("\"participant\":\"([^\"]+)\",\"role\":\"([A-Z]+)\",\"purchases\":([-0-9.]+),\"refunds\":([-0-9.]+),\"interchange\":([-0-9.]+),\"schemeFee\":([-0-9.]+),\"net\":([-0-9.]+)").matcher(run);
            while (lines.find()) {
                System.out.printf("     %-9s %-7s purchases %8s  refunds %6s  interchange %6s  scheme fee %6s  NET %9s%n", lines.group(2), lines.group(1),
                        lines.group(3), lines.group(4), lines.group(5), lines.group(6), lines.group(7));
            }
        }
        expect("the network settled the cleared items", !runs.isEmpty());
        for (String run : runs) {
            waitUntil("NeoBank decided statement " + run, () -> contains(get(BANK + "/api/admin/settlement", adminToken), "\"runNumber\":\"" + run + "\""), 60);
            expect("NeoBank checked the figures itself and MATCHED " + run, matched(run));
        }

        step("14. DISPUTE 1: the customer says the 46.00 dinner never arrived; the shop's bank answers with evidence");
        BigDecimal ledgerBefore = ledger();
        String dinnerAuthorization = find(get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", demoToken),
                "(?s)\\{\"id\":\"([^\"]+)\"(?:(?!\\{\"id\").)*?\"capturedAmount\":\"46\\.00\"");
        String filed = post(BANK + "/api/cards/payments/" + dinnerAuthorization + "/dispute", "{\"reason\":\"NOT_RECEIVED\",\"note\":\"The dinner never arrived\"}", demoToken);
        expect("the dispute is filed", "FILED".equals(field(filed, "status")));
        expect("the customer got the 46.00 back at once (provisional credit)", ledger().subtract(ledgerBefore).compareTo(new BigDecimal("46.00")) == 0);
        waitUntil("the network accepted the chargeback", () -> "OPEN".equals(disputeStatus(dinnerAuthorization)), 90);
        expect("the card network accepted the chargeback and the dispute is open", "OPEN".equals(disputeStatus(dinnerAuthorization)));
        waitUntil("the shop received the chargeback", () -> contains(get(POS + "/api/disputes", null), "\"status\":\"RECEIVED\""), 60);
        String chargeback = get(POS + "/api/disputes", null);
        expect("the shop sees the chargeback (reason: not received)", contains(chargeback, "\"status\":\"RECEIVED\"") && contains(chargeback, "NOT_RECEIVED"));
        String chargebackId = find(chargeback, "\"id\":\"([^\"]+)\"");
        post(POS + "/api/represent", "{\"id\":\"" + chargebackId + "\",\"note\":\"Signed receipt and delivery note on file\"}");
        waitUntil("the dispute was lost", () -> "LOST".equals(disputeStatus(dinnerAuthorization)), 90);
        expect("the shop proved its case: the customer LOST the dispute", "LOST".equals(disputeStatus(dinnerAuthorization)));
        expect("the 46.00 was taken from the customer again", ledger().compareTo(ledgerBefore) == 0);

        step("15. DISPUTE 2: another payment is disputed and nobody answers: after the window the customer WINS");
        String coffeeDispute = post(BANK + "/api/cards/payments/" + coffeeAuthorizationId() + "/dispute", "{\"reason\":\"UNAUTHORIZED\",\"note\":\"I did not make this payment\"}", demoToken);
        expect("the second dispute is filed", "FILED".equals(field(coffeeDispute, "status")));
        String coffeeAuth = field(coffeeDispute, "authorizationId");
        waitUntil("the second dispute was won", () -> "WON".equals(disputeStatus(coffeeAuth)), 120);
        expect("nobody answered in time: the customer WON and the credit is final", "WON".equals(disputeStatus(coffeeAuth)));

        step("16. The chargebacks and the representment are settled between the banks like refunds and purchases");
        List<String> disputeRuns = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String run = post(NET + "/settlement/run", "");
            if (!"true".equals(field(run, "settled"))) {
                break;
            }
            disputeRuns.add(field(run, "runNumber"));
            System.out.println("   " + field(run, "message"));
        }
        expect("the network settled the dispute items", !disputeRuns.isEmpty());
        for (String run : disputeRuns) {
            waitUntil("NeoBank decided statement " + run, () -> contains(get(BANK + "/api/admin/settlement", adminToken), "\"runNumber\":\"" + run + "\""), 60);
            expect("NeoBank checked the figures itself and MATCHED " + run, matched(run));
        }

        step("17. What NeoBank says about all of it (admin views)");
        String clearing = get(BANK + "/api/admin/clearing", adminToken);
        System.out.println("   clearing books check: " + find(clearing, "\"booksMatch\":(true|false)") + ", owed to the card network " + find(clearing, "\"settlementPayable\":\"([^\"]+)\""));
        expect("the clearing books agree with the posted items", contains(clearing, "\"booksMatch\":true"));
        String recon = get(BANK + "/api/admin/reconciliation", adminToken);
        expect("the ledger balances (every entry sums to zero)", contains(recon, "\"healthy\":true"));
        String settlement = get(BANK + "/api/admin/settlement", adminToken);
        expect("the ledger agrees with the cleared and settled items", contains(settlement, "\"ledgerAgrees\":true"));

        System.out.println("\nLook at it yourself: the bank http://127.0.0.1:4200 (demo@neobank.local / DemoPassw0rd: Cards, History; admin@neobank.local / AdminPassw0rd: Clearing, Settlement, Disputes),");
        System.out.println("the shop http://127.0.0.1:9000, and the files in the clearing volume (docs/DEMO-SCRIPT.md says where to look).");
        if (failures.isEmpty()) {
            System.out.println("\nAll expectations met.");
        } else {
            System.out.println("\n" + failures.size() + " expectation(s) NOT met:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    // ---- the narrative ----

    static void step(String text) {
        System.out.println("\n" + text);
    }

    static void expect(String what, boolean ok) {
        System.out.println("   " + (ok ? "[ok]   " : "[FAIL] ") + what);
        if (!ok) {
            failures.add(what);
        }
    }

    // ---- disputes ----

    static BigDecimal ledger() throws Exception {
        return new BigDecimal(find(get(BANK + "/api/cards", bankToken), "\"ledgerBalance\":\"([^\"]+)\""));
    }

    /** The state of the customer's dispute of one payment. */
    static String disputeStatus(String authorizationId) throws Exception {
        Matcher m = Pattern.compile("\"authorizationId\":\"" + Pattern.quote(authorizationId) + "\"[^{}]*?\"status\":\"([A-Z]+)\"").matcher(get(BANK + "/api/cards/disputes", bankToken));
        return m.find() ? m.group(1) : null;
    }

    /** The authorization of the 25.00 coffee of step 1 (the posted payment that was partly refunded). */
    static String coffeeAuthorizationId() throws Exception {
        return find(get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", bankToken),
                "(?s)\\{\"id\":\"([^\"]+)\"(?:(?!\\{\"id\").)*?\"capturedAmount\":\"25\\.00\"");
    }

    // ---- the stack ----

    static void waitForTheStack() throws Exception {
        System.out.print("Waiting for the stack");
        for (int i = 0; i < 120; i++) {
            try {
                if (contains(get(NET + "/status", null), "\"issuerLink\":\"UP\"") && contains(get(POS + "/api/config", null), "demoCard")
                        && contains(get(BANK + "/actuator/health", null), "UP")) {
                    System.out.println(" ... up.\n");
                    return;
                }
            } catch (Exception e) {
                // not up yet
            }
            System.out.print(".");
            Thread.sleep(2000);
        }
        System.out.println();
        System.err.println("The stack did not come up: is `docker compose up --build` running (and finished building)?");
        System.exit(2);
    }

    static int holds() throws Exception {
        return Integer.parseInt(find(get(BANK + "/api/cards/" + cardId + "/authorizations?pending=true", bankToken), "\"totalElements\":(\\d+)"));
    }

    static int captured() throws Exception {
        String json = get(BANK + "/api/cards/" + cardId + "/authorizations?size=50", bankToken);
        Matcher m = Pattern.compile("\"status\":\"CAPTURED\"").matcher(json);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static boolean matched(String run) throws Exception {
        String json = get(BANK + "/api/admin/settlement", adminToken);
        int at = json.indexOf("\"runNumber\":\"" + run + "\"");
        int status = json.indexOf("\"status\":\"", at);
        return at >= 0 && json.startsWith("MATCHED", status + "\"status\":\"".length());
    }

    static String posStatus(String id) throws Exception {
        String json = get(POS + "/api/transactions", null);
        Matcher m = Pattern.compile("\"id\":\"" + Pattern.quote(id) + "\"[^}]*?\"status\":\"([A-Z_]+)\"").matcher(json);
        return m.find() ? m.group(1) : "?";
    }

    static String batchStatus(String fileName) throws Exception {
        String json = get(POS + "/api/batches", null);
        Matcher m = Pattern.compile("\"fileName\":\"" + Pattern.quote(fileName) + "\"[^}]*?\"status\":\"([A-Z]+)\"").matcher(json);
        return m.find() ? m.group(1) : "?";
    }

    static String onlineBody(String amount, String code) {
        return onlineBody(amount, code, null);
    }

    static String onlineBody(String amount, String code, String authentication) {
        return "{\"pan\":\"" + pan + "\",\"expiry\":\"" + expiry + "\",\"cvv\":\"" + code + "\",\"amount\":\"" + amount + "\",\"merchantId\":\"" + CAFE + "\""
                + (authentication == null ? "" : ",\"authentication\":\"" + authentication + "\"") + "}";
    }

    /** The customer answers a request in the bank's app (approve or reject); returns the HTTP status. */
    static int answer(String waitingJson, String reference, String what, String token) throws Exception {
        String id = find(waitingJson, "\"id\":\"([^\"]+)\",\"reference\":\"" + reference + "\"");
        HttpRequest request = HttpRequest.newBuilder(URI.create(BANK + "/api/cards/authentications/" + id + "/" + what)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static String pay(String amount, String shop, String pinOrNull) throws Exception {
        String body = "{\"pan\":\"" + pan + "\",\"expiry\":\"" + expiry + "\",\"amount\":\"" + amount + "\",\"merchantId\":\"" + shop + "\""
                + (pinOrNull == null ? "" : ",\"pin\":\"" + pinOrNull + "\"") + "}";
        String result = post(POS + "/api/pay", body);
        System.out.println("   paid " + amount + " -> " + field(result, "status") + (field(result, "responseCode") == null ? "" : " (code " + field(result, "responseCode") + ")"));
        return result;
    }

    static String login(String email, String password) throws Exception {
        String json = post(BANK + "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        return find(json, "\"accessToken\":\"([^\"]+)\"");
    }

    /** A condition that may fail while the stack is still busy (then it simply is not true yet). */
    interface Check {
        boolean ok() throws Exception;
    }

    static void waitUntil(String what, Check condition, int seconds) throws Exception {
        for (int i = 0; i < seconds; i++) {
            try {
                if (condition.ok()) {
                    return;
                }
            } catch (Exception e) {
                // keep waiting
            }
            Thread.sleep(1000);
        }
        System.out.println("   (gave up waiting: " + what + ")");
    }

    // ---- small HTTP and JSON helpers (no libraries: the answers are flat enough for regular expressions) ----

    static String get(String url, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String url, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String url, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false|null|[-0-9.]+)").matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(1).equals("null") ? null : m.group(1);
    }

    static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("not found in the answer: " + regex);
        }
        return m.group(1);
    }

    static boolean contains(String text, String part) {
        return text != null && text.contains(part);
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
