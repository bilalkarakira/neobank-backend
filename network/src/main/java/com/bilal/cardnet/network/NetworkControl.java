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
    private final SettlementService settlement;
    private HttpServer server;

    /** @param settlement the settlement service, or null when settlement is not available (clearing off) */
    public NetworkControl(int port, Supplier<Map<String, Object>> status, Chaos chaos, SettlementService settlement) {
        this.wantedPort = port;
        this.status = status;
        this.chaos = chaos;
        this.settlement = settlement;
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
        server.createContext("/settlement/run", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, Json.write(error("use POST")));
                return;
            }
            if (settlement == null) {
                reply(exchange, 409, Json.write(error("settlement is switched off: no clearing folder is configured")));
                return;
            }
            try {
                java.time.LocalDate date = null;
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.startsWith("date=")) {
                    date = java.time.LocalDate.parse(query.substring("date=".length()));
                }
                java.util.Optional<SettlementService.Run> run = settlement.run(date);
                Map<String, Object> body = Json.object();
                body.put("settled", run.isPresent());
                body.put("message", run.isPresent() ? "Settled " + run.get().itemCount() + " item(s) of " + run.get().businessDate()
                        + " as run " + run.get().runNumber() + ", value date " + run.get().valueDate() + "." : "Nothing to settle.");
                run.ifPresent(r -> body.put("run", runMap(r)));
                reply(exchange, 200, Json.write(body));
            } catch (java.time.format.DateTimeParseException e) {
                reply(exchange, 400, Json.write(error("the date must look like 2026-10-06")));
            }
        });
        server.createContext("/settlements", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 405, Json.write(error("use GET")));
                return;
            }
            Map<String, Object> body = Json.object();
            java.util.List<Object> runs = Json.list();
            if (settlement != null) {
                settlement.recentRuns(20).forEach(r -> runs.add(runMap(r)));
                body.put("unsettledItems", settlement.unsettledItems());
            }
            body.put("runs", runs);
            reply(exchange, 200, Json.write(body));
        });
        server.start();
    }

    private static Map<String, Object> runMap(SettlementService.Run run) {
        Map<String, Object> map = Json.object();
        map.put("runNumber", run.runNumber());
        map.put("businessDate", run.businessDate().toString());
        map.put("valueDate", run.valueDate().toString());
        map.put("itemCount", run.itemCount());
        java.util.List<Object> lines = Json.list();
        for (SettlementFiles.Line line : run.lines()) {
            Map<String, Object> l = Json.object();
            l.put("participant", line.participant());
            l.put("role", line.role());
            l.put("purchases", line.purchases());
            l.put("refunds", line.refunds());
            l.put("interchange", line.interchange());
            l.put("schemeFee", line.schemeFee());
            l.put("net", line.net());
            l.put("items", line.items());
            lines.add(l);
        }
        map.put("lines", lines);
        return map;
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
