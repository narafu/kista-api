## 핵심 제약 사항

### Git 규칙
- 커밋 메시지는 한글로 작성한다.
- Conventional Commit 접두사 사용: `feat(scope):`, `fix:`, `docs:`, `debug:` 등 + 명령형 제목

### domain/port/out/ 네이밍 규칙
- 모든 아웃바운드 포트 인터페이스: `*Port` 접미사 사용 (예: `UserPort`, `AccountPort`, `StrategyPort`)
- Spring Data JPA 인터페이스는 `*JpaRepository` (adapter 레이어) — `domain/port/out/`와 완전히 다른 계층
- `*Repository` 접미사 사용 금지 — JpaRepository와 혼동 유발

### adapter/out 간 JpaRepository 접근 제한
- `*JpaRepository`는 package-private — 선언 패키지 외부에서 직접 import 시 컴파일 오류
- 다른 패키지 adapter에서 DB 조작이 필요하면 도메인 포트(`domain/port/out/`) 경유 필수
- 패턴: `AlpacaCalendarAdapter`(adapter.out.alpaca) → `MarketHolidayStorePort`(domain.port.out) → `MarketCalendarPersistenceAdapter`(persistence.calendar)
- 참고: `KisAuthApi`(adapter.out.kis) → `BrokerTokenCachePort` → `KisTokenPersistenceAdapter`(persistence.kistoken)


### GlobalExceptionHandler 자동 예외 처리
- Controller에서 별도 catch/rethrow 불필요 — 아래 예외는 모두 `GlobalExceptionHandler`에서 자동 처리됨
- `NoSuchElementException` → 404, `IllegalArgumentException` → 400, `IllegalStateException` → 400, `PrivacyTradeConflictException` → 409
- `SecurityException` → 403, `KisApiException` → 503, `TossApiException` → 503, `Account.InvalidBrokerKeyException` → 422
- `ManualTradingException` / `OrderCancelException` → 409, `Account.DuplicateAccountException` → 409
- `User.CooldownException` → 429(Retry-After 포함, 재신청 쿨다운), `Account.KisRateLimitException` → 429
- `InvalidRefreshTokenException` → 401 (RT 재사용·만료·위변조 시)

### Account ↔ Strategy 분리
- `Account` record 필드 9개: `id, userId, nickname, accountNo, appKey, secretKey, brokerAccountCode, broker, createdAt` — type/status/ticker/multiple/updatedAt 없음 (updatedAt은 persistence 레이어 `BaseAuditEntity`가 관리; `createdAt`은 신규 등록 시 null, persistence 저장 후 채워짐)
- `Strategy` record 필드 6개: `id, accountId, type(Type), status(Status), ticker(Ticker), cycleSeedType(CycleSeedType)`
- `StrategyVersion` record 필드 5개: `id, strategyId, versionNo, createdAt, deletedAt` — 전략 설정 이력 부모
- `StrategyInfiniteDetail` record 필드 2개: `strategyVersionId, divisionCount`
- `StrategyVrDetail` record 필드 4개: `strategyVersionId, intervalWeeks, bandWidth, recurringAmount` — gradient()/poolLimitRate() 계산 메서드 제공
- `StrategyCycle` record 필드 9개: `id, strategyId, strategyVersionId, startAmount, endAmount, startDate, endDate, createdAt, deletedAt` — 실행된 사이클과 적용 버전 고정값
- `CyclePosition` record 필드 8개: `id, strategyCycleId, usdDeposit, closingPrice, avgPrice, holdings, createdAt, deletedAt` — 체결마다 append되는 공통 포지션 스냅샷
- `CyclePositionInfiniteDetail` record 필드 2개: `cyclePositionId, isReverseMode`
- `StrategyCycleVrDetail` record 필드 4개: `strategyCycleId, value, gradient, poolLimit` — 사이클 시작 시 VR 파라미터 스냅샷
- `StrategyDetail` record 필드 7개: `Strategy strategy, BigDecimal initialUsdDeposit, Integer divisionCount, boolean isReverseMode, Double currentRound, Integer currentHoldings, VrSummary vr` — 최신 사이클/활성 버전/최신 포지션을 합쳐 응답 조립 (`StrategyService.toDetail()`), `VrSummary`는 nested record (VR 외 타입은 null)
- `Type`, `Status`, `Ticker`, `CycleSeedType` 모두 `Strategy` record의 nested enum
- 계좌당 종목(ticker) 중복 등록 불가 — `StrategyPort.existsByAccountIdAndTicker(accountId, ticker)` (계좌당 여러 전략 등록 가능, 종목별 1개)
- `StrategyCycle.startAmount`: 사이클 시작 시드(시작금액); VR에서는 사이클 시작 pool(예수금) — 최신 포지션 `holdings=0`일 때만 `StrategyCyclePort.updateStartAmount()`로 in-place 갱신
- `cycleSeedType`: 사이클 종료 후 자동 재등록 정책 (DEFAULT `NONE`); **VR 전략은 NONE 강제** — 롤오버가 자체 사이클 교체 담당

### 잔고검증 토글 (UserSettings.balanceCheckEnabled)
- `UserSettings` aggregate(`domain/model/user/UserSettings.java`) — `User` record 아님
- ON이면 `StrategyService` 시드 등록/수정 시 "KIS 가용금액 − 기존 활성 전략 점유 시드" 한도 초과를 `IllegalArgumentException`으로 차단
- 설정 미존재 시 `UserSettings.defaultFor(userId)` — `balanceCheckEnabled=true`, 빈 notificationPrefs

### 스케쥴러 주문 예산 배정 (상세 규칙 SSOT → workflow.md)
- 예산 배정 우선순위·compute skip·실패 격리·수동 SELL 검증 등 실행 규칙 전체는 `docs/agents/workflow.md`가 SSOT — 매매·스케쥴러·주문 로직 작업 시 필수 Read
- `orders.order_leg`는 스케쥴러 내부 leg 식별자 — 신규 전략 주문은 non-blank concrete leg 필수(`UNKNOWN` 잔존 시 `TradingService`가 PLANNED 저장 전 `IllegalStateException`으로 거절), legacy 행은 `UNKNOWN` 유지, 브로커 API payload에 미포함
- 슬롯 점유 판단: concrete leg는 `timing + direction + orderLeg`, `UNKNOWN` legacy 행은 `timing + direction` coarse
- `V24__add_order_leg_and_order_indexes.sql`이 `orders.order_leg`와 scheduler/reservation 조회용 인덱스를 추가 — 후속 orders 쿼리 변경 시 인덱스 prefix와 조회 조건 함께 확인 (V23 번호 충돌로 병합 시 V24로 재번호됨)

### MetaController (enum SSOT)
- `GET /api/meta` — `MetaBundle` 단일 번들 (strategyTypes/tickers/brokers/strategyStatuses/cycleSeedTypes)
- enum에 label/description 포함 (DTO `from()` 팩토리) — UI에서 enum 리터럴 하드코딩 금지
- `TickerMeta`: `code`/`label`/`description`/`targetProfitRate` — KIS 거래소 코드는 `KisExchangeRegistry`(어댑터 내부) 전용, UI 미노출

### 파일 인코딩 주의 (BOM)
- 서브에이전트가 Java 파일 import 수정 시 BOM(`\xef\xbb\xbf`) 삽입 버그 발생 사례 → `compileJava` 즉시 실패
- 일괄 제거: `grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '1s/^\xef\xbb\xbf//' "$f"; done`

### 수량 변수명 규칙
- **보유 잔고 수량**: `holdings` (avgPrice와 짝), **주문/체결 수량**: `quantity` (단건 거래)
- `qty` 사용 금지 (DB 컬럼/Java 필드/JSON 키 모두)
- `privacy_trade_base_orders.quantity` — nullable (FIDA 수신 시 수량 미확정 허용)
- 복합 수량 필드: `orderedQuantity`/`filledQuantity` (`orderedQty`/`filledQty` 금지)

### AES-256 암호화 컬럼 크기
- AES-256 CBC 암호화 + Base64 인코딩 시 입력 ~180자 → 출력 ~260자 — VARCHAR(255) 초과로 `DataIntegrityViolationException` 발생
- 암호화 저장 컬럼은 반드시 VARCHAR(512) 이상 — `AccountEntity`: account_no/app_key/secret_key/telegram_bot_token 모두 512
- 새 암호화 컬럼 추가 시 length=512로 선언, Flyway도 동일하게

### User nested enum 패턴
- `User.UserRole`, `User.UserStatus`, `User.NotificationChannel` — 독립 enum 파일 금지, `User` record 내 nested enum으로 선언
- import: `import com.kista.domain.model.user.User.NotificationChannel`
- 신규 유저 기본 알림 채널: `User.DEFAULT_CHANNEL = NotificationChannel.NONE` (domain 상수 — 서비스/컨트롤러에서 직접 하드코딩 금지)

### 도메인 Command 명명 규칙
- 도메인 포트 인바운드 파라미터/입력 모델: `*Command` suffix (예: `FidaOrderCommand`)
- `*Request` suffix 사용 금지 — 외부 HTTP DTO 성격으로 오인됨
- `domain/model/<도메인>/` 하위 위치 (ArchUnit domain 규칙 준수)

### Ticker enum (Strategy.Ticker nested enum)
- import: `import com.kista.domain.model.strategy.Strategy.Ticker;`
- MAGX/USD/TQQQ/SOXL — KIS 거래소 코드는 `KisExchangeRegistry`(adapter/out/kis)가 관리, 새 종목 추가 시 양쪽 갱신 필수
- PRIVACY·VR 신규 등록 ticker는 `StrategyCreationSettings.ticker()`의 고정값 정책으로 결정되며, 다른 명시 입력은 거부한다 (기본값: PRIVACY=SOXL, VR=TQQQ)
- ticker는 `Account` 아닌 `strategy.ticker()` — 매매 시 strategy에서 참조


### Virtual Thread
- Virtual Thread 활성화됨 — `@Async`, `CompletableFuture` **사용 금지**, 대기 시 `Thread.sleep()` 사용

### JPA 설정
- `@ManyToOne`에 `@JoinColumn(name="...", nullable=false)` 항상 명시 — 생략 시 Hibernate 추론(`필드명_id`)에 의존

### Java Enum ↔ DB 컬럼 매핑 규칙 (전 프로젝트 통일)
- **DB 컬럼**: PostgreSQL 네이티브 ENUM (`CREATE TYPE ... AS ENUM`) **사용 금지** — VARCHAR(20) 사용
- **JPA 매핑**: `@Enumerated(EnumType.STRING)` 단독 사용 — `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 사용 금지
- **Flyway**: `CREATE TYPE` 구문 작성 금지, 컬럼 정의는 `VARCHAR(20)` (값 길이 여유 있게)

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
averagePrice (holdings==0이면 prevClosePrice 전일종가)
purchaseAmount = averagePrice × holdings
totalAssets = usdDeposit + purchaseAmount
unitAmount = totalAssets ÷ divisionCount  (scale=2, HALF_UP — 분모는 리터럴 20이 아닌 divisionCount; 허용값 20/30/40 — RuntimeSettings 기본값·capability 메타 availableDivisionCounts() 동기화됨, 기본값은 20)
currentRound = holdings==0 ? 0.0 : purchaseAmount ÷ unitAmount  (double, 소수점 허용)
priceOffsetRate = targetProfitRate × (1 - 2×currentRound/divisionCount)  (scale=2, HALF_UP)
referencePrice = averagePrice × (1 + priceOffsetRate)  (scale=2, HALF_UP — LOC 주문 가격 기준)
targetPrice = averagePrice × (1 + targetProfitRate)  (scale=2, HALF_UP)
```
- `usdDeposit` = 통합주문가능금액 (KIS `TTTC2101R` `itgr_ord_psbl_amt`, 미국 행 필터링) — 원화 자동 환전 포함, totalAssets 계산에 사용
- `currentRound`는 floor 없이 소수점 허용
- **전반/후반 분기**: `priceOffsetRate > 0` → 전반, `≤ 0` → 후반 (수학적으로 currentRound < divisionCount/2 여부와 동치)
- **전반**: LOC 매수①(unitAmount/2/averagePrice, 평단가) + LOC 매수②((unitAmount − averagePrice×매수①수량)×(1+priceOffsetRate)/referencePrice, 기준가) + LOC 매도(holdings/4, referencePrice+0.01) + 지정가 매도(holdings-holdings/4, targetPrice)
- **후반 unitAmount>usdDeposit**: MOC 매도(holdings/4)만 / **후반 unitAmount≤usdDeposit**: LOC 매수(unitAmount/referencePrice, referencePrice) + LOC 매도 + 지정가 매도

### VR 공식 (변경 금지 — 단위 테스트로 검증)
```
lowerBand = V × (1 − bandWidth/100)  (scale=2, HALF_UP)
upperBand = V × (1 + bandWidth/100)  (scale=2, HALF_UP)
buyPrice(m)  = lowerBand ÷ (holdings + m − 1)  (scale=2, HALF_UP, m=1..20, divisor<1이면 skip)
sellPrice(s) = upperBand ÷ (holdings − s + 1)  (scale=2, HALF_UP, s=1..20)

V' = V + pool/G + recurringAmount + (평가금 − V) / (2√G)  (scale=2 HALF_UP, 중간 scale=10)
     평가금 = holdings × 종가
```
- gradient G: `recurringAmount < 0` → 20(인출), 그 외 → 10 (`StrategyVrDetail.gradient()`)
- poolLimitRate: `recurringAmount > 0` → 0.75, `== 0` → 0.50, `< 0` → 0.25 (`StrategyVrDetail.poolLimitRate()`)
- 등록 검증: `initialValue`, `initialUsdDeposit`, `recurringAmount` null은 0으로 취급
- 적립식(`recurringAmount > 0`): 초기 V와 초기 시드가 모두 0이어도 등록 가능
- 거치식/인출식(`recurringAmount <= 0`): `initialValue + initialUsdDeposit > 0` 필수
- 인출식(`recurringAmount < 0`): `initialValue + initialUsdDeposit >= abs(recurringAmount) × 100 × (4 / intervalWeeks)` 필수
- 첫 사이클 poolLimit: (`initialValue` + `initialUsdDeposit`) × `poolLimitRate()`; 이후 롤오버 poolLimit은 기존처럼 USD pool × `poolLimitRate()`
- 첫 사이클 bootstrap(`initialValue`=기존 TQQQ 평가금, `initialUsdDeposit`=초기 USD pool): V만 있으면 poolLimit LOC+AT_CLOSE 분할매도, pool만 있으면 poolLimit LOC+AT_CLOSE 분할매수 — 각각 poolLimit 금액을 남은 거래일로 분할, 적립식 V=0/pool=0이면 due date 당일 recurringAmount LOC+AT_CLOSE 매수
- bootstrap LOC 가격: 매수 `currentPrice × 1.10`, 매도 `currentPrice × 0.90`; 주문 수량은 예산/가격 내림 정수
- 사다리 병합: 동일 가격 연속 rung은 수량 병합(매수), 매도는 holdings>20이면 마지막 단(s=20)에 잔여 전량
- 가격 캡: `buyPrice > currentPrice × 1.10` 이면 cap 가격으로 교체 — scale=2 HALF_UP (currentPrice=null이면 미적용)
- rollover due 조건: `cycle.startDate() + intervalWeeks ≤ today` (당일 포함)
- V′ ≤ 0이면 롤오버 보류 — 사이클 유지, 관리자·사용자 알림
- 단, 적립식 bootstrap 매수 실패(`recurringAmount>0`, 기존 V=0, holdings=0)는 V=0 새 사이클로 롤오버해 다음 due date에 다시 recurringAmount LOC 매수를 시도

### 계좌번호 마스킹 (AccountNumberMasker)
- `domain/model/account/AccountNumberMasker.mask(accountNo)` — 계좌번호 마스킹 단일 알고리즘(SSOT). 숫자 이외 문자 전부 제거 후 마지막 4자리만 노출(`"****1234"`)
- KIS(하이픈 1개)·TOSS(하이픈 2개) 포맷 모두 대응 — 하이픈 위치별 개별 마스킹을 DTO 3곳에 중복 구현하던 방식은 부분 노출 결함으로 폐기됨
- 신규 DTO에서 계좌번호 마스킹이 필요하면 반드시 이 유틸을 재사용 — 개별 `substring`/`replace` 마스킹 로직 신규 작성 금지

### 상태 종속 민감 필드 마스킹 패턴 (rejectReason 사례)
- `User.rejectReason`(반려 사유, REJECTED 상태에서만 의미) — `UserResponse.from()`에서 `user.status() == REJECTED`일 때만 노출, 그 외 상태는 `null` 강제
- 특정 상태에서만 유효한 민감 필드는 DB엔 그대로 보존하되, 응답 DTO의 `from()` 팩토리에서 상태 조건부로 마스킹 — 필드 자체를 지우지 않고 응답 시점에만 걸러내는 방식 재사용

### KIS 계좌번호 DB 저장 방식
- 계좌번호는 `accounts.account_no` (AES-256 암호화) + `accounts.broker_account_code` (KIS: null, TOSS: accountSeq) 저장
- KIS API 호출: `KisHttpClient.splitAccountNo(account.accountNo())` → `[CANO, ACNT_PRDT_CD]` — `-` 기준 분리, 구분자 없으면 `ACNT_PRDT_CD="01"` 기본값
- `account.accountNo()`에 `"74420614-01"` 형태로 저장된 경우 split 결과 `["74420614","01"]`; `"74420614"` 8자리만 저장된 경우 기본 `"01"` 사용

### Flyway
- 운영 DB에 **이미 적용된 마이그레이션 파일은 절대 수정 금지** (V1 포함 전체 버전) — Flyway 체크섬 불일치로 앱 기동 즉시 크래시. 새 마이그레이션은 기존 최신 버전 다음 번호로 (`ls src/main/resources/db/migration`로 확인) [V12 수정→운영 크래시 사례]
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화
- **Entity ↔ Flyway 크로스체크 필수**: Entity의 `nullable`, `length`, `precision`, `scale` 변경 시 Flyway SQL과 반드시 대조. `ddl-auto: validate`는 타입 불일치를 부팅 시 즉시 `SchemaManagementException`으로 잡음. `NOT NULL` 불일치만 런타임 무증상 → 실제 null 삽입 시 `DataIntegrityViolationException`
- **`@Column(scale)` 주의**: DDL 힌트일 뿐, JPA 1차 캐시에는 원본 BigDecimal 유지 — `@Transactional` 내 저장 직후 읽으면 DB 반올림 전 값 반환
- PostgreSQL `ADD COLUMN`은 항상 맨 뒤 — 특정 위치 강제는 테이블 재생성 패턴 사용 (`CREATE TABLE _new + INSERT SELECT + DROP + RENAME`)
- 재생성 패턴에서 named UNIQUE 제약 주의: `ALTER TABLE xxx_old DROP CONSTRAINT foo;`를 RENAME 직후·CREATE 전에 추가 필수 (unnamed UNIQUE는 Postgres가 자동으로 새 이름 생성)
- **컬럼 순서 규칙**: `pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at` 순서 고정. Entity 필드 선언 순서와 반드시 일치
- Java 코드만 삭제해도 DB 테이블은 자동 제거 안 됨 — 미사용 테이블은 신규 마이그레이션으로 `DROP TABLE IF EXISTS`
- **FK 추가 시 `ON DELETE CASCADE` 여부 반드시 명시** — 기본값 `ON DELETE RESTRICT`
- Flyway checksum mismatch (로컬 파일 수정 시): `DELETE FROM flyway_schema_history WHERE version = 'N'` + 해당 테이블 DROP → 앱 재시작 (로컬 전용)

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도


### 텔레그램 로컬 테스트
- `api.telegram.org:443` TCP가 ISP 레벨에서 차단될 수 있음 — 로컬 `curl .../sendMessage` 테스트 시 VPN 필요
- 로컬 Docker에서 Telegram 인바운드(callback_query) 동작 불가 — Telegram 서버가 localhost 미접근
- 로컬 승인: `curl -s -X POST http://localhost:8080/api/auth/dev-approve/<UUID>` (`DevAuthController`, `@Profile("local")` 전용)

### Adapter 내부 중첩 타입 접근 제어자
- 같은 패키지 테스트에서 참조하려면 `private record` 금지 — `record`(package-private)으로 선언해야 `Outer.Inner.class` 매처 사용 가능
- 예: `KisAuthApi.TokenCheckResponse`, `KisOrderApi.OrderResponse` 패턴
- `private record`를 유지하면서 테스트에서 response 타입을 `any(Class.class)` 매처로 우회하면 타입 안전성 저하 → package-private 선언 권장

### Lombok 패턴
- `RestTemplate` 빈이 여러 개(`kisRestTemplate`, `telegramRestTemplate`)이므로 필드명을 빈 이름과 반드시 일치 — 불일치 시 `NoUniqueBeanDefinitionException`

### AES-256 암호화 위치
- KIS 자격증명·계좌번호·텔레그램 봇 토큰은 **persistence adapter 경계에서만** 암호화/복호화 (ArchUnit: application → adapter 의존 금지)

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

### @EnableJpaAuditing 위치
- `@SpringBootApplication` 아닌 별도 `JpaAuditingConfig.java` (`adapter/out/persistence/`)에 선언 — 아니면 `@WebMvcTest` `BeanCreationException` 발생

### Lombok @MappedSuperclass 상속 주의
- `@MappedSuperclass` 부모 필드의 getter/setter는 서브클래스 `@Getter`/`@Setter`로 생성되지 않음 → 부모 클래스에 직접 선언 필요
- `@Setter(AccessLevel.PACKAGE)` 범위는 **선언 클래스 패키지 기준** — `BaseAuditEntity`(`adapter.out.persistence`)의 setter는 하위 패키지(`adapter.out.persistence.account` 등)에서 접근 불가 (컴파일 오류)

### 자체 JWT 인증 (ECC P-256)
- `JwtIssuerService`: EC P-256 JWK → ES256 JWT 발급, `JwtAuthFilter`: principal을 `UUID` 타입으로 저장
- **`JwtDecoder` @Bean은 반드시 `JwtDecoderConfig.java`에 분리** — `SecurityConfig`에 두면 `JwtAuthFilter` 순환 참조로 기동 실패
- 환경변수: `JWT_SIGNING_KEY` (EC P-256 JWK JSON), `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`(선택)

### 주석 규칙
- 신규 코드 작성 시 주석을 함께 작성할 것
- 필드: `// 역할 한 줄` 인라인 주석
- 비즈니스 로직 블록 직전: 단계 설명 한 줄
- API 상수/코드값: `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용

### CORS (SecurityConfig)
- `CORS_ALLOWED_ORIGINS` 환경변수 (쉼표 구분), 기본값 `http://localhost:3000`
- allowedMethods에 **PATCH 필수** — 미포함 시 전략중지/재개 등 PATCH 엔드포인트 403
- **`SecurityConfig`에 `.exceptionHandling()` + `authenticationEntryPoint` 반드시 설정** — 미설정 시 인증 실패가 401 대신 403 반환
- **`JwtAuthFilter` catch 절은 `Exception`으로** — `JwtException`만 잡으면 NPE·IAE 미처리 → 익명 사용자 → 403

### Telegram Webhook 등록
- `/telegram/webhook` 엔드포인트가 있어도 `setWebhook` API 미호출 시 버튼 클릭(callback_query) 이벤트 미수신
- 등록: `curl -X POST "https://api.telegram.org/bot{TOKEN}/setWebhook" -d '{"url":"https://kista-api.fly.dev/telegram/webhook"}'`
- 배포 URL 변경 시 재등록 필요

### @Transactional 내부 외부 시스템 호출 금지
- RestTemplate(텔레그램, KIS 등)을 `@Transactional` 내부에서 호출 금지 — 롤백 시 취소 불가
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)`
- 이벤트 위치: `application/event/`, 리스너 위치: `adapter/out/` (ArchUnit: adapter.out → application 의존 허용)

### 도메인 포트 인터페이스와 타입 위치 규칙
- `domain/port/in` 또는 `domain/port/out` 인터페이스의 파라미터·반환 타입으로 쓰이는 record/class는 반드시 `domain/model/` 하위에 위치 — `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
- `application/service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙)
- 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import

### 공유 DTO @Valid 제약
- `AccountRequest`는 register/update 공용 — `@Valid` 추가 시 `@NotNull strategyType`이 update에도 강제됨 (Breaking Change)
- register에만 필수인 필드는 `@NotNull` + register 메서드에만 `@Valid` 적용, update는 `@Valid` 없이 유지
- `AccountService.update()`는 strategyType 변경 지원 — null 전달 시 기존값 유지, PRIVACY 선택 시 ticker는 SOXL 강제 (register와 동일 규칙)

### 테이블 재생성 패턴 FK 제약명 주의
- 인라인 `REFERENCES` 대신 `CONSTRAINT 명시_fkey FOREIGN KEY (...)` 형식 사용 — 제약명 충돌(`_fkey1` 숫자 접미사) 방지
- 실제 제약명 확인: `SELECT conname FROM pg_constraint WHERE conrelid = 'table'::regclass AND contype = 'f';`
- **PK 인덱스도 Postgres가 자동 리네임**(`t_pkey` → `t_old_pkey`) — RENAME 후 수동 `ALTER INDEX t_pkey ...` 호출 시 "relation does not exist" 오류 (운영 배포 실패 사례, commit 6fdc65d). 별도 ALTER INDEX 불필요, 새 테이블 CREATE 시 자동으로 새 `t_pkey` 생성됨

### .env 파일 멀티라인 값 금지
- JSON 환경변수(예: `FIREBASE_SERVICE_ACCOUNT_JSON`)는 반드시 한 줄로 직렬화 — `.env` 파서는 줄바꿈을 값 끝으로 인식, 첫 줄 이후 무시됨
- 변환: `python3 -c "import json; content=open('.env.prod').read(); start=content.index('KEY=')+4; print(json.dumps(json.loads(content[start:].strip()), separators=(',',':')))"`

### ADMIN 권한 관리
- ADMIN seed: `ADMIN_KAKAO_IDS` 환경변수 (쉼표 구분) — 로그인 시 idempotent promote
- `/api/admin/**` → `hasRole("ADMIN")`, `audit_logs`에 관리자 액션 영구 기록
- 로컬: `POST /api/auth/dev-admin-token` → 고정 UUID `...002` ADMIN 발급

### 런타임 설정 API 규칙
- `GET /api/runtime-config` → 로그인 전 UI가 가입·계좌·전략 생성 정책을 조회하는 공개 엔드포인트. 동적 설정이므로 `Cache-Control: no-store` 유지
- `GET|PUT /api/admin/settings` → ADMIN 전용. PUT은 auth/brokers/strategies 전체 설정을 검증한 뒤 한 번에 교체하며 부분 갱신 API로 취급하지 않음. 조회·갱신 응답 모두 `Cache-Control: no-store` 유지
- `brokers.<broker>.enabled=false`이면 해당 증권사의 신규 계좌 등록과 연결 테스트를 외부 API 호출 전에 400으로 차단. 기존 계좌의 조회·수정·매매는 영향받지 않음
- `StrategyService.register()`는 신규 전략에만 `strategies.<type>` 생성 정책을 적용: `enabled=false`면 400으로 차단하고, ticker·INFINITE divisionCount·VR recurringMode/bandWidth/intervalWeeks의 생략 기본값과 허용/고정값을 검증. 기존 전략 수정·실행에는 소급 적용하지 않음
- `RegisterStrategyCommand.divisionCount=0`은 INFINITE 신규 등록의 미입력 sentinel이며 런타임 기본값으로 치환. VR `recurringMode`는 `recurringAmount` 부호(DEPOSIT/HOLD/WITHDRAW)로만 검증하고 금액 크기는 기존 VR 자산 규칙에 맡김. `recurringMode.customizable=false` 설정은 기본값과 유일한 허용값이 모두 `HOLD`여야 함
- 런타임 설정 응답은 `NON_NULL` 직렬화 사용. 전략 유형에 적용되지 않는 field(예: PRIVACY의 `divisionCount`)는 `null`로 내리지 않고 JSON에서 생략
- `approvalRequired` 값이 `true → false`로 바뀌면 그 시점의 모든 PENDING 사용자를 기존 `UserUseCase.approve()` 흐름으로 활성화. 설정 갱신은 `RUNTIME_SETTINGS_UPDATE` 감사 로그 기록

### 시간 기준 정책 (KST 단일 기준)
- **거래일(tradeDate) = KST 일자** — 매매가 실행·정산되는 KST 아침이 속한 날. DB(`orders.trade_date`)·도메인·API 모두 동일 값, 변환 없음 (V28에서 US→KST shift 완료)
- **`privacy_trade_bases.release_date` = FIDA 발행일 원본(KST)** — 거래일 아님. 발행일↔거래일(+1일)은 `PrivacyDates.releaseDateFor()/tradeDateOf()` 업무 규칙 헬퍼만 사용
- **외부 원본 참조 데이터는 원본 기준 유지**: `us_market_holidays`(US 달력일), `market_index_prices`(US 거래일) — KST↔US 변환은 해당 어댑터 내부에서만 (`UsTradeDates.toUsTradeDate()/toKstTradeDate()`)
- `UsTradeDates` 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter` — 도메인·서비스·orders persistence에서 사용 금지
- **Toss API**: 주문 접수일(KST) 기준 — 변환 없음. `TossOrderApi.fetchExecutions()`는 전날 저녁 선접수 대응으로 `queryFrom = from - 1일` 조회 후 `filledAt`(KST) 재필터
- Instant ↔ KST 일자 경계는 `atStartOfDay(TimeZones.KST)` 단일 관용구 — `ZoneOffset.UTC` 자정 경계 금지
- 거래일 경계 시각: `DstInfo.SCHEDULER_RUN_TIME = 04:30 KST` (마감 배치 cron 발화와 동일) — preview·수동실행·주문취소가 `DstInfo.nextTradeDate()` SSOT 사용
- 인라인 `.minusDays(1)`/`.plusDays(1)`로 날짜 기준 변환 금지 — 반드시 `UsTradeDates`/`PrivacyDates` 경유

### 소프트 삭제(Soft Delete) 패턴
- `users`, `accounts`, `strategy`, `strategy_cycle`, `cycle_position`, `app_error_logs` — `deleted_at` 컬럼, `@SQLRestriction("deleted_at IS NULL")` 선언
- **`nativeQuery = true` 쿼리는 `@SQLRestriction` 미적용** — `AND tc.deleted_at IS NULL` 수동 명시 필수 (`findAllActiveCycles` 등)
- Cascade 순서: 서비스 레이어에서 사이클 → 계좌 → 사용자 순으로 명시 처리 (DB FK CASCADE 미작동)
