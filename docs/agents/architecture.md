## 아키텍처

### Gradle 구조

세 서브프로젝트: `:trading-core`(매매 실행 도메인 — trading/matching/broker/account/privacy/marketcalendar), `:shared`(sharedkernel/contract/platform — outbound-zero 공용 어휘·wire 계약·인프라 leaf), 루트 `:api`(그 외 전부, bootJar 산출). 루트와 `:trading-core` 모두 `:shared`를 단방향 `implementation` 의존한다(`shared ← trading-core`, `shared ← api`).

- 루트 main과 `:trading-core` main 사이 컴파일 의존은 **양방향 모두 없다** — 루트는 trading-core 타입을 import할 수 없고 HTTP 내부 API·Redis로만 통신하며, 그 wire 타입은 양쪽이 컴파일 타임에 공유하는 `com.kista.contract`(`:shared`)가 선언한다. 루트 **테스트** 소스만 `testImplementation(project(":trading-core"))`·`testFixtures(project(":trading-core"))`로 참조한다(루트 `src/testFixtures`엔 User fixture `DomainFixtures`만 잔류).
- 배포 산출물은 둘: 루트 `app.jar`(`kista-api`/`kista-scheduler` 2-role)와 `trading-core.jar`(`com.kista.tradingweb.TradingApplication`, `kista-trading` 프로세스 — 앱셸 `tradingweb` 소속). `Dockerfile`이 한 이미지에 두 jar를 담고 `APP_JAR`로 선택한다 — 컴파일 경계이면서 런타임 프로세스 분리다.
- `shared/build.gradle.kts`는 JUnit Platform 버전 정합을 위해 `org.springframework.boot` 플러그인을 적용하되 `bootJar`를 `enabled = false`로 비활성화한다. `trading-core/build.gradle.kts`는 `bootJar`를 활성 상태로 둔다.
- 테스트 지원: `com.kista.support`(`DataJpaTestBase`/`WebMvcTestSupport`/`TradingFixtures`)·`application-test.yml`은 `trading-core/src/testFixtures`. trading-core 모든 모듈(com.kista.trading 포함)의 `@DataJpaTest`는 상위 `@SpringBootConfiguration`이 없으므로(`TradingApplication`은 앱셸 `com.kista.tradingweb`에 있다) `@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)`를, `@WebMvcTest`는 `@ContextConfiguration(classes = TradingApplication.class)`를 명시한다. `trading-core`의 `test` 태스크는 `workingDir = rootProject.projectDir`다(루트 상대경로를 읽는 테스트용). Flyway 마이그레이션은 서비스별로 나뉜다 — root `db/migration`, trading-core `db/migration-trading`.
- **경계 검증**: `GradleModuleBoundaryTest`(`src/test/java/com/kista/architecture`)가 `:trading-core→:api`, `:shared→:trading-core`/`:shared→:api` 역방향 의존 금지를 컴파일 산출물(`*/build/classes/java/main`) 기준으로 강제하고, trading-core 테스트·testFixtures 산출물도 동일 규칙으로 검증한다. 내부 API wire 계약은 `InternalApiContractTest`가 잠근다 — `/api/internal/**` 핸들러의 반환·`@RequestBody` 타입이 `contract`/`sharedkernel`/JDK 밖이면 실패하고, `contract`의 의존은 JDK·sharedkernel·Jackson/Swagger/Bean Validation 어노테이션으로 제한된다(과거 own-type 복제쌍 + 리플렉션 검증 `OwnTypeContractTest`는 폐지).

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`). `domain_must_not_depend_on_outer_layers`는 `com.kista..domain..` 전체를 예외 없이 커버한다 — 전략 구현체 Spring 배선은 `CycleStrategyBeanConfig` 팩토리가 전담한다.
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (record aggregate 분리 제약 → `docs/agents/modules/trading.md` "Account ↔ Strategy 분리"). 마이그레이션·이관·사고 이력은 `docs/agents/modulith-migration-history.md` 참고 (필요시 Read).

Spring Modulith 기반 애그리게이트 모듈 구조다. 레거시 최상위 `com.kista.domain`/`application`/`adapter` 패키지는 전부 소멸했다.

```
DB 스키마 5분리, 소유 서비스별(같은 DB `kistadb`·같은 DB 유저). root(`:api`): `public`(users/refresh_tokens/user_settings/user_notification_prefs/admin_runtime_settings/audit_logs/app_error_logs/fcm_device_tokens 등 인증/관리자/로그/알림 성격 플랫폼 공통 + `scheduler_locks`/`event_publication`)·`finance`(가계부)·`kista_ref`(외부 참조·시장 데이터 — housing_benchmark_prices/housing_price_indices/market_index_prices/fear_greed_snapshots). `:trading-core`: `trading`(accounts/broker_tokens/strategy·strategy_version 계열/strategy_cycle 계열/cycle_position 계열/orders + `user_notify_profile` 복제본 + `scheduler_locks`/`event_publication` **사본** — root `public`에 의존하지 않도록 자체 보유)·`trading_ref`(us_market_holidays + FIDA PRIVACY 기준 매매표 privacy_trade_bases/privacy_trade_base_orders, 전역 공유·비개인 데이터가 기준). `KisTokenEntity`(`broker_tokens`)는 trading 소유. Flyway도 서비스별 — root는 `src/main/resources/db/migration`(이력 `flyway_schema_history_api`, 기본 스키마 `public`), trading-core는 `trading-core/src/main/resources/db/migration-trading`(이력 `flyway_schema_history_trading`, 기본 스키마 `trading`)이며 각자 소유 스키마 테이블만 만든다. **다른 서비스 소유 스키마 테이블은 참조 금지(FK 포함)**. `event_publication`이 서비스별 2개라 EPR 재발행 소유도 갈린다(root=`kista-scheduler`, trading=`kista-trading` → constraints.md Git 규칙). 신규 테이블은 소유 서비스 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 root는 `finance`·trading은 `trading`이라 생략 시 validate 실패) — nativeQuery/JdbcTemplate/raw SQL은 Hikari `connection-init-sql`의 search_path(root `finance, kista_ref, public` / trading `trading, trading_ref`)로 unqualified 이름이 자동 해석되므로 스키마 접두사 불필요(trading 프로세스는 root `public` 테이블을 볼 수 없다). 재편 경위 → `docs/agents/modulith-migration-history.md` "DB 스키마 재편"
```

### 모듈 한눈에 보기
모듈별 상세(패키지 트리·NamedInterface 내부 구조·전략 패턴)는 각 모듈 디렉토리의 `CLAUDE.md`가 자동 로드한다. 여러 모듈을 동시에 다루거나 own-type 신설을 검토할 때는 `docs/agents/modules/<module>.md`를 직접 Read.

```
com.kista.sharedkernel/  :shared    · OPEN · (NamedInterface 없음, 순수 어휘)   · 공유 enum·이벤트·순수 포트, outbound 0    → modules/sharedkernel.md
com.kista.contract/      :shared    · OPEN · (NamedInterface 없음, wire 스키마) · 프로세스 경계 내부 API·Redis 요청/응답 record(Published Language), sharedkernel 외 outbound 0 → modules/contract.md
com.kista.platform/      :shared    · OPEN · (NamedInterface 없음, 인프라 leaf) · persistence/crypto/time/scheduling/redis 공용 인프라, outbound 0 → modules/platform.md
com.kista.matching/      :trading-core · CLOSED · "kernel"                 · 주문생성 커널(순수 계산), CycleOrderStrategy SSOT → modules/matching.md
com.kista.finance/       :api       · CLOSED · "domain"/"usecase"/"port"   · 가계부 애그리게이트, 마감월 쓰기 차단          → modules/finance.md
com.kista.notify/        :api       · CLOSED · "port"/"domain"            · Telegram/FCM 순수 아웃바운드 알림 게이트웨이(봇 명령 채널은 admin 소유, 수신자는 notify 소유 NotificationRecipient). 매매 알림 6종은 tradingnotify(trading-core) 소유 → modules/notify.md
com.kista.broker/        :trading-core · CLOSED · "domain"/"port"/"application" · KIS/Toss/Mock 연동, Account를 전혀 참조하지 않음(Account.toBrokerRef() 1곳 전담) → modules/broker.md
com.kista.trading/       :trading-core · CLOSED · "domain"/"usecase"/"port"/"schedule"/"event" · 주문·사이클 실행·전략 설정 (통계·알림은 아래 두 모듈로 분리) → modules/trading.md
com.kista.tradingstats/  :trading-core · CLOSED · "domain"/"usecase"/"port"  · 계좌·증권사 통계·백테스트·포트폴리오·summary 조회. trading 공개 계약만 읽는 단방향 소비자 → modules/tradingstats.md
com.kista.tradingnotify/ :trading-core · CLOSED · (NamedInterface 0개, 리스너 sink) · 매매 알림 6종+게이트웨이. trading 이벤트 11종·privacy 이벤트 구독, EPR 재발행 소유 → modules/tradingnotify.md
com.kista.market/        :api       · CLOSED · "domain"/"port"/"event"     · 공포탐욕지수(CNN/Crypto) 애그리게이트          → modules/market.md
com.kista.marketcalendar/ :trading-core · CLOSED · "domain"/"port"        · 미국 시장 휴장일 캘린더                        → modules/marketcalendar.md
com.kista.privacy/       :trading-core · CLOSED · "domain"/"port"/"usecase"/"event" · FIDA 기준 매매표 전역 SSOT                   → modules/privacy.md
com.kista.benchmark/     :api       · CLOSED · "domain"/"usecase"/"port"/"event"/"schedule" · 주택/ETF 벤치마크 비교(구 `stats` — 2026-09-30 개명, 계좌·Toss 통계는 tradingstats 소유) → modules/benchmark.md
com.kista.admin/         :api       · CLOSED · "domain"/"usecase"/"port"   · 관리자 조회·정정·재정렬·런타임 설정            → modules/admin.md
com.kista.user/          :api       · CLOSED · "domain"/"usecase"/"port"/"event" · 가입·승인·프로필·JWT 인증               → modules/user.md
com.kista.account/       :trading-core · CLOSED · "domain"/"usecase"/"port"/"event" · 계좌 자격증명·브로커 연결. Strategy와 별도 aggregate → modules/account.md
com.kista.web/           :api       · CLOSED · (NamedInterface 0개, 앱셸 sink) · 크로스모듈 fan-out·GlobalExceptionHandler·MetaController·RootSecurityPolicy → modules/web.md
com.kista.tradingweb/    :trading-core · CLOSED · (NamedInterface 0개, 앱셸 sink) · TradingApplication·TradingExceptionHandler·JpaRepositoryConfig → modules/tradingweb.md
```

신규 own-type 복제·게이트 판정 시 `docs/agents/own-type-ledger.md` 필수 Read — (b) 허용 사례·단일 소유 포트 타입·narrowing projection 원장(프로세스 경계 wire 타입은 own-type이 아니라 `com.kista.contract`). 자동 로드되지 않는다.

### Spring Modulith 모듈 구성
19개 모듈(finance/notify/broker/trading/tradingstats/tradingnotify/matching/market/marketcalendar/privacy/benchmark/admin/user/account/sharedkernel/contract/platform/web/tradingweb — `:api`·`:trading-core`·`:shared`에 나뉘어 위치) 전부 `@ApplicationModule`로 선언돼 있고, 모듈 간 경계는 `ApplicationModules.verify()`(`ModulithArchitectureTest`)가, 모듈 내부 레이어 방향은 `HexagonalArchitectureTest`가 각각 검증한다. 각 모듈의 NamedInterface와 내부 패키지는 위 "모듈 한눈에 보기" 요약과 `docs/agents/modules/<module>.md`(해당 모듈 디렉토리 작업 시 자동 로드)에 기록돼 있다 — 신규 코드 추가 시 해당 모듈 문서에서 위치·공개 범위를 확인할 것.

모듈 간 참조는 원칙적으로 상대 모듈이 공개한 NamedInterface(도메인 타입 또는 own-type projection)만 거쳐야 하며(프로세스 경계를 넘는 wire 타입은 own-type이 아니라 `com.kista.contract`), 서로 참조가 얽히면 포트 역전(own-type 정의 + 상대가 구현) 또는 이벤트 발행(`@TransactionalEventListener`, EPR 재시도) 패턴을 쓴다. 다른 프로세스(trading-core ↔ root) 사이는 EPR이 전달되지 않으므로 내부 HTTP API 또는 Redis(Pub/Sub·Stream)를 쓴다.

### 인증 userId 추출 패턴
- 모든 컨트롤러: `@AuthenticationPrincipal UUID userId` 메서드 파라미터로 직접 주입 — `SecurityContextHolder` 수동 호출 금지
- `JwtAuthFilter`: principal을 `UUID` 타입으로 저장 (`String` 아님)

### 소유권 검증 패턴
- `account.verifyOwnedBy(requesterId)` — 불일치 시 `SecurityException` (403 매핑은 `ProblemDetailMappings.GENERIC`이 공급)
- `AccountPort.requireOwnedAccount(accountId, requesterId)`(default) — `findByIdOrThrow` + `verifyOwnedBy` 한 번에. 소유권 검증이 필요한 서비스는 이것을 쓴다(`AccountStatisticsService` 등)
- `accountPort.findByIdOrThrow(id)` — 없으면 `NoSuchElementException` (404 매핑)
- 전략·사이클 소유권은 `strategy.accountId()`가 요청 계좌와 일치하는지로 검증한다(예: `TradingInternalQueryController.requireStrategyOwnedByAccount`) — 별도 `verifyOwnedBy`는 없다
- Controller에 try/catch 추가 금지 — `ResponseStatusException` 등 Spring HTTP 클래스는 application layer 사용 불가 (ArchUnit 규칙)

### JPA Auditing
- `BaseAuditEntity` (`@MappedSuperclass`, `com.kista.platform.persistence`): 각 모듈 Entity가 상속(`UserEntity`/`AccountEntity`/`OrderEntity` 등) — `@CreatedDate`/`@LastModifiedDate`로 `createdAt`/`updatedAt` 자동 관리
- 새 엔티티에 타임스탬프 필요 시 `BaseAuditEntity`(`createdAt`+`updatedAt`) 또는 `BaseCreatedAtEntity`(`createdAt`만) 상속 — `updated_at` 컬럼 없는 엔티티에 `BaseAuditEntity` 사용 금지 (`ddl-auto: validate` 실패)
- 서비스에서 domain record 생성 시: `updatedAt=null` (adapter가 무시, `@LastModifiedDate`가 처리), `createdAt`은 update 시 기존 값 보존 / register 시 `null` (`@CreatedDate`가 처리)
- `toEntity()` 내에서 `setCreatedAt()`/`setUpdatedAt()` 명시적 호출 금지 — 호출 자체가 dead code이며 `@Setter(PACKAGE)` 범위 제약과도 충돌

### 텔레그램 알림 (사용자 봇)
- 계좌별 텔레그램 설정은 없다 — 사용자 봇(`telegramBotToken`/`chatId`)만 사용. 봇 토큰은 persistence 경계에서만 AES-256 암호화/복호화(root `UserPersistenceAdapter`, trading-core `UserNotifyProfilePersistenceAdapter`/`UserNotifyProfileSyncListener`)
- 매매 알림(`notifyTradingReport` 등)은 trading-core `tradingnotify`가 `trading.user_notify_profile` 복제본(`TradingUserProfile`)의 토큰·chatId로 발송하고, 둘 중 하나라도 없으면 조용히 건너뛴다(`TradingUserNotificationAdapter.sendIfLinked`) → `modules/tradingnotify.md`

### 전략 패턴 (모듈별 문서로 이동)
BrokerCapabilitiesPort/BrokerRouter 패턴·TDA 전략 패턴(InfiniteStrategy)·CycleOrderStrategy Capability 패턴·PRIVACY 전략 패턴·VR 전략 패턴은 각각 `modules/broker.md`·`modules/trading.md`·`modules/matching.md`·`modules/privacy.md`·`modules/trading.md`로 이동했다.
