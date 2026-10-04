package com.bilal.neobank.recurring;

import com.bilal.neobank.recurring.RecurringRepository.Row;
import com.bilal.neobank.recurring.dto.RecurringDtos.CreateRequest;
import com.bilal.neobank.recurring.dto.RecurringDtos.PaymentResponse;
import com.bilal.neobank.recurring.dto.RecurringDtos.RunResponse;
import com.bilal.neobank.recurring.dto.RecurringDtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@RestController
public class RecurringController {

    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @PostMapping("/api/recurring-payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        return toResponse(service.create(userId(jwt), request));
    }

    @GetMapping("/api/recurring-payments")
    public List<PaymentResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt)).stream().map(RecurringController::toResponse).toList();
    }

    @GetMapping("/api/recurring-payments/{id}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return toResponse(service.get(userId(jwt), id));
    }

    @PutMapping("/api/recurring-payments/{id}")
    public PaymentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateRequest request) {
        return toResponse(service.update(userId(jwt), id, request));
    }

    @PostMapping("/api/recurring-payments/{id}/pause")
    public PaymentResponse pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return toResponse(service.pause(userId(jwt), id));
    }

    @PostMapping("/api/recurring-payments/{id}/resume")
    public PaymentResponse resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return toResponse(service.resume(userId(jwt), id));
    }

    @DeleteMapping("/api/recurring-payments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.cancel(userId(jwt), id);
    }

    @GetMapping("/api/recurring-payments/{id}/runs")
    public List<RunResponse> runs(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.runs(userId(jwt), id).stream()
                .map(r -> new RunResponse(r.runDate(), r.status(), r.failureCode(), r.failureMessage(),
                        r.transactionId(), r.executedAt().toInstant()))
                .toList();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static PaymentResponse toResponse(Row r) {
        return new PaymentResponse(r.id(), r.sourceAccountId(), r.destinationIban(),
                r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), r.description(), r.categoryId(),
                r.frequency(), r.startDate(), r.endDate(), r.nextRunDate(), r.status(), r.autoPaused(),
                r.consecutiveFailures(), r.createdAt().toInstant());
    }
}
