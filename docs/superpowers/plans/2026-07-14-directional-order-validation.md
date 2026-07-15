# Directional Order Validation and Account Budget Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split scheduled order validation by BUY/SELL conditions for every strategy, and allocate limited BUY cash per account by deterministic strategy priority.

**Architecture:** Strategy calculation remains unchanged: each strategy still returns a full `OrderPlan`. Scheduler flow changes from "compute one cycle → validate all-or-nothing → save" to "compute all cycle candidates → filter by missing timing/direction slot → validate SELL by sellable quantity → allocate BUY by account budget priority → save approved orders." Existing PLANNED/PLACED orders block only the same `timing + direction` slot.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Gradle.

## Global Constraints

- Do not change domain strategy formulas in `InfiniteStrategy`, `PrivacyStrategy`, or `VrStrategy`.
- Do not add DB migrations; `orders.timing`, `orders.direction`, and `orders.status` already contain the needed dimensions.
- Apply BUY/SELL split to all strategies.
- Allocate BUY budgets per account, not globally.
- BUY priority order is `VR` first, `INFINITE` second, `PRIVACY` last.
- Within the same strategy type, approve the smaller total BUY requirement first.
- Tie-break deterministically by `strategy.id()` then `currentCycle.id()`.
- BUY approval is cycle-level all-or-nothing for the candidate BUY slot set. Do not approve individual BUY orders inside one cycle.
- Treat `AccountBalance.usdDeposit` as account-level integrated orderable cash; use the first priority-sorted candidate only as the ticker needed to query that account balance.
- SELL approval is independent from BUY approval and uses sellable quantity.
- `TradingOpenScheduler` may create all timing slots, but only AT_OPEN slots are immediately placed.
- `TradingCloseScheduler` may create missing AT_CLOSE slots only; it must not recreate missed AT_OPEN orders after market open.
- Existing PLANNED/PLACED orders prevent duplicate creation only for the same `timing + direction` slot.
- Keep existing user insufficient-balance notification behavior; do not introduce a new notification contract in this change.

---

## File Structure

- Create `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
  - Own account-level BUY budget allocation and SELL sellable validation.
  - Keep priority policy isolated from scheduler orchestration.
- Modify `src/main/java/com/kista/application/service/trading/TradingService.java`
  - Add slot-aware candidate collection.
  - Replace cycle-level skip in `planAndSaveOrders()` and `planSaveAndPlaceSells()`.
  - Batch-save approved candidates after account-level allocation.
- Modify `src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java`
  - Unit-test strategy priority, smaller-first tie-break, all-or-nothing BUY approval, and SELL independence.
- Modify `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
  - Add `SellableQuantityPort` mock wiring.
  - Replace all-or-nothing insufficient balance expectations.
  - Add regression tests for partial saves and close-scheduler recovery.
- Modify `src/main/java/com/kista/domain/port/out/OrderPort.java`
  - Add account/date/ticker SELL reservation aggregation for PLANNED/PLACED orders.
- Modify `src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java`
  - Add the native `COALESCE(SUM(quantity))` SELL reservation query.
- Modify `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
  - Expose the reservation aggregation through the domain port with KST/UTC conversion.
- Modify `docs/agents/workflow.md`
  - Document slot-based scheduler behavior and account-level BUY budget allocation.
- Modify `docs/agents/architecture.md`
  - Add `TradingOrderBudgetAllocator` to the application trading helper SSOT.
- No changes expected in:
  - `src/main/java/com/kista/domain/strategy/*Strategy.java`
  - `src/main/java/com/kista/domain/model/order/Order.java`
  - Flyway migrations; existing order columns are sufficient.

---

### Task 1: Add Budget Allocator Unit Tests

**Files:**
- Create: `src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java`
- Create later in Task 2: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`

**Interfaces:**
- Produces tests for `TradingOrderBudgetAllocator.allocate(List<Candidate>, LocalDate)`.
- Expected public package-private types:
  - `record Candidate(BatchContext ctx, List<Order> orders)`
  - `record Allocation(List<Candidate> approved, List<Candidate> rejectedBuy, List<Candidate> rejectedSell)`

- [ ] **Step 1: Write failing allocator tests**

Create `TradingOrderBudgetAllocatorTest`:

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingOrderBudgetAllocatorTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock LiveBalancePort liveBalancePort;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock OrderPort orderPort;

    TradingOrderBudgetAllocator allocator;

    Account account;
    User user;
    LocalDate tradeDate;

    @BeforeEach
    void setUp() {
        account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
        user = DomainFixtures.activeUserWithTelegram(account.userId());
        tradeDate = LocalDate.of(2026, 7, 15);
        allocator = new TradingOrderBudgetAllocator(registry, orderPort);
        lenient().when(registry.require(any(Account.class), eq(LiveBalancePort.class))).thenReturn(liveBalancePort);
        lenient().when(registry.require(any(Account.class), eq(SellableQuantityPort.class))).thenReturn(sellableQuantityPort);
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(eq(account.id()), eq(tradeDate))).thenReturn(BigDecimal.ZERO);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 100));
    }

    @Test
    void allocate_prioritizesVrThenInfiniteThenPrivacyWithLimitedCash() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("3000.00")));

        TradingOrderBudgetAllocator.Candidate vr = candidate(Strategy.Type.VR, "1500.00");
        TradingOrderBudgetAllocator.Candidate infinite = candidate(Strategy.Type.INFINITE, "1000.00");
        TradingOrderBudgetAllocator.Candidate privacy = candidate(Strategy.Type.PRIVACY, "1000.00");

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(privacy, infinite, vr), tradeDate);

        assertThat(result.approved()).containsExactly(vr, infinite);
        assertThat(result.rejectedBuy()).containsExactly(privacy);
    }

    @Test
    void allocate_sameStrategyTypeApprovesSmallerBuyTotalFirst() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        TradingOrderBudgetAllocator.Candidate large = candidate(Strategy.Type.INFINITE, "1200.00");
        TradingOrderBudgetAllocator.Candidate small = candidate(Strategy.Type.INFINITE, "800.00");

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(large, small), tradeDate);

        assertThat(result.approved()).containsExactly(small);
        assertThat(result.rejectedBuy()).containsExactly(large);
    }

    @Test
    void allocate_buyIsAllOrNothingWithinCycle() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        TradingOrderBudgetAllocator.Candidate candidate = candidate(Strategy.Type.INFINITE,
                buy("700.00"), buy("400.00"));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedBuy()).containsExactly(candidate);
    }

    @Test
    void allocate_sellDoesNotConsumeBuyBudget() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 10));

        TradingOrderBudgetAllocator.Candidate sellOnly = candidate(Strategy.Type.PRIVACY,
                sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(sellOnly), tradeDate);

        assertThat(result.approved()).containsExactly(sellOnly);
        assertThat(result.rejectedBuy()).isEmpty();
        assertThat(result.rejectedSell()).isEmpty();
    }

    @Test
    void allocate_rejectsSellWhenSellableQuantityIsInsufficient() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 2));

        TradingOrderBudgetAllocator.Candidate tooMuchSell = candidate(Strategy.Type.VR,
                sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(tooMuchSell), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedSell()).containsExactly(tooMuchSell);
    }

    private TradingOrderBudgetAllocator.Candidate candidate(Strategy.Type type, String buyAmount) {
        return candidate(type, buy(buyAmount));
    }

    private TradingOrderBudgetAllocator.Candidate candidate(Strategy.Type type, Order... orders) {
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), type,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, tradeDate, null, null, null);
        return new TradingOrderBudgetAllocator.Candidate(
                new BatchContext(strategy, cycle, account, user),
                List.of(orders));
    }

    private Order buy(String amount) {
        return new Order(null, null, null, tradeDate, Strategy.Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal(amount),
                Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order sell(String price, int quantity) {
        return new Order(null, null, null, tradeDate, Strategy.Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, quantity, new BigDecimal(price),
                Order.OrderStatus.PLANNED, null, null, null);
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest'
```

Expected: compile fails because `TradingOrderBudgetAllocator` does not exist yet.

---

### Task 2: Implement Account-Level Budget Allocator

**Files:**
- Create: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`

**Interfaces:**
- Produces package-private component:
  - `Allocation allocate(List<Candidate> candidates, LocalDate tradeDate)`
  - `Candidate.approvedOrders()` is represented by a new `Candidate` with filtered valid orders.

- [ ] **Step 1: Create allocator implementation**

Create `TradingOrderBudgetAllocator.java`:

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;

@Slf4j
@Component
@RequiredArgsConstructor
class TradingOrderBudgetAllocator {

    private final BrokerAdapterRegistry registry;
    private final OrderPort orderPort;

    record Candidate(BatchContext ctx, List<Order> orders) {}
    record Allocation(List<Candidate> approved, List<Candidate> rejectedBuy, List<Candidate> rejectedSell) {}

    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());

        List<Candidate> sellApproved = new ArrayList<>();
        List<Candidate> buyCandidates = new ArrayList<>();
        List<Candidate> rejectedSell = new ArrayList<>();

        for (Candidate candidate : candidates) {
            List<Order> sells = candidate.orders().stream().filter(o -> o.direction() == SELL).toList();
            List<Order> buys = candidate.orders().stream().filter(o -> o.direction() == BUY).toList();

            if (!sells.isEmpty()) {
                int requiredSellQuantity = sells.stream().mapToInt(Order::quantity).sum();
                int sellableQuantity = sellableQuantityFor(candidate.ctx());
                if (requiredSellQuantity <= sellableQuantity) {
                    sellApproved.add(new Candidate(candidate.ctx(), sells));
                    log.info("[{}] SELL 승인: strategy={}, required={}, sellable={}",
                            candidate.ctx().account().nickname(), candidate.ctx().strategy().type(),
                            requiredSellQuantity, sellableQuantity);
                } else {
                    rejectedSell.add(new Candidate(candidate.ctx(), sells));
                    log.warn("[{}] SELL 판매가능수량 부족으로 제외: strategy={}, required={}, sellable={}",
                            candidate.ctx().account().nickname(), candidate.ctx().strategy().type(),
                            requiredSellQuantity, sellableQuantity);
                }
            }
            if (!buys.isEmpty()) {
                buyCandidates.add(new Candidate(candidate.ctx(), buys));
            }
        }

        BuyAllocation buyAllocation = allocateBuysByAccount(buyCandidates, tradeDate);
        List<Candidate> approved = mergeApproved(sellApproved, buyAllocation.approved());
        return new Allocation(approved, buyAllocation.rejected(), rejectedSell);
    }

    private BuyAllocation allocateBuysByAccount(List<Candidate> buyCandidates, LocalDate tradeDate) {
        Map<UUID, List<Candidate>> byAccount = new LinkedHashMap<>();
        for (Candidate c : buyCandidates) {
            byAccount.computeIfAbsent(c.ctx().account().id(), ignored -> new ArrayList<>()).add(c);
        }

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        for (List<Candidate> accountCandidates : byAccount.values()) {
            List<Candidate> sorted = accountCandidates.stream()
                    .sorted(buyPriorityComparator())
                    .toList();
            Account account = sorted.getFirst().ctx().account();
            Strategy budgetProbeStrategy = sorted.getFirst().ctx().strategy();
            AccountBalance live = registry.require(account, LiveBalancePort.class)
                    .getLiveBalance(account, budgetProbeStrategy.ticker());
            BigDecimal reservedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), tradeDate);
            BigDecimal allocatedInBatch = BigDecimal.ZERO;
            for (Candidate c : sorted) {
                BigDecimal required = buyTotal(c.orders());
                BigDecimal alreadyCommitted = reservedBuy.add(allocatedInBatch);
                if (live.hasSufficientDepositFor(c.orders(), alreadyCommitted)) {
                    approved.add(c);
                    allocatedInBatch = allocatedInBatch.add(required);
                    BigDecimal remaining = live.usdDeposit().subtract(reservedBuy).subtract(allocatedInBatch);
                    log.info("[{}] BUY 예산 배정: strategy={}, required={}, remaining={}",
                            account.nickname(), c.ctx().strategy().type(), required, remaining);
                } else {
                    BigDecimal remaining = live.usdDeposit().subtract(reservedBuy).subtract(allocatedInBatch);
                    rejected.add(c);
                    log.warn("[{}] BUY 예산 부족으로 제외: strategy={}, required={}, remaining={}",
                            account.nickname(), c.ctx().strategy().type(), required, remaining);
                }
            }
        }
        return new BuyAllocation(approved, rejected);
    }

    private int sellableQuantityFor(BatchContext ctx) {
        return registry.require(ctx.account(), SellableQuantityPort.class)
                .getSellableQuantity(ctx.strategy().ticker(), ctx.account())
                .quantity();
    }

    private Comparator<Candidate> buyPriorityComparator() {
        return Comparator
                .comparingInt((Candidate c) -> strategyPriority(c.ctx().strategy().type()))
                .thenComparing(c -> buyTotal(c.orders()))
                .thenComparing(c -> c.ctx().strategy().id())
                .thenComparing(c -> c.ctx().currentCycle().id());
    }

    private int strategyPriority(Strategy.Type type) {
        return switch (type) {
            case VR -> 0;
            case INFINITE -> 1;
            case PRIVACY -> 2;
        };
    }

    private BigDecimal buyTotal(List<Order> orders) {
        return orders.stream()
                .filter(o -> o.direction() == BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Candidate> mergeApproved(List<Candidate> sellApproved, List<Candidate> buyApproved) {
        Map<BatchContext, List<Order>> byContext = new LinkedHashMap<>();
        Stream.concat(sellApproved.stream(), buyApproved.stream())
                .forEach(c -> byContext.computeIfAbsent(c.ctx(), ignored -> new ArrayList<>()).addAll(c.orders()));
        return byContext.entrySet().stream()
                .map(e -> new Candidate(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    private record BuyAllocation(List<Candidate> approved, List<Candidate> rejected) {}
}
```

- [ ] **Step 2: Run allocator tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest'
```

Expected: PASS.

---

### Task 3: Add Slot-Aware Candidate Collection to TradingService

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`

**Interfaces:**
- Consumes `TradingOrderBudgetAllocator`.
- Produces private records:
  - `OrderSlot`
  - `CyclePlanCandidate`
- Produces helper:
  - `collectCycleCandidate(...)`, returning a candidate or `null` so `runSafely` remains `Optional<CyclePlanCandidate>` rather than nesting optionals.
  - `filterCreatableOrders(...)`

- [ ] **Step 1: Inject allocator**

Add field:

```java
private final TradingOrderBudgetAllocator budgetAllocator;
```

Update `TradingServiceTest.setUp()` later to construct it.

- [ ] **Step 2: Add slot records**

Inside `TradingService`, near existing private records, add:

```java
private record OrderSlot(Order.OrderTiming timing, Order.OrderDirection direction) {
    static OrderSlot of(Order order) {
        return new OrderSlot(order.timing(), order.direction());
    }
}

private record CyclePlanCandidate(
        CycleState state,
        List<Order> creatableOrders
) {}
```

- [ ] **Step 3: Add creatable-order filtering**

Add:

```java
private List<Order> filterCreatableOrders(List<Order> plannedTemplates,
                                          List<Order> existingOrders,
                                          Set<Order.OrderTiming> creatableTimings) {
    Set<OrderSlot> existingSlots = existingOrders.stream()
            .map(OrderSlot::of)
            .collect(Collectors.toSet());
    return plannedTemplates.stream()
            .filter(o -> creatableTimings.contains(o.timing()))
            .filter(o -> !existingSlots.contains(OrderSlot.of(o)))
            .toList();
}
```

- [ ] **Step 4: Add shared candidate collector**

Add:

```java
private CyclePlanCandidate collectCycleCandidate(BatchContext ctx,
        Map<Ticker, PriceSnapshot> startPriceSnapshots,
        PrivacyTradeBase privacyBase,
        LocalDate tradeDate,
        Set<Order.OrderTiming> creatableTimings) {
    Strategy strategy = ctx.strategy();
    Account account = ctx.account();
    AccountBalance balance = loadBalance(strategy, account);
    PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
    BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
    BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);

    List<Order> existingOrders = orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), tradeDate);
    Optional<CycleOrderStrategy.OrderPlan> planOpt = orderComputer.compute(
            balance, strategy, prevClosePrice, tradeDate, ctx.currentCycle(), privacyBase, account.nickname(), price);
    if (planOpt.isEmpty()) {
        log.info("[{}] 전략 계산 skip (PRIVACY 기준 미수신 등)", account.nickname());
        if (existingOrders.isEmpty()) return null;
        CycleState existingState = buildCycleStateFromExistingOrders(
                ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size());
        return new CyclePlanCandidate(existingState, List.of());
    }

    List<Order> creatable = filterCreatableOrders(planOpt.get().orders(), existingOrders, creatableTimings);
    PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
    CycleState state = new CycleState(ctx, balance, planOpt.get().position(), price, privacyBaseForState);
    return new CyclePlanCandidate(state, creatable);
}
```

- [ ] **Step 5: Remove old all-or-nothing helpers**

Delete these methods after no callers remain:

```java
private Optional<CycleOrderStrategy.OrderPlan> computeValidateAndSave(...)
private boolean notifyIfInsufficientLiveBalance(...)
```

Keep `buildCycleStateFromExistingOrders(...)`. It is required when strategy calculation is skipped but existing PLANNED/PLACED orders still need placement, execution reporting, and notification processing.

Do not delete `planAndSaveOrders(...)` yet — `planAll(...)` still calls it until Task 5 Step 1 rewrites `planAll(...)`. Once that rewrite lands, `planAndSaveOrders(...)` has no remaining callers; delete it as part of Task 5 Step 1.

- [ ] **Step 6: Compile**

Run:

```bash
./gradlew compileJava
```

Expected: compile errors only for constructor/test wiring until Task 4; production compile should pass once `TradingService` constructor injection is satisfied by Spring.

---

### Task 4: Update Open Scheduler to Allocate Account Budgets Before Saving

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Uses `budgetAllocator.allocate(...)`.
- Saves approved orders using `TradingOrderPlanner.savePlannedOrders(...)`.
- Places AT_OPEN orders after saving.

- [ ] **Step 1: Rewrite `placeOpenOrders(...)` post-open loop**

Replace the per-context direct `planSaveAndPlaceSells(...)` loop with:

```java
List<CyclePlanCandidate> candidates = new ArrayList<>();
for (BatchContext ctx : contexts) {
    runSafely("개장 order 후보 생성", ctx,
            () -> collectCycleCandidate(ctx, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(),
                    tradeDate, EnumSet.allOf(Order.OrderTiming.class)))
            .ifPresent(candidates::add);
}

saveAllocatedOrders(candidates, tradeDate);

for (BatchContext ctx : contexts) {
    runSafely("개장 AT_OPEN 접수", ctx, () -> {
        placeAtOpenPlannedOrders(ctx.account(), ctx.currentCycle().id(), tradeDate);
        return null;
    });
}
```

- [ ] **Step 2: Add save/place helpers**

Add:

```java
private void saveAllocatedOrders(List<CyclePlanCandidate> candidates, LocalDate tradeDate) {
    List<TradingOrderBudgetAllocator.Candidate> allocationCandidates = candidates.stream()
            .filter(c -> !c.creatableOrders().isEmpty())
            .map(c -> new TradingOrderBudgetAllocator.Candidate(c.state().ctx(), c.creatableOrders()))
            .toList();
    TradingOrderBudgetAllocator.Allocation allocation = budgetAllocator.allocate(allocationCandidates, tradeDate);

    for (TradingOrderBudgetAllocator.Candidate approved : allocation.approved()) {
        orderPlanner.savePlannedOrders(approved.orders(), approved.ctx().account(), approved.ctx().currentCycle().id());
    }

    Stream.concat(allocation.rejectedBuy().stream(), allocation.rejectedSell().stream())
            .map(TradingOrderBudgetAllocator.Candidate::ctx)
            .distinct()
            .forEach(ctx -> userNotificationPort.notifyInsufficientBalance(
                    ctx.user(), ctx.account(), ctx.strategy().type(), ctx.strategy().ticker()));
}

private void placeAtOpenPlannedOrders(Account account, UUID cycleId, LocalDate tradeDate) {
    List<Order> atOpenOrders = orderPort.findAtOpenPlannedByCycleAndDate(cycleId, tradeDate);
    if (atOpenOrders.isEmpty()) {
        log.info("[{}] 개장 선접수할 주문 없음", account.nickname());
        return;
    }
    orderExecutor.placeGiven(atOpenOrders, account);
}
```

Keep separate structured warning logs for `rejectedBuy` (required cash versus remaining cash) and `rejectedSell` (required quantity versus sellable quantity). The existing notification contract is reused for compatibility, but logs must preserve the true rejection reason for operations and later notification-contract cleanup.

- [ ] **Step 3: Remove `planSaveAndPlaceSells(...)`**

Delete the old method:

```java
private void planSaveAndPlaceSells(...)
```

- [ ] **Step 4: Wire allocator in `TradingServiceTest.setUp()`**

Add the mock and registry wiring using the actual account-domain value type:

```java
@Mock SellableQuantityPort sellableQuantityPort;
```

```java
lenient().doReturn(sellableQuantityPort).when(tradingRegistry).require(any(Account.class), eq(SellableQuantityPort.class));
lenient().when(sellableQuantityPort.getSellableQuantity(any(), any()))
        .thenReturn(new com.kista.domain.model.account.SellableQuantity("SOXL", 100));
lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
```

Construct the allocator immediately before the service and add it as the final constructor argument; this explicitly updates the manual constructor call in `setUp()`:

```java
TradingOrderBudgetAllocator budgetAllocator = new TradingOrderBudgetAllocator(tradingRegistry, orderPort);
service = new TradingService(
        marketCalendarPort, notifyPort, userNotificationPort,
        orderPort, privacyTradePort, strategyCyclePort,
        balanceLoader, tradingRegistry, orderComputer, orderPlanner,
        priceFetcher, orderExecutor, reporter,
        marketEventNotifier, budgetAllocator);
```

- [ ] **Step 5: Add open scheduler priority regression test**

Add to `TradingServiceTest`:

```java
@Test
void placeOpenOrders_allocatesBuyBudgetByStrategyPriorityPerAccount() throws InterruptedException {
    // VR은 TQQQ 고정(Strategy.Type.resolveTicker) — 픽스처도 실제 등록 제약과 일치시켜
    // 계좌 예산 조회가 sorted.getFirst()로 고른 VR 후보의 TQQQ를 그대로 쓰는 경로를 검증한다.
    Strategy vr = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.VR,
            Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
    Strategy infinite = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    Strategy privacy = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.PRIVACY,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vr.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
    StrategyCycle infiniteCycle = new StrategyCycle(UUID.randomUUID(), infinite.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
    StrategyCycle privacyCycle = new StrategyCycle(UUID.randomUUID(), privacy.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(
            Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00")),
            Ticker.TQQQ, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
    when(cycleHistoryPort.findLatestOneByStrategyId(any())).thenReturn(Optional.of(NORMAL_HISTORY));
    // budgetProbeStrategy는 우선순위 정렬 후 첫 후보(VR, TQQQ)에서 나온다 — 계좌 통합잔고이므로 값은 SOXL 조회와 동일하게 둔다
    when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.TQQQ)))
            .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("3000.00")));
    when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
    PrivacyTradeBase privacyBase = new PrivacyTradeBase(
            UUID.randomUUID(), new BigDecimal("20.00"), 10, new BigDecimal("20.00"), List.of());
    when(privacyTradePort.findTodayTrade(any())).thenReturn(Optional.of(privacyBase));
    UUID vrVersionId = vrCycle.strategyVersionId();
    StrategyCycleVrDetail cycleVr = new StrategyCycleVrDetail(
            vrCycle.id(), new BigDecimal("1000.00"), 10, new BigDecimal("2500.00"));
    StrategyVrDetail vrDetail = new StrategyVrDetail(
            vrVersionId, 4, new BigDecimal("15.00"), 0);
    when(strategyCycleVrPort.findByCycleId(vrCycle.id())).thenReturn(Optional.of(cycleVr));
    when(strategyVrDetailPort.findByStrategyVersionId(vrVersionId)).thenReturn(Optional.of(vrDetail));
    when(orderPort.sumFilledBuyAmountByCycleId(vrCycle.id())).thenReturn(BigDecimal.ZERO);
    when(vrStrategy.buildOrders(any(), eq(Ticker.TQQQ), any(), any()))
            .thenReturn(List.of(buyTemplate(Ticker.TQQQ, "1500.00", Order.OrderTiming.AT_OPEN)));
    when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any()))
            .thenReturn(List.of(buyTemplate(Ticker.SOXL, "1000.00", Order.OrderTiming.AT_CLOSE)));
    when(privacyStrategy.buildOrders(any(), any(), any()))
            .thenReturn(List.of(buyTemplate(Ticker.SOXL, "1000.00", Order.OrderTiming.AT_CLOSE)));

    service.placeOpenOrders(List.of(
            new BatchContext(privacy, privacyCycle, ACCOUNT, USER),
            new BatchContext(infinite, infiniteCycle, ACCOUNT, USER),
            new BatchContext(vr, vrCycle, ACCOUNT, USER)
    ), PAST_DST);

    verify(orderPort, times(2)).saveAll(anyList());
    verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.PRIVACY), eq(Ticker.SOXL));
}
```

The test must fail if either VR port or `privacyTradePort.findTodayTrade(...)` is unstubbed; do not rely on `runSafely` swallowing those setup errors.

Add helper:

```java
private Order buyTemplate(Ticker ticker, String amount, Order.OrderTiming timing) {
    return new Order(null, null, null, LocalDate.now(), ticker, Order.OrderType.LIMIT,
            timing, Order.OrderDirection.BUY, 1, new BigDecimal(amount),
            Order.OrderStatus.PLANNED, null, null, null);
}
```

- [ ] **Step 6: Run open scheduler tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest.placeOpenOrders_*'
```

Expected: open scheduler tests pass after updating old all-or-nothing expectations.

---

### Task 5: Update Close Scheduler to Recover Missing AT_CLOSE Slots With Priority

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- `planAll(...)` must collect candidates for all contexts first, allocate per account, save approved AT_CLOSE candidates, and return `CycleState` for contexts with either existing orders or newly approved orders.
- Close scheduler must not create AT_OPEN candidates.

- [ ] **Step 1: Rewrite `planAll(...)`**

Replace the existing loop body with:

```java
List<CyclePlanCandidate> candidates = new ArrayList<>();
for (BatchContext ctx : contexts) {
    runSafely("plan 후보 생성", ctx,
            () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, today,
                    EnumSet.of(Order.OrderTiming.AT_CLOSE)))
            .ifPresent(candidates::add);
}
saveAllocatedOrders(candidates, today);
return candidates.stream()
        .map(CyclePlanCandidate::state)
        .toList();
```

Because `collectCycleCandidate(...)` returns an existing-order state when `compute()` is empty, this also returns states when only existing orders are present. `placeAll(...)` can therefore include pre-placed orders in reporting even when PRIVACY input is unavailable or another strategy calculation is skipped.

`planAndSaveOrders(...)` has no remaining callers after this rewrite. Delete it in this step (deferred from Task 3 Step 5).

- [ ] **Step 2: Keep initial no-existing-orders behavior correct**

Because close scheduler now uses `EnumSet.of(Order.OrderTiming.AT_CLOSE)`, AT_OPEN missed slots are never created at close. Existing AT_OPEN orders can still be found later by:

```java
orderPort.findPlacedByCycleAndDate(state.ctx().currentCycle().id(), today)
```

inside `placeAll(...)`.

- [ ] **Step 3: Add existing-order preservation test when compute skips**

Add a PRIVACY context with one existing `AT_CLOSE SELL` in PLANNED status and make `privacyTradePort.findTodayTrade(today)` return `Optional.empty()`. Run `executeBatch(...)` and verify the existing order is still loaded for placement; no new order is saved:

```java
when(privacyTradePort.findTodayTrade(today)).thenReturn(Optional.empty());
when(orderPort.findPlannedOrPlacedByCycleAndDate(privacyCycle.id(), today))
        .thenReturn(List.of(existingPrivacySell));
when(orderPort.findPlannedByCycleAndDate(privacyCycle.id(), today))
        .thenReturn(List.of(existingPrivacySell));
when(brokerOrderPort.place(existingPrivacySell, ACCOUNT)).thenReturn(placedPrivacySell);

service.executeBatch(List.of(new BatchContext(privacy, privacyCycle, ACCOUNT, USER)), PAST_DST);

verify(orderPort, never()).saveAll(anyList());
verify(brokerOrderPort).place(existingPrivacySell, ACCOUNT);
```

Use a valid `PrivacyTradeBase` only in tests that expect PRIVACY strategy computation. This regression intentionally leaves it absent to prove existing orders survive `compute() == Optional.empty()`.

- [ ] **Step 4: Add close recovery test**

Add:

```java
@Test
void executeBatch_existingAtOpenSell_doesNotBlockMissingAtCloseBuy() throws InterruptedException {
    LocalDate today = LocalDate.now(TimeZones.KST);
    Order existingSellPlaced = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), today, Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "ORD-SELL-OPEN", null, null);
    Order buyTemplate = new Order(null, null, null, today, Ticker.SOXL, Order.OrderType.LOC,
            Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
            Order.OrderStatus.PLANNED, null, null, null);
    Order buyPlanned = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), today, Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
            Order.OrderStatus.PLANNED, null, null, null);
    Order buyPlaced = buyPlanned.withPlaced("ORD-BUY-CLOSE");

    when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
    when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, new BigDecimal("24.00")));
    when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
    when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of(buyTemplate));
    when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingSellPlaced));
    when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(buyPlanned));
    when(orderPort.findPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existingSellPlaced));
    when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
            .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00")));
    when(brokerOrderPort.place(eq(buyPlanned), eq(ACCOUNT))).thenReturn(buyPlaced);
    when(kisExecutionPort.getExecutions(any(), any(), eq(Ticker.SOXL), eq(ACCOUNT))).thenReturn(List.of());

    service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

    verify(orderPort).saveAll(argThat(saved ->
            saved.size() == 1
                    && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE
                    && saved.getFirst().direction() == Order.OrderDirection.BUY));
    verify(brokerOrderPort).place(eq(buyPlanned), eq(ACCOUNT));
}
```

- [ ] **Step 5: Add no-late-AT_OPEN-recovery test for VR**

Add a test where `vrStrategy.buildOrders(...)` returns only AT_OPEN BUY/SELL and `executeBatch(...)` is run. Verify no AT_OPEN orders are saved:

```java
verify(orderPort, never()).saveAll(argThat(saved ->
        saved.stream().anyMatch(o -> o.timing() == Order.OrderTiming.AT_OPEN)));
```

- [ ] **Step 6: Run close scheduler tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: PASS after adapting existing tests to the new candidate-collection flow.

---

### Task 6: Add Privacy and Multi-Account Regression Coverage

**Files:**
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Proves the behavior is strategy-independent and account budgets are isolated.

- [ ] **Step 1: Add PRIVACY partial-save test**

Add a test where `privacyStrategy.buildOrders(...)` returns one AT_CLOSE BUY and one AT_CLOSE SELL, live deposit is insufficient for BUY, and sellable quantity is sufficient for SELL. Verify only SELL is saved:

```java
verify(orderPort).saveAll(argThat(saved ->
        saved.size() == 1
                && saved.getFirst().direction() == Order.OrderDirection.SELL
                && saved.getFirst().timing() == Order.OrderTiming.AT_CLOSE));
```

- [ ] **Step 2: Add PRIVACY close recovery test**

Set `findPlannedOrPlacedByCycleAndDate(...)` to return an existing AT_CLOSE SELL only. Make `privacyStrategy.buildOrders(...)` return AT_CLOSE BUY + AT_CLOSE SELL. With sufficient live deposit, verify only AT_CLOSE BUY is saved.

- [ ] **Step 3: Add account isolation test**

Create two accounts, each with `$1000` live deposit and one `$1000` BUY candidate. Run them in the same batch. Verify both accounts receive their BUY order:

```java
verify(orderPort, times(2)).saveAll(argThat(saved ->
        saved.size() == 1 && saved.getFirst().direction() == Order.OrderDirection.BUY));
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: PASS.

---

### Task 7: Update Workflow Documentation

**Files:**
- Modify: `docs/agents/workflow.md`
- Modify: `docs/agents/architecture.md`

**Interfaces:**
- Documents scheduler behavior for future agents and operators.

- [ ] **Step 1: Update scheduler flow bullet**

Replace the current “당일 PLANNED/PLACED 주문 존재 시 신규 생성만 skip” language with:

```markdown
- 각 사이클: 휴장 확인 → 잔고 조회 → 현재가(배치 캐시 or 단건 fallback) → 전략 계산 → 기존 PLANNED/PLACED 주문을 `timing+direction` 슬롯으로 확인 → 없는 슬롯만 신규 생성 후보로 유지 → 계좌별 BUY 후보를 우선순위로 예수금 배정(`VR` → `INFINITE` → `PRIVACY`, 같은 타입은 필요 BUY 금액 오름차순) → SELL 후보는 판매가능수량으로 별도 검증 → 통과한 주문만 PLANNED 저장 → AT_OPEN 주문 선접수 → `DstInfo.waitUntilOrderTime()` 대기 → PLANNED AT_CLOSE 주문 조회 → KIS/Toss 접수 → 체결 리포트
```

- [ ] **Step 2: Add partial-order policy note**

Add:

```markdown
### 부분 주문 생성 및 예수금 배정 정책
- BUY와 SELL은 독립 검증한다. BUY 예수금 부족은 SELL 저장·접수를 막지 않고, SELL 판매가능수량 부족은 BUY 저장·접수를 막지 않는다.
- BUY 예수금은 계좌 단위로 배정한다. 전략 우선순위는 `VR` → `INFINITE` → `PRIVACY`이며, 같은 전략 타입은 필요 BUY 금액이 작은 사이클을 먼저 승인한다.
- 한 사이클의 BUY 후보는 주문 단위로 쪼개지 않고 전체 승인 또는 전체 제외한다.
- 중복 방지는 `timing+direction` 슬롯 기준이다. 예: 기존 `AT_OPEN SELL`은 신규 `AT_CLOSE BUY` 생성을 막지 않는다.
- 장 개시 스케쥴러는 모든 timing의 신규 주문을 저장할 수 있지만, 즉시 접수는 `AT_OPEN`만 수행한다.
- 마감 스케쥴러는 누락된 `AT_CLOSE` 슬롯만 복구한다. 이미 지나간 `AT_OPEN` 슬롯은 마감 시점에 자동 생성하지 않는다.
```

- [ ] **Step 3: Register the allocator in the architecture SSOT**

In `docs/agents/architecture.md`, append `TradingOrderBudgetAllocator` to the package-private helper list under `application/service/trading/`:

```markdown
package-private helper: TradingBalanceLoader/TradingOrderPlanner/TradingOrderExecutor/TradingPriceFetcher/TradingReporter/BuyOrderPriceCapper/CycleOrderComputer/CycleSnapshotCreator/SeedResolutionPolicy/TradingDayCounter/TradingOrderBudgetAllocator
```

- [ ] **Step 4: Run docs-neutral verification**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: docs change does not affect tests.

---

### Task 8: Final Verification

**Files:**
- No code changes unless verification finds a defect.

**Interfaces:**
- Confirms behavior is complete and project still compiles.

- [ ] **Step 1: Run focused allocator tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest'
```

Expected: PASS.

- [ ] **Step 2: Run focused service tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
```

Expected: PASS.

- [ ] **Step 3: Run production compile**

Run:

```bash
./gradlew compileJava
```

Expected: PASS.

- [ ] **Step 4: Run broader non-integration test suite**

Run:

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 5: Review changed behavior manually**

Check the diff:

```bash
git diff -- src/main/java/com/kista/application/service/trading/TradingService.java src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java src/test/java/com/kista/application/service/trading/TradingServiceTest.java src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java docs/agents/workflow.md docs/agents/architecture.md
```

Confirm:
- No strategy formula changed.
- No DB contract changed.
- BUY budget is account-scoped.
- BUY priority is `VR` → `INFINITE` → `PRIVACY`.
- Same strategy type uses smaller BUY total first.
- BUY candidates are approved cycle-level all-or-nothing.
- SELL candidates do not consume BUY budget.
- `AT_OPEN` missed orders are not created by close scheduler.
- Existing opposite-direction slots no longer block missing valid slots.
- Existing orders remain in placement/reporting flow when strategy computation returns empty.
- User insufficient-balance notification still fires when either side is excluded.

---

## Self-Review

- Spec coverage: The plan applies BUY/SELL split to all strategies and adds account-level BUY priority allocation.
- Priority coverage: The plan fixes the policy to `VR` → `INFINITE` → `PRIVACY`, smaller BUY total first within a type, deterministic tie-breaks.
- Side effect coverage: The plan avoids the earlier bad side effect by letting close scheduler recover missing AT_CLOSE slots even when opposite-direction orders already exist.
- Existing-order coverage: `compute()` returning empty no longer discards an existing-order state; a dedicated PRIVACY regression test verifies placement continues.
- VR timing coverage: The plan explicitly prevents late AT_OPEN recovery.
- SELL reservation coverage: SELL allocation includes existing PLANNED/PLACED reservations by account and ticker, then applies deterministic same-batch allocation before accepting new candidates.
- Privacy coverage: The plan adds PRIVACY-specific regression tests so common behavior is proven beyond INFINITE.
- Multi-account coverage: The plan adds an account-isolation test so one account's candidates cannot consume another account's budget.
- Placeholder scan: No task uses TBD/TODO placeholders; each helper has concrete names and expected behavior.
- Type consistency: Helper signatures use existing domain types: `Order`, `Account`, `Strategy`, `AccountBalance`, `BatchContext`, `Order.OrderTiming`, and `Order.OrderDirection`.
- Review corrections (round 1): `SellableQuantity` uses the account package and two-argument constructor; `runSafely` receives a nullable candidate rather than an `Optional`; deterministic maps use `LinkedHashMap`; sell-only tests contain no unused live-balance stubs; priority tests provide PRIVACY and VR computation fixtures.
- Review corrections (round 2): SELL approval/rejection now logs required-vs-sellable quantity symmetrically with the BUY path (`sellableQuantityFor` helper); `planAndSaveOrders(...)` deletion is explicitly scheduled for Task 5 Step 1 (its last caller) instead of being silently orphaned; the Task 4 Step 5 fixture assigns VR the `TQQQ` ticker it is actually forced to use (`Strategy.Type.resolveTicker`), with dependent stubs (`getLiveBalance`, `getPriceSnapshots`, `vrStrategy.buildOrders` matcher, `buyTemplate`) updated to match so the budget-probe-ticker path is exercised realistically.
