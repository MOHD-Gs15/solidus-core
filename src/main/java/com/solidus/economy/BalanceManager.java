package com.solidus.economy;

import com.solidus.api.EconomyHooks;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.util.CurrencyUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Balance Manager - High-level API for all economy balance operations.
 *
 * This is the primary interface through which commands, shop transactions,
 * and auction operations interact with the economy. It wraps the low-level
 * SQLiteStorage operations with business logic validation, anti-exploit
 * checks, and player notification.
 *
 * Thread Safety:
 * - All operations return CompletableFuture and execute asynchronously
 *   on the dedicated database worker thread.
 * - The caller (typically on the server tick thread) should use .thenAccept()
 *   or .thenApply() to process results and send player notifications.
 * - Operations that modify balances use SQLite IMMEDIATE transactions
 *   to prevent race conditions and duplication glitches.
 */
public class BalanceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BalanceManager.class);

    private final SQLiteStorage storage;

    public BalanceManager(SQLiteStorage storage) {
        this.storage = storage;
    }

    /**
     * Gets a player's current balance.
     * If the player has no record, one is created with the default starting balance.
     *
     * @param player The server player
     * @return CompletableFuture containing the current balance
     */
    public CompletableFuture<Double> getBalance(ServerPlayer player) {
        return storage.getBalance(player.getUUID(), player.getName().getString());
    }

    /**
     * Gets a player's balance by UUID and name (for offline lookups).
     *
     * @param uuid       The player's UUID
     * @param playerName The player's name
     * @return CompletableFuture containing the current balance
     */
    public CompletableFuture<Double> getBalance(UUID uuid, String playerName) {
        return storage.getBalance(uuid, playerName);
    }

    /**
     * Sets a player's balance to an exact value.
     * Used primarily for administrative operations.
     *
     * @param player The server player
     * @param amount The new balance amount
     * @return CompletableFuture indicating success
     */
    public CompletableFuture<Boolean> setBalance(ServerPlayer player, double amount) {
        if (!CurrencyUtil.isValidBalance(amount)) {
            LOGGER.warn("Invalid balance set rejected for {}: {}", player.getName().getString(), amount);
            return CompletableFuture.completedFuture(false);
        }
        return storage.setBalance(player.getUUID(), player.getName().getString(), amount);
    }

    /**
     * Adds currency to a player's balance.
     * Used for: selling items, receiving payments, auction proceeds.
     *
     * @param player The server player
     * @param amount The amount to add (must be positive)
     * @return CompletableFuture with the new balance, or -1 on failure
     */
    public CompletableFuture<Double> addBalance(ServerPlayer player, double amount) {
        if (!CurrencyUtil.isValidAmount(amount)) {
            LOGGER.warn("Invalid add-balance amount rejected: {}", amount);
            return CompletableFuture.completedFuture(-1.0);
        }
        return storage.addBalance(player.getUUID(), player.getName().getString(), amount);
    }

    /**
     * Adds currency to a player by UUID (for offline transactions like auction returns).
     *
     * @param uuid       The player's UUID
     * @param playerName The player's name
     * @param amount     The amount to add
     * @return CompletableFuture with the new balance, or -1 on failure
     */
    public CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount) {
        if (!CurrencyUtil.isValidAmount(amount)) {
            return CompletableFuture.completedFuture(-1.0);
        }
        return storage.addBalance(uuid, playerName, amount);
    }

    /**
     * Subtracts currency from a player's balance.
     * Used for: buying items, sending payments, listing auction items.
     * Rejects if insufficient funds (returns -1.0).
     *
     * @param player The server player
     * @param amount The amount to subtract (must be positive)
     * @return CompletableFuture with the new balance, or -1.0 if insufficient funds
     */
    public CompletableFuture<Double> subtractBalance(ServerPlayer player, double amount) {
        if (!CurrencyUtil.isValidAmount(amount)) {
            LOGGER.warn("Invalid subtract-balance amount rejected: {}", amount);
            return CompletableFuture.completedFuture(-1.0);
        }
        return storage.subtractBalance(player.getUUID(), player.getName().getString(), amount);
    }

    /**
     * Subtracts currency from a player's balance by UUID (for offline transactions).
     * Rejects if the player has insufficient funds (returns -1.0).
     *
     * <p>This is the offline-safe version needed by mods that perform
     * balance operations where the target player might not be online
     * (e.g., timed penalties, death penalties, automatic fees, etc.).</p>
     *
     * @param uuid       The player's UUID
     * @param playerName The player's name
     * @param amount     The amount to subtract (must be positive)
     * @return CompletableFuture with the new balance, or -1.0 if insufficient funds
     */
    public CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount) {
        if (!CurrencyUtil.isValidAmount(amount)) {
            LOGGER.warn("Invalid subtract-balance amount rejected: {}", amount);
            return CompletableFuture.completedFuture(-1.0);
        }
        return storage.subtractBalance(uuid, playerName, amount);
    }

    /**
     * Checks if a player can afford a specific amount.
     *
     * @param player The server player
     * @param amount The amount to check
     * @return CompletableFuture with true if the player has sufficient funds
     */
    public CompletableFuture<Boolean> hasSufficientBalance(ServerPlayer player, double amount) {
        return storage.hasBalance(player.getUUID(), amount);
    }

    /**
     * Performs a safe peer-to-peer transfer between two players.
     * This is an atomic operation: either both sides succeed or neither does.
     *
     * Anti-Exploit Protections:
     * - Negative amount rejection (prevents reverse-transfer exploits)
     * - Zero amount rejection (prevents spam)
     * - Self-transfer rejection (prevents confusion)
     * - Insufficient funds check (atomic deduct-then-add)
     * - Maximum transaction cap enforcement
     *
     * @param sender   The player sending currency
     * @param receiver The player receiving currency
     * @param amount   The amount to transfer
     * @return CompletableFuture with TransferResult indicating outcome
     */
    public CompletableFuture<TransferResult> transfer(ServerPlayer sender, ServerPlayer receiver, double amount) {
        return transferOffline(
            sender.getUUID(), sender.getName().getString(),
            receiver.getUUID(), receiver.getName().getString(),
            amount
        );
    }

    /**
     * Performs an offline-safe transfer between two players by UUID.
     * Neither player needs to be online. This is an atomic operation:
     * either both sides succeed or neither does.
     *
     * <p>If the deduction from the sender fails (insufficient funds),
     * the receiver's balance is not modified. If the addition to the
     * receiver fails after deduction, the sender is refunded.</p>
     *
     * Anti-Exploit Protections:
     * - Negative amount rejection
     * - Zero amount rejection
     * - Self-transfer rejection
     * - Insufficient funds check (atomic deduct-then-add)
     * - Maximum transaction cap enforcement
     *
     * @param senderUuid   The sender's UUID
     * @param senderName   The sender's name
     * @param receiverUuid The receiver's UUID
     * @param receiverName The receiver's name
     * @param amount       The amount to transfer
     * @return CompletableFuture with TransferResult indicating outcome
     */
    public CompletableFuture<TransferResult> transferOffline(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount) {
        // Pre-validation (synchronous, fast checks)
        if (amount <= 0) {
            return CompletableFuture.completedFuture(
                new TransferResult(false, "Amount must be positive.", 0, 0));
        }
        if (!CurrencyUtil.isValidAmount(amount)) {
            return CompletableFuture.completedFuture(
                new TransferResult(false, "Amount exceeds maximum transfer limit.", 0, 0));
        }
        if (senderUuid.equals(receiverUuid)) {
            return CompletableFuture.completedFuture(
                new TransferResult(false, "You cannot pay yourself.", 0, 0));
        }

        // Transaction hook veto (Solidus 2.1.0+): companion mods (e.g. Solidus
        // Governance) can enforce limits / trading locks / frozen accounts here.
        // Runs synchronously on the caller thread - hooks are contracted to be
        // fast, non-blocking, in-memory checks. First denial wins and its reason
        // is surfaced to the player verbatim via the TransferResult message.
        if (EconomyHooks.hasHooks()) {
            SolidusTransactionHook.Decision decision = EconomyHooks.allow(hook ->
                hook.allowTransfer(senderUuid, senderName, receiverUuid, receiverName, amount));
            if (!decision.allowed()) {
                return CompletableFuture.completedFuture(
                    new TransferResult(false, decision.reason(), 0, 0));
            }
        }

        // Atomic transfer: deduct from sender, then add to receiver
        return storage.subtractBalance(senderUuid, senderName, amount)
            .thenCompose(newSenderBalance -> {
                if (newSenderBalance < 0) {
                    // Insufficient funds - nothing was deducted
                    return CompletableFuture.completedFuture(
                        new TransferResult(false, "Insufficient funds.", 0, 0));
                }
                // Deduction succeeded, now add to receiver
                return storage.addBalance(receiverUuid, receiverName, amount)
                    .thenCompose(newReceiverBalance -> {
                        if (newReceiverBalance < 0) {
                            // CRITICAL: Addition failed after deduction - must verify refund succeeds
                            LOGGER.error(
                                "CRITICAL: Transfer add failed after deduct! Refunding sender. Sender: {}, Receiver: {}, Amount: {}",
                                senderName, receiverName, amount);
                            // Chain the refund and verify it succeeds - this is NOT fire-and-forget
                            return storage.addBalance(senderUuid, senderName, amount)
                                .thenApply(refundResult -> {
                                    if (refundResult < 0) {
                                        LOGGER.error(
                                            "CATASTROPHIC: Refund also failed after transfer deduct! Sender: {}, Amount: {}. MANUAL INTERVENTION REQUIRED.",
                                            senderName, amount);
                                    }
                                    return new TransferResult(false, "Transfer failed. Please try again.", 0, 0);
                                });
                        }
                        // Hook notifications (Solidus 2.1.0+): fired only after the
                        // transfer has fully settled so observers (limit recording,
                        // tax collection) always see committed state.
                        EconomyHooks.notifyHooks(hook ->
                            hook.afterTransfer(senderUuid, senderName,
                                receiverUuid, receiverName, amount));

                        return CompletableFuture.completedFuture(
                            new TransferResult(true, "Transfer successful.", newSenderBalance, newReceiverBalance));
                    });
            });
    }

    /**
     * Gets the top N balances for leaderboard display.
     *
     * @param limit Number of top entries to return
     * @return CompletableFuture with list of balance entries
     */
    public CompletableFuture<List<SQLiteStorage.BalanceEntry>> getTopBalances(int limit) {
        return storage.getTopBalances(limit);
    }

    /**
     * Result of a peer-to-peer transfer operation.
     *
     * @param success           Whether the transfer succeeded
     * @param message           Human-readable result message
     * @param senderNewBalance   The sender's new balance (0 if failed)
     * @param receiverNewBalance The receiver's new balance (0 if failed)
     */
    public record TransferResult(
        boolean success,
        String message,
        double senderNewBalance,
        double receiverNewBalance
    ) {}
}
