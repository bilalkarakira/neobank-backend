package com.bilal.neobank.auth;

import com.bilal.neobank.auth.dto.RegisterRequest;
import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AuthService authService = new AuthService(userRepository, passwordEncoder);

    @Test
    void registerNormalizesEmailHashesPasswordAndAssignsCustomerRole() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = authService.register(
                new RegisterRequest(" Bilal ", "Karakira", "  Bilal@Example.COM ", "S3curepass12"));

        assertThat(user.getEmail()).isEqualTo("bilal@example.com");
        assertThat(user.getFirstName()).isEqualTo("Bilal");
        assertThat(user.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(user.getPasswordHash()).isNotEqualTo("S3curepass12");
        assertThat(passwordEncoder.matches("S3curepass12", user.getPasswordHash())).isTrue();
    }

    @Test
    void registerRejectsExistingEmailWithoutSaving() {
        when(userRepository.existsByEmail("bilal@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Bilal", "Karakira", "BILAL@example.com", "S3curepass12")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).saveAndFlush(any());
    }
}
