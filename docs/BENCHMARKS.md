# Solidus Benchmarks — Methodology & Results

> **Status: methodology ready, results pending.** Every results table below
> is intentionally **empty** until workstream WS-4 runs the sessions
> described here. No numbers in this file are invented, projected, or
> copied from `docs/ARCHITECTURE.md` §21 (which is explicitly marked as
> unmeasured design estimates). When a table cell is filled, it must be
> backed by a run manifest (§5) — otherwise it stays `—`.

---

## 1. Scope

Four metric families are defined for the Solidus economy plane:

| # | Metric family | Question it answers |
|---|---------------|---------------------|
| M1 | `transferAtomicWithLedger` latency (p50/p95/p99) | How long does one atomic money move + ledger write actually take, per backend? |
| M2 | Server tick cost (MSPT / TPS) under economy load | How much of the 50 ms tick budget does Solidus consume while players trade? |
| M3 | Economy executor queue age | Does the single-threaded mutation executor fall behind, and by how much, under burst? |
| M4 | Money-supply persistence (cent-exactness) | Does a long, hostile workload end with `SUM(balances)` exactly equal to the ledger replay, to the cent? |

Non-goals: Minecraft client FPS, GUI rendering, companion-mod overhead
(Analytics/Governance/Enforcer are measured in their own repos), and
network-transport microbenchmarks unrelated to the economy path.

## 2. Benchmark environment (pin before every session)

Every session records this manifest; a result without a manifest is void.

| Field | Recorded value |
|-------|----------------|
| CPU model / cores / governor | — |
| RAM / speed | — |
| Disk (type, fs, journaling) | — |
| OS + kernel | — |
| Java (distribution + version) | temurin 25 |
| Minecraft / Fabric Loader / Fabric API | 26.1.2 / 0.19.4 / 0.155.2+26.1.2 |
| Solidus commit + `mod_version` | — |
| MariaDB version (storage `mysql`) | 11.x |
| Redis version (when M3 uses Redis L2) | 7.x |
| Storage modes under test | `sqlite` · `mysql` (loopback) |
| JVM flags | — |
| Date / operator | — |

Environment rules:

- Databases run on **loopback** unless the session explicitly targets a
  LAN topology (and then the topology is part of the manifest).
- No other world/tick load runs on the server during M1/M3 sessions;
  M2 measures the *combined* cost by design and says so.
- CPU governor pinned to `performance`; disk sync settings recorded
  (SQLite WAL fsync behavior and MariaDB `innodb_flush_log_at_trx_commit`
  dominate latency — both are documented per session).

## 3. Methodology

### 3.1 WS-4.1 — storage-level harness (micro, deterministic)

The harness drives the real `StorageBackend` implementations (SQLite and
`MySqlStorage` over loopback MariaDB) through the same calls the live server
makes, with **no Minecraft bootstrap** — the same principle as the test
suite (`StorageBackendContractTest`). Per metric:

1. **Warmup:** 2,000 operations discarded (JIT + pool + page cache).
2. **Sample size:** ≥ 10,000 timed operations for M1; ≥ 60 s wall-clock for
   M3 bursts; ≥ 1,000,000 mutation sequence for M4.
3. **Reporting:** p50 / p95 / p99 / max + mean, in milliseconds. Percentiles
   are computed over per-operation wall time measured *around the whole
   future chain* (submission → completion), not around the DB call alone.
4. **Concurrency shapes for M1:** 1 caller (serial baseline), 8, 64 —
   each shape reported separately; never merged.
5. **Seeded, replayable data:** the same deterministic seed produces the
   same account/ledger rows; the harness prints the seed into the manifest.

### 3.2 WS-4.2 — live-server session (macro, realistic)

A provisioned server (real Minecraft tick loop, real packet path) with
scripted bot clients — the console-harness scenarios of
`docs/CONSOLE_TESTING.md` scaled up:

- M2: `/spark`-class profiler **or** the server's own tick-time sampling;
  report MSPT p50/p95/p99 and derived TPS over a ≥ 30 min window that
  includes the workload bursts below.
- Workload bursts (each measured on its own, then combined):
  1. steady `/pay` chatter (10 bots, 1 pay / 2 s each),
  2. auction storm (list/bid/collect cycles, 5 bots),
  3. baltop + `/transactions` reads (the expensive read shapes),
  4. sell-GUI spam.
- M3: the economy executor's queue age is sampled every second
  (age of the oldest pending task, ms). The alerting threshold proposed to
  the owner is **p95 ≤ 100 ms** — a proposal, not a promise.

### 3.3 Honesty rules

- No number may be quoted from §21 estimates; if a session reproduces a
  §21 order of magnitude, that is a coincidence to note, not validation.
- Failed/degraded runs are reported (fail-closed startup, circuit-breaker
  trips) rather than discarded.
- Every table row below links its manifest (commit hash + date).

## 4. Metric definitions (exact)

### M1 — `transferAtomicWithLedger` latency

One sample = one `transferAtomicWithLedger(opId, …)` from submission to
future completion: balance claim + escrow-safe debit/credit + ledger rows in
one transaction + `operations` idempotency claim. Split readings (SQL time
vs total) are welcome but recorded as separate columns, never merged into
the headline percentile.

### M2 — MSPT / TPS

MSPT = milliseconds per server tick (p50/p95/p99 over the session window).
TPS is derived (`1000 / MSPT_p50`, capped at 20) and reported alongside —
never instead of the percentiles.

### M3 — Economy executor queue age

Age (ms) of the oldest task waiting in the economy single-thread executor's
queue, sampled at 1 Hz. Burst definition: 200 queued transfers from 50
threads, then steady state recovery time recorded.

### M4 — Money-supply persistence (cent-exact)

After each session: `SUM(player_balances)` must equal the full ledger replay
(metered categories + starting-balance mint, exactly as `SupplyIntegrity`
computes it) **to the cent**, with the escrow row included and
`/solidus-admin integrity check` clean. Any drift — even 0.01 — is a
reported defect, not a benchmark footnote.

## 5. Run manifest (copy per session)

```
session id:
date / operator:
solidus commit:            (git rev-parse HEAD)
mod_version:
storage mode:              sqlite | mysql
db version / flags:
jvm flags:
workload seed:
warmup / samples:
artifacts:                 (raw CSV paths)
```

## 6. Results

### M1 — `transferAtomicWithLedger` (ms)

| Backend | Callers | p50 | p95 | p99 | max | mean | manifest |
|---------|---------|-----|-----|-----|-----|------|----------|
| SQLite | 1 | — | — | — | — | — | — |
| SQLite | 8 | — | — | — | — | — | — |
| SQLite | 64 | — | — | — | — | — | — |
| MySQL (loopback) | 1 | — | — | — | — | — | — |
| MySQL (loopback) | 8 | — | — | — | — | — | — |
| MySQL (loopback) | 64 | — | — | — | — | — | — |

### M2 — Tick cost under economy load

| Session | MSPT p50 | MSPT p95 | MSPT p99 | TPS (derived) | manifest |
|---------|----------|----------|----------|---------------|----------|
| Idle baseline (mod loaded, no traffic) | — | — | — | — | — |
| Burst 1 — pay chatter | — | — | — | — | — |
| Burst 2 — auction storm | — | — | — | — | — |
| Burst 3 — heavy reads | — | — | — | — | — |
| Burst 4 — sell-GUI spam | — | — | — | — | — |
| Combined (1+2+3+4) | — | — | — | — | — |

### M3 — Economy executor queue age (ms)

| Phase | p50 | p95 | p99 | max | recovery to steady | manifest |
|-------|-----|-----|-----|-----|--------------------|----------|
| Steady state | — | — | — | — | — | — |
| 200-transfer burst | — | — | — | — | — | — |

### M4 — Supply conservation

| Session | SUM(balances) | Ledger replay | Δ | Escrow check | integrity check | manifest |
|---------|---------------|---------------|---|--------------|-----------------|----------|
| M4-a long mixed workload | — | — | — | — | — | — |
| M4-b crash-kill mid-burst + restart | — | — | — | — | — | — |

## 7. Acceptance criteria for replacing §21

A §21 estimate may be rewritten into a measured statement only when:

1. the matching table here has ≥ 3 sessions on independent days,
2. all sessions carry complete manifests,
3. M4 is cent-exact in every session, and
4. the WS-4 owner signs the session notes.

Until then §21 keeps its "design estimates, not measured benchmarks" banner.
