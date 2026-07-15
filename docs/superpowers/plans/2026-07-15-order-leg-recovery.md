# Order Leg Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist strategy order-leg identity so scheduler/manual reruns can recreate only missing legs instead of suppressing every order with the same `timing + direction`.

**Architecture:** Add `orders.order_leg` as an internal persisted string and carry it through the domain `Order`, JPA entity, mapper, and strategy-generated templates. `TradingService` uses `timing + direction + orderLeg` for concrete orders and preserves legacy `UNKNOWN` rows as coarse `timing + direction` occupancy. Strategy allocation priority moves from allocator switch logic into `CycleOrderStrategy` capability metadata.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Flyway, JUnit 5, Mockito, AssertJ, Gradle.

## Global Constraints

- `order_leg` is internal only; do not send it to broker APIs.
- Existing historical rows are backfilled as `UNKNOWN` and must remain readable.
- Existing `UNKNOWN` rows keep conservative coarse-slot behavior by `timing + direction`.
- New strategy-generated scheduler templates must use non-blank concrete `orderLeg` values.
- VR and PRIVACY concrete compute-skip is out of scope because ladder length is variable.
- Do not infer exact legs for historical rows.
- PostgreSQL enum types are forbidden; use `VARCHAR`.
- Flyway migration must be append-only and named `V23__add_order_leg_and_order_indexes.sql`.

---

## File Structure

- Modify `src/main/java/com/kista/domain/model/order/Order.java`
  - Add `String orderLeg`, constants, sequence helper, compatibility constructor, and `withLeg(...)`.
- Modify `src/main/java/com/kista/adapter/out/persistence/trade/OrderEntity.java`
  - Add `orderLeg` column mapping.
- Modify `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
  - Map `orderLeg` both ways.
- Create `src/main/resources/db/migration/V23__add_order_leg_and_order_indexes.sql`
  - Add `order_leg`, backfill/default, and order query indexes.
- Modify strategy classes under `src/main/java/com/kista/domain/strategy/`
  - Assign stable concrete `orderLeg` values to generated templates.
- Modify `src/main/java/com/kista/application/service/trading/TradingService.java`
  - Replace `OrderSlot` matching with leg-aware matching plus legacy `UNKNOWN` coarse fallback.
  - Add conservative compute skip for legacy coarse rows and complete INFINITE concrete sets.
- Modify `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`
  - Add `allocationPriority()` and optional `canSkipOrderComputation(...)`.
- Modify `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
  - Use `CycleOrderStrategies` capability instead of local switch.
- Update focused tests and docs under `docs/agents/`.

### Task 1: Persist `order_leg` Through Domain and JPA

**Files:**
- Modify: `src/main/java/com/kista/domain/model/order/Order.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderEntity.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
- Create: `src/main/resources/db/migration/V23__add_order_leg_and_order_indexes.sql`
- Modify: `src/test/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapterDbTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingOrderPlannerTest.java`

**Interfaces:**
- Produces `Order.UNKNOWN_LEG = "UNKNOWN"`.
- Produces `Order.leg(String prefix, int index)` returning zero-padded keys like `VR_BUY_01`.
- Produces `Order.withLeg(String orderLeg)`.
- Keeps existing `new Order(... filledPrice)` call sites compiling through an auxiliary constructor that defaults to `UNKNOWN`.

- [ ] **Step 1: Add failing persistence test**

In `OrderPersistenceAdapterDbTest`, add a test that inserts through `OrderPersistenceAdapter.saveAll(...)` and reads back through `findPlannedByCycleAndDate(...)`:

```java
@Test
void saveAll_persistsAndLoadsOrderLeg() {
    Order order = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
            Order.OrderDirection.BUY, 1, new BigDecimal("22.00"))
            .withLeg("INFINITE_EARLY_AVG_BUY");

    adapter.saveAll(List.of(Order.plan(order, accountId, cycleId)));

    List<Order> result = adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now());

    assertThat(result).singleElement()
            .satisfies(saved -> assertThat(saved.orderLeg()).isEqualTo("INFINITE_EARLY_AVG_BUY"));
}
```

Add imports:

```java
import com.kista.domain.model.order.Order;
import java.util.List;
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew test --tests 'com.kista.adapter.out.persistence.trade.OrderPersistenceAdapterDbTest.saveAll_persistsAndLoadsOrderLeg'
```

Expected: compile failure because `Order.orderLeg()` and `withLeg(...)` do not exist.

- [ ] **Step 3: Add `orderLeg` to `Order` with compatibility constructor**

Update `Order` canonical record components by adding `String orderLeg` after `direction`.

Add constants/helpers inside `Order`:

```java
public static final String UNKNOWN_LEG = "UNKNOWN";

public Order(UUID id, UUID accountId, UUID strategyCycleId, LocalDate tradeDate, Ticker ticker,
             OrderType orderType, OrderTiming timing, OrderDirection direction,
             Integer quantity, BigDecimal price, OrderStatus status, String externalOrderId,
             Integer filledQuantity, BigDecimal filledPrice) {
    this(id, accountId, strategyCycleId, tradeDate, ticker, orderType, timing, direction,
            UNKNOWN_LEG, quantity, price, status, externalOrderId, filledQuantity, filledPrice);
}

public Order {
    if (orderLeg == null || orderLeg.isBlank()) orderLeg = UNKNOWN_LEG;
}

public static String leg(String prefix, int index) {
    if (index < 1) throw new IllegalArgumentException("order leg index must be positive: " + index);
    return "%s_%02d".formatted(prefix, index);
}

public Order withLeg(String newLeg) {
    return new Order(id, accountId, strategyCycleId, tradeDate, ticker,
            orderType, timing, direction, newLeg, quantity, price, status,
            externalOrderId, filledQuantity, filledPrice);
}
```

Update all factory/copy methods in `Order` so they preserve or set `orderLeg`:

```java
Order.plan(...)              // preserve template.orderLeg()
Order.planned(...)           // default UNKNOWN unless overload with leg is used
Order.planned(..., leg)      // add overload for strategy templates
withPlaced/withPrice/reorder/withFailed // preserve existing orderLeg
filledManual(...)            // UNKNOWN
```

- [ ] **Step 4: Add JPA mapping and migration**

Add to `OrderEntity` after `direction`:

```java
@Column(name = "order_leg", nullable = false, length = 50)
private String orderLeg = Order.UNKNOWN_LEG;
```

Update `OrderPersistenceAdapter.toEntity(...)`:

```java
e.setOrderLeg(o.orderLeg());
```

Update `toDomain(...)` to pass `e.getOrderLeg()` in the new canonical constructor.

Create `src/main/resources/db/migration/V23__add_order_leg_and_order_indexes.sql`:

```sql
ALTER TABLE orders
    ADD COLUMN order_leg VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN';

CREATE INDEX idx_orders_cycle_date_status
    ON orders(strategy_cycle_id, trade_date, status);

CREATE INDEX idx_orders_cycle_date_timing_status
    ON orders(strategy_cycle_id, trade_date, timing, status);

CREATE INDEX idx_orders_account_date_status_direction
    ON orders(account_id, trade_date, status, direction);

CREATE INDEX idx_orders_account_date_ticker_direction_status
    ON orders(account_id, trade_date, ticker, direction, status);
```

- [ ] **Step 5: Update direct SQL test helper**

In `OrderPersistenceAdapterDbTest.insertOrderForAccount(...)`, include `order_leg` explicitly so tests keep exercising the new column:

```java
"INSERT INTO orders (..., direction, order_leg, price, quantity, status, ...)"
...
direction, Order.UNKNOWN_LEG, new BigDecimal("22.00"), ...
```

- [ ] **Step 6: Verify Task 1**

Run:

```bash
./gradlew test --tests 'com.kista.adapter.out.persistence.trade.OrderPersistenceAdapterDbTest' \
  --tests 'com.kista.application.service.trading.TradingOrderPlannerTest'
./gradlew compileJava
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 7: Commit Task 1**

```bash
git add src/main/java/com/kista/domain/model/order/Order.java \
  src/main/java/com/kista/adapter/out/persistence/trade/OrderEntity.java \
  src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java \
  src/main/resources/db/migration/V23__add_order_leg_and_order_indexes.sql \
  src/test/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapterDbTest.java \
  src/test/java/com/kista/application/service/trading/TradingOrderPlannerTest.java
git commit -m "feat(trading): persist order leg identity"
```

### Task 2: Assign Concrete Legs in Strategies and Cap Preparation

**Files:**
- Modify: `src/main/java/com/kista/domain/strategy/InfiniteStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/ReverseInfiniteStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/PrivacyStrategy.java`
- Modify: `src/test/java/com/kista/domain/strategy/InfiniteStrategyTypeTest.java`
- Modify: `src/test/java/com/kista/domain/strategy/VrStrategyTest.java`
- Modify: `src/test/java/com/kista/domain/strategy/PrivacyStrategyTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java`

**Interfaces:**
- Consumes `Order.planned(..., String orderLeg)` and `Order.leg(prefix, index)` from Task 1.
- Produces concrete `orderLeg` for all strategy-generated templates.

- [ ] **Step 1: Add failing strategy leg assertions**

Add assertions to existing strategy tests:

```java
assertThat(orders).extracting(Order::orderLeg)
        .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                "INFINITE_LOC_SELL", "INFINITE_LIMIT_SELL");
```

For cap tests:

```java
assertThat(result).extracting(Order::orderLeg)
        .containsExactly("INFINITE_EARLY_AVG_BUY", "INFINITE_EARLY_REF_BUY",
                "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03");
```

For merged cap:

```java
assertThat(result).extracting(Order::orderLeg)
        .containsExactly("INFINITE_EARLY_MERGED_BUY",
                "INFINITE_CORRECTION_01", "INFINITE_CORRECTION_02", "INFINITE_CORRECTION_03");
```

For VR tests, assert sequential keys:

```java
assertThat(buyOrders).extracting(Order::orderLeg)
        .containsExactly("VR_BUY_01", "VR_BUY_02");
```

For PRIVACY tests, assert sequential keys:

```java
assertThat(buyOrders(orders)).extracting(Order::orderLeg)
        .containsExactly("PRIVACY_BUY_01", "PRIVACY_BUY_02");
assertThat(sellOrders(orders)).extracting(Order::orderLeg)
        .containsExactly("PRIVACY_SELL_01");
```

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
./gradlew test --tests 'com.kista.domain.strategy.InfiniteStrategyTypeTest' \
  --tests 'com.kista.domain.strategy.VrStrategyTest' \
  --tests 'com.kista.domain.strategy.PrivacyStrategyTest'
```

Expected: failures because generated orders still use `UNKNOWN`.

- [ ] **Step 3: Assign INFINITE and reverse INFINITE legs**

Update `InfiniteStrategy`:

```java
orders.add(Order.planned(..., "INFINITE_EARLY_AVG_BUY"));
orders.add(Order.planned(..., "INFINITE_EARLY_REF_BUY"));
orders.add(Order.planned(..., "INFINITE_LATE_REF_BUY"));
orders.add(Order.planned(..., "INFINITE_MOC_SELL"));
orders.add(Order.planned(..., "INFINITE_LOC_SELL"));
orders.add(Order.planned(..., "INFINITE_LIMIT_SELL"));
```

In `buildCappedBuyOrders(...)`, preserve base legs from original BUY orders, use `INFINITE_EARLY_MERGED_BUY` when the two early orders merge, and add corrections with:

```java
Order.leg("INFINITE_CORRECTION", correctionIndex)
```

Update `ReverseInfiniteStrategy` with:

```java
"REVERSE_INFINITE_MOC_SELL"
"REVERSE_INFINITE_LOC_SELL"
"REVERSE_INFINITE_LOC_BUY"
```

- [ ] **Step 4: Assign VR and PRIVACY sequence legs**

In `VrStrategy.mergeSamePriceOrders(...)`, assign `VR_BUY_01..N` to merged BUY orders. In `buildSellOrders(...)`, assign `VR_SELL_01..N`.

In `PrivacyStrategy`, after final `buyOrders` and `sellOrders` are built, map them through:

```java
private List<Order> assignSequentialLegs(List<Order> orders, String prefix) {
    List<Order> result = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
        result.add(orders.get(i).withLeg(Order.leg(prefix, i + 1)));
    }
    return result;
}
```

- [ ] **Step 5: Verify Task 2**

Run:

```bash
./gradlew test --tests 'com.kista.domain.strategy.InfiniteStrategyTypeTest' \
  --tests 'com.kista.domain.strategy.VrStrategyTest' \
  --tests 'com.kista.domain.strategy.PrivacyStrategyTest' \
  --tests 'com.kista.application.service.trading.BuyOrderPriceCapperTest'
git diff --check
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add src/main/java/com/kista/domain/strategy/InfiniteStrategy.java \
  src/main/java/com/kista/domain/strategy/ReverseInfiniteStrategy.java \
  src/main/java/com/kista/domain/strategy/VrStrategy.java \
  src/main/java/com/kista/domain/strategy/PrivacyStrategy.java \
  src/test/java/com/kista/domain/strategy/InfiniteStrategyTypeTest.java \
  src/test/java/com/kista/domain/strategy/VrStrategyTest.java \
  src/test/java/com/kista/domain/strategy/PrivacyStrategyTest.java \
  src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java
git commit -m "feat(trading): assign strategy order legs"
```

### Task 3: Use Leg-Aware Slot Recovery in TradingService

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Consumes concrete `orderLeg` values from Task 2.
- Produces leg-aware `filterCreatableOrders(...)` behavior.

- [ ] **Step 1: Add failing partial recovery regression**

Add a test to `TradingServiceTest` where existing orders contain only one concrete BUY leg and the strategy computes three BUY legs:

```java
@Test
void executeBatch_existingConcreteBuyLeg_savesOnlyMissingBuyLegs() throws InterruptedException {
    Order existing = new Order(UUID.randomUUID(), ACCOUNT.id(), STRATEGY_CYCLE.id(), LocalDate.now(),
            Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
            "INFINITE_EARLY_AVG_BUY", 1, new BigDecimal("20.00"), Order.OrderStatus.PLANNED,
            null, null, null);
    Order avg = buyTemplate(Ticker.SOXL, "20.00", Order.OrderTiming.AT_CLOSE).withLeg("INFINITE_EARLY_AVG_BUY");
    Order ref = buyTemplate(Ticker.SOXL, "22.00", Order.OrderTiming.AT_CLOSE).withLeg("INFINITE_EARLY_REF_BUY");
    Order correction = buyTemplate(Ticker.SOXL, "18.00", Order.OrderTiming.AT_CLOSE).withLeg("INFINITE_CORRECTION_01");

    when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
            .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
    when(kisPricePort.getPrices(anyList(), eq(ACCOUNT))).thenReturn(Map.of(Ticker.SOXL, PRICE));
    when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
    when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
            .thenReturn(List.of(existing));
    when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
            .thenReturn(List.of(avg, ref, correction));
    when(orderPort.findPlannedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(existing));
    when(kisExecutionPort.getExecutions(any(), any(), any(), eq(ACCOUNT))).thenReturn(List.of());

    service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

    verify(orderPort).saveAll(argThat(saved -> saved.size() == 2
            && saved.stream().map(Order::orderLeg).toList()
                    .containsAll(List.of("INFINITE_EARLY_REF_BUY", "INFINITE_CORRECTION_01"))));
}
```

- [ ] **Step 2: Add legacy UNKNOWN coarse regression**

Add a test where existing order has `UNKNOWN` and computed templates have two concrete `AT_CLOSE + BUY` legs. Verify no new BUY is saved for that timing/direction.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest.executeBatch_existingConcreteBuyLeg_savesOnlyMissingBuyLegs' \
  --tests 'com.kista.application.service.trading.TradingServiceTest.executeBatch_existingUnknownBuy_keepsCoarseSlotBehavior'
```

Expected: partial recovery fails because `OrderSlot` still keys by `timing + direction`.

- [ ] **Step 4: Implement leg-aware matching**

Change `OrderSlot` in `TradingService`:

```java
private record OrderSlot(Order.OrderTiming timing, Order.OrderDirection direction, String orderLeg) {
    static OrderSlot of(Order order) {
        return new OrderSlot(order.timing(), order.direction(), order.orderLeg());
    }
}

private record LegacySlot(Order.OrderTiming timing, Order.OrderDirection direction) {
    static LegacySlot of(Order order) {
        return new LegacySlot(order.timing(), order.direction());
    }
}
```

Update `filterCreatableOrders(...)`:

```java
Set<OrderSlot> existingConcreteSlots = existingOrders.stream()
        .filter(order -> !Order.UNKNOWN_LEG.equals(order.orderLeg()))
        .map(OrderSlot::of)
        .collect(Collectors.toSet());
Set<LegacySlot> existingLegacySlots = existingOrders.stream()
        .filter(order -> Order.UNKNOWN_LEG.equals(order.orderLeg()))
        .map(LegacySlot::of)
        .collect(Collectors.toSet());

return plannedTemplates.stream()
        .filter(order -> creatableTimings.contains(order.timing()))
        .filter(order -> !existingLegacySlots.contains(LegacySlot.of(order)))
        .filter(order -> !existingConcreteSlots.contains(OrderSlot.of(order)))
        .toList();
```

- [ ] **Step 5: Verify Task 3**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
git diff --check
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java \
  src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "fix(trading): recover missing order legs"
```

### Task 4: Move Allocation Priority Into Strategy Capability

**Files:**
- Modify: `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/InfiniteCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/PrivacyCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Produces `CycleOrderStrategy.allocationPriority()`.
- `TradingOrderBudgetAllocator` constructor now consumes `CycleOrderStrategies`.

- [ ] **Step 1: Add capability test**

In `TradingOrderBudgetAllocatorTest`, construct real `CycleOrderStrategies` with mocked strategy implementations and assert existing priority behavior still passes. The current tests already verify behavior; this step mainly prepares constructor wiring.

- [ ] **Step 2: Implement capability**

Add to `CycleOrderStrategy`:

```java
default int allocationPriority() { return 100; }
```

Override:

```java
// VrCycleOrderStrategy
@Override public int allocationPriority() { return 0; }

// InfiniteCycleOrderStrategy
@Override public int allocationPriority() { return 1; }

// PrivacyCycleOrderStrategy
@Override public int allocationPriority() { return 2; }
```

Update `TradingOrderBudgetAllocator`:

```java
private final CycleOrderStrategies cycleOrderStrategies;

private int strategyPriority(Strategy.Type type) {
    return cycleOrderStrategies.of(type).allocationPriority();
}
```

Update test and service construction to pass `cycleStrategies`.

- [ ] **Step 3: Verify Task 4**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest' \
  --tests 'com.kista.application.service.trading.TradingServiceTest'
./gradlew test --tests 'com.kista.architecture.*'
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Commit Task 4**

```bash
git add src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java \
  src/main/java/com/kista/domain/strategy/InfiniteCycleOrderStrategy.java \
  src/main/java/com/kista/domain/strategy/PrivacyCycleOrderStrategy.java \
  src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java \
  src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java \
  src/test/java/com/kista/application/service/trading/TradingOrderBudgetAllocatorTest.java \
  src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "refactor(trading): move allocation priority to strategy capability"
```

### Task 5: Add Conservative Compute Skip

**Files:**
- Modify: `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/InfiniteCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Produces `CycleOrderStrategy.canSkipOrderComputation(List<Order> existingOrders, Set<Order.OrderTiming> creatableTimings)`.
- Only INFINITE implements concrete skip; default returns false.

- [ ] **Step 1: Add skip tests**

Add `TradingServiceTest` cases:

```java
@Test
void executeBatch_existingCompleteInfiniteEarlyConcreteLegs_skipsOrderComputer() { ... }

@Test
void executeBatch_existingPartialInfiniteConcreteLegs_doesNotSkipOrderComputer() { ... }
```

The complete case uses existing `INFINITE_EARLY_AVG_BUY` and `INFINITE_EARLY_REF_BUY` for `AT_CLOSE`; verify `infiniteStrategy.buildOrders(...)` is never called and existing placement/reporting still proceeds. The partial case has only `INFINITE_EARLY_AVG_BUY`; verify `buildOrders(...)` is called.

- [ ] **Step 2: Implement conservative capability**

Add default to `CycleOrderStrategy`:

```java
default boolean canSkipOrderComputation(List<Order> existingOrders, Set<Order.OrderTiming> creatableTimings) {
    return false;
}
```

Implement in `InfiniteCycleOrderStrategy` with helper sets:

```java
Set<String> atCloseLegs = existingOrders.stream()
        .filter(order -> creatableTimings.contains(order.timing()))
        .filter(order -> order.timing() == Order.OrderTiming.AT_CLOSE)
        .map(Order::orderLeg)
        .collect(Collectors.toSet());

boolean earlyComplete = atCloseLegs.contains("INFINITE_EARLY_AVG_BUY")
        && atCloseLegs.contains("INFINITE_EARLY_REF_BUY");
boolean earlyMergedComplete = atCloseLegs.contains("INFINITE_EARLY_MERGED_BUY");
boolean lateComplete = atCloseLegs.contains("INFINITE_LATE_REF_BUY");
boolean correctionComplete = atCloseLegs.contains("INFINITE_CORRECTION_01")
        && atCloseLegs.contains("INFINITE_CORRECTION_02")
        && atCloseLegs.contains("INFINITE_CORRECTION_03")
        && (earlyComplete || earlyMergedComplete || lateComplete);

return earlyComplete || earlyMergedComplete || lateComplete || correctionComplete;
```

Also return true for legacy UNKNOWN coarse occupancy only when all existing target rows are UNKNOWN and at least one target timing/direction is occupied.

- [ ] **Step 3: Use capability before compute**

In `TradingService.collectCycleCandidate(...)`, after loading existing orders and before `orderComputer.compute(...)`:

```java
CycleOrderStrategy strategyHandler = cycleOrderStrategies.of(strategy.type());
if (!existingOrders.isEmpty()
        && strategyHandler.canSkipOrderComputation(existingOrders, creatableTimings)) {
    CycleState existingState = buildCycleStateFromExistingOrders(
            ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size(), false);
    return new CyclePlanCandidate(existingState, List.of(), true);
}
```

Adjust `buildCycleStateFromExistingOrders(...)` to accept a boolean that controls INFINITE position recalculation. Use `true` in the existing compute-empty branch to preserve current cap behavior.

- [ ] **Step 4: Verify Task 5**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'
git diff --check
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java \
  src/main/java/com/kista/domain/strategy/InfiniteCycleOrderStrategy.java \
  src/main/java/com/kista/application/service/trading/TradingService.java \
  src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "perf(trading): skip complete infinite order recomputation"
```

### Task 6: Documentation and Final Verification

**Files:**
- Modify: `docs/agents/workflow.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/testing.md`

**Interfaces:**
- Documents behavior only; no code interface changes.

- [ ] **Step 1: Update shared docs**

Document:
- `orders.order_leg` internal column and `UNKNOWN` legacy behavior.
- Scheduler slot matching is now `timing + direction + orderLeg` for concrete legs.
- VR/PRIVACY concrete compute skip remains disabled by design.
- Order query indexes exist for scheduler/reservation queries.
- Tests should use `withLeg(...)` for scheduler leg recovery scenarios.

- [ ] **Step 2: Run full verification**

Run sequentially, not in parallel, because both commands use Gradle `:test` outputs:

```bash
./gradlew test --rerun-tasks
./gradlew test --tests 'com.kista.architecture.*' --rerun-tasks
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 3: Commit Task 6**

```bash
git add docs/agents/workflow.md docs/agents/constraints.md docs/agents/architecture.md docs/agents/testing.md
git commit -m "docs: sync order leg recovery behavior"
```

---

## Self-Review

- Spec coverage: order_leg persistence, strategy assignment, leg-aware slot filtering, legacy UNKNOWN compatibility, allocation priority capability, indexes, and conservative compute skip are all covered.
- Placeholder scan: no TBD/TODO placeholders are present.
- Type consistency: plan uses `String orderLeg`, `Order.UNKNOWN_LEG`, `Order.leg(...)`, and `CycleOrderStrategy.allocationPriority()` consistently with the revised spec.
