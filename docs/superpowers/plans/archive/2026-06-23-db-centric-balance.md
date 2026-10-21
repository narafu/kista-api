# DB 중심 잔고 설계 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 전략 계산은 DB(`cycle_position`) 잔고, 주문 생성 직전 유효성 검사는 live API 잔고로 통일하고, 브로커 접수 단계에는 자체 잔고 체크를 두지 않는다.

**Architecture:** `BrokerAccountRouter.getLiveBalance()`를 단일 live 조회 창구로 추가한다. `TradingService.loadBalance()`에서 Toss live 분기를 제거해 KIS·Toss 모두 DB 경유로 통일하고, 주문 저장 직전(`planSaveAndPlaceSells`, `planAndSaveOrders`)에 live 잔고 부족 시 저장 건너뜀·알림으로 교체한다. `ManualTradingService`는 기존 BUY 예수금 체크에 SELL 보유수량 체크를 추가한다.

**Tech Stack:** Java 21, Spring Boot 3, Mockito 5, Next.js 16 (TypeScript)

---

## 파일 맵

| 파일 | 변경 내용 |
|---|---|
| `application/service/trading/BrokerAccountRouter.java` | `getLiveBalance()` 추가, `getUsdDeposit()` 위임으로 리팩토링 |
| `application/service/trading/TradingService.java` | `tosAccountPort` 제거, `brokerAccountRouter` 추가, `loadBalance()` 수정, `isLiveBalanceInsufficient()` 추가, `planSaveAndPlaceSells()` / `planAndSaveOrders()` 수정 |
| `application/service/trading/ManualTradingService.java` | `getLiveBalance()` 1회 호출로 통합, SELL 보유수량 체크 추가 |
| `application/service/trading/TradingServiceTest.java` | `KisAccountPort` mock 추가, setUp 수정, 기존 insufficientBalance 테스트 동작 수정, 신규 테스트 추가 |
| `widgets/account-detail/AccountSummaryCard.tsx` | props `kisUsdDeposit` → `usdDeposit`, `kisPosEvalUsd` → `posEvalUsd` |
| `widgets/account-detail/AccountDetailTabs.tsx` | 동일 props 이름 변경 |
| `app/(main)/accounts/[id]/page.tsx` | 변수명 변경 |

---

## Task 1: `BrokerAccountRouter` — `getLiveBalance()` 추가

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/BrokerAccountRouter.java`

- [ ] **Step 1: `getLiveBalance()` 추가 및 `getUsdDeposit()` 위임 리팩토링**

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisAccountPort;
import com.kista.domain.port.out.TosAccountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// account.broker() 기반으로 KIS/Toss 잔고 조회 라우터
@Component
@RequiredArgsConstructor
class BrokerAccountRouter {

    private final KisAccountPort kisAccountPort;
    private final TosAccountPort tosAccountPort;

    // live 잔고 조회 — holdings + usdDeposit (주문 생성 직전 유효성 검사 전용)
    AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return switch (account.broker()) {
            case KIS  -> kisAccountPort.getBalance(account, ticker);
            case TOSS -> tosAccountPort.getBalance(account, ticker);
        };
    }

    // 계좌의 USD 주문가능금액(실잔고) 조회
    BigDecimal getUsdDeposit(Account account, Ticker ticker) {
        return getLiveBalance(account, ticker).usdDeposit();
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
bash gradlew compileJava
```
Expected: BUILD SUCCESSFUL

---

## Task 2: `TradingService` — `tosAccountPort` 제거, `brokerAccountRouter` 추가, `loadBalance()` 수정

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

- [ ] **Step 1: `TradingService` 필드 교체 및 `loadBalance()` 수정**

`TradingService.java` 에서 아래 필드를 교체한다.

```java
// 제거
private final TosAccountPort tosAccountPort;               // Toss live 잔고 조회 (holdings + usdDeposit)

// 추가 (기존 필드 목록에서 balanceLoader 바로 아래에 위치)
private final BrokerAccountRouter brokerAccountRouter;     // live 잔고 검사 전용 (주문 저장 직전 유효성 확인)
```

`TradingService` import에서 `TosAccountPort` 제거, `BrokerAccountRouter`는 같은 패키지라 import 불필요.

`loadBalance()` 메서드 (현재 line 158~166) 수정:

```java
// 잔고 로드 — KIS·Toss 모두 cycle_position DB 이력 사용 (전략 공식 기준)
private AccountBalance loadBalance(Strategy strategy, Account account) {
    AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
    log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}",
            account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());
    return balance;
}
```

필드 주석도 수정:
```java
private final TradingBalanceLoader balanceLoader;          // 잔고 로드 헬퍼 — KIS·Toss 모두 DB 이력(cycle_position)
```

- [ ] **Step 2: `TradingServiceTest` `setUp()` 수정 — `KisAccountPort` mock 추가, 생성자 교체**

`TradingServiceTest.java`에 `@Mock KisAccountPort kisAccountPort;` 추가 (기존 `@Mock TosAccountPort tosAccountPort;` 아래):

```java
@Mock KisAccountPort kisAccountPort;
```

`import com.kista.domain.port.out.KisAccountPort;` 추가.

`setUp()` 내 `BrokerAccountRouter` 인스턴스 생성 및 `TradingService` 생성자 교체:

```java
// setUp() 내 기존 service = new TradingService(...) 위에 추가
BrokerAccountRouter brokerAccountRouter = new BrokerAccountRouter(kisAccountPort, tosAccountPort);

// KIS 계좌 기반 테스트 — live 잔고 부족 체크 시 kisAccountPort.getBalance() 호출
// lenient: live 체크가 필요 없는 테스트(이미 PLANNED 존재·휴장일 등)는 미호출
lenient().when(kisAccountPort.getBalance(eq(ACCOUNT), any()))
        .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));

// 기존 service = new TradingService(...) 수정
service = new TradingService(
        marketCalendarPort, notifyPort, userNotificationPort,
        orderPort, privacyTradePort, strategyCyclePort,
        balanceLoader, brokerAccountRouter, orderComputer, orderPlanner,
        priceFetcher, orderExecutor, reporter);
```

- [ ] **Step 3: 기존 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.TradingServiceTest"
```
Expected: BUILD SUCCESSFUL (기존 테스트 전부 GREEN)

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/BrokerAccountRouter.java
git add src/main/java/com/kista/application/service/trading/TradingService.java
git add src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): loadBalance DB 통일 — Toss live 분기 제거, BrokerAccountRouter live 검사 창구 추가

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `TradingService` — `isLiveBalanceInsufficient()` 추가 + `planSaveAndPlaceSells()` 수정

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

- [ ] **Step 1: 기존 insufficientBalance 테스트 동작 수정 (failing 상태 만들기)**

`TradingServiceTest`에서 `placeOpenOrders_insufficientBalance_notifiesUserButStillSavesOrders` 테스트를 찾아 아래와 같이 수정한다.

**테스트명 변경:** `placeOpenOrders_insufficientBalance_notifiesUserAndSkipsSave`

테스트 내 `kisAccountPort.getBalance()` stub 추가 — 잔고 부족 상황:
```java
// live 잔고 부족: usdDeposit=$10이고 BUY 총액=$50,000 → 부족
when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
        .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));
```

기존 `verify(orderPort).saveAll(anyList());` → **`verify(orderPort, never()).saveAll(any());`** 로 교체.

전체 수정된 테스트:
```java
@Test
void placeOpenOrders_insufficientBalance_notifiesUserAndSkipsSave() throws InterruptedException {
    BigDecimal prevClose = new BigDecimal("19.00");
    Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
            Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
            Order.OrderStatus.PLANNED, null, null, null);

    when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
            .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
    when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(LOW_HISTORY));
    when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
            .thenReturn(List.of(bigBuy));
    when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
            .thenReturn(List.of());
    // live 잔고 부족: BUY $50,000 > usdDeposit $10
    when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
            .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

    service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

    // 사용자 알림 발송
    verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
    // 주문 저장 건너뜀
    verify(orderPort, never()).saveAll(any());
    // KIS 접수 없음
    verify(kisOrderPort, never()).place(any(), any());
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.TradingServiceTest.placeOpenOrders_insufficientBalance_notifiesUserAndSkipsSave"
```
Expected: FAIL (아직 구현 전)

- [ ] **Step 3: `isLiveBalanceInsufficient()` 헬퍼 추가 + `planSaveAndPlaceSells()` 수정**

`TradingService` 끝부분(private 헬퍼 영역)에 추가:
```java
// BUY 예수금 또는 SELL 보유수량이 live 잔고 대비 부족한지 판단
private static boolean isLiveBalanceInsufficient(List<Order> orders, AccountBalance live) {
    BigDecimal buyTotal = orders.stream()
            .filter(o -> o.direction() == Order.OrderDirection.BUY)
            .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    int sellTotal = orders.stream()
            .filter(o -> o.direction() == Order.OrderDirection.SELL)
            .mapToInt(Order::quantity).sum();
    return buyTotal.compareTo(live.usdDeposit()) > 0
            || sellTotal > live.holdings();
}
```

`planSaveAndPlaceSells()` 내 예수금 부족 확인 블록 (현재 line 286~295) 교체:

```java
// 기존 코드 (제거)
// List<Order> buyOrders = result.orders().stream()
//         .filter(o -> o.direction() == Order.OrderDirection.BUY).toList();
// if (!buyOrders.isEmpty() && !balance.isOrderValid(buyOrders)) {
//     log.warn("[{}] 예수금 부족 — 사용자 알람 후 주문 저장 진행", account.nickname());
//     userNotificationPort.notifyInsufficientBalance(user, account, strategy.type(), strategy.ticker());
// }
// orderPlanner.savePlannedOrders(result.orders(), account, currentCycle.id());

// 신규 코드
AccountBalance live = brokerAccountRouter.getLiveBalance(account, strategy.ticker());
if (isLiveBalanceInsufficient(result.orders(), live)) {
    log.warn("[{}] live 잔고 부족 — 주문 저장 건너뜀 (예수금 or 보유수량)", account.nickname());
    userNotificationPort.notifyInsufficientBalance(user, account, strategy.type(), strategy.ticker());
    return;
}

// 전체 PLANNED 저장
orderPlanner.savePlannedOrders(result.orders(), account, currentCycle.id());
```

- [ ] **Step 4: 수정된 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.TradingServiceTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: SELL 보유수량 부족 케이스 테스트 추가**

```java
@Test
void placeOpenOrders_insufficientHoldings_notifiesUserAndSkipsSave() throws InterruptedException {
    BigDecimal prevClose = new BigDecimal("19.00");
    // SELL 주문 100주 — live holdings=5주 → 부족
    Order bigSell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
            Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 100, new BigDecimal("22.00"),
            Order.OrderStatus.PLANNED, null, null, null);

    when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
            .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
    when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
    when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
            .thenReturn(List.of(bigSell));
    when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
            .thenReturn(List.of());
    // live holdings=5 < SELL 100주 → 부족
    when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
            .thenReturn(new AccountBalance(5, new BigDecimal("20.00"), new BigDecimal("10000.00")));

    service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

    verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
    verify(orderPort, never()).saveAll(any());
}
```

- [ ] **Step 6: 전체 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.TradingServiceTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java
git add src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(trading): 개장 스케쥴러 live 잔고 부족 시 주문 저장 건너뜀 (기존: 저장 진행)

BUY 예수금 부족 또는 SELL 보유수량 부족이면 알림만 발송하고 PLANNED 저장하지 않음.
isLiveBalanceInsufficient() 헬퍼로 BUY·SELL 조건 통합.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `TradingService.planAndSaveOrders()` — 마감 스케쥴러 live 잔고 체크 추가

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

- [ ] **Step 1: 실패 테스트 먼저 작성**

`TradingServiceTest`에 추가:
```java
@Test
void executeBatch_liveBalanceInsufficient_skipsOrderPlanAndNotifies() throws InterruptedException {
    // 마감 스케쥴러 plan 단계 — live 잔고 부족 시 PLANNED 저장 건너뜀
    BigDecimal prevClose = new BigDecimal("19.00");
    Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
            Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
            Order.OrderStatus.PLANNED, null, null, null);

    when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
    when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
            .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, prevClose)));
    when(kisPricePort.getPrices(anyList(), eq(ACCOUNT)))
            .thenReturn(Map.of(Ticker.SOXL, PRICE));
    when(cycleHistoryPort.findLatestByStrategyId(STRATEGY.id(), 1)).thenReturn(List.of(NORMAL_HISTORY));
    when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
            .thenReturn(List.of(bigBuy));
    // 오늘 주문 없음 → 신규 계산 진행
    when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
            .thenReturn(List.of());
    // live 잔고 부족: BUY $50,000 > usdDeposit $10
    when(kisAccountPort.getBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
            .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

    service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

    // 알림 발송
    verify(userNotificationPort).notifyInsufficientBalance(eq(USER), eq(ACCOUNT), eq(Strategy.Type.INFINITE), eq(Ticker.SOXL));
    // 저장 없음
    verify(orderPort, never()).saveAll(any());
    // 브로커 접수 없음
    verify(kisOrderPort, never()).place(any(), any());
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.TradingServiceTest.executeBatch_liveBalanceInsufficient_skipsOrderPlanAndNotifies"
```
Expected: FAIL

- [ ] **Step 3: `planAndSaveOrders()` 수정**

`planAndSaveOrders()` 내부에서 `User user = ctx.user();` 추가 (현재 없음):

```java
private CycleState planAndSaveOrders(BatchContext ctx, ...) {
    Strategy strategy = ctx.strategy();
    StrategyCycle currentCycle = ctx.currentCycle();
    Account account = ctx.account();
    User user = ctx.user();  // live 잔고 부족 알림용 추가
    // ...
```

`result == null` 체크 이후, `orderPlanner.savePlannedOrders()` 직전에 live 체크 삽입:

```java
CycleOrderComputer.ComputeResult result = orderComputer.computeUnlessSkipped(
        balance, strategy, prevClosePrice, today, currentCycle, privacyBase, account.nickname())
        .orElse(null);
if (result == null) return null;

// live 잔고 검사 — 부족 시 알림 후 저장 건너뜀
AccountBalance live = brokerAccountRouter.getLiveBalance(account, strategy.ticker());
if (isLiveBalanceInsufficient(result.orders(), live)) {
    log.warn("[{}] live 잔고 부족 — 마감 스케쥴러 plan 저장 건너뜀", account.nickname());
    userNotificationPort.notifyInsufficientBalance(user, account, strategy.type(), strategy.ticker());
    return null;
}

orderPlanner.savePlannedOrders(result.orders(), account, currentCycle.id());
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.TradingServiceTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java
git add src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(trading): 마감 스케쥴러 plan 단계 live 잔고 부족 시 저장 건너뜀

개장 스케쥴러와 동일한 정책 적용: live usdDeposit·holdings 부족 시 알림+skip.
브로커 접수(placeAll) 단계는 자체 체크 없이 브로커 거부에 위임.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `ManualTradingService` — SELL 보유수량 체크 추가

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/ManualTradingService.java`
- Create: `src/test/java/com/kista/application/service/trading/ManualTradingServiceTest.java`

- [ ] **Step 1: `ManualTradingServiceTest` 생성 (실패 테스트 먼저)**

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualTradingServiceTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock AccountPort accountPort;
    @Mock OrderPort orderPort;
    @Mock UserPort userPort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock TradingPriceFetcher priceFetcher;
    @Mock TradingBalanceLoader balanceLoader;
    @Mock CycleOrderComputer orderComputer;
    @Mock TradingOrderPlanner orderPlanner;
    @Mock BrokerAccountRouter brokerAccountRouter;

    ManualTradingService service;

    static final UUID REQUESTER_ID = UUID.randomUUID();
    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), REQUESTER_ID, "테스트계좌",
            "74420614", "key", "secret", "01", Account.Broker.KIS
    );
    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE, 20
    );
    static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), new BigDecimal("1000.00"), null,
            LocalDate.now(), null, null, null, StrategyCycle.SeedResolvedBy.BROKER_VERIFIED
    );
    static final User USER = new User(
            REQUESTER_ID, "kakao-1", "테스터", User.UserRole.USER,
            User.UserStatus.ACTIVE, null, null, NotificationChannel.NONE, null, null
    );
    // DB 잔고 — 전략 계산용
    static final AccountBalance DB_BALANCE = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("500.00"));

    @BeforeEach
    void setUp() {
        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                userPort, privacyTradePort, priceFetcher, balanceLoader,
                orderComputer, orderPlanner, brokerAccountRouter);

        when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), REQUESTER_ID)).thenReturn(ACCOUNT);
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(CYCLE));
        when(userPort.findByIdOrThrow(ACCOUNT.userId())).thenReturn(USER);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        when(balanceLoader.loadBalanceOrThrow(STRATEGY))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(DB_BALANCE, null));
        when(priceFetcher.fetchPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(java.util.Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("22.00"), new BigDecimal("20.00"))));
    }

    @Test
    void execute_insufficientSellHoldings_throwsManualTradingException() {
        // SELL 15주 — live holdings=10 → 부족
        Order sellOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_OPEN,
                Order.OrderDirection.SELL, 15, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);

        CycleOrderComputer.ComputeResult result =
                new CycleOrderComputer.ComputeResult(List.of(sellOrder), null);
        when(orderComputer.computeUnlessSkipped(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(result));
        // live holdings=10 < SELL 15주
        when(brokerAccountRouter.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessageContaining("보유 수량이 부족합니다");
    }

    @Test
    void execute_sufficientBalance_savesOrders() {
        Order buyOrder = new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE,
                Order.OrderDirection.BUY, 1, new BigDecimal("22.00"),
                Order.OrderStatus.PLANNED, null, null, null);

        CycleOrderComputer.ComputeResult result =
                new CycleOrderComputer.ComputeResult(List.of(buyOrder), null);
        when(orderComputer.computeUnlessSkipped(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(result));
        // live 충분: usdDeposit=$10,000 > BUY $22, holdings=10 (SELL 없음)
        when(brokerAccountRouter.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10000.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of(buyOrder));

        var orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPlanner).savePlannedOrders(anyList(), eq(ACCOUNT), eq(CYCLE.id()));
        assertThat(orders).hasSize(1);
    }
}
```

`import static org.assertj.core.api.Assertions.assertThat;` 추가.

- [ ] **Step 2: 테스트가 컴파일되는지 확인 (아직 실패해야 함)**

```bash
bash gradlew compileTestJava
```
Expected: BUILD SUCCESSFUL (컴파일은 성공, 로직은 미구현)

```bash
bash gradlew test --tests "com.kista.application.service.trading.ManualTradingServiceTest.execute_insufficientSellHoldings_throwsManualTradingException"
```
Expected: FAIL

- [ ] **Step 3: `ManualTradingService` 수정 — `getLiveBalance()` 통합 + SELL 체크 추가**

`ManualTradingService.java` 에서 예수금 부족 체크 블록 (현재 line 88~100) 교체:

```java
// live 잔고 1회 조회 — BUY 예수금·SELL 보유수량 모두 검사
AccountBalance liveBalance = brokerAccountRouter.getLiveBalance(account, strategy.ticker());

// 예수금 부족 체크: 신규 BUY 합계 > (실잔고 - 타 전략 당일 PLANNED BUY 합계)
BigDecimal newBuyTotal = result.orders().stream()
        .filter(o -> o.direction() == Order.OrderDirection.BUY)
        .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
if (newBuyTotal.compareTo(BigDecimal.ZERO) > 0) {
    BigDecimal otherBuyTotal = orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
    BigDecimal available = liveBalance.usdDeposit().subtract(otherBuyTotal);
    if (newBuyTotal.compareTo(available) > 0) {
        throw new ManualTradingException("예수금이 부족합니다");
    }
}

// 보유수량 부족 체크: SELL 수량 합계 > live holdings
int newSellTotal = result.orders().stream()
        .filter(o -> o.direction() == Order.OrderDirection.SELL)
        .mapToInt(Order::quantity).sum();
if (newSellTotal > liveBalance.holdings()) {
    throw new ManualTradingException("보유 수량이 부족합니다");
}
```

기존 `brokerAccountRouter.getUsdDeposit(...)` 호출 라인은 위 코드로 완전 대체되므로 제거.

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.trading.ManualTradingServiceTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/ManualTradingService.java
git add src/test/java/com/kista/application/service/trading/ManualTradingServiceTest.java
git commit -m "$(cat <<'EOF'
feat(trading): 바로 주문 SELL 보유수량 부족 체크 추가, getLiveBalance() 단일 호출로 통합

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: kista-ui props 이름 정리

**Files:**
- Modify: `widgets/account-detail/AccountSummaryCard.tsx`
- Modify: `widgets/account-detail/AccountDetailTabs.tsx`
- Modify: `app/(main)/accounts/[id]/page.tsx`

> 이 변경은 UI/API 동작에 영향 없음 — `/api/accounts/{id}/portfolio`는 이미 KIS·Toss 모두 지원.

- [ ] **Step 1: `AccountSummaryCard.tsx` props 이름 변경**

```tsx
interface Props {
  account: Account
  usdDeposit: number      // 변경: kisUsdDeposit → usdDeposit
  posEvalUsd: number      // 변경: kisPosEvalUsd → posEvalUsd
}

export function AccountSummaryCard({ account, usdDeposit, posEvalUsd }: Props) {
  // ...
  <KpiCard label="예수금(실계좌기준)" value={`$${fmtUsd(usdDeposit)}`} />
  <KpiCard label="평가금(실계좌기준)" value={`$${fmtUsd(posEvalUsd)}`} />
```

- [ ] **Step 2: `AccountDetailTabs.tsx` props 이름 변경**

```tsx
interface Props {
  account: Account
  strategies: Strategy[]
  usdDeposit: number      // 변경
  posEvalUsd: number      // 변경
}

export function AccountDetailTabs({ account, strategies: initialStrategies, usdDeposit, posEvalUsd }: Props) {
  // ...
  // AccountSummaryCard 사용처 2곳 (모바일/데스크탑) 모두 변경
  <AccountSummaryCard account={account} usdDeposit={usdDeposit} posEvalUsd={posEvalUsd} />
```

- [ ] **Step 3: `app/(main)/accounts/[id]/page.tsx` 변수명 변경**

```tsx
const usdDeposit = toNum(portfolioRaw?.summary?.usdDeposit)    // 변경: kisUsdDeposit → usdDeposit
const posEvalUsd = toNum(portfolioRaw?.summary?.posEvalUsd)    // 변경: kisPosEvalUsd → posEvalUsd

// AccountDetailTabs 전달
<AccountDetailTabs
  account={account}
  strategies={strategies}
  usdDeposit={usdDeposit}
  posEvalUsd={posEvalUsd}
/>
```

- [ ] **Step 4: TypeScript 타입 체크**

kista-ui 디렉토리에서:
```bash
npx tsc --noEmit
```
Expected: 오류 없음

- [ ] **Step 5: 커밋 (kista-ui 저장소)**

kista-ui 디렉토리에서:
```bash
git add widgets/account-detail/AccountSummaryCard.tsx
git add widgets/account-detail/AccountDetailTabs.tsx
git add app/\(main\)/accounts/\[id\]/page.tsx
git commit -m "$(cat <<'EOF'
refactor(account-detail): props 이름에서 kis 접두사 제거 (브로커 무관)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 전체 테스트 및 최종 커밋

- [ ] **Step 1: kista-api 전체 테스트**

```bash
bash gradlew test
```
Expected: BUILD SUCCESSFUL

실패 시 진단:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

- [ ] **Step 2: 컴파일 최종 확인**

```bash
bash gradlew compileJava
```
Expected: BUILD SUCCESSFUL
