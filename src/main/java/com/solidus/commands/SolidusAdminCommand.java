package com.solidus.commands;

import com.solidus.api.PermissionChecker;
import com.solidus.api.SolidusPermissions;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.StorageConfig;
import com.solidus.economy.StorageMigrator;
import com.solidus.util.ConfigManager;
import com.solidus.util.TextUtil;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /solidus-admin — storage administration (2.2.1, DB scaling plan §8.2).
 *
 * <p><b>Permission:</b> {@code solidus.command.admin} (default OP 4 — server
 * owner). The migration drains nothing and locks nothing: it copies the
 * SQLite data set into the configured MySQL target while the local server
 * runs, verifies counts + money supply to the cent, and writes a report
 * file. The actual cutover is a config flip + restart, exactly as the plan
 * prescribes.</p>
 *
 * <p><b>Usage:</b> {@code /solidus-admin storage migrate [--force] [--batch N]}</p>
 * <ul>
 *   <li>{@code --force} — allow running with players online (recommended only
 *       during a maintenance window; the copy is read-consistent per batch but
 *       live writes during the copy can force a second re-run to converge).</li>
 *   <li>{@code --batch N} — rows per batch (default 500).</li>
 * </ul>
 *
 * <p>The command is re-runnable at any time: money/state tables are written
 * with ON DUPLICATE KEY UPDATE (latest wins) and append-only tables with
 * INSERT IGNORE — repeated runs converge without duplicating data.</p>
 */
public class SolidusAdminCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolidusAdminCommand.class);
    private static final int DEFAULT_BATCH = 500;

    /** One migration at a time, server-wide. */
    private static final AtomicBoolean MIGRATION_RUNNING = new AtomicBoolean(false);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, EconomyEngine economyEngine) {
        dispatcher.register(Commands.literal("solidus-admin")
            .requires(PermissionChecker.require(SolidusPermissions.ADMIN, 4))
            .then(Commands.literal("storage")
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
            )
        );
    }

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
