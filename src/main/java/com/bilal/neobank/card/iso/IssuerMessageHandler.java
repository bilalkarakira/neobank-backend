package com.bilal.neobank.card.iso;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.neobank.card.auth.Authorization;
import com.bilal.neobank.card.auth.AuthorizationEngine.AdviceInput;
import com.bilal.neobank.card.auth.AuthorizationEngine.AuthInput;
import com.bilal.neobank.card.auth.AuthorizationEngine.Decision;
import com.bilal.neobank.card.auth.AuthorizationEngine.ParentRef;
import com.bilal.neobank.card.auth.AuthorizationEngine.ReversalInput;
import com.bilal.neobank.card.auth.IssuerService;
import com.bilal.neobank.card.auth.ResponseCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Turns a decoded ISO 8583 request into a decision and the decision back into a response message. Knows the message
 * layout; the rules themselves live in the engine. A message type the issuer does not handle gets no answer (empty):
 * the caller closes the connection.
 */
@Component
public class IssuerMessageHandler {

    /** State of one TCP connection: a sign-on (0800/001) is needed before the issuer takes payments on it. */
    public static final class Connection {
        private volatile boolean signedOn;

        public boolean signedOn() {
            return signedOn;
        }
    }

    private static final List<Integer> AUTH_REQUIRED = List.of(2, 3, 4, 7, 11, 13, 14, 18, 32, 37, 41, 42, 49);
    private static final List<Integer> REVERSAL_REQUIRED = List.of(2, 4, 13, 37, 41, 42, 49, 90);
    /** Elements copied from a request into its response. */
    private static final List<Integer> ECHOED = List.of(2, 3, 4, 7, 11, 12, 13, 37, 41, 42, 49);

    private final IssuerService issuer;

    public IssuerMessageHandler(IssuerService issuer) {
        this.issuer = issuer;
    }

    public Optional<Message> handle(Message request, Connection connection) {
        return switch (request.mti()) {
            case "0100", "0200" -> Optional.of(authorization(request, connection));
            case "0120" -> Optional.of(advice(request, connection));
            case "0400" -> Optional.of(reversal(request, connection));
            case "0800" -> Optional.of(networkManagement(request, connection));
            default -> Optional.empty();
        };
    }

    private Message authorization(Message request, Connection connection) {
        if (!connection.signedOn()) {
            return respond(request, new Decision(ResponseCode.ISSUER_UNAVAILABLE, null));
        }
        if (!hasAll(request, AUTH_REQUIRED)) {
            return respond(request, new Decision(ResponseCode.FORMAT_ERROR, null));
        }
        Authorization.Request fields = new Authorization.Request(request.mti(), request.get(11),
                request.get(37), request.get(41).strip(), request.get(42).strip(), optional(request, 43), request.get(18),
                padAcquirer(request.get(32)), request.get(3), optional(request, 22), request.get(13), request.get(7),
                amount(request.get(4)), request.get(49));
        // DE 90 turns the request into an increment: the first authorization it adds to (original MTI, STAN, time, acquirer)
        ParentRef parent = null;
        if (request.has(90)) {
            String ref = request.get(90);
            if (ref.length() < 31 || !ref.startsWith("0100") || !padAcquirer(request.get(32)).equals(ref.substring(20, 31))) {
                return respond(request, new Decision(ResponseCode.FORMAT_ERROR, null));
            }
            parent = new ParentRef(ref.substring(4, 10), ref.substring(10, 20));
        }
        AuthInput input = new AuthInput(request.get(2), request.get(14), request.get(52), fields, parent, request.get(53));
        return respond(request, issuer.authorize(input));
    }

    /** 0120: the card network tells us it approved a payment by itself (stand-in). Needs the elements of a payment plus DE 38. */
    private Message advice(Message request, Connection connection) {
        if (!connection.signedOn()) {
            return respond(request, new Decision(ResponseCode.ISSUER_UNAVAILABLE, null));
        }
        if (!hasAll(request, AUTH_REQUIRED) || !request.has(38)) {
            return respond(request, new Decision(ResponseCode.FORMAT_ERROR, null));
        }
        Authorization.Request fields = new Authorization.Request(request.mti(), request.get(11),
                request.get(37), request.get(41).strip(), request.get(42).strip(), optional(request, 43), request.get(18),
                padAcquirer(request.get(32)), request.get(3), optional(request, 22), request.get(13), request.get(7),
                amount(request.get(4)), request.get(49));
        return respond(request, issuer.advise(new AdviceInput(request.get(2), request.get(38), fields)));
    }

    private Message reversal(Message request, Connection connection) {
        if (!connection.signedOn()) {
            return respond(request, new Decision(ResponseCode.ISSUER_UNAVAILABLE, null));
        }
        if (!hasAll(request, REVERSAL_REQUIRED)) {
            return respond(request, new Decision(ResponseCode.FORMAT_ERROR, null));
        }
        // DE 90: original MTI (4), STAN (6), transmission date and time (10), acquirer (11), forwarding institution (11)
        String original = request.get(90);
        String originalMti = original.substring(0, 4);
        if (!originalMti.equals("0100") && !originalMti.equals("0200")) {
            return respond(request, new Decision(ResponseCode.FORMAT_ERROR, null));
        }
        String originalStan = original.substring(4, 10);
        String originalTransmittedAt = original.substring(10, 20);
        Authorization.Request fields = new Authorization.Request("0400", originalStan, request.get(37),
                request.get(41).strip(), request.get(42).strip(), optional(request, 43), optional(request, 18),
                original.substring(20, 31), optional(request, 3), null, request.get(13), originalTransmittedAt,
                amount(request.get(4)), request.get(49));
        return respond(request, issuer.reverse(
                new ReversalInput(request.get(2), amount(request.get(4)), originalStan, originalTransmittedAt, fields)));
    }

    /** 0800 with DE 70: 001 sign-on, 002 sign-off, 301 echo test. */
    private Message networkManagement(Message request, Connection connection) {
        Message response = Message.of("0810");
        for (int field : List.of(7, 11, 70)) {
            if (request.has(field)) {
                response.set(field, request.get(field));
            }
        }
        String code = request.get(70);
        if (code == null) {
            return response.set(39, ResponseCode.FORMAT_ERROR);
        }
        switch (code) {
            case "001" -> connection.signedOn = true;
            case "002" -> connection.signedOn = false;
            case "301" -> {
                // echo: nothing to do
            }
            default -> {
                return response.set(39, ResponseCode.INVALID_TRANSACTION);
            }
        }
        return response.set(39, ResponseCode.APPROVED);
    }

    private static Message respond(Message request, Decision decision) {
        Message response = Message.of("%04d".formatted(Integer.parseInt(request.mti()) + 10));
        for (int field : ECHOED) {
            if (request.has(field)) {
                response.set(field, request.get(field));
            }
        }
        if (decision.authCode() != null) {
            response.set(38, decision.authCode());
        }
        return response.set(39, decision.code());
    }

    private static boolean hasAll(Message message, List<Integer> fields) {
        return fields.stream().allMatch(message::has);
    }

    private static String optional(Message message, int field) {
        String value = message.get(field);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** DE 4 is minor units in 12 digits: 000000002550 is 25.50. */
    private static BigDecimal amount(String de4) {
        return new BigDecimal(new BigInteger(de4), 2);
    }

    /** DE 32 is 1-11 digits; stored left-padded to 11 so it compares with the acquirer inside DE 90. */
    private static String padAcquirer(String de32) {
        return "0".repeat(11 - de32.length()) + de32;
    }
}
