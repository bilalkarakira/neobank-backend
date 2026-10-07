package com.bilal.neobank.card.sca;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The customer's side of an online payment that asks for approval. The owner always comes from the token. */
@RestController
@RequestMapping("/api/cards/authentications")
public class CardAuthenticationController {

    public record Item(UUID id, String reference, String merchantName, String amount, String cardLast4, Instant createdAt, Instant expiresAt) {

        static Item from(CardAuthenticationService.Pending p) {
            return new Item(p.id(), p.reference(), p.merchantName(), p.amount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                    p.cardLast4(), p.createdAt(), p.expiresAt());
        }
    }

    private final CardAuthenticationService service;

    public CardAuthenticationController(CardAuthenticationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Item> pending(@AuthenticationPrincipal Jwt jwt) {
        return service.pending(UUID.fromString(jwt.getSubject())).stream().map(Item::from).toList();
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.approve(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.reject(UUID.fromString(jwt.getSubject()), id);
    }
}
