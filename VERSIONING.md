# Versioning

The four Solidus ecosystem mods — **Core**, **Analytics**, **Governance**, and **Enforcer** —
share one **family version**. The family version is the integration contract: it tells a
server owner, at a glance, which releases are built and tested to work together.

| Bump | Meaning | Can you update one mod alone? |
|------|---------|-------------------------------|
| **Patch** `2.1.0 → 2.1.1` | Bug fixes, new features (commands, GUIs), and additive schema changes. Companions are never broken. | Yes — any `2.1.x` works with any other `2.1.y`. |
| **Family (Minor)** `2.1.x → 2.2.0` | **Owner-designated architecture era** — never used for ordinary feature additions. The `2.2` family is **reserved** for the cross-server / multi-server storage era. | No — the other mods must move to the new family in lockstep. |
| **Major** `2.x → 3.0.0` | Architectural reset of the ecosystem contract. | No — full coordinated release. |

Current family: **2.2.0** — Core is on it (the multi-server storage era began:
`MySqlStorage` + `DECIMAL(18,2)` exact money landed in 2.2.0). Companions built
against the `2.1.x` API keep working — the release is purely additive to
`SolidusAPI`; they declare the minimum family
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
