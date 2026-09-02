package com.solidus.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.solidus.SolidusMod;
import com.solidus.api.EconomyHooks;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.auction.AuctionEntry;
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

            // Apply optional global economy settings (keys documented in the
            // README that were previously ignored - see applyGlobalSettings).
            applyGlobalSettings(root);

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
     * Applies optional top-level economy settings documented in the README:
     * <ul>
     *   <li>{@code "startingBalance"} / {@code "starting_balance"} - starting wallet for new players</li>
     *   <li>{@code "currency"}  - displayed currency symbol</li>
     *   <li>{@code "listingFee"} - auction listing fee in whole percent (e.g. 2 = 2%)</li>
     * </ul>
     * Missing keys keep the current values. These keys were previously
     * advertised in the README but silently ignored by the loader.
     */
    private void applyGlobalSettings(JsonObject root) {
        try {
            if (root.has("startingBalance") || root.has("starting_balance")) {
                JsonElement el = root.has("startingBalance")
                    ? root.get("startingBalance")
                    : root.get("starting_balance");
                CurrencyUtil.setStartingBalance(el.getAsDouble());
                SolidusMod.LOGGER.info("Applied shop.json startingBalance override.");
            }

            if (root.has("currency") && root.get("currency").isJsonPrimitive()) {
                CurrencyUtil.setCurrencySymbol(root.get("currency").getAsString());
                SolidusMod.LOGGER.info("Applied shop.json currency symbol override.");
            }

            if (root.has("listingFee") && root.get("listingFee").isJsonPrimitive()) {
                // Config uses whole-percent units: 2 => 0.02
                AuctionEntry.setListingFeePercent(root.get("listingFee").getAsDouble() / 100.0);
                SolidusMod.LOGGER.info("Applied shop.json listingFee override.");
            }
        } catch (Exception e) {
            SolidusMod.LOGGER.warn("Ignored invalid global setting in shop.json: {}", e.getMessage());
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

        // SECURITY FIX: validate that the material resolves to a real registry
        // item BEFORE charging any money. Previously a typo'd or removed
        // material deducted the price first and then produced an EMPTY stack -
        // money gone, nothing delivered.
        if (resolveItem(material) == null) {
            SolidusMod.LOGGER.error("Shop buy blocked - unknown material in shop.json: {}", material);
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

        // Transaction hook veto (Solidus 2.1.0+): cost is fully known, before
        // any money moves. A denial here is a clean no-op.
        SolidusTransactionHook.Decision hookDecision = EconomyHooks.allow(hook ->
            hook.allowShopPurchase(playerId, player.getName().getString(), totalCost));
        if (!hookDecision.allowed()) {
            pendingBuys.remove(playerId);
            player.sendSystemMessage(TextUtil.error(hookDecision.reason()));
            return;
        }

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
                    if (itemStack.isEmpty()) {
                        // Should be impossible now (validated before charging), but we
                        // NEVER keep money without delivering goods. Refund immediately.
                        SolidusMod.LOGGER.error(
                            "Buy produced an empty stack for material '{}'! Refunding {}.",
                            material, totalCost);
                        balanceManager.addBalance(player, totalCost);
                        player.sendSystemMessage(TextUtil.error("Purchase failed. You have been refunded."));
                        return;
                    }
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

                    // Hook notification (Solidus 2.1.0+): purchase fully settled.
                    EconomyHooks.notifyHooks(hook ->
                        hook.afterShopPurchase(playerId, player.getName().getString(), totalCost));

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
        }).exceptionally(ex -> {
            // Audit 2.1.3: an exceptionally-completed future skipped thenAccept
            // (and its finally block) - the pending-buy lock leaked until
            // disconnect, permanently blocking further purchases.
            player.level().getServer().execute(() -> {
                pendingBuys.remove(playerId);
                SolidusMod.LOGGER.error("Buy deduction future failed for {} - lock released.",
                    player.getName().getString(), ex);
            });
            return null;
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
     * FIX v3 (payout-before-removal TOCTOU): the items are removed
     * SYNCHRONOUSLY first, and only then is the balance credited - for exactly
     * the amount actually removed. If the credit fails, the items are restored.
     * This closes the window where a cheat client could move/drop part of the
     * stack during the async payout and still get paid the full amount.
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

        // Transaction hook veto (Solidus 2.1.0+): BEFORE the items are removed
        // from the inventory. A denial here is a clean no-op.
        SolidusTransactionHook.Decision hookDecision = EconomyHooks.allow(hook ->
            hook.allowShopSell(playerId, player.getName().getString()));
        if (!hookDecision.allowed()) {
            pendingSells.remove(playerId);
            player.sendSystemMessage(TextUtil.error(hookDecision.reason()));
            return;
        }

        // SECURITY FIX (payout-before-removal TOCTOU): previously the balance
        // was credited FIRST and the items removed afterwards, so a player
        // could move/drop part of the stack during the async credit window and
        // still get paid the full pre-checked amount.
        //
        // New flow: remove the items SYNCHRONOUSLY first, then pay for exactly
        // what was actually removed. If crediting fails, the items are restored
        // to the player. Nothing can be duplicated and nothing gets lost.
        //
        // Audit 2.1.3: the restore now returns the ACTUAL removed stacks
        // (with all data components - enchantments, names, durability) instead
        // of manufacturing fresh registry stacks, which silently stripped every
        // NBT component from restored items.
        java.util.List<net.minecraft.world.item.ItemStack> removedStacks = new java.util.ArrayList<>();
        int removedCount = removeItemFromInventory(player, material, quantity, removedStacks);
        if (removedCount <= 0) {
            pendingSells.remove(playerId);
            player.sendSystemMessage(TextUtil.error("You don't have " + quantity + "x " + material + " in your inventory."));
            return;
        }

        // Pay for exactly what was removed
        double totalValue = CurrencyUtil.round(item.sellPrice() * removedCount);

        BalanceManager balanceManager = economyEngine.getBalanceManager();
        balanceManager.addBalance(player, totalValue).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                try {
                    if (newBalance < 0) {
                        // Balance add failed - give the items back so nothing is lost.
                        // The actual removed stacks (NBT included) are restored.
                        SolidusMod.LOGGER.error("Sell balance add failed for {}! Restoring {}x {}.",
                            player.getName().getString(), removedCount, material);
                        restoreRemovedStacks(player, removedStacks);
                        player.sendSystemMessage(TextUtil.error(
                            "Transaction error. Your items have been returned. Please try again."));
                        return;
                    }

                    // Success notification
                    MutableComponent message = TextUtil.success("Sold " + removedCount + "x " + material + " for ")
                        .append(TextUtil.currency(CurrencyUtil.format(totalValue)));

                    // Log transaction
                    economyEngine.getTransactionLog().log(
                        TransactionLog.Type.SHOP_SELL,
                        player.getUUID(), player.getName().getString(),
                        null, null,
                        totalValue, material, removedCount,
                        "Sold " + removedCount + "x " + material + " to shop"
                    );

                    // Hook notification (Solidus 2.1.0+): sell fully settled.
                    EconomyHooks.notifyHooks(hook ->
                        hook.afterShopSell(playerId, player.getName().getString(), totalValue));

                    player.sendSystemMessage(message.append(
                        TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                        .append(TextUtil.currency(CurrencyUtil.format(newBalance)))
                    );
                } finally {
                    // Always release the lock so the player can sell again
                    pendingSells.remove(playerId);
                }
            });
        }).exceptionally(ex -> {
            // Audit 2.1.3: an exceptionally-completed future skipped thenAccept
            // entirely (and its finally block) - the pending-sell lock leaked
            // until disconnect AND the removed items were never restored.
            player.level().getServer().execute(() -> {
                pendingSells.remove(playerId);
                SolidusMod.LOGGER.error("Sell payout future failed for {} - restoring {}x {}.",
                    player.getName().getString(), removedCount, material, ex);
                restoreRemovedStacks(player, removedStacks);
                player.sendSystemMessage(TextUtil.error(
                    "Transaction error. Your items have been returned. Please try again."));
            });
            return null;
        });
    }

    /**
     * Restores the actual removed stacks to the player (inventory first,
     * feet-drop fallback) - preserving all data components.
     */
    private void restoreRemovedStacks(ServerPlayer player, java.util.List<net.minecraft.world.item.ItemStack> stacks) {
        for (net.minecraft.world.item.ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            if (!player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false);
            }
        }
    }

    /**
     * Restores previously-removed sell items when the balance credit failed.
     * Re-adds stacks using each item's real max stack size; whatever does not
     * fit into the inventory is dropped at the player's feet so no value can
     * ever be silently destroyed.
     *
     * @deprecated superseded by {@link #restoreRemovedStacks} (audit 2.1.3):
     * this rebuilds items from the registry and strips all data components
     * (enchantments, custom names, durability). Kept as a last-resort
     * fallback for callers without a stack snapshot.
     */
    @Deprecated
    private void restoreItemsToPlayer(ServerPlayer player, String material, int count) {
        net.minecraft.world.item.Item resolvedItem = resolveItem(material);
        if (resolvedItem == null) {
            SolidusMod.LOGGER.error("Cannot restore {}x {} - material unresolvable!", count, material);
            return;
        }
        int remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(resolvedItem.getDefaultMaxStackSize(), remaining);
            remaining -= chunk;
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(resolvedItem, chunk);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
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

    /**
     * Resolves a shop.json material name to a registry Item.
     * Returns {@code null} when the material does not exist. Shared by the
     * pre-charge validation in processBuy and stack creation so both sides
     * can never disagree about whether an item exists.
     */
    private static net.minecraft.world.item.Item resolveItem(String material) {
        if (material == null || material.isBlank()) return null;
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.tryParse(material.toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private net.minecraft.world.item.ItemStack createItemStack(String material, int quantity) {
        net.minecraft.world.item.Item item = resolveItem(material);
        if (item == null) {
            SolidusMod.LOGGER.error("Unknown material: {}", material);
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        try {
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
     * Removes items from the player's main inventory only.
     * Armor slots (36-39) and offhand (40) are protected - this prevents
     * players from accidentally selling equipped armor via the shop GUI.
     * The same safety pattern is used in SellCommand.java.
     *
     * Audit 2.1.3: optionally collects a snapshot of every removed partial
     * stack (with data components) so a failed payout can restore the exact
     * items instead of manufacturing fresh NBT-less ones.
     */
    private int removeItemFromInventory(ServerPlayer player, String material, int quantity) {
        return removeItemFromInventory(player, material, quantity, null);
    }

    private int removeItemFromInventory(ServerPlayer player, String material, int quantity,
                                         java.util.List<net.minecraft.world.item.ItemStack> removedSink) {
        int remaining = quantity;
        int removed = 0;
        // Only remove from main inventory (slots 0-35), skip armor and offhand
        for (int i = 0; i < 36 && remaining > 0; i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && getMaterialName(stack).equalsIgnoreCase(material)) {
                int toRemove = Math.min(stack.getCount(), remaining);
                if (removedSink != null && toRemove > 0) {
                    net.minecraft.world.item.ItemStack snapshot = stack.copy();
                    snapshot.setCount(toRemove);
                    removedSink.add(snapshot);
                }
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
     * Audit 2.1.3: accepts a snapshot of every consumed stack (regular items,
     * shulker contents, sold shulker boxes). Previously a failed credit
     * (reached via the MAX_TRANSACTION payout cap or a DB error) logged
     * "Items lost" and destroyed them. Now every consumed stack is restored.
     *
     * @param player        The selling player
     * @param totalEarnings The total amount earned from selling
     * @param totalItemsSold The total number of items sold
     */
    public void processSellAllEarnings(ServerPlayer player, double totalEarnings, int totalItemsSold) {
        processSellAllEarnings(player, totalEarnings, totalItemsSold, null);
    }

    /**
     * Processes earnings from a bulk sell with a restore snapshot.
     *
     * @param consumedStacks Exact stacks consumed for this payout (null = none
     *                       recorded; e.g. a caller with nothing to restore)
     */
    public void processSellAllEarnings(ServerPlayer player, double totalEarnings, int totalItemsSold,
                                        java.util.List<net.minecraft.world.item.ItemStack> consumedStacks) {
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        balanceManager.addBalance(player, totalEarnings).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                if (newBalance < 0) {
                    // Audit 2.1.3: credit failed (payout cap / DB error) -
                    // restore every consumed stack instead of destroying it.
                    SolidusMod.LOGGER.error(
                        "Sell-all balance add failed for {}! Restoring {} consumed stack(s). Amount: {}",
                        player.getName().getString(),
                        consumedStacks != null ? consumedStacks.size() : 0, totalEarnings);
                    if (consumedStacks != null) {
                        restoreRemovedStacks(player, consumedStacks);
                    }
                    player.sendSystemMessage(TextUtil.error(
                        "Transaction error. Your items have been returned. Please try again."));
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

                // Hook notification (Solidus 2.1.0+): sell-all payout settled.
                // (The corresponding allowShopSell veto already ran in the
                // /sell all command BEFORE any item was removed.)
                EconomyHooks.notifyHooks(hook ->
                    hook.afterShopSell(player.getUUID(), player.getName().getString(), totalEarnings));

                // Success notification
                player.sendSystemMessage(
                    TextUtil.success("Sold " + totalItemsSold + " item(s) for ")
                        .append(TextUtil.currency(CurrencyUtil.format(totalEarnings)))
                        .append(TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                        .append(TextUtil.currency(CurrencyUtil.format(newBalance)))
                );
            });
        }).exceptionally(ex -> {
            // Audit 2.1.3: exceptionally-completed futures skipped thenAccept -
            // restore the consumed stacks and never silently destroy them.
            player.level().getServer().execute(() -> {
                SolidusMod.LOGGER.error("Sell-all payout future failed for {} - restoring consumed stacks.",
                    player.getName().getString(), ex);
                if (consumedStacks != null) {
                    restoreRemovedStacks(player, consumedStacks);
                }
                player.sendSystemMessage(TextUtil.error(
                    "Transaction error. Your items have been returned. Please try again."));
            });
            return null;
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
