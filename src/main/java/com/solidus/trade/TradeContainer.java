package com.solidus.trade;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Trade Container - Real container backing an open trade session.
 *
 * <p>Exactly the {@code SellContainer} pattern: a 54-slot server-side
 * container that ACTUALLY stores the items players offer (they leave the
 * player's inventory the moment they are placed), while the fixed UI slots
 * (headers, buttons, status indicators) are populated at construction and
 * later mutated by the session status logic.</p>
 *
 * <p>Slot map (see {@link TradeGUI}): offer slots hold real trade items;
 * every other slot is UI (display items, buttons, status). UI slot contents
 * are managed by {@link TradeScreenHandler} / {@link TradeManager}.</p>
 */
public class TradeContainer implements Container {

    private final ItemStack[] items;

    public TradeContainer() {
        this.items = new ItemStack[54]; // GENERIC_9x6 = 54 slots
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }
    }

    @Override
    public int getContainerSize() {
        return items.length;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack item : items) {
            if (!item.isEmpty()) return false;
        }
        return true;
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
        // No-op: state changes are tracked via the ScreenHandler + full resyncs.
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
