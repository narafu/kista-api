# Order Budget Small Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align SELL allocation priority with BUY allocation priority and remove narrow cleanup debt left outside the previous hardening commit.

**Architecture:** Keep `TradingOrderBudgetAllocator` as the single account-scoped allocation policy owner. Reuse the existing `strategyPriority(Strategy.Type)` helper for both BUY and SELL ordering, while keeping BUY all-or-nothing and BUY/SELL independent approval unchanged. Cleanup is limited to dead dependency removal and inline documentation; `OrderSlot` logical-leg recovery remains a separate design task.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, Gradle.

## Global Constraints

- Do not change BUY priority: `VR` -> `INFINITE` -> `PRIVACY`, then smaller BUY amount, then strategy/cycle IDs.
- SELL approval remains independent from BUY approval.
- SELL validation still uses account + trade date + ticker sellable quantity minus existing PLANNED/PLACED reservations.
- Do not change `OrderSlot` semantics in this plan; it remains `timing + direction`.
- Do not introduce Flyway migrations in this plan.
- Commit after each task if the task is executed independently.

---

## File Structure

- Modify `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
  - Add SELL priority ordering by strategy type, then smaller requested SELL quantity, then strategy/cycle IDs.
  - Add concise comments to package-private records where they clarify allocator contracts.
- Modify `src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java`
  - Add regression tests proving SELL priority uses `VR -> INFINITE -> PRIVACY`.
  - Add same-strategy smaller SELL quantity precedence.
- Modify `src/main/java/com/kista/application/service/trading/TradingService.java`
  - Remove unused `BrokerAdapterRegistry registry` field.
- Modify `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
  - Update `TradingService` constructor call after the field removal.
- Modify `docs/agents/constraints.md` and `docs/agents/workflow.md`
  - Document SELL priority once behavior is stable.

### Task 1: Align SELL Allocation Priority With BUY Policy

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java`

**Interfaces:**
- Consumes: existing `TradingOrderBudgetAllocator.allocate(List<Candidate> candidates, LocalDate tradeDate)`.
- Produces: same `Allocation` record; no signature changes.

- [ ] **Step 1: Add failing SELL strategy priority test**

Add this test to `TradingOrderBudgetAllocatorTest`:

```java
@Test
void allocate_prioritizesVrThenInfiniteThenPrivacyForLimitedSellableQuantity() {
    when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
            .thenReturn(new SellableQuantity("SOXL", 3));

    TradingOrderBudgetAllocator.Candidate vr = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            Strategy.Type.VR, sell("25.00", 2));
    TradingOrderBudgetAllocator.Candidate infinite = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Strategy.Type.INFINITE, sell("25.00", 2));
    TradingOrderBudgetAllocator.Candidate privacy = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Strategy.Type.PRIVACY, sell("25.00", 2));

    TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(privacy, infinite, vr), tradeDate);

    assertThat(result.approved()).containsExactly(vr);
    assertThat(result.rejectedSell()).containsExactly(infinite, privacy);
}
```

- [ ] **Step 2: Add failing same-strategy smaller SELL test**

Add this test to `TradingOrderBudgetAllocatorTest`:

```java
@Test
void allocate_sameStrategyTypeApprovesSmallerSellQuantityFirst() {
    when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
            .thenReturn(new SellableQuantity("SOXL", 2));

    TradingOrderBudgetAllocator.Candidate large = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Strategy.Type.INFINITE, sell("25.00", 4));
    TradingOrderBudgetAllocator.Candidate small = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Strategy.Type.INFINITE, sell("25.00", 2));

    TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(large, small), tradeDate);

    assertThat(result.approved()).containsExactly(small);
    assertThat(result.rejectedSell()).containsExactly(large);
}
```

Use deterministic UUIDs and sellable quantity `2` for the comparator fallback tests:

```java
@Test
void allocate_sameStrategyTypeAndSellQuantityApprovesLowerStrategyUuidFirst() {
    when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
            .thenReturn(new SellableQuantity("SOXL", 2));

    TradingOrderBudgetAllocator.Candidate lowerStrategyId = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Strategy.Type.INFINITE, sell("25.00", 2));
    TradingOrderBudgetAllocator.Candidate higherStrategyId = candidate(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Strategy.Type.INFINITE, sell("25.00", 2));

    TradingOrderBudgetAllocator.Allocation result = allocator.allocate(
            List.of(higherStrategyId, lowerStrategyId), tradeDate);

    assertThat(result.approved()).containsExactly(lowerStrategyId);
    assertThat(result.rejectedSell()).containsExactly(higherStrategyId);
}

@Test
void allocate_sameStrategyTypeAndSellQuantityAndStrategyUuidUsesLowerCycleUuid() {
    when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
            .thenReturn(new SellableQuantity("SOXL", 2));

    UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    TradingOrderBudgetAllocator.Candidate lowerCycleId = candidate(
            strategyId,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Strategy.Type.INFINITE, sell("25.00", 2));
    TradingOrderBudgetAllocator.Candidate higherCycleId = candidate(
            strategyId,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Strategy.Type.INFINITE, sell("25.00", 2));

    TradingOrderBudgetAllocator.Allocation result = allocator.allocate(
            List.of(higherCycleId, lowerCycleId), tradeDate);

    assertThat(result.approved()).containsExactly(lowerCycleId);
    assertThat(result.rejectedSell()).containsExactly(higherCycleId);
}
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest.allocate_prioritizesVrThenInfiniteThenPrivacyForLimitedSellableQuantity' \
  --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest.allocate_sameStrategyTypeApprovesSmallerSellQuantityFirst'
```

Expected: FAIL because current `sellPriorityComparator()` sorts by strategy UUID and cycle UUID only.

- [ ] **Step 4: Implement SELL priority comparator**

Change `sellPriorityComparator()` in `TradingOrderBudgetAllocator` to:

```java
private Comparator<SellRequest> sellPriorityComparator() {
    return Comparator
            .comparingInt((SellRequest request) -> strategyPriority(request.candidate().ctx().strategy().type()))
            .thenComparingInt(request -> sellTotal(request.orders()))
            .thenComparing(request -> request.candidate().ctx().strategy().id())
            .thenComparing(request -> request.candidate().ctx().currentCycle().id());
}
```

- [ ] **Step 5: Run allocator tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest'
```

Expected: PASS.

- [ ] **Step 6: Commit task 1**

Run:

```bash
git add src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java \
  src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java
git commit -m "fix(trading): align sell allocation priority"
```

### Task 2: Remove Narrow Cleanup Debt and Sync Docs

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/workflow.md`

**Interfaces:**
- Consumes: Task 1 SELL priority behavior.
- Produces: no public interface changes; only constructor arity changes for `TradingService` tests because one unused final field is removed.

- [ ] **Step 1: Remove unused `TradingService.registry` field**

Delete this field from `TradingService`:

```java
private final BrokerAdapterRegistry registry;             // live 잔고 검사 전용 (주문 저장 직전 유효성 확인)
```

Remove the now-unused import if present:

```java
import com.kista.application.service.broker.BrokerAdapterRegistry;
```

Update the `new TradingService(...)` call in `TradingServiceTest.setUp()` by removing the `tradingRegistry` argument:

```java
service = new TradingService(
        marketCalendarPort, notifyPort, userNotificationPort,
        orderPort, privacyTradePort, strategyCyclePort,
        balanceLoader, orderComputer, orderPlanner,
        priceFetcher, orderExecutor, reporter,
        marketEventNotifier, budgetAllocator, priceCapper, cycleStrategies);
```

- [ ] **Step 2: Add allocator contract comments**

Add concise comments above the allocator records:

```java
// Allocation input for one strategy cycle; orders may include BUY, SELL, or both.
record Candidate(BatchContext ctx, List<Order> orders) {}

// Approved contains only approved directions per candidate; rejected lists are direction-specific.
record Allocation(List<Candidate> approved, List<Candidate> rejectedBuy, List<Candidate> rejectedSell) {}
```

- [ ] **Step 3: Sync docs**

In `docs/agents/constraints.md`, update the scheduler order budget bullet to state:

```markdown
- BUY와 SELL 모두 계좌별 `VR → INFINITE → PRIVACY` 우선순위를 따른다. BUY는 같은 전략 타입에서 총 매수금액이 작은 사이클 우선, SELL은 같은 전략 타입에서 필요 매도수량이 작은 사이클 우선이다.
```

In `docs/agents/workflow.md`, update the account allocation bullet to include the same SELL tie-breaker sentence.

- [ ] **Step 4: Run compile and focused tests**

Run:

```bash
./gradlew compileJava
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest' \
  --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest'
```

Expected: PASS.

- [ ] **Step 5: Run full verification**

Run:

```bash
./gradlew test --rerun-tasks
./gradlew test --tests 'com.kista.architecture.*' --rerun-tasks
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit task 2**

Run:

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java \
  src/test/java/com/kista/application/service/trading/TradingServiceTest.java \
  src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java \
  docs/agents/constraints.md docs/agents/workflow.md
git commit -m "chore(trading): clean up order budget allocator"
```

---

## Out of Scope

- `OrderSlot` logical-leg identity for partial rerun recovery.
- Skipping `orderComputer.compute()` when all target slots are already occupied.
- Moving `strategyPriority` into `CycleOrderStrategy` capability metadata.
- Adding `orders` table indexes.
- Flyway migrations.

## Self-Review

- Spec coverage: SELL priority and cleanup items are covered by Task 1 and Task 2.
- Placeholder scan: no TBD/TODO placeholders remain.
- Type consistency: all referenced methods and records exist in the current codebase.
