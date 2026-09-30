package com.solidus.compat;

import com.solidus.SolidusMod;
import net.minecraft.server.level.ServerPlayer;

/**
 * Compat — the one facade the rest of the mod talks to about version
 * compatibility. Nothing outside the compat package (except the mixin
 * shell) may reference Minecraft's click-input types.
 *
 * <p>Three jobs:</p>
 * <ol>
 *   <li>Hold the selected {@link ContainerClickBridge} /
 *       {@link ClickInputResolver} for the running Minecraft family.</li>
 *   <li>Expose the {@link #verifyCompatibility()} entry point called once
 *       at mod initialization.</li>
 *   <li>Provide the GUI gate {@link #ensureGuiAvailable(ServerPlayer)}
 *       used by every menu-open path so a broken compat surface produces
 *       a clear player message instead of a broken menu (or a crash).</li>
 * </ol>
 *
 * <p>Selection policy today: the 26.1 implementation is the only one, and
 * it is selected only when {@link CompatState#allProbesPassed()} is true.
 * When a future family lands (26.2+), selection becomes a probe-driven
 * decision — try the newest implementation whose probes pass, fall back
 * to older ones, and expose the outcome through {@link CompatState}.</p>
 *
 * @since 2.3.0
 */
public final class Compat {

    private static volatile ClickInputResolver inputResolver;
    private static volatile ContainerClickBridge clickBridge;

    /** Rate limit for the player-facing "GUI disabled" chatter (once per minute per player). */
    private static final long GUI_DISABLED_MESSAGE_COOLDOWN_MS = 60_000;
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>
        LAST_GUI_DISABLED_NOTICE = new java.util.concurrent.ConcurrentHashMap<>();

    private Compat() {
    }

    // -- Initialization --------------------------------------

    /**
     * Runs the compatibility probes and wires the version-specific
     * implementations. Called once from SolidusMod initialization, before
     * any GUI can open. Never throws: a probe failure degrades the GUI
     * layer while the rest of the mod keeps running.
     */
    public static void verifyCompatibility() {
        CompatProbes.verifyCompatibility();

        if (CompatState.allProbesPassed()) {
            com.solidus.compat.impl_26_1.ContainerClickBridge_26_1 bridge =
                new com.solidus.compat.impl_26_1.ContainerClickBridge_26_1();
            clickBridge = bridge;
            inputResolver = bridge;
            SolidusMod.LOGGER.info(
                "Compat: container-click bridge for Minecraft {} selected.",
                bridge.minecraftFamily());
        } else {
            // Leave implementations null: routes below degrade to pass-through
            // and GUI opens are blocked by ensureGuiAvailable.
            clickBridge = null;
            inputResolver = null;
        }
    }

    // -- Click routing ---------------------------------------

    /**
     * Entry used by the mixin shell. Degrades to "pass through to vanilla"
     * (returns false) whenever the compat surface is not fully verified —
     * no Solidus menu can be open in that state, because all open paths
     * are gated by {@link #ensureGuiAvailable}.
     *
     * @param player   the clicking player
     * @param rawPacket the raw container-click packet
     * @return true when consumed (vanilla handling must be cancelled)
     */
    public static boolean routeContainerClick(ServerPlayer player, Object rawPacket) {
        ContainerClickBridge bridge = clickBridge;
        if (bridge == null || !CompatState.isClickRoutingAvailable()) {
            return false;
        }
        try {
            return bridge.route(player, rawPacket);
        } catch (Throwable t) {
            // The bridge contract says implementations never throw, but a
            // defensive catch here guarantees a broken bridge can at worst
            // pass clicks through to vanilla — never take down the network
            // thread.
            SolidusMod.LOGGER.error(
                "Compat: container-click bridge threw — passing the click to vanilla. {}", t.toString(), t);
            return false;
        }
    }

    /**
     * Normalizes a raw Minecraft click input into stable semantics.
     * Callers outside compat should only need this inside their
     * {@code clicked(int,int,<versionInput>,Player)} overrides — the one
     * place the vanilla dispatch signature forces the version type onto
     * a ScreenHandler.
     *
     * @param rawContainerInput the version-specific input object (nullable)
     * @return stable input semantics (OTHER when unavailable/unknown)
     */
    public static ClickDescriptor.Input resolveInput(Object rawContainerInput) {
        ClickInputResolver resolver = inputResolver;
        if (resolver == null) {
            return ClickDescriptor.Input.OTHER;
        }
        try {
            ClickDescriptor.Input resolved = resolver.resolve(rawContainerInput);
            return resolved != null ? resolved : ClickDescriptor.Input.OTHER;
        } catch (Throwable t) {
            return ClickDescriptor.Input.OTHER;
        }
    }

    /**
     * Convenience for ScreenHandlers: build a stable descriptor from the
     * raw override parameters in one line.
     *
     * @param slotIndex the clicked slot
     * @param button    the physical button
     * @param rawContainerInput the version-specific input object (nullable)
     * @return an immutable descriptor
     */
    public static ClickDescriptor describe(int slotIndex, int button, Object rawContainerInput) {
        return ClickDescriptor.of(slotIndex, button, resolveInput(rawContainerInput));
    }

    /**
     * Cross-check wired into the first player JOIN event: by that moment
     * the packet-listener class has necessarily been loaded and Mixin has
     * either applied ContainerClickMixin (SolidusMixinPlugin saw the
     * postApply event) or definitively failed to (require=0 logged a
     * warning). If the probes passed but the apply never happened, the GUI
     * layer is downgraded before any player has had a chance to open a
     * broken menu.
     */
    public static void confirmClickMixinAfterFirstConnection() {
        if (CompatState.allProbesPassed()
            && !SolidusMixinPlugin.isContainerClickMixinApplied()) {
            CompatState.downgrade(
                "ContainerClickMixin did not apply to its target (see Mixin warnings in the log "
                    + "above) - virtual GUI routing cannot be trusted");
            SolidusMod.LOGGER.error(
                "Solidus: container-click mixin did not apply even though compatibility probes "
                    + "passed. Virtual GUIs are DISABLED for this session; commands, storage and "
                    + "ledger continue to work. Check for a conflicting mod transformer on "
                    + "ServerGamePacketListenerImpl.");
        }
    }

    // -- GUI gate --------------------------------------------

    /**
     * The gate every virtual-menu open path must pass. When compatibility
     * is broken it sends the player one clear, cooldown-limited message
     * (and logs the state once) and returns false.
     *
     * @param player the player asking for a Solidus menu
     * @return true when the menu may open
     */
    public static boolean ensureGuiAvailable(ServerPlayer player) {
        if (CompatState.isGuiSafe()) {
            return true;
        }
        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = LAST_GUI_DISABLED_NOTICE.get(player.getUUID());
            if (last == null || now - last >= GUI_DISABLED_MESSAGE_COOLDOWN_MS) {
                LAST_GUI_DISABLED_NOTICE.put(player.getUUID(), now);
                player.sendSystemMessage(com.solidus.util.TextUtil.error(
                    "[Solidus] Virtual menus are temporarily disabled on this server "
                        + "(Minecraft version changed - server owner must update Solidus). "
                        + "Commands like /pay, /balance, /baltop still work."));
            }
        }
        return false;
    }
}
