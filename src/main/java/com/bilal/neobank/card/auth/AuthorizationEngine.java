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
 * answers (see docs/SPEC-P2-issuer.md section 4). Everything happens in ONE transaction that holds the account's row
 * lock from the first read of the balance to the insert of the hold, so concurrent requests for one account queue up.
 *
 * <p>Declines are recorded too (they are replayed for a duplicate and shown to the customer). The only exceptions are
 * answers given before the request could be identified. An unexpected exception rolls everything back.
 */
@Service
public class AuthorizationEngine {

    /** What the issuer needs from an authorization request, already parsed from the message. */
    public record AuthInput(String pan, String expiry, String pinBlock, Authorization.Request request) {
    }

    /** A reversal: the original is identified by DE 90 (STAN, transmission time, acquirer) and the card. */
    public record ReversalInput(String pan, BigDecimal amount, String originalStan, String originalTransmittedAt,
            Authorization.Request request) {
    }

    /** The answer: DE39 and, for an approval, the authorization code (DE38). */
    public record Decision(String code, String authCode) {
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

    public AuthorizationEngine(CardRepository cardRepository, AuthorizationRepository authorizationRepository,
            HoldService holdService, CardCrypto crypto, CardProperties properties,
            NotificationService notifications, EntityManager entityManager, Clock clock) {
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
        // 7. what the card may do: purchases (processing code 00) in USD, not at blocked merchant categories
        if (!"00".equals(r.processingCode().substring(0, 2)) || !"840".equals(r.currency())
                || properties.blockedMcc().contains(r.mcc())) {
            return decline(card, account, panHash, last4, r, ResponseCode.NOT_PERMITTED, now);
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
        // 10 + 11. funds, then the hold, under the account lock
        String authCode = newAuthCode();
        Authorization approved = Authorization.approved(card.getId(), account.getId(), panHash, last4, r, authCode, now,
                now.plus(properties.holdTtl()));
        if (!holdService.tryReserve(approved)) {
            return decline(card, account, panHash, last4, r, ResponseCode.INSUFFICIENT_FUNDS, now);
        }
        return new Decision(ResponseCode.APPROVED, authCode);
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
        card.setPinFailures(card.getPinFailures() + 1);
        if (card.getPinFailures() >= properties.maxPinFailures()) {
            card.freeze(FreezeReason.PIN_TRIES);
            cardRepository.save(card);
            notifications.cardFrozenByPin(card.getUserId(), card.getLast4());
            return ResponseCode.PIN_TRIES_EXCEEDED;
        }
        cardRepository.save(card);
        return ResponseCode.WRONG_PIN;
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
