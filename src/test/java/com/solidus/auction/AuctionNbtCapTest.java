package com.solidus.auction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security unit tests for the auction listing payload cap (audit SOL-006,
 * CWE-400): oversized serialized items must be rejected at LIST time - before
 * any fee is charged or any row is written - so a nested-shulker / stuffed
 * written book cannot inflate the auction table, the startup sweep, and every
 * container broadcast.
 *
 * Pure unit tests: only the static boundary is exercised, no Minecraft server
 * and no database is involved.
 */
@DisplayName("Auction listing NBT cap (SOL-006)")
class AuctionNbtCapTest {

    @Test
    @DisplayName("the cap is 128 KB of serialized text")
    void capValue() {
        assertEquals(131_072, AuctionManager.MAX_ITEM_NBT_CHARS);
    }

    @Test
    @DisplayName("null and empty payloads never exceed the cap")
    void nullAndEmpty() {
        assertFalse(AuctionManager.exceedsNbtCap(null));
        assertFalse(AuctionManager.exceedsNbtCap(""));
    }

    @Test
    @DisplayName("a legitimate item (material-name fallback, ~12 KB book) is accepted")
    void legitimatePayloadsPass() {
        assertFalse(AuctionManager.exceedsNbtCap("minecraft:diamond_sword"));
        // A fully written book serializes to ~12 KB - comfortably inside.
        assertFalse(AuctionManager.exceedsNbtCap("nbt:".repeat(1_500)));
    }

    @Test
    @DisplayName("exactly at the cap is still accepted (boundary)")
    void atCapAccepted() {
        assertFalse(AuctionManager.exceedsNbtCap("x".repeat(AuctionManager.MAX_ITEM_NBT_CHARS)));
    }

    @Test
    @DisplayName("one character past the cap is rejected (boundary)")
    void pastCapRejected() {
        assertTrue(AuctionManager.exceedsNbtCap("x".repeat(AuctionManager.MAX_ITEM_NBT_CHARS + 1)));
        assertTrue(AuctionManager.exceedsNbtCap("x".repeat(AuctionManager.MAX_ITEM_NBT_CHARS * 4)));
    }
}
