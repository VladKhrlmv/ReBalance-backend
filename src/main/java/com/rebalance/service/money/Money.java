package com.rebalance.service.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversions between API/DB amounts (BigDecimal, 2 decimals) and integer cents used for all arithmetic.
 */
public final class Money {
    private Money() {
    }

    /**
     * @throws ArithmeticException if the amount has more than 2 decimals or does not fit in a long
     */
    public static long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    }

    public static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    /** Formats cents as "1234.50" for messages. */
    public static String format(long cents) {
        return fromCents(cents).toPlainString();
    }
}
