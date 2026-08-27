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
