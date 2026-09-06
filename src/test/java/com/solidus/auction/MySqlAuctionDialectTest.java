package com.solidus.auction;

import com.solidus.economy.EconomyEngine;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.StorageBackend;
import com.solidus.economy.TransactionLog;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the MYSQL dialect of the auction store against a REAL
 * MySQL/MariaDB database (2.2.1 — DB scaling plan §11 item 1).
 *
 * <p>The risky parts of the port are the dialect seams, and every one of
 * them is covered here against a live server:</p>
 * <ul>
 *   <li>MYSQL DDL executes cleanly (DECIMAL money, MEDIUMTEXT blobs, inline
 *       KEY clauses — MySQL 8 has no CREATE INDEX IF NOT EXISTS).</li>
 *   <li>{@code insertSoldHistory} / {@code archiveAndDeleteListing} /
 *       {@code archiveAndDeleteCollectibles} through the JDBC transaction
 *       API ({@code setAutoCommit(false)} instead of SQLite's
 *       {@code BEGIN IMMEDIATE}), including the exactly-once claim guard.</li>
 *   <li>{@code recoverOrphanedSoldRows} with the information_schema probe
 *       and the {@code id} column replacing SQLite's {@code rowid}.</li>
 *   <li>Read paths through a live MYSQL-dialect {@link AuctionManager}
 *       (active listings, search with LIKE-escape, bid-state batch).</li>
 * </ul>
 *
 * <p><b>Activation</b> (self-skipping otherwise): {@code SOLIDUS_TEST_MYSQL_HOST}
 * — same service container contract as {@code MySqlStorageContractTest}.</p>
 */
@DisplayName("Auction store on MySQL/MariaDB (2.2.1 dialect port)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MySqlAuctionDialectTest {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlAuctionDialectTest.class);

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private static final String MYSQL_URL =
        "jdbc:mariadb://" + HOST + ":" + PORT + "/" + DATABASE;

    private static final UUID SELLER = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID BUYER = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");

    private static Connection conn;

    @BeforeAll
    static void connectAndCreateSchema() throws Exception {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — MySQL auction dialect tests skipped "
                + "(the SQLite settlement tests cover the same flows on the default dialect)");
        conn = DriverManager.getConnection(MYSQL_URL, USER, PASSWORD);
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS auction_listings, auction_sold_history, "
                + "auction_bid_state, auction_bids, auction_won_items, transaction_log");
            for (String ddl : AuctionManager.AuctionDialect.MYSQL.statements()) {
                if (ddl != null) {
                    st.execute(ddl);
                }
            }
            st.execute("""
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
                """);
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private void wipe() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM auction_listings");
            st.execute("DELETE FROM auction_sold_history");
            st.execute("DELETE FROM auction_bid_state");
            st.execute("DELETE FROM auction_bids");
            st.execute("DELETE FROM auction_won_items");
            st.execute("DELETE FROM transaction_log");
        }
    }

    private long insertListing(UUID listingId, int status, double price, long expireInMs) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO auction_listings
            (listing_id, seller_uuid, seller_name, material_name, quantity, item_nbt,
             price, listed_timestamp, expire_timestamp, status)
            VALUES (?, ?, 'SellerA', 'minecraft:diamond', 1, NULL, ?, ?, ?, ?)
            """)) {
            ps.setString(1, listingId.toString());
            ps.setString(2, SELLER.toString());
            ps.setDouble(3, price);
            ps.setLong(4, now);
            ps.setLong(5, now + expireInMs);
            ps.setInt(6, status);
            ps.executeUpdate();
        }
        return now;
    }

    private com.solidus.auction.AuctionEntry entryOf(UUID listingId, double price) {
        return new com.solidus.auction.AuctionEntry(listingId, SELLER, "SellerA",
            "minecraft:diamond", 1, null, price,
            System.currentTimeMillis(), System.currentTimeMillis() + 60_000,
            com.solidus.auction.ListingStatus.SOLD);
    }

    @Test
    @Order(1)
    @DisplayName("insertSoldHistory (MYSQL): insert + duplicate tolerated as success")
    void soldHistoryInsertAndDuplicate() throws Exception {
        wipe();
        var entry = entryOf(UUID.randomUUID(), 25.50);
        assertTrue(AuctionManager.insertSoldHistory(conn, entry, BUYER, "BuyerB",
            AuctionManager.SETTLED_SOLD, System.currentTimeMillis(), LOG,
            AuctionManager.AuctionDialect.MYSQL));
        // Duplicate insert must be tolerated as success (the caller proceeds with the delete).
        assertTrue(AuctionManager.insertSoldHistory(conn, entry, BUYER, "BuyerB",
            AuctionManager.SETTLED_SOLD, System.currentTimeMillis(), LOG,
            AuctionManager.AuctionDialect.MYSQL));
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) c, price p FROM auction_sold_history")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong("c"), "duplicate must not create a second row");
                assertEquals(25.50, rs.getDouble("p"), 0.0001, "DECIMAL(18,2) round-trip");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("archiveAndDeleteListing (MYSQL tx API): claims exactly-once, refuses second claim")
    void archiveAndDeleteClaimsExactlyOnce() throws Exception {
        wipe();
        UUID listingId = UUID.randomUUID();
        insertListing(listingId, 0, 40.00, 60_000);

        assertTrue(AuctionManager.archiveAndDeleteListing(conn, listingId,
            AuctionManager.SETTLED_EXPIRED_RETURN, null, null,
            System.currentTimeMillis(), LOG, AuctionManager.AuctionDialect.MYSQL));
        // Second claim on the same row must fail (status guard inside the tx).
        assertFalse(AuctionManager.archiveAndDeleteListing(conn, listingId,
            AuctionManager.SETTLED_EXPIRED_RETURN, null, null,
            System.currentTimeMillis(), LOG, AuctionManager.AuctionDialect.MYSQL));
        assertEquals(0, count("auction_listings"), "claimed row must be deleted");
        assertEquals(1, count("auction_sold_history"), "archive committed with the delete");
        // autoCommit must be restored after the transaction
        assertTrue(conn.getAutoCommit(), "autoCommit restored after commit");
    }

    @Test
    @Order(3)
    @DisplayName("archiveAndDeleteCollectibles (MYSQL): claims all status=2 rows of the seller")
    void archiveAndDeleteCollectiblesClaimsBatch() throws Exception {
        wipe();
        insertListing(UUID.randomUUID(), 2, 10.00, -1000);
        insertListing(UUID.randomUUID(), 2, 20.00, -1000);
        insertListing(UUID.randomUUID(), 0, 30.00, 60_000); // untouched (ACTIVE)

        int claimed = AuctionManager.archiveAndDeleteCollectibles(conn, SELLER,
            System.currentTimeMillis(), LOG, AuctionManager.AuctionDialect.MYSQL);
        assertEquals(2, claimed);
        assertEquals(1, count("auction_listings"), "only the ACTIVE row remains");
        assertEquals(2, count("auction_sold_history"));
    }

    @Test
    @Order(4)
    @DisplayName("recoverOrphanedSoldRows (MYSQL): log match archives, no match re-lists")
    void orphanRecoveryUsesInformationSchemaAndId() throws Exception {
        wipe();
        UUID withLog = UUID.randomUUID();
        UUID withoutLog = UUID.randomUUID();
        long listed = insertListing(withLog, 1, 15.00, 60_000);
        insertListing(withoutLog, 1, 35.00, 60_000);

        // Matching AUCTION_SOLD row: seller + amount + material + quantity + listed<=ts.
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO transaction_log
            (timestamp, type, player_uuid, player_name, target_uuid, target_name,
             amount, item_material, item_quantity, description)
            VALUES (?, 'AUCTION_SOLD', ?, 'SellerA', ?, 'BuyerB', 15.00, 'minecraft:diamond', 1, 'sale')
            """)) {
            ps.setLong(1, listed + 1);
            ps.setString(2, SELLER.toString());
            ps.setString(3, BUYER.toString());
            ps.executeUpdate();
        }

        int[] result = AuctionManager.recoverOrphanedSoldRows(conn, conn,
            "AUCTION_SOLD", LOG, AuctionManager.AuctionDialect.MYSQL);
        assertEquals(1, result[0], "one orphan archived (log match)");
        assertEquals(1, result[1], "one orphan re-listed (no log match)");
        assertEquals(0, count("auction_listings WHERE status = 1"), "no SOLD rows remain");
        assertEquals(1, count("auction_listings WHERE status = 0"), "re-listed row is ACTIVE");
        // Only the ARCHIVED orphan lands in history (1 row) — the re-listed
        // one never settles (same expectation as the SQLite sibling test,
        // AuctionSettlementHistoryTest.relistsOrphanWithoutLog).
        assertEquals(1, count("auction_sold_history"));
    }

    @Test
    @Order(5)
    @DisplayName("MYSQL-dialect AuctionManager read paths: DDL + active/search/bid-state queries")
    void managerReadPathsAgainstMysqlDialect() throws Exception {
        wipe();
        insertListing(UUID.randomUUID(), 0, 5.00, 60_000);
        UUID bidListing = UUID.randomUUID();
        insertListing(bidListing, 0, 50.00, 60_000);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO auction_bid_state (listing_id, start_price, current_bid, current_bidder_uuid, "
                    + "current_bidder_name, bid_count, extensions_used) VALUES (?, 10.00, 12.50, ?, 'BidderC', 2, 1)")) {
            ps.setString(1, bidListing.toString());
            ps.setString(2, BUYER.toString());
            ps.executeUpdate();
        }

        // Pure-Java stubs (NO Mockito anywhere in this test: the inline mock
        // maker cannot instrument classes OR interfaces on the Java 25
        // toolchain — same root cause as the AdminOpsTest workaround).
        StorageBackend stubBackend = new StorageBackend() {
            @Override public void initialize() { }
            @Override public void shutdown() { }
            @Override public CompletableFuture<Double> getBalance(UUID uuid, String playerName) {
                return java.util.concurrent.CompletableFuture.completedFuture(0.0);
            }
            @Override public CompletableFuture<List<SQLiteStorage.BalanceEntry>> getTopBalances(int limit, int offset) {
                return java.util.concurrent.CompletableFuture.completedFuture(List.of());
            }
            @Override public CompletableFuture<Integer> getBalanceEntryCount() {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public CompletableFuture<SQLiteStorage.EconomyStats> getEconomyStats() {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<Boolean> setBalance(UUID uuid, String playerName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            }
            @Override public CompletableFuture<Double> addBalance(UUID uuid, String playerName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(amount);
            }
            @Override public CompletableFuture<Double> subtractBalance(UUID uuid, String playerName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(amount);
            }
            @Override public CompletableFuture<Boolean> hasBalance(UUID uuid, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            @Override public CompletableFuture<SQLiteStorage.TransferOutcome> transferAtomic(
                    UUID senderUuid, String senderName, UUID receiverUuid, String receiverName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<SQLiteStorage.TransferOutcome> transferAtomicWithLedger(
                    UUID senderUuid, String senderName, UUID receiverUuid, String receiverName,
                    double amount, List<SQLiteStorage.AtomicLedgerRow> ledgerRows) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public TransactionLog getTransactionLog() { return null; }
            @Override public Map<UUID, String> getPlayerNameCache() { return Map.of(); }
        };
        EconomyEngine engine = new EconomyEngine() {
            @Override public StorageBackend getStorage() { return stubBackend; }
        };
        AuctionManager manager = new AuctionManager(engine, () -> {
            try {
                return DriverManager.getConnection(MYSQL_URL, USER, PASSWORD);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        manager.initialize();

        List<com.solidus.auction.AuctionEntry> active =
            manager.getActiveListings().get(5, TimeUnit.SECONDS);
        assertEquals(2, active.size(), "MYSQL DDL created the tables the manager reads");

        List<com.solidus.auction.AuctionEntry> wildcard =
            manager.searchListings("DIA%MOND").get(5, TimeUnit.SECONDS);
        assertEquals(0, wildcard.size(), "LIKE wildcards in user terms are escaped (literal match)");

        List<com.solidus.auction.AuctionEntry> plain =
            manager.searchListings("diamond").get(5, TimeUnit.SECONDS);
        assertEquals(2, plain.size(), "plain term matches material case-insensitively");
        assertTrue(plain.get(0).price() <= plain.get(1).price(), "cheapest first");

        var states = manager.getBidStates(List.of(bidListing)).get(5, TimeUnit.SECONDS);
        assertTrue(states.containsKey(bidListing));
        assertEquals(12.50, states.get(bidListing).currentBid(), 0.0001);
        assertEquals(1, states.get(bidListing).extensionsUsed());
    }

    private long count(String tableFragment) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) c FROM " + tableFragment)) {
            return rs.next() ? rs.getLong("c") : 0;
        }
    }
}
