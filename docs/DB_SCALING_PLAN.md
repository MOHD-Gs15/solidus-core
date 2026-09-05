# Solidus — Database Scaling Plan: MySQL / MariaDB / Redis

> **Status: Phases 1+2 IMPLEMENTED** — Phase 1 (abstraction) shipped as 2.1.5,
> Phase 2 (MySQL/MariaDB multi-server backend) shipped as 2.2.0. Phases 3/4
> (Redis cache/bus + network integrity tooling) target 2.2.1 — see §11 for the
> shipped-vs-remaining scope table. | Author: agent implementation notes
>
> This document is the engineering plan for the third community request:
> *"SQLite only — no MySQL/MariaDB/Redis: impossible to run on a server
> network with unified balances. Any serious expanding server will hit this
> wall."* The goal is a phased, risk-managed path from the current
> single-file-SQLite architecture to a network-capable storage layer
> **without breaking the crash-resilience guarantees** this codebase is
> built on.

---

## Table of Contents

1. [Why This Is the Hardest Change](#1-why-this-is-the-hardest-change)
2. [Current Architecture Inventory](#2-current-architecture-inventory)
3. [Target Architecture](#3-target-architecture)
4. [Phase 1 — Storage Abstraction Layer](#4-phase-1--storage-abstraction-layer)
5. [Phase 2 — MySQL/MariaDB Implementation](#5-phase-2--mysqlmariadb-implementation)
6. [Phase 3 — Redis Layer (Cache + Pub/Sub)](#6-phase-3--redis-layer-cache--pubsub)
7. [Phase 4 — Cross-Server Money Integrity](#7-phase-4--cross-server-money-integrity)
8. [Migration & Rollout](#8-migration--rollout)
9. [Risks & Mitigations](#9-risks--mitigations)
10. [Effort Estimate](#10-effort-estimate)

---

## 1. Why This Is the Hardest Change

Everything Solidus guarantees today rests on properties that SQLite gives us
for free and a network setup takes away:

| Guarantee today | How SQLite provides it | What breaks on MySQL+Redis |
| --- | --- | --- |
| Atomic two-leg money moves | `BEGIN IMMEDIATE ... COMMIT` on one file | Same SQL works, but only within ONE server's connection |
| Crash-resilient ledger (money ↔ evidence) | Ledger row commits inside the money transaction | Still fine per-server — but "unified balance" across servers needs a single authority |
| Single-threaded executor = the lock | All mutations serialize through one thread | With N servers, N executors exist — the executor is no longer a lock |
| Millisecond balance reads | In-memory `balanceCache` mirror | Cache becomes a distributed-cache problem (stale reads = dupe exploits) |
| Exactly-once claims (`UPDATE ... WHERE status=0`) | Serialized executor + conditional updates | Works on InnoDB too (row locks) — but only when ALL servers use the same DB |

The critical insight: **the SQL itself ports easily; the concurrency model
does not.** The single-threaded executor pattern is duplicated across
`SQLiteStorage` (economy), `AuctionManager` (auctions) and implicitly in the
claim-based settlement flows. A network deployment must replace "the
executor is the lock" with "the database is the lock" — every mutation must
become a single atomic SQL statement (or stored procedure) on the shared
database, and every claim must survive two servers racing the same row.

## 2. Current Architecture Inventory

What exists today (2.1.4) and how it maps to the plan:

| Component | File | Coupling to SQLite |
| --- | --- | --- |
| Economy storage | `economy/SQLiteStorage.java` (~950 lines) | HIGH — SQL dialect (WAL pragmas, `INSERT OR REPLACE`, `BEGIN IMMEDIATE`), in-memory cache, single-thread executor |
| Balance API | `economy/BalanceManager.java` | LOW — talks to `SQLiteStorage` through `CompletableFuture` facades |
| Auction store | `auction/AuctionManager.java` | HIGH — owns its own `auctions.db`, own executor, raw SQL in ~15 statements |
| Transaction log | `economy/TransactionLog.java` | MEDIUM — sync inserts inside atomic transfers + own persistence |
| Escrow | `economy/EscrowAccount.java` | NONE — it is just a reserved UUID row; ports for free |
| Bid state | `auction/AuctionManager.java` (bid tables) | HIGH — same pattern as listings |
| Trade sessions | `trade/*` | NONE — pure memory state, intentionally ephemeral |
| Config | `util/ConfigManager.java` | LOW — needs a `storage.json` addition |

**Good news**: all money flows already funnel through a small primitive set:
`transferAtomic(WithLedger)`, `subtractBalance`, `addBalance`,
`setBalance`, `hasBalance`, `getTopBalances`, `getEconomyStats`. The whole
scaling project reduces to making those primitives network-correct.

## 3. Target Architecture

```
                         ┌──────────────────────────┐
                         │   Solidus Core (per      │
                         │   server instance)       │
                         │                          │
   commands/GUIs ───────>│  BalanceManager (API)    │
                         │       │                  │
                         │  StorageBackend (NEW IF) │
                         │       │                  │
                         │  ┌────┴─────┐  ┌───────┐ │
                         │  │SQLiteImpl│  │MySqlImpl│ │  <-- Phase 1/2
                         │  └──────────┘  └───────┘ │
                         │       │            │     │
                         └───────┼────────────┼─────┘
                                 │            │
                     local file  │            │ JDBC (HikariCP pool)
                                 v            v
                            economy.db    MySQL/MariaDB  <-- shared authority
                                          (balances, ledger, auctions)
                                              ^
                                              │ cache invalidation / balance bus
                                          Redis  <--------------+  <-- Phase 3
                                        (optional: cache + pub/sub)      │
                                                                         │
                                     other Solidus servers (same stack) -┘
```

Design decisions up front:

1. **MySQL/MariaDB is the money authority.** Redis is never the source of
   truth for balances — it caches and signals only. This keeps the ledger
   model (money ↔ evidence in one transaction) intact, which is the single
   most important property to preserve.
2. **One shared database, not per-server DBs with sync.** Sync-based
   (session-per-server + replication) designs reintroduce every dupe window
   this codebase spent 2.1.x closing. A single authority with row
   locks is the only model that keeps `transferAtomic` semantics.
3. **SQLite remains the default.** Networks opt in via config; single
   servers never pay the MySQL tax.

## 4. Phase 1 — Storage Abstraction Layer

**Goal**: extract an interface without changing behaviour. Pure refactor,
zero new features, full test-suite compatibility.

New interface `com.solidus.economy.StorageBackend` mirroring the current
`SQLiteStorage` public surface:

```java
public interface StorageBackend {
    void initialize();
    void shutdown();

    CompletableFuture<Double>   getBalance(UUID uuid, String name);
    CompletableFuture<Boolean>  setBalance(UUID uuid, String name, double amount);
    CompletableFuture<Double>   addBalance(UUID uuid, String name, double amount);
    CompletableFuture<Double>   subtractBalance(UUID uuid, String name, double amount);
    CompletableFuture<Boolean>  hasBalance(UUID uuid, double amount);
    CompletableFuture<TransferOutcome> transferAtomic(
        UUID s, String sn, UUID r, String rn, double amount);
    CompletableFuture<TransferOutcome> transferAtomicWithLedger(
        UUID s, String sn, UUID r, String rn, double amount,
        List<AtomicLedgerRow> rows);

    CompletableFuture<List<BalanceEntry>> getTopBalances(int limit, int offset);
    CompletableFuture<Integer>            getBalanceEntryCount();
    CompletableFuture<EconomyStats>       getEconomyStats();
    TransactionLog transactionLog();
    // ... auction store gets its own parallel interface (AuctionStore)
}
```

Steps:

1. Rename nothing yet; make `SQLiteStorage` implement `StorageBackend`.
2. `EconomyEngine` holds a `StorageBackend`, selected by config
   (`storage.type = "sqlite"` default).
3. Extract the **dialect specifics** behind small hooks: `upsertBalanceSql()`,
   `beginTransactionSql()`, `insertOrIgnore()` — SQLite uses
   `INSERT OR REPLACE` / `BEGIN IMMEDIATE`; MySQL will override with
   `INSERT ... ON DUPLICATE KEY UPDATE` / `START TRANSACTION WITH
   CONSISTENT SNAPSHOT` (InnoDB default isolation is fine; REPEATABLE READ
   is acceptable because every money primitive re-reads inside its own
   transaction).
4. Move the shared executor/queueing logic into an abstract base
   (`AsyncStorageBase`) so implementations only supply SQL.
5. **Tests**: existing `SQLiteStorageTest` etc. must stay green unchanged;
   add a contract test suite that runs against ANY `StorageBackend` — this
   becomes the acceptance harness for Phase 2.

**Exit criteria**: no behaviour change, no schema change, tests green, PR
reviewable in one sitting.

## 5. Phase 2 — MySQL/MariaDB Implementation

**Goal**: `MySqlStorage implements StorageBackend` against a shared MariaDB
(schema-compatible with MySQL 8+), network-ready.

### 5.1 Dependencies & config

```gradle
// build.gradle (new)
implementation "com.zaxxer:HikariCP:7.0.2"
implementation "org.mariadb.jdbc:mariadb-java-client:3.5.3"
include "com.zaxxer:HikariCP:7.0.2"
include "org.mariadb.jdbc:mariadb-java-client:3.5.3"
```

New `config/solidus/storage.json`:

```json
{
  "type": "mysql",                    // "sqlite" (default) | "mysql"
  "mysql": {
    "host": "db.myhost.net",
    "port": 3306,
    "database": "solidus",
    "user": "solidus",
    "password": "CHANGE_ME",
    "pool": { "maxSize": 10, "connectionTimeoutMs": 5000 },
    "useSsl": true
  }
}
```

Password handling: support an env override (`SOLIDUS_DB_PASSWORD`) and
document file permissions — never log the connection string.

### 5.2 Schema translation

| SQLite | MySQL/MariaDB |
| --- | --- |
| `TEXT PRIMARY KEY` uuid columns | `CHAR(36)` (or `BINARY(16)` + adapter — v1 uses CHAR(36) for simpler debugging) |
| `REAL` money columns | `DECIMAL(18,2)` — **important**: kills float drift on sums (Gini, money supply) |
| `INSERT OR REPLACE` | `INSERT ... ON DUPLICATE KEY UPDATE` |
| `BEGIN IMMEDIATE` | `START TRANSACTION` + `SELECT ... FOR UPDATE` on the affected rows (the InnoDB equivalent of "grab the write lock first") |
| `AUTOINCREMENT` | `AUTO_INCREMENT` |
| WAL / synchronous pragmas | n/a (InnoDB `innodb_flush_log_at_trx_commit=1` recommended) |

One migration script `docs/sql/mysql/001_init.sql` mirroring all tables
(`player_balances`, `transaction_log`, `auction_listings`,
`auction_sold_history`, `auction_bid_state`, `auction_bids`,
`auction_won_items`) with the existing indexes.

### 5.3 The concurrency rewrite (the real work)

Every claim/mutation must become a **single atomic statement on the shared
DB**, because two servers may race:

- `transferAtomicWithLedger` —
  `START TRANSACTION; SELECT balance FROM player_balances WHERE uuid=? FOR UPDATE;`
  (both rows) `UPDATE ...; UPDATE ...; INSERT ledger...; COMMIT;`
  A deadlock-retry wrapper (2 retries, exponential backoff) is mandatory —
  two opposing transfers can deadlock on lock order; solve by always
  locking the lower UUID first.
- Auction claims (`UPDATE ... WHERE listing_id=? AND status=0`) already are
  single conditional UPDATEs — they are network-correct as-is. The surrounding
  read-then-act code must be tightened so the *claim result* is the only
  branch condition (this is already true for purchase/cancel/expiry paths —
  verify each one against the checklist).
- `AuctionManager`'s single-thread executor stays (it still serializes
  *local* flows and keeps the non-blocking API), but it no longer represents
  a global lock — the row guards carry the correctness.
- Balance cache: switch from "authoritative mirror" to "read-through cache
  with version check" — see Phase 3. Before Redis exists, MySQL mode disables
  the in-memory balance cache for writes and always reads balances inside
  the transaction (slower, correct).

### 5.4 Conversions & numerics

`double` balances must stay source-compatible (`CurrencyUtil.round` already
normalizes to 2dp), but `DECIMAL(18,2)` columns mean the JDBC layer reads
`BigDecimal` and converts. Add a `Money` value wrapper in Phase 1 (private
constructor from double, `toDecimal()`, `toDouble()`) so the boundary is
explicit and testable.

**Exit criteria**: contract test suite green against MySQL via Testcontainers
or a CI MariaDB service; a 2-server integration harness transfers money
across instances without dupes under a scripted race (1000 concurrent
transfers over 2 servers, final supply invariant holds).

## 6. Phase 3 — Redis Layer (Cache + Pub/Sub)

**Goal**: latency + cross-server signalling. Redis is OPTIONAL — a MySQL-only
network works; Redis makes it fast and aware.

```gradle
implementation "io.lettuce:lettuce-core:6.5.5.RELEASE"
include "io.lettuce:lettuce-core:6.5.5.RELEASE"
```

New `config` section:

```json
"redis": { "enabled": false, "uri": "redis://cache.myhost.net:6379/0", "passwordEnv": "SOLIDUS_REDIS_PASSWORD" }
```

### 6.1 Balance cache (read path)

- Key: `solidus:bal:<uuid>` = JSON `{ "amount": 123.45, "version": 987, "server": "s1", "ts": ... }`
- Reads hit Redis (sub-ms); misses fall through to MySQL and populate.
- Writes NEVER write balances to Redis directly — MySQL is mutated inside
  its transaction, then the new value+version is published.

### 6.2 Invalidation bus (pub/sub)

- Channel `solidus:bal:inv` — after a committed transfer, publish
  `{uuids: [...], version}`. Every server invalidates its local cache entry
  (the current in-memory `balanceCache` survives as the L1 layer; Redis is L2;
  MySQL stays authority).
- `solidus:events` channel carries auction sold/won + trade events so
  notifications (offline delivery) fire on whichever server hosts the player —
  replaces the current "queue and hope they join here" limitation for networks.

### 6.3 Optional network locks (only if needed)

Cross-server "one player, one mutation" issues (e.g. the same player playing
on two servers simultaneously bidding their balance) are already safe: the
MySQL transaction is the guard. Redis locks (`SET NX PX`) are planned ONLY
for convenience features (prevent concurrent trade sessions of the same
account across servers), never for money correctness.

## 7. Phase 4 — Cross-Server Money Integrity

The invariant that makes the network actually trustworthy:

> **Global supply conservation**: at any moment, `SUM(all balances) + SUM(escrow)`
> equals the initial supply + all shop faucets − all shop sinks. The startup
> escrow-consistency check from 2.1.4 generalizes to a network-wide invariant
> checker (scheduled job on one elected server, warning on drift > 0.01).

Additional network flows:

1. **Unified baltop** — already correct once `getTopBalances` reads the
   shared DB (escrow exclusion carries over untouched).
2. **Offline delivery** — `queueNotification`/won-items must become
   network-aware: either poll the shared tables for "players hosted here"
   (simple, 1-min latency) or consume the Redis events bus (instant).
3. **Auction house** — one shared auction DB means all servers see the same
   market; expiry sweeps run on every server but claims are exactly-once by
   the row guards, so no double-settlement is possible (verify with the race
   harness).

## 8. Migration & Rollout

1. **Prereq**: deploy Phase 1 (abstraction) as 2.1.5 (current-family patch,
   pure refactor).
2. **Cutover tool**: `/solidus-admin storage migrate --to mysql --batch 500`
   (admin command, OP 4) — drains executors, begins: read all SQLite rows →
   bulk insert into MySQL inside per-table transactions → verify counts +
   `SUM(balance)` match to the cent → flip config → resume. Idempotent,
   re-runnable, writes a migration report file.
3. **Downtime expectation**: minutes (drain + copy), not hours; the command
   refuses to run while players are online unless `--force`.
4. **Rollback**: keep the SQLite files read-only; config flip back is enough
   if the MySQL cutover is rejected before players transact.
5. **Family version**: per `VERSIONING.md`, the multi-server era is the
   owner-reserved family bump (`2.2.0`); companions keep working (safe
   `fromCode` fallbacks),
   but Analytics should be re-verified against a MySQL-backed ledger.

## 9. Risks & Mitigations

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Distributed race reintroduces a dupe window | CRITICAL | Every money mutation = single transaction on shared DB + deadlock-ordered locking; cross-server race harness in CI |
| Connection pool exhaustion stalls the server thread | HIGH | All DB work stays on executors (existing pattern); HikariCP `maxSize` tuned; hard timeout + clean failure paths (every caller already handles failed futures) |
| Float drift on aggregates | MEDIUM | `DECIMAL(18,2)` + `Money` boundary wrapper |
| Redis outage | MEDIUM | Redis is cache-only; system degrades to MySQL-read-through (slower, correct). `useRedis` flag + circuit breaker |
| MySQL outage | HIGH | Admin choice: degrade to read-only mode (config) or fail closed; balances never silently zero |
| Schema drift between SQLite and MySQL | MEDIUM | One `001_init.sql` + a schema-version table + startup assertions |
| Migration partial failure | HIGH | Migrate command is transactional per table + verifies sums before commit; report file; SQLite originals preserved read-only |

## 10. Effort Estimate

| Phase | Scope | Estimate |
| --- | --- | --- |
| 1 — Storage abstraction | Interface, base class, config hook, contract tests | ~1 week |
| 2 — MySQL/MariaDB | Dialect, pool, schema, concurrency rewrite, race harness | ~2–3 weeks |
| 3 — Redis cache/bus | Lettuce integration, L1/L2 cache, events | ~1 week |
| 4 — Network integrity | Invariant checker, notification routing, baltop polish | ~1 week |
| Migration tooling + docs | Cutover command, SQL scripts, runbook | ~3–4 days |

Phases 1 and 2 SHIPPED: Phase 1 as 2.1.5 (pure abstraction refactor, current
family) and Phase 2 as 2.2.0 (MySqlStorage — the owner-reserved 2.2.x family).
Phases 3/4 (Redis + network features) target 2.2.1 — a MySQL-only network is
already fully functional without Redis.

## 11. Shipped vs Remaining (post-2.2.0 scope ledger)

**Shipped in 2.1.5 (Phase 1 — pure refactor, zero behaviour change):**

- `StorageBackend` interface mirroring the SQLiteStorage public surface.
- `StorageConfig` + `config/solidus/storage.json` (`type`: sqlite|mysql,
  pool settings, `SOLIDUS_DB_PASSWORD` env override).
- `StorageBackendContractTest` harness: 11 contract cases run through the
  interface ONLY — the acceptance harness for every future backend.
- Nested result types intentionally stay on `SQLiteStorage` (companion
  source compatibility inside a patch family — owner rule).

**Shipped in 2.2.0 (Phase 2 — the multi-server release):**

- `MySqlStorage implements StorageBackend`: HikariCP pool, auto-created
  schema (`player_balances` + `operations` + log/notifications, MySQL
  dialect), database-first reads, fail-closed startup on unreachable DB.
- Exact money: `DECIMAL(18,2)` columns + `Money` (BigDecimal) boundary.
- `transferAtomic(WithLedger)`: `SELECT ... FOR UPDATE` both rows in
  deterministic lower-UUID-first order, ledger rows inside the transaction,
  compare-and-set updates, deadlock/lock-wait retry (2× backoff).
- `TransactionLog` made dialect-aware (SQLite | MySQL DDL; pooled vs
  persistent connection flavors) — zero change to its public API.
- `docs/sql/mysql/001_init.sql`: operator reference schema incl. the
  provisioned auction tables.
- Race harness: `MySqlTransferRaceTest` (two backends, one shared DB, 200
  concurrent transfers → money-supply conservation to the cent) + MySQL
  contract binding — both CI-activated via `SOLIDUS_TEST_MYSQL_HOST`.

**Remaining for 2.2.1 (NOT in 2.2.0 — do not assume otherwise):**

1. **Auction store on MySQL (`AuctionStore` port)** — in 2.2.0 the auction
   house remains per-server SQLite (its money legs already flow through the
   shared economy DB). Tables are provisioned in `001_init.sql`.
2. **Redis layer** (§6): L1/L2 cache + pub/sub invalidation + network-aware
   notification delivery.
3. **`/solidus-admin storage migrate` cutover command** (§8.2) — until it
   ships, SQLite→MySQL data migration is manual SQL (export `player_balances`
   + `transaction_log` and import; the schema file matches 1:1).
4. **`operations` idempotency wiring** — the table is created in 2.2.0, the
   primitives are not routed through it yet (command-driven primitives have
   no network retries to dedupe until Redis/2.2.1).
5. `SKIP LOCKED` expiry sweeps + outbox (§5.5-adopted additions from the
   reviewed hybrid plan).
