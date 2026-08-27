package com.solidus.api;

import com.solidus.SolidusMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Permission configuration loaded from {@code config/solidus/permissions.json}.
 *
 * <p>Allows server owners to customize the default OP level required for
 * each Solidus command when LuckPerms is not installed. When LuckPerms IS
 * installed, this file is ignored - LuckPerms manages all permissions.</p>
 *
 * <h3>Example permissions.json:</h3>
 * <pre>{@code
 * {
 *   "solidus.command.balance": { "default": 0, "description": "Check your balance" },
 *   "solidus.command.baltop": { "default": 0, "description": "View leaderboard" },
 *   "solidus.analytics.dashboard": { "default": 2, "description": "View analytics dashboard" }
 * }
 * }</pre>
 *
 * <p>OP Levels: 0=All Players, 1=VIPs, 2=Moderators, 3=Admins, 4=Owner</p>
 *
 * @since 2.1.0
 */
public final class PermissionConfig {

    private static volatile PermissionConfig instance;

    private final Map<String, Integer> opLevelOverrides;
    private final Path configPath;

    private PermissionConfig(Path configDir) {
        this.configPath = configDir.resolve("solidus").resolve("permissions.json");
        this.opLevelOverrides = new LinkedHashMap<>();
        load();
    }

    /**
     * Initializes the PermissionConfig singleton. Called once by SolidusMod during startup.
     *
     * @param configDir The FabricLoader config directory
     */
    public static void initialize(Path configDir) {
        if (instance != null) {
            SolidusMod.LOGGER.warn("[Solidus] PermissionConfig already initialized.");
            return;
        }
        instance = new PermissionConfig(configDir);
        SolidusMod.LOGGER.info("[Solidus] PermissionConfig loaded. {} custom permission overrides.",
            instance.opLevelOverrides.size());
    }

    /**
     * Gets the PermissionConfig instance.
     *
     * @return The config instance, or null if not yet initialized
     */
    public static PermissionConfig getInstance() {
        return instance;
    }

    /**
     * Gets the configured OP level for a permission node.
     *
     * @param permission The permission node
     * @return The OP level from config, or null if not overridden
     */
    public Integer getOpLevel(String permission) {
        return opLevelOverrides.get(permission);
    }

    /**
     * Returns an unmodifiable view of all configured overrides.
     *
     * @return Map of permission node -> OP level
     */
    public Map<String, Integer> getOverrides() {
        return Collections.unmodifiableMap(opLevelOverrides);
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reload() {
        opLevelOverrides.clear();
        load();
        SolidusMod.LOGGER.info("[Solidus] PermissionConfig reloaded. {} custom overrides.", opLevelOverrides.size());
    }

    // -- Private Implementation ----------------------------------

    private void load() {
        // Ensure parent directory exists
        try {
            Files.createDirectories(configPath.getParent());
        } catch (IOException e) {
            SolidusMod.LOGGER.error("[Solidus] Failed to create permissions config directory", e);
            return;
        }

        // If config file doesn't exist, generate a default one
        if (!Files.exists(configPath)) {
            generateDefaultConfig();
            return;
        }

        // Load existing config
        try {
            String json = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                String key = entry.getKey();

                // Skip comment keys
                if (key.startsWith("_")) continue;

                if (entry.getValue().isJsonObject()) {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    if (obj.has("default") && obj.get("default").isJsonPrimitive()) {
                        int opLevel = obj.get("default").getAsInt();
                        if (opLevel >= 0 && opLevel <= 4) {
                            opLevelOverrides.put(key, opLevel);
                        } else {
                            SolidusMod.LOGGER.warn("[Solidus] Invalid OP level {} for '{}' in permissions.json (must be 0-4). Using default.",
                                opLevel, key);
                        }
                    }
                }
            }

            SolidusMod.LOGGER.info("[Solidus] Loaded {} permission overrides from permissions.json", opLevelOverrides.size());
        } catch (Exception e) {
            SolidusMod.LOGGER.error("[Solidus] Failed to load permissions.json. Using hardcoded defaults.", e);
        }
    }

    /**
     * Generates a default permissions.json with all permission nodes
     * and their hardcoded default OP levels.
     */
    private void generateDefaultConfig() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject root = new JsonObject();

            // Header comments (using _ prefix to mark as comments)
            root.addProperty("_comment", "Solidus Permission Configuration - Customizes default access levels for each command");
            root.addProperty("_docs", "OP Levels: 0=All Players, 1=VIPs, 2=Moderators, 3=Admins, 4=Owner");
            root.addProperty("_luckperms", "If LuckPerms is installed, this file is IGNORED - LuckPerms manages all permissions");
            root.addProperty("_reload", "Run /solidus reload (if available) to reload this file without restarting the server");

            // Core economy commands
            addPermissionEntry(root, SolidusPermissions.BALANCE, 0, "Check your balance - /balance, /bal");
            addPermissionEntry(root, SolidusPermissions.PAY, 0, "Pay an online player - /pay <player> <amount>");
            addPermissionEntry(root, SolidusPermissions.PAY_OFFLINE, 0, "Pay an offline player - /pay offline <name> <amount>");
            addPermissionEntry(root, SolidusPermissions.BALTOP, 0, "View leaderboard - /baltop");
            addPermissionEntry(root, SolidusPermissions.SHOP, 0, "Open shop GUI - /shop");
            addPermissionEntry(root, SolidusPermissions.SHOP_SEARCH, 0, "Search shop items - /shop search");
            addPermissionEntry(root, SolidusPermissions.SHOP_RELOAD, 2, "Hot-reload shop.json - /shop reload (admin only)");
            addPermissionEntry(root, SolidusPermissions.SELL, 0, "Sell items - /sell gui, /sell all");
            addPermissionEntry(root, SolidusPermissions.AUCTION_VIEW, 0, "View auction house - /ah");
            addPermissionEntry(root, SolidusPermissions.AUCTION_SELL, 0, "List item on auction - /ah sell");
            addPermissionEntry(root, SolidusPermissions.AUCTION_COLLECT, 0, "Collect expired items - /ah collect");
            addPermissionEntry(root, SolidusPermissions.AUCTION_CANCEL, 0, "Cancel your listing - /ah cancel");
            addPermissionEntry(root, SolidusPermissions.AUCTION_SORT, 0, "Sort listings - /ah sort");
            addPermissionEntry(root, SolidusPermissions.TRANSACTIONS, 0, "View transaction history - /transactions");

            // Analytics commands
            addPermissionEntry(root, SolidusPermissions.ANALYTICS, 2, "View analytics dashboard - /analytics");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_WEALTH, 2, "View wealth distribution - /analytics wealth");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_INFLATION, 2, "View inflation report - /analytics inflation");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_TOP, 2, "View top rankings - /analytics top");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_HISTORY, 2, "View daily metrics - /analytics history");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_HEALTH, 2, "View economy health - /analytics health");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_FRAUD, 2, "View fraud detection - /analytics fraud");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_SNAPSHOT, 3, "Force snapshot - /analytics snapshot");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_EXPORT, 3, "Export data - /analytics export");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_DASHBOARD_MANAGE, 3, "Manage dashboard - /analytics dashboard");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_LICENSE, 3, "View license - /analytics license");
            addPermissionEntry(root, SolidusPermissions.ANALYTICS_FINGERPRINT, 3, "View fingerprint - /analytics fingerprint");
            addPermissionEntry(root, SolidusPermissions.INFLATION, 2, "View inflation - /inflation");

            // Territory commands
            addPermissionEntry(root, SolidusPermissions.LAND, 0, "Use /land commands");
            addPermissionEntry(root, SolidusPermissions.LAND_CLAIM, 0, "Claim territory - /land claim");
            addPermissionEntry(root, SolidusPermissions.LAND_ADMIN, 2, "Admin territory commands - /landadmin");
            addPermissionEntry(root, SolidusPermissions.LAND_BYPASS, 2, "Bypass territory protection");

            // Governance commands
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE, 2, "View governance status - /governance");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_AUDIT, 2, "View audit trail - /governance audit");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_TAX, 3, "Manage tax rates - /governance tax");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_INTERVENTION, 3, "Economy intervention - /governance intervention");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_RECOVERY, 3, "Recovery operations - /governance recovery");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_AUTOMATION, 3, "Automation control - /governance automation");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_LIMITS_VIEW, 2, "View transaction limits - /governance limits");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_LIMITS_SET, 3, "Set transaction limits - /governance limits set");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_DISCORD_VIEW, 2, "View Discord integration - /governance discord");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_DISCORD_SET, 3, "Configure Discord - /governance discord set");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_EVENT_VIEW, 2, "View events - /governance event list");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_EVENT_MANAGE, 3, "Manage events - /governance event create");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_PROFILE, 2, "View player profile - /governance profile");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_POLICY_VIEW, 2, "View policies - /governance policy list");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_POLICY_MANAGE, 3, "Manage policies - /governance policy save");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_RULES_VIEW, 2, "View rules - /governance rules list");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_RULES_MANAGE, 3, "Manage rules - /governance rules add");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_SIMULATION, 3, "Economy simulation - /governance simulation");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_LICENSE, 3, "View license - /governance license");
            addPermissionEntry(root, SolidusPermissions.GOVERNANCE_FINGERPRINT, 3, "View fingerprint - /governance fingerprint");

            String json = gson.toJson(root);
            Files.writeString(configPath, json);

            SolidusMod.LOGGER.info("[Solidus] Generated default permissions.json at {}", configPath);
        } catch (Exception e) {
            SolidusMod.LOGGER.error("[Solidus] Failed to generate default permissions.json", e);
        }
    }

    private void addPermissionEntry(JsonObject root, String permission, int defaultOpLevel, String description) {
        JsonObject entry = new JsonObject();
        entry.addProperty("default", defaultOpLevel);
        entry.addProperty("description", description);
        root.add(permission, entry);
    }
}
