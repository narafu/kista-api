# 01. 기능별 수직 슬라이스 측정 (2026-11-11, 읽기 전용)

측정 방식: 진입점에서 DB·외부 호출까지 실제 호출 경로의 파일을 `find`+`wc -l`로 센 것. `코드줄*` = 빈 줄·`//` 주석·import·package 제외 줄 수(주석·선언 노이즈 제거 후 크기). "로직/배선" 비율은 **추정**이다(서비스·도메인 파일을 읽고 분기·계산이 있는 줄만 로직으로 계산). 경로는 모두 `kista-api/` 기준, 패키지 접두 `com/kista/` 생략. 테스트 코드는 제외했다.

## 0. 먼저 알아야 할 구조 사실 (측정 중 발견)

1. **kista-ui가 전략·계좌·통계를 root를 거치지 않고 trading-core(8081)로 직접 프록시한다.** `kista-ui/app/api/trading-cycles/[[...path]]/route.ts:3-5`(`target: 'trading'`, 6줄), `accounts/[[...path]]/route.ts:5`(`target: 'trading'`), `stats/[[...path]]/route.ts:6`(housing-benchmark만 `'api'`). 그래서 요청의 "kista-ui → root → trading-core 내부 HTTP" 구간은 **기능 1·3(통계)·5에는 존재하지 않는다**(root 컨트롤러·HTTP 클라이언트·contract 0줄). root↔trading-core HTTP 경계는 admin 조회·벤치마크·시장 캘린더/캔들·PRIVACY 조회에만 쓰인다(아래 기능 3B에서 별도 측정).
2. 그 대신 **사용자 복제본 동기화**가 경계 비용으로 숨어 있다: `UserNotifyProfile*`(root 발행 → Redis Stream → trading-core 수신 → `trading.user_notify_profile` 저장). 기능 1(`StrategyCreationService.java:99-100` 존재 확인)·2(`BatchContextFactory`)·6(`TradingUserProfiles.requireProfile`)이 이 복제본에 의존한다. 별도 표(§7).
3. 저장소 전체 규모(main): root 427파일/16,501줄, trading-core 629파일/35,581줄, shared 104파일/3,436줄. JPA 엔티티 43, `*JpaRepository` 40, `*PersistenceAdapter` 37, `*Port` 98개(그중 구현체 1개 88, 0개 6), `application/usecase` 인터페이스 52개(**전부 구현체 1개**), `application/service` 123개.

## 1. 요약표

| # | 기능 | 파일 수 | 줄(raw) | 코드줄 | 로직 추정(코드줄 대비) | 프로세스 경계 코드(raw) | 타입 갈아입기 |
|---|---|--:|--:|--:|---|---|--:|
| 1 | 전략 등록 (INFINITE 경로) | 46 | 3,313 | 2,064 | ~330 (16%) | 0 직접 (+복제본 동기화 §7) | 약 19회 |
| 1' | 전략 등록 (VR·DCA·PRIVACY 포함 전체 표면) | 69 | 4,382 | 2,698 | ~480 (18%) | 〃 | 〃 |
| 2 | 마감 배치 INFINITE 주문 생성·접수 | 58 | 6,531 | 4,086 | ~1,200 (29%) | 0 (복제본 의존) | 약 11회 |
| 3A | 통계 요약 조회 `GET /api/stats/summary` | 전용 7 + 공유 26 = 33 | 2,636 (전용 696) | 1,653 (전용 455) | ~400 (전용 기준 88%, 전체 24%) | 0 | 4회 |
| 3B | 벤치마크 비교(root→trading-core 실제 프록시 경로) | 24 | 1,635 | 1,069 | ~420 (39%) | 약 410 | 6회 (InvestmentPoint 3복제) |
| 4 | 가계부 거래내역 CRUD (+공유·해제) | 17 | 721 | 430 | ~110 (26%) | 0 (root 단독) | 5회 |
| 5 | 계좌 등록(KIS, 자격증명 AES) | 19 | 1,203 | 753 | ~270 (36%) | 0 | 4회 |
| 6 | 마감 리포트 알림(Telegram + FCM + SSE) | 30 | 1,448 | 843 | ~130 (15%) | 약 535 (+root 전용 전달 계층 약 500) | 8회 |

\* 코드줄 기준. 로직 추정의 근거는 각 절 끝에 적었다.

---

## 2. 기능 1 — 전략 등록 `POST /api/accounts/{id}/trading-cycles`

진입점 `TradingCycleController.java:72-82`(trading-core, kista-ui가 직접 호출). 아래는 **INFINITE 등록**이 지나는 파일이다(VR/DCA/PRIVACY 전용 파일 23개 제외, 전체 표면은 69파일/4,382줄). 영속은 6개 테이블(strategy, strategy_version, strategy_infinite_version, strategy_cycle, cycle_position, cycle_position_infinite) × 각 5파일 스택이다.

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| TradingCycleController | controller | 285 | 207 | tradingcore/trading/adapter/in/web/TradingCycleController.java |
| TradingCycleRequest | DTO | 77 | 59 | tradingcore/trading/adapter/in/web/dto/TradingCycleRequest.java |
| TradingCycleResponse | DTO | 127 | 107 | tradingcore/trading/adapter/in/web/dto/TradingCycleResponse.java |
| RegisterStrategyCommand | command | 36 | 22 | tradingcore/trading/domain/model/RegisterStrategyCommand.java |
| StrategyUseCase | usecase | 50 | 16 | tradingcore/trading/application/usecase/StrategyUseCase.java |
| StrategyService | service | 270 | 194 | tradingcore/trading/application/service/StrategyService.java |
| StrategyCreationService | service | 189 | 100 | tradingcore/trading/application/service/StrategyCreationService.java |
| StrategyCreationPersister | service | 84 | 48 | tradingcore/trading/application/service/StrategyCreationPersister.java |
| StrategyCreationResolvers | domain | 25 | 12 | tradingcore/trading/domain/creation/StrategyCreationResolvers.java |
| StrategyCreationResolver | domain | 35 | 15 | tradingcore/trading/domain/creation/StrategyCreationResolver.java |
| InfiniteCreationResolver | domain | 20 | 11 | tradingcore/trading/domain/creation/InfiniteCreationResolver.java |
| StrategyCreationRequest | domain | 15 | 7 | tradingcore/trading/domain/creation/StrategyCreationRequest.java |
| ResolvedCreation | domain | ? | ? | NOT FOUND |
| StrategyDetailAssembler | service | 189 | 127 | tradingcore/trading/application/service/StrategyDetailAssembler.java |
| StrategyDetail | domain | 27 | 19 | tradingcore/trading/domain/model/StrategyDetail.java |
| StrategyCreationPolicyPort | port | 12 | 3 | tradingcore/trading/application/port/output/StrategyCreationPolicyPort.java |
| StrategyPort | port | 62 | 24 | tradingcore/trading/application/port/output/StrategyPort.java |
| StrategyVersionPort | port | 31 | 12 | tradingcore/trading/application/port/output/StrategyVersionPort.java |
| StrategyInfiniteDetailPort | port | 20 | 6 | tradingcore/trading/application/port/output/StrategyInfiniteDetailPort.java |
| StrategyCyclePort | port | 53 | 18 | tradingcore/trading/application/port/output/StrategyCyclePort.java |
| CyclePositionPort | port | 71 | 25 | tradingcore/trading/application/port/output/CyclePositionPort.java |
| CyclePositionInfiniteDetailPort | port | 18 | 5 | tradingcore/trading/application/port/output/CyclePositionInfiniteDetailPort.java |
| StrategyPersistenceAdapter | adapter | 149 | 106 | tradingcore/trading/adapter/out/persistence/StrategyPersistenceAdapter.java |
| StrategyVersionPersistenceAdapter | adapter | 76 | 50 | tradingcore/trading/adapter/out/persistence/StrategyVersionPersistenceAdapter.java |
| StrategyInfiniteDetailPersistenceAdapter | adapter | 56 | 33 | tradingcore/trading/adapter/out/persistence/StrategyInfiniteDetailPersistenceAdapter.java |
| StrategyCyclePersistenceAdapter | adapter | 131 | 89 | tradingcore/trading/adapter/out/persistence/StrategyCyclePersistenceAdapter.java |
| CyclePositionPersistenceAdapter | adapter | 210 | 154 | tradingcore/trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java |
| CyclePositionInfiniteDetailPersistenceAdapter | adapter | 53 | 32 | tradingcore/trading/adapter/out/persistence/CyclePositionInfiniteDetailPersistenceAdapter.java |
| StrategyEntity | entity | 53 | 28 | tradingcore/trading/adapter/out/persistence/StrategyEntity.java |
| StrategyVersionEntity | entity | 36 | 18 | tradingcore/trading/adapter/out/persistence/StrategyVersionEntity.java |
| StrategyInfiniteEntity | entity | 34 | 15 | tradingcore/trading/adapter/out/persistence/StrategyInfiniteEntity.java |
| StrategyCycleEntity | entity | 54 | 28 | tradingcore/trading/adapter/out/persistence/StrategyCycleEntity.java |
| CyclePositionEntity | entity | 46 | 24 | tradingcore/trading/adapter/out/persistence/CyclePositionEntity.java |
| CyclePositionInfiniteEntity | entity | 34 | 15 | tradingcore/trading/adapter/out/persistence/CyclePositionInfiniteEntity.java |
| StrategyJpaRepository | jparepo | 99 | 60 | tradingcore/trading/adapter/out/persistence/StrategyJpaRepository.java |
| StrategyVersionJpaRepository | jparepo | 34 | 15 | tradingcore/trading/adapter/out/persistence/StrategyVersionJpaRepository.java |
| StrategyInfiniteJpaRepository | jparepo | 22 | 12 | tradingcore/trading/adapter/out/persistence/StrategyInfiniteJpaRepository.java |
| StrategyCycleJpaRepository | jparepo | 64 | 34 | tradingcore/trading/adapter/out/persistence/StrategyCycleJpaRepository.java |
| CyclePositionJpaRepository | jparepo | 161 | 121 | tradingcore/trading/adapter/out/persistence/CyclePositionJpaRepository.java |
| CyclePositionInfiniteJpaRepository | jparepo | 45 | 29 | tradingcore/trading/adapter/out/persistence/CyclePositionInfiniteJpaRepository.java |
| Strategy | domain | 78 | 49 | tradingcore/trading/domain/model/Strategy.java |
| StrategyVersion | domain | 13 | 7 | tradingcore/trading/domain/model/StrategyVersion.java |
| StrategyInfiniteDetail | domain | 16 | 10 | tradingcore/trading/domain/model/StrategyInfiniteDetail.java |
| StrategyCycle | domain | 68 | 49 | tradingcore/trading/domain/model/StrategyCycle.java |
| CyclePosition | domain | 71 | 44 | tradingcore/trading/domain/model/CyclePosition.java |
| CyclePositionInfiniteDetail | domain | 8 | 4 | tradingcore/trading/domain/model/CyclePositionInfiniteDetail.java |
| StrategyCapitalChangedEvent | event | 6 | 1 | tradingcore/trading/application/event/StrategyCapitalChangedEvent.java |

**합계** TOTAL files=46 raw=3313 code=2064

**역할별 소계(INFINITE 경로, 코드줄)**: port 7파일 93, adapter 6파일 464, entity 6파일 128, jparepo 6파일 271 → **영속 계층 25파일 / raw 1,624줄 / 코드 956(전체의 46%)**. controller 1+DTO 2+command 1+usecase 1+service 4 = 9파일 / 코드 880. domain 12파일 / 코드 227.

**타입 갈아입기 (같은 데이터가 몇 번 모양을 바꾸나)**
| 단계 | 타입(필드 수) | 변환 코드 위치 | 변환 줄 |
|---|---|---|--:|
| 1 | JSON → `TradingCycleRequest` (21필드, `@Schema` 포함 77줄) | Jackson | - |
| 2 | → `RegisterStrategyCommand` (15필드, +`VrRampInput` 8필드, +`DcaConfigRequest→DcaStrategyConfig`) | `TradingCycleRequest.java:62-72` | 11 |
| 3 | → `StrategyCreationRequest` (5필드, ISP 좁히기) | `StrategyCreationService.java:171-173` | 3 |
| 4 | → `ResolvedCreation` (5필드) | `*CreationResolver` 4종 + `StrategyCreationResolvers` | ~40 |
| 5 | → `StrategyCreationPersister.Draft` (14필드) | `StrategyCreationService.java:131-133` | 3 |
| 6 | → domain `Strategy`/`StrategyVersion`/`StrategyInfiniteDetail`/`StrategyCycle`/`CyclePosition`/`CyclePositionInfiniteDetail` (6 record) | `StrategyCreationPersister.java:48-83` | ~35 |
| 7~18 | 6개 domain → 6개 Entity → 6개 domain 재변환(저장 왕복) | 어댑터 `toEntity/toDomain` | **87** (어댑터 6개 합) |
| 19 | → `StrategyDetail` → `TradingCycleResponse` (18필드) + `VrSummary` 16필드 domain→DTO 복제 | `StrategyDetailAssembler`(변환부 32줄), `TradingCycleResponse.java:49-62,70-95` | ~45 |
변환 코드 합계 ≈ 140줄(코드줄의 7%). **줄 수는 작지만 "타입 선언 + 파일"이 많다**: 같은 개념 `divisionCount`가 main 37개 파일에, `bandWidth`가 29개 파일에 등장한다.

**순수 전달만 하는 층(로직 없음)**
- `StrategyService.register` (`StrategyService.java:63-67`): `creationService.register` 위임 + 이벤트 1줄. `StrategyUseCase`(50줄)는 이 서비스 하나만 구현.
- `StrategyCreationResolvers`(25줄) + `StrategyCreationRequest`(15줄): 타입별 라우터와 ISP 좁히기 record.
- 영속 스택 중 `StrategyInfiniteDetail*`·`CyclePositionInfiniteDetail*`: 필드 1~2개짜리 개념에 5파일 145줄(`StrategyInfiniteDetailPort.java` 20 + `...PersistenceAdapter.java` 56 + `StrategyInfiniteEntity.java` 34 + `StrategyInfiniteJpaRepository.java` 22 + `StrategyInfiniteDetail.java` 16).

**로직 vs 배선 추정**: 로직 ~330줄 = `StrategyCreationService` 검증·산정 분기 ~110 + `StrategyCreationPersister` 저장 순서·타입 분기 ~40 + 리졸버 ~35 + `StrategyDetailAssembler` 조립 ~100 + `Strategy`·`StrategyCycle` 도메인 규칙 ~45. 나머지 ~1,730줄(84%)은 선언·위임·매핑·영속 스택이다.

**프로세스 경계**: 직접 0줄. (사용자 복제본 존재 확인 `StrategyCreationService.java:99-100`이 §7의 17파일에 의존.)

---

## 3. 기능 2 — 마감 배치의 INFINITE 주문 생성·접수

진입점 `TradingCloseScheduler.run()`(`TradingCloseScheduler.java:54-57`, 화~토 04:30) → `SchedulerJobRunner` → `BatchContextFactory` → `TradingExecutionUseCase`(= `TradingExecutionFacade`) → `TradingService.runCloseBatch`(`:72`) → 후보 계획(`TradingCandidatePlanner`) → 계산(`CycleOrderComputer` → `InfiniteCycleOrderStrategy` → `InfiniteStrategy`/`InfinitePosition`) → 원장 상한·cap → `orders` PLANNED 저장 → `BuyOrderPriceCapper` → `TradingOrderExecutor`/`OrderSubmitter` → `BrokerRouter` → `KisBrokerAdapter` → `KisOrderApi` → `KisHttpClient`(KIS HTTP) → 리포트·`CyclePositionPersistor`·`CycleRotationService`.

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| TradingCloseScheduler | scheduler | 113 | 64 | tradingcore/trading/adapter/in/schedule/TradingCloseScheduler.java |
| BatchContextFactory | scheduler | 80 | 49 | tradingcore/trading/adapter/in/schedule/BatchContextFactory.java |
| SchedulerJobRunner | scheduler(shared) | 63 | 41 | shared/platform/scheduling/SchedulerJobRunner.java |
| TradingExecutionUseCase | usecase | 34 | 12 | tradingcore/trading/application/usecase/TradingExecutionUseCase.java |
| TradingExecutionFacade | facade(순수위임) | 79 | 49 | tradingcore/trading/application/service/TradingExecutionFacade.java |
| TradingService | service | 478 | 327 | tradingcore/trading/application/service/TradingService.java |
| TradingCandidatePlanner | service | 329 | 242 | tradingcore/trading/application/service/TradingCandidatePlanner.java |
| StrategyOrderPlanBuilder | service | 103 | 56 | tradingcore/trading/application/service/StrategyOrderPlanBuilder.java |
| CycleOrderComputer | service | 140 | 87 | tradingcore/trading/application/service/CycleOrderComputer.java |
| TradingPriceFetcher | service | 199 | 132 | tradingcore/trading/application/service/TradingPriceFetcher.java |
| TradingBalanceLoader | service | 37 | 20 | tradingcore/trading/application/service/support/TradingBalanceLoader.java |
| TradingOrderSlots | service | 39 | 20 | tradingcore/trading/application/service/TradingOrderSlots.java |
| StrategyLedgerBudget | service | 86 | 49 | tradingcore/trading/application/service/StrategyLedgerBudget.java |
| AccountBudgetLock | service | 34 | 16 | tradingcore/trading/application/service/AccountBudgetLock.java |
| PrivacyBaseGuard | service | 54 | 31 | tradingcore/trading/application/service/PrivacyBaseGuard.java |
| TradingOrderPlanner | service | 35 | 17 | tradingcore/trading/application/service/support/TradingOrderPlanner.java |
| BuyOrderPriceCapper | service | 167 | 101 | tradingcore/trading/application/service/BuyOrderPriceCapper.java |
| TradingOrderExecutor | service | 181 | 116 | tradingcore/trading/application/service/TradingOrderExecutor.java |
| OrderSubmitter | service | 178 | 128 | tradingcore/trading/application/service/OrderSubmitter.java |
| OrderSubmissionReconciler | service | 104 | 64 | tradingcore/trading/application/service/OrderSubmissionReconciler.java |
| SubmittingOrderAwaiter | service | 73 | 43 | tradingcore/trading/application/service/SubmittingOrderAwaiter.java |
| TradingReporter | service | 177 | 105 | tradingcore/trading/application/service/TradingReporter.java |
| CloseReportScope | service | 40 | 19 | tradingcore/trading/application/service/CloseReportScope.java |
| CyclePositionPersistor | service | 113 | 68 | tradingcore/trading/application/service/CyclePositionPersistor.java |
| CycleRotationService | service | 136 | 78 | tradingcore/trading/application/service/CycleRotationService.java |
| CycleSnapshotCreator | service | 160 | 88 | tradingcore/trading/application/service/CycleSnapshotCreator.java |
| ExecutionFillMapper | mapper | 28 | 13 | tradingcore/trading/application/service/ExecutionFillMapper.java |
| OrderFill | domain | 32 | 16 | tradingcore/trading/application/service/OrderFill.java |
| TradingBatchGuard | service | 65 | 44 | tradingcore/trading/application/service/support/TradingBatchGuard.java |
| TradingBatchRunState | service | 132 | 102 | tradingcore/trading/application/service/support/TradingBatchRunState.java |
| TradingParallelRunner | service | 89 | 57 | tradingcore/trading/application/service/support/TradingParallelRunner.java |
| BatchContext | domain | 5 | 1 | tradingcore/trading/domain/model/BatchContext.java |
| DstInfo | domain | 155 | 97 | tradingcore/trading/domain/model/DstInfo.java |
| CycleOrderStrategies | strategy | 24 | 13 | tradingcore/matching/domain/strategy/CycleOrderStrategies.java |
| CycleOrderStrategy | strategy | 182 | 74 | tradingcore/matching/domain/strategy/CycleOrderStrategy.java |
| InfiniteCycleOrderStrategy | strategy | 182 | 120 | tradingcore/matching/domain/strategy/InfiniteCycleOrderStrategy.java |
| InfiniteStrategy | strategy | 199 | 127 | tradingcore/matching/domain/strategy/InfiniteStrategy.java |
| ReverseInfiniteStrategy | strategy | 79 | 45 | tradingcore/matching/domain/strategy/ReverseInfiniteStrategy.java |
| PriceCapPolicy | strategy | 51 | 24 | tradingcore/matching/domain/strategy/PriceCapPolicy.java |
| InfinitePosition | domain | 166 | 106 | tradingcore/matching/domain/model/InfinitePosition.java |
| ReverseModePosition | domain | 79 | 48 | tradingcore/matching/domain/model/ReverseModePosition.java |
| PlannedOrder | domain | 76 | 46 | tradingcore/matching/domain/model/PlannedOrder.java |
| AccountBalance | domain | 108 | 72 | tradingcore/matching/domain/model/AccountBalance.java |
| Order | domain | 119 | 81 | tradingcore/trading/domain/model/Order.java |
| OrderPort | port | 106 | 32 | tradingcore/trading/application/port/output/OrderPort.java |
| OrderPersistenceAdapter | adapter | 327 | 234 | tradingcore/trading/adapter/out/persistence/OrderPersistenceAdapter.java |
| OrderEntity | entity | 80 | 44 | tradingcore/trading/adapter/out/persistence/OrderEntity.java |
| OrderJpaRepository | jparepo | 172 | 120 | tradingcore/trading/adapter/out/persistence/OrderJpaRepository.java |
| BrokerOrderCorrectionPort | port | 13 | 4 | tradingcore/broker/application/port/output/BrokerOrderCorrectionPort.java |
| BrokerRouter | adapter(라우터) | 147 | 101 | tradingcore/broker/application/service/BrokerRouter.java |
| BrokerAdapterPort | port | 9 | 3 | tradingcore/broker/application/port/output/BrokerAdapterPort.java |
| KisBrokerAdapter | adapter | 118 | 84 | tradingcore/broker/adapter/out/kis/KisBrokerAdapter.java |
| KisOrderApi | adapter | 171 | 125 | tradingcore/broker/adapter/out/kis/KisOrderApi.java |
| KisHttpClient | adapter | 220 | 150 | tradingcore/broker/adapter/out/kis/KisHttpClient.java |
| KisResponseParser | adapter | 70 | 36 | tradingcore/broker/adapter/out/kis/KisResponseParser.java |
| TradingBatchRunPort | port | 23 | 6 | tradingcore/trading/application/port/output/TradingBatchRunPort.java |
| TradingBatchRunPersistenceAdapter | adapter | 59 | 37 | tradingcore/trading/adapter/out/persistence/TradingBatchRunPersistenceAdapter.java |
| TradingReportReadyEvent | event | 13 | 2 | tradingcore/trading/application/event/TradingReportReadyEvent.java |

**합계** TOTAL files=58 raw=6531 code=4086

**역할별 소계(코드줄)**: service 24파일 2,010(49%), matching strategy 6파일 403 + domain 8파일 467(순수 계산 커널 870), 영속 3파일(OrderPersistenceAdapter 327·OrderEntity 80·OrderJpaRepository 172) 약 330.

**타입 갈아입기 (약 11회)**: `Strategy`+`StrategyCycle`+`TradingAccount` → `BatchContext`(`BatchContext.java`, 5줄) → `TradingCandidatePlanner.CycleState` → `PlanContext.InfiniteInputs`/`BalanceLoad` → `AccountBalance` → `OrderPlan` → `PlannedOrder`(76) → `Order.fromPlanned`(저장 직전 승격, `TradingOrderPlanner.java:16-31`) → `OrderEntity` → `Order` → `OrderInstruction`(`OrderSubmitter.java:93-96`) → KIS 요청 JSON 문자열(`KisOrderApi`) → `OrderResult` → `markPlaced`(DB 상태) → `OrderFill`/`TradingReport` → `TradingReportReadyEvent`. 변환 코드는 얇다(`ExecutionFillMapper` 28줄, `instructionOf` 4줄, `fromPlanned`)—이 슬라이스의 비대는 변환이 아니라 **오케스트레이션 클래스 수**다.

**순수 전달만 하는 층**
- `TradingExecutionFacade`(79줄/코드 49): 10개 메서드가 전부 `tradingService.x(...)`·`manualTradingService.x(...)` 위임(`TradingExecutionFacade.java:22-79`). `TradingExecutionUseCase`(34줄)는 이 파사드 하나가 구현.
- `BrokerRouter.place`(`BrokerRouter.java:71-73`, `of(account).place(...)` 1줄) → `KisBrokerAdapter.place`(`KisBrokerAdapter.java:75-77`, `kisOrderApi.place(...)` 1줄) → `KisOrderApi.place`: **라우터 → 어댑터 → API** 3단 중 앞 2단이 위임. `KisBrokerAdapter` public 16메서드 중 14개가 `return kisXxxApi.xxx(...)`(118줄 중 대부분).
- `TradingOrderPlanner`(35줄): `orderPort.saveAll` + UNKNOWN leg 가드 1개.
- `StrategyOrderPlanBuilder`(103줄)·`CycleOrderComputer`(140줄): 조립·조회 순서만 담은 오케스트레이터(분기는 있으나 계산 없음). `CycleOrderComputer.compute`는 8·9개 파라미터 오버로드 2개(`CycleOrderComputer.java:47,54`).

**로직 vs 배선 추정**: 로직 ~1,200줄(29%) = 매매 커널(`InfiniteStrategy` 140 + `ReverseInfiniteStrategy` 55 + `InfinitePosition` 100 + `ReverseModePosition` 50 + `PriceCapPolicy` 35 + `InfiniteCycleOrderStrategy` 120 ≈ 500) + `BuyOrderPriceCapper` ~110 + `OrderSubmitter` 결과 분류 ~100 + `CyclePositionPersistor`/`CycleRotationService`/`TradingReporter` ~250 + 원장 상한 ~60 + 나머지 분기 ~180. `TradingService`(478줄/코드 ~330)는 상태 체크포인트·대기·병렬 실행 **흐름 제어**라서 로직이 아닌 배선으로 분류했다. 즉 순수 매매 공식은 약 500줄(12%)이고 나머지는 흐름·복구·배선이다.

**프로세스 경계**: 직접 0줄. 단 `BatchContextFactory`가 사용자 복제본(§7)을 조회.

---

## 4. 기능 3 — 전략 상세/통계 조회

### 3A. `GET /api/stats/summary` (kista-ui → trading-core 직접, root 없음)

진입점 `TradingStatsController.java:34-37`. 전용 파일은 7개(695줄)이고 나머지 26개는 다른 기능과 공유하는 영속·도메인이다(조회가 `AccountPort`·`StrategyPort`·`StrategyCyclePort`·`CyclePositionPort`·`CycleCapitalEventPort` 5개 포트에서 전부 읽어 메모리에서 집계).

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| TradingStatsController | controller | 65 | 38 | tradingcore/stats/adapter/in/web/TradingStatsController.java |
| StatsSummaryResponse | DTO | 37 | 27 | tradingcore/stats/adapter/in/web/dto/StatsSummaryResponse.java |
| TradingStatsUseCase | usecase | 25 | 6 | tradingcore/stats/application/usecase/TradingStatsUseCase.java |
| TradingStatsService | service | 435 | 300 | tradingcore/stats/application/service/TradingStatsService.java |
| CapitalLedgerTimeline | service | 103 | 67 | tradingcore/stats/application/service/CapitalLedgerTimeline.java |
| StatsSummary | domain | 14 | 7 | tradingcore/stats/domain/model/StatsSummary.java |
| StrategyTypeStats | domain | 17 | 10 | tradingcore/stats/domain/model/StrategyTypeStats.java |
| TtlResultCache | platform | 107 | 73 | shared/platform/cache/TtlResultCache.java |
| AccountPort | port | 34 | 20 | tradingcore/account/application/port/output/AccountPort.java |
| StrategyPort | port | 62 | 24 | tradingcore/trading/application/port/output/StrategyPort.java |
| StrategyCyclePort | port | 53 | 18 | tradingcore/trading/application/port/output/StrategyCyclePort.java |
| CyclePositionPort | port | 71 | 25 | tradingcore/trading/application/port/output/CyclePositionPort.java |
| CycleCapitalEventPort | port | 16 | 4 | tradingcore/trading/application/port/output/CycleCapitalEventPort.java |
| AccountPersistenceAdapter | adapter | 89 | 60 | tradingcore/account/adapter/out/persistence/AccountPersistenceAdapter.java |
| StrategyPersistenceAdapter | adapter | 149 | 106 | tradingcore/trading/adapter/out/persistence/StrategyPersistenceAdapter.java |
| StrategyCyclePersistenceAdapter | adapter | 131 | 89 | tradingcore/trading/adapter/out/persistence/StrategyCyclePersistenceAdapter.java |
| CyclePositionPersistenceAdapter | adapter | 210 | 154 | tradingcore/trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java |
| CycleCapitalEventPersistenceAdapter | adapter | 48 | 31 | tradingcore/trading/adapter/out/persistence/CycleCapitalEventPersistenceAdapter.java |
| AccountEntity | entity | 80 | 50 | tradingcore/account/adapter/out/persistence/AccountEntity.java |
| StrategyEntity | entity | 53 | 28 | tradingcore/trading/adapter/out/persistence/StrategyEntity.java |
| StrategyCycleEntity | entity | 54 | 28 | tradingcore/trading/adapter/out/persistence/StrategyCycleEntity.java |
| CyclePositionEntity | entity | 46 | 24 | tradingcore/trading/adapter/out/persistence/CyclePositionEntity.java |
| CycleCapitalEventEntity | entity | 49 | 26 | tradingcore/trading/adapter/out/persistence/CycleCapitalEventEntity.java |
| AccountJpaRepository | jparepo | 29 | 12 | tradingcore/account/adapter/out/persistence/AccountJpaRepository.java |
| StrategyJpaRepository | jparepo | 99 | 60 | tradingcore/trading/adapter/out/persistence/StrategyJpaRepository.java |
| StrategyCycleJpaRepository | jparepo | 64 | 34 | tradingcore/trading/adapter/out/persistence/StrategyCycleJpaRepository.java |
| CyclePositionJpaRepository | jparepo | 161 | 121 | tradingcore/trading/adapter/out/persistence/CyclePositionJpaRepository.java |
| CycleCapitalEventJpaRepository | jparepo | 13 | 3 | tradingcore/trading/adapter/out/persistence/CycleCapitalEventJpaRepository.java |
| Account | domain | 85 | 54 | tradingcore/account/domain/model/Account.java |
| Strategy | domain | 78 | 49 | tradingcore/trading/domain/model/Strategy.java |
| StrategyCycle | domain | 68 | 49 | tradingcore/trading/domain/model/StrategyCycle.java |
| CyclePosition | domain | 71 | 44 | tradingcore/trading/domain/model/CyclePosition.java |
| CycleCapitalEvent | domain | 20 | 12 | tradingcore/trading/domain/model/CycleCapitalEvent.java |

**합계** TOTAL files=33 raw=2636 code=1653

- 타입: Entity 5종 → domain 5종 → `CycleView`(서비스 내부 private record 조인) → `StatsSummary`(`StatsSummary.java`, 5필드) → `StatsSummaryResponse`(`StatsSummaryResponse.java:31-37` + `TypeStats` 9필드 복제 `:20-27`) = 4회. 변환 줄 ~25.
- 순수 전달층: `TradingStatsUseCase`(25줄, 구현 1개). `StatsSummaryResponse.TypeStats.from`은 필드 복사(enum→String 2개 외 동일).
- 로직: `TradingStatsService`(435줄) 집계 ~300줄 + `CapitalLedgerTimeline` ~67 → **로직 ~370~400줄**. 전용 파일 기준 로직 비율 약 85%로 높고, "비대"는 공유 영속 스택(25파일/1,837줄)을 읽기 전용 집계에도 그대로 거치는 데서 나온다. 캐시 무효화용 `@EventListener` 2개와 `TtlResultCache`(107줄)가 같은 서비스에 동거.
- 경계: 0줄.

### 3B. 벤치마크 비교 (root → trading-core 내부 HTTP, 실제 "root 프록시" 경로)

`BenchmarkController`(root) → `BenchmarkService` → `InvestmentPointsPort` → `InvestmentPointsHttpAdapter` → HTTP `/api/internal/trading/stats/investment-points` → `TradingStatsInternalController`(trading-core) → `InvestmentPointsQueryService` → `InvestmentIndexCalculator`.

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| BenchmarkController | controller(root) | 138 | 100 | api/benchmark/adapter/in/web/BenchmarkController.java |
| BenchmarkComparisonResponse | DTO(root) | 144 | 113 | api/benchmark/adapter/in/web/dto/BenchmarkComparisonResponse.java |
| BenchmarkUseCase | usecase(root) | 41 | 13 | api/benchmark/application/usecase/BenchmarkUseCase.java |
| BenchmarkService | service(root) | 243 | 171 | api/benchmark/application/service/BenchmarkService.java |
| BenchmarkComparisonBuilder | service(root) | 137 | 98 | api/benchmark/application/service/BenchmarkComparisonBuilder.java |
| BenchmarkComparison | domain(root) | 34 | 25 | api/benchmark/domain/model/BenchmarkComparison.java |
| PerformanceComparisonSummary | domain(root) | 13 | 9 | api/benchmark/domain/model/PerformanceComparisonSummary.java |
| BenchmarkPoint | domain(root) | 12 | 7 | api/benchmark/domain/model/BenchmarkPoint.java |
| StrategyRef | domain(root-own-type) | 17 | 6 | api/benchmark/domain/model/StrategyRef.java |
| InvestmentPointsPort | port(root) | 18 | 5 | api/benchmark/application/port/output/InvestmentPointsPort.java |
| InvestmentPointsHttpAdapter | http-client(root) | 63 | 35 | api/benchmark/adapter/out/internal/InvestmentPointsHttpAdapter.java |
| InternalApiClientConfig | http-client(shared) | 78 | 39 | shared/platform/internalapi/InternalApiClientConfig.java |
| InternalApiStatusHandlers | http-client(shared) | 33 | 15 | shared/platform/internalapi/InternalApiStatusHandlers.java |
| InternalApiErrorDetails | http-client(shared) | 41 | 23 | shared/platform/internalapi/InternalApiErrorDetails.java |
| InternalApiProperties | http-client(shared) | 14 | 8 | shared/platform/internalapi/InternalApiProperties.java |
| InvestmentPointsResponse | contract(shared) | 34 | 19 | shared/contract/stats/InvestmentPointsResponse.java |
| TradingStatsInternalController | internal-controller(tc) | 45 | 25 | tradingcore/stats/adapter/in/web/TradingStatsInternalController.java |
| StatsContractMapper | mapper(tc) | 46 | 24 | tradingcore/stats/adapter/in/web/StatsContractMapper.java |
| InvestmentPointsQuery | usecase(tc) | 18 | 5 | tradingcore/stats/application/usecase/InvestmentPointsQuery.java |
| InvestmentPointsQueryService | service(tc) | 89 | 48 | tradingcore/stats/application/service/InvestmentPointsQueryService.java |
| InvestmentIndexCalculator | service(tc) | 335 | 263 | tradingcore/stats/application/InvestmentIndexCalculator.java |
| InvestmentPointsResult | domain(tc) | 18 | 7 | tradingcore/stats/domain/model/InvestmentPointsResult.java |
| InvestmentPoint | domain(own-type 복제) | 13 | 6 | api/benchmark/domain/model/InvestmentPoint.java |
| InvestmentPoint | domain(own-type 복제) | 11 | 5 | tradingcore/stats/domain/model/InvestmentPoint.java |

**합계** TOTAL files=24 raw=1635 code=1069

- 타입(6회, `InvestmentPoint` 3복제): trading `InvestmentPoint`(stats domain) → `InvestmentPointsResponse.InvestmentPointDto`(contract, `StatsContractMapper.java:20-34`) → JSON → DTO → root `InvestmentPoint`(own-type 복제, `InvestmentPointsHttpAdapter.java:54-62`) → `BenchmarkPoint`/`BenchmarkComparison` → `BenchmarkComparisonResponse`(144줄). `StrategyRef`도 같은 이유로 root에 복제.
- **경계 전용 코드(raw 약 410줄 / 9파일)**: `InvestmentPointsPort` 18 + `InvestmentPointsHttpAdapter` 63 + `InternalApi*`(shared 4파일 166, 모든 내부 클라이언트 공용) + `InvestmentPointsResponse` 34 + `TradingStatsInternalController` 45(엔드포인트 2개 공용) + `StatsContractMapper` 46(포트폴리오와 공용) + root 복제 타입 `InvestmentPoint`·`StrategyRef` 약 30. 전체 슬라이스(1,635줄)의 **약 25%**.
- 순수 전달층: `InvestmentPointsQuery`(18줄, 구현 1), `StatsContractMapper.toDto`(필드 3개 복사), `InvestmentPointsHttpAdapter.toResult`(필드 복사 + 에러 상태 역매핑 `:34-48` ≈ 같은 패턴이 `InternalApi*`로 일부 공용화됨).
- 로직: `InvestmentIndexCalculator` ~230 + `BenchmarkComparisonBuilder` ~90 + `BenchmarkService` ~100 ≈ 420줄(39%).

---

## 5. 기능 4 — 가계부 거래내역 CRUD (`/api/finance/transactions`, root 단독)

엔드포인트 6개(목록·등록·수정·삭제·공유·공유해제). 진입점 `FinanceTransactionController.java:25-114`.

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| FinanceTransactionController | controller | 114 | 84 | api/finance/adapter/in/web/FinanceTransactionController.java |
| FinanceTransactionRequest | DTO | 26 | 14 | api/finance/adapter/in/web/dto/FinanceTransactionRequest.java |
| FinanceTransactionResponse | DTO | 26 | 18 | api/finance/adapter/in/web/dto/FinanceTransactionResponse.java |
| FinanceTransactionCommand | command | 12 | 6 | api/finance/domain/model/FinanceTransactionCommand.java |
| FinanceTransactionUseCase | usecase | 22 | 8 | api/finance/application/usecase/FinanceTransactionUseCase.java |
| FinanceTransactionService | service | 120 | 83 | api/finance/application/service/FinanceTransactionService.java |
| FinanceTransactionPort | port | 25 | 11 | api/finance/application/port/output/FinanceTransactionPort.java |
| FinanceTransactionPersistenceAdapter | adapter | 50 | 29 | api/finance/adapter/out/persistence/FinanceTransactionPersistenceAdapter.java |
| FinanceTransactionEntity | entity | 67 | 41 | api/finance/adapter/out/persistence/FinanceTransactionEntity.java |
| FinanceTransactionJpaRepository | jparepo | 39 | 18 | api/finance/adapter/out/persistence/FinanceTransactionJpaRepository.java |
| FinanceTransaction | domain | 31 | 22 | api/finance/domain/model/FinanceTransaction.java |
| GroupShareable | domain | 15 | 6 | api/finance/domain/model/GroupShareable.java |
| FinanceAccessSupport | helper | 32 | 15 | api/finance/application/service/FinanceAccessSupport.java |
| GroupShareSupport | helper | 49 | 30 | api/finance/application/service/GroupShareSupport.java |
| GroupMembershipSupport | helper | 41 | 23 | api/finance/application/service/GroupMembershipSupport.java |
| MonthlyClosingGuard | helper | 27 | 11 | api/finance/application/service/MonthlyClosingGuard.java |
| BaseAuditEntity | platform | 25 | 11 | shared/platform/persistence/BaseAuditEntity.java |

**합계** TOTAL files=17 raw=721 code=430

- 타입(5회): JSON → `FinanceTransactionRequest`(4필드, `:21-26`) → `FinanceTransactionCommand`(같은 4필드, `toCommand` 1줄) → `FinanceTransaction`(8필드, 서비스가 `new`, `FinanceTransactionService.java:49-50,64-65`) → `FinanceTransactionEntity`(`fromModel`/`toDomain` 12줄, `:55-67`) → domain → `FinanceTransactionResponse`(`from` 1줄). `transactionDate` 필드는 가계부 모듈 7개 파일에 등장. 변환 합계 ≈ 25줄(코드줄의 6%).
- 순수 전달층: `FinanceTransactionCommand`(12줄: Request와 필드 동일, 등록·수정 공용이라는 이유만 존재), `FinanceTransactionUseCase`(22줄, 구현 1), `FinanceTransactionPersistenceAdapter`(50줄: 5개 메서드 전부 `jpaRepository` 위임 + 매핑), `FinanceTransactionPort`(25줄).
- 컨트롤러 114줄 중 84줄이 코드이고 그 대부분이 `@Operation`/`@ApiResponses`/`@Parameter` 애노테이션이다(`:25-100`).
- 로직 ~110줄 = 서비스의 카테고리 접근 검증·마감월 가드·그룹 공유 분기 ~60 + 헬퍼 4개 ~40 + 도메인 접근 판정 ~10. 같은 패턴이 `FinanceAccount/Budget/Category/Group/AssetSnapshot` 5~6개 리소스에 반복(가계부 모듈 5,024줄 / 95파일).
- 경계: 0줄(root 단독, trading-core 무관).

---

## 6. 기능 5 — 계좌 등록 (KIS, AES-256 자격증명) `POST /api/accounts`

진입점 `AccountController.java:47-52`(trading-core, kista-ui 직접). 암호화는 `AccountEntity`의 `@Convert(AesStringConverter)`가 하므로 어댑터·서비스는 평문만 다룬다(제약 준수). 계좌번호 중복 판정용 HMAC 해시는 `AccountPersistenceAdapter.save`가 계산(`:50-55`).

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| AccountController | controller | 122 | 86 | tradingcore/account/adapter/in/web/AccountController.java |
| AccountRequest | DTO | 37 | 22 | tradingcore/account/adapter/in/web/dto/AccountRequest.java |
| AccountResponse | DTO | 40 | 29 | tradingcore/account/adapter/in/web/dto/AccountResponse.java |
| RegisterAccountCommand | command | 15 | 8 | tradingcore/account/domain/model/RegisterAccountCommand.java |
| AccountUseCase | usecase | 33 | 9 | tradingcore/account/application/usecase/AccountUseCase.java |
| AccountService | service | 247 | 173 | tradingcore/account/application/service/AccountService.java |
| AccountPort | port | 34 | 20 | tradingcore/account/application/port/output/AccountPort.java |
| AccountPersistenceAdapter | adapter | 89 | 60 | tradingcore/account/adapter/out/persistence/AccountPersistenceAdapter.java |
| AccountEntity | entity | 80 | 50 | tradingcore/account/adapter/out/persistence/AccountEntity.java |
| AccountJpaRepository | jparepo | 29 | 12 | tradingcore/account/adapter/out/persistence/AccountJpaRepository.java |
| Account | domain | 85 | 54 | tradingcore/account/domain/model/Account.java |
| AesStringConverter | crypto | 25 | 14 | shared/platform/crypto/AesStringConverter.java |
| AesCryptoService | crypto | 61 | 37 | shared/platform/crypto/AesCryptoService.java |
| AccountNoHasher | crypto | 34 | 18 | shared/platform/crypto/AccountNoHasher.java |
| BrokerConnectionTesters | router | 30 | 13 | tradingcore/broker/application/service/BrokerConnectionTesters.java |
| BrokerConnectionTestPort | port | 17 | 5 | tradingcore/broker/application/port/output/BrokerConnectionTestPort.java |
| BrokerAccountVerification | domain | 10 | 5 | tradingcore/broker/domain/model/BrokerAccountVerification.java |
| KisAuthApi | external-adapter | 206 | 135 | tradingcore/broker/adapter/out/kis/KisAuthApi.java |
| BrokerEnabledPort | port | 9 | 3 | tradingcore/account/application/port/output/BrokerEnabledPort.java |

**합계** TOTAL files=19 raw=1203 code=753

- 타입(4회): JSON → `AccountRequest`(6필드) → `RegisterAccountCommand`(같은 6필드, `AccountRequest.java:27-31` 1줄) → `Account`(10필드, `AccountService.java:103`) → `AccountEntity`(+해시, `fromModel`) → `Account` 재변환(`AccountPersistenceAdapter.java:79-89`, 11줄) → `AccountResponse`(`from`). 외부 호출 쪽은 `BrokerAccountVerification`(10줄)로 한 번 더 변환.
- 순수 전달층: `RegisterAccountCommand`(Request와 6필드 동일), `AccountUseCase`(33줄, 구현 1), `BrokerConnectionTesters`(30줄: broker → `BrokerConnectionTestPort` 라우터, 구현체 6개 `KisAuthApi`/`TossAuthApi`/`MockAuthApi`/`UpbitAuthApi`/`MeritzAuthApi`/`BinanceAuthApi`), `BrokerEnabledPort`(9줄, trading `TradingPolicyService`가 구현 — 계좌 모듈이 trading 정책을 포트로 거꾸로 조회).
- 로직 ~270줄 = `AccountService.register` 분기(브로커별 자격증명 규칙·중복·최대 10개·만료일 산정) ~90 + `Account` 도메인 ~15 + `KisAuthApi` 검증 ~100 + 암호화 3파일 ~69. `AccountService`(247줄)는 등록 외 수정·키 교체·삭제도 포함.
- 경계: 0줄.

---

## 7. 기능 6 — 마감 리포트 알림 (이벤트 → 리스너 → Telegram/FCM/SSE, Redis 경계)

발행 `TradingReporter.java:85`(`TradingReportReadyEvent`) → `TradingReportNotifier`(`@TransactionalEventListener(fallbackExecution = true)`) → ① Telegram: `TradingUserNotificationAdapter.notify`(`TelegramHttpClient` **trading-core에서 직접 발송**) ② FCM: 같은 `notify`가 `RedisPushNotificationPublisher`로 Redis Stream XADD → root `PushNotificationStreamConsumer` → `UserPushNotificationAdapter` → `FcmAdapter` ③ SSE: `RedisTradeEventPublisher`(Pub/Sub) → root `RedisTradeEventSubscriber` → `TradeSseEmitterRegistry` → `TradeStreamController`.

| 파일 | 역할 | 줄 | 코드줄* | 경로(com/kista/ 이하) |
|---|---|--:|--:|---|
| TradingReportReadyEvent | event | 13 | 2 | tradingcore/trading/application/event/TradingReportReadyEvent.java |
| TradingReportNotifier | listener | 57 | 28 | tradingcore/notify/adapter/out/gateway/TradingReportNotifier.java |
| TradingUserNotificationPort | port | 44 | 20 | tradingcore/notify/application/port/output/TradingUserNotificationPort.java |
| TradingUserNotificationAdapter | adapter(Telegram+Redis발행) | 180 | 133 | tradingcore/notify/adapter/out/gateway/TradingUserNotificationAdapter.java |
| TelegramHttpClient | http-client(shared) | 98 | 64 | shared/platform/telegram/TelegramHttpClient.java |
| TradingRealtimeNotificationPort | port | 11 | 3 | tradingcore/notify/application/port/output/TradingRealtimeNotificationPort.java |
| RedisTradeEventPublisher | adapter(Redis pub) | 27 | 11 | tradingcore/notify/adapter/out/gateway/RedisTradeEventPublisher.java |
| RedisPushNotificationPublisher | adapter(Redis stream pub) | 35 | 16 | tradingcore/notify/adapter/out/gateway/RedisPushNotificationPublisher.java |
| TradeEventMessage | contract | 40 | 24 | shared/contract/notify/TradeEventMessage.java |
| TradeEventEnvelope | contract | 9 | 4 | shared/contract/notify/TradeEventEnvelope.java |
| UserPushNotificationRequestedEvent | contract(kernel) | 8 | 2 | shared/kernel/UserPushNotificationRequestedEvent.java |
| RedisStreams | platform | 123 | 76 | shared/platform/redis/RedisStreams.java |
| RedisStreamConfig | platform | 19 | 11 | shared/platform/redis/RedisStreamConfig.java |
| RedisStreamSubscriber | platform | 174 | 115 | shared/platform/redis/RedisStreamSubscriber.java |
| TradingUserProfiles | helper | 22 | 8 | tradingcore/notify/adapter/out/gateway/TradingUserProfiles.java |
| TradingUserProfile | domain | 32 | 19 | tradingcore/trading/domain/model/TradingUserProfile.java |
| PushNotificationStreamConsumer | consumer(root) | 58 | 26 | api/notify/adapter/in/redis/PushNotificationStreamConsumer.java |
| UserPushNotificationAdapter | adapter(root) | 36 | 18 | api/notify/adapter/out/gateway/UserPushNotificationAdapter.java |
| PushNotificationPort | port(root) | 10 | 3 | api/notify/application/port/output/PushNotificationPort.java |
| UserNotificationPort | port(root) | 10 | 6 | api/notify/application/port/output/UserNotificationPort.java |
| FcmAdapter | adapter(root FCM) | 73 | 46 | api/notify/adapter/out/gateway/FcmAdapter.java |
| FcmConfig | config(root) | 38 | 21 | api/notify/adapter/out/gateway/FcmConfig.java |
| FcmDeviceTokenPort | port(root) | 11 | 6 | api/notify/application/port/output/FcmDeviceTokenPort.java |
| FcmDeviceTokenPersistenceAdapter | adapter(root) | 59 | 38 | api/notify/adapter/out/persistence/FcmDeviceTokenPersistenceAdapter.java |
| FcmDeviceTokenEntity | entity(root) | 42 | 25 | api/notify/adapter/out/persistence/FcmDeviceTokenEntity.java |
| FcmDeviceTokenJpaRepository | jparepo(root) | 25 | 13 | api/notify/adapter/out/persistence/FcmDeviceTokenJpaRepository.java |
| RedisTradeEventSubscriber | subscriber(root SSE) | 42 | 21 | api/notify/adapter/out/sse/RedisTradeEventSubscriber.java |
| TradeSseEmitterRegistry | sse(root) | 70 | 41 | api/notify/adapter/out/sse/TradeSseEmitterRegistry.java |
| SseEmitterRegistry | sse(root) | 52 | 31 | api/notify/adapter/out/sse/SseEmitterRegistry.java |
| TradeStreamController | controller(root SSE) | 30 | 12 | api/notify/adapter/in/web/TradeStreamController.java |

**합계** TOTAL files=30 raw=1448 code=843

(`TradingReporter`의 해당 줄은 1줄이라 표에서 제외, `TradingNotifyAdapter`는 admin 오류 경로라 제외.)

- 타입(8회): `TradingReport`+`List<TradeLegSummary>` → `TradingReportReadyEvent`(7필드) → (재조회) `TradingUserProfile` → ① HTML 문자열 → `toPlainText` 정규식(`TradingUserNotificationAdapter.java:171-173`) → `UserPushNotificationRequestedEvent`(kernel) → JSON → root 동명 타입 → `pushIfEnabled(title, body)` → FCM `Message`. ② `TradeEventMessage`(contract) → `TradeEventEnvelope` → JSON → root `TradeEventMessage` → SSE JSON.
- **경계 전용 코드(raw 약 535줄)**: 발행 `RedisPushNotificationPublisher` 35 + `RedisTradeEventPublisher` 27, contract `TradeEventMessage` 40 + `TradeEventEnvelope` 9 + `UserPushNotificationRequestedEvent` 8, Redis 골격 `RedisStreams` 123 + `RedisStreamConfig` 19 + `RedisStreamSubscriber` 174, 수신 `PushNotificationStreamConsumer` 58 + `RedisTradeEventSubscriber` 42. 여기에 root 전용 전달 계층(`FcmAdapter`·`FcmConfig`·`FcmDeviceToken*` 4파일·SSE 레지스트리 2파일·컨트롤러)이 약 500줄 — **FCM·SSE가 root에 있는 이유는 `fcm_device_tokens`가 `users` FK라 trading-core가 읽지 못하기 때문**(`RedisPushNotificationPublisher.java` 주석).
- 순수 전달층: `TradingRealtimeNotificationPort`(11줄)↔`RedisTradeEventPublisher`(27줄), `PushNotificationPort`(10줄)↔`UserPushNotificationAdapter`(36줄), `TradingUserNotificationPort`(44줄, 메서드 13개 = 알림 종류마다 메서드 1개씩 추가하는 구조).
- **이중 판정**: 채널 결정이 프로세스마다 갈라져 있다 — Telegram 여부는 trading-core `sendIfLinked`(`TradingUserNotificationAdapter.java:175-180`, `profile.telegramEnabled()`), FCM 여부는 root `UserPushNotificationAdapter.java:32`(`includesFcm()`) — 그래서 root에서 `UserPort.findById`를 다시 조회한다.
- 이관 흔적: `// root의 동명 클래스를 이관` 주석이 `notify/adapter/out/gateway` 5개 파일에 남아 있고(`TradingReportNotifier.java:21`, `CycleEndedNotifier.java:13`, `CycleLifecycleNotifier.java:13`, `TradingAlertNotifier.java:25`, `TradingUserNotificationAdapter.java:23` "byte-identical").
- 로직 ~130줄(15%) = 메시지 문구 조립 ~70 + 리스너 분기 ~28 + 컨슈머·채널 판정 ~30. 나머지 ~710줄이 전송·배선.

---

## 8. 숨은 경계 비용 — 사용자 복제본 동기화 (기능 1·2·6 공통 의존)

root `users`를 trading-core가 못 읽어서 알림 프로필을 복제한다. 17파일 / raw 약 730줄.

| 방향 | 파일(줄) |
|---|---|
| root 발행 | `UserNotifyProfilePublisher` 52, `UserEventStreamPublisher` 48, `UserEventResubmitScheduler`(root, `api/web`) 60, kernel `UserNotifyProfileChangedEvent` 31 |
| trading-core 수신 | `UserNotifyProfileSyncListener` 34, `UserEventStreamConsumer` 67, `UserNotifyProfileStreamConsumer` 29, `UserDeletedStreamConsumer` 28, `UserEventStreamBridge` 36, `UserEventRepublisher` 30, `UserEventResubmitScheduler`(tc) 60 |
| trading-core 영속 | `UserNotifyProfileEntity` 66, `UserNotifyProfileJpaRepository` 13, `UserNotifyProfilePersistenceAdapter` 105, `TradingUserProfilePort` 17, `TradingUserProfileChange` 20, `TradingUserProfile` 32 |

## 9. 프로세스 경계 코드 총량 (저장소 전체, raw 줄)

| 구분 | 파일 | 줄 |
|---|--:|--:|
| root `adapter/out/internal/*HttpAdapter` (admin 7 + benchmark 2 + market 2) | 11 | 626 |
| root가 그 어댑터를 위해 선언한 `*Port` | 11 | 162 |
| trading-core `*Internal*Controller` | 12 | 586 |
| trading-core `*ContractMapper` | 3 | 155 |
| `shared/contract/*` wire record | 21 | 471 |
| `shared/platform/internalapi/*` | 4 | 166 |
| 사용자 복제본 동기화(§8) | 17 | ~730 |
| Redis 알림 경계(§7 경계 전용) | 약 10 | ~535 |
| **합계** | **약 89** | **약 3,430** (main 전체 55,518줄의 6.2%) |

---

## 10. 기능 공통으로 반복되는 비대 패턴 (상위 8)

| # | 패턴 | 근거 file:line / 숫자 | 한 줄 해결 방향 |
|--:|---|---|---|
| 1 | **테이블 1개 = 5파일 스택** (port + adapter + entity + JpaRepository + domain record), 필드 1~2개짜리 개념에도 동일 | `StrategyInfiniteDetailPort.java`(20)+`...PersistenceAdapter.java:25-56`+`StrategyInfiniteEntity.java`(34)+`...JpaRepository.java`(22)+`StrategyInfiniteDetail.java`(16) = 145줄. 저장소 전체 entity 43·repo 40·adapter 37·port 98. 기능 1의 영속 계층이 INFINITE 경로 코드의 46% | 자식 테이블은 aggregate 어댑터 1개에 흡수, 읽기 집계용 포트는 쿼리 전용 1개로 합침 |
| 2 | **유스케이스 인터페이스 + 서비스 1:1 + 구현 1개** | `application/usecase` 52개 전부 구현 1개. `StrategyUseCase.java:17`, `TradingStatsUseCase`, `AccountUseCase`, `FinanceTransactionUseCase` 모두 단일 구현 | 컨트롤러가 서비스를 직접 의존, 인터페이스는 다중 구현이 생길 때만 |
| 3 | **같은 필드를 Request → Command → domain → Entity → Response로 재선언** | `TradingCycleRequest.java:62-72`→`RegisterStrategyCommand.java:12-35`(15필드), `AccountRequest.java:27-31`→`RegisterAccountCommand`(6필드 동일), `FinanceTransactionRequest.java:25`→`FinanceTransactionCommand`(4필드 동일). `divisionCount` 37파일·`bandWidth` 29파일·`transactionDate` 가계부 7파일 | Command를 없애고 Request(검증 애노테이션 포함)를 서비스 입력으로 쓰거나, Command를 단일 타입으로 하고 DTO는 응답만 |
| 4 | **순수 위임 파사드·라우터·어댑터 3단** | `TradingExecutionFacade.java:22-79`(10메서드 전부 위임), `BrokerRouter.java:71-73` → `KisBrokerAdapter.java:75-77`(public 16 중 14 위임) → `KisOrderApi`, `TradingOrderPlanner`(35줄) | 파사드 삭제·`BrokerRouter`가 `*Api`를 직접 선택, `KisBrokerAdapter` 제거 |
| 5 | **프로세스 경계마다 contract + mapper + HTTP 어댑터 + 내부 컨트롤러 + own-type 복제** | `InvestmentPointsHttpAdapter.java:54-62`, `StatsContractMapper.java:20-34`, `InvestmentPoint` 3복제, 경계 코드 약 89파일/3,430줄(§9). 게다가 UI는 이미 기능 1·3A·5를 trading-core로 직접 프록시(`kista-ui/app/api/*/route.ts`)라 root 경유 경계가 필요한 기능이 적다 | 2-프로세스 전제 재검토(경계 기능을 한쪽으로 모으거나 contract를 root가 직접 DTO로 소비) |
| 6 | **타입별 평행 구조(INFINITE/VR/DCA/PRIVACY)가 등록·저장·조회 전 계층에 반복** | `StrategyCreationPersister.java:53,77`(switch 2회 + 타입별 detail 포트 3개), 리졸버 4종, `*Entity` 타입별(`StrategyVrVersionEntity` 59·`StrategyDcaRuleEntity` 114 등), 전략 전체 표면 69파일 vs INFINITE만 46파일 | 타입 고유 필드는 version detail 1개(JSON 또는 sealed 타입)로 수용 |
| 7 | **한 흐름이 24개 서비스 클래스로 쪼개진 오케스트레이션** | 기능 2: service 24파일 3,149줄·`TradingService` 478줄(`:63-300`, 체크포인트·대기·병렬 제어)·`trading/application/service` 55개 클래스. 순수 매매 공식은 약 500줄뿐 | 배치 단계를 단일 파이프라인 객체로 정리하고 재개·병렬·체크포인트는 별도 횡단 모듈로 분리 |
| 8 | **주석·애노테이션·선언 노이즈와 이관 흔적** | raw 대비 코드줄 비율 기능1 61%·기능2 63%·기능6 58%. `FinanceTransactionController.java:25-100` 애노테이션이 본문 대부분, `TradingCycleRequest` 21필드마다 `@Schema` 2~3줄. `// root의 동명 클래스를 이관`이 5파일에 잔존, 채널 판정 이중화(`TradingUserNotificationAdapter.java:175` vs `UserPushNotificationAdapter.java:32`), HTML→plain 정규식(`:171`) | 이관 주석 일괄 제거, 알림 채널 판정·문구를 한 곳(트레이딩 or root)으로 |

### 보조 관찰
- 로직 비율 상위 슬라이스: 3A 통계(전용 85%)·3B 벤치마크(39%)·기능 5(36%)·기능 2(29%). 하위: 기능 6(15%)·기능 1(16%)·가계부(26%). **CRUD·알림처럼 "로직이 얇은" 기능일수록 배선이 코드의 85% 이상**이다.
- 포트 98개 중 구현 1개가 88개. 헥사고날 포트가 "교체 지점"이 아니라 "패키지 경계 선언"으로만 쓰이고 있다(ArchUnit 규칙이 강제).
- 줄 수 대비 파일 수가 문제인 유형: 매핑 코드는 기능 1에서 140줄(7%)뿐인데 파일은 46개. 줄을 줄이는 것보다 **파일·타입 개수**를 줄이는 쪽이 감량 효과가 크다.
