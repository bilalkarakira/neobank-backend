package com.bilal.neobank.transfer;

import com.bilal.neobank.transfer.dto.RecipientResponse;
import com.bilal.neobank.transfer.dto.TransferLimitsResponse;
import com.bilal.neobank.transfer.dto.TransferRequest;
import com.bilal.neobank.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class TransferController {

    private final TransferService transferService;
    private final TransferLimitService limitService;

    public TransferController(TransferService transferService, TransferLimitService limitService) {
        this.transferService = transferService;
        this.limitService = limitService;
    }

    /** 201 for a new transfer, 200 with the original result when the same Idempotency-Key is replayed. */
    @PostMapping("/api/transfers")
    public ResponseEntity<TransferResponse> transfer(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferService.Result result = transferService.transfer(UUID.fromString(jwt.getSubject()), idempotencyKey,
                request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.response());
    }

    @GetMapping("/api/transfers/limits")
    public TransferLimitsResponse limits(@AuthenticationPrincipal Jwt jwt) {
        return limitService.limits(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/api/recipients/lookup")
    public RecipientResponse lookup(@AuthenticationPrincipal Jwt jwt, @RequestParam String iban) {
        return transferService.lookup(UUID.fromString(jwt.getSubject()), iban);
    }
}
