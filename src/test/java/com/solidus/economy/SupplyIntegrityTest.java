package com.solidus.economy;

import com.solidus.util.ConfigManager;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supply-integrity checker behaviour on a REAL SQLite store (2.2.4 —
 * DB scaling plan §7). Drives money through the production
 * {@link SQLiteStorage} + {@link TransactionLog} pair and asserts the
 * replay arithmetic end to end:
 *
 * <ul>
 *   <li>bootstrap accepts the current books as the baseline;</li>
 *   <li>metered windows (SHOP_SELL / ADMIN_GIVE / ADMIN_TAKE / ADMIN_SET
 *       delta) replay to zero unexplained drift and advance the checkpoint;</li>
 *   <li>an unmetered mint (balance created without a ledger row) is detected
 *       with the EXACT expected unexplained figure, the checkpoint stays
 *       sticky, and rebase clears it;</li>
 *   <li>the row-growth term covers auto-created rows (starting balance).</li>
 * </ul>
 *
 * Uses a same-thread executor, so every CompletableFuture is already
 * completed when the call returns.
 */
@DisplayName("SupplyIntegrity: ledger replay + drift detection (SQLite)")
class SupplyIntegrityTest {

    @TempDir
    Path tempDir;

    private SQLiteStorage storage;
    private SupplyIntegrity integrity;
    private TransactionLog log;

    private static final double TOLERANCE = 0.01;

    private ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
        };
    }

    @BeforeEach
    void setUp() {
        ConfigManager.initialize(tempDir);
        storage = new SQLiteStorage(tempDir.toAbsolutePath().toString());
        storage.initialize();
        log = storage.getTransactionLog();
        integrity = new SupplyIntegrity(log, TransactionLog.Dialect.SQLITE,
            directExecutor(), TOLERANCE);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    private SupplyIntegrity.Report run() {
        return integrity.runOnce().join();
    }

    /**
     * The storage's ledger writes run on ITS internal executor — poll until
     * the expected row count is committed so the checker's windowed SUM is
     * deterministic (production callers have the same eventual visibility).
     */
    private void awaitLedgerCount(int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                long count = log.withConnection(conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT COUNT(*) c FROM transaction_log");
                         ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong("c");
                    }
                });
                if (count >= expected) {
                    return;
                }
            } catch (Exception ignored) {
                // retry until the deadline
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("ledger rows did not commit within 10s (expected " + expected + ")");
    }

    @Test
    @DisplayName("bootstrap accepts the current books and reports itself")
    void bootstrapEstablishesBaseline() {
        // Escrow pre-creation mirrors EconomyEngine.initialize()
        storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0).join();
        storage.getBalance(UUID.randomUUID(), "FirstJoin").join();

        SupplyIntegrity.Report report = run();
        assertTrue(report.bootstrapped(), "first run must establish the baseline");
        assertTrue(report.withinTolerance());
        assertTrue(report.observedSupply() > 0, "starting-balance row counted");
        assertEquals(report.observedSupply(), report.expectedSupply(), 0.0001);
    }

    @Test
    @DisplayName("metered window (shop + admin give/take + admin set delta) replays to zero drift")
    void meteredWindowReplaysBalanced() {
        UUID player = UUID.randomUUID();
        storage.getBalance(player, "Trader").join(); // row mint: starting (covered pre-baseline)
        run(); // bootstrap

        // SHOP_SELL faucet: +25.00 (ledger row + real credit)
        storage.addBalance(player, "Trader", 25.00).join();
        log.log(TransactionLog.Type.SHOP_SELL, player, "Trader", null, null, 25.00, null, 0, "sell x");

        // ADMIN_GIVE faucet: +100.00
        storage.addBalance(player, "Trader", 100.00).join();
        log.log(TransactionLog.Type.ADMIN_GIVE, player, "Trader", null, null, 100.00, null, 0, "give");

        // ADMIN_TAKE sink: -40.00
        double afterTake = storage.subtractBalance(player, "Trader", 40.00).join();
        assertTrue(afterTake > 0);
        log.log(TransactionLog.Type.ADMIN_TAKE, player, "Trader", null, null, 40.00, null, 0, "take");

        // ADMIN_SET with the 2.2.4 signed-delta semantics: 0.0 balance set away.
        double oldBalance = storage.getBalance(player, "Trader").join();
        double newBalance = oldBalance + 37.50;
        storage.setBalance(player, "Trader", newBalance).join();
        log.log(TransactionLog.Type.ADMIN_SET, player, "Trader", null, null, 37.50, null, 0, "set delta");
        awaitLedgerCount(4);

        SupplyIntegrity.Report report = run();
        assertFalse(report.bootstrapped());
        assertTrue(report.withinTolerance(), "expected balanced replay, got unexplained "
            + report.unexplained() + " (" + report.summary() + ")");
        assertEquals(0.0, report.unexplained(), 0.005,
            "metered deltas + row growth must explain the supply move exactly");
        assertEquals(122.50, report.meteredDelta(), 0.005, "25 + 100 - 40 + 37.50");
        assertEquals(0, report.rowsCreated(), "no new rows inside the window");
        assertTrue(report.watermarkTo() > report.watermarkFrom(), "checkpoint advanced");
    }

    @Test
    @DisplayName("row auto-creation inside the window is covered by the row-growth term")
    void rowGrowthTermCoversNewRows() {
        UUID known = UUID.randomUUID();
        storage.getBalance(known, "Seed").join();
        run(); // bootstrap

        // A new player appears (getBalance materializes the starting-balance row).
        // Metered delta: none. Row growth: +starting. Replay must balance exactly.
        UUID newcomer = UUID.randomUUID();
        storage.getBalance(newcomer, "Newcomer").join();

        SupplyIntegrity.Report report = run();
        assertTrue(report.withinTolerance(), "unexplained " + report.unexplained());
        assertEquals(1, report.rowsCreated());
        assertEquals(CurrencyUtil.getStartingBalance(), report.rowGrowthMint(), 0.005);
        assertEquals(0.0, report.unexplained(), 0.005);
    }

    @Test
    @DisplayName("unmetered mint is detected with the exact figure; checkpoint sticky; rebase clears")
    void unmeteredMintDetectedThenRebased() {
        UUID player = UUID.randomUUID();
        storage.getBalance(player, "Player").join();
        run(); // bootstrap

        // A companion mint through the raw storage API with NO ledger row.
        // Actual supply: +starting + 50; replay sees only the row term
        // (+starting) — unexplained must be exactly +50.
        storage.addBalance(UUID.randomUUID(), "GhostBeneficiary", 50.00).join();

        SupplyIntegrity.Report drift = run();
        assertFalse(drift.withinTolerance(), "unmetered mint must be flagged");
        assertEquals(50.0, drift.unexplained(), 0.005,
            "row-growth term absorbs the starting mint; the 50.00 stays unexplained");
        assertEquals("drift — checkpoint kept", drift.note());

        // Sticky baseline: an unchanged re-run reports the same drift.
        SupplyIntegrity.Report again = run();
        assertFalse(again.withinTolerance(), "checkpoint must not have advanced");
        assertEquals(drift.watermarkFrom(), again.watermarkFrom(), "window unchanged");

        // Admin accepts the books: rebase clears, next run bootstraps fresh.
        assertTrue(integrity.rebaseline().join());
        SupplyIntegrity.Report fresh = run();
        assertTrue(fresh.bootstrapped(), "post-rebase run re-establishes the baseline");
    }

    @Test
    @DisplayName("tolerance boundary: drift exactly at tolerance passes, beyond it warns")
    void toleranceBoundary() {
        UUID player = UUID.randomUUID();
        storage.getBalance(player, "Player").join();
        run(); // bootstrap

        // Unmetered mint of exactly the tolerance: |unexplained| <= tolerance passes.
        storage.addBalance(UUID.randomUUID(), "GhostSmall", TOLERANCE).join();
        SupplyIntegrity.Report atBoundary = run();
        assertTrue(atBoundary.withinTolerance(), "drift == tolerance must pass");
        assertEquals(TOLERANCE, atBoundary.unexplained(), 0.0001);

        // A further unmetered 1.00 mint: the boundary run's 0.01 was already
        // absorbed by the advanced checkpoint, so unexplained is exactly 1.00
        // (the row-growth term covers the new row's starting mint).
        storage.addBalance(UUID.randomUUID(), "GhostPlus", 1.00).join();
        SupplyIntegrity.Report beyond = run();
        assertFalse(beyond.withinTolerance());
        assertEquals(1.00, beyond.unexplained(), 0.0001);
    }

    @Test
    @DisplayName("transfer-shaped ledger rows (PAY pair) are net-zero in the replay")
    void transferPairsAreNetZero() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        storage.getBalance(alice, "Alice").join();
        storage.getBalance(bob, "Bob").join();
        run(); // bootstrap

        // /pay 30.00 Alice -> Bob through the atomic transfer + its two rows.
        storage.transferAtomic(alice, "Alice", bob, "Bob", 30.00).join();
        log.log(TransactionLog.Type.PAY_SEND, alice, "Alice", bob, "Bob", 30.00, null, 0, "pay");
        log.log(TransactionLog.Type.PAY_RECEIVE, bob, "Bob", alice, "Alice", 30.00, null, 0, "pay");
        awaitLedgerCount(2);

        SupplyIntegrity.Report report = run();
        assertTrue(report.withinTolerance(), "unexplained " + report.unexplained());
        assertEquals(0.0, report.meteredDelta(), 0.005, "PAY pair nets zero");
        assertEquals(0.0, report.unexplained(), 0.005);
    }
}
