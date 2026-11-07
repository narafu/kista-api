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
- web DTO의 `toCommand()`도 기본값을 정하지 않는다 — 생략 필드는 null 그대로 넘기고 도메인 resolver·팩토리·서비스 한 곳이 정한다(예: INFINITE `divisionCount`는 `InfiniteCreationResolver`가 정책 기본값으로, `broker`는 `AccountService`가 KIS로). `0` 같은 sentinel로 생략을 표현하지 않는다
- USD 금액(가격·원장 금액) 반올림 자릿수는 `Currency.USD.amountScale()`을 쓴다 — 리터럴 `2`·모듈별 private `MONEY_SCALE` 금지. 원장(`cycle_position` 금액·평단, 통화 무관 `numeric(20,2)`)을 다루며 코인 DCA 계좌도 지나는 경로는 `AccountBalance.LEDGER_SCALE`, 주문 금액처럼 시장 통화가 결정하는 값은 `Market`/`Currency`의 `amountScale()`을 쓴다. 비율·퍼센트 자릿수는 별개다

### 신규 파일 배치
신규 코드는 해당 서브프로젝트 루트의 애그리게이트 모듈(`com.kista.api.<module>`·`com.kista.tradingcore.<module>`, 공용은 `com.kista.shared.<module>`) 안에 `domain/model` + `application/{usecase,port/output,service}` + `adapter/{in,out}` 구조로 추가한다. 모듈별 NamedInterface 공개 범위·internal 패키지는 `docs/agents/modules/<루트>/<ID>.md`가 SSOT.
- broker 외부에서 필요한 기능은 `com.kista.tradingcore.broker.application.port.output`에 신규 `*Port`를 만들어 노출한다(`adapter/out/*`은 비공개)
- 여러 모듈을 집계하는 inbound 관심사(크로스모듈 컨트롤러·전역 `@ControllerAdvice`)는 `com.kista.api.web`, trading-core 전역 web 관심사(`TradingExceptionHandler`)는 `com.kista.tradingcore.web`에 둔다. 특정 애그리게이트 컨트롤러는 그 모듈의 `adapter/in/web`
- persistence base entity·대칭키 암호화·스케쥴러 공통 골격은 `com.kista.shared.platform`에 둔다. 주문생성 알고리즘 커널(전략 계산·position 값객체·`PlannedOrder`·`AccountBalance`)은 `com.kista.tradingcore.matching`(`"kernel"`)에 둔다. 가드: `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`·`matching_must_not_depend_on_other_modules`
- 같은 하위 패키지 이름(`domain/strategy` 등)을 모듈마다 다른 의미로 재사용하지 않는다(matching `domain/strategy`=주문생성 전략, trading `domain/creation`=등록 정책). 값 타입은 `domain/model/<x>`, 도메인 로직과 그 값이 한 하위 도메인이면 한 패키지로 둔다(stats `domain/backtest`)
- `@Configuration`·`@TransactionalEventListener` 리스너·`@Scheduled`는 `application/service`에 두지 않는다(설정=`<module>/config`, 저장 트리거 리스너=`adapter/in/event`, 스케쥴러=`adapter/in/schedule`). 기존 예외: `TradingStatsService`의 캐시 무효화 `@EventListener`(TradingReportReady·StrategyCapitalChanged 두 이벤트를 받고, 트랜잭션 동기화가 활성이면 afterCommit에 비운다. `@TransactionalEventListener`를 쓰지 않는 이유는 메모리 캐시 무효화에 EPR `event_publication` 행이 남지 않게 하기 위해서다)
- `@Aspect`는 저장소 전체에서 금지(가드 `no_aspects_in_codebase` — 모듈 간 런타임 결합이 생긴다, 횡단 관심사는 이벤트로)

모듈 간 참조가 얽히면 own-type 정의(아래 게이트 통과 시) 또는 이벤트 발행(`@TransactionalEventListener`, EPR 재시도) 패턴을 사용한다. 이관 경위는 `docs/history/modulith-migration-history.md`(필요시 Read).

**포트 인터페이스 위치·네이밍**
- 인바운드 포트는 `application/usecase/`, 아웃바운드 포트는 `application/port/output/`에 `*Port` 접미사로 둔다(`*Repository` 금지 — `*JpaRepository`와 혼동). 가드: `HexagonalArchitectureTest.outbound_port_interfaces_must_have_Port_suffix` 등 레이어 규칙
- 포트의 파라미터·반환 타입 record/class는 `domain/model/` 하위에 둔다. 컨트롤러 DTO와 겹치는 타입은 `domain/model/<도메인>`으로 옮기고 DTO에서 re-import한다

### 모듈 경계 own-type — 정당화 게이트
- 프로세스 경계(내부 HTTP·Redis)를 넘는 wire 타입은 own-type 복제가 아니라 `com.kista.shared.contract`에 선언한다(`InternalApiContractTest`가 강제).
- 모듈 간 값 타입 복제는 (a) 순환 불가피 (b) 외부 계약 분리 둘 중 하나일 때만 정당하고, (b)는 wire 변환 코드 소비를 grep으로 확인한 파일:라인 증거가 의무다.
- 신규 own-type 복제·게이트 판정 시 `docs/agents/own-type-ledger.md`를 작업 시 Read(자동 로드 안 됨).

→ 상세: `docs/agents/own-type-ledger.md` "모듈 경계 own-type — 정당화 게이트"

### adapter/out 간 JpaRepository 접근
- `*JpaRepository`는 package-private(가드 `persistence_jpa_repositories_must_be_package_private`) — 다른 패키지 adapter가 DB 조작이 필요하면 아웃바운드 포트(`application/port/output/`)를 거친다

### GlobalExceptionHandler 자동 예외 처리
- Controller에서 catch/rethrow 금지 — advice별 `MAPPINGS` 테이블이 예외→HTTP 매핑 SSOT이고 같은 예외를 `@ExceptionHandler`에 중복 나열하지 않는다.
- ProblemDetail `detail`은 사용자에게 보여줄 한국어 문장이며 내부 정보(URL·응답 바디·계좌번호·영어 프레임워크 메시지)를 싣지 않는다. application·domain의 IAE/ISE 리터럴은 "~니다."로 끝난다(가드 `ExceptionMessageFormatTest`).
- `ErrorCode` 상수 이름 변경·삭제 금지(kista-ui 계약).

→ 상세: `docs/agents/modules/api/web.md` "GlobalExceptionHandler 자동 예외 처리"

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
- 운영 DB에 이미 적용된 마이그레이션 파일은 수정 금지(V1 포함, checksum 불일치로 기동 크래시) — 새 마이그레이션은 소유 서비스 디렉토리의 최신 버전 다음 번호.
- root 마이그레이션은 expand/contract(컬럼 추가는 nullable 또는 DEFAULT, 드롭·리네임·SET NOT NULL·타입 변경은 두 배포로 분리, trading-core 제외).
- enum 컬럼은 `VARCHAR(20)` + `@Enumerated(EnumType.STRING)` 단독 — 네이티브 ENUM 금지. 다른 서비스 소유 스키마 테이블은 FK 포함 참조 금지.

→ 상세: `docs/agents/flyway.md` (마이그레이션 디렉토리 작업 시 자동 로드)

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도

### AES-256 암호화 위치·컬럼 크기
- 암호화 컬럼은 엔티티에 `@Convert(converter = AesStringConverter.class)`를 명시하고 어댑터·매퍼에서 직접 암복호화하지 않는다. 컬럼은 `length=512` 이상(Flyway도 동일).
- 암호화 컬럼은 쿼리 조건에 쓰지 않는다(`*_hash` 경유). 테스트·시드 raw SQL INSERT는 반드시 `crypto.encrypt(...)` 암호문을 넣는다.

→ 상세: `docs/agents/modules/shared/platform.md` "AES-256 암호화 위치·컬럼 크기"

### 보안·필터 (SecurityConfig·JWT·내부 인증·CORS)
- 필터 이중 등록 방지: `@Component` Filter + `addFilterBefore()`는 `FilterRegistrationBean.setEnabled(false)` 필수.
- `/api/internal/**`는 `X-Internal-Token` 필수이고 `INTERNAL_API_TOKEN`이 빈값이면 기동 실패. `SecurityConfig`에 `authenticationEntryPoint`가 없으면 401 대신 403이 된다.
- CORS allowedMethods에 PATCH 필수. 라우트 규칙은 첫 매치 우선(공통 permitAll → internal → admin → 셸 정책 → authenticated).

→ 상세: `docs/agents/modules/shared/platform.md` "보안·필터"

### 파라미터 개수·브로커 호출 실패 정책
- 메서드 파라미터 8개 이상이거나 같은 타입이 인접해 자리 바뀜이 조용히 통과할 수 있으면 record로 묶는다 — 같은 값을 이미 담은 기존 record(`VrRampParams`·`ResolvedCreation` 등)가 있으면 새로 만들지 말고 재사용
- 브로커·시세 호출 실패 정책은 `BrokerCallGuard`로 통일: 실패 시 중단은 `wrap`(fail-closed), 건너뛰고 대체값으로 진행은 `tryGet`(fail-open). 신규 코드는 서비스에서 브로커 호출을 raw `catch (RuntimeException)`으로 감싸지 않는다 — 단 이벤트 발행·분기 처리가 필요한 경우(예: 실패 종목 집계, 관리자 알림 이벤트)는 예외(기존 catch는 대부분 이 예외에 해당). 이벤트·분기 없이 로그 후 대체값만 반환하는 경우는 예외 없이 `tryGet`을 쓴다. 벤더 rate limit(HTTP 429·벤더 한도 코드)은 각 어댑터 HTTP 클라이언트에서 `BrokerRateLimitException`으로 변환한다

### 주석 규칙
- 신규 코드는 주석을 함께 쓴다 — 필드는 `// 역할 한 줄` 인라인, 비즈니스 로직 블록 직전에 단계 설명 한 줄, 코드값은 `"840" // 국가코드: 미국` 형식
- Javadoc·블록 주석 금지 — `//` 인라인만 사용
- 로그 문맥은 `[strategyId={}]`·`[accountId={}]`·`[orderId={}]`·`[userId={}]` 접두사로 시작한다. 본문 중간 `key=`로 쓰지 않는다.
- `log.error`는 예외 객체를 마지막 인자로 넘긴다. `log.warn`은 `e.getMessage()`만 남기고 스택이 필요하면 error로 올린다.

### @Transactional 내부 외부 시스템 호출 금지
- 외부 HTTP 호출(텔레그램, KIS 등)을 `@Transactional` 내부에서 하지 않는다 — 롤백 시 취소 불가
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)`. 이벤트는 `application/event/`, 리스너는 `adapter/out/`
- 이벤트가 외부 호출이 아니라 **저장 트리거**(인바운드 성격, 예: admin `AppErrorRaisedListener`)면 리스너를 `adapter/in/event/`에 두고, 정리 로직은 usecase 또는 `application/port/output` 쓰기 포트 뒤에 둔다(인바운드 어댑터는 `application.service`를 직접 쓸 수 없다)
- 발행 지점이 트랜잭션 밖이면 동기 `@EventListener`를 쓴다(AFTER_COMMIT은 트랜잭션 없을 때 이벤트를 버린다)

### 시간 기준 정책 (KST 단일 기준)
- 거래일은 KST 단일 기준이다. `ZoneId.of`는 `TimeZones` 상수로만, 현재 시각은 `Clock` 빈 주입(`X.now(clock)`), `UsTradeDates`는 허용 어댑터에서만 쓴다.
- Toss 날짜 처리 예외는 toss-api.md.

→ 상세: `docs/agents/modules/shared/platform.md` "시간 기준 정책", Toss는 `docs/agents/toss-api.md` "날짜 처리"

### 소프트 삭제(Soft Delete) 패턴
- `users`, `accounts`, `strategy`, `strategy_cycle`, `cycle_position`, `app_error_logs` — `deleted_at` 컬럼, `@SQLRestriction("deleted_at IS NULL")` 선언
- **`nativeQuery = true` 쿼리는 `@SQLRestriction` 미적용** — `AND tc.deleted_at IS NULL` 수동 명시 필수 (`findAllActiveCycles` 등)
- Cascade 순서: 서비스 레이어에서 사이클 → 계좌 → 사용자 순으로 명시 처리 (DB FK CASCADE 미작동)
