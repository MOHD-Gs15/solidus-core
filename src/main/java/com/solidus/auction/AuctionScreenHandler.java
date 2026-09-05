package com.solidus.auction;

import com.solidus.auction.AuctionGUI.GuiSlot;
import com.solidus.gui.DisplaySlot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auction Screen Handler - Native server-side GUI handler for the Auction House.
 *
 * Same architecture principles as ShopScreenHandler:
 * - All items are Display-Only
 * - Moving, dragging, shifting items is BLOCKED
 * - Left-click on auction item = Purchase
 * - Click events trigger financial transactions through the economy engine
 *
 * Race Condition Protection:
 * When a purchase is triggered, the AuctionManager uses database row-level
 * locking (BEGIN IMMEDIATE) to ensure only one player can purchase a given
 * listing, preventing duplication glitches.
 */
public class AuctionScreenHandler extends AbstractContainerMenu {

    private final ServerPlayer player;
    private final AuctionManager auctionManager;
    private final List<GuiSlot> guiSlots;
    private final int currentPage;
    private final boolean myItemsView;

    /**
     * FIX: the active sort order is remembered so PREV/NEXT page navigation
     * re-fetches listings with the SAME ordering instead of silently falling
     * back to NEWEST (which reset /ah sort as soon as you changed page).
     */
    private final AuctionManager.SortOrder sortOrder;

    private final Map<Integer, GuiSlot> slotMap = new HashMap<>();

    /**
     * Opens a new auction screen for the player.
     *
     * @param sortOrder the active sort order for this view (nullable = NEWEST)
     */
    public static void openScreen(ServerPlayer player, Component title,
                                   List<GuiSlot> slots, AuctionManager auctionManager,
                                   int page, boolean myItems,
                                   AuctionManager.SortOrder sortOrder) {
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
                return new AuctionScreenHandler(syncId, playerInventory,
                    (ServerPlayer) player, slots, auctionManager, page, myItems, sortOrder);
            }
        });
    }

    private AuctionScreenHandler(int syncId, Inventory playerInventory,
                                  ServerPlayer player, List<GuiSlot> slots,
                                  AuctionManager auctionManager, int page, boolean myItems,
                                  AuctionManager.SortOrder sortOrder) {
        super(MenuType.GENERIC_9x6, syncId);
        this.player = player;
        this.auctionManager = auctionManager;
        this.guiSlots = slots;
        this.currentPage = page;
        this.myItemsView = myItems;
        this.sortOrder = sortOrder;

        // Build slot map
        for (GuiSlot guiSlot : slots) {
            slotMap.put(guiSlot.index(), guiSlot);
        }

        // Add auction container slots.
        // DisplaySlot: mayPlace/mayPickup/set are all blocked - no vanilla
        // code path can ever move an item through these slots.
        AuctionDummyContainer container = new AuctionDummyContainer(slots);
        for (int i = 0; i < 54; i++) {
            this.addSlot(new DisplaySlot(container, i, 0, 0));
        }

        // Add player inventory slots
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory,
                    col + row * 9 + 9,
                    8 + col * 18,
                    84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    @Override
    public void clicked(int slotIndex, int button, net.minecraft.world.inventory.ContainerInput containerInput, Player player) {
        // Defensive (audit 2.1.3): only the player who owns this handler may
        // interact - mirrors ShopScreenHandler's invariant.
        if (player != this.player) {
            com.solidus.SolidusMod.LOGGER.warn("Rejected click on auction GUI of {} from a different actor.",
                this.player.getName().getString());
            return;
        }

        // Player inventory clicks (slot >= 54) - return without action.
        // Note: Vanilla processing is already cancelled by the ServerPlayerEntityMixin,
        // so player inventory interaction is blocked while the auction GUI is open.
        // This is intentional for security - prevents item manipulation exploits.
        // The mixin then calls broadcastFullState(), erasing any optimistic
        // client-side prediction (ghost items) in the same moment.
        if (slotIndex < 0 || slotIndex >= 54) {
            return;
        }

        GuiSlot guiSlot = slotMap.get(slotIndex);
        if (guiSlot == null) {
            return;
        }

        // Audit 2.1.3: the GUI advertises "Left-Click to Purchase" (see the
        // listing lore), but ANY click type (right-click, number-key SWAP,
        // drag, THROW) previously triggered a full purchase attempt. Accept
        // only a plain left PICKUP for item slots so forged/unusual gestures
        // can't initiate settlement, matching the documented interaction.
        if (guiSlot.type() == GuiSlot.Type.AUCTION_ITEM
            && (containerInput != net.minecraft.world.inventory.ContainerInput.PICKUP || button != 0)
            && !(containerInput == net.minecraft.world.inventory.ContainerInput.PICKUP && button == 1)) {
            return;
        }

        switch (guiSlot.type()) {
            case AUCTION_ITEM -> {
                // BIDDING: right-click opens the "type your bid in chat" prompt
                // (standard auction-plugin UX); left-click stays Buy Now.
                if (containerInput == net.minecraft.world.inventory.ContainerInput.PICKUP && button == 1) {
                    handleBidPrompt(guiSlot);
                } else {
                    handleAuctionItemClick(guiSlot);
                }
            }
            case REFRESH -> handleRefresh();
            case MY_ITEMS -> handleMyItems();
            case NAVIGATION -> handleNavigation(guiSlot);
            case DISPLAY_ONLY, FILLER -> {
                // Non-interactive - ignore
            }
        }
    }

    /**
     * Opens the bid chat prompt for a listing. The player's next chat message
     * is consumed as the bid amount (or "cancel" aborts). The GUI closes so
     * the player can see the chat clearly - the standard auction-plugin flow.
     */
    private void handleBidPrompt(GuiSlot slot) {
        String listingIdStr = slot.actionKey();
        if (listingIdStr == null) return;

        java.util.UUID listingId;
        try {
            listingId = java.util.UUID.fromString(listingIdStr);
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(com.solidus.util.TextUtil.error("Invalid listing ID."));
            return;
        }

        com.solidus.chat.ChatPrompts prompts = com.solidus.SolidusMod.getChatPrompts();
        if (prompts == null) {
            player.sendSystemMessage(com.solidus.util.TextUtil.error(
                "Bid prompt unavailable - use /ah bid " + listingIdStr + " <amount>"));
            return;
        }

        player.closeContainer();
        player.sendSystemMessage(com.solidus.util.TextUtil.styled(
            "Type your bid amount in chat (e.g. 1500), or type 'cancel':", ChatFormatting.LIGHT_PURPLE));
        prompts.openPrompt(player, (p, message) -> {
            String trimmed = message.trim();
            if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("الغاء")) {
                p.sendSystemMessage(com.solidus.util.TextUtil.styled("Bid cancelled.", ChatFormatting.GRAY));
                return com.solidus.chat.ChatPrompts.CONSUME;
            }
            try {
                double amount = Double.parseDouble(trimmed.replace(",", ""));
                auctionManager.placeBid(p, listingId, amount);
            } catch (NumberFormatException e) {
                p.sendSystemMessage(com.solidus.util.TextUtil.error(
                    "'" + trimmed + "' is not a valid number. Bid cancelled."));
            }
            return com.solidus.chat.ChatPrompts.CONSUME;
        });
    }

    /**
     * Handles a purchase click on an auction item.
     */
    private void handleAuctionItemClick(GuiSlot slot) {
        String listingIdStr = slot.actionKey();
        if (listingIdStr == null) return;

        try {
            java.util.UUID listingId = java.util.UUID.fromString(listingIdStr);
            auctionManager.purchaseItem(player, listingId);
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(com.solidus.util.TextUtil.error("Invalid listing ID."));
        }
    }

    /**
     * Handles the refresh button click.
     */
    private void handleRefresh() {
        // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
        player.closeContainer();
        if (myItemsView) {
            AuctionGUI.openMyListings(player, auctionManager);
        } else if (sortOrder != null) {
            // Keep the active sort when refreshing a sorted view
            AuctionGUI.openAuctionSorted(player, auctionManager, sortOrder);
        } else {
            AuctionGUI.openAuction(player, auctionManager);
        }
    }

    /**
     * Handles the My Items button click.
     */
    private void handleMyItems() {
        // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
        player.closeContainer();
        AuctionGUI.openMyListings(player, auctionManager);
    }

    /**
     * Handles navigation button clicks.
     */
    private void handleNavigation(GuiSlot slot) {
        String action = slot.actionKey();
        if (action == null) return;

        AuctionManager.SortOrder navOrder = (sortOrder != null) ? sortOrder : AuctionManager.SortOrder.NEWEST;

        switch (action) {
            case "PREV" -> {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
                if (myItemsView) {
                    auctionManager.getListingsBySeller(player.getUUID()).thenAccept(listings -> {
                        player.level().getServer().execute(() ->
                            AuctionGUI.buildAndOpenAuctionScreen(player, auctionManager, listings, currentPage - 1, true));
                    });
                } else {
                    // Re-fetch active listings keeping the active sort order (FIX)
                    auctionManager.getActiveListings(navOrder).thenAccept(listings -> {
                        player.level().getServer().execute(() ->
                            AuctionGUI.buildAndOpenAuctionScreen(player, auctionManager, listings, currentPage - 1, false, navOrder));
                    });
                }
            }
            case "NEXT" -> {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
                if (myItemsView) {
                    auctionManager.getListingsBySeller(player.getUUID()).thenAccept(listings -> {
                        player.level().getServer().execute(() ->
                            AuctionGUI.buildAndOpenAuctionScreen(player, auctionManager, listings, currentPage + 1, true));
                    });
                } else {
                    // Re-fetch active listings keeping the active sort order (FIX)
                    auctionManager.getActiveListings(navOrder).thenAccept(listings -> {
                        player.level().getServer().execute(() ->
                            AuctionGUI.buildAndOpenAuctionScreen(player, auctionManager, listings, currentPage + 1, false, navOrder));
                    });
                }
            }
            case "CLOSE" -> player.closeContainer(); // TODO: 26.1.x - Verify closeContainer() not renamed
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // Block all quick-move
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
    }
}
