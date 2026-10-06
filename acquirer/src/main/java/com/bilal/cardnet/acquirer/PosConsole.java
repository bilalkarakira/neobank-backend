package com.bilal.cardnet.acquirer;

import com.bilal.cardnet.shared.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * The virtual POS: one web page and a small JSON API on the loopback interface (no authentication, localhost only, test
 * numbers only). Everything it shows of a card is masked, and the hex dumps are redacted (see {@link Redact}).
 *
 * <pre>
 * GET  /                      the page
 * GET  /api/config            shops, demo card, acquirer and terminal ids
 * POST /api/pay               {pan, expiry, amount, merchantId, pin?}
 * POST /api/resend            send the latest payment again (a duplicate)
 * POST /api/void              {id}
 * POST /api/adjust            {id, amount}  the final amount of an approved payment (a tip)
 * POST /api/refund            {id, amount}  refund all or part of a cleared payment
 * POST /api/close-day         put every approved payment and due refund into one clearing file
 * GET  /api/batches           the clearing files and their status
 * GET  /api/transactions      the latest payments
 * GET  /api/network           the network's status (proxied)
 * POST /api/network/chaos     the network's chaos switches (proxied)
 * </pre>
 */
public final class PosConsole {

    private static final int MAX_BODY = 4096;
    private static final System.Logger LOG = System.getLogger(PosConsole.class.getName());

    private final AcquirerConfig config;
    private final AcquirerService service;
    private final AcquirerClearing clearing;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpServer server;

    public PosConsole(AcquirerConfig config, AcquirerService service, AcquirerClearing clearing) {
        this.config = config;
        this.service = service;
        this.clearing = clearing;
    }

    public synchronized void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.httpPort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> route(exchange));
        server.start();
        LOG.log(System.Logger.Level.INFO, "POS console on http://127.0.0.1:{0}", String.valueOf(port()));
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    // ---- routing ----

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            switch (path) {
                case "/" -> page(exchange, method);
                case "/api/config" -> only(exchange, method, "GET", () -> json(exchange, 200, config()));
                case "/api/transactions" -> only(exchange, method, "GET", () -> json(exchange, 200, transactions()));
                case "/api/pay" -> only(exchange, method, "POST", () -> json(exchange, 200, pay(Json.parseFlat(body(exchange)))));
                case "/api/resend" -> only(exchange, method, "POST", () -> json(exchange, 200, resultMap(service.resendLast())));
                case "/api/void" -> only(exchange, method, "POST", () -> json(exchange, 200, voidSale(Json.parseFlat(body(exchange)))));
                case "/api/adjust" -> only(exchange, method, "POST", () -> json(exchange, 200, adjust(Json.parseFlat(body(exchange)))));
                case "/api/refund" -> only(exchange, method, "POST", () -> json(exchange, 200, refund(Json.parseFlat(body(exchange)))));
                case "/api/close-day" -> only(exchange, method, "POST", () -> json(exchange, 200, closeDay()));
                case "/api/batches" -> only(exchange, method, "GET", () -> json(exchange, 200, batches()));
                case "/api/network" -> only(exchange, method, "GET", () -> proxy(exchange, "GET", "/status", null));
                case "/api/network/chaos" -> only(exchange, method, "POST", () -> proxy(exchange, "POST", "/chaos", body(exchange)));
                default -> json(exchange, 404, error("not found"));
            }
        } catch (AcquirerService.BadRequestException | IllegalArgumentException e) {
            json(exchange, 400, error(e.getMessage()));
        } catch (AcquirerService.NotFoundException e) {
            json(exchange, 404, error(e.getMessage()));
        } catch (AcquirerService.ConflictException e) {
            json(exchange, 409, error(e.getMessage()));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The console failed on {0} ({1})", path, e.getClass().getSimpleName());
            json(exchange, 500, error("something went wrong inside the acquirer"));
        } finally {
            exchange.close();
        }
    }

    private interface Handler {
        void run() throws IOException;
    }

    private void only(HttpExchange exchange, String method, String allowed, Handler handler) throws IOException {
        if (!method.equals(allowed)) {
            exchange.getResponseHeaders().set("Allow", allowed);
            json(exchange, 405, error("use " + allowed));
            return;
        }
        handler.run();
    }

    // ---- the endpoints ----

    private void page(HttpExchange exchange, String method) throws IOException {
        if (!method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            json(exchange, 405, error("use GET"));
            return;
        }
        try (InputStream in = PosConsole.class.getResourceAsStream("/pos/index.html")) {
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private Map<String, Object> config() {
        Map<String, Object> map = Json.object();
        List<Object> shops = Json.list();
        for (AcquirerConfig.Merchant m : config.merchants) {
            Map<String, Object> shop = Json.object();
            shop.put("id", m.id());
            shop.put("name", m.name());
            shop.put("mcc", m.mcc());
            shops.add(shop);
        }
        map.put("merchants", shops);
        Map<String, Object> demo = Json.object();
        demo.put("pan", config.demoCardPan);
        demo.put("pin", config.demoCardPin);
        demo.put("expiry", config.demoCardExpiry);
        map.put("demoCard", demo);
        map.put("clearing", clearing.enabled());
        map.put("tipLimitPercent", config.tipLimit.movePointRight(2).stripTrailingZeros().toPlainString());
        map.put("acquirerId", config.acquirerId);
        map.put("terminalId", config.terminalId);
        return map;
    }

    private Map<String, Object> pay(Map<String, Object> in) {
        AcquirerService.Result result = service.pay(new AcquirerService.PayRequest(text(in, "pan"), text(in, "expiry"), text(in, "amount"),
                text(in, "merchantId"), text(in, "pin")));
        return resultMap(result);
    }

    private Map<String, Object> voidSale(Map<String, Object> in) {
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(in.get("id")));
        } catch (IllegalArgumentException e) {
            throw new AcquirerService.BadRequestException("That is not a payment id.");
        }
        return resultMap(service.voidSale(id));
    }

    private static UUID id(Map<String, Object> in) {
        try {
            return UUID.fromString(String.valueOf(in.get("id")));
        } catch (IllegalArgumentException e) {
            throw new AcquirerService.BadRequestException("That is not a payment id.");
        }
    }

    private Map<String, Object> adjust(Map<String, Object> in) {
        return rowMap(clearing.adjust(id(in), text(in, "amount")));
    }

    private Map<String, Object> refund(Map<String, Object> in) {
        return rowMap(clearing.refund(id(in), text(in, "amount")));
    }

    private Map<String, Object> closeDay() {
        Map<String, Object> map = Json.object();
        java.util.Optional<AcquirerClearing.BatchResult> batch = clearing.closeDay();
        if (batch.isEmpty()) {
            map.put("batch", null);
            map.put("message", "Nothing to send: no approved payment or refund is waiting.");
            return map;
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", batch.get().id().toString());
        b.put("fileId", batch.get().fileId());
        b.put("fileName", batch.get().fileName());
        b.put("count", batch.get().count());
        b.put("controlTotal", batch.get().controlTotal());
        map.put("batch", b);
        map.put("message", "Sent " + batch.get().count() + " item(s) to the network in file " + batch.get().fileName() + ".");
        return map;
    }

    private Map<String, Object> batches() {
        Map<String, Object> map = Json.object();
        List<Object> rows = Json.list();
        for (AcquirerClearing.BatchRow b : clearing.batches(10)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", b.id().toString());
            r.put("fileId", b.fileId());
            r.put("fileName", b.fileName());
            r.put("businessDate", b.businessDate().toString());
            r.put("count", b.count());
            r.put("controlTotal", java.math.BigDecimal.valueOf(b.controlTotal(), 2).toPlainString());
            r.put("status", b.status());
            r.put("createdAt", b.createdAt().toString());
            r.put("ackReason", b.ackReason());
            rows.add(r);
        }
        map.put("batches", rows);
        return map;
    }

    private static Map<String, Object> rowMap(AcquirerStore.Row row) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", row.id().toString());
        r.put("createdAt", row.createdAt().toString());
        r.put("merchant", row.merchantName());
        r.put("card", row.maskedPan());
        r.put("amount", row.amount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString());
        r.put("finalAmount", row.finalAmount() == null ? null : row.finalAmount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString());
        r.put("kind", row.kind());
        r.put("refundOf", row.refundOf() == null ? null : row.refundOf().toString());
        r.put("status", row.status());
        r.put("responseCode", row.responseCode());
        r.put("authCode", row.authCode());
        r.put("reason", row.reason());
        r.put("timedOut", row.timedOut());
        r.put("stan", row.stan());
        r.put("rrn", row.rrn());
        return r;
    }

    private Map<String, Object> transactions() {
        Map<String, Object> map = Json.object();
        List<Object> rows = Json.list();
        for (AcquirerStore.Row row : service.store().recent(20)) {
            rows.add(rowMap(row));
        }
        map.put("transactions", rows);
        return map;
    }

    private static Map<String, Object> resultMap(AcquirerService.Result result) {
        Map<String, Object> map = Json.object();
        map.put("id", result.id().toString());
        map.put("status", result.status());
        map.put("responseCode", result.responseCode());
        map.put("authCode", result.authCode());
        map.put("message", result.message());
        map.put("requestText", result.requestText());
        map.put("requestHex", result.requestHex());
        map.put("responseText", result.responseText());
        map.put("responseHex", result.responseHex());
        return map;
    }

    private void proxy(HttpExchange exchange, String method, String path, String body) throws IOException {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.networkControlUrl + path)).timeout(Duration.ofSeconds(3));
            HttpResponse<String> response = http.send(method.equals("GET") ? request.GET().build()
                    : request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(), HttpResponse.BodyHandlers.ofString());
            raw(exchange, response.statusCode(), response.body());
        } catch (IOException e) {
            json(exchange, 502, error("the network is not reachable"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            json(exchange, 502, error("interrupted"));
        }
    }

    // ---- small helpers ----

    private static String text(Map<String, Object> in, String key) {
        Object value = in.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = Json.object();
        map.put("error", message);
        return map;
    }

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IllegalArgumentException("the request is too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void json(HttpExchange exchange, int code, Map<String, Object> body) throws IOException {
        raw(exchange, code, Json.write(body));
    }

    private static void raw(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
