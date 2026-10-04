package com.bilal.neobank.security;

import com.bilal.neobank.auth.dto.TokenResponse;
import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    private static final SecretKeySpec KEY = new SecretKeySpec(SECRET.getBytes(), "HmacSHA256");

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(15));
    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    private final JwtService jwtService = new JwtService(encoder, properties);

    @Test
    void issuedTokenCarriesExpectedClaimsAndExpiresInFifteenMinutes() {
        User user = new User("bilal@example.com", "hash", "Bilal", "Karakira", Role.CUSTOMER);

        TokenResponse response = jwtService.issue(user);
        Jwt jwt = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(response.accessToken());

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("bilal@example.com");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void secretShorterThan32BytesIsRejected() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
