# KRW 응답 금액 정수 반올림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원장에서 나온 KRW 금액이 trading-core API 응답에 정수(scale 0)로 나가게 한다. USD·USDT 응답과 모든 비율·지수 값은 바뀌지 않는다.

**Architecture:** 원장 저장과 내부 계산은 지금처럼 `AccountBalance.LEDGER_SCALE`(2)을 쓴다. 결과를 조립하는 application 서비스·assembler에서 통화가 정해지는 지점에 `Currency.round(BigDecimal)`(HALF_UP, `amountScale()`)을 적용한다. 선례는 `AccountLedgerTotalsService:41`이다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + AssertJ + Mockito (`:trading-core`)

**Spec:** `docs/superpowers/specs/archive/2026-11-10-krw-amount-scale-design.md`

## Global Constraints

- 반올림 대상은 금액(예수금, 총자산, 원금, 시작·종료 금액, 손익, DCA 정액 금액)뿐이다. 가격(`avgPrice`, `closingPrice`)과 수량(`holdings`)은 바꾸지 않는다.
- 비율(`returnRate`, `avgReturnRate`, `winRate`)과 투자지수는 반올림 전 값으로 계산한다. 같은 입력에서 값이 바뀌면 실패다.
- 한 응답 안의 등식을 지킨다: summary는 `realized + unrealized = 총손익`, `total* = Σ byType`이다. cycles는 `pnl = endAmount − startAmount`이다(반올림된 값끼리 뺀다).
- `Currency.round(null)`은 NPE다. nullable 필드(`endAmount`, `pnl`, `initialDeposit`, `ledgerDeposit`)는 호출부에서 null을 그대로 둔다.
- persistence 매퍼와 web DTO `from()`은 바꾸지 않는다(constraints.md: 매퍼는 필드 복사만, DTO는 통화가 없다). 엔티티 `scale2`(`StrategyDcaRuleEntity:106`, `StrategyDcaConditionEntity:70`)는 DB 컬럼 정규화라 그대로 둔다.
- 주석 규칙: `//` 인라인만 쓰고 Javadoc은 쓰지 않는다. 신규 메서드에는 역할 한 줄 주석을 단다.
- 테스트 단언에서 scale은 `isEqualByComparingTo`로 검증할 수 없다(scale 무시). `hasToString("12346")` 또는 `.scale()`로 단언한다.
- 테스트는 클래스 단위로 좁혀 돌린다(`./gradlew :trading-core:test --tests '<FQCN>'`). 전체 스위트는 Task 4에서 1회만 돌린다.
- 커밋 메시지는 한글 Conventional Commit이다. author는 `narafu <narafu@kakao.com>`이다. push하지 않는다.

## Review Focus

1. 미실현 손익이 음수인 KRW 사이클 — HALF_UP 반올림 후에도 `realized + unrealized = 총손익`이 맞아야 한다. Task 1 테스트 `krwSummary_negativeUnrealized_keepsSumInvariant`가 잠근다.
2. 진행 중 사이클인데 스냅샷이 없어 `endAmount`가 null인 경우 — cycles 응답에서 NPE 없이 `endAmount`·`pnl`이 null이어야 한다. Task 1 테스트 `krwCycles_openWithoutSnapshot_keepsNullAmounts`가 잠근다.
3. USD 경로 회귀 — 기존 `TradingStatsServiceTest` USD 테스트가 수정 없이 통과해야 한다. scale이 2로 유지되는지는 Task 1 `usdSummary_keepsScale2`가 잠근다.
4. 원장이 없는 전략 상세(`ledgerDeposit` null, `initialDeposit` null) — NPE 없이 null이어야 한다. Task 3 테스트 `krwDetail_withoutPosition_keepsNullDeposits`가 잠근다.
5. HOLDING_PERCENT DCA 규칙(`amount` null) — 반올림을 건너뛰고 `percent`는 그대로여야 한다. Task 3 테스트 `holdingPercentRule_keepsPercentUntouched`가 잠근다.

---

### Task 1: stats summary·equity-curve·cycles 금액을 통화 scale로 반올림

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/TradingStatsService.java` (`sum()` `:427-429`, `toTypeStats` `:268-311`, summary 합계 `:163-166`, `buildPoints` `:387-388`, `toPerformance` `:410-425`)
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/TradingStatsServiceTest.java`
- Docs: `docs/agents/modules/tradingcore/stats.md` `:10`, `:24`

**Interfaces:**
- Consumes: `Currency.round(BigDecimal)` (`shared/.../kernel/Currency.java:26`), `CycleView.currency()` (이미 존재, `loadCycles`가 채움)
- Produces: 없음(응답 값만 바뀜)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`:680` 부근의 통화별 합산 픽스처(UPBIT 계좌)를 재사용하거나 같은 방식으로 KRW 픽스처를 만든다. 소수가 있는 원장 값을 넣는다(예: 종료 사이클 openAmount `1000.40`·endAmount `1100.75`, 진행 중 사이클 최신 포지션 총자산 `2000.60`·원금 `1500.20`).

```java
@Test
void krwSummary_roundsAmountsToScale0_ratiosUnchanged() {
    StatsSummary s = statsService.getSummary(USER_ID, Currency.KRW);
    StrategyTypeStats dca = /* byType DCA */;
    assertThat(dca.realizedPnl().scale()).isZero();
    assertThat(dca.unrealizedPnl().scale()).isZero();
    assertThat(s.totalRealizedPnl().scale()).isZero();
    assertThat(s.totalUnrealizedPnl().scale()).isZero();
    assertThat(s.activePrincipal().scale()).isZero();
    // 비율은 반올림 전 실현손익(100.35)/원금(1000.40)으로 계산된 값과 같다
    assertThat(dca.avgReturnRate()).isEqualByComparingTo(
            new BigDecimal("100.35").divide(new BigDecimal("1000.40"), 6, HALF_UP).setScale(4, HALF_UP));
}

@Test
void krwSummary_negativeUnrealized_keepsSumInvariant() {
    // 진행 중 사이클 평가손 (예: 총자산 999.45, 원금 1000.20)
    StrategyTypeStats dca = /* byType DCA */;
    // 총손익(closed 실현 + active 미실현)을 픽스처 값으로 HALF_UP 0자리 계산한 기대값
    assertThat(dca.realizedPnl().add(dca.unrealizedPnl())).isEqualByComparingTo(expectedTotalPnlRounded);
    assertThat(s.totalRealizedPnl()).isEqualByComparingTo(/* Σ byType realized */);
}

@Test
void krwEquityCurve_pointsScale0() {
    EquityCurve c = statsService.getEquityCurve(USER_ID, Currency.KRW, null, null, today);
    assertThat(c.points()).allSatisfy(p -> {
        assertThat(p.totalAsset().scale()).isZero();
        assertThat(p.principal().scale()).isZero();
    });
}

@Test
void krwCycles_amountsScale0_pnlIsDifferenceOfRounded_returnRateFromRaw() {
    CyclePerformance p = /* KRW 종료 사이클 항목 */;
    assertThat(p.startAmount()).hasToString("1000");   // 1000.40
    assertThat(p.endAmount()).hasToString("1101");     // 1100.75
    assertThat(p.pnl()).hasToString("101");            // 1101 − 1000
    assertThat(p.returnRate()).isEqualByComparingTo(
            new BigDecimal("100.35").divide(new BigDecimal("1000.40"), 4, HALF_UP));
}

@Test
void krwCycles_openWithoutSnapshot_keepsNullAmounts() {
    // 진행 중 KRW 사이클, 스냅샷 없음
    assertThat(p.endAmount()).isNull();
    assertThat(p.pnl()).isNull();
    assertThat(p.startAmount().scale()).isZero();
}

@Test
void usdSummary_keepsScale2() {
    // 기존 USD 픽스처
    assertThat(statsService.getSummary(USER_ID, Currency.USD).totalRealizedPnl().scale()).isEqualTo(2);
}
```

`CyclePerformance` 필드명은 도메인 기준이다(`principal`이면 그 이름으로 단언한다. 응답 DTO에서는 `startAmount`로 나간다).

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.TradingStatsServiceTest' 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: KRW 테스트들이 FAIL(scale 2)하고 `usdSummary_keepsScale2`는 PASS한다.

- [ ] **Step 3: 구현한다**

- `private static BigDecimal sum(Stream<BigDecimal> stream, Currency currency)`로 바꾼다. 합계를 `currency.round(...)`로 반올림한다. 모든 호출부에 요청 통화를 넘긴다(`getSummary`의 합계 3개와 `toTypeStats`).
- `toTypeStats`에 `Currency currency` 파라미터를 추가한다. `:279`의 `setScale(LEDGER_SCALE)`를 `currency.round(...)`로 바꾼다. 승률·평균 수익률 계산(`:286-306`)은 바꾸지 않는다.
- `buildPoints`에 `Currency currency` 파라미터를 추가한다. `:387-388`을 `currency.round(asset)`·`currency.round(principal)`로 바꾼다.
- `toPerformance`: `returnRate`는 지금처럼 반올림 전 `pnl`로 먼저 계산한다. 그다음 `Currency c = v.currency()`로 `principal`·`endAmount`를 반올림하고(null은 유지), `pnl`은 반올림된 `endAmount − principal`로 다시 계산해 결과에 넣는다.
- `AccountBalance` import가 고아가 되면 지운다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: Step 2와 같은 명령
Expected: `BUILD SUCCESSFUL`, 기존 USD 테스트 포함 전부 PASS

- [ ] **Step 5: 변이 확인**

`toPerformance`의 `pnl` 재계산을 잠시 되돌리고(`endAmount.subtract(principal)` raw) `krwCycles_amountsScale0_pnlIsDifferenceOfRounded_returnRateFromRaw`가 실패하는지 본다. 확인한 뒤 원복한다.

- [ ] **Step 6: stats.md를 고친다**

- `:10`: `totalAssetWithAvgPriceFallback()`(scale=2) 서술 뒤에 "응답 금액(summary·equity-curve·cycles)은 요청·사이클 통화의 `Currency.round()`로 조립 시점에 반올림한다(KRW 정수). 비율은 반올림 전 값으로 계산한다"를 덧붙인다.
- `:24` CQ-70 "곡선 마지막 손익 = 같은 범위 summary 총손익" 뒤에 "(KRW는 점마다 자산·원금을 따로 반올림해 최대 1원 차이)"를 덧붙인다.

- [ ] **Step 7: 커밋한다**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/stats/application/service/TradingStatsService.java \
        trading-core/src/test/java/com/kista/tradingcore/stats/application/service/TradingStatsServiceTest.java \
        docs/agents/modules/tradingcore/stats.md
git commit -m "fix(stats): summary·equity-curve·cycles 응답 금액을 통화 자릿수로 반올림한다"
```

---

### Task 2: 계좌·전략 거래 이력의 예수금을 계좌 통화로 반올림

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/CyclePositionHistoryEntry.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/AccountStatisticsService.java:99-118` (`getByAccount`, `toPage`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/support/StrategyHistoryQueryService.java:95-108` (`getByStrategy`)
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/CyclePositionHistoryEntryTest.java` (없으면 생성), `.../stats/application/service/AccountStatisticsServiceTest.java`, `StrategyHistoryQueryService` 테스트(`StrategyServiceTest`가 `getByStrategy`를 다루면 거기에 추가)

**Interfaces:**
- Produces: `public CyclePositionHistoryEntry roundedTo(Currency currency)` — `deposit`만 `currency.round(deposit)`로 바꾼 사본을 돌려준다. 나머지 필드는 그대로다.
- Consumes: `Account account = accountPort.requireOwnedAccount(...)`(반환값을 지금 버리고 있다), `OwnedStrategy.load(...).account()`. 통화는 `account.broker().getCurrency()`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
@Test
void roundedTo_krw_roundsDepositOnly() {
    var e = new CyclePositionHistoryEntry(id, accountId, StrategyTicker.BTC /* 코인 티커 */, StrategyType.DCA,
            new BigDecimal("12345.67"), new BigDecimal("0.55"), new BigDecimal("0.53"), new BigDecimal("10"), now);
    var r = e.roundedTo(Currency.KRW);
    assertThat(r.deposit()).hasToString("12346");
    assertThat(r.closingPrice()).hasToString("0.55");
    assertThat(r.avgPrice()).hasToString("0.53");
    assertThat(r.holdings()).isEqualByComparingTo("10");
}

@Test
void roundedTo_usd_keepsScale2() {
    assertThat(entryWithDeposit("100.50").roundedTo(Currency.USD).deposit()).hasToString("100.50");
}
```

`AccountStatisticsServiceTest`: UPBIT 계좌로 `getByAccount`를 부르면 `items().getFirst().deposit()`이 `hasToString("12346")`이다.
`getByStrategy`: UPBIT 계좌 전략이면 `deposit`이 `hasToString("12346")`이다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntryTest' --tests 'com.kista.tradingcore.stats.application.service.AccountStatisticsServiceTest' --tests '<getByStrategy 테스트 FQCN>' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: `roundedTo` 컴파일 에러 또는 FAIL

- [ ] **Step 3: 구현한다**

- `CyclePositionHistoryEntry.roundedTo(Currency)`를 추가한다(`// 응답용 — 예수금만 계좌 통화 자릿수로 반올림`).
- `AccountStatisticsService.getByAccount`: `requireOwnedAccount` 반환값에서 통화를 얻는다. `raw`를 `roundedTo(currency)`로 매핑한 뒤 `toPage`에 넘긴다. `toPage`의 `size + 1` hasMore 판정은 그대로다.
- `StrategyHistoryQueryService.getByStrategy`: `OwnedStrategy.load(...)` 결과의 `account()`로 같은 처리를 한다.
- 두 `CycleHistoryResponse`(stats·trading)는 바꾸지 않는다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: Step 2와 같은 명령
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋한다**

```bash
git commit -am "fix(trading-core): 거래 이력 응답의 예수금을 계좌 통화 자릿수로 반올림한다"
```
(새 테스트 파일이 있으면 먼저 `git add`한다.)

---

### Task 3: 전략 상세 예수금과 DCA 정액 금액을 계좌 통화로 반올림

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyDetailAssembler.java` (`toDetail` `:39`, `toDetails` `:86`, `assemble` `:161-185`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaSummaryAssembler.java:37-47` (`assembleRule`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaRule.java` (`withRuleKey` `:105` 옆)
- Test: `.../trading/application/service/DcaSummaryAssemblerTest.java`, `StrategyDetailAssembler` 테스트(`StrategyServiceTest`가 상세 조립을 검증하면 거기에, 없으면 `StrategyDetailAssemblerTest` 생성)

**Interfaces:**
- Produces: `public DcaRule withSizing(DcaSizing sizing)` — sizing만 바꾼 사본.
- Consumes: `toDetail`의 `Market market`, `toDetails`의 `marketByAccountId.get(strategy.accountId())`. 통화는 `market.quoteCurrency()`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
// DcaSummaryAssemblerTest
@Test
void cryptoKrwRule_amountScale0() {
    DcaRule rule = /* AMOUNT 규칙, amount new BigDecimal("10000.00") */;
    var s = assembler.assembleRule(rule, null, today, Market.KRW_CRYPTO /* UPBIT 시장 상수 */);
    assertThat(s.rule().sizing().amount()).hasToString("10000");
}

@Test
void holdingPercentRule_keepsPercentUntouched() {
    DcaRule rule = /* HOLDING_PERCENT, percent new BigDecimal("50.00") */;
    var s = assembler.assembleRule(rule, null, today, /* KRW 코인 시장 */);
    assertThat(s.rule().sizing().amount()).isNull();
    assertThat(s.rule().sizing().percent()).hasToString("50.00");
}

@Test
void usdRule_keepsScale2() {
    // 주식 시장(USD), amount "100.00" → "100.00"
}

// StrategyDetailAssembler
@Test
void krwDetail_roundsDeposits() {
    // UPBIT 시장 DCA 전략, 최신 사이클 startAmount 50000.00, 최신 포지션 deposit 12345.67
    assertThat(detail.initialDeposit()).hasToString("50000");
    assertThat(detail.ledgerDeposit()).hasToString("12346");
}

@Test
void krwDetail_withoutPosition_keepsNullDeposits() {
    // 사이클·포지션 없음
    assertThat(detail.initialDeposit()).isNull();
    assertThat(detail.ledgerDeposit()).isNull();
}
```

시장 상수 이름은 `Market` enum에서 KRW 견적 시장을 골라 쓴다.

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.DcaSummaryAssemblerTest' --tests '<상세 조립 테스트 FQCN>' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: KRW 테스트 FAIL

- [ ] **Step 3: 구현한다**

- `DcaRule.withSizing(DcaSizing)`을 추가한다(`withRuleKey`와 같은 모양).
- `DcaSummaryAssembler.assembleRule`: 시작 부분에서 AMOUNT 규칙이면 `rule = rule.withSizing(new DcaSizing(type, market.quoteCurrency().round(amount), null))`으로 바꾼다. `amount`가 null이면(HOLDING_PERCENT) 그대로 둔다. 이후 분기는 바꾸지 않는다. 계산용 규칙이 아니라 조회 요약 경로임을 한 줄 주석으로 남긴다.
- `StrategyDetailAssembler.assemble`에 `Currency currency` 파라미터를 추가한다. `toDetail`은 `market.quoteCurrency()`, `toDetails`는 `marketByAccountId.get(strategy.accountId()).quoteCurrency()`를 넘긴다. `initialDeposit`과 `ledgerDeposit`(`latestPosition.map(CyclePosition::deposit)`)에 `.map(currency::round)`을 적용한다. VR 분기의 `openingPosition.map(CyclePosition::deposit)`도 같다. `currentRound` 계산 입력(`pos.deposit()`)은 바꾸지 않는다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: Step 2와 같은 명령, 그리고 `--tests 'com.kista.tradingcore.trading.adapter.in.web.TradingCycleControllerTest'`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋한다**

```bash
git commit -am "fix(trading): 전략 상세 예수금과 DCA 정액 금액을 계좌 통화 자릿수로 반올림한다"
```

---

### Task 4: constraints.md 규칙 갱신, 전체 검증, kista-ui 알림

**Files:**
- Modify: `docs/agents/constraints.md` "Deprecated API·클래스 식별 문자열·persistence 기본값" 절의 USD 금액 반올림 항목

- [ ] **Step 1: constraints.md에 한 문장을 덧붙인다**

해당 항목 끝에 붙인다: "원장 금액이 응답으로 나갈 때는 결과를 조립하는 서비스에서 그 통화의 `Currency.round()`로 반올림한다(KRW 정수 — `TradingStatsService`·`StrategyDetailAssembler`·`AccountLedgerTotalsService`). 비율은 반올림 전 값으로 계산하고, 가격·수량은 대상이 아니다."

- [ ] **Step 2: 드리프트 확인**

Run: `grep -n "LEDGER_SCALE\|scale=2\|소수 2자리" docs/agents/modules/tradingcore/*.md src/test/resources/architecture/flows.yml README.md`
Expected: 응답 금액 자릿수를 "2자리"로 단정하는 서술이 남아 있으면 고친다. flows.yml·README는 변경이 없어야 한다.

- [ ] **Step 3: 전체 테스트 1회**

Run: `./gradlew :trading-core:test 2>&1 | grep -E "FAILED|BUILD"` 그다음 `./gradlew :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL` 두 번

- [ ] **Step 4: 리뷰어 검수**

sonnet 리뷰어 1명이 `git diff <설계 커밋>..HEAD`를 Global Constraints와 Review Focus 기준으로 검토한다. 결함이 있으면 고치고 해당 클래스 테스트를 재실행한다.

- [ ] **Step 5: 커밋한다**

```bash
git commit -am "docs: 응답 금액의 통화별 반올림 규칙을 constraints에 추가한다"
```

- [ ] **Step 6: kista-ui 알림을 쓴다(커밋 아님, 사용자에게 전달)**

"trading-core 응답의 KRW 금액(stats summary·equity-curve·cycles, cycle-history, 전략 이력, 전략 상세 initialDeposit·ledgerDeposit, DCA rule sizing.amount)이 소수 2자리에서 정수로 바뀌었다. 필드 이름과 타입(number)은 같다. `fmtMoney`가 이미 KRW를 정수로 표시하므로 UI 변경은 필요 없다. KRW 금액을 문자열로 비교하거나 `.toFixed(2)`를 쓰는 곳이 있으면 확인만 한다."
