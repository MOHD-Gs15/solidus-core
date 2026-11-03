# Decision Memos — for the Owner (and handoff notes for the build task)

> Delivered by the documentation-sync task (WS-7). **Nothing in this file
> changes code or metadata by itself** — each memo ends in a concrete,
> ready-to-apply action that the owner (or the task owning that file)
> executes after approval. File ownership per the task split: the build task
> owns `fabric.mod.json`, `build.gradle`, `.github/**` and `src/**`.

---

## MEMO-1 · Removal of the deprecated reflective shims (`getEconomyEngine` / `getTransactionLog`)

**Background.** The 2.3.0 family bump introduced the `solidus-api` contract
jar and demoted `SolidusAPI`'s erased legacy signatures to
`@Deprecated` reflective shims, kept "deprecated for one transition family"
— with **no version number and no date** attached. As of 2.3.2 both shipped
companions compile against the contract with zero reflection, so the shims
have no known caller left in the ecosystem; they remain public API, and
removing them inside a `2.3.x` patch is forbidden by the versioning policy
(breaking changes ship only at family/major boundaries).

**Proposal (owner decides — not effective until approved):**

| Item | Proposal |
|------|----------|
| Remove in | **Next family bump: `2.4.0`** if the next era is additive-compatible, otherwise `3.0.0`. Rationale: the removal must ride a boundary that is already breaking, so it adds zero extra migration cost; a dedicated "removal release" would burn a version number for a two-line diff. |
| Target date | Tie to the first family-bump release **after** companions have shipped at least one full release cycle on `>=2.3.2` (they have, since 2.3.2). Suggested cut-line: when `solidus-api`'s contract adds its first new member after 2.3.2, whichever release carries it also drops the shims. |
| Interim guard (optional, patch-safe) | Log a one-line `logger.warn("SolidusAPI.getEconomyEngine() is deprecated and will be removed in the next family release")` on first call per classload — a warning is additive, so it may ship in any `2.3.x` patch. |
| Execution owner | `src/**` = build task (Task A). Documentation (VERSIONING.md) already states the policy conditionally. |

**Alternatives considered:** (a) keep the shims forever — rejected, they
perpetuate exactly the reflective coupling W-5 closed; (b) remove them in a
2.3.x patch with a major-version error message — rejected, violates the
family contract and silently kills companion servers on update.

---

## MEMO-2 · `suggests >=2.1.0` (Core) vs `depends >=2.3.2` (companions)

**The contradiction.** Core's `fabric.mod.json` ships
`"solidus-governance": ">=2.1.0", "solidus-analytics": ">=2.1.0",
"solidus-enforcer": ">=2.1.0"` in `suggests`. Meanwhile the companions
themselves declare the loader-enforced `"solidus": ">=2.3.2"` in their
`depends` (since 2.3.2, the W-5 closure). `suggests` is informational in
Fabric's loader — it changes nothing at runtime — but the docstring "the
minimum family version it was integration-tested against" is now false:
the actually integration-tested floor is `>=2.3.2`.

**The two options (owner picks one):**

1. **Raise Core's `suggests` to `>=2.3.2`** for all three companions.
   *Pros:* documentation-honest, zero behavioral change (`suggests` is
   advisory), one-line diff in `fabric.mod.json`. *Cons:* needs a Core
   release to ship; purely cosmetic since enforcement lives in the
   companions' `depends`.
2. **Keep `>=2.1.0` and re-document `suggests` as deliberately advisory**
   ("the oldest companion generation we will not actively break — loader
   enforcement of the real floor happens in the companions' own
   `depends`"). *Pros:* no release needed. *Cons:* the two numbers keep
   telling different stories and every future audit re-flags it.

**Recommendation:** option 1, folded into the next Core patch that touches
`fabric.mod.json` anyway (do not cut a release for it alone). VERSIONING.md
currently documents the mismatch as "stale-but-harmless" with the enforced
contract named explicitly, so both options leave the docs truthful until
the owner acts.

**Execution owner:** `fabric.mod.json` = build task (Task A). If option 2
is chosen, only this memo and VERSIONING.md need a follow-up edit.

---

## MEMO-3 · Handoff notes observed during the documentation pass (for the build task / Task A)

Facts observed while reading CI and build wiring for documentation. Not
acted on (wrong owner); recorded here so they are not lost:

1. **`compat-matrix.yml` trigger looks corrupted.** The `on.push.branches`
   and `on.pull_request.branches` values read `ain]` — the opening bracket
   of `[main]` is missing (likely a non-ASCII-stripping casualty; the repo
   has `scripts/strip_non_ascii.py` history). As written, the
   compat-matrix workflow can only be started manually
   (`workflow_dispatch`), and the push/PR trigger matches no real branch.
   Suggested fix (one line each): `branches: [main]`.
2. **Wiring `scripts/docs_lint.py` into CI** (proposed invocation — Task A
   owns `.github/workflows/`):

   ```yaml
   - name: Documentation lint (numbers vs reality)
     run: python3 scripts/docs_lint.py
   ```

   Cheap (pure Python, no Java), and it fails the build the moment docs
   drift from `gradle.properties` / `src/test` again. Recommended placement:
   a dedicated docs job or the first step of the existing test job.
3. **Dependabot still bumps `mockito-core`** (build.gradle dev-dependency
   history shows 5.11.0 → 5.23.0 merges) although zero tests import it on
   the Java 25 toolchain. Harmless but misleading dependency-graph noise;
   candidate for removal in a Task A patch (verify nothing else pulls it
   first).
