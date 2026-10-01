package com.solidus.compat;

import net.minecraft.world.inventory.ContainerInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Degradation tests for the update-resilience architecture (audit W-3,
 * family 2.3.0). The mixin config declares {@code require = 0}, so a
 * Minecraft internals change no longer crashes the server at startup —
 * which means <b>nothing but these tests would notice if the graceful
 * path itself regressed</b>. This suite pins every guarantee the banner
 * in the README makes:
 *
 * <ol>
 *   <li><b>Happy path:</b> against the exact Minecraft this build compiles
 *       against, all probes pass, the 26.1 bridge is wired, and input
 *       normalization works. (This is also a canary: bumping the Minecraft
 *       dependency without updating the probes fails HERE first.)</li>
 *   <li><b>Failed-probe path:</b> the GUI layer degrades to a safe
 *       pass-through — clicks route to vanilla, input resolves to OTHER,
 *       menu opens are refused — while the state reports exactly what
 *       failed.</li>
 *   <li><b>Runtime downgrade path:</b> probes passed but the mixin never
 *       applied (conflicting transformer) — the first-JOIN cross-check
 *       flips the GUI off mid-session, before any player opened a menu.</li>
 *   <li><b>Write-once semantics:</b> the first probe report wins; a
 *       downgrade never un-downgrades (no flapping while menus are
 *       open).</li>
 * </ol>
 *
 * <p>State manipulation: {@code CompatState.report}/{@code downgrade} are
 * package-private and write-once by design (production never resets them),
 * so each test resets the private statics via reflection first. That is a
 * test-only liberty — production code has no reset path, on purpose.</p>
 */
@DisplayName("Compat graceful degradation (audit W-3)")
class CompatDegradationTest {

    @BeforeEach
    void resetCompatState() throws Exception {
        setPrivateStatic(CompatState.class, "verified", false);
        setPrivateStatic(CompatState.class, "failures", List.of());
        setPrivateStatic(Compat.class, "clickBridge", null);
        setPrivateStatic(Compat.class, "inputResolver", null);
        setPrivateStatic(SolidusMixinPlugin.class, "containerClickMixinApplied", false);
    }

    // -- 1) Happy path: real probes against the compiled Minecraft --------

    @Test
    @DisplayName("probes pass against the Minecraft this build compiles against, and the 26.1 bridge wires up")
    void realProbesPassAndWireTheBridge() {
        Compat.verifyCompatibility();

        assertAll(
            () -> assertTrue(CompatState.isVerified(), "the probe must have run"),
            () -> assertTrue(CompatState.failures().isEmpty(),
                "every shape must match the compiled Minecraft — failures: " + CompatState.failures()),
            () -> assertTrue(CompatState.allProbesPassed()),
            () -> assertTrue(CompatState.isGuiSafe()),
            () -> assertTrue(CompatState.isClickRoutingAvailable()),
            () -> assertTrue(CompatState.summary().contains("all probes passed"),
                "summary was: " + CompatState.summary()));
    }

    @Test
    @DisplayName("wired resolver normalizes the real ContainerInput constants (PICKUP / QUICK_MOVE / unknown)")
    void wiredResolverNormalizesRealInputs() {
        Compat.verifyCompatibility();

        assertEquals(ClickDescriptor.Input.PICKUP, Compat.resolveInput(ContainerInput.PICKUP));
        assertEquals(ClickDescriptor.Input.QUICK_MOVE, Compat.resolveInput(ContainerInput.QUICK_MOVE));
        assertEquals(ClickDescriptor.Input.OTHER, Compat.resolveInput(null),
            "null input must degrade to OTHER, never throw");
        assertEquals(ClickDescriptor.Input.OTHER, Compat.resolveInput(new Object()),
            "an unrecognized input object must degrade to OTHER");
    }

    @Test
    @DisplayName("describe() composes the stable click descriptor from raw override parameters")
    void describeComposesStableDescriptor() {
        Compat.verifyCompatibility();

        ClickDescriptor click = Compat.describe(14, 0, ContainerInput.PICKUP);
        assertEquals(14, click.slotIndex());
        assertEquals(0, click.button());
        assertEquals(ClickDescriptor.Input.PICKUP, click.input());
    }

    // -- 2) Failed-probe path: graceful, explicit, pass-through ----------

    @Test
    @DisplayName("a failed probe disables the GUI layer without touching anything else")
    void failedProbeDisablesGuiGracefully() {
        // Simulate the exact report CompatProbes would produce after a
        // Minecraft update renamed the packet accessor.
        CompatState.report(List.of(
            "ServerboundContainerClickPacket#slotNum() -> NoSuchMethodException"));

        assertAll(
            () -> assertTrue(CompatState.isVerified(), "the report still counts as a probe run"),
            () -> assertFalse(CompatState.allProbesPassed()),
            () -> assertFalse(CompatState.isGuiSafe(), "no virtual menu may open"),
            () -> assertFalse(CompatState.isClickRoutingAvailable()),
            () -> assertTrue(CompatState.summary().contains("slotNum"),
                "the summary must name the failed shape — was: " + CompatState.summary()),
            () -> assertEquals(1, CompatState.failures().size()));
    }

    @Test
    @DisplayName("in the degraded state clicks pass through to vanilla and inputs resolve to OTHER")
    void degradedStateIsPassThrough() {
        CompatState.report(List.of("AbstractContainerMenu#clicked(int,int,ContainerInput,Player) -> NoSuchMethodException"));

        assertFalse(Compat.routeContainerClick(null, new Object()),
            "unroutable state must return false (vanilla handles the click) — never throw");
        assertEquals(ClickDescriptor.Input.OTHER, Compat.resolveInput(ContainerInput.PICKUP),
            "with no wired resolver every input is OTHER (no handler can act on it)");
        assertFalse(Compat.ensureGuiAvailable(null),
            "the GUI gate must refuse; a null player must not throw (rate-limit map is keyed on UUID)");
    }

    @Test
    @DisplayName("unverified state (probe never ran) is also GUI-unsafe and says so")
    void unverifiedStateIsUnsafeAndLoud() {
        assertFalse(CompatState.isVerified());
        assertFalse(CompatState.isGuiSafe(),
            "before the probe runs, no menu may open either");
        assertEquals("compat: probe has not run yet", CompatState.summary());
        assertFalse(Compat.ensureGuiAvailable(null));
    }

    // -- 3) Runtime downgrade: probes green, mixin never applied ----------

    @Test
    @DisplayName("probes passed but the mixin never applied: first-JOIN cross-check downgrades the GUI mid-session")
    void runtimeDowngradeWhenMixinMissing() {
        // All shapes verified...
        CompatState.report(List.of());
        assertTrue(CompatState.isGuiSafe(), "precondition: probes green");

        // ...but Mixin never applied the injection (no postApply event fired
        // — exactly the unit-test environment, where no mixin system runs).
        Compat.confirmClickMixinAfterFirstConnection();

        assertAll(
            () -> assertFalse(CompatState.isGuiSafe(),
                "a green probe with a dead mixin must still mean GUI-off"),
            () -> assertEquals(1, CompatState.failures().size()),
            () -> assertTrue(CompatState.failures().get(0).contains("ContainerClickMixin"),
                "the downgrade must name the missing mixin — was: " + CompatState.failures()),
            () -> assertFalse(CompatState.isClickRoutingAvailable()));
    }

    @Test
    @DisplayName("the cross-check is silent when the mixin DID apply")
    void crossCheckSilentWhenMixinApplied() {
        new SolidusMixinPlugin().postApply(
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            null, SolidusMixinPlugin.CONTAINER_CLICK_MIXIN, null);

        CompatState.report(List.of());
        Compat.confirmClickMixinAfterFirstConnection();

        assertTrue(CompatState.isGuiSafe(),
            "probe green + mixin applied = the one state where menus open");
    }

    // -- 4) State machine semantics ---------------------------------------

    @Test
    @DisplayName("the first probe report wins — later reports cannot rewrite history")
    void reportIsWriteOnceFirstWins() {
        CompatState.report(List.of("first failure"));
        CompatState.report(List.of());

        assertEquals(1, CompatState.failures().size());
        assertEquals("first failure", CompatState.failures().get(0),
            "a second (possibly greener) report must not resurrect the GUI");
    }

    @Test
    @DisplayName("a downgrade after a healthy probe run flips the state and it stays flipped")
    void downgradeIsSticky() {
        CompatState.report(List.of());
        assertTrue(CompatState.allProbesPassed());

        CompatState.downgrade("late runtime failure");

        assertFalse(CompatState.allProbesPassed());
        assertTrue(CompatState.summary().contains("late runtime failure"));
        // Still sticky after further reports:
        CompatState.report(List.of());
        assertFalse(CompatState.allProbesPassed(),
            "a downgrade must never un-downgrade (no flapping while menus are open)");
    }

    // -- 5) Mixin plugin observation contract -----------------------------

    @Test
    @DisplayName("the mixin plugin observes but never vetoes, and only tracks its own mixin")
    void mixinPluginObservesWithoutVeto() {
        SolidusMixinPlugin plugin = new SolidusMixinPlugin();

        assertAll(
            () -> assertTrue(plugin.shouldApplyMixin("any.Target", "any.Mixin"),
                "the plugin must never veto an apply"),
            () -> assertNull(plugin.getRefMapperConfig(),
                "unobfuscated Minecraft: the refmap must stay disabled"),
            () -> assertFalse(SolidusMixinPlugin.isContainerClickMixinApplied()));

        // A DIFFERENT mixin applying must not trip the flag.
        plugin.postApply("net.minecraft.X", null, "com.solidus.mixin.SomeOtherMixin", null);
        assertFalse(SolidusMixinPlugin.isContainerClickMixinApplied());

        // The tracked one applying must.
        plugin.postApply("net.minecraft.server.network.ServerGamePacketListenerImpl",
            null, SolidusMixinPlugin.CONTAINER_CLICK_MIXIN, null);
        assertTrue(SolidusMixinPlugin.isContainerClickMixinApplied());
    }

    // -- helpers -----------------------------------------------------------

    private static void setPrivateStatic(Class<?> owner, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }
}
