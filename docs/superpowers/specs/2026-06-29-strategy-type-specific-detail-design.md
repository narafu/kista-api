# Strategy Type-Specific Detail Design

## Scope

Apply only the following schema/model split now:

- Move `strategy.division_count` to `strategy_infinite.division_count`
- Move `cycle_position.is_reverse_mode` to `cycle_position_infinite.is_reverse_mode`
- Remove `strategy_cycle.seed_resolved_by`
- Keep VR strategy design out of scope for now

## Goal

Separate INFINITE-only fields from common strategy tables without introducing premature VR-specific structures.

## Target Model

### Common strategy root

`strategy`

- `id`
- `account_id`
- `type`
- `ticker`
- `status`
- `cycle_seed_type`

### INFINITE strategy detail

`strategy_infinite`

- `strategy_id`
- `division_count`

Rules:

- Exists only when `strategy.type = INFINITE`
- One-to-one with `strategy`
- `division_count` is no longer stored on the common strategy row

### Common cycle metadata

`strategy_cycle`

- `id`
- `strategy_id`
- `start_amount`
- `end_amount`
- `start_date`
- `end_date`

Rules:

- `seed_resolved_by` is removed
- `strategy_cycle` keeps only type-agnostic cycle lifecycle data

### Common cycle position snapshot

`cycle_position`

- `id`
- `strategy_cycle_id`
- `usd_deposit`
- `closing_price`
- `avg_price`
- `holdings`

Rules:

- `cycle_position` keeps only type-agnostic position snapshot data

### INFINITE position detail

`cycle_position_infinite`

- `cycle_position_id`
- `is_reverse_mode`

Rules:

- Exists only for INFINITE positions
- One-to-one with `cycle_position`

## Domain Direction

The current `Strategy` aggregate directly carries `divisionCount`, and `CyclePosition` directly carries `isReverseMode`.
After this change, the domain should stop treating those as common fields.

Recommended direction:

- `Strategy` contains only common strategy fields
- Add an INFINITE-specific detail model for `divisionCount`
- `CyclePosition` contains only common position snapshot fields
- Add an INFINITE-specific position detail model for `isReverseMode`

This is a real type-specific detail split, not just a table split inside persistence.

## Persistence Direction

Persistence must enforce these invariants:

- Saving an INFINITE strategy writes both `strategy` and `strategy_infinite`
- Saving a non-INFINITE strategy writes only `strategy`
- Saving an INFINITE cycle position writes both `cycle_position` and `cycle_position_infinite`
- Saving a non-INFINITE cycle position writes only `cycle_position`
- Reading an INFINITE strategy/position reconstructs common data plus INFINITE detail

## Migration Direction

Add append-only Flyway migrations that:

1. Create `strategy_infinite`
2. Backfill `strategy.division_count` into `strategy_infinite`
3. Create `cycle_position_infinite`
4. Backfill `cycle_position.is_reverse_mode` into `cycle_position_infinite`
5. Drop `strategy.division_count`
6. Drop `cycle_position.is_reverse_mode`
7. Drop `strategy_cycle.seed_resolved_by`

Migration design constraints:

- Preserve existing strategy and cycle position rows
- Use named PK/FK constraints
- Keep FK delete behavior explicit
- Cross-check entity nullability and column order against Flyway SQL

## Application Impact

Expected impact areas:

- Strategy registration and load paths
- Trading calculation paths that currently read `strategy.divisionCount()`
- Reporting paths that currently read `cycle_position.is_reverse_mode`
- Cycle rotation logic affected by `seed_resolved_by` removal
- API DTOs that currently expose common `divisionCount`

## Trade-Offs

### Chosen approach

Adopt a partial type-specific detail split for INFINITE only, while leaving VR for later.

Pros:

- Removes INFINITE-only fields from common tables now
- Establishes the pattern for future strategy-specific detail tables
- Avoids committing to VR schema before VR rules are stable

Cons:

- Requires domain and persistence refactoring, not just SQL changes
- Adds one-to-one joins for INFINITE reads/writes
- Introduces an asymmetry until another strategy detail table is added

### Rejected alternatives

Keep current schema:

- Lowest cost
- Leaves INFINITE-only fields in common tables

Split only the tables but keep common domain fields:

- Avoids full domain redesign
- Creates an awkward model where persistence is split but the aggregate still pretends the fields are common

## Testing Direction

Add or update tests for:

- Strategy persistence round-trip for INFINITE and non-INFINITE strategies
- Cycle position persistence round-trip for INFINITE and non-INFINITE positions
- Migration backfill correctness
- Trading/reporting behavior after INFINITE detail lookup
- Removal of `seed_resolved_by` dependencies

## Non-Goals

- No VR schema in this change
- No redesign of cycle math or trading formulas
- No new strategy capabilities beyond the INFINITE detail split
