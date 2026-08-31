package com.solidus.auction;

import com.solidus.economy.BalanceManager;
import com.solidus.economy.SQLiteStorage;
import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BalanceManager#settleAuctionPurchase} - the atomic
 * buyer-to-seller settlement used by the auction house.
 *
 * Contract locked in here:
 * <ul>
 *   <li>The payment moves inside ONE SQLite transaction: a crash or failure
 *       between the buyer debit and the seller credit is impossible - both
 *       legs commit or neither does.</li>
 *   <li>Failures (insufficient funds, seller balance cap) move NOTHING and
 *       leave both balances untouched, so the auction layer can safely roll
 *       the listing back to ACTIVE.</li>
 *   <li>NO generic transfer hooks are consulted (no {@code allowTransfer}
 *       veto, no {@code afterTransfer} notification): the auction flow runs
 *       its own hook lifecycle ({@code allowAuctionPurchase} veto before
 *       money moves, {@code afterAuctionSale} after settlement). Double
 *       counting daily transfer limits or stacking transfer tax on top of
 *       auction tax would be a governance bug.</li>
 * </ul>
 */
@DisplayName("Atomic auction settlement (settleAuctionPurchase)")
class AuctionSettlementTest {

    private static final UUID BUYER =
        UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID SELLER =
        UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");

    private SQLiteStorage storage;
    private Path tempDir;
    private double savedStartingBalance;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-auction-settle-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        savedStartingBalance = CurrencyUtil.getStartingBalance();
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

    private BalanceManager.TransferResult settleSync(double price) throws Exception {
        BalanceManager manager = new BalanceManager(storage);
        return manager.settleAuctionPurchase(
            BUYER, "Buyer", SELLER, "Seller", price).get(5, TimeUnit.SECONDS);
    }

    private double balanceSync(UUID uuid) throws Exception {
        return storage.getBalance(uuid, "").get(5, TimeUnit.SECONDS);
    }

    private void setBalanceSync(UUID uuid, String name, double amount) throws Exception {
        assertTrue(storage.setBalance(uuid, name, amount).get(5, TimeUnit.SECONDS),
            "setBalance fixture failed for " + uuid);
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("successful settlement debits buyer and credits seller atomically")
        void settlementMovesMoneyBuyerToSeller() throws Exception {
            setBalanceSync(BUYER, "Buyer", 300.0);
            setBalanceSync(SELLER, "Seller", 50.0);

            BalanceManager.TransferResult result = settleSync(120.0);

            assertTrue(result.success(), "expected success, got: " + result.message());
            assertEquals(180.0, result.senderNewBalance(), 1e-9,
                "buyer new balance must be reported");
            assertEquals(170.0, result.receiverNewBalance(), 1e-9,
                "seller new balance must be reported");
            assertEquals(180.0, balanceSync(BUYER), 1e-9);
            assertEquals(170.0, balanceSync(SELLER), 1e-9);
        }

        @Test
        @DisplayName("money supply is conserved (no duplication, no vanishing)")
        void moneySupplyConserved() throws Exception {
            setBalanceSync(BUYER, "Buyer", 999.99);
            setBalanceSync(SELLER, "Seller", 0.01);

            settleSync(250.0);

            assertEquals(1000.0, balanceSync(BUYER) + balanceSync(SELLER), 1e-9,
                "total money must be identical before and after settlement");
        }
    }

    @Nested
    @DisplayName("failure paths move nothing")
    class FailurePaths {

        @Test
        @DisplayName("insufficient funds fails and both balances stay untouched")
        void insufficientFundsMovesNothing() throws Exception {
            setBalanceSync(BUYER, "Buyer", 40.0);
            setBalanceSync(SELLER, "Seller", 50.0);

            BalanceManager.TransferResult result = settleSync(120.0);

            assertFalse(result.success());
            assertEquals("Insufficient funds.", result.message());
            assertEquals(40.0, balanceSync(BUYER), 1e-9,
                "buyer must NOT be debited on a failed settlement");
            assertEquals(50.0, balanceSync(SELLER), 1e-9,
                "seller must NOT be credited on a failed settlement");
        }

        @Test
        @DisplayName("seller at balance cap fails cleanly (receiver overflow)")
        void sellerAtCapMovesNothing() throws Exception {
            setBalanceSync(BUYER, "Buyer", 500.0);
            setBalanceSync(SELLER, "Seller", CurrencyUtil.MAX_BALANCE);

            BalanceManager.TransferResult result = settleSync(100.0);

            assertFalse(result.success(),
                "crediting a maxed-out seller must fail, not silently truncate");
            assertTrue(result.message().contains("seller balance limit"),
                "message should explain the seller cap, got: " + result.message());
            assertEquals(500.0, balanceSync(BUYER), 1e-9);
            assertEquals(CurrencyUtil.MAX_BALANCE, balanceSync(SELLER), 1e-9);
        }

        @Test
        @DisplayName("pre-validation rejects zero, negative and self purchases")
        void preValidationGuardsStayIntact() throws Exception {
            setBalanceSync(BUYER, "Buyer", 100.0);

            BalanceManager manager = new BalanceManager(storage);

            assertFalse(manager.settleAuctionPurchase(
                BUYER, "Buyer", SELLER, "Seller", 0.0).get(5, TimeUnit.SECONDS).success());
            assertFalse(manager.settleAuctionPurchase(
                BUYER, "Buyer", SELLER, "Seller", -5.0).get(5, TimeUnit.SECONDS).success());
            assertFalse(manager.settleAuctionPurchase(
                BUYER, "Buyer", BUYER, "Buyer", 10.0).get(5, TimeUnit.SECONDS).success());

            assertEquals(100.0, balanceSync(BUYER), 1e-9);
        }
    }
}
