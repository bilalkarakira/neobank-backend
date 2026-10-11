package com.bilal.neobank.card.auth;

import com.bilal.cardnet.iso8583.PinBlock;
import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.card.Card;
import com.bilal.neobank.card.CardCrypto;
import com.bilal.neobank.card.CardProperties;
import com.bilal.neobank.card.CardRepository;
import com.bilal.neobank.card.CardStatus;
import com.bilal.neobank.card.FreezeReason;
import com.bilal.neobank.card.clearing.CardReceivables;
import com.bilal.neobank.card.sca.CardAuthentication;
import com.bilal.neobank.card.sca.CardAuthenticationRepository;
import com.bilal.neobank.card.sca.CardAuthenticationService;
import com.bilal.neobank.card.Luhn;
import com.bilal.neobank.notification.NotificationService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The issuer's decision: should this card payment be approved? Rules run in a fixed order and the first one that fails
 * answers. Everything happens in ONE transaction that holds the account's row
 * lock from the first read of the balance to the insert of the hold, so concurrent requests for one account queue up.
 *
 * <p>Declines are recorded too (they are replayed for a duplicate and shown to the customer). The only exceptions are
 * answers given before the request could be identified. An unexpected exception rolls everything back.
 */
@Service
public class AuthorizationEngine {

    /**
     * What the issuer needs from an authorization request, already parsed from the message. {@code cvv} is the security code of an online
     * payment (DE 53); it is only compared with the hash and never stored or logged.
     */
    public record AuthInput(String pan, String expiry, String pinBlock, Authorization.Request request, ParentRef parent, String cvv,
            String authenticationReference) {

        /** An authorization that tops up nothing and has no security code. */
        public AuthInput(String pan, String expiry, String pinBlock, Authorization.Request request) {
            this(pan, expiry, pinBlock, request, null, null, null);
        }

        public AuthInput(String pan, String expiry, String pinBlock, Authorization.Request request, ParentRef parent) {
            this(pan, expiry, pinBlock, request, parent, null, null);
        }

        public AuthInput(String pan, String expiry, String pinBlock, Authorization.Request request, ParentRef parent, String cvv) {
            this(pan, expiry, pinBlock, request, parent, cvv, null);
        }
    }

    /** DE 90 of an incremental authorization: which first authorization it adds to (its STAN and transmission time). */
    public record ParentRef(String originalStan, String originalTransmittedAt) {
    }

    /** A 0120 advice: the network already approved this payment with {@code authCode}. */
    public record AdviceInput(String pan, String authCode, Authorization.Request request) {
    }

    /** A reversal: the original is identified by DE 90 (STAN, transmission time, acquirer) and the card. */
    public record ReversalInput(String pan, BigDecimal amount, String originalStan, String originalTransmittedAt,
            Authorization.Request request) {
    }

    /** The answer: DE39 and, for an approval, the authorization code (DE38); {@code challenge} is DE 48 of a 1A answer (3DS|reference). */
    public record Decision(String code, String authCode, String challenge) {

        public Decision(String code, String authCode) {
            this(code, authCode, null);
        }

        public boolean approved() {
            return ResponseCode.APPROVED.equals(code);
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CardRepository cardRepository;
    private final AuthorizationRepository authorizationRepository;
    private final HoldService holdService;
    private final CardCrypto crypto;
    private final CardProperties properties;
    private final NotificationService notifications;
    private final EntityManager entityManager;
    private final Clock clock;
    private final CardReceivables receivables;
    private final CardAuthenticationRepository authentications;
    private final CardAuthenticationService authenticationService;

    public AuthorizationEngine(CardRepository cardRepository, AuthorizationRepository authorizationRepository,
            HoldService holdService, CardCrypto crypto, CardProperties properties,
            NotificationService notifications, EntityManager entityManager, Clock clock, CardReceivables receivables,
            CardAuthenticationRepository authentications, CardAuthenticationService authenticationService) {
        this.receivables = receivables;
        this.authentications = authentications;
        this.authenticationService = authenticationService;
        this.cardRepository = cardRepository;
        this.authorizationRepository = authorizationRepository;
        this.holdService = holdService;
        this.crypto = crypto;
        this.properties = properties;
        this.notifications = notifications;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public Decision authorize(AuthInput in) {
        if (!properties.authorizationsEnabled()) {
            return new Decision(ResponseCode.ISSUER_UNAVAILABLE, null); // switched off: nothing is recorded
        }
        // a lock that cannot be had quickly means something is wrong; answer 96 rather than hang the terminal
        entityManager.createNativeQuery("set local lock_timeout = '2s'").executeUpdate();

        Instant now = clock.instant();
        Authorization.Request r = in.request();
        String panHash = crypto.hashPan(in.pan());
        String last4 = last4(in.pan());

        // lock order: account first, then card
        Optional<Card> found = cardRepository.findByPanHash(panHash);
        Card card = null;
        Account account = null;
        if (found.isPresent()) {
            account = holdService.lockAccount(found.get().getAccountId());
            card = cardRepository.lockById(found.get().getId()).orElseThrow();
            entityManager.refresh(card);
        }

        // 2. duplicate: replay the first answer, never decide twice (and never hold twice)
        Optional<Authorization> duplicate = authorizationRepository.findDuplicate(panHash, r.acquirerId(), r.stan(),
                r.rrn(), r.terminalId(), r.localDate());
        if (duplicate.isPresent()) {
            Authorization first = duplicate.get();
            return first.getAmount().compareTo(r.amount()) == 0
                    ? new Decision(first.getResponseCode(), first.getAuthCode())
                    : new Decision(ResponseCode.DUPLICATE, null);
        }

        // 1b. zero amount
        if (r.amount().signum() == 0) {
            return decline(card, account, panHash, last4, r, ResponseCode.INVALID_AMOUNT, now);
        }
        // 3. unknown card (or a number that cannot be one)
        if (card == null || !Luhn.isValid(in.pan())) {
            return decline(card, account, panHash, last4, r, ResponseCode.INVALID_CARD, now);
        }
        // 4. expiry
        if (!card.getExpiry().equals(in.expiry())) {
            return decline(card, account, panHash, last4, r, ResponseCode.INVALID_CARD, now);
        }
        if (YearMonth.now(clock.withZone(ZoneOffset.UTC)).isAfter(expiryMonth(card.getExpiry()))) {
            return decline(card, account, panHash, last4, r, ResponseCode.EXPIRED_CARD, now);
        }
        // 5. card status
        if (card.getStatus() == CardStatus.CANCELLED) {
            return decline(card, account, panHash, last4, r, ResponseCode.INVALID_CARD, now);
        }
        if (card.getStatus() == CardStatus.FROZEN) {
            return decline(card, account, panHash, last4, r, ResponseCode.NOT_PERMITTED, now);
        }
        // 6. the account behind the card
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return decline(card, account, panHash, last4, r, ResponseCode.DO_NOT_HONOUR, now);
        }
        // 6b. the customer owes the bank money from an earlier cleared payment: no new card payments until it is paid off
        if (receivables.hasOutstanding(account.getId())) {
            return decline(card, account, panHash, last4, r, ResponseCode.DO_NOT_HONOUR, now);
        }
        // 7. what the card may do: purchases (processing code 00) and cash withdrawals (01) in USD, not at blocked merchant categories
        String processing = r.processingCode().substring(0, 2);
        boolean cash = "01".equals(processing);
        if ((!"00".equals(processing) && !cash) || !"840".equals(r.currency()) || properties.blockedMcc().contains(r.mcc())) {
            return decline(card, account, panHash, last4, r, ResponseCode.NOT_PERMITTED, now);
        }
        // 7b. cash is paid out only against the PIN
        if (cash && in.pinBlock() == null) {
            return decline(card, account, panHash, last4, r, ResponseCode.NOT_PERMITTED, now);
        }
        // 7c. an increment names the authorization it adds to (DE 90) and has to fit under it
        Authorization parent = null;
        if (in.parent() != null) {
            parent = incrementParent(panHash, r, in.parent(), processing, now);
            if (parent == null) {
                return decline(card, account, panHash, last4, r, ResponseCode.INVALID_TRANSACTION, now);
            }
        }
        // 7d. online (card not present): no PIN can be given, and the security code printed on the card must be right
        boolean online = in.cvv() != null || (r.entryMode() != null && r.entryMode().startsWith("81"));
        if (online) {
            if (in.pinBlock() != null || card.getCvvHash() == null) {
                return decline(card, account, panHash, last4, r, ResponseCode.NOT_PERMITTED, now);
            }
            String cvvCode = checkSecurityCode(card, in.cvv());
            if (cvvCode != null) {
                return decline(card, account, panHash, last4, r, cvvCode, now);
            }
        }
        // 7e. an online payment above the threshold needs the customer's approval of exactly this payment (the proof comes in DE 48)
        CardAuthentication proof = null;
        if (online && r.amount().compareTo(properties.scaThreshold()) > 0) {
            Optional<CardAuthentication> given = in.authenticationReference() == null ? Optional.empty()
                    : authentications.findByReference(in.authenticationReference());
            if (given.isPresent() && given.get().getStatus() == CardAuthentication.Status.REJECTED && fits(given.get(), card, r)) {
                return decline(card, account, panHash, last4, r, ResponseCode.DO_NOT_HONOUR, now);
            }
            if (given.isEmpty() || !usable(given.get(), card, r, now)) {
                CardAuthentication asked = authenticationService.challenge(card, r.merchantId(), r.merchantName(), r.amount(), now);
                authorizationRepository.saveAndFlush(Authorization.declined(card.getId(), account.getId(), panHash, last4, r,
                        ResponseCode.AUTHENTICATION_REQUIRED, now));
                return new Decision(ResponseCode.AUTHENTICATION_REQUIRED, null, "3DS|" + asked.getReference());
            }
            proof = given.get();
        }
        // 8. PIN (only when one was sent): before limits and funds, so a caller without the PIN learns nothing about money
        if (in.pinBlock() != null) {
            String pinCode = checkPin(card, in.pan(), in.pinBlock());
            if (pinCode != null) {
                return decline(card, account, panHash, last4, r, pinCode, now);
            }
        }
        // 9. velocity and limits
        if (authorizationRepository.countApprovalsSince(card.getId(), now.minus(properties.velocityWindow()))
                >= properties.velocityMax()) {
            return decline(card, account, panHash, last4, r, ResponseCode.VELOCITY, now);
        }
        if (r.amount().compareTo(properties.perTransactionCap()) > 0) {
            return decline(card, account, panHash, last4, r, ResponseCode.EXCEEDS_LIMIT, now);
        }
        Instant startOfDay = LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal today = authorizationRepository.sumApprovedSince(card.getId(), startOfDay);
        if (today.add(r.amount()).compareTo(card.getDailyLimit()) > 0) {
            return decline(card, account, panHash, last4, r, ResponseCode.EXCEEDS_LIMIT, now);
        }
        // 9b. the cash machine limits: one withdrawal, and the day's cash in all
        if (cash) {
            if (r.amount().compareTo(properties.cashPerWithdrawalMax()) > 0) {
                return decline(card, account, panHash, last4, r, ResponseCode.EXCEEDS_LIMIT, now);
            }
            if (authorizationRepository.sumCashApprovedSince(card.getId(), startOfDay).add(r.amount()).compareTo(properties.cashDailyLimit()) > 0) {
                return decline(card, account, panHash, last4, r, ResponseCode.EXCEEDS_LIMIT, now);
            }
        }
        // 10 + 11. funds, then the hold, under the account lock
        // an increment answers with the code of the authorization it adds to: that is the one the shop presents for clearing
        String authCode = parent != null ? parent.getAuthCode() : newAuthCode();
        Authorization approved = Authorization.approved(card.getId(), account.getId(), panHash, last4, r, authCode, now,
                now.plus(properties.holdTtl()));
        if (parent != null) {
            approved.incrementOf(parent.getId());
        }
        // the approval is used up in the same transaction as the authorization; the account lock makes two requests with one approval queue up
        if (proof != null && authentications.markUsed(proof.getId(), now) != 1) {
            CardAuthentication asked = authenticationService.challenge(card, r.merchantId(), r.merchantName(), r.amount(), now);
            authorizationRepository.saveAndFlush(Authorization.declined(card.getId(), account.getId(), panHash, last4, r,
                    ResponseCode.AUTHENTICATION_REQUIRED, now));
            return new Decision(ResponseCode.AUTHENTICATION_REQUIRED, null, "3DS|" + asked.getReference());
        }
        if (!holdService.tryReserve(approved)) {
            if (proof != null) {
                authentications.release(proof.getId()); // nothing was paid: the customer's approval is not wasted
            }
            return decline(card, account, panHash, last4, r, ResponseCode.INSUFFICIENT_FUNDS, now);
        }
        return new Decision(ResponseCode.APPROVED, authCode);
    }

    /** The approval is for this card, this amount and this shop (whatever its state). */
    private static boolean fits(CardAuthentication a, Card card, Authorization.Request r) {
        return a.getCardId().equals(card.getId()) && a.getAmount().compareTo(r.amount()) == 0 && a.getMerchantId().equals(r.merchantId());
    }

    /** Approved, not expired (at its expiry second it is expired), not used, and for exactly this payment. */
    private static boolean usable(CardAuthentication a, Card card, Authorization.Request r, Instant now) {
        return fits(a, card, r) && a.getStatus() == CardAuthentication.Status.APPROVED && a.getUsedAt() == null && now.isBefore(a.getExpiresAt());
    }

    /**
     * The first authorization an increment adds to, or null when it may not be added to: it must exist for this card, still hold its
     * money, not be an increment or cash itself, be at the same terminal and merchant, have fewer increments than allowed, and
     * stay within the per-transaction cap together with its active increments and this one. Called under the account's row lock,
     * so two increments at the same moment are counted one after the other.
     */
    /** (An online payment cannot be added to: an addition has no security code and no approval of the customer.) */
    private Authorization incrementParent(String panHash, Authorization.Request r, ParentRef ref, String processing, Instant now) {
        if (!"00".equals(processing)) {
            return null;
        }
        Optional<Authorization> found = authorizationRepository.findFirstByPanHashAndAcquirerIdAndStanAndTransmittedAtOrderByCreatedAtAsc(
                panHash, r.acquirerId(), ref.originalStan(), ref.originalTransmittedAt());
        if (found.isEmpty()) {
            return null;
        }
        Authorization first = found.get();
        boolean cash = first.getProcessingCode() != null && first.getProcessingCode().startsWith("01");
        boolean online = first.getEntryMode() != null && first.getEntryMode().startsWith("81");
        if (first.getParentId() != null || cash || online || !first.holdsMoney(now) || !first.getTerminalId().equals(r.terminalId())
                || !first.getMerchantId().equals(r.merchantId())) {
            return null;
        }
        if (authorizationRepository.countByParentIdAndStatus(first.getId(), AuthorizationStatus.AUTHORIZED) >= properties.maxIncrements()) {
            return null;
        }
        BigDecimal total = first.getAmount().add(authorizationRepository.sumActiveIncrements(first.getId())).add(r.amount());
        return total.compareTo(properties.perTransactionCap()) > 0 ? null : first;
    }

    /**
     * Records a payment the card network approved on its own while this bank was unavailable. Always answers 00: an advice
     * states what already happened, and the rules do not run again. It holds the money whatever the balance is (the bank
     * stands behind the network's answer; the books check reports an account whose holds exceed its balance). Repeats are
     * harmless: a request already known changes nothing, except that an earlier DECLINE of the very same request is replaced
     * by the approval the shop was given. An unknown card or an account that is not active gets no hold, only a DECLINED
     * row for the audit.
     */
    @Transactional
    public Decision advise(AdviceInput in) {
        entityManager.createNativeQuery("set local lock_timeout = '2s'").executeUpdate();
        Instant now = clock.instant();
        Authorization.Request r = in.request();
        String panHash = crypto.hashPan(in.pan());
        String last4 = last4(in.pan());

        Optional<Card> found = cardRepository.findByPanHash(panHash);
        Card card = found.orElse(null);
        Account account = card == null ? null : holdService.lockAccount(card.getAccountId());

        Optional<Authorization> existing = authorizationRepository.findDuplicate(panHash, r.acquirerId(), r.stan(), r.rrn(),
                r.terminalId(), r.localDate());
        if (existing.isPresent()) {
            Authorization known = existing.get();
            if (known.getStatus() == AuthorizationStatus.DECLINED && card != null && account.getStatus() == AccountStatus.ACTIVE) {
                known.replaceDeclineWithAdvice(in.authCode(), now.plus(properties.holdTtl()));
                authorizationRepository.save(known);
            }
            return new Decision(ResponseCode.APPROVED, null);
        }
        if (card == null) {
            decline(null, null, panHash, last4, r, ResponseCode.INVALID_CARD, now);
        } else if (account.getStatus() != AccountStatus.ACTIVE) {
            decline(card, account, panHash, last4, r, ResponseCode.DO_NOT_HONOUR, now);
        } else {
            authorizationRepository.saveAndFlush(Authorization.advised(card.getId(), account.getId(), panHash, last4, r,
                    in.authCode(), now, now.plus(properties.holdTtl())));
        }
        return new Decision(ResponseCode.APPROVED, null);
    }

    /**
     * Releases the hold of an earlier authorization. Always answers 00 when the request is well formed, because the
     * acquirer only wants to know the matter is closed. A reversal whose original is not here yet (the timeout case: the
     * acquirer gave up while the request was still on its way) leaves a tombstone so the late original is refused.
     * A different amount than the hold is the one refusal (30): partial reversals are not supported.
     */
    @Transactional
    public Decision reverse(ReversalInput in) {
        entityManager.createNativeQuery("set local lock_timeout = '2s'").executeUpdate();
        Instant now = clock.instant();
        Authorization.Request r = in.request();
        String panHash = crypto.hashPan(in.pan());

        Optional<Card> card = cardRepository.findByPanHash(panHash);
        card.ifPresent(c -> holdService.lockAccount(c.getAccountId()));

        Optional<Authorization> original = authorizationRepository
                .findFirstByPanHashAndAcquirerIdAndStanAndTransmittedAtOrderByCreatedAtAsc(panHash, r.acquirerId(),
                        in.originalStan(), in.originalTransmittedAt());
        if (original.isPresent()) {
            Authorization a = original.get();
            if (a.getStatus() == AuthorizationStatus.AUTHORIZED) {
                if (a.getAmount().compareTo(in.amount()) != 0) {
                    return new Decision(ResponseCode.FORMAT_ERROR, null);
                }
                a.reverse(now);
                authorizationRepository.save(a);
            }
            return new Decision(ResponseCode.APPROVED, null);
        }
        authorizationRepository.save(Authorization.tombstone(card.map(Card::getId).orElse(null),
                card.map(Card::getAccountId).orElse(null), panHash, last4(in.pan()), r, now));
        return new Decision(ResponseCode.APPROVED, null);
    }

    /** Null when the PIN is right (the failure counter is reset); otherwise the response code for the failure. */
    private String checkPin(Card card, String pan, String pinBlock) {
        Optional<String> pin = PinBlock.decode(pinBlock, pan);
        if (pin.isPresent() && crypto.pinMatches(card.getId(), pin.get(), card.getPinHash())) {
            if (card.getPinFailures() != 0) {
                card.setPinFailures(0);
                cardRepository.save(card);
            }
            return null;
        }
        return failedSecret(card, ResponseCode.WRONG_PIN);
    }

    /**
     * Null when the security code of an online payment is right. A wrong or missing code counts like a wrong PIN: the same counter, and
     * the third failure freezes the card, so the code cannot be guessed by trying (it has only a thousand values). A right code does not
     * reset the counter: that is the PIN's way out, and online payments must not be a way to undo failures.
     */
    private String checkSecurityCode(Card card, String cvv) {
        if (crypto.cvvMatches(card.getId(), cvv, card.getCvvHash())) {
            return null;
        }
        return failedSecret(card, ResponseCode.WRONG_SECURITY_CODE);
    }

    /** One more wrong PIN or security code: counted, and the card is frozen when the allowed number is reached. */
    private String failedSecret(Card card, String wrongCode) {
        card.setPinFailures(card.getPinFailures() + 1);
        if (card.getPinFailures() >= properties.maxPinFailures()) {
            card.freeze(FreezeReason.PIN_TRIES);
            cardRepository.save(card);
            notifications.cardFrozenByPin(card.getUserId(), card.getLast4());
            return ResponseCode.PIN_TRIES_EXCEEDED;
        }
        cardRepository.save(card);
        return wrongCode;
    }

    private Decision decline(Card card, Account account, String panHash, String last4, Authorization.Request request,
            String code, Instant now) {
        authorizationRepository.saveAndFlush(Authorization.declined(card == null ? null : card.getId(),
                account == null ? null : account.getId(), panHash, last4, request, code, now));
        return new Decision(code, null);
    }

    private static YearMonth expiryMonth(String yymm) {
        return YearMonth.of(2000 + Integer.parseInt(yymm.substring(0, 2)), Integer.parseInt(yymm.substring(2)));
    }

    private static String last4(String pan) {
        return pan.length() >= 4 ? pan.substring(pan.length() - 4) : "0".repeat(4 - pan.length()) + pan;
    }

    private static String newAuthCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
