# 원장 BigDecimal 통일 + 자기 주문 체결 원장 Implementation Plan (Plan 1/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원장 보유수량(`holdings`)을 int에서 BigDecimal로 바꾸고, 모든 전략의 원장이 자기 주문 체결만 반영하게 한다. 기존 INFINITE·VR·PRIVACY의 주문 결과는 바뀌지 않아야 한다.

**Architecture:** 원장 타입을 BigDecimal로 바꾼다. 대상은 `AccountBalance.holdings`, `AccountBalance.Fill.quantity`, `CyclePosition.holdings`, DB `cycle_position.holdings`, 원장을 노출하는 응답·wire다. 주식 전략 공식은 손대지 않는다. 공식은 `AccountBalance.wholeShares()`(정수 주, 소수면 `ArithmeticException`)로 보유를 읽는다. 브로커 vendor 타입(`BrokerBalance`·`SellableQuantity`·`Execution`)과 FIDA 기준표(`PrivacyPlan`·privacy 계약)는 int를 유지한다. `TradingReporter`의 자기 주문 체결 필터는 DCA 전용이었는데, 이를 모든 전략 공통 규칙으로 바꾼다.

**Tech Stack:** Java 25, Spring Boot 4, JPA/Flyway(PostgreSQL), JUnit 5, AssertJ, Mockito

**Spec:** `docs/superpowers/specs/2026-10-21-dca-multi-rule-ledger-design.md` — §3 "원장 저장"·"원장 원칙" 행, §5.2 주식, §9 R1·E5. Plan 2(`2026-10-21-dca-multi-rule.md`)는 이 plan이 두 role 모두에 배포된 뒤 시작한다.

## Global Constraints

- 매매 공식 의미 변경 금지(`docs/agents/modules/tradingcore/trading-formulas.md`). Task 1의 골든 테스트가 R1 전후로 결과가 같은지 확인한다.
- 원장 비교는 `signum()`·`compareTo`를 쓴다. BigDecimal `equals`나 `==`·`!=`는 금지다.
- 원장 BigDecimal은 `Quantities.normalize`로 정규화한다. 꼬리 0과 지수 표기를 막아서, 정수 주식 수량이 JSON에서 지금처럼 `5`로 직렬화되게 한다.
- DB `numeric(20,8)`은 `orders.quantity`(V8)와 같은 정밀도다.
- trading-core 마이그레이션은 다음 번호 `V11__cycle_position_holdings_numeric.sql`이다. 지금 최신은 V10이다.
- 주석은 `//` 인라인만 쓴다. Javadoc은 금지다.
- 테스트는 서브프로젝트를 지정해서 실행한다(`:trading-core:test --tests ...`, `:test --tests ...`). 전체 스위트는 마지막 Task에서 1회만 돌린다.
- 커밋 author는 `narafu <narafu@kakao.com>`이고, 메시지는 한글 Conventional Commit에 Co-Authored-By 트레일러를 붙인다.

## Review Focus

1. **소수 보유가 주식 공식에 닿는 경우.** `wholeShares()`가 `ArithmeticException`을 내야 하고 조용히 잘라 버리면 안 된다(Task 2).
2. **외부 체결이 섞인 executions.** 브로커가 같은 종목에서 자기 주문이 아닌 체결을 돌려줘도 원장이 바뀌지 않아야 한다(Task 4).
3. **보정 leg와 수동 실행 주문의 체결.** `INFINITE_CORRECTION_*` leg 주문과 `ManualTradingService`가 저장한 주문의 체결은 자기 주문이므로 원장에 반영되어야 한다(Task 4).
4. **DB numeric 왕복.** 저장 후 다시 읽은 `CyclePosition.holdings`가 `5.00000000`이 아니라 `5`로 정규화돼 JSON·equals가 지금과 같아야 한다(Task 3).
5. **holdings 0 판정.** `BigDecimal("0.00000000")`도 0으로 판정되어야 한다. 이 판정을 쓰는 곳은 사이클 종료(`CycleCloser`), 리버스 진입, VR bootstrap이다(Task 2·3).

---

### Task 1: 백테스트 골든(특성화) 테스트 — 현재 HEAD 결과 고정

**Files:**
- Create: `trading-core/src/test/java/com/kista/tradingcore/stats/domain/backtest/BacktestGoldenTest.java`
- Create: `trading-core/src/test/resources/backtest-golden/{infinite,infinite-reverse,vr,privacy}.txt`

**Interfaces:**
- Consumes: `BacktestEngine.run(List<DailyCandle>, BacktestCommand)`, `BacktestEngine.Output`(`points()`, `tradeCount()`, `cycleCount()`, `warnings()`)
- Produces: 이후 Task가 매번 돌리는 회귀 잠금

- [ ] **Step 1: 결정적 캔들 생성기와 골든 테스트 작성**

캔들은 seed가 고정된 `java.util.Random(42)`로 만든다. 2024-01-02부터 평일 300개, 일간 수익률은 ±4% 랜덤워크다. 시가·고가·저가·종가 관계가 유효해야 한다. 시나리오는 셋이다.
- `infinite`: INFINITE SOXL, 분할 40, seed 10000, 리버스모드 진입이 없는 상승 구간 캔들
- `infinite-reverse`: 분할 20, 리버스모드에 진입할 만큼 큰 하락 구간 캔들
- `vr`: VR TQQQ, seed 2000, initialValue 1000, intervalWeeks 2, recurringAmount 100
- `privacy`: PRIVACY SOXL. `BacktestEngine.run(candles, command, privacyBases)` 오버로드를 쓰고, 고정 `PrivacyTradeBase`를 5개 이상 넣는다(BUY·SELL 혼합, 보유 감소 매도 포함). Task 2가 `PrivacyStrategy`를 수정하므로 반드시 넣는다.

`render(Output)`는 한 줄에 한 point를 쓴다. 형식은 `date|totalAsset(toPlainString, scale 2)`이고, 끝에 `trades=N cycles=M warnings=[...]`를 붙인다.

골든 파일 경로는 `trading-core/src/test/resources/backtest-golden/<scenario>.txt`다. trading-core 테스트의 `workingDir`가 루트이므로 이 상대경로로 읽고 쓴다. **파일이 없으면 현재 결과를 써 넣고, "골든 생성됨 — 재실행 필요" 메시지로 테스트를 실패시킨다.** 시스템 프로퍼티는 테스트 JVM에 전달되지 않으므로 쓰지 않는다.

```java
@ParameterizedTest @ValueSource(strings = {"infinite", "infinite-reverse", "vr", "privacy"})
void 백테스트_결과가_골든과_같다(String scenario) {
    assertThat(render(engine.run(candles(scenario), command(scenario)))).isEqualTo(readGolden(scenario));
}
```

- [ ] **Step 2: 현재 HEAD에서 골든 생성**

Run: `./gradlew :trading-core:test --tests '*BacktestGoldenTest' 2>&1 | grep -E "FAILED|BUILD|골든 생성됨"`
Expected: 첫 실행은 4건이 "골든 생성됨"으로 FAILED이고, 파일 4개가 생겨야 한다. 각 파일은 비어 있지 않아야 한다. `infinite-reverse`의 경로가 실제로 리버스모드를 탔는지 확인한다. `warnings`나 trade 수로 확인하되, 안 탔으면 캔들 seed나 구간을 조정한다.

- [ ] **Step 3: 비교 모드로 통과 확인**

Run: `./gradlew :trading-core:test --tests '*BacktestGoldenTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit** — `test(stats): R1 전환 전 백테스트 결과를 골든으로 고정한다`

---

### Task 2: 매칭 커널 원장 타입 BigDecimal — `AccountBalance`·`Fill`·`Quantities` 이동

**Files:**
- Move: `trading-core/.../trading/domain/model/Quantities.java` → `shared/src/main/java/com/kista/shared/kernel/Quantities.java`. 커널(matching)이 trading에 의존할 수 없어서 공용 어휘로 옮긴다. 기존 import는 전부 바꾼다.
- Modify: `trading-core/.../matching/domain/model/AccountBalance.java`
- Modify: AccountBalance 생성처 전부와 `holdings()` int 소비처 전부(조사 결과 main 약 40곳)
  - matching: `InfinitePosition`·`VrPosition`·`ReverseModePosition`·`VrStrategy`·`PrivacyStrategy`·`InfiniteCycleOrderStrategy`
  - stats: `BacktestEngine`
  - trading: `ExecutionFillMapper`, `TradingBalanceLoader`, `DcaRoundPlanner:171`
  - 그 외 컴파일러가 가리키는 곳
- Test: `trading-core/src/test/.../matching/domain/model/AccountBalanceTest.java`와 컴파일이 깨지는 테스트들
- **컴파일 경계:** Task 2가 끝나면 trading-core 전체(main과 test)가 컴파일되어야 한다. 그래서 trading 쪽 `AccountBalance.holdings()` int 소비처를 **임시로 `wholeShares()`에 연결**한다. 대상은 `CyclePosition.tradeSnapshot`·`toBalance`, `CycleCloser`, `VrReconfigureService`, `ManualTradeCorrectionService`, `CycleSnapshotCreator`, `TradingReporter` 등이다. `CyclePosition`과 DB는 int로 둔다. Task 3에서 이 소비처를 BigDecimal 의미(`signum`, `compareTo`, `multiply`)로 다시 바꾼다.

**Interfaces:**
- Produces:
  - `record AccountBalance(BigDecimal holdings, BigDecimal avgPrice, BigDecimal usdDeposit)`. compact 생성자가 `holdings = Quantities.normalize(holdings)`로 정규화하고, null은 금지한다.
  - `int AccountBalance.wholeShares()`: `holdings.intValueExact()`
  - `boolean AccountBalance.hasHoldings()`: `holdings.signum() > 0`
  - `interface AccountBalance.Fill { OrderDirection direction(); BigDecimal quantity(); BigDecimal amountUsd(); }`
  - 편의 생성자 `AccountBalance(int holdings, BigDecimal avgPrice, BigDecimal usdDeposit)`: 주식 호출부와 테스트 호환용. 내부에서 `BigDecimal.valueOf`로 바꾼다.
  - `com.kista.shared.kernel.Quantities.normalize(BigDecimal)`: 시그니처는 그대로다.

- [ ] **Step 1: 실패 테스트 작성** (`AccountBalanceTest`)

```java
@Test void 소수_보유는_wholeShares에서_예외() {
    assertThatThrownBy(() -> new AccountBalance(new BigDecimal("0.5"), BigDecimal.TEN, BigDecimal.ZERO).wholeShares())
        .isInstanceOf(ArithmeticException.class);
}
@Test void 소수_체결을_원장에_반영한다() {
    var b = new AccountBalance(BigDecimal.ZERO, null, BigDecimal.ZERO)
        .applyExecutions(List.of(fill(BUY, "0.00123", "100000")));
    assertThat(b.holdings()).isEqualByComparingTo("0.00123");
    assertThat(b.avgPrice()).isEqualByComparingTo("81300813.0081"); // 100000/0.00123, scale 4 HALF_UP
}
@Test void 꼬리0_보유는_정규화된다() {
    assertThat(new AccountBalance(new BigDecimal("5.00000000"), BigDecimal.ONE, BigDecimal.ZERO).holdings()).isEqualTo(new BigDecimal("5"));
}
@Test void 정수_전량매도_후_보유0이면_평단_null() { /* 기존 applyExecutions 의미 — holdings 10 매도 10 → holdings 0, avgPrice null */ }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*AccountBalanceTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`applyExecutions`는 지금 int로 하는 연산(L46-73)을 같은 의미의 BigDecimal 연산으로 옮긴다. 대상은 매수·매도 합, `max(0, holdings - sell)`, 매도 후 원가 비례 축소, 평단 `divide(newHoldings, 4, HALF_UP)`다. `== 0`/`> 0`은 `signum()`으로 쓴다.

주식 공식 소비처는 `holdings()`를 `wholeShares()`로 바꾼다. **공식 본문의 산술은 바꾸지 않는다.** 바뀌는 곳은 이렇다.
- `InfinitePosition.holdings()`·`VrPosition.holdings()`는 `int`를 계속 돌려주되 `balance.wholeShares()`에 위임한다.
- `ReverseModePosition.of`는 `balance.wholeShares()`를 쓴다.
- `BacktestEngine`의 `balance.holdings()`는 `wholeShares()`로 바꾼다.
- `PrivacyStrategy` L64·L165는 `wholeShares()`를 쓴다.

`ExecutionFillMapper.ExecutionFill.quantity`는 `BigDecimal.valueOf(execution.quantity())`로 만든다. `Execution`은 int를 유지한다. `BacktestEngine`의 익명 Fill도 같은 방식이다.

- [ ] **Step 4: matching·stats·trading 관련 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.*' --tests '*BacktestGoldenTest' --tests '*BacktestEngineTest' --tests '*TradingServiceTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: BUILD SUCCESSFUL. 골든 3개가 그대로 통과해야 한다.

- [ ] **Step 5: Commit** — `refactor(matching): 원장 보유수량을 BigDecimal로 바꾸고 주식 공식은 wholeShares로 읽는다`

---

### Task 3: `CyclePosition`·DB·응답 원장 타입 BigDecimal

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V11__cycle_position_holdings_numeric.sql`
  ```sql
  -- 원장 보유수량을 소수(크립토)까지 담도록 넓힌다. trading-core 단독 스키마라 root expand/contract 제약 밖.
  -- 주의: 이후 구 이미지(int holdings 엔티티)는 ddl-auto validate에서 기동 실패 — 이미지 롤백 불가(V8·V9와 동일).
  ALTER TABLE trading.cycle_position ALTER COLUMN holdings TYPE numeric(20,8);
  ```
- Modify: `CyclePositionEntity.java:41`을 `@Column(name="holdings", nullable=false, precision=20, scale=8) private BigDecimal holdings;`로 바꾼다.
- Modify: `CyclePosition`(`BigDecimal holdings`, compact 생성자에서 normalize)
  - `bootstrapSnapshot(..., BigDecimal holdings, ...)`
  - `totalAssetAtCloseOrNull`과 `totalAssetWithAvgPriceFallback`은 `multiply(holdings)`, `signum()`으로 바꾼다.
- Modify: `CyclePositionHistoryEntry.holdings`를 BigDecimal로 바꾼다.
- Modify: 응답과 wire를 BigDecimal로 바꾼다.
  - trading-core: `trading/.../dto/CycleHistoryResponse`, `stats/.../dto/CycleHistoryResponse`, `PortfolioSummaryResponse`, `NextOrdersResponse`
  - `:shared`: `PortfolioCurrentResponse.holdings`, `TradeCorrectionResponse.finalHoldings`
  - root: `AdminTradeCorrectionResponse.finalHoldings`, `TelegramBotService:131,135`(`closingPrice.multiply(holdings)`)
- Modify: `holdings` int 소비처를 `signum()`/`compareTo`/`wholeShares()`로 바꾼다.
  - `StrategyService:198`
  - `CyclePositionPersistor:48,62`
  - `CycleSnapshotCreator:107`
  - `VrReconfigureService:149-170`. VR은 주식이라 `latestPosition.toBalance().wholeShares()`를 쓴다.
  - `VrCycleRolloverService:118`
  - `ManualTradeCorrectionService:85,127`
  - `CycleCloser:21`
  - `StrategyCreationPersister:45,70`
  - `StrategyCreationService:81-95`. 등록 입력 `initialHoldings`는 Plan 1에서 Integer를 유지하고, 경계에서 `BigDecimal.valueOf`로 바꾼다.
  - `scripts/dev-seed/dev-seed.sql`: 정수 리터럴이라 numeric에 그대로 들어간다. 변경할 필요가 없는지만 확인한다.
- Test: `CyclePositionPersistorTest`, `CycleSnapshotCreatorTest`, `StrategyServiceTest`, `ManualTradeCorrectionServiceTest`, persistence DbTest, `ContractWireFormatTest`, root `PortfolioQueryHttpAdapterTest`·`TradingCommandHttpAdapterTest`

**Interfaces:**
- Consumes: Task 2 `AccountBalance(BigDecimal, …)`, `wholeShares()`, `hasHoldings()`, `Quantities`
- Produces:
  - `record CyclePosition(UUID id, UUID strategyCycleId, BigDecimal usdDeposit, BigDecimal closingPrice, BigDecimal avgPrice, BigDecimal holdings, Instant createdAt, Instant deletedAt)`
  - `PortfolioCurrentResponse.holdings: BigDecimal`
  - `TradeCorrectionResponse.finalHoldings: BigDecimal`

- [ ] **Step 1: 실패 테스트 작성**
  - `CyclePositionPersistenceAdapterDbTest`(또는 기존 persistence DbTest)에 케이스를 추가한다. `holdings=new BigDecimal("0.00123")`을 저장한 뒤 조회하면 값이 `0.00123`이어야 한다. `holdings=5`를 저장한 뒤 조회하면 `isEqualTo(new BigDecimal("5"))`가 성립해야 한다(정규화).
  - `ContractWireFormatTest`에 케이스를 추가한다. `PortfolioCurrentResponse`의 holdings `5`가 JSON에서 `"holdings":5`로 직렬화되어야 한다.
  - `CycleCloserTest`(또는 기존 사이클 종료 테스트)에 케이스를 추가한다. holdings `0.00000000`도 종료 대상이어야 한다.

- [ ] **Step 2: 실패 확인** — 해당 테스트를 지정해서 실행하면 컴파일 실패가 나야 한다.

- [ ] **Step 3: 구현**: 위 Files 목록대로 바꾼다. 컴파일러가 가리키는 나머지 int 소비처도 같은 규칙으로 처리한다. 규칙은 이렇다. 원장 비교는 `signum`, 주식 공식 입력은 `wholeShares()`, 금액 계산은 `multiply(holdings)`다.

- [ ] **Step 4: 통과 확인**

Run: `docker compose up -d postgres` 후 `./gradlew :trading-core:test --tests '*CyclePosition*' --tests '*CycleCloser*' --tests '*StrategyServiceTest' --tests '*ManualTradeCorrection*' --tests '*BacktestGoldenTest' --tests '*TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"`, `./gradlew :shared:test --tests '*ContractWireFormatTest' :test --tests '*PortfolioQueryHttpAdapterTest' --tests '*TradingCommandHttpAdapterTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: BUILD SUCCESSFUL. `TradingApplicationContextLoadTest`가 V11을 적용한 뒤 `ddl-auto: validate`를 통과해야 한다.

- [ ] **Step 5: Commit** — `refactor(trading): cycle_position 보유수량을 numeric·BigDecimal로 넓힌다`

---

### Task 4: 모든 전략의 원장은 자기 주문 체결만 반영

**Files:**
- Modify: `trading-core/.../trading/application/service/TradingReporter.java:64-71`
  - `if (snapshots)` 안에 있던 자기 주문 필터를 조건 없이 적용한다.
  - DCA의 `liveSnapshot` 분기는 Plan 2에서 제거하므로 이번에는 그대로 둔다.
- Test: `trading-core/src/test/.../trading/application/service/TradingReporterTest.java`

**Interfaces:**
- Consumes: `Order.externalOrderId()`, `Execution.externalOrderId()`
- Produces: 없음. 동작만 바뀐다. 리포트 알림(`TradeLegSummary`)도 필터된 체결로만 만든다.

- [ ] **Step 1: 실패 테스트 작성** (`TradingReporterTest`)

```java
@Test void INFINITE_외부_체결은_원장에_반영하지_않는다() {
    // mainOrders: externalOrderId "A1"(BUY 3 FILLED). executions: A1 BUY 3 + "EXT9" BUY 5(사용자 앱 직접 매매)
    // 저장된 cycle_position.holdings == 기존 + 3 (외부 5주 미반영)
}
@Test void 보정_leg_주문_체결은_반영한다() { /* INFINITE_CORRECTION_01 leg, externalOrderId "C1" 체결 → 반영 */ }
@Test void 체결_없는_자기_주문은_원장_불변() { /* executions 비어 있음 → holdings 그대로 */ }
@Test void 개장배치_AT_OPEN_자기주문_체결도_반영한다() { /* prePlaced 주문 체결 → 반영 */ }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*TradingReporterTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `INFINITE_외부_체결은_원장에_반영하지_않는다` FAILED

- [ ] **Step 3: 구현**: 자기 주문 ID 집합(`mainOrders`의 non-null `externalOrderId`)으로 executions를 거르는 코드를 분기 밖으로 옮긴다. 먼저 `TradingService.java:220-243`에서 `mainOrders`가 어떤 쿼리로 채워지는지 확인한다. 거기에 `prePlaced`(개장 배치에서 접수된 주문), 같은 날 수동 실행 주문, 재개된 배치의 주문이 모두 들어가는지 보고, 확인 결과를 커밋 메시지 본문에 한 줄로 남긴다. 빠지는 종류가 있으면 이 Task에서 함께 포함시킨다. 주석은 "원장 = 우리 매매 원칙의 기록, 외부 매매는 수동 보정으로만 반영 (spec 3장 원장 원칙)"으로 단다.

- [ ] **Step 4: 통과 확인** — Step 2 명령을 다시 실행했을 때 Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit** — `feat(trading): 모든 전략의 원장이 자기 주문 체결만 반영하게 한다`

---

### Task 5: 문서·아키텍처 맵 동기화 + 전체 검증

**Files:**
- Modify: `src/test/resources/architecture/flows.yml`. 마감 리포트 단계의 `desc`에 "자기 주문 체결만 원장 반영"을 넣는다.
- Modify: `docs/agents/modules/tradingcore/trading.md`. 원장 규칙과 `wholeShares()` 관용구를 적는다.
- Modify: `docs/agents/modules/tradingcore/matching.md`. `AccountBalance` BigDecimal 원장과 주식 공식의 `wholeShares()`를 적는다.
- Modify: `docs/agents/modules/shared/kernel.md`. `Quantities`를 이동했다고 적는다.
- Modify: `docs/agents/constraints.md` "수량 변수명 규칙"에 "원장 holdings는 BigDecimal(정수 주식은 normalize로 정수 표기)" 한 줄을 넣는다.

- [ ] **Step 1: 문서 갱신**
- [ ] **Step 2: 전체 스위트 1회 실행**

Run: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`
Expected: BUILD SUCCESSFUL. 이 Step에는 `ArchitectureMapTest`, `HexagonalArchitectureTest`, `GradleModuleBoundaryTest`, 골든 테스트가 포함된다.

- [ ] **Step 3: Commit** — `docs: 원장 BigDecimal·자기 주문 체결 원칙을 모듈 문서와 아키텍처 맵에 반영한다`

## 배포 메모

- V11은 이미지 롤백이 불가능하다(구 이미지는 validate에서 실패한다).
- root(`:api`)와 trading-core는 wire 타입(`PortfolioCurrentResponse` 등)이 바뀌므로 함께 배포한다. Plan 1에서 값은 항상 정수라 JSON 표현은 지금과 같다.
- kista-ui 영향: 표시 형식은 바뀌지 않는다(정수 그대로). openapi 타입은 `integer`에서 `number`로 바뀐다. Plan 2 확정 계약과 함께 kista-ui-fc 세션에 전달한다.
