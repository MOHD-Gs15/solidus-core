package com.solidus.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SQLiteStorage#getEconomyStats()} - the single-query
 * economy aggregates (count, mean, money supply, Gini) that replaced the
 * getTopBalances(100000) row pulls in companion mods (R28).
 *
 * Gini expectations are exact closed-form values:
 * <ul>
 *   <li>equal balances -> 0.0</li>
 *   <li>[0, 0, 100, 100] -> 0.5</li>
 *   <li>[0, 0, 0, 400] -> (n-1)/n = 0.75 (maximum inequality for n = 4)</li>
 * </ul>
 */
@DisplayName("Economy stats (single aggregate query)")
class EconomyStatsTest {

    private SQLiteStorage storage;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-econ-stats-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown();
        }
        try {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
        }
    }

    private void seed(double... balances) throws Exception {
        int i = 0;
        for (double balance : balances) {
            UUID uuid = UUID.fromString(String.format(
                "cc%06d-0000-0000-0000-000000000000", i++));
            assertTrue(storage.setBalance(uuid, "P" + i, balance).get(5, TimeUnit.SECONDS),
                "seed failed for balance " + balance);
        }
    }

    private SQLiteStorage.EconomyStats stats() throws Exception {
        return storage.getEconomyStats().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("empty economy reports zeros")
    void emptyEconomyIsZeros() throws Exception {
        SQLiteStorage.EconomyStats s = stats();
        assertEquals(0, s.playerCount());
        assertEquals(0.0, s.avgBalance(), 1e-9);
        assertEquals(0.0, s.totalSupply(), 1e-9);
        assertEquals(0.0, s.giniCoefficient(), 1e-9);
    }

    @Test
    @DisplayName("perfect equality has Gini 0 and exact count/mean/supply")
    void perfectEquality() throws Exception {
        seed(100, 100, 100, 100);
        SQLiteStorage.EconomyStats s = stats();
        assertEquals(4, s.playerCount());
        assertEquals(100.0, s.avgBalance(), 1e-9);
        assertEquals(400.0, s.totalSupply(), 1e-9);
        assertEquals(0.0, s.giniCoefficient(), 1e-9,
            "equal balances must yield a Gini of exactly 0");
    }

    @Test
    @DisplayName("half-inequality [0,0,100,100] has Gini 0.5")
    void halfInequality() throws Exception {
        seed(100, 0, 100, 0);
        SQLiteStorage.EconomyStats s = stats();
        assertEquals(4, s.playerCount());
        assertEquals(50.0, s.avgBalance(), 1e-9);
        assertEquals(200.0, s.totalSupply(), 1e-9);
        assertEquals(0.5, s.giniCoefficient(), 1e-6);
    }

    @Test
    @DisplayName("maximum inequality [0,0,0,400] has Gini (n-1)/n = 0.75")
    void maximumInequality() throws Exception {
        seed(0, 0, 0, 400);
        SQLiteStorage.EconomyStats s = stats();
        assertEquals(4, s.playerCount());
        assertEquals(0.75, s.giniCoefficient(), 1e-6);
    }

    @Test
    @DisplayName("gini stays in [0,1] for a mixed distribution")
    void mixedDistributionBounded() throws Exception {
        seed(5.5, 1200.25, 0.75, 300, 64, 90000, 17.5, 8);
        SQLiteStorage.EconomyStats s = stats();
        assertEquals(8, s.playerCount());
        assertTrue(s.giniCoefficient() >= 0.0 && s.giniCoefficient() <= 1.0,
            "gini must stay normalized, got " + s.giniCoefficient());
        assertEquals(91596.0, s.totalSupply(), 1e-6);
        assertEquals(11449.5, s.avgBalance(), 1e-6);
    }
}
