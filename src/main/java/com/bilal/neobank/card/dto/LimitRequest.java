package com.bilal.neobank.card.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record LimitRequest(@NotNull @Digits(integer = 9, fraction = 2) BigDecimal dailyLimit) {
}
