package com.solidus.auction;

import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auction House GUI - Displays active auction listings in a virtual chest menu.
 *
 * Layout: GENERIC_9x6 (54 slots)
 * +---------------------------------------------------------+
 * |  [0]  [Auction House Title]  ... [Refresh] [My Items]  |  Row 0: Header
 * |  [9] ... [44]   Auction Item Displays with price lore   |  Rows 1-4: Listings
 * |  [45] ... [52]   More Listings                          |  Row 5: More listings
 * |  [53]  [ Prev Page] [Next Page ]                     |  Navigation
 * +---------------------------------------------------------+
 *
 * All items are Display-Only. Left-click = Purchase.
 */
public final class AuctionGUI {

    private static final int INVENTORY_SIZE = 54;
    /**
     * FIX: was 45, which overlapped the PREV/NEXT/CLOSE navigation slots
     * (48/50/53) - listings landing on those slots were overwritten by the
     * nav buttons and became unpurchasable while still counted in the header.
     * We now reserve those three slots and place at most 42 listings per page.
     */
    private static final int ITEMS_PER_PAGE = 42;
    private static final int ITEMS_START = 9;
    private static final int REFRESH_BUTTON_SLOT = 7;
    private static final int MY_ITEMS_BUTTON_SLOT = 8;
    private static final int PREV_PAGE_SLOT = 48;
    private static final int NEXT_PAGE_SLOT = 50;
    private static final int CLOSE_BUTTON_SLOT = 53;

    private AuctionGUI() {}

    /**
     * Opens the auction house main view for a player.
     */
    public static void openAuction(ServerPlayer player, AuctionManager auctionManager) {
        auctionManager.getActiveListings().thenAccept(listings -> {
            attachBidStatesAndBuild(player, auctionManager, listings, 0, false, null);
        });
    }

    /**
     * Opens the auction house with a specific sort order.
     *
     * @param player        The player viewing the auction
     * @param auctionManager The auction manager
     * @param sortOrder     The sort order to apply
     */
    public static void openAuctionSorted(ServerPlayer player, AuctionManager auctionManager,
                                          AuctionManager.SortOrder sortOrder) {
        auctionManager.getActiveListings(sortOrder).thenAccept(listings -> {
            attachBidStatesAndBuild(player, auctionManager, listings, 0, false, sortOrder);
        });
    }

    /**
     * Opens the auction house showing only the player's own listings.
     */
    public static void openMyListings(ServerPlayer player, AuctionManager auctionManager) {
        auctionManager.getListingsBySeller(player.getUUID()).thenAccept(listings -> {
            attachBidStatesAndBuild(player, auctionManager, listings, 0, true, null);
        });
    }

    /**
     * Backward-compatible entry point used by page navigation and refresh:
     * builds with NO bid information (bidding badges simply absent).
     */
    public static void buildAndOpenAuctionScreen(ServerPlayer player, AuctionManager auctionManager,
                                                    List<AuctionEntry> listings, int page, boolean myItems) {
        buildAndOpenAuctionScreen(player, auctionManager, listings, page, myItems, null, Collections.emptyMap());
    }

    /**
     * Backward-compatible entry point with sort order but no bid states.
     */
    public static void buildAndOpenAuctionScreen(ServerPlayer player, AuctionManager auctionManager,
                                                    List<AuctionEntry> listings, int page, boolean myItems,
                                                    AuctionManager.SortOrder sortOrder) {
        buildAndOpenAuctionScreen(player, auctionManager, listings, page, myItems, sortOrder, Collections.emptyMap());
    }

    /**
     * Builds and opens the auction screen with the given listings and bid states.
     */
    public static void buildAndOpenAuctionScreen(ServerPlayer player, AuctionManager auctionManager,
                                                    List<AuctionEntry> listings, int page, boolean myItems,
                                                    AuctionManager.SortOrder sortOrder,
                                                    Map<UUID, BidState> bidStates) {
        List<GuiSlot> slots = new ArrayList<>();

        // Header: Title (show sort order if specified)
        String titleSuffix = sortOrder != null ? " [" + sortOrder.displayName() + "]" : "";
        ItemStack titleItem = createDisplayItem(Items.GOLD_BLOCK,
            TextUtil.styledBold("Auction House" + titleSuffix, ChatFormatting.GOLD),
            TextUtil.loreLine(listings.size() + " active listings"));
        slots.add(new GuiSlot(4, titleItem, GuiSlot.Type.DISPLAY_ONLY, null));

        // Header: Refresh button
        ItemStack refreshItem = createDisplayItem(Items.COMPASS,
            TextUtil.styledBold("Refresh", ChatFormatting.AQUA),
            TextUtil.loreLine("Click to refresh listings"));
        slots.add(new GuiSlot(REFRESH_BUTTON_SLOT, refreshItem, GuiSlot.Type.REFRESH, "REFRESH"));

        // Header: My Items button
        ItemStack myItemsItem = createDisplayItem(Items.PLAYER_HEAD,
            TextUtil.styledBold("My Listings", ChatFormatting.GREEN),
            TextUtil.loreLine("View your own listings"));
        slots.add(new GuiSlot(MY_ITEMS_BUTTON_SLOT, myItemsItem, GuiSlot.Type.MY_ITEMS, "MY_ITEMS"));

        // Listings with pagination - placed ONLY on free (non-navigation) slots
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, listings.size());

        java.util.List<Integer> slotLayout = listingSlots();
        for (int i = startIndex, pos = 0; i < endIndex && pos < slotLayout.size(); i++, pos++) {
            AuctionEntry entry = listings.get(i);
            int slot = slotLayout.get(pos);
            ItemStack displayItem = createAuctionItemDisplay(entry, bidStates.get(entry.listingId()));
            slots.add(new GuiSlot(slot, displayItem, GuiSlot.Type.AUCTION_ITEM, entry.listingId().toString()));
        }

        // Navigation: Previous page
        if (page > 0) {
            ItemStack prevItem = createDisplayItem(Items.SPECTRAL_ARROW,
                TextUtil.styledBold("< Previous Page", ChatFormatting.YELLOW),
                TextUtil.loreLine("Page " + page));
            slots.add(new GuiSlot(PREV_PAGE_SLOT, prevItem, GuiSlot.Type.NAVIGATION, "PREV"));
        }

        // Navigation: Next page
        if (endIndex < listings.size()) {
            ItemStack nextItem = createDisplayItem(Items.SPECTRAL_ARROW,
                TextUtil.styledBold("Next Page >", ChatFormatting.YELLOW),
                TextUtil.loreLine("Page " + (page + 2)));
            slots.add(new GuiSlot(NEXT_PAGE_SLOT, nextItem, GuiSlot.Type.NAVIGATION, "NEXT"));
        }

        // Close button
        ItemStack closeItem = createDisplayItem(Items.BARRIER,
            TextUtil.styledBold("Close Auction House", ChatFormatting.RED),
            TextUtil.loreLine("Click to close"));
        slots.add(new GuiSlot(CLOSE_BUTTON_SLOT, closeItem, GuiSlot.Type.NAVIGATION, "CLOSE"));

        // Fill empty slots
        fillEmptySlots(slots, INVENTORY_SIZE);

        // Open screen handler - the active sort order is passed through so page
        // navigation and refresh keep it (previously sort was silently lost).
        AuctionScreenHandler.openScreen(player,
            TextUtil.shopTitle("Auction House"),
            slots, auctionManager, page, myItems, sortOrder);
    }

    /**
     * Fetches the bid states for a page of listings, then continues to the
     * screen build on the server thread. One extra query per page - and the
     * bidding badges/lore only appear on bid-enabled listings.
     */
    private static void attachBidStatesAndBuild(ServerPlayer player, AuctionManager auctionManager,
                                                 List<AuctionEntry> listings, int page, boolean myItems,
                                                 AuctionManager.SortOrder sortOrder) {
        List<UUID> pageIds = new ArrayList<>();
        int startIndex = page * AuctionGUI.itemsPerPage();
        int endIndex = Math.min(startIndex + AuctionGUI.itemsPerPage(), listings.size());
        for (int i = startIndex; i < endIndex; i++) {
            pageIds.add(listings.get(i).listingId());
        }
        auctionManager.getBidStates(pageIds).thenAccept(bidStates ->
            player.level().getServer().execute(() ->
                buildAndOpenAuctionScreen(player, auctionManager, listings, page, myItems, sortOrder, bidStates)));
    }

    /** Items per page constant exposed for the bid-state page window. */
    public static int itemsPerPage() {
        return ITEMS_PER_PAGE;
    }

    /**
     * Slot indices available for listing displays (9..53 minus the reserved
     * navigation slots PREV/NEXT/CLOSE).
     */
    private static java.util.List<Integer> listingSlots() {
        java.util.List<Integer> slots = new ArrayList<>(ITEMS_PER_PAGE);
        for (int s = ITEMS_START; s < INVENTORY_SIZE; s++) {
            if (s == PREV_PAGE_SLOT || s == NEXT_PAGE_SLOT || s == CLOSE_BUTTON_SLOT) continue;
            slots.add(s);
        }
        return slots;
    }

    /**
     * Creates a display ItemStack for an auction listing.
     *
     * @param bidState the listing's bid state, or null for buy-now-only listings
     */
    private static ItemStack createAuctionItemDisplay(AuctionEntry entry, BidState bidState) {
        ItemStack display;
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.tryParse(entry.materialName().toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
            display = (item != null) ? new ItemStack(item) : new ItemStack(Items.PAPER);
        } catch (Exception e) {
            display = new ItemStack(Items.PAPER);
        }

        // Display name
        Component name = display.getHoverName().copy().withStyle(ChatFormatting.WHITE);
        display.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);

        // Lore with auction details
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(""));

        if (bidState != null) {
            // BIDDING-ENABLED LISTING
            lore.add(TextUtil.styledBold("BIDDING ENABLED", ChatFormatting.LIGHT_PURPLE));
            if (bidState.hasBids()) {
                lore.add(TextUtil.currency("Current Bid: " + CurrencyUtil.format(bidState.currentBid())));
                lore.add(TextUtil.styled("Top Bidder: " + bidState.currentBidderName(), ChatFormatting.YELLOW));
                lore.add(TextUtil.styled("Bids: " + bidState.bidCount(), ChatFormatting.AQUA));
            } else {
                lore.add(TextUtil.styled("Opening Bid: " + CurrencyUtil.format(bidState.startPrice()), ChatFormatting.AQUA));
                lore.add(TextUtil.styled("No bids yet", ChatFormatting.GRAY));
            }
            lore.add(TextUtil.currency("Buy Now: " + CurrencyUtil.format(entry.price())));
            lore.add(TextUtil.styled("Next Bid Min: " + CurrencyUtil.format(
                com.solidus.auction.BidRules.minNextBid(bidState.startPrice(), bidState.currentBid())), ChatFormatting.GRAY));
        } else {
            // BUY-NOW-ONLY LISTING (classic view)
            lore.add(TextUtil.currency("Price: " + CurrencyUtil.format(entry.price())));
        }
        lore.add(TextUtil.styled("Seller: " + entry.sellerName(), ChatFormatting.YELLOW));
        lore.add(TextUtil.styled("Quantity: " + entry.quantity(), ChatFormatting.AQUA));

        // Time remaining
        long remaining = entry.expireTimestamp() - System.currentTimeMillis();
        if (remaining > 0) {
            long hours = remaining / (60 * 60 * 1000);
            long minutes = (remaining % (60 * 60 * 1000)) / (60 * 1000);
            lore.add(TextUtil.styled("Expires in: " + hours + "h " + minutes + "m", ChatFormatting.GRAY));
        }

        lore.add(Component.literal(""));
        lore.add(TextUtil.styled("Left-Click to " + (bidState != null ? "Buy Now" : "Purchase"), ChatFormatting.GREEN));
        if (bidState != null) {
            lore.add(TextUtil.styled("Right-Click to Bid (type amount in chat)", ChatFormatting.LIGHT_PURPLE));
        }

        display.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));

        return display;
    }

    private static ItemStack createDisplayItem(net.minecraft.world.item.Item itemType,
                                                Component name, Component loreLine) {
        ItemStack item = new ItemStack(itemType);
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
        List<Component> lore = new ArrayList<>();
        lore.add(loreLine);
        item.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));
        return item;
    }

    private static void fillEmptySlots(List<GuiSlot> slots, int totalSlots) {
        java.util.Set<Integer> occupiedSlots = new java.util.HashSet<>();
        for (GuiSlot slot : slots) {
            occupiedSlots.add(slot.index());
        }

        ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glassPane.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(" "));

        for (int i = 0; i < totalSlots; i++) {
            if (!occupiedSlots.contains(i)) {
                slots.add(new GuiSlot(i, glassPane.copy(), GuiSlot.Type.FILLER, null));
            }
        }
    }

    /**
     * GUI Slot data class for the Auction House.
     */
    public record GuiSlot(
        int index,
        ItemStack displayStack,
        Type type,
        String actionKey
    ) {
        public enum Type {
            AUCTION_ITEM,    // Clickable auction listing
            REFRESH,         // Refresh listings button
            MY_ITEMS,        // View own listings button
            NAVIGATION,      // Page navigation / close
            DISPLAY_ONLY,    // Non-interactive header element
            FILLER           // Empty glass pane
        }
    }
}
