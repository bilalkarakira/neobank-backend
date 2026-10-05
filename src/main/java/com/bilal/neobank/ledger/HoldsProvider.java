package com.bilal.neobank.ledger;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Money that is reserved but not yet moved (card authorizations). The ledger asks, the card package answers, so the
 * ledger does not depend on cards. Available balance = ledger balance minus this.
 */
public interface HoldsProvider {

    /** Total of the active holds on one account. */
    BigDecimal activeHolds(UUID accountId);

    /** Same for many accounts at once; accounts without holds are absent from the map. */
    Map<UUID, BigDecimal> activeHolds(Collection<UUID> accountIds);
}
