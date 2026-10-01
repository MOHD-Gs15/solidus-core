# Versioning

The four Solidus ecosystem mods — **Core**, **Analytics**, **Governance**, and **Enforcer** —
share one **family version**. The family version is the integration contract: it tells a
server owner, at a glance, which releases are built and tested to work together.

| Bump | Meaning | Can you update one mod alone? |
|------|---------|-------------------------------|
| **Patch** `2.1.0 → 2.1.1` | Bug fixes, new features (commands, GUIs), and additive schema changes. Companions are never broken. | Yes — any `2.1.x` works with any other `2.1.y`. |
| **Family (Minor)** `2.1.x → 2.2.0` | **Owner-designated architecture era** — never used for ordinary feature additions. The `2.2` family is **reserved** for the cross-server / multi-server storage era. | No — the other mods must move to the new family in lockstep. |
| **Major** `2.x → 3.0.0` | Architectural reset of the ecosystem contract. | No — full coordinated release. |

Current family: **2.3.3** — the update-resilience / family-contract era. The
compatibility architecture landed: all Minecraft-internal touch-points are
confined to `com.solidus.compat` (audit W-3 — a Minecraft internals change now
degrades the virtual-GUI layer gracefully with a clear banner instead of
crashing the server at startup), and the family glue moved from silent
reflection to a real contract (audit W-5): companions compile against the
`solidus-api` jar (a Minecraft-free artifact nested inside Core) and declare
a `"solidus": ">=2.3.x <3.0.0"` floor in their `fabric.mod.json`, so
Fabric's loader — not a log line — rejects incompatible combinations.
Patch **2.3.3** (Core alone, per the patch rule above) closed a CI-side hole
the integration audit found in the W-2 net: the `test` task did not depend
on `jar`, so on a fresh checkout (exactly what CI runs) the packaging smoke
test SILENTLY ABORTED and the shipped-jar Redis checks never executed in CI;
`test` now builds the jar first, and the smoke test both prefers the
current version's artifact over stale ones in `build/libs` and asserts the
nested `solidus-api-<version>.jar` matches `mod_version` (anti-drift).
The same patch added `solidus-enforcer` to Core's `suggests` (it listed only
Governance and Analytics) and refreshed the Jedis-era notes.
Patch **2.3.2** closed audit finding **W-5** end-to-end: the solidus-api
contract gained `isMysqlMode()`, `getShopSellPrices()` and
`withLedgerConnection(LedgerWork)` — the three surfaces Enforcer and
Analytics still reached by reflecting into Core internals
(ShopManager records, EconomyEngine, a Proxy over
TransactionLog$SqlWork). Both companions now compile against the
contract with ZERO reflection; their `depends` floors are `>=2.3.2`.
Governance followed in the same patch line (2.3.0 → 2.3.2) when the
follow-up integration audit found its simulation still carried the family's
LAST reflective reach-in (`getEconomyEngine() → getStorage() →
getActiveAccountCount(int)` — methods Core no longer declares, so the path
silently returned -1 on every query and its direct-file fallback could never
see the MySQL backend); it now rides `withLedgerConnection` like everyone
else. The same 2.3.2 patch fixed a production bug the new degradation
tests caught on their first run: a CompatProbes lookup used `ServerPlayer`
for the `containerMenu` field, which is DECLARED on `Player` — the probe
always failed and the GUI layer was silently disabled on every 2.3.0/2.3.1
server.
Patch **2.3.1** (Core alone, per the patch rule above) shipped audit fix
**W-2**: the optional Redis layer's client was swapped from Lettuce 6.5.5
to Jedis 5.2.0 — the old build nested only `lettuce-core` while Lettuce
hard-requires Netty + Project Reactor at runtime, so `redis.enabled=true`
crashed the shipped jar with `NoClassDefFoundError` while CI stayed green
(the test classpath silently supplied the missing transitives). Wire
payloads are unchanged, so mixed 2.3.x servers interoperate on the same
Redis; see `docs/DB_SCALING_PLAN.md` §12.

The previously promised family: 2.2.5 hardened the whole storage/auction surface against the security-audit
findings SOL-001…SOL-006 — Redis URI credential redaction + scheme allowlist,
TLS-by-default MySQL connections with a loud cleartext warning off loopback,
player-name sanitization at every ledger/auction boundary, log-forgery-proof
logging, and a 128 KB cap on auction item payloads).
Companions built against the `2.1.x` API keep working — the releases are
purely additive to `SolidusAPI`; they declare the minimum family
they were integration-tested against in their own `fabric.mod.json`.

> **Owner rule (2026-09-06):** feature additions such as `/trade` and the
> auction bidding system stay inside the current `2.1.x` family — they shipped
> as `2.1.4`, NOT as a family jump. The `2.2.x` family is reserved exclusively
> for the upcoming multi-server / storage rewrite and will advance in patches
> (`2.2.0`, `2.2.1`, ...) as that era lands. Breaking API / hook-signature
> changes remain impossible inside a patch family.

Each mod's `fabric.mod.json` `suggests` entry declares the **minimum family version** it
was integration-tested against (Core ships `"solidus-governance": ">=2.1.0",
"solidus-analytics": ">=2.1.0", "solidus-enforcer": ">=2.1.0"`).

## Where the number lives

Each mod's version has exactly one source of truth — a single line in `gradle.properties`:

```properties
mod_version = 2.1.4
```

`fabric.mod.json` picks it up through the `${version}` expansion at build time — never
edit it by hand. The jar filename, the `Implementation-Version` manifest attribute, and
the version the Cloud agent reports in `health.meta` all flow from the same line.

## Versioned separately

Two cloud components keep their own version families, on purpose — they release on
their own cadence and must stay compatible across mod patch releases:

| Component | Version | Contract |
|-----------|---------|----------|
| Cloud relay (`cloud-relay/` in Solidus-analytics, Node.js) | `0.x` | see `cloud-relay/README.md` |
| Cloud wire protocol | `1.x` | see `docs/cloud/PROTOCOL.md` §14 (Versioning & Compatibility) |
