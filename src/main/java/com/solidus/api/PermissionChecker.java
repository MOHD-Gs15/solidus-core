package com.solidus.api;

import com.solidus.SolidusMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Unified permission checking system for Solidus.
 *
 * <p>Provides fine-grained permission node support with optional LuckPerms integration.
 * When LuckPerms is not installed, falls back to vanilla OP levels defined in
 * {@code permissions.json} config (or hardcoded defaults).</p>
 *
 * <h3>Permission Resolution Order:</h3>
 * <ol>
 *   <li>If LuckPerms is installed: Check the permission node via LuckPerms API (reflection)</li>
 *   <li>LuckPerms returns TRUE -> granted, FALSE -> denied</li>
 *   <li>LuckPerms returns UNDEFINED -> fall back to OP level from config</li>
 *   <li>If LuckPerms is NOT installed: Use vanilla OP level from config</li>
 * </ol>
 *
 * <h3>Wildcard Support (LuckPerms only):</h3>
 * <p>LuckPerms natively supports wildcards:</p>
 * <ul>
 *   <li>{@code solidus.*} - All Solidus permissions</li>
 *   <li>{@code solidus.command.*} - All core economy commands</li>
 *   <li>{@code solidus.analytics.*} - All analytics permissions</li>
 *   <li>{@code solidus.territory.*} - All territory permissions</li>
 *   <li>{@code solidus.governance.*} - All governance permissions</li>
 * </ul>
 *
 * <h3>Customization without LuckPerms:</h3>
 * <p>Server owners can edit {@code config/solidus/permissions.json} to change
 * the default OP level required for each command. For example, to make
 * {@code /baltop} admin-only, change its default from 0 to 2.</p>
 *
 * @since 2.1.0
 */
public final class PermissionChecker {

    private static Boolean luckPermsLoaded = null;

    // -- Reflection cache for LuckPerms API (avoid repeated lookups) --
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean reflectionFailed = false;
    private static java.lang.reflect.Method getApiMethod;
    private static java.lang.reflect.Method getUserManagerMethod;
    private static java.lang.reflect.Method getUserMethod;
    private static java.lang.reflect.Method getCachedDataMethod;
    private static java.lang.reflect.Method getPermissionDataMethod;
    private static java.lang.reflect.Method checkPermissionMethod;

    private PermissionChecker() {} // Prevent instantiation

    // -- Public API ----------------------------------------------

    /**
     * Checks whether the given CommandSourceStack has at least the
     * requested OP level using the Minecraft 26.1.x permission system.
     *
     * <p>In 26.1.x, the legacy {@code source.hasPermission(int)} method
     * was removed. The replacement is the {@link PermissionLevel} enum
     * combined with {@link Permission.HasCommandLevel}.</p>
     *
     * @param source  the command source to check
     * @param opLevel the OP level (0 = all players, 4 = owner)
     * @return {@code true} if the source has at least the requested level
     */
    private static boolean hasOpLevel(CommandSourceStack source, int opLevel) {
        int clamped = Math.max(0, Math.min(4, opLevel));
        PermissionLevel level = PermissionLevel.byId(clamped);
        return source.permissions().hasPermission(new Permission.HasCommandLevel(level));
    }

    /**
     * Checks whether the given ServerPlayer has at least the
     * requested OP level using the Minecraft 26.1.x permission system.
     *
     * @param player  the player to check
     * @param opLevel the OP level (0 = all players, 4 = owner)
     * @return {@code true} if the player has at least the requested level
     */
    private static boolean hasOpLevel(ServerPlayer player, int opLevel) {
        int clamped = Math.max(0, Math.min(4, opLevel));
        PermissionLevel level = PermissionLevel.byId(clamped);
        return player.permissions().hasPermission(new Permission.HasCommandLevel(level));
    }

    /**
     * Checks if a CommandSourceStack has a specific permission.
     *
     * @param source         The command source (player or console)
     * @param permission     The permission node (e.g., "solidus.command.balance")
     * @param defaultOpLevel The default OP level if no LuckPerms rule exists (0 = all players)
     * @return true if the source has the permission
     */
    public static boolean check(CommandSourceStack source, String permission, int defaultOpLevel) {
        // Resolve the effective OP level from config (allows server owners to customize)
        int effectiveOpLevel = resolveOpLevel(permission, defaultOpLevel);

        if (isLuckPermsLoaded()) {
            // 26.1.x: CommandSourceStack.getPlayer() no longer throws CommandSyntaxException
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                return checkLuckPerms(player.getUUID(), player, permission, effectiveOpLevel);
            }
            // Console execution - console always has permission
        }
        return hasOpLevel(source, effectiveOpLevel);
    }

    /**
     * Checks if a ServerPlayer has a specific permission.
     * Use this for runtime permission checks outside of command registration.
     *
     * @param player         The player to check
     * @param permission     The permission node
     * @param defaultOpLevel The default OP level (0 = all players)
     * @return true if the player has the permission
     */
    public static boolean check(ServerPlayer player, String permission, int defaultOpLevel) {
        int effectiveOpLevel = resolveOpLevel(permission, defaultOpLevel);

        if (isLuckPermsLoaded()) {
            return checkLuckPerms(player.getUUID(), player, permission, effectiveOpLevel);
        }
        return hasOpLevel(player, effectiveOpLevel);
    }

    /**
     * Creates a Brigadier requirement predicate for a permission node.
     *
     * <p>Usage example:</p>
     * <pre>{@code
     * Commands.literal("balance")
     *     .requires(PermissionChecker.require(SolidusPermissions.BALANCE, 0))
     *     .executes(ctx -> { ... })
     * }</pre>
     *
     * @param permission     The permission node
     * @param defaultOpLevel The default OP level
     * @return A predicate for use with Brigadier's .requires()
     */
    public static Predicate<CommandSourceStack> require(String permission, int defaultOpLevel) {
        return source -> check(source, permission, defaultOpLevel);
    }

    /**
     * Checks if LuckPerms is loaded on the server.
     *
     * @return true if LuckPerms is present
     */
    public static boolean isLuckPermsAvailable() {
        return isLuckPermsLoaded();
    }

    // -- LuckPerms Detection -------------------------------------

    private static boolean isLuckPermsLoaded() {
        if (luckPermsLoaded == null) {
            luckPermsLoaded = FabricLoader.getInstance().isModLoaded("luckperms");
            if (luckPermsLoaded) {
                SolidusMod.LOGGER.info("[Solidus] LuckPerms detected - fine-grained permission nodes are active.");
            } else {
                SolidusMod.LOGGER.info("[Solidus] LuckPerms not detected - using vanilla OP levels for permissions.");
            }
        }
        return luckPermsLoaded;
    }

    // -- Config-Based OP Level Resolution ------------------------

    /**
     * Resolves the effective OP level for a permission from the config.
     * Falls back to the hardcoded default if the config doesn't specify one.
     */
    private static int resolveOpLevel(String permission, int hardcodedDefault) {
        PermissionConfig config = PermissionConfig.getInstance();
        if (config != null) {
            Integer configLevel = config.getOpLevel(permission);
            if (configLevel != null) {
                return configLevel;
            }
        }
        return hardcodedDefault;
    }

    // -- LuckPerms Integration (Reflection) ----------------------

    /**
     * Initializes the LuckPerms API reflection cache.
     * Called lazily on first permission check when LuckPerms is detected.
     */
    private static synchronized void initLuckPermsReflection() {
        if (reflectionInitialized || reflectionFailed) return;

        try {
            // LuckPerms API: LuckPermsProvider.get()
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            getApiMethod = providerClass.getMethod("get");

            // LuckPerms API: api.getUserManager()
            Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms");
            getUserManagerMethod = apiClass.getMethod("getUserManager");

            // UserManager: getUser(UUID)
            Class<?> userManagerClass = Class.forName("net.luckperms.api.manager.UserManager");
            getUserMethod = userManagerClass.getMethod("getUser", UUID.class);

            // User: getCachedData()
            Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
            getCachedDataMethod = userClass.getMethod("getCachedData");

            // CachedDataManager: getPermissionData()
            Class<?> cachedDataClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
            getPermissionDataMethod = cachedDataClass.getMethod("getPermissionData");

            // PermissionData: checkPermission(String)
            Class<?> permissionDataClass = Class.forName("net.luckperms.api.cacheddata.PermissionData");
            checkPermissionMethod = permissionDataClass.getMethod("checkPermission", String.class);

            reflectionInitialized = true;
            SolidusMod.LOGGER.info("[Solidus] LuckPerms API reflection initialized successfully.");
        } catch (Exception e) {
            SolidusMod.LOGGER.warn("[Solidus] Failed to initialize LuckPerms reflection. Falling back to OP levels: {}", e.getMessage());
            reflectionFailed = true;
            luckPermsLoaded = false; // Disable LuckPerms checks entirely
        }
    }

    /**
     * Checks a permission via LuckPerms API using reflection.
     *
     * @param playerUuid     The player's UUID
     * @param player         The ServerPlayer (for OP level fallback)
     * @param permission     The permission node
     * @param effectiveOpLevel The effective OP level (from config)
     * @return true if the permission is granted
     */
    private static boolean checkLuckPerms(UUID playerUuid, ServerPlayer player,
                                           String permission, int effectiveOpLevel) {
        if (!reflectionInitialized) {
            initLuckPermsReflection();
            if (!reflectionInitialized) {
                // Reflection init failed - fall back to OP level
                return hasOpLevel(player, effectiveOpLevel);
            }
        }

        try {
            Object api = getApiMethod.invoke(null);
            Object userManager = getUserManagerMethod.invoke(api);
            Object user = getUserMethod.invoke(userManager, playerUuid);

            if (user == null) {
                // User not in LuckPerms cache - fall back to OP level
                return hasOpLevel(player, effectiveOpLevel);
            }

            Object cachedData = getCachedDataMethod.invoke(user);
            Object permissionData = getPermissionDataMethod.invoke(cachedData);
            Object result = checkPermissionMethod.invoke(permissionData, permission);

            // Tristate: TRUE, FALSE, UNDEFINED
            String resultStr = result.toString();
            return switch (resultStr) {
                case "TRUE" -> true;
                case "FALSE" -> false;
                default -> hasOpLevel(player, effectiveOpLevel); // UNDEFINED -> fall back
            };
        } catch (Exception e) {
            SolidusMod.LOGGER.debug("[Solidus] LuckPerms check failed for '{}': {}", permission, e.getMessage());
            return hasOpLevel(player, effectiveOpLevel);
        }
    }
}
