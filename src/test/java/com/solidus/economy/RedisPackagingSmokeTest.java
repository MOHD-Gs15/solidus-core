package com.solidus.economy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PACKAGING smoke test (audit W-2, 2.3.1) — the regression test for the bug
 * CI could never see before: the old build's SHIPPED jar nested only
 * lettuce-core while Lettuce hard-requires Netty and Reactor at runtime; the
 * Gradle TEST classpath silently supplied both, so every CI run stayed green
 * while {@code redis.enabled=true} in production meant
 * {@code NoClassDefFoundError}.
 *
 * <p>This test builds nothing itself. It takes the jar produced by
 * {@code ./gradlew jar} (skips politely when absent), extracts the nested
 * dependency jars from {@code META-INF/jars}, and drives {@code RedisLayer}
 * THROUGH AN ISOLATED {@code URLClassLoader} whose parent is {@code null}
 * (bootstrap JVM only). The only application classes reachable are the ones
 * physically shipped in the mod jar plus its nested jars, plus gson and
 * slf4j — which in production the Minecraft/Fabric runtime provides. A
 * missing runtime dependency therefore fails HERE, loudly, instead of on a
 * customer's server.</p>
 *
 * <p>Wire target: {@code SOLIDUS_TEST_REDIS_URI} when set (CI: a real
 * {@code redis:7} service container); otherwise an in-JVM fake RESP2 server,
 * so plain local runs still verify the packaged artifact end-to-end.</p>
 */
@DisplayName("Packaging smoke: the BUILT jar speaks Redis on its own (2.3.1, W-2)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisPackagingSmokeTest {

    private Path modJar;
    private FakeRedisServer fake;
    private String redisUri;

    @BeforeAll
    void findJarAndTarget() throws Exception {
        modJar = findBuiltJar();
        if (modJar == null) {
            Assumptions.abort("no built jar under build/libs — run ./gradlew jar first (CI does)");
        }
        redisUri = System.getenv("SOLIDUS_TEST_REDIS_URI");
        if (redisUri == null || redisUri.isBlank()) {
            fake = FakeRedisServer.start();
            redisUri = fake.uri();
        }
    }

    @AfterAll
    void stopFake() {
        if (fake != null) {
            fake.close();
        }
    }

    /** Newest non-sources jar in build/libs, or null when nothing was built. */
    private static Path findBuiltJar() throws IOException {
        Path libs = Paths.get("build", "libs");
        if (!Files.isDirectory(libs)) return null;
        try (Stream<Path> entries = Files.list(libs)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".jar") && !n.contains("-sources") && !n.contains("-javadoc");
                })
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .orElse(null);
        }
    }

    @Test
    @DisplayName("shipped jar nests the Redis runtime (jedis, pool2, json) — and never lettuce/netty")
    void nestsRedisDependencies() throws IOException {
        List<String> nested = nestedJarNames();
        assertTrue(nested.stream().anyMatch(n -> n.startsWith("jedis-")),
            "jedis must be nested — was: " + nested);
        assertTrue(nested.stream().anyMatch(n -> n.startsWith("commons-pool2-")),
            "commons-pool2 must be nested — was: " + nested);
        assertTrue(nested.stream().anyMatch(n -> n.startsWith("json-")),
            "org.json must be nested — was: " + nested);
        // The W-2 regression guards: the libraries that caused the original
        // production breakage (and the classpath-collision risk with
        // Minecraft's own Netty) must never come back nested.
        assertTrue(nested.stream().noneMatch(n -> n.contains("lettuce")),
            "lettuce must never ship again (W-2) — was: " + nested);
        assertTrue(nested.stream().noneMatch(n -> n.contains("netty")),
            "netty must never ship (collides with Minecraft's own Netty) — was: " + nested);
        // Family contract (audit W-5) rides in the same jar.
        assertTrue(nested.stream().anyMatch(n -> n.startsWith("solidus-api-")),
            "solidus-api must stay nested (W-5) — was: " + nested);
    }

    @Test
    @DisplayName("RedisLayer inside an isolated classloader (shipped jar + nested deps only) round-trips Redis")
    void packagedArtifactSpeaksRedis() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(modJar.toUri().toURL());
        Path extracted = Files.createTempDirectory("solidus-packaging-smoke");
        try {
            // The nested jars are exactly what Fabric's loader would put on
            // the classpath in production.
            try (JarFile jar = new JarFile(modJar.toFile())) {
                // !isDirectory(): the jar carries a "META-INF/jars/" DIRECTORY
                // entry whose stripped name is the empty string — resolving it
                // against the temp dir would REPLACE the directory itself
                // with a regular file and fail every subsequent copy with
                // FileSystemException("... Not a directory").
                for (var entry : jar.stream()
                        .filter(e -> e.getName().startsWith("META-INF/jars/")
                                && !e.isDirectory())
                        .toList()) {
                    Path out = extracted.resolve(entry.getName().substring("META-INF/jars/".length()));
                    try (var is = jar.getInputStream(entry)) {
                        Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    urls.add(out.toUri().toURL());
                }
            }
            // Production-runtime stand-ins Minecraft/Fabric provide:
            URL gson = runtimeLocationOf("com.google.gson.Gson");
            URL slf4jApi = runtimeLocationOf("org.slf4j.Logger");
            URL slf4jSimple = runtimeLocationOf("org.slf4j.simple.SimpleLogger");
            for (URL u : new URL[]{gson, slf4jApi, slf4jSimple}) {
                if (u != null) urls.add(u);
            }

            try (URLClassLoader isolated = new URLClassLoader(urls.toArray(URL[]::new), null)) {
                Class<?> settingsClass =
                    Class.forName("com.solidus.economy.StorageConfig$RedisSettings", true, isolated);
                Object settings = settingsClass
                    .getDeclaredConstructor(boolean.class, String.class, String.class, int.class)
                    .newInstance(true, redisUri, "SOLIDUS_TEST_REDIS_PASSWORD", 30);
                Class<?> layerClass =
                    Class.forName("com.solidus.economy.RedisLayer", true, isolated);

                // Sanity: the class really loaded from the BUILT jar, not
                // from this test's own classpath.
                assertEquals(modJar.toUri().toURL(),
                    layerClass.getProtectionDomain().getCodeSource().getLocation(),
                    "RedisLayer must load from the built jar");

                Object layer = layerClass.getMethod("start", settingsClass).invoke(null, settings);
                assertNotNull(layer, "layer must start against the (real or fake) Redis");
                try {
                    // 1. L2 cache round trip through the packaged code.
                    UUID uuid = UUID.randomUUID();
                    Object before = layerClass.getMethod("getCachedBalance", UUID.class).invoke(layer, uuid);
                    assertNull(before, "fresh key must miss");
                    layerClass.getMethod("cacheBalance", UUID.class, double.class)
                        .invoke(layer, uuid, 777.25);
                    Object after = layerClass.getMethod("getCachedBalance", UUID.class).invoke(layer, uuid);
                    assertEquals(777.25, (Double) after, 0.0001, "cache write-through must read back");

                    // 2. Events channel round trip through the packaged code.
                    CountDownLatch delivered = new CountDownLatch(1);
                    List<UUID> players = new ArrayList<>();
                    List<String> messages = new ArrayList<>();
                    BiConsumer<UUID, String> eventListener = (u, m) -> {
                        players.add(u);
                        messages.add(m);
                        delivered.countDown();
                    };
                    layerClass.getMethod("onPlayerEvent", BiConsumer.class).invoke(layer, eventListener);
                    UUID player = UUID.randomUUID();
                    layerClass.getMethod("publishPlayerEvent", UUID.class, String.class)
                        .invoke(layer, player, "packaged smoke says hi");
                    assertTrue(delivered.await(5, TimeUnit.SECONDS),
                        "event must round-trip through the packaged artifact");
                    assertEquals(player, players.get(0));
                    assertEquals("packaged smoke says hi", messages.get(0));

                    // 3. Invalidation bus round trip through the packaged code.
                    CountDownLatch invalidated = new CountDownLatch(1);
                    List<List<UUID>> batches = new ArrayList<>();
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Consumer<List<UUID>> invalidationListener = ids -> {
                        batches.add(ids);
                        invalidated.countDown();
                    };
                    layerClass.getMethod("onBalanceInvalidation", Consumer.class)
                        .invoke(layer, invalidationListener);
                    layerClass.getMethod("publishBalanceInvalidation", List.class)
                        .invoke(layer, List.of(uuid));
                    assertTrue(invalidated.await(5, TimeUnit.SECONDS),
                        "invalidation must round-trip through the packaged artifact");
                    assertEquals(List.of(uuid), batches.get(0));
                } finally {
                    layerClass.getMethod("close").invoke(layer);
                }
            }
        } catch (InvocationTargetException ite) {
            // A NoClassDefFoundError / NoSuchMethodError here IS the W-2 class
            // of failure: the packaged artifact is missing runtime pieces
            // again. Fail with an unmistakable message.
            Throwable cause = ite.getCause();
            if (cause instanceof NoClassDefFoundError || cause instanceof NoSuchMethodError) {
                fail("PACKAGED ARTIFACT IS BROKEN (W-2 regression): " + cause
                    + " — the shipped jar is missing a runtime dependency."
                    + " Keep build.gradle include() lines and the nested-jar"
                    + " assertions above in sync.");
            }
            throw ite;
        } finally {
            try (Stream<Path> walk = Files.walk(extracted)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    private List<String> nestedJarNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(modJar.toFile())) {
            for (var entry : jar.stream()
                    .filter(e -> e.getName().startsWith("META-INF/jars/")
                            && !e.isDirectory())
                    .toList()) {
                names.add(entry.getName().substring("META-INF/jars/".length()));
            }
        }
        return names;
    }

    /** Where a class's defining jar lives on the current classpath, or null. */
    private static URL runtimeLocationOf(String className) {
        try {
            return Class.forName(className).getProtectionDomain().getCodeSource().getLocation();
        } catch (Exception e) {
            return null;
        }
    }
}
