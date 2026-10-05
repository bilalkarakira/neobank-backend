package com.bilal.neobank.auth;

import com.bilal.neobank.auth.dto.LoginRequest;
import com.bilal.neobank.auth.dto.RegisterRequest;
import com.bilal.neobank.auth.dto.TokenResponse;
import com.bilal.neobank.auth.dto.UserResponse;
import com.bilal.neobank.ratelimit.ClientIp;
import com.bilal.neobank.ratelimit.LoginLockout;
import com.bilal.neobank.ratelimit.RateLimitProperties;
import com.bilal.neobank.ratelimit.RateLimiter;
import com.bilal.neobank.security.JwtService;
import com.bilal.neobank.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final RefreshCookies cookies;
    private final RateLimiter rateLimiter;
    private final LoginLockout lockout;
    private final RateLimitProperties limits;

    public AuthController(AuthService authService, JwtService jwtService, RefreshTokenService refreshTokens,
            RefreshCookies cookies, RateLimiter rateLimiter, LoginLockout lockout, RateLimitProperties limits) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.rateLimiter = rateLimiter;
        this.lockout = lockout;
        this.limits = limits;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimiter.check("register-ip:" + ClientIp.of(http), limits.registerPerIp());
        return UserResponse.from(authService.register(request));
    }

    /**
     * The access token is in the body (the app keeps it in memory); the refresh token is an httpOnly cookie.
     * Order matters: the cheap address limit first, then the per-email lock, and only then the (slow) password check,
     * so a locked or flooding client costs us almost nothing and learns nothing.
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimiter.check("login-ip:" + ClientIp.of(http), limits.loginPerIp());
        String email = AuthService.normalizeEmail(request.email());
        lockout.assertNotLocked(email);
        User user;
        try {
            user = authService.authenticate(request);
        } catch (InvalidCredentialsException e) {
            lockout.recordFailure(email);
            throw e;
        }
        lockout.recordSuccess(email);
        RefreshTokenService.Issued refresh = refreshTokens.issueForLogin(user, DeviceLabel.of(http.getHeader(HttpHeaders.USER_AGENT)),
                ClientIp.of(http));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.set(refresh))
                .body(jwtService.issue(user, refresh.familyId()));
    }

    /** Exchanges the refresh cookie for a new access token and a new refresh cookie (rotation). */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestHeader(value = MissingCsrfHeaderException.HEADER, required = false) String marker,
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken, HttpServletRequest http) {
        requireMarker(marker);
        rateLimiter.check("refresh-ip:" + ClientIp.of(http), limits.refreshPerIp());
        try {
            RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken);
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.set(rotation.issued()))
                    .body(jwtService.issue(rotation.user(), rotation.issued().familyId()));
        } catch (InvalidRefreshTokenException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                    "The session has expired. Please log in again.");
            problem.setTitle("Session expired");
            problem.setProperty("code", "INVALID_REFRESH_TOKEN");
            // the cookie is useless now, so tell the browser to drop it
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, cookies.clear())
                    .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }
    }

    /** Ends this device's login. Always succeeds, so a client with a stale cookie can still log out. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = MissingCsrfHeaderException.HEADER, required = false) String marker,
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
        requireMarker(marker);
        refreshTokens.revokeLoginOf(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    /** Ends every login of the signed-in user. Access tokens already issued still work until they expire. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        refreshTokens.revokeAllFor(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    private static void requireMarker(String marker) {
        if (!MissingCsrfHeaderException.VALUE.equals(marker)) {
            throw new MissingCsrfHeaderException();
        }
    }
}
