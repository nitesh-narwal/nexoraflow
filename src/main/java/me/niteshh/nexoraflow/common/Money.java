package me.niteshh.nexoraflow.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Financial arithmetic helpers. All monetary values in NexoraFlow are {@link BigDecimal} with scale 2
 * and HALF_UP rounding - floating point is never used for money.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String value) {
        return scale(new BigDecimal(value));
    }

    public static BigDecimal of(long value) {
        return scale(BigDecimal.valueOf(value));
    }

    public static BigDecimal sum(Iterable<BigDecimal> values) {
        BigDecimal total = ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return scale(total);
    }

    /** Compares by numeric value, ignoring scale (2.0 equals 2.00). */
    public static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    public static boolean gte(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0;
    }

    public static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    public static boolean lte(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0;
    }
}
