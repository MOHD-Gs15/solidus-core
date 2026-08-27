package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.shop.ShopManager;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /shop command - Opens the virtual server shop GUI or searches items.
 *
 * Usage:
 *   /shop              - Opens the full shop GUI
 *   /shop search <query> - Search shop items by name
 *
 * Permissions:
 *   solidus.command.shop        - Open shop GUI (default: all players)
 *   solidus.command.shop.search - Search shop items (default: all players)
 *
 * The search sub-command provides a text-based item lookup when the player
 * knows the item name but doesn't want to browse through multiple pages.
 * Results show item name, buy/sell prices, and section location.
 */
public class ShopCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ShopManager shopManager) {
        dispatcher.register(Commands.literal("shop")
            .requires(PermissionChecker.require(SolidusPermissions.SHOP, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                shopManager.openShop(player);
                return 1;
            })
            // /shop search <query> - Search items by name
            .then(Commands.literal("search")
                .requires(PermissionChecker.require(SolidusPermissions.SHOP_SEARCH, 0))
                .then(Commands.argument("query", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        // Suggest material names from the shop
                        return SharedSuggestionProvider.suggest(
                            shopManager.getSections().values().stream()
                                .flatMap(section -> section.items().stream())
                                .map(ShopManager.ShopItem::material),
                            builder);
                    })
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String query = StringArgumentType.getString(context, "query");
                        executeSearch(player, shopManager, query);
                        return 1;
                    })
                )
            )
            // /shop reload - Hot-reload shop.json without restarting the server (admin only)
            .then(Commands.literal("reload")
                .requires(PermissionChecker.require(SolidusPermissions.SHOP_RELOAD, 2))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    source.sendSuccess(() -> TextUtil.styled(
                        "Reloading shop configuration...", ChatFormatting.YELLOW),
                        true);
                    shopManager.loadConfiguration();
                    boolean ok = shopManager.isLoaded();
                    if (ok) {
                        int sections = shopManager.getSections().size();
                        int items = shopManager.getSections().values().stream()
                            .mapToInt(s -> s.items().size()).sum();
                        source.sendSuccess(() -> TextUtil.success(
                            "Shop reloaded: " + sections + " sections, " + items + " items."),
                            true);
                    } else {
                        source.sendFailure(TextUtil.error(
                            "Shop reload failed. Check server logs for details."));
                    }
                    return ok ? 1 : 0;
                })
            )
        );
    }

    /**
     * Searches all shop sections for items matching the query string.
     * Matching is case-insensitive and supports partial matches.
     */
    private static void executeSearch(ServerPlayer player, ShopManager shopManager, String query) {
        String lowerQuery = query.toLowerCase();
        List<SearchResult> results = new ArrayList<>();

        for (Map.Entry<String, ShopManager.ShopSection> sectionEntry : shopManager.getSections().entrySet()) {
            String sectionKey = sectionEntry.getKey();
            ShopManager.ShopSection section = sectionEntry.getValue();

            for (ShopManager.ShopItem item : section.items()) {
                if (item.material().toLowerCase().contains(lowerQuery)) {
                    results.add(new SearchResult(sectionKey, section.displayName(), item));
                }
            }
        }

        // Header
        player.sendSystemMessage(TextUtil.styledBold(
            "======= Shop Search: " + query + " =======", ChatFormatting.GOLD));

        if (results.isEmpty()) {
            player.sendSystemMessage(TextUtil.styled(
                "No items found matching '" + query + "'.", ChatFormatting.GRAY));
            player.sendSystemMessage(TextUtil.styled(
                "Tip: Use partial names like 'diamond' or 'iron'", ChatFormatting.DARK_GRAY));
        } else {
            player.sendSystemMessage(TextUtil.styled(
                "Found " + results.size() + " item(s):", ChatFormatting.YELLOW));

            for (SearchResult result : results) {
                ShopManager.ShopItem item = result.item();

                MutableComponent line = TextUtil.styledBold("  " + item.material() + " ", ChatFormatting.WHITE);

                // Buy price
                if (item.buyPrice() > 0) {
                    line = line.append(TextUtil.styled("Buy: ", ChatFormatting.GREEN))
                        .append(TextUtil.currency(CurrencyUtil.format(item.buyPrice())));
                } else {
                    line = line.append(TextUtil.styled("Buy: - ", ChatFormatting.DARK_GRAY));
                }

                // Separator
                line = line.append(TextUtil.styled(" | ", ChatFormatting.DARK_GRAY));

                // Sell price
                if (item.sellPrice() > 0) {
                    line = line.append(TextUtil.styled("Sell: ", ChatFormatting.RED))
                        .append(TextUtil.currency(CurrencyUtil.format(item.sellPrice())));
                } else {
                    line = line.append(TextUtil.styled("Sell: - ", ChatFormatting.DARK_GRAY));
                }

                // Section
                line = line.append(TextUtil.styled(" [" + result.sectionKey() + "]", ChatFormatting.AQUA));

                player.sendSystemMessage(line);
            }
        }

        player.sendSystemMessage(TextUtil.styledBold(
            "===================================", ChatFormatting.GOLD));
    }

    /** Internal search result record */
    private record SearchResult(String sectionKey, Component sectionName, ShopManager.ShopItem item) {}
}
