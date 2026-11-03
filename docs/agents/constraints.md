## 핵심 제약 사항

### Git 규칙
- `git push`는 사용자가 명시적으로 요청할 때만 실행 — 요청 없이 자동 푸시 금지, 요청하면 즉시 실행
- 커밋 전 author 확인 (`git config user.name`/`user.email`): `narafu <narafu@kakao.com>`
- 커밋 메시지는 한글, Conventional Commit 접두사(`feat(scope):`, `fix:`, `docs:`, `debug:` 등) + 명령형 제목
- 배포: **kista-trading은 단일 인스턴스·stop-first 교체·`stop_grace_period` ≥ 200s를 유지**해야 한다(재개가 잔여 락을 인수하므로 겹침 기동은 이중 실행 — `.github/tests/compose-invariants.bats`가 잠금). EPR 재발행 소유(`REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART`는 kista-trading·kista-scheduler만 true, kista-api는 false — 둘 이상이 같은 테이블에 true면 이중 claim)와 배포 파이프라인 구조 → docker-infra.md "서버 배포 방식"

### Deprecated API·클래스 식별 문자열·persistence 기본값
- deprecated API는 새로 쓰지 않는다 — Spring 7/Jackson 3 대체를 쓴다(`HttpStatus.UNPROCESSABLE_CONTENT`, `JsonNode.asString()`, `org.jspecify.annotations.Nullable` — 중첩 타입은 `Outer.@Nullable Inner`)
- 오류 보고 caller 등 클래스 식별 문자열은 `X.class.getSimpleName()` — 리터럴 하드코딩 금지. `getClass()`는 프록시 이름이 나오므로 쓰지 않는다
- persistence 매퍼(`toEntity`/`toDomain`)는 필드 복사만 한다 — 기본값은 도메인 생성 시점에서 정하고 어댑터에 `!= null ? x : 기본값` 폴백을 두지 않는다. 예외는 DB 컬럼 자체가 nullable인 레거시 값(예: `user_settings.strategy_suggestions`)뿐

### 신규 파일 배치
신규 코드는 해당 서브프로젝트 루트의 애그리게이트 모듈(`com.kista.api.<module>`·`com.kista.tradingcore.<module>`, 공용은 `com.kista.shared.<module>`) 안에 `domain/model` + `application/{usecase,port/output,service}` + `adapter/{in,out}` 구조로 추가한다. 모듈별 NamedInterface 공개 범위·internal 패키지는 `docs/agents/modules/<루트>/<ID>.md`가 SSOT.
- broker 외부에서 필요한 기능은 `com.kista.tradingcore.broker.application.port.output`에 신규 `*Port`를 만들어 노출한다(`adapter/out/*`은 비공개)
- 여러 모듈을 집계하는 inbound 관심사(크로스모듈 컨트롤러·전역 `@ControllerAdvice`)는 `com.kista.api.web`, trading-core 전역 web 관심사(`TradingExceptionHandler`)는 `com.kista.tradingcore.web`에 둔다. 특정 애그리게이트 컨트롤러는 그 모듈의 `adapter/in/web`
- persistence base entity·대칭키 암호화·스케쥴러 공통 골격은 `com.kista.shared.platform`에 둔다. 주문생성 알고리즘 커널(전략 계산·position 값객체·`PlannedOrder`·`AccountBalance`)은 `com.kista.tradingcore.matching`(`"kernel"`)에 둔다. 가드: `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`·`matching_must_not_depend_on_other_modules`
- 같은 하위 패키지 이름(`domain/strategy` 등)을 모듈마다 다른 의미로 재사용하지 않는다(matching `domain/strategy`=주문생성 전략, trading `domain/creation`=등록 정책). 값 타입은 `domain/model/<x>`, 도메인 로직과 그 값이 한 하위 도메인이면 한 패키지로 둔다(stats `domain/backtest`)
- `@Configuration`·`@TransactionalEventListener` 리스너·`@Scheduled`는 `application/service`에 두지 않는다(설정=`<module>/config`, 저장 트리거 리스너=`adapter/in/event`, 스케쥴러=`adapter/in/schedule`). 기존 예외: `TradingStatsService`의 캐시 무효화 `@EventListener`
- `@Aspect`는 저장소 전체에서 금지(가드 `no_aspects_in_codebase` — 모듈 간 런타임 결합이 생긴다, 횡단 관심사는 이벤트로)

모듈 간 참조가 얽히면 own-type 정의(아래 게이트 통과 시) 또는 이벤트 발행(`@TransactionalEventListener`, EPR 재시도) 패턴을 사용한다. 이관 경위는 `docs/history/modulith-migration-history.md`(필요시 Read).

**포트 인터페이스 위치·네이밍**
- 인바운드 포트는 `application/usecase/`, 아웃바운드 포트는 `application/port/output/`에 `*Port` 접미사로 둔다(`*Repository` 금지 — `*JpaRepository`와 혼동). 가드: `HexagonalArchitectureTest.outbound_port_interfaces_must_have_Port_suffix` 등 레이어 규칙
- 포트의 파라미터·반환 타입 record/class는 `domain/model/` 하위에 둔다. 컨트롤러 DTO와 겹치는 타입은 `domain/model/<도메인>`으로 옮기고 DTO에서 re-import한다

### 모듈 경계 own-type — 정당화 게이트
**프로세스 경계(root `:api` ↔ `:trading-core`)를 넘는 wire 타입(내부 HTTP API 요청·응답 body, Redis payload)은 own-type 복제가 아니라 `com.kista.shared.contract`(`:shared`)에 선언해 양쪽이 컴파일 타임에 공유한다 — 이 게이트를 거치지 않는다.** contract는 JDK+sharedkernel만 참조하는 outbound-zero 값 타입이라 양쪽이 의존할 수 있다. 새 내부 엔드포인트는 (1) `contract.<채널>`에 record를 두고 (2) trading-core 컨트롤러가 도메인 → contract로 매핑하며(도메인 record를 wire에 직접 반환 금지) (3) root 어댑터가 contract를 그대로 포트 시그니처에 쓴다. `InternalApiContractTest`가 이 규칙을 강제한다. 공유 어휘(enum 등)는 sharedkernel, wire 스키마(필드명이 곧 계약)는 contract — `docs/agents/modules/shared/contract.md`.

아래 게이트는 wire 타입이 아닌 **모듈 간 값 타입** 복제에 적용된다. 값 타입을 다른 모듈에 복제(own-type)하는 것은 다음 둘 중 하나를 만족할 때만 정당하다. 아니면 복제가 아니라 sharedkernel 승격 또는 소유권 이동 대상이다.
- **(a) 순환 불가피**: 통합하면 모듈 순환이 생기고, 소유권 이동으로도 끊을 수 없다.
- **(b) 외부 계약 분리**: 두 값 집합이 각기 다른 외부 계약(증권사 wire 포맷, DB 컬럼, 업스트림 피드, HTTP 응답 스키마)에 묶여 독립적으로 버전이 오를 수 있다.

**(b) 주장 시 증거 의무**: "외부 계약에 묶여 있다"는 서술만으로는 통과하지 못한다 — 그 값이 wire 포맷 변환 코드(어댑터의 switch/if 매핑, 예: `KisOrderApi.resolveOrderDvsn`)에서 실제로 소비되는지 grep으로 확인해 파일:라인을 근거로 남기고, 확인 없는 "~로 보인다/추정된다" 단정은 문서에 적지 않는다.

포트 역전(DIP)은 값 타입 복제가 아니라 이 게이트 대상이 아니다 — 사례 → `own-type-ledger.md` "포트 역전(DIP) 사례".

**복제본(replica)·제3자 구현**: 정의자도 데이터 소유자도 아닌 모듈(예: web)의 구현을 선례로 삼지 않고, 모듈 경계를 넘는 데이터 의존은 "소유자 이벤트 + 소비자 소유 복제본" 또는 내부 HTTP API 중에서 고른다. 신규 복제 테이블은 마이그레이션에서 **기존 데이터를 반드시 백필**하고, 원본의 **모든 쓰기 지점을 전수 확인해 발행**을 건다(복제본 필수 조건 2가지·빈 결과 실패 양상 3종 → `own-type-ledger.md` "복제본·제3자 구현 설계 규칙").

**신규 own-type 복제·게이트 판정 시 `docs/agents/own-type-ledger.md` 필수 Read**(자동 로드 안 됨). (a)(b) 어느 쪽도 아니면 복제하지 말고 sharedkernel 승격 또는 소유권 이동을 먼저 검토한다.

### adapter/out 간 JpaRepository 접근
- `*JpaRepository`는 package-private(가드 `persistence_jpa_repositories_must_be_package_private`) — 다른 패키지 adapter가 DB 조작이 필요하면 아웃바운드 포트(`application/port/output/`)를 거친다

### GlobalExceptionHandler 자동 예외 처리
- Controller에서 catch/rethrow 하지 않는다 — 도메인 예외 → HTTP 코드 매핑은 `GlobalExceptionHandler`가 SSOT
- **예외 매핑 SSOT**: advice별 `MAPPINGS` 테이블 하나가 기준이다 — `ProblemDetailMappings.withGeneric(...)` + `catchAll`로 처리하고, 같은 예외를 `@ExceptionHandler` 목록에 중복 나열하지 않는다(헤더·고유 로직이 필요한 전용 핸들러만 별도 메서드: `User.CooldownException`, `BrokerApiException`, 검증 실패 등)
  - 범용 예외 타입 선택: 부재=`NoSuchElementException`(404, 고정 문구라 메시지에 진단용 id 허용), 상태=`IllegalStateException`, 입력=`IllegalArgumentException`(둘 다 400, 메시지 원문이 detail). application·domain의 IAE/ISE 리터럴은 "~니다."로 끝나야 한다 — 가드 `ExceptionMessageFormatTest`(기존 위반은 `exception-message-allowlist.txt`에 동결, 고치면 지운다)
- **ProblemDetail 응답 계약**: `detail`은 사용자에게 그대로 보여줄 수 있는 한국어 문구다(완전한 문장은 "~습니다."+마침표). 내부 정보(URL·응답 바디·계좌번호·enum 원문·영어 프레임워크 메시지)는 detail에 싣지 말고 로그로 — 영어 내부 정보인 프레임워크 예외는 `Mapping.fixedDetail`로 고정 문구를 쓴다. 예외: 검증 실패(`MethodArgumentNotValidException`)는 "[field: message]" 형식 유지
- **기계 판독 코드 `code`**: UI가 분기하거나 문구를 달리 보여줘야 하는 예외만 `ErrorCode`(`com.kista.shared.platform.web`)를 `Mapping`에 지정한다. 상수 이름 변경·삭제 금지(kista-ui 계약). 추가 절차: ① `ErrorCode` 상수 ② 핸들러 `Mapping(status, title, code, fixedDetail)` ③ 같은 예외 클래스가 여러 의미면 서브클래스 분리(`resolve()`가 계층 하위부터 탐색 — 예: `AlreadyOrderedTodayException`) ④ openapi는 `ErrorCodeOpenApiCustomizer`가 자동 반영 → kista-ui `gen:types` + 문구 매핑
- **내부 API 경유 한계**: root 내부 API 어댑터는 trading-core 응답의 status만 보고 `Admin*` 예외를 되살린다. 같은 status에 서로 다른 코드가 실려 오는 경로가 생기면 `InternalApiErrorDetails`가 `code`도 읽어 전달하도록 확장한다

### 모듈 문서가 SSOT인 규칙 (해당 모듈 작업 시 자동 로드, 그 외엔 직접 Read)
- Account ↔ Strategy 분리·전략 원장 상한, 런타임 매매 정책 적용 → `modules/tradingcore/trading.md`
- 매매 공식·VR 공식(변경 금지 — 단위 테스트로 검증) → `modules/tradingcore/trading-formulas.md`
- 런타임 설정 API 엔드포인트 규칙 → `modules/api/admin.md`

### 아키텍처 맵 flows.yml 동기화
아키텍처 맵의 랜드스케이프·흐름·생명주기 탭은 `src/test/resources/architecture/flows.yml` 수기 원본이다. `ArchitectureMapTest`는 `code:` 참조 존재와 enum 상수 집합만 검증하고 의미는 검증하지 않는다. 다음을 바꾸면 같은 작업에서 `flows.yml`도 함께 갱신한다.
- 매매·스케쥴러·주문 흐름의 동작 → 해당 단계의 `desc`·`cond`·`state`·`fail`
- 프로세스 간 통신 경로(HTTP·Redis·이벤트) → `lane`·`to`
- 상태 전이 규칙 → `lifecycles`의 전이
- 사용자·관리자 기능 추가·삭제 → `flows`와 `landscape`
- 주기 점검 요청("아키텍처 맵 최신화") 시에는 `flows.yml` 마지막 수정 커밋 이후 `git log`에서 `code:` 참조 클래스·흐름 관련 변경을 추려 대조한다

### MetaController (enum SSOT)
- `GET /api/meta` — enum 메타(label/description 포함) 단일 번들 제공 — UI에서 enum 리터럴 하드코딩 금지

### 수량 변수명 규칙
- **보유 잔고 수량**: `holdings` (avgPrice와 짝), **주문/체결 수량**: `quantity` (단건 거래)
- 원장 `holdings`는 BigDecimal(`Quantities.normalize` — 정수 주식은 꼬리 0·지수 없이 정수 표기, JSON도 `5`)
- 복합 수량 필드: `orderedQuantity`/`filledQuantity` (`orderedQty`/`filledQty` 금지)

### User/Account/Strategy 공유 enum — sharedkernel
`User.UserRole`/`UserStatus`/`NotificationType`, `Strategy.Type`/`Status`/`Ticker`/`CycleSeedType`, `Account.Broker`, `NotificationChannel`은 여러 모듈이 공유하는 `com.kista.shared.kernel` 독립 타입이다 — nested enum으로 재도입 금지. DB `@Enumerated(STRING)` 컬럼 상수명은 절대 바꾸지 않는다.
- 신규 유저 기본 알림 채널: `User.DEFAULT_CHANNEL = NotificationChannel.NONE` — 서비스/컨트롤러에서 직접 하드코딩 금지

### 도메인 Command 명명 규칙
- `application.usecase` 인바운드 포트 입력 모델은 `*Command` suffix(`*Request` 금지 — 외부 HTTP DTO로 오인), `domain/model/<도메인>/` 하위에 둔다

### Ticker enum (sharedkernel.StrategyTicker)
- KIS 거래소 코드는 `KisExchangeRegistry` 전용 — 새 종목 추가 시 양쪽 갱신 필수
- PRIVACY·VR 신규 등록 ticker는 `StrategyCreationSettings.ticker()` 고정값 정책으로 결정하고 다른 입력은 거부한다(기본값: PRIVACY=SOXL, VR=TQQQ)

### Virtual Thread
- Virtual Thread 활성화됨 — `@Async`, `CompletableFuture` **사용 금지**, 대기 시 `Thread.sleep()` 사용

### 계좌번호 마스킹 (AccountNumberMasker)
- `com.kista.shared.kernel.AccountNumberMasker.mask(accountNo)`가 마스킹 단일 알고리즘(SSOT)이다(숫자 외 문자 제거 후 마지막 4자리만 `"****1234"`). 신규 DTO는 이 유틸을 재사용하고 개별 `substring`/`replace` 마스킹을 새로 쓰지 않는다

### 상태 종속 민감 필드 마스킹
특정 상태에서만 유효한 민감 필드(예: `User.rejectReason` — REJECTED에서만 노출)는 DB엔 보존하고 응답 DTO `from()`에서 상태 조건부로 마스킹한다. 상세 → `modules/api/user.md`.

### JPA 연관 매핑
- `@ManyToOne`에는 `@JoinColumn(name="...", nullable=false)`를 항상 명시한다 — 생략하면 Hibernate 추론(`필드명_id`)에 의존한다

### Flyway
- 운영 DB에 **이미 적용된 마이그레이션 파일은 절대 수정 금지**(V1 포함) — checksum 불일치로 기동 즉시 크래시한다. 새 마이그레이션은 소유 서비스 디렉토리의 최신 버전 다음 번호로 만든다
- 서비스별 디렉토리: root `src/main/resources/db/migration`, trading-core `trading-core/src/main/resources/db/migration-trading`. 신규 테이블은 소유 서비스 디렉토리에만 추가하고(스키마 소유 → architecture.md "DB 스키마 5분리") **다른 서비스 소유 스키마 테이블은 FK 포함 참조 금지**
- **root 마이그레이션은 expand/contract**: `kista-api`·`kista-scheduler`가 독립 배포되므로 직전 이미지와 호환돼야 한다 — 컬럼 추가는 nullable 또는 DEFAULT, 드롭·리네임·SET NOT NULL·타입 변경은 두 배포로 분리(trading-core는 제외). CI `check-migrations.sh`가 막는다
- enum 컬럼은 `VARCHAR(20)` + `@Enumerated(EnumType.STRING)` 단독. PostgreSQL 네이티브 ENUM(`CREATE TYPE`)과 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 금지
- 컬럼 순서·FK `ON DELETE`·Entity↔SQL 크로스체크(NOT NULL 불일치는 런타임 무증상)·재생성 패턴·복제 테이블 배포 순서·EPR 고아 row·로컬 checksum 복구 → `docs/agents/flyway.md`

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도

### AES-256 암호화 위치·컬럼 크기
- KIS 자격증명·계좌번호·텔레그램 봇 토큰·이메일은 엔티티 컬럼에 `@Convert(converter = AesStringConverter.class)`(`shared.platform.crypto`, autoApply 없음)를 명시해 암복호화한다. 어댑터·매퍼는 평문 복사만 하고 `AesCryptoService`를 직접 쓰지 않는다(`@DataJpaTest`는 `DataJpaTestBase`가 `AesCryptoService` 빈을 import)
- AES-256 GCM + Base64는 입력 ~180자 → 출력 ~260자라 VARCHAR(255)를 넘는다. 암호화 저장 컬럼은 Entity `length=512` 이상, Flyway도 동일(`DataIntegrityViolationException` 방지)
- AES-GCM이 비결정론적이라 암호화 컬럼을 JPQL/파생 쿼리 조건에 쓰면 매칭되지 않는다 — 조회는 `*_hash`(`AccountNoHasher`) 경유, 해시는 어댑터가 평문으로 계산한다
- 컨버터는 엔티티를 로드할 때마다 복호화한다 — 테스트·시드의 raw SQL INSERT는 반드시 `crypto.encrypt(...)` 암호문을 넣는다. 복호화 불가 값이 한 행이라도 있으면 그 엔티티를 읽는 모든 경로(배치 재개 포함)가 실패한다

### 보안·필터 (SecurityConfig·JWT·내부 인증·CORS)
- **필터 이중 등록 방지**: `@Component` Filter + `addFilterBefore()` 조합은 `FilterRegistrationBean.setEnabled(false)` 필수. `SecurityConfig`에 새 Filter를 추가하면 `@Import(SecurityConfig.class)`를 쓰는 **모든** `@WebMvcTest`에도 그 Filter를 `@Import`해야 한다(누락 시 `NoSuchBeanDefinitionException` → 다른 테스트까지 전파)
- **내부 인증**: `/api/internal/**`는 `X-Internal-Token`을 `INTERNAL_API_TOKEN`과 대조하고(`hasRole("INTERNAL")`, `InternalTokenAuthFilter`는 JWT 필터보다 먼저) 빈값이면 기동 실패한다. `@WebMvcTest` 패턴: `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})` + `@TestPropertySource(properties = "internal.api.token=test-token")` + `.header("X-Internal-Token", "test-token")` (`FidaOrderControllerTest` 참고)
- **JWT(ES256)**: `JwtAuthFilter`는 principal을 `UUID`로 저장하고 catch 절은 `Exception`이다(`JwtException`만 잡으면 NPE·IAE 미처리 → 익명 사용자 → 403). **`JwtDecoder` @Bean은 `JwtDecoderConfig.java`에 분리**(`SecurityConfig`에 두면 `JwtAuthFilter` 순환 참조로 기동 실패)
- **인증 실패 코드**: `SecurityConfig`에 `.exceptionHandling()` + `authenticationEntryPoint`를 반드시 설정한다(미설정 시 401 대신 403)
- **CORS**: `CORS_ALLOWED_ORIGINS` 빈 목록이면 기동 실패(trading-core만 비-prod 기본값 `http://localhost:3000`). allowedMethods에 **PATCH 필수**(미포함 시 전략 중지/재개 등 PATCH 403)
- **라우트 규칙 순서(첫 매치 우선)**: 공통 permitAll → `/api/internal/**` → `/api/admin/**` → 셸 기여 `SecurityRoutePolicy`(root `RootSecurityPolicy`·trading-core `TradingSecurityPolicy`) → `anyRequest().authenticated()`. 프로세스 고유 permitAll은 `SecurityConfig`에 직접 쓰지 말고 셸 정책으로 기여한다(`SecurityConfigOrderingTest`가 잠금). permitAll 경로를 검증하는 `@WebMvcTest`는 해당 정책도 `@Import`
- **`@EnableJpaAuditing`**은 `@SpringBootApplication`이 아니라 `JpaAuditingConfig.java`(`com.kista.shared.platform.persistence`)에 선언한다(아니면 `@WebMvcTest` `BeanCreationException`)
- ADMIN 권한 관리(seed·`/api/admin/**`·audit_logs) → `modules/api/admin.md`

### 파라미터 개수·브로커 호출 실패 정책
- 메서드 파라미터 8개 이상이거나 같은 타입이 인접해 자리 바뀜이 조용히 통과할 수 있으면 record로 묶는다 — 같은 값을 이미 담은 기존 record(`VrRampParams`·`ResolvedCreation` 등)가 있으면 새로 만들지 말고 재사용
- 브로커·시세 호출 실패 정책은 `BrokerCallGuard`로 통일: 실패 시 중단은 `wrap`(fail-closed), 건너뛰고 대체값으로 진행은 `tryGet`(fail-open). 신규 코드는 서비스에서 브로커 호출을 raw `catch (RuntimeException)`으로 감싸지 않는다 — 단 이벤트 발행·분기 처리가 필요한 경우(예: 실패 종목 집계, 관리자 알림 이벤트)는 예외(기존 catch는 대부분 이 예외에 해당). 이벤트·분기 없이 로그 후 대체값만 반환하는 경우는 예외 없이 `tryGet`을 쓴다. 벤더 rate limit(HTTP 429·벤더 한도 코드)은 각 어댑터 HTTP 클라이언트에서 `BrokerRateLimitException`으로 변환한다

### 주석 규칙
- 신규 코드는 주석을 함께 쓴다 — 필드는 `// 역할 한 줄` 인라인, 비즈니스 로직 블록 직전에 단계 설명 한 줄, 코드값은 `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용

### @Transactional 내부 외부 시스템 호출 금지
- 외부 HTTP 호출(텔레그램, KIS 등)을 `@Transactional` 내부에서 하지 않는다 — 롤백 시 취소 불가
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)`. 이벤트는 `application/event/`, 리스너는 `adapter/out/`
- 이벤트가 외부 호출이 아니라 **저장 트리거**(인바운드 성격, 예: admin `AppErrorRaisedListener`)면 리스너를 `adapter/in/event/`에 두고, 정리 로직은 usecase 또는 `application/port/output` 쓰기 포트 뒤에 둔다(인바운드 어댑터는 `application.service`를 직접 쓸 수 없다)
- 발행 지점이 트랜잭션 밖이면 동기 `@EventListener`를 쓴다(AFTER_COMMIT은 트랜잭션 없을 때 이벤트를 버린다)

### 시간 기준 정책 (KST 단일 기준)
- **거래일(tradeDate) = KST 일자** — 매매가 실행·정산되는 KST 아침이 속한 날. DB(`orders.trade_date`)·도메인·API 모두 동일 값, 변환 없음
- **`privacy_trade_bases.release_date` = FIDA 발행일 원본(KST)** — 거래일 아님. 발행일↔거래일(+1일)은 `PrivacyDates.releaseDateFor()/tradeDateOf()`만 사용
- **외부 원본 참조 데이터는 원본 기준 유지**: `us_market_holidays`(US 달력일) 등 KST↔US 변환은 해당 어댑터 내부에서만 `UsTradeDates`(`com.kista.shared.platform.time`)로 한다. 도메인·서비스·orders persistence에서 사용 금지 — 가드 `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`(allowlist 어댑터가 코드에 있다)
- **Toss API**: 주문 접수일(KST) 기준, 변환 없음. `TossOrderApi.fetchExecutions()`는 전날 저녁 선접수 대응으로 `queryFrom = from - 1일` 조회 후, `filledAt`의 뉴욕 일자(US 세션일)를 `UsTradeDates.toKstTradeDate`로 바꾼 KST 거래일로 재필터(KST 달력일을 쓰면 D-1 22:00 이후 장중 체결이 D에서 빠진다). 예외: 일봉 캔들 `date()`는 US 세션일이라 `TossPriceApi.getClosingPrice`가 KST 거래일 D를 US 세션 D-1로 변환한다(KIS `fetchConfirmedClose`와 동일)
- Instant ↔ KST 일자 경계는 `atStartOfDay(TimeZones.KST)` 단일 관용구 — `ZoneOffset.UTC` 자정 경계 금지
- KST 일자 구간의 상한(당일 포함 반개구간)을 Instant로 바꿀 때는 `KstDays.endExclusive(day)`(`com.kista.shared.kernel`)를 쓴다 — `plusDays(1).atStartOfDay(...)`를 인라인하지 않는다
- `ZoneId.of`는 `TimeZones` 상수(`KST`·`NY`)로만 — main 코드에서 직접 호출 금지(가드 `zoneId_of_must_only_be_called_in_TimeZones`)
- 거래일 경계 시각 `DstInfo.SCHEDULER_RUN_TIME = 04:30 KST` — preview·수동실행·주문취소는 `DstInfo.nextTradeDate(ZonedDateTime)`를 쓴다
- 현재 시각은 앱 루트 `ClockConfig`의 KST `Clock` 빈을 주입해 `X.now(clock)`으로 읽는다. 도메인·정적 유틸은 시각을 파라미터로 받는다. 테스트는 `Clock.fixed`(`FixedClockTestConfig`) — 가드 `HexagonalArchitectureTest.main_code_must_not_read_system_clock_directly`
- 인라인 `.minusDays(1)`/`.plusDays(1)`로 날짜 기준 변환 금지 — `UsTradeDates`/`PrivacyDates` 경유

### 소프트 삭제(Soft Delete) 패턴
- `users`, `accounts`, `strategy`, `strategy_cycle`, `cycle_position`, `app_error_logs` — `deleted_at` 컬럼, `@SQLRestriction("deleted_at IS NULL")` 선언
- **`nativeQuery = true` 쿼리는 `@SQLRestriction` 미적용** — `AND tc.deleted_at IS NULL` 수동 명시 필수 (`findAllActiveCycles` 등)
- Cascade 순서: 서비스 레이어에서 사이클 → 계좌 → 사용자 순으로 명시 처리 (DB FK CASCADE 미작동)
