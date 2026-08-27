package com.solidus.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SolidusPermissions}.
 *
 * Validates that the permission-node constants follow the naming convention
 * and that the default-OP-level resolver returns the correct level for
 * admin-only sub-commands.
 */
@DisplayName("SolidusPermissions")
class SolidusPermissionsTest {

    // -- Naming Convention -----------------------------------

    @Nested
    @DisplayName("Naming Convention")
    class NamingConventionTest {

        @Test
        @DisplayName("SHOP permission follows 'solidus.command.shop' pattern")
        void shopPermissionName() {
            assertEquals("solidus.command.shop", SolidusPermissions.SHOP);
        }

        @Test
        @DisplayName("SHOP_SEARCH permission follows 'solidus.command.shop.search' pattern")
        void shopSearchPermissionName() {
            assertEquals("solidus.command.shop.search", SolidusPermissions.SHOP_SEARCH);
        }

        @Test
        @DisplayName("SHOP_RELOAD permission follows 'solidus.command.shop.reload' pattern")
        void shopReloadPermissionName() {
            assertEquals("solidus.command.shop.reload", SolidusPermissions.SHOP_RELOAD);
        }

        @Test
        @DisplayName("All core command permissions start with 'solidus.command.'")
        void corePermissionsStartWithCommandPrefix() {
            assertTrue(SolidusPermissions.BALANCE.startsWith("solidus.command."));
            assertTrue(SolidusPermissions.PAY.startsWith("solidus.command."));
            assertTrue(SolidusPermissions.SHOP.startsWith("solidus.command."));
            assertTrue(SolidusPermissions.SELL.startsWith("solidus.command."));
            assertTrue(SolidusPermissions.AUCTION_VIEW.startsWith("solidus.command."));
            assertTrue(SolidusPermissions.TRANSACTIONS.startsWith("solidus.command."));
        }
    }

    // -- getDefaultOpLevel -----------------------------------

    @Nested
    @DisplayName("getDefaultOpLevel()")
    class GetDefaultOpLevelTest {

        @Test
        @DisplayName("core player commands return OP level 0")
        void coreCommandsReturnZero() {
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.BALANCE));
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.PAY));
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.SHOP));
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.SHOP_SEARCH));
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.SELL));
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.AUCTION_VIEW));
            assertEquals(0, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.TRANSACTIONS));
        }

        @Test
        @DisplayName("SHOP_RELOAD returns OP level 2 (admin-only)")
        void shopReloadReturnsTwo() {
            assertEquals(2, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.SHOP_RELOAD));
        }

        @Test
        @DisplayName("SHOP_RELOAD is the only core command with elevated OP level")
        void shopReloadIsOnlyElevatedCoreCommand() {
            // All solidus.command.* permissions should be 0 EXCEPT SHOP_RELOAD
            String[] corePermissions = {
                SolidusPermissions.BALANCE,
                SolidusPermissions.PAY,
                SolidusPermissions.PAY_OFFLINE,
                SolidusPermissions.BALTOP,
                SolidusPermissions.SHOP,
                SolidusPermissions.SHOP_SEARCH,
                SolidusPermissions.SELL,
                SolidusPermissions.AUCTION_VIEW,
                SolidusPermissions.AUCTION_SELL,
                SolidusPermissions.AUCTION_COLLECT,
                SolidusPermissions.AUCTION_CANCEL,
                SolidusPermissions.AUCTION_SORT,
                SolidusPermissions.TRANSACTIONS
            };
            for (String perm : corePermissions) {
                assertEquals(0, SolidusPermissions.getDefaultOpLevel(perm),
                    "permission " + perm + " should be OP level 0");
            }
            // SHOP_RELOAD is the exception
            assertEquals(2, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.SHOP_RELOAD));
        }

        @Test
        @DisplayName("analytics management commands return OP level 3")
        void analyticsManagementReturnsThree() {
            assertEquals(3, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.ANALYTICS_SNAPSHOT));
            assertEquals(3, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.ANALYTICS_EXPORT));
            assertEquals(3, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.ANALYTICS_DASHBOARD_MANAGE));
        }

        @Test
        @DisplayName("analytics view commands return OP level 2")
        void analyticsViewReturnsTwo() {
            assertEquals(2, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.ANALYTICS));
            assertEquals(2, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.ANALYTICS_WEALTH));
            assertEquals(2, SolidusPermissions.getDefaultOpLevel(SolidusPermissions.ANALYTICS_FRAUD));
        }

        @Test
        @DisplayName("unknown permission returns OP level 2 (safe default)")
        void unknownPermissionReturnsTwo() {
            assertEquals(2, SolidusPermissions.getDefaultOpLevel("solidus.unknown.permission"));
            assertEquals(2, SolidusPermissions.getDefaultOpLevel("not.a.solidus.permission"));
        }
    }
}
