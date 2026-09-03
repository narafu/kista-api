## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (record aggregate 분리 제약 → constraints.md "Account ↔ Strategy 분리").

```
common/          ← 공통 유틸리티 (Spring/JPA 독립)
  UsTradeDates   — KST↔US 거래일 ±1일 변환. 사용 허용 위치: KisTradingApi(KIS API는 US 거래일 기준)·MarketCalendarPersistenceAdapter·KisPriceApi(dailyprice BYMD)뿐 — 도메인·서비스·orders persistence에서 사용 금지 (→ constraints.md "시간 기준 정책")

domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체(record), 어그리게이트별 서브패키지: user/account/strategy/auth/admin/settings/asset (broker/kis/toss는 com.kista.broker.domain.model로, order 전체 및 strategy 실행 이력 17개는 com.kista.trading.domain.model로, market(공포탐욕지수·시장휴장일)은 com.kista.market.domain.model로, privacy(FIDA 기준 매매표 + PrivacyDates 발행일↔거래일 헬퍼)는 com.kista.privacy.domain.model로, stats(통계 20타입) + backtest(커맨드·결과 5타입)는 com.kista.stats.domain.model(+.backtest)로 이전됨)
                   Strategy.Type/Status/Ticker/CycleSeedType 등은 Strategy record의 nested enum (→ constraints.md "Account ↔ Strategy 분리")
                   `strategy/` 잔류 8개(Strategy/StrategyVersion/StrategyInfiniteDetail/StrategyVrDetail/StrategyDetail/StrategySeedPreview/RegisterStrategyCommand/UpdateStrategyCommand)는 설정 이력 계층(등록·수정) — 실행 이력(StrategyCycle/CyclePosition 등)은 trading으로 이전, 설정 계층은 향후 strategy 모듈 후보로 legacy 잔류
  (strategy/ 디렉토리 없음 — 전략 구현 클래스(Infinite/ReverseInfinite/Privacy/VrStrategy) 14개 전체가 com.kista.trading.domain.strategy로 이전됨, 아래 "com.kista.trading/" 참고)
  (domain/port/in, domain/port/out 폐지됨 — 레거시 포트는 application/usecase, application/port/output로 이전됨, 아래 application/ 절 참고)

application/
  usecase/       ← UseCase/Query 인터페이스 (인바운드 포트, 레거시 30개 — 옛 domain/port/in에서 이전됨. 각 모듈(finance/notify/broker/trading)의 usecase는 해당 모듈 절 참고)
  port/output/   ← *Port 접미사 아웃바운드 포트 인터페이스 (레거시 29개 — 옛 domain/port/out에서 이전됨. 각 모듈의 port는 해당 모듈 절 참고)
  service/       ← UseCase 구현체 (package-private @Service), Port를 통해서만 외부 호출, 어그리게이트별 서브패키지: account/strategy/user/admin/settings/auth/asset (broker는 com.kista.broker.application.service로, trading은 com.kista.trading.application.service로, market은 com.kista.market.application.service로, privacy는 com.kista.privacy.application.service로, stats/backtest/portfolio는 com.kista.stats.application.service로 이전됨 — 아래 "com.kista.trading/"·"com.kista.market/"·"com.kista.privacy/"·"com.kista.stats/" 참고)
  event/         ← 도메인 이벤트(application 레이어) — 사용자 승인/거부/재신청/신규가입/탈퇴 (사이클 종료/신규시작·매매리포트·주문취소실패 등 매매 관련 이벤트는 com.kista.trading.application.event로 이전됨). 전부 Spring Modulith Event Publication Registry로 추적됨(`event_publication` 테이블, 재기동 시 미완료 이벤트 자동 재시도) — 리스너 annotation은 기존 @TransactionalEventListener 그대로, User/Account를 담던 이벤트는 평문 비밀값이 DB에 저장되지 않도록 ID(userId/accountId)만 담고 리스너가 UserPort/AccountPort로 재조회한다

adapter/in/
  schedule/      ← RefreshTokenCleanupScheduler 등 배치 스케쥴러 (매매 스케쥴러 TradingOpenScheduler/TradingCloseScheduler/BatchContextFactory는 com.kista.trading.adapter.in.schedule로, 공포탐욕지수·시장휴장일 캘린더 갱신 스케쥴러(FearGreedScheduler/MarketCalendarRefreshScheduler)는 com.kista.market.adapter.in.schedule로, KB Land·시장지수 동기화 스케쥴러(KbLandHousingBenchmarkScheduler/KbLandPriceIndexScheduler/MarketIndexPriceSyncScheduler)는 com.kista.stats.adapter.in.schedule로 이전됨) — 정확한 실행 시각·락 TTL은 `scheduler-time-table.md` 참고
                   SchedulerJobRunner(공통 실행 골격 — 시작/완료 알림·인터럽트 처리)
                   SchedulerLockService(package-private 분산 락 — tryRun(lockKey, timeout, task); @ConditionalOnProperty(scheduler.enabled) 로컬 중복 실행 방지) — Postgres 기반(`scheduler_locks` 테이블, DB 서버 시각 `now()` 기준 `INSERT ... ON CONFLICT ... WHERE lock_until <= now()`)이라 다중 인스턴스라도 시계 편차 없이 안전하게 경쟁, 어느 한쪽만 매 사이클 실행
  web/           ← REST Controller + DTO — Auth/Account/TradingCycle/Dashboard/Statistics(KIS 전용 live)/TossStatistics(Toss 전용 live)/Meta(enum SSOT)/Settings/Fcm/TradeStream(SSE)/Admin*/Asset/AssetMonthlyCheck/RuntimeConfig/AdminSettings/AdminObservability/AdminScheduler/AdminPing/DevAuth(local 전용)/ClientErrorLog (OrderCancelController는 com.kista.trading.adapter.in.web으로, FearGreedController/MarketHolidayController는 com.kista.market.adapter.in.web으로, FidaOrderController(`/api/internal/**`)는 com.kista.privacy.adapter.in.web으로, StatsController(DB 근사 집계)/BacktestController(과거 일봉 시뮬레이션, 계좌 무관)는 com.kista.stats.adapter.in.web으로 이전됨 — 넷 다 internal, NamedInterface 미공개. AdminPrivacyTradeController는 admin 소유라 레거시 잔류)
                   상세 라우팅·응답 형식 차이 → 아래 "DashboardController vs StatisticsController" 참고
  web/security/  ← JwtAuthFilter (Bearer JWT), InternalTokenAuthFilter (X-Internal-Token 서버간 인증)

adapter/out/
  (broker/kis/toss/mock/persistence/kistoken은 com.kista.broker.adapter.out.{internal,kis,toss,mock,persistence}로 이전됨 — 아래 "com.kista.broker/" 참고)
  marketdata/    ← CommonMarketPriceFeed — 계좌 자격증명 불필요한 공통 시세 조회 인터페이스, com.kista.broker.adapter.out.toss.TossPriceApi가 구현(모의계좌가 재사용)
  (feargreed/·persistence/calendar/·persistence/feargreed/ 폐지됨 — com.kista.market.adapter.out.{feargreed,persistence.calendar,persistence.feargreed}로 이전됨, 아래 "com.kista.market/" 참고)
  (persistence/privacy/ 폐지됨 — com.kista.privacy.adapter.out.persistence로 이전됨, 아래 "com.kista.privacy/" 참고)
  (kbland/·alpaca/·persistence/housingbenchmark/·persistence/marketindex/ 폐지됨 — com.kista.stats.adapter.out.{kbland,alpaca,persistence.housingbenchmark,persistence.marketindex}로 이전됨, 아래 "com.kista.stats/" 참고. AlpacaIndexPriceAdapter가 stats로 옮겨가며 레거시 adapter/out/alpaca 디렉토리 소멸 — market판 AlpacaCalendarAdapter만 남고 빈 이름 충돌은 계속 marketAlpacaConfig로 회피)
  redis/         ← RedisBlacklistAdapter (BlacklistPort — userId/JTI 단위 JWT 블랙리스트, TTL 기반)
  persistence/   ← JPA 인프라 + 어그리게이트별 서브패키지, 각각 Entity + *JpaRepository(package-private) + *PersistenceAdapter(Port 구현) 3종 구성
                   DB 스키마 3분리(kista/finance/reference, V15 마이그레이션): kista=순수 매매 도메인(계좌·주문·전략·포지션), finance=가계부, reference=외부 참조·시장 데이터(FIDA PRIVACY 기준 매매표 포함, 전역 공유·비개인 데이터가 기준). 인증/관리자/로그/알림 성격 테이블(users/user_settings/user_notification_prefs/refresh_tokens/broker_tokens/admin_runtime_settings/audit_logs/app_error_logs/fcm_device_tokens/scheduler_locks)은 플랫폼 공통이라 public 유지. 신규 테이블은 이 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 kista라 생략 시 validate 실패) — nativeQuery/JdbcTemplate/raw SQL은 DB 유저 search_path(`kista, finance, reference, public`)로 unqualified 이름이 자동 해석되므로 스키마 접두사 불필요
  sse/           ← SseEmitterRegistry(사용자별 SSE), TradeSseEmitterRegistry(매매 이벤트 SSE)
  kakao/         ← KakaoOAuthAdapter — 카카오 소셜 로그인
  heartbeat/     ← HeartbeatAdapter — 스케쥴러 dead-man's switch 핑, Open/Close 스케쥴러가 호출
  crypto/        ← AesCryptoService(AES-256, persistence 경계에서만 사용), AccountNoHasher(계좌번호 결정론적 HMAC-SHA256 해시 — 전역 중복 체크용)

com.kista.finance/   ← Spring Modulith 첫 이전 모듈(CLOSED) — 가계부 애그리게이트, 위 레거시 4패키지와 별개 최상위. 내부는 동일 Hexagonal 레이어 유지
  domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — "domain" NamedInterface로 공개
  application/usecase/  ← UseCase 인터페이스(9개, 옛 domain/port/in에서 이전) — "usecase" NamedInterface로 공개
  application/port/output/ ← *Port 접미사 포트(7개, 옛 domain/port/out에서 이전) — "port" NamedInterface로 공개
  application/service/  ← FinanceAccountService/FinanceBudgetService/FinanceCategoryService/FinanceGroupService/FinanceTransactionService/AssetSnapshotService/BulkFinanceRegisterService/MonthlyClosingService/FinanceRegistrationReminderNotifier — 모두 internal(외부 비공개)
  adapter/in/web/     ← Finance*Controller/AssetSnapshotController/MonthlyClosingController/AdminFinanceCategoryController(경로만 /api/admin/**, finance 소유 유지) + dto/
  adapter/in/schedule/ ← FinanceRegistrationReminderScheduler
  adapter/out/persistence/ ← Entity + *JpaRepository + *PersistenceAdapter 3종

com.kista.notify/    ← Spring Modulith 2번째 이전 모듈(CLOSED) — Telegram/FCM 알림 발송 애그리게이트, 위 레거시 4패키지와 별개 최상위. domain 패키지 자체가 없고(모델 없음) application.port.output(공개 계약, "port" NamedInterface) + adapter만 존재하는 얇은 게이트웨이 모듈(자체 UseCase 없음, 레거시 application.usecase를 그대로 소비)
  application/port/output/ ← NotifyPort/UserNotificationPort/FcmDeviceTokenPort/TelegramBotInfoPort — "port" NamedInterface로 공개
  adapter/in/telegram/ ← TelegramWebhookController + TelegramBotService, TelegramApiClient(package-private) + TelegramUpdate
  adapter/out/gateway/ ← TelegramAdapter(관리자봇), CompositeUserNotificationAdapter → TelegramUserNotificationAdapter + FcmAdapter(사용자 알림), TelegramBotInfoAdapter/TelegramHttpClient/TelegramConfig/TelegramProperties/FcmConfig + 이벤트 리스너 6종(CycleEndedNotifier/CycleLifecycleNotifier/OrderCancelFailureNotifier/TradingReportNotifier/UserDeletedNotifier/TradingAlertNotifier) — TradingAlertNotifier는 trading이 발행하는 6개 이벤트(TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/MarketCloseEvent/BatchInterruptedEvent)를 `@TransactionalEventListener(fallbackExecution=true)`로 구독 — trading이 notify 포트를 직접 호출하던 11개 지점을 이벤트 발행으로 전환해 notify→trading→notify 순환을 제거한 결과물(trading.application.event가 "event" NamedInterface로 공개)
  adapter/out/persistence/ ← FcmDeviceTokenEntity + FcmDeviceTokenJpaRepository + FcmDeviceTokenPersistenceAdapter

com.kista.broker/    ← Spring Modulith 3번째 이전 모듈(CLOSED) — KIS/Toss/Mock 증권사 연동 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"port"·"application" 3개 NamedInterface 공개, adapter/out은 의도적으로 비공개(모듈 내부 구현)
  domain/model/       ← Currency/DailyTransaction*/Execution/MarginItem/PresentBalanceResult 등 공통 불변 값 객체 + broker↔trading 순환 제거로 신설된 broker 소유 타입: Direction/OrderType(trading의 동명 enum과 값 집합만 동일 — 모듈 경계상 공유 불가라 별도 소유)/PriceSnapshot(현재가+전일종가, trading판과 필드 동일한 복제)/BrokerBalance(LiveBalancePort 반환값 — trading이 이 값으로 자신의 AccountBalance를 구성)/OrderInstruction·OrderResult(BrokerOrderCorrectionPort.place() 요청/응답)/CancelInstruction(BrokerOrderCorrectionPort.cancel() 요청)/PlacedOrderView·PositionView(MockSimulationDataPort 반환용 얇은 뷰)
  domain/model/kis/   ← KisApiException 등 KIS 전용 도메인 모델
  domain/model/toss/  ← Toss 전용 도메인 모델 — domain/model과 함께 "domain" NamedInterface로 병합 공개
  application/port/output/ ← 브로커 Capability 인터페이스(*Port) 총 16개 — 공통 7개(KIS/Toss/Mock 모두 구현) + BrokerAdapterPort(라우팅 마커) + BrokerConnectionTestPort(*AuthApi 클래스가 구현) + BrokerTokenCachePort(KisTokenPersistenceAdapter가 구현) + MockSimulationDataPort(MockBrokerAdapter 전용 — AlpacaCalendarAdapter→MarketHolidayStorePort 패턴의 역방향 적용: 데이터를 필요로 하는 broker가 포트를 정의하고, 데이터를 가진 trading이 `trading.adapter.out.MockSimulationDataAdapter`로 구현) + Toss 전용 5개. "port" NamedInterface로 공개. BrokerConnectionTestPort: 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅 — verifyAccount→brokerAccountCode(KIS: null, Toss: accountSeq)
  application/service/ ← BrokerAdapterRegistry(public, require(account, Port.class)/find())/BrokerConnectionTesters(계좌 등록 전 자격증명 검증)/BrokerCallGuard — "application" NamedInterface로 공개
  adapter/out/kis/    ← KisHttpClient(공통 헤더 + executeWithRetry: 401 시 거절된 토큰을 조건부 무효화한 후 최신 토큰으로 1회 재시도)/KisAuthApi/KisOrderApi/KisPriceApi/KisTradingApi/KisResponseParser/KisExchangeRegistry/KisConfig/KisTokenCoordinator/KisBrokerAdapter(BrokerConnectionTestPort는 KisAuthApi가 구현)
  adapter/out/toss/   ← TossHttpClient/TossConfig/TossAuthApi/TossCandleApi/TossHoldingsApi/TossOrderApi/TossPriceApi/TossMarketApi/TossResponseParser/TossResult/TossMarketCalendarCache/TossStockInfoCache/UsdKrwRateCache
                        TossDistributedTokenCoordinator + TossRedisTokenStore(계좌·관리자 Redis canonical token; TTL owner lease+원자적 generation INCR; generation counter/canonical generation을 비교하는 Lua fencing CAS; owner-safe unlock; SHA-256 최근 발급 fingerprint 2초 TTL) — 관리자(admin) 토큰은 Account가 없어 TokenCoordinator 범위 밖 별도 public 메서드(TossTokenStore)
                        TossBrokerAdapter(공통 7개 + Toss 전용 5개 Port 구현; BrokerConnectionTestPort는 TossAuthApi가 구현)
  adapter/out/mock/   ← MockBrokerAdapter(BrokerConnectionTestPort는 MockAuthApi가 구현) — 증권사 API 호출 없이 DB(cycle_position/orders) 기반 잔고·체결 시뮬레이션. 시세는 레거시 adapter/out/marketdata/CommonMarketPriceFeed 경유
                        getLiveBalance()의 usdDeposit은 계좌 내 전략 전체 합산값(TradingOrderBudgetAllocator가 대표 전략 1개로 계좌 전체 BUY 예산을 판단하는 기존 계약에 맞춤 — 전략별 값을 그대로 반환하면 다른 전략 잔고로 오판정)
  adapter/out/internal/ ← TokenCoordinator(계좌 토큰 obtain/recover 공통 계약 — 순수 adapter 내부 인터페이스, KIS/Toss 둘 다 구현하지만 폴리모픽 주입 지점은 없음: KisAuthApi/TossAuthApi 각자 구체 타입을 직접 주입)/DoubleCheckedTokenCache(KisTokenCoordinator 전용 JVM 내 토큰 캐시 — 1차 조회 → miss 시 계좌별 락 → 2차 double-check → 신규 발급; `BrokerTokenCachePort.saveToken`/`invalidateToken`은 REQUIRES_NEW로 락 해제 전 독립 커밋)/PrevCloseCache(전일종가 캐시, 현재 사용처는 TossPriceApi뿐)
  adapter/out/persistence/ ← KisTokenEntity + KisTokenJpaRepository + KisTokenPersistenceAdapter (옛 persistence/kistoken)

com.kista.trading/   ← Spring Modulith 4번째 이전 모듈(CLOSED) — 주문/사이클 실행 이력/주문생성 전략 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"usecase"·"port"·"event"·"schedule" 5개 NamedInterface 공개 — application.service·adapter.out.*은 의도적으로 비공개(모듈 내부 구현)
  domain/model/       ← 주문(Order 등 8개 전체) + 사이클 실행 이력(AccountBalance/BatchContext/BootstrapPosition/CycleHistoryPage/CyclePosition/CyclePositionHistoryEntry/CyclePositionInfiniteDetail/DstInfo/InfinitePosition/PriceSnapshot/ReconfigureVrCommand/ReverseModePosition/StrategyCycle/StrategyCycleVrDetail/TradingReport/TradingSnapshot/VrPosition 등) 불변 값 객체 — domain.strategy와 함께 "domain" NamedInterface로 병합 공개. legacy `Strategy`/`StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail` 등 설정 이력 계층은 여기로 옮겨오지 않고 레거시 최상위 `domain/model/strategy`에 남아있다(향후 strategy 모듈 후보)
  domain/strategy/    ← CycleOrderStrategy 계열 전략 구현 클래스(Infinite/ReverseInfinite/Privacy/VrStrategy) 14개 전체 — CycleOrderStrategy capability 패턴 SSOT(아래 "CycleOrderStrategy Capability 패턴" 참고), PriceCapPolicy(매수 가격 캡 배수 SSOT). "domain" 이름으로 병합 공개
  application/usecase/ ← TradingExecutionUseCase/VrReconfigureUseCase — legacy TradingCycleController가 참조. "usecase" 이름으로 공개
  application/port/output/ ← OrderPort/CyclePositionPort/CyclePositionInfiniteDetailPort/StrategyCyclePort/StrategyCycleVrPort/TradingErrorReportPort — "port" 이름으로 공개
  application/service/ ← internal(비공개) — TradingExecutionFacade(preview/executeManually/cancelOrder/cancelByCycle/execute/executeBatch 단일 진입점), TradingService(배치·단건 실행 코어)
                         PreviewDepositCache — TradingBuyCompetitionSimulator 전용 계좌 단위 라이브 예수금(usdDeposit) 짧은 TTL(3초) 캐시 + 계좌별 락으로 동시 miss 단일화. usdDeposit은 ticker 무관 계좌 전체 값이라 계좌당 전략 N개의 preview 병렬 호출을 실제 조회 1회로 축소. 실주문 집행 경로(ManualTradingService/TradingOrderBudgetAllocator)는 미사용 — 항상 최신값 직접 조회
                         TradingOrderBudgetAllocator — 계좌별 slot-aware BUY/SELL 독립 예산 배정 (우선순위·실패 격리·제외 규칙 상세 → workflow.md "스케쥴러 주문 예산 배정")
                         live 잔고·판매가능수량 조회는 com.kista.broker.application.service.BrokerAdapterRegistry.require(account, LiveBalancePort/SellableQuantityPort.class) 직접 라우팅 — 별도 Router 클래스 없음 (KIS: TTTS3012R fetchHolding 재사용 / Toss: /api/v1/sellable-quantity)
                         CyclePositionPersistor: 포지션 스냅샷 저장 + 사이클 종료·rotation + VrCycleRolloverService.rollIfDue() 호출 (VR 예외 → "VR 전략 패턴")
  application/event/  ← trading 모듈의 공개 계약 — CycleCompletedEvent/CycleEndedEvent/NewCycleStartedEvent/OrderCancelFailedEvent/TradingReportReadyEvent/TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/MarketCloseEvent/BatchInterruptedEvent 11개 — notify 모듈이 `@TransactionalEventListener`로 구독(TradingAlertNotifier 등, EPR로 추적되어 재기동 시 실패분 자동 재시도). User/Account를 담던 이벤트는 userId/accountId만 담아 EPR 직렬화에 평문 비밀값이 노출되지 않게 함. "event" 이름으로 공개
  adapter/in/schedule/ ← TradingOpenScheduler/TradingCloseScheduler/BatchContextFactory(전략 목록 → BatchContext 빌드, 조회 실패 시 skip + notifyError) — legacy AdminSchedulerController가 수동 트리거용으로 구체 클래스를 직접 주입하는 기존 관례(KbLand 스케쥴러와 동일 패턴)를 유지하기 위해 공개. "schedule" 이름으로 공개
  adapter/in/web/      ← internal(비공개) — OrderCancelController
  adapter/out/         ← internal(비공개) — MockSimulationDataAdapter(broker.application.port.output.MockSimulationDataPort 구현 — broker의 MockBrokerAdapter가 필요로 하는 cycle_position/orders 데이터를 trading이 제공, 위 "com.kista.broker/" 참고)
  adapter/out/persistence/ ← internal(비공개) — Order/CyclePosition/CyclePositionInfinite/StrategyCycle/StrategyCycleVr Entity + *JpaRepository + *PersistenceAdapter 3종 구성, PersistenceSupport(레거시 persistence/strategy와 별도 복제본)

com.kista.market/    ← Spring Modulith 5번째 이전 모듈(CLOSED) — 공포탐욕지수+미국 시장 휴장일 캘린더 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"(domain.model)·"port"(application.port.output)·"event"(application.event) 3개 NamedInterface 공개 — application.{usecase,service}·adapter는 의도적으로 비공개(모듈 내부 구현)
  domain/model/       ← FearGreedRating/FearGreedSnapshot/MarketSessionSnapshot — "domain" NamedInterface로 공개
  application/port/output/ ← CnnFearGreedPort/CryptoFearGreedPort/FearGreedSnapshotPort/MarketCalendarPort/MarketCalendarRefreshPort/MarketHolidayStorePort 6개 — "port" NamedInterface로 공개
  application/usecase/ ← FetchFearGreedUseCase/GetFearGreedUseCase — internal(외부 소비자 없음, 모듈 내부 adapter/in/web에서만 참조). `MarketUseCase`(시장 캘린더/캔들 조회)는 예외적으로 레거시 `com.kista.application.usecase`에 잔류 — `MarketHolidayService`(market 내부)가 구현, `MarketHolidayController`(market 내부)가 소비
  application/event/  ← FearGreedFetchFailedEvent — notify 모듈이 `@TransactionalEventListener`로 구독(MarketAlertNotifier, CLOSED↔CLOSED 모듈 간 이벤트 교차 — trading.application.event와 동일 패턴). "event" 이름으로 공개
  application/service/ ← internal(비공개) — FearGreedQueryService/FearGreedService/MarketHolidayService
  adapter/in/web/     ← internal(비공개) — FearGreedController/MarketHolidayController + dto/
  adapter/in/schedule/ ← internal(비공개) — FearGreedScheduler/MarketCalendarRefreshScheduler
  adapter/out/feargreed/ ← internal(비공개) — CnnFearGreedAdapter/CryptoFearGreedAdapter/FearGreedConfig
  adapter/out/alpaca/  ← internal(비공개) — AlpacaCalendarAdapter/AlpacaConfig/AlpacaProperties (`com.kista.stats.adapter.out.alpaca`의 동명 클래스(AlpacaIndexPriceAdapter/AlpacaConfig/AlpacaProperties, 원래 빈 이름 `alpacaRestClient` 유지)와 빈 이름 충돌 방지를 위해 market판만 `marketAlpacaConfig`/`marketAlpacaRestClient`로 명시적 개명)
  adapter/out/persistence/calendar/  ← internal(비공개) — UsMarketHolidayEntity + UsMarketHolidayJpaRepository + MarketCalendarPersistenceAdapter
  adapter/out/persistence/feargreed/ ← internal(비공개) — FearGreedSnapshotEntity + FearGreedSnapshotJpaRepository + FearGreedSnapshotPersistenceAdapter

com.kista.privacy/   ← Spring Modulith 6번째 이전 모듈(CLOSED) — FIDA 기준 매매표(PRIVACY 전략의 전역 SSOT 매매 계획) 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"(domain.model)·"port"(application.port.output)·"usecase"(application.usecase)·"event"(application.event) 4개 NamedInterface 공개 — application.service·adapter는 의도적으로 비공개(모듈 내부 구현). PRIVACY *전략 실행* 로직(PrivacyStrategy/PrivacyCycleOrderStrategy 등)은 trading 소유로 유지 — 이 모듈은 계획 데이터만 다룬다
  domain/model/       ← FidaOrderCommand/FidaPlannedOrder/PrivacyCurrentBase/PrivacyDates/PrivacyOrderDirection/PrivacyOrderType/PrivacyTradeBase/PrivacyTradeBaseView/PrivacyTradeConflictException/PrivacyTradeSaveResult/PrivacyTradeValidationReport 11개 record/enum/예외 — "domain" NamedInterface로 공개. PrivacyOrderDirection/PrivacyOrderType은 `trading.domain.model.Order`의 동명 nested enum과 값 집합만 동일한 privacy 자체 소유 타입(broker의 Direction/OrderType 복제와 동일 패턴, 상수명 byte-identical). PrivacyDates.releaseDateFor()/tradeDateOf()는 FIDA 발행일↔거래일 업무 규칙 헬퍼(시간대 변환 아님) — BacktestService가 "domain"으로 소비
  application/port/output/ ← PrivacyTradePort — "port" NamedInterface로 공개
  application/usecase/ ← PrivacyUseCase(FidaOrderController가 소비)/PrivacyTradeValidationUseCase(trading의 TradingOpenScheduler가 소비) — "usecase" NamedInterface로 공개
  application/event/  ← PrivacyAlertRaisedEvent(Severity{BLOCKING,WARNING}, String message) — notify 모듈이 `@TransactionalEventListener(fallbackExecution=true)`로 구독(PrivacyAlertNotifier, CLOSED↔CLOSED 모듈 간 이벤트 교차 — trading/market.application.event와 동일 패턴). "event" 이름으로 공개
  application/service/ ← internal(비공개) — PrivacyService(PrivacyUseCase 구현, notify 직접 호출 대신 PrivacyAlertRaisedEvent 발행)/PrivacyTradeValidationService
  adapter/in/web/     ← internal(비공개) — FidaOrderController(`/api/internal/**`) + dto/FidaOrderResponse
  adapter/out/persistence/ ← internal(비공개) — PrivacyTradeBaseEntity + PrivacyTradeBaseOrderEntity + PrivacyTradeBaseJpaRepository + PrivacyTradePersistenceAdapter (옛 adapter/out/persistence/privacy)

com.kista.stats/    ← Spring Modulith 7번째 이전 모듈(CLOSED) — 사용자 통계·주택/ETF 벤치마크 비교·과거 일봉 백테스트·텔레그램 포트폴리오 조회 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"(domain.model + domain.model.backtest)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개 — application.service·domain.backtest·adapter.in.web(+dto)·adapter.out.*은 의도적으로 비공개(모듈 내부 구현). 통계 서비스 3종(AccountStatisticsService/TossStatisticsService/BrokerStatisticsRouter)·AccountStatisticsUseCase/TossStatisticsUseCase는 스펙 4단계(account+strategy-config) 대상이라 레거시 잔류
  domain/model/       ← 통계 20타입(BenchmarkAssetType/BenchmarkGranularity/BenchmarkScope/CurrentExchangeRate/CyclePerformance/CyclePerformancePage/EquityCurve/EquityPoint/EtfBenchmarkSymbol/HousingBenchmarkComparison/HousingBenchmarkPoint/HousingBenchmarkPrice/HousingBenchmarkRegion/HousingPriceIndex/IndexPrice/InvestmentPoint/PerformanceComparisonSummary/ReturnMetrics/StatsSummary/StrategyTypeStats) — "domain" NamedInterface로 공개
  domain/model/backtest/ ← 백테스트 커맨드·결과 5타입(BacktestCommand/BacktestPoint/BacktestResult/BacktestSummary/DailyCandle, 레거시 domain/model/backtest 구조 유지) — domain/model과 함께 "domain" NamedInterface로 병합 공개(trading의 model+strategy 병합과 동일 패턴)
  domain/backtest/    ← internal(비공개) — BacktestEngine/FillSimulator (백테스트 시뮬레이션 엔진)
  application/usecase/ ← UserStatsUseCase/BacktestUseCase/PortfolioUseCase/FetchHousingBenchmarkUseCase/FetchHousingPriceIndexUseCase/SyncMarketIndexPricesUseCase 6개 — "usecase" NamedInterface로 공개(PortfolioUseCase는 notify TelegramBotService가 `/portfolio` 명령 응답에 소비)
  application/port/output/ ← HistoricalCandlePort/HousingBenchmarkFeedPort/HousingBenchmarkPricePort/HousingPriceIndexPort/IndexPriceFeedPort/IndexPricePort 6개 — "port" NamedInterface로 공개
  application/event/  ← StatsAlertRaisedEvent(String message) — notify 모듈이 `@TransactionalEventListener(fallbackExecution=true)`로 구독(StatsAlertNotifier, CLOSED↔CLOSED 모듈 간 이벤트 교차 — trading/market/privacy.application.event와 동일 패턴). "event" 이름으로 공개
  application/service/ ← internal(비공개) — StatsService(UserStatsUseCase 구현 — summary/equity-curve/cycles/housing-benchmark 계열)
                         StatsResultCache — summary/equity-curve 5분, 벤치마크 비교(housing-benchmark, ETF 포함) 10분 인메모리 TTL 캐시. 매매 직후 통계가 해당 TTL만큼 stale할 수 있음. 단일 인스턴스 전제 — 다중 인스턴스 시 인스턴스별 캐시가 최대 TTL만큼 상이할 수 있음
                         MonthlyReturnCalculator/HousingBenchmarkComparisonBuilder — Spring·포트 비의존 순수 계산 클래스(TWR·정규화 비교 조립). HousingBenchmarkComparisonBuilder는 ETF 비교에도 재사용됨(이름은 HousingBenchmark)
                         getHousingBenchmarkComparison: currentExchangeRate는 요청마다 실시간 조회하는 정보성 필드일 뿐 수익률·공통월·summary 계산에는 미반영(조회 실패 시 null 처리, 200 정상 반환) — 투자(USD)·벤치마크(HOUSING=KRW/ETF=USD) 현지통화 그대로 비교, 환율 변환 없음
                         HousingBenchmarkService/HousingPriceIndexService — KB Land 수집 실패 시 notify 직접 호출 대신 StatsAlertRaisedEvent 발행. BacktestService/PortfolioService/MarketIndexPriceSyncService도 여기
  adapter/in/web/     ← internal(비공개) — StatsController/BacktestController + dto/ 9종(StatsSummaryResponse/EquityCurveResponse/CyclePerformancePageResponse/EtfPriceSeriesResponse/BacktestResponse/HousingBenchmarkComparisonResponse/HousingBenchmarkRegionsResponse/HousingBenchmarkSeriesResponse/HousingPriceIndexSeriesResponse)
  adapter/in/schedule/ ← KbLandHousingBenchmarkScheduler/KbLandPriceIndexScheduler/MarketIndexPriceSyncScheduler — legacy AdminSchedulerController가 KbLand 스케쥴러 2개를 수동 트리거용 ObjectProvider<>로 직접 주입(trading "schedule" NamedInterface 선례와 동일 관례)해 공개. "schedule" 이름으로 공개. MarketIndexPriceSyncScheduler는 비거래일에도 Alpaca 빈 배열 반환으로 무해한 no-op이라 요일 조건 없음
  adapter/out/alpaca/  ← internal(비공개) — AlpacaIndexPriceAdapter/AlpacaConfig/AlpacaProperties (MarketIndexPrice* 계열 전용, market 이전 때 복제로 남겨뒀던 레거시 원본을 stats가 소유권 인수 — 빈 이름 `alpacaRestClient` 그대로, market판 `marketAlpacaConfig`와 무충돌)
  adapter/out/kbland/  ← internal(비공개) — KbLandHousingBenchmarkAdapter/KbLandConfig/KbLandProperties (KB Land 아파트 5분위 매매평균가격(월간) + 주간 매매가격지수 조회)
  adapter/out/persistence/housingbenchmark/ ← internal(비공개) — HousingBenchmarkPriceEntity/HousingPriceIndexEntity + *JpaRepository + *PersistenceAdapter 6개
  adapter/out/persistence/marketindex/ ← internal(비공개) — MarketIndexPriceEntity + MarketIndexPriceJpaRepository + MarketIndexPricePersistenceAdapter 3개
```

### Spring Modulith 점진 도입
`finance`가 첫 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개 — application.service·adapter는 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 "domain" 하나에 model+port를 병합 공개했었다). 레거시 최상위 4패키지(`common`/`domain`/`application`/`adapter`)는 아직 옮기지 않은 코드가 담긴 임시 이전 shim으로 `Type.OPEN` 선언돼 있어 외부 참조를 계속 허용한다 — 내용물이 모두 새 모듈로 옮겨지면 package-info와 함께 자연 소멸한다. `ApplicationModules.verify()`(`ModulithArchitectureTest`)와 일반화된 `HexagonalArchitectureTest`(`..domain..` 등 와일드카드 매처로 옛 최상위 구조·새 모듈 구조를 규칙 하나로 동시 커버) 둘 다 `com.kista.architecture` 패키지에서 실행된다 — 전자는 모듈 **간** 경계, 후자는 모듈 **내부** 레이어 방향을 각각 담당하는 직교 축. `notify`가 두 번째 이전 모듈이다(`@ApplicationModule` CLOSED, 자체 domain/model 없이 application.port.output만 "port" NamedInterface로 공개 — 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 domain/port/out을 "domain" 이름으로 공개했었다). `broker`가 세 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain/model+domain/model.kis+domain/model.toss)·"port"(application/port/output)·"application"(application/service) 3개 NamedInterface 공개 — adapter/out은 KIS/Toss/Mock 연동 구현 디테일이라 의도적으로 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 domain/port/out을 "domain"에 병합 공개했었다). `trading`이 네 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.strategy)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개 — application.service·adapter.out.*은 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 domain.port.{in,out}을 "domain"에 병합 공개했었다). 선언 직전 `broker↔trading`/`notify↔trading`/`broker→trading→notify→broker` 3개 모듈 순환이 발견돼 별도 디커플링 작업으로 제거한 뒤 재개했다 — broker는 자기 소유 타입(Direction/PriceSnapshot/BrokerBalance 등, 위 참고)만 포트 시그니처에 사용하고 trading이 매핑, notify는 trading이 발행하는 도메인 이벤트를 구독하는 방식(TradingAlertNotifier, 위 참고)으로 전환해 양방향 참조를 단방향으로 정리했다. `market`이 다섯 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"port"(application.port.output)·"event"(application.event) 3개 NamedInterface 공개 — application.{usecase,service}·adapter는 비공개). 이전 과정에서 2개의 모듈 순환이 실측 발견돼 해소한 뒤 재개했다 — ① `market→notify→trading→market` 3단 전이 순환: `FearGreedService`의 notify 직접 호출을 `FearGreedFetchFailedEvent` 발행으로 전환해 notify가 이벤트로 구독하는 방식(`MarketAlertNotifier`, broker-trading-notify 디커플링과 동일 패턴)으로 해소. ② `market→trading`: `MarketHolidayController`가 쓰던 `trading.domain.model.DstInfo` 직접 참조를 market 자체 소유 `MarketSessionSnapshot`으로 대체해 해소(레거시 시절부터 있던 결합이 CLOSED 전환 과정에서 드러난 사례, broker의 Direction/OrderType 복제 패턴과 동일 — `trading→market`(MarketCalendarPort 소비)은 정상 리프 의존이라 그대로 유지). `privacy`가 여섯 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"port"(application.port.output)·"usecase"(application.usecase)·"event"(application.event) 4개 NamedInterface 공개 — application.service·adapter는 비공개). 이전 과정에서 2개의 모듈 순환이 실측 발견돼 해소한 뒤 재개했다 — ① `privacy↔trading`(직접): privacy 파일이 `trading.domain.model.Order`(nested enum OrderType/OrderDirection + 주문 4필드)를 그대로 빌려 쓰던 것을 privacy가 `PrivacyOrderType`/`PrivacyOrderDirection`/`FidaPlannedOrder`를 자체 소유하는 방식으로 해소(broker의 Direction/OrderType 복제와 동일 패턴). `PrivacyStrategy`(trading)가 `Order.OrderType.valueOf(name())`으로 매핑한다 — 상수명 byte-identical, DB `@Enumerated(STRING)` 호환. ② `privacy→notify→trading→privacy`(전이 — 스펙의 pairwise 분석이 놓쳤음, market의 `market→notify→trading→market`과 동일 맹점): `PrivacyService`의 `NotifyPort.notifyError`/`notifyInfo` 직접 호출을 `PrivacyAlertRaisedEvent` 발행으로 전환하고 notify가 `PrivacyAlertNotifier`로 구독(`MarketAlertNotifier`/`FearGreedFetchFailedEvent`와 동일 패턴). `trading→privacy`(18개 파일이 privacy "domain" NamedInterface 소비)는 정상 단방향 리프 의존이라 그대로 유지. `stats`가 일곱 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.model.backtest)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개 — application.service·domain.backtest·adapter.in.web·adapter.out.*는 비공개). 이전 과정에서 `stats↔notify` 직접 2-cycle이 실측 발견돼 해소한 뒤 재개했다 — `stats→notify`(`HousingBenchmarkService`/`HousingPriceIndexService`의 `NotifyPort.notifyError` 직접 호출)를 `StatsAlertRaisedEvent` 발행으로 전환하고 notify가 `StatsAlertNotifier`로 구독(`MarketAlertNotifier`/`PrivacyAlertNotifier`와 동일 패턴 — 3번째 인스턴스). `notify→stats`(`TelegramBotService`가 `PortfolioUseCase`를 `/portfolio` 명령 응답에 소비)는 정상 단방향 리프 의존이라 그대로 유지. 이 순환은 pairwise 분석의 맹점이 아니라 — 스펙 "결합도 실측" 표가 `notify→stats`를 "단방향이라 순환 아님"으로 옳게 판정했으나, `PortfolioUseCase`가 stats 모듈로 이동하면서 기존 `stats→notify`와 합쳐져 방향이 뒤집힌 사례다. 전체 계획(finance✅ → notify✅ → broker✅ → trading✅ → market✅(5번째) → privacy✅(6번째) → stats✅(7번째))은 `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`(finance~trading 4모듈) + `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`(잔여 8모듈 카탈로그, market이 1단계 첫 착수) 참고.

### DashboardController vs StatisticsController 응답 형식 차이
- `DashboardController`: DB 기반 전용 DTO 반환 — `GET /api/accounts/{accountId}/cycle-history` → `CycleHistoryPageResponse` (커서 페이지네이션)
- `StatisticsController`: **KIS 전용** live API 직접 호출 → 전용 Response DTO 반환 (`PortfolioSummaryResponse`/`List<MarginResponse>`/`DailyTransactionResponse`/`MultiPriceResponse`)
  - normalizer는 이미 API 서버 쪽(`PortfolioSummaryResponse.from()` 등)에서 수행 — `PresentBalanceResult` 등 도메인 모델은 컨트롤러 반환 직전에 DTO로 정규화되어 kista-ui는 그대로 소비
  - 신규 live 엔드포인트 추가 시 kista-ui 타입과 응답 필드명 반드시 대조 확인
- `TossStatisticsController`: **Toss 전용** — 캔들/환율/세션/종목정보/계좌정보 5개 엔드포인트 (`/api/accounts/{accountId}/*`)

### 신규 외부 서비스 어댑터 구조 패턴
`adapter/out/<서비스명>/` 아래 3파일로 구성 (KakaoConfig, TelegramConfig, AlpacaConfig 동일 패턴):
- `*Properties.java` — `@ConfigurationProperties(prefix="...")` record
- `*Config.java` — `@Configuration` + `@EnableConfigurationProperties(*Properties.class)` + RestTemplate `@Bean`
- `*Adapter.java` — `@Component`, Port 구현, RestTemplate + Properties 주입

### 인증 userId 추출 패턴
- 모든 컨트롤러: `@AuthenticationPrincipal UUID userId` 메서드 파라미터로 직접 주입 — `SecurityContextHolder` 수동 호출 금지
- `JwtAuthFilter`: principal을 `UUID` 타입으로 저장 (`String` 아님)

### 소유권 검증 패턴
- `account.verifyOwnedBy(requesterId)` — 불일치 시 `SecurityException` (컨트롤러에서 403 매핑)
- `tradingCycle.verifyOwnedBy(account)` — `cycle.accountId().equals(account.id())` 검증, 마찬가지로 `SecurityException`
- 사이클 소유권 확인 순서: `cycleRepository.findByIdOrThrow(id)` → `accountRepository.findByIdOrThrow(cycle.accountId())` → `account.verifyOwnedBy(requesterId)`
- `accountRepository.findByIdOrThrow(id)` / `cycleRepository.findByIdOrThrow(id)` — 없으면 `NoSuchElementException` (컨트롤러에서 404 매핑)
- Service 내 반복 검증은 `private Account requireOwnedAccount(UUID accountId, UUID requesterId)` 헬퍼로 추출 — `AccountStatisticsService` 패턴 참고
- Controller에 try/catch 추가 금지 — `ResponseStatusException` 등 Spring HTTP 클래스는 application layer 사용 불가 (ArchUnit 규칙)

### JPA Auditing
- `BaseAuditEntity` (`@MappedSuperclass`): `UserEntity`, `AccountEntity`가 상속 — `@CreatedDate`/`@LastModifiedDate`로 `createdAt`/`updatedAt` 자동 관리
- 새 엔티티에 타임스탬프 필요 시 `BaseAuditEntity`(`createdAt`+`updatedAt`) 또는 `BaseCreatedAtEntity`(`createdAt`만) 상속 — `updated_at` 컬럼 없는 엔티티에 `BaseAuditEntity` 사용 금지 (`ddl-auto: validate` 실패); `com.kista.broker.adapter.out.persistence.KisTokenEntity` 등 DB DEFAULT(`insertable=false, updatable=false`) 방식 엔티티는 그대로 유지
- 서비스에서 domain record 생성 시: `updatedAt=null` (adapter가 무시, `@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null` (`@CreatedDate`가 처리)
- `toEntity()` 내에서 `setCreatedAt()`/`setUpdatedAt()` 명시적 호출 금지 — `@CreatedDate(updatable=false)` / `@LastModifiedDate`가 INSERT·UPDATE 시 자동 처리. 호출 자체가 dead code이며 `@Setter(PACKAGE)` 범위 제약과도 충돌함

### 텔레그램 알림 (notifyTradingReport)
- 계좌별 텔레그램 설정 제거됨 — `User.telegramBotToken/chatId` 사용자봇만 사용 → 미설정 시 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용

### BrokerAdapter Registry 패턴
- `com.kista.broker.application.service.BrokerAdapterRegistry`: `Map<Account.Broker, BrokerAdapterPort>` — Spring이 `List<BrokerAdapterPort>` 자동 수집, broker 기준 Map 빌드
- `registry.require(account, XxxPort.class)` — 해당 브로커가 Capability 미지원 시 `IllegalArgumentException` → GlobalExceptionHandler 400
- `registry.find(account, XxxPort.class)` — `Optional<T>` 반환, 미지원 시 `Optional.empty()`
- 신규 브로커 추가: `com.kista.broker.application.port.output.BrokerAdapterPort` 구현체 1개만 추가 — Router/switch 수정 불필요
- `Account.isToss()` 삭제됨 — 브로커 분기 필요 시 `account.broker() == Account.Broker.TOSS` 직접 비교
- `BrokerAdapterRegistry`는 `public` — 레거시 최상위 여러 서브패키지(account/trading/strategy)에서 "application" NamedInterface로 공개 소비하므로 예외적 공개 접근자

### TDA 전략 패턴 (InfiniteStrategy)
- `TradingService.execute()` 잔고 조회: KIS API 아님 → `findRecentByCycleId(cycleId, 1)` 최신 이력에서 `AccountBalance` 구성 (이력 없으면 `IllegalStateException`)
- PRIVACY execute() null guard: `snapshot=null` → `saveAndNotify`에서 `snapshot != null` 조건 가드 유지 필수
- **`preview()` today 오프셋**: 날짜 경계는 KST 04:30 (`DstInfo.SCHEDULER_RUN_TIME`) → `TradingPreviewService`의 `today = DstInfo.nextTradeDate()` SSOT 사용 (미적용 시 PRIVACY `findTodayTrade()` 날짜 1일 어긋남)
- **`INSUFFICIENT_BALANCE` skip 시 position 포함**: `shouldSkip(price)` true여도 `InfinitePosition`을 Result에 포함 — 프론트에서 단위금액·현재가·부족 금액 표시 목적

### CycleOrderStrategy Capability 패턴
- `CycleOrderStrategy` 인터페이스: 전략 타입별 동작(basePrice 소스, 전일종가 필요 여부, 분할수, 리버스모드 지원, 청산 시 사이클 종료 여부, 최소시드, 예산배정 우선순위, compute skip, 롤오버 판정 필요 여부, BUY 가격 캡 보정 방식 등)을 캡슐화하는 다형성 계층 — 메서드별 상세는 코드가 SSOT
  - `canSkipOrderComputation()`은 기본 false이며 INFINITE만 complete concrete leg 또는 direction-aware legacy UNKNOWN 점유를 보수적으로 판단한다
  - `priceCapMode()`: VR도 생성 시점 cap을 적용하지 않고 접수 전 `BuyOrderPriceCapper`가 `VrStrategy.buildCappedBuyOrders()`로 보정한다
- `CycleOrderStrategies`: `Map<Strategy.Type, CycleOrderStrategy>` 라우터 — `of(type)` 으로 구현체 조회
- **프론트 capability 소비**: `GET /api/meta`의 `StrategyTypeMeta`에 capability 7필드(code/description/availableTickers/requiresPrivacyBase/tickerFixed/supportsReverseMode/divisionCounts) 직렬화 → 프론트는 `isInfinite` 휴리스틱 대신 `divisionCounts`/`requiresPrivacyBase` 직접 소비
- **최소시드 미리보기**: `GET /api/accounts/{id}/strategy-seed-preview?type=&ticker=&divisionCount=` → `StrategySeedPreviewResponse { ticker, basePrice, minSeed, skipReason }`
  - `AccountStatisticsService.strategySeedPreview()` 구현 — `BrokerAdapterRegistry`(BrokerPricePort) + `PrivacyTradePort` + `CycleOrderStrategies.minRequiredDeposit` 조합
  - PRIVACY + 기준 매매표 없는 날 → `skipReason="NO_PRIVACY_BASE"` (basePrice/minSeed=null)
- **신규 전략 타입 추가 시**: `Strategy.Type` enum case + `CycleOrderStrategy` 구현체 1개만 추가하면 메타 capability·최소시드·UI 자동 반영

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trade_bases` (`com.kista.privacy.adapter.out.persistence`, `PrivacyTradeBaseEntity`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음** (계좌별 아닌 시스템 공통 기준)
  - `(release_date, ticker)` UNIQUE 제약 (`uq_privacy_trade_bases_release_date_ticker`) — 하루에 종목당 기준 매매표 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trade_base_orders` (`com.kista.privacy.adapter.out.persistence`, `PrivacyTradeBaseOrderEntity`): 기준 매매표 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - direction/orderType은 privacy 자체 소유 enum(`PrivacyOrderDirection`{BUY/SELL} / `PrivacyOrderType`{LOC/MOC/LIMIT}, 상수명은 `Order.*`와 byte-identical) — VARCHAR + `@Enumerated(STRING)`
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `com.kista.privacy.adapter.out.persistence.PrivacyTradePersistenceAdapter` 정렬 처리
- FIDA 수신 흐름: `(tradeDate, ticker)` 없음 → 201 / 내용 동일 → 200(멱등) / 내용 다름 → `PrivacyTradeConflictException` → 409
- 스케쥴러: `StrategyType.PRIVACY` → `PrivacyCycleOrderStrategy.plan()` → `PrivacyStrategy.buildOrders()` (`CycleOrderComputer`가 전략별 분기)

### VR 전략 패턴 (밸류리밸런싱)
공식·bootstrap 규칙·가격 캡·롤오버 조건의 SSOT는 constraints.md "VR 공식" — 여기서는 구조·흐름만 기록.
- **TQQQ 전용** — `Strategy.Type.VR.resolveTicker()` → `Ticker.TQQQ` 강제. divisionCount 없음(null 직렬화), cycleSeedType=NONE 강제
- `strategy_vr_version` (`StrategyVrVersionEntity`): 전략 버전별 VR 설정 — intervalWeeks(롤오버 주기), bandWidth(밴드 폭 %), recurringAmount(USD, 양수=적립·0=거치·음수=인출) + 램프 파라미터 8개(initialGradient/gGraceWeeks/gStepWeeks/gMax/initialPoolLimitRate/pGraceWeeks/pStepWeeks/poolLimitFloor)
- `strategy_cycle_vr` (`StrategyCycleVrEntity`): 사이클 시작 시 스냅샷 — value(사이클 기준 V값), gradient(경과주수 기준 `gradientAt()` 재계산값), poolLimitRate(비율 스냅샷, 달러 아님 — poolLimit은 개장 `CyclePosition.usdDeposit×poolLimitRate`로 조회 시점 파생). `StrategyCycle.startAmount`는 모든 전략에서 개장 예수금+개장 보유분 시장가다.
- 주문 생성: `CycleOrderComputer` → `VrCycleOrderStrategy.plan()` → `VrStrategy.buildOrders()` → 매수·매도 사다리 LIMIT+AT_OPEN 주문 생성 (bootstrap은 LOC+AT_CLOSE)
- **holdings=0에도 사이클 유지** — `endsCycleOnLiquidation()=false`, `CyclePositionPersistor`가 종료 미발동
- **N주 롤오버**: `VrCycleRolloverService.rollIfDue()` — `CyclePositionPersistor`의 포지션 저장 직후 매일 판정, due이면 V′ 계산 후 기존 사이클 종료 + 새 사이클 원자 생성 (V′≤0 보류 등 규칙 → constraints.md). gradient·poolLimitRate는 스냅샷 이월이 아닌 전략 최초 사이클 startDate 기준 경과주수로 매번 재계산
- **운영 중 재설정**: `VrReconfigureService`(`application/service/trading`, package-private) + `VrReconfigureUseCase` — `PUT /api/trading-cycles/{id}/vr-config`로 밴드폭·주기·적립금·램프 파라미터 수정 + 선택적 자본 주입/인출(수량/예수금)을 새 버전 발급+강제 롤오버 1회로 처리. 상세 규칙 → constraints.md "VR 공식"
