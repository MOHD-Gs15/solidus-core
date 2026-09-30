package com.solidus.api;

/**
 * SolidusApiAccess — static entry point for the {@link SolidusApi} contract.
 *
 * <p>Solidus Core installs its implementation at mod-initialization time
 * (before any server lifecycle event fires — the same ordering guarantee
 * the legacy {@code SolidusAPI} singleton gives reflective 2.1.x
 * companions). Companion mods read it through {@link #get()}:</p>
 *
 * <pre>{@code
 * SolidusApi api = SolidusApiAccess.get();
 * if (api == null) {
 *     // Only reachable when Solidus Core is absent — and companions
 *     // declare "depends": { "solidus": ">=2.3.0 <3.0.0" }, so the
 *     // loader already refused to load us in that case. This branch
 *     // exists for unit tests and embedding scenarios.
 *     return;
 * }
 * }</pre>
 *
 * <p>This holder deliberately mirrors the shape of the legacy
 * {@code com.solidus.api.SolidusAPI#getInstance()} access pattern (a static
 * getter over a singleton) so the mental model — and the null-check
 * discipline — carries over unchanged.</p>
 *
 * @since 2.3.0 (family contract 2.3)
 */
public final class SolidusApiAccess {

    private static volatile SolidusApi instance;

    private SolidusApiAccess() {
    }

    /**
     * Installs the implementation. Called once by Solidus Core during mod
     * initialization; subsequent calls are ignored (first installer wins,
     * mirroring the legacy singleton's idempotence).
     *
     * @param impl the implementation serving the contract (never null)
     */
    public static void install(SolidusApi impl) {
        if (impl == null) {
            throw new IllegalArgumentException("SolidusApi implementation must not be null");
        }
        if (instance != null) {
            return;
        }
        synchronized (SolidusApiAccess.class) {
            if (instance == null) {
                instance = impl;
            }
        }
    }

    /**
     * Removes the installed implementation (Core shutdown / tests).
     */
    public static synchronized void uninstall() {
        instance = null;
    }

    /**
     * @return the installed {@link SolidusApi}, or {@code null} when Solidus
     *         Core is not loaded or not yet initialized
     */
    public static SolidusApi get() {
        return instance;
    }

    /**
     * @return true when Core is loaded and the contract is served
     */
    public static boolean isAvailable() {
        return instance != null;
    }
}
