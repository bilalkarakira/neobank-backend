package com.bilal.neobank.card.dto;

import com.bilal.neobank.card.CardService.IssuedCard;

/** The answer to issuing a card: the only time the full number, PIN and security code are ever shown. */
public record IssuedCardResponse(CardResponse card, String pan, String pin, String cvv) {

    public static IssuedCardResponse from(IssuedCard issued) {
        return new IssuedCardResponse(CardResponse.from(issued.view()), issued.pan(), issued.pin(), issued.cvv());
    }
}
