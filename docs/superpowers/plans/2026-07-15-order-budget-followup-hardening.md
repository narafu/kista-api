# Order Budget Follow-up Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the reporting, failure-isolation, post-allocation price-cap, manual SELL reservation, and order-sequence regressions found after account budget allocation was introduced.

**Architecture:** Keep `TradingOrderBudgetAllocator` responsible for deterministic account-scoped allocation, but make scheduler orchestration consume an explicit save result instead of assuming every computed candidate may proceed. Prepare price-capped BUY templates before allocation, isolate account allocation and per-cycle side effects with the existing `runSafely` boundary, and retain placement-time capping only as a compatibility path for orders persisted before the scheduler run.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, Spring Data JPA, Gradle.

## Global Constraints

- Preserve strategy formulas in `InfiniteStrategy`, `PrivacyStrategy`, and `VrStrategy`.
- Preserve BUY priority `VR` -> `INFINITE` -> `PRIVACY`, then smaller total BUY amount, then strategy/cycle IDs.
- Preserve cycle-level all-or-nothing approval for each BUY side.
- Preserve independent BUY and SELL approval; rejection of one side must not discard the approved opposite side.
- Existing PLANNED/PLACED orders must remain eligible for placement and reporting even when strategy computation returns empty.
- A newly computed cycle with no existing order and no successfully saved approved order must not reach placement or reporting.
- One account's balance lookup, sellable lookup, persistence, or notification failure must not stop other accounts.
- SELL validation must include existing PLANNED/PLACED reservations for the same account, trade date, and ticker.
- Close scheduler creates only missing AT_CLOSE slots; open scheduler may create all slots and immediately places only AT_OPEN.
- No Flyway migration is required.

---

## File Structure

- Modify `TradingService.java`: return an explicit planning outcome, isolate allocation/save/notification failures, and filter reportable states.
- Modify `TradingOrderBudgetAllocator.java`: retain source order when merging independently approved BUY/SELL sides.
- Modify `BuyOrderPriceCapper.java`: expose a persistence-free order preparation method used before allocation.
- Modify `ManualTradingService.java`: include existing SELL reservations in manual-order validation.
- Modify focused trading and persistence tests; update `docs/agents/` after behavior is stable.

### Task 1: Prevent Rejected New Cycles From Reaching Reporting

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Produce `private record SaveAllocationResult(Set<BatchContext> savedContexts, Set<BatchContext> failedContexts) {}`.
- Change `saveAllocatedOrders(...)` to return `SaveAllocationResult`.
- `planAll(...)` returns states only when the context had existing orders or at least one approved order was saved.

- [ ] **Step 1: Write a failing regression test**

Add `executeBatch_bothSidesRejectedWithoutExistingOrders_skipsPlacementAndReporting()` with a new INFINITE candidate containing BUY and SELL, insufficient BUY cash, and insufficient sellable quantity. Verify:

```java
verify(orderPort, never()).saveAll(anyList());
verify(orderPort, never()).findPlannedByCycleAndDate(eq(cycle.id()), any());
verify(cycleHistoryPort, never()).save(any());
verify(userNotificationPort).notifyInsufficientBalance(user, account, Strategy.Type.INFINITE, Ticker.SOXL);
```

Also retain the existing `computeEmptyWithExistingOrders_preservesPlacement` test as the opposite branch.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest.executeBatch_bothSidesRejectedWithoutExistingOrders_skipsPlacementAndReporting'
```

Expected: FAIL because `planAll()` currently returns every computed state.

- [ ] **Step 3: Track existing-order eligibility explicitly**

Extend `CyclePlanCandidate`:

```java
private record CyclePlanCandidate(
        CycleState state,
        List<Order> creatableOrders,
        boolean hasExistingOrders
) {}
```

Set `hasExistingOrders` from `existingOrders.isEmpty()` inside `collectCycleCandidate(...)`.

- [ ] **Step 4: Return saved contexts and filter states**

Make `saveAllocatedOrders(...)` add a context to `savedContexts` only after `orderPlanner.savePlannedOrders(...)` returns successfully. Update `planAll(...)`:

```java
SaveAllocationResult result = saveAllocatedOrders(candidates, today);
return candidates.stream()
        .filter(candidate -> candidate.hasExistingOrders()
                || result.savedContexts().contains(candidate.state().ctx()))
        .map(CyclePlanCandidate::state)
        .toList();
```

- [ ] **Step 5: Run `TradingServiceTest`**

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "fix(trading): skip reporting rejected order cycles"
```

### Task 2: Restore Account and Cycle Failure Isolation

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Allocation input is grouped by `account.id()` before calling `budgetAllocator.allocate(...)`.
- Allocation failures skip only that account.
- Save and insufficient-balance notification failures are isolated per context.

- [ ] **Step 1: Add failing account-isolation tests**

Add two-account tests where account A throws from `LiveBalancePort.getLiveBalance(...)` and account B has sufficient cash. Add a second test where account A's `orderPort.saveAll(...)` throws and account B still saves. Verify account B reaches save and placement while `notifyPort.notifyError(...)` receives account A's exception.

- [ ] **Step 2: Verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest.*failure*doesNotStopOtherAccount'
```

Expected: FAIL because allocation/save currently occurs outside `runSafely`.

- [ ] **Step 3: Group and isolate allocation**

In `saveAllocatedOrders(...)`, group candidates with `LinkedHashMap<UUID, List<Candidate>>`. For each account group, call:

```java
runSafely("계좌 주문 예산 배정", first.ctx(),
        () -> budgetAllocator.allocate(accountCandidates, tradeDate))
        .ifPresent(allocations::add);
```

Do not catch `InterruptedException`; preserve `runSafely` interruption propagation.

- [ ] **Step 4: Isolate save and notification**

Wrap each approved candidate save and each rejected context notification in separate `runSafely` calls. Record `savedContexts` only after successful save. Deduplicate rejected contexts before notification.

- [ ] **Step 5: Run focused and full service tests**

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: PASS, including both account-isolation tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "fix(trading): isolate account allocation failures"
```

### Task 3: Apply BUY Price Capping Before Budget Allocation

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/BuyOrderPriceCapper.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Produce `List<Order> prepareForAllocation(List<Order> orders, BigDecimal currentPrice, InfinitePosition position, CycleOrderStrategy.PriceCapMode mode, LocalDate tradeDate)`.
- The method performs no persistence calls and preserves SELL templates unchanged.
- Existing `capIfNeeded(...)` and `capPrivacyIfNeeded(...)` remain for previously persisted orders.

- [ ] **Step 1: Add pure preparation tests**

Cover:

```java
prepareForAllocation_infiniteCap_returnsCappedBuysAndCorrectionsWithoutPersistence();
prepareForAllocation_privacyCap_changesOnlyExceedingBuyPrices();
prepareForAllocation_preservesSellOrdersAndOriginalRelativeOrder();
prepareForAllocation_noCapReturnsOriginalOrders();
```

Verify no `OrderPort` or `TradingOrderPlanner` interaction from the pure method.

- [ ] **Step 2: Run tests and verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.trading.BuyOrderPriceCapperTest.prepareForAllocation*'
```

Expected: compile failure because the method does not exist.

- [ ] **Step 3: Implement pure preparation**

Compute `cap = currentPrice * 1.10` with existing scale/rounding. For `INFINITE_POSITION`, call `infiniteStrategy.buildCappedBuyOrders(...)` only when a BUY exceeds cap, then replace BUY entries while retaining SELL entries. For `PRIVACY_SIMPLE`, replace only exceeding BUY prices. For `NONE`, null price, or null INFINITE position, return the input unchanged.

- [ ] **Step 4: Prepare candidate orders before allocation**

Inject `BuyOrderPriceCapper` into `TradingService`. In `collectCycleCandidate(...)`, prepare `planOpt.get().orders()` before `filterCreatableOrders(...)`, using `cycleOrderStrategies.of(strategy.type()).priceCapMode()`. Allocator totals must therefore include correction orders and final capped quantities.

- [ ] **Step 5: Add scheduler budget regression**

Create an INFINITE plan whose original BUY total fits but whose prepared capped/correction BUY total exceeds remaining cash. Verify no BUY is saved, while an independently valid SELL is still saved.

- [ ] **Step 6: Run capper and service tests**

```bash
./gradlew test --tests 'com.kista.application.service.trading.BuyOrderPriceCapperTest' --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kista/application/service/trading/BuyOrderPriceCapper.java src/main/java/com/kista/application/service/trading/TradingService.java src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "fix(trading): allocate capped buy totals"
```

### Task 4: Include Reserved SELL Quantity in Manual Trading

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/ManualTradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/ManualTradingServiceTest.java`

**Interfaces:**
- `checkSellableOrThrow(...)` consumes `LocalDate tradeDate`.
- Validation is `reservedSell + newSell <= brokerSellable`.

- [ ] **Step 1: Write the failing test**

Stub broker sellable quantity to `5`, existing reserved SELL to `3`, and the manual plan SELL to `3`. Verify `ManualTradingException("보유 수량이 부족합니다")` and no save.

- [ ] **Step 2: Verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.trading.ManualTradingServiceTest.execute_existingReservedSellExceedsAvailable_rejects'
```

Expected: FAIL because only the new SELL total is checked.

- [ ] **Step 3: Add reservation total**

```java
int reserved = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
        account.id(), tradeDate, strategy.ticker());
if (reserved + newSellTotal > sellableQty) {
    throw new ManualTradingException("보유 수량이 부족합니다");
}
```

Pass `today` from `execute(...)` and log reserved/new/sellable separately.

- [ ] **Step 4: Run manual trading tests**

```bash
./gradlew test --tests 'com.kista.application.service.trading.ManualTradingServiceTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kista/application/service/trading/ManualTradingService.java src/test/java/com/kista/application/service/trading/ManualTradingServiceTest.java
git commit -m "fix(trading): reserve sell quantity for manual orders"
```

### Task 5: Preserve Strategy Order Sequence and Strengthen Isolation Tests

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java`
- Modify: `src/test/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapterDbTest.java`

**Interfaces:**
- `mergeApproved(...)` reconstructs each candidate from the original candidate order list.
- Approval filtering removes rejected directions but does not reorder approved orders.

- [ ] **Step 1: Add a failing mixed-order test**

Create one candidate ordered `[BUY, SELL, BUY]` with both sides approved. Assert `result.approved().getFirst().orders()` contains exactly the same three instances in that order. Add partial approval assertions that a rejected BUY yields only SELL without moving it relative to other retained orders.

- [ ] **Step 2: Verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest.allocate_preservesOriginalOrderSequence'
```

Expected: FAIL because current merge concatenates SELL before BUY.

- [ ] **Step 3: Reconstruct from source candidates**

Pass original candidates into `mergeApproved(...)`. Build approved `(BatchContext, OrderDirection)` keys, stream each source candidate's original `orders()`, retain only approved directions, and emit non-empty candidates in deterministic source/priority order. Do not concatenate independently allocated lists.

- [ ] **Step 4: Strengthen persistence DB isolation data**

In `OrderPersistenceAdapterDbTest`, insert SELL rows for another account, another ticker, and another trade date. Assert only matching account/date/ticker PLANNED/PLACED rows contribute; CANCELLED/FILLED rows do not.

- [ ] **Step 5: Run allocator and persistence tests**

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest' --tests 'com.kista.adapter.out.persistence.trade.OrderPersistenceAdapterDbTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java src/test/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapterDbTest.java
git commit -m "fix(trading): preserve allocated order sequence"
```

### Task 6: Documentation and Final Verification

**Files:**
- Modify: `docs/agents/workflow.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/testing.md`
- Modify: `docs/superpowers/plans/2026-07-15-order-budget-followup-hardening.md`

- [ ] **Step 1: Update behavior documentation**

Document that rejected new cycles do not report, allocation failures are account-isolated, scheduler BUY totals use prepared capped orders, and manual SELL validation includes existing reservations.

- [ ] **Step 2: Run focused verification**

```bash
./gradlew test --tests 'com.kista.application.service.trading.*Test' --tests 'com.kista.adapter.out.persistence.trade.OrderPersistenceAdapterTest' --tests 'com.kista.adapter.out.persistence.trade.OrderPersistenceAdapterDbTest'
```

Expected: PASS.

- [ ] **Step 3: Run project verification**

```bash
./gradlew compileJava
./gradlew test --rerun-tasks
./gradlew test --tests 'com.kista.architecture.*'
git diff --check
```

Expected: all commands exit 0. Integration tests are not required unless persistence DB tests indicate an environment-specific failure.

- [ ] **Step 4: Review the final diff**

Confirm no strategy formula, DB schema, scheduler timing, or notification contract changed. Confirm each review finding has a named regression test.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/agents/workflow.md docs/agents/constraints.md docs/agents/testing.md docs/superpowers/plans/2026-07-15-order-budget-followup-hardening.md
git commit -m "docs: document order budget hardening"
```

## Self-Review

- Critical reporting contamination is covered by Task 1 and its inverse existing-order regression.
- Account-wide failure propagation is covered by Task 2 for lookup and save failures.
- Post-allocation INFINITE correction growth is covered by Task 3 using final prepared BUY totals.
- Manual SELL over-reservation is covered by Task 4.
- Order reordering and weak DB isolation fixtures are covered by Task 5.
- Every task names its production methods and regression tests explicitly.
- Existing placement-time capping remains intentionally for previously persisted orders; pre-allocation preparation is the scheduler path for newly created orders.
