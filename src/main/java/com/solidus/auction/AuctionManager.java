package com.solidus.auction;

import com.solidus.SolidusMod;
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
    private static final String CREATE_TABLE_SQL = """
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
    private static final String CREATE_INDEX_SQL = """
        CREATE INDEX IF NOT EXISTS idx_active_listings
        ON auction_listings (status, expire_timestamp)
    """;

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
            }
            initialized = true;
            SolidusMod.LOGGER.info("Auction database initialized successfully.");
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

        // Calculate listing fee
        double listingFee = AuctionEntry.calculateListingFee(price);
        BalanceManager balanceManager = economyEngine.getBalanceManager();

        // Capture item details NOW (before any async hops) to prevent
        // the player swapping items in their hand during the async chain.
        // Use registry path name for reliable material matching - getItem().toString()
        // is unreliable (may include namespace prefixes or vary by mapping).
        String materialName = TextUtil.getMaterialName(heldItem);
        int quantity = heldItem.getCount();
        String itemNbt = serializeItemStack(heldItem);
        int selectedSlot = player.getInventory().getSelectedSlot();

        // TOCTOU Fix: Skip the separate getBalance check and go directly to
        // subtractBalance, which atomically checks-and-deducts on the
        // single-threaded economy executor. This eliminates the window where
        // the player could spend money elsewhere between check and deduction.
        balanceManager.subtractBalance(player, listingFee).thenAccept(newBalance -> {
            player.level().getServer().execute(() -> {
                try {
                    if (newBalance < 0) {
                        // Insufficient funds or failure - no money was deducted
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

                    // Save to database first, THEN remove item from player's hand
                    // This prevents item loss if the database save fails
                    saveListing(entry).thenAccept(success -> {
                        player.level().getServer().execute(() -> {
                            try {
                                if (success) {
                                    // Item saved successfully - now safe to remove from player
                                    player.getInventory().setItem(selectedSlot, ItemStack.EMPTY);

                                    // Log transaction
                                    economyEngine.getTransactionLog().log(
                                        TransactionLog.Type.AUCTION_LIST,
                                        player.getUUID(), player.getName().getString(),
                                        null, null,
                                        listingFee, materialName, quantity,
                                        "Listed " + quantity + "x " + materialName + " for " + CurrencyUtil.format(price)
                                    );

                                    player.sendSystemMessage(
                                        TextUtil.success("Item listed on the Auction House for ")
                                            .append(TextUtil.currency(CurrencyUtil.format(price)))
                                            .append(TextUtil.styled(" (Fee: " + CurrencyUtil.format(listingFee) + ")", ChatFormatting.GRAY))
                                    );
                                } else {
                                    // CRITICAL: Listing save failed after fee deduction - attempt refund
                                    SolidusMod.LOGGER.error("CRITICAL: Auction listing save failed for {}! Refunding fee.",
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
                                        });
                                    });
                                }
                            } finally {
                                pendingListings.remove(playerId);
                            }
                        });
                    });
                } finally {
                    // Release the pending guard for non-saveListing paths
                    // (saveListing path releases in its own finally block)
                    if (newBalance < 0) {
                        pendingListings.remove(playerId);
                    }
                }
            });
        });
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
     */
    public void processExpiredListings() {
        CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM auction_listings WHERE status = 0 AND expire_timestamp <= ?";
            List<AuctionEntry> expired = new ArrayList<>();
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expired.add(mapResultSetToEntry(rs));
                    }
                }
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to query expired listings", e);
            }
            return expired;
        }, asyncExecutor).thenAccept(expired -> {
            MinecraftServer currentServer = this.server;
            if (currentServer == null) return;

            for (AuctionEntry entry : expired) {
                // Mark expired listings as status=2 (EXPIRED) instead of leaving at status=0
                // This prevents them from reappearing in active listings
                markAsExpired(entry.listingId());

                // Return items to online sellers immediately
                currentServer.execute(() -> {
                    ServerPlayer seller = currentServer.getPlayerList().getPlayer(entry.sellerUuid());
                    if (seller != null) {
                        ItemStack returnedItem = deserializeItemStack(
                            entry.itemNbt(), entry.materialName(), entry.quantity());
                        if (!seller.getInventory().add(returnedItem)) {
                            seller.drop(returnedItem, false);
                        }
                        seller.sendSystemMessage(
                            TextUtil.warning("Your auction listing for " + entry.quantity() + "x " +
                                entry.materialName() + " has expired and been returned to you."));
                    }
                    // Offline sellers: items are marked as expired (status=2)
                    // A future /ah collect command could retrieve these
                });

                SolidusMod.LOGGER.info("Expired listing processed: seller={}, item={}",
                    entry.sellerName(), entry.materialName());
            }
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
     * Marks a listing as expired (status=2) to distinguish from active (0) and purchased (1).
     * Expired items can be retrieved by the seller via a future /ah collect command.
     */
    private void markAsExpired(UUID listingId) {
        CompletableFuture.runAsync(() -> {
            String sql = "UPDATE auction_listings SET status = 2 WHERE listing_id = ?";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, listingId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to mark listing as expired: {}", listingId, e);
            }
        }, asyncExecutor);
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
     * 2. On server thread: give items to the player
     * 3. Delete collected listings from the database
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

                // Delete collected listings from the database
                deleteCollectedListings(player.getUUID());

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

                // Mark as EXPIRED (status=2) to remove from active listings
                String updateSql = "UPDATE auction_listings SET status = 2 WHERE listing_id = ?";
                try (PreparedStatement ps = persistentConnection.prepareStatement(updateSql)) {
                    ps.setString(1, listingId.toString());
                    ps.executeUpdate();
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

    /**
     * Deletes all collected (EXPIRED) listings for a seller from the database.
     * Called after items have been successfully returned to the player.
     */
    private void deleteCollectedListings(UUID sellerUuid) {
        CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM auction_listings WHERE seller_uuid = ? AND status = 2";
            try (PreparedStatement ps = persistentConnection.prepareStatement(sql)) {
                ps.setString(1, sellerUuid.toString());
                int deleted = ps.executeUpdate();
                SolidusMod.LOGGER.info("Deleted {} collected listings for seller: {}", deleted, sellerUuid);
            } catch (SQLException e) {
                SolidusMod.LOGGER.error("Failed to delete collected listings for seller: {}", sellerUuid, e);
            }
        }, asyncExecutor);
    }

    // -- Getters -------------------------------------------

    public EconomyEngine getEconomyEngine() {
        return economyEngine;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
