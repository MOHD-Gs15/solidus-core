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
 *     "useSsl": false
 *   }
 * }
 * }</pre>
 *
 * <p>Password handling: {@code mysql.password} supports the env override
 * {@code SOLIDUS_DB_PASSWORD} (wins when set) — the connection string is
 * never logged.</p>
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

    private static final String FILE_NAME = "storage.json";
    private static final String PASSWORD_ENV = "SOLIDUS_DB_PASSWORD";

    private final Type type;
    private final MySqlSettings mysql;

    private StorageConfig(Type type, MySqlSettings mysql) {
        this.type = type;
        this.mysql = mysql;
    }

    public Type type() {
        return type;
    }

    public MySqlSettings mysql() {
        return mysql;
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
            return new StorageConfig(Type.SQLITE, null);
        }

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
                return new StorageConfig(Type.SQLITE, null);
            }
            return new StorageConfig(Type.MYSQL, mysql);
        }
        if (!"sqlite".equals(rawType)) {
            SolidusMod.LOGGER.warn(
                "storage.json: unknown type '{}' — falling back to SQLite.", rawType);
        }
        return new StorageConfig(Type.SQLITE, null);
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
        boolean useSsl = boolOr(m, "useSsl", false);

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
