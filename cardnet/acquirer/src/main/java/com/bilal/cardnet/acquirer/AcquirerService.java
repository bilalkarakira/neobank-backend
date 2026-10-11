package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.PinBlock;
import com.bilal.cardnet.shared.Messages;
import com.bilal.cardnet.shared.PendingReversalStore;
import com.bilal.cardnet.shared.ReversalWorker;
import com.bilal.cardnet.shared.Sequences;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What the acquirer does for its terminal: build the payment, send it with a timeout, and when
 * it is unsure what happened, cancel it with a reversal that is retried until the issuer acknowledges it. An answer that did
 * not come is never guessed: the payment is reported as cancelled, never as approved.
 */
public final class AcquirerService {

    /** Bad input from the person at the terminal (400). */
    public static final class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** No such payment (404). */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** The payment is in a state that does not allow this (409). */
    public static final class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** What the terminal sends. {@code pin} may be null (no PIN entered); {@code expiry} is MM/YY as printed on the card. */
    /** An online payment: no card in a terminal, so no PIN; the security code printed on the card instead. */
    public record OnlineRequest(String pan, String expiry, String cvv, String amount, String merchantId, String authentication) {

        /** A first try: no proof of the customer's approval yet. */
        public OnlineRequest(String pan, String expiry, String cvv, String amount, String merchantId) {
            this(pan, expiry, cvv, amount, merchantId, null);
        }
    }

    /** A request for more on a payment already approved (a pump that keeps filling): the extra amount only. */
    public record IncreaseRequest(UUID paymentId, String amount) {
    }

    /** A cash withdrawal at the machine: the PIN is not optional. */
    public record WithdrawRequest(String pan, String expiry, String amount, String pin) {
    }

    public record PayRequest(String pan, String expiry, String amount, String merchantId, String pin) {
    }

    /** What the terminal shows. The texts are the decoded messages with the number masked and the PIN hidden, and the redacted hex. */
    public record Result(UUID id, String status, String responseCode, String authCode, String message, String requestText, String requestHex,
            String responseText, String responseHex, String challenge) {
    }

    private static final Pattern PAN = Pattern.compile("\\d{13,19}");
    private static final Pattern EXPIRY = Pattern.compile("(0[1-9]|1[0-2])/(\\d{2})");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");
    private static final Pattern PIN = Pattern.compile("\\d{4,12}");
    private static final Pattern CVV = Pattern.compile("\\d{3,4}");
    private static final Pattern AUTHENTICATION = Pattern.compile("[A-Za-z0-9]{4,8}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMdd").withZone(ZoneOffset.UTC);

    private final AcquirerConfig config;
    private final DataSource ds;
    private final AcquirerStore store;
    private final PendingReversalStore reversals;
    private final NetworkClient client;
    private final Clock clock;
    private final ReversalWorker worker;

    public AcquirerService(AcquirerConfig config, DataSource ds, AcquirerStore store, PendingReversalStore reversals, NetworkClient client,
            Clock clock) {
        this.config = config;
        this.ds = ds;
        this.store = store;
        this.reversals = reversals;
        this.client = client;
        this.clock = clock;
        this.worker = new ReversalWorker("ACQUIRER", reversals, reversal -> client.exchange(reversal, config.networkTimeout),
                () -> Sequences.nextStan(ds), clock, new com.bilal.cardnet.shared.RetrySchedule(config.retryScale), this::reversalDone,
                this::reversalFailed);
    }

    public void start() {
        worker.start(config.reversalPoll);
    }

    public void stop() {
        worker.stop();
    }

    /** One pass of the reversal queue (the background loop does this by itself; tests call it). */
    public int deliverReversals() {
        return worker.tick();
    }

    public AcquirerStore store() {
        return store;
    }

    // ---- paying ----

    public Result pay(PayRequest request) {
        AcquirerConfig.Merchant merchant = config.merchants.stream().filter(m -> m.id().equals(request.merchantId())).findFirst()
                .orElseThrow(() -> new BadRequestException("Choose one of the listed shops."));
        String pan = clean(request.pan());
        if (!PAN.matcher(pan).matches()) {
            throw new BadRequestException("The card number must be 13 to 19 digits.");
        }
        java.util.regex.Matcher expiry = EXPIRY.matcher(request.expiry() == null ? "" : request.expiry().strip());
        if (!expiry.matches()) {
            throw new BadRequestException("The expiry date must look like MM/YY, for example 10/29.");
        }
        String amountText = request.amount() == null ? "" : request.amount().strip();
        if (!AMOUNT.matcher(amountText).matches() || new BigDecimal(amountText).signum() <= 0) {
            throw new BadRequestException("The amount must be a positive number with at most two decimals, for example 12.50.");
        }
        String pin = request.pin() == null || request.pin().isBlank() ? null : request.pin().strip();
        if (pin != null && !PIN.matcher(pin).matches()) {
            throw new BadRequestException("A PIN is 4 to 12 digits.");
        }

        return authorize(merchant, pan, expiry, amountText, pin, "000000", "051", "00", null, null);
    }

    /**
     * An online payment (card not present): DE 22 812 and DE 25 59, no PIN, and the security code in DE 53. The code is only put in the
     * message that is sent: it is not kept in the payment record or in a queued reversal, and the console never shows it.
     */
    public Result payOnline(OnlineRequest request) {
        AcquirerConfig.Merchant merchant = config.merchants.stream().filter(m -> m.id().equals(request.merchantId())).findFirst()
                .orElseThrow(() -> new BadRequestException("Choose one of the listed shops."));
        String pan = clean(request.pan());
        if (!PAN.matcher(pan).matches()) {
            throw new BadRequestException("The card number must be 13 to 19 digits.");
        }
        java.util.regex.Matcher expiry = EXPIRY.matcher(request.expiry() == null ? "" : request.expiry().strip());
        if (!expiry.matches()) {
            throw new BadRequestException("The expiry date must look like MM/YY, for example 10/29.");
        }
        String cvv = request.cvv() == null ? "" : request.cvv().strip();
        if (!CVV.matcher(cvv).matches()) {
            throw new BadRequestException("The security code is the 3 or 4 digits on the card.");
        }
        String amountText = request.amount() == null ? "" : request.amount().strip();
        if (!AMOUNT.matcher(amountText).matches() || new BigDecimal(amountText).signum() <= 0) {
            throw new BadRequestException("The amount must be a positive number with at most two decimals, for example 12.50.");
        }
        String authentication = request.authentication() == null || request.authentication().isBlank() ? null : request.authentication().strip();
        if (authentication != null && !AUTHENTICATION.matcher(authentication).matches()) {
            throw new BadRequestException("The approval reference is 4 to 8 letters and digits.");
        }
        return authorize(merchant, pan, expiry, amountText, null, "000000", "812", "59", cvv, authentication);
    }

    /**
     * A cash withdrawal: an authorization with processing code 010000 at the cash machine (category 6011). The PIN is always sent, the
     * amount is a whole number of notes of 10 up to the machine's maximum. What the bank allows (limits, funds) is the bank's answer.
     */
    public Result withdraw(WithdrawRequest request) {
        String pan = clean(request.pan());
        if (!PAN.matcher(pan).matches()) {
            throw new BadRequestException("The card number must be 13 to 19 digits.");
        }
        java.util.regex.Matcher expiry = EXPIRY.matcher(request.expiry() == null ? "" : request.expiry().strip());
        if (!expiry.matches()) {
            throw new BadRequestException("The expiry date must look like MM/YY, for example 10/29.");
        }
        String pin = request.pin() == null ? "" : request.pin().strip();
        if (!PIN.matcher(pin).matches()) {
            throw new BadRequestException("A cash withdrawal needs the PIN (4 to 12 digits).");
        }
        String amountText = request.amount() == null ? "" : request.amount().strip();
        if (!AMOUNT.matcher(amountText).matches()) {
            throw new BadRequestException("The amount must be a whole number of dollars.");
        }
        BigDecimal amount = new BigDecimal(amountText);
        if (amount.signum() <= 0 || amount.remainder(BigDecimal.TEN).signum() != 0) {
            throw new BadRequestException("The machine pays notes of 10: choose 10, 20, 30 ...");
        }
        if (amount.compareTo(config.atmMax) > 0) {
            throw new BadRequestException("The machine pays at most " + config.atmMax.toPlainString() + " at a time.");
        }
        return authorize(config.atm, pan, expiry, amount.setScale(2).toPlainString(), pin, "010000", "051", "00", null, null);
    }

    private Result authorize(AcquirerConfig.Merchant merchant, String pan, java.util.regex.Matcher expiry, String amountText, String pin,
            String processingCode, String entryMode, String conditionCode, String cvv, String authentication) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String stan = Sequences.nextStan(ds);
        String rrn = Sequences.nextRrn(ds);
        Message payment = Message.of("0100").set(2, pan).set(3, processingCode).set(4, Messages.de4(new BigDecimal(amountText)))
                .set(7, Messages.de7(now)).set(11, stan).set(12, TIME.format(now)).set(13, DATE.format(now))
                .set(14, expiry.group(2) + expiry.group(1)).set(18, merchant.mcc()).set(22, entryMode).set(25, conditionCode)
                .set(32, config.acquirerId).set(37, rrn).set(41, "%-8s".formatted(config.terminalId)).set(42, "%-15s".formatted(merchant.id()))
                .set(43, "%-40s".formatted(merchant.name())).set(49, "840");
        if (authentication != null) {
            payment.set(48, "3DS|" + authentication); // the proof of the customer's approval is no secret: it is kept like any other field
        }
        // PIN data and the security code are in the message that is sent only: the stored copy and any queued reversal never have them
        Message sent = payment.copyWithMti("0100");
        if (pin != null) {
            sent.set(52, PinBlock.encode(pin, pan));
        }
        if (cvv != null) {
            sent.set(53, cvv);
        }

        store.insert(id, payment, merchant.name(), pin != null, now);
        Optional<Message> answer = client.exchange(sent, config.networkTimeout);
        return settle(id, sent, answer.filter(a -> answers(sent, a)));
    }

    /** True when {@code answer} is the response to {@code request}. */
    private static boolean answers(Message request, Message answer) {
        return answer.mti().equals(Messages.responseMti(request.mti())) && request.get(11).equals(answer.get(11))
                && request.get(37).equals(answer.get(37)) && answer.has(39);
    }

    private Result settle(UUID id, Message sent, Optional<Message> answer) {
        Instant now = clock.instant();
        if (answer.isEmpty()) {
            // no answer in time: do not guess, cancel it. The reversal is durable and retried until acknowledged.
            store.outcome(id, "REVERSING", null, null, "no answer in time: the payment is being cancelled", true, now);
            reversals.add("ACQUIRER", sent, Messages.amount(sent), "no answer in time", now, config.reversalDeadline, id);
            worker.wake();
            return result(id, "REVERSING", null, null, "No answer in time. The payment was NOT approved: it is being cancelled with a reversal that is retried until the bank confirms it.",
                    sent, null);
        }
        Message response = answer.get();
        String code = response.get(39);
        if ("00".equals(code)) {
            store.outcome(id, "APPROVED", code, response.get(38), null, false, now);
            return result(id, "APPROVED", code, response.get(38), "Approved. Authorization code " + response.get(38) + ".", sent, response);
        }
        String reason = PosTexts.reason(code);
        store.outcome(id, "DECLINED", code, null, reason, false, now);
        String challenge = challengeOf(response);
        if ("1A".equals(code) && challenge != null) {
            return result(id, "DECLINED", code, null, "The customer must approve this payment first (1A). Reference " + challenge
                    + ": it is shown in the bank's app. After approving, pay again with this reference.", sent, response);
        }
        return result(id, "DECLINED", code, null, "Declined (" + code + "): " + reason, sent, response);
    }

    /** The approval reference in DE 48 of an answer ("3DS|reference"), or null. */
    private static String challengeOf(Message response) {
        String data = response == null ? null : response.get(48);
        return data != null && data.startsWith("3DS|") && data.length() > 4 ? data.substring(4).strip() : null;
    }

    // ---- adding to an authorization ----

    /**
     * Asks the bank for more on an approved payment: a new authorization request (own STAN and RRN) whose DE 90 names the first one and
     * whose amount is only the extra. Allowed while the payment is approved, not in a batch, without a final amount, and not cash.
     * What the bank answers decides (12 when it cannot be added to, 51 without money, 61 over a limit ...). No answer in time: the
     * increase is reversed on its own (a durable reversal) and the payment is unaffected.
     */
    public Result increase(IncreaseRequest request) {
        String amountText = request.amount() == null ? "" : request.amount().strip();
        if (!AMOUNT.matcher(amountText).matches() || new BigDecimal(amountText).signum() <= 0) {
            throw new BadRequestException("The amount to add must be a positive number with at most two decimals, for example 25.00.");
        }
        BigDecimal amount = new BigDecimal(amountText).setScale(2);
        AcquirerStore.Row row = store.find(request.paymentId()).orElseThrow(() -> new NotFoundException("No such payment."));
        if (row.isRefund() || row.cash() || row.online()) {
            throw new ConflictException(row.cash() ? "A cash withdrawal cannot be added to."
                    : row.online() ? "An online payment cannot be added to: an addition carries neither the security code nor the customer's approval."
                    : "A refund cannot be added to.");
        }
        if (!row.status().equals("APPROVED") || row.batchId() != null) {
            throw new ConflictException("Only an approved payment that is not in a clearing file yet can be added to; this one is "
                    + row.status().toLowerCase().replace('_', ' ') + ".");
        }
        if (row.finalAmount() != null) {
            throw new ConflictException("The final amount of this payment was already set, so nothing can be added to it.");
        }

        Message first = store.request(row.id());
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Message extra = Message.of("0100").set(2, first.get(2)).set(3, "000000").set(4, Messages.de4(amount)).set(7, Messages.de7(now))
                .set(11, Sequences.nextStan(ds)).set(12, TIME.format(now)).set(13, DATE.format(now)).set(14, first.get(14)).set(18, first.get(18))
                .set(22, first.get(22)).set(25, first.get(25)).set(32, first.get(32)).set(37, Sequences.nextRrn(ds)).set(41, first.get(41))
                .set(42, first.get(42)).set(43, first.get(43)).set(49, first.get(49))
                .set(90, "0100" + first.get(11) + first.get(7) + Messages.padAcquirer(first.get(32)) + "00000000000");
        store.insertIncrement(id, row.id(), extra, now);
        Optional<Message> answer = client.exchange(extra, config.networkTimeout).filter(a -> answers(extra, a));
        Instant after = clock.instant();
        if (answer.isEmpty()) {
            store.incrementOutcome(id, "REVERSING", null, "no answer in time: the addition is being cancelled", true, after);
            reversals.add("ACQUIRER", extra, amount, "no answer in time", after, config.reversalDeadline, id);
            worker.wake();
            return result(id, "REVERSING", null, null, "No answer in time. Nothing was added: the request is being cancelled with a reversal that is retried until the bank confirms it. The payment is unchanged.",
                    extra, null);
        }
        Message response = answer.get();
        String code = response.get(39);
        if (!"00".equals(code)) {
            String reason = PosTexts.reason(code);
            store.incrementOutcome(id, "DECLINED", code, reason, false, after);
            return result(id, "DECLINED", code, null, "Not added (" + code + "): " + reason, extra, response);
        }
        if (!store.addToPayment(row.id(), amount, after)) {
            // the bank said yes but the payment is no longer open (the day was closed meanwhile): give the money back
            store.incrementOutcome(id, "REVERSING", code, "the payment was closed meanwhile: the addition is being cancelled", false, after);
            reversals.add("ACQUIRER", extra, amount, "the payment was closed meanwhile", after, config.reversalDeadline, id);
            worker.wake();
            return result(id, "REVERSING", code, response.get(38), "The bank approved it, but this payment was closed in the meantime, so the addition is being cancelled.", extra, response);
        }
        store.incrementOutcome(id, "APPROVED", code, null, false, after);
        BigDecimal total = store.find(row.id()).orElseThrow().authorizedTotal();
        return result(id, "APPROVED", code, response.get(38), "Added " + amount.toPlainString() + ": " + total.toPlainString() + " is now authorized for this payment.", extra, response);
    }

    // ---- void and resend ----

    /** Cancels an approved payment: a reversal that is retried until the issuer acknowledges it. */
    public Result voidSale(UUID id) {
        AcquirerStore.Row row = store.find(id).orElseThrow(() -> new NotFoundException("No such payment."));
        Instant now = clock.instant();
        if (!store.transition(id, "APPROVED", "REVERSING", "voided at the terminal", now)) {
            throw new ConflictException("Only an approved payment can be voided; this one is " + row.status().toLowerCase().replace('_', ' ') + ".");
        }
        Message original = store.request(id);
        reversals.add("ACQUIRER", original, row.amount(), "voided at the terminal", now, config.reversalDeadline, id);
        // what was added to the payment is cancelled with it: one reversal for each approved addition, by its own STAN and amount
        for (AcquirerStore.Increment addition : store.incrementsOf(id)) {
            if (store.transitionIncrement(addition.id(), "APPROVED", "REVERSING", "voided with the payment", now)) {
                reversals.add("ACQUIRER", store.incrementRequest(addition.id()), addition.amount(), "voided with the payment", now, config.reversalDeadline, addition.id());
            }
        }
        worker.wake();
        return result(id, "REVERSING", row.responseCode(), row.authCode(), "Void sent: the payment is being cancelled and will show as reversed once the bank confirms.", original, null);
    }

    /**
     * Sends the latest payment again, exactly as it was (without a PIN, which is never kept), to show the duplicate rule:
     * the issuer answers as it did the first time and holds nothing more. Nothing in the payment record changes.
     */
    public Result resendLast() {
        AcquirerStore.Row row = store.latest().orElseThrow(() -> new NotFoundException("There is no payment to send again."));
        Message request = store.request(row.id());
        Optional<Message> answer = client.exchange(request, config.networkTimeout).filter(a -> answers(request, a));
        if (answer.isEmpty()) {
            return result(row.id(), row.status(), null, null, "No answer to the repeated request.", request, null);
        }
        String code = answer.get().get(39);
        String text = "00".equals(code) ? "The same answer again: approved, authorization code " + answer.get().get(38) + ". The bank holds the money once."
                : "The same answer again: " + code + " " + PosTexts.reason(code);
        return result(row.id(), row.status(), code, answer.get().get(38), text, request, answer.get());
    }

    // ---- the reversal worker's callbacks ----

    private void reversalDone(UUID reversalId) {
        // the id is that of a payment or of an addition to one: the other update changes nothing
        reversals.acqTransactionOf(reversalId).ifPresent(tx -> {
            store.status(tx, "REVERSED", "the bank confirmed the reversal", clock.instant());
            store.incrementStatus(tx, "REVERSED", "the bank confirmed the reversal", clock.instant());
        });
    }

    private void reversalFailed(UUID reversalId, String why) {
        reversals.acqTransactionOf(reversalId).ifPresent(tx -> {
            store.status(tx, "REVERSAL_FAILED", "could not be reversed (" + why + "): needs a person", clock.instant());
            store.incrementStatus(tx, "REVERSAL_FAILED", "could not be reversed (" + why + "): needs a person", clock.instant());
        });
    }

    // ---- helpers ----

    private Result result(UUID id, String status, String code, String authCode, String message, Message request, Message response) {
        return new Result(id, status, code, authCode, message, Redact.decoded(request), Redact.hexDump(request),
                response == null ? null : Redact.decoded(response), response == null ? null : Redact.hexDump(response), challengeOf(response));
    }

    private static String clean(String pan) {
        return pan == null ? "" : pan.replace(" ", "").replace("-", "");
    }
}
