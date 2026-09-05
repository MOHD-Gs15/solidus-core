package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.trade.TradeManager;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * /trade command - Direct player-to-player trading with mutual approval.
 *
 * Usage:
 *   /trade <player>  - Request a trade with a nearby player
 *   /trade accept    - Accept a pending incoming trade request
 *   /trade deny      - Refuse a pending incoming trade request
 *   /trade cancel    - Cancel the trade you are currently in
 *
 * Permissions:
 *   solidus.command.trade - All trade actions (default: all players)
 *
 * The trade window shows BOTH sides' offers live (items + money) and only
 * executes when both players press READY - any change to either offer
 * un-readies both. This closes the /pay-then-drop-items scam class.
 */
public class TradeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, TradeManager tradeManager) {
        dispatcher.register(Commands.literal("trade")
            .requires(PermissionChecker.require(SolidusPermissions.TRADE, 0))
            // /trade <player> - request a trade
            .then(Commands.argument("player", EntityArgument.player())
                .executes(context -> {
                    ServerPlayer sender = context.getSource().getPlayerOrException();
                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                    tradeManager.requestTrade(sender, target);
                    return 1;
                })
            )
            // /trade accept
            .then(Commands.literal("accept")
                .executes(context -> {
                    ServerPlayer sender = context.getSource().getPlayerOrException();
                    tradeManager.acceptTrade(sender);
                    return 1;
                })
            )
            // /trade deny
            .then(Commands.literal("deny")
                .executes(context -> {
                    ServerPlayer sender = context.getSource().getPlayerOrException();
                    tradeManager.denyTrade(sender);
                    return 1;
                })
            )
            // /trade cancel
            .then(Commands.literal("cancel")
                .executes(context -> {
                    ServerPlayer sender = context.getSource().getPlayerOrException();
                    tradeManager.cancelMyTrade(sender);
                    return 1;
                })
            )
        );
    }
}
