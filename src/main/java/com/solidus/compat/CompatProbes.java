package com.solidus.compat;

import com.solidus.SolidusMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * CompatProbes — the startup "manual check" for Minecraft-version
 * compatibility (audit W-3 fix).
 *
 * <p>The mixin config sets {@code require = 0} on the high-risk
 * {@code handleContainerClick} injection, so a future Minecraft update
 * that reshapes the target degrades to a <b>log warning</b> instead of a
 * startup crash. But a silently-dead mixin means virtual-GUI clicks would
 * fall through to vanilla container handling — items could move in menus
 * that must be display-only. Something has to notice, and it has to notice
 * <i>before the first player opens a shop</i>.</p>
 *
 * <p>That is this class. At mod initialization we reflectively re-verify
 * every Minecraft shape the GUI pipeline compiled against:</p>
 *
 * <ol>
 *   <li>{@code ServerGamePacketListenerImpl#handleContainerClick(ServerboundContainerClickPacket)}</li>
 *   <li>the packet's record accessors {@code slotNum()}, {@code buttonNum()},
 *       {@code containerInput()}</li>
 *   <li>{@code ContainerInput}'s {@code PICKUP} and {@code QUICK_MOVE} constants</li>
 *   <li>{@code AbstractContainerMenu#clicked(int, int, ContainerInput, Player)}
 *       — the dispatch our ScreenHandlers override as defense-in-depth</li>
 *   <li>{@code AbstractContainerMenu#broadcastFullState()} — the anti-ghost resync</li>
 *   <li>the mixin's {@code @Shadow} targets: {@code player} on the packet
 *       listener, {@code containerMenu} on ServerPlayer, {@code containerId}
 *       on the menu</li>
 * </ol>
 *
 * <p>This works without any mappings knowledge because Minecraft 26.1+
 * ships unobfuscated — runtime names are Mojang's official names, exactly
 * the names we compiled against. A failed probe means the shapes no longer
 * match, which is precisely the situation where the GUI layer must be
 * switched off while commands/storage/ledger keep running.</p>
 *
 * <p><b>Failure policy of the probe itself:</b> fail-closed for the GUI
 * layer, never for the server. Any unexpected exception inside a probe is
 * caught, recorded as a failure with its cause, and the server boots on.
 * The probe can never itself become the crash it exists to prevent.</p>
 *
 * @see CompatState for the resulting capability flags
 * @since 2.3.0
 */
public final class CompatProbes {

    private CompatProbes() {
    }

    /**
     * Runs all probes once and stores the report in {@link CompatState}.
     * Called from SolidusMod initialization, before any GUI can open and
     * before the mixin has necessarily seen its first packet.
     *
     * <p>Idempotent: the first call wins and later calls are no-ops.</p>
     */
    public static synchronized void verifyCompatibility() {
        if (CompatState.isVerified()) {
            return;
        }

        List<String> failures = new ArrayList<>();

        probe(failures, "class net.minecraft.server.network.ServerGamePacketListenerImpl",
            () -> Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl"));

        Class<?> listenerClass = softClass("net.minecraft.server.network.ServerGamePacketListenerImpl");
        Class<?> packetClass = softClass("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");
        Class<?> containerInputClass = resolveContainerInputClass(packetClass);

        // 1) The mixin's injection target method.
        if (listenerClass != null && packetClass != null) {
            probe(failures, "ServerGamePacketListenerImpl#handleContainerClick(ServerboundContainerClickPacket)",
                () -> listenerClass.getDeclaredMethod("handleContainerClick", packetClass));
        }

        // 2) The packet record accessors the 26.1 impl reads.
        if (packetClass != null) {
            probe(failures, "ServerboundContainerClickPacket#slotNum()",
                () -> packetClass.getDeclaredMethod("slotNum"));
            probe(failures, "ServerboundContainerClickPacket#buttonNum()",
                () -> packetClass.getDeclaredMethod("buttonNum"));
            probe(failures, "ServerboundContainerClickPacket#containerInput()",
                () -> packetClass.getDeclaredMethod("containerInput"));
        }

        // 3) The ContainerInput constants the resolver maps.
        if (containerInputClass != null) {
            probe(failures, "ContainerInput.PICKUP",
                () -> containerInputClass.getField("PICKUP"));
            probe(failures, "ContainerInput.QUICK_MOVE",
                () -> containerInputClass.getField("QUICK_MOVE"));
        }

        // 4) The vanilla menu dispatch our ScreenHandlers override — if this
        //    signature drifts, the overrides stop intercepting and item
        //    movement in "virtual" menus is no longer blocked server-side.
        Class<?> menuClass = softClass("net.minecraft.world.inventory.AbstractContainerMenu");
        Class<?> playerClass = softClass("net.minecraft.world.entity.player.Player");
        if (menuClass != null && containerInputClass != null && playerClass != null) {
            probe(failures, "AbstractContainerMenu#clicked(int,int,ContainerInput,Player)",
                () -> menuClass.getDeclaredMethod("clicked",
                    int.class, int.class, containerInputClass, playerClass));
        }

        // 5) The anti-ghost resync entry point.
        if (menuClass != null) {
            probe(failures, "AbstractContainerMenu#broadcastFullState()",
                () -> menuClass.getDeclaredMethod("broadcastFullState"));
        }

        // 6) The mixin @Shadow fields.
        if (listenerClass != null) {
            probe(failures, "ServerGamePacketListenerImpl.player",
                () -> listenerClass.getDeclaredField("player"));
        }
        // containerMenu is DECLARED on Player and only INHERITED by
        // ServerPlayer — getDeclaredField cannot see inherited members, so
        // probing ServerPlayer for it always failed and wrongly disabled
        // the GUI layer on every 2.3.0/2.3.1 server (caught by
        // CompatDegradationTest, fixed 2.3.2). Probe the declaring class;
        // the bridge reaches the field through the very same Player type.
        if (playerClass != null) {
            probe(failures, "Player.containerMenu",
                () -> playerClass.getDeclaredField("containerMenu"));
        }
        if (menuClass != null) {
            probe(failures, "AbstractContainerMenu.containerId",
                () -> menuClass.getDeclaredField("containerId"));
        }

        // 7) WS-1 registry.api — the version-free resolution idiom Family A
        //    (Palette) rides on. This protects the PATTERN, not just one
        //    use of it: if Mojang moves Identifier/Registry/Holder again,
        //    the GUI layer must degrade here, not NPE inside a menu.
        probe(failures, "registry.api (Identifier.tryParse / Registry.get / Holder.value / BuiltInRegistries.ITEM)",
            CompatProbes::probeRegistryApi);

        // 8) WS-1 palette probes (Family A) — resolve the 17 palette ids the
        //    way Palette does, and pin the READY/NOT-READY distinction.
        //    Skipped gracefully when the item registry is not populated
        //    (plain unit-test JVM) — verified for real in the live-boot drill.
        reportPaletteProbes(failures);

        // 9) WS-1 drop.handle (Family B) — resolve the CompatDrop strategy
        //    ONCE, here, exactly as the spec pins it. Outcome lands in
        //    CompatState's DROP_STRATEGY surface (NOT in `failures`: a
        //    broken drop surface must not switch off GUIs — it fails
        //    closed per call site instead).
        try {
            CompatDrop.resolve();
        } catch (Throwable t) {
            SolidusMod.LOGGER.error("CompatDrop resolution threw unexpectedly: {}", t.toString(), t);
        }
        CompatState.DropStrategy resolvedStrategy;
        try {
            resolvedStrategy = CompatState.DropStrategy.valueOf(CompatDrop.strategy().name());
        } catch (IllegalArgumentException badMapping) {
            resolvedStrategy = CompatState.DropStrategy.UNRESOLVED;
        }
        CompatState.reportDropSurface(resolvedStrategy, CompatDrop.failureReason());
        if (CompatDrop.isBroken()) {
            SolidusMod.LOGGER.error(
                "Solidus: CompatDrop has NO drop strategy on this Minecraft version — world-drop "
                    + "recovery paths will REFUSE to hand out items (fail-closed, CRITICAL per call) "
                    + "and keep them in their persistent store instead. Reason: {}",
                CompatDrop.failureReason());
        }

        CompatState.report(failures);

        if (failures.isEmpty()) {
            SolidusMod.LOGGER.info(
                "Solidus compatibility probe: all checks passed — virtual GUI routing verified against this Minecraft version.");
        } else {
            // Loud, actionable, and explicitly scoped: the server owner sees
            // WHAT broke, WHAT still works, and WHAT to do next.
            SolidusMod.LOGGER.error(
                "┌────────────────────────────────────────────────────────────────────────────┐");
            SolidusMod.LOGGER.error(
                "│ Solidus: virtual GUI layer DISABLED (Minecraft internals changed).        │");
            SolidusMod.LOGGER.error(
                "│ Failed compatibility probes:                                             │");
            for (String failure : failures) {
                SolidusMod.LOGGER.error("│   - {}", failure);
            }
            SolidusMod.LOGGER.error(
                "│ Still running: /pay, /balance, /baltop, /transactions, storage, ledger,   │");
            SolidusMod.LOGGER.error(
                "│ taxes and all companion enforcement (no data was lost).                   │");
            SolidusMod.LOGGER.error(
                "│ Fix: run a Solidus build matching this Minecraft version, or see          │");
            SolidusMod.LOGGER.error(
                "│ src/main/java/com/solidus/compat/PACKAGE.md for the update checklist.    │");
            SolidusMod.LOGGER.error(
                "└────────────────────────────────────────────────────────────────────────────┘");
        }
    }

    // -- helpers --------------------------------------------

    /**
     * WS-1: verifies the registry API the Palette pattern is built on —
     * reflection only, so the probe itself never touches Minecraft statics
     * that would trigger a bootstrap in a unit-test JVM.
     */
    private static void probeRegistryApi() throws Throwable {
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false,
            CompatProbes.class.getClassLoader());
        identifier.getDeclaredMethod("tryParse", String.class);

        Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false,
            CompatProbes.class.getClassLoader());
        registries.getField("ITEM");

        Class<?> registry = Class.forName("net.minecraft.core.Registry", false,
            CompatProbes.class.getClassLoader());
        // The single-parameter lookup overload is named `get` in every 26.x
        // jar we measured, but its erasure drifted between (Object) and
        // (Identifier) — accept either, require one of them.
        boolean hasSingleArgGet = false;
        for (Method m : registry.getDeclaredMethods()) {
            if (m.getName().equals("get") && m.getParameterCount() == 1) {
                Class<?> p = m.getParameterTypes()[0];
                if (p == Object.class || p == identifier) {
                    hasSingleArgGet = true;
                    break;
                }
            }
        }
        if (!hasSingleArgGet) {
            throw new NoSuchMethodException(
                "Registry.get(Identifier|Object) — the single-arg key lookup is gone");
        }

        Class<?> holder = Class.forName("net.minecraft.core.Holder", false,
            CompatProbes.class.getClassLoader());
        holder.getDeclaredMethod("value");
    }

    /**
     * WS-1: palette.ids + palette.distinct. A registry that is not
     * populated (plain unit-test JVM) is a SKIP, not a failure — the
     * live-boot drill asserts PALETTE_EXACT for real. A missing SUBSET of
     * ids degrades visually without blocking GUIs (flag + admin report);
     * only a broken registry API (throw inside the probe) lands in
     * {@code failures} and blocks the GUI layer.
     */
    private static void reportPaletteProbes(List<String> failures) {
        try {
            // Palette-free canary (pure reflection): is the vanilla item
            // registry populated? Touching Palette itself would resolve its
            // fields — against an EMPTY registry that falls all the way to
            // Items.BARRIER and triggers a full bootstrap in a unit-test
            // JVM (the exact trap ShopGUILayout's lazy arrays avoid).
            if (!itemRegistryPopulated()) {
                CompatState.reportPaletteSurface(false, false, java.util.List.of(
                    "item registry not populated at probe time — palette is asserted "
                        + "for real in the live-boot drill (compat/PACKAGE.md)"));
                return;
            }
            java.util.List<String> missing = Palette.probeMissingIds();
            boolean distinct = Palette.probeDistinctReadyIndicators();
            boolean exact = Palette.isExact() && missing.isEmpty() && distinct;
            java.util.List<String> notes = new ArrayList<>(Palette.degradationNotes());
            if (!missing.isEmpty()) {
                notes.add("unresolved palette ids: " + String.join(", ", missing));
                SolidusMod.LOGGER.warn(
                    "Solidus Palette degraded: {} palette id(s) unresolved even via fallbacks: {}",
                    missing.size(), String.join(", ", missing));
            }
            if (!distinct) {
                notes.add("LIME_DYE and GRAY_DYE collapsed to the same item — "
                    + "READY / NOT-READY indicators are visually identical (GUIs stay open)");
                SolidusMod.LOGGER.warn(
                    "Solidus Palette: READY/NOT-READY indicator dyes collapsed to one item "
                        + "— trade readiness must be re-checked visually.");
            }
            CompatState.reportPaletteSurface(exact, !exact, notes);
        } catch (Throwable t) {
            // The registry API itself is unusable — Family A cannot render
            // GUIs. Fail closed for the GUI layer via the failures list.
            // Report the FULL cause chain: an ExceptionInInitializerError
            // without its cause names the symptom, not the broken clinit
            // (the live-boot drill needed the stack to diagnose 26.3).
            StringBuilder chain = new StringBuilder(t.toString());
            for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
                chain.append(" | caused by ").append(c);
            }
            failures.add("palette.registry (BuiltInRegistries.ITEM usable for Palette ids) -> "
                + chain);
            SolidusMod.LOGGER.error(
                "Solidus: palette registry probe threw — full stack for the update checklist:", t);
            CompatState.reportPaletteSurface(false, true,
                java.util.List.of("palette registry probe threw: " + chain));
        }
    }

    /**
     * WS-1 canary — is the vanilla item registry populated? Deliberately
     * implemented HERE (not on Palette) and with a direct registry lookup:
     * it neither touches {@code Palette} (whose clinit resolves the 17
     * fields) nor {@code Items} (whose clinit IS the bootstrap), so in a
     * plain unit-test JVM it returns false and the palette probes skip
     * gracefully.
     */
    private static boolean itemRegistryPopulated() {
        try {
            return BuiltInRegistries.ITEM.get(Identifier.tryParse("air")).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Loads a class without initializing it; null (and a debug log) on failure. */
    private static Class<?> softClass(String name) {
        try {
            return Class.forName(name, false,
                CompatProbes.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Finds the ContainerInput type by reading the return type of the
     * packet's {@code containerInput()} accessor — the type itself is
     * referenced only through the packet, so we never hard-code its
     * location twice.
     */
    private static Class<?> resolveContainerInputClass(Class<?> packetClass) {
        if (packetClass == null) {
            return null;
        }
        try {
            Method accessor = packetClass.getDeclaredMethod("containerInput");
            return accessor.getReturnType();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Runs one probe, appending a human-readable failure on error. */
    private static void probe(List<String> failures, String what, Probe probe) {
        try {
            probe.run();
        } catch (Throwable t) {
            failures.add(what + " -> " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    @FunctionalInterface
    private interface Probe {
        void run() throws Throwable;
    }
}
