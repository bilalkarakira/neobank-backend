package com.bilal.cardnet.network;

import com.bilal.cardnet.shared.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A tiny HTTP API on the loopback interface (no authentication: localhost only, a simulation):
 * GET /status and POST /chaos with a flat JSON object. The demo console proxies to it.
 */
public final class NetworkControl {

    private static final int MAX_BODY = 4096;

    private final int wantedPort;
    private final Supplier<Map<String, Object>> status;
    private final Chaos chaos;
    private HttpServer server;

    public NetworkControl(int port, Supplier<Map<String, Object>> status, Chaos chaos) {
        this.wantedPort = port;
        this.status = status;
        this.chaos = chaos;
    }

    public synchronized void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), wantedPort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/status", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 405, Json.write(error("use GET")));
                return;
            }
            reply(exchange, 200, Json.write(status.get()));
        });
        server.createContext("/chaos", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, Json.write(error("use POST")));
                return;
            }
            try {
                chaos.apply(Json.parseFlat(readBody(exchange)));
                reply(exchange, 200, Json.write(chaos.toMap()));
            } catch (IllegalArgumentException e) {
                reply(exchange, 400, Json.write(error(e.getMessage())));
            }
        });
        server.start();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = Json.object();
        map.put("error", message);
        return map;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                throw new IllegalArgumentException("the request is too large");
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
