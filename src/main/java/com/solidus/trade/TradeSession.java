package com.solidus.trade;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single direct trade session between two players.
 *
 * <p><b>Player-agnostic by design:</b> the session stores UUIDs and cached
 * display names only - never {@code ServerPlayer} references. The manager
 * resolves live players when it needs to send messages or hand out items.
 * This keeps the state machine pure (unit-testable without a Minecraft
 * runtime) and immune to stale player references after disconnects.</p>
 *
 * <p><b>Mirrored containers</b> (the TradeMe pattern): each player gets their
 * OWN {@link TradeContainer} in which "my offer" is always the LEFT 3 columns
 * and "their offer" the mirrored RIGHT 3 columns. Whenever a player changes
 * their own offer, the handler copies it into the partner's container's
 * right-hand mirror and the partner's menu is fully resynced - so both
 * players always see an identical, live preview of the trade from their own
 * perspective.</p>
 *
 * <p><b>Escrow model</b> (mirrors the sell GUI): the moment a player places
 * an item into their offer slots it LEAVES their inventory and lives in
 * their session container. On cancel / disconnect every item returns to its
 * owner. On execution the items swap owners and the money moves atomically.
 * While a session is open the offered items are fully protected: the offerer
 * cannot spend, drop or sell them elsewhere.</p>
 *
 * <p><b>Anti bait-and-switch:</b> ANY change to a player's offer (item
 * placed, removed, or money edited) un-readies BOTH sides. The green light
 * only stays on while both offers are untouched.</p>
 */
public class TradeSession {

    public enum State {
        /** GUIs open, both players filling their offers. */
        ACTIVE,
        /** Execution started (money moving) - no more interactions accepted. */
        EXECUTING,
        /** Finished successfully. */
        COMPLETED,
        /** Cancelled / aborted. */
        CANCELLED
    }

    /** Role of a player inside the session. */
    public enum Side { INITIATOR, PARTNER }

    private final UUID sessionId;
    private final UUID initiatorUuid;
    private final String initiatorName;
    private final UUID partnerUuid;
    private final String partnerName;

    /** Each side's own container (their own offer on the LEFT columns). */
    private final TradeContainer initiatorContainer;
    private final TradeContainer partnerContainer;

    private final long createdAt = System.currentTimeMillis();

    private volatile State state = State.ACTIVE;
    private volatile boolean initiatorReady = false;
    private volatile boolean partnerReady = false;
    private volatile double initiatorMoney = 0;
    private volatile double partnerMoney = 0;
    private volatile long lastActivity = createdAt;

    public TradeSession(UUID sessionId,
                        UUID initiatorUuid, String initiatorName,
                        UUID partnerUuid, String partnerName) {
        this.sessionId = sessionId;
        this.initiatorUuid = initiatorUuid;
        this.initiatorName = initiatorName;
        this.partnerUuid = partnerUuid;
        this.partnerName = partnerName;
        this.initiatorContainer = new TradeContainer();
        this.partnerContainer = new TradeContainer();
    }

    // -- Identity ------------------------------------------

    public UUID sessionId() { return sessionId; }
    public State state() { return state; }
    public long createdAt() { return createdAt; }
    public long lastActivity() { return lastActivity; }

    public UUID uuidOf(Side side) {
        return side == Side.INITIATOR ? initiatorUuid : partnerUuid;
    }

    public String nameOf(Side side) {
        return side == Side.INITIATOR ? initiatorName : partnerName;
    }

    /** Resolves a player UUID to their side, or null when they are not part of it. */
    public Side sideOf(UUID playerUuid) {
        if (initiatorUuid.equals(playerUuid)) return Side.INITIATOR;
        if (partnerUuid.equals(playerUuid)) return Side.PARTNER;
        return null;
    }

    /** The other side of the given side. */
    public static Side other(Side side) {
        return side == Side.INITIATOR ? Side.PARTNER : Side.INITIATOR;
    }

    /** The container owned by the given side (their offer on the LEFT). */
    public TradeContainer containerOf(Side side) {
        return side == Side.INITIATOR ? initiatorContainer : partnerContainer;
    }

    // -- Offer state ---------------------------------------

    public boolean isReady(Side side) {
        return side == Side.INITIATOR ? initiatorReady : partnerReady;
    }

    public void setReady(Side side, boolean ready) {
        if (side == Side.INITIATOR) initiatorReady = ready;
        else partnerReady = ready;
        touch();
    }

    public boolean bothReady() {
        return initiatorReady && partnerReady;
    }

    public double moneyOf(Side side) {
        return side == Side.INITIATOR ? initiatorMoney : partnerMoney;
    }

    /**
     * Sets a side's money offer. ANY change un-readies BOTH sides
     * (anti bait-and-switch) - enforced here at the state level so no
     * caller can forget it.
     */
    public void setMoney(Side side, double amount) {
        if (side == Side.INITIATOR) initiatorMoney = amount;
        else partnerMoney = amount;
        unreadyBoth();
    }

    /** Any change un-readies BOTH sides (anti bait-and-switch). */
    public void unreadyBoth() {
        initiatorReady = false;
        partnerReady = false;
        touch();
    }

    private void touch() {
        lastActivity = System.currentTimeMillis();
    }

    // -- Item access ---------------------------------------

    /** Snapshot of the items a side has offered (copies, never null). */
    public List<ItemStack> offeredItems(Side side) {
        List<ItemStack> out = new ArrayList<>();
        TradeContainer container = containerOf(side);
        for (int slot : TradeGUI.MY_OFFER_SLOTS) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) out.add(stack.copy());
        }
        return out;
    }

    /** Total number of offered item stacks for a side. */
    public int offeredStackCount(Side side) {
        TradeContainer container = containerOf(side);
        int n = 0;
        for (int slot : TradeGUI.MY_OFFER_SLOTS) {
            if (!container.getItem(slot).isEmpty()) n++;
        }
        return n;
    }

    /** True when neither side offered anything at all. */
    public boolean isEmptyTrade() {
        return offeredStackCount(Side.INITIATOR) == 0 && offeredStackCount(Side.PARTNER) == 0
            && initiatorMoney <= 0 && partnerMoney <= 0;
    }

    /**
     * Removes (takes ownership of) the offered items of a side from their
     * container and returns them. Used at execution to swap the stacks.
     */
    public List<ItemStack> takeOfferedItems(Side side) {
        TradeContainer container = containerOf(side);
        List<ItemStack> out = new ArrayList<>();
        for (int slot : TradeGUI.MY_OFFER_SLOTS) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                out.add(stack);
                container.setItem(slot, ItemStack.EMPTY);
            }
        }
        return out;
    }

    /**
     * Claims and clears the offered items of a side WITHOUT a destination -
     * used by the cancel path where the manager hands them to the (possibly
     * resolved) owner.
     */
    public List<ItemStack> claimOfferedItems(Side side) {
        return takeOfferedItems(side);
    }

    public void markExecuting() {
        this.state = State.EXECUTING;
    }

    public void markCompleted() {
        this.state = State.COMPLETED;
    }

    public void markCancelled() {
        this.state = State.CANCELLED;
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.CANCELLED;
    }
}
