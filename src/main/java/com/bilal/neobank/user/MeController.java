package com.bilal.neobank.user;

import com.bilal.neobank.auth.dto.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository userRepository;

    public MeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        // a valid token for a deleted user is no longer a valid session
        return userRepository.findById(UUID.fromString(jwt.getSubject()))
                .map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
