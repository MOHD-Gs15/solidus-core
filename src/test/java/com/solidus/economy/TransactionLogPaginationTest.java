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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransactionLog} SQL-level pagination.
 *
 * Validates the LIMIT/OFFSET overload and COUNT(*) support used by
 * /transactions to avoid loading the whole ledger into memory:
 * - Page windows are contiguous and newest-first
 * - Offsets beyond the end return empty pages (never an error)
 * - Pages never mix other players' rows
 * - COUNT(*) is per-player and matches the seeded row count
 * - The legacy limit-only overload keeps returning the newest N entries
 *
 * Uses a real SQLite database in a temp directory. A same-thread executor
 * (Runnable::run) makes every CompletableFuture already completed when the
 * call returns, so no waiting or sleeping is needed.
 */
@DisplayName("TransactionLog pagination (SQL LIMIT/OFFSET)")
class TransactionLogPaginationTest {

    private Connection conn;
    private TransactionLog log;
    private Path tempDir;
    private UUID playerA;
    private UUID playerB;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-txlog-test-");
        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("tx.db"));
        // Direct executor: async tasks run synchronously on the calling thread,
        // so every CompletableFuture is already completed when the call returns.
        ExecutorService directExecutor = new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
        };
        log = new TransactionLog(conn, directExecutor);
        log.initialize();

        playerA = UUID.randomUUID();
        playerB = UUID.randomUUID();

        // Seed 25 rows for player A with fully controlled timestamps (1000..1024)
        // plus 5 rows for player B. Direct SQL instead of log() so timestamps
        // are deterministic; log() stamps wall-clock time.
        String insert = "INSERT INTO transaction_log "
            + "(timestamp, type, player_uuid, player_name, amount, description) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (int i = 0; i < 25; i++) {
                ps.setLong(1, 1000 + i);
                ps.setString(2, TransactionLog.Type.PAY_SEND.code());
                ps.setString(3, playerA.toString());
                ps.setString(4, "Alice");
                ps.setDouble(5, 10.0 + i);
                ps.setString(6, "seed-" + i);
                ps.addBatch();
            }
            for (int i = 0; i < 5; i++) {
                ps.setLong(1, 2000 + i);
                ps.setString(2, TransactionLog.Type.PAY_RECEIVE.code());
                ps.setString(3, playerB.toString());
                ps.setString(4, "Bob");
                ps.setDouble(5, 1.0);
                ps.setString(6, "bob-" + i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try { conn.close(); } catch (Exception ignored) {}
        try {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    private List<TransactionLog.TransactionEntry> page(UUID who, int limit, int offset) throws Exception {
        return log.getTransactions(who, limit, offset).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("first page returns the newest entries, newest first")
    void firstPageNewestFirst() throws Exception {
        List<TransactionLog.TransactionEntry> entries = page(playerA, 10, 0);
        assertEquals(10, entries.size());
        assertEquals("seed-24", entries.get(0).description(), "newest row (ts=1024) comes first");
        assertEquals("seed-15", entries.get(9).description(), "page ends at ts=1015");
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i - 1).timestamp() >= entries.get(i).timestamp(),
                "timestamps must be non-increasing within a page");
        }
    }

    @Test
    @DisplayName("second page continues exactly where the first ended")
    void secondPageContinues() throws Exception {
        List<TransactionLog.TransactionEntry> first = page(playerA, 10, 0);
        List<TransactionLog.TransactionEntry> second = page(playerA, 10, 10);
        assertEquals(10, second.size());
        assertEquals("seed-14", second.get(0).description());
        assertEquals("seed-5", second.get(9).description());
        // Contiguity: newest of page 2 is directly older than oldest of page 1
        assertTrue(first.get(9).timestamp() > second.get(0).timestamp());
    }

    @Test
    @DisplayName("last partial page returns only the remainder")
    void lastPartialPage() throws Exception {
        List<TransactionLog.TransactionEntry> entries = page(playerA, 10, 20);
        assertEquals(5, entries.size());
        assertEquals("seed-4", entries.get(0).description());
        assertEquals("seed-0", entries.get(4).description());
    }

    @Test
    @DisplayName("offset beyond the end returns an empty page, never an error")
    void offsetBeyondEnd() throws Exception {
        assertTrue(page(playerA, 10, 30).isEmpty());
        assertTrue(page(playerA, 10, 1000).isEmpty());
    }

    @Test
    @DisplayName("negative offset is clamped to 0")
    void negativeOffsetClamped() throws Exception {
        List<TransactionLog.TransactionEntry> entries = page(playerA, 10, -5);
        assertEquals(10, entries.size());
        assertEquals("seed-24", entries.get(0).description());
    }

    @Test
    @DisplayName("pagination never mixes other players' rows")
    void isolatedPerPlayer() throws Exception {
        for (List<TransactionLog.TransactionEntry> p :
                List.of(page(playerA, 10, 0), page(playerA, 10, 10), page(playerA, 10, 20))) {
            for (TransactionLog.TransactionEntry e : p) {
                assertEquals(playerA, e.playerUuid());
            }
        }
        // Player B's pages are independent of A's ledger position
        List<TransactionLog.TransactionEntry> bPage = page(playerB, 10, 0);
        assertEquals(5, bPage.size());
        for (TransactionLog.TransactionEntry e : bPage) {
            assertEquals(playerB, e.playerUuid());
        }
    }

    @Test
    @DisplayName("countTransactions counts only the player's rows")
    void countPerPlayer() throws Exception {
        assertEquals(25, log.countTransactions(playerA).get(5, TimeUnit.SECONDS));
        assertEquals(5, log.countTransactions(playerB).get(5, TimeUnit.SECONDS));
        assertEquals(0, log.countTransactions(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("count matches summed page sizes (footer math stays correct)")
    void countMatchesPageSum() throws Exception {
        int total = 0;
        int offset = 0;
        List<TransactionLog.TransactionEntry> p;
        while (!(p = page(playerA, 10, offset)).isEmpty()) {
            total += p.size();
            offset += 10;
        }
        assertEquals(25, total);
        assertEquals(25, log.countTransactions(playerA).get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("legacy limit-only overload still returns the newest N")
    void legacyOverloadStillWorks() throws Exception {
        List<TransactionLog.TransactionEntry> entries =
            log.getTransactions(playerA, 12).get(5, TimeUnit.SECONDS);
        assertEquals(12, entries.size());
        assertEquals("seed-24", entries.get(0).description());
        assertEquals("seed-13", entries.get(11).description());
    }

    @Test
    @DisplayName("log() + paged read work end-to-end on a real connection")
    void logThenRead() throws Exception {
        UUID carol = UUID.randomUUID();
        log.log(TransactionLog.Type.SHOP_BUY, carol, "Carol", null, null,
            5.0, null, 0, "bought stuff");
        List<TransactionLog.TransactionEntry> entries = page(carol, 10, 0);
        assertEquals(1, entries.size());
        assertEquals(TransactionLog.Type.SHOP_BUY, entries.get(0).type());
        assertEquals(5.0, entries.get(0).amount());
        assertEquals("Carol", entries.get(0).playerName());
    }
}
