package com.bilal.neobank.auth;

import com.bilal.neobank.account.AccountNotFoundException;
import com.bilal.neobank.auth.PasswordExceptions.SamePasswordException;
import com.bilal.neobank.auth.PasswordExceptions.WrongCurrentPasswordException;
import com.bilal.neobank.ratelimit.LoginLockout;
import com.bilal.neobank.user.User;
import com.bilal.neobank.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Changing a password is the moment a customer says "someone else may have my session", so every other login is ended
 * in the same transaction; only the device that made the change stays signed in.
 */
@Service
public class PasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final LoginLockout lockout;

    public PasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokens, LoginLockout lockout) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.lockout = lockout;
    }

    /**
     * Wrong current passwords count toward the SAME lockout as wrong login passwords (its counters commit even though this
     * transaction rolls back): someone holding a stolen access token must not be able to guess the real password here.
     *
     * @param keepSessionId the caller's own login, which stays signed in; null ends every login
     */
    @Transactional
    public void change(UUID userId, UUID keepSessionId, String currentPassword, String newPassword) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(AccountNotFoundException::new);
        String email = user.getEmail();
        lockout.assertNotLocked(email);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            lockout.recordFailure(email);
            throw new WrongCurrentPasswordException();
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new SamePasswordException();
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokens.revokeAllExcept(userId, keepSessionId);
        lockout.recordSuccess(email);
    }
}
