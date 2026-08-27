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
import java.util.List;
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

    /** Transaction type enum for type-safe logging */
    public enum Type {
        SHOP_BUY("SHOP_BUY"),
        SHOP_SELL("SHOP_SELL"),
        AUCTION_LIST("AUCTION_LIST"),
        AUCTION_SOLD("AUCTION_SOLD"),
        AUCTION_BOUGHT("AUCTION_BOUGHT"),
        AUCTION_EXPIRED("AUCTION_EXPIRED"),
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

    private final Connection persistentConnection;
    private final ExecutorService asyncExecutor;

    public TransactionLog(Connection persistentConnection, ExecutorService asyncExecutor) {
        this.persistentConnection = persistentConnection;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * Initializes the transaction log tables and loads pending notifications into memory.
     */
    public void initialize() {
        try (Statement stmt = persistentConnection.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            stmt.execute(CREATE_INDEX_SQL);
            stmt.execute(CREATE_NOTIFICATIONS_TABLE_SQL);
            stmt.execute(CREATE_NOTIFICATIONS_INDEX_SQL);
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
            String sql = """
                INSERT INTO transaction_log
                (timestamp, type, player_uuid, player_name, target_uuid, target_name,
                 amount, item_material, item_quantity, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
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
            } catch (SQLException e) {
                LOGGER.error("Failed to log transaction: {} for player: {}", type, playerName, e);
            }
        }, asyncExecutor);
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
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to get transactions for player: {}", playerUuid, e);
            }
            return entries;
        }, asyncExecutor);
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
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, playerUuid.toString());
                ps.setString(3, message);
                ps.executeUpdate();
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
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new PendingNotificationRow(rs.getLong("id"), rs.getString("message")));
                    }
                }
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
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql.toString())) {
                ps.setString(1, playerUuid.toString());
                for (int i = 0; i < ids.length; i++) {
                    ps.setLong(i + 2, ids[i]);
                }
                ps.executeUpdate();
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
