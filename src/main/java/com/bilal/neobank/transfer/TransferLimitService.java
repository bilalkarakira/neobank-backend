package com.bilal.neobank.transfer;

import com.bilal.neobank.ledger.LedgerTransactionRepository;
import com.bilal.neobank.ledger.TransactionType;
import com.bilal.neobank.transfer.dto.TransferLimitsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class TransferLimitService {

    private final LedgerTransactionRepository transactionRepository;
    private final TransferProperties properties;
    private final Clock clock;

    public TransferLimitService(LedgerTransactionRepository transactionRepository, TransferProperties properties,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public BigDecimal dailyLimit() {
        return properties.dailyLimit();
    }

    /** Sent to other customers since 00:00 UTC today. Transfers between the user's own accounts do not count. */
    @Transactional(readOnly = true)
    public BigDecimal usedToday(UUID userId) {
        Instant startOfDay = LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant();
        return transactionRepository.sumSentToOthersSince(TransactionType.TRANSFER, userId, startOfDay);
    }

    @Transactional(readOnly = true)
    public TransferLimitsResponse limits(UUID userId) {
        BigDecimal used = usedToday(userId);
        BigDecimal remaining = properties.dailyLimit().subtract(used).max(BigDecimal.ZERO);
        return new TransferLimitsResponse(money(properties.dailyLimit()), money(used), money(remaining));
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
