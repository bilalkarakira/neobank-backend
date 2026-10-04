package com.bilal.neobank.auth;

import com.bilal.neobank.auth.dto.LoginRequest;
import com.bilal.neobank.auth.dto.RegisterRequest;
import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    /** Checked when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public User register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()),
                request.firstName().trim(), request.lastName().trim(), Role.CUSTOMER);
        try {
            // flush now so the UNIQUE constraint fires here if two requests race past the check above
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyRegisteredException(email);
        }
    }

    /** Returns the user if the credentials match; unknown email and wrong password are indistinguishable. */
    public User authenticate(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }
        return user;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
