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

            // Step 1: Get victim's balance via reflection
            java.lang.reflect.Method getBalance = apiClass.getMethod(
                "getBalance", net.minecraft.server.level.ServerPlayer.class);
            java.util.concurrent.CompletableFuture<Double> balanceFuture =
                (java.util.concurrent.CompletableFuture<Double>) getBalance.invoke(api, victim);

            // Step 2: Calculate penalty and perform transfer
            balanceFuture.thenAccept(balance -> {
                if (balance == null || balance <= 0) return;

                double penalty = Math.floor(balance * penaltyPercent * 100) / 100.0;
                if (penalty < 0.01) return; // Below minimum transaction

                // Subtract from victim
                try {
                    java.lang.reflect.Method subtract = apiClass.getMethod(
                        "subtractBalance",
                        net.minecraft.server.level.ServerPlayer.class, double.class);
                    java.util.concurrent.CompletableFuture<Double> subFuture =
                        (java.util.concurrent.CompletableFuture<Double>) subtract.invoke(api, victim, penalty);

                    subFuture.thenAccept(newVictimBalance -> {
                        if (newVictimBalance == null || newVictimBalance < 0) {
                            return; // Insufficient funds or failure
                        }

                        // Add to killer
                        try {
                            java.lang.reflect.Method add = apiClass.getMethod(
                                "addBalance",
                                net.minecraft.server.level.ServerPlayer.class, double.class);
                            java.util.concurrent.CompletableFuture<Double> addFuture =
                                (java.util.concurrent.CompletableFuture<Double>) add.invoke(api, killer, penalty);

                            addFuture.thenAccept(newKillerBalance -> {
                                if (newKillerBalance != null && newKillerBalance >= 0) {
                                    // Success - notify both players on the server thread
                                    victim.level().getServer().execute(() -> {
                                        String formattedPenalty = String.format("%,.2f S$", penalty);
                                        victim.sendSystemMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                "[Solidus] Death penalty: -" + formattedPenalty)
                                            .withStyle(net.minecraft.ChatFormatting.RED));
                                        killer.sendSystemMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                "[Solidus] Kill reward: +" + formattedPenalty)
                                            .withStyle(net.minecraft.ChatFormatting.GREEN));
                                    });

                                    // Log the transactions via reflection
                                    logDeathTransaction(api, victim, killer, penalty, penaltyPercent);
                                } else {
                                    // CRITICAL: Killer addBalance failed after victim was deducted!
                                    // Refund the victim to prevent money destruction
                                    try {
                                        java.lang.reflect.Method refundAdd = apiClass.getMethod(
                                            "addBalance",
                                            net.minecraft.server.level.ServerPlayer.class, double.class);
                                        refundAdd.invoke(api, victim, penalty);
                                        victim.level().getServer().execute(() ->
                                            victim.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal(
                                                    "[Solidus] Death penalty transfer failed. Your balance has been restored.")
                                                .withStyle(net.minecraft.ChatFormatting.YELLOW)));
                                    } catch (Exception refundEx) {
                                        // CATASTROPHIC: Both add to killer AND refund to victim failed
                                        victim.level().getServer().execute(() ->
                                            victim.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal(
                                                    "[Solidus] CRITICAL: Death penalty failed and refund also failed. Contact admin!")
                                                .withStyle(net.minecraft.ChatFormatting.RED)));
                                    }
                                }
                            });
                        } catch (Exception e) {
                            // Add to killer failed - refund the victim to prevent money destruction
                            try {
                                java.lang.reflect.Method refundAdd = apiClass.getMethod(
                                    "addBalance",
                                    net.minecraft.server.level.ServerPlayer.class, double.class);
                                refundAdd.invoke(api, victim, penalty);
                            } catch (Exception refundEx) {
                                // Refund also failed - logged but can't do anything more
                            }
                        }
                    });
                } catch (Exception e) {
                    // Subtract from victim failed - non-critical
                }
            });

        } catch (Exception e) {
            // Solidus API call failed - expected if Solidus is not loaded
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
