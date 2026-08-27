package com.solidus.sell;

import com.solidus.shop.ShopManager;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sell GUI Builder - Constructs the virtual Chest GUI for selling items.
 *
 * Layout: GENERIC_9x6 (54 slots)
 * +---------------------------------------------------------+
 * |  [Info] [Filler x7] [Close & Sell]                     |  Row 0: Header (UI slots)
 * |  [9]  [10] ... [17]   Input Area                       |  Row 1: Input slots
 * |  [18] [19] ... [26]   Input Area                       |  Row 2: Input slots
 * |  [27] [28] ... [35]   Input Area                       |  Row 3: Input slots
 * |  [36] [37] ... [44]   Input Area                       |  Row 4: Input slots
 * |  [45] [46] ... [53]   Input Area                       |  Row 5: Input slots
 * +---------------------------------------------------------+
 *
 * Row 0 (slots 0-8): UI elements (info, fillers, close button)
 * Rows 1-5 (slots 9-53): Input area where players place items to sell
 *
 * Items placed in the input area are processed when the GUI is closed:
 * - Sellable items (present in shop with sell price > 0) are sold
 * - Unsellable items are returned to the player's inventory
 * - If inventory is full, unsellable items are dropped on the ground
 * - Shulker boxes are processed: sellable contents are sold,
 *   unsellable contents remain in the shulker box
 */
public final class SellGUI {

    private static final int INVENTORY_SIZE = 54;
    private static final int INPUT_START = 9;

    // Navigation action keys
    public static final String NAV_CLOSE = "NAV_CLOSE";

    private SellGUI() {}

    /**
     * Opens the sell GUI for a player.
     * The player can place items in the input area to sell them.
     * When the GUI is closed, all sellable items are sold and
     * unsellable items are returned to the player.
     */
    public static void openSellGUI(ServerPlayer player, ShopManager shopManager) {
        List<GuiSlot> uiSlots = buildUISlots();
        SellScreenHandler.openScreen(player, uiSlots, shopManager);
    }

    /**
     * Builds the UI slots for the sell GUI header (row 0).
     * Input area slots (9-53) are left empty for player item placement.
     */
    static List<GuiSlot> buildUISlots() {
        List<GuiSlot> slots = new ArrayList<>();

        // Slot 0: Info item
        ItemStack infoItem = new ItemStack(Items.HOPPER);
        infoItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            TextUtil.styledBold("Sell GUI", ChatFormatting.GOLD));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(Component.literal(""));
        infoLore.add(TextUtil.loreLine("Place items in the slots below to sell them"));
        infoLore.add(TextUtil.loreLine("Only items with a sell price in the shop will be sold"));
        infoLore.add(TextUtil.loreLine("Unsellable items will be returned to your inventory"));
        infoLore.add(TextUtil.loreLine("Items inside Shulker Boxes will also be processed"));
        infoLore.add(Component.literal(""));
        infoLore.add(TextUtil.styled("Close the GUI to sell your items!", ChatFormatting.YELLOW));
        infoItem.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(infoLore));
        slots.add(new GuiSlot(0, infoItem, GuiSlot.Type.DISPLAY_ONLY, null));

        // Slot 8: Close & Sell button
        ItemStack closeItem = new ItemStack(Items.BARRIER);
        closeItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            TextUtil.styledBold("Close & Sell", ChatFormatting.RED));
        List<Component> closeLore = new ArrayList<>();
        closeLore.add(Component.literal(""));
        closeLore.add(TextUtil.loreLine("Click to process sales and close"));
        closeLore.add(TextUtil.loreLine("Or press E to close and sell"));
        closeItem.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(closeLore));
        slots.add(new GuiSlot(8, closeItem, GuiSlot.Type.NAVIGATION, NAV_CLOSE));

        // Fill remaining header slots with glass panes
        Set<Integer> occupied = new HashSet<>();
        for (GuiSlot slot : slots) {
            occupied.add(slot.index());
        }

        ItemStack glassPane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glassPane.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            Component.literal(" "));

        for (int i = 0; i < 9; i++) { // Only fill row 0
            if (!occupied.contains(i)) {
                slots.add(new GuiSlot(i, glassPane.copy(), GuiSlot.Type.FILLER, null));
            }
        }

        return slots;
    }

    /**
     * GUI Slot data class for the Sell GUI.
     * Only used for UI header slots (0-8). Input area slots (9-53) are
     * managed by the SellContainer directly.
     */
    public record GuiSlot(
        int index,
        ItemStack displayStack,
        Type type,
        String actionKey
    ) {
        public enum Type {
            NAVIGATION,       // Close & Sell button
            DISPLAY_ONLY,     // Info item
            FILLER            // Glass pane filler
        }
    }
}
