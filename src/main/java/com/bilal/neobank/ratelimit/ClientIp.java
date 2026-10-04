package com.bilal.neobank.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address a request came from, as the servlet container reports it. Forwarded headers are deliberately NOT parsed
 * here: a header any client can send would let an attacker pick their own "address" and dodge every limit. Behind a
 * reverse proxy, enable Spring's own handling (server.forward-headers-strategy) so the container reports the real
 * client address, and make sure the proxy overwrites the header instead of appending to what the client sent.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
