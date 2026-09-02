package com.solidus.mixin;

import com.solidus.SolidusMod;
import com.solidus.networking.PacketHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerInput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ServerPlayerEntity Mixin - Hooks into the player's network connection
 * to intercept container click packets for virtual GUI processing.
 *
 * This mixin intercepts the handleContainerClick method on the server-side
 * packet listener. When a player clicks in any container, we check if it's
 * a Solidus virtual GUI (Shop or Auction) and route the click through our
 * custom handling pipeline with rate limiting.
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
 * Ghost Item Prevention:
 * When the mixin cancels a container click packet on the server side, the
 * client does not immediately know about the cancellation due to network
 * latency (ping). The client menu is a vanilla ChestMenu (GENERIC_9x6) that
 * optimistically predicts every click locally, including picking up display
 * items for free. This causes "ghost items" - items that exist only in the
 * client's inventory prediction while the server never moved anything.
 *
 * THE FIX: after canceling, we force a FULL container resync via
 * broadcastFullState(), which re-sends every slot (display slots AND the
 * player inventory section) plus the carried stack and resets the incremental
 * sync markers. Any phantom item is erased in the same moment it was created.
 *
 * Why broadcastChanges() was NOT enough (the 2.1.0 bug):
 * broadcastChanges() only sends slots whose server-side state CHANGED since
 * the last sync. When we REJECT a click, nothing changed on the server - so
 * nothing is sent - and the client's ghost prediction survives until the
 * next full sync (typically reopening the GUI). broadcastFullState() always
 * sends the complete state, which is exactly what a rejected click needs.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayerEntityMixin {

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
     * CRITICAL: After canceling a packet, we MUST call broadcastChanges()
     * to force the client to resync with the server's container state.
     * Without this, ghost items appear due to the client-server state
     * mismatch caused by network latency.
     *
     * Accessor Compatibility Note:
     * ServerboundContainerClickPacket is a Record class in Minecraft 26.1.x,
     * using record-style accessors (slotNum(), containerInput() - no get prefix).
     *
     * In 26.1.x, ServerboundContainerClickPacket uses ContainerInput
     * instead of ClickType + separate buttonNum(). The button info is
     * partially absorbed into the ContainerInput, but buttonNum() still
     * carries the physical button (0 = left, 1 = right) - verified against
     * the 26.1.2 mapped jar via javap.
     */
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void onContainerClick(
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet,
        CallbackInfo ci) {

        PacketHandler packetHandler = SolidusMod.getPacketHandler();
        if (packetHandler == null) return;

        // Extract click data from the packet (accessors verified via javap
        // against the 26.1.2 mojmap-mapped jar)
        int slotIndex = packet.slotNum();
        int button = packet.buttonNum();
        ContainerInput containerInput = packet.containerInput();

        // Check if this is a Solidus GUI click
        boolean handled = packetHandler.handleContainerClick(
            player, slotIndex, button, containerInput);

        if (handled) {
            // Cancel vanilla processing - the click has been handled by Solidus
            ci.cancel();

            // FORCE FULL RESYNC: the client menu is a vanilla ChestMenu that
            // optimistically predicted this click locally (possibly moving a
            // display item into its inventory view for free). Since we rejected
            // or reinterpreted the click, the server state is the single source
            // of truth again - broadcastFullState() re-sends EVERY slot (display
            // area + player inventory + carried stack) and resets the incremental
            // sync markers, so any phantom/ghost item is erased immediately.
            // (broadcastChanges() was insufficient here: it only sends slots that
            // CHANGED server-side, and a rejected click changes nothing.)
            player.containerMenu.broadcastFullState();
        }
    }
}
