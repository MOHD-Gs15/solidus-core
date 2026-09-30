# Versioning

The four Solidus ecosystem mods — **Core**, **Analytics**, **Governance**, and **Enforcer** —
share one **family version**. The family version is the integration contract: it tells a
server owner, at a glance, which releases are built and tested to work together.

| Bump | Meaning | Can you update one mod alone? |
|------|---------|-------------------------------|
| **Patch** `2.1.0 → 2.1.1` | Bug fixes, new features (commands, GUIs), and additive schema changes. Companions are never broken. | Yes — any `2.1.x` works with any other `2.1.y`. |
| **Family (Minor)** `2.1.x → 2.2.0` | **Owner-designated architecture era** — never used for ordinary feature additions. The `2.2` family is **reserved** for the cross-server / multi-server storage era. | No — the other mods must move to the new family in lockstep. |
| **Major** `2.x → 3.0.0` | Architectural reset of the ecosystem contract. | No — full coordinated release. |

Current family: **2.3.0** — the update-resilience / family-contract era. The
compatibility architecture landed: all Minecraft-internal touch-points are
confined to `com.solidus.compat` (audit W-3 — a Minecraft internals change now
degrades the virtual-GUI layer gracefully with a clear banner instead of
crashing the server at startup), and the family glue moved from silent
reflection to a real contract (audit W-5): companions compile against the
`solidus-api` jar (a Minecraft-free artifact nested inside Core) and declare
`"depends": { "solidus": ">=2.3.0 <3.0.0" }` in their `fabric.mod.json`, so
Fabric's loader — not a log line — rejects incompatible combinations. The
previously promised family: 2.2.5 hardened the whole storage/auction surface against the security-audit
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
was integration-tested against (e.g. Core ships `"solidus-governance": ">=2.1.0",
"solidus-analytics": ">=2.1.0"`).

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
