package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BalanceManager} using real SQLiteStorage.
 * Only tests the offline (UUID-based) methods that don't require ServerPlayer.
 */
@DisplayName("BalanceManager")
class BalanceManagerTest {

    private BalanceManager manager;
    private SQLiteStorage storage;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-bm-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        manager = new BalanceManager(storage);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        try {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    // -- getBalance (UUID) ---------------------------------

    @Nested
    @DisplayName("getBalance(UUID)")
    class GetBalanceTest {

        @Test
        @DisplayName("returns starting balance for new player")
        void returnsStartingBalanceForNewPlayer() throws Exception {
            Double balance = manager.getBalance(UUID.randomUUID(), "NewPlayer").get();
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE, balance);
        }

        @Test
        @DisplayName("returns correct balance after set")
        void returnsCorrectBalanceAfterSet() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 1234.56).get();

            Double balance = manager.getBalance(uuid, "TestPlayer").get();
            assertEquals(1234.56, balance);
        }
    }

    // -- addBalance (UUID) ---------------------------------

    @Nested
    @DisplayName("addBalance(UUID)")
    class AddBalanceTest {

        @Test
        @DisplayName("adds amount to player balance")
        void addsAmount() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 1000.0).get();

            Double newBalance = manager.addBalance(uuid, "TestPlayer", 500.0).get();
            assertEquals(1500.0, newBalance);
        }

        @Test
        @DisplayName("rejects invalid amount (zero)")
        void rejectsZero() throws Exception {
            UUID uuid = UUID.randomUUID();
            Double result = manager.addBalance(uuid, "TestPlayer", 0.0).get();
            assertEquals(-1.0, result);
        }

        @Test
        @DisplayName("rejects invalid amount (negative)")
        void rejectsNegative() throws Exception {
            UUID uuid = UUID.randomUUID();
            Double result = manager.addBalance(uuid, "TestPlayer", -50.0).get();
            assertEquals(-1.0, result);
        }

        @Test
        @DisplayName("rejects amount above MAX_TRANSACTION")
        void rejectsAboveMax() throws Exception {
            UUID uuid = UUID.randomUUID();
            Double result = manager.addBalance(uuid, "TestPlayer", CurrencyUtil.MAX_TRANSACTION + 1).get();
            assertEquals(-1.0, result);
        }
    }

    // -- subtractBalance (UUID) ----------------------------

    @Nested
    @DisplayName("subtractBalance(UUID)")
    class SubtractBalanceTest {

        @Test
        @DisplayName("subtracts amount from player balance")
        void subtractsAmount() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 1000.0).get();

            Double newBalance = manager.subtractBalance(uuid, "TestPlayer", 300.0).get();
            assertEquals(700.0, newBalance);
        }

        @Test
        @DisplayName("rejects invalid amount (negative)")
        void rejectsNegative() throws Exception {
            UUID uuid = UUID.randomUUID();
            Double result = manager.subtractBalance(uuid, "TestPlayer", -50.0).get();
            assertEquals(-1.0, result);
        }

        @Test
        @DisplayName("returns -1 when insufficient funds")
        void returnsNegOneOnInsufficientFunds() throws Exception {
            UUID uuid = UUID.randomUUID();
            storage.setBalance(uuid, "TestPlayer", 100.0).get();

            Double result = manager.subtractBalance(uuid, "TestPlayer", 500.0).get();
            assertEquals(-1.0, result);
        }
    }

    // -- transferOffline -----------------------------------

    @Nested
    @DisplayName("transferOffline()")
    class TransferOfflineTest {

        @Test
        @DisplayName("successful transfer moves money between players")
        void successfulTransfer() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            storage.setBalance(alice, "Alice", 1000.0).get();
            storage.setBalance(bob, "Bob", 200.0).get();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", 100.0
            ).get();

            assertTrue(result.success());
            assertEquals(900.0, result.senderNewBalance());
            assertEquals(300.0, result.receiverNewBalance());
        }

        @Test
        @DisplayName("rejects negative amount")
        void rejectsNegativeAmount() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", -50.0
            ).get();

            assertFalse(result.success());
            assertTrue(result.message().contains("positive"));
        }

        @Test
        @DisplayName("rejects zero amount")
        void rejectsZeroAmount() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", 0.0
            ).get();

            assertFalse(result.success());
        }

        @Test
        @DisplayName("rejects self-transfer")
        void rejectsSelfTransfer() throws Exception {
            UUID sameUuid = UUID.randomUUID();

            BalanceManager.TransferResult result = manager.transferOffline(
                sameUuid, "Alice", sameUuid, "Alice", 50.0
            ).get();

            assertFalse(result.success());
            assertTrue(result.message().toLowerCase().contains("yourself"));
        }

        @Test
        @DisplayName("rejects transfer exceeding MAX_TRANSACTION")
        void rejectsExceedingMaxTransaction() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            storage.setBalance(alice, "Alice", CurrencyUtil.MAX_BALANCE).get();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", CurrencyUtil.MAX_TRANSACTION + 1
            ).get();

            assertFalse(result.success());
        }

        @Test
        @DisplayName("fails when sender has insufficient funds")
        void failsOnInsufficientFunds() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            storage.setBalance(alice, "Alice", 50.0).get();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", 100.0
            ).get();

            assertFalse(result.success());
            assertTrue(result.message().toLowerCase().contains("insufficient"));

            // Verify neither balance changed
            assertEquals(50.0, storage.getBalance(alice, "Alice").get());
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE, storage.getBalance(bob, "Bob").get());
        }

        @Test
        @DisplayName("transfer is atomic - sender balance deducted only when receiver gets funds")
        void transferIsAtomic() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            storage.setBalance(alice, "Alice", 500.0).get();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", 200.0
            ).get();

            assertTrue(result.success());
            assertEquals(300.0, storage.getBalance(alice, "Alice").get());
            assertEquals(CurrencyUtil.DEFAULT_STARTING_BALANCE + 200.0, storage.getBalance(bob, "Bob").get());
        }

        @Test
        @DisplayName("can transfer exact balance (sender goes to zero)")
        void canTransferExactBalance() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            storage.setBalance(alice, "Alice", 500.0).get();

            BalanceManager.TransferResult result = manager.transferOffline(
                alice, "Alice", bob, "Bob", 500.0
            ).get();

            assertTrue(result.success());
            assertEquals(0.0, result.senderNewBalance());
        }
    }
}
