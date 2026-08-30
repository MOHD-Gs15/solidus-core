package com.solidus.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SQLiteStorage} SQL-level leaderboard pagination used by
 * /baltop [page]:
 * - Page windows are contiguous and balance-descending
 * - Ranks are global and continue across pages (page 2 starts at #11)
 * - Offsets beyond the end return empty pages (never an error)
 * - Negative offsets are clamped to 0
 * - getBalanceEntryCount matches the seeded player count (footer math)
 * - The legacy limit-only overload keeps returning the global top N
 *
 * Uses a real SQLite database in a temp directory. Every seeded write is
 * awaited via its own CompletableFuture (single-threaded executor), so no
 * sleeping is needed before the pagination reads.
 */
@DisplayName("SQLiteStorage leaderboard pagination (SQL LIMIT/OFFSET)")
class BaltopPaginationTest {

    private static final int SEEDED_PLAYERS = 25;

    private SQLiteStorage storage;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-baltop-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();

        // Seed 25 players with strictly increasing balances 100..124 so the
        // leaderboard order is fully deterministic: Player24 (124) first.
        // setBalance() upserts both the name and the balance, and each call
        // is awaited, so the executor has processed every write when this
        // method returns.
        for (int i = 0; i < SEEDED_PLAYERS; i++) {
            UUID uuid = UUID.randomUUID();
            String name = String.format("TopPlayer%02d", i);
            boolean ok = storage.setBalance(uuid, name, 100.0 + i).get(5, TimeUnit.SECONDS);
            assertTrue(ok, "seed write for " + name + " must succeed");
        }
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

    private List<SQLiteStorage.BalanceEntry> page(int limit, int offset) throws Exception {
        return storage.getTopBalances(limit, offset).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("first page returns the wealthiest players, ranks 1..10")
    void firstPageIsGlobalTop() throws Exception {
        List<SQLiteStorage.BalanceEntry> entries = page(10, 0);
        assertEquals(10, entries.size());
        assertEquals(1, entries.get(0).rank());
        assertEquals("TopPlayer24", entries.get(0).playerName());
        assertEquals(124.0, entries.get(0).balance());
        assertEquals("TopPlayer15", entries.get(9).playerName());
        assertEquals(10, entries.get(9).rank());
    }

    @Test
    @DisplayName("second page continues exactly where the first ended, ranks 11..20")
    void secondPageContinuesWithGlobalRanks() throws Exception {
        List<SQLiteStorage.BalanceEntry> first = page(10, 0);
        List<SQLiteStorage.BalanceEntry> second = page(10, 10);
        assertEquals(10, second.size());
        assertEquals("TopPlayer14", second.get(0).playerName());
        assertEquals(11, second.get(0).rank(), "ranks continue across pages");
        assertEquals("TopPlayer05", second.get(9).playerName());
        assertEquals(20, second.get(9).rank());
        // Contiguity: richest of page 2 is directly poorer than poorest of page 1
        assertTrue(first.get(9).balance() > second.get(0).balance());
    }

    @Test
    @DisplayName("last partial page returns the remainder, ranks 21..25")
    void lastPartialPage() throws Exception {
        List<SQLiteStorage.BalanceEntry> entries = page(10, 20);
        assertEquals(5, entries.size());
        assertEquals("TopPlayer04", entries.get(0).playerName());
        assertEquals(21, entries.get(0).rank());
        assertEquals("TopPlayer00", entries.get(4).playerName());
        assertEquals(25, entries.get(4).rank());
    }

    @Test
    @DisplayName("offset beyond the end returns an empty page, never an error")
    void offsetBeyondEnd() throws Exception {
        assertTrue(page(10, 30).isEmpty());
        assertTrue(page(10, 1000).isEmpty());
    }

    @Test
    @DisplayName("negative offset is clamped to 0")
    void negativeOffsetClamped() throws Exception {
        List<SQLiteStorage.BalanceEntry> entries = page(10, -5);
        assertEquals(10, entries.size());
        assertEquals("TopPlayer24", entries.get(0).playerName());
        assertEquals(1, entries.get(0).rank());
    }

    @Test
    @DisplayName("balances are strictly descending within every page")
    void strictlyDescendingWithinPages() throws Exception {
        for (List<SQLiteStorage.BalanceEntry> p : List.of(page(10, 0), page(10, 10), page(10, 20))) {
            for (int i = 1; i < p.size(); i++) {
                assertTrue(p.get(i - 1).balance() > p.get(i).balance(),
                    "balances must strictly decrease within a page");
            }
        }
    }

    @Test
    @DisplayName("getBalanceEntryCount matches the seeded player count (footer math)")
    void countMatchesSeed() throws Exception {
        assertEquals(25, storage.getBalanceEntryCount().get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("count matches summed page sizes (Page X/Y math stays correct)")
    void countMatchesPageSum() throws Exception {
        int total = 0;
        int offset = 0;
        List<SQLiteStorage.BalanceEntry> p;
        while (!(p = page(10, offset)).isEmpty()) {
            total += p.size();
            offset += 10;
        }
        assertEquals(25, total);
        assertEquals(25, storage.getBalanceEntryCount().get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("legacy limit-only overload still returns the global top N")
    void legacyOverloadStillWorks() throws Exception {
        List<SQLiteStorage.BalanceEntry> top = storage.getTopBalances(5).get(5, TimeUnit.SECONDS);
        assertEquals(5, top.size());
        assertEquals("TopPlayer24", top.get(0).playerName());
        assertEquals("TopPlayer20", top.get(4).playerName());
        for (int i = 0; i < top.size(); i++) {
            assertEquals(i + 1, top.get(i).rank(), "first-page ranks start at 1");
        }
    }

    @Test
    @DisplayName("new player joins the leaderboard through the paged path too")
    void newPlayerVisibleThroughPagedPath() throws Exception {
        UUID latecomer = UUID.randomUUID();
        assertTrue(storage.setBalance(latecomer, "LateRich", 999.0).get(5, TimeUnit.SECONDS));

        assertEquals(26, storage.getBalanceEntryCount().get(5, TimeUnit.SECONDS));
        List<SQLiteStorage.BalanceEntry> first = page(10, 0);
        assertEquals("LateRich", first.get(0).playerName());
        assertEquals(999.0, first.get(0).balance());
        assertEquals(1, first.get(0).rank());
    }
}
