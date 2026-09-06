package com.solidus.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.solidus.util.ConfigManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI-gated test for the SQLite → MySQL cutover migrator (2.2.1 — DB scaling
 * plan §8.2): builds a realistic SQLite source (balances + ledger + auction
 * tables), runs {@link StorageMigrator} against the live MySQL target and
 * verifies counts, money supply and the RE-RUNNABILITY guarantee (a second
 * run must converge without duplicating rows).
 *
 * <p>Activation (self-skipping otherwise): {@code SOLIDUS_TEST_MYSQL_HOST} —
 * same service container contract as {@code MySqlStorageContractTest}.</p>
 */
@DisplayName("SQLite → MySQL cutover migrator (2.2.1)")
public class StorageMigratorTest {

    private static final Logger LOG = LoggerFactory.getLogger(StorageMigratorTest.class);

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private static final String MIGRATE_DB_PREFIX = "solidus_migrate_";

    private Path sqliteDir;
    private String targetDatabase;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — migrator tests skipped");
        sqliteDir = Files.createTempDirectory("solidus-migrate-src-");
        // The migrator writes its report into the Solidus config dir — point
        // the static ConfigManager at a temp dir (same pattern as
        // StorageConfigTest) so the report file assertion is meaningful here.
        ConfigManager.initialize(sqliteDir);
        targetDatabase = MIGRATE_DB_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        createTargetDatabase();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (targetDatabase != null) {
            dropTargetDatabase();
        }
        if (sqliteDir != null) {
            Files.walk(sqliteDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }

    /** A dedicated throwaway database per run — the migrator must never touch shared state. */
    private void createTargetDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mariadb://" + HOST + ":" + PORT + "/?user=" + USER + "&password=" + PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + targetDatabase
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private void dropTargetDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mariadb://" + HOST + ":" + PORT + "/?user=" + USER + "&password=" + PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + targetDatabase + "`");
        }
    }

    private Path buildSqliteSource() throws Exception {
        Path db = sqliteDir.resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE player_balances (
                        uuid TEXT PRIMARY KEY NOT NULL, player_name TEXT NOT NULL,
                        balance REAL NOT NULL, last_updated INTEGER NOT NULL)""");
                st.execute("""
                    CREATE TABLE transaction_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL,
                        type TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL,
                        target_uuid TEXT, target_name TEXT, amount REAL NOT NULL,
                        item_material TEXT, item_quantity INTEGER, description TEXT)""");
                st.execute("""
                    CREATE TABLE pending_notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL,
                        player_uuid TEXT NOT NULL, message TEXT NOT NULL)""");
                st.execute(AuctionManagerLite.CREATE_LISTINGS);
                st.execute(AuctionManagerLite.CREATE_BIDS);
                st.execute(AuctionManagerLite.CREATE_SOLD_HISTORY);
                st.execute(AuctionManagerLite.CREATE_BID_STATE);
                st.execute(AuctionManagerLite.CREATE_WON_ITEMS);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_balances VALUES (?, ?, ?, ?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, "Alice");
                ps.setDouble(3, 123.45);
                ps.setLong(4, System.currentTimeMillis());
                ps.addBatch();
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, "Bob");
                ps.setDouble(3, 678.90);
                ps.setLong(4, System.currentTimeMillis());
                ps.addBatch();
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount)
                    VALUES (?, 'PAY_SEND', ?, 'Alice', 10.00)""")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, UUID.randomUUID().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, material_name, "
                        + "quantity, price, listed_timestamp, expire_timestamp, status) "
                        + "VALUES (?, ?, 'Alice', 'minecraft:diamond', 1, 5.25, ?, ?, 0)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, UUID.randomUUID().toString());
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, System.currentTimeMillis() + 60_000);
                ps.executeUpdate();
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auction_sold_history (listing_id, seller_uuid, seller_name, material_name, "
                        + "quantity, price, buyer_uuid, buyer_name, listed_timestamp, settled_timestamp, settled_reason) "
                        + "VALUES (?, ?, 'Alice', 'minecraft:emerald', 2, 12.50, ?, 'Bob', ?, ?, 'SOLD')")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, UUID.randomUUID().toString());
                ps.setString(3, UUID.randomUUID().toString());
                ps.setLong(4, now - 60_000);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auction_bid_state (listing_id, start_price, current_bid, "
                        + "current_bidder_uuid, current_bidder_name, bid_count, extensions_used) "
                        + "VALUES (?, 5.00, 7.25, ?, 'Bob', 3, 1)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, UUID.randomUUID().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auction_won_items (listing_id, winner_uuid, winner_name, material_name, "
                        + "item_nbt, quantity, win_price, won_timestamp) "
                        + "VALUES (?, ?, 'Bob', 'minecraft:iron_ingot', NULL, 5, 3.75, ?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, UUID.randomUUID().toString());
                ps.setLong(3, now);
                ps.executeUpdate();
            }
        }
        return db;
    }

    /** The tables the migrator's auction DDL needs for this test (full set). */
    private static final class AuctionManagerLite {
        static final String CREATE_LISTINGS = """
            CREATE TABLE auction_listings (
                listing_id TEXT PRIMARY KEY NOT NULL, seller_uuid TEXT NOT NULL,
                seller_name TEXT NOT NULL, material_name TEXT NOT NULL,
                quantity INTEGER NOT NULL, item_nbt TEXT, price REAL NOT NULL,
                listed_timestamp INTEGER NOT NULL, expire_timestamp INTEGER NOT NULL,
                status INTEGER NOT NULL DEFAULT 0)""";
        static final String CREATE_BIDS = """
            CREATE TABLE auction_bids (
                bid_id INTEGER PRIMARY KEY AUTOINCREMENT, listing_id TEXT NOT NULL,
                bidder_uuid TEXT NOT NULL, bidder_name TEXT NOT NULL,
                amount REAL NOT NULL, bid_timestamp INTEGER NOT NULL)""";
        static final String CREATE_SOLD_HISTORY = """
            CREATE TABLE auction_sold_history (
                listing_id TEXT PRIMARY KEY NOT NULL, seller_uuid TEXT NOT NULL,
                seller_name TEXT NOT NULL, material_name TEXT NOT NULL,
                quantity INTEGER NOT NULL, price REAL NOT NULL, buyer_uuid TEXT,
                buyer_name TEXT, listed_timestamp INTEGER NOT NULL,
                settled_timestamp INTEGER NOT NULL, settled_reason TEXT NOT NULL)""";
        static final String CREATE_BID_STATE = """
            CREATE TABLE auction_bid_state (
                listing_id TEXT PRIMARY KEY NOT NULL, start_price REAL NOT NULL,
                current_bid REAL, current_bidder_uuid TEXT, current_bidder_name TEXT,
                bid_count INTEGER NOT NULL DEFAULT 0, extensions_used INTEGER NOT NULL DEFAULT 0)""";
        static final String CREATE_WON_ITEMS = """
            CREATE TABLE auction_won_items (
                win_id INTEGER PRIMARY KEY AUTOINCREMENT, listing_id TEXT NOT NULL UNIQUE,
                winner_uuid TEXT NOT NULL, winner_name TEXT NOT NULL,
                material_name TEXT NOT NULL, item_nbt TEXT, quantity INTEGER NOT NULL,
                win_price REAL NOT NULL, won_timestamp INTEGER NOT NULL)""";
    }

    @Test
    @DisplayName("copy + verify succeeds, money supply matches to the cent, re-run converges")
    void migrateVerifyAndReRun() throws Exception {
        Path source = buildSqliteSource();
        StorageConfig.MySqlSettings target = new StorageConfig.MySqlSettings(
            HOST, PORT, targetDatabase, USER, PASSWORD, 2, 5000, false);
        StorageMigrator migrator = new StorageMigrator(source, target);

        StorageMigrator.MigrationReport report = migrator.run(1, LOG); // batch 1 → many batches
        assertTrue(report.success(), "first run must succeed: " + report.issues());
        assertEquals(802.35, report.sqliteSupply(), 0.005);
        assertEquals(report.sqliteSupply(), report.mysqlSupply(), 0.005);
        assertTrue(report.tableCounts().stream().anyMatch(l -> l.startsWith("player_balances: 2")));
        assertNotNull(report.reportFile(), "a report file must be written");

        // Re-run (the crash-recovery scenario): must converge, not duplicate.
        StorageMigrator.MigrationReport second = migrator.run(500, LOG);
        assertTrue(second.success(), "re-run must succeed: " + second.issues());
        assertEquals(2, countTarget("player_balances"), "re-run must not duplicate balances");
        assertEquals(1, countTarget("transaction_log"));
        assertEquals(1, countTarget("auction_listings"));
        assertEquals(1, countTarget("auction_sold_history"), "re-run must not duplicate history");
        assertEquals(1, countTarget("auction_bid_state"));
        assertEquals(1, countTarget("auction_won_items"));
        assertEquals(802.35, second.mysqlSupply(), 0.005);
    }

    private long countTarget(String table) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mariadb://" + HOST + ":" + PORT + "/" + targetDatabase, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            try (var rs = st.executeQuery("SELECT COUNT(*) c FROM `" + table + "`")) {
                return rs.next() ? rs.getLong("c") : 0;
            }
        }
    }
}
