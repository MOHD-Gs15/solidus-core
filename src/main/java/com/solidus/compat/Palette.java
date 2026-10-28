package com.solidus.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Version-free GUI palette (WS-1, Family A — audit follow-up to W-3).
 *
 * <p>Minecraft 26.3 REMOVED the per-color Java constants from {@code Items}
 * AND {@code Blocks} (measured against the official server jars:
 * {@code Items} kept one generic {@code STAINED_GLASS_PANE} and zero
 * {@code *_DYE} constants; snake_case ids inside {@code Items.class} fell
 * 441 → 40 as registration became data-driven). The registry IDS survived
 * — {@code gray_stained_glass_pane}, {@code lime_dye}, {@code gray_dye} etc.
 * are all still registered. So this class resolves palette entries by the
 * id string — the one key that is stable across versions — using the exact
 * idiom {@code ShopGUI}/{@code TextUtil} already run in production:</p>
 *
 * <pre>{@code
 * Item item = BuiltInRegistries.ITEM
 *     .get(Identifier.tryParse(id))
 *     .map(Holder::value).orElse(null);
 * }</pre>
 *
 * <p>One resolver, version-free: no reflection, no per-version bridge.
 * <b>The constants change, the ids stay</b> — the ids are the key precisely
 * because they outlive the constants.</p>
 *
 * <p><b>Fallback chain (panes):</b> {@code <exact id>} →
 * {@code stained_glass_pane} (the generic pane that survived as a constant
 * in 26.3) → {@code glass_pane} → the compile-time {@link Items#BARRIER}
 * refuge (a guaranteed survivor — 1 of the 13 constants that made it into
 * 26.3). <b>Dyes:</b> {@code <exact id>} → {@code dye} →
 * {@link Items#BARRIER}. Every candidate is wrapped in
 * {@code catch (Throwable)} — the CompatProbes lesson.</p>
 *
 * <p><b>Semantics preserved:</b> {@link #LIME_DYE} and {@link #GRAY_DYE}
 * are the READY / NOT-READY indicators in the trade GUI; if both degrade to
 * the same refuge item the probe surface raises {@code PALETTE_DEGRADED}
 * and /solidus-admin compat reports it — the GUI stays open (visual
 * degradation is safe) but it must be SEEN.</p>
 *
 * <p><b>Zero null:</b> every public field is non-null whenever
 * {@code Items.BARRIER} itself is live (i.e. any sane, bootstrapped
 * runtime). If the registry API itself is broken the startup probe fails
 * and {@link Compat#ensureGuiAvailable(net.minecraft.server.level.ServerPlayer)}
 * blocks GUI opens — no new degradation mechanism was invented here.</p>
 *
 * <p>RULE: no file outside {@code com.solidus.compat} may reference a color
 * constant ({@code Items.*_STAINED_GLASS_PANE} / {@code Items.*_DYE}).
 * Enforced by {@code CompatArchitectureGuardTest}.</p>
 *
 * @since 2.4.0
 */
public final class Palette {

    // -- Glass panes (GUI chrome / filler) ----------------------------------
    public static final Item BLACK_STAINED_GLASS_PANE       = pane("black_stained_glass_pane");
    public static final Item BLUE_STAINED_GLASS_PANE        = pane("blue_stained_glass_pane");
    public static final Item CYAN_STAINED_GLASS_PANE        = pane("cyan_stained_glass_pane");
    public static final Item GRAY_STAINED_GLASS_PANE        = pane("gray_stained_glass_pane");
    public static final Item GREEN_STAINED_GLASS_PANE       = pane("green_stained_glass_pane");
    public static final Item LIGHT_BLUE_STAINED_GLASS_PANE  = pane("light_blue_stained_glass_pane");
    public static final Item LIGHT_GRAY_STAINED_GLASS_PANE  = pane("light_gray_stained_glass_pane");
    public static final Item LIME_STAINED_GLASS_PANE        = pane("lime_stained_glass_pane");
    public static final Item MAGENTA_STAINED_GLASS_PANE     = pane("magenta_stained_glass_pane");
    public static final Item ORANGE_STAINED_GLASS_PANE      = pane("orange_stained_glass_pane");
    public static final Item PINK_STAINED_GLASS_PANE        = pane("pink_stained_glass_pane");
    public static final Item PURPLE_STAINED_GLASS_PANE      = pane("purple_stained_glass_pane");
    public static final Item RED_STAINED_GLASS_PANE         = pane("red_stained_glass_pane");
    public static final Item WHITE_STAINED_GLASS_PANE       = pane("white_stained_glass_pane");
    public static final Item YELLOW_STAINED_GLASS_PANE      = pane("yellow_stained_glass_pane");

    // -- Dyes (READY / NOT-READY indicators) ---------------------------------

    /** TradeGUI READY indicator — must stay a DIFFERENT item than {@link #GRAY_DYE}. */
    public static final Item LIME_DYE = dye("lime_dye");
    /** TradeGUI NOT-READY indicator — must stay a DIFFERENT item than {@link #LIME_DYE}. */
    public static final Item GRAY_DYE = dye("gray_dye");

    // -- Resolution machinery ------------------------------------------------

    /** The 17 palette entries: constant name -> exact registry id (ordered). */
    static final Map<String, String> ENTRY_IDS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("BLACK_STAINED_GLASS_PANE",       "black_stained_glass_pane");
        m.put("BLUE_STAINED_GLASS_PANE",        "blue_stained_glass_pane");
        m.put("CYAN_STAINED_GLASS_PANE",        "cyan_stained_glass_pane");
        m.put("GRAY_STAINED_GLASS_PANE",        "gray_stained_glass_pane");
        m.put("GREEN_STAINED_GLASS_PANE",       "green_stained_glass_pane");
        m.put("LIGHT_BLUE_STAINED_GLASS_PANE",  "light_blue_stained_glass_pane");
        m.put("LIGHT_GRAY_STAINED_GLASS_PANE",  "light_gray_stained_glass_pane");
        m.put("LIME_STAINED_GLASS_PANE",        "lime_stained_glass_pane");
        m.put("MAGENTA_STAINED_GLASS_PANE",     "magenta_stained_glass_pane");
        m.put("ORANGE_STAINED_GLASS_PANE",      "orange_stained_glass_pane");
        m.put("PINK_STAINED_GLASS_PANE",        "pink_stained_glass_pane");
        m.put("PURPLE_STAINED_GLASS_PANE",      "purple_stained_glass_pane");
        m.put("RED_STAINED_GLASS_PANE",         "red_stained_glass_pane");
        m.put("WHITE_STAINED_GLASS_PANE",       "white_stained_glass_pane");
        m.put("YELLOW_STAINED_GLASS_PANE",      "yellow_stained_glass_pane");
        m.put("LIME_DYE",                       "lime_dye");
        m.put("GRAY_DYE",                       "gray_dye");
        ENTRY_IDS = Collections.unmodifiableMap(m);
    }

    /** Sentinel recorded in {@link #CHOSEN} when only the barrier refuge saved an entry. */
    private static final String BARRIER_REFUGE = "<Items.BARRIER refuge>";

    /** key -> resolved item (successful candidate hits only; refuges re-attempt). */
    private static final ConcurrentHashMap<String, Item> RESOLVED = new ConcurrentHashMap<>();
    /** key -> the candidate that won (or {@link #BARRIER_REFUGE}) — for the admin report. */
    private static final ConcurrentHashMap<String, String> CHOSEN = new ConcurrentHashMap<>();
    /** Human-readable degradation notes, filled during resolution. */
    private static final List<String> NOTES = Collections.synchronizedList(new ArrayList<>());

    private static Item pane(String exactId) {
        Item resolved = resolve(exactId, exactId, "stained_glass_pane", "glass_pane");
        if (resolved != null) {
            return resolved;
        }
        note(exactId, "no candidate resolved — falling back to the compile-time Items.BARRIER refuge");
        CHOSEN.put(exactId, BARRIER_REFUGE);
        return Items.BARRIER;
    }

    private static Item dye(String exactId) {
        Item resolved = resolve(exactId, exactId, "dye");
        if (resolved != null) {
            return resolved;
        }
        note(exactId, "no candidate resolved — falling back to the compile-time Items.BARRIER refuge");
        CHOSEN.put(exactId, BARRIER_REFUGE);
        return Items.BARRIER;
    }

    /**
     * Tries each candidate id through the production resolution idiom.
     * Catches {@link Throwable} per candidate — a broken candidate must
     * never poison the remaining chain (CompatProbes lesson).
     */
    private static Item resolve(String key, String... candidates) {
        Item cached = RESOLVED.get(key);
        if (cached != null) {
            return cached;
        }
        for (String candidate : candidates) {
            try {
                Item item = BuiltInRegistries.ITEM
                    .get(Identifier.tryParse(candidate))
                    .map(Holder::value)
                    .orElse(null);
                if (item != null) {
                    RESOLVED.put(key, item);
                    CHOSEN.put(key, candidate);
                    if (!candidate.equals(key)) {
                        note(key, "exact id unavailable — resolved via fallback candidate '" + candidate + "'");
                    }
                    return item;
                }
            } catch (Throwable t) {
                note(key, "candidate '" + candidate + "' threw "
                    + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }
        }
        return null;
    }

    private static void note(String key, String message) {
        NOTES.add(key + " -> " + message);
    }

    // -- Probe surface (called from CompatProbes.verifyCompatibility) -------

    /**
     * Re-resolves every entry that did not land on its exact id and returns
     * the ids still missing (refuge entries count as missing — they are the
     * degraded state). Call only after the CALLER has verified via a
     * Palette-free reflection canary (see CompatProbes) that the item
     * registry is actually populated — touching this class resolves the
     * fields below, and that must never happen against an empty registry.
     */
    static List<String> probeMissingIds() {
        List<String> missing = new ArrayList<>();
        for (String id : ENTRY_IDS.values()) {
            if (RESOLVED.get(id) != null) {
                continue;
            }
            // One re-attempt (the registry may have finished loading since
            // clinit) — with the SAME candidate chain the entry originally
            // had, so a dye can never "recover" into a glass pane.
            boolean isDye = id.endsWith("_dye");
            Item re = isDye
                ? resolve(id, id, "dye")
                : resolve(id, id, "stained_glass_pane", "glass_pane");
            if (re == null) {
                missing.add(id);
            }
        }
        return missing;
    }

    /**
     * The READY/NOT-READY semantic guarantee: after resolution the two
     * indicator dyes must still be two DIFFERENT items.
     */
    static boolean probeDistinctReadyIndicators() {
        Item lime = RESOLVED.get("lime_dye");
        Item gray = RESOLVED.get("gray_dye");
        return lime != null && gray != null && lime != gray;
    }

    /** True when every entry resolved to its EXACT registry id (no fallback, no refuge). */
    static boolean isExact() {
        if (CHOSEN.size() < ENTRY_IDS.size()) {
            return false;
        }
        // CHOSEN is keyed by the registry ID (the value side of ENTRY_IDS).
        for (String id : ENTRY_IDS.values()) {
            if (!id.equals(CHOSEN.get(id))) {
                return false;
            }
        }
        return true;
    }

    /** Resolution/degradation notes for /solidus-admin compat. */
    static List<String> degradationNotes() {
        synchronized (NOTES) {
            return List.copyOf(NOTES);
        }
    }

    private Palette() {
    }
}
