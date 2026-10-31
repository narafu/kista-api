## 아키텍처

### Gradle 구조

세 서브프로젝트: `:trading-core`(매매 실행 도메인 — trading/matching/broker/account/privacy/marketcalendar), `:shared`(sharedkernel/contract/platform — outbound-zero 공용 어휘·wire 계약·인프라 leaf), 루트 `:api`(그 외 전부, bootJar 산출). 루트와 `:trading-core` 모두 `:shared`를 단방향 `implementation` 의존한다(`shared ← trading-core`, `shared ← api`).

- 루트 main과 `:trading-core` main 사이 컴파일 의존은 **양방향 모두 없다** — 루트는 trading-core 타입을 import할 수 없고 HTTP 내부 API·Redis로만 통신하며, wire 타입은 양쪽이 공유하는 `com.kista.shared.contract`(`:shared`)가 선언한다. 루트 **테스트** 소스만 `testImplementation(project(":trading-core"))`로 참조한다.
- 배포 산출물은 둘: 루트 `app.jar`(`kista-api`/`kista-scheduler` 2-role)와 `trading-core.jar`(`kista-trading` 프로세스). `Dockerfile`이 한 이미지에 두 jar를 담고 `APP_JAR`로 선택한다.
- 테스트 구성 → testing.md

### 루트 패키지
서브프로젝트마다 자기 루트 패키지 아래에만 클래스를 둔다 — `:api`=`com.kista.api`, `:trading-core`=`com.kista.tradingcore`, `:shared`=`com.kista.shared`. 앱 클래스(`ApiApplication`, `TradingApplication`)가 각 루트에 있어 기본 스캔이 자기 루트만 덮는다(스캔 목록 없음). :shared 빈은 컴포넌트 스캔하지 않고 `PlatformAutoConfiguration`(AutoConfiguration.imports 등록)이 등록한다 → `modules/shared/platform.md`.
- **경계 가드**: `GradleModuleBoundaryTest`가 루트 패키지 소속·`:trading-core→:api`·`:shared→(trading-core|api)` 역방향 의존 금지를 강제하고, `InternalApiContractTest`가 `/api/internal/**` 핸들러의 반환·`@RequestBody` 타입이 `contract`/`sharedkernel`/JDK 안인지 잠근다. 컨텍스트 로드는 `ApplicationContextLoadTest`(api)·`TradingApplicationContextLoadTest`(trading-core)가 잠근다.

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`) — 전략 구현체 Spring 배선은 `CycleStrategyBeanConfig` 팩토리가 전담한다.
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (record aggregate 분리 제약 → `docs/agents/modules/tradingcore/trading.md` "Account ↔ Strategy 분리"). 마이그레이션·이관·사고 이력은 `docs/history/modulith-migration-history.md` 참고 (필요시 Read).

Spring Modulith 기반 애그리게이트 모듈 구조다.

```
DB 스키마 5분리, 소유 서비스별(같은 DB `kistadb`·같은 DB 유저). root(`:api`): `public`(users/refresh_tokens/user_settings/user_notification_prefs/admin_runtime_settings/audit_logs/app_error_logs/fcm_device_tokens 등 인증/관리자/로그/알림 성격 플랫폼 공통 + `scheduler_locks`/`event_publication`)·`finance`(가계부)·`kista_ref`(외부 참조·시장 데이터 — housing_benchmark_prices/housing_price_indices/market_index_prices/fear_greed_snapshots). `:trading-core`: `trading`(accounts/broker_tokens/strategy·strategy_version 계열/strategy_cycle 계열/cycle_position 계열/orders + `user_notify_profile` 복제본 + `scheduler_locks`/`event_publication` **사본** — root `public`에 의존하지 않도록 자체 보유)·`trading_ref`(us_market_holidays + FIDA PRIVACY 기준 매매표 privacy_trade_bases/privacy_trade_base_orders, 전역 공유·비개인 데이터가 기준). `KisTokenEntity`(`broker_tokens`)는 trading 소유. Flyway도 서비스별(이력 `flyway_schema_history_api`/`flyway_schema_history_trading`, 디렉토리·절차 → flyway.md)이며 각자 소유 스키마 테이블만 만든다. **다른 서비스 소유 스키마 테이블은 참조 금지(FK 포함)**. `event_publication`이 서비스별 2개라 EPR 재발행 소유도 갈린다(root=`kista-scheduler`, trading=`kista-trading` → constraints.md Git 규칙). 신규 테이블은 소유 서비스 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 root는 `finance`·trading은 `trading`이라 생략 시 validate 실패) — nativeQuery/JdbcTemplate/raw SQL은 Hikari `connection-init-sql`의 search_path(root `finance, kista_ref, public` / trading `trading, trading_ref`)로 unqualified 이름이 자동 해석되므로 스키마 접두사 불필요(trading 프로세스는 root `public` 테이블을 볼 수 없다). 재편 경위 → `docs/history/modulith-migration-history.md` "DB 스키마 재편"
```

### 모듈 한눈에 보기
모듈별 상세(패키지 트리·NamedInterface 내부 구조·전략 패턴)는 각 모듈 디렉토리의 `CLAUDE.md`가 자동 로드한다. 여러 모듈을 동시에 다루거나 own-type 신설을 검토할 때는 `docs/agents/modules/<루트>/<ID>.md`를 직접 Read.

```
# api 프로세스 모델 (kista-api·kista-scheduler) — api 7 + shared 3
com.kista.api.finance/       :api       · CLOSED · "domain"/"usecase"/"port"   · 가계부 애그리게이트, 마감월 쓰기 차단          → modules/api/finance.md
com.kista.api.notify/        :api       · CLOSED · "port"/"domain"            · Telegram/FCM 순수 아웃바운드 알림 게이트웨이(봇 명령 채널은 admin 소유, 수신자는 notify 소유 NotificationRecipient). 매매 알림 6종은 tradingcore.notify(trading-core) 소유 → modules/api/notify.md
com.kista.api.market/        :api       · CLOSED · "domain"/"port"/"event"     · 공포탐욕지수(CNN/Crypto) 애그리게이트          → modules/api/market.md
com.kista.api.benchmark/     :api       · CLOSED · "domain"/"usecase"/"port"/"event"/"schedule" · 주택/ETF 벤치마크 비교(계좌·Toss 통계는 tradingcore.stats 소유) → modules/api/benchmark.md
com.kista.api.admin/         :api       · CLOSED · "domain"/"usecase"/"port"   · 관리자 조회·정정·재정렬·런타임 설정            → modules/api/admin.md
com.kista.api.user/          :api       · CLOSED · "domain"/"usecase"/"port"/"event" · 가입·승인·프로필·JWT 인증               → modules/api/user.md
com.kista.api.web/           :api       · CLOSED · (NamedInterface 0개, 앱셸 sink) · 크로스모듈 fan-out·GlobalExceptionHandler·MetaController·RootSecurityPolicy → modules/api/web.md
com.kista.shared.kernel/  :shared    · OPEN · (NamedInterface 없음, 순수 어휘)   · 공유 enum·이벤트·순수 포트, outbound 0    → modules/shared/kernel.md
com.kista.shared.contract/      :shared    · OPEN · (NamedInterface 없음, wire 스키마) · 프로세스 경계 내부 API·Redis 요청/응답 record(Published Language), sharedkernel 외 outbound 0 → modules/shared/contract.md
com.kista.shared.platform/      :shared    · OPEN · (NamedInterface 없음, 인프라 leaf) · persistence/crypto/time/scheduling/redis 공용 인프라, outbound 0 → modules/shared/platform.md
```

```
# trading-core 프로세스 모델 (kista-trading) — trading-core 9 + shared 3 (shared는 위와 같은 3개)
com.kista.tradingcore.matching/      :trading-core · CLOSED · "kernel"                 · 주문생성 커널(순수 계산), CycleOrderStrategy SSOT → modules/tradingcore/matching.md
com.kista.tradingcore.broker/        :trading-core · CLOSED · "domain"/"port"/"application" · KIS/Toss/Mock 연동, Account를 전혀 참조하지 않음(Account.toBrokerRef() 1곳 전담) → modules/tradingcore/broker.md
com.kista.tradingcore.trading/       :trading-core · CLOSED · "domain"/"usecase"/"port"/"schedule"/"event" · 주문·사이클 실행·전략 설정 (통계·알림은 아래 두 모듈로 분리) → modules/tradingcore/trading.md
com.kista.tradingcore.stats/  :trading-core · CLOSED · "domain"/"usecase"/"port"  · 계좌·증권사 통계·백테스트·포트폴리오·summary 조회. trading 공개 계약만 읽는 단방향 소비자 → modules/tradingcore/stats.md
com.kista.tradingcore.notify/ :trading-core · CLOSED · (NamedInterface 0개, 리스너 sink) · 매매 알림 6종+게이트웨이. trading·privacy 이벤트 구독, EPR 재발행 소유 → modules/tradingcore/notify.md
com.kista.tradingcore.marketcalendar/ :trading-core · CLOSED · "domain"/"port"        · 미국 시장 휴장일 캘린더                        → modules/tradingcore/marketcalendar.md
com.kista.tradingcore.privacy/       :trading-core · CLOSED · "domain"/"port"/"usecase"/"event" · FIDA 기준 매매표 전역 SSOT                   → modules/tradingcore/privacy.md
com.kista.tradingcore.account/       :trading-core · CLOSED · "domain"/"usecase"/"port"/"event" · 계좌 자격증명·브로커 연결. Strategy와 별도 aggregate → modules/tradingcore/account.md
com.kista.tradingcore.web/    :trading-core · CLOSED · (NamedInterface 0개, 앱셸 sink) · TradingExceptionHandler(부팅 진입점 `TradingApplication`은 이 모듈이 아니라 `com.kista.tradingcore` 루트) → modules/tradingcore/web.md
# shared 3개(kernel·contract·platform)는 위 api 블록과 동일
```

### Spring Modulith 모듈 구성
모듈 모델은 프로세스별로 둘이다 — `ApplicationModules.of(ApiApplication.class)`(api 7 + shared 3)와 `ApplicationModules.of(TradingApplication.class)`(trading-core 9 + shared 3). 두 앱 클래스 모두 `@Modulithic(additionalPackages = "com.kista.shared")`로 `:shared`를 모델에 포함한다. 모듈 ID는 각 `package-info`의 `@ApplicationModule(id = ...)`로 고정하고 감지 전략은 `spring.modulith.detection-strategy: explicitly-annotated`(양쪽 application.yml)다 — **새 모듈은 `id`를 반드시 지정**한다. 두 프로세스에 같은 ID(`notify`, `web`)가 있어 문서·아키텍처 맵에서는 `api.notify`/`tradingcore.notify`처럼 루트를 붙여 쓴다. 모듈 간 경계는 `ApplicationModules.verify()`(각 프로세스의 `ModulithArchitectureTest`)가, 모듈 내부 레이어 방향은 `HexagonalArchitectureTest`가 검증한다.

모듈 간 참조는 원칙적으로 상대 모듈이 공개한 NamedInterface(도메인 타입 또는 own-type projection)만 거쳐야 하며(프로세스 경계를 넘는 wire 타입은 own-type이 아니라 `com.kista.shared.contract`), 서로 참조가 얽히면 포트 역전(own-type 정의 + 상대가 구현) 또는 이벤트 발행(`@TransactionalEventListener`, EPR 재시도) 패턴을 쓴다. 다른 프로세스(trading-core ↔ root) 사이는 EPR이 전달되지 않으므로 내부 HTTP API 또는 Redis(Pub/Sub·Stream)를 쓴다.

### 인증 userId 추출 패턴
- 컨트롤러는 `@AuthenticationPrincipal UUID userId` 파라미터로 주입한다 — `SecurityContextHolder` 수동 호출 금지

### 소유권 검증 패턴
- `account.verifyOwnedBy(requesterId)` — 불일치 시 `SecurityException`(403 매핑은 `ProblemDetailMappings.GENERIC`이 공급). 서비스는 `AccountPort.requireOwnedAccount(accountId, requesterId)`(`findByIdOrThrow` + `verifyOwnedBy`)를 쓴다(`AccountStatisticsService` 등)
- 전략·사이클 소유권은 `strategy.accountId()`가 요청 계좌와 일치하는지로 검증한다(예: `TradingInternalQueryController.requireStrategyOwnedByAccount`)

### JPA Auditing
- `BaseAuditEntity`(`createdAt`+`updatedAt`) 또는 `BaseCreatedAtEntity`(`createdAt`만)를 상속한다(`com.kista.shared.platform.persistence`) — `updated_at` 컬럼 없는 엔티티에 `BaseAuditEntity` 금지(`ddl-auto: validate` 실패)
- 서비스에서 domain record 생성 시 `updatedAt=null`(`@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null`(`@CreatedDate`가 처리)
- `toEntity()`에서 `setCreatedAt()`/`setUpdatedAt()`을 호출하지 않는다 — `BaseAuditEntity`의 setter는 package-private라 각 모듈 persistence 패키지에서 접근도 안 된다

### 텔레그램 알림 (사용자 봇)
- 사용자 봇(`telegramBotToken`/`chatId`)만 쓰고 계좌별 설정은 없다. 토큰 암복호화는 엔티티 `@Convert(AesStringConverter)`가 한다 → constraints.md "AES-256"
- 매매 알림은 trading-core `tradingcore.notify`가 `trading.user_notify_profile` 복제본 기준으로 발송하며, 토큰·chatId·텔레그램 채널 중 하나라도 없으면 조용히 건너뛴다 → `modules/tradingcore/notify.md`
