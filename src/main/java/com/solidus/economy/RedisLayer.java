package com.solidus.economy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.exceptions.JedisConnectionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * <p><b>Wire compatibility (audit W-2, 2.3.1):</b> the client library is
 * <b>Jedis 5.2.0</b> — plain sockets, no Netty, no Project Reactor, no JNI
 * natives. The previous Lettuce 6.5.5 build compiled and its CI passed, but
 * the SHIPPED jar nested only {@code lettuce-core} while Lettuce hard-requires
 * Netty and Reactor at runtime: {@code redis.enabled=true} in production meant
 * {@link NoClassDefFoundError} on first use (the Gradle <i>test</i> classpath
 * silently supplied both transitives, which is exactly why CI never saw it).
 * RedisLayer never used Lettuce's async/reactive machinery anyway — every
 * call already funnels through a single guard executor with a hard timeout —
 * so the sync-only, zero-transitive Jedis client is the exact right shape.
 * The JSON payload shapes on both channels are unchanged, so 2.3.x servers
 * on the same Redis interoperate regardless of which client they embed.</p>
 *
 * <p><b>Failure model:</b> every Redis call is guarded (short timeout +
 * circuit breaker). An outage degrades the server to plain MySQL reads
 * (slower, correct); nothing money-related ever fails because of Redis.
 * The breaker re-probes after a cooldown so a recovered Redis is adopted
 * automatically. A dropped command connection is re-established lazily on
 * the next guarded call; a dropped pub/sub connection is retried on a
 * backoff loop by its dedicated subscriber thread.</p>
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
    /** TCP connect timeout — mirrors the previous Lettuce RedisURI timeout. */
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    /** Socket read timeout for command calls (not the pub/sub connection). */
    private static final int SOCKET_TIMEOUT_MS = 2_000;
    /** How long {@link #start} waits for the pub/sub subscription to confirm. */
    private static final long SUBSCRIBE_CONFIRM_TIMEOUT_MS = 3_000;
    /** Backoff between pub/sub reconnect attempts (dedicated daemon thread). */
    private static final long PUBSUB_RETRY_MS = 5_000;

    /** Parsed endpoint — immutable; the env password override replaces it. */
    private final Endpoint endpoint;
    private final int balanceTtlSeconds;

    /**
     * Command connection, confined to {@link #GUARD_EXECUTOR} (written only
     * from {@link #cmd()}); volatile so {@link #close()} can see it.
     */
    private volatile Jedis commands;
    /** Set by the guard path on connection loss; the next call reconnects. */
    private volatile boolean reconnectNeeded;
    /** The pub/sub connection owned by the subscriber thread (volatile for close). */
    private volatile Jedis pubsubJedis;
    private volatile boolean closed;
    private Thread subscriber;
    /** Counted down once the pub/sub subscription is confirmed. */
    private final CountDownLatch subscribedOnce = new CountDownLatch(1);
    private final Listener listener = new Listener();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long breakerOpenedAt = 0;

    /** Registered by SolidusMod: fires when OTHER servers invalidate balances. */
    private volatile Consumer<List<UUID>> balanceInvalidationListener = ids -> {};
    /** Registered by SolidusMod: fires for network notification events (player, message). */
    private volatile BiConsumer<UUID, String> eventListener = (uuid, msg) -> {};

    private RedisLayer(Endpoint endpoint, int balanceTtlSeconds) {
        this.endpoint = endpoint;
        this.balanceTtlSeconds = balanceTtlSeconds;
    }

    /**
     * SECURITY (audit SOL-001, CWE-532): masks any credentials embedded in a
     * Redis URI before it can reach a log file or crash report. A URI like
     * {@code redis://:MyStrongPass@10.0.0.5:6379/0} becomes
     * {@code redis://***@10.0.0.5:6379/0}. The recommended path stays the
     * {@code passwordEnv} variable (never part of the URI at all); this
     * redaction is the safety net for operators who follow the common Redis
     * convention of inlining credentials in the URI.
     *
     * <p><b>redactUri is a redaction tool, not a URI parser</b> — one
     * deliberate regex, no decode, no re-encode (rebuilding a URI after
     * parsing it re-introduces the very class of bugs it guards against).
     * Package-private static so tests can verify the redaction directly.</p>
     */
    static String redactUri(String uri) {
        if (uri == null || uri.isEmpty()) return String.valueOf(uri);
        // Everything between "//" and the FIRST "@" is userinfo — user,
        // password, or both. Replace the whole block. RFC 3986 forbids a raw
        // '@' inside userinfo (it must be percent-encoded as %40), so the
        // first '@' after "//" is always the userinfo/host delimiter — and
        // unlike a [^@/]+ class, this still redacts operators who embed a raw
        // '/' in their password (invalid syntax, but exactly the mistake this
        // safety net exists for).
        return uri.replaceAll("(?<=//)[^@]+@", "***@");
    }

    /**
     * SECURITY (audit SOL-003): only the clear-text {@code redis://} and the
     * TLS-encrypted {@code rediss://} schemes are accepted. Jedis enables
     * TLS with certificate verification against the JVM trust store for any
     * {@code rediss://} endpoint (HostnameVerifier default), so cross-network
     * deployments have a supported, verified encryption path — documented in
     * the storage.json template and docs/DB_SCALING_PLAN.md.
     *
     * <p>Package-private static so tests can verify the allowlist directly.</p>
     */
    static boolean isSupportedScheme(String uri) {
        String lower = uri.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("redis://") || lower.startsWith("rediss://");
    }

    /** Parsed {@code redis://[user[:password]@]host[:port][/db]} endpoint. */
    record Endpoint(String host, int port, int db, String user, String password, boolean ssl) { }

    /**
     * Minimal strict parser for the two supported schemes. Percent-decodes
     * userinfo per RFC 3986 ({@code %XX} only — a literal {@code +} stays a
     * {@code +}, unlike form decoding, which would silently corrupt Redis
     * passwords containing it). Package-private static so tests verify the
     * parse table directly.
     */
    static Endpoint parseUri(String uriStr) {
        URI uri;
        try {
            uri = new URI(uriStr.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed uri", e);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("no host");
        }
        int port = uri.getPort() <= 0 ? 6379 : uri.getPort();
        int db = 0;
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            String dbStr = path.substring(1);
            try {
                db = Integer.parseInt(dbStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid database index '" + dbStr + "'");
            }
            if (db < 0) {
                throw new IllegalArgumentException("negative database index '" + dbStr + "'");
            }
        }
        String user = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                user = emptyToNull(decode(userInfo.substring(0, colon)));
                password = emptyToNull(decode(userInfo.substring(colon + 1)));
            } else {
                // Bare userinfo = password (Lettuce-compatible leniency for
                // redis://secret@host — no ACL username in the URI).
                password = emptyToNull(decode(userInfo));
            }
        }
        boolean ssl = "rediss".equalsIgnoreCase(uri.getScheme());
        return new Endpoint(uri.getHost(), port, db, user, password, ssl);
    }

    /** RFC 3986 {@code %XX} decoding — never touches '+' or other literals. */
    private static String decode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        byte[] out = new byte[s.length()];
        int o = 0;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out[o++] = (byte) ((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            out[o++] = (byte) c;
            i++;
        }
        return new String(out, 0, o, StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Opens one plain-socket connection. RESP2 is pinned deliberately: every
     * Redis server since 2.x speaks it and the reply-type drift of RESP3 buys
     * this layer nothing. {@code CLIENT SETINFO} is disabled so pre-7.2
     * servers (and restricted proxies like some managed Redis offerings that
     * reject unknown commands) never see connect-time chatter they might
     * refuse.
     */
    private Jedis openJedis(boolean forPubSub) {
        DefaultJedisClientConfig.Builder b = DefaultJedisClientConfig.builder()
            .protocol(RedisProtocol.RESP2)
            .clientSetInfoConfig(ClientSetInfoConfig.DISABLED)
            .connectionTimeoutMillis(CONNECT_TIMEOUT_MS)
            // The pub/sub connection BLOCKS waiting for messages: infinite
            // read timeout. Command calls keep a bounded one so a stalled
            // server cannot pin the guard executor thread past its socket.
            .socketTimeoutMillis(forPubSub ? 0 : SOCKET_TIMEOUT_MS)
            .ssl(endpoint.ssl());
        if (endpoint.password() != null) {
            b.password(endpoint.password());
            if (endpoint.user() != null) {
                b.user(endpoint.user());
            }
        }
        if (endpoint.db() != 0) {
            b.database(endpoint.db());
        }
        return new Jedis(new HostAndPort(endpoint.host(), endpoint.port()), b.build());
    }

    /**
     * Starts the layer. Throws (fail-closed, like MySqlStorage) when the URI
     * is malformed or the server is unreachable — the caller then continues
     * without Redis instead of running half-wired.
     *
     * <p>SECURITY (audit SOL-001): every message that embeds the configured
     * URI is redacted through {@link #redactUri(String)} — no credential ever
     * reaches the logs. SECURITY (audit SOL-003): non-redis schemes are
     * rejected up front instead of being handed to the client unverified.</p>
     *
     * @return a connected layer, or null when {@code settings.enabled} is false
     */
    public static RedisLayer start(StorageConfig.RedisSettings settings) {
        if (settings == null || !settings.enabled()) {
            return null;
        }
        if (!isSupportedScheme(settings.uri())) {
            throw new RuntimeException(
                "Solidus Redis layer: unsupported uri scheme ('"
                    + redactUri(settings.uri()) + "') — only redis:// and rediss:// are supported. "
                    + "Use rediss:// for TLS-encrypted connections. economy NOT degraded; "
                    + "fix storage.json or set redis.enabled=false");
        }
        Endpoint endpoint;
        try {
            endpoint = parseUri(settings.uri());
        } catch (RuntimeException e) {
            throw new RuntimeException(
                "Solidus Redis layer: invalid uri '" + redactUri(settings.uri()) + "' — economy NOT degraded, "
                    + "fix storage.json or set redis.enabled=false", e);
        }
        if (settings.passwordEnv() != null) {
            String envPassword = System.getenv(settings.passwordEnv());
            if (envPassword != null && !envPassword.isBlank()) {
                // The env variable overrides any password embedded in the URI
                // (same precedence as the previous Lettuce build: the URI is
                // parsed first, then env wins).
                endpoint = new Endpoint(endpoint.host(), endpoint.port(), endpoint.db(),
                    endpoint.user(), envPassword, endpoint.ssl());
            }
        }

        RedisLayer layer = new RedisLayer(endpoint, settings.balanceTtlSeconds());
        try {
            // Jedis connects lazily on first command: PING forces the TCP +
            // AUTH handshake now so an unreachable server fails STARTUP
            // (fail-closed), exactly like the old client.connect() did.
            layer.commands = layer.openJedis(false);
            layer.commands.ping();
            layer.subscriber = new Thread(layer::runSubscriber, "Solidus-Redis-PubSub");
            layer.subscriber.setDaemon(true);
            layer.subscriber.start();
            if (!layer.subscribedOnce.await(SUBSCRIBE_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("pub/sub subscription not confirmed within "
                    + SUBSCRIBE_CONFIRM_TIMEOUT_MS + "ms");
            }
        } catch (RuntimeException e) {
            layer.shutdownInternals();
            throw new RuntimeException(
                "Solidus Redis layer: cannot reach " + redactUri(settings.uri())
                    + " — continuing WITHOUT Redis (MySQL-only mode). Fix storage.json if Redis was intended.",
                e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            layer.shutdownInternals();
            throw new RuntimeException(
                "Solidus Redis layer: interrupted while connecting to " + redactUri(settings.uri())
                    + " — continuing WITHOUT Redis (MySQL-only mode).", e);
        }
        LOGGER.info("Solidus Redis layer connected to {} (tls={}, L2 balance cache TTL {}s, pub/sub on {} + {})",
            redactUri(settings.uri()), endpoint.ssl(), settings.balanceTtlSeconds(),
            CHANNEL_BALANCE_INVALIDATION, CHANNEL_EVENTS);
        return layer;
    }

    /**
     * The pub/sub loop: subscribes both channels on a dedicated connection,
     * and whenever that connection drops (restart, failover, timeout) retries
     * after a backoff. Mirrors the previous client's silent auto-reconnect.
     */
    private void runSubscriber() {
        while (!closed) {
            try (Jedis ps = openJedis(true)) {
                pubsubJedis = ps;
                ps.subscribe(listener, CHANNEL_BALANCE_INVALIDATION, CHANNEL_EVENTS);
                // subscribe() returns only on unsubscribe() or connection loss.
                if (closed) return;
                LOGGER.warn("Solidus Redis pub/sub ended unexpectedly; reconnecting in {}s.",
                    PUBSUB_RETRY_MS / 1000);
            } catch (Exception e) {
                if (closed) return;
                LOGGER.warn("Solidus Redis pub/sub connection failed ({}); reconnecting in {}s.",
                    String.valueOf(e.getMessage()), PUBSUB_RETRY_MS / 1000);
            }
            sleepQuietly(PUBSUB_RETRY_MS);
        }
    }

    /** Pub/sub adapter — decodes both channels through {@link #handleMessage}. */
    private final class Listener extends JedisPubSub {
        @Override
        public void onMessage(String channel, String message) {
            handleMessage(channel, message);
        }

        @Override
        public void onSubscribe(String channel, int count) {
            subscribedOnce.countDown();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            String json = call(j -> j.get(BALANCE_KEY_PREFIX + uuid));
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
            call(j -> j.setex(BALANCE_KEY_PREFIX + uuid, balanceTtlSeconds, GSON.toJson(obj)));
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
            call(j -> j.del(uuids.stream().map(u -> BALANCE_KEY_PREFIX + u).toArray(String[]::new)));
            call(j -> j.publish(CHANNEL_BALANCE_INVALIDATION, GSON.toJson(obj)));
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
            call(j -> j.publish(CHANNEL_EVENTS, GSON.toJson(obj)));
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

    /**
     * Runs one Redis call with a hard timeout so a stalled server cannot hang
     * a caller.
     *
     * <p>AUDIT FIX 2.2.6 (HNG-01): the call now runs on a tiny DEDICATED
     * daemon executor instead of the shared JDK common ForkJoinPool — a
     * stalled Redis used to pile up blocked commonPool threads (starving
     * parallel streams and other JDK machinery), and the timed-out task was
     * never cancelled. On timeout the future is cancelled and the breaker
     * counts the failure; the caller sees the bounded delay only.</p>
     */
    private <T> T guarded(java.util.function.Supplier<T> call) throws Exception {
        var future = java.util.concurrent.CompletableFuture.supplyAsync(call, GUARD_EXECUTOR);
        try {
            return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            future.cancel(true);
            throw te;
        }
    }

    /** Dedicated executor for guarded Redis calls (HNG-01). */
    private static final java.util.concurrent.ExecutorService GUARD_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Redis-Guard");
            t.setDaemon(true);
            return t;
        });

    /**
     * Runs one command on the guard executor against the live connection,
     * reconnecting first when a previous call lost the connection (lazy
     * recovery — the re-probe after the breaker cooldown adopts a recovered
     * server automatically, replacing the previous client's auto-reconnect).
     */
    private <T> T call(java.util.function.Function<Jedis, T> op) throws Exception {
        return guarded(() -> {
            try {
                return op.apply(cmd());
            } catch (JedisConnectionException e) {
                reconnectNeeded = true;
                throw e;
            }
        });
    }

    /** GUARD_EXECUTOR-confined: returns the live command connection. */
    private Jedis cmd() {
        if (closed) {
            throw new IllegalStateException("Redis layer is closed");
        }
        if (reconnectNeeded || commands == null) {
            Jedis dead = commands;
            commands = null;
            if (dead != null) {
                try {
                    dead.close();
                } catch (Exception ignored) {
                }
            }
            commands = openJedis(false);
            reconnectNeeded = false;
        }
        return commands;
    }

    @Override
    public void close() {
        shutdownInternals();
        LOGGER.info("Solidus Redis layer closed.");
    }

    /** Idempotent teardown used by both close() and a failed start(). */
    private void shutdownInternals() {
        closed = true;
        try {
            listener.unsubscribe();
        } catch (Exception ignored) {
        }
        Jedis ps = pubsubJedis;
        if (ps != null) {
            try {
                ps.close();
            } catch (Exception ignored) {
            }
        }
        Thread t = subscriber;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Jedis c = commands;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        commands = null;
    }
}
