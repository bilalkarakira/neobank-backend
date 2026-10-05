package com.bilal.neobank.security;

import com.bilal.neobank.auth.dto.TokenResponse;
import com.bilal.neobank.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public JwtService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * @param sessionId the login (refresh-token family) this access token belongs to, as the "sid" claim: it is how the
     *                  server knows which entry of the session list is "this device". Null leaves the claim out.
     */
    public TokenResponse issue(User user, UUID sessionId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()));
        if (sessionId != null) {
            builder.claim("sid", sessionId.toString());
        }
        JwtClaimsSet claims = builder.build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.ttl().toSeconds());
    }
}
