# WS-0 / Task A — أ-7: 26.3 Upgrade Diagnosis (compile-level, no code changed)

> Run: `./gradlew compileJava compileTestJava --no-daemon
> -Pminecraft_version=26.3 -Pfabric_api_version=0.165.0+26.3 -Pminecraft_dep=~26.3`
> Date: 2026-10-28 · JDK 25 · Gradle 9.5.1 · loom 1.16-SNAPSHOT
> Exit code: **1** (real compile failure — Fabric API 0.165.0+26.3 EXISTS and
> resolves fine; this is not a "no API yet" case).

## Headline numbers

| Metric | Value |
| --- | --- |
| Compile errors against MC 26.3 | **112** (all in `src/main`, **0** in `src/test`) |
| Inside `com.solidus.compat` (adapter + mixin) | **0** — the W-3 layer survived intact |
| Inside the 4 ScreenHandlers | **6** (Sell 4, Trade 2, Auction 0, Shop 0) |
| Everywhere else | **106** |
| Distinct root causes | **exactly 2 families** |

## Family A — 78 errors: `Items.*` colored-pane / dye constants removed

`cannot find symbol` on `class Items` for the GUI palette:
`GRAY_STAINED_GLASS_PANE` (12), `LIGHT_BLUE_STAINED_GLASS_PANE` (8),
`WHITE/YELLOW/RED/PURPLE/PINK/ORANGE/MAGENTA/LIME/LIGHT_GRAY/GREEN/CYAN/BLUE/BLACK_STAINED_GLASS_PANE`
(4 each), `LIME_DYE` (4), `GRAY_DYE` (4).

26.3 reshuffled the item registry for colored glass panes and dyes — the
static `Items.*` constants the GUI layouts were compiled against are gone.

| File | Errors |
| --- | --- |
| `shop/ShopGUILayout.java` | 62 |
| `trade/TradeGUI.java` | 12 |
| `sell/SellGUI.java` | 2 |
| `auction/AuctionGUI.java` | 2 |

**Fix shape:** stop referencing raw `Items` constants from GUI layouts; add a
palette resolver in `com.solidus.compat` (one indirection, per-version impl
under `impl_26_x/` like `ContainerClickBridge_26_1`) that returns filler/dye
items for the current version. That is the same lesson W-3 taught: the churn
lands in one adapter, not 4 GUIs.

**Effort:** 0.5–1 day (mechanical once the 26.3 replacement idiom is read; the
unknown is whether panes/dyes became data-driven lookups — worst case add 1
day for a registry-based resolver + probes).

## Family B — 34 errors: `Player.drop(ItemStack, boolean)` signature changed

26.3 added a third parameter: `ServerPlayer.drop(ItemStack, boolean,
Prediction)` (new drop-prediction argument; the boolean-only overloads are
gone). Every call site is the same idiom `player.drop(stack, false)`.

| File | Errors |
| --- | --- |
| `auction/AuctionManager.java` | 14 |
| `trade/TradeManager.java` | 8 |
| `shop/ShopManager.java` | 6 |
| `sell/SellScreenHandler.java` | 4 (one at :204 also flags arity) |
| `trade/TradeScreenHandler.java` | 2 |

**Fix shape:** one semantic decision (which `Prediction` value replaces the
old server-side "silent drop" semantics — read 26.3's `Prediction` enum) +
a mechanical sweep of 34 call sites across 5 files. Best routed through a
`compat` helper (`CompatDrop.dropSilently(player, stack)`) so the next
signature churn is again a one-file change.

**Effort:** 0.5 day including the wrapper + spot checks.

## Extra measured facts (loader-level, from the أ-2 live drill)

- With the current `minecraft_dep = ~26.1.2`, Fabric Loader **refuses to load
  the mod on a 26.3 server** (hard dependency rejection, proven live:
  `Mod 'Solidus' 2.3.3 requires any version between 26.1.2 (inclusive) and
  26.2- (exclusive) of 'Minecraft'`). Upgrading is therefore both a compile
  AND a dependency-range change (`~26.3` + loader/Fabric API bumps).
- Stable `latest` on the Mojang manifest today is **26.3** (snapshot
  26.4-snapshot-6) — the gap grows with every release; the compat-matrix
  probes now mark 26.2/26.3 as KNOWN failures without gating main (أ-8).

## Realistic effort estimate for a full 26.3 upgrade

| Work item | Estimate |
| --- | --- |
| Family B (drop → Prediction) + compat wrapper | 0.5 day |
| Family A (palette resolver + 4 GUI files) | 0.5–1 day |
| `gradle.properties` bumps (MC/Fabric API/dep range) + probes green | 0.5 day |
| Full suite + live-boot drill re-run against a 26.3 server | 0.5 day |
| **Total** | **2–3 working days** (single engineer, with the compile-level view only) |

**Caveats (the W-2/W-3 lesson):** this measures COMPILE breakage only. 26.3
may also change runtime behavior the probes cannot see (container-click
routing shapes, GUI packet handling). The first 26.3 boot must re-run the
أ-3 log assertions and the RCON drill (`scripts/live-boot/`) before any
support claim — do not ship on a green compile alone. No date commitments
before this diagnosis per the task contract; this report is the input for
Decisions 1 & 2 in `WORK_PLAN_AGENT_VS_HUMAN.md §5`.
