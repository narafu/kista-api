## 핵심 제약 사항

### Git 규칙
- `git push`는 사용자가 명시적으로 요청할 때만 실행 — 요청 없이 자동 푸시 금지, 요청하면 즉시 실행
- 커밋 전 author 확인 (`git config user.name`/`user.email`): `narafu <narafu@kakao.com>`
- 커밋 메시지는 한글, Conventional Commit 접두사(`feat(scope):`, `fix:`, `docs:`, `debug:` 등) + 명령형 제목
- 매매 시간대 배포 가드는 이제 `deploy-scheduler` 잡에만 적용된다 — `deploy-api`는 시간대 무관하게 배포 가능

### application/port/output/ 네이밍 규칙
- 아웃바운드 포트 인터페이스: `*Port` 접미사. `*Repository` 접미사 사용 금지 — adapter 레이어 `*JpaRepository`와 혼동 유발

### 신규 파일 배치
신규 코드는 레거시 최상위가 아닌 해당 애그리게이트 모듈(`com.kista.<module>`) 안에 추가한다 — `domain/model` + `application/{usecase,port/output,service}` + `adapter/{in,out}` 서브구조 준수. 포트는 `domain/port/{in,out}`이 아닌 `application/{usecase,port/output}`에 위치. 모듈별 정확한 NamedInterface 공개 범위·internal 패키지는 `architecture.md` 패키지 트리가 SSOT — 신규 코드 추가 전 해당 모듈 절에서 확인할 것.
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

**포트 역전(DIP)은 값 타입 복제가 아니므로 이 게이트·아래 목록에 포함하지 않는다** — "포트를 필요로 하는 쪽이 정의하고 데이터를 가진 쪽이 구현"하는 정상 설계이며 own-type 인스턴스 번호를 부여하지 않는다: `ApprovalPolicyPort`(user 정의·admin 구현)/`BrokerEnabledPort`(account 정의·admin 구현)/`StrategyCreationPolicyPort`(trading 정의·admin 구현)/`ActiveStrategyCountPort`(user 정의·trading 구현)/`MockSimulationDataPort`(broker 정의·trading 구현).

**(b) 외부 계약 분리로 의도적 허용된 복제** (원장에 남는 것):
- DTO 이중복제: `TossCandleResponse`(market/stats), `CycleHistoryPageResponse`/`CycleHistoryResponse`(stats/trading) — 각기 다른 HTTP 엔드포인트의 JSON 응답 계약. 통합 시 한 모듈의 엔드포인트 필드 추가가 다른 모듈 응답 스키마에 전이됨

**단일 소유 포트 시그니처 타입** (쌍둥이 없음 — own-type 게이트 대상 아님, 참고용 기록):
- broker `BrokerBalance`/`OrderInstruction`/`OrderResult`/`CancelInstruction`(`com.kista.broker.domain.model`): broker↔trading 간 `LiveBalancePort`/`BrokerOrderCorrectionPort.place()/cancel()` 요청·응답 shape. 복제본 없음 — trading이 직접 소비. `OrderInstruction`/`OrderResult`는 `AdminReorderService`(재정렬 시 브로커 재주문)도 직접 소비 — admin↔broker CLOSED-CLOSED 참조, 순환은 미발생. admin이 관리자 지정 수량·가격으로 `BrokerOrderCorrectionPort.place()`를 직접 호출해 `TradingOrderBudgetAllocator`/`BuyOrderPriceCapper`를 거치지 않는다(관리자 수동 정정 도구라 의도적으로 보이나 별도 확인·합의된 바는 없음) — own-type 우회는 불필요. broker↔trading 순환은 이 타입들이 아니라 `BrokerAccountRef`(아래)로 끊는다
- broker `PriceSnapshot`: `BrokerPricePort` 반환 타입, broker 단독 소유(matching 사본 없음 — 커널 코드는 `BigDecimal` 스칼라만 받아 자체 타입 불필요, trading이 broker판을 직접 소비)

**narrowing projection** (own-type이되 값 복제가 아니라 애그리게이트 축소 노출 — 별도 트랙):
- `BrokerAccountRef`(broker, `Account`의 자격증명 투영): `Account→BrokerAccountRef` 변환은 `Account.toBrokerRef()` 1곳이 전담. `SellableQuantity`/`BrokerCredentialException`/`BrokerRateLimitException`은 broker 단독 소유 — account 측엔 대응 타입 없음. "복제"가 아니라 broker-native
- `StrategyRefLite`(broker, `MockSimulationDataPort` 확장용 초경량 뷰)
- `StrategyCreationRequest`(trading.domain.strategy, 원시값 5개): 리졸버 4종이 19필드 `RegisterStrategyCommand` 전체 대신 실제로 쓰는 필드만 받는 ISP 좁히기 — 모듈 경계용이 아니라 순수 인터페이스 설계
- `FidaPlannedOrder`(privacy.domain.model, 원시값 4필드): `trading.domain.model.Order`(15필드) 대신 FIDA가 실제로 보내고 privacy가 실제로 읽는 필드만 받는 ISP 좁히기 — 필드 타입은 `sharedkernel.OrderDirection`/`OrderType`

신규 broker/notify/privacy 포트 추가 시 이 게이트를 먼저 통과할 것 — (a)(b) 어느 쪽도 아니면 복제하지 말고 sharedkernel 승격 또는 소유권 이동을 먼저 검토한다.

### adapter/out 간 JpaRepository 접근 제한
- `*JpaRepository`는 package-private — 선언 패키지 외부에서 직접 import 시 컴파일 오류
- 다른 패키지 adapter에서 DB 조작이 필요하면 아웃바운드 포트(`application/port/output/`) 경유 필수
- 패턴: `com.kista.market.adapter.out.alpaca.AlpacaCalendarAdapter` → `com.kista.market.application.port.output.MarketHolidayStorePort` → `com.kista.market.adapter.out.persistence.calendar.MarketCalendarPersistenceAdapter`
- 참고: `com.kista.broker.adapter.out.kis.KisAuthApi` → `com.kista.broker.application.port.output.BrokerTokenCachePort` → `com.kista.broker.adapter.out.persistence.KisTokenPersistenceAdapter`


### GlobalExceptionHandler 자동 예외 처리
- Controller에서 별도 catch/rethrow 불필요 — 도메인 예외 → HTTP 코드 매핑은 `GlobalExceptionHandler`가 SSOT (예외별 코드는 코드가 SSOT)
- async/SSE lifecycle 예외(`AsyncRequestTimeoutException` / `AsyncRequestNotUsableException`)는 이미 종료된 스트림에 응답 본문을 쓰지 않고 `handleAsyncLifecycle()`에서 debug 로그만 남긴다

### Account ↔ Strategy 분리
계좌·전략은 별도 aggregate — 필드 구성은 코드가 SSOT, 아래는 코드로 자명하지 않은 제약·역할만 기록.
- `Account`: type/status/ticker/multiple/updatedAt **없음** (전략 속성은 Strategy로 분리). `updatedAt`은 persistence `BaseAuditEntity`가 관리, `createdAt`은 신규 등록 시 null → persistence 저장 후 채워짐
- `Strategy`: `StrategyType`/`StrategyStatus`/`StrategyTicker`/`StrategyCycleSeedType`는 `com.kista.sharedkernel`의 전역 공용 어휘 — nested 재도입 금지, DB `@Enumerated(STRING)` 컬럼 상수명 byte-identical 유지. `Strategy` record 자체는 `com.kista.trading.domain.model`에 위치
- 설정 이력 계층: `StrategyVersion`(버전 부모) → `StrategyInfiniteDetail`(divisionCount) / `StrategyVrDetail`(intervalWeeks·bandWidth·recurringAmount + 램프 8필드; `gradientAt(weeks)`/`poolLimitRateAt(weeks)`는 VR 공식 메서드) — `com.kista.trading.domain.model`(trading "domain" NamedInterface 공개)
- 실행 이력 계층: `StrategyCycle`(실행된 사이클 + 적용 버전 고정값; `startAmount` 계약 → 아래 "VR 공식"의 "개장 금액 계약")은 비-VR 최신 포지션 `holdings=0`일 때만 `StrategyCyclePort.updateStartAmount()`로 in-place 갱신. VR 일반 시드 수정은 저장 전에 거부하고 VR 재설정을 사용한다. VR의 개장 USD pool은 개장 `CyclePosition.usdDeposit`(`initialUsdDeposit`)으로 별도 보존 → `CyclePosition`(체결마다 append되는 포지션 스냅샷, dedup/UNIQUE 없음) + 타입별 detail `CyclePositionInfiniteDetail`(isReverseMode) / `StrategyCycleVrDetail`(사이클 시작 VR 파라미터 스냅샷 value·gradient·poolLimitRate)
- `StrategyDetail`: 최신 사이클·활성 버전·최신 포지션을 합쳐 만드는 응답 조립 DTO(`StrategyService.toDetail()`), `vr` 필드는 top-level `com.kista.trading.domain.model.VrSummary`(VR 외 null)
- 계좌당 종목(ticker) 중복 등록 불가 — `StrategyPort.existsByAccountIdAndTicker` (계좌당 여러 전략, 종목별 1개)
- `cycleSeedType`: 사이클 종료 후 자동 재등록 정책 (기본 `NONE`); **VR은 NONE 강제** — 롤오버가 사이클 교체 담당

### 잔고검증 토글 (UserSettings.balanceCheckEnabled)
- `UserSettings` aggregate(`com.kista.user.domain.model`) — `User` record 아님
- ON이면 `StrategyService` 시드 등록/수정 시 "KIS 가용금액 − 기존 활성 전략 점유 시드" 한도 초과를 `IllegalArgumentException`으로 차단
- 설정 미존재 시 `UserSettings.defaultFor(userId)` — `balanceCheckEnabled=true`, 빈 notificationPrefs

### 스케쥴러 주문 예산 배정 (상세 규칙 SSOT → workflow.md)
- 예산 배정 우선순위·compute skip·실패 격리·수동 SELL 검증 등 실행 규칙 전체는 `docs/agents/workflow.md`가 SSOT — 매매·스케쥴러·주문 로직 작업 시 필수 Read
- `orders.order_leg`는 스케쥴러 내부 leg 식별자 — 신규 전략 주문은 non-blank concrete leg 필수(`UNKNOWN` 잔존 시 `TradingService`가 PLANNED 저장 전 `IllegalStateException`으로 거절), legacy 행은 `UNKNOWN` 유지, 브로커 API payload에 미포함
- 슬롯 점유 판단: concrete leg는 `timing + direction + orderLeg`, `UNKNOWN` legacy 행은 `timing + direction` coarse
- `orders.order_leg`와 scheduler/reservation 조회용 인덱스(`idx_orders_cycle_date_timing_status` 등)는 현재 `V1__init.sql`에 포함되어 있다(과거 별도 마이그레이션이었으나 스쿼시됨) — 후속 orders 쿼리 변경 시 인덱스 prefix와 조회 조건 함께 확인

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
- **gradient(G)·poolLimitRate 램프**: 둘 다 고정값이 아닌 "전략 최초 사이클 startDate부터 경과한 주수(weeks)"에 따라 점진 변화하는 값. 초기값·램프 파라미터(유예·단계주기·상하한) 8개는 전략 등록 시 사용자 입력(`StrategyVrDetail`: `initialGradient/gGraceWeeks/gStepWeeks/gMax/initialPoolLimitRate/pGraceWeeks/pStepWeeks/poolLimitFloor`), 생략 시 recurringMode(적립/거치/인출) 고정값 표(kista-ui `RAMP_DEFAULTS_BY_MODE`와 동기화) + 유예 52주·단계 26주로 채운다 — gGraceWeeks/gStepWeeks/pGraceWeeks/pStepWeeks 4필드만 생략 시 관례값, 나머지 4필드(initialGradient/gMax/initialPoolLimitRate/poolLimitFloor)는 아래 표 그대로
    | recurringMode | initialGradient | gMax | initialPoolLimitRate | poolLimitFloor |
    |---|---|---|---|---|
    | 적립(`recurringAmount>0`) | 10 | 20 | 1.0 | 0.5 |
    | 거치(`recurringAmount==0`) | 10 | 20 | 0.75 | 0.5 |
    | 인출(`recurringAmount<0`) | 40 | 50 | 0.1 | 0.1 |
  - `gradientAt(weeks)`: `weeks < gGraceWeeks` → `initialGradient`; 이후 `gStepWeeks`마다 `+1`(고정, `StrategyVrDetail.G_STEP`), `gMax` 상한
    - `gStepWeeks=0`은 gradient 램프 자체를 비활성화(항상 `initialGradient` 유지) — 이때 `gMax`·`gGraceWeeks`는 무관해지므로 0 허용(등록·재설정 양쪽 `gStepWeeks > 0`일 때만 `gMax >= initialGradient` 강제)
  - `poolLimitRateAt(weeks)`: `weeks < pGraceWeeks` → `initialPoolLimitRate`; 이후 `pStepWeeks`마다 `-5%p`(고정, `StrategyVrDetail.POOL_LIMIT_STEP`), `poolLimitFloor` 하한(scale=2 HALF_UP)
    - `pStepWeeks=0`은 poolLimitRate 램프 자체를 비활성화(항상 `initialPoolLimitRate` 유지) — 이때 `poolLimitFloor`·`pGraceWeeks`는 무관해지므로 검증 없이 0 허용(등록·재설정 양쪽 `pStepWeeks > 0`일 때만 `0 < poolLimitFloor <= initialPoolLimitRate` 강제)
  - G·poolLimitRate 두 램프의 유예·단계주기는 서로 독립
  - weeks 재계산 시점: 사이클 롤오버(`VrCycleRolloverService`) 및 운영 중 재설정(`VrReconfigureService`) — 둘 다 `ChronoUnit.WEEKS.between(전략 최초 사이클.startDate, today)`. 사이클 진행 중엔 `strategy_cycle_vr` 스냅샷(gradient·poolLimitRate) 고정
- 등록 검증: `initialValue`, `initialUsdDeposit`, `recurringAmount` null은 0으로 취급
- 적립식(`recurringAmount > 0`): 초기 V와 초기 시드가 모두 0이어도 등록 가능
- 거치식/인출식(`recurringAmount <= 0`): `initialValue + initialUsdDeposit > 0` 필수
- 인출식(`recurringAmount < 0`): `initialValue + initialUsdDeposit >= abs(recurringAmount) × 100 × (4 / intervalWeeks)` 필수 — 운영 중 재설정으로 `recurringAmount`를 인출식으로 바꾸는 경우도 `VrReconfigureService`가 동일 규칙 재검증
- **개장 금액 계약**: `initialUsdDeposit` = 사이클 개장 USD pool(개장 `CyclePosition.usdDeposit`), `startAmount` = 개장 예수금 + 개장 보유분 시장가(모든 전략), `poolLimit` = 개장 pool × `poolLimitRate` (scale=2 HALF_UP). 보유분 시장가를 pool에 포함하지 않는다.
- **종료 금액 계약**: VR 롤오버 `endAmount` = 마감 예수금 + 보유분 종가 평가액(scale=2 HALF_UP). 재설정은 이전 사이클을 자본 조정 전 포지션의 총자산으로 종료하고 새 사이클을 자본 조정 후 총자산으로 시작해 주입/인출을 이전 사이클 손익에 포함하지 않는다.
- **레거시 통계 호환**: Stats는 VR 개장 포지션의 `usdDeposit + holdings × closingPrice`를 개장 원금으로 사용한다. 개장 holdings가 양수인데 `closingPrice`가 null이면 시장가 복원이 불가능하므로 저장된 `startAmount`를 유지한다. 비-VR 계산은 저장된 `startAmount`를 그대로 사용한다.
- **`strategy_cycle_vr.pool_limit_rate`**(비율, 달러 아님)를 스냅샷 저장. poolLimit(달러)은 저장하지 않고 조회 시점에 개장 `CyclePosition.usdDeposit × poolLimitRate`로 파생 — 첫 사이클은 `poolLimitRateAt(0)`, 롤오버·재설정 사이클은 `poolLimitRateAt(weeks)`를 저장
- **bootstrap 진입 판정(`VrStrategy.buildOrders`/`needsBootstrap`)**: `firstCycle` 개념 없이 순수 상태 기반으로 게이팅. holdings=0인데 V=0이면 사다리 공식 자체가 무의미(lowerBand=0)해 bootstrap 대상. holdings=0이고 V>0이어도 사다리 첫 유효 단(m=2, 가격=lowerBand 그대로)이 잔여예산을 초과하면 마찬가지로 bootstrap 대상 — `nextValue()` 공식이 holdings와 무관하게 매 롤오버 V를 키우므로(`pool/G+recurringAmount` 항), holdings=0이 지속되면 V가 예산 대비 과도하게 커져 사다리로는 영원히 매수가 불가능해질 수 있기 때문
  - **holdings>0 드리프트 케이스**: 등록 시 V=시장가×수량으로 확립되지만, 이후 holdings가 늘지 않는 채로 롤오버가 반복되면(`nextValue()`가 holdings 무관하게 V를 계속 키움) 사다리 첫 유효 단(m=1, divisor=holdings)조차 잔여예산을 초과할 수 있다. 이 경우도 매수만 bootstrap(예산 내 캡 가격 LOC)으로 대체하고, 매도 사다리는 이 드리프트와 무관하게 정상 생성한다(전량 bootstrap 전환과 달리 매도까지 사라지지 않음)
- **bootstrap 잔여예산(`remainingBudget`)**: 원칙은 `poolLimit − poolUsed`(이번 사이클에 이미 매수 체결된 금액 차감). 단 `poolLimit`이 0(사이클 개장 시점 예수금 자체가 0이었던 완전 무일푼 시작이라 poolLimit이 그 사이클 내내 영구 고정)이면 DB상 예수금(`pool`, `cycle_position` 최신 스냅샷)을 그대로 상한으로 대신 쓴다. 어느 쪽이든 DB상 예수금은 넘지 않는다(`governanceLimit.min(pool)`). `poolUsed`가 실제 체결 기준이라 부분/미체결 여부와 무관하게 다음날 정확한 잔여예산이 재계산된다. 예산<=0이면 빈 주문(다음 롤오버에서 `nextValue()` 공식이 V를 자연 성장시킴 — 실제로 holdings가 생기면 이 판정 자체가 꺼지므로 별도 처리 불필요)
- bootstrap LOC 가격: `PriceCapPolicy.capFor(referencePrice)`(= referencePrice × 1.05, currentPrice 없으면 전일종가로 대체 가능) — 일반 매수 캡과 동일 기준 사용. 주문 수량은 잔여예산/가격 내림 정수
- 사다리 병합: 동일 가격 연속 rung은 수량 병합(매수), 매도는 holdings>20이면 마지막 단(s=20)에 잔여 전량
- 가격 캡: `buyPrice > currentPrice × 1.05`(`PriceCapPolicy`, INFINITE/PRIVACY의 `BuyOrderPriceCapper`와 공용) 이면 cap 가격으로 교체 — scale=2 HALF_UP (currentPrice=null이면 미적용). VR은 매수 사다리 생성 시점(`VrStrategy.buildBuyOrders`)에는 캡을 적용하지 않고, 접수 직전 `BuyOrderPriceCapper`(`PriceCapMode.VR_POSITION`)가 `VrStrategy.buildCappedBuyOrders()`로 재산정한다 — INFINITE/PRIVACY와 동일한 공통 보정 경로
- rollover due 조건: `cycle.startDate() + intervalWeeks ≤ today` (당일 포함)
- V′ ≤ 0이면 롤오버 보류 — 사이클 유지, 관리자·사용자 알림. V=0·holdings=0인 채로 롤오버가 진행되는 경우도 `nextValue()` 결과를 그대로 쓴다(예전 존재했던 "V 강제 0 유지" 가드는 폐기됨) — `pool/G+recurringAmount` 항으로 다음 사이클 V가 자연 성장하고, 실제 매수는 항상 pool/poolLimit 실측 잔고 한도 내에서만 이뤄지므로 과다지출 위험이 없다
- **운영 중 재설정** (`PUT /api/trading-cycles/{id}/vr-config`, `VrReconfigureUseCase`/`VrReconfigureService`): 밴드폭·주기·적립금·램프 파라미터 수정 + 선택적 자본 주입/인출(수량/예수금)을 "새 `strategy_vr_version` 발급 + 강제 롤오버(현재 사이클 종료→새 사이클 즉시 생성)" 단일 메커니즘으로 처리. VR 전용, 소유권 검증 필수
  - 램프 시계(경과주수)는 재설정해도 리셋하지 않음 — 항상 전략 최초 사이클 startDate 기준
  - 순수 파라미터 수정: V·holdings·usdDeposit 이월. 수량 주입 +N주(단가 Pc): `holdings+=N`, `avgPrice` 가중평균, `V+=N×현재가`. 수량 인출 -N주: holdings·V 감소, 잔여 평단가 유지. 예수금 주입/인출은 usdDeposit만 증감하고 V는 불변
  - 검증 순서: 램프 파라미터·자본 주입 형태(수량 음수 금지 등)·인출식 최소자산 재검증까지 모두 통과한 뒤에만 브로커 미체결 주문 취소(`OrderCancelService`, 별도 트랜잭션이라 이후 실패해도 롤백 불가)를 호출 — 검증 실패 시 브로커에 실주문 취소가 나가지 않도록 순서 고정

### 계좌번호 마스킹 (AccountNumberMasker)
- `com.kista.account.domain.model.AccountNumberMasker.mask(accountNo)` — 계좌번호 마스킹 단일 알고리즘(SSOT). 숫자 이외 문자 전부 제거 후 마지막 4자리만 노출(`"****1234"`)
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
- 운영 DB에 **이미 적용된 마이그레이션 파일은 절대 수정 금지** (V1 포함 전체 버전) — Flyway 체크섬 불일치로 앱 기동 즉시 크래시. 새 마이그레이션은 기존 최신 버전 다음 번호로 (`ls src/main/resources/db/migration`로 확인) — 과거 적용된 버전 수정으로 실제 운영 크래시가 난 사례 있음
- 마이그레이션 이력은 과거 스쿼시된 적이 있다(현재 `V1__init.sql`이 예전 여러 버전을 흡수) — `constraints.md`·`architecture.md` 등 문서에 특정 버전 번호(`V24`, `V28` 등)를 근거로 서술하지 말 것. 버전 번호는 `git log`로만 추적하고, 문서에는 "어떤 컬럼/제약이 어느 파일에 있는지"만 현재 파일 기준으로 기록
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화
- **2-role 배포 backward-compat (expand/contract)**: `kista-api`·`kista-scheduler`가 독립 배포되므로, 새 마이그레이션은 직전 배포 이미지와 호환돼야 한다 — 컬럼 추가는 nullable 또는 DEFAULT, 컬럼/테이블 드롭·리네임은 두 배포에 분리(먼저 코드 참조 제거 → 다음 배포에서 스키마 변경). 이를 못 지키는 마이그레이션을 실은 커밋은 두 role을 함께 배포한다. `ddl-auto: validate`는 기동 시에만 검사하므로, 스큐 상태의 스케쥴러는 드롭된 컬럼을 매매 도중 런타임에 만날 때까지 계속 돈다
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
- `GET /api/runtime-config` → 로그인 전 UI가 가입·계좌·전략 생성 정책을 조회하는 공개 엔드포인트. 동적 설정이므로 `Cache-Control: no-store` 유지
- `GET|PUT /api/admin/settings` → ADMIN 전용. PUT은 auth/brokers/strategies 전체 설정을 검증한 뒤 한 번에 교체하며 부분 갱신 API로 취급하지 않음. 조회·갱신 응답 모두 `Cache-Control: no-store` 유지
- `brokers.<broker>.enabled=false`이면 해당 증권사의 신규 계좌 등록과 연결 테스트를 외부 API 호출 전에 400으로 차단. 기존 계좌의 조회·수정·매매는 영향받지 않음
- `StrategyService.register()`는 신규 전략에만 `strategies.<type>` 생성 정책을 적용: `enabled=false`면 400으로 차단하고, ticker·INFINITE divisionCount·VR recurringMode/bandWidth/intervalWeeks의 생략 기본값과 허용/고정값을 검증. 기존 전략 수정·실행에는 소급 적용하지 않음
- `RegisterStrategyCommand.divisionCount=0`은 INFINITE 신규 등록의 미입력 sentinel이며 런타임 기본값으로 치환. VR `recurringMode`는 `recurringAmount` 부호(DEPOSIT/HOLD/WITHDRAW)로만 검증하고 금액 크기는 기존 VR 자산 규칙에 맡김. `recurringMode.customizable=false` 설정은 기본값과 유일한 허용값이 모두 `HOLD`여야 함
- 런타임 설정 응답은 `NON_NULL` 직렬화 사용. 전략 유형에 적용되지 않는 field(예: PRIVACY의 `divisionCount`)는 `null`로 내리지 않고 JSON에서 생략
- `approvalRequired` 값이 `true → false`로 바뀌면 그 시점의 모든 PENDING 사용자를 기존 `UserUseCase.approve()` 흐름으로 활성화. 설정 갱신은 `RUNTIME_SETTINGS_UPDATE` 감사 로그 기록

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
