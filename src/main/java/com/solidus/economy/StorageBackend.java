package com.solidus.economy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Storage backend contract for the Solidus economy (DB scaling plan, Phase 1).
 *
 * <p>This interface mirrors the public surface of {@link SQLiteStorage} so the
 * economy can run against any implementation selected by configuration
 * ({@code config/solidus/storage.json}, default {@code "sqlite"}). All money
 * primitives are asynchronous and must never block the server tick thread.</p>
 *
 * <p><b>Type compatibility note (owner rule, 2.1.x):</b> the result types
 * ({@link SQLiteStorage.TransferOutcome}, {@link SQLiteStorage.BalanceEntry},
 * {@link SQLiteStorage.EconomyStats}, {@link SQLiteStorage.AtomicLedgerRow})
 * intentionally stay nested on {@link SQLiteStorage}. Companions compile
 * against those names via {@code SolidusAPI}, and VERSIONING.md forbids
 * breaking companions inside a patch family. Extracting them to top-level
 * types is deferred to a family bump and is NOT required for this interface
 * to work.</p>
 *
 * <p><b>Concurrency contract:</b> implementations serialize local mutations
 * through a single worker thread (the "executor is the lock" model). In
 * network-capable backends the shared database is the real lock — every
 * mutation must survive two servers racing the same row — while the local
 * executor only preserves ordering and keeps callers non-blocking.</p>
 *
 * @see SQLiteStorage the default single-file backend
 * @see MySqlStorage the shared-database backend (2.2.0+)
 */
public interface StorageBackend {

    // -- Lifecycle ----------------------------------------

    /** Initializes the backend (schema, pools, caches). Called once at startup. */
    void initialize();

    /** Shuts the backend down, flushing pending work and closing resources. */
    void shutdown();

    // -- Reads --------------------------------------------

    /**
     * Gets a player's balance. If the player has no record, one is created
     * with the default starting balance (mirrors SQLite semantics).
     */
    CompletableFuture<Double> getBalance(UUID uuid, String playerName);

    /**
     * Gets a page of the leaderboard, highest balance first. Ranks are global
     * and continue across pages (offset 10 starts at rank 11). System accounts
     * (bid escrow) are excluded.
     */
    CompletableFuture<List<SQLiteStorage.BalanceEntry>> getTopBalances(int limit, int offset);

    /** First leaderboard page (limit, offset 0). */
    default CompletableFuture<List<SQLiteStorage.BalanceEntry>> getTopBalances(int limit) {
        return getTopBalances(limit, 0);
    }

    /** Counts all registered balance rows (players + system accounts). */
    CompletableFuture<Integer> getBalanceEntryCount();

    /** Economy-wide aggregates (count, mean, supply, Gini) computed in SQL. */
    CompletableFuture<SQLiteStorage.EconomyStats> getEconomyStats();

    // -- Writes -------------------------------------------

    /** Sets a balance to an exact value. Returns false on invalid amounts. */
    CompletableFuture<Boolean> setBalance(UUID uuid, String playerName, double amount);

    /**
     * Adds an amount. Returns the new balance, or -1 on failure
     * (invalid amount / overflow cap / persistence error).
     */
    CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount);

    /**
     * Subtracts an amount. Returns the new balance, or -1 on failure
     * (invalid amount / insufficient funds / persistence error).
     */
    CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount);

    /** True when the account can afford {@code amount}. */
    CompletableFuture<Boolean> hasBalance(UUID uuid, double amount);

    // -- Atomic transfers ----------------------------------

    /**
     * Moves money sender → receiver as ONE atomic unit (both legs settle or
     * neither does). A sender with no row is treated as holding the starting
     * balance; a receiver with no row is created with starting + amount.
     */
    CompletableFuture<SQLiteStorage.TransferOutcome> transferAtomic(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount);

    /**
     * Same atomicity as {@link #transferAtomic}, plus the given ledger rows
     * are committed INSIDE the same transaction as the two balance updates —
     * money and evidence stay consistent in both directions (audit 2.1.3).
     */
    CompletableFuture<SQLiteStorage.TransferOutcome> transferAtomicWithLedger(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount,
            List<SQLiteStorage.AtomicLedgerRow> ledgerRows);

    // -- Shared services ------------------------------------

    /** The transaction log / offline-notification service bound to this backend. */
    TransactionLog getTransactionLog();

    /**
     * Unmodifiable view of the last-known player names (UUID → name), used
     * for offline lookups and command suggestions.
     */
    Map<UUID, String> getPlayerNameCache();
}
