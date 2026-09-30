package com.solidus.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SolidusApi — the stable, Minecraft-free facade over the Solidus
 * economy engine.
 *
 * <p>This interface is the <b>compile-time contract</b> between Solidus
 * Core and the companion family (Governance, Enforcer, Analytics). It is
 * the replacement for the historic reflective integration: companions
 * used to {@code Class.forName("com.solidus.api.SolidusAPI")} and then
 * reach into Core <i>internals</i> ({@code EconomyEngine},
 * {@code SQLiteStorage}) with MethodHandles — a silent-degradation
 * hazard where Governance limits/taxes/freezes could turn off without
 * any loader-level signal (audit finding W-5).</p>
 *
 * <h3>How a companion consumes this (no reflection):</h3>
 * <pre>{@code
 * // build.gradle:   compileOnly files("libs/solidus-api-2.3.0.jar")
 * // fabric.mod.json: "depends": { "solidus": ">=2.3.0 <3.0.0" }
 *
 * SolidusApi api = SolidusApiAccess.get();
 * if (api == null) throw new IllegalStateException("Solidus Core missing — loader should have caught this");
 * api.getBalance(uuid, name).thenAccept(b -> ...);
 * }</pre>
 *
 * <p>Because companions declare {@code depends: solidus} in
 * {@code fabric.mod.json}, Fabric's loader itself refuses to start the
 * companion against a missing or incompatible Core. The silent
 * "standalone mode" failure mode is structurally eliminated.</p>
 *
 * <h3>Design rules:</h3>
 * <ul>
 *   <li><b>Player identification is always {@code (UUID, String name)}</b> —
 *       no Minecraft types leak into the contract. The engine resolves
 *       online players by UUID; offline players get a durable balance row
 *       created on first touch, exactly like the legacy offline variants.</li>
 *   <li>All balance mutations return {@link CompletableFuture} and run on
 *       Solidus's single-threaded economy executor — the same ordering
 *       guarantees Core itself obeys.</li>
 *   <li>Value records ({@link BalanceEntry}, {@link EconomyStats},
 *       {@link TransferResult}, {@link TransactionRecord}) are frozen data
 *       carriers owned by this module — their component names are part of
 *       the contract (reflective 2.1.x-era readers bind to those names).</li>
 * </ul>
 *
 * <h3>Threading:</h3> veto hooks run synchronously on the economy executor
 * or server thread; API futures complete on the economy executor. Callers
 * on the server tick thread must hop back with {@code server.execute(...)}
 * before touching game state.
 *
 * @see SolidusApiAccess for the entry point
 * @see SolidusTransactionHook for enforcement hooks
 * @since 2.3.0 (family contract 2.3)
 */
public interface SolidusApi {

    // -- Availability -------------------------------------

    /**
     * @return the version of Solidus Core serving this API (e.g. {@code "2.3.0"}),
     *         never null
     */
    String getCoreVersion();

    /**
     * @return true when the storage engine finished initializing and balance
     *         operations can be dispatched
     */
    boolean isEngineReady();

    // -- Balance operations (offline-safe) ------------------

    /**
     * Reads a player's balance, creating the durable balance row on first
     * touch for unknown players. Works for online and offline players alike.
     *
     * @param uuid   the player's UUID
     * @param name   the player's current name (row creation / rename)
     * @return future completing with the balance (0.0 for fresh accounts)
     */
    CompletableFuture<Double> getBalance(UUID uuid, String name);

    /**
     * Credits currency to a player (online or offline).
     *
     * @param uuid   the player's UUID
     * @param name   the player's name
     * @param amount the amount to add (must be &ge; 0)
     * @return future completing with the new balance, or -1.0 on failure
     */
    CompletableFuture<Double> addBalance(UUID uuid, String name, double amount);

    /**
     * Debits currency from a player (online or offline). Fails cleanly with
     * -1.0 when funds are insufficient — this is a <i>move</i>, not a
     * <i>force-write</i> (see {@link #setBalance} for administrative writes).
     *
     * @param uuid   the player's UUID
     * @param name   the player's name
     * @param amount the amount to subtract (must be &ge; 0)
     * @return future completing with the new balance, or -1.0 if insufficient
     */
    CompletableFuture<Double> subtractBalance(UUID uuid, String name, double amount);

    /**
     * Checks whether a player can afford an amount without moving money.
     *
     * @param uuid   the player's UUID
     * @param name   the player's name
     * @param amount the amount to check
     * @return future completing with true when the balance covers the amount
     */
    CompletableFuture<Boolean> hasSufficientBalance(UUID uuid, String name, double amount);

    /**
     * Administrative balance OVERWRITE (not a move). This is the legitimate,
     * audited replacement for the old Governance hack that reflectively called
     * {@code SQLiteStorage.setBalance(...)} — a Core internal. The write is
     * journaled into the append-only ledger so supply-integrity auditing and
     * the rebase workflow stay exact.
     *
     * <p>Use for: backup/rollback restore, admin corrections, wealth-cap
     * automation. Do NOT use it for taxes/purchases (use the move methods so
     * the ledger records the flow).</p>
     *
     * @param uuid   the player's UUID
     * @param name   the player's name
     * @param newBalance the exact balance to write
     * @param reason  audit reason recorded in the ledger (never null)
     * @return future completing with true when the write landed
     */
    CompletableFuture<Boolean> setBalance(UUID uuid, String name, double newBalance, String reason);

    /**
     * Atomic peer-to-peer move: either both sides settle or neither does.
     * All transfer veto hooks run inside this call.
     *
     * @param senderUuid   the sender's UUID
     * @param senderName   the sender's name
     * @param receiverUuid the receiver's UUID
     * @param receiverName the receiver's name
     * @param amount       the amount to move (must be &gt; 0)
     * @return future completing with the settlement outcome
     */
    CompletableFuture<TransferResult> transfer(UUID senderUuid, String senderName,
                                                UUID receiverUuid, String receiverName,
                                                double amount);

    // -- Leaderboard & aggregates --------------------------

    /**
     * Top balances by rank, first page.
     *
     * @param limit page size
     * @return future completing with the ranked entries
     */
    CompletableFuture<List<BalanceEntry>> getTopBalances(int limit);

    /**
     * Top balances with SQL-side pagination — deep pages cost the same as
     * page 1 (LIMIT/OFFSET pushdown).
     *
     * @param limit  page size
     * @param offset 0-based number of higher-ranked entries to skip
     * @return future completing with the ranked entries
     */
    CompletableFuture<List<BalanceEntry>> getTopBalances(int limit, int offset);

    /**
     * @return future completing with the number of balance rows (for page math)
     */
    CompletableFuture<Integer> getBalanceEntryCount();

    /**
     * Economy-wide aggregates (player count, mean, money supply, Gini)
     * computed inside the database with one aggregate query — never
     * materializes balance rows.
     *
     * @return future completing with the aggregate snapshot
     */
    CompletableFuture<EconomyStats> getEconomyStats();

    // -- Transaction history (read) -------------------------

    /**
     * Most recent transactions of one player, newest first.
     *
     * @param playerUuid the player's UUID
     * @param limit      maximum number of entries
     * @return future completing with the records
     */
    CompletableFuture<List<TransactionRecord>> getTransactions(UUID playerUuid, int limit);

    /**
     * Paged transaction history of one player.
     *
     * @param playerUuid the player's UUID
     * @param limit      page size
     * @param offset     0-based number of newer entries to skip
     * @return future completing with the records
     */
    CompletableFuture<List<TransactionRecord>> getTransactions(UUID playerUuid, int limit, int offset);

    /**
     * Transactions of one player since an epoch-millisecond instant —
     * the window companions need for live monitoring and fraud detection.
     *
     * @param playerUuid   the player's UUID
     * @param sinceEpochMs inclusive lower bound
     * @return future completing with the records (newest first)
     */
    CompletableFuture<List<TransactionRecord>> getTransactionsSince(UUID playerUuid, long sinceEpochMs);

    // -- Custom transaction logging (write) -----------------

    /**
     * Writes a custom entry into the append-only ledger so a companion's
     * economy effects (bounties, penalties, taxes, rewards) appear in the
     * player's {@code /transactions} history and in the supply-integrity
     * replay. This is the audited replacement for grabbing Core's
     * {@code TransactionLog} internal.
     *
     * @param record the record to append (type codes starting with
     *               {@code "COMPANION_"} are reserved for callers of this API)
     * @return future completing with true when persisted
     */
    CompletableFuture<Boolean> logTransaction(TransactionRecord record);

    // -- Enforcement hooks ----------------------------------

    /**
     * Registers an economy transaction hook (vetoes + post-settlement
     * observation). Registration is idempotent by {@link SolidusTransactionHook#name()}
     * and first-come-first-served — a duplicate name is rejected and logged.
     *
     * @param hook the hook to register
     * @return true if registered, false if the name is taken
     */
    boolean registerTransactionHook(SolidusTransactionHook hook);

    /**
     * Removes a previously registered hook.
     *
     * @param hook the hook instance to remove
     * @return true if it was registered and is now removed
     */
    boolean unregisterTransactionHook(SolidusTransactionHook hook);

    /**
     * @return the number of active hooks (diagnostics)
     */
    int getRegisteredHookCount();
}
