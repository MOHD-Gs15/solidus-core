package com.solidus.economy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
 * CI-gated tests for the OPTIONAL Redis layer (2.2.1 — DB scaling plan §6):
 * L2 balance cache round-trip, cross-connection pub/sub invalidation, and
 * the player-events channel that carries instant network notifications.
 *
 * <p>Money correctness never depends on these paths — they are speed and
 * awareness features. The tests therefore assert exactly that surface.</p>
 *
 * <p><b>Activation</b> (self-skipping otherwise):
 * {@code SOLIDUS_TEST_REDIS_URI} (e.g. {@code redis://127.0.0.1:6379/0};
 * password via {@code SOLIDUS_TEST_REDIS_PASSWORD} if required).</p>
 */
@DisplayName("Redis layer: L2 cache + pub/sub (2.2.1)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RedisLayerTest {

    private RedisLayer layer;

    @BeforeAll
    void start() {
        String uri = System.getenv("SOLIDUS_TEST_REDIS_URI");
        Assumptions.assumeTrue(uri != null,
            "SOLIDUS_TEST_REDIS_URI not set — Redis layer tests skipped");
        layer = RedisLayer.start(new StorageConfig.RedisSettings(
            true, uri, "SOLIDUS_TEST_REDIS_PASSWORD", 30));
    }

    @AfterAll
    void stop() {
        if (layer != null) {
            layer.close();
        }
    }

    @Test
    @DisplayName("L2 balance cache: write-through read-back, miss before write")
    void balanceCacheRoundTrip() {
        Assumptions.assumeTrue(layer != null);
        UUID uuid = UUID.randomUUID();
        assertNull(layer.getCachedBalance(uuid), "fresh key must miss");
        layer.cacheBalance(uuid, 123.45);
        Double cached = layer.getCachedBalance(uuid);
        assertNotNull(cached);
        assertEquals(123.45, cached, 0.0001);
    }

    @Test
    @DisplayName("invalidation publish+subscribe: a listener on the same layer (and other clients) receives the uuids")
    void invalidationBusDelivers() throws Exception {
        Assumptions.assumeTrue(layer != null);
        List<List<UUID>> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        layer.onBalanceInvalidation(ids -> {
            received.add(ids);
            latch.countDown();
        });

        // NOTE: publishing on the same StatefulRedisConnection that subscribed
        // still delivers (Redis pub/sub is connection-mode based; the pubsub
        // connection is separate here), so no second client is needed.
        UUID uuid = UUID.randomUUID();
        layer.publishBalanceInvalidation(List.of(uuid));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "invalidation must reach the subscriber");
        assertEquals(1, received.size());
        assertEquals(List.of(uuid), received.get(0));
    }

    @Test
    @DisplayName("events channel: player uuid + message survive the round trip")
    void eventsBusDelivers() throws Exception {
        Assumptions.assumeTrue(layer != null);
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
        Assumptions.assumeTrue(layer != null);
        CountDownLatch gotValid = new CountDownLatch(1);
        layer.onPlayerEvent((uuid, message) -> gotValid.countDown());

        // Send garbage through a raw second client to simulate a broken publisher.
        try (var raw = io.lettuce.core.RedisClient.create(
                System.getenv("SOLIDUS_TEST_REDIS_URI")).connect()) {
            raw.sync().publish(RedisLayer.CHANNEL_EVENTS, "not-json");
            raw.sync().publish(RedisLayer.CHANNEL_EVENTS, "{\"uuid\":\"zzz\"}");
        }

        UUID player = UUID.randomUUID();
        layer.publishPlayerEvent(player, "still alive");
        assertTrue(gotValid.await(5, TimeUnit.SECONDS),
            "the subscriber must survive malformed messages");
    }
}
