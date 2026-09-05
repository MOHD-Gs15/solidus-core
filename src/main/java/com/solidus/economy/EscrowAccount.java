package com.solidus.economy;

import java.util.UUID;

/**
 * The Solidus ESCROW system account.
 *
 * <p>Bid-escrow for the auction house works by moving the bid amount from the
 * bidder into this dedicated account the moment the bid is placed (via the
 * hardened {@code transferAtomicWithLedger} primitive - atomic money + ledger).
 * On settlement the amount is transferred escrow-to-seller; on outbid / cancel
 * / buy-now-override it is transferred escrow-to-bidder. Money therefore never
 * disappears and never double-exists: every leg is one atomic transaction with
 * its ledger evidence.</p>
 *
 * <p>The all-zero UUID is a sentinel no real player can ever hold. The account
 * is pre-created with balance 0 (see {@code EconomyEngine.initialize()}) so no
 * phantom "starting balance" money is minted when the first bid lands.</p>
 *
 * <p>Leaderboards and player counts exclude this account (see
 * {@code SQLiteStorage#getTopBalances}); economy-wide statistics deliberately
 * keep it - escrowed money is still in circulation, just temporarily locked.</p>
 */
public final class EscrowAccount {

    /** Sentinel UUID (all zeros) - cannot collide with any real player UUID. */
    public static final UUID UUID_ZERO = new UUID(0L, 0L);

    /** Display name stored alongside the escrow balance row. */
    public static final String NAME = "Solidus Escrow";

    private static final String UUID_ZERO_STRING = UUID_ZERO.toString();

    private EscrowAccount() {
        // Utility class - no instantiation
    }

    /** True when the given UUID is a Solidus system account (not a player). */
    public static boolean isSystemAccount(UUID uuid) {
        return UUID_ZERO.equals(uuid);
    }

    /** The SQL literal of the sentinel UUID for WHERE-clause exclusions. */
    public static String sqlLiteral() {
        return "'" + UUID_ZERO_STRING + "'";
    }
}
