package com.solidus.economy;


import com.solidus.util.ConfigManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StorageConfig} (DB scaling plan Phase 1):
 * default creation, type parsing, and safe fallbacks. The economy must
 * always come up - a broken config can never take the server down.
 */
public class StorageConfigTest {

    @TempDir
    Path tempDir;

    private void initConfigDir() {
        ConfigManager.initialize(tempDir);
    }

    private void writeStorageJson(String content) throws Exception {
        Path dir = tempDir.resolve("config").resolve("solidus");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("storage.json"), content);
    }

    @Test
    @DisplayName("missing storage.json defaults to SQLITE and creates the default file")
    void defaultsToSqlite() {
        initConfigDir();
        StorageConfig config = StorageConfig.load();
        assertEquals(StorageConfig.Type.SQLITE, config.type());
        assertNull(config.mysql());
        // Default file was created for the operator to edit later
        Path file = tempDir.resolve("config").resolve("solidus").resolve("storage.json");
        assertTrue(Files.exists(file));
    }

    @Test
    @DisplayName("type=mysql parses the mysql block")
    void parsesMysql() throws Exception {
        writeStorageJson("""
            {
              "type": "mysql",
              "mysql": {
                "host": "db.example.net",
                "port": 3307,
                "database": "solidus_prod",
                "user": "solidus",
                "password": "secret",
                "pool": { "maxSize": 16, "connectionTimeoutMs": 4000 },
                "useSsl": true
              }
            }
            """);
        initConfigDir();
        StorageConfig config = StorageConfig.load();
        assertEquals(StorageConfig.Type.MYSQL, config.type());
        assertNotNull(config.mysql());
        assertEquals("db.example.net", config.mysql().host());
        assertEquals(3307, config.mysql().port());
        assertEquals("solidus_prod", config.mysql().database());
        assertEquals(16, config.mysql().maxPoolSize());
        assertEquals(4000, config.mysql().connectionTimeoutMs());
        assertTrue(config.mysql().useSsl());
    }

    @Test
    @DisplayName("mariadb is accepted as an alias of the mysql branch")
    void mariadbAlias() throws Exception {
        writeStorageJson("""
            { "type": "mariadb",
              "mysql": { "host": "h", "database": "d", "user": "u", "password": "p" } }
            """);
        initConfigDir();
        assertEquals(StorageConfig.Type.MYSQL, StorageConfig.load().type());
    }

    @Test
    @DisplayName("unknown type falls back; blank database name falls back")
    void safeFallbacks() throws Exception {
        writeStorageJson("{ \"type\": \"oracle\" }");
        initConfigDir();
        assertEquals(StorageConfig.Type.SQLITE, StorageConfig.load().type());

        // A blank database name cannot be defaulted - explicit fallback
        writeStorageJson("{ \"type\": \"mysql\", \"mysql\": { \"database\": \"\" } }");
        assertEquals(StorageConfig.Type.SQLITE, StorageConfig.load().type());
    }

    @Test
    @DisplayName("pool defaults are sane when the block is omitted")
    void poolDefaults() throws Exception {
        writeStorageJson("""
            { "type": "mysql",
              "mysql": { "host": "h", "database": "d", "user": "u", "password": "p" } }
            """);
        initConfigDir();
        StorageConfig config = StorageConfig.load();
        assertEquals(10, config.mysql().maxPoolSize());
        assertEquals(5000, config.mysql().connectionTimeoutMs());
    }
}
