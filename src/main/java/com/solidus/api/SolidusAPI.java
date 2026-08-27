package com.solidus.api;

import com.solidus.SolidusMod;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.TransactionLog;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SolidusAPI - Stable public API for inter-mod integration.
 *
 * <p>This is the <b>only</b> class that external mods should depend on.
 * Internal classes ({@code EconomyEngine}, {@code BalanceManager}, etc.)
 * may change between versions without notice, but the methods defined
 * here are guaranteed to remain stable across minor and patch releases.</p>
 *
 * <h3>Usage from another mod (reflection-based, no compile dependency):</h3>
 * <pre>{@code
 * // 1. Check if Solidus is loaded
 * boolean hasSolidus = FabricLoader.getInstance().isModLoaded("solidus");
 * if (!hasSolidus) return;
 *
 * // 2. Get the API instance via reflection
 * Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
 * Method getInstance = apiClass.getMethod("getInstance");
 * Object api = getInstance.invoke(null);
 *
 * // 3. Call methods via reflection
 * Method getBalance = apiClass.getMethod("getBalance", ServerPlayer.class);
 * CompletableFuture<Double> balance = (CompletableFuture<Double>) getBalance.invoke(api, player);
 * }</pre>
 *
 * <h3>Usage from another mod (with compile dependency):</h3>
 * <pre>{@code
 * SolidusAPI api = SolidusAPI.getInstance();
 * if (api == null) return; // Solidus not loaded
 *
 * api.getBalance(victim).thenAccept(balance -> {
 *     double penalty = balance * 0.15;
 *     api.subtractBalance(victim, penalty);
 *     api.addBalance(killer, penalty);
 * });
 * }</pre>
 *
 * <h3>Thread Safety:</h3>
 * All methods return {@link CompletableFuture} and execute asynchronously
 * on Solidus's dedicated database worker thread. Callers on the server
 * tick thread must use {@code .thenAccept()} + {@code server.execute()}
 * for any UI or game-state updates.
 *
 * @since 1.0.0
 */
public final class SolidusAPI {

    private static volatile SolidusAPI instance;

    private final EconomyEngine engine;

    private SolidusAPI(EconomyEngine engine) {
        this.engine = engine;
    }

    /**
     * Initializes the API singleton. Called once by SolidusMod during startup.
     * External mods must NOT call this method.
     *
     * @param engine The initialized EconomyEngine instance
     */
    public static void initialize(EconomyEngine engine) {
        if (instance != null) {
            SolidusMod.LOGGER.warn("SolidusAPI already initialized. Ignoring duplicate call.");
            return;
        }
        instance = new SolidusAPI(engine);
        SolidusMod.LOGGER.info("SolidusAPI initialized. External mods can now integrate.");
    }

    /**
     * Gets the SolidusAPI instance.
     * Returns {@code null} if Solidus is not loaded or not yet initialized.
     *
     * <p>External mods should always null-check the return value before
     * calling any API methods.</p>
     *
     * @return The API instance, or null if Solidus is unavailable
     */
    public static SolidusAPI getInstance() {
        return instance;
    }

    /**
     * Checks whether Solidus is loaded and its API is ready for use.
     * This is a fast, non-blocking check.
     *
     * @return true if Solidus is loaded and the API is initialized
     */
    public static boolean isAvailable() {
        return instance != null && instance.engine != null && instance.engine.isInitialized();
    }

    // -- Balance Operations (Online Players) --------------

    /**
     * Gets an online player's current balance.
     *
     * @param player The server player (must be online)
     * @return CompletableFuture containing the current balance
     */
    public CompletableFuture<Double> getBalance(ServerPlayer player) {
        return engine.getBalanceManager().getBalance(player);
    }

    /**
     * Gets a player's balance by UUID and name (works for offline players).
     *
     * @param uuid       The player's UUID
     * @param playerName The player's name (for record creation)
     * @return CompletableFuture containing the current balance
     */
    public CompletableFuture<Double> getBalanceOffline(UUID uuid, String playerName) {
        return engine.getBalanceManager().getBalance(uuid, playerName);
    }

    /**
     * Adds currency to an online player's balance.
     * Used for: rewards, death penalty transfers to killer, etc.
     *
     * @param player The server player (must be online)
     * @param amount The amount to add (must be positive)
     * @return CompletableFuture with the new balance, or -1 on failure
     */
    public CompletableFuture<Double> addBalance(ServerPlayer player, double amount) {
        return engine.getBalanceManager().addBalance(player, amount);
    }

    /**
     * Adds currency to a player's balance by UUID (works for offline players).
     *
     * @param uuid       The player's UUID
     * @param playerName The player's name
     * @param amount     The amount to add
     * @return CompletableFuture with the new balance, or -1 on failure
     */
    public CompletableFuture<Double> addBalanceOffline(UUID uuid, String playerName, double amount) {
        return engine.getBalanceManager().addBalance(uuid, playerName, amount);
    }

    /**
     * Subtracts currency from an online player's balance.
     * Rejects if the player has insufficient funds (returns -1.0).
     *
     * @param player The server player (must be online)
     * @param amount The amount to subtract (must be positive)
     * @return CompletableFuture with the new balance, or -1.0 if insufficient funds
     */
    public CompletableFuture<Double> subtractBalance(ServerPlayer player, double amount) {
        return engine.getBalanceManager().subtractBalance(player, amount);
    }

    /**
     * Subtracts currency from a player's balance by UUID (works for offline players).
     * Rejects if the player has insufficient funds (returns -1.0).
     *
     * @param uuid       The player's UUID
     * @param playerName The player's name
     * @param amount     The amount to subtract (must be positive)
     * @return CompletableFuture with the new balance, or -1.0 if insufficient funds
     */
    public CompletableFuture<Double> subtractBalanceOffline(UUID uuid, String playerName, double amount) {
        return engine.getBalanceManager().subtractBalance(uuid, playerName, amount);
    }

    /**
     * Checks if an online player can afford a specific amount.
     *
     * @param player The server player
     * @param amount The amount to check
     * @return CompletableFuture with true if the player has sufficient funds
     */
    public CompletableFuture<Boolean> hasSufficientBalance(ServerPlayer player, double amount) {
        return engine.getBalanceManager().hasSufficientBalance(player, amount);
    }

    // -- Transfer Operations -----------------------------

    /**
     * Performs a safe peer-to-peer transfer between two online players.
     * Atomic: either both sides succeed or neither does.
     *
     * @param sender   The player sending currency
     * @param receiver The player receiving currency
     * @param amount   The amount to transfer
     * @return CompletableFuture with TransferResult indicating outcome
     */
    public CompletableFuture<BalanceManager.TransferResult> transfer(
            ServerPlayer sender, ServerPlayer receiver, double amount) {
        return engine.getBalanceManager().transfer(sender, receiver, amount);
    }

    /**
     * Performs an offline-safe transfer between two players by UUID.
     * Neither player needs to be online. Atomic: either both sides
     * succeed or neither does.
     *
     * @param senderUuid       The sender's UUID
     * @param senderName       The sender's name
     * @param receiverUuid     The receiver's UUID
     * @param receiverName     The receiver's name
     * @param amount           The amount to transfer
     * @return CompletableFuture with TransferResult indicating outcome
     */
    public CompletableFuture<BalanceManager.TransferResult> transferOffline(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount) {
        return engine.getBalanceManager().transferOffline(
            senderUuid, senderName, receiverUuid, receiverName, amount);
    }

    // -- Leaderboard -------------------------------------

    /**
     * Gets the top N players by balance for leaderboard display.
     *
     * @param limit Maximum number of entries to return
     * @return CompletableFuture with list of BalanceEntry objects
     */
    public CompletableFuture<List<SQLiteStorage.BalanceEntry>> getTopBalances(int limit) {
        return engine.getBalanceManager().getTopBalances(limit);
    }

    // -- Transaction Logging -----------------------------

    /**
     * Gets the transaction log for recording custom transaction types
     * or querying a player's financial history.
     *
     * <p>External mods can use this to log their own transactions
     * (e.g., death penalties) so they appear in the player's
     * {@code /transactions} history.</p>
     *
     * @return The TransactionLog instance, or null if not initialized
     */
    public TransactionLog getTransactionLog() {
        if (engine == null || !engine.isInitialized()) return null;
        return engine.getTransactionLog();
    }

    // -- Utility -----------------------------------------

    /**
     * Gets the internal EconomyEngine instance.
     * Only for advanced use cases that need direct access to
     * Solidus internals. Most operations should use the API methods above.
     *
     * @return The EconomyEngine instance
     */
    public EconomyEngine getEconomyEngine() {
        return engine;
    }
}
