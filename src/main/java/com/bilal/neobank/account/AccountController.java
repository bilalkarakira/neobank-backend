package com.bilal.neobank.account;

import com.bilal.neobank.account.dto.AccountResponse;
import com.bilal.neobank.account.dto.OpenAccountRequest;
import com.bilal.neobank.interest.InterestProperties;
import com.bilal.neobank.ledger.HoldsProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The owner always comes from the token's subject, never from the request. */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final InterestProperties interestProperties;
    private final HoldsProvider holds;

    public AccountController(AccountService accountService, InterestProperties interestProperties,
            HoldsProvider holds) {
        this.holds = holds;
        this.accountService = accountService;
        this.interestProperties = interestProperties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OpenAccountRequest request) {
        return response(accountService.open(userId(jwt), request), java.math.BigDecimal.ZERO);
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        List<Account> accounts = accountService.list(userId(jwt));
        var held = holds.activeHolds(accounts.stream().map(Account::getId).toList());
        return accounts.stream()
                .map(a -> response(a, held.getOrDefault(a.getId(), java.math.BigDecimal.ZERO))).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Account account = accountService.get(userId(jwt), id);
        return response(account, holds.activeHolds(account.getId()));
    }

    private AccountResponse response(Account account, java.math.BigDecimal held) {
        return AccountResponse.from(account, interestProperties.apyPercent(), held);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
