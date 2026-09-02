package com.solidus.shop;

import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shop GUI Builder - Constructs the virtual Chest GUI for the shop.
 *
 * <h2>Layout Philosophy (v3 - Centered Geometric Design)</h2>
 * The previous layout packed items top-to-bottom starting from slot 9 and
 * filled leftover space with a uniform gray pane. The redesigned layout
 * frames the content with a dark stained-glass border, centers items
 * both vertically and horizontally inside a 7x4 content area, and uses
 * a rotating palette of accent-colored panes for empty interior slots.
 *
 * <h3>Main Menu</h3>
 * <pre>
 *  Row 0: # # # # # # # # #     <- black border
 *  Row 1: #   S1  S2  S3  S4  S5  S6  S7   #
 *  Row 2: #   S8  S9  S10 S11 .   .   .   #     <- sections centered
 *  Row 3: #   .   .   .   .   .   .   .   #
 *  Row 4: #   .   .   .   .   .   .   .   #
 *  Row 5: # # # # # [CLOSE] # # #         <- bottom border w/ close button
 * </pre>
 *
 * <h3>Section View</h3>
 * <pre>
 *  Row 0: [BACK] # # # [TITLE] # # # #   <- header
 *  Row 1: #   I1  I2  I3  I4  I5  I6  I7   #
 *  Row 2: #   I8  I9  I10 I11 I12 I13 I14  #   <- items centered
 *  Row 3: #   .   .   .   .   .   .   .   #
 *  Row 4: #   .   .   .   .   .   .   .   #
 *  Row 5: # # [< PREV] [INFO] [NEXT >] # # <- navigation
 * </pre>
 *
 * <h2>Click Model</h2>
 * All items remain display-only. Clicks are intercepted by
 * {@link ShopScreenHandler#clicked} and rewritten into buy / sell /
 * navigate actions. No item movement is permitted.
 */
public final class ShopGUI {

    private static final int INVENTORY_SIZE = ShopGUILayout.INVENTORY_SIZE; // 54
    private static final int ITEMS_PER_PAGE = ShopGUILayout.MAX_CONTENT_ITEMS; // 28

    // -- Header Slots ----------------------------------------
    private static final int BACK_BUTTON_SLOT   = ShopGUILayout.slot(0, 0);    // 0
    private static final int TITLE_SLOT         = ShopGUILayout.slot(0, 4);    // 4
    private static final int CLOSE_BUTTON_SLOT  = ShopGUILayout.slot(5, 4);    // 49

    // -- Navigation Slots (bottom row interior) --------------
    private static final int PREV_PAGE_SLOT     = ShopGUILayout.slot(5, 2);    // 47
    private static final int PAGE_INFO_SLOT     = ShopGUILayout.slot(5, 3);    // 48
    private static final int NEXT_PAGE_SLOT     = ShopGUILayout.slot(5, 5);    // 50
    private static final int SEARCH_BUTTON_SLOT = ShopGUILayout.slot(5, 6);    // 51

    // -- Navigation Tags (stored in GuiSlot.actionKey) --------
    public static final String NAV_BACK   = "NAV_BACK";
    public static final String NAV_PREV   = "NAV_PREV";
    public static final String NAV_NEXT   = "NAV_NEXT";
    public static final String NAV_CLOSE  = "NAV_CLOSE";
    public static final String NAV_INFO   = "NAV_INFO";
    public static final String NAV_SEARCH = "NAV_SEARCH";

    private ShopGUI() {}

    // ===================================================================
    //  Main Menu
    // ===================================================================

    /**
     * Opens the main shop menu showing all sections as clickable icons,
     * centered inside the content area.
     */
    public static void openMainMenu(ServerPlayer player, ShopManager shopManager) {
        List<GuiSlot> slots = new ArrayList<>();
        Set<Integer> occupied = new HashSet<>();

        // -- Section buttons, centered in the content area --
        List<ShopManager.ShopSection> sections = new ArrayList<>(shopManager.getSections().values());
        int sectionCount = Math.min(sections.size(), ShopGUILayout.MAX_CONTENT_ITEMS);
        int[] centered = ShopGUILayout.centeredContentSlots(sectionCount);

        for (int i = 0; i < sectionCount; i++) {
            ShopManager.ShopSection section = sections.get(i);
            int slotIndex = centered[i];
            ItemStack icon = createSectionIcon(section);
            slots.add(new GuiSlot(slotIndex, icon, GuiSlot.Type.SECTION_BUTTON, section.key()));
            occupied.add(slotIndex);
        }

        // -- Close button (bottom-center, inside border) --
        ItemStack closeItem = createNavigationItem(Items.BARRIER,
            TextUtil.styledBold("Close", ChatFormatting.RED),
            TextUtil.loreLine("Click to close the shop"));
        slots.add(new GuiSlot(CLOSE_BUTTON_SLOT, closeItem, GuiSlot.Type.NAVIGATION, NAV_CLOSE));
        occupied.add(CLOSE_BUTTON_SLOT);

        // -- Info button (next to close, shows shop stats) --
        int totalItems = sections.stream().mapToInt(s -> s.items().size()).sum();
        ItemStack infoItem = createNavigationItem(Items.KNOWLEDGE_BOOK,
            TextUtil.styledBold("Shop Info", ChatFormatting.AQUA),
            TextUtil.loreLine(sections.size() + " categories | " + totalItems + " items"));
        slots.add(new GuiSlot(SEARCH_BUTTON_SLOT, infoItem, GuiSlot.Type.DISPLAY_ONLY, NAV_INFO));
        occupied.add(SEARCH_BUTTON_SLOT);

        // -- Fill remaining slots with decorative panes --
        fillDecorativePanes(slots, occupied);

        ShopScreenHandler.openScreen(player,
            TextUtil.shopTitle("Solidus Shop"),
            slots, shopManager, null, 0);
    }

    // ===================================================================
    //  Section View
    // ===================================================================

    /**
     * Opens a specific shop section with pagination. Items are centered
     * both horizontally and vertically inside the content area.
     */
    public static void openSection(ServerPlayer player, ShopManager shopManager,
                                    ShopManager.ShopSection section, int page) {
        List<GuiSlot> slots = new ArrayList<>();
        Set<Integer> occupied = new HashSet<>();

        // -- Header: Back button --
        ItemStack backItem = createNavigationItem(Items.ARROW,
            TextUtil.styledBold("<< Back", ChatFormatting.RED),
            TextUtil.loreLine("Return to categories"));
        slots.add(new GuiSlot(BACK_BUTTON_SLOT, backItem, GuiSlot.Type.NAVIGATION, NAV_BACK));
        occupied.add(BACK_BUTTON_SLOT);

        // -- Header: Section name (centered title) --
        ItemStack titleItem = createNavigationItem(Items.WRITABLE_BOOK,
            section.displayName().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
            TextUtil.loreLine(section.items().size() + " items | page " + (page + 1)));
        slots.add(new GuiSlot(TITLE_SLOT, titleItem, GuiSlot.Type.DISPLAY_ONLY, null));
        occupied.add(TITLE_SLOT);

        // -- Content: paginated items, centered --
        List<ShopManager.ShopItem> items = section.items();
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, items.size());
        int itemsThisPage = endIndex - startIndex;

        int[] centered = ShopGUILayout.centeredContentSlots(itemsThisPage);
        for (int i = startIndex; i < endIndex; i++) {
            int displayIdx = i - startIndex;
            int slotIndex = centered[displayIdx];
            ShopManager.ShopItem shopItem = items.get(i);
            ItemStack display = createShopItemDisplay(shopItem);
            slots.add(new GuiSlot(slotIndex, display, GuiSlot.Type.SHOP_ITEM, shopItem.material()));
            occupied.add(slotIndex);
        }

        // -- Footer: previous-page button --
        int totalPages = getTotalPages(items.size());
        if (page > 0) {
            ItemStack prevItem = createNavigationItem(Items.SPECTRAL_ARROW,
                TextUtil.styledBold("<< Prev", ChatFormatting.YELLOW),
                TextUtil.loreLine("Page " + page + " / " + totalPages));
            slots.add(new GuiSlot(PREV_PAGE_SLOT, prevItem, GuiSlot.Type.NAVIGATION, NAV_PREV));
            occupied.add(PREV_PAGE_SLOT);
        }

        // -- Footer: page indicator (always shown) --
        ItemStack pageInfo = createNavigationItem(Items.PAPER,
            TextUtil.styledBold((page + 1) + " / " + totalPages, ChatFormatting.GRAY),
            TextUtil.loreLine("Current page"));
        slots.add(new GuiSlot(PAGE_INFO_SLOT, pageInfo, GuiSlot.Type.DISPLAY_ONLY, NAV_INFO));
        occupied.add(PAGE_INFO_SLOT);

        // -- Footer: next-page button --
        if (endIndex < items.size()) {
            ItemStack nextItem = createNavigationItem(Items.SPECTRAL_ARROW,
                TextUtil.styledBold("Next >>", ChatFormatting.YELLOW),
                TextUtil.loreLine("Page " + (page + 2) + " / " + totalPages));
            slots.add(new GuiSlot(NEXT_PAGE_SLOT, nextItem, GuiSlot.Type.NAVIGATION, NAV_NEXT));
            occupied.add(NEXT_PAGE_SLOT);
        }

        // -- Footer: close button --
        ItemStack closeItem = createNavigationItem(Items.BARRIER,
            TextUtil.styledBold("Close", ChatFormatting.RED),
            TextUtil.loreLine("Click to close"));
        slots.add(new GuiSlot(CLOSE_BUTTON_SLOT, closeItem, GuiSlot.Type.NAVIGATION, NAV_CLOSE));
        occupied.add(CLOSE_BUTTON_SLOT);

        // -- Fill remaining slots with decorative panes --
        fillDecorativePanes(slots, occupied);

        ShopScreenHandler.openScreen(player,
            TextUtil.shopTitle("Solidus Shop - " + section.displayName().getString()),
            slots, shopManager, section.key(), page);
    }

    // ===================================================================
    //  ItemStack Builders
    // ===================================================================

    /**
     * Creates a section icon ItemStack for the main menu.
     */
    private static ItemStack createSectionIcon(ShopManager.ShopSection section) {
        ItemStack icon;
        try {
            Item item = BuiltInRegistries.ITEM
                .get(Identifier.tryParse(section.icon().toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
            icon = (item != null) ? new ItemStack(item) : new ItemStack(Items.CHEST);
        } catch (Exception e) {
            icon = new ItemStack(Items.CHEST);
        }

        // Display name
        icon.set(DataComponents.CUSTOM_NAME,
            section.displayName().copy().withStyle(ChatFormatting.BOLD));

        // Lore with item count and click hint
        List<Component> lore = new ArrayList<>();
        lore.add(TextUtil.loreLine("Click to browse"));
        lore.add(TextUtil.styled(section.items().size() + " items available", ChatFormatting.AQUA));
        icon.set(DataComponents.LORE, new ItemLore(lore));
        return icon;
    }

    /**
     * Resolves the actual max stack size for a material (static version of
     * ShopScreenHandler.getMaxStackSize - the lore builder runs in a static
     * context). Not all items stack to 64 (ender pearls = 16, mace = 1);
     * falls back to 64 if the item can't be resolved.
     */
    private static int resolveMaxStackSize(String material) {
        try {
            Item item = BuiltInRegistries.ITEM
                .get(Identifier.tryParse(material.toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
            if (item != null) {
                return item.getDefaultMaxStackSize();
            }
        } catch (Exception e) {
            // Fall through to default
        }
        return 64;
    }

    /**
     * Creates a shop item display ItemStack with price information in the lore.
     * Left-click = Buy 1, Right-click = Sell 1, Shift+Left = Buy stack, Shift+Right = Sell All
     */
    private static ItemStack createShopItemDisplay(ShopManager.ShopItem shopItem) {
        ItemStack display;
        try {
            Item item = BuiltInRegistries.ITEM
                .get(Identifier.tryParse(shopItem.material().toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
            display = (item != null) ? new ItemStack(item) : new ItemStack(Items.PAPER);
        } catch (Exception e) {
            display = new ItemStack(Items.PAPER);
        }

        // Display name (white, bold for visibility)
        Component itemName = display.getHoverName().copy().withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
        display.set(DataComponents.CUSTOM_NAME, itemName);

        // Build lore with pricing info
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(""));

        if (shopItem.buyPrice() > 0) {
            lore.add(TextUtil.buyPriceLore(shopItem.buyPrice()));
            // Audit 2.1.3: use the material's REAL max stack size - the fixed
            // "x64" line was wrong for ENDER_PEARL (16) and MACE (1), showing
            // 64x the actual quantity and price the purchase delivers.
            int bulk = resolveMaxStackSize(shopItem.material());
            lore.add(TextUtil.styled("  Shift+L-Click: Buy x" + bulk + " for "
                + CurrencyUtil.format(shopItem.buyPrice() * bulk), ChatFormatting.DARK_GREEN));
        } else {
            lore.add(TextUtil.styled("Buy: Not Available", ChatFormatting.GRAY));
        }

        if (shopItem.sellPrice() > 0) {
            lore.add(TextUtil.sellPriceLore(shopItem.sellPrice()));
            lore.add(TextUtil.styled("  R-Click: Sell 1 | Shift+R-Click: Sell All",
                ChatFormatting.DARK_RED));
        } else {
            lore.add(TextUtil.styled("Sell: Not Available", ChatFormatting.GRAY));
        }

        lore.add(Component.literal(""));
        lore.add(TextUtil.styled("L-Click: Buy 1 | R-Click: Sell 1", ChatFormatting.DARK_GRAY));

        display.set(DataComponents.LORE, new ItemLore(lore));
        return display;
    }

    /**
     * Creates a navigation item (arrow, barrier, etc.) with custom name and lore.
     */
    private static ItemStack createNavigationItem(Item itemType,
                                                   Component name, Component loreLine) {
        ItemStack item = new ItemStack(itemType);
        item.set(DataComponents.CUSTOM_NAME, name);
        List<Component> lore = new ArrayList<>();
        lore.add(loreLine);
        item.set(DataComponents.LORE, new ItemLore(lore));
        return item;
    }

    // ===================================================================
    //  Decorative Fillers
    // ===================================================================

    /**
     * Fills every unoccupied slot with a decorative stained-glass pane.
     * Border slots receive dark panes; interior content slots receive
     * rotating accent-colored panes for a vibrant, geometric pattern.
     */
    private static void fillDecorativePanes(List<GuiSlot> slots, Set<Integer> occupied) {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (occupied.contains(i)) continue;

            ItemStack pane = ShopGUILayout.isBorderSlot(i)
                ? ShopGUILayout.createBorderPane(i)
                : ShopGUILayout.createAccentPane(i);

            slots.add(new GuiSlot(i, pane.copy(), GuiSlot.Type.FILLER, null));
        }
    }

    // ===================================================================
    //  Helpers
    // ===================================================================

    private static int getTotalPages(int totalItems) {
        return Math.max(1, (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE));
    }

    /**
     * Returns the number of items displayed per page in section view.
     */
    public static int getItemsPerPage() {
        return ITEMS_PER_PAGE;
    }

    // ===================================================================
    //  GuiSlot Record
    // ===================================================================

    /**
     * Data class representing a single slot in the virtual shop GUI.
     */
    public record GuiSlot(
        int index,
        ItemStack displayStack,
        Type type,
        String actionKey
    ) {
        public enum Type {
            SHOP_ITEM,        // Clickable shop item (buy/sell)
            SECTION_BUTTON,   // Clickable section category
            NAVIGATION,       // Navigation button (prev/next/back/close)
            DISPLAY_ONLY,     // Non-interactive display element
            FILLER            // Empty glass pane filler
        }
    }
}
