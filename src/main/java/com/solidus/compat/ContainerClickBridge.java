package com.solidus.compat;

import net.minecraft.server.level.ServerPlayer;

/**
 * ContainerClickBridge — the stable seam between Minecraft's container
 * click pipeline and Solidus's virtual GUI routing.
 *
 * <p><b>Why (audit W-3 / adapter layer):</b> the mixin on
 * {@code ServerGamePacketListenerImpl#handleContainerClick} is the mod's
 * highest-churn, highest-blast-radius hook. This interface keeps the
 * mixin itself a dumb shell: it captures the packet and delegates here.
 * Version-specific knowledge (packet accessors, ContainerInput, the
 * containerId desync guard interplay) lives exclusively in the
 * {@code impl_26_1} implementation package.</p>
 *
 * <p>On a Minecraft update that reshapes the click pipeline, the changes
 * are confined to one implementation class — the bridge interface, the
 * mixin shell's {@code @Inject} target string and the probes stay
 * as-is.</p>
 *
 * <h3>Contract:</h3>
 * <ul>
 *   <li>{@link #route} must be safe to call on the network thread for any
 *       container click — including clicks on vanilla menus (returned
 *       unconsumed) and packets with a stale containerId (dropped).</li>
 *   <li>Implementations must NOT throw; a bridge that cannot process a
 *       packet returns {@code false} and lets vanilla handle it.</li>
 *   <li>The routing decision includes the rate limiter: clicks on Solidus
 *       menus that exceed the cooldown are consumed-and-dropped, exactly
 *       like the pre-2.3.0 PacketHandler behavior.</li>
 * </ul>
 *
 * @since 2.3.0
 */
public interface ContainerClickBridge {

    /**
     * @return the Minecraft version family this bridge handles
     *         (e.g. {@code "26.1"})
     */
    String minecraftFamily();

    /**
     * Checks whether the player currently has a Solidus virtual menu open
     * with the given container id. Used by the mixin's desync guard.
     *
     * @param player      the clicking player
     * @param containerId the packet's container id
     * @return true when the open menu is a Solidus menu with that id
     */
    boolean isSolidusMenu(ServerPlayer player, int containerId);

    /**
     * Routes one raw container-click packet.
     *
     * <p>The packet is {@code Object} on the stable interface so the
     * signature survives Minecraft changing the concrete packet type;
     * implementations cast to their family's packet class.</p>
     *
     * @param player   the clicking player (from the listener's shadow field)
     * @param rawPacket the raw ServerboundContainerClickPacket
     * @return true when the click was consumed (handled or rate-limit
     *         dropped) and vanilla processing must be cancelled; false to
     *         pass through to vanilla
     */
    boolean route(ServerPlayer player, Object rawPacket);
}
