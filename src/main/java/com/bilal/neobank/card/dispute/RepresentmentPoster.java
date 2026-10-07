package com.bilal.neobank.card.dispute;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.neobank.card.CardCrypto;
import com.bilal.neobank.card.clearing.ClearingPoster;
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
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Posts ONE representment (the shop's answer to a chargeback) in ONE database transaction. Like {@link ClearingPoster} the item
 * row is inserted first under a unique (dispute file, record) key, so however many ingests run at once an item is posted once.
 * Lock order as everywhere: the account row first, then the dispute.
 *
 * <p>The network accepted the representment and settles it like a purchase, so NeoBank always posts it: the customer is debited
 * again for what the account can cover and owes the rest. The dispute becomes LOST, also when it was WON already (a late
 * representment: the reconciliation shows it as a finding).
 */
@Service
public class RepresentmentPoster {

    private final JdbcClient jdbc;
    private final CardCrypto crypto;
    private final DisputePostings postings;
    private final NotificationService notifications;
    private final Clock clock;

    public RepresentmentPoster(JdbcClient jdbc, CardCrypto crypto, DisputePostings postings, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.postings = postings;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public ClearingPoster.Result post(UUID fileRow, LocalDate businessDate, int recordNo, Message m) {
        UUID itemId = UUID.randomUUID();
        Instant now = clock.instant();
        BigDecimal amount = new BigDecimal(new BigInteger(m.get(4)), 2);
        String pan = m.get(2);
        String last4 = pan.substring(pan.length() - 4);
        String merchant = m.has(43) && !m.get(43).isBlank() ? m.get(43).strip() : m.get(42).strip();

        int inserted = jdbc.sql("""
                insert into clearing_items (id, file_id, dispute_file_id, record_no, kind, amount, last4, merchant_name, result, created_at, business_date)
                values (:id, null, :file, :rec, 'REPRESENTMENT', :amount, :last4, :merchant, 'PENDING', :now, :date)
                on conflict (dispute_file_id, record_no) where dispute_file_id is not null do nothing
                """).param("id", itemId).param("file", fileRow).param("rec", recordNo).param("amount", amount).param("last4", last4)
                .param("merchant", merchant).param("now", Timestamp.from(now)).param("date", businessDate).update();
        if (inserted == 0) {
            return jdbc.sql("select result, reason from clearing_items where dispute_file_id = :f and record_no = :r")
                    .param("f", fileRow).param("r", recordNo).query((rs, i) -> new ClearingPoster.Result(rs.getString(1), rs.getString(2))).single();
        }

        String panHash = crypto.hashPan(pan);
        record Found(UUID id, UUID accountId) {
        }
        Optional<Found> found = jdbc.sql("""
                select d.id, d.account_id from card_disputes d join authorizations a on a.id = d.authorization_id
                where a.pan_hash = :hash and a.stan = :stan and a.rrn = :rrn
                """).param("hash", panHash).param("stan", m.get(11)).param("rrn", m.get(37))
                .query((rs, i) -> new Found(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
        if (found.isEmpty()) {
            return decided(itemId, "REJECTED", "no dispute for this payment", null, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        UUID accountId = found.get().accountId();
        postings.lockAccountFirst(accountId); // account row first, then the dispute row

        record Dispute(UUID id, UUID userId, UUID cardId, UUID authorizationId, String status, BigDecimal amount, UUID representment, String merchant) {
        }
        Dispute d = jdbc.sql("""
                select id, user_id, card_id, authorization_id, status, amount, representment_item_id, merchant_name from card_disputes where id = :id for update
                """).param("id", found.get().id()).query((rs, i) -> new Dispute(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getBigDecimal(6), rs.getObject(7, UUID.class),
                rs.getString(8))).single();
        if (d.representment() != null) {
            return decided(itemId, "DUPLICATE", "this chargeback was already answered", d.authorizationId(), d.cardId(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (d.status().equals("LOST")) {
            return decided(itemId, "REJECTED", "the dispute is already closed as lost", d.authorizationId(), d.cardId(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (amount.compareTo(d.amount()) > 0) {
            return decided(itemId, "REJECTED", "more than the " + DisputeService.money(d.amount()) + " that was disputed", d.authorizationId(), d.cardId(),
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        DisputePostings.Debit debit = postings.debit(accountId, d.userId(), itemId, itemId.toString(), TransactionType.CARD_REPRESENTED,
                "Card dispute lost: " + d.merchant(), amount, now);
        String note = ClearingFile.noteOf(m);
        boolean late = d.status().equals("WON");
        jdbc.sql("""
                update card_disputes set status = 'LOST', closed_at = :now, representment_item_id = :item, late_representment = :late,
                       outcome = :outcome where id = :id
                """).param("now", Timestamp.from(now)).param("item", itemId).param("late", late)
                .param("outcome", truncate("The shop answered with evidence: " + (note == null ? "" : note), 200)).param("id", d.id()).update();
        String shortfallText = debit.shortfall().signum() > 0 ? "; " + DisputeService.money(debit.shortfall()) + " of it is owed to the bank" : "";
        notifications.disputeLost(d.userId(), d.merchant(), DisputeService.money(amount),
                "the shop proved its case" + shortfallText);
        return decided(itemId, "POSTED", shortfallText.isEmpty() ? "" : "shortfall owed by the customer", d.authorizationId(), d.cardId(),
                debit.covered(), debit.shortfall());
    }

    private ClearingPoster.Result decided(UUID itemId, String result, String reason, UUID authorizationId, UUID cardId, BigDecimal covered, BigDecimal shortfall) {
        String stored = switch (result) {
            case "POSTED" -> "REPRESENTMENT_POSTED";
            default -> result;
        };
        jdbc.sql("""
                update clearing_items set result = :result, reason = :reason, authorization_id = cast(:auth as uuid), card_id = cast(:card as uuid),
                       covered = :covered, shortfall = :shortfall where id = :id
                """).param("result", stored).param("reason", truncate(reason, 120)).param("auth", authorizationId).param("card", cardId)
                .param("covered", covered).param("shortfall", shortfall).param("id", itemId).update();
        return new ClearingPoster.Result(stored, reason);
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }
}
