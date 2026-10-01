package com.solidus.api;

import com.solidus.SolidusMod;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.StorageBackend;
import com.solidus.economy.TransactionLog;

import com.solidus.shop.ShopManager;

import net.minecraft.server.level.ServerPlayer;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SolidusAPI — the <b>legacy reflective facade</b>, kept binary-compatible
 * for companion mods built before family 2.3.0.
 *
 * <p>As of family 2.3.0 (audit W-5 fix) the compile-time contract lives in
 * the separate <b>solidus-api</b> artifact ({@link SolidusApi} +
 * {@link SolidusApiAccess}), and this class implements and installs it.
 * New companion code MUST compile against {@code solidus-api}:</p>
 *
 * <pre>{@code
 * SolidusApi api = SolidusApiAccess.get();
 * api.getBalance(uuid, name).thenAccept(...);
 * }</pre>
 *
 * <h3>Why this class still exists</h3>
 * <p>Governance 2.1.x, Enforcer 2.1.x and Analytics 2.1.x reach Core only
 * through {@code Class.forName("com.solidus.api.SolidusAPI")} and reflective
 * method invocation. Removing or re-typing those methods would silently push
 * them into standalone mode — the exact failure this API exists to prevent.
 * The class therefore keeps every historic public method with its exact
 * erased signature, delegating to the same engine paths as the new
 * interface. It is a shim, not the contract.</p>
 *
 * <h3>Binary-compatibility guarantees of this shim</h3>
 * <ul>
 *   <li>All method names and erased parameter types are frozen until 3.0.0.</li>
 *   <li>Generic return types were migrated to the solidus-api records
 *       ({@link BalanceEntry}, {@link EconomyStats}, {@link TransferResult},
 *       {@link TransactionRecord}). Erasure keeps the reflective signatures
 *       byte-identical, and the record component names match the historic
 *       inner records — reflective readers see no difference.</li>
 *   <li>{@link #getEconomyEngine()} and {@link #getTransactionLog()} still
 *       expose Core internals for pre-2.3 Governance. They are deprecated:
 *       new code must use the safe API surface instead
 *       ({@link SolidusApi#setBalance} replaces the reflective
 *       SQLiteStorage.setBalance reach-in, {@link SolidusApi#logTransaction}
 *       replaces the TransactionLog grab).</li>
 * </ul>
 *
 * @deprecated compile against the solidus-api artifact instead
 * @since 1.0.0
 */
@Deprecated
public final class SolidusAPI implements SolidusApi {

    private static volatile SolidusAPI instance;

    private final EconomyEngine engine;

    private SolidusAPI(EconomyEngine engine) {
        this.engine = engine;
    }

    /**
     * Initializes the legacy facade and installs the {@link SolidusApi}
     * contract. Called once by SolidusMod during startup — at mod-init time,
     * before any server lifecycle event, so both reflective 2.1.x companions
     * and 2.3.0+ compiled companions see a ready API in every hook.
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
        SolidusApiAccess.install(instance);
        SolidusMod.LOGGER.info(
            "SolidusAPI initialized (legacy reflective shim + solidus-api contract installed). "
                + "External mods can now integrate.");
    }

    /**
     * Gets the legacy facade instance.
     * Returns {@code null} if Solidus is not loaded or not yet initialized.
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

    // -- SolidusApi: availability --------------------------

    @Override
    public String getCoreVersion() {
        return "2.3.2";
    }

    @Override
    public boolean isEngineReady() {
        return isAvailable();
    }

    /**
     * solidus-api 2.3.2: the backend signal Analytics used to reach by
     * reflecting into {@code EconomyEngine.isMysqlMode()}. SQLite tests and
     * single-server setups see false; a MySQL/MariaDB network Core reports
     * true and companions switch their ledger reads to
     * {@link #withLedgerConnection}.
     */
    @Override
    public boolean isMysqlMode() {
        return engine != null && engine.isMysqlMode();
    }

    /**
     * solidus-api 2.3.2: the sell-price table Enforcer used to walk by
     * reflecting over {@code ShopManager} internals. The shop config is an
     * immutable in-memory snapshot after load and {@link
     * ShopManager#getSections()} returns an unmodifiable map, so this is a
     * cheap, non-blocking snapshot — returned already-completed.
     *
     * <p>{@code SolidusMod.getShopManager()} is created <i>after</i>
     * {@code SolidusAPI.initialize(...)} during mod init, so a companion
     * calling in that window (or a unit test without the full mod boot)
     * simply sees an empty table.</p>
     */
    @Override
    public CompletableFuture<Map<String, Double>> getShopSellPrices() {
        return CompletableFuture.completedFuture(snapshotShopSellPrices());
    }

    /**
     * Builds the material -&gt; sell-price snapshot. First-writer-wins on
     * duplicate materials across sections, mirroring the reflective walk it
     * replaces (Enforcer's {@code putIfAbsent} + uppercase normalization).
     */
    private static Map<String, Double> snapshotShopSellPrices() {
        ShopManager shop = SolidusMod.getShopManager();
        if (shop == null) {
            return Map.of();
        }
        Map<String, Double> prices = new LinkedHashMap<>();
        for (ShopManager.ShopSection section : shop.getSections().values()) {
            for (ShopManager.ShopItem item : section.items()) {
                if (item.material() != null && item.sellPrice() > 0.0) {
                    prices.putIfAbsent(item.material().toUpperCase(Locale.ROOT), item.sellPrice());
                }
            }
        }
        return java.util.Collections.unmodifiableMap(prices);
    }

    /**
     * solidus-api 2.3.2: backend-agnostic ledger access. Companions used to
     * build a reflective {@code Proxy} over Core's internal
     * {@code TransactionLog$SqlWork} interface — now the adapter runs
     * directly, with the same borrow/return semantics Core itself uses
     * for the supply-integrity checker.
     */
    @Override
    public <T> T withLedgerConnection(LedgerWork<T> work) throws SQLException {
        if (engine == null || !engine.isInitialized() || engine.getTransactionLog() == null) {
            throw new SQLException("Solidus ledger is not initialized");
        }
        return engine.getTransactionLog().withConnection(work::run);
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

    @Override
    public CompletableFuture<Double> getBalance(UUID uuid, String name) {
        return getBalanceOffline(uuid, name);
    }

    /**
     * Adds currency to an online player's balance.
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

    @Override
    public CompletableFuture<Double> addBalance(UUID uuid, String name, double amount) {
        return addBalanceOffline(uuid, name, amount);
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

    @Override
    public CompletableFuture<Double> subtractBalance(UUID uuid, String name, double amount) {
        return subtractBalanceOffline(uuid, name, amount);
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

    @Override
    public CompletableFuture<Boolean> hasSufficientBalance(UUID uuid, String name, double amount) {
        return getBalance(uuid, name).thenApply(balance -> balance != null && balance >= amount);
    }

    @Override
    public CompletableFuture<Boolean> setBalance(UUID uuid, String name, double newBalance, String reason) {
        if (newBalance < 0) {
            return CompletableFuture.completedFuture(false);
        }
        StorageBackend storage = engine.getStorage();
        if (storage == null) {
            return CompletableFuture.completedFuture(false);
        }
        String safeReason = reason != null && !reason.isBlank() ? reason : "companion setBalance via solidus-api";
        return storage.setBalance(uuid, name, newBalance).thenApply(written -> {
            if (Boolean.TRUE.equals(written)) {
                // Journal the administrative overwrite so the supply-integrity
                // replay and the rebase workflow stay exact — the audited
                // replacement for Governance's reflective SQLiteStorage hack.
                TransactionLog txLog = engine.getTransactionLog();
                if (txLog != null) {
                    txLog.log(TransactionLog.Type.ADMIN_SET, uuid, name,
                        null, null, newBalance, null, 0, safeReason);
                }
            }
            return Boolean.TRUE.equals(written);
        });
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
    public CompletableFuture<TransferResult> transfer(
            ServerPlayer sender, ServerPlayer receiver, double amount) {
        return engine.getBalanceManager().transfer(sender, receiver, amount)
            .thenApply(SolidusAPI::toApiTransferResult);
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
    public CompletableFuture<TransferResult> transferOffline(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount) {
        return engine.getBalanceManager().transferOffline(
                senderUuid, senderName, receiverUuid, receiverName, amount)
            .thenApply(SolidusAPI::toApiTransferResult);
    }

    @Override
    public CompletableFuture<TransferResult> transfer(UUID senderUuid, String senderName,
                                                      UUID receiverUuid, String receiverName,
                                                      double amount) {
        return transferOffline(senderUuid, senderName, receiverUuid, receiverName, amount);
    }

    // -- Leaderboard -------------------------------------

    /**
     * Gets the top N players by balance for leaderboard display (first page
     * of {@link #getTopBalances(int, int)}).
     *
     * @param limit Maximum number of entries to return
     * @return CompletableFuture with list of BalanceEntry objects
     */
    public CompletableFuture<List<BalanceEntry>> getTopBalances(int limit) {
        return engine.getBalanceManager().getTopBalances(limit)
            .thenApply(entries -> entries.stream().map(SolidusAPI::toApiBalanceEntry).toList());
    }

    /**
     * Gets a page of the leaderboard with pagination pushed down to the
     * database (LIMIT/OFFSET), so deep pages cost the same as page 1.
     *
     * @param limit  Maximum number of entries to return (page size)
     * @param offset Number of higher-ranked entries to skip (0-based)
     * @return CompletableFuture with list of BalanceEntry objects
     */
    public CompletableFuture<List<BalanceEntry>> getTopBalances(int limit, int offset) {
        return engine.getBalanceManager().getTopBalances(limit, offset)
            .thenApply(entries -> entries.stream().map(SolidusAPI::toApiBalanceEntry).toList());
    }

    /**
     * Counts all registered economy entries (players with a balance row).
     *
     * @return CompletableFuture with the total number of balance entries
     */
    public CompletableFuture<Integer> getBalanceEntryCount() {
        return engine.getBalanceManager().countBalanceEntries();
    }

    /**
     * Economy-wide aggregates (player count, mean balance, money supply, Gini
     * coefficient) computed inside the database with one aggregate query.
     *
     * @return CompletableFuture with the current EconomyStats
     */
    public CompletableFuture<EconomyStats> getEconomyStats() {
        return engine.getBalanceManager().getEconomyStats()
            .thenApply(stats -> new EconomyStats(
                stats.playerCount(), stats.avgBalance(),
                stats.totalSupply(), stats.giniCoefficient()));
    }

    // -- Transaction history (read) -------------------------

    @Override
    public CompletableFuture<List<TransactionRecord>> getTransactions(UUID playerUuid, int limit) {
        TransactionLog txLog = engine.getTransactionLog();
        if (txLog == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return txLog.getTransactions(playerUuid, limit)
            .thenApply(entries -> entries.stream().map(SolidusAPI::toApiTransactionRecord).toList());
    }

    @Override
    public CompletableFuture<List<TransactionRecord>> getTransactions(UUID playerUuid, int limit, int offset) {
        TransactionLog txLog = engine.getTransactionLog();
        if (txLog == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return txLog.getTransactions(playerUuid, limit, offset)
            .thenApply(entries -> entries.stream().map(SolidusAPI::toApiTransactionRecord).toList());
    }

    @Override
    public CompletableFuture<List<TransactionRecord>> getTransactionsSince(UUID playerUuid, long sinceEpochMs) {
        TransactionLog txLog = engine.getTransactionLog();
        if (txLog == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return txLog.getTransactionsSince(playerUuid, sinceEpochMs)
            .thenApply(entries -> entries.stream().map(SolidusAPI::toApiTransactionRecord).toList());
    }

    // -- Custom transaction logging (write) -----------------

    @Override
    public CompletableFuture<Boolean> logTransaction(TransactionRecord record) {
        TransactionLog txLog = engine.getTransactionLog();
        if (txLog == null || record == null || record.type() == null) {
            return CompletableFuture.completedFuture(false);
        }
        // Reject codes that do not map to a real ledger Type: TransactionLog's
        // fromCode() deliberately falls back to SHOP_BUY for unknown codes,
        // which would corrupt transaction semantics. Validate strictly here.
        TransactionLog.Type type = resolveType(record.type());
        if (type == null) {
            SolidusMod.LOGGER.warn(
                "solidus-api logTransaction rejected unknown type code '{}'. "
                    + "Use one of the documented TransactionLog type codes.",
                record.type());
            return CompletableFuture.completedFuture(false);
        }
        try {
            txLog.log(type,
                record.playerUuid(), record.playerName(),
                record.targetUuid(), record.targetName(),
                record.amount(), record.itemMaterial(),
                record.itemQuantity(), record.description());
            return CompletableFuture.completedFuture(true);
        } catch (Throwable t) {
            SolidusMod.LOGGER.warn("solidus-api logTransaction failed: {}", t.toString());
            return CompletableFuture.completedFuture(false);
        }
    }

    // -- Transaction Logging (legacy accessor) --------------

    /**
     * Gets the transaction log for recording custom transaction types
     * or querying a player's financial history.
     *
     * <p>Exposed for reflective 2.1.x companions (Enforcer's history reader).
     * New code must use {@link #getTransactions(UUID, int)} /
     * {@link #logTransaction(TransactionRecord)} instead.</p>
     *
     * @return The TransactionLog instance, or null if not initialized
     * @deprecated internal type on the return — use the solidus-api surface
     */
    @Deprecated
    public TransactionLog getTransactionLog() {
        if (engine == null || !engine.isInitialized()) return null;
        return engine.getTransactionLog();
    }

    // -- Transaction Hooks --------------------------------

    /**
     * Registers an economy transaction hook. Hooks can veto transactions
     * before they happen (limits, trading locks, freezes) and observe them
     * afterwards (recording, taxes, alerts).
     *
     * <p>Registration is idempotent by hook name: registering a second hook
     * with the same {@link SolidusTransactionHook#name() name} is ignored
     * and returns false.</p>
     *
     * @param hook The hook to register (must not be null)
     * @return true if registered, false if a hook with the same name exists
     */
    public boolean registerTransactionHook(SolidusTransactionHook hook) {
        return EconomyHooks.register(hook);
    }

    /**
     * Unregisters a previously registered transaction hook.
     *
     * @param hook The hook instance to remove
     * @return true if it was registered and is now removed
     */
    public boolean unregisterTransactionHook(SolidusTransactionHook hook) {
        return EconomyHooks.unregister(hook);
    }

    /**
     * Gets the number of currently registered transaction hooks.
     *
     * @return the number of active hooks
     */
    public int getRegisteredHookCount() {
        return EconomyHooks.registeredHooks().size();
    }

    // -- Utility -----------------------------------------

    /**
     * Gets the internal EconomyEngine instance.
     *
     * <p>Kept ONLY for reflective 2.1.x Governance (it reached the engine's
     * storage through this method to perform rollback restores). 2.3.0+
     * companions must use {@link #setBalance(UUID, String, double, String)}
     * for administrative writes.</p>
     *
     * @return The EconomyEngine instance
     * @deprecated internal type on the return — use the solidus-api surface
     */
    @Deprecated
    public EconomyEngine getEconomyEngine() {
        return engine;
    }

    // -- Mapping helpers (internal -> api records) ----------

    private static TransferResult toApiTransferResult(BalanceManager.TransferResult result) {
        return new TransferResult(result.success(), result.message(),
            result.senderNewBalance(), result.receiverNewBalance());
    }

    private static BalanceEntry toApiBalanceEntry(SQLiteStorage.BalanceEntry entry) {
        return new BalanceEntry(entry.uuid(), entry.rank(), entry.playerName(), entry.balance());
    }

    private static TransactionRecord toApiTransactionRecord(TransactionLog.TransactionEntry entry) {
        return new TransactionRecord(entry.timestamp(), entry.type().code(),
            entry.playerUuid(), entry.playerName(), entry.targetUuid(), entry.targetName(),
            entry.amount(), entry.itemMaterial(), entry.itemQuantity(), entry.description());
    }

    /** Strict type-code resolution (no silent SHOP_BUY fallback). */
    private static TransactionLog.Type resolveType(String code) {
        if (code == null) return null;
        for (TransactionLog.Type type : TransactionLog.Type.values()) {
            if (type.code().equals(code)) return type;
        }
        return null;
    }
}
