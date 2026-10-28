package com.solidus.auction;

import com.solidus.economy.EconomyEngine;
import com.solidus.economy.EscrowAccount;
import com.solidus.economy.SQLiteStorage;
import com.solidus.util.ConfigManager;
import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WS-0 live-boot drill companion: drives the REAL console bid flow
 * ({@code placeBidAs}) against a REAL {@link AuctionManager} (SQLITE dialect)
 * wired to a REAL {@link SQLiteStorage} — the exact combination the live
 * server runs. The first live boot showed the bid silently never landing
 * (feedback is lost over RCON), so this test reproduces the flow in-JVM and
 * CAPTURES the outcome messages.
 */
@DisplayName("placeBidAs end-to-end on real SQLITE stores (live-boot drill mirror)")
class AuctionBidAsEscrowFlowTest {

    private static final UUID BIDDER =
        UUID.fromString("cccccccc-3333-3333-3333-333333333333");
    private static final UUID SELLER =
        UUID.fromString("dddddddd-4444-4444-4444-444444444444");

    private Path tempDir;
    private SQLiteStorage storage;
    private AuctionManager manager;
    private final ConcurrentLinkedQueue<String> feedback = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-bidas-flow-");
        ConfigManager.initialize(tempDir);
        double saved = CurrencyUtil.getStartingBalance();
        CurrencyUtil.setStartingBalance(500.0);

        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        assertTrue(storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0)
            .get(5, TimeUnit.SECONDS));
        assertTrue(storage.setBalance(BIDDER, "Bob", 599.99).get(5, TimeUnit.SECONDS));
        assertTrue(storage.setBalance(SELLER, "Alice", 900.01).get(5, TimeUnit.SECONDS));
        CurrencyUtil.setStartingBalance(saved);

        EconomyEngine engine = new EconomyEngine() {
            @Override public com.solidus.economy.StorageBackend getStorage() { return storage; }
        };
        manager = new AuctionManager(engine);
        manager.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) manager.shutdown();
        if (storage != null) storage.shutdown();
        Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
    }

    private UUID insertActiveListing(double price, double startBid) throws Exception {
        UUID listingId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("config").resolve("solidus")
                    .resolve("auctions.db").toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute(AuctionManager.CREATE_TABLE_SQL);
            st.execute(AuctionManager.CREATE_BID_STATE_SQL);
            st.execute(AuctionManager.CREATE_BID_HISTORY_SQL);
            st.execute(AuctionManager.CREATE_WON_ITEMS_SQL);
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, "
                    + "material_name, quantity, item_nbt, price, listed_timestamp, "
                    + "expire_timestamp, status) VALUES (?,?,?,?,?,?,?,?,?,0)")) {
                ps.setString(1, listingId.toString());
                ps.setString(2, SELLER.toString());
                ps.setString(3, "Alice");
                ps.setString(4, "diamond");
                ps.setInt(5, 5);
                ps.setString(6, null);
                ps.setDouble(7, price);
                ps.setLong(8, System.currentTimeMillis());
                ps.setLong(9, System.currentTimeMillis() + AuctionEntry.DEFAULT_DURATION_MS);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO auction_bid_state (listing_id, start_price, current_bid, "
                    + "current_bidder_uuid, current_bidder_name, bid_count, extensions_used) "
                    + "VALUES (?, ?, NULL, NULL, NULL, 0, 0)")) {
                ps.setString(1, listingId.toString());
                ps.setDouble(2, startBid);
                assertEquals(1, ps.executeUpdate());
            }
        }
        return listingId;
    }

    @Test
    @DisplayName("bid-as charges escrow and claims the bid on real stores")
    void bidAsLandsAndEscrows() throws Exception {
        UUID listingId = insertActiveListing(500.0, 100.0);

        // Sanity: phase-1 reads must see the listing (WS-0 liveness regression).
        List<AuctionEntry> active = manager.getActiveListings().get(5, TimeUnit.SECONDS);
        assertEquals(1, active.size(), "listing visible to the manager before bidding");

        manager.placeBidAs(BIDDER, "Bob", listingId, 120.0,
            (isError, message) -> feedback.add((isError ? "[ERR] " : "[OK] ") + message));

        // The pipeline is async across two executors — poll the state.
        UUID seen = null;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(250);
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:"
                    + tempDir.resolve("config").resolve("solidus")
                        .resolve("auctions.db").toAbsolutePath());
                 Statement st = conn.createStatement();
                 var rs = st.executeQuery(
                     "SELECT current_bidder_uuid FROM auction_bid_state "
                         + "WHERE listing_id = '" + listingId + "' AND bid_count = 1")) {
                if (rs.next()) { seen = UUID.fromString(rs.getString(1)); break; }
            }
        }
        System.out.println("FEEDBACK CAPTURED: " + feedback);
        assertEquals(BIDDER, seen, "bid must be claimed; feedback said: " + feedback);

        double escrow = storage.getBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME)
            .get(5, TimeUnit.SECONDS);
        assertEquals(120.0, escrow, 0.0001, "escrow holds exactly the bid");
        double bob = storage.getBalance(BIDDER, "Bob").get(5, TimeUnit.SECONDS);
        assertEquals(599.99 - 120.0, bob, 0.0001, "bidder debited exactly the bid");
        assertTrue(feedback.stream().anyMatch(m -> m.startsWith("[OK]")),
            "at least one success feedback expected, got: " + feedback);
    }
}
