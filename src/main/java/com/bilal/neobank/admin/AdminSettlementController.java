package com.bilal.neobank.admin;

import com.bilal.neobank.card.clearing.SettlementIngestService;
import com.bilal.neobank.card.clearing.SettlementReconciliation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Admin view of settlement and reconciliation (ADMIN only: SecurityConfig guards /api/admin/**): the settlement statements of the
 * card network, the three-way reconciliation of a business day and the button that looks for new statements.
 */
@RestController
public class AdminSettlementController {

    public record IngestResult(int matched, int disputed, int refused) {
    }

    private final SettlementReconciliation reconciliation;
    private final SettlementIngestService ingest;
    private final AdminRepository adminRepository;
    private final Clock clock;

    public AdminSettlementController(SettlementReconciliation reconciliation, SettlementIngestService ingest, AdminRepository adminRepository,
            Clock clock) {
        this.reconciliation = reconciliation;
        this.ingest = ingest;
        this.adminRepository = adminRepository;
        this.clock = clock;
    }

    /** The reconciliation of one business day, or of everything when no date is given. */
    @GetMapping("/api/admin/settlement")
    public SettlementReconciliation.Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.report(date);
    }

    /** Looks for new settlement statements now (the background job does the same every few seconds). Recorded in the audit log. */
    @PostMapping("/api/admin/settlement/ingest")
    public IngestResult runIngest(@AuthenticationPrincipal Jwt jwt) {
        SettlementIngestService.Summary summary = ingest.ingest();
        adminRepository.insertAction(UUID.fromString(jwt.getSubject()), "RUN_SETTLEMENT_INGEST", null, null,
                "matched=%d disputed=%d refused=%d".formatted(summary.matched(), summary.disputed(), summary.refused()),
                clock.instant().atOffset(ZoneOffset.UTC));
        return new IngestResult(summary.matched(), summary.disputed(), summary.refused());
    }
}
