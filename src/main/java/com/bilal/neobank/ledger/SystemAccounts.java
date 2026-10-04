package com.bilal.neobank.ledger;

import java.util.UUID;

/** Fixed ids of the bank's internal accounts (inserted by migration V4). */
public final class SystemAccounts {

    public static final UUID FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID INTEREST = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private SystemAccounts() {
    }
}
