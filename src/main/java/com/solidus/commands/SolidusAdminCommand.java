package com.solidus.commands;

import com.solidus.admin.AdminOps;
import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.auction.AuctionManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.StorageConfig;
import com.solidus.economy.StorageMigrator;
import com.solidus.economy.SupplyIntegrity;
import com.solidus.util.ConfigManager;
import com.solidus.util.TextUtil;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * /solidus-admin — storage administration + console-driven economy testing.
 *
 * <p><b>Permission:</b> {@code solidus.command.admin} (default OP 4 — server
 * owner). The console always passes the permission check.</p>
 *
 * <h2>Storage migration (2.2.1)</h2>
 * <p>{@code /solidus-admin storage migrate [--force] [--batch N]} copies the
 * SQLite data set into the configured MySQL target, verifies counts + money
 * supply to the cent, and writes a report file. The cutover itself is a
 * config flip + restart. Re-runs converge (idempotent writes).</p>
 *
 * <h2>Console test harness (2.2.2)</h2>
 * <p>The {@code account} / {@code money} / {@code pay-as} / {@code bid-as} /
 * {@code auction} / {@code audit} / {@code diag} branches drive a FULL
 * economy lifecycle from the console (or Rcon) without any real player:
 * dummy accounts are materialized under the vanilla offline-mode UUID
 * derivation, money moves through the exact same atomic primitives the
 * player commands use, and every admin adjustment lands in the ledger with
 * the ADMIN_* types. Intended for multi-server race testing - see
 * docs/CONSOLE_TESTING.md for scripted scenarios.</p>
 */
public class SolidusAdminCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolidusAdminCommand.class);
    private static final int DEFAULT_BATCH = 500;

    /** One migration at a time, server-wide. */
    private static final AtomicBoolean MIGRATION_RUNNING = new AtomicBoolean(false);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                EconomyEngine economyEngine,
                                AuctionManager auctionManager,
                                SupplyIntegrity supplyIntegrity) {
        AdminOps admin = new AdminOps(economyEngine, auctionManager, SolidusAdminCommand::resolveItem,
            supplyIntegrity);

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("solidus-admin")
            .requires(PermissionChecker.require(SolidusPermissions.ADMIN, 4));

        // -- Storage migration (2.2.1) ------------------------
        root.then(Commands.literal("storage")
            .then(Commands.literal("migrate")
                .executes(context -> executeMigrate(context.getSource(), economyEngine,
                    DEFAULT_BATCH, false, List.of()))
                .then(Commands.argument("flags", StringArgumentType.greedyString())
                    .executes(context -> {
                        String raw = StringArgumentType.getString(context, "flags");
                        List<String> tokens = new ArrayList<>(List.of(raw.trim().split("\\s+")));
                        int batch = DEFAULT_BATCH;
                        boolean force = false;
                        List<String> unknown = new ArrayList<>();
                        for (int i = 0; i < tokens.size(); i++) {
                            String token = tokens.get(i);
                            switch (token) {
                                case "--force" -> force = true;
                                case "--batch" -> {
                                    if (i + 1 < tokens.size()) {
                                        try {
                                            batch = Integer.parseInt(tokens.get(++i));
                                        } catch (NumberFormatException e) {
                                            context.getSource().sendFailure(TextUtil.error(
                                                "--batch expects a number, got: " + tokens.get(i)));
                                            return 0;
                                        }
                                    }
                                }
                                default -> unknown.add(token);
                            }
                        }
                        if (!unknown.isEmpty()) {
                            context.getSource().sendFailure(TextUtil.error(
                                "Unknown flag(s): " + String.join(", ", unknown)
                                    + " — supported: --force, --batch <n>"));
                            return 0;
                        }
                        return executeMigrate(context.getSource(), economyEngine, batch, force, tokens);
                    })
                )
            )
        );

        // -- Account lifecycle (2.2.2) -------------------------
        root.then(Commands.literal("account")
            .then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> report(context.getSource(),
                        admin.createAccount(StringArgumentType.getString(context, "name"), null)))
                    .then(Commands.argument("balance", DoubleArgumentType.doubleArg(0))
                        .executes(context -> report(context.getSource(),
                            admin.createAccount(StringArgumentType.getString(context, "name"),
                                DoubleArgumentType.getDouble(context, "balance")))))))
            .then(Commands.literal("balance")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> report(context.getSource(),
                        admin.accountBalance(StringArgumentType.getString(context, "name"))))))
            .then(Commands.literal("list")
                .executes(context -> report(context.getSource(), admin.listAccounts(1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                    .executes(context -> report(context.getSource(),
                        admin.listAccounts(IntegerArgumentType.getInteger(context, "page"))))))
        );

        // -- Money primitives (2.2.2) ---------------------------
        root.then(Commands.literal("money")
            .then(Commands.literal("give")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> report(context.getSource(),
                            admin.give(context.getSource().getTextName(),
                                StringArgumentType.getString(context, "name"),
                                DoubleArgumentType.getDouble(context, "amount")))))))
            .then(Commands.literal("set")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
                        .executes(context -> report(context.getSource(),
                            admin.set(context.getSource().getTextName(),
                                StringArgumentType.getString(context, "name"),
                                DoubleArgumentType.getDouble(context, "amount")))))))
            .then(Commands.literal("take")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> report(context.getSource(),
                            admin.take(context.getSource().getTextName(),
                                StringArgumentType.getString(context, "name"),
                                DoubleArgumentType.getDouble(context, "amount")))))))
        );

        // -- Real transfer between two accounts (2.2.2) ---------
        root.then(Commands.literal("pay-as")
            .then(Commands.argument("from", StringArgumentType.word())
                .then(Commands.argument("to", StringArgumentType.word())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> report(context.getSource(),
                            admin.payAs(StringArgumentType.getString(context, "from"),
                                StringArgumentType.getString(context, "to"),
                                DoubleArgumentType.getDouble(context, "amount")))))))
        );

        // -- Auction flows on dummy accounts (2.2.2) -------------
        root.then(Commands.literal("bid-as")
            .then(Commands.argument("bidder", StringArgumentType.word())
                .then(Commands.argument("listing_id", UuidArgument.uuid())
                    .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(context -> reportWithFeedback(context.getSource(),
                            admin.bidAs(StringArgumentType.getString(context, "bidder"),
                                UuidArgument.getUuid(context, "listing_id"),
                                DoubleArgumentType.getDouble(context, "amount"),
                                feedbackSink(context.getSource())))
                        )
                    )
                )
            )
        );

        root.then(Commands.literal("auction")
            .then(Commands.literal("create")
                .then(Commands.argument("seller", StringArgumentType.word())
                    .then(Commands.argument("item_id", StringArgumentType.word())
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                .executes(context -> reportWithFeedback(context.getSource(),
                                    admin.auctionCreate(
                                        StringArgumentType.getString(context, "seller"),
                                        StringArgumentType.getString(context, "item_id"),
                                        IntegerArgumentType.getInteger(context, "count"),
                                        DoubleArgumentType.getDouble(context, "price"),
                                        0,
                                        feedbackSink(context.getSource()))))
                                .then(Commands.argument("startbid", DoubleArgumentType.doubleArg(0.01))
                                    .executes(context -> reportWithFeedback(context.getSource(),
                                        admin.auctionCreate(
                                            StringArgumentType.getString(context, "seller"),
                                            StringArgumentType.getString(context, "item_id"),
                                            IntegerArgumentType.getInteger(context, "count"),
                                            DoubleArgumentType.getDouble(context, "price"),
                                            DoubleArgumentType.getDouble(context, "startbid"),
                                            feedbackSink(context.getSource())))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        );

        // -- Audit & diagnostics (2.2.2) --------------------------
        root.then(Commands.literal("audit")
            .executes(context -> reportLines(context.getSource(), admin.audit())));
        root.then(Commands.literal("diag")
            .executes(context -> reportLines(context.getSource(), admin.diag())));

        // -- Supply integrity (2.2.4, DB scaling plan §7) ----------
        root.then(Commands.literal("integrity")
            .then(Commands.literal("check")
                .executes(context -> reportLines(context.getSource(), admin.integrityCheck())))
            .then(Commands.literal("rebase")
                .executes(context -> report(context.getSource(), admin.integrityRebase()))));

        dispatcher.register(root);
    }

    // -- Async result plumbing ---------------------------------

    /** Reports one {@link AdminOps.OpResult} on the server thread. */
    private static int report(CommandSourceStack source, CompletableFuture<AdminOps.OpResult> future) {
        var server = source.getServer();
        future.thenAccept(result -> server.execute(() -> {
            if (result.success()) {
                source.sendSuccess(() -> TextUtil.success(result.message()), false);
            } else {
                source.sendFailure(TextUtil.error(result.message()));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    /** Reports a future whose granular outcome arrives via the feedback sink. */
    private static int reportWithFeedback(CommandSourceStack source,
                                          CompletableFuture<AdminOps.OpResult> future) {
        var server = source.getServer();
        future.thenAccept(result -> server.execute(() -> {
            if (!result.success()) {
                source.sendFailure(TextUtil.error(result.message()));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    /** Thread-safe feedback sink for the auction flows (marshals to server thread). */
    private static BiConsumer<Boolean, String> feedbackSink(CommandSourceStack source) {
        var server = source.getServer();
        return (isError, message) -> server.execute(() -> {
            if (isError) {
                source.sendFailure(TextUtil.error(message));
            } else {
                source.sendSuccess(() -> TextUtil.success(message), false);
            }
        });
    }

    /** Reports multi-line audit/diag output; "!!" lines render as errors. */
    private static int reportLines(CommandSourceStack source, CompletableFuture<List<String>> future) {
        var server = source.getServer();
        future.thenAccept(lines -> server.execute(() -> {
            for (String line : lines) {
                if (line.startsWith("!!")) {
                    source.sendFailure(TextUtil.error(line));
                } else {
                    source.sendSuccess(() -> TextUtil.plain(line), false);
                }
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Production item resolver: same registry idiom as the auction manager's
     * material fallback ("minecraft:diamond" or bare "diamond").
     */
    private static net.minecraft.world.item.ItemStack resolveItem(String itemId, int count) {
        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.tryParse(itemId.toLowerCase(Locale.ROOT)))
                .map(net.minecraft.core.Holder::value).orElse(null);
            return item == null ? null : new net.minecraft.world.item.ItemStack(item, count);
        } catch (Exception e) {
            LOGGER.warn("Admin item resolver failed for '{}': {}", itemId, e.getMessage());
            return null;
        }
    }

    // -- Storage migration (2.2.1, unchanged) --------------------

    private static int executeMigrate(CommandSourceStack source, EconomyEngine engine,
                                      int batch, boolean force, List<String> flags) {
        final int batchSize = Math.max(50, Math.min(5000, batch));

        // ── Preconditions (fail loudly, nothing touched) ──
        if (engine.isMysqlMode()) {
            source.sendFailure(TextUtil.error(
                "This server already runs on MySQL — there is nothing to migrate. "
                    + "The migrator only converts a live SQLite installation."));
            return 0;
        }
        StorageConfig.MySqlSettings target = StorageConfig.load().mysql();
        if (target == null) {
            source.sendFailure(TextUtil.error(
                "storage.json has no usable \"mysql\" block — configure host/port/database/user/password first "
                    + "(password via the SOLIDUS_DB_PASSWORD environment variable is recommended)."));
            return 0;
        }
        int online = source.getServer().getPlayerCount();
        if (online > 0 && !force) {
            source.sendFailure(TextUtil.error(
                online + " player(s) are online. Run during a maintenance window, or repeat with --force "
                    + "(a re-run always converges thanks to idempotent writes)."));
            return 0;
        }
        if (!MIGRATION_RUNNING.compareAndSet(false, true)) {
            source.sendFailure(TextUtil.error("A migration is already in progress."));
            return 0;
        }

        final Path sqliteDb = ConfigManager.getConfigDir().toAbsolutePath()
            .resolve(SQLiteStorage.DATABASE_NAME);
        final boolean forced = force;
        source.sendSuccess(() -> TextUtil.styled(
            "Migrating " + sqliteDb + " → " + target.host() + ":" + target.port() + "/" + target.database()
                + " (batch " + batchSize + (forced ? ", --force" : "") + ")...",
            net.minecraft.ChatFormatting.AQUA), true);
        LOGGER.info("Storage migration started by {} (batch={}, force={})",
            source.getTextName(), batchSize, forced);

        Thread worker = new Thread(() -> {
            try {
                StorageMigrator migrator = new StorageMigrator(sqliteDb, target);
                StorageMigrator.MigrationReport report = migrator.run(batchSize, LOGGER);
                MIGRATION_RUNNING.set(false);

                source.getServer().execute(() -> {
                    if (report.success()) {
                        source.sendSuccess(() -> TextUtil.success(
                            "Migration completed in " + report.durationMs() + " ms — supply "
                                + com.solidus.util.CurrencyUtil.format(report.sqliteSupply())
                                + " verified on both sides."), false);
                        for (String line : report.tableCounts()) {
                            source.sendSuccess(() -> TextUtil.styled("  " + line, net.minecraft.ChatFormatting.GRAY), false);
                        }
                    } else {
                        source.sendFailure(TextUtil.error(
                            "Migration FAILED after " + report.durationMs() + " ms — see issues below."));
                        for (String issue : report.issues()) {
                            source.sendFailure(TextUtil.error("  ! " + issue));
                        }
                        source.sendFailure(TextUtil.error(
                            "MySQL was NOT activated. The SQLite backend is untouched and stays authoritative."));
                    }
                    String file = report.reportFile();
                    if (file != null) {
                        source.sendSuccess(() -> TextUtil.styled(
                            "Report: " + file, net.minecraft.ChatFormatting.GRAY), false);
                    }
                    source.sendSuccess(() -> TextUtil.styled(
                        "Cutover: set \"type\": \"mysql\" in config/solidus/storage.json, then restart this server "
                            + "(and every server that shares the database).",
                        net.minecraft.ChatFormatting.YELLOW), false);
                });
            } catch (Exception e) {
                MIGRATION_RUNNING.set(false);
                LOGGER.error("Storage migration crashed", e);
                source.getServer().execute(() -> source.sendFailure(TextUtil.error(
                    "Migration crashed: " + e.getMessage() + " — check latest.log. SQLite stays authoritative.")));
            }
        }, "Solidus-Storage-Migrate");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }
}
