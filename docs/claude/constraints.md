## 핵심 제약 사항

### domain/port/out/ 네이밍 규칙
- 모든 아웃바운드 포트 인터페이스: `*Port` 접미사 사용 (예: `UserPort`, `AccountPort`, `StrategyPort`)
- Spring Data JPA 인터페이스는 `*JpaRepository` (adapter 레이어) — `domain/port/out/`와 완전히 다른 계층
- `*Repository` 접미사 사용 금지 — JpaRepository와 혼동 유발

### adapter/out 간 JpaRepository 접근 제한
- `*JpaRepository`는 package-private — 선언 패키지 외부에서 직접 import 시 컴파일 오류
- 다른 패키지 adapter에서 DB 조작이 필요하면 도메인 포트(`domain/port/out/`) 경유 필수
- 패턴: `AlpacaCalendarAdapter`(adapter.out.alpaca) → `MarketHolidayStorePort`(domain.port.out) → `MarketCalendarPersistenceAdapter`(persistence.calendar)
- 참고: `KisTokenAdapter`(adapter.out.kis) → `KisTokenCachePort` → `KisTokenPersistenceAdapter`(persistence.kistoken)


### GlobalExceptionHandler 자동 예외 처리
- Controller에서 별도 catch/rethrow 불필요 — 아래 예외는 모두 `GlobalExceptionHandler`에서 자동 처리됨
- `NoSuchElementException` → 404, `IllegalArgumentException` → 400, `IllegalStateException` → 400, `PrivacyTradeConflictException` → 409
- `SecurityException` → 403, `KisApiException` → 503, `TossApiException` → 503, `Account.InvalidKisKeyException` → 422
- `ManualTradingException` / `OrderCancelException` → 409, `Account.DuplicateAccountException` → 409
- `Account.CooldownException` → 429(Retry-After 포함), `Account.KisRateLimitException` → 429
- `InvalidRefreshTokenException` → 401 (RT 재사용·만료·위변조 시)

### Account ↔ Strategy 분리
- `Account` record 필드 8개: `id, userId, nickname, accountNo, appKey, secretKey, kisAccountType, broker` — type/status/ticker/multiple/createdAt/updatedAt 없음 (감사 컬럼은 persistence 레이어 `BaseAuditEntity`가 관리)
- `Strategy` record 필드 7개: `id, accountId, type(Type), status(Status), ticker(Ticker), cycleSeedType(CycleSeedType), divisionCount(int)`
- `StrategyCycle` record 필드 9개: `id, strategyId, startAmount, endAmount, startDate, endDate, createdAt, deletedAt, seedResolvedBy` — 사이클 단위 메타(시드/기간)
- `CyclePosition` record 필드 8개: `id, strategyCycleId, usdDeposit, closingPrice, avgPrice, holdings, createdAt, deletedAt` — 체결마다 append되는 포지션 스냅샷
- `StrategyDetail` record: `Strategy strategy, BigDecimal initialUsdDeposit, boolean isReverseMode` — 최신 `StrategyCycle.startAmount`를 묶어 응답 조립 (`StrategyService.toDetail()`)
- `Type`, `Status`, `Ticker`, `CycleSeedType` 모두 `Strategy` record의 nested enum
- 계좌당 종목(ticker) 중복 등록 불가 — `StrategyPort.existsByAccountIdAndTicker(accountId, ticker)` (계좌당 여러 전략 등록 가능, 종목별 1개)
- `StrategyCycle.startAmount`: 사이클 시작 시드(시작금액) — 시드 수정 시 `StrategyCyclePort.updateStartAmount()`로 in-place 갱신
- `cycleSeedType`: 사이클 종료 후 자동 재등록 정책 (DEFAULT `NONE`)

### 잔고검증 토글 (UserSettings.balanceCheckEnabled)
- `UserSettings.balanceCheckEnabled`: `User` record 아님 — `UserSettings` aggregate(`domain/model/user/UserSettings.java`)에 위치 (커밋 9a02f56에서 이전)
- ON이면 `StrategyService` 시드 등록/수정 시 "KIS 가용금액 − 기존 활성 전략 점유 시드" 한도 초과를 `IllegalArgumentException`으로 차단
- `UserSettingsService.updateBalanceCheckEnabled()`: OFF→ON 전환 시 활성 전략 존재하면 "시드>실잔고면 다음 사이클 회전 시 PAUSED" 경고 로그, ON→OFF 전환 시 "APBK0988 주문거부 가능" 경고 로그 (audit 목적, 차단 아님)
- 설정 미존재 시 `UserSettings.defaultFor(userId)` — `balanceCheckEnabled=true`, 빈 notificationPrefs


### MetaController (enum SSOT)
- `/api/meta` — `MetaBundle` 번들 (strategyTypes/tickers/brokers/strategyStatuses/cycleSeedTypes) 단일 엔드포인트
- 개별 엔드포인트(`/api/meta/strategy-types` 등) 삭제됨 — UI는 번들 엔드포인트만 사용
- `Strategy.Type`/`Strategy.Status`, `Strategy.Ticker`, `Account.Broker` enum에 label/description 필드 포함 (DTO `from()` 팩토리)
- UI는 이 엔드포인트로 라벨/available tickers/default값 수신 — enum 리터럴 UI 하드코딩 금지
- `TickerMeta` 응답 필드: `code`/`label`/`description`/`targetProfitRate` — KIS 거래소 코드(OVRS_EXCG_CD/EXCD)는 어댑터 내부(`KisExchangeRegistry`) 전용, UI 미노출

### 파일 인코딩 주의 (BOM)
- 서브에이전트가 Java 파일 import 수정 시 BOM(`\xef\xbb\xbf`) 삽입 버그 발생 사례 → `compileJava` 즉시 실패
- 일괄 제거: `grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '1s/^\xef\xbb\xbf//' "$f"; done`

### 수량 변수명 규칙
- **보유 잔고 수량** (avgPrice와 짝이 되는 것): `holdings` — `AccountBalance`, `TradingSnapshot`, `CyclePositionHistoryEntry`, `PresentBalanceResult.Item`, `PrivacyTradeBaseEntity`, `PrivacyTradeBaseOrderEntity`
- **주문/체결 수량** (단건 거래 수량): `quantity` — `Order`, `PlannedOrder`, `Execution`, `DailyTransaction`
- `qty` 사용 금지 (DB 컬럼/Java 필드/JSON 키 모두)
- DB 컬럼: `cycle_position.holdings`, `privacy_trade_bases.holdings`(보유) / `orders.quantity`, `privacy_trade_base_orders.quantity`(주문, nullable — FIDA 수신 시 수량 미확정 케이스 허용)
- KIS 어댑터 내부 record: `@JsonProperty` 값(KIS API 키)은 유지, Java 필드명만 의미 명료화 완료 (예: `cblcQty`→`balanceQuantity`)
- 복합 수량 필드: `orderedQty`/`filledQty` 패턴 금지 → `orderedQuantity`/`filledQuantity`
- `InfinitePosition.calcXxxQuantity()` 메서드명은 "주문 수량 계산 결과"이므로 Quantity 유지 (보유수량 아님)

### AES-256 암호화 컬럼 크기
- AES-256 CBC 암호화 + Base64 인코딩 시 입력 ~180자 → 출력 ~260자 — VARCHAR(255) 초과로 `DataIntegrityViolationException` 발생
- 암호화 저장 컬럼은 반드시 VARCHAR(512) 이상 — `AccountEntity`: account_no/app_key/secret_key/telegram_bot_token 모두 512
- 새 암호화 컬럼 추가 시 length=512로 선언, Flyway도 동일하게

### User nested enum 패턴
- `User.UserRole`, `User.UserStatus`, `User.NotificationChannel` — 독립 enum 파일 금지, `User` record 내 nested enum으로 선언
- import: `import com.kista.domain.model.user.User.NotificationChannel` (독립 파일 `NotificationChannel.java` 삭제됨)
- 신규 유저 기본 알림 채널: `User.DEFAULT_CHANNEL = NotificationChannel.NONE` (domain 상수 — 서비스/컨트롤러에서 직접 `NotificationChannel.NONE` 하드코딩 금지)
- 새 사용자 관련 enum 추가 시 동일 패턴으로 `User` record 내부에 선언

### 도메인 Command 명명 규칙
- 도메인 포트 인바운드 파라미터/입력 모델: `*Command` suffix (예: `FidaOrderCommand`)
- `*Request` suffix 사용 금지 — 외부 HTTP DTO 성격으로 오인됨
- `domain/model/<도메인>/` 하위 위치 (ArchUnit domain 규칙 준수)

### Ticker enum (Strategy.Ticker nested enum)
- **import 경로**: `import com.kista.domain.model.strategy.Strategy.Ticker;`
- `Strategy.Ticker` enum: TQQQ/SOXL/USD/MAGX/FNGU/BULZ — `targetProfitRate`/`description` 필드만 보유. KIS 거래소 코드(OVRS_EXCG_CD/EXCD)는 `Ticker`가 아닌 `KisExchangeRegistry`(adapter/out/kis)가 매핑 관리 — 새 종목 추가 시 양쪽 모두 갱신 필요
- PRIVACY 전략: 항상 `Strategy.Ticker.SOXL` 강제 (`Strategy.Type.resolveTicker()`)
- `Strategy.Ticker.tryParse(String)` — KIS 응답 종목코드 안전 변환 (`Optional` 반환)
- **Account에서 ticker 제거됨** — 매매 시 `strategy.ticker()` 사용


### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**
- 사이클 단위 순차 실행: `StrategyPort.findAllActive()` → `executeBatch(contexts)` 1회 호출 — context 빌드 실패 사이클은 스케쥴러에서 skip, 실행 중 실패 격리는 `TradingService.executeBatch()` 내부에서 처리

### JPA 설정
- `@ManyToOne`에 `@JoinColumn(name="...", nullable=false)` 항상 명시 — 생략 시 Hibernate 기본 추론(`필드명_id`)에 의존 → 네이밍 전략 변경 시 운영 이슈
- IDE 경고 "열을 해결할 수 없습니다" — Flyway 미적용 상태의 false positive. `compileJava BUILD SUCCESSFUL`이 실제 검증 기준
- **`BaseAuditEntity` vs `BaseCreatedAtEntity`**: `createdAt`+`updatedAt` 필요 시 `BaseAuditEntity` 상속, `createdAt`만 필요 시 `BaseCreatedAtEntity` 상속 — `updated_at` 컬럼 없는 엔티티에 `BaseAuditEntity` 사용 금지 (`ddl-auto: validate` 실패)

### Java Enum ↔ DB 컬럼 매핑 규칙 (전 프로젝트 통일)
- **DB 컬럼**: PostgreSQL 네이티브 ENUM (`CREATE TYPE ... AS ENUM`) **사용 금지** — VARCHAR(20) 사용
- **JPA 매핑**: `@Enumerated(EnumType.STRING)` 단독 사용 — `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 사용 금지
- **Flyway**: `CREATE TYPE` 구문 작성 금지, 컬럼 정의는 `VARCHAR(20)` (값 길이 여유 있게)

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
averagePrice (holdings==0이면 prevClosePrice 전일종가)
purchaseAmount = averagePrice × holdings
totalAssets = usdDeposit + purchaseAmount
unitAmount = totalAssets ÷ 20  (scale=2, HALF_UP)
currentRound = holdings==0 ? 0.0 : purchaseAmount ÷ unitAmount  (double, 소수점 허용)
priceOffsetRate = targetProfitRate × (1 - 2×currentRound/20)  (scale=2, HALF_UP)
referencePrice = averagePrice × (1 + priceOffsetRate)  (scale=2, HALF_UP — LOC 주문 가격 기준)
targetPrice = averagePrice × (1 + targetProfitRate)  (scale=2, HALF_UP)
```
- `usdDeposit` = 통합주문가능금액 (KIS `TTTC2101R` `itgr_ord_psbl_amt`, 미국 행 필터링) — 원화 자동 환전 포함, totalAssets 계산에 사용
- `currentRound`는 floor 없이 소수점 허용
- **전반/후반 분기**: `priceOffsetRate > 0` → 전반, `≤ 0` → 후반 (수학적으로 currentRound < 10 / currentRound ≥ 10과 동치)
- **전반**: LOC 매수①(unitAmount/2/averagePrice, 평단가) + LOC 매수②((unitAmount − averagePrice×매수①수량)×(1+priceOffsetRate)/referencePrice, 기준가) + LOC 매도(holdings/4, referencePrice+0.01) + 지정가 매도(holdings-holdings/4, targetPrice)
- **후반 unitAmount>usdDeposit**: MOC 매도(holdings/4)만 / **후반 unitAmount≤usdDeposit**: LOC 매수(unitAmount/referencePrice, referencePrice) + LOC 매도 + 지정가 매도

### KIS 계좌번호 DB 저장 방식
- 계좌번호는 `accounts.account_no` (8자리, AES-256 암호화) + `accounts.kis_account_type` (평문 `"01"`) 으로 분리 저장
- KIS API 호출: `CANO = account.accountNo()`, `ACNT_PRDT_CD = account.kisAccountType()`
- `74420614-01` 형태로 하나의 필드에 합치면 KIS API CANO 파라미터 오류 — 반드시 분리

### Flyway
- `V1__init.sql` **수정 금지** — 새 마이그레이션은 기존 최신 버전 다음 번호로 (`ls src/main/resources/db/migration`로 확인)
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화
- **Entity ↔ Flyway 크로스체크 필수**: Entity의 `nullable`, `length`, `precision`, `scale`, `columnDefinition` 변경 시 해당 컬럼을 생성/변경한 Flyway SQL과 반드시 대조. `ddl-auto: validate`는 컬럼 타입·`precision`·`scale` 불일치를 부팅 시 즉시 `SchemaManagementException`으로 잡음. `NOT NULL` 등 제약 불일치만 런타임까지 무증상 → 실제 null 삽입 시 `DataIntegrityViolationException` (`avg_price` 사례, V1/V5/V7)
- **`@Column(scale)` 주의**: DDL 힌트일 뿐, INSERT/UPDATE 시 Hibernate가 BigDecimal을 자동 반올림하지 않음. PostgreSQL이 컬럼 타입(`NUMERIC(12,2)`)에 맞춰 INSERT 시 반올림. 단 JPA 1차 캐시에는 원본 scale의 BigDecimal이 유지됨 — `@Transactional` 내 저장 직후 읽으면 DB 반올림 전 값 반환
- PostgreSQL `ADD COLUMN`은 항상 맨 뒤에 추가 (`AFTER` 절 없음) — 컬럼을 특정 위치에 두려면 테이블 재생성 방식 사용 (`CREATE TABLE _new + INSERT SELECT + DROP + RENAME`)
- 재생성 패턴에서 **명시적으로 이름 붙인 제약조건** (`CONSTRAINT foo UNIQUE (...)`) 주의: 테이블 리네임 후 `_old`에 제약조건명이 남아 새 테이블 CREATE 시 충돌 → `ALTER TABLE xxx_old DROP CONSTRAINT foo;`를 RENAME 직후·CREATE 전에 추가 필수 (`uq_privacy_trade_bases_date_ticker` 같은 named UNIQUE). unnamed `UNIQUE`는 PostgreSQL이 자동으로 충돌 없는 이름 생성하므로 해당 없음
- 컬럼 타입 변경 시 `USING` 캐스팅 필수 — `ALTER TABLE t ALTER COLUMN c TYPE VARCHAR(20) USING c::text` (미작성 시 오류)
- **컬럼 순서는 Entity 필드 선언 순서와 반드시 일치** — 테이블 재생성 시 SQL `CREATE TABLE` 컬럼 순서를 Entity 필드 선언 순서에 맞춰 작성할 것 (불일치 시 코드 리뷰 혼란 및 향후 마이그레이션 추적 오류 유발)
- **컬럼 순서 규칙 (모든 테이블 공통)**: `pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at` — 감사·삭제 컬럼은 반드시 이 순서로 맨 뒤에 위치 (없는 컬럼은 생략). 신규 컬럼은 항상 `created_at` 앞에 추가. `ADD COLUMN`이 맨 뒤에 붙으므로 위치 강제가 필요하면 테이블 재생성 패턴 사용 (V3 `orders`, V6 `strategy_cycle` 사례)
- Java 코드만 삭제해도 DB 테이블은 자동 제거 안 됨 — 미사용 테이블은 신규 마이그레이션으로 `DROP TABLE IF EXISTS`
- **FK 추가 시 `ON DELETE CASCADE` 여부 반드시 명시** — 기본값 `ON DELETE RESTRICT` → 부모 레코드 삭제 시 FK 위반 유발
- Flyway checksum mismatch (로컬 마이그레이션 파일 수정 시): `DELETE FROM flyway_schema_history WHERE version = 'N'` + 해당 테이블 DROP → 앱 재시작 (로컬 전용 — 운영 DB에 절대 적용 금지)

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도


### 텔레그램 로컬 테스트
- `api.telegram.org:443` TCP가 ISP 레벨에서 차단될 수 있음 (ping은 성공해도 curl 타임아웃)
- 로컬에서 `curl .../sendMessage` 테스트 시 VPN 필요
- 로컬 Docker에서 Telegram 인바운드(버튼 클릭 callback_query) 동작 불가 — Telegram 서버가 localhost 미접근
- 로컬 승인 방법: `POST /api/auth/dev-approve/{userId}` (`DevAuthController`, `@Profile("local")` 전용)
  - `curl -s -X POST http://localhost:8080/api/auth/dev-approve/<UUID>`
  - UUID 확인: `docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "SELECT id, nickname, status FROM users ORDER BY created_at DESC LIMIT 5;"`

### Adapter 내부 중첩 타입 접근 제어자
- 같은 패키지 테스트에서 참조하려면 `private record` 금지 — `record`(package-private)으로 선언해야 `Outer.Inner.class` 매처 사용 가능
- 예: `KisConnectionTestAdapter.TokenCheckResponse`, `KisOrderApi.OrderResponse` 패턴
- `private record`를 유지하면서 테스트에서 response 타입을 `any(Class.class)` 매처로 우회하면 타입 안전성 저하 → package-private 선언 권장

### Lombok 패턴
- `RestTemplate` 빈이 여러 개(`kisRestTemplate`, `telegramRestTemplate`)이므로 필드명을 빈 이름과 반드시 일치 — 불일치 시 `NoUniqueBeanDefinitionException`

### AES-256 암호화 위치
- KIS 자격증명·계좌번호·텔레그램 봇 토큰은 **persistence adapter 경계에서만** 암호화/복호화
- `AccountPersistenceAdapter`가 `AesCryptoService` 주입받아 처리 — `AccountService`(application layer)는 평문만 다룸
- ArchUnit 규칙(application → adapter 금지)으로 서비스가 암호화 서비스 직접 호출 불가
- 신규 환경변수: `AES_ENCRYPTION_KEY` (Base64 32바이트)

### TelegramApiClient package-private 제약
- `TelegramApiClient` (`adapter/in/telegram/`)는 package-private → application layer나 다른 패키지에서 직접 참조 불가
- 사용자 고유 botToken으로 Telegram API 호출이 필요하면: `domain/port/out/` 포트 + `adapter/out/notify/` 어댑터 신규 생성 패턴 (예: `TelegramBotInfoPort` + `TelegramBotInfoAdapter`)
- 기존 `telegramRestTemplate` 빈 재사용 가능 (필드명 일치시키면 자동 주입)

### Spring Security Filter 이중 등록 방지
- `@Component` Filter + `addFilterBefore()` 조합 시 `FilterRegistrationBean.setEnabled(false)` 필수 (이중 실행 방지)
- `SecurityConfig`에 새 Filter 추가 시 `@Import(SecurityConfig.class)` 사용하는 **모든** `@WebMvcTest`에도 해당 Filter `@Import` 필수 — 누락 시 `NoSuchBeanDefinitionException` → 다른 테스트까지 `IllegalStateException` 전파

### 서버 간 내부 인증 (InternalTokenAuthFilter)
- `/api/internal/**` 경로: `X-Internal-Token` 헤더 검증 — 환경변수 `INTERNAL_API_TOKEN` 값과 일치해야 통과 (미설정 시 항상 401)
- `SecurityConfig`: `/api/internal/**` → `hasRole("INTERNAL")`, `InternalTokenAuthFilter` JWT 필터보다 먼저 실행
- `@WebMvcTest`에서 `/api/internal/**` 경로 테스트: `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})` + `@TestPropertySource(properties = "internal.api.token=test-token")` + `.header("X-Internal-Token", "test-token")` 패턴 (`FidaOrderControllerTest` 참고)

### 소유권 검증 예외 패턴 (V2)
- Service 내 반복 검증은 `private Account requireOwnedAccount(UUID accountId, UUID requesterId)` 헬퍼로 추출 — `AccountStatisticsService` 패턴 참고
- Service에서 소유권 위반 시 `SecurityException`(Java 내장 unchecked) throw → `GlobalExceptionHandler`가 403 자동 처리
- KIS API 오류: Service에서 `KisApiException` 그대로 전파 → `GlobalExceptionHandler`가 503 자동 처리
- `InvalidKisKeyException`(domain/model/) → `GlobalExceptionHandler`가 422 자동 처리
- Controller에 try/catch 추가 금지 — `ResponseStatusException` 등 Spring HTTP 클래스는 application layer 사용 불가 (ArchUnit 규칙)

### @EnableJpaAuditing 위치
- `@SpringBootApplication` 아닌 별도 `JpaAuditingConfig.java` (`adapter/out/persistence/`)에 선언 — 아니면 `@WebMvcTest` `BeanCreationException` 발생

### Lombok @MappedSuperclass 상속 주의
- `@MappedSuperclass` 부모 클래스 필드의 getter/setter는 서브클래스의 `@Getter`/`@Setter`로 생성되지 않음
- `BaseAuditEntity` 같은 공통 엔티티 부모에 직접 `@Getter @Setter(AccessLevel.PACKAGE)` 추가 필요
- `@Setter(AccessLevel.PACKAGE)` 범위는 **선언 클래스 패키지 기준** — `BaseAuditEntity`(`adapter.out.persistence`)의 setter는 하위 패키지(`adapter.out.persistence.account` 등)에서 접근 불가. 서브패키지 어댑터/테스트에서 `setCreatedAt()`/`setUpdatedAt()` 호출 시 컴파일 오류 발생

### 자체 JWT 인증 (ECC P-256)
- `JwtIssuerService`: `jwt.signing-key` EC P-256 JWK로 ES256 JWT 발급 (AT TTL: `TokenConstants.AT_TTL` = 1일, `domain/model/auth/`)
- `JwtAuthFilter`: Bearer 토큰 추출 → `JwtDecoder`(NimbusJwtDecoder) 검증 → principal UUID 설정 (log.warn 실패 시)
- `JwtDecoderConfig`: 단일 빈, 프로파일 분기 없음 — `${jwt.signing-key}` 공개키만으로 검증
- **`JwtDecoder` @Bean은 반드시 `JwtDecoderConfig.java`에 분리** — `SecurityConfig`에 두면 `JwtAuthFilter` 순환 참조로 `APPLICATION FAILED TO START`
- 로컬 EC 키: `application-local.yml`의 `jwt.signing-key` (gitignored, Edit 도구로 직접 수정)
- 환경변수: `JWT_SIGNING_KEY` (EC P-256 JWK JSON), `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`(선택)
- 의존성: `spring-boot-starter-oauth2-resource-server` 필요 (`NimbusJwtDecoder`, `nimbus-jose-jwt` 포함)

### 주석 규칙
- 신규 코드 작성 시 주석을 함께 작성할 것
- 필드: `// 역할 한 줄` 인라인 주석
- 비즈니스 로직 블록 직전: 단계 설명 한 줄
- API 상수/코드값: `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용

### CORS (SecurityConfig)
- SecurityConfig에 `.cors(cors -> cors.configurationSource(...))` 필수 — 미설정 시 Vercel 브라우저 fetch 전체 차단
- 허용 origin: `CORS_ALLOWED_ORIGINS` 환경변수 (Fly.io secrets), 기본값 `http://localhost:3000`
- `CORS_ALLOWED_ORIGINS` 쉼표 구분, 각 origin 앞뒤 공백 자동 trim — `http://localhost:3000, http://127.0.0.1:3000`처럼 공백 포함 작성 가능
- `corsConfigurationSource()`: allowedMethods=GET/POST/PUT/**PATCH**/DELETE/OPTIONS (PATCH 미포함 시 전략중지/재개 등 PATCH 엔드포인트 403), allowedHeaders=Authorization/Content-Type, allowCredentials=true
- **`SecurityConfig`에 `.exceptionHandling()` + `authenticationEntryPoint` 반드시 설정** — 미설정 시 `Http403ForbiddenEntryPoint` 기본 적용 → 인증 실패가 401 대신 403 반환
- **`JwtAuthFilter` catch 절은 `Exception`으로** — `JwtException`만 잡으면 `UUID.fromString(jwt.getSubject())`의 NPE·IAE 미처리 → 인증 미설정 → 익명 사용자 → 403

### Telegram Webhook 등록
- `/telegram/webhook` 엔드포인트가 있어도 `setWebhook` API 미호출 시 버튼 클릭(callback_query) 이벤트 미수신
- 등록: `curl -X POST "https://api.telegram.org/bot{TOKEN}/setWebhook" -d '{"url":"https://kista-api.fly.dev/telegram/webhook"}'`
- 배포 URL 변경 시 재등록 필요

### @Transactional 내부 외부 시스템 호출 금지
- RestTemplate(텔레그램, KIS 등) 호출을 @Transactional 내부에서 하면 롤백 시에도 취소 불가 → 중복 알림 등 부작용
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)` 사용
- 도메인 이벤트 클래스 위치: `application/event/` — `TradingCyclePausedEvent`, `TradingCycleResumedEvent`, `NewUserRegisteredEvent`
- 리스너 위치: `adapter/out/` — ArchUnit 규칙상 adapter.out → application 의존 허용



### ArchUnit 규칙 예외 (adapter.out)
- `adapter.out → application` 의존 허용 — TelegramAdapter(adapter.out)에서 NewUserRegisteredEvent(application.service) 참조 가능

### 도메인 포트 인터페이스와 타입 위치 규칙
- `domain/port/in` 또는 `domain/port/out` 인터페이스의 파라미터·반환 타입으로 쓰이는 record/class는 반드시 `domain/model/` 하위에 위치 — `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
- `application/service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙)
- 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import

### 공유 DTO @Valid 제약
- `AccountRequest`는 register/update 공용 — `@Valid` 추가 시 `@NotNull strategyType`이 update에도 강제됨 (Breaking Change)
- register에만 필수인 필드는 `@NotNull` + register 메서드에만 `@Valid` 적용, update는 `@Valid` 없이 유지
- `AccountService.update()`는 strategyType 변경 지원 — null 전달 시 기존값 유지, PRIVACY 선택 시 ticker는 SOXL 강제 (register와 동일 규칙)

### 테이블 재생성 패턴 FK 제약명 주의
- `ALTER TABLE t RENAME TO t_old` 후 `CREATE TABLE t (...REFERENCES ...)` 인라인 선언 시 PostgreSQL이 `t_old`의 기존 제약명과 충돌 → 자동으로 숫자 접미사(`_fkey1`) 부여
- 인라인 `REFERENCES` 대신 `CONSTRAINT 명시_fkey FOREIGN KEY (...)` 형식 사용 권장 — 명시적 이름으로 충돌 없이 생성됨
- 기존 접미사 제약 정리: `ALTER TABLE t RENAME CONSTRAINT old_fkey1 TO old_fkey;` 후 다음 마이그레이션에서 정상 이름으로 참조 가능
- 실제 제약명 확인: `SELECT conname FROM pg_constraint WHERE conrelid = 'table'::regclass AND contype = 'f';`
- **PK 인덱스도 Postgres가 자동 리네임**(`t_pkey` → `t_old_pkey`) — RENAME 후 수동 `ALTER INDEX t_pkey ...` 호출 시 "relation does not exist" 오류 (V16 운영 배포 실패 사례, 6fdc65d). 별도 ALTER INDEX 불필요, 새 테이블 CREATE 시 자동으로 새 `t_pkey` 생성됨

### .env 파일 멀티라인 값 금지
- JSON 환경변수(예: `FIREBASE_SERVICE_ACCOUNT_JSON`)는 반드시 한 줄로 직렬화 — `.env` 파서는 줄바꿈을 값 끝으로 인식, 첫 줄 이후 무시됨
- 변환: `python3 -c "import json; content=open('.env.prod').read(); start=content.index('KEY=')+4; print(json.dumps(json.loads(content[start:].strip()), separators=(',',':')))"`

### ADMIN 권한 관리
- `users.role` VARCHAR(20) (`USER` / `ADMIN`) — 네이티브 ENUM 아님
- ADMIN seed: 환경변수 `ADMIN_KAKAO_IDS` (쉼표 구분 String) — `UserService.register()` / `KakaoLoginService.login()`에서 idempotent promote
- JWT claim: `"role": "ADMIN"` — `JwtIssuerService.issue(uuid, role)`
- `JwtAuthFilter`: `ROLE_USER` / `ROLE_ADMIN` authorities 자동 부여
- `/api/admin/**` → `hasRole("ADMIN")` (SecurityConfig)
- `audit_logs`: 관리자 액션 영구 기록 (admin_id, action, target_type, target_id, payload JSONB)
- 로컬: `POST /api/auth/dev-admin-token` → 고정 UUID `...002` ADMIN 자동 발급

### tradeDate 변환 정책 (KST 코드 ↔ UTC=US 거래일 DB)
- 도메인 `tradeDate`(LocalDate): **KST 일자** — 예: KST 2026-05-27 04:30 매매 → KST `2026-05-27`
- DB `trade_date` 컬럼: **UTC 일자 = US 거래일** — 예: ET 2026-05-26 거래일 → DB `2026-05-26`
- 변환: `com.kista.common.TradeDateConverter.toUtc(KST)` → `-1일` / `toKst(UTC)` → `+1일` (KST 04:30 매매 기준 단순 ±1일 규칙)
- 적용 위치: `OrderPersistenceAdapter`, `PrivacyTradePersistenceAdapter` 의 toEntity/toDomain + LocalDate 파라미터 조회 메서드만 — JPA `@Converter` 자동 적용 금지 (가시성)
- FIDA 외부 입력: UTC 송신 → `FidaOrderService` 진입부에서 `toKst()` 변환 후 도메인 호출 (persistence가 다시 UTC로 변환하므로 원본 UTC 일자가 DB에 정확히 저장됨)
- 인라인 `.minusDays(1)`/`.plusDays(1)` 직접 사용 금지 — 의미 추적을 위해 `TradeDateConverter` 헬퍼 경유 필수
- `com.kista.common` 패키지: 유틸리티 헬퍼 위치 (도메인 무관, 어댑터·서비스 공용)

### 소프트 삭제(Soft Delete) 패턴
- `users`, `accounts`, `strategy`, `strategy_cycle`, `cycle_position` — `deleted_at` 컬럼, `@SQLRestriction("deleted_at IS NULL")` 선언
- **`nativeQuery = true` 쿼리는 `@SQLRestriction` 미적용** — `AND tc.deleted_at IS NULL` 수동 명시 필수 (`findAllActiveCycles` 등)
- Cascade 순서: 서비스 레이어에서 사이클 → 계좌 → 사용자 순으로 명시 처리 (DB FK CASCADE 미작동)
