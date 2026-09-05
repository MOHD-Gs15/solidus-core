# Solidus Economy — Server-Side Minecraft Fabric Mod

[![Solidus Family](https://img.shields.io/badge/Solidus_Family-2.1.4-8B5CF6.svg)](VERSIONING.md)
[![Platform](https://img.shields.io/badge/Platform-Fabric-blue.svg)](https://fabricmc.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-green.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![Server-Side](https://img.shields.io/badge/Server_Side-Only-brightgreen.svg)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Economy](https://img.shields.io/badge/Type-Economy_Mod-8B5CF6.svg)]()

**Server-side economy engine for Minecraft Fabric — virtual currency, server shop, auction house, and crash-resilient persistence. No client mods required.**

Stable economies · Vanilla compatibility · Zero client installation · Minecraft 26.1.x Ready

[Economy](#-economy) · [Server Shop](#-server-shop-shop) · [Auction House](#-auction-house-ah) · [Sell System](#-sell-system-sell) · [API](#-inter-mod-api) · [Quick Start](#-quick-start) · [Ecosystem](#-solidus-ecosystem)

---

<!-- Schema.org Structured Data for Search Engines
{
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "name": "Solidus Economy",
  "applicationCategory": "GameModification",
  "operatingSystem": "Minecraft 26.1.x",
  "programmingLanguage": "Java 25",
  "runtimePlatform": "Fabric Loader 0.19.4+",
  "license": "MIT",
  "description": "Server-side economy engine for Minecraft Fabric with virtual currency, GUI shop, auction house, and crash-resilient persistence. No client mods required.",
  "author": { "@type": "Person", "name": "MOHD-Gs15", "url": "https://github.com/MOHD-Gs15" },
  "url": "https://github.com/MOHD-Gs15/solidus-core",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
-->

## Why Solidus?

Solidus is a complete **server-side economy and commerce engine** for Minecraft Fabric. It is designed from the ground up for long-term survival servers that need a stable, inflation-resistant virtual economy — without requiring client mods, resource packs, custom assets, or plugin stacks.

Every transaction is persisted through crashes using async SQLite with WAL journaling. Every shop price is hot-reloadable without restarting the server. Every API call is thread-safe and available through reflection — zero compile-time dependency for third-party integration.

### Highlights

* **Fully server-side architecture** — works with any vanilla client, zero client installation
* **Built-in virtual economy** with async persistence via SQLite (WAL mode, `CompletableFuture`-based)
* **GUI-based server shop** — 11 categories, 185 configured items, hot-reload pricing
* **Player-driven auction house** — buy-now listings plus optional **bidding** with money escrow, anti-snipe protection, and self-healing refunds
* **Direct player-to-player trade** (`/trade`) — a dual-preview window for items AND money that executes only when both players are ready
* **Hot-reload configuration** — change prices, categories, and settings without restart
* **Inter-mod API** (`SolidusAPI`) — reflection-based, zero compile-time dependency for third-party mods
* **Crash-resilient data storage** — WAL journaling ensures no data loss on server crash
* **Shulker box support** — all sell commands scan and process items inside shulker boxes

---

## Solidus Ecosystem

Solidus Core is the foundation of the **Solidus Economy Ecosystem** — a suite of server-side Fabric mods that work together to create a complete, balanced economy for Minecraft servers.

| Module | License | Description |
|--------|---------|-------------|
| **solidus-core** | **MIT** | **Economy engine, server shop, auction house** (this repo) |
| [solidus-analytics](https://github.com/MOHD-Gs15/solidus-analytics) | Proprietary | Economy intelligence dashboard, inflation tracking, fraud detection, live web dashboard (AES-256-GCM encrypted) |
| [Solidus-Enforcer](https://github.com/MOHD-Gs15/Solidus-Enforcer) | MIT | Bounty hunting, hunter license system, alliance rewards, autonomous anti-monopoly bounties |
| [Solidus-Governance](https://github.com/MOHD-Gs15/Solidus-Governance) | Proprietary | Economy administration, progressive taxation, immutable audit logging, point-in-time rollback recovery |
| [solidus-territory](https://github.com/MOHD-Gs15/solidus-territory) | MIT | Polygon-based land claiming, rent system, territory trading, visual particle borders |

Each module integrates with Solidus Core through **reflection-based bridges** — zero compile dependency, automatic activation when Core is present, graceful degradation when absent.

---

## Features

### Economy

A lightweight virtual economy designed for multiplayer survival servers. All operations are persisted asynchronously through SQLite with WAL journaling — the server thread never blocks on disk I/O, and data survives crashes.

* Configurable starting balance
* Secure player transfers (`/pay`) — online and offline, validated server-side
* Global wealth leaderboard (`/baltop`)
* Full transaction history (`/transactions`) with pagination + CSV export
* Offline notifications on login — players see missed payments
* Currency symbol: `S$` (configurable)

### Server Shop (`/shop`)

Virtual shop interface powered entirely by the server. Uses vanilla container packets — no client mod or resource pack needed. Players see a GUI with categorized items, buy with one click, and items appear directly in their inventory.

* 11 categories with 185 configured items
* Stack trading support — buy in bulk
* Item search (`/shop search <query>`) — partial name matching
* Hot-reload configuration — edit `shop.json` and run `/shop reload` (OP 2+) without restart
* Display-only GUI protection — no item movement exploits (server validates every click)
* Per-item buy and sell pricing — fully operator-controlled in `shop.json`

### Auction House (`/ah`)

Marketplace for player-to-player trading. Players list items from their inventory, other players browse, bid, or buy. The server handles listing, bidding, expiration, refunds, and notifications — all server-side.

* Item listing directly from inventory (`/ah sell <price>`)
* **Optional bidding** (`/ah sell <price> <startbid>`) — bid money is held in a system escrow account the moment a bid is placed, refunded instantly on outbid/cancel/buy-now, and released to the seller when the auction expires with a winner
* **Anti-snipe protection** — a bid in the last 10 minutes extends the auction by 5 minutes (capped at 12 extensions)
* Won items are delivered immediately if the winner is online, or wait in `/ah collect` if offline
* Listing expiration with automatic item return to seller
* Reclaim expired and won items (`/ah collect`)
* Cancel own listings (`/ah cancel <uuid>`) — the top bidder is refunded automatically
* Sort listings (`/ah sort <newest|price_low|price_high|material>`) and free-text search (`/ah search <term>`)
* Listing fee support — configurable to add money sinks
* Offline seller notifications — players see sold items when they log in

See [docs/FEATURES_TRADE_BIDDING.md](docs/FEATURES_TRADE_BIDDING.md) for the full bidding reference.

### Direct Trade (`/trade`)

A mutual-preview trade window between two nearby players — items AND money on both sides — that executes only when **both** players press READY. This replaces the old `/pay`-then-drop-items flow that enabled half-payment scams.

* `/trade <player>` sends a request (both players within 10 blocks, 30-second TTL, 5-second cooldown)
* 54-slot window: your offer on the left 3 columns, your partner's live offer mirrored on the right
* Offer items (real inventory interaction) and money (click the gold ingot, type the amount in chat)
* Any change to either offer un-readies BOTH sides — a last-second bait-and-switch is impossible
* Offered items are held by the session the moment they are placed; every cancel path (ESC, close, disconnect, idle timeout, `/trade cancel`) returns them to their owners
* Money legs run through the same atomic transfer as `/pay`, so governance hooks and limits apply

See [docs/FEATURES_TRADE_BIDDING.md](docs/FEATURES_TRADE_BIDDING.md) for the full trade reference.

### Sell System (`/sell`)

Sell items directly from your inventory or through a visual GUI. Supports shulker box scanning, partial name matching, and configurable per-material pricing.

* **`/sell gui`** — Opens a virtual chest interface where you place items to sell. Sellable items are processed and paid for; unsellable items are returned to your inventory (or dropped on the ground if inventory is full).
* **`/sell all`** — Instantly sells every sellable item in your inventory.
* **`/sell all <item>`** — Sells all instances of a specific item (e.g., `/sell all ender_pearl`). Supports both underscores and spaces, and partial name matching.

#### Shulker Box Support

All sell commands fully support shulker boxes:

* Items inside shulker boxes are scanned and sold just like regular inventory items.
* When using `/sell gui`, placing a shulker box in the sell window will sell all sellable contents inside it. Unsellable items stay inside the shulker box, and the shulker box is returned to your inventory.
* When using `/sell all` or `/sell all <item>`, matching items inside shulker boxes are sold as well. The shulker box is updated in place with only the remaining unsellable items.
* If all items inside a shulker box are sold and the shulker box itself is sellable (listed in the shop), it will also be sold automatically.

### Economy Price Control

There is no automatic "farm detection" or percentage-reduction mechanism in the code. Price control is fully manual and fully operator-owned: every material's buy and sell price is set explicitly per material in `shop.json` (keys `buy-price` / `sell-price`), and prices can be lowered on farmed resources at any time and applied live via `/shop reload`. Setting the sell price of iron ingots below the buy price — or to `null` to make an item unsellable — is the supported way to counter inflation from automated farms. Sell prices are charged/paid exactly as configured; the server applies no hidden multipliers.

### Inter-Mod API

Solidus provides a public API (`SolidusAPI`) for other Fabric mods to integrate with the economy system. The API uses **Java MethodHandle reflection** — meaning zero compile-time dependency. Third-party mods can call Solidus methods without importing Solidus classes at compile time. If Solidus Core is not installed on the server, the reflection calls simply return empty results rather than crashing.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full API reference, method signatures, and integration examples.

### Transaction Hooks (new in 2.1.0)

Solidus 2.1.0 adds a **transaction hook system** (`SolidusTransactionHook`) that lets companion mods intercept economy traffic at every money-movement point — the mechanism Solidus Governance uses to enforce daily limits, trading locks, frozen accounts, and transaction taxes:

| Flow | Veto hook (before) | Notification hook (after) |
|------|--------------------|---------------------------|
| `/pay` + API transfers | `allowTransfer` | `afterTransfer` |
| Auction listing | `allowAuctionListing` | `afterAuctionListing` |
| Auction purchase | `allowAuctionPurchase` | `afterAuctionSale` |
| Shop purchase | `allowShopPurchase` | `afterShopPurchase` |
| Shop sell (GUI · `/sell all` · Sell GUI) | `allowShopSell` | `afterShopSell` |

A veto denial aborts the transaction cleanly — balances untouched, items stay in hand, and the denial reason is shown to the player. Notifications fire only after the transaction has fully settled. Registration goes through `SolidusAPI.registerTransactionHook(hook)` (reflection-friendly; duplicate hook names are ignored), and the dispatch is **fail-open**: a hook that throws is logged and skipped for that transaction, so one misbehaving mod can never wedge the economy.

---

## Quick Start

### Installation

> **Requirements:** Minecraft 26.1.x · Java 25 · Fabric Loader 0.19.4+ · Fabric API 0.155.2+

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
3. Download the latest Solidus release from [Releases](https://github.com/MOHD-Gs15/solidus-core/releases)
4. Place both `.jar` files into your server's `mods/` folder
5. Start the server
6. Configure `config/solidus/shop.json` to customize your economy

**No client installation required.** Players join with standard Minecraft clients and everything works.

### First-Time Setup

```
/balance                                 ← Check your starting balance (default: 500 S$)
/pay PlayerName 100                      ← Send money to another player
/shop                                    ← Open the server shop GUI
/sell all                                ← Sell all sellable items in your inventory
/ah sell 500                             ← List an item on the auction house
/baltop                                  ← See the wealth leaderboard (10 per page)
/baltop 2                                ← Jump to leaderboard page 2
```

### Adding Ecosystem Modules

Once Solidus Core is running, you can add any combination of ecosystem modules:

| Module | What It Adds | Installation |
|--------|-------------|-------------|
| [solidus-analytics](https://github.com/MOHD-Gs15/solidus-analytics) | Live economy dashboard, inflation tracking, fraud detection | Drop JAR in `mods/` |
| [Solidus-Enforcer](https://github.com/MOHD-Gs15/Solidus-Enforcer) | Bounty hunting, hunter licenses, anti-monopoly system | Drop JAR in `mods/` |
| [Solidus-Governance](https://github.com/MOHD-Gs15/Solidus-Governance) | Taxation, audit logging, rollback recovery | Drop JAR in `mods/` |
| [solidus-territory](https://github.com/MOHD-Gs15/solidus-territory) | Polygon land claiming, rent, territory trading | Drop JAR in `mods/` |

All modules auto-detect Solidus Core via reflection and activate automatically. No additional configuration needed for basic integration.

---

## Commands

| Command | Description |
| --- | --- |
| `/balance` | Show balance |
| `/pay <player> <amount>` | Transfer to online player |
| `/pay offline <player> <amount>` | Transfer to offline player |
| `/baltop [page]` | Wealth leaderboard, 10 per page (ranks continue across pages) |
| `/shop` | Open shop |
| `/shop search <query>` | Search shop items |
| `/sell gui` | Open sell GUI (place items to sell) |
| `/sell all` | Sell all sellable items in inventory |
| `/sell all <item>` | Sell all of a specific item (e.g. `ender_pearl`) |
| `/ah` | Open auction |
| `/ah sell <price> [startbid]` | Create listing; optional opening bid enables **bidding** |
| `/ah bid <uuid> <amount>` | Bid on a bidding-enabled listing (or right-click it in the GUI) |
| `/ah collect` | Reclaim expired items **and won auction items** |
| `/ah cancel <uuid>` | Cancel own listing (top bidder auto-refunded) |
| `/ah sort <criteria>` | Sort listings (newest/price_low/price_high/material) |
| `/ah search <term>` | Free-text search across active listings (cheapest first) |
| `/trade <player>` | Request a direct trade with a nearby player |
| `/trade accept` / `deny` | Respond to a pending trade request |
| `/trade cancel` | Cancel the trade you are in |
| `/transactions [page]` | Transaction history (10 per page) |
| `/transactions export [days]` | Export your own history to CSV (default 7 days) |
| `/transactions exportall [days]` | Export the full ledger to CSV (OP 2+) |

---

## Configuration

Solidus generates configuration automatically on first run. All configuration supports **hot reload** — edit the file and run the reload command without restarting your server.

**Location:** `config/solidus/shop.json`

**Example:**

```json
{
  "startingBalance": 500,
  "currency": "S$",
  "listingFee": 2
}
```

Supports:

* Categories and per-item pricing (buy and sell prices per material)
* Optional top-level economy keys: `startingBalance`, `currency`, `listingFee` (whole percent)
* Text formatting and currency symbol customization
* Hot reload without restart — edit `shop.json` and run `/shop reload` (OP 2+)

---

## Compatibility

| Component | Requirement | Notes |
| --- | --- | --- |
| Minecraft | 26.1.x | Uses Mojang Official Mappings (no Yarn needed since 26.1) |
| Loader | Fabric 0.19.2+ | Server-side only |
| Fabric API | 0.149.0+ | Required |
| Java | 25 | Required |
| Client | Any (vanilla or modded) | No client installation needed |
| Database | SQLite (bundled) | WAL journaling for crash resilience |
| Side | Server only | Zero client-side dependencies |

---

## Architecture

```
com.solidus/
├── SolidusMod.java              — Entry point, lifecycle, tick scheduler
├── api/
│   ├── SolidusAPI.java          — Public API (reflection-safe, thread-safe)
│   ├── SolidusTransactionHook.java — Veto + notification hook interface
│   ├── EconomyHooks.java        — Hook registry & dispatch (fail-open)
│   ├── SolidusIntegration.java  — Reference integration example
│   ├── SolidusPermissions.java  — Permission node constants
│   ├── PermissionChecker.java   — LuckPerms + OP-level checking
│   └── PermissionConfig.java    — permissions.json loader/generator
├── economy/
│   ├── EconomyEngine.java       — Lifecycle coordinator (storage + balances)
│   ├── SQLiteStorage.java       — SQLite + WAL + single-thread executor + cache
│   ├── BalanceManager.java      — Validated balance/transfer API
│   ├── TransactionLog.java      — Ledger, CSV export, offline notifications
│   └── EscrowAccount.java       — Bid-escrow system account (sentinel UUID)
├── auction/
│   ├── AuctionManager.java      — Listings, purchase, expiry, bidding, recovery
│   ├── AuctionEntry.java        — Immutable listing record
│   ├── ListingStatus.java       — ACTIVE/SOLD/EXPIRED enum
│   ├── BidRules.java            — Pure bid validation + anti-snipe rules
│   ├── BidState.java            — Per-listing bid state snapshot
│   ├── AuctionGUI.java          — Browse/search/buy/bid interface
│   ├── AuctionScreenHandler.java — Click routing (buy / bid prompt)
│   └── AuctionDummyContainer.java — Display-only container
├── trade/
│   ├── TradeManager.java        — Requests, sessions, execution, reaping
│   ├── TradeSession.java        — Player-agnostic session state machine
│   ├── TradeContainer.java      — 54-slot session container (item escrow)
│   ├── TradeGUI.java            — Window layout + display builders
│   └── TradeScreenHandler.java  — Manual cursor movement + click safety
├── chat/
│   └── ChatPrompts.java         — "Type amount in chat" prompt service
├── commands/
│   ├── BalanceCommand.java      — /balance, /bal
│   ├── PayCommand.java          — /pay, /pay offline
│   ├── BaltopCommand.java       — /baltop
│   ├── ShopCommand.java         — /shop, /shop search, /shop reload
│   ├── SellCommand.java         — /sell gui, /sell all [item]
│   ├── AuctionCommand.java      — /ah sell/bid/collect/cancel/sort/search
│   ├── TradeCommand.java        — /trade <player>|accept|deny|cancel
│   └── TransactionsCommand.java — /transactions, export, exportall
├── shop/
│   ├── ShopManager.java         — Config parsing + buy/sell transactions
│   ├── ShopGUI.java             — Virtual chest builder (bordered layout)
│   ├── ShopGUILayout.java       — Pure-Java centering layout engine
│   ├── ShopScreenHandler.java   — Click rewriting handler
│   └── ShopDummyContainer.java  — Display-only container
├── sell/
│   ├── SellGUI.java             — Sell window builder
│   ├── SellScreenHandler.java   — Full cursor item movement (825 lines)
│   └── SellContainer.java       — Real container (stores player items)
├── gui/
│   └── DisplaySlot.java         — Display-only Slot (no place/pickup/set)
├── mixin/
│   └── ServerPlayerEntityMixin.java — Click packet interception + resync
├── networking/
│   ├── PacketHandler.java       — Click routing gateway + full resyncs
│   └── RateLimiter.java         — 150ms click / 1s pay cooldowns
└── util/
    ├── ConfigManager.java       — File I/O, JSON loading, JAR resource copying
    ├── CurrencyUtil.java        — Currency constants, formatting, validation
    └── TextUtil.java            — Component utilities, material names
```

### Key Design Decisions

1. **Async SQLite with WAL journaling** — All database operations run on a dedicated single-thread `ExecutorService` for serial consistency. Returns use `CompletableFuture` so the server thread never blocks. WAL mode ensures crash resilience — no committed transaction is lost even on hard shutdown.

2. **Reflection-based API** — `SolidusAPI` exposes economy operations through `MethodHandle` reflection. Third-party mods call these methods without any compile-time dependency on Solidus. If Solidus is absent, calls return empty/default values rather than throwing `NoClassDefFoundError`.

3. **Server-side GUI via vanilla packets** — Shop, auction, sell, and trade interfaces use vanilla container/window packets. No custom client mod, no resource pack, no custom network channel. Works on any client — vanilla, Fabric, Forge (via protocol translation).

4. **Hot-reload configuration** — Operators adjust prices, add categories, or modify items in `shop.json` and apply them live with `/shop reload` (OP 2+), without restarting the server. This enables live economy tuning in response to market conditions.

---

## FAQ

### Does this require client mods?

**No.** Players join using standard Minecraft clients. The shop and auction house GUIs are rendered using vanilla container packets — no client mod, resource pack, or custom asset needed.

### Works with proxy networks (BungeeCord, Velocity)?

**Yes.** Solidus runs on backend servers behind proxies. Economy data is per-server (stored in local SQLite).

### Supports offline mode?

**Yes**, but online-mode servers are recommended for security. UUID resolution works in both modes.

### Can prices be changed live without restart?

**Yes.** Configuration supports hot reload — edit `shop.json` and reload without restarting the server. This is critical for active servers where restarts cause player disruption.

### Does Solidus integrate with other mods?

**Yes.** Solidus provides a stable public API (`SolidusAPI`) for other Fabric mods. Integration works via `MethodHandle` reflection with zero compile-time dependency. Third-party mods can check balances, process transfers, and hook into economy events. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full API reference.

### How does Solidus protect against inflation from automated farms?

There is no automatic farm detection — price control is manual and operator-owned. Server operators set each material's buy and sell price explicitly in `shop.json` (and can lower the sell price of farmed items such as iron ingots, or make them unsellable entirely). Edits apply live via `/shop reload` without a restart, which is the supported way to counter farm-driven inflation.

### What happens to economy data if the server crashes?

All transactions are persisted through SQLite with WAL (Write-Ahead Logging) journaling. WAL mode guarantees that no committed transaction is lost — even during a hard crash or power failure. Data integrity is maintained at the database level, not the application level.

### Is Solidus Core free?

**Yes.** Solidus Core is licensed under the MIT License — fully open-source, no premium tier, no feature gating. Some ecosystem modules (Analytics, Governance) offer premium features with a license key, but Core itself is completely free.

---

## Download

| Platform | Link |
| --- | --- |
| GitHub Releases | [Latest Release](https://github.com/MOHD-Gs15/solidus-core/releases) |
| Modrinth | [MOHD_Gs on Modrinth](https://modrinth.com/user/MOHD_Gs) |

---

## Contributing

Contributions are welcome.

* Report issues via [GitHub Issues](https://github.com/MOHD-Gs15/solidus-core/issues)
* Suggest features or improvements
* Submit pull requests

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for technical details, API reference, and contribution guidelines.

---

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details. All features are 100% free and open-source with no premium tier or feature gating.

---

## Keywords

`minecraft economy mod` · `minecraft fabric mod` · `minecraft server economy` · `minecraft virtual currency` · `minecraft auction house` · `minecraft server shop` · `fabric economy plugin` · `minecraft survival economy` · `server-side minecraft mod` · `minecraft commerce engine` · `solidus economy` · `minecraft inflation protection`

---

Built by [MOHD-Gs15](https://github.com/MOHD-Gs15) · [Email](mailto:mohdmxmxm@gmail.com) · Discord: **mohd_gs** · Part of the [Solidus Economy Ecosystem](https://github.com/MOHD-Gs15)
