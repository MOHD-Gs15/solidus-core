# Modrinth Publishing Memo — the 2.4.0 family reality (execution is human-only)

> Verified state: Solidus Core is **not on Modrinth** yet. Publishing
> requires the owner's Modrinth account (2FA, PAT) and is therefore
> **human-only** — the automation task deliberately stops at this memo.
>
> **Rewritten in the ws-d docs-reality pass.** The previous edition still
> described the 2.3.3 era (target 26.1.2, fabric-api `0.155.2+26.1.2`,
> versions v2.2.5…v2.3.3, "do NOT claim 26.2/26.3"). Executing it as
> written would have listed the mod on a Minecraft version that refuses
> to load the shipped jar — the released binary declares `~26.3` as a
> **required** dependency. This memo is now lint-enforced:
> `scripts/docs_lint.py` checks the fields below against
> `gradle.properties` and fails CI on drift.

---

## 0. The one-line reality (checked by docs_lint)

| Fact | Value | Source of truth |
|------|-------|-----------------|
| Mod version | **2.4.0** | `gradle.properties` `mod_version` |
| Minecraft | **26.3 — required** (`~26.3`) | `gradle.properties` `minecraft_dep` |
| Fabric API | **required `0.165.0+26.3`** | `gradle.properties` `fabric_api_version` |
| Loader | 0.20.1 | `gradle.properties` `loader_version` |
| Java | 25 (server-side) | `gradle.properties` `java_version` |

Per `compat-matrix.yml`: **26.3 is the REQUIRED target** (failures block).
26.1.2 and 26.2 ride as **informative backward probes** and 26.4 as a
forward probe — they are CI-only environments, never published game
versions. The released jar declares `~26.3`, so it refuses to load on
26.1.2/26.2 by design.

## 1. Prerequisites (account & permissions)

| Item | Notes |
|------|-------|
| Modrinth account | Preferably the existing `MOHD_Gs` profile (already linked from README). |
| Authorization | Create a PAT at modrinth.com/settings/pats with `write::projects` + `write::versions` scope — only if the owner later wants a bot to upload; the first publish is easiest by hand. |
| Project ownership | Only the owner creates the project; collaborators are added after. |

## 2. Project creation fields

| Field | Value to use |
|-------|--------------|
| Title | `Solidus` (Modrinth slugs are lowercase: `solidus`) |
| Project type | **Mod** |
| Loader | **Fabric** |
| Summary (≤ 256 chars) | e.g. *"Complete server-side economy: balances, transfers, shop, auction house with escrow bidding, trading, and an auditable ledger. MySQL/MariaDB + Redis ready. Vanilla client — zero player installs."* |
| Categories | `economy`, `administration`, `social` (per Modrinth's server-side category set) |
| License | **MIT** (matches LICENSE) |
| Environment | **Server-side only** — client side "not required"; players join vanilla. |
| Source URL | `https://github.com/MOHD-Gs15/solidus-core` |
| Issue tracker | GitHub Issues of the same repo |
| Description body | Reuse `README.md` content (already fact-checked); add `docs/OPS_RUNBOOK.md` and `docs/BENCHMARKS.md` links so admins find ops guidance. In the description, state the supported game version as **26.3**. |

## 3. Version entries — publish **2.4.0** only

| Field | Value |
|-------|-------|
| Version number | `2.4.0` (Modrinth convention: no leading `v`) |
| Version channel | `release` |
| Game versions | **26.3** — required (probe-only versions are never published; see §0) |
| Loaders | `fabric` |
| Dependencies | **Fabric API — required, `0.165.0+26.3`** (match the `~26.3` contract; do not write "or newer minor" for a different MC line) |
| Files | `solidus-2.4.0.jar` — the exact asset attached to the GitHub Release **v2.4.0** (SHA-256-verified against the green family-boot run's `family-jars-2.4.0` artifact and its `SHA256SUMS.txt`). Download it from the Release; **never rebuild**. |
| Changelog | The GitHub Release v2.4.0 body, word-identical (single source of truth: `notes/CHANGES.md` §2.4.0) |

### Why no historical Modrinth versions

GitHub Releases now cover the ladder — `v2.2.5`, `v2.3.0`, `v2.3.1`,
`v2.3.2`, `v2.3.3`, `v2.4.0` (v2.2.6 deliberately skipped: no build of it
ever existed; the "2.2.6" audit round shipped inside 2.3.0). But every
release older than v2.4.0 carries **no jar** — per the W-2 rule, old
versions are never rebuilt. A Modrinth version entry without its original
file is a dead end for users, so the ladder stays on GitHub and Modrinth
starts at 2.4.0.

### Future releases (2.4.1 and beyond)

Same pipeline every time: build **once** in CI → the jar is attached to the
GitHub Release → the **same** jar and the **same** changelog text go to
Modrinth. Game versions follow whatever `minecraft_dep` says at that
moment — check `gradle.properties`, not habit.

## 4. Gallery & presentation

- Screenshots (in-game): shop main menu, a shop section, auction house,
  auction listing dialog, trade window, `/baltop`, `/transactions`.
  All GUIs are server-side, so plain vanilla-client screenshots are correct.
- Caption each shot; the trade/escrow screens are the differentiators
  (mandatory escrow, dual confirmation).
- Optional: a short "network setup" section in the description pointing to
  `docs/DB_SCALING_PLAN.md` + `docs/OPS_RUNBOOK.md`.

## 5. Post-publish housekeeping

1. Add the Modrinth badge to `README.md` next to the GitHub badges (one
   line, docs task can do it on request).
2. GitHub Releases and Modrinth changelogs must stay word-identical —
   single source of truth is `notes/CHANGES.md` → GitHub Release body →
   Modrinth changelog.
3. Future releases: publish GitHub Release first, Modrinth version second,
   same changelog text, same jar artifact (never rebuild between the two).
