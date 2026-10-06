package com.bilal.neobank.ledger;

import java.util.UUID;

/** Fixed ids of the bank's internal accounts (inserted by migration V4). */
public final class SystemAccounts {

    public static final UUID FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID INTEREST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** What NeoBank owes the card network for cleared payments (migration V18). */
    public static final UUID CARD_SETTLEMENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    /** What customers owe NeoBank for the part of a cleared card payment their account could not cover (V18). */
    public static final UUID CARD_RECEIVABLE = UUID.fromString("00000000-0000-0000-0000-000000000006");

    /** Interchange NeoBank earned less the scheme fees it paid (migration V19). */
    public static final UUID INTERCHANGE_INCOME = UUID.fromString("00000000-0000-0000-0000-000000000004");
    /** What NeoBank has paid out to the settlement bank, net (V19). */
    public static final UUID SETTLEMENT_BANK = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private SystemAccounts() {
    }
}
