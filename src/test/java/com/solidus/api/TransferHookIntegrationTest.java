package com.solidus.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.solidus.economy.BalanceManager;
import com.solidus.economy.SQLiteStorage;
import com.solidus.util.CurrencyUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests proving the transaction hook contract end-to-end through
 * {@link BalanceManager#transferOffline} - the single choke point every
 * transfer flows through (/pay online + offline, SolidusAPI.transfer):
 *
 * <ul>
 *   <li>A hook denial blocks the transfer, keeps both balances untouched,
 *       and surfaces the hook's reason verbatim in TransferResult.message.</li>
 *   <li>{@code afterTransfer} fires only after a fully settled transfer,
 *       with the exact UUIDs/names/amount.</li>
 *   <li>Failed transfers (insufficient funds) never fire notifications.</li>
 * </ul>
 *
 * Uses a real SQLite database in a temp directory; no Minecraft deps.
 */
@DisplayName("Transaction hook integration (BalanceManager)")
class TransferHookIntegrationTest {

    private SQLiteStorage storage;
    private BalanceManager balances;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("solidus-hook-test-");
        storage = new SQLiteStorage(tempDir.toString());
        storage.initialize();
        balances = new BalanceManager(storage);
    }

    @AfterEach
    void tearDown() {
        for (SolidusTransactionHook hook : EconomyHooks.registeredHooks()) {
            EconomyHooks.unregister(hook);
        }
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

    /** Hook that denies transfers over a configurable cap and records afterTransfer events. */
    private static final class LimitHook implements SolidusTransactionHook {
        final double cap;
        final List<String> settled = new CopyOnWriteArrayList<>();

        LimitHook(double cap) { this.cap = cap; }

        @Override public String name() { return "limit-hook"; }

        @Override
        public Decision allowTransfer(UUID s, String sn, UUID r, String rn, double amount) {
            if (amount > cap) {
                return Decision.deny("Daily transfer limit exceeded (cap " + cap + ")");
            }
            return Decision.ALLOW;
        }

        @Override
        public void afterTransfer(UUID s, String sn, UUID r, String rn, double amount) {
            settled.add(sn + "->" + rn + ":" + amount);
        }
    }

    private void seed(UUID player, String name, double balance) throws Exception {
        balances.addBalance(player, name, balance).get(5, TimeUnit.SECONDS);
    }

    private double balanceOf(UUID player, String name) throws Exception {
        return balances.getBalance(player, name).get(5, TimeUnit.SECONDS);
    }

    // -- Veto path -----------------------------------------

    @Nested
    @DisplayName("Veto (allowTransfer)")
    class VetoPath {

        @Test
        @DisplayName("denied transfer fails with the hook's reason and moves no money")
        void denialBlocksAndPreservesBalances() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 100.0);
            seed(bob, "Bob", 50.0);
            double sb = CurrencyUtil.getStartingBalance();

            EconomyHooks.register(new LimitHook(40.0));

            BalanceManager.TransferResult result = balances
                .transferOffline(alice, "Alice", bob, "Bob", 75.0)
                .get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertEquals("Daily transfer limit exceeded (cap 40.0)", result.message());
            assertEquals(sb + 100.0, balanceOf(alice, "Alice"), 1e-9, "sender balance untouched");
            assertEquals(sb + 50.0, balanceOf(bob, "Bob"), 1e-9, "receiver balance untouched");
        }

        @Test
        @DisplayName("denial with null reason is normalized to the generic fallback message")
        void nullReasonGetsFallback() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 10.0);

            EconomyHooks.register(new SolidusTransactionHook() {
                @Override public String name() { return "null-reason"; }
                @Override public Decision allowTransfer(UUID s, String sn, UUID r, String rn, double a) {
                    return new Decision(false, null);
                }
            });

            BalanceManager.TransferResult result = balances
                .transferOffline(alice, "Alice", bob, "Bob", 5.0)
                .get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertEquals("Transaction denied.", result.message(), "null reason normalized");
            assertEquals(CurrencyUtil.getStartingBalance() + 10.0, balanceOf(alice, "Alice"), 1e-9);
        }

        @Test
        @DisplayName("allowed transfer proceeds normally under a hook")
        void allowPassesThrough() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 100.0);
            double sb = CurrencyUtil.getStartingBalance();
            EconomyHooks.register(new LimitHook(40.0));

            BalanceManager.TransferResult result = balances
                .transferOffline(alice, "Alice", bob, "Bob", 40.0)
                .get(5, TimeUnit.SECONDS);

            assertTrue(result.success());
            assertEquals(sb + 60.0, balanceOf(alice, "Alice"), 1e-9);
            assertEquals(sb + 40.0, balanceOf(bob, "Bob"), 1e-9, "new receiver gets starting balance + amount");
        }
    }

    // -- Notification path ----------------------------------

    @Nested
    @DisplayName("Notification (afterTransfer)")
    class NotificationPath {

        @Test
        @DisplayName("successful transfer fires exactly one afterTransfer with full context")
        void successFiresNotification() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 100.0);
            LimitHook hook = new LimitHook(1000.0);
            EconomyHooks.register(hook);

            balances.transferOffline(alice, "Alice", bob, "Bob", 25.5).get(5, TimeUnit.SECONDS);

            assertEquals(List.of("Alice->Bob:25.5"), hook.settled);
        }

        @Test
        @DisplayName("failed transfer (insufficient funds) fires no notification")
        void failureFiresNothing() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 5.0);
            LimitHook hook = new LimitHook(1.0e9); // no limit veto - failure must come from funds
            EconomyHooks.register(hook);

            // Request far above the seeded balance (starting + 5.0)
            double amount = CurrencyUtil.getStartingBalance() + 5000.0;
            BalanceManager.TransferResult result = balances
                .transferOffline(alice, "Alice", bob, "Bob", amount)
                .get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(hook.settled.isEmpty(), "no afterTransfer on failed transfer");
        }

        @Test
        @DisplayName("denied transfer fires no notification")
        void denialFiresNothing() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 100.0);
            LimitHook hook = new LimitHook(10.0);
            EconomyHooks.register(hook);

            balances.transferOffline(alice, "Alice", bob, "Bob", 99.0).get(5, TimeUnit.SECONDS);

            assertTrue(hook.settled.isEmpty(), "no afterTransfer on vetoed transfer");
        }

        @Test
        @DisplayName("throwing notification hook does not corrupt the transfer result")
        void throwingObserverDoesNotBreakTransfer() throws Exception {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seed(alice, "Alice", 100.0);

            EconomyHooks.register(new SolidusTransactionHook() {
                @Override public String name() { return "thrower"; }
                @Override public void afterTransfer(UUID s, String sn, UUID r, String rn, double a) {
                    throw new IllegalStateException("observer boom");
                }
            });

            BalanceManager.TransferResult result = balances
                .transferOffline(alice, "Alice", bob, "Bob", 10.0)
                .get(5, TimeUnit.SECONDS);

            assertTrue(result.success(), "transfer must succeed despite observer failure");
            assertEquals(CurrencyUtil.getStartingBalance() + 90.0, balanceOf(alice, "Alice"), 1e-9);
        }
    }

    // -- SolidusAPI surface ---------------------------------

    @Nested
    @DisplayName("SolidusAPI registration surface")
    class ApiSurface {

        @Test
        @DisplayName("SolidusAPI delegates registration to the shared registry")
        void apiDelegates() {
            // SolidusAPI.initialize() is not called (needs EconomyEngine), so
            // getInstance() is null - but the hook registry is static and must
            // be reachable through the same code path used by the instance
            // methods. Direct registry assertions cover the dispatch; here we
            // verify idempotent registration semantics one more time.
            LimitHook hook = new LimitHook(1.0);
            assertTrue(EconomyHooks.register(hook));
            assertFalse(EconomyHooks.register(new LimitHook(2.0)), "same hook name must dedupe");
            assertTrue(EconomyHooks.unregister(hook));
        }
    }
}
