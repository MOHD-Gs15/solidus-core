package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-server race harness (DB scaling plan §5.4 exit criteria):
 * TWO {@link MySqlStorage} instances — two simulated servers — hammer the SAME
 * shared database with concurrent transfers. The invariant that must hold at
 * the end: <b>money supply is conserved to the cent</b> (no dupes, no losses).
 *
 * <p>Self-skips unless {@code SOLIDUS_TEST_MYSQL_HOST} is set (CI service
 * container); see {@link MySqlStorageContractTest} for the CI wiring.</p>
 */
public class MySqlTransferRaceTest {

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private static final int TRANSFERS_PER_SERVER = 100;
    private static final double AMOUNT = 1.0;

    private static void assumeDatabaseConfigured() {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — race harness skipped");
    }

    @Test
    @DisplayName("two servers race 200 concurrent transfers — supply is conserved exactly")
    void twoServerRaceConservesSupply() throws Exception {
        assumeDatabaseConfigured();

        StorageConfig.MySqlSettings settings =
            new StorageConfig.MySqlSettings(HOST, PORT, DATABASE, USER, PASSWORD, 8, 5000, false);

        MySqlStorage serverA = new MySqlStorage(settings);
        MySqlStorage serverB = new MySqlStorage(settings);
        serverA.initialize();
        serverB.initialize();
        try {
            UUID sender = UUID.randomUUID();
            UUID receiver = UUID.randomUUID();
            double starting = 1_000_000.0;

            // Seed both accounts through one backend (setBalance is an upsert)
            serverA.setBalance(sender, "RaceSender", starting).get(30, TimeUnit.SECONDS);
            serverA.setBalance(receiver, "RaceReceiver", 0.0).get(30, TimeUnit.SECONDS);

            // Both "servers" fire transfers concurrently. Within each storage
            // the executor serializes; across the two, InnoDB row locks +
            // deterministic lock order carry the correctness.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger persisted = new AtomicInteger();

            List<CompletableFuture<Void>> jobs = List.of(
                CompletableFuture.runAsync(() -> runLeg(serverA, sender, receiver, start, persisted), pool),
                CompletableFuture.runAsync(() -> runLeg(serverB, sender, receiver, start, persisted), pool));

            start.countDown();
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
            pool.shutdown();

            int moved = persisted.get();
            assertEquals(TRANSFERS_PER_SERVER * 2, moved,
                "every transfer must eventually succeed via lock ordering/retries");

            double senderEnd = serverA.getBalance(sender, "RaceSender").get(30, TimeUnit.SECONDS);
            double receiverEnd = serverB.getBalance(receiver, "RaceReceiver").get(30, TimeUnit.SECONDS);

            assertEquals(CurrencyUtil.round(starting - moved * AMOUNT), senderEnd,
                "sender balance must reflect exactly the committed transfers");
            assertEquals(CurrencyUtil.round(moved * AMOUNT), receiverEnd,
                "receiver balance must reflect exactly the committed transfers");

            // The supply invariant: nothing created, nothing destroyed.
            assertEquals(starting, CurrencyUtil.round(senderEnd + receiverEnd),
                "money supply conservation violated");
        } finally {
            serverA.shutdown();
            serverB.shutdown();
            wipeTables();
        }
    }

    private void runLeg(MySqlStorage server, UUID sender, UUID receiver,
                        CountDownLatch start, AtomicInteger persisted) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        List<CompletableFuture<SQLiteStorage.TransferOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < TRANSFERS_PER_SERVER; i++) {
            futures.add(server.transferAtomic(
                sender, "RaceSender", receiver, "RaceReceiver", AMOUNT));
        }
        for (CompletableFuture<SQLiteStorage.TransferOutcome> f : futures) {
            try {
                SQLiteStorage.TransferOutcome outcome = f.get(60, TimeUnit.SECONDS);
                if (outcome.status() == SQLiteStorage.TransferStatus.SUCCESS) {
                    persisted.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException("transfer future failed", e);
            }
        }
    }

    private void wipeTables() throws Exception {
        String url = "jdbc:mariadb://" + HOST + ":" + PORT + "/" + DATABASE;
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM player_balances");
            stmt.execute("DELETE FROM transaction_log");
            stmt.execute("DELETE FROM pending_notifications");
        }
    }
}
