package com.bilal.neobank.admin;

import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(new AdminProperties(email, password), users, encoder);
    }

    @Test
    void createsTheConfiguredAdministratorWithAHashedPasswordAndANormalisedEmail() {
        when(encoder.encode("AdminPassw0rd!")).thenReturn("hashed");
        when(users.existsByEmail("boss@example.com")).thenReturn(false);

        bootstrap("  Boss@Example.COM ", "AdminPassw0rd!").run(null);

        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("boss@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed"); // never the plain password
    }

    @Test
    void neverChangesOrPromotesAnExistingUser() {
        when(users.existsByEmail("boss@example.com")).thenReturn(true);

        bootstrap("boss@example.com", "AdminPassw0rd!").run(null);

        verify(users, never()).save(any());
    }

    @Test
    void refusesToStartWithAWeakPassword() {
        assertThatThrownBy(() -> bootstrap("boss@example.com", "short").run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 12");
        verify(users, never()).save(any());
        verify(encoder, never()).encode(eq("short"));
    }
}
