# Spring Modulith trading 코어 모듈 이전 설계

원칙 SSOT는 [2026-08-27-spring-modulith-migration-design.md](2026-08-27-spring-modulith-migration-design.md) (모듈 템플릿, common 정책, 포트 위치, 테스트 전략). 이 문서는 trading 모듈(4번 타깃 — finance✅ → notify✅ → broker✅ → **trading 코어**)의 구체 파일 인벤토리·크로스모듈 의존 분석만 다룬다.

## 전제

finance·notify·broker 모듈 이전 완료, main에 병합됨(commit `4e458c6b`). 이번 작업은 그 위에서 이어간다. 이 브랜치도 모듈 구분뿐 아니라 리팩토링을 겸한다 — 작업 중 발견되는 개선 지점은 임의 수정하지 말고 적극적으로 보고할 것.

**스코프 결정(사용자 확정)**: `domain/model/strategy` 패키지는 물리적으로 하나였지만 constraints.md가 이미 "설정 이력 계층"(Strategy/StrategyVersion/StrategyInfiniteDetail/StrategyVrDetail)과 "실행 이력 계층"(StrategyCycle/CyclePosition 등)으로 문서화해둔 두 계층이다. 이번 trading 이전은 **실행 이력 전체 + 주문생성 로직(CycleOrderStrategy 계열)까지 넓게 흡수**한다. 설정 계층은 legacy에 남기고 향후 별도 strategy 모듈 몫으로 미룬다.

## 이동 대상

### domain/model (25개) → `com.kista.trading.domain.model` (flatten, 서브패키지 없음)
- `domain/model/order/*` (8, 그대로): `BuyCompetitionPreview, CancelResult, ManualTradingException, NextOrdersPreview, Order, OrderCancelException, SellSufficiencyPreview, TradeEvent`
- `domain/model/strategy/*` 중 실행 이력 17개: `AccountBalance, BatchContext, BootstrapPosition, CycleHistoryPage, CyclePosition, CyclePositionHistoryEntry, CyclePositionInfiniteDetail, DstInfo, InfinitePosition, PriceSnapshot, ReconfigureVrCommand, ReverseModePosition, StrategyCycle, StrategyCycleVrDetail, TradingReport, TradingSnapshot, VrPosition`(설정 계층 8개 `RegisterStrategyCommand, Strategy, StrategyDetail, StrategyInfiniteDetail, StrategySeedPreview, StrategyVersion, StrategyVrDetail, UpdateStrategyCommand`는 legacy 잔류 — 전체 25개 중 8+17)
- 이름 충돌 없음 확인(order 8개 vs strategy 실행이력 17개)

### domain/strategy (14개) → `com.kista.trading.domain.strategy` (그대로, 서브패키지명 유지)
`CycleOrderStrategies, CycleOrderStrategy, InfiniteCreationResolver, InfiniteCycleOrderStrategy, InfiniteStrategy, PriceCapPolicy, PrivacyCreationResolver, PrivacyCycleOrderStrategy, PrivacyStrategy, ReverseInfiniteStrategy, StrategyCreationResolver, StrategyCreationResolvers, VrCreationResolver, VrCycleOrderStrategy, VrStrategy`
- `HexagonalArchitectureTest`의 `@Component` 허용 예외 규칙이 이미 `com.kista..domain.strategy..` 와일드카드로 일반화돼 있어 새 경로(`com.kista.trading.domain.strategy`)도 추가 수정 없이 자동 커버됨

### domain/port/in (2개) → `com.kista.trading.domain.port.in`
`TradingExecutionUseCase, VrReconfigureUseCase`

### domain/port/out (5개) → `com.kista.trading.domain.port.out`
`OrderPort, CyclePositionPort, CyclePositionInfiniteDetailPort, StrategyCyclePort, StrategyCycleVrPort`

### application/service (27개) → `com.kista.trading.application.service` (flatten)
`application/service/trading/*` 전부 그대로: `BuyOrderPriceCapper, BuyPriorityOrdering, CycleOrderComputer, CyclePositionPersistor, CycleRotationService, CycleSnapshotCreator, ManualTradingService, MarketEventNotifier, OrderCancelService, OrderCancelStateWriter, PreviewDepositCache, SeedResolutionPolicy, StrategyOrderPlanBuilder, TradingBalanceLoader, TradingBuyCompetitionSimulator, TradingExecutionFacade, TradingOrderBudgetAllocator, TradingOrderExecutor, TradingOrderPlanner, TradingOrderSlots, TradingParallelRunner, TradingPreviewService, TradingPriceFetcher, TradingReporter, TradingSellSufficiencySimulator, TradingService, VrCycleRolloverService, VrReconfigureService`
- 외부(legacy 포함) 어디서도 `application.service.trading.*`를 직접 import하지 않음 확인(전수 grep) — **"application" Named Interface 불필요**(broker와의 차이점)

### application/event (5개) → `com.kista.trading.application.event`
`CycleCompletedEvent, CycleEndedEvent, NewCycleStartedEvent, OrderCancelFailedEvent, TradingReportReadyEvent`
- notify 모듈(CLOSED)의 `CycleEndedNotifier/CycleLifecycleNotifier/OrderCancelFailureNotifier/TradingReportNotifier`가 `@TransactionalEventListener`로 소비 중 — **CLOSED↔CLOSED 모듈 간 이벤트 교차 최초 사례**(finance/notify/broker 이전엔 없었음). 이벤트 위치는 기존 프로젝트 관례(`이벤트: application/event/, 리스너: adapter/out/`)를 그대로 따라 domain이 아닌 application 하위 유지, 다만 모듈 밖 접근을 위해 별도 Named Interface로 공개

### adapter/in/schedule (3개) → `com.kista.trading.adapter.in.schedule`
`TradingOpenScheduler, TradingCloseScheduler, BatchContextFactory`
- `SchedulerJobRunner`/`SchedulerLockService`는 KbLand·FearGreed·MarketCalendar·RefreshTokenCleanup·finance 스케쥴러까지 공유하는 범용 인프라라 legacy 잔류(변경 없음)

### adapter/in/web (1개) → `com.kista.trading.adapter.in.web`
`OrderCancelController` — `TradingExecutionUseCase` 포트 하나에만 의존하는 순수 trading 관심사라 이전. `TradingCycleController`는 스코프 제외(아래 참고)

### adapter/out/persistence (15개) → `com.kista.trading.adapter.out.persistence` (flatten)
- `adapter/out/persistence/trade/*` (3): `OrderEntity, OrderJpaRepository, OrderPersistenceAdapter`
- `adapter/out/persistence/strategy/*` 중 실행 이력 12개: `CyclePositionEntity, CyclePositionJpaRepository, CyclePositionPersistenceAdapter, CyclePositionInfiniteEntity, CyclePositionInfiniteJpaRepository, CyclePositionInfiniteDetailPersistenceAdapter, StrategyCycleEntity, StrategyCycleJpaRepository, StrategyCyclePersistenceAdapter, StrategyCycleVrEntity, StrategyCycleVrJpaRepository, StrategyCycleVrPersistenceAdapter`
- `PersistenceSupport`(package-private upsert 헬퍼, 5줄)는 legacy strategy 설정 엔티티(`StrategyPersistenceAdapter` 등 4곳)와 trading행 엔티티(`StrategyCycleVrPersistenceAdapter`, `CyclePositionInfiniteDetailPersistenceAdapter`) 양쪽이 공유 — 모듈 경계상 공유 불가하므로 **trading에 동일 내용으로 복제 생성**, legacy 원본은 그대로 유지

### 대응 테스트 동일 패키지 구조로 이동
정확한 개수는 착수 직전 `find`로 재확인(broker 때도 사전 추정보다 늘었던 선례 있음) — 대상 패키지: `domain/model/{order,strategy 실행이력 대상}`, `domain/strategy`, `application/service/trading`, `adapter/in/schedule`(Trading*), `adapter/out/persistence/{trade,strategy 실행이력 대상}`.

**총 이동 규모(소스만)**: 25+14+2+5+27+5+3+1+15 = **97개** + 복제 헬퍼 1개. finance(약 40)·notify(약 33)·broker(67)보다 많음 — 이번이 최대 규모 모듈.

## 이번 스코프 제외 (판단 근거)

- **Strategy 설정 계층** (`Strategy.java`, `StrategyVersion`, `StrategyInfiniteDetail`, `StrategyVrDetail`, `RegisterStrategyCommand`, `UpdateStrategyCommand`, `StrategySeedPreview`, `StrategyDetail` + 대응 `domain/port/{in,out}`인 `StrategyUseCase, AdminStrategyUseCase, UpdateStrategySuggestionsUseCase, StrategyPort, StrategyVersionPort, StrategyInfiniteDetailPort, StrategyVrDetailPort` + 대응 persistence 11개) — constraints.md가 이미 문서화해둔 경계, 향후 별도 strategy 모듈 몫. trading은 이들을 legacy OPEN 경유로 참조(반대 방향, 아래 "크로스모듈 의존" 참고)
- **`TradingCycleController`** — `StrategyUseCase`(설정, CRUD/pause/resume) + `AccountStatisticsUseCase`(계좌 통계, 이력 조회) + `TradingExecutionUseCase`(trading) + `VrReconfigureUseCase`(trading) 4개 포트가 뒤섞인 파사드 컨트롤러. 단일 아그리게이트 소유가 아니므로 legacy 잔류, trading의 "domain" Named Interface로 포트 2개 참조
- **`TradeSseEmitterRegistry`/`TradeStreamController`** — legacy `SseEmitterRegistry`(`RealtimeNotificationPort` 구현체, user 상태변경 SSE 겸용)가 필드로 직접 합성(composition)해서 씀. adapter/out은 모듈 비공개(Named Interface 미공개)가 원칙이라 이전 시 legacy의 직접 필드 주입이 깨짐. legacy 잔류, `TradeEvent`(trading 이전 대상)만 "domain" 인터페이스로 참조
- **`AccountStatisticsService`/`TossStatisticsService`, `GlobalExceptionHandler`** — broker 이전 때와 동일 사유(계좌 소유권 검증이 선행하는 account 도메인 소유 기능 / 예외 매핑은 legacy adapter.in.web 소유). 변경 없이 trading의 "domain" Named Interface만 참조
- **`domain/backtest/{FillSimulator, BacktestEngine}`, `application/service/backtest/BacktestService`** — broker 이전 때와 동일 사유(과거 일봉 시뮬레이션 전용, trading의 domain/strategy·domain/model을 참조하지만 자체는 trading 소유 아님)

## 모듈 내부 구조

```
com.kista.trading/
├── domain/
│   ├── model/                  ← order 8개 + strategy 실행이력 17개, flat (25개)
│   ├── strategy/                ← CycleOrderStrategy 계열 14개 (@Component 예외)
│   ├── port/in/                 ← TradingExecutionUseCase, VrReconfigureUseCase
│   └── port/out/                ← OrderPort, CyclePositionPort, CyclePositionInfiniteDetailPort, StrategyCyclePort, StrategyCycleVrPort
├── application/
│   ├── service/                 ← 27개, flat, 전부 package-private
│   └── event/                   ← CycleCompletedEvent 등 5개
├── adapter/
│   ├── in/
│   │   ├── schedule/             ← TradingOpenScheduler, TradingCloseScheduler, BatchContextFactory
│   │   └── web/                  ← OrderCancelController
│   └── out/
│       └── persistence/          ← orders 3 + cycle_position 6 + strategy_cycle 6, flat (PersistenceSupport 복제본 포함)
└── package-info.java             ← @ApplicationModule(type = Type.CLOSED)
```

## Named Interface

**2개** — broker(domain+application)와 달리 trading은 application/service 외부 소비자가 없어 "application" 인터페이스가 불필요한 대신, 이벤트 교차 소비 때문에 "event"가 신규로 필요하다.

- `@NamedInterface("domain")` — `domain/model`, `domain/strategy`, `domain/port/in`, `domain/port/out` 4개 패키지
- `@NamedInterface("event")` — `application/event` 1개 패키지

## 크로스모듈 의존

### trading → old top-level (신규 방향 — broker와 동일 패턴)

- `domain.model.account.Account`, `SellableQuantity` — 대부분 서비스·포트 시그니처
- `domain.model.strategy.{Strategy, StrategyVersion, StrategyInfiniteDetail, StrategyVrDetail, StrategyDetail}`(설정 계층, legacy 잔류) — `TradingService, CycleOrderComputer, VrCycleRolloverService, VrReconfigureService` 등 다수
- `domain.model.user.{User, UserSettings, NotificationType}` — `MarketEventNotifier` 등
- `application.service.strategy.VrStrategyLifecycle`(public, "trading의 CycleSnapshotCreator에서도 재사용" 주석으로 이미 공개 확정된 예외 접근자) — `CycleSnapshotCreator`가 참조
- `notify.domain.port.out.UserNotificationPort`, `broker.domain.*`(Currency/Execution 등), `broker.application.service.BrokerAdapterRegistry` — 기존에도 있던 참조, 경로 변경 없음

### old top-level → trading (신규 방향 — import 경로만 변경, 로직 변경 없음)

- **legacy 잔류 컨트롤러/서비스**: `TradingCycleController`(위 스코프 제외 사유), `AccountStatisticsService/TossStatisticsService`(strategySeedPreview 등에서 `CycleOrderStrategies`/`DstInfo` 참조), `AdminReorderService/AdminTradeCorrectionService`(`Order`, `AdminReorderCommand`/`Result` 등), `StrategyService/VrStrategyLifecycle`(`StrategyCycle`/`CyclePosition`/`CycleOrderStrategies` 등 실행 계층 다수 참조)
- **domain 순수 계산**: `domain/backtest/{FillSimulator, BacktestEngine}`, `application/service/backtest/BacktestService`
- **web/adapter**: `adapter/in/web/dto/{TradingCycleResponse, StrategyOrdersResponse, NextOrdersResponse, CancelOrdersResponse, AdminReorderRequest, AdminTradeResponse, ExecuteOrdersResponse, ...}`, `adapter/out/sse/{SseEmitterRegistry, TradeSseEmitterRegistry}`(`TradeEvent`)
- **notify 모듈(CLOSED)**: `TradingReportNotifier`(`Order`, `TradingReportReadyEvent`), `TelegramBotService`(`Order`), `TelegramUserNotificationAdapter`(`Strategy`(legacy)+`TradingReport`(trading) 동시 참조 — 분리 필요), `CycleEndedNotifier`/`CycleLifecycleNotifier`/`OrderCancelFailureNotifier` — trading의 "event" Named Interface 최초 소비 사례
- **broker 모듈(CLOSED)**: `TossPriceApi`(`DstInfo`), `KisOrderApi/TossOrderApi/MockBrokerAdapter/KisResponseParser/Execution/DailyTransaction/BrokerOrderCorrectionPort`(`Order`) — 기존에도 있던 참조, 경로만 변경

### 와일드카드 import 사전 스캔 (broker 교훈 반영)

이전 시점 기준 `import com.kista.domain.model.strategy.*;` 7건 확인 — **분리 대상**:
- trading행 6건(자기 자신 이동, import 경로만 내부 정리): `TradingService, TradingReporter, ManualTradingService, CyclePositionPersistor, CycleOrderComputer, VrCycleRolloverService`
- legacy 잔류 1건(명시적 import로 legacy/trading 양쪽에 분리 필요): `StrategyService`
- `notify/adapter/out/gateway/TelegramUserNotificationAdapter.java`는 개별 import이지만 `Strategy`(legacy)와 `TradingReport`(trading) 양쪽을 동시에 참조 — 패키지 분리 시 import 경로 갈라 써야 함

태스크 착수 직전 재실행 필수:
```
grep -rn "import com.kista\.\(domain\.model\.\(order\|strategy\)\|domain\.strategy\|application\.service\.trading\|application\.event\)\.\*;" src/main
```

### 문자열 리터럴 FQN 참조

broker 이전 때 확인된 방식과 동일하게, 착수 직전 AOP pointcut·리플렉션 문자열 재확인 필요(`ErrorLogAspect` 등). 현재까지 위험 신호 없음.

## 테스트

- `ModulithArchitectureTest.verifyModularStructure()` — trading 추가 후 순환 없어야 함. 이벤트 교차(notify↔trading) 방향이 단방향(trading이 이벤트 발행, notify가 구독)인지 재확인 — trading이 notify를 참조하는 코드가 없어야 순환 없음
- `HexagonalArchitectureTest` — 이미 와일드카드 일반화 완료, `domain.strategy` 예외 규칙도 새 경로 자동 커버 확인됨(위 참고) — 추가 변경 불필요
- 이동 대상 테스트 전체 동일 패키지 구조로 이동(기계적)

## DB

변경 없음. `orders`/`cycle_position`/`cycle_position_infinite_detail`/`strategy_cycle`/`strategy_cycle_vr`는 이미 `kista` 스키마 단일 소속.

## 문서

`docs/agents/architecture.md`, `constraints.md`, `CLAUDE.md`의 trading 관련 서술을 finance/notify/broker 이전 때와 동일한 방식으로 갱신. `docs/agents/workflow.md`(스케쥴러 실행 흐름·예산배정 SSOT)도 패키지 경로 언급이 있으면 함께 확인.

## 리팩토링 관찰 (구현 중 추가 발견 시 갱신)

- `PersistenceSupport` 5줄 헬퍼 복제 — 모듈 경계상 공유 불가, 중복이 결합보다 저렴하다고 판단(사용자 승인 시 진행)
- 그 외 발견되는 개선 지점은 임의 수정하지 말고 구현 중 사용자에게 즉시 보고

## 미해결 확인 필요 항목

없음.
