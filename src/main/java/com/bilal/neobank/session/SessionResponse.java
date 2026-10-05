package com.bilal.neobank.session;

import java.time.Instant;
import java.util.UUID;

/**
 * @param device       a readable label such as "Chrome on Windows"
 * @param lastActiveAt the last time this login renewed its access token (so accurate to about 15 minutes)
 * @param current      true for the device making this request
 */
public record SessionResponse(UUID id, String device, String ip, Instant startedAt, Instant lastActiveAt,
        Instant expiresAt, boolean current) {
}
