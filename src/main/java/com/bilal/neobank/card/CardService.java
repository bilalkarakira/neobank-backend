package com.bilal.neobank.card;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.account.AccountRepository;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationRepository;
import com.bilal.neobank.ledger.HoldsProvider;
import com.bilal.neobank.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Issuing and managing cards. Everything is scoped to the owner: someone else's card is "not found". */
@Service
public class CardService {

    public static final String BIN = "999900";
    public static final int MAX_PAGE_SIZE = 50;

    /** A card with the figures the Cards page shows next to it. */
    public record CardView(Card card, Account account, BigDecimal heldOnAccount, BigDecimal spentToday) {
    }

    /** The one moment the full number and PIN exist outside the bank's hashing. */
    public record IssuedCard(CardView view, String pan, String pin) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuthorizationRepository authorizationRepository;
    private final HoldsProvider holds;
    private final CardCrypto crypto;
    private final CardProperties properties;
    private final Clock clock;

    public CardService(CardRepository cardRepository, AccountRepository accountRepository,
            UserRepository userRepository, AuthorizationRepository authorizationRepository, HoldsProvider holds,
            CardCrypto crypto, CardProperties properties, Clock clock) {
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.authorizationRepository = authorizationRepository;
        this.holds = holds;
        this.crypto = crypto;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedCard issue(UUID userId, UUID accountId, String nickname) {
        // lock the owner's row so two simultaneous requests cannot both pass the limit below
        userRepository.findByIdForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(AccountNotFoundException::new);
        if (account.getType() != AccountType.CHECKING || account.getStatus() != AccountStatus.ACTIVE) {
            throw new CardExceptions.AccountNotEligibleException();
        }
        if (cardRepository.countByUserIdAndStatusNot(userId, CardStatus.CANCELLED) >= properties.maxPerCustomer()) {
            throw new CardExceptions.CardLimitReachedException(properties.maxPerCustomer());
        }

        String pan;
        String panHash;
        do {
            pan = newPan();
            panHash = crypto.hashPan(pan);
        } while (cardRepository.existsByPanHash(panHash));

        String pin = "%04d".formatted(RANDOM.nextInt(10_000));
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        YearMonth expiry = YearMonth.now(clock).plusYears(3);
        String expiryText = "%02d%02d".formatted(expiry.getYear() % 100, expiry.getMonthValue());
        String trimmed = nickname == null || nickname.isBlank() ? null : nickname.trim();

        Card card = cardRepository.save(new Card(id, userId, accountId, panHash, pan.substring(12), BIN, expiryText,
                crypto.hashPin(id, pin), properties.defaultDailyLimit(), trimmed, now));
        return new IssuedCard(view(card, account), pan, pin);
    }

    @Transactional(readOnly = true)
    public List<CardView> list(UUID userId) {
        List<Card> cards = cardRepository.findByUserIdAndStatusNotOrderByCreatedAtDesc(userId, CardStatus.CANCELLED);
        Map<UUID, Account> accounts = accountRepository.findAllById(cards.stream().map(Card::getAccountId).distinct().toList())
                .stream().collect(Collectors.toMap(Account::getId, a -> a));
        Map<UUID, BigDecimal> held = holds.activeHolds(accounts.keySet());
        return cards.stream().map(c -> new CardView(c, accounts.get(c.getAccountId()),
                held.getOrDefault(c.getAccountId(), BigDecimal.ZERO), spentToday(c))).toList();
    }

    @Transactional
    public CardView freeze(UUID userId, UUID cardId) {
        Card card = lockOwned(userId, cardId);
        if (card.getStatus() == CardStatus.ACTIVE) {
            card.freeze(FreezeReason.CUSTOMER);
        }
        return view(cardRepository.save(card));
    }

    @Transactional
    public CardView unfreeze(UUID userId, UUID cardId) {
        Card card = lockOwned(userId, cardId);
        if (card.getStatus() == CardStatus.FROZEN) {
            if (card.getFreezeReason() == FreezeReason.ADMIN) {
                throw new CardExceptions.InvalidStateException(
                        "The bank froze this card, so only the bank can unfreeze it.");
            }
            card.unfreeze();
        }
        return view(cardRepository.save(card));
    }

    @Transactional
    public void cancel(UUID userId, UUID cardId) {
        Card card = lockOwned(userId, cardId);
        card.cancel(clock.instant());
        cardRepository.save(card);
    }

    @Transactional
    public CardView changeLimit(UUID userId, UUID cardId, BigDecimal limit) {
        if (limit.signum() <= 0 || limit.compareTo(properties.maxDailyLimit()) > 0) {
            throw new CardExceptions.InvalidLimitException("The daily limit must be between 0.01 and %s."
                    .formatted(properties.maxDailyLimit().toPlainString()));
        }
        Card card = lockOwned(userId, cardId);
        card.changeDailyLimit(limit);
        return view(cardRepository.save(card));
    }

    /** Closing an account ends its cards. The caller holds the account's row lock (lock order: account, then card). */
    @Transactional
    public int cancelCardsOfAccount(UUID accountId) {
        return cardRepository.cancelAllOfAccount(accountId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Page<Authorization> authorizations(UUID userId, UUID cardId, int page, int size) {
        cardRepository.findOwnedNotCancelled(cardId, userId).orElseThrow(CardExceptions.CardNotFoundException::new);
        return authorizationRepository.findByCardIdOrderByCreatedAtDesc(cardId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
    }

    @Transactional(readOnly = true)
    public List<Authorization> pendingHolds(UUID userId, UUID cardId) {
        cardRepository.findOwnedNotCancelled(cardId, userId).orElseThrow(CardExceptions.CardNotFoundException::new);
        return authorizationRepository.findActiveHolds(cardId, clock.instant());
    }

    private Card lockOwned(UUID userId, UUID cardId) {
        return cardRepository.lockOwnedNotCancelled(cardId, userId).orElseThrow(CardExceptions.CardNotFoundException::new);
    }

    private CardView view(Card card) {
        Account account = accountRepository.findById(card.getAccountId()).orElseThrow(AccountNotFoundException::new);
        return view(card, account);
    }

    private CardView view(Card card, Account account) {
        return new CardView(card, account, holds.activeHolds(account.getId()), spentToday(card));
    }

    private BigDecimal spentToday(Card card) {
        Instant startOfDay = LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().toInstant(ZoneOffset.UTC);
        return authorizationRepository.sumApprovedSince(card.getId(), startOfDay);
    }

    /** BIN + 9 random digits + Luhn check digit. */
    static String newPan() {
        StringBuilder body = new StringBuilder(BIN);
        for (int i = 0; i < 9; i++) {
            body.append(RANDOM.nextInt(10));
        }
        return body.append(Luhn.checkDigit(body.toString())).toString();
    }
}
