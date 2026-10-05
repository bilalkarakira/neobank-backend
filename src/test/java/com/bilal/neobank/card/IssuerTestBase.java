package com.bilal.neobank.card;

import com.bilal.cardnet.iso8583.Frame;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.Packager;
import com.bilal.cardnet.iso8583.PinBlock;
import com.bilal.neobank.MutableClock;
import com.bilal.neobank.analytics.AnalyticsTestBase;
import com.bilal.neobank.card.auth.AuthorizationRepository;
import com.bilal.neobank.card.iso.IssuerServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A tiny terminal (acquirer side) that talks to the issuer over a real socket, and helpers to build customers and cards. */
abstract class IssuerTestBase extends AnalyticsTestBase {

    @Autowired
    protected IssuerServer server;

    @Autowired
    protected CardService cardService;

    @Autowired
    protected MutableClock mutableClock;

    @Autowired
    protected AuthorizationRepository authorizations;

    protected final Packager packager = new Packager();
    private final AtomicInteger sequence = new AtomicInteger(100);

    @BeforeEach
    void resetClock() {
        mutableClock.set(Instant.now());
    }

    /** A customer with a funded checking account and one card. */
    protected record Fixture(String token, Acc account, UUID cardId, String pan, String pin, String expiry) {
    }

    protected Fixture fixture(String email, String balance) throws Exception {
        String token = tokenFor(email);
        Acc account = open(token, "CHECKING");
        if (new BigDecimal(balance).signum() > 0) {
            deposit(token, account, balance);
        }
        return card(token, account);
    }

    protected Fixture card(String token, Acc account) {
        var issued = cardService.issue(UUID.fromString(account.userId()), UUID.fromString(account.id()), null);
        String expiry = jdbc.queryForObject("select expiry from cards where id = ?", String.class, issued.view().card().getId());
        return new Fixture(token, account, issued.view().card().getId(), issued.pan(), issued.pin(), expiry);
    }

    protected void deposit(String token, Acc account, String amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + account.id() + "/deposits").header("Authorization", token)
                .header("Idempotency-Key", "dep-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"%s\"}".formatted(amount))).andExpect(status().isCreated());
    }

    protected BigDecimal available(Fixture f) {
        BigDecimal balance = jdbc.queryForObject("select balance from accounts where id = ?::uuid", BigDecimal.class, f.account().id());
        BigDecimal held = jdbc.queryForObject("select coalesce(sum(amount), 0) from authorizations where account_id = ?::uuid "
                + "and status = 'AUTHORIZED' and expires_at > ?", BigDecimal.class, f.account().id(),
                java.sql.Timestamp.from(mutableClock.instant()));
        return balance.subtract(held);
    }

    protected int rows(String where, Object... args) {
        return jdbc.queryForObject("select count(*) from authorizations where " + where, Integer.class, args);
    }

    // ---- messages ----

    protected String nextStan() {
        return "%06d".formatted(sequence.incrementAndGet());
    }

    /** A purchase authorization with a fresh STAN and RRN. Callers change fields on the result. */
    protected Message purchase(Fixture f, String amount) {
        int n = sequence.incrementAndGet();
        return purchaseWith(f.pan(), f.expiry(), amount, "%06d".formatted(n), "%012d".formatted(n));
    }

    protected Message purchaseWith(String pan, String expiry, String amount, String stan, String rrn) {
        return Message.of("0100")
                .set(2, pan).set(3, "000000").set(4, cents(amount)).set(7, "1005120000").set(11, stan)
                .set(12, "120000").set(13, "1005").set(14, expiry).set(18, "5814").set(22, "051").set(25, "00")
                .set(32, "123456").set(37, rrn).set(41, "TERM0001").set(42, "MERCHANT0000001")
                .set(43, "%-40s".formatted("Corner Cafe Springfield")).set(49, "840");
    }

    protected Message withPin(Message message, String pin) {
        return message.set(52, PinBlock.encode(pin, message.get(2)));
    }

    /** A reversal of an earlier request: repeats its card, amount and terminal data and names it in DE 90. */
    protected Message reversalOf(Message original) {
        String de90 = original.mti() + original.get(11) + original.get(7) + "00000" + original.get(32) + "00000000000";
        return Message.of("0400").set(2, original.get(2)).set(3, original.get(3)).set(4, original.get(4))
                .set(7, "1005120500").set(11, nextStan()).set(12, "120500").set(13, original.get(13))
                .set(37, original.get(37)).set(41, original.get(41)).set(42, original.get(42))
                .set(49, original.get(49)).set(90, de90);
    }

    protected static String cents(String amount) {
        return "%012d".formatted(new BigDecimal(amount).movePointRight(2).longValueExact());
    }

    protected static String code(Message response) {
        return response.get(39);
    }

    /** The acquirer side of one TCP connection. */
    protected final class Terminal implements AutoCloseable {

        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Terminal() throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
            socket.setSoTimeout(10_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        Terminal signedOn() throws IOException {
            Message response = exchange(Message.of("0800").set(7, "1005120000").set(11, nextStan()).set(70, "001"));
            if (!"00".equals(code(response))) {
                throw new IllegalStateException("sign-on refused: " + code(response));
            }
            return this;
        }

        Message exchange(Message request) throws IOException {
            Frame.write(out, packager.pack(request));
            return receive();
        }

        Message receive() throws IOException {
            byte[] frame = Frame.read(in);
            if (frame == null) {
                throw new IOException("connection closed by the issuer");
            }
            return packager.unpack(frame);
        }

        /** True when the issuer closed the connection (a read returns the end of the stream or a reset). */
        boolean closedByIssuer() {
            try {
                return in.read() < 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
