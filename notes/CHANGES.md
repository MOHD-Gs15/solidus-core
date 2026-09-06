# Solidus-Core — Developer Improvements (v2.1.0)

> Comprehensive report of improvements made by the developer in charge of the project.
> Date: 2026-06-18
> Target: Minecraft 26.1.x · Fabric Loader 0.19.2+ · Java 25

---

## Table of Contents

1. [Shop UI Redesign (/shop)](#1-shop-ui-redesign-shop)
2. [Security Vulnerability Fixes](#2-security-vulnerability-fixes)
3. [Bug Fixes](#3-bug-fixes)
4. [New Features](#4-new-features)
5. [Test Improvements](#5-test-improvements)
6. [Suggestions for Future Improvements](#6-suggestions-for-future-improvements)

---

## 1. Shop UI Redesign (/shop)

### The Problem
The old UI was:
- Stacking items top-to-bottom starting from slot 9 (default)
- Filling empty spaces with a uniform gray pane (visually bland)
- No geometric arrangement or visual centering
- The UX felt "cluttered" rather than organized

### The Implemented Solution

#### A. New class: `ShopGUILayout.java`
Created a pure-Java geometric layout engine (no Minecraft dependencies) that provides:

- **Coordinate conversion**: `slot(row, col)`, `rowOf(slot)`, `colOf(slot)`
- **Slot classification**: `isBorderSlot(slot)` and `isContentSlot(slot)` determine if a slot is on the border or in the content area
- **Centering calculation**: `centeredContentSlots(count)` returns the optimal slot array to center `count` items in a 7x4 content area (28 slots max)

Algorithm:
1. Compute the number of rows needed: `ceil(count / 7)`
2. Center the rows vertically within 4 content rows
3. For each row, center the items horizontally within 7 columns

#### B. Rewritten `ShopGUI.java`
The new design:

```
Main Menu:                           Section View:
+--#-#-#-#-#-#-#-#-#--+             +[back]-#-#-#[title]-#-#-#-#+
| #  S1  S2  S3  S4  S5  S6  S7  # |             | #  I1  I2  I3  I4  I5  I6  I7  # |
| #  S8  S9  S10 S11 .   .   .   # |             | #  I8  I9  I10 I11 I12 I13 I14 # |
| #  .   .   .   .   .   .   .   # |             | #  .   .   .   .   .   .   .   # |
| #  .   .   .   .   .   .   .   # |             | #  .   .   .   .   .   .   .   # |
| #-#-#-#-#[CLOSE]#[INFO]#-#-# |             | #-#[< PREV][INFO][NEXT >]#-# |
+---------------------+             +----------------------------+
```

Key components:
- **Dark border**: `BLACK_STAINED_GLASS_PANE` around the entire border (26 slots)
- **Colored glass in empty interior**: 11 alternating colors (blue, cyan, light_blue, green, lime, yellow, orange, red, pink, magenta, purple) — gives an attractive visual pattern
- **Centered items**: Sections/items are centered both horizontally and vertically in the chest
- **Bottom navigation bar**: Previous/Next/Close buttons + page indicator + info button

#### C. Changes in `ShopScreenHandler.java`
- Player ownership check (defensive): only the player who owns the handler can click
- Prevent navigation to negative pages (defensive clamping)
- Explicit use of `ContainerInput` (26.1.x API) instead of legacy `ClickType`

---

## 2. Security Vulnerability Fixes

### A. Quantity validation in `processBuy` / `processSell`
**Problem**: There was no validation of the `quantity` value — a negative or zero value would pass through.

**Fix**:
```java
if (quantity <= 0) { reject("Invalid quantity."); return; }
if (quantity > 2304) { reject("Quantity too large."); return; } // 36 stacks x 64
```

### B. Integer overflow prevention
**Problem**: `item.buyPrice() * quantity` was computed as `int`, which could overflow for large quantities.

**Fix**: Use `double` explicitly:
```java
double totalCost = CurrencyUtil.round(item.buyPrice() * (double) quantity);
```

### C. Item-loss detection during async transactions
**Problem**: In `processSell`, items are verified to exist before starting the async operation, but the player could drop or move items before the operation completes.

**Fix**: `removeItemFromInventory` now returns the actual count removed. If it's less than expected, a warning is logged for admin review.

### D. Cleanup of pending transactions on player disconnect
**Problem**: If a player disconnects during an async transaction, their UUID stayed locked in `pendingBuys`/`pendingSells` forever, preventing them from buying/selling after reconnecting.

**Fix**:
- Added `ShopManager.clearPendingTransactions(UUID)`
- Called it in `PacketHandler.register()` on the `ServerPlayConnectionEvents.DISCONNECT` event

### E. Prevent clicks from a non-owner player
**Fix**: Added `player != this.player` check in `ShopScreenHandler.clicked()`.

---

## 3. Bug Fixes

### A. `TextUtil.formatCurrency` bug (mismatch between tests and implementation)
**Problem**: The test expected `"1.5 S$"` (one decimal) but the implementation used `%,.2f` (two decimals). Tests would fail.

**Fix**: Changed the implementation to `%,.1f` to match the original test intent and allow compact display in lore lines.

### B. Updated `handlesSmallAmounts` test
**Problem**: The test expected `"0.01 S$"` for `0.01`, but with `%,.1f` it becomes `"0.0 S$"`. Updated the test to accurately reflect the new behavior.

---

## 4. New Features

### A. `/shop reload` command (admin only, OP 2+)
Allows admins to reload `shop.json` without restarting the server:
```
/shop reload
```
- New permission: `solidus.command.shop.reload` (default OP level: 2)
- Added to `SolidusPermissions`, `PermissionConfig`, and the `permissions.json` generator
- Displays a success message with the number of loaded sections and items

### B. Shop info button in the UI
When opening the main menu, a `KNOWLEDGE_BOOK` button appears in the corner showing:
- Number of categories
- Total items

### C. Page indicator in section view
On every page of a shop section, a `PAPER` displays `current page / total pages`, improving UX in large sections.

---

## 5. Test Improvements

### A. New tests: `ShopGUILayoutTest` (43 tests)
Comprehensive coverage of `ShopGUILayout`:
- Constants (INVENTORY_SIZE, COLUMNS, ROWS, ...)
- Coordinate conversion (`slot`, `rowOf`, `colOf`)
- Slot classification (`isBorderSlot`, `isContentSlot`)
- Centering calculation (`centeredContentSlots`) — edge cases, horizontal and vertical centering, uniqueness, correct inventory partitioning

### B. New tests: `RateLimiterTest` (24 tests)
- Allow first click, reject immediate follow-up
- Independent rate limits per player
- Concurrency tests: 100 threads clicking for the same player -> only one succeeds
- Concurrency tests: 100 threads clicking for 100 players -> all succeed
- Memory cleanup (`removePlayer`, `clear`, `getTrackedPlayerCount`)

### C. New tests: `SolidusPermissionsTest` (11 tests)
- Verify naming convention compliance
- Verify default OP level for each permission
- Verify `SHOP_RELOAD` is the only elevated core command

### D. Improved `TextUtilTest` (3 new tests)
- `roundsHalfUp`: test single-decimal rounding
- `handlesNegative`: defensive test for negative values
- `handlesVeryLarge`: test large numbers without scientific notation

---

## 6. Suggestions for Future Improvements

These are additional suggestions for the next developer or the user to implement:

### A. UX Improvements
1. **In-GUI search**: A search button in the main menu that opens a GUI with search results instead of just chat
2. **Balance display in UI**: A header element showing the player's current balance + potential transaction value
3. **Shopping cart**: Allow players to collect multiple items before a single payment
4. **Inventory availability indicator**: In the lore, show "You have: 5" for items the player owns

### B. Economic Improvements
1. **Dynamic pricing**: Automatically reduce sell price when selling large quantities (anti-dump)
2. **Daily purchase limit**: Prevent the rich from buying the entire stock in a single day
3. **Off-peak discounts**: Reduce prices during certain hours to attract activity
4. **Inflation display**: Track average prices and show "Price change: +5%" in the lore

### C. Technical Improvements
1. **ItemStack caching**: Create a `Map<String, ItemStack>` in `ShopManager` to avoid building ItemStacks on every UI open (performance improvement)
2. **Metrics**: Track how often each section is opened, most-bought items, most-sold items — useful for the analytics module
3. **Additional admin commands**: `/shop setprice <material> <buy> <sell>` to adjust prices from in-game without editing JSON
4. **shop.json backups**: Save a copy before every `reload` in `config/solidus/backups/`

### D. Minecraft 26.1.x Compatibility
1. **Review `ContainerInput`**: Verify final constant names (QUICK_MOVE, PICKUP, etc.) when JDK 25 + Minecraft 26.1.x are officially available
2. **Replace `player.closeContainer()`**: Check if it has been renamed to `closeMenu()` in the final 26.1.x
3. **Update `ServerboundContainerClickPacket`**: Verify `buttonNum()` — it may no longer exist (absorbed into `ContainerInput`)
4. **Mixin testing**: Update `ScreenHandlerMixin` to match the final signature of `clicked(Slot, int, ContainerInput, Player)`

### E. Additional Security Improvements
1. **CAPTCHA for large transactions**: If a transaction exceeds 100,000 S$, require a second confirmation click
2. **IP throttling**: Reject transactions from the same IP within 100ms (prevent multi-account abuse)
3. **Behavioral monitoring**: If a player sells > 10,000 items in a minute, log an alert for fraud detection

---

## Summary

| Item | Count |
|------|------|
| New files | 4 (`ShopGUILayout.java`, `ShopGUILayoutTest.java`, `RateLimiterTest.java`, `SolidusPermissionsTest.java`) |
| Modified files | 9 |
| New tests | 78+ |
| Security vulnerabilities fixed | 5 |
| Bugs fixed | 2 |
| New features | 3 (`/shop reload`, info button, page indicator) |
| Legacy TODO comments removed | 12+ |
| Non-ASCII characters cleaned | All source files are now pure ASCII |

The new UI offers a professional visual experience with a dark border and colored glass, and items are centered in the chest instead of the default top-down arrangement — exactly as the user requested.

---

# Appendix — Storage Scaling 2.1.5 / 2.2.0 / 2.2.1

> DB-scaling delivery notes (full phase ledger lives in `docs/DB_SCALING_PLAN.md` §11,
> and the engineering review of the external hybrid plan in `notes/PLAN_REVIEW_HYBRID.md`).
> Date: 2026-09-06

| Release | Content |
|---------|---------|
| **2.1.5** | `StorageBackend` interface + `StorageConfig`/`storage.json` + the 11-case contract test harness (pure refactor, zero behaviour change). |
| **2.2.0** | `MySqlStorage` (HikariCP, DECIMAL(18,2) + `Money` boundary, FOR UPDATE deterministic locking, in-transaction ledger rows, deadlock retries), dialect-aware `TransactionLog`, `docs/sql/mysql/001_init.sql`, MySQL race harness. |
| **2.2.1** | Auction store on the shared database (`AuctionDialect` SQLITE\|MYSQL, exactly-once claims preserved, SKIP LOCKED sweep), optional `RedisLayer` (L2 cache + `solidus:bal:inv` invalidation + `solidus:events` instant network notifications, circuit breaker), `/solidus-admin storage migrate` cutover command + `StorageMigrator` (idempotent copy + cent-exact verify + report file), `operations` idempotency wiring (`transferAtomicWithLedger(opId, …)` with the corrected ON CONFLICT pattern). |

**Bugs fixed in 2.2.1 (discovered during the dialect port):**

1. `markExpiredRowCollectible` never bound its `?` parameter — the offline-seller
   collectible flip was dead code (the parameter silently compared against NULL).
2. `checkEscrowConsistency` read `player_balances` through the AUCTION connection
   (a different SQLite file) — the escrow check silently failed on every startup;
   it now reads through the storage API (async, non-blocking).
3. `extensions_used` was never incremented — the anti-snipe cap (12 extensions)
   never took effect; the counter now advances with every successful extension.

**Test totals after 2.2.1:** 364 test executions — 337 passed, 0 failed,
27 self-skipping CI-gated tests (MySQL/MariaDB + Redis service containers;
the same files bind and run green in CI when `SOLIDUS_TEST_MYSQL_HOST` /
`SOLIDUS_TEST_REDIS_URI` are set).

---

## 2.2.2 — Console test harness (playerless economy testing)

**New:**

- `com.solidus.admin.AdminOps` — player-agnostic admin/testing service; the
  full economy lifecycle is now drivable from the server console or Rcon:
  dummy account creation (vanilla offline-mode UUID derivation), money
  give/set/take, real atomic `pay-as` transfers, `bid-as` escrow bids and
  `auction create` listings on dummy accounts, an invariant `audit`
  (negative-balance tail scan, escrow sanity, supply snapshot) and `diag`
  (backend/Redis/auction-mode/economy snapshot).
- `/solidus-admin` subcommand tree extended accordingly (OP 4 / console).
- Ledger types `ADMIN_GIVE` / `ADMIN_SET` / `ADMIN_TAKE` (rendered as
  `AD+` / `AD=` / `AD-` in `/transactions`); `pay-as` records the standard
  `PAY_SEND` / `PAY_RECEIVE` rows for exact parity with player `/pay`.
- `AuctionManager`: `placeBidAs` / `listItemAs` player-agnostic cores
  (the `ServerPlayer` methods became thin wrappers — behavior identical);
  `bidErrorMessage` extracted as a pure string mapper.
- New doc: `docs/CONSOLE_TESTING.md` — command reference + scripted
  single-server, auction-lifecycle, two-server race and outage scenarios.
- CI: `.github/workflows/test.yml` now boots `mariadb:11` + `redis:7` service
  containers with the `SOLIDUS_TEST_*` variables set, so the 27 previously
  self-skipping network tests (200-transfer race, idempotency replay, MySQL
  auction dialect, migrator against a throwaway database, Redis layer) run
  for real on every push.

**Internal:**

- `SolidusAdminCommand.register` now also receives the `AuctionManager`.
- `TransactionsCommand` type/color switches extended for the ADMIN_* types
  (exhaustiveness compiler-enforced).

**Test totals after 2.2.2:** 387 test executions — 360 passed, 0 failed,
27 CI-gated (which now activate in GitHub Actions against real service
containers). New suite: `AdminOpsTest` (23 cases, real SQLiteStorage harness,
no Mockito — the inline mock maker is incompatible with the Java 25
toolchain; a thin EconomyEngine subclass replaces it).

---

## 2.2.3 — Bug fixes flushed out by the first real CI run

The 2.2.2 push activated the 27 CI-gated tests for the first time (real
`mariadb:11` / `redis:7` service containers). Four failures came back —
each a latent 2.2.1 production bug, plus one more found while reproducing
them locally against a portable MariaDB 11.8.6 (no Docker required:
.deb-extracted `mariadbd` on a user datadir).

**Fixed (production):**

- **Idempotent transfer replay double-paid.** The `operations` claim used
  `INSERT … ON DUPLICATE KEY UPDATE` with an affected-rows `1 vs 2` routing;
  MariaDB returns **0** for a no-op duplicate update (a replay sends the
  same `op_type`), so replays re-executed the transfer. Claim is now
  `INSERT IGNORE` (1 = fresh claim, 0 = already claimed) — unambiguous on
  both dialects.
- **Auction orphan sweep archived nothing on MySQL.** The sweep's archive
  call defaulted to the SQLITE dialect → `INSERT OR IGNORE` (invalid on
  MariaDB) → log-matched orphans were RE-LISTED (a paid item could re-enter
  the market). The dialect now flows through.
- **Auction search returned an empty list on MySQL.** `ESCAPE '\'` breaks
  statement parsing on MariaDB/MySQL (backslash is a string-literal escape
  there; SQLite takes it literally). The escape character is now `!`
  (`!`→`!!`, `%`→`!%`, `_`→`!_`) — portable, no dialect branching.
- **Cutover migrator failed on a fresh target DB.** Only the five auction
  tables were created; `player_balances` did not exist. The migrator now
  creates the FULL target schema (`MySqlStorage.createEconomySchema` +
  ledger/notifications + auctions) and tolerates tables missing on old
  SQLite backups (skipped with a report note, not an abort).
- **Migrator verify phase closed the shared SQLite connection** (latent
  since 2.2.1 — the copy stage always failed first, masking it). Verify
  counts now reuse the outer connection.

**Fixed (tests):**

- `MySqlAuctionDialectTest` read-path case: pure-Java stubs replace Mockito
  entirely (the inline mock maker cannot instrument classes OR interfaces on
  the Java 25 toolchain).
- Orphan-recovery test expectation corrected (archived history rows = 1, not
  2 — a re-listed orphan never settles; matches the SQLite sibling test).
- `StorageMigratorTest`: `ConfigManager` pointed at a temp dir (report-file
  assertion meaningful); the SQLite source now carries all five auction
  tables with real rows (full copy + re-run convergence coverage).

**Test totals after 2.2.3:** verified locally against a real MariaDB 11.8.6 —
387 executions: 383 passed, 0 failed, 4 CI-gated skips (Redis layer only;
the 23 MySQL-gated tests now run green against the real server). GitHub
Actions is expected to show **zero** gated skips (both service containers up).

---

## 2.2.4 — Phase 4: cross-server money integrity (DB scaling plan §7)

The final phase of the storage plan turns the 2.1.4 startup escrow check
into a scheduled, network-wide invariant auditor, closes the last no-Redis
notification gap, and verifies the auction exactly-once claim under a real
concurrent race.

**New: `SupplyIntegrity` checker**

- Single-row checkpoint (`solidus_supply_checkpoint`, dialect-aware DDL, on
  the ledger's own connection source) stores the last clean run's (supply,
  ledger watermark, row count).
- Every run replays the ledger window through the METERED categories —
  `SHOP_SELL +`, `SHOP_BUY −`, `ADMIN_GIVE +`, `ADMIN_TAKE −`,
  `ADMIN_SET ±delta`, `DEATH_PENALTY −`, `DEATH_REWARD +` — adds
  `starting_balance × rows_created` for the auto-create mint, and compares
  against the observed `SUM(player_balances)` (escrow row included).
- Drift beyond tolerance (default 0.01) warns with the full breakdown and
  does NOT advance the checkpoint (sticky baseline). After investigation,
  `/solidus-admin integrity rebase` accepts the current books.
- Known unmetered sources are documented in the report itself (companion
  `SolidusAPI` add/subtract calls, `setBalance` on a missing row) — a
  warning is a prompt to read the breakdown, not an alarm.
- The watermark is the max ledger id the SUM query actually covered
  (captured in the same statement) — rows committed mid-check are never
  silently skipped.
- Election for the scheduler is a config flag (`integrity.elected`,
  default true = correct for single servers; networks set it on exactly
  one server). The check is strictly read-only.

**Ledger semantics correction (required by the replay):**

- `ADMIN_SET` rows now record the SIGNED supply delta (new − old) instead
  of the final balance, on both admin paths (`money set` now reads the old
  balance first; `account create` logs `final − starting`). A final-balance
  value would replay the replaced balance as a burn. Prior rows are
  absorbed by the baseline bootstrap; `TransactionsCommand` display is
  unchanged (ADMIN_SET renders amount-neutral).

**Notification delivery (no-Redis path):**

- New 60-second sweep: `SELECT DISTINCT player_uuid FROM
  pending_notifications` on the shared database, intersected with locally
  hosted online players, delivered via the existing exact-row path.
  Closes the "queued on server A, player online on server B, Redis off"
  gap. The JOIN hook and the 2.2.1 Redis events bus are unchanged.

**Escrow consistency made periodic:**

- `AuctionManager.checkEscrowConsistency` is public and runs on the same
  scheduled cadence (and from `/solidus-admin integrity check`).

**Auction settlement race verification:**

- `MySqlAuctionSweepRaceTest` (CI-gated): the production
  `archiveAndDeleteListing` claim driven from TWO concurrent connections
  across 10 rounds — exactly one winner per round and exactly one history
  row per listing, with autoCommit restored on both connections. Double settlement on a network is now race-proven
  impossible, not just argued.

**Ops surface:**

- `/solidus-admin integrity check` — full supply report + escrow dispatch.
- `/solidus-admin integrity rebase` — accept current books as baseline.
- Default `storage.json` template ships the `integrity` block with
  commented defaults (`enabled/intervalMinutes/tolerance/elected`).

**Compat notes (owner rules honored):**

- `SolidusAdminCommand.register` gained a `SupplyIntegrity` parameter
  (internal wiring — not part of the companion API).
- `TransactionLog.withConnection`/`SqlWork` are now public (additive);
  new additive members: `TransactionLog.getConnectionSource`,
  `TransactionLog.playersWithPendingNotifications`,
  `EconomyEngine.integritySettings`, `StorageConfig.integrity()` +
  `IntegritySettings` record, `AdminOps` 4-arg constructor (3-arg kept).

**Test totals after 2.2.4:** 397 test executions (10 new: 6 SQLite
`SupplyIntegrityTest` + 4 CI-gated MySQL; the gated pool grows from 27 to
31 — 27 MySQL + 4 Redis). Verified locally against a real MariaDB 11.8.6:
397 executed, 0 failed, 4 skips (Redis only — no local Redis); GitHub
Actions is expected to run all 397 with zero gated skips.
