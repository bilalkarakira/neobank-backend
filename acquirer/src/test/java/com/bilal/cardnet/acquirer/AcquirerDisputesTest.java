package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.acquirer.AcquirerService.BadRequestException;
import com.bilal.cardnet.acquirer.AcquirerService.ConflictException;
import com.bilal.cardnet.acquirer.AcquirerService.NotFoundException;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingAck;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.cardnet.iso8583.clearing.ClearingFileCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The acquirer's side of disputes: real network processor, real files, fake issuer. */
class AcquirerDisputesTest {

    @TempDir
    Path root;

    final ClearingFileCodec codec = ClearingFileCodec.withSecret("acq-test-secret");

    private AcqHarness harness() throws Exception {
        return new AcqHarness(root, c -> { }, n -> { });
    }

    /** A payment that went through authorization, the day's file and the issuer's posting: CLEARED. */
    private UUID cleared(AcqHarness h, String amount) {
        UUID id = h.payApproved(amount);
        h.acquirer.clearing().closeDay().orElseThrow();
        h.pumpUntil("payment cleared", () -> h.status(id).equals("CLEARED"));
        return id;
    }

    private Message chargeback(AcqHarness h, UUID payment, String amount, String reason) {
        Message o = h.acquirer.service().store().request(payment);
        return Message.of("1442").set(2, o.get(2)).set(3, "000000").set(4, amount).set(7, o.get(7)).set(11, o.get(11)).set(12, o.get(12))
                .set(13, o.get(13)).set(32, o.get(32)).set(37, o.get(37)).set(41, o.get(41)).set(42, o.get(42)).set(49, o.get(49))
                .set(48, "CB|" + reason + "|customer says no");
    }

    /** The issuer files chargebacks; the network checks and forwards; the acquirer picks them up. */
    private void filed(AcqHarness h, String fileId, Message... items) {
        codec.write(h.dirs.issuerOut(), fileId + ".dsp", new ClearingFile(fileId, LocalDate.now(ZoneOffset.UTC), "999900", "NET", "USD", List.of(items)));
        beat(h);
    }

    private void beat(AcqHarness h) {
        for (int i = 0; i < 3; i++) {
            h.network.disputes().tick();
            h.acquirer.disputes().tick();
        }
    }

    private List<Path> dsp(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".dsp")).sorted().toList();
        }
    }

    private UUID onlyDispute(AcqHarness h) {
        return UUID.fromString(h.scalar("select id::text from acq_disputes"));
    }

    // ---- receiving ----

    @Test
    void aChargebackIsMatchedToItsPaymentRecordedAndAcknowledgedAndTheShopCanRepresentOnce() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "80.00");
            filed(h, "D1", chargeback(h, payment, "000000003000", "UNAUTHORIZED"));

            assertThat(h.count("select count(*) from acq_disputes")).isEqualTo(1);
            assertThat(h.scalar("select status || '/' || amount || '/' || reason || '/' || payment_id::text from acq_disputes"))
                    .isEqualTo("RECEIVED/30.00/UNAUTHORIZED/" + payment);
            ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve("X0000001.ack"));
            assertThat(ack.status()).isEqualTo(ClearingAck.Status.ACCEPTED);
            assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("CHARGEBACK_ACCEPTED");
            assertThat(h.scalar("select ack_status from net_dispute_forwards")).as("the network read our acknowledgement").isEqualTo("ACCEPTED");
            assertThat(h.scalar("select status from acq_transactions where id = ?", payment)).as("the payment itself is untouched").isEqualTo("CLEARED");

            AcquirerDisputes.Row row = h.acquirer.disputes().represent(onlyDispute(h), "Signed receipt 1234, goods handed over");

            assertThat(row.status()).isEqualTo("REPRESENTED");
            assertThat(row.representmentNote()).isEqualTo("Signed receipt 1234, goods handed over");
            List<Path> files = dsp(h.dirs.acquirerOut());
            assertThat(files).hasSize(1);
            ClearingFile file = codec.read(files.get(0));
            assertThat(file.fileId()).isEqualTo("R0000001");
            assertThat(file.sender()).isEqualTo("123456");
            assertThat(file.receiver()).isEqualTo("NET");
            Message rep = file.presentments().get(0);
            assertThat(rep.mti()).isEqualTo("1240");
            assertThat(rep.get(4)).isEqualTo("000000003000");
            assertThat(rep.get(48)).isEqualTo("REP|Signed receipt 1234, goods handed over");
            Message original = h.acquirer.service().store().request(payment);
            for (int de : new int[] {2, 11, 32, 37, 41, 42, 49}) {
                assertThat(rep.get(de)).as("DE %d", de).isEqualTo(original.get(de));
            }

            beat(h);

            assertThat(h.scalar("select rep_answer from acq_disputes")).isEqualTo("ACCEPTED");
            assertThat(h.scalar("select result from net_clearing_items where kind = 'REPRESENTMENT'")).isEqualTo("REPRESENTMENT_ACCEPTED");
            assertThat(h.acquirer.disputes().list(10)).singleElement().satisfies(r -> {
                assertThat(r.status()).isEqualTo("REPRESENTED");
                assertThat(r.representmentAnswer()).isEqualTo("ACCEPTED");
                assertThat(r.paymentAmount()).isEqualTo("80.00");
                assertThat(r.amount()).isEqualTo("30.00");
            });
        }
    }

    @Test
    void aChargebackForSomethingThisShopNeverSoldOrForTooMuchOrTwiceIsRefusedInTheAcknowledgement() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            Message unknown = chargeback(h, payment, "000000001000", "OTHER").set(37, "999999999999");
            filed(h, "D1", chargeback(h, payment, "000000001000", "OTHER"), unknown);
            assertThat(h.count("select count(*) from acq_disputes")).isEqualTo(1);
            // the network refuses the unknown one before it ever reaches us
            assertThat(h.count("select count(*) from acq_dispute_items")).isEqualTo(1);

            filed(h, "D2", chargeback(h, payment, "000000000500", "DUPLICATE"));

            assertThat(h.count("select count(*) from acq_disputes")).as("a payment has at most one chargeback").isEqualTo(1);
            assertThat(h.count("select count(*) from net_clearing_items where kind = 'CHARGEBACK' and result = 'CHARGEBACK_ACCEPTED'")).isEqualTo(1);
        }
    }

    @Test
    void whenTheNetworkPassesOnAChargebackWeCannotMatchWeRefuseThatItemInOurAcknowledgement() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            // a payment this shop has but that is no longer CLEARED (as if a refund or void changed it): the network still forwards the chargeback
            h.exec("update acq_transactions set status = 'REFUND_DUE' where id = ?", payment);
            filed(h, "D1", chargeback(h, payment, "000000001000", "OTHER"));

            ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve("X0000001.ack"));
            assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("REFUSED");
            assertThat(ack.items().get(0).reason()).startsWith("UNKNOWN_PAYMENT");
            assertThat(h.count("select count(*) from acq_disputes")).isZero();
            assertThat(h.scalar("select ack_status from net_dispute_forwards")).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void theAcquirerAlsoRefusesASecondChargebackAndOneWithTheWrongIdentifiersWithoutTheNetworksHelp() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            UUID other = cleared(h, "40.00");
            LocalDate day = LocalDate.now(ZoneOffset.UTC);
            codec.write(h.dirs.acquirerIn(), "X0000011.dsp", new ClearingFile("X0000011", day, "NET", "123456", "USD",
                    List.of(chargeback(h, payment, "000000001000", "OTHER"))));
            h.acquirer.disputes().tick();
            // same stan but another payment's rrn, and a second chargeback for the first payment
            Message wrongRrn = chargeback(h, payment, "000000001000", "OTHER").set(37, h.acquirer.service().store().request(other).get(37));
            codec.write(h.dirs.acquirerIn(), "X0000012.dsp", new ClearingFile("X0000012", day, "NET", "123456", "USD",
                    List.of(chargeback(h, payment, "000000000500", "DUPLICATE"), wrongRrn)));

            h.acquirer.disputes().tick();

            ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve("X0000012.ack"));
            assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("REFUSED", "REFUSED");
            assertThat(ack.items().get(0).reason()).startsWith("ALREADY_DISPUTED");
            assertThat(ack.items().get(1).reason()).startsWith("UNKNOWN_PAYMENT");
            assertThat(h.count("select count(*) from acq_disputes")).isEqualTo(1);
            assertThat(h.scalar("select amount from acq_disputes")).isEqualTo("10.00");
        }
    }

    @Test
    void aRefundIsNotSomethingThatCanBeChargedBack() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            AcquirerStore.Row refund = h.acquirer.clearing().refund(payment, "10.00");
            h.exec("update acq_transactions set status = 'CLEARED' where id = ?", UUID.fromString(refund.id().toString()));
            codec.write(h.dirs.acquirerIn(), "X0000013.dsp", new ClearingFile("X0000013", LocalDate.now(ZoneOffset.UTC), "NET", "123456", "USD",
                    List.of(chargeback(h, UUID.fromString(refund.id().toString()), "000000000500", "OTHER"))));

            h.acquirer.disputes().tick();

            ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve("X0000013.ack"));
            assertThat(ack.items().get(0).result()).isEqualTo("REFUSED");
            assertThat(h.count("select count(*) from acq_disputes")).isZero();
        }
    }

    @Test
    void tooMuchIsRefusedByTheAcquirerEvenIfTheNetworkLetItThrough() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            h.exec("update acq_transactions set final_amount = 20.00 where id = ?", payment);
            filed(h, "D1", chargeback(h, payment, "000000003000", "OTHER"));

            ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve("X0000001.ack"));
            assertThat(ack.items().get(0).result()).isEqualTo("REFUSED");
            assertThat(ack.items().get(0).reason()).startsWith("AMOUNT_TOO_LARGE");
            assertThat(h.count("select count(*) from acq_disputes")).isZero();
        }
    }

    @Test
    void aDisputeFileThatCannotBeTrustedIsRejectedWholeAndOnlyOnce() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            ClearingFile good = new ClearingFile("X0000009", LocalDate.now(ZoneOffset.UTC), "NET", "123456", "USD",
                    List.of(chargeback(h, payment, "000000001000", "OTHER")));
            // wrong direction: a representment cannot come from the network
            Message rep = chargeback(h, payment, "000000001000", "OTHER");
            rep = Message.of("1240").set(2, rep.get(2)).set(3, "000000").set(4, "000000001000").set(7, rep.get(7)).set(11, rep.get(11)).set(12, rep.get(12))
                    .set(13, rep.get(13)).set(32, rep.get(32)).set(37, rep.get(37)).set(41, rep.get(41)).set(42, rep.get(42)).set(49, rep.get(49)).set(48, "REP|x");
            codec.write(h.dirs.acquirerIn(), "X0000009.dsp", new ClearingFile("X0000009", good.businessDate(), "NET", "123456", "USD", List.of(rep)));
            // wrong receiver
            codec.write(h.dirs.acquirerIn(), "X0000008.dsp", new ClearingFile("X0000008", good.businessDate(), "NET", "654321", "USD", good.presentments()));
            // wrong sender
            codec.write(h.dirs.acquirerIn(), "X0000007.dsp", new ClearingFile("X0000007", good.businessDate(), "666666", "123456", "USD", good.presentments()));
            // file id and name disagree
            codec.write(h.dirs.acquirerIn(), "X0000006.dsp", new ClearingFile("X0000005", good.businessDate(), "NET", "123456", "USD", good.presentments()));

            h.acquirer.disputes().tick();
            h.acquirer.disputes().tick();

            assertThat(h.count("select count(*) from acq_disputes")).isZero();
            assertThat(h.count("select count(*) from acq_dispute_files where status = 'REJECTED'")).isEqualTo(4);
            for (String name : new String[] {"X0000009", "X0000008", "X0000007", "X0000006"}) {
                ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve(name + ".ack"));
                assertThat(ack.status()).as(name).isEqualTo(ClearingAck.Status.REJECTED);
            }
            assertThat(codec.readAck(h.dirs.acquirerOut().resolve("X0000009.ack")).reason()).startsWith("WRONG_DIRECTION");
            assertThat(codec.readAck(h.dirs.acquirerOut().resolve("X0000008.ack")).reason()).startsWith("WRONG_RECEIVER");
            assertThat(codec.readAck(h.dirs.acquirerOut().resolve("X0000007.ack")).reason()).startsWith("UNKNOWN_SENDER");
            assertThat(codec.readAck(h.dirs.acquirerOut().resolve("X0000006.ack")).reason()).startsWith("FILE_NAME_MISMATCH");
            assertThat(h.acquirer.disputes().tick()).as("nothing left to do").isZero();
        }
    }

    // ---- representing ----

    @Test
    void anAnswerNeedsANoteWithinTheLimitAndNeverContainsTheFieldSeparator() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            filed(h, "D1", chargeback(h, payment, "000000001000", "OTHER"));
            UUID id = onlyDispute(h);

            assertThatThrownBy(() -> h.acquirer.disputes().represent(id, null)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.acquirer.disputes().represent(id, "   ")).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> h.acquirer.disputes().represent(id, "x".repeat(201))).isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("200");
            assertThat(h.scalar("select status from acq_disputes")).as("a refused answer changes nothing").isEqualTo("RECEIVED");
            assertThat(dsp(h.dirs.acquirerOut())).isEmpty();

            assertThat(AcquirerDisputes.cleanNote("x".repeat(200))).hasSize(200);
            assertThat(AcquirerDisputes.cleanNote("  a|béc  ")).isEqualTo("a b c");
            h.acquirer.disputes().represent(id, "a|béc");
            ClearingFile file = codec.read(dsp(h.dirs.acquirerOut()).get(0));
            assertThat(file.presentments().get(0).get(48)).isEqualTo("REP|a b c");
        }
    }

    @Test
    void aChargebackIsAnsweredOnceNotTwiceEvenWhenTwoClicksArriveTogether() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            filed(h, "D1", chargeback(h, payment, "000000001000", "OTHER"));
            UUID id = onlyDispute(h);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Object> outcomes = Collections.synchronizedList(new ArrayList<>());
            Runnable click = () -> {
                try {
                    barrier.await();
                    outcomes.add(h.acquirer.disputes().represent(id, "receipt"));
                } catch (ConflictException e) {
                    outcomes.add(e);
                } catch (Exception e) {
                    outcomes.add(new IllegalStateException(e));
                }
            };
            Thread a = new Thread(click);
            Thread b = new Thread(click);
            a.start();
            b.start();
            a.join();
            b.join();

            assertThat(outcomes.stream().filter(o -> o instanceof AcquirerDisputes.Row)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> o instanceof ConflictException)).hasSize(1);
            assertThat(dsp(h.dirs.acquirerOut())).hasSize(1);
            assertThatThrownBy(() -> h.acquirer.disputes().represent(id, "again")).isInstanceOf(ConflictException.class).hasMessageContaining("already answered");
            assertThatThrownBy(() -> h.acquirer.disputes().represent(UUID.randomUUID(), "x")).isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    void aCrashBetweenRecordingTheAnswerAndWritingTheFileIsRepairedByTheNextBeat() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            filed(h, "D1", chargeback(h, payment, "000000001000", "OTHER"));
            h.acquirer.disputes().represent(onlyDispute(h), "receipt");
            Path file = dsp(h.dirs.acquirerOut()).get(0);
            ClearingFile before = codec.read(file);
            Files.delete(file);
            h.exec("update acq_disputes set rep_written = false");
            Files.deleteIfExists(h.dirs.acquirerOut().resolve("X0000001.ack"));
            h.exec("update acq_dispute_files set ack_written = false");

            h.acquirer.disputes().tick();

            assertThat(codec.read(dsp(h.dirs.acquirerOut()).get(0))).as("the same content (the encryption nonce differs)").isEqualTo(before);
            assertThat(h.dirs.acquirerOut().resolve("X0000001.ack")).exists();
            assertThat(h.scalar("select count(*) from acq_disputes where rep_written")).isEqualTo("1");
        }
    }

    @Test
    void aRefusalOrAnUnusableAnswerFromTheNetworkIsRecordedAsSuch() throws Exception {
        try (AcqHarness h = harness()) {
            UUID p1 = cleared(h, "50.00");
            UUID p2 = cleared(h, "60.00");
            filed(h, "D1", chargeback(h, p1, "000000001000", "OTHER"), chargeback(h, p2, "000000001000", "OTHER"));
            List<UUID> ids = new ArrayList<>();
            for (String s : h.acquirer.disputes().list(10).stream().map(r -> r.id().toString()).toList()) {
                ids.add(UUID.fromString(s));
            }
            h.acquirer.disputes().represent(ids.get(0), "one");
            h.acquirer.disputes().represent(ids.get(1), "two");
            String first = h.scalar("select rep_file_id from acq_disputes where id = ?", ids.get(0));
            String second = h.scalar("select rep_file_id from acq_disputes where id = ?", ids.get(1));
            codec.writeAck(h.dirs.acquirerIn(), first + ".ack", ClearingAck.accepted(first, List.of(new ClearingAck.Item(1, "REFUSED", "ALREADY_REPRESENTED: nope"))));
            codec.writeAck(h.dirs.acquirerIn(), second + ".ack", ClearingAck.accepted("SOMETHING", List.of(new ClearingAck.Item(1, "REPRESENTMENT_ACCEPTED", null))));

            h.acquirer.disputes().tick();

            assertThat(h.scalar("select rep_answer from acq_disputes where id = ?", ids.get(0))).startsWith("REFUSED: ALREADY_REPRESENTED");
            assertThat(h.scalar("select rep_answer from acq_disputes where id = ?", ids.get(1))).startsWith("UNREADABLE");
        }
    }

    // ---- the console ----

    @Test
    void theConsoleListsChargebacksAndLetsTheShopRepresent() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            filed(h, "D1", chargeback(h, payment, "000000001000", "NOT_RECEIVED"));
            UUID id = onlyDispute(h);
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + h.acquirer.consolePort();

            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/api/disputes")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).contains(id.toString(), "NOT_RECEIVED", "RECEIVED", "10.00");
            assertThat(list.body()).doesNotContain(AcqHarness.PAN);

            HttpResponse<String> bad = http.send(post(base + "/api/represent", "{\"id\":\"" + id + "\",\"note\":\"\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(bad.statusCode()).isEqualTo(400);
            HttpResponse<String> ok = http.send(post(base + "/api/represent", "{\"id\":\"" + id + "\",\"note\":\"receipt 7\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).contains("REPRESENTED", "receipt 7");
            HttpResponse<String> again = http.send(post(base + "/api/represent", "{\"id\":\"" + id + "\",\"note\":\"again\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(again.statusCode()).isEqualTo(409);
            HttpResponse<String> nothing = http.send(post(base + "/api/represent", "{\"id\":\"" + UUID.randomUUID() + "\",\"note\":\"x\"}"), HttpResponse.BodyHandlers.ofString());
            assertThat(nothing.statusCode()).isEqualTo(404);
            HttpResponse<String> wrongMethod = http.send(HttpRequest.newBuilder(URI.create(base + "/api/represent")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(wrongMethod.statusCode()).isEqualTo(405);
        }
    }

    private static HttpRequest post(String url, String json) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    @Test
    void aChargebackForExactlyTheClearedAmountIsAcceptedAndOneCentMoreIsNot() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            UUID tipped = cleared(h, "20.00");
            h.exec("update acq_transactions set final_amount = 25.00 where id = ?", tipped);
            LocalDate day = LocalDate.now(ZoneOffset.UTC);
            codec.write(h.dirs.acquirerIn(), "X0000021.dsp", new ClearingFile("X0000021", day, "NET", "123456", "USD",
                    List.of(chargeback(h, payment, "000000005000", "OTHER"), chargeback(h, tipped, "000000002501", "OTHER"))));

            h.acquirer.disputes().tick();

            ClearingAck ack = codec.readAck(h.dirs.acquirerOut().resolve("X0000021.ack"));
            assertThat(ack.items()).extracting(ClearingAck.Item::result).containsExactly("CHARGEBACK_ACCEPTED", "REFUSED");
            assertThat(ack.items().get(1).reason()).startsWith("AMOUNT_TOO_LARGE");
        }
    }

    @Test
    void answeringWhileClearingIsSwitchedOffIsAConflictNotAMissingChargeback() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    post("http://127.0.0.1:" + h.acquirer.consolePort() + "/api/represent", "{\"id\":\"" + UUID.randomUUID() + "\",\"note\":\"x\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).contains("switched off");
        }
    }

    @Test
    void withClearingSwitchedOffThereAreNoDisputesToHandle() throws Exception {
        try (AcqHarness h = new AcqHarness()) {
            assertThat(h.acquirer.disputes().enabled()).isFalse();
            assertThat(h.acquirer.disputes().tick()).isZero();
        }
    }

    @Test
    void noCardNumberIsKeptInTheAcquirerDisputeTablesOrFiles() throws Exception {
        try (AcqHarness h = harness()) {
            UUID payment = cleared(h, "50.00");
            filed(h, "D1", chargeback(h, payment, "000000001000", "OTHER"));
            h.acquirer.disputes().represent(onlyDispute(h), "receipt");
            beat(h);

            for (String table : new String[] {"acq_disputes", "acq_dispute_files", "acq_dispute_items"}) {
                assertThat(h.scalar("select string_agg(t::text, ' ') from " + table + " t")).as(table).doesNotContain(AcqHarness.PAN);
            }
            try (var s = Files.list(h.dirs.acquirerOut())) {
                for (Path p : s.toList()) {
                    assertThat(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1)).as(p.toString()).doesNotContain(AcqHarness.PAN);
                }
            }
        }
    }
}
