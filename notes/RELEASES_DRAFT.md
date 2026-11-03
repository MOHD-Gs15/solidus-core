# Release Drafts — paste-ready bodies for the six missing GitHub Releases

> **State verified 2026-10:** the repo has **0 published Releases** and tags
> stop at `v2.2.4`, while `mod_version = 2.3.3`. Drafts below cover the six
> unreleased versions. **Execution is human-only** (owner's GitHub account):
> create the tag, then paste the matching body at
> <https://github.com/MOHD-Gs15/solidus-core/releases/new>.
> Tag names follow the existing convention (`v2.2.4` → `v2.2.5` …).
> These drafts are derived from `notes/CHANGES.md` (which is derived from
> the actual merge history) — no new claims were invented.

---

## v2.2.5

**Title:** `2.2.5 — Security-audit round: SOL-001..SOL-006 closed`

```markdown
First dedicated security audit of the storage/auction surface — all six findings closed.

## Security fixes
- **SOL-001 (CWE-532)** — Redis URI credentials redacted in every `RedisLayer` log/error path (`redactUri`; also covers raw `/` passwords, first `@` after `//` is the RFC-3986 userinfo delimiter).
- **SOL-002 (CWE-319)** — **`useSsl` now defaults to TRUE** for MySQL connections; loud cleartext warning when disabled on non-loopback hosts (`localhost`/`127.x`/`::1` only).
- **SOL-003** — Redis URI scheme allowlist (`redis://` / `rediss://`), fail-closed before any network I/O; `rediss://` gets certificate-verified TLS.
- **SOL-004** — player-name sanitization at every ledger/auction boundary (clamped to `VARCHAR(64)`, control chars + legacy codes stripped).
- **SOL-005 (CWE-117)** — log-forgery-proof logging (`sanitizeForLog` escapes CR/LF/TAB).
- **SOL-006 (CWE-400)** — 128 KB cap on serialized auction item payloads, enforced at LIST time before any fee or row.

## Tests
+38 tests (sanitizers, URI redaction, scheme allowlist, SSL default, loopback classification, NBT cap). Suite: 435 tests/run — CI fully green: 870 passed / 0 failed / 0 skipped.

## Upgrade notes
If your `storage.json` relies on the old implicit cleartext MySQL connection, check `useSsl` after upgrading: the default flipped to `true`.
```

---

## v2.2.6

**Title:** `2.2.6 — Reliability & accounting integrity fixes (full audit)`

```markdown
Full reliability audit closed — 7 CRITICAL, 5 HIGH and all MEDIUM findings fixed. Full report: `docs/AUDIT_REPORT.md`.

## CRITICAL — economy/accounting integrity
- **ESC-01** — restart no longer wipes escrowed bid funds (`initialize()` no longer zeroes the escrow account).
- **ASC-01** — anti-snipe extension wrote a non-existent column → unbacked top bid (bidder refunded, seller never paid). Fixed.
- **ASC-02** — auction buy-now claim is now conditional (`status = 0` guard) → cross-server double purchase eliminated.
- **ESC-02** — outbid refunds re-read displaced bid state inside the claim phase → no double refund / trapped escrow under overlapping bids.
- **ASC-03** — orphaned-bid startup sweep claims before refunding → crash-replay / cross-server double refund eliminated.
- **TRD-01/TRD-02** — trade items no longer deposit into detached player objects; exceptional money futures no longer wedge sessions.

## HIGH / MEDIUM
- Trade: money rollback is hook-free (no governance triple-count), cancel/execute race hardened, shutdown drain ordering fixed.
- Shop: buy/sell liveness re-checks + item recovery paths.
- Sell GUI: `removed()` wrapped in try/finally — no lost items on screen close.
- Redis L2 checks moved off the server thread (guarded-call executor) — server-thread hang hardening.
- `MAX_TRANSACTION` guard, component-bearing stack sell guard, `PayCommand` exceptional-future handling.

## Tests
435 executed / 0 failed (31 CI-gated run on containers in CI).

⚠ Note: if you ran 2.2.5 with auctions that survived a restart, run `/solidus-admin integrity check` after upgrading — ESC-01 may have silently zeroed escrow on your old install.
```

---

## v2.3.0

**Title:** `2.3.0 — FAMILY: update-resilience architecture + solidus-api contract`

```markdown
**Family bump (minor).** Companions must move to the 2.3 family in lockstep. The compatibility architecture of the ecosystem changed — see `VERSIONING.md`.

## W-3 — survive Minecraft updates gracefully
- All Minecraft-internal touch-points confined to `com.solidus.compat`; the high-risk click mixin is `require=0`.
- Startup probes verify every compiled-against shape; on failure the virtual-GUI layer degrades with a clear banner while commands, storage, ledger, taxes and companion enforcement keep working.
- `/solidus-admin compat` reports probe state; `compat/PACKAGE.md` is the Minecraft-update checklist (AR+EN).

## W-5 — reflection replaced by a loader-enforced contract
- New `solidus-api` Minecraft-free contract jar (nested jar-in-jar): `SolidusApi`, `SolidusApiAccess`, `SolidusTransactionHook`, `BalanceEntry`, `EconomyStats`, `TransferResult`, `TransactionRecord`.
- Companions declare `"solidus": ">=2.3.x <3.0.0"` in `depends` — Fabric's loader rejects incompatible combos at startup.
- `SolidusAPI` legacy signatures (`getEconomyEngine`/`getTransactionLog`) are **deprecated** transition shims (removal policy: `VERSIONING.md`).
- New audited `setBalance`; `logTransaction` validates type codes strictly.

## CI
- New `compat-matrix` workflow: MC 26.1.2 required, 26.2/26.3 forward-probes (informative-only), Fabric API resolved dynamically.

## Tests
435 tests green.
```

---

## v2.3.1

**Title:** `2.3.1 — W-2: self-contained Redis runtime (Lettuce → Jedis)`

```markdown
**Fixed in production:** `redis.enabled=true` shipped a `NoClassDefFoundError` on first use — the old build nested only `lettuce-core` while Lettuce hard-requires Netty + Project Reactor at runtime; CI stayed green because the test classpath silently supplied the missing transitives.

## Changes
- `RedisLayer` now speaks **Jedis 5.2.0** (plain sockets — no classpath collision with Minecraft's Netty), with commons-pool2 + org.json nested alongside.
- **Wire payloads unchanged** — mixed 2.3.x servers interoperate on the same Redis regardless of embedded client. RESP2 pinned; lazy reconnect + dedicated pub/sub reconnect thread.

## New regression guards (this cannot silently regress)
- `RedisPackagingSmokeTest` — drives the BUILT jar through an isolated classloader; a missing runtime dependency now fails the build loudly.
- `FakeRedisServer` + `RedisLayerFakeServerTest` — full Jedis client path over an in-JVM RESP2 server on a real TCP socket.

## Tests
446 tests, 0 failures.

## Upgrade notes
No config changes. If you were bitten by W-2 on 2.3.0, this is the fix release.
```

---

## v2.3.2

**Title:** `2.3.2 — W-5 closed: solidus-api contract extensions + compat probe fix`

```markdown
The last reflective reach-ins are gone — both companions now compile against the `solidus-api` contract with ZERO reflection.

## Contract additions (replace companion reflection)
- `isMysqlMode()` — backend signal for Analytics (SQLite files vs shared MySQL ledger).
- `getShopSellPrices()` — material → sell-price snapshot; replaces Enforcer's reflective ShopManager walk.
- `withLedgerConnection(LedgerWork<T>)` — bounded, read-only, backend-agnostic ledger SQL; replaces Analytics' proxy over `TransactionLog$SqlWork`.
- Companions' `depends` floors are now `>=2.3.2`; Governance moved 2.3.0 → 2.3.2 in the same line (its simulation silently returned -1 on every query — the family's last reflective reach-in).

## Production bug fixed (caught by the new degradation tests on first run)
- `CompatProbes` looked up `containerMenu` on `ServerPlayer` — the field is DECLARED on `Player` — so the probe always failed and **the GUI layer was silently disabled on every 2.3.0/2.3.1 server**. The probe now reflects on the declaring class; `CompatDegradationTest` (11 tests) pins the degradation contract.

## Tests
462 tests, 0 failures.

## Upgrade notes
If GUIs were dead on 2.3.0/2.3.1 without any error, this is the fix. Companion mods should rebuild against solidus-api 2.3.2.
```

---

## v2.3.3

**Title:** `2.3.3 — CI hole closed: the W-2 packaging smoke was silently skipping`

```markdown
Integration-audit finding: `test` did not depend on `jar`, and CI runs `./gradlew test` on a fresh checkout — so `RedisPackagingSmokeTest` found no jar and aborted via `Assumptions`: green, but unverified. The W-2 net ("CI catches a broken shipped jar") was effectively OFF.

## Changes
- Every Test task now depends on `jar` (local runs too — 2.3.2-era local runs had been smoke-testing the stale 2.3.1 artifact).
- `RedisPackagingSmokeTest` prefers `solidus-<mod_version>.jar` over stale artifacts and asserts the nested contract jar is exactly `solidus-api-<mod_version>.jar` (anti-drift).
- `fabric.mod.json` `suggests` now includes `solidus-enforcer` (was missing).
- Redis layer docs corrected to Jedis 5.2.0.

## Tests
462 tests, 0 failures, 31 env-gated (all 462 execute in CI).

## Upgrade notes
Core-alone patch — safe to drop in on any 2.3.x server. No config changes.
```

---

## Publishing checklist (per release)

1. Tag from the exact merge/fix commit (see `notes/CHANGES.md` version
   headers for the commit list), push the tag.
2. Paste the draft body above; set the **previous tag** in GitHub's compare
   so the auto-generated diff is attached.
3. Attach the CI-built artifact `solidus-<version>.jar` from a **green run
   of that exact commit** (do not rebuild locally — CI runs the packaging
   smoke).
4. After all six: check the Releases page shows a continuous
   v2.2.4 → v2.3.3 ladder, then do Modrinth (see
   `notes/MODRINTH_PUBLISHING.md`).
