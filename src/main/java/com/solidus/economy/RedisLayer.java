package com.solidus.economy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * OPTIONAL Redis coordination layer (DB scaling plan §6 — shipped 2.2.1).
 *
 * <p><b>Role boundaries (the design rule that keeps this safe):</b> the shared
 * MySQL/MariaDB database stays the ONLY source of truth for money. Redis
 * adds two speed/awareness services and nothing else:</p>
 *
 * <ol>
 *   <li><b>L2 balance cache</b> (read path): keys {@code solidus:bal:<uuid>}
 *       hold the last-known balance with a short TTL. Reads served from Redis
 *       skip a database round trip; misses fall through to MySQL and populate.
 *       Writers never publish balances as truth — they DELETE the key and
 *       broadcast an invalidation, so every reader converges back to the
 *       database. The TTL bounds staleness even when a pub/sub message is
 *       lost (Redis pub/sub is fire-and-forget).</li>
 *   <li><b>Event bus</b> (pub/sub): channel {@code solidus:bal:inv} carries
 *       balance invalidations; {@code solidus:events} carries offline-tolerant
 *       player notifications so the server that actually hosts the player can
 *       deliver instantly — the old "queue and hope they join here" limitation
 *       disappears on a network. The database row remains the durable copy;
 *       instant delivery only deletes that row when it really was delivered.</li>
 * </ol>
 *
 * <p><b>Failure model:</b> every Redis call is guarded (short timeout +
 * circuit breaker). An outage degrades the server to plain MySQL reads
 * (slower, correct); nothing money-related ever fails because of Redis.
 * The breaker re-probes after a cooldown so a recovered Redis is adopted
 * automatically.</p>
 *
 * <p><b>Lifecycle:</b> started by SolidusMod when {@code storage.json} has
 * {@code "redis": { "enabled": true }}; closed on SERVER_STOPPING before the
 * storage backends shut down.</p>
 */
public final class RedisLayer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisLayer.class);
    private static final Gson GSON = new Gson();

    public static final String CHANNEL_BALANCE_INVALIDATION = "solidus:bal:inv";
    public static final String CHANNEL_EVENTS = "solidus:events";
    private static final String BALANCE_KEY_PREFIX = "solidus:bal:";

    /** Consecutive failures before the breaker opens (degrade to DB reads). */
    private static final int BREAKER_THRESHOLD = 3;
    /** How long the breaker stays open before re-probing. */
    private static final long BREAKER_COOLDOWN_MS = 30_000;
    /** Hard ceiling for one synchronous Redis call (server thread safety). */
    private static final long CALL_TIMEOUT_MS = 250;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final StatefulRedisPubSubConnection<String, String> pubsub;
    private final int balanceTtlSeconds;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long breakerOpenedAt = 0;

    /** Registered by SolidusMod: fires when OTHER servers invalidate balances. */
    private volatile Consumer<List<UUID>> balanceInvalidationListener = ids -> {};
    /** Registered by SolidusMod: fires for network notification events (player, message). */
    private volatile BiConsumer<UUID, String> eventListener = (uuid, msg) -> {};

    private RedisLayer(RedisClient client,
                       StatefulRedisConnection<String, String> commands,
                       StatefulRedisPubSubConnection<String, String> pubsub,
                       int balanceTtlSeconds) {
        this.client = client;
        this.commands = commands;
        this.pubsub = pubsub;
        this.balanceTtlSeconds = balanceTtlSeconds;
    }

    /**
     * Starts the layer. Throws (fail-closed, like MySqlStorage) when the URI
     * is malformed or the server is unreachable — the caller then continues
     * without Redis instead of running half-wired.
     *
     * @return a connected layer, or null when {@code settings.enabled} is false
     */
    public static RedisLayer start(StorageConfig.RedisSettings settings) {
        if (settings == null || !settings.enabled()) {
            return null;
        }
        RedisURI uri;
        try {
            uri = RedisURI.create(settings.uri());
            uri.setTimeout(Duration.ofSeconds(3));
            String envPassword = System.getenv(settings.passwordEnv());
            if (envPassword != null && !envPassword.isBlank()) {
                uri.setPassword(envPassword.toCharArray());
            }
        } catch (RuntimeException e) {
            throw new RuntimeException(
                "Solidus Redis layer: invalid uri '" + settings.uri() + "' — economy NOT degraded, "
                    + "fix storage.json or set redis.enabled=false", e);
        }

        RedisClient client = RedisClient.create(uri);
        StatefulRedisConnection<String, String> commands;
        StatefulRedisPubSubConnection<String, String> pubsub;
        try {
            commands = client.connect();
            pubsub = client.connectPubSub();
        } catch (RuntimeException e) {
            client.shutdown();
            throw new RuntimeException(
                "Solidus Redis layer: cannot reach " + settings.uri()
                    + " — continuing WITHOUT Redis (MySQL-only mode). Fix storage.json if Redis was intended.",
                e);
        }

        RedisLayer layer = new RedisLayer(client, commands, pubsub, settings.balanceTtlSeconds());
        pubsub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                layer.handleMessage(channel, message);
            }
        });
        pubsub.sync().subscribe(CHANNEL_BALANCE_INVALIDATION, CHANNEL_EVENTS);
        LOGGER.info("Solidus Redis layer connected to {} (L2 balance cache TTL {}s, pub/sub on {} + {})",
            settings.uri(), settings.balanceTtlSeconds(), CHANNEL_BALANCE_INVALIDATION, CHANNEL_EVENTS);
        return layer;
    }

    // -- L2 balance cache (read path) --------------------------------------

    /**
     * Reads the cached balance for {@code uuid}, or {@code null} on miss,
     * disabled breaker or any failure (failures are NOT errors here — the
     * caller falls through to the database).
     */
    public Double getCachedBalance(UUID uuid) {
        if (!isAvailable()) return null;
        try {
            String json = guarded(() -> commands.sync().get(BALANCE_KEY_PREFIX + uuid));
            if (json == null || json.isBlank()) {
                noteSuccess();
                return null;
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            noteSuccess();
            return obj.get("amount").getAsDouble();
        } catch (Exception e) {
            noteFailure("balance read", e);
            return null;
        }
    }

    /**
     * Populates the L2 cache after a database read. Best-effort: a Redis
     * outage here simply leaves the next read to hit the database again.
     */
    public void cacheBalance(UUID uuid, double amount) {
        if (!isAvailable()) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("amount", amount);
            obj.addProperty("ts", System.currentTimeMillis());
            guarded(() -> commands.sync().setex(BALANCE_KEY_PREFIX + uuid,
                balanceTtlSeconds, GSON.toJson(obj)));
            noteSuccess();
        } catch (Exception e) {
            noteFailure("balance write-through", e);
        }
    }

    // -- Invalidation bus (write path) --------------------------------------

    /**
     * After a committed mutation: drops the local L2 keys and broadcasts the
     * invalidation so every other server drops its cache too. Callers already
     * updated their in-memory L1 via the storage backend.
     */
    public void publishBalanceInvalidation(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) return;
        if (!isAvailable()) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("ts", System.currentTimeMillis());
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (UUID uuid : uuids) {
                arr.add(uuid.toString());
            }
            obj.add("uuids", arr);
            guarded(() -> commands.sync().del(uuids.stream().map(u -> BALANCE_KEY_PREFIX + u).toArray(String[]::new)));
            guarded(() -> commands.sync().publish(CHANNEL_BALANCE_INVALIDATION, GSON.toJson(obj)));
            noteSuccess();
        } catch (Exception e) {
            noteFailure("balance invalidation publish", e);
        }
    }

    /**
     * Publishes an offline-tolerant notification to the whole network. The
     * caller has ALREADY stored the durable row in pending_notifications —
     * subscribers deliver only when they host the player and delete the row
     * on real delivery.
     */
    public void publishPlayerEvent(UUID playerUuid, String message) {
        if (playerUuid == null || message == null) return;
        if (!isAvailable()) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", playerUuid.toString());
            obj.addProperty("message", message);
            obj.addProperty("ts", System.currentTimeMillis());
            guarded(() -> commands.sync().publish(CHANNEL_EVENTS, GSON.toJson(obj)));
            noteSuccess();
        } catch (Exception e) {
            noteFailure("event publish", e);
        }
    }

    // -- Subscriptions -------------------------------------------------------

    /** Registers the callback fired when a balance invalidation arrives. */
    public void onBalanceInvalidation(Consumer<List<UUID>> listener) {
        this.balanceInvalidationListener = listener != null ? listener : ids -> {};
    }

    /** Registers the callback fired for network player events. */
    public void onPlayerEvent(BiConsumer<UUID, String> listener) {
        this.eventListener = listener != null ? listener : (uuid, msg) -> {};
    }

    private void handleMessage(String channel, String message) {
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            if (CHANNEL_BALANCE_INVALIDATION.equals(channel) && obj.has("uuids")) {
                List<UUID> ids = new ArrayList<>();
                for (var el : obj.getAsJsonArray("uuids")) {
                    try {
                        ids.add(UUID.fromString(el.getAsString()));
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed id — never let one bad row kill the batch
                    }
                }
                balanceInvalidationListener.accept(ids);
            } else if (CHANNEL_EVENTS.equals(channel) && obj.has("uuid") && obj.has("message")) {
                eventListener.accept(UUID.fromString(obj.get("uuid").getAsString()),
                    obj.get("message").getAsString());
            }
        } catch (Exception e) {
            LOGGER.warn("Dropping malformed Redis message on {}: {}", channel, e.getMessage());
        }
    }

    // -- Circuit breaker -----------------------------------------------------

    /** True when the breaker is closed (or its cooldown elapsed for a re-probe). */
    private boolean isAvailable() {
        long openedAt = breakerOpenedAt;
        if (openedAt == 0) return true;
        if (System.currentTimeMillis() - openedAt >= BREAKER_COOLDOWN_MS) {
            // Allow a single re-probe: reset counters; a new failure re-opens.
            consecutiveFailures.set(BREAKER_THRESHOLD - 1);
            breakerOpenedAt = 0;
            LOGGER.info("Solidus Redis layer: breaker cooldown elapsed — re-probing Redis.");
            return true;
        }
        return false;
    }

    private void noteSuccess() {
        consecutiveFailures.set(0);
        breakerOpenedAt = 0;
    }

    private void noteFailure(String what, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= BREAKER_THRESHOLD && breakerOpenedAt == 0) {
            breakerOpenedAt = System.currentTimeMillis();
            LOGGER.warn("Solidus Redis layer: {} failures in a row ({}: {}) — degrading to database-only reads "
                + "for {}s. Money correctness is unaffected.",
                failures, what, e.getMessage(), BREAKER_COOLDOWN_MS / 1000);
        } else if (failures < BREAKER_THRESHOLD) {
            LOGGER.debug("Solidus Redis layer: {} failed: {}", what, e.getMessage());
        }
    }

    /** Runs one Redis call with a hard timeout so a stalled server cannot hang a caller. */
    private <T> T guarded(java.util.function.Supplier<T> call) throws Exception {
        var future = java.util.concurrent.CompletableFuture.supplyAsync(call);
        return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            pubsub.close();
        } catch (Exception ignored) {
        }
        try {
            commands.close();
        } catch (Exception ignored) {
        }
        client.shutdown();
        LOGGER.info("Solidus Redis layer closed.");
    }
}
