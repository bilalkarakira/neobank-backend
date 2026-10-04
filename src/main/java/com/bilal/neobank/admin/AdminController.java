package com.bilal.neobank.admin;

import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.admin.AdminDtos.AccountPage;
import com.bilal.neobank.admin.AdminDtos.AuditEntry;
import com.bilal.neobank.admin.AdminDtos.Overview;
import com.bilal.neobank.admin.AdminDtos.Reconciliation;
import com.bilal.neobank.admin.AdminDtos.ReasonRequest;
import com.bilal.neobank.interest.InterestService;
import com.bilal.neobank.recurring.RecurringExecutor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Reachable only with the ADMIN role: SecurityConfig guards the whole /api/admin/** tree. */
@RestController
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/overview")
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/api/admin/reconciliation")
    public Reconciliation reconciliation() {
        return service.reconciliation();
    }

    @GetMapping("/api/admin/accounts")
    public AccountPage accounts(@RequestParam(required = false) String q,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.accounts(q, status, page, size);
    }

    @PostMapping("/api/admin/accounts/{id}/freeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request) {
        service.freeze(UUID.fromString(jwt.getSubject()), id, request.reason());
    }

    @PostMapping("/api/admin/accounts/{id}/unfreeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request) {
        service.unfreeze(UUID.fromString(jwt.getSubject()), id, request.reason());
    }

    @PostMapping("/api/admin/jobs/interest")
    public InterestService.RunSummary runInterest(@AuthenticationPrincipal Jwt jwt) {
        return service.runInterest(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/api/admin/jobs/recurring")
    public RecurringExecutor.RunSummary runRecurring(@AuthenticationPrincipal Jwt jwt) {
        return service.runRecurring(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/api/admin/audit")
    public List<AuditEntry> audit() {
        return service.audit();
    }
}
