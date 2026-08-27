package com.solidus.shop;

import com.solidus.shop.ShopGUI.GuiSlot;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Dummy Container for the virtual shop GUI.
 *
 * This container exists solely to provide ItemStack data to the
 * ScreenHandler's Slot objects. It does NOT allow any item insertion
 * or removal - all transactions are handled through the economy engine.
 *
 * The container is populated once during GUI construction and remains
 * static throughout the GUI session. Any click events that would
 * normally move items between containers are intercepted and cancelled
 * by the ShopScreenHandler.
 */
public class ShopDummyContainer implements Container {

    private final ItemStack[] items;
    private final List<GuiSlot> guiSlots;

    public ShopDummyContainer(List<GuiSlot> guiSlots) {
        this.guiSlots = guiSlots;
        this.items = new ItemStack[54]; // GENERIC_9x6 = 54 slots

        // Initialize all slots as empty
        for (int i = 0; i < items.length; i++) {
            items[i] = ItemStack.EMPTY;
        }

        // Populate with display items from GUI definition
        for (GuiSlot slot : guiSlots) {
            if (slot.index() >= 0 && slot.index() < items.length) {
                items[slot.index()] = slot.displayStack().copy();
            }
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
        // BLOCK: Items cannot be removed from the virtual shop container
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        // BLOCK: Items cannot be removed from the virtual shop container
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        // BLOCK: Items cannot be placed into the virtual shop container
        // The display is static and controlled entirely by the server
    }

    @Override
    public void setChanged() {
        // No-op: The container never changes
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        // No-op: The container cannot be cleared by player actions
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }
}
