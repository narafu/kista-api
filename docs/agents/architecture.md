## 아키텍처

### Gradle 구조

세 서브프로젝트: `:trading-core`(매매 실행 도메인 — trading/matching/broker/account/privacy/marketcalendar), `:shared`(sharedkernel/contract/platform — outbound-zero 공용 어휘·wire 계약·인프라 leaf), 루트 `:api`(그 외 전부, bootJar 산출). 루트와 `:trading-core` 모두 `:shared`를 단방향 `implementation` 의존한다(`shared ← trading-core`, `shared ← api`).

- 루트 main과 `:trading-core` main 사이 컴파일 의존은 **양방향 모두 없다** — 루트는 trading-core 타입을 import할 수 없고 HTTP 내부 API·Redis로만 통신하며, wire 타입은 양쪽이 공유하는 `com.kista.shared.contract`(`:shared`)가 선언한다. 루트 **테스트** 소스만 `testImplementation(project(":trading-core"))`로 참조한다.
- 테스트 구성 → testing.md

### 루트 패키지
서브프로젝트마다 자기 루트 패키지 아래에만 클래스를 둔다 — `:api`=`com.kista.api`, `:trading-core`=`com.kista.tradingcore`, `:shared`=`com.kista.shared`. 앱 클래스(`ApiApplication`, `TradingApplication`)가 각 루트에 있어 기본 스캔이 자기 루트만 덮는다(스캔 목록 없음).
- **경계 가드**: `GradleModuleBoundaryTest`가 루트 패키지 소속·`:trading-core→:api`·`:shared→(trading-core|api)` 역방향 의존 금지를 강제한다. 컨텍스트 로드는 `ApplicationContextLoadTest`(api)·`TradingApplicationContextLoadTest`(trading-core)가 잠근다.

Hexagonal Architecture (Port & Adapter). **ArchUnit이 빌드 시 레이어 의존성을 강제 검증**한다 (`HexagonalArchitectureTest`).
클래스·필드 상세는 코드가 SSOT — 아래 맵은 위치·역할·비자명한 규칙만 기록한다 (record aggregate 분리 제약 → `docs/agents/modules/tradingcore/trading.md` "Account ↔ Strategy 분리"). 마이그레이션·이관·사고 이력은 `docs/history/modulith-migration-history.md` 참고 (필요시 Read).

Spring Modulith 기반 애그리게이트 모듈 구조다.

### DB 스키마 5분리
DB 스키마는 소유 서비스별로 5개다 — root(`:api`): `public`·`finance`·`kista_ref`, `:trading-core`: `trading`·`trading_ref`. 신규 테이블은 소유 서비스 기준으로 분류해 Entity에 `@Table(schema=...)` 명시(public도 명시 — search_path 첫 스키마가 root는 `finance`·trading은 `trading`이라 생략 시 validate 실패). **다른 서비스 소유 스키마 테이블은 참조 금지(FK 포함)**. 상세 테이블 소유 표·search_path·Flyway 이력 → `flyway.md` "DB 스키마 5분리" (마이그레이션 작업 시 자동 로드, 그 외엔 직접 Read).

### 모듈 한눈에 보기
모듈별 상세(패키지 트리·NamedInterface 내부 구조·전략 패턴)는 각 모듈 디렉토리의 `CLAUDE.md`가 자동 로드한다. 여러 모듈을 동시에 다루거나 own-type 신설을 검토할 때는 `docs/agents/modules/<루트>/<ID>.md`를 직접 Read.

```
# api 프로세스 모델 (kista-api·kista-scheduler) — api 7 + shared 3
com.kista.api.finance/       :api       · CLOSED · "domain"/"usecase"/"port"   · 가계부 애그리게이트                    → modules/api/finance.md
com.kista.api.notify/        :api       · CLOSED · "port"/"domain"            · Telegram/FCM 아웃바운드 알림 게이트웨이   → modules/api/notify.md
com.kista.api.market/        :api       · CLOSED · "domain"/"port"/"event"     · 공포탐욕지수 애그리게이트                → modules/api/market.md
com.kista.api.benchmark/     :api       · CLOSED · "domain"/"usecase"/"port"/"event"/"schedule" · 주택/ETF 벤치마크 비교  → modules/api/benchmark.md
com.kista.api.admin/         :api       · CLOSED · "domain"/"usecase"/"port"   · 관리자 조회·정정·런타임 설정            → modules/api/admin.md
com.kista.api.user/          :api       · CLOSED · "domain"/"usecase"/"port"/"event" · 가입·승인·프로필·JWT 인증           → modules/api/user.md
com.kista.api.web/           :api       · CLOSED · (NamedInterface 0개)        · 크로스모듈 fan-out 앱셸                  → modules/api/web.md
com.kista.shared.kernel/     :shared    · OPEN · (NamedInterface 없음)         · 공유 enum·이벤트·순수 포트              → modules/shared/kernel.md
com.kista.shared.contract/   :shared    · OPEN · (NamedInterface 없음)         · 프로세스 경계 wire 계약 record          → modules/shared/contract.md
com.kista.shared.platform/   :shared    · OPEN · (NamedInterface 없음)         · 공용 인프라 leaf                        → modules/shared/platform.md
```

```
# trading-core 프로세스 모델 (kista-trading) — trading-core 9 + shared 3 (shared는 위와 같은 3개)
com.kista.tradingcore.matching/      :trading-core · CLOSED · "kernel"                 · 주문생성 커널(순수 계산)             → modules/tradingcore/matching.md
com.kista.tradingcore.broker/        :trading-core · CLOSED · "domain"/"port"/"application" · KIS/Toss/Mock 증권사 연동 → modules/tradingcore/broker.md
com.kista.tradingcore.trading/       :trading-core · CLOSED · "domain"/"usecase"/"port"/"schedule"/"event" · 주문·사이클 실행·전략 설정 → modules/tradingcore/trading.md
com.kista.tradingcore.stats/         :trading-core · CLOSED · "domain"/"usecase"/"port"  · 통계·백테스트·포트폴리오 조회      → modules/tradingcore/stats.md
com.kista.tradingcore.notify/        :trading-core · CLOSED · (NamedInterface 0개)     · 매매 알림 리스너·게이트웨이          → modules/tradingcore/notify.md
com.kista.tradingcore.marketcalendar/ :trading-core · CLOSED · "domain"/"port"         · 미국 시장 휴장일 캘린더              → modules/tradingcore/marketcalendar.md
com.kista.tradingcore.privacy/       :trading-core · CLOSED · "domain"/"port"/"usecase"/"event" · FIDA 기준 매매표 전역 SSOT → modules/tradingcore/privacy.md
com.kista.tradingcore.account/       :trading-core · CLOSED · "domain"/"usecase"/"port"/"event" · 계좌 자격증명·브로커 연결    → modules/tradingcore/account.md
com.kista.tradingcore.web/           :trading-core · CLOSED · (NamedInterface 0개)     · 전역 web 앱셸                        → modules/tradingcore/web.md
# shared 3개(kernel·contract·platform)는 위 api 블록과 동일
```

### Spring Modulith 모듈 구성
모듈 모델은 프로세스별로 둘이다 — `ApplicationModules.of(ApiApplication.class)`(api 7 + shared 3)와 `ApplicationModules.of(TradingApplication.class)`(trading-core 9 + shared 3). 두 앱 클래스 모두 `@Modulithic(additionalPackages = "com.kista.shared")`로 `:shared`를 모델에 포함한다. 모듈 ID는 각 `package-info`의 `@ApplicationModule(id = ...)`로 고정하고 감지 전략은 `spring.modulith.detection-strategy: explicitly-annotated`(양쪽 application.yml)다 — **새 모듈은 `id`를 반드시 지정**한다. 두 프로세스에 같은 ID(`notify`, `web`)가 있어 문서·아키텍처 맵에서는 `api.notify`/`tradingcore.notify`처럼 루트를 붙여 쓴다. 모듈 간 경계는 `ApplicationModules.verify()`(각 프로세스의 `ModulithArchitectureTest`)가, 모듈 내부 레이어 방향은 `HexagonalArchitectureTest`가 검증한다.

모듈 간 참조는 원칙적으로 상대 모듈이 공개한 NamedInterface(도메인 타입 또는 own-type projection)만 거쳐야 한다(프로세스 경계를 넘는 wire 타입은 own-type이 아니라 `com.kista.shared.contract`). 다른 프로세스(trading-core ↔ root) 사이는 EPR이 전달되지 않으므로 내부 HTTP API 또는 Redis(Pub/Sub·Stream)를 쓴다.

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
사용자 봇만 사용, 계좌별 설정 없음. 발송 규칙 → `modules/tradingcore/notify.md`
