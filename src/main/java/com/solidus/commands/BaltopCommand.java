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

/**
 * /baltop command - Server leaderboard displaying the wealthiest players.
 *
 * Usage: /baltop [page]
 * Permission: solidus.command.baltop (default: all players)
 *
 * Displays 10 players per page, ordered by balance. Ranks are global and
 * continue across pages (page 2 starts at #11), so the bottom of the
 * credit ladder is reachable instead of hidden behind a fixed top-10.
 *
 * Performance: pagination is pushed down to SQLite (COUNT(*) for the page
 * footer + LIMIT/OFFSET on the idx_balance_rank index for the rows), so
 * opening page 50 costs the same as page 1 no matter how large the economy
 * grows. All text uses Component.literal().withStyle() - NO legacy
 * formatting codes.
 */
public class BaltopCommand {

    private static final int PAGE_SIZE = 10;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BalanceManager balanceManager) {
        dispatcher.register(Commands.literal("baltop")
            .requires(PermissionChecker.require(SolidusPermissions.BALTOP, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                executeBaltop(player, balanceManager, 1);
                return 1;
            })
            .then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    int page = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page");
                    executeBaltop(player, balanceManager, page);
                    return 1;
                })
            )
        );
    }

    private static void executeBaltop(ServerPlayer player, BalanceManager balanceManager, int page) {
        int offset = (page - 1) * PAGE_SIZE;

        // Cheap COUNT(*) for the footer, then only the requested page via SQL
        // LIMIT/OFFSET - no leaderboard scan beyond the returned window.
        balanceManager.countBalanceEntries().thenAccept(totalCount ->
            balanceManager.getTopBalances(PAGE_SIZE, offset).thenAccept(entries -> {
                player.level().getServer().execute(() -> {
                    // Header
                    player.sendSystemMessage(TextUtil.styledBold(
                        "======= Solidus Leaderboard =======", ChatFormatting.GOLD));

                    if (totalCount == 0) {
                        player.sendSystemMessage(TextUtil.styled(
                            "No players found in the economy yet.", ChatFormatting.GRAY));
                        player.sendSystemMessage(TextUtil.styledBold(
                            "===================================", ChatFormatting.GOLD));
                        return;
                    }

                    if (entries.isEmpty()) {
                        player.sendSystemMessage(TextUtil.styled(
                            "No entries on this page.", ChatFormatting.GRAY));
                        player.sendSystemMessage(TextUtil.styledBold(
                            "===================================", ChatFormatting.GOLD));
                        return;
                    }

                    // Entries (ranks continue across pages: page 2 starts at #11)
                    for (SQLiteStorage.BalanceEntry entry : entries) {
                        MutableComponent rankComponent = TextUtil.styled(
                            "#" + entry.rank() + " ", ChatFormatting.YELLOW);
                        Component nameComponent = TextUtil.styledBold(
                            entry.playerName() + " ", ChatFormatting.WHITE);
                        Component balanceComponent = TextUtil.currency(
                            CurrencyUtil.format(entry.balance()));

                        player.sendSystemMessage(rankComponent.append(nameComponent).append(balanceComponent));
                    }

                    // Footer with page info
                    int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / PAGE_SIZE));
                    if (page < totalPages) {
                        player.sendSystemMessage(
                            TextUtil.styled("Page " + page + "/" + totalPages + " - ",
                                ChatFormatting.GRAY)
                                .append(TextUtil.styled("/baltop " + (page + 1), ChatFormatting.AQUA))
                        );
                    } else {
                        player.sendSystemMessage(
                            TextUtil.styled("Page " + page + "/" + totalPages + " (last page)",
                                ChatFormatting.GRAY)
                        );
                    }

                    player.sendSystemMessage(TextUtil.styledBold(
                        "===================================", ChatFormatting.GOLD));
                });
            }));
    }
}
