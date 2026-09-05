package com.solidus.networking;

import com.solidus.SolidusMod;
import com.solidus.shop.ShopScreenHandler;
import com.solidus.sell.SellScreenHandler;
import com.solidus.auction.AuctionScreenHandler;
import com.solidus.trade.TradeManager;
import com.solidus.trade.TradeScreenHandler;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet Handler - Intercepts and processes network packets for virtual GUIs.
 *
 * Architecture:
 * The mod hooks deeply into ServerPlayerEntity network connection filters to
 * catch incoming interaction packets (ServerboundContainerClickPacket). If the
 * screen ID matches the virtual shop/auction, the click is analyzed, processed
 * financially, and canceled visually to ensure the shop layout remains static.
 *
 * Security Layer (audit 2.1.3 restructure):
 * - The menu-type check runs FIRST: the rate limiter only applies to Solidus
 *   virtual GUIs. Previously it consumed EVERY container click (including
 *   vanilla chests and the player's own inventory), silently dropping
 *   legitimate vanilla interactions at >6.6 clicks/s.
 * - Clicks on virtual container slots are intercepted and handled by the
 *   appropriate ScreenHandler (ShopScreenHandler or AuctionScreenHandler)
 * - The ScreenHandlers already block all item movement by overriding clicked()
 *
 * Amplification guard:
 * - Every PROCESSED Solidus click is followed by broadcastFullState() (the
 *   anti-ghost-item guarantee from PR#13).
 * - Every DROPPED (rate-limited) click is followed by at most ONE throttled
 *   full resync per DROP_RESYNC_INTERVAL_MS. Previously each flooded ~15-byte
 *   click packet produced a full multi-KB container broadcast, letting a
 *   vanilla-protocol bot amplify its traffic orders of magnitude.
 *
 * Player Disconnect Handling:
 * - When a player disconnects, their rate limiter entry and drop-resync
 *   bookkeeping are cleaned up to prevent memory leaks
 */
public class PacketHandler {

    private final com.solidus.shop.ShopManager shopManager;
    private final com.solidus.auction.AuctionManager auctionManager;
    private final TradeManager tradeManager;
    private final RateLimiter rateLimiter;

    /** Per-player timestamp of the last full-resync sent for a DROPPED click. */
    private final ConcurrentHashMap<UUID, Long> lastDropResyncMs = new ConcurrentHashMap<>();

    /** Minimum spacing between full resyncs caused by rate-limited clicks. */
    private static final long DROP_RESYNC_INTERVAL_MS = 200;

    public PacketHandler(com.solidus.shop.ShopManager shopManager,
                          com.solidus.auction.AuctionManager auctionManager,
                          TradeManager tradeManager,
                          RateLimiter rateLimiter) {
        this.shopManager = shopManager;
        this.auctionManager = auctionManager;
        this.tradeManager = tradeManager;
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
            lastDropResyncMs.remove(playerUuid);

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

        SolidusMod.LOGGER.info("Packet handler registered. Rate limiter active ({}ms cooldown, Solidus GUIs only).",
            RateLimiter.MIN_CLICK_INTERVAL_MS);
    }

    /**
     * Processes an incoming container click packet.
     * Called by the ServerPlayerEntityMixin when a click packet is received.
     *
     * This method acts as the gateway between raw network packets and the
     * high-level ScreenHandler click processing. It routes the click to the
     * appropriate handler and applies the anti-ghost-item resync policy:
     * a full container broadcast after every processed Solidus click, and a
     * throttled broadcast (at most one per {@link #DROP_RESYNC_INTERVAL_MS})
     * for clicks dropped by the rate limiter.
     *
     * @param player    The player who clicked
     * @param slotIndex The slot index that was clicked
     * @param button    The button used (0=left, 1=right)
     * @param containerInput The container input (replaces ClickType in 26.1.x)
     * @return true if the click was consumed by Solidus (processed or dropped
     *         by the rate limiter) and vanilla handling must be cancelled,
     *         false if it should be passed through to vanilla handling
     */
    public boolean handleContainerClick(ServerPlayer player, int slotIndex,
                                          int button, net.minecraft.world.inventory.ContainerInput containerInput) {
        // SCOPE CHECK FIRST (audit 2.1.3): only Solidus virtual menus are
        // rate-limited. Vanilla containers (chests, inventories, crafting
        // tables) must pass through untouched - consuming their clicks broke
        // normal gameplay for every player on the server.
        AbstractContainerMenu currentMenu = player.containerMenu;

        if (currentMenu instanceof ShopScreenHandler shopHandler) {
            if (!rateLimiter.allowClick(player.getUUID())) {
                // Click came too fast - silently drop the packet, with a
                // bounded (throttled) anti-ghost resync.
                SolidusMod.LOGGER.debug("Rate-limited click from player: {} (remaining: {}ms)",
                    player.getName().getString(), rateLimiter.getRemainingCooldown(player.getUUID()));
                throttledDropResync(player);
                return true; // Consume the packet - don't pass to vanilla
            }
            // Route to shop click handler
            shopHandler.clicked(slotIndex, button, containerInput, player);
            fullResync(player);
            return true;
        }

        if (currentMenu instanceof SellScreenHandler sellHandler) {
            if (!rateLimiter.allowClick(player.getUUID())) {
                SolidusMod.LOGGER.debug("Rate-limited click from player: {} (remaining: {}ms)",
                    player.getName().getString(), rateLimiter.getRemainingCooldown(player.getUUID()));
                throttledDropResync(player);
                return true; // Consume the packet - don't pass to vanilla
            }
            // Route to sell click handler - all clicks are handled manually
            // because the sell GUI allows item placement
            sellHandler.clicked(slotIndex, button, containerInput, player);
            fullResync(player);
            return true;
        }

        if (currentMenu instanceof AuctionScreenHandler auctionHandler) {
            if (!rateLimiter.allowClick(player.getUUID())) {
                SolidusMod.LOGGER.debug("Rate-limited click from player: {} (remaining: {}ms)",
                    player.getName().getString(), rateLimiter.getRemainingCooldown(player.getUUID()));
                throttledDropResync(player);
                return true; // Consume the packet - don't pass to vanilla
            }
            // Route to auction click handler
            auctionHandler.clicked(slotIndex, button, containerInput, player);
            fullResync(player);
            return true;
        }

        if (currentMenu instanceof TradeScreenHandler tradeHandler) {
            if (!rateLimiter.allowClick(player.getUUID())) {
                SolidusMod.LOGGER.debug("Rate-limited click from player: {} (remaining: {}ms)",
                    player.getName().getString(), rateLimiter.getRemainingCooldown(player.getUUID()));
                throttledDropResync(player);
                return true; // Consume the packet - don't pass to vanilla
            }
            // Route to trade click handler - clicks are handled manually (the
            // trade window allows real item movement like the sell GUI).
            tradeHandler.clicked(slotIndex, button, containerInput, player);
            fullResync(player);
            return true;
        }

        // Not a Solidus GUI - pass through to vanilla handling
        return false;
    }

    /**
     * Full container resync after a PROCESSED Solidus click: the server is the
     * single source of truth and any optimistic client prediction (ghost
     * items) is erased in the same moment it was created (PR#13 guarantee).
     */
    private void fullResync(ServerPlayer player) {
        player.containerMenu.broadcastFullState();
    }

    /**
     * Bounded anti-ghost resync for DROPPED clicks. A dropped click was still
     * optimistically predicted by the client, so a resync is eventually needed
     * - but flooding the server with clicks must never amplify into a stream
     * of full container broadcasts. At most one broadcast per window; the
     * next PROCESSED click (or the next window) erases any leftover ghost.
     */
    private void throttledDropResync(ServerPlayer player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();
        Long last = lastDropResyncMs.get(uuid);
        if (last == null || now - last >= DROP_RESYNC_INTERVAL_MS) {
            lastDropResyncMs.put(uuid, now);
            player.containerMenu.broadcastFullState();
        }
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
            || currentMenu instanceof AuctionScreenHandler
            || currentMenu instanceof TradeScreenHandler;
    }
}
