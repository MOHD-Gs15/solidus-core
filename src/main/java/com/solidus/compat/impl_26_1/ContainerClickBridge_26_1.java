package com.solidus.compat.impl_26_1;

import com.solidus.SolidusMod;
import com.solidus.compat.ClickDescriptor;
import com.solidus.compat.ClickInputResolver;
import com.solidus.compat.ContainerClickBridge;
import com.solidus.networking.PacketHandler;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * ContainerClickBridge_26_1 — the Minecraft 26.1.x implementation of the
 * container-click seam.
 *
 * <p><b>This file is the entire point of the compat architecture:</b> every
 * line below this javadoc is allowed to know 26.1-specific shapes —
 * the {@code ServerboundContainerClickPacket} record accessors
 * ({@code slotNum()}, {@code buttonNum()}, {@code containerInput()}) and
 * the {@code ContainerInput} constants. When Minecraft 26.2+ changes any
 * of them, the update is a NEW sibling package ({@code impl_26_2}) plus a
 * selection flip in {@link com.solidus.compat.Compat} and a probe update —
 * never a diff scattered across the shop/auction/sell/trade handlers.</p>
 *
 * <p>Routing logic (rate limiting, scope check, resync policy) stays in
 * the version-free {@link PacketHandler#handleContainerClick(ServerPlayer,
 * ClickDescriptor)}; this class only extracts and normalize.</p>
 *
 * <h3>26.1.x accessor notes (verified via javap against the 26.1.2
 * unobfuscated jar):</h3>
 * <ul>
 *   <li>{@code ServerboundContainerClickPacket} is a record:
 *       {@code slotNum()}, {@code buttonNum()}, {@code containerInput()}
 *       — no {@code get} prefix.</li>
 *   <li>{@code ContainerInput} replaces the legacy ClickType+buttonNum
 *       pair; the physical button is still carried by {@code buttonNum()}
 *       (0 = left, 1 = right).</li>
 *   <li>{@code QUICK_MOVE} marks shift-click intent; {@code PICKUP} marks
 *       regular clicks.</li>
 * </ul>
 *
 * @since 2.3.0
 */
public final class ContainerClickBridge_26_1 implements ContainerClickBridge, ClickInputResolver {

    @Override
    public String minecraftFamily() {
        return "26.1";
    }

    // -- ContainerClickBridge --------------------------------

    @Override
    public boolean isSolidusMenu(ServerPlayer player, int containerId) {
        AbstractContainerMenu menu = player.containerMenu;
        // The menu id must match the open menu AND be one of ours; the
        // instanceof check alone would mis-route a stale-id packet onto
        // whichever Solidus menu is currently open (audit 2.1.3).
        return menu != null
            && menu.containerId == containerId
            && isSolidusHandler(menu);
    }

    @Override
    public boolean route(ServerPlayer player, Object rawPacket) {
        if (!(rawPacket instanceof ServerboundContainerClickPacket packet)) {
            return false;
        }

        PacketHandler packetHandler = SolidusMod.getPacketHandler();
        if (packetHandler == null) {
            return false;
        }

        // Defense-in-depth (audit 2.1.3): vanilla's handleContainerClick
        // validates the packet's containerId against the open menu BEFORE
        // acting. Running at HEAD bypasses that guard, letting a stale
        // containerId click land on whatever Solidus menu is currently open.
        // Restore the check here so routing is strictly desync-safe.
        AbstractContainerMenu currentMenu = player.containerMenu;
        if (currentMenu == null || packet.containerId() != currentMenu.containerId) {
            return false;
        }

        // Extract and normalize the click (26.1 record accessors).
        int slotIndex = packet.slotNum();
        int button = packet.buttonNum();
        ContainerInput containerInput = packet.containerInput();
        ClickDescriptor click = ClickDescriptor.of(slotIndex, button, resolve(containerInput));

        // Version-free routing: scope check (Solidus menus only), rate
        // limiting, handler dispatch and the anti-ghost resync policy.
        return packetHandler.handleContainerClick(player, click);
    }

    // -- ClickInputResolver ----------------------------------

    @Override
    public ClickDescriptor.Input resolve(Object rawContainerInput) {
        if (rawContainerInput instanceof ContainerInput input) {
            if (input == ContainerInput.QUICK_MOVE) {
                return ClickDescriptor.Input.QUICK_MOVE;
            }
            if (input == ContainerInput.PICKUP) {
                return ClickDescriptor.Input.PICKUP;
            }
        }
        return ClickDescriptor.Input.OTHER;
    }

    // -- helpers ----------------------------------------------

    private static boolean isSolidusHandler(AbstractContainerMenu menu) {
        return menu instanceof com.solidus.shop.ShopScreenHandler
            || menu instanceof com.solidus.sell.SellScreenHandler
            || menu instanceof com.solidus.auction.AuctionScreenHandler
            || menu instanceof com.solidus.trade.TradeScreenHandler;
    }
}
