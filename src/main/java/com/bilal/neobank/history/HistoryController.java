package com.bilal.neobank.history;

import com.bilal.neobank.history.dto.HistoryPage;
import com.bilal.neobank.ledger.TransactionType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/api/accounts/{accountId}/transactions")
    public HistoryPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        HistoryFilter filter = HistoryFilter.of(from, to, type, direction, minAmount, maxAmount, q, sort, page, size);
        return historyService.page(UUID.fromString(jwt.getSubject()), accountId, filter);
    }

    /** Same filters as the list (paging is ignored); capped at app.history.export-max-rows. */
    @GetMapping("/api/accounts/{accountId}/transactions/export.csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort) {
        HistoryFilter filter = HistoryFilter.of(from, to, type, direction, minAmount, maxAmount, q, sort, null, null);
        HistoryService.Export export = historyService.export(UUID.fromString(jwt.getSubject()), accountId, filter);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString());
        if (export.truncated()) {
            response.header("X-Export-Truncated", "true");
        }
        return response.body(export.content());
    }
}
