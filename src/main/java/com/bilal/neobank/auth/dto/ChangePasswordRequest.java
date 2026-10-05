package com.bilal.neobank.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The new password follows exactly the same rules as at registration. */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 72) String currentPassword,
        @NotBlank
        @Size(min = 10, max = 72, message = "must be between 10 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "must contain at least one letter and one digit")
        String newPassword) {
}
