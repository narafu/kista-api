## 핵심 제약 사항

### domain/port/out/ 네이밍 규칙
- 모든 아웃바운드 포트 인터페이스: `*Port` 접미사 사용 (예: `UserPort`, `AccountPort`, `TradingCyclePort`)
- Spring Data JPA 인터페이스는 `*JpaRepository` (adapter 레이어) — `domain/port/out/`와 완전히 다른 계층
- `*Repository` 접미사 사용 금지 — JpaRepository와 혼동 유발

### RESTful API 설계 원칙

**URI 규칙**
- 리소스 식별: 명사 복수형 — `/accounts`, `/trading-cycles`, `/orders`
- 계층 표현: 소속 관계 `/accounts/{id}/trading-cycles` — 독립 리소스는 루트 수준
- 동사 URI 금지: `/getAccount`, `/createOrder` 형태 사용 불가 — HTTP 메서드로 표현
- 상태 전이 예외: `/trading-cycles/{id}/pause`, `/trading-cycles/{id}/resume` — PATCH + 서브 경로 패턴

**HTTP 메서드**
- `GET`: 안전·멱등 — 상태 변경 사이드이펙트 금지
- `POST`: 생성 (비멱등) — 응답 201 + `Location` 헤더
- `PUT`: 전체 교체 (멱등)
- `PATCH`: 부분 수정 — pause/resume/status 변경
- `DELETE`: 삭제 (멱등) — 성공 시 204 No Content

**HTTP 상태 코드 (이 프로젝트 매핑)**
- `200`: 조회·수정 성공
- `201`: 생성 성공 (`Location` 헤더 포함)
- `204`: 삭제 성공 (바디 없음)
- `400`: `IllegalArgumentException` — 잘못된 파라미터
- `401`: 인증 없음 (JWT 미포함·만료)
- `403`: `SecurityException` — 소유권 불일치·권한 없음
- `404`: `NoSuchElementException` — 리소스 없음
- `409`: `PrivacyTradeConflictException` — 중복·충돌
- `422`: `InvalidKisKeyException` — KIS 자격증명 검증 실패
- `429`: `CooldownException` — 재신청 쿨다운
- `503`: KIS API 오류 — 외부 서비스 불가

**응답 형식**
- 도메인 record 직접 반환 금지 → `XxxResponse.from(domain)` DTO 사용 (예외: `StatisticsController`는 KIS live 모델 그대로 반환 — kista-ui normalizer 필요)
- 오류 응답: `GlobalExceptionHandler`가 일관된 형식으로 처리 — 컨트롤러에서 별도 catch/rethrow 불필요

### GlobalExceptionHandler 자동 예외 처리
- `NoSuchElementException` → 404, `IllegalArgumentException` → 400, `PrivacyTradeConflictException` → 409 — Controller에서 별도 catch/rethrow 불필요 (단, `SecurityException`→403, KIS 오류→503은 컨트롤러에서 직접 처리)
- **모든 엔드포인트에 SecurityException catch 필수**: 목록 조회(`GET /api/accounts/{id}/trading-cycles`) 포함 소유권 검증이 있는 모든 서비스 메서드를 호출하는 엔드포인트는 `SecurityException → 403` 처리 필수 — 누락 시 Spring 기본 핸들러가 500 반환 → 프론트엔드에서 해당 데이터가 빈 배열/null로 처리됨

### Account ↔ TradingCycle 분리 (V38 이후)
- `Account` record 필드 10개: `id, userId, nickname, accountNo, kisAppKey, kisSecretKey, kisAccountType, broker, createdAt, updatedAt` — type/status/ticker/multiple 없음
- `TradingCycle` record 필드 8개: `id, accountId, type(Type), status(Status), ticker(Ticker), initialUsdDeposit, createdAt, updatedAt`
- `Type`, `Status`, `Ticker` 모두 `TradingCycle` record의 nested enum — 구 `Strategy.StrategyType/StrategyStatus/Ticker` 대체
- `MAX_CYCLES_PER_ACCOUNT = 1` (`TradingCycleService` 상수) — 운영 정책, 확장 시 상수만 변경
- `initialUsdDeposit`: 사이클 시작 시 초기 입금액 메타 기록용 — 매매 공식(B = usdDeposit + M) 변경 없음
- V35 마이그레이션: accounts/strategies/orders/trade_histories/portfolio_snapshots/kis_tokens TRUNCATE (기존 데이터 초기화)
- V38 마이그레이션: `strategies` → `trading_cycle` 테이블 리네임 + `initial_usd_deposit` 컬럼 추가
- V39 마이그레이션: `trading_cycle_history` 테이블 신설 — ON DELETE CASCADE
- V44 마이그레이션: `trading_cycle_history.trade_date` 컬럼 및 UNIQUE 제약 제거, `avg_price` nullable 허용
- V46 마이그레이션: `trading_cycle.multiple` 컬럼 제거 — PRIVACY 배수는 `floor(initialUsdDeposit / currentCycleStart, 2)` 동적 산출로 대체

### 변경된 포트 시그니처 (V38 이후)
- `ExecuteTradingUseCase.execute(TradingCycle cycle, Account account, User user)` — TradingCycle 파라미터
- `ExecuteTradingUseCase.executeBatch(List<BatchContext> contexts)` — 복수 사이클 일괄 실행 (현재가 1회 조회). `BatchContext`는 인터페이스 내 nested record `(TradingCycle cycle, Account account, User user)`
- `NotifyPort.notifyInsufficientBalance(Account, AccountBalance, TradingCycle.Ticker)` — TradingCycle.Ticker
- `UserNotificationPort.notifyStrategyChanged(User, Account, TradingCycle cycle, String action)` — TradingCycle
- `InfinitePosition(AccountBalance, TradingCycle.Ticker, BigDecimal price)` — TradingCycle.Ticker, multiple 제거 (V46)
- `TradingCycleHistoryPort` — `save`, `findRecentByCycleId(cycleId, limit)`, `findByAccountId(accountId, from, to)`, `findRecentGlobal(limit)`, `findRecentDaysGlobal(days)`
- **`TradingService`는 `KisAccountPort` 미사용** — 잔고는 `TradingCycleHistoryPort.findRecentByCycleId(cycleId, 1)` 최신 이력에서 읽음
- **`PortfolioSnapshot` 완전 제거** — `GetPortfolioUseCase`는 `AccountCycleHistoryEntry` 반환, `PortfolioSnapshotResponse.from(AccountCycleHistoryEntry)`에서 `marketValueUsd`/`totalAssetUsd` computed

### MetaController (enum SSOT)
- `/api/meta` — `MetaBundle` 번들 (strategyTypes/tickers/brokers/strategyStatuses)
- `/api/meta/strategy-types`, `/api/meta/tickers`, `/api/meta/brokers`, `/api/meta/strategy-statuses` — 개별 조회
- `TradingCycle.Type`/`TradingCycle.Status`, `TradingCycle.Ticker`, `Account.Broker` enum에 label/description 필드 포함 (DTO `from()` 팩토리)
- UI는 이 엔드포인트로 라벨/available tickers/default값 수신 — enum 리터럴 UI 하드코딩 금지
- `TickerMeta` 응답 필드: `code`/`label`/`description`/`targetProfitRate` — `exchangeCode`/`excdCode`는 KIS 내부 코드이므로 UI 미노출

### 파일 인코딩 주의 (BOM)
- 서브에이전트가 Java 파일 import 수정 시 BOM(`\xef\xbb\xbf`) 삽입 버그 발생 사례 → `compileJava` 즉시 실패
- 일괄 제거: `grep -rl $'\xef\xbb\xbf' src --include="*.java" | while read f; do sed -i '1s/^\xef\xbb\xbf//' "$f"; done`

### 수량 변수명 규칙 (V26 전수 통일 완료)
- **보유 잔고 수량** (avgPrice와 짝이 되는 것): `holdings` — `AccountBalance`, `TradingSnapshot`, `TradingCycleHistory`, `PresentBalanceResult.Item`, `PrivacyTradeEntity`, `PrivacyTradeOrderEntity`
- **주문/체결 수량** (단건 거래 수량): `quantity` — `Order`, `PlannedOrder`, `TradeHistory`, `Execution`, `DailyTransaction`, `ReservationOrderCommand`, `ReservationOrder.orderedQuantity/filledQuantity`
- `qty` 사용 금지 (DB 컬럼/Java 필드/JSON 키 모두)
- DB 컬럼: `trading_cycle_history.holdings`, `privacy_trades_master.holdings`(보유) / `orders.quantity`, `privacy_trades_detail.quantity`(주문, nullable — FIDA 수신 시 수량 미확정 케이스 허용)
- KIS 어댑터 내부 record: `@JsonProperty` 값(KIS API 키)은 유지, Java 필드명만 의미 명료화 — `cblcQty`→`balanceQuantity`, `slclQty`→`sellLiquidationQuantity`, `ftCcldQty`→`filledQuantity`, `ftOrdQty`→`orderedQuantity`, `cblcQty13`→`balanceQuantity13`
- 복합 수량 필드: `orderedQty`/`filledQty` 패턴 금지 → `orderedQuantity`/`filledQuantity`
- `InfinitePosition.calcXxxQuantity()` 메서드명은 "주문 수량 계산 결과"이므로 Quantity 유지 (보유수량 아님)

### AES-256 암호화 컬럼 크기
- AES-256 CBC 암호화 + Base64 인코딩 시 입력 ~180자 → 출력 ~260자 — VARCHAR(255) 초과로 `DataIntegrityViolationException` 발생
- 암호화 저장 컬럼은 반드시 VARCHAR(512) 이상 — `AccountEntity`: account_no/kis_app_key/kis_secret_key/telegram_bot_token 모두 512
- 새 암호화 컬럼 추가 시 length=512로 선언, Flyway도 동일하게

### Ticker enum (V38 이후: TradingCycle.Ticker nested enum)
- **`Ticker`는 `TradingCycle.Ticker`** — `domain/model/tradingcycle/TradingCycle.java` 내 nested enum. 구 `Strategy.Ticker` 완전 삭제됨
- **import 경로**: `import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;` (구 `Strategy.Ticker` import 사용 금지)
- `TradingCycle.Ticker` enum: `TQQQ(ExchangeCode.NASD, ExcdCode.NAS, 0.15, desc)`, `SOXL(ExchangeCode.AMEX, ExcdCode.AMS, 0.20, ...)` — exchangeCode(ExchangeCode)/excdCode(ExcdCode)/targetProfitRate/description 필드 포함 (label 없음). 새 종목 추가 시 두 코드 체계 모두 지정 필수
- `TradingCycle.Ticker.tryParse(String)` — `Optional<TradingCycle.Ticker>` 반환, KIS 응답 종목코드 안전 변환
- PRIVACY 전략: 항상 서버에서 `TradingCycle.Ticker.SOXL` 강제 (`TradingCycle.Type.resolveTicker()` — 도메인 규칙)
- INFINITE 전략: 지정 없으면 `Ticker` 선언 순서 첫 번째(현재 TQQQ) — `Type.resolveTicker(requested)` 단일 파라미터 시그니처, `availableTickers().iterator().next()` fallback
- `TradingCycle.Type` enum: `description` 필드만 보유 (label/defaultTicker/defaultMultiple 없음). `availableTickers()` 메서드가 INFINITE→allOf, PRIVACY→{SOXL} 동적 반환
- DB: `trading_cycle.ticker` 컬럼에 `Ticker.name()` 저장, `TradingCyclePersistenceAdapter`에서 `TradingCycle.Ticker.valueOf(e.getTicker())`로 변환
- KIS 응답 모델 — `TradingCycle.Ticker ticker` 필드 사용, 어댑터에서 `tryParse` 필터로 enum 외 종목 제거
- **Account에서 ticker 제거됨** — `Account.ticker()` 호출 불가. 매매 시 `tradingCycle.ticker()` 사용
- **`symbolName: String`은 유지** — KIS `ovrs_item_name`/`prdt_name`은 enum 후보 아님

### Swagger 개발 도구
- `OpenApiConfig.java` (`adapter/in/web/security/`) — Bearer JWT SecurityScheme 전역 등록 (자물쇠 버튼)
- `@SecurityRequirements` (빈 어노테이션) — 특정 엔드포인트의 자물쇠 아이콘 제거
- `DevAuthController.java` (`adapter/in/web/`, `@Profile("local")`) — 로컬 전용 dev-token 발급
- `@Schema` 추가 위치: DTO (`adapter/in/web/dto/`)에만 — `domain/model/`은 ArchUnit이 막지 않아도 "외부 의존성 0" 원칙으로 금지 (StatisticsController가 반환하는 PeriodProfitResult 등 도메인 객체는 springdoc 자동 introspection에 맡김)
- Java record에서 `@Schema`는 생성자 파라미터(필드 선언)에 직접 붙임
- 컨트롤러 클래스에 `@Tag(name="...", description="...")` — Swagger UI 그룹 레이블
  - `POST /api/auth/dev-token` → 고정 UUID `00000000-0000-0000-0000-000000000001` 테스트 유저 자동 생성·승인 + JWT 반환
  - 응답 JSON: `{"accessToken":"...","tokenType":"bearer","expiresIn":604800}` — 필드명 `accessToken` (`token` 아님)
  - dev-token 서명: `jwt.signing-key`(application-local.yml, gitignored) EC 개인키로 ES256 서명 — JwtIssuerService 사용
  - 카카오 OAuth 직접 처리

### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**
- TradingScheduler cron: `0 0 4 * * TUE-SAT` (화~토 04:00 KST) — 변경 금지
- 사이클 단위 순차 실행: `TradingCyclePort.findAllActive()` → `executeBatch(contexts)` 1회 호출 — context 빌드 실패 사이클은 스케줄러에서 skip, 실행 중 실패 격리는 `TradingService.executeBatch()` 내부에서 처리

### JPA 설정
- `spring.jpa.open-in-view: false` 명시 — REST API이므로 불필요, 커넥션 점유 방지
- `@ManyToOne`에 `@JoinColumn(name="...", nullable=false)` 항상 명시 — 생략 시 Hibernate 기본 추론(`필드명_id`)에 의존 → 네이밍 전략 변경 시 운영 이슈
- IDE 경고 "열을 해결할 수 없습니다" — Flyway 미적용 상태의 false positive. `compileJava BUILD SUCCESSFUL`이 실제 검증 기준
- **`BaseAuditEntity` vs `BaseCreatedAtEntity`**: `createdAt`+`updatedAt` 필요 시 `BaseAuditEntity` 상속, `createdAt`만 필요 시 `BaseCreatedAtEntity` 상속 — `updated_at` 컬럼 없는 엔티티에 `BaseAuditEntity` 사용 금지 (`ddl-auto: validate` 실패)

### Java Enum ↔ DB 컬럼 매핑 규칙 (전 프로젝트 통일)
- **DB 컬럼**: PostgreSQL 네이티브 ENUM (`CREATE TYPE ... AS ENUM`) **사용 금지** — VARCHAR(20) 사용
- **JPA 매핑**: `@Enumerated(EnumType.STRING)` 단독 사용 — `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 사용 금지
- **Flyway**: `CREATE TYPE` 구문 작성 금지, 컬럼 정의는 `VARCHAR(20)` (값 길이 여유 있게)
- 기존 네이티브 ENUM은 V25에서 VARCHAR로 전환 완료 (`user_status`, `user_role`, `strategy_type`, `strategy_status`)

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
A = averagePrice (qty==0이면 currentPrice)   Q = quantity
M = A × Q (purchaseAmount)   D = currentPrice × Q (evaluationAmount, 정보성)
B = usdDeposit + M (totalAssets)   K = B ÷ 20 (unitAmount, scale=2, HALF_UP)
T = Q==0 ? 0.0 : M ÷ K  (currentRound, double, 소수점 허용)
S = 0.20 × (1 - 2T/20)  (priceOffsetRate, scale=4, HALF_UP)
G = A × (1 + S)  (referencePrice, scale=2, HALF_UP — LOC 주문 가격 기준)
P = A × 1.20  (targetPrice, scale=2, HALF_UP)
```
- `usdDeposit` = 통합주문가능금액 (KIS `TTTC2101R` `itgr_ord_psbl_amt`, 미국 행 필터링) — 원화 자동 환전 포함, B 계산에 사용
- `currentRound`(T)는 floor 없이 소수점 허용
- **전반/후반 분기**: `priceOffsetRate > 0` → 전반, `≤ 0` → 후반 (수학적으로 T < 10 / T ≥ 10과 동치)
- **전반**: LOC 매수①(K/2/A, 평단가) + LOC 매수②((K − A×Q①)/G, 기준가) + LOC 매도(Q/4, G+0.01) + 지정가 매도(Q-Q/4, P)
- **후반 K>D**: MOC 매도(Q/4)만 / **후반 K≤D**: LOC 매수(K/G, G) + LOC 매도 + 지정가 매도

### KIS 계좌번호 DB 저장 방식
- 계좌번호는 `accounts.account_no` (8자리, AES-256 암호화) + `accounts.kis_account_type` (평문 `"01"`) 으로 분리 저장
- KIS API 호출: `CANO = account.accountNo()`, `ACNT_PRDT_CD = account.kisAccountType()`
- `74420614-01` 형태로 하나의 필드에 합치면 KIS API CANO 파라미터 오류 — 반드시 분리

### Flyway
- `V1__`~`V5__.sql` **절대 수정 금지** — 새 마이그레이션은 `V6__...` 이후로 (V6~V8: V2 users/accounts 테이블, V9: kis_tokens account_id UUID PK)
- 현재 최신: `V50__drop_portfolio_snapshots.sql` (V27: accounts.broker, V28: planned_orders→orders rename, V29: orders/kis_tokens updated_at, V30: privacy_trades_master current_cycle_realized_pnl, V31: privacy_trades_detail.quantity nullable, V32: privacy updated_at 제거, V33: strategies.multiple, V34: privacy avg_price nullable, V35: TRUNCATE, V36: trade_histories 재생성(account_id NOT NULL·kis_order_id→order_id), V37: 미사용(번호 없음), V38: `strategies`→`trading_cycle` 리네임 + `initial_usd_deposit`, V39: `trading_cycle_history` 신설, V40: fcm_device_tokens, V41: notification_channel, V42: trading_cycle 컬럼 재정렬, V43: soft delete, V44: trading_cycle_history 변경, V45: portfolio_snapshots current_price 컬럼 제거, V46: trading_cycle.multiple 컬럼 제거, V47: cycle_seed_type 추가 + holdings INT 변환, V48: numeric scale 2 통일, V49: trading_cycle_history 재생성(current_price NUMERIC(12,2) 추가, avg_price 앞), V50: portfolio_snapshots DROP)
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화
- **Entity ↔ Flyway 크로스체크 필수**: Entity의 `nullable`, `length`, `precision`, `scale`, `columnDefinition` 변경 시 해당 컬럼을 생성/변경한 Flyway SQL과 반드시 대조. `ddl-auto: validate`는 컬럼 타입·`precision`·`scale` 불일치를 부팅 시 즉시 `SchemaManagementException`으로 잡음. `NOT NULL` 등 제약 불일치만 런타임까지 무증상 → 실제 null 삽입 시 `DataIntegrityViolationException` (V34 `avg_price` 사례)
- **`@Column(scale)` 주의**: DDL 힌트일 뿐, INSERT/UPDATE 시 Hibernate가 BigDecimal을 자동 반올림하지 않음. PostgreSQL이 컬럼 타입(`NUMERIC(12,2)`)에 맞춰 INSERT 시 반올림. 단 JPA 1차 캐시에는 원본 scale의 BigDecimal이 유지됨 — `@Transactional` 내 저장 직후 읽으면 DB 반올림 전 값 반환
- PostgreSQL `ADD COLUMN`은 항상 맨 뒤에 추가 (`AFTER` 절 없음) — 컬럼을 특정 위치에 두려면 테이블 재생성 방식 사용 (`CREATE TABLE _new + INSERT SELECT + DROP + RENAME` — V22/V36 패턴 참고)
- 재생성 패턴에서 **명시적으로 이름 붙인 제약조건** (`CONSTRAINT foo UNIQUE (...)`) 주의: 테이블 리네임 후 `_old`에 제약조건명이 남아 새 테이블 CREATE 시 충돌 → `ALTER TABLE xxx_old DROP CONSTRAINT foo;`를 RENAME 직후·CREATE 전에 추가 필수 (V42 `uq_privacy_trades_master_date_ticker` 사례). unnamed `UNIQUE`는 PostgreSQL이 자동으로 충돌 없는 이름 생성하므로 해당 없음
- 컬럼 타입 변경 시 `USING` 캐스팅 필수 — `ALTER TABLE t ALTER COLUMN c TYPE VARCHAR(20) USING c::text` (미작성 시 오류)
- **컬럼 순서는 Entity 필드 선언 순서와 반드시 일치** — 테이블 재생성 시 SQL `CREATE TABLE` 컬럼 순서를 Entity 필드 선언 순서에 맞춰 작성할 것 (불일치 시 코드 리뷰 혼란 및 향후 마이그레이션 추적 오류 유발)
- Java 코드만 삭제해도 DB 테이블은 자동 제거 안 됨 — 미사용 테이블은 신규 마이그레이션으로 `DROP TABLE IF EXISTS` (V21 패턴)
- **FK 추가 시 `ON DELETE CASCADE` 여부 반드시 명시** — 기본값 `ON DELETE RESTRICT` → 부모 레코드 삭제 시 FK 위반 유발 (V8 누락으로 계좌삭제 500 발생)
- PostgreSQL ENUM → VARCHAR 전환 시 `DROP TYPE` 실패 원인: `ALTER COLUMN TYPE` 후에도 DEFAULT 표현식(`'PENDING'::user_status`)에 ENUM 캐스팅이 남아 의존성 유지 → `DROP TYPE ... CASCADE`로 의존 DEFAULT 함께 제거 후 `SET DEFAULT '값'`으로 재설정 (V25 패턴)
- Flyway checksum mismatch (로컬 마이그레이션 파일 수정 시): `DELETE FROM flyway_schema_history WHERE version = 'N'` + 해당 테이블 DROP → 앱 재시작 (로컬 전용 — 운영 DB에 절대 적용 금지)

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도

### springdoc 버전 제약
- Spring Boot 3.4.x(Spring Framework 6.2)는 springdoc **2.7.0 이상** 필요 — 2.6.x는 `NoSuchMethodError: ControllerAdviceBean` 발생
- 현재: `springdoc = "2.8.4"` (`gradle/libs.versions.toml`)

### 텔레그램 로컬 테스트
- `api.telegram.org:443` TCP가 ISP 레벨에서 차단될 수 있음 (ping은 성공해도 curl 타임아웃)
- 로컬에서 `curl .../sendMessage` 테스트 시 VPN 필요
- 로컬 Docker에서 Telegram 인바운드(버튼 클릭 callback_query) 동작 불가 — Telegram 서버가 localhost 미접근
- 로컬 승인 방법: `POST /api/auth/dev-approve/{userId}` (`DevAuthController`, `@Profile("local")` 전용)
  - `curl -s -X POST http://localhost:8080/api/auth/dev-approve/<UUID>`
  - UUID 확인: `docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "SELECT id, nickname, status FROM users ORDER BY created_at DESC LIMIT 5;"`

### Adapter 내부 중첩 타입 접근 제어자
- 같은 패키지 테스트에서 참조하려면 `private record` 금지 — `record`(package-private)으로 선언해야 `Outer.Inner.class` 매처 사용 가능
- 예: `KisConnectionTestAdapter.TokenCheckResponse`, `KisOrderAdapter.OrderResponse` 패턴
- `private record`를 유지하면서 테스트에서 response 타입을 `any(Class.class)` 매처로 우회하면 타입 안전성 저하 → package-private 선언 권장

### Lombok 패턴
- `@Slf4j` + `@RequiredArgsConstructor` 표준 — 수동 로거/생성자 작성 금지
- package-private 타입을 생성자에서 참조 시: `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` (ClassEscapesItsScope 회피)
- `@Value` 필드를 Lombok 생성자에 포함하려면 `lombok.config`에 `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value` 필요 (이미 설정됨)
- `RestTemplate` 빈이 여러 개(`kisRestTemplate`, `telegramRestTemplate`)이므로 필드명을 빈 이름과 반드시 일치시킬 것 — 불일치 시 NoUniqueBeanDefinitionException

### AES-256 암호화 위치 (V2)
- KIS 자격증명·계좌번호·텔레그램 봇 토큰은 **persistence adapter 경계에서만** 암호화/복호화
- `AccountPersistenceAdapter`가 `AesCryptoService` 주입받아 처리 — `AccountService`(application layer)는 평문만 다룸
- ArchUnit 규칙(application → adapter 금지)으로 서비스가 암호화 서비스 직접 호출 불가
- 신규 환경변수: `AES_ENCRYPTION_KEY` (Base64 32바이트)

### TelegramApiClient package-private 제약
- `TelegramApiClient` (`adapter/in/telegram/`)는 package-private → application layer나 다른 패키지에서 직접 참조 불가
- 사용자 고유 botToken으로 Telegram API 호출이 필요하면: `domain/port/out/` 포트 + `adapter/out/notify/` 어댑터 신규 생성 패턴 (예: `TelegramBotInfoPort` + `TelegramBotInfoAdapter`)
- 기존 `telegramRestTemplate` 빈 재사용 가능 (필드명 일치시키면 자동 주입)

### Spring Security Filter 이중 등록 방지
- `@Component` Filter를 `SecurityFilterChain.addFilterBefore()`로 추가 시 서블릿 필터 체인에도 자동 등록되어 이중 실행
- `SecurityConfig`에 `FilterRegistrationBean<MyFilter>.setEnabled(false)` 빈 선언으로 비활성화 (현재 `JwtAuthFilter`, `InternalTokenAuthFilter` 모두 적용됨)
- `SecurityConfig`에 새 Filter 주입 필드 추가 시 `@Import(SecurityConfig.class)` 사용하는 **모든** `@WebMvcTest` 테스트에 해당 Filter 클래스도 `@Import` 목록에 추가 필수 — 누락 시 `NoSuchBeanDefinitionException`으로 컨텍스트 실패 (실패가 캐시돼 무관한 테스트까지 `IllegalStateException` 전파)

### 서버 간 내부 인증 (InternalTokenAuthFilter)
- `/api/internal/**` 경로: `X-Internal-Token` 헤더 검증 — 환경변수 `INTERNAL_API_TOKEN` 값과 일치해야 통과 (미설정 시 항상 401)
- `SecurityConfig`: `/api/internal/**` → `hasRole("INTERNAL")`, `InternalTokenAuthFilter` JWT 필터보다 먼저 실행
- `@WebMvcTest`에서 `/api/internal/**` 경로 테스트: `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})` + `@TestPropertySource(properties = "internal.api.token=test-token")` + `.header("X-Internal-Token", "test-token")` 패턴 (`FidaOrderControllerTest` 참고)

### 소유권 검증 예외 패턴 (V2)
- Service에서 소유권 위반 시 `SecurityException`(Java 내장 unchecked) throw
- Controller에서 catch → `ResponseStatusException(HttpStatus.FORBIDDEN)` 변환
- application 레이어가 Spring HTTP에 의존하지 않아 ArchUnit 규칙 준수
- KIS API 오류: Service에서 예외 그대로 전파 → Controller에서 `catch (Exception e) → ResponseStatusException(503)` 변환
- `ResponseStatusException` 등 Spring HTTP 클래스는 application layer 금지 (ArchUnit `application → adapter` 규칙)
- `InvalidKisKeyException`(domain/model/) → Controller에서 422(UNPROCESSABLE_ENTITY) 변환 — register/update 공통 적용

### @EnableJpaAuditing 위치
- `@EnableJpaAuditing`을 `@SpringBootApplication`에 두면 `@WebMvcTest` 슬라이스 테스트가 `BeanCreationException` 실패 — JPA 인프라 없음
- 반드시 별도 `@Configuration` 클래스로 분리: `adapter/out/persistence/JpaAuditingConfig.java`
- `@WebMvcTest`는 persistence 패키지의 `@Configuration`을 로드하지 않아 충돌 없음

### Lombok @MappedSuperclass 상속 주의
- `@MappedSuperclass` 부모 클래스 필드의 getter/setter는 서브클래스의 `@Getter`/`@Setter`로 생성되지 않음
- `BaseAuditEntity` 같은 공통 엔티티 부모에 직접 `@Getter @Setter(AccessLevel.PACKAGE)` 추가 필요
- `@Setter(AccessLevel.PACKAGE)` 범위는 **선언 클래스 패키지 기준** — `BaseAuditEntity`(`adapter.out.persistence`)의 setter는 하위 패키지(`adapter.out.persistence.account` 등)에서 접근 불가. 서브패키지 어댑터/테스트에서 `setCreatedAt()`/`setUpdatedAt()` 호출 시 컴파일 오류 발생

### 자체 JWT 인증 (ECC P-256)
- `JwtIssuerService`: `jwt.signing-key` EC P-256 JWK로 ES256 JWT 발급 (TTL: 7일 = 604_800_000ms)
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
- 허용 origin: `CORS_ALLOWED_ORIGINS` 환경변수 (Render), 기본값 `http://localhost:3000`
- `CORS_ALLOWED_ORIGINS` 쉼표 구분, 각 origin 앞뒤 공백 자동 trim — `http://localhost:3000, http://127.0.0.1:3000`처럼 공백 포함 작성 가능
- `corsConfigurationSource()`: allowedMethods=GET/POST/PUT/**PATCH**/DELETE/OPTIONS (PATCH 미포함 시 전략중지/재개 등 PATCH 엔드포인트 403), allowedHeaders=Authorization/Content-Type, allowCredentials=true
- **`SecurityConfig`에 `.exceptionHandling()` + `authenticationEntryPoint` 반드시 설정** — 미설정 시 `Http403ForbiddenEntryPoint` 기본 적용 → 인증 실패가 401 대신 403 반환
- **`JwtAuthFilter` catch 절은 `Exception`으로** — `JwtException`만 잡으면 `UUID.fromString(jwt.getSubject())`의 NPE·IAE 미처리 → 인증 미설정 → 익명 사용자 → 403

### Telegram Webhook 등록
- `/telegram/webhook` 엔드포인트가 있어도 `setWebhook` API 미호출 시 버튼 클릭(callback_query) 이벤트 미수신
- 등록: `curl -X POST "https://api.telegram.org/bot{TOKEN}/setWebhook" -d '{"url":"https://kista-api.onrender.com/telegram/webhook"}'`
- Render URL 변경 시 재등록 필요

### @Transactional 내부 외부 시스템 호출 금지
- RestTemplate(텔레그램, KIS 등) 호출을 @Transactional 내부에서 하면 롤백 시에도 취소 불가 → 중복 알림 등 부작용
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)` 사용

### trading_cycle 테이블 (V11→V38 이력)
- V11: `accounts.strategy`/`strategy_status` → `strategies` 테이블로 분리 (account:cycle = 1:N 대비 설계)
- V38: `strategies` → `trading_cycle`으로 리네임 + `initial_usd_deposit` 컬럼 추가
- `TradingCyclePersistenceAdapter`: `toDomain`/`toEntity` 매핑, `initialUsdDeposit` 포함
- `findAllActive()`: native SQL, `JOIN trading_cycle tc ON tc.account_id = a.id WHERE ... AND tc.status = 'ACTIVE'`
- `trading_cycle_history` (V39): 사이클별 일별 스냅샷 — `TradingService.execute()` 종료 시 1건 append. `DataIntegrityViolationException` catch+log.warn으로 같은 날 중복 실행 무시

### reapply 쿨다운 정책
- PENDING 사용자: 1시간마다 재신청 가능 (누락 대비 알림 재발송, 무한 요청 방지)
- REJECTED 사용자: 거절 후 24시간 후 재신청 가능
- `lastReappliedAt` 단일 필드로 추적 — `reject()` 시 설정, `reapply()` 성공 시 갱신
- 쿨다운 위반: `CooldownException(retryAfter: Instant)` → controller에서 429 변환

### ArchUnit 규칙 예외 (adapter.out)
- `adapter.in → application` 의존 금지 / `adapter.out → application` 의존 허용
- TelegramAdapter(adapter.out)에서 NewUserRegisteredEvent(application.service) 참조 가능

### 도메인 포트 인터페이스와 타입 위치 규칙
- `domain/port/in` 또는 `domain/port/out` 인터페이스의 파라미터·반환 타입으로 쓰이는 record/class는 반드시 `domain/model/` 하위에 위치 — `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
- `application/service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙)
- 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import

### 공유 DTO @Valid 제약
- `AccountRequest`는 register/update 공용 — `@Valid` 추가 시 `@NotNull strategyType`이 update에도 강제됨 (Breaking Change)
- register에만 필수인 필드는 `@NotNull` + register 메서드에만 `@Valid` 적용, update는 `@Valid` 없이 유지
- `AccountService.update()`는 strategyType 변경 지원 — null 전달 시 기존값 유지, PRIVACY 선택 시 ticker는 SOXL 강제 (register와 동일 규칙)

### 테이블 재생성 패턴 FK 제약명 주의 (V22 사례)
- `ALTER TABLE t RENAME TO t_old` 후 `CREATE TABLE t (...REFERENCES ...)` 인라인 선언 시 PostgreSQL이 `t_old`의 기존 제약명과 충돌 → 자동으로 숫자 접미사(`_fkey1`) 부여
- 인라인 `REFERENCES` 대신 `CONSTRAINT 명시_fkey FOREIGN KEY (...)` 형식 사용 권장 — 명시적 이름으로 충돌 없이 생성됨
- 기존 접미사 제약 정리: `ALTER TABLE t RENAME CONSTRAINT old_fkey1 TO old_fkey;` 후 다음 마이그레이션에서 정상 이름으로 참조 가능
- 실제 제약명 확인: `SELECT conname FROM pg_constraint WHERE conrelid = 'table'::regclass AND contype = 'f';`

### .env 파일 멀티라인 값 금지
- JSON 환경변수(예: `FIREBASE_SERVICE_ACCOUNT_JSON`)는 반드시 한 줄로 직렬화 — `.env` 파서는 줄바꿈을 값 끝으로 인식, 첫 줄 이후 무시됨
- 변환: `python3 -c "import json; content=open('.env.prod').read(); start=content.index('KEY=')+4; print(json.dumps(json.loads(content[start:].strip()), separators=(',',':')))"`

### ADMIN 권한 관리
- `users.role` PostgreSQL 네이티브 ENUM (`user_role`: USER/ADMIN) — V17
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
- 적용 위치: `OrderPersistenceAdapter`, `TradeHistoryPersistenceAdapter`, `PrivacyTradePersistenceAdapter` 의 toEntity/toDomain + LocalDate 파라미터 조회 메서드만 — JPA `@Converter` 자동 적용 금지 (가시성)
- FIDA 외부 입력: UTC 송신 → `FidaOrderService` 진입부에서 `toKst()` 변환 후 도메인 호출 (persistence가 다시 UTC로 변환하므로 원본 UTC 일자가 DB에 정확히 저장됨)
- 인라인 `.minusDays(1)`/`.plusDays(1)` 직접 사용 금지 — 의미 추적을 위해 `TradeDateConverter` 헬퍼 경유 필수
- `com.kista.common` 패키지: 유틸리티 헬퍼 위치 (도메인 무관, 어댑터·서비스 공용)

### 소프트 삭제(Soft Delete) 패턴 (V43 이후)
- 대상 테이블: `users`, `accounts`, `trading_cycle` — `deleted_at` 컬럼으로 논리 삭제
- Entity: `@SQLRestriction("deleted_at IS NULL")` 선언 → JPQL 자동 필터 (Hibernate 6)
- **`nativeQuery = true` 쿼리는 `@SQLRestriction` 미적용** — `AND tc.deleted_at IS NULL` 수동 명시 필수 (`findAllActiveCycles` 등)
- JPA Repository: `@Modifying @Query("UPDATE XxxEntity SET deletedAt = :now WHERE id = :id")` 패턴
- Cascade 순서: 서비스 레이어에서 사이클 → 계좌 → 사용자 순으로 명시 처리 (DB FK CASCADE 미작동)
- 새 엔티티에 소프트 삭제 추가 시: Entity `@SQLRestriction` + `deleted_at` 필드 + V마이그레이션 `ALTER TABLE ... ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE` + Repository soft delete 쿼리 + Adapter 구현
