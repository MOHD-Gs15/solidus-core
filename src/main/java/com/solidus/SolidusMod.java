package com.solidus;

import com.solidus.api.SolidusAPI;
import com.solidus.api.PermissionConfig;
import com.solidus.commands.BalanceCommand;
import com.solidus.commands.BaltopCommand;
import com.solidus.commands.PayCommand;
import com.solidus.commands.SellCommand;
import com.solidus.commands.ShopCommand;
import com.solidus.commands.AuctionCommand;
import com.solidus.commands.TransactionsCommand;
import com.solidus.commands.SolidusAdminCommand;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.MySqlStorage;
import com.solidus.economy.RedisLayer;
import com.solidus.economy.SupplyIntegrity;
import com.solidus.economy.StorageConfig;
import com.solidus.economy.TransactionLog;
import com.solidus.chat.ChatPrompts;
import com.solidus.shop.ShopManager;
import com.solidus.auction.AuctionManager;
import com.solidus.trade.TradeManager;
import com.solidus.commands.TradeCommand;
import com.solidus.networking.PacketHandler;
import com.solidus.networking.RateLimiter;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Solidus - Advanced Server-Side Economy & Commerce Engine
 * Copyright (c) 2026 MOHD-Gs15. All rights reserved.
 *
 * Main entry point for the dedicated server mod.
 *
 * Architecture: 100% Server-Side Only
 * - No custom textures, no custom models, no client-side dependencies
 * - Players connect using completely un-modded Vanilla Minecraft Client
 * - All UI operates via packet manipulation (Virtual Chest GUI)
 */
public class SolidusMod implements DedicatedServerModInitializer {

    public static final String MOD_ID = "solidus";
    public static final String MOD_NAME = "Solidus";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static EconomyEngine economyEngine;
    private static ShopManager shopManager;
    private static AuctionManager auctionManager;
    private static PacketHandler packetHandler;
    private static RateLimiter rateLimiter;
    private static ChatPrompts chatPrompts;
    private static TradeManager tradeManager;
    /** Optional Redis coordination layer (2.2.1) — null unless enabled in storage.json. */
    private static volatile RedisLayer redisLayer;
    /** Injected via SERVER_STARTED — MinecraftServer.getServer() is unavailable in Fabric. */
    private static volatile MinecraftServer activeServer;

    /** Tick counter for periodic tasks (auction expiration check every 5 minutes) */
    private static long tickCounter = 0;
    private static final int AUCTION_EXPIRY_CHECK_INTERVAL = 6000; // 5 minutes (6000 ticks)

    /** Supply-integrity service (2.2.4, DB scaling plan §7) — null until wired. */
    private static volatile SupplyIntegrity supplyIntegrity;
    /** Single-thread executor for the integrity check (never blocks the tick). */
    private static volatile ExecutorService integrityExecutor;
    /** Ticks since the last 60s notification sweep (2.2.4). */
    private static long notificationSweepCounter = 0;
    /** Ticks accumulated toward the next integrity cycle (2.2.4). */
    private static long integrityTickCounter = 0;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Solidus Economy & Commerce Engine is initializing...");

        // Initialize permission system (must be before command registration)
        PermissionConfig.initialize(
            net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
        );

        // Initialize core subsystems in dependency order
        rateLimiter = new RateLimiter();

        economyEngine = new EconomyEngine();
        economyEngine.initialize();

        // Initialize the public API immediately after the engine (mod init time).
        // COMPATIBILITY FIX: this used to run at SERVER_STARTED, but companion
        // mods (Solidus Governance) detect the API and register their enforcement
        // hooks at SERVER_STARTING - which fires BEFORE SERVER_STARTED - so
        // SolidusAPI.getInstance() was still null during every registration
        // attempt and Governance silently fell back to standalone mode (no
        // limits, no freezes, no taxes inside Core flows). Initializing here
        // guarantees the API exists before any server lifecycle event, while
        // remaining idempotent (a duplicate call is safely ignored).
        SolidusAPI.initialize(economyEngine);

        shopManager = new ShopManager(economyEngine);
        shopManager.loadConfiguration();

        // ── Optional Redis layer (2.2.1) — MUST be attached BEFORE the auction
        // manager below so its first reads can use the L2 cache.
        startRedisLayer(economyEngine);

        // Auction house: on MySQL the store lives on the SHARED database (one
        // network-wide market); on SQLite it keeps the per-server file.
        if (economyEngine.isMysqlMode()) {
            auctionManager = new AuctionManager(economyEngine, economyEngine.auctionConnectionSource());
        } else {
            auctionManager = new AuctionManager(economyEngine);
        }
        auctionManager.initialize();

        // Chat prompt service (bid amounts, trade money input) - must exist
        // before any GUI that can open a prompt.
        chatPrompts = new ChatPrompts();

        // ── Supply integrity service (2.2.4, DB scaling plan §7): audits
        // supply conservation + escrow consistency on a schedule. Reads its
        // ledger windows through the SAME connection source the ledger uses
        // (shared file / shared pool), so the audit is consistent with the
        // money it inspects.
        startSupplyIntegrity(economyEngine);

        // Direct player-to-player trade system (/trade).
        tradeManager = new TradeManager(economyEngine, chatPrompts);

        packetHandler = new PacketHandler(shopManager, auctionManager, tradeManager, rateLimiter);
        packetHandler.register();

        // Register all server-side commands
        BalanceManager balanceManager = economyEngine.getBalanceManager();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            BalanceCommand.register(dispatcher, balanceManager);
            PayCommand.register(dispatcher, economyEngine);
            BaltopCommand.register(dispatcher, balanceManager);
            ShopCommand.register(dispatcher, shopManager);
            SellCommand.register(dispatcher, shopManager);
            AuctionCommand.register(dispatcher, auctionManager);
            TradeCommand.register(dispatcher, tradeManager);
            TransactionsCommand.register(dispatcher, economyEngine);
            SolidusAdminCommand.register(dispatcher, economyEngine, auctionManager, supplyIntegrity);
        });

        // Register server shutdown hook for clean database closure
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Solidus is shutting down...");
            tradeManager.shutdown();
            auctionManager.shutdown();
            economyEngine.shutdown();
            closeRedisLayer();
            shutdownSupplyIntegrity();
            rateLimiter.clear();
            LOGGER.info("Solidus shutdown complete. All data saved.");
        });

        // Inject MinecraftServer instance into AuctionManager
        // Required because MinecraftServer.getServer() is NOT available in Fabric
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            auctionManager.setServer(server);
            tradeManager.setServer(server);
            activeServer = server;

            LOGGER.info("Solidus: MinecraftServer instance injected into AuctionManager + TradeManager.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> activeServer = null);

        // Register periodic tick handler for auction expiration checks
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= AUCTION_EXPIRY_CHECK_INTERVAL) {
                tickCounter = 0;
                auctionManager.processExpiredListings();
                // Same cadence: reap idle trade sessions (items returned).
                tradeManager.reapIdleSessions();
            }

            // ── 2.2.4: network-aware notification sweep (60s). Closes the
            // no-Redis delivery gap: a notification queued anywhere on the
            // network reaches a player hosted HERE within one sweep, even
            // without the Redis events bus (JOIN delivery still covers logins).
            notificationSweepCounter++;
            if (notificationSweepCounter >= 1200) { // 60s × 20 tps
                notificationSweepCounter = 0;
                sweepPendingNotifications();
            }

            // ── 2.2.4: scheduled supply-integrity cycle (elected servers only).
            StorageConfig.IntegritySettings integrity =
                economyEngine != null ? economyEngine.integritySettings() : null;
            if (integrity != null && integrity.enabled() && integrity.elected()) {
                integrityTickCounter++;
                long intervalTicks = Math.max(1, integrity.intervalMinutes()) * 1200L;
                if (integrityTickCounter >= intervalTicks) {
                    integrityTickCounter = 0;
                    runIntegrityCycle();
                }
            }
        });

        // Deliver pending offline notifications when a player joins
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            TransactionLog transactionLog = economyEngine.getTransactionLog();
            if (transactionLog != null) {
                transactionLog.deliverPendingNotifications(handler.getPlayer());
            }
        });

        // Cancel any open trade session + chat prompt when a player disconnects
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            tradeManager.handleDisconnect(handler.getPlayer());
            chatPrompts.cancelPrompt(handler.getPlayer().getUUID());
        });

        LOGGER.info("Solidus initialized successfully. Economy engine online.");
    }

    // -- Static Accessors ----------------------------------

    public static EconomyEngine getEconomyEngine() {
        return economyEngine;
    }

    public static RedisLayer getRedisLayer() {
        return redisLayer;
    }

    /**
     * Starts the optional Redis layer (2.2.1) when storage.json enables it and
     * wires: L2 invalidation callbacks into the MySQL storage, instant network
     * notification delivery on the events channel, and the notification
     * broadcaster. A startup failure here degrades to MySQL-only mode (never
     * blocks the server).
     */
    private static void startRedisLayer(EconomyEngine engine) {
        try {
            RedisLayer layer = RedisLayer.start(engine.redisSettings());
            if (layer == null) {
                LOGGER.info("Solidus Redis layer disabled (storage.json redis.enabled=false) — database-only mode.");
                return;
            }
            redisLayer = layer;

            if (engine.getStorage() instanceof MySqlStorage mysql) {
                mysql.setRedisLayer(layer);
                // Other servers' mutations invalidate our local L1/L2 copies.
                layer.onBalanceInvalidation(mysql::dropLocalBalanceCache);
            } else {
                LOGGER.info("Solidus Redis layer active with SQLite storage: only the events bus is used.");
            }

            // Network-aware notification delivery: when a queued notification
            // for an OFFLINE player arrives and that player is hosted HERE,
            // deliver instantly and delete the durable row (no relogin wait).
            TransactionLog log = engine.getTransactionLog();
            if (log != null) {
                log.setNotificationBroadcaster(layer::publishPlayerEvent);
            }
            layer.onPlayerEvent((playerUuid, message) -> {
                MinecraftServer currentServer = activeServer;
                if (currentServer == null) return;
                var online = currentServer.getPlayerList().getPlayer(playerUuid);
                if (online == null) return; // not hosted here — durable row stays
                TransactionLog txLog = engine.getTransactionLog();
                if (txLog == null) return;
                currentServer.execute(() -> {
                    var stillOnline = currentServer.getPlayerList().getPlayer(playerUuid);
                    if (stillOnline == null) return;
                    stillOnline.sendSystemMessage(com.solidus.util.TextUtil.styled(
                        "[Solidus] " + message, net.minecraft.ChatFormatting.AQUA));
                    txLog.deletePendingNotificationsByMessage(playerUuid, message);
                });
            });
        } catch (RuntimeException e) {
            LOGGER.error("Solidus Redis layer failed to start — continuing in database-only mode.", e);
            redisLayer = null;
        }
    }

    private static void closeRedisLayer() {
        RedisLayer layer = redisLayer;
        redisLayer = null;
        if (layer != null) {
            try {
                layer.close();
            } catch (Exception e) {
                LOGGER.warn("Redis layer close error (ignored): {}", e.getMessage());
            }
        }
    }

    // -- Supply integrity (2.2.4, DB scaling plan §7) --------

    /**
     * Builds the supply-integrity checker against the ACTIVE storage's
     * transaction-log connection source. A failure here is logged and the
     * server continues without the checker — integrity reporting must never
     * take the economy down.
     */
    private static void startSupplyIntegrity(EconomyEngine engine) {
        try {
            TransactionLog log = engine.getTransactionLog();
            if (log == null) {
                LOGGER.warn("Solidus supply integrity: no transaction log — checker disabled.");
                return;
            }
            ThreadFactory threadFactory = new ThreadFactory() {
                private final AtomicInteger index = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "Solidus-Integrity-" + index.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            };
            ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
            integrityExecutor = executor;
            StorageConfig.IntegritySettings settings = engine.integritySettings();
            supplyIntegrity = new SupplyIntegrity(
                log,
                engine.isMysqlMode() ? TransactionLog.Dialect.MYSQL : TransactionLog.Dialect.SQLITE,
                executor,
                settings.tolerance());
            LOGGER.info("Solidus supply integrity checker armed (interval {} min, tolerance {}, elected: {}).",
                settings.intervalMinutes(), settings.tolerance(), settings.elected());
        } catch (Exception e) {
            LOGGER.error("Solidus supply integrity failed to start — continuing without it.", e);
            supplyIntegrity = null;
        }
    }

    private static void shutdownSupplyIntegrity() {
        supplyIntegrity = null;
        ExecutorService executor = integrityExecutor;
        integrityExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** One scheduled integrity cycle: escrow consistency + supply replay. */
    private static void runIntegrityCycle() {
        AuctionManager auctions = auctionManager;
        if (auctions != null) {
            auctions.checkEscrowConsistency();
        }
        SupplyIntegrity integrity = supplyIntegrity;
        if (integrity == null) {
            return;
        }
        integrity.runOnce().thenAccept(report -> {
            if (report.bootstrapped()) {
                return; // baseline establishment is already logged inside runOnce
            }
            if (report.withinTolerance()) {
                LOGGER.info("Supply integrity: OK (unexplained {} — within tolerance).",
                    report.unexplained());
            } else {
                LOGGER.warn("Supply integrity report:\n{}", report.summary());
            }
        });
    }

    /**
     * The no-Redis network delivery sweep (2.2.4): players holding pending
     * notifications anywhere on the shared database who are hosted HERE get
     * their rows delivered now (exact-row delivery + delete).
     */
    private static void sweepPendingNotifications() {
        TransactionLog txLog = economyEngine != null ? economyEngine.getTransactionLog() : null;
        MinecraftServer server = activeServer;
        if (txLog == null || server == null) {
            return;
        }
        txLog.playersWithPendingNotifications().thenAccept(ids -> {
            if (ids.isEmpty()) {
                return;
            }
            MinecraftServer current = activeServer;
            if (current == null) {
                return;
            }
            current.execute(() -> {
                for (UUID playerUuid : ids) {
                    var online = current.getPlayerList().getPlayer(playerUuid);
                    if (online != null) {
                        txLog.deliverPendingNotifications(online);
                    }
                }
            });
        });
    }

    public static ShopManager getShopManager() {
        return shopManager;
    }

    public static AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public static PacketHandler getPacketHandler() {
        return packetHandler;
    }

    public static RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public static ChatPrompts getChatPrompts() {
        return chatPrompts;
    }

    public static TradeManager getTradeManager() {
        return tradeManager;
    }
}
