# Modrinth Publishing Memo — what is needed (execution is human-only)

> Verified state: Solidus Core is **not on Modrinth** yet. Publishing
> requires the owner's Modrinth account (2FA, PAT) and is therefore
> **human-only** — the automation task deliberately stops at this memo.

---

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
| Description body | Reuse `README.md` content (already fact-checked); add `docs/OPS_RUNBOOK.md` and `docs/BENCHMARKS.md` links so admins find ops guidance. |

## 3. Version entries (one per published release)

For each of v2.2.5 … v2.3.3 (see `notes/RELEASES_DRAFT.md` for bodies):

| Field | Value |
|-------|-------|
| Version number | `2.2.5` … `2.3.3` (Modrinth convention: no leading `v`) |
| Version channel | `release` (all six are release-grade) |
| Game versions | **26.1.2** (the pinned target; do NOT claim 26.2/26.3 — those are CI probes only, per `compat-matrix.yml`) |
| Loaders | `fabric` |
| Dependencies | **Fabric API — required** (`0.155.2+26.1.2` or newer minor); optionally note "works with any recent Fabric API for 26.1.x" |
| Files | The CI-built `solidus-<version>.jar` from the green run of that exact commit (see publishing checklist in `notes/RELEASES_DRAFT.md`) |
| Changelog | Paste the matching section from `notes/RELEASES_DRAFT.md` |

Order matters for users: publish oldest → newest so the "latest" landing
page ends at 2.3.3 with the full ladder browsable.

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
   single source of truth is `notes/RELEASES_DRAFT.md` → then CHANGES.md.
3. Future releases: publish GitHub Release first, Modrinth version second,
   same changelog text, same jar artifact (never rebuild between the two).
