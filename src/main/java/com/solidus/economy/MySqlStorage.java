package com.solidus.economy;

import com.solidus.util.CurrencyUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
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
 * MySQL / MariaDB storage backend for the Solidus economy (DB scaling plan,
 * Phase 2 — the multi-server release).
 *
 * <p><b>Why a shared database is the lock:</b> on a network, N servers each
 * run their own executor — "the executor is the lock" no longer holds. Every
 * mutation here is therefore a single atomic SQL transaction on the shared
 * database, guarded by row locks ({@code SELECT ... FOR UPDATE}) acquired in
 * deterministic (lower-UUID-first) order. Two servers racing the same account
 * can never interleave the deduct and credit legs: the row lock serializes
 * them, and the local single-thread executor only preserves ordering and
 * keeps the server tick thread non-blocking.</p>
 *
 * <p><b>Money exactness:</b> balances live in {@code DECIMAL(18,2)} columns
 * and cross the JDBC boundary through {@link Money} ({@link java.math.BigDecimal}),
 * eliminating float drift on aggregates across servers. The public API stays
 * {@code double} (companion compatibility, owner rule) — conversions happen
 * only inside this class.</p>
 *
 * <p><b>Caching policy:</b> the in-memory balance cache is a FALLBACK ONLY
 * (used when a read fails) — never an authority. Every balance read goes to
 * the database, so a value written by another server is immediately visible.
 * The player-name cache keeps the SQLite semantics (offline lookups,
 * command suggestions). Redis-backed L1/L2 caching arrives in 2.2.1.</p>
 *
 * <p><b>Failure model:</b> connection loss degrades reads to the fallback
 * cache (stale but non-zero — balances never silently read as 0) and fails
 * writes cleanly (callers already handle failed futures). Deadlocks
 * (InnoDB 1213 / lock-wait 1205) are retried twice with backoff; a transfer
 * that cannot lock both rows rolls back completely and reports
 * {@code PERSIST_ERROR} — nothing ever moves half-way.</p>
 */
public class MySqlStorage implements StorageBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySqlStorage.class);

    private static final String CREATE_PLAYER_BALANCES_SQL = """
        CREATE TABLE IF NOT EXISTS player_balances (
            uuid CHAR(36) PRIMARY KEY NOT NULL,
            player_name VARCHAR(64) NOT NULL,
            balance DECIMAL(18,2) NOT NULL DEFAULT 0.00,
            last_updated BIGINT NOT NULL,
            KEY idx_balance_rank (balance DESC)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    /**
     * Idempotency-key table (DB scaling plan §5.1): reserved for cross-server
     * retry-safe operations. Created up-front so 2.2.1 can adopt it without a
     * schema migration; not yet written by the primitives in 2.2.0.
     */
    private static final String CREATE_OPERATIONS_SQL = """
        CREATE TABLE IF NOT EXISTS operations (
            op_id CHAR(36) PRIMARY KEY NOT NULL,
            account_uuid CHAR(36),
            op_type VARCHAR(32) NOT NULL,
            request_hash VARCHAR(128),
            result_state TEXT,
            created_at BIGINT NOT NULL,
            KEY idx_operations_account (account_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    private static final String SELECT_BALANCE = "SELECT balance FROM player_balances WHERE uuid = ?";
    private static final String SELECT_BALANCE_FOR_UPDATE =
        "SELECT balance FROM player_balances WHERE uuid = ? FOR UPDATE";
    private static final String INSERT_IGNORE_BALANCE =
        "INSERT IGNORE INTO player_balances (uuid, player_name, balance, last_updated) VALUES (?, ?, ?, ?)";
    private static final String UPSERT_BALANCE = """
        INSERT INTO player_balances (uuid, player_name, balance, last_updated)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            balance = VALUES(balance),
            player_name = VALUES(player_name),
            last_updated = VALUES(last_updated)
        """;
    private static final String UPDATE_NAME =
        "UPDATE player_balances SET player_name = COALESCE(NULLIF(?, ''), player_name), last_updated = ? WHERE uuid = ?";

    private final StorageConfig.MySqlSettings settings;
    private final ExecutorService asyncExecutor;
    private final ConcurrentHashMap<UUID, Double> balanceFallbackCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerNameCache = new ConcurrentHashMap<>();

    private volatile HikariDataSource dataSource;
    private volatile boolean initialized = false;
    private TransactionLog transactionLog;

    /**
     * Constructs the backend. {@link #initialize()} opens the pool, creates
     * the schema and preloads caches — and FAILS CLOSED with a clear error
     * if the database is unreachable (a network server must never boot with
     * a silently wrong economy).
     */
    public MySqlStorage(StorageConfig.MySqlSettings settings) {
        this.settings = settings;
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-MySQL-Economy-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void initialize() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(jdbcUrl());
        hikari.setUsername(settings.user());
        hikari.setPassword(settings.password());
        hikari.setMaximumPoolSize(settings.maxPoolSize());
        hikari.setMinimumIdle(Math.max(2, settings.maxPoolSize() / 2));
        hikari.setConnectionTimeout(settings.connectionTimeoutMs());
        hikari.setMaxLifetime(1_800_000);      // 30 min — shorter than typical server wait_timeout
        hikari.setKeepaliveTime(30_000);       // keep idle pool connections alive
        hikari.setPoolName("Solidus-MySQL");
        try {
            this.dataSource = new HikariDataSource(hikari);
        } catch (RuntimeException e) {
            throw new RuntimeException(
                "Solidus MySQL backend: failed to create the connection pool for "
                    + settings.host() + ":" + settings.port() + "/" + settings.database()
                    + " (check storage.json credentials) — economy NOT started.", e);
        }

        // Fail closed: verify connectivity before any economy traffic.
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (SQLException e) {
            dataSource.close();
            throw new RuntimeException(
                "Solidus MySQL backend: cannot reach the database at "
                    + settings.host() + ":" + settings.port() + "/" + settings.database()
                    + " — economy NOT started. Fix storage.json or set type back to \"sqlite\".", e);
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_PLAYER_BALANCES_SQL);
            stmt.execute(CREATE_OPERATIONS_SQL);
        } catch (SQLException e) {
            dataSource.close();
            throw new RuntimeException("Solidus MySQL backend: schema creation failed", e);
        }

        // Transaction log + offline notifications on the shared database,
        // with pooled connections and the MySQL dialect (DECIMAL money).
        this.transactionLog = new TransactionLog(dataSource::getConnection, asyncExecutor);
        transactionLog.initialize(TransactionLog.Dialect.MYSQL);

        preloadCaches();

        initialized = true;
        LOGGER.info("MySQL/MariaDB economy storage initialized ({}:{}/{}). Cached {} player names.",
            settings.host(), settings.port(), settings.database(), playerNameCache.size());
    }

    private String jdbcUrl() {
        String url = "jdbc:mariadb://" + settings.host() + ":" + settings.port()
            + "/" + settings.database() + "?useSsl=" + settings.useSsl();
        if (settings.useSsl()) {
            // Trust-server-certificate is NOT enabled: operators supplying
            // useSsl=true are expected to have a verifiable certificate or to
            // add serverSslCert themselves (documented in docs/sql/mysql/).
        }
        return url;
    }

    private void preloadCaches() {
        String sql = "SELECT uuid, player_name, balance FROM player_balances";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid = safeUuid(rs.getString("uuid"));
                if (uuid == null) continue;
                String name = rs.getString("player_name");
                if (name != null && !name.isEmpty()) {
                    playerNameCache.put(uuid, name);
                }
                balanceFallbackCache.put(uuid, rs.getDouble("balance"));
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to preload player caches — continuing with empty caches", e);
        }
    }

    // -- Reads (database-first; caches are fallback only) -----------------

    @Override
    public CompletableFuture<Double> getBalance(UUID uuid, String playerName) {
        ensureInitialized();
        scheduleNameRefresh(uuid, playerName);
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                Double balance = selectBalance(conn, uuid);
                if (balance == null) {
                    // New player: create with the starting balance (race-safe)
                    insertNewPlayerRow(conn, uuid, playerName);
                    balance = selectBalance(conn, uuid);
                    if (balance == null) {
                        balance = CurrencyUtil.getStartingBalance();
                    }
                }
                balanceFallbackCache.put(uuid, balance);
                return balance;
            } catch (SQLException e) {
                LOGGER.error("Balance read failed for {} — serving degraded cache value", uuid, e);
                Double cached = balanceFallbackCache.get(uuid);
                return cached != null ? cached : CurrencyUtil.getStartingBalance();
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<List<SQLiteStorage.BalanceEntry>> getTopBalances(int limit, int offset) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            List<SQLiteStorage.BalanceEntry> entries = new ArrayList<>();
            // System accounts (bid escrow) are excluded from leaderboards.
            String sql = "SELECT uuid, player_name, balance FROM player_balances "
                + "WHERE uuid <> ? ORDER BY balance DESC LIMIT ? OFFSET ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, EscrowAccount.UUID_ZERO.toString());
                ps.setInt(2, limit);
                ps.setInt(3, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = Math.max(0, offset);
                    while (rs.next()) {
                        rank++;
                        entries.add(new SQLiteStorage.BalanceEntry(
                            safeUuid(rs.getString("uuid")), rank,
                            rs.getString("player_name"), rs.getDouble("balance")));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Leaderboard read failed — serving degraded cache page", e);
                return balanceFallbackCache.entrySet().stream()
                    .filter(entry -> !EscrowAccount.isSystemAccount(entry.getKey()))
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .skip(Math.max(0, offset))
                    .limit(limit)
                    .collect(ArrayList<SQLiteStorage.BalanceEntry>::new,
                        (list, entry) -> {
                            String name = playerNameCache.getOrDefault(entry.getKey(), "Unknown");
                            list.add(new SQLiteStorage.BalanceEntry(entry.getKey(),
                                Math.max(0, offset) + list.size() + 1, name, entry.getValue()));
                        },
                        ArrayList::addAll);
            }
            return entries;
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Integer> getBalanceEntryCount() {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM player_balances";
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                LOGGER.error("Entry count read failed — serving degraded cache size", e);
            }
            return balanceFallbackCache.size();
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<SQLiteStorage.EconomyStats> getEconomyStats() {
        ensureInitialized();
        return CompletableFuture.supplyAsync(() -> {
            // Gini for ascending-ranked balances: G = 2*SUM(rank*balance) / (n*SUM(balance)) - (n+1)/n
            // rn_balance = balance * ROW_NUMBER() so a single aggregate pass
            // computes SUM(rank*balance) without mixing window and aggregate
            // functions (works identically on MariaDB 10.2+ and MySQL 8+).
            String sql = """
                SELECT COUNT(*) AS n,
                       COALESCE(AVG(balance), 0) AS avg_balance,
                       COALESCE(SUM(balance), 0) AS total_supply,
                       CASE WHEN COUNT(*) > 1 AND COALESCE(SUM(balance), 0) > 0
                            THEN GREATEST(0.0, LEAST(1.0,
                                 (2.0 * SUM(rn_balance)) / (COUNT(*) * SUM(balance)) - (COUNT(*) + 1.0) / COUNT(*)))
                            ELSE 0.0 END AS gini
                FROM (
                    SELECT balance, balance * ROW_NUMBER() OVER (ORDER BY balance) AS rn_balance
                    FROM player_balances
                ) t
                """;
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    int count = rs.getInt("n");
                    if (count <= 0) {
                        return new SQLiteStorage.EconomyStats(0, 0.0, 0.0, 0.0);
                    }
                    double avg = rs.getDouble("avg_balance");
                    double supply = rs.getDouble("total_supply");
                    double gini = rs.getDouble("gini");
                    if (Double.isNaN(avg) || Double.isInfinite(avg)) avg = 0.0;
                    if (Double.isNaN(supply) || Double.isInfinite(supply)) supply = 0.0;
                    if (Double.isNaN(gini) || Double.isInfinite(gini)) gini = 0.0;
                    return new SQLiteStorage.EconomyStats(count, avg, supply, gini);
                }
            } catch (SQLException e) {
                LOGGER.error("Economy stats read failed — serving degraded cache aggregates", e);
            }
            int cachedCount = balanceFallbackCache.size();
            double cachedSupply = 0.0;
            for (double v : balanceFallbackCache.values()) {
                cachedSupply += v;
            }
            return cachedCount > 0
                ? new SQLiteStorage.EconomyStats(cachedCount, cachedSupply / cachedCount, cachedSupply, 0.0)
                : new SQLiteStorage.EconomyStats(0, 0.0, 0.0, 0.0);
        }, asyncExecutor);
    }

    // -- Writes (locked read-modify-write inside DB transactions) ----------

    @Override
    public CompletableFuture<Boolean> setBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        final double roundedAmount = CurrencyUtil.round(amount);
        if (!CurrencyUtil.isValidBalance(roundedAmount)) {
            LOGGER.warn("Invalid balance amount rejected: {}", roundedAmount);
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(UPSERT_BALANCE)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, playerName == null ? "" : playerName);
                    ps.setBigDecimal(3, Money.of(roundedAmount).toDecimal());
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                balanceFallbackCache.put(uuid, roundedAmount);
                if (playerName != null && !playerName.isEmpty()) {
                    playerNameCache.put(uuid, playerName);
                }
                return true;
            } catch (SQLException e) {
                LOGGER.error("Failed to persist balance for UUID: {}", uuid, e);
                return false;
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(
            () -> addOrSubtract(uuid, playerName, CurrencyUtil.round(amount), false),
            asyncExecutor);
    }

    @Override
    public CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount) {
        ensureInitialized();
        return CompletableFuture.supplyAsync(
            () -> addOrSubtract(uuid, playerName, CurrencyUtil.round(amount), true),
            asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> hasBalance(UUID uuid, double amount) {
        return getBalance(uuid, "").thenApply(balance -> balance >= amount);
    }

    /**
     * Shared body of add/subtract: locks the account row, recomputes the
     * balance EXACTLY (BigDecimal), enforces the 0..MAX_BALANCE cap, writes.
     * Returns the new balance, or -1 on any rejection/failure (SQLite parity).
     */
    private double addOrSubtract(UUID uuid, String playerName, double roundedAmount, boolean subtract) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                double current = lockAndReadBalance(conn, uuid, playerName);
                Money newMoney = subtract
                    ? Money.of(current).subtract(Money.of(roundedAmount))
                    : Money.of(current).add(Money.of(roundedAmount));
                if (newMoney.isNegative() || newMoney.isOverCap()) {
                    conn.rollback();
                    return -1.0;
                }
                if (!updateBalanceCapped(conn, uuid, playerName, newMoney)) {
                    conn.rollback();
                    return -1.0;
                }
                conn.commit();
                balanceFallbackCache.put(uuid, newMoney.toDouble());
                return newMoney.toDouble();
            } catch (SQLException e) {
                rollbackQuietly(conn);
                LOGGER.error("add/subtract failed for UUID: {}", uuid, e);
                return -1.0;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException outer) {
            LOGGER.error("Connection failure during add/subtract for UUID: {}", uuid, outer);
            return -1.0;
        }
    }

    // -- Atomic transfers ---------------------------------------------------

    @Override
    public CompletableFuture<SQLiteStorage.TransferOutcome> transferAtomic(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount) {
        return transferAtomicWithLedger(senderUuid, senderName, receiverUuid, receiverName,
            amount, List.of());
    }

    @Override
    public CompletableFuture<SQLiteStorage.TransferOutcome> transferAtomicWithLedger(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double amount,
            List<SQLiteStorage.AtomicLedgerRow> ledgerRows) {
        ensureInitialized();
        final double roundedAmount = CurrencyUtil.round(amount);
        final List<SQLiteStorage.AtomicLedgerRow> rows =
            ledgerRows != null ? ledgerRows : List.of();

        return CompletableFuture.supplyAsync(() ->
            executeAtomicTransfer(senderUuid, senderName, receiverUuid, receiverName,
                roundedAmount, rows),
            asyncExecutor);
    }

    /**
     * One atomic SQL transaction: both legs (deduct + credit) plus the ledger
     * evidence commit together or not at all. Rows are locked in deterministic
     * (lower-UUID-first) order so two servers transferring in opposite
     * directions cannot deadlock each other; residual deadlocks / lock-wait
     * timeouts are retried twice with backoff.
     */
    private SQLiteStorage.TransferOutcome executeAtomicTransfer(
            UUID senderUuid, String senderName,
            UUID receiverUuid, String receiverName,
            double roundedAmount,
            List<SQLiteStorage.AtomicLedgerRow> ledgerRows) {

        if (!CurrencyUtil.isValidAmount(roundedAmount)) {
            LOGGER.warn("Atomic transfer rejected: invalid amount {}", roundedAmount);
            return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
        }
        if (senderUuid.equals(receiverUuid)) {
            LOGGER.warn("Atomic transfer rejected: sender and receiver are the same account");
            return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
        }

        final int maxAttempts = 3; // 1 + 2 deadlock retries (DB scaling plan §5.3)
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Deterministic lock order: the lower UUID row is locked first.
                    UUID first = senderUuid.compareTo(receiverUuid) < 0 ? senderUuid : receiverUuid;
                    UUID second = first.equals(senderUuid) ? receiverUuid : senderUuid;

                    double firstBalance = lockAndReadBalance(conn, first, nameFor(first, senderUuid, senderName, receiverName));
                    double secondBalance = lockAndReadBalance(conn, second, nameFor(second, senderUuid, senderName, receiverName));

                    double senderCurrent = first.equals(senderUuid) ? firstBalance : secondBalance;
                    double receiverCurrent = first.equals(receiverUuid) ? firstBalance : secondBalance;

                    Money senderNew = Money.of(senderCurrent).subtract(Money.of(roundedAmount));
                    if (senderCurrent < roundedAmount || senderNew.isNegative()) {
                        conn.rollback();
                        return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS, 0, 0);
                    }

                    Money receiverNew = Money.of(receiverCurrent).add(Money.of(roundedAmount));
                    if (receiverNew.isOverCap()) {
                        LOGGER.warn("Atomic transfer rejected: receiver balance would become {} (cap exceeded)",
                            receiverNew);
                        conn.rollback();
                        return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.RECEIVER_OVERFLOW, 0, 0);
                    }

                    // Compare-and-set writes (belt & braces: the row locks make
                    // the prior-balance predicate always true unless something
                    // external wrote in between — then we refuse, not corrupt).
                    if (!updateBalanceCapped(conn, senderUuid, senderName, senderNew, senderCurrent)
                            || !updateBalanceCapped(conn, receiverUuid, receiverName, receiverNew, receiverCurrent)) {
                        conn.rollback();
                        return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
                    }

                    // Ledger evidence commits WITH the money (audit 2.1.3 parity).
                    for (SQLiteStorage.AtomicLedgerRow row : ledgerRows) {
                        boolean inserted = TransactionLog.insertRowSync(
                            conn, row.type(), row.playerUuid(), row.playerName(),
                            row.targetUuid(), row.targetName(),
                            row.amount(), row.itemMaterial(), row.itemQuantity(),
                            row.description());
                        if (!inserted) {
                            LOGGER.warn("Atomic transfer: ledger insert failed - rolling back money + ledger together");
                            conn.rollback();
                            return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
                        }
                    }

                    conn.commit();

                    // Committed: refresh local fallback caches.
                    balanceFallbackCache.put(senderUuid, senderNew.toDouble());
                    balanceFallbackCache.put(receiverUuid, receiverNew.toDouble());
                    if (senderName != null && !senderName.isEmpty()) {
                        playerNameCache.put(senderUuid, senderName);
                    }
                    if (receiverName != null && !receiverName.isEmpty()) {
                        playerNameCache.put(receiverUuid, receiverName);
                    }
                    return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.SUCCESS,
                        senderNew.toDouble(), receiverNew.toDouble());
                } catch (SQLException inner) {
                    rollbackQuietly(conn);
                    if (isTransientLockFailure(inner) && attempt < maxAttempts) {
                        backoffForRetry(attempt);
                        continue; // retry the whole transaction
                    }
                    LOGGER.error("Atomic transfer failed after rollback. Sender: {}, Receiver: {}",
                        senderUuid, receiverUuid, inner);
                    return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException outer) {
                LOGGER.error("Connection failure during atomic transfer (attempt {}/{})",
                    attempt, maxAttempts, outer);
                if (isTransientLockFailure(outer) && attempt < maxAttempts) {
                    backoffForRetry(attempt);
                    continue;
                }
                return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
            }
        }
        return new SQLiteStorage.TransferOutcome(SQLiteStorage.TransferStatus.PERSIST_ERROR, 0, 0);
    }

    // -- Transaction helpers ------------------------------------------------

    /** The display name to persist for an account during a transfer. */
    private String nameFor(UUID account, UUID senderUuid, String senderName, String receiverName) {
        return account.equals(senderUuid) ? senderName : receiverName;
    }

    /**
     * Ensures the account row exists and locks it ({@code FOR UPDATE}) inside
     * the caller's transaction. A missing row is created with the starting
     * balance (mirroring SQLite semantics) — the insert is race-safe against
     * another server creating the same row concurrently.
     */
    private double lockAndReadBalance(Connection conn, UUID uuid, String playerName) throws SQLException {
        Double balance = selectBalanceForUpdate(conn, uuid);
        if (balance == null) {
            insertNewPlayerRow(conn, uuid, playerName);
            balance = selectBalanceForUpdate(conn, uuid);
            if (balance == null) {
                throw new SQLException("Account row vanished after insert: " + uuid);
            }
        }
        return balance;
    }

    private Double selectBalance(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BALANCE)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                }
            }
        }
        return null;
    }

    private Double selectBalanceForUpdate(Connection conn, UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BALANCE_FOR_UPDATE)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                }
            }
        }
        return null;
    }

    private void insertNewPlayerRow(Connection conn, UUID uuid, String playerName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_IGNORE_BALANCE)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName == null ? "" : playerName);
            ps.setBigDecimal(3, Money.of(CurrencyUtil.getStartingBalance()).toDecimal());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Writes a balance with a compare-and-set predicate on the previously
     * locked value, keeping the player name when the caller supplies none.
     *
     * @param expectedCurrent when {@code >= 0} the update also requires
     *                        {@code balance = expectedCurrent} (CAS); pass -1
     *                        to skip the predicate (row already locked)
     * @return true when exactly one row was updated
     */
    private boolean updateBalanceCapped(Connection conn, UUID uuid, String playerName,
                                        Money newBalance, double expectedCurrent) throws SQLException {
        String sql;
        if (expectedCurrent >= 0) {
            sql = "UPDATE player_balances SET balance = ?, "
                + "player_name = COALESCE(NULLIF(?, ''), player_name), last_updated = ? "
                + "WHERE uuid = ? AND balance = ?";
        } else {
            sql = "UPDATE player_balances SET balance = ?, "
                + "player_name = COALESCE(NULLIF(?, ''), player_name), last_updated = ? "
                + "WHERE uuid = ?";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, newBalance.toDecimal());
            ps.setString(2, playerName);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, uuid.toString());
            if (expectedCurrent >= 0) {
                ps.setBigDecimal(5, Money.of(expectedCurrent).toDecimal());
            }
            return ps.executeUpdate() == 1;
        }
    }

    private boolean updateBalanceCapped(Connection conn, UUID uuid, String playerName, Money newBalance) throws SQLException {
        return updateBalanceCapped(conn, uuid, playerName, newBalance, -1);
    }

    /** InnoDB deadlock (1213) / lock-wait timeout (1205) / SQLState 40001. */
    private static boolean isTransientLockFailure(SQLException e) {
        while (e != null) {
            if (e.getErrorCode() == 1205 || e.getErrorCode() == 1213) {
                return true;
            }
            if ("40001".equals(e.getSQLState())) {
                return true;
            }
            e = e.getNextException();
        }
        return false;
    }

    private void backoffForRetry(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(attempt == 1 ? 50 : 200);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOGGER.error("CATASTROPHIC: rollback of a failed operation did not complete - "
                + "the database transaction will be resolved by the server on connection close.", e);
        }
    }

    /** Fire-and-forget name refresh (ordered through the economy executor). */
    private void scheduleNameRefresh(UUID uuid, String playerName) {
        if (playerName == null || playerName.isEmpty()
                || playerName.equals(playerNameCache.get(uuid))) {
            return;
        }
        asyncExecutor.execute(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(UPDATE_NAME)) {
                ps.setString(1, playerName);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
                playerNameCache.put(uuid, playerName);
            } catch (SQLException e) {
                LOGGER.warn("Name refresh skipped for {}: {}", uuid, e.getMessage());
            }
        });
    }

    // -- Shared services / lifecycle ---------------------------------------

    @Override
    public TransactionLog getTransactionLog() {
        return transactionLog;
    }

    @Override
    public Map<UUID, String> getPlayerNameCache() {
        return Collections.unmodifiableMap(playerNameCache);
    }

    @Override
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                LOGGER.warn("MySQL economy executor forced shutdown after timeout.");
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (dataSource != null) {
            dataSource.close();
            LOGGER.info("MySQL economy connection pool closed.");
        }
        LOGGER.info("MySQL storage shut down complete.");
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MySqlStorage accessed before initialization!");
        }
    }

    /** Parses a balance-table uuid column defensively (null-safe for degraded rows). */
    private static UUID safeUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
