package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link Money} boundary wrapper (DB scaling plan §5.4):
 * exact 2-decimal math at the JDBC boundary, cap enforcement, and rounding
 * parity with {@link CurrencyUtil#round(double)}.
 */
public class MoneyTest {

    @Test
    @DisplayName("of(double) rounds to exactly 2 decimals, HALF_UP")
    void roundsToTwoDecimals() {
        assertEquals(new BigDecimal("12.35"), Money.of(12.345).toDecimal());
        assertEquals(new BigDecimal("12.34"), Money.of(12.344).toDecimal());
        assertEquals(new BigDecimal("0.10"), Money.of(0.1).toDecimal());
        assertEquals(2, Money.of(5).toDecimal().scale());
    }

    @Test
    @DisplayName("add/subtract are exact (no float drift)")
    void exactArithmetic() {
        // 0.1 + 0.2 in raw doubles = 0.30000000000000004; Money must be exact
        Money sum = Money.of(0.1).add(Money.of(0.2));
        assertEquals(new BigDecimal("0.30"), sum.toDecimal());
        assertEquals(0.30, sum.toDouble(), 0.0);

        Money back = sum.subtract(Money.of(0.1));
        assertEquals(new BigDecimal("0.20"), back.toDecimal());
    }

    @Test
    @DisplayName("large balances stay exact at 2 decimals")
    void largeValuesExact() {
        Money m = Money.of(1_000_000.555);
        assertEquals(new BigDecimal("1000000.56"), m.toDecimal());

        Money cap = Money.of(CurrencyUtil.MAX_BALANCE);
        assertEquals(new BigDecimal("100000000.00"), cap.toDecimal());
        assertFalse(cap.isOverCap());
        assertTrue(cap.add(Money.of(0.01)).isOverCap());
    }

    @Test
    @DisplayName("isNegative flags overdrafts; ZERO is not negative")
    void signChecks() {
        assertFalse(Money.ZERO.isNegative());
        assertTrue(Money.of(5).subtract(Money.of(10)).isNegative());
    }

    @Test
    @DisplayName("compareTo/equals use numeric value, not scale")
    void comparisons() {
        assertEquals(Money.of(1.0), Money.ofDecimal(new BigDecimal("1.00")));
        assertEquals(0, Money.of(2.5).compareTo(Money.of(2.50)));
        assertTrue(Money.of(1.0).compareTo(Money.of(2.0)) < 0);
    }
}
