package com.solidus.economy;

import com.solidus.SolidusMod;
import com.solidus.util.ConfigManager;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Economy Engine - Central coordinator for the Solidus economy system.
 *
 * The EconomyEngine is the top-level manager that owns and coordinates all
 * economy subsystems: database storage, balance management, and anti-farm
 * deflation. It is initialized once during mod startup and provides access
 * to all subsystems through getter methods.
 *
 * Lifecycle:
 * 1. initialize() - Called during mod startup. Creates DB, loads config.
 * 2. Runtime - Commands and shop/auction systems use getBalanceManager().
 * 3. shutdown() - Called on server stop. Flushes data, closes connections.
 */
public class EconomyEngine {

    private StorageBackend storage;
    private BalanceManager balanceManager;
    private volatile boolean initialized = false;

    public EconomyEngine() {
        // Construction is lightweight; actual work happens in initialize()
    }

    /**
     * Initializes the economy engine and all subsystems.
     * Must be called once during mod startup, after ConfigManager is ready.
     */
    public void initialize() {
        SolidusMod.LOGGER.info("Initializing Solidus Economy Engine...");

        // Initialize config directory using FabricLoader's API
        // This is the reliable way to get the config directory in a Fabric mod,
        // instead of using Path.of(".") which depends on the CWD (current working directory)
        // and could resolve to the wrong path if the server is launched from a different directory.
        // FabricLoader.getInstance().getConfigDir() always returns the correct path
        // regardless of where the JVM was started from.
        ConfigManager.initialize(FabricLoader.getInstance().getConfigDir().getParent());

        // Select the storage backend from config/solidus/storage.json
        // (DB scaling plan Phase 1/2): "sqlite" (default) or "mysql" (2.2.0+).
        StorageConfig storageConfig = StorageConfig.load();
        if (storageConfig.type() == StorageConfig.Type.MYSQL) {
            MySqlStorage mysql = new MySqlStorage(storageConfig.mysql());
            mysql.initialize(); // fails closed with a clear error if the DB is unreachable
            storage = mysql;
            SolidusMod.LOGGER.info("Storage backend: MySQL/MariaDB (multi-server mode, unified balances)");
        } else {
            String dbPath = ConfigManager.getConfigDir().toAbsolutePath().toString();
            SQLiteStorage sqlite = new SQLiteStorage(dbPath);
            sqlite.initialize();
            storage = sqlite;
            SolidusMod.LOGGER.info("Storage backend: SQLite (single-server mode)");
        }

        // Pre-create the bid-escrow system account with balance 0. Without
        // this, the first atomic transfer INTO escrow would create the row as
        // "starting balance + amount", minting phantom money that locks itself
        // inside the system account forever. With a zero-balance row in place,
        // escrow holds EXACTLY the sum of all open top bids at any moment.
        storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0);

        // Initialize balance manager
        balanceManager = new BalanceManager(storage);

        initialized = true;
        SolidusMod.LOGGER.info("Solidus Economy Engine initialized successfully.");
        SolidusMod.LOGGER.info("Starting balance: {} | Currency: {}",
            com.solidus.util.CurrencyUtil.getStartingBalance(),
            com.solidus.util.CurrencyUtil.CURRENCY_NAME);
    }

    /**
     * Shuts down the economy engine gracefully.
     * Flushes all pending database operations and closes connections.
     * Must be called on server stop to prevent data loss.
     */
    public void shutdown() {
        if (!initialized) return;

        SolidusMod.LOGGER.info("Shutting down Solidus Economy Engine...");
        storage.shutdown();
        initialized = false;
        SolidusMod.LOGGER.info("Solidus Economy Engine shut down complete.");
    }

    /**
     * Gets the balance manager for performing economy operations.
     * @throws IllegalStateException if called before initialization
     */
    public BalanceManager getBalanceManager() {
        ensureInitialized();
        return balanceManager;
    }

    /**
     * Gets the storage backend (for advanced/internal operations).
     * The concrete implementation is selected by {@code storage.json}.
     * @throws IllegalStateException if called before initialization
     */
    public StorageBackend getStorage() {
        ensureInitialized();
        return storage;
    }

    /**
     * Gets the transaction log for recording and querying financial history.
     * @throws IllegalStateException if called before initialization
     */
    public TransactionLog getTransactionLog() {
        ensureInitialized();
        return storage.getTransactionLog();
    }

    /**
     * Checks if the engine is initialized and ready for operations.
     */
    public boolean isInitialized() {
        return initialized;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("EconomyEngine accessed before initialization!");
        }
    }
}
