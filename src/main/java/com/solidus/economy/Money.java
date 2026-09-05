package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable money value at the storage boundary (DB scaling plan Phase 2).
 *
 * <p>Solidus keeps {@code double} in its public API and in the SQLite column
 * (source compatibility with companions, owner rule), but a shared network
 * database must store money EXACTLY: {@code DECIMAL(18,2)} columns backed by
 * {@link BigDecimal} at the JDBC boundary. Every double crossing into or out
 * of {@link MySqlStorage} funnels through this class so 2-decimal rounding
 * happens in exactly one place — the same rounding {@link CurrencyUtil#round}
 * applies on the API side.</p>
 *
 * <p>Note on range: {@code NUMERIC(18,2)} comfortably covers
 * {@link CurrencyUtil#MAX_BALANCE} (100,000,000.00) with headroom for
 * aggregate columns (SUM over balances).</p>
 */
public final class Money implements Comparable<Money> {

    /** Scale of all money values (2 decimal places, matching DECIMAL(18,2)). */
    public static final int SCALE = 2;

    /** Zero money. */
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));

    private final BigDecimal value;

    private Money(BigDecimal scaled) {
        this.value = scaled;
    }

    /**
     * Creates money from a double, rounded to 2 decimals (HALF_UP) — the same
     * normalization {@link CurrencyUtil#round(double)} applies on the API side.
     */
    public static Money of(double amount) {
        return ofDecimal(BigDecimal.valueOf(amount));
    }

    /** Creates money from a decimal, forcing exactly {@value #SCALE} decimals. */
    public static Money ofDecimal(BigDecimal decimal) {
        BigDecimal d = decimal == null ? BigDecimal.ZERO : decimal;
        return new Money(d.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** The exact 2-decimal value (for JDBC {@code setBigDecimal}). */
    public BigDecimal toDecimal() {
        return value;
    }

    /** The double view (for reads consumed by the double-based API). */
    public double toDouble() {
        return value.doubleValue();
    }

    /** Exact addition. */
    public Money add(Money other) {
        return new Money(value.add(other.value).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** Exact subtraction. */
    public Money subtract(Money other) {
        return new Money(value.subtract(other.value).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** True when strictly below zero. */
    public boolean isNegative() {
        return value.signum() < 0;
    }

    /** True when above {@link CurrencyUtil#MAX_BALANCE}. */
    public boolean isOverCap() {
        return value.compareTo(BigDecimal.valueOf(CurrencyUtil.MAX_BALANCE)) > 0;
    }

    @Override
    public int compareTo(Money other) {
        return value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other && value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
