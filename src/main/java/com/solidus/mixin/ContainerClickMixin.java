package com.solidus.mixin;

import com.solidus.compat.Compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ContainerClickMixin — hooks the server-side packet listener to intercept
 * container click packets for virtual GUI processing.
 *
 * <p>(Renamed from ServerPlayerEntityMixin in 2.3.0 — the target is the
 * packet listener, not the player.)</p>
 *
 * This mixin intercepts the handleContainerClick method. When a player
 * clicks in any container, we check if it's a Solidus virtual GUI (Shop,
 * Sell, Auction, Trade) and route the click through our custom handling
 * pipeline with rate limiting.
 *
 * Defense-in-Depth Strategy:
 * - Primary defense: ShopScreenHandler and AuctionScreenHandler override
 *   clicked() and quickMoveStack() to block all item movement
 * - Secondary defense: This mixin intercepts packets before they reach
 *   the vanilla handler, adding rate limiting
 * - The abstract quickMoveStack is NOT targeted here (it cannot be injected
 *   into since it has no method body). Instead, the concrete overrides in
 *   our ScreenHandlers provide the protection.
 *
 * ─────────────────────────────────────────────────────────────────────
 * UPDATE RESILIENCE (audit W-3 fix, family 2.3.0):
 *
 *   This injector declares require = 0. When a future Minecraft version
 *   changes the shape of handleContainerClick (as 26.1.x already did when
 *   ClickType+buttonNum became the ContainerInput record), the injection
 *   logs a WARNING and is skipped — the server still boots.
 *
 *   The safety net is layered:
 *   1. com.solidus.compat.CompatProbes reflectively verifies every
 *      Minecraft shape this pipeline compiled against at startup, and
 *      com.solidus.compat.CompatState turns the report into capability
 *      flags.
 *   2. When a probe fails, ALL virtual GUI open paths are blocked with a
 *      clear message (Compat.ensureGuiAvailable) — commands, storage, the
 *      ledger and companion enforcement keep working.
 *   3. Compat.routeContainerClick passes clicks through to vanilla when
 *      the compat surface is not fully verified, so a half-applied state
 *      can never leave a Solidus menu open with un-routed clicks.
 *
 *   Every version-specific detail (packet accessors, ContainerInput) lives
 *   in com.solidus.compat.impl_26_1 — see compat/PACKAGE.md for the
 *   Minecraft-update checklist.
 * ─────────────────────────────────────────────────────────────────────
 *
 * Ghost Item Prevention:
 * When the mixin cancels a container click packet on the server side, the
 * client does not immediately know about the cancellation due to network
 * latency (ping). The client menu is a vanilla ChestMenu (GENERIC_9x6) that
 * optimistically predicts every click locally, including picking up display
 * items for free. This causes "ghost items" - items that exist only in the
 * client's inventory prediction while the server never moved anything.
 *
 * THE FIX: after canceling, PacketHandler forces a FULL container resync via
 * broadcastFullState() (re-sends every slot plus the carried stack and resets
 * the incremental sync markers) after every PROCESSED Solidus click. For
 * clicks DROPPED by the rate limiter the resync is throttled to at most one
 * per 200ms so a flooded packet stream cannot amplify into a stream of
 * multi-KB broadcasts. See PacketHandler for the full policy.
 *
 * Why broadcastChanges() was NOT enough (the 2.1.0 bug):
 * broadcastChanges() only sends slots whose server-side state CHANGED since
 * the last sync. When we REJECT a click, nothing changed on the server - so
 * nothing is sent - and the client's ghost prediction survives until the
 * next full sync (typically reopening the GUI). broadcastFullState() always
 * sends the complete state, which is exactly what a rejected click needs.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Intercepts container click packets before vanilla processing.
     *
     * If the player has a Solidus virtual GUI open, the click is processed
     * by the PacketHandler (which applies rate limiting and routes to the
     * appropriate ScreenHandler), and the vanilla handling is cancelled.
     *
     * If the player is using a normal vanilla container, the click is
     * passed through unchanged.
     *
     * require = 0: a missing/reshaped target method on a newer Minecraft
     * logs a warning instead of crashing the server (see class javadoc).
     */
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true, require = 0)
    private void onContainerClick(
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet,
        CallbackInfo ci) {

        boolean handled = Compat.routeContainerClick(player, packet);

        if (handled) {
            // Cancel vanilla processing - the click has been handled (or
            // rate-limited and dropped) by Solidus.
            ci.cancel();
        }
    }
}
