package com.solidus.auction;

import com.solidus.economy.EconomyEngine;
import com.solidus.economy.StorageBackend;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.TransactionLog;
import com.solidus.util.ConfigManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WS-0 live-boot regression (Task A, أ-3 discovery — 2.3.3).
 *
 * <p>Found by the FIRST real server boot of the project's history: the
 * SQLITE-dialect {@code initialize()} created the schema through a
 * try-with-resources on {@code connectionSource.open()} — which hands out the
 * SHARED persistent connection for SQLITE — and closed it the moment the DDL
 * block exited. Every later store operation then died with
 * {@code SQLException: database connection closed} (listing saves, startup
 * sweeps, bid recovery), while all 462 JVM-only tests stayed green because
 * none of them drove a real {@link AuctionManager} through the SQLITE
 * initialize + use lifecycle. The MYSQL flavor borrows pooled connections and
 * MUST keep closing them — the fix splits the two paths; this test pins the
 * SQLITE side so the closed-connection class can never ship again.</p>
 */
@DisplayName("AuctionManager SQLITE dialect: store must stay usable after initialize()")
class AuctionSqliteStoreLivenessTest {

    private Path tempDir;
    private AuctionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-auction-liveness-");
        ConfigManager.initialize(tempDir);

        // Pure-Java stubs (NO Mockito anywhere in this test: the inline mock
        // maker cannot instrument classes OR interfaces on the Java 25
        // toolchain — same workaround as AdminOpsTest/MySqlAuctionDialectTest).
        StorageBackend stubBackend = new StorageBackend() {
            @Override public void initialize() { }
            @Override public void shutdown() { }
            @Override public java.util.concurrent.CompletableFuture<Double> getBalance(
                    UUID uuid, String playerName) {
                return java.util.concurrent.CompletableFuture.completedFuture(0.0);
            }
            @Override public java.util.concurrent.CompletableFuture<List<SQLiteStorage.BalanceEntry>>
                    getTopBalances(int limit, int offset) {
                return java.util.concurrent.CompletableFuture.completedFuture(List.of());
            }
            @Override public java.util.concurrent.CompletableFuture<Integer> getBalanceEntryCount() {
                return java.util.concurrent.CompletableFuture.completedFuture(0);
            }
            @Override public java.util.concurrent.CompletableFuture<SQLiteStorage.EconomyStats>
                    getEconomyStats() {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public java.util.concurrent.CompletableFuture<Boolean> setBalance(
                    UUID uuid, String playerName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            }
            @Override public java.util.concurrent.CompletableFuture<Double> addBalance(
                    UUID uuid, String playerName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(amount);
            }
            @Override public java.util.concurrent.CompletableFuture<Double> subtractBalance(
                    UUID uuid, String playerName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(amount);
            }
            @Override public java.util.concurrent.CompletableFuture<Boolean> hasBalance(
                    UUID uuid, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            @Override public java.util.concurrent.CompletableFuture<SQLiteStorage.TransferOutcome>
                    transferAtomic(UUID senderUuid, String senderName,
                                   UUID receiverUuid, String receiverName, double amount) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public java.util.concurrent.CompletableFuture<SQLiteStorage.TransferOutcome>
                    transferAtomicWithLedger(UUID senderUuid, String senderName,
                                             UUID receiverUuid, String receiverName,
                                             double amount,
                                             List<SQLiteStorage.AtomicLedgerRow> ledgerRows) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public TransactionLog getTransactionLog() { return null; }
            @Override public Map<UUID, String> getPlayerNameCache() { return Map.of(); }
        };
        EconomyEngine engine = new EconomyEngine() {
            @Override public StorageBackend getStorage() { return stubBackend; }
        };
        manager = new AuctionManager(engine);
        manager.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            manager.shutdown();
        }
        Files.walk(tempDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(p -> p.toFile().delete());
    }

    @Test
    @DisplayName("the shared SQLITE connection survives initialize() (WS-0 live-boot bug)")
    void storeIsUsableAfterInitialize() throws Exception {
        // Before the fix this completed exceptionally with
        // "java.sql.SQLException: database connection closed" on every call.
        List<AuctionEntry> active =
            assertDoesNotThrow(() -> manager.getActiveListings().get(5, TimeUnit.SECONDS),
                "store reads must work after initialize() — the shared SQLITE "
                    + "connection must NOT be closed by the schema DDL block");
        assertNotNull(active);
        assertTrue(active.isEmpty(), "fresh store has no active listings");
    }

    @Test
    @DisplayName("rows written before init are readable after init (full lifecycle)")
    void listingRowSurvivesAndIsReadable() throws Exception {
        UUID listingId = UUID.randomUUID();
        // ConfigManager.initialize() nests the store under <run>/config/solidus
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:"
                + tempDir.resolve("config").resolve("solidus").resolve("auctions.db").toAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO auction_listings (listing_id, seller_uuid, seller_name, "
                     + "material_name, quantity, item_nbt, price, listed_timestamp, "
                     + "expire_timestamp, status) VALUES (?,?,?,?,?,?,?,?,?,0)")) {
            ps.setString(1, listingId.toString());
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, "Alice");
            ps.setString(4, "diamond");
            ps.setInt(5, 5);
            ps.setString(6, null);
            ps.setDouble(7, 500.0);
            ps.setLong(8, System.currentTimeMillis());
            ps.setLong(9, System.currentTimeMillis() + AuctionEntry.DEFAULT_DURATION_MS);
            assertEquals(1, ps.executeUpdate(), "DDL from initialize() must have created the table");
        }

        List<AuctionEntry> active = manager.getActiveListings().get(5, TimeUnit.SECONDS);
        assertEquals(1, active.size(), "the listing written by the store's own connection is visible");
        assertEquals(listingId, active.get(0).listingId());
        assertEquals("diamond", active.get(0).materialName());
        assertEquals(500.0, active.get(0).price(), 0.0001);
    }
}
