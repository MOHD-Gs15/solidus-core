package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.auction.AuctionEntry;
import com.solidus.auction.AuctionManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.solidus.util.CurrencyUtil;

import java.util.List;
import java.util.UUID;

/**
 * /ah command - Auction House commands.
 *
 * Usage:
 *   /ah                    - View global auction listings
 *   /ah sell <price> [startbid] - List the held item; optional opening bid enables bidding
 *   /ah bid <uuid> <amount> - Bid on a bidding-enabled listing (or right-click it in the GUI)
 *   /ah collect            - Collect expired auction items AND won auction items
 *   /ah cancel <uuid>      - Cancel your own active listing
 *   /ah sort <newest|price_low|price_high|material> - View sorted listings
 *   /ah search <term>      - Free-text search across active listings
 *
 * Permissions:
 *   solidus.command.auction        - View auction house (default: all players)
 *   solidus.command.auction.sell   - List items (default: all players)
 *   solidus.command.auction.bid    - Bid on listings (default: all players)
 *   solidus.command.auction.collect - Collect items (default: all players)
 *   solidus.command.auction.cancel  - Cancel listings (default: all players)
 *   solidus.command.auction.sort   - Sort listings (default: all players)
 *
 * The Auction House excludes structural progression items like Armor Trims
 * from the virtual server shop, forcing them into player-driven commerce
 * to incentivize real survival exploration.
 */
public class AuctionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, AuctionManager auctionManager) {
        // /ah - View listings
        dispatcher.register(Commands.literal("ah")
            .requires(PermissionChecker.require(SolidusPermissions.AUCTION_VIEW, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                auctionManager.openAuction(player);
                return 1;
            })
            // /ah sell <price> - List held item (buy-now only)
            .then(Commands.literal("sell")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_SELL, 0))
                .then(Commands.argument("price", DoubleArgumentType.doubleArg(1.0))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        double price = DoubleArgumentType.getDouble(context, "price");
                        auctionManager.listItem(player, price);
                        return 1;
                    })
                    // /ah sell <price> <startbid> - List held item WITH bidding
                    .then(Commands.argument("startbid", DoubleArgumentType.doubleArg(1.0))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            double price = DoubleArgumentType.getDouble(context, "price");
                            double startBid = DoubleArgumentType.getDouble(context, "startbid");
                            auctionManager.listItem(player, price, startBid);
                            return 1;
                        })
                    )
                )
            )
            // /ah bid <listing_id> <amount> - Bid on a bidding-enabled listing
            .then(Commands.literal("bid")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_BID, 0))
                .then(Commands.argument("listing_id", UuidArgument.uuid())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1.0))
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            UUID listingId = UuidArgument.getUuid(context, "listing_id");
                            double amount = DoubleArgumentType.getDouble(context, "amount");
                            auctionManager.placeBid(player, listingId, amount);
                            return 1;
                        })
                    )
                )
            )
            // /ah collect - Collect expired auction items
            .then(Commands.literal("collect")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_COLLECT, 0))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    auctionManager.collectExpiredItems(player);
                    return 1;
                })
            )
            // /ah cancel <uuid> - Cancel own active listing
            .then(Commands.literal("cancel")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_CANCEL, 0))
                .then(Commands.argument("listing_id", UuidArgument.uuid())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        UUID listingId = UuidArgument.getUuid(context, "listing_id");
                        auctionManager.cancelListing(player, listingId);
                        return 1;
                    })
                )
            )
            // /ah sort <order> - View sorted listings
            .then(Commands.literal("sort")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_SORT, 0))
                .then(Commands.literal("newest")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        com.solidus.auction.AuctionGUI.openAuctionSorted(player, auctionManager, AuctionManager.SortOrder.NEWEST);
                        return 1;
                    })
                )
                .then(Commands.literal("price_low")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        com.solidus.auction.AuctionGUI.openAuctionSorted(player, auctionManager, AuctionManager.SortOrder.PRICE_LOW);
                        return 1;
                    })
                )
                .then(Commands.literal("price_high")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        com.solidus.auction.AuctionGUI.openAuctionSorted(player, auctionManager, AuctionManager.SortOrder.PRICE_HIGH);
                        return 1;
                    })
                )
                .then(Commands.literal("material")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        com.solidus.auction.AuctionGUI.openAuctionSorted(player, auctionManager, AuctionManager.SortOrder.MATERIAL);
                        return 1;
                    })
                )
            )
            // /ah search <term> - Free-text search across active listings
            .then(Commands.literal("search")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_VIEW, 0))
                .then(Commands.argument("term", StringArgumentType.greedyString())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String term = StringArgumentType.getString(context, "term");
                        executeSearch(player, auctionManager, term);
                        return 1;
                    })
                )
            )
        );
    }

    private static void executeSearch(ServerPlayer player, AuctionManager auctionManager, String term) {
        auctionManager.searchListings(term).thenAccept(entries ->
            player.level().getServer().execute(() -> sendSearchResults(player, term, entries)));
    }

    private static void sendSearchResults(ServerPlayer player, String term, List<AuctionEntry> entries) {
        if (entries.isEmpty()) {
            player.sendSystemMessage(Component.literal("\u00a77No active listings matching '")
                .append(Component.literal(term).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("'.")));
            return;
        }
        player.sendSystemMessage(Component.literal("\u00a76[AH] ")
            .append(Component.literal("Active listings matching '").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(term).withStyle(ChatFormatting.AQUA))
            .append(Component.literal("' (cheapest first):").withStyle(ChatFormatting.YELLOW)));
        long now = System.currentTimeMillis();
        for (AuctionEntry entry : entries) {
            long remainingMs = Math.max(0L, entry.expireTimestamp() - now);
            player.sendSystemMessage(Component.literal(String.format("\u00a77  %dx \u00a7f%s%s \u00a7e- \u00a7b%s \u00a77- \u00a7a%s \u00a77- ends in %s",
                entry.quantity(),
                entry.materialName(),
                entry.itemNbt() != null && !entry.itemNbt().isBlank() ? " \u00a7d[nbt]" : "",
                CurrencyUtil.format(entry.price()),
                entry.sellerName(),
                humanizeDuration(remainingMs))));
        }
        if (entries.size() >= AuctionManager.MAX_SEARCH_RESULTS) {
            player.sendSystemMessage(Component.literal("\u00a77(showing first " + AuctionManager.MAX_SEARCH_RESULTS
                + " results - refine your search to see more)"));
        }
    }

    private static String humanizeDuration(long ms) {
        long minutes = ms / 60000L;
        if (minutes < 60L) return minutes + "m";
        long hours = minutes / 60L;
        if (hours < 48L) return hours + "h " + (minutes % 60L) + "m";
        return (hours / 24L) + "d " + (hours % 24L) + "h";
    }
}
