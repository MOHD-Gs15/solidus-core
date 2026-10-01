package com.solidus.api;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * LedgerWork — one bounded unit of ledger SQL, carried over the contract
 * boundary.
 *
 * <p>Companion-side twin of Core's {@code TransactionLog.SqlWork}: the
 * work receives whatever live JDBC connection Core's storage is currently
 * configured for (the shared SQLite connection in single-server mode, a
 * pooled MySQL/MariaDB connection in network mode), so a companion can
 * read the ledger without ever knowing — or reflectively reaching into —
 * Core's storage internals (audit finding W-5).</p>
 *
 * <h3>Rules of use (see {@link SolidusApi#withLedgerConnection}):</h3>
 * <ul>
 *   <li><b>Read-only, bounded queries only</b> — SELECT with a LIMIT or an
 *       aggregate. A companion must never write to Core's database through
 *       this seam; mutations belong on the {@code *Balance}/{@code transfer}
 *       API methods so the ledger stays the single source of truth.</li>
 *   <li><b>Background threads only</b> — the call runs synchronously on the
 *       caller's thread. Never call it from the server tick thread.</li>
 *   <li>The connection is managed by Core (borrowed/returned or shared);
 *       the work must not close it, cache it, or hand it to another
 *       thread.</li>
 * </ul>
 *
 * @param <T> the query result type
 * @since 2.3.2 (family contract 2.3)
 */
@FunctionalInterface
public interface LedgerWork<T> {

    /**
     * Runs the query on the given live ledger connection.
     *
     * @param connection Core's current ledger connection (never null)
     * @return the query result
     * @throws SQLException when the query fails; Core propagates it as-is
     */
    T run(Connection connection) throws SQLException;
}
