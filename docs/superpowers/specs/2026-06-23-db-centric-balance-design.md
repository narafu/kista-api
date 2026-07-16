# DB 중심 잔고 설계 — 전략 계산 / 주문 유효성 / 실계좌 표시 분리

## 배경

현재 `TradingService.loadBalance()`가 KIS는 DB(`cycle_position`), Toss는 live API로 다르게 동작한다.
실계좌는 전략 외에도 다른 주식·매매가 존재할 수 있으므로, 전략 공식에 사용하는 잔고는 KISTA가
자체 기록한 DB 값을 사용해야 한다. live 잔고는 "지금 주문 가능한가?" 를 검증하는 용도로만 쓴다.

## 핵심 원칙

| 단계 | balance 출처 | 목적 |
|---|---|---|
| 전략 공식 계산 (avgPrice·holdings·usdDeposit) | **DB** (`cycle_position`) | 브로커 독립적 전략 연산 |
| 주문 생성 직전 유효성 검사 | **Live API** | 실제 예수금·보유수량 확인 |
| 브로커 접수 (주문 실행) | 없음 | 브로커 거부에 맡김 |

## 변경 사항

### kista-api

#### 1. `TradingService.loadBalance()` — Toss live 분기 제거

```java
// Before
AccountBalance balance = account.isToss()
    ? tosAccountPort.getBalance(account, strategy.ticker())
    : balanceLoader.loadBalanceOrThrow(strategy).balance();

// After
AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
```

- `TradingService`에서 `tosAccountPort` 의존성 제거
- `TradingService` 필드 주석 `\ 잔고 로드 헬퍼 (KIS: DB 이력 기반)` → 통일 반영으로 수정

#### 2. `BrokerAccountRouter.getLiveBalance()` 추가

```java
// 신규 — live 잔고 (holdings + usdDeposit). 유효성 검사 전용
AccountBalance getLiveBalance(Account account, Ticker ticker) {
    return switch (account.broker()) {
        case KIS  -> kisAccountPort.getBalance(account, ticker);
        case TOSS -> tosAccountPort.getBalance(account, ticker);
    };
}
```

- 기존 `getUsdDeposit()`은 `getLiveBalance().usdDeposit()` 위임으로 리팩토링

#### 3. `TradingService.planSaveAndPlaceSells()` — 개장 스케쥴러 live 체크로 교체

```
Before: balance.isOrderValid(buyOrders) [DB 기반] → 부족해도 저장 진행
After:  liveBalance = brokerAccountRouter.getLiveBalance(account, ticker)
        isSufficient(result.orders(), liveBalance) 검사
        → 부족 시: userNotificationPort.notifyInsufficientBalance() + return (저장 안 함)
        → 충분 시: 기존대로 저장
```

검사 기준:
- BUY: `sum(price × quantity) > liveBalance.usdDeposit()` → 예수금 부족
- SELL: `sum(quantity) > liveBalance.holdings()` → 보유수량 부족

#### 4. `TradingService.planAndSaveOrders()` — 마감 스케쥴러 plan 단계에 live 체크 추가

```
오늘 주문이 없고 새로 계산한 경우에만 적용:
  liveBalance = brokerAccountRouter.getLiveBalance(account, ticker)
  → 부족 시: userNotificationPort.notifyInsufficientBalance() + return null
  → 충분 시: 기존대로 savePlannedOrders()
```

주문이 이미 존재하면 skip(live 체크 없음) — 기존 동작 유지.

#### 5. `ManualTradingService` — SELL 보유수량 체크 추가

```
Before: BUY 총액만 live usdDeposit과 비교 → 부족 시 throw ManualTradingException
After:  BUY 체크 유지 (기존 로직)
        + SELL 체크: liveBalance.holdings() < sum(sellQuantity) → throw ManualTradingException("보유 수량이 부족합니다")
```

`getLiveBalance()` 1회 호출 후 BUY·SELL 모두 검사.

### kista-ui

#### 6. `AccountSummaryCard` / `AccountDetailTabs` props 이름 정리

```tsx
// Before
kisUsdDeposit: number
kisPosEvalUsd: number

// After
usdDeposit: number
posEvalUsd: number
```

레이블 "예수금(실계좌기준)" / "평가금(실계좌기준)" 유지. 
`/api/accounts/{id}/portfolio`가 이미 KIS·Toss 모두 지원하므로 API 변경 없음.

## 변경하지 않는 것

- `TradingOrderExecutor.placeOrders()`: 브로커 접수 직전 자체 잔고 체크 없음 — 브로커 거부에 위임
- `BrokerStatisticsRouter.getPresentBalance()`: 이미 브로커 분기 처리됨, 변경 없음
- `CycleRotationService`: 자체 최소금액 검사는 live `BrokerMarginRouter` 사용 중 — 변경 없음
- `TradingBalanceLoader.tryLoadBalance()` (preview 전용): preview는 DB 기반 유지

## 유효성 검사 헬퍼

공통 로직 중복 방지를 위해 `TradingService`에 private 헬퍼 추출:

```java
// BUY 예수금 부족 또는 SELL 보유수량 부족 여부 판단
private boolean isLiveBalanceInsufficient(List<Order> orders, AccountBalance live) {
    BigDecimal buyTotal = orders.stream()
        .filter(o -> o.direction() == BUY)
        .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
        .reduce(ZERO, BigDecimal::add);
    int sellTotal = orders.stream()
        .filter(o -> o.direction() == SELL)
        .mapToInt(Order::quantity).sum();
    return buyTotal.compareTo(live.usdDeposit()) > 0
        || sellTotal > live.holdings();
}
```

## 영향받는 파일 목록

**kista-api**
- `application/service/trading/TradingService.java`
- `application/service/trading/BrokerAccountRouter.java`
- `application/service/trading/ManualTradingService.java`

**kista-ui**
- `widgets/account-detail/AccountSummaryCard.tsx`
- `widgets/account-detail/AccountDetailTabs.tsx`
- `app/(main)/accounts/[id]/page.tsx`
