package com.bilal.neobank.card.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Housekeeping of authorizations. */
@Service
public class AuthorizationMaintenance {

    private final AuthorizationRepository repository;
    private final Clock clock;

    public AuthorizationMaintenance(AuthorizationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Marks holds past their expiry as EXPIRED and returns how many. Only tidiness: an expired hold stopped reserving
     * money at its expires_at, because every holds query filters on it.
     */
    @Transactional
    public int expireOldHolds() {
        return repository.markExpired(clock.instant());
    }
}
