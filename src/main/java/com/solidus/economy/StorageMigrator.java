package com.solidus.economy;

import com.solidus.auction.AuctionManager;
import com.solidus.util.CurrencyUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite → MySQL/MariaDB cutover migrator (DB scaling plan §8.2 — shipped 2.2.1).
 *
 * <p>Copies the ENTIRE Solidus data set from a local SQLite economy + auction
 * file pair into the configured MySQL target: balances, the transaction
 * ledger, offline notifications and all five auction tables (the auction
 * store moved onto the shared database in 2.2.1, so a full cutover includes
 * it).</p>
 *
 * <p><b>Idempotent / re-runnable:</b> every table uses keyset pagination on
 * its id column and idempotent writes — money tables
 * (player_balances, auction_listings, auction_bid_state) use
 * {@code ON DUPLICATE KEY UPDATE} so the latest SQLite state wins on a
 * re-run; append-only tables (ledger, notifications, history, bids, won
 * items) use {@code INSERT IGNORE} with explicit ids so a re-run skips
 * already-copied rows instead of duplicating them.</p>
 *
 * <p><b>Verification:</b> after the copy it compares per-table row counts and
 * the money supply ({@code SUM(balance)}, rounded to the cent) on both sides;
 * any mismatch fails the migration loudly instead of blessing a broken
 * cutover.</p>
 *
 * <p><b>Not a lock:</b> the source SQLite file is copied while the local
 * server may still be running (WAL allows concurrent readers). The command
 * refuses to run with players online unless {@code --force}, and the report
 * always prints the recommended procedure: run on a quiet server, then flip
 * {@code storage.json} to {@code "type": "mysql"} and restart.</p>
 */
public final class StorageMigrator {

    /** Result of one migration run. */
    public record MigrationReport(
            boolean success,
            long durationMs,
            List<String> tableCounts,
            double sqliteSupply,
            double mysqlSupply,
            List<String> issues,
            String reportFile) {
    }

    /** Copy plan entry: (table, id column, money-table flag). */
    private record TablePlan(String table, String idColumn, boolean latestWins) {}

    private static final List<TablePlan> TABLES = List.of(
        new TablePlan("player_balances", "rowid", true),
        new TablePlan("transaction_log", "id", false),
        new TablePlan("pending_notifications", "id", false),
        new TablePlan("auction_listings", "rowid", true),
        new TablePlan("auction_sold_history", "rowid", false),
        new TablePlan("auction_bid_state", "rowid", true),
        new TablePlan("auction_bids", "bid_id", false),
        new TablePlan("auction_won_items", "win_id", false)
    );

    private final String sqliteUrl;
    private final StorageConfig.MySqlSettings target;

    public StorageMigrator(Path sqliteEconomyDb, StorageConfig.MySqlSettings target) {
        this.sqliteUrl = "jdbc:sqlite:" + sqliteEconomyDb.toAbsolutePath();
        this.target = target;
    }

    /**
     * Runs the full copy + verify. Designed to run on a dedicated worker
     * thread — it blocks for the whole migration.
     */
    public MigrationReport run(int batchSize, Logger log) {
        long start = System.currentTimeMillis();
        List<String> issues = new ArrayList<>();
        List<String> tableCounts = new ArrayList<>();

        HikariDataSource mysql = null;
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl)) {
            mysql = openTarget();
            // Schema on the target FIRST: MySqlStorage's auto-create covers
            // economy tables; auction DDL mirrors AuctionManager (2.2.1).
            createAuctionSchema(mysql);

            for (TablePlan plan : TABLES) {
                long copied = copyTable(sqlite, mysql, plan, batchSize, issues, log);
                tableCounts.add(plan.table() + ": " + copied + " row(s) copied");
                log.info("Migrated {}: {} row(s)", plan.table(), copied);
            }

            // ── Verify ──
            long sourcePlayers;
            long targetPlayers;
            double sourceSupply;
            double targetSupply;
            try (Connection sc = sqlite;
                 Connection tc = mysql.getConnection()) {
                sourcePlayers = count(sc, "SELECT COUNT(*) FROM player_balances");
                targetPlayers = count(tc, "SELECT COUNT(*) FROM player_balances");
                sourceSupply = round2(sum(sc, "SELECT COALESCE(SUM(balance), 0) FROM player_balances"));
                targetSupply = round2(sum(tc, "SELECT COALESCE(SUM(balance), 0) FROM player_balances"));
            }

            if (sourcePlayers != targetPlayers) {
                issues.add("player count mismatch: sqlite=" + sourcePlayers + " mysql=" + targetPlayers);
            }
            if (Math.abs(sourceSupply - targetSupply) > 0.005) {
                issues.add("MONEY SUPPLY MISMATCH: sqlite=" + sourceSupply + " mysql=" + targetSupply);
            }
            for (TablePlan plan : TABLES) {
                long s;
                long t;
                try (Connection sc = sqlite;
                     Connection tc = mysql.getConnection()) {
                    s = count(sc, "SELECT COUNT(*) FROM " + plan.table());
                    t = count(tc, "SELECT COUNT(*) FROM " + plan.table());
                }
                if (s != t) {
                    issues.add(plan.table() + " count mismatch: sqlite=" + s + " mysql=" + t);
                }
            }

            long duration = System.currentTimeMillis() - start;
            boolean ok = issues.isEmpty();
            MigrationReport report = new MigrationReport(ok, duration, tableCounts,
                sourceSupply, targetSupply, issues, null);
            String file = writeReport(report);
            return new MigrationReport(ok, duration, tableCounts, sourceSupply, targetSupply, issues, file);
        } catch (SQLException e) {
            issues.add("migration aborted: " + e.getMessage());
            MigrationReport failed = new MigrationReport(false, System.currentTimeMillis() - start,
                tableCounts, 0, 0, issues, null);
            String file = writeReport(failed);
            return new MigrationReport(false, System.currentTimeMillis() - start,
                tableCounts, 0, 0, issues, file);
        } finally {
            if (mysql != null) mysql.close();
        }
    }

    // -- Copy ---------------------------------------------------------------

    private HikariDataSource openTarget() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mariadb://" + target.host() + ":" + target.port() + "/" + target.database()
            + "?useSsl=" + target.useSsl());
        cfg.setUsername(target.user());
        cfg.setPassword(target.password());
        cfg.setMaximumPoolSize(2); // migration only needs a pair of connections
        cfg.setConnectionTimeout(target.connectionTimeoutMs());
        cfg.setPoolName("Solidus-Migrate");
        try {
            return new HikariDataSource(cfg);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot reach the MySQL target " + target.host() + ":"
                + target.port() + "/" + target.database() + " — nothing was migrated.", e);
        }
    }

    /** Creates the five auction tables on the target (mirrors AuctionManager.MYSQL DDL). */
    private void createAuctionSchema(HikariDataSource mysql) throws SQLException {
        try (Connection conn = mysql.getConnection();
             Statement st = conn.createStatement()) {
            for (String ddl : AuctionManager.AuctionDialect.MYSQL.statements()) {
                if (ddl != null) st.execute(ddl);
            }
        }
    }

    private long copyTable(Connection sqlite, HikariDataSource mysql, TablePlan plan,
                           int batchSize, List<String> issues, Logger log) throws SQLException {
        boolean hasId = !plan.idColumn().equals("rowid");
        String selectSql = hasId
            ? "SELECT * FROM " + plan.table() + " WHERE " + plan.idColumn() + " > ? ORDER BY " + plan.idColumn() + " LIMIT ?"
            : "SELECT rowid AS _rid, * FROM " + plan.table() + " WHERE rowid > ? ORDER BY rowid LIMIT ?";
        String upsertSql = buildUpsertSql(plan.table());

        long total = 0;
        long cursor = 0;
        boolean more = true;
        while (more) {
            List<List<Object>> rows = new ArrayList<>();
            long maxId = cursor;
            try (PreparedStatement ps = sqlite.prepareStatement(selectSql)) {
                ps.setLong(1, cursor);
                ps.setInt(2, batchSize);
                try (ResultSet rs = ps.executeQuery()) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(cols);
                        for (int c = 1; c <= cols; c++) {
                            row.add(rs.getObject(c));
                        }
                        rows.add(row);
                        // _rid (rowid mode) is column 1; id mode: track the id column value
                        if (hasId) {
                            maxId = rs.getLong(plan.idColumn());
                        } else {
                            maxId = rs.getLong("_rid");
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                more = false;
                break;
            }

            try (Connection targetConn = mysql.getConnection();
                 PreparedStatement ins = targetConn.prepareStatement(upsertSql)) {
                targetConn.setAutoCommit(false);
                try {
                    for (List<Object> row : rows) {
                        // Bind: skip the _rid helper column (rowid mode) in the INSERT.
                        int startCol = hasId ? 1 : 2;
                        for (int i = 0; i < ins.getParameterMetaData().getParameterCount(); i++) {
                            Object value = row.get(startCol - 1 + i);
                            if (value instanceof Double d) {
                                // Money columns land in DECIMAL(18,2) through the
                                // exact-money boundary (same rule as live writes).
                                ins.setBigDecimal(i + 1, Money.of(CurrencyUtil.round(d)).toDecimal());
                            } else {
                                ins.setObject(i + 1, value);
                            }
                        }
                        ins.addBatch();
                    }
                    ins.executeBatch();
                    targetConn.commit();
                } catch (SQLException batchError) {
                    targetConn.rollback();
                    issues.add("batch failed on " + plan.table() + " at cursor " + cursor + ": "
                        + batchError.getMessage());
                    throw batchError;
                } finally {
                    targetConn.setAutoCommit(true);
                }
            }
            total += rows.size();
            cursor = maxId;
            more = rows.size() == batchSize;
        }
        return total;
    }

    /**
     * Idempotent write per table kind:
     * latest-wins tables → ON DUPLICATE KEY UPDATE (every non-key column to
     * its VALUES()); append-only tables → INSERT IGNORE (explicit id keeps
     * re-runs from duplicating).
     */
    private String buildUpsertSql(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection sqlite = DriverManager.getConnection(sqliteUrl);
             Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            var meta = rs.getMetaData();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                columns.add(meta.getColumnName(c));
            }
        }

        boolean latestWins = TABLES.stream()
            .anyMatch(p -> p.table().equals(table) && p.latestWins());
        String cols = String.join(", ", columns);
        String marks = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        if (latestWins) {
            StringBuilder updates = new StringBuilder();
            for (String col : columns) {
                if (updates.length() > 0) updates.append(", ");
                updates.append(col).append(" = VALUES(").append(col).append(")");
            }
            return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") "
                + "ON DUPLICATE KEY UPDATE " + updates;
        }
        return "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
    }

    // -- Verify helpers -----------------------------------------------------

    private long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private double sum(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getDouble(1) : 0;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // -- Report -------------------------------------------------------------

    private String writeReport(MigrationReport report) {
        try {
            Path dir = com.solidus.util.ConfigManager.getConfigDir().toAbsolutePath();
            Path file = dir.resolve("migration-report-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(java.time.LocalDateTime.now()) + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append("Solidus SQLite -> MySQL migration report\n");
            sb.append("========================================\n");
            sb.append("Result: ").append(report.success() ? "SUCCESS" : "FAILED").append('\n');
            sb.append("Duration: ").append(report.durationMs()).append(" ms\n");
            sb.append("Money supply: sqlite=").append(report.sqliteSupply())
                .append(" mysql=").append(report.mysqlSupply()).append('\n');
            sb.append("\nTables:\n");
            for (String line : report.tableCounts()) {
                sb.append("  - ").append(line).append('\n');
            }
            if (!report.issues().isEmpty()) {
                sb.append("\nIssues:\n");
                for (String issue : report.issues()) {
                    sb.append("  ! ").append(issue).append('\n');
                }
            }
            sb.append("\nNext steps:\n");
            sb.append("  1. Keep the SQLite files read-only as the rollback copy.\n");
            sb.append("  2. Set \"type\": \"mysql\" in config/solidus/storage.json.\n");
            sb.append("  3. Restart the server (or every server of the network).\n");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return file.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
