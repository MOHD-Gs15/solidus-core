package com.solidus.auction;

import com.solidus.util.CurrencyUtil;

/**
 * Pure, side-effect-free rules engine for the auction bidding system.
 *
 * <p>Extracted from {@link AuctionManager} so the arithmetic and validation
 * rules can be unit-tested without a Minecraft server (mirrors the
 * {@code searchListingsVia} static-core pattern already used for search).</p>
 *
 * <p><b>Bidding model (escrow):</b> when a player bids, the bid amount is
 * deducted from their balance IMMEDIATELY and held by the system (escrow).
 * When they are outbid the exact escrowed amount is refunded. When the
 * auction expires the highest bidder's escrow is released to the seller and
 * the item goes to the winner. This is the model used by mainstream auction
 * plugins (Crazy Auctions, AuctionHouse-style escrow) because it eliminates
 * the "win then can't pay" scam entirely - the money is always already
 * there when the auction ends.</p>
 */
public final class BidRules {

    /**
     * Minimum raise over the current highest bid, as a fraction of the
     * current bid (5%). A flat floor of {@link #MIN_RAISE_ABSOLUTE} also
     * applies so tiny early bids still move meaningfully.
     */
    public static final double MIN_RAISE_PERCENT = 0.05;

    /** Absolute minimum raise amount (in currency units). */
    public static final double MIN_RAISE_ABSOLUTE = 1.0;

    /**
     * If a bid lands within the final {@link #ANTI_SNIPE_WINDOW_MS} of the
     * expiry, the listing is extended by {@link #ANTI_SNIPE_EXTENSION_MS}.
     * Standard anti-snipe protection: prevents auction snipers from placing
     * an unbeatable bid 1 second before expiry.
     */
    public static final long ANTI_SNIPE_WINDOW_MS = 10 * 60 * 1000L;   // 10 minutes
    public static final long ANTI_SNIPE_EXTENSION_MS = 5 * 60 * 1000L; // 5 minutes

    /** Upper bound on how many times ONE listing may be extended (abuse cap). */
    public static final int MAX_ANTI_SNIPE_EXTENSIONS = 12;

    private BidRules() {
        // Utility class - no instantiation
    }

    /**
     * Computes the minimum acceptable next bid.
     *
     * @param startPrice  the opening (reserve) price configured by the seller
     * @param currentBid  the current highest bid ({@code null} when no bids yet)
     * @return the minimum amount a new bid must meet or exceed
     */
    public static double minNextBid(double startPrice, Double currentBid) {
        if (currentBid == null) {
            return Math.max(AuctionEntry.MIN_LISTING_PRICE, round2(startPrice));
        }
        double raise = Math.max(currentBid * MIN_RAISE_PERCENT, MIN_RAISE_ABSOLUTE);
        return round2(currentBid + raise);
    }

    /**
     * Validates a prospective bid against a listing's bid state.
     *
     * @param amount      the amount the player wants to bid
     * @param startPrice  the listing's opening price
     * @param currentBid  the current highest bid (null = no bids yet)
     * @return null when the bid is acceptable, otherwise a player-facing
     *         rejection reason
     */
    public static String validateBid(double amount, double startPrice, Double currentBid) {
        if (!Double.isFinite(amount)) {
            return "Bid amount must be a finite number.";
        }
        if (amount < AuctionEntry.MIN_LISTING_PRICE) {
            return "Minimum bid is " + CurrencyUtil.format(AuctionEntry.MIN_LISTING_PRICE) + ".";
        }
        if (amount > AuctionEntry.MAX_LISTING_PRICE) {
            return "Maximum bid is " + CurrencyUtil.format(AuctionEntry.MAX_LISTING_PRICE) + ".";
        }
        double minBid = minNextBid(startPrice, currentBid);
        if (amount < minBid) {
            return "Your bid is too low. Minimum bid is " + CurrencyUtil.format(minBid) + ".";
        }
        return null;
    }

    /**
     * Computes the new expiry timestamp when anti-snipe protection triggers.
     *
     * @param expireTimestamp the listing's current expiry
     * @param now             the time the bid was placed
     * @param extensionsUsed  how many times this listing was already extended
     * @return the new expiry (same as the old one when the cap is exhausted or
     *         the bid is outside the window)
     */
    public static long antiSnipeExpiry(long expireTimestamp, long now, int extensionsUsed) {
        if (extensionsUsed >= MAX_ANTI_SNIPE_EXTENSIONS) {
            return expireTimestamp;
        }
        long remaining = expireTimestamp - now;
        if (remaining > 0 && remaining <= ANTI_SNIPE_WINDOW_MS) {
            return expireTimestamp + ANTI_SNIPE_EXTENSION_MS;
        }
        return expireTimestamp;
    }

    /**
     * Rounds to 2 decimal places (matches the currency's storage precision).
     */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
