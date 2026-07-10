## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (핵심 record 필드 → constraints.md "Account ↔ Strategy 분리").

```
common/          ← 공통 유틸리티 (Spring/JPA 독립)
  CycleLookups   — 활성 StrategyCycle 조회 헬퍼 (requireLatestCycle — IllegalStateException 400)
  TimeZones      — KST/UTC ZoneId 상수
  TradeDateConverter — toUtc(KST)/toKst(UTC) ±1일 변환 (→ constraints.md "tradeDate 변환 정책")

domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체 (record 사용), 어그리게이트별 서브패키지
    user/        ← User (nested: UserRole/UserStatus/NotificationChannel enum + CooldownException), UserSettings, FcmDeviceToken,
                   NotificationType(TRADING_ALERT) — 독립 enum, User nested enum 아님
    account/     ← Account (nested exceptions: InvalidBrokerKeyException, KisRateLimitException, DuplicateAccountException), Broker,
                   Register/UpdateAccountCommand, SellableQuantity
    strategy/    ← Strategy (nested: Type{INFINITE/PRIVACY/VR}, Status, Ticker, CycleSeedType), StrategyCycle, CyclePosition, StrategyDetail,
                   CyclePositionHistoryEntry, CycleHistoryPage, Register/UpdateStrategyCommand, BatchContext, InfinitePosition, ReverseModePosition,
                   AccountBalance, TradingSnapshot, TradingReport, DstInfo, PriceSnapshot,
                   StrategyVrDetail(+gradient()/poolLimitRate()), StrategyCycleVrDetail, VrPosition(+lowerBand/upperBand/buyPrice/sellPrice/nextValue())
    order/       ← Order, TradeEvent, CancelResult, NextOrdersPreview
                   Order.withPlaced(externalOrderId) — 브로커 접수 완료 표시 헬퍼
                   OrderPort 조회/삭제는 strategyCycleId+tradeDate 기준 (cycle 단위 격리)
                   OrderPort.findPlannedOrPlacedByCycleAndDate(cycleId, date) — 당일 중복 주문 존재 여부 확인 (개장 스케쥴러 skip 판단) + 수동 실행 결과 반환
    auth/        ← RefreshToken, TokenRefreshResult, InvalidRefreshTokenException (→ GlobalExceptionHandler 401)
    broker/      ← 브로커 공통 응답 record (Execution, PresentBalanceResult, MarginItem, Currency, DailyTransaction* — KIS·Toss 공용)
    kis/         ← KisApiException (KIS 전용)
    toss/        ← TossApiException (→ GlobalExceptionHandler 503), TossAccountInfo, TossCandle, TossExchangeRate, TossMarketSession, TossStockInfo
    market/      ← FearGreedSnapshot, FearGreedRating
    admin/       ← AdminAnomalies, AdminStats, AdminUserView, AuditLog, AppErrorLog
    privacy/     ← FidaOrderCommand, PrivacyCurrentBase, PrivacyTradeBase, PrivacyTradeBaseView, PrivacyTradeSaveResult, PrivacyTradeConflictException
  strategy/      ← InfiniteStrategy/ReverseInfiniteStrategy/PrivacyStrategy/VrStrategy 구현 클래스 (1:1 인터페이스 통폐합됨) — @Component 허용 예외 (ArchUnit)
                   CycleOrderStrategy (인터페이스 + Infinite/Privacy/VrCycleOrderStrategy 구현): 최소시드·capability SSOT (아래 "CycleOrderStrategy Capability 패턴")
                   CycleOrderStrategies (@Component 라우터): Map<Strategy.Type, CycleOrderStrategy> 자동 수집
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스 (*Port)
    broker/      ← 브로커 공통 Capability 인터페이스
                   공통 9개: BrokerAdapterPort(supports()), PortfolioPort, MarginPort, SellableQuantityPort, ExecutionPort, BrokerOrderCorrectionPort(place/cancel), BrokerPricePort, LiveBalancePort, BrokerConnectionTestPort
                   Toss 전용 5개: CandlePort, ExchangeRatePort, StockInfoPort, BrokerMarketCalendarPort, BrokerAccountPort
                   BrokerConnectionTestPort: 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅 — verifyCredentials/verifyAccount→brokerAccountCode (KIS: null, Toss: accountSeq)

application/
  service/       ← UseCase 구현체 (package-private @Service), Port를 통해서만 외부 호출, 어그리게이트별 서브패키지
    trading/     ← TradingExecutionFacade (TradingExecutionUseCase 구현 — preview/executeManually/cancelOrder/cancelByCycle/execute/executeBatch 단일 진입점),
                   TradingService(배치·단건 실행 코어), ManualTradingService(수동 실행), TradingPreviewService(미리보기 전용, @Transactional readOnly),
                   OrderCancelService(주문 취소), CycleRotationService(사이클 종료 후 cycleSeedType 기반 재등록),
                   MarketEventNotifier(장 개시·마감 알림 — UserPort+UserSettingsPort+UserNotificationPort 조합)
                   package-private helper: TradingBalanceLoader/TradingOrderPlanner/TradingOrderExecutor/TradingPriceFetcher/TradingReporter/BuyOrderPriceCapper/CycleOrderComputer
                   BrokerAccountRouter: getLiveBalance/getUsdDeposit/getSellableQuantity — BrokerAdapterRegistry 경유 브로커별 라우팅 (KIS: TTTS3012R fetchHolding 재사용 / Toss: /api/v1/sellable-quantity)
                   CyclePositionPersistor: 포지션 스냅샷 저장 + 사이클 종료·rotation + VrCycleRolloverService.rollIfDue() 호출 (VR 예외 → "VR 전략 패턴")
    account/     ← AccountService, AccountStatisticsService, TossStatisticsService, BrokerStatisticsRouter(package-private — 브로커별 통계 라우팅)
    broker/      ← BrokerAdapterRegistry (public @Component — require(account, Port.class) / find())
                   BrokerConnectionTesters (public @Component — of(broker) 라우팅, 계좌 등록 전 자격증명 검증용)
    strategy/    ← StrategyService
    user/        ← UserService(인증·수명주기), UserProfileService(텔레그램·닉네임·알림채널·FCM), UserSettingsService, UserCascadeDeleter(소프트 삭제 cascade helper)
    portfolio/   ← PortfolioService
    market/      ← MarketHolidayService, FearGreedQueryService, FearGreedService
    privacy/     ← PrivacyService
    admin/       ← AdminService, AdminQueryService(에러 로그 조회/삭제 포함), AdminStrategyService, AdminCycleCloser(holdings 소진 시 사이클 종료), AdminSelectionChain, AdminOrderCorrectionService, AdminTradeCorrectionService
    auth/        ← BlacklistService (JWT 블랙리스트), TokenService (RT 발급/갱신/폐기)
  event/         ← UserApproved/UserRejected/UserReappliedEvent — @TransactionalEventListener용 도메인 이벤트 (application 레이어)

adapter/in/
  schedule/      ← TradingOpenScheduler (월~금 22:30 KST — 전략 전체 order 생성 + INFINITE 매도 선접수 + 예수금 부족 사용자 알람)
                   TradingCloseScheduler (화~토 04:30 KST — DST 장마감 30분 전, INFINITE 매수 보정·접수 + PRIVACY 접수 + 리포트, 멀티계좌)
                   RefreshTokenCleanupScheduler (매일 04:00 KST 만료 RT 삭제 / 03:05 KST grace 초과 회전 RT 삭제)
                   MarketCalendarRefreshScheduler (1월 1일 3년치 / 매월 1일 최신화), FearGreedScheduler (KST 00:00/06:30/10:00 — CNN·크립토 공포탐욕지수)
                   BatchContextFactory (전략 목록 → BatchContext 빌드, 조회 실패 시 skip + notifyError)
                   SchedulerJobRunner (공통 실행 골격 — 시작/완료 알림·인터럽트 처리; no-context run(name,Runnable) 오버로드: FearGreed·MarketCalendar용)
                   SchedulerLockService (package-private 분산 락 — tryRun(lockKey, timeout, task); @ConditionalOnProperty(scheduler.enabled) 로컬 중복 실행 방지)
  web/           ← REST Controller + DTO: Auth(카카오/JWT/승인/탈퇴/SSE), Account(CRUD+연결테스트), TradingCycle(CRUD+pause/resume+수동실행+`GET /api/accounts/{id}/strategy-seed-preview`),
                   Dashboard(DB기반 사이클 이력), Statistics(KIS 전용 live), TossStatistics(Toss 전용 live 5개), FearGreed(`GET /api/market/fear-greed`),
                   Meta(`GET /api/meta` enum SSOT 번들만, Cache 1h), OrderCancel, MarketHoliday(휴장일/세션 DIRECT|BLOCKED), FidaOrder(`/api/internal/**`, X-Internal-Token),
                   Settings(텔레그램+알림채널), Fcm, TradeStream(SSE), Admin*(`/api/admin/**` — Dashboard/Account/Anomalies/Audit/Trade/User/PrivacyTrade),
                   AdminObservability(`/api/admin/logs/` 감사·앱에러 로그 조회, `DELETE /errors/{id}` 소프트 삭제), AdminPing(`/api/admin/_ping`), DevAuth(local 전용)
  web/security/  ← JwtAuthFilter (Bearer JWT), InternalTokenAuthFilter (X-Internal-Token 서버간 인증)
  telegram/      ← TelegramWebhookController + TelegramBotService

adapter/out/
  broker/        ← DoubleCheckedTokenCache (KIS/Toss 공용 토큰 캐시 — 1차 조회 → miss 시 계좌별 락 → 2차 double-check → 신규 발급)
  kis/           ← KisHttpClient (공통 헤더 + executeWithRetry: 401 시 invalidateToken(INVALIDATED_TOKEN 센티널) 후 1회 재시도, RestClientException → KisApiException 래핑)
                   KisBrokerAdapter (BrokerAdapterPort + 공통 5개 Port 구현)
  toss/          ← TossHttpClient(공통 헤더)/TossConfig, TossAuthApi/TossCandleApi/TossHoldingsApi/TossOrderApi/TossPriceApi/TossMarketApi,
                   TossResponseParser (숫자 파싱 헬퍼, 패키지 내부 전용), TossBrokerAdapter (공통 5개 + Toss 전용 5개 Port 구현)
  feargreed/     ← CnnFearGreedAdapter, CryptoFearGreedAdapter
  redis/         ← RedisBlacklistAdapter (BlacklistPort — userId/JTI 단위 JWT 블랙리스트, TTL 기반)
  persistence/   ← JPA 인프라 (BaseAuditEntity, BaseCreatedAtEntity, JpaAuditingConfig) + 어그리게이트별 서브패키지
                   각 서브패키지는 Entity + *JpaRepository(package-private) + *PersistenceAdapter(Port 구현) 3종 구성:
                   user(+AdminUserViewAdapter) / account / strategy(+PersistenceSupport upsert 헬퍼; VR: StrategyVrVersionEntity=strategy_vr_version, StrategyCycleVrEntity=strategy_cycle_vr)
                   / kistoken(KisTokenEntity, table=broker_tokens) / auth(RefreshToken) / audit(AuditLog+AppErrorLog) / settings(UserSettings+UserNotificationPref)
                   / trade(Order) / privacy(PrivacyTradeBase+PrivacyTradeBaseOrder) / calendar(UsMarketHoliday) / fcm / feargreed
  notify/        ← TelegramAdapter (NotifyPort — 관리자봇 오류/리포트 알림)
                   CompositeUserNotificationAdapter → TelegramUserNotificationAdapter + FcmAdapter (UserNotificationPort — 사용자 알림)
                   TelegramBotInfoAdapter (봇 username 조회), TelegramHttpClient (package-private HTTP 헬퍼)
  sse/           ← SseEmitterRegistry (RealtimeNotificationPort — 사용자별 SSE 연결 관리), TradeSseEmitterRegistry (매매 이벤트 SSE)
  kakao/         ← KakaoOAuthAdapter — 카카오 소셜 로그인
  alpaca/        ← AlpacaCalendarAdapter (MarketCalendarRefreshPort) — Alpaca Markets API
  crypto/        ← AesCryptoService — AES-256 암호화/복호화 (persistence 경계에서만 사용)
```

### DashboardController vs StatisticsController 응답 형식 차이
- `DashboardController`: DB 기반 전용 DTO 반환 — `GET /api/accounts/{accountId}/cycle-history` → `CycleHistoryPageResponse` (커서 페이지네이션)
- `StatisticsController`: **KIS 전용** live API 직접 호출 → `PresentBalanceResult`, `PeriodProfitResult` 등 도메인 모델 그대로 반환
  - kista-ui에서 소비 시 normalizer 함수 필요 (예: `normalizePortfolio()`, `ProfitSummary` optional 필드 fallback)
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
- 새 엔티티에 타임스탬프 필요 시 `BaseAuditEntity`(`createdAt`+`updatedAt`) 또는 `BaseCreatedAtEntity`(`createdAt`만) 상속 — `updated_at` 컬럼 없는 엔티티에 `BaseAuditEntity` 사용 금지 (`ddl-auto: validate` 실패); `KisTokenEntity` 등 DB DEFAULT(`insertable=false, updatable=false`) 방식 엔티티는 그대로 유지
- 서비스에서 domain record 생성 시: `updatedAt=null` (adapter가 무시, `@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null` (`@CreatedDate`가 처리)
- `toEntity()` 내에서 `setCreatedAt()`/`setUpdatedAt()` 명시적 호출 금지 — `@CreatedDate(updatable=false)` / `@LastModifiedDate`가 INSERT·UPDATE 시 자동 처리. 호출 자체가 dead code이며 `@Setter(PACKAGE)` 범위 제약과도 충돌함

### 텔레그램 알림 (notifyTradingReport)
- 계좌별 텔레그램 설정 제거됨 — `User.telegramBotToken/chatId` 사용자봇만 사용 → 미설정 시 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용

### BrokerAdapter Registry 패턴
- `BrokerAdapterRegistry`: `Map<Account.Broker, BrokerAdapterPort>` — Spring이 `List<BrokerAdapterPort>` 자동 수집, broker 기준 Map 빌드
- `registry.require(account, XxxPort.class)` — 해당 브로커가 Capability 미지원 시 `IllegalArgumentException` → GlobalExceptionHandler 400
- `registry.find(account, XxxPort.class)` — `Optional<T>` 반환, 미지원 시 `Optional.empty()`
- 신규 브로커 추가: `BrokerAdapterPort` 구현체 1개만 추가 — Router/switch 수정 불필요
- `Account.isToss()` 삭제됨 — 브로커 분기 필요 시 `account.broker() == Account.Broker.TOSS` 직접 비교
- `BrokerAdapterRegistry`는 `public` — 여러 서브패키지(account/trading/strategy)에서 사용하므로 예외적 공개 접근자

### TDA 전략 패턴 (InfiniteStrategy)
- `TradingService.execute()` 잔고 조회: KIS API 아님 → `findRecentByCycleId(cycleId, 1)` 최신 이력에서 `AccountBalance` 구성 (이력 없으면 `IllegalStateException`)
- PRIVACY execute() null guard: `snapshot=null` → `saveAndNotify`에서 `snapshot != null` 조건 가드 유지 필수
- **`preview()` today 오프셋**: 날짜 경계는 KST 04:00 (DstInfo.SCHEDULER_RUN_TIME) → `preview()`의 `today`는 `LocalTime.now().isBefore(4,0) ? today : today+1` 패턴 (미적용 시 PRIVACY `findTodayTrade()` 날짜 1일 어긋남)
- **`INSUFFICIENT_BALANCE` skip 시 position 포함**: `shouldSkip(price)` true여도 `InfinitePosition`을 Result에 포함 — 프론트에서 단위금액·현재가·부족 금액 표시 목적

### CycleOrderStrategy Capability 패턴
- `CycleOrderStrategy` 인터페이스: 전략 타입별 동작을 캡슐화하는 다형성 계층
  - `requiresPrivacyBase()` — basePrice 소스가 기준 매매표인지 (PRIVACY=true, INFINITE/VR=false)
  - `requiresPrevClose()` — 전일 종가 필요 여부
  - `availableDivisionCounts()` — 지원하는 분할 수 목록 (INFINITE=`[20]`, PRIVACY/VR=`[]`)
  - `supportsReverseMode()` — 리버스모드 배지 지원 (INFINITE=true, PRIVACY/VR=false)
  - `endsCycleOnLiquidation()` — holdings=0(전량 청산) 시 사이클 종료 여부 (VR=false, INFINITE/PRIVACY=true)
  - `minRequiredDeposit(price, privacyBase, divisionCount)` — 최소 시드 계산 (SSOT, VR=null 미적용)
- `CycleOrderStrategies`: `Map<Strategy.Type, CycleOrderStrategy>` 라우터 — `of(type)` 으로 구현체 조회
- **프론트 capability 소비**: `GET /api/meta`의 `StrategyTypeMeta`에 capability 7필드(code/description/availableTickers/requiresPrivacyBase/tickerFixed/supportsReverseMode/divisionCounts) 직렬화 → 프론트는 `isInfinite` 휴리스틱 대신 `divisionCounts`/`requiresPrivacyBase` 직접 소비
- **최소시드 미리보기**: `GET /api/accounts/{id}/strategy-seed-preview?type=&ticker=&divisionCount=` → `StrategySeedPreviewResponse { ticker, basePrice, minSeed, skipReason }`
  - `AccountStatisticsService.strategySeedPreview()` 구현 — `BrokerPriceRouter` + `PrivacyTradePort` + `CycleOrderStrategies.minRequiredDeposit` 조합
  - PRIVACY + 기준 매매표 없는 날 → `skipReason="NO_PRIVACY_BASE"` (basePrice/minSeed=null)
- **신규 전략 타입 추가 시**: `Strategy.Type` enum case + `CycleOrderStrategy` 구현체 1개만 추가하면 메타 capability·최소시드·UI 자동 반영

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trade_bases` (`adapter/out/persistence/privacy/`, `PrivacyTradeBaseEntity`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음** (계좌별 아닌 시스템 공통 기준)
  - `(trade_date, ticker)` UNIQUE 제약 (`uq_privacy_trade_bases_date_ticker`) — 하루에 종목당 기준 매매표 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trade_base_orders` (`PrivacyTradeBaseOrderEntity`): 기준 매매표 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
- FIDA 수신 흐름: `(tradeDate, ticker)` 없음 → 201 / 내용 동일 → 200(멱등) / 내용 다름 → `PrivacyTradeConflictException` → 409
- 스케쥴러: `StrategyType.PRIVACY` → `PrivacyCycleOrderStrategy.compute()` → `PrivacyStrategy.buildOrders()` (`CycleOrderComputer`가 전략별 분기)

### VR 전략 패턴 (밸류리밸런싱)
공식·bootstrap 규칙·가격 캡·롤오버 조건의 SSOT는 constraints.md "VR 공식" — 여기서는 구조·흐름만 기록.
- **TQQQ 전용** — `Strategy.Type.VR.resolveTicker()` → `Ticker.TQQQ` 강제. divisionCount 없음(null 직렬화), cycleSeedType=NONE 강제
- `strategy_vr_version` (`StrategyVrVersionEntity`): 전략 버전별 VR 설정 — intervalWeeks(롤오버 주기), bandWidth(밴드 폭 %), recurringAmount(USD, 양수=적립·0=거치·음수=인출)
- `strategy_cycle_vr` (`StrategyCycleVrEntity`): 사이클 시작 시 스냅샷 — value(사이클 기준 V값), gradient, poolLimit
- 주문 생성: `CycleOrderComputer` → `VrCycleOrderStrategy.plan()` → `VrStrategy.buildOrders()` → 매수·매도 사다리 LIMIT+AT_OPEN 주문 생성 (bootstrap은 LOC+AT_CLOSE)
- **holdings=0에도 사이클 유지** — `endsCycleOnLiquidation()=false`, `CyclePositionPersistor`가 종료 미발동
- **N주 롤오버**: `VrCycleRolloverService.rollIfDue()` — `CyclePositionPersistor`의 포지션 저장 직후 매일 판정, due이면 V′ 계산 후 기존 사이클 종료 + 새 사이클 원자 생성 (V′≤0 보류 등 규칙 → constraints.md)
