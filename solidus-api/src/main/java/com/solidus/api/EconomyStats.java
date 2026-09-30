package com.solidus.api;

/**
 * EconomyStats — economy-wide aggregate snapshot computed inside the
 * database (no balance rows are materialized).
 *
 * <p>Contract mirror of the historic {@code SQLiteStorage.EconomyStats}
 * record; component names are part of the stable surface.</p>
 *
 * @param playerCount    number of balance rows
 * @param avgBalance     mean balance across rows
 * @param totalSupply    sum of all balances (the money supply)
 * @param giniCoefficient Gini coefficient of the distribution (0 = equal)
 * @since 2.3.0 (family contract 2.3)
 */
public record EconomyStats(
    int playerCount,
    double avgBalance,
    double totalSupply,
    double giniCoefficient
) {
}
