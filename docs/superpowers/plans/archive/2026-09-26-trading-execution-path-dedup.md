# trading 실행 경로 중복 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading 모듈의 실행 경로(미리보기/야간배치/수동실행)에 중복 구현된 계산·검증 로직을 단일 경로로 통합하고, `PriceCapMode` enum 기반 분기를 `CycleOrderStrategy` capability 다형성으로 편입한다.

**Architecture:** 기존 구조(오케스트레이션 레이어, capability 패턴 자체)는 그대로 두고, 같은 계산이 여러 곳에 재구현된 지점만 단일 진입점으로 합친다. 단건 미리보기는 배치 미리보기에 위임하고, 예산 배정기는 다계좌 방어 코드를 단일계좌 전용으로 걷어내고, 수동실행은 배치와 동일한 계획빌더·배정기를 재사용하며, BUY 가격 캡은 enum switch 대신 전략 구현체가 스스로 계산한다.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5 + Mockito + AssertJ, Gradle(`:trading-core` 서브프로젝트)

**Spec:** `docs/superpowers/specs/2026-09-26-trading-execution-path-dedup-design.md`

## Global Constraints

- 매매 공식(`docs/agents/modules/trading-formulas.md`)은 절대 변경하지 않는다 — 이 계획의 모든 태스크는 계산 결과가 아니라 계산이 호출되는 경로만 바꾼다.
- 태스크 진행 중 검증은 `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.*'`처럼 좁힌 범위로 실행한다. 전체 스위트(`./gradlew test`)는 4개 태스크가 모두 끝난 뒤 최종 1회만 돌린다.
- 커밋 author는 `narafu <narafu@kakao.com>`, 커밋 메시지는 한글 Conventional Commit(`refactor(trading): ...`) 형식을 따른다.
- 각 태스크는 매매 실행 경로(주문 계산·예산 배정·가격 보정)를 직접 건드리므로, 태스크별 구현이 끝나면 커밋 전 반드시 별도 리뷰어 검수를 받는다(이 계획의 스텝에는 포함하지 않음 — 실행 단계에서 subagent-driven-development/executing-plans가 적용).
- 신규 own-type·포트 복제는 발생하지 않는다(순수 리팩토링). `docs/agents/workflow.md`(스케쥴러 실행 흐름 SSOT)는 Task 2 착수 전에 재확인한다.

## Review Focus

- **Task 3의 캡 적용 전/후 금액 차이로 수동실행 검증 결과가 갈리는 경계 케이스** — 스펙이 명시한 의도적 동작 변경(예수금 부족 거부 기준이 "캡 적용 전"에서 "캡 적용 후"로 바뀜)이 실제로 새 테스트로 고정됐는지, 그리고 기존 수동실행 테스트가 이 변경으로 의미가 바뀌지 않았는지.
- **Task 4에서 VR bootstrap 주문(LOC+AT_CLOSE)이 `capBuyOrders` 편입 후에도 계속 스킵되는지** — `isVrBootstrapShaped` 판정이 `VrCycleOrderStrategy`로 이동한 뒤에도 `BuyOrderPriceCapperTest`의 부트스트랩 회귀 테스트가 동일하게 통과하는지.
- **Task 4에서 PRIVACY "초과분만 취소" vs INFINITE/VR "전체 취소" 차이가 `capsIndividualOrders()` 편입 후에도 보존되는지** — `applyIndividualCap`/`applyBatchCap` 분리가 기존 `markCancelled` 호출 횟수 assertion과 정확히 일치하는지.
- **Task 2에서 단일계좌 전용화 이후 여러 계좌 candidate가 섞여 들어와도 조용히 틀린 배정이 나가지 않는지** — 호출부(`TradingCandidatePlanner.saveAllocatedOrders`, `ManualTradingService.execute`) 둘 다 이미 단일계좌 리스트만 넘기는 것을 코드로 재확인했으므로 별도 가드는 추가하지 않지만, 리뷰 시 이 전제가 실제로 100% 성립하는지(다른 신규 호출부가 생기지 않았는지) 재확인한다.
- **Task 1에서 배치 미리보기가 이미 주문 낸 경쟁 전략까지 항상 재계산하게 되는 비용 증가** — 정확성 문제는 아니지만, 계좌당 전략 수가 큰 경우 실측 응답 시간이 유의미하게 늘어나는지 스테이징/운영 로그로 사후 확인한다(이 계획의 테스트 범위 밖).
- **Task 1에서 previewBatch의 사전계산 실패 재시도 로직이 실제로 옮겨졌는지** — self-review 중 발견된 지점: `buildPreview`의 null 분기를 단순 제거만 하면 대상 전략 자신의 사전계산이 실패했을 때 `planResult`가 null인 채로 `result.isSkip()`을 호출해 NPE가 난다. Step 3에 재시도 로직을 `previewBatch()` 최종 루프로 옮기는 코드와 Step 9의 회귀 테스트(`preview_retriesOwnPlanComputation_whenPrecomputeFailedForTargetStrategy`)를 반영해뒀으니, 리뷰 시 이 재시도 코드가 실제로 구현됐는지와 신규 테스트가 실제로 NPE 시나리오를 재현하는지 확인한다.

---

## Task 1: 미리보기 단건→배치 위임 통합

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingPreviewService.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingBuyCompetitionSimulator.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/TradingPreviewServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/TradingBuyCompetitionSimulatorTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/StrategyOrderPlanBuilderTest.java`

**Interfaces:**
- Consumes: 기존 `previewBatch(UUID accountId, UUID requesterId)`가 이미 있음(변경 없음)
- Produces: `preview(UUID strategyId, UUID requesterId)`는 시그니처 그대로, 내부 구현만 `previewBatch` 위임으로 교체 — 이후 태스크는 이 시그니처에 의존하지 않는다

- [ ] **Step 1: `StrategyOrderPlanBuilder.build`의 5-인자 오버로드 제거**

`trading-core/src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java`를 다음과 같이 수정(44-52번째 줄 교체):

```java
    // prevCloseCache: 배치 미리보기(TradingPreviewService.previewBatch) 전용 — 계좌 내 종목별 전일종가를
    // 1회 일괄 조회(getPrevCloses)해 넘기면 전략마다 개별 KIS 호출을 생략한다. 캐시에 없는 종목은
    // 기존과 동일하게 단건 라이브 조회로 폴백한다. 호출부가 항상 previewBatch 경로를 거치므로 이제 필수 인자다.
    PlanResult build(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today, String label,
                      Map<StrategyTicker, BigDecimal> prevCloseCache) {
```
(기존 5-인자 `build(strategy, account, currentCycle, today, label)` 오버로드와 그 안에서 6-인자 버전을 호출하던 위임 코드는 삭제한다.)

- [ ] **Step 2: `TradingBuyCompetitionSimulator.simulate`의 6-파라미터 오버로드 제거**

`trading-core/src/main/java/com/kista/trading/application/service/TradingBuyCompetitionSimulator.java`의 58-62번째 줄(context 없는 6-파라미터 위임 오버로드)을 삭제한다. 64-66번째 줄의 7-파라미터 시그니처만 남기고 `BatchContext context`는 필수 인자로 유지, 메서드 본문 107-114번째 줄의 `context != null ? ... : planBuilder.build(other, account, otherCycle, today, "competition:" + other.id())` 3항 분기를 다음으로 교체:

```java
            StrategyOrderPlanBuilder.PlanResult result = context.planResultsByStrategyId().get(other.id());
            if (result == null) {
                // 사전 계산 실패(캐시 미스) — 즉시 재계산해 과소평가 방지. prevCloseCache는 여기서 알 수 없으므로
                // 빈 맵을 넘겨 StrategyOrderPlanBuilder가 단건 라이브 조회로 폴백하게 한다.
                result = planBuilder.build(other, account, otherCycle, today, "competition:" + other.id(), Map.of());
            }
```
88번째 줄의 `List<Strategy> candidates = context != null ? context.strategies() : strategyPort.findByAccountId(account.id());`도 `context.strategies()`로 단순화하고, 93-101번째 줄의 `context != null ? context.xxx() : ...` 2곳도 각각 `context.cyclesByStrategyId().get(other.id())`, `context.todayOrdersByStrategyId().getOrDefault(other.id(), List.of())`로 단순화한다(널 체크·폴백 분기 제거).

- [ ] **Step 3: `TradingPreviewService.preview()`를 `previewBatch()` 위임으로 교체**

`trading-core/src/main/java/com/kista/trading/application/service/TradingPreviewService.java`의 46-58번째 줄(`preview` 메서드 전체)을 다음으로 교체:

```java
    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    // 내부적으로 previewBatch()에 위임한다 — 대상 전략이 BUY 계획을 가지면 경쟁 시뮬레이션 때문에 계좌 내
    // 활성 전략 전체를 어차피 다시 계산해야 하므로, 단건과 배치를 별도 코드 경로로 유지할 이유가 없다.
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return Optional.ofNullable(previewBatch(strategy.accountId(), requesterId).get(strategyId))
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategyId));
    }
```
`java.util.Optional` import가 이미 있는지 확인하고 없으면 추가한다(현재 27번째 줄 `import java.util.NoSuchElementException;` 근처).

이어서 `buildPreview` 메서드(146-211번째 줄)의 4개 precomputed 인자를 필수 인자로 바꾼다 — 시그니처를 다음으로 교체:
```java
    private NextOrdersPreview buildPreview(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today,
                                            List<Order> todayOrders,
                                            StrategyOrderPlanBuilder.PlanResult planResult,
                                            TradingBuyCompetitionSimulator.BatchContext context,
                                            BigDecimal totalAccountPlannedBuy) {
```
본문에서 151-165번째 줄의 `precomputedTodayOrders != null ? ... : orderPort.findPlannedOrPlacedByCycleAndDate(...)`와 `precomputedTotalAccountPlannedBuy != null ? ... : orderPort.sumPlannedBuyByAccountAndDate(...)` 널 분기를 제거하고 파라미터를 그대로 사용하도록 바꾼다:
```java
        List<Order> otherStrategiesPlannedBuySource = todayOrders; // 파라미터명 그대로 사용
        BigDecimal thisStrategyPlannedBuy = todayOrders.stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);
```
(`precomputedTodayOrders`/`precomputedTotalAccountPlannedBuy` 참조를 각각 `todayOrders`/`totalAccountPlannedBuy`로 바꿔치기만 하면 된다.)

180-196번째 줄의 `precomputedPlanResult != null ? ... : planBuilder.build(...)`와 `context != null ? competitionSimulator.simulate(..., context) : competitionSimulator.simulate(...)` 2곳도 분기 제거:
```java
        StrategyOrderPlanBuilder.PlanResult result = planResult;
        if (result.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayOrders, otherStrategiesPlannedBuyUsd, null, null);
        }
        CycleOrderStrategy.OrderPlan plan = result.plan();

        List<PlannedOrder> buyOrders = plan.orders().stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .toList();
        BuyCompetitionPreview competition = buyOrders.isEmpty()
                ? null
                : competitionSimulator.simulate(strategy, account, currentCycle, buyOrders, today, otherStrategiesPlannedBuyUsd, context);
```
**주의 — 여기서 놓치기 쉬운 회귀 지점**: `previewBatch()`의 사전계산 루프(100-130번째 줄)는 `planBuilder.build(...)`가 `RuntimeException`을 던지면 `catch` 블록에서 `log.warn(...)`만 하고 `planResultsByStrategyId`에 아무것도 넣지 않는다(121-129번째 줄) — 즉 그 전략의 `planResultsByStrategyId.get(strategy.id())`는 `null`로 남는다. 원래 `buildPreview`의 `precomputedPlanResult != null ? precomputedPlanResult : planBuilder.build(...)` 분기가 바로 이 경우(대상 전략 자신의 사전계산 실패)를 감지해 즉시 재시도하는 안전장치였다 — 단순히 "단건 호출이라 precomputed가 없는 경우"만을 위한 분기가 아니었다. `buildPreview`에서 이 분기를 제거했으므로, 재시도 로직을 호출부인 `previewBatch()`의 최종 루프(136-142번째 줄)로 옮겨야 한다. 해당 루프를 다음으로 교체한다:
```java
        Map<UUID, NextOrdersPreview> previews = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            StrategyCycle cycle = cyclesByStrategyId.get(strategy.id());
            if (cycle == null) continue;
            StrategyOrderPlanBuilder.PlanResult planResult = planResultsByStrategyId.get(strategy.id());
            if (planResult == null) {
                // 사전계산 단계에서 이 전략의 build()가 실패해 캐시에 없는 경우 — 즉시 재시도.
                // 재시도도 실패하면 기존과 동일하게 예외를 그대로 전파한다(호출부인 previewBatch()가
                // 통째로 실패하는 기존 동작 그대로 — 이 계획은 그 예외 처리 정책 자체는 바꾸지 않는다).
                planResult = planBuilder.build(strategy, account, cycle, today, "preview:" + strategy.id(), prevCloseCache);
            }
            previews.put(strategy.id(), buildPreview(strategy, account, cycle, today,
                    todayOrdersByStrategyId.get(strategy.id()), planResult, context, totalAccountPlannedBuy));
        }
        return previews;
```
(이 교체로 `buildPreview`에 넘어가는 `planResult` 인자는 이제 null일 수 없다 — `result.isSkip()` 호출 시 NPE 위험 제거.)

- [ ] **Step 4: 좁힌 범위로 컴파일 확인**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL (메서드 시그니처 불일치로 인한 컴파일 오류가 없어야 함)

- [ ] **Step 5: 테스트 파일의 오버로드 호출부를 새 시그니처로 갱신 — `StrategyOrderPlanBuilderTest`**

`trading-core/src/test/java/com/kista/trading/application/service/StrategyOrderPlanBuilderTest.java`에서 5-인자 `builder.build(strategy, account, cycle, today, "label")` 호출(73, 91, 115, 134, 184번째 줄)을 전부 `builder.build(strategy, account, cycle, today, "label", Map.of())`로 바꾼다(151, 169번째 줄은 이미 6-인자라 변경 불필요).

- [ ] **Step 6: 테스트 파일의 오버로드 호출부를 새 시그니처로 갱신 — `TradingBuyCompetitionSimulatorTest`**

`trading-core/src/test/java/com/kista/trading/application/service/TradingBuyCompetitionSimulatorTest.java`를 열어 `simulator.simulate(...)` 호출부(80, 106, 128, 156, 181, 206, 239, 259, 273번째 줄) 각각을 확인한다. 6-파라미터 형태(`currentStrategy, account, currentCycle, currentBuyOrders, today, otherStrategiesPlannedBuyUsd`)로 호출하는 곳은 마지막에 `null`이 아닌 `new TradingBuyCompetitionSimulator.BatchContext(List.of(), Map.of(), Map.of(), Map.of())`(빈 컨텍스트 — 캐시 미스로 즉시 재계산 경로를 타게 함, 기존 6-파라미터 오버로드가 내부에서 `context=null`로 위임하던 것과 동일한 실행 경로) 또는 이미 7-파라미터로 실제 `BatchContext`를 넘기고 있는 곳은 그대로 둔다. 각 호출부를 실제로 열어 어느 형태인지 확인 후 누락 없이 7-파라미터로 통일한다.

- [ ] **Step 7: 테스트 파일의 오버로드 호출부를 새 시그니처로 갱신 — `TradingPreviewServiceTest`**

`trading-core/src/test/java/com/kista/trading/application/service/TradingPreviewServiceTest.java`에서 `planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString())` 형태의 5-인자 stub/verify(90번째 줄 등 다수)를 전부 `planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any())`로 갱신한다. `verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any())`(98번째 줄) 형태는 `any()`를 하나 더 추가해 7개로 갱신한다. 파일 전체에서 `planBuilder.build(` / `competitionSimulator.simulate(` / `verify(planBuilder` / `verify(competitionSimulator`로 grep해 누락된 호출부가 없는지 재확인한다.

Run: `grep -n "planBuilder.build(\|competitionSimulator.simulate(" trading-core/src/test/java/com/kista/trading/application/service/TradingPreviewServiceTest.java`
Expected: 모든 `build(` 호출이 6개 인자, 모든 `simulate(` 호출이 7개 인자여야 한다.

- [ ] **Step 8: 좁힌 범위로 테스트 실행 — 기존 테스트가 그대로 통과해야 한다**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.TradingPreviewServiceTest' --tests 'com.kista.trading.application.service.TradingBuyCompetitionSimulatorTest' --tests 'com.kista.trading.application.service.StrategyOrderPlanBuilderTest'`
Expected: BUILD SUCCESSFUL, 기존 테스트 케이스 전부 PASS(신규 동작 없음 — 순수 시그니처 정리이므로 assertion 변경 불필요)

- [ ] **Step 9: 신규 회귀 테스트 작성 — 경쟁 전략이 이미 오늘 주문을 낸 케이스**

`TradingPreviewServiceTest.java`에 다음 테스트를 추가한다(기존 `preview_returnsOrdersWithoutCompetition_whenPlanHasNoBuyOrders` 테스트 뒤에):

```java
    // 배치 통합 이후에도 이미 오늘 주문을 낸 경쟁 전략은 경쟁 순위에서 제외돼야 한다(계산은 더 하지만 결과는 불변)
    @Test
    void preview_excludesCompetitorThatAlreadyOrderedToday_evenThoughBatchAlwaysComputesItsPlan() {
        Strategy competitor = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle competitorCycle = new StrategyCycle(
                UUID.randomUUID(), competitor.id(), UUID.randomUUID(), new BigDecimal("1000.00"),
                null, LocalDate.now().minusDays(7), null, null, null);
        Order competitorExistingOrder = new Order(UUID.randomUUID(), ACCOUNT.id(), competitorCycle.id(),
                LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("20.00"), OrderStatus.PLANNED, null, null, null);

        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY, competitor));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE));
        // previewBatch()의 배치 사이클 조회는 대상 전략(STRATEGY) 목록으로만 호출되지 않는다 — 계좌 내 전략 전체를 대상으로 조회
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id(), competitor.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE, competitor.id(), competitorCycle));
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any()))
                .thenReturn(Map.of(competitorCycle.id(), List.of(competitorExistingOrder)));

        PlannedOrder buyOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.BUY, 1, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan targetPlan = new CycleOrderStrategy.OrderPlan(null, null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(targetPlan, null));
        // 경쟁자는 이미 오늘 주문이 있으므로 previewBatch가 계획을 계산은 하지만(배치는 항상 전량 계산)
        // TradingBuyCompetitionSimulator가 alreadyOrdered로 걸러 최종 경쟁 순위에는 포함하지 않는다
        when(planBuilder.build(eq(competitor), eq(ACCOUNT), eq(competitorCycle), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(
                        new CycleOrderStrategy.OrderPlan(null, null, List.of(buyOrder)), null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        // 배치가 competitor의 계획도 미리 계산했음을 확인(비용 증가는 의도된 트레이드오프)
        verify(planBuilder).build(eq(competitor), eq(ACCOUNT), eq(competitorCycle), any(), anyString(), any());
        // 하지만 competitor는 alreadyOrdered라 경쟁 순위에서 제외돼 blocked 목록에 나타나지 않는다
        assertThat(result.competition()).isNotNull();
        assertThat(result.competition().blocked()).isEmpty();
    }
```

바로 위에서 고친 회귀(사전계산 실패 시 재시도)를 고정하는 테스트도 같은 파일에 추가한다:
```java
    // previewBatch()의 사전계산 단계에서 대상 전략 자신의 build()가 한 번 실패해도(캐시 미스),
    // buildPreview 호출 전에 재시도해야 한다 — 재시도 없이 null을 그대로 넘기면 result.isSkip()에서 NPE.
    @Test
    void preview_retriesOwnPlanComputation_whenPrecomputeFailedForTargetStrategy() {
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE));
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any())).thenReturn(Map.of());

        CycleOrderStrategy.OrderPlan noOrderPlan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenThrow(new RuntimeException("일시적 계산 오류"))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(noOrderPlan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result).isNotNull();
        assertThat(result.orders()).isEmpty();
        verify(planBuilder, times(2)).build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any());
    }
```

- [ ] **Step 10: 신규 테스트 실행 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.TradingPreviewServiceTest'`
Expected: BUILD SUCCESSFUL, 신규 테스트 2개(경쟁 전략 제외 케이스 + 사전계산 실패 재시도 케이스) 모두 PASS

- [ ] **Step 11: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/application/service/TradingPreviewService.java \
        trading-core/src/main/java/com/kista/trading/application/service/TradingBuyCompetitionSimulator.java \
        trading-core/src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java \
        trading-core/src/test/java/com/kista/trading/application/service/TradingPreviewServiceTest.java \
        trading-core/src/test/java/com/kista/trading/application/service/TradingBuyCompetitionSimulatorTest.java \
        trading-core/src/test/java/com/kista/trading/application/service/StrategyOrderPlanBuilderTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): 미리보기 단건 경로를 배치 위임으로 통합

preview()가 previewBatch()에 위임하도록 바꾸고, precomputed null 분기·
2개 오버로드(StrategyOrderPlanBuilder.build/TradingBuyCompetitionSimulator.simulate)를
제거해 같은 계산이 두 경로에 반복 구현되던 문제를 없앴다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Qi36d28hgnhCujutf162ys
EOF
)"
```

---

## Task 2: TradingOrderBudgetAllocator 단일계좌 전용화

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/TradingOrderBudgetAllocatorTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java`

**Interfaces:**
- Consumes: `com.kista.trading.application.service.support.TradingParallelRunner`(기존 클래스, `Task<T>(groupKey, Callable<Optional<T>>)` + `runAll(List<Task<T>>) -> List<T>`), `TradingBatchGuard.runSafely(phase, ctx, ThrowingSupplier<T>) -> Optional<T>`(기존)
- Produces: `TradingOrderBudgetAllocator.allocate(List<Candidate> candidates, LocalDate tradeDate) -> Allocation`(2-인자 단일 진입점 — 이후 태스크(Task 4)가 이 시그니처를 그대로 재사용한다). `TradingOrderBudgetAllocator` 생성자는 `(BrokerAdapterRegistry, OrderPort, CycleOrderStrategies)` 3-인자로 축소(TradingParallelRunner 제거)

- [ ] **Step 1: `TradingOrderBudgetAllocator`에서 다계좌 병렬 선조회 인프라 제거**

`trading-core/src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java`를 다음과 같이 재작성한다:

클래스 필드(41-46번째 줄)에서 `TradingParallelRunner parallelRunner` 필드와 관련 import(`com.kista.trading.application.service.support.TradingParallelRunner`)를 제거:
```java
@Slf4j
@Component
@RequiredArgsConstructor
class TradingOrderBudgetAllocator {

    private final BrokerAdapterRegistry registry;              // live 잔고·판매가능수량 조회
    private final OrderPort orderPort;                         // 기존 PLANNED/PLACED 예약분 조회
    private final CycleOrderStrategies cycleOrderStrategies;    // 전략 타입별 예산 배정 우선순위 조회
```

`AccountQuote` record(61-63번째 줄)에서 `failure` 필드 제거:
```java
    // 계좌 단위 브로커 선조회 결과
    record AccountQuote(AccountBalance liveBalance, Map<StrategyTicker, Integer> sellableByTicker) {}
```

`LiveQuotes` record(66-72번째 줄), `fetchLiveQuotes` 메서드(75-87번째 줄), `rethrowIfFailed` 메서드(126-131번째 줄)를 전부 삭제한다.

`fetchQuoteInline` 메서드(91-124번째 줄)를 `fetchQuote`로 이름을 바꾸고 try/catch를 제거(예외를 그대로 전파 — 호출부가 `runSafely`로 감싸므로 여기서 흡수할 필요 없음):
```java
    // 한 계좌 스코프의 브로커 선조회 — 잔고는 BUY 후보 존재 시만, 판매가능수량은 SELL 후보의 종목별로만 조회한다
    // candidates는 항상 단일 계좌 스코프여야 한다(호출부가 이미 계좌별로 묶어서 넘긴다)
    private AccountQuote fetchQuote(List<Candidate> accountCandidates) {
        Account account = accountCandidates.getFirst().ctx().account();

        List<Candidate> buyCandidates = accountCandidates.stream()
                .map(candidate -> candidate.withOrders(
                        candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
        AccountBalance liveBalance = null;
        if (!buyCandidates.isEmpty()) {
            Candidate probe = buyCandidates.stream().sorted(buyPriorityComparator()).findFirst().orElseThrow();
            BrokerBalance bb = registry.require(account.toBrokerRef(), LiveBalancePort.class)
                    .getLiveBalance(account.toBrokerRef(), probe.ctx().strategy().ticker());
            liveBalance = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
        }

        Set<StrategyTicker> sellTickers = accountCandidates.stream()
                .flatMap(candidate -> candidate.orders().stream())
                .filter(order -> order.direction() == SELL)
                .map(PlannedOrder::ticker)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<StrategyTicker, Integer> sellableByTicker = new LinkedHashMap<>();
        for (StrategyTicker ticker : sellTickers) {
            int sellable = registry.require(account.toBrokerRef(), SellableQuantityPort.class)
                    .getSellableQuantity(ticker, account.toBrokerRef())
                    .quantity();
            sellableByTicker.put(ticker, sellable);
        }
        return new AccountQuote(liveBalance, sellableByTicker);
    }
```

`allocate` 메서드 2개(134-148번째 줄, 2-인자/3-인자)를 하나로 합친다:
```java
    // candidates는 반드시 단일 계좌 스코프 — 조회+배정을 한 번에 수행한다
    Allocation allocate(List<Candidate> candidates, LocalDate tradeDate) {
        if (candidates.isEmpty()) return new Allocation(List.of(), List.of(), List.of());
        AccountQuote quote = fetchQuote(candidates);

        SellAllocation sellAllocation = allocateSells(candidates, tradeDate, quote);
        BuyAllocation buyAllocation = allocateBuys(candidates, tradeDate, quote);
        List<Candidate> approved = mergeApproved(candidates, sellAllocation.approved(), buyAllocation.approved());
        return new Allocation(approved, buyAllocation.rejected(), sellAllocation.rejected());
    }
```

`allocateSells`의 `requestsByAccountTicker`(151-160번째 줄)는 계좌가 항상 동일하므로 `AccountTicker` 래퍼 없이 `StrategyTicker`만으로 그룹핑하도록 단순화:
```java
    private SellAllocation allocateSells(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
        Map<StrategyTicker, List<SellRequest>> requestsByTicker = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            Map<StrategyTicker, List<PlannedOrder>> sellsByTicker = candidate.orders().stream()
                    .filter(order -> order.direction() == SELL)
                    .collect(java.util.stream.Collectors.groupingBy(
                            PlannedOrder::ticker, LinkedHashMap::new, java.util.stream.Collectors.toList()));
            sellsByTicker.forEach((ticker, sells) -> requestsByTicker
                    .computeIfAbsent(ticker, ignored -> new ArrayList<>())
                    .add(new SellRequest(candidate, sells)));
        }

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        requestsByTicker.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> allocateSellsForTicker(entry.getKey(), entry.getValue(), tradeDate, quote, approved, rejected));
        return new SellAllocation(approved, rejected);
    }

    private void allocateSellsForTicker(StrategyTicker ticker, List<SellRequest> requests, LocalDate tradeDate,
                                                AccountQuote quote, List<Candidate> approved, List<Candidate> rejected) {
        List<SellRequest> sorted = requests.stream().sorted(sellPriorityComparator()).toList();
        Account account = sorted.getFirst().candidate().ctx().account();
        Integer sellableQuantityBoxed = quote.sellableByTicker().get(ticker);
        if (sellableQuantityBoxed == null) {
            throw new IllegalStateException("판매가능수량 선조회 결과 없음: accountId=" + account.id() + ", ticker=" + ticker);
        }
        int sellableQuantity = sellableQuantityBoxed;
        int reservedQuantity = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, ticker);
        int requestedQuantity = sorted.stream().mapToInt(request -> sellTotal(request.orders())).sum();
        int allocatedQuantity = 0;

        for (SellRequest request : sorted) {
            int requiredQuantity = sellTotal(request.orders());
            if (reservedQuantity + allocatedQuantity + requiredQuantity <= sellableQuantity) {
                approved.add(request.candidate().withOrders(request.orders()));
                allocatedQuantity += requiredQuantity;
                log.info("[{}] SELL 승인: ticker={}, required={}, reserved={}, allocated={}, sellable={}",
                        account.nickname(), ticker, requiredQuantity, reservedQuantity, allocatedQuantity, sellableQuantity);
            } else {
                rejected.add(request.candidate().withOrders(request.orders()));
                log.warn("[{}] SELL 판매가능수량 부족으로 제외: ticker={}, required={}, requestedTotal={}, reserved={}, allocated={}, sellable={}",
                        account.nickname(), ticker, requiredQuantity, requestedQuantity, reservedQuantity, allocatedQuantity, sellableQuantity);
            }
        }
    }
```
(`allocateSellsForAccountTicker`를 `allocateSellsForTicker`로 리네임했고, `AccountTicker` record와 `accountTickerComparator()` 메서드는 이제 사용처가 없으므로 클래스 하단(266-269, 312번째 줄)에서 함께 삭제한다.)

`allocateBuysByAccount`를 `allocateBuys`로 이름을 바꾸고 `candidatesByAccount` 재그룹 제거:
```java
    private BuyAllocation allocateBuys(List<Candidate> candidates, LocalDate tradeDate, AccountQuote quote) {
        List<Candidate> buyCandidates = candidates.stream()
                .map(candidate -> candidate.withOrders(
                        candidate.orders().stream().filter(order -> order.direction() == BUY).toList()))
                .filter(candidate -> !candidate.orders().isEmpty())
                .toList();
        if (buyCandidates.isEmpty()) return new BuyAllocation(List.of(), List.of());

        List<Candidate> sorted = buyCandidates.stream().sorted(buyPriorityComparator()).toList();
        Account account = sorted.getFirst().ctx().account();
        AccountBalance live = quote.liveBalance();
        if (live == null) {
            throw new IllegalStateException("BUY 잔고 선조회 결과 없음: accountId=" + account.id());
        }
        BigDecimal reservedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), tradeDate);
        BigDecimal allocatedInBatch = BigDecimal.ZERO;

        List<Candidate> approved = new ArrayList<>();
        List<Candidate> rejected = new ArrayList<>();
        for (Candidate candidate : sorted) {
            BigDecimal required = buyTotal(candidate.orders());
            BigDecimal alreadyCommitted = reservedBuy.add(allocatedInBatch);
            if (live.hasSufficientDepositFor(candidate.orders(), alreadyCommitted)) {
                approved.add(candidate);
                allocatedInBatch = allocatedInBatch.add(required);
                log.info("[{}] BUY 예산 배정: strategy={}, required={}, remaining={}",
                        account.nickname(), candidate.ctx().strategy().type(), required,
                        remainingDeposit(live, reservedBuy, allocatedInBatch));
            } else {
                rejected.add(candidate);
                log.warn("[{}] BUY 예산 부족으로 제외: strategy={}, required={}, remaining={}",
                        account.nickname(), candidate.ctx().strategy().type(), required,
                        remainingDeposit(live, reservedBuy, allocatedInBatch));
            }
        }
        return new BuyAllocation(approved, rejected);
    }
```
`allocate()` 안의 `allocateBuysByAccount(candidates, tradeDate, quote)` 호출도 `allocateBuys(candidates, tradeDate, quote)`로 이름만 맞춘다.

- [ ] **Step 2: `TradingCandidatePlanner`에 `TradingParallelRunner` 주입, 조회+배정을 계좌별 병렬 태스크로 결합**

`trading-core/src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java` 상단 import에 `import com.kista.trading.application.service.support.TradingParallelRunner;`를 추가하고, 필드 목록(36-44번째 줄) 마지막 줄(`private final TradingBatchGuard batchGuard;`) 바로 뒤에 다음을 추가한다 — `@RequiredArgsConstructor`는 필드 선언 순서대로 생성자 파라미터를 만들므로 반드시 맨 뒤에 추가해야 아래 Step 5의 `new TradingCandidatePlanner(...)` 호출 인자 순서(마지막 인자가 `parallelRunner`)와 일치한다:
```java
    private final OrderPort orderPort;
    private final CycleOrderComputer orderComputer;
    private final TradingOrderPlanner orderPlanner;
    private final BuyOrderPriceCapper priceCapper;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final TradingOrderBudgetAllocator budgetAllocator;
    private final TradingBalanceLoader balanceLoader;
    private final ApplicationEventPublisher eventPublisher; // 예수금 부족 알림(InsufficientBalanceEvent)
    private final TradingBatchGuard batchGuard;
    private final TradingParallelRunner parallelRunner;         // 계좌별 조회+예산배정 병렬 실행
```

`saveAllocatedOrders` 메서드(194-251번째 줄)의 205-222번째 줄(`fetchLiveQuotes` 선조회 + 순차 `for` 루프)을 다음으로 교체:
```java
        List<TradingParallelRunner.Task<TradingOrderBudgetAllocator.Allocation>> tasks = candidatesByAccount.values().stream()
                .map(accountCandidates -> {
                    BatchContext firstContext = accountCandidates.getFirst().ctx();
                    return new TradingParallelRunner.Task<TradingOrderBudgetAllocator.Allocation>(firstContext.account().id(),
                            () -> batchGuard.runSafely("계좌 주문 예산 배정", firstContext,
                                    () -> budgetAllocator.allocate(accountCandidates, tradeDate)));
                })
                .toList();
        List<TradingOrderBudgetAllocator.Allocation> allocations = new ArrayList<>(parallelRunner.runAll(tasks));
```
(이 위에 있던 `Set<BatchContext> savedContexts = new LinkedHashSet<>();`와 `List<TradingOrderBudgetAllocator.Allocation> allocations = new ArrayList<>();` 중복 선언은 위 교체 코드가 `allocations`를 직접 초기화하므로 기존 `List<...> allocations = new ArrayList<>();` 빈 선언 줄만 제거하고 `savedContexts` 선언은 그대로 둔다.)

- [ ] **Step 3: 좁힌 범위로 컴파일 확인**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: `TradingOrderBudgetAllocatorTest`에서 삭제된 API를 테스트하던 케이스 제거, 나머지는 그대로 통과해야 함**

`trading-core/src/test/java/com/kista/trading/application/service/TradingOrderBudgetAllocatorTest.java`에서 다음 2개 테스트 메서드를 삭제한다(319-332번째 줄 `fetchLiveQuotes_capturesFailurePerAccountAndAllocateRethrowsOriginalException`, 334-348번째 줄 `allocate_withPrefetchedQuoteDoesNotCallRegistryAgain`) — 둘 다 삭제된 `fetchLiveQuotes`/`LiveQuotes`/3-인자 `allocate`를 직접 테스트하던 케이스라 더 이상 컴파일되지 않는다.

77번째 줄의 allocator 생성 코드를 3-인자로 갱신:
```java
        allocator = new TradingOrderBudgetAllocator(registry, orderPort, cycleOrderStrategies);
```
(`TradingParallelRunner` import와 `new TradingParallelRunner(2)` 인자 제거.)

나머지 12개 테스트(`allocate_prioritizesVrThenInfiniteThenPrivacyWithLimitedCash` 등)는 전부 이미 `allocator.allocate(candidates, tradeDate)` 2-인자 형태를 쓰고 있으므로 수정 없이 그대로 통과해야 한다.

- [ ] **Step 5: `TradingServiceTest`의 allocator/candidatePlanner 생성자 배선 갱신**

`trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java`의 217-222번째 줄을 다음으로 교체:
```java
        TradingOrderBudgetAllocator budgetAllocator = new TradingOrderBudgetAllocator(
                tradingRegistry, orderPort, cycleStrategies);
        TradingBatchGuard batchGuard = new TradingBatchGuard(eventPublisher);
        TradingCandidatePlanner candidatePlanner = new TradingCandidatePlanner(
                orderPort, orderComputer, orderPlanner, priceCapper, cycleStrategies,
                budgetAllocator, balanceLoader, eventPublisher, batchGuard, new TradingParallelRunner(0));
```
(`new TradingParallelRunner(0)`을 추가해 기존과 동일한 순차 인라인 모드를 `TradingCandidatePlanner`에도 적용 — 기존 51개 테스트의 결정적 호출 순서를 보존한다.)

- [ ] **Step 6: 좁힌 범위로 테스트 실행**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.TradingOrderBudgetAllocatorTest' --tests 'com.kista.trading.application.service.TradingServiceTest'`
Expected: BUILD SUCCESSFUL, 전부 PASS

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java \
        trading-core/src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java \
        trading-core/src/test/java/com/kista/trading/application/service/TradingOrderBudgetAllocatorTest.java \
        trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): 예산 배정기를 단일계좌 전용으로 축소

실제 호출부가 항상 단일계좌 candidate만 넘기는데도 다계좌 병렬 선조회
인프라(fetchLiveQuotes/LiveQuotes/AccountQuote.failure)를 갖추고 있던
것을 걷어내고, 계좌별 조회+배정을 TradingParallelRunner 태스크 하나로
묶었다. allocateBuysByAccount/allocateSellsForAccountTicker의 불필요한
계좌 재그룹 로직도 함께 제거했다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Qi36d28hgnhCujutf162ys
EOF
)"
```

---

## Task 3: ManualTradingService 배정기/계획빌더 재사용 통합

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `StrategyOrderPlanBuilder.build(strategy, account, currentCycle, today, label, prevCloseCache)`, Task 2의 `TradingOrderBudgetAllocator.allocate(candidates, tradeDate)`, 기존 `BuyOrderPriceCapper.prepareForAllocation(orders, currentPrice, position, vrPosition, ticker, mode, tradeDate)`(Task 4에서 시그니처가 `mode`→`type`으로 바뀌므로 이 태스크는 **Task 4보다 먼저 실행해 현재 시그니처 그대로 사용**한다 — 아래 코드는 현재 `PriceCapMode` 시그니처 기준)
- Produces: 없음(리프 서비스)

- [ ] **Step 1: `ManualTradingService`에 신규 의존성 주입, 불필요해진 필드 제거**

`trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java`의 필드 목록(37-48번째 줄)을 다음으로 교체:
```java
class ManualTradingService {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final TradingPriceFetcher priceFetcher;
    private final StrategyOrderPlanBuilder planBuilder;
    private final BuyOrderPriceCapper priceCapper;
    private final TradingOrderBudgetAllocator budgetAllocator;
    private final CycleOrderStrategies cycleOrderStrategies;   // priceCapper.prepareForAllocation의 mode 조회용
    private final TradingOrderExecutor orderExecutor;
    private final BrokerAdapterRegistry registry;
    private final ApplicationEventPublisher eventPublisher; // live 잔고 조회 실패 시 관리자 알림 이벤트 (4xx라 GlobalExceptionHandler가 미기록)
```
(제거: `privacyTradePort`, `balanceLoader`, `orderComputer` — 이제 `planBuilder`가 이 3개 책임을 대신한다. 추가: `planBuilder`, `priceCapper`, `budgetAllocator`, `cycleOrderStrategies`.)
`import com.kista.matching.domain.strategy.CycleOrderStrategies;`를 상단 import에 추가한다(이미 `com.kista.matching.domain.strategy.CycleOrderStrategy` 등 와일드카드 없이 개별 import를 쓰고 있으므로 명시적으로 추가).

- [ ] **Step 2: `execute()`의 계획 계산 블록을 `planBuilder.build()` 호출로 교체**

`execute(UUID strategyId, UUID requesterId, DstInfo dst)`(55-108번째 줄)의 72-95번째 줄(전일종가 조회~예수금 검증)을 다음으로 교체:
```java
        LocalDate today = DstInfo.nextTradeDate();

        // 이중 실행 방지 — PLANNED 또는 PLACED 중 하나라도 있으면 거부
        if (!orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today).isEmpty())
            throw new ManualTradingException("오늘 이미 주문이 등록된 전략입니다");

        // 잔고 로드~전일종가~privacyBase~전략 계산을 배치와 동일한 StrategyOrderPlanBuilder에 위임한다
        StrategyOrderPlanBuilder.PlanResult result = planBuilder.build(strategy, account, currentCycle, today, account.nickname(), Map.of());
        if (result.isSkip()) return List.of(); // 전략 차원 skip (PRIVACY 기준매매표 미수신 등)
        CycleOrderStrategy.OrderPlan plan = result.plan();

        // 접수 전 가격 캡 적용 — 배치(TradingCandidatePlanner)와 동일 지점에서 동일 기준으로 적용해
        // 캡 적용 전 금액으로 검증하던 기존 버그(배치는 캡 적용 후 배정, 수동실행은 캡 적용 전 검증)를 없앤다
        BigDecimal startPrice = fetchStartPriceOrNull(strategy, account);
        List<PlannedOrder> preparedOrders = priceCapper.prepareForAllocation(
                plan.orders(), startPrice, plan.position(), plan.vrPosition(), strategy.ticker(),
                cycleOrderStrategies.of(strategy.type()).priceCapMode(), today);

        // 예산 배정기로 예수금/보유수량 검증 — 단건 candidate 하나만 넘긴다(Task 2가 단일계좌 전용으로 축소한 진입점)
        BatchContext ctx = new BatchContext(strategy, currentCycle, account,
                null /* userProfile: 알림 미사용 경로라 null — allocate()는 approved/rejected 판단에만 ctx.account() 사용 */);
        TradingOrderBudgetAllocator.Allocation allocation =
                budgetAllocator.allocate(List.of(new TradingOrderBudgetAllocator.Candidate(ctx, preparedOrders)), today);
        if (!allocation.rejectedBuy().isEmpty()) throw new ManualTradingException("예수금이 부족합니다");
        if (!allocation.rejectedSell().isEmpty()) throw new ManualTradingException("보유 수량이 부족합니다");
```
`BatchContext`의 `userProfile` 필드에 `null`을 넘겨도 되는지는 `allocate()`가 `ctx.userProfile()`을 참조하지 않는지 확인 필요 — `TradingOrderBudgetAllocator.allocate`/`allocateSells`/`allocateBuys`/`mergeApproved` 어디에서도 `ctx.userProfile()`을 호출하지 않으므로(Task 2에서 읽은 최종 코드 기준) 안전하다.

`fetchStartPriceOrNull` 헬퍼를 새로 추가한다(기존 `fetchPrevCloseOrThrow`가 전일종가만 반환했던 것과 달리, 가격 캡은 **현재가**가 필요하다 — 기존 코드에는 없던 개념이므로 신규 작성):
```java
    // BUY 가격 캡 판단용 현재가 — 조회 실패 시 캡 미적용(null이면 prepareForAllocation이 원본 그대로 반환)
    private BigDecimal fetchStartPriceOrNull(Strategy strategy, Account account) {
        try {
            return priceFetcher.fetchPrices(List.of(strategy.ticker()), account).get(strategy.ticker());
        } catch (Exception e) {
            log.warn("[{}] 캡 판단용 현재가 조회 실패 — 캡 미적용: {}", account.nickname(), e.getMessage());
            return null;
        }
    }
```

100-104번째 줄(`orderPlanner.savePlannedOrders(plan.orders(), ...)`와 `placeAtOpenOrdersIfMarketOpen(...)`)은 `plan.orders()` 대신 캡 적용된 `preparedOrders` 중 allocator가 승인한 목록(`allocation.approved()`에서 orders만 추출)을 저장하도록 바꾼다:
```java
        List<PlannedOrder> approvedOrders = allocation.approved().stream()
                .flatMap(candidate -> candidate.orders().stream())
                .toList();
        orderPlanner.savePlannedOrders(approvedOrders, account, currentCycle.id());

        // 개장 이후 수동 실행 시 AT_OPEN 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
        placeAtOpenOrdersIfMarketOpen(strategy, account, currentCycle.id(), today, plan.position(), plan.vrPosition(), dst);

        // 저장된 주문 반환 (UI에서 예약 확인용)
        return orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
```

- [ ] **Step 3: 이제 불필요해진 private 메서드 제거**

`checkSellableOrThrow`(142-154번째 줄)와 `fetchLiveBalanceOrThrow`(125-139번째 줄)는 배정기가 대체하므로 삭제한다. `fetchPrevCloseOrThrow`(111-122번째 줄)도 `planBuilder.build()`가 내부에서 전일종가 조회를 대신 수행하므로 삭제한다. `import com.kista.sharedkernel.TradingErrorEvent;`, `import com.kista.broker.domain.model.BrokerBalance;`, `import com.kista.broker.application.port.output.SellableQuantityPort;`, `import com.kista.privacy.application.port.output.PrivacyTradePort;` 등 더 이상 쓰이지 않는 import는 `./gradlew :trading-core:compileJava` 경고를 보고 정리한다.

- [ ] **Step 4: 좁힌 범위로 컴파일 확인**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: `ManualTradingServiceTest` 재작성 — setUp() 재구성**

`trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java`의 `@Mock` 목록(56-73번째 줄)에서 `cyclePositionPort`/`cyclePositionInfiniteDetailPort`/`strategyInfiniteDetailPort`/`liveBalancePort`/`sellableQuantityPort`/`strategyCycleVrPort`/`strategyVrDetailPort`는 그대로 유지(`planBuilder`/`priceCapper` 내부 조립에 여전히 필요), `privacyTradePort`도 유지(`planBuilder` 조립에 필요)한다. `setUp()`(95-143번째 줄)에서 `ManualTradingService` 직접 생성 대신 `StrategyOrderPlanBuilder`/`BuyOrderPriceCapper`/`TradingOrderBudgetAllocator`를 실제로 조립해서 주입하도록 바꾼다:
```java
    @BeforeEach
    void setUp() {
        TradingBalanceLoader balanceLoader = new TradingBalanceLoader(cyclePositionPort);
        ReverseInfiniteStrategy reverseStrategy = mock(ReverseInfiniteStrategy.class);
        PrivacyStrategy privacyStrategy = mock(PrivacyStrategy.class);
        CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(
                new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy),
                new PrivacyCycleOrderStrategy(privacyStrategy),
                new VrCycleOrderStrategy(vrStrategy)));
        CycleOrderComputer orderComputer = new CycleOrderComputer(
                cycleStrategies, cyclePositionPort, cyclePositionInfiniteDetailPort, strategyInfiniteDetailPort,
                strategyCycleVrPort, strategyVrDetailPort, orderPort);
        TradingOrderPlanner orderPlanner = new TradingOrderPlanner(orderPort);

        doReturn(kisPricePort).when(brokerAdapterRegistry).require(any(BrokerAccountRef.class), eq(BrokerPricePort.class));
        doReturn(liveBalancePort).when(brokerAdapterRegistry).require(any(BrokerAccountRef.class), eq(LiveBalancePort.class));

        TradingPriceFetcher priceFetcher = new TradingPriceFetcher(brokerAdapterRegistry, eventPublisher, privacyTradePort);
        StrategyOrderPlanBuilder planBuilder = new StrategyOrderPlanBuilder(
                balanceLoader, brokerAdapterRegistry, privacyTradePort, orderComputer, cycleStrategies);
        BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(
                orderPort, orderPlanner, infiniteStrategy, vrStrategy, strategyCyclePort);
        TradingOrderBudgetAllocator budgetAllocator = new TradingOrderBudgetAllocator(
                brokerAdapterRegistry, orderPort, cycleStrategies);

        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                priceFetcher, planBuilder, priceCapper, budgetAllocator, cycleStrategies,
                orderExecutor, brokerAdapterRegistry, eventPublisher);

        lenient().when(brokerAdapterRegistry.require(any(), eq(SellableQuantityPort.class)))
                .thenReturn(sellableQuantityPort);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), any()))
                .thenReturn(new SellableQuantity("SOXL", 100));

        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), REQUESTER_ID)).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.requireLatestByStrategyId(STRATEGY.id())).thenReturn(CYCLE);
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        lenient().when(cyclePositionPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(HISTORY));
        lenient().when(cyclePositionPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of(HISTORY));
        lenient().when(cyclePositionInfiniteDetailPort.findLatestByCycleId(eq(CYCLE.id()), anyInt())).thenReturn(List.of());
        lenient().when(strategyInfiniteDetailPort.findByStrategyVersionId(STRATEGY_VERSION_ID))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        lenient().when(strategyInfiniteDetailPort.findActiveByStrategyId(STRATEGY.id()))
                .thenReturn(Optional.of(new StrategyInfiniteDetail(STRATEGY_VERSION_ID, 40)));
        lenient().when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new PriceSnapshot(new BigDecimal("22.00"), new BigDecimal("20.00"))));
        // 캡 판단용 현재가 — 기본값은 캡이 트리거되지 않는 낮은 가격(개별 테스트가 필요 시 override)
        lenient().when(kisPricePort.getPrices(anyList(), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("20.00")));
    }
```
(`StrategyOrderPlanBuilder` 생성자 인자 순서는 이 계획의 Task 1 이전 원본 그대로: `(balanceLoader, registry, privacyTradePort, orderComputer, cycleOrderStrategies)` — `StrategyOrderPlanBuilder.java` 29-35번째 줄 필드 선언 순서와 일치시킨다.)

- [ ] **Step 6: 기존 4개 테스트를 새 캡 판단용 현재가 stub에 맞게 조정**

`execute_insufficientSellHoldings_throwsManualTradingException`(146-163번째 줄)·`execute_existingReservedSellExceedsAvailable_rejects`(184-205번째 줄)는 SELL 검증만 다루므로 setUp()의 기본 `getPrices` stub만으로 통과해야 한다(수정 불필요, 그대로 둔다).

`execute_liveBalanceFetchFails_notifiesAdminAndThrowsManualTradingException`(165-182번째 줄)는 `liveBalancePort.getLiveBalance(...)`가 예외를 던지는 케이스인데, 이제 이 조회는 `budgetAllocator.allocate()` 내부(`fetchQuote`)에서 발생한다 — `allocate()`가 예외를 그대로 던지므로(Task 2에서 try/catch 제거) `ManualTradingService.execute()`가 이를 잡아 `ManualTradingException`으로 래핑하고 `TradingErrorEvent`를 발행하는 코드가 여전히 필요하다. `execute()`에 다음 try/catch를 `budgetAllocator.allocate(...)` 호출에 추가한다(Step 2의 코드를 아래로 교체):
```java
        TradingOrderBudgetAllocator.Allocation allocation;
        try {
            allocation = budgetAllocator.allocate(
                    List.of(new TradingOrderBudgetAllocator.Candidate(ctx, preparedOrders)), today);
        } catch (Exception e) {
            log.warn("[{}] 예산 배정 조회 실패 — 바로주문 중단: account={}, ticker={}, error={}",
                    account.nickname(), account.id(), strategy.ticker().name(), e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
```
(이 테스트는 수정 없이 그대로 통과해야 한다 — `verify(eventPublisher).publishEvent(any(TradingErrorEvent.class));` assertion 유지.)

`execute_sufficientBalance_savesOrders`(207-233번째 줄)는 수정 없이 통과해야 한다(BUY $20 << live $10,000, 캡도 트리거 안 됨).

VR 관련 3개 테스트(`execute_vrStrategy_savesLimitAtOpenOrders`/`execute_vrStrategy_marketOpen_placesAtOpenOrdersWithCorrectArguments`/`execute_vrStrategy_marketClosed_doesNotPlaceAtOpenOrders`)는 `setUpVrManualExecution()`(239-296번째 줄)이 `liveBalancePort`/`orderPort.sumPlannedBuyByAccountAndDate` 등을 이미 충분한 값으로 stub하고 있으므로 캡 적용(`prepareForAllocation`)과 배정(`allocate`)을 거쳐도 승인돼야 한다 — 수정 없이 통과해야 한다. 단, `execute_vrStrategy_marketOpen_placesAtOpenOrdersWithCorrectArguments`가 stub하는 `kisPricePort.getPrices(eq(List.of(StrategyTicker.SOXL)), eq(ACCOUNT_REF))`(326-327번째 줄, 21.00 반환)는 `placeAtOpenOrdersIfMarketOpen` 내부 재조회용이라 그대로 두되, 새로 추가된 `fetchStartPriceOrNull`의 `getPrices` 호출과 인자 매처가 겹쳐 `UnnecessaryStubbingException`이 나지 않는지 Step 7에서 실행 결과로 확인한다.

- [ ] **Step 7: 신규 테스트 — 캡 적용 전/후 경계값 회귀**

`ManualTradingServiceTest.java`에 다음 테스트를 추가한다(기존 `execute_sufficientBalance_savesOrders` 뒤):
```java
    // 캡 적용 전 금액으로는 예수금 부족이지만 캡 적용 후 금액으로는 충분한 경계 케이스 —
    // 수동실행이 배치와 동일하게 "캡 적용 후" 금액으로 검증하도록 바뀐 것을 고정하는 회귀 테스트
    @Test
    void execute_buyPriceExceedsCapButCappedAmountFitsBudget_savesOrders() {
        // 원가 100.00×1주=100.00은 live usdDeposit(60.00)을 초과하지만,
        // 현재가 50.00 기준 캡(52.50)으로 보정된 뒤 금액(52.50)은 예산 안에 든다
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("100.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        // InfinitePosition 캡 재산정 — 1주 그대로, 가격만 cap(52.50)으로 교체
        when(infiniteStrategy.buildCappedBuyOrders(any(InfinitePosition.class), any(LocalDate.class), anyList(), eq(new BigDecimal("52.50"))))
                .thenAnswer(invocation -> {
                    List<PlannedOrder> original = invocation.getArgument(2);
                    return original.stream().map(o -> o.withPrice(new BigDecimal("52.50"))).toList();
                });
        // 캡 판단용 현재가 50.00 → cap = 52.50
        when(kisPricePort.getPrices(eq(List.of(StrategyTicker.SOXL)), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("50.00")));
        // live 잔고: holdings=10, usdDeposit=60.00 — 캡 전(100.00)은 부족, 캡 후(52.50)는 충분
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("60.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);
        Order savedOrder = new Order(UUID.randomUUID(), ACCOUNT.id(), CYCLE.id(), LocalDate.now(),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("52.50"), OrderStatus.PLANNED, null, null, null);
        lenient().when(orderPort.findPlannedByCycleAndDate(eq(CYCLE.id()), any())).thenReturn(List.of());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(CYCLE.id()), any()))
                .thenReturn(List.of(), List.of(savedOrder));

        List<Order> orders = service.execute(STRATEGY.id(), REQUESTER_ID);

        verify(orderPort).saveAll(argThat(saved -> saved.stream()
                .anyMatch(o -> o.price().compareTo(new BigDecimal("52.50")) == 0)));
        assertThat(orders).hasSize(1);
    }

    // 캡을 적용해도 여전히 예산을 초과하면 거부되어야 한다(캡이 항상 통과시키는 것은 아님을 확인)
    @Test
    void execute_cappedAmountStillExceedsBudget_throwsManualTradingException() {
        Order buyTemplate = new Order(null, null, null, LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LOC, OrderTiming.AT_CLOSE,
                OrderDirection.BUY, 1, new BigDecimal("100.00"),
                OrderStatus.PLANNED, null, null, null);
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buyTemplate.toPlanned()));
        when(infiniteStrategy.buildCappedBuyOrders(any(InfinitePosition.class), any(LocalDate.class), anyList(), eq(new BigDecimal("52.50"))))
                .thenAnswer(invocation -> {
                    List<PlannedOrder> original = invocation.getArgument(2);
                    return original.stream().map(o -> o.withPrice(new BigDecimal("52.50"))).toList();
                });
        when(kisPricePort.getPrices(eq(List.of(StrategyTicker.SOXL)), eq(ACCOUNT_REF)))
                .thenReturn(Map.of(StrategyTicker.SOXL, new BigDecimal("50.00")));
        // live usdDeposit=10.00 — 캡 후 금액(52.50)조차 초과
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT_REF), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(10, new BigDecimal("20.00"), new BigDecimal("10.00")));
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any())).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.execute(STRATEGY.id(), REQUESTER_ID))
                .isInstanceOf(ManualTradingException.class)
                .hasMessage("예수금이 부족합니다");

        verify(orderPort, never()).saveAll(anyList());
    }
```

- [ ] **Step 8: 테스트 실행**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.ManualTradingServiceTest'`
Expected: BUILD SUCCESSFUL, 기존 7개 + 신규 2개 전부 PASS

- [ ] **Step 9: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java \
        trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): 수동실행이 배치와 동일한 계획빌더·배정기를 재사용

ManualTradingService.execute()가 잔고로드~compute~예수금검증을 직접
재구현하던 것을 StrategyOrderPlanBuilder/TradingOrderBudgetAllocator
재사용으로 교체했다. 부수적으로 캡 적용 전 금액으로 검증하던 기존
불일치(배치는 캡 적용 후 배정)도 함께 고쳤다 — 회귀 테스트로 고정.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Qi36d28hgnhCujutf162ys
EOF
)"
```

---

## Task 4: PriceCapMode → CycleOrderStrategy capability 편입

**Files:**
- Modify: `trading-core/src/main/java/com/kista/matching/domain/strategy/CycleOrderStrategy.java`
- Modify: `trading-core/src/main/java/com/kista/matching/domain/strategy/InfiniteCycleOrderStrategy.java`
- Modify: `trading-core/src/main/java/com/kista/matching/domain/strategy/VrCycleOrderStrategy.java`
- Modify: `trading-core/src/main/java/com/kista/matching/domain/strategy/PrivacyCycleOrderStrategy.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/BuyOrderPriceCapper.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java` (Task 2에서 이미 읽은 148-150번째 줄 호출부의 `mode` 인자만 `type`으로 교체)
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java` (Task 3에서 넘긴 `mode` 인자를 `type`으로 교체)
- Test: `trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java` (Step 마지막에 1줄만 수정)

**Interfaces:**
- Consumes: 없음(이 태스크가 최상위 캡슐화)
- Produces: `CycleOrderStrategy.capBuyOrders(List<PlannedOrder>, BigDecimal, InfinitePosition, VrPosition, StrategyTicker, LocalDate) -> List<PlannedOrder>`, `CycleOrderStrategy.capsIndividualOrders() -> boolean`, `CycleOrderStrategy.needsCapCheck(InfinitePosition, VrPosition) -> boolean` — 이후 태스크 없음(마지막 태스크)

> **설계 보강 — 스펙 문서와의 차이:** 스펙(`docs/superpowers/specs/2026-09-26-trading-execution-path-dedup-design.md` Section 3)은 `capBuys(orders, cap, ...)` 단일 메서드만 제안했지만, 실제 코드를 읽어보니 `BuyOrderPriceCapper.capIfNeeded`가 (a) PRIVACY는 "개별 초과 주문만 취소·재저장", INFINITE/VR은 "전체 취소·전체 재저장"이라는 **DB 행 단위** 차이를 갖고 있고(스펙이 보존을 명시한 부분), (b) `@Transactional` 오픈 자체를 막기 위해 `TradingOrderExecutor.applyCap`이 `position`/`vrPosition` null을 사전 가드하는 성능 최적화가 있다(주석 참고). 순수 계산 메서드 하나로는 이 두 가지를 동시에 만족시킬 수 없어 `capsIndividualOrders()`(DB 행 단위 결정)와 `needsCapCheck()`(트랜잭션 오픈 전 사전 가드) 2개를 추가했다. `CycleOrderStrategy`는 `com.kista.matching` 소속으로 `OrderPort`/`@Transactional` 등 trading 모듈 인프라를 참조할 수 없으므로(ArchUnit `matching_must_not_depend_on_other_modules`), 이 2개도 순수 boolean 반환 capability로 유지했다.

- [ ] **Step 1: `CycleOrderStrategy`에 신규 capability 메서드 3개 추가, `PriceCapMode`/`priceCapMode()` 제거**

`trading-core/src/main/java/com/kista/matching/domain/strategy/CycleOrderStrategy.java`의 53-57번째 줄(`PriceCapMode` enum + `priceCapMode()`)을 다음으로 교체:
```java
    // 캡 초과 BUY 재산정 — INFINITE/VR은 사다리 전체 재구성(개수·구성이 원본과 달라질 수 있음),
    // PRIVACY는 개별 주문 가격만 치환(개수·순서 불변). 캡 미적용 대상(position/vrPosition 없음, 부트스트랩 등)이면
    // 입력을 그대로(참조 동일) 반환한다 — 호출부가 이 "변경 없음"을 감지해 취소·재저장을 건너뛴다.
    default List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                             InfinitePosition position, VrPosition vrPosition,
                                             StrategyTicker ticker, LocalDate tradeDate) {
        return buyOrders;
    }

    // true: capBuyOrders 결과를 buyOrders와 인덱스별로 비교해 값이 바뀐 주문만 개별 취소·재저장(PRIVACY)
    // false: buyOrders 전체를 한 번에 취소하고 결과 전체를 재저장(INFINITE/VR — 사다리 재구성은 개별 대응이 무의미)
    default boolean capsIndividualOrders() { return false; }

    // 가격 캡 로직을 시도하기 전에 필요한 입력(position/vrPosition)이 갖춰졌는지 여부 —
    // false면 호출부가 DB 조회·트랜잭션을 열지 않고 즉시 스킵한다(스케쥴러 매 틱 빈 트랜잭션 방지)
    default boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) { return true; }
```
같은 파일 상단 import에 `import java.math.BigDecimal;`(이미 있음, 4번째 줄 확인), `import java.time.LocalDate;`(이미 있음, 11번째 줄)가 이미 존재하므로 추가 import는 불필요하다.

- [ ] **Step 2: `InfiniteCycleOrderStrategy`에 캡 로직 이동**

`trading-core/src/main/java/com/kista/matching/domain/strategy/InfiniteCycleOrderStrategy.java`의 52-53번째 줄(`priceCapMode()`)을 삭제하고 다음을 추가(파일 하단 `minRequiredDeposit` 메서드 뒤):
```java
    @Override
    public List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                            InfinitePosition position, VrPosition vrPosition,
                                            StrategyTicker ticker, LocalDate tradeDate) {
        if (position == null) return buyOrders;
        return infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap);
    }

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) {
        return position != null;
    }
```
`import com.kista.matching.domain.model.VrPosition;`을 상단 import에 추가한다(현재 파일은 `InfinitePosition`/`ReverseModePosition`만 import하고 있음).

- [ ] **Step 3: `VrCycleOrderStrategy`에 캡 로직 이동, `isVrBootstrapShaped` 이관**

`trading-core/src/main/java/com/kista/matching/domain/strategy/VrCycleOrderStrategy.java`의 48-50번째 줄(`priceCapMode()`)을 삭제하고 다음을 추가(파일 하단):
```java
    @Override
    public List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                            InfinitePosition position, VrPosition vrPosition,
                                            StrategyTicker ticker, LocalDate tradeDate) {
        if (vrPosition == null || isVrBootstrapShaped(buyOrders)) return buyOrders;
        return vrStrategy.buildCappedBuyOrders(vrPosition, ticker, tradeDate, cap);
    }

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) {
        return vrPosition != null;
    }

    // VR bootstrap 주문(LOC+AT_CLOSE)은 사다리 재산정 대상이 아니다 — BuyOrderPriceCapper에서 이동.
    // VrStrategy.buildOrders()는 holdings=0에서 첫 포지션을 못 만든 상태(needsBootstrap)면 bootstrap
    // 주문만 단독 반환하지만, holdings>0인데 사다리 첫 유효 단조차 예산 초과인 드리프트 상태에서는
    // bootstrap BUY(LOC+AT_CLOSE)와 정상 매도 사다리(LIMIT+AT_OPEN)가 같은 배치에 섞여 반환될 수 있다.
    // 사다리 매수는 항상 LIMIT+AT_OPEN이므로, BUY 중 하나라도 LOC이면 이번 배치의 매수가 bootstrap이라는 뜻이다.
    private static boolean isVrBootstrapShaped(List<PlannedOrder> buyOrders) {
        return buyOrders.stream().anyMatch(o -> o.orderType() == com.kista.sharedkernel.OrderType.LOC);
    }
```
`import com.kista.matching.domain.model.InfinitePosition;`을 상단 import에 추가한다(현재 파일은 `VrPosition`만 import).

- [ ] **Step 4: `PrivacyCycleOrderStrategy`에 캡 로직 이동**

`trading-core/src/main/java/com/kista/matching/domain/strategy/PrivacyCycleOrderStrategy.java`의 26-27번째 줄(`priceCapMode()`)을 삭제하고 다음을 추가(파일 하단):
```java
    @Override
    public List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                            InfinitePosition position, VrPosition vrPosition,
                                            StrategyTicker ticker, LocalDate tradeDate) {
        return buyOrders.stream()
                .map(o -> o.price().compareTo(cap) > 0 ? o.withPrice(cap) : o)
                .toList();
    }

    @Override
    public boolean capsIndividualOrders() { return true; }
```
`import com.kista.matching.domain.model.InfinitePosition;`과 `import com.kista.matching.domain.model.VrPosition;`을 상단 import에 추가한다(현재 파일은 둘 다 import하지 않음).

- [ ] **Step 5: `BuyOrderPriceCapper` 재작성**

`trading-core/src/main/java/com/kista/trading/application/service/BuyOrderPriceCapper.java`를 다음과 같이 재작성한다. 필드(38-44번째 줄)에서 `InfiniteStrategy infiniteStrategy`/`VrStrategy vrStrategy`를 `CycleOrderStrategies cycleOrderStrategies`로 교체:
```java
class BuyOrderPriceCapper {

    private final OrderPort orderPort;
    private final TradingOrderPlanner orderPlanner;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final StrategyCyclePort strategyCyclePort;
```
`prepareForAllocation`(46-75번째 줄)을 다음으로 교체:
```java
    // 신규 후보의 최종 BUY를 allocator 입력 전에 계산하며 영속화는 수행하지 않는다
    List<PlannedOrder> prepareForAllocation(List<PlannedOrder> orders, BigDecimal currentPrice, InfinitePosition position,
                                     VrPosition vrPosition, StrategyTicker ticker,
                                     StrategyType type, LocalDate tradeDate) {
        if (currentPrice == null) return orders;
        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<PlannedOrder> buyOrders = orders.stream().filter(order -> order.direction() == BUY).toList();
        if (buyOrders.stream().noneMatch(order -> order.price().compareTo(cap) > 0)) return orders;

        List<PlannedOrder> cappedBuys = cycleOrderStrategies.of(type).capBuyOrders(buyOrders, cap, position, vrPosition, ticker, tradeDate);
        return PriceCapPolicy.replaceBuysPreservingOrder(orders, cappedBuys);
    }
```
`capIfNeeded`(83-100번째 줄)와 `applyPrivacyCap`(103-116번째 줄), `applyCapIfNeeded` 2개 오버로드(140-154, 151-178번째 줄)를 다음으로 교체:
```java
    // 전략별 BUY 가격 캡 진입점 단일화 — capsIndividualOrders()로 DB 행 단위 취소·재저장 방식을 결정한다.
    // capIfNeeded는 @Transactional이라 needsCapCheck() 가드는 TradingOrderExecutor.applyCap에서 미리 걸러
    // 스킵 케이스마다 빈 트랜잭션이 열리지 않도록 한다.
    @Transactional
    void capIfNeeded(StrategyType type, boolean atOpen, LocalDate date, Account account, UUID strategyCycleId,
                     BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, StrategyTicker ticker) {
        strategyCyclePort.lockForUpdate(strategyCycleId); // 동일 사이클 동시 보정 직렬화
        List<Order> buyOrders = loadBuyOrders(strategyCycleId, date, atOpen);
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = PriceCapPolicy.capFor(currentPrice);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        if (plannedBuyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        CycleOrderStrategy strategy = cycleOrderStrategies.of(type);
        List<PlannedOrder> corrected = strategy.capBuyOrders(plannedBuyOrders, cap, position, vrPosition, ticker, date);

        if (strategy.capsIndividualOrders()) {
            applyIndividualCap(account, strategyCycleId, buyOrders, plannedBuyOrders, corrected);
        } else {
            applyBatchCap(account, strategyCycleId, buyOrders, plannedBuyOrders, corrected);
        }
    }

    // PRIVACY 전용 — 값이 바뀐 행만 취소·재저장(변하지 않은 행은 DB에 그대로 둔다)
    private void applyIndividualCap(Account account, UUID strategyCycleId, List<Order> buyOrders,
                                    List<PlannedOrder> plannedBuyOrders, List<PlannedOrder> corrected) {
        List<PlannedOrder> changed = new ArrayList<>();
        for (int i = 0; i < buyOrders.size(); i++) {
            if (!plannedBuyOrders.get(i).equals(corrected.get(i))) {
                orderPort.markCancelled(buyOrders.get(i).id());
                changed.add(corrected.get(i));
            }
        }
        if (changed.isEmpty()) return;
        log.info("[{}] BUY 가격 보정 필요 — 개별 보정 주문: {}", account.nickname(), describePlannedOrders(changed));
        orderPlanner.savePlannedOrders(changed, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료(개별)", account.nickname());
    }

    // INFINITE/VR 전용 — 사다리 전체를 취소하고 재산정 결과 전체를 재저장(개별 행 대응이 무의미)
    private void applyBatchCap(Account account, UUID strategyCycleId, List<Order> buyOrders,
                               List<PlannedOrder> plannedBuyOrders, List<PlannedOrder> corrected) {
        if (plannedBuyOrders.equals(corrected)) return; // 예: VR bootstrap — 캡 재산정 대상 아님
        log.info("[{}] BUY 가격 보정 필요 — 원래 주문: {}", account.nickname(), describePlannedOrders(plannedBuyOrders));
        buyOrders.forEach(o -> orderPort.markCancelled(o.id()));
        if (corrected.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(corrected, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료(전체) — 보정 주문: {}", account.nickname(), describePlannedOrders(corrected));
    }
```
`loadBuyOrders`(132-137번째 줄)와 `describeOrders`/`describePlannedOrders`(180-187번째 줄)는 그대로 둔다. `describeOrders`(`List<Order>` 버전)는 더 이상 호출부가 없으므로 삭제한다. `import com.kista.matching.domain.strategy.InfiniteStrategy;`/`import com.kista.matching.domain.strategy.VrStrategy;`는 제거하고 `import com.kista.matching.domain.strategy.CycleOrderStrategies;`를 추가한다. `import com.kista.sharedkernel.StrategyType;`도 추가한다(이미 29번째 줄에 `import com.kista.sharedkernel.StrategyTicker;`가 있으므로 그 근처).

- [ ] **Step 6: `TradingOrderExecutor.applyCap` 단순화**

`trading-core/src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java`의 69-77번째 줄을 다음으로 교체:
```java
    private void applyCap(boolean atOpen, LocalDate date, Account account, UUID strategyCycleId,
                          BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        if (currentPrice == null) return;
        CycleOrderStrategy orderStrategy = cycleOrderStrategies.of(strategy.type());
        if (!orderStrategy.needsCapCheck(position, vrPosition)) return;
        buyOrderPriceCapper.capIfNeeded(strategy.type(), atOpen, date, account, strategyCycleId, currentPrice, position, vrPosition, strategy.ticker());
    }
```

- [ ] **Step 7: 호출부의 `mode` 인자를 `type`으로 교체**

`trading-core/src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java`의 148-150번째 줄:
```java
        List<PlannedOrder> preparedOrders = priceCapper.prepareForAllocation(
                planOpt.get().orders(), price, planOpt.get().position(), planOpt.get().vrPosition(), strategy.ticker(),
                strategy.type(), tradeDate);
```
`trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java`(Task 3에서 작성한 `prepareForAllocation` 호출부)의 `cycleOrderStrategies.of(strategy.type()).priceCapMode()` 부분을 `strategy.type()`로 교체:
```java
        List<PlannedOrder> preparedOrders = priceCapper.prepareForAllocation(
                plan.orders(), startPrice, plan.position(), plan.vrPosition(), strategy.ticker(),
                strategy.type(), today);
```
`cycleOrderStrategies` 필드는 이제 이 호출 하나만을 위해 존재했는데 더 이상 쓰이지 않는다 — `grep -n "cycleOrderStrategies" trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java`로 다른 참조가 없음을 확인한 뒤 필드 목록(Task 3 Step 1에서 추가한 줄)에서 제거해 최종 필드 목록을 다음으로 맞춘다:
```java
class ManualTradingService {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final TradingPriceFetcher priceFetcher;
    private final StrategyOrderPlanBuilder planBuilder;
    private final BuyOrderPriceCapper priceCapper;
    private final TradingOrderBudgetAllocator budgetAllocator;
    private final TradingOrderExecutor orderExecutor;
    private final BrokerAdapterRegistry registry;
    private final ApplicationEventPublisher eventPublisher; // live 잔고 조회 실패 시 관리자 알림 이벤트 (4xx라 GlobalExceptionHandler가 미기록)
```
`import com.kista.matching.domain.strategy.CycleOrderStrategies;`도 함께 제거한다(Task 3 Step 1에서 추가했던 것).

`ManualTradingServiceTest.java`의 `setUp()`(Task 3 Step 5에서 작성한 생성자 호출)도 인자 수가 12개에서 11개로 줄어들므로 함께 갱신한다:
```java
        service = new ManualTradingService(
                strategyPort, strategyCyclePort, accountPort, orderPort,
                priceFetcher, planBuilder, priceCapper, budgetAllocator,
                orderExecutor, brokerAdapterRegistry, eventPublisher);
```
(`cycleStrategies` 지역변수 자체는 `CycleOrderComputer`/`StrategyOrderPlanBuilder`/`TradingOrderBudgetAllocator` 조립에 여전히 필요하므로 `setUp()` 앞부분의 `CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(...)` 선언은 그대로 둔다 — `ManualTradingService` 생성자 호출에서만 빠진다.)

**추가로 놓치기 쉬운 지점**: 같은 `setUp()` 안에서 `BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(orderPort, orderPlanner, infiniteStrategy, vrStrategy, strategyCyclePort);`(Task 3 Step 5에서 작성)도 이 태스크(Step 5)에서 `BuyOrderPriceCapper`의 생성자를 `(OrderPort, TradingOrderPlanner, CycleOrderStrategies, StrategyCyclePort)` 4-인자로 바꿨으므로 함께 갱신해야 한다 — 그대로 두면 컴파일 오류다:
```java
        BuyOrderPriceCapper priceCapper = new BuyOrderPriceCapper(
                orderPort, orderPlanner, cycleStrategies, strategyCyclePort);
```

- [ ] **Step 8: 좁힌 범위로 컴파일 확인**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: `BuyOrderPriceCapperTest` 재작성 — mock 대상을 `CycleOrderStrategy`로 교체**

`trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java`의 목적은 파일 헤더 주석(40-42번째 줄)에 명시된 대로 "I/O 오케스트레이션만 검증"이다 — 캡 계산 자체(`buildCappedBuyOrders`)의 정확성은 `InfiniteStrategyTypeTest`/`VrStrategyTypeTest`가 담당하므로, 이 파일은 실제 `InfiniteCycleOrderStrategy`/`VrCycleOrderStrategy`/`PrivacyCycleOrderStrategy`를 실제 커널(mock된 `InfiniteStrategy`/`VrStrategy`)과 함께 조립해서 쓰던 기존 패턴 대신, `CycleOrderStrategy`를 직접 mock하고 `capBuyOrders`/`capsIndividualOrders`를 stub하는 방식으로 바꾼다(`TradingOrderBudgetAllocatorTest`가 이미 이 패턴을 쓰고 있음).

파일 전체를 다음으로 교체한다:
```java
package com.kista.trading.application.service;
import com.kista.trading.application.service.support.TradingOrderPlanner;

import com.kista.sharedkernel.OrderStatus;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.matching.domain.model.VrPosition;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;

// BUY PLANNED 가격이 currentPrice × 1.05 초과 시 전략별 재산정 위임 후 영속화 — I/O 오케스트레이션만 검증
// 캡 가격 재산정 공식(병합/보정 등)은 InfiniteStrategyTypeTest.buildCappedBuyOrders / VrStrategyTypeTest.buildCappedBuyOrders 참고
// CycleOrderStrategy 자체를 mock해 capBuyOrders/capsIndividualOrders만 stub한다 — 실제 계산 로직 검증은 이 파일의 책임이 아니다
@ExtendWith(MockitoExtension.class)
class BuyOrderPriceCapperTest {

    @Mock OrderPort orderPort;
    @Mock TradingOrderPlanner orderPlanner;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock CycleOrderStrategy infiniteType;
    @Mock CycleOrderStrategy privacyType;
    @Mock CycleOrderStrategy vrType;
    @Captor ArgumentCaptor<List<PlannedOrder>> ordersCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Broker.KIS, null);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), StrategyTicker.SOXL, new BigDecimal("10.00"), 20);

    static final VrPosition VR_POSITION = new VrPosition(
            new AccountBalance(1, new BigDecimal("100.00"), new BigDecimal("5000.00")),
            new BigDecimal("10000.00"), new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);

    BuyOrderPriceCapper capper;

    @BeforeEach
    void setUp() {
        lenient().when(infiniteType.cycleType()).thenReturn(StrategyType.INFINITE);
        lenient().when(privacyType.cycleType()).thenReturn(StrategyType.PRIVACY);
        lenient().when(vrType.cycleType()).thenReturn(StrategyType.VR);
        lenient().when(privacyType.capsIndividualOrders()).thenReturn(true);
        CycleOrderStrategies cycleOrderStrategies = new CycleOrderStrategies(List.of(infiniteType, privacyType, vrType));
        capper = new BuyOrderPriceCapper(orderPort, orderPlanner, cycleOrderStrategies, strategyCyclePort);
    }

    private Order buy(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, quantity, new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    private Order sell(String price, int quantity) {
        return new Order(null, null, null, TODAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.SELL, quantity, new BigDecimal(price), OrderStatus.PLANNED, null, null, null);
    }

    // ─── prepareForAllocation ─────────────────────────────────────────────

    @Test
    void prepareForAllocation_infiniteCap_returnsCappedBuysAndCorrectionsWithoutPersistence() {
        PlannedOrder originalBuy = buy("60.00", 1).toPlanned();
        PlannedOrder cappedBuy = buy("52.50", 9).toPlanned();
        PlannedOrder correction = buy("50.00", 1).toPlanned();
        when(infiniteType.capBuyOrders(eq(List.of(originalBuy)), eq(new BigDecimal("52.50")),
                eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(cappedBuy, correction));

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                List.of(originalBuy), new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL,
                StrategyType.INFINITE, TODAY);

        assertThat(prepared).containsExactly(cappedBuy, correction);
        verifyNoInteractions(orderPort, orderPlanner);
    }

    @Test
    void prepareForAllocation_privacyCap_changesOnlyExceedingBuyPrices() {
        PlannedOrder exceedingBuy = buy("40.00", 5).toPlanned();
        PlannedOrder sell = sell("45.00", 2).toPlanned();
        PlannedOrder withinCapBuy = buy("28.00", 3).toPlanned();
        when(privacyType.capBuyOrders(eq(List.of(exceedingBuy, withinCapBuy)), eq(new BigDecimal("31.50")),
                isNull(), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(exceedingBuy.withPrice(new BigDecimal("31.50")), withinCapBuy));

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                List.of(exceedingBuy, sell, withinCapBuy), new BigDecimal("30.00"), null, null, StrategyTicker.SOXL,
                StrategyType.PRIVACY, TODAY);

        assertThat(prepared.get(0).price()).isEqualByComparingTo("31.50");
        assertThat(prepared.get(0).quantity()).isEqualTo(5);
        assertThat(prepared.get(1)).isSameAs(sell);
        assertThat(prepared.get(2)).isSameAs(withinCapBuy);
        verifyNoInteractions(orderPort, orderPlanner);
    }

    @Test
    void prepareForAllocation_noCapReturnsOriginalOrders() {
        List<PlannedOrder> orders = List.of(buy("60.00", 1).toPlanned(), sell("70.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                orders, new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL, StrategyType.INFINITE, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner);
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
    }

    @Test
    void prepareForAllocation_currentPriceNull_returnsOriginalOrders() {
        List<PlannedOrder> orders = List.of(buy("60.00", 1).toPlanned(), sell("70.00", 1).toPlanned());

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                orders, null, POSITION, null, StrategyTicker.SOXL, StrategyType.INFINITE, TODAY);

        assertThat(prepared).isSameAs(orders);
        verifyNoInteractions(orderPort, orderPlanner, infiniteType);
    }

    @Test
    void prepareForAllocation_vrCap_returnsCappedBuysWithoutPersistence() {
        PlannedOrder originalBuy = buy("8500.00", 1).toPlanned();
        PlannedOrder sell = sell("11500.00", 1).toPlanned();
        PlannedOrder cappedBuy = buy("525.00", 2).toPlanned();
        when(vrType.capBuyOrders(eq(List.of(originalBuy)), eq(new BigDecimal("52.50")),
                isNull(), eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY)))
                .thenReturn(List.of(cappedBuy));

        List<PlannedOrder> prepared = capper.prepareForAllocation(
                List.of(originalBuy, sell), new BigDecimal("50.00"), null, VR_POSITION, StrategyTicker.TQQQ,
                StrategyType.VR, TODAY);

        assertThat(prepared).containsExactly(cappedBuy, sell);
        verifyNoInteractions(orderPort, orderPlanner);
    }

    // ─── capIfNeeded — INFINITE/VR(전체 취소·재저장) ────────────────────────

    @Test
    void capIfNeeded_noBuyOrders_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capIfNeeded_allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.05 = 52.50 — 모든 BUY가 cap 이하라 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 18)));

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(infiniteType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capIfNeeded_bootstrapUnchangedResult_skipsCorrectionEntirely() {
        // VR bootstrap 등 capBuyOrders가 입력을 그대로 반환하는 경우 — 취소·재저장 전혀 발생하지 않아야 한다
        List<Order> buyOrders = List.of(buy("8500.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        when(vrType.capBuyOrders(eq(plannedBuyOrders), any(), isNull(), eq(VR_POSITION), eq(StrategyTicker.TQQQ), eq(TODAY)))
                .thenReturn(plannedBuyOrders); // 변경 없음 — bootstrap 스킵을 흉내

        capper.capIfNeeded(StrategyType.VR, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("90.00"), null, VR_POSITION, StrategyTicker.TQQQ);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void buysExceedCap_delegatesToStrategyAndPersistsResult() {
        // cap = 50 × 1.05 = 52.50
        List<Order> buyOrders = List.of(buy("60.00", 1), buy("52.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        List<PlannedOrder> capped = List.of(buy("52.50", 9).toPlanned(), buy("52.00", 11).toPlanned());
        when(infiniteType.capBuyOrders(eq(plannedBuyOrders), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(capped);

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL);

        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        inOrder.verify(orderPort, times(2)).markCancelled(isNull()); // 테스트 buy()의 id=null
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        assertThat(ordersCaptor.getValue()).isEqualTo(capped);
    }

    @Test
    void cappedResultEmpty_deletesWithoutSaving() {
        List<Order> buyOrders = List.of(buy("200.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        when(infiniteType.capBuyOrders(eq(plannedBuyOrders), any(), eq(POSITION), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of());

        capper.capIfNeeded(StrategyType.INFINITE, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), POSITION, null, StrategyTicker.SOXL);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(orderPort).markCancelled(isNull());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    // ─── capIfNeeded — PRIVACY(개별 취소·재저장) ─────────────────────────────

    @Test
    void capPrivacyIfNeeded_buysExceedCap_capsToCurrentPriceX105KeepingQuantity() {
        // currentPrice=30, cap=31.50 — FIDA 가격 40.00만 cap 초과, 28.00은 cap 이하라 그대로 유지
        List<Order> buyOrders = List.of(buy("40.00", 5), buy("28.00", 3));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<PlannedOrder> plannedBuyOrders = buyOrders.stream().map(Order::toPlanned).toList();
        PlannedOrder cappedFirst = plannedBuyOrders.get(0).withPrice(new BigDecimal("31.50"));
        when(privacyType.capBuyOrders(eq(plannedBuyOrders), eq(new BigDecimal("31.50")), isNull(), isNull(), eq(StrategyTicker.SOXL), eq(TODAY)))
                .thenReturn(List.of(cappedFirst, plannedBuyOrders.get(1))); // 두 번째는 변경 없음(동일 값)

        capper.capIfNeeded(StrategyType.PRIVACY, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("30.00"), null, null, StrategyTicker.SOXL);

        InOrder inOrder = inOrder(strategyCyclePort, orderPort, orderPlanner);
        inOrder.verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        inOrder.verify(orderPort).findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY);
        inOrder.verify(orderPort, times(1)).markCancelled(isNull()); // 40.00짜리 1건만 취소
        inOrder.verify(orderPlanner).savePlannedOrders(any(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));

        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<PlannedOrder> saved = ordersCaptor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).price()).isEqualByComparingTo("31.50");
        assertThat(saved.get(0).quantity()).isEqualTo(5);
    }

    @Test
    void capPrivacyIfNeeded_allBuysWithinCap_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 5)));

        capper.capIfNeeded(StrategyType.PRIVACY, false, TODAY, ACCOUNT, STRATEGY_CYCLE_ID,
                new BigDecimal("50.00"), null, null, StrategyTicker.SOXL);

        verify(strategyCyclePort).lockForUpdate(STRATEGY_CYCLE_ID);
        verify(privacyType, never()).capBuyOrders(any(), any(), any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }
}
```

- [ ] **Step 10: `TradingOrderExecutor.needsCapCheck` 가드에 대한 단위 테스트가 필요한지 확인**

`TradingOrderExecutorTest.java`가 존재하는지 확인한다: `ls trading-core/src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java`. 존재하면 `applyCap` 관련 기존 테스트가 `PriceCapMode` mock을 쓰고 있는지 grep(`grep -n "PriceCapMode\|priceCapMode" trading-core/src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java`)하고, 있으면 `cycleOrderStrategies.of(...).needsCapCheck(position, vrPosition)`/`capIfNeeded(strategy.type(), ...)` stub으로 교체한다. 존재하지 않으면 이 스텝은 스킵한다.

- [ ] **Step 11: 좁힌 범위로 테스트 실행**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.application.service.BuyOrderPriceCapperTest' --tests 'com.kista.trading.application.service.ManualTradingServiceTest' --tests 'com.kista.trading.application.service.TradingServiceTest' --tests 'com.kista.matching.domain.strategy.*'`
Expected: BUILD SUCCESSFUL — 특히 `InfiniteStrategyTypeTest`/`VrStrategyTypeTest`(커널 계산 자체)는 이 태스크에서 전혀 건드리지 않았으므로 무수정 통과해야 한다.

- [ ] **Step 12: 전체 스위트 최종 검증(4개 태스크 통틀어 1회)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 클래스를 좁혀 원인 확인.

- [ ] **Step 13: 커밋**

```bash
git add trading-core/src/main/java/com/kista/matching/domain/strategy/CycleOrderStrategy.java \
        trading-core/src/main/java/com/kista/matching/domain/strategy/InfiniteCycleOrderStrategy.java \
        trading-core/src/main/java/com/kista/matching/domain/strategy/VrCycleOrderStrategy.java \
        trading-core/src/main/java/com/kista/matching/domain/strategy/PrivacyCycleOrderStrategy.java \
        trading-core/src/main/java/com/kista/trading/application/service/BuyOrderPriceCapper.java \
        trading-core/src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java \
        trading-core/src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java \
        trading-core/src/main/java/com/kista/trading/application/service/ManualTradingService.java \
        trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java \
        trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(matching,trading): PriceCapMode enum을 CycleOrderStrategy capability로 편입

BuyOrderPriceCapper/TradingOrderExecutor의 enum switch 3곳을
CycleOrderStrategy.capBuyOrders()/capsIndividualOrders()/needsCapCheck()
다형성 호출로 대체했다. PriceCapMode enum과 priceCapMode() 제거,
BuyOrderPriceCapper의 InfiniteStrategy/VrStrategy 직접 주입도 제거.
PRIVACY 개별취소 vs INFINITE/VR 전체취소 동작 차이는 그대로 보존.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Qi36d28hgnhCujutf162ys
EOF
)"
```
