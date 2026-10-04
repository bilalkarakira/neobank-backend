package com.bilal.neobank.account;

import com.bilal.neobank.account.dto.OpenAccountRequest;
import com.bilal.neobank.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountProperties properties;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository,
            AccountProperties properties) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Transactional
    public Account open(UUID userId, OpenAccountRequest request) {
        // Lock the owner's row so two simultaneous requests cannot both pass the limit check below.
        userRepository.findByIdForUpdate(userId).orElseThrow(AccountNotFoundException::new);

        int max = properties.maxFor(request.type());
        long open = accountRepository.countByUserIdAndTypeAndStatusNot(userId, request.type(), AccountStatus.CLOSED);
        if (open >= max) {
            throw new AccountLimitReachedException(request.type(), max);
        }

        String nickname = request.nickname() == null || request.nickname().isBlank() ? null : request.nickname().trim();
        String iban = IbanGenerator.generate(accountRepository.nextAccountNumber());
        return accountRepository.save(new Account(userId, iban, request.type(), nickname));
    }

    @Transactional(readOnly = true)
    public List<Account> list(UUID userId) {
        return accountRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Someone else's account is reported as "not found", never "forbidden", so its existence is not leaked. */
    @Transactional(readOnly = true)
    public Account get(UUID userId, UUID accountId) {
        return accountRepository.findByIdAndUserId(accountId, userId).orElseThrow(AccountNotFoundException::new);
    }
}
