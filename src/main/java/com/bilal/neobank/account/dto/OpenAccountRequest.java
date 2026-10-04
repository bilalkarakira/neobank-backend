package com.bilal.neobank.account.dto;

import com.bilal.neobank.account.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OpenAccountRequest(@NotNull AccountType type, @Size(max = 40) String nickname) {
}
