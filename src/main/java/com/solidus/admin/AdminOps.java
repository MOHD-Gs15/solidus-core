package com.solidus.admin;

import com.solidus.auction.AuctionManager;
import com.solidus.economy.BalanceManager;
import com.solidus.economy.EconomyEngine;
import com.solidus.economy.EscrowAccount;
import com.solidus.economy.SQLiteStorage;
import com.solidus.economy.StorageBackend;
import com.solidus.economy.SupplyIntegrity;
import com.solidus.economy.TransactionLog;
import com.solidus.util.CurrencyUtil;

import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Admin/testing operations for the Solidus economy (2.2.2).
 *
 * <p>This service powers the {@code /solidus-admin} test subcommands. It is
 * deliberately PLAYER-AGNOSTIC: every operation addresses accounts purely by
 * UUID + name, so a whole economy lifecycle - create accounts, seed money,
 * pay between accounts, bid on auctions - can be driven from the SERVER
 * CONSOLE (or Rcon) without any real player ever joining. That makes it the
 * scripting surface for multi-server race testing.</p>
 *
 * <h2>Dummy account identity</h2>
 * Test accounts use the VANILLA offline-mode UUID derivation:
 * {@code UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))}.
 * Consequences (documented deliberately):
 * <ul>
 *   <li>On an OFFLINE-mode server, a real player joining with the same name
 *       resolves to the SAME UUID and therefore inherits the dummy account -
 *       useful for pick-up-where-you-left test scenarios, but operators must
 *       not leave test accounts funded on production servers.</li>
 *   <li>On an ONLINE-mode server the derivation never collides with real
 *       premium UUIDs, so dummies stay fully isolated.</li>
 * </ul>
 *
 * <h2>Money correctness</h2>
 * Every money path routes through the SAME primitives the player commands
 * use - {@link StorageBackend#addBalance}, {@link StorageBackend#subtractBalance},
 * {@link StorageBackend#setBalance} and {@link BalanceManager#transferOffline}
 * (atomic both-legs-or-neither, hooks included). Admin debits/credits are
 * always recorded in the ledger with the dedicated ADMIN_GIVE / ADMIN_SET /
 * ADMIN_TAKE types, so supply-conservation audits stay exact to the cent.
 *
 * <h2>Threading</h2>
 * All operations return {@link CompletableFuture}s and never block the caller.
 * Result messages are plain strings; the Brigadier layer is responsible for
 * rendering them with text components and dispatching onto the server thread.
 */
public final class AdminOps {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminOps.class);

    /** Minecraft username rule: 1-16 chars, letters/digits/underscore. */
    private static final String NAME_PATTERN = "^[A-Za-z0-9_]{1,16}$";

    /** Page size for account list / audit pagination reads. */
    private static final int PAGE_SIZE = 10;

    private final EconomyEngine engine;
    private final AuctionManager auctions;
    private final ItemResolver itemResolver;
    /** Supply-integrity service (2.2.4) — null in legacy unit-test wiring. */
    private final SupplyIntegrity integrity;

    /**
     * Resolves an item id string ("minecraft:diamond") into a stack of the
     * given size. The production wiring uses the built-in item registry with
     * the exact same idiom as {@code AuctionManager}'s material fallback.
     * Injectable so pure unit tests can exercise the paths around it.
     */
    @FunctionalInterface
    public interface ItemResolver {
        ItemStack resolve(String itemId, int count);
    }

    /** Generic admin operation result. */
    public record OpResult(boolean success, String message) {}

    /** Resolved account reference (UUID + canonical stored name). */
    public record AccountRef(UUID uuid, String name) {}

    public AdminOps(EconomyEngine engine, AuctionManager auctions, ItemResolver itemResolver) {
        this(engine, auctions, itemResolver, null);
    }

    /** Full wiring (2.2.4): includes the supply-integrity service. */
    public AdminOps(EconomyEngine engine, AuctionManager auctions, ItemResolver itemResolver,
                    SupplyIntegrity integrity) {
        this.engine = engine;
        this.auctions = auctions;
        this.itemResolver = itemResolver;
        this.integrity = integrity;
    }

    // -- Identity helpers ----------------------------------

    /**
     * The vanilla OFFLINE-mode UUID derivation for a player name. Test
     * accounts are materialized under this UUID so that on offline-mode
     * servers a real join with the same name picks the account up naturally.
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** True when the name obeys the Minecraft username rule. */
    public static boolean isValidAccountName(String name) {
        return name != null && name.matches(NAME_PATTERN);
    }

    /**
     * Resolves an account NAME to its UUID via the storage name cache
     * (case-insensitive; an exact-case match wins over case variants).
     * Returns null when the name is unknown - accounts must exist before
     * they can be used, keeping supply-conservation tests deterministic.
     */
    public AccountRef resolveAccount(String name) {
        if (name == null || name.isEmpty()) return null;
        Map<UUID, String> cache = engine.getStorage().getPlayerNameCache();

        // Exact-case match first.
        for (Map.Entry<UUID, String> e : cache.entrySet()) {
            if (e.getValue().equals(name)) {
                return new AccountRef(e.getKey(), e.getValue());
            }
        }
        // Case-insensitive fallback (deterministic by UUID ordering).
        String lower = name.toLowerCase(Locale.ROOT);
        UUID best = null;
        for (Map.Entry<UUID, String> e : cache.entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).equals(lower)
                    && (best == null || e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
            }
        }
        return best != null ? new AccountRef(best, cache.get(best)) : null;
    }

    // -- Account lifecycle ----------------------------------

    /**
     * Materializes an account for a player who has never joined. Created at
     * the configured starting balance unless an explicit initial balance is
     * provided (applied via a logged ADMIN_SET so the ledger stays complete).
     */
    public CompletableFuture<OpResult> createAccount(String name, Double initialBalance) {
        if (!isValidAccountName(name)) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Invalid account name '" + name + "' - use 1-16 chars [A-Za-z0-9_]."));
        }
        UUID uuid = offlineUuid(name);
        StorageBackend storage = engine.getStorage();

        if (storage.getPlayerNameCache().containsKey(uuid)) {
            return storage.getBalance(uuid, name).thenApply(bal -> new OpResult(false,
                "Account '" + name + "' already exists (balance " + CurrencyUtil.format(bal) + ")."));
        }

        // Initial balance validation before touching storage.
        if (initialBalance != null && !CurrencyUtil.isValidBalance(initialBalance)) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Initial balance out of range [0, " + CurrencyUtil.format(CurrencyUtil.MAX_BALANCE) + "]."));
        }

        // Materialize the row at the starting balance (same semantics as a
        // first join), then optionally apply the requested initial balance.
        return storage.getBalance(uuid, name).thenCompose(starting -> {
            if (initialBalance == null || Math.abs(initialBalance - starting) < 0.005) {
                return CompletableFuture.completedFuture(new OpResult(true,
                    "Account '" + name + "' created (uuid " + uuid + ", balance "
                        + CurrencyUtil.format(initialBalance != null ? initialBalance : starting) + ")."));
            }
            double rounded = CurrencyUtil.round(initialBalance);
            return storage.setBalance(uuid, name, rounded).thenApply(ok -> {
                if (!ok) {
                    return new OpResult(false, "Account row created but the initial balance was rejected.");
                }
                // 2.2.4: ADMIN_SET carries the SIGNED supply delta (final − old),
                // not the final balance — the supply-integrity checker replays
                // ledger rows as deltas, and a "final balance" value double-counts
                // the replaced balance as a burn.
                double delta = CurrencyUtil.round(rounded - starting);
                engine.getTransactionLog().log(TransactionLog.Type.ADMIN_SET,
                    uuid, name, null, null, delta, null, 0,
                    "Initial balance for test account '" + name + "' (delta "
                        + CurrencyUtil.format(delta) + ")");
                return new OpResult(true,
                    "Account '" + name + "' created (uuid " + uuid + ", balance "
                        + CurrencyUtil.format(rounded) + ").");
            });
        });
    }

    /** Shows one account's balance (console-readable). */
    public CompletableFuture<OpResult> accountBalance(String name) {
        AccountRef ref = resolveAccount(name);
        if (ref == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Unknown account '" + name + "'. Create it with /solidus-admin account create."));
        }
        return engine.getStorage().getBalance(ref.uuid(), ref.name())
            .thenApply(bal -> new OpResult(true,
                ref.name() + " (" + ref.uuid() + "): " + CurrencyUtil.format(bal)));
    }

    /** Lists accounts (highest balance first), one page of {@link #PAGE_SIZE}. */
    public CompletableFuture<OpResult> listAccounts(int page) {
        int p = Math.max(1, page);
        StorageBackend storage = engine.getStorage();
        return storage.getBalanceEntryCount().thenCompose(total -> {
            if (total == 0) {
                return CompletableFuture.completedFuture(
                    new OpResult(true, "No accounts exist yet."));
            }
            int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
            int safePage = Math.min(p, pages);
            int offset = (safePage - 1) * PAGE_SIZE;
            return storage.getTopBalances(PAGE_SIZE, offset).thenApply(rows -> {
                StringBuilder sb = new StringBuilder("Accounts page " + safePage + "/" + pages
                    + " (" + total + " total):");
                for (SQLiteStorage.BalanceEntry row : rows) {
                    sb.append("\n  #").append(row.rank()).append(" ")
                        .append(row.playerName()).append(": ")
                        .append(CurrencyUtil.format(row.balance()));
                }
                return new OpResult(true, sb.toString());
            });
        });
    }

    // -- Money primitives -----------------------------------

    /** Credits an account (ledger: ADMIN_GIVE). */
    public CompletableFuture<OpResult> give(String issuer, String name, double amount) {
        return adjust(issuer, name, amount, AdjustKind.GIVE);
    }

    /** Overwrites an account balance (ledger: ADMIN_SET). */
    public CompletableFuture<OpResult> set(String issuer, String name, double amount) {
        return adjust(issuer, name, amount, AdjustKind.SET);
    }

    /** Debits an account; rejects insufficient funds (ledger: ADMIN_TAKE). */
    public CompletableFuture<OpResult> take(String issuer, String name, double amount) {
        return adjust(issuer, name, amount, AdjustKind.TAKE);
    }

    private enum AdjustKind { GIVE, SET, TAKE }

    private CompletableFuture<OpResult> adjust(String issuer, String name, double amount, AdjustKind kind) {
        AccountRef ref = resolveAccount(name);
        if (ref == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Unknown account '" + name + "'. Create it with /solidus-admin account create."));
        }
        double rounded = CurrencyUtil.round(amount);
        StorageBackend storage = engine.getStorage();

        switch (kind) {
            case GIVE -> {
                if (!CurrencyUtil.isValidAmount(rounded)) {
                    return CompletableFuture.completedFuture(new OpResult(false,
                        "Amount out of range [" + CurrencyUtil.MIN_TRANSACTION + ", "
                            + CurrencyUtil.format(CurrencyUtil.MAX_TRANSACTION) + "]."));
                }
                return storage.addBalance(ref.uuid(), ref.name(), rounded).thenApply(newBalance -> {
                    if (newBalance < 0) {
                        return new OpResult(false, "Give failed (overflow cap or persistence error).");
                    }
                    logAdmin(TransactionLog.Type.ADMIN_GIVE, issuer, ref, rounded);
                    return new OpResult(true,
                        "Gave " + CurrencyUtil.format(rounded) + " to " + ref.name()
                            + " (new balance " + CurrencyUtil.format(newBalance) + ").");
                });
            }
            case SET -> {
                if (!CurrencyUtil.isValidBalance(rounded)) {
                    return CompletableFuture.completedFuture(new OpResult(false,
                        "Balance out of range [0, " + CurrencyUtil.format(CurrencyUtil.MAX_BALANCE) + "]."));
                }
                // 2.2.4: read the old balance first so the ADMIN_SET ledger row
                // can carry the SIGNED supply delta (new − old). The row used to
                // record the FINAL balance, which the supply-integrity checker
                // would replay as a full burn of the old balance.
                return storage.getBalance(ref.uuid(), ref.name())
                    .thenCompose(oldBalance -> storage.setBalance(ref.uuid(), ref.name(), rounded)
                        .thenApply(ok -> {
                            if (!ok) {
                                return new OpResult(false, "Set failed (invalid amount or persistence error).");
                            }
                            double delta = CurrencyUtil.round(rounded - oldBalance);
                            logAdmin(TransactionLog.Type.ADMIN_SET, issuer, ref, delta);
                            return new OpResult(true,
                                "Set " + ref.name() + " to " + CurrencyUtil.format(rounded)
                                    + " (was " + CurrencyUtil.format(oldBalance)
                                    + ", delta " + (delta >= 0 ? "+" : "") + CurrencyUtil.format(delta) + ").");
                        }));
            }
            case TAKE -> {
                if (!CurrencyUtil.isValidAmount(rounded)) {
                    return CompletableFuture.completedFuture(new OpResult(false,
                        "Amount out of range [" + CurrencyUtil.MIN_TRANSACTION + ", "
                            + CurrencyUtil.format(CurrencyUtil.MAX_TRANSACTION) + "]."));
                }
                return storage.subtractBalance(ref.uuid(), ref.name(), rounded).thenApply(newBalance -> {
                    if (newBalance < 0) {
                        return new OpResult(false, "Take failed (insufficient funds or persistence error).");
                    }
                    logAdmin(TransactionLog.Type.ADMIN_TAKE, issuer, ref, rounded);
                    return new OpResult(true,
                        "Took " + CurrencyUtil.format(rounded) + " from " + ref.name()
                            + " (new balance " + CurrencyUtil.format(newBalance) + ").");
                });
            }
        }
        // unreachable - exhaustive switch above
        throw new IllegalStateException("Unknown adjust kind " + kind);
    }

    private void logAdmin(TransactionLog.Type type, String issuer, AccountRef target, double amount) {
        engine.getTransactionLog().log(type, target.uuid(), target.name(),
            null, null, amount, null, 0,
            "Admin " + type.code() + " by " + issuer);
    }

    // -- Transfers between accounts --------------------------

    /**
     * Executes a REAL atomic transfer between two accounts through
     * {@link BalanceManager#transferOffline} - the same path as /pay: hooks,
     * bounds checks, atomic both-legs-or-neither, ledger PAY_SEND/PAY_RECEIVE.
     * Both accounts must already exist (deterministic test semantics).
     */
    public CompletableFuture<OpResult> payAs(String fromName, String toName, double amount) {
        AccountRef from = resolveAccount(fromName);
        if (from == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Unknown sender account '" + fromName + "'."));
        }
        AccountRef to = resolveAccount(toName);
        if (to == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Unknown receiver account '" + toName
                    + "'. Create it with /solidus-admin account create."));
        }
        return engine.getBalanceManager()
            .transferOffline(from.uuid(), from.name(), to.uuid(), to.name(), amount)
            .thenApply(result -> {
                if (result.success()) {
                    // Ledger parity with /pay offline: both sides of the
                    // payment are recorded exactly like a player payment.
                    TransactionLog txLog = engine.getTransactionLog();
                    txLog.log(TransactionLog.Type.PAY_SEND,
                        from.uuid(), from.name(), to.uuid(), to.name(),
                        amount, null, 0,
                        "Pay-as to " + to.name() + " (admin)");
                    txLog.log(TransactionLog.Type.PAY_RECEIVE,
                        to.uuid(), to.name(), from.uuid(), from.name(),
                        amount, null, 0,
                        "Pay-as from " + from.name() + " (admin)");
                    return new OpResult(true,
                        "Transferred " + CurrencyUtil.format(amount) + ": " + from.name()
                            + " -> " + to.name()
                            + " (sender " + CurrencyUtil.format(result.senderNewBalance())
                            + ", receiver " + CurrencyUtil.format(result.receiverNewBalance()) + ").");
                }
                return new OpResult(false, "Pay-as failed: " + result.message());
            });
    }

    // -- Auction flows ----------------------------------------

    /**
     * Places a bid on behalf of an (offline/dummy) account through the REAL
     * escrow flow ({@link AuctionManager#placeBidAs}). Feedback messages are
     * forwarded into the supplied consumer; the returned future completes as
     * soon as the bid is ACCEPTED into the async pipeline (its outcome lands
     * in the feedback).
     */
    public CompletableFuture<OpResult> bidAs(String bidderName, UUID listingId, double amount,
                                             BiConsumer<Boolean, String> feedback) {
        if (auctions == null) {
            return CompletableFuture.completedFuture(
                new OpResult(false, "Auction manager unavailable."));
        }
        if (!CurrencyUtil.isValidAmount(CurrencyUtil.round(amount))) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Bid amount out of range [" + CurrencyUtil.MIN_TRANSACTION + ", "
                    + CurrencyUtil.format(CurrencyUtil.MAX_TRANSACTION) + "]."));
        }
        AccountRef bidder = resolveAccount(bidderName);
        if (bidder == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Unknown bidder account '" + bidderName
                    + "'. Create it with /solidus-admin account create."));
        }
        auctions.placeBidAs(bidder.uuid(), bidder.name(), listingId,
            CurrencyUtil.round(amount), feedback);
        return CompletableFuture.completedFuture(new OpResult(true,
            "Bid accepted into the escrow pipeline (outcome follows)."));
    }

    /**
     * Creates an auction listing on behalf of an (offline/dummy) account with
     * a conjured item ({@link AuctionManager#listItemAs}). The full player
     * money path runs: listing fee, governance veto, AUCTION_LIST ledger.
     */
    public CompletableFuture<OpResult> auctionCreate(String sellerName, String itemId, int count,
                                                     double price, double startBid,
                                                     BiConsumer<Boolean, String> feedback) {
        if (auctions == null) {
            return CompletableFuture.completedFuture(
                new OpResult(false, "Auction manager unavailable."));
        }
        if (itemResolver == null) {
            return CompletableFuture.completedFuture(
                new OpResult(false, "Item resolver unavailable (unexpected runtime wiring)."));
        }
        AccountRef seller = resolveAccount(sellerName);
        if (seller == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Unknown seller account '" + sellerName
                    + "'. Create it with /solidus-admin account create."));
        }
        if (count < 1 || count > 64) {
            return CompletableFuture.completedFuture(
                new OpResult(false, "Count must be between 1 and 64."));
        }
        ItemStack item = itemResolver.resolve(itemId, count);
        if (item == null || item.isEmpty()) {
            return CompletableFuture.completedFuture(
                new OpResult(false, "Unknown item id '" + itemId + "'."));
        }
        auctions.listItemAs(seller.uuid(), seller.name(), item,
            CurrencyUtil.round(price), CurrencyUtil.round(startBid), feedback);
        return CompletableFuture.completedFuture(new OpResult(true,
            "Listing accepted into the pipeline (fee/ledger outcome follows)."));
    }

    // -- Audit & diagnostics ----------------------------------

    /**
     * Invariant audit over the whole economy:
     * <ol>
     *   <li>NEGATIVE BALANCE scan - balances are sorted descending, so any
     *       negative rows form a suffix; the scan walks pages backwards from
     *       the tail and stops at the first non-negative page (cheap).</li>
     *   <li>ESCROW sanity - the system account must never go negative; a
     *       positive escrow means open bids are holding money (expected while
     *       auctions run).</li>
     *   <li>SUPPLY snapshot - count / mean / supply / Gini for eyeballing
     *       conservation between runs.</li>
     * </ol>
     */
    public CompletableFuture<List<String>> audit() {
        StorageBackend storage = engine.getStorage();
        List<String> report = new ArrayList<>();

        return storage.getBalanceEntryCount().thenCompose(total ->
            scanNegativeBalances(storage, total).thenCompose(negatives ->
                storage.getBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME)
                    .thenCompose(escrow -> storage.getEconomyStats().thenApply(stats -> {
                        report.add("Audit — accounts: " + total);
                        report.add("Money supply: " + CurrencyUtil.format(stats.totalSupply())
                            + " | mean: " + CurrencyUtil.format(stats.avgBalance())
                            + " | Gini: " + String.format(Locale.ROOT, "%.4f",
                                stats.giniCoefficient()));
                        if (escrow < 0) {
                            report.add("!! ESCROW NEGATIVE: " + CurrencyUtil.format(escrow)
                                + " — invariant violated, investigate immediately.");
                        } else if (escrow > 0) {
                            report.add("Escrow holds " + CurrencyUtil.format(escrow)
                                + " (open bids — expected while auctions are live).");
                        } else {
                            report.add("Escrow at zero (no open bids).");
                        }
                        if (negatives.isEmpty()) {
                            report.add("Negative balances: none.");
                        } else {
                            report.add("!! NEGATIVE BALANCES (" + negatives.size() + "):");
                            for (String line : negatives) {
                                report.add("  " + line);
                            }
                        }
                        report.add("Audit complete.");
                        return report;
                    }))));
    }

    // -- Supply integrity (2.2.4, DB scaling plan §7) --------

    /**
     * Runs the supply-integrity check NOW and also fires the escrow
     * consistency check. Returns the report lines for the command layer.
     * When the integrity service was not wired (legacy unit-test ctor), the
     * escrow check still runs and the report says so.
     */
    public CompletableFuture<List<String>> integrityCheck() {
        List<String> lines = new ArrayList<>();
        if (auctions != null) {
            // Async fire-and-forget: it logs its own warning on drift, and a
            // transient in-flight bid can false-flag — the supply report below
            // is the precise artifact.
            auctions.checkEscrowConsistency();
            lines.add("Escrow consistency check: dispatched (see log for warnings).");
        }
        if (integrity == null) {
            lines.add("Supply integrity service not wired in this context.");
            return CompletableFuture.completedFuture(lines);
        }
        return integrity.runOnce().thenApply(report -> {
            for (String line : report.summary().split("\n")) {
                lines.add(line);
            }
            return lines;
        });
    }

    /**
     * Accepts the CURRENT books as the new supply baseline (admin action
     * after investigating a drift report).
     */
    public CompletableFuture<OpResult> integrityRebase() {
        if (integrity == null) {
            return CompletableFuture.completedFuture(new OpResult(false,
                "Supply integrity service not wired in this context."));
        }
        return integrity.rebaseline().thenApply(ok -> ok
            ? new OpResult(true,
                "Supply baseline cleared — the next integrity check re-establishes it "
                    + "from the current books.")
            : new OpResult(false, "Rebase failed (see log)."));
    }

    /**
     * Walks leaderboard pages BACKWARDS from the tail (balances sorted
     * descending - negatives can only be a suffix) and returns one line per
     * negative row. Stops at the first fully non-negative page.
     */
    private CompletableFuture<List<String>> scanNegativeBalances(StorageBackend storage, int total) {
        List<String> negatives = new ArrayList<>();
        if (total == 0) {
            return CompletableFuture.completedFuture(negatives);
        }
        AtomicBoolean cleanTailSeen = new AtomicBoolean(false);
        int pageCount = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        CompletableFuture<List<String>> result = CompletableFuture.completedFuture(negatives);
        for (int page = pageCount; page >= 1; page--) {
            int offset = (page - 1) * PAGE_SIZE;
            result = result.thenCompose(done -> {
                if (cleanTailSeen.get()) {
                    return CompletableFuture.completedFuture(negatives);
                }
                return storage.getTopBalances(PAGE_SIZE, offset).thenApply(rows -> {
                    for (SQLiteStorage.BalanceEntry row : rows) {
                        if (row.balance() < 0) {
                            negatives.add("#" + row.rank() + " " + row.playerName()
                                + " (" + row.uuid() + "): " + CurrencyUtil.format(row.balance()));
                        }
                    }
                    if (!rows.isEmpty() && rows.stream().allMatch(r -> r.balance() >= 0)) {
                        cleanTailSeen.set(true);
                    }
                    return negatives;
                });
            });
        }
        return result;
    }

    /**
     * Runtime diagnostics: active backend, Redis state, auction store mode,
     * economy snapshot. Answers "which mode is this server actually in?".
     */
    public CompletableFuture<List<String>> diag() {
        StorageBackend storage = engine.getStorage();
        List<String> lines = new ArrayList<>();
        return storage.getEconomyStats().thenApply(stats -> {
            lines.add("Backend: " + (engine.isMysqlMode()
                ? "MySQL/MariaDB (multi-server, unified balances)"
                : "SQLite (single-server)"));
            lines.add("Auction store: " + (engine.auctionConnectionSource() != null
                ? "shared MySQL database" : "local SQLite (auctions.db)"));
            boolean redisEnabled = engine.redisSettings() != null && engine.redisSettings().enabled();
            lines.add("Redis layer: " + (redisEnabled
                ? "enabled (L2 cache + pub/sub)" : "disabled (default)"));
            lines.add("Accounts: " + stats.playerCount()
                + " | supply: " + CurrencyUtil.format(stats.totalSupply())
                + " | mean: " + CurrencyUtil.format(stats.avgBalance())
                + " | Gini: " + String.format(Locale.ROOT, "%.4f", stats.giniCoefficient()));
            lines.add("Known names in cache: " + storage.getPlayerNameCache().size());
            lines.add("Auction manager initialized: "
                + (auctions != null && auctions.isInitialized()));
            return lines;
        });
    }
}
