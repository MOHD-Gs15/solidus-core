package com.solidus.sell;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Sell Container - Real container that stores items placed by players
 * in the sell GUI.
 *
 * Unlike ShopDummyContainer/AuctionDummyContainer which are display-only,
 * this container actually stores ItemStacks and allows item insertion
 * and removal for the sell input area (slots 9-53).
 *
 * Slots 0-8 are reserved for UI elements (info, close button, fillers)
 * and are populated during GUI construction. These slots block item
 * insertion via the SellScreenHandler's ReadOnlySlot wrapper.
 *
 * Slots 9-53 are the input area where players place items to sell.
 */
public class SellContainer implements Container {

    private final ItemStack[] items;

    public SellContainer() {
        this.items = new ItemStack[54]; // GENERIC_9x6 = 54 slots
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }
    }

    /**
     * Checks if a slot index is in the input area (slots 9-53).
     * Only input area slots accept item placement.
     */
    public boolean isInputSlot(int slot) {
        return slot >= 9 && slot < 54;
    }

    @Override
    public int getContainerSize() {
        return items.length;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 9; i < items.length; i++) {
            if (!items[i].isEmpty()) return false;
        }
        return true;
    }

    /**
     * Checks if there are any items in the input area (slots 9-53).
     */
    public boolean hasInputItems() {
        for (int i = 9; i < items.length; i++) {
            if (!items[i].isEmpty()) return true;
        }
        return false;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot >= 0 && slot < items.length) {
            return items[slot];
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= items.length) return ItemStack.EMPTY;
        ItemStack stack = items[slot];
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = stack.split(amount);
        if (stack.isEmpty()) {
            items[slot] = ItemStack.EMPTY;
        }
        setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= items.length) return ItemStack.EMPTY;
        ItemStack stack = items[slot];
        items[slot] = ItemStack.EMPTY;
        return stack;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.length) return;
        items[slot] = stack;
        setChanged();
    }

    @Override
    public void setChanged() {
        // No-op: State changes are tracked via the ScreenHandler
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }
}
