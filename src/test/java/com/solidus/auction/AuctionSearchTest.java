package com.solidus.auction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code /ah search} query core: case-insensitive substring
 * matching on material and seller names, wildcard escaping, status/expiry
 * filtering, cheapest-first ordering and the result limit.
 *
 * Drives the Minecraft-free static JDBC helper of {@link AuctionManager}
 * against a real SQLite database, mirroring the production table schema.
 */
@DisplayName("Auction house free-text search")
class AuctionSearchTest {

    private static final Logger LOG = LoggerFactory.getLogger("test");

    private Path tempDir;
    private Connection conn;
    private long now;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-ah-search-test-");
        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("auctions.db"));
        try (Statement st = conn.createStatement()) {
            st.execute(AuctionManager.CREATE_TABLE_SQL);
            st.execute(AuctionManager.CREATE_INDEX_SQL);
        }
        now = System.currentTimeMillis();
        // Active listings (status 0, not expired)
        insert("l-1", "Steve", "minecraft:diamond_sword", 100.0, 0, now + 3600_000);
        insert("l-2", "alex", "minecraft:diamond_pickaxe", 250.0, 0, now + 7200_000);
        insert("l-3", "Notch", "minecraft:dirt", 5.0, 0, now + 60_000);
        insert("l-4", "DiamondBack", "minecraft:stone", 50.0, 0, now + 86400_000);
        // Sold listing must be excluded
        insert("l-5", "Steve", "minecraft:diamond_helmet", 900.0, 1, now + 3600_000);
        // Expired listing (status 2) must be excluded
        insert("l-6", "Steve", "minecraft:diamond_chestplate", 800.0, 2, now + 3600_000);
        // Active but already past expiry must be excluded
        insert("l-7", "Steve", "minecraft:diamond_shovel", 70.0, 0, now - 1000);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    private void insert(String id, String seller, String material, double price,
                        int status, long expire) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO auction_listings
            (listing_id, seller_uuid, seller_name, material_name, quantity,
             item_nbt, price, listed_timestamp, expire_timestamp, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, seller);
            ps.setString(4, material);
            ps.setInt(5, 1);
            ps.setString(6, null);
            ps.setDouble(7, price);
            ps.setLong(8, now - 1000);
            ps.setLong(9, expire);
            ps.setInt(10, status);
            ps.executeUpdate();
        }
    }

    private List<AuctionEntry> search(String term, int limit) throws Exception {
        return AuctionManager.searchListingsVia(conn, term, limit);
    }

    @Test
    @DisplayName("matches material substring case-insensitively")
    void materialSubstringMatch() throws Exception {
        List<AuctionEntry> results = search("DIAMOND", 50);
        // diamond_sword, diamond_pickaxe + seller DiamondBack (name match) = 3 rows
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(e -> e.materialName().toLowerCase().contains("diamond")
                || e.sellerName().toLowerCase().contains("diamond")));
    }

    @Test
    @DisplayName("matches seller name case-insensitively")
    void sellerNameMatch() throws Exception {
        List<AuctionEntry> results = search("steve", 50);
        // Steve's active diamond_sword only (other Steve rows are sold/expired)
        assertEquals(1, results.size());
        assertEquals("minecraft:diamond_sword", results.get(0).materialName());
    }

    @Test
    @DisplayName("results are ordered cheapest first")
    void cheapestFirst() throws Exception {
        List<AuctionEntry> results = search("diamond", 50);
        assertEquals(3, results.size());
        assertEquals(50.0, results.get(0).price(), 1e-9);
        assertEquals(100.0, results.get(1).price(), 1e-9);
        assertEquals(250.0, results.get(2).price(), 1e-9);
    }

    @Test
    @DisplayName("respects the limit")
    void respectsLimit() throws Exception {
        List<AuctionEntry> results = search("diamond", 2);
        assertEquals(2, results.size());
        assertEquals(50.0, results.get(0).price(), 1e-9);
    }

    @Test
    @DisplayName("no match returns empty list")
    void noMatch() throws Exception {
        assertTrue(search("netherite", 50).isEmpty());
    }

    @Test
    @DisplayName("underscore is matched literally, not as a wildcard")
    void underscoreIsLiteral() throws Exception {
        // "diamond_a" would match "minecraft:diamond_axe" if _ were a wildcard;
        // with literal matching only nothing contains the literal substring.
        assertTrue(search("diamond_a", 50).isEmpty());
        // Exact literal underscore match still works.
        List<AuctionEntry> results = search("diamond_sword", 50);
        assertEquals(1, results.size());
        assertEquals("minecraft:diamond_sword", results.get(0).materialName());
    }

    @Test
    @DisplayName("sold, expired-status and past-expiry rows are excluded")
    void filtersStatusAndExpiry() throws Exception {
        List<AuctionEntry> results = search("diamond", 50);
        assertFalse(results.stream().anyMatch(e -> e.materialName().contains("helmet")));
        assertFalse(results.stream().anyMatch(e -> e.materialName().contains("chestplate")));
        assertFalse(results.stream().anyMatch(e -> e.materialName().contains("shovel")));
    }

    @Test
    @DisplayName("sanitizeSearchTerm rejects null, blank and oversized terms")
    void sanitizeRules() {
        assertNull(AuctionManager.sanitizeSearchTerm(null));
        assertNull(AuctionManager.sanitizeSearchTerm("   "));
        assertNull(AuctionManager.sanitizeSearchTerm("x".repeat(65)));
        assertEquals("diamond", AuctionManager.sanitizeSearchTerm("  diamond  "));
        assertEquals("x".repeat(64), AuctionManager.sanitizeSearchTerm("x".repeat(64)));
    }
}
