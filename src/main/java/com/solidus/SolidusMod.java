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
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.TransactionLog;
import com.solidus.shop.ShopManager;
import com.solidus.auction.AuctionManager;
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

/**
 * Solidus - Advanced Server-Side Economy & Commerce Engine
 * Copyright (c) 2026 MOHD_Gs. All rights reserved.
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

    /** Tick counter for periodic tasks (auction expiration check every 5 minutes) */
    private static long tickCounter = 0;
    private static final int AUCTION_EXPIRY_CHECK_INTERVAL = 6000; // 5 minutes (6000 ticks)

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

        auctionManager = new AuctionManager(economyEngine);
        auctionManager.initialize();

        packetHandler = new PacketHandler(shopManager, auctionManager, rateLimiter);
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
            TransactionsCommand.register(dispatcher, economyEngine);
        });

        // Register server shutdown hook for clean database closure
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Solidus is shutting down...");
            auctionManager.shutdown();
            economyEngine.shutdown();
            rateLimiter.clear();
            LOGGER.info("Solidus shutdown complete. All data saved.");
        });

        // Inject MinecraftServer instance into AuctionManager
        // Required because MinecraftServer.getServer() is NOT available in Fabric
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            auctionManager.setServer(server);

            LOGGER.info("Solidus: MinecraftServer instance injected into AuctionManager.");
        });

        // Register periodic tick handler for auction expiration checks
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= AUCTION_EXPIRY_CHECK_INTERVAL) {
                tickCounter = 0;
                auctionManager.processExpiredListings();
            }
        });

        // Deliver pending offline notifications when a player joins
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            TransactionLog transactionLog = economyEngine.getTransactionLog();
            if (transactionLog != null) {
                transactionLog.deliverPendingNotifications(handler.getPlayer());
            }
        });

        LOGGER.info("Solidus initialized successfully. Economy engine online.");
    }

    // -- Static Accessors ----------------------------------

    public static EconomyEngine getEconomyEngine() {
        return economyEngine;
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
}
