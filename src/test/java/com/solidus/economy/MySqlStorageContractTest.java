package com.solidus.economy;

import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Binds the {@link StorageBackendContractTest} harness to {@link MySqlStorage}
 * against a REAL MySQL/MariaDB database.
 *
 * <p><b>Activation</b> (self-skipping otherwise): set
 * {@code SOLIDUS_TEST_MYSQL_HOST} (optionally PORT/DATABASE/USER/PASSWORD via
 * {@code SOLIDUS_TEST_MYSQL_*}; defaults: 127.0.0.1:3306, database
 * {@code solidus_test}, user/password {@code solidus}). The database must
 * already exist — the mod auto-creates its TABLES, not the database itself
 * (matching production: operators provision the schema role).</p>
 *
 * <p>Designed for a CI service container — example GitHub Actions:</p>
 * <pre>
 * services:
 *   mariadb:
 *     image: mariadb:10.11
 *     env: { MARIADB_DATABASE: solidus_test, MARIADB_USER: solidus, MARIADB_PASSWORD: solidus }
 *     ports: [ "3306:3306" ]
 * env:
 *   SOLIDUS_TEST_MYSQL_HOST: 127.0.0.1
 *   SOLIDUS_TEST_MYSQL_USER: solidus
 *   SOLIDUS_TEST_MYSQL_PASSWORD: solidus
 * </pre>
 */
public class MySqlStorageContractTest extends StorageBackendContractTest {

    private static final String HOST = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PORT", "3306"));
    private static final String DATABASE = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_DATABASE", "solidus_test");
    private static final String USER = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_USER", "solidus");
    private static final String PASSWORD = System.getenv().getOrDefault("SOLIDUS_TEST_MYSQL_PASSWORD", "solidus");

    private MySqlStorage mysql;

    private static void assumeDatabaseConfigured() {
        Assumptions.assumeTrue(System.getenv("SOLIDUS_TEST_MYSQL_HOST") != null,
            "SOLIDUS_TEST_MYSQL_HOST not set — MySQL contract tests skipped "
                + "(the SQLite contract binds the same harness in CI)");
    }

    @Override
    protected StorageBackend createBackend() {
        assumeDatabaseConfigured();
        StorageConfig.MySqlSettings settings = new StorageConfig.MySqlSettings(
            HOST, PORT, DATABASE, USER, PASSWORD, 6, 5000, false);
        mysql = new MySqlStorage(settings);
        return mysql;
    }

    @Override
    protected void destroyBackend() throws Exception {
        if (mysql != null) {
            mysql.shutdown();
            wipeTables();
            mysql = null;
        }
    }

    /** Clears the shared tables so the next run starts from a clean slate. */
    private void wipeTables() throws Exception {
        String url = "jdbc:mariadb://" + HOST + ":" + PORT + "/" + DATABASE;
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : new String[]{
                    "player_balances", "transaction_log",
                    "pending_notifications", "operations"}) {
                stmt.execute("DELETE FROM `" + table + "`");
            }
            stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
