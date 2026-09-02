package com.solidus.commands;

import com.solidus.api.EconomyHooks;
import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.sell.SellGUI;
import com.solidus.sell.SellScreenHandler;
import com.solidus.shop.ShopManager;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /sell command - Sells items from the player's inventory.
 *
 * Usage:
 *   /sell gui          - Opens a virtual chest GUI to place items for selling
 *   /sell all          - Sells all sellable items in the player's inventory
 *   /sell all <item>   - Sells all of a specific item (e.g., /sell all ender_pearl)
 *
 * Permission: solidus.command.sell (default: all players)
 *
 * Shulker Box Support:
 * All sell commands also process items inside shulker boxes in the player's
 * inventory. Sellable items inside shulker boxes are sold, and unsellable
 * items remain in the shulker box. The shulker box is updated in place.
 *
 * Item Name Matching:
 * For /sell all <item>, the item name can use spaces or underscores
 * (e.g., both "ender_pearl" and "ender pearl" will match ENDER_PEARL).
 * The matching is case-insensitive and supports partial matches.
 */
public class SellCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ShopManager shopManager) {
        dispatcher.register(Commands.literal("sell")
            .requires(PermissionChecker.require(SolidusPermissions.SELL, 0))
            // /sell gui - Open the sell GUI
            .then(Commands.literal("gui")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    SellGUI.openSellGUI(player, shopManager);
                    return 1;
                })
            )
            // /sell all [item] - Sell all sellable items or a specific item
            .then(Commands.literal("all")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    executeSellAll(player, shopManager, null);
                    return 1;
                })
                .then(Commands.argument("item", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        // Suggest sellable item names from the shop
                        return SharedSuggestionProvider.suggest(
                            shopManager.getSections().values().stream()
                                .flatMap(section -> section.items().stream())
                                .filter(item -> item.sellPrice() > 0)
                                .map(ShopManager.ShopItem::material)
                                .map(m -> m.toLowerCase().replace("_", " ")),
                            builder);
                    })
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String itemName = StringArgumentType.getString(context, "item");
                        executeSellAll(player, shopManager, itemName);
                        return 1;
                    })
                )
            )
        );
    }

    /**
     * Executes the /sell all command.
     *
     * If itemName is null, sells ALL sellable items in the player's inventory
     * (including items inside shulker boxes).
     *
     * If itemName is specified, sells all of that specific item type
     * (including from inside shulker boxes).
     *
     * @param player    The selling player
     * @param shopManager The shop manager for price lookups
     * @param itemName  The item name to sell (null = all sellable items)
     */
    private static void executeSellAll(ServerPlayer player, ShopManager shopManager, String itemName) {
        // Normalize the item name (spaces to underscores, uppercase)
        String targetMaterial = itemName != null
            ? itemName.toUpperCase().replace(" ", "_")
            : null;

        // Validate the target item exists in the shop (if specified)
        if (targetMaterial != null) {
            ShopManager.ShopItem shopItem = shopManager.findItem(targetMaterial);
            if (shopItem == null) {
                // Try partial matching
                shopItem = findItemByPartialName(shopManager, targetMaterial);
                if (shopItem == null) {
                    player.sendSystemMessage(TextUtil.error(
                        "Item '" + itemName + "' not found in the shop."));
                    return;
                }
                targetMaterial = shopItem.material();
            }
            if (shopItem.sellPrice() <= 0) {
                player.sendSystemMessage(TextUtil.error(
                    "Item '" + itemName + "' cannot be sold."));
                return;
            }
        }

        double totalEarnings = 0.0;
        int totalItemsSold = 0;
        List<String> soldItems = new ArrayList<>();
        // Audit 2.1.3: exact snapshot of every consumed stack (regular items,
        // shulker contents, sold shulker boxes, offhand). If the async credit
        // fails (payout cap / DB error), these are restored instead of being
        // silently destroyed (the old "Items lost" path).
        List<net.minecraft.world.item.ItemStack> consumedStacks = new ArrayList<>();

        // Transaction hook veto (Solidus 2.1.0+): MUST run before the scan
        // loop below - that loop removes items from the inventory as it goes,
        // so a denial after it starts would strand already-sold items.
        SolidusTransactionHook.Decision hookDecision = EconomyHooks.allow(hook ->
            hook.allowShopSell(player.getUUID(), player.getName().getString()));
        if (!hookDecision.allowed()) {
            player.sendSystemMessage(TextUtil.error(hookDecision.reason()));
            return;
        }

        // Process regular inventory items
        // Minecraft inventory layout: slots 0-35 = main + hotbar, 36-39 = armor, 40 = offhand
        // Skip armor slots (36-39) to prevent accidental armor sales.
        // Audit 2.1.3: explicit whitelist (0-35 main + 40 offhand) instead of
        // blacklisting armor - future slot renumbering can never make new
        // equipment slots silently sellable at base material price.
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (i > 35 && i != 40) continue;

            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;

            // Check if this is a shulker box
            if (SellScreenHandler.isShulkerBox(stack)) {
                // Process shulker box contents
                ShulkerSellResult result = sellFromShulkerBox(
                    player, stack, shopManager, targetMaterial);
                totalEarnings += result.earnings;
                totalItemsSold += result.itemsSold;
                // Snapshot the consumed contents for restore (audit 2.1.3)
                consumedStacks.addAll(result.soldStacks);

                if (result.itemsSold > 0) {
                    // Update the shulker box with remaining items
                    if (result.allItemsSold) {
                        // All items in the shulker were sold. Decide the fate of the box itself.
                        // Check if the shulker box itself is sellable too
                        String shulkerMaterial = getMaterialName(stack);
                        ShopManager.ShopItem shulkerShopItem = shopManager.findItem(shulkerMaterial);
                        boolean sellBoxToo = targetMaterial == null
                            && shulkerShopItem != null && shulkerShopItem.sellPrice() > 0;

                        if (sellBoxToo) {
                            // Sell the emptied shulker box too (full /sell all run)
                            totalEarnings += shulkerShopItem.sellPrice();
                            totalItemsSold += 1;
                            // The emptied box is consumed - snapshot it for restore.
                            consumedStacks.add(result.updatedShulkerBox.copy());
                            player.getInventory().setItem(i, ItemStack.EMPTY);
                        } else {
                            // SECURITY FIX (dupe prevention): previously this branch left the
                            // ORIGINAL stack untouched, meaning the player was paid for the
                            // contents while keeping a shulker box still holding ALL of its
                            // items - an infinite money duplication loop via /sell all <item>
                            // (or /sell all with an unlisted shulker type). Always write back
                            // the emptied box so the sold contents are really gone.
                            player.getInventory().setItem(i, result.updatedShulkerBox);
                        }
                    } else {
                        // Some items remain - update the shulker box in place
                        player.getInventory().setItem(i, result.updatedShulkerBox);
                    }
                }
                continue;
            }

            // Regular item - check if it matches the target
            String material = getMaterialName(stack);
            if (targetMaterial != null && !material.equalsIgnoreCase(targetMaterial)) {
                continue; // Not the target item
            }

            // Check if sellable
            ShopManager.ShopItem shopItem = shopManager.findItem(material);
            if (shopItem == null || shopItem.sellPrice() <= 0) {
                continue; // Not sellable
            }

            // Sell the entire stack
            double value = CurrencyUtil.round(shopItem.sellPrice() * stack.getCount());
            totalEarnings += value;
            totalItemsSold += stack.getCount();
            soldItems.add(stack.getCount() + "x " + material);

            // Remove the item from inventory (snapshot for restore)
            consumedStacks.add(stack.copy());
            player.getInventory().setItem(i, ItemStack.EMPTY);
        }

        // Also check offhand slot for /sell all and /sell all <item>
        // Previously, offhand was only checked when selling ALL items (targetMaterial == null),
        // meaning /sell all ender_pearl would miss ender pearls in the offhand slot.
        {
            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty() && !SellScreenHandler.isShulkerBox(offhand)) {
                String material = getMaterialName(offhand);
                boolean matchesTarget = targetMaterial == null || material.equalsIgnoreCase(targetMaterial);
                if (matchesTarget) {
                    ShopManager.ShopItem shopItem = shopManager.findItem(material);
                    if (shopItem != null && shopItem.sellPrice() > 0) {
                        double value = CurrencyUtil.round(shopItem.sellPrice() * offhand.getCount());
                        totalEarnings += value;
                        totalItemsSold += offhand.getCount();
                        if (targetMaterial != null) {
                            soldItems.add(offhand.getCount() + "x " + material + " (offhand)");
                        }
                        consumedStacks.add(offhand.copy());
                        player.getInventory().setItem(40, ItemStack.EMPTY);
                    }
                }
            }
            // Note: We don't sell armor to prevent accidental sales
        }

        // Apply earnings
        if (totalEarnings > 0) {
            shopManager.processSellAllEarnings(player, totalEarnings, totalItemsSold, consumedStacks);
        } else if (targetMaterial != null) {
            player.sendSystemMessage(TextUtil.error(
                "You don't have any sellable '" + itemName + "' in your inventory."));
        } else {
            player.sendSystemMessage(TextUtil.styled(
                "No sellable items found in your inventory.", ChatFormatting.GRAY));
        }
    }

    /**
     * Sells items from inside a shulker box.
     *
     * @param player         The selling player
     * @param shulkerStack   The shulker box ItemStack
     * @param shopManager    The shop manager
     * @param targetMaterial The target material (null = all sellable)
     * @return ShulkerSellResult with earnings, count, and updated shulker box
     */
    private static ShulkerSellResult sellFromShulkerBox(
            ServerPlayer player, ItemStack shulkerStack,
            ShopManager shopManager, String targetMaterial) {

        ItemContainerContents contents = shulkerStack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return new ShulkerSellResult(0, 0, false, shulkerStack, List.of());
        }

        // Read items from the shulker box into a mutable list
        net.minecraft.core.NonNullList<ItemStack> shulkerItems =
            net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(shulkerItems);

        double earnings = 0.0;
        int itemsSold = 0;
        boolean allItemsSold = true;
        List<ItemStack> soldStacks = new ArrayList<>();

        for (int i = 0; i < shulkerItems.size(); i++) {
            ItemStack item = shulkerItems.get(i);
            if (item.isEmpty()) continue;

            String material = getMaterialName(item);

            // Filter by target material if specified
            if (targetMaterial != null && !material.equalsIgnoreCase(targetMaterial)) {
                allItemsSold = false;
                continue;
            }

            // Check if sellable
            ShopManager.ShopItem shopItem = shopManager.findItem(material);
            if (shopItem == null || shopItem.sellPrice() <= 0) {
                allItemsSold = false;
                continue;
            }

            // Sell the entire stack
            double value = CurrencyUtil.round(shopItem.sellPrice() * item.getCount());
            earnings += value;
            itemsSold += item.getCount();
            soldStacks.add(item.copy()); // snapshot for restore (audit 2.1.3)
            shulkerItems.set(i, ItemStack.EMPTY); // Remove sold item
        }

        // Check if there are any remaining items
        boolean hasRemainingItems = false;
        for (ItemStack item : shulkerItems) {
            if (!item.isEmpty()) {
                hasRemainingItems = true;
                break;
            }
        }

        if (!hasRemainingItems) {
            allItemsSold = true;
        }

        // Create updated shulker box
        ItemStack updatedShulker = shulkerStack.copy();
        if (itemsSold > 0) {
            updatedShulker.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(shulkerItems));
        }

        return new ShulkerSellResult(earnings, itemsSold, allItemsSold, updatedShulker, soldStacks);
    }

    /**
     * Result of selling items from a shulker box.
     */
    private record ShulkerSellResult(
        double earnings,
        int itemsSold,
        boolean allItemsSold,
        ItemStack updatedShulkerBox,
        List<ItemStack> soldStacks
    ) {}

    /**
     * Extracts the registry path name from an ItemStack for reliable
     * material matching.
     */
    private static String getMaterialName(ItemStack stack) {
        return TextUtil.getMaterialName(stack);
    }

    /**
     * Finds a shop item by partial name matching.
     * Supports partial matches and space-to-underscore conversion.
     */
    private static ShopManager.ShopItem findItemByPartialName(
            ShopManager shopManager, String searchName) {
        String normalized = searchName.toUpperCase().replace(" ", "_");

        // First try exact match
        for (Map.Entry<String, ShopManager.ShopSection> sectionEntry : shopManager.getSections().entrySet()) {
            for (ShopManager.ShopItem item : sectionEntry.getValue().items()) {
                if (item.material().equalsIgnoreCase(normalized)) {
                    return item;
                }
            }
        }

        // Then try partial match (contains) - only one-direction: material contains search term
        // The reverse direction (normalized contains material) is too broad and matches unrelated items
        for (Map.Entry<String, ShopManager.ShopSection> sectionEntry : shopManager.getSections().entrySet()) {
            for (ShopManager.ShopItem item : sectionEntry.getValue().items()) {
                if (item.material().toUpperCase().contains(normalized)) {
                    return item;
                }
            }
        }

        return null;
    }
}
