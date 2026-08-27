package com.solidus.util;

/**
 * Centralized currency formatting and constants for the Solidus economy.
 *
 * The internal currency unit is "Solidus" (abbreviated S$).
 * All calculations use double precision to support fractional pricing.
 */
public final class CurrencyUtil {

    /** The currency symbol displayed to players (legacy/default constant). */
    public static final String CURRENCY_SYMBOL = "S$";

    /** The currency name displayed in full */
    public static final String CURRENCY_NAME = "Solidus";

    /** Default starting balance for new players (legacy/default constant). */
    public static final double DEFAULT_STARTING_BALANCE = 500.0;

    // ------------------------------------------------------------------
    //  Runtime-adjustable economy settings (shop.json overrides)
    //
    //  The legacy constants above are kept for backward compatibility
    //  (tests and ecosystem mods reference them). Internal code reads
    //  through the getters below, so server operators can override the
    //  values via optional top-level keys in shop.json without a restart:
    //
    //    "startingBalance" / "starting_balance" - new-player wallet
    //    "currency"                             - displayed symbol
    // ------------------------------------------------------------------

    private static volatile double startingBalance = DEFAULT_STARTING_BALANCE;
    private static volatile String currencySymbol = CURRENCY_SYMBOL;

    /** The starting balance new players receive (runtime-configurable). */
    public static double getStartingBalance() {
        return startingBalance;
    }

    /**
     * Sets the starting balance for new players.
     * Invalid values (negative, NaN, over MAX_BALANCE) are ignored so that a
     * single bad line in shop.json cannot corrupt the economy.
     */
    public static void setStartingBalance(double value) {
        if (isValidBalance(value)) {
            startingBalance = round(value);
        }
    }

    /** The currency symbol used by all display formatting paths. */
    public static String getCurrencySymbol() {
        return currencySymbol;
    }

    /**
     * Sets the displayed currency symbol. Ignores blank/unsafe values (after
     * legacy-formatting sanitization) and caps the length at 8 characters so
     * GUI lore lines remain readable.
     */
    public static void setCurrencySymbol(String symbol) {
        if (symbol == null) return;
        String cleaned = TextUtil.sanitizeLegacyFormatting(symbol).trim();
        if (cleaned.isEmpty() || cleaned.length() > 8) return;
        currencySymbol = cleaned;
    }

    /** Minimum transaction amount (prevents dust transactions) */
    public static final double MIN_TRANSACTION = 0.01;

    /** Maximum single transaction amount (anti-exploit cap) */
    public static final double MAX_TRANSACTION = 10_000_000.0;

    /** Maximum balance a player can hold */
    public static final double MAX_BALANCE = 100_000_000.0;

    private CurrencyUtil() {}

    /**
     * Formats a numeric value as a currency display string.
     * Uses locale-aware number formatting with appropriate decimal places.
     *
     * @param amount The raw currency amount
     * @return Formatted string like "1,250.5 S$"
     */
    public static String format(double amount) {
        String symbol = getCurrencySymbol();
        if (amount == (long) amount) {
            return String.format("%,d", (long) amount) + " " + symbol;
        }
        return String.format("%,.2f", amount) + " " + symbol;
    }

    /**
     * Formats a currency amount for compact display (e.g., in lore lines).
     */
    public static String formatCompact(double amount) {
        String symbol = getCurrencySymbol();
        if (amount >= 1_000_000) {
            return String.format("%.1fM", amount / 1_000_000) + " " + symbol;
        }
        if (amount >= 1_000) {
            return String.format("%.1fK", amount / 1_000) + " " + symbol;
        }
        if (amount == (long) amount) {
            return (long) amount + " " + symbol;
        }
        return String.format("%.1f", amount) + " " + symbol;
    }

    /**
     * Validates whether a transaction amount is within acceptable bounds.
     *
     * @param amount The amount to validate
     * @return true if the amount is valid for processing
     */
    public static boolean isValidAmount(double amount) {
        return amount >= MIN_TRANSACTION && amount <= MAX_TRANSACTION
            && !Double.isNaN(amount) && !Double.isInfinite(amount);
    }

    /**
     * Validates whether a balance value is within acceptable storage bounds.
     *
     * @param balance The balance to validate
     * @return true if the balance is within storage limits
     */
    public static boolean isValidBalance(double balance) {
        return balance >= 0.0 && balance <= MAX_BALANCE
            && !Double.isNaN(balance) && !Double.isInfinite(balance);
    }

    /**
     * Rounds a currency value to 2 decimal places to prevent floating-point drift.
     *
     * @param amount The raw amount
     * @return The rounded amount
     */
    public static double round(double amount) {
        return Math.round(amount * 100.0) / 100.0;
    }
}
