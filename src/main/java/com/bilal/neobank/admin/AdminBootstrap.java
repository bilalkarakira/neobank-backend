package com.bilal.neobank.admin;

import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Creates the configured administrator if it does not exist yet. An existing user is never promoted or changed:
 * becoming an administrator must not be possible by registering first and having someone set a property later.
 */
@Component
@ConditionalOnProperty(prefix = "app.admin", name = { "email", "password" })
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(AdminProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.email().trim().toLowerCase(Locale.ROOT);
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            // refuse to start with a weak admin password rather than quietly creating a weak admin
            throw new IllegalStateException("app.admin.password must be at least %d characters".formatted(MIN_PASSWORD_LENGTH));
        }
        if (userRepository.existsByEmail(email)) {
            log.info("Administrator bootstrap: {} already exists, left unchanged", email);
            return;
        }
        userRepository.save(new User(email, passwordEncoder.encode(properties.password()), "Bank", "Admin", Role.ADMIN));
        log.info("Administrator {} created", email);
    }
}
