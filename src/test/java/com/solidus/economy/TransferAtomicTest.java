package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SQLiteStorage#transferAtomic} and the production transfer
 * path {@link BalanceManager#transferOffline}.
 *
 * These tests lock in the atomicity guarantees that R19/R12 hardening added:
 * both transfer legs commit inside ONE SQLite transaction (BEGIN IMMEDIATE
 * ... COMMIT), so money can neither vanish between the legs nor be duplicated
 * by interleaving, and any failure rolls the database AND cache back to a
 * consistent pre-transfer state.
 */
@DisplayName("Atomic transfer")
class TransferAtomicTest {

    private static final UUID SENDER =
        UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECEIVER =
        UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNREGISTERED =
        UUID.fromString("33333333-3333-3333-3333-333333333333");

    private SQLiteStorage storage;
    private Path tempDir;
    private double savedStartingBalance;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-transfer-test-");
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

    private SQLiteStorage.TransferOutcome transferSync(
            UUID sender, String senderName, UUID receiver, String receiverName, double amount)
            throws Exception {
        return storage.transferAtomic(sender, senderName, receiver, receiverName, amount)
            .get(5, TimeUnit.SECONDS);
    }

    private double balanceSync(UUID uuid) throws Exception {
        return storage.getBalance(uuid, "").get(5, TimeUnit.SECONDS);
    }

    /** Reopens the database from disk to prove state was (or was not) persisted. */
    private SQLiteStorage reopenStorage() {
        storage.shutdown();
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        return storage;
    }

    @Nested
    @DisplayName("storage level (SQLiteStorage.transferAtomic)")
    class StorageLevel {

        @Test
        @DisplayName("success moves both legs and persists to disk")
        void successMovesBothLegs() throws Exception {
            setBalanceSync(SENDER, "Sender", 100.0);
            setBalanceSync(RECEIVER, "Receiver", 50.0);

            SQLiteStorage.TransferOutcome outcome =
                transferSync(SENDER, "Sender", RECEIVER, "Receiver", 30.0);

            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
            assertEquals(70.0, outcome.senderNewBalance(), 1e-9);
            assertEquals(80.0, outcome.receiverNewBalance(), 1e-9);
            assertEquals(70.0, balanceSync(SENDER), 1e-9);
            assertEquals(80.0, balanceSync(RECEIVER), 1e-9);

            // Persistence proof: reopen from disk and re-check.
            reopenStorage();
            assertEquals(70.0, balanceSync(SENDER), 1e-9);
            assertEquals(80.0, balanceSync(RECEIVER), 1e-9);
        }

        @Test
        @DisplayName("insufficient funds moves nothing (rollback)")
        void insufficientFundsMovesNothing() throws Exception {
            setBalanceSync(SENDER, "Sender", 10.0);
            setBalanceSync(RECEIVER, "Receiver", 100.0);

            SQLiteStorage.TransferOutcome outcome =
                transferSync(SENDER, "Sender", RECEIVER, "Receiver", 50.0);

            assertEquals(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS, outcome.status());
            assertEquals(10.0, balanceSync(SENDER), 1e-9);
            assertEquals(100.0, balanceSync(RECEIVER), 1e-9);

            reopenStorage();
            assertEquals(10.0, balanceSync(SENDER), 1e-9);
            assertEquals(100.0, balanceSync(RECEIVER), 1e-9);
        }

        @Test
        @DisplayName("receiver overflow rolls the whole transfer back")
        void receiverOverflowRollsBack() throws Exception {
            setBalanceSync(SENDER, "Sender", 100.0);
            setBalanceSync(RECEIVER, "Receiver", CurrencyUtil.MAX_BALANCE - 1.0);

            SQLiteStorage.TransferOutcome outcome =
                transferSync(SENDER, "Sender", RECEIVER, "Receiver", 5.0);

            assertEquals(SQLiteStorage.TransferStatus.RECEIVER_OVERFLOW, outcome.status());
            // Old deduct-then-add flow left the sender deducted while the
            // receiver credit failed; the atomic path must leave both intact.
            assertEquals(100.0, balanceSync(SENDER), 1e-9);
            assertEquals(CurrencyUtil.MAX_BALANCE - 1.0, balanceSync(RECEIVER), 1e-9);
        }

        @Test
        @DisplayName("unregistered receiver is created with starting balance + amount")
        void unregisteredReceiverCreated() throws Exception {
            setBalanceSync(SENDER, "Sender", 1000.0);
            double starting = CurrencyUtil.getStartingBalance();

            SQLiteStorage.TransferOutcome outcome =
                transferSync(SENDER, "Sender", UNREGISTERED, "Newcomer", 25.0);

            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
            assertEquals(975.0, outcome.senderNewBalance(), 1e-9);
            assertEquals(starting + 25.0, outcome.receiverNewBalance(), 1e-9);
        }

        @Test
        @DisplayName("unregistered sender is treated as holding the starting balance")
        void unregisteredSenderUsesStartingBalance() throws Exception {
            double starting = CurrencyUtil.getStartingBalance();
            setBalanceSync(RECEIVER, "Receiver", 0.0);

            SQLiteStorage.TransferOutcome outcome =
                transferSync(UNREGISTERED, "Newcomer", RECEIVER, "Receiver", 100.0);

            assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
            assertEquals(starting - 100.0, outcome.senderNewBalance(), 1e-9);
            assertEquals(100.0, balanceSync(RECEIVER), 1e-9);
        }

        @Test
        @DisplayName("self-transfer is rejected at storage level")
        void selfTransferRejected() throws Exception {
            setBalanceSync(SENDER, "Sender", 100.0);

            SQLiteStorage.TransferOutcome outcome =
                transferSync(SENDER, "Sender", SENDER, "Sender", 10.0);

            assertNotEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
            assertEquals(100.0, balanceSync(SENDER), 1e-9);
        }

        @Test
        @DisplayName("concurrent transfers conserve the total money supply")
        void concurrentTransfersConserveMoney() throws Exception {
            setBalanceSync(SENDER, "Sender", 1000.0);
            setBalanceSync(RECEIVER, "Receiver", 1000.0);

            List<CompletableFuture<SQLiteStorage.TransferOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                boolean forward = (i % 2 == 0);
                futures.add(storage.transferAtomic(
                    forward ? SENDER : RECEIVER, "A",
                    forward ? RECEIVER : SENDER, "B",
                    10.0));
            }
            for (CompletableFuture<SQLiteStorage.TransferOutcome> f : futures) {
                assertEquals(SQLiteStorage.TransferStatus.SUCCESS, f.get(10, TimeUnit.SECONDS).status());
            }

            double total = balanceSync(SENDER) + balanceSync(RECEIVER);
            assertEquals(2000.0, total, 1e-6,
                "Money supply must be conserved across concurrent transfers");
        }
    }

    @Nested
    @DisplayName("production path (BalanceManager.transferOffline)")
    class BalanceManagerLevel {

        @Test
        @DisplayName("successful transfer reports new balances on both sides")
        void transferReportsBothNewBalances() throws Exception {
            setBalanceSync(SENDER, "Sender", 200.0);
            setBalanceSync(RECEIVER, "Receiver", 20.0);
            BalanceManager manager = new BalanceManager(storage);

            BalanceManager.TransferResult result = manager.transferOffline(
                SENDER, "Sender", RECEIVER, "Receiver", 50.0).get(5, TimeUnit.SECONDS);

            assertTrue(result.success(), "expected success, got: " + result.message());
            assertEquals("Transfer successful.", result.message());
            assertEquals(150.0, result.senderNewBalance(), 1e-9);
            assertEquals(70.0, result.receiverNewBalance(), 1e-9);
        }

        @Test
        @DisplayName("insufficient funds reports failure and moves nothing")
        void insufficientFundsFailsCleanly() throws Exception {
            setBalanceSync(SENDER, "Sender", 5.0);
            setBalanceSync(RECEIVER, "Receiver", 20.0);
            BalanceManager manager = new BalanceManager(storage);

            BalanceManager.TransferResult result = manager.transferOffline(
                SENDER, "Sender", RECEIVER, "Receiver", 50.0).get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertEquals("Insufficient funds.", result.message());
            assertEquals(5.0, balanceSync(SENDER), 1e-9);
            assertEquals(20.0, balanceSync(RECEIVER), 1e-9);
        }

        @Test
        @DisplayName("pre-validation still rejects negative, zero and self transfers")
        void preValidationGuardsStayIntact() throws Exception {
            setBalanceSync(SENDER, "Sender", 100.0);
            BalanceManager manager = new BalanceManager(storage);

            BalanceManager.TransferResult zero = manager.transferOffline(
                SENDER, "Sender", RECEIVER, "Receiver", 0.0).get(5, TimeUnit.SECONDS);
            assertFalse(zero.success());

            BalanceManager.TransferResult negative = manager.transferOffline(
                SENDER, "Sender", RECEIVER, "Receiver", -10.0).get(5, TimeUnit.SECONDS);
            assertFalse(negative.success());

            BalanceManager.TransferResult self = manager.transferOffline(
                SENDER, "Sender", SENDER, "Sender", 10.0).get(5, TimeUnit.SECONDS);
            assertFalse(self.success());

            assertEquals(100.0, balanceSync(SENDER), 1e-9);
        }
    }

    private void setBalanceSync(UUID uuid, String name, double amount) throws Exception {
        assertTrue(storage.setBalance(uuid, name, amount).get(5, TimeUnit.SECONDS),
            "setBalance fixture failed for " + uuid);
    }
}
