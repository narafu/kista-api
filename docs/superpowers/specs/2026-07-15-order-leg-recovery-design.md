# Order Leg Recovery Design

## Goal

Recover only missing strategy order legs on scheduler or manual reruns, instead of treating every existing `timing + direction` pair as a fully occupied slot.

## Problem

`TradingService.OrderSlot` currently keys existing orders by only `timing + direction`. This is too coarse for strategies that intentionally create multiple orders in the same timing and direction.

Example: INFINITE BUY cap can produce a base BUY plus correction BUY orders. If only one `AT_CLOSE + BUY` order exists after a partial save or placement failure, the current slot logic treats the whole BUY side as occupied and suppresses the missing BUY legs.

Price, quantity, and order type cannot reliably identify intent because cap and correction calculations may change those values between runs.

## Chosen Approach

Add a persisted logical leg identifier to `orders`:

```sql
order_leg VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN'
```

In Java this is represented as `String orderLeg` on the `Order` record, with constants and factory helpers for strategy-generated values. A string is intentional: VR and PRIVACY can create variable-length ladders, so enum values like `VR_BUY` are not precise enough and a finite enum would either under-model the behavior or grow into a brittle list of dozens of sequence values.

`OrderSlot` becomes:

```java
record OrderSlot(Order.OrderTiming timing, Order.OrderDirection direction, String leg)
```

The scheduler will compare existing PLANNED/PLACED orders and newly computed templates by `timing + direction + leg`. That allows reruns to recreate missing legs while leaving already planned or placed legs untouched.

## Initial Leg Taxonomy

The first implementation uses stable string keys:

```text
UNKNOWN
INFINITE_EARLY_AVG_BUY
INFINITE_EARLY_REF_BUY
INFINITE_EARLY_MERGED_BUY
INFINITE_LATE_REF_BUY
INFINITE_CORRECTION_01
INFINITE_CORRECTION_02
INFINITE_CORRECTION_03
INFINITE_MOC_SELL
INFINITE_LOC_SELL
INFINITE_LIMIT_SELL
REVERSE_INFINITE_MOC_SELL
REVERSE_INFINITE_LOC_SELL
REVERSE_INFINITE_LOC_BUY
VR_BUY_01..VR_BUY_20
VR_SELL_01..VR_SELL_20
PRIVACY_BUY_01..PRIVACY_BUY_N
PRIVACY_SELL_01..PRIVACY_SELL_N
```

`UNKNOWN` exists only for migrated legacy rows and tests/admin-created records that do not carry a semantic leg yet. New strategy-generated orders should use concrete legs. Sequence suffixes are 1-based and zero-padded to two digits for stable lexical sorting and easier diagnostics.

## Backward Compatibility

The Flyway migration adds `order_leg` with default `UNKNOWN` and backfills existing rows. Existing historical orders therefore remain readable without needing to infer exact strategy intent retroactively.

For rerun behavior, an existing `UNKNOWN` order still occupies only its old coarse `timing + direction` slot. This preserves conservative behavior for legacy rows and avoids accidentally duplicating old orders whose true leg is unknowable.

Newly generated orders with concrete legs use exact leg matching.

## Strategy Assignment Rules

Each strategy is responsible for assigning stable legs when it creates order templates.

INFINITE:
- Early mode first BUY at average price: `INFINITE_EARLY_AVG_BUY`
- Early mode second BUY at reference price: `INFINITE_EARLY_REF_BUY`
- Early mode cap merge when average/reference BUY collapse into one order: `INFINITE_EARLY_MERGED_BUY`
- Late mode reference BUY: `INFINITE_LATE_REF_BUY`
- Cap correction orders: `INFINITE_CORRECTION_1..3`
- First reverse day MOC sell: `REVERSE_INFINITE_MOC_SELL`
- Reverse recurring LOC sell/buy: `REVERSE_INFINITE_LOC_SELL`, `REVERSE_INFINITE_LOC_BUY`
- Common sells: `INFINITE_LOC_SELL`, `INFINITE_LIMIT_SELL`

VR:
- Merged BUY ladder orders: `VR_BUY_01`, `VR_BUY_02`, ... in final returned order
- SELL ladder orders: `VR_SELL_01`, `VR_SELL_02`, ... in final returned order

PRIVACY:
- BUY orders are assigned sequentially after PrivacyStrategy merges/sorts final BUY templates: `PRIVACY_BUY_01`, `PRIVACY_BUY_02`, ...
- SELL orders are assigned sequentially after final SELL templates: `PRIVACY_SELL_01`, `PRIVACY_SELL_02`, ...

If a strategy attempts to create a null or blank leg for a new scheduler template, the implementation must fail fast with an `IllegalStateException` rather than silently using `UNKNOWN`.

## Compute Skip Optimization

After leg-aware slots are available, `TradingService.collectCycleCandidate()` can skip `orderComputer.compute()` only when a strategy can prove from existing rows alone that no missing leg is recoverable.

Rules:
- Legacy `UNKNOWN` rows keep the old coarse behavior: if existing `UNKNOWN` orders occupy the relevant `timing + direction` pairs, computation can be skipped.
- Concrete INFINITE rows can be skipped only for known complete sets, such as early AVG+REF, early MERGED, late REF, or base+all correction legs for the relevant timing.
- VR and PRIVACY have variable-length ladders that cannot be proven complete from existing rows alone without recomputing strategy inputs, so they should not use concrete-leg compute skipping in the first implementation.
- If skip is not provably safe, compute and then filter by leg-aware slots.

This optimization is secondary to correctness. The implementation must prefer an extra `orderComputer.compute()` call over suppressing a missing leg.

## Strategy Priority Capability

Move allocator strategy priority from `TradingOrderBudgetAllocator.strategyPriority()` into `CycleOrderStrategy` capability metadata:

```java
default int allocationPriority() { return 100; }
```

Implementations return:
- VR: `0`
- INFINITE: `1`
- PRIVACY: `2`

`TradingOrderBudgetAllocator` receives `CycleOrderStrategies` and asks `cycleOrderStrategies.of(type).allocationPriority()`. This removes the allocator-level switch while preserving existing behavior.

## Indexes

Add indexes for scheduler and reservation queries:

```sql
CREATE INDEX idx_orders_cycle_date_status
    ON orders(strategy_cycle_id, trade_date, status);

CREATE INDEX idx_orders_cycle_date_timing_status
    ON orders(strategy_cycle_id, trade_date, timing, status);

CREATE INDEX idx_orders_account_date_status_direction
    ON orders(account_id, trade_date, status, direction);

CREATE INDEX idx_orders_account_date_ticker_direction_status
    ON orders(account_id, trade_date, ticker, direction, status);
```

These match the existing repository queries used for rerun detection, AT_OPEN placement, BUY budget reservation, and SELL reservation.

## Testing

Required tests:
- Domain strategy tests verify generated orders carry expected `orderLeg` strings.
- `TradingServiceTest` covers partial rerun recovery: one existing concrete BUY leg suppresses only that leg and saves missing BUY correction legs.
- Legacy `UNKNOWN` existing order keeps conservative coarse-slot behavior.
- `OrderPersistenceAdapterDbTest` verifies `order_leg` is persisted and loaded.
- Repository-level tests cover index-sensitive query boundaries through existing adapter methods.
- Allocator tests verify strategy priority remains unchanged after moving priority into `CycleOrderStrategy`.

## Out of Scope

- Changing broker API order payloads. `order_leg` is internal only.
- Inferring exact legs for historical rows.
- Changing public API DTOs unless existing responses already expose the entire `Order` domain object directly.
