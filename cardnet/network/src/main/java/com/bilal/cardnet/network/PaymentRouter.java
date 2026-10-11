package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Json;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.PanVault;
import com.bilal.cardnet.shared.PendingReversalStore;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * What the network does with a payment or a reversal from an acquirer:
 * route by BIN, ask the issuer, and when the issuer cannot answer in time either stand in (small purchases) or say 91.
 * An issuer approval that arrives after the network already answered on its own is dealt with so nothing is left
 * hanging: reversed when the shop was told no, kept when the shop was told yes.
 */
public final class PaymentRouter {

    private static final System.Logger LOG = System.getLogger(PaymentRouter.class.getName());
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LATE_MEMORY = Duration.ofMinutes(5);

    /** What the network remembers about a request it answered without the issuer. */
    private record Late(Message request, boolean standInApproved, Instant at) {
    }

    private final NetworkConfig config;
    private final BinTable bins;
    private final IssuerLink link;
    private final AdviceStore advices;
    private final PendingReversalStore reversals;
    private final NetTransactionLog txLog;
    private final PanVault vault;
    private final Clock clock;
    private final Runnable wakeReversals;
    private final Object standInLock = new Object();
    private final ConcurrentHashMap<String, CompletableFuture<Message>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Late> late = new ConcurrentHashMap<>();

    private final AtomicLong answeredByIssuer = new AtomicLong();
    private final AtomicLong standInApprovals = new AtomicLong();
    private final AtomicLong standInRefusals = new AtomicLong();
    private final AtomicLong refusedAtTheDoor = new AtomicLong();
    private final AtomicLong reversalsForwarded = new AtomicLong();
    private final AtomicLong advicesCancelledByReversal = new AtomicLong();
    private final AtomicLong lateApprovalsReversed = new AtomicLong();
    private final AtomicLong lateApprovalsKept = new AtomicLong();

    public PaymentRouter(NetworkConfig config, BinTable bins, IssuerLink link, AdviceStore advices, PendingReversalStore reversals,
            NetTransactionLog txLog, PanVault vault, Clock clock, Runnable wakeReversals) {
        this.config = config;
        this.bins = bins;
        this.link = link;
        this.advices = advices;
        this.reversals = reversals;
        this.txLog = txLog;
        this.vault = vault;
        this.clock = clock;
        this.wakeReversals = wakeReversals;
        link.onUnsolicited(this::onLateAnswer);
    }

    // ---- payments (0100, 0200) ----

    public Message authorize(Message request) {
        return singleFlight("A|" + request.get(32) + "|" + request.get(11) + "|" + request.get(37), () -> doAuthorize(request));
    }

    private Message doAuthorize(Message request) {
        long started = System.nanoTime();
        if (!Messages.hasAll(request, 2, 3, 4, 7, 11, 13, 18, 32, 37, 41, 42, 49)) {
            return refuse(request, "30", started, "required elements missing");
        }
        String pan = request.get(2);
        if (bins.lookup(pan).isEmpty()) {
            return refuse(request, "15", started, "no issuer for this card number");
        }
        // a request the network already approved on its own is answered the same way again, whatever the link looks like now
        Optional<AdviceStore.Advice> earlier = advices.findByRequest(Messages.padAcquirer(request.get(32)), request.get(11), request.get(37));
        if (earlier.isPresent() && !earlier.get().status().equals("CANCELLED")) {
            txLog.record(request, NetTransactionLog.Route.STAND_IN, null, "00", millisSince(started), false, "replay of a stand-in approval");
            return Messages.response(request, "00").set(38, earlier.get().authCode());
        }

        boolean timedOut = false;
        String issuerCode = null;
        try {
            Message answer = link.request(request, config.issuerTimeout);
            issuerCode = answer.get(39);
            // 91 and 96 from the issuer mean "I could not decide": the same as not answering
            if (!"91".equals(issuerCode) && !"96".equals(issuerCode)) {
                answeredByIssuer.incrementAndGet();
                txLog.record(request, NetTransactionLog.Route.ISSUER, issuerCode, issuerCode, millisSince(started), false, null);
                return answer;
            }
        } catch (TimeoutException e) {
            timedOut = true;
        } catch (IssuerLink.UnavailableException e) {
            // down: stand-in below
        }
        return standIn(request, timedOut, issuerCode, started);
    }

    private Message standIn(Message request, boolean timedOut, String issuerCode, long started) {
        boolean approved;
        String authCode = null;
        String why;
        synchronized (standInLock) {
            String fingerprint = vault.fingerprint(request.get(2));
            BigDecimal used = advices.usedSince(fingerprint, startOfUtcDay());
            StandIn.Decision decision = StandIn.decide(request, used, config);
            approved = decision.approve();
            why = decision.reason();
            if (approved) {
                authCode = advices.create(request, fingerprint, "SI" + randomCode(4), clock.instant()).authCode();
            }
        }
        if (timedOut) {
            // what is remembered for a late answer never has PIN data or the security code
            late.put(Messages.responseKey(request), new Late(request.copyWithMti(request.mti()).remove(52).remove(53), approved, clock.instant()));
            forgetOldLate();
        }
        String note = (timedOut ? "issuer timed out; " : "issuer unavailable; ") + (approved ? "stand-in approved" : "stand-in refused: " + why);
        if (approved) {
            standInApprovals.incrementAndGet();
            txLog.record(request, NetTransactionLog.Route.STAND_IN, issuerCode, "00", millisSince(started), false, note);
            return Messages.response(request, "00").set(38, authCode);
        }
        standInRefusals.incrementAndGet();
        txLog.record(request, NetTransactionLog.Route.REFUSED, issuerCode, "91", millisSince(started), false, note);
        return Messages.response(request, "91");
    }

    /** An issuer answer nobody was waiting for any more: the request had timed out and the network answered on its own. */
    private void onLateAnswer(Message answer) {
        Late context = late.remove(Messages.keyOfResponse(answer));
        if (context == null) {
            return;
        }
        boolean approved = "00".equals(answer.get(39));
        txLog.record(context.request(), NetTransactionLog.Route.ISSUER, answer.get(39), null, 0, true,
                "late answer " + (approved ? "(approved)" : "(" + answer.get(39) + ")"));
        if (!approved) {
            return; // a late decline needs nothing: no hold exists, and a stand-in approval stays in force
        }
        Message request = context.request();
        if (context.standInApproved()) {
            // the shop heard "yes" and the issuer now says yes too: keep its hold, drop our advice
            advices.cancelPendingByRequest(Messages.padAcquirer(request.get(32)), request.get(11), request.get(37), "ISSUER_ANSWERED_LATE");
            lateApprovalsKept.incrementAndGet();
        } else {
            // the shop heard "no", so the issuer's hold must go
            reversals.add("NETWORK", request, Messages.amount(request), "late approval", clock.instant(), config.reversalDeadline, null);
            lateApprovalsReversed.incrementAndGet();
            wakeReversals.run();
        }
    }

    // ---- reversals (0400) ----

    public Message reverse(Message reversal) {
        return singleFlight("R|" + reversal.get(11) + "|" + reversal.get(37), () -> doReverse(reversal));
    }

    private Message doReverse(Message reversal) {
        long started = System.nanoTime();
        if (!Messages.hasAll(reversal, 2, 4, 13, 37, 41, 42, 49, 90)) {
            return refuse(reversal, "30", started, "required elements missing");
        }
        Messages.Original original;
        try {
            original = Messages.original(reversal);
        } catch (Iso8583Exception e) {
            return refuse(reversal, "30", started, "DE 90 does not name a payment");
        }
        String pan = reversal.get(2);
        // a stand-in approval the issuer has not heard of yet is simply withdrawn: the issuer never learns of it
        if (advices.cancelPending(vault.fingerprint(pan), original.acquirer(), original.stan(), original.de7(), "REVERSED_BEFORE_ADVICE")) {
            advicesCancelledByReversal.incrementAndGet();
            txLog.record(reversal, NetTransactionLog.Route.CANCELLED, null, "00", millisSince(started), false, "stand-in approval withdrawn before its advice");
            return Messages.response(reversal, "00");
        }
        if (bins.lookup(pan).isEmpty()) {
            txLog.record(reversal, NetTransactionLog.Route.REFUSED, null, "00", millisSince(started), false, "no issuer: nothing to reverse");
            return Messages.response(reversal, "00");
        }
        try {
            Message answer = link.request(reversal, config.issuerTimeout);
            reversalsForwarded.incrementAndGet();
            txLog.record(reversal, NetTransactionLog.Route.ISSUER, answer.get(39), answer.get(39), millisSince(started), false, null);
            return answer;
        } catch (TimeoutException | IssuerLink.UnavailableException e) {
            txLog.record(reversal, NetTransactionLog.Route.REFUSED, null, "91", millisSince(started), false, "issuer unreachable; the acquirer retries");
            return Messages.response(reversal, "91");
        }
    }

    // ---- helpers ----

    private Message refuse(Message request, String code, long started, String note) {
        refusedAtTheDoor.incrementAndGet();
        txLog.record(request, NetTransactionLog.Route.REFUSED, null, code, millisSince(started), false, note);
        return Messages.response(request, code);
    }

    /** One decision per request at a time: a resend of a request still being handled waits for, and shares, the first answer. */
    private Message singleFlight(String key, Supplier<Message> work) {
        CompletableFuture<Message> mine = new CompletableFuture<>();
        CompletableFuture<Message> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return existing.join();
        }
        try {
            Message result = work.get();
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private void forgetOldLate() {
        Instant cutoff = clock.instant().minus(LATE_MEMORY);
        late.values().removeIf(l -> l.at().isBefore(cutoff));
    }

    private Instant startOfUtcDay() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String randomCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** Counters for the control API. */
    public Map<String, Object> counters() {
        Map<String, Object> map = Json.object();
        map.put("answeredByIssuer", answeredByIssuer.get());
        map.put("standInApprovals", standInApprovals.get());
        map.put("standInRefusals", standInRefusals.get());
        map.put("refusedAtTheDoor", refusedAtTheDoor.get());
        map.put("reversalsForwarded", reversalsForwarded.get());
        map.put("advicesWithdrawnByReversal", advicesCancelledByReversal.get());
        map.put("lateApprovalsReversed", lateApprovalsReversed.get());
        map.put("lateApprovalsKept", lateApprovalsKept.get());
        return map;
    }

    /** Requests still waiting for a late issuer answer (for tests). */
    public int lateCount() {
        return late.size();
    }
}
