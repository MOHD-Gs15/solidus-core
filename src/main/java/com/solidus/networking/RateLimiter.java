package com.solidus.networking;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet Rate Limiter - Prevents cheat clients from exploiting virtual menus.
 *
 * Problem:
 * Cheat clients like Meteor Client can send hundreds of container click packets
 * per second, potentially exploiting race conditions in shop/auction transactions
 * or causing server lag through packet flooding.
 *
 * Solution:
 * Implements a strict millisecond cooldown on incoming click packets per player UUID.
 * If a player sends a click packet before the cooldown has elapsed, the packet
 * is silently dropped. This effectively neutralizes speed-based exploits while
 * having zero impact on legitimate players who click at normal human speeds.
 *
 * Configuration:
 * - MIN_CLICK_INTERVAL_MS: Minimum milliseconds between allowed clicks (150ms)
 *   This translates to ~6.6 clicks/second maximum, which is more than enough
 *   for normal gameplay but far below what cheat clients can produce.
 * - CLEANUP_INTERVAL_MS: How often to clean up stale entries (60 seconds)
 * - STALE_THRESHOLD_MS: How long before an entry is considered stale (5 minutes)
 */
public class RateLimiter {

    /** Minimum interval between allowed clicks in milliseconds */
    public static final long MIN_CLICK_INTERVAL_MS = 150;

    /** How often to run cleanup of stale entries */
    private static final long CLEANUP_INTERVAL_MS = 60_000;

    /** How long before an entry is considered stale and can be removed */
    private static final long STALE_THRESHOLD_MS = 300_000; // 5 minutes

    /**
     * Tracks the last allowed click timestamp for each player.
     * Key: Player UUID
     * Value: Epoch millisecond timestamp of the last allowed click
     */
    private final ConcurrentHashMap<UUID, Long> lastClickTimestamps = new ConcurrentHashMap<>();

    /** Timestamp of the last cleanup run */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    /**
     * Checks whether a click from the given player should be allowed.
     *
     * @param playerUuid The UUID of the player who clicked
     * @return true if the click is allowed (cooldown has elapsed),
     *         false if the click should be silently dropped
     */
    public boolean allowClick(UUID playerUuid) {
        long now = System.currentTimeMillis();

        // Periodic cleanup of stale entries to prevent memory leaks
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupStaleEntries(now);
            lastCleanupTime = now;
        }

        // Use compute() for atomic check-then-update, preventing race conditions
        // even if called from multiple threads concurrently
        final boolean[] allowed = {false};

        lastClickTimestamps.compute(playerUuid, (uuid, lastClick) -> {
            if (lastClick == null) {
                // First click from this player - allow it
                allowed[0] = true;
                return now;
            }

            long elapsed = now - lastClick;

            if (elapsed < MIN_CLICK_INTERVAL_MS) {
                // Click came too fast - deny it, don't update timestamp
                allowed[0] = false;
                return lastClick;
            }

            // Click is within allowed rate - update timestamp and allow
            allowed[0] = true;
            return now;
        });

        return allowed[0];
    }

    /**
     * Gets the remaining cooldown time for a player.
     *
     * @param playerUuid The player's UUID
     * @return Remaining cooldown in milliseconds, or 0 if no cooldown is active
     */
    public long getRemainingCooldown(UUID playerUuid) {
        Long lastClick = lastClickTimestamps.get(playerUuid);
        if (lastClick == null) return 0;

        long elapsed = System.currentTimeMillis() - lastClick;
        long remaining = MIN_CLICK_INTERVAL_MS - elapsed;
        return Math.max(0, remaining);
    }

    /**
     * Removes the rate limit entry for a player.
     * Called when a player disconnects to free memory.
     *
     * @param playerUuid The UUID of the disconnected player
     */
    public void removePlayer(UUID playerUuid) {
        if (playerUuid == null) return; // defensive: ConcurrentHashMap does not allow null keys
        lastClickTimestamps.remove(playerUuid);
    }

    /**
     * Gets the current number of tracked players.
     * Useful for monitoring and debugging.
     */
    public int getTrackedPlayerCount() {
        return lastClickTimestamps.size();
    }

    /**
     * Cleans up entries for players who haven't clicked in a while.
     * Prevents memory leaks from players who connected but never interacted again.
     */
    private void cleanupStaleEntries(long now) {
        lastClickTimestamps.entrySet().removeIf(entry ->
            (now - entry.getValue()) > STALE_THRESHOLD_MS
        );
    }

    /**
     * Clears all rate limit entries.
     * Should be called on server shutdown.
     */
    public void clear() {
        lastClickTimestamps.clear();
    }
}
