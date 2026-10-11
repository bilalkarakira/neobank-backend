package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.iso8583.PinBlock;
import com.bilal.cardnet.shared.Pans;
import com.bilal.cardnet.shared.testing.Await;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bilal.cardnet.acquirer.AcqHarness.CAFE;
import static com.bilal.cardnet.acquirer.AcqHarness.PAN;
import static org.assertj.core.api.Assertions.assertThat;

/** Through approval, decline, the no-answer reversal, void and resend: no card number or PIN in the logs, the tables or the results. */
class AcquirerSecretsTest {

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
    void noNumberAndNoPinIsLoggedStoredOrShownInTheClear() throws Exception {
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
        try (AcqHarness h = new AcqHarness()) {
            List<AcquirerService.Result> results = new ArrayList<>();
            results.add(h.service().pay(h.payWithPin("25.50")));
            h.issuer.decision = m -> "55";
            results.add(h.service().pay(h.payWithPin("9.00")));
            h.issuer.decision = m -> "00";
            AcquirerService.Result paid = h.service().pay(h.pay("12.00"));
            results.add(paid);
            results.add(h.service().resendLast());
            results.add(h.service().voidSale(paid.id()));
            h.network.chaos().acquirerResponseDelayMs(1800);
            results.add(h.service().pay(h.payWithPin("7.00")));                 // no answer: a queued reversal
            h.network.chaos().acquirerResponseDelayMs(0);
            Await.until("everything is reversed", () -> h.count("select count(*) from pending_reversals where status = 'DONE'") == 2);
            try {
                h.service().pay(new AcquirerService.PayRequest(PAN + "x", "10/29", "5.00", CAFE, "4321"));
            } catch (AcquirerService.BadRequestException expected) {
                // refused: the refusal must not echo the number either
                assertThat(expected.getMessage()).doesNotContain(PAN);
            }

            StringBuilder shown = new StringBuilder();
            StringBuilder dumps = new StringBuilder();
            for (AcquirerService.Result r : results) {
                shown.append(r.message()).append(r.requestText()).append(r.responseText());
                dumps.append(r.requestHex()).append(r.responseHex());
            }
            String tables = h.scalar("""
                    select coalesce((select string_agg((to_jsonb(t) - 'request_ciphertext')::text, ' ') from acq_transactions t), '') || ' ' ||
                           coalesce((select string_agg((to_jsonb(t) - 'request_ciphertext')::text, ' ') from pending_reversals t), '') || ' ' ||
                           coalesce((select string_agg((to_jsonb(t) - 'request_ciphertext')::text, ' ') from net_transactions t), '')
                    """);
            String ciphertexts = h.scalar("select coalesce(string_agg(encode(c, 'escape'), ' '), '') from "
                    + "(select request_ciphertext c from acq_transactions union all select request_ciphertext from pending_reversals) x");
            String pinBlock = PinBlock.encode("4321", PAN);
            String allLogs = String.join("\n", logs);

            assertThat(allLogs).as("something was logged").isNotEmpty();
            String[] labels = { "logs", "tables", "shown" };
            int label = 0;
            for (String where : new String[] { allLogs, tables, shown.toString() }) {
                assertThat(where).doesNotContain(PAN).doesNotContain(pinBlock);
                assertThat(luhnNumbersIn(where)).as(labels[label++]).isEmpty();
            }
            // ciphertext is random bytes: a card-number scan means nothing there, only the literal number is checked
            assertThat(ciphertexts).doesNotContain(PAN).doesNotContain(pinBlock);
            // the hex dumps strings fixed-width numeric fields together, so a Luhn scan would flag chance matches there; they are checked
            // exactly instead (and byte by byte in RedactTest): not the number and not the PIN block
            assertThat(dumps.toString()).doesNotContain(PAN).doesNotContain(pinBlock);
            assertThat(tables).doesNotContain("4321\"").as("the PIN is not in any row");
            assertThat(shown.toString()).contains("999900******0012");
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
        }
    }
}
