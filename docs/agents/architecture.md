## 아키텍처

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (record aggregate 분리 제약 → constraints.md "Account ↔ Strategy 분리").

```
common/          ← 공통 유틸리티 (Spring/JPA 독립)
  UsTradeDates   — KST↔US 거래일 ±1일 변환. 사용 허용 위치: KisTradingApi(KIS API는 US 거래일 기준)·MarketCalendarPersistenceAdapter·KisPriceApi(dailyprice BYMD)뿐 — 도메인·서비스·orders persistence에서 사용 금지 (→ constraints.md "시간 기준 정책")

domain/          ← 순수 Java record/class. Spring·JPA 어노테이션 금지
  model/         ← 불변 값 객체(record), 어그리게이트별 서브패키지: user/account/strategy/order/auth/market/stats/admin/privacy/settings/asset (broker/kis/toss는 com.kista.broker.domain.model로 이전됨)
                   Strategy.Type/Status/Ticker/CycleSeedType 등은 Strategy record의 nested enum (→ constraints.md "Account ↔ Strategy 분리")
                   Order.orderLeg — 신규 주문은 concrete leg 필수, legacy 행만 UNKNOWN 유지 (→ constraints.md "스케쥴러 주문 예산 배정")
                   privacy/PrivacyDates.releaseDateFor()/tradeDateOf() — FIDA 발행일↔거래일 업무 규칙 헬퍼 (시간대 변환 아님)
  strategy/      ← 전략 구현 클래스(Infinite/ReverseInfinite/Privacy/VrStrategy) — @Component 허용 예외 (ArchUnit)
                   CycleOrderStrategy capability 패턴 SSOT (아래 "CycleOrderStrategy Capability 패턴" 참고)
                   PriceCapPolicy(순수 정적 유틸) — 매수 가격 캡 배수(currentPrice×1.05) 단일 SSOT, application의 BuyOrderPriceCapper와 domain의 VrStrategy.buildCappedBuyOrders()가 공용
  port/in/       ← UseCase 인터페이스 (인바운드 포트)
  port/out/      ← 아웃바운드 포트 인터페이스 (*Port) (broker Capability 인터페이스는 com.kista.broker.domain.port.out으로 이전됨)

application/
  service/       ← UseCase 구현체 (package-private @Service), Port를 통해서만 외부 호출, 어그리게이트별 서브패키지: trading/account/strategy/user/portfolio/stats/market/privacy/admin/settings/auth/asset (broker는 com.kista.broker.application.service로 이전됨)
    trading/     ← TradingExecutionFacade가 preview/executeManually/cancelOrder/cancelByCycle/execute/executeBatch 단일 진입점, TradingService가 배치·단건 실행 코어
                   PreviewDepositCache — TradingBuyCompetitionSimulator 전용 계좌 단위 라이브 예수금(usdDeposit) 짧은 TTL(3초) 캐시 + 계좌별 락으로 동시 miss 단일화. usdDeposit은 ticker 무관 계좌 전체 값이라 계좌당 전략 N개의 preview 병렬 호출을 실제 조회 1회로 축소. 실주문 집행 경로(ManualTradingService/TradingOrderBudgetAllocator)는 미사용 — 항상 최신값 직접 조회
                   TradingOrderBudgetAllocator — 계좌별 slot-aware BUY/SELL 독립 예산 배정 (우선순위·실패 격리·제외 규칙 상세 → workflow.md "스케쥴러 주문 예산 배정")
                   live 잔고·판매가능수량 조회는 com.kista.broker.application.service.BrokerAdapterRegistry.require(account, LiveBalancePort/SellableQuantityPort.class) 직접 라우팅 — 별도 Router 클래스 없음 (KIS: TTTS3012R fetchHolding 재사용 / Toss: /api/v1/sellable-quantity)
                   CyclePositionPersistor: 포지션 스냅샷 저장 + 사이클 종료·rotation + VrCycleRolloverService.rollIfDue() 호출 (VR 예외 → "VR 전략 패턴")
    stats/       ← StatsService(UserStatsUseCase 구현 — summary/equity-curve/cycles/housing-benchmark 계열)
                   StatsResultCache — summary/equity-curve 5분, 벤치마크 비교(housing-benchmark, ETF 포함) 10분 인메모리 TTL 캐시. 매매 직후 통계가 해당 TTL만큼 stale할 수 있음. 단일 인스턴스 전제 — 다중 인스턴스 시 인스턴스별 캐시가 최대 TTL만큼 상이할 수 있음
                   MonthlyReturnCalculator/HousingBenchmarkComparisonBuilder — Spring·포트 비의존 순수 계산 클래스(TWR·정규화 비교 조립). HousingBenchmarkComparisonBuilder는 ETF 비교에도 재사용됨(이름은 HousingBenchmark)
                   getHousingBenchmarkComparison: currentExchangeRate는 요청마다 실시간 조회하는 정보성 필드일 뿐 수익률·공통월·summary 계산에는 미반영(조회 실패 시 null 처리, 200 정상 반환) — 투자(USD)·벤치마크(HOUSING=KRW/ETF=USD) 현지통화 그대로 비교, 환율 변환 없음
  event/         ← @TransactionalEventListener용 도메인 이벤트(application 레이어) — 사용자 승인/거부/재신청/신규가입, 사이클 종료/신규시작, 매매리포트, 주문취소실패, 사용자탈퇴 등

adapter/in/
  schedule/      ← 매매·시세·캘린더·KB Land 등 배치 스케쥴러 — 정확한 실행 시각·락 TTL은 `scheduler-time-table.md` 참고
                   BatchContextFactory(전략 목록 → BatchContext 빌드, 조회 실패 시 skip + notifyError), SchedulerJobRunner(공통 실행 골격 — 시작/완료 알림·인터럽트 처리)
                   SchedulerLockService(package-private 분산 락 — tryRun(lockKey, timeout, task); @ConditionalOnProperty(scheduler.enabled) 로컬 중복 실행 방지) — Postgres 기반(`scheduler_locks` 테이블, DB 서버 시각 `now()` 기준 `INSERT ... ON CONFLICT ... WHERE lock_until <= now()`)이라 다중 인스턴스라도 시계 편차 없이 안전하게 경쟁, 어느 한쪽만 매 사이클 실행
                   MarketIndexPriceSyncScheduler는 비거래일에도 Alpaca 빈 배열 반환으로 무해한 no-op이라 요일 조건 없음
  web/           ← REST Controller + DTO — Auth/Account/TradingCycle/Dashboard/Statistics(KIS 전용 live)/TossStatistics(Toss 전용 live)/Stats(DB 근사 집계)/FearGreed/Meta(enum SSOT)/OrderCancel/MarketHoliday/Backtest(과거 일봉 시뮬레이션, 계좌 무관)/FidaOrder(`/api/internal/**`)/Settings/Fcm/TradeStream(SSE)/Admin*/Asset/AssetMonthlyCheck/RuntimeConfig/AdminSettings/AdminObservability/AdminScheduler/AdminPing/DevAuth(local 전용)/ClientErrorLog
                   상세 라우팅·응답 형식 차이 → 아래 "DashboardController vs StatisticsController" 참고
  web/security/  ← JwtAuthFilter (Bearer JWT), InternalTokenAuthFilter (X-Internal-Token 서버간 인증)

adapter/out/
  (broker/kis/toss/mock/persistence/kistoken은 com.kista.broker.adapter.out.{internal,kis,toss,mock,persistence}로 이전됨 — 아래 "com.kista.broker/" 참고)
  marketdata/    ← CommonMarketPriceFeed — 계좌 자격증명 불필요한 공통 시세 조회 인터페이스, com.kista.broker.adapter.out.toss.TossPriceApi가 구현(모의계좌가 재사용)
  kbland/        ← KB Land 아파트 5분위 매매평균가격(월간) + 주간 매매가격지수 조회 어댑터
  feargreed/     ← CnnFearGreedAdapter, CryptoFearGreedAdapter
  redis/         ← RedisBlacklistAdapter (BlacklistPort — userId/JTI 단위 JWT 블랙리스트, TTL 기반)
  persistence/   ← JPA 인프라 + 어그리게이트별 서브패키지, 각각 Entity + *JpaRepository(package-private) + *PersistenceAdapter(Port 구현) 3종 구성
                   DB 스키마 3분리(kista/finance/reference, V15 마이그레이션): kista=순수 매매 도메인(계좌·주문·전략·포지션), finance=가계부, reference=외부 참조·시장 데이터(FIDA PRIVACY 기준 매매표 포함, 전역 공유·비개인 데이터가 기준). 인증/관리자/로그/알림 성격 테이블(users/user_settings/user_notification_prefs/refresh_tokens/broker_tokens/admin_runtime_settings/audit_logs/app_error_logs/fcm_device_tokens/scheduler_locks)은 플랫폼 공통이라 public 유지. 신규 테이블은 이 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 kista라 생략 시 validate 실패) — nativeQuery/JdbcTemplate/raw SQL은 DB 유저 search_path(`kista, finance, reference, public`)로 unqualified 이름이 자동 해석되므로 스키마 접두사 불필요
  sse/           ← SseEmitterRegistry(사용자별 SSE), TradeSseEmitterRegistry(매매 이벤트 SSE)
  kakao/         ← KakaoOAuthAdapter — 카카오 소셜 로그인
  alpaca/        ← AlpacaCalendarAdapter, AlpacaIndexPriceAdapter — Alpaca Markets API
  heartbeat/     ← HeartbeatAdapter — 스케쥴러 dead-man's switch 핑, Open/Close 스케쥴러가 호출
  crypto/        ← AesCryptoService(AES-256, persistence 경계에서만 사용), AccountNoHasher(계좌번호 결정론적 HMAC-SHA256 해시 — 전역 중복 체크용)

com.kista.finance/   ← Spring Modulith 첫 이전 모듈(CLOSED) — 가계부 애그리게이트, 위 레거시 4패키지와 별개 최상위. 내부는 동일 Hexagonal 레이어 유지
  domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — domain/port/{in,out}와 함께 "domain" NamedInterface로 병합 공개
  domain/port/in/    ← UseCase 인터페이스, domain/port/out/ ← *Port 접미사
  application/service/  ← FinanceAccountService/FinanceBudgetService/FinanceCategoryService/FinanceGroupService/FinanceTransactionService/AssetSnapshotService/BulkFinanceRegisterService/MonthlyClosingService/FinanceRegistrationReminderNotifier — 모두 internal(외부 비공개)
  adapter/in/web/     ← Finance*Controller/AssetSnapshotController/MonthlyClosingController/AdminFinanceCategoryController(경로만 /api/admin/**, finance 소유 유지) + dto/
  adapter/in/schedule/ ← FinanceRegistrationReminderScheduler
  adapter/out/persistence/ ← Entity + *JpaRepository + *PersistenceAdapter 3종

com.kista.notify/    ← Spring Modulith 2번째 이전 모듈(CLOSED) — Telegram/FCM 알림 발송 애그리게이트, 위 레거시 4패키지와 별개 최상위. domain/model·application 레이어 없이 domain/port/out(공개 계약) + adapter만 존재하는 얇은 게이트웨이 모듈(자체 UseCase 없음, 레거시 domain/port/in을 그대로 소비)
  domain/port/out/    ← NotifyPort/UserNotificationPort/FcmDeviceTokenPort/TelegramBotInfoPort — "domain" NamedInterface로 공개
  adapter/in/telegram/ ← TelegramWebhookController + TelegramBotService, TelegramApiClient(package-private) + TelegramUpdate
  adapter/out/gateway/ ← TelegramAdapter(관리자봇), CompositeUserNotificationAdapter → TelegramUserNotificationAdapter + FcmAdapter(사용자 알림), TelegramBotInfoAdapter/TelegramHttpClient/TelegramConfig/TelegramProperties/FcmConfig + 이벤트 리스너 5종(CycleEndedNotifier/CycleLifecycleNotifier/OrderCancelFailureNotifier/TradingReportNotifier/UserDeletedNotifier)
  adapter/out/persistence/ ← FcmDeviceTokenEntity + FcmDeviceTokenJpaRepository + FcmDeviceTokenPersistenceAdapter

com.kista.broker/    ← Spring Modulith 3번째 이전 모듈(CLOSED) — KIS/Toss/Mock 증권사 연동 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"application" 두 NamedInterface만 공개, adapter/out은 의도적으로 비공개(모듈 내부 구현)
  domain/model/       ← Currency/DailyTransaction*/Execution/MarginItem/PresentBalanceResult 등 공통 불변 값 객체
  domain/model/kis/   ← KisApiException 등 KIS 전용 도메인 모델
  domain/model/toss/  ← Toss 전용 도메인 모델 — domain/model·domain/port/out과 함께 "domain" NamedInterface로 병합 공개
  domain/port/out/    ← 브로커 Capability 인터페이스(*Port) 총 15개 — 공통 7개(KIS/Toss/Mock 모두 구현) + BrokerAdapterPort(라우팅 마커) + BrokerConnectionTestPort(*AuthApi 클래스가 구현) + BrokerTokenCachePort(KisTokenPersistenceAdapter가 구현) + Toss 전용 5개. BrokerConnectionTestPort: 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅 — verifyAccount→brokerAccountCode(KIS: null, Toss: accountSeq)
  application/service/ ← BrokerAdapterRegistry(public, require(account, Port.class)/find())/BrokerConnectionTesters(계좌 등록 전 자격증명 검증)/BrokerCallGuard — "application" NamedInterface로 공개
  adapter/out/kis/    ← KisHttpClient(공통 헤더 + executeWithRetry: 401 시 거절된 토큰을 조건부 무효화한 후 최신 토큰으로 1회 재시도)/KisAuthApi/KisOrderApi/KisPriceApi/KisTradingApi/KisResponseParser/KisExchangeRegistry/KisConfig/KisTokenCoordinator/KisBrokerAdapter(BrokerConnectionTestPort는 KisAuthApi가 구현)
  adapter/out/toss/   ← TossHttpClient/TossConfig/TossAuthApi/TossCandleApi/TossHoldingsApi/TossOrderApi/TossPriceApi/TossMarketApi/TossResponseParser/TossResult/TossMarketCalendarCache/TossStockInfoCache/UsdKrwRateCache
                        TossDistributedTokenCoordinator + TossRedisTokenStore(계좌·관리자 Redis canonical token; TTL owner lease+원자적 generation INCR; generation counter/canonical generation을 비교하는 Lua fencing CAS; owner-safe unlock; SHA-256 최근 발급 fingerprint 2초 TTL) — 관리자(admin) 토큰은 Account가 없어 TokenCoordinator 범위 밖 별도 public 메서드(TossTokenStore)
                        TossBrokerAdapter(공통 7개 + Toss 전용 5개 Port 구현; BrokerConnectionTestPort는 TossAuthApi가 구현)
  adapter/out/mock/   ← MockBrokerAdapter(BrokerConnectionTestPort는 MockAuthApi가 구현) — 증권사 API 호출 없이 DB(cycle_position/orders) 기반 잔고·체결 시뮬레이션. 시세는 레거시 adapter/out/marketdata/CommonMarketPriceFeed 경유
                        getLiveBalance()의 usdDeposit은 계좌 내 전략 전체 합산값(TradingOrderBudgetAllocator가 대표 전략 1개로 계좌 전체 BUY 예산을 판단하는 기존 계약에 맞춤 — 전략별 값을 그대로 반환하면 다른 전략 잔고로 오판정)
  adapter/out/internal/ ← TokenCoordinator(계좌 토큰 obtain/recover 공통 계약 — 순수 adapter 내부 인터페이스, KIS/Toss 둘 다 구현하지만 폴리모픽 주입 지점은 없음: KisAuthApi/TossAuthApi 각자 구체 타입을 직접 주입)/DoubleCheckedTokenCache(KisTokenCoordinator 전용 JVM 내 토큰 캐시 — 1차 조회 → miss 시 계좌별 락 → 2차 double-check → 신규 발급; `BrokerTokenCachePort.saveToken`/`invalidateToken`은 REQUIRES_NEW로 락 해제 전 독립 커밋)/PrevCloseCache(전일종가 캐시, 현재 사용처는 TossPriceApi뿐)
  adapter/out/persistence/ ← KisTokenEntity + KisTokenJpaRepository + KisTokenPersistenceAdapter (옛 persistence/kistoken)
```

### Spring Modulith 점진 도입
`finance`가 첫 이전 모듈이다(`@ApplicationModule` CLOSED, `domain` 레이어만 `@NamedInterface("domain")`으로 공개). 레거시 최상위 4패키지(`common`/`domain`/`application`/`adapter`)는 아직 옮기지 않은 코드가 담긴 임시 이전 shim으로 `Type.OPEN` 선언돼 있어 외부 참조를 계속 허용한다 — 내용물이 모두 새 모듈로 옮겨지면 package-info와 함께 자연 소멸한다. `ApplicationModules.verify()`(`ModulithArchitectureTest`)와 일반화된 `HexagonalArchitectureTest`(`..domain..` 등 와일드카드 매처로 옛 최상위 구조·새 모듈 구조를 규칙 하나로 동시 커버) 둘 다 `com.kista.architecture` 패키지에서 실행된다 — 전자는 모듈 **간** 경계, 후자는 모듈 **내부** 레이어 방향을 각각 담당하는 직교 축. `notify`가 두 번째 이전 모듈이다(`@ApplicationModule` CLOSED, 자체 domain/model·application 없이 domain/port/out만 "domain" NamedInterface로 공개). `broker`가 세 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain/model+domain/model.kis+domain/model.toss+domain/port/out)과 "application"(application/service) 두 NamedInterface만 공개 — adapter/out은 KIS/Toss/Mock 연동 구현 디테일이라 의도적으로 비공개). 전체 계획·향후 모듈 순서(finance✅ → notify✅ → broker✅ → trading)는 `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md` 참고.

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
- 신규 브로커 추가: `com.kista.broker.domain.port.out.BrokerAdapterPort` 구현체 1개만 추가 — Router/switch 수정 불필요
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
- `privacy_trade_bases` (`adapter/out/persistence/privacy/`, `PrivacyTradeBaseEntity`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음** (계좌별 아닌 시스템 공통 기준)
  - `(release_date, ticker)` UNIQUE 제약 (`uq_privacy_trade_bases_release_date_ticker`) — 하루에 종목당 기준 매매표 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trade_base_orders` (`PrivacyTradeBaseOrderEntity`): 기준 매매표 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
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
