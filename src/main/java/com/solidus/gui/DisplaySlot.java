package com.solidus.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Display-only Slot for virtual Solidus GUIs (Shop, Auction, Sell UI).
 *
 * SECURITY CONTRACT:
 * A slot of this type can NEVER be the source or destination of any item
 * movement driven by vanilla container logic, on the server side. It exists
 * purely to hold a visual ItemStack that the client renders.
 *
 * Three independent guarantees:
 * <ul>
 *   <li>{@link #mayPlace(ItemStack)} returns {@code false} - vanilla can never
 *       insert, merge or swap an item INTO this slot (blocks QUICK_MOVE into
 *       the display area, cursor placement, drag-merge, etc.)</li>
 *   <li>{@link #mayPickup(Player)} returns {@code false} - vanilla can never
 *       take, split, swap or throw an item FROM this slot (blocks PICKUP,
 *       SWAP, THROW, PICKUP_ALL and half-stack pickups)</li>
 *   <li>{@link #set(ItemStack)} is a no-op - even if some future code path
 *       tries to overwrite the display stack through the Slot API, the
 *       layout stays frozen. Initial population happens through the owning
 *       Container directly, never through the Slot.</li>
 * </ul>
 *
 * WHY THIS EXISTS (ghost-item exploit, 2.1.0):
 * The Solidus GUIs open through vanilla {@code MenuType.GENERIC_9x6}, so the
 * CLIENT constructs its own vanilla {@code ChestMenu} for the same screen.
 * That client menu performs optimistic local prediction of every click using
 * vanilla slot rules - which allow picking display items up for free. The
 * server rejected the click but never re-synced, so "ghost items" survived in
 * the client inventory until the next full sync (blocks appeared to break,
 * stolen items vanished on reopen).
 *
 * The complete fix is layered:
 * <ol>
 *   <li>This class hardens the server-side slots (defense in depth).</li>
 *   <li>The ServerPlayerEntityMixin now calls {@code broadcastFullState()}
 *       after every intercepted click, which immediately erases ANY client
 *       prediction mismatch - ghost items now disappear in the same tick
 *       they are created, instead of surviving until the next GUI open.</li>
 * </ol>
 */
public class DisplaySlot extends Slot {

    public DisplaySlot(Container container, int index, int x, int y) {
        super(container, index, x, y);
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false; // Never accept item insertion
    }

    @Override
    public boolean mayPickup(Player player) {
        return false; // Never allow item extraction
    }

    @Override
    public void set(ItemStack stack) {
        // No-op: the display layout is frozen after construction.
        // Initial population goes through the owning Container, not the Slot.
    }
}
