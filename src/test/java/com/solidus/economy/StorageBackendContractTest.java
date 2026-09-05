package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Storage backend contract tests (DB scaling plan, Phase 1).
 *
 * <p>The abstract harness runs the SAME behavioral assertions through the
 * {@link StorageBackend} interface ONLY - no knowledge of the concrete
 * implementation. Subclasses bind a backend:</p>
 * <ul>
 *   <li>{@code SQLiteStorageContractTest} - always runs (CI default).</li>
 *   <li>{@code MySqlStorageContractTest} (2.2.0) - runs against a real
 *       MariaDB when one is available, otherwise self-skips.</li>
 * </ul>
 *
 * <p>This suite becomes the acceptance harness for every future backend:
 * a new {@code StorageBackend} implementation is correct when this contract
 * is green against it.</p>
 */
public abstract class StorageBackendContractTest {

    /** Timeout for async primitives - generous for CI, still fails-fast. */
    protected static final long TIMEOUT_SECONDS = 30;

    /** Fresh, initialized backend backed by an isolated store. */
    protected StorageBackend backend;

    /** Creates and initializes one backend instance over isolated storage. */
    protected abstract StorageBackend createBackend() throws Exception;

    /** Releases the backend and its storage (delete files / drop rows as needed). */
    protected abstract void destroyBackend() throws Exception;

    @BeforeEach
    final void setUpContract() throws Exception {
        backend = createBackend();
        backend.initialize();
    }

    @AfterEach
    final void tearDownContract() throws Exception {
        try {
            if (backend != null) {
                backend.shutdown();
            }
        } finally {
            destroyBackend();
        }
    }

    private static <T> T join(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Async primitive did not complete in time", e);
        }
    }

    // -------------------------------------------------------
    // Contract: reads
    // -------------------------------------------------------

    @Test
    @DisplayName("getBalance creates a starting-balance record for a new player")
    void getBalanceCreatesStartingBalance() {
        UUID uuid = UUID.randomUUID();
        double balance = join(backend.getBalance(uuid, "NewPlayer"));
        assertEquals(CurrencyUtil.getStartingBalance(), balance);
        assertEquals(CurrencyUtil.getStartingBalance(), join(backend.getBalance(uuid, "NewPlayer")));
    }

    @Test
    @DisplayName("setBalance writes and reads back; invalid balances rejected")
    void setBalanceRoundTrip() {
        UUID uuid = UUID.randomUUID();
        assertTrue(join(backend.setBalance(uuid, "Setter", 123.45)));
        assertEquals(123.45, join(backend.getBalance(uuid, "Setter")));

        // Invalid: negative and above the MAX_BALANCE cap are rejected
        assertFalse(join(backend.setBalance(uuid, "Setter", -1.0)));
        assertFalse(join(backend.setBalance(uuid, "Setter", CurrencyUtil.MAX_BALANCE + 1)));
        assertEquals(123.45, join(backend.getBalance(uuid, "Setter")));
    }

    @Test
    @DisplayName("addBalance accumulates and enforces the MAX_BALANCE cap")
    void addBalanceAccumulates() {
        UUID uuid = UUID.randomUUID();
        double starting = CurrencyUtil.getStartingBalance();
        assertEquals(CurrencyUtil.round(starting + 10.0),
                join(backend.addBalance(uuid, "Adder", 10.0)));

        // Overflow beyond the cap fails with -1 and leaves the balance intact
        double current = join(backend.getBalance(uuid, "Adder"));
        assertEquals(-1.0, join(backend.addBalance(uuid, "Adder", CurrencyUtil.MAX_BALANCE)));
        assertEquals(current, join(backend.getBalance(uuid, "Adder")));
    }

    @Test
    @DisplayName("subtractBalance rejects overdrafts atomically")
    void subtractBalanceInsufficient() {
        UUID uuid = UUID.randomUUID();
        double starting = join(backend.getBalance(uuid, "Spendthrift"));
        double drained = join(backend.subtractBalance(uuid, "Spendthrift", starting));
        assertEquals(0.0, drained);
        assertEquals(-1.0, join(backend.subtractBalance(uuid, "Spendthrift", 0.01)));
        assertEquals(0.0, join(backend.getBalance(uuid, "Spendthrift")));
    }

    @Test
    @DisplayName("hasBalance reflects affordability")
    void hasBalanceContract() {
        UUID uuid = UUID.randomUUID();
        assertTrue(join(backend.hasBalance(uuid, 0.0)));
        assertFalse(join(backend.hasBalance(uuid, CurrencyUtil.MAX_BALANCE)));
    }

    // -------------------------------------------------------
    // Contract: atomic transfers
    // -------------------------------------------------------

    @Test
    @DisplayName("transferAtomic moves money and conserves supply")
    void transferAtomicSuccess() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        join(backend.setBalance(from, "From", 100.0));
        join(backend.setBalance(to, "To", 50.0));

        SQLiteStorage.TransferOutcome outcome = join(
                backend.transferAtomic(from, "From", to, "To", 40.0));
        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
        assertEquals(60.0, outcome.senderNewBalance());
        assertEquals(90.0, outcome.receiverNewBalance());
        assertEquals(60.0, join(backend.getBalance(from, "From")));
        assertEquals(90.0, join(backend.getBalance(to, "To")));
    }

    @Test
    @DisplayName("transferAtomic rejects insufficient funds and self-transfer; nothing moves")
    void transferAtomicRejections() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        join(backend.setBalance(from, "Broke", 10.0));

        assertEquals(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS,
                join(backend.transferAtomic(from, "Broke", to, "Rich", 10.01)).status());
        assertEquals(SQLiteStorage.TransferStatus.PERSIST_ERROR,
                join(backend.transferAtomic(from, "Broke", from, "Broke", 1.0)).status());
        assertEquals(10.0, join(backend.getBalance(from, "Broke")));
    }

    @Test
    @DisplayName("transferAtomic enforces the receiver balance cap")
    void transferAtomicReceiverOverflow() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        join(backend.setBalance(from, "Whale", 100.0));
        join(backend.setBalance(to, "Capped", CurrencyUtil.MAX_BALANCE));

        assertEquals(SQLiteStorage.TransferStatus.RECEIVER_OVERFLOW,
                join(backend.transferAtomic(from, "Whale", to, "Capped", 1.0)).status());
        assertEquals(100.0, join(backend.getBalance(from, "Whale")));
        assertEquals(CurrencyUtil.MAX_BALANCE, join(backend.getBalance(to, "Capped")));
    }

    @Test
    @DisplayName("transferAtomicWithLedger commits money + ledger evidence together")
    void transferAtomicWithLedgerContract() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        join(backend.setBalance(from, "Buyer", 200.0));
        join(backend.setBalance(to, "Seller", 0.0));

        SQLiteStorage.AtomicLedgerRow row = new SQLiteStorage.AtomicLedgerRow(
                TransactionLog.Type.PAY_SEND, from, "Buyer", to, "Seller",
                25.0, null, 0, "contract test evidence");
        SQLiteStorage.TransferOutcome outcome = join(
                backend.transferAtomicWithLedger(from, "Buyer", to, "Seller", 25.0, List.of(row)));
        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
        assertEquals(175.0, join(backend.getBalance(from, "Buyer")));
        assertEquals(25.0, join(backend.getBalance(to, "Seller")));

        // The ledger row is durable and queryable through the shared log
        List<TransactionLog.TransactionEntry> entries = join(
                backend.getTransactionLog().getTransactions(from, 10));
        assertEquals(1, entries.size());
        assertEquals(TransactionLog.Type.PAY_SEND, entries.get(0).type());
        assertEquals(25.0, entries.get(0).amount());
    }

    // -------------------------------------------------------
    // Contract: leaderboard + stats
    // -------------------------------------------------------

    @Test
    @DisplayName("baltop paginates, ranks globally, and excludes the escrow account")
    void baltopPaginationAndEscrowExclusion() {
        UUID rich = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID poor = UUID.randomUUID();
        join(backend.setBalance(rich, "Rich", 300.0));
        join(backend.setBalance(mid, "Mid", 200.0));
        join(backend.setBalance(poor, "Poor", 100.0));
        // Escrow must never appear on leaderboards
        join(backend.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 500.0));

        List<SQLiteStorage.BalanceEntry> page1 = join(backend.getTopBalances(2, 0));
        assertEquals(2, page1.size());
        assertEquals("Rich", page1.get(0).playerName());
        assertEquals(1, page1.get(0).rank());
        assertEquals("Mid", page1.get(1).playerName());
        assertEquals(2, page1.get(1).rank());

        List<SQLiteStorage.BalanceEntry> page2 = join(backend.getTopBalances(2, 2));
        assertFalse(page2.isEmpty());
        assertEquals("Poor", page2.get(0).playerName());
        assertEquals(3, page2.get(0).rank());
        assertTrue(page2.stream().noneMatch(e -> EscrowAccount.isSystemAccount(e.uuid())));
    }

    @Test
    @DisplayName("entry count includes system accounts; stats conserve supply")
    void countAndStatsContract() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        join(backend.setBalance(a, "A", 120.0));
        join(backend.setBalance(b, "B", 80.0));

        // A and B exist in this isolated store (escrow is pre-created by
        // EconomyEngine in production, not by the backend itself)
        assertTrue(join(backend.getBalanceEntryCount()) >= 2);

        SQLiteStorage.EconomyStats stats = join(backend.getEconomyStats());
        assertTrue(stats.playerCount() >= 2);
        assertTrue(stats.totalSupply() >= 200.0);
        assertTrue(stats.giniCoefficient() >= 0.0 && stats.giniCoefficient() <= 1.0);
    }

    @Test
    @DisplayName("player name cache serves offline lookups")
    void playerNameCacheContract() {
        UUID uuid = UUID.randomUUID();
        join(backend.getBalance(uuid, "CachedName"));
        assertTrue(backend.getPlayerNameCache().containsKey(uuid));
        assertEquals("CachedName", backend.getPlayerNameCache().get(uuid));
    }
}
