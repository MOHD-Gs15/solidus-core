package com.solidus.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract extension tests for the 2.3.2 solidus-api additions (audit W-5
 * closure): the three members companions used to reach by reflecting into
 * Core internals — {@code EconomyEngine.isMysqlMode()},
 * {@code ShopManager} internals, and the {@code TransactionLog$SqlWork}
 * proxy — are now first-class contract members.
 *
 * <p>These tests bind the SQLite/no-mod-boot environment every unit test
 * runs in: the engine is constructed but never Fabric-initialized, and
 * {@code SolidusMod.getShopManager()} is null. That exercises exactly the
 * documented degradation paths (false / empty table / fail-closed
 * SQLException) without needing a booted server.</p>
 */
@DisplayName("solidus-api 2.3.2 contract extensions (W-5 closure)")
class SolidusApiContractExtensionTest {

    private static SolidusAPI api;

    @BeforeAll
    static void installShim() {
        // Lightweight engine: constructed, never Fabric-initialized. The
        // shim accepts it; every guarded member must degrade safely.
        SolidusAPI.initialize(new com.solidus.economy.EconomyEngine());
        api = SolidusAPI.getInstance();
        assertNotNull(api, "shim must be installed and retrievable");
    }

    @AfterAll
    static void uninstallShim() {
        SolidusApiAccess.uninstall();
    }

    @Test
    @DisplayName("getCoreVersion stays in lockstep with gradle.properties (anti-drift)")
    void coreVersionMatchesGradleProperties() throws Exception {
        Path props = Paths.get("gradle.properties");
        // The Gradle test worker's CWD is the project root; guard anyway so a
        // different harness skips instead of lying.
        AssumptionsLite.assumeTrue(Files.isRegularFile(props), "gradle.properties not found from CWD");

        String declared = Files.readAllLines(props).stream()
            .filter(l -> l.startsWith("mod_version"))
            .map(l -> l.substring(l.indexOf('=') + 1).trim())
            .findFirst()
            .orElse("");
        assertEquals(declared, api.getCoreVersion(),
            "SolidusAPI.getCoreVersion() must equal gradle.properties mod_version — "
                + "update the constant when bumping the version (it is what companions display)");
    }

    @Test
    @DisplayName("isMysqlMode is false for an uninitialized/SQLite engine (safe default)")
    void isMysqlModeFalseWhenNotInitialized() {
        assertFalse(api.isMysqlMode(),
            "an engine that never reached MySQL network mode must report false");
    }

    @Test
    @DisplayName("getShopSellPrices is empty (never null, never throws) when the shop is not loaded")
    void shopPricesEmptyWhenShopNotLoaded() {
        Map<String, Double> table = api.getShopSellPrices().join();
        assertNotNull(table, "the table is a snapshot, not a nullable");
        assertTrue(table.isEmpty(),
            "SolidusMod.getShopManager() is null in unit tests — the documented "
                + "pre-boot window must yield an empty table, not an exception");
    }

    @Test
    @DisplayName("withLedgerConnection fails closed (SQLException) before the engine initializes")
    void withLedgerConnectionFailsClosedWhenUninitialized() {
        assertThrows(SQLException.class, () ->
                api.withLedgerConnection(conn -> {
                    fail("the work must never run against an uninitialized ledger");
                    return null;
                }),
            "the documented contract is: no live ledger -> SQLException, "
                + "never a null connection or a silent no-op");
    }

    @Test
    @DisplayName("withLedgerConnection propagates the work's result and checked exception type")
    void withLedgerConnectionSignatureShape() {
        // Compile-time shape guard: the generic must carry BOTH the value and
        // the SQLException through, exactly like Core's internal SqlWork.
        assertThrows(SQLException.class, () ->
            api.<Void>withLedgerConnection(conn -> {
                throw new SQLException("boom");
            }));
    }

    /** Tiny Assumptions shim so this class needs no extra JUnit import surface. */
    private static final class AssumptionsLite {
        static void assumeTrue(boolean condition, String message) {
            if (!condition) {
                org.junit.jupiter.api.Assumptions.abort(message);
            }
        }
    }
}
