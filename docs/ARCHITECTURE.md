# Solidus-Core Architecture Documentation

> **Version**: 2.0.0 | **Minecraft**: 26.1.x | **Fabric**: 0.19.2+ | **Java**: 25  
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
    - 10.2 [ScreenHandlerMixin — Safety Net](#102-screenhandlermixin--safety-net)
    - 10.3 [PacketHandler — Click Routing Gateway](#103-packethandler--click-routing-gateway)
    - 10.4 [RateLimiter — 150ms Click Cooldown](#104-ratelimiter--150ms-click-cooldown)
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
| **GUI Shop** | JSON-configured virtual shop with 11 sections, 120+ items, buy/sell prices |
| **Auction House** | Peer-to-peer marketplace with 72h listings, 2% listing fee, sort/filter/cancel/collect |
| **Sell GUI** | Full cursor-based item placement GUI with shulker box content inspection |
| **Transaction Logging** | 10 transaction types with persistent audit trail and offline notification delivery |
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

1. **RateLimiter** — 150ms cooldown prevents rapid automated clicks
2. **Mixin Interception** — `ServerPlayerEntityMixin` catches clicks before vanilla processing
3. **ScreenHandler Rewriting** — Custom handlers rewrite clicks into Solidus actions
4. **DummyContainer** — Display-only containers block all item insertion/removal
5. **broadcastChanges()** — Forces client-server resync after every handled click

### 2.6 Reflection-Based Inter-Mod API

External mods should not need to compile against Solidus. The `SolidusAPI` class is accessible via pure Java reflection, allowing zero-dependency integration. This means:

- No Maven/Gradle dependency on solidus-core required
- No version coupling — the API contract is method names and parameter types
- Mods can gracefully degrade if Solidus is not installed

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
│  │  │  │+Cache      │ │  │  (120+ items)  │  │ (listings)   │  │   │
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
│  │  │ ServerPlayerEntityMixin │  │   ScreenHandlerMixin      │ │   │
│  │  │ (Packet Interception)   │  │   (Safety Net)            │ │   │
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
│     ├─ SQLiteStorage: opens DB, creates tables,       │
│     │   enables WAL, pre-loads all balances to cache  │
│     ├─ BalanceManager: wraps SQLiteStorage             │
│     └─ TransactionLog: creates log table, loads       │
│        pending offline notifications                   │
│                                                        │
│  4. new ShopManager(economyEngine) → loadConfiguration│
│     └─ Loads shop.json from JAR resources             │
│                                                        │
│  5. new AuctionManager(economyEngine) → initialize()   │
│     └─ Opens auction DB, loads active listings,       │
│        starts single-thread executor                   │
│                                                        │
│  6. new PacketHandler(shop, auction, rateLimiter)      │
│     └─ Registers container click interceptor          │
│                                                        │
│  7. Register Brigadier commands                        │
│     └─ /bal, /pay, /baltop, /shop, /sell, /ah,       │
│        /transactions                                   │
│                                                        │
│  8. Register SERVER_STOPPING hook                      │
│     └─ Clean shutdown: auctionMgr → economy → limiter │
│                                                        │
│  9. Register SERVER_STARTED hook                       │
│     ├─ Inject MinecraftServer into AuctionManager     │
│     └─ Initialize SolidusAPI singleton                │
│                                                        │
│  10. Register END_SERVER_TICK hook                     │
│      └─ Auction expiry check every 6000 ticks (5min)  │
│                                                        │
│  11. Register ServerPlayConnectionEvents.JOIN          │
│      └─ Deliver pending offline notifications         │
└──────────────────────────────────────────────────────┘
```

### Shutdown Sequence

```
SERVER_STOPPING event fires:
  1. auctionManager.shutdown()    — Executor shutdown + final DB writes
  2. economyEngine.shutdown()     — SQLite connection close + cache flush
  3. rateLimiter.clear()          — Clear cooldown map
```

The shutdown order is the reverse of initialization, ensuring that dependent systems are torn down after their dependencies.

---

## 5. Package Structure

```
com.solidus
├── SolidusMod.java              // Entry point, subsystem orchestrator
├── api/                          // Public integration API
│   ├── SolidusAPI.java           // Stable singleton API (reflection-accessible)
│   ├── SolidusIntegration.java   // Reference implementation for external mods
│   ├── SolidusPermissions.java   // Permission node constants
│   ├── PermissionChecker.java    // Unified checking (LuckPerms + OP fallback)
│   └── PermissionConfig.java     // OP-level config loader
├── auction/                      // Auction House subsystem
│   ├── AuctionManager.java       // Core controller (954 lines)
│   ├── AuctionEntry.java         // Immutable listing record
│   ├── ListingStatus.java        // ACTIVE/SOLD/EXPIRED enum
│   ├── AuctionGUI.java           // Virtual chest builder
│   ├── AuctionScreenHandler.java // Click handler
│   └── AuctionDummyContainer.java // Display-only container
├── commands/                     // Brigadier command registrations
│   ├── BalanceCommand.java       // /bal
│   ├── PayCommand.java           // /pay (online + offline)
│   ├── BaltopCommand.java        // /baltop
│   ├── ShopCommand.java          // /shop, /shop search
│   ├── SellCommand.java          // /sell gui, /sell all
│   ├── AuctionCommand.java       // /ah, /ah sell/collect/cancel/sort
│   └── TransactionsCommand.java  // /transactions [page]
├── economy/                      // Core economy engine
│   ├── EconomyEngine.java        // Central coordinator
│   ├── SQLiteStorage.java        // Async persistent backend
│   ├── BalanceManager.java       // High-level balance API
│   └── TransactionLog.java       // Audit trail + notifications
├── mixin/                        // Mixin injections
│   ├── ServerPlayerEntityMixin.java // Packet interception
│   └── ScreenHandlerMixin.java      // Safety net for virtual GUIs
├── networking/                   // Packet processing
│   ├── PacketHandler.java        // Click routing gateway
│   └── RateLimiter.java         // 150ms cooldown per player
├── sell/                         // Sell GUI subsystem
│   ├── SellGUI.java              // Virtual chest builder
│   ├── SellScreenHandler.java    // Full cursor item movement (746 lines)
│   └── SellContainer.java        // Real container (stores player items)
├── shop/                         // Virtual Shop subsystem
│   ├── ShopManager.java          // Config loader + transaction processor
│   ├── ShopGUI.java              // Virtual chest builder
│   ├── ShopScreenHandler.java    // Click rewriting handler
│   └── ShopDummyContainer.java   // Display-only container
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
// Simplified lifecycle
public class EconomyEngine {
    private SQLiteStorage storage;
    private BalanceManager balanceManager;
    private TransactionLog transactionLog;
    private volatile boolean initialized = false;

    public void initialize() {
        storage = new SQLiteStorage();
        storage.initialize();           // Opens DB, creates tables, pre-loads cache
        transactionLog = new TransactionLog(storage);
        balanceManager = new BalanceManager(storage, transactionLog);
        initialized = true;
    }

    public void shutdown() {
        initialized = false;
        storage.shutdown();             // Close SQLite connection
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
│  │   solidus_economy.db                 │   │
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
| `getBalance(UUID)` | Reads from in-memory cache (instant) | `CompletableFuture<Double>` |
| `addBalance(UUID, String, double)` | Validates → updates cache → persists to SQLite | `CompletableFuture<Double>` |
| `subtractBalance(UUID, String, double)` | Atomic check-and-deduct (TOCTOU-safe) | `CompletableFuture<Double>` |
| `setBalance(UUID, String, double)` | Direct balance set (admin operation) | `CompletableFuture<Double>` |
| `hasBalance(UUID, double)` | Checks cache for sufficient funds | `CompletableFuture<Boolean>` |
| `getTopBalances(int)` | Queries SQLite for leaderboard | `CompletableFuture<List<BalanceEntry>>` |
| `ensurePlayerExists(UUID, String)` | Creates player record if missing | `CompletableFuture<Void>` |

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
- **Atomic transfers**: `transferOffline()` implements deduct-then-add with rollback on failure

#### TransferResult

All transfer operations return a `TransferResult` record:

```java
public record TransferResult(boolean success, String message) {
    public static TransferResult ok(String msg) { return new TransferResult(true, msg); }
    public static TransferResult fail(String msg) { return new TransferResult(false, msg); }
}
```

#### Atomic Transfer with Rollback

The `transferOffline` method implements a **deduct-then-add** pattern with automatic rollback:

```
1. Validate amount (positive, within limits)
2. Check sender has sufficient balance
3. Deduct from sender (cache update + queue DB write)
4. Add to receiver (cache update + queue DB write)
5. If receiver add fails → rollback: add back to sender
6. Log transaction for both parties
7. Queue offline notification for receiver (if offline)
```

Because all operations are serialized through the same single-thread executor, the deduct and add happen atomically — no other operation can interleave between them.

---

### 6.4 TransactionLog — Audit Trail & Notifications

**File**: `com.solidus.economy.TransactionLog`

The `TransactionLog` serves two purposes:

1. **Persistent Audit Trail** — Every financial operation is logged to the SQLite `transactions` table with type, amount, timestamp, and involved parties
2. **Offline Notification Delivery** — When a transaction affects an offline player, a notification is queued and delivered when they next join

#### Transaction Types

| Code | Type | Color | Description |
|------|------|-------|-------------|
| 0 | `PAY_SEND` | Gold | Sent currency to another player |
| 1 | `PAY_RECEIVE` | Green | Received currency from another player |
| 2 | `SHOP_BUY` | Red | Purchased item from shop |
| 3 | `SHOP_SELL` | Green | Sold item to shop |
| 4 | `AUCTION_BUY` | Red | Purchased auction listing |
| 5 | `AUCTION_SELL` | Green | Auction listing sold |
| 6 | `AUCTION_LIST` | Yellow | Listed item on auction (fee) |
| 7 | `AUCTION_EXPIRE` | Gray | Auction listing expired |
| 8 | `ADMIN_SET` | Aqua | Admin set balance |
| 9 | `PENALTY` | Dark Red | Death penalty or other deduction |

#### Offline Notification Architecture

```
┌──────────────────────────────────────────────┐
│        TransactionLog                         │
│                                               │
│  ConcurrentHashMap<UUID,                      │
│      CopyOnWriteArrayList<String>>            │
│  pendingNotifications                         │
│                                               │
│  ┌─────────────┐     ┌──────────────────┐    │
│  │ onTransaction│     │ DB: notifications│    │
│  │ (recipient   │────▶│ table            │    │
│  │  is offline) │     │ (persistent)     │    │
│  └─────────────┘     └──────────────────┘    │
│                                               │
│  ┌─────────────────────────────────────┐     │
│  │ deliverPendingNotifications(player)  │     │
│  │ - Called on ServerPlayConnectionEvents│    │
│  │   .JOIN                              │     │
│  │ - Reads from memory + DB            │     │
│  │ - Sends formatted messages          │     │
│  │ - Clears from memory + DB           │     │
│  └─────────────────────────────────────┘     │
└──────────────────────────────────────────────┘
```

`CopyOnWriteArrayList` is chosen for thread safety: multiple threads may add notifications concurrently, while the rare read operation (delivery) gets a consistent snapshot.

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

The handler completely cancels vanilla container behavior (via the `ScreenHandlerMixin` safety net) and implements its own click logic. This prevents players from actually picking up shop display items.

---

## 8. Core Subsystem: Auction House

The auction house is a peer-to-peer marketplace where players list items for sale, and other players can purchase them. Unlike the shop (which trades with the server economy), auctions transfer items and currency directly between players.

### 8.1 AuctionManager — Race-Condition-Free Controller

**File**: `com.solidus.auction.AuctionManager` (954 lines)

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
│ [Item6] [Item7] [Item8] [Item9] [Item10] ...    │  (45 slots)
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

For shop and auction GUIs, Solidus cancels this entirely (via `ScreenHandlerMixin`) because no real items should move. But for the sell GUI, players **must** be able to place items into input slots. The solution: implement a custom `clicked()` method that handles item movement for input slots while blocking it for UI slots.

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
4. Instead, ScreenHandlerMixin allows it through
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

    boolean handled = packetHandler.handleContainerClick(
        player, packet.slotNum(), packet.buttonNum(), packet.containerInput());

    if (handled) {
        ci.cancel();                          // Prevent vanilla processing
        player.containerMenu.broadcastChanges(); // Force client-server resync
    }
}
```

**Critical**: `ci.cancel()` prevents vanilla from processing the click (which would move items in the container). `broadcastChanges()` forces a full resync, preventing ghost items that would appear if the client processed the click but the server didn't.

### 10.2 ScreenHandlerMixin — Safety Net

**File**: `com.solidus.mixin.ScreenHandlerMixin`

This is a second layer of protection. Even if the `ServerPlayerEntityMixin` somehow fails to intercept a click (edge case), this Mixin catches it at the `AbstractContainerMenu.clicked()` level:

```java
@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
private void onClicked(Slot slot, int slotIndex, int button,
                       ContainerInput containerInput, Player player, CallbackInfo ci) {
    if (player instanceof ServerPlayer serverPlayer) {
        AbstractContainerMenu currentMenu = serverPlayer.containerMenu;
        if (currentMenu instanceof ShopScreenHandler || currentMenu instanceof AuctionScreenHandler) {
            ci.cancel();
            currentMenu.broadcastChanges();
        }
        // Note: SellScreenHandler is EXCLUDED — it implements its own item movement
    }
}
```

The explicit exclusion of `SellScreenHandler` is crucial — the sell GUI needs to handle real item movement, so vanilla's `clicked()` must not be blocked for it.

### 10.3 PacketHandler — Click Routing Gateway

**File**: `com.solidus.networking.PacketHandler`

`PacketHandler` receives intercepted clicks and routes them to the appropriate handler based on the player's currently open container:

```
Incoming Click
      │
      ▼
┌─────────────────┐
│ RateLimiter Check│──▶ Rejected if < 150ms since last click
└────────┬────────┘
         │ Passed
         ▼
┌─────────────────────────────────────────┐
│ Determine current ScreenHandler type     │
│                                          │
│  ShopScreenHandler?  → ShopScreenHandler │
│                       .handleClick()     │
│                                          │
│  SellScreenHandler?  → Allow through     │
│                       (SellScreenHandler │
│                        handles itself)   │
│                                          │
│  AuctionScreenHandler?→ AuctionScrHandler│
│                       .handleClick()     │
│                                          │
│  Other?              → Return false      │
│                       (not a Solidus GUI)│
└─────────────────────────────────────────┘
```

The handler also manages cleanup when players disconnect, removing rate limit entries and any pending state.

### 10.4 RateLimiter — 150ms Click Cooldown

**File**: `com.solidus.networking.RateLimiter`

A per-player click cooldown prevents automated click spam (e.g., from auto-clicker mods or macros):

```java
public boolean tryConsume(UUID playerUuid) {
    long now = System.currentTimeMillis();
    AtomicBoolean allowed = new AtomicBoolean(false);

    rateLimitMap.compute(playerUuid, (uuid, lastClick) -> {
        if (lastClick == null || (now - lastClick) >= COOLDOWN_MS) {
            allowed.set(true);
            return now;
        }
        return lastClick;  // Reject — too soon
    });

    return allowed.get();
}
```

The `compute()` method is atomic — it guarantees that the check and update happen as a single operation, preventing race conditions where two simultaneous clicks both pass the check.

**Stale Entry Cleanup**: A periodic cleanup removes entries older than 5 minutes, preventing memory leaks from players who disconnect without triggering the cleanup event.

---

## 11. Cross-Cutting: Permission System

Solidus implements a fine-grained permission system that integrates with LuckPerms when available and falls back to configurable OP levels when it's not.

### 11.1 SolidusPermissions — Permission Node Registry

**File**: `com.solidus.api.SolidusPermissions`

All permission nodes follow the convention `solidus.<module>.<category>.<action>`:

| Module | Permission | Default OP Level |
|--------|-----------|-----------------|
| Core | `solidus.core.balance.view` | 0 (all players) |
| Core | `solidus.core.balance.others` | 1 (OPs) |
| Core | `solidus.core.pay` | 0 |
| Core | `solidus.core.pay.offline` | 0 |
| Core | `solidus.core.baltop` | 0 |
| Shop | `solidus.core.shop.view` | 0 |
| Shop | `solidus.core.shop.buy` | 0 |
| Shop | `solidus.core.shop.sell` | 0 |
| Auction | `solidus.core.auction.view` | 0 |
| Auction | `solidus.core.auction.sell` | 0 |
| Auction | `solidus.core.auction.collect` | 0 |
| Auction | `solidus.core.auction.cancel` | 0 |
| Auction | `solidus.core.auction.sort` | 0 |
| Analytics | `solidus.analytics.view` | 1 |
| Analytics | `solidus.analytics.export` | 2 |
| Territory | `solidus.territory.claim` | 0 |
| Territory | `solidus.territory.admin` | 3 |
| Governance | `solidus.governance.policy` | 2 |
| Governance | `solidus.governance.tax` | 2 |
| Governance | `solidus.governance.audit` | 2 |

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
  "solidus.core.balance.view": 0,
  "solidus.core.pay": 0,
  "solidus.core.auction.view": 0,
  "solidus.analytics.view": 1,
  "solidus.governance.policy": 2
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
Layer 1: RateLimiter
  └─ Rejects rapid-fire clicks before they reach any handler

Layer 2: ServerPlayerEntityMixin
  └─ Intercepts handleContainerClick at HEAD
  └─ If Solidus handles the click → cancel vanilla processing
  └─ Call broadcastChanges() → forces full resync

Layer 3: ScreenHandlerMixin
  └─ Safety net: cancels clicked() for Shop/Auction GUIs
  └─ Even if Layer 2 somehow misses, this catches it

Layer 4: DummyContainer
  └─ Even if both Mixins fail, container blocks all mutations
  └─ Items physically cannot be inserted or removed

Layer 5: broadcastChanges()
  └─ After every handled click, forces complete state resync
  └─ Client's view is corrected to match server state
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
| `getTopBalances(int)` | `CompletableFuture<List<BalanceEntry>>` | Leaderboard query |
| `getTransactionLog()` | `TransactionLog` | Access to transaction logging |
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
    modImplementation "com.github.mohd-gs:solidus-core:v2.0.0"
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

### Economy Database: `solidus_economy.db`

#### Table: `balances`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `uuid` | TEXT | PRIMARY KEY | Player UUID (hyphenated) |
| `name` | TEXT | NOT NULL | Player name (last known) |
| `balance` | REAL | NOT NULL DEFAULT 500.0 | Current balance |

```sql
CREATE TABLE IF NOT EXISTS balances (
    uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    balance REAL NOT NULL DEFAULT 500.0
);
```

**UPSERT pattern**: New players are created with `INSERT OR REPLACE` (or equivalent UPSERT), ensuring atomic creation without separate existence checks.

#### Table: `transactions`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Transaction ID |
| `uuid` | TEXT | NOT NULL | Player UUID |
| `type` | INTEGER | NOT NULL | Transaction type code (0-9) |
| `amount` | REAL | NOT NULL | Transaction amount |
| `description` | TEXT | | Human-readable description |
| `timestamp` | INTEGER | NOT NULL | Epoch millis |

```sql
CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL,
    type INTEGER NOT NULL,
    amount REAL NOT NULL,
    description TEXT,
    timestamp INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_transactions_uuid ON transactions(uuid);
CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp);
```

#### Table: `notifications`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | Notification ID |
| `uuid` | TEXT | NOT NULL | Recipient player UUID |
| `message` | TEXT | NOT NULL | Notification message |

```sql
CREATE TABLE IF NOT EXISTS notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL,
    message TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notifications_uuid ON notifications(uuid);
```

### Auction Database: `solidus_auctions.db`

#### Table: `auction_listings`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `listing_id` | TEXT | PRIMARY KEY | Listing UUID (hyphenated) |
| `seller_uuid` | TEXT | NOT NULL | Seller's player UUID |
| `seller_name` | TEXT | NOT NULL | Seller's display name |
| `material_name` | TEXT | NOT NULL | Material registry key |
| `quantity` | INTEGER | NOT NULL | Stack size |
| `item_nbt` | TEXT | | Serialized NBT data |
| `price` | REAL | NOT NULL | Listed price |
| `listed_timestamp` | INTEGER | NOT NULL | Listed time (epoch millis) |
| `expire_timestamp` | INTEGER | NOT NULL | Expiry time (epoch millis) |
| `status` | INTEGER | NOT NULL DEFAULT 0 | 0=ACTIVE, 1=SOLD, 2=EXPIRED |

```sql
CREATE TABLE IF NOT EXISTS auction_listings (
    listing_id TEXT PRIMARY KEY,
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
CREATE INDEX IF NOT EXISTS idx_auction_status ON auction_listings(status);
CREATE INDEX IF NOT EXISTS idx_auction_seller ON auction_listings(seller_uuid);
```

---

## 16. Configuration System

### Config Directory Structure

```
config/solidus/
├── permissions.json     // Permission → OP level mapping
├── shop.json           // Shop sections and item prices
└── solidus_economy.db  // SQLite economy database
solidus_auctions.db      // SQLite auction database (server root)
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
- **Hot reload**: Configuration can be reloaded without server restart (future enhancement)

---

## 17. Command Reference

| Command | Permission | Description |
|---------|-----------|-------------|
| `/balance` or `/bal` | `solidus.core.balance.view` | View your balance |
| `/pay <player> <amount>` | `solidus.core.pay` | Pay an online player |
| `/pay offline <name> <amount>` | `solidus.core.pay.offline` | Pay an offline player |
| `/baltop` | `solidus.core.baltop` | View top 10 leaderboard |
| `/shop` | `solidus.core.shop.view` | Open the virtual shop |
| `/shop search <query>` | `solidus.core.shop.view` | Search shop items |
| `/sell gui` | `solidus.core.shop.sell` | Open sell GUI |
| `/sell all` | `solidus.core.shop.sell` | Sell all sellable items |
| `/sell all <item>` | `solidus.core.shop.sell` | Sell all of a specific item |
| `/ah` | `solidus.core.auction.view` | Open auction house |
| `/ah sell <price>` | `solidus.core.auction.sell` | List held item for sale |
| `/ah collect` | `solidus.core.auction.collect` | Collect expired items/proceeds |
| `/ah cancel <uuid>` | `solidus.core.auction.cancel` | Cancel a listing |
| `/ah sort <order>` | `solidus.core.auction.sort` | Sort listings |
| `/transactions [page]` | `solidus.core.balance.view` | View transaction history |

---

## 18. Testing Strategy

Solidus includes a comprehensive test suite using JUnit 5 and Mockito.

### Test Files

| Test | Coverage |
|------|----------|
| `BalanceManagerTest` | getBalance, addBalance, subtractBalance, transferOffline (success, negative/zero/self/over-max rejection, insufficient funds, atomicity) |
| `SQLiteStorageTest` | New player creation, addBalance, subtractBalance, setBalance, hasBalance, getTopBalances, concurrency (100 concurrent adds, no overdraft, new player race), persistence across restart |
| `CurrencyUtilTest` | Constants, isValidAmount, isValidBalance, round, format, formatCompact, edge cases |
| `TextUtilTest` | formatCurrency, sanitizeLegacyFormatting (color codes, format codes, null, empty, mixed, uppercase, standalone section sign) |

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
| **Ghost item exploitation** | 5-layer defense-in-depth (rate limiter → Mixin → ScreenHandler → DummyContainer → broadcastChanges) |
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

| Player Count | `solidus_economy.db` Size | `solidus_auctions.db` Size |
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
| **broadcastChanges()** | A method on `AbstractContainerMenu` that forces the server to resend the entire container state to the client |
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

> **For questions, issues, or contributions**, visit [github.com/mohd-gs/solidus-core](https://github.com/mohd-gs/solidus-core)  
> **Author**: MOHD_Gs | **License**: MIT
