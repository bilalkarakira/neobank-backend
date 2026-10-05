package com.bilal.neobank.card.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record IssueCardRequest(@NotNull UUID accountId, @Size(max = 40) String nickname) {
}
