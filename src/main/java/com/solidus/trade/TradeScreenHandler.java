package com.solidus.trade;

import com.solidus.SolidusMod;

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
import java.util.Map;

/**
 * Trade Screen Handler - Server-side GUI handler for the /trade window.
 *
 * <p>Architecture mirrors {@code SellScreenHandler} (manual cursor-based item
 * movement) combined with {@code ShopScreenHandler} click safety:</p>
 *
 * <ul>
 *   <li>MY offer slots (left 3 columns): REAL item placement - pickup, place,
 *       swap, split, shift-click both ways. Items placed here leave the
 *       player's inventory into the session container (escrow).</li>
 *   <li>THEIR offer slots (right 3 columns): display-only mirror of the
 *       partner's live offer - hard-blocked against all movement.</li>
 *   <li>UI slots: READY toggle, money button (chat prompt), cancel.</li>
 *   <li>Player inventory slots (54+): full cursor interaction.</li>
 * </ul>
 *
 * <p>After EVERY accepted interaction the handler refreshes the status
 * displays, mirrors the offer into the partner's container and both menus are
 * fully resynced (the PacketHandler resyncs the clicking player; the handler
 * itself resyncs the partner). Any offer change un-readies BOTH sides - the
 * core anti bait-and-switch guarantee.</p>
 */
public class TradeScreenHandler extends AbstractContainerMenu {

    private final ServerPlayer player;
    private final TradeManager tradeManager;
    private final TradeSession session;
    private final TradeSession.Side side;
    private final TradeContainer container;

    /** The session this window belongs to (used by the manager for routing). */
    public TradeSession session() {
        return session;
    }

    /** Which side of the session this viewer is. */
    public TradeSession.Side side() {
        return side;
    }

    private TradeScreenHandler(int syncId, Inventory playerInventory,
                                ServerPlayer player, TradeManager tradeManager,
                                TradeSession session, TradeSession.Side side) {
        super(MenuType.GENERIC_9x6, syncId);
        this.player = player;
        this.tradeManager = tradeManager;
        this.session = session;
        this.side = side;
        this.container = session.containerOf(side);

        // Populate the fixed UI + their-mirror display items.
        refreshStatusDisplays();

        // The session container: every slot 0-53 is backed by it. The offer
        // slots must stay freely movable, so unlike the display GUIs we do
        // NOT wrap them in DisplaySlot - the clicked() override below IS the
        // permission layer (mirroring SellScreenHandler's approach).
        for (int i = 0; i < 54; i++) {
            this.addSlot(new Slot(container, i, 0, 0));
        }

        // Player inventory slots (standard 9x3 + hotbar layout offset)
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

    /** Opens the trade window for one side of the session. */
    public static void openFor(ServerPlayer viewer, TradeManager tradeManager,
                                TradeSession session, TradeSession.Side side) {
        viewer.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return com.solidus.util.TextUtil.shopTitle(
                    "Trade with " + session.nameOf(TradeSession.other(side)));
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
                return new TradeScreenHandler(syncId, playerInventory,
                    (ServerPlayer) player, tradeManager, session, side);
            }
        });
    }

    // -- Click routing -------------------------------------

    @Override
    public void clicked(int slotIndex, int button, ContainerInput containerInput, Player player) {
        // Defensive: only the player who owns this handler may interact.
        if (player != this.player) {
            SolidusMod.LOGGER.warn("Rejected click on trade GUI of {} from a different actor.",
                this.player.getName().getString());
            return;
        }

        // Session must still be live - a click racing execution/cancel is a no-op.
        if (session.state() != TradeSession.State.ACTIVE) {
            return;
        }

        // Clicks outside the container: return the cursor item to the
        // player's inventory instead of dropping it (dropping during a trade
        // would defeat the scam protection the trade window exists for).
        if (slotIndex == -999) {
            returnCursorToInventory();
            return;
        }

        if (slotIndex < 0) return;

        // Player inventory (54+)
        if (slotIndex >= 54 && slotIndex < this.slots.size()) {
            handleInventorySlotClick(slotIndex, button, containerInput);
            refreshStatusDisplays();
            syncCursorToClient();
            return;
        }

        if (slotIndex >= 54) return; // forged out-of-range index

        // Mirror slots (partner's offer): hard display-only.
        if (isTheirOfferSlot(slotIndex)) {
            syncCursorToClient();
            return;
        }

        // MY offer slots: real item movement.
        if (isMyOfferSlot(slotIndex)) {
            handleMyOfferSlotClick(slotIndex, button, containerInput);
            onMyOfferChanged();
            return;
        }

        // UI slots
        handleUiSlotClick(slotIndex);
        refreshStatusDisplays();
        syncCursorToClient();
    }

    private void handleUiSlotClick(int slotIndex) {
        switch (slotIndex) {
            case TradeGUI.READY_SLOT -> tradeManager.toggleReady(session, side);
            case TradeGUI.CANCEL_SLOT -> tradeManager.cancelSession(session,
                "cancelled", player.getName().getString());
            case TradeGUI.MY_MONEY_SLOT -> {
                // Standard chat-prompt money input (TradeMe-style). The window
                // STAYS OPEN - chat works over any GUI, so the player can see
                // their offer while typing the amount. Closing here would trip
                // removed() and cancel the whole session.
                player.sendSystemMessage(com.solidus.util.TextUtil.styled(
                    "Type the money amount to offer in chat (e.g. 5000), 0 to clear, or 'cancel':",
                    net.minecraft.ChatFormatting.GOLD));
                com.solidus.chat.ChatPrompts prompts = SolidusMod.getChatPrompts();
                if (prompts != null) {
                    prompts.openPrompt(player, (p, message) -> {
                        String trimmed = message.trim();
                        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("الغاء")) {
                            p.sendSystemMessage(com.solidus.util.TextUtil.styled(
                                "Money offer unchanged.", net.minecraft.ChatFormatting.GRAY));
                            return com.solidus.chat.ChatPrompts.CONSUME;
                        }
                        try {
                            double amount = Double.parseDouble(trimmed.replace(",", ""));
                            tradeManager.setMoneyOffer(session, side, amount);
                        } catch (NumberFormatException e) {
                            p.sendSystemMessage(com.solidus.util.TextUtil.error(
                                "'" + trimmed + "' is not a valid number. Money offer unchanged."));
                        }
                        return com.solidus.chat.ChatPrompts.CONSUME;
                    });
                }
            }
            default -> { /* filler / info slots - ignore */ }
        }
    }

    // -- Offer movement (SellScreenHandler pattern) --------

    private void handleMyOfferSlotClick(int slotIndex, int button, ContainerInput containerInput) {
        Slot slot = this.slots.get(slotIndex);
        ItemStack slotStack = slot.getItem();
        ItemStack cursor = getCarried();

        if (containerInput == ContainerInput.QUICK_MOVE) {
            // Shift-click: move item out of the offer back to the inventory.
            if (!slotStack.isEmpty()) {
                ItemStack toMove = slotStack.copy();
                slot.set(ItemStack.EMPTY);
                if (!player.getInventory().add(toMove)) {
                    // Inventory full - back into the offer slot (never drop).
                    slot.set(toMove);
                }
            }
            return;
        }

        if (containerInput == ContainerInput.PICKUP) {
            if (button == 0) {
                // Left click
                if (cursor.isEmpty()) {
                    if (!slotStack.isEmpty()) {
                        setCarried(slotStack.copy());
                        slot.set(ItemStack.EMPTY);
                    }
                } else {
                    if (slotStack.isEmpty()) {
                        slot.set(cursor.copy());
                        setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack)) {
                        int space = slotStack.getMaxStackSize() - slotStack.getCount();
                        int toAdd = Math.min(cursor.getCount(), space);
                        if (toAdd > 0) {
                            slotStack.grow(toAdd);
                            cursor.shrink(toAdd);
                            if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                        } else {
                            slot.set(cursor.copy());
                            setCarried(slotStack.copy());
                        }
                    } else {
                        slot.set(cursor.copy());
                        setCarried(slotStack.copy());
                    }
                }
            } else if (button == 1) {
                // Right click
                if (cursor.isEmpty()) {
                    if (!slotStack.isEmpty()) {
                        int half = (slotStack.getCount() + 1) / 2;
                        ItemStack halfStack = slotStack.copy();
                        halfStack.setCount(half);
                        setCarried(halfStack);
                        slotStack.shrink(half);
                        if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                    }
                } else {
                    if (slotStack.isEmpty()) {
                        ItemStack oneItem = cursor.copy();
                        oneItem.setCount(1);
                        slot.set(oneItem);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack)
                            && slotStack.getCount() < slotStack.getMaxStackSize()) {
                        slotStack.grow(1);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    private void handleInventorySlotClick(int slotIndex, int button, ContainerInput containerInput) {
        Slot slot = this.slots.get(slotIndex);
        ItemStack slotStack = slot.getItem();
        ItemStack cursor = getCarried();

        if (containerInput == ContainerInput.QUICK_MOVE) {
            // Shift-click: move from inventory into my offer area.
            if (!slotStack.isEmpty()) {
                ItemStack remaining = moveToMyOffer(slotStack);
                int moved = slotStack.getCount() - remaining.getCount();
                if (moved > 0) {
                    slotStack.shrink(moved);
                    if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                    onMyOfferChanged();
                }
            }
            return;
        }

        if (containerInput == ContainerInput.PICKUP) {
            if (button == 0) {
                if (cursor.isEmpty()) {
                    if (!slotStack.isEmpty()) {
                        setCarried(slotStack.copy());
                        slot.set(ItemStack.EMPTY);
                    }
                } else {
                    if (slotStack.isEmpty()) {
                        slot.set(cursor.copy());
                        setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack)) {
                        int space = slotStack.getMaxStackSize() - slotStack.getCount();
                        int toAdd = Math.min(cursor.getCount(), space);
                        if (toAdd > 0) {
                            slotStack.grow(toAdd);
                            cursor.shrink(toAdd);
                            if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                        } else {
                            slot.set(cursor.copy());
                            setCarried(slotStack.copy());
                        }
                    } else {
                        slot.set(cursor.copy());
                        setCarried(slotStack.copy());
                    }
                }
            } else if (button == 1) {
                if (cursor.isEmpty()) {
                    if (!slotStack.isEmpty()) {
                        int half = (slotStack.getCount() + 1) / 2;
                        ItemStack halfStack = slotStack.copy();
                        halfStack.setCount(half);
                        setCarried(halfStack);
                        slotStack.shrink(half);
                        if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                    }
                } else {
                    if (slotStack.isEmpty()) {
                        ItemStack oneItem = cursor.copy();
                        oneItem.setCount(1);
                        slot.set(oneItem);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack)
                            && slotStack.getCount() < slotStack.getMaxStackSize()) {
                        slotStack.grow(1);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    /** Places a stack into my offer area (merge then empty slots). */
    private ItemStack moveToMyOffer(ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slotIndex : TradeGUI.MY_OFFER_SLOTS) {
            if (remaining.isEmpty()) break;
            ItemStack slotStack = container.getItem(slotIndex);
            if (!slotStack.isEmpty() && canStackItems(remaining, slotStack)
                && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int space = slotStack.getMaxStackSize() - slotStack.getCount();
                int toAdd = Math.min(remaining.getCount(), space);
                slotStack.grow(toAdd);
                remaining.shrink(toAdd);
            }
        }
        for (int slotIndex : TradeGUI.MY_OFFER_SLOTS) {
            if (remaining.isEmpty()) break;
            if (container.getItem(slotIndex).isEmpty()) {
                container.setItem(slotIndex, remaining.copy());
                remaining.setCount(0);
            }
        }
        return remaining;
    }

    private void returnCursorToInventory() {
        ItemStack cursor = getCarried();
        if (cursor.isEmpty()) return;
        setCarried(ItemStack.EMPTY);
        if (!player.getInventory().add(cursor)) {
            // Inventory full: offer slot is the safe fallback (never the ground).
            ItemStack rest = moveToMyOffer(cursor);
            if (!rest.isEmpty()) {
                player.drop(rest, false);
            }
        }
        refreshStatusDisplays();
        syncCursorToClient();
    }

    // -- Status + mirroring --------------------------------

    /** Called after MY offer content changed: un-ready both, mirror, resync. */
    private void onMyOfferChanged() {
        session.unreadyBoth();
        refreshStatusDisplays();
        tradeManager.mirrorOffer(session, side);
        tradeManager.refreshPartnerView(session, side);
        syncCursorToClient();
    }

    /** Rebuilds every fixed UI display item from the session state. */
    public void refreshStatusDisplays() {
        TradeSession.Side other = TradeSession.other(side);

        container.setItem(TradeGUI.TITLE_SLOT, TradeGUI.titleItem());
        container.setItem(TradeGUI.MY_INFO_SLOT, TradeGUI.myInfoItem(
            player.getName().getString(), session.moneyOf(side), session.isReady(side)));
        container.setItem(TradeGUI.THEIR_INFO_SLOT, TradeGUI.theirInfoItem(
            session.nameOf(other), session.moneyOf(other)));
        container.setItem(TradeGUI.MY_MONEY_SLOT, TradeGUI.myMoneyItem(session.moneyOf(side)));
        container.setItem(TradeGUI.THEIR_MONEY_SLOT, TradeGUI.theirMoneyItem(session.moneyOf(other)));
        container.setItem(TradeGUI.READY_SLOT, TradeGUI.readyItem(session.isReady(side)));
        container.setItem(TradeGUI.THEIR_READY_SLOT, TradeGUI.theirReadyItem(session.isReady(other)));
        container.setItem(TradeGUI.CANCEL_SLOT, TradeGUI.cancelItem());

        for (int slot : TradeGUI.FILLER_SLOTS) {
            container.setItem(slot, TradeGUI.filler());
        }
    }

    /** Re-mirrors the partner's live offer into MY right-hand columns. */
    public void refreshMirrorFromPartner() {
        TradeContainer partnerContainer = session.containerOf(TradeSession.other(side));
        int[] mySlots = TradeGUI.MY_OFFER_SLOTS;        // partner's own (left) slots
        int[] mirrorSlots = TradeGUI.THEIR_OFFER_SLOTS; // my mirror (right) slots
        for (int i = 0; i < mySlots.length; i++) {
            container.setItem(mirrorSlots[i], partnerContainer.getItem(mySlots[i]).copy());
        }
    }

    private boolean isMyOfferSlot(int slotIndex) {
        for (int s : TradeGUI.MY_OFFER_SLOTS) {
            if (s == slotIndex) return true;
        }
        return false;
    }

    private boolean isTheirOfferSlot(int slotIndex) {
        for (int s : TradeGUI.THEIR_OFFER_SLOTS) {
            if (s == slotIndex) return true;
        }
        return false;
    }

    private static boolean canStackItems(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem()
            && (!a.isStackable() || ItemStack.isSameItemSameComponents(a, b));
    }

    private void syncCursorToClient() {
        // Reset the client's optimistic cursor prediction (2.1.0 ghost-item
        // guarantee): the PacketHandler full resync covers container slots,
        // this covers the carried stack.
        player.containerMenu.broadcastFullState();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // blocked - all movement is manual
    }

    @Override
    public boolean stillValid(Player player) {
        // The session lifecycle (not vanilla block distance) governs validity.
        return session.state() == TradeSession.State.ACTIVE
            || session.state() == TradeSession.State.EXECUTING;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // GUI closed (ESC or otherwise) => cancel the trade and return items.
        // The manager is idempotent: a session that already finished/finished
        // executing is ignored.
        if (session.state() == TradeSession.State.ACTIVE) {
            tradeManager.cancelSession(session, "closed the trade window",
                this.player.getName().getString());
        }
    }
}
