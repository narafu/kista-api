# 코인 DCA 실행 (묶음 C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업비트 계좌의 ACTIVE 코인 DCA 전략을 10분 주기 스케줄러로 실행(조건 평가·금액 산정·시장가 주문·체결 동기화·알림)하고, 키 만료 알림과 A·B 이월 Minor 4건을 처리한다.

**Architecture:** trading-core `trading` 모듈에 코인 전용 경로를 둔다 — `CryptoDcaScheduler`(adapter.in.schedule) → `CryptoDcaRunner`(application.service, 계좌별 순차) → `CryptoOrderSynchronizer`(PLANNED 복구·PLACED 체결 확인) + `CryptoDcaRoundExecutor`(판정·금액·주문). 조건 판정은 matching 커널에서 `DcaConditions`로 추출해 주식 `DcaCycleOrderStrategy.decide`와 공유한다. 주식 정수 경로(`OrderSubmitter`·`TradingReporter`·`Order.toPlanned()`)는 타지 않는다.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JPA/Flyway(PostgreSQL), JUnit5·Mockito·AssertJ, RestClient(`MockRestServiceServer`).

**Spec:** `docs/superpowers/specs/2026-10-17-crypto-upbit-dca-design.md` — 섹션 3(묶음 C, 2026-10-18 개정본). 섹션 1·2는 구현 완료.

**작업 위치:** worktree `/Users/phs/workspace/kista/kista-api-worktrees/crypto-upbit-dca` (브랜치 `feat/crypto-upbit-dca`). main 체크아웃 금지. push 금지(사용자 요청 시에만). 커밋 author `narafu <narafu@kakao.com>`, 메시지 한글 + Conventional Commit, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**진행 원장:** `.superpowers/sdd/2026-10-18-crypto-upbit-dca-c/progress.md`(C 소유, 새로 만든다). A·B 원장 `.superpowers/sdd/2026-10-17-crypto-upbit-dca-a-b/progress.md`는 읽기 전용.

## Global Constraints

- 경로 접두사: `TC` = `trading-core/src/main/java/com/kista/tradingcore`, `TCT` = `trading-core/src/test/java/com/kista/tradingcore`.
- 테스트 명령은 서브프로젝트 지정 필수: `./gradlew :trading-core:test --tests '...'` / `./gradlew :test --tests '...'`. 출력은 `2>&1 | grep -E "FAILED|BUILD|tests completed|Test.*>"`로 필터. 전체 스위트는 Task 12에서 1회만.
- 테스트 DB: `docker compose up -d postgres` 상태의 `localhost:5432/kistadb_test`. 로컬 서버(8080·8081)가 떠 있으니 **서버 재기동·`bootRun` 금지**(필요하면 컨트롤러에게 보고).
- 거래일 = 실행 시점 KST 날짜(`LocalDate.now(clock.withZone(TimeZones.KST))`). 휴장 보정 없음, 말일 보정만(`DcaSchedule` + `d -> true`).
- 최소 주문 금액 5,000 KRW (`DcaConfig.CRYPTO_MIN_AMOUNT` 재사용). 매수 금액은 정수 KRW. 매도 수량은 소수 8자리 FLOOR.
- 업비트 KRW 수수료율 상수 `0.0005`(0.05%) — `ponytail:` 주석으로 "거래소별 수수료 조회가 필요해지면 확장".
- 체결 판정은 `executedVolume > 0`(state `done`/`cancel` 무관).
- 일봉 250봉(주식 `DcaRoundPlanner.CANDLE_COUNT`와 동일).
- 주석 규칙: `//` 인라인만, Javadoc 금지, 필드는 역할 한 줄 주석.
- `@Async`/`CompletableFuture` 금지(Virtual Thread). `@Transactional` 안에서 업비트 HTTP 호출 금지.
- Flyway 신규 마이그레이션 불필요(스키마 변경 없음). 생기면 컨트롤러에 먼저 보고.
- 업비트 실키 검증 불가 — HTTP는 `MockRestServiceServer` stub만.

## Review Focus

1. 결과 불명(I/O·5xx) 주문 실패 뒤 다음 틱에서 **중복 매수가 나가지 않아야** 한다 — PLANNED는 `findOrderByIdentifier` 확인 전 재주문 금지. (Task 6 테스트 `plannedOrderNotFoundBeforeHourEndsIsFailedThenRetried`·`unknownPlacementLeavesPlanned`)
2. 같은 계좌 BTC·ETH 두 매수 전략의 합이 KRW 가용액을 넘으면 두 번째 전략은 **로컬 차감된 잔액**으로 축소되거나 5,000 미달 skip이어야 한다. (Task 7 `secondStrategyUsesLocallyDeductedBalance`)
3. 실행 시각이 KST 00:00~08:59이면 마지막 완성 봉은 `오늘−2일`이다 — 이걸 오늘−1일로 보면 매일 새벽 실행이 DATA_UNAVAILABLE로 죽는다. (Task 7 `freshnessBeforeNineExpectsTwoDaysAgo`)
4. 코인 주문이 `orders`에 들어간 뒤 주식 경로(관리자 주문 조회·계좌 삭제 취소)가 `intValueExact`/`BrokerRouter(UPBIT)`로 예외를 내지 않아야 한다. (Task 10)
5. 한 계좌 업비트 장애가 다른 계좌 실행을 막지 않아야 한다. (Task 8 `oneAccountFailureDoesNotStopOthers`)

---

### Task 1: 조건 판정 커널 추출 (`DcaConditions`)

**Files:**
- Create: `TC/matching/domain/strategy/DcaConditions.java`
- Modify: `TC/matching/domain/strategy/DcaCycleOrderStrategy.java` (조건 평가·`evaluate`·`evaluateAvgPrice`·`compare`·`negatedSymbol`·`dataFailure`를 `DcaConditions`로 이동, `decide`는 위임)
- Modify: `TC/trading/application/service/DcaRoundPlanner.java` (`loadHolding`의 `Holding` 생성 — quantity를 `BigDecimal.valueOf(b.holdings())`로)
- Test: `TCT/matching/domain/strategy/DcaConditionsTest.java`, 기존 `DcaCycleOrderStrategy` 테스트(수정 없이 통과해야 함)

**Interfaces:**
- Produces:
  - `DcaCycleOrderStrategy.DcaInputs.Holding(BigDecimal quantity, BigDecimal avgPrice)` — quantity를 `int` → `BigDecimal`로 변경(코인 소수 보유). `evaluateAvgPrice`의 `quantity() <= 0`은 `signum() <= 0`.
  - `public final class DcaConditions` (matching "kernel" NamedInterface가 공개하는 패키지인지 `package-info` 확인 — `DcaCycleOrderStrategy`와 같은 패키지라 공개됨):
    - `public static Optional<DcaDecision.Skipped> check(DcaConfig config, List<BigDecimal> closes, DcaCycleOrderStrategy.DcaInputs.Holding holding, BigDecimal fearGreed, BigDecimal referencePrice)` — 전부 통과면 empty. 순서·문구는 현재 `decide`와 동일: 전일 종가 없음/≤0 → `DATA_UNAVAILABLE "전일 종가 조회 실패"`(referencePrice=null), 지표 계산 불가 → `DATA_UNAVAILABLE dataFailure(cond)`, 미충족 → `CONDITION_NOT_MET detail`. 반환 Skipped의 referencePrice는 인자값(전일 종가 실패만 null).

- [ ] **Step 1: 실패 테스트 작성** — `DcaConditionsTest`
  - `noConditionsPasses`: 조건 0개, closes `[100]` → empty.
  - `missingPrevCloseIsDataUnavailableWithNullReference`: closes null → `DATA_UNAVAILABLE`, detail `"전일 종가 조회 실패"`, referencePrice null.
  - `avgPriceWithFractionalHoldingEvaluates`: AVG_PRICE `LTE -5`, holding `(new BigDecimal("0.00012345"), 1000)`, target = 1000×0.95 = 950.00 — closes `[950]` → empty, closes `[951]` → `CONDITION_NOT_MET`, detail `"전일 종가 951.00 > 평단 1000.00 × (1 − 5%) = 950.00"`.
  - `zeroHoldingPassesAvgPrice`: holding `(BigDecimal.ZERO, null)` → empty.
  - `smaShortHistoryIsDataUnavailable`: SMA(200), closes 199개 → `DATA_UNAVAILABLE "SMA(200) 계산용 일봉 부족"`.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.strategy.DcaConditionsTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** — 로직은 이동만 한다(문구·순서 변경 금지). `decide`는 `basis` 계산 → SELL `sellableQuantity == null` 검사 → `DcaConditions.check(...)` → NO_HOLDINGS → 수량 순서를 유지한다. 주의: 현재 `decide`는 전일 종가 검사를 `basis` 계산·sellable 검사보다 먼저 한다 — `check` 안에서 전일 종가를 다시 검사해도 결과가 같도록 `decide`의 선행 전일 종가 검사는 그대로 둔다.
- [ ] **Step 4: 통과 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.*' --tests 'com.kista.tradingcore.trading.application.service.DcaRoundPlanner*'` → PASS, 기존 DCA 테스트는 `Holding` 생성자 인자 타입 외 수정 없음.
- [ ] **Step 5: 커밋** — `refactor(matching): DCA 조건 판정을 DcaConditions로 추출해 코인 경로와 공유한다`

---

### Task 2: 업비트 일봉 250봉 페이징

**Files:**
- Modify: `TC/broker/adapter/out/upbit/UpbitExchangeAdapter.java:82-96`
- Modify: `TC/broker/application/port/output/CryptoExchangePort.java` (주석: `count` 1~400)
- Test: `TCT/broker/adapter/out/upbit/UpbitExchangeAdapterTest.java`

**Interfaces:**
- Produces: `List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count)` — `1 ≤ count ≤ 400`(밖이면 IAE), 오래된 순, 진행 중 봉 제외, 결과 개수 ≤ count(상장 이력 부족 시 적게).

- [ ] **Step 1: 실패 테스트** — `MockRestServiceServer`로 `/v1/candles/days` 2회 응답:
  - `pagesWithToWhenCountExceeds200`: count=250. 1차 요청 쿼리 `market=KRW-BTC&count=200`(to 없음) → 200행(최신 봉 1개는 진행 중). 2차 요청 쿼리에 `to=<1차 응답 중 가장 오래된 봉의 candle_date_time_utc>`(형식 `yyyy-MM-dd'T'HH:mm:ss`, 업비트 `to`는 exclusive) + `count=200` → 200행. 결과 size 250, 날짜 오름차순·중복 없음, 마지막 원소 = 어제 봉.
  - `stopsWhenHistoryRunsOut`: 2차 응답이 빈 배열 → 1차 완성 봉(199)만 반환, 3차 요청 없음.
  - `rejectsCountOver400`: count=401 → IAE.
  - 기존 count≤199 테스트는 그대로 통과(1회 요청).
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.upbit.UpbitExchangeAdapterTest'`
- [ ] **Step 3: 구현** — `CandleRow`에 `@JsonProperty("candle_date_time_utc") LocalDateTime startUtc` 추가. 요청당 `count=200`, 완성 봉 ≥ count가 되거나 응답이 비면 중단(최대 3회). `to`는 직전 응답의 최소 `startUtc`를 `DateTimeFormatter.ISO_LOCAL_DATE_TIME`로. 완성 판정·정렬은 기존 로직 유지, `kstDate` 기준 중복 제거.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 5: 커밋** — `feat(broker): 업비트 일봉 조회에 to 페이징을 추가해 250봉을 지원한다`

---

### Task 3: 코인 공포탐욕지수 어댑터

spec은 "`FearGreedIndexPort`에 market 인자 추가"이지만, 구현체 2개를 market으로 라우팅하는 디스패처가 필요해진다. 대신 **코인 전용 포트를 따로 둔다**(주식 경로 무변경). Task 11에서 spec 문구를 이에 맞춰 고친다.

**Files:**
- Create: `TC/trading/application/port/output/CryptoFearGreedIndexPort.java`
- Create: `TC/trading/adapter/out/feargreed/CryptoFearGreedIndexAdapter.java`
- Test: `TCT/trading/adapter/out/feargreed/CryptoFearGreedIndexAdapterTest.java`

**Interfaces:**
- Produces: `public interface CryptoFearGreedIndexPort { Optional<BigDecimal> latest(); }` — alternative.me 최신값(소수 2자리 HALF_UP), 실패·값 없음 empty.

- [ ] **Step 1: 실패 테스트** — `CnnFearGreedIndexAdapter` 테스트 패턴 복제(같은 `fearGreedRestClient` 빈 주입 방식). URL `https://api.alternative.me/fng/?limit=1`. 응답 `{"data":[{"value":"27","value_classification":"Fear","timestamp":"..."}]}` → `27.00`. `data` 빈 배열 → empty. 500 → empty.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CryptoFearGreedIndexAdapterTest'`
- [ ] **Step 3: 구현** — package-private `@Component`, `CnnFearGreedIndexAdapter`와 같은 구조(try/catch → warn 로그 → empty). 응답 record: `AltResponse(List<Item> data)`, `Item(@JsonProperty("value") String value)`. root `com.kista.api.market.adapter.out.feargreed.CryptoFearGreedAdapter`의 응답 구조를 참고하되 import는 하지 않는다(프로세스 경계).
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(trading): 코인 DCA FEAR_GREED 조건용 alternative.me 지수 어댑터를 추가한다`

---

### Task 4: 체결 알림 계약 `TradeEventMessage.quantity` → BigDecimal

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/contract/notify/TradeEventMessage.java` (필드 `BigDecimal quantity`, `buy`/`sell` 팩토리 인자 `BigDecimal quantity`)
- Modify: `TC/notify/adapter/out/gateway/TradingReportNotifier.java:43-47` (`BigDecimal.valueOf(e.quantity())`)
- Modify: root SSE 소비처 `src/main/java/com/kista/api/notify/adapter/out/sse/*` — 컴파일 오류 나는 곳만
- Test: `TradeEventMessage` 직렬화 테스트(없으면 `shared/src/test/java/com/kista/shared/contract/notify/TradeEventMessageTest.java` 생성)

**Interfaces:**
- Produces: `TradeEventMessage.buy(String ticker, BigDecimal quantity, double price, double amount, String nickname, UUID accountId, UUID strategyId)` / `sell(...)` 동일 시그니처.

- [ ] **Step 1: 실패 테스트** — `serializesFractionalQuantityAsPlainNumber`: `buy("BTC", new BigDecimal("0.00012345"), ...)`를 Jackson으로 직렬화 → JSON에 `"quantity":0.00012345`(지수 표기 아님). `deserializesIntegerQuantity`: `"quantity":3` 역직렬화 → `BigDecimal` 3(구버전 발행자 호환).
- [ ] **Step 2: 실패 확인** — `./gradlew :shared:test --tests '*TradeEventMessageTest'` (shared에 test 소스셋이 없으면 trading-core test로 옮긴다).
- [ ] **Step 3: 구현** — 필드에 `@JsonSerialize(using = PlainDecimalSerializer.class)`가 shared에서 접근 가능하면 쓰고, 아니면 `@JsonFormat(shape = JsonFormat.Shape.NUMBER)` 대신 `BigDecimal` 그대로 두고 테스트로 표기를 확인(`Quantities.normalize`를 거치지 않으면 지수 표기는 생기지 않는다). 컴파일 오류가 나는 호출처 전부 수정(`grep -rn "TradeEventMessage\.\(buy\|sell\)"`).
- [ ] **Step 4: 통과 확인** — 위 테스트 + `./gradlew :trading-core:test --tests '*TradingReportNotifier*'` + `./gradlew :test --tests '*TradeSse*' --tests '*RedisTradeEvent*'`.
- [ ] **Step 5: 커밋** — `feat(contract): 체결 알림 수량을 BigDecimal로 넓혀 코인 소수 수량을 싣는다` (본문에 "root·trading-core 같은 커밋 배포, kista-ui 타입 변경 필요" 명시)

---

### Task 5: 코인 주문 도메인·조회 포트

**Files:**
- Modify: `TC/trading/domain/model/Order.java` (팩토리 추가)
- Modify: `TC/trading/application/port/output/OrderPort.java` + persistence 어댑터/JpaRepository(`TC/trading/adapter/out/persistence/...` — `OrderPort` 구현체 위치는 `grep -rln "implements OrderPort"`)
- Test: `TCT/trading/domain/model/OrderTest.java`(있으면 추가), `OrderPersistenceAdapterDbTest`에 케이스 추가

**Interfaces:**
- Produces:
  - `public static Order cryptoMarket(UUID accountId, UUID strategyCycleId, LocalDate tradeDate, StrategyTicker ticker, OrderDirection direction, String orderLeg, OrderTiming timing, BigDecimal quantity, BigDecimal price)` — `orderType=MARKET`, `status=PLANNED`, `id=null`(저장 시 v7 생성), externalOrderId/filled* null.
  - `List<Order> findOpenByAccountId(UUID accountId)` — status ∈ {PLANNED, PLACED}, 날짜 무관(자정 넘김 대비), `trade_date, created_at` 오름차순. soft delete 제외.

- [ ] **Step 1: 실패 테스트**
  - `OrderTest.cryptoMarketKeepsFractionalQuantity`: quantity `0.00012345` → `order.quantity()` compareTo 같음, status PLANNED, orderType MARKET.
  - `OrderPersistenceAdapterDbTest.findOpenByAccountIdReturnsPlannedAndPlacedAcrossDates`: 같은 계좌에 어제 PLACED·오늘 PLANNED·오늘 FILLED·다른 계좌 PLANNED 저장 → 2건(어제 PLACED, 오늘 PLANNED 순). 선행 행은 기존 테스트의 accounts → strategy → strategy_version → strategy_cycle 삽입 패턴.
  - `savesAndReloadsFractionalQuantity`: `cryptoMarket(... quantity 0.00012345, price 150000000)` save → reload 후 quantity·price 동일.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*OrderTest' --tests '*OrderPersistenceAdapterDbTest'`
- [ ] **Step 3: 구현** — JpaRepository에 derived query 또는 JPQL `status in :statuses and accountId = :accountId order by tradeDate, createdAt`.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(trading): 코인 시장가 주문 팩토리와 계좌 미완료 주문 조회를 추가한다`

---

### Task 6: 코인 주문 동기화 (`CryptoOrderSynchronizer`) + 체결 이벤트

**Files:**
- Create: `TC/trading/application/service/CryptoOrderSynchronizer.java` (package-private `@Component`)
- Create: `TC/trading/application/service/CryptoFailure.java` (package-private, 예외 분류)
- Create: `TC/trading/application/event/CryptoOrderFilledEvent.java`
- Modify: `TC/trading/application/service/DcaExecutionRecorder.java` (관리자 알림 대상에 `EXCHANGE_API_ERROR` 추가)
- Test: `TCT/trading/application/service/CryptoOrderSynchronizerTest.java`, `CryptoFailureTest.java`

**Interfaces:**
- Consumes: `OrderPort.findOpenByAccountId`, `markPlaced`, `markFailed`, `markFilled`, `markCancelled`(Task 5); `CryptoExchangePort.findOrderByIdentifier/findOrderByUuid`; `DcaExecutionRecorder.recordOrdered/recordSkipped`.
- Produces:
  - `enum CryptoFailure { KEY_EXPIRED, REJECTED, TRANSIENT; static CryptoFailure classify(RuntimeException e) }` — `e.getClass() == BrokerCredentialException.class` → KEY_EXPIRED(하위 `BrokerIpNotAllowedException`·`BrokerPermissionDeniedException`은 REJECTED), `BrokerApiException.isRejection(e)` → REJECTED, 그 외(`BrokerRateLimitException`·5xx·I/O·기타 RuntimeException) → TRANSIENT. `DcaSkipReason reason()` : KEY_EXPIRED→`API_KEY_EXPIRED`, 나머지→`EXCHANGE_API_ERROR`.
  - `record CryptoOrderFilledEvent(UUID userId, UUID accountId, UUID strategyId, String accountNickname, StrategyTicker ticker, OrderDirection direction, BigDecimal filledQuantity, BigDecimal avgPrice, BigDecimal amountKrw)` — amountKrw = filledQuantity × avgPrice, 원 단위 HALF_UP.
  - `void sync(CryptoExchangePort exchange, List<BatchContext> accountContexts, Map<UUID, DcaConfig> configsByStrategyId, LocalDateTime nowKst)` — 한 계좌의 전략 컨텍스트들(같은 account). 주문 → 전략 매칭은 `order.strategyCycleId() == ctx.currentCycle().id()`.

동작(주문별, 예외는 주문 단위로 잡아 TRANSIENT면 그대로 두고 다음 주문):
- PLANNED: `findOrderByIdentifier(order.id().toString())`
  - present → `markPlaced(id, result.uuid())` + `recorder.recordOrdered(ctx, order.tradeDate(), order, order.price())`. 결과가 이미 체결 상태면 같은 틱에 아래 PLACED 처리도 수행.
  - empty → `markFailed(id)`. 그리고 `order.tradeDate()`가 오늘이 아니거나 `nowKst.getHour() != config.executionHour()`면 `recorder.recordSkipped(ctx, order.tradeDate(), EXCHANGE_API_ERROR, "주문 접수 확인 실패", order.price())`. 시간대 안이면 기록 없음(Task 7이 재시도).
- PLACED: `findOrderByUuid(order.externalOrderId())`
  - `executedVolume > 0` 이고 state ∈ {DONE, CANCEL} → `markFilled(id, executedVolume, avgPrice, FILLED)` + `CryptoOrderFilledEvent` 발행.
  - `executedVolume == 0` 이고 CANCEL → `markCancelled(id)`.
  - 그 외(WAIT/WATCH) → 그대로.

- [ ] **Step 1: 실패 테스트** — Mockito, `CryptoExchangePort`·`OrderPort`·`DcaExecutionRecorder`·`ApplicationEventPublisher` mock.
  - `plannedFoundOnExchangeIsRecoveredWithoutResubmit`: PLANNED + identifier 조회 present(WAIT) → `markPlaced(id,"upbit-uuid")`, `recordOrdered`, `buyMarketByAmount`/`sellMarketByVolume` 호출 없음.
  - `plannedOrderNotFoundBeforeHourEndsIsFailedThenRetried`: now 13:20, executionHour 13, empty → `markFailed`, `recordSkipped` 호출 없음.
  - `plannedOrderNotFoundAfterHourIsSkipped`: now 14:00, executionHour 13 → `markFailed` + `recordSkipped(..., EXCHANGE_API_ERROR, "주문 접수 확인 실패", price)`.
  - `transientLookupFailureLeavesPlanned`: 조회가 `RuntimeException` → mark* 호출 없음.
  - `placedFilledIsMarkedAndEventPublished`: executedVolume `0.0001`, avgPrice `150000000`, state CANCEL → `markFilled(id, 0.0001, 150000000, FILLED)`, 이벤트 amountKrw `15000`.
  - `placedCancelledWithoutFillIsCancelled`.
  - `CryptoFailureTest`: 각 예외 타입 → 분류(`StubBrokerApiException`으로 rejected true/false 둘 다).
  - `DcaExecutionRecorderTest`(기존)에 `exchangeApiErrorAlsoNotifiesAdmin` 추가.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CryptoOrderSynchronizerTest' --tests '*CryptoFailureTest' --tests '*DcaExecutionRecorderTest'`
- [ ] **Step 3: 구현** — `@Transactional` 없음(HTTP 포함). recorder 메서드는 자체 트랜잭션.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(trading): 코인 주문의 접수 복구·체결 확인 동기화를 추가한다`

---

### Task 7: 코인 DCA 회차 실행 (`CryptoDcaRoundExecutor`)

**Files:**
- Create: `TC/trading/application/service/CryptoDcaRoundExecutor.java` (package-private `@Component`)
- Test: `TCT/trading/application/service/CryptoDcaRoundExecutorTest.java`

**Interfaces:**
- Consumes: `DcaConditions.check`(Task 1), `getCompletedDailyCandles(ticker, 250)`(Task 2), `CryptoFearGreedIndexPort`(Task 3), `Order.cryptoMarket`·`OrderPort.save/markPlaced/markFailed/findByStrategyId`(Task 5), `CryptoFailure`(Task 6), `DcaExecutionRecorder`, `DcaExecutionPort.find`, `DcaSchedule.isExecutionDay`.
- Produces: `void executeAccount(CryptoExchangePort exchange, List<BatchContext> accountContexts, Map<UUID, DcaConfig> configsByStrategyId, LocalDateTime nowKst)` — 한 계좌 처리. 계좌 단위 예외는 던진다(Task 8이 격리).

동작:
1. 대상 전략 = `config.executionHour() == nowKst.getHour()` && `DcaSchedule.isExecutionDay(today, config, d -> true)` && `dcaExecutionPort.find(strategyId, today).isEmpty()`. 없으면 HTTP 호출 없이 반환.
2. 당일 같은 `orderLeg`의 FAILED 아닌 주문이 `findByStrategyId(strategyId, today, today)`에 있으면 `recordOrdered`로 복구하고 넘어간다(주식 `DcaRoundPlanner`의 terminal 복구와 같은 규칙).
3. 계좌 1회: `getBalance(account)`, `getCurrentPrices(대상 티커)`. 실패는 아래 오류 규칙.
4. 전략별(전략 컨텍스트 순서대로):
   - closes = `getCompletedDailyCandles(ticker, 250)` — 티커별 메모(같은 틱 안). 신선도: 마지막 봉 `kstDate` == (`nowKst.getHour() >= 9` ? today−1 : today−2), 아니면 closes=null.
   - holding = AVG_PRICE 조건 있을 때만 `new Holding(balance.holdings[t].total(), avgBuyPrice)`(보유 없으면 `(ZERO, null)`).
   - fearGreed = FEAR_GREED 조건 있을 때만 `CryptoFearGreedIndexPort.latest().orElse(null)`(틱 메모).
   - `DcaConditions.check(config, closes, holding, fearGreed, currentPrice)` → Skipped면 `recordSkipped` 후 다음.
   - 금액: BUY `amount = min(config.amount(), floor(krwAvailable ÷ 1.0005))`(정수). SELL: 보유 가용 0 → `NO_HOLDINGS "보유 수량 0"`; `volume = min(floor8(config.amount() ÷ currentPrice), 보유 가용)`, 금액 = volume × currentPrice.
   - 금액 < 5,000 → `recordSkipped(BELOW_MIN_ORDER_AMOUNT, "주문 금액 <원> < 최소 5000", currentPrice)`.
   - 주문: `order = orderPort.save(Order.cryptoMarket(account.id(), cycle.id(), today, ticker, direction, config.orderLeg(), config.timing(), 추정수량, currentPrice))` — 추정수량 BUY = floor8(amount ÷ currentPrice), SELL = volume. 그 뒤 `buyMarketByAmount(ref, ticker, amount, order.id().toString())` / `sellMarketByVolume(...)`.
     - 성공 → `markPlaced(order.id(), result.uuid())` + `recordOrdered(ctx, today, order, currentPrice)`. 로컬 차감: BUY `krwAvailable -= amount × 1.0005`, SELL 보유 가용 `-= volume`.
     - 실패 → `CryptoFailure.classify`: KEY_EXPIRED/REJECTED → `markFailed` + `recordSkipped(reason, 메시지, currentPrice)`; TRANSIENT → PLANNED 그대로(Task 6이 다음 틱 복구), 로컬 차감은 성공과 같게(이중 지출 방지).
5. 오류 규칙(잔고·시세·일봉 조회 실패 포함): KEY_EXPIRED → 남은 대상 전략 전부 `recordSkipped(API_KEY_EXPIRED)`; REJECTED → 해당 범위 `EXCHANGE_API_ERROR`; TRANSIENT → `nowKst.getMinute() >= 50`이면 `EXCHANGE_API_ERROR` skip, 아니면 기록 없이 반환(다음 틱 재시도). 일봉 TRANSIENT 실패는 closes=null로 처리하지 말고 이 규칙을 따른다(DATA_UNAVAILABLE은 신선도 불일치 전용).

- [ ] **Step 1: 실패 테스트** — 고정 `nowKst`, mock `CryptoExchangePort`(`getCompletedDailyCandles`는 오늘−1까지 250봉), `OrderPort.save`는 id 부여한 Order 반환.
  - `buyCapsToAvailableKrwMinusFee`: amount 100000, krwAvailable 50000 → `buyMarketByAmount(..., 49975, id)` (floor(50000/1.0005)=49975).
  - `buyBelowMinAfterCapIsSkipped`: krwAvailable 5002 → amount 4999 → `BELOW_MIN_ORDER_AMOUNT`, 주문 없음.
  - `secondStrategyUsesLocallyDeductedBalance`: BTC·ETH 각 30000 BUY, krwAvailable 40000 → BTC 30000, ETH `floor((40000 − 30015)/1.0005)` = 9980.
  - `sellVolumeFloorsToEightDecimalsAndCapsHoldings`: amount 10000, currentPrice 30000000 → volume `0.00033333`; 보유 0.0002 → `0.0002`, 금액 6000 ≥ 5000 → 주문.
  - `sellWithoutHoldingsIsNoHoldings`.
  - `freshnessBeforeNineExpectsTwoDaysAgo`: now 03:10, 마지막 봉 today−2 → 조건 통과·주문; now 10:10, 마지막 봉 today−2 → `DATA_UNAVAILABLE`.
  - `notExecutionHourDoesNothing`: executionHour 13, now 12:50 → exchange 호출 0.
  - `alreadyExecutedTodayDoesNothing`.
  - `keyExpiredSkipsAllTargets`: `getBalance`가 `BrokerCredentialException` → 대상 2개 모두 `API_KEY_EXPIRED`.
  - `transientBeforeMinute50Retries` / `transientAtMinute50IsSkipped`.
  - `unknownPlacementLeavesPlanned`: `buyMarketByAmount`가 `StubBrokerApiException`(rejected=false) → `markFailed`·`recordSkipped` 없음.
  - `rejectedPlacementIsFailedAndSkipped`: rejected=true → `markFailed` + `EXCHANGE_API_ERROR`.
  - `monthEndCorrectionRunsOnLastDay`: MONTHLY dayOfMonth 31, today 2026-02-28 → 실행.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CryptoDcaRoundExecutorTest'`
- [ ] **Step 3: 구현** — `@Transactional` 없음. 상수 `CANDLE_COUNT = 250`, `FEE_RATE = new BigDecimal("0.0005")`(ponytail 주석), `LAST_TICK_MINUTE = 50`.
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(trading): 코인 DCA 회차 판정·금액 산정·시장가 주문 실행기를 추가한다`

---

### Task 8: 러너·스케줄러 (`CryptoDcaRunner`, `CryptoDcaScheduler`)

**Files:**
- Create: `TC/trading/application/service/CryptoDcaRunner.java` (public `@Service` — usecase 인터페이스 `TC/trading/application/usecase/CryptoDcaExecutionUseCase.java` 경유)
- Create: `TC/trading/adapter/in/schedule/CryptoDcaScheduler.java`
- Test: `TCT/trading/application/service/CryptoDcaRunnerTest.java`, `TCT/trading/adapter/in/schedule/CryptoDcaSchedulerTest.java`

**Interfaces:**
- Consumes: `StrategyPort.findAllActive()`, `AccountPort.findAll()`, `StrategyCyclePort.findLatestByStrategyIds`, `TradingUserProfilePort.findAllByUserIds`, `StrategyDcaDetailPort.findActiveByStrategyId`, `CryptoExchanges.of(broker)`, Task 6 `sync`, Task 7 `executeAccount`, `TradingErrorReportPort.reportError`.
- Produces: `interface CryptoDcaExecutionUseCase { void runTick(LocalDateTime nowKst); }`.

동작: ACTIVE·`type == DCA`·계좌 `broker().getMarket() == Market.CRYPTO_KRW` 전략만 → 계좌별 그룹(계좌 순서 고정: accountId 정렬) → 계좌마다 `try { sync(...); executeAccount(...); } catch (RuntimeException e) { log.error; reportError(e); }`. 컨텍스트 조립 실패(사이클·프로필 없음)는 전략 단위 `reportError` 후 제외(`BatchContextFactory`와 같은 규칙, 코드는 재사용하지 않는다 — 그쪽은 코인을 제외하므로).

스케줄러:
```java
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
@Scheduled(cron = "0 */10 * * * *", zone = TimeZones.KST_ID)
public void run() { schedulerLockService.tryRun("crypto-dca", Duration.ofMinutes(9), () -> useCase.runTick(LocalDateTime.now(clock.withZone(TimeZones.KST)))); }
```
(`SchedulerLockService.tryRun`의 `LockedTask` 시그니처·`InterruptedException` 처리는 `TradingOpenScheduler`를 따른다. `SchedulerJobRunner`로 감쌀지는 10분 주기 시작/종료 알림이 텔레그램을 도배하지 않는지 확인 후 결정 — 도배하면 감싸지 않는다.)

- [ ] **Step 1: 실패 테스트**
  - `onlyCryptoDcaStrategiesAreRun`: KIS DCA·UPBIT INFINITE(가정상 없음이지만 필터 확인)·UPBIT DCA → executeAccount는 UPBIT DCA 계좌 1회.
  - `oneAccountFailureDoesNotStopOthers`: 계좌 A `sync`가 예외 → `reportError` 1회, 계좌 B `executeAccount` 호출됨.
  - `syncRunsBeforeExecute`: InOrder 검증.
  - `CryptoDcaSchedulerTest.lockNameAndDuration`: `tryRun("crypto-dca", Duration.ofMinutes(9), any())`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CryptoDcaRunnerTest' --tests '*CryptoDcaSchedulerTest'`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** + `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*' --tests '*TradingApplicationContextLoadTest'` (Modulith·Hexagonal 규칙, 빈 와이어링)
- [ ] **Step 5: 커밋** — `feat(trading): 10분 주기 코인 DCA 스케줄러와 계좌별 러너를 추가한다`

---

### Task 9: 알림 — 코인 체결·키 만료, 다음 실행일 24/7

**Files:**
- Modify: `TC/notify/application/port/output/TradingUserNotificationPort.java` (+`notifyCryptoTrade`, `notifyApiKeyExpiring`)
- Modify: `TC/notify/adapter/out/gateway/TradingUserNotificationAdapter.java`
- Create: `TC/notify/adapter/out/gateway/CryptoTradeNotifier.java` (`CryptoOrderFilledEvent` 리스너 — 텔레그램/FCM + SSE `TradeEventMessage`)
- Create: `TC/account/application/event/ApiKeyExpiringEvent.java`, `TC/account/adapter/in/schedule/ApiKeyExpiryScheduler.java`, 리스너 `TC/notify/adapter/out/gateway/ApiKeyExpiryNotifier.java`
- Modify: `TC/trading/application/service/DcaSummaryAssembler.java` + 호출처 `StrategyService.java:240`, `StrategyCreationService.java:132`
- Test: `CryptoTradeNotifierTest`, `ApiKeyExpirySchedulerTest`, `ApiKeyExpiryNotifierTest`, `DcaSummaryAssemblerTest`

**Interfaces:**
- Consumes: `CryptoOrderFilledEvent`(Task 6), `TradeEventMessage.buy/sell(BigDecimal ...)`(Task 4).
- Produces:
  - `void notifyCryptoTrade(TradingUserProfile profile, CryptoOrderFilledEvent event)` — 텔레그램 문구 `"[<닉네임>] <BTC> 매수 체결 — 수량 <0.00012345>, 평균가 <150,000,000>원, 금액 <15,000>원"`(SELL은 "매도"). 수량은 `toPlainString()`, 원화는 천 단위 콤마. `TradingUserProfile.telegramEnabled()` 규칙·TRADING_ALERT 설정은 기존 `notifyTradingReport`와 동일하게 따른다.
  - `record ApiKeyExpiringEvent(UUID userId, UUID accountId, String accountNickname, Broker broker, LocalDate expiresOn, int daysLeft)` — account 모듈 `"event"` NamedInterface 패키지에 둔다(위치는 기존 account 이벤트와 같은 패키지 확인).
  - `ApiKeyExpiryScheduler`: `@Scheduled(cron = "0 0 10 * * *", zone = KST)`, 락 `api-key-expiry`, `Duration.ofMinutes(30)`. `accountPort.findAll()` 중 `apiKeyExpiresAt != null`이고 `daysLeft = ChronoUnit.DAYS.between(todayKst, expiresAt를 KST 날짜로)` ∈ {30, 7, 0}이면 이벤트 발행.
  - `void notifyApiKeyExpiring(TradingUserProfile profile, ApiKeyExpiringEvent event)` — 문구 `daysLeft == 0`: `"[<닉네임>] 업비트 API 키가 오늘 만료됩니다. 키를 새로 발급해 교체해 주세요."`, 그 외 `"[<닉네임>] 업비트 API 키가 <N>일 후(<yyyy-MM-dd>) 만료됩니다."`.
  - `DcaSummaryAssembler.assemble(DcaConfig config, DcaExecution last, LocalDate from, Market market)` — `CRYPTO_KRW`면 `DcaSchedule.nextExecutionDay(from, config, d -> true)`를 KST 날짜 그대로(usSession=kst). `static LocalDate firstEligibleDate(LocalDate cycleStartDate, DcaExecution last, DcaConfig config, Market market, LocalDateTime nowKst)` — CRYPTO_KRW: `from = today`, `nowKst.getHour() > executionHour`이거나 `last.tradeDate == today`면 내일, 시작일 제외 규칙 없음. 주식은 기존 로직 그대로.

- [ ] **Step 1: 실패 테스트**
  - `CryptoTradeNotifierTest.sendsTelegramAndSse`: 이벤트 → `notifyCryptoTrade` 1회, `realtimeNotificationPort.notifyTrade(userId, msg)`에서 `msg.quantity()` = `0.00012345`, `kind == BUY`.
  - `ApiKeyExpirySchedulerTest`: 만료일이 오늘+30/+7/+0/+8/null인 계좌 5개 → 이벤트 3건(daysLeft 30,7,0).
  - `ApiKeyExpiryNotifierTest`: daysLeft 0 문구, 7 문구.
  - `DcaSummaryAssemblerTest.cryptoNextExecutionIncludesTodayBeforeHour`: DAILY, executionHour 13, now 10:00 → today; now 14:00 → tomorrow; 주식 케이스 기존 테스트 통과.
  - `cryptoWeeklySundayIsNotShiftedByUsHoliday`: WEEKLY SUN → 다음 일요일 그대로.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CryptoTradeNotifierTest' --tests '*ApiKeyExpiry*' --tests '*DcaSummaryAssemblerTest'`
- [ ] **Step 3: 구현** — 리스너는 `@TransactionalEventListener(fallbackExecution = true)`(기존 `TradingReportNotifier`와 동일), 프로필은 `TradingUserProfiles.requireProfile`. notify 모듈이 account 이벤트를 구독하는 것이 Modulith 허용인지 `ModulithArchitectureTest`로 확인 — 막히면 이벤트를 `trading` 쪽이 아니라 account `event` NamedInterface에 두었는지 재확인.
- [ ] **Step 4: 통과 확인** + `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*'`
- [ ] **Step 5: 커밋** — `feat(notify): 코인 체결·업비트 키 만료 알림과 코인 DCA 다음 실행일 계산을 추가한다`

---

### Task 10: 코인 주문 혼입 감사

코인 주문이 `orders`에 들어간 뒤 계좌·사이클로 좁혀지지 않은 기존 경로가 깨지지 않게 한다. 경로마다 코인 주문 1건을 섞은 테스트로 증명한다.

**Files (확인 대상 — 문제 있는 곳만 수정):**
- `TC/trading/adapter/in/web/TradingInternalQueryController.java:38,46` → root 소비처(`grep -rn "orders\b\|OrderResponse" src/main/java/com/kista/api/admin`) — root가 수량을 `int`로 읽거나 브로커 라우팅을 하면 코인 제외 또는 BigDecimal 처리.
- `TC/trading/application/service/OrderCancelService.java:79`, `AccountOpenOrderCanceller`(`grep -rln "implements AccountOpenOrderCancelPort"`) — 코인 계좌 삭제·키 교체 시 PLACED 코인 주문에 `BrokerRouter` 취소를 호출하면 안 된다: 코인 계좌면 브로커 취소를 건너뛰고 PLANNED만 `markCancelled`(시장가는 즉시 체결되어 취소 대상이 없다).
- `TC/trading/adapter/in/schedule/TradingBatchResumer.java`, `OrderSubmissionReconciler`, `SubmittingOrderAwaiter` — 코인은 SUBMITTING을 쓰지 않음을 확인(수정 없으면 원장에 "확인만" 기록).
- `PortfolioService.java:34`(`findByUser`), `findByStrategyId` 소비처(전략 주문 이력 API) — 정수 변환(`intValue*`) 지점 grep.

- [ ] **Step 1: 감사** — 위 경로마다 코인 주문(quantity `0.00012345`, broker UPBIT)이 흘러갈 때 결과를 표로 원장에 기록(경로 / 문제 여부 / 조치).
- [ ] **Step 2: 실패 테스트** — 문제 있는 경로마다 1개. 최소 `accountDeletionWithPlacedCryptoOrderDoesNotCallBrokerCancel`(코인 계좌 삭제 → `BrokerRouter`/주식 취소 포트 호출 0, 예외 없음).
- [ ] **Step 3: 실패 확인 → 구현 → 통과 확인** — 해당 테스트 클래스만.
- [ ] **Step 4: 커밋** — `fix(trading): 코인 주문이 주식 전용 주문 조회·취소 경로에 섞이지 않게 한다`

---

### Task 11: A·B 이월 Minor 4건 + 문서 동기화

**Files:**
- `src/main/java/com/kista/api/admin/adapter/in/web/dto/AdminAccountResponse.java:30`, `AdminAccountItem.java:30` — `a.accountNo() == null ? null : AccountNumberMasker.mask(a.accountNo())`(UPBIT은 계좌번호 없음 → null).
- `TC/stats/domain/model/CryptoPortfolio.java:22-27` + 산정 서비스(`grep -rn "new CryptoPortfolio.Holding\|Holding(" TC/stats`) — 현재가 없으면 `currentPrice`·`evalAmountKrw` null, `totalAssetKrw`는 null 항목 제외 합산. `CryptoPortfolioResponse`의 `@Schema`에 nullable 표기.
- `TC/account/application/service/AccountService.java:128` 키 교체 저장 — `DataIntegrityViolationException`(unique 위반)을 `Account.DuplicateAccountException(cmd.appKey())`로 변환(409). 서비스가 adapter 예외를 직접 잡는 게 ArchUnit에 걸리면 persistence 어댑터 `save`에서 변환.
- `src/main/java/com/kista/api/web/GlobalExceptionHandler.java:57` — 제목 `"KIS Rate Limit"` → `"Broker Rate Limit"`.
- 문서: `src/test/resources/architecture/flows.yml`(코인 DCA 흐름·업비트 통신·`CryptoDcaScheduler`·`ApiKeyExpiryScheduler`), `docs/agents/scheduler-time-table.md`(crypto-dca 10분, api-key-expiry 10:00), `docs/agents/modules/tradingcore/{broker,trading,account,matching,notify}.md`, `README.md`(스케줄러·지원 범위), spec 섹션 3의 FEAR_GREED 문구를 "코인 전용 `CryptoFearGreedIndexPort`"로 수정.
- Test: `AdminAccountResponseTest`(UPBIT null), `CryptoPortfolio`/서비스 테스트(`missingPriceExcludedFromTotal`), `AccountServiceTest.rotationUniqueViolationIsDuplicate`, `ArchitectureMapTest`.

- [ ] **Step 1: 실패 테스트 작성**(위 4개 + `ArchitectureMapTest`는 문서 수정 후 실행)
- [ ] **Step 2: 실패 확인** — 해당 클래스만(`:test`/`:trading-core:test` 구분).
- [ ] **Step 3: 구현 + 문서 수정**
- [ ] **Step 4: 통과 확인** — 위 테스트 + `./gradlew :test --tests '*ArchitectureMapTest'`(위치 확인 후 해당 서브프로젝트).
- [ ] **Step 5: 커밋** — 코드(`fix: 업비트 관리자 계좌번호·포트폴리오 시세 누락·키 교체 경합·rate limit 제목을 정리한다`)와 문서(`docs: 코인 DCA 실행 흐름·스케줄러를 아키텍처 맵과 모듈 문서에 반영한다`) 2커밋.

---

### Task 12: 최종 검증 (컨트롤러 직접 수행)

- [ ] `./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"` → BUILD SUCCESSFUL. 실패 시 `grep -oP 'failures="\K[^"]+' */build/test-results/test/TEST-*.xml build/test-results/test/TEST-*.xml | grep -v ':0'`.
- [ ] 원장에 계약 변경 3건 정리 → "업비트 ui" 세션 전달용: `TradeEventMessage.quantity` BigDecimal(Task 4), `CryptoPortfolioResponse` 현재가·평가액 nullable(Task 11), 관리자 계좌 `accountNoMasked` UPBIT null(Task 11), `nextExecution` 24/7(Task 9).
- [ ] 배포 메모: root·trading-core 같은 커밋 배포(Task 4 계약), V8·V9 이후 trading-core 롤백 불가(기존), 로컬 trading-core는 `scheduler.enabled` 기본 on이라 UPBIT 실계좌를 로컬에 연결하면 실주문이 나간다.
