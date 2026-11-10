# PRIVACY 배수 동적 산정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PRIVACY 배수를 `start_amount ÷ currentCycleStart`(사이클 고정)에서 `우리 자산 ÷ FIDA 자산`(전일 종가 평가, 매일 재산출)으로 바꾼다.

**Architecture:** 커널(`matching`)이 평가가 `P`와 FIDA 평단·실현손익을 입력으로 받아 배수를 산출한다. 자산이 무효하면 `PrivacyValuationException`을 던진다. 운영(`CycleOrderComputer`)은 최신 `cycle_position.closing_price`를 P로 넘기고, 백테스트는 `candle.close()`를 넘긴다. 이제 쓸모가 없어진 bootstrap 시작금액 경로는 제거한다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5, AssertJ, Mockito

**Spec:** `docs/superpowers/specs/archive/2026-11-11-privacy-dynamic-multiple-design.md`

## Global Constraints

- 배수 = `ourAsset ÷ fidaAsset`, `setScale(2, RoundingMode.FLOOR)`
  - `ourAsset = balance.deposit() + balance.holdings() × P`
  - `fidaAsset = (currentCycleStart + realizedPnl − fidaHoldings × avgPrice) + fidaHoldings × P`. FIDA 보유가 0이고 평단이 null이면 `fidaHoldings × avgPrice` 항은 0이다.
- 무효(→ `PrivacyValuationException`) 조건:
  - `fidaAsset ≤ 0`
  - `ourAsset ≤ 0`
  - FIDA 보유 > 0인데 `avgPrice == null`
  - P가 필요한데(우리 또는 FIDA 보유 > 0) `P == null`
- BUY 가감·SELL cap·버림 보정 로직은 바꾸지 않는다(`PrivacyStrategy` 62행 이후).
- 매매 공식 SSOT(`trading-formulas.md`) 변경은 2026-11-11 사용자 승인 사항이다.
- 주석: 신규 코드에 `//` 한 줄 주석. Javadoc 금지. IAE/ISE 메시지는 "~니다."로 끝난다(`ExceptionMessageFormatTest`).
- 빌드·테스트 명령은 서브프로젝트를 지정하고 `2>&1 | grep -E "FAILED|BUILD|ERROR|tests completed"`로 거른다.
- 서브에이전트는 git add/commit을 하지 않는다. 커밋은 컨트롤러가 리뷰 후 한다.

## Review Focus

1. 등록 직후(보유 0, `initialSnapshot` closing_price null)에 FIDA 보유 > 0인 경우: P를 `prevClosePrice`로 폴백해야 한다. 둘 다 null이면 예외가 나야 한다. → Task 2 테스트
2. 미리보기 경로가 `PrivacyValuationException`으로 500을 내지 않고 `PRIVACY_BASE_BLOCKED`를 내야 한다. → Task 2 테스트
3. 백테스트에서 무효 자산 세션은 주문 없이 경고만 남기고 다음 세션으로 계속 진행해야 한다. → Task 3 테스트
4. FIDA 보유 0이고 평단 null인 정상 케이스(사이클 시작 직후)에서는 예외가 나면 안 된다. → Task 1 테스트
5. 추가 투입(`CapitalLedgerService`)으로 최신 행 예수금이 늘면 다음 계산 배수가 커져야 한다. 구조상 자동이므로 Task 2에서 최신 행을 재조회하는지만 확인한다.

---

### Task 1: 커널 — 자산비 배수 산출

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/PrivacyValuationException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/PrivacyPlan.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/PrivacyStrategy.java:27-34`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/PrivacyCycleOrderStrategy.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/CycleOrderStrategy.java:136-144` (`PrivacyInputs`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/privacy/domain/model/PrivacyTradeBase.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapter.java:153`
- Modify (컴파일 유지용 최소 변경만): `CycleOrderComputer.java:58-60,81-83`, `BacktestEngine.java:340-342`. 의미 있는 변경은 Task 2·3에서 한다.
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/PrivacyStrategyTest.java`, 그리고 `new PrivacyTradeBase(`/`new PrivacyPlan(`을 쓰는 테스트 전부(`grep -rl` 결과 약 12개)

**Interfaces:**
- Produces:
  - `PrivacyPlan(int holdings, BigDecimal avgPrice /*nullable*/, BigDecimal realizedPnl, BigDecimal currentCycleStart, List<PrivacyPlannedTrade> trades)`
    - 기존 `currentCycleStart > 0` 가드를 유지한다.
    - `realizedPnl == null`이면 ISE를 던진다.
  - `public final class PrivacyValuationException extends IllegalStateException` (생성자 `(String message)`)
  - `public static BigDecimal PrivacyStrategy.multiple(AccountBalance balance, BigDecimal price, PrivacyPlan plan)`
    - Global Constraints 공식대로 산출한다.
    - 무효 조건이면 `PrivacyValuationException`을 던진다.
  - `public List<PlannedOrder> buildOrders(AccountBalance balance, BigDecimal price, PrivacyPlan plan)` — 첫 줄에서 `multiple(...)`을 호출한다.
  - `PlanContext.PrivacyInputs(BigDecimal valuationPrice, PrivacyPlan privacyPlan, BigDecimal currentPrice)` — `initialDeposit` 자리를 대체한다.
  - `PrivacyCycleOrderStrategy.requiresPrevClose()`가 `true`를 반환한다(Task 2의 미리보기 폴백용).
  - `PrivacyTradeBase(UUID id, BigDecimal avgPrice, int holdings, BigDecimal realizedPnl, BigDecimal currentCycleStart, List<PrivacyTrade> trades)`
    - `toPlan()`이 `avgPrice`와 `realizedPnl`을 전달한다.
    - 어댑터는 `entity.getCurrentCycleRealizedPnl()`을 넣는다.

- [ ] **Step 1: 회귀 테스트 작성** (`PrivacyStrategyTest`)

```java
@Test
void 운영_2026_11_11_사례_자산비_배수_4_86_BUY_45() {
    AccountBalance balance = new AccountBalance(new BigDecimal("552"), new BigDecimal("50.00"), new BigDecimal("2791.71"));
    PrivacyPlan plan = new PrivacyPlan(112, new BigDecimal("102.09"), new BigDecimal("-2339.37"), new BigDecimal("14434.58"),
            List.of(buy("55.14", 11), sell("57.90", 6), sell("65.43", 6), sell("76.96", 11)));
    assertThat(PrivacyStrategy.multiple(balance, new BigDecimal("56.20"), plan)).isEqualByComparingTo("4.86");
    List<PlannedOrder> orders = strategy.buildOrders(balance, new BigDecimal("56.20"), plan);
    assertThat(buyQty(orders, "55.14")).isEqualTo(45);
}
```

`buy`·`sell`·`buyQty`가 없으면 기존 테스트 헬퍼에 맞춰 만든다. 주문 유형은 LIMIT, 거래일은 아무 날이나 쓴다. avgPrice 50.00은 계산에 쓰이지 않는다.

예외 테스트 4건을 추가한다. 모두 `assertThatThrownBy(...).isInstanceOf(PrivacyValuationException.class)`로 단언한다.
- `FIDA_자산_0이하면_예외`: currentCycleStart 100, realizedPnl −100, FIDA 보유 0, avgPrice null
- `FIDA_보유있는데_평단없으면_예외`
- `우리_자산_0이하면_예외`: deposit 0, holdings 0
- `보유있는데_평가가_null이면_예외`

정상 케이스 1건을 추가한다.
- `FIDA_보유0_평단null_정상`: 예외 없음. 배수 = (deposit + holdings×P) ÷ (currentCycleStart + realizedPnl)

- [ ] **Step 2: 기존 테스트 전환**

`PrivacyStrategyTest`의 기존 28개 테스트를 새 시그니처로 바꾼다. 원래 배수(`1000 ÷ currentCycleStart`)를 그대로 재현하도록 입력을 정한다. FIDA 보유 0, avgPrice null, realizedPnl 0으로 두면 fidaAsset은 currentCycleStart가 된다. 그러면 `deposit + holdings×P = 1000`이 되게 P를 고르면 된다(예: 보유 h주면 P = (1000 − deposit) ÷ h). 계산이 안 맞는 테스트는 기대 수량을 유지하는 입력을 찾되, 기대값 자체는 바꾸지 않는다. FIDA 보유를 0으로 두면 BUY 가감 target이 0이 되는 부작용이 있다. 그런 테스트는 `plan.holdings`를 유지해야 하므로, FIDA 보유와 평단을 함께 정해 fidaAsset을 맞춘다. 예: 평단 = P이면 `fidaAsset = currentCycleStart + realizedPnl`이 된다.

- [ ] **Step 3: 실패 확인** — `./gradlew :trading-core:compileTestJava 2>&1 | grep -E "error|BUILD"`. 컴파일 오류가 나야 한다.

- [ ] **Step 4: 구현** — Interfaces 그대로 구현한다.
  - `multiple` 로그: `log.info("[PRIVACY] 배수 산출: ourAsset={}, fidaAsset={}, price={}, multiple={}", ...)`
  - 예외 메시지 예: `"PRIVACY 배수 산출 불가 — FIDA 자산이 0 이하입니다."`
  - `CycleOrderComputer`와 `BacktestEngine`은 일단 `prevClosePrice`와 `refClose`를 `valuationPrice` 자리에 넘겨 컴파일만 맞춘다. 실제 출처는 Task 2·3에서 정한다.

- [ ] **Step 5: 통과 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.*' --tests 'com.kista.tradingcore.privacy.*' 2>&1 | grep -E "FAILED|BUILD|tests completed"`. BUILD SUCCESSFUL이 나와야 한다.

---

### Task 2: 운영 경로 — 평가가 출처와 미리보기 skip

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/CycleOrderComputer.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyOrderPlanBuilder.java:85-91`
- Test: `CycleOrderComputerTest` (없으면 신설), `StrategyOrderPlanBuilderTest`

**Interfaces:**
- Consumes: Task 1의 `PrivacyInputs(valuationPrice, …)`, `PrivacyValuationException`
- Produces: 없음(내부 변경)

- [ ] **Step 1: 테스트 작성**
  - `CycleOrderComputerTest`
    - `PRIVACY_평가가는_최신_cycle_position_종가`: `cyclePositionPort.findLatestOne(cycleId)`가 closingPrice 56.20인 행을 반환하고 prevClosePrice가 99이면, 전략에 전달된 `ctx.privacy().valuationPrice()`가 56.20이다. `CycleOrderStrategies`를 mock하고 `ArgumentCaptor<PlanContext>`로 확인한다.
    - `PRIVACY_종가_null이면_prevClose_폴백`: closingPrice null, prevClosePrice 55.00이면 결과는 55.00이다.
  - `StrategyOrderPlanBuilderTest`
    - `PRIVACY_자산_무효면_PRIVACY_BASE_BLOCKED`: `orderComputer.compute`가 `PrivacyValuationException`을 던지면 `PlanResult.skipReason() == PRIVACY_BASE_BLOCKED`이다.

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CycleOrderComputerTest' --tests '*StrategyOrderPlanBuilderTest' 2>&1 | grep -E "FAILED|BUILD"`

- [ ] **Step 3: 구현**
  - `CycleOrderComputer`
    - `CyclePositionPort`를 주입한다.
    - PRIVACY일 때만 `valuationPrice = cyclePositionPort.findLatestOne(currentCycle.id()).map(CyclePosition::closingPrice).orElse(null)`로 구하고, null이면 `prevClosePrice`를 쓴다. 기존 `initialDeposit` 변수와 그 주석(44행)은 제거한다.
    - `findLatestOne`의 반환 타입을 확인한다(`CapitalLedgerService:207`에서 쓰는 형태).
  - `StrategyOrderPlanBuilder`
    - `compute` 호출을 `try { … } catch (PrivacyValuationException e) { log.warn(...); return new PlanResult(null, SkipReason.PRIVACY_BASE_BLOCKED); }`로 감싼다.
  - 배치(`TradingCandidatePlanner`)는 변경하지 않는다. `TradingBatchGuard.runSafely`가 예외를 전략 단위로 격리하고 관리자 알림을 보낸다.

- [ ] **Step 4: 통과 확인** — Step 2 명령과 `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' 2>&1 | grep -E "FAILED|BUILD|tests completed"`

---

### Task 3: 백테스트와 bootstrap 시작금액 제거

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/backtest/BacktestEngine.java` (76행 `privacyStartAmount` 파라미터, 293-306 `runPrivacy`, 334-346 `planPrivacyOrders`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/BacktestService.java:185-200`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java:67,119-121`
- Delete: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyStartAmountCalculator.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/privacy/domain/model/PrivacyCurrentBase.java`
  - `bootstrapStartAmount`를 삭제한다.
  - `currentCycleRealizedPnl`·`avgPrice` 필드는 다른 소비처가 없으면 삭제한다(grep으로 확인).
- Modify: `PrivacyTradePort.java:18-19`, `PrivacyTradePersistenceAdapter.java:124-138`. `findSeedBaseFrom`의 소비처가 사라지면 port에서 제거하고, `findSeedPreviewBase`는 내부 구현으로 흡수한다.
- Test: `BacktestEngineTest`, `BacktestGoldenTest`, `BacktestServiceTest`, `StrategyServiceTest:161`, `StrategySeedPreviewServiceTest`, `PrivacyTradeBaseTest`

**Interfaces:**
- Consumes: Task 1의 `PrivacyInputs`, `PrivacyValuationException`
- Produces: `BacktestEngine` 공개 진입 메서드에서 `privacyStartAmount` 파라미터를 제거한다.

- [ ] **Step 1: 테스트 작성** (`BacktestEngineTest`)
  - `PRIVACY_자산_무효_세션은_주문없이_경고_후_계속`: 무효 base를 하루 끼우면 그날은 주문 0건이고, warnings에 그 세션 날짜가 들어가며, 다음 정상 세션에는 주문이 생긴다.
  - `PRIVACY_배수는_전일종가_자산비`: 시드 10000, 보유 0, FIDA currentCycleStart 5000, realizedPnl 0, FIDA 보유 0이면 첫 주문 세션의 배수는 2.00이다. BUY 수량이 FIDA 수량 × 2로 나오는지 확인한다.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*BacktestEngineTest' 2>&1 | grep -E "FAILED|BUILD"`
- [ ] **Step 3: 구현**
  - `planPrivacyOrders`
    - `PrivacyInputs(refClose, base.toPlan(), refClose)`로 조립한다.
    - `plan(ctx)` 호출을 `try/catch (PrivacyValuationException e)`로 감싼다. 예외가 나면 `warnings.add("%s PRIVACY 배수 산출 불가 — 주문 없음: %s".formatted(nextSession, e.getMessage()))`를 남기고 `List.of()`를 반환한다.
    - 334행 배수 주석을 새 공식으로 고친다.
  - `runPrivacy`: `privacyStartAmount` 분기를 삭제한다. `cycleStartAmount`는 원금 그대로 둔다(재시작 시드용).
  - `BacktestService`: bootstrap 산출 메서드와 그 호출을 삭제한다.
  - `StrategyCreationService`
    - 계산기 필드를 삭제한다.
    - `BigDecimal openAmount = initialDeposit.add(initialStockValue);` 하나로 두고 `startAmount` 자리에 `openAmount`를 쓴다.
    - 주석 "PRIVACY 중간 시작 배수 환산" 문구를 제거한다.
- [ ] **Step 4: 기존 테스트 정리**
  - bootstrap 기대 테스트는 삭제한다. 등록 테스트는 `startAmount == openAmount`로 바꾼다.
  - `BacktestGoldenTest`의 PRIVACY 기대값이 바뀌면 재생성한다. 대표 1세션의 배수·BUY 수량을 손 계산으로 대조하고, 그 계산을 테스트 주석 한 줄로 남긴다.
- [ ] **Step 5: 통과 확인** — `./gradlew :trading-core:test 2>&1 | grep -E "FAILED|BUILD|tests completed"`, `./gradlew :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"` (root ArchUnit이 trading-core도 검사한다)

---

### Task 4: 문서·아키텍처 맵·마무리

**Files:**
- Modify: `docs/agents/modules/tradingcore/trading-formulas.md:73-76`
- Modify: `docs/agents/modules/tradingcore/privacy.md:4,20`
- Modify: `src/test/resources/architecture/flows.yml` (배치·미리보기 PRIVACY 단계 `desc`/`fail`, 873-885 자본 투입의 start_amount ↔ 배수 서술)
- Modify: `docs/agents/code-quality-backlog.md` (`StrategyStartAmountCalculator`·`PrivacyStrategy`·`BacktestEngine` grep 결과 항목만)
- Move: 계획과 spec을 `plans/archive/`·`specs/archive/`로 옮긴다. 배포 후 운영 확인 단계(Step 3)가 끝난 뒤 하는 마지막 커밋에서 한다.
- Delete memory (구현 완료 시, 컨트롤러가 직접): `/Users/phs/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/project_privacy_dynamic_multiple.md`와 `MEMORY.md`의 해당 줄

- [ ] **Step 1: 문서 갱신**
  - `trading-formulas.md`
    - PRIVACY 배수 절을 Global Constraints 공식으로 교체한다.
    - bootstrap 문단을 삭제한다.
    - "2026-11-11 사용자 승인으로 고정 배수(start_amount ÷ currentCycleStart)에서 자산비 배수로 변경" 한 줄을 추가한다.
    - 검증 사례(배수 4.86, BUY 45)를 예시로 넣는다.
- [ ] **Step 2: 확인** — `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`, 이어서 전체 `./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"` 1회
- [ ] **Step 3: 운영 확인(배포 후, 사용자 push·배포 요청 시)** — 다음 거래일 미리보기에서 대상 전략 BUY 수량이 공식 결과와 같은지 확인한다(읽기 전용). 그다음 archive 이동을 한다.
