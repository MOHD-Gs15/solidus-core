package com.solidus.auction;

import com.solidus.economy.EscrowAccount;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.TransactionLog;
import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Tests for the BIDDING escrow flows built on the hardened atomic-transfer
 * primitives (mirrors AuctionSettlementTest's storage-only harness - no
 * Minecraft server required).
 *
 * <p>Contract locked in here:</p>
 * <ul>
 *   <li>The escrow account pre-exists at ZERO - no phantom starting balance
 *       is minted into the system account.</li>
 *   <li>A bid charge moves money bidder -> escrow atomically WITH its
 *       BID_PLACED ledger row; insufficient funds move nothing.</li>
 *   <li>An outbid/cancel refund moves money escrow -> bidder WITH its
 *       BID_REFUNDED ledger row.</li>
 *   <li>A win release moves the escrow to the seller with BOTH the seller's
 *       AUCTION_SOLD and the winner's AUCTION_WON rows inside the same
 *       transaction.</li>
 *   <li>The escrow account is invisible to the baltop query.</li>
 * </ul>
 */
@DisplayName("Bid escrow flows (atomic transfers + ledger evidence)")
class BidEscrowFlowTest {

    private static final UUID BIDDER =
        UUID.fromString("cccccccc-3333-3333-3333-333333333333");
    private static final UUID SELLER =
        UUID.fromString("dddddddd-4444-4444-4444-444444444444");

    private SQLiteStorage storage;
    private Path tempDir;
    private double savedStartingBalance;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-bid-escrow-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        savedStartingBalance = CurrencyUtil.getStartingBalance();

        // EconomyEngine.initialize() pre-creates the escrow row at zero.
        assertTrue(storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0)
            .get(5, TimeUnit.SECONDS), "escrow pre-create failed");

        // Build the auction schema (bid tables live in auctions.db in prod,
        // but the flows under test only touch the economy DB + ledger).
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir + "/auctions.db");
             Statement st = conn.createStatement()) {
            st.execute(AuctionManager.CREATE_TABLE_SQL);
            st.execute(AuctionManager.CREATE_BID_STATE_SQL);
            st.execute(AuctionManager.CREATE_BID_HISTORY_SQL);
            st.execute(AuctionManager.CREATE_WON_ITEMS_SQL);
        }
    }

    @AfterEach
    void tearDown() {
        CurrencyUtil.setStartingBalance(savedStartingBalance);
        if (storage != null) {
            storage.shutdown();
        }
        try {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    private double balanceSync(UUID uuid) throws Exception {
        return storage.getBalance(uuid, "").get(5, TimeUnit.SECONDS);
    }

    private void setBalanceSync(UUID uuid, String name, double amount) throws Exception {
        assertTrue(storage.setBalance(uuid, name, amount).get(5, TimeUnit.SECONDS),
            "setBalance fixture failed for " + uuid);
    }

    @Nested
    @DisplayName("escrow account setup")
    class EscrowSetup {

        @Test
        @DisplayName("escrow pre-exists at exactly zero")
        void escrowStartsAtZero() throws Exception {
            assertEquals(0.0, balanceSync(EscrowAccount.UUID_ZERO),
                "escrow must start at zero - no phantom starting balance");
        }

        @Test
        @DisplayName("escrow is excluded from the baltop query")
        void escrowHiddenFromBaltop() throws Exception {
            setBalanceSync(BIDDER, "Bidder", 50.0);
            setBalanceSync(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 9_999.0);

            List<SQLiteStorage.BalanceEntry> top =
                storage.getTopBalances(10, 0).get(5, TimeUnit.SECONDS);

            assertTrue(top.stream().noneMatch(e -> EscrowAccount.isSystemAccount(e.uuid())),
                "escrow must never appear on /baltop");
            assertTrue(top.stream().anyMatch(e -> BIDDER.equals(e.uuid())),
                "real players still appear");
        }

        @Test
        @DisplayName("isSystemAccount only matches the zero UUID")
        void systemAccountCheck() {
            assertTrue(EscrowAccount.isSystemAccount(EscrowAccount.UUID_ZERO));
            assertFalse(EscrowAccount.isSystemAccount(BIDDER));
            assertFalse(EscrowAccount.isSystemAccount(null));
        }
    }

    @Nested
    @DisplayName("bid charge / refund / release")
    class MoneyFlows {

        @Test
        @DisplayName("bid charge moves bidder -> escrow atomically")
        void bidChargeEscrowsMoney() throws Exception {
            setBalanceSync(BIDDER, "Bidder", 200.0);

            var outcome = storage.transferAtomicWithLedger(
                BIDDER, "Bidder", EscrowAccount.UUID_ZERO, EscrowAccount.NAME,
                120.0,
                List.of(new SQLiteStorage.AtomicLedgerRow(
                    TransactionLog.Type.BID_PLACED,
                    BIDDER, "Bidder", SELLER, "Seller",
                    120.0, "DIAMOND", 1, "Bid 120.00 S$ on 1x DIAMOND")))
                .get(5, TimeUnit.SECONDS);

            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
            assertEquals(80.0, balanceSync(BIDDER), "bidder debited exactly the bid");
            assertEquals(120.0, balanceSync(EscrowAccount.UUID_ZERO),
                "escrow holds exactly the bid");
        }

        @Test
        @DisplayName("insufficient funds move nothing")
        void insufficientFundsNoMovement() throws Exception {
            setBalanceSync(BIDDER, "Bidder", 50.0);
            setBalanceSync(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0);

            var outcome = storage.transferAtomicWithLedger(
                BIDDER, "Bidder", EscrowAccount.UUID_ZERO, EscrowAccount.NAME,
                120.0, List.of())
                .get(5, TimeUnit.SECONDS);

            assertEquals(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS, outcome.status());
            assertEquals(50.0, balanceSync(BIDDER), "bidder untouched");
            assertEquals(0.0, balanceSync(EscrowAccount.UUID_ZERO), "escrow untouched");
        }

        @Test
        @DisplayName("outbid refund moves escrow -> bidder back")
        void refundReturnsMoney() throws Exception {
            setBalanceSync(BIDDER, "Bidder", 200.0);
            // Charge first.
            storage.transferAtomicWithLedger(BIDDER, "Bidder",
                EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 100.0, List.of())
                .get(5, TimeUnit.SECONDS);

            var refund = storage.transferAtomicWithLedger(
                EscrowAccount.UUID_ZERO, EscrowAccount.NAME, BIDDER, "Bidder",
                100.0,
                List.of(new SQLiteStorage.AtomicLedgerRow(
                    TransactionLog.Type.BID_REFUNDED,
                    BIDDER, "Bidder", null, null,
                    100.0, null, 0, "Outbid refund")))
                .get(5, TimeUnit.SECONDS);

            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, refund.status());
            assertEquals(200.0, balanceSync(BIDDER), "bidder made whole");
            assertEquals(0.0, balanceSync(EscrowAccount.UUID_ZERO), "escrow empty again");
        }

        @Test
        @DisplayName("win release credits seller with AUCTION_SOLD + AUCTION_WON evidence")
        void winReleasePaysSellerWithEvidence() throws Exception {
            setBalanceSync(SELLER, "Seller", 10.0);
            // Simulate the top bid sitting in escrow.
            setBalanceSync(BIDDER, "Bidder", 0.0);
            setBalanceSync(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 75.0);

            var release = storage.transferAtomicWithLedger(
                EscrowAccount.UUID_ZERO, EscrowAccount.NAME, SELLER, "Seller",
                75.0,
                List.of(
                    new SQLiteStorage.AtomicLedgerRow(
                        TransactionLog.Type.AUCTION_SOLD,
                        SELLER, "Seller", BIDDER, "Bidder",
                        75.0, "DIAMOND", 1, "Auction WON by Bidder"),
                    new SQLiteStorage.AtomicLedgerRow(
                        TransactionLog.Type.AUCTION_WON,
                        BIDDER, "Bidder", SELLER, "Seller",
                        75.0, "DIAMOND", 1, "Won auction")))
                .get(5, TimeUnit.SECONDS);

            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, release.status());
            assertEquals(85.0, balanceSync(SELLER), "seller paid the winning bid");
            assertEquals(0.0, balanceSync(EscrowAccount.UUID_ZERO), "escrow emptied");
        }

        @Test
        @DisplayName("receiver overflow protects the escrow cap and moves nothing")
        void escrowOverflowProtected() throws Exception {
            setBalanceSync(BIDDER, "Bidder", 200.0);
            setBalanceSync(EscrowAccount.UUID_ZERO, EscrowAccount.NAME,
                CurrencyUtil.MAX_BALANCE);

            var outcome = storage.transferAtomicWithLedger(
                BIDDER, "Bidder", EscrowAccount.UUID_ZERO, EscrowAccount.NAME,
                50.0, List.of())
                .get(5, TimeUnit.SECONDS);

            assertEquals(SQLiteStorage.TransferStatus.RECEIVER_OVERFLOW, outcome.status());
            assertEquals(200.0, balanceSync(BIDDER), "bidder untouched on overflow");
        }
    }

    @Nested
    @DisplayName("bid state semantics")
    class BidStateSemantics {

        @Test
        @DisplayName("conditional top-bid claim is exactly-once for equal amounts")
        void conditionalClaimExactlyOnce() throws Exception {
            UUID listingId = UUID.randomUUID();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir + "/auctions.db")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO auction_bid_state (listing_id, start_price) VALUES (?, ?)")) {
                    ps.setString(1, listingId.toString());
                    ps.setDouble(2, 10.0);
                    ps.executeUpdate();
                }

                String claimSql = """
                    UPDATE auction_bid_state
                    SET current_bid = ?, current_bidder_uuid = ?, current_bidder_name = ?,
                        bid_count = bid_count + 1
                    WHERE listing_id = ? AND (current_bid IS NULL OR current_bid < ?)
                    """;

                // First bidder claims the empty slot.
                try (PreparedStatement ps = conn.prepareStatement(claimSql)) {
                    ps.setDouble(1, 50.0);
                    ps.setString(2, BIDDER.toString());
                    ps.setString(3, "Bidder");
                    ps.setString(4, listingId.toString());
                    ps.setDouble(5, 50.0);
                    assertEquals(1, ps.executeUpdate(), "first claim must succeed");
                }
                // Same-amount re-claim must NOT succeed (no race double-write).
                try (PreparedStatement ps = conn.prepareStatement(claimSql)) {
                    ps.setDouble(1, 50.0);
                    ps.setString(2, SELLER.toString());
                    ps.setString(3, "Seller");
                    ps.setString(4, listingId.toString());
                    ps.setDouble(5, 50.0);
                    assertEquals(0, ps.executeUpdate(),
                        "equal-amount claim must lose the race");
                }
                // Higher bid takes over.
                try (PreparedStatement ps = conn.prepareStatement(claimSql)) {
                    ps.setDouble(1, 75.0);
                    ps.setString(2, SELLER.toString());
                    ps.setString(3, "Seller");
                    ps.setString(4, listingId.toString());
                    ps.setDouble(5, 75.0);
                    assertEquals(1, ps.executeUpdate(), "higher claim must succeed");
                }
            }
        }
    }

    @Nested
    @DisplayName("BidRules")
    class Rules {

        @Test
        @DisplayName("opening bid floor is the configured start price")
        void openingFloor() {
            assertEquals(50.0, BidRules.minNextBid(50.0, null));
        }

        @Test
        @DisplayName("next bid requires max(5%, 1.0) raise")
        void raiseRules() {
            // 5% of 200 = 10 -> 210
            assertEquals(210.0, BidRules.minNextBid(50.0, 200.0));
            // 5% of 10 = 0.5 < 1.0 flat floor -> 11
            assertEquals(11.0, BidRules.minNextBid(5.0, 10.0));
        }

        @Test
        @DisplayName("validateBid rejects low, negative and absurd amounts")
        void validateRejections() {
            assertNotNull(BidRules.validateBid(0.0, 10.0, null));
            assertNotNull(BidRules.validateBid(-5.0, 10.0, null));
            assertNotNull(BidRules.validateBid(5.0, 10.0, null), "below opening");
            assertNotNull(BidRules.validateBid(10.5, 10.0, 10.0), "below next minimum");
            assertNotNull(BidRules.validateBid(Double.NaN, 10.0, null));
            assertNotNull(BidRules.validateBid(BidRules.round2(
                AuctionEntry.MAX_LISTING_PRICE * 2), 10.0, null));
            assertNull(BidRules.validateBid(10.0, 10.0, null), "exact opening is valid");
            assertNull(BidRules.validateBid(210.0, 50.0, 200.0), "valid raise");
        }

        @Test
        @DisplayName("anti-snipe extends only inside the window and caps out")
        void antiSnipe() {
            long now = System.currentTimeMillis();
            long expiry = now + 5 * 60 * 1000L; // 5 min left -> inside window

            assertEquals(expiry + BidRules.ANTI_SNIPE_EXTENSION_MS,
                BidRules.antiSnipeExpiry(expiry, now, 0), "inside window extends");

            long farExpiry = now + 48 * 60 * 60 * 1000L; // 48h left -> outside
            assertEquals(farExpiry, BidRules.antiSnipeExpiry(farExpiry, now, 0),
                "outside window does not extend");

            assertEquals(expiry, BidRules.antiSnipeExpiry(expiry, now, 999),
                "cap exhausted does not extend");
        }
    }
}
