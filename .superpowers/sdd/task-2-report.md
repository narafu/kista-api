# Task 2 Report

## Summary
- Added `V17__split_infinite_strategy_details.sql` to move INFINITE-only persistence fields into dedicated detail tables.
- Added `strategy_infinite` / `cycle_position_infinite` JPA entities and repositories.
- Added `StrategyInfiniteDetailPort` / `CyclePositionInfiniteDetailPort`.
- Removed `division_count` and `is_reverse_mode` from common JPA entities so the new detail tables are the storage source of truth.
- Added persistence tests for the new common/detail save flow.

## Files Changed
- `src/main/resources/db/migration/V17__split_infinite_strategy_details.sql`
- `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyInfiniteEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyInfiniteJpaRepository.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionInfiniteEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionInfiniteJpaRepository.java`
- `src/main/java/com/kista/domain/port/out/StrategyInfiniteDetailPort.java`
- `src/main/java/com/kista/domain/port/out/CyclePositionInfiniteDetailPort.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyPersistenceAdapter.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java`
- `src/test/java/com/kista/adapter/out/persistence/strategy/StrategyPersistenceAdapterTest.java`
- `src/test/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapterTest.java`

## TDD Evidence
1. Added the new persistence tests first.
2. Ran:
   - `./gradlew test --tests 'com.kista.adapter.out.persistence.strategy.StrategyPersistenceAdapterTest' --tests 'com.kista.adapter.out.persistence.strategy.CyclePositionPersistenceAdapterTest'`
3. Initial result:
   - `compileTestJava` failed because `StrategyInfiniteJpaRepository`, `CyclePositionInfiniteJpaRepository`, and the new detail persistence adapters did not exist yet.
4. Implemented the missing detail persistence pieces.

## Verification
- Focused test command:
  - `./gradlew test --tests 'com.kista.adapter.out.persistence.strategy.StrategyPersistenceAdapterTest' --tests 'com.kista.adapter.out.persistence.strategy.CyclePositionPersistenceAdapterTest'`
  - Result: failed in `compileTestJava`, but due to pre-existing branch-wide test source mismatches outside this task scope.
  - Unrelated failing files included:
    - `src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java`
    - `src/test/java/com/kista/adapter/in/schedule/TradingCloseSchedulerTest.java`
    - `src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java`
    - `src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java`
    - `src/test/java/com/kista/application/service/trading/OrderCancelServiceTest.java`
    - `src/test/java/com/kista/application/service/trading/ManualTradingServiceTest.java`
  - These failures are old constructor/signature usages for `Strategy`, `StrategyCycle`, `CyclePosition`, and `StrategyDetail`, not failures from the Task 2 persistence files.
- Compile check:
  - `./gradlew compileJava`
  - Result: `BUILD SUCCESSFUL`

## Concerns
- The required focused test command cannot go green until the unrelated test sources above are reconciled with the already-changed domain model on this branch.

## Task 2 Fix Follow-up
- Replaced the two Mockito-only persistence tests with `DataJpaTestBase` coverage that persists real `strategy`, `strategy_infinite`, `cycle_position`, and `cycle_position_infinite` rows through the actual adapters.
- Kept the compile-fix scope narrow to the requested files by updating outdated test constructors for `Strategy`, `StrategyCycle`, `CyclePosition`, and `StrategyDetail`.

## Follow-up Verification
- Ran exactly:
  - `./gradlew test --tests 'com.kista.adapter.out.persistence.strategy.StrategyPersistenceAdapterTest' --tests 'com.kista.adapter.out.persistence.strategy.CyclePositionPersistenceAdapterTest'`
- Result on 2026-06-30:
  - `compileTestJava` now fails in exactly one remaining file outside the allowed edit list:
    - `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`
  - Exact reason:
    - `StrategyDetail` constructor call is still using the old 4-argument form and now requires `Strategy, BigDecimal, Integer, boolean, Double`
- The previously listed blockers in:
  - `TradingOpenSchedulerTest`
  - `TradingCloseSchedulerTest`
  - `TradingCycleControllerTest`
  - `TradingPreviewServiceTest`
  - `OrderCancelServiceTest`
  - `ManualTradingServiceTest`
  were updated and no longer appear in the focused command failure output.

## Remaining Concern
- The new DataJpa persistence tests could not be executed end-to-end because Gradle compiles the full test source set first, and the out-of-scope `StrategyServiceTest` constructor mismatch still blocks `compileTestJava`.
