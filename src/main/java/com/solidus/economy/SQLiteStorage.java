package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous SQLite Storage Backend for Solidus Economy Engine.
 *
 * Architecture: Single-Threaded Executor Queue + In-Memory Cache + Persistent Connection
 *
 * Thread Safety Strategy (v2):
 * All cache WRITES are processed through a dedicated single-thread executor,
 * which guarantees sequential execution without any overlap. This eliminates
 * race conditions between caller threads (server tick, command threads) and
 * the executor worker thread.
 *
 * Key rules:
 * - Cache READS (balanceCache.get) happen on the caller thread - safe because
 *   ConcurrentHashMap guarantees per-key atomicity and visibility.
 * - Cache WRITES (balanceCache.put, playerNameCache.put) happen ONLY on the
 *   executor thread - either via supplyAsync() for new player creation, or
 *   within existing supplyAsync() blocks for mutations.
 * - Player name updates from getBalance() are scheduled via
 *   asyncExecutor.execute() to prevent caller-thread writes from racing
 *   with executor-thread writes from persistBalance().
 * - New player creation uses a double-check pattern inside supplyAsync()
 *   and putIfAbsent() to prevent redundant DB inserts when two threads
 *   race on the same new UUID.
 *
 * Persistent Connection:
 * A single persistent SQLite connection is shared across all executor operations.
 * Since the single-threaded executor serializes all access, connection sharing is
 * inherently safe - no two operations can use the connection simultaneously.
 * This eliminates the overhead of opening/closing connections for every operation.
 *
 * Crash Resilience:
 * - WAL (Write-Ahead Logging) mode ensures committed transactions survive crashes.
 * - The in-memory cache is rebuilt from the database on startup.
 * - Auto-checkpoint balances performance vs. crash recovery window.
 * - All critical mutations are persisted to SQLite immediately after the
 *   in-memory state is updated, minimizing the data-at-risk window.
 */
public class SQLiteStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(SQLiteStorage.class);
    public static final String DATABASE_NAME = "economy.db";
    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS player_balances (
            uuid TEXT PRIMARY KEY NOT NULL,
            player_name TEXT NOT NULL,
            balance REAL NOT NULL DEFAULT 0.0,
            last_updated INTEGER NOT NULL
        )
    """;
    private static final String CREATE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_balance_rank
        ON player_balances (balance DESC)
    """;

    private final ConcurrentHashMap<UUID, Double> balanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerNameCache = new ConcurrentHashMap<>();

    private final ExecutorService asyncExecutor;
    private final String databaseUrl;
    private volatile boolean initialized = false;

    /** Persistent database connection - shared across all executor operations */
    private volatile Connection persistentConnection;

    /** Transaction log - shares this executor and connection */
    private TransactionLog transactionLog;

    /**
     * Constructs a new SQLiteStorage with the given config directory path.
     *
     * @param configDir The directory where the database file will be stored
     */
    public SQLiteStorage(String configDir) {
        this.databaseUrl = "jdbc:sqlite:" + configDir + "/" + DATABASE_NAME;
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Economy-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initializes the database: creates tables, indexes, configures WAL mode,
     * opens the persistent connection, and pre-loads all balances into cache.
     * Must be called once during mod startup before any other operations.
     */
    public void initialize() {
        try {
            // Open persistent connection (safe because single-threaded executor serializes all access)
            persistentConnection = DriverManager.getConnection(databaseUrl);

            // Enable WAL mode for crash resilience
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA temp_store=MEMORY");
                stmt.execute("PRAGMA mmap_size=67108864"); // 64MB memory map
                stmt.execute("PRAGMA cache_size=-2000"); // 2MB cache
            }

            // Create tables
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_INDEX_SQL);
            }

            // Pre-load all balances into the in-memory cache
            loadAllBalancesIntoCache(persistentConnection);

            // Initialize transaction log (shares connection and executor)
            transactionLog = new TransactionLog(persistentConnection, asyncExecutor);
            transactionLog.initialize();

            initialized = true;
            LOGGER.info("SQLite database initialized successfully. Cached {} player balances.",
                balanceCache.size());
        } catch (SQLException e) {
            LOGGER.error("CRITICAL: Failed to initialize SQLite database!", e);
            throw new RuntimeException("Solidus economy database initialization failed", e);
        }
    }

    /**
     * Pre-loads all existing balances and player names from the database
     * into the in-memory cache.
     */
    private void loadAllBalancesIntoCache(Connection conn) throws SQLException {
        String sql = "SELECT uuid, player_name, balance FROM player_balances";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                String name = rs.getString("player_name");
                double balance = rs.getDouble("balance");
                balanceCache.put(uuid, balance);
                if (name != null && !name.isEmpty()) {
                    playerNameCache.put(uuid, name);
                }
            }
        }
    }

    /**
     * Shuts down the async executor gracefully and closes the persistent connection.
     * All pending database writes are flushed before shutdown completes.
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                LOGGER.warn("Economy executor forced shutdown after timeout.");
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close persistent connection
        if (persistentConnection != null) {
            try {
                persistentConnection.close();
                LOGGER.info("Economy database connection closed.");
            } catch (SQLException e) {
                LOGGER.error("Failed to close economy database connection", e);
            }
        }

        LOGGER.info("SQLite storage shut down complete.");
    }

    // -- Read Operations ---------------------------------

    /**
     * Retrieves a player's balance from the in-memory cache.
     *
     * Thread Safety Strategy (v2 - Race Condition Fix):
     * - Cache reads (balanceCache.get) happen on the CALLER thread - safe due to
     *   ConcurrentHashMap's per-key atomicity guarantees.
     * - Player name updates are scheduled on the executor (fire-and-forget)
     *   to prevent caller-thread writes from racing with executor-thread writes.
     * - New player creation is delegated to the executor via supplyAsync(),
     *   so that balanceCache.putIfAbsent() and DB persist both happen on the
     *   same single-threaded executor that serializes all mutations.
     * - A double-check pattern inside the executor prevents redundant persists
     *   when two threads race on the same new UUID.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name (for record creation)
     * @return CompletableFuture containing the player's balance
     */
    public CompletableFuture<Double> getBalance(UUID uuid, String playerName) {
        ensureInitialized();

        // Schedule name refresh via executor (fire-and-forget, ordered with other mutations)
        // This prevents the caller thread from writing playerNameCache directly,
        // which could race with executor-thread writes from persistBalance().
        if (playerName != null && !playerName.isEmpty()) {
            asyncExecutor.execute(() -> {
                playerNameCache.put(uuid, playerName);
            });
        }

        // Cache read - happens on caller thread, safe due to ConcurrentHashMap atomicity
        Double balance = balanceCache.get(uuid);
        if (balance != null) {
            return CompletableFuture.completedFuture(balance);
        }

        // New player: delegate to executor to prevent race on cache writes.
        // All cache mutations and DB persist happen on the single-threaded executor,
        // guaranteeing sequential ordering with addBalance/subtractBalance/setBalance.
        return CompletableFuture.supplyAsync(() -> {
            // Double-check: another thread may have populated this while we waited
            Double cached = balanceCache.get(uuid);
            if (cached != null) {
                return cached;
            }

            // Optimistic cache write - putIfAbsent prevents overwriting if raced
            balanceCache.putIfAbsent(uuid, CurrencyUtil.getStartingBalance());

            // Persist new player directly (we're already on the executor thread)
            persistNewPlayerDirect(uuid, playerName, CurrencyUtil.getStartingBalance());

            return CurrencyUtil.getStartingBalance();
        }, asyncExecutor);
    }

    /**
     * Retrieves the top N players by balance for leaderboard display
     * (first page of {@link #getTopBalances(int, int)}).
     *
     * Uses the SQLite idx_balance_rank index for efficient sorting
     * instead of sorting the entire in-memory cache. This scales
     * much better with thousands of players since SQLite only needs
     * to scan the index and return the top N rows.
     *
     * Player names are resolved from the in-memory playerNameCache
     * for instant lookup without additional DB queries.
     *
     * @param limit Maximum number of entries to return
     * @return CompletableFuture containing list of BalanceEntry objects
     */
    public CompletableFuture<List<BalanceEntry>> getTopBalances(int limit) {
        return getTopBalances(limit, 0);
    }

    /**
     * Retrieves a page of the leaderboard with pagination pushed down to
     * SQLite (LIMIT/OFFSET on the idx_balance_rank index), so fetching page
     * 50 costs the same as page 1 no matter how many players are registered.
     *
     * <p>Ranks are global and continue across pages: with an offset of 10,
     * the first returned entry is rank 11. A negative offset is clamped to 0,
     * and an offset beyond the end simply returns an empty list.</p>
     *
     * Player names are resolved from the in-memory playerNameCache
     * for instant lookup without additional DB queries.
     *
     * @param limit  Maximum number of entries to return (page size)
     * @param offset Number of higher-ranked entries to skip (0-based)
     * @return CompletableFuture containing list of BalanceEntry objects
     */
    public CompletableFuture<List<BalanceEntry>> getTopBalances(int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            List<BalanceEntry> entries = new ArrayList<>();
            String sql = "SELECT uuid, player_name, balance FROM player_balances "
                + "ORDER BY balance DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = Math.max(0, offset);
                    while (rs.next()) {
                        rank++;
                        String name = rs.getString("player_name");
                        // Fallback to playerNameCache if DB name is empty
                        if (name == null || name.isEmpty()) {
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            name = playerNameCache.getOrDefault(uuid, "Unknown");
                        }
                        entries.add(new BalanceEntry(rank, name, rs.getDouble("balance")));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to get top balances from database", e);
                // Fallback to in-memory sort if DB query fails (offset honored
                // with skip so pages stay aligned even on the degraded path)
                return balanceCache.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .skip(Math.max(0, offset))
                    .limit(limit)
                    .collect(ArrayList<BalanceEntry>::new,
                        (list, entry) -> {
                            String name = playerNameCache.getOrDefault(entry.getKey(), "Unknown");
                            list.add(new BalanceEntry(Math.max(0, offset) + list.size() + 1, name, entry.getValue()));
                        },
                        ArrayList::addAll);
            }
            return entries;
        }, asyncExecutor);
    }

    /**
     * Counts all registered economy entries (players with a balance row).
     *
     * <p>Used together with {@link #getTopBalances(int, int)} to render
     * "Page X/Y" footers without loading any player rows. Runs as a cheap
     * COUNT(*) on player_balances, with the in-memory cache size as the
     * fallback if the query fails.</p>
     *
     * @return CompletableFuture with the total number of balance entries
     */
    public CompletableFuture<Integer> getBalanceEntryCount() {
        return CompletableFuture.supplyAsync(() -> {
            ensureInitialized();
            String sql = "SELECT COUNT(*) FROM player_balances";
            try (Statement stmt = persistentConnection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to count balance entries from database", e);
            }
            return balanceCache.size();
        }, asyncExecutor);
    }

    // -- Write Operations (via Single-Threaded Executor Queue) --

    /**
     * Sets a player's balance to an exact value.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name
     * @param amount     The new balance value
     * @return CompletableFuture indicating success
     */
    public CompletableFuture<Boolean> setBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        final double roundedAmount = CurrencyUtil.round(amount);
        if (!CurrencyUtil.isValidBalance(roundedAmount)) {
            LOGGER.warn("Invalid balance amount rejected: {}", roundedAmount);
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            Double previousBalance = balanceCache.get(uuid);
            balanceCache.put(uuid, roundedAmount);
            boolean success = persistBalance(uuid, playerName, roundedAmount);
            if (!success) {
                if (previousBalance != null) {
                    balanceCache.put(uuid, previousBalance);
                } else {
                    balanceCache.remove(uuid);
                }
                LOGGER.error("Failed to persist balance for UUID: {}. Cache rolled back to previous value.", uuid);
            }
            return success;
        }, asyncExecutor);
    }

    /**
     * Atomically adds an amount to a player's balance.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name
     * @param amount     The amount to add (must be positive)
     * @return CompletableFuture with the new balance, or -1 on failure
     */
    public CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        final double roundedAmount = CurrencyUtil.round(amount);

        return CompletableFuture.supplyAsync(() -> {
            double currentBalance = balanceCache.getOrDefault(uuid, CurrencyUtil.getStartingBalance());
            double newBalance = CurrencyUtil.round(currentBalance + roundedAmount);

            if (!CurrencyUtil.isValidBalance(newBalance)) {
                LOGGER.warn("Balance overflow prevented for UUID: {} (would be {})",
                    uuid, newBalance);
                return -1.0;
            }

            balanceCache.put(uuid, newBalance);
            boolean success = persistBalance(uuid, playerName, newBalance);
            if (!success) {
                balanceCache.put(uuid, currentBalance);
                LOGGER.error("Failed to persist add-balance for UUID: {}. Cache rolled back.", uuid);
                return -1.0;
            }

            return newBalance;
        }, asyncExecutor);
    }

    /**
     * Atomically subtracts an amount from a player's balance.
     *
     * @param uuid       The player's unique ID
     * @param playerName The player's display name
     * @param amount     The amount to subtract (must be positive)
     * @return CompletableFuture with the new balance, or -1 on failure/insufficient funds
     */
    public CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        final double roundedAmount = CurrencyUtil.round(amount);

        return CompletableFuture.supplyAsync(() -> {
            double currentBalance = balanceCache.getOrDefault(uuid, CurrencyUtil.getStartingBalance());

            if (currentBalance < roundedAmount) {
                return -1.0;
            }

            double newBalance = CurrencyUtil.round(currentBalance - roundedAmount);
            balanceCache.put(uuid, newBalance);
            boolean success = persistBalance(uuid, playerName, newBalance);
            if (!success) {
                balanceCache.put(uuid, currentBalance);
                LOGGER.error("Failed to persist subtract-balance for UUID: {}. Cache rolled back.", uuid);
                return -1.0;
            }

            return newBalance;
        }, asyncExecutor);
    }

    // -- Atomic Transfers ---------------------------------

    /** Outcome status of an atomic {@link #transferAtomic} attempt. */
    public enum TransferStatus {
        /** Both legs settled and committed. */
        SUCCESS,
        /** Sender had insufficient funds - nothing was moved. */
        INSUFFICIENT_FUNDS,
        /** Crediting the receiver would break the balance cap - nothing was moved. */
        RECEIVER_OVERFLOW,
        /** Database error - nothing was moved (transaction rolled back). */
        PERSIST_ERROR
    }

    /**
     * Immutable result of an atomic transfer.
     *
     * @param status             outcome status
     * @param senderNewBalance   sender balance after a successful transfer (0 on failure)
     * @param receiverNewBalance receiver balance after a successful transfer (0 on failure)
     */
    public record TransferOutcome(TransferStatus status, double senderNewBalance, double receiverNewBalance) {}

    /**
     * Moves {@code amount} from one account to another as a single atomic unit.
     *
     * <p>Both the deduction from the sender and the credit to the receiver run
     * inside one SQLite transaction ({@code BEGIN IMMEDIATE ... COMMIT}) on the
     * shared persistent connection. Because the whole pair executes as one
     * transaction on the single-threaded economy executor:</p>
     *
     * <ul>
     *   <li>A crash between the two legs can no longer destroy money - SQLite's
     *       WAL journaling recovers to a state that either contains both
     *       updates or neither.</li>
     *   <li>Concurrent transfers cannot interleave between the legs - the
     *       executor serializes all storage tasks and the IMMEDIATE transaction
     *       holds the write lock for the whole pair.</li>
     *   <li>On any failure (insufficient funds, receiver overflow, database
     *       error) the transaction is rolled back and the in-memory cache is
     *       left untouched, so cache and database stay consistent.</li>
     * </ul>
     *
     * <p>Semantics mirror the standalone subtract/add pair this method replaces:
     * a sender with no row is treated as holding the starting balance, a
     * receiver with no row is created with starting balance + amount, and all
     * amounts are rounded through {@link CurrencyUtil#round(double)}.</p>
     *
     * @param senderUuid   The sender's unique ID
     * @param senderName   The sender's display name
     * @param receiverUuid The receiver's unique ID
     * @param receiverName The receiver's display name
     * @param amount       The amount to move (must be positive)
     * @return CompletableFuture with the {@link TransferOutcome}
     */
    public CompletableFuture<TransferOutcome> transferAtomic(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount) {
        ensureInitialized();
        final double roundedAmount = CurrencyUtil.round(amount);

        return CompletableFuture.supplyAsync(() ->
            executeAtomicTransfer(senderUuid, senderName, receiverUuid, receiverName, roundedAmount),
            asyncExecutor);
    }

    /**
     * Executes the atomic transfer on the executor thread. Must only be called
     * from the single-threaded executor (the persistent connection is not
     * thread-safe and the cache contract requires executor-only writes).
     *
     * <p>The transaction is driven with raw {@code BEGIN IMMEDIATE} /
     * {@code COMMIT} / {@code ROLLBACK} statements on the persistent
     * connection. The connection stays in autocommit mode from the driver's
     * perspective; SQLite itself suspends autocommit from the moment the raw
     * {@code BEGIN} runs until the matching {@code COMMIT}/{@code ROLLBACK},
     * which keeps the driver's transaction state machine out of the way.</p>
     */
    private TransferOutcome executeAtomicTransfer(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double roundedAmount) {

        if (!CurrencyUtil.isValidAmount(roundedAmount)) {
            LOGGER.warn("Atomic transfer rejected: invalid amount {}", roundedAmount);
            return new TransferOutcome(TransferStatus.PERSIST_ERROR, 0, 0);
        }
        if (senderUuid.equals(receiverUuid)) {
            LOGGER.warn("Atomic transfer rejected: sender and receiver are the same account");
            return new TransferOutcome(TransferStatus.PERSIST_ERROR, 0, 0);
        }

        try {
            // Grab the write lock up front so no external writer can slip
            // between the deduct and the credit leg.
            try (Statement tx = persistentConnection.createStatement()) {
                tx.execute("BEGIN IMMEDIATE");
            }

            double senderCurrent = readBalanceForTransfer(senderUuid);
            double receiverCurrent = readBalanceForTransfer(receiverUuid);

            double senderNew = CurrencyUtil.round(senderCurrent - roundedAmount);
            if (senderCurrent < roundedAmount || senderNew < 0) {
                rollbackTransfer();
                return new TransferOutcome(TransferStatus.INSUFFICIENT_FUNDS, 0, 0);
            }

            double receiverNew = CurrencyUtil.round(receiverCurrent + roundedAmount);
            if (!CurrencyUtil.isValidBalance(receiverNew)) {
                LOGGER.warn("Atomic transfer rejected: receiver balance would become {} (cap exceeded)", receiverNew);
                rollbackTransfer();
                return new TransferOutcome(TransferStatus.RECEIVER_OVERFLOW, 0, 0);
            }

            upsertBalanceInTx(senderUuid, senderName, senderNew);
            upsertBalanceInTx(receiverUuid, receiverName, receiverNew);

            try (Statement tx = persistentConnection.createStatement()) {
                tx.execute("COMMIT");
            }

            // Committed: publish both new balances to the in-memory cache.
            balanceCache.put(senderUuid, senderNew);
            balanceCache.put(receiverUuid, receiverNew);
            if (senderName != null && !senderName.isEmpty()) {
                playerNameCache.put(senderUuid, senderName);
            }
            if (receiverName != null && !receiverName.isEmpty()) {
                playerNameCache.put(receiverUuid, receiverName);
            }
            return new TransferOutcome(TransferStatus.SUCCESS, senderNew, receiverNew);
        } catch (SQLException e) {
            LOGGER.error("Atomic transfer failed - rolling back. Sender: {}, Receiver: {}",
                senderUuid, receiverUuid, e);
            rollbackTransfer();
            return new TransferOutcome(TransferStatus.PERSIST_ERROR, 0, 0);
        }
    }

    /**
     * Reads one account's balance inside the open transfer transaction.
     * A missing row is treated as holding the starting balance, mirroring the
     * cache-defaulting behavior of the standalone add/subtract operations.
     */
    private double readBalanceForTransfer(UUID uuid) throws SQLException {
        String sql = "SELECT balance FROM player_balances WHERE uuid = ?";
        try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                }
            }
        }
        return CurrencyUtil.getStartingBalance();
    }

    /**
     * Writes one account's balance inside the open transfer transaction using
     * the same UPSERT shape as {@link #persistBalance} so new and existing
     * rows are handled identically.
     */
    private void upsertBalanceInTx(UUID uuid, String playerName, double balance) throws SQLException {
        String upsertSql = """
            INSERT INTO player_balances (uuid, player_name, balance, last_updated)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                balance = excluded.balance,
                player_name = excluded.player_name,
                last_updated = excluded.last_updated
        """;
        try (PreparedStatement ps = persistentConnection.prepareStatement(upsertSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setDouble(3, balance);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Rolls back the open transfer transaction. Never throws: if the rollback
     * itself fails, the error is logged as CATASTROPHIC and the database
     * journal resolves the transaction when the connection is recovered.
     */
    private void rollbackTransfer() {
        try (Statement tx = persistentConnection.createStatement()) {
            tx.execute("ROLLBACK");
        } catch (SQLException e) {
            LOGGER.error("CATASTROPHIC: rollback of a failed transfer did not complete - "
                + "cache left untouched; the database journal will resolve the transaction on restart.", e);
        }
    }

    /**
     * Checks whether a player has at least the specified amount.
     *
     * @param uuid   The player's unique ID
     * @param amount The amount to check against
     * @return CompletableFuture with true if the player can afford it
     */
    public CompletableFuture<Boolean> hasBalance(UUID uuid, double amount) {
        return getBalance(uuid, "").thenApply(balance -> balance >= amount);
    }

    // -- Internal Persistence Helpers ---------------------

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SQLiteStorage accessed before initialization!");
        }
    }

    /**
     * Persists a balance update to SQLite using the persistent connection.
     * Called from the single-threaded executor - no locking needed.
     * Also updates the playerNameCache to keep names current.
     *
     * Uses UPSERT to handle both new and existing players in a single statement,
     * eliminating the need for separate INSERT/UPDATE logic and guaranteeing
     * correct ordering even for newly created player entries.
     */
    private boolean persistBalance(UUID uuid, String playerName, double balance) {
        if (playerName != null && !playerName.isEmpty()) {
            playerNameCache.put(uuid, playerName);
        }

        String upsertSql = """
            INSERT INTO player_balances (uuid, player_name, balance, last_updated)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                balance = excluded.balance,
                player_name = excluded.player_name,
                last_updated = excluded.last_updated
        """;
        try (PreparedStatement ps = persistentConnection.prepareStatement(upsertSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setDouble(3, balance);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOGGER.error("Failed to persist balance for UUID: {}", uuid, e);
            return false;
        }
    }

    /**
     * Persists a new player record to SQLite directly on the current thread.
     *
     * Must be called from the asyncExecutor thread (no submission to executor here
     * - the caller is already running on it).
     *
     * Uses UPSERT (INSERT ... ON CONFLICT DO UPDATE) to guarantee correct ordering:
     * since this runs on the same executor thread as all other mutations, it executes
     * sequentially with addBalance/subtractBalance/setBalance for the same UUID.
     * The UPSERT ensures the player row is created or updated atomically regardless
     * of whether a concurrent operation already inserted the row.
     */
    private void persistNewPlayerDirect(UUID uuid, String playerName, double balance) {
        String upsertSql = """
            INSERT INTO player_balances (uuid, player_name, balance, last_updated)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                last_updated = excluded.last_updated
        """;
        try (PreparedStatement ps = persistentConnection.prepareStatement(upsertSql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.setDouble(3, balance);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to persist new player: {}", uuid, e);
        }
    }

    /**
     * Returns the transaction log instance.
     * Available after initialize() has been called.
     */
    public TransactionLog getTransactionLog() {
        return transactionLog;
    }

    /**
     * Returns an unmodifiable view of the player name cache for offline player lookups.
     * Maps UUID -> last known player name.
     *
     * The returned map is read-only to prevent external code from bypassing
     * the single-threaded executor contract for cache mutations. All writes
     * to playerNameCache must go through the executor thread.
     */
    public Map<UUID, String> getPlayerNameCache() {
        return Collections.unmodifiableMap(playerNameCache);
    }

    /**
     * Immutable data class representing a leaderboard entry.
     */
    public record BalanceEntry(int rank, String playerName, double balance) {}
}
