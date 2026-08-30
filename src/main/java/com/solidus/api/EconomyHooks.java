package com.solidus.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * EconomyHooks - Global registry and dispatch point for
 * {@link SolidusTransactionHook} implementations.
 *
 * <p>Core call sites invoke {@link #allow(Function)} before money moves and
 * {@link #notifyHooks(Consumer)} after a transaction settles. External mods
 * register through {@link SolidusAPI#registerTransactionHook(SolidusTransactionHook)}
 * (this class is internal plumbing, not part of the stable API surface).</p>
 *
 * <p><b>Thread safety:</b> backed by a {@link CopyOnWriteArrayList}, so hooks
 * may be registered/unregistered at any time while transactions are flowing.</p>
 *
 * <p><b>Failure policy (fail-open):</b> a hook that throws inside a veto
 * check is skipped for that transaction (transaction proceeds, error logged).
 * A hook that throws inside a notification is skipped for that notification.
 * One misbehaving hook can never wedge or halt the economy.</p>
 *
 * @since 2.1.0
 */
public final class EconomyHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("SolidusHooks");

    private static final CopyOnWriteArrayList<SolidusTransactionHook> HOOKS =
        new CopyOnWriteArrayList<>();

    private EconomyHooks() {}

    /**
     * Registers a transaction hook.
     *
     * @param hook the hook to register
     * @return true if registered, false if a hook with the same name is
     *         already registered (or the hook was null)
     */
    public static boolean register(SolidusTransactionHook hook) {
        if (hook == null || hook.name() == null || hook.name().isBlank()) {
            LOGGER.warn("Rejected hook registration: null hook or blank name.");
            return false;
        }
        for (SolidusTransactionHook existing : HOOKS) {
            if (existing.name().equals(hook.name())) {
                LOGGER.warn("Hook '{}' is already registered. Ignoring duplicate.", hook.name());
                return false;
            }
        }
        HOOKS.add(hook);
        LOGGER.info("Economy transaction hook registered: {} ({} active)", hook.name(), HOOKS.size());
        return true;
    }

    /**
     * Unregisters a previously registered hook.
     *
     * @param hook the hook instance (or proxy) to remove
     * @return true if it was registered and is now removed
     */
    public static boolean unregister(SolidusTransactionHook hook) {
        if (hook == null) return false;
        boolean removed = HOOKS.remove(hook);
        if (removed) {
            LOGGER.info("Economy transaction hook unregistered: {} ({} active)", hook.name(), HOOKS.size());
        }
        return removed;
    }

    /**
     * @return true if at least one hook is registered
     */
    public static boolean hasHooks() {
        return !HOOKS.isEmpty();
    }

    /**
     * @return an immutable snapshot of the currently registered hooks
     */
    public static List<SolidusTransactionHook> registeredHooks() {
        return List.copyOf(HOOKS);
    }

    /**
     * Runs a veto check across all registered hooks.
     *
     * <p>Returns the <b>first denial</b> (so the player sees the most
     * relevant reason). A hook returning null is treated as ALLOW. A hook
     * throwing is logged and skipped (fail-open). With no hooks registered
     * this returns {@link SolidusTransactionHook.Decision#ALLOW} without
     * allocating lambda dispatch overhead beyond the loop guard.</p>
     *
     * <p>Denial reasons are normalized: a denial with a null/blank reason is
     * returned with the generic fallback message, so call sites can surface
     * {@code reason()} to players verbatim.</p>
     *
     * @param check function invoking one hook's allowXxx method
     * @return the first denial found (never-null reason), or
     *         {@link SolidusTransactionHook.Decision#ALLOW}
     */
    public static SolidusTransactionHook.Decision allow(
            Function<SolidusTransactionHook, SolidusTransactionHook.Decision> check) {
        if (HOOKS.isEmpty()) {
            return SolidusTransactionHook.Decision.ALLOW;
        }
        for (SolidusTransactionHook hook : HOOKS) {
            try {
                SolidusTransactionHook.Decision decision = check.apply(hook);
                if (decision != null && !decision.allowed()) {
                    String reason = decision.reason();
                    return (reason != null && !reason.isBlank())
                        ? decision
                        : SolidusTransactionHook.Decision.deny(null); // falls back to generic message
                }
            } catch (Throwable t) {
                LOGGER.warn("Hook '{}' threw in veto check - failing open. {}",
                    hook.name(), t.toString(), t);
            }
        }
        return SolidusTransactionHook.Decision.ALLOW;
    }

    /**
     * Delivers a post-transaction notification to all registered hooks.
     * Exceptions from one hook never prevent delivery to the others.
     *
     * @param event function invoking one hook's afterXxx method
     */
    public static void notifyHooks(Consumer<SolidusTransactionHook> event) {
        if (HOOKS.isEmpty()) {
            return;
        }
        for (SolidusTransactionHook hook : HOOKS) {
            try {
                event.accept(hook);
            } catch (Throwable t) {
                LOGGER.warn("Hook '{}' threw in notification - skipped. {}",
                    hook.name(), t.toString(), t);
            }
        }
    }
}
