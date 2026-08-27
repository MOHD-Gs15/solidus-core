package com.solidus.auction;

import com.solidus.auction.AuctionGUI.GuiSlot;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Dummy Container for the Auction House GUI.
 * Same architecture as ShopDummyContainer - items are Display-Only.
 */
public class AuctionDummyContainer implements Container {

    private final ItemStack[] items;

    public AuctionDummyContainer(List<GuiSlot> guiSlots) {
        this.items = new ItemStack[54];
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }
        for (GuiSlot slot : guiSlots) {
            if (slot.index() >= 0 && slot.index() < items.length) {
                items[slot.index()] = slot.displayStack().copy();
            }
        }
    }

    @Override
    public int getContainerSize() { return items.length; }

    @Override
    public boolean isEmpty() {
        for (ItemStack item : items) {
            if (!item.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return (slot >= 0 && slot < items.length) ? items[slot] : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }

    @Override
    public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }

    @Override
    public void setItem(int slot, ItemStack stack) { /* BLOCK */ }

    @Override
    public void setChanged() { /* No-op */ }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() { /* No-op */ }

    @Override
    public int getMaxStackSize() { return 64; }
}
