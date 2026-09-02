package com.solidus.api;

/**
 * Reference integration class demonstrating how other mods can use Solidus
 * via <b>pure reflection</b> with zero compile-time dependency.
 *
 * <p><b>IMPORTANT:</b> This class is provided as <b>reference code only</b>.
 * Other mods should copy and adapt this pattern into their own packages.
 * Do NOT depend on this class at compile time - it exists inside Solidus's
 * JAR purely as a working example.</p>
 *
 * <h3>CombatKeepMod Integration:</h3>
 * On combat death, deduct 15% of the victim's balance and give it to the killer:
 * <pre>{@code
 * // In your death callback:
 * if (SolidusIntegration.isSolidusAvailable()) {
 *     SolidusIntegration.applyDeathPenalty(victim, killer, 0.15);
 * }
 * }</pre>
 *
 * <h3>Adding to your fabric.mod.json:</h3>
 * <pre>{@code
 * "suggests": {
 *   "solidus": "*"
 * }
 * }</pre>
 *
 * <h3>How this works without compile-time dependency:</h3>
 * <ul>
 *   <li>All Solidus classes are accessed via {@code Class.forName()} and reflection</li>
 *   <li>No Solidus import statements are used (except Minecraft's own classes)</li>
 *   <li>If Solidus is not installed, all reflection calls fail gracefully</li>
 *   <li>Your mod compiles and runs perfectly without Solidus on the classpath</li>
 * </ul>
 *
 * @since 1.0.0
 */
public final class SolidusIntegration {

    private SolidusIntegration() {} // Utility class - no instantiation

    /**
     * Checks if Solidus is loaded via FabricLoader.
     * Safe to call at any time - no reflection needed.
     *
     * @return true if Solidus is present
     */
    public static boolean isSolidusAvailable() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
            .isModLoaded("solidus");
    }

    /**
     * Gets the SolidusAPI instance via reflection.
     * Returns null if Solidus is not loaded or not yet initialized.
     *
     * @return The SolidusAPI instance, or null if unavailable
     */
    public static Object getAPI() {
        try {
            Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
            java.lang.reflect.Method getInstance = apiClass.getMethod("getInstance");
            return getInstance.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies a death penalty: deducts a percentage of the victim's balance
     * and transfers it to the killer. All operations are fully async and
     * thread-safe.
     *
     * <p>This method uses <b>pure reflection</b> - zero compile-time
     * dependency on Solidus. If Solidus is not loaded, this method
     * returns immediately without doing anything.</p>
     *
     * <p><b>Audit 2.1.3 rewrite:</b> the previous reference implementation
     * chained subtractBalance(victim) then addBalance(killer) as two separate
     * transactions with a manual refund ladder - exactly the crash-window
     * money-destruction pattern Solidus Core eliminated with
     * {@code transferAtomic} (a crash between the legs, or a failed refund,
     * destroyed the penalty money with no record). It also bypassed the
     * transaction-hook veto/notify contract, so Governance-style limits and
     * taxes never saw death transfers. Companion mods that copied this file
     * faithfully replicated the bug. The replacement performs ONE atomic
     * {@code SolidusAPI.transferOffline} call: both legs commit inside one
     * SQLite transaction, hooks see the transfer, and no refund path is
     * needed because there is no window between the legs.</p>
     *
     * @param victim         The player who died
     * @param killer         The player who killed them
     * @param penaltyPercent The percentage to transfer (0.15 = 15%)
     */
    @SuppressWarnings("unchecked")
    public static void applyDeathPenalty(
            net.minecraft.server.level.ServerPlayer victim,
            net.minecraft.server.level.ServerPlayer killer,
            double penaltyPercent) {

        if (!isSolidusAvailable()) return;

        try {
            Object api = getAPI();
            if (api == null) return;

            Class<?> apiClass = api.getClass();

            // Step 1: Get the victim's balance via reflection
            java.lang.reflect.Method getBalance = apiClass.getMethod(
                "getBalance", net.minecraft.server.level.ServerPlayer.class);
            java.util.concurrent.CompletableFuture<Double> balanceFuture =
                (java.util.concurrent.CompletableFuture<Double>) getBalance.invoke(api, victim);

            balanceFuture.thenAccept(balance -> {
                if (balance == null || balance <= 0) return;

                double penalty = Math.floor(balance * penaltyPercent * 100) / 100.0;
                if (penalty < 0.01) return; // Below minimum transaction

                try {
                    // Step 2: ONE atomic transfer - both legs (deduct victim,
                    // credit killer) commit inside a single SQLite transaction.
                    // No crash window, no manual refund ladder, and the
                    // transfer hooks (limits / freezes / taxes) see it.
                    java.lang.reflect.Method transferOffline = apiClass.getMethod(
                        "transferOffline",
                        java.util.UUID.class, String.class,
                        java.util.UUID.class, String.class,
                        double.class);
                    java.util.concurrent.CompletableFuture<Object> transferFuture =
                        (java.util.concurrent.CompletableFuture<Object>) transferOffline.invoke(
                            api,
                            victim.getUUID(), victim.getName().getString(),
                            killer.getUUID(), killer.getName().getString(),
                            penalty);

                    transferFuture.thenAccept(transferResult -> {
                        if (transferResult == null) return;

                        try {
                            java.lang.reflect.Method success = transferResult.getClass().getMethod("success");
                            boolean ok = (boolean) success.invoke(transferResult);

                            victim.level().getServer().execute(() -> {
                                String formattedPenalty = String.format("%,.2f S$", penalty);
                                if (ok) {
                                    victim.sendSystemMessage(
                                        net.minecraft.network.chat.Component.literal(
                                            "[Solidus] Death penalty: -" + formattedPenalty)
                                        .withStyle(net.minecraft.ChatFormatting.RED));
                                    killer.sendSystemMessage(
                                        net.minecraft.network.chat.Component.literal(
                                            "[Solidus] Kill reward: +" + formattedPenalty)
                                        .withStyle(net.minecraft.ChatFormatting.GREEN));
                                } else {
                                    // Atomic transfer rejected (insufficient funds,
                                    // balance cap, or a hook veto) - NOTHING was moved,
                                    // so there is nothing to refund.
                                    java.lang.reflect.Method message = null;
                                    try {
                                        message = transferResult.getClass().getMethod("message");
                                        String reason = (String) message.invoke(transferResult);
                                        victim.sendSystemMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                "[Solidus] Death penalty failed: " + reason)
                                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
                                    } catch (Exception ignored) {
                                        victim.sendSystemMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                "[Solidus] Death penalty failed.")
                                            .withStyle(net.minecraft.ChatFormatting.YELLOW));
                                    }
                                }
                            });

                            // Log the transactions via reflection (best-effort,
                            // non-critical)
                            if (ok) {
                                logDeathTransaction(api, victim, killer, penalty, penaltyPercent);
                            }
                        } catch (Exception ignored) {
                            // Reading the TransferResult failed - the money itself
                            // moved atomically or not at all, nothing to compensate.
                        }
                    });
                } catch (Exception e) {
                    // transferOffline unavailable - nothing has moved, no refund needed
                }
            });
        } catch (Exception e) {
            // Solidus present but API shape changed / not initialized - no-op
        }
    }

    /**
     * Logs death penalty and reward transactions to the Solidus transaction log.
     * Pure reflection - no Solidus imports needed.
     */
    private static void logDeathTransaction(
            Object api,
            net.minecraft.server.level.ServerPlayer victim,
            net.minecraft.server.level.ServerPlayer killer,
            double penalty,
            double penaltyPercent) {
        try {
            java.lang.reflect.Method getLog = api.getClass().getMethod("getTransactionLog");
            Object txLog = getLog.invoke(api);
            if (txLog == null) return;

            Class<?> logClass = txLog.getClass();
            Class<?> typeClass = Class.forName("com.solidus.economy.TransactionLog$Type");

            java.lang.reflect.Method fromCode = typeClass.getMethod("fromCode", String.class);
            java.lang.reflect.Method logMethod = logClass.getMethod(
                "log", typeClass,
                java.util.UUID.class, String.class,
                java.util.UUID.class, String.class,
                double.class, String.class, int.class, String.class);

            // Log DEATH_PENALTY for victim
            Object deathPenaltyType = fromCode.invoke(null, "DEATH_PENALTY");
            logMethod.invoke(txLog,
                deathPenaltyType,
                victim.getUUID(), victim.getName().getString(),
                killer.getUUID(), killer.getName().getString(),
                penalty, null, 0,
                "Death penalty: " + (int)(penaltyPercent * 100) + "% of balance");

            // Log DEATH_REWARD for killer
            Object deathRewardType = fromCode.invoke(null, "DEATH_REWARD");
            logMethod.invoke(txLog,
                deathRewardType,
                killer.getUUID(), killer.getName().getString(),
                victim.getUUID(), victim.getName().getString(),
                penalty, null, 0,
                "Kill reward: " + (int)(penaltyPercent * 100) + "% of victim's balance");

        } catch (Exception e) {
            // Transaction logging failure is non-critical
        }
    }
}
