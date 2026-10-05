package com.bilal.neobank.card.dto;

import com.bilal.neobank.card.CardService.IssuedCard;

/** The answer to issuing a card: the only time the full number and PIN are ever shown. */
public record IssuedCardResponse(CardResponse card, String pan, String pin) {

    public static IssuedCardResponse from(IssuedCard issued) {
        return new IssuedCardResponse(CardResponse.from(issued.view()), issued.pan(), issued.pin());
    }
}
