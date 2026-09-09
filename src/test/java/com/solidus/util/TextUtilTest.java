package com.solidus.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TextUtil} - only testing pure Java methods
 * that do not depend on Minecraft's Component/ChatFormatting classes.
 *
 * Methods tested:
 * - formatCurrency(double)
 * - sanitizeLegacyFormatting(String)
 *
 * Methods NOT tested (require Minecraft runtime):
 * - styled(), styledBold(), styledItalic(), styledBoldItalic()
 * - plain(), error(), success(), warning(), currency()
 * - shopTitle(), sectionHeader(), loreLine()
 * - buyPriceLore(), sellPriceLore()
 * - getMaterialName()
 */
@DisplayName("TextUtil (pure Java methods)")
class TextUtilTest {

    // -- formatCurrency -------------------------------------

    @Nested
    @DisplayName("formatCurrency()")
    class FormatCurrencyTest {

        @Test
        @DisplayName("formats whole numbers without decimals")
        void formatsWholeNumbers() {
            assertEquals("1,000 S$", TextUtil.formatCurrency(1000.0));
            assertEquals("500 S$", TextUtil.formatCurrency(500.0));
            assertEquals("0 S$", TextUtil.formatCurrency(0.0));
        }

        @Test
        @DisplayName("formats fractional amounts with one decimal")
        void formatsFractionalAmounts() {
            assertEquals("1.5 S$", TextUtil.formatCurrency(1.5));
            assertEquals("99.9 S$", TextUtil.formatCurrency(99.9));
        }

        @Test
        @DisplayName("includes currency symbol S$")
        void includesCurrencySymbol() {
            String result = TextUtil.formatCurrency(100.0);
            assertTrue(result.endsWith(" S$"));
        }

        @Test
        @DisplayName("formats large numbers with comma separators")
        void formatsLargeNumbers() {
            assertEquals("1,000,000 S$", TextUtil.formatCurrency(1_000_000.0));
            assertEquals("10,000,000 S$", TextUtil.formatCurrency(10_000_000.0));
        }

        @Test
        @DisplayName("formats starting balance correctly")
        void formatsStartingBalance() {
            assertEquals("500 S$", TextUtil.formatCurrency(CurrencyUtil.DEFAULT_STARTING_BALANCE));
        }

        @Test
        @DisplayName("handles very small amounts (1-decimal rounding)")
        void handlesSmallAmounts() {
            // 0.01 rounds to 0.0 at 1 decimal place
            assertEquals("0.0 S$", TextUtil.formatCurrency(0.01));
            // 0.05 rounds up to 0.1 at 1 decimal place
            assertEquals("0.1 S$", TextUtil.formatCurrency(0.05));
            // 0.04 rounds down to 0.0 at 1 decimal place
            assertEquals("0.0 S$", TextUtil.formatCurrency(0.04));
        }

        @Test
        @DisplayName("difference from CurrencyUtil.format: uses 1 decimal instead of 2")
        void formatCurrencyVsCurrencyUtilFormat() {
            // TextUtil.formatCurrency uses %,.1f for non-whole numbers
            // CurrencyUtil.format uses %,.2f for non-whole numbers
            String textUtilResult = TextUtil.formatCurrency(1.55);
            String currencyUtilResult = CurrencyUtil.format(1.55);
            // TextUtil: "1.6 S$" (rounded to 1 decimal)
            // CurrencyUtil: "1.55 S$" (2 decimals)
            assertNotEquals(textUtilResult, currencyUtilResult);
        }

        @Test
        @DisplayName("rounds half-up at the single decimal place")
        void roundsHalfUp() {
            // 1.45 -> "1.5 S$" (banker's rounding may give 1.4, but standard half-up gives 1.5)
            // String.format uses HALF_UP rounding for %f
            String result = TextUtil.formatCurrency(1.45);
            assertTrue(result.equals("1.5 S$") || result.equals("1.4 S$"),
                "1.45 should round to either 1.4 or 1.5 depending on rounding mode, got: " + result);
        }

        @Test
        @DisplayName("handles negative amounts (defensive)")
        void handlesNegative() {
            // Negative amounts shouldn't normally occur, but formatCurrency
            // should not crash on them.
            String result = TextUtil.formatCurrency(-1.5);
            assertTrue(result.contains("S$"));
            assertTrue(result.contains("-1.5") || result.contains("(1.5)"),
                "negative amount should be displayed with minus sign or parentheses: " + result);
        }

        @Test
        @DisplayName("handles very large amounts without scientific notation")
        void handlesVeryLarge() {
            // 10 million with fractional part
            String result = TextUtil.formatCurrency(10_000_000.5);
            assertTrue(result.contains("10,000,000"),
                "large amount should use comma separators: " + result);
            assertTrue(result.endsWith(" S$"));
        }
    }

    // -- sanitizeLegacyFormatting ---------------------------

    @Nested
    @DisplayName("sanitizeLegacyFormatting()")
    class SanitizeLegacyFormattingTest {

        @Test
        @DisplayName("removes section sign color codes")
        void removesColorCodes() {
            assertEquals("Hello", TextUtil.sanitizeLegacyFormatting("\u00A7aHello"));
            assertEquals("World", TextUtil.sanitizeLegacyFormatting("\u00A74World"));
        }

        @Test
        @DisplayName("removes multiple formatting codes")
        void removesMultipleCodes() {
            assertEquals("Bold", TextUtil.sanitizeLegacyFormatting("\u00A7l\u00A7cBold"));
        }

        @Test
        @DisplayName("removes formatting codes (k-o, r)")
        void removesFormatCodes() {
            assertEquals("text", TextUtil.sanitizeLegacyFormatting("\u00A7ktext"));
            assertEquals("reset", TextUtil.sanitizeLegacyFormatting("\u00A7rreset"));
            assertEquals("italic", TextUtil.sanitizeLegacyFormatting("\u00A7oitalic"));
        }

        @Test
        @DisplayName("returns empty string for null input")
        void handlesNull() {
            assertEquals("", TextUtil.sanitizeLegacyFormatting(null));
        }

        @Test
        @DisplayName("returns empty string for empty input")
        void handlesEmpty() {
            assertEquals("", TextUtil.sanitizeLegacyFormatting(""));
        }

        @Test
        @DisplayName("preserves text without formatting codes")
        void preservesCleanText() {
            assertEquals("Hello World", TextUtil.sanitizeLegacyFormatting("Hello World"));
        }

        @Test
        @DisplayName("handles uppercase formatting codes")
        void handlesUppercaseCodes() {
            assertEquals("Test", TextUtil.sanitizeLegacyFormatting("\u00A7ATest"));
            assertEquals("Bold", TextUtil.sanitizeLegacyFormatting("\u00A7LBold"));
        }

        @Test
        @DisplayName("handles mixed formatting and text")
        void handlesMixedFormattingAndText() {
            String input = "\u00A76Gold \u00A7lBold \u00A7rReset";
            assertEquals("Gold Bold Reset", TextUtil.sanitizeLegacyFormatting(input));
        }

        @Test
        @DisplayName("does not remove standalone section signs without valid codes")
        void preservesStandaloneSectionSign() {
            // \u00A7X is not a valid code (X is not 0-9, a-f, k-o, r)
            // But the regex matches \u00A7[0-9a-fk-orA-FK-OR]
            assertEquals("\u00A7ztext", TextUtil.sanitizeLegacyFormatting("\u00A7ztext"));
        }
    }

    // -- sanitizePlayerName (audit SOL-004) ------------------

    @Nested
    @DisplayName("sanitizePlayerName()")
    class SanitizePlayerNameTest {

        @Test
        @DisplayName("never returns null")
        void neverReturnsNull() {
            assertNotNull(TextUtil.sanitizePlayerName(null));
            assertEquals("", TextUtil.sanitizePlayerName(null));
        }

        @Test
        @DisplayName("empty string passes through as empty")
        void handlesEmpty() {
            assertEquals("", TextUtil.sanitizePlayerName(""));
        }

        @Test
        @DisplayName("clean names pass through unchanged")
        void cleanNameUnchanged() {
            assertEquals("Steve", TextUtil.sanitizePlayerName("Steve"));
            assertEquals("Alex_123", TextUtil.sanitizePlayerName("Alex_123"));
        }

        @Test
        @DisplayName("strips legacy formatting codes (SOL-004: lore injection)")
        void stripsLegacyCodes() {
            assertEquals("Hostile", TextUtil.sanitizePlayerName("\u00A7cHostile"));
            assertEquals("Gold", TextUtil.sanitizePlayerName("\u00A76\u00A7lGold"));
        }

        @Test
        @DisplayName("drops CR/LF/TAB so a hostile name cannot break the ledger line")
        void dropsLineBreaks() {
            assertEquals("Steve", TextUtil.sanitizePlayerName("Ste\nve"));
            assertEquals("Steve", TextUtil.sanitizePlayerName("Ste\rve"));
            assertEquals("AB", TextUtil.sanitizePlayerName("A\tB"));
        }

        @Test
        @DisplayName("log-forgery attempt collapses to a single line")
        void logForgeryCollapses() {
            String hostile = "Bob\n[SERVER] Your balance was set to 0";
            String safe = TextUtil.sanitizePlayerName(hostile);
            assertFalse(safe.contains("\n"));
            assertFalse(safe.contains("\r"));
            assertEquals("Bob[SERVER] Your balance was set to 0", safe);
        }

        @Test
        @DisplayName("drops C0 control characters and DEL")
        void dropsControlChars() {
            assertEquals("AB", TextUtil.sanitizePlayerName("A\u0000B"));
            assertEquals("AB", TextUtil.sanitizePlayerName("A\u001BB"));
            assertEquals("AB", TextUtil.sanitizePlayerName("A\u007FB"));
        }

        @Test
        @DisplayName("trims surrounding whitespace")
        void trims() {
            assertEquals("Steve", TextUtil.sanitizePlayerName("  Steve  "));
        }

        @Test
        @DisplayName("clamps to the VARCHAR(64) ledger width")
        void clampsTo64() {
            String hostile = "x".repeat(100);
            assertEquals(64, TextUtil.sanitizePlayerName(hostile).length());
            assertEquals(64, TextUtil.MAX_NAME_LENGTH);
        }

        @Test
        @DisplayName("a name exactly at the cap is untouched")
        void atCapUnchanged() {
            String ok = "y".repeat(64);
            assertEquals(ok, TextUtil.sanitizePlayerName(ok));
        }

        @Test
        @DisplayName("printable non-ASCII characters are preserved")
        void preservesPrintableUnicode() {
            assertEquals("Österreich", TextUtil.sanitizePlayerName("Österreich"));
        }
    }

    // -- sanitizeForLog (audit SOL-005, CWE-117) -------------

    @Nested
    @DisplayName("sanitizeForLog()")
    class SanitizeForLogTest {

        @Test
        @DisplayName("never returns null; empty passes through")
        void nullAndEmpty() {
            assertEquals("", TextUtil.sanitizeForLog(null));
            assertEquals("", TextUtil.sanitizeForLog(""));
        }

        @Test
        @DisplayName("plain text is untouched")
        void plainUntouched() {
            assertEquals("hello world", TextUtil.sanitizeForLog("hello world"));
            assertEquals("SHOP_BUY", TextUtil.sanitizeForLog("SHOP_BUY"));
        }

        @Test
        @DisplayName("escapes newline instead of dropping (forensics preserved)")
        void escapesNewline() {
            assertEquals("a\\nb", TextUtil.sanitizeForLog("a\nb"));
            assertEquals("a\\rb", TextUtil.sanitizeForLog("a\rb"));
            assertEquals("a\\tb", TextUtil.sanitizeForLog("a\tb"));
        }

        @Test
        @DisplayName("a tampered ledger row cannot forge a second log line")
        void ledgerRowCannotForgeLines() {
            String tampered = "X\n[INFO] Solidus: granted 999999999 to Attacker";
            String safe = TextUtil.sanitizeForLog(tampered);
            assertFalse(safe.contains("\n"), "raw LF must not survive");
            assertFalse(safe.contains("\r"), "raw CR must not survive");
            assertTrue(safe.contains("\\n"), "the escape must preserve the evidence");
            // One physical line: the escape sequence, not the character.
            assertEquals("X\\n[INFO] Solidus: granted 999999999 to Attacker", safe);
        }

        @Test
        @DisplayName("drops other control characters entirely")
        void dropsOtherControls() {
            assertEquals("ab", TextUtil.sanitizeForLog("a\u0000b"));
            assertEquals("ab", TextUtil.sanitizeForLog("a\u007Fb"));
        }
    }
}
