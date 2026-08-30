package com.solidus.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link EconomyHooks} registry and dispatch semantics:
 * registration dedupe, first-denial-wins veto dispatch, fail-open exception
 * policy, and notification isolation. No Minecraft dependencies required.
 */
@DisplayName("EconomyHooks registry")
class EconomyHooksTest {

    @BeforeEach
    @AfterEach
    void cleanRegistry() {
        // Tests share the static registry - wipe it between tests.
        for (SolidusTransactionHook hook : EconomyHooks.registeredHooks()) {
            EconomyHooks.unregister(hook);
        }
    }

    /** Simple recording hook with configurable veto behavior. */
    private static class FakeHook implements SolidusTransactionHook {
        final String name;
        final List<String> calls = new CopyOnWriteArrayList<>();
        SolidusTransactionHook.Decision transferDecision = SolidusTransactionHook.Decision.ALLOW;
        RuntimeException throwInAllow = null;
        RuntimeException throwInNotify = null;

        FakeHook(String name) { this.name = name; }

        @Override public String name() { return name; }

        @Override public Decision allowTransfer(UUID s, String sn, UUID r, String rn, double amount) {
            calls.add("allowTransfer:" + amount);
            if (throwInAllow != null) throw throwInAllow;
            return transferDecision;
        }

        @Override public void afterTransfer(UUID s, String sn, UUID r, String rn, double amount) {
            calls.add("afterTransfer:" + amount);
            if (throwInNotify != null) throw throwInNotify;
        }
    }

    // -- Registration --------------------------------------

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("register accepts a new hook and hasHooks reflects it")
        void registerNew() {
            FakeHook hook = new FakeHook("test-a");
            assertTrue(EconomyHooks.register(hook));
            assertTrue(EconomyHooks.hasHooks());
            assertEquals(1, EconomyHooks.registeredHooks().size());
        }

        @Test
        @DisplayName("duplicate name is rejected (idempotent registration)")
        void duplicateNameRejected() {
            assertTrue(EconomyHooks.register(new FakeHook("dup")));
            assertFalse(EconomyHooks.register(new FakeHook("dup")));
            assertEquals(1, EconomyHooks.registeredHooks().size());
        }

        @Test
        @DisplayName("null hook and blank name are rejected")
        void invalidRejected() {
            assertFalse(EconomyHooks.register(null));
            assertFalse(EconomyHooks.register(new FakeHook("  ")));
            assertFalse(EconomyHooks.unregister(null));
        }

        @Test
        @DisplayName("unregister removes only the matching instance")
        void unregisterRemovesMatching() {
            FakeHook a = new FakeHook("hook-a");
            FakeHook b = new FakeHook("hook-b");
            assertTrue(EconomyHooks.register(a));
            assertTrue(EconomyHooks.register(b));
            assertTrue(EconomyHooks.unregister(a));
            assertFalse(EconomyHooks.unregister(a)); // already gone
            List<SolidusTransactionHook> remaining = EconomyHooks.registeredHooks();
            assertEquals(1, remaining.size());
            assertSame(b, remaining.get(0));
        }
    }

    // -- Veto dispatch -------------------------------------

    @Nested
    @DisplayName("Veto dispatch (allow)")
    class VetoDispatch {

        @Test
        @DisplayName("no hooks registered -> ALLOW")
        void allowWithNoHooks() {
            assertSame(SolidusTransactionHook.Decision.ALLOW,
                EconomyHooks.allow(h -> h.allowTransfer(null, null, null, null, 10)));
        }

        @Test
        @DisplayName("first denial wins; later hooks are not consulted")
        void firstDenialWins() {
            FakeHook first = new FakeHook("first");
            first.transferDecision = SolidusTransactionHook.Decision.deny("denied-by-first");
            FakeHook second = new FakeHook("second");
            second.transferDecision = SolidusTransactionHook.Decision.deny("denied-by-second");
            EconomyHooks.register(first);
            EconomyHooks.register(second);

            SolidusTransactionHook.Decision result =
                EconomyHooks.allow(h -> h.allowTransfer(null, null, null, null, 10));

            assertFalse(result.allowed());
            assertEquals("denied-by-first", result.reason());
            assertEquals(List.of("allowTransfer:10.0"), first.calls);
            assertTrue(second.calls.isEmpty(), "second hook must not be consulted after first denial");
        }

        @Test
        @DisplayName("null decision from a hook is treated as ALLOW")
        void nullDecisionAllowed() {
            FakeHook hook = new FakeHook("null-returner") {
                @Override public Decision allowTransfer(UUID s, String sn, UUID r, String rn, double a) {
                    return null;
                }
            };
            EconomyHooks.register(hook);
            assertTrue(EconomyHooks.allow(h -> h.allowTransfer(null, null, null, null, 10)).allowed());
        }

        @Test
        @DisplayName("throwing veto hook fails open and later hooks still run")
        void vetoExceptionFailsOpen() {
            FakeHook throwing = new FakeHook("thrower");
            throwing.throwInAllow = new IllegalStateException("boom");
            FakeHook healthy = new FakeHook("healthy");
            healthy.transferDecision = SolidusTransactionHook.Decision.deny("healthy-denial");
            EconomyHooks.register(throwing);
            EconomyHooks.register(healthy);

            SolidusTransactionHook.Decision result =
                EconomyHooks.allow(h -> h.allowTransfer(null, null, null, null, 10));

            assertFalse(result.allowed());
            assertEquals("healthy-denial", result.reason());
        }

        @Test
        @DisplayName("all hooks allow -> ALLOW")
        void allAllow() {
            FakeHook a = new FakeHook("allow-a");
            FakeHook b = new FakeHook("allow-b");
            EconomyHooks.register(a);
            EconomyHooks.register(b);
            assertTrue(EconomyHooks.allow(h -> h.allowTransfer(null, null, null, null, 10)).allowed());
            assertEquals(List.of("allowTransfer:10.0"), a.calls);
            assertEquals(List.of("allowTransfer:10.0"), b.calls);
        }
    }

    // -- Notification dispatch ------------------------------

    @Nested
    @DisplayName("Notification dispatch (notifyHooks)")
    class NotificationDispatch {

        @Test
        @DisplayName("all hooks receive the notification")
        void allNotified() {
            FakeHook a = new FakeHook("notify-a");
            FakeHook b = new FakeHook("notify-b");
            EconomyHooks.register(a);
            EconomyHooks.register(b);

            EconomyHooks.notifyHooks(h -> h.afterTransfer(null, null, null, null, 42.5));

            assertEquals(List.of("afterTransfer:42.5"), a.calls);
            assertEquals(List.of("afterTransfer:42.5"), b.calls);
        }

        @Test
        @DisplayName("one throwing hook does not block the others")
        void exceptionIsolated() {
            FakeHook throwing = new FakeHook("notify-thrower");
            throwing.throwInNotify = new IllegalStateException("boom");
            FakeHook healthy = new FakeHook("notify-healthy");
            EconomyHooks.register(throwing);
            EconomyHooks.register(healthy);

            assertDoesNotThrow(() ->
                EconomyHooks.notifyHooks(h -> h.afterTransfer(null, null, null, null, 1)));
            assertTrue(healthy.calls.size() == 1, "healthy hook must still be notified");
        }

        @Test
        @DisplayName("notification with no hooks is a no-op")
        void noHooksNoOp() {
            assertDoesNotThrow(() ->
                EconomyHooks.notifyHooks(h -> h.afterTransfer(null, null, null, null, 1)));
        }
    }

    // -- Decision record -----------------------------------

    @Nested
    @DisplayName("Decision record")
    class DecisionRecord {

        @Test
        @DisplayName("ALLOW is permissive with null reason")
        void allowConstant() {
            assertTrue(SolidusTransactionHook.Decision.ALLOW.allowed());
            assertNull(SolidusTransactionHook.Decision.ALLOW.reason());
        }

        @Test
        @DisplayName("deny carries the reason; null reason gets a fallback")
        void denyFactory() {
            SolidusTransactionHook.Decision d = SolidusTransactionHook.Decision.deny("daily limit reached");
            assertFalse(d.allowed());
            assertEquals("daily limit reached", d.reason());

            SolidusTransactionHook.Decision fallback = SolidusTransactionHook.Decision.deny(null);
            assertFalse(fallback.allowed());
            assertEquals("Transaction denied.", fallback.reason());
        }
    }

    // -- Default interface methods ---------------------------

    @Nested
    @DisplayName("Default interface behavior")
    class DefaultMethods {

        @Test
        @DisplayName("a bare hook implementing only name() defaults every hook point to ALLOW/no-op")
        void bareHookDefaults() {
            SolidusTransactionHook bare = new SolidusTransactionHook() {
                @Override public String name() { return "bare"; }
            };
            UUID uuid = UUID.randomUUID();
            assertSame(SolidusTransactionHook.Decision.ALLOW, bare.allowTransfer(uuid, "a", uuid, "b", 1));
            assertSame(SolidusTransactionHook.Decision.ALLOW, bare.allowAuctionListing(uuid, "a", 1));
            assertSame(SolidusTransactionHook.Decision.ALLOW, bare.allowAuctionPurchase(uuid, "a", 1));
            assertSame(SolidusTransactionHook.Decision.ALLOW, bare.allowShopPurchase(uuid, "a", 1));
            assertSame(SolidusTransactionHook.Decision.ALLOW, bare.allowShopSell(uuid, "a"));
            assertDoesNotThrow(() -> {
                bare.afterTransfer(uuid, "a", uuid, "b", 1);
                bare.afterAuctionListing(uuid, "a", 1, 0.1);
                bare.afterAuctionSale(uuid, "a", uuid, "b", 1);
                bare.afterShopPurchase(uuid, "a", 1);
                bare.afterShopSell(uuid, "a", 1);
            });
        }

        @Test
        @DisplayName("counting hook records every hook point invocation via defaults test")
        void hookPointCounting() {
            AtomicInteger counter = new AtomicInteger();
            SolidusTransactionHook counting = new SolidusTransactionHook() {
                @Override public String name() { return "counting"; }
                @Override public Decision allowTransfer(UUID s, String sn, UUID r, String rn, double a) {
                    counter.incrementAndGet();
                    return Decision.ALLOW;
                }
            };
            EconomyHooks.register(counting);
            EconomyHooks.allow(h -> h.allowTransfer(null, null, null, null, 5));
            assertEquals(1, counter.get());
        }
    }
}
