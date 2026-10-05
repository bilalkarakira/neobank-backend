package com.bilal.neobank.card.dto;

import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationStatus;
import com.bilal.neobank.card.auth.ResponseCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One card payment attempt. A hold that has run past its expiry is shown as EXPIRED even before the nightly job
 * flips the row, because that is when it stopped reserving money.
 */
public record AuthorizationItem(UUID id, String merchantName, String amount, AuthorizationStatus status,
        String responseCode, String reason, Instant createdAt, Instant expiresAt) {

    public static AuthorizationItem from(Authorization a, Instant now) {
        AuthorizationStatus status = a.getStatus() == AuthorizationStatus.AUTHORIZED && !a.holdsMoney(now)
                ? AuthorizationStatus.EXPIRED : a.getStatus();
        return new AuthorizationItem(a.getId(), a.getMerchantName(), CardResponse.money(a.getAmount()), status,
                a.getResponseCode(), ResponseCode.reason(a.getResponseCode()), a.getCreatedAt(),
                a.getStatus() == AuthorizationStatus.AUTHORIZED ? a.getExpiresAt() : null);
    }
}
