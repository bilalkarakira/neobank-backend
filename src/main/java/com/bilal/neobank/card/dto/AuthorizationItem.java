package com.bilal.neobank.card.dto;

import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationStatus;
import com.bilal.neobank.card.auth.ResponseCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One card payment attempt. A hold that has run past its expiry is shown as EXPIRED even before the nightly job
 * flips the row, because that is when it stopped reserving money. A CAPTURED payment (cleared) shows what was actually
 * posted in {@code capturedAmount}, which can be more than {@code amount} (a tip). {@code cash}: a withdrawal at a cash machine. {@code parentId}: set when this is an increment, the first authorization it adds to.
 * {@code online}: a payment without the card present (entry mode 81x).
 */
public record AuthorizationItem(UUID id, String merchantName, String amount, AuthorizationStatus status,
        String responseCode, String reason, boolean standIn, Instant createdAt, Instant expiresAt, String capturedAmount,
        Instant capturedAt, boolean forced, boolean cash, UUID parentId, boolean online) {

    public static AuthorizationItem from(Authorization a, Instant now) {
        AuthorizationStatus status = a.getStatus() == AuthorizationStatus.AUTHORIZED && !a.holdsMoney(now)
                ? AuthorizationStatus.EXPIRED : a.getStatus();
        return new AuthorizationItem(a.getId(), a.getMerchantName(), CardResponse.money(a.getAmount()), status,
                a.getResponseCode(), ResponseCode.reason(a.getResponseCode()), a.isViaStandIn(), a.getCreatedAt(),
                a.getStatus() == AuthorizationStatus.AUTHORIZED ? a.getExpiresAt() : null,
                a.getCapturedAmount() == null ? null : CardResponse.money(a.getCapturedAmount()), a.getCapturedAt(), a.isForced(),
                a.getProcessingCode() != null && a.getProcessingCode().startsWith("01"), a.getParentId(),
                a.getEntryMode() != null && a.getEntryMode().startsWith("81"));
    }
}
