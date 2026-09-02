package com.solidus.networking;

import com.solidus.SolidusMod;
import com.solidus.shop.ShopScreenHandler;
import com.solidus.sell.SellScreenHandler;
import com.solidus.auction.AuctionScreenHandler;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.UUID;

/**
 * Packet Handler - Intercepts and processes network packets for virtual GUIs.
 *
 * Architecture:
 * The mod hooks deeply into ServerPlayerEntity network connection filters to
 * catch incoming interaction packets (ServerboundContainerClickPacket). If the
 * screen ID matches the virtual shop/auction, the click is analyzed, processed
 * financially, and canceled visually to ensure the shop layout remains static.
 *
 * Security Layer:
 * - All clicks pass through the RateLimiter before processing
 * - Clicks on virtual container slots (0-53) are intercepted and handled
 *   by the appropriate ScreenHandler (ShopScreenHandler or AuctionScreenHandler)
 * - The ScreenHandlers already block all item movement by overriding clicked()
 *
 * Player Disconnect Handling:
 * - When a player disconnects, their rate limiter entry is cleaned up
 *   to prevent memory leaks
 */
public class PacketHandler {

    private final com.solidus.shop.ShopManager shopManager;
    private final com.solidus.auction.AuctionManager auctionManager;
    private final RateLimiter rateLimiter;

    public PacketHandler(com.solidus.shop.ShopManager shopManager,
                          com.solidus.auction.AuctionManager auctionManager,
                          RateLimiter rateLimiter) {
        this.shopManager = shopManager;
        this.auctionManager = auctionManager;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Registers all packet handling hooks and event listeners.
     * Called during mod initialization.
     */
    public void register() {
        // Register player disconnect handler for rate limiter + pending-transaction cleanup
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerUuid = handler.getPlayer().getUUID();
            rateLimiter.removePlayer(playerUuid);

            // Release any pending buy/sell locks so the player can transact
            // normally when they reconnect. Without this, a disconnect mid-transaction
            // would leave the player permanently locked out of the shop.
            if (shopManager != null) {
                shopManager.clearPendingTransactions(playerUuid);
            }

            // Sell GUI item recovery (2.1.2): vanilla never invokes
            // AbstractContainerMenu.removed() for a menu left open at
            // disconnect, so any items the player had already placed in the
            // sell input area (slots 9-53) would be silently LOST together
            // with the discarded SellContainer. This event fires on the
            // server thread BEFORE PlayerList.save() serializes the player,
            // so running the standard removed() processing here (sell
            // sellables, return the rest) is persisted correctly.
            // removed() empties the container, so a later invocation (if any)
            // is a harmless no-op - no double-payout / dupe risk.
            ServerPlayer disconnectedPlayer = handler.getPlayer();
            if (disconnectedPlayer.containerMenu instanceof SellScreenHandler sellHandler) {
                SolidusMod.LOGGER.info(
                    "Player {} disconnected with the sell GUI open - processing placed items now.",
                    disconnectedPlayer.getName().getString());
                sellHandler.removed(disconnectedPlayer);
            }

            SolidusMod.LOGGER.debug("Cleaned up rate limiter + pending transactions for disconnected player: {}",
                handler.getPlayer().getName().getString());
        });

        SolidusMod.LOGGER.info("Packet handler registered. Rate limiter active ({}ms cooldown).",
            RateLimiter.MIN_CLICK_INTERVAL_MS);
    }

    /**
     * Processes an incoming container click packet.
     * Called by the ServerPlayerEntityMixin when a click packet is received.
     *
     * This method acts as the gateway between raw network packets and the
     * high-level ScreenHandler click processing. It adds the rate limiting
     * layer and routes the click to the appropriate handler.
     *
     * @param player    The player who clicked
     * @param slotIndex The slot index that was clicked
     * @param button    The button used (0=left, 1=right)
     * @param containerInput The container input (replaces ClickType in 26.1.x)
     * @return true if the click was processed by a Solidus handler,
     *         false if it should be passed through to vanilla handling
     */
    public boolean handleContainerClick(ServerPlayer player, int slotIndex,
                                          int button, net.minecraft.world.inventory.ContainerInput containerInput) {
        // Rate limit check
        if (!rateLimiter.allowClick(player.getUUID())) {
            // Click came too fast - silently drop the packet
            SolidusMod.LOGGER.debug("Rate-limited click from player: {} (remaining: {}ms)",
                player.getName().getString(), rateLimiter.getRemainingCooldown(player.getUUID()));
            return true; // Consume the packet - don't pass to vanilla
        }

        // Check if the player has a Solidus screen handler open
        AbstractContainerMenu currentMenu = player.containerMenu;

        if (currentMenu instanceof ShopScreenHandler shopHandler) {
            // Route to shop click handler
            shopHandler.clicked(slotIndex, button, containerInput, player);
            return true;
        }

        if (currentMenu instanceof SellScreenHandler sellHandler) {
            // Route to sell click handler - all clicks are handled manually
            // because the sell GUI allows item placement
            sellHandler.clicked(slotIndex, button, containerInput, player);
            return true;
        }

        if (currentMenu instanceof AuctionScreenHandler auctionHandler) {
            // Route to auction click handler
            auctionHandler.clicked(slotIndex, button, containerInput, player);
            return true;
        }

        // Not a Solidus GUI - pass through to vanilla handling
        return false;
    }

    /**
     * Checks if a player currently has a Solidus virtual GUI open.
     *
     * @param player The player to check
     * @return true if the player has a Shop or Auction screen open
     */
    public boolean hasSolidusScreenOpen(ServerPlayer player) {
        AbstractContainerMenu currentMenu = player.containerMenu;
        return currentMenu instanceof ShopScreenHandler
            || currentMenu instanceof SellScreenHandler
            || currentMenu instanceof AuctionScreenHandler;
    }
}
