# Console Test Harness — driving the economy without players (2.2.2)

Everything in this guide runs from the **server console** or **Rcon** — no
real player ever needs to join. This is the scripting surface for the
multi-server test scenarios that were previously only executable as JUnit
race tests.

Permission: `solidus.command.admin` (default OP 4). The console always passes.

---

## 1. Command reference

### Accounts

| Command | Effect |
| --- | --- |
| `/solidus-admin account create <name> [balance]` | Materializes an account for a player who has never joined. Without `[balance]` the account starts at the configured starting balance; with it, the exact value is applied and logged as `ADMIN_SET`. |
| `/solidus-admin account balance <name>` | Prints the account's balance + UUID. |
| `/solidus-admin account list [page]` | Leaderboard-style listing, 10 per page. |

### Money

| Command | Effect |
| --- | --- |
| `/solidus-admin money give <name> <amount>` | Credits the account (ledger: `ADMIN_GIVE`). |
| `/solidus-admin money set <name> <amount>` | Overwrites the balance (ledger: `ADMIN_SET`). |
| `/solidus-admin money take <name> <amount>` | Debits; rejected when funds are insufficient (ledger: `ADMIN_TAKE`). |

### Transfers and auctions on dummy accounts

| Command | Effect |
| --- | --- |
| `/solidus-admin pay-as <from> <to> <amount>` | REAL atomic transfer through the same `transferOffline` path as `/pay`: governance hooks, bounds checks, `PAY_SEND`/`PAY_RECEIVE` ledger rows. Both accounts must exist. |
| `/solidus-admin bid-as <bidder> <listing_id> <amount>` | Places a bid on behalf of a dummy account through the REAL escrow pipeline (validation → atomic escrow charge → exactly-once claim → outbid refund → anti-snipe). |
| `/solidus-admin auction create <seller> <item> <count> <price> [startbid]` | Lists a conjured item for a dummy seller through the REAL listing path (listing fee, governance veto, `AUCTION_LIST` ledger). `item` is a registry id, e.g. `minecraft:diamond`. |

### Verification

| Command | Effect |
| --- | --- |
| `/solidus-admin audit` | Invariants: negative-balance scan (backward tail pages), escrow sanity, money-supply snapshot with Gini. `!!` lines are violations. |
| `/solidus-admin diag` | Answers "which mode am I actually in?": active backend (SQLite vs MySQL), auction store mode, Redis layer state, economy snapshot, name-cache size. |

---

## 2. Dummy account identity (read this once)

Accounts are created under the **vanilla offline-mode UUID derivation**:

```
UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))
```

Consequences:

- **Offline-mode servers**: a real player joining with the same name resolves
  to the SAME UUID and inherits the dummy account. This is useful for
  scripted setups — and a reason not to leave funded test accounts on a
  production server (`money set <name> 0` before deleting anything).
- **Online-mode servers**: premium UUIDs can never collide with the
  derivation, so dummies stay isolated.

Every admin adjustment (`give`/`set`/`take`, initial balances) writes an
`ADMIN_*` ledger row with the issuing console in the description, so the
transaction log (`/transactions`, `exportall`) stays a complete audit trail.

---

## 3. Scenario playbook

### 3.1 Single-server smoke (SQLite, one console)

```
solidus-admin account create Alice 1000
solidus-admin account create Bob 500
solidus-admin pay-as Alice Bob 99.99
solidus-admin account balance Alice        # 900.01
solidus-admin account balance Bob          # 599.99
solidus-admin audit                        # supply 1500.00, no negatives
```

Supply conservation check: `1000 + 500` before == `900.01 + 599.99` after.

### 3.2 Full auction lifecycle (no players)

```
solidus-admin account create Seller 10000
solidus-admin auction create Seller minecraft:diamond 5 500 100
solidus-admin account create BidderA 10000
solidus-admin account create BidderB 10000
solidus-admin bid-as BidderA <listing_id> 120
solidus-admin bid-as BidderB <listing_id> 150      # A is auto-refunded from escrow
solidus-admin account balance BidderA              # 10000 again (escrow refund)
solidus-admin account balance Seller               # 10000 - listing fee
solidus-admin audit                                # escrow holds 150 (open bid)
```

When the auction expires, settlement releases escrow → seller and stores the
item in `auction_won_items` for BidderB (`/ah collect` if they ever join, or
inspect via `diag`/DB).

### 3.3 Multi-server race (the actual network test)

Setup: two (or more) servers, `storage.json` → `"type": "mysql"` pointing at
the SAME MariaDB/MySQL database, Redis optional (2.2.1 instant
notifications). On each console create distinct dummies, then cross-fire:

```
# Server A console
solidus-admin account create A_Sender 1000000
solidus-admin pay-as A_Sender B_Receiver 1234.56     # B_Receiver lives on server B

# Server B console (simultaneously)
solidus-admin pay-as B_Sender A_Receiver 4321.09
```

Repeat in a tight Rcon loop on both servers for load; then:

```
solidus-admin audit        # on either server — supply must be EXACTLY conserved
solidus-admin diag         # must say: MySQL/MariaDB (multi-server)
```

The money math is validated to the cent because every leg is one atomic
transaction with row locks and deterministic lock ordering (lower UUID
first) — the same guarantee the JUnit `MySqlTransferRaceTest` exercises with
200 concurrent transfers from two storage instances.

### 3.4 Outbid race across servers

```
# A: create listing with opening bid
solidus-admin auction create Seller minecraft:elytra 1 10000 500
# B (same listing id, same shared market in MySQL mode):
solidus-admin bid-as BidderA <listing_id> 600 &
solidus-admin bid-as BidderB <listing_id> 600
```

Exactly one of the two 600-bids wins the claim; the loser is refunded from
escrow (see the `OUTBID_RACE` feedback). `audit` afterwards must show escrow
== the winning bid and supply unchanged.

### 3.5 Degraded mode / outage drill

```
solidus-admin diag                       # baseline: MySQL, Redis enabled
systemctl stop mariadb                   # (host shell)
/pay …                                   # in-game or console error — server stays alive
systemctl start mariadb
solidus-admin audit                      # consistency after recovery
```

---

## 4. CI: the same scenarios without a single Minecraft process

The JUnit suite contains the network scenarios as gated tests. With the
provided GitHub Actions workflow (`.github/workflows/test.yml`), every push
boots real `mariadb:11` + `redis:7` service containers and runs:

- `MySqlStorageContractTest` — the backend contract on real MariaDB
- `MySqlTransferRaceTest` — TWO storage instances, 200 concurrent transfers,
  supply conservation to the cent (the scripted twin of scenario 3.3)
- `MySqlOperationsIdempotencyTest` — replayed operation ids never move money twice
- `MySqlAuctionDialectTest` — shared auction market SQL on MariaDB
- `StorageMigratorTest` — SQLite→MySQL migration against a throwaway database
- `RedisLayerTest` — L2 cache, invalidation bus, circuit breaker on real Redis

Locally the same activation works with any MariaDB/Redis reachable from the
test JVM: set `SOLIDUS_TEST_MYSQL_HOST` / `..._DATABASE` / `..._USER` /
`..._PASSWORD` and `SOLIDUS_TEST_REDIS_URI`, then `./gradlew test`.

---

## 5. Ledger types introduced by the harness

| Type | Meaning |
| --- | --- |
| `ADMIN_GIVE` | Console credit (`money give`) |
| `ADMIN_SET` | Console overwrite (`money set`, account initial balance) |
| `ADMIN_TAKE` | Console debit (`money take`) |
| `PAY_SEND` / `PAY_RECEIVE` | `pay-as` legs — identical to player `/pay` |

`/transactions` renders the admin types as `AD+` / `AD=` / `AD-` (green /
yellow / red).
