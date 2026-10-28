package com.solidus.compat;

import com.solidus.SolidusMod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * CompatDrop — the version-free world-drop wrapper (WS-1, Family B).
 *
 * <p><b>Why this exists:</b> Minecraft 26.3 reshaped the world-drop entry
 * point. Measured from the official server jars:</p>
 *
 * <ul>
 *   <li>26.1.2 — {@code Player} declares
 *       {@code drop(ItemStack, boolean)ItemEntity} (inherited by
 *       {@code ServerPlayer});</li>
 *   <li>26.3 — {@code Player} no longer declares {@code drop} at all;
 *       {@code ServerPlayer} declares
 *       {@code drop(ItemStack, boolean, Prediction)ItemEntity} where
 *       {@code net.minecraft.util.Prediction} is an enum with exactly
 *       {@code PREDICTED} and {@code SERVER_ONLY}.</li>
 * </ul>
 *
 * <p>Every Solidus {@code .drop(...)} call site is a <b>recovery path</b>
 * (inventory full while returning escrowed / refunded / won items) — losing
 * the stack there is item destruction (audit TRD-01/H-4). So the wrapper is
 * resolved <b>once at startup</b> from {@link CompatProbes#verifyCompatibility()}
 * and every call site goes through here; nothing outside
 * {@code com.solidus.compat} may call a raw {@code drop} any more
 * (enforced by {@code CompatArchitectureGuardTest}).</p>
 *
 * <p><b>Strategies (tried in this order):</b></p>
 * <ol>
 *   <li>{@code THREE_ARG_PREDICTION} (26.3+) — MethodHandle on
 *       {@code ServerPlayer.drop(ItemStack, boolean, Prediction)} with the
 *       constant {@code Prediction.SERVER_ONLY}. Reflection-only, because
 *       this source must also compile against 26.1.x where the
 *       {@code Prediction} type does not exist.</li>
 *   <li>{@code TWO_ARG} (26.1.x) — MethodHandle on
 *       {@code Player.drop(ItemStack, boolean)}.</li>
 * </ol>
 *
 * <p><b>Datum choice — {@code SERVER_ONLY}:</b> every Solidus call site is a
 * server-initiated recovery drop the client has NOT predicted; this is pinned
 * behaviourally in the live-boot drill (exactly one item entity per stack, no
 * ghosts, no duplicates) and is NOT assumed correct on signature alone.</p>
 *
 * <p><b>Failure policy (fail-closed, never silent):</b> if no strategy
 * resolves (or an invocation throws), the drop is <b>refused</b>: a
 * {@code CRITICAL} log line carries the full reconciliation context
 * (who, what, how much, where), the stack is left untouched — the caller
 * decides between its persistent alternative (e.g. the
 * {@code auction_won_items} collectible row for the auction surface, or
 * keeping items in the trade session) — and {@code null} is returned.
 * A broken drop surface must degrade to "admin can manually restore from
 * the log", never to "the item quietly vanished".</p>
 *
 * <p>Handles are stored adapted via {@code asType(...)} and invoked with
 * plain {@code invoke} (never {@code invokeExact}) so the call sites stay
 * insensitive to static-type drift of the last parameter. Performance is
 * not a concern: all call sites are rare recovery paths.</p>
 *
 * @since 2.4.0
 */
public final class CompatDrop {

    /** Resolution outcome for the running Minecraft version. */
    public enum Strategy {
        /** 26.3+: ServerPlayer.drop(ItemStack, boolean, Prediction). */
        THREE_ARG_PREDICTION,
        /** 26.1.x: Player.drop(ItemStack, boolean). */
        TWO_ARG,
        /** No known shape resolved — drops are refused (fail-closed). */
        BROKEN
    }

    private static volatile Strategy strategy = Strategy.BROKEN;
    private static volatile String failureReason = "probe has not run yet";

    /** 26.3+ handle, adapted to (ServerPlayer, ItemStack, boolean, Object)ItemEntity. */
    private static volatile MethodHandle threeArgDrop;
    /** {@code Prediction.SERVER_ONLY} as an opaque Object (type is 26.3-only). */
    private static volatile Object serverOnlyPrediction;

    /** 26.1.x handle, adapted to (ServerPlayer, ItemStack, boolean)ItemEntity. */
    private static volatile MethodHandle twoArgDrop;

    /** The big banner is printed once; per-call CRITICAL lines always print. */
    private static final AtomicBoolean BANNER_LOGGED = new AtomicBoolean(false);

    private CompatDrop() {
    }

    // -- Resolution (called once from CompatProbes.verifyCompatibility) ----

    /**
     * Resolves the drop strategy for the RUNNING Minecraft version.
     * Reflection-only so this source compiles against 26.1.x (no
     * {@code Prediction} type there) and 26.3+ alike. Idempotent: the first
     * successful resolution wins. Never throws.
     */
    static synchronized void resolve() {
        if (strategy == Strategy.THREE_ARG_PREDICTION || strategy == Strategy.TWO_ARG) {
            return; // already resolved
        }

        // -- Strategy 1: 26.3+ ServerPlayer.drop(ItemStack, boolean, Prediction)
        try {
            Class<?> prediction = Class.forName("net.minecraft.util.Prediction", false,
                CompatDrop.class.getClassLoader());
            Method drop = ServerPlayer.class.getDeclaredMethod(
                "drop", ItemStack.class, boolean.class, prediction);
            drop.setAccessible(true);
            MethodHandle raw = MethodHandles.lookup().unreflect(drop);
            // Adapt the last parameter to Object so the invocation site never
            // needs the 26.3-only type in its static signature.
            threeArgDrop = raw.asType(MethodType.methodType(
                ItemEntity.class, ServerPlayer.class, ItemStack.class,
                boolean.class, Object.class));

            Field serverOnly = prediction.getField("SERVER_ONLY");
            serverOnlyPrediction = serverOnly.get(null);

            strategy = Strategy.THREE_ARG_PREDICTION;
            failureReason = "resolved ServerPlayer#drop(ItemStack,boolean,net.minecraft.util.Prediction) "
                + "+ Prediction.SERVER_ONLY (26.3+ shape)";
            SolidusMod.LOGGER.info(
                "CompatDrop: THREE_ARG_PREDICTION strategy selected (ServerPlayer#drop + Prediction.SERVER_ONLY).");
            return;
        } catch (Throwable t) {
            failureReason = "3-arg ServerPlayer#drop(ItemStack,boolean,Prediction) -> "
                + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }

        // -- Strategy 2: 26.1.x Player.drop(ItemStack, boolean)
        try {
            Method drop = findInHierarchy(
                net.minecraft.world.entity.player.Player.class,
                "drop", ItemStack.class, boolean.class);
            drop.setAccessible(true);
            MethodHandle raw = MethodHandles.lookup().unreflect(drop);
            twoArgDrop = raw.asType(MethodType.methodType(
                ItemEntity.class, ServerPlayer.class, ItemStack.class, boolean.class));

            strategy = Strategy.TWO_ARG;
            failureReason = failureReason + " | resolved Player#drop(ItemStack,boolean) (26.1.x shape)";
            SolidusMod.LOGGER.info("CompatDrop: TWO_ARG strategy selected (Player#drop(ItemStack,boolean)).");
            return;
        } catch (Throwable t) {
            failureReason = failureReason
                + " | 2-arg Player#drop(ItemStack,boolean) -> " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }

        strategy = Strategy.BROKEN;
        SolidusMod.LOGGER.error(
            "CompatDrop: NO drop strategy resolved — world-drop recovery is DISABLED. {}",
            failureReason);
    }

    /** Walks up the class hierarchy to find a declared method (Probes-style). */
    private static Method findInHierarchy(Class<?> start, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchMethodException(name + methodSignature(params) + " not found in hierarchy of " + start.getName());
    }

    private static String methodSignature(Class<?>... params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) {
            if (sb.length() > 1) sb.append(',');
            sb.append(p.getSimpleName());
        }
        return sb.append(')').toString();
    }

    // -- Public surface ----------------------------------------------------

    /**
     * The version-free world drop — replaces every raw
     * {@code player.drop(stack, throwRandomly)} call site.
     *
     * @param player        the (online) server player the items belong to
     * @param stack         the stack to drop; {@code null}/empty is a no-op returning null
     * @param throwRandomly vanilla "throw around" randomization flag
     * @return the spawned {@link ItemEntity}, vanilla's own null when nothing
     *         was spawned, or {@code null} when the drop surface is broken
     *         (in which case a CRITICAL log line was emitted and the stack
     *         was left untouched — the caller must apply its persistent
     *         alternative, never silently give up the items)
     */
    public static ItemEntity drop(ServerPlayer player, ItemStack stack, boolean throwRandomly) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Strategy current = strategy;
        try {
            switch (current) {
                case THREE_ARG_PREDICTION -> {
                    MethodHandle handle = threeArgDrop;
                    if (handle != null) {
                        return (ItemEntity) handle.invoke(player, stack, throwRandomly, serverOnlyPrediction);
                    }
                }
                case TWO_ARG -> {
                    MethodHandle handle = twoArgDrop;
                    if (handle != null) {
                        return (ItemEntity) handle.invoke(player, stack, throwRandomly);
                    }
                }
                default -> {
                    // fall through to the fail-closed path
                }
            }
        } catch (Throwable t) {
            // A strategy that resolved at startup must not fail at runtime —
            // if it does, refuse the drop rather than risk a half-executed
            // vanilla path. The fail-closed branch below logs the context.
            SolidusMod.LOGGER.error(
                "CompatDrop: strategy {} threw during invocation — refusing the drop. {}",
                current, t.toString(), t);
        }
        return refuseAndReport(player, stack, throwRandomly, current);
    }

    /**
     * Convenience for the 16 recovery paths that used {@code drop(stack, false)}.
     */
    public static ItemEntity dropSilently(ServerPlayer player, ItemStack stack) {
        return drop(player, stack, false);
    }

    /** The resolved strategy for the running Minecraft version. */
    public static Strategy strategy() {
        return strategy;
    }

    /** Stable name for logs and the /solidus-admin compat report. */
    public static String strategyName() {
        return strategy.name();
    }

    /** True when drops are refused (no strategy resolved / invocation broken). */
    public static boolean isBroken() {
        return strategy == Strategy.BROKEN;
    }

    /** Human-readable resolution report (why this strategy / why broken). */
    public static String failureReason() {
        return failureReason;
    }

    // -- Fail-closed path --------------------------------------------------

    /**
     * The DROP_BROKEN contract: NO silent drop, NO quiet item destruction.
     * Logs the full reconciliation context (who / what / how many / where),
     * leaves the stack untouched for the caller's persistent alternative,
     * and returns null.
     */
    private static ItemEntity refuseAndReport(ServerPlayer player, ItemStack stack,
                                              boolean throwRandomly, Strategy attempted) {
        if (BANNER_LOGGED.compareAndSet(false, true)) {
            SolidusMod.LOGGER.error(
                "┌────────────────────────────────────────────────────────────────────────────┐");
            SolidusMod.LOGGER.error(
                "│ Solidus: CompatDrop is BROKEN on this Minecraft version — world-drop      │");
            SolidusMod.LOGGER.error(
                "│ recovery paths refuse to hand out items instead of silently destroying    │");
            SolidusMod.LOGGER.error(
                "│ them. Every refusal logs a CRITICAL line with the full context below.     │");
            SolidusMod.LOGGER.error(
                "│ Reason: {}", pad(failureReason, 74));
            SolidusMod.LOGGER.error(
                "│ Fix: see src/main/java/com/solidus/compat/PACKAGE.md (update checklist). │");
            SolidusMod.LOGGER.error(
                "└────────────────────────────────────────────────────────────────────────────┘");
        }
        SolidusMod.LOGGER.error(
            "CRITICAL: Solidus refused a world drop (strategy {}, reason: {}) — the stack was "
                + "NOT delivered and NOT destroyed. Reconcile manually from this context + the ledger: "
                + "player={}, uuid={}, item={}, count={}, throwRandomly={}. "
                + "The caller is expected to apply its persistent alternative "
                + "(auction_won_items row / trade session retention).",
            attempted, failureReason,
            safePlayerName(player), safePlayerUuid(player),
            safeItemKey(stack), safeCount(stack), throwRandomly);
        return null;
    }

    // -- Safe context gatherers (must never throw while reporting) ---------

    private static String safePlayerName(ServerPlayer player) {
        try {
            return player != null ? player.getName().getString() : "<null>";
        } catch (Throwable t) {
            return "<unresolvable>";
        }
    }

    private static String safePlayerUuid(ServerPlayer player) {
        try {
            return player != null ? String.valueOf(player.getUUID()) : "<null>";
        } catch (Throwable t) {
            return "<unresolvable>";
        }
    }

    private static String safeItemKey(ItemStack stack) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        } catch (Throwable t) {
            return "<unresolvable>";
        }
    }

    private static int safeCount(ItemStack stack) {
        try {
            return stack.getCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        return s.length() >= width ? s.substring(0, width) : s + " ".repeat(width - s.length());
    }
}
