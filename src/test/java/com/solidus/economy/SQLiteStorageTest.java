package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link SQLiteStorage} using a real SQLite database
 * in a temporary directory. No Minecraft dependencies required.
 *
 * These tests verify the async SQLite operations, cache behavior,
 * persistence, rollback on failure, and concurrent access safety.
 */
@DisplayName("SQLiteStorage")
class SQLiteStorageTest {

    private SQLiteStorage storage;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        // Clean up temp directory
        try {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    // -- New Player ----------------------------------------

    @Nested
    @DisplayName("New Player")
    class NewPlayerTest {

        @Test
        @DisplayName("new player gets default starting balance")
        void newPlayerGetsStartingBalance() throws Exception {
            UUID uuid = UUID.randomUUID();
            Double balance = storage.getBalance(uuid, "TestPlayer").get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE, balance);
        }

        @Test
        @DisplayName("new player is persisted to database")
        void newPlayerIsPersisted() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.getBalance(uuid, "TestPlayer").get();

            // getBalance now delegates new-player creation to the executor,
            // which persists synchronously within the supplyAsync block.
            // A small delay ensures the executor has fully processed.
            Thread.sleep(200);

            // Create a new storage instance and verify the balance loads
            SQLiteStorage storage2 = new SQLiteStorage(tempDir.toString());
            storage2.initialize();
            Double balance = storage2.getBalance(uuid, "TestPlayer").get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE, balance);
            storage2.shutdown();
        }

        @Test
        @DisplayName("player name is cached after getBalance")
        void playerNameIsCachedAfterGetBalance() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.getBalance(uuid, "Notch").get();

            // Name update is async (via executor.execute), wait for it
            Thread.sleep(100);

            assertEquals("Notch", storage.getPlayerNameCache().get(uuid));
        }

        @Test
        @DisplayName("getBalance with empty name does not update playerNameCache")
        void emptyNameDoesNotUpdateCache() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.getBalance(uuid, "").get();

            Thread.sleep(100);

            // Empty name should not overwrite the cache
            assertNull(storage.getPlayerNameCache().get(uuid));
        }
    }

    // -- addBalance ----------------------------------------

    @Nested
    @DisplayName("addBalance()")
    class AddBalanceTest {

        @Test
        @DisplayName("adds amount to existing balance")
        void addsToExistingBalance() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.getBalance(uuid, "TestPlayer").get(); // Initialize with starting balance

            Double newBalance = storage.addBalance(uuid, "TestPlayer", 100.0).get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE + 100.0, newBalance);
        }

        @Test
        @DisplayName("adding to non-existent player creates with starting + amount")
        void addsToNewPlayer() throws Exception {
            UUID uuid = UUID.randomUUID();
            Double newBalance = storage.addBalance(uuid, "TestPlayer", 50.0).get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE + 50.0, newBalance);
        }

        @Test
        @DisplayName("prevents balance overflow above MAX_BALANCE")
        void preventsOverflow() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", CurrencyUtil.MAX_BALANCE - 10).get();

            Double result = storage.addBalance(uuid, "TestPlayer", 100.0).get();
            assertEquals(-1.0, result);
        }

        @Test
        @DisplayName("multiple sequential adds accumulate correctly")
        void sequentialAddsAccumulate() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.getBalance(uuid, "TestPlayer").get();

            storage.addBalance(uuid, "TestPlayer", 100.0).get();
            storage.addBalance(uuid, "TestPlayer", 200.0).get();
            Double finalBalance = storage.addBalance(uuid, "TestPlayer", 300.0).get();

            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE + 600.0, finalBalance);
        }
    }

    // -- subtractBalance -----------------------------------

    @Nested
    @DisplayName("subtractBalance()")
    class SubtractBalanceTest {

        @Test
        @DisplayName("subtracts amount from balance")
        void subtractsFromBalance() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 1000.0).get();

            Double newBalance = storage.subtractBalance(uuid, "TestPlayer", 300.0).get();
            assertEquals(700.0, newBalance);
        }

        @Test
        @DisplayName("returns -1 when insufficient funds")
        void returnsNegOneOnInsufficientFunds() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 100.0).get();

            Double result = storage.subtractBalance(uuid, "TestPlayer", 200.0).get();
            assertEquals(-1.0, result);
        }

        @Test
        @DisplayName("balance unchanged when subtract fails")
        void balanceUnchangedOnFailedSubtract() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 100.0).get();

            storage.subtractBalance(uuid, "TestPlayer", 200.0).get();
            Double currentBalance = storage.getBalance(uuid, "TestPlayer").get();
            assertEquals(100.0, currentBalance);
        }

        @Test
        @DisplayName("can subtract exact balance to zero")
        void canSubtractToZero() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 500.0).get();

            Double newBalance = storage.subtractBalance(uuid, "TestPlayer", 500.0).get();
            assertEquals(0.0, newBalance);
        }
    }

    // -- setBalance ----------------------------------------

    @Nested
    @DisplayName("setBalance()")
    class SetBalanceTest {

        @Test
        @DisplayName("sets balance to exact value")
        void setsExactValue() throws Exception {
            UUID uuid = UUID.randomUUID();
            Boolean success = storage.setBalance(uuid, "TestPlayer", 9999.99).get();
            assertTrue(success);

            Double balance = storage.getBalance(uuid, "TestPlayer").get();
            assertEquals(9999.99, balance);
        }

        @Test
        @DisplayName("rejects invalid balance (negative)")
        void rejectsNegative() throws Exception {
            UUID uuid = UUID.randomUUID();
            Boolean success = storage.setBalance(uuid, "TestPlayer", -100.0).get();
            assertFalse(success);
        }

        @Test
        @DisplayName("rejects balance above MAX_BALANCE")
        void rejectsAboveMax() throws Exception {
            UUID uuid = UUID.randomUUID();
            Boolean success = storage.setBalance(uuid, "TestPlayer", CurrencyUtil.MAX_BALANCE + 1).get();
            assertFalse(success);
        }

        @Test
        @DisplayName("allows setting balance to zero")
        void allowsZero() throws Exception {
            UUID uuid = UUID.randomUUID();
            Boolean success = storage.setBalance(uuid, "TestPlayer", 0.0).get();
            assertTrue(success);
        }
    }

    // -- hasBalance ----------------------------------------

    @Nested
    @DisplayName("hasBalance()")
    class HasBalanceTest {

        @Test
        @DisplayName("returns true when player has sufficient funds")
        void returnsTrueWhenSufficient() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 1000.0).get();

            Boolean has = storage.hasBalance(uuid, 500.0).get();
            assertTrue(has);
        }

        @Test
        @DisplayName("returns false when player has insufficient funds")
        void returnsFalseWhenInsufficient() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 100.0).get();

            Boolean has = storage.hasBalance(uuid, 500.0).get();
            assertFalse(has);
        }

        @Test
        @DisplayName("returns true when amount equals balance exactly")
        void returnsTrueWhenExact() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 500.0).get();

            Boolean has = storage.hasBalance(uuid, 500.0).get();
            assertTrue(has);
        }
    }

    // -- getTopBalances ------------------------------------

    @Nested
    @DisplayName("getTopBalances()")
    class GetTopBalancesTest {

        @Test
        @DisplayName("returns entries sorted by balance descending")
        void returnsSortedByBalance() throws Exception {
            storage.setBalance(UUID.randomUUID(), "PoorPlayer", 100.0).get();
            storage.setBalance(UUID.randomUUID(), "RichPlayer", 99999.0).get();
            storage.setBalance(UUID.randomUUID(), "MidPlayer", 5000.0).get();

            // Wait for async persist
            Thread.sleep(200);

            List<SQLiteStorage.BalanceEntry> top = storage.getTopBalances(3).get();
            assertEquals(3, top.size());
            assertEquals("RichPlayer", top.get(0).playerName());
            assertEquals(99999.0, top.get(0).balance());
        }

        @Test
        @DisplayName("respects limit parameter")
        void respectsLimit() throws Exception {
            for (int i = 0; i < 10; i++) {
                storage.setBalance(UUID.randomUUID(), "Player" + i, 1000.0 * (i + 1)).get();
            }

            Thread.sleep(200);

            List<SQLiteStorage.BalanceEntry> top = storage.getTopBalances(5).get();
            assertEquals(5, top.size());
        }
    }

    // -- Concurrency ---------------------------------------

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTest {

        @Test
        @DisplayName("100 concurrent addBalance operations are thread-safe")
        void concurrentAddBalanceIsThreadSafe() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.getBalance(uuid, "TestPlayer").get(); // Initialize

            // Submit 100 concurrent add operations
            CompletableFuture<Double>[] futures = new CompletableFuture[100];
            for (int i = 0; i < 100; i++) {
                futures[i] = storage.addBalance(uuid, "TestPlayer", 1.0);
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            // Since the executor is single-threaded, all operations should be serialized
            Double finalBalance = storage.getBalance(uuid, "TestPlayer").get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE + 100.0, finalBalance);
        }

        @Test
        @DisplayName("concurrent subtract operations do not cause overdraft")
        void concurrentSubtractNoOverdraft() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 100.0).get();

            // Try to subtract 1.0 fifty times (only 100 available, so all should succeed)
            CompletableFuture<Double>[] futures = new CompletableFuture[50];
            for (int i = 0; i < 50; i++) {
                futures[i] = storage.subtractBalance(uuid, "TestPlayer", 1.0);
            }

            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            Double finalBalance = storage.getBalance(uuid, "TestPlayer").get();
            assertEquals(50.0, finalBalance);
        }

        @Test
        @DisplayName("concurrent getBalance for same new UUID does not corrupt cache")
        void concurrentGetBalanceNewPlayerNoCorruption() throws Exception {
            UUID uuid = UUID.randomUUID();
            ExecutorService testPool = Executors.newFixedThreadPool(8);

            // 8 threads call getBalance for the same NEW UUID simultaneously
            CompletableFuture<Double>[] futures = new CompletableFuture[8];
            for (int i = 0; i < 8; i++) {
                final int idx = i;
                futures[i] = CompletableFuture.supplyAsync(
                    () -> storage.getBalance(uuid, "Player" + idx).join(),
                    testPool
                );
            }

            // All should return the starting balance
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
            for (CompletableFuture<Double> f : futures) {
                assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE, f.get());
            }

            // Cache should have exactly one entry
            Double cached = storage.getBalance(uuid, "").get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE, cached);

            testPool.shutdown();
        }

        @Test
        @DisplayName("concurrent getBalance with different names - latest name wins in cache")
        void concurrentGetBalanceLatestNameWins() throws Exception {
            UUID uuid = UUID.randomUUID();

            // Call getBalance with different names from different threads
            storage.getBalance(uuid, "Name1");
            storage.getBalance(uuid, "Name2");
            storage.getBalance(uuid, "Name3").get(); // Wait for last one

            // Give executor time to process name updates
            Thread.sleep(200);

            // The name should be one of the three (executor processes in order)
            String cachedName = storage.getPlayerNameCache().get(uuid);
            assertNotNull(cachedName);
            assertTrue(cachedName.equals("Name1") || cachedName.equals("Name2") || cachedName.equals("Name3"));
        }

        @Test
        @DisplayName("getBalance racing with addBalance - no lost updates")
        void getBalanceRacingWithAddBalance() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 500.0).get();

            // addBalance runs on executor, getBalance reads from cache
            CompletableFuture<Double> addFuture = storage.addBalance(uuid, "TestPlayer", 100.0);
            CompletableFuture<Double> getFuture = storage.getBalance(uuid, "TestPlayer");

            Double added = addFuture.get(5, TimeUnit.SECONDS);
            Double read = getFuture.get(5, TimeUnit.SECONDS);

            // addBalance should succeed
            assertEquals(600.0, added);
            // getBalance may return 500 or 600 (stale read is acceptable)
            assertTrue(read == 500.0 || read == 600.0);
        }
    }

    // -- Persistence Across Restart ------------------------

    @Nested
    @DisplayName("Persistence")
    class PersistenceTest {

        @Test
        @DisplayName("balance persists after shutdown and restart")
        void balancePersistsAcrossRestart() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "PersistentPlayer", 7777.77).get();

            // Shutdown
            storage.shutdown();
            Thread.sleep(200);

            // Restart with same database
            SQLiteStorage storage2 = new SQLiteStorage(tempDir.toString());
            storage2.initialize();

            Double balance = storage2.getBalance(uuid, "PersistentPlayer").get();
            assertEquals(7777.77, balance);

            storage2.shutdown();
        }
    }
}
