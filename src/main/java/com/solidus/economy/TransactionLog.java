package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Transaction Log - Persistent audit trail and offline notification system.
 *
 * Architecture:
 * - Shares the economy.db persistent connection and executor for safe,
 *   serialized access (no separate thread pool or connection needed)
 * - All transaction records are stored in SQLite for durability
 * - Offline player notifications are queued and delivered on login
 *
 * Transaction Types:
 * - SHOP_BUY:      Player purchased from the server shop
 * - SHOP_SELL:      Player sold to the server shop
 * - AUCTION_LIST:   Player listed an item on the auction house
 * - AUCTION_SOLD:   Player's auction item was purchased
 * - AUCTION_BOUGHT: Player purchased from the auction house
 * - AUCTION_EXPIRED: Player's auction listing expired
 * - PAY_SEND:       Player sent money to another player
 * - PAY_RECEIVE:    Player received money from another player
 * - DEATH_PENALTY:  Player lost money from being killed (deducted)
 * - DEATH_REWARD:   Player gained money from killing another player
 *
 * Offline Notification Flow:
 * When a transaction involves an offline player (e.g., auction seller receiving
 * payment while offline), a pending notification is stored. When the player
 * logs in, all pending notifications are delivered via chat messages.
 */
public class TransactionLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionLog.class);

    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS transaction_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            type TEXT NOT NULL,
            player_uuid TEXT NOT NULL,
            player_name TEXT NOT NULL,
            target_uuid TEXT,
            target_name TEXT,
            amount REAL NOT NULL,
            item_material TEXT,
            item_quantity INTEGER,
            description TEXT
        )
    """;
    private static final String CREATE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_transaction_player
        ON transaction_log (player_uuid, timestamp DESC)
    """;
    private static final String CREATE_NOTIFICATIONS_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS pending_notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            player_uuid TEXT NOT NULL,
            message TEXT NOT NULL
        )
    """;
    private static final String CREATE_NOTIFICATIONS_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_notifications_player
        ON pending_notifications (player_uuid)
    """;

    /**
     * SQL dialect for the log DDL (DB scaling plan Phase 2, 2.2.0).
     *
     * <p>{@link #SQLITE} keeps the historical single-file schema (AUTOINCREMENT
     * ids, REAL money columns). {@link #MYSQL} mirrors it for MySQL/MariaDB
     * (AUTO_INCREMENT ids, DECIMAL(18,2) money columns, inline KEY clauses —
     * MySQL 8 does not support CREATE INDEX IF NOT EXISTS).</p>
     */
    public enum Dialect {
        SQLITE(
            CREATE_TABLE_SQL,
            CREATE_INDEX_SQL,
            CREATE_NOTIFICATIONS_TABLE_SQL,
            CREATE_NOTIFICATIONS_INDEX_SQL
        ),
        MYSQL(
            """
            CREATE TABLE IF NOT EXISTS transaction_log (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                timestamp BIGINT NOT NULL,
                type VARCHAR(32) NOT NULL,
                player_uuid CHAR(36) NOT NULL,
                player_name VARCHAR(64) NOT NULL,
                target_uuid CHAR(36),
                target_name VARCHAR(64),
                amount DECIMAL(18,2) NOT NULL,
                item_material VARCHAR(128),
                item_quantity INTEGER,
                description TEXT,
                KEY idx_transaction_player (player_uuid, timestamp DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            null,
            """
            CREATE TABLE IF NOT EXISTS pending_notifications (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                timestamp BIGINT NOT NULL,
                player_uuid CHAR(36) NOT NULL,
                message TEXT NOT NULL,
                KEY idx_notifications_player (player_uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            null
        );

        private final String[] statements;

        Dialect(String... statements) {
            this.statements = statements;
        }

        /** DDL statements to execute at initialize(); null entries are skipped. */
        public String[] statements() {
            return statements;
        }
    }

    /** Transaction type enum for type-safe logging */
    public enum Type {
        SHOP_BUY("SHOP_BUY"),
        SHOP_SELL("SHOP_SELL"),
        AUCTION_LIST("AUCTION_LIST"),
        AUCTION_SOLD("AUCTION_SOLD"),
        AUCTION_BOUGHT("AUCTION_BOUGHT"),
        AUCTION_EXPIRED("AUCTION_EXPIRED"),
        /** Escrowed bid placed on an auction listing (amount deducted at bid time) */
        BID_PLACED("BID_PLACED"),
        /** Outbid refund - the escrowed amount returned to the previous bidder */
        BID_REFUNDED("BID_REFUNDED"),
        /** A bidding auction settled to the highest bidder (escrow released to seller) */
        AUCTION_WON("AUCTION_WON"),
        /** Direct player-to-player trade: items/money this player GAVE */
        TRADE_SEND("TRADE_SEND"),
        /** Direct player-to-player trade: items/money this player RECEIVED */
        TRADE_RECEIVE("TRADE_RECEIVE"),
        PAY_SEND("PAY_SEND"),
        PAY_RECEIVE("PAY_RECEIVE"),
        DEATH_PENALTY("DEATH_PENALTY"),
        DEATH_REWARD("DEATH_REWARD");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Type fromCode(String code) {
            for (Type t : values()) {
                if (t.code.equals(code)) return t;
            }
            // Unknown transaction type - log a warning instead of silently
            // falling back to PAY_SEND which would corrupt transaction semantics
            LOGGER.warn("Unknown transaction type code: '{}'. Defaulting to SHOP_BUY.", code);
            return SHOP_BUY; // safe fallback - SHOP_BUY is the most generic type
        }
    }

    /** Immutable record representing a single transaction entry */
    public record TransactionEntry(
        long timestamp,
        Type type,
        UUID playerUuid,
        String playerName,
        UUID targetUuid,
        String targetName,
        double amount,
        String itemMaterial,
        int itemQuantity,
        String description
    ) {}

    /**
     * Opens a JDBC connection for one log operation. Thread-safe by contract.
     * The pooled (MySQL/MariaDB) flavor borrows a connection per operation;
     * the persistent (SQLite) flavor returns the same shared connection.
     */
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    interface SqlWork<T> {
        T run(Connection conn) throws SQLException;
    }

    private final ConnectionSource connectionSource;
    private final boolean closeConnections;
    private final ExecutorService asyncExecutor;

    /**
     * SQLite flavor: one persistent connection shared with the storage
     * backend, serialized by the storage executor, never closed here.
     */
    public TransactionLog(Connection persistentConnection, ExecutorService asyncExecutor) {
        this.connectionSource = () -> persistentConnection;
        this.closeConnections = false;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Pooled flavor (MySQL/MariaDB, 2.2.0+): borrows a connection per
     * operation from the storage pool and returns it after use.
     */
    public TransactionLog(ConnectionSource connectionSource, ExecutorService asyncExecutor) {
        this.connectionSource = connectionSource;
        this.closeConnections = true;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Runs one log operation on a connection. In the persistent flavor the
     * connection is shared and MUST NOT be closed; in the pooled flavor the
     * connection is closed (returned to the pool) when the work completes.
     */
    private <T> T withConnection(SqlWork<T> work) throws SQLException {
        Connection conn = connectionSource.open();
        if (!closeConnections) {
            return work.run(conn);
        }
        try (Connection pooled = conn) {
            return work.run(pooled);
        }
    }

    /**
     * Initializes the transaction log tables using the SQLite dialect.
     */
    public void initialize() {
        initialize(Dialect.SQLITE);
    }

    /**
     * Initializes the transaction log tables with the given dialect.
     */
    public void initialize(Dialect dialect) {
        try {
            withConnection(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    for (String ddl : dialect.statements()) {
                        if (ddl != null) {
                            stmt.execute(ddl);
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize transaction log tables!", e);
        }

        // FIX v2: pending notifications are now purely database-driven.
        // The old in-memory mirror (loaded here at startup) could diverge from
        // the database and caused deliveries to wipe freshly queued rows.
    }

    /**
     * Logs a transaction to the persistent database.
     * This is fire-and-forget - no return value needed.
     *
     * <p><b>Audit 2.1.3:</b> durability-critical callers (auction settlement)
     * must NOT use this method - the row is queued as a separate executor task
     * AFTER the money movement commits, so a crash between the two leaves
     * committed money with no ledger evidence. Those callers use
     * {@code SQLiteStorage.transferAtomicWithLedger}, which inserts the ledger
     * rows INSIDE the same SQLite transaction as the balance updates.
     *
     * @param type         The transaction type
     * @param playerUuid   The primary player's UUID
     * @param playerName   The primary player's name
     * @param targetUuid   The secondary player's UUID (null if N/A)
     * @param targetName   The secondary player's name (null if N/A)
     * @param amount       The currency amount involved
     * @param itemMaterial The item material name (null if N/A)
     * @param itemQuantity The item quantity (0 if N/A)
     * @param description  A human-readable description
     */
    public void log(Type type, UUID playerUuid, String playerName,
                    UUID targetUuid, String targetName,
                    double amount, String itemMaterial, int itemQuantity,
                    String description) {
        CompletableFuture.runAsync(() -> {
            try {
                withConnection(conn -> {
                    insertRowSync(conn, type, playerUuid, playerName,
                        targetUuid, targetName, amount, itemMaterial, itemQuantity, description);
                    return null;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to log transaction: {} for player: {}", type, playerName, e);
            }
        }, asyncExecutor);
    }

    /**
     * Synchronously inserts one ledger row on the given connection.
     *
     * <p>Used two ways: (a) as the body of the async {@link #log} helper, and
     * (b) by {@code SQLiteStorage.transferAtomicWithLedger} to write auction
     * settlement evidence INSIDE the money transaction itself - money and
     * evidence then commit atomically, so the startup recovery sweep can
     * always distinguish "paid" from "never paid" listings.
     *
     * @return true if the row was inserted, false on SQLException (caller
     *         decides whether to roll back the surrounding transaction)
     */
    static boolean insertRowSync(Connection conn, Type type, UUID playerUuid, String playerName,
                                 UUID targetUuid, String targetName,
                                 double amount, String itemMaterial, int itemQuantity,
                                 String description) {
        String sql = """
            INSERT INTO transaction_log
            (timestamp, type, player_uuid, player_name, target_uuid, target_name,
             amount, item_material, item_quantity, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setString(2, type.code());
            ps.setString(3, playerUuid.toString());
            ps.setString(4, playerName);
            ps.setString(5, targetUuid != null ? targetUuid.toString() : null);
            ps.setString(6, targetName);
            ps.setDouble(7, amount);
            ps.setString(8, itemMaterial);
            ps.setInt(9, itemQuantity);
            ps.setString(10, description);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOGGER.error("Failed to log transaction: {} for player: {}", type, playerName, e);
            return false;
        }
    }

    /**
     * Gets the last N transactions for a specific player.
     *
     * @param playerUuid The player's UUID
     * @param limit      Maximum number of entries to return
     * @return CompletableFuture with a list of TransactionEntry objects
     */
    public CompletableFuture<List<TransactionEntry>> getTransactions(UUID playerUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM transaction_log WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ?";
            try {
                entries = withConnection(conn -> {
                    List<TransactionEntry> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, playerUuid.toString());
                        ps.setInt(2, limit);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                list.add(mapResultSetToEntry(rs));
                            }
                        }
                    }
                    return list;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to get transactions for player: {}", playerUuid, e);
            } catch (RuntimeException e) {
                // Audit 2.1.3: a corrupt player_uuid row (IllegalArgumentException
                // from UUID.fromString) previously escaped the SQLException-only
                // catch, failed the whole future, and silently rendered the
                // player's history empty. A single corrupt row now skips.
                LOGGER.warn("Skipping corrupt transaction row for player {}: {}",
                    playerUuid, e.getMessage());
            }
            return entries;
        }, asyncExecutor);
    }

    /**
     * Gets a page of transactions for a specific player, newest first.
     * Pagination is pushed down to SQLite (LIMIT/OFFSET on the
     * (player_uuid, timestamp DESC) index) instead of fetching the whole
     * history and slicing in memory, so page N costs the same regardless of
     * how large the ledger grows.
     *
     * @param playerUuid The player's UUID
     * @param limit      Maximum number of entries to return (page size)
     * @param offset     Number of newest entries to skip (0-based;
     *                   (page - 1) * pageSize for /transactions)
     * @return CompletableFuture with the page of TransactionEntry objects
     */
    public CompletableFuture<List<TransactionEntry>> getTransactions(UUID playerUuid, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM transaction_log WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?";
            try {
                entries = withConnection(conn -> {
                    List<TransactionEntry> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, playerUuid.toString());
                        ps.setInt(2, limit);
                        ps.setInt(3, Math.max(0, offset));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                list.add(mapResultSetToEntry(rs));
                            }
                        }
                    }
                    return list;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to get transaction page for player: {}", playerUuid, e);
            }
            return entries;
        }, asyncExecutor);
    }

    /**
     * Counts all transactions recorded for a specific player.
     * Used by /transactions to compute total pages without fetching rows.
     *
     * @param playerUuid The player's UUID
     * @return CompletableFuture with the total number of entries for the player
     */
    public CompletableFuture<Integer> countTransactions(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM transaction_log WHERE player_uuid = ?";
            try {
                return withConnection(conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, playerUuid.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                return rs.getInt(1);
                            }
                        }
                    }
                    return 0;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to count transactions for player: {}", playerUuid, e);
            }
            return 0;
        }, asyncExecutor);
    }

    /**
     * Maximum number of rows a single export may return. Exports are rare,
     * command-triggered operations, but the ledger grows for the life of the
     * server - this cap keeps a runaway export from exhausting server memory.
     * When the cap is hit, the newest rows win (ORDER BY timestamp DESC).
     */
    public static final int MAX_EXPORT_ROWS = 200_000;

    /**
     * Gets a player's transactions within a time window, newest first.
     * Used by {@code /transactions export [days]} to hand players their own
     * history as CSV. Uses the (player_uuid, timestamp DESC) index, so the
     * window scan stays efficient even on a large ledger.
     *
     * @param playerUuid  The player's UUID
     * @param sinceEpochMs Inclusive lower bound on row timestamps (millis)
     * @return CompletableFuture with matching entries, newest first
     */
    public CompletableFuture<List<TransactionEntry>> getTransactionsSince(UUID playerUuid, long sinceEpochMs) {
        return getTransactionsSince(playerUuid, sinceEpochMs, MAX_EXPORT_ROWS);
    }

    /** Windowed read with an explicit row cap (see {@link #MAX_EXPORT_ROWS}). */
    public CompletableFuture<List<TransactionEntry>> getTransactionsSince(
            UUID playerUuid, long sinceEpochMs, int maxRows) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM transaction_log "
                + "WHERE player_uuid = ? AND timestamp >= ? "
                + "ORDER BY timestamp DESC LIMIT ?";
            try {
                entries = withConnection(conn -> {
                    List<TransactionEntry> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, playerUuid.toString());
                        ps.setLong(2, sinceEpochMs);
                        ps.setInt(3, Math.max(0, maxRows));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                list.add(mapResultSetToEntry(rs));
                            }
                        }
                    }
                    return list;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to get transactions since {} for player: {}", sinceEpochMs, playerUuid, e);
            }
            return entries;
        }, asyncExecutor);
    }

    /**
     * Gets ALL players' transactions within a time window, newest first.
     * Used by the admin-only {@code /transactions exportall [days]} - the
     * full-ledger counterpart of {@link #getTransactionsSince(UUID, long)}.
     * Capped at {@link #MAX_EXPORT_ROWS} (newest rows win).
     *
     * @param sinceEpochMs Inclusive lower bound on row timestamps (millis)
     * @return CompletableFuture with matching entries across all players
     */
    public CompletableFuture<List<TransactionEntry>> getAllTransactionsSince(long sinceEpochMs) {
        return getAllTransactionsSince(sinceEpochMs, MAX_EXPORT_ROWS);
    }

    /** Windowed all-players read with an explicit row cap. */
    public CompletableFuture<List<TransactionEntry>> getAllTransactionsSince(long sinceEpochMs, int maxRows) {
        return CompletableFuture.supplyAsync(() -> {
            List<TransactionEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM transaction_log "
                + "WHERE timestamp >= ? "
                + "ORDER BY timestamp DESC LIMIT ?";
            try {
                entries = withConnection(conn -> {
                    List<TransactionEntry> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, sinceEpochMs);
                        ps.setInt(2, Math.max(0, maxRows));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                list.add(mapResultSetToEntry(rs));
                            }
                        }
                    }
                    return list;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to get all transactions since {}", sinceEpochMs, e);
            }
            return entries;
        }, asyncExecutor);
    }

    // -- CSV Export ----------------------------------------

    private static final String CSV_HEADER =
        "timestamp_ms,timestamp_utc,type,player_uuid,player_name,"
        + "target_uuid,target_name,amount,item_material,item_quantity,description";

    private static final DateTimeFormatter CSV_UTC_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Builds a RFC 4180-style CSV document from transaction entries.
     *
     * <p>Columns: timestamp_ms (sortable epoch), timestamp_utc (ISO-8601 UTC,
     * human-readable), type, player_uuid, player_name, target_uuid,
     * target_name, amount (2 decimals, Locale.ROOT), item_material,
     * item_quantity, description. Fields containing commas, quotes, or line
     * breaks are quoted with doubled inner quotes; null fields export empty.</p>
     *
     * @param entries The entries to serialize (already ordered by the caller)
     * @return The full CSV document as a string (header always present)
     */
    public static String buildCsv(List<TransactionEntry> entries) {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        for (TransactionEntry e : entries) {
            sb.append(e.timestamp()).append(',')
                .append(CSV_UTC_FORMAT.format(Instant.ofEpochMilli(e.timestamp()))).append(',')
                .append(e.type().code()).append(',')
                .append(e.playerUuid()).append(',')
                .append(csvEscape(e.playerName())).append(',')
                .append(e.targetUuid() != null ? e.targetUuid().toString() : "").append(',')
                .append(csvEscape(e.targetName())).append(',')
                .append(String.format(Locale.ROOT, "%.2f", e.amount())).append(',')
                .append(csvEscape(e.itemMaterial())).append(',')
                .append(e.itemQuantity()).append(',')
                .append(csvEscape(e.description())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Writes {@link #buildCsv(List)} to a file as UTF-8, creating parent
     * directories as needed.
     *
     * @param entries The entries to serialize
     * @param file    The target file (parent dirs created if missing)
     * @throws IOException if directories cannot be created or the file
     *                     cannot be written
     */
    public static void writeCsvFile(List<TransactionEntry> entries, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, buildCsv(entries), StandardCharsets.UTF_8);
    }

    /**
     * RFC 4180 field escaping: quotes a field when it contains a comma,
     * quote, CR, or LF, and doubles any inner quotes. Null becomes empty.
     * Package-private so the export behavior is directly unit-testable.
     *
     * <p>Audit 2.1.3 - spreadsheet formula injection guard: a field that BEGINS
     * with {@code =}, {@code +}, {@code -}, {@code @} (or TAB/CR) would be
     * passed through unquoted and executed as a formula when an admin opens
     * the export in Excel/LibreOffice. Player-controlled fields (names from
     * offline-mode servers or companion-mod ledger rows) must never reach a
     * privileged consumer as executable content, so such fields get a leading
     * apostrophe - the standard neutralizing prefix.</p>
     */
    static String csvEscape(String field) {
        if (field == null) return "";
        boolean needsQuoting = field.indexOf(',') >= 0
            || field.indexOf('"') >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0;
        boolean formulaPrefix = !field.isEmpty() && isFormulaPrefix(field.charAt(0));
        if (!needsQuoting && !formulaPrefix) return field;
        String escaped = formulaPrefix ? "'" + field : field;
        if (!needsQuoting) return escaped;
        return '"' + escaped.replace("\"", "\"\"") + '"';
    }

    /** Characters that start a spreadsheet formula when placed at cell start. */
    private static boolean isFormulaPrefix(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    // -- Offline Notification System -----------------------

    /**
     * Queues a notification for a player who may be offline.
     * If the player is online, the notification is delivered immediately.
     * If offline, it is stored in the database and delivered on next login.
     *
     * @param playerUuid The player's UUID
     * @param message    The notification message to deliver
     * @param server     The MinecraftServer instance (may be null during tests)
     */
    public void queueNotification(UUID playerUuid, String message, net.minecraft.server.MinecraftServer server) {
        // Try to deliver immediately if the player is online
        if (server != null) {
            net.minecraft.server.level.ServerPlayer onlinePlayer =
                server.getPlayerList().getPlayer(playerUuid);
            if (onlinePlayer != null) {
                onlinePlayer.sendSystemMessage(com.solidus.util.TextUtil.styled(
                    "[Solidus] " + message, net.minecraft.ChatFormatting.AQUA));
                return;
            }
        }

        // Player is offline - store the notification in the database; it will be
        // delivered on next login. (Single source of truth: the database.)
        CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO pending_notifications (timestamp, player_uuid, message) VALUES (?, ?, ?)";
            try {
                withConnection(conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, System.currentTimeMillis());
                        ps.setString(2, playerUuid.toString());
                        ps.setString(3, message);
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to queue notification for player: {}", playerUuid, e);
            }
        }, asyncExecutor);
    }

    /**
     * Delivers all pending notifications for a player who just logged in.
     * Called from the player join event handler.
     *
     * <p>FIX v2 (notification loss): previously delivery emptied an in-memory
     * cache first and then deleted ALL database rows for the player, destroying
     * any notification queued in between. Delivery now selects EXACT rows,
     * sends them, and deletes only those rows by id.</p>
     *
     * @param player The player who just connected
     */
    public void deliverPendingNotifications(net.minecraft.server.level.ServerPlayer player) {
        UUID playerUuid = player.getUUID();

        CompletableFuture.supplyAsync(() -> {
            List<PendingNotificationRow> rows = new ArrayList<>();
            String sql = "SELECT id, message FROM pending_notifications WHERE player_uuid = ? ORDER BY timestamp ASC";
            try {
                rows = withConnection(conn -> {
                    List<PendingNotificationRow> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, playerUuid.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                list.add(new PendingNotificationRow(rs.getLong("id"), rs.getString("message")));
                            }
                        }
                    }
                    return list;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to load pending notifications for: {}",
                    player.getName().getString(), e);
            }
            return rows;
        }, asyncExecutor).thenAccept(rows -> {
            if (rows.isEmpty()) return;

            net.minecraft.server.MinecraftServer server = player.level().getServer();
            if (server == null) return;

            server.execute(() -> {
                // Double-check the player is still connected before sending
                if (server.getPlayerList().getPlayer(playerUuid) == null) return;

                for (PendingNotificationRow row : rows) {
                    player.sendSystemMessage(com.solidus.util.TextUtil.styled(
                        "[Solidus] " + row.message(), net.minecraft.ChatFormatting.AQUA));
                }

                // Delete ONLY the rows that were just delivered - newer
                // notifications queued after our snapshot remain untouched.
                deletePendingNotificationsByIds(playerUuid,
                    rows.stream().mapToLong(PendingNotificationRow::id).toArray());
            });
        });
    }

    /** A pending notification row captured for targeted delivery/deletion. */
    private record PendingNotificationRow(long id, String message) {}

    // -- Internal Helpers ----------------------------------

    /**
     * Deletes exactly the delivered notification rows (by id).
     * Never touches notifications queued after the delivery snapshot.
     */
    private void deletePendingNotificationsByIds(UUID playerUuid, long[] ids) {
        if (ids.length == 0) return;
        CompletableFuture.runAsync(() -> {
            StringBuilder sql = new StringBuilder(
                "DELETE FROM pending_notifications WHERE player_uuid = ? AND id IN (");
            for (int i = 0; i < ids.length; i++) {
                sql.append(i == 0 ? "?" : ", ?");
            }
            sql.append(")");
            try {
                withConnection(conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                        ps.setString(1, playerUuid.toString());
                        for (int i = 0; i < ids.length; i++) {
                            ps.setLong(i + 2, ids[i]);
                        }
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (SQLException e) {
                LOGGER.error("Failed to delete delivered notifications for: {}", playerUuid, e);
            }
        }, asyncExecutor);
    }

    private TransactionEntry mapResultSetToEntry(ResultSet rs) throws SQLException {
        String targetUuidStr = rs.getString("target_uuid");
        return new TransactionEntry(
            rs.getLong("timestamp"),
            Type.fromCode(rs.getString("type")),
            UUID.fromString(rs.getString("player_uuid")),
            rs.getString("player_name"),
            targetUuidStr != null ? UUID.fromString(targetUuidStr) : null,
            rs.getString("target_name"),
            rs.getDouble("amount"),
            rs.getString("item_material"),
            rs.getInt("item_quantity"),
            rs.getString("description")
        );
    }
}
