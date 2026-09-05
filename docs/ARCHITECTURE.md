# Solidus-Core Architecture Documentation

> **Version**: 2.2.0 | **Minecraft**: 26.1.x | **Fabric**: 0.19.4+ | **Java**: 25  
> **License**: MIT | **Environment**: 100% Server-Side Only

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Philosophy & Design Principles](#2-architecture-philosophy--design-principles)
3. [High-Level System Architecture](#3-high-level-system-architecture)
4. [Initialization & Lifecycle](#4-initialization--lifecycle)
5. [Package Structure](#5-package-structure)
6. [Core Subsystem: Economy Engine](#6-core-subsystem-economy-engine)
   - 6.1 [EconomyEngine — The Central Coordinator](#61-economyengine--the-central-coordinator)
   - 6.2 [SQLiteStorage — Async Persistent Backend](#62-sqlitestorage--async-persistent-backend)
   - 6.3 [BalanceManager — High-Level Balance API](#63-balancemanager--high-level-balance-api)
   - 6.4 [TransactionLog — Audit Trail & Notifications](#64-transactionlog--audit-trail--notifications)
7. [Core Subsystem: Virtual Shop](#7-core-subsystem-virtual-shop)
   - 7.1 [ShopManager — Configuration & Transactions](#71-shopmanager--configuration--transactions)
   - 7.2 [Shop GUI Architecture](#72-shop-gui-architecture)
   - 7.3 [ShopScreenHandler — Click Rewriting](#73-shopscreenhandler--click-rewriting)
8. [Core Subsystem: Auction House](#8-core-subsystem-auction-house)
   - 8.1 [AuctionManager — Race-Condition-Free Controller](#81-auctionmanager--race-condition-free-controller)
   - 8.2 [Auction Data Model](#82-auction-data-model)
   - 8.3 [Auction GUI Architecture](#83-auction-gui-architecture)
9. [Core Subsystem: Sell System](#9-core-subsystem-sell-system)
   - 9.1 [SellScreenHandler — Cursor-Based Item Movement](#91-sellscreenhandler--cursor-based-item-movement)
   - 9.2 [Sell Flow: Open → Place → Close → Process](#92-sell-flow-open--place--close--process)
10. [Cross-Cutting: Networking & Packet Handling](#10-cross-cutting-networking--packet-handling)
    - 10.1 [ServerPlayerEntityMixin — Packet Interception](#101-serverplayerentitymixin--packet-interception)
    - 10.2 [ScreenHandler-Level Protections (No Second Mixin)](#102-screenhandler-level-protections-no-second-mixin)
    - 10.3 [PacketHandler — Click Routing Gateway](#103-packethandler--click-routing-gateway)
    - 10.4 [RateLimiter — Click & Transfer Cooldowns](#104-ratelimiter--click--transfer-cooldowns)
11. [Cross-Cutting: Permission System](#11-cross-cutting-permission-system)
    - 11.1 [SolidusPermissions — Permission Node Registry](#111-soliduspermissions--permission-node-registry)
    - 11.2 [PermissionChecker — Unified Checking with LuckPerms](#112-permissionchecker--unified-checking-with-luckperms)
    - 11.3 [PermissionConfig — OP-Level Fallback Configuration](#113-permissionconfig--op-level-fallback-configuration)
12. [Cross-Cutting: Virtual GUI Architecture](#12-cross-cutting-virtual-gui-architecture)
    - 12.1 [The DummyContainer Pattern](#121-the-dummycontainer-pattern)
    - 12.2 [Ghost Item Prevention — Defense-in-Depth](#122-ghost-item-prevention--defense-in-depth)
13. [Public API & Integration Guide](#13-public-api--integration-guide)
    - 13.1 [SolidusAPI — Stable Public API](#131-solidusapi--stable-public-api)
    - 13.2 [Reflection-Based Integration (Zero Dependency)](#132-reflection-based-integration-zero-dependency)
    - 13.3 [Compile-Time Integration](#133-compile-time-integration)
    - 13.4 [SolidusIntegration — Reference Implementation](#134-solidusintegration--reference-implementation)
    - 13.5 [SolidusTransactionHook — Economy Interception Hooks](#135-solidustransactionhook--economy-interception-hooks)
14. [Thread Safety Model](#14-thread-safety-model)
15. [Database Schema](#15-database-schema)
16. [Configuration System](#16-configuration-system)
17. [Command Reference](#17-command-reference)
18. [Testing Strategy](#18-testing-strategy)
19. [Extension Points & Integration Hooks](#19-extension-points--integration-hooks)
20. [Security Considerations](#20-security-considerations)
21. [Performance Characteristics](#21-performance-characteristics)
22. [Glossary](#22-glossary)

---

## 1. System Overview

**Solidus-Core** is an advanced server-side economy and commerce engine built for Minecraft Fabric. It provides a complete virtual currency system, GUI-based shop, peer-to-peer auction house, item selling system, and a stable public API for inter-mod integration — all running entirely on the server with zero client-side modifications required.

The mod operates through **packet manipulation**: it intercepts container click packets from vanilla Minecraft clients, rewrites them into Solidus-specific actions (buy, sell, bid, navigate), and sends back updated inventory snapshots. Players interact with what appears to be normal chest menus, but the underlying logic is entirely custom.

### Key Capabilities

| Feature | Description |
|---------|-------------|
| **Virtual Currency** | S$ (Solidus) with configurable starting balance, max balance, and transaction limits |
| **Persistent Storage** | SQLite with WAL mode, in-memory cache, and async single-thread executor |
| **GUI Shop** | JSON-configured virtual shop with 11 sections, 185 items, buy/sell prices |
| **Auction House** | Peer-to-peer marketplace: buy-now listings plus optional **bidding** (money escrow, anti-snipe, self-healing refunds), 72h listings, configurable fee, sort/search/cancel/collect |
| **Direct Trade** | `/trade` mutual-preview window (items + money) that executes only when both players are ready — see `docs/FEATURES_TRADE_BIDDING.md` |
| **Sell GUI** | Full cursor-based item placement GUI with shulker box content inspection |
| **Transaction Logging** | 15 transaction types with persistent audit trail and offline notification delivery |
| **Permission System** | Fine-grained permission nodes with LuckPerms integration and OP-level fallback |
| **Public API** | Stable `SolidusAPI` singleton accessible via reflection (zero compile dependency) |
| **Rate Limiting** | 150ms click cooldown per player to prevent exploit automation |
| **Anti-Dupe Protection** | TOCTOU-safe atomic operations, double-purchase guards, ghost item prevention |

---

## 2. Architecture Philosophy & Design Principles

### 2.1 Server-Side Only

Every feature operates without client-side mods. Players connect with completely unmodified vanilla Minecraft clients. All UI is rendered through native Minecraft chest inventory packets — no custom textures, no custom models, no client-side code.

**Implication**: The mod cannot send custom GUI layouts. It must work within the constraints of vanilla container slots (9×6 = 54 slots max for a large chest). Every visual element is an `ItemStack` displayed in a slot — glass panes for decoration, paper for info, specific items for shop icons.

### 2.2 Async-First, Never Block the Tick Thread

The Minecraft server runs on a single main tick thread. Blocking it — even briefly — causes TPS drops and player-visible lag. Solidus is designed so that **zero blocking calls** exist on the tick thread:

- All database operations are dispatched to a single-thread executor and return `CompletableFuture`
- Callbacks chain via `.thenAccept()` + `server.execute()` to safely update game state
- No `.join()`, `.get()`, or `.await()` calls exist anywhere in the codebase

### 2.3 Single-Thread Executor Serialization

Instead of using database-level locking (which is complex and error-prone), Solidus serializes all mutations through dedicated single-thread executors. This guarantees that:

- No two balance operations can execute concurrently
- No race conditions between check-then-act sequences
- The in-memory cache is always consistent with the database
- No need for `synchronized` blocks or `ReentrantLock`

**Trade-off**: Operations are slightly slower due to executor queuing, but this is negligible for a Minecraft server's transaction volume and eliminates an entire class of concurrency bugs.

### 2.4 TOCTOU-Safe Atomic Operations

Time-of-Check-to-Time-of-Use (TOCTOU) vulnerabilities are the most common exploit in economy plugins. Solidus prevents them by making check-and-act operations atomic within the executor:

```
// VULNERABLE (separate check then act):
double balance = getBalance(player);    // CHECK
if (balance >= amount) {
    subtractBalance(player, amount);    // ACT — balance may have changed!
}

// SAFE (Solidus approach — atomic within executor):
subtractBalance(player, amount)         // Checks AND deducts atomically
    .thenAccept(newBalance -> {
        if (newBalance < 0) handleInsufficientFunds();
    });
```

### 2.5 Defense-in-Depth for Virtual GUIs

Virtual GUIs (shop, auction) are inherently vulnerable because the client believes it's interacting with a real container. Solidus implements five layers of protection:

1. **RateLimiter** — 150ms cooldown prevents rapid automated clicks (Solidus GUIs only)
2. **Mixin Interception** — `ServerPlayerEntityMixin` catches clicks before vanilla processing (with a packet containerId desync guard)
3. **ScreenHandler `clicked()` Overrides** — Custom handlers rewrite clicks into Solidus actions, verify ownership, and whitelist click types
4. **DisplaySlot** — Slot-level hardening: `mayPlace`/`mayPickup` return false and `set()` is a no-op
5. **DummyContainer** — Display-only containers block all item insertion/removal
6. **broadcastFullState()** — Full client resync after every processed click (plus a throttled resync for rate-limited ones) — erases ghost-item predictions in the same tick

### 2.6 Reflection-Based Inter-Mod API

External mods should not need to compile against Solidus. The `SolidusAPI` class is accessible via pure Java reflection, allowing zero-dependency integration. This means:

- No Maven/Gradle dependency on solidus-core required
- No version coupling — the API contract is method names and parameter types
- Mods can gracefully degrade if Solidus is not installed

**Trust boundary (audit 2.1.3):** the reflection API is a *cooperation* surface, not a *security* boundary. Any mod loaded into the same JVM already shares Solidus's classes, heap and file system — a hostile mod can bypass `SolidusAPI` entirely (reflect into `BalanceManager`, open the SQLite file directly, or hook the same vanilla packets Solidus hooks). Treat every mod you install as trusted code; vet companion mods before adding them to `mods/`. Two mitigations exist for honest-but-buggy (not hostile) companions: hook-name collisions are rejected and logged at `ERROR` (a silent duplicate registration is the classic way a companion's enforcement quietly disappears), and the `SolidusIntegration` reference example demonstrates the atomic `transferOffline` pattern instead of the crash-prone subtract-then-add ladder.

---

## 3. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Minecraft Server                             │
│                                                                     │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────────────┐   │
│  │  Brigadier   │    │   Fabric     │    │   Minecraft Server   │   │
│  │  Commands    │    │   Events     │    │   Tick Loop          │   │
│  └──────┬───────┘    └──────┬───────┘    └──────────┬───────────┘   │
│         │                   │                       │               │
│         ▼                   ▼                       ▼               │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      SolidusMod.java                         │   │
│  │                 (DedicatedServerModInitializer)              │   │
│  │  ┌────────────────┐  ┌────────────────┐  ┌──────────────┐  │   │
│  │  │  EconomyEngine  │  │  ShopManager   │  │ AuctionMgr   │  │   │
│  │  │  ┌────────────┐ │  │                │  │              │  │   │
│  │  │  │SQLiteStore │ │  │  shop.json     │  │ SQLite DB    │  │   │
│  │  │  │+Cache      │ │  │  (185 items)   │  │ (listings)   │  │   │
│  │  │  └────────────┘ │  │                │  │              │  │   │
│  │  │  ┌────────────┐ │  └───────┬────────┘  └──────┬───────┘  │   │
│  │  │  │BalanceMgr  │ │          │                   │          │   │
│  │  │  └────────────┘ │          ▼                   ▼          │   │
│  │  │  ┌────────────┐ │   ┌──────────────┐   ┌──────────────┐  │   │
│  │  │  │TransactionLog│ │   │ ShopGUI/     │   │ AuctionGUI/  │  │   │
│  │  │  └────────────┘ │   │ ShopScreenH. │   │ AuctionScrH. │  │   │
│  │  └────────────────┘ │   └──────────────┘   └──────────────┘  │   │
│  │                      │                                      │   │
│  │  ┌──────────────────┐│  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │  PacketHandler   ││  │  SellGUI/    │  │ SolidusAPI   │  │   │
│  │  │  + RateLimiter   ││  │  SellScreenH.│  │ (Public API) │  │   │
│  │  └──────────────────┘│  └──────────────┘  └──────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    Mixin Layer                                │   │
│  │  ┌─────────────────────────┐  ┌───────────────────────────┐ │   │
│  │  │ ServerPlayerEntityMixin │  │   DisplaySlot             │ │   │
│  │  │ (Packet Interception)   │  │   (Frozen display slots)  │ │   │
│  │  └─────────────────────────┘  └───────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                   Permission System                           │   │
│  │  ┌──────────────┐  ┌────────────────┐  ┌────────────────┐  │   │
│  │  │SolidusPerms  │  │PermissionChk   │  │PermissionConfig│  │   │
│  │  │(Constants)   │  │(+LuckPerms)    │  │(OP Fallback)   │  │   │
│  │  └──────────────┘  └────────────────┘  └────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────┐
                    │   External Mods      │
                    │   (via SolidusAPI)   │
                    │   Reflection-based   │
                    └─────────────────────┘
```

---

## 4. Initialization & Lifecycle

The entire mod lifecycle is managed by `SolidusMod.java`, which implements `DedicatedServerModInitializer`. The initialization sequence is strictly ordered by dependency:

```
┌──────────────────────────────────────────────────────┐
│              onInitializeServer()                      │
│                                                        │
│  1. PermissionConfig.initialize(configDir)             │
│     └─ Loads/creates config/solidus/permissions.json  │
│                                                        │
│  2. new RateLimiter()                                  │
│     └─ 150ms cooldown map initialized                  │
│                                                        │
│  3. new EconomyEngine() → initialize()                 │
│     ├─ ConfigManager: resolves config/solidus dir      │
│     ├─ SQLiteStorage: opens economy.db, creates tables,│
│     │   enables WAL, pre-loads all balances to cache,  │
│     │   creates the TransactionLog (same executor)     │
│     ├─ Pre-creates the escrow account at balance 0     │
│     └─ BalanceManager: wraps SQLiteStorage             │
│                                                        │
│  4. SolidusAPI.initialize(engine)                      │
│     └─ At MOD INIT time (NOT SERVER_STARTED) so        │
│        companions registering hooks at SERVER_STARTING │
│        find a non-null API (idempotent)                │
│                                                        │
│  5. new ShopManager(economyEngine) → loadConfiguration │
│     └─ Copies shop.json from JAR on first run, parses  │
│        it, applies startingBalance/currency/listingFee │
│                                                        │
│  6. new AuctionManager(economyEngine) → initialize()   │
│     └─ Opens auctions.db (+ bid tables), runs startup  │
│        sweeps (orphaned SOLD rows, orphaned bid states,│
│        escrow consistency check)                       │
│                                                        │
│  7. new ChatPrompts() + new TradeManager(engine, prompts)│
│                                                        │
│  8. new PacketHandler(shop, auction, trade, limiter)   │
│     └─ Registers DISCONNECT cleanup (rate limiter,     │
│        pending shop transactions, sell-GUI recovery)   │
│                                                        │
│  9. Register Brigadier commands                        │
│     └─ /bal, /pay, /baltop, /shop, /sell, /ah,        │
│        /trade, /transactions                           │
│                                                        │
│ 10. Register SERVER_STOPPING hook                      │
│     └─ Clean shutdown: trade → auction → economy →     │
│        rate limiter                                    │
│                                                        │
│ 11. Register SERVER_STARTED hook                       │
│     └─ Inject MinecraftServer into AuctionManager +    │
│        TradeManager                                    │
│                                                        │
│ 12. Register END_SERVER_TICK hook                      │
│     └─ Every 6000 ticks: auction expiry check +        │
│        reap idle trade sessions                        │
│                                                        │
│ 13. Register JOIN / DISCONNECT events                  │
│     └─ JOIN: deliver pending offline notifications      │
│     └─ DISCONNECT: cancel trade session + chat prompt  │
└──────────────────────────────────────────────────────┘
```

### Shutdown Sequence

```
SERVER_STOPPING event fires:
  1. tradeManager.shutdown()      — Cancel open sessions, return offered items
  2. auctionManager.shutdown()    — Executor shutdown + final DB writes
  3. economyEngine.shutdown()     — SQLite connection close + cache flush
  4. rateLimiter.clear()          — Clear cooldown map
```

The shutdown order is the reverse of initialization, ensuring that dependent systems are torn down after their dependencies.

---

## 5. Package Structure

```
com.solidus
├── SolidusMod.java              // Entry point, subsystem orchestrator
├── api/                          // Public integration API
│   ├── SolidusAPI.java           // Stable singleton API (reflection-accessible)
│   ├── SolidusTransactionHook.java // Veto + notification hook interface (2.1.0+)
│   ├── EconomyHooks.java         // Hook registry & dispatch (internal, fail-open)
│   ├── SolidusIntegration.java   // Reference implementation for external mods
│   ├── SolidusPermissions.java   // Permission node constants
│   ├── PermissionChecker.java    // Unified checking (LuckPerms + OP fallback)
│   └── PermissionConfig.java     // OP-level config loader
├── auction/                      // Auction House subsystem
│   ├── AuctionManager.java       // Core controller (2570 lines incl. bidding)
│   ├── AuctionEntry.java         // Immutable listing record
│   ├── ListingStatus.java        // ACTIVE/SOLD/EXPIRED enum
│   ├── BidRules.java             // Pure bid validation + anti-snipe arithmetic
│   ├── BidState.java             // Per-listing bid state snapshot record
│   ├── AuctionGUI.java           // Virtual chest builder (bid-aware lore)
│   ├── AuctionScreenHandler.java // Click handler (left=buy, right=bid prompt)
│   └── AuctionDummyContainer.java // Display-only container
├── chat/                         // Chat-driven input
│   └── ChatPrompts.java          // "Type amount in chat" prompt service (2.2.0+)
├── commands/                     // Brigadier command registrations
│   ├── BalanceCommand.java       // /bal
│   ├── PayCommand.java           // /pay (online + offline)
│   ├── BaltopCommand.java        // /baltop
│   ├── ShopCommand.java          // /shop, /shop search, /shop reload
│   ├── SellCommand.java          // /sell gui, /sell all [item] (+shulkers)
│   ├── AuctionCommand.java       // /ah sell/bid/collect/cancel/sort/search
│   ├── TradeCommand.java         // /trade <player>|accept|deny|cancel (2.2.0+)
│   └── TransactionsCommand.java  // /transactions [page] [export [days] | exportall [days]]
├── economy/                      // Core economy engine
│   ├── EconomyEngine.java        // Central coordinator
│   ├── SQLiteStorage.java        // Async persistent backend (941 lines)
│   ├── BalanceManager.java       // High-level balance API
│   ├── TransactionLog.java       // Audit trail + notifications + CSV export
│   └── EscrowAccount.java        // Bid-escrow system account (2.2.0+)
├── gui/                          // Shared GUI primitives
│   └── DisplaySlot.java          // Display-only Slot (no place/pickup/set)
├── mixin/                        // Mixin injections
│   └── ServerPlayerEntityMixin.java // Packet interception (the ONLY mixin)
├── networking/                   // Packet processing
│   ├── PacketHandler.java        // Click routing gateway + resync policy
│   └── RateLimiter.java          // 150ms click / 1s /pay cooldowns
├── sell/                         // Sell GUI subsystem
│   ├── SellGUI.java              // Virtual chest builder
│   ├── SellScreenHandler.java    // Full cursor item movement (826 lines)
│   └── SellContainer.java        // Real container (stores player items)
├── shop/                         // Virtual Shop subsystem
│   ├── ShopManager.java          // Config loader + transaction processor
│   ├── ShopGUI.java              // Virtual chest builder (bordered layout)
│   ├── ShopGUILayout.java        // Pure-Java centering layout engine
│   ├── ShopScreenHandler.java    // Click rewriting handler
│   └── ShopDummyContainer.java   // Display-only container
├── trade/                        // Direct trade subsystem (2.2.0+)
│   ├── TradeManager.java         // Requests, sessions, execution, reaping
│   ├── TradeSession.java         // Player-agnostic session state machine
│   ├── TradeContainer.java       // 54-slot session container (item escrow)
│   ├── TradeGUI.java             // Window layout + display builders
│   └── TradeScreenHandler.java   // Manual cursor movement + click safety
└── util/                         // Shared utilities
    ├── ConfigManager.java        // File I/O, JSON loading, JAR resource copying
    ├── CurrencyUtil.java         // Currency constants, formatting, validation
    └── TextUtil.java             // Modern Component utilities, material names
```

---

## 6. Core Subsystem: Economy Engine

The economy engine is the heart of Solidus. It manages virtual currency persistence, provides thread-safe balance operations, and records every financial transaction for audit and notification purposes.

### 6.1 EconomyEngine — The Central Coordinator

**File**: `com.solidus.economy.EconomyEngine`

`EconomyEngine` is the top-level coordinator that owns and manages the lifecycle of three sub-components:

| Component | Purpose |
|-----------|---------|
| `SQLiteStorage` | Low-level async database operations |
| `BalanceManager` | High-level validated balance API |
| `TransactionLog` | Persistent audit trail and notifications |

```java
// Simplified lifecycle (matches EconomyEngine.java)
public class EconomyEngine {
    private SQLiteStorage storage;
    private BalanceManager balanceManager;
    private volatile boolean initialized = false;

    public void initialize() {
        ConfigManager.initialize(...);          // resolves config/solidus
        storage = new SQLiteStorage(configDir);
        storage.initialize();                   // Opens DB, creates tables, pre-loads cache,
                                                // creates the TransactionLog on the same executor
        // Pre-create the bid-escrow system account at balance 0 so the first
        // transfer INTO escrow cannot mint phantom "starting balance" money.
        storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0);
        balanceManager = new BalanceManager(storage);
        initialized = true;
    }

    public TransactionLog getTransactionLog() {
        return storage.getTransactionLog();     // owned by SQLiteStorage
    }

    public void shutdown() {
        initialized = false;
        storage.shutdown();                     // Close SQLite connection
    }
}
```

**Key Design Decision**: `EconomyEngine` does not perform any business logic itself. It is purely a lifecycle manager and dependency injector. All actual operations flow through `BalanceManager` and `SQLiteStorage`.

---

### 6.2 SQLiteStorage — Async Persistent Backend

**File**: `com.solidus.economy.SQLiteStorage`

This is the most architecturally significant class in Solidus. It implements an **async SQLite backend with in-memory cache** that guarantees thread safety without database-level locking.

#### Architecture: Dual-Layer Storage

```
┌─────────────────────────────────────────────┐
│              BalanceManager                  │
│         (validation, business logic)         │
└──────────────────┬──────────────────────────┘
                   │ CompletableFuture operations
                   ▼
┌─────────────────────────────────────────────┐
│              SQLiteStorage                   │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │   In-Memory Cache                    │   │
│  │   ConcurrentHashMap<UUID, Double>    │   │
│  │   - Instant reads (no DB query)      │   │
│  │   - Always consistent with DB        │   │
│  └──────────────┬───────────────────────┘   │
│                 │ All mutations serialized   │
│                 ▼                            │
│  ┌──────────────────────────────────────┐   │
│  │   Single-Thread Executor             │   │
│  │   ExecutorService (single thread)    │   │
│  │   - Serializes all DB operations     │   │
│  │   - Eliminates race conditions       │   │
│  │   - Guarantees ordering             │   │
│  └──────────────┬───────────────────────┘   │
│                 │                            │
│                 ▼                            │
│  ┌──────────────────────────────────────┐   │
│  │   SQLite Database (WAL mode)         │   │
│  │   economy.db                         │   │
│  │   - Write-Ahead Logging              │   │
│  │   - Crash-safe persistence           │   │
│  │   - Concurrent read access           │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

#### WAL Mode (Write-Ahead Logging)

SQLite's default journal mode (DELETE) creates exclusive locks during writes, blocking all readers. WAL mode changes this:

- **Writers** append to a separate WAL file (non-blocking for readers)
- **Readers** see a snapshot from before the current write transaction
- **Checkpoint** merges WAL back into the main database periodically
- **Crash Recovery**: On restart, SQLite automatically replays the WAL

This is critical for a Minecraft server where the tick thread may need to read balances while a write is in progress.

#### Pre-Loading Strategy

On startup, `SQLiteStorage` loads **all** player balances into the in-memory `ConcurrentHashMap`. This means:

- Balance reads never touch the database (instant, O(1) from cache)
- Only writes go through the executor to SQLite
- The cache is the single source of truth during runtime
- Database is only consulted during initialization and for queries that bypass the cache (like `getTopBalances`)

#### Name Cache

In addition to the balance cache, `SQLiteStorage` maintains a `ConcurrentHashMap<UUID, String>` mapping UUIDs to player names. This enables offline operations like `/pay offline <name>` to resolve names to UUIDs without additional database queries.

#### Key Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `getBalance(UUID)` | Reads from in-memory cache (instant; new players are created at the configured starting balance on the executor) | `CompletableFuture<Double>` |
| `addBalance(UUID, String, double)` | Validates → updates cache → persists to SQLite (rollback on persist failure) | `CompletableFuture<Double>` |
| `subtractBalance(UUID, String, double)` | Atomic check-and-deduct (TOCTOU-safe) | `CompletableFuture<Double>` |
| `setBalance(UUID, String, double)` | Direct balance set (admin operation) | `CompletableFuture<Boolean>` |
| `transferAtomic(...)` | Both legs inside one `BEGIN IMMEDIATE ... COMMIT` transaction | `CompletableFuture<TransferOutcome>` |
| `transferAtomicWithLedger(..., rows)` | Same, plus ledger rows inserted INSIDE the money transaction (audit 2.1.3) | `CompletableFuture<TransferOutcome>` |
| `hasBalance(UUID, double)` | Checks cache for sufficient funds | `CompletableFuture<Boolean>` |
| `getTopBalances(int)` | Queries SQLite for leaderboard (first page) | `CompletableFuture<List<BalanceEntry>>` |
| `getTopBalances(int, int)` | Paged leaderboard via SQL LIMIT/OFFSET on idx_balance_rank; ranks continue across pages; **escrow system account excluded** | `CompletableFuture<List<BalanceEntry>>` |
| `getBalanceEntryCount()` | Cheap COUNT(*) of economy entries for "Page X/Y" footers | `CompletableFuture<Integer>` |
| `getEconomyStats()` | One-query aggregates: player count, mean, supply, Gini (no rows pulled) | `CompletableFuture<EconomyStats>` |
| `getPlayerNameCache()` | Read-only view for offline name lookups | `Map<UUID, String>` |

#### The `subtractBalance` Atomic Guarantee

```java
// Simplified implementation showing the atomic check-and-deduct
public CompletableFuture<Double> subtractBalance(UUID uuid, String name, double amount) {
    Double current = balanceCache.get(uuid);
    if (current == null || current < amount) {
        return CompletableFuture.completedFuture(-1.0);  // Insufficient funds
    }
    double newBalance = current - amount;
    balanceCache.put(uuid, newBalance);                  // Immediate cache update
    return submitToExecutor(() -> {
        // Persist to SQLite — serialized, no race possible
        executeUpdate("UPDATE balances SET balance = ? WHERE uuid = ?", newBalance, uuid.toString());
        return newBalance;
    });
}
```

Because all mutations go through the single-thread executor, and the cache is updated immediately before the DB write is queued, no other operation can read a stale balance between the check and the deduct.

---

### 6.3 BalanceManager — High-Level Balance API

**File**: `com.solidus.economy.BalanceManager`

`BalanceManager` wraps `SQLiteStorage` with business logic validation:

- **Amount validation**: Rejects negative, zero, NaN, infinite amounts
- **Balance limits**: Enforces `MAX_BALANCE` (100,000,000 S$) and `MAX_TRANSACTION` (10,000,000 S$)
- **Player resolution**: Converts `ServerPlayer` → UUID + name for storage operations
- **Atomic transfers**: `transferOffline()` runs both legs inside ONE SQLite transaction via `transferAtomic` — no manual refund path exists

#### TransferResult

All transfer operations return a `TransferResult` record:

```java
public record TransferResult(
    boolean success,
    String message,
    double senderNewBalance,     // 0 when the transfer failed
    double receiverNewBalance    // 0 when the transfer failed
) {}
```

#### Atomic Transfer (Single SQLite Transaction)

`transferOffline` delegates to `SQLiteStorage.transferAtomic`, which runs **both legs inside ONE `BEGIN IMMEDIATE ... COMMIT` transaction** on the economy executor:

```
1. Validate amount (positive, within limits, not self-transfer)
2. Fire the allowTransfer governance veto (2.1.0+ hooks)
3. BEGIN IMMEDIATE — grab the write lock up front
4. Read both balances inside the transaction (missing row = starting balance)
5. Insufficient funds / receiver-overflow → ROLLBACK, nothing moved
6. UPSERT both balances + insert the ledger rows in the same transaction
7. COMMIT — then publish both new balances to the in-memory cache
8. Fire the afterTransfer governance notification (post-settlement)
```

There is no manual deduct-then-refund path anymore: a crash between the legs is
impossible by construction, because both legs commit or roll back as one unit.

---

### 6.4 TransactionLog — Audit Trail & Notifications

**File**: `com.solidus.economy.TransactionLog`

The `TransactionLog` serves two purposes:

1. **Persistent Audit Trail** — Every financial operation is logged to the SQLite `transaction_log` table with type, amount, timestamp, and involved parties
2. **Offline Notification Delivery** — When a transaction affects an offline player, a notification is queued and delivered when they next join

#### Transaction Types

The `type` column stores TEXT codes (not numeric). 15 types exist (2.2.0):

| Code | Direction | Description |
|------|-----------|-------------|
| `PAY_SEND` | money out | Sent currency to another player |
| `PAY_RECEIVE` | money in | Received currency from another player |
| `SHOP_BUY` | money out | Purchased item from shop |
| `SHOP_SELL` | money in | Sold item to shop |
| `AUCTION_LIST` | money out | Listed item on auction (fee) |
| `AUCTION_SOLD` | money in | Auction listing sold (seller side) |
| `AUCTION_BOUGHT` | money out | Purchased auction listing (buyer side) |
| `AUCTION_EXPIRED` | item flow | Auction listing expired |
| `BID_PLACED` | money out (2.2.0) | Escrowed bid placed — amount moved to escrow |
| `BID_REFUNDED` | money in (2.2.0) | Escrowed amount returned to the bidder |
| `AUCTION_WON` | money in (2.2.0) | Bidding auction settled to the highest bidder |
| `TRADE_SEND` | money/items out (2.2.0) | Direct trade: what this player gave |
| `TRADE_RECEIVE` | money/items in (2.2.0) | Direct trade: what this player received |
| `DEATH_PENALTY` | money out | Lost money from being killed |
| `DEATH_REWARD` | money in | Gained money from killing another player |

`Type.fromCode` logs a warning and falls back to `SHOP_BUY` for unknown codes, so
companion mods compiled against older type lists keep rendering safely.

#### Offline Notification Architecture

Notifications are **purely database-driven** (single source of truth — the
`pending_notifications` table in `economy.db`):

```
queueNotification(uuid, msg, server)
  ├─ Player ONLINE  → deliver immediately as chat
  └─ Player OFFLINE → INSERT row into pending_notifications

deliverPendingNotifications(player)      [on JOIN]
  ├─ SELECT exact rows ordered by timestamp
  ├─ Send messages on the server thread (still-connected check first)
  └─ DELETE only the delivered row ids — a newer notification queued
     after the snapshot survives (no loss window)
```

The old in-memory mirror (a `ConcurrentHashMap<UUID, CopyOnWriteArrayList<String>>`
of pending messages) was removed: it could diverge from the database, and delivery
used to wipe freshly queued rows.

#### CSV Export (`/transactions export`)

Solidus 2.1.0 adds windowed reads plus CSV serialization for server bookkeeping:

| Method | SQL | Row cap |
|--------|-----|---------|
| `getTransactionsSince(uuid, sinceMs)` | `WHERE player_uuid = ? AND timestamp >= ?` on the (player_uuid, timestamp DESC) index | `MAX_EXPORT_ROWS` (200,000, newest win) |
| `getAllTransactionsSince(sinceMs)` | `WHERE timestamp >= ?` (admin full-ledger path) | same cap |

- `buildCsv(entries)` / `writeCsvFile(entries, path)` emit RFC 4180-style CSV with 11 columns: `timestamp_ms` (sortable epoch), `timestamp_utc` (ISO-8601 UTC), `type`, `player_uuid`, `player_name`, `target_uuid`, `target_name`, `amount` (2 decimals, `Locale.ROOT`), `item_material`, `item_quantity`, `description`. Fields containing commas, quotes, or line breaks are quoted with doubled inner quotes; null fields export empty.
- Files land in `<game dir>/solidus/exports/transactions_export_[all_]<yyyyMMdd_HHmmss>.csv`, suffixed `_2`, `_3`, ... on collision. File IO runs on the common pool - never the DB executor and never the server thread; only the completion message hops back via `server.execute`.
- Permission model: `/transactions export [days]` exports the caller's own history (OP 0, default 7 days, max 365); `/transactions exportall [days]` exports every player's ledger (OP 2+).

---

## 7. Core Subsystem: Virtual Shop

The virtual shop is a JSON-configured server shop where players can buy and sell items through a native Minecraft chest GUI. Unlike the auction house (peer-to-peer), the shop trades directly with the server's virtual economy.

### 7.1 ShopManager — Configuration & Transactions

**File**: `com.solidus.shop.ShopManager`

#### Shop Configuration Loading

Shop items are defined in `shop.json`, bundled within the mod JAR. The loading process:

```
1. Read shop.json from JAR resources
2. Parse JSON using Gson → Map of sections
3. Each section has: display_name, icon material, list of items
4. Each item has: material, buy_price (or null), sell_price (or null)
5. Deserialize display names using ComponentSerialization.CODEC
   (supports Minecraft text component JSON format)
6. Build in-memory lookup maps: material → item, material → section
```

#### Transaction Processing with TOCTOU Protection

```java
// Simplified buy flow showing anti-race-condition guards
public boolean buyItem(ServerPlayer player, String material, int quantity) {
    // 1. Double-purchase guard — prevent same player from buying simultaneously
    if (pendingBuys.contains(player.getUUID())) return false;
    pendingBuys.add(player.getUUID());

    try {
        double price = getItemBuyPrice(material) * quantity;
        // 2. Atomic subtract (TOCTOU-safe) — checks AND deducts atomically
        double newBalance = balanceManager.subtractBalance(player, price).join();
        if (newBalance < 0) {
            // Refund not needed — subtractBalance returns -1 but doesn't deduct
            return false;
        }
        // 3. Give items to player
        giveItems(player, material, quantity);
        // 4. Log transaction
        transactionLog.log(player, SHOP_BUY, price, material, quantity);
        return true;
    } finally {
        pendingBuys.remove(player.getUUID());
    }
}
```

The `pendingBuys` / `pendingSells` sets prevent a player from triggering two simultaneous transactions that could lead to:
- Double-spending (buying two items when they can only afford one)
- Double-selling (selling the same item stack twice)

#### Shulker Box Support

When selling items, if the item is a shulker box, `ShopManager` inspects its contents using Minecraft's `ShulkerBoxBlockEntity` item saving convention. Each item inside the shulker is priced individually, and the total sell value is the sum of all contained items' sell prices.

---

### 7.2 Shop GUI Architecture

The shop GUI is built by `ShopGUI.java` using the virtual chest pattern:

#### Main Menu (Page 0)

```
┌─────────────────────────────────────────────────┐
│ [Solidus Shop]  [Search]  []  []  []  []  [X]   │  Row 0: Title + navigation
├─────────────────────────────────────────────────┤
│ [Building] [Ores] [Food] [Farming] [Combat] ... │  Rows 1-4: Section icons
│ [Trials]   [Armor] [Potions] [Redstone] [Deco]  │
│ [Misc/Ocean] []  []  []  []  []  []  []          │
├─────────────────────────────────────────────────┤
│ []  []  []  []  []  []  []  []  []               │  Row 5: Empty
└─────────────────────────────────────────────────┘
```

#### Section Page (Paginated)

```
┌─────────────────────────────────────────────────┐
│ [← Back] [Section Name]  []  []  []  []  [Page] │  Row 0: Navigation
├─────────────────────────────────────────────────┤
│ [Item1] [Item2] [Item3] [Item4] [Item5] ...     │  Rows 1-4: Items with price lore
│ [Item6] [Item7] [Item8] [Item9] [Item10] ...    │
│ ...                                              │
├─────────────────────────────────────────────────┤
│ [←Prev] []  []  []  []  []  []  []  [Next→]     │  Row 5: Pagination
└─────────────────────────────────────────────────┘
```

Each item's lore shows:
- **Buy price**: Green text with `► Buy: 50 S$`
- **Sell price**: Red text with `◄ Sell: 25 S$`
- Items without a buy price show "Buy: N/A"
- Items without a sell price show "Sell: N/A"

---

### 7.3 ShopScreenHandler — Click Rewriting

**File**: `com.solidus.shop.ShopScreenHandler`

The `ShopScreenHandler` intercepts every click in the shop GUI and translates it into a Solidus action. This is where the virtual GUI pattern becomes critical: the client thinks it's clicking on an item in a chest, but the server rewrites that click into a buy/sell operation.

#### Click Mapping

| Action | Click Type | Behavior |
|--------|-----------|----------|
| Buy 1 | Left-click | Purchases 1 of the clicked item |
| Sell 1 | Right-click | Sells 1 of the clicked item from inventory |
| Buy 64 | Shift + Left-click | Purchases a full stack (64) |
| Sell All | Shift + Right-click | Sells all of that item from inventory |
| Navigate Section | Left-click section icon | Opens that section's page |
| Go Back | Left-click arrow | Returns to main menu |
| Next/Prev Page | Left-click arrows | Paginates through items |

The handler completely cancels vanilla container behavior by overriding `clicked()`/`quickMoveStack()` and backing every display slot with `DisplaySlot` + `ShopDummyContainer`. This prevents players from actually picking up shop display items.

---

## 8. Core Subsystem: Auction House

The auction house is a peer-to-peer marketplace where players list items for sale, and other players can purchase them. Unlike the shop (which trades with the server economy), auctions transfer items and currency directly between players.

### 8.1 AuctionManager — Race-Condition-Free Controller

**File**: `com.solidus.auction.AuctionManager` (2,570 lines — the largest class in the mod; it now also owns the 2.2.0 bidding system, see `docs/FEATURES_TRADE_BIDDING.md`)

The auction house faces the most complex concurrency challenges in Solidus. Two players might attempt to purchase the same listing simultaneously, or a player might try to cancel a listing at the same moment another player buys it.

#### Single-Thread Executor Serialization

Like `SQLiteStorage`, `AuctionManager` uses a dedicated single-thread executor for all mutations. This eliminates the need for explicit locking:

```java
// All mutations go through the executor
private final ExecutorService auctionExecutor = Executors.newSingleThreadExecutor();

private <T> CompletableFuture<T> submitToExecutor(Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, auctionExecutor);
}
```

#### Anti-Dupe Protection

The most critical operation is purchasing a listing. Here's the protection strategy:

```
1. Verify listing exists and is ACTIVE
2. Verify buyer is not the seller (no self-purchase)
3. Verify buyer has sufficient balance (atomic subtractBalance)
4. Mark listing as SOLD (prevents double-purchase)
5. Give item to buyer
6. Credit seller (addBalance, works even if seller is offline)
7. Log transaction for both parties
8. Queue offline notification for seller
```

The key insight is that step 3 (subtract balance) is TOCTOU-safe (atomic check-and-deduct), and step 4 (mark as SOLD) happens within the same executor task, so no other operation can interleave.

#### Sort Orders

```java
public enum SortOrder {
    NEWEST,       // By listing time (descending)
    PRICE_LOW,    // By price (ascending)
    PRICE_HIGH,   // By price (descending)
    MATERIAL      // By material name (alphabetical)
}
```

#### Expiration Processing

Auction expiration is checked every 6000 ticks (5 minutes) via the `END_SERVER_TICK` event:

```java
ServerTickEvents.END_SERVER_TICK.register(server -> {
    tickCounter++;
    if (tickCounter >= AUCTION_EXPIRY_CHECK_INTERVAL) {
        tickCounter = 0;
        auctionManager.processExpiredListings();
    }
});
```

Expired listings have their status changed to `EXPIRED` in the database. The seller can then use `/ah collect` to retrieve their items.

#### MinecraftServer Injection

Fabric does not provide a static `MinecraftServer.getServer()` method. The `AuctionManager` needs the server instance to give items to players. This is injected via the `SERVER_STARTED` event:

```java
ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    auctionManager.setServer(server);
});
```

---

### 8.2 Auction Data Model

**Files**: `AuctionEntry.java`, `ListingStatus.java`

#### AuctionEntry (Java Record)

```java
public record AuctionEntry(
    UUID listingId,          // Unique listing identifier
    UUID sellerUuid,         // Seller's player UUID
    String sellerName,       // Seller's display name
    String materialName,     // Material registry key (e.g., "minecraft:diamond")
    int quantity,            // Stack size
    String itemNbt,          // Serialized NBT data (enchantments, custom names, etc.)
    double price,            // Listed price in S$
    long listedTimestamp,    // Epoch millis when listed
    long expireTimestamp,    // Epoch millis when listing expires
    ListingStatus status     // ACTIVE, SOLD, or EXPIRED
) {
    public static final long DEFAULT_DURATION_MS = 72 * 60 * 60 * 1000L;   // 72 hours
    public static final long MAX_DURATION_MS    = 168 * 60 * 60 * 1000L;   // 168 hours (7 days)
    public static final double MIN_LISTING_PRICE = 1.0;
    public static final double MAX_LISTING_PRICE = 10_000_000.0;
    public static final double LISTING_FEE_PERCENT = 0.02;                 // 2% fee
}
```

The choice of a Java `record` ensures immutability — once a listing is created, its core attributes cannot be modified. Only the `status` field changes (via database update), and the record is replaced with a new instance.

#### Listing Status Lifecycle

```
  ┌─────────┐     Player purchases     ┌──────────┐
  │ ACTIVE  │ ─────────────────────────▶│   SOLD   │
  └────┬────┘                           └──────────┘
       │
       │ 72 hours pass (no purchase)
       │
       ▼
  ┌──────────┐
  │ EXPIRED  │  → Seller collects items via /ah collect
  └──────────┘
```

#### Listing Fee

When a player lists an item, a 2% fee is charged (minimum 1 S$). This fee is non-refundable, even if the listing expires. The fee serves as a disincentive for spam listings and covers the economic cost of server-side storage.

---

### 8.3 Auction GUI Architecture

**Files**: `AuctionGUI.java`, `AuctionScreenHandler.java`, `AuctionDummyContainer.java`

The auction GUI follows the same virtual chest pattern as the shop:

```
┌─────────────────────────────────────────────────┐
│ [Auction House]  [Refresh]  [My Items]  []  []  │  Row 0: Header
│                              []  [X]             │
├─────────────────────────────────────────────────┤
│ [Item1] [Item2] [Item3] [Item4] [Item5] ...     │  Rows 1-5: Listings
│ [Item6] [Item7] [Item8] [Item9] [Item10] ...    │  (42 listing slots — slots 48/50/53 are reserved for navigation)
│ ...                                              │
├─────────────────────────────────────────────────┤
│ [←Prev] []  []  []  []  []  []  []  [Next→]     │  Row 6: Navigation
└─────────────────────────────────────────────────┘
```

Each listing item displays:
- The actual item (with NBT: enchantments, custom names, etc.)
- Lore showing: price, seller name, time remaining
- Color-coded expiry indicators (green > yellow > red)

#### Click Routing in AuctionScreenHandler

| Click Target | Action |
|-------------|--------|
| Auction item | Purchase the listing (with confirmation check) |
| Refresh button | Reload and redisplay current page |
| My Items button | Show only the player's own listings |
| Navigation arrows | Paginate through listings |
| Close button | Close the GUI |

---

## 9. Core Subsystem: Sell System

The sell system is the most technically complex GUI subsystem because, unlike the shop and auction (which are display-only), it must handle **real item placement** — players put actual items into the GUI for selling.

### 9.1 SellScreenHandler — Cursor-Based Item Movement

**File**: `com.solidus.sell.SellScreenHandler` (746 lines)

This is the most complex `ScreenHandler` in Solidus. It implements full cursor-based item movement for the sell GUI's input area, replicating vanilla Minecraft's container interaction behavior entirely in server-side code.

#### Why Custom Cursor Movement?

In vanilla Minecraft, when a player clicks a slot in a container:
1. The client sends a `ServerboundContainerClickPacket`
2. The server processes it in `AbstractContainerMenu.clicked()`
3. The server updates the cursor and slot state
4. The client and server synchronize

For shop and auction GUIs, Solidus cancels this entirely inside `PacketHandler` (which intercepts the packet and never lets vanilla `clicked()` run) because no real items should move. But for the sell GUI, players **must** be able to place items into input slots. The solution: implement a custom `clicked()` method that handles item movement for input slots while blocking it for UI slots.

#### Slot Layout

```
Slot 0:     Info item (ReadOnlySlot)
Slot 1-7:   Glass pane fillers (ReadOnlySlot)
Slot 8:     Close button (ReadOnlySlot)
Slots 9-53: Input area (player can place items here)
```

#### Click Processing Flow

```
1. ServerPlayerEntityMixin intercepts handleContainerClick
2. PacketHandler.handleContainerClick() is called
3. For SellScreenHandler, the Mixin does NOT cancel (unlike shop/auction)
4. PacketHandler routes the click to SellScreenHandler (rate-limited, then a full resync)
5. SellScreenHandler.clicked() is called
6. Custom logic determines:
   - If UI slot (0-8): ignore click
   - If input slot (9-53): handle item movement
   - If player inventory: allow normal interaction
7. On container close: process all items in input area
```

#### Cursor State Synchronization

The most challenging aspect is keeping the client's cursor state synchronized with the server. If they desync, players see "ghost items" — items that appear to be in their cursor but don't actually exist.

```java
// After every cursor-modifying operation:
player.connection.send(new ClientboundContainerSetSlotPacket(
    -1,       // Window ID -1 = cursor slot
    0,        // State revision
    cursorItem // Current cursor state
));
```

This packet explicitly tells the client what's on its cursor, overriding any client-side prediction.

#### Item Processing on Close

When the sell GUI is closed (either by pressing Escape or the close button), all items in input slots 9-53 are processed:

```
For each non-empty slot:
  1. Check if the item has a sell price in shop.json
  2. If sellable:
     a. Calculate sell price × quantity
     b. Add currency to player's balance
     c. Log SHOP_SELL transaction
     d. Remove item from inventory
  3. If NOT sellable:
     a. Return item to player's inventory
     b. If inventory is full, drop at player's location
  4. Special case: Shulker boxes
     a. Inspect contents using BlockItem.getBlockEntityData()
     b. Price each contained item individually
     c. Return unsellable contents to player
```

---

### 9.2 Sell Flow: Open → Place → Close → Process

```
Player executes /sell gui
         │
         ▼
┌─────────────────────────────────────┐
│ SellGUI.openSellGUI(player, shopMgr)│
│  - Creates SellContainer (real)     │
│  - Creates SellScreenHandler        │
│  - Opens chest menu for player      │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Player places items in slots 9-53   │
│  - Custom clicked() handles cursor  │
│  - ReadOnlySlot blocks UI slots     │
│  - Cursor sync packets sent         │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Player closes GUI                   │
│  - removed() callback fires         │
│  - Process all items in input area  │
│  - Sell sellable items              │
│  - Return unsellable items          │
│  - Handle shulker box contents      │
│  - Send summary message to player   │
└─────────────────────────────────────┘
```

---

## 10. Cross-Cutting: Networking & Packet Handling

The networking layer is the bridge between vanilla Minecraft's container system and Solidus's custom GUI logic. It intercepts container click packets before vanilla processing, applies rate limiting, and routes clicks to the appropriate Solidus handler.

### 10.1 ServerPlayerEntityMixin — Packet Interception

**File**: `com.solidus.mixin.ServerPlayerEntityMixin`

This Mixin injects into `ServerGamePacketListenerImpl.handleContainerClick()` at `@At("HEAD")`, allowing Solidus to intercept every container click before vanilla processes it:

```java
@Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
private void onContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
    PacketHandler packetHandler = SolidusMod.getPacketHandler();
    if (packetHandler == null) return;

    // Desync guard (audit 2.1.3): only process clicks whose packet
    // containerId matches the currently open menu.
    if (player.containerMenu == null
        || packet.containerId() != player.containerMenu.containerId) {
        return;
    }

    boolean handled = packetHandler.handleContainerClick(
        player, packet.slotNum(), packet.buttonNum(), packet.containerInput());

    if (handled) {
        ci.cancel();  // Prevent vanilla processing.
        // Resync policy lives in PacketHandler: broadcastFullState() after
        // every PROCESSED click; a throttled full resync (max 1 per 200ms)
        // for clicks dropped by the rate limiter.
    }
}
```

**Critical**: `ci.cancel()` prevents vanilla from processing the click. A plain `broadcastChanges()` was NOT enough (the 2.1.0 ghost-item bug): it only sends slots whose server state changed, and a rejected click changes nothing — so the client's optimistic prediction survived. `broadcastFullState()` always resends the complete container state (plus the carried stack), erasing any ghost item in the same tick it is created (the PR#13 guarantee).

### 10.2 ScreenHandler-Level Protections (No Second Mixin)

**Note (2.2.0 audit):** an older revision of this document described a
`ScreenHandlerMixin` safety net. **That mixin does not exist** —
`solidus.mixins.json` registers exactly one mixin,
`ServerPlayerEntityMixin`. Vanilla `clicked()` never runs for Solidus display
GUIs because the packet is consumed one layer above (Mixin + PacketHandler), and
the handlers themselves are hardened:

- **Ownership check** — every ScreenHandler (`Shop`, `Auction`, `Trade`, `Sell`)
  rejects clicks from any actor other than the owning player (defensive, audit 2.1.3).
- **Click-type whitelisting** — e.g. `AuctionScreenHandler` accepts only a plain
  `PICKUP` left-click for purchases (right-click on bid-enabled listings opens the
  bid chat prompt); SWAP/THROW/drag gestures are ignored.
- **`quickMoveStack()` returns `ItemStack.EMPTY`** in all display handlers —
  shift-click cannot move anything.
- **`DisplaySlot`** (see §12) freezes the slots themselves.
- Handlers that DO move real items (`SellScreenHandler`, `TradeScreenHandler`)
  implement manual cursor movement and are routed through `PacketHandler` like
  every other Solidus GUI.

### 10.3 PacketHandler — Click Routing Gateway

**File**: `com.solidus.networking.PacketHandler`

`PacketHandler` receives intercepted clicks and routes them to the appropriate handler based on the player's currently open container:

```
Incoming Click
      │
      ▼
┌───────────────────────────────┐
│ SCOPE CHECK FIRST (audit 2.1.3)│──▶ Not a Solidus menu? → return false
│ Only Solidus GUIs are limited  │    (vanilla containers pass untouched)
└────────┬──────────────────────┘
         ▼
┌─────────────────┐
│ RateLimiter Check│──▶ Dropped if < 150ms since last click
│ (150ms, per GUI) │    (+ at most ONE throttled full resync per 200ms,
└────────┬────────┘      so floods cannot amplify into multi-KB broadcasts)
         │ Passed
         ▼
┌─────────────────────────────────────────┐
│ Route by current ScreenHandler type      │
│                                          │
│  ShopScreenHandler?    → shop.clicked()  │
│  AuctionScreenHandler? → auction.clicked()│
│  SellScreenHandler?    → sell.clicked()  │ (manual item movement)
│  TradeScreenHandler?   → trade.clicked() │ (manual item movement, 2.2.0+)
│                                          │
│  After EVERY processed click:            │
│  broadcastFullState() (anti-ghost, PR#13)│
│                                          │
│  Other?              → Return false      │
└─────────────────────────────────────────┘
```

The `register()` hook also cleans up on disconnect: rate-limiter entry, drop-resync
bookkeeping, pending shop buy/sell locks, and sell-GUI item recovery (vanilla never
invokes `removed()` for a menu open at disconnect — the handler runs it explicitly
so placed items are sold/returned instead of being lost).

### 10.4 RateLimiter — Click & Transfer Cooldowns

**File**: `com.solidus.networking.RateLimiter`

A per-player cooldown system with **two independent buckets** — a GUI click never consumes a transfer slot and vice versa:

| Bucket | Constant | Interval | Guarded path |
|--------|----------|----------|--------------|
| Clicks | `MIN_CLICK_INTERVAL_MS` | 150ms | Container click packets (shop/auction GUIs) — silently dropped |
| Transfers | `MIN_PAY_INTERVAL_MS` | 1,000ms | `/pay` (online + offline) — friendly wait message with remaining seconds |

Both buckets share one atomic acquire primitive:

```java
private boolean tryAcquire(ConcurrentHashMap<UUID, Long> timestamps,
                           UUID playerUuid, long minIntervalMs) {
    // ...
    timestamps.compute(playerUuid, (uuid, last) -> {
        if (last == null)          { allowed[0] = true;  return now; }   // first action
        if (now - last < interval) { allowed[0] = false; return last; }  // too soon — keep old stamp
        allowed[0] = true;         return now;                           // allowed — refresh stamp
    });
    return allowed[0];
}
```

The `compute()` method is atomic — it guarantees that the check and update happen as a single operation, preventing race conditions where two simultaneous clicks (or payment macros) both pass the check.

The transfer bucket exists because each `/pay` writes two ledger rows, sends two messages and invokes registered hooks — a command macro flooding `/pay` would pollute the audit trail and press SQLite even from an unmodified client.

**Stale Entry Cleanup**: A periodic cleanup removes entries older than 5 minutes from both buckets, preventing memory leaks from players who disconnect without triggering the cleanup event. `removePlayer()` (on disconnect) and `clear()` (on shutdown) sweep both buckets.

---

## 11. Cross-Cutting: Permission System

Solidus implements a fine-grained permission system that integrates with LuckPerms when available and falls back to configurable OP levels when it's not.

### 11.1 SolidusPermissions — Permission Node Registry

**File**: `com.solidus.api.SolidusPermissions`

All permission nodes follow the convention `solidus.<module>.<category>.<action>`:

Core command nodes follow `solidus.command.<command>[.<sub>]` (all default to
OP 0 unless noted). The authoritative list lives in `SolidusPermissions`:

| Permission | Default OP Level | Controls |
|-----------|-----------------|----------|
| `solidus.command.balance` | 0 | `/balance`, `/bal` |
| `solidus.command.pay` | 0 | `/pay <player> <amount>` |
| `solidus.command.pay.offline` | 0 | `/pay offline <name> <amount>` |
| `solidus.command.baltop` | 0 | `/baltop` |
| `solidus.command.shop` | 0 | `/shop` |
| `solidus.command.shop.search` | 0 | `/shop search <query>` |
| `solidus.command.shop.reload` | **2** | `/shop reload` |
| `solidus.command.sell` | 0 | `/sell gui`, `/sell all [item]` |
| `solidus.command.auction` | 0 | `/ah` |
| `solidus.command.auction.sell` | 0 | `/ah sell <price> [startbid]` |
| `solidus.command.auction.bid` | 0 | `/ah bid <uuid> <amount>` + GUI right-click bid (2.2.0+) |
| `solidus.command.auction.collect` | 0 | `/ah collect` |
| `solidus.command.auction.cancel` | 0 | `/ah cancel <uuid>` |
| `solidus.command.auction.sort` | 0 | `/ah sort <order>` |
| `solidus.command.trade` | 0 | the whole `/trade` family (2.2.0+) |
| `solidus.command.transactions` | 0 | `/transactions [page]` |
| `solidus.command.transactions.export` | 0 | `/transactions export [days]` |
| `solidus.command.transactions.exportall` | **2** | `/transactions exportall [days]` |

Companion-module nodes (declared here for ecosystem-wide defaults): analytics
viewing defaults to OP 2 and analytics management (`snapshot`, `export`,
`dashboard.manage`, `license`, `fingerprint`) to OP 3; territory `land`/`claim`
default to OP 0 with `admin`/`bypass` at OP 2; governance viewing defaults to
OP 2 and management (tax, intervention, recovery, limits.set, event.manage,
policy.manage, rules.manage, simulation, license, fingerprint) to OP 3.
Unknown nodes fall back to OP 2 (safe default).

The `getDefaultOpLevel(String permission)` method provides fallback OP levels for the permission configuration file. This ensures that even without LuckPerms, server admins can control access.

### 11.2 PermissionChecker — Unified Checking with LuckPerms

**File**: `com.solidus.api.PermissionChecker`

`PermissionChecker` implements a two-tier checking strategy:

```
┌───────────────────────────────────────┐
│         PermissionChecker              │
│                                        │
│  1. Try LuckPerms (via reflection)     │
│     └─ Class.forName("net.luckperms...")│
│     └─ If available: use LP API        │
│     └─ Supports wildcards              │
│                                        │
│  2. Fall back to OP levels             │
│     └─ Read from PermissionConfig      │
│     └─ Compare player's OP level       │
│     └─ No wildcard support             │
└───────────────────────────────────────┘
```

**LuckPerms Integration via Reflection**: Rather than compile against LuckPerms (which would create a hard dependency), `PermissionChecker` uses reflection to call LuckPerms methods. This means:

- Solidus works without LuckPerms installed
- If LuckPerms is present, it's automatically used
- No version coupling with LuckPerms releases

The reflection chain:
```java
Class<?> apiClass = Class.forName("net.luckperms.api.LuckPermsProvider");
Method getMethod = apiClass.getMethod("get");
Object luckPermsApi = getMethod.invoke(null);
Method getUserMethod = luckPermsApi.getClass().getMethod("getUserManager");
// ... chain continues to check permission
```

**Brigadier Integration**: `PermissionChecker` provides a `require(String permission, int defaultOpLevel)` method that returns a `Predicate<CommandSourceStack>` for use with Brigadier's `.requires()`:

```java
Commands.literal("ah")
    .requires(PermissionChecker.require(SolidusPermissions.AUCTION_VIEW, 0))
    .executes(context -> { ... })
```

### 11.3 PermissionConfig — OP-Level Fallback Configuration

**File**: `com.solidus.api.PermissionConfig`

When LuckPerms is not installed, `PermissionConfig` loads a `permissions.json` file from `config/solidus/` that maps permission nodes to minimum OP levels:

```json
{
  "solidus.command.balance": 0,
  "solidus.command.pay": 0,
  "solidus.command.auction": 0,
  "solidus.analytics.dashboard": 2,
  "solidus.governance.audit": 2
}
```

**Auto-Generation**: If `permissions.json` doesn't exist, it's automatically created with default values from `SolidusPermissions.getDefaultOpLevel()`. This ensures the file always exists and is up-to-date with the current version's permission nodes.

---

## 12. Cross-Cutting: Virtual GUI Architecture

Solidus's virtual GUI system is one of its most distinctive architectural features. It renders interactive menus using vanilla Minecraft's chest inventory system, requiring zero client-side modifications.

### 12.1 The DummyContainer Pattern

For display-only GUIs (shop, auction), Solidus uses `DummyContainer` implementations that extend `Container` but block all mutations:

```java
public class ShopDummyContainer implements Container {
    private final ItemStack[] items = new ItemStack[54];

    // Reading: allowed
    @Override public ItemStack getItem(int slot) { return items[slot]; }
    @Override public int getContainerSize() { return items.length; }

    // Writing: BLOCKED
    @Override public void setItem(int slot, ItemStack stack) { /* BLOCK */ }
    @Override public ItemStack removeItem(int slot, int amount) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ItemStack.EMPTY; }
}
```

This means even if a click somehow reaches the container (bypassing all other protection layers), the container will not store or remove any items. The player's real inventory remains untouched.

**Contrast with SellContainer**: The sell GUI uses a **real** container (`SellContainer`) that actually stores items placed by the player. This is necessary because the sell GUI needs to track what items the player wants to sell.

### 12.2 Ghost Item Prevention — Defense-in-Depth

"Ghost items" are items that appear on the client's screen but don't exist on the server. They occur when the client processes a click (predicting the server will agree) but the server rejects it. Solidus prevents this through multiple layers:

```
Layer 1: RateLimiter (Solidus GUIs only)
  └─ Rejects rapid-fire clicks before they reach any handler

Layer 2: ServerPlayerEntityMixin (the ONLY mixin)
  └─ Intercepts handleContainerClick at HEAD (with containerId desync guard)
  └─ If Solidus handles the click → cancel vanilla processing

Layer 3: ScreenHandler hardening
  └─ Ownership checks, click-type whitelisting, quickMoveStack() blocked

Layer 4: DisplaySlot
  └─ mayPlace/mayPickup return false; set() is a no-op — vanilla slot logic
     can never insert, merge, swap, split or throw display items

Layer 5: DummyContainer
  └─ Even if everything above fails, the container blocks all mutations

Layer 6: broadcastFullState() resyncs
  └─ After EVERY processed click (throttled for dropped ones): the client's
     optimistic prediction is erased in the same tick it was created
```

---

## 13. Public API & Integration Guide

### 13.1 SolidusAPI — Stable Public API

**File**: `com.solidus.api.SolidusAPI`

`SolidusAPI` is the **only** class that external mods should depend on. Internal classes (`EconomyEngine`, `BalanceManager`, `SQLiteStorage`) may change between versions without notice, but the methods defined in `SolidusAPI` are guaranteed to remain stable across minor and patch releases.

#### API Contract

| Method | Returns | Description |
|--------|---------|-------------|
| `getBalance(ServerPlayer)` | `CompletableFuture<Double>` | Get online player's balance |
| `getBalanceOffline(UUID, String)` | `CompletableFuture<Double>` | Get offline player's balance |
| `addBalance(ServerPlayer, double)` | `CompletableFuture<Double>` | Add to online player's balance |
| `addBalanceOffline(UUID, String, double)` | `CompletableFuture<Double>` | Add to offline player's balance |
| `subtractBalance(ServerPlayer, double)` | `CompletableFuture<Double>` | Deduct from online player (returns -1 if insufficient) |
| `subtractBalanceOffline(UUID, String, double)` | `CompletableFuture<Double>` | Deduct from offline player |
| `hasSufficientBalance(ServerPlayer, double)` | `CompletableFuture<Boolean>` | Check if player can afford |
| `transfer(ServerPlayer, ServerPlayer, double)` | `CompletableFuture<TransferResult>` | Atomic online transfer |
| `transferOffline(UUID, String, UUID, String, double)` | `CompletableFuture<TransferResult>` | Atomic offline transfer |
| `getTopBalances(int, int)` | `CompletableFuture<List<BalanceEntry>>` | Paged leaderboard query (offset 10 → ranks start at 11) |
| `getBalanceEntryCount()` | `CompletableFuture<Integer>` | Count of economy entries (page footers) |
| `getEconomyStats()` | `CompletableFuture<EconomyStats>` | Aggregates: count, mean, supply, Gini |
| `getTransactionLog()` | `TransactionLog` | Access to transaction logging |
| `registerTransactionHook(hook)` | `boolean` | Register an economy transaction hook (false if name taken) |
| `unregisterTransactionHook(hook)` | `boolean` | Remove a previously registered hook |
| `getRegisteredHookCount()` | `int` | Number of active hooks (diagnostics) |
| `isAvailable()` | `boolean` | Check if API is initialized |

#### Thread Safety

All `SolidusAPI` methods return `CompletableFuture` and execute asynchronously on Solidus's dedicated database worker thread. Callers on the server tick thread **must** use `.thenAccept()` + `server.execute()` for any UI or game-state updates:

```java
// CORRECT — safe callback on tick thread
api.getBalance(player).thenAccept(balance -> {
    server.execute(() -> {
        player.sendSystemMessage(Component.literal("Balance: " + balance));
    });
});

// WRONG — may execute on DB thread, causing ConcurrentModificationException
api.getBalance(player).thenAccept(balance -> {
    player.sendSystemMessage(Component.literal("Balance: " + balance));
});
```

### 13.2 Reflection-Based Integration (Zero Dependency)

External mods can integrate with Solidus without any compile-time dependency using pure Java reflection:

```java
public class MyCombatMod {
    private Object solidusApi;
    private Class<?> apiClass;

    public void onModInit() {
        // 1. Check if Solidus is loaded
        if (!FabricLoader.getInstance().isModLoaded("solidus")) return;

        try {
            // 2. Get the API instance via reflection
            apiClass = Class.forName("com.solidus.api.SolidusAPI");
            Method getInstance = apiClass.getMethod("getInstance");
            solidusApi = getInstance.invoke(null);

            if (solidusApi == null) {
                LOGGER.warn("Solidus is loaded but API not yet initialized");
                return;
            }

            // 3. Verify API is ready
            Method isAvailable = apiClass.getMethod("isAvailable");
            boolean ready = (boolean) isAvailable.invoke(null);
            if (!ready) {
                LOGGER.warn("Solidus API not ready");
                return;
            }

            LOGGER.info("Solidus integration ready!");
        } catch (Exception e) {
            LOGGER.error("Failed to integrate with Solidus", e);
        }
    }

    public void applyDeathPenalty(ServerPlayer victim, ServerPlayer killer) {
        try {
            Method getBalance = apiClass.getMethod("getBalance", ServerPlayer.class);
            CompletableFuture<Double> future =
                (CompletableFuture<Double>) getBalance.invoke(solidusApi, victim);

            future.thenAccept(balance -> {
                double penalty = balance * 0.15;
                try {
                    Method subtract = apiClass.getMethod(
                        "subtractBalance", ServerPlayer.class, double.class);
                    subtract.invoke(solidusApi, victim, penalty);

                    Method add = apiClass.getMethod(
                        "addBalance", ServerPlayer.class, double.class);
                    add.invoke(solidusApi, killer, penalty);
                } catch (Exception e) {
                    LOGGER.error("Failed to apply death penalty", e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Failed to get balance for death penalty", e);
        }
    }
}
```

### 13.3 Compile-Time Integration

If you prefer type-safe integration, add solidus-core as a dependency in your `build.gradle`:

```groovy
dependencies {
    modImplementation "com.github.MOHD-Gs15:solidus-core:v2.2.0"
}
```

Then use the API directly:

```java
SolidusAPI api = SolidusAPI.getInstance();
if (api == null) return;

api.subtractBalance(victim, penalty).thenAccept(newBalance -> {
    if (newBalance >= 0) {
        api.addBalance(killer, penalty);
    }
});
```

### 13.4 SolidusIntegration — Reference Implementation

**File**: `com.solidus.api.SolidusIntegration`

Solidus ships with a complete reference implementation showing how an external mod would integrate. This class is **not** used internally — it exists purely as documentation-by-example:

- `applyDeathPenalty()` — Deducts a percentage of the victim's balance and gives it to the killer
- `applyRefundWithSafety()` — Deducts with a refund safety check (if deduction fails, don't proceed)
- Custom transaction logging — Shows how external mods can add their own transaction types to the audit trail

### 13.5 SolidusTransactionHook — Economy Interception Hooks (new in 2.1.0)

**Files**: `com.solidus.api.SolidusTransactionHook` (interface) · `com.solidus.api.EconomyHooks` (registry, internal) · `com.solidus.api.SolidusAPI` (registration)

Companion mods can intercept **every money-movement point** in Solidus — before it happens (veto) and after it settles (notification) — without forking or patching Core. This is the mechanism Solidus Governance uses to enforce transfer limits, trading locks, account freezes and taxes.

#### Hooked Transaction Points

| Flow | Veto hook (pre-transaction) | Notification hook (post-settlement) |
|------|-----------------------------|--------------------------------------|
| `/pay` + API transfers (online + offline) | `allowTransfer(sender, receiver, amount)` | `afterTransfer(sender, receiver, amount)` |
| Auction listing creation | `allowAuctionListing(seller, price)` | `afterAuctionListing(seller, price, fee)` |
| Auction purchase | `allowAuctionPurchase(buyer, price)` | `afterAuctionSale(seller, buyer, price)` |
| Shop purchase (GUI) | `allowShopPurchase(player, cost)` | `afterShopPurchase(player, cost)` |
| Shop sell (GUI, `/sell all`, Sell GUI close) | `allowShopSell(player)` | `afterShopSell(player, payout)` |

Veto hooks run **before any money or item moves** — a denial aborts the transaction cleanly: balances untouched, items stay where they are, and the player sees the hook's denial reason verbatim. Notification hooks run **after full settlement** and are intended for limit recording, tax collection, statistics and alerts. Batch sell flows pass no amount to the veto (the exact payout is not known yet) — use `afterShopSell` to observe the actual payout.

#### Dispatch Rules (`EconomyHooks`)

| Rule | Behavior |
|------|----------|
| First denial wins | The first veto denial is returned; its reason is surfaced to the player |
| Reason normalization | A denial with a null/blank reason gets the generic fallback message |
| Fail-open | A hook that throws is logged and skipped — it can never wedge the economy |
| Duplicate protection | Registration rejects a second hook with the same `name()` |
| Thread safety | `CopyOnWriteArrayList` registry — hooks may register/unregister while transactions flow |

#### Threading Contract

- **Veto hooks** run synchronously on the caller's thread (server tick thread, or the auction executor for purchases). They must be fast, non-blocking, in-memory checks. They must **not** synchronously call back into Solidus balance APIs — that queues work on the economy executor and risks deadlock.
- **Notification hooks** run after settlement and may dispatch async work (e.g., chain `SolidusAPI` futures) but must never block the calling thread.

#### Compile-Time Usage Example

All interface methods are `default`, so a hook implements only what it needs — `name()` is the only abstract method. The `Decision` record provides `Decision.ALLOW` and `Decision.deny(String reason)`:

```java
public class MyTransferGuard implements SolidusTransactionHook {
    @Override public String name() { return "my-transfer-guard"; }

    @Override
    public Decision allowTransfer(UUID sender, String senderName,
                                  UUID receiver, String receiverName,
                                  double amount) {
        return amount >= 1_000_000
            ? Decision.deny("Transfers over S$1,000,000 require staff approval.")
            : Decision.ALLOW;
    }

    @Override
    public void afterTransfer(UUID sender, String senderName,
                              UUID receiver, String receiverName,
                              double amount) {
        stats.record(sender, receiver, amount);  // async-safe, never block
    }
}

SolidusAPI.getInstance().registerTransactionHook(new MyTransferGuard());
```

#### Reflection-Based Registration (Zero Dependency)

```java
Class<?> hookItf = Class.forName("com.solidus.api.SolidusTransactionHook");
Object proxy = Proxy.newProxyInstance(hookItf.getClassLoader(),
        new Class<?>[]{ hookItf }, myInvocationHandler);  // fall back to generic defaults:
                                                          // ALLOW / no-op / 0.0
Class<?> apiClass = Class.forName("com.solidus.api.SolidusAPI");
Object api = apiClass.getMethod("getInstance").invoke(null);
apiClass.getMethod("registerTransactionHook", hookItf).invoke(api, proxy);
```

`Decision.ALLOW` is a static field on the nested `Decision` class (`SolidusTransactionHook$Decision`); a denial is built by invoking `deny` with the reason string. Call `unregisterTransactionHook` (or the reflected equivalent) on your mod's `SERVER_STOPPING` to release the hook.

#### Reference Consumer

Solidus Governance (1.2.0+) registers `CoreHookBridge` through exactly this contract: `allowTransfer` enforces daily transfer limits + trading lock + account freezes; `afterTransfer` / `afterAuctionSale` / `afterShopPurchase` collect configured taxes into the treasury account; listing/purchase vetoes enforce auction limits.

---

## 14. Thread Safety Model

Understanding Solidus's thread safety model is critical for anyone building integrations.

### Thread Architecture

```
┌─────────────────────────────────────────────────┐
│              Minecraft Server Main Thread         │
│              (Tick Thread)                        │
│                                                   │
│  - Processes player commands                      │
│  - Handles player join/disconnect                 │
│  - Triggers auction expiry checks                 │
│  - Calls async operations and chains callbacks    │
│                                                   │
│  NEVER: blocks on DB operations                   │
│  NEVER: directly modifies shared mutable state    │
└───────────────────┬───────────────────────────────┘
                    │ CompletableFuture chains
                    │ .thenAccept() + server.execute()
                    ▼
┌─────────────────────────────────────────────────┐
│           Economy Executor (single thread)        │
│                                                   │
│  - All balance mutations (add/subtract/set)       │
│  - All SQLite writes                              │
│  - Cache updates (ConcurrentHashMap)              │
│  - Transfer operations (deduct + add)             │
│                                                   │
│  GUARANTEE: operations are serialized             │
│  GUARANTEE: no two operations run concurrently    │
└───────────────────┬───────────────────────────────┘
                    │ Separate executor
                    ▼
┌─────────────────────────────────────────────────┐
│           Auction Executor (single thread)        │
│                                                   │
│  - All auction listing mutations                  │
│  - Purchase, cancel, expire operations            │
│  - Auction SQLite writes                          │
│                                                   │
│  GUARANTEE: auction operations are serialized     │
└─────────────────────────────────────────────────┘
```

### Safe Patterns

| Pattern | Safe? | Explanation |
|---------|-------|-------------|
| `api.getBalance(player).thenAccept(...)` | Yes | Read from cache (instant) |
| `api.addBalance(player, amt).thenAccept(...)` | Yes | Async on economy executor |
| `api.subtractBalance(player, amt).join()` | **NO** | `.join()` blocks the tick thread |
| `api.transfer(a, b, amt).thenAccept(bal -> player.sendSystemMessage(...))` | **NO** | Callback may run on DB thread |
| `api.transfer(a, b, amt).thenAccept(bal -> server.execute(() -> player.sendSystemMessage(...)))` | Yes | Safely scheduled on tick thread |

### ConcurrentHashMap Usage

`ConcurrentHashMap` is used for the balance cache and name cache. It provides:

- **Thread-safe reads**: Multiple threads can read simultaneously without locking
- **Atomic mutations**: `put()`, `compute()`, and `replace()` are atomic
- **Weak consistency for iteration**: Iterators see elements that existed at iteration start

The single-thread executor ensures that **mutations** are never concurrent, but reads from the tick thread (via `getBalance`) happen concurrently with executor mutations. This is safe because:

1. `ConcurrentHashMap.get()` returns the most recently completed `put()` value
2. The executor updates the cache **before** the CompletableFuture completes
3. The calling thread always sees the updated value

---

## 15. Database Schema

Solidus Core owns two SQLite databases, both stored in `config/solidus/`:

- `economy.db` — balances, transaction ledger, offline notifications
- `auctions.db` — auction listings and the settled-listing archive

Both run in WAL mode and are written exclusively through their single-threaded
executors (`Solidus-Economy-Worker` / `Solidus-Auction-Worker`), so external
readers (solidus-analytics, Governance recovery, or any plain SQLite client)
can query them concurrently without blocking the server.

### Economy Database: `config/solidus/economy.db`

#### Table: `player_balances`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `uuid` | TEXT | PRIMARY KEY NOT NULL | Player UUID (hyphenated) |
| `player_name` | TEXT | NOT NULL | Last known player name |
| `balance` | REAL | NOT NULL DEFAULT 0.0 | Current balance (S$) |
| `last_updated` | INTEGER | NOT NULL | Last mutation (epoch millis) |

```sql
CREATE TABLE IF NOT EXISTS player_balances (
    uuid TEXT PRIMARY KEY NOT NULL,
    player_name TEXT NOT NULL,
    balance REAL NOT NULL DEFAULT 0.0,
    last_updated INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_balance_rank
    ON player_balances (balance DESC);
```

**Write pattern**: every mutation is a modern UPSERT
(`INSERT ... ON CONFLICT(uuid) DO UPDATE SET ...`) executed on the economy
executor — atomic account creation, no separate existence check. The column
default is `0.0`; new players are inserted by the application layer with the
configured starting balance (`shop.json` → `startingBalance`, default 500).
`idx_balance_rank (balance DESC)` powers `/baltop` ordering and pagination.

#### Table: `transaction_log`

Append-only ledger with one row per affected party: a `/pay` writes a
`PAY_SEND` row (sender perspective) plus a `PAY_RECEIVE` row (receiver
perspective), and an auction sale writes `AUCTION_BOUGHT` + `AUCTION_SOLD`
rows. Consumers that measure money movement must count one side only —
solidus-analytics, for example, excludes the mirror rows from volume.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Ledger ID (also solidus-analytics' polling cursor) |
| `timestamp` | INTEGER | NOT NULL | Epoch millis |
| `type` | TEXT | NOT NULL | Transaction type (TEXT enum, see below) |
| `player_uuid` | TEXT | NOT NULL | Primary party UUID |
| `player_name` | TEXT | NOT NULL | Primary party name (last known) |
| `target_uuid` | TEXT | | Counterparty UUID (transfers/auctions) |
| `target_name` | TEXT | | Counterparty name |
| `amount` | REAL | NOT NULL | Signed amount from `player_uuid`'s perspective |
| `item_material` | TEXT | | Material registry key (item flows only) |
| `item_quantity` | INTEGER | | Stack size (item flows only) |
| `description` | TEXT | | Human-readable description |

```sql
CREATE TABLE IF NOT EXISTS transaction_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    type TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    player_name TEXT NOT NULL,
    target_uuid TEXT,
    target_name TEXT,
    amount REAL NOT NULL,
    item_material TEXT,
    item_quantity INTEGER,
    description TEXT
);
CREATE INDEX IF NOT EXISTS idx_transaction_player
    ON transaction_log (player_uuid, timestamp DESC);
```

`type` values (TEXT, not numeric codes): `SHOP_BUY`, `SHOP_SELL`,
`AUCTION_LIST`, `AUCTION_SOLD`, `AUCTION_BOUGHT`, `AUCTION_EXPIRED`,
`BID_PLACED`, `BID_REFUNDED`, `AUCTION_WON`, `TRADE_SEND`, `TRADE_RECEIVE`,
`PAY_SEND`, `PAY_RECEIVE`, `DEATH_PENALTY`, `DEATH_REWARD`.

`idx_transaction_player (player_uuid, timestamp DESC)` serves `/transactions`
pagination (`getTransactions` with `LIMIT ? OFFSET ?`) and the CSV exports
(`getTransactionsSince` / `getAllTransactionsSince`).

#### Table: `pending_notifications`

Offline delivery queue: a row is inserted when a transaction benefits a
player who is currently offline (e.g. an auction seller being paid), delivered
as chat messages on next login, then deleted.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Notification ID |
| `timestamp` | INTEGER | NOT NULL | Queued time (epoch millis) |
| `player_uuid` | TEXT | NOT NULL | Recipient player UUID |
| `message` | TEXT | NOT NULL | Pre-rendered chat message |

```sql
CREATE TABLE IF NOT EXISTS pending_notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    player_uuid TEXT NOT NULL,
    message TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notifications_player
    ON pending_notifications (player_uuid);
```

### Auction Database: `config/solidus/auctions.db`

#### Table: `auction_listings`

Live listing queue. A row leaves this table only after being copied into
`auction_sold_history` — the archive insert and the delete run inside one SQL
transaction, so a failed ledger write can never erase the only record of a
completed sale.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `listing_id` | TEXT | PRIMARY KEY NOT NULL | Listing UUID (hyphenated) |
| `seller_uuid` | TEXT | NOT NULL | Seller's player UUID |
| `seller_name` | TEXT | NOT NULL | Seller's display name |
| `material_name` | TEXT | NOT NULL | Material registry key |
| `quantity` | INTEGER | NOT NULL | Stack size |
| `item_nbt` | TEXT | | Full serialized item data |
| `price` | REAL | NOT NULL | Buy-now price (S$) |
| `listed_timestamp` | INTEGER | NOT NULL | Listed time (epoch millis) |
| `expire_timestamp` | INTEGER | NOT NULL | Expiry time (epoch millis) |
| `status` | INTEGER | NOT NULL DEFAULT 0 | 0=ACTIVE, 1=SOLD (settlement in flight), 2=EXPIRED (awaiting seller) |

```sql
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
);
CREATE INDEX IF NOT EXISTS idx_active_listings
    ON auction_listings (status, expire_timestamp);
```

**Status lifecycle**: `0 → 1` when a buyer pays (the row is then archived and
deleted once settlement completes); `0 → 2` when the listing expires and is
awaiting seller collection. If a crash leaves an orphaned `status = 1` row,
startup reconciliation either archives it as a completed sale (buyer
attributed from a matching `AUCTION_SOLD` ledger entry) or safely re-lists it
(`status = 0`) when no payment ever moved. `idx_active_listings
(status, expire_timestamp)` serves browse, expiry scans, and the guarded
claim statements.

#### Table: `auction_sold_history`

Append-only archive of every settled listing. `item_nbt` is intentionally not
archived: audit and analytics need material/quantity/price, not serialized
item blobs.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `listing_id` | TEXT | PRIMARY KEY NOT NULL | Original listing UUID |
| `seller_uuid` | TEXT | NOT NULL | Seller's player UUID |
| `seller_name` | TEXT | NOT NULL | Seller's display name |
| `material_name` | TEXT | NOT NULL | Material registry key |
| `quantity` | INTEGER | NOT NULL | Stack size |
| `price` | REAL | NOT NULL | Settled price (S$) |
| `buyer_uuid` | TEXT | | Buyer UUID (null for non-sale settlements) |
| `buyer_name` | TEXT | | Buyer display name |
| `listed_timestamp` | INTEGER | NOT NULL | Original listing time (epoch millis) |
| `settled_timestamp` | INTEGER | NOT NULL | Settlement time (epoch millis) |
| `settled_reason` | TEXT | NOT NULL | Settlement cause (see below) |

```sql
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
);
CREATE INDEX IF NOT EXISTS idx_sold_history_time
    ON auction_sold_history (settled_timestamp DESC);
```

`settled_reason` values: `SOLD` (buy-now), `WON` (bidding auction expired with
a winning bid, buyer attributed), `EXPIRED_RETURN`, `EXPIRED_COLLECT`,
`CANCELLED`, `CORRUPT` (the row's item data could not be deserialized —
undeliverable).

#### Table: `auction_bid_state` (2.2.0)

Bid state for bidding-enabled listings — one optional row per listing, keyed by
listing id. A listing WITHOUT a row is a buy-now-only listing; the feature is
purely additive and `AuctionEntry` is untouched.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `listing_id` | TEXT | PRIMARY KEY NOT NULL | Listing UUID (hyphenated) |
| `start_price` | REAL | NOT NULL | Opening (reserve) bid |
| `current_bid` | REAL | | Current highest bid (NULL = no bids yet) |
| `current_bidder_uuid` | TEXT | | Highest bidder UUID |
| `current_bidder_name` | TEXT | | Highest bidder display name |
| `bid_count` | INTEGER | NOT NULL DEFAULT 0 | Number of bids placed |
| `extensions_used` | INTEGER | NOT NULL DEFAULT 0 | Anti-snipe extensions applied |

The top-bid slot is claimed with a conditional
`UPDATE ... WHERE current_bid IS NULL OR current_bid < ?` (exactly-once), and
refund/release flows move money through `transferAtomicWithLedger`.

#### Table: `auction_bids` (2.2.0)

Append-only bid history (audit/analytics):

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `bid_id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Bid ID |
| `listing_id` | TEXT | NOT NULL | Listing UUID |
| `bidder_uuid` | TEXT | NOT NULL | Bidder UUID |
| `bidder_name` | TEXT | NOT NULL | Bidder display name |
| `amount` | REAL | NOT NULL | Bid amount |
| `bid_timestamp` | INTEGER | NOT NULL | Epoch millis |

`CREATE INDEX idx_bids_listing ON auction_bids (listing_id, bid_timestamp DESC)`.

#### Table: `auction_won_items` (2.2.0)

Offline winner delivery queue (claimed and deleted by `/ah collect`):

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `win_id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Win ID |
| `listing_id` | TEXT | NOT NULL UNIQUE | Listing UUID (idempotent inserts) |
| `winner_uuid` | TEXT | NOT NULL | Winner UUID |
| `winner_name` | TEXT | NOT NULL | Winner display name |
| `material_name` | TEXT | NOT NULL | Material registry key |
| `item_nbt` | TEXT | | Serialized item data (NULL in one narrow crash case — see AGENT_NOTES) |
| `quantity` | INTEGER | NOT NULL | Stack size |
| `win_price` | REAL | NOT NULL | Winning bid amount |
| `won_timestamp` | INTEGER | NOT NULL | Epoch millis |

`CREATE INDEX idx_won_items_winner ON auction_won_items (winner_uuid)`. A
startup sweep (`refundOrphanedBidStates`) refunds any bid whose listing is no
longer ACTIVE, and an escrow-consistency check compares the escrow account
balance against the sum of open top bids on every boot.

---

## 16. Configuration System

### Config Directory Structure

```
<server run dir>/
├── config/
│   └── solidus/              // Solidus Core data directory
│       ├── permissions.json  // Permission → OP level mapping
│       ├── shop.json         // Shop sections and item prices
│       ├── economy.db        // SQLite economy database (+ WAL files)
│       └── auctions.db       // SQLite auction database (+ WAL files)
└── solidus/
    └── exports/              // /transactions CSV exports (RFC 4180)
```

### shop.json Format

```json
{
  "sections": [
    {
      "id": "building_blocks",
      "display_name": "{\"text\":\"Building Blocks\",\"color\":\"#4FC3F7\",\"bold\":true}",
      "icon": "minecraft:bricks",
      "items": [
        {
          "material": "minecraft:stone",
          "buy_price": 10.0,
          "sell_price": 5.0
        },
        {
          "material": "minecraft:oak_planks",
          "buy_price": 5.0,
          "sell_price": 2.0
        }
      ]
    }
  ]
}
```

**Display names** use Minecraft's JSON text component format (parsed via `ComponentSerialization.CODEC`), supporting colors, bold, italic, and other formatting.

**Pricing**: Items can have `null` for `buy_price` (not purchasable) or `sell_price` (not sellable), enabling flexible shop configurations.

### ConfigManager

**File**: `com.solidus.util.ConfigManager`

`ConfigManager` handles all file I/O for the configuration system:

- **Load from JAR**: Copies default configuration files from the mod JAR to the config directory on first run
- **JSON parsing**: Uses Gson for reading/writing configuration
- **Hot reload**: `/shop reload` (OP 2+) re-reads `shop.json` and re-applies the global overrides without a server restart

---

## 17. Command Reference

| Command | Permission | Description |
|---------|-----------|-------------|
| `/balance` or `/bal` | `solidus.command.balance` | View your balance |
| `/pay <player> <amount>` | `solidus.command.pay` | Pay an online player |
| `/pay offline <name> <amount>` | `solidus.command.pay.offline` | Pay an offline player |
| `/baltop [page]` | `solidus.command.baltop` | Wealth leaderboard, 10 per page, ranks continue across pages |
| `/shop` | `solidus.command.shop` | Open the virtual shop |
| `/shop search <query>` | `solidus.command.shop.search` | Search shop items |
| `/shop reload` | `solidus.command.shop.reload` (OP 2) | Hot-reload `shop.json` |
| `/sell gui` | `solidus.command.sell` | Open sell GUI |
| `/sell all` | `solidus.command.sell` | Sell all sellable items (incl. shulker contents) |
| `/sell all <item>` | `solidus.command.sell` | Sell all of a specific item |
| `/ah` | `solidus.command.auction` | Open auction house |
| `/ah sell <price> [startbid]` | `solidus.command.auction.sell` | List held item; an opening bid enables bidding |
| `/ah bid <uuid> <amount>` | `solidus.command.auction.bid` | Bid on a bid-enabled listing (or right-click it in the GUI) |
| `/ah collect` | `solidus.command.auction.collect` | Collect expired items AND won auction items |
| `/ah cancel <uuid>` | `solidus.command.auction.cancel` | Cancel a listing (top bidder auto-refunded) |
| `/ah sort <order>` | `solidus.command.auction.sort` | Sort: newest / price_low / price_high / material |
| `/ah search <term>` | `solidus.command.auction` | Free-text search (cheapest first, max 15 results) |
| `/trade <player>` | `solidus.command.trade` | Request a direct trade (within 10 blocks) |
| `/trade accept` / `deny` | `solidus.command.trade` | Respond to a pending trade request |
| `/trade cancel` | `solidus.command.trade` | Cancel the trade you are in |
| `/transactions [page]` | `solidus.command.transactions` | View transaction history (10 per page) |
| `/transactions export [days]` | `solidus.command.transactions.export` (OP 0) | Export own history to CSV (default 7 days) |
| `/transactions exportall [days]` | `solidus.command.transactions.exportall` (OP 2) | Export the full ledger to CSV |


---

## 18. Testing Strategy

Solidus includes a comprehensive test suite using JUnit 5 and Mockito.

### Test Files

20 test classes, 309 `@Test` methods (all green on JDK 25). Storage/state-level
tests run on plain SQLite or pure Java — no Minecraft bootstrap required:

| Test | Coverage |
|------|----------|
| `BalanceManagerTest` (17) | get/add/subtract balances, transfer validation, atomicity |
| `SQLiteStorageTest` (27) | Player creation, persistence, concurrency (100-thread races), restart survival |
| `TransferAtomicTest` (10) | Single-transaction transfer semantics (success/insufficient/overflow/self) |
| `TransferAtomicLedgerTest` (5) | Ledger rows commit inside the money transaction; failure rolls back both |
| `TransactionLogExportTest` (12) | CSV building, RFC 4180 escaping, formula-injection guard |
| `TransactionLogPaginationTest` (10) | LIMIT/OFFSET pages, counts |
| `BaltopPaginationTest` (10) | Paged leaderboard, rank continuity, escrow exclusion |
| `EconomyStatsTest` (5) | Aggregates: count, mean, supply, Gini |
| `BidEscrowFlowTest` (13) | 2.2.0: escrow charge/refund/release, exactly-once claims, BidRules arithmetic |
| `TradeSessionStateTest` (8) | 2.2.0: anti bait-and-switch, empty-trade rejection, lifecycle, isolation |
| `AuctionSettlementTest` (5) | Buy-now settlement claims |
| `AuctionSettlementHistoryTest` (10) | Archive + delete invariants, startup recovery sweeps |
| `AuctionSearchTest` (8) | Sanitized free-text search, escaping, caps |
| `ShopGUILayoutTest` (36) | Layout arithmetic, centering, slot classification |
| `RateLimiterTest` (32) | Cooldowns, per-player isolation, concurrency, cleanup |
| `SolidusPermissionsTest` (10) | Naming convention, default OP levels |
| `EconomyHooksTest` (16) | Hook registry: duplicates, fail-open, dispatch |
| `TransferHookIntegrationTest` (8) | Veto/notification flow through transfers |
| `CurrencyUtilTest` (48) | Constants, validation, rounding, formatting |
| `TextUtilTest` (19) | Currency formatting, legacy-code sanitization |

### Concurrency Testing

`SQLiteStorageTest` includes critical concurrency tests:

- **100 concurrent adds**: 100 threads simultaneously add to the same balance; the final balance must be exactly `startBalance + (100 × addAmount)`
- **No overdraft**: 100 threads simultaneously try to subtract more than the balance holds; the balance must never go negative
- **New player race**: 100 threads simultaneously create the same player; only one record should exist
- **Persistence**: Data survives a simulated restart (close + reopen database)
### Concurrency Testing

`SQLiteStorageTest` includes critical concurrency tests:

- **100 concurrent adds**: 100 threads simultaneously add to the same balance; the final balance must be exactly `startBalance + (100 × addAmount)`
- **No overdraft**: 100 threads simultaneously try to subtract more than the balance holds; the balance must never go negative
- **New player race**: 100 threads simultaneously create the same player; only one record should exist
- **Persistence**: Data survives a simulated restart (close + reopen database)

### CI Pipeline

The `.github/workflows/test.yml` runs:
- JDK 25 setup
- `./gradlew test`
- Upload test results as artifacts

---

## 19. Extension Points & Integration Hooks

### For Economy Extension Mods

| Hook | How to Access | Use Case |
|------|---------------|----------|
| Balance operations | `SolidusAPI.getInstance().getBalance/addBalance/subtractBalance` | Death penalties, rewards, quests |
| Offline balance | `SolidusAPI.getInstance().getBalanceOffline/addBalanceOffline/subtractBalanceOffline` | Offline rewards, scheduled payments |
| Atomic transfers | `SolidusAPI.getInstance().transfer/transferOffline` | Peer-to-peer trades, tax collection |
| Transaction logging | `SolidusAPI.getInstance().getTransactionLog()` | Audit trail integration, custom transaction types |
| Transaction hooks (veto + notify) | `SolidusAPI.getInstance().registerTransactionHook(...)` — see [13.5](#135-solidustransactionhook--economy-interception-hooks) | Transfer limits, taxes, freezes, trading locks, alerts |
| Permission checking | `PermissionChecker.require(node, defaultOpLevel)` | Custom command permissions |

### For GUI Extension Mods

Solidus's virtual GUI pattern can be extended for custom menus:

1. **Create a DummyContainer**: Extend `Container` with display-only items
2. **Create a GUI builder**: Similar to `ShopGUI`/`AuctionGUI`, build `GuiSlot` lists
3. **Create a ScreenHandler**: Extend `AbstractContainerMenu` with custom click routing
4. **Register with PacketHandler**: Add your handler type to the click routing logic

### For Data Analysis Mods

- `SQLiteStorage.getTopBalances()` — Leaderboard data
- `TransactionLog` — Full transaction history for analysis
- Direct SQLite access — The database files are standard SQLite and can be queried by external tools

---

## 20. Security Considerations

### Economic Exploit Prevention

| Vulnerability | Mitigation |
|---------------|------------|
| **TOCTOU (Time-of-Check-to-Time-of-Use)** | Atomic check-and-deduct in `subtractBalance`; single-thread executor serialization |
| **Double-spending** | `pendingBuys`/`pendingSells` guards in ShopManager; atomic balance operations |
| **Dupe via race conditions** | Single-thread executor for all mutations; no concurrent DB writes |
| **Click automation** | 150ms `RateLimiter` per player; `compute()` atomic check-and-update |
| **Ghost item exploitation** | Layered defense: rate limiter → Mixin → handler hardening → DisplaySlot → DummyContainer → broadcastFullState resyncs (see §12.2) |
| **Negative balance** | `subtractBalance` rejects if funds insufficient; `BalanceManager` validates all amounts |
| **Overflow** | `MAX_BALANCE` (100M) and `MAX_TRANSACTION` (10M) caps; `isValidAmount` and `isValidBalance` validation |
| **NaN/Infinity injection** | Explicit checks in `CurrencyUtil.isValidAmount()` reject `Double.NaN` and `Double.isInfinite()` |
| **Self-payment** | `transferOffline` rejects transfers where sender = receiver |
| **Self-purchase in auctions** | `AuctionManager.purchaseListing` rejects buyer = seller |

### Database Security

- SQLite files are stored in the server directory (not accessible to players)
- WAL mode provides crash recovery without data corruption
- All operations are parameterized (no SQL injection risk)
- Balance cache is updated before CompletableFuture completes, ensuring consistency

---

## 21. Performance Characteristics

### Memory Usage

| Component | Memory Footprint | Growth |
|-----------|-----------------|--------|
| Balance cache (`ConcurrentHashMap`) | ~100 bytes per player | Linear with player count |
| Name cache (`ConcurrentHashMap`) | ~80 bytes per player | Linear with player count |
| Rate limit map | ~50 bytes per online player | Capped by cleanup (5-min threshold) |
| Auction listings | ~200 bytes per listing | Linear with active listings |
| Pending notifications | ~100 bytes per notification | Cleared on delivery |

### Operation Latency

| Operation | Latency | Explanation |
|-----------|---------|-------------|
| `getBalance` | < 1ms | Direct `ConcurrentHashMap.get()` |
| `addBalance` | < 1ms (perceived) | Cache update is instant; DB write is async |
| `subtractBalance` | < 1ms (perceived) | Same as addBalance |
| `transfer` | < 1ms (perceived) | Two cache updates; DB writes are async |
| `getTopBalances` | ~5-50ms | SQLite query (depends on player count) |
| Shop transaction | < 5ms | Balance check + item giving + log |
| Auction purchase | < 5ms | Balance check + item giving + status update + log |
| Auction expiry check | ~10-100ms | SQLite scan of active listings (every 5 minutes) |

### Database Size Estimates

| Player Count | `economy.db` Size | `auctions.db` Size |
|-------------|--------------------------|--------------------------|
| 100 | ~100 KB | ~50 KB |
| 1,000 | ~1 MB | ~500 KB |
| 10,000 | ~10 MB | ~5 MB |
| 100,000 | ~100 MB | ~50 MB |

Transaction log size grows with usage; consider periodic pruning for high-traffic servers.

---

## 22. Glossary

| Term | Definition |
|------|------------|
| **S$** | Solidus currency symbol |
| **TOCTOU** | Time-of-Check-to-Time-of-Use — a race condition where a value changes between checking it and acting on it |
| **WAL** | Write-Ahead Logging — SQLite journaling mode that allows concurrent reads during writes |
| **DummyContainer** | A `Container` implementation that blocks all mutations, used for display-only GUIs |
| **Ghost Item** | An item that appears on the client's screen but doesn't exist on the server, caused by client-server desync |
| **Virtual GUI** | A GUI that renders as a vanilla chest menu but is entirely custom logic on the server side |
| **Single-Thread Executor** | An `ExecutorService` backed by a single thread, serializing all submitted tasks to eliminate concurrency |
| **broadcastFullState()** | A method on `AbstractContainerMenu` that forces the server to resend the ENTIRE container state (all slots + carried stack) to the client — used after every processed Solidus click; `broadcastChanges()` alone was insufficient because it only sends changed slots |
| **Mixin** | A Fabric tool that injects custom code into Minecraft's compiled classes at runtime |
| **Brigadier** | Minecraft's command framework, used for registering `/bal`, `/pay`, etc. |
| **CompletableFuture** | Java's async computation wrapper; used throughout Solidus for non-blocking operations |
| **ConcurrentHashMap** | A thread-safe `Map` implementation allowing concurrent reads and atomic mutations |
| **CopyOnWriteArrayList** | A thread-safe `List` where modifications create a new copy; ideal for read-heavy concurrent access |
| **ScreenHandler** | Server-side class (extending `AbstractContainerMenu`) that manages container logic and click handling |
| **SolidusAPI** | The stable public API singleton for inter-mod integration, accessible via reflection |
| **MethodHandle** | Low-level Java reflection mechanism (mentioned in SolidusIntegration for advanced reflection) |
| **BalanceEntry** | A record type containing UUID, name, and balance for leaderboard results |
| **TransferResult** | A record type containing success status and message for transfer operations |
| **GuiSlot** | A record type mapping a slot index to a display `ItemStack` for GUI construction |
| **ListingStatus** | Enum for auction lifecycle: ACTIVE(0), SOLD(1), EXPIRED(2) |
| **ReadOnlySlot** | A `Slot` implementation that prevents item insertion/removal, used for UI elements in SellScreenHandler |

---

> **For questions, issues, or contributions**, visit [github.com/MOHD-Gs15/solidus-core](https://github.com/MOHD-Gs15/solidus-core)  
> **Author**: MOHD-Gs15 | **License**: MIT
