package com.solidus.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CurrencyUtil} - pure utility class with zero dependencies.
 * These tests validate currency formatting, validation, and rounding logic.
 */
@DisplayName("CurrencyUtil")
class CurrencyUtilTest {

    // -- Constants ------------------------------------------

    @Test
    @DisplayName("CURRENCY_SYMBOL is S$")
    void currencySymbolIsCorrect() {
        assertEquals("S$", CurrencyUtil.CURRENCY_SYMBOL);
    }

    @Test
    @DisplayName("DEFAULT_STARTING_BALANCE is 500.0")
    void defaultStartingBalanceIsCorrect() {
        assertEquals(500.0, CurrencyUtil.DEFAULT_STARTING_BALANCE);
    }

    @Test
    @DisplayName("MIN_TRANSACTION is 0.01")
    void minTransactionIsCorrect() {
        assertEquals(0.01, CurrencyUtil.MIN_TRANSACTION);
    }

    @Test
    @DisplayName("MAX_TRANSACTION is 10,000,000.0")
    void maxTransactionIsCorrect() {
        assertEquals(10_000_000.0, CurrencyUtil.MAX_TRANSACTION);
    }

    @Test
    @DisplayName("MAX_BALANCE is 100,000,000.0")
    void maxBalanceIsCorrect() {
        assertEquals(100_000_000.0, CurrencyUtil.MAX_BALANCE);
    }

    // -- isValidAmount --------------------------------------

    @Nested
    @DisplayName("isValidAmount()")
    class IsValidAmountTest {

        @Test
        @DisplayName("accepts minimum valid amount (0.01)")
        void acceptsMinAmount() {
            assertTrue(CurrencyUtil.isValidAmount(CurrencyUtil.MIN_TRANSACTION));
        }

        @Test
        @DisplayName("accepts maximum valid amount (10,000,000)")
        void acceptsMaxAmount() {
            assertTrue(CurrencyUtil.isValidAmount(CurrencyUtil.MAX_TRANSACTION));
        }

        @Test
        @DisplayName("accepts typical transaction amount (100)")
        void acceptsTypicalAmount() {
            assertTrue(CurrencyUtil.isValidAmount(100.0));
        }

        @Test
        @DisplayName("accepts large transaction (5,000,000)")
        void acceptsLargeAmount() {
            assertTrue(CurrencyUtil.isValidAmount(5_000_000.0));
        }

        @Test
        @DisplayName("rejects zero")
        void rejectsZero() {
            assertFalse(CurrencyUtil.isValidAmount(0.0));
        }

        @Test
        @DisplayName("rejects negative values")
        void rejectsNegative() {
            assertFalse(CurrencyUtil.isValidAmount(-1.0));
            assertFalse(CurrencyUtil.isValidAmount(-0.01));
            assertFalse(CurrencyUtil.isValidAmount(-1000.0));
        }

        @Test
        @DisplayName("rejects amounts above MAX_TRANSACTION")
        void rejectsAboveMax() {
            assertFalse(CurrencyUtil.isValidAmount(10_000_001.0));
            assertFalse(CurrencyUtil.isValidAmount(100_000_000.0));
        }

        @Test
        @DisplayName("rejects NaN")
        void rejectsNaN() {
            assertFalse(CurrencyUtil.isValidAmount(Double.NaN));
        }

        @Test
        @DisplayName("rejects positive infinity")
        void rejectsPositiveInfinity() {
            assertFalse(CurrencyUtil.isValidAmount(Double.POSITIVE_INFINITY));
        }

        @Test
        @DisplayName("rejects negative infinity")
        void rejectsNegativeInfinity() {
            assertFalse(CurrencyUtil.isValidAmount(Double.NEGATIVE_INFINITY));
        }

        @Test
        @DisplayName("accepts fractional amounts within range")
        void acceptsFractionalAmounts() {
            assertTrue(CurrencyUtil.isValidAmount(0.01));
            assertTrue(CurrencyUtil.isValidAmount(1.50));
            assertTrue(CurrencyUtil.isValidAmount(999.99));
        }

        @Test
        @DisplayName("rejects amount just below MIN_TRANSACTION")
        void rejectsBelowMin() {
            assertFalse(CurrencyUtil.isValidAmount(0.001));
            assertFalse(CurrencyUtil.isValidAmount(0.009));
        }
    }

    // -- isValidBalance -------------------------------------

    @Nested
    @DisplayName("isValidBalance()")
    class IsValidBalanceTest {

        @Test
        @DisplayName("accepts zero balance")
        void acceptsZero() {
            assertTrue(CurrencyUtil.isValidBalance(0.0));
        }

        @Test
        @DisplayName("accepts maximum balance (100,000,000)")
        void acceptsMaxBalance() {
            assertTrue(CurrencyUtil.isValidBalance(CurrencyUtil.MAX_BALANCE));
        }

        @Test
        @DisplayName("accepts typical balance (500)")
        void acceptsTypicalBalance() {
            assertTrue(CurrencyUtil.isValidBalance(500.0));
        }

        @Test
        @DisplayName("rejects negative balance")
        void rejectsNegative() {
            assertFalse(CurrencyUtil.isValidBalance(-1.0));
            assertFalse(CurrencyUtil.isValidBalance(-0.01));
        }

        @Test
        @DisplayName("rejects balance above MAX_BALANCE")
        void rejectsAboveMax() {
            assertFalse(CurrencyUtil.isValidBalance(100_000_001.0));
            assertFalse(CurrencyUtil.isValidBalance(Double.MAX_VALUE));
        }

        @Test
        @DisplayName("rejects NaN")
        void rejectsNaN() {
            assertFalse(CurrencyUtil.isValidBalance(Double.NaN));
        }

        @Test
        @DisplayName("rejects infinities")
        void rejectsInfinities() {
            assertFalse(CurrencyUtil.isValidBalance(Double.POSITIVE_INFINITY));
            assertFalse(CurrencyUtil.isValidBalance(Double.NEGATIVE_INFINITY));
        }

        @Test
        @DisplayName("difference from isValidAmount: zero is valid for balance but not for transaction")
        void zeroIsValidBalanceButNotAmount() {
            assertTrue(CurrencyUtil.isValidBalance(0.0));
            assertFalse(CurrencyUtil.isValidAmount(0.0));
        }
    }

    // -- round ----------------------------------------------

    @Nested
    @DisplayName("round()")
    class RoundTest {

        @Test
        @DisplayName("rounds to 2 decimal places")
        void roundsToTwoDecimals() {
            assertEquals(1.23, CurrencyUtil.round(1.234));
            assertEquals(1.24, CurrencyUtil.round(1.235));
        }

        @Test
        @DisplayName("fixes floating point error (0.1 + 0.2)")
        void fixesFloatingPointError() {
            // 0.1 + 0.2 = 0.30000000000000004 in IEEE 754
            assertEquals(0.3, CurrencyUtil.round(0.1 + 0.2));
        }

        @Test
        @DisplayName("rounds whole numbers correctly")
        void roundsWholeNumbers() {
            assertEquals(100.0, CurrencyUtil.round(100.0));
            assertEquals(0.0, CurrencyUtil.round(0.0));
        }

        @Test
        @DisplayName("rounds down correctly")
        void roundsDown() {
            assertEquals(1.99, CurrencyUtil.round(1.994));
        }

        @Test
        @DisplayName("rounds up correctly")
        void roundsUp() {
            assertEquals(2.0, CurrencyUtil.round(1.995));
        }

        @Test
        @DisplayName("handles negative rounding")
        void handlesNegativeRounding() {
            assertEquals(-1.23, CurrencyUtil.round(-1.234));
        }

        @Test
        @DisplayName("rounds large values without precision loss")
        void roundsLargeValues() {
            assertEquals(1_000_000.50, CurrencyUtil.round(1_000_000.499));
            assertEquals(1_000_001.0, CurrencyUtil.round(1_000_000.999));
        }

        @Test
        @DisplayName("repeated rounding is idempotent")
        void roundingIsIdempotent() {
            double once = CurrencyUtil.round(1.235);
            double twice = CurrencyUtil.round(once);
            assertEquals(once, twice);
        }
    }

    // -- format ---------------------------------------------

    @Nested
    @DisplayName("format()")
    class FormatTest {

        @Test
        @DisplayName("formats whole numbers without decimals")
        void formatsWholeNumbers() {
            assertEquals("1,000 S$", CurrencyUtil.format(1000.0));
            assertEquals("500 S$", CurrencyUtil.format(500.0));
            assertEquals("0 S$", CurrencyUtil.format(0.0));
        }

        @Test
        @DisplayName("formats fractional amounts with 2 decimal places")
        void formatsFractionalAmounts() {
            assertEquals("1.50 S$", CurrencyUtil.format(1.5));
            assertEquals("999.99 S$", CurrencyUtil.format(999.99));
        }

        @Test
        @DisplayName("formats large numbers with comma separators")
        void formatsWithCommas() {
            assertEquals("10,000,000 S$", CurrencyUtil.format(10_000_000.0));
            assertEquals("1,000,000 S$", CurrencyUtil.format(1_000_000.0));
        }

        @Test
        @DisplayName("includes currency symbol S$")
        void includesCurrencySymbol() {
            String result = CurrencyUtil.format(100.0);
            assertTrue(result.endsWith(" S$"));
        }

        @Test
        @DisplayName("formats starting balance correctly")
        void formatsStartingBalance() {
            String result = CurrencyUtil.format(CurrencyUtil.DEFAULT_STARTING_BALANCE);
            assertEquals("500 S$", result);
        }
    }

    // -- formatCompact --------------------------------------

    @Nested
    @DisplayName("formatCompact()")
    class FormatCompactTest {

        @Test
        @DisplayName("formats millions with M suffix")
        void formatsMillions() {
            assertEquals("1.0M S$", CurrencyUtil.formatCompact(1_000_000.0));
            assertEquals("5.5M S$", CurrencyUtil.formatCompact(5_500_000.0));
        }

        @Test
        @DisplayName("formats thousands with K suffix")
        void formatsThousands() {
            assertEquals("1.0K S$", CurrencyUtil.formatCompact(1_000.0));
            assertEquals("50.0K S$", CurrencyUtil.formatCompact(50_000.0));
        }

        @Test
        @DisplayName("formats sub-thousand without suffix")
        void formatsSubThousand() {
            assertEquals("100 S$", CurrencyUtil.formatCompact(100.0));
            assertEquals("500 S$", CurrencyUtil.formatCompact(500.0));
        }

        @Test
        @DisplayName("formats fractional sub-thousand with one decimal")
        void formatsFractionalSubThousand() {
            assertEquals("99.9 S$", CurrencyUtil.formatCompact(99.9));
        }

        @Test
        @DisplayName("includes currency symbol S$")
        void includesCurrencySymbol() {
            String result = CurrencyUtil.formatCompact(1_000_000.0);
            assertTrue(result.endsWith(" S$"));
        }
    }

    // -- Edge Cases -----------------------------------------

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTest {

        @Test
        @DisplayName("isValidAmount boundary: exactly MIN_TRANSACTION")
        void boundaryMinTransaction() {
            assertTrue(CurrencyUtil.isValidAmount(0.01));
            assertFalse(CurrencyUtil.isValidAmount(0.009));
        }

        @Test
        @DisplayName("isValidAmount boundary: exactly MAX_TRANSACTION")
        void boundaryMaxTransaction() {
            assertTrue(CurrencyUtil.isValidAmount(10_000_000.0));
            assertFalse(CurrencyUtil.isValidAmount(10_000_000.01));
        }

        @Test
        @DisplayName("isValidBalance boundary: exactly MAX_BALANCE")
        void boundaryMaxBalance() {
            assertTrue(CurrencyUtil.isValidBalance(100_000_000.0));
            assertFalse(CurrencyUtil.isValidBalance(100_000_000.01));
        }

        @Test
        @DisplayName("round handles very small fractions")
        void roundVerySmallFractions() {
            assertEquals(0.01, CurrencyUtil.round(0.009));
            assertEquals(0.0, CurrencyUtil.round(0.004));
        }

        @Test
        @DisplayName("format then round is consistent")
        void formatThenRoundConsistency() {
            double original = 1234.567;
            double rounded = CurrencyUtil.round(original);
            String formatted = CurrencyUtil.format(rounded);
            // format(1234.57) should produce "1,234.57 S$"
            assertTrue(formatted.contains("1,234.57"));
        }
    }
}
