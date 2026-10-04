package com.bilal.neobank.auth.dto;

import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String firstName, String lastName, String email, Role role, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFirstName(), user.getLastName(),
                user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
