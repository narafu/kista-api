# 바로주문 미리보기 — 계좌 내 BUY 예산 경쟁 시뮬레이션

## 배경

`TradingPreviewService.preview()`는 전략 1개 단위로만 호출되며, `otherStrategiesPlannedBuyUsd` 필드로 계좌 내 타 전략의 **이미 PLANNED로 저장된** BUY 합계만 보여준다. 아직 스케쥴러가 돌지 않아 PLANNED 주문이 없는 타 활성 전략의 "오늘 필요할 매수 후보"는 계산에 포함되지 않는다.

반면 실제 야간 배치(`TradingOrderBudgetAllocator`)는 계좌 내 모든 활성 전략의 BUY 후보를 `CycleOrderStrategy.allocationPriority()` 기준(`VR → INFINITE → PRIVACY`, 동일 타입 내 금액 작은 순)으로 정렬해 그리디하게 예산을 소진하므로, preview에서 "충분하다"고 보여도 실제 배치 시점엔 우선순위 낮은 다른 전략이 밀려 거절될 수 있다.

이 기능은 preview 시점에 계좌 내 모든 활성 전략을 대상으로 동일한 우선순위 시뮬레이션을 수행해, 대상 전략의 BUY가 실제로 승인될지 미리 판단해 보여준다.

## 범위

- **BUY만 다룸.** `계좌당 종목(ticker) 중복 등록 불가` 규칙상 한 계좌 내 활성 전략은 항상 서로 다른 ticker를 가지므로 SELL(판매가능수량) 경쟁은 같은 계좌 내에서 구조적으로 발생하지 않는다.
- API 응답 확장까지만. UI 연동은 별도 작업.

## 도메인 모델

`domain/model/order/NextOrdersPreview.java`에 필드 추가:

```java
public record NextOrdersPreview(
    LocalDate tradeDate,
    InfinitePosition position,
    List<Order> orders,
    SkipReason skipReason,
    List<Order> todayPlannedOrders,
    BigDecimal otherStrategiesPlannedBuyUsd,
    BuyCompetitionPreview competition   // 신규 — 대상 전략 plan에 BUY 주문이 없으면 null
) { ... }
```

신규 `domain/model/order/BuyCompetitionPreview.java`:

```java
public record BuyCompetitionPreview(
    boolean sufficientBudget,
    BigDecimal availableDeposit,               // 라이브 예수금 - 당일 계좌 전체 PLANNED BUY
    BigDecimal requiredForThisStrategy,
    BigDecimal consumedByHigherPriority,        // 대상 전략보다 우선순위 앞선 경쟁 전략들의 필요금액 합
    List<CompetingStrategy> blockedByHigherPriority,
    List<UUID> uncertainStrategyIds             // 계산 실패/skip돼 0으로 처리된 전략 id
) {
    public record CompetingStrategy(
        UUID strategyId, Strategy.Type type, Strategy.Ticker ticker,
        BigDecimal requiredBuyUsd, int priority
    ) {}
}
```

## 애플리케이션 레이어

### 신규 `StrategyOrderPlanBuilder` (package-private, `application/service/trading/`)

`TradingPreviewService.preview()`에 인라인돼 있는 "잔고 로드 → prevClose 조회(필요 시) → privacyBase 조회(필요 시) → `CycleOrderComputer.compute()`" 오케스트레이션을 추출한다. 대상 전략 1개 계산(`preview()`)과 계좌 내 여러 전략 순회 계산(경쟁 시뮬레이터) 양쪽에서 재사용해 동일 로직 중복 작성을 막는다.

```java
record PlanResult(CycleOrderStrategy.OrderPlan plan, NextOrdersPreview.SkipReason skipReason) {
    boolean isSkip() { return plan == null; }
}
PlanResult build(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today, String label)
```

의존성: `TradingBalanceLoader`, `BrokerAdapterRegistry`, `PrivacyTradePort`, `CycleOrderComputer`, `CycleOrderStrategies`.

### 신규 `BuyPriorityOrdering` (package-private, `application/service/trading/`)

`TradingOrderBudgetAllocator.buyPriorityComparator()`가 쓰는 "타입 우선순위 → 금액 오름차순 → strategyId → cycleId" 정렬 규칙을 제네릭 static 메서드로 단일화한다. 경쟁 시뮬레이터가 실제 배치와 **동일한 정렬**을 재사용해야 결과가 어긋나지 않으므로, 기존 allocator도 이 유틸로 리팩터링한다 (로직 드리프트 방지 — 보일러플레이트/중복 로직 즉시 정리 원칙 적용).

```java
final class BuyPriorityOrdering {
    private BuyPriorityOrdering() {}

    static <T> Comparator<T> comparator(
            CycleOrderStrategies strategies,
            Function<T, Strategy.Type> typeFn,
            Function<T, BigDecimal> amountFn,
            Function<T, UUID> strategyIdFn,
            Function<T, UUID> cycleIdFn) {
        return Comparator
                .comparingInt((T t) -> strategies.of(typeFn.apply(t)).allocationPriority())
                .thenComparing(amountFn)
                .thenComparing(strategyIdFn)
                .thenComparing(cycleIdFn);
    }
}
```

`TradingOrderBudgetAllocator.buyPriorityComparator()`는 이 유틸을 `Candidate`에 적용하도록 변경한다.

### 신규 `TradingBuyCompetitionSimulator` (package-private, `application/service/trading/`)

```java
BuyCompetitionPreview simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle,
                                List<Order> currentBuyOrders, LocalDate today)
```

처리 순서:
1. `requiredForThisStrategy` = `currentBuyOrders` 중 BUY 합계. 0이면 즉시 `null` 반환(호출측에서 처리, 시뮬레이터는 호출 안 됨).
2. `availableDeposit` = `BrokerAdapterRegistry.require(account, LiveBalancePort.class).getLiveBalance(account, currentStrategy.ticker()).usdDeposit()` − `orderPort.sumPlannedBuyByAccountAndDate(account.id(), today)` (allocator와 동일한 라이브 잔고 기준).
3. `strategyPort.findByAccountId(account.id())`로 계좌 내 전략 전체 조회 → 현재 전략 제외, `Status.ACTIVE`만 필터.
4. 각 타 전략에 대해:
   - `strategyCyclePort.findLatestByStrategyId(other.id())`로 사이클 조회, 없으면 skip(경쟁 대상 아님).
   - `orderPort.findPlannedOrPlacedByCycleAndDate(otherCycle.id(), today)`가 비어있지 않으면(이미 오늘 주문 존재) skip — 이미 `availableDeposit` 계산의 `reservedBuy`에 반영돼 있으므로 중복 계산 방지.
   - 비어있으면 `StrategyOrderPlanBuilder.build(...)`로 가상 계산. 실패(예외) 또는 skip(`NO_PRIVACY_BASE`/`NO_CYCLE_HISTORY`) 시 **0으로 취급하고 계속 진행**, `uncertainStrategyIds`에 추가.
   - BUY 합계가 0보다 크면 후보 목록에 추가.
5. 현재 전략 + 후보 목록을 하나의 리스트로 합쳐 `BuyPriorityOrdering.comparator(...)`로 정렬.
6. 정렬된 리스트를 순회하며 현재 전략 **앞**에 오는 후보들의 필요금액을 누적(`consumedByHigherPriority`)하고 `blockedByHigherPriority`에 담음. 이 리스트는 정렬 순서(우선순위 높은 순)를 그대로 유지해 UI가 "누가 먼저 예산을 가져가는지" 순서대로 보여줄 수 있게 한다.
7. `sufficientBudget` = `(consumedByHigherPriority + requiredForThisStrategy) <= availableDeposit`.

**주의**: 다른 후보들끼리도 서로 예산이 부족해 캐스케이딩 거절될 수 있지만, 이번 시뮬레이션은 그 정밀도까지는 재현하지 않는다 — "내 앞에 우선순위가 있는 전략들이 총 얼마를 쓰는지" 수준의 근사치로, 실제 배치가 반드시 이 그대로 승인/거절한다는 보장은 아니다(라이브 잔고·가격이 시점에 따라 달라질 수 있음). 문서·API 설명에 이 한계를 명시한다.

### `TradingPreviewService.preview()` 변경

기존 인라인 로직을 `StrategyOrderPlanBuilder.build(...)` 호출로 교체하고, `plan.orders()`에 BUY가 1건 이상 있으면 `TradingBuyCompetitionSimulator.simulate(...)`를 호출해 `competition` 필드를 채운다. BUY가 없으면 `competition = null`.

## 어댑터 — API 응답

`adapter/in/web/dto/NextOrdersResponse.java`에 대응 필드/레코드 추가:

```java
BuyCompetitionPreview competition   // 신규

public record BuyCompetitionPreview(
    boolean sufficientBudget,
    BigDecimal availableDeposit,
    BigDecimal requiredForThisStrategy,
    BigDecimal consumedByHigherPriority,
    List<CompetingStrategy> blockedByHigherPriority,
    List<UUID> uncertainStrategyIds
) {
    public record CompetingStrategy(UUID strategyId, String strategyType, String ticker,
                                     BigDecimal requiredBuyUsd, int priority) {}
    public static BuyCompetitionPreview from(com.kista.domain.model.order.BuyCompetitionPreview c) { ... }
}
```

`NextOrdersResponse.from()`에서 `result.competition() == null ? null : BuyCompetitionPreview.from(...)`로 매핑. Swagger `@Schema` 어노테이션 필드별 부여 (기존 DTO 패턴 준수).

## 에러 처리

- 경쟁 시뮬레이션 대상 타 전략 계산 중 예외/skip 발생 시: 0으로 취급하고 전체 계산은 계속 진행, 해당 전략 id를 `uncertainStrategyIds`에 담아 응답에 노출.
- 시뮬레이터 자체(라이브 잔고 조회 등 대상 전략 관련 필수 조회) 실패는 `preview()` 전체 예외로 전파 — 기존 `BrokerCallGuard.wrap()` 패턴 유지.

## 테스트 계획

- `TradingBuyCompetitionSimulatorTest` (신규): 
  - 계좌 내 VR/INFINITE/PRIVACY 혼합 시 우선순위 정렬이 `allocationPriority()` 순서를 따르는지
  - 동일 타입 내 금액 작은 사이클이 우선하는지
  - 이미 PLANNED 있는 타 전략은 후보에서 제외되는지
  - 타 전략 계산 실패 시 0 처리 + `uncertainStrategyIds` 반영
  - `sufficientBudget` 경계값(정확히 availableDeposit과 같을 때 true)
- `TradingPreviewServiceTest` 갱신: BUY 없는 plan은 `competition == null`, BUY 있으면 시뮬레이터 호출·결과 반영 검증
- `TradingOrderBudgetAllocatorTest`: 리팩터링한 `BuyPriorityOrdering` 사용 후에도 기존 정렬 동작 회귀 없는지 (기존 테스트 그대로 통과해야 함)
- `NextOrdersResponseTest`(있다면) 또는 컨트롤러 테스트에서 `competition` 필드 직렬화 확인
