# VR Pool Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate VR pool from total `startAmount` by using each cycle's opening position USD deposit as the pool.

**Architecture:** Preserve `StrategyCycle.startAmount` as total opening assets. Extend `CyclePositionPort` with an oldest-position lookup and use that single persisted snapshot as the pool source for VR detail and order calculations, avoiding a migration.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- `initialUsdDeposit = pool`.
- `startAmount = initialUsdDeposit + opening holdings market value` for every strategy.
- `poolLimit = initialUsdDeposit * poolLimitRate`.
- Do not add a database migration.
- Preserve unrelated FCM worktree changes.

---

### Task 1: Opening Position Pool Lookup

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/CyclePositionPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionJpaRepository.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java`
- Test: `src/test/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapterTest.java`

**Interfaces:**
- Produces: `Optional<CyclePosition> findFirstOne(UUID cycleId)` returning the oldest non-deleted cycle position.

- [ ] Add a persistence test with two positions in one cycle and assert `findFirstOne` returns the opening position.
- [ ] Run the focused test and verify RED because the port method does not exist.
- [ ] Add `findTop1ByStrategyCycleIdOrderByCreatedAtAsc` to the JPA repository and map it through `CyclePositionPersistenceAdapter`.
- [ ] Run the focused persistence test and verify GREEN.

### Task 2: Correct Registration And Detail Pool

**Files:**
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java`
- Modify: `src/main/java/com/kista/application/service/strategy/VrStrategyLifecycle.java`
- Test: `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`
- Test: `src/test/java/com/kista/application/service/strategy/VrStrategyLifecycleTest.java`

**Interfaces:**
- Consumes: `CyclePositionPort.findFirstOne(UUID)`.
- Produces: VR detail `initialUsdDeposit` and `poolLimit` based on the opening position deposit.

- [ ] Change the VR bootstrap regression expectations to `startAmount=1600`, `initialUsdDeposit=1000`, `V=600`, and `poolLimit=500`.
- [ ] Change lifecycle tests so total `startAmount=1600`, opening pool `1000`, and rate `0.50` produce `poolLimit=500`.
- [ ] Run both focused tests and verify RED.
- [ ] Restore shared registration `startAmount = normalizedInitialUsdDeposit + initialStockValue`.
- [ ] Return the saved initial position in `InitialCycleResult`; registration responses use its `usdDeposit` as VR `initialUsdDeposit` and summary pool input.
- [ ] Inject `CyclePositionPort` into `VrStrategyLifecycle`; `findSummary` loads the opening position and `buildSummary` accepts pool instead of startAmount.
- [ ] In `StrategyService.toDetail`, use the opening position deposit for VR `initialUsdDeposit`; retain latest-cycle startAmount for non-VR.
- [ ] Run `StrategyServiceTest` and `VrStrategyLifecycleTest` and verify GREEN.

### Task 3: Correct Trading And Rollover Pool Semantics

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/CycleOrderComputer.java`
- Modify: `src/main/java/com/kista/application/service/trading/CycleSnapshotCreator.java`
- Test: `src/test/java/com/kista/application/service/trading/CycleOrderComputerTest.java`
- Test: the focused existing test class covering `CycleSnapshotCreator`, or add `CycleSnapshotCreatorTest.java` if none exists.

**Interfaces:**
- Consumes: `CyclePositionPort.findFirstOne(UUID)`.
- Produces: trading pool limit based on opening USD deposit and VR rollover `startAmount` based on total opening assets.

- [ ] Add/adjust tests proving an opening pool of 1000 and rate 0.50 produces a 500 trading limit even when startAmount is 1600.
- [ ] Add a rollover snapshot test proving deposit 1000 plus 5 holdings at closing price 120 saves startAmount 1600 and preserves opening position deposit 1000.
- [ ] Run focused tests and verify RED.
- [ ] In `CycleOrderComputer`, replace `currentCycle.startAmount()` with the opening position `usdDeposit` when deriving VR poolLimit; fail fast when missing.
- [ ] In `CycleSnapshotCreator`, save VR cycle startAmount as `postBalance.usdDeposit + closingPrice * holdings`, scaled to two decimals with HALF_UP.
- [ ] Run focused tests and verify GREEN.

### Task 4: Documentation And Verification

**Files:**
- Modify: `docs/agents/constraints.md`
- Modify: `docs/superpowers/specs/2026-07-29-vr-initial-pool-basis-design.md` to mark it superseded by the corrected design.

**Interfaces:**
- Produces: one consistent documented contract for pool, V, and startAmount.

- [ ] Document the exact corrected formulas and replace statements that derive poolLimit from startAmount.
- [ ] Mark the earlier initial-pool-basis design as superseded.
- [ ] Run `./gradlew compileJava`.
- [ ] Run focused strategy, trading, and persistence tests.
- [ ] Run `./gradlew test`.
- [ ] Review `git diff` and `git status --short`, confirming FCM changes remain untouched.
