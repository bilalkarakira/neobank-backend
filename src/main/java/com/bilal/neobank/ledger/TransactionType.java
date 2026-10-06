package com.bilal.neobank.ledger;

public enum TransactionType {
    DEPOSIT,
    TRANSFER,
    /** Savings interest credited by the system. */
    INTEREST,
    /** A cleared card payment: the customer's account to the card settlement account. */
    CARD_PURCHASE,
    /** A cleared card refund: the card settlement account back to the customer (or to what the customer owed). */
    CARD_REFUND,
    /** The part of a cleared payment the customer could not cover: the bank pays the network and carries a receivable. */
    CARD_SHORTFALL,
    /** The customer's money used to pay off a card shortfall. */
    CARD_COLLECTION,
    /** Settlement of a business day of cleared card payments with the settlement bank (system only). */
    CARD_SETTLEMENT
}
