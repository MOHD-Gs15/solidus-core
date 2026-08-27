package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.auction.AuctionManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * /ah command - Auction House commands.
 *
 * Usage:
 *   /ah                    - View global auction listings
 *   /ah sell <price>       - List the item in main hand for the specified price
 *   /ah collect            - Collect expired auction items
 *   /ah cancel <uuid>      - Cancel your own active listing
 *   /ah sort <newest|price_low|price_high|material> - View sorted listings
 *
 * Permissions:
 *   solidus.command.auction        - View auction house (default: all players)
 *   solidus.command.auction.sell   - List items (default: all players)
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
            // /ah sell <price> - List held item
            .then(Commands.literal("sell")
                .requires(PermissionChecker.require(SolidusPermissions.AUCTION_SELL, 0))
                .then(Commands.argument("price", DoubleArgumentType.doubleArg(1.0))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        double price = DoubleArgumentType.getDouble(context, "price");
                        auctionManager.listItem(player, price);
                        return 1;
                    })
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
        );
    }
}
