package com.bilal.neobank.auth.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
