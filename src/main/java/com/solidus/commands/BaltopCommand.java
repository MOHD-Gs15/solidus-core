package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.SQLiteStorage;
import com.solidus.util.TextUtil;
import com.solidus.util.CurrencyUtil;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /baltop command - Server leaderboard displaying the wealthiest players.
 *
 * Usage: /baltop
 * Permission: solidus.command.baltop (default: all players)
 *
 * Displays the top 10 players by balance in a formatted list.
 * All text uses Component.literal().withStyle() - NO legacy formatting codes.
 */
public class BaltopCommand {

    private static final int TOP_COUNT = 10;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BalanceManager balanceManager) {
        dispatcher.register(Commands.literal("baltop")
            .requires(PermissionChecker.require(SolidusPermissions.BALTOP, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                executeBaltop(player, balanceManager);
                return 1;
            })
        );
    }

    private static void executeBaltop(ServerPlayer player, BalanceManager balanceManager) {
        balanceManager.getTopBalances(TOP_COUNT).thenAccept(entries -> {
            player.level().getServer().execute(() -> {
                // Header
                player.sendSystemMessage(TextUtil.styledBold(
                    "======= Solidus Leaderboard =======", ChatFormatting.GOLD));

                if (entries.isEmpty()) {
                    player.sendSystemMessage(TextUtil.styled(
                        "No players found in the economy yet.", ChatFormatting.GRAY));
                    return;
                }

                // Entries
                for (SQLiteStorage.BalanceEntry entry : entries) {
                    MutableComponent rankComponent = TextUtil.styled(
                        "#" + entry.rank() + " ", ChatFormatting.YELLOW);
                    Component nameComponent = TextUtil.styledBold(
                        entry.playerName() + " ", ChatFormatting.WHITE);
                    Component balanceComponent = TextUtil.currency(
                        CurrencyUtil.format(entry.balance()));

                    player.sendSystemMessage(rankComponent.append(nameComponent).append(balanceComponent));
                }

                // Footer
                player.sendSystemMessage(TextUtil.styledBold(
                    "===================================", ChatFormatting.GOLD));
            });
        });
    }
}
