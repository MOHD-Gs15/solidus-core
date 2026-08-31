package com.solidus.auction;

import com.solidus.SolidusMod;
import com.solidus.api.EconomyHooks;
import com.solidus.api.SolidusTransactionHook;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.TransactionLog;
import com.solidus.util.CurrencyUtil;
import com.solidus.util.TextUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Auction Manager - Core controller for the Auction House system.
 *
 * Design Principles:
 * - Player-Driven Economy: The auction house enables peer-to-peer commerce.
 *   Items like Armor Trims are excluded from the server shop, forcing them
 *   into the auction house to incentivize real survival exploration.
 *
 * - Concurrency Security (Race Condition / Anti-Dupe Protection):
 *   All auction mutations (listing, purchasing, expiring) are processed through
 *   a dedicated single-thread executor. This guarantees sequential execution
 *   without any overlap, completely eliminating race conditions without needing
 *   database-level locking (BEGIN IMMEDIATE).
 *
 * - No Server Thread Blocking:
 *   All async operations use CompletableFuture chaining (.thenAccept, .thenCompose)
 *   instead of .join(). This ensures the server tick thread is never blocked,
 *   preventing lag spikes for all players.
 *
 * - MinecraftServer Injection:
 *   The server instance is injected via ServerLifecycleEvents.SERVER_STARTED
 *   instead of the static MinecraftServer.getServer() call, which does not exist
 *   in the Fabric modding environment.
 *
 * - Listing Status:
 *   Uses ListingStatus enum (ACTIVE, SOLD, EXPIRED) instead of a boolean,
 *   properly representing the three distinct states of an auction listing.
 *
 * - Persistent Database Connection:
 *   Uses a single persistent SQLite connection per executor instead of opening
 *   a new connection for every operation. Since all operations are serialized
 *   through the single-threaded executor, connection sharing is safe.
 */
public class AuctionManager {

    private static final String DATABASE_NAME = "auctions.db";
    // Package-private so the settlement-history tests can build the same schema.
    static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS auction_listings (
            listing_id TEXT PRIMARY KEY NOT NULL,
            seller_uuid TEXT NOT NULL,
            seller_name TEXT NOT NULL,
            material_name TEXT NOT NULL,
            quantity INTEGER NOT NULL,
            item_nbt TEXT,
            price REAL NOT NULL,
            listed_timestamp INTEGER NOT NULL,
            expire_timestamp INTEGER NOT NULL,
            status INTEGER NOT NULL DEFAULT 0
        )
    """;
    static final String CREATE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_active_listings
        ON auction_listings (status, expire_timestamp)
    """;

    /**
     * Append-only archive of settled listings.
     *
     * <p>Every row removed from {@code auction_listings} is first (or
     * atomically, in the same transaction) copied here, so a failed
     * TransactionLog insert can never erase the only record of a completed
     * sale. {@code item_nbt} is intentionally not archived: analytics and
     * audit need material/quantity/price, not serialized NBT blobs.</p>
     */
    static final String CREATE_SOLD_HISTORY_SQL = """
        CREATE TABLE IF NOT EXISTS auction_sold_history (
            listing_id TEXT PRIMARY KEY NOT NULL,
            seller_uuid TEXT NOT NULL,
            seller_name TEXT NOT NULL,
            material_name TEXT NOT NULL,
            quantity INTEGER NOT NULL,
            price REAL NOT NULL,
            buyer_uuid TEXT,
            buyer_name TEXT,
            listed_timestamp INTEGER NOT NULL,
            settled_timestamp INTEGER NOT NULL,
            settled_reason TEXT NOT NULL
        )
    """;
    static final String CREATE_SOLD_HISTORY_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_sold_history_time
        ON auction_sold_history (settled_timestamp DESC)
    """;

    // settled_reason values (package-private for tests)
    static final String SETTLED_SOLD = "SOLD";
    static final String SETTLED_EXPIRED_RETURN = "EXPIRED_RETURN";
    static final String SETTLED_EXPIRED_COLLECT = "EXPIRED_COLLECT";
    static final String SETTLED_CANCELLED = "CANCELLED";

    private final EconomyEngine economyEngine;
    private final ExecutorService asyncExecutor;
    private final String databaseUrl;
    private volatile boolean initialized = false;

    /** Injected MinecraftServer instance - set via setServer() during SERVER_STARTED */
    private volatile MinecraftServer server;

    /** Persistent database connection - shared across all executor operations */
    private volatile Connection persistentConnection;

    /**
     * Tracks players with a pending listing operation to prevent:
     * 1. Concurrent listings that could double-charge listing fees
     * 2. TOCTOU between the held item capture and item removal
     */
    private final Set<UUID> pendingListings = ConcurrentHashMap.newKeySet();

    public AuctionManager(EconomyEngine economyEngine) {
        this.economyEngine = economyEngine;
        this.databaseUrl = "jdbc:sqlite:" + getDatabasePath();
        // Single-threaded executor guarantees sequential consistency for all
        // auction DB operations - NO race conditions possible, NO locking needed
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Solidus-Auction-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Injects the MinecraftServer instance.
     * Called via ServerLifecycleEvents.SERVER_STARTED in SolidusMod.
     *
     * This is required because MinecraftServer.getServer() is NOT available
     * in the Fabric modding environment. The server instance must be injected
     * through lifecycle events.
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
        SolidusMod.LOGGER.info("AuctionManager: MinecraftServer instance injected.");
    }

    /**
     * Initializes the auction database.
     */
    public void initialize() {
        try {
            // Open persistent connection (safe because single-threaded executor serializes all access)
            persistentConnection = DriverManager.getConnection(databaseUrl);
            try (Statement stmt = persistentConnection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute(CREATE_TABLE_SQL);
                stmt.execute(CREATE_INDEX_SQL);
                stmt.execute(CREATE_SOLD_HISTORY_SQL);
                stmt.execute(CREATE_SOLD_HISTORY_INDEX_SQL);
            }
            initialized = true;
            SolidusMod.LOGGER.info("Auction database initialized successfully.");
            // Startup recovery: status=1 rows are crash residues (purchase marked
            // SOLD but settlement never finished) or archive failures kept as
            // evidence. Reconcile them against TransactionLog now.
            recoverOrphanedSoldRowsFromEconomy();
        } catch (SQLException e) {
            SolidusMod.LOGGER.error("Failed to initialize auction database!", e);
        }
    }

    /**
     * Shuts down the auction database executor and closes the persistent connection.
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close persistent connection
        if (persistentConnection != null) {
            try {
                persistentConnection.close();
                SolidusMod.LOGGER.info("Auction database connection closed.");
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to close auction database connection", e);
            }
        }
    }

    // -- Listing Operations --------------------------------

    /**
     * Lists an item from the player's main hand on the auction house.
     *
     * Flow (fully async - no server thread blocking):
     * 1. Validate the player is holding an item
     * 2. Calculate and deduct the listing fee (async chain)
     * 3. Remove the item from the player's inventory
     * 4. Create the auction listing in the database
     *
     * @param player The player listing the item
     * @param price  The listing price
     */
    public void listItem(ServerPlayer player, double price) {
        // Validate price
        if (price < AuctionEntry.MIN_LISTING_PRICE) {
            player.sendSystemMessage(TextUtil.error(
                "Minimum listing price is " + CurrencyUtil.format(AuctionEntry.MIN_LISTING_PRICE)));
            return;
        }
        if (price > AuctionEntry.MAX_LISTING_PRICE) {
            player.sendSystemMessage(TextUtil.error(
                "Maximum listing price is " + CurrencyUtil.format(AuctionEntry.MAX_LISTING_PRICE)));
            return;
        }

        // Check held item
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.isEmpty()) {
            player.sendSystemMessage(TextUtil.error("You must hold an item to list it!"));
            return;
        }

        // Prevent concurrent listings for the same player
        UUID playerId = player.getUUID();
        if (!pendingListings.add(playerId)) {
            player.sendSystemMessage(TextUtil.error("A listing is already in progress. Please wait."));
            return;
        }

        // Transaction hook veto (Solidus 2.1.0+): BEFORE the item leaves the
        // player's hand and before any fee is charged. A denial here is a
        // clean no-op - nothing has been touched yet.
        SolidusTransactionHook.Decision hookDecision = EconomyHooks.allow(hook ->
            hook.allowAuctionListing(playerId, player.getName().getString(), price));
        if (!hookDecision.allowed()) {
            pendingListings.remove(playerId);
            player.sendSystemMessage(TextUtil.error(hookDecision.reason()));
            return;
        }

        // Capture item details NOW, and SECURITY FIX (TOCTOU item duplication):
        // previously the item stayed in the player's hand while the fee deduction
        // and the database save ran asynchronously - a cheat client could deposit,
        // drop or spend the very stack being listed during that window and keep a
        // marketable copy. We now remove the stack from the hand SYNCHRONOUSLY
        // (before any async hop), and every failure path below returns it.
        String materialName = TextUtil.getMaterialName(heldItem);
        int quantity = heldItem.getCount();
        String itemNbt = serializeItemStack(heldItem);
        int selectedSlot = player.getInventory().getSelectedSlot();
        final ItemStack capturedStack = heldItem.copy();

        player.getInventory().setItem(selectedSlot, ItemStack.EMPTY);

        // Calculate listing fee
        double listingFee = AuctionEntry.calculateListingFee(price);
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        // TOCTOU Fix: Skip the separate getBalance check and go directly to
        // subtractBalance, which atomically checks-and-deducts on the
        // single-threaded economy executor. This eliminates the window where
        // the player could spend money elsewhere between check and deduction.
        balanceManager.subtractBalance(player, listingFee).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                if (newBalance < 0) {
                    // Insufficient funds or failure - no money was deducted.
                    // Give the captured item straight back; nothing was listed.
                    returnCapturedItem(player, capturedStack);
                    pendingListings.remove(playerId);
                    player.sendSystemMessage(TextUtil.error(
                        "Listing fee is " + CurrencyUtil.format(listingFee) +
                        ". Insufficient funds!"));
                    return;
                }

                // Create the listing
                AuctionEntry entry = AuctionEntry.create(
                    player.getUUID(), player.getName().getString(),
                    materialName, quantity, itemNbt, price
                );

                // Save to database first; the physical item was already taken out
                // of the player's hand, so on save failure we refund the fee AND
                // return the item to keep both sides of the transaction intact.
                saveListing(entry).thenAccept(success -> {
                    player.level().getServer().execute(() -> {
                        try {
                            if (success) {
                                // Listing saved successfully.

                                // Log transaction
                                economyEngine.getTransactionLog().log(
                                    TransactionLog.Type.AUCTION_LIST,
                                    player.getUUID(), player.getName().getString(),
                                    null, null,
                                    listingFee, materialName, quantity,
                                    "Listed " + quantity + "x " + materialName + " for " + CurrencyUtil.format(price)
                                );

                                // Hook notification (Solidus 2.1.0+): listing fully settled.
                                EconomyHooks.notifyHooks(hook ->
                                    hook.afterAuctionListing(player.getUUID(), player.getName().getString(),
                                        price, listingFee));

                                player.sendSystemMessage(
                                    TextUtil.success("Item listed on the Auction House for ")
                                        .append(TextUtil.currency(CurrencyUtil.format(price)))
                                        .append(TextUtil.styled(" (Fee: " + CurrencyUtil.format(listingFee) + ")", ChatFormatting.GRAY))
                                );
                            } else {
                                // CRITICAL: Listing save failed after fee deduction - refund fee
                                SolidusMod.LOGGER.error("CRITICAL: Auction listing save failed for {}! Refunding fee and returning item.",
                                    player.getName().getString());
                                balanceManager.addBalance(player, listingFee).thenAccept(refundBalance -> {
                                    player.level().getServer().execute(() -> {
                                        if (refundBalance < 0) {
                                            SolidusMod.LOGGER.error(
                                                "CATASTROPHIC: Listing fee refund also failed for {}! Amount: {}",
                                                player.getName().getString(), listingFee);
                                            player.sendSystemMessage(TextUtil.error(
                                                "Critical error: listing fee refund failed. Please contact an admin."));
                                        } else {
                                            player.sendSystemMessage(TextUtil.error(
                                                "Failed to list item. Listing fee has been refunded."));
                                        }
                                        // In BOTH cases return the captured item - it never
                                        // became part of a successful listing.
                                        returnCapturedItem(player, capturedStack);
                                    });
                                });
                            }
                        } finally {
                            pendingListings.remove(playerId);
                        }
                    });
                });
            });
        });
    }

    /**
     * Returns a previously captured listing item to the player's inventory.
     * If the inventory is full, the item is dropped at the player's feet so it
     * can never be destroyed.
     */
    private void returnCapturedItem(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    // -- Purchase Operations -------------------------------

    /**
     * Processes a purchase from the auction house with RACE CONDITION PROTECTION.
     *
     * Anti-Dupe Strategy (Single-Threaded Executor Queue):
     * All auction database mutations are processed through a single-threaded
     * executor, which guarantees that only one transaction runs at a time.
     * When two players try to buy the same listing simultaneously:
     * - The first player's request enters the executor and marks the listing as SOLD
     * - The second player's request enters the queue and finds the listing already sold
     * - No database locking (BEGIN IMMEDIATE) is needed - the executor IS the lock
     *
     * No Server Thread Blocking:
     * The entire purchase flow uses CompletableFuture chaining instead of .join().
     * The server tick thread is never blocked, preventing lag spikes.
     *
     * @param buyer      The player purchasing the item
     * @param listingId  The UUID of the listing to purchase
     */
    public void purchaseItem(ServerPlayer buyer, UUID listingId) {
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        // Step 1: On the auction executor, verify and mark as SOLD atomically
        CompletableFuture.supplyAsync(() -> {
            try {
                // Check if the listing is still active
                String selectSql = "SELECT * FROM auction_listings WHERE listing_id = ? AND status = 0";
                AuctionEntry entry = null;
                try (PreparedStatement ps = persistentConnection.prepareStatement(selectSql)) {
                    ps.setString(1, listingId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            entry = mapResultSetToEntry(rs);
                        }
                    }
                }

                if (entry == null) {
                    return "SOLD_OUT";
                }

                if (entry.isExpired()) {
                    return "EXPIRED";
                }

                // Check if buyer is the seller
                if (entry.sellerUuid().equals(buyer.getUUID())) {
                    return "OWN_ITEM";
                }

                // Transaction hook veto (Solidus 2.1.0+): runs on the auction
                // executor BEFORE the listing is marked SOLD. A denial here
                // leaves the listing untouched and fully buyable by others.
                // (entry is reassigned above, so capture it into a final local
                // for lambda use.)
                final AuctionEntry vetoEntry = entry;
                SolidusTransactionHook.Decision hookDecision = EconomyHooks.allow(hook ->
                    hook.allowAuctionPurchase(buyer.getUUID(), buyer.getName().getString(), vetoEntry.price()));
                if (!hookDecision.allowed()) {
                    return "HOOK_VETOED:" + (hookDecision.reason() != null
                        ? hookDecision.reason() : "Transaction denied.");
                }

                // Mark as SOLD IMMEDIATELY (single-threaded executor guarantees
                // no other thread can interfere - this IS the atomic operation)
                String updateSql = "UPDATE auction_listings SET status = 1 WHERE listing_id = ?";
                try (PreparedStatement ps = persistentConnection.prepareStatement(updateSql)) {
                    ps.setString(1, listingId.toString());
                    ps.executeUpdate();
                }

                return entry;

            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Auction purchase DB error for listing: {}", listingId, e);
                return "DB_ERROR";
            }
        }, asyncExecutor).thenAccept(result -> {
            // Step 2: Back on the server thread - process the financial side
            // Balance reads are instant (in-memory cache), no cross-executor blocking
            buyer.level().getServer().execute(() -> {
                if (result instanceof String errorMsg) {
                    if (errorMsg.startsWith("HOOK_VETOED:")) {
                        // Denied by a transaction hook - the listing was never
                        // marked SOLD, nothing to roll back.
                        buyer.sendSystemMessage(TextUtil.error(errorMsg.substring("HOOK_VETOED:".length())));
                        return;
                    }
                    switch (errorMsg) {
                        case "SOLD_OUT" -> buyer.sendSystemMessage(
                            TextUtil.error("This item has already been sold!"));
                        case "EXPIRED" -> buyer.sendSystemMessage(
                            TextUtil.error("This listing has expired!"));
                        case "OWN_ITEM" -> buyer.sendSystemMessage(
                            TextUtil.error("You cannot buy your own listing!"));
                        case "DB_ERROR" -> buyer.sendSystemMessage(
                            TextUtil.error("Transaction error. Please try again."));
                    }
                    return;
                }

                AuctionEntry entry = (AuctionEntry) result;

                // TOCTOU Fix: Skip the separate getBalance check and go directly
                // to subtractBalance, which atomically checks-and-deducts on the
                // single-threaded economy executor. This eliminates the window where
                // the buyer could spend money elsewhere between the check and deduction.
                balanceManager.subtractBalance(buyer, entry.price()).thenAccept(newBuyerBalance -> {
                    buyer.level().getServer().execute(() -> {
                        if (newBuyerBalance < 0) {
                            // Buyer couldn't afford - no money was deducted, rollback the sale
                            SolidusMod.LOGGER.warn("Auction buyer {} cannot afford listing {}. Rolling back.",
                                buyer.getName().getString(), entry.listingId());
                            markAsUnsold(entry.listingId());
                            buyer.sendSystemMessage(TextUtil.error("Insufficient funds!"));
                            return;
                        }

                        // Pay the seller FIRST - if this fails, we must rollback the buyer
                        balanceManager.addBalance(entry.sellerUuid(), entry.sellerName(), entry.price())
                            .thenAccept(newSellerBalance -> {
                                buyer.level().getServer().execute(() -> {
                                    if (newSellerBalance < 0) {
                                        // CRITICAL: Seller payment failed after buyer was charged!
                                        // Rollback: refund the buyer and mark listing as unsold
                                        SolidusMod.LOGGER.error(
                                            "CRITICAL: Failed to pay auction seller {} for listing {}. Rolling back buyer.",
                                            entry.sellerName(), entry.listingId());
                                        markAsUnsold(entry.listingId());
                                        balanceManager.addBalance(buyer, entry.price()).thenAccept(refundBalance -> {
                                            buyer.level().getServer().execute(() -> {
                                                if (refundBalance < 0) {
                                                    SolidusMod.LOGGER.error(
                                                        "CATASTROPHIC: Buyer refund also failed after seller payment failure! Buyer: {}, Amount: {}",
                                                        buyer.getName().getString(), entry.price());
                                                    buyer.sendSystemMessage(TextUtil.error(
                                                        "Critical error: refund failed. Please contact an admin immediately."));
                                                } else {
                                                    buyer.sendSystemMessage(TextUtil.error(
                                                        "Transaction failed - seller could not be paid. Your money has been refunded."));
                                                }
                                            });
                                        });
                                        return;
                                    }

                                    // Seller paid successfully - give item to buyer
                                    ItemStack purchasedItem = deserializeItemStack(entry.itemNbt(), entry.materialName(), entry.quantity());
                                    if (!buyer.getInventory().add(purchasedItem)) {
                                        buyer.drop(purchasedItem, false);
                                        buyer.sendSystemMessage(TextUtil.warning("Inventory full! Item dropped at your feet."));
                                    }

                                    // Log transaction for buyer
                                    economyEngine.getTransactionLog().log(
                                        TransactionLog.Type.AUCTION_BOUGHT,
                                        buyer.getUUID(), buyer.getName().getString(),
                                        entry.sellerUuid(), entry.sellerName(),
                                        entry.price(), entry.materialName(), entry.quantity(),
                                        "Bought " + entry.quantity() + "x " + entry.materialName() + " from " + entry.sellerName()
                                    );

                                    // Log transaction for seller (may be offline)
                                    economyEngine.getTransactionLog().log(
                                        TransactionLog.Type.AUCTION_SOLD,
                                        entry.sellerUuid(), entry.sellerName(),
                                        buyer.getUUID(), buyer.getName().getString(),
                                        entry.price(), entry.materialName(), entry.quantity(),
                                        "Sold " + entry.quantity() + "x " + entry.materialName() + " to " + buyer.getName().getString()
                                    );

                                    // Queue notification for seller (delivers immediately if online)
                                    economyEngine.getTransactionLog().queueNotification(
                                        entry.sellerUuid(),
                                        "Your auction item " + entry.quantity() + "x " + entry.materialName() +
                                            " was purchased by " + buyer.getName().getString() + " for " +
                                            CurrencyUtil.format(entry.price()),
                                        buyer.level().getServer()
                                    );

                                    // Housekeeping: the sale is fully settled (money moved both
                                    // ways, item delivered). The SOLD row is only deleted AFTER
                                    // it has been durably archived into auction_sold_history
                                    // (buyer attributed), so a failed TransactionLog insert can
                                    // no longer erase the only record of the sale. If archiving
                                    // fails, the SOLD row is kept and the startup sweep retries.
                                    settleSoldListing(entry, buyer);

                                    // Success notification
                                    buyer.sendSystemMessage(
                                        TextUtil.success("Purchased " + entry.quantity() + "x " + entry.materialName() + " for ")
                                            .append(TextUtil.currency(CurrencyUtil.format(entry.price())))
                                            .append(TextUtil.styled(" | New balance: ", ChatFormatting.GRAY))
                                            .append(TextUtil.currency(CurrencyUtil.format(newBuyerBalance)))
                                    );
                                });
                            });
                    });
                });
            });
        });
    }

    // -- Browse Operations ---------------------------------

    /**
     * Opens the auction house GUI for a player.
     */
    public void openAuction(ServerPlayer player) {
        if (!initialized) {
            player.sendSystemMessage(TextUtil.error("Auction House is not available yet."));
            return;
        }
        AuctionGUI.openAuction(player, this);
    }

    /**
     * Gets all active (unsold, unexpired) listings.
     *
     * @return CompletableFuture with a list of active AuctionEntry objects
     */
    public CompletableFuture<List<AuctionEntry>> getActiveListings() {
        return getActiveListings(SortOrder.NEWEST);
    }

    /**
     * Gets all active listings with a specified sort order.
     *
     * @param sortOrder The sort order to apply (NEWEST, PRICE_LOW, PRICE_HIGH, MATERIAL)
     * @return CompletableFuture with a list of active AuctionEntry objects
     */
    public CompletableFuture<List<AuctionEntry>> getActiveListings(SortOrder sortOrder) {
        if (!initialized) {
            // Pre-initialize call (e.g. a GUI refresh racing startup): report an
            // empty market instead of blowing up on the null connection.
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<AuctionEntry> entries = new ArrayList<>();
            String orderBy = switch (sortOrder) {
                case NEWEST -> "listed_timestamp DESC";
                case PRICE_LOW -> "price ASC";
                case PRICE_HIGH -> "price DESC";
                case MATERIAL -> "material_name ASC, price ASC";
            };
            String sql = "SELECT * FROM auction_listings WHERE status = 0 AND expire_timestamp > ? ORDER BY " + orderBy;
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to get active auction listings", e);
            }
            return entries;
        }, asyncExecutor);
    }

    /** Sort order options for auction listings */
    public enum SortOrder {
        NEWEST("Newest First"),
        PRICE_LOW("Price: Low to High"),
        PRICE_HIGH("Price: High to Low"),
        MATERIAL("Material A-Z");

        private final String displayName;

        SortOrder(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * Gets all active listings by a specific seller.
     */
    public CompletableFuture<List<AuctionEntry>> getListingsBySeller(UUID sellerUuid) {
        if (!initialized) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<AuctionEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM auction_listings WHERE seller_uuid = ? AND status = 0 AND expire_timestamp > ? ORDER BY listed_timestamp DESC";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, sellerUuid.toString());
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to get seller listings", e);
            }
            return entries;
        }, asyncExecutor);
    }

    // -- Expiration Processing -----------------------------

    /**
     * Processes expired listings and returns items to their sellers.
     * Should be called periodically by a scheduled task.
     *
     * <p>SECURITY REWRITE (cross-flow TOCTOU dupe): the old flow marked rows
     * status=2 asynchronously, then handed out items and deleted rows in LATER
     * hops on other threads. In the window between mark and delete the row was
     * visible to {@code /ah collect} (which matches every status=2 row of the
     * seller), letting a seller withdraw the SAME expired item twice. Two
     * overlapping sweep runs also reused stale SELECT snapshots, double-handing
     * items even without /ah collect.</p>
     *
     * <p>Every claim is now ATOMIC inside one serialized executor step:
     * online sellers get their row DELETED here (direct hand-out follows on the
     * main thread); offline sellers get a condition-guarded flip to status=2,
     * recoverable only via /ah collect - which itself claims-by-delete BEFORE
     * paying out. Each statement carries "AND status = 0" so no row can ever be
     * claimed twice, even by a stale overlapping sweep.</p>
     */
    public void processExpiredListings() {
        MinecraftServer currentServer = this.server;
        if (currentServer == null) return;

        // Snapshot the online sellers ONCE on the calling thread. This method runs
        // from END_SERVER_TICK (main server thread), so reading the player list here
        // is safe; the DB-worker below must NOT touch live player lists.
        Set<UUID> onlineSellers = new HashSet<>();
        for (ServerPlayer online : currentServer.getPlayerList().getPlayers()) {
            onlineSellers.add(online.getUUID());
        }

        CompletableFuture.supplyAsync(() -> {
            List<AuctionEntry> expired = new ArrayList<>();
            String sql = "SELECT * FROM auction_listings WHERE status = 0 AND expire_timestamp <= ?";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expired.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to query expired listings", e);
                return new ArrayList<AuctionEntry>();
            }

            List<AuctionEntry> toReturnDirectly = new ArrayList<>();
            for (AuctionEntry entry : expired) {
                boolean sellerOnline = onlineSellers.contains(entry.sellerUuid());

                boolean claimed = sellerOnline
                    ? claimExpiredRowForReturn(entry.listingId())
                    : markExpiredRowCollectible(entry.listingId());
                if (!claimed) continue; // another flow already settled this row

                economyEngine.getTransactionLog().log(
                    TransactionLog.Type.AUCTION_EXPIRED,
                    entry.sellerUuid(), entry.sellerName(),
                    null, null,
                    0, entry.materialName(), entry.quantity(),
                    sellerOnline
                        ? "Listing expired - item returned to seller"
                        : "Listing expired - waiting for /ah collect");

                SolidusMod.LOGGER.info("Expired listing settled: seller={}, item={}, directReturn={}",
                    entry.sellerName(), entry.materialName(), sellerOnline);

                if (sellerOnline) {
                    toReturnDirectly.add(entry);
                }
            }
            return toReturnDirectly;
        }, asyncExecutor).thenAccept(toReturn -> {
            if (toReturn.isEmpty()) return;

            currentServer.execute(() -> {
                for (AuctionEntry entry : toReturn) {
                    ServerPlayer seller = currentServer.getPlayerList().getPlayer(entry.sellerUuid());
                    if (seller == null) continue; // disconnected in the meantime - harmless

                    ItemStack returnedItem = deserializeItemStack(
                        entry.itemNbt(), entry.materialName(), entry.quantity());
                    if (!seller.getInventory().add(returnedItem)) {
                        seller.drop(returnedItem, false);
                    }
                    seller.sendSystemMessage(
                        TextUtil.warning("Your auction listing for " + entry.quantity() + "x " +
                            entry.materialName() + " has expired and been returned to you."));
                }
            });
        });
    }

    // -- Internal Helpers ----------------------------------

    private String getDatabasePath() {
        return com.solidus.util.ConfigManager.getConfigDir().toAbsolutePath() + "/" + DATABASE_NAME;
    }

    private CompletableFuture<Boolean> saveListing(AuctionEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO auction_listings
                (listing_id, seller_uuid, seller_name, material_name, quantity,
                 item_nbt, price, listed_timestamp, expire_timestamp, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, entry.listingId().toString());
                ps.setString(2, entry.sellerUuid().toString());
                ps.setString(3, entry.sellerName());
                ps.setString(4, entry.materialName());
                ps.setInt(5, entry.quantity());
                ps.setString(6, entry.itemNbt());
                ps.setDouble(7, entry.price());
                ps.setLong(8, entry.listedTimestamp());
                ps.setLong(9, entry.expireTimestamp());
                ps.setInt(10, entry.status().ordinal()); // 0=ACTIVE, 1=SOLD, 2=EXPIRED
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to save auction listing", e);
                return false;
            }
        }, asyncExecutor);
    }

    private void markAsUnsold(UUID listingId) {
        CompletableFuture.runAsync(() -> {
            String sql = "UPDATE auction_listings SET status = 0 WHERE listing_id = ?";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, listingId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to mark listing as unsold: {}", listingId, e);
            }
        }, asyncExecutor);
    }

    /**
     * Claims an ACTIVE expired row for DIRECT return to an online seller by
     * atomically archiving + deleting it inside the caller's serialized
     * executor step. The exactly-once semantics are still enforced by the
     * "AND status = 0" guard - now inside the same transaction as the archive.
     * Returns true only when THIS call performed the claim.
     */
    private boolean claimExpiredRowForReturn(UUID listingId) {
        return archiveAndDeleteListing(persistentConnection, listingId,
            SETTLED_EXPIRED_RETURN, null, null, System.currentTimeMillis(), SolidusMod.LOGGER);
    }

    /**
     * Claims an ACTIVE expired row for an OFFLINE seller by flipping it to
     * status=2 (collectible later via /ah collect) inside the caller's
     * serialized executor step.
     * Returns true only when THIS call performed the claim (exactly-once
     * semantics enforced by the "AND status = 0" guard).
     */
    private boolean markExpiredRowCollectible(UUID listingId) {
        String sql = "UPDATE auction_listings SET status = 2 WHERE listing_id = ? AND status = 0";
        try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            SolidusMod.LOGGER.error("Failed to mark listing {} collectible: {}", listingId, e.getMessage());
            return false;
        }
    }

    private AuctionEntry mapResultSetToEntry(ResultSet rs) throws SQLException {
        int statusCode = rs.getInt("status");
        ListingStatus status = ListingStatus.fromCode(statusCode);
        return new AuctionEntry(
            UUID.fromString(rs.getString("listing_id")),
            UUID.fromString(rs.getString("seller_uuid")),
            rs.getString("seller_name"),
            rs.getString("material_name"),
            rs.getInt("quantity"),
            rs.getString("item_nbt"),
            rs.getDouble("price"),
            rs.getLong("listed_timestamp"),
            rs.getLong("expire_timestamp"),
            status
        );
    }

    private String serializeItemStack(ItemStack stack) {
        // Serialize the item to a string representation using NBT
        // Uses injected MinecraftServer instance instead of static getServer()
        try {
            MinecraftServer currentServer = this.server;
            if (currentServer != null) {
                var registryAccess = currentServer.registryAccess();
                // 26.1.x: ItemStack.save() was removed. Use ItemStack.CODEC with the
                // registry access's ops to serialize to a Tag, then toString().
                var dataResult = ItemStack.CODEC.encodeStart(
                    net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registryAccess),
                    stack
                );
                var jsonElement = dataResult.result().orElse(null);
                if (jsonElement != null) {
                    return jsonElement.toString();
                }
            }
        } catch (Exception e) {
            SolidusMod.LOGGER.warn("Item NBT serialization failed, using material fallback: {}", e.getMessage());
        }
        // Fallback to simple material name serialization using registry path
        return TextUtil.getMaterialName(stack);
    }

    private ItemStack deserializeItemStack(String itemNbt, String materialName, int quantity) {
        try {
            // Try to deserialize from NBT (JSON-encoded)
            if (itemNbt != null && (itemNbt.startsWith("{") || itemNbt.startsWith("["))) {
                MinecraftServer currentServer = this.server;
                if (currentServer != null) {
                    var registryAccess = currentServer.registryAccess();
                    // 26.1.x: parse via Gson -> JsonElement, then ItemStack.CODEC
                    var jsonElement = com.google.gson.JsonParser.parseString(itemNbt);
                    var dataResult = ItemStack.CODEC.parse(
                        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registryAccess),
                        jsonElement
                    );
                    var parsed = dataResult.result().orElse(ItemStack.EMPTY);
                    if (!parsed.isEmpty()) return parsed;
                }
            }
        } catch (Exception e) {
            SolidusMod.LOGGER.warn("Failed to deserialize item NBT, falling back to material: {}", materialName);
        }

        // Fallback: create item from material name
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.tryParse(materialName.toLowerCase()))
                .map(net.minecraft.core.Holder::value).orElse(null);
            if (item == null) return ItemStack.EMPTY;
            return new ItemStack(item, quantity);
        } catch (Exception e) {
            SolidusMod.LOGGER.error("Failed to create item from material: {}", materialName);
            return ItemStack.EMPTY;
        }
    }

    // -- Collect & Cancel Operations ----------------------

    /**
     * Collects all expired and uncollected items for a seller.
     *
     * When a listing expires (status=EXPIRED) and the seller was offline,
     * the item remains in the database marked as EXPIRED. This method
     * retrieves those items and returns them to the seller's inventory.
     *
     * After collecting, the listings are deleted from the database
     * to prevent re-collection.
     *
     * Flow (fully async - no server thread blocking):
     * 1. Query all EXPIRED listings for this seller
     * 2. In the SAME serialized executor step, claim them by deleting the rows
     *    (prevents crash-window double collection)
     * 3. On server thread: give items to the player
     *
     * @param player The seller collecting their expired items
     */
    public void collectExpiredItems(ServerPlayer player) {
        CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM auction_listings WHERE seller_uuid = ? AND status = 2";
            List<AuctionEntry> expired = new ArrayList<>();
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, player.getUUID().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expired.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to query expired listings for collection", e);
            }

            // SECURITY FIX (crash-window dupe): previously the rows were deleted only
            // AFTER the items had been handed to the player (async, fire-and-forget).
            // A server crash between hand-out and delete let the seller claim the same
            // items a second time after restart. The rows are now claimed (deleted)
            // inside this same serialized executor step - BEFORE any items are handed
            // out. Hand-over itself is guaranteed by the inventory-add/drop fallback.
            if (!expired.isEmpty()) {
                // Atomic archive+delete: rows land in auction_sold_history in the
                // same transaction that removes them - no hand-out without evidence.
                int claimed = archiveAndDeleteCollectibles(persistentConnection,
                    player.getUUID(), System.currentTimeMillis(), SolidusMod.LOGGER);
                if (claimed != expired.size()) {
                    // Archive+delete failed or claimed fewer rows than selected -
                    // do NOT hand out items, or they could be re-claimed later.
                    // Return an empty list so the retry is safe.
                    return new ArrayList<AuctionEntry>();
                }
                SolidusMod.LOGGER.info("Claimed {} collected listings for seller: {}",
                    claimed, player.getUUID());
            }
            return expired;
        }, asyncExecutor).thenAccept(expired -> {
            player.level().getServer().execute(() -> {
                if (expired.isEmpty()) {
                    player.sendSystemMessage(TextUtil.styled(
                        "You have no expired items to collect.", ChatFormatting.GRAY));
                    return;
                }

                int collected = 0;
                for (AuctionEntry entry : expired) {
                    ItemStack item = deserializeItemStack(
                        entry.itemNbt(), entry.materialName(), entry.quantity());
                    if (!player.getInventory().add(item)) {
                        player.drop(item, false);
                    }
                    collected++;
                }

                player.sendSystemMessage(
                    TextUtil.success("Collected " + collected + " expired item(s)!"));
            });
        });
    }

    /**
     * Cancels an active listing and returns the item to the seller.
     *
     * Only the seller can cancel their own listing, and only if it is
     * still ACTIVE (not yet purchased or expired).
     *
     * Flow (fully async - no server thread blocking):
     * 1. On auction executor: verify listing exists and belongs to the seller
     * 2. Mark the listing as EXPIRED (status=2) atomically
     * 3. On server thread: return the item to the seller's inventory
     *
     * @param player  The seller canceling their listing
     * @param listingId The UUID of the listing to cancel
     */
    public void cancelListing(ServerPlayer player, UUID listingId) {
        CompletableFuture.supplyAsync(() -> {
            try {
                // Check if the listing is active and belongs to this seller
                String selectSql = "SELECT * FROM auction_listings WHERE listing_id = ? AND status = 0";
                AuctionEntry entry = null;
                try (PreparedStatement ps = persistentConnection.prepareStatement(selectSql)) {
                    ps.setString(1, listingId.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            entry = mapResultSetToEntry(rs);
                        }
                    }
                }

                if (entry == null) {
                    return "NOT_FOUND";
                }

                // Verify ownership
                if (!entry.sellerUuid().equals(player.getUUID())) {
                    return "NOT_OWNER";
                }

                // SECURITY FIX (guaranteed dupe): cancellation used to flip the row
                // to status=2 EXPIRED and leave it in the database while the item was
                // handed back. The leftover row matched /ah collect's query (every
                // status=2 row of this seller), so collecting afterwards paid out a
                // SECOND copy of the exact same stack - trivially repeatable, no
                // timing required. The row is now atomically ARCHIVED + DELETED
                // (claimed) inside this same serialized executor step; the hand-out
                // follows on the server thread.
                if (!archiveAndDeleteListing(persistentConnection, listingId,
                        SETTLED_CANCELLED, null, null, System.currentTimeMillis(), SolidusMod.LOGGER)) {
                    // A buy, expiry sweep or another cancel consumed the listing
                    // between our SELECT and DELETE - nothing was changed.
                    return "NOT_FOUND";
                }

                return entry;

            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Auction cancel DB error for listing: {}", listingId, e);
                return "DB_ERROR";
            }
        }, asyncExecutor).thenAccept(result -> {
            player.level().getServer().execute(() -> {
                if (result instanceof String errorMsg) {
                    switch (errorMsg) {
                        case "NOT_FOUND" -> player.sendSystemMessage(
                            TextUtil.error("Listing not found or already sold!"));
                        case "NOT_OWNER" -> player.sendSystemMessage(
                            TextUtil.error("You can only cancel your own listings!"));
                        case "DB_ERROR" -> player.sendSystemMessage(
                            TextUtil.error("Transaction error. Please try again."));
                    }
                    return;
                }

                AuctionEntry entry = (AuctionEntry) result;

                // Return item to the seller
                ItemStack item = deserializeItemStack(
                    entry.itemNbt(), entry.materialName(), entry.quantity());
                if (!player.getInventory().add(item)) {
                    player.drop(item, false);
                    player.sendSystemMessage(TextUtil.warning("Inventory full! Item dropped at your feet."));
                }

                player.sendSystemMessage(
                    TextUtil.success("Cancelled listing for " + entry.quantity() + "x " + entry.materialName()));
            });
        });
    }

    /**
     * Gets all expired (uncollected) listings for a specific seller.
     * Used to show the count of collectible items in /ah collect.
     *
     * @param sellerUuid The seller's UUID
     * @return CompletableFuture with a list of EXPIRED AuctionEntry objects
     */
    public CompletableFuture<List<AuctionEntry>> getExpiredListingsBySeller(UUID sellerUuid) {
        if (!initialized) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        return CompletableFuture.supplyAsync(() -> {
            List<AuctionEntry> entries = new ArrayList<>();
            String sql = "SELECT * FROM auction_listings WHERE seller_uuid = ? AND status = 2 ORDER BY expire_timestamp DESC";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, sellerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to get expired listings for seller", e);
            }
            return entries;
        }, asyncExecutor);
    }

    // -- Settlement History & Startup Recovery ------------

    /**
     * Archives a fully-settled sale, then removes the SOLD row.
     *
     * <p>Ordering is evidence-first: the listing row is copied into
     * {@code auction_sold_history} (with the buyer attributed) and only deleted
     * once that INSERT is confirmed durable. If the archive write fails, the
     * SOLD row is deliberately kept - it becomes input for the startup sweep
     * ({@link #recoverOrphanedSoldRows(Connection, Connection, String, Logger)})
     * which retries the settlement on the next restart. This closes the audit
     * gap where a failed TransactionLog insert left a completed sale with no
     * record at all.</p>
     */
    private void settleSoldListing(AuctionEntry entry, ServerPlayer buyer) {
        final UUID buyerUuid = buyer.getUUID();
        final String buyerName = buyer.getName().getString();
        final long settledAt = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            boolean archived = insertSoldHistory(persistentConnection, entry,
                buyerUuid, buyerName, SETTLED_SOLD, settledAt, SolidusMod.LOGGER);
            if (!archived) {
                SolidusMod.LOGGER.error(
                    "Settled listing {} could NOT be archived - keeping SOLD row for startup recovery",
                    entry.listingId());
                return;
            }
            try (PreparedStatement ps = persistentConnection.prepareStatement(
                    "DELETE FROM auction_listings WHERE listing_id = ? AND status = 1")) {
                ps.setString(1, entry.listingId().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to delete archived sold listing: {}", entry.listingId(), e);
            }
        }, asyncExecutor);
    }

    /**
     * Startup reconciliation for orphaned {@code status = 1} rows.
     *
     * <p>A row left in SOLD state by a crash is either:</p>
     * <ul>
     *   <li>a completed sale whose archive/delete step never ran -
     *       TransactionLog then contains a matching AUCTION_SOLD entry, so the
     *       row is archived (buyer attributed from the log) and removed; or</li>
     *   <li>a purchase that never finished - no payment moved, no log entry
     *       exists, and the item is safely re-listed ({@code status = 0}).</li>
     * </ul>
     *
     * <p>Runs synchronously on the init thread before the server accepts
     * connections; the auction executor has no competing work yet.</p>
     */
    private void recoverOrphanedSoldRowsFromEconomy() {
        // The auction database and the economy database are separate SQLite
        // files, so matching against TransactionLog needs a second connection.
        String economyUrl = "jdbc:sqlite:" + com.solidus.util.ConfigManager.getConfigDir().toAbsolutePath()
            + "/" + com.solidus.economy.SQLiteStorage.DATABASE_NAME;
        try (Connection economyConn = DriverManager.getConnection(economyUrl)) {
            int[] result = recoverOrphanedSoldRows(persistentConnection, economyConn,
                TransactionLog.Type.AUCTION_SOLD.code(), SolidusMod.LOGGER);
            if (result[0] > 0 || result[1] > 0) {
                SolidusMod.LOGGER.info("Auction startup recovery: {} orphaned SOLD row(s) archived, {} re-listed",
                    result[0], result[1]);
            }
        } catch (SQLException e) {
            SolidusMod.LOGGER.error(
                "Auction startup recovery failed - orphaned SOLD rows left for the next restart", e);
        }
    }

    /** Archive insert column list shared by the settlement helpers. */
    private static final String HISTORY_INSERT_COLUMNS =
        "(listing_id, seller_uuid, seller_name, material_name, quantity, price,"
        + " buyer_uuid, buyer_name, listed_timestamp, settled_timestamp, settled_reason)";

    /**
     * Inserts one settled row into {@code auction_sold_history}.
     * Package-private and Minecraft-free so the settlement tests can drive it
     * directly against a plain JDBC connection.
     *
     * @return true when the history row is (now) present - a duplicate insert
     *         is tolerated as success so callers can proceed with the delete
     */
    static boolean insertSoldHistory(Connection conn, AuctionEntry entry,
                                     UUID buyerUuid, String buyerName,
                                     String reason, long settledTimestamp, org.slf4j.Logger log) {
        String sql = "INSERT OR IGNORE INTO auction_sold_history " + HISTORY_INSERT_COLUMNS
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.listingId().toString());
            ps.setString(2, entry.sellerUuid().toString());
            ps.setString(3, entry.sellerName());
            ps.setString(4, entry.materialName());
            ps.setInt(5, entry.quantity());
            ps.setDouble(6, entry.price());
            ps.setString(7, buyerUuid != null ? buyerUuid.toString() : null);
            ps.setString(8, buyerName);
            ps.setLong(9, entry.listedTimestamp());
            ps.setLong(10, settledTimestamp);
            ps.setString(11, reason);
            if (ps.executeUpdate() == 1) {
                return true;
            }
            // OR IGNORE swallowed a duplicate - success only if the row exists
            try (PreparedStatement chk = conn.prepareStatement(
                    "SELECT 1 FROM auction_sold_history WHERE listing_id = ?")) {
                chk.setString(1, entry.listingId().toString());
                try (ResultSet rs = chk.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            log.error("Failed to archive settled listing {}: {}", entry.listingId(), e.getMessage());
            return false;
        }
    }

    /**
     * Atomically archives one ACTIVE listing and deletes it. The exactly-once
     * claim guard ({@code AND status = 0}) now lives inside the same
     * transaction as the archive, so a claim can never leave "row removed but
     * nothing archived". Used by the expiry-return and cancel flows.
     *
     * @return true when THIS call claimed (archived + deleted) the row
     */
    static boolean archiveAndDeleteListing(Connection conn, UUID listingId, String reason,
                                           UUID buyerUuid, String buyerName,
                                           long settledTimestamp, org.slf4j.Logger log) {
        String insertSql = "INSERT INTO auction_sold_history " + HISTORY_INSERT_COLUMNS
            + " SELECT listing_id, seller_uuid, seller_name, material_name, quantity, price,"
            + " ?, ?, listed_timestamp, ?, ?"
            + " FROM auction_listings WHERE listing_id = ? AND status = 0";
        String deleteSql = "DELETE FROM auction_listings WHERE listing_id = ? AND status = 0";
        try {
            conn.createStatement().execute("BEGIN IMMEDIATE");
            boolean claimed;
            try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                ins.setString(1, buyerUuid != null ? buyerUuid.toString() : null);
                ins.setString(2, buyerName);
                ins.setLong(3, settledTimestamp);
                ins.setString(4, reason);
                ins.setString(5, listingId.toString());
                claimed = ins.executeUpdate() > 0;
            }
            if (claimed) {
                try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                    del.setString(1, listingId.toString());
                    del.executeUpdate();
                }
            }
            conn.createStatement().execute("COMMIT");
            return claimed;
        } catch (SQLException e) {
            tryRollback(conn);
            log.error("Failed to archive-and-delete listing {}: {}", listingId, e.getMessage());
            return false;
        }
    }

    /**
     * Atomically archives and deletes every collectible ({@code status = 2})
     * row of one seller - used by /ah collect. Same evidence guarantee as
     * {@link #archiveAndDeleteListing}: rows and their archived copies are
     * committed together or not at all.
     *
     * @return the number of rows claimed, or -1 on failure (the caller must
     *         not hand out items then - a retry stays safe)
     */
    static int archiveAndDeleteCollectibles(Connection conn, UUID sellerUuid,
                                            long settledTimestamp, org.slf4j.Logger log) {
        String insertSql = "INSERT INTO auction_sold_history " + HISTORY_INSERT_COLUMNS
            + " SELECT listing_id, seller_uuid, seller_name, material_name, quantity, price,"
            + " NULL, NULL, listed_timestamp, ?, ?"
            + " FROM auction_listings WHERE seller_uuid = ? AND status = 2";
        String deleteSql = "DELETE FROM auction_listings WHERE seller_uuid = ? AND status = 2";
        try {
            conn.createStatement().execute("BEGIN IMMEDIATE");
            int claimed;
            try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                ins.setLong(1, settledTimestamp);
                ins.setString(2, SETTLED_EXPIRED_COLLECT);
                ins.setString(3, sellerUuid.toString());
                claimed = ins.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                del.setString(1, sellerUuid.toString());
                claimed = del.executeUpdate();
            }
            conn.createStatement().execute("COMMIT");
            return claimed;
        } catch (SQLException e) {
            tryRollback(conn);
            log.error("Failed to archive-and-delete collectibles for seller {}: {}",
                sellerUuid, e.getMessage());
            return -1;
        }
    }

    /**
     * Startup sweep for orphaned {@code status = 1} rows (see the instance
     * javadoc above). Package-private and Minecraft-free for tests.
     *
     * <p>Log matching is best-effort: AUCTION_SOLD entries are paired to orphan
     * rows by (seller, amount, material, quantity, listed_timestamp), oldest
     * to oldest, each log row consumed once. Money and item flows are never
     * altered by this sweep - it only restores bookkeeping.</p>
     *
     * @return {@code int[]{archived, relisted}}
     */
    static int[] recoverOrphanedSoldRows(Connection auctionConn, Connection economyConn,
                                         String soldTypeCode, org.slf4j.Logger log) {
        // The economy side may not exist yet (fresh install) - nothing to match
        try (Statement st = economyConn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'transaction_log'")) {
            if (!rs.next()) {
                log.warn("Startup sweep skipped: economy database has no transaction_log table yet");
                return new int[]{0, 0};
            }
        } catch (SQLException e) {
            log.error("Startup sweep skipped: cannot inspect economy database: {}", e.getMessage());
            return new int[]{0, 0};
        }

        List<AuctionEntry> orphans = new ArrayList<>();
        String selectSql = "SELECT * FROM auction_listings WHERE status = 1 ORDER BY listed_timestamp ASC";
        try (PreparedStatement ps = auctionConn.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                orphans.add(new AuctionEntry(
                    UUID.fromString(rs.getString("listing_id")),
                    UUID.fromString(rs.getString("seller_uuid")),
                    rs.getString("seller_name"),
                    rs.getString("material_name"),
                    rs.getInt("quantity"),
                    rs.getString("item_nbt"),
                    rs.getDouble("price"),
                    rs.getLong("listed_timestamp"),
                    rs.getLong("expire_timestamp"),
                    ListingStatus.SOLD));
            }
        } catch (SQLException e) {
            log.error("Startup sweep aborted: cannot read orphaned SOLD rows: {}", e.getMessage());
            return new int[]{0, 0};
        }

        Set<Long> consumedLogRows = new HashSet<>();
        int archived = 0;
        int relisted = 0;
        for (AuctionEntry entry : orphans) {
            Long matchedRowid = null;
            UUID buyerUuid = null;
            String buyerName = null;
            long logTimestamp = 0L;

            StringBuilder notIn = new StringBuilder();
            if (!consumedLogRows.isEmpty()) {
                notIn.append(" AND rowid NOT IN (");
                boolean first = true;
                for (Long ignored : consumedLogRows) {
                    if (!first) notIn.append(",");
                    notIn.append("?");
                    first = false;
                }
                notIn.append(")");
            }
            String matchSql = "SELECT rowid, target_uuid, target_name, timestamp FROM transaction_log"
                + " WHERE type = ? AND player_uuid = ? AND amount = ? AND item_material = ?"
                + " AND item_quantity = ? AND timestamp >= ?" + notIn
                + " ORDER BY timestamp ASC LIMIT 1";
            try (PreparedStatement ps = economyConn.prepareStatement(matchSql)) {
                int idx = 1;
                ps.setString(idx++, soldTypeCode);
                ps.setString(idx++, entry.sellerUuid().toString());
                ps.setDouble(idx++, entry.price());
                ps.setString(idx++, entry.materialName());
                ps.setInt(idx++, entry.quantity());
                ps.setLong(idx++, entry.listedTimestamp());
                for (Long used : consumedLogRows) {
                    ps.setLong(idx++, used);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        matchedRowid = rs.getLong("rowid");
                        String targetUuid = rs.getString("target_uuid");
                        buyerUuid = targetUuid != null ? UUID.fromString(targetUuid) : null;
                        buyerName = rs.getString("target_name");
                        logTimestamp = rs.getLong("timestamp");
                    }
                }
            } catch (SQLException ex) {
                log.error("Startup sweep: log match failed for listing {} - leaving SOLD row in place: {}",
                    entry.listingId(), ex.getMessage());
                continue;
            }

            if (matchedRowid != null && insertSoldHistory(auctionConn, entry,
                    buyerUuid, buyerName, SETTLED_SOLD, logTimestamp, log)) {
                try (PreparedStatement del = auctionConn.prepareStatement(
                        "DELETE FROM auction_listings WHERE listing_id = ? AND status = 1")) {
                    del.setString(1, entry.listingId().toString());
                    del.executeUpdate();
                } catch (SQLException ex) {
                    log.error("Startup sweep: archived but could not delete listing {}: {}",
                        entry.listingId(), ex.getMessage());
                    continue; // duplicate insert on the next run is idempotent
                }
                consumedLogRows.add(matchedRowid);
                archived++;
            } else {
                // No matching sale log: the purchase never completed (no payment
                // had moved) - safely put the item back on the market.
                try (PreparedStatement upd = auctionConn.prepareStatement(
                        "UPDATE auction_listings SET status = 0 WHERE listing_id = ? AND status = 1")) {
                    upd.setString(1, entry.listingId().toString());
                    upd.executeUpdate();
                } catch (SQLException ex) {
                    log.error("Startup sweep: could not re-list orphaned row {}: {}",
                        entry.listingId(), ex.getMessage());
                    continue;
                }
                relisted++;
                log.warn("Startup sweep: re-listed orphaned SOLD row {} - no matching sale in TransactionLog",
                    entry.listingId());
            }
        }
        return new int[]{archived, relisted};
    }

    /** Best-effort transaction rollback that never throws. */
    private static void tryRollback(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // no open transaction or the connection is already broken
        }
    }

    // -- Getters -------------------------------------------

    public EconomyEngine getEconomyEngine() {
        return economyEngine;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
