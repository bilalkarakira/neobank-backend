package com.bilal.cardnet.network;

import com.bilal.cardnet.iso8583.Message;
import com.bilal.cardnet.shared.Messages;

import java.math.BigDecimal;

/**
 * The network's rule for answering on the issuer's behalf when the issuer cannot be reached. Deliberately narrow: the
 * network knows nothing about the card's real status or balance, so it only approves small, ordinary purchases, and only
 * up to a daily total per card. It also cannot check a PIN, so a payment with a PIN is never stood in for.
 */
public final class StandIn {

    /** @param reason why it said no (for the logs and the tests); for an approval, "ok" */
    public record Decision(boolean approve, String reason) {
    }

    private StandIn() {
    }

    /** @param usedToday what this card has already been approved for by stand-in today (UTC) */
    public static Decision decide(Message request, BigDecimal usedToday, NetworkConfig config) {
        if (request.has(90)) {
            return no("an increment adds to an authorization only the bank knows");
        }
        if (request.has(52)) {
            return no("a PIN was sent and the network cannot verify it");
        }
        String processing = request.get(3);
        if (processing == null || !processing.startsWith("00")) {
            return no("not a purchase");
        }
        if (!"840".equals(request.get(49))) {
            return no("not US dollars");
        }
        if (config.blockedMcc.contains(request.get(18))) {
            return no("merchant category not allowed");
        }
        BigDecimal amount = Messages.amount(request);
        if (amount.signum() <= 0) {
            return no("no amount");
        }
        if (amount.compareTo(config.standInMax) > 0) {
            return no("over the per-payment stand-in limit");
        }
        if (usedToday.add(amount).compareTo(config.standInDailyCap) > 0) {
            return no("over the card's daily stand-in total");
        }
        return new Decision(true, "ok");
    }

    private static Decision no(String reason) {
        return new Decision(false, reason);
    }
}
