package com.solidus.compat;

/**
 * ClickDescriptor — Solidus's stable, version-free representation of one
 * container click.
 *
 * <p><b>Why (audit W-3 / adapter layer):</b> Minecraft 26.1 replaced
 * {@code ClickType + buttonNum} on the container-click packet with a
 * {@code ContainerInput} record — the exact kind of internals churn that
 * used to require touching every ScreenHandler. This descriptor is the
 * firewall: the version-specific type is translated into these stable
 * semantics exactly once, inside the compat package
 * ({@code impl_26_1.ClickInputResolver_26_1}), and the entire rest of the
 * mod consumes only this class.</p>
 *
 * <p>On the next Minecraft update that reshapes click inputs, the diff is
 * confined to the resolver implementation — the ~29,500 lines of economy,
 * shop, auction, sell and trade logic are untouched.</p>
 *
 * <p>Raw values ({@link #slotIndex()}, {@link #button()}) keep their
 * historical meanings: {@code button} is the physical mouse button
 * (0 = left, 1 = right) — verified against the 26.1.2 mapped jar, where
 * the record still carries it separately from the input enum.</p>
 *
 * @since 2.3.0
 */
public final class ClickDescriptor {

    /**
     * The click semantics Solidus GUIs actually act on. Named by intent —
     * not by Minecraft's enum — so the mapping from any future input type
     * is a decision inside the compat package alone.
     */
    public enum Input {
        /** Regular pick-up click (left or right — disambiguated by button). */
        PICKUP,
        /** Shift-click / quick-move intent. */
        QUICK_MOVE,
        /** Anything Solidus does not bind behavior to (swap, clone, throw, ...). */
        OTHER
    }

    private final int slotIndex;
    private final int button;
    private final Input input;

    private ClickDescriptor(int slotIndex, int button, Input input) {
        this.slotIndex = slotIndex;
        this.button = button;
        this.input = input == null ? Input.OTHER : input;
    }

    /**
     * Builds a descriptor from already-extracted values.
     *
     * @param slotIndex the clicked slot (-65535 for "no slot" style packets)
     * @param button    the physical button (0 = left, 1 = right)
     * @param input     the normalized input semantics
     * @return an immutable descriptor
     */
    public static ClickDescriptor of(int slotIndex, int button, Input input) {
        return new ClickDescriptor(slotIndex, button, input);
    }

    /** @return the clicked slot index */
    public int slotIndex() {
        return slotIndex;
    }

    /** @return the physical button (0 = left, 1 = right) */
    public int button() {
        return button;
    }

    /** @return the normalized input semantics (never null) */
    public Input input() {
        return input;
    }

    /** @return true for shift-click intent */
    public boolean isQuickMove() {
        return input == Input.QUICK_MOVE;
    }

    /** @return true for regular pick-up clicks */
    public boolean isPickup() {
        return input == Input.PICKUP;
    }

    /** @return true for a regular LEFT pick-up click */
    public boolean isLeftPickup() {
        return input == Input.PICKUP && button == 0;
    }

    /** @return true for a regular RIGHT pick-up click */
    public boolean isRightPickup() {
        return input == Input.PICKUP && button == 1;
    }

    @Override
    public String toString() {
        return "ClickDescriptor[slot=" + slotIndex + ", button=" + button + ", input=" + input + "]";
    }
}
