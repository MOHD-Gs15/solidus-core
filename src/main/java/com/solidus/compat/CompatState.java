package com.solidus.compat;

import java.util.List;

/**
 * CompatState — single source of truth for Minecraft-version compatibility.
 *
 * <p><b>Why this exists (audit W-3):</b> Solidus's virtual-GUI pipeline is
 * wired into exactly the kind of Minecraft internals that churn between
 * versions — {@code ServerGamePacketListenerImpl#handleContainerClick},
 * the {@code ServerboundContainerClickPacket} record accessors, the
 * {@code ContainerInput} type, and the {@code AbstractContainerMenu#clicked}
 * dispatch signature. With the old {@code defaultRequire=1} mixin config,
 * any of those changing shape meant a <b>hard crash at server startup</b>
 * for every server owner who updated Minecraft before Solidus shipped an
 * update.</p>
 *
 * <p>As of family 2.3.0 the policy is <b>graceful degradation</b>:</p>
 * <ul>
 *   <li>High-risk mixin injectors declare {@code require = 0} — a failed
 *       injection logs a warning instead of crashing the server.</li>
 *   <li>At startup, {@link CompatProbes#verifyCompatibility()} reflectively
 *       verifies every Minecraft shape the pipeline compiled against
 *       (possible without mappings because Minecraft 26.1+ ships
 *       unobfuscated).</li>
 *   <li>If any probe fails, the <b>GUI layer is disabled with a loud,
 *       actionable message</b> — while commands, storage, the ledger, taxes
 *       and every non-GUI flow keep working normally.</li>
 * </ul>
 *
 * <p>This class is deliberately dependency-free (no Minecraft imports) so
 * the compat state is readable from anywhere, including the API shim and
 * tests.</p>
 *
 * @since 2.3.0
 */
public final class CompatState {

    /**
     * Stable capability flags. Each capability names a subsystem whose
     * Minecraft touch-points are confined to the {@code compat/} package;
     * the flag tells the rest of the mod whether that subsystem is safe
     * to use on the RUNNING Minecraft version.
     */
    public enum Capability {
        /** Container-click routing: the handleContainerClick mixin target
         *  and the packet accessors it reads. */
        CONTAINER_CLICK_ROUTING("container click routing (virtual GUIs)"),

        /** The vanilla clicked(int,int,ContainerInput,Player) dispatch our
         *  ScreenHandlers override as defense-in-depth. */
        MENU_CLICK_DISPATCH("AbstractContainerMenu#clicked dispatch"),

        /** The full-state resync used by the anti-ghost-item policy. */
        FULL_STATE_RESYNC("broadcastFullState() resync");

        private final String description;

        Capability(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private static volatile boolean verified = false;

    /** Capability -> available. Guarded by the write-once protocol below. */
    private static volatile List<String> failures = List.of();

    private CompatState() {
    }

    /**
     * Writes the probe report. Called exactly once from
     * {@link CompatProbes#verifyCompatibility()} at mod initialization
     * (before any GUI can open). Later calls are ignored — the report is
     * sticky for the lifetime of the process, matching the mixin system's
     * own apply-once behavior.
     *
     * @param probeFailures human-readable list of failed probes (empty = all green)
     */
    static void report(List<String> probeFailures) {
        // Best-effort write-once: a race between two callers keeps the
        // FIRST report, which is the one produced before any GUI opened.
        synchronized (CompatState.class) {
            if (!verified) {
                failures = List.copyOf(probeFailures);
                verified = true;
            }
        }
    }

    // -- Runtime downgrade -----------------------------------

    /**
     * Appends a post-init failure (e.g. the mixin-apply cross-check on the
     * first player connection found the mixin was never applied). Once a
     * downgrade lands, {@link #isGuiSafe()} stays false for the lifetime of
     * the process — a broken GUI surface must not flappingly recover while
     * players hold menus open.
     *
     * @param reason human-readable, action-oriented cause
     */
    static synchronized void downgrade(String reason) {
        List<String> updated = new java.util.ArrayList<>(failures);
        updated.add(reason);
        failures = List.copyOf(updated);
        verified = true;
    }

    // -- Queries --------------------------------------------

    /**
     * @return true when the startup probe ran (and thus the state is meaningful)
     */
    public static boolean isVerified() {
        return verified;
    }

    /**
     * @return immutable list of failed probe descriptions (empty when healthy)
     */
    public static List<String> failures() {
        return failures;
    }

    /**
     * @return true when every compatibility probe passed
     */
    public static boolean allProbesPassed() {
        return verified && failures.isEmpty();
    }

    /**
     * The single decision every GUI-open path asks before showing a menu.
     *
     * @return true when virtual GUIs may open on this Minecraft version
     */
    public static boolean isGuiSafe() {
        return allProbesPassed();
    }

    /**
     * @return true when container-click routing (the mixin + bridge) is
     *         usable; false means clicks on Solidus menus would fall through
     *         to vanilla handling and must be treated as broken
     */
    public static boolean isClickRoutingAvailable() {
        return allProbesPassed();
    }

    /**
     * One-line summary for logs and the /solidus-admin compat command.
     */
    public static String summary() {
        if (!verified) {
            return "compat: probe has not run yet";
        }
        if (failures.isEmpty()) {
            return "compat: all probes passed (GUI routing available)";
        }
        return "compat: " + failures.size() + " failed probe(s): "
            + String.join("; ", failures);
    }
}
