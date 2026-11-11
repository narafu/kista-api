# 03. 전략·브로커 두 축의 평행 구조 측정

대상: `trading-core/src/main/java/com/kista/tradingcore` 의 trading 16,324 / broker 7,632 / matching 2,725 / stats 5,357 줄 (main 소스, 합계 32,038).
방법: find·wc·grep 집계 + 정규화 diff 유사도(주석·import 제거, 전략/브로커명 치환 후 라인 시퀀스 ratio) + 표본 읽기. **(추정)** 표시는 정밀 측정이 아님. 줄 수는 파일 전체 줄(주석 포함).

## 한눈에 보는 결론

- 전략 축은 "주문 계산"만 다형성(`CycleOrderStrategy` + 4구현)으로 정리돼 있고, **나머지 모든 역할(등록 저장·영속·상세 조립·갱신·가드·백테스트)은 `strategy.isX()`/`switch`로 서비스에 흩어져 있다.** 분기 지점 약 63곳(+선언 12).
- 브로커 축은 **포트 구현의 공통 골격이 없다.** HTTP 클라이언트·토큰·rate limit·오류 변환을 브로커마다 따로 구현했고(텍스트 유사도 0.1~0.3, 책임은 동일), 줄 수의 약 61%가 "주력이 아닌" 브로커(KIS·Toss·Upbit·Mock)에 있다.
- 큰 클래스 자체는 적다(400줄 이상 3개). 비대함은 **큰 클래스가 아니라 "작은 클래스 다수 × 종류 수" 곱셈**(application/service 56파일 6,988줄, persistence 43파일 3,083줄)에서 온다.

---

## A. 전략 축

### A-1. 전략 종류 × 역할 클래스 매트릭스 (셀 = 클래스 줄 수)

| 역할 | INFINITE | VR | PRIVACY | DCA |
|---|---|---|---|---|
| 주문 계산기(순수) | InfiniteStrategy 199, ReverseInfiniteStrategy 79 | VrStrategy 201 | PrivacyStrategy 268 (+PrivacyValuationException 8) | 계산기 별도 없음 — DcaCycleOrderStrategy 196 안에 규칙 평가 포함, DcaConditions 113, DcaSchedule 72 |
| `CycleOrderStrategy` 어댑터 | InfiniteCycleOrderStrategy 182 | VrCycleOrderStrategy 110 | PrivacyCycleOrderStrategy 65 | DcaCycleOrderStrategy 196 (계산기 겸용) |
| position/입력 값객체 (matching) | InfinitePosition 166, ReverseModePosition 79 | VrPosition 88, StrategyVrDetail 56 | PrivacyPlan 39 | DcaStrategyConfig 63, DcaRule 113, DcaCondition 39, DcaSizing 24, DcaIndicators 43, DcaDecision 11, DcaRuleDecision 16 |
| 등록 정책 resolver | InfiniteCreationResolver 20 | VrCreationResolver 47 (+VrRampInput 55, VrRampParams 20, VrRampValidator 89, VrRegistration 52) | PrivacyCreationResolver 20 | DcaCreationResolver 20 |
| 전략 상세 영속(Entity+JpaRepo+Adapter+Port+도메인 record) | 약 306 (persistence 244 + port 38 + domain 24) | 약 320+ (persistence 264 + port 43 + domain 12+) | 없음(상세 테이블 없음) | 약 600+ (persistence 514 — 실행기록·룰·조건 포함 — + port 54 + domain 42+) |
| 배치 단계 | 공통 `TradingService`(분기) | 공통 `TradingService`(분기) + VrCycleRolloverService 179 | 공통 + PrivacyBaseGuard 54 | **별도 파이프라인**(코인): CryptoDcaRunner 125, CryptoDcaRoundExecutor 242, CryptoOrderSynchronizer 135, CryptoLedgerRecorder 63, CryptoDcaScheduler 34, DcaExecutionRecorder 90, DcaPlanInputCollector 194. 주식 DCA는 `TradingService.java:373`로 공통 흐름 |
| 검증/가드 | InfiniteMinSeedGuard 52 | VrRampValidator 89, VrReconfigureService 191 | PrivacyBaseGuard 54 (+privacy 모듈 PrivacyTradeValidationService 81) | DcaConfigService 51 |
| 상세 조립 | StrategyDetailAssembler 189 (공통, 전 타입 분기) | 위 + VrStrategyDetailService 76, VrSummary 24 | 위 | 위 + DcaSummaryAssembler 75 |
| 통계/백테스트 (stats) | runInfinite+InfiniteState ≈ 100 (추정) | runVr+rolloverIfDue+VrState ≈ 150 (추정) | runPrivacy+PrivacyState ≈ 95 (추정) | **미지원** (`BacktestEngine.java:67` 예외) |
| web DTO 전용 | — | VrConfigRequest 52 | — | Dca*Request/Response 8종 약 260 |
| 알림 문구 | (4개 모듈 밖 notify, 미측정) | — | PrivacyAlertNotifier 27 (notify 모듈) | DcaRoundSkippedEvent 20 |
| 전략명 포함 파일 합계(하한) | 1,083줄 | 1,642줄 | 454줄 (+privacy 모듈 1,054, 대상 밖) | 1,792줄 (+Crypto DCA 비Dca명 2파일 198) |

※ 이름 패턴(Infinite|Reverse / Vr / Privacy / Dca) 합 4,971줄 = 4개 모듈의 15.5%. 공통 서비스 안에 흩어진 분기 코드는 이 숫자에 안 잡힌다.

### A-2. 역할별 공통 인터페이스·베이스 유무

| 역할 | 공통 추상화 | 판정 |
|---|---|---|
| 주문 계산 | `CycleOrderStrategy`(182줄, default 메서드 약 12개: `requiresPrevClose`, `endsCycleOnLiquidation`, `tracksReverseMode`, `requiresRolloverCheck`, `capsIndividualOrders`, `fixesPricesAtPlan`, `recordsRounds`, `needsCapCheck`, `canSkipOrderComputation`…) + `CycleOrderStrategies` 레지스트리(24) + `StrategyCapability`(shared.kernel) | 있음. 단 **전략 차이를 boolean 플래그 메서드로 인터페이스에 누적**하는 형태 |
| 계산기(`*Strategy`) | 없음. 시그니처가 모두 다름: `InfiniteStrategy.buildOrders(position, date)`, `VrStrategy.buildOrders(position, ticker, …)`, `PrivacyStrategy.buildOrders(balance, price, plan)` | 공통 없음 |
| position 값객체 | 없음 (`InfinitePosition`/`VrPosition`/`PrivacyPlan+AccountBalance`/`DcaStrategyConfig` 각자). `PlanContext`가 infinite/privacy/vr/dca 입력 4개를 **nullable 필드로 전부 나른다**(`CycleOrderComputer.java:55-135`가 타입 무관하게 전 묶음을 조립) | 태그드 유니온을 null로 흉내 |
| 등록 resolver | `StrategyCreationResolver`(35) + `StrategyCreationResolvers` 레지스트리(25) | 있음 |
| 영속 detail | 없음 (타입별 Port·Adapter·Entity 개별) | 공통 없음 |
| 배치 단계 | 없음 (`TradingService` 하나가 분기) | 공통 없음 |
| 가드/검증 | 없음 (`InfiniteMinSeedGuard`/`PrivacyBaseGuard`/`VrRampValidator`/`DcaConfigService` 제각각) | 공통 없음 |
| 상세 조립 | 없음 (`StrategyDetailAssembler` 단일 클래스 안 분기) | 공통 없음 |

### A-3. 전략 타입 분기 전수 (trading-core 4개 모듈, `isInfinite/isVr/isPrivacy/isDca`·`StrategyType.X`·`case X`)

grep 라인 75개 = 선언 12(`Strategy.java:36-59` isX 4, `*CreationResolver.type()` 4, `*CycleOrderStrategy.cycleType()` 4) + **분기 지점 약 63**.

| 역할 | 파일:라인 | 분기 수 |
|---|---|---|
| 상세 조립(단건/배치 이중) | `StrategyDetailAssembler.java:42,48,64,69` (단건) / `:111,120,127,141,152` (배치) / `:168,174` | 11 |
| 플랜 입력 조립 | `CycleOrderComputer.java:64,80,91,128` | 4 |
| 등록 | `StrategyCreationService.java:76,80,111,122` | 4 |
| 등록 저장 | `StrategyCreationPersister.java:54-64` (switch 4케이스), `:78-80` (switch 3케이스) | 2 switch / 7 케이스 |
| 갱신·원장 | `StrategyUpdateWriter.java:58,61`; `CapitalLedgerService.java:78,183`; `StrategyService.java:177,237` | 6 |
| 유스케이스 가드 | `InfiniteMinSeedGuard.java:38`; `DcaConfigService.java:40`; `DcaExecutionQueryService.java:40`; `ManualTradingService.java:62`; `VrReconfigureService.java:68`; `CryptoDcaRunner.java:58` | 6 |
| 배치·플래너 | `TradingService.java:373`; `TradingCandidatePlanner.java:219` | 2 |
| web 응답 | `TradingCycleResponse.java:125` | 1 |
| 백테스트 | `BacktestEngine.java:64-67`(switch),`:74` 외 13곳 hit; `BacktestService.java:65,84,93,112,118` 외 7곳 hit | 약 20 (stats, hit 기준) |

→ 분기가 가장 몰린 파일: BacktestEngine(13) > StrategyDetailAssembler(12) > BacktestService(7)=StrategyCreationPersister(7).
→ 이미 다형성으로 치환된 곳은 주문 계산(`cycleStrategies.of(strategy.type())`)과 등록 resolver뿐.

### A-4. 구조가 80% 이상 같은 쌍 / 같은 역할인데 형태가 다른 쌍

유사도(정규화 라인 ratio):

| 80%+ 동형 쌍 | 유사도 | 줄 수 | 비고 |
|---|---|---|---|
| InfiniteCreationResolver ↔ PrivacyCreationResolver | 0.87 | 20/20 | `trading/domain/creation/` |
| InfiniteCreationResolver ↔ DcaCreationResolver | 0.87 | 20/20 | ticker 정책만 다름 — 3개를 파라미터화 가능 (CLAUDE.md 추출 기준 3곳 충족) |
| StrategyCreationResolvers ↔ CycleOrderStrategies | 구조 동일(`Map<StrategyType,X>` 레지스트리, 못 찾으면 ISE) | 25/24 | 텍스트 0.22지만 동형. 2곳이라 CLAUDE.md 기준으로는 유지 |

| 같은 역할인데 형태가 다른 쌍 | 유사도 | 줄 수 |
|---|---|---|
| InfiniteStrategy ↔ VrStrategy (계산기) | 0.14 | 199/201 |
| InfiniteCycleOrderStrategy ↔ VrCycleOrderStrategy ↔ PrivacyCycleOrderStrategy | 0.27~0.41 | 182/110/65 |
| InfiniteCreationResolver ↔ VrCreationResolver | 0.45 | 20/47 (+VrRamp* 216) |
| StrategyInfinite·Vr·Dca DetailPersistenceAdapter | 0.27~0.50 | 56/79/73 |
| BacktestEngine 내 runVr/runInfinite/runPrivacy (같은 골격 3벌) | 육안 확인: `plan*Orders`가 PlanContext 조립 → `strategies.of(T).plan(ctx)` → `applyBuyCap` 동형 (`:225-240`,`:272-286`,`:321-350`) | 각 ≈ 20~45 |

---

## B. 브로커 축

### B-1. 브로커 × 기능 어댑터 매트릭스 (셀 = 클래스 줄 수)

| 기능 | KIS | Toss | 메리츠 | 바이낸스 | 업비트 | Mock |
|---|---|---|---|---|---|---|
| 어댑터(포트 구현 facade) | KisBrokerAdapter 118 | TossBrokerAdapter 138 | MeritzBrokerAdapter 103 | BinanceExchangeAdapter 239 | UpbitExchangeAdapter 211 | MockBrokerAdapter 215 |
| 인증/토큰 | KisAuthApi 206, KisAdminTokenCoordinator 105 (+공용 DbTokenCoordinator 69, KisToken* 106) | TossAuthApi 162, TossDistributedTokenCoordinator 207, TossRedisTokenStore 229, TossTokenStore 39, AccountTokenEvictionAdapter 50 | MeritzAuthApi 140 (+공용 DbTokenCoordinator) | BinanceAuthApi 50 (요청별 HMAC 서명) | UpbitAuthApi 59 (요청별 JWT) | MockAuthApi 28 |
| 시세 | KisPriceApi 261, KisAdminPriceSource 82 | TossPriceApi 255, TossCandleApi 174, TossAdminPriceSource 59, UsdKrwRateCache 46 | MeritzPriceApi 130 | (어댑터 안) | (어댑터 안) | (TossPriceApi 재사용) |
| 잔고/체결/증거금 | KisTradingApi 334 | TossHoldingsApi 218 | MeritzTradingApi 162 | (어댑터 안) | (어댑터 안) | (어댑터 안) |
| 주문 접수·정정/취소 | KisOrderApi 171 | TossOrderApi 214 | MeritzOrderApi 212 | (어댑터 안) + BinanceClientOrderIds 22 | (어댑터 안) | (어댑터 안) |
| 오류 변환 | KisApiException 24 | TossApiException 33 (+ErrorBodyDecoder 23 공용) | MeritzApiException 32 | BinanceApiException 24 | UpbitApiException 24 | — |
| rate limit | KisHttpClient 내 CallIntervalGate(350ms)+EGW00201 재시도 (`KisHttpClient.java:123,126-184`) | TossHttpClient 내 관리자 GET만 429 재시도 (`:64-80`) | MeritzHttpClient 내 CallIntervalGate(110ms)+멱등 재시도 (`:39,133`) | 429/418 → BrokerRateLimitException 변환만 (`BinanceHttpClient.java:121`) | 동일 (`UpbitHttpClient.java:99`) | — |
| HTTP 클라이언트·설정 | KisHttpClient 220, KisConfig 23 | TossHttpClient 209, TossConfig 24 | MeritzHttpClient 192, MeritzConfig 20 | BinanceHttpClient 142, BinanceConfig 18 | UpbitHttpClient 148, UpbitConfig 20 | — |
| 심볼/거래소 레지스트리 | KisExchangeRegistry 44 | — | MeritzExchangeRegistry 38 | BinanceSymbolRegistry 34 | UpbitMarketRegistry 33 | — |
| 벤더 응답 모델 | KisResponseParser 70 + 중첩 record 26개 | TossResponseParser 29, TossResult 6, TossCandle 21, TossExchangeRate 16 + 중첩 record 21개 | MeritzResponse 84 + 중첩 record 11개 | 중첩 record 9개 | 중첩 record 6개 | 0 |
| 브로커 합계(전용 클래스) | 1,634 (+exception 24, 토큰 persistence 106) | 2,059 (+model 70) | 1,081 (+exception 32) | 505 (+24) | 471 (+24) | 243 |

### B-2. 포트 구현 골격

- 공통 골격 **없음**. 증권사형(KIS/Toss/Meritz/Mock)은 `BrokerCapabilitiesPort`(공통 6개 포트 묶음 인터페이스) 구현 facade가 `*TradingApi/*OrderApi/*PriceApi`로 **메서드 단위 위임**만 한다(KisBrokerAdapter 118줄 중 위임 약 14메서드). 코인형(Binance/Upbit)은 별도 `CryptoExchangePort`(약 10메서드)로 분리, 어댑터가 HTTP 클라이언트를 직접 호출.
- 어댑터 facade 간 유사도: KIS↔Meritz 0.66, KIS↔Toss 0.58. `*Config` 간 0.73. 반면 HttpClient 0.15~0.25, AuthApi 0.24, OrderApi 0.14, PriceApi 0.12~0.13, TradingApi 0.10 (**같은 책임을 다르게 구현**).
- 공용 유틸은 `adapter/out/internal` 4개 171줄(TokenCoordinator·DbTokenCoordinator·CallIntervalGate·ErrorBodyDecoder)이 전부이며 사용처가 KIS/Meritz/Toss에 부분적으로만 걸쳐 있다. broker.md 스스로 "더블체크락·전일종가 캐시·확정종가 폴백은 공용 유틸 없이 각 소비자 내부 인라인"이라고 기록.
- 벤더 예외 `*ApiException` 5종은 `BrokerApiException`(49) 상속(공통 상위 타입은 있음) — 각 24~33줄.

### B-3. 벤더 모델 줄 수

벤더 전용 `domain/model/{kis,toss,meritz,binance,upbit}` 합 174줄(대부분 예외)이고 응답 모델은 어댑터 클래스 **안의 중첩 record 73개**(KIS 26·Toss 21·Meritz 11·Binance 9·Upbit 6) + 파서/래퍼 4개(KisResponseParser 70, TossResponseParser 29, TossResult 6, MeritzResponse 84 = 189줄). 중첩 record 줄 수는 별도 측정 안 함(KisTradingApi 334·KisPriceApi 261의 상당 부분일 것으로 **추정**).

### B-4. 운영 비중 (근거: memory `project_broker_strategy.md` 2026-10-23 — 미국주식 메리츠 주력, 코인 바이낸스 주력, 토스 대시보드, 업비트·KIS 선택적/제거 가능; `Broker.java` enum)

| 구분 | 브로커 | 줄 수(전용 클래스+예외) | broker 모듈 7,632 대비 |
|---|---|---|---|
| 주력 | 메리츠 1,113 + 바이낸스 529 | 1,642 | 21.5% |
| 대시보드/보조 | 토스 | 2,129 | 27.9% |
| 선택·제거 가능 | KIS 1,764 (KisToken persistence 106 포함 — 단 DbTokenCoordinator는 Meritz도 사용) + 업비트 495 | 2,259 | 29.6% |
| 테스트용 | Mock | 243 | 3.2% |
| 공통(포트 275·공용모델 483·service 289·internal 171·marketdata 92·web 46 등) | — | 1,359 | 17.8% |
| 비주력(토스+KIS+업비트+Mock) 합 | | 4,631 | **60.7%** |

주의: 토스는 Mock의 시세(`TossPriceApi`)·관리자 시세·환율·캔들 통계의 유일 제공자라 "제거 비용이 큼". 해당 줄 수 전부가 삭제 후보는 아님.

---

## C. 배치·스케쥴러 흐름

| 배치 | 진입 클래스(adapter/in/schedule) | 서비스 흐름 | 비고 |
|---|---|---|---|
| 개장(OPEN) | TradingOpenScheduler 59 (22:00 KST) | `TradingExecutionFacade` → `TradingService.runOpenBatch`(`:315`) → `placePreOpenDcaOrders`(`:371`)·`planAndPlaceAtOpen`(`:395`) | 락 `TradingBatchJob.OPEN` |
| 마감(CLOSE) | TradingCloseScheduler 113 (04:30 KST) | Facade → `TradingService.runCloseBatch`(`:72`) → `placeAll`(`:253`)·`reportAll`(`:286`), 재기동 시 `rebuildPlacedStates`(`:201`) | 락 `CLOSE`, 접수 창 판정·정지 전략 리포트 포함 |
| 보정 | (스케쥴러 아님) BuyOrderPriceCapper 167이 `TradingCandidatePlanner`/`TradingOrderSlots`/`TradingOrderExecutor`에서 호출 | 주문 계획 중 캡 초과 BUY 재산정 | workflow-capper.md |
| 재기동 재개 | TradingBatchResumer 108 → 각 스케쥴러 `resume()`/`resumeReport()` | 동일 `TradingService` 경로 재진입 | slot 멱등 전제 |
| 코인 DCA | CryptoDcaScheduler 34 | `CryptoDcaRunner` 125 → `CryptoDcaRoundExecutor` 242 → `CryptoOrderSynchronizer` 135·`CryptoLedgerRecorder` 63·`DcaExecutionRecorder` 90 | 주식 흐름(TradingService)과 **독립 파이프라인** |
| 수동 실행 | (web) ManualTradingService 176 (의존 14) | `TradingCandidatePlanner`·`StrategyOrderPlanBuilder`·`TradingOrderPlanner`·`BuyOrderPriceCapper`·`TradingOrderExecutor` 재사용 | 배치 코드와 호출 서비스가 겹침 |
| 미리보기 | TradingPreviewService 311 (의존 12) | `StrategyOrderPlanBuilder`·`TradingCandidatePlanner` 재사용 | 읽기 전용 |

- 개장/마감 스케쥴러 골격 중복: `run()`/`runNow()`/`resume()` + `schedulerLockService.tryRun/takeOver` + `jobRunner.run(label, buildAll, useCase::x)` + `heartbeatPort.pingX()` 가 거의 동일(Open 59줄 vs Close 113줄; 마감은 접수 창 판정·정지 전략 합류·리포트 재개가 추가). 공통 골격은 있으나 추상화 없음.
- **한 흐름이 거치는 서비스 수(마감 배치, 추정)**: `TradingService` 직접 의존 18개(priceFetcher, candidatePlanner, orderExecutor, reporter, marketEventNotifier, parallelRunner, batchGuard, balanceLoader, privacyBaseGuard, cycleOrderStrategies, 포트 8…)에서 한 단계 더 들어가 `CycleOrderComputer`→`StrategyOrderPlanBuilder`→`TradingOrderPlanner`→`BuyOrderPriceCapper`→`OrderSubmitter`→`TradingReporter`→`CyclePositionPersistor`→`CycleRotationService` 까지 이어져 **서비스급 클래스 약 20개**를 통과한다(grep으로 확인한 호출 관계 기준, 완전 측정 아님).
- 호출 다수 클래스(서비스/support 내 참조 파일 수): `TradingCandidatePlanner` ← 7, `BuyOrderPriceCapper` ← 5, `TradingOrderExecutor` ← 5, `StrategyOrderPlanBuilder` ← 3 — 배치·수동·미리보기·DCA 수집 4갈래가 같은 계획기를 공유하는 구조.

---

## D. trading 모듈 내부 분포

### D-1. 하위 패키지

| 패키지 | 파일 | 줄 |
|---|---|---|
| application/service | 56 | 6,988 |
| adapter/out/persistence | 43 (+policy 3 = 118줄) | 3,083 |
| domain/model | 50 | 1,319 |
| adapter/in/web/dto | 21 | 929 |
| application/service/support | 12 | 740 |
| adapter/in/web | 9 | 654 |
| application/port/output | 21 | 576 |
| adapter/in/schedule | 7 | 458 |
| domain/creation | 12 | 402 |
| application/event | 17 | 211 |
| adapter/in/redis | 5 | 190 |
| application/usecase | 13 | 182 |
| 기타(event·feargreed·config·heartbeat·redis·security 등) | 17 | 592 |

service+support 합 7,728줄 = trading의 47%.

### D-2. 400줄 초과 클래스 (4개 모듈 전체에서 3개뿐)

| 클래스 | 줄 | 책임 |
|---|---|---|
| stats/domain/backtest/BacktestEngine | 582 | 백테스트 일 단위 시뮬레이션 (VR·INFINITE·PRIVACY 3경로 + 상태 클래스 4개) |
| trading/application/service/TradingService | 478 | 마감·개장 배치 오케스트레이션(대기·계획·접수·리포트·재개) |
| stats/application/service/TradingStatsService | 435 | 사이클·통계 집계 + 캐시 무효화 리스너(constraints.md 기존 예외) |

300~399: InvestmentIndexCalculator 335, KisTradingApi 334, TradingCandidatePlanner 329, OrderPersistenceAdapter 327, TradingPreviewService 311.

### D-3. application/service 서비스 개수·의존 수

(`@Service`/`@Component` 클래스의 `private final` 필드 수 또는 명시 생성자 파라미터 수, 정규식 집계라 ±1 오차 가능 **(추정)**)

- trading application/service(+support 포함): 클래스 59개, **평균 의존 5.68**
- stats application/service: 8개, 평균 5.0 / broker application/service: 4개, 평균 1.25 / trading support 9개 평균 2.33

의존 8개 이상(19개): TradingService 18, StrategyService 17, ManualTradingService 14, TradingCandidatePlanner 13, TradingPreviewService 12, VrReconfigureService 11, CapitalLedgerService 10, OrderCancelService 10, StrategyDetailAssembler 10, StrategyCreationService 10, StrategyUpdateWriter 10, ManualTradeCorrectionService 9, TradingOrderExecutor 9, StrategyHistoryQueryService 9, CryptoDcaRunner 9, CyclePositionPersistor 9, DcaPlanInputCollector 8, StrategyOrderPlanBuilder 8, StrategyCreationPersister 8.
(stats: CryptoMarketIndicatorService 9)

→ 전략 타입 분기 파일(A-3)과 의존 8+ 서비스 목록이 **상당수 겹친다**(StrategyService, StrategyCreationService, StrategyUpdateWriter, StrategyDetailAssembler, CapitalLedgerService, VrReconfigureService, CryptoDcaRunner, StrategyCreationPersister …): 타입별 협력 포트(`StrategyInfiniteDetailPort`, `StrategyVrDetailPort`, `StrategyDcaDetailPort`, `CyclePositionInfiniteDetailPort`, `StrategyCycleVrPort` 5종)가 공통 서비스 생성자에 전부 주입되는 구조 때문(추정).

---

## E. stats

- **백테스트는 matching 커널을 재사용한다.** `BacktestEngine`이 `CycleOrderStrategies.of(T).plan(ctx)`, `capBuyOrders`, `fitBuysToBudget`, `PriceCapPolicy`를 직접 호출(`:121,159,234,280,341`), 운영과 같은 계획 코드를 쓴다. 별도 구현된 것은 (1) **체결 시뮬레이션** `FillSimulator`(58줄)와 (2) **전략별 일일 상태 관리**(`DayState`+`VrState`/`InfiniteState`/`PrivacyState`, `:384-582` 약 200줄)뿐.
- 중복 계산 로직:
  - 체결 판정 규칙이 `FillSimulator`(stats)와 `MockBrokerAdapter`(`:127,157`, broker)에 **같은 규칙으로 두 벌**(MockBrokerAdapter 주석이 "broker는 trading 타입을 참조할 수 없어" 복제라고 명시).
  - `runVr/runInfinite/runPrivacy` 3벌 + `plan*Orders` 3벌의 PlanContext 조립이 `CycleOrderComputer`의 운영용 조립(`:55-135`)과 같은 일을 하는 **병행 구현**(입력 구성만 stats용으로 다시 작성).
  - 백테스트는 DCA 미지원이라 DCA 규칙 평가(`DcaCycleOrderStrategy.decideRule`)는 백테스트 경로가 없음.
- BacktestService 240줄 안에 타입별 검증 분기 7곳(`:65,84,93,112,118`…).
- TradingStatsService 435·InvestmentIndexCalculator 335 내부 중복은 이번 측정에서 정밀 확인하지 않음.

---

## 평행 중복 상위 5

| # | 평행 구조 | 근거(file:line) | 규모 | 한 줄 해법 |
|---|---|---|---|---|
| 1 | 전략별 상세 영속 스택 반복 (Entity+JpaRepo+Adapter+Port+record × Infinite/Vr/Dca ×(strategy·cycle·position 단계)) | `trading/adapter/out/persistence/StrategyInfinite*`·`CyclePositionInfinite*`·`StrategyVr*`·`StrategyCycleVr*`·`StrategyDca*`, port `Strategy{Infinite,Vr,Dca}DetailPort`·`CyclePositionInfiniteDetailPort`·`StrategyCycleVrPort` | 전용 파일 약 20개 ≈ 1,300줄(추정, persistence 3,083줄 중) | 타입별 `DetailStore` 구현만 두고 공통 detail 포트 한 쌍으로 모으기 |
| 2 | BacktestEngine의 전략별 3벌(runX·planXDay·planXOrders·XState) | `stats/domain/backtest/BacktestEngine.java:172,242,288` / `:225,272,321` / `:463,509,555` | ≈ 350줄(추정) | 타입별 `PlanContext` 공급자만 전략 쪽에 두고 `runDays` 하나로 구동 |
| 3 | StrategyDetailAssembler 단건/배치 이중 구현과 타입 분기 | `StrategyDetailAssembler.java:42-75` ↔ `:116-160` (isVr/isInfinite/isDca 각각 2벌) | 11개 분기 | 배치 버전 하나로 통일하고 단건은 `List.of(x)` 호출 |
| 4 | Binance↔Upbit 코인 어댑터·HTTP 클라이언트 골격 | `BinanceExchangeAdapter.java:48-239` ↔ `UpbitExchangeAdapter.java:51-211` (0.31), `BinanceHttpClient.java:121` ↔ `UpbitHttpClient.java:99` (429/418 변환 동일), `*Config` 0.76, `*AuthApi` 0.49 | 약 1,000줄 | 공통 `CryptoHttpClient`(오류 변환·재시도)·완성 일봉/주문 상태 변환 지원 클래스 추출 |
| 5 | 증권사 facade 위임 보일러플레이트 + Config | `KisBrokerAdapter.java:19-118`, `MeritzBrokerAdapter.java:18-103`, `TossBrokerAdapter.java:20-138` (유사도 0.58~0.66), `KisConfig`/`TossConfig`/`MeritzConfig` 0.73 | facade 359줄, 대부분 `return xApi.m(...)` | `*TradingApi`가 포트를 직접 구현하도록 합쳐 위임 계층 제거 |

(차점: Infinite/Privacy/Dca CreationResolver 0.87 동형 20줄 ×3 — `trading/domain/creation/`; 개장/마감 스케쥴러 골격 `TradingOpenScheduler.java`↔`TradingCloseScheduler.java`.)

## 비일관 상위 5

| # | 같은 문제를 다르게 푼 곳 | 근거(file:line) | 한 줄 해법 |
|---|---|---|---|
| 1 | rate limit·재시도·호출 간격을 브로커마다 다른 방식으로: KIS 350ms 게이트+EGW00201 재시도, Meritz 110ms 게이트+멱등 재시도, Toss 관리자 GET만 429 재시도·게이트 없음, Binance/Upbit 변환만 | `KisHttpClient.java:123,126-184`; `MeritzHttpClient.java:39,133`; `TossHttpClient.java:64-80`; `BinanceHttpClient.java:121`; `UpbitHttpClient.java:99` | gate·backoff·멱등 플래그를 한 정책 객체로 모아 각 HttpClient가 위임 |
| 2 | 토큰 관리 3계통: DB 코디네이터(KIS·Meritz), Redis 분산(Toss 약 436줄), KIS 관리자 전용(계약 미구현 105줄), 코인은 요청별 서명 | `DbTokenCoordinator.java`, `TossRedisTokenStore.java`+`TossDistributedTokenCoordinator.java`, `KisAdminTokenCoordinator.java`(broker.md:16 "계약 미구현") | `TokenCoordinator` 계약으로 통일하고 저장소(DB/Redis)만 교체 |
| 3 | 전략 다형성이 "주문 계산"에서만 끝남: 인터페이스는 플래그 메서드 12개로 비대해지고 나머지는 `isX()`로 서비스에 직접 분기(분기 약 63곳) | `CycleOrderStrategy.java`(default 플래그 약 12개); `Strategy.java:36-59`; A-3 표 | 등록 저장·갱신·상세 조립·가드도 `StrategyType`별 핸들러 인터페이스(기존 레지스트리 패턴)로 이동 |
| 4 | 전략마다 position/입력 표현이 다르고 `PlanContext`가 4종 입력을 nullable로 전부 운반 | `CycleOrderStrategy.PlanContext` 생성자(8인자), `CycleOrderComputer.java:55-135`, `BacktestEngine.java:225-240,272-286,321-350`에서 `null`로 채워 호출; `minRequiredDeposit(price, privacyPlan, divisionCount)` 시그니처 | 전략별 sealed `PlanInput` record로 바꾸고 조립을 전략 쪽이 소유 |
| 5 | DCA 실행 파이프라인이 둘: 코인은 전용 러너·실행기·동기화·원장 기록, 주식은 `TradingService` 공통 흐름 + 포트 계열도 `CryptoExchangePort` vs `BrokerCapabilitiesPort`로 이원화; 체결 규칙은 stats/broker 두 벌 | `CryptoDcaRunner.java:58`(의존 9) ↔ `TradingService.java:373`; `CryptoExchangePort.java:18` ↔ `BrokerCapabilitiesPort.java`; `MockBrokerAdapter.java:127,157` ↔ `FillSimulator.java` | 시장 종류별 실행기 하나로 배치 골격(계획→접수→동기화→원장)을 공유 |

## 측정 한계

- 정규화 diff 유사도는 라인 시퀀스 기준이라 "책임은 같지만 라인이 다른" 경우를 낮게 평가한다(HttpClient 0.15~0.25는 책임 동일). "평행 중복"은 유사도 + 표본 읽기 병용.
- 의존 수는 정규식 집계(±1 오차). 전략별 합계는 파일명 패턴 하한이며 공통 서비스 안 분기 코드는 포함하지 않음.
- 벤더 중첩 record 줄 수, stats 서비스 내부 중복, 4개 모듈 밖 notify/privacy의 전략별 코드는 미측정.
