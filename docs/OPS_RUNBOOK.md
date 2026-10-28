# Solidus Operations Runbook

> Field manual for server owners and admins: backup & restore, storage
> migration rollback, table housekeeping, secrets & TLS, and the
> offline-mode test-account trap. Every procedure here uses only shipped
> Solidus surface (`/solidus-admin`, `storage.json`, documented env vars).
> Commands that touch raw SQL are templates — review them against your
> schema version before running.

---

## 1. Backup

### 1.1 SQLite (single server)

Solidus's SQLite backend runs in WAL mode. A copy taken while the server is
running can miss WAL content or catch a mid-checkpoint state — so:

**The safe procedure is: clean stop → copy → start.**

1. Stop the server cleanly (normal shutdown, not `kill -9`). Solidus closes
   its connections on shutdown, which checkpoints the WAL.
2. Copy **all** Solidus database files, not just the main file — the main
   DB plus any `-wal` / `-shm` siblings that remain, from the server
   directory (paths per your `storage.json`).
3. Copy `config/solidus/storage.json` with it (it is the map of what you
   just backed up).
4. Restart. Schedule: daily copy retained ≥ 14 days is a reasonable floor;
   keep at least one copy outside the host machine.

Emergency (cannot stop): use SQLite's own backup path
(`VACUUM INTO 'backup.db'` from a sqlite3 shell, or the `.backup` command) —
never `cp` a live WAL database. Verify the emergency copy with §2.3 before
trusting it.

### 1.2 MySQL / MariaDB (network)

The network truth lives in the shared database. With MariaDB (Solidus
targets MariaDB 10.6+ / MySQL 8.0+):

```bash
# Full logical backup
mariadb-dump --single-transaction --routines --triggers \
  -u <admin> -p solidus_economy > solidus-$(date +%F).sql

# Plus, if you want point-in-time recovery, ensure binary logging is on
# (log_bin) and archive the binlogs alongside the dump:
mysqlbinlog --start-datetime="$(date -d '1 day ago' +%F/%T)" \
  <binlog files> >> solidus-$(date +%F)-binlog.sql
```

- `--single-transaction` gives a consistent snapshot without locking
  players out (InnoDB).
- Binlog archiving is what turns "nightly backup" into "lose at most N
  minutes" — enable and rotate it deliberately.
- The `auction_*`, `operations`, `pending_notifications` and
  `solidus_supply_checkpoint` tables live in the **same** database — the
  dump above captures them; do not hand-pick tables.

### 1.3 What is NOT in either backup

- Redis content: by design disposable (L2 cache + pub/sub; the database is
  the source of truth). No backup needed — ever.
- `config/solidus/` (shop.json, storage.json, permissions.json): version
  them like code; the economy restore is incomplete without them.

## 2. Restore & verification

1. Provision the target (files for SQLite / empty database + schema for
   MySQL — Solidus creates its schema at startup, or replay the dump which
   contains it).
2. Load the backup (SQLite: stop server → replace files → start; MySQL:
   `mariadb < solidus-YYYY-MM-DD.sql`).
3. Start the server and **verify before opening to players**:

```
/solidus-admin integrity check   # supply vs ledger replay + escrow consistency
/solidus-admin audit             # negative-balance tail, escrow sanity, snapshot
/solidus-admin diag              # backend, Redis, auction mode, economy snapshot
```

4. Spot-check one known balance and one recent `/transactions` entry against
   pre-incident data.
5. Only then reopen. A restore that skips step 3 is a rumor of a restore.

**Restoring to a DIFFERENT database host:** update `storage.json`
(`mysql.host`) or point the env-derived settings; remember `useSsl` rules
(§5).

## 3. Rollback after a failed `/solidus-admin storage migrate`

The migrator (2.2.1+) is a **copy + verify** tool, not a move: it copies
SQLite content into the MySQL target with a cent-exact verification pass and
writes a report file. The SQLite source is never deleted or modified by the
migration. That design is what makes rollback boring:

1. **Do not retry migrate immediately** — read the report file it wrote and
   the server log. The known historical failure modes (all fixed in
   2.2.3, but they define what to look for): partial target schema,
   verify-phase connection closure, old SQLite backups missing auction
   tables (tolerated as report notes, not aborts).
2. **Return to SQLite:**
   - Set `storage.json` `type` back to `sqlite` (the source files are
     untouched — that is the rollback).
   - Restart the server. The economy resumes on SQLite with pre-migration
     data.
3. **Freeze writes during investigation** on a network: the danger window is
   players spending on backend A while you compare against backend B. Keep
   the server on exactly one backend at a time.
4. **If some writes DID land on MySQL** before the failure: treat MySQL as
   the divergent copy and discard it, or diff via
   `/solidus-admin integrity check` on each backend separately before
   choosing. Never "merge by hand".
5. Re-attempt migrate only after (a) the report's findings are addressed,
   (b) a fresh backup exists (§1), and (c) a maintenance window is open —
   migrations take a write lock window proportional to data size.

## 4. Table housekeeping (deferred-but-needed maintenance)

The engineering review of the hybrid-DB proposal
(`notes/PLAN_REVIEW_HYBRID.md` §6, item 3) deferred automated housekeeping.
Until it ships as code, this is the manual, conservative procedure:

| Table | Safe to prune? | Rule | Why |
|-------|----------------|------|-----|
| `operations` | ✅ yes | rows older than **30 days**, in maintenance window | Idempotency claims; their replay horizon is minutes, not weeks. Never prune while a migration or known retry storm is in flight. |
| `pending_notifications` | ✅ yes | rows older than **30 days** | Undelivered notices for players who have not returned in a month; the 60 s sweep only delivers to currently-online players. |
| `transaction_log` | ❌ **no automated pruning** | append-only audit plane | It is the input of the supply-integrity replay and the players' `/transactions` history. Archive/export instead (`/transactions exportall`); ledger partitioning is future work per the plan review. If you absolutely must shrink it, export first, then take an owner-approved, backup-verified decision — and rebase (`/solidus-admin integrity rebase`) only after reading what rebase means in `notes/CHANGES.md` 2.2.4. |
| `auction_sold_history` / `auction_*` | ❌ no | settlement + recovery invariants | Startup orphan sweeps and exactly-once claims assume their presence. |

Templates (review against your schema, run in a window, with a backup):

```sql
-- MySQL/MariaDB
DELETE FROM operations             WHERE created_at < NOW() - INTERVAL 30 DAY;
DELETE FROM pending_notifications  WHERE created_at < NOW() - INTERVAL 30 DAY;
```

(Adapt the timestamp column names to your actual schema before executing —
`001_init.sql` is the reference.) On SQLite the same idea applies with
`DELETE ... WHERE <timestamp> < datetime('now', '-30 days')`, followed by a
clean restart so the WAL checkpoints.

After any housekeeping run: `/solidus-admin integrity check` must stay
clean — housekeeping touches only `operations`/`pending_notifications`, so
a dirty check afterwards means the template was wrong. Stop and restore.

## 5. Secrets & TLS

### 5.1 Environment-variable password behavior

| Variable | Feeds | Behavior |
|----------|-------|----------|
| `SOLIDUS_DB_PASSWORD` | MySQL/MariaDB user password | Overrides/supplies the DB credential without touching `storage.json`; keep the config file secret-free. |
| `SOLIDUS_REDIS_PASSWORD` | Redis auth | Same principle for the Redis layer. |

- Secrets **never** belong in `storage.json` — that file is readable,
  backup-able, and version-controlled in some setups.
- When a Redis URI is required to carry credentials inline, the layer
  redacts them in every log/error path (SOL-001) — but do not rely on
  redaction as a security feature; prefer the env var.
- Systemd units: `EnvironmentFile=/etc/solidus/secrets.env` with
  `chmod 600` and root ownership; Docker: `env_file` or orchestrator
  secrets.

### 5.2 TLS to the database (`useSsl`)

- **Since SOL-002 (2.2.5), `useSsl` defaults to `true`.** Keep it true
  unless the database is on the same host (loopback).
- Choosing `useSsl=false` on a **non-loopback** host triggers a loud
  cleartext warning at startup — if you see it in production logs, the
  economy credentials are traversing the network unencrypted. Fix the
  config or the topology; do not silence the warning.
- Loopback means `localhost` / `127.x` / `::1` only — a container bridge or
  a private VLAN is **not** loopback and will (correctly) warn.
- Redis: only `redis://` (or `rediss://` for TLS) URIs are accepted,
  fail-closed before any network I/O (SOL-003).

## 6. ⚠ Offline-mode: the funded-test-account trap

Solidus's console harness (`docs/CONSOLE_TESTING.md`) creates dummy accounts
under the **vanilla offline-mode UUID derivation**:

```
UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))
```

Consequence: **on an offline-mode server, a real player joining with the
same name resolves to the SAME UUID and inherits the dummy account** —
including any funding you gave it during testing. A `TestAdmin` account that
carried 1,000,000 S$ through rehearsal becomes a real player's bank balance
on day one.

**Pre-launch checklist (mandatory for offline-mode deployments):**

1. List the dummy accounts: `/solidus-admin account list`.
2. Zero every one of them: `/solidus-admin money set <name> 0`
   (the console doc recommends `money set … 0` **before** deleting
   anything — deletion alone does not zero the ledger's story).
3. Re-run `/solidus-admin audit` and confirm the supply snapshot matches
   expectation.
4. Online-mode (premium) servers are structurally immune — premium UUIDs
   cannot collide with the derivation — but the checklist costs nothing.

## 7. Quick health triage

| Symptom | First checks |
|---------|--------------|
| `NoClassDefFoundError` mentioning Redis/Jedis at runtime | Jar packaging — did the jar come from a real `build`? `RedisPackagingSmokeTest` guards this in CI since 2.3.1/2.3.3. Replace the jar with a CI-built artifact. |
| GUIs refuse to open with a banner | `Compat` probe degradation (W-3 by design) — `/solidus-admin compat`, compare against `compat/PACKAGE.md`; economy is still safe. |
| Cleartext DB warning at startup | §5.2 — non-loopback host with `useSsl=false`. |
| `integrity check` reports drift | Do NOT rebase immediately. Read the breakdown (known unmetered sources are listed in the report itself), compare with `notes/CHANGES.md` 2.2.4 semantics, then decide. |
| Redis down | Economy keeps working (L2 is optional by design); expect the fail-closed startup choice documented in `docs/DB_SCALING_PLAN.md` §12 and slower reads until it returns. |
