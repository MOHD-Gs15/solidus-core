package com.solidus.shop;

import com.solidus.compat.Palette;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Shop GUI Layout Engine - Provides centered geometric positioning and
 * decorative stained-glass-pane fills for the virtual shop container.
 *
 * <h2>Inventory Grid (GENERIC_9x6 = 54 slots)</h2>
 * <pre>
 *   Cols -> 0    1    2    3    4    5    6    7    8
 *   Row 0: [B]  [B]  [B]  [B]  [B]  [B]  [B]  [B]  [B]   <- top border (black)
 *   Row 1: [B]  .    .    .    .    .    .    .    [B]   <- content row A
 *   Row 2: [B]  .    .    .    .    .    .    .    [B]   <- content row B
 *   Row 3: [B]  .    .    .    .    .    .    .    [B]   <- content row C
 *   Row 4: [B]  .    .    .    .    .    .    .    [B]   <- content row D
 *   Row 5: [B]  [B]  [B]  [B]  [B]  [B]  [B]  [B]  [B]   <- bottom border / nav
 * </pre>
 *
 * <h2>Centering Strategy</h2>
 * Items are centered both <b>vertically</b> (across the 4 content rows) and
 * <b>horizontally</b> (within each row, max 7 items per row). This produces
 * a geometrically balanced layout regardless of how many items are being
 * displayed - a single item appears dead-center, 7 items fill one row,
 * 14 items fill two rows, and so on.
 *
 * <h2>Stained Glass Palette</h2>
 * Empty slots are filled with a rotating palette of stained glass panes to
 * add color and visual interest. The border uses dark panes (black/gray)
 * to frame the content, while interior fillers use brighter accent colors
 * that cycle to create a subtle pattern.
 */
public final class ShopGUILayout {

    /** Total slots in a GENERIC_9x6 inventory */
    public static final int INVENTORY_SIZE = 54;

    /** Number of columns */
    public static final int COLUMNS = 9;

    /** Number of rows */
    public static final int ROWS = 6;

    /** Items per content row (excludes left/right border columns) */
    public static final int CONTENT_WIDTH = 7;

    /** Number of content rows (excludes top/bottom border rows) */
    public static final int CONTENT_ROWS = 4;

    /** Maximum items that fit in the centered content area */
    public static final int MAX_CONTENT_ITEMS = CONTENT_WIDTH * CONTENT_ROWS; // 28

    // -- Border Palette (dark, framing) -----------------------
    // Lazy-initialized to avoid triggering Minecraft class bootstrap when
    // this class is loaded by unit tests that only exercise the pure-Java
    // layout arithmetic. (Palette resolves via the item registry — see
    // com.solidus.compat.Palette.)
    private static volatile Item[] borderPanes;

    // -- Content Filler Palette (bright, accent) --------------
    private static volatile Item[] accentPanes;

    private static Item[] borderPanes() {
        if (borderPanes == null) {
            borderPanes = new Item[] {
                Palette.BLACK_STAINED_GLASS_PANE,
                Palette.GRAY_STAINED_GLASS_PANE,
                Palette.LIGHT_GRAY_STAINED_GLASS_PANE
            };
        }
        return borderPanes;
    }

    private static Item[] accentPanes() {
        if (accentPanes == null) {
            accentPanes = new Item[] {
                Palette.BLUE_STAINED_GLASS_PANE,
                Palette.CYAN_STAINED_GLASS_PANE,
                Palette.LIGHT_BLUE_STAINED_GLASS_PANE,
                Palette.GREEN_STAINED_GLASS_PANE,
                Palette.LIME_STAINED_GLASS_PANE,
                Palette.YELLOW_STAINED_GLASS_PANE,
                Palette.ORANGE_STAINED_GLASS_PANE,
                Palette.RED_STAINED_GLASS_PANE,
                Palette.PINK_STAINED_GLASS_PANE,
                Palette.MAGENTA_STAINED_GLASS_PANE,
                Palette.PURPLE_STAINED_GLASS_PANE
            };
        }
        return accentPanes;
    }

    private ShopGUILayout() {}

    // -- Coordinate Helpers -----------------------------------

    /**
     * Converts (row, col) coordinates to a flat slot index.
     */
    public static int slot(int row, int col) {
        return row * COLUMNS + col;
    }

    /**
     * Returns the row index for a given flat slot index.
     */
    public static int rowOf(int slotIndex) {
        return slotIndex / COLUMNS;
    }

    /**
     * Returns the column index for a given flat slot index.
     */
    public static int colOf(int slotIndex) {
        return slotIndex % COLUMNS;
    }

    /**
     * Returns true if the slot is on the outer border of the inventory.
     */
    public static boolean isBorderSlot(int slotIndex) {
        int row = rowOf(slotIndex);
        int col = colOf(slotIndex);
        return row == 0 || row == ROWS - 1 || col == 0 || col == COLUMNS - 1;
    }

    /**
     * Returns true if the slot is inside the centered content area
     * (rows 1-4, columns 1-7).
     */
    public static boolean isContentSlot(int slotIndex) {
        int row = rowOf(slotIndex);
        int col = colOf(slotIndex);
        return row >= 1 && row <= CONTENT_ROWS && col >= 1 && col <= CONTENT_WIDTH;
    }

    // -- Centered Layout Calculation --------------------------

    /**
     * Calculates slot positions that center {@code count} items in the
     * content area, both vertically and horizontally.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Compute the number of rows needed (ceil(count / 7)).</li>
     *   <li>Center the block of rows vertically within the 4 content rows.</li>
     *   <li>For each row, center the items horizontally within the 7 columns.</li>
     * </ol>
     *
     * @param count number of items to position (clamped to MAX_CONTENT_ITEMS)
     * @return ordered array of slot indices for the items
     */
    public static int[] centeredContentSlots(int count) {
        if (count <= 0) return new int[0];
        int n = Math.min(count, MAX_CONTENT_ITEMS);

        int rowsNeeded = (int) Math.ceil(n / (double) CONTENT_WIDTH);
        int verticalStart = (CONTENT_ROWS - rowsNeeded) / 2; // 0-based offset within content area

        int[] result = new int[n];
        int placed = 0;
        for (int r = 0; r < rowsNeeded && placed < n; r++) {
            int itemsThisRow = Math.min(CONTENT_WIDTH, n - placed);
            int horizontalStart = (CONTENT_WIDTH - itemsThisRow) / 2;

            for (int c = 0; c < itemsThisRow && placed < n; c++) {
                int contentRow = verticalStart + r + 1;       // +1 because row 0 is border
                int contentCol = horizontalStart + c + 1;     // +1 because col 0 is border
                result[placed++] = slot(contentRow, contentCol);
            }
        }
        return result;
    }

    /**
     * Convenience: returns the single centered slot for one item.
     */
    public static int centeredSingleSlot() {
        return slot(CONTENT_ROWS / 2 + 1, CONTENT_WIDTH / 2 + 1); // row 2-3 boundary, col 4
    }

    // -- Decorative Pane Builders -----------------------------

    /**
     * Creates a decorative stained-glass-pane ItemStack for border slots.
     * The pane is named with a single space so it appears unnamed in the tooltip.
     *
     * @param slotIndex the slot where this pane will sit (used to pick a color)
     * @return an ItemStack containing the pane
     */
    public static ItemStack createBorderPane(int slotIndex) {
        Item type = borderPanes()[Math.abs(slotIndex) % borderPanes().length];
        return unnamedPane(type);
    }

    /**
     * Creates a decorative stained-glass-pane ItemStack for interior filler slots.
     * Uses the brighter accent palette and cycles by slot index for a subtle pattern.
     *
     * @param slotIndex the slot where this pane will sit (used to pick a color)
     * @return an ItemStack containing the pane
     */
    public static ItemStack createAccentPane(int slotIndex) {
        Item type = accentPanes()[Math.abs(slotIndex) % accentPanes().length];
        return unnamedPane(type);
    }

    /**
     * Creates a custom-colored pane with an optional label.
     *
     * @param color the ChatFormatting color (mapped to the closest dye color)
     * @param label optional display name; pass null for an unnamed pane
     * @return the styled pane ItemStack
     */
    public static ItemStack createColoredPane(ChatFormatting color, Component label) {
        Item type = paneForColor(color);
        ItemStack stack = new ItemStack(type);
        if (label != null) {
            stack.set(DataComponents.CUSTOM_NAME, label);
        } else {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        }
        return stack;
    }

    /**
     * Returns all 54 slot indices in the inventory, sorted ascending.
     */
    public static List<Integer> allSlots() {
        List<Integer> slots = new ArrayList<>(INVENTORY_SIZE);
        for (int i = 0; i < INVENTORY_SIZE; i++) slots.add(i);
        return slots;
    }

    // -- Internal Helpers -------------------------------------

    private static ItemStack unnamedPane(Item type) {
        ItemStack stack = new ItemStack(type);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        return stack;
    }

    private static Item paneForColor(ChatFormatting color) {
        // Map ChatFormatting colors to the closest stained glass pane variant
        return switch (color) {
            case BLACK -> Palette.BLACK_STAINED_GLASS_PANE;
            case DARK_BLUE -> Palette.BLUE_STAINED_GLASS_PANE;
            case DARK_GREEN -> Palette.GREEN_STAINED_GLASS_PANE;
            case DARK_AQUA -> Palette.CYAN_STAINED_GLASS_PANE;
            case DARK_RED -> Palette.RED_STAINED_GLASS_PANE;
            case DARK_PURPLE -> Palette.PURPLE_STAINED_GLASS_PANE;
            case GOLD -> Palette.ORANGE_STAINED_GLASS_PANE;
            case GRAY -> Palette.GRAY_STAINED_GLASS_PANE;
            case DARK_GRAY -> Palette.LIGHT_GRAY_STAINED_GLASS_PANE;
            case BLUE -> Palette.LIGHT_BLUE_STAINED_GLASS_PANE;
            case GREEN -> Palette.LIME_STAINED_GLASS_PANE;
            case AQUA -> Palette.LIGHT_BLUE_STAINED_GLASS_PANE;
            case RED -> Palette.PINK_STAINED_GLASS_PANE;
            case LIGHT_PURPLE -> Palette.MAGENTA_STAINED_GLASS_PANE;
            case YELLOW -> Palette.YELLOW_STAINED_GLASS_PANE;
            case WHITE -> Palette.WHITE_STAINED_GLASS_PANE;
            default -> Palette.GRAY_STAINED_GLASS_PANE;
        };
    }
}
