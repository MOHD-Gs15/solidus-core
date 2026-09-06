package com.solidus.economy;

import com.solidus.util.CurrencyUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Network-wide supply integrity checker (DB scaling plan §7 — shipped 2.2.4).
 *
 * <p><b>The invariant.</b> On a shared-database network, money is trusted
 * because every mutation is one atomic transaction with ledger evidence. This
 * checker independently RE-AUDITS that story on a schedule: the observed
 * supply (SUM of all {@code player_balances} rows, escrow account included —
 * escrow is a balance row) must equal what the ledger says the supply should
 * have become since the previous checkpoint:</p>
 *
 * <pre>
 * expected = checkpoint_supply
 *          + Σ metered ledger deltas in the window   (shop + admin + death)
 *          + starting_balance × rows_created          (auto-create mints)
 * unexplained = observed_supply − expected
 * </pre>
 *
 * <p><b>Metered ledger categories</b> (amounts are stored as positive
 * magnitudes; the type carries the direction): {@code SHOP_SELL} mints,
 * {@code SHOP_BUY} burns, {@code ADMIN_GIVE} mints, {@code ADMIN_TAKE} burns,
 * {@code ADMIN_SET} applies its recorded SIGNED delta (2.2.4 corrected the
 * two admin paths to log the delta instead of the final balance),
 * {@code DEATH_PENALTY} burns, {@code DEATH_REWARD} mints. Every other
 * category (PAY_*, TRADE_*, BID_*, AUCTION_*) is a transfer between balance
 * rows — net zero by construction, so it must NOT appear in the sum.</p>
 *
 * <p><b>The row-growth term.</b> A first-ever balance row is materialized
 * with the starting balance by reads ({@code getBalance}), transfers, and
 * add/subtract — a mint the ledger does not record. Every new row adds
 * exactly {@code starting_balance} (the escrow sentinel row is created with
 * balance 0 before any checkpoint can exist, so it never lands inside a
 * window in practice; the account-create flow materializes via getBalance
 * first, keeping the term uniform).</p>
 *
 * <p><b>Known unmetered sources</b> (documented, tolerated): companion mods
 * moving money through the raw {@code SolidusAPI} add/subtract methods write
 * no ledger rows; {@code setBalance} on a never-seen row mints {@code amount}
 * instead of {@code starting + amount}. The {@code unexplained} figure in the
 * report includes exactly these — a warning is a prompt to read the
 * breakdown, not an alarm that money is broken.</p>
 *
 * <p><b>Checkpoint protocol.</b> A single-row table
 * ({@code solidus_supply_checkpoint}) stores (supply, ledger watermark,
 * row count) of the last CLEAN run. The watermark is the max ledger id the
 * SUM query actually covered (captured in the same query, so rows committed
 * mid-check are never silently skipped). On drift the checkpoint is NOT
 * advanced — the baseline stays sticky so the next run re-audits the same
 * window; after investigation, {@link #rebaseline()} accepts the current
 * state as the new baseline.</p>
 *
 * <p><b>Scheduling + election.</b> SolidusMod runs {@link #runOnce()} on the
 * configured interval where {@code integrity.enabled && integrity.elected}.
 * On a network the runbook sets {@code elected: true} on exactly one server.
 * The check is strictly READ-ONLY against the money tables — a duplicate
 * election misconfiguration can only duplicate warnings, never move money.
 * The per-instance escrow consistency check
 * ({@code AuctionManager#checkEscrowConsistency}) runs on the same cadence.</p>
 */
public final class SupplyIntegrity {

    private static final Logger LOGGER = LoggerFactory.getLogger(SupplyIntegrity.class);

    /** Single-row checkpoint table (dialect-aware DDL). */
    private static final String CHECKPOINT_TABLE = "solidus_supply_checkpoint";

    private static final String CREATE_CHECKPOINT_SQLITE = """
        CREATE TABLE IF NOT EXISTS solidus_supply_checkpoint (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            supply REAL NOT NULL,
            ledger_watermark INTEGER NOT NULL,
            row_count INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """;

    private static final String CREATE_CHECKPOINT_MYSQL = """
        CREATE TABLE IF NOT EXISTS solidus_supply_checkpoint (
            id BIGINT PRIMARY KEY,
            supply DECIMAL(18,2) NOT NULL,
            ledger_watermark BIGINT NOT NULL,
            row_count INT NOT NULL,
            updated_at BIGINT NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """;

    private static final String DELETE_CHECKPOINT = "DELETE FROM solidus_supply_checkpoint";

    /**
     * Metered supply delta over the window (rows with id > watermark), plus
     * the watermark high-water mark of the rows the SUM actually covered.
     * Types are matched by their stored codes; amounts are positive
     * magnitudes with the direction carried by the type.
     */
    private static final String METERED_DELTA_SQL = """
        SELECT COALESCE(SUM(CASE type
                 WHEN 'SHOP_SELL'    THEN amount
                 WHEN 'SHOP_BUY'     THEN -amount
                 WHEN 'ADMIN_GIVE'   THEN amount
                 WHEN 'ADMIN_TAKE'   THEN -amount
                 WHEN 'ADMIN_SET'    THEN amount
                 WHEN 'DEATH_PENALTY' THEN -amount
                 WHEN 'DEATH_REWARD'  THEN amount
                 ELSE 0 END), 0) AS metered,
               COALESCE(MAX(id), 0) AS max_seen
        FROM transaction_log
        WHERE id > ?
        """;

    /** Observed supply + row count, one consistent read. */
    private static final String SUPPLY_AND_ROWS_SQL =
        "SELECT COUNT(*) AS rows_total, COALESCE(SUM(balance), 0) AS supply "
            + "FROM player_balances";

    private static final String INSERT_CHECKPOINT =
        "INSERT INTO " + CHECKPOINT_TABLE
            + " (id, supply, ledger_watermark, row_count, updated_at) VALUES (1, ?, ?, ?, ?)";

    private static final String UPDATE_CHECKPOINT =
        "UPDATE " + CHECKPOINT_TABLE
            + " SET supply = ?, ledger_watermark = ?, row_count = ?, updated_at = ? WHERE id = 1";

    private final TransactionLog log;
    private final TransactionLog.Dialect dialect;
    private final ExecutorService executor;
    private final double tolerance;

    public SupplyIntegrity(TransactionLog log,
                           TransactionLog.Dialect dialect,
                           ExecutorService executor,
                           double tolerance) {
        this.log = log;
        this.dialect = dialect;
        this.executor = executor;
        this.tolerance = tolerance;
    }

    /** One integrity check outcome — every intermediate number is exposed. */
    public record Report(
            boolean bootstrapped,
            double checkpointSupply,
            double meteredDelta,
            double rowGrowthMint,
            long rowsCreated,
            double observedSupply,
            double expectedSupply,
            double unexplained,
            boolean withinTolerance,
            long watermarkFrom,
            long watermarkTo,
            double tolerance,
            String note) {

        /** Multi-line human-readable summary for the admin command/console. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (bootstrapped) {
                sb.append("Supply integrity: BASELINE established (no prior checkpoint).\n");
                sb.append(String.format(java.util.Locale.ROOT,
                    "  observed supply: %.2f | ledger watermark: %d | rows: %d%n",
                    observedSupply, watermarkTo, rowsCreated + 0));
                sb.append("  Drift is measured from this point forward.");
                return sb.toString();
            }
            sb.append(String.format(java.util.Locale.ROOT,
                "Supply integrity: %s (unexplained %.2f, tolerance %.2f)%n",
                withinTolerance ? "OK" : "DRIFT DETECTED", unexplained, tolerance));
            sb.append(String.format(java.util.Locale.ROOT,
                "  observed supply %.2f | expected %.2f (checkpoint %.2f)%n",
                observedSupply, expectedSupply, checkpointSupply));
            sb.append(String.format(java.util.Locale.ROOT,
                "  metered ledger delta %+.2f | row-growth mint %+.2f (%d new rows)%n",
                meteredDelta, rowGrowthMint, rowsCreated));
            sb.append(String.format(java.util.Locale.ROOT,
                "  ledger window %d -> %d", watermarkFrom, watermarkTo));
            if (!withinTolerance) {
                sb.append("\n  Known unmetered sources: companion SolidusAPI add/subtract")
                    .append("\n  calls, setBalance on a missing row. Investigate the ledger")
                    .append("\n  window, then /solidus-admin integrity rebase to accept.");
            }
            return sb.toString();
        }
    }

    /**
     * Runs one full supply check. Never throws — failures become a Report
     * with a note (the scheduler must survive a transient database blip).
     */
    public CompletableFuture<Report> runOnce() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runOnceInternal();
            } catch (SQLException e) {
                LOGGER.error("Supply integrity check failed (transient?) — no state changed.", e);
                return new Report(false, 0, 0, 0, 0, 0, 0, 0, true, 0, 0, tolerance,
                    "check failed: " + e.getMessage() + " — checkpoint untouched");
            }
        }, executor);
    }

    private Report runOnceInternal() throws SQLException {
        return log.withConnection(conn -> {
            ensureCheckpointTable(conn);

            Double ckptSupply = null;
            long watermarkFrom = 0;
            long ckptRows = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT supply, ledger_watermark, row_count FROM "
                        + CHECKPOINT_TABLE + " WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ckptSupply = rs.getDouble("supply");
                    watermarkFrom = rs.getLong("ledger_watermark");
                    ckptRows = rs.getLong("row_count");
                }
            }

            double observedSupply;
            long rowsNow;
            try (PreparedStatement ps = conn.prepareStatement(SUPPLY_AND_ROWS_SQL);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                rowsNow = rs.getLong("rows_total");
                observedSupply = CurrencyUtil.round(rs.getDouble("supply"));
            }

            double meteredDelta;
            long watermarkTo;
            try (PreparedStatement ps = conn.prepareStatement(METERED_DELTA_SQL)) {
                ps.setLong(1, watermarkFrom);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    meteredDelta = CurrencyUtil.round(rs.getDouble("metered"));
                    watermarkTo = rs.getLong("max_seen");
                }
            }

            // -- Bootstrap: accept today's books as the baseline ------------
            if (ckptSupply == null) {
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = conn.prepareStatement(INSERT_CHECKPOINT)) {
                    ps.setDouble(1, observedSupply);
                    ps.setLong(2, watermarkTo);
                    ps.setLong(3, rowsNow);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                LOGGER.info("Supply integrity: baseline established (supply {}, "
                        + "ledger watermark {}, rows {}). Drift is measured from now on.",
                    CurrencyUtil.format(observedSupply), watermarkTo, rowsNow);
                return new Report(true, 0, 0, 0, 0, observedSupply, observedSupply,
                    0, true, 0, watermarkTo, tolerance, "baseline established");
            }

            // -- Normal run: replay the window against the checkpoint -------
            long rowsCreated = Math.max(0, rowsNow - ckptRows);
            double rowGrowthMint = CurrencyUtil.round(
                CurrencyUtil.getStartingBalance() * rowsCreated);
            double expected = CurrencyUtil.round(
                ckptSupply + meteredDelta + rowGrowthMint);
            double unexplained = CurrencyUtil.round(observedSupply - expected);
            boolean within = Math.abs(unexplained) <= tolerance;

            if (within) {
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = conn.prepareStatement(UPDATE_CHECKPOINT)) {
                    ps.setDouble(1, observedSupply);
                    ps.setLong(2, watermarkTo);
                    ps.setLong(3, rowsNow);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
            } else {
                // Sticky baseline: the checkpoint stays so the next run
                // re-audits the SAME window until an admin rebaselines.
                LOGGER.warn("SUPPLY INTEGRITY: unexplained drift {} "
                        + "(observed {}, expected {}, metered {}, row-growth {}, window {} -> {}). "
                        + "Checkpoint NOT advanced — investigate the ledger window, then "
                        + "/solidus-admin integrity rebase if this is expected.",
                    CurrencyUtil.format(unexplained), CurrencyUtil.format(observedSupply),
                    CurrencyUtil.format(expected), CurrencyUtil.format(meteredDelta),
                    CurrencyUtil.format(rowGrowthMint), watermarkFrom, watermarkTo);
            }

            return new Report(false, ckptSupply, meteredDelta, rowGrowthMint,
                rowsCreated, observedSupply, expected, unexplained, within,
                watermarkFrom, watermarkTo, tolerance,
                within ? "ok" : "drift — checkpoint kept");
        });
    }

    /**
     * Accepts the CURRENT books as the new baseline (admin action after
     * investigating a drift). The next {@link #runOnce()} bootstraps fresh.
     */
    public CompletableFuture<Boolean> rebaseline() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return log.withConnection(conn -> {
                    ensureCheckpointTable(conn);
                    try (Statement st = conn.createStatement()) {
                        return st.executeUpdate(DELETE_CHECKPOINT) >= 0;
                    }
                });
            } catch (SQLException e) {
                LOGGER.error("Supply integrity rebase failed", e);
                return false;
            }
        }, executor);
    }

    private void ensureCheckpointTable(Connection conn) throws SQLException {
        String ddl = dialect == TransactionLog.Dialect.MYSQL
            ? CREATE_CHECKPOINT_MYSQL
            : CREATE_CHECKPOINT_SQLITE;
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
        }
    }
}
