package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.Pans;
import com.bilal.cardnet.shared.testing.Await;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bilal.cardnet.network.NetHarness.OTHER_PAN;
import static com.bilal.cardnet.network.NetHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;

/** Through every path (approval, decline, stand-in, late reversal, junk), no card number or PIN reaches a log or a table. */
class SecretsTest {

    private static List<String> luhnNumbersIn(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)").matcher(text);
        while (matcher.find()) {
            if (Pans.luhnValid(matcher.group())) {
                found.add(matcher.group().substring(0, 6) + "...");
            }
        }
        return found;
    }

    @Test
    void noNumberAndNoPinIsLoggedOrStoredInTheClear() throws Exception {
        List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                if (record.getParameters() != null) {
                    message = java.text.MessageFormat.format(message, record.getParameters());
                }
                logs.add(message);
                if (record.getThrown() != null) {
                    logs.add(String.valueOf(record.getThrown()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // only OUR loggers: the JDK and the libraries log things of their own (certificate dumps, for one) that contain long digit runs
        Logger root = Logger.getLogger("com.bilal.cardnet");
        Level before = root.getLevel();
        root.setLevel(Level.ALL);
        root.addHandler(capture);
        try (NetHarness h = new NetHarness()) {
            Message withPin = h.purchase(PAN, "5.00").set(52, "0123456789ABCDEF");
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {
                acquirer.exchange(h.purchase(PAN, "25.50"));                    // approved by the issuer
                h.issuer.decision = m -> "51";
                acquirer.exchange(h.purchase(PAN, "25.50"));                    // declined
                h.issuer.decision = m -> "00";
                acquirer.exchange(h.purchase("4111111111111111", "5.00"));      // no issuer
                h.issuer.stop();
                h.waitForLinkDown();
                acquirer.exchange(h.purchase(OTHER_PAN, "10.00"));              // stand-in approval
                acquirer.exchange(withPin);                                     // stand-in refused (PIN)
                h.issuer.restart();
                h.waitForLink();
                Await.until("advice delivered", () -> h.count("select count(*) from net_standin_advices where status = 'DELIVERED'") == 1);
                h.issuer.responseDelayMs = 1200;
                acquirer.exchange(h.purchase(PAN, "80.00"));                    // late approval, reversed
                Await.until("late reversal done", () -> h.count("select count(*) from pending_reversals where status = 'DONE'") == 1);
            }
            try (NetHarness.Acquirer acquirer = h.acquirer().signedOn()) {      // junk
                Frame.write(acquirer.out, ("junk " + PAN).getBytes(StandardCharsets.US_ASCII));
                acquirer.closedByNetwork();
            }

            String tables = h.scalar("""
                    select coalesce((select string_agg((to_jsonb(t) - 'request_ciphertext')::text, ' ') from net_transactions t), '') || ' ' ||
                           coalesce((select string_agg((to_jsonb(t) - 'request_ciphertext')::text, ' ') from net_standin_advices t), '') || ' ' ||
                           coalesce((select string_agg((to_jsonb(t) - 'request_ciphertext')::text, ' ') from pending_reversals t), '')
                    """);
            String bytes = h.scalar("select coalesce(string_agg(encode(request_ciphertext, 'escape'), ' '), '') from "
                    + "(select request_ciphertext from net_standin_advices union all select request_ciphertext from pending_reversals) x");
            String allLogs = String.join("\n", logs);

            assertThat(allLogs).as("something was logged at all").isNotEmpty();
            for (String pan : List.of(PAN, OTHER_PAN, "4111111111111111")) {
                assertThat(allLogs).as("logs hold " + Pans.mask(pan)).doesNotContain(pan);
                assertThat(tables).as("tables hold " + Pans.mask(pan)).doesNotContain(pan);
                assertThat(bytes).as("ciphertext holds " + Pans.mask(pan)).doesNotContain(pan);
            }
            assertThat(allLogs).doesNotContain("0123456789ABCDEF");
            assertThat(tables).doesNotContain("0123456789ABCDEF");
            assertThat(luhnNumbersIn(allLogs)).as("card-like numbers in the logs").isEmpty();
            assertThat(luhnNumbersIn(tables)).as("card-like numbers in the tables").isEmpty();
            assertThat(h.scalar("select string_agg(masked_pan, ',') from net_transactions where masked_pan is not null")).contains("999900******0012");
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
        }
    }
}
