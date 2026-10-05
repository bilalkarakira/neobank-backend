package com.bilal.neobank.card;

import com.bilal.neobank.card.dto.AuthorizationItem;
import com.bilal.neobank.card.dto.CardResponse;
import com.bilal.neobank.card.dto.IssueCardRequest;
import com.bilal.neobank.card.dto.IssuedCardResponse;
import com.bilal.neobank.card.dto.LimitRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The owner always comes from the token's subject, never from the request. */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    public record AuthorizationPage(List<AuthorizationItem> items, int page, int size, long totalElements) {
    }

    private final CardService cardService;
    private final Clock clock;

    public CardController(CardService cardService, Clock clock) {
        this.cardService = cardService;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedCardResponse issue(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IssueCardRequest request) {
        return IssuedCardResponse.from(cardService.issue(userId(jwt), request.accountId(), request.nickname()));
    }

    @GetMapping
    public List<CardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return cardService.list(userId(jwt)).stream().map(CardResponse::from).toList();
    }

    @PostMapping("/{id}/freeze")
    public CardResponse freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return CardResponse.from(cardService.freeze(userId(jwt), id));
    }

    @PostMapping("/{id}/unfreeze")
    public CardResponse unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return CardResponse.from(cardService.unfreeze(userId(jwt), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        cardService.cancel(userId(jwt), id);
    }

    @PutMapping("/{id}/limit")
    public CardResponse limit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody LimitRequest request) {
        return CardResponse.from(cardService.changeLimit(userId(jwt), id, request.dailyLimit()));
    }

    /** Newest first; {@code pending=true} lists only the holds that still reserve money. */
    @GetMapping("/{id}/authorizations")
    public AuthorizationPage authorizations(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean pending, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Instant now = clock.instant();
        if (pending) {
            List<AuthorizationItem> items = cardService.pendingHolds(userId(jwt), id).stream()
                    .map(a -> AuthorizationItem.from(a, now)).toList();
            return new AuthorizationPage(items, 0, items.size(), items.size());
        }
        Page<com.bilal.neobank.card.auth.Authorization> result = cardService.authorizations(userId(jwt), id, page, size);
        return new AuthorizationPage(result.getContent().stream().map(a -> AuthorizationItem.from(a, now)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
