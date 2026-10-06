package com.bilal.cardnet.network;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one cleared item costs, in the three layers of a card scheme (posted in P5; here only worked out and recorded):
 * the merchant discount (what the shop pays its bank), the interchange (what the acquirer pays the issuer) and the scheme
 * fee (what the acquirer and the issuer each pay the network). Each is rounded to the cent, half up, on its own. A refund
 * carries the NEGATIVE of the fees of a purchase of the same amount: the part refunded reverses its fees.
 */
public record Fees(BigDecimal merchantDiscount, BigDecimal interchange, BigDecimal schemeFeeAcquirer, BigDecimal schemeFeeIssuer) {

    public static Fees of(BigDecimal amount, boolean refund, NetworkConfig config) {
        BigDecimal sign = BigDecimal.valueOf(refund ? -1 : 1);
        return new Fees(round(amount, config.merchantDiscountRate, sign), round(amount, config.interchangeRate, sign),
                round(amount, config.schemeFeeRate, sign), round(amount, config.schemeFeeRate, sign));
    }

    private static BigDecimal round(BigDecimal amount, BigDecimal rate, BigDecimal sign) {
        BigDecimal fee = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP).multiply(sign);
        return fee.signum() == 0 ? BigDecimal.ZERO.setScale(2) : fee;
    }
}
