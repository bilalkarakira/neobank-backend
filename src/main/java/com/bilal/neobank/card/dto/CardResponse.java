package com.bilal.neobank.card.dto;

import com.bilal.neobank.card.Card;
import com.bilal.neobank.card.CardService.CardView;
import com.bilal.neobank.card.CardStatus;
import com.bilal.neobank.card.FreezeReason;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** A card as the customer sees it: masked number, never the PAN or PIN. Money is a string (never floating point). */
public record CardResponse(UUID id, UUID accountId, String accountName, String maskedPan, String last4, String expiry,
        CardStatus status, FreezeReason freezeReason, String nickname, String dailyLimit, String spentToday,
        String ledgerBalance, String availableBalance, String heldAmount, Instant createdAt) {

    public static CardResponse from(CardView view) {
        Card card = view.card();
        BigDecimal balance = view.account().getBalance();
        String name = view.account().getNickname() != null ? view.account().getNickname()
                : view.account().getType().name().charAt(0) + view.account().getType().name().substring(1).toLowerCase();
        return new CardResponse(card.getId(), card.getAccountId(), name,
                card.getBin().substring(0, 4) + " " + card.getBin().substring(4) + "** **** " + card.getLast4(),
                card.getLast4(), card.getExpiry().substring(2) + "/" + card.getExpiry().substring(0, 2),
                card.getStatus(), card.getFreezeReason(), card.getNickname(), money(card.getDailyLimit()),
                money(view.spentToday()), money(balance), money(balance.subtract(view.heldOnAccount())),
                money(view.heldOnAccount()), card.getCreatedAt());
    }

    static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
