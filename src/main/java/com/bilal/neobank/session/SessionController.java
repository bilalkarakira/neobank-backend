package com.bilal.neobank.session;

import com.bilal.neobank.auth.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Where you are logged in". A session is one login (a refresh-token family). Everything here is scoped to the
 * signed-in user: someone else's session id is simply "not found". "This device" is known from the access token's
 * sid claim.
 */
@RestController
public class SessionController {

    private final RefreshTokenService refreshTokens;

    public SessionController(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/api/sessions")
    public List<SessionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        UUID current = currentSession(jwt);
        return refreshTokens.activeSessions(userId(jwt)).stream()
                .map(s -> new SessionResponse(s.familyId(), s.device() == null ? "Unknown device" : s.device(), s.ip(),
                        s.startedAt().toInstant(), s.lastActiveAt().toInstant(), s.expiresAt().toInstant(),
                        s.familyId().equals(current)))
                .toList();
    }

    /** Ends one login. Ending the current one is allowed: it is a logout, and the client then clears its own state. */
    @DeleteMapping("/api/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!refreshTokens.revokeSession(userId(jwt), id)) {
            throw new SessionNotFoundException();
        }
    }

    /** Ends every login except this device's. */
    @PostMapping("/api/sessions/revoke-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOthers(@AuthenticationPrincipal Jwt jwt) {
        refreshTokens.revokeAllExcept(userId(jwt), currentSession(jwt));
    }

    static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Null for an access token issued before sessions existed: then no entry is "this device". */
    public static UUID currentSession(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        return sid == null ? null : UUID.fromString(sid);
    }
}
