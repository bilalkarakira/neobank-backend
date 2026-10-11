package com.bilal.neobank.card.clearing;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.card.Card;
import com.bilal.neobank.card.CardCrypto;
import com.bilal.neobank.card.CardRepository;
import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationRepository;
import com.bilal.neobank.card.auth.AuthorizationStatus;
import com.bilal.neobank.card.auth.HoldService;
import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.ledger.HoldsProvider;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import com.bilal.neobank.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Posts ONE cleared item (a first presentment, 1240) to the books, in ONE database transaction.
 * The item row is inserted first under a unique (file, record) key, so however many ingests run at once an item is posted once:
 * the second one waits for the first and then finds the row. Lock order as everywhere: the account row first.
 *
 * <p>A purchase is matched to its authorization by the duplicate key (card, acquirer, STAN, RRN, terminal, local date).
 * Money moves for the FINAL amount of the presentment: the part the account can cover goes customer -> card settlement,
 * and the rest (a tip beyond the hold, a hold that lapsed, an account spent meanwhile) goes receivable -> card settlement and
 * becomes something the customer owes. NeoBank pays the network in full either way, as a real issuer must.
 */
@Service
public class ClearingPoster {

    /** What happened to one item; {@code result} is the code the acknowledgement carries. */
    public record Result(String result, String reason) {
    }

    private final JdbcClient jdbc;
    private final CardRepository cards;
    private final CardCrypto crypto;
    private final AuthorizationRepository authorizations;
    private final HoldService holdService;
    private final HoldsProvider holds;
    private final LedgerService ledger;
    private final CardReceivables receivables;
    private final NotificationService notifications;
    private final Clock clock;

    public ClearingPoster(JdbcClient jdbc, CardRepository cards, CardCrypto crypto, AuthorizationRepository authorizations,
            HoldService holdService, HoldsProvider holds, LedgerService ledger, CardReceivables receivables,
            NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.crypto = crypto;
        this.authorizations = authorizations;
        this.holdService = holdService;
        this.holds = holds;
        this.ledger = ledger;
        this.receivables = receivables;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public Result post(UUID fileId, int recordNo, Message m) {
        UUID itemId = UUID.randomUUID();
        Instant now = clock.instant();
        boolean refund = ClearingFile.REFUND.equals(m.get(3));
        BigDecimal amount = new BigDecimal(new BigInteger(m.get(4)), 2);
        String pan = m.get(2);
        String last4 = pan.substring(pan.length() - 4);
        String merchant = merchant(m);

        int inserted = jdbc.sql("""
                insert into clearing_items (id, file_id, record_no, kind, amount, last4, merchant_name, result, created_at, business_date)
                values (:id, :file, :rec, :kind, :amount, :last4, :merchant, 'PENDING', :now, (select business_date from clearing_files where id = :file))
                on conflict (file_id, record_no) do nothing
                """).param("id", itemId).param("file", fileId).param("rec", recordNo).param("kind", refund ? "REFUND" : "PURCHASE")
                .param("amount", amount).param("last4", last4).param("merchant", merchant).param("now", Timestamp.from(now)).update();
        if (inserted == 0) {
            return jdbc.sql("select result, reason from clearing_items where file_id = :f and record_no = :r")
                    .param("f", fileId).param("r", recordNo).query((rs, i) -> new Result(rs.getString(1), rs.getString(2))).single();
        }

        Outcome outcome = refund ? refund(itemId, m, amount, merchant, now) : purchase(itemId, m, amount, merchant, now);
        jdbc.sql("""
                update clearing_items set result = :result, reason = :reason, authorization_id = cast(:auth as uuid), card_id = cast(:card as uuid),
                       covered = :covered, shortfall = :shortfall where id = :id
                """).param("result", outcome.result).param("reason", truncate(outcome.reason, 120)).param("auth", outcome.authorizationId)
                .param("card", outcome.cardId).param("covered", outcome.covered).param("shortfall", outcome.shortfall)
                .param("id", itemId).update();
        return new Result(outcome.result, outcome.reason);
    }

    /** What deciding an item produced, before it is written on the item row. */
    private static final class Outcome {
        String result;
        String reason = "";
        UUID authorizationId;
        UUID cardId;
        BigDecimal covered = BigDecimal.ZERO;
        BigDecimal shortfall = BigDecimal.ZERO;

        static Outcome of(String result, String reason) {
            Outcome o = new Outcome();
            o.result = result;
            o.reason = reason;
            return o;
        }
    }

    // ---- purchases ----

    private Outcome purchase(UUID itemId, Message m, BigDecimal amount, String merchant, Instant now) {
        String panHash = crypto.hashPan(m.get(2));
        Optional<Card> found = cards.findByPanHash(panHash);
        if (found.isEmpty()) {
            return Outcome.of("REJECTED", "unknown card");
        }
        Card card = found.get();
        Account account = holdService.lockAccount(card.getAccountId()); // account row first, then everything else

        Authorization.Request request = request(m, amount);
        Optional<Authorization> match = authorizations.findDuplicate(panHash, request.acquirerId(), request.stan(), request.rrn(),
                request.terminalId(), request.localDate());
        Outcome outcome = Outcome.of("POSTED", "");
        outcome.cardId = card.getId();
        Authorization authorization = match.orElse(null);
        if (authorization != null) {
            outcome.authorizationId = authorization.getId();
            if (authorization.getStatus() == AuthorizationStatus.CAPTURED) {
                outcome.result = "DUPLICATE";
                outcome.reason = "already captured";
                return outcome;
            }
            if (authorization.getStatus() == AuthorizationStatus.REVERSED || authorization.getStatus() == AuthorizationStatus.DECLINED) {
                outcome.result = "REJECTED";
                outcome.reason = "the authorization was " + authorization.getStatus().name().toLowerCase();
                return outcome;
            }
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            outcome.result = "REJECTED";
            outcome.reason = "the account is " + account.getStatus().name().toLowerCase() + ": needs a person";
            return outcome;
        }

        // the hold ends now: the debit below must not be blocked by its own hold
        if (authorization != null) {
            authorization.capture(amount, now);
            authorizations.saveAndFlush(authorization);
            // what the shop added to this payment (a pump that topped up) is part of the amount cleared now: those holds are over too
            authorizations.mergeIncrements(authorization.getId(), now);
        } else {
            authorization = Authorization.forcedCapture(card.getId(), account.getId(), panHash, card.getLast4(), request, m.get(38) == null ? "" : m.get(38), amount, now);
            authorizations.saveAndFlush(authorization);
            outcome.authorizationId = authorization.getId();
            outcome.result = "FORCE_POSTED";
            outcome.reason = "no authorization found: honoured and flagged";
        }

        BigDecimal available = account.getBalance().subtract(holds.activeHolds(account.getId())).max(BigDecimal.ZERO);
        BigDecimal covered = amount.min(available);
        BigDecimal shortfall = amount.subtract(covered);
        if (covered.signum() > 0) {
            post(TransactionType.CARD_PURCHASE, itemId + "-p", merchant, covered, account.getId(), SystemAccounts.CARD_SETTLEMENT);
        }
        if (shortfall.signum() > 0) {
            post(TransactionType.CARD_SHORTFALL, itemId + "-s", "Card shortfall: " + merchant, shortfall,
                    SystemAccounts.CARD_RECEIVABLE, SystemAccounts.CARD_SETTLEMENT);
            receivables.add(card.getUserId(), account.getId(), itemId, shortfall, now);
            notifications.cardShortfall(card.getUserId(), merchant, money(covered), money(shortfall));
            outcome.reason = (outcome.reason.isEmpty() ? "" : outcome.reason + "; ") + "shortfall " + money(shortfall) + " owed by the customer";
        }
        outcome.covered = covered;
        outcome.shortfall = shortfall;
        return outcome;
    }

    // ---- refunds ----

    private Outcome refund(UUID itemId, Message m, BigDecimal amount, String merchant, Instant now) {
        Optional<Card> found = cards.findByPanHash(crypto.hashPan(m.get(2)));
        if (found.isEmpty()) {
            return Outcome.of("REJECTED", "unknown card");
        }
        Card card = found.get();
        Account account = holdService.lockAccount(card.getAccountId());
        Outcome outcome = Outcome.of("REFUND_POSTED", "");
        outcome.cardId = card.getId();
        if (account.getStatus() != AccountStatus.ACTIVE) {
            outcome.result = "REJECTED";
            outcome.reason = "the account is " + account.getStatus().name().toLowerCase() + ": needs a person";
            return outcome;
        }
        // what the customer still owes the bank is paid off first, then the rest goes to the customer
        BigDecimal payoff = amount.min(receivables.outstanding(account.getId()));
        BigDecimal rest = amount.subtract(payoff);
        if (payoff.signum() > 0) {
            post(TransactionType.CARD_REFUND, itemId + "-o", "Refund applied to what you owe: " + merchant, payoff,
                    SystemAccounts.CARD_SETTLEMENT, SystemAccounts.CARD_RECEIVABLE);
            receivables.reduce(account.getId(), payoff, now);
            outcome.reason = "applied " + money(payoff) + " to money the customer owed";
        }
        if (rest.signum() > 0) {
            post(TransactionType.CARD_REFUND, itemId + "-r", "Refund: " + merchant, rest, SystemAccounts.CARD_SETTLEMENT, account.getId());
        }
        return outcome;
    }

    // ---- helpers ----

    /** One two-leg ledger transaction moving {@code amount} from one account to another. */
    private void post(TransactionType type, String key, String description, BigDecimal amount, UUID from, UUID to) {
        String idempotencyKey = "clr-" + key;
        ledger.post(type, null, idempotencyKey.length() > 64 ? idempotencyKey.substring(0, 64) : idempotencyKey,
                Fingerprint.sha256(key + "|" + amount.toPlainString()), truncate(description, 100),
                List.of(new Posting(from, amount.negate()), new Posting(to, amount)));
    }

    private static Authorization.Request request(Message m, BigDecimal amount) {
        return new Authorization.Request("1240", m.get(11), m.get(37), m.get(41).strip(), m.get(42).strip(), optional(m, 43),
                optional(m, 18), padAcquirer(m.get(32)), m.get(3), null, m.get(13), m.get(7), amount, m.get(49));
    }

    private static String merchant(Message m) {
        String name = optional(m, 43);
        return name == null ? m.get(42).strip() : name;
    }

    private static String optional(Message m, int field) {
        String value = m.get(field);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** DE 32 is 1-11 digits; stored left-padded to 11 so it compares with the acquirer of the authorization. */
    private static String padAcquirer(String de32) {
        return "0".repeat(Math.max(0, 11 - de32.length())) + de32;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }
}
