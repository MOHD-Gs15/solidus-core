package com.solidus.economy;

import com.google.gson.JsonObject;
import com.solidus.SolidusMod;
import com.solidus.util.ConfigManager;

/**
 * Storage selection configuration ({@code config/solidus/storage.json}).
 *
 * <p>DB scaling plan, Phase 1 (2.1.5): introduces the config file with
 * {@code "type": "sqlite"} as the default. Phase 2 (2.2.0) activates the
 * {@code "mysql"} branch backed by {@link MySqlStorage} for multi-server
 * networks with unified balances.</p>
 *
 * <p>Default file created on first run:</p>
 * <pre>{@code
 * {
 *   "type": "sqlite",
 *   "mysql": {
 *     "host": "127.0.0.1", "port": 3306, "database": "solidus",
 *     "user": "solidus", "password": "CHANGE_ME",
 *     "pool": { "maxSize": 10, "connectionTimeoutMs": 5000 },
 *     "useSsl": true
 *   }
 * }
 * }</pre>
 *
 * <p>Password handling: {@code mysql.password} supports the env override
 * {@code SOLIDUS_DB_PASSWORD} (wins when set) — the connection string is
 * never logged.</p>
 *
 * <p>Redis block (2.2.1, DB scaling plan §6): OPTIONAL coordination layer.
 * Disabled by default; a MySQL-only network is fully functional without it.
 * When enabled it adds: an L2 balance cache in front of MySQL read traffic,
 * a pub/sub invalidation bus ({@code solidus:bal:inv}), and instant
 * network-wide notification delivery ({@code solidus:events}). Redis is
 * NEVER the source of truth — the shared database stays authoritative, and
 * a Redis outage degrades to plain database reads.</p>
 *
 * <p>Default file created on first run (2.2.1):</p>
 * <pre>{@code
 * {
 *   "type": "sqlite",
 *   "mysql": {
 *     "host": "127.0.0.1", "port": 3306, "database": "solidus",
 *     "user": "solidus", "password": "CHANGE_ME",
 *     "pool": { "maxSize": 10, "connectionTimeoutMs": 5000 },
 *     "useSsl": true
 *   },
 *   "redis": {
 *     "enabled": false, "uri": "redis://127.0.0.1:6379/0",
 *     "balanceTtlSeconds": 30
 *   }
 * }
 * }</pre>
 */
public final class StorageConfig {

    /** Selected storage backend kind. */
    public enum Type { SQLITE, MYSQL }

    /** MySQL/MariaDB connection + pool settings. */
    public record MySqlSettings(
            String host,
            int port,
            String database,
            String user,
            String password,
            int maxPoolSize,
            int connectionTimeoutMs,
            boolean useSsl) {
    }

    /**
     * Optional Redis coordination settings (2.2.1). {@link #disabled()} is
     * the safe default used when the block is missing or malformed.
     */
    public record RedisSettings(
            boolean enabled,
            String uri,
            String passwordEnv,
            int balanceTtlSeconds) {

        public static RedisSettings disabled() {
            return new RedisSettings(false, "redis://127.0.0.1:6379/0", "SOLIDUS_REDIS_PASSWORD", 30);
        }
    }

    /**
     * Supply-integrity scheduler settings (2.2.4, DB scaling plan §7).
     *
     * <p>{@code elected} is the network election: the scheduled checker runs
     * only where it is true. The default (true) is correct for every
     * single-server install; on a MySQL network the runbook sets
     * {@code "elected": true} on exactly ONE server's storage.json — the
     * check is read-only, so a misconfiguration only duplicates warnings,
     * never affects money.</p>
     */
    public record IntegritySettings(
            boolean enabled,
            int intervalMinutes,
            double tolerance,
            boolean elected) {

        public static IntegritySettings defaults() {
            return new IntegritySettings(true, 30, 0.01, true);
        }
    }

    private static final String FILE_NAME = "storage.json";
    private static final String PASSWORD_ENV = "SOLIDUS_DB_PASSWORD";

    private final Type type;
    private final MySqlSettings mysql;
    private final RedisSettings redis;
    private final IntegritySettings integrity;

    private StorageConfig(Type type, MySqlSettings mysql, RedisSettings redis) {
        this(type, mysql, redis, IntegritySettings.defaults());
    }

    private StorageConfig(Type type, MySqlSettings mysql, RedisSettings redis,
                          IntegritySettings integrity) {
        this.type = type;
        this.mysql = mysql;
        this.redis = redis;
        this.integrity = integrity != null ? integrity : IntegritySettings.defaults();
    }

    public Type type() {
        return type;
    }

    public MySqlSettings mysql() {
        return mysql;
    }

    public RedisSettings redis() {
        return redis;
    }

    /** The supply-integrity scheduler block (2.2.4). Never null. */
    public IntegritySettings integrity() {
        return integrity;
    }

    /**
     * Loads {@code storage.json} from the Solidus config directory, creating
     * the default (SQLite) file on first run. Unknown/missing types fall back
     * to SQLITE — the economy must always come up, never fail on config.
     */
    public static StorageConfig load() {
        ConfigManager.copyDefaultIfMissing(FILE_NAME, FILE_NAME);
        JsonObject json = ConfigManager.loadJson(FILE_NAME);
        if (json == null) {
            return new StorageConfig(Type.SQLITE, null, RedisSettings.disabled());
        }
        IntegritySettings integrity = parseIntegrity(json);

        String rawType = json.has("type") && json.get("type").isJsonPrimitive()
                ? json.get("type").getAsString().trim().toLowerCase(java.util.Locale.ROOT)
                : "sqlite";

        MySqlSettings mysql = null;
        if ("mysql".equals(rawType) || "mariadb".equals(rawType)) {
            mysql = parseMysql(json.has("mysql") && json.get("mysql").isJsonObject()
                    ? json.getAsJsonObject("mysql") : new JsonObject());
            if (mysql == null) {
                // Invalid mysql block — never leave a server without economy.
                SolidusMod.LOGGER.error(
                    "storage.json: 'mysql' block is incomplete — falling back to SQLite.");
                return new StorageConfig(Type.SQLITE, null, parseRedis(json), integrity);
            }
            return new StorageConfig(Type.MYSQL, mysql, parseRedis(json), integrity);
        }
        if (!"sqlite".equals(rawType)) {
            SolidusMod.LOGGER.warn(
                "storage.json: unknown type '{}' — falling back to SQLite.", rawType);
        }
        return new StorageConfig(Type.SQLITE, null, parseRedis(json), integrity);
    }

    /** Parses the optional integrity block; missing/malformed means defaults. */
    private static IntegritySettings parseIntegrity(JsonObject root) {
        IntegritySettings def = IntegritySettings.defaults();
        if (!root.has("integrity") || !root.get("integrity").isJsonObject()) {
            return def;
        }
        JsonObject b = root.getAsJsonObject("integrity");
        boolean enabled = boolOr(b, "enabled", def.enabled());
        int minutes = Math.max(1, intOr(b, "intervalMinutes", def.intervalMinutes()));
        // Tolerance may arrive as a JSON number or a quoted string; a malformed
        // value keeps the default — it must never disable the check.
        double tolerance = def.tolerance();
        if (b.has("tolerance") && b.get("tolerance").isJsonPrimitive()) {
            try {
                tolerance = b.get("tolerance").getAsDouble();
            } catch (NumberFormatException | ClassCastException ignored) {
                // keep default
            }
        }
        boolean elected = boolOr(b, "elected", def.elected());
        return new IntegritySettings(enabled, minutes, Math.max(0.0, tolerance), elected);
    }

    /** Parses the optional redis block; missing/malformed means disabled. */
    private static RedisSettings parseRedis(JsonObject root) {
        if (!root.has("redis") || !root.get("redis").isJsonObject()) {
            return RedisSettings.disabled();
        }
        JsonObject r = root.getAsJsonObject("redis");
        boolean enabled = r.has("enabled") && r.get("enabled").isJsonPrimitive()
                && r.get("enabled").getAsBoolean();
        String uri = stringOr(r, "uri", "redis://127.0.0.1:6379/0");
        String passwordEnv = stringOr(r, "passwordEnv", "SOLIDUS_REDIS_PASSWORD");
        int ttl = intOr(r, "balanceTtlSeconds", 30);
        if (!enabled) {
            return RedisSettings.disabled();
        }
        if (uri == null || uri.isBlank()) {
            SolidusMod.LOGGER.error("storage.json: redis.enabled=true but no uri — Redis stays disabled.");
            return RedisSettings.disabled();
        }
        return new RedisSettings(true, uri, passwordEnv, Math.max(1, ttl));
    }

    /** Parses + validates the mysql block; null when a required field is missing. */
    private static MySqlSettings parseMysql(JsonObject m) {
        String host = stringOr(m, "host", "127.0.0.1");
        int port = intOr(m, "port", 3306);
        String database = stringOr(m, "database", "solidus");
        String user = stringOr(m, "user", "solidus");
        String password = stringOr(m, "password", "");
        int maxSize = intOr(m, "pool", "maxSize", 10);
        int connTimeout = intOr(m, "pool", "connectionTimeoutMs", 5000);
        // SECURITY (audit SOL-002, CWE-319): the SECURE choice is the silent
        // default. This is a multi-server feature first — a shared database
        // across a network carries every balance and ledger row, so the
        // missing-key default must be encryption ON. Localhost installs that
        // genuinely run without TLS set "useSsl": false explicitly (the
        // shipped template now does the opposite of before: enables it).
        boolean useSsl = boolOr(m, "useSsl", true);

        if (database == null || database.isBlank()) {
            return null;
        }
        // Env override wins over the file value (never logged either way).
        String envPassword = System.getenv(PASSWORD_ENV);
        if (envPassword != null && !envPassword.isBlank()) {
            password = envPassword;
        }
        return new MySqlSettings(host, port, database, user, password,
                Math.max(2, maxSize), Math.max(1000, connTimeout), useSsl);
    }

    private static String stringOr(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static int intOr(JsonObject o, String key, String nestedKey, int def) {
        if (o.has(key) && o.get(key).isJsonObject()) {
            return intOr(o.getAsJsonObject(key), nestedKey, def);
        }
        return def;
    }

    private static int intOr(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
    }

    private static boolean boolOr(JsonObject o, String key, boolean def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
    }
}
