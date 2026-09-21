## 아키텍처

### Gradle 구조

세 서브프로젝트: `:trading-core`(매매 실행 도메인 — trading/matching/broker/account/privacy/marketcalendar), `:shared`(sharedkernel/platform — outbound-zero 공용 어휘+인프라 leaf), 루트 `:api`(그 외 전부, bootJar 산출). 루트와 `:trading-core` 모두 `:shared`를 단방향 `implementation` 의존한다(`shared ← trading-core`, `shared ← api`).

- 루트 main과 `:trading-core` main 사이 컴파일 의존은 **양방향 모두 없다** — 루트는 trading-core 타입을 import할 수 없고 HTTP 내부 API·Redis·own-type으로만 통신한다. 루트 **테스트** 소스만 `testImplementation(project(":trading-core"))`·`testFixtures(project(":trading-core"))`로 참조한다(루트 `src/testFixtures`엔 User fixture `DomainFixtures`만 잔류).
- 배포 산출물은 둘: 루트 `app.jar`(`kista-api`/`kista-scheduler` 2-role)와 `trading-core.jar`(`TradingApplication`, `kista-trading` 프로세스). `Dockerfile`이 한 이미지에 두 jar를 담고 `APP_JAR`로 선택한다 — 컴파일 경계이면서 런타임 프로세스 분리다.
- `shared/build.gradle.kts`는 JUnit Platform 버전 정합을 위해 `org.springframework.boot` 플러그인을 적용하되 `bootJar`를 `enabled = false`로 비활성화한다. `trading-core/build.gradle.kts`는 `bootJar`를 활성 상태로 둔다.
- 테스트 지원: `com.kista.support`(`DataJpaTestBase`/`WebMvcTestSupport`/`TradingFixtures`)·`application-test.yml`은 `trading-core/src/testFixtures`. com.kista.trading 밖 패키지의 trading-core `@DataJpaTest`는 상위 `@SpringBootConfiguration`이 없으므로 `@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)`를 명시한다. `trading-core`의 `test` 태스크는 `workingDir = rootProject.projectDir`다(루트 상대경로를 읽는 테스트용). Flyway 마이그레이션은 서비스별로 나뉜다 — root `db/migration`, trading-core `db/migration-trading`.
- **경계 검증**: `GradleModuleBoundaryTest`(`src/test/java/com/kista/architecture`)가 `:trading-core→:api`, `:shared→:trading-core`/`:shared→:api` 역방향 의존 금지를 컴파일 산출물(`*/build/classes/java/main`) 기준으로 강제하고, trading-core 테스트·testFixtures 산출물도 동일 규칙으로 검증한다. 복제(own-type) 쌍의 shape 드리프트는 `OwnTypeContractTest`가 검증한다(reader 컴포넌트 ⊆ writer, 상세는 테스트 헤더 주석 — (b) 외부 계약 분리 쌍은 독립 진화가 의도라 제외).

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`). `domain_must_not_depend_on_outer_layers`는 `com.kista..domain..` 전체를 예외 없이 커버한다 — 전략 구현체 Spring 배선은 `CycleStrategyBeanConfig` 팩토리가 전담한다.
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (record aggregate 분리 제약 → constraints.md "Account ↔ Strategy 분리"). 마이그레이션·이관·사고 이력은 `docs/agents/modulith-migration-history.md` 참고 (필요시 Read).

Spring Modulith 기반 애그리게이트 모듈 구조다. 레거시 최상위 `com.kista.domain`/`application`/`adapter` 패키지는 전부 소멸했다.

```
DB 스키마 5분리, 소유 서비스별(같은 DB `kistadb`·같은 DB 유저). root(`:api`): `public`(users/refresh_tokens/user_settings/user_notification_prefs/admin_runtime_settings/audit_logs/app_error_logs/fcm_device_tokens 등 인증/관리자/로그/알림 성격 플랫폼 공통 + `scheduler_locks`/`event_publication`)·`finance`(가계부)·`kista_ref`(외부 참조·시장 데이터 — housing_benchmark_prices/housing_price_indices/market_index_prices/fear_greed_snapshots). `:trading-core`: `trading`(accounts/broker_tokens/strategy·strategy_version 계열/strategy_cycle 계열/cycle_position 계열/orders + `user_notify_profile` 복제본 + `scheduler_locks`/`event_publication` **사본** — root `public`에 의존하지 않도록 자체 보유)·`trading_ref`(us_market_holidays + FIDA PRIVACY 기준 매매표 privacy_trade_bases/privacy_trade_base_orders, 전역 공유·비개인 데이터가 기준). `KisTokenEntity`(`broker_tokens`)는 trading 소유. Flyway도 서비스별 — root는 `src/main/resources/db/migration`(이력 `flyway_schema_history_api`, 기본 스키마 `public`), trading-core는 `trading-core/src/main/resources/db/migration-trading`(이력 `flyway_schema_history_trading`, 기본 스키마 `trading`)이며 각자 소유 스키마 테이블만 만든다. **다른 서비스 소유 스키마 테이블은 참조 금지(FK 포함)**. `event_publication`이 서비스별 2개라 EPR 재발행 소유도 갈린다(root=`kista-scheduler`, trading=`kista-trading` → constraints.md Git 규칙). 신규 테이블은 소유 서비스 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 root는 `finance`·trading은 `trading`이라 생략 시 validate 실패) — nativeQuery/JdbcTemplate/raw SQL은 Hikari `connection-init-sql`의 search_path(root `finance, kista_ref, public` / trading `trading, trading_ref`)로 unqualified 이름이 자동 해석되므로 스키마 접두사 불필요(trading 프로세스는 root `public` 테이블을 볼 수 없다). 재편 경위 → `docs/agents/modulith-migration-history.md` "DB 스키마 재편"

com.kista.sharedkernel/ ← 순수 어휘 패키지. `@ApplicationModule(Type.OPEN)` — outbound reference 0인 값 타입만 담아 다른 모듈을 참조하지 않는다는 전제(`HexagonalArchitectureTest.sharedkernel_must_not_depend_on_other_modules`). user/account 모듈은 소비만 하고 소유하지 않는다. 승격 경위 → history "sharedkernel 승격 경위"
  공유 enum       ← UserRole/UserStatus/NotificationType/StrategyType/StrategyStatus/StrategyTicker/StrategyCycleSeedType/Broker(TOSS/KIS/MOCK)/OrderDirection/OrderType/OrderStatus/OrderTiming. KIS/Toss wire 매핑은 어댑터 코드가 담당(enum 값 집합엔 외부 계약 없음). `NotificationChannel`은 user 단독 소비라 `com.kista.user.domain.model` 잔류
  정책·기본값     ← RecurringMode/StrategyCreationSettings/StrategyFieldSettings(전략 생성 정책 — 자체 검증 로직을 가진 record도 outbound-zero면 포함)/StrategyDefaults(`DEFAULT_DIVISION_COUNT=20` — PRIVACY/VR처럼 분할 수 설정이 없는 전략의 고정값)
  유틸            ← `TimeZones`(KST 단일 소스)/`AccountNumberMasker`(계좌번호 마스킹 SSOT). `UsTradeDates`는 어댑터 전용이라 sharedkernel이 아닌 `platform.time` 소속
  값 타입         ← `TradingReport`(notify `notifyTradingReport()` 시그니처)/`ReturnMetrics`(자산곡선 성과 지표 순수 계산 — root `HousingBenchmarkComparisonBuilder`·trading-core `BacktestEngine`/`BacktestService` 공용)/`DailyCandle`(백테스트 일봉 OHLC)/`TradeLegSummary`(direction/ticker/quantity/price/amountUsd 5필드 — `broker.Execution`의 notify 전용 narrowing). 전부 sharedkernel enum+JDK 타입만 참조
  이벤트          ← `UserDeletedEvent`(발행 주체는 user, 구독은 trading·finance·account 등)/`UserNotifyProfileChangedEvent(userId, notificationPrefs, balanceCheckEnabled, active)`/`CycleCompletedEvent`/`CycleEndedEvent`/`NewCycleStartedEvent`/`InsufficientBalanceEvent`/`TradingReportReadyEvent`(notify가 실제로 쓰는 스칼라만 담음 — 재조회 없음)/`OrderCancelFailedEvent`/`TradingErrorEvent`/`MarketClosedEvent`/`MarketOpenEvent`/`MarketCloseEvent`/`BatchInterruptedEvent`(accountNickname 포함)/`PrivacyAlertRaisedEvent`. 이벤트 FQCN을 옮기면 `event_publication`에 `ClassNotFoundException`이 남는다 → constraints.md Flyway 절
  port/           ← 순수 인터페이스 3개(`BrokerEnabledPort`/`StrategyCreationPolicyPort`/`HistoricalCandlePort`) — root가 trading-core 정의 인터페이스를 implements하려면 타입 identity가 필요해 own-type 복제로 대체 불가. 시그니처가 sharedkernel+JDK 타입만 사용

com.kista.platform/  ← 전역 인프라 leaf 모듈. `@ApplicationModule(Type.OPEN)` — `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`가 outbound-zero를 강제(`com.kista.platform..` 외 `com.kista..` 전부 금지). Spring/JPA 바인딩이 있어 sharedkernel(순수 JDK)과 분리
  persistence/   ← BaseAuditEntity(`createdAt`+`updatedAt`)/BaseCreatedAtEntity(`createdAt`만)/JpaAuditingConfig(`@EnableJpaAuditing` 단독 선언 — `@SpringBootApplication`에 두면 `@WebMvcTest` BeanCreationException). `@Setter(AccessLevel.PACKAGE)` 범위 주의 → constraints.md "Lombok @MappedSuperclass 상속 주의"
  crypto/        ← AesCryptoService(AES-256, persistence 경계에서만 사용)/AccountNoHasher(계좌번호 결정론적 HMAC-SHA256 해시 — 전역 중복 체크용)/Sha256(RT 해시·Toss token fingerprint 공용)
  time/          ← UsTradeDates(KST↔US 거래일 단순 ±1 변환). 사용처는 `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`가 4클래스(KisTradingApi/KisPriceApi/TossPriceApi/MarketCalendarPersistenceAdapter)로 강제
  scheduling/    ← SchedulerJobRunner(공통 실행 골격 — STARTED/COMPLETED/FAILED `SchedulerLifecycleEvent` 발행 + 인터럽트 처리. `run(String, Runnable)` / 제네릭 `<T> run(String, Supplier<List<T>>, Action<T>)`)/SchedulerLockService(package-private 분산 락 `tryRun(lockKey, timeout, task)` — `@ConditionalOnProperty(scheduler.enabled)`, Postgres `scheduler_locks`, DB 서버 시각 `now()` 기준 `INSERT ... ON CONFLICT ... WHERE lock_until <= now()`라 다중 인스턴스 시계 편차 무관)/SchedulerLifecycleEvent(jobName, Phase, errorMessage). `SchedulerJobRunner`는 `NotifyPort`를 직접 주입하지 않고 이벤트만 발행하며 notify `SchedulerNotifier`(`@TransactionalEventListener(fallbackExecution=true)`)가 구독. 정확한 실행 시각·락 TTL → `scheduler-time-table.md`. **`@Scheduled` 사용 프로세스는 `@EnableScheduling` 필수** — 빠지면 에러 없이 조용히 무시된다(신규 부트 클래스에 `@Scheduled` 소비자가 있으면 반드시 확인, 실제 사고 → history)
  redis/         ← `RedisStreamConfig`(user 이벤트 Stream 키·컨슈머그룹명)/`RedisPubSubConfig`
  metrics/       ← MetricsConfig(모듈 의존 0의 순수 인프라)

com.kista.matching/  ← Spring Modulith 모듈(CLOSED) — 주문생성 커널(순수 계산). `domain/model` + `domain/strategy` 두 패키지가 `"kernel"` NamedInterface로 병합 공개. outbound 엣지는 `sharedkernel`·`privacy`뿐(`HexagonalArchitectureTest.matching_must_not_depend_on_other_modules` — PRIVACY 전략이 `FidaPlannedOrder` 등 privacy 계획 데이터를 읽어야 함). domain은 Spring 비의존 — 빈 배선은 `trading`의 `CycleStrategyBeanConfig`가 전담, `stats`의 `BacktestEngine`은 순수 도메인 원칙으로 직접 `new`
  domain/model/       ← `AccountBalance`(순수 잔고 record — `buyTotal`/`hasSufficientDepositFor`/`applyExecutions`; `Execution→Fill` 변환은 호출부 TradingReporter/AdminTradeCorrectionService/BacktestEngine이 담당)/`BootstrapPosition`/`InfinitePosition`/`PlannedOrder`(direction/orderType은 sharedkernel 참조, `OrderTiming`도 sharedkernel)/`ReverseModePosition`/`StrategyVrDetail`(`gradientAt`/`poolLimitRateAt` — VR 공식 SSOT)/`VrPosition`. 현재가+전일종가는 `broker.PriceSnapshot`을 쓴다(커널은 `BigDecimal` 스칼라만 받음)
  domain/strategy/    ← `CycleOrderStrategy` 계열(Infinite/ReverseInfinite/Privacy/VrStrategy + `*CycleOrderStrategy` 4종) 전체 — capability 패턴 SSOT(아래 "CycleOrderStrategy Capability 패턴"), `PriceCapPolicy`(매수 가격 캡 배수 SSOT). `PlanContext`는 `StrategyTicker ticker`만 보유 — matching↔trading 순환 방지
  adapter/in/web/     ← internal — `StrategyCapabilityInternalController`(`GET /api/internal/matching/strategy-capabilities/{type}`) + `StrategyCapabilityResponse`(own-type — root `web.dto.StrategyCapability`가 소비). root `MetaController`의 `CycleOrderStrategy` 직접 import를 이 내부 API 호출로 대체. `cycleStrategies` 빈은 여전히 `trading`의 `CycleStrategyBeanConfig`가 배선

com.kista.finance/   ← Spring Modulith 모듈(CLOSED) — 가계부 애그리게이트
  domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — "domain" NamedInterface
  application/usecase/  ← UseCase 인터페이스(9개) — "usecase" NamedInterface
  application/port/output/ ← *Port(7개) — "port" NamedInterface
  application/service/  ← FinanceAccountService/FinanceBudgetService/FinanceCategoryService/FinanceGroupService/FinanceTransactionService/AssetSnapshotService/BulkFinanceRegisterService/MonthlyClosingService/FinanceRegistrationReminderNotifier + MonthlyClosingGuard(package-private) — internal
    - **마감월 쓰기 차단**: `MonthlyClosing.completed=true`인 달은 자산 스냅샷·거래의 create/update/delete/shareToGroup/unshare를 `MonthClosedException`(409)으로 전면 차단. `MonthlyClosingGuard.verifyMonthOpen(currentGroupId, userId, date)`가 SSOT(`AssetSnapshotService`/`FinanceTransactionService`가 주입). update는 기존 날짜+대상 날짜 양방향 검사. 마감 스코프는 `MonthlyClosingPort.isMonthClosed`의 either/or(그룹 있으면 그룹 마감 행, 없으면 개인 마감 행) — `findMyScope`의 union과 다르다(그룹 소속 유저는 개인 소유 record도 그룹 마감으로 판정). `MonthlyClosingService.upsert`(마감 해제)는 가드 대상 아님. bulk 등록은 항목별 try/catch라 마감월 항목만 실패 수집
  adapter/in/web/     ← Finance*Controller/AssetSnapshotController/MonthlyClosingController/AdminFinanceCategoryController(경로만 /api/admin/**, finance 소유) + dto/
  adapter/in/schedule/ ← FinanceRegistrationReminderScheduler
  adapter/out/persistence/ ← Entity + *JpaRepository + *PersistenceAdapter 3종

com.kista.notify/    ← Spring Modulith 모듈(CLOSED) — Telegram/FCM 알림 발송. 얇은 게이트웨이(자체 UseCase 없음) — `application.port.output`만 공개("port" NamedInterface), 나머지 internal. domain/model엔 SSE 값 객체 `TradeEventView` 하나뿐(NamedInterface 없음)
  application/port/output/ ← NotifyPort/UserNotificationPort/FcmDeviceTokenPort/RealtimeNotificationPort(SSE — `notifyStatusChange(UUID, UserStatus)`/`notifyTrade(UUID, TradeEventView)`) — "port". `TradeEventView`는 trading-core `com.kista.trading.notify.domain.model.TradeEventView`(발행측)와 필드 byte-identical own-type — Redis Pub/Sub(`RedisTradeEventSubscriber`)가 JSON 계약으로만 동기화(순환 불가피 (a), `OwnTypeContractTest`가 exact로 검증). `TradeLegSummary`(sharedkernel)와 별개 타입
  adapter/in/telegram/ ← TelegramWebhookController + TelegramBotService, TelegramApiClient(package-private) + TelegramUpdate
  adapter/in/web/     ← FcmController/TradeStreamController(매매 이벤트 SSE)/StatusStreamController(`GET /api/auth/status-stream`) + dto/FcmTokenRequest
  adapter/out/gateway/ ← TelegramAdapter(관리자봇), CompositeUserNotificationAdapter → TelegramUserNotificationAdapter + FcmAdapter(사용자 알림), TelegramBotInfoAdapter/TelegramHttpClient/TelegramConfig/TelegramProperties/FcmConfig + 이벤트 리스너 5종(UserDeletedNotifier/MarketAlertNotifier/StatsAlertNotifier/SchedulerNotifier/UserFcmCleanupListener). **매매 알림 6종(TradingAlertNotifier/CycleEndedNotifier/CycleLifecycleNotifier/OrderCancelFailureNotifier/TradingReportNotifier/PrivacyAlertNotifier)은 root notify가 아니라 `com.kista.trading.notify`(trading-core) 소유**
  adapter/out/sse/    ← SseEmitterRegistry(사용자별)/TradeSseEmitterRegistry(매매 이벤트) — `sse` 경로 세그먼트 유지 필수(`HexagonalArchitectureTest.sse_emitter_registry_must_not_be_used_in_application_layer`의 `com.kista..adapter.out.sse..` 와일드카드)
  adapter/out/persistence/ ← FcmDeviceTokenEntity + FcmDeviceTokenJpaRepository + FcmDeviceTokenPersistenceAdapter
  com.kista.trading.notify/ (trading-core 소유, NamedInterface 없는 얇은 게이트웨이) ← `application/port/output/`(TradingNotifyPort/TradingRealtimeNotificationPort/TradingUserNotificationPort — `User` 대신 trading-core 소유 경량 프로필 타입) + `adapter/out/gateway/`(TradingNotifyAdapter/TradingUserNotificationAdapter/RedisPushNotificationPublisher/RedisTradeEventPublisher/TelegramConfig/TelegramHttpClient/TelegramProperties + 매매 알림 6종) + `domain/model/TradeEventView`. 이 프로세스(`kista-trading`)가 `trading.event_publication`(사본) EPR 재발행(`REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=true`, docker-compose.yml)의 소유자 — 매매 알림 리스너가 여기 있어 재기동 시 자기 미완료 이벤트를 자기가 재발행한다. root `public.event_publication`은 `kista-scheduler`가 재발행한다(`kista-api`는 false 유지 — 둘 다 true면 이중 claim; scheduler는 `scanBasePackages`가 trading-core 패키지를 배제해 trading 리스너를 스캔하지 않는다)

com.kista.broker/    ← Spring Modulith 모듈(CLOSED) — KIS/Toss/Mock 증권사 연동. "domain"·"port"·"application" 3개 NamedInterface 공개, adapter/out은 비공개. **broker는 `Account`를 전혀 참조하지 않는다** — 자체 타입만 사용, `Account→BrokerAccountRef` 변환은 `Account.toBrokerRef()` 1곳이 전담
  domain/model/       ← Currency/DailyTransaction*/Execution/MarginItem/PresentBalanceResult 등 공통 값 객체 + broker 단독 소유 타입(쌍둥이 없음): PriceSnapshot(`BrokerPricePort` 반환 + `prevCloseOrNull`)/BrokerBalance(LiveBalancePort 반환 — trading이 자신의 AccountBalance 구성에 사용)/OrderInstruction·OrderResult·CancelInstruction(`BrokerOrderCorrectionPort.place()/cancel()`)/PlacedOrderView·PositionView·StrategyRefLite(MockSimulationDataPort 반환용 얇은 뷰)/BrokerAccountRef(id/appKey/secretKey/accountNo/brokerAccountCode + broker — Account 전체를 포트에 노출하지 않기 위한 자격증명 투영)/SellableQuantity/BrokerCredentialException·BrokerRateLimitException(KIS/Toss 인증 실패 시 KisAuthApi/TossAuthApi가 throw, 422/429 매핑)
  domain/model/kis/·toss/ ← KIS/Toss 전용 도메인 모델 — domain/model과 함께 "domain"으로 병합 공개
  application/port/output/ ← 브로커 Capability `*Port` 16개 — 공통 7개(KIS/Toss/Mock 모두 구현) + BrokerAdapterPort(라우팅 마커) + BrokerConnectionTestPort(*AuthApi가 구현 — 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅, verifyAccount→brokerAccountCode(KIS: null, Toss: accountSeq)) + BrokerTokenCachePort(KisTokenPersistenceAdapter 구현) + MockSimulationDataPort(MockBrokerAdapter 전용 — 데이터를 필요로 하는 broker가 정의하고 가진 trading이 `MockSimulationDataAdapter`로 구현하는 포트 역전) + Toss 전용 5개. 10개 포트는 `Account` 대신 `BrokerAccountRef`를 시그니처에 사용
  application/service/ ← BrokerAdapterRegistry(public, `require(BrokerAccountRef, Port.class)`/`find()`)/BrokerConnectionTesters(`of(Broker)`)/BrokerCallGuard — "application"
  adapter/in/web/     ← internal — CandleInternalController(`/api/internal/broker/candles/latest`, X-Internal-Token) — root market이 `CandlePort`/`TossCandle`을 참조하지 않도록 하는 내부 엔드포인트, own-type `CandleResponse`(컨트롤러 내부 record)로 매핑해 반환, `market.adapter.out.internal.CandleQueryHttpAdapter`가 소비
  adapter/out/kis/    ← KisHttpClient(공통 헤더 + executeWithRetry: 401 시 거절된 토큰을 조건부 무효화 후 최신 토큰으로 1회 재시도)/KisAuthApi/KisOrderApi/KisPriceApi/KisTradingApi/KisResponseParser/KisExchangeRegistry/KisConfig/KisTokenCoordinator/KisBrokerAdapter
  adapter/out/toss/   ← TossHttpClient/TossConfig/TossAuthApi/TossCandleApi/TossHoldingsApi/TossOrderApi/TossPriceApi/TossMarketApi/TossResponseParser/TossResult/TossMarketCalendarCache/TossStockInfoCache/UsdKrwRateCache
                        TossDistributedTokenCoordinator + TossRedisTokenStore(계좌·관리자 Redis canonical token; TTL owner lease+원자적 generation INCR; generation counter/canonical generation 비교 Lua fencing CAS; owner-safe unlock; SHA-256 최근 발급 fingerprint 2초 TTL) — 관리자 토큰은 Account가 없어 TokenCoordinator 범위 밖 별도 public 메서드(TossTokenStore)
                        TossBrokerAdapter(공통 7개 + Toss 전용 5개 Port 구현)
  adapter/out/mock/   ← MockBrokerAdapter — 증권사 API 호출 없이 DB(cycle_position/orders) 기반 잔고·체결 시뮬레이션. 시세는 `broker.adapter.out.marketdata.CommonMarketPriceFeed` 경유. `getLiveBalance()`의 usdDeposit은 계좌 내 전략 전체 합산값(TradingOrderBudgetAllocator가 대표 전략 1개로 계좌 전체 BUY 예산을 판단하는 계약에 맞춤 — 전략별 값을 반환하면 다른 전략 잔고로 오판정)
  adapter/out/internal/ ← TokenCoordinator(계좌 토큰 obtain/recover 공통 계약 — adapter 내부 인터페이스, 폴리모픽 주입 없음)/DoubleCheckedTokenCache(KisTokenCoordinator 전용 — 1차 조회 → miss 시 계좌별 락 → 2차 double-check → 신규 발급; `BrokerTokenCachePort.saveToken`/`invalidateToken`은 REQUIRES_NEW로 락 해제 전 독립 커밋)/PrevCloseCache(현재 사용처 TossPriceApi뿐)
  adapter/out/persistence/ ← KisTokenEntity + KisTokenJpaRepository + KisTokenPersistenceAdapter

com.kista.trading/   ← Spring Modulith 모듈(CLOSED) — 주문/사이클 실행 이력/주문생성 전략 + 계좌별 영속 전략 설정(Strategy) 애그리게이트, `stats/` 서브패키지(계좌·Toss 통계, 백테스트, 포트폴리오, summary/equity-curve/cycles)는 기존 NamedInterface에 병합. "domain"·"usecase"·"port"·"schedule"·"stats" 5개 NamedInterface 공개 — application.service·adapter.out.*은 비공개. `application/event/`는 없다(이벤트는 전부 sharedkernel 소유)
  domain/model/       ← 주문(`Order` — `matching.PlannedOrder`·sharedkernel enum 참조, `fromPlanned`/`toPlanned`로 커널과 상호 변환) + 사이클 실행 이력(BatchContext/BuyCompetitionPreview/CancelResult/CycleHistoryPage/CyclePosition/CyclePositionHistoryEntry/CyclePositionInfiniteDetail/DstInfo/ManualTradingException/NextOrdersPreview/OrderCancelException/ReconfigureVrCommand/SellSufficiencyPreview/StrategyCycle/StrategyCycleVrDetail 등) + 버전별 실행 파라미터(StrategyVersion/StrategyInfiniteDetail) + VrSummary + 전략 설정 애그리게이트(Strategy/StrategyDetail/RegisterStrategyCommand/UpdateStrategyCommand/StrategySeedPreview/StrategySummary) + admin이 own-type으로 복제하는 커맨드/결과(ReorderCommand/ReorderResult/ManualTradeCorrectionCommand/ManualTradeCorrectionResult). position 값객체·`AccountBalance`·`PlannedOrder`는 `com.kista.matching` 소유 — "domain"으로 병합 공개
  domain/strategy/    ← 전략 *등록 정책* 리졸버만(StrategyCreationResolver(s)/InfiniteCreationResolver/PrivacyCreationResolver/VrCreationResolver/StrategyCreationRequest). `StrategyCreationRequest`(원시값 5개)는 19필드 `RegisterStrategyCommand`를 넘기지 않기 위한 ISP 좁히기 — 정당한 narrowing
  application/usecase/ ← TradingExecutionUseCase/VrReconfigureUseCase/VrStrategyDetailUseCase/StrategyUseCase(조회/등록/수정/삭제/일시정지/재개 + 시드 미리보기·사이클 이력·주문 내역) — "usecase". **`StrategyUseCase`는 command/query 미분리 유지** — 유일 소비자(`TradingCycleController`)가 전체 surface를 쓰고 `StrategyService`가 `toDetail`/`assemble`을 공유한다. register()와 조회 3종(시드 미리보기·사이클 이력·주문 내역)은 `StrategyCreationService`/`StrategyHistoryQueryService`로 내부 분리돼 있다. 분리 트리거: `toDetail`/`assemble` 공유 범위에 query만 필요한 2번째 소비자(admin read 경로 등) 등장 시
  application/port/output/ ← OrderPort/CyclePositionPort/CyclePositionInfiniteDetailPort/StrategyCyclePort/StrategyCycleVrPort/TradingErrorReportPort/StrategyVersionPort/StrategyInfiniteDetailPort/StrategyVrDetailPort/StrategyPort + HeartbeatPort(스케쥴러 dead-man's switch, 외부 소비자 0) + TradingUserProfilePort(trading 정의·자체 구현 — `UserNotifyProfilePersistenceAdapter`가 `trading.user_notify_profile` 복제본만 읽는다) — "port"
  application/service/ ← internal — TradingExecutionFacade(preview/executeManually/cancelOrder/cancelByCycle/execute/executeBatch 단일 진입점), TradingService(배치·단건 실행 최상위 오케스트레이션 — 대기·리포트 순서만)
                         TradingBatchGuard(전략별 단계 실행 격리 — runSafely/notifyErrorSafely/notifyBatchInterrupted) + TradingCandidatePlanner(후보수집+계좌별 예산 배정 — executeBatch/placeOpenOrders 공용)
                         PreviewDepositCache — TradingBuyCompetitionSimulator 전용 계좌 단위 라이브 usdDeposit 3초 TTL 캐시 + 계좌별 락(계좌당 전략 N개 preview 병렬 호출을 실제 조회 1회로). 실주문 경로(ManualTradingService/TradingOrderBudgetAllocator)는 미사용 — 항상 최신값
                         TradingOrderBudgetAllocator — 계좌별 slot-aware BUY/SELL 독립 예산 배정 (규칙 → workflow.md "스케쥴러 주문 예산 배정")
                         live 잔고·판매가능수량은 `BrokerAdapterRegistry.require(account, LiveBalancePort/SellableQuantityPort.class)` 직접 라우팅 — 별도 Router 없음
                         CyclePositionPersistor: 포지션 스냅샷 저장 + 사이클 종료·rotation + `VrCycleRolloverService.rollIfDue()` 호출 (VR 예외 → "VR 전략 패턴")
                         support/CycleCloser·SelectionChain(admin에서 이관된 사이클 종료·선정 체인)/ReorderService/StrategyService(StrategyUseCase 구현)/AccountCascadeListener(AccountDeletedEvent 구독)/StrategyUserCascadeListener(UserDeletedEvent 구독)
  adapter/in/schedule/ ← TradingOpenScheduler/TradingCloseScheduler/BatchContextFactory(전략 목록 → BatchContext, 조회 실패 시 skip + notifyError). root는 이 빈을 주입할 수 없어 수동 트리거는 `TradingSchedulerInternalController`가 대신 실행 — "schedule"
  adapter/in/web/      ← internal(TradingSchedulerInternalController·내부 컨트롤러 예외) — OrderCancelController + TradingCycleController(사이클 CRUD·pause/resume·수동 실행·VR 재설정·전략 이력/주문/시드 미리보기) + dto/ + 내부 API: TradingSchedulerInternalController(`POST /api/internal/trading/scheduler/{open,close}`, 202, 가상 스레드 백그라운드 실행)/TradingInternalQueryController(orders/strategies/strategy-summaries 등 조회)/TradingInternalCommandController(`/reorder`·`/trade-corrections`·`/reorder-timing-availability`)/StrategyStatusInternalController/ActiveStrategyCountInternalController(`GET /api/internal/trading/active-strategy-count`). `CycleHistoryPageResponse`/`CycleHistoryResponse`는 stats `dto` 사본과 별개 응답 계약(b)
                        TradingExceptionHandler(`@RestControllerAdvice(basePackages={trading/account/privacy/trading.stats/broker/marketcalendar/matching 7개 adapter.in.web}, @Order(HIGHEST_PRECEDENCE))`) — trading-core 네이티브 컨트롤러 전용 예외 6종(`BrokerCredentialException`/`BrokerRateLimitException`/`ManualTradingException`/`OrderCancelException`/`PrivacyTradeConflictException`/`Account.DuplicateAccountException`) + 범용 프레임워크 예외(`GENERIC_MAPPINGS`/`handleGeneric`, `isClientDisconnect` 가드) 매핑. root `GlobalExceptionHandler`와 같은 JVM에 공존하지 않아 범용 매핑도 자체 보유
  adapter/out/         ← internal — MockSimulationDataAdapter(broker `MockSimulationDataPort` 구현)
  adapter/out/heartbeat/ ← internal — HeartbeatAdapter/HeartbeatConfig/HeartbeatProperties(healthchecks.io 핑)
  adapter/out/persistence/ ← internal — Order/CyclePosition/CyclePositionInfinite/StrategyCycle/StrategyCycleVr/StrategyVersion/StrategyInfiniteDetail/StrategyVrDetail(StrategyVrVersion*)/Strategy Entity + *JpaRepository + *PersistenceAdapter, PersistenceSupport
  adapter/out/persistence/ (user_notify_profile 복제본) ← UserNotifyProfileEntity + UserNotifyProfileJpaRepository + UserNotifyProfilePersistenceAdapter(`TradingUserProfilePort` 구현) + UserNotifyProfileSyncListener — trading-core 소유 사용자 알림·잔고검증·활성여부 읽기 전용 복제본(`trading` 스키마, 원본은 user 소유 `public.users`/`user_settings`/`user_notification_prefs`). root·trading-core는 별도 프로세스라 Modulith EPR로 이벤트가 전달되지 않는다 — root `UserEventStreamPublisher`(`com.kista.user.adapter.out.redis`)가 `UserNotifyProfileChangedEvent`·`UserDeletedEvent`를 `stream:user.notify-profile.changed`/`stream:user.deleted`(Redis Stream, 키·컨슈머그룹명은 `platform.redis.RedisStreamConfig`)로 XADD하고, trading-core `UserEventStreamConsumerConfig`(컨슈머 그룹 `trading-core`)가 구독해 `UserEventStreamBridge`가 로컬 `publishEvent()`로 재발행한 뒤 이 리스너가 `@TransactionalEventListener`로 받는다(root 쪽 로컬 리스너 — finance cascade·UserDeletedNotifier·UserFcmCleanupListener — 는 병행 유지). 발행 측 `fallbackExecution=true` 필수(`UserService.login()` ADMIN 승격이 `@Transactional(NOT_SUPPORTED)` 구간이라 없으면 통째로 버려짐). pending 복구는 `UserEventStreamRecoveryScheduler`(5분 XAUTOCLAIM). `is_active`는 `UserStatus.ACTIVE` 전용 컬럼으로 `findAllActive()`(장 개장·마감 브로드캐스트 대상)가 이것만으로 판별 — `TradingUserProfile` record에는 넣지 않는다. 복제본 DDL은 trading baseline(`db/migration-trading`)이 소유하고, 새 DB에 복제본을 처음 채울 땐 기존 사용자 백필이 필수다(복제본이 비면 `findAllByUserIds()`가 빈 맵을 돌려 전 계좌를 건너뛰어 매매 전면 중단 — 필수 조건 → constraints.md)
  stats/domain/model/  ← InvestmentPoint/BenchmarkGranularity(root `com.kista.stats.domain.model`에 own-type 복제본) + StatsSummary/EquityCurve/EquityPoint/CyclePerformancePage/CyclePerformance/StrategyTypeStats(summary/equity-curve/cycles 타입). stats/application과 함께 "stats" NamedInterface
  stats/application/   ← MonthlyReturnCalculator(public — CyclePosition/StrategyCycle로 시간가중수익률·투자지수 계산하는 순수 계산기, 로직 SSOT는 코드)
  stats/application/usecase/ ← AccountStatisticsUseCase/PortfolioUseCase(notify `TelegramBotService`가 `/portfolio`에 소비)/TossStatisticsUseCase/BacktestUseCase/TradingStatsUseCase — trading의 usecase와 병합 공개
  stats/application/service/ ← internal — AccountStatisticsService/PortfolioService/BacktestService/TossStatisticsService/BrokerStatisticsRouter/TradingStatsService + TradingStatsResultCache(summary/equity-curve 5분 TTL — root `StatsResultCache`(벤치마크 10분)와 별개 인스턴스)
  stats/domain/backtest/ ← internal — BacktestEngine/FillSimulator. `com.kista.matching`을 직접 `new`해 소비
  stats/domain/model/backtest/ ← BacktestCommand/BacktestPoint/BacktestResult/BacktestSummary
  stats/adapter/in/web/ ← internal — DashboardController/StatisticsController(KIS live)/TossStatisticsController(Toss live)/BacktestController/TradingStatsController(summary/equity-curve/cycles)/TradingStatsInternalController(`/api/internal/trading/stats/investment-points`, root `InvestmentPointsHttpAdapter`가 소비)/PortfolioQueryInternalController + dto/(TossCandleResponse는 market 사본과 별개 응답 계약(b))

com.kista.market/    ← Spring Modulith 모듈(CLOSED, root 전용) — 공포탐욕지수(CNN/Crypto) 애그리게이트. 휴장일 캘린더는 `marketcalendar`로 분리. "domain"·"port"·"event" 3개 NamedInterface 공개, usecase·service·adapter는 internal
  domain/model/       ← FearGreedRating/FearGreedSnapshot + MarketSession(DIRECT/BLOCKED — `marketcalendar.MarketSessionSnapshot.MarketSession` own-type)/TossDailyCandle(`broker.TossCandle` 일봉 own-type) — "domain". 순환 방지용 복제, `OwnTypeContractTest`가 검증
  application/port/output/ ← CnnFearGreedPort/CryptoFearGreedPort/FearGreedSnapshotPort + MarketCalendarQueryPort(marketcalendar 내부API, `SessionView(MarketSession, isDst)`)/CandleQueryPort(broker 내부API, 일봉만) — "port"
  application/usecase/ ← FetchFearGreedUseCase/GetFearGreedUseCase/MarketUseCase — internal(외부 소비자 없음). `MarketHolidayService`가 `MarketUseCase` 구현 — marketcalendar/broker 양쪽에 HTTP 위임하며 그쪽 타입을 직접 참조하지 않는다
  application/event/  ← FearGreedFetchFailedEvent — notify `MarketAlertNotifier`가 구독. "event"
  application/service/ ← internal — FearGreedQueryService/FearGreedService/MarketHolidayService
  adapter/in/web/     ← internal — FearGreedController/MarketHolidayController + dto/(FearGreedResponse/MarketSessionResponse/TossCandleResponse)
  adapter/in/schedule/ ← FearGreedScheduler
  adapter/out/feargreed/ ← CnnFearGreedAdapter/CryptoFearGreedAdapter/FearGreedConfig
  adapter/out/internal/ ← MarketCalendarQueryHttpAdapter(`/api/internal/marketcalendar/**`)/CandleQueryHttpAdapter(`/api/internal/broker/candles/latest`, interval="1d" 고정)
  adapter/out/persistence/feargreed/ ← FearGreedSnapshotEntity + JpaRepository + PersistenceAdapter

com.kista.marketcalendar/ ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 미국 시장 휴장일 캘린더. "domain"·"port" 2개 NamedInterface, service·adapter internal
  domain/model/       ← MarketSessionSnapshot
  application/port/output/ ← MarketCalendarPort/MarketCalendarRefreshPort/MarketHolidayStorePort
  adapter/in/web/     ← internal — MarketCalendarInternalController(`/api/internal/marketcalendar/{holidays,is-open,session}`) — `session` 라우트는 own-type `SessionResponse(String session, boolean isDst)`
  adapter/in/schedule/ ← MarketCalendarRefreshScheduler
    - **2-role 이후 캘린더 부트스트랩 staleness**: 초기 적재(`ApplicationReadyEvent`)는 `@ConditionalOnProperty(scheduler.enabled)`로 게이팅돼 `kista-scheduler` 재기동 시에만 실행된다(`kista-api` 재기동마다가 아님). self-heal 창이 수시간→수주. 월간(1일)·연간(1월 1일) 갱신 크론이 있어 무해, 캘린더 이상 시 `kista-scheduler` 재기동으로 즉시 재적재
  adapter/out/alpaca/  ← AlpacaCalendarAdapter/AlpacaConfig/AlpacaProperties — stats판과 빈 이름 충돌 방지를 위해 marketcalendar판만 `marketAlpacaConfig`/`marketAlpacaRestClient`로 개명
  adapter/out/persistence/ ← UsMarketHolidayEntity + JpaRepository + MarketCalendarPersistenceAdapter

com.kista.privacy/   ← Spring Modulith 모듈(CLOSED) — FIDA 기준 매매표(PRIVACY 전략의 전역 SSOT 매매 계획). "domain"·"port"·"usecase" 3개 NamedInterface, service·adapter internal. PRIVACY *전략 실행* 로직은 matching/trading 소유 — 이 모듈은 계획 데이터만
  domain/model/       ← FidaOrderCommand/FidaPlannedOrder/PrivacyCurrentBase/PrivacyDates/PrivacyTradeBase/PrivacyTradeBaseView/PrivacyTradeConflictException/PrivacyTradeSaveResult/PrivacyTradeValidationReport 등. `PrivacyDates.releaseDateFor()/tradeDateOf()`는 FIDA 발행일↔거래일 업무 규칙 헬퍼(시간대 변환 아님)
  application/port/output/ ← PrivacyTradePort
  application/usecase/ ← PrivacyUseCase(FidaOrderController)/PrivacyTradeValidationUseCase(TradingOpenScheduler)
  application/service/ ← internal — PrivacyService(notify 직접 호출 대신 sharedkernel `PrivacyAlertRaisedEvent` 발행)/PrivacyTradeValidationService
  adapter/in/web/     ← internal — FidaOrderController(`POST /api/internal/fida-orders`)/PrivacyInternalQueryController(`GET /api/internal/privacy/trade-bases`)/PrivacyBaseInternalController(`GET|PATCH .../trade-bases/{baseId}`, `PATCH .../orders/{orderId}` — 관리자 수동 보정) + dto/FidaOrderResponse
  adapter/out/persistence/ ← PrivacyTradeBaseEntity + PrivacyTradeBaseOrderEntity + JpaRepository + PrivacyTradePersistenceAdapter

com.kista.stats/    ← Spring Modulith 모듈(CLOSED, root) — 주택/ETF 벤치마크 비교·KB Land/Alpaca 지수 시세 수집만 담당(계좌·Toss 통계·백테스트·포트폴리오·summary/equity-curve/cycles는 `com.kista.trading.stats` 소유). "domain"·"usecase"·"port"·"event"·"schedule" 5개 NamedInterface, service·adapter.in.web·adapter.out.*은 internal. root `StatsController`는 housing/ETF 벤치마크 비교 6개 라우트만 소유
  domain/model/       ← 벤치마크 11타입(BenchmarkAssetType/BenchmarkScope/CurrentExchangeRate/EtfBenchmarkSymbol/HousingBenchmarkComparison/HousingBenchmarkPoint/HousingBenchmarkPrice/HousingBenchmarkRegion/HousingPriceIndex/IndexPrice/PerformanceComparisonSummary) + own-type InvestmentPoint/BenchmarkGranularity/StrategyRef(`Strategy`의 id/type/ticker 3필드 narrowing — 과거 trading own-type `StrategyRef`와 이름만 같은 별개 타입) — "domain"
  application/usecase/ ← UserStatsUseCase(벤치마크 비교 6개 메서드)/FetchHousingBenchmarkUseCase/FetchHousingPriceIndexUseCase/SyncMarketIndexPricesUseCase
  application/port/output/ ← HousingBenchmarkFeedPort/HousingBenchmarkPricePort/HousingPriceIndexPort/IndexPriceFeedPort/IndexPricePort/InvestmentPointsPort(trading-core 내부 API 호출 — `Result` 시그니처가 own-type). `HistoricalCandlePort`는 sharedkernel 소유, 구현체 `AlpacaIndexPriceAdapter`만 이 모듈에 남음
  application/event/  ← StatsAlertRaisedEvent(String message) — notify `StatsAlertNotifier`가 구독. "event"
  application/service/ ← internal — StatsService(벤치마크 비교 6개)/StatsResultCache(벤치마크 10분 인메모리 TTL 캐시, 단일 인스턴스 전제 — 다중 인스턴스 시 인스턴스별 캐시가 최대 TTL만큼 상이 가능)/HousingBenchmarkComparisonBuilder(Spring·포트 비의존 순수 계산 — 정규화 비교 조립, ETF 비교에도 재사용. TWR 계산은 trading 소유 `MonthlyReturnCalculator`가 담당하고 결과 InvestmentPoint만 전달받음)/HousingBenchmarkService/HousingPriceIndexService/MarketIndexPriceSyncService(수집 실패 시 `StatsAlertRaisedEvent` 발행)
                         getHousingBenchmarkComparison: currentExchangeRate는 요청마다 실시간 조회하는 정보성 필드일 뿐 수익률·공통월·summary에는 미반영(조회 실패 시 null, 200 정상) — 투자(USD)·벤치마크(HOUSING=KRW/ETF=USD) 현지통화 그대로 비교, 환율 변환 없음
  adapter/in/web/     ← internal — StatsController + openapi/HousingBenchmarkOpenApiCustomizer + dto/ 5종
  adapter/in/schedule/ ← KbLandHousingBenchmarkScheduler/KbLandPriceIndexScheduler/MarketIndexPriceSyncScheduler — `com.kista.web.AdminSchedulerController`가 KbLand 2개를 수동 트리거용으로 주입(공개 "schedule"). MarketIndexPriceSyncScheduler는 비거래일에도 Alpaca 빈 배열 반환으로 무해한 no-op이라 요일 조건 없음
  adapter/out/alpaca/  ← AlpacaIndexPriceAdapter/AlpacaConfig/AlpacaProperties (빈 이름 `alpacaRestClient`)
  adapter/out/kbland/  ← KbLandHousingBenchmarkAdapter/KbLandConfig/KbLandProperties (아파트 5분위 매매평균가격(월간) + 주간 매매가격지수)
  adapter/out/internal/ ← InvestmentPointsHttpAdapter(trading-core 내부 API)
  adapter/out/persistence/housingbenchmark/ ← HousingBenchmarkPriceEntity/HousingPriceIndexEntity + JpaRepository + PersistenceAdapter 6개
  adapter/out/persistence/marketindex/ ← MarketIndexPriceEntity + JpaRepository + PersistenceAdapter 3개

com.kista.admin/    ← Spring Modulith 모듈(CLOSED) — 관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정. "domain"·"usecase"·"port" 3개 NamedInterface, service·adapter internal. event/schedule NamedInterface 없음. `adapter/out/aop/ErrorLogAspect`(`NotifyPort.notifyError` 인터셉트 → `AppErrorLogPort` 저장, notify는 포인트컷 문자열이라 컴파일 의존 없음)도 internal
  domain/model/       ← 관리자 read-model/own-type(AdminAccountView/AdminAnomalies/AdminBrokerCredentialException/AdminBrokerRateLimitException/AdminManualTradeCorrectionCommand/AdminOrderView/AdminReorderCommand/AdminReorderResult/AdminReorderTimingAvailability/AdminStats/AdminStrategySummary/AdminStrategyView/AdminTradeCorrectionResult/AdminPrivacy* 계열/AppErrorLog/AuditLog) + 런타임 설정 3(RuntimeSettings/BenchmarkSettings/BenchmarkFieldSettings) — "domain". `AdminAccountView`는 id/userId/accountNo(마스킹 전)/broker/createdAt 5필드로 의도적으로 좁힌 read model(appKey/secretKey/nickname/brokerAccountCode 제외 — 내부 API 응답에 평문 비밀값이 실리는 것을 막기 위함). own-type 목록·게이트 → constraints.md, shape 드리프트는 `OwnTypeContractTest`
  application/usecase/ ← AdminQueryUseCase/AdminReorderUseCase/AdminStrategyUseCase/AdminTradeCorrectionUseCase/AdminUserUseCase/AdminSettingsUseCase/RuntimeSettingsUseCase — "usecase"
  application/port/output/ ← AccountQueryPort/AppErrorLogPort/AuditLogPort/PrivacyQueryPort/RuntimeSettingsPort/TradingCommandPort/TradingQueryPort/TradingSchedulerCommandPort(trigger only 2메서드) — "port"
  application/service/ ← internal — AdminService/AdminQueryService/AdminReorderService/AdminStrategyService/AdminTradeCorrectionService/AdminPrivacyTradeService/RuntimeSettingsService. AdminQueryService는 계좌 목록/단건을 accountQueryPort(내부 API)로 위임하고 `accountPort`(trading-core AccountPort 직접 의존)는 getStats()의 countAll() 집계에만 잔존
  adapter/in/web/     ← internal(AdminTradingSchedulerController 예외) — 컨트롤러 10개(AdminAccount/AdminDashboard/AdminObservability/AdminPing/AdminPrivacyTrade/AdminSettings/AdminTrade/AdminUser/RuntimeConfig/ClientErrorLog) + AdminUserViews + dto/ 21종. `AdminTradingSchedulerController`(`/api/admin/scheduler/{open,close}`, 202)는 `scheduler.enabled` 게이팅 없이 kista-api role에서도 상시 노출 — `com.kista.web.AdminSchedulerController`(`/kbland-*`)와 prefix 공유하되 하위 경로가 겹치지 않는다. KbLand 트리거는 role-게이팅이라 admin에 두면 "admin 컨트롤러는 항상 존재" 전제가 깨져 web에 잔류
  adapter/out/internal/ ← AccountQueryHttpAdapter/PrivacyQueryHttpAdapter/TradingCommandHttpAdapter/TradingQueryHttpAdapter/TradingSchedulerCommandHttpAdapter — 위 5개 포트를 내부 API(RestClient)로 구현. Scheduler 어댑터는 짧은 호출이라 공용 `internalApiRestClient`를 쓰고 reorder류는 `internalApiWriteRestClient` 사용. `PrivacyQueryHttpAdapter.createBase`는 409를 `onStatus`로 되돌려 `AdminPrivacyTradeConflictException`으로 변환(없으면 catch-all 500)
  adapter/out/persistence/audit/    ← AuditLogEntity/AppErrorLogEntity + JpaRepository + PersistenceAdapter 6파일
  adapter/out/persistence/settings/ ← RuntimeSettingsEntity + JpaRepository + PersistenceAdapter 3파일
  ── `@Table(schema="public")`: AuditLogEntity/AppErrorLogEntity/RuntimeSettingsEntity는 플랫폼 공통 테이블이라 public 명시 유지

com.kista.user/     ← Spring Modulith 모듈(CLOSED) — 가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth. "domain"(domain.model+domain.auth 병합)·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter·config internal
  domain/model/       ← User/UserSettings/AdminUserView/NotificationChannel. `User.DEFAULT_CHANNEL = NotificationChannel.NONE`(domain 상수) — 서비스/컨트롤러 하드코딩 금지
  domain/auth/        ← RefreshToken/TokenRefreshResult/TokenConstants/InvalidRefreshTokenException
  application/usecase/ ← BlacklistUseCase/GetUserSettingsQuery/TokenUseCase/UpdateBalanceCheckUseCase/UpdateNotificationPrefUseCase/UpdateStrategySuggestionsUseCase/UserProfileUseCase/UserUseCase
  application/port/output/ ← AdminUserViewPort/ApprovalPolicyPort/BlacklistPort/KakaoOAuthPort/RefreshTokenPort/TelegramBotInfoPort/UserPort/UserSettingsPort/ActiveStrategyCountPort. `ApprovalPolicyPort`(가입 승인 필요 여부 FOR UPDATE 락 조회)는 user가 정의하고 admin이 구현하는 포트 역전
  application/event/  ← NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/UserReappliedEvent — notify 등이 구독(`UserDeletedEvent`는 sharedkernel 소유)
  application/service/ ← internal — BlacklistService/TokenService/UserCascadeDeleter/UserProfileService/UserService/UserSettingsService. `UserCascadeDeleter`(`UserService.deleteMe`/`AdminService.deleteUser` 공통 진입점)는 trading·finance·account·전략 설정 cascade를 전부 `UserDeletedEvent` 발행으로 처리(직접 포트 호출 0, 각 모듈이 자체 리스너로 정리, EPR 재시도 보장). 계좌 cascade는 `AccountUserCascadeListener`(AFTER_COMMIT) 비동기라 탈퇴 HTTP 응답 시점엔 계좌 소프트 삭제가 미완료일 수 있다(최종적 일관성). `UserNotifyProfilePublisher`(package-private)가 상태·설정 변경 시 `UserNotifyProfileChangedEvent`를 발행하는 SSOT — `UserService` 상태 전이 5곳과 `UserSettingsService` 알림/잔고검증 변경 2곳이 호출
  adapter/in/web/     ← internal — AuthController/DevAuthController(local 전용)/SettingsController + dto/
  adapter/in/web/security/ ← internal — JwtAuthFilter/InternalTokenAuthFilter/SecurityConfig/JwtDecoderConfig/JwtIssuerService/OpenApiConfig/RefreshTokenCookieHelper
  adapter/in/schedule/ ← RefreshTokenCleanupScheduler(platform `SchedulerJobRunner` 재사용)
  adapter/out/kakao/  ← KakaoOAuthAdapter/KakaoConfig/KakaoProperties
  adapter/out/redis/  ← RedisBlacklistAdapter + UserEventStreamPublisher(trading-core 복제본 동기화용 Redis Stream 발행)
  adapter/out/persistence/user/    ← UserEntity + UserJpaRepository + UserPersistenceAdapter, AdminUserViewAdapter
  adapter/out/persistence/auth/    ← RefreshTokenEntity + JpaRepository + PersistenceAdapter
  adapter/out/persistence/settings/ ← UserSettingsJpaEntity/UserNotificationPrefJpaEntity/UserNotificationPrefId + JpaRepository + UserSettingsPersistenceAdapter
  config/             ← AdminBootstrapProperties(`admin.kakao-ids` 바인딩)/AdminConfig

com.kista.account/   ← Spring Modulith 모듈(CLOSED, `:trading-core`) — 계좌 자격증명·브로커 연결. "domain"·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter internal
  domain/model/       ← Account/RegisterAccountCommand/UpdateAccountCommand. `Account.Broker` nested enum 없음 — `sharedkernel.Broker` 참조. `SellableQuantity`/브로커 자격증명 예외는 broker 소유가 SSOT
  application/usecase/ ← AccountUseCase(조회/등록/수정/삭제/증권사 연결 테스트)
  application/port/output/ ← AccountPort. `BrokerEnabledPort`는 sharedkernel 소유(admin `RuntimeSettingsService`가 구현, account는 소비만)
  application/event/  ← AccountDeletedEvent(UUID accountId) — `AccountService.delete()`가 `StrategyPort.deleteByAccountId()`를 직접 호출하지 않고 발행, `trading.AccountCascadeListener`가 AFTER_COMMIT 구독(EPR 추적)
  application/service/ ← internal — AccountService(등록/수정/삭제/연결테스트)/AccountUserCascadeListener(sharedkernel `UserDeletedEvent` 구독 → `AccountPort.deleteByUserId` 소프트 삭제; AFTER_COMMIT + `fallbackExecution=true` + `@Transactional(REQUIRES_NEW)`)
  adapter/in/web/     ← internal — AccountController + dto/(AccountRequest/AccountResponse/TestConnectionRequest) + AccountInternalController(`/api/internal/accounts` — admin `AccountQueryHttpAdapter`가 소비하는 읽기 전용). **`Account`를 그대로 반환하지 않는다** — appKey/secretKey(복호화된 자격증명)가 내부망 응답에 실리지 않도록 `AdminAccountView`와 byte-identical한 5필드 own-type `AccountInternalResponse`(컨트롤러 내부 record)로 매핑
  adapter/out/persistence/ ← AccountEntity + AccountJpaRepository + AccountPersistenceAdapter

com.kista.web/       ← Spring Modulith 앱셸 모듈. `@ApplicationModule(Type.CLOSED)` — NamedInterface **0개**. 아무 모듈도 web을 참조하지 않는 sink라 순환에 참여할 수 없다(`HexagonalArchitectureTest.web_must_stay_pure_inbound_sink`가 `web → ..application.service../..adapter.out..` 의존 금지로 강제). **신규 파일이 단일 모듈만 소비하거나 모듈 의존이 없으면 web에 두지 말고 소유 모듈로 보낼 것** — 여기엔 진짜 다중 모듈 fan-out과 순환 회피용 포트 구현체만
  trading/             ← ActiveStrategyCountAdapter(`user.ActiveStrategyCountPort` 구현) — trading-core 내부 API(`GET /api/internal/trading/active-strategy-count`)를 호출하는 순수 HTTP 어댑터, 타입 의존 없음(위치만 과거 잔재)
  GlobalExceptionHandler ← root 소유 컨트롤러(admin/user/finance/stats/market/web) 전용 범용 예외(`SecurityException`→403/`NoSuchElementException`→404/`IllegalArgumentException`→400 등) 매핑(`@RestControllerAdvice`). trading-core 예외 6종은 `TradingExceptionHandler`가 전담. `KisApiException`/`TossApiException`은 root/admin 소유 `AppErrorLogPort` 의존 때문에 root 잔류
  MetaController        ← `GET /api/meta` — enum 메타(label/description) 단일 번들. finance(`domain.model`)+matching(`"kernel"` — 내부 API 경유)+sharedkernel fan-out이라 단일 모듈 이관 불가, UI enum 리터럴 하드코딩 방지
  AdminSchedulerController ← stats "schedule" 소비 — KbLand 스케쥴러 수동 트리거(`/api/admin/scheduler/kbland-*`). 클래스 레벨 `@ConditionalOnProperty(scheduler.enabled)`라 kista-api role에선 빈 미등록(오라우팅 시 404)
  dto/                 ← MetaBundle·EnumMeta·StrategyTypeMeta·TickerMeta·StrategyCapability(matching 내부 API 응답 own-type)
```

### Spring Modulith 모듈 구성
15개 모듈(finance/notify/broker/trading/matching/market/marketcalendar/privacy/stats/admin/user/account/sharedkernel/platform/web — `:api`·`:trading-core`·`:shared`에 나뉘어 위치) 전부 `@ApplicationModule`로 선언돼 있고, 모듈 간 경계는 `ApplicationModules.verify()`(`ModulithArchitectureTest`)가, 모듈 내부 레이어 방향은 `HexagonalArchitectureTest`가 각각 검증한다. 각 모듈의 NamedInterface와 내부 패키지는 위 트리에 기록돼 있다 — 신규 코드 추가 시 해당 모듈 절에서 위치·공개 범위를 확인할 것.

모듈 간 참조는 원칙적으로 상대 모듈이 공개한 NamedInterface(도메인 타입 또는 own-type projection)만 거쳐야 하며, 서로 참조가 얽히면 포트 역전(own-type 정의 + 상대가 구현) 또는 이벤트 발행(`@TransactionalEventListener`, EPR 재시도) 패턴을 쓴다. 다른 프로세스(trading-core ↔ root) 사이는 EPR이 전달되지 않으므로 내부 HTTP API 또는 Redis(Pub/Sub·Stream)를 쓴다.

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
- `toEntity()` 내에서 `setCreatedAt()`/`setUpdatedAt()` 명시적 호출 금지 — 호출 자체가 dead code이며 `@Setter(PACKAGE)` 범위 제약과도 충돌

### 텔레그램 알림 (notifyTradingReport)
- 계좌별 텔레그램 설정 제거됨 — `User.telegramBotToken/chatId` 사용자봇만 사용 → 미설정 시 생략 (`log.warn`)
- `UserPersistenceAdapter`: telegramBotToken AES-256 암호화/복호화 적용

### BrokerAdapter Registry 패턴
- `com.kista.broker.application.service.BrokerAdapterRegistry`: `Map<Broker, BrokerAdapterPort>`(sharedkernel.Broker) — Spring이 `List<BrokerAdapterPort>` 자동 수집
- `registry.require(account, XxxPort.class)` — 브로커가 Capability 미지원 시 `IllegalArgumentException` → GlobalExceptionHandler 400
- `registry.find(account, XxxPort.class)` — `Optional<T>`, 미지원 시 `Optional.empty()`
- 신규 브로커 추가: `BrokerAdapterPort` 구현체 1개만 추가 — Router/switch 수정 불필요
- `Account.isToss()` 삭제됨 — 브로커 분기 필요 시 `account.broker() == Broker.TOSS` 직접 비교
- `BrokerAdapterRegistry`는 `public` — 여러 모듈에서 "application" NamedInterface로 소비하는 예외적 공개 접근자

### TDA 전략 패턴 (InfiniteStrategy)
- `TradingService.execute()` 잔고 조회: KIS API 아님 → `findRecentByCycleId(cycleId, 1)` 최신 이력에서 `AccountBalance` 구성 (이력 없으면 `IllegalStateException`)
- PRIVACY execute() null guard: `snapshot=null` → `saveAndNotify`에서 `snapshot != null` 조건 가드 유지 필수
- **`preview()` today 오프셋**: 날짜 경계는 KST 04:30 (`DstInfo.SCHEDULER_RUN_TIME`) → `TradingPreviewService`의 `today = DstInfo.nextTradeDate()` SSOT 사용 (미적용 시 PRIVACY `findTodayTrade()` 날짜 1일 어긋남)
- **`INSUFFICIENT_BALANCE` skip 시 position 포함**: `shouldSkip(price)` true여도 `InfinitePosition`을 Result에 포함 — 프론트에서 단위금액·현재가·부족 금액 표시 목적

### CycleOrderStrategy Capability 패턴
- SSOT 위치: `com.kista.matching.domain.strategy`. `CycleOrderStrategy` 인터페이스: 전략 타입별 동작(basePrice 소스, 전일종가 필요 여부, 분할수, 리버스모드 지원, 청산 시 사이클 종료 여부, 최소시드, 예산배정 우선순위, compute skip, 롤오버 판정 필요 여부, BUY 가격 캡 보정 방식 등)을 캡슐화하는 다형성 계층 — 메서드별 상세는 코드가 SSOT
  - `canSkipOrderComputation()`은 기본 false이며 INFINITE만 complete concrete leg 또는 direction-aware legacy UNKNOWN 점유를 보수적으로 판단한다
  - `priceCapMode()`: VR도 생성 시점 cap을 적용하지 않고 접수 전 `BuyOrderPriceCapper`가 `VrStrategy.buildCappedBuyOrders()`로 보정한다
- `CycleOrderStrategies`: `Map<StrategyType, CycleOrderStrategy>` 라우터 — `of(type)`으로 구현체 조회
- **프론트 capability 소비**: `GET /api/meta`의 `StrategyTypeMeta`에 capability 7필드(code/description/availableTickers/requiresPrivacyBase/tickerFixed/supportsReverseMode/divisionCounts) 직렬화 → 프론트는 `isInfinite` 휴리스틱 대신 `divisionCounts`/`requiresPrivacyBase` 직접 소비
- **최소시드 미리보기**: `GET /api/accounts/{id}/strategy-seed-preview?type=&ticker=&divisionCount=` → `StrategySeedPreviewResponse { ticker, basePrice, minSeed, skipReason }`
  - `StrategyService.strategySeedPreview()`(trading) — `BrokerAdapterRegistry`(BrokerPricePort) + `PrivacyTradePort` + `CycleOrderStrategies.minRequiredDeposit` 조합. 브로커 HTTP 호출 포함이라 `@Transactional(NOT_SUPPORTED)` (register()와 동일)
  - PRIVACY + 기준 매매표 없는 날 → `skipReason="NO_PRIVACY_BASE"` (basePrice/minSeed=null)
- **신규 전략 타입 추가 시**: `StrategyType` enum case + `CycleOrderStrategy` 구현체 1개만 추가하면 메타 capability·최소시드·UI 자동 반영

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trade_bases` (`PrivacyTradeBaseEntity`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음**
  - `(release_date, ticker)` UNIQUE (`uq_privacy_trade_bases_release_date_ticker`) — 하루에 종목당 기준 매매표 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trade_base_orders` (`PrivacyTradeBaseOrderEntity`): 기준 매매표 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - direction/orderType은 `sharedkernel.OrderDirection`{BUY/SELL} / `OrderType`{LOC/MOC/LIMIT} — VARCHAR + `@Enumerated(STRING)`
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
- FIDA 수신 흐름: `(tradeDate, ticker)` 없음 → 201 / 내용 동일 → 200(멱등) / 내용 다름 → `PrivacyTradeConflictException` → 409
- 스케쥴러: `StrategyType.PRIVACY` → `PrivacyCycleOrderStrategy.plan()` → `PrivacyStrategy.buildOrders()` (`CycleOrderComputer`가 전략별 분기)

### VR 전략 패턴 (밸류리밸런싱)
공식·bootstrap 규칙·가격 캡·롤오버 조건의 SSOT는 constraints.md "VR 공식" — 여기서는 구조·흐름만 기록.
- **TQQQ 전용** — `StrategyType.VR.resolveTicker()` → `TQQQ` 강제. divisionCount 없음(null 직렬화), cycleSeedType=NONE 강제
- `strategy_vr_version` (`StrategyVrVersionEntity`): 전략 버전별 VR 설정 — intervalWeeks(롤오버 주기), bandWidth(밴드 폭 %), recurringAmount(USD, 양수=적립·0=거치·음수=인출) + 램프 파라미터 8개(initialGradient/gGraceWeeks/gStepWeeks/gMax/initialPoolLimitRate/pGraceWeeks/pStepWeeks/poolLimitFloor)
- `strategy_cycle_vr` (`StrategyCycleVrEntity`): 사이클 시작 시 스냅샷 — value(사이클 기준 V값), gradient(경과주수 기준 `gradientAt()` 재계산값), poolLimitRate(비율 스냅샷, 달러 아님 — poolLimit은 개장 `CyclePosition.usdDeposit×poolLimitRate`로 조회 시점 파생). `StrategyCycle.startAmount`는 모든 전략에서 개장 예수금+개장 보유분 시장가다.
- 주문 생성: `CycleOrderComputer` → `VrCycleOrderStrategy.plan()` → `VrStrategy.buildOrders()` → 매수·매도 사다리 LIMIT+AT_OPEN 주문 생성 (bootstrap은 LOC+AT_CLOSE)
- **holdings=0에도 사이클 유지** — `endsCycleOnLiquidation()=false`, `CyclePositionPersistor`가 종료 미발동
- **N주 롤오버**: `VrCycleRolloverService.rollIfDue()` — `CyclePositionPersistor`의 포지션 저장 직후 매일 판정, due이면 V′ 계산 후 기존 사이클 종료 + 새 사이클 원자 생성 (V′≤0 보류 등 규칙 → constraints.md). gradient·poolLimitRate는 스냅샷 이월이 아닌 전략 최초 사이클 startDate 기준 경과주수로 매번 재계산
- **운영 중 재설정**: `VrReconfigureService`(package-private) + `VrReconfigureUseCase` — `PUT /api/trading-cycles/{id}/vr-config`로 밴드폭·주기·적립금·램프 파라미터 수정 + 선택적 자본 주입/인출을 새 버전 발급+강제 롤오버 1회로 처리. 상세 규칙 → constraints.md "VR 공식"
