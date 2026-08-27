package com.solidus.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ShopGUILayout}.
 *
 * These tests validate the pure-Java layout arithmetic (slot indexing,
 * border/content classification, and centered positioning) without
 * requiring a Minecraft runtime. The ItemStack factory methods that
 * depend on {@code net.minecraft.world.item.Items} are covered by
 * integration tests, not here.
 */
@DisplayName("ShopGUILayout")
class ShopGUILayoutTest {

    // -- Constants -------------------------------------------

    @Test
    @DisplayName("INVENTORY_SIZE is 54 (9x6)")
    void inventorySizeIsCorrect() {
        assertEquals(54, ShopGUILayout.INVENTORY_SIZE);
    }

    @Test
    @DisplayName("COLUMNS is 9, ROWS is 6")
    void dimensionsAreCorrect() {
        assertEquals(9, ShopGUILayout.COLUMNS);
        assertEquals(6, ShopGUILayout.ROWS);
    }

    @Test
    @DisplayName("CONTENT_WIDTH is 7, CONTENT_ROWS is 4")
    void contentDimensionsAreCorrect() {
        assertEquals(7, ShopGUILayout.CONTENT_WIDTH);
        assertEquals(4, ShopGUILayout.CONTENT_ROWS);
    }

    @Test
    @DisplayName("MAX_CONTENT_ITEMS is 28 (7x4)")
    void maxContentItemsIsCorrect() {
        assertEquals(28, ShopGUILayout.MAX_CONTENT_ITEMS);
    }

    // -- slot(row, col) --------------------------------------

    @Nested
    @DisplayName("slot(row, col)")
    class SlotCoordinateTest {

        @Test
        @DisplayName("row 0 col 0 -> slot 0")
        void topLeft() {
            assertEquals(0, ShopGUILayout.slot(0, 0));
        }

        @Test
        @DisplayName("row 0 col 8 -> slot 8")
        void topRight() {
            assertEquals(8, ShopGUILayout.slot(0, 8));
        }

        @Test
        @DisplayName("row 5 col 0 -> slot 45")
        void bottomLeft() {
            assertEquals(45, ShopGUILayout.slot(5, 0));
        }

        @Test
        @DisplayName("row 5 col 8 -> slot 53")
        void bottomRight() {
            assertEquals(53, ShopGUILayout.slot(5, 8));
        }

        @Test
        @DisplayName("row 2 col 4 -> slot 22 (center of inventory)")
        void center() {
            assertEquals(22, ShopGUILayout.slot(2, 4));
        }

        @Test
        @DisplayName("row 1 col 1 -> slot 10 (first content slot)")
        void firstContentSlot() {
            assertEquals(10, ShopGUILayout.slot(1, 1));
        }

        @Test
        @DisplayName("row 4 col 7 -> slot 43 (last content slot)")
        void lastContentSlot() {
            assertEquals(43, ShopGUILayout.slot(4, 7));
        }
    }

    // -- rowOf / colOf ---------------------------------------

    @Nested
    @DisplayName("rowOf() / colOf()")
    class RowColOfTest {

        @Test
        @DisplayName("rowOf returns correct row for all slots")
        void rowOfAllSlots() {
            for (int slot = 0; slot < 54; slot++) {
                int expected = slot / 9;
                assertEquals(expected, ShopGUILayout.rowOf(slot),
                    "rowOf(" + slot + ") should be " + expected);
            }
        }

        @Test
        @DisplayName("colOf returns correct column for all slots")
        void colOfAllSlots() {
            for (int slot = 0; slot < 54; slot++) {
                int expected = slot % 9;
                assertEquals(expected, ShopGUILayout.colOf(slot),
                    "colOf(" + slot + ") should be " + expected);
            }
        }
    }

    // -- isBorderSlot ----------------------------------------

    @Nested
    @DisplayName("isBorderSlot()")
    class IsBorderSlotTest {

        @Test
        @DisplayName("top row (0-8) is border")
        void topRowIsBorder() {
            for (int col = 0; col < 9; col++) {
                assertTrue(ShopGUILayout.isBorderSlot(col),
                    "slot " + col + " should be border");
            }
        }

        @Test
        @DisplayName("bottom row (45-53) is border")
        void bottomRowIsBorder() {
            for (int col = 0; col < 9; col++) {
                int slot = 45 + col;
                assertTrue(ShopGUILayout.isBorderSlot(slot),
                    "slot " + slot + " should be border");
            }
        }

        @Test
        @DisplayName("left column (0, 9, 18, 27, 36, 45) is border")
        void leftColumnIsBorder() {
            for (int row = 0; row < 6; row++) {
                int slot = row * 9;
                assertTrue(ShopGUILayout.isBorderSlot(slot),
                    "slot " + slot + " should be border");
            }
        }

        @Test
        @DisplayName("right column (8, 17, 26, 35, 44, 53) is border")
        void rightColumnIsBorder() {
            for (int row = 0; row < 6; row++) {
                int slot = row * 9 + 8;
                assertTrue(ShopGUILayout.isBorderSlot(slot),
                    "slot " + slot + " should be border");
            }
        }

        @Test
        @DisplayName("content area (rows 1-4, cols 1-7) is NOT border")
        void contentAreaIsNotBorder() {
            for (int row = 1; row <= 4; row++) {
                for (int col = 1; col <= 7; col++) {
                    int slot = row * 9 + col;
                    assertFalse(ShopGUILayout.isBorderSlot(slot),
                        "slot " + slot + " should NOT be border");
                }
            }
        }

        @Test
        @DisplayName("border has exactly 26 slots (top 9 + bottom 9 + 4 left interior + 4 right interior)")
        void borderHasCorrectCount() {
            int count = 0;
            for (int i = 0; i < 54; i++) {
                if (ShopGUILayout.isBorderSlot(i)) count++;
            }
            assertEquals(26, count);
        }
    }

    // -- isContentSlot ---------------------------------------

    @Nested
    @DisplayName("isContentSlot()")
    class IsContentSlotTest {

        @Test
        @DisplayName("content area (rows 1-4, cols 1-7) is content")
        void contentAreaIsContent() {
            for (int row = 1; row <= 4; row++) {
                for (int col = 1; col <= 7; col++) {
                    int slot = row * 9 + col;
                    assertTrue(ShopGUILayout.isContentSlot(slot),
                        "slot " + slot + " should be content");
                }
            }
        }

        @Test
        @DisplayName("border slots are NOT content")
        void borderSlotsAreNotContent() {
            for (int i = 0; i < 54; i++) {
                if (ShopGUILayout.isBorderSlot(i)) {
                    assertFalse(ShopGUILayout.isContentSlot(i),
                        "border slot " + i + " should NOT be content");
                }
            }
        }

        @Test
        @DisplayName("content area has exactly 28 slots (7 cols x 4 rows)")
        void contentAreaHasCorrectCount() {
            int count = 0;
            for (int i = 0; i < 54; i++) {
                if (ShopGUILayout.isContentSlot(i)) count++;
            }
            assertEquals(28, count);
        }

        @Test
        @DisplayName("border and content partition the inventory (no overlap, full coverage)")
        void borderAndContentPartition() {
            for (int i = 0; i < 54; i++) {
                boolean isBorder = ShopGUILayout.isBorderSlot(i);
                boolean isContent = ShopGUILayout.isContentSlot(i);
                assertFalse(isBorder && isContent,
                    "slot " + i + " cannot be both border and content");
                assertTrue(isBorder || isContent,
                    "slot " + i + " must be either border or content");
            }
        }
    }

    // -- centeredContentSlots --------------------------------

    @Nested
    @DisplayName("centeredContentSlots()")
    class CenteredContentSlotsTest {

        @Test
        @DisplayName("count 0 returns empty array")
        void countZeroReturnsEmpty() {
            int[] result = ShopGUILayout.centeredContentSlots(0);
            assertEquals(0, result.length);
        }

        @Test
        @DisplayName("negative count returns empty array")
        void negativeCountReturnsEmpty() {
            int[] result = ShopGUILayout.centeredContentSlots(-5);
            assertEquals(0, result.length);
        }

        @Test
        @DisplayName("count 1 returns the geometric center slot")
        void singleItemIsCentered() {
            int[] result = ShopGUILayout.centeredContentSlots(1);
            assertEquals(1, result.length);
            // Center of 4 content rows: (4-1)/2 = 1 -> content row 1 -> border row 2
            // Center of 7 cols: (7-1)/2 = 3 -> content col 3 -> border col 4
            // slot(2, 4) = 2*9 + 4 = 22
            assertEquals(22, result[0]);
        }

        @Test
        @DisplayName("count 7 fills one centered row")
        void sevenItemsFillOneRow() {
            int[] result = ShopGUILayout.centeredContentSlots(7);
            assertEquals(7, result.length);
            // All slots should be in the same row (row 2, vertically centered for 1 row)
            int expectedRow = 2;
            for (int slot : result) {
                assertEquals(expectedRow, ShopGUILayout.rowOf(slot),
                    "slot " + slot + " should be in row " + expectedRow);
            }
            // Columns should be 1-7 (content columns)
            for (int i = 0; i < 7; i++) {
                assertEquals(1 + i, ShopGUILayout.colOf(result[i]),
                    "column " + i + " should be " + (1 + i));
            }
        }

        @Test
        @DisplayName("count 14 fills two centered rows")
        void fourteenItemsFillTwoRows() {
            int[] result = ShopGUILayout.centeredContentSlots(14);
            assertEquals(14, result.length);
            // Two rows centered in 4 content rows -> start at content row 1 -> border row 2
            int firstRow = ShopGUILayout.rowOf(result[0]);
            int lastRow = ShopGUILayout.rowOf(result[13]);
            assertEquals(2, firstRow);
            assertEquals(3, lastRow);
        }

        @Test
        @DisplayName("count 28 fills all 4 content rows completely")
        void twentyEightItemsFillAll() {
            int[] result = ShopGUILayout.centeredContentSlots(28);
            assertEquals(28, result.length);
            // All slots must be in the content area
            for (int slot : result) {
                assertTrue(ShopGUILayout.isContentSlot(slot),
                    "slot " + slot + " must be a content slot");
            }
            // No duplicates
            java.util.Set<Integer> unique = new java.util.HashSet<>();
            for (int slot : result) {
                assertTrue(unique.add(slot), "duplicate slot: " + slot);
            }
        }

        @Test
        @DisplayName("count > MAX_CONTENT_ITEMS is clamped to 28")
        void overMaxIsClamped() {
            int[] result = ShopGUILayout.centeredContentSlots(100);
            assertEquals(28, result.length);
        }

        @Test
        @DisplayName("count 3 is centered horizontally in one row")
        void threeItemsCenteredHorizontally() {
            int[] result = ShopGUILayout.centeredContentSlots(3);
            assertEquals(3, result.length);
            // 3 items in 7-wide row -> start col = (7-3)/2 = 2 -> +1 border = 3
            // Cols 3, 4, 5
            int[] expectedCols = {3, 4, 5};
            for (int i = 0; i < 3; i++) {
                assertEquals(expectedCols[i], ShopGUILayout.colOf(result[i]),
                    "col " + i + " should be " + expectedCols[i]);
            }
            // All in same row (vertically centered for 1 row: row 2)
            int expectedRow = 2;
            for (int slot : result) {
                assertEquals(expectedRow, ShopGUILayout.rowOf(slot));
            }
        }

        @Test
        @DisplayName("count 8 splits across two rows: 7 + 1 (the lone item is centered)")
        void eightItemsSplitAcrossRows() {
            int[] result = ShopGUILayout.centeredContentSlots(8);
            assertEquals(8, result.length);
            // First 7 fill one row, last 1 is centered in next row
            int row1 = ShopGUILayout.rowOf(result[0]);
            int row2 = ShopGUILayout.rowOf(result[7]);
            assertNotEquals(row1, row2, "8th item should be on a different row");
        }

        @Test
        @DisplayName("all returned slots are in the content area")
        void allReturnedSlotsAreContent() {
            for (int n : new int[]{1, 2, 3, 5, 7, 10, 14, 20, 28}) {
                int[] result = ShopGUILayout.centeredContentSlots(n);
                for (int slot : result) {
                    assertTrue(ShopGUILayout.isContentSlot(slot),
                        "count=" + n + " slot " + slot + " must be content");
                }
            }
        }

        @Test
        @DisplayName("all returned slots are unique")
        void allReturnedSlotsAreUnique() {
            for (int n : new int[]{1, 5, 7, 14, 21, 28}) {
                int[] result = ShopGUILayout.centeredContentSlots(n);
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (int slot : result) {
                    assertTrue(seen.add(slot),
                        "count=" + n + " has duplicate slot " + slot);
                }
            }
        }

        @Test
        @DisplayName("vertical centering: 1 row -> row 2, 2 rows -> rows 2-3, 3 rows -> rows 1-3, 4 rows -> rows 1-4")
        void verticalCentering() {
            // 1 row -> start at content row 1 (border row 2)
            int[] r1 = ShopGUILayout.centeredContentSlots(7);
            assertEquals(2, ShopGUILayout.rowOf(r1[0]));

            // 2 rows -> start at content row 1 (border row 2)
            int[] r2 = ShopGUILayout.centeredContentSlots(14);
            assertEquals(2, ShopGUILayout.rowOf(r2[0]));
            assertEquals(3, ShopGUILayout.rowOf(r2[13]));

            // 3 rows -> start at content row 0 (border row 1)
            int[] r3 = ShopGUILayout.centeredContentSlots(21);
            assertEquals(1, ShopGUILayout.rowOf(r3[0]));
            assertEquals(3, ShopGUILayout.rowOf(r3[20]));

            // 4 rows -> start at content row 0 (border row 1)
            int[] r4 = ShopGUILayout.centeredContentSlots(28);
            assertEquals(1, ShopGUILayout.rowOf(r4[0]));
            assertEquals(4, ShopGUILayout.rowOf(r4[27]));
        }
    }

    // -- allSlots --------------------------------------------

    @Test
    @DisplayName("allSlots() returns 54 slots 0-53 in order")
    void allSlotsReturnsFullInventory() {
        java.util.List<Integer> slots = ShopGUILayout.allSlots();
        assertEquals(54, slots.size());
        for (int i = 0; i < 54; i++) {
            assertEquals(i, slots.get(i));
        }
    }
}
