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

    private SQLiteStorage storage;
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

        // Initialize SQLite storage
        String dbPath = ConfigManager.getConfigDir().toAbsolutePath().toString();
        storage = new SQLiteStorage(dbPath);
        storage.initialize();

        // Initialize balance manager
        balanceManager = new BalanceManager(storage);

        initialized = true;
        SolidusMod.LOGGER.info("Solidus Economy Engine initialized successfully.");
        SolidusMod.LOGGER.info("Starting balance: {} | Currency: {}",
            com.solidus.util.CurrencyUtil.DEFAULT_STARTING_BALANCE,
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
     * Gets the raw SQLite storage (for advanced/internal operations).
     * @throws IllegalStateException if called before initialization
     */
    public SQLiteStorage getStorage() {
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
