package com.solidus.shop;

import com.solidus.SolidusMod;
import com.solidus.gui.DisplaySlot;
import com.solidus.shop.ShopGUI.GuiSlot;
import com.solidus.util.TextUtil;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shop Screen Handler - Native server-side GUI handler for the virtual shop.
 *
 * CRITICAL ARCHITECTURE:
 * This is a NATIVE ScreenHandler extension, NOT a third-party GUI library.
 * Writing native ScreenHandler extensions is mandatory for long-term server
 * infrastructure stability, as required by the Solidus specification.
 *
 * This handler intercepts and completely rewrites slot click events (onSlotClick).
 * The GUI items are strictly "Display-Only". Moving, dragging, shifting, or
 * double-clicking items into the player inventory is BLOCKED programmatically.
 *
 * Click Processing:
 * - Left-click on shop item = Buy 1 unit
 * - Right-click on shop item = Sell 1 unit
 * - Shift+Left-click on shop item = Buy 64 units (stack)
 * - Shift+Right-click on shop item = Sell all of that item
 * - Click on section button = Navigate to section
 * - Click on navigation = Prev/Next/Back/Close
 *
 * All clicks on display/filler items are silently consumed.
 */
public class ShopScreenHandler extends AbstractContainerMenu {

    /** Total number of virtual shop slots (GENERIC_9x6 = 54). */
    private static final int INVENTORY_SIZE = 54;

    private final ServerPlayer player;
    private final ShopManager shopManager;
    private final List<GuiSlot> guiSlots;
    private final String currentSection;
    private final int currentPage;

    // Map from slot index to GuiSlot for fast lookup during click processing
    private final Map<Integer, GuiSlot> slotMap = new HashMap<>();

    /**
     * Opens a new shop screen for the player.
     * Creates the handler, populates slots, and sends the OpenScreen packet.
     *
     * @throws IllegalArgumentException if page is negative
     */
    public static void openScreen(ServerPlayer player, Component title,
                                   List<GuiSlot> slots, ShopManager shopManager,
                                   String section, int page) {
        // Clamp page to >= 0 and store in a final variable for the inner class
        final int safePage;
        if (page < 0) {
            SolidusMod.LOGGER.warn("Attempted to open shop screen with negative page {} for player {}",
                page, player.getName().getString());
            safePage = 0;
        } else {
            safePage = page;
        }

        // Create handler via SimpleMenuProvider
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return title;
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
                return new ShopScreenHandler(syncId, playerInventory,
                    (ServerPlayer) player, slots, shopManager, section, safePage);
            }
        });
    }

    private ShopScreenHandler(int syncId, Inventory playerInventory,
                               ServerPlayer player, List<GuiSlot> slots,
                               ShopManager shopManager, String section, int page) {
        super(MenuType.GENERIC_9x6, syncId);
        this.player = player;
        this.shopManager = shopManager;
        this.guiSlots = slots;
        this.currentSection = section;
        this.currentPage = page;

        // Build slot map for click lookup
        for (GuiSlot guiSlot : slots) {
            slotMap.put(guiSlot.index(), guiSlot);
        }

        // Add container slots (the virtual shop inventory)
        // Using a dummy container that we control completely.
        // DisplaySlot: mayPlace/mayPickup/set are all blocked - no vanilla
        // code path can ever move an item through these slots.
        ShopDummyContainer container = new ShopDummyContainer(slots);
        for (int i = 0; i < 54; i++) {
            this.addSlot(new DisplaySlot(container, i, 0, 0));
        }

        // Add player inventory slots (standard 9x3 + hotbar layout offset)
        int playerInvOffset = 54; // After the 54 container slots
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

    /**
     * CRITICAL: Complete rewrite of slot click handling.
     *
     * All shop items are Display-Only. The default behavior of moving
     * items between containers is entirely suppressed. Instead, clicks
     * are analyzed for their intent (buy, sell, navigate) and processed
     * as financial transactions or navigation events.
     *
     * The {@code button} parameter is retained for right-click detection
     * (0 = left, 1 = right). In Minecraft 26.1.x, {@link ContainerInput}
     * replaces the legacy {@code ClickType} enum and absorbs most click
     * variants; {@code QUICK_MOVE} still indicates a shift-click.
     */
    @Override
    public void clicked(int slotIndex, int button, ContainerInput containerInput, Player player) {
        // Player inventory clicks (slot >= 54) - return without action.
        // Vanilla processing is already cancelled by the ServerPlayerEntityMixin,
        // so player inventory interaction is blocked while the shop GUI is open.
        // This is intentional for security - prevents item manipulation exploits.
        // The mixin then calls broadcastFullState(), erasing any optimistic
        // client-side prediction (ghost items) in the same moment.
        if (slotIndex < 0 || slotIndex >= INVENTORY_SIZE) {
            return;
        }

        // Defensive: only the player who owns this handler may interact.
        if (player != this.player) {
            SolidusMod.LOGGER.warn("Shop click from non-owner player {} (expected {})",
                player.getName().getString(), this.player.getName().getString());
            return;
        }

        GuiSlot guiSlot = slotMap.get(slotIndex);
        if (guiSlot == null) {
            return; // Unknown slot - ignore
        }

        switch (guiSlot.type()) {
            case SHOP_ITEM -> handleShopItemClick(guiSlot, button, containerInput);
            case SECTION_BUTTON -> handleSectionButtonClick(guiSlot);
            case NAVIGATION -> handleNavigationClick(guiSlot);
            case DISPLAY_ONLY, FILLER -> {
                // Do nothing - these are non-interactive
            }
        }

        // ALWAYS cancel the default click behavior
        // The shop layout must remain static
    }

    /**
     * Handles a click on a shop item - processes buy/sell transactions.
     *
     * Click combinations:
     * <ul>
     *   <li>Left-click -> Buy 1</li>
     *   <li>Right-click -> Sell 1</li>
     *   <li>Shift+Left-click -> Buy a full stack (item.maxStackSize)</li>
     *   <li>Shift+Right-click -> Sell all matching items in the main inventory</li>
     * </ul>
     */
    private void handleShopItemClick(GuiSlot slot, int button, ContainerInput containerInput) {
        String material = slot.actionKey();
        if (material == null || material.isBlank()) return;

        boolean isShiftClick = containerInput == ContainerInput.QUICK_MOVE;
        boolean isRightClick = button == 1;

        if (isShiftClick && isRightClick) {
            // Shift+Right-Click: Sell all of this item
            // Count the actual number of this item in the player's inventory
            int totalInInventory = countItemInInventory(player, material);
            if (totalInInventory <= 0) {
                player.sendSystemMessage(TextUtil.error("You don't have any " + material + " to sell!"));
                return;
            }
            shopManager.processSell(player, material, totalInInventory);
        } else if (isShiftClick) {
            // Shift+Left-Click: Buy a stack (use item's actual max stack size)
            int maxStack = getMaxStackSize(material);
            shopManager.processBuy(player, material, maxStack);
        } else if (isRightClick) {
            // Right-Click: Sell 1
            shopManager.processSell(player, material, 1);
        } else {
            // Left-Click: Buy 1
            shopManager.processBuy(player, material, 1);
        }
    }

    /**
     * Handles a click on a section button - navigates to that section.
     */
    private void handleSectionButtonClick(GuiSlot slot) {
        String sectionKey = slot.actionKey();
        if (sectionKey == null || sectionKey.isBlank()) return;

        player.closeContainer();
        shopManager.openSection(player, sectionKey, 0);
    }

    /**
     * Handles navigation button clicks (prev/next/back/close).
     * Defensive page clamping prevents negative-page or beyond-last-page
     * navigation that could be triggered by race conditions between
     * inventory reloads and rapid clicks.
     */
    private void handleNavigationClick(GuiSlot slot) {
        String action = slot.actionKey();
        if (action == null) return;

        switch (action) {
            case ShopGUI.NAV_BACK -> {
                player.closeContainer();
                shopManager.openShop(player);
            }
            case ShopGUI.NAV_PREV -> {
                if (currentPage <= 0) return; // already on first page
                player.closeContainer();
                shopManager.openSection(player, currentSection, currentPage - 1);
            }
            case ShopGUI.NAV_NEXT -> {
                player.closeContainer();
                shopManager.openSection(player, currentSection, currentPage + 1);
            }
            case ShopGUI.NAV_CLOSE -> {
                player.closeContainer();
            }
            // NAV_INFO is DISPLAY_ONLY, but if it ever arrives here, ignore it.
            default -> { }
        }
    }

    /**
     * Gets the actual max stack size for a material.
     * Not all items stack to 64 (e.g., ender pearls = 16, snowballs = 16).
     * Falls back to 64 if the item can't be resolved.
     */
    private int getMaxStackSize(String material) {
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.tryParse(material.toLowerCase()))
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
     * Counts the total number of a specific material in the player's main inventory.
     * Used by Shift+Right-Click to sell all of a given item.
     * Armor slots (36-39) and offhand (40) are excluded to prevent accidental sales.
     */
    private int countItemInInventory(ServerPlayer player, String material) {
        int count = 0;
        // Only count items in main inventory (slots 0-35), skip armor and offhand
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && TextUtil.getMaterialName(stack).equalsIgnoreCase(material)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Prevents quick-stack (shift-click) from moving items out of the shop.
     * Only allows shift-click INTO the shop (which we also block).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Block ALL quick-move operations in the shop GUI
        return ItemStack.EMPTY;
    }

    /**
     * Prevents the player from taking items from the shop container.
     */
    @Override
    public boolean stillValid(Player player) {
        return true; // Always valid while the screen is open
    }

    /**
     * Called when the player closes the screen.
     * No items to drop since the shop is virtual.
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        // No item cleanup needed - shop items are virtual/display-only
    }
}
