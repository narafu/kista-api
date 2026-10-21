# trading↔broker, trading↔notify 모듈 순환 의존 제거 설계

## 배경

`docs/superpowers/plans/2026-08-29-spring-modulith-trading-migration.md` Task 4(모듈 선언)에서 `ModulithArchitectureTest`가 3개의 모듈 순환을 검출해 블로킹됨:
1. `broker → trading → broker`
2. `broker → trading → notify → broker`
3. `notify → trading → notify`

**원인**: 이전 브레인스토밍(trading 이전 스펙)이 "old top-level(legacy OPEN) ↔ trading" 방향만 분석했다. 그러나 broker·notify는 이미 CLOSED 모듈이고, trading이 legacy OPEN shim에 있던 시절부터 이미 이 둘과 양방향으로 얽혀 있었다 — trading이 CLOSED로 선언되는 순간 그 얽힘이 실제 순환으로 드러났다. addendum 1b(`AccountBalance.Fill` 인터페이스, commit `aa038a11`)는 이 중 한 방향(broker의 `Execution`→`AccountBalance` 직접 참조)만 끊었을 뿐, `BrokerAdapterRegistry`를 통한 trading→broker 방향과 `Execution.direction()`이 여전히 `Order.OrderDirection`(trading 타입)을 반환하는 문제가 남아 순환이 재발했다.

**해결 원칙**: 순환은 "양방향 참조"가 있을 때만 발생한다. trading→broker(주문 집행 요청)와 notify→trading(이벤트 구독)은 설계상 의도된, 유지해야 할 방향이다. 문제는 반대 방향 — broker/notify의 **포트·어댑터·도메인 모델 자체**가 trading 타입을 시그니처에 직접 참조하는 것. 이걸 없애면(broker/notify가 자기 소유 타입만 쓰도록) 순환 없이 두 방향(trading→broker, notify→trading)만 남아 문제가 사라진다.

전수 조사 결과(현재 코드베이스 기준):
- broker 14개 파일이 `com.kista.trading.*` import
- notify 2개 포트 인터페이스가 trading 타입(`AccountBalance`, `TradingReport`)을 메서드 시그니처에 사용
- trading 14개 파일이 `com.kista.broker.*` import (유지 — 정상 방향)
- trading 11개 파일이 `com.kista.notify.*` import (제거 대상 — 이벤트로 전환)

## 스코프 세 갈래 (하나의 스펙, 순차 실행)

### A. broker 포트·도메인 모델의 trading 타입 참조 제거 (최대 규모)

**문제**: `BrokerPricePort.getPriceSnapshot(s)`가 trading의 `PriceSnapshot` 반환, `LiveBalancePort.getLiveBalance`가 trading의 `AccountBalance` 반환, `BrokerOrderCorrectionPort.place/cancel`이 trading의 `Order`를 주고받음. `Execution`/`DailyTransaction`(broker 도메인 모델)이 `Order.OrderDirection`(trading 타입)을 필드로 가짐. `Order.OrderDirection.kisSllType()`— KIS 전용 인코딩 로직이 trading 도메인 enum에 박혀있는 기존 결함도 이 참에 정리(별도 발견, 사용자 승인 시 같이 처리).

**해결**: broker가 자기 소유의 얇은 타입을 신설하고, 포트 시그니처를 그 타입으로 바꾼다. trading 쪽 호출부(이미 broker에 의존하는 게 정상인 파일들)가 broker 타입 ↔ trading 타입 매핑을 담당한다.

- `com.kista.broker.domain.model.Direction { BUY, SELL }` — `Order.OrderDirection` 대체. `kisSllType()`류 KIS 전용 인코딩은 `KisOrderApi` 내부 private 매핑 함수로 이동(broker 소유 로직이 broker 안에 있는 게 맞음)
- `com.kista.broker.domain.model.OrderType { LOC, MOC, LIMIT }` — trading의 `Order.OrderType` 미러(3개 값 고정, 거의 안 바뀜 — 값 늘어날 때만 양쪽 동기화)
- `com.kista.broker.domain.model.PriceSnapshot(BigDecimal current, BigDecimal prevClose)` — trading의 `PriceSnapshot`과 필드 동일한 broker 소유 복제(2필드, `PersistenceSupport` 복제와 같은 판단 — 인터페이스보다 복제가 저렴)
- `com.kista.broker.domain.model.BrokerBalance(int holdings, BigDecimal avgPrice, BigDecimal usdDeposit)` — `LiveBalancePort.getLiveBalance` 반환 타입
- `com.kista.broker.domain.model.OrderInstruction(Ticker ticker, Direction direction, OrderType orderType, Integer quantity, BigDecimal price)` — `place()` 요청
- `com.kista.broker.domain.model.OrderResult(String externalOrderId)` — `place()` 응답(broker는 결과만 반환, trading이 `Order.withPlaced(externalOrderId)`로 병합 — 현재 `order.withPlaced(odno)`를 broker adapter 안에서 호출하던 것을 trading 호출부로 이동)
- `com.kista.broker.domain.model.CancelInstruction(Ticker ticker, String externalOrderId)` — `cancel()` 요청
- `Execution`/`DailyTransaction`의 `Order.OrderDirection` 필드 → `Direction`으로 교체

포트 시그니처 변경:
```
BrokerPricePort.getPriceSnapshot(s)   : PriceSnapshot(trading) → PriceSnapshot(broker)
LiveBalancePort.getLiveBalance        : AccountBalance(trading) → BrokerBalance(broker)
BrokerOrderCorrectionPort.place       : (Order,Account)→Order  →  (OrderInstruction,Account)→OrderResult
BrokerOrderCorrectionPort.cancel      : (Order,Account)→void   →  (CancelInstruction,Account)→void
```

**영향받는 broker 파일(14개)**: `KisBrokerAdapter, KisOrderApi, KisPriceApi, KisResponseParser, KisTradingApi, TossBrokerAdapter, TossHoldingsApi, TossOrderApi, TossPriceApi`(어댑터 9개) + `Execution, DailyTransaction`(도메인 모델 2개) + `BrokerOrderCorrectionPort, BrokerPricePort, LiveBalancePort`(포트 3개). `MockBrokerAdapter`도 이 3개 포트를 구현하므로 함께 수정(스코프 B와 겹침 — 아래 참고).

**영향받는 trading/legacy 호출부**: `TradingOrderExecutor, OrderCancelService, TradingReporter`(BrokerOrderCorrectionPort 호출), `AdminReorderService`(legacy, 동일 포트), `TradingOrderBudgetAllocator, ManualTradingService, PreviewDepositCache`(LiveBalancePort 호출), `StrategyService`(legacy, 동일 포트), `TradingPriceFetcher`(BrokerPricePort 호출), `CommonMarketPriceFeed`(legacy, 동일 포트). 각 호출부에 broker 타입 ↔ trading 타입 매핑 1~2줄 추가(예: `new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit())`).

**TossPriceApi의 `DstInfo.calculate()` 별도 처리**: broker가 필요로 하는 건 `isRegularSessionActive()`/`lastSessionOpenInstant()` 딱 2개(둘 다 "지금이 정규장 시간대인지 + 마지막 개장 시각"만 계산하는 순수 KST/DST 계산) — trading의 스케쥴러 오케스트레이션 로직(`waitUntilOrderTime`, `nextTradeDate`, `SCHEDULER_RUN_TIME`)과는 무관한 별개 관심사다. `common/` 승격은 하지 않는다(DstInfo 전체는 186줄짜리 trading 스케쥴링 도메인 클래스라 "전역 기술 유틸"이 아님 — master 스펙의 common 정책 위반). 대신 `TossPriceApi` 내부에 이 2개 계산만 하는 private 메서드를 복제한다(US 정규장 시간대 계산은 자주 안 바뀌는 상수 로직이라 저비용 복제로 판단 — `PersistenceSupport`와 동일 판단 기준).

### B. `MockBrokerAdapter`의 trading persistence 직접 접근 제거

**문제**: `MockBrokerAdapter`가 `com.kista.trading.domain.port.out.{OrderPort, CyclePositionPort, StrategyCyclePort}`(trading 소유 영속성 포트)를 생성자로 직접 주입받아 DB 상태를 읽어 체결·잔고를 시뮬레이션한다.

**해결**: constraints.md에 이미 확립된 패턴(`AlpacaCalendarAdapter → MarketHolidayStorePort(domain 소유) → MarketCalendarPersistenceAdapter`)을 그대로 적용 — **포트를 필요로 하는 쪽(broker)이 정의하고, 데이터를 가진 쪽(trading)이 구현**한다.

- `com.kista.broker.domain.port.out.MockSimulationDataPort`(신설, broker 소유) — `findPlacedOrders(cycleId, tradeDate)`, `findActiveCycleId(strategyId)`, `findLatestPosition(strategyId)` 등 Mock이 실제 필요로 하는 최소 메서드만. 반환 타입도 broker 소유 얇은 뷰 레코드(`PlacedOrderView`, `PositionView` 등 — Order/CyclePosition 전체가 아닌 Mock이 쓰는 필드만)
- `com.kista.trading.adapter.out.MockSimulationDataAdapter`(신설, trading 소유, `com.kista.broker.domain.port.out.MockSimulationDataPort` 구현) — 내부적으로 trading 자신의 `OrderPort`/`CyclePositionPort`/`StrategyCyclePort`를 호출(같은 모듈 내부 호출이라 경계 문제 없음)하고 broker 뷰 레코드로 매핑
- `MockBrokerAdapter`는 이제 `MockSimulationDataPort`(broker 소유)만 주입받는다 — trading 타입 참조 0

방향 확인: `MockSimulationDataAdapter`(trading)가 `MockSimulationDataPort`(broker 소유 인터페이스)를 import하는 건 trading→broker(정상, 유지 방향). broker의 `MockBrokerAdapter`는 더 이상 trading을 참조하지 않음.

### C. trading→notify 직접 호출 11곳 → 이벤트 발행 전환

**문제**: trading의 11개 파일이 notify의 `NotifyPort`/`UserNotificationPort`를 생성자 필드로 직접 주입받아 호출한다. 전부 fire-and-forget(반환값 없음) 알림 호출 8종:
```
notifyPort.notifyError(Exception)
notifyPort.notifyInsufficientBalance(Account, AccountBalance, Ticker)
notifyPort.notifyMarketClosed()
userNotificationPort.notifyBatchInterrupted(User, Account)
userNotificationPort.notifyError(User, Exception)
userNotificationPort.notifyInsufficientBalance(User, Account, Strategy.Type, Ticker)
userNotificationPort.notifyMarketClose(User)
userNotificationPort.notifyMarketOpen(User)
```

**해결**: 기존 5개 이벤트(`CycleCompletedEvent` 등)와 동일 패턴 — trading이 이벤트를 발행하고, notify가 리스너로 구독해 **기존 `NotifyPort`/`UserNotificationPort` 메서드를 그대로 호출**한다. `NotifyPort.notifyInsufficientBalance`가 trading의 `AccountBalance`를, `UserNotificationPort.notifyTradingReport`가 trading의 `TradingReport`를 시그니처에 갖고 있는 건 **그대로 둔다** — 이건 notify→trading 단방향 참조이고, C가 trading→notify 호출을 전부 없애면 반대 방향이 사라져 순환 자체가 안 생긴다(포트 시그니처를 손댈 필요 없음 — A/B와 달리 이 부분은 수정 대상이 아님이 핵심 발견).

신규 이벤트(`com.kista.trading.application.event`, "event" NamedInterface에 자동 포함):
```
TradingErrorEvent(Exception e)                                    — notifyError(Exception)/notifyError(User,Exception) 양쪽 다 이 이벤트로 통일, User 유무는 필드로 구분(nullable user)
InsufficientBalanceEvent(Account, AccountBalance, Ticker, User)   — NotifyPort/UserNotificationPort 두 메서드 페이로드 합집합, User nullable
MarketClosedEvent()                                                — notifyMarketClosed() 대응
MarketOpenEvent(User)                                              — notifyMarketOpen(User) 대응
MarketCloseEvent(User)                                             — notifyMarketClose(User) 대응 (MarketClosedEvent와 별개 — 하나는 관리자 알림, 하나는 사용자 알림, 기존 포트도 별도 메서드)
BatchInterruptedEvent(User, Account)                                — notifyBatchInterrupted 대응
```
User가 nullable인 이벤트(`TradingErrorEvent`, `InsufficientBalanceEvent`)는 notify 리스너에서 `user == null`이면 `NotifyPort`(관리자용) 메서드를, non-null이면 `UserNotificationPort`(사용자용) 메서드를 호출하도록 분기 — 발행 시점에 trading이 이미 "이건 관리자용/사용자용" 구분을 갖고 있으므로(현재 코드도 두 포트를 상황별로 골라 호출 중) 이 정보를 이벤트에 실어 보낸다.

**영향받는 trading 파일(11개)**: 정확한 목록은 착수 직전 재확인(브로커 이전 때도 사전 추정 대비 늘었던 선례) — 현재 기준 `application/service/{CycleRotationService, ManualTradingService, MarketEventNotifier, TradingOrderExecutor, TradingPriceFetcher, TradingReporter, TradingService, VrCycleRolloverService, VrReconfigureService}` + `adapter/in/schedule/{BatchContextFactory, TradingOpenScheduler}`. `MarketEventNotifier`는 이름 그대로 이미 알림 라우팅을 전담하는 클래스라, 다른 10개 파일의 이벤트 발행을 이 클래스로 집중시키는 리팩토링도 고려 가능하나 — **이번 스코프에서는 안 함**(각자 발행처에서 바로 `ApplicationEventPublisher.publishEvent()` 호출, 기존 5개 이벤트와 동일한 방식 유지 — 구조 변경 최소화).

**신규 notify 리스너**: 기존 5개 이벤트 리스너 클래스(`CycleEndedNotifier` 등) 패턴을 따라 `com.kista.notify.adapter.out.gateway`에 신설 — 이벤트 종류가 늘어난 만큼 클래스를 나눌지 하나로 합칠지는 착수 시 판단(예: `TradingAlertNotifier` 하나로 6개 이벤트 다 처리 — 리스너 메서드 6개, 각각 1~2줄).

## 크로스모듈 의존 최종 상태

```
trading → broker   : BrokerAdapterRegistry, BrokerOrderCorrectionPort(신규 시그니처), LiveBalancePort(신규 시그니처), BrokerPricePort(신규 시그니처) — 유지, 정상 방향
broker  → trading   : 0 (전부 broker 소유 타입으로 대체)
notify  → trading   : application.event.*(5+6=11개, "event" NamedInterface) + NotifyPort/UserNotificationPort 시그니처 2곳(AccountBalance, TradingReport 그대로 유지) — 유지, 정상 방향(단방향)
trading → notify   : 0 (전부 이벤트 발행으로 대체)
```
순환 없음 — `broker → trading`, `notify → trading` 두 방향만 남고 반대편이 모두 사라짐.

## 테스트

- 대응하는 broker/mock 어댑터 테스트 전체가 새 타입에 맞춰 갱신 필요(기계적 — 생성자 인자 타입 변경)
- `ModulithArchitectureTest` — 이 작업의 최종 검증 기준. 3개 순환 전부 사라져야 함
- `HexagonalArchitectureTest` — 회귀 확인(타입 이동 없음, 새 타입 추가뿐이라 영향 없을 것으로 예상)
- 매매 공식·VR 공식 등 계산 로직 자체는 무변경 — 관련 단위 테스트는 기존 값 그대로 통과해야 함(순수 타입/배선 변경)

## DB

변경 없음.

## 리팩토링 관찰

- `Order.OrderDirection.kisSllType()` — KIS 전용 인코딩이 trading 도메인 enum에 있던 기존 결함, 이 작업으로 자연 해소(broker 쪽으로 이동)
- `DstInfo`가 "달력/세션 상태 계산"과 "스케쥴러 오케스트레이션 타이밍"이라는 두 관심사를 한 클래스에 담고 있음 — 이번엔 broker가 필요로 하는 2개 메서드만 좁게 복제하는 선에서 처리하고, 클래스 분리 자체는 이번 스코프 밖(발견만 기록, 사용자 승인 시 별도 작업)

## 미해결 확인 필요 항목

없음.
