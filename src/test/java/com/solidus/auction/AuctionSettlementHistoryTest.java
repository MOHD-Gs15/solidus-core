package com.solidus.auction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code auction_sold_history} archive and the startup sweep
 * that reconciles orphaned SOLD rows against TransactionLog.
 *
 * Drives the Minecraft-free static JDBC helpers of {@link AuctionManager}
 * against two real SQLite databases (auctions.db + economy.db) - mirroring
 * the production topology of two separate database files.
 */
@DisplayName("Auction settlement history & orphan recovery")
class AuctionSettlementHistoryTest {

    private static final Logger LOG = LoggerFactory.getLogger("test");

    private Path tempDir;
    private Connection auctionConn;
    private Connection economyConn;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-auction-test-");
        auctionConn = DriverManager.getConnection(
            "jdbc:sqlite:" + tempDir.resolve("auctions.db"));
        economyConn = DriverManager.getConnection(
            "jdbc:sqlite:" + tempDir.resolve("economy.db"));

        try (Statement st = auctionConn.createStatement()) {
            st.execute(AuctionManager.CREATE_TABLE_SQL);
            st.execute(AuctionManager.CREATE_INDEX_SQL);
            st.execute(AuctionManager.CREATE_SOLD_HISTORY_SQL);
            st.execute(AuctionManager.CREATE_SOLD_HISTORY_INDEX_SQL);
        }
        // Minimal transaction_log schema with the columns the sweep matches on
        try (Statement st = economyConn.createStatement()) {
            st.execute("""
                CREATE TABLE transaction_log (
                    timestamp INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT,
                    target_uuid TEXT,
                    target_name TEXT,
                    amount REAL NOT NULL,
                    item_material TEXT,
                    item_quantity INTEGER,
                    description TEXT
                )
                """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        closeQuietly(auctionConn);
        closeQuietly(economyConn);
        try (var walk = Files.walk(tempDir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    private static void closeQuietly(Connection conn) {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    // -- Helpers -------------------------------------------

    private AuctionEntry insertListing(String material, int quantity, double price,
                                       long listedAt, int statusCode) throws SQLException {
        UUID listingId = UUID.randomUUID();
        UUID sellerUuid = UUID.randomUUID();
        insertListingWithIds(listingId, sellerUuid, "Seller", material, quantity,
            price, listedAt, statusCode);
        return new AuctionEntry(listingId, sellerUuid, "Seller", material, quantity,
            null, price, listedAt, listedAt + AuctionEntry.DEFAULT_DURATION_MS,
            ListingStatus.fromCode(statusCode));
    }

    private void insertListingWithIds(UUID listingId, UUID sellerUuid, String sellerName,
                                      String material, int quantity, double price,
                                      long listedAt, int statusCode) throws SQLException {
        String sql = """
            INSERT INTO auction_listings
            (listing_id, seller_uuid, seller_name, material_name, quantity,
             item_nbt, price, listed_timestamp, expire_timestamp, status)
            VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = auctionConn.prepareStatement(sql)) {
            ps.setString(1, listingId.toString());
            ps.setString(2, sellerUuid.toString());
            ps.setString(3, sellerName);
            ps.setString(4, material);
            ps.setInt(5, quantity);
            ps.setDouble(6, price);
            ps.setLong(7, listedAt);
            ps.setLong(8, listedAt + AuctionEntry.DEFAULT_DURATION_MS);
            ps.setInt(9, statusCode);
            ps.executeUpdate();
        }
    }

    /** Mirrors TransactionLog's AUCTION_SOLD row: player=seller, target=buyer. */
    private long insertSoldLog(UUID sellerUuid, String sellerName, UUID buyerUuid,
                               String buyerName, String material, int quantity,
                               double price, long soldAt) throws SQLException {
        String sql = """
            INSERT INTO transaction_log
            (timestamp, type, player_uuid, player_name, target_uuid, target_name,
             amount, item_material, item_quantity, description)
            VALUES (?, 'AUCTION_SOLD', ?, ?, ?, ?, ?, ?, ?, 'test sale')
            """;
        try (PreparedStatement ps = economyConn.prepareStatement(sql)) {
            ps.setLong(1, soldAt);
            ps.setString(2, sellerUuid.toString());
            ps.setString(3, sellerName);
            ps.setString(4, buyerUuid != null ? buyerUuid.toString() : null);
            ps.setString(5, buyerName);
            ps.setDouble(6, price);
            ps.setString(7, material);
            ps.setInt(8, quantity);
            ps.executeUpdate();
        }
        try (Statement st = economyConn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private int countTable(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long historyCount(String reason) throws SQLException {
        String sql = "SELECT COUNT(*) FROM auction_sold_history WHERE settled_reason = ?";
        try (PreparedStatement ps = auctionConn.prepareStatement(sql)) {
            ps.setString(1, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // -- insertSoldHistory ----------------------------------

    @Nested
    @DisplayName("insertSoldHistory")
    class InsertSoldHistory {

        @Test
        @DisplayName("archives the entry with buyer attribution")
        void archivesWithBuyer() throws Exception {
            AuctionEntry entry = insertListing("minecraft:diamond", 5, 100.0,
                System.currentTimeMillis(), 0);
            UUID buyer = UUID.randomUUID();

            assertTrue(AuctionManager.insertSoldHistory(auctionConn, entry, buyer,
                "Buyer", AuctionManager.SETTLED_SOLD, 12345L, LOG));

            assertEquals(1, countTable(auctionConn, "auction_sold_history"));
            try (PreparedStatement ps = auctionConn.prepareStatement(
                    "SELECT buyer_uuid, buyer_name, settled_timestamp, settled_reason"
                    + " FROM auction_sold_history")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(buyer.toString(), rs.getString("buyer_uuid"));
                    assertEquals("Buyer", rs.getString("buyer_name"));
                    assertEquals(12345L, rs.getLong("settled_timestamp"));
                    assertEquals(AuctionManager.SETTLED_SOLD, rs.getString("settled_reason"));
                }
            }
        }

        @Test
        @DisplayName("duplicate insert is idempotent and still reports success")
        void duplicateIsIdempotent() throws Exception {
            AuctionEntry entry = insertListing("minecraft:iron_ingot", 1, 10.0,
                System.currentTimeMillis(), 0);

            assertTrue(AuctionManager.insertSoldHistory(auctionConn, entry, null,
                null, AuctionManager.SETTLED_SOLD, 1L, LOG));
            // Same listing re-archived (e.g. sweep retry): tolerated, no dup row
            assertTrue(AuctionManager.insertSoldHistory(auctionConn, entry, null,
                null, AuctionManager.SETTLED_SOLD, 2L, LOG));

            assertEquals(1, countTable(auctionConn, "auction_sold_history"));
        }
    }

    // -- archiveAndDeleteListing ----------------------------

    @Nested
    @DisplayName("archiveAndDeleteListing")
    class ArchiveAndDeleteListing {

        @Test
        @DisplayName("claims exactly once: archives + deletes the ACTIVE row")
        void claimsExactlyOnce() throws Exception {
            UUID listingId = UUID.randomUUID();
            insertListingWithIds(listingId, UUID.randomUUID(), "Seller",
                "minecraft:emerald", 2, 50.0, 1000L, 0);

            assertTrue(AuctionManager.archiveAndDeleteListing(auctionConn, listingId,
                AuctionManager.SETTLED_CANCELLED, null, null, 2000L, LOG));
            // Second claim on the same row must fail - row is gone
            assertFalse(AuctionManager.archiveAndDeleteListing(auctionConn, listingId,
                AuctionManager.SETTLED_CANCELLED, null, null, 3000L, LOG));

            assertEquals(0, countTable(auctionConn, "auction_listings"));
            assertEquals(1, countTable(auctionConn, "auction_sold_history"));
            assertEquals(1, historyCount(AuctionManager.SETTLED_CANCELLED));
        }

        @Test
        @DisplayName("refuses to claim a row that is not ACTIVE (status != 0)")
        void refusesNonActive() throws Exception {
            UUID listingId = UUID.randomUUID();
            insertListingWithIds(listingId, UUID.randomUUID(), "Seller",
                "minecraft:gold_ingot", 1, 20.0, 1000L, 1); // SOLD

            assertFalse(AuctionManager.archiveAndDeleteListing(auctionConn, listingId,
                AuctionManager.SETTLED_CANCELLED, null, null, 2000L, LOG));

            // Nothing archived, nothing deleted
            assertEquals(1, countTable(auctionConn, "auction_listings"));
            assertEquals(0, countTable(auctionConn, "auction_sold_history"));
        }
    }

    // -- archiveAndDeleteCollectibles ------------------------

    @Nested
    @DisplayName("archiveAndDeleteCollectibles")
    class ArchiveAndDeleteCollectibles {

        @Test
        @DisplayName("archives and deletes all status=2 rows of the seller")
        void archivesAllCollectibles() throws Exception {
            UUID seller = UUID.randomUUID();
            insertListingWithIds(UUID.randomUUID(), seller, "Seller",
                "minecraft:obsidian", 8, 40.0, 1000L, 2);
            insertListingWithIds(UUID.randomUUID(), seller, "Seller",
                "minecraft:obsidian", 3, 15.0, 2000L, 2);
            // Other seller's collectible must stay untouched
            insertListingWithIds(UUID.randomUUID(), UUID.randomUUID(), "Other",
                "minecraft:obsidian", 1, 5.0, 1000L, 2);

            int claimed = AuctionManager.archiveAndDeleteCollectibles(auctionConn,
                seller, 5000L, LOG);

            assertEquals(2, claimed);
            assertEquals(1, countTable(auctionConn, "auction_listings"));
            assertEquals(2, countTable(auctionConn, "auction_sold_history"));
            assertEquals(2, historyCount(AuctionManager.SETTLED_EXPIRED_COLLECT));

            // Retry is a clean no-op
            assertEquals(0, AuctionManager.archiveAndDeleteCollectibles(auctionConn,
                seller, 6000L, LOG));
        }
    }

    // -- recoverOrphanedSoldRows ------------------------------

    @Nested
    @DisplayName("recoverOrphanedSoldRows")
    class RecoverOrphanedSoldRows {

        @Test
        @DisplayName("archives orphan WITH matching sale log (buyer from log), deletes row")
        void archivesOrphanWithLog() throws Exception {
            AuctionEntry orphan = insertListing("minecraft:diamond", 5, 100.0,
                1000L, 1);
            UUID buyer = UUID.randomUUID();
            long soldAt = 2000L;
            insertSoldLog(orphan.sellerUuid(), "Seller", buyer, "Buyer",
                "minecraft:diamond", 5, 100.0, soldAt);

            int[] result = AuctionManager.recoverOrphanedSoldRows(
                auctionConn, economyConn, "AUCTION_SOLD", LOG);

            assertArrayEquals(new int[]{1, 0}, result);
            assertEquals(0, countTable(auctionConn, "auction_listings"));
            assertEquals(1, countTable(auctionConn, "auction_sold_history"));

            try (PreparedStatement ps = auctionConn.prepareStatement(
                    "SELECT buyer_uuid, buyer_name, settled_timestamp FROM auction_sold_history")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(buyer.toString(), rs.getString("buyer_uuid"));
                    assertEquals("Buyer", rs.getString("buyer_name"));
                    assertEquals(soldAt, rs.getLong("settled_timestamp"));
                }
            }
        }

        @Test
        @DisplayName("re-lists orphan WITHOUT matching sale log (purchase never finished)")
        void relistsOrphanWithoutLog() throws Exception {
            AuctionEntry orphan = insertListing("minecraft:iron_ingot", 1, 10.0,
                1000L, 1);
            // A sale log exists but does NOT match (different price)
            insertSoldLog(orphan.sellerUuid(), "Seller", UUID.randomUUID(), "Buyer",
                "minecraft:iron_ingot", 1, 99.0, 2000L);

            int[] result = AuctionManager.recoverOrphanedSoldRows(
                auctionConn, economyConn, "AUCTION_SOLD", LOG);

            assertArrayEquals(new int[]{0, 1}, result);
            // Row is back on the market, not archived
            assertEquals(1, countTable(auctionConn, "auction_listings"));
            assertEquals(0, countTable(auctionConn, "auction_sold_history"));
            try (PreparedStatement ps = auctionConn.prepareStatement(
                    "SELECT status FROM auction_listings WHERE listing_id = ?")) {
                ps.setString(1, orphan.listingId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt("status"));
                }
            }
        }

        @Test
        @DisplayName("consumes each log row once: two identical orphans, one log")
        void consumesLogRowsOnce() throws Exception {
            // Two identical orphan rows - oldest processed first
            UUID seller = UUID.randomUUID();
            UUID older = UUID.randomUUID();
            UUID newer = UUID.randomUUID();
            insertListingWithIds(older, seller, "Seller", "minecraft:stone", 64,
                5.0, 1000L, 1);
            insertListingWithIds(newer, seller, "Seller", "minecraft:stone", 64,
                5.0, 2000L, 1);
            insertSoldLog(seller, "Seller", UUID.randomUUID(), "Buyer",
                "minecraft:stone", 64, 5.0, 3000L);

            int[] result = AuctionManager.recoverOrphanedSoldRows(
                auctionConn, economyConn, "AUCTION_SOLD", LOG);

            assertArrayEquals(new int[]{1, 1}, result);
            assertEquals(1, countTable(auctionConn, "auction_sold_history"));
            // The OLDER listing was archived against the log
            try (PreparedStatement ps = auctionConn.prepareStatement(
                    "SELECT COUNT(*) FROM auction_sold_history WHERE listing_id = ?")) {
                ps.setString(1, older.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
            // The newer one was re-listed
            try (PreparedStatement ps = auctionConn.prepareStatement(
                    "SELECT status FROM auction_listings WHERE listing_id = ?")) {
                ps.setString(1, newer.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt("status"));
                }
            }
        }

        @Test
        @DisplayName("skips cleanly when the economy DB has no transaction_log table")
        void skipsWithoutLogTable() throws Exception {
            try (Statement st = economyConn.createStatement()) {
                st.execute("DROP TABLE transaction_log");
            }
            insertListing("minecraft:diamond", 1, 7.0, 1000L, 1);

            int[] result = AuctionManager.recoverOrphanedSoldRows(
                auctionConn, economyConn, "AUCTION_SOLD", LOG);

            assertArrayEquals(new int[]{0, 0}, result);
            // Orphan untouched - retried on the next restart
            assertEquals(1, countTable(auctionConn, "auction_listings"));
        }

        @Test
        @DisplayName("ignores logs of other sellers and pre-listing timestamps")
        void respectsSellerAndTimeGuards() throws Exception {
            AuctionEntry orphan = insertListing("minecraft:diamond", 1, 7.0,
                5000L, 1);
            // Same material/qty/price but a DIFFERENT seller - must not match
            insertSoldLog(UUID.randomUUID(), "Other", UUID.randomUUID(), "Buyer",
                "minecraft:diamond", 1, 7.0, 6000L);
            // Same seller matching fields but sold BEFORE the listing - must not match
            insertSoldLog(orphan.sellerUuid(), "Seller", UUID.randomUUID(), "Buyer",
                "minecraft:diamond", 1, 7.0, 4000L);

            int[] result = AuctionManager.recoverOrphanedSoldRows(
                auctionConn, economyConn, "AUCTION_SOLD", LOG);

            assertArrayEquals(new int[]{0, 1}, result);
            assertEquals(0, countTable(auctionConn, "auction_sold_history"));
        }
    }
}
