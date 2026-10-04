package com.bilal.neobank.transfer.dto;

/** Deliberately minimal: just enough ("Alice K.") for the sender to confirm they typed the right IBAN. */
public record RecipientResponse(String iban, String ownerName) {
}
