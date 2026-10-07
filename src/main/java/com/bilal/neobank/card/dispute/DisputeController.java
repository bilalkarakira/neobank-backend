package com.bilal.neobank.card.dispute;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The customer's side of disputes. The owner always comes from the token's subject, never from the request. */
@RestController
@RequestMapping("/api/cards")
public class DisputeController {

    /** {@code amount} is optional: without it the whole payment is disputed. */
    public record DisputeRequest(@NotBlank @Size(max = 20) String reason, @Size(max = 200) String note,
            @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }

    private final DisputeService service;

    public DisputeController(DisputeService service) {
        this.service = service;
    }

    @PostMapping("/payments/{authorizationId}/dispute")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeService.View file(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID authorizationId, @Valid @RequestBody DisputeRequest request) {
        return service.file(UUID.fromString(jwt.getSubject()), authorizationId, request.reason(), request.note(), request.amount());
    }

    @GetMapping("/disputes")
    public List<DisputeService.View> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(UUID.fromString(jwt.getSubject()));
    }
}
