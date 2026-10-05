package com.bilal.neobank.card.auth;

import com.bilal.neobank.ledger.HoldsProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Answers the ledger's question "how much of this account is reserved?" from the authorizations table. */
@Component
public class CardHolds implements HoldsProvider {

    private final AuthorizationRepository repository;
    private final Clock clock;

    public CardHolds(AuthorizationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public BigDecimal activeHolds(UUID accountId) {
        return repository.sumActiveHolds(accountId, clock.instant());
    }

    @Override
    public Map<UUID, BigDecimal> activeHolds(Collection<UUID> accountIds) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        for (Object[] row : repository.sumActiveHoldsByAccount(accountIds, clock.instant())) {
            result.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return result;
    }
}
