# INFINITE 보정 주문·cap 점검 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** INFINITE 커널에 승인된 공식 변경 3건(보정 상시 부착·보정 가격 FLOOR·매수② 0주 규칙)을 반영하고, SSOT 문서를 코드와 결정 경위에 맞춘다.

**Architecture:** 변경은 순수 계산 커널 `com.kista.tradingcore.matching`(`InfiniteStrategy`, `InfinitePosition`) 안에서 끝난다. 실행 경로(`BuyOrderPriceCapper`, `StrategyLedgerBudget`, `BacktestEngine`)는 커널 출력을 그대로 소비하므로 코드 변경 없이 테스트 기대값만 따라 바뀐다.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Gradle `:trading-core`

**Spec:** `docs/superpowers/specs/2026-10-25-infinite-correction-cap-review-design.md`

## Global Constraints

- 매매 공식 SSOT: `docs/agents/modules/tradingcore/trading-formulas.md` — 이 계획의 공식 변경은 사용자 승인 완료(2026-10-25). 계획 밖 공식 변경 금지.
- 보정 주문: base BUY 다음에 3회, 가격 = `unitAmount / (누적수량 + 1)` **scale 2 FLOOR**, 수량 1, `LOC`, leg `INFINITE_CORRECTION_01~03`, 누적수량 0이면 skip.
- base 주문(매수①②·병합·후반 LOC)의 가격·수량은 원장 맞춤으로 줄이지 않는다. 원장 초과 시 축소 수단은 `InfiniteStrategy.trimCorrectionsToBudget`(뒤쪽 보정부터 제거)뿐.
- cap 배수 `1.05` 값 변경 금지.
- 주석은 `//` 인라인만(Javadoc·블록 주석 금지), 4-space 들여쓰기.
- 테스트는 서브프로젝트 지정 필수: `./gradlew :trading-core:test --tests '<pattern>'`. 출력은 `2>&1 | grep -E "FAILED|tests completed|BUILD"`로 거른다.
- 커밋: author `narafu <narafu@kakao.com>`, 한글 Conventional Commit, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **보고 의무(사용자 요구)**: 기대값이 바뀐 기존 테스트는 클래스·테스트명별로 "이전 → 새 값, 이유(spec §2.x)"로 보고한다. `InfinitePositionTest`·`InfiniteStrategyTypeTest`·`BacktestEngineTest`는 바뀌지 않았어도 "변화 없음"을 명시한다.

## Review Focus

1. 전반 매수② 0주인 날에 cap이 발동 — base가 1건이라 후반 공식으로 빠지면 안 된다(Task 1 테스트 `capped_earlyStageWithoutBuy2_staysOnEarlyFormula`).
2. 소액 원장에서 보정이 base와 합쳐 원장을 넘음 — 보정만 뒤에서부터 빠지고 base는 남아야 한다(Task 2 테스트 `buildOrders_zeroRound_smallDeposit_trimsAllCorrections`).
3. cap 재진입(입력에 이미 보정 3건 포함) — 이제 cap 미발동일에도 보정이 PLANNED로 저장되므로 접수 직전 재캡 입력에 보정이 늘 섞인다. 기존 `buildCappedBuyOrders_reentrantWithExistingCorrectionLegs_ignoresThemAndRecalculatesBaseOnly`가 계속 통과해야 한다(Task 2 Step 4에서 확인).
4. 같은 날 재실행 — 보정 상시 저장으로 `InfiniteCycleOrderStrategy.canSkipOrderComputation`의 `correctionComplete`가 평일에도 성립한다. 매수② 0주인 날은 `EARLY_REF`가 없어 재계산되지만 `excludeExisting`이 중복을 막는다. 이 메서드는 수정하지 않는다(spec §4 재량 → 현행 유지로 결정). 기존 canSkip 테스트 통과로 확인(Task 2 Step 4).
5. 백테스트 예산 계약 — 보정이 상시 붙어도 `buildOrders` 내부 trim 덕에 "BUY 합계 > 예수금이면 그날 BUY 전부 거절"이 보정 때문에 새로 발동하지 않아야 한다. `BacktestEngineTest` 기대값 변화는 체결 증가(보정 체결)로만 설명돼야 하며, 거절일 증가로 설명되면 결함이다(Task 2 보고).

---

### Task 1: 보정 가격 FLOOR + 매수② 0주 규칙 + cap 경로 전후반 분기

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/InfinitePosition.java` (`earlyBuyQuantity2`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/InfiniteStrategy.java` (`buildCappedBuyOrders`, `computeEarlyBuys`, `addCorrectionOrder`, `buildEarlyStageOrders`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/BuyOrderPriceCapper.java:31` (주석만)
- Modify: `docs/agents/modules/tradingcore/trading-formulas.md`
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/InfinitePositionTest.java`, `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/InfiniteStrategyTypeTest.java`

**Interfaces:**
- Produces: `InfinitePosition.earlyBuyQuantity2(BigDecimal unitAmount, BigDecimal price1, int quantity, BigDecimal price2): int` — 잔여 `unitAmount − price1×quantity`가 0 이하면 `0`, 아니면 `max(1, floor(잔여/price2))`. 시그니처 불변.
- Produces: `InfiniteStrategy.addCorrectionOrder(...)` 가격 FLOOR(Task 2가 재사용).

- [ ] **Step 1: 실패 테스트 작성**

`InfinitePositionTest`:
```java
@Test
@DisplayName("earlyBuyQuantity2: 매수①이 unitAmount를 다 쓰면 0주, 잔여가 있으면 최소 1주")
void earlyBuyQuantity2_zeroWhenFirstBuyConsumesUnit() {
    assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("5.00"), new BigDecimal("60"), 1, new BigDecimal("72.00"))).isZero();
    assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("100.00"), new BigDecimal("100"), 1, new BigDecimal("120.00"))).isZero();
    assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("100.00"), new BigDecimal("60"), 1, new BigDecimal("72.00"))).isEqualTo(1);
    assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("1000.00"), new BigDecimal("50"), 10, new BigDecimal("60.00"))).isEqualTo(8);
}
```

`InfiniteStrategyTypeTest`:
```java
@Test
@DisplayName("buildOrders 0회차 매수①이 unitAmount 초과: 매수② 미생성")
void buildOrders_zeroRound_firstBuyConsumesUnit_omitsBuy2() {
    // unitAmount=100/20=5.00, 매수① max(1,0)=1주×60 ≥ 5 → 매수② 0주
    InfinitePosition position = new InfinitePosition(new AccountBalance(0, null, new BigDecimal("100")), StrategyTicker.SOXL, new BigDecimal("60"), 20);
    List<PlannedOrder> buys = strategy.buildOrders(position, TODAY).stream().filter(o -> o.direction() == BUY).toList();
    assertThat(buys).extracting(PlannedOrder::orderLeg).doesNotContain("INFINITE_EARLY_REF_BUY");
    assertThat(buys.getFirst()).matches(o -> o.quantity() == 1 && o.price().compareTo(new BigDecimal("60")) == 0
            && o.orderLeg().equals("INFINITE_EARLY_AVG_BUY"));
}

@Test
@DisplayName("buildCappedBuyOrders 보정 가격은 FLOOR — (N+1)×가격 ≤ unitAmount")
void buildCappedBuyOrders_correctionPriceFloors() {
    // 0회차 prevClose=50, usdDeposit=20000 → unitAmount=1000, cap=52.50(현재가 50)
    // base 50×10, 52.50×9 (N=19) → 보정 1000/20=50.00, 1000/21=47.61(HALF_UP이면 47.62), 1000/22=45.45
    InfinitePosition position = new InfinitePosition(new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("50"), 20);
    List<PlannedOrder> base = strategy.buildOrders(position, TODAY).stream()
            .filter(o -> o.direction() == BUY && !o.orderLeg().startsWith("INFINITE_CORRECTION_")).toList();
    List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, base, new BigDecimal("52.50"));
    assertThat(result).filteredOn(o -> o.orderLeg().startsWith("INFINITE_CORRECTION_"))
            .extracting(PlannedOrder::price).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("50.00"), new BigDecimal("47.61"), new BigDecimal("45.45"));
}

@Test
@DisplayName("buildCappedBuyOrders 전반 매수② 0주 + cap 발동: 후반 공식이 아닌 전반 공식으로 재산정")
void capped_earlyStageWithoutBuy2_staysOnEarlyFormula() {
    // 0회차 prevClose=100, usdDeposit=2000 → unitAmount=100, 매수① 1주×100 ≥ 100 → base 1건(매수② 0주)
    // cap=84(현재가 80): 전반 공식 cappedAvg=cappedRef=84 → q1=1, 잔여 16>0 → q2=1 → 병합 84×2
    // (후반 공식이면 floor(100/84)=1주 — 이 단언이 분기 결함을 잡는다)
    InfinitePosition position = new InfinitePosition(new AccountBalance(0, null, new BigDecimal("2000")), StrategyTicker.SOXL, new BigDecimal("100"), 20);
    List<PlannedOrder> base = List.of(PlannedOrder.of(TODAY, StrategyTicker.SOXL, LOC, BUY, 1, new BigDecimal("100"), "INFINITE_EARLY_AVG_BUY"));
    List<PlannedOrder> result = strategy.buildCappedBuyOrders(position, TODAY, base, new BigDecimal("84.00"));
    assertThat(result.getFirst()).matches(o -> o.orderLeg().equals("INFINITE_EARLY_MERGED_BUY")
            && o.quantity() == 2 && o.price().compareTo(new BigDecimal("84.00")) == 0);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*InfinitePositionTest' --tests '*InfiniteStrategyTypeTest' 2>&1 | grep -E "FAILED|tests completed|BUILD"`
Expected: 새 테스트 4건 FAILED.

- [ ] **Step 3: 구현**

- `earlyBuyQuantity2`: 위 Interfaces 규칙. 주석을 "잔여 0 이하면 0주, 아니면 최소 1주"로 갱신.
- `buildEarlyStageOrders`: 매수② 수량이 0이면 그 주문을 추가하지 않는다. 주석 "(최소 1주)"를 정정.
- `buildCappedBuyOrders`: 전후반 분기를 `baseOrders.size() == 1` 대신 `position.isEarlyStage()`로. 후반은 기존 `computeLateBuys(..., baseOrders.getFirst(), ...)`.
- `computeEarlyBuys`: base가 1건(매수②가 없던 날)이어도 동작해야 한다. `cappedAvg = 첫 base 가격.min(cap)`, `cappedRef = (매수② 있으면 그 가격, 없으면 position.referencePrice()).min(cap)`. 병합(동가)이면 `q1+q2` 1건(leg `INFINITE_EARLY_MERGED_BUY`, orderType은 첫 base). 분리면 매수①은 첫 base의 orderType·leg, 매수②는 `q2 > 0`일 때만 추가 — 원본 매수②가 없으면 `LOC`·leg `INFINITE_EARLY_REF_BUY`.
- `addCorrectionOrder`: `RoundingMode.HALF_UP` → `RoundingMode.FLOOR`.
- `BuyOrderPriceCapper.java:31` 주석: `(unitAmount-averagePrice·q1)·(1+r)/referencePrice` → `(unitAmount-averagePrice·q1)/referencePrice`.

- [ ] **Step 4: 통과 확인 + 기존 테스트 갱신**

Run: Step 2와 같은 명령. 새 테스트 통과 확인. 실패하는 기존 테스트는 바뀐 공식으로 기대값을 다시 계산해 고치고, 각각을 보고 목록에 적는다. 사전 측정상 FLOOR만으로 `buildCappedBuyOrders` 6건이 바뀐다. `buildOrders_zeroRound_smallDeposit_guaranteesMinimumOneShare`(unitAmount 1.00, 매수① 100)는 매수②가 사라져 1건이 된다 — DisplayName도 실제 동작으로 고친다.

- [ ] **Step 5: 변이 확인**

`addCorrectionOrder`를 잠시 HALF_UP으로, `earlyBuyQuantity2`를 잠시 `max(1, ...)` 원복, 분기를 잠시 `size()==1`로 되돌려 각각 `correctionPriceFloors`·`zeroWhenFirstBuyConsumesUnit`·`capped_earlyStageWithoutBuy2_staysOnEarlyFormula`가 실패하는지 확인 후 복구.

- [ ] **Step 6: SSOT 문서 갱신 (`trading-formulas.md`)**

- 전반 공식 줄: 매수② `((unitAmount − averagePrice×매수①수량)/referencePrice, 기준가)` — `×(1+priceOffsetRate)` 제거, "잔여가 0 이하면 매수② 없음, 아니면 최소 1주" 추가. 매수① 최소 1주 유지 명시.
- isFinalRound 설명에 한 줄: "holdings ≥ 1·원장 ≥ 0이면 전반부에서는 성립하지 않는다(전반 ⇒ 원장 > 매입액 ≥ 평단, 기준가 ≤ 평단×(1+targetProfitRate)) — 리버스 진입에 isEarlyStage 가드가 필요 없는 이유".
- 보정 주문 절 신설(또는 기존 VR cap 줄 근처): 보정 공식(Global Constraints 그대로, FLOOR).
- cap 배수 경위 절: spec §2.4의 사실 3개(1.10 기원과 4154c0482의 Toss CLS 가격 범위 취소 사유, d40d07e13에서 1.05로 축소한 사용자 진술, 2026-10-25 재평가 수치·결론)와 재평가 SQL(아래)을 그대로 옮긴다. 추정 근거는 쓰지 않는다.

재평가 SQL(운영 `kistadb`, 읽기 전용):
```sql
with capdays as (select distinct strategy_cycle_id, trade_date from trading.orders
                 where order_leg like 'INFINITE_CORRECTION_%' and external_order_id is not null),
legs as (select o.strategy_cycle_id, o.trade_date, o.price, coalesce(o.filled_quantity,0)>0 filled,
                rank() over (partition by o.strategy_cycle_id, o.trade_date order by o.price desc) rk
         from trading.orders o join capdays using (strategy_cycle_id, trade_date)
         where o.direction='BUY' and o.external_order_id is not null and o.order_leg not like 'INFINITE_CORRECTION%'),
cp as (select strategy_cycle_id, (created_at at time zone 'Asia/Seoul')::date d, max(closing_price) close
       from trading.cycle_position where deleted_at is null group by 1,2)
select l.trade_date, l.price cap_leg, cp.close, round(cp.close/(l.price/1.05)-1,4) move, l.filled
from legs l left join cp on cp.strategy_cycle_id=l.strategy_cycle_id and cp.d=l.trade_date
where l.rk=1 and cp.close is not null order by l.filled, move desc;
```
주의: Task 2 이후 보정이 상시 붙으므로 이 SQL의 `capdays`(보정 leg 존재일 = cap 발동일)는 배포일 이후 데이터에서 성립하지 않는다. 문서에 "배포 이전 데이터 전용 — 이후 재평가는 cap 발동일 식별 방법을 새로 정해야 한다"고 명시한다.

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/matching trading-core/src/main/java/com/kista/tradingcore/trading/application/service/BuyOrderPriceCapper.java trading-core/src/test/java/com/kista/tradingcore/matching docs/agents/modules/tradingcore/trading-formulas.md
git commit -m "fix(matching): INFINITE 보정 가격을 내림하고 매수①이 회차 금액을 다 쓰면 매수②를 내지 않는다"
```

---

### Task 2: 보정 주문 상시 부착

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/InfiniteStrategy.java` (`buildEarlyStageOrders`, `buildLateStageOrders`, 보정 부착 공용 헬퍼)
- Modify: `docs/agents/workflow.md` ("BuyOrderPriceCapper 보정 주문" 절), `docs/agents/testing.md`(INFINITE 서술 중 바뀐 사실만), `docs/superpowers/specs/2026-10-24-strategy-own-ledger-budget-design.md` §9(4개 항목 옆에 "→ 2026-10-25 설계에서 처리" 링크)
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/strategy/InfiniteStrategyTypeTest.java` + 실패하는 trading-core 테스트 전부(예상: `TradingServiceTest`, `BuyOrderPriceCapperTest`, `ManualTradingServiceTest`, `InfiniteCycleOrderStrategy` canSkip 테스트, `BacktestEngineTest`)

**Interfaces:**
- Consumes: Task 1의 FLOOR 보정·`earlyBuyQuantity2`·`isEarlyStage` 분기.
- Produces: `InfiniteStrategy.buildOrders`가 일반모드 BUY가 있으면 base BUY 바로 뒤(SELL 앞)에 보정 3건을 붙이고, `trimCorrectionsToBudget(buys, position.usdDeposit())`를 적용한 결과를 낸다. `buildCappedBuyOrders`와 같은 보정 부착 코드를 공유한다(헬퍼 추출 방식은 재량). `isFinalRound` 분기(MOC만)·리버스모드는 불변.

- [ ] **Step 1: 실패 테스트 작성** (`InfiniteStrategyTypeTest`)

- `buildOrders_frontHalf_withQuantity` 기대값 갱신(기존 테스트 수정): unitAmount 60, base `20×1`, `22.60×1`(N=2) → 보정 `60/3=20.00`, `60/4=15.00`, `60/5=12.00`. 총 7건, leg 순서 `INFINITE_EARLY_AVG_BUY, INFINITE_EARLY_REF_BUY, INFINITE_CORRECTION_01, INFINITE_CORRECTION_02, INFINITE_CORRECTION_03, INFINITE_LOC_SELL, INFINITE_LIMIT_SELL`, 보정 가격 단언 포함.
- 신규 `buildOrders_backHalf_noCap_attachesCorrections`: `buildOrders_backHalf_sufficientDeposit`와 같은 position(평단 5, 보유 200, 원장 100) → leg 순서 `INFINITE_LATE_REF_BUY, INFINITE_CORRECTION_01..03, INFINITE_LOC_SELL, INFINITE_LIMIT_SELL`, 각 보정 가격 × (그 시점 누적수량+1) ≤ unitAmount.
- 신규 `buildOrders_zeroRound_smallDeposit_trimsAllCorrections`: 원장 20, prevClose 100 (unitAmount 1.00, base 100×1이 이미 원장 초과) → BUY는 `INFINITE_EARLY_AVG_BUY` 1건뿐(보정 전부 제거, base 유지).
- 신규 `buildOrders_deadlockSeed_trimsTrailingCorrections`: 기존 helper `deadlockSeedPosition()`으로 `buildOrders` → BUY leg가 base 뒤 보정의 prefix(`01`, `01..02` 등 연속)이고 `AccountBalance.buyTotal(BUY) ≤ position.usdDeposit()`, 보정 3건 전부는 아님.
- 신규 `buildOrders_finalRound_noCorrections`: `buildOrders_backHalf_insufficientDeposit`와 같은 position → 결과에 `INFINITE_CORRECTION_` leg 없음.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests '*InfiniteStrategyTypeTest' 2>&1 | grep -E "FAILED|tests completed|BUILD"`
Expected: 위 테스트들 FAILED(`finalRound_noCorrections`는 이미 통과할 수 있음 — 회귀 고정용).

- [ ] **Step 3: 구현** — Interfaces의 Produces대로. `buildCappedBuyOrders`의 "전후반 공통 보정 + trim" 블록을 헬퍼로 빼서 `buildEarlyStageOrders`·`buildLateStageOrders`(BUY가 있을 때)·`buildCappedBuyOrders`가 함께 쓴다. 클래스 상단 `CORRECTION_ORDER_COUNT` 주석 "가격 캡 보정 주문 추가 횟수" → "장마감 하락 대비 보정 주문 추가 횟수".

- [ ] **Step 4: trading-core 전체 테스트로 파급 확인**

Run: `./gradlew :trading-core:test 2>&1 | grep -E "FAILED|tests completed|BUILD"`
실패마다: 원인이 "보정 3건이 상시 붙음"으로 설명되면 기대값을 갱신하고 보고 목록에 "이전 → 새 값, 이유 §2.1"로 적는다. 그 외 원인이면 멈추고 보고한다. Review Focus 3·4의 기존 테스트(재진입, canSkip)가 기대값 수정 없이 통과하는지 특히 확인하고 보고에 적는다. `BacktestEngineTest` 변화는 Review Focus 5 기준으로 해석해 보고한다.

- [ ] **Step 5: 변이 확인** — `buildOrders`의 보정 부착 호출을 잠시 지워 신규 테스트가 실패하는지, trim 호출을 잠시 지워 `trimsAllCorrections`·`deadlockSeed`가 실패하는지 확인 후 복구.

- [ ] **Step 6: 문서 갱신**

- `workflow.md` "BuyOrderPriceCapper 보정 주문" INFINITE 절: 보정은 cap 경로 전용이 아니라 `buildOrders`가 상시 붙이고 cap 재산정 시 base와 함께 다시 계산된다, 가격 `K/(누적수량+1)` HALF_UP → FLOOR, `buildOrders`에서도 `trimCorrectionsToBudget` 적용.
- `trading-formulas.md` 보정 주문 절(Task 1에서 신설)에 "cap과 무관하게 일반모드 BUY마다 부착(isFinalRound·리버스 제외)" 추가.
- `testing.md`: INFINITE 테스트 패턴 서술 중 위 변경으로 거짓이 된 문장만 고친다(없으면 변경 없음).
- 원 spec §9의 INFINITE 4개 항목에 `→ 처리: docs/superpowers/specs/2026-10-25-infinite-correction-cap-review-design.md` 표기.
- `src/test/resources/architecture/flows.yml`에 보정 주문 서술이 없는지 grep으로 재확인(있으면 갱신).

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src docs/agents docs/superpowers/specs/2026-10-24-strategy-own-ledger-budget-design.md
git commit -m "feat(matching): INFINITE 보정 주문을 cap 발동과 무관하게 항상 붙인다"
```

---

### 마무리 (Task 2 리뷰 통과 후)

- [ ] `./gradlew test :trading-core:test 2>&1 | grep -E "FAILED|tests completed|BUILD"` 1회(아키텍처 테스트 포함). 실패 시 XML로 진단: `grep -oP 'failures="\K[^"]+' trading-core/build/test-results/test/TEST-*.xml | grep -v ':0'`
- [ ] 기대값 변경 보고서(Global Constraints "보고 의무")를 사용자에게 제출.
- [ ] push는 사용자 요청 시에만.
