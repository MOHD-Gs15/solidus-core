package com.solidus.api;

import java.util.UUID;

/**
 * SolidusTransactionHook - Economy transaction hook interface.
 *
 * <p>Allows companion mods (e.g. Solidus Governance) to intercept Solidus
 * economy transactions at every money-movement point:</p>
 *
 * <ul>
 *   <li><b>Veto hooks</b> ({@code allowXxx}): called <i>before</i> money moves.
 *       Returning a denial aborts the transaction cleanly - no balances are
 *       touched, no items are taken, the player sees the denial reason.</li>
 *   <li><b>Notification hooks</b> ({@code afterXxx}): called <i>after</i> a
 *       transaction has fully settled successfully. Used for limit recording,
 *       tax collection, statistics, alerts, etc.</li>
 * </ul>
 *
 * <h3>Hooked transaction points (Solidus 2.1.0+):</h3>
 * <table border="1">
 *   <tr><th>Flow</th><th>Veto hook</th><th>Notification hook</th></tr>
 *   <tr><td>/pay and API transfers (online + offline)</td><td>{@link #allowTransfer}</td><td>{@link #afterTransfer}</td></tr>
 *   <tr><td>Auction listing creation</td><td>{@link #allowAuctionListing}</td><td>{@link #afterAuctionListing}</td></tr>
 *   <tr><td>Auction purchase</td><td>{@link #allowAuctionPurchase}</td><td>{@link #afterAuctionSale}</td></tr>
 *   <tr><td>Shop purchase (GUI)</td><td>{@link #allowShopPurchase}</td><td>{@link #afterShopPurchase}</td></tr>
 *   <tr><td>Shop sell (GUI, /sell all, Sell GUI)</td><td>{@link #allowShopSell}</td><td>{@link #afterShopSell}</td></tr>
 * </table>
 *
 * <h3>Threading contract:</h3>
 * <ul>
 *   <li>Veto hooks run synchronously on the caller's thread (usually the
 *       server tick thread, or the auction executor for purchases).
 *       They <b>must be fast and non-blocking</b> - in-memory checks only.
 *       They must NOT synchronously call back into Solidus balance APIs
 *       (that would queue work on the economy executor and risk deadlock).</li>
 *   <li>Notification hooks run after the movement has settled. They may
 *       dispatch async work (e.g. chain {@code SolidusAPI} futures) but must
 *       never block the calling thread waiting for a result.</li>
 * </ul>
 *
 * <h3>Failure policy (fail-open):</h3>
 * If a hook throws from any method, the exception is logged and the hook is
 * skipped for that call. A throwing veto hook can never wedge the economy.
 *
 * <h3>Registration (reflection-based, zero compile dependency):</h3>
 * <pre>{@code
 * Class<?> hookItf = Class.forName("com.solidus.api.SolidusTransactionHook");
 * Object proxy = Proxy.newProxyInstance(hookItf.getClassLoader(),
 *         new Class<?>[]{ hookItf }, myInvocationHandler);
 * Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
 * Object api = apiClass.getMethod("getInstance").invoke(null);
 * apiClass.getMethod("registerTransactionHook", hookItf).invoke(api, proxy);
 * }</pre>
 *
 * <p>All methods have default implementations, so a hook only overrides what
 * it needs (a reflection Proxy handler can fall back to generic defaults:
 * {@code ALLOW} / no-op / {@code 0.0}).</p>
 *
 * @see SolidusAPI#registerTransactionHook(SolidusTransactionHook)
 * @since 2.1.0
 */
public interface SolidusTransactionHook {

    /**
     * Unique hook name (used for duplicate-registration detection and logging).
     * Example: {@code "solidus-governance"}.
     */
    String name();

    // -- Veto hooks (pre-transaction) ----------------------

    /**
     * Called before any peer-to-peer transfer (/pay online + offline, and the
     * SolidusAPI transfer methods - all flows funnel through this hook).
     *
     * @param senderUuid   the sender's UUID
     * @param senderName   the sender's name
     * @param receiverUuid the receiver's UUID
     * @param receiverName the receiver's name
     * @param amount       the amount about to be transferred
     * @return {@link Decision#ALLOW} to permit, or a denial to abort
     */
    default Decision allowTransfer(UUID senderUuid, String senderName,
                                   UUID receiverUuid, String receiverName,
                                   double amount) {
        return Decision.ALLOW;
    }

    /**
     * Called before a player creates an auction listing (before the listing
     * fee is charged and before the item leaves the player's hand).
     *
     * @param sellerUuid the listing player's UUID
     * @param sellerName the listing player's name
     * @param price      the requested listing price
     * @return {@link Decision#ALLOW} to permit, or a denial to abort
     */
    default Decision allowAuctionListing(UUID sellerUuid, String sellerName, double price) {
        return Decision.ALLOW;
    }

    /**
     * Called before a buyer purchases an auction listing (after the listing
     * was validated as active, before any money moves).
     *
     * @param buyerUuid the buyer's UUID
     * @param buyerName the buyer's name
     * @param price     the listing's purchase price
     * @return {@link Decision#ALLOW} to permit, or a denial to abort
     */
    default Decision allowAuctionPurchase(UUID buyerUuid, String buyerName, double price) {
        return Decision.ALLOW;
    }

    /**
     * Called before a player buys from the admin shop, after the total cost
     * is known but before any money moves.
     *
     * @param playerUuid the buyer's UUID
     * @param playerName the buyer's name
     * @param cost       the total purchase cost
     * @return {@link Decision#ALLOW} to permit, or a denial to abort
     */
    default Decision allowShopPurchase(UUID playerUuid, String playerName, double cost) {
        return Decision.ALLOW;
    }

    /**
     * Called before a player sells items to the shop (all sell flows: shop
     * GUI sell button, Sell GUI close, /sell all). The exact payout is not
     * known at veto time for batch flows, so no amount is passed - use
     * {@link #afterShopSell} to observe the actual payout.
     *
     * @param playerUuid the seller's UUID
     * @param playerName the seller's name
     * @return {@link Decision#ALLOW} to permit, or a denial to abort
     */
    default Decision allowShopSell(UUID playerUuid, String playerName) {
        return Decision.ALLOW;
    }

    // -- Notification hooks (post-transaction) -------------

    /**
     * Called after a transfer has fully settled (both balances updated).
     *
     * @param senderUuid   the sender's UUID
     * @param senderName   the sender's name
     * @param receiverUuid the receiver's UUID
     * @param receiverName the receiver's name
     * @param amount       the transferred amount
     */
    default void afterTransfer(UUID senderUuid, String senderName,
                               UUID receiverUuid, String receiverName,
                               double amount) {
    }

    /**
     * Called after an auction listing was successfully created (fee charged,
     * item captured, listing persisted).
     *
     * @param sellerUuid the listing player's UUID
     * @param sellerName the listing player's name
     * @param price      the listing price
     * @param fee        the listing fee that was charged
     */
    default void afterAuctionListing(UUID sellerUuid, String sellerName,
                                     double price, double fee) {
    }

    /**
     * Called after an auction sale has fully settled (buyer charged, seller
     * paid, item delivered to the buyer).
     *
     * @param sellerUuid the seller's UUID (may be offline)
     * @param sellerName the seller's name
     * @param buyerUuid  the buyer's UUID
     * @param buyerName  the buyer's name
     * @param price      the sale price
     */
    default void afterAuctionSale(UUID sellerUuid, String sellerName,
                                  UUID buyerUuid, String buyerName,
                                  double price) {
    }

    /**
     * Called after a shop purchase has fully settled (money deducted, items
     * delivered or dropped at the player's feet).
     *
     * @param playerUuid the buyer's UUID
     * @param playerName the buyer's name
     * @param cost       the total amount that was charged
     */
    default void afterShopPurchase(UUID playerUuid, String playerName, double cost) {
    }

    /**
     * Called after a shop sell has fully settled (items removed, payout
     * credited).
     *
     * @param playerUuid the seller's UUID
     * @param playerName the seller's name
     * @param payout     the amount that was credited
     */
    default void afterShopSell(UUID playerUuid, String playerName, double payout) {
    }

    /**
     * Decision returned by veto hooks.
     *
     * @param allowed true to permit the transaction
     * @param reason  human-readable denial reason shown to the player when
     *                denied (ignored when allowed). Null when allowed.
     */
    record Decision(boolean allowed, String reason) {

        /** Shared permit-all decision. */
        public static final Decision ALLOW = new Decision(true, null);

        /**
         * Builds a denial with a player-facing reason.
         *
         * @param reason why the transaction is denied (shown to the player)
         * @return a denied decision
         */
        public static Decision deny(String reason) {
            return new Decision(false, reason != null ? reason : "Transaction denied.");
        }
    }
}
