package com.solidus.commands;

import com.solidus.SolidusMod;
import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.StorageBackend;
import com.solidus.economy.TransactionLog;
import com.solidus.networking.RateLimiter;
import com.solidus.util.TextUtil;
import com.solidus.util.CurrencyUtil;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * /pay command - Safe peer-to-peer balance transfer with offline player support.
 *
 * Usage:
 *   /pay <player> <amount>       - Pay an online player
 *   /pay offline <name> <amount> - Pay an offline player by name
 *
 * Permissions:
 *   solidus.command.pay         - Pay online players (default: all players)
 *   solidus.command.pay.offline - Pay offline players (default: all players)
 *
 * Anti-Exploit Protections:
 * - Negative amount rejection (prevents reverse-transfer exploitation)
 * - Zero amount rejection (prevents spam)
 * - Self-transfer rejection (prevents confusion)
 * - Maximum transaction cap enforcement
 * - Recipient existence validation
 * - Atomic deduct-then-add operation with rollback on failure
 * - Per-sender transfer cooldown via RateLimiter (1 payment/second) - stops
 *   command-macro flooding from polluting the ledger and hammering SQLite
 *
 * Offline Payment Flow:
 * When paying an offline player, the system looks up the player's UUID
 * from the economy database cache. If the player has never been on the
 * server, the payment is rejected. A notification is queued for delivery
 * when the offline player next logs in.
 *
 * All text uses Component.literal().withStyle() - NO legacy formatting codes.
 */
public class PayCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyEngine economyEngine) {
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        dispatcher.register(Commands.literal("pay")
            .requires(PermissionChecker.require(SolidusPermissions.PAY, 0))
            // /pay <online_player> <amount> - Pay an online player
            .then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(CurrencyUtil.MIN_TRANSACTION))
                    .executes(context -> {
                        ServerPlayer sender = context.getSource().getPlayerOrException();
                        ServerPlayer receiver = EntityArgument.getPlayer(context, "player");
                        double amount = DoubleArgumentType.getDouble(context, "amount");
                        executePayOnline(sender, receiver, amount, economyEngine);
                        return 1;
                    })
                )
            )
            // /pay offline <name> <amount> - Pay an offline player by name
            .then(Commands.literal("offline")
                .requires(PermissionChecker.require(SolidusPermissions.PAY_OFFLINE, 0))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        // Suggest known player names from the database cache
                        StorageBackend storage = economyEngine.getStorage();
                        return SharedSuggestionProvider.suggest(
                            storage.getPlayerNameCache().values().stream(), builder);
                    })
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(CurrencyUtil.MIN_TRANSACTION))
                        .executes(context -> {
                            ServerPlayer sender = context.getSource().getPlayerOrException();
                            String targetName = StringArgumentType.getString(context, "name");
                            double amount = DoubleArgumentType.getDouble(context, "amount");
                            executePayOffline(sender, targetName, amount, economyEngine);
                            return 1;
                        })
                    )
                )
            )
        );
    }

    /**
     * Per-sender anti-spam gate for both /pay paths.
     * Consumes a transfer slot only when allowed, so a rejected payment still
     * counts as the sender's attempt for this second. Returns true to proceed.
     */
    private static boolean allowByRateLimit(ServerPlayer sender) {
        RateLimiter rateLimiter = SolidusMod.getRateLimiter();
        if (rateLimiter == null) return true; // defensive: never called before init in production

        if (rateLimiter.allowTransfer(sender.getUUID())) {
            return true;
        }

        long remainingSec = (rateLimiter.getRemainingTransferCooldown(sender.getUUID()) + 999) / 1000;
        sender.sendSystemMessage(TextUtil.error(
            "You are sending payments too quickly. Try again in " + remainingSec + "s."));
        return false;
    }

    /**
     * Executes a payment to an online player.
     */
    private static void executePayOnline(ServerPlayer sender, ServerPlayer receiver,
                                          double amount, EconomyEngine economyEngine) {
        BalanceManager balanceManager = economyEngine.getBalanceManager();
        TransactionLog transactionLog = economyEngine.getTransactionLog();

        // Rate limit: max 1 payment per second per sender (anti-spam gate)
        if (!allowByRateLimit(sender)) return;

        // Pre-validation: reject negative or zero amounts
        if (amount <= 0) {
            sender.sendSystemMessage(TextUtil.error("Amount must be positive!"));
            return;
        }

        // Pre-validation: reject amounts exceeding the maximum transaction cap
        if (amount > CurrencyUtil.MAX_TRANSACTION) {
            sender.sendSystemMessage(TextUtil.error(
                "Amount exceeds maximum transfer limit of " + CurrencyUtil.format(CurrencyUtil.MAX_TRANSACTION)));
            return;
        }

        // Pre-validation: prevent self-transfer
        if (sender.getUUID().equals(receiver.getUUID())) {
            sender.sendSystemMessage(TextUtil.error("You cannot pay yourself!"));
            return;
        }

        // Perform atomic transfer
        balanceManager.transfer(sender, receiver, amount).thenAccept(result -> {
            // Schedule notification on the server thread
            var server = sender.level().getServer();
            if (server == null) return;

            server.execute(() -> {
                if (result.success()) {
                    // Notify sender
                    sender.sendSystemMessage(
                        TextUtil.success("You paid " + receiver.getName().getString() + " ")
                            .append(TextUtil.currency(CurrencyUtil.format(amount)))
                            .append(TextUtil.plain(". "))
                            .append(TextUtil.styled("New balance: ", net.minecraft.ChatFormatting.GRAY))
                            .append(TextUtil.currency(CurrencyUtil.format(result.senderNewBalance())))
                    );

                    // Notify receiver
                    receiver.sendSystemMessage(
                        TextUtil.success("You received " + CurrencyUtil.format(amount) + " from ")
                            .append(TextUtil.styled(sender.getName().getString(), net.minecraft.ChatFormatting.YELLOW))
                            .append(TextUtil.plain(". "))
                            .append(TextUtil.styled("New balance: ", net.minecraft.ChatFormatting.GRAY))
                            .append(TextUtil.currency(CurrencyUtil.format(result.receiverNewBalance())))
                    );

                    // Log transaction for both players
                    transactionLog.log(TransactionLog.Type.PAY_SEND,
                        sender.getUUID(), sender.getName().getString(),
                        receiver.getUUID(), receiver.getName().getString(),
                        amount, null, 0,
                        "Paid " + receiver.getName().getString());

                    transactionLog.log(TransactionLog.Type.PAY_RECEIVE,
                        receiver.getUUID(), receiver.getName().getString(),
                        sender.getUUID(), sender.getName().getString(),
                        amount, null, 0,
                        "Received from " + sender.getName().getString());
                } else {
                    // Transfer failed
                    sender.sendSystemMessage(TextUtil.error(result.message()));
                }
            });
        });
    }

    /**
     * Executes a payment to an offline player by name.
     *
     * The system looks up the player's UUID from the economy database cache.
     * If the player has never joined the server (no cache entry), the payment
     * is rejected. A notification is queued for delivery on next login.
     */
    private static void executePayOffline(ServerPlayer sender, String targetName,
                                           double amount, EconomyEngine economyEngine) {
        BalanceManager balanceManager = economyEngine.getBalanceManager();
        TransactionLog transactionLog = economyEngine.getTransactionLog();
        StorageBackend storage = economyEngine.getStorage();

        // Rate limit: max 1 payment per second per sender (anti-spam gate)
        if (!allowByRateLimit(sender)) return;

        // Pre-validation: reject negative or zero amounts
        if (amount <= 0) {
            sender.sendSystemMessage(TextUtil.error("Amount must be positive!"));
            return;
        }

        // Pre-validation: reject amounts exceeding the maximum transaction cap
        if (amount > CurrencyUtil.MAX_TRANSACTION) {
            sender.sendSystemMessage(TextUtil.error(
                "Amount exceeds maximum transfer limit of " + CurrencyUtil.format(CurrencyUtil.MAX_TRANSACTION)));
            return;
        }

        // Look up target UUID from the name cache.
        // Audit 2.1.3 (two fixes):
        // 1. Exact-case match wins over case-insensitive match - two distinct
        //    accounts can share a case-insensitive name (Mojang renames,
        //    offline-mode proxies), and the old single equalsIgnoreCase pass
        //    routed /pay offline bob to whichever map entry iteration hit
        //    first (effectively arbitrary).
        // 2. The CANONICAL cached name (not the typed string) is used for the
        //    transfer and the ledger. The old code persisted the raw typed
        //    string as the recipient's player_name via the transfer UPSERT -
        //    Unicode case folding (long-s, Kelvin sign, ...) let equalsIgnoreCase
        //    match "ſteve" to "Steve" and then poison the stored name with the
        //    homoglyph variant.
        UUID targetUuid = null;
        String canonicalName = null;
        for (var entry : storage.getPlayerNameCache().entrySet()) {
            if (entry.getValue().equals(targetName)) {
                targetUuid = entry.getKey();
                canonicalName = entry.getValue();
                break;
            }
        }
        if (targetUuid == null) {
            for (var entry : storage.getPlayerNameCache().entrySet()) {
                if (entry.getValue().equalsIgnoreCase(targetName)) {
                    // Only accept the case-insensitive match when it is UNIQUE -
                    // an ambiguous case-insensitive collision must not route money.
                    if (targetUuid != null) {
                        sender.sendSystemMessage(TextUtil.error(
                            "Ambiguous player name (multiple case-insensitive matches: '"
                                + canonicalName + "' / '" + entry.getValue()
                                + "'). Type the exact capitalization."));
                        return;
                    }
                    targetUuid = entry.getKey();
                    canonicalName = entry.getValue();
                }
            }
        }

        if (targetUuid == null) {
            sender.sendSystemMessage(TextUtil.error(
                "Player '" + targetName + "' not found. They must have joined the server at least once."));
            return;
        }
        final String receiverCanonicalName = canonicalName;

        // Prevent self-transfer
        if (sender.getUUID().equals(targetUuid)) {
            sender.sendSystemMessage(TextUtil.error("You cannot pay yourself!"));
            return;
        }

        final UUID receiverUuid = targetUuid;

        // Offline transfer: use atomic transferOffline() which handles
        // deduct-then-add with automatic rollback on failure.
        // Audit 2.1.3: receiverCanonicalName (from the server's own cache) is
        // persisted instead of the typed string - the UPSERT writes this name
        // into the recipient's balance row.
        balanceManager.transferOffline(
            sender.getUUID(), sender.getName().getString(),
            receiverUuid, receiverCanonicalName,
            amount
        ).thenAccept(result -> {
            var server = sender.level().getServer();
            if (server == null) return;

            server.execute(() -> {
                if (!result.success()) {
                    sender.sendSystemMessage(TextUtil.error(result.message()));
                    return;
                }

                // Transfer succeeded - addBalance was already done inside transferOffline
                // We just need the sender's new balance for the notification
                double newSenderBalance = result.senderNewBalance();

                // Notify sender
                sender.sendSystemMessage(
                    TextUtil.success("You paid " + receiverCanonicalName + " (offline) ")
                        .append(TextUtil.currency(CurrencyUtil.format(amount)))
                        .append(TextUtil.plain(". "))
                        .append(TextUtil.styled("New balance: ", net.minecraft.ChatFormatting.GRAY))
                        .append(TextUtil.currency(CurrencyUtil.format(newSenderBalance)))
                );

                // Queue notification for offline player
                transactionLog.queueNotification(receiverUuid,
                    "You received " + CurrencyUtil.format(amount) + " from " +
                        sender.getName().getString() + " while you were offline.",
                    server);

                // Log transactions (canonical receiver name - audit 2.1.3)
                transactionLog.log(TransactionLog.Type.PAY_SEND,
                    sender.getUUID(), sender.getName().getString(),
                    receiverUuid, receiverCanonicalName,
                    amount, null, 0,
                    "Paid " + receiverCanonicalName + " (offline)");

                transactionLog.log(TransactionLog.Type.PAY_RECEIVE,
                    receiverUuid, receiverCanonicalName,
                    sender.getUUID(), sender.getName().getString(),
                    amount, null, 0,
                    "Received from " + sender.getName().getString() + " (offline)");
            });
        });
    }
}
