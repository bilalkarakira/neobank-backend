package com.bilal.cardnet.shared;

import com.bilal.cardnet.iso8583.Iso8583Exception;
import com.bilal.cardnet.iso8583.Message;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The one place that knows how messages between acquirer, network and issuer are built and read, so the conventions
 * the issuer relies on (see SPEC-P2-issuer.md) cannot drift between the processes.
 */
public final class Messages {

    /** Elements copied from a request into its response. */
    public static final List<Integer> ECHOED = List.of(2, 3, 4, 7, 11, 12, 13, 37, 41, 42, 49);

    private static final DateTimeFormatter DE7 = DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);

    private Messages() {
    }

    /** DE 7 (transmission date and time, UTC) as MMDDhhmmss. */
    public static String de7(Instant at) {
        return DE7.format(at);
    }

    /** 0100 -> 0110, 0200 -> 0210, 0400 -> 0410, 0800 -> 0810. */
    public static String responseMti(String requestMti) {
        return "%04d".formatted(Integer.parseInt(requestMti) + 10);
    }

    /** A response to {@code request} with the usual elements echoed and DE 39 set. */
    public static Message response(Message request, String code) {
        Message response = Message.of(responseMti(request.mti()));
        for (int field : ECHOED) {
            if (request.has(field)) {
                response.set(field, request.get(field));
            }
        }
        return response.set(39, code);
    }

    /** What a response to this request will be recognised by: its MTI, STAN and retrieval reference number. */
    public static String responseKey(Message request) {
        return responseMti(request.mti()) + "|" + nullToEmpty(request.get(11)) + "|" + nullToEmpty(request.get(37));
    }

    /** The same key read from a response itself. */
    public static String keyOfResponse(Message response) {
        return response.mti() + "|" + nullToEmpty(response.get(11)) + "|" + nullToEmpty(response.get(37));
    }

    public static boolean hasAll(Message message, int... fields) {
        for (int field : fields) {
            if (!message.has(field)) {
                return false;
            }
        }
        return true;
    }

    /** DE 4 is minor units in 12 digits: 000000002550 is 25.50. */
    public static BigDecimal amount(Message message) {
        return new BigDecimal(new BigInteger(message.get(4)), 2);
    }

    public static String de4(BigDecimal amount) {
        return "%012d".formatted(amount.movePointRight(2).longValueExact());
    }

    /** DE 32 is 1-11 digits; compared in its 11-digit form, as it appears inside DE 90. */
    public static String padAcquirer(String de32) {
        return "0".repeat(Math.max(0, 11 - de32.length())) + de32;
    }

    /** The request a reversal points at, read from DE 90. */
    public record Original(String mti, String stan, String de7, String acquirer) {
    }

    /** @throws Iso8583Exception when DE 90 is missing or does not name an authorization or financial request */
    public static Original original(Message reversal) {
        String de90 = reversal.get(90);
        if (de90 == null || de90.length() != 42) {
            throw new Iso8583Exception(90, "a reversal must carry the original data elements");
        }
        String mti = de90.substring(0, 4);
        if (!mti.equals("0100") && !mti.equals("0200")) {
            throw new Iso8583Exception(90, "the original must be a 0100 or 0200");
        }
        return new Original(mti, de90.substring(4, 10), de90.substring(10, 20), de90.substring(20, 31));
    }

    public static Message signOn(String stan, String de7) {
        return network("001", stan, de7);
    }

    public static Message echo(String stan, String de7) {
        return network("301", stan, de7);
    }

    public static Message signOff(String stan, String de7) {
        return network("002", stan, de7);
    }

    private static Message network(String code, String stan, String de7) {
        return Message.of("0800").set(7, de7).set(11, stan).set(70, code);
    }

    /**
     * The reversal of an earlier request: repeats the card, amount and terminal data and names the original in DE 90
     * (original MTI, STAN, DE 7, acquirer padded to 11, forwarding institution zeros).
     */
    public static Message reversalOf(Message original, String stan, String de7) {
        Message reversal = Message.of("0400");
        for (int field : new int[] { 2, 3, 4, 13, 37, 41, 42, 49, 18, 43 }) {
            if (original.has(field)) {
                reversal.set(field, original.get(field));
            }
        }
        String acquirer = original.has(32) ? padAcquirer(original.get(32)) : "0".repeat(11);
        reversal.set(7, de7).set(11, stan);
        return reversal.set(90, original.mti() + original.get(11) + original.get(7) + acquirer + "0".repeat(11));
    }

    /**
     * The 0120 authorization advice for a payment the network approved by itself: the request again, without any PIN
     * data or security code, with the authorization code it gave and DE 39 = 00 ("approved").
     */
    public static Message advice(Message request, String authCode) {
        Message advice = request.copyWithMti("0120");
        advice.remove(52).remove(53);
        return advice.set(38, authCode).set(39, "00");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
