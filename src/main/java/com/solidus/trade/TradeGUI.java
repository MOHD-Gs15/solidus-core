package com.solidus.trade;

import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Trade GUI - Layout and display builders for the player-to-player trade
 * window (GENERIC_9x6, 54 slots).
 *
 * <pre>
 * +-------------------------------------------------------------------+
 * |  MY INFO  |  fill  |  TITLE  |  fill  |        THEIR INFO       | row 0
 * | [a][b][c] |        [MY MONEY][THEIR MONEY]   |  [x][y][z]       | rows 1-2
 * | [d][e][f] |  fill  |  READY  | THEY-READY| fill            | rows 3-4
 * | [g][h][i] |        |  fill   |  CANCEL |  fill |  [w][u][v]     | row 5
 * +-------------------------------------------------------------------+
 *   MY OFFER = left 3 columns (15 slots)   THEIR OFFER = right 3 columns
 * </pre>
 *
 * <p>MY offer slots accept real item placement (cursor movement handled by
 * {@link TradeScreenHandler}); THEIR offer slots are display-only mirrors of
 * what the other player put up. Both players see the SAME layout from their
 * own perspective - every player's own offer is always on the left.</p>
 */
public final class TradeGUI {

    private TradeGUI() {}

    // -- Slot map ------------------------------------------
    public static final int MY_INFO_SLOT = 0;
    public static final int TITLE_SLOT = 4;
    public static final int THEIR_INFO_SLOT = 8;
    public static final int MY_MONEY_SLOT = 13;
    public static final int THEIR_MONEY_SLOT = 22;
    public static final int READY_SLOT = 31;
    public static final int THEIR_READY_SLOT = 40;
    public static final int CANCEL_SLOT = 49;

    /** Offer slots in MY OWN container (left 3 columns, rows 1-5). */
    public static final int[] MY_OFFER_SLOTS = {
        9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38, 45, 46, 47
    };

    /** Mirror slots showing the partner's offer (right 3 columns, rows 1-5). */
    public static final int[] THEIR_OFFER_SLOTS = {
        15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44, 51, 52, 53
    };

    /** Pure filler panes (separators + header padding). */
    public static final int[] FILLER_SLOTS = {
        1, 2, 3, 5, 6, 7, 12, 14, 21, 23, 30, 32, 39, 41, 48, 50
    };

    // -- Display builders ----------------------------------

    public static ItemStack titleItem() {
        return displayItem(Items.GOLD_BLOCK,
            TextUtil.styledBold("Trade", ChatFormatting.GOLD),
            TextUtil.loreLine("Put items on the left, then press READY"));
    }

    public static ItemStack myInfoItem(String myName, double money, boolean ready) {
        return displayItem(Items.PLAYER_HEAD,
            TextUtil.styledBold("You: " + myName, ChatFormatting.GREEN),
            TextUtil.loreLine("Your offer: " + moneyLore(money)));
    }

    public static ItemStack theirInfoItem(String otherName, double money) {
        return displayItem(Items.PLAYER_HEAD,
            TextUtil.styledBold("Partner: " + otherName, ChatFormatting.YELLOW),
            TextUtil.loreLine("Their offer: " + moneyLore(money)));
    }

    /** My clickable money button (left columns, row 1). */
    public static ItemStack myMoneyItem(double money) {
        ItemStack item = displayItem(Items.GOLD_INGOT,
            TextUtil.styledBold("Your Money Offer", ChatFormatting.GOLD),
            TextUtil.loreLine("Current: " + moneyLore(money)));
        List<Component> lore = new ArrayList<>();
        lore.add(TextUtil.loreLine("Click, then type the amount in chat"));
        lore.add(TextUtil.loreLine("Type 0 to remove your money offer"));
        item.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));
        return item;
    }

    public static ItemStack theirMoneyItem(double money) {
        return displayItem(Items.GOLD_NUGGET,
            TextUtil.styledBold("Their Money Offer", ChatFormatting.GOLD),
            TextUtil.loreLine("Current: " + moneyLore(money)));
    }

    public static ItemStack readyItem(boolean ready) {
        return displayItem(ready ? Items.LIME_DYE : Items.GRAY_DYE,
            TextUtil.styledBold(ready ? "READY - waiting for partner" : "Ready?",
                ready ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
            TextUtil.loreLine(ready
                ? "Any change to either offer un-readies both"
                : "Click to accept the trade as shown"));
    }

    public static ItemStack theirReadyItem(boolean ready) {
        return displayItem(ready ? Items.LIME_DYE : Items.GRAY_DYE,
            TextUtil.styledBold(ready ? "Partner: READY" : "Partner: not ready",
                ready ? ChatFormatting.GREEN : ChatFormatting.GRAY),
            TextUtil.loreLine("Executes when both sides are ready"));
    }

    public static ItemStack cancelItem() {
        return displayItem(Items.BARRIER,
            TextUtil.styledBold("Cancel Trade", ChatFormatting.RED),
            TextUtil.loreLine("All offered items return to their owners"));
    }

    public static ItemStack filler() {
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(" "));
        return pane;
    }

    public static ItemStack theirOfferMarker() {
        return displayItem(Items.LIGHT_BLUE_STAINED_GLASS_PANE,
            TextUtil.styledBold("Their Offer Area", ChatFormatting.AQUA),
            TextUtil.loreLine("Items they put up appear here"));
    }

    private static String moneyLore(double money) {
        return money > 0 ? CurrencyUtil.format(money) : "no money";
    }

    private static ItemStack displayItem(net.minecraft.world.item.Item type, Component name,
                                          Component loreLine) {
        ItemStack item = new ItemStack(type);
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
        List<Component> lore = new ArrayList<>();
        lore.add(loreLine);
        item.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore));
        return item;
    }
}
