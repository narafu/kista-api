# Spring Modulith broker 모듈 이전 설계

원칙 SSOT는 [2026-08-27-spring-modulith-migration-design.md](2026-08-27-spring-modulith-migration-design.md) (모듈 템플릿, common 정책, 포트 위치, 테스트 전략). 이 문서는 broker 모듈(3번 타깃 — finance✅ → notify✅ → **broker** → trading 코어)의 구체 파일 인벤토리·크로스모듈 의존 분석만 다룬다.

## 전제

finance·notify 모듈 이전 완료, main에 병합됨(commit `4e5158ef`). 이번 작업은 그 위에서 이어간다. 이 브랜치도 모듈 구분뿐 아니라 리팩토링을 겸한다 — 작업 중 발견되는 개선 지점은 임의 수정하지 말고 적극적으로 보고할 것.

kis·toss·mock 세 브로커 어댑터는 `BrokerAdapterPort` 등 동일 포트 인터페이스 집합을 구현하는 하나의 캡슐이라 별도 모듈로 쪼개지 않고 **단일 broker 모듈**로 이전한다(사용자 확정) — 세 어댑터가 포트 소유권을 나눠 가지면 오히려 경계가 애매해진다.

## 이동 대상

### domain/port/out (15개) → `com.kista.broker.domain.port.out`
`domain/port/out/broker/*.java` 14개 그대로: `BrokerAccountPort, BrokerAdapterPort, BrokerConnectionTestPort, BrokerMarketCalendarPort, BrokerOrderCorrectionPort, BrokerPricePort, CandlePort, ExchangeRatePort, ExecutionPort, LiveBalancePort, MarginPort, PortfolioPort, SellableQuantityPort, StockInfoPort`
+ `domain/port/out/BrokerTokenCachePort.java` (현재 `broker/` 서브패키지 밖에 낙오돼있음 — 같이 편입, 패키지만 정리, 로직 변경 없음)

### domain/model (14개) → `com.kista.broker.domain.model{, .kis, .toss}`
- `domain/model/broker/*.java` (7, 공통) → `com.kista.broker.domain.model`: `Currency, DailyTransaction, DailyTransactionResult, DailyTransactionSummary, Execution, MarginItem, PresentBalanceResult`
- `domain/model/kis/KisApiException.java` (1) → `com.kista.broker.domain.model.kis`
- `domain/model/toss/*.java` (6) → `com.kista.broker.domain.model.toss`: `TossAccountInfo, TossApiException, TossCandle, TossExchangeRate, TossMarketSession, TossStockInfo`

### application/service (3개) → `com.kista.broker.application.service`
`BrokerAdapterRegistry`(public 유지 — 여러 legacy 서브패키지가 직접 참조하는 예외적 공개 접근자, 이 성격은 이전 후에도 동일), `BrokerConnectionTesters`, `BrokerCallGuard`

### adapter/out (33개) → `com.kista.broker.adapter.out.{kis, toss, mock, internal, persistence}`
- `adapter/out/kis/*` (10) 그대로 `com.kista.broker.adapter.out.kis`: `KisAuthApi, KisBrokerAdapter, KisConfig, KisExchangeRegistry, KisHttpClient, KisOrderApi, KisPriceApi, KisResponseParser, KisTokenCoordinator, KisTradingApi`
- `adapter/out/toss/*` (17) 그대로 `com.kista.broker.adapter.out.toss`: `TossAuthApi, TossBrokerAdapter, TossCandleApi, TossConfig, TossDistributedTokenCoordinator, TossHoldingsApi, TossHttpClient, TossMarketApi, TossMarketCalendarCache, TossOrderApi, TossPriceApi, TossRedisTokenStore, TossResponseParser, TossResult, TossStockInfoCache, TossTokenStore, UsdKrwRateCache`
- `adapter/out/mock/*` (2) 그대로 `com.kista.broker.adapter.out.mock`: `MockAuthApi, MockBrokerAdapter`
- `adapter/out/broker/*` (3) → `com.kista.broker.adapter.out.internal` (**개명** — 모듈명과 겹치는 `broker.broker` stutter 회피, notify의 `notify`→`gateway` 개명과 동일 사유): `DoubleCheckedTokenCache, PrevCloseCache, TokenCoordinator`
- `adapter/out/persistence/kistoken/*` (3) → `com.kista.broker.adapter.out.persistence` (flat, finance/notify 관례와 동일): `KisTokenEntity, KisTokenJpaRepository, KisTokenPersistenceAdapter`

### 대응 테스트 (27개) 동일 패키지 구조로 이동
`adapter/out/{kis(8), toss(13), mock(1), broker→internal(2)}`, `adapter/out/persistence/kistoken(1)`, `application/service/broker(1, BrokerCallGuardTest)`, `domain/model/broker(1, PresentBalanceResultTest)`.

**총 이동 규모**: 소스 67개 + 테스트 27개 = 94개 파일. finance(약 40개)·notify(약 33개)보다 많음 — 포트 개수(13)·크로스모듈 참조 지점이 더 많기 때문. 이동 자체는 기계적(패키지 선언 + import 경로 치환)이라 파일 수보다 **크로스모듈 import 갱신 범위**가 실질 난이도.

## 이번 스코프 제외 (판단 근거)

- **`adapter/out/marketdata/CommonMarketPriceFeed.java`** — 계좌 자격증명 불필요한 공통 시세 조회(모의계좌가 재사용). 브로커 자격증명 기반 어댑터와 목적이 다르다 — broker로 끌어오지 않는다.
- **`adapter/out/alpaca/*`, `adapter/out/heartbeat/*`** — 각각 시세·달력(Alpaca), 스케쥴러 dead-man's switch(heartbeat) 용도. KIS/Toss 증권사 API와 무관 — 제외.
- **`AccountStatisticsUseCase`/`AccountStatisticsService`(KIS 전용 live 통계), `TossStatisticsUseCase`/`TossStatisticsService`(Toss 전용 live 통계)** — `AccountPort`(계좌 소유권 검증)에 의존하는 **account 도메인 소유** 기능이다. 내부 구현이 `BrokerAdapterRegistry.require(...)`를 얇게 호출할 뿐이라 broker로 옮기고 싶은 유혹이 있지만, 대칭 기능인 `AccountStatisticsService`가 이미 account 소유로 확정돼 있어(KIS/Toss 동일하게 계좌 소유권 검증이 선행) 두 기능을 다르게 분류하면 일관성이 깨진다. 둘 다 legacy에 남기고 broker의 `domain`(model+port) Named Interface만 참조하게 유지 — **broker 모듈은 `domain/port/in`이 아예 없다** (notify와 동일한 모양: outbound만 존재).
- **`GlobalExceptionHandler`의 `KisApiException`/`TossApiException` 매핑** — finance의 `UserCascadeDeleter`/`MetaController`/`GlobalExceptionHandler` 역참조 4건과 같은 성격. `adapter.in.web`(legacy)이 broker의 `domain` Named Interface를 참조하는 것으로 충분, GlobalExceptionHandler 자체는 옮기지 않는다.
- **`domain/backtest/{FillSimulator, BacktestEngine}`** — 과거 일봉 시뮬레이션(계좌 무관), broker 도메인 모델(`Currency` 등)을 참조하지만 자체는 backtest 전용 순수 계산 로직이라 broker 소유 아님. legacy에 유지.

## 모듈 내부 구조

```
com.kista.broker/
├── domain/
│   ├── model/                 ← Currency, DailyTransaction* 등 공통 7개
│   │   ├── kis/                ← KisApiException
│   │   └── toss/                ← TossAccountInfo 등 6개
│   └── port/out/               ← 14개 포트 (BrokerAdapterPort, LiveBalancePort, BrokerTokenCachePort 등)
├── application/
│   └── service/                ← BrokerAdapterRegistry(public), BrokerConnectionTesters, BrokerCallGuard
├── adapter/
│   └── out/
│       ├── kis/                 ← KisAuthApi 등 10개
│       ├── toss/                ← TossAuthApi 등 17개
│       ├── mock/                ← MockAuthApi, MockBrokerAdapter
│       ├── internal/            ← DoubleCheckedTokenCache, PrevCloseCache, TokenCoordinator (kis/toss 내부 공용 헬퍼, adapter 밖 노출 안 함)
│       └── persistence/         ← KisTokenEntity/JpaRepository/PersistenceAdapter (flat)
└── package-info.java            ← @ApplicationModule(type = Type.CLOSED)
```

domain/port/in 없음(broker 자체 UseCase 없음 — notify와 동일 모양).

## Named Interface

`domain`(model+port/out 전체)과 `application`(BrokerAdapterRegistry/BrokerConnectionTesters/BrokerCallGuard) 2개 공개. `adapter`는 internal(모듈 밖 접근 불가) — KisHttpClient·TossRedisTokenStore 등 구현 세부는 이번 이전으로 처음 진짜 은닉된다(기존엔 최상위 패키지라 사실상 전부 public 접근 가능했음).

`@org.springframework.modulith.NamedInterface("domain")` — `domain/model`, `domain/model/kis`, `domain/model/toss`, `domain/port/out` 4개 패키지 전부에 선언.
`@org.springframework.modulith.NamedInterface("application")` — `application/service` 패키지에 선언.

## 크로스모듈 의존

### broker → old top-level (신규 방향 — notify와 동일 패턴, 이미 검증된 방식)

**최초 스펙 초안에서 "broker는 완전 고립 모듈"이라고 잘못 판단했던 부분 정정** — `BrokerConnectionTestPort`만 예외적으로 `verifyAccount(broker enum, ...)`로 설계돼 Account를 참조하지 않을 뿐, 나머지 13개 포트·7개 공통 모델·kis/toss/mock 어댑터 전체는 legacy 코어 도메인을 광범위하게 참조한다(전수 grep으로 확인):
- `domain.model.account.{Account, SellableQuantity}` — 거의 전 파일(포트 시그니처, 어댑터 구현체 전부)
- `domain.model.order.Order` — `BrokerOrderCorrectionPort`, `Execution`/`DailyTransaction`(broker 모델 자체), `KisOrderApi`, `TossOrderApi`, `MockBrokerAdapter`
- `domain.model.strategy.{Strategy, Strategy.Ticker, AccountBalance, PriceSnapshot, CyclePosition, StrategyCycle, DstInfo}` — 포트 시그니처 다수 + kis/toss/mock 어댑터 다수

notify가 이미 같은 성격의 의존(`order.Order, user.User, account.Account, strategy.Strategy` 등)을 가지면서도 CLOSED+Named Interface로 문제없이 검증 통과한 선례가 있다 — legacy 최상위 4패키지가 `Type.OPEN`으로 선언돼 있어(commit `beb9a3ed`) 이 방향 참조는 추가 설정 없이 `ModulithArchitectureTest.verify()`를 통과한다. **broker가 domain을 Named Interface로 공개해야 하는 이유가 애초에 이 역방향이 아니라 순방향(legacy → broker) 때문이라는 점은 원래 서술이 맞다** — 다만 "broker → legacy 없음"이라는 문장 자체가 틀렸으므로 이 절을 신설해 정정한다.

### old top-level → broker (신규 방향 — import 경로만 변경, 로직 변경 없음)

`domain`(model+port/out) 참조처, 압도적으로 많음 — 주요 그룹만 나열(전수는 이동 커밋에서 IDE 전역 치환으로 처리):
- **trading 코어 전체**: `TradingPriceFetcher, ManualTradingService, PreviewDepositCache, TradingOrderBudgetAllocator, TradingReporter, StrategyOrderPlanBuilder, VrCycleRolloverService, OrderCancelService, TradingSellSufficiencySimulator, TradingOrderExecutor, VrReconfigureService, CycleRotationService`(전부 `BrokerAdapterRegistry` + 여러 port 참조)
- **account/strategy/admin**: `AccountStatisticsService, TossStatisticsService, BrokerStatisticsRouter, StrategyService, AccountService(BrokerConnectionTesters), AdminReorderService, AdminTradeCorrectionService`
- **domain 순수 계산**: `domain/model/strategy/AccountBalance.java`(broker의 `Currency` 등 참조), `domain/backtest/{FillSimulator, BacktestEngine}`
- **web/event**: `GlobalExceptionHandler`(KisApiException/TossApiException), `adapter/in/web/dto/{MarginResponse, DailyTransactionResponse, PortfolioSummaryResponse}`, `application/event/TradingReportReadyEvent`
- **domain/port/in**: `AccountStatisticsUseCase`, `TossStatisticsUseCase`(반환 타입으로 broker 도메인 모델 참조 — 위 스코프 제외 사유 참고)

`application`(BrokerAdapterRegistry/BrokerConnectionTesters/BrokerCallGuard) 참조처: 위 trading/account/strategy/admin 목록과 대부분 겹침 + `AdminReorderService`, `AccountService`(BrokerConnectionTesters만), `GlobalExceptionHandler`(BrokerCallGuard 예외 타입).

### 와일드카드 import 사전 스캔 (notify 교훈 반영 — 이동 커밋 직전 재스캔 필수)

이전 시점 기준 6개 파일 확인됨: `TossBrokerAdapter, MockBrokerAdapter, KisBrokerAdapter, KisTradingApi`(자기 자신 이동 대상, import 경로만 내부 정리) + `TossStatisticsService, TossStatisticsUseCase`(legacy 잔류, broker 패키지 와일드카드 import를 신규 경로로 갱신 필요). 이동 대상 파일 수가 finance/notify보다 훨씬 많아 실제 브리프 작성 시 이 목록보다 늘어날 가능성 높음 — 태스크 착수 직전 `grep -rn "import com.kista\.\(domain\.port\.out\.broker\|domain\.model\.\(broker\|kis\|toss\)\|application\.service\.broker\)\.\*;"` 재실행 필수.

### 문자열 리터럴 FQN 참조

전수 확인 완료 — broker/kis/toss/mock 관련 AOP pointcut·리플렉션 문자열 없음(`ErrorLogAspect`는 notify 포트만 대상). 이번 모듈은 이 리스크 없음.

## 테스트

- `ModulithArchitectureTest.verifyModularStructure()` — broker 추가 후에도 순환 없어야 함(위 "broker → old top-level 없음" 확인 결과상 순환 발생 여지 자체가 없음)
- `HexagonalArchitectureTest` — 이미 와일드카드 일반화 완료(`com.kista..domain..` 등), 추가 변경 불필요
- 이동 대상 테스트 27개 전체 동일 패키지 구조로 이동(기계적)

## DB

변경 없음. `broker_tokens`(KisTokenEntity) 등은 이미 public 스키마.

## 문서

`docs/agents/architecture.md`, `constraints.md`, `CLAUDE.md`의 broker 관련 서술을 finance/notify 이전 때(commit `a0ee1a30`/`c2718026`)와 동일한 방식으로 갱신. `docs/agents/kis-api.md`, `docs/agents/toss-api.md`도 패키지 경로 언급이 있으면 함께 확인.

## 리팩토링 관찰 (구현 중 추가 발견 시 갱신)

- `adapter/out/broker` → `adapter/out/internal` 개명 — 모듈명과의 stutter 회피(사용자 승인, notify `gateway` 선례와 동일 사유)
- 그 외 발견되는 개선 지점은 임의 수정하지 말고 구현 중 사용자에게 즉시 보고

## 미해결 확인 필요 항목

없음.
