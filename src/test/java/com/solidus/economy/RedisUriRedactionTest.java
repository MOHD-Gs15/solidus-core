package com.solidus.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for the Redis connection string hardening (audit round 2.2.5):
 *
 * <ul>
 *   <li>SOL-001 (CWE-532): {@link RedisLayer#redactUri(String)} must strip any
 *       embedded credentials before the URI can reach a log file.</li>
 *   <li>SOL-003: {@link RedisLayer#start} must reject non-redis schemes
 *       up front, and its error message must itself be credential-free.</li>
 * </ul>
 *
 * Pure unit tests — no Redis server, no Lettuce connection is ever opened
 * (the scheme check fires before any network I/O).
 */
@DisplayName("Redis URI security (SOL-001 redaction / SOL-003 scheme allowlist)")
class RedisUriRedactionTest {

    // -- redactUri() ----------------------------------------

    @Test
    @DisplayName("password-only userinfo is redacted")
    void passwordOnlyRedacted() {
        assertEquals("redis://***@10.0.0.5:6379/0",
            RedisLayer.redactUri("redis://:MyStrongPass@10.0.0.5:6379/0"));
    }

    @Test
    @DisplayName("user:password userinfo is redacted")
    void userAndPasswordRedacted() {
        assertEquals("redis://***@cache.internal:6380/2",
            RedisLayer.redactUri("redis://solidus:s3cret@cache.internal:6380/2"));
    }

    @Test
    @DisplayName("rediss:// (TLS) URIs are redacted too")
    void tlsSchemeRedacted() {
        assertEquals("rediss://***@cache.example.net:6379/0",
            RedisLayer.redactUri("rediss://u:p@cache.example.net:6379/0"));
    }

    @Test
    @DisplayName("a URI without credentials is returned unchanged")
    void withoutCredentialsUnchanged() {
        assertEquals("redis://127.0.0.1:6379/0",
            RedisLayer.redactUri("redis://127.0.0.1:6379/0"));
    }

    @Test
    @DisplayName("a raw '/' inside the password still redacts (invalid-but-possible)")
    void slashInPasswordStillRedacted() {
        // RFC 3986 forbids '/' in userinfo, but an operator who does it anyway
        // must not leak the secret into the logs — that is the whole point of
        // the safety net.
        assertEquals("redis://***@10.0.0.5:6379/0",
            RedisLayer.redactUri("redis://u:p/ssh@10.0.0.5:6379/0"));
    }

    @Test
    @DisplayName("null and empty inputs are passed through as their string form")
    void nullAndEmpty() {
        assertEquals("null", RedisLayer.redactUri(null));
        assertEquals("", RedisLayer.redactUri(""));
    }

    // -- isSupportedScheme() --------------------------------

    @Test
    @DisplayName("redis:// and rediss:// are accepted (case-insensitive)")
    void supportedSchemes() {
        assertTrue(RedisLayer.isSupportedScheme("redis://127.0.0.1:6379/0"));
        assertTrue(RedisLayer.isSupportedScheme("rediss://cache.example.net:6379/0"));
        assertTrue(RedisLayer.isSupportedScheme("REDIS://127.0.0.1:6379/0"));
        assertTrue(RedisLayer.isSupportedScheme("Rediss://127.0.0.1:6379/0"));
    }

    @Test
    @DisplayName("every non-redis scheme is rejected")
    void unsupportedSchemes() {
        assertFalse(RedisLayer.isSupportedScheme("http://127.0.0.1:6379/0"));
        assertFalse(RedisLayer.isSupportedScheme("https://127.0.0.1:6379/0"));
        assertFalse(RedisLayer.isSupportedScheme("file:///etc/passwd"));
        assertFalse(RedisLayer.isSupportedScheme("unix:///tmp/redis.sock"));
        assertFalse(RedisLayer.isSupportedScheme(""));
    }

    // -- start() fail-closed on bad scheme -------------------

    @Test
    @DisplayName("start() throws on a non-redis scheme - and the message carries NO credential")
    void startRejectsNonRedisSchemeWithoutLeaking() {
        StorageConfig.RedisSettings settings = new StorageConfig.RedisSettings(
            true, "http://hunter2@evil.example.net:6379/0", "SOLIDUS_REDIS_PASSWORD", 30);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> RedisLayer.start(settings));
        assertTrue(ex.getMessage().contains("unsupported uri scheme"),
            "unexpected message: " + ex.getMessage());
        // The redacted host may appear; the credential must not.
        assertFalse(ex.getMessage().contains("hunter2"),
            "credential leaked in error message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("***@"),
            "expected the redacted form in the message: " + ex.getMessage());
    }

    @Test
    @DisplayName("start() is a no-op when redis.enabled=false")
    void startSkipsWhenDisabled() {
        assertNull(RedisLayer.start(StorageConfig.RedisSettings.disabled()));
    }
}
