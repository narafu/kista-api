## 핵심 제약 사항

### Git 규칙
- `git push`는 사용자가 명시적으로 요청할 때만 실행 — 요청 없이 자동 푸시 금지, 요청하면 즉시 실행
- 커밋 전 author 확인 (`git config user.name`/`user.email`): `narafu <narafu@kakao.com>`
- 커밋 메시지는 한글, Conventional Commit 접두사(`feat(scope):`, `fix:`, `docs:`, `debug:` 등) + 명령형 제목
- 매매 시간대 배포 가드(`_deploy-role.yml`)는 `deploy-trading` 잡에만 `apply_trading_guard: true` — `deploy-api`·`deploy-scheduler`는 시간대 무관하게 배포 가능(`server-deploy.yml` 참고). 또한 변경 경로 게이팅(`changes` 잡, docker-infra.md "변경 경로 게이팅")으로 trading-core 산출물에 영향이 없는 커밋에서는 `deploy-trading` 자체가 실행되지 않아 이 가드도 발동하지 않는다 — 가드는 약화된 게 아니라 매매와 무관한 커밋에서 오탐하지 않을 뿐이다. 근거: 4a(`de693b5c`)에서 매매 알림 6종(`TradingAlertNotifier`/`CycleEndedNotifier`/`CycleLifecycleNotifier`/`OrderCancelFailureNotifier`/`TradingReportNotifier`/`PrivacyAlertNotifier`)이 root notify에서 trading-core로 이관되며 EPR 재발행 소유(`REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART=true`, docker-compose.yml)도 kista-trading으로 넘어갔다 — `deploy-trading`은 매매 배치 스레드 강제 종료 + 자기 EPR 재발행 두 리스크 모두를 진다. `KistaApplication.scanBasePackages`가 trading-core 패키지를 원천 배제하므로 kista-scheduler엔 매매 관련 빈이 아예 없어 EPR 재발행도 root 자체 이벤트(가입·승인·finance·KbLand alert 등)만 건드린다 — 매매 시간대와 무관해 가드 불필요. (`event_publication`이 서비스별로 분리돼 kista-trading은 `trading.event_publication`, kista-scheduler는 root `public.event_publication`을 각각 재발행한다 — kista-api는 false 유지, 둘 다 true면 이중 claim) (과거 kista-scheduler에 이 가드가 있었던 건 4a 이관 이전 가정이 남은 stale 설정이었다 — `f631ba52`가 이관 8시간 뒤 작성한 주석인데도 옛 가정을 그대로 반영해 발생)

### application/port/output/ 네이밍 규칙
- 아웃바운드 포트 인터페이스: `*Port` 접미사. `*Repository` 접미사 사용 금지 — adapter 레이어 `*JpaRepository`와 혼동 유발

### 신규 파일 배치
신규 코드는 레거시 최상위가 아닌 해당 애그리게이트 모듈(`com.kista.<module>`) 안에 추가한다 — `domain/model` + `application/{usecase,port/output,service}` + `adapter/{in,out}` 서브구조 준수. 포트는 `domain/port/{in,out}`이 아닌 `application/{usecase,port/output}`에 위치. 모듈별 정확한 NamedInterface 공개 범위·internal 패키지는 `docs/agents/modules/<module>.md`(해당 모듈 디렉토리 작업 시 자동 로드)가 SSOT — 신규 코드 추가 전 해당 모듈 문서에서 확인할 것.
- broker `adapter/out/*`은 NamedInterface 비공개라 모듈 밖에서 접근 불가 — 새 기능이 필요하면 `com.kista.broker.application.port.output`에 신규 `*Port`를 만들어 노출한다
- 레거시 `com.kista.adapter`/`com.kista.application` shim은 전부 소멸했다. 여러 모듈을 집계하는 앱 레벨 inbound 관심사(크로스모듈 컨트롤러·`GlobalExceptionHandler` 등 전역 `@ControllerAdvice`·`@Aspect`)는 `com.kista.web`(pure inbound sink, NamedInterface 0개)에 추가 — 특정 애그리게이트 컨트롤러는 그 모듈의 `adapter/in/web`으로
- persistence base entity·대칭키 암호화·스케쥴러 공통 골격은 `com.kista.platform`(인프라 leaf)에 추가 — 다른 `com.kista` 모듈 참조 시 `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`가 빌드를 깬다
- 주문생성 알고리즘 커널(전략 계산·position 값객체·`PlannedOrder`·`AccountBalance` 등)은 `com.kista.matching`에 추가, `"kernel"` NamedInterface로 공개 — `com.kista.sharedkernel` + `com.kista.privacy` 외 의존 금지(`HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`)

모듈 간 참조가 얽히면 own-type 정의(아래 "모듈 경계 own-type" 게이트 통과 시) 또는 이벤트 발행(`@TransactionalEventListener`, EPR 재시도 보장) 패턴을 사용한다. 마이그레이션 경위·순환 해소 상세는 `docs/agents/modulith-migration-history.md` 참고(필요시 Read).

### 모듈 경계 own-type — 정당화 게이트
값 타입을 다른 모듈에 복제(own-type)하는 것은 다음 둘 중 하나를 만족할 때만 정당하다. 아니면 복제가 아니라 sharedkernel 승격 또는 소유권 이동 대상이다.
- **(a) 순환 불가피**: 통합하면 모듈 순환이 생기고, 소유권 이동으로도 끊을 수 없다.
- **(b) 외부 계약 분리**: 두 값 집합이 각기 다른 외부 계약(증권사 wire 포맷, DB 컬럼, 업스트림 피드, HTTP 응답 스키마)에 묶여 독립적으로 버전이 오를 수 있다.

**(b) 주장 시 증거 의무**: "외부 계약에 묶여있다"는 서술만으로는 통과 못 한다 — 그 값이 실제로 wire 포맷 변환 코드(어댑터의 switch/if 매핑, 예: `KisOrderApi.resolveOrderDvsn` 류)에서 소비되는지 grep으로 확인하고 그 파일:라인을 근거로 남길 것. `OrderDirection`/`OrderType` 3중복제 사례 — "KIS/Toss wire 포맷이 실려있다"고 서술만 하고 실측을 안 해서, 실제로는 enum 값 자체엔 계약이 없고 매핑은 어댑터 코드가 담당한다는 사실이 나중에야 드러나 뒤늦게 sharedkernel로 승격·복제본 삭제됐다. 새 own-type 복제·게이트 판정을 문서에 적을 때는 이 확인을 거치지 않은 "~로 보인다/추정된다" 식 단정을 넣지 말 것.

**포트 역전(DIP)은 값 타입 복제가 아니므로 이 게이트·아래 목록에 포함하지 않는다** — "포트를 필요로 하는 쪽이 정의하고 데이터를 가진 쪽이 구현"하는 정상 설계이며 own-type 인스턴스 번호를 부여하지 않는다: `ApprovalPolicyPort`(user 정의·admin 구현)/`BrokerEnabledPort`(sharedkernel 정의·admin 구현·account 소비)/`StrategyCreationPolicyPort`(sharedkernel 정의·admin 구현·trading 소비)/`ActiveStrategyCountPort`(user 정의·`com.kista.web.trading.ActiveStrategyCountAdapter` 구현)/`MockSimulationDataPort`(broker 정의·trading 구현). `HistoricalCandlePort`도 같은 이유(root가 trading-core 정의 인터페이스를 implements할 수 없는 컴파일 경계)로 `com.kista.sharedkernel.port`로 승격되어 admin/stats/trading-core 어느 쪽 소유도 아니게 됐다 — root `AlpacaIndexPriceAdapter`(stats)가 구현하고 trading-core `BacktestEngine`이 소비. `TradingUserProfilePort`는 한때 이 계열이었으나 지금은 trading이 정의하고 trading 자신이 구현하므로 포트 역전이 아니다(아래 "폐기된 전례" 참고).

**폐기된 전례 — 제3자(web) 구현은 컴파일 경계와 양립 불가**: `TradingUserProfilePort`는 한때 "정의자(trading)도 데이터 소유자(user)도 아닌 `com.kista.web`이 구현"하는 유일한 형태였다(`TradingUserProfileAdapter`). Modulith 슬라이스 순환(`Slice trading -> Slice user -> Slice trading`)은 그렇게 피할 수 있었지만, root(`:api`)의 `:trading-core` 컴파일 의존이 0이 되는 순간 root는 trading-core가 정의한 인터페이스를 구현하는 것 자체가 불가능해진다 — 스타일 문제가 아니라 컴파일 불가다. Task 12에서 이 메커니즘을 통째로 교체했다: trading-core가 자기 스키마(`trading.user_notify_profile`)에 사용자 알림·잔고검증·활성여부 복제본을 두고 자기 포트를 자기가 구현하며(`UserNotifyProfilePersistenceAdapter`), user는 `UserNotifyProfileChangedEvent`(sharedkernel)/`UserDeletedEvent`만 발행한다. `TradingUserProfileAdapter`는 삭제됐다.

**따라서 "제3자 구현"을 새 설계의 선례로 삼지 말 것** — 모듈 경계를 넘는 데이터 의존은 (1) 소유자가 데이터를 밀어주는 이벤트 + 소비자 소유 복제본, 또는 (2) 내부 HTTP API(`TradingCommandPort`/`TradingQueryPort` 계열) 중에서 고른다. `com.kista.web.trading.ActiveStrategyCountAdapter`(user 정의·web 구현)만 이 형태로 남아있는데, 이쪽은 이미 trading-core 내부 HTTP API를 호출하는 순수 HTTP 어댑터라 타입 의존이 없다 — 위치만 과거 잔재다.

**복제본을 쓸 때의 필수 조건 2가지**(user_notify_profile 사례에서 실측):
- **(a) 신규 복제 테이블은 마이그레이션에서 기존 데이터를 반드시 백필한다.** 복제본이 비면 소비처가 빈 결과를 정상 응답으로 받아 기능이 멈춘다. `TradingUserProfilePort` 3개 메서드의 실제 실패 양상이 서로 다르다는 점에 주의: `findAllByUserIds()` 빈 맵 → `BatchContextFactory`가 전략마다 `NoSuchElementException`을 던지고 잡아 `errorReportPort.reportError()`로 관리자 알림을 내보낸다(**시끄럽게** 전면 중단 — 전략 수만큼 알림이 쏟아진다). `findByUserId()` 빈 Optional → 전략 등록이 "사용자를 찾을 수 없습니다"로 거부된다. `findAllActive()` 빈 리스트 → `MarketEventNotifier`가 **조용히** 아무에게도 안 보낸다(예외·로그 없음). 셋 중 마지막이 발견이 가장 늦다.
- **(b) 원본의 모든 쓰기 지점을 전수 확인하고 발행을 건다.** 상태값은 `withStatus`/`withRejection` 같은 도메인 메서드명으로, 설정값은 필드명으로 grep한다. 포트 메서드명만 보고 판단하지 말 것 — `findAllActive()`가 실제로 `UserStatus.ACTIVE` 필터라는 사실은 소비처(`MarketEventNotifier`)와 구 어댑터를 읽어야만 드러났고, 이걸 놓쳤다면 복제본에 `is_active` 컬럼이 빠져 개장·마감 알림이 비활성 사용자에게까지 나갔을 것이다.

**신규 own-type 복제·게이트 판정 시 `docs/agents/own-type-ledger.md` 필수 Read** — 기존 (a)(b) 허용 사례 전체 목록(ReorderCommand/AdminOrderView/AdminAccountView/AdminPrivacyTradeBaseView 계열/InvestmentPoint/MarketSession/TossDailyCandle/TradeEventView 등)·DTO 이중복제 사례·단일 소유 포트 시그니처 타입·narrowing projection 원장. 자동 로드되지 않는다.

신규 broker/notify/privacy 포트 추가 시 이 게이트를 먼저 통과할 것 — (a)(b) 어느 쪽도 아니면 복제하지 말고 sharedkernel 승격 또는 소유권 이동을 먼저 검토한다.

### adapter/out 간 JpaRepository 접근 제한
- `*JpaRepository`는 package-private — 선언 패키지 외부에서 직접 import 시 컴파일 오류
- 다른 패키지 adapter에서 DB 조작이 필요하면 아웃바운드 포트(`application/port/output/`) 경유 필수
- 패턴: `com.kista.marketcalendar.adapter.out.alpaca.AlpacaCalendarAdapter` → `com.kista.marketcalendar.application.port.output.MarketHolidayStorePort` → `com.kista.marketcalendar.adapter.out.persistence.MarketCalendarPersistenceAdapter`
- 참고: `com.kista.broker.adapter.out.kis.KisAuthApi` → `com.kista.broker.application.port.output.BrokerTokenCachePort` → `com.kista.broker.adapter.out.persistence.KisTokenPersistenceAdapter`


### GlobalExceptionHandler 자동 예외 처리
- Controller에서 별도 catch/rethrow 불필요 — 도메인 예외 → HTTP 코드 매핑은 `GlobalExceptionHandler`가 SSOT (예외별 코드는 코드가 SSOT)
- async/SSE lifecycle 예외(`AsyncRequestTimeoutException` / `AsyncRequestNotUsableException`)는 이미 종료된 스트림에 응답 본문을 쓰지 않고 `handleAsyncLifecycle()`에서 debug 로그만 남긴다

### Account ↔ Strategy 분리 / 잔고검증 토글 / 스케쥴러 주문 예산 배정
Account ↔ Strategy 분리·잔고검증 토글(UserSettings.balanceCheckEnabled)·스케쥴러 주문 예산 배정 규칙은 `docs/agents/modules/trading.md`(account.md·user.md에서 cross-link)로 이동했다.

### MetaController (enum SSOT)
- `GET /api/meta` — enum 메타(label/description 포함) 단일 번들 제공 — UI에서 enum 리터럴 하드코딩 금지

### 수량 변수명 규칙
- **보유 잔고 수량**: `holdings` (avgPrice와 짝), **주문/체결 수량**: `quantity` (단건 거래)
- `privacy_trade_base_orders.quantity` — nullable (FIDA 수신 시 수량 미확정 허용)
- 복합 수량 필드: `orderedQuantity`/`filledQuantity` (`orderedQty`/`filledQty` 금지)

### AES-256 암호화 컬럼 크기
- AES-256 CBC 암호화 + Base64 인코딩 시 입력 ~180자 → 출력 ~260자 — VARCHAR(255) 초과로 `DataIntegrityViolationException` 발생
- 암호화 저장 컬럼은 반드시 VARCHAR(512) 이상 — `AccountEntity`: account_no/app_key/secret_key 512, `UserEntity`: telegram_bot_token 512
- 새 암호화 컬럼 추가 시 length=512로 선언, Flyway도 동일하게

### User/Account/Strategy 공유 enum — sharedkernel 이관 완료
`User.UserRole`/`UserStatus`/`NotificationType`, `Strategy.Type`/`Status`/`Ticker`/`CycleSeedType`, `Account.Broker`는 여러 모듈이 공유하는 값이라 `com.kista.sharedkernel` 독립 타입이다 — 이 모듈들에 nested enum으로 재도입 금지. `NotificationChannel`은 user 단독 소비라 `com.kista.user.domain.model` 독립 파일로 잔류. DB `@Enumerated(STRING)` 컬럼 상수명은 모두 byte-identical 유지 — 이동해도 상수명은 절대 바꾸지 않는다.
- 신규 유저 기본 알림 채널: `User.DEFAULT_CHANNEL = NotificationChannel.NONE`(domain 상수, `User`에 유지) — 서비스/컨트롤러에서 직접 하드코딩 금지

### 도메인 Command 명명 규칙
- `application.usecase` 인바운드 포트 파라미터/입력 모델: `*Command` suffix, `*Request` suffix 금지(외부 HTTP DTO 성격 오인), `domain/model/<도메인>/` 하위 위치

### Ticker enum (sharedkernel.StrategyTicker)
- KIS 거래소 코드는 `com.kista.broker.adapter.out.kis.KisExchangeRegistry` 전용 — 새 종목 추가 시 양쪽 갱신 필수
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

### 매매 공식 / VR 공식 (변경 금지 — 단위 테스트로 검증)
매매 공식·VR 공식은 `docs/agents/modules/trading-formulas.md`로 이동했다 — `matching/`·`trading/` 작업 시 자동 로드, 그 외에는 직접 Read.

### 계좌번호 마스킹 (AccountNumberMasker)
- `com.kista.sharedkernel.AccountNumberMasker.mask(accountNo)` — 계좌번호 마스킹 단일 알고리즘(SSOT). 숫자 이외 문자 전부 제거 후 마지막 4자리만 노출(`"****1234"`)
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
- 운영 DB에 **이미 적용된 마이그레이션 파일은 절대 수정 금지** (V1 포함 전체 버전) — Flyway 체크섬 불일치로 앱 기동 즉시 크래시. 새 마이그레이션은 소유 서비스 디렉토리의 기존 최신 버전 다음 번호로 (root `ls src/main/resources/db/migration`, trading-core `ls trading-core/src/main/resources/db/migration-trading`로 확인) — 과거 적용된 버전 수정으로 실제 운영 크래시가 난 사례 있음
- 마이그레이션 이력은 과거 스쿼시된 적이 있다(현재 `V1__init.sql`이 예전 여러 버전을 흡수) — `constraints.md`·`architecture.md` 등 문서에 특정 버전 번호(`V24`, `V28` 등)를 근거로 서술하지 말 것. 버전 번호는 `git log`로만 추적하고, 문서에는 "어떤 컬럼/제약이 어느 파일에 있는지"만 현재 파일 기준으로 기록
- **서비스별 Flyway**: root는 `src/main/resources/db/migration`(이력 `flyway_schema_history_api`, 기본 스키마 `public`), trading-core는 `trading-core/src/main/resources/db/migration-trading`(이력 `flyway_schema_history_trading`, 기본 스키마 `trading`) — 신규 테이블은 소유 서비스(스키마 소유 표 → architecture.md "DB 스키마 5분리")의 디렉토리에만 추가한다. **다른 서비스 소유 스키마 테이블은 참조 금지(FK 포함)** — trading baseline이 root 테이블에 의존하면 서비스별 소유·물리 분리가 깨지고 fresh DB 기동이 실패한다. 두 서비스 모두 `V1__init.sql` baseline 하나로 스쿼시돼 있다(옛 공용 `public.flyway_schema_history`는 롤백 증거로 운영 DB에 보존, 수정·삭제 금지). 스키마 재편 이행 릴리스는 자동 롤백이 불가능하다 → `deploy/server/schema-reorg/RUNBOOK.md`
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화
- **2-role 배포 backward-compat (expand/contract)** — root(`db/migration`) 마이그레이션에만 해당하며 trading-core(`db/migration-trading`)는 자기 스키마·이력을 독립으로 가져 이 제약 밖이다: `kista-api`·`kista-scheduler`가 독립 배포되므로, root 새 마이그레이션은 직전 배포 이미지와 호환돼야 한다 — 컬럼 추가는 nullable 또는 DEFAULT, 컬럼/테이블 드롭·리네임은 두 배포에 분리(먼저 코드 참조 제거 → 다음 배포에서 스키마 변경). 이를 못 지키는 마이그레이션을 실은 커밋은 두 role을 함께 배포한다. `ddl-auto: validate`는 기동 시에만 검사하므로, 스큐 상태의 스케쥴러는 드롭된 컬럼을 매매 도중 런타임에 만날 때까지 계속 돈다
  - **스키마뿐 아니라 "이벤트로 채워지는 복제 테이블"도 순서 제약을 만든다**: 복제본을 채우는 발행이 한쪽 role에만 있으면, 그 role이 구버전인 채로 반대쪽만 신버전이 되면 복제본이 비어 신버전이 깨진다. `user_notify_profile`이 그 사례 — 발행은 `kista-api` 경로(가입·승인·거절·재신청·설정변경)에만 있으므로 **`kista-api`를 `kista-scheduler`보다 먼저(또는 동시에) 배포해야 한다**. 반대 순서는 무해하다(구 스케쥴러는 `users`/`user_settings`를 직접 읽음) — 단방향 제약이다. 새 복제 테이블을 추가할 때 "발행 주체가 어느 role인가"를 먼저 확인하고 마이그레이션 헤더에 순서를 명시할 것
  - **이벤트 클래스 패키지 이동은 `event_publication`에 `ClassNotFoundException`을 남긴다**(`event_publication`은 서비스별 2개 — root `public`·trading `trading`이라 이벤트를 발행·구독하는 서비스 쪽 테이블에서 확인·정리한다. 아래 SQL은 테이블 접두사 없이 쓰였으므로 trading 이벤트는 `trading.event_publication`에 대해 실행): Modulith EPR은 이벤트를 FQCN으로 저장하고 재기동 republish 시 그 이름으로 클래스를 resolve한다 — Task17에서 트레이딩·privacy 이벤트 11+1개가 `com.kista.trading.application.event.*`/`com.kista.privacy.application.event.*`에서 `com.kista.sharedkernel.*`로 옮겨졌으므로, 배포 직전 옛 패키지로 남아있던 미완료 row는 필드 shape 불일치(Jackson 역직렬화 실패)가 아니라 **옛 FQCN을 더 이상 classpath에서 찾을 수 없는 `ClassNotFoundException`**으로 매 재기동마다 반복 실패한다 — 자연 치유되지 않는다. 승격된 11개(trading, 구 `com.kista.trading.application.event`): `CycleEndedEvent`/`CycleCompletedEvent`/`NewCycleStartedEvent`/`InsufficientBalanceEvent`/`TradingReportReadyEvent`/`OrderCancelFailedEvent`/`TradingErrorEvent`/`MarketClosedEvent`/`MarketOpenEvent`/`MarketCloseEvent`/`BatchInterruptedEvent` + privacy 1개(구 `com.kista.privacy.application.event`): `PrivacyAlertRaisedEvent` — 신규 FQCN은 전부 `com.kista.sharedkernel.<EventName>`. 배포 직전 `SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND (event_type LIKE 'com.kista.trading.application.event.%' OR event_type = 'com.kista.privacy.application.event.PrivacyAlertRaisedEvent')`로 잔여를 확인하고, 0건이 아니면 배포 전에 `DELETE FROM event_publication WHERE completion_date IS NULL AND (event_type LIKE 'com.kista.trading.application.event.%' OR event_type = 'com.kista.privacy.application.event.PrivacyAlertRaisedEvent')`로 정리한다(해당 미완료 알림은 유실 — 배포 공지에 "이 시점 진행 중이던 사이클/오류 알림 일부가 유실될 수 있음" 명시). `completion_date`를 임의로 채워 "완료"로 위장하는 방식은 실제 리스너 실행 없이 완료 처리되므로 피하고, 삭제(재시도 포기)로 처리할 것
- **Entity ↔ Flyway 크로스체크 필수**: Entity의 `nullable`, `length`, `precision`, `scale` 변경 시 Flyway SQL과 반드시 대조. `ddl-auto: validate`는 타입 불일치를 부팅 시 즉시 `SchemaManagementException`으로 잡음. `NOT NULL` 불일치만 런타임 무증상 → 실제 null 삽입 시 `DataIntegrityViolationException`
- **`@Column(scale)` 주의**: DDL 힌트일 뿐, JPA 1차 캐시에는 원본 BigDecimal 유지 — `@Transactional` 내 저장 직후 읽으면 DB 반올림 전 값 반환
- PostgreSQL `ADD COLUMN`은 항상 맨 뒤 — 특정 위치 강제는 테이블 재생성 패턴 사용 (`CREATE TABLE _new + INSERT SELECT + DROP + RENAME`)
- 재생성 패턴에서 named UNIQUE 제약 주의: `ALTER TABLE xxx_old DROP CONSTRAINT foo;`를 RENAME 직후·CREATE 전에 추가 필수 (unnamed UNIQUE는 Postgres가 자동으로 새 이름 생성)
- **컬럼 순서 규칙**: `pk, fk, 비즈니스 컬럼…, created_at, updated_at, deleted_at` 순서 고정. Entity 필드 선언 순서와 반드시 일치
- Java 코드만 삭제해도 DB 테이블은 자동 제거 안 됨 — 미사용 테이블은 신규 마이그레이션으로 `DROP TABLE IF EXISTS`
- **FK 추가 시 `ON DELETE CASCADE` 여부 반드시 명시** — 기본값 `ON DELETE RESTRICT`
- Flyway checksum mismatch (로컬 파일 수정 시): 서비스별 이력 테이블(`flyway_schema_history_api`/`flyway_schema_history_trading`)에서 `DELETE ... WHERE version = 'N'` + 해당 테이블 DROP → 앱 재시작 (로컬 전용)

### application-local.yml Docker 호환성
- datasource url/username/password는 반드시 `${DB_URL:...}` 형식 유지 — 하드코딩 시 Docker에서 주입한 `DB_URL=postgres:5432`가 무시되고 `localhost:5432`로 접속 시도


### Adapter 내부 중첩 타입 접근 제어자
- 같은 패키지 테스트에서 참조하려면 `private record` 금지 — `record`(package-private)으로 선언해야 `Outer.Inner.class` 매처 사용 가능
- 예: `com.kista.broker.adapter.out.kis.KisAuthApi.TokenCheckResponse`, `KisOrderApi.OrderResponse` 패턴
- `private record`를 유지하면서 테스트에서 response 타입을 `any(Class.class)` 매처로 우회하면 타입 안전성 저하 → package-private 선언 권장

### Lombok 패턴
- `RestTemplate` 빈이 여러 개(`kisRestTemplate`, `telegramRestTemplate`)이므로 필드명을 빈 이름과 반드시 일치 — 불일치 시 `NoUniqueBeanDefinitionException`

### AES-256 암호화 위치
- KIS 자격증명·계좌번호·텔레그램 봇 토큰은 **persistence adapter 경계에서만** 암호화/복호화 (ArchUnit: application → adapter 의존 금지)

### TelegramApiClient package-private 제약
- `TelegramApiClient` (`com.kista.notify.adapter.in.telegram`)는 package-private → application layer나 다른 패키지에서 직접 참조 불가
- 사용자 고유 botToken으로 Telegram API 호출이 필요하면: `com.kista.notify.application.port.output` 포트 + `com.kista.notify.adapter.out.gateway` 어댑터 신규 생성 패턴 (예: `TelegramBotInfoPort` + `TelegramBotInfoAdapter`)
- 기존 `telegramRestTemplate` 빈 재사용 가능 (필드명 일치시키면 자동 주입)

### Spring Security Filter 이중 등록 방지
- `@Component` Filter + `addFilterBefore()` 조합 시 `FilterRegistrationBean.setEnabled(false)` 필수 (이중 실행 방지)
- `SecurityConfig`에 새 Filter 추가 시 `@Import(SecurityConfig.class)` 사용하는 **모든** `@WebMvcTest`에도 해당 Filter `@Import` 필수 — 누락 시 `NoSuchBeanDefinitionException` → 다른 테스트까지 `IllegalStateException` 전파

### 서버 간 내부 인증 (InternalTokenAuthFilter)
- `/api/internal/**` 경로: `X-Internal-Token` 헤더 검증 — 환경변수 `INTERNAL_API_TOKEN` 값과 일치해야 통과 (미설정 시 항상 401)
- `SecurityConfig`: `/api/internal/**` → `hasRole("INTERNAL")`, `InternalTokenAuthFilter` JWT 필터보다 먼저 실행
- `@WebMvcTest`에서 `/api/internal/**` 경로 테스트: `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})` + `@TestPropertySource(properties = "internal.api.token=test-token")` + `.header("X-Internal-Token", "test-token")` 패턴 (`FidaOrderControllerTest` 참고)

### @EnableJpaAuditing 위치
- `@SpringBootApplication` 아닌 별도 `JpaAuditingConfig.java` (`com.kista.platform.persistence`)에 선언 — 아니면 `@WebMvcTest` `BeanCreationException` 발생

### Lombok @MappedSuperclass 상속 주의
- `@MappedSuperclass` 부모 필드의 getter/setter는 서브클래스 `@Getter`/`@Setter`로 생성되지 않음 → 부모 클래스에 직접 선언 필요
- `@Setter(AccessLevel.PACKAGE)` 범위는 **선언 클래스 패키지 기준** — `BaseAuditEntity`(`com.kista.platform.persistence`)의 package-private setter는 이를 상속하는 각 모듈 persistence 패키지(`com.kista.user.adapter.out.persistence.user` 등)에서 접근 불가. `createdAt`/`updatedAt` 필드 자체는 `protected`라 상속 접근 가능

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

### @Transactional 내부 외부 시스템 호출 금지
- RestTemplate(텔레그램, KIS 등)을 `@Transactional` 내부에서 호출 금지 — 롤백 시 취소 불가
- 패턴: `eventPublisher.publishEvent(event)` + `@TransactionalEventListener(phase = AFTER_COMMIT)`
- 이벤트 위치: `application/event/`, 리스너 위치: `adapter/out/` (ArchUnit: adapter.out → application 의존 허용)

### 포트 인터페이스 위치 규칙
- 인바운드 포트(UseCase/Query 인터페이스)는 `application/usecase/`, 아웃바운드 포트(`*Port`)는 `application/port/output/`에 위치 — `domain/port/{in,out}`은 더 이상 사용하지 않는다
- 포트의 파라미터·반환 타입으로 쓰이는 record/class는 여전히 `domain/model/` 하위에 위치 — 포트 위치가 옮겨가도 타입 소유는 domain 유지. `adapter/in/web/dto/`에 두면 `domain → adapter` ArchUnit 규칙 위반
- `adapter/in`(컨트롤러 등)은 `application.usecase`/`application.port.output`(인터페이스)에는 의존 가능하지만 `application.service`(구현체)에는 의존 금지 — ArchUnit이 이 경계만 강제
- `application.service`도 마찬가지로 `adapter` 패키지 import 금지 (`application → adapter` 규칙, 변경 없음)
- 컨트롤러 DTO와 겹치는 타입이 있으면 `domain/model/<도메인>` 패키지로 이동 후 DTO에서 re-import (변경 없음)

### 공유 DTO @Valid 제약
- `AccountRequest`는 register/update 공용 — `@Valid` 추가 시 `@NotNull strategyType`이 update에도 강제됨 (Breaking Change)
- register에만 필수인 필드는 `@NotNull` + register 메서드에만 `@Valid` 적용, update는 `@Valid` 없이 유지
- `AccountService.update()`는 strategyType 변경 지원 — null 전달 시 기존값 유지, PRIVACY 선택 시 ticker는 SOXL 강제 (register와 동일 규칙)

### FCM 디바이스 토큰 저장 규칙
- 한 사용자가 같은 플랫폼의 여러 디바이스 토큰을 가질 수 있다. 신규 토큰 저장 시 같은 사용자·플랫폼 토큰을 일괄 삭제하지 않고, 동일 토큰의 기존 소유 레코드만 삭제한 뒤 현재 사용자에게 저장한다

### ADMIN 권한 관리
- ADMIN seed: `ADMIN_KAKAO_IDS` 환경변수 (쉼표 구분) — 로그인 시 idempotent promote
- `/api/admin/**` → `hasRole("ADMIN")`, `audit_logs`에 관리자 액션 영구 기록
- 로컬: `POST /api/auth/dev-admin-token` → 고정 UUID `...002` ADMIN 발급

### 런타임 설정 API 규칙
런타임 설정 API 규칙은 `docs/agents/modules/admin.md`로 이동했다 — admin 단독 소유.

### 시간 기준 정책 (KST 단일 기준)
- **거래일(tradeDate) = KST 일자** — 매매가 실행·정산되는 KST 아침이 속한 날. DB(`orders.trade_date`)·도메인·API 모두 동일 값, 변환 없음 (과거 US 거래일 기준에서 KST 기준으로 전환 완료됨)
- **`privacy_trade_bases.release_date` = FIDA 발행일 원본(KST)** — 거래일 아님. 발행일↔거래일(+1일)은 `PrivacyDates.releaseDateFor()/tradeDateOf()` 업무 규칙 헬퍼만 사용
- **외부 원본 참조 데이터는 원본 기준 유지**: `us_market_holidays`(US 달력일) — KST↔US 변환은 해당 어댑터 내부에서만 (`UsTradeDates.toUsTradeDate()/toKstTradeDate()`)
- `UsTradeDates`(`com.kista.platform.time` — 어댑터 전용이라 sharedkernel "공용 어휘"에서 분리) 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter`, `KisPriceApi`(dailyprice BYMD 파라미터), `TossPriceApi.getClosingPrice`(Toss 일봉 캔들 `date()`는 US 세션일 기준) — 도메인·서비스·orders persistence에서 사용 금지. `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`가 이 4클래스 allowlist를 실제로 강제한다(모듈 경계 재구성 #3)
- **Toss API**: 주문 접수일(KST) 기준 — 변환 없음. `TossOrderApi.fetchExecutions()`는 전날 저녁 선접수 대응으로 `queryFrom = from - 1일` 조회 후 `filledAt`(KST) 재필터. 예외: 일봉 캔들(`TossCandleApi`)의 `date()`는 US 세션일이라 `TossPriceApi.getClosingPrice`는 KST 거래일 D를 US 세션 D-1로 변환해 조회 (KIS `fetchConfirmedClose`와 동일 규칙)
- Instant ↔ KST 일자 경계는 `atStartOfDay(TimeZones.KST)` 단일 관용구 — `ZoneOffset.UTC` 자정 경계 금지
- 거래일 경계 시각: `DstInfo.SCHEDULER_RUN_TIME = 04:30 KST` (마감 배치 cron 발화와 동일) — preview·수동실행·주문취소가 `DstInfo.nextTradeDate()` SSOT 사용
- 인라인 `.minusDays(1)`/`.plusDays(1)`로 날짜 기준 변환 금지 — 반드시 `UsTradeDates`/`PrivacyDates` 경유

### 소프트 삭제(Soft Delete) 패턴
- `users`, `accounts`, `strategy`, `strategy_cycle`, `cycle_position`, `app_error_logs` — `deleted_at` 컬럼, `@SQLRestriction("deleted_at IS NULL")` 선언
- **`nativeQuery = true` 쿼리는 `@SQLRestriction` 미적용** — `AND tc.deleted_at IS NULL` 수동 명시 필수 (`findAllActiveCycles` 등)
- Cascade 순서: 서비스 레이어에서 사이클 → 계좌 → 사용자 순으로 명시 처리 (DB FK CASCADE 미작동)
