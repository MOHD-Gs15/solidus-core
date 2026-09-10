<h1 align="center">Solidus</h1>

<p align="center"><strong>The complete server-side economy &amp; commerce engine for Minecraft servers</strong><br>
Balances, transfers, a server shop, an auction house, and secure player-to-player trading — in one mod.</p>

<p align="center">
  <a href="https://github.com/MOHD-Gs15/solidus-core/actions/workflows/test.yml"><img src="https://github.com/MOHD-Gs15/solidus-core/actions/workflows/test.yml/badge.svg" alt="Tests"></a>
  <a href="https://github.com/MOHD-Gs15/solidus-core/actions/workflows/codeql.yml"><img src="https://github.com/MOHD-Gs15/solidus-core/actions/workflows/codeql.yml/badge.svg" alt="CodeQL"></a>
  <img src="https://img.shields.io/badge/version-2.2.5-blue" alt="Version 2.2.5">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-brightgreen" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT License"></a>
  <a href="https://github.com/MOHD-Gs15"><img src="https://img.shields.io/badge/mod%20by-MOHD--Gs-6f42c1" alt="Mod by MOHD-Gs"></a>
</p>

**Solidus** is a server-side economy mod for Minecraft (Fabric, MC 26.1.2). Players never install anything — they join with the vanilla client and immediately get a persistent wallet in the server's own currency, the **Solidus (S$)**, plus a graphical server shop, an in-game auction house with bidding, fast inventory selling, and a double-confirmation trade screen. Everything is stored in a real database (SQLite out of the box, MySQL/MariaDB for server networks) and every single credit and debit is written to an auditable transaction ledger. Free and open source under the MIT license.

## Why server owners pick Solidus

- **Zero client install** — pure server-side; players join with the unmodified game.
- **Works in one minute** — drop it in `mods`, start the server, done. SQLite storage needs no setup at all.
- **Network-ready** — point it at MySQL/MariaDB and every server in your network shares one wallet balance, with an optional Redis layer for caching and instant cross-server notifications.
- **Bank-grade honesty** — a periodic auditor re-checks that the sum of all balances matches the ledger, so money can never silently appear or disappear.
- **Player-friendly UIs** — the shop, auction house, and selling all work through click-through chest GUIs, not command memorization.

## Features

- **Persistent wallets** — every player has a balance that survives restarts, crashes, and offline time.
- **Instant payments** — one `/pay` command, including transfers to players who are currently offline.
- **Server shop** — a browsable in-game shop GUI with instant search (`/shop search <word>`).
- **Quick selling** — sell everything sellable in your inventory with one command, or drag-and-drop through a GUI.
- **Auction house** — list items with a buy-now price and a starting bid; bids are held in a mandatory escrow account until the auction settles; sortable listings.
- **Player-to-player trading** — a dual-side trade window where both parties confirm, so nothing changes hands without consent.
- **Leaderboard** — `/baltop` shows the richest players, paginated.
- **Full transaction ledger** — every player can review and export their own history; admins get export-everything tooling.
- **Real storage engine** — SQLite by default; MySQL/MariaDB for multi-server networks with a one-command data migration.
- **Money-supply integrity checks** — periodic verification that total balances equal the ledger replay, plus data-tamper protection and optional TLS for database connections.
- **Powerful admin toolkit** — grant/set/take balances, create accounts, act as a player, migrate storage, and audit the whole economy from one command tree.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) on your server (Minecraft 26.1.2).
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) into your `mods` folder.
3. Drop `solidus-2.2.5.jar` into `mods`.
4. Start the server — the economy is live immediately on local SQLite storage.

> Server-side only: players connect with the vanilla client and need zero downloads.

## Commands

### Player commands

| Command | What it does |
|---------|--------------|
| `/balance` · `/bal` | Show your balance |
| `/pay <player> <amount>` | Send money to an online player |
| `/pay offline <name> <amount>` | Send money to an offline player |
| `/baltop [page]` | Richest-players leaderboard |
| `/transactions [days]` | Your transaction history (default: last 7 days) |
| `/transactions export` | Export your history to a file |
| `/shop` | Open the server shop |
| `/shop search <word>` | Search the shop by item name |
| `/sell gui` | Open the drag-and-drop selling interface |
| `/sell all [item]` | Sell every sellable item (or one specific item type) |
| `/ah` | Browse the auction house |
| `/ah sell <price> <start-bid>` | List an item (buy-now price + starting bid) |
| `/ah bid <id> <amount>` | Bid on a listing |
| `/ah collect` | Collect your purchases and auction earnings |
| `/ah cancel <id>` | Cancel a listing you own |
| `/ah sort newest\|price_low\|price_high\|material` | Sort the listings |
| `/trade <player>` | Invite a player to trade |
| `/trade accept\|deny\|cancel` | Accept / decline / cancel a trade |

### Admin commands (operator permission)

| Command | What it does |
|---------|--------------|
| `/solidus-admin money give\|set\|take <player> <amount>` | Grant, set, or withdraw a balance |
| `/solidus-admin account create\|balance\|list` | Create and inspect accounts |
| `/solidus-admin pay-as <from> <to> <amount>` | Transfer on behalf of a player |
| `/solidus-admin bid-as <player> <id> <amount>` | Bid on behalf of a player |
| `/solidus-admin auction create <seller> <item> <count> <price> <start-bid>` | Create an admin auction |
| `/solidus-admin storage migrate` | Migrate all data from SQLite to MySQL with zero loss |
| `/solidus-admin integrity check\|rebase` | Verify money-supply integrity / rebase the ledger baseline |
| `/solidus-admin audit` | Quick economy audit report |
| `/solidus-admin diag` | System diagnostics |

Permissions use the `solidus.command.*` keys for players and `solidus.command.admin` for administration — the full catalog is documented in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick configuration

After the first start you'll find `config/solidus/storage.json`. The keys that matter most:

| Key | Default | Purpose |
|-----|---------|---------|
| `type` | `sqlite` | `sqlite` for a single server; `mysql` (or `mariadb`) for a network with one shared balance |
| `mysql.host / port / database / user` | — | Connection details for the network database |
| `mysql.useSsl` | `true` | TLS for the database connection (keep it on unless the DB is on the same host) |
| `redis.enabled` | `false` | Optional accelerator: balance cache + instant cross-server notifications |
| `integrity.enabled` | `true` | Automatic periodic audit that all balances still match the ledger |

- Keep secrets out of the config file: use the `SOLIDUS_DB_PASSWORD` environment variable for the database and `SOLIDUS_REDIS_PASSWORD` for Redis.
- Moving to MySQL? Run `/solidus-admin storage migrate` once and everything transfers automatically.

## For advanced users

**Architecture.** The engine (`EconomyEngine`) separates the money layer from the storage layer (`StorageBackend`), and every financial operation is atomic and safe under multi-server concurrency: the transaction ledger uses row-level optimistic locking, and every auction bid passes through a mandatory escrow account. The optional Redis layer is never the source of truth — it is an L2 cache with Pub/Sub invalidation, and losing it never takes the economy down.

**Money-supply integrity.** A periodic auditor compares the sum of all balances against a full ledger replay (all income sources, sinks, and admin operations) and verifies that the escrow total equals the sum of open winning bids. On a network, elect exactly one server with `integrity.elected: true`.

**Versioning.** The ecosystem follows a documented family contract in [VERSIONING.md](VERSIONING.md): any `2.2.x` Solidus release works with any `2.1.x` release of the companion mods (Analytics / Governance / Enforcer).

**Currency.** The internal unit is the Solidus, symbol `S$`, formatted `1,250.5 S$`; rounding rules are specified in [docs/MONEY_ROUNDING.md](docs/MONEY_ROUNDING.md).

**Testing.** The suite spans 38 test classes (435 tests per run). CI executes it twice per push — plain and under coverage (870 green executions) — against real `mariadb:11` and `redis:7` service containers, so the MySQL and Redis paths are exercised on every commit. Build locally with JDK 25: `./gradlew build` (artifact lands in `build/libs/`).

## Documentation

| Document | Contents |
|----------|----------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture, permissions catalog, and storage layers in detail |
| [docs/DB_SCALING_PLAN.md](docs/DB_SCALING_PLAN.md) | Scaling the database: SQLite → MySQL → Redis |
| [docs/FEATURES_TRADE_BIDDING.md](docs/FEATURES_TRADE_BIDDING.md) | Step-by-step mechanics of trading and escrow bidding |
| [docs/MONEY_ROUNDING.md](docs/MONEY_ROUNDING.md) | Rounding rules and currency representation |
| [docs/CONSOLE_TESTING.md](docs/CONSOLE_TESTING.md) | Testing the economy from the server console |
| [docs/sql/mysql/001_init.sql](docs/sql/mysql/001_init.sql) | Ready-to-run MySQL schema script |
| [VERSIONING.md](VERSIONING.md) | Version policy and family compatibility |
| [notes/CHANGES.md](notes/CHANGES.md) | Changelog |

## The Solidus family

| Mod | What it adds | Repository |
|-----|--------------|------------|
| **Solidus Core** (this repo) | The economy engine itself | [MOHD-Gs15/solidus-core](https://github.com/MOHD-Gs15/solidus-core) |
| **Solidus Analytics** | Economy monitoring, web dashboard, fraud detection | [MOHD-Gs15/solidus-analytics](https://github.com/MOHD-Gs15/solidus-analytics) |
| **Solidus Governance** | Taxes, limits, policies, audits, backups, recovery | [MOHD-Gs15/Solidus-Governance](https://github.com/MOHD-Gs15/Solidus-Governance) |
| **Solidus Enforcer** | Bounties, hunter licenses, anti-exploit enforcement | [MOHD-Gs15/Solidus-Enforcer](https://github.com/MOHD-Gs15/Solidus-Enforcer) |

## License & credits

- **Mod by [MOHD-Gs](https://github.com/MOHD-Gs15)** — profile on [Modrinth](https://modrinth.com/user/MOHD_Gs).
- Licensed under the [MIT License](LICENSE) — free to use, modify, and ship with your server.
