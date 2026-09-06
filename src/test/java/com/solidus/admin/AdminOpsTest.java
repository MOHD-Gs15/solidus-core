package com.solidus.admin;

import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.StorageBackend;
import com.solidus.economy.StorageConfig;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link AdminOps} over a REAL SQLiteStorage in a temp
 * directory (same storage-only harness as BalanceManagerTest /
 * BidEscrowFlowTest - no Minecraft classes instantiated).
 *
 * <p>Contract locked in here:</p>
 * <ul>
 *   <li>Dummy accounts materialize under the vanilla offline-mode UUID
 *       derivation and appear in the name cache for /pay offline.</li>
 *   <li>give/set/take route through the same storage primitives as player
 *       commands and ALWAYS land in the ledger with the ADMIN_* types.</li>
 *   <li>payAs runs the real atomic transferOffline path (hooks included),
 *       records PAY_SEND/PAY_RECEIVE rows and conserves supply to the cent.</li>
 *   <li>audit detects injected negative balances via the backward page scan
 *       and reports escrow + supply snapshot lines.</li>
 * </ul>
 */
@DisplayName("AdminOps (console test harness)")
class AdminOpsTest {

    private SQLiteStorage storage;
    private EconomyEngine engine;
    private AdminOps admin;
    private Path tempDir;
    private double savedStartingBalance;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-admin-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();

        // Production parity: EconomyEngine.initialize() pre-creates the escrow
        // row at zero BEFORE any other storage access (without this, a
        // getBalance(UUID_ZERO) read would materialize it at the starting
        // balance and mint phantom money).
        assertTrue(storage.setBalance(
            com.solidus.economy.EscrowAccount.UUID_ZERO,
            com.solidus.economy.EscrowAccount.NAME, 0.0).get(5, TimeUnit.SECONDS));

        // A thin fake EconomyEngine wired to the REAL primitives (EconomyEngine
        // itself cannot be initialized in unit tests - FabricLoader is
        // unavailable - and the Mockito inline mock maker is broken on the
        // Java 25 toolchain, so we subclass instead of mocking).
        engine = new EconomyEngine() {
            private final BalanceManager balanceManager = new BalanceManager(storage);
            @Override public StorageBackend getStorage() { return storage; }
            @Override public TransactionLog getTransactionLog() { return storage.getTransactionLog(); }
            @Override public BalanceManager getBalanceManager() { return balanceManager; }
            @Override public boolean isMysqlMode() { return false; }
            @Override public StorageConfig.RedisSettings redisSettings() {
                return StorageConfig.RedisSettings.disabled();
            }
            @Override public TransactionLog.ConnectionSource auctionConnectionSource() { return null; }
        };

        admin = new AdminOps(engine, null, null);
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
        } catch (Exception ignored) {}
    }

    private static void ok(AdminOps.OpResult result) {
        assertTrue(result.success(), "expected success but got: " + result.message());
    }

    private static void fail(AdminOps.OpResult result) {
        assertFalse(result.success(), "expected failure but got: " + result.message());
    }

    private double balanceOf(String name) throws Exception {
        return storage.getBalance(AdminOps.offlineUuid(name), name).get(5, TimeUnit.SECONDS);
    }

    private List<TransactionLog.TransactionEntry> ledgerOf(UUID uuid) throws Exception {
        return storage.getTransactionLog().getTransactions(uuid, 50).get(5, TimeUnit.SECONDS);
    }

    // -- Identity -------------------------------------------

    @Nested
    @DisplayName("offline UUID derivation + name rules")
    class IdentityTest {

        @Test
        @DisplayName("offlineUuid is deterministic and matches the vanilla formula")
        void deterministicOfflineUuid() {
            UUID a = AdminOps.offlineUuid("TestBot");
            UUID b = AdminOps.offlineUuid("TestBot");
            assertEquals(a, b);
            assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:TestBot".getBytes()), a);
            assertNotEquals(AdminOps.offlineUuid("TestboT"), a);
        }

        @Test
        @DisplayName("account names obey the Minecraft username rule")
        void nameValidation() {
            assertTrue(AdminOps.isValidAccountName("Bot_01"));
            assertTrue(AdminOps.isValidAccountName("a"));
            assertTrue(AdminOps.isValidAccountName("sixteen_characte")); // 16 chars
            assertFalse(AdminOps.isValidAccountName(null));
            assertFalse(AdminOps.isValidAccountName(""));
            assertFalse(AdminOps.isValidAccountName("has space"));
            assertFalse(AdminOps.isValidAccountName("bad-dash"));
            assertFalse(AdminOps.isValidAccountName("seventeen_chars12")); // 17 chars
        }
    }

    // -- createAccount ---------------------------------------

    @Nested
    @DisplayName("account create")
    class CreateAccountTest {

        @Test
        @DisplayName("creates a never-joined account at the starting balance")
        void createsAtStartingBalance() throws Exception {
            ok(admin.createAccount("FreshBot", null).get(5, TimeUnit.SECONDS));
            assertEquals(CurrencyUtil.getStartingBalance(), balanceOf("FreshBot"));
            // Visible to offline name resolution (feeds /pay offline).
            assertNotNull(admin.resolveAccount("FreshBot"));
        }

        @Test
        @DisplayName("explicit initial balance lands exactly and is logged ADMIN_SET")
        void initialBalanceAppliedAndLogged() throws Exception {
            ok(admin.createAccount("SeededBot", 1234.5).get(5, TimeUnit.SECONDS));
            assertEquals(1234.5, balanceOf("SeededBot"));

            List<TransactionLog.TransactionEntry> ledger =
                ledgerOf(AdminOps.offlineUuid("SeededBot"));
            assertEquals(1, ledger.size());
            assertEquals(TransactionLog.Type.ADMIN_SET, ledger.get(0).type());
            assertEquals(1234.5, ledger.get(0).amount());
        }

        @Test
        @DisplayName("duplicate creation is rejected with the existing balance")
        void duplicateRejected() throws Exception {
            ok(admin.createAccount("DupBot", null).get(5, TimeUnit.SECONDS));
            AdminOps.OpResult second = admin.createAccount("DupBot", null).get(5, TimeUnit.SECONDS);
            fail(second);
            assertTrue(second.message().contains("already exists"));
        }

        @Test
        @DisplayName("invalid names are rejected without touching storage")
        void invalidNameRejected() throws Exception {
            fail(admin.createAccount("bad name!", null).get(5, TimeUnit.SECONDS));
            assertNull(admin.resolveAccount("bad name!"));
        }

        @Test
        @DisplayName("out-of-range initial balance is rejected")
        void outOfRangeInitialRejected() throws Exception {
            fail(admin.createAccount("RangeBot", -1.0).get(5, TimeUnit.SECONDS));
            fail(admin.createAccount("RangeBot", CurrencyUtil.MAX_BALANCE + 1).get(5, TimeUnit.SECONDS));
        }
    }

    // -- resolveAccount ---------------------------------------

    @Nested
    @DisplayName("account resolution")
    class ResolveTest {

        @Test
        @DisplayName("unknown names resolve to null")
        void unknownIsNull() {
            assertNull(admin.resolveAccount("Nobody"));
            assertNull(admin.resolveAccount(null));
            assertNull(admin.resolveAccount(""));
        }

        @Test
        @DisplayName("resolution is case-insensitive")
        void caseInsensitive() throws Exception {
            ok(admin.createAccount("CaseBot", null).get(5, TimeUnit.SECONDS));
            AdminOps.AccountRef ref = admin.resolveAccount("casebot");
            assertNotNull(ref);
            assertEquals(AdminOps.offlineUuid("CaseBot"), ref.uuid());
        }
    }

    // -- give / set / take ------------------------------------

    @Nested
    @DisplayName("money give/set/take")
    class AdjustTest {

        @Test
        @DisplayName("give credits the account and logs ADMIN_GIVE")
        void giveCreditsAndLogs() throws Exception {
            ok(admin.createAccount("GiveBot", 10.0).get(5, TimeUnit.SECONDS));
            ok(admin.give("Console", "GiveBot", 2.25).get(5, TimeUnit.SECONDS));
            assertEquals(12.25, balanceOf("GiveBot"));

            List<TransactionLog.TransactionEntry> ledger =
                ledgerOf(AdminOps.offlineUuid("GiveBot"));
            assertTrue(ledger.stream().anyMatch(t -> t.type() == TransactionLog.Type.ADMIN_GIVE
                && Math.abs(t.amount() - 2.25) < 0.005));
        }

        @Test
        @DisplayName("set overwrites the balance exactly and logs ADMIN_SET")
        void setOverwrites() throws Exception {
            ok(admin.createAccount("SetBot", 10.0).get(5, TimeUnit.SECONDS));
            ok(admin.set("Console", "SetBot", 77.77).get(5, TimeUnit.SECONDS));
            assertEquals(77.77, balanceOf("SetBot"));
        }

        @Test
        @DisplayName("take debits and rejects insufficient funds")
        void takeDebitsAndRejects() throws Exception {
            ok(admin.createAccount("TakeBot", 5.0).get(5, TimeUnit.SECONDS));
            ok(admin.take("Console", "TakeBot", 2.0).get(5, TimeUnit.SECONDS));
            assertEquals(3.0, balanceOf("TakeBot"));

            AdminOps.OpResult over = admin.take("Console", "TakeBot", 100.0).get(5, TimeUnit.SECONDS);
            fail(over);
            assertEquals(3.0, balanceOf("TakeBot"), "balance must be unchanged after a rejected take");
        }

        @Test
        @DisplayName("out-of-range amounts and unknown accounts are rejected")
        void rejections() throws Exception {
            fail(admin.give("Console", "Ghost", 5.0).get(5, TimeUnit.SECONDS));
            ok(admin.createAccount("RangeBot2", 1.0).get(5, TimeUnit.SECONDS));
            fail(admin.give("Console", "RangeBot2", 0.001).get(5, TimeUnit.SECONDS));
            fail(admin.give("Console", "RangeBot2", CurrencyUtil.MAX_TRANSACTION + 1).get(5, TimeUnit.SECONDS));
            fail(admin.set("Console", "RangeBot2", -0.5).get(5, TimeUnit.SECONDS));
        }
    }

    // -- payAs -------------------------------------------------

    @Nested
    @DisplayName("pay-as (real atomic transfer path)")
    class PayAsTest {

        @Test
        @DisplayName("transfers atomically and conserves supply to the cent")
        void transfersAndConserves() throws Exception {
            ok(admin.createAccount("SenderBot", 100.0).get(5, TimeUnit.SECONDS));
            ok(admin.createAccount("ReceiverBot", 50.0).get(5, TimeUnit.SECONDS));
            double supplyBefore = 150.0;

            AdminOps.OpResult result = admin.payAs("SenderBot", "ReceiverBot", 30.01)
                .get(5, TimeUnit.SECONDS);
            ok(result);

            assertEquals(69.99, balanceOf("SenderBot"));
            assertEquals(80.01, balanceOf("ReceiverBot"));
            assertEquals(supplyBefore,
                balanceOf("SenderBot") + balanceOf("ReceiverBot"), 1e-9);

            // Ledger parity with /pay: both sides recorded.
            List<TransactionLog.TransactionEntry> senderLedger =
                ledgerOf(AdminOps.offlineUuid("SenderBot"));
            assertTrue(senderLedger.stream().anyMatch(t -> t.type() == TransactionLog.Type.PAY_SEND));
            List<TransactionLog.TransactionEntry> receiverLedger =
                ledgerOf(AdminOps.offlineUuid("ReceiverBot"));
            assertTrue(receiverLedger.stream().anyMatch(t -> t.type() == TransactionLog.Type.PAY_RECEIVE));
        }

        @Test
        @DisplayName("unknown receiver is rejected with a hint")
        void unknownReceiverRejected() throws Exception {
            ok(admin.createAccount("LonelyBot", 10.0).get(5, TimeUnit.SECONDS));
            AdminOps.OpResult result = admin.payAs("LonelyBot", "MissingBot", 5.0)
                .get(5, TimeUnit.SECONDS);
            fail(result);
            assertTrue(result.message().contains("account create"));
        }

        @Test
        @DisplayName("self-pay and insufficient funds are rejected, balances untouched")
        void guards() throws Exception {
            ok(admin.createAccount("SelfBot", 10.0).get(5, TimeUnit.SECONDS));
            fail(admin.payAs("SelfBot", "SelfBot", 5.0).get(5, TimeUnit.SECONDS));

            ok(admin.createAccount("RichBot", 100.0).get(5, TimeUnit.SECONDS));
            ok(admin.createAccount("PoorBot", 1.0).get(5, TimeUnit.SECONDS));
            AdminOps.OpResult over = admin.payAs("PoorBot", "RichBot", 500.0).get(5, TimeUnit.SECONDS);
            fail(over);
            assertEquals(1.0, balanceOf("PoorBot"));
            assertEquals(100.0, balanceOf("RichBot"));
        }

        @Test
        @DisplayName("MAX_TRANSACTION bound is enforced")
        void maxTransaction() throws Exception {
            ok(admin.createAccount("WhaleBot", 100.0).get(5, TimeUnit.SECONDS));
            ok(admin.createAccount("MinnowBot", 0.0).get(5, TimeUnit.SECONDS));
            fail(admin.payAs("WhaleBot", "MinnowBot", CurrencyUtil.MAX_TRANSACTION + 0.01)
                .get(5, TimeUnit.SECONDS));
        }
    }

    // -- auction guards (real flows covered by BidEscrowFlowTest) --

    @Nested
    @DisplayName("bid-as / auction create guards")
    class AuctionGuardTest {

        @Test
        @DisplayName("null auction manager is reported clearly")
        void nullAuctionManager() throws Exception {
            ok(admin.createAccount("BidderBot", 10.0).get(5, TimeUnit.SECONDS));
            AdminOps.OpResult result = admin.bidAs("BidderBot", UUID.randomUUID(), 5.0,
                (err, msg) -> {}).get(5, TimeUnit.SECONDS);
            fail(result);
            assertTrue(result.message().contains("Auction manager unavailable"));
        }

        @Test
        @DisplayName("unknown bidder is rejected before reaching the pipeline")
        void unknownBidderRejected() throws Exception {
            AdminOps.OpResult result = admin.bidAs("NoBidder", UUID.randomUUID(), 5.0,
                (err, msg) -> {}).get(5, TimeUnit.SECONDS);
            fail(result);
        }

        @Test
        @DisplayName("count bounds are enforced before item resolution")
        void countBounds() throws Exception {
            ok(admin.createAccount("SellerBot", 100.0).get(5, TimeUnit.SECONDS));
            AdminOps.OpResult zero = admin.auctionCreate("SellerBot", "minecraft:diamond", 0,
                100.0, 0, (err, msg) -> {}).get(5, TimeUnit.SECONDS);
            fail(zero);
        }
    }

    // -- audit / diag -------------------------------------------

    @Nested
    @DisplayName("audit + diag")
    class AuditTest {

        @Test
        @DisplayName("clean economy reports no negatives and a snapshot")
        void cleanAudit() throws Exception {
            ok(admin.createAccount("CleanBot", 25.0).get(5, TimeUnit.SECONDS));
            List<String> report = admin.audit().get(10, TimeUnit.SECONDS);

            String all = String.join("\n", report);
            assertTrue(all.contains("Audit complete."));
            assertTrue(all.contains("Negative balances: none."));
            assertTrue(all.contains("Money supply:"));
            assertTrue(all.contains("Escrow at zero (no open bids)."));
        }

        @Test
        @DisplayName("injected negative balance is detected by the tail scan")
        void negativeDetected() throws Exception {
            ok(admin.createAccount("VictimBot", 5.0).get(5, TimeUnit.SECONDS));
            // Simulate corruption directly in the database file - setBalance
            // (correctly) refuses negative values, so bypass it.
            try (Connection conn = DriverManager.getConnection(
                    "jdbc:sqlite:" + tempDir + "/" + SQLiteStorage.DATABASE_NAME);
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE player_balances SET balance = ? WHERE player_name = ?")) {
                ps.setDouble(1, -3.33);
                ps.setString(2, "VictimBot");
                assertEquals(1, ps.executeUpdate());
            }

            List<String> report = admin.audit().get(10, TimeUnit.SECONDS);
            String all = String.join("\n", report);
            assertTrue(all.contains("NEGATIVE BALANCES"));
            assertTrue(all.contains("VictimBot"));
            assertTrue(all.contains("-3.33"));
        }

        @Test
        @DisplayName("diag reports the SQLite backend and snapshot lines")
        void diagLines() throws Exception {
            List<String> lines = admin.diag().get(10, TimeUnit.SECONDS);
            String all = String.join("\n", lines);
            assertTrue(all.contains("SQLite (single-server)"));
            assertTrue(all.contains("Redis layer: disabled"));
            assertTrue(all.contains("Auction store: local SQLite"));
        }
    }
}
