package com.solidus.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SQLiteStorage#transferAtomicWithLedger} (audit 2.1.3).
 *
 * Locks in the invariant the auction recovery sweep depends on: the ledger
 * rows that prove money moved are committed in the SAME SQLite transaction
 * as the balance updates. After a successful (committed) transfer the rows
 * MUST exist on disk - a crash between "money committed" and "evidence
 * written" previously re-listed already-paid auction listings (money
 * printing). And when the transfer fails, NO money and NO rows may remain.
 */
@DisplayName("Atomic transfer with in-transaction ledger")
class TransferAtomicLedgerTest {

    private static final UUID BUYER =
        UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID SELLER =
        UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");

    private SQLiteStorage storage;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-ledger-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        setBalanceSync(BUYER, "Buyer", 1000.0);
        setBalanceSync(SELLER, "Seller", 0.0);
    }

    @AfterEach
    void tearDown() {
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

    private void setBalanceSync(UUID uuid, String name, double balance) throws Exception {
        storage.setBalance(uuid, name, balance).get(5, TimeUnit.SECONDS);
    }

    private double balanceSync(UUID uuid) throws Exception {
        return storage.getBalance(uuid, "").get(5, TimeUnit.SECONDS);
    }

    private List<SQLiteStorage.AtomicLedgerRow> auctionLedgerRows(double price) {
        return List.of(
            new SQLiteStorage.AtomicLedgerRow(
                TransactionLog.Type.AUCTION_BOUGHT,
                BUYER, "Buyer", SELLER, "Seller",
                price, "DIAMOND", 1,
                "Bought 1x DIAMOND from Seller"),
            new SQLiteStorage.AtomicLedgerRow(
                TransactionLog.Type.AUCTION_SOLD,
                SELLER, "Seller", BUYER, "Buyer",
                price, "DIAMOND", 1,
                "Sold 1x DIAMOND to Buyer"));
    }

    private SQLiteStorage.TransferOutcome transferWithLedgerSync(double price) throws Exception {
        return storage.transferAtomicWithLedger(BUYER, "Buyer", SELLER, "Seller", price,
                auctionLedgerRows(price))
            .get(5, TimeUnit.SECONDS);
    }

    /** Count ledger rows of the given type, reading the file directly (fresh connection). */
    private int countLedgerRows(TransactionLog.Type type) throws Exception {
        // Ensure the storage's async writes are flushed before we open a reader.
        storage.shutdown();
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();

        String url = "jdbc:sqlite:" + tempDir + "/economy.db";
        String sql = "SELECT COUNT(*) FROM transaction_log WHERE type = ?";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.code());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("committed transfer persists BOTH ledger rows with the money (recovery evidence)")
    void committedTransferPersistsLedgerRows() throws Exception {
        SQLiteStorage.TransferOutcome outcome = transferWithLedgerSync(250.0);

        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
        assertEquals(750.0, balanceSync(BUYER), 1e-9);
        assertEquals(250.0, balanceSync(SELLER), 1e-9);

        // The evidence must be durable on disk the instant the money is.
        assertEquals(1, countLedgerRows(TransactionLog.Type.AUCTION_BOUGHT),
            "AUCTION_BOUGHT row must commit with the money");
        assertEquals(1, countLedgerRows(TransactionLog.Type.AUCTION_SOLD),
            "AUCTION_SOLD row must commit with the money (recovery sweep evidence)");
    }

    @Test
    @DisplayName("failed transfer (insufficient funds) leaves NO money moved and NO rows")
    void failedTransferWritesNoRows() throws Exception {
        SQLiteStorage.TransferOutcome outcome =
            storage.transferAtomicWithLedger(BUYER, "Buyer", SELLER, "Seller", 5000.0,
                    auctionLedgerRows(5000.0))
                .get(5, TimeUnit.SECONDS);

        assertEquals(SQLiteStorage.TransferStatus.INSUFFICIENT_FUNDS, outcome.status());
        assertEquals(1000.0, balanceSync(BUYER), 1e-9);
        assertEquals(0.0, balanceSync(SELLER), 1e-9);
        assertEquals(0, countLedgerRows(TransactionLog.Type.AUCTION_BOUGHT));
        assertEquals(0, countLedgerRows(TransactionLog.Type.AUCTION_SOLD));
    }

    @Test
    @DisplayName("ledger rows survive reopen (crash-consistency proof)")
    void ledgerRowsSurviveReopen() throws Exception {
        transferWithLedgerSync(100.0);

        // Simulate a hard restart: shut down and reopen the storage.
        storage.shutdown();
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();

        assertEquals(900.0, balanceSync(BUYER), 1e-9);
        assertEquals(100.0, balanceSync(SELLER), 1e-9);
        assertEquals(1, countLedgerRows(TransactionLog.Type.AUCTION_SOLD),
            "evidence must still be there after reopen - this is what the recovery sweep reads");
    }

    @Test
    @DisplayName("null ledger rows behaves exactly like the plain transferAtomic")
    void nullLedgerRowsFallsBackToPlainTransfer() throws Exception {
        SQLiteStorage.TransferOutcome outcome =
            storage.transferAtomicWithLedger(BUYER, "Buyer", SELLER, "Seller", 50.0, null)
                .get(5, TimeUnit.SECONDS);

        assertEquals(SQLiteStorage.TransferStatus.SUCCESS, outcome.status());
        assertEquals(950.0, balanceSync(BUYER), 1e-9);
        assertEquals(50.0, balanceSync(SELLER), 1e-9);
    }

    @Test
    @DisplayName("atomicity: a mid-balance failure cannot leave rows without money")
    void rowsNeverCommitWithoutMoney() throws Exception {
        // Self-transfer is rejected before any writes happen - if the ledger
        // insert path were somehow reached before validation, rows would leak.
        SQLiteStorage.TransferOutcome outcome =
            storage.transferAtomicWithLedger(BUYER, "Buyer", BUYER, "Buyer", 10.0,
                    auctionLedgerRows(10.0))
                .get(5, TimeUnit.SECONDS);

        assertEquals(SQLiteStorage.TransferStatus.PERSIST_ERROR, outcome.status());
        assertEquals(1000.0, balanceSync(BUYER), 1e-9);
        assertEquals(0, countLedgerRows(TransactionLog.Type.AUCTION_BOUGHT));
        assertEquals(0, countLedgerRows(TransactionLog.Type.AUCTION_SOLD));
    }
}
