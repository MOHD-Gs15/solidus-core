package com.solidus.networking;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimiter}.
 *
 * Validates the per-player click-cooldown behavior, including:
 * - First-click always allowed
 * - Subsequent clicks within the cooldown window are rejected
 * - Clicks after the cooldown window are allowed
 * - Thread safety under concurrent access
 * - Player removal and stale-entry cleanup
 */
@DisplayName("RateLimiter")
class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter();
    }

    @AfterEach
    void tearDown() {
        limiter.clear();
    }

    // -- Constants -------------------------------------------

    @Test
    @DisplayName("MIN_CLICK_INTERVAL_MS is 150")
    void minClickIntervalIsCorrect() {
        assertEquals(150, RateLimiter.MIN_CLICK_INTERVAL_MS);
    }

    // -- allowClick ------------------------------------------

    @Nested
    @DisplayName("allowClick()")
    class AllowClickTest {

        @Test
        @DisplayName("first click from a new player is allowed")
        void firstClickAllowed() {
            UUID player = UUID.randomUUID();
            assertTrue(limiter.allowClick(player));
        }

        @Test
        @DisplayName("immediate second click from the same player is rejected")
        void immediateSecondClickRejected() {
            UUID player = UUID.randomUUID();
            assertTrue(limiter.allowClick(player));
            assertFalse(limiter.allowClick(player));
        }

        @Test
        @DisplayName("click after cooldown elapses is allowed")
        void clickAfterCooldownAllowed() throws InterruptedException {
            UUID player = UUID.randomUUID();
            assertTrue(limiter.allowClick(player));
            // Wait for cooldown + small buffer
            Thread.sleep(RateLimiter.MIN_CLICK_INTERVAL_MS + 20);
            assertTrue(limiter.allowClick(player));
        }

        @Test
        @DisplayName("different players are rate-limited independently")
        void differentPlayersIndependent() {
            UUID player1 = UUID.randomUUID();
            UUID player2 = UUID.randomUUID();
            assertTrue(limiter.allowClick(player1));
            // Player 2's first click should be allowed even though player 1 just clicked
            assertTrue(limiter.allowClick(player2));
            // Player 1's immediate second click is still rejected
            assertFalse(limiter.allowClick(player1));
        }

        @Test
        @DisplayName("rapid succession: only first click passes, rest are dropped")
        void rapidSuccession() {
            UUID player = UUID.randomUUID();
            int allowed = 0;
            for (int i = 0; i < 50; i++) {
                if (limiter.allowClick(player)) allowed++;
            }
            assertEquals(1, allowed, "only the first rapid click should be allowed");
        }
    }

    // -- getRemainingCooldown --------------------------------

    @Nested
    @DisplayName("getRemainingCooldown()")
    class GetRemainingCooldownTest {

        @Test
        @DisplayName("returns 0 for unknown player")
        void unknownPlayerReturnsZero() {
            UUID player = UUID.randomUUID();
            assertEquals(0, limiter.getRemainingCooldown(player));
        }

        @Test
        @DisplayName("returns positive value immediately after a click")
        void afterClickReturnsPositive() {
            UUID player = UUID.randomUUID();
            limiter.allowClick(player);
            long remaining = limiter.getRemainingCooldown(player);
            assertTrue(remaining > 0, "remaining cooldown should be positive right after click");
            assertTrue(remaining <= RateLimiter.MIN_CLICK_INTERVAL_MS,
                "remaining should not exceed MIN_CLICK_INTERVAL_MS");
        }

        @Test
        @DisplayName("returns 0 after cooldown elapses")
        void returnsZeroAfterCooldown() throws InterruptedException {
            UUID player = UUID.randomUUID();
            limiter.allowClick(player);
            Thread.sleep(RateLimiter.MIN_CLICK_INTERVAL_MS + 20);
            assertEquals(0, limiter.getRemainingCooldown(player));
        }
    }

    // -- removePlayer ----------------------------------------

    @Nested
    @DisplayName("removePlayer()")
    class RemovePlayerTest {

        @Test
        @DisplayName("removed player's next click is allowed (fresh state)")
        void removedPlayerCanClickAgain() {
            UUID player = UUID.randomUUID();
            limiter.allowClick(player);
            assertFalse(limiter.allowClick(player)); // would be rejected
            limiter.removePlayer(player);
            assertTrue(limiter.allowClick(player)); // fresh state after removal
        }

        @Test
        @DisplayName("removePlayer with null UUID does not throw")
        void removeNullDoesNotThrow() {
            assertDoesNotThrow(() -> limiter.removePlayer(null));
        }

        @Test
        @DisplayName("removePlayer with unknown UUID does not throw")
        void removeUnknownDoesNotThrow() {
            assertDoesNotThrow(() -> limiter.removePlayer(UUID.randomUUID()));
        }
    }

    // -- getTrackedPlayerCount -------------------------------

    @Nested
    @DisplayName("getTrackedPlayerCount()")
    class GetTrackedPlayerCountTest {

        @Test
        @DisplayName("starts at 0")
        void startsAtZero() {
            assertEquals(0, limiter.getTrackedPlayerCount());
        }

        @Test
        @DisplayName("increases after first click from a player")
        void increasesAfterClick() {
            UUID player = UUID.randomUUID();
            limiter.allowClick(player);
            assertEquals(1, limiter.getTrackedPlayerCount());
        }

        @Test
        @DisplayName("does not increase on rejected (rate-limited) click")
        void doesNotIncreaseOnRejectedClick() {
            UUID player = UUID.randomUUID();
            limiter.allowClick(player);
            limiter.allowClick(player); // rejected, but player is still tracked
            assertEquals(1, limiter.getTrackedPlayerCount());
        }

        @Test
        @DisplayName("decreases after removePlayer")
        void decreasesAfterRemove() {
            UUID player = UUID.randomUUID();
            limiter.allowClick(player);
            assertEquals(1, limiter.getTrackedPlayerCount());
            limiter.removePlayer(player);
            assertEquals(0, limiter.getTrackedPlayerCount());
        }
    }

    // -- clear -----------------------------------------------

    @Test
    @DisplayName("clear() removes all tracked players")
    void clearRemovesAll() {
        for (int i = 0; i < 10; i++) {
            limiter.allowClick(UUID.randomUUID());
        }
        assertEquals(10, limiter.getTrackedPlayerCount());
        limiter.clear();
        assertEquals(0, limiter.getTrackedPlayerCount());
    }

    // -- Concurrency -----------------------------------------

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTest {

        @Test
        @DisplayName("100 threads clicking for the same player: only 1 succeeds")
        void concurrentClicksForSamePlayer() throws InterruptedException {
            UUID player = UUID.randomUUID();
            int threadCount = 100;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            AtomicInteger allowedCount = new AtomicInteger(0);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.allowClick(player)) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(1, allowedCount.get(),
                "exactly one concurrent click should be allowed");
        }

        @Test
        @DisplayName("100 threads clicking for different players: all 100 succeed")
        void concurrentClicksForDifferentPlayers() throws InterruptedException {
            int threadCount = 100;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            AtomicInteger allowedCount = new AtomicInteger(0);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final UUID player = UUID.randomUUID();
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.allowClick(player)) {
                            allowedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(threadCount, allowedCount.get(),
                "all concurrent clicks for different players should be allowed");
        }
    }
}
