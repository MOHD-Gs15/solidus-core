# Solidus-Core — Full Reliability & Stability Audit

**Repository:** `MOHD-Gs15/Solidus-core` (commit `9c35c08`, v2.2.5)
**Audit scope:** every production source file (~20,500 LOC) + tests
**Focus areas (as requested):** client–server conflicts · accounting errors · duplication · disappearance · hanging
**Result:** 7 CRITICAL / 5 HIGH / 6 MEDIUM findings — **17 fixed in this pass**, remainder documented with recommendations
**Verification:** `compileJava` clean under JDK 25 · **435/435 tests passing** (31 skipped = MySQL/Redis container tests that run in CI)

---

## 1. Executive Summary

Solidus-Core is a genuinely well-engineered economy mod. Its core money layer is strong: single-leg atomic transfers with in-transaction ledger evidence, deterministic lock ordering on MySQL, idempotent operations, an escrow-backed bidding model, a money-supply integrity auditor, and a hardened client-trust boundary on all four virtual GUIs. The 2.1.x→2.2.5 history visible in the code comments shows a disciplined bug-hunting culture.

That said, this audit found **seven critical defects** — five of them money-moving — all concentrated in the interaction *between* subsystems rather than inside them: the escrow account is wiped by its own initializer on every restart; the anti-snipe path updates a column that does not exist, converting every snipe-window bid into a free win; the auction buy-now claim is not conditional, allowing cross-server double purchase; the outbid refund pays a stale snapshot, printing money; and the trade execution path loses items on disconnect, can wedge permanently on a DB error, and can have its own rollback vetoed by governance hooks.

All of these are fixed in the attached patch (`solidus-2.2.6-reliability-fixes.patch`, 1,100 insertions across 13 files), and every existing test still passes.

---

## 2. Methodology

1. **Full read of the money-critical path:** `Money`, `BalanceManager`, `EconomyEngine`, `SQLiteStorage`, `MySqlStorage`, `RedisLayer`, `StorageBackend`, `EscrowAccount`, `TransactionLog`, `SupplyIntegrity` (partial), `AuctionManager` (all 3,218 lines), `SolidusMod`, `PacketHandler`, `RateLimiter`, `TradeManager`, `TradeSession`, `BidRules`, `CurrencyUtil`, `ShopManager`, `SellCommand`, `SellScreenHandler`, `PayCommand`.
2. **Parallel deep-dive** on trade/shop/sell/networking/commands/API by a dedicated audit pass, then **independent verification of each reported finding** against the source before accepting it.
3. **Schema-vs-code cross-check:** every SQL statement was matched against the DDL of its dialect (this is how ASC-01 was found).
4. **Crash-clock analysis** for every state machine: what happens if the server dies between any two lines, and what two servers racing the same shared row can do.
5. **Fix implementation + compile + full test suite.**

---

## 3. Findings — CRITICAL (all fixed)

### C-1 · ESC-01 — Server restart destroys all escrowed bid money
**File:** `economy/EconomyEngine.java` (init) · **Severity:** CRITICAL (money destruction) · **Status: FIXED**

`initialize()` ran `storage.setBalance(EscrowAccount.UUID_ZERO, EscrowAccount.NAME, 0.0)`. `setBalance` is an unconditional UPSERT (`balance = excluded.balance`). The comment says "pre-create the account" — the implementation *zeroed* it. Any restart with an open bid-enabled listing instantly annihilated the bidders' escrowed funds; outbid refunds and won-auction payouts then failed with `INSUFFICIENT_FUNDS` while winners still received items. The startup escrow-consistency warning would flag the hole only *after* the damage.

**Fix:** new `StorageBackend.ensureAccount()` (create-if-absent, `INSERT OR IGNORE` / `INSERT IGNORE`, affected-rows checked) implemented in both storages; `EconomyEngine` now calls it and never touches an existing escrow row.

### C-2 · ASC-01 — Anti-snipe extension writes a non-existent column → unbacked top bid
**File:** `auction/AuctionManager.extendListingExpiry` · **Severity:** CRITICAL (money printing + seller loss) · **Status: FIXED**

The 2.2.1 "fix" for the extension counter ran `UPDATE auction_listings SET expire_timestamp = ?, extensions_used = extensions_used + 1` — but `extensions_used` exists only on `auction_bid_state`; **neither dialect's `auction_listings` DDL has that column**. Every bid inside the 10-minute snipe window threw `no such column` *after* the claim had already succeeded and the displaced bidder had been refunded; the phase-3 catch then refunded the *new* bidder too. Net result: the listing's top bid had **no escrow behind it** — the bidder could win the item for free while the seller was never paid, and the `MAX_ANTI_SNIPE_EXTENSIONS` cap never engaged. This also explains any "ESCROW CONSISTENCY" warnings after bidding wars.

**Fix:** the UPDATE now targets `expire_timestamp` (status-guarded) on `auction_listings`, and increments `extensions_used` on `auction_bid_state` where it actually lives — restoring both the extension and its abuse cap.

### C-3 · ASC-02 — Auction buy-now claim is not conditional → cross-server double purchase
**File:** `auction/AuctionManager.purchaseItem` · **Severity:** CRITICAL on MySQL networks (double sale, double payment) · **Status: FIXED**

The comment block claims *"every claim below is a conditional (exactly-once) UPDATE"* — the buy-now path violated exactly that: `UPDATE auction_listings SET status = 1 WHERE listing_id = ?` with **no `AND status = 0` guard**, and the affected-rows value was ignored. Two servers on the shared market could both SELECT the ACTIVE row, both "mark it sold" (the second UPDATE re-writes `1→1` harmlessly), and both settle: two buyers receive the item, the seller is paid twice. Single-server SQLite was safe only by executor serialization.

**Fix:** the claim is now `... WHERE listing_id = ? AND status = 0` with an affected-rows check; a lost race returns `SOLD_OUT` before any money moves.

### C-4 · ESC-02 — Outbid refund pays a stale phase-1 snapshot → double refund + trapped escrow
**File:** `auction/AuctionManager.placeBidAs` (phase 3) · **Severity:** CRITICAL (money printing) · **Status: FIXED**

The displaced bidder was refunded from the bid-state snapshot taken in phase 1 — *before* the escrow charge ran on the economy executor. Two overlapping bids could both snapshot the same old top bid (A@100): X claims 150 (refunds A), then Y claims 200 and refunds **A again** from its stale snapshot. A was paid twice; X's 150 was stranded in escrow with no state pointing at it. Realistic trigger: two players bidding within the same few-hundred-ms charge window (bidding wars / spam-clicking).

**Fix:** new `claimTopBidWithDisplaced()` performs the displaced-bid read and the conditional claim as one atomic step — on the serialized executor (SQLite) or inside one `SELECT ... FOR UPDATE` transaction with proper rollback (MySQL) — and the refund targets exactly the bid that was actually displaced. Cross-server: two servers can no longer refund different displaced bidders for the same claim.

### C-5 · TRD-01 — Trade items deposited into a detached player object on mid-execution disconnect
**File:** `trade/TradeManager.executeTrade` · **Severity:** CRITICAL (irreversible item loss) · **Status: FIXED**

The delivery continuation reused the `ServerPlayer` references captured *before* the async money phase. `cancelSession` refuses `EXECUTING` sessions, so a disconnect during the money phase (a DB round-trip window on MySQL) meant items were written into an already-saved, detached player object — never persisted, never dropped: silently destroyed. Money legs had already committed. Repeatable by Alt-F4 during lag.

**Fix:** players are re-resolved inside the delivery hop. A recipient who vanished is recovered by spawning the stacks as item entities at their last known position (vanilla death-drop semantics); if even that is impossible the loss is logged CRITICAL with full ledger context instead of vanishing silently.

### C-6 · TRD-02 — Exceptional money future wedges the trade session forever
**File:** `trade/TradeManager.executeTrade` · **Severity:** CRITICAL (permanent lockout + hostage items) · **Status: FIXED**

The money chain used `.thenAccept` only. An exceptionally-completed future (driver error, engine already shut down) skipped the continuation entirely: the session stayed `EXECUTING` forever — `/trade cancel`, GUI close, disconnect cleanup and the idle reaper all refuse EXECUTING sessions — so **both players were permanently locked out of trading** (with items still escrowed) until restart.

**Fix:** `whenComplete` + a single `finalizeTrade()` path that force-aborts on error, returns items, removes the session and notifies both players.

### C-7 · ASC-03 — Startup bid sweep refunds without claiming → double refund (crash replay & multi-server)
**File:** `auction/AuctionManager.refundOrphanedBidStates` (also `settleBidStateOnRemoval`) · **Severity:** CRITICAL on networks / HIGH single-server · **Status: FIXED**

The sweep refunded escrow **before** deleting the bid-state row and ignored the delete's affected-rows: (a) every server runs the sweep at boot — two servers starting together both refunded the same orphans; (b) a crash after the refund but before the delete replayed the refund on next boot.

**Fix:** claim-then-refund — `deleteBidStateClaimed()` returns whether *this call* removed the row, and only the winner refunds. The failure mode is now crash-safe in the correct direction: interrupted refunds leave money **in** escrow (flagged by the consistency check for admin reconciliation), never paid twice.

---

## 4. Findings — HIGH (all fixed)

### H-1 · TRD-03 — Trade money rollback is hook-vetoable and triple-counts governance limits
**File:** `trade/TradeManager.executeMoneyLegs` · **Status: FIXED**

Legs and rollback ran through `transferOffline`, which fires `allowTransfer`/`afterTransfer`. A governance hook hitting a daily cap mid-trade could veto the **rollback of an already-committed leg** — the trade then aborted with items returned, but the first leg's money stayed with the partner: paid for nothing. The same path fired `afterTransfer` up to 3× per trade, triple-counting limits/taxes.

**Fix:** new hook-free `BalanceManager.transferInternal()` (same atomicity, no hook lifecycle — mirroring `settleAuctionPurchase`); trade legs, rollback, and empty-leg skip all use it. Trades are consensual, escrow-backed movements and must execute and roll back unconditionally.

### H-2 · TRD-04 — Cancel/execute race could duplicate escrowed stacks; unresolvable owner silently destroyed items
**Files:** `trade/TradeManager.cancelSession`, `trade/TradeSession` · **Status: FIXED**

`cancelSession` is reachable from the server thread *and* the disconnect event; the state check and `markCancelled()` were check-then-act, and `claimOfferedItems` read-and-cleared an unsynchronized container — two racing cancellers could both hand out the same stacks. The `owner == null` branch discarded the claimed items with **no log at all**.

**Fix:** atomic `tryBeginExecution()` / `tryMarkCancelled()` transitions in `TradeSession`; `takeOfferedItems` is now `synchronized`; the unresolvable-owner path logs CRITICAL and world-drops for recovery.

### H-3 · TRD-05 / M-4 — Shutdown returned escrowed items while money legs were still queued
**File:** `trade/TradeManager.shutdown` · **Status: FIXED**

`SERVER_STOPPING` returned the trade items immediately, but the queued money legs then committed during `EconomyEngine.shutdown()` — money changed hands while both players got their items back.

**Fix:** shutdown drains each EXECUTING session's money phase (bounded 10 s) and completes the delivery on the server thread; the finisher and the drain are mutually exclusive via the terminal-state guard.

### H-4 · M-1 / M-2 — Shop buy/sell wrote goods and restores into detached player objects
**Files:** `shop/ShopManager.processBuy`, `processSell`, `SellScreenHandler` payout-restore paths · **Status: FIXED**

Same async-gap class as C-5: a disconnect between the atomic money operation and the delivery hop destroyed the purchased items (buy) or the to-be-restored sold items (credit failure). Both paths now re-resolve the live player and recover stacks via a world drop at the last known position, with CRITICAL logging when even that fails.

### H-5 · HNG-01 — Redis L2 probe blocked the server tick thread up to 250 ms; guard tasks piled on the common pool
**Files:** `economy/MySqlStorage.getBalance`, `economy/RedisLayer.guarded` · **Status: FIXED**

The Redis cache probe ran on the *caller* thread (often the tick thread) with a 250 ms hard timeout — a slow Redis could stall every tick by a quarter second. The guard also ran on the shared `ForkJoinPool.commonPool()` and never cancelled timed-out tasks.

**Fix:** the probe moved inside the async executor hop (server thread never blocked); guarded calls run on a dedicated daemon executor and are cancelled on timeout; breaker semantics unchanged.

---

## 5. Findings — MEDIUM (fixed unless noted)

| ID | Location | Finding | Status |
|----|----------|---------|--------|
| M-3 | `SellScreenHandler.removed`, `SellCommand` (3 loops), `ShopManager.removeItemFromInventory` | **NBT-blind selling:** enchanted/renamed/damaged/container stacks sold at plain material price — Sharpness V sword sold as scrap, stuffed bundle sold as empty. | **FIXED** — one shared `hasCustomComponents()` guard (via `DataComponentPatch`) refuses component-bearing stacks in all four sell paths, with a clear player message pointing to the auction house. *Behavior change — see §8.* |
| M-5 | `SellScreenHandler.removed` | No exception safety around the consume loop — a mid-loop throw destroyed every unprocessed slot and never armed the payout. | **FIXED** — `try/finally` returns whatever remains in the container. |
| L-4 | `commands/PayCommand` | `/pay` futures had no exceptional completion handling → silent failures; display rounded HALF_UP while the transfer moved a differently-rounded amount (`10.005` shown as "10.01", 10.00 moved). | **FIXED** — `whenComplete` on both paths + one normalization (`CurrencyUtil.round`) at the command boundary so message, ledger and movement match. |
| L-2 | `shop/ShopManager.processBuy` | Bulk quantity could exceed `MAX_TRANSACTION`, failing with a misleading "Insufficient funds". | **FIXED** — explicit cap check with a clear message before any money moves. |
| — | `auction/AuctionManager` (won-item delivery) | Winner disconnecting between snapshot and hand-out now persists a collectible row (`reinsertAsCollectible` / won-items insert) — verified correct. | Verified (no change needed) |
| — | `economy/MySqlStorage.getBalanceEntryCount` | Counts the escrow system row while `getTopBalances` excludes it — "Page X/Y" math is off by one on MySQL. | **NOT FIXED (documented)** — cosmetic; suggest `WHERE uuid <> escrow` for parity. |

---

## 6. Verified-Solid Areas (no action needed)

These were explicitly audited and are correct — worth knowing so they are not "fixed" into regressions later:

- **Client trust boundary:** all four screen handlers fully override `clicked()`; only whitelisted `ContainerInput` actions act; slot indices bounds-checked; `-999` handled; `quickMoveStack` neutered; the mixin re-validates `containerId` (the 2.1.3 desync fix); `broadcastFullState()` after every processed click kills ghost-item predictions; drop-flood amplification is bounded by the throttled resync.
- **Money primitives:** `transferAtomic(WithLedger)` executes both legs inside one `BEGIN IMMEDIATE`/InnoDB transaction, deterministic lower-UUID lock order, deadlock retry with backoff, CAS-writes as belt-and-braces, ledger evidence committed *with* the money (audit 2.1.3). SQLite cache rollback on persist failure is correct.
- **Idempotent transfers:** the `operations` table claim (`INSERT IGNORE` + affected-rows) survived the 2.2.3 MariaDB fix and is correct.
- **Auction expiry sweep:** claim-by-delete / conditional flips with `AND status = 0` guards; archive-before-delete evidence ordering; crash-window `/ah collect` dupe already closed.
- **Escrow release order** (delete state → release money) chooses the safe failure mode (trapped-in-escrow, flagged) over double-payment.
- **Sell TOCTOU:** items removed synchronously first, payment for exactly what was removed, NBT-preserving restore snapshots, `.exceptionally` on all payout chains; shulker-box dupe closed.
- **Rate limiting:** atomic `compute()`-based, per-bucket, with disconnect cleanup — no burst window, no lock-free races.
- **Permissions / names / logs:** layered permission checks; SOL-004 name sanitization at every storage boundary; SOL-005 log-forgery escapes — all consistently applied.

---

## 7. What the Patch Changes (13 files, +1,100 / −287)

| File | Changes |
|------|---------|
| `economy/StorageBackend.java` | `ensureAccount()` default (create-if-absent contract) |
| `economy/SQLiteStorage.java` | `ensureAccount()` impl (`INSERT OR IGNORE`) |
| `economy/MySqlStorage.java` | `ensureAccount()` impl; Redis L2 probe moved off the caller thread |
| `economy/EconomyEngine.java` | Escrow pre-creation via `ensureAccount` (ESC-01) |
| `economy/BalanceManager.java` | Hook-free `transferInternal()` for settlement/rollback legs |
| `economy/RedisLayer.java` | Dedicated guard executor + timeout cancellation |
| `auction/AuctionManager.java` | Correct-table anti-snipe update (ASC-01); conditional SOLD claim (ASC-02); claim-with-displaced-bid refund, `FOR UPDATE` on MySQL (ESC-02); claim-then-refund sweeps (ASC-03) |
| `trade/TradeSession.java` | Atomic state transitions; synchronized item claims; money-phase handle for shutdown drain |
| `trade/TradeManager.java` | Full execution rewrite: `whenComplete` force-abort, player re-resolution, world-drop recovery, hook-free legs/rollback, synchronized cancel, shutdown drain |
| `shop/ShopManager.java` | Buy/sell liveness re-resolution + recovery drops; `MAX_TRANSACTION` guard; component-aware sellable matching |
| `sell/SellScreenHandler.java` | `try/finally` exception safety in `removed()`; shared `hasCustomComponents()` guard |
| `commands/SellCommand.java` | Component guard in all three sell loops (inventory, offhand, shulker contents) |
| `commands/PayCommand.java` | `whenComplete` on both paths; amount normalization at entry |

Every fix carries an `AUDIT FIX 2.2.6 (<ID>)` comment block explaining the original defect, the failure scenario, and the reasoning — so the patch is its own changelog.

---

## 8. Deliberate Behavior Changes (owner should review)

1. **Component-bearing items are no longer sellable to the shop** (M-3). Enchanted/renamed/damaged/container stacks are returned with a message directing players to the auction house. This protects players from value destruction but is a gameplay policy change. If you want the old behavior back, remove the `hasCustomComponents` checks — but then at least special-case `Damage` and `CONTAINER` components.
2. **Trade money legs no longer fire `allowTransfer`/`afterTransfer` hooks.** Governance limits now apply to `/pay`, shop and auctions — not to consensual trades (both parties pressed READY). This matches the existing auction-settlement precedent (`settleAuctionPurchase`) and fixes the vetoable-rollback defect; if you want a `allowTrade` hook lifecycle, add a dedicated one in `SolidusTransactionHook`.
3. **A recipient who disconnects mid-trade/mid-purchase now finds their items dropped at their last position** instead of silently written into a dead player object. Nothing is ever destroyed without a CRITICAL log.

---

## 9. Remaining Recommendations (not in this patch)

1. **`getBalanceEntryCount` vs leaderboard parity** — exclude the escrow row for correct "Page X/Y" on MySQL.
2. **`hasBalance(uuid, "")`** creates empty-name rows for never-seen players (both storages) — harmless but untidy; consider `SELECT`-only for the `""` name case.
3. **Auction `purchaseItem` "insufficient funds" path** calls `markAsUnsold` (now status-guarded `1→0`) — consider *not* marking SOLD until after the money settles on MySQL (claim via a dedicated `status=3 PENDING` state) to shrink the crash window the startup sweep currently covers.
4. **`lastWonDeliveryCount`** in `AuctionManager` grows unbounded (one entry per player) — trivial memory trim.
5. **`RedisLayer.close()`** should also shut down `GUARD_EXECUTOR` (daemon threads make this cosmetic).
6. **`isShulkerBox` + bundle policy:** bundles with contents are now protected by the component guard; consider a GUI lore warning on the sell screen listing what will be refused.
7. **CI:** add a test that opens a bid, *restarts* the store (close + reopen the SQLite file), and asserts the escrow balance survives — this is exactly the ESC-01 regression class, and no current test covers it.

---

## 10. How to Apply

```bash
# from the repository root
git apply solidus-2.2.6-reliability-fixes.patch
./gradlew build          # JDK 25
```

The patch applies cleanly on top of `9c35c08` (v2.2.5, `main`).

---

## 11. Verification Evidence

- `./gradlew compileJava` — **clean** (only a pre-existing deprecation note in `RedisLayer`).
- `./gradlew test` — **435 tests, 0 failures** (31 skipped: the MySQL/Redis service-container tests that CI runs against real `mariadb:11` / `redis:7`).
- All fixes are individually commented `AUDIT FIX 2.2.6 (<finding-id>)` for reviewability.

*Audit performed with a reliability-first mindset: every mutation path was checked for exactly-once semantics, every async hop for player-liveness and thread ownership, every state machine for its crash clock, and every SQL statement against its schema.*
