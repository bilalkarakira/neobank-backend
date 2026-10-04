package com.bilal.neobank.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 401/403 responses raised by the security filters (before MVC), written as RFC 7807 problem json. */
public final class ProblemJsonHandlers {

    private ProblemJsonHandlers() {
    }

    public static final AuthenticationEntryPoint ENTRY_POINT = (request, response, ex) -> write(response,
            HttpStatus.UNAUTHORIZED, "Unauthenticated", "Authentication is required to access this resource.",
            "UNAUTHENTICATED");

    public static final AccessDeniedHandler ACCESS_DENIED = (request, response, ex) -> write(response,
            HttpStatus.FORBIDDEN, "Access denied", "You do not have permission to access this resource.",
            "ACCESS_DENIED");

    private static void write(HttpServletResponse response, HttpStatus status, String title, String detail,
            String code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // all values are fixed constants, so no JSON escaping is needed
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}"
                .formatted(title, status.value(), detail, code));
    }
}
