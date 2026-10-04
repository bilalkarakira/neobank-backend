package com.bilal.neobank.account;

import com.bilal.neobank.account.dto.DepositRequest;
import com.bilal.neobank.account.dto.DepositResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/accounts/{accountId}/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    /** 201 for a new deposit, 200 with the original result when the same Idempotency-Key is replayed. */
    @PostMapping
    public ResponseEntity<DepositResponse> deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        DepositService.Result result = depositService.deposit(UUID.fromString(jwt.getSubject()), accountId,
                idempotencyKey, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.response());
    }
}
