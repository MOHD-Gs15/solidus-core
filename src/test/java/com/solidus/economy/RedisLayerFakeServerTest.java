package com.solidus.economy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Infrastructure-free verification of the Redis layer (audit W-2, 2.3.1):
 * an in-JVM {@link FakeRedisServer} stands in for a real Redis so the full
 * Jedis-backed {@link RedisLayer} path — TCP connect, command encoding, L2
 * cache setex/get, pub/sub subscribe/publish push frames, malformed-message
 * survival — runs on EVERY plain {@code ./gradlew test}, with no containers
 * and no environment variables.
 *
 * <p>Real-Redis fidelity is covered in CI by {@code RedisLayerTest}
 * (live {@code redis:7} service container) and by
 * {@code RedisPackagingSmokeTest} (the BUILT jar in an isolated
 * classloader). This class adds the fail-closed startup checks the
 * live-Redis CI cannot easily do: unsupported scheme, malformed URI and
 * unreachable server must all throw with a REDACTED uri, never silently
 * degrade.</p>
 */
@DisplayName("Redis layer vs in-JVM fake RESP2 server (2.3.1, W-2)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisLayerFakeServerTest {

    private FakeRedisServer redis;
    private RedisLayer layer;

    @BeforeAll
    void start() throws Exception {
        redis = FakeRedisServer.start();
        layer = RedisLayer.start(new StorageConfig.RedisSettings(
            true, redis.uri(), "SOLIDUS_TEST_REDIS_PASSWORD", 30));
    }

    @AfterAll
    void stop() {
        if (layer != null) {
            layer.close();
        }
        if (redis != null) {
            redis.close();
        }
    }

    @Test
    @DisplayName("L2 balance cache: miss before write, then write-through read-back")
    void balanceCacheRoundTrip() {
        UUID uuid = UUID.randomUUID();
        assertNull(layer.getCachedBalance(uuid), "fresh key must miss");
        layer.cacheBalance(uuid, 123.45);
        Double cached = layer.getCachedBalance(uuid);
        assertNotNull(cached);
        assertEquals(123.45, cached, 0.0001);
    }

    @Test
    @DisplayName("invalidation publish+subscribe: the uuid list round-trips through pub/sub")
    void invalidationBusDelivers() throws Exception {
        List<List<UUID>> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        layer.onBalanceInvalidation(ids -> {
            received.add(ids);
            latch.countDown();
        });

        UUID uuid = UUID.randomUUID();
        layer.publishBalanceInvalidation(List.of(uuid));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "invalidation must reach the subscriber");
        assertEquals(1, received.size());
        assertEquals(List.of(uuid), received.get(0));
    }

    @Test
    @DisplayName("events channel: player uuid + message survive the round trip")
    void eventsBusDelivers() throws Exception {
        List<UUID> players = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        layer.onPlayerEvent((uuid, message) -> {
            players.add(uuid);
            messages.add(message);
            latch.countDown();
        });

        UUID player = UUID.randomUUID();
        layer.publishPlayerEvent(player, "Your auction was WON by Test!");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "event must reach the subscriber");
        assertEquals(player, players.get(0));
        assertEquals("Your auction was WON by Test!", messages.get(0));
    }

    @Test
    @DisplayName("malformed payloads are dropped without killing the subscriber")
    void malformedMessagesIgnored() throws Exception {
        CountDownLatch gotValid = new CountDownLatch(1);
        layer.onPlayerEvent((uuid, message) -> gotValid.countDown());

        // Send garbage through a raw second client to simulate a broken publisher.
        try (var raw = new redis.clients.jedis.Jedis(java.net.URI.create(redis.uri()))) {
            raw.publish(RedisLayer.CHANNEL_EVENTS, "not-json");
            raw.publish(RedisLayer.CHANNEL_EVENTS, "{\"uuid\":\"zzz\"}");
        }

        UUID player = UUID.randomUUID();
        layer.publishPlayerEvent(player, "still alive");
        assertTrue(gotValid.await(5, TimeUnit.SECONDS),
            "the subscriber must survive malformed messages");
    }

    // -- fail-closed startup (the contract SolidusMod relies on) --------------

    @Test
    @DisplayName("disabled layer returns null (both null settings and enabled=false)")
    void disabledReturnsNull() {
        assertNull(RedisLayer.start(null));
        assertNull(RedisLayer.start(StorageConfig.RedisSettings.disabled()));
    }

    @Test
    @DisplayName("fail-closed: unsupported scheme rejected with a REDACTED uri")
    void unsupportedSchemeRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            RedisLayer.start(new StorageConfig.RedisSettings(true, "ftp://secret@evil:9000/0", null, 30)));
        assertFalse(ex.getMessage().contains("secret@evil"), "uri must be redacted, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("***@"));
        assertTrue(ex.getMessage().contains("economy NOT degraded"));
    }

    @Test
    @DisplayName("fail-closed: malformed uri rejected with a REDACTED uri")
    void malformedUriRejected() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            RedisLayer.start(new StorageConfig.RedisSettings(true, "redis://:pass@host/notadb", null, 30)));
        assertFalse(ex.getMessage().contains(":pass@"));
        assertTrue(ex.getMessage().contains("***@"));
    }

    @Test
    @DisplayName("fail-closed: unreachable server throws (redacted) instead of half-wiring")
    void unreachableThrows() throws Exception {
        // Reserve a port and release it so nothing is listening there.
        int deadPort;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            deadPort = probe.getLocalPort();
        }
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            RedisLayer.start(new StorageConfig.RedisSettings(true,
                "redis://:supersecret@127.0.0.1:" + deadPort + "/0", null, 30)));
        assertFalse(ex.getMessage().contains("supersecret"), "password must never reach the message");
        assertTrue(ex.getMessage().contains("***@"));
        assertTrue(ex.getMessage().contains("continuing WITHOUT Redis"));
    }

    @Test
    @DisplayName("URI parser: percent-decoding, TLS scheme, defaults")
    void uriParsing() {
        var ep = RedisLayer.parseUri("redis://user:p%40ss@10.0.0.5:6400/2");
        assertEquals("10.0.0.5", ep.host());
        assertEquals(6400, ep.port());
        assertEquals(2, ep.db());
        assertEquals("user", ep.user());
        assertEquals("p@ss", ep.password());
        assertFalse(ep.ssl());
        assertTrue(RedisLayer.parseUri("rediss://h").ssl());
        assertEquals(6379, RedisLayer.parseUri("redis://h").port());
    }
}
