package com.bilal.neobank.transfer.dto;

public record TransferLimitsResponse(String dailyLimit, String usedToday, String remaining) {
}
