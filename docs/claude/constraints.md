## 핵심 제약 사항

### AES-256 암호화 컬럼 크기
- AES-256 CBC 암호화 + Base64 인코딩 시 입력 ~180자 → 출력 ~260자 — VARCHAR(255) 초과로 `DataIntegrityViolationException` 발생
- 암호화 저장 컬럼은 반드시 VARCHAR(512) 이상 — `AccountEntity`: account_no/kis_app_key/kis_secret_key/telegram_bot_token 모두 512
- 새 암호화 컬럼 추가 시 length=512로 선언, Flyway도 동일하게

### Ticker enum (단일 진실 공급원)
- `Ticker` enum: `TQQQ("NASD", 0.15)`, `SOXL("AMS", 0.20)`, `USD("NASD", 0.20)` — `exchangeCode` + `targetProfitRate` 통합 관리
- `Account.ticker: Ticker` — 기존 `symbol: String` + `exchangeCode: String` 두 필드 대체
- `resolveExchangeCode()` 메서드 삭제됨 — `ticker.getExchangeCode()`로 대체
- PRIVACY 전략: 항상 서버에서 `Ticker.SOXL` 강제 (클라이언트 입력 무시) — `register()` 참고
- INFINITE 전략: 지정 없으면 기본 `Ticker.TQQQ`, exchangeCode는 Ticker가 자동 결정
- DB: `accounts.symbol` 컬럼 유지 (Ticker.name() 저장), `exchange_code` 컬럼 V14 마이그레이션으로 제거됨
- `AccountPersistenceAdapter`: `Ticker.valueOf(entity.getSymbol())`으로 변환

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
- TradingScheduler cron: `0 0 4 * * TUE-SAT` (화~토 04:00 KST) — V2 멀티계좌 스케줄 (변경 금지)
- 멀티계좌 순차 실행: `AccountRepository.findAllActive()` → 계좌별 `execute(Account, User)` — 한 계좌 실패 시 다음 계좌 계속 (격리)

### JPA 설정
- `spring.jpa.open-in-view: false` 명시 — REST API이므로 불필요, 커넥션 점유 방지

### PostgreSQL 네이티브 ENUM 타입 매핑
- Flyway가 `user_status`, `strategy_type`, `strategy_status`를 PostgreSQL 네이티브 ENUM으로 생성 (`CREATE TYPE ... AS ENUM`)
- Hibernate 6+의 `@Enumerated(EnumType.STRING)` 단독 사용 시 varchar로 바인딩 → `column "status" is of type user_status but expression is of type character varying` 오류
- 반드시 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 병기 필요 (`UserEntity.status`, `StrategyEntity.type/status`에 이미 적용됨)
- VARCHAR 컬럼 ENUM(예: `TradeHistoryEntity`)은 해당 없음 — 네이티브 ENUM 컬럼에만 필요

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
- **전반**: LOC 매수①(K/2/A, 평단가) + LOC 매수②(K/2/G, 기준가) + LOC 매도(Q/4, G+0.01) + 지정가 매도(Q-Q/4, P)
- **후반 K>D**: MOC 매도(Q/4)만 / **후반 K≤D**: LOC 매수(K/G, G) + LOC 매도 + 지정가 매도

### KIS 계좌번호 DB 저장 방식
- 계좌번호는 `accounts.account_no` (8자리, AES-256 암호화) + `accounts.kis_account_type` (평문 `"01"`) 으로 분리 저장
- KIS API 호출: `CANO = account.accountNo()`, `ACNT_PRDT_CD = account.kisAccountType()`
- `74420614-01` 형태로 하나의 필드에 합치면 KIS API CANO 파라미터 오류 — 반드시 분리

### Flyway
- `V1__`~`V5__.sql` **절대 수정 금지** — 새 마이그레이션은 `V6__...` 이후로 (V6~V8: V2 users/accounts 테이블, V9: kis_tokens account_id UUID PK)
- 현재 최신: `V15__expand_encrypted_columns.sql` (accounts 암호화 컬럼 VARCHAR(512) 확장)
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화

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

### Spring Security Filter 이중 등록 방지
- `@Component` Filter를 `SecurityFilterChain.addFilterBefore()`로 추가 시 서블릿 필터 체인에도 자동 등록되어 이중 실행
- `SecurityConfig`에 `FilterRegistrationBean<MyFilter>.setEnabled(false)` 빈 선언으로 비활성화 (현재 `JwtAuthFilter`에 적용됨)

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
- `corsConfigurationSource()`: allowedMethods=GET/POST/PUT/DELETE/OPTIONS, allowedHeaders=Authorization/Content-Type, allowCredentials=true

### Telegram Webhook 등록
- `/telegram/webhook` 엔드포인트가 있어도 `setWebhook` API 미호출 시 버튼 클릭(callback_query) 이벤트 미수신
- 등록: `curl -X POST "https://api.telegram.org/bot{TOKEN}/setWebhook" -d '{"url":"https://kista-api.onrender.com/telegram/webhook"}'`
- Render URL 변경 시 재등록 필요

### @Transactional 내부 외부 시스템 호출 금지
- RestTemplate(텔레그램, KIS 등) 호출을 @Transactional 내부에서 하면 롤백 시에도 취소 불가 → 중복 알림 등 부작용
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)` 사용

### strategies 테이블 분리 (V11)
- `accounts.strategy`/`strategy_status` → `strategies` 테이블로 분리 (account:strategy = 1:N 대비 설계)
- `AccountPersistenceAdapter.save()`: `account.id()==null` → `StrategyEntity` 신규 생성, `!=null` → 기존 로드 후 type/status 업데이트
- `save()` 내 `buildDomain(AccountEntity, StrategyEntity)` 헬퍼로 이중 `findByAccountId` 쿼리 방지 — `toDomain()`은 조회 경로(`findById` 등) 전용
- `findAllActive()`: native SQL, `JOIN strategies s ON s.account_id = a.id WHERE ... AND s.status = 'ACTIVE'`, `SELECT DISTINCT` (향후 1:N 중복 방지)

### reapply 쿨다운 정책
- PENDING 사용자: 1시간마다 재신청 가능 (누락 대비 알림 재발송, 무한 요청 방지)
- REJECTED 사용자: 거절 후 24시간 후 재신청 가능
- `lastReappliedAt` 단일 필드로 추적 — `reject()` 시 설정, `reapply()` 성공 시 갱신
- 쿨다운 위반: `CooldownException(retryAfter: Instant)` → controller에서 429 변환

### ArchUnit 규칙 예외 (adapter.out)
- `adapter.in → application` 의존 금지 / `adapter.out → application` 의존 허용
- TelegramAdapter(adapter.out)에서 NewUserRegisteredEvent(application.service) 참조 가능

### 공유 DTO @Valid 제약
- `AccountRequest`는 register/update 공용 — `@Valid` 추가 시 `@NotNull strategyType`이 update에도 강제됨 (Breaking Change)
- register에만 필수인 필드는 `@NotNull` + register 메서드에만 `@Valid` 적용, update는 `@Valid` 없이 유지
- `AccountService.update()`는 strategyType을 요청이 아닌 기존 DB 값에서 읽음 — update 요청의 strategyType 무시됨
