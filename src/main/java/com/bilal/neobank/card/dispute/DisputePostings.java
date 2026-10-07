package com.bilal.neobank.card.dispute;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.card.auth.HoldService;
import com.bilal.neobank.card.clearing.CardReceivables;
import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.ledger.HoldsProvider;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The two money moves of a dispute, each in the caller's transaction. Both lock the account row first (the application's lock
 * order) and use the same rules as cleared refunds and purchases:
 * <ul>
 *   <li>{@link #credit}: card settlement account -> customer, but what the customer still owes the bank (an earlier shortfall) is
 *       paid off first, exactly as for a refund;</li>
 *   <li>{@link #debit}: customer -> card settlement account for what the account can cover; the rest becomes a receivable the
 *       customer owes. A frozen or closed account covers nothing: all of it is a receivable, so the books never depend on the
 *       account's state (the network has counted the item already).</li>
 * </ul>
 */
@Component
public class DisputePostings {

    /** What a debit took from the account and what it left as a debt. */
    public record Debit(BigDecimal covered, BigDecimal shortfall) {
    }

    private final HoldService holdService;
    private final HoldsProvider holds;
    private final LedgerService ledger;
    private final CardReceivables receivables;

    public DisputePostings(HoldService holdService, HoldsProvider holds, LedgerService ledger, CardReceivables receivables) {
        this.holdService = holdService;
        this.holds = holds;
        this.ledger = ledger;
        this.receivables = receivables;
    }

    /** Takes the account's row lock now, so a caller that must lock other rows afterwards keeps the lock order (account first). */
    public void lockAccountFirst(UUID accountId) {
        holdService.lockAccount(accountId);
    }

    public void credit(UUID accountId, String key, TransactionType type, String description, BigDecimal amount, Instant now) {
        holdService.lockAccount(accountId);
        BigDecimal payoff = amount.min(receivables.outstanding(accountId));
        BigDecimal rest = amount.subtract(payoff);
        if (payoff.signum() > 0) {
            post(type, key + "-o", "Applied to what you owe: " + description, payoff, SystemAccounts.CARD_SETTLEMENT, SystemAccounts.CARD_RECEIVABLE);
            receivables.reduce(accountId, payoff, now);
        }
        if (rest.signum() > 0) {
            post(type, key + "-r", description, rest, SystemAccounts.CARD_SETTLEMENT, accountId);
        }
    }

    public Debit debit(UUID accountId, UUID userId, UUID itemId, String key, TransactionType type, String description, BigDecimal amount, Instant now) {
        Account account = holdService.lockAccount(accountId);
        BigDecimal covered = BigDecimal.ZERO;
        if (account.getStatus() == AccountStatus.ACTIVE) {
            covered = amount.min(account.getBalance().subtract(holds.activeHolds(accountId)).max(BigDecimal.ZERO));
        }
        BigDecimal shortfall = amount.subtract(covered);
        if (covered.signum() > 0) {
            post(type, key + "-c", description, covered, accountId, SystemAccounts.CARD_SETTLEMENT);
        }
        if (shortfall.signum() > 0) {
            post(TransactionType.CARD_SHORTFALL, key + "-s", "Card shortfall: " + description, shortfall, SystemAccounts.CARD_RECEIVABLE,
                    SystemAccounts.CARD_SETTLEMENT);
            receivables.add(userId, accountId, itemId, shortfall, now);
        }
        return new Debit(covered, shortfall);
    }

    private void post(TransactionType type, String key, String description, BigDecimal amount, UUID from, UUID to) {
        String idempotencyKey = "dsp-" + key;
        ledger.post(type, null, idempotencyKey.length() > 64 ? idempotencyKey.substring(0, 64) : idempotencyKey,
                Fingerprint.sha256(key + "|" + amount.toPlainString()), description.length() > 100 ? description.substring(0, 100) : description,
                List.of(new Posting(from, amount.negate()), new Posting(to, amount)));
    }
}
