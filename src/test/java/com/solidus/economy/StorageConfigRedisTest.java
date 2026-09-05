package com.solidus.economy;

import com.solidus.util.ConfigManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the OPTIONAL redis block of {@code storage.json}
 * (2.2.1, DB scaling plan §6). Defaults must be safe: missing/malformed
 * block = disabled, and disabled must be the outcome of ANY ambiguity.
 */
public class StorageConfigRedisTest {

    @TempDir
    Path tempDir;

    private void writeStorageJson(String content) throws Exception {
        Path dir = tempDir.resolve("config").resolve("solidus");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("storage.json"), content);
    }

    private StorageConfig load() {
        ConfigManager.initialize(tempDir);
        return StorageConfig.load();
    }

    @Test
    @DisplayName("missing redis block → disabled defaults")
    void missingBlockIsDisabled() throws Exception {
        writeStorageJson("{\"type\": \"mysql\", \"mysql\": {\"database\": \"solidus\"}}");
        StorageConfig.RedisSettings redis = load().redis();
        assertNotNull(redis);
        assertFalse(redis.enabled());
        assertEquals("redis://127.0.0.1:6379/0", redis.uri());
        assertEquals("SOLIDUS_REDIS_PASSWORD", redis.passwordEnv());
        assertEquals(30, redis.balanceTtlSeconds());
    }

    @Test
    @DisplayName("redis.enabled=true parses uri/ttl/passwordEnv")
    void parsesEnabledBlock() throws Exception {
        writeStorageJson("""
            {
              "type": "sqlite",
              "redis": {
                "enabled": true,
                "uri": "redis://cache.example.net:6380/2",
                "passwordEnv": "MY_REDIS_SECRET",
                "balanceTtlSeconds": 45
              }
            }
            """);
        StorageConfig.RedisSettings redis = load().redis();
        assertTrue(redis.enabled());
        assertEquals("redis://cache.example.net:6380/2", redis.uri());
        assertEquals("MY_REDIS_SECRET", redis.passwordEnv());
        assertEquals(45, redis.balanceTtlSeconds());
    }

    @Test
    @DisplayName("enabled=true with blank uri → disabled (never half-configured)")
    void blankUriDisables() throws Exception {
        writeStorageJson("""
            {
              "type": "sqlite",
              "redis": { "enabled": true, "uri": "   " }
            }
            """);
        assertFalse(load().redis().enabled());
    }

    @Test
    @DisplayName("redis.enabled=false stays disabled regardless of other fields")
    void explicitFalse() throws Exception {
        writeStorageJson("""
            {
              "type": "sqlite",
              "redis": { "enabled": false, "uri": "redis://host:6379/0" }
            }
            """);
        assertFalse(load().redis().enabled());
    }

    @Test
    @DisplayName("malformed redis block (non-object) → disabled, no throw")
    void malformedBlockIsDisabled() throws Exception {
        writeStorageJson("""
            {
              "type": "sqlite",
              "redis": "oops"
            }
            """);
        assertFalse(load().redis().enabled());
    }

    @Test
    @DisplayName("negative TTL is clamped to at least 1 second")
    void ttlClamped() throws Exception {
        writeStorageJson("""
            {
              "type": "sqlite",
              "redis": { "enabled": true, "balanceTtlSeconds": -5 }
            }
            """);
        assertEquals(1, load().redis().balanceTtlSeconds());
    }
}
