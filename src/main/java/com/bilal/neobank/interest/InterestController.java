package com.bilal.neobank.interest;

import com.bilal.neobank.account.Account;
import com.bilal.neobank.account.AccountService;
import com.bilal.neobank.account.AccountType;
import com.bilal.neobank.interest.dto.InterestResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
public class InterestController {

    private final AccountService accountService;
    private final InterestRepository repository;
    private final InterestProperties properties;
    private final Clock clock;

    public InterestController(AccountService accountService, InterestRepository repository,
            InterestProperties properties, Clock clock) {
        this.accountService = accountService;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** How this account's interest was earned. Owner only; checking accounts simply have none. */
    @GetMapping("/api/accounts/{accountId}/interest")
    public InterestResponse interest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId) {
        Account account = accountService.get(UUID.fromString(jwt.getSubject()), accountId); // 404 unless theirs
        if (account.getType() != AccountType.SAVINGS) {
            return new InterestResponse(null, "0.00", List.of());
        }
        int year = clock.withZone(ZoneOffset.UTC).instant().atOffset(ZoneOffset.UTC).getYear();
        List<InterestResponse.Item> items = repository.list(accountId).stream()
                .map(a -> new InterestResponse.Item(a.period(), a.status(),
                        a.averageBalance().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        a.monthlyRate().toPlainString(), a.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()))
                .toList();
        return new InterestResponse(properties.apyPercent(),
                repository.earnedForYear(accountId, year).setScale(2, RoundingMode.HALF_UP).toPlainString(), items);
    }
}
