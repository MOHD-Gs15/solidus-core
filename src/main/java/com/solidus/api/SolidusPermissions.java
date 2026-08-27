package com.solidus.api;

/**
 * All Solidus permission node constants across all modules.
 *
 * <p><b>Naming Convention:</b> {@code solidus.<module>.<category>.<action>}</p>
 *
 * <p>Each permission has a default OP level that is used when
 * LuckPerms is not installed. OP levels:</p>
 * <ul>
 *   <li>0 = All players (default for economy commands)</li>
 *   <li>1 = VIPs/Moderators (can bypass spawn protection)</li>
 *   <li>2 = Moderators (can use admin commands)</li>
 *   <li>3 = Admins (full admin access)</li>
 *   <li>4 = Server Owner (full control)</li>
 * </ul>
 *
 * <h3>Wildcard Support (LuckPerms only):</h3>
 * <ul>
 *   <li>{@code solidus.*} - Everything</li>
 *   <li>{@code solidus.command.*} - All core economy commands</li>
 *   <li>{@code solidus.analytics.*} - All analytics</li>
 *   <li>{@code solidus.territory.*} - All territory</li>
 *   <li>{@code solidus.governance.*} - All governance</li>
 * </ul>
 *
 * @since 2.1.0
 */
public final class SolidusPermissions {

    private SolidusPermissions() {} // Prevent instantiation

    // ===========================================================
    //  CORE - Economy & Commerce Commands
    //  Default OP Level: 0 (available to all players)
    // ===========================================================

    /** Check your balance - /balance, /bal */
    public static final String BALANCE = "solidus.command.balance";

    /** Pay an online player - /pay <player> <amount> */
    public static final String PAY = "solidus.command.pay";

    /** Pay an offline player - /pay offline <name> <amount> */
    public static final String PAY_OFFLINE = "solidus.command.pay.offline";

    /** View leaderboard - /baltop */
    public static final String BALTOP = "solidus.command.baltop";

    /** Open the shop GUI - /shop */
    public static final String SHOP = "solidus.command.shop";

    /** Search shop items - /shop search */
    public static final String SHOP_SEARCH = "solidus.command.shop.search";

    /** Hot-reload shop.json - /shop reload (admin only, OP 2+) */
    public static final String SHOP_RELOAD = "solidus.command.shop.reload";

    /** Sell items - /sell gui, /sell all */
    public static final String SELL = "solidus.command.sell";

    /** View auction house - /ah */
    public static final String AUCTION_VIEW = "solidus.command.auction";

    /** List item on auction - /ah sell */
    public static final String AUCTION_SELL = "solidus.command.auction.sell";

    /** Collect expired auction items - /ah collect */
    public static final String AUCTION_COLLECT = "solidus.command.auction.collect";

    /** Cancel own auction listing - /ah cancel */
    public static final String AUCTION_CANCEL = "solidus.command.auction.cancel";

    /** Sort auction listings - /ah sort */
    public static final String AUCTION_SORT = "solidus.command.auction.sort";

    /** View transaction history - /transactions */
    public static final String TRANSACTIONS = "solidus.command.transactions";

    // ===========================================================
    //  ANALYTICS - Data Analytics Commands
    // ===========================================================

    /** View analytics dashboard - /analytics */
    public static final String ANALYTICS = "solidus.analytics.dashboard";

    /** View wealth distribution - /analytics wealth */
    public static final String ANALYTICS_WEALTH = "solidus.analytics.wealth";

    /** View inflation report - /analytics inflation */
    public static final String ANALYTICS_INFLATION = "solidus.analytics.inflation";

    /** View top rankings - /analytics top */
    public static final String ANALYTICS_TOP = "solidus.analytics.top";

    /** View daily metrics history - /analytics history */
    public static final String ANALYTICS_HISTORY = "solidus.analytics.history";

    /** View economy health - /analytics health */
    public static final String ANALYTICS_HEALTH = "solidus.analytics.health";

    /** View fraud detection - /analytics fraud */
    public static final String ANALYTICS_FRAUD = "solidus.analytics.fraud";

    /** Force analytics snapshot - /analytics snapshot (OP 3+) */
    public static final String ANALYTICS_SNAPSHOT = "solidus.analytics.snapshot";

    /** Export analytics data - /analytics export (OP 3+) */
    public static final String ANALYTICS_EXPORT = "solidus.analytics.export";

    /** Manage analytics dashboard - /analytics dashboard (OP 3+) */
    public static final String ANALYTICS_DASHBOARD_MANAGE = "solidus.analytics.dashboard.manage";

    /** View/manage analytics license - /analytics license (OP 3+) */
    public static final String ANALYTICS_LICENSE = "solidus.analytics.license";

    /** View analytics fingerprint - /analytics fingerprint (OP 3+) */
    public static final String ANALYTICS_FINGERPRINT = "solidus.analytics.fingerprint";

    /** View inflation command - /inflation */
    public static final String INFLATION = "solidus.analytics.inflation.cmd";

    // ===========================================================
    //  TERRITORY - Land Claiming & Management
    // ===========================================================

    /** Use /land commands - base permission */
    public static final String LAND = "solidus.territory.land";

    /** Claim territory - /land claim */
    public static final String LAND_CLAIM = "solidus.territory.claim";

    /** Use land admin commands - /landadmin (OP 2+) */
    public static final String LAND_ADMIN = "solidus.territory.admin";

    /** Bypass territory protection (OP 2+) */
    public static final String LAND_BYPASS = "solidus.territory.bypass";

    // ===========================================================
    //  GOVERNANCE - Server Economy Governance
    // ===========================================================

    /** View governance status - /governance */
    public static final String GOVERNANCE = "solidus.governance.dashboard";

    /** View audit trail - /governance audit */
    public static final String GOVERNANCE_AUDIT = "solidus.governance.audit";

    /** Manage tax rates - /governance tax (OP 3+) */
    public static final String GOVERNANCE_TAX = "solidus.governance.tax";

    /** Economy intervention - /governance intervention (OP 3+) */
    public static final String GOVERNANCE_INTERVENTION = "solidus.governance.intervention";

    /** Recovery operations - /governance recovery (OP 3+) */
    public static final String GOVERNANCE_RECOVERY = "solidus.governance.recovery";

    /** Automation control - /governance automation (OP 3+) */
    public static final String GOVERNANCE_AUTOMATION = "solidus.governance.automation";

    /** View transaction limits - /governance limits */
    public static final String GOVERNANCE_LIMITS_VIEW = "solidus.governance.limits.view";

    /** Set transaction limits - /governance limits set (OP 3+) */
    public static final String GOVERNANCE_LIMITS_SET = "solidus.governance.limits.set";

    /** View Discord integration - /governance discord */
    public static final String GOVERNANCE_DISCORD_VIEW = "solidus.governance.discord.view";

    /** Configure Discord integration - /governance discord set (OP 3+) */
    public static final String GOVERNANCE_DISCORD_SET = "solidus.governance.discord.set";

    /** View events - /governance event list */
    public static final String GOVERNANCE_EVENT_VIEW = "solidus.governance.event.view";

    /** Create/cancel events - /governance event create (OP 3+) */
    public static final String GOVERNANCE_EVENT_MANAGE = "solidus.governance.event.manage";

    /** View player profile - /governance profile */
    public static final String GOVERNANCE_PROFILE = "solidus.governance.profile";

    /** View policies - /governance policy list */
    public static final String GOVERNANCE_POLICY_VIEW = "solidus.governance.policy.view";

    /** Manage policies - /governance policy save/load/delete (OP 3+) */
    public static final String GOVERNANCE_POLICY_MANAGE = "solidus.governance.policy.manage";

    /** View rules - /governance rules list */
    public static final String GOVERNANCE_RULES_VIEW = "solidus.governance.rules.view";

    /** Manage rules - /governance rules add/delete (OP 3+) */
    public static final String GOVERNANCE_RULES_MANAGE = "solidus.governance.rules.manage";

    /** Economy simulation - /governance simulation (OP 3+) */
    public static final String GOVERNANCE_SIMULATION = "solidus.governance.simulation";

    /** View/manage governance license - /governance license (OP 3+) */
    public static final String GOVERNANCE_LICENSE = "solidus.governance.license";

    /** View governance fingerprint - /governance fingerprint (OP 3+) */
    public static final String GOVERNANCE_FINGERPRINT = "solidus.governance.fingerprint";

    // ===========================================================
    //  DEFAULT OP LEVEL MAPPING
    //  Used by PermissionConfig to generate permissions.json
    // ===========================================================

    /**
     * Returns the hardcoded default OP level for a permission node.
     * This is used when LuckPerms is not installed AND the config
     * file doesn't override it.
     *
     * @param permission The permission node
     * @return The default OP level (0 = all players)
     */
    public static int getDefaultOpLevel(String permission) {
        // Core commands - all players
        if (permission.startsWith("solidus.command.")) {
            // Admin-only sub-commands need a higher OP level
            if (permission.equals(SHOP_RELOAD)) return 2;
            return 0;
        }

        // Analytics - OP 2 for viewing, OP 3 for management
        if (permission.equals(ANALYTICS_SNAPSHOT)
            || permission.equals(ANALYTICS_EXPORT)
            || permission.equals(ANALYTICS_DASHBOARD_MANAGE)
            || permission.equals(ANALYTICS_LICENSE)
            || permission.equals(ANALYTICS_FINGERPRINT)) return 3;
        if (permission.startsWith("solidus.analytics.")) return 2;

        // Territory - OP 0 for players, OP 2 for admin
        if (permission.equals(LAND_ADMIN) || permission.equals(LAND_BYPASS)) return 2;
        if (permission.startsWith("solidus.territory.")) return 0;

        // Governance - OP 2 for viewing, OP 3 for management
        if (permission.equals(GOVERNANCE_TAX)
            || permission.equals(GOVERNANCE_INTERVENTION)
            || permission.equals(GOVERNANCE_RECOVERY)
            || permission.equals(GOVERNANCE_AUTOMATION)
            || permission.equals(GOVERNANCE_LIMITS_SET)
            || permission.equals(GOVERNANCE_DISCORD_SET)
            || permission.equals(GOVERNANCE_EVENT_MANAGE)
            || permission.equals(GOVERNANCE_POLICY_MANAGE)
            || permission.equals(GOVERNANCE_RULES_MANAGE)
            || permission.equals(GOVERNANCE_SIMULATION)
            || permission.equals(GOVERNANCE_LICENSE)
            || permission.equals(GOVERNANCE_FINGERPRINT)) return 3;
        if (permission.startsWith("solidus.governance.")) return 2;

        // Unknown permission - default to OP 2 (safe default)
        return 2;
    }
}
