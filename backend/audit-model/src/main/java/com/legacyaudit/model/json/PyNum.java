package com.legacyaudit.model.json;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Python's float arithmetic where Java's differs: {@code round(x, n)} and {@code sum()} of floats. */
public final class PyNum {

    private PyNum() {}

    /** {@code round(x, digits)}: half to even on the exact binary value. */
    public static double round(double x, int digits) {
        if (!Double.isFinite(x)) {
            return x;
        }
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code sum(floats)} as Python 3.12+ adds floats (Neumaier's compensated summation). */
    public static double sum(Iterable<Double> values) {
        double total = 0.0;
        double c = 0.0;
        for (double x : values) {
            double t = total + x;
            if (Math.abs(total) >= Math.abs(x)) {
                c += (total - t) + x;
            } else {
                c += (x - t) + total;
            }
            total = t;
        }
        if (c != 0.0 && Double.isFinite(c)) {
            total += c;
        }
        return total;
    }
}
