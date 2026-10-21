# VR Initial Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** VR 전략 등록과 첫 사이클 주문을 초기 TQQQ 평가금, 초기 USD pool, 주기당 입출금 의미에 맞게 바꾼다.

**Architecture:** 등록 검증은 `StrategyService`에 두고, VR 값 정규화와 첫 사이클 poolLimit 계산은 `VrStrategyLifecycle`에 둔다. 주문 생성은 기존 VR 사다리 로직을 보존하면서 첫 사이클 bootstrap LOC 주문 분기를 추가한다. 남은 거래일 수는 `MarketCalendarPort`를 사용하는 application helper에서 계산해 `CycleOrderComputer -> VrCycleOrderStrategy -> VrStrategy`로 전달한다.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, hexagonal architecture, Flyway 없음

## Global Constraints

- Work in `/Users/phs/workspace/kista/kista-api`.
- Do not edit Flyway migrations for this change; no schema change is required.
- Keep domain model free of Spring/JPA annotations.
- Keep application services depending on ports, not adapters.
- Use `IllegalArgumentException` for invalid VR registration rules so `GlobalExceptionHandler` maps them to 400.
- `initialValue`, `initialUsdDeposit`, and `recurringAmount` nulls must be normalized before VR calculations.
- All first-cycle bootstrap orders must be `LOC` and `AT_CLOSE`.
- Buy bootstrap LOC price is `currentPrice * 1.10`; sell bootstrap LOC price is `currentPrice * 0.90`.
- After first cycle, VR `poolLimit` must remain based on `postBalance.usdDeposit * poolLimitRate()`.

---

## File Structure

- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java`  
  Role: VR registration validation and normalized initial values passed into cycle creation.
- Modify: `src/main/java/com/kista/application/service/strategy/VrStrategyLifecycle.java`  
  Role: normalize VR initial money values and calculate first-cycle poolLimit from `initialValue + initialUsdDeposit`.
- Modify: `src/main/java/com/kista/domain/model/strategy/VrPosition.java`  
  Role: carry bootstrap metadata into `VrStrategy` and expose bootstrap helper calculations.
- Modify: `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`  
  Role: extend `PlanContext.VrInputs` with first-cycle bootstrap fields.
- Modify: `src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java`  
  Role: pass extended VR inputs into `VrPosition`.
- Modify: `src/main/java/com/kista/domain/strategy/VrStrategy.java`  
  Role: generate first-cycle bootstrap LOC orders or existing ladder orders.
- Create: `src/main/java/com/kista/application/service/trading/TradingDayCounter.java`  
  Role: count remaining open market days from trade date through due date.
- Modify: `src/main/java/com/kista/application/service/trading/CycleOrderComputer.java`  
  Role: identify VR first cycle, due date, remaining trading days, and pass bootstrap inputs.
- Modify: `src/main/java/com/kista/application/service/trading/VrCycleRolloverService.java`  
  Role: allow recurring bootstrap cycles to continue with `V=0` when due-date buy failed.
- Modify tests:
  `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`,
  `src/test/java/com/kista/application/service/strategy/VrStrategyLifecycleTest.java`,
  `src/test/java/com/kista/domain/strategy/VrStrategyTypeTest.java`,
  `src/test/java/com/kista/application/service/trading/CycleOrderComputerTest.java`,
  `src/test/java/com/kista/application/service/trading/VrCycleRolloverServiceTest.java`.
- Modify docs:
  `docs/agents/architecture.md`,
  `docs/agents/constraints.md`.

---

### Task 1: VR Registration Validation And First-Cycle PoolLimit

**Files:**
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java`
- Modify: `src/main/java/com/kista/application/service/strategy/VrStrategyLifecycle.java`
- Test: `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`
- Test: `src/test/java/com/kista/application/service/strategy/VrStrategyLifecycleTest.java`

**Interfaces:**
- Consumes: `RegisterStrategyCommand.initialValue()`, `initialUsdDeposit()`, `intervalWeeks()`, `bandWidth()`, `recurringAmount()`.
- Produces: registration policy where nullable money values are treated as zero and first-cycle `poolLimit = (initialValue + initialUsdDeposit) * poolLimitRate`.

- [ ] **Step 1: Add failing registration validation tests**

Add these cases to `StrategyServiceTest`:

```java
@Test
@DisplayName("VR 적립식은 초기 V와 초기 시드가 모두 0이어도 등록 가능")
void register_vr_recurringDeposit_allowsZeroInitialValueAndSeed() {
    RegisterStrategyCommand cmd = new RegisterStrategyCommand(
            Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
            BigDecimal.ZERO, 2, new BigDecimal("15.00"), 200);
    Account account = ownerAccount();
    UUID vrStrategyId = UUID.randomUUID();
    UUID vrCycleId = UUID.randomUUID();
    Strategy savedVrStrategy = new Strategy(vrStrategyId, ACCOUNT_ID, Strategy.Type.VR,
            Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE);
    StrategyCycle savedCycle = new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
            BigDecimal.ZERO, null, LocalDate.now(), null, null, null);
    CyclePosition savedPosition = new CyclePosition(UUID.randomUUID(), vrCycleId,
            BigDecimal.ZERO, null, null, 0, null, null);
    StrategyCycleVrDetail savedCycleVr = new StrategyCycleVrDetail(
            vrCycleId, BigDecimal.ZERO, 10, new BigDecimal("0.00"));

    when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(account);
    when(strategyPort.existsByAccountIdAndTicker(ACCOUNT_ID, Strategy.Ticker.TQQQ)).thenReturn(false);
    when(userPort.findByIdOrThrow(USER_ID)).thenReturn(activeUser());
    when(userSettingsPort.findOrDefault(USER_ID)).thenReturn(UserSettings.defaultFor(USER_ID));
    when(registry.require(account, MarginPort.class)).thenReturn(marginPort);
    when(marginPort.getUsdBuyableAmount(account)).thenReturn(new BigDecimal("5000"));
    when(strategyPort.findByAccountId(ACCOUNT_ID)).thenReturn(List.of());
    when(strategyPort.save(any(Strategy.class))).thenReturn(savedVrStrategy);
    when(strategyCyclePort.save(any(StrategyCycle.class))).thenReturn(savedCycle);
    when(cyclePositionPort.save(any(CyclePosition.class))).thenReturn(savedPosition);
    when(registry.require(account, LiveBalancePort.class)).thenReturn(liveBalancePort);
    when(liveBalancePort.getLiveBalance(account, Strategy.Ticker.TQQQ))
            .thenReturn(new AccountBalance(0, null, BigDecimal.ZERO));
    when(strategyCycleVrPort.save(any(StrategyCycleVrDetail.class))).thenReturn(savedCycleVr);

    StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

    assertThat(result.vr()).isNotNull();
    assertThat(result.vr().value()).isEqualByComparingTo("0");
    assertThat(result.vr().poolLimit()).isEqualByComparingTo("0.00");
}

@Test
@DisplayName("VR 거치식과 인출식은 초기 V와 초기 시드가 모두 0이면 등록 불가")
void register_vr_nonDeposit_requiresInitialValueOrSeed() {
    RegisterStrategyCommand hold = new RegisterStrategyCommand(
            Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
            BigDecimal.ZERO, 2, new BigDecimal("15.00"), 0);
    RegisterStrategyCommand withdraw = new RegisterStrategyCommand(
            Strategy.Type.VR, null, BigDecimal.ZERO, null, 20,
            BigDecimal.ZERO, 2, new BigDecimal("15.00"), -100);

    when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

    assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, hold))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
    assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, withdraw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
}

@Test
@DisplayName("VR 인출식은 초기 자산이 월 인출액의 100배 이상이어야 등록 가능")
void register_vr_withdrawal_requiresMinimumInitialAssets() {
    RegisterStrategyCommand cmd = new RegisterStrategyCommand(
            Strategy.Type.VR, null, new BigDecimal("1000"), null, 20,
            new BigDecimal("1000"), 2, new BigDecimal("15.00"), -100);

    when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

    assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, cmd))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("인출식 VR 전략의 초기 자산");
}
```

- [ ] **Step 2: Add failing lifecycle poolLimit test**

Add this case to `VrStrategyLifecycleTest`:

```java
@Test
@DisplayName("saveInitialCycleDetail() 첫 사이클 poolLimit은 초기 V와 초기 시드 합계 기준")
void saveInitialCycleDetail_usesInitialValuePlusSeedForFirstPoolLimit() {
    UUID cycleId = UUID.randomUUID();
    StrategyVrDetail vrDetail = new StrategyVrDetail(UUID.randomUUID(), 4, new BigDecimal("15.00"), 0);
    when(strategyCycleVrPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

    StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
            cycleId, BigDecimal.ZERO, new BigDecimal("10000"), vrDetail);

    assertThat(result.poolLimit()).isEqualByComparingTo("5000.00");
}
```

- [ ] **Step 3: Run RED tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.application.service.strategy.VrStrategyLifecycleTest'
```

Expected: FAIL because current validation requires both `initialValue` and `initialUsdDeposit` to be greater than zero and first poolLimit uses only seed.

- [ ] **Step 4: Implement normalized validation and first poolLimit**

Change `StrategyService.validateVrCommand()` to:

```java
private void validateVrCommand(RegisterStrategyCommand cmd) {
    if (cmd.intervalWeeks() == null || cmd.intervalWeeks() <= 0) {
        throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
    }
    if (cmd.bandWidth() == null || cmd.bandWidth().signum() <= 0) {
        throw new IllegalArgumentException("VR 전략의 밴드 폭(bandWidth)은 0보다 커야 합니다");
    }

    BigDecimal initialValue = normalizeMoney(cmd.initialValue());
    BigDecimal initialUsdDeposit = normalizeMoney(cmd.initialUsdDeposit());
    int recurringAmount = cmd.recurringAmount() != null ? cmd.recurringAmount() : 0;
    BigDecimal initialAssets = initialValue.add(initialUsdDeposit);

    if (recurringAmount <= 0 && initialAssets.signum() <= 0) {
        throw new IllegalArgumentException("VR 거치식/인출식은 초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
    }
    if (recurringAmount < 0) {
        BigDecimal required = BigDecimal.valueOf(Math.abs((long) recurringAmount))
                .multiply(BigDecimal.valueOf(100))
                .multiply(BigDecimal.valueOf(4))
                .divide(BigDecimal.valueOf(cmd.intervalWeeks()), 2, RoundingMode.HALF_UP);
        if (initialAssets.compareTo(required) < 0) {
            throw new IllegalArgumentException("인출식 VR 전략의 초기 자산은 " + required + " 이상이어야 합니다");
        }
    }
}

private BigDecimal normalizeMoney(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
}
```

Add `java.math.RoundingMode` import to `StrategyService`.

Change `VrStrategyLifecycle.saveInitialCycleDetail()` to:

```java
StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                             BigDecimal initialValue, StrategyVrDetail vrDetail) {
    BigDecimal initialPool = initialUsdDeposit != null ? initialUsdDeposit : BigDecimal.ZERO;
    BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
    BigDecimal initialAssets = initialPool.add(initialV);
    BigDecimal poolLimit = initialAssets
            .multiply(vrDetail.poolLimitRate())
            .setScale(2, RoundingMode.HALF_UP);
    return strategyCycleVrPort.save(
            new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradient(), poolLimit));
}
```

- [ ] **Step 5: Run GREEN tests**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.application.service.strategy.VrStrategyLifecycleTest'
```

Expected: PASS.

- [ ] **Step 6: Commit task**

Run:

```bash
git add src/main/java/com/kista/application/service/strategy/StrategyService.java \
  src/main/java/com/kista/application/service/strategy/VrStrategyLifecycle.java \
  src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java \
  src/test/java/com/kista/application/service/strategy/VrStrategyLifecycleTest.java
git commit -m "feat(vr): 초기 자산 기반 등록 검증 반영"
```

---

### Task 2: First-Cycle Bootstrap LOC Orders

**Files:**
- Modify: `src/main/java/com/kista/domain/model/strategy/VrPosition.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrStrategy.java`
- Test: `src/test/java/com/kista/domain/strategy/VrStrategyTypeTest.java`

**Interfaces:**
- Produces: `VrPosition` fields for `firstCycle`, `cycleDue`, `remainingTradingDays`, and `recurringAmount`.
- Produces: first-cycle LOC buy/sell bootstrap orders before existing ladder order fallback.

- [ ] **Step 1: Add failing bootstrap order tests**

Add these tests to `VrStrategyTypeTest`:

```java
@Test
@DisplayName("첫 사이클 V만 있으면 poolLimit을 남은 거래일로 나눠 LOC 매도한다")
void firstCycle_valueOnly_sellsPoolLimitAcrossRemainingDays() {
    AccountBalance balance = new AccountBalance(10, new BigDecimal("100"), BigDecimal.ZERO);
    VrPosition position = new VrPosition(
            balance, new BigDecimal("10000"), new BigDecimal("15.00"),
            new BigDecimal("5000.00"), BigDecimal.ZERO,
            true, false, 10, 0);

    List<Order> sells = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), TODAY)
            .stream().filter(o -> o.direction() == SELL).toList();

    assertThat(sells).hasSize(1);
    assertThat(sells.getFirst().orderType()).isEqualTo(Order.OrderType.LOC);
    assertThat(sells.getFirst().timing()).isEqualTo(Order.OrderTiming.AT_CLOSE);
    assertThat(sells.getFirst().price()).isEqualByComparingTo("90.00");
    assertThat(sells.getFirst().quantity()).isEqualTo(5);
}

@Test
@DisplayName("첫 사이클 시드만 있으면 poolLimit을 남은 거래일로 나눠 LOC 매수한다")
void firstCycle_seedOnly_buysPoolLimitAcrossRemainingDays() {
    AccountBalance balance = new AccountBalance(0, null, new BigDecimal("10000"));
    VrPosition position = new VrPosition(
            balance, BigDecimal.ZERO, new BigDecimal("15.00"),
            new BigDecimal("5000.00"), BigDecimal.ZERO,
            true, false, 10, 0);

    List<Order> buys = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), TODAY)
            .stream().filter(o -> o.direction() == BUY).toList();

    assertThat(buys).hasSize(1);
    assertThat(buys.getFirst().orderType()).isEqualTo(Order.OrderType.LOC);
    assertThat(buys.getFirst().timing()).isEqualTo(Order.OrderTiming.AT_CLOSE);
    assertThat(buys.getFirst().price()).isEqualByComparingTo("110.00");
    assertThat(buys.getFirst().quantity()).isEqualTo(4);
}

@Test
@DisplayName("적립식 첫 사이클은 due date 당일에 recurringAmount만큼 LOC 매수한다")
void firstCycle_recurringOnly_dueDate_buysRecurringAmount() {
    AccountBalance balance = new AccountBalance(0, null, BigDecimal.ZERO);
    VrPosition position = new VrPosition(
            balance, BigDecimal.ZERO, new BigDecimal("15.00"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            true, true, 1, 200);

    List<Order> buys = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), TODAY)
            .stream().filter(o -> o.direction() == BUY).toList();

    assertThat(buys).hasSize(1);
    assertThat(buys.getFirst().orderType()).isEqualTo(Order.OrderType.LOC);
    assertThat(buys.getFirst().timing()).isEqualTo(Order.OrderTiming.AT_CLOSE);
    assertThat(buys.getFirst().price()).isEqualByComparingTo("110.00");
    assertThat(buys.getFirst().quantity()).isEqualTo(1);
}

@Test
@DisplayName("적립식 첫 사이클은 due date 전에는 주문을 만들지 않는다")
void firstCycle_recurringOnly_beforeDueDate_noOrders() {
    AccountBalance balance = new AccountBalance(0, null, BigDecimal.ZERO);
    VrPosition position = new VrPosition(
            balance, BigDecimal.ZERO, new BigDecimal("15.00"),
            BigDecimal.ZERO, BigDecimal.ZERO,
            true, false, 5, 200);

    List<Order> orders = strategy.buildOrders(position, TQQQ, new BigDecimal("100.00"), TODAY);

    assertThat(orders).isEmpty();
}
```

- [ ] **Step 2: Run RED test**

Run:

```bash
./gradlew test --tests 'com.kista.domain.strategy.VrStrategyTypeTest'
```

Expected: FAIL because `VrPosition` does not yet have bootstrap fields.

- [ ] **Step 3: Extend `VrPosition`**

Change the record signature to:

```java
public record VrPosition(
        AccountBalance balance,
        BigDecimal value,
        BigDecimal bandWidth,
        BigDecimal poolLimit,
        BigDecimal poolUsed,
        boolean firstCycle,
        boolean cycleDue,
        int remainingTradingDays,
        int recurringAmount
) {
```

Add a compact constructor:

```java
public VrPosition {
    value = value != null ? value : BigDecimal.ZERO;
    poolLimit = poolLimit != null ? poolLimit : BigDecimal.ZERO;
    poolUsed = poolUsed != null ? poolUsed : BigDecimal.ZERO;
    remainingTradingDays = Math.max(remainingTradingDays, 1);
}
```

Update all existing tests and production call sites to pass `false, false, 1, 0` for non-bootstrap cases.

- [ ] **Step 4: Add bootstrap branch to `VrStrategy`**

At the top of `buildOrders()`, before ladder orders:

```java
if (position.firstCycle()) {
    List<Order> bootstrap = buildBootstrapOrders(position, ticker, currentPrice, tradeDate);
    if (bootstrap != null) return bootstrap;
}
```

Add these helpers:

```java
private List<Order> buildBootstrapOrders(VrPosition position, Strategy.Ticker ticker,
                                         BigDecimal currentPrice, LocalDate tradeDate) {
    if (currentPrice == null || currentPrice.signum() <= 0) return List.of();
    boolean hasValue = position.value().signum() > 0;
    boolean hasPool = position.pool().signum() > 0;

    if (hasValue && !hasPool) {
        return buildDailyLocOrder(position.poolLimit(), position.remainingTradingDays(),
                currentPrice.multiply(new BigDecimal("0.90")).setScale(2, HALF_UP),
                ticker, tradeDate, SELL);
    }
    if (!hasValue && hasPool) {
        return buildDailyLocOrder(position.poolLimit(), position.remainingTradingDays(),
                currentPrice.multiply(new BigDecimal("1.10")).setScale(2, HALF_UP),
                ticker, tradeDate, BUY);
    }
    if (!hasValue && !hasPool && position.recurringAmount() > 0) {
        if (!position.cycleDue()) return List.of();
        return buildDailyLocOrder(BigDecimal.valueOf(position.recurringAmount()), 1,
                currentPrice.multiply(new BigDecimal("1.10")).setScale(2, HALF_UP),
                ticker, tradeDate, BUY);
    }
    return null;
}

private List<Order> buildDailyLocOrder(BigDecimal totalBudget, int remainingTradingDays,
                                       BigDecimal price, Strategy.Ticker ticker,
                                       LocalDate tradeDate, Order.OrderDirection direction) {
    BigDecimal dailyBudget = totalBudget.divide(BigDecimal.valueOf(Math.max(remainingTradingDays, 1)), 2, HALF_UP);
    int quantity = dailyBudget.divide(price, 0, java.math.RoundingMode.DOWN).intValue();
    if (quantity <= 0) return List.of();
    return List.of(Order.planned(tradeDate, ticker, Order.OrderType.LOC, direction, quantity, price, Order.OrderTiming.AT_CLOSE));
}
```

- [ ] **Step 5: Run GREEN test**

Run:

```bash
./gradlew test --tests 'com.kista.domain.strategy.VrStrategyTypeTest'
```

Expected: PASS.

- [ ] **Step 6: Commit task**

Run:

```bash
git add src/main/java/com/kista/domain/model/strategy/VrPosition.java \
  src/main/java/com/kista/domain/strategy/VrStrategy.java \
  src/test/java/com/kista/domain/strategy/VrStrategyTypeTest.java
git commit -m "feat(vr): 첫 사이클 LOC 부트스트랩 주문 추가"
```

---

### Task 3: Wire Remaining Trading Days Into VR Planning

**Files:**
- Create: `src/main/java/com/kista/application/service/trading/TradingDayCounter.java`
- Modify: `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/application/service/trading/CycleOrderComputer.java`
- Test: `src/test/java/com/kista/application/service/trading/CycleOrderComputerTest.java`

**Interfaces:**
- Consumes: `MarketCalendarPort.isMarketOpen(LocalDate)`.
- Produces: `PlanContext.VrInputs(value, bandWidth, poolLimit, poolUsed, currentPrice, firstCycle, cycleDue, remainingTradingDays, recurringAmount)`.

- [ ] **Step 1: Add failing CycleOrderComputer test**

Add a test to `CycleOrderComputerTest` that stubs a VR current cycle with `startDate=2026-07-06`, `intervalWeeks=2`, `tradeDate=2026-07-10`, and verifies the captured `VrPosition` has `firstCycle=true`, `cycleDue=false`, `remainingTradingDays > 0`, `recurringAmount=200`.

Use an `ArgumentCaptor<VrPosition>` around:

```java
verify(vrStrategy).buildOrders(captor.capture(), eq(Ticker.TQQQ), eq(CURRENT_PRICE), any(LocalDate.class));
VrPosition captured = captor.getValue();
assertThat(captured.firstCycle()).isTrue();
assertThat(captured.cycleDue()).isFalse();
assertThat(captured.remainingTradingDays()).isGreaterThan(0);
assertThat(captured.recurringAmount()).isEqualTo(200);
```

- [ ] **Step 2: Run RED test**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.CycleOrderComputerTest'
```

Expected: FAIL because `VrInputs` does not carry bootstrap fields.

- [ ] **Step 3: Create `TradingDayCounter`**

Create:

```java
package com.kista.application.service.trading;

import com.kista.domain.port.out.MarketCalendarPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// VR 첫 사이클 분할 주문에 필요한 남은 미국 거래일 수 계산
@Component
@RequiredArgsConstructor
class TradingDayCounter {

    private final MarketCalendarPort marketCalendarPort; // 미국 시장 개장일 조회

    int countOpenDaysInclusive(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) return 1;
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (marketCalendarPort.isMarketOpen(d)) count++;
        }
        return Math.max(count, 1);
    }
}
```

- [ ] **Step 4: Extend `VrInputs`**

In `CycleOrderStrategy.PlanContext.VrInputs`, add:

```java
boolean firstCycle,
boolean cycleDue,
int remainingTradingDays,
int recurringAmount
```

Update all constructors in tests and production.

- [ ] **Step 5: Wire fields in `CycleOrderComputer` and `VrCycleOrderStrategy`**

Inject `TradingDayCounter` into `CycleOrderComputer`.

Inside VR input assembly:

```java
LocalDate dueDate = currentCycle.startDate().plusWeeks(vrDetail.intervalWeeks());
boolean firstCycle = currentCycle.startAmount() != null
        && strategyCycleVrPort.findByCycleId(currentCycle.id()).isPresent()
        && cyclePositionPort.findLatestByCycleId(currentCycle.id(), 2).size() <= 1;
boolean cycleDue = !tradeDate.isBefore(dueDate);
int remainingTradingDays = tradingDayCounter.countOpenDaysInclusive(tradeDate, dueDate);
vrInputs = new CycleOrderStrategy.PlanContext.VrInputs(
        cycleVr.value(), vrDetail.bandWidth(), cycleVr.poolLimit(), poolUsed, currentPrice,
        firstCycle, cycleDue, remainingTradingDays, vrDetail.recurringAmount());
```

In `VrCycleOrderStrategy`, pass the new fields into `VrPosition`.

- [ ] **Step 6: Run GREEN test**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.CycleOrderComputerTest'
```

Expected: PASS.

- [ ] **Step 7: Commit task**

Run:

```bash
git add src/main/java/com/kista/application/service/trading/TradingDayCounter.java \
  src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java \
  src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java \
  src/main/java/com/kista/application/service/trading/CycleOrderComputer.java \
  src/test/java/com/kista/application/service/trading/CycleOrderComputerTest.java
git commit -m "feat(vr): 첫 사이클 거래일 계산 배선"
```

---

### Task 4: Rollover For Recurring Bootstrap Failure

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/VrCycleRolloverService.java`
- Test: `src/test/java/com/kista/application/service/trading/VrCycleRolloverServiceTest.java`

**Interfaces:**
- Consumes: current `cycleVr.value()`, `detail.recurringAmount()`, `postBalance.holdings()`, `closingPrice`.
- Produces: recurring bootstrap cycle can roll to a new `V=0` cycle when no buy filled.

- [ ] **Step 1: Add failing rollover test**

Add a test to `VrCycleRolloverServiceTest`:

```java
@Test
@DisplayName("적립식 bootstrap 매수 실패로 V가 0이면 새 사이클도 V=0으로 이어간다")
void rollIfDue_recurringBootstrapFailed_rollsWithZeroValue() {
    StrategyCycleVrDetail zeroCycleVr = new StrategyCycleVrDetail(
            CYCLE_ID, BigDecimal.ZERO, 10, BigDecimal.ZERO);
    StrategyVrDetail depositDetail = new StrategyVrDetail(
            STRATEGY_VERSION_ID, 2, new BigDecimal("15.00"), 200);
    AccountBalance postBalance = new AccountBalance(0, null, BigDecimal.ZERO);

    when(strategyCycleVrPort.findByCycleId(CYCLE_ID)).thenReturn(Optional.of(zeroCycleVr));
    when(strategyVrDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID)).thenReturn(Optional.of(depositDetail));

    service.rollIfDue(ctx, postBalance, new BigDecimal("100.00"), CYCLE_START.plusWeeks(2));

    verify(strategyCyclePort).markEnded(eq(CYCLE_ID), eq(BigDecimal.ZERO), eq(CYCLE_START.plusWeeks(2)));
    verify(cycleSnapshotCreator).createVrCycleAndSnapshot(
            eq(STRATEGY_ID), eq(STRATEGY_VERSION_ID), eq(postBalance), eq(new BigDecimal("100.00")),
            eq(BigDecimal.ZERO), eq(10), eq(BigDecimal.ZERO));
}
```

- [ ] **Step 2: Run RED test**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.VrCycleRolloverServiceTest'
```

Expected: FAIL because current `newValue <= 0` always blocks rollover.

- [ ] **Step 3: Add recurring bootstrap exception**

In `VrCycleRolloverService`, before the `newValue <= 0` block:

```java
boolean recurringBootstrapWithoutValue = detail.recurringAmount() > 0
        && cycleVr.value().signum() == 0
        && postBalance.holdings() == 0;
if (recurringBootstrapWithoutValue) {
    newValue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
}
```

Change the block to:

```java
if (newValue.compareTo(BigDecimal.ZERO) <= 0 && !recurringBootstrapWithoutValue) {
    log.warn("[strategyId={}] VR 롤오버 보류 — V′≤0 (newValue={})", strategy.id(), newValue);
    notifyPort.notifyError(new IllegalStateException(
            "VR V′≤0 — 롤오버 보류: strategyId=" + strategy.id() + " newValue=" + newValue));
    userNotificationPort.notifyError(ctx.user(),
            new IllegalStateException("VR V′≤0 — 설정 조정 필요: strategyId=" + strategy.id()));
    return;
}
```

Keep `newPoolLimit = postBalance.usdDeposit() * poolLimitRate` unchanged.

- [ ] **Step 4: Run GREEN test**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.trading.VrCycleRolloverServiceTest'
```

Expected: PASS.

- [ ] **Step 5: Commit task**

Run:

```bash
git add src/main/java/com/kista/application/service/trading/VrCycleRolloverService.java \
  src/test/java/com/kista/application/service/trading/VrCycleRolloverServiceTest.java
git commit -m "feat(vr): 적립식 bootstrap 실패 롤오버 허용"
```

---

### Task 5: Docs And Verification

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`

**Interfaces:**
- Produces: project docs describing the new VR initial bootstrap policy.

- [ ] **Step 1: Update docs**

In `docs/agents/architecture.md`, update `VR 전략 패턴` to mention:

```text
첫 사이클 bootstrap:
- initialValue는 기존 TQQQ 평가금, initialUsdDeposit은 초기 USD pool.
- 적립식은 둘 다 0이어도 등록 가능하며 due date 당일 recurringAmount LOC 매수.
- V만 있으면 poolLimit 금액을 남은 거래일로 나눠 LOC 매도.
- pool만 있으면 poolLimit 금액을 남은 거래일로 나눠 LOC 매수.
- 첫 사이클 이후 poolLimit는 기존처럼 USD pool 기준.
```

In `docs/agents/constraints.md`, update `VR 공식` with the same registration validation rules.

- [ ] **Step 2: Run focused verification**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest' \
  --tests 'com.kista.application.service.strategy.VrStrategyLifecycleTest' \
  --tests 'com.kista.domain.strategy.VrStrategyTypeTest' \
  --tests 'com.kista.application.service.trading.CycleOrderComputerTest' \
  --tests 'com.kista.application.service.trading.VrCycleRolloverServiceTest'
```

Expected: PASS.

- [ ] **Step 3: Run compile verification**

Run:

```bash
./gradlew compileJava
```

Expected: PASS.

- [ ] **Step 4: Commit docs**

Run:

```bash
git add docs/agents/architecture.md docs/agents/constraints.md
git commit -m "docs(vr): 초기 bootstrap 정책 문서화"
```

---

## Known Risks

- First-cycle bootstrap “firstCycle” detection must be stable. If relying on position count is brittle, add an explicit field to `StrategyCycleVrDetail` in a later schema change, but do not add schema in this pass unless tests prove the inference unsafe.
- LOC quantity is integer shares. If daily budget is smaller than one share at the chosen LOC price, no order is generated for that day.
- Existing `BuyOrderPriceCapper` should not rewrite bootstrap LOC orders unexpectedly; verify `TradingOrderExecutor` only applies existing cap logic where intended.
- `MarketCalendarPort.isMarketOpen()` returns false when year data is missing. `TradingDayCounter` falls back to at least 1 day, so bootstrap does not divide by zero.
