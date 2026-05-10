## 핵심 제약 사항

### Swagger 개발 도구
- `OpenApiConfig.java` (`adapter/in/web/security/`) — Bearer JWT SecurityScheme 전역 등록 (자물쇠 버튼)
- `@SecurityRequirements` (빈 어노테이션) — 특정 엔드포인트의 자물쇠 아이콘 제거
- `DevAuthController.java` (`adapter/in/web/`, `@Profile("local")`) — 로컬 전용 dev-token 발급
  - `POST /api/auth/dev-token` → 고정 UUID `00000000-0000-0000-0000-000000000001` 테스트 유저 자동 생성·승인 + JWT 반환
  - Supabase는 카카오 OAuth 전용 — email/password 로그인 불가

### Virtual Thread
- `spring.threads.virtual.enabled=true` (application.yml에 설정됨)
- `TradingService` 내부 대기: `Thread.sleep()` 사용
- `@Async`, `CompletableFuture` **사용 금지**
- TradingScheduler cron: `0 0 4 * * TUE-SAT` (화~토 04:00 KST) — V2 멀티계좌 스케줄 (변경 금지)
- 멀티계좌 순차 실행: `AccountRepository.findAllActive()` → 계좌별 `execute(Account, User)` — 한 계좌 실패 시 다음 계좌 계속 (격리)

### JPA 설정
- `spring.jpa.open-in-view: false` 명시 — REST API이므로 불필요, Supabase PgBouncer Transaction Mode에서 트랜잭션 외부 커넥션 점유 방지

### PostgreSQL 네이티브 ENUM 타입 매핑
- Flyway가 `user_status`, `strategy_type`, `strategy_status`를 PostgreSQL 네이티브 ENUM으로 생성 (`CREATE TYPE ... AS ENUM`)
- Hibernate 6+의 `@Enumerated(EnumType.STRING)` 단독 사용 시 varchar로 바인딩 → `column "status" is of type user_status but expression is of type character varying` 오류
- 반드시 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 병기 필요 (`UserEntity.status`, `AccountEntity.strategy/strategyStatus`에 이미 적용됨)
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

### KIS 계좌번호 환경변수 분리
- `KIS_ACCOUNT_NO=74420614` (8자리만) + `KIS_ACCOUNT_TYPE=01` 별도 설정
- `74420614-01` 형태로 하나의 변수에 넣으면 KIS API CANO 파라미터 오류

### Flyway
- `V1__`~`V5__.sql` **절대 수정 금지** — 새 마이그레이션은 `V6__...` 이후로 (V6~V8: V2 users/accounts 테이블, V9: kis_tokens account_id UUID PK)
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
- callback_query 시뮬레이션: `curl -s -X POST http://localhost:8080/telegram/webhook -H "Content-Type: application/json" -d '{"callback_query":{"id":"test123","data":"approve:<UUID>","message":{"chat":{"id":<CHAT_ID>}}}}'`
- `answerCallbackQuery 실패` 에러는 가짜 callback ID 사용 시 정상 발생 — 승인 로직 자체는 실행됨

### Lombok 패턴
- `@Slf4j` + `@RequiredArgsConstructor` 표준 — 수동 로거/생성자 작성 금지
- package-private 타입을 생성자에서 참조 시: `@RequiredArgsConstructor(access = AccessLevel.PACKAGE)` (ClassEscapesItsScope 회피)
- `@Value` 필드를 Lombok 생성자에 포함하려면 `lombok.config`에 `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value` 필요 (이미 설정됨)
- `RestTemplate` 빈이 여러 개(`kisRestTemplate`, `telegramRestTemplate`)이므로 필드명을 빈 이름과 반드시 일치시킬 것 — 불일치 시 NoUniqueBeanDefinitionException

### AES-256 암호화 위치 (V2)
- KIS 자격증명·계좌번호·텔레그램 봇 토큰은 **persistence adapter 경계에서만** 암호화/복호화
- `AccountPersistenceAdapter`가 `AesCryptoService` 주입받아 처리 — `AccountService`(application layer)는 평문만 다룸
- ArchUnit 규칙(application → adapter 금지)으로 서비스가 암호화 서비스 직접 호출 불가
- 신규 환경변수: `AES_ENCRYPTION_KEY` (Base64 32바이트), `SUPABASE_JWT_SECRET`

### Spring Security Filter 이중 등록 방지
- `@Component` Filter를 `SecurityFilterChain.addFilterBefore()`로 추가 시 서블릿 필터 체인에도 자동 등록되어 이중 실행
- `SecurityConfig`에 `FilterRegistrationBean<MyFilter>.setEnabled(false)` 빈 선언으로 비활성화 (현재 `SupabaseJwtFilter`에 적용됨)

### 소유권 검증 예외 패턴 (V2)
- Service에서 소유권 위반 시 `SecurityException`(Java 내장 unchecked) throw
- Controller에서 catch → `ResponseStatusException(HttpStatus.FORBIDDEN)` 변환
- application 레이어가 Spring HTTP에 의존하지 않아 ArchUnit 규칙 준수
- KIS API 오류: Service에서 예외 그대로 전파 → Controller에서 `catch (Exception e) → ResponseStatusException(503)` 변환
- `ResponseStatusException` 등 Spring HTTP 클래스는 application layer 금지 (ArchUnit `application → adapter` 규칙)

### @EnableJpaAuditing 위치
- `@EnableJpaAuditing`을 `@SpringBootApplication`에 두면 `@WebMvcTest` 슬라이스 테스트가 `BeanCreationException` 실패 — JPA 인프라 없음
- 반드시 별도 `@Configuration` 클래스로 분리: `adapter/out/persistence/JpaAuditingConfig.java`
- `@WebMvcTest`는 persistence 패키지의 `@Configuration`을 로드하지 않아 충돌 없음

### Lombok @MappedSuperclass 상속 주의
- `@MappedSuperclass` 부모 클래스 필드의 getter/setter는 서브클래스의 `@Getter`/`@Setter`로 생성되지 않음
- `BaseAuditEntity` 같은 공통 엔티티 부모에 직접 `@Getter @Setter(AccessLevel.PACKAGE)` 추가 필요

### Supabase JWT 인증 (ECC P-256 방식)
- Supabase가 HS256(공유 시크릿) → ECC P-256(비대칭키)으로 전환됨 — `SUPABASE_JWT_SECRET` 더 이상 사용 안 함
- 인증 방식: `NimbusJwtDecoder.withJwkSetUri(jwksUri)` — JWKS 자동 패치·캐시·키 갱신 처리
- JWKS URI: `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json` (kista: `nnpchirdkaxvdybhqzct`)
- `NimbusJwtDecoder.withJwkSetUri("")` (빈 문자열)는 bean 생성 시점에 `MalformedURLException` 즉시 발생 — 빈 기본값 금지
- profile별 `JwtDecoder` 빈: `@Profile("local | test")` → HS256, `@Profile("!(local | test)")` → JWKS (SpEL OR/NOT 지원)
- **`JwtDecoder` @Bean은 반드시 `JwtDecoderConfig.java`에 분리** — `SecurityConfig`에 두면 `SupabaseJwtFilter` 순환 참조로 `APPLICATION FAILED TO START`
- 로컬 HS256 유지 이유: `DevAuthController`가 HS256으로 dev-token 생성 — JWKS로 변경 시 dev-token이 401 → **로컬만 HS256이 맞음, JWKS로 통일 금지**
- 의존성: `spring-boot-starter-oauth2-resource-server` 추가 필요 (`NimbusJwtDecoder` 포함)

### 주석 규칙
- 신규 코드 작성 시 주석을 함께 작성할 것
- 필드: `// 역할 한 줄` 인라인 주석
- 비즈니스 로직 블록 직전: 단계 설명 한 줄
- API 상수/코드값: `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용
