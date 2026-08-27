package com.solidus.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.solidus.SolidusMod;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.TransactionLog;
import com.solidus.util.ConfigManager;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shop Manager - Core controller for the virtual server shop system.
 *
 * Responsibilities:
 * - Loads and parses shop.json configuration from the config directory
 * - Manages shop sections, pagination, and item data
 * - Opens virtual GENERIC_9x6 chest GUI for players
 * - Processes buy/sell transactions through the economy engine
 *
 * Text Component Parsing:
 * In Minecraft 26.1.2, the official ComponentSerialization.CODEC is used
 * to parse text components from shop.json instead of a custom GSON parser.
 * This ensures full compatibility with Mojang's evolving Data Components
 * and text architecture - any future changes to the Component system will
 * be automatically supported without breaking the mod.
 *
 * Architecture:
 * The shop operates entirely via server-driven packet manipulation.
 * The client sees a standard ChestMenu, but all slot interactions
 * are intercepted and processed server-side. Items in the shop are
 * "Display-Only" - moving, dragging, or shifting items is blocked.
 */
public class ShopManager {

    private final EconomyEngine economyEngine;
    // ConcurrentHashMap for thread-safe access from server tick and reload commands
    private final Map<String, ShopSection> sections = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    // Tracks players with a pending sell transaction to prevent double-sell race condition.
    // When processSell starts, the player UUID is added; it is removed when the async chain completes.
    private final Set<UUID> pendingSells = ConcurrentHashMap.newKeySet();

    // Tracks players with a pending buy transaction to prevent double-buy race condition.
    // When processBuy starts, the player UUID is added; it is removed when the async chain completes.
    private final Set<UUID> pendingBuys = ConcurrentHashMap.newKeySet();

    public ShopManager(EconomyEngine economyEngine) {
        this.economyEngine = economyEngine;
    }

    /**
     * Loads the shop configuration from shop.json.
     * If the file doesn't exist, copies the default from the JAR.
     */
    public void loadConfiguration() {
        SolidusMod.LOGGER.info("Loading shop configuration...");

        // Ensure default shop.json exists
        ConfigManager.copyDefaultIfMissing("shop.json", "shop.json");

        // Load and parse
        String content = ConfigManager.readFile("shop.json");
        if (content == null) {
            SolidusMod.LOGGER.error("Failed to load shop.json! Shop will be empty.");
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject sectionsObj = root.getAsJsonObject("sections");

            sections.clear();

            for (Map.Entry<String, JsonElement> entry : sectionsObj.entrySet()) {
                String sectionKey = entry.getKey();
                JsonObject sectionObj = entry.getValue().getAsJsonObject();
                ShopSection section = parseSection(sectionKey, sectionObj);
                sections.put(sectionKey, section);
            }

            loaded = true;
            SolidusMod.LOGGER.info("Shop configuration loaded: {} sections, {} total items.",
                sections.size(), sections.values().stream().mapToInt(s -> s.items.size()).sum());

        } catch (Exception e) {
            SolidusMod.LOGGER.error("Failed to parse shop.json!", e);
        }
    }

    /**
     * Parses a single shop section from JSON.
     */
    private ShopSection parseSection(String key, JsonObject obj) {
        // Parse display name using the official Minecraft Codec system
        JsonObject displayNameObj = obj.getAsJsonObject("display_name");
        Component displayName = parseTextComponent(displayNameObj);

        // Parse icon material
        String icon = obj.has("icon") ? obj.get("icon").getAsString() : "CHEST";

        // Parse items
        List<ShopItem> items = new ArrayList<>();
        if (obj.has("items")) {
            JsonObject itemsObj = obj.getAsJsonObject("items");
            for (Map.Entry<String, JsonElement> itemEntry : itemsObj.entrySet()) {
                JsonObject itemObj = itemEntry.getValue().getAsJsonObject();
                items.add(parseShopItem(itemObj));
            }
        }

        return new ShopSection(key, displayName, icon, items);
    }

    /**
     * Parses a shop item from JSON.
     */
    private ShopItem parseShopItem(JsonObject obj) {
        String material = obj.get("material").getAsString();
        double buyPrice = obj.has("buy-price") ? obj.get("buy-price").getAsDouble() : -1;
        double sellPrice = obj.has("sell-price") ? obj.get("sell-price").getAsDouble() : -1;

        return new ShopItem(material, buyPrice, sellPrice);
    }

    /**
     * Parses a JSON text component object into a Minecraft Component
     * using the official ComponentSerialization.CODEC.
     *
     * In Minecraft 26.1.2, the game fully relies on the Codec system
     * for serializing and deserializing data components and text.
     * Using the official Codec instead of a custom GSON parser ensures
     * forward compatibility with any changes Mojang makes to the
     * Component architecture in future versions.
     *
     * Supports the format: { "text": "...", "color": "...", "bold": true }
     * This is the same format used by Minecraft's own JSON text components.
     *
     * @param json The JSON object representing a text component
     * @return A properly constructed Minecraft Component
     */
    private Component parseTextComponent(JsonObject json) {
        if (json == null) {
            return Component.literal("Unknown");
        }

        try {
            // Use the official Minecraft Codec to parse the text component.
            // ComponentSerialization.CODEC is the authoritative parser that
            // Minecraft uses internally for all text component operations.
            // It handles all valid JSON text component features including:
            // text, color, bold, italic, underlined, strikethrough, obfuscated,
            // hoverEvent, clickEvent, extra (children), and more.
            DataResult<Component> result = ComponentSerialization.CODEC.parse(
                JsonOps.INSTANCE, json);

            return result.resultOrPartial(error -> {
                SolidusMod.LOGGER.warn("Failed to parse text component from shop.json: {}", error);
            }).orElse(Component.literal("Unknown"));
        } catch (Exception e) {
            SolidusMod.LOGGER.warn("Exception parsing text component from shop.json: {}", e.getMessage());
            return Component.literal("Unknown");
        }
    }

    /**
     * Opens the virtual shop GUI for a player.
     * Creates and registers a ShopScreenHandler that manages the entire
     * interaction lifecycle through server-side packet manipulation.
     */
    public void openShop(ServerPlayer player) {
        if (!loaded) {
            player.sendSystemMessage(TextUtil.error("Shop is not loaded yet. Please contact an admin."));
            return;
        }

        // Open the shop starting at the main menu (section selection)
        ShopGUI.openMainMenu(player, this);
    }

    /**
     * Opens a specific section of the shop for a player.
     */
    public void openSection(ServerPlayer player, String sectionKey, int page) {
        ShopSection section = sections.get(sectionKey);
        if (section == null) {
            player.sendSystemMessage(TextUtil.error("Shop section not found."));
            return;
        }
        ShopGUI.openSection(player, this, section, page);
    }

    /**
     * Processes a buy transaction for a shop item.
     *
     * Transaction Flow (fully async - no server thread blocking):
     * 1. Validate the item exists and has a valid buy price
     * 2. Prevent double-buy with pendingBuys guard
     * 3. Atomically deduct the price from the player's balance (single async step)
     * 4. On success, spawn the purchased item stack into the player's inventory
     *
     * TOCTOU Fix (v2):
     * Previously, the balance was checked first (getBalance) then deducted
     * (subtractBalance) in separate async steps with a server.execute() hop
     * in between. This created a time-of-check/time-of-use gap where the
     * player could spend money elsewhere between the check and deduction.
     *
     * Now, we skip the separate balance check and go straight to subtractBalance(),
     * which atomically checks-and-deducts on the single-threaded economy executor.
     * If the player has insufficient funds, subtractBalance returns -1.
     * This eliminates the TOCTOU window entirely.
     *
     * @param player     The buying player
     * @param material   The Minecraft material name
     * @param quantity   The number of items to buy
     */
    public void processBuy(ServerPlayer player, String material, int quantity) {
        // Validate quantity - prevents negative/zero exploits and overflow.
        if (quantity <= 0) {
            player.sendSystemMessage(TextUtil.error("Invalid quantity."));
            return;
        }
        if (quantity > 2304) { // 36 stacks of 64 - sane upper bound
            player.sendSystemMessage(TextUtil.error("Quantity too large. Maximum is 2304 (36 stacks)."));
            return;
        }

        // Find the item in any section
        ShopItem item = findItem(material);
        if (item == null || item.buyPrice() <= 0) {
            player.sendSystemMessage(TextUtil.error("This item cannot be purchased."));
            return;
        }

        // Prevent double-buy race condition
        UUID playerId = player.getUUID();
        if (!pendingBuys.add(playerId)) {
            player.sendSystemMessage(TextUtil.error("A purchase is already in progress. Please wait."));
            return;
        }

        // Use double arithmetic to avoid integer overflow on large quantities
        double totalCost = CurrencyUtil.round(item.buyPrice() * (double) quantity);
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        // Atomic check-and-deduct: subtractBalance checks funds AND deducts
        // on the same single-threaded executor, eliminating the TOCTOU window.
        balanceManager.subtractBalance(player, totalCost).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                try {
                    if (newBalance < 0) {
                        // Insufficient funds or failure - no money was deducted
                        player.sendSystemMessage(
                            TextUtil.error("Insufficient funds! You need " + CurrencyUtil.format(totalCost)
                                + " to complete this purchase."));
                        return;
                    }

                    // Spawn item into player's inventory
                    net.minecraft.world.item.ItemStack itemStack = createItemStack(material, quantity);
                    if (!player.getInventory().add(itemStack)) {
                        // Inventory full - drop at player's feet
                        player.drop(itemStack, false);
                        player.sendSystemMessage(TextUtil.warning("Inventory full! Item dropped at your feet."));
                    }

                    // Log transaction
                    economyEngine.getTransactionLog().log(
                        TransactionLog.Type.SHOP_BUY,
                        player.getUUID(), player.getName().getString(),
                        null, null,
                        totalCost, material, quantity,
                        "Bought " + quantity + "x " + material + " from shop"
                    );

                    // Success notification
                    player.sendSystemMessage(
                        TextUtil.success("Purchased " + quantity + "x " + material + " for ")
                            .append(TextUtil.currency(CurrencyUtil.format(totalCost)))
                            .append(TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                            .append(TextUtil.currency(CurrencyUtil.format(newBalance)))
                    );
                } finally {
                    // Always release the lock so the player can buy again
                    pendingBuys.remove(playerId);
                }
            });
        });
    }

    /**
     * Processes a sell transaction for a shop item.
     *
     * Transaction Flow (fully async - no server thread blocking):
     * 1. Validate the item exists and has a valid sell price
     * 2. Verify the player has the item in their inventory
     * 3. Atomically add the sell price to the player's balance (async chain)
     * 4. Remove the item from the player's inventory ONLY after balance succeeds
     *
     * No .join() is used - the balance add operation is chained with
     * .thenAccept() and server-thread callbacks via player.level().getServer().execute(),
     * preventing any server tick thread blocking.
     *
     * FIX: Balance is added BEFORE removing items to prevent item loss
     * if the balance operation fails. Items are only removed after confirming
     * the balance was successfully credited.
     *
     * @param player     The selling player
     * @param material   The Minecraft material name
     * @param quantity   The number of items to sell
     */
    public void processSell(ServerPlayer player, String material, int quantity) {
        // Validate quantity - prevents negative/zero exploits.
        if (quantity <= 0) {
            player.sendSystemMessage(TextUtil.error("Invalid quantity."));
            return;
        }

        ShopItem item = findItem(material);
        if (item == null || item.sellPrice() <= 0) {
            player.sendSystemMessage(TextUtil.error("This item cannot be sold."));
            return;
        }

        // Prevent double-sell race condition: reject if this player already has a pending sell
        UUID playerId = player.getUUID();
        if (!pendingSells.add(playerId)) {
            player.sendSystemMessage(TextUtil.error("A sell transaction is already in progress. Please wait."));
            return;
        }

        // Check if player has the item
        if (!hasItemInInventory(player, material, quantity)) {
            pendingSells.remove(playerId);
            player.sendSystemMessage(TextUtil.error("You don't have " + quantity + "x " + material + " in your inventory."));
            return;
        }

        // Calculate sell price
        double totalValue = CurrencyUtil.round(item.sellPrice() * quantity);

        // Add balance FIRST - if this fails, items are NOT removed (prevents item loss)
        // FIX: Previously items were removed before balance add, causing item loss on failure
        BalanceManager balanceManager = economyEngine.getBalanceManager();
        balanceManager.addBalance(player, totalValue).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                try {
                    if (newBalance < 0) {
                        // Balance add failed - items remain in inventory, no data loss
                        SolidusMod.LOGGER.error("Sell balance add failed for {}! Items not removed.",
                            player.getName().getString());
                        player.sendSystemMessage(TextUtil.error("Transaction error. Your items are safe. Please try again."));
                        return;
                    }

                    // Balance added successfully - now safe to remove items.
                    // Re-verify the inventory still has the items (they may have been
                    // moved/dropped between the pre-check and now), and only remove
                    // what's actually present. The player already received the money,
                    // so we log any shortfall as a warning for admin investigation.
                    int actuallyRemoved = removeItemFromInventory(player, material, quantity);
                    if (actuallyRemoved < quantity) {
                        SolidusMod.LOGGER.warn(
                            "Sell item count mismatch for {}: expected {}x {} but only removed {}x. " +
                            "Player may have moved items during the async balance operation.",
                            player.getName().getString(), quantity, material, actuallyRemoved);
                    }

                    // Success notification
                    MutableComponent message = TextUtil.success("Sold " + quantity + "x " + material + " for ")
                        .append(TextUtil.currency(CurrencyUtil.format(totalValue)));

                    // Log transaction
                    economyEngine.getTransactionLog().log(
                        TransactionLog.Type.SHOP_SELL,
                        player.getUUID(), player.getName().getString(),
                        null, null,
                        totalValue, material, quantity,
                        "Sold " + quantity + "x " + material + " to shop"
                    );

                    player.sendSystemMessage(message.append(
                        TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                        .append(TextUtil.currency(CurrencyUtil.format(newBalance)))
                    );
                } finally {
                    // Always release the lock so the player can sell again
                    pendingSells.remove(playerId);
                }
            });
        });
    }

    // -- Helpers -------------------------------------------

    /**
     * Public accessor for finding an item by material name.
     * Used by the sell system to look up sell prices.
     */
    public ShopItem findItem(String material) {
        for (ShopSection section : sections.values()) {
            for (ShopItem item : section.items) {
                if (item.material().equalsIgnoreCase(material)) {
                    return item;
                }
            }
        }
        return null;
    }

    private net.minecraft.world.item.ItemStack createItemStack(String material, int quantity) {
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.tryParse(material.toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
            if (item == null) {
                SolidusMod.LOGGER.error("Unknown material: {}", material);
                return net.minecraft.world.item.ItemStack.EMPTY;
            }
            return new net.minecraft.world.item.ItemStack(item, quantity);
        } catch (Exception e) {
            SolidusMod.LOGGER.error("Failed to create ItemStack for material: {}", material, e);
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /**
     * Extracts the registry path name from an ItemStack for reliable
     * material matching. This avoids issues with getItem().toString()
     * which may include namespace prefixes or vary by mapping.
     */
    private String getMaterialName(net.minecraft.world.item.ItemStack stack) {
        return TextUtil.getMaterialName(stack);
    }

    /**
     * Checks if the player has enough of a specific item in their main inventory.
     * Armor slots (36-39) are excluded to prevent accidental armor sales.
     * Offhand (slot 40) is excluded from shop sells for safety - players
     * can use /sell commands if they want to sell offhand items.
     */
    private boolean hasItemInInventory(ServerPlayer player, String material, int quantity) {
        int count = 0;
        // getContainerSize() = 41 (36 main + 4 armor + 1 offhand)
        // Only count items in main inventory (slots 0-35)
        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && getMaterialName(stack).equalsIgnoreCase(material)) {
                count += stack.getCount();
            }
        }
        return count >= quantity;
    }

    /**
     * Removes items from the player's main inventory only.
     * Armor slots (36-39) and offhand (40) are protected - this prevents
     * players from accidentally selling equipped armor via the shop GUI.
     * The same safety pattern is used in SellCommand.java.
     */
    private int removeItemFromInventory(ServerPlayer player, String material, int quantity) {
        int remaining = quantity;
        int removed = 0;
        // Only remove from main inventory (slots 0-35), skip armor and offhand
        for (int i = 0; i < 36 && remaining > 0; i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && getMaterialName(stack).equalsIgnoreCase(material)) {
                int toRemove = Math.min(stack.getCount(), remaining);
                stack.shrink(toRemove);
                remaining -= toRemove;
                removed += toRemove;
            }
        }
        return removed;
    }

    /**
     * Processes earnings from a bulk sell operation (sell all / sell GUI).
     * Adds the total earnings to the player's balance and logs the transaction.
     *
     * @param player        The selling player
     * @param totalEarnings The total amount earned from selling
     * @param totalItemsSold The total number of items sold
     */
    public void processSellAllEarnings(ServerPlayer player, double totalEarnings, int totalItemsSold) {
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        balanceManager.addBalance(player, totalEarnings).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                if (newBalance < 0) {
                    SolidusMod.LOGGER.error("CRITICAL: Sell-all balance add failed for {}! Items lost. Amount: {}",
                        player.getName().getString(), totalEarnings);
                    player.sendSystemMessage(TextUtil.error("Transaction error. Please contact an admin."));
                    return;
                }

                // Log transaction
                economyEngine.getTransactionLog().log(
                    TransactionLog.Type.SHOP_SELL,
                    player.getUUID(), player.getName().getString(),
                    null, null,
                    totalEarnings, "VARIOUS", totalItemsSold,
                    "Sold " + totalItemsSold + " items via /sell all for " + CurrencyUtil.format(totalEarnings)
                );

                // Success notification
                player.sendSystemMessage(
                    TextUtil.success("Sold " + totalItemsSold + " item(s) for ")
                        .append(TextUtil.currency(CurrencyUtil.format(totalEarnings)))
                        .append(TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                        .append(TextUtil.currency(CurrencyUtil.format(newBalance)))
                );
            });
        });
    }

    /**
     * Public accessor for extracting the material name from an ItemStack.
     * Used by the sell system for item identification.
     */
    public static String getMaterialNameStatic(net.minecraft.world.item.ItemStack stack) {
        return TextUtil.getMaterialName(stack);
    }

    /**
     * Clears any pending buy/sell locks for a player who has disconnected.
     * <p>
     * If a player disconnects mid-transaction (e.g. while the async balance
     * operation is in flight), the {@code pendingBuys} / {@code pendingSells}
     * set would otherwise hold their UUID forever, blocking all future
     * purchases until the server restarts. This hook lets the disconnect
     * listener release those locks so the player can transact normally
     * when they reconnect.
     * <p>
     * <b>Concurrency note:</b> The async transaction callback may still
     * execute after this call returns; the callback's {@code finally}
     * block uses {@link java.util.Set#remove(Object)} which is a no-op
     * if the UUID was already removed here. So the cleanup is safe.
     */
    public void clearPendingTransactions(UUID playerId) {
        if (playerId == null) return;
        pendingBuys.remove(playerId);
        pendingSells.remove(playerId);
    }

    /**
     * Returns the number of players currently locked in a pending buy.
     * Useful for monitoring and diagnostics.
     */
    public int getPendingBuyCount() {
        return pendingBuys.size();
    }

    /**
     * Returns the number of players currently locked in a pending sell.
     */
    public int getPendingSellCount() {
        return pendingSells.size();
    }

    // -- Getters -------------------------------------------

    public Map<String, ShopSection> getSections() {
        return Collections.unmodifiableMap(sections);
    }

    public EconomyEngine getEconomyEngine() {
        return economyEngine;
    }

    public boolean isLoaded() {
        return loaded;
    }

    // -- Data Classes --------------------------------------

    /**
     * Represents a shop section (category of items).
     */
    public record ShopSection(
        String key,
        Component displayName,
        String icon,
        List<ShopItem> items
    ) {}

    /**
     * Represents a single shop item with pricing.
     *
     * @param material  The Minecraft Material name (e.g., "DIAMOND")
     * @param buyPrice  Price to buy 1 unit (-1 = not purchasable)
     * @param sellPrice Price received for selling 1 unit
     */
    public record ShopItem(
        String material,
        double buyPrice,
        double sellPrice
    ) {}
}
