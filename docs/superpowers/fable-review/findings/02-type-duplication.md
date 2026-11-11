# 02. 타입 복제·간접층 측정 (main 소스, 읽기 전용)

측정 기준: `src/main`(api) + `trading-core/src/main` + `shared/src/main`. 테스트 제외. 스크립트 전수 집계 + 표본 읽기 보정. **(추정)** 표시는 휴리스틱 값, 나머지는 grep/파싱 실측.
한계: 필드 겹침%는 이름 일치 기준(엔티티는 `private` 필드 정규식 파싱), "호출처"는 단어 출현 파일 수라 리플렉션·Spring 주입 간접 참조는 못 본다. 줄 수는 주석·공백 포함.

## 0. 전체 규모

| 구분 | 파일 | 줄 |
|---|---:|---:|
| 루트 `:api` | 427 | 16,501 |
| `:trading-core` | 629 | 35,581 |
| `:shared` | 104 | 3,436 |
| **합계** | **1,160** | **55,518** |

카테고리별 줄 수(이름·경로 규칙 분류, 대략): 서비스/Writer/Assembler 76개 8,910 · 도메인 모델 197개 7,413 · 컨트롤러 55개 4,083 · PersistenceAdapter 37개 3,335 · Response DTO 72개 2,883 · Entity 44개 2,407 · Port 98개 2,034 · JpaRepository 39개 1,501 · Request DTO 39개 1,103 · UseCase 52개 898 · Command 19개 457 · Event 34개 441 · package-info 66개 366. (분류 안 된 "기타" 296개 19,753줄 — 브로커 벤더 어댑터·스케줄러·Redis·설정 등)

**영속성 한 벌(Entity+JpaRepo+PersistenceAdapter) = 120개 파일 7,243줄**, 여기에 1:1 Port 선언이 얹힌다.

## A. 개념별 타입 복제 지도

### A-1. 개념별 관련 타입 수 (파일 수 / 줄 수)

| 개념 | 관련 파일 | 줄 | Entity | JpaRepo | PersAdapter | Port | UseCase | Domain(record) | Command | Request | Response | Event | Service류 |
|---|---:|---:|---|---|---|---|---|---|---|---|---|---|---|
| Strategy(+DCA/INF/VR 상세) | 73 | 3,903 | 7/440 | 6/235 | 5/438 | 6/174 | 1/51 | 14/553 | (RegisterStrategyCommand 19필드) | 5/104 | 6/171 | 2/24 | 11/1,288 |
| StrategyCycle·CyclePosition·이력 | 52 | 3,158 | 5/223 | 5/297 | 5/505 | 5/183 | - | 11/476 | - | 1/78 | 6/291 | 4/61 | 1/137 |
| Order(체결·주문·FIDA 포함) | 33 | 2,145 | 2/127 | 1/173 | 1/328 | 1/107 | 1/12 | 6/244 | 1/33 | 1/44 | 5/218 | 1/7 | 3/314 |
| Account(+브로커 참조) | 35 | 1,572 | 1/81 | 1/30 | 1/90 | 4/70 | 3/70 | 4/124 | 2/27 | 1/38 | 3/83 | 1/7 | 4/480 |
| User(+설정·알림 프로필) | 59 | 2,328 | 4/223 | 4/80 | 3/279 | 4/81 | 5/101 | 3/132 | - | - | 2/85 | 7/76 | 4/402 |
| 통계/성과/백테스트/InvestmentPoint | 52 | 2,876 | - | - | - | 2/27 | 5/90 | 15/779 | 1/50 | 3/35 | 4/149 | - | 7/1,015 |
| 포트폴리오 조회 | 16 | 580 | - | - | - | 2/26 | 2/32 | 3/49 | - | - | 4/161 | - | 2/165 |
| 가계부(Finance·Asset·Budget…) | 102 | 4,873 | 9/603 | 9/364 | 7/497 | 8/254 | 10/178 | 11/370 | 5/67 | 9/236 | 10/259 | 1/8 | 9/983 |
| 알림(Notify/Telegram/FCM/Push) | 66 | 2,603 | 3/140 | 3/59 | 2/166 | 8/125 | - | 1/22 | - | 4/43 | - | 4/61 | 1/184 (+게이트웨이 Notifier류 "기타" 38/1,730) |
| 벤치마크·공포탐욕·시장지수 | 66 | 2,781 | 4/267 | 4/72 | 4/233 | 8/112 | 6/84 | 13/234 | - | - | 5/277 | 2/16 | 6/629 |
| Admin | 46 | 2,275 | - | - | - | 1/21 | 6/132 | 5/52 | - | 4/251 | 7/292 | - | 6/529 |
| DCA·Crypto | 88 | 4,633 | 3/254 | 3/103 | 2/165 | 6/148 | 5/61 | 24/1,292 | - | 4/109 | 7/392 | 2/44 | 7/595 |
| Privacy(FIDA) | 46 | 2,144 | 3/129 | - | 1/244 | 2/69 | 3/49 | 13/584 | 4/103 | 4/99 | 3/121 | 1/11 | 4/225 |

(개념 간 파일은 일부 중복 집계: DCA/Crypto는 Strategy와, Cycle은 Strategy와 겹침. 합산 금지.)

### A-2. 같은 개념의 형상 체인과 필드 겹침 (이름 일치, 작은 쪽 기준)

| 개념 | 타입(경로 약칭, 필드수/줄) | 겹침 |
|---|---|---|
| Order | domain `Order` (T:trading/domain/model, 17f/120) · `OrderEntity` (15f/81) · contract `OrderResponse` (15f/31) · `AdminTradeResponse` (A:admin, 19f/77) · `PlannedOrder` (matching, 8f/77) · `OrderInstruction`(broker 7f) · `PortfolioOrderResponse`(contract 7f) · `FidaOrderResponse`/`Request`(contract 8f/7f) · `FidaOrderCommand`(7f) · `FidaPlannedOrder`(4f) · `PrivacyTradeBaseOrderEntity`(6f) | Order↔Entity 93%, Order↔OrderResponse **100%**, Order↔AdminTradeResponse 76%, Order↔PlannedOrder 87% |
| Strategy | `Strategy`(6f) · `StrategyEntity`(7f) + 6개 상세 엔티티 · contract `StrategyResponse`(6f) · `StrategySummary`/`SummaryResponse`(2f 각) · `StrategyDetail`(12f) · `RegisterStrategyCommand`(19f) · `StrategyCreationRequest`(5~6f, 4개 리졸버용 축소) · `TradingCycleRequest`(14f)/`TradingCycleResponse`(17f/128줄) · `StrategyRef`(api.benchmark 3f) · `StrategyRefLite`(broker 2f) · `StrategyTypeStats`(8f) | Strategy↔Entity 83%, Strategy↔contract Response **100%**, StrategyDetail↔TradingCycleResponse 75%, Strategy↔Command 33% |
| StrategyCycle | `StrategyCycle`(11f) · `StrategyCycleEntity`(9f) · `StrategyCycleVrEntity`/`VrDetail` · `TradingCycleResponse`(17f) · `CycleHistoryResponse`(7f, **trading·stats에 줄 단위 동일 사본 2개**) · `CyclePerformance`(14f) | Cycle↔Entity 88% |
| CyclePosition | `CyclePosition`(8f/72) · `CyclePositionEntity`(7f) · `CyclePositionInfiniteEntity`/`InfiniteDetail` · `CyclePositionHistoryEntry`(9f/48) · `CycleHistoryResponse`(7f) · `PositionView`(broker 3f) | Position↔Entity 85%, ↔HistoryEntry 62%, ↔HistoryResponse 57% |
| Account | `Account`(13f/86) · `AccountEntity`(11f) · `AccountResponse`(7f) · contract `AccountSummaryResponse`(4f) · `RegisterAccountCommand`(6f)/`UpdateAccountCommand`(2f) · `AccountRequest`(6f) · own-type `TradingAccount`(4f, trading) · `BrokerAccountRef`(7f, broker) | Account↔Entity 72%, ↔Command 83%, ↔TradingAccount 75%, ↔BrokerAccountRef 71% |
| AccountBalance류 | `AccountBalance`(matching 3f/109줄) · `PresentBalanceResult`(broker 6f/101줄) · `CryptoBalance`(3f) · `AccountLedgerTotals`(4f)/`...Response`(4f) · `AccountCashCheckPreview`/`HoldingsCheckPreview` | - |
| User | `User`(12f/85) · `UserEntity`(13f/93) · `UserResponse`(10f/57) · `AdminUserResponse`(5f) · `UserSummary`(5f) · own-type `NotificationRecipient`(6f, notify) · trading 복제 `UserNotifyProfileEntity`(7f) + `UserNotifyProfileChangedEvent`(9f, kernel) | User↔Entity 91%, ↔Response 60%, ↔Summary 80%, ↔NotificationRecipient 50% |
| 가계부 | `FinanceAccount`(15f/55) · `FinanceAccountEntity`(86줄) · `FinanceAccountResponse`(8f) · `AssetSnapshot`(18f/28)·`AssetSnapshotResponse`(15f/32) · 그룹/예산/월마감 각각 Request·Response·Command | Account↔Response **100%**, Snapshot↔Response 66% |
| 통계 조회 결과 | `InvestmentPoint`(stats 12줄 / benchmark 14줄 — **동명 2개**) · contract `InvestmentPointsResponse` · `PortfolioCurrentResponse` · `StatsContractMapper` | - |

### A-3. 동일/동명 중복 (정확 일치 또는 이름 동일)

| 이름 | 위치 | 줄(합) |
|---|---|---:|
| `CycleHistoryPageResponse`+`CycleHistoryResponse` | stats / trading adapter/in/web/dto (필드 동일, 36+26줄 x2) — 원장이 "(b) 의도적 허용" | 124 |
| `UserEventResubmitScheduler` | api.web / tradingcore.trading (61줄 x2) | 122 |
| `PlainDecimalSerializer` | shared.contract.notify / tradingcore.stats.dto (21줄 x2) | 42 |
| `AlpacaConfig`+`AlpacaProperties` | benchmark / stats / marketcalendar 3벌 (21+18+21, 7+10+7줄) | 84 |
| `BinanceConfig` | stats / broker | 36 |
| `InvestmentPoint` | benchmark / stats | 26 |
| `MarketSessionResponse` | shared.contract / api.market (4f 동일) | 31 |
| `RedisBlacklistAdapter` | user / trading (35+15) | 50 |
| `Market`(kernel enum 35줄) / `Market`(finance 14줄) | 동명 다른 의미 | 49 |
| **합계 11개 이름** | | **564** |

→ 완전 동일 사본을 1벌로 줄이면 약 **250줄 (추정)**.

### A-4. 변환 메서드 (from/toDomain/toEntity/toCommand/toResponse 등, 메서드 선언부 파싱)

| 메서드 | 개수 | 줄 |
|---|---:|---:|
| `from` | 84 | 597 |
| `toDomain` | 40 | 293 |
| `toEntity` | 10 | 120 |
| `toCommand` | 18 | 83 |
| `toResponse` | 13 | 72 |
| `toDetail`/`toView`/`toSummary`/`toDto`/`toModel`/`toWire` | 9 | 88 |
| **합계** | **174** | **1,253** |

레이어별: web-dto 122개/799줄, persistence 47개/383줄, 기타 4/68, domain 1/3. 별도 매퍼·어셈블러 클래스: `StatsContractMapper` 47 · `PrivacyContractMapper` 63 · `TradingContractMapper` 48 · `ExecutionFillMapper` 29 · `DcaSummaryAssembler` 76 · `StrategyDetailAssembler` 190 (합 453줄). 한계: `{`로 시작하는 선언만 잡았고 람다·`of()`·인라인 `new XResponse(...)`는 빠졌으므로 실제는 이보다 크다(추정 +20~30%).

## B. 포트·유스케이스 인터페이스 대 구현

`application/usecase` 52개 + `application/port/output` 97개 = **149개 인터페이스** (선언 2,919줄). 구현체는 main 기준 `implements`/`extends` 파싱.

| 구분 | 개수 | 구현 1개 | 구현 2+ | 메서드 1개짜리 | 인터페이스 줄 | 구현측 `@Override` 수 |
|---|---:|---:|---:|---:|---:|---:|
| UseCase | 52 | **52 (100%)** | 0 | 24 | 898 | 179 |
| Port(output) | 97 | 86 (89%) | 11 | 25 | 2,021 | 588 |
| **전체** | **149** | **138 (92.6%)** | 11 | **49 (33%)** | **2,919** | 767 |

구현 1개짜리 138개의 인터페이스 선언 2,733줄 + 구현측 `@Override` 561개. 시그니처 반복분은 **약 2,000~3,300줄 (추정**, 인터페이스 줄에 주석 포함)). 구현 클래스 파일 자체는 12,420줄(이 중 시그니처 반복이 아닌 본 로직).

모듈별 집계:

| 모듈 | 인터페이스 | 구현1 | 구현2+ | 메서드1 | 인터페이스 줄 |
|---|---:|---:|---:|---:|---:|
| api.admin | 17 | 17 | 0 | 2 | 305 |
| api.benchmark | 11 | 11 | 0 | 6 | 178 |
| api.finance | 18 | 18 | 0 | 3 | 432 |
| api.market | 8 | 8 | 0 | 4 | 101 |
| api.notify | 4 | 3 | 1 | 1 | 41 |
| api.user | 13 | 13 | 0 | 3 | 234 |
| tradingcore.account | 4 | 4 | 0 | 2 | 90 |
| tradingcore.broker | 17 | 7 | 10 | 6 | 289 |
| tradingcore.marketcalendar | 3 | 3 | 0 | 0 | 45 |
| tradingcore.notify | 3 | 3 | 0 | 1 | 75 |
| tradingcore.privacy | 3 | 3 | 0 | 1 | 62 |
| tradingcore.stats | 16 | 16 | 0 | 8 | 283 |
| tradingcore.trading | 32 | 32 | 0 | 12 | 784 |

**다형성이 실제로 쓰이는 포트(구현 2+)**: `UserNotificationPort`(Telegram·Fcm·Composite 3), `BrokerConnectionTestPort`(Kis/Toss/Meritz/Binance/Upbit/Mock AuthApi 6), `CryptoExchangePort`(Upbit·Binance 2), `BrokerCapabilitiesPort` 및 그 상위 포트들(`BrokerAdapterPort`·`ExecutionPort`·`PortfolioPort`·`BrokerPricePort`·`MarginPort`·`SellableQuantityPort`·`BrokerOrderCorrectionPort` — Kis/Toss/Meritz/Mock 4 어댑터 + `BrokerRouter`). 브로커 표 외 진짜 다형성은 **알림 1 + 크립토 거래소 1 + 연결 테스트 1개뿐**. 나머지 138개는 구현 1개.

소비처가 1개 파일뿐인 UseCase/Port: **56개 / 727줄** (UseCase 대부분이 컨트롤러 1개에서만 주입 — 예: `FinanceBudgetUseCase`→`FinanceBudgetController`, `AdminSettingsUseCase`→`AdminSettingsController`, `TradingCycleController` 하나가 `DcaConfigUseCase`·`InjectCapitalUseCase`·`WithdrawCapitalUseCase`·`DcaExecutionQuery` 4개). 크립토 지표 6개 포트가 모두 `CryptoMarketIndicatorService` 하나만 소비.

## C. 단일 호출 간접층

### C-1. 로직 없이 위임만 하는 클래스 (모든 메서드 본문이 `bean.method(...)` 한 줄, 휴리스틱)
37개 / 1,168줄. 그중 이벤트 리스너·스케줄러·Redis 컨슈머는 진입점 역할이라 제외하면 순수 간접층 후보:

| 클래스 | 줄 | 비고 |
|---|---:|---|
| `TradingExecutionFacade` (trading/application/service) | 80 | 10개 메서드 전부 위임, 참조 0(인터페이스 경유) |
| `RefreshTokenPersistenceAdapter` (user) | 69 | JpaRepository 위임 8개 |
| `OrderCancelStateWriter` | 47 | |
| `PortfolioQueryHttpAdapter` / `MarketCalendarQueryHttpAdapter` / `TradingSchedulerCommandHttpAdapter` (api.*/adapter/out/internal) | 39/38/35 | 내부 HTTP 포워딩 |
| `MarketIndexPricePersistenceAdapter` | 35 | |
| `RedisBlacklistAdapter`(user) | 35 | |
| `UserModuleFinanceMemberAdapter` | 33 | 모듈 경계용 포워더 |
| `AlertNotifier`·`UserDeletedNotifier`·`FinanceReminderChannelNotifier`·`ApiKeyExpiryNotifier`·`PrivacyAlertNotifier`·`OrderCancelFailureNotifier`·`TelegramBotInfoAdapter`·`TradingUserProfiles`·`UserSummaries`·`TradingErrorReporter`·`BrokerStatisticsService` | 20~30 각 | 알림 게이트웨이 1줄 위임 클래스군 |
| `StatsContractMapper`·`PrivacyContractMapper`·`TradingContractMapper` | 47/63/48 | 도메인↔contract 변환 |

순수 위임 클래스(리스너·스케줄러 제외) 약 20개 / **약 800줄 (추정)**.

### C-2. 호출처(참조 파일)가 1곳뿐인 public 클래스 (인터페이스 미구현, 리스너/스케줄러/컨트롤러 제외): 32개 / 2,534줄
벤더 REST API 클래스(`TossHoldingsApi` 219·`TossOrderApi` 215·`MeritzOrderApi` 213·`MeritzTradingApi` 163·`MeritzPriceApi` 131)는 실로직이라 간접층이 아니다. 간접층 후보: `StrategyUpdateWriter` 133 · `StrategyHistoryQueryService` 130 · `OrderSubmissionReconciler` 105 · `DcaSummaryAssembler` 76 · `SubmittingOrderAwaiter` 74 · `MarketEventNotifier` 71 · `CancelledOrderFillRecorder` 61 · `UserChannelNotifier` 56 · `SelectionChain` 44 · `CycleCloser` 33 · `AdminSeedPromoter` 35 · `AccountDeletionWriter` 32 · `BinanceClientOrderIds` 23 · `UpbitMarketRegistry` 34 · `BinanceSymbolRegistry` 35 등 (인라인 가능 여부는 호출 1곳의 크기에 따라 다름 — 후보 합 약 900줄, 추정).

## D. record·DTO·이벤트 선언

| 항목 | 값 |
|---|---|
| main record 선언(중첩 포함) | **618개 / 약 8,051줄** (전체 줄의 14.5%) — 최상위 341개, 중첩 약 277개 |
| 필드 15개 이상 | 13개: `TradingCycleRequest` 24f, `MetaBundle` 20, `RegisterStrategyCommand` 19, `AdminTradeResponse` 19, `AssetSnapshot` 19, `Order` 17, `TradingCycleResponse` 17, `VrConfigRequest` 17, `FinanceAccount` 16, `VrSummary` 16 (domain+TradingCycleResponse 내부 중복 2벌), `AssetSnapshotResponse` 15, `OrderResponse` 15 |
| 최상위 record 중 참조 파일이 자기 외 1개 이하 | 73개 / 1,388줄 (거의 모두 컨트롤러·서비스 1곳 전용 DTO) |
| 모듈별 record | trading 132/2,161 · stats 112/1,122 · broker 102/667 · admin 49/881 · benchmark 44/271 · finance 38/659 · matching 25/858 · contract 25/290 · user 25/205 · privacy 15/194 |
| 이름 접미사별 | Response 124/2,270 · Request 46/730 · Event 34/170 · Row 25/66 · Item 23/236 · Result 22/180 · Command 19/288 · Summary 16/173 · Properties 10/26 · 기타 252/3,240 |

`VrSummary`가 domain(`trading/domain/model`, 19줄)과 `TradingCycleResponse` 내부 중첩(42줄)에 같은 16필드로 2벌. `Event` record 34개 중 `UserApproved/Reapplied/Rejected/SeedPromotedEvent`·`UserDeletedEvent` 등 1필드 7줄짜리가 다수(아래 E).

## E. 모듈 경계 비용

| 항목 | 개수 | 줄 | 비고 |
|---|---:|---:|---|
| package-info | 66 | 300 | 그중 `@ApplicationModule` 19, `@NamedInterface` 56 |
| NamedInterface 공개 패키지 | 56 | 파일 411 / 12,167줄 | 공개면 자체(비용 아님, 이 범위가 "공개 계약" 크기) |
| `shared.contract` wire 타입 | 21 | 471 | + 변환 `*ContractMapper` 3개 157줄 + 소비측(root) DTO 사본·역변환 |
| `shared.kernel` | 43 | 1,011 | 공용 enum·이벤트·포트 |
| ledger 허용 own-type/projection | `NotificationRecipient` 21(+`NotificationRecipients` 16) · `TradingAccount` 23 · `BrokerAccountRef` 27 · `StrategyRefLite` 9 · `StrategyCreationRequest` 15 · `FidaPlannedOrder` 16 · `StrategyRef` 17 · DTO 이중복제 `CycleHistory*` 124 | 약 270 | 원장 문서(`own-type-ledger.md`) 기준 8개 + 이중복제 |
| 포트 역전 전용 인터페이스 | `ApprovalPolicyPort`·`BrokerEnabledPort`·`AccountOpenOrderCancelPort`·`MockSimulationDataPort` | 약 50 (추정) | 구현은 다른 모듈 |
| 모듈 간 이벤트 타입 | `*Event.java` 34개 407줄 (kernel 9개 90줄) · 리스너 포함 파일 39개 | 407 + (리스너 파일 2,301줄 중 이벤트 처리분 미분리) | |
| 프로세스 간 복제본 `user_notify_profile` | Entity 66 + 이벤트 31 + 동기 리스너 34 + 스트림 컨슈머 29 + 포트 17 + 어댑터 22 + `UserDeletedStreamConsumer` 28 + `UserEventRepublisher` 30 + `UserEventResubmitScheduler` 61 | **약 318** | 모듈 경계 하나(알림 프로필)를 위한 기반 |
| Redis 관련 | 파일 다수 | 464 | 스트림 소비·발행 골격 |
| internal HTTP | 15 파일 | 797 | + HttpAdapter류 626줄 |

경계 장치 합계(package-info 300 + ledger own-type 270 + contract 471 + 3 ContractMapper 157 + 이벤트 407 + 복제본 318 + internal HTTP 797 ≈ **약 2,700줄, 전체의 약 5% (추정, 중복 제외 안 함)**).

## 줄일 수 있는 총량 추정 — 상위 8

| # | 대상 | 근거 | 추정 감소 | 해법(한 줄) |
|---|---|---|---:|---|
| 1 | 구현 1개뿐인 UseCase 52개 + 모듈 내부 전용 Port | 52개 100% 구현1, 24개 메서드 1개, 소비처 컨트롤러 1곳 (B) | 1,500~2,200줄 | 컨트롤러가 서비스 클래스를 직접 쓰고 UseCase 인터페이스 제거(모듈 간 Port·다형성 Port만 유지) |
| 2 | 영속성 3층(Entity+JpaRepo+PersistenceAdapter) 중 필드 83~93% 동일 매핑 | Entity 44 + JpaRepo 39 + Adapter 37 = 7,243줄, `toDomain`/`toEntity` 50개 413줄 | 1,500~2,500줄 (추정) | 도메인 record를 JPA 엔티티로 대체 또는 변환기 제네릭화로 어댑터 슬림화 |
| 3 | 도메인과 필드 76~100% 동일한 Response DTO | Order/OrderResponse 100%, Strategy/StrategyResponse 100%, FinanceAccount/Response 100% (A-2), Response 124개 2,270줄, `from` 84개 597줄 | 1,000~1,500줄 (추정) | 동일 형상 Response는 도메인 record 직접 직렬화(필요 필드만 `@JsonIgnore`) |
| 4 | 단일 호출·순수 위임 클래스 | C-1 약 20개 800줄 + C-2 후보 약 900줄 | 800~1,200줄 (추정) | Facade·Notifier·단일 호출 Writer를 호출 서비스에 인라인 |
| 5 | contract + ContractMapper + 소비측 사본 | contract 471 + Mapper 157 + root측 DTO 사본, Order/Strategy 100% 동형 (A) | 500~800줄 (추정) | contract record를 서비스 응답 타입으로 직접 사용해 Mapper 삭제 |
| 6 | 동명·동일 사본 11개 이름 | `CycleHistory*` 124, `UserEventResubmitScheduler` 122, `Alpaca*` 84 등 564줄 (A-3) | 约 250줄 | 공용 모듈로 승격해 1벌만 유지(CycleHistory는 원장 (b) 재검토) |
| 7 | 1필드 7줄짜리 이벤트·Command·Request 군집 | Event 34개 407줄, 1필드 User*Event 5종, 최상위 record 73개 1,388줄이 단일 파일 전용 | 200~400줄 (추정) | `UserStatusChangedEvent(userId,kind)` 등으로 병합, 1곳 전용 Request는 컨트롤러 중첩 record화 |
| 8 | 프로세스 복제본·내부 HTTP 어댑터 | user_notify_profile 복제 318줄, internal HTTP 797줄, HttpAdapter 626줄 (E) | 200~400줄 (추정) | 복제본 동기 대신 조회 API 1개로 대체 가능성 검토(설계 판단 필요, 줄 수 근거는 약함) |

합계 상한 추정(중복 제외 전): 약 **6,000~9,000줄 (전체 55,518줄의 11~16%, 추정)**. 1·2·3은 서로 겹치므로 실제 합은 이보다 작다. 인터페이스·DTO 계층은 ArchUnit 가드(`HexagonalArchitectureTest`, `InternalApiContractTest`)와 결합돼 있어 규칙 변경이 선행돼야 한다.
