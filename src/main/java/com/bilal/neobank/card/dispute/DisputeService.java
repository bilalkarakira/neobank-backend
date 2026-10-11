package com.bilal.neobank.card.dispute;

import com.bilal.cardnet.iso8583.clearing.ClearingFile;
import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.card.auth.HoldService;
import com.bilal.neobank.ledger.TransactionType;
import com.bilal.neobank.notification.NotificationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * A customer disputes a cleared card payment. Filing is ONE database transaction: the dispute row is
 * inserted first under a unique authorization id (so two clicks make one dispute), then the chargeback item and the provisional
 * credit. The chargeback file for the network is written later by {@link DisputeFileWriter}.
 */
@Service
public class DisputeService {

    /** What the customer and the web app see of one dispute. */
    public record View(UUID id, UUID authorizationId, String merchantName, String last4, String amount, String reason, String note, String status,
            String outcome, Instant filedAt, Instant openedAt, Instant closedAt, Instant answerDueAt) {
    }

    private final JdbcClient jdbc;
    private final HoldService holdService;
    private final DisputePostings postings;
    private final NotificationService notifications;
    private final DisputeProperties properties;
    private final Clock clock;

    public DisputeService(JdbcClient jdbc, HoldService holdService, DisputePostings postings, NotificationService notifications,
            DisputeProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.holdService = holdService;
        this.postings = postings;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public View file(UUID userId, UUID authorizationId, String reason, String note, BigDecimal requestedAmount) {
        record Payment(UUID cardId, UUID accountId, String merchant, String last4, BigDecimal amount, LocalDate businessDate, UUID userId) {
        }
        Payment payment = jdbc.sql("""
                select a.card_id, a.account_id, a.merchant_name, a.last4, i.amount, i.business_date, c.user_id
                from authorizations a join cards c on c.id = a.card_id
                join clearing_items i on i.authorization_id = a.id and i.kind = 'PURCHASE' and i.result in ('POSTED', 'FORCE_POSTED')
                where a.id = :id and a.status = 'CAPTURED' order by i.created_at limit 1
                """).param("id", authorizationId).query((rs, n) -> new Payment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class), rs.getObject(7, UUID.class))).optional()
                .filter(p -> p.userId().equals(userId)).orElseThrow(DisputeExceptions.NotFoundException::new);

        if (reason == null || !ClearingFile.DISPUTE_REASONS.contains(reason)) {
            throw new DisputeExceptions.NotAllowedException("Choose a reason: " + new java.util.TreeSet<>(ClearingFile.DISPUTE_REASONS) + ".");
        }
        String cleanNote = cleanNote(note);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (ChronoUnit.DAYS.between(payment.businessDate(), today) > DisputeProperties.MAX_AGE_DAYS) {
            throw new DisputeExceptions.NotAllowedException("This payment is more than " + DisputeProperties.MAX_AGE_DAYS + " days old and can no longer be disputed.");
        }
        BigDecimal amount = requestedAmount == null ? payment.amount() : requestedAmount;
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(payment.amount()) > 0) {
            throw new DisputeExceptions.NotAllowedException("The amount must be more than zero and at most " + money(payment.amount()) + ", the amount of the payment.");
        }

        Account account = holdService.lockAccount(payment.accountId()); // account row first
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new DisputeExceptions.NotAllowedException("Your account is not active, so a dispute cannot be filed now.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into card_disputes (id, authorization_id, card_id, user_id, account_id, merchant_name, last4, amount, reason, note, status, filed_at)
                    values (:id, :auth, :card, :user, :account, :merchant, :last4, :amount, :reason, :note, 'FILED', :now)
                    """).param("id", id).param("auth", authorizationId).param("card", payment.cardId()).param("user", userId)
                    .param("account", payment.accountId()).param("merchant", payment.merchant()).param("last4", payment.last4())
                    .param("amount", amount).param("reason", reason).param("note", cleanNote).param("now", Timestamp.from(now)).update();
        } catch (DuplicateKeyException e) {
            throw new DisputeExceptions.NotAllowedException("This payment was already disputed.");
        }
        UUID item = UUID.randomUUID();
        jdbc.sql("""
                insert into clearing_items (id, file_id, record_no, kind, amount, last4, merchant_name, authorization_id, card_id, result, created_at, business_date)
                values (:id, null, 0, 'CHARGEBACK', :amount, :last4, :merchant, :auth, :card, 'CHARGEBACK_SENT', :now, :date)
                """).param("id", item).param("amount", amount).param("last4", payment.last4()).param("merchant", payment.merchant())
                .param("auth", authorizationId).param("card", payment.cardId()).param("now", Timestamp.from(now)).param("date", today).update();
        jdbc.sql("update card_disputes set chargeback_item_id = :item where id = :id").param("item", item).param("id", id).update();

        postings.credit(payment.accountId(), id.toString(), TransactionType.CARD_PROVISIONAL, "Card dispute credit: " + payment.merchant(), amount, now);
        notifications.disputeFiled(userId, payment.merchant(), money(amount));
        return find(id);
    }

    /** The customer's disputes, newest first. */
    @Transactional(readOnly = true)
    public List<View> list(UUID userId) {
        return jdbc.sql(SELECT + " where d.user_id = :user order by d.filed_at desc, d.id desc limit 100").param("user", userId)
                .query((rs, n) -> view(rs)).list();
    }

    @Transactional(readOnly = true)
    public View find(UUID id) {
        return jdbc.sql(SELECT + " where d.id = :id").param("id", id).query((rs, n) -> view(rs)).single();
    }

    private static final String SELECT = """
            select d.id, d.authorization_id, d.merchant_name, d.last4, d.amount, d.reason, d.note, d.status, d.outcome, d.filed_at, d.opened_at, d.closed_at
            from card_disputes d
            """;

    private View view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant opened = instant(rs.getObject(11, OffsetDateTime.class));
        String status = rs.getString(8);
        return new View(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), money(rs.getBigDecimal(5)),
                rs.getString(6), rs.getString(7), status, rs.getString(9), instant(rs.getObject(10, OffsetDateTime.class)), opened,
                instant(rs.getObject(12, OffsetDateTime.class)), status.equals("OPEN") && opened != null ? opened.plus(properties.window()) : null);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** At most 200 printable ASCII characters; the bar that separates the fields of the message and anything else becomes a space. */
    static String cleanNote(String note) {
        String text = note == null ? "" : note.strip();
        StringBuilder out = new StringBuilder();
        for (char ch : text.toCharArray()) {
            out.append(ch >= 0x20 && ch <= 0x7E && ch != '|' ? ch : ' ');
        }
        if (out.length() > ClearingFile.MAX_NOTE) {
            throw new DisputeExceptions.NotAllowedException("The note can be at most " + ClearingFile.MAX_NOTE + " characters.");
        }
        return out.toString();
    }

    static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
