## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).

```
domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체 (record 사용), 어그리게이트별 서브패키지
    user/        ← User (nested enums: Role, Status, NotificationChannel), FcmDeviceToken
    account/     ← Account(+nested exceptions: CooldownException, InvalidKisKeyException), Broker
    strategy/    ← Strategy(+nested: Type, Status, Ticker, CycleSeedType), StrategyCycle, CyclePosition, StrategyDetail, CyclePositionHistoryEntry, CycleHistoryPage,
                   RegisterStrategyCommand, UpdateStrategyCommand, BatchContext, InfinitePosition, ReverseModePosition, AccountBalance, TradingSnapshot, TradingReport, DstInfo
                   Strategy 필드: id, accountId, type(Type), status(Status), ticker(Ticker), cycleSeedType, divisionCount(int)
                   StrategyDetail 필드: strategy, initialUsdDeposit, isReverseMode(boolean)
                   StrategyCycle 필드: id, strategyId, startAmount, endAmount, startDate, endDate, createdAt, deletedAt
                   CyclePosition 필드: id, strategyCycleId, usdDeposit, closingPrice, avgPrice, holdings, createdAt, deletedAt
    order/       ← Order, TradeEvent
                   Order 필드: id, accountId, strategyCycleId, tradeDate, ticker, orderType, direction, quantity, price, status, kisOrderId, filledQuantity, filledPrice
                   OrderPort 조회/삭제는 strategyCycleId+tradeDate 기준 (1계좌 다중 전략 시 cycle 단위 격리): findPlannedByCycleAndDate, findPlacedByCycleAndDate, findPlannedOrPlacedByCycleAndDate, deletePlannedByCycleAndDate, deletePlannedBuyByCycleAndDate
    kis/         ← KIS 응답 record (Execution, PresentBalanceResult, PeriodProfitResult, MarginItem, KisApiException, Currency, DailyTransaction* 등)
    admin/       ← AdminAnomalies, AdminStats, AdminUserView, AuditLog
    privacy/     ← FidaOrderCommand, PrivacyCurrentBase, PrivacyTradeBase, PrivacyTradeSaveResult, PrivacyTradeConflictException
  strategy/      ← InfiniteTradingStrategy/PrivacyTradingStrategy 인터페이스 + InfiniteStrategy/PrivacyStrategy 구현 — @Component 허용 예외 (ArchUnit)
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스

application/
  service/       ← UseCase 구현체 (package-private @Service), Port를 통해서만 외부 호출, 어그리게이트별 서브패키지
    trading/     ← TradingExecutionFacade(TradingExecutionUseCase 구현 Facade — preview/executeManually/cancelOrder/cancelByCycle/execute/executeBatch 단일 진입점),
                   TradingService(배치·단건 실행 코어), ManualTradingService(수동 실행), TradingPreviewService(미리보기 전용, @Transactional readOnly),
                   OrderCancelService(주문 취소), CycleRotationService(사이클 종료 후 cycleSeedType 기반 재등록),
                   TradingBalanceLoader/TradingOrderPlanner/TradingOrderExecutor/TradingPriceFetcher/TradingReporter/BuyOrderPriceCapper/CycleOrderComputer (helper, package-private)
    account/     ← AccountService, AccountStatisticsService
    strategy/    ← StrategyService
    user/        ← UserService, UserCascadeDeleter (사용자 소프트 삭제 cascade helper)
    portfolio/   ← PortfolioService
    market/      ← MarketHolidayService
    privacy/     ← PrivacyService
    admin/       ← AdminService, AdminQueryService

adapter/in/
  schedule/      ← TradingScheduler (월~금 04:00 KST, 멀티계좌) + MarketCalendarRefreshScheduler (1월 1일 3년치 / 매월 1일 최신화)
  web/           ← REST Controller + DTO
                    AuthController (카카오/JWT/승인/탈퇴/SSE), AccountController (계좌CRUD+연결테스트), TradingCycleController (사이클CRUD+pause/resume+수동실행), DashboardController (DB기반 포트폴리오/거래내역), KisStatisticsController (KIS live 잔고/수익/가격), MetaController (enum SSOT /api/meta/**, Cache 1h), OrderCancelController, MarketHolidayController (휴장일/세션 DIRECT|BLOCKED), FidaOrderController (/api/internal/**, X-Internal-Token), SettingsController (텔레그램+알림채널), FcmController, TradeStreamController (SSE), PrivacyTradeController, Admin*Controller×7 (Dashboard/Account/Anomalies/Audit/Trade/User/PrivacyTrade — /api/admin/**), AdminPingController (/api/admin/_ping), DevAuthController (local전용)
  web/security/  ← JwtAuthFilter (Bearer JWT), InternalTokenAuthFilter (X-Internal-Token 서버간 인증)
  telegram/      ← TelegramWebhookController + TelegramBotService

### DashboardController vs KisStatisticsController 응답 형식 차이
- `DashboardController`: DB 기반 전용 DTO 반환
  - `GET /api/portfolio/current` / `GET /api/portfolio/snapshots` → `PortfolioSnapshotResponse`(`CyclePositionHistoryEntry` 기반, `marketValueUsd`/`totalAssetUsd`는 `currentPrice × holdings` computed 값, `snapshotDate` 제거됨)
  - `GET /api/trades` → `TradeHistoryResponse`(`Order` 기반)
  - kista-ui: 포트폴리오 날짜는 `snapshotDate`(제거) 대신 `createdAt`(Instant)에서 추출
- `KisStatisticsController`: KIS live API 직접 호출 → `PresentBalanceResult`, `PeriodProfitResult` 등 도메인 모델 그대로 반환
  - kista-ui에서 소비 시 normalizer 함수 필요 (예: `normalizePortfolio()`, `ProfitSummary` optional 필드 fallback)
  - 신규 KIS live 엔드포인트 추가 시 kista-ui 타입과 응답 필드명 반드시 대조 확인

adapter/out/
  kis/           ← KIS API Adapter (KisHttpClient 공통 헤더 처리)
  persistence/   ← JPA 인프라 (BaseAuditEntity, BaseCreatedAtEntity, JpaAuditingConfig) + 어그리게이트별 서브패키지
    user/        ← UserEntity + UserJpaRepository + UserPersistenceAdapter + AdminUserViewAdapter
    account/     ← AccountEntity + AccountJpaRepository + AccountPersistenceAdapter
    strategy/    ← StrategyEntity/StrategyCycleEntity/CyclePositionEntity + 각 JpaRepository + PersistenceAdapter (9파일)
    kistoken/    ← KisTokenEntity(table=broker_tokens) + KisTokenJpaRepository + KisTokenPersistenceAdapter
    audit/       ← AuditLogEntity + AuditLogJpaRepository + AuditLogPersistenceAdapter
    trade/       ← Order (Entity + Repo + Adapter)
    privacy/     ← PrivacyTradeMasterEntity + PrivacyTradeDetailEntity + PrivacyTradePersistenceAdapter (PrivacyTradePort 구현)
    calendar/    ← UsMarketHolidayEntity + UsMarketHolidayJpaRepository + MarketCalendarPersistenceAdapter
    fcm/         ← FcmDeviceTokenEntity + FcmDeviceTokenJpaRepository + FcmDeviceTokenPersistenceAdapter
  notify/        ← TelegramAdapter (NotifyPort 구현 — 관리자봇 오류/리포트 알림)
                   CompositeUserNotificationAdapter (UserNotificationPort 구현 — 하위 어댑터 위임)
                   TelegramUserNotificationAdapter + FcmAdapter (UserNotificationPort 구현 — 사용자 알림)
                   TelegramBotInfoAdapter (TelegramBotInfoPort 구현 — 봇 username 조회)
                   TelegramHttpClient (package-private — notify 패키지 내부 HTTP 헬퍼)
  sse/           ← SseEmitterRegistry (RealtimeNotificationPort 구현 — 사용자별 SSE 연결 관리)
                   TradeSseEmitterRegistry (매매 이벤트 SSE)
  kakao/         ← KakaoOAuthAdapter (KakaoOAuthPort 구현) — 카카오 소셜 로그인
  alpaca/        ← AlpacaCalendarAdapter (MarketCalendarRefreshPort 구현) — Alpaca Markets API
  crypto/        ← AesCryptoService — AES-256 암호화/복호화 (persistence 경계에서만 사용)
```

### 레이어 의존 방향

```
adapter.in  →  domain.port.in (UseCase)
application →  domain (model + port)
adapter.out →  domain.port.out (Port 구현)
domain      →  외부 의존 없음
```

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

### JPA Auditing
- `BaseAuditEntity` (`@MappedSuperclass`): `UserEntity`, `AccountEntity`가 상속 — `@CreatedDate`/`@LastModifiedDate`로 `createdAt`/`updatedAt` 자동 관리
- 새 엔티티에 타임스탬프 필요 시 `BaseAuditEntity` 상속; `KisTokenEntity` 등 DB DEFAULT(`insertable=false, updatable=false`) 방식 엔티티는 그대로 유지
- 서비스에서 domain record 생성 시: `updatedAt=null` (adapter가 무시, `@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null` (`@CreatedDate`가 처리)
- `toEntity()` 내에서 `setCreatedAt()`/`setUpdatedAt()` 명시적 호출 금지 — `@CreatedDate(updatable=false)` / `@LastModifiedDate`가 INSERT·UPDATE 시 자동 처리. 호출 자체가 dead code이며 `@Setter(PACKAGE)` 범위 제약과도 충돌함

### 텔레그램 알림 (notifyTradingReport)
- 계좌별 텔레그램 설정 제거됨 — `User.telegramBotToken/chatId` 사용자봇만 사용 → 미설정 시 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용

### TDA 전략 패턴 (InfiniteStrategy)
- `InfinitePosition(AccountBalance, Strategy.Ticker, BigDecimal price)` — `unitAmount K = B ÷ 20`
- `TelegramAdapter`/`TelegramUserNotificationAdapter` 접근 경로: `r.snapshot().X()` (포맷 문자열 변경 금지)
- `TradingService.execute()` 잔고 조회: KIS API 아님 → `findRecentByCycleId(cycleId, 1)` 최신 이력에서 `AccountBalance` 구성 (이력 없으면 `IllegalStateException`)
- PRIVACY execute() null guard: `snapshot=null` → `saveAndNotify`에서 `snapshot != null` 조건 가드 유지 필수
- **`preview()` today 오프셋**: 스케줄러는 KST 04:00 실행 → `preview()`의 `today`는 `LocalTime.now().isBefore(4,0) ? today : today+1` 패턴. 미적용 시 PRIVACY `findTodayTrade()` 날짜 1일 어긋남
- **`INSUFFICIENT_BALANCE` skip 시 position 포함**: `shouldSkip(price)` true여도 `InfinitePosition`을 Result에 포함 — 프론트에서 단위금액·현재가·부족 금액 표시 목적 (`position=null` 관행의 의도된 예외)
- **`AccountBalance.shouldSkip(price)` 오버로드**: 0회차(holdings==0)에서 `unitAmount(=usdDeposit/20) < currentPrice`면 매수 수량 0 케이스. `tryLoadBalance()`의 `shouldSkip()` 통과 후 INFINITE 블록에서 추가 검증
- **0회차 미리보기**: holdings=0에서 LOC매수②만 기준가에 생성, 매도 수량=0 생략 — 정상 동작. 0회차에서 priceOffsetRate=targetProfitRate이므로 referencePrice=targetPrice (두 KPI 카드 동일값)
- **dev-token 소유권 제한**: `POST /api/auth/dev-token`은 고정 UUID(`...0001`) 소유 계좌만 접근 가능 — 403 시 DB에서 `cycle_position` 직접 조회로 대체

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trades_master` (`adapter/out/persistence/privacy/`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음** (계좌별 아닌 시스템 공통 기준)
  - `(trade_date, ticker)` UNIQUE 제약 (`uq_privacy_trades_master_date_ticker`, V1, V4에서 제약명 rename) — 하루에 종목당 마스터 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trades_detail`: 마스터 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
- FIDA 수신 흐름 (`PrivacyTradeSaveResult(id, created)` 반환):
  - `(tradeDate, ticker)` 없음 → INSERT → 201
  - 있고 내용 동일 → 200 (멱등)
  - 있고 내용 다름 → `PrivacyTradeConflictException` (`domain/model/privacy/`) → 409
- `PrivacyTradeSaveResult` (`domain/port/out/`): `UUID id` + `boolean created` — 컨트롤러가 201/200 분기
- 스케줄러 흐름: `StrategyType.PRIVACY` → `privacy_trades_master/detail` 조회 → `orders` 복사 (미구현, INFINITE와 동일 출구)
- 테이블명 컨벤션: 마스터-디테일 분리 시 `xxx_master` / `xxx_detail` 접미사 패턴 사용

