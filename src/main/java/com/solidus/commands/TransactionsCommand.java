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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /transactions command - View recent financial transaction history.
 *
 * Usage: /transactions [page]
 * Permission: solidus.command.transactions (default: all players)
 *
 * Displays 10 transactions per page for the player, showing type,
 * amount, counterpart, item details, and timestamp.
 *
 * Export (2.1.0): /transactions export [days] writes the caller's own
 * history to a CSV file under <server>/solidus/exports/ (default 7 days);
 * /transactions exportall [days] (OP 2+) exports every player's ledger.
 * The ledger is capped at TransactionLog.MAX_EXPORT_ROWS per export.
 *
 * Performance: pagination is pushed down to SQLite (COUNT(*) for the page
 * footer + LIMIT/OFFSET for the rows themselves) so opening page 50 costs
 * the same as page 1 no matter how large the ledger grows.
 * All text uses Component.literal().withStyle() - NO legacy formatting codes.
 */
public class TransactionsCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionsCommand.class);

    private static final int PAGE_SIZE = 10;
    private static final int DEFAULT_EXPORT_DAYS = 7;
    private static final DateTimeFormatter EXPORT_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

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
            // /transactions export [days] - own history as CSV (all players)
            .then(Commands.literal("export")
                .requires(PermissionChecker.require(SolidusPermissions.TRANSACTIONS_EXPORT, 0))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    executeExport(player, economyEngine, DEFAULT_EXPORT_DAYS, false);
                    return 1;
                })
                .then(Commands.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 365))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        int days = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "days");
                        executeExport(player, economyEngine, days, false);
                        return 1;
                    })
                )
            )
            // /transactions exportall [days] - full ledger as CSV (OP 2+)
            .then(Commands.literal("exportall")
                .requires(PermissionChecker.require(SolidusPermissions.TRANSACTIONS_EXPORT_ALL, 0))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    executeExport(player, economyEngine, DEFAULT_EXPORT_DAYS, true);
                    return 1;
                })
                .then(Commands.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 365))
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        int days = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "days");
                        executeExport(player, economyEngine, days, true);
                        return 1;
                    })
                )
            )
        );
    }

    private static void executeTransactions(ServerPlayer player, EconomyEngine economyEngine, int page) {
        TransactionLog transactionLog = economyEngine.getTransactionLog();
        int offset = (page - 1) * PAGE_SIZE;

        // Cheap COUNT(*) for the footer, then only the requested page via SQL
        // LIMIT/OFFSET - the whole history is never loaded into memory.
        transactionLog.countTransactions(player.getUUID()).thenAccept(totalCount ->
            transactionLog.getTransactions(player.getUUID(), PAGE_SIZE, offset).thenAccept(pageEntries -> {
                player.level().getServer().execute(() -> {
                    // Header
                    player.sendSystemMessage(TextUtil.styledBold(
                        "======= Transaction History =======", ChatFormatting.GOLD));

                    if (totalCount == 0) {
                        player.sendSystemMessage(TextUtil.styled(
                            "No transactions recorded yet.", ChatFormatting.GRAY));
                        player.sendSystemMessage(TextUtil.styledBold(
                            "===================================", ChatFormatting.GOLD));
                        return;
                    }

                    if (pageEntries.isEmpty()) {
                        player.sendSystemMessage(TextUtil.styled(
                            "No transactions on this page.", ChatFormatting.GRAY));
                        player.sendSystemMessage(TextUtil.styledBold(
                            "===================================", ChatFormatting.GOLD));
                        return;
                    }

                    for (TransactionLog.TransactionEntry entry : pageEntries) {
                        Component message = formatTransactionEntry(entry);
                        player.sendSystemMessage(message);
                    }

                    // Footer with page info
                    int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / PAGE_SIZE));
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
            }));
    }

    private static void executeExport(ServerPlayer player, EconomyEngine economyEngine, int days, boolean allPlayers) {
        TransactionLog transactionLog = economyEngine.getTransactionLog();
        long sinceMs = System.currentTimeMillis() - Duration.ofDays(days).toMillis();

        CompletableFuture<List<TransactionLog.TransactionEntry>> fetch = allPlayers
            ? transactionLog.getAllTransactionsSince(sinceMs)
            : transactionLog.getTransactionsSince(player.getUUID(), sinceMs);

        // File IO runs on the common pool (never the DB executor, never the
        // server thread); only the final message hops back via server.execute.
        fetch.thenAcceptAsync(entries -> {
            if (entries.isEmpty()) {
                player.level().getServer().execute(() -> player.sendSystemMessage(
                    TextUtil.styled("No transactions in the last " + days
                        + " day(s) - nothing to export.", ChatFormatting.GRAY)));
                return;
            }
            try {
                Path dir = resolveExportDir();
                String baseName = "transactions_export_" + (allPlayers ? "all_" : "")
                    + EXPORT_STAMP.format(java.time.Instant.now().atZone(ZoneOffset.UTC));
                Path file = nextAvailableFile(dir, baseName);
                TransactionLog.writeCsvFile(entries, file);

                long sizeKb = Math.max(1, Files.size(file) / 1024);
                String scope = allPlayers ? "(all players) " : "";
                player.level().getServer().execute(() -> player.sendSystemMessage(
                    TextUtil.styled("Exported " + entries.size() + " transactions "
                        + scope + "from the last " + days + " day(s) to ", ChatFormatting.GREEN)
                        .append(TextUtil.styled("solidus/exports/" + file.getFileName(), ChatFormatting.AQUA))
                        .append(TextUtil.styled(" (" + sizeKb + " KB)", ChatFormatting.GRAY))));
            } catch (Exception e) {
                LOGGER.error("CSV export failed for {}: allPlayers={}",
                    player.getName().getString(), allPlayers, e);
                player.level().getServer().execute(() -> player.sendSystemMessage(
                    TextUtil.error("Export failed - check the server log for details.")));
            }
        });
    }

    /**
     * Export directory: <game dir>/solidus/exports (game dir resolved the
     * same way EconomyEngine resolves it - via FabricLoader's config dir).
     * Falls back to a relative path if the loader is unavailable.
     */
    private static Path resolveExportDir() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().getParent().resolve("solidus").resolve("exports");
        } catch (Throwable t) {
            return java.nio.file.Paths.get("solidus", "exports");
        }
    }

    /** Returns <base>.csv, or <base>_2.csv, <base>_3.csv, ... if taken. */
    private static Path nextAvailableFile(Path dir, String baseName) {
        Path file = dir.resolve(baseName + ".csv");
        int n = 2;
        while (Files.exists(file) && n < 100) {
            file = dir.resolve(baseName + "_" + n + ".csv");
            n++;
        }
        return file;
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
