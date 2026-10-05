package com.bilal.neobank.admin;

import com.bilal.neobank.account.AccountStatus;
import com.bilal.neobank.admin.AdminDtos.AccountPage;
import com.bilal.neobank.admin.AdminDtos.AuditEntry;
import com.bilal.neobank.admin.AdminDtos.Overview;
import com.bilal.neobank.admin.AdminDtos.Reconciliation;
import com.bilal.neobank.admin.AdminDtos.ReasonRequest;
import com.bilal.neobank.admin.AdminDtos.SecuritySnapshot;
import com.bilal.neobank.admin.AdminDtos.UnlockLoginRequest;
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

    /** Final: an account can only be closed when it is empty. */
    @PostMapping("/api/admin/accounts/{id}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        service.closeAccount(UUID.fromString(jwt.getSubject()), id, request.reason());
    }

    /** Opening a customer is recorded in the audit log. */
    @GetMapping("/api/admin/customers/{userId}")
    public AdminDtos.CustomerDetail customer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return service.customer(UUID.fromString(jwt.getSubject()), userId);
    }

    /** What administrators have done about this customer, newest first. */
    @GetMapping("/api/admin/customers/{userId}/audit")
    public List<AuditEntry> customerAudit(@PathVariable UUID userId) {
        return service.customerAudit(userId);
    }

    @PostMapping("/api/admin/accounts/{id}/unfreeze")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request) {
        service.unfreeze(UUID.fromString(jwt.getSubject()), id, request.reason());
    }

    @GetMapping("/api/admin/security")
    public SecuritySnapshot security() {
        return service.security();
    }

    @PostMapping("/api/admin/security/login-lockouts/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlockLogin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UnlockLoginRequest request) {
        service.unlockLogin(UUID.fromString(jwt.getSubject()), request.email(), request.reason());
    }

    @PostMapping("/api/admin/security/probing-blocks/{userId}/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearProbingBlock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @Valid @RequestBody ReasonRequest request) {
        service.clearProbingBlock(UUID.fromString(jwt.getSubject()), userId, request.reason());
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
