# Money representation: rounding audit & migration path (R12)

## Decision record (Phase 0/1)

The migration from `double`/`REAL` balances to integer minor units
(`long` cents) is **deferred**. This document records the audit of the
current rounding behavior and the migration plan for when it is
scheduled. The risk amplifier that made doubles dangerous - the
non-atomic transfer with a manual refund path - was already removed by
the single-transaction `SQLiteStorage.transferAtomic` fix.

## Current behavior (audited)

- All balance mutations pass through `CurrencyUtil.round(amount)` =
  `Math.round(value * 100.0) / 100.0` (half-up at 2 decimals).
- `CurrencyUtil.isValidAmount` bounds every transaction to
  `[MIN_TRANSACTION=0.01, MAX_TRANSACTION=10_000_000.0]`;
  `isValidBalance` bounds every stored balance to
  `[0.0, MAX_BALANCE=100_000_000.0]`, rejecting NaN/Infinity.
- Storage is `REAL` in SQLite (`player_balances.balance`); the in-memory
  cache stores `Double` values; the API (`SolidusAPI`, hooks) exposes
  `double`.
- Tax math rounds at the point of calculation (`TaxEngine.roundTax`),
  so a tax of 12.345 becomes 12.35 before collection.

## Why this is acceptable for now

- Binary64 exactly represents integers and simple 2-decimal values up
  to 2^53/100; with the 100M balance cap every balance is exactly
  representable after rounding, and every mutation re-rounds before
  storage, so drift cannot accumulate across operations.
- The dangerous pattern (subtract, then add later, with rounding in
  each step and a refund on failure) is gone: a transfer now computes
  both legs inside one transaction from the same pre-read state and
  commits or rolls back as a unit.

## Residual risks to re-evaluate

- Any future code path that adds/subtracts WITHOUT going through
  `CurrencyUtil.round` at the boundary can reintroduce drift.
- Aggregate reporting (sums over thousands of rows) accumulates binary64
  error in the last digits; acceptable for display, not for ledgers.

## Migration plan (Phase 2, when scheduled)

1. Add `balance_cents INTEGER` alongside `balance REAL`; backfill with
   `CAST(ROUND(balance * 100) AS INTEGER)` and verify
   `ABS(balance - balance_cents / 100.0) < 0.005` for every row before
   proceeding.
2. Dual-write window: mutate both columns, read from `REAL` (rollback
   safety), with a startup integrity check.
3. Switch reads to `balance_cents`, change the cache and
   `SolidusAPI`/hook signatures to `long`, format for display at the UI
   layer (`amount / 100.0` only for rendering).
4. Drop the `REAL` column after one full release cycle.
5. Keep `CurrencyUtil` as the single conversion point
   (`toCents(double)`, `fromCents(long)`) so the switch is mechanical.
