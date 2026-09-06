package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supply-integrity checker against a REAL MySQL/MariaDB server (2.2.4 —
 * DB scaling plan §7). Proves the checkpoint table lives cleanly beside the
 * shared economy schema (DECIMAL(18,2) sums, BIGINT watermarks) and the
 * replay arithmetic holds on the network backend:
 *
 * <ol>
 *   <li>bootstrap + balanced metered window (ADMIN_GIVE) advances the
 *       checkpoint;</li>
 *   <li>an unmetered mint is flagged with the exact figure and the
 *       checkpoint stays sticky;</li>
 *   <li>rebase clears the baseline on the shared database.</li>
 * </ol>
 *
 * <p><b>Activation</b> (self-skipping otherwise): {@code SOLIDUS_TEST_MYSQL_HOST}
 * — same service-container contract as {@link MySqlStorageContractTest}.</p>
 */
@DisplayName("SupplyIntegrity on MySQL/MariaDB (2.2.4)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MySqlSupplyIntegrityTest {

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private static MySqlStorage storage;
    private static SupplyIntegrity integrity;
    private static TransactionLog log;

    private static final double TOLERANCE = 0.01;

    private static void assumeDatabaseConfigured() {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — MySQL supply-integrity tests skipped");
    }

    @BeforeAll
    static void connectAndPrepare() throws Exception {
        assumeDatabaseConfigured();
        StorageConfig.MySqlSettings settings =
            new StorageConfig.MySqlSettings(HOST, PORT, DATABASE, USER, PASSWORD, 4, 5000, false);
        storage = new MySqlStorage(settings);
        storage.initialize();
        log = storage.getTransactionLog();
        integrity = new SupplyIntegrity(log, TransactionLog.Dialect.MYSQL,
            directExecutor(), TOLERANCE);
    }

    @AfterAll
    static void close() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    @BeforeEach
    void resetBaseline() {
        // rebaseline() CREATEs the checkpoint table when missing and wipes the
        // single row — every test starts from an unbootstrapped store.
        assertTrue(integrity.rebaseline().join(),
            "checkpoint table must be writable on the shared database");
    }

    /**
     * TransactionLog writes are async on its own executor — poll until the
     * expected ledger rows for {@code player} are committed so the checker's
     * windowed SUM is deterministic.
     */
    private static void awaitLedgerRows(UUID player, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = DriverManager.getConnection(
                    "jdbc:mariadb://" + HOST + ":" + PORT + "/" + DATABASE, USER, PASSWORD);
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) c FROM transaction_log WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong("c") >= expected) {
                        return;
                    }
                }
            }
            Thread.sleep(100);
        }
        fail("ledger rows for " + player + " did not commit within 10s");
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
        };
    }

    @Test
    @Order(1)
    @DisplayName("bootstrap + balanced ADMIN_GIVE window advances the checkpoint (DECIMAL sums)")
    void balancedWindowOnMysql() throws Exception {
        UUID player = UUID.randomUUID();
        storage.setBalance(player, "IntegrityTrader", 500.00).get(30, TimeUnit.SECONDS);
        // Seed BEFORE bootstrap: the setBalance row + its value land in the baseline.
        SupplyIntegrity.Report baseline = integrity.runOnce().join();
        assertTrue(baseline.bootstrapped());
        assertEquals(500.00, baseline.observedSupply(), 0.005);

        // Metered faucet inside the window: +250 with its ledger row.
        storage.addBalance(player, "IntegrityTrader", 250.00).get(30, TimeUnit.SECONDS);
        log.log(TransactionLog.Type.ADMIN_GIVE, player, "IntegrityTrader",
            null, null, 250.00, null, 0, "mysql integrity test give");
        awaitLedgerRows(player, 1);

        // A brand-new player row inside the window (auto-create mint + starting).
        storage.getBalance(UUID.randomUUID(), "IntegrityNewcomer").get(30, TimeUnit.SECONDS);

        SupplyIntegrity.Report report = integrity.runOnce().join();
        assertFalse(report.bootstrapped());
        assertTrue(report.withinTolerance(), "unexplained " + report.unexplained()
            + " — " + report.summary());
        assertEquals(250.00, report.meteredDelta(), 0.005);
        assertEquals(CurrencyUtil.getStartingBalance(), report.rowGrowthMint(), 0.005);
        assertEquals(1, report.rowsCreated());
        assertTrue(report.watermarkTo() > report.watermarkFrom(), "checkpoint advanced");
    }

    @Test
    @Order(2)
    @DisplayName("unmetered mint is flagged with the exact figure; checkpoint sticky; rebase works")
    void driftDetectionOnMysql() throws Exception {
        UUID player = UUID.randomUUID();
        storage.setBalance(player, "IntegrityOwner", 100.00).get(30, TimeUnit.SECONDS);
        integrity.runOnce().join(); // bootstrap

        // Unmetered companion-style mint (no ledger row): +75 actual beyond
        // the row-growth term the replay already accounts for.
        storage.addBalance(UUID.randomUUID(), "IntegrityGhost", 75.00).get(30, TimeUnit.SECONDS);

        SupplyIntegrity.Report drift = integrity.runOnce().join();
        assertFalse(drift.withinTolerance(), "unmetered mint must be flagged on MySQL");
        assertEquals(75.00, drift.unexplained(), 0.005, "exact unexplained figure");
        assertEquals("drift — checkpoint kept", drift.note());

        // Sticky: re-run reports the same window again.
        SupplyIntegrity.Report again = integrity.runOnce().join();
        assertFalse(again.withinTolerance());
        assertEquals(drift.watermarkFrom(), again.watermarkFrom());

        assertTrue(integrity.rebaseline().join());
        assertTrue(integrity.runOnce().join().bootstrapped(), "rebase clears the drift state");
    }
}
