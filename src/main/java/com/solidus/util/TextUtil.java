package com.solidus.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

/**
 * Text Component Utility - CRASH PREVENTION LAYER
 *
 * All visual fields, titles, lore, and text responses MUST use this utility.
 * The legacy formatting section sign character (paragraph sign) is completely
 * deprecated and stripped from the client/server runtime. Hardcoding or
 * injecting raw text strings containing that character will trigger immediate
 * client network packet disconnects or unexpected thread crashes.
 *
 * This utility enforces the modern Component architecture standard.
 */
public final class TextUtil {

    private TextUtil() {
        // Utility class - no instantiation
    }

    /**
     * Creates a styled text component using the modern Component architecture.
     * This is the ONLY approved method for creating display text.
     *
     * @param text    The literal text content
     * @param color   The ChatFormatting color to apply
     * @return A properly constructed Component
     */
    public static MutableComponent styled(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    /**
     * Creates a styled text component with bold formatting.
     */
    public static MutableComponent styledBold(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withBold(true));
    }

    /**
     * Creates a styled text component with italic formatting.
     */
    public static MutableComponent styledItalic(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color).withItalic(true));
    }

    /**
     * Creates a styled text component with both bold and italic formatting.
     */
    public static MutableComponent styledBoldItalic(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(style ->
            style.withColor(color).withBold(true).withItalic(true)
        );
    }

    /**
     * Creates a plain text component without any formatting.
     */
    public static MutableComponent plain(String text) {
        return Component.literal(text);
    }

    /**
     * Creates an error message component (red, bold).
     */
    public static MutableComponent error(String text) {
        return Component.literal(text).withStyle(style ->
            style.withColor(ChatFormatting.RED).withBold(true)
        );
    }

    /**
     * Creates a success message component (green).
     */
    public static MutableComponent success(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    /**
     * Creates a warning message component (yellow).
     */
    public static MutableComponent warning(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    /**
     * Creates a currency display component (gold).
     */
    public static MutableComponent currency(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD);
    }

    /**
     * Creates a shop title component (gold, bold).
     */
    public static MutableComponent shopTitle(String text) {
        return Component.literal(text).withStyle(style ->
            style.withColor(ChatFormatting.GOLD).withBold(true)
        );
    }

    /**
     * Creates a section header component (dark_aqua, bold).
     */
    public static MutableComponent sectionHeader(String text) {
        return Component.literal(text).withStyle(style ->
            style.withColor(ChatFormatting.DARK_AQUA).withBold(true)
        );
    }

    /**
     * Creates an item lore line (gray, italic).
     */
    public static MutableComponent loreLine(String text) {
        return Component.literal(text).withStyle(style ->
            style.withColor(ChatFormatting.GRAY).withItalic(true)
        );
    }

    /**
     * Creates a price display lore line (green for buy, red for sell).
     */
    public static MutableComponent buyPriceLore(double price) {
        return Component.literal("Buy: " + formatCurrency(price))
            .withStyle(ChatFormatting.GREEN);
    }

    /**
     * Creates a sell price display lore line.
     */
    public static MutableComponent sellPriceLore(double price) {
        return Component.literal("Sell: " + formatCurrency(price))
            .withStyle(ChatFormatting.RED);
    }

    /**
     * Formats a numeric value as a currency string for compact display
     * in GUI lore lines. Uses 1 decimal place for fractional amounts to
     * keep the display concise - this is intentionally different from
     * {@link CurrencyUtil#format(double)} which uses 2 decimals for
     * higher precision in chat messages.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code 1000.0} -> {@code "1,000 S$"}</li>
     *   <li>{@code 1.5}    -> {@code "1.5 S$"}</li>
     *   <li>{@code 99.99}  -> {@code "100.0 S$"} (1-decimal rounding)</li>
     * </ul>
     */
    public static String formatCurrency(double amount) {
        String symbol = CurrencyUtil.getCurrencySymbol();
        if (amount == (long) amount) {
            return String.format("%,d", (long) amount) + " " + symbol;
        }
        return String.format("%,.1f", amount) + " " + symbol;
    }

    /**
     * SANITIZATION: Strips any legacy formatting codes from a string.
     * This is a safety net to prevent accidental injection of the
     * banned paragraph-sign character into any text field.
     *
     * @param input The raw string to sanitize
     * @return The string with all legacy formatting stripped
     */
    public static String sanitizeLegacyFormatting(String input) {
        if (input == null) return "";
        // Remove the legacy section-sign character and the following code character
        return input.replaceAll("\u00A7[0-9a-fk-orA-FK-OR]", "");
    }

    /**
     * Maximum length for a stored/displayed player name (audit SOL-004).
     * Matches the MySQL {@code player_name VARCHAR(64)} / ledger column width.
     * Vanilla online-mode names are max 16 chars; the cap only bites for
     * hostile names arriving via permissive offline-mode proxies.
     */
    public static final int MAX_NAME_LENGTH = 64;

    /**
     * SECURITY (audit SOL-004): sanitizes a player-sourced name BEFORE it is
     * written to storage or rendered into GUI lore / chat components.
     *
     * <p>Online-mode servers implicitly guarantee {@code [A-Za-z0-9_]{1,16}},
     * but this mod explicitly supports offline-mode and permissive proxies
     * (documented in PayCommand). A hostile name arriving through such a
     * proxy previously could: exceed the 64-char column width (breaking every
     * subsequent ledger insert on MySQL), carry CRLF sequences that pollute
     * logs and CSV exports, or embed legacy formatting codes into lore.</p>
     *
     * <p>The sanitizer strips legacy {@code §} codes, removes ALL control
     * characters (C0 range, DEL, and therefore newlines/tabs), trims, and
     * clamps the result to {@link #MAX_NAME_LENGTH}. It never returns null
     * (empty string for null input) so callers can pass results straight to
     * {@code setString}.</p>
     *
     * @param input raw name as it arrived from the session/DB/proxy
     * @return a safe single-line name, at most 64 characters
     */
    public static String sanitizePlayerName(String input) {
        if (input == null || input.isEmpty()) return "";
        String cleaned = sanitizeLegacyFormatting(input);
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            // Keep printable characters only: drops \n \r \t and every other
            // C0 control + DEL (7F). Names never need control characters.
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String result = sb.toString().trim();
        if (result.length() > MAX_NAME_LENGTH) {
            result = result.substring(0, MAX_NAME_LENGTH);
        }
        return result;
    }

    /**
     * SECURITY (audit SOL-005, CWE-117): makes a string safe for single-line
     * log output. Strings logged by the economy often originate from database
     * rows (transaction type codes, stored player names) — a tampered row
     * must not be able to forge log lines via embedded CR/LF sequences.
     *
     * <p>Unlike {@link #sanitizePlayerName} this ESCAPES the newlines instead
     * of dropping them ({@code \n} becomes the two-character sequence
     * {@code \n}), preserving forensic evidence while guaranteeing one log
     * entry stays one line. Control characters other than CR/LF/TAB are
     * removed entirely.</p>
     *
     * @param input raw value about to be passed to a LOGGER call
     * @return a single-line-safe representation, never null
     */
    public static String sanitizeForLog(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n') {
                sb.append('\\').append('n');
            } else if (c == '\r') {
                sb.append('\\').append('r');
            } else if (c == '\t') {
                sb.append('\\').append('t');
            } else if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
            // other control chars: dropped
        }
        return sb.toString();
    }

    /**
     * Extracts the registry path name from an ItemStack for reliable
     * material matching. This avoids issues with getItem().toString()
     * which may include namespace prefixes or vary by mapping.
     *
     * This is the shared utility method to avoid duplication across
     * ShopManager, SellScreenHandler, and SellCommand.
     *
     * @param stack The ItemStack to extract the material name from
     * @return The uppercase registry path name (e.g., "DIAMOND")
     */
    public static String getMaterialName(net.minecraft.world.item.ItemStack stack) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath().toUpperCase();
        } catch (Exception e) {
            return stack.getItem().toString().toUpperCase();
        }
    }
}
