# Solidus Economy — Server-Side Minecraft Fabric Mod

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
  "runtimePlatform": "Fabric Loader 0.19.2+",
  "license": "MIT",
  "description": "Server-side economy engine for Minecraft Fabric with virtual currency, GUI shop, auction house, and crash-resilient persistence. No client mods required.",
  "author": { "@type": "Person", "name": "MOHD_Gs", "url": "https://github.com/mohd-gs" },
  "url": "https://github.com/mohd-gs/solidus-core",
  "offers": { "@type": "Offer", "price": "0", "priceCurrency": "USD" }
}
-->

## Why Solidus?

Solidus is a complete **server-side economy and commerce engine** for Minecraft Fabric. It is designed from the ground up for long-term survival servers that need a stable, inflation-resistant virtual economy — without requiring client mods, resource packs, custom assets, or plugin stacks.

Every transaction is persisted through crashes using async SQLite with WAL journaling. Every shop price is hot-reloadable without restarting the server. Every API call is thread-safe and available through reflection — zero compile-time dependency for third-party integration.

### Highlights

* **Fully server-side architecture** — works with any vanilla client, zero client installation
* **Built-in virtual economy** with async persistence via SQLite (WAL mode, `CompletableFuture`-based)
* **GUI-based server shop** — 11 categories, 120+ configured items, hot-reload pricing
* **Player-driven auction house** — listing, expiration, reclaim, sorting, offline notifications
* **Hot-reload configuration** — change prices, categories, and settings without restart
* **Inter-mod API** (`SolidusAPI`) — reflection-based, zero compile-time dependency for third-party mods
* **Crash-resilient data storage** — WAL journaling ensures no data loss on server crash
* **Anti-farm economy protection** — configurable sell-price reductions for farmed resources
* **Shulker box support** — all sell commands scan and process items inside shulker boxes

---

## Solidus Ecosystem

Solidus Core is the foundation of the **Solidus Economy Ecosystem** — a suite of server-side Fabric mods that work together to create a complete, balanced economy for Minecraft servers.

| Module | License | Description |
|--------|---------|-------------|
| **solidus-core** | **MIT** | **Economy engine, server shop, auction house** (this repo) |
| [solidus-analytics](https://github.com/mohd-gs/solidus-analytics) | Proprietary | Economy intelligence dashboard, inflation tracking, fraud detection, live web dashboard (AES-256-GCM encrypted) |
| [Solidus-Enforcer](https://github.com/mohd-gs/Solidus-Enforcer) | MIT | Bounty hunting, hunter license system, alliance rewards, autonomous anti-monopoly bounties |
| [Solidus-Governance](https://github.com/mohd-gs/Solidus-Governance) | Proprietary | Economy administration, progressive taxation, immutable audit logging, point-in-time rollback recovery |
| [solidus-territory](https://github.com/mohd-gs/solidus-territory) | MIT | Polygon-based land claiming, rent system, territory trading, visual particle borders |

Each module integrates with Solidus Core through **reflection-based bridges** — zero compile dependency, automatic activation when Core is present, graceful degradation when absent.

---

## Features

### Economy

A lightweight virtual economy designed for multiplayer survival servers. All operations are persisted asynchronously through SQLite with WAL journaling — the server thread never blocks on disk I/O, and data survives crashes.

* Configurable starting balance
* Secure player transfers (`/pay`) — online and offline, validated server-side
* Global wealth leaderboard (`/baltop`)
* Full transaction history (`/transactions`) with pagination
* Offline notifications on login — players see missed payments
* Currency symbol: `S$` (configurable)

### Server Shop (`/shop`)

Virtual shop interface powered entirely by the server. Uses vanilla container packets — no client mod or resource pack needed. Players see a GUI with categorized items, buy with one click, and items appear directly in their inventory.

* 11 categories with 120+ configured items
* Stack trading support — buy in bulk
* Item search (`/shop search <query>`) — partial name matching
* Hot-reload configuration — edit `shop.json` and reload without restart
* Display-only GUI protection — no item movement exploits (server validates every click)
* Per-item buy and sell pricing
* Anti-farm sell-price reductions — configurable per material to counter automated farm inflation

### Auction House (`/ah`)

Marketplace for player-to-player trading. Players list items from their inventory, other players browse and buy. The server handles listing, expiration, refunds, and notifications — all server-side.

* Item listing directly from inventory (`/ah sell <price>`)
* Listing expiration with automatic item return to seller
* Reclaim expired items (`/ah collect`)
* Cancel own listings (`/ah cancel <uuid>`)
* Sort listings by price, newest, or material (`/ah sort`)
* Listing fee support — configurable to add money sinks
* Offline seller notifications — players see sold items when they log in

### Sell System (`/sell`)

Sell items directly from your inventory or through a visual GUI. Supports shulker box scanning, partial name matching, and configurable per-material pricing with anti-farm reductions.

* **`/sell gui`** — Opens a virtual chest interface where you place items to sell. Sellable items are processed and paid for; unsellable items are returned to your inventory (or dropped on the ground if inventory is full).
* **`/sell all`** — Instantly sells every sellable item in your inventory.
* **`/sell all <item>`** — Sells all instances of a specific item (e.g., `/sell all ender_pearl`). Supports both underscores and spaces, and partial name matching.

#### Shulker Box Support

All sell commands fully support shulker boxes:

* Items inside shulker boxes are scanned and sold just like regular inventory items.
* When using `/sell gui`, placing a shulker box in the sell window will sell all sellable contents inside it. Unsellable items stay inside the shulker box, and the shulker box is returned to your inventory.
* When using `/sell all` or `/sell all <item>`, matching items inside shulker boxes are sold as well. The shulker box is updated in place with only the remaining unsellable items.
* If all items inside a shulker box are sold and the shulker box itself is sellable (listed in the shop), it will also be sold automatically.

### Economy Protection

Solidus applies sell-price reductions to farmed resources, configured directly in `shop.json` by the server operator. This gives you full control over economic balance — adjust prices to counter inflation from automated farms without needing to restart the server. When a player sells a farmable item (like iron from an iron farm), the sell price is automatically reduced by the configured percentage, keeping the economy balanced even on servers with large-scale redstone farms.

### Inter-Mod API

Solidus provides a public API (`SolidusAPI`) for other Fabric mods to integrate with the economy system. The API uses **Java MethodHandle reflection** — meaning zero compile-time dependency. Third-party mods can call Solidus methods without importing Solidus classes at compile time. If Solidus Core is not installed on the server, the reflection calls simply return empty results rather than crashing.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full API reference, method signatures, and integration examples.

---

## Quick Start

### Installation

> **Requirements:** Minecraft 26.1.x · Java 25 · Fabric Loader 0.19.2+ · Fabric API 0.149.0+

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) on the server
3. Download the latest Solidus release from [Releases](https://github.com/mohd-gs/solidus-core/releases)
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
/baltop                                  ← See the wealth leaderboard
```

### Adding Ecosystem Modules

Once Solidus Core is running, you can add any combination of ecosystem modules:

| Module | What It Adds | Installation |
|--------|-------------|-------------|
| [solidus-analytics](https://github.com/mohd-gs/solidus-analytics) | Live economy dashboard, inflation tracking, fraud detection | Drop JAR in `mods/` |
| [Solidus-Enforcer](https://github.com/mohd-gs/Solidus-Enforcer) | Bounty hunting, hunter licenses, anti-monopoly system | Drop JAR in `mods/` |
| [Solidus-Governance](https://github.com/mohd-gs/Solidus-Governance) | Taxation, audit logging, rollback recovery | Drop JAR in `mods/` |
| [solidus-territory](https://github.com/mohd-gs/solidus-territory) | Polygon land claiming, rent, territory trading | Drop JAR in `mods/` |

All modules auto-detect Solidus Core via reflection and activate automatically. No additional configuration needed for basic integration.

---

## Commands

| Command | Description |
| --- | --- |
| `/balance` | Show balance |
| `/pay <player> <amount>` | Transfer to online player |
| `/pay offline <player> <amount>` | Transfer to offline player |
| `/baltop` | Wealth leaderboard |
| `/shop` | Open shop |
| `/shop search <query>` | Search shop items |
| `/sell gui` | Open sell GUI (place items to sell) |
| `/sell all` | Sell all sellable items in inventory |
| `/sell all <item>` | Sell all of a specific item (e.g. `ender_pearl`) |
| `/ah` | Open auction |
| `/ah sell <price>` | Create listing |
| `/ah collect` | Reclaim expired items |
| `/ah cancel <uuid>` | Cancel own listing |
| `/ah sort <criteria>` | Sort listings (price/newest/material) |
| `/transactions [page]` | Transaction history |

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
* Sell-price reductions for farmable items (anti-farm inflation protection)
* Text formatting and currency symbol customization
* Hot reload without restart — edit `shop.json` and reload in-game

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
│   └── SolidusAPI.java          — Public API (reflection-safe, thread-safe)
├── economy/
│   ├── EconomyManager.java      — Balance operations, transfers, leaderboard
│   └── TransactionLogger.java   — Full transaction history with pagination
├── shop/
│   ├── ShopManager.java         — 11 categories, 120+ items, hot-reload
│   └── ShopGUI.java             — Server-side GUI via vanilla container packets
├── auction/
│   ├── AuctionHouse.java        — Listing, expiration, reclaim, sorting
│   └── AuctionGUI.java          — Browse/search/buy interface
├── sell/
│   ├── SellManager.java         — Per-material pricing, shulker box scanning
│   └── SellGUI.java             — Visual sell interface
├── storage/
│   └── EconomyDatabase.java     — SQLite + WAL + single-thread executor
├── config/
│   └── ConfigManager.java       — Hot-reload JSON configuration
└── integration/
    └── ModuleBridge.java         — Reflection bridge for ecosystem modules
```

### Key Design Decisions

1. **Async SQLite with WAL journaling** — All database operations run on a dedicated single-thread `ExecutorService` for serial consistency. Returns use `CompletableFuture` so the server thread never blocks. WAL mode ensures crash resilience — no committed transaction is lost even on hard shutdown.

2. **Reflection-based API** — `SolidusAPI` exposes economy operations through `MethodHandle` reflection. Third-party mods call these methods without any compile-time dependency on Solidus. If Solidus is absent, calls return empty/default values rather than throwing `NoClassDefFoundError`.

3. **Server-side GUI via vanilla packets** — Shop and auction interfaces use vanilla container/window packets. No custom client mod, no resource pack, no custom network channel. Works on any client — vanilla, Fabric, Forge (via protocol translation).

4. **Hot-reload configuration** — `shop.json` is watched for changes. Operators can adjust prices, add categories, or modify items and reload without restarting the server. This enables live economy tuning in response to market conditions.

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

Solidus applies configurable sell-price reductions to farmable resources. Server operators set reduction percentages per material in `shop.json` — for example, reducing the sell price of iron ingots by 40% to counter iron farm output. This gives fine-grained control over economic balance without requiring a server restart.

### What happens to economy data if the server crashes?

All transactions are persisted through SQLite with WAL (Write-Ahead Logging) journaling. WAL mode guarantees that no committed transaction is lost — even during a hard crash or power failure. Data integrity is maintained at the database level, not the application level.

### Is Solidus Core free?

**Yes.** Solidus Core is licensed under the MIT License — fully open-source, no premium tier, no feature gating. Some ecosystem modules (Analytics, Governance) offer premium features with a license key, but Core itself is completely free.

---

## Download

| Platform | Link |
| --- | --- |
| GitHub Releases | [Latest Release](https://github.com/mohd-gs/solidus-core/releases) |
| Modrinth | [MOHD_Gs on Modrinth](https://modrinth.com/user/MOHD_Gs) |

---

## Contributing

Contributions are welcome.

* Report issues via [GitHub Issues](https://github.com/mohd-gs/solidus-core/issues)
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

Built by [MOHD_Gs](https://github.com/mohd-gs) · [Email](mailto:mohdmxmxm@gmail.com) · Discord: **mohd_gs** · Part of the [Solidus Economy Ecosystem](https://github.com/mohd-gs)
