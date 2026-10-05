package com.bilal.neobank.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Makes ending a session take effect at once. An access token is a signed, self-contained pass that is valid for 15
 * minutes by itself, so without this check a revoked session (logout, a removed device, a password change, detected
 * token theft) would keep working until the pass expired. Every request that carries a session id ("sid") is checked
 * against the refresh-token family it belongs to.
 *
 * Cost: one indexed lookup per authenticated request. There is deliberately no cache: a cache would bring back exactly
 * the delay this removes.
 *
 * Tokens without a sid (issued before sessions existed) are accepted until they expire, as before.
 */
@Component
public class SessionStatusValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ENDED = new OAuth2Error("invalid_token", "The session has ended", null);

    private record Family(long tokens, long revoked) {
    }

    private final JdbcClient jdbc;

    public SessionStatusValidator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            return OAuth2TokenValidatorResult.success();
        }
        UUID family;
        try {
            family = UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        Family state = jdbc.sql("""
                select count(*) tokens, count(*) filter (where revoked_at is not null) revoked
                from refresh_tokens where family_id = :family
                """).param("family", family)
                .query((rs, i) -> new Family(rs.getLong("tokens"), rs.getLong("revoked"))).single();
        // revoked, or no trace of the session at all (deleted along with its user): either way it is over
        return state.tokens() == 0 || state.revoked() > 0 ? OAuth2TokenValidatorResult.failure(ENDED)
                : OAuth2TokenValidatorResult.success();
    }
}
