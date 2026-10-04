package com.bilal.neobank.interest.dto;

import com.bilal.neobank.interest.AccrualStatus;

import java.util.List;

/** Money is a decimal string; the monthly rate is a ratio with ten decimals. */
public record InterestResponse(String apyPercent, String earnedThisYear, List<Item> accruals) {

    public record Item(String period, AccrualStatus status, String averageBalance, String monthlyRate, String amount) {
    }
}
