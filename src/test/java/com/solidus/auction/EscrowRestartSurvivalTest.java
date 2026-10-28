package com.solidus.auction;

import com.solidus.economy.EscrowAccount;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.TransactionLog;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ESC-01 restart-survival regression (Task A, أ-5 — AUDIT_REPORT §9.7 gap).
 *
 * <p>Scenario: an auction bid is open and its escrow is FUNDED, then the
 * server stops ({@code shutdown()}) and boots again on the same state files.
 * The escrow balance and the bid state must survive exactly — historically
 * nothing in the suite covered close+reopen of the same SQLite files, so a
 * startup path that zeroed (or lost) the escrow would have shipped silently.
 * The live-boot WS-0 drill runs this scenario for real via RCON; this test is
 * its JVM counterpart so it guards every future CI run.</p>
 *
 * <p>Contract locked here:</p>
 * <ul>
 *   <li>{@code shutdown()} flushes and closes; a NEW instance on the SAME
 *       files reloads the funded escrow (not zero).</li>
 *   <li>The bidder's debited balance survives the restart.</li>
 *   <li>The open {@code auction_bid_state} row (current bid + bidder) is
 *       intact after reopen.</li>
 *   <li>The new instance can keep moving money (not wedged read-only).</li>
 * </ul>
 */
@DisplayName("ESC-01: funded escrow + open bid survive close/reopen of the same SQLite files")
class EscrowRestartSurvivalTest {

    private static final UUID BIDDER =
        UUID.fromString("cccccccc-3333-3333-3333-333333333333");
    private static final UUID SELLER =
        UUID.fromString("dddddddd-4444-4444-4444-444444444444");
    private static final double BID_AMOUNT = 120.0;

    private Path tempDir;
    private double savedStartingBalance;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-esc01-restart-");
        savedStartingBalance = CurrencyUtil.getStartingBalance();
    }

    @AfterEach
    void tearDown() {
        CurrencyUtil.setStartingBalance(savedStartingBalance);
        try {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    /** Mirrors the live-boot drill: fund a bid (escrow charged) + open listing. */
    private void firstSession() throws Exception {
        SQLiteStorage storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();

        assertTrue(storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0)
            .get(5, TimeUnit.SECONDS), "escrow pre-create failed");
        assertTrue(storage.setBalance(BIDDER, "Bob", 599.99).get(5, TimeUnit.SECONDS),
            "bidder funding failed");
        assertTrue(storage.setBalance(SELLER, "Alice", 900.01).get(5, TimeUnit.SECONDS),
            "seller funding failed");

        // The bid: Bob -> escrow, atomically, with its BID_PLACED evidence.
        var outcome = storage.transferAtomicWithLedger(
            BIDDER, "Bob", EscrowAccount.UUID_ZERO, EscrowAccount.NAME,
            BID_AMOUNT,
            List.of(new SQLiteStorage.AtomicLedgerRow(
                TransactionLog.Type.BID_PLACED,
                BIDDER, "Bob", SELLER, "Alice",
                BID_AMOUNT, "diamond", 5, "Bid 120.00 S$ on 5x diamond")))
            .get(5, TimeUnit.SECONDS);
        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status(),
            "escrow charge must succeed in session 1");

        // Open listing + bid state in the auction store (same file layout the
        // live server uses: auctions.db next to economy.db under the config dir).
        UUID listingId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir + "/auctions.db");
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
                ps.setDouble(7, 500.0);
                ps.setLong(8, System.currentTimeMillis());
                ps.setLong(9, System.currentTimeMillis() + AuctionEntry.DEFAULT_DURATION_MS);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO auction_bid_state (listing_id, start_price, current_bid, "
                    + "current_bidder_uuid, current_bidder_name, bid_count, extensions_used) "
                    + "VALUES (?, 100.0, 120.0, ?, 'Bob', 1, 0)")) {
                ps.setString(1, listingId.toString());
                ps.setString(2, BIDDER.toString());
                assertEquals(1, ps.executeUpdate());
            }
        }

        // ── stop: flush + close everything the way the server does ──
        storage.shutdown();
    }

    @Test
    @DisplayName("escrow stays funded after shutdown + reopen (not zeroed)")
    void escrowSurvivesRestart() throws Exception {
        firstSession();

        // ── second boot: NEW instance over the SAME files ──
        SQLiteStorage reopened = new SQLiteStorage(tempDir.toString());
        try {
            reopened.initialize();

            double escrow = reopened
                .getBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME)
                .get(5, TimeUnit.SECONDS);
            assertEquals(BID_AMOUNT, escrow,
                "ESC-01 VIOLATION: the funded escrow was lost/zeroed by the restart");

            double bob = reopened.getBalance(BIDDER, "Bob").get(5, TimeUnit.SECONDS);
            assertEquals(599.99 - BID_AMOUNT, bob, 0.0001,
                "the bidder's debited balance must survive the restart");

            double alice = reopened.getBalance(SELLER, "Alice").get(5, TimeUnit.SECONDS);
            assertEquals(900.01, alice, 0.0001, "seller balance untouched");
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    @DisplayName("open bid state row survives shutdown + reopen")
    void bidStateSurvivesRestart() throws Exception {
        firstSession();

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir + "/auctions.db");
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT current_bid, current_bidder_name, bid_count "
                     + "FROM auction_bid_state WHERE bid_count = 1")) {
            assertTrue(rs.next(), "the open bid row must exist after the restart");
            assertEquals(120.0, rs.getDouble("current_bid"), 0.0001,
                "ESC-01 VIOLATION: current bid lost across restart");
            assertEquals("Bob", rs.getString("current_bidder_name"));
            assertFalse(rs.next(), "exactly one open bid expected");
        }
    }

    @Test
    @DisplayName("reopened store is fully writable (not wedged read-only)")
    void reopenedStoreAcceptsWrites() throws Exception {
        firstSession();

        SQLiteStorage reopened = new SQLiteStorage(tempDir.toString());
        try {
            reopened.initialize();

            var outcome = reopened.transferAtomicWithLedger(
                SELLER, "Alice", BIDDER, "Bob", 0.01, List.of())
                .get(5, TimeUnit.SECONDS);
            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status(),
                "money movement must keep working after the restart");
            assertEquals(479.99 + 0.01,
                reopened.getBalance(BIDDER, "Bob").get(5, TimeUnit.SECONDS), 0.0001);
        } finally {
            reopened.shutdown();
        }
    }
}
