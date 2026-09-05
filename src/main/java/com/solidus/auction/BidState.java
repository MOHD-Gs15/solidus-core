package com.solidus.auction;

import java.util.UUID;

/**
 * Immutable snapshot of the bid state attached to an auction listing.
 *
 * <p>Bid state is stored in a dedicated {@code auction_bid_state} table
 * (one optional row per listing) instead of adding columns to
 * {@code auction_listings}. This keeps {@link AuctionEntry} - and every
 * test/consumer built on it - completely unchanged, and makes bidding a
 * purely additive feature: a listing without a bid-state row is simply a
 * buy-now-only listing.</p>
 *
 * @param listingId        the listing this state belongs to
 * @param startPrice       the opening (reserve) price the seller configured
 * @param currentBid       the current highest escrowed bid (null = no bids yet)
 * @param currentBidderUuid  the highest bidder's UUID (null = no bids yet)
 * @param currentBidderName  the highest bidder's display name (null = no bids yet)
 * @param bidCount         how many bids have been placed so far
 * @param extensionsUsed   how many anti-snipe extensions were applied
 */
public record BidState(
    UUID listingId,
    double startPrice,
    Double currentBid,
    UUID currentBidderUuid,
    String currentBidderName,
    int bidCount,
    int extensionsUsed
) {
    /** Creates the initial state for a fresh bidding-enabled listing. */
    public static BidState initial(UUID listingId, double startPrice) {
        return new BidState(listingId, startPrice, null, null, null, 0, 0);
    }

    /** True when at least one escrowed bid exists on this listing. */
    public boolean hasBids() {
        return currentBid != null && currentBidderUuid != null;
    }
}
