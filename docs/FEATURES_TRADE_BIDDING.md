# Solidus 2.1.4 — Bidding System & Direct Trade

> **New in this release** | Minecraft 26.1.x | Fabric Loader 0.19.4+ | Java 25 | 100% Server-Side
>
> This document is the full reference for the two features added in 2.1.4:
> the auction **bidding system** (escrow model) and the **direct trade window**
> (`/trade`). Companion documentation: `docs/DB_SCALING_PLAN.md` (the
> MySQL/MariaDB/Redis roadmap) and `notes/AGENT_NOTES.md` (implementation
> notes & known tradeoffs).

---

## Table of Contents

1. [Auction Bidding System](#1-auction-bidding-system)
   - 1.1 [Player-Facing Behaviour](#11-player-facing-behaviour)
   - 1.2 [The Escrow Model — Why and How](#12-the-escrow-model--why-and-how)
   - 1.3 [Anti-Snipe Protection](#13-anti-snipe-protection)
   - 1.4 [Every Refund Path](#14-every-refund-path)
   - 1.5 [GUI & Commands](#15-gui--commands)
   - 1.6 [Database Schema](#16-database-schema)
   - 1.7 [Crash Recovery](#17-crash-recovery)
2. [Direct Trade Window (`/trade`)](#2-direct-trade-window-trade)
   - 2.1 [Player-Facing Behaviour](#21-player-facing-behaviour)
   - 2.2 [The Anti-Scam Contract](#22-the-anti-scam-contract)
   - 2.3 [Execution Order & Failure Handling](#23-execution-order--failure-handling)
   - 2.4 [GUI Layout](#24-gui-layout)
   - 2.5 [Integration With Governance](#25-integration-with-governance)
3. [New Permissions & Ledger Types](#3-new-permissions--ledger-types)
4. [Configuration Constants](#4-configuration-constants)
5. [Testing](#5-testing)

---

## 1. Auction Bidding System

Previously the Auction House was **buy-now only**: every listing had a fixed
price and the only interaction was an instant purchase. Listings can now
optionally accept **bids**, and expire to the highest bidder — the standard
behaviour of mainstream auction plugins (Crazy Auctions, AuctionHouse,
zAuctionHouse), closing the biggest feature gap reported by the community.

### 1.1 Player-Facing Behaviour

- **Sellers** enable bidding by adding an opening bid to the listing:
  `/ah sell <buy-now-price> <starting-bid>` (e.g. `/ah sell 5000 1000`).
  The buy-now price stays active for the whole auction; whoever acts first
  wins — a final bid before expiry, or a buy-now click.
- **Buyers** see bidding-enabled listings with a purple `BIDDING ENABLED`
  header in the GUI lore: current bid, top bidder, bid count, minimum next
  bid, and the buy-now price. Left-click still buys instantly; **right-click
  opens a chat prompt** ("type your bid in chat") — the standard auction UX.
  Power users can also use `/ah bid <listing-uuid> <amount>`.
- **Winning**: when a bid-enabled listing expires WITH at least one bid, the
  highest bidder wins. The item is delivered immediately if they are online,
  otherwise it waits in `/ah collect` (the same command that returns expired
  items — one command for everything a player is owed). The seller is paid
  automatically.
- **Selling with no bids** behaves exactly as before: item returns to the
  seller (or waits in `/ah collect` if offline).

### 1.2 The Escrow Model — Why and How

The critical design decision: **a bid charges the money immediately** and
holds it in a dedicated system account. Charging at win-time instead (some
plugins do this) would allow players to bid, win, and be unable to pay —
forcing the sale to fall through and punishing the seller. With escrow, the
money is *always already there* when the auction ends.

All money movements use the hardened `transferAtomicWithLedger` primitive —
the same audit-2.1.3 mechanism as auction settlement — so **every leg is one
atomic SQLite transaction whose ledger evidence commits WITH the money**:

```
BID PLACED    bidder ──(BID_PLACED)──> ESCROW        [atomic transfer]
OUTBID        ESCROW ──(BID_REFUNDED)──> prev bidder [atomic transfer]
BUY-NOW WIN   ESCROW ──(BID_REFUNDED)──> top bidder  [atomic transfer]
CANCEL        ESCROW ──(BID_REFUNDED)──> top bidder  [atomic transfer]
AUCTION EXPIRES (with bids)
              ESCROW ──(AUCTION_SOLD + AUCTION_WON)──> seller [atomic]
```

The **escrow account** (`com.solidus.economy.EscrowAccount`) is a sentinel
all-zero UUID, pre-created at balance 0 so no phantom "starting balance"
money is ever minted into it. At any instant the escrow balance equals the
sum of all open top bids — the startup verifies this and warns loudly on any
mismatch (see 1.7). Escrow is **excluded from `/baltop`** (it is not a
player) but deliberately *included* in economy-wide statistics (the money is
in circulation, just locked).

Bid claims are **exactly-once** by construction: the top-bid slot is claimed
with a conditional `UPDATE ... WHERE current_bid IS NULL OR current_bid < ?`
inside the single-threaded auction executor, so two simultaneous bids can
never both win — the loser is refunded automatically.

### 1.3 Anti-Snipe Protection

A bid placed within the **final 10 minutes** of an auction extends its
deadline by **5 minutes** (the classic anti-snipe rule). Extensions cap at
**12 per listing** so a listing cannot be prolonged forever by two colluding
accounts. Constants live in `BidRules` (see §4).

### 1.4 Every Refund Path

The system is self-healing. The top bidder's escrow is refunded when:

| Event | Mechanism |
| --- | --- |
| Outbid by a higher bid | Immediate refund on claim success |
| Someone re-bids the same amount in a race | Losing claimant refunded |
| Seller cancels the listing | Inline refund + startup backstop |
| Anyone buys the listing via buy-now | Refund AFTER the sale settlement commits |
| Server crash anywhere in the middle | **Startup sweep** (`refundOrphanedBidStates`) refunds every bid whose listing is no longer ACTIVE |
| Bidder had insufficient funds | Charge never happened — nothing to refund |
| DB error during claim | Immediate self-refund in the error path |

The startup sweep guarantees the invariant: **no bidder money can ever be
trapped in escrow by a listing that can never settle.**

### 1.5 GUI & Commands

- Auction GUI lore now distinguishes `Price:` (buy-now-only) from
  `Current Bid / Buy Now / Next Bid Min` (bidding-enabled).
- `Left-Click` = buy now (unchanged); `Right-Click` = bid chat prompt
  (bidding-enabled listings only). The click hardening from audit 2.1.3 is
  preserved: forged click types are still rejected.
- New command: `/ah bid <listing_uuid> <amount>`; extended:
  `/ah sell <price> <startbid>`.
- Chat prompt input accepts `1,500.50` style numbers (commas stripped) and
  `cancel` (or `الغاء`) aborts. A consumed prompt message is **not broadcast**
  to other players (privacy), and prompts expire after 5 minutes.

### 1.6 Database Schema

Bid state lives in a **separate** `auctions.db` table rather than new columns
on `auction_listings`, keeping the `AuctionEntry` record and every existing
consumer/test untouched. A listing without a bid-state row is simply a
buy-now-only listing — the feature is purely additive:

```sql
CREATE TABLE IF NOT EXISTS auction_bid_state (
    listing_id TEXT PRIMARY KEY NOT NULL,
    start_price REAL NOT NULL,
    current_bid REAL,                -- NULL = no bids yet
    current_bidder_uuid TEXT,
    current_bidder_name TEXT,
    bid_count INTEGER NOT NULL DEFAULT 0,
    extensions_used INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS auction_bids (         -- append-only history
    bid_id INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id TEXT NOT NULL,
    bidder_uuid TEXT NOT NULL,
    bidder_name TEXT NOT NULL,
    amount REAL NOT NULL,
    bid_timestamp INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auction_won_items (    -- offline winner delivery
    win_id INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id TEXT NOT NULL UNIQUE,              -- idempotent inserts
    winner_uuid TEXT NOT NULL,
    winner_name TEXT NOT NULL,
    material_name TEXT NOT NULL,
    item_nbt TEXT,
    quantity INTEGER NOT NULL,
    win_price REAL NOT NULL,
    won_timestamp INTEGER NOT NULL
);
```

Won auctions are archived into `auction_sold_history` with
`settled_reason = 'WON'` and the buyer attributed — so the existing
exactly-once settlement guarantee and audit trail apply to bidding sales
identically to buy-now sales.

### 1.7 Crash Recovery

On every startup, after the existing orphaned-SOLD-row sweep:

1. **Orphaned bid-state sweep** — every `auction_bid_state` row whose listing
   is not an ACTIVE row (bought / cancelled / archived / vanished) has its
   top bid refunded from escrow and the row deleted.
2. **Escrow consistency check** — the escrow account balance is compared to
   the sum of open top bids. A mismatch (possible only from a crash in the
   millisecond window between the escrow charge and the bid-state claim)
   logs a prominent `ESCROW CONSISTENCY` warning with amounts for admin
   reconciliation via the `BID_PLACED` / `BID_REFUNDED` ledger rows.

---

## 2. Direct Trade Window (`/trade`)

The **most-requested missing feature**: previously players traded by
`/pay` then dropping items — the classic half-payment scam. The trade window
shows both sides' offers live (items AND money) and executes **only when both
players press READY** — the same mutual-preview model as TradeMe and
Essentials-style trade plugins, which eliminates roughly the entire scam
class the community complained about.

### 2.1 Player-Facing Behaviour

1. `/trade <player>` sends a request (both players must be within 10 blocks;
   30-second TTL; 5-second cooldown between requests).
2. The target accepts with `/trade accept` (or refuses with `/trade deny`).
3. Both players get a 54-slot trade window. Your offer slots are the LEFT 3
   columns; the partner's live offer mirrors into the RIGHT 3 columns. Every
   player sees the same layout from their own perspective.
4. Offering items: normal inventory interaction — left-click to place, right
   click to split/place-one, shift-click to move stacks. Items placed leave
   your inventory immediately (escrow) and are protected while the window is
   open.
5. Offering money: click the gold ingot (`Your Money Offer`), **type the
   amount in chat** (0 clears, `cancel` aborts). The amount is private —
   the prompt message is not broadcast.
6. Press READY (lime dye). When **both** are ready the trade executes
   instantly: money moves atomically both ways, items swap. A trade with no
   items and no money on either side is rejected.
7. Cancel by closing the window (ESC), the barrier button, `/trade cancel`,
   disconnecting, or letting the session idle for 15 minutes. **Every offered
   item always returns to its owner.**

### 2.2 The Anti-Scam Contract

These rules are enforced at the state-machine level (`TradeSession`) and are
unit-tested:

- **Escrow on placement**: an offered item is OUT of its owner's inventory
  the moment it appears in the window. The offerer cannot spend/drop/sell it
  elsewhere, and it cannot vanish from the window.
- **Any change un-readies BOTH sides** — placing/removing an item or editing
  the money offer resets both ready flags. The green light only stays on
  while both offers are untouched, so a last-second bait-and-switch is
  impossible.
- **Display-only partner columns**: no click type can move the partner's
  offered items (defense-in-depth mirrors `DisplaySlot`, enforced manually in
  the handler like the sell GUI).
- **Nothing is ever dropped by the trade system itself**: clicking outside
  the window returns the cursor item to your inventory (or your offer slots),
  never the ground; cancel paths return items to inventories with a
  drop-at-feet fallback only when the inventory is full.
- **Forged/odd click types are rejected** (plain PICKUP left/right only for
  offers), mirroring the audit-2.1.3 GUI hardening.

### 2.3 Execution Order & Failure Handling

Execution is strictly ordered so no player can ever lose items to a money
failure:

```
1. markExecuting()  - further clicks are ignored
2. MONEY  (async, atomic):
   leg A->B (if any) -> leg B->A (if any)
   - leg 2 fails  => leg 1 is ROLLED BACK (atomic transfer back)
   - any failure  => whole trade aborted, ALL items returned to owners
3. ITEMS (only after money fully settled):
   take initiator's container items -> partner inventory (drop fallback)
   take partner's container items   -> initiator inventory
4. Ledger: TRADE_SEND + TRADE_RECEIVE rows per direction
5. markCompleted(), windows close, both players notified
```

Money moves through `transferOffline`, so **Governance transfer hooks,
limits and taxes apply to trade money exactly like `/pay`** — no new hook
surface was added and double-collection logic stays intact.

### 2.4 GUI Layout

```
+--------------------------------------------------------------------+
| [YOU: name]  pane  TRADE  pane  [PARTNER: name]                    |  0..8
| [a][b][c]   pane [MY $][THEIR $] pane   [x][y][z]                  |  offer rows
| [d][e][f]   pane   pane   pane   pane   [w][u][v]                  |
| [g][h][i]   pane [READY][THEIR READY] pane [s][t][r]               |  status row
| [j][k][l]   pane   pane  [CANCEL]  pane [p][q][n]                  |
+--------------------------------------------------------------------+
   MY OFFER = left 3 columns (15 slots)      THEIR OFFER = right 3 columns
```

Both windows view mirrored containers: your items are always rendered on
YOUR left, the partner's on YOUR right, updated live with a full state
resync on every change (the PR#13 anti-ghost-item guarantee applies to the
trade window as well).

### 2.5 Integration With Governance

- Money legs fire `allowTransfer` (veto) and `afterTransfer` (notify) —
  limits, freezes and taxes work unchanged.
- Item-only legs fire no money hooks (no money moves); the full trade is in
  the ledger (`TRADE_SEND` / `TRADE_RECEIVE`).
- Companion mods compiled against 2.1.x keep working: unknown ledger codes
  fall back safely through `TransactionLog.Type.fromCode`.

---

## 3. New Permissions & Ledger Types

| Node | Default | Controls |
| --- | --- | --- |
| `solidus.command.auction.bid` | all players | `/ah bid` + GUI right-click bidding |
| `solidus.command.trade` | all players | the whole `/trade` family |

New `TransactionLog.Type` values: `BID_PLACED`, `BID_REFUNDED`,
`AUCTION_WON`, `TRADE_SEND`, `TRADE_RECEIVE`. `/transactions` renders them
with distinct icons/colors; the CSV export includes them automatically.

## 4. Configuration Constants

Tunable constants (code-level, hot-reload config planned):

| Constant | Value | Where |
| --- | --- | --- |
| Minimum raise | max(5% of current bid, S$ 1.00) | `BidRules.MIN_RAISE_*` |
| Anti-snipe window | 10 min before expiry | `BidRules.ANTI_SNIPE_WINDOW_MS` |
| Anti-snipe extension | 5 min per trigger | `BidRules.ANTI_SNIPE_EXTENSION_MS` |
| Max anti-snipe extensions | 12 per listing | `BidRules.MAX_ANTI_SNIPE_EXTENSIONS` |
| Trade distance | 10 blocks | `TradeManager.MAX_TRADE_DISTANCE` |
| Trade request TTL / cooldown | 30 s / 5 s | `TradeManager.REQUEST_*` |
| Trade idle timeout | 15 min | `TradeManager.IDLE_SESSION_TTL_MS` |
| Chat prompt TTL | 5 min | `ChatPrompts.PROMPT_TTL_MS` |

## 5. Testing

New test suites (all storage/state-level, no Minecraft runtime needed —
mirroring the project's existing test philosophy):

- `BidEscrowFlowTest` — escrow zero-init, charge/refund/release with ledger
  evidence, insufficient-funds and overflow safety, exactly-once conditional
  bid claims, baltop exclusion, `BidRules` arithmetic (raise floor, anti-snipe
  windows, caps).
- `TradeSessionStateTest` — the anti bait-and-switch contract (any money
  change un-readies both), empty-trade detection, side resolution, state
  lifecycle, per-side container isolation.

Full suite: **309+ tests green**. The GUI item-placement flow and the
execution order are covered by the documented invariants and should get a
manual smoke test on a live server (see `notes/AGENT_NOTES.md` for the
suggested checklist).
