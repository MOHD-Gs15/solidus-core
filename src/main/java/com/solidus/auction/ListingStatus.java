package com.solidus.auction;

/**
 * Represents the lifecycle status of an auction listing.
 *
 * Stored in the database as an integer:
 * - 0 = ACTIVE  - Listing is visible and available for purchase
 * - 1 = SOLD    - Listing has been purchased by another player
 * - 2 = EXPIRED - Listing duration exceeded, item should be returned to seller
 *
 * This enum replaces the previous boolean 'sold' field which could not
 * distinguish between ACTIVE and EXPIRED listings (both were 'false'),
 * causing expired items to potentially reappear in active listings.
 */
public enum ListingStatus {

    /** Listing is active and available for purchase */
    ACTIVE(0),

    /** Listing has been purchased by another player */
    SOLD(1),

    /** Listing has expired and the item should be returned to the seller */
    EXPIRED(2);

    private final int code;

    ListingStatus(int code) {
        this.code = code;
    }

    /** Returns the integer code stored in the database */
    public int code() {
        return code;
    }

    /**
     * Converts a database integer code to a ListingStatus.
     * Any unknown code defaults to EXPIRED for safety
     * (prevents unknown listings from appearing as active).
     *
     * @param code The integer code from the database
     * @return The corresponding ListingStatus
     */
    public static ListingStatus fromCode(int code) {
        return switch (code) {
            case 0 -> ACTIVE;
            case 1 -> SOLD;
            default -> EXPIRED; // Unknown codes treated as expired (safe default)
        };
    }
}
