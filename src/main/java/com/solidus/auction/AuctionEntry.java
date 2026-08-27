package com.solidus.auction;

import java.util.UUID;

/**
 * Immutable data model representing a single auction listing.
 *
 * Each auction entry represents an item listed by a player for sale
 * at a fixed price. Other players can browse and purchase these items.
 *
 * Listing Lifecycle:
 * 1. ACTIVE  - Item listed via /ah sell <price> (status=0)
 * 2. SOLD    - Purchased by another player (status=1)
 * 3. EXPIRED - Listing duration exceeded, item returned to seller (status=2)
 *
 * The ListingStatus enum properly represents all three states,
 * replacing the previous boolean 'sold' field which could not
 * distinguish between ACTIVE and EXPIRED listings.
 */
public record AuctionEntry(
    UUID listingId,           // Unique identifier for this listing
    UUID sellerUuid,          // The player who listed the item
    String sellerName,        // Cached seller display name
    String materialName,      // Minecraft Material name for the item
    int quantity,             // Number of items in the stack
    String itemNbt,           // Serialized item data (for enchanted/custom items)
    double price,             // Listed sale price in Solidus currency
    long listedTimestamp,     // Epoch millis when the item was listed
    long expireTimestamp,     // Epoch millis when the listing expires
    ListingStatus status      // Current status of the listing
) {

    /**
     * Default auction duration in milliseconds (72 hours).
     */
    public static final long DEFAULT_DURATION_MS = 72 * 60 * 60 * 1000L;

    /**
     * Maximum auction duration in milliseconds (168 hours = 7 days).
     */
    public static final long MAX_DURATION_MS = 168 * 60 * 60 * 1000L;

    /**
     * Minimum listing price.
     */
    public static final double MIN_LISTING_PRICE = 1.0;

    /**
     * Maximum listing price.
     */
    public static final double MAX_LISTING_PRICE = 10_000_000.0;

    /**
     * Listing fee percentage (2% of the listed price).
     * This fee is deducted from the seller's balance when listing
     * to discourage spam listings and stabilize the economy.
     */
    public static final double LISTING_FEE_PERCENT = 0.02;

    /**
     * Creates a new AuctionEntry with auto-generated IDs and timestamps.
     * New entries always start with ACTIVE status.
     */
    public static AuctionEntry create(UUID sellerUuid, String sellerName,
                                       String materialName, int quantity,
                                       String itemNbt, double price) {
        long now = System.currentTimeMillis();
        return new AuctionEntry(
            UUID.randomUUID(),
            sellerUuid,
            sellerName,
            materialName,
            quantity,
            itemNbt,
            price,
            now,
            now + DEFAULT_DURATION_MS,
            ListingStatus.ACTIVE
        );
    }

    /**
     * Checks if this listing has expired (ACTIVE status past its expiry time).
     */
    public boolean isExpired() {
        return status == ListingStatus.ACTIVE && System.currentTimeMillis() > expireTimestamp;
    }

    /**
     * Checks if this listing is currently active (not sold, not expired).
     */
    public boolean isActive() {
        return status == ListingStatus.ACTIVE && !isExpired();
    }

    /**
     * Calculates the listing fee for a given price.
     */
    public static double calculateListingFee(double price) {
        return Math.max(1.0, price * LISTING_FEE_PERCENT);
    }
}
