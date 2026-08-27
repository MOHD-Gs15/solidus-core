package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.economy.BalanceManager;
import com.solidus.util.TextUtil;
import com.solidus.util.CurrencyUtil;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * /balance command - Displays the player's current currency balance.
 *
 * Usage: /balance or /bal
 * Permission: solidus.command.balance (default: all players)
 *
 * All text uses Component.literal().withStyle() - NO legacy formatting codes.
 */
public class BalanceCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BalanceManager balanceManager) {
        dispatcher.register(Commands.literal("balance")
            .requires(PermissionChecker.require(SolidusPermissions.BALANCE, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                executeBalance(player, balanceManager);
                return 1;
            })
        );

        // Alias: /bal
        dispatcher.register(Commands.literal("bal")
            .requires(PermissionChecker.require(SolidusPermissions.BALANCE, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                executeBalance(player, balanceManager);
                return 1;
            })
        );
    }

    private static void executeBalance(ServerPlayer player, BalanceManager balanceManager) {
        balanceManager.getBalance(player).thenAccept(balance -> {
            // Schedule notification on the server thread to avoid thread-safety issues
            player.level().getServer().execute(() -> {
                player.sendSystemMessage(
                    TextUtil.styledBold("Solidus Balance: ", net.minecraft.ChatFormatting.DARK_AQUA)
                        .append(TextUtil.currency(CurrencyUtil.format(balance)))
                );
            });
        });
    }
}
