package com.bilal.neobank.card.sca;

import com.bilal.neobank.card.Card;
import com.bilal.neobank.card.CardExceptions;
import com.bilal.neobank.card.CardProperties;
import com.bilal.neobank.card.CardRepository;
import com.bilal.neobank.card.CardStatus;
import com.bilal.neobank.card.sca.CardAuthentication.Status;
import com.bilal.neobank.notification.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Asking the customer to approve an online payment, and what the customer answers. The issuer's engine calls {@link #challenge} inside
 * its own transaction (the notice exists exactly when the authentication does); the customer API calls the rest.
 */
@Service
public class CardAuthenticationService {

    /** What the customer sees of one request: the card is named by its last four digits only. */
    public record Pending(UUID id, String reference, String merchantName, BigDecimal amount, String cardLast4, Instant createdAt, Instant expiresAt) {
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O or 1/I: the shop shows it to a person
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CardAuthenticationRepository authentications;
    private final CardRepository cards;
    private final NotificationService notifications;
    private final CardProperties properties;
    private final Clock clock;

    public CardAuthenticationService(CardAuthenticationRepository authentications, CardRepository cards, NotificationService notifications,
            CardProperties properties, Clock clock) {
        this.authentications = authentications;
        this.cards = cards;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /** A new request for approval of {@code amount} at this shop, valid for the configured time, with a notice to the card's owner. */
    @Transactional
    public CardAuthentication challenge(Card card, String merchantId, String merchantName, BigDecimal amount, Instant now) {
        String reference;
        do {
            reference = newReference();
        } while (authentications.findByReference(reference).isPresent());
        String shop = merchantName == null || merchantName.isBlank() ? "an online shop" : merchantName.strip();
        CardAuthentication created = authentications.saveAndFlush(new CardAuthentication(card.getId(), reference, amount, merchantId,
                shop.length() > 40 ? shop.substring(0, 40) : shop, now, now.plus(properties.scaTtl())));
        notifications.authenticationRequested(card.getUserId(), shop, amount.toPlainString(), card.getLast4(), reference);
        return created;
    }

    /** The customer's open requests (pending and not expired) for all their cards, newest first. */
    @Transactional(readOnly = true)
    public List<Pending> pending(UUID userId) {
        List<Card> owned = cards.findByUserIdAndStatusNotOrderByCreatedAtDesc(userId, CardStatus.CANCELLED);
        if (owned.isEmpty()) {
            return List.of();
        }
        Map<UUID, Card> byId = owned.stream().collect(Collectors.toMap(Card::getId, Function.identity()));
        return authentications.pending(byId.keySet(), clock.instant()).stream()
                .map(a -> new Pending(a.getId(), a.getReference(), a.getMerchantName(), a.getAmount(), byId.get(a.getCardId()).getLast4(),
                        a.getCreatedAt(), a.getExpiresAt()))
                .toList();
    }

    @Transactional
    public void approve(UUID userId, UUID id) {
        decide(userId, id, Status.APPROVED);
    }

    @Transactional
    public void reject(UUID userId, UUID id) {
        decide(userId, id, Status.REJECTED);
    }

    /** Someone else's request and an unknown one look the same (404); one no longer open is a state error. */
    private void decide(UUID userId, UUID id, Status answer) {
        CardAuthentication found = authentications.findById(id).orElseThrow(CardExceptions.CardNotFoundException::new);
        Card card = cards.findById(found.getCardId()).orElseThrow(CardExceptions.CardNotFoundException::new);
        if (!card.getUserId().equals(userId)) {
            throw new CardExceptions.CardNotFoundException();
        }
        if (authentications.decide(id, answer, clock.instant()) != 1) {
            throw new CardExceptions.InvalidStateException("This request is no longer waiting for your answer: it was already answered or it expired.");
        }
    }

    private static String newReference() {
        StringBuilder text = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            text.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }
}
