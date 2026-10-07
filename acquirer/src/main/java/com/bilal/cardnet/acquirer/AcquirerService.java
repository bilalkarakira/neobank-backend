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
 * What the acquirer does for its terminal (SPEC-P3-network.md, section 4): build the payment, send it with a timeout, and when
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
    /** A cash withdrawal at the machine: the PIN is not optional. */
    public record WithdrawRequest(String pan, String expiry, String amount, String pin) {
    }

    public record PayRequest(String pan, String expiry, String amount, String merchantId, String pin) {
    }

    /** What the terminal shows. The texts are the decoded messages with the number masked and the PIN hidden, and the redacted hex. */
    public record Result(UUID id, String status, String responseCode, String authCode, String message, String requestText, String requestHex,
            String responseText, String responseHex) {
    }

    private static final Pattern PAN = Pattern.compile("\\d{13,19}");
    private static final Pattern EXPIRY = Pattern.compile("(0[1-9]|1[0-2])/(\\d{2})");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");
    private static final Pattern PIN = Pattern.compile("\\d{4,12}");
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

        return authorize(merchant, pan, expiry, amountText, pin, "000000");
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
        return authorize(config.atm, pan, expiry, amount.setScale(2).toPlainString(), pin, "010000");
    }

    private Result authorize(AcquirerConfig.Merchant merchant, String pan, java.util.regex.Matcher expiry, String amountText, String pin,
            String processingCode) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String stan = Sequences.nextStan(ds);
        String rrn = Sequences.nextRrn(ds);
        Message payment = Message.of("0100").set(2, pan).set(3, processingCode).set(4, Messages.de4(new BigDecimal(amountText)))
                .set(7, Messages.de7(now)).set(11, stan).set(12, TIME.format(now)).set(13, DATE.format(now))
                .set(14, expiry.group(2) + expiry.group(1)).set(18, merchant.mcc()).set(22, "051").set(25, "00")
                .set(32, config.acquirerId).set(37, rrn).set(41, "%-8s".formatted(config.terminalId)).set(42, "%-15s".formatted(merchant.id()))
                .set(43, "%-40s".formatted(merchant.name())).set(49, "840");
        Message sent = pin == null ? payment : payment.copyWithMti("0100").set(52, PinBlock.encode(pin, pan));

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
        return result(id, "DECLINED", code, null, "Declined (" + code + "): " + reason, sent, response);
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
        reversals.acqTransactionOf(reversalId).ifPresent(tx -> store.status(tx, "REVERSED", "the bank confirmed the reversal", clock.instant()));
    }

    private void reversalFailed(UUID reversalId, String why) {
        reversals.acqTransactionOf(reversalId).ifPresent(tx -> store.status(tx, "REVERSAL_FAILED", "could not be reversed (" + why + "): needs a person", clock.instant()));
    }

    // ---- helpers ----

    private Result result(UUID id, String status, String code, String authCode, String message, Message request, Message response) {
        return new Result(id, status, code, authCode, message, Redact.decoded(request), Redact.hexDump(request),
                response == null ? null : Redact.decoded(response), response == null ? null : Redact.hexDump(response));
    }

    private static String clean(String pan) {
        return pan == null ? "" : pan.replace(" ", "").replace("-", "");
    }
}
