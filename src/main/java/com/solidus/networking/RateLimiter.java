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
 * The same mechanism also throttles /pay transfers (separate bucket, separate
 * interval): each payment writes two transaction rows, sends two messages and
 * invokes registered hooks, so flooding /pay would pollute the ledger and press
 * the database even from an unmodified client with a command macro.
 *
 * Configuration:
 * - MIN_CLICK_INTERVAL_MS: Minimum milliseconds between allowed clicks (150ms)
 *   This translates to ~6.6 clicks/second maximum, which is more than enough
 *   for normal gameplay but far below what cheat clients can produce.
 * - MIN_PAY_INTERVAL_MS: Minimum milliseconds between allowed /pay commands (1s)
 *   Caps transfers at 1/second/player - far above legitimate use, far below
 *   what a spam macro could produce.
 * - CLEANUP_INTERVAL_MS: How often to clean up stale entries (60 seconds)
 * - STALE_THRESHOLD_MS: How long before an entry is considered stale (5 minutes)
 */
public class RateLimiter {

    /** Minimum interval between allowed clicks in milliseconds */
    public static final long MIN_CLICK_INTERVAL_MS = 150;

    /** Minimum interval between allowed /pay transfers in milliseconds */
    public static final long MIN_PAY_INTERVAL_MS = 1_000;

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

    /**
     * Tracks the last allowed /pay timestamp for each player.
     * Kept separate from clicks so a GUI click never consumes a transfer slot
     * and vice versa.
     */
    private final ConcurrentHashMap<UUID, Long> lastPayTimestamps = new ConcurrentHashMap<>();

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
        return tryAcquire(lastClickTimestamps, playerUuid, MIN_CLICK_INTERVAL_MS);
    }

    /**
     * Checks whether a /pay transfer from the given player should be allowed.
     * Used as an anti-spam gate on the /pay command path; the caller should
     * surface a friendly message instead of dropping silently.
     *
     * @param playerUuid The UUID of the sending player
     * @return true if the transfer is allowed (cooldown has elapsed),
     *         false if it came too fast after the previous one
     */
    public boolean allowTransfer(UUID playerUuid) {
        return tryAcquire(lastPayTimestamps, playerUuid, MIN_PAY_INTERVAL_MS);
    }

    /**
     * Shared atomic check-then-update used by both buckets.
     * Uses compute() so concurrent callers can never all pass the check.
     */
    private boolean tryAcquire(ConcurrentHashMap<UUID, Long> timestamps,
                               UUID playerUuid, long minIntervalMs) {
        long now = System.currentTimeMillis();

        // Periodic cleanup of stale entries to prevent memory leaks
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupStaleEntries(now);
            lastCleanupTime = now;
        }

        final boolean[] allowed = {false};

        timestamps.compute(playerUuid, (uuid, last) -> {
            if (last == null) {
                // First action from this player - allow it
                allowed[0] = true;
                return now;
            }

            long elapsed = now - last;

            if (elapsed < minIntervalMs) {
                // Action came too fast - deny it, don't update timestamp
                allowed[0] = false;
                return last;
            }

            // Action is within allowed rate - update timestamp and allow
            allowed[0] = true;
            return now;
        });

        return allowed[0];
    }

    /**
     * Gets the remaining cooldown time for a player.
     *
     * @param playerUuid The player's UUID
     * @return Remaining click cooldown in milliseconds, or 0 if no cooldown is active
     */
    public long getRemainingCooldown(UUID playerUuid) {
        return remaining(lastClickTimestamps, playerUuid, MIN_CLICK_INTERVAL_MS);
    }

    /**
     * Gets the remaining transfer cooldown for a player.
     * Used by /pay to report a friendly wait time instead of failing silently.
     *
     * @param playerUuid The player's UUID
     * @return Remaining transfer cooldown in milliseconds, or 0 if none is active
     */
    public long getRemainingTransferCooldown(UUID playerUuid) {
        return remaining(lastPayTimestamps, playerUuid, MIN_PAY_INTERVAL_MS);
    }

    /** Shared remaining-time computation for both buckets. */
    private long remaining(ConcurrentHashMap<UUID, Long> timestamps,
                           UUID playerUuid, long minIntervalMs) {
        Long last = timestamps.get(playerUuid);
        if (last == null) return 0;

        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0, minIntervalMs - elapsed);
    }

    /**
     * Removes both rate limit entries (clicks and transfers) for a player.
     * Called when a player disconnects to free memory.
     *
     * @param playerUuid The UUID of the disconnected player
     */
    public void removePlayer(UUID playerUuid) {
        if (playerUuid == null) return; // defensive: ConcurrentHashMap does not allow null keys
        lastClickTimestamps.remove(playerUuid);
        lastPayTimestamps.remove(playerUuid);
    }

    /**
     * Gets the current number of tracked players.
     * Useful for monitoring and debugging.
     */
    public int getTrackedPlayerCount() {
        return lastClickTimestamps.size();
    }

    /**
     * Cleans up entries for players who haven't acted in a while.
     * Prevents memory leaks from players who connected but never interacted again.
     */
    private void cleanupStaleEntries(long now) {
        lastClickTimestamps.entrySet().removeIf(entry ->
            (now - entry.getValue()) > STALE_THRESHOLD_MS
        );
        lastPayTimestamps.entrySet().removeIf(entry ->
            (now - entry.getValue()) > STALE_THRESHOLD_MS
        );
    }

    /**
     * Clears all rate limit entries (clicks and transfers).
     * Should be called on server shutdown.
     */
    public void clear() {
        lastClickTimestamps.clear();
        lastPayTimestamps.clear();
    }
}
