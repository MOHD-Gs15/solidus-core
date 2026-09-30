package com.solidus.api;

import java.util.UUID;

/**
 * BalanceEntry — one row of the balance leaderboard.
 *
 * <p>Contract mirror of the historic {@code SQLiteStorage.BalanceEntry}
 * record: component names and order are part of the stable surface
 * (reflective 2.1.x-era readers bind to {@code uuid()}, {@code rank()},
 * {@code playerName()}, {@code balance()} by name).</p>
 *
 * @param uuid       the player's UUID (never null)
 * @param rank       1-based global rank
 * @param playerName the player's current display name
 * @param balance    the balance at snapshot time
 * @since 2.3.0 (family contract 2.3)
 */
public record BalanceEntry(UUID uuid, int rank, String playerName, double balance) {
}
