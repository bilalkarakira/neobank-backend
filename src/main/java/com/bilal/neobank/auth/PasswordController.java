package com.bilal.neobank.auth;

import com.bilal.neobank.auth.dto.ChangePasswordRequest;
import com.bilal.neobank.session.SessionController;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PasswordController {

    private final PasswordService passwordService;

    public PasswordController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    /** Changes the password and ends every other login. 204; the current login stays. */
    @PostMapping("/api/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        passwordService.change(UUID.fromString(jwt.getSubject()), SessionController.currentSession(jwt),
                request.currentPassword(), request.newPassword());
    }
}
