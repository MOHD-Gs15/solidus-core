package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.TransactionLog;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * /transactions command - View recent financial transaction history.
 *
 * Usage: /transactions [page]
 * Permission: solidus.command.transactions (default: all players)
 *
 * Displays the last 10 transactions for the player, showing type,
 * amount, counterpart, item details, and timestamp.
 * All text uses Component.literal().withStyle() - NO legacy formatting codes.
 */
public class TransactionsCommand {

    private static final int PAGE_SIZE = 10;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyEngine economyEngine) {
        dispatcher.register(Commands.literal("transactions")
            .requires(PermissionChecker.require(SolidusPermissions.TRANSACTIONS, 0))
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                executeTransactions(player, economyEngine, 1);
                return 1;
            })
            .then(Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    int page = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page");
                    executeTransactions(player, economyEngine, page);
                    return 1;
                })
            )
        );
    }

    private static void executeTransactions(ServerPlayer player, EconomyEngine economyEngine, int page) {
        TransactionLog transactionLog = economyEngine.getTransactionLog();
        int offset = (page - 1) * PAGE_SIZE;

        transactionLog.getTransactions(player.getUUID(), PAGE_SIZE + offset).thenAccept(allEntries -> {
            player.level().getServer().execute(() -> {
                // Header
                player.sendSystemMessage(TextUtil.styledBold(
                    "======= Transaction History =======", ChatFormatting.GOLD));

                if (allEntries.isEmpty()) {
                    player.sendSystemMessage(TextUtil.styled(
                        "No transactions recorded yet.", ChatFormatting.GRAY));
                    return;
                }

                // Paginate
                int fromIndex = Math.min(offset, allEntries.size());
                int toIndex = Math.min(offset + PAGE_SIZE, allEntries.size());
                List<TransactionLog.TransactionEntry> pageEntries = allEntries.subList(fromIndex, toIndex);

                if (pageEntries.isEmpty()) {
                    player.sendSystemMessage(TextUtil.styled(
                        "No transactions on this page.", ChatFormatting.GRAY));
                    return;
                }

                for (TransactionLog.TransactionEntry entry : pageEntries) {
                    Component message = formatTransactionEntry(entry);
                    player.sendSystemMessage(message);
                }

                // Footer with page info
                int totalPages = (int) Math.ceil((double) allEntries.size() / PAGE_SIZE);
                if (page < totalPages) {
                    player.sendSystemMessage(
                        TextUtil.styled("Page " + page + "/" + totalPages + " - ",
                            ChatFormatting.GRAY)
                            .append(TextUtil.styled("/transactions " + (page + 1), ChatFormatting.AQUA))
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
        });
    }

    private static Component formatTransactionEntry(TransactionLog.TransactionEntry entry) {
        // Type indicator
        String typeIcon = switch (entry.type()) {
            case SHOP_BUY -> "BUY";
            case SHOP_SELL -> "SELL";
            case AUCTION_LIST -> "LIST";
            case AUCTION_SOLD -> "SOLD";
            case AUCTION_BOUGHT -> "WON";
            case AUCTION_EXPIRED -> "EXPIRE";
            case PAY_SEND -> "PAY-";
            case PAY_RECEIVE -> "PAY+";
            case DEATH_PENALTY -> "DEATH-";
            case DEATH_REWARD -> "DEATH+";
        };

        ChatFormatting typeColor = switch (entry.type()) {
            case SHOP_BUY, PAY_SEND, AUCTION_LIST, DEATH_PENALTY -> ChatFormatting.RED;
            case SHOP_SELL, PAY_RECEIVE, AUCTION_SOLD, DEATH_REWARD -> ChatFormatting.GREEN;
            case AUCTION_BOUGHT -> ChatFormatting.AQUA;
            case AUCTION_EXPIRED -> ChatFormatting.YELLOW;
        };

        // Time ago
        long agoMs = System.currentTimeMillis() - entry.timestamp();
        String timeAgo = formatTimeAgo(agoMs);

        // Build message: [TYPE] Amount - Description (time ago)
        MutableComponent msg = TextUtil.styledBold("[" + typeIcon + "] ", typeColor);

        // Amount
        if (entry.amount() > 0) {
            msg = msg.append(TextUtil.currency(CurrencyUtil.format(entry.amount())))
                .append(TextUtil.styled(" - ", ChatFormatting.GRAY));
        }

        // Target player
        if (entry.targetName() != null && !entry.targetName().isEmpty()) {
            msg = msg.append(TextUtil.styled(entry.targetName() + " ", ChatFormatting.WHITE));
        }

        // Item info
        if (entry.itemMaterial() != null && entry.itemQuantity() > 0) {
            msg = msg.append(TextUtil.styled(
                entry.itemQuantity() + "x " + entry.itemMaterial() + " ", ChatFormatting.AQUA));
        }

        // Time
        msg = msg.append(TextUtil.styled("(" + timeAgo + ")", ChatFormatting.DARK_GRAY));

        return msg;
    }

    private static String formatTimeAgo(long agoMs) {
        long seconds = agoMs / 1000;
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }
}
