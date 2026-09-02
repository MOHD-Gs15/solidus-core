package com.solidus.sell;

import com.solidus.SolidusMod;
import com.solidus.api.EconomyHooks;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.TransactionLog;
import com.solidus.gui.DisplaySlot;
import com.solidus.sell.SellGUI.GuiSlot;
import com.solidus.shop.ShopManager;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sell Screen Handler - Server-side GUI handler for the sell window.
 *
 * CRITICAL ARCHITECTURE DIFFERENCES from ShopScreenHandler/AuctionScreenHandler:
 *
 * Unlike the shop and auction GUIs which are display-only (all item movement
 * is blocked), the sell GUI ALLOWS item placement in the input area (slots 9-53).
 * Players can place items from their inventory into these slots, and when the
 * GUI is closed, sellable items are sold and unsellable items are returned.
 *
 * Click Handling Strategy:
 * - UI slots (0-8): Intercepted by PacketHandler, handled by handleUISlotClick()
 * - Input slots (9-53): Handled manually in clicked() - implements full cursor-based
 *   item movement (pickup, place, swap, split, shift-click)
 * - Player inventory slots (54+): Also handled manually for cursor interaction
 *
 * The PacketHandler returns true for ALL sell GUI clicks (like shop/auction),
 * so ALL item movement must be implemented manually here. This is necessary
 * because the PacketHandler's rate limiting and packet cancellation applies
 * uniformly to all Solidus GUIs.
 *
 * Shulker Box Handling:
 * When processing items on close, shulker boxes are examined:
 * - Sellable items inside shulker boxes are sold
 * - Unsellable items inside shulker boxes remain in the shulker box
 * - The shulker box itself is returned to the player's inventory
 * - If the shulker box becomes empty, it is still returned
 */
public class SellScreenHandler extends AbstractContainerMenu {

    private final ServerPlayer player;
    private final ShopManager shopManager;
    private final SellContainer sellContainer;
    private final Map<Integer, GuiSlot> uiSlotMap = new HashMap<>();

    /**
     * Opens a new sell screen for the player.
     */
    public static void openScreen(ServerPlayer player, List<GuiSlot> uiSlots, ShopManager shopManager) {
        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return TextUtil.shopTitle("Sell Items");
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
                return new SellScreenHandler(syncId, playerInventory,
                    (ServerPlayer) player, uiSlots, shopManager);
            }
        });
    }

    private SellScreenHandler(int syncId, Inventory playerInventory,
                               ServerPlayer player, List<GuiSlot> uiSlots,
                               ShopManager shopManager) {
        super(MenuType.GENERIC_9x6, syncId);
        this.player = player;
        this.shopManager = shopManager;
        this.sellContainer = new SellContainer();

        // Build UI slot map for click lookup
        for (GuiSlot guiSlot : uiSlots) {
            uiSlotMap.put(guiSlot.index(), guiSlot);
        }

        // Populate UI slots in the container (slots 0-8)
        for (GuiSlot guiSlot : uiSlots) {
            sellContainer.setItem(guiSlot.index(), guiSlot.displayStack().copy());
        }

        // Add sell container slots:
        // Slots 0-8: UI elements (read-only, block item placement)
        // DisplaySlot: mayPlace/mayPickup/set are all blocked - no vanilla
        // code path can ever move an item through these slots.
        for (int i = 0; i < 9; i++) {
            this.addSlot(new DisplaySlot(sellContainer, i, 0, 0));
        }

        // Slots 9-53: Input area (accept item placement)
        for (int i = 9; i < 54; i++) {
            this.addSlot(new Slot(sellContainer, i, 0, 0));
        }

        // Add player inventory slots (standard 9x3 + hotbar layout offset)
        int playerInvOffset = 54; // After the 54 container slots
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

    /**
     * Handles UI slot clicks (slots 0-8).
     * Called by the PacketHandler when a click is detected on a UI slot.
     */
    public void handleUISlotClick(int slotIndex) {
        GuiSlot guiSlot = uiSlotMap.get(slotIndex);
        if (guiSlot == null) return;

        if (guiSlot.type() == GuiSlot.Type.NAVIGATION) {
            String action = guiSlot.actionKey();
            if (SellGUI.NAV_CLOSE.equals(action)) {
                // TODO: 26.1.x - Verify player.closeContainer() still exists (not renamed to closeMenu)
                player.closeContainer();
            }
        }
    }

    /**
     * Complete rewrite of slot click handling.
     *
     * Unlike ShopScreenHandler which blocks all item movement, this handler
     * implements full cursor-based item movement for the sell GUI. Players
     * can place items in the input area (slots 9-53) and retrieve them.
     *
     * Item Movement:
     * - Left-click on player inventory item: Pick up entire stack to cursor
     * - Left-click on empty input slot: Place cursor item
     * - Left-click on occupied input slot: Swap cursor with slot
     * - Right-click on player inventory item: Pick up half stack
     * - Right-click on empty input slot: Place one item from cursor
     * - Right-click on occupied input slot: Place one item from cursor
     * - Shift+Left-click on inventory item: Move to input area
     * - Shift+Left-click on input area item: Move back to inventory
     * - Click outside (slot -999): Drop cursor item
     */
    @Override
    // TODO: 26.1.x - ClickType -> ContainerInput; button param may be removed (absorbed into ContainerInput)
    public void clicked(int slotIndex, int button, ContainerInput containerInput, Player player) {
        // Defensive: only the player who owns this handler may interact.
        // Today the only reachable caller (PacketHandler) routes each click
        // through the clicking player's own menu, but this invariant must not
        // depend on the dispatcher staying correct - mirror ShopScreenHandler.
        if (player != this.player) {
            SolidusMod.LOGGER.warn("Rejected click on sell GUI of {} from a different actor.",
                this.player.getName().getString());
            return;
        }

        // Handle clicks outside the container (drop cursor item)
        if (slotIndex == -999) {
            ItemStack cursor = getCarried();
            if (!cursor.isEmpty()) {
                player.drop(cursor, true);
                setCarried(ItemStack.EMPTY);
                syncCursorToClient();
            }
            return;
        }

        if (slotIndex < 0) return;

        // Handle UI slots (0-8) - only close button is interactive
        if (slotIndex < 9) {
            GuiSlot guiSlot = uiSlotMap.get(slotIndex);
            if (guiSlot != null && guiSlot.type() == GuiSlot.Type.NAVIGATION) {
                String action = guiSlot.actionKey();
                if (SellGUI.NAV_CLOSE.equals(action)) {
                    // this.player is ServerPlayer which exposes closeContainer() as public
                    this.player.closeContainer();
                }
            }
            // Clear client's optimistic cursor for UI slot clicks
            syncCursorToClient();
            return; // Block all interaction with UI slots
        }

        // Handle input area slots (9-53)
        if (slotIndex >= 9 && slotIndex < 54) {
            handleInputSlotClick(slotIndex, button, containerInput);
            syncCursorToClient();
            return;
        }

        // Handle player inventory slots (54+)
        // SECURITY FIX (audit 2.1.3): slotIndex comes straight from a network
        // packet and the mixin runs BEFORE vanilla's bounds validation, so a
        // forged slotNum >= this.slots.size() reached slots.get() and threw an
        // uncaught IndexOutOfBoundsException through the mixin - kicking the
        // sender. Hard upper bound, mirroring ShopScreenHandler.
        if (slotIndex >= 54 && slotIndex < this.slots.size()) {
            handleInventorySlotClick(slotIndex, button, containerInput);
            syncCursorToClient();
            return;
        }
    }

    /**
     * Handles clicks on input area slots (9-53).
     */
    private void handleInputSlotClick(int slotIndex, int button, ContainerInput containerInput) {
        Slot slot = this.slots.get(slotIndex);
        ItemStack slotStack = slot.getItem();
        ItemStack cursor = getCarried();

        // TODO: 26.1.x - ContainerInput replaces ClickType. The old ClickType.QUICK_MOVE
        //  likely corresponds to ContainerInput.QUICK_MOVE or a similar shift-click variant.
        //  The old ClickType.PICKUP + button=0 (left) / button=1 (right) likely maps to
        //  ContainerInput.PICKUP (or LEFT_CLICK/RIGHT_CLICK). Verify at compile time.
        if (containerInput == ContainerInput.QUICK_MOVE) {
            // Shift-click: move item from input area back to player inventory
            if (!slotStack.isEmpty()) {
                ItemStack toMove = slotStack.copy();
                slot.set(ItemStack.EMPTY);
                returnItemToPlayer(toMove);
            }
            return;
        }

        // TODO: 26.1.x - ClickType.PICKUP -> ContainerInput.PICKUP (verify at compile time)
        if (containerInput == ContainerInput.PICKUP) {
            if (button == 0) {
                // Left click
                if (cursor.isEmpty()) {
                    // Pick up the entire stack from the slot
                    if (!slotStack.isEmpty()) {
                        setCarried(slotStack.copy());
                        slot.set(ItemStack.EMPTY);
                    }
                } else {
                    // Place cursor item in the slot
                    if (slotStack.isEmpty()) {
                        slot.set(cursor.copy());
                        setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack)) {
                        // Merge cursor into slot
                        int space = slotStack.getMaxStackSize() - slotStack.getCount();
                        int toAdd = Math.min(cursor.getCount(), space);
                        if (toAdd > 0) {
                            slotStack.grow(toAdd);
                            cursor.shrink(toAdd);
                            if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                        } else {
                            // No space - swap
                            slot.set(cursor.copy());
                            setCarried(slotStack.copy());
                        }
                    } else {
                        // Different items - swap
                        slot.set(cursor.copy());
                        setCarried(slotStack.copy());
                    }
                }
            } else if (button == 1) {
                // Right click
                if (cursor.isEmpty()) {
                    // Pick up half the stack
                    if (!slotStack.isEmpty()) {
                        int half = (slotStack.getCount() + 1) / 2;
                        ItemStack halfStack = slotStack.copy();
                        halfStack.setCount(half);
                        setCarried(halfStack);
                        slotStack.shrink(half);
                        if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                    }
                } else {
                    // Place one item from cursor
                    if (slotStack.isEmpty()) {
                        ItemStack oneItem = cursor.copy();
                        oneItem.setCount(1);
                        slot.set(oneItem);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack) && slotStack.getCount() < slotStack.getMaxStackSize()) {
                        slotStack.grow(1);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    }
                    // If items don't match or slot is full, do nothing
                }
            }
        }
    }

    /**
     * Handles clicks on player inventory slots (54+).
     */
    private void handleInventorySlotClick(int slotIndex, int button, ContainerInput containerInput) {
        Slot slot = this.slots.get(slotIndex);
        ItemStack slotStack = slot.getItem();
        ItemStack cursor = getCarried();

        // TODO: 26.1.x - ContainerInput replaces ClickType. Verify QUICK_MOVE constant name.
        if (containerInput == ContainerInput.QUICK_MOVE) {
            // Shift-click: move item from player inventory to input area
            if (!slotStack.isEmpty()) {
                ItemStack remaining = moveItemToInputArea(slotStack);
                if (remaining.getCount() < slotStack.getCount()) {
                    int moved = slotStack.getCount() - remaining.getCount();
                    slotStack.shrink(moved);
                    if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                }
            }
            return;
        }

        // TODO: 26.1.x - ClickType.PICKUP -> ContainerInput.PICKUP (verify at compile time)
        if (containerInput == ContainerInput.PICKUP) {
            if (button == 0) {
                // Left click
                if (cursor.isEmpty()) {
                    // Pick up entire stack from inventory
                    if (!slotStack.isEmpty()) {
                        setCarried(slotStack.copy());
                        slot.set(ItemStack.EMPTY);
                    }
                } else {
                    // Place cursor item back into inventory slot
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
                            // No space - swap
                            slot.set(cursor.copy());
                            setCarried(slotStack.copy());
                        }
                    } else {
                        // Different items - swap
                        slot.set(cursor.copy());
                        setCarried(slotStack.copy());
                    }
                }
            } else if (button == 1) {
                // Right click
                if (cursor.isEmpty()) {
                    // Pick up half the stack
                    if (!slotStack.isEmpty()) {
                        int half = (slotStack.getCount() + 1) / 2;
                        ItemStack halfStack = slotStack.copy();
                        halfStack.setCount(half);
                        setCarried(halfStack);
                        slotStack.shrink(half);
                        if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                    }
                } else {
                    // Place one item from cursor
                    if (slotStack.isEmpty()) {
                        ItemStack oneItem = cursor.copy();
                        oneItem.setCount(1);
                        slot.set(oneItem);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    } else if (canStackItems(cursor, slotStack) && slotStack.getCount() < slotStack.getMaxStackSize()) {
                        slotStack.grow(1);
                        cursor.shrink(1);
                        if (cursor.isEmpty()) setCarried(ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    /**
     * Moves an item stack to the first available input area slot.
     * Returns the remaining items that couldn't be placed.
     */
    private ItemStack moveItemToInputArea(ItemStack stack) {
        ItemStack remaining = stack.copy();

        // First, try to merge with existing stacks of the same type
        for (int i = 9; i < 54 && !remaining.isEmpty(); i++) {
            ItemStack slotStack = sellContainer.getItem(i);
            if (!slotStack.isEmpty() && canStackItems(remaining, slotStack)
                && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int space = slotStack.getMaxStackSize() - slotStack.getCount();
                int toAdd = Math.min(remaining.getCount(), space);
                slotStack.grow(toAdd);
                remaining.shrink(toAdd);
            }
        }

        // Then, try to place in empty slots
        for (int i = 9; i < 54 && !remaining.isEmpty(); i++) {
            ItemStack slotStack = sellContainer.getItem(i);
            if (slotStack.isEmpty()) {
                int toPlace = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                ItemStack newStack = remaining.copy();
                newStack.setCount(toPlace);
                sellContainer.setItem(i, newStack);
                remaining.shrink(toPlace);
            }
        }

        return remaining;
    }

    /**
     * Returns an item to the player's inventory.
     * If the inventory is full, the item is dropped on the ground.
     */
    private void returnItemToPlayer(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) {
            // Inventory full - drop at player's feet
            player.drop(stack, false);
        }
    }

    /**
     * Syncs the cursor (carried) item state to the client.
     *
     * This is CRITICAL for the sell GUI because the PacketHandler intercepts
     * and cancels all click packets for Solidus GUIs. After cancellation,
     * broadcastChanges() syncs slot contents but NOT the cursor item.
     * Without explicit cursor syncing, the client would show a "ghost cursor"
     * from its optimistic click processing that doesn't match the server state.
     *
     * MC 26.1.x: the cursor (carried) stack has a DEDICATED packet:
     * ClientboundSetCursorItemPacket. Sending the legacy-style
     * ClientboundContainerSetSlotPacket with slot -1 is INVALID here - the
     * vanilla client calls AbstractContainerMenu.setItem(-1, ...) which throws
     * IndexOutOfBoundsException (Index -1 out of bounds) and instantly
     * disconnects the player ("Too many suspicious packets" / network
     * protocol error). This was the crash that kicked players out of the
     * sell GUI on every item placement in 2.1.1.
     */
    private void syncCursorToClient() {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket(
            this.getCarried()));
    }

    /**
     * Checks if two ItemStacks can be stacked together.
     */
    private boolean canStackItems(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /**
     * Blocks quick-move (shift-click) from moving items out of UI slots.
     * Implements shift-click for input area and player inventory.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // This method is called by vanilla for shift-clicks, but since
        // the PacketHandler intercepts all clicks for Solidus GUIs,
        // this method should never be reached. We implement it as a safety net.

        // Bounds guard (audit 2.1.3): index is an int surface that a future
        // caller could pass unvalidated; slots.get() must stay in range.
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }

        if (index < 9) {
            // UI slot - block
            return ItemStack.EMPTY;
        }

        if (index >= 9 && index < 54) {
            // Input area - move to player inventory
            Slot slot = this.slots.get(index);
            if (slot != null && slot.hasItem()) {
                ItemStack slotStack = slot.getItem();
                ItemStack toMove = slotStack.copy();
                slot.set(ItemStack.EMPTY);
                returnItemToPlayer(toMove);
                return toMove;
            }
        } else {
            // Player inventory - move to input area
            Slot slot = this.slots.get(index);
            if (slot != null && slot.hasItem()) {
                ItemStack slotStack = slot.getItem();
                ItemStack remaining = moveItemToInputArea(slotStack);
                int moved = slotStack.getCount() - remaining.getCount();
                if (moved > 0) {
                    slotStack.shrink(moved);
                    if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
                    ItemStack result = slotStack.copy();
                    result.setCount(moved);
                    return result;
                }
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * Called when the player closes the screen.
     * This is where all items in the sell container are processed:
     *
     * 1. Sellable items are sold (balance added via async chain)
     * 2. Unsellable items are returned to the player's inventory
     * 3. If inventory is full, items are dropped on the ground
     * 4. Shulker boxes are processed: sellable contents are sold,
     *    unsellable contents remain in the shulker box
     * 5. The cursor item is returned to the player
     */
    @Override
    public void removed(Player player) {
        // Return cursor item to player
        ItemStack cursor = getCarried();
        if (!cursor.isEmpty()) {
            returnItemToPlayer(cursor);
            setCarried(ItemStack.EMPTY);
        }

        // Transaction hook veto (Solidus 2.1.0+): runs BEFORE anything is
        // consumed. On denial, every placed item is handed straight back and
        // no payout happens - the GUI simply acts as a return-to-sender.
        SolidusTransactionHook.Decision hookDecision = EconomyHooks.allow(hook ->
            hook.allowShopSell(this.player.getUUID(), this.player.getName().getString()));
        if (!hookDecision.allowed()) {
            for (int i = 9; i < 54; i++) {
                ItemStack stack = sellContainer.getItem(i);
                if (!stack.isEmpty()) {
                    returnItemToPlayer(stack);
                    sellContainer.setItem(i, ItemStack.EMPTY);
                }
            }
            this.player.sendSystemMessage(TextUtil.error(hookDecision.reason()));
            super.removed(player);
            return;
        }

        // Process all items in the input area (slots 9-53)
        double totalEarnings = 0.0;
        int totalItemsSold = 0;
        java.util.List<ItemStack> unsellableItems = new java.util.ArrayList<>();
        // Snapshots of sellable stacks consumed for this payout. If the async
        // balance credit later fails, these are handed back to the player
        // instead of being silently destroyed.
        java.util.List<ItemStack> soldForCredit = new java.util.ArrayList<>();

        for (int i = 9; i < 54; i++) {
            ItemStack stack = sellContainer.getItem(i);
            if (stack.isEmpty()) continue;

            // Check if this is a shulker box
            if (isShulkerBox(stack)) {
                // Process shulker box contents
                ShulkerProcessResult result = processShulkerBox(stack);
                totalEarnings += result.earnings;
                totalItemsSold += result.itemsSold;

                // Audit 2.1.3: snapshot exactly what was consumed - the sold
                // contents, plus the (emptied) box itself when it was sold too.
                // Previously shulker payouts were excluded from the failure
                // restore, silently destroying contents + box on credit failure.
                if (result.earnings > 0) {
                    soldForCredit.addAll(result.soldStacks);
                }

                if (result.hasRemainingItems()) {
                    // Shulker box still has unsellable items - return it
                    unsellableItems.add(result.updatedShulkerBox);
                } else if (result.earnings > 0) {
                    // All items in the shulker were sold
                    // The shulker box itself might be sellable too
                    ShopManager.ShopItem shulkerShopItem = shopManager.findItem(getMaterialName(stack));
                    if (shulkerShopItem != null && shulkerShopItem.sellPrice() > 0) {
                        totalEarnings += shulkerShopItem.sellPrice();
                        totalItemsSold += 1;
                        // The emptied box is consumed - snapshot it for restore.
                        soldForCredit.add(result.updatedShulkerBox.copy());
                    } else {
                        // Return empty shulker box
                        ItemStack emptyShulker = stack.copy();
                        emptyShulker.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                        unsellableItems.add(emptyShulker);
                    }
                } else {
                    // No items were sold from this shulker - return as-is
                    unsellableItems.add(result.updatedShulkerBox);
                }
            } else {
                // Regular item - check if sellable
                String material = getMaterialName(stack);
                ShopManager.ShopItem shopItem = shopManager.findItem(material);

                if (shopItem != null && shopItem.sellPrice() > 0) {
                    // Sell the entire stack
                    double value = CurrencyUtil.round(shopItem.sellPrice() * stack.getCount());
                    totalEarnings += value;
                    totalItemsSold += stack.getCount();
                    // Recovery snapshot - see the balance-failure handler below.
                    soldForCredit.add(stack.copy());
                } else {
                    // Not sellable - return to player
                    unsellableItems.add(stack.copy());
                }
            }

            sellContainer.setItem(i, ItemStack.EMPTY);
        }

        // Return unsellable items to the player's inventory
        for (ItemStack item : unsellableItems) {
            returnItemToPlayer(item);
        }

        // Add earnings to player's balance (async)
        if (totalEarnings > 0) {
            EconomyEngine economyEngine = shopManager.getEconomyEngine();
            BalanceManager balanceManager = economyEngine.getBalanceManager();

            // Capture effectively-final snapshots for the lambda below.
            // The local variables totalEarnings and totalItemsSold are reassigned
            // inside loops above, so they cannot be directly referenced from a
            // lambda in Java 25+ (which enforces strict effectively-final rules).
            final double finalTotalEarnings = totalEarnings;
            final int finalTotalItemsSold = totalItemsSold;

            // Use this.player (ServerPlayer) instead of the method parameter
            // (Player), since BalanceManager.addBalance requires ServerPlayer
            // and player.level().getServer() requires ServerPlayer.
            final java.util.List<ItemStack> finalSoldStacks = soldForCredit;
            balanceManager.addBalance(this.player, finalTotalEarnings).thenAccept(newBalance -> {
                this.player.level().getServer().execute(() -> {
                    if (newBalance < 0) {
                        // Crediting failed - RESTORE every snapshot taken of the items
                        // that were consumed for this payout. Previously these items
                        // were destroyed with no compensation. Anything that does not
                        // fit back into the inventory is dropped at the player's feet.
                        SolidusMod.LOGGER.error(
                            "CRITICAL: Sell GUI balance add failed for {}! Restoring {} consumed stack(s). Amount: {}",
                            this.player.getName().getString(), finalSoldStacks.size(), finalTotalEarnings);
                        for (ItemStack restore : finalSoldStacks) {
                            returnItemToPlayer(restore);
                        }
                        this.player.sendSystemMessage(TextUtil.error(
                            "Transaction error. Your placed items have been returned. Please try again."));
                        return;
                    }

                    // Log transaction
                    economyEngine.getTransactionLog().log(
                        TransactionLog.Type.SHOP_SELL,
                        this.player.getUUID(), this.player.getName().getString(),
                        null, null,
                        finalTotalEarnings, "VARIOUS", finalTotalItemsSold,
                        "Sold " + finalTotalItemsSold + " items via sell GUI for " + CurrencyUtil.format(finalTotalEarnings)
                    );

                    // Hook notification (Solidus 2.1.0+): sell GUI payout settled.
                    EconomyHooks.notifyHooks(hook ->
                        hook.afterShopSell(this.player.getUUID(), this.player.getName().getString(), finalTotalEarnings));

                    // Success notification
                    this.player.sendSystemMessage(
                        TextUtil.success("Sold " + finalTotalItemsSold + " item(s) for ")
                            .append(TextUtil.currency(CurrencyUtil.format(finalTotalEarnings)))
                            .append(TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                            .append(TextUtil.currency(CurrencyUtil.format(newBalance)))
                    );
                });
            }).exceptionally(ex -> {
                // Audit 2.1.3: an exceptionally-completed payout future
                // skipped thenAccept entirely - the consumed stacks were
                // never restored. Restore them here.
                this.player.level().getServer().execute(() -> {
                    SolidusMod.LOGGER.error(
                        "Sell GUI payout future failed for {} - restoring {} consumed stack(s).",
                        this.player.getName().getString(), finalSoldStacks.size(), ex);
                    for (ItemStack restore : finalSoldStacks) {
                        returnItemToPlayer(restore);
                    }
                    this.player.sendSystemMessage(TextUtil.error(
                        "Transaction error. Your placed items have been returned. Please try again."));
                });
                return null;
            });
        } else if (unsellableItems.isEmpty()) {
            // No items were placed in the GUI
            this.player.sendSystemMessage(TextUtil.styled("No items to sell.", ChatFormatting.GRAY));
        } else {
            // Some items couldn't be sold
            this.player.sendSystemMessage(TextUtil.warning(
                "None of the placed items could be sold. They have been returned to your inventory."));
        }

        super.removed(player);
    }

    // -- Shulker Box Processing ------------------------------

    /**
     * Result of processing a shulker box's contents.
     */
    private record ShulkerProcessResult(
        double earnings,
        int itemsSold,
        ItemStack updatedShulkerBox,
        java.util.List<ItemStack> soldStacks
    ) {
        boolean hasRemainingItems() {
            ItemContainerContents contents = updatedShulkerBox.get(DataComponents.CONTAINER);
            if (contents == null) return false;
            net.minecraft.core.NonNullList<ItemStack> checkItems =
                net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
            contents.copyInto(checkItems);
            for (ItemStack s : checkItems) {
                if (!s.isEmpty()) return true;
            }
            return false;
        }
    }

    /**
     * Processes the contents of a shulker box, selling sellable items
     * and keeping unsellable items inside.
     */
    private ShulkerProcessResult processShulkerBox(ItemStack shulkerStack) {
        ItemContainerContents contents = shulkerStack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return new ShulkerProcessResult(0, 0, shulkerStack.copy(), java.util.List.of());
        }

        // Read items from the shulker box into a mutable list
        net.minecraft.core.NonNullList<ItemStack> shulkerItems =
            net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
        contents.copyInto(shulkerItems);

        double earnings = 0.0;
        int itemsSold = 0;
        java.util.List<ItemStack> soldStacks = new java.util.ArrayList<>();

        // Process each item in the shulker box
        for (int i = 0; i < shulkerItems.size(); i++) {
            ItemStack item = shulkerItems.get(i);
            if (item.isEmpty()) continue;

            String material = getMaterialName(item);
            ShopManager.ShopItem shopItem = shopManager.findItem(material);

            if (shopItem != null && shopItem.sellPrice() > 0) {
                // Sell the entire stack
                double value = CurrencyUtil.round(shopItem.sellPrice() * item.getCount());
                earnings += value;
                itemsSold += item.getCount();
                soldStacks.add(item.copy()); // snapshot for restore (audit 2.1.3)
                shulkerItems.set(i, ItemStack.EMPTY); // Remove sold item
            }
            // Unsellable items remain in their slot
        }

        // Create updated shulker box with remaining items
        ItemStack updatedShulker = shulkerStack.copy();
        updatedShulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(shulkerItems));

        return new ShulkerProcessResult(earnings, itemsSold, updatedShulker, soldStacks);
    }

    // -- Utility Methods -------------------------------------

    /**
     * Checks if an ItemStack is a shulker box.
     */
    public static boolean isShulkerBox(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof BlockItem blockItem) {
            return blockItem.getBlock() instanceof ShulkerBoxBlock;
        }
        return false;
    }

    /**
     * Extracts the registry path name from an ItemStack for reliable
     * material matching.
     */
    private String getMaterialName(ItemStack stack) {
        return TextUtil.getMaterialName(stack);
    }

    // -- Custom Slot Implementation --------------------------

    // (UI slots 0-8 now use the shared com.solidus.gui.DisplaySlot class,
    //  which replaces the previous private ReadOnlySlot with identical
    //  semantics: mayPlace=false, mayPickup=false, set() is a no-op.)
}
