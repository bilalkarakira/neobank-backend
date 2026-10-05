package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.TestClockConfiguration;
import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The TCP side: sign-on, network management, framing, hostile input, lock timeouts, and no PAN anywhere it should not be. */
@SpringBootTest(properties = { "app.cards.listener.enabled=true", "app.cards.listener.port=0", "app.cards.velocity-max=1000" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
@ExtendWith(OutputCaptureExtension.class)
class IssuerListenerTest extends IssuerTestBase {

    @Autowired
    DataSource dataSource;

    private Message networkMessage(String code) {
        Message message = Message.of("0800").set(7, "1005120000").set(11, nextStan());
        return code == null ? message : message.set(70, code);
    }

    // ---- network management ----

    @Test
    void signOnEchoAndSignOffAnswerWithTheirOwnElementsEchoed() throws Exception {
        try (Terminal terminal = new Terminal()) {
            Message signOn = networkMessage("001");
            Message response = terminal.exchange(signOn);
            assertThat(response.mti()).isEqualTo("0810");
            assertThat(code(response)).isEqualTo("00");
            assertThat(response.get(7)).isEqualTo(signOn.get(7));
            assertThat(response.get(11)).isEqualTo(signOn.get(11));
            assertThat(response.get(70)).isEqualTo("001");

            assertThat(code(terminal.exchange(networkMessage("301")))).isEqualTo("00");
            assertThat(code(terminal.exchange(networkMessage("002")))).isEqualTo("00");
        }
    }

    @Test
    void echoWorksWithoutASignOn() throws Exception {
        try (Terminal terminal = new Terminal()) {
            assertThat(code(terminal.exchange(networkMessage("301")))).isEqualTo("00");
        }
    }

    @Test
    void anUnknownNetworkCodeIsInvalidAndAMissingOneIsAFormatError() throws Exception {
        try (Terminal terminal = new Terminal()) {
            assertThat(code(terminal.exchange(networkMessage("999")))).isEqualTo("12");
            assertThat(code(terminal.exchange(networkMessage(null)))).isEqualTo("30");
        }
    }

    @Test
    void paymentsAndReversalsNeedASignOnOnTheirConnection() throws Exception {
        Fixture f = fixture("listen1@example.com", "100.00");
        Message request = purchase(f, "10.00");

        try (Terminal terminal = new Terminal()) {
            assertThat(code(terminal.exchange(request))).as("before sign-on").isEqualTo("91");
            assertThat(code(terminal.exchange(reversalOf(request)))).as("reversal before sign-on").isEqualTo("91");
            assertThat(rows("true")).isZero();

            terminal.exchange(networkMessage("001"));
            assertThat(code(terminal.exchange(request))).isEqualTo("00");

            terminal.exchange(networkMessage("002"));
            assertThat(code(terminal.exchange(purchase(f, "10.00")))).as("after sign-off").isEqualTo("91");
        }
        try (Terminal another = new Terminal()) {
            assertThat(code(another.exchange(purchase(f, "10.00")))).as("sign-on belongs to one connection").isEqualTo("91");
        }
        assertThat(rows("true")).isEqualTo(1);
    }

    // ---- framing and hostile input ----

    @Test
    void aSplitFrameDeliveredByteByByteIsHandled() throws Exception {
        Fixture f = fixture("listen2@example.com", "100.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            byte[] message = packager.pack(purchase(f, "10.00"));
            byte[] framed = new byte[message.length + 2];
            framed[0] = (byte) (message.length >>> 8);
            framed[1] = (byte) message.length;
            System.arraycopy(message, 0, framed, 2, message.length);
            for (byte b : framed) {
                terminal.out.write(b);
                terminal.out.flush();
            }

            assertThat(code(terminal.receive())).isEqualTo("00");
        }
    }

    @Test
    void severalRequestsSentAtOnceAreAnsweredInOrder() throws Exception {
        Fixture f = fixture("listen3@example.com", "100.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            List<Message> requests = List.of(purchase(f, "1.00"), purchase(f, "2.00"), purchase(f, "3.00"));
            for (Message request : requests) {
                Frame.write(terminal.out, packager.pack(request));
            }
            for (Message request : requests) {
                Message response = terminal.receive();
                assertThat(response.get(11)).isEqualTo(request.get(11));
                assertThat(code(response)).isEqualTo("00");
            }
        }
    }

    @Test
    void hostileInputClosesTheConnectionAndNeverHarmsTheServer() throws Exception {
        Fixture f = fixture("listen4@example.com", "100.00");
        byte[] valid = packager.pack(purchase(f, "10.00"));
        long before = server.closedForBadInput();
        List<byte[]> hostile = new ArrayList<>();
        hostile.add("HELLO WORLD, THIS IS NOT ISO 8583".getBytes(StandardCharsets.US_ASCII));
        hostile.add(java.util.Arrays.copyOf(valid, valid.length - 1));                      // truncated message
        hostile.add(new byte[] { 0x00, 0x01, (byte) 0xFF, (byte) 0xFE });                   // binary junk
        hostile.add(("0100" + "8".repeat(16)).getBytes(StandardCharsets.US_ASCII));         // bitmap promises fields that are not there
        hostile.add(packager.pack(Message.of("0300").set(11, "000001")));                   // well formed, not handled
        for (byte[] bytes : hostile) {
            try (Terminal terminal = new Terminal().signedOn()) {
                Frame.write(terminal.out, bytes);
                assertThat(terminal.closedByIssuer()).as("closed after " + new String(bytes, StandardCharsets.ISO_8859_1).substring(0, 4)).isTrue();
            }
        }
        // a frame that announces more than the maximum, and a frame of length 0
        for (byte[] header : new byte[][] { { (byte) 0xFF, (byte) 0xFF }, { 0x00, 0x00 }, { 0x10, 0x01 } }) {
            try (Terminal terminal = new Terminal().signedOn()) {
                terminal.out.write(header);
                terminal.out.flush();
                assertThat(terminal.closedByIssuer()).isTrue();
            }
        }
        // the connection ends in the middle of a frame: nothing to answer, nothing broken
        try (Terminal terminal = new Terminal().signedOn()) {
            terminal.out.write(new byte[] { 0x00, 0x50, '0', '1' });
            terminal.out.flush();
        }

        assertThat(server.closedForBadInput() - before).isGreaterThanOrEqualTo(hostile.size() + 3);
        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(purchase(f, "10.00")))).as("the server still works").isEqualTo("00");
        }
        assertThat(rows("true")).isEqualTo(1);
    }

    @Test
    void manyConnectionsAtOnceAllGetAnswered() throws Exception {
        Fixture f = fixture("listen5@example.com", "1000.00");
        List<Terminal> terminals = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                terminals.add(new Terminal().signedOn());
            }
            for (Terminal terminal : terminals) {
                assertThat(code(terminal.exchange(purchase(f, "1.00")))).isEqualTo("00");
            }
        } finally {
            for (Terminal terminal : terminals) {
                terminal.close();
            }
        }
    }

    // ---- failure of the issuer itself ----

    @Test
    void anAccountLockedForTooLongMeansSystemErrorAndNoHold() throws Exception {
        Fixture f = fixture("listen6@example.com", "100.00");
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("select id from accounts where id = ? for update")) {
                lock.setObject(1, UUID.fromString(f.account().id()));
                lock.executeQuery();

                long started = System.nanoTime();
                try (Terminal terminal = new Terminal().signedOn()) {
                    assertThat(code(terminal.exchange(purchase(f, "10.00")))).isEqualTo("96");
                }
                long millis = (System.nanoTime() - started) / 1_000_000;
                assertThat(millis).as("waited for the 2 s lock timeout, not forever").isBetween(1_500L, 8_000L);
            } finally {
                blocker.rollback();
            }
        }

        assertThat(rows("true")).as("nothing recorded").isZero();
        assertThat(available(f)).isEqualByComparingTo("100.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            assertThat(code(terminal.exchange(purchase(f, "10.00")))).as("and it recovers").isEqualTo("00");
        }
    }

    // ---- the PAN stays out of logs and tables ----

    @Test
    void noPanAppearsInTheLogsOrInAnyTableWhateverHappens(CapturedOutput output) throws Exception {
        Fixture f = fixture("leak@example.com", "100.00");
        String unknown = "999900123456789" + Luhn.checkDigit("999900123456789");
        Message approved = purchase(f, "10.00");
        try (Terminal terminal = new Terminal().signedOn()) {
            terminal.exchange(approved);
            terminal.exchange(approved);                                                      // duplicate
            terminal.exchange(purchase(f, "9999.00"));                                        // 61
            terminal.exchange(withPin(purchase(f, "5.00"), "0000".equals(f.pin()) ? "1111" : "0000")); // 55
            terminal.exchange(purchaseWith(unknown, f.expiry(), "5.00", nextStan(), "000000000042")); // 14
            terminal.exchange(reversalOf(approved));
            terminal.exchange(reversalOf(purchaseWith(unknown, f.expiry(), "5.00", nextStan(), "000000000043"))); // tombstone
        }
        try (Terminal terminal = new Terminal().signedOn()) {
            Frame.write(terminal.out, "garbage".getBytes(StandardCharsets.US_ASCII));
            terminal.closedByIssuer();
        }

        String logs = output.getAll();
        String tables = jdbc.queryForObject("""
                select coalesce((select string_agg((to_jsonb(a) - 'pan_hash')::text, ' ') from authorizations a), '') || ' ' ||
                       coalesce((select string_agg((to_jsonb(c) - 'pan_hash' - 'pin_hash')::text, ' ') from cards c), '') || ' ' ||
                       coalesce((select string_agg(row_to_json(n)::text, ' ') from notifications n), '') || ' ' ||
                       coalesce((select string_agg(row_to_json(t)::text, ' ') from transactions t), '')
                """, String.class);
        for (String pan : List.of(f.pan(), unknown)) {
            assertThat(logs).as("logs contain a PAN").doesNotContain(pan);
            assertThat(tables).as("tables contain a PAN").doesNotContain(pan);
        }
        assertThat(luhnNumbersIn(logs)).as("any card-like number in the logs").isEmpty();
        assertThat(luhnNumbersIn(tables)).as("any card-like number in the tables").isEmpty();
    }

    /** Runs of 13-19 digits that pass the Luhn check: what a scanner for card numbers would flag. */
    private static List<String> luhnNumbersIn(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)").matcher(text);
        while (matcher.find()) {
            if (Luhn.isValid(matcher.group())) {
                found.add(matcher.group().substring(0, 6) + "..." );
            }
        }
        return found;
    }
}
