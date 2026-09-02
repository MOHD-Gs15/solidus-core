package com.solidus.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Tests for the /transactions export backend in {@link TransactionLog}:
 * - Windowed reads (getTransactionsSince / getAllTransactionsSince)
 * - Newest-first ordering across the window
 * - Row caps so exports can never exhaust memory (newest rows win)
 * - RFC 4180 CSV building: header, escaping, null handling, amount format
 * - CSV file writing (UTF-8, content matches buildCsv)
 *
 * Uses a real SQLite database with fully controlled timestamps and a
 * same-thread executor, so every CompletableFuture is already completed
 * when the call returns.
 */
@DisplayName("TransactionLog CSV export (windowed reads + RFC 4180)")
class TransactionLogExportTest {

    private Connection conn;
    private TransactionLog log;
    private Path tempDir;
    private UUID alice;
    private UUID bob;

    // Fixed "now" so window boundaries are deterministic
    private static final long NOW = 1_000_000L;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-export-test-");
        conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("tx.db"));
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

        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
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

    /** Seeds a row with a fully controlled timestamp. */
    private void seed(UUID player, String name, long timestamp, String description) throws Exception {
        String insert = "INSERT INTO transaction_log "
            + "(timestamp, type, player_uuid, player_name, amount, description) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setLong(1, timestamp);
            ps.setString(2, TransactionLog.Type.PAY_SEND.code());
            ps.setString(3, player.toString());
            ps.setString(4, name);
            ps.setDouble(5, 12.5);
            ps.setString(6, description);
            ps.executeUpdate();
        }
    }

    private List<TransactionLog.TransactionEntry> since(UUID who, long sinceMs) throws Exception {
        return log.getTransactionsSince(who, sinceMs).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getTransactionsSince returns only rows inside the window, newest first")
    void windowedReadPerPlayer() throws Exception {
        seed(alice, "Alice", NOW - 3 * DAY_MS, "old");
        seed(alice, "Alice", NOW - DAY_MS, "in-window-1");
        seed(alice, "Alice", NOW, "in-window-2");
        seed(bob, "Bob", NOW, "bobs-row");

        List<TransactionLog.TransactionEntry> entries = since(alice, NOW - 2 * DAY_MS);
        assertEquals(2, entries.size(), "old row and other players' rows excluded");
        assertEquals("in-window-2", entries.get(0).description());
        assertEquals("in-window-1", entries.get(1).description());
        assertEquals(alice, entries.get(0).playerUuid());
        assertEquals(alice, entries.get(1).playerUuid());
    }

    @Test
    @DisplayName("window lower bound is inclusive (timestamp == sinceMs counts)")
    void windowLowerBoundInclusive() throws Exception {
        seed(alice, "Alice", NOW - 2 * DAY_MS, "boundary");
        seed(alice, "Alice", NOW - 2 * DAY_MS - 1, "just-too-old");

        List<TransactionLog.TransactionEntry> entries = since(alice, NOW - 2 * DAY_MS);
        assertEquals(1, entries.size());
        assertEquals("boundary", entries.get(0).description());
    }

    @Test
    @DisplayName("getAllTransactionsSince spans multiple players, newest first")
    void allPlayersWindowedRead() throws Exception {
        seed(alice, "Alice", NOW - 100, "alice-old");
        seed(bob, "Bob", NOW - 50, "bob-new");
        seed(alice, "Alice", NOW, "alice-new");
        seed(bob, "Bob", NOW - 10 * DAY_MS, "bob-too-old");

        List<TransactionLog.TransactionEntry> entries =
            log.getAllTransactionsSince(NOW - DAY_MS).get(5, TimeUnit.SECONDS);
        assertEquals(3, entries.size());
        assertEquals("alice-new", entries.get(0).description());
        assertEquals("bob-new", entries.get(1).description());
        assertEquals("alice-old", entries.get(2).description());
    }

    @Test
    @DisplayName("row cap keeps the NEWEST rows (memory guard for huge ledgers)")
    void rowCapKeepsNewestRows() throws Exception {
        for (int i = 0; i < 15; i++) {
            seed(alice, "Alice", NOW - 1000 + i, "row-" + i);
        }
        List<TransactionLog.TransactionEntry> entries =
            log.getAllTransactionsSince(0, 10).get(5, TimeUnit.SECONDS);
        assertEquals(10, entries.size(), "capped at maxRows");
        assertEquals("row-14", entries.get(0).description(), "newest row wins under cap");
        assertEquals("row-5", entries.get(9).description(), "oldest rows are the ones dropped");
    }

    @Test
    @DisplayName("empty window returns an empty list (never an error)")
    void emptyWindow() throws Exception {
        assertTrue(since(alice, NOW - DAY_MS).isEmpty());
        assertTrue(log.getAllTransactionsSince(NOW + 1).get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("buildCsv emits header + one row per entry with plain fields")
    void csvHeaderAndPlainRow() {
        long ts = 1_700_000_000_000L;
        List<TransactionLog.TransactionEntry> entries = List.of(
            new TransactionLog.TransactionEntry(
                ts, TransactionLog.Type.PAY_SEND, alice, "Alice", bob, "Bob",
                1234.5, null, 0, "payment for repairs"));

        String csv = TransactionLog.buildCsv(entries);
        String[] lines = csv.split("\n", -1);
        assertEquals(3, lines.length, "header + 1 row + trailing newline split");
        assertEquals("timestamp_ms,timestamp_utc,type,player_uuid,player_name,"
            + "target_uuid,target_name,amount,item_material,item_quantity,description",
            lines[0]);

        String[] cols = lines[1].split(",", -1);
        assertEquals(11, cols.length);
        assertEquals("1700000000000", cols[0]);
        assertEquals("2023-11-14T22:13:20Z", cols[1], "ISO-8601 UTC timestamp");
        assertEquals("PAY_SEND", cols[2]);
        assertEquals(alice.toString(), cols[3]);
        assertEquals("Alice", cols[4]);
        assertEquals(bob.toString(), cols[5]);
        assertEquals("Bob", cols[6]);
        assertEquals("1234.50", cols[7], "two decimals, Locale.ROOT");
        assertEquals("", cols[8], "null item material exports empty");
        assertEquals("0", cols[9]);
        assertEquals("payment for repairs", cols[10]);
    }

    @Test
    @DisplayName("buildCsv escapes commas, quotes and line breaks (RFC 4180)")
    void csvEscaping() {
        List<TransactionLog.TransactionEntry> entries = List.of(
            new TransactionLog.TransactionEntry(
                1L, TransactionLog.Type.SHOP_BUY, alice, "Alice, Jr",
                null, null, 1.0, null, 0, "said \"hi\"\nline2\tok"));

        String csv = TransactionLog.buildCsv(entries);
        String[] lines = csv.split("\n", -1);
        assertEquals(4, lines.length, "newline inside a field must not split rows: " + csv);
        assertTrue(lines[1].startsWith("1,1970-01-01T00:00:00Z,SHOP_BUY,"),
            "row intact, got: <" + lines[1] + ">");
        assertTrue(lines[1].contains("\"Alice, Jr\""), "comma field is quoted");
        assertTrue(lines[1].contains("\"said \"\"hi\"\""), "quotes doubled");
        assertTrue(lines[2].endsWith("line2\tok\""), "multi-line field quoted across lines");
    }

    @Test
    @DisplayName("csvEscape: plain field unchanged, null becomes empty")
    void csvEscapeBasics() {
        assertEquals("simple", TransactionLog.csvEscape("simple"));
        assertEquals("", TransactionLog.csvEscape(null));
        assertEquals("", TransactionLog.csvEscape(""));
        assertEquals("\"a,b\"", TransactionLog.csvEscape("a,b"));
        assertEquals("\"say \"\"x\"\"\"", TransactionLog.csvEscape("say \"x\""));
        assertEquals("\"line1\nline2\"", TransactionLog.csvEscape("line1\nline2"));
        assertEquals("\"cr\rhere\"", TransactionLog.csvEscape("cr\rhere"));
    }

    @Test
    @DisplayName("csvEscape: spreadsheet formula prefixes are neutralized (audit 2.1.3)")
    void csvEscapeNeutralizesFormulaInjection() {
        // A field starting with = + - @ or TAB/CR must never reach a privileged
        // spreadsheet consumer as executable content - it gets a leading apostrophe.
        assertEquals("'=cmd|'/c calc'!A0", TransactionLog.csvEscape("=cmd|'/c calc'!A0"));
        assertEquals("\"'=HYPERLINK(\"\"http://evil\"\", \"\"click\"\")\"",
            TransactionLog.csvEscape("=HYPERLINK(\"http://evil\", \"click\")"));
        assertEquals("'+SUM(A1:A2)", TransactionLog.csvEscape("+SUM(A1:A2)"));
        assertEquals("'-1+1", TransactionLog.csvEscape("-1+1"));
        assertEquals("'@cmd", TransactionLog.csvEscape("@cmd"));
        assertEquals("'\ttab-prefixed", TransactionLog.csvEscape("\ttab-prefixed"));

        // Formula prefix + comma: apostrophe inside the RFC 4180 quotes
        assertEquals("\"'=a,b\"", TransactionLog.csvEscape("=a,b"));

        // Fields that merely CONTAIN these characters stay untouched
        assertEquals("a=b", TransactionLog.csvEscape("a=b"));
        assertEquals("plain-name", TransactionLog.csvEscape("plain-name"));
        assertEquals("1+1", TransactionLog.csvEscape("1+1"));
    }

    @Test
    @DisplayName("writeCsvFile writes UTF-8 content matching buildCsv")
    void writeCsvFileRoundTrip(@TempDir Path dir) throws Exception {
        List<TransactionLog.TransactionEntry> entries = List.of(
            new TransactionLog.TransactionEntry(
                1_700_000_000_000L, TransactionLog.Type.PAY_RECEIVE, alice, "Alice",
                null, null, 50.25, null, 0, "from p2p transfer"));

        Path file = dir.resolve("nested").resolve("export.csv");
        TransactionLog.writeCsvFile(entries, file);

        String content = Files.readString(file);
        assertEquals(TransactionLog.buildCsv(entries), content, "file content matches buildCsv");
        assertTrue(Files.exists(file.getParent()), "parent dirs created");
    }

    @Test
    @DisplayName("empty export still produces a valid header-only CSV")
    void headerOnlyWhenEmpty() {
        String csv = TransactionLog.buildCsv(List.of());
        String[] lines = csv.split("\n", -1);
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("timestamp_ms,"));
        assertEquals("", lines[1], "trailing newline only");
    }

    @Test
    @DisplayName("windowed reads stay isolated per player (export leaks nothing)")
    void exportDoesNotLeakOtherPlayers() throws Exception {
        seed(alice, "Alice", NOW, "a1");
        seed(bob, "Bob", NOW, "b1");
        seed(bob, "Bob", NOW - 1, "b2");

        for (TransactionLog.TransactionEntry e : since(alice, 0)) {
            assertEquals(alice, e.playerUuid(), "export must never include other players' rows");
        }
        assertEquals(1, since(alice, 0).size());
        assertEquals(2, since(bob, 0).size());
    }
}
