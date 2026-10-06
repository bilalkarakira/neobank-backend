package com.bilal.neobank.card.clearing;

import com.bilal.neobank.card.auth.HoldService;
import com.bilal.neobank.ledger.Fingerprint;
import com.bilal.neobank.ledger.HoldsProvider;
import com.bilal.neobank.ledger.LedgerService;
import com.bilal.neobank.ledger.Posting;
import com.bilal.neobank.ledger.SystemAccounts;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Collects what a customer owes the bank from the money that arrives on the account (a deposit): customer -> card receivable,
 * as much as the available balance (balance minus card holds) allows. A customer whose account holds less than is owed pays
 * the rest with the next deposit.
 */
@Service
public class ReceivableCollection {

    private final CardReceivables receivables;
    private final HoldService holdService;
    private final HoldsProvider holds;
    private final LedgerService ledger;
    private final Clock clock;

    public ReceivableCollection(CardReceivables receivables, HoldService holdService, HoldsProvider holds, LedgerService ledger,
            Clock clock) {
        this.receivables = receivables;
        this.holdService = holdService;
        this.holds = holds;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** @return how much was collected (zero when nothing was owed or nothing was available) */
    @Transactional
    public BigDecimal collectFrom(UUID accountId) {
        BigDecimal owed = receivables.outstanding(accountId);
        if (owed.signum() == 0) {
            return BigDecimal.ZERO;
        }
        var account = holdService.lockAccount(accountId);
        BigDecimal available = account.getBalance().subtract(holds.activeHolds(accountId)).max(BigDecimal.ZERO);
        BigDecimal pay = owed.min(available);
        if (pay.signum() == 0) {
            return BigDecimal.ZERO;
        }
        String key = "clr-col-" + UUID.randomUUID();
        ledger.post(TransactionType.CARD_COLLECTION, null, key, Fingerprint.sha256(key + "|" + pay.toPlainString()),
                "Card payment shortfall repaid", List.of(new Posting(accountId, pay.negate()),
                        new Posting(SystemAccounts.CARD_RECEIVABLE, pay)));
        receivables.reduce(accountId, pay, clock.instant());
        return pay;
    }
}
