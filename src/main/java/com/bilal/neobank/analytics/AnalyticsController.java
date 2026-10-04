package com.bilal.neobank.analytics;

import com.bilal.neobank.analytics.dto.AnalyticsDtos.BalancePoint;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.CashflowMonth;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.SpendingByCategory;
import com.bilal.neobank.analytics.dto.AnalyticsDtos.Summary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code accountId} is optional everywhere: omitted means "all my accounts". */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID accountId) {
        return service.summary(user(jwt), accountId);
    }

    @GetMapping("/balance-history")
    public List<BalancePoint> balanceHistory(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID accountId, @RequestParam(required = false) Integer days) {
        return service.balanceHistory(user(jwt), accountId, days);
    }

    @GetMapping("/cashflow")
    public List<CashflowMonth> cashflow(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID accountId, @RequestParam(required = false) Integer months) {
        return service.cashflow(user(jwt), accountId, months);
    }

    @GetMapping("/spending-by-category")
    public SpendingByCategory spendingByCategory(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.spendingByCategory(user(jwt), accountId, from, to);
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
