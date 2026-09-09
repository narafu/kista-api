# 주문생성 알고리즘 커널 추출 — `com.kista.matching` 신설

- 작성일: 2026-09-09
- 브랜치: `sdd/matching-kernel-extraction` (main에서 분기)
- 로드맵 항목: 모듈 경계 재구성 #1 (우선순위 1)
- 기준: `ApplicationModules.verify()` GREEN · `HexagonalArchitectureTest` GREEN

## 배경과 목적

`stats.domain.backtest.BacktestEngine`이 `trading.domain.strategy`의 전략 구현체
(`VrStrategy`, `InfiniteStrategy`, `CycleOrderStrategies`, `CycleOrderStrategy`, `PriceCapPolicy`)를
직접 import한다. 라이브 주문생성 알고리즘을 과거 캔들에 재실행하는 것이다. 하나의 순수 계산 커널을
**두 소비자(라이브 실행 / 백테스트 시뮬레이션)**가 쓰는데, 커널이 실행 애그리게이트(`com.kista.trading`)
안에 갇혀 있다.

매매·VR 공식은 `constraints.md`에서 "변경 금지" + 단위테스트로 고정돼 있고, 전략 클래스는 전부 Spring 비의존
순수 계산 클래스다 (`CycleStrategyBeanConfig`가 trading용 `@Bean` 배선, `BacktestEngine`은 직접 `new`).
"라이브와 백테스트가 같은 공식을 쓴다"는 보장이 현재는 **"같은 클래스를 import한다"는 관례**로만 유지된다.

이 재구성은 그 커널을 독립 모듈로 분리해:

1. `stats → trading` 의존을 대폭 줄이고 (backtest 관련 ~15개 import 제거)
2. trading의 "domain" NamedInterface를 축소하고
3. 라이브/백테스트 공식 드리프트를 **모듈 경계(타입)**로 차단한다.

## 클로저 확인 (실측)

`trading/domain/strategy/*` → `com.kista.*` 의존은 다음뿐이며, 모두 커널과 함께 이동하거나 이미 공용 어휘다:

- 순수 값객체 `InfinitePosition` · `VrPosition` · `ReverseModePosition` · `BootstrapPosition` ·
  `PriceSnapshot` · `StrategyVrDetail` · `StrategyInfiniteDetail` — `com.kista.*` import는
  `sharedkernel.StrategyTicker` 하나뿐, 나머지는 완전 순수 (이 중 `StrategyInfiniteDetail`은 커널이
  타입으로 참조하지 않아 trading 잔류 — 아래 §trading 잔류 참고)
- `Strategy`(설정 애그리게이트) — 커널에서 `ctx.strategy().ticker()` 4곳 + `CycleOrderStrategies.of(Strategy)`
  1곳(`strategy.type()`)만 사용 → `PlanContext`가 `StrategyType` + `StrategyTicker`를 직접 보유하도록 대체
- `AccountBalance` — 커널은 `holdings()`/`avgPrice()`/`usdDeposit()` getter만 사용.
  `broker.domain.model.Execution` 결합(`Fill.of`)은 커널과 무관
- `Order` — 유일한 오염원. 커널은 `Order.planned(...)`가 채우는 8개 계획 필드 + `status=PLANNED`만 쓰는데,
  `Order`는 실행 생명주기 상태(`id` · `OrderStatus` 전이 · `externalOrderId` · `filledQuantity` ·
  `filledPrice`)를 함께 보유한다

`StrategyCycle` · `CyclePosition` · `CycleHistoryPage` · persistence 애그리게이트는 클로저에 들어오지 않는다.
추출하면 trading이 딸려오는 게 아니라 실제로 분리된다.

### `Order` 반환 타입이 증상

`CycleOrderStrategy.OrderPlan`이 `List<Order>`를 담고, 커널의 모든 `buildOrders()`/`buildCappedBuyOrders()`가
`List<Order>`를 반환한다. 순수 가격·수량 계산이 알 필요 없는 실행 상태를 반환 타입에 실어 나른다.
`privacy.domain.model.FidaPlannedOrder`가 우연히 비슷한 4필드 모양이지만, 이건 **FIDA 피드 수신 DTO(입력)**이지
커널 출력이 아니다 — 통합 대상이 아니다 (아래 "비목표" 참고).

## 목표 설계

### 모듈 형태

`com.kista.matching` — `@ApplicationModule` **CLOSED**, domain-only (레이어 서브구조 없음, `sharedkernel`과
유사하되 CLOSED). 목표 outbound 엣지: **`com.kista.sharedkernel` + `com.kista.privacy`뿐**.

- NamedInterface **단일 `"kernel"`** — `matching.domain.strategy`와 `matching.domain.model` 두 패키지의
  `package-info.java`에 `@NamedInterface("kernel")`를 붙여 병합 공개 (현 trading의 `domain.model` +
  `domain.strategy` → "domain" 병합 패턴 그대로). 소비자가 trading·stats 둘뿐이고 둘 다 커널 대부분을
  필요로 하므로 분할("strategy"/"model")은 이득 없음
- `matching.application` **신설하지 않음**. 빈 배선 `CycleStrategyBeanConfig`는
  `trading.application.service`에 잔류 — trading이 matching 공개 클래스를 `new`한다. stats는 오늘도 이
  `@Configuration`에서 `CycleOrderStrategies` 빈을 주입받고 있으며(Modulith는 빈 provenance가 아닌 패키지
  참조를 검사하므로 무회귀), matching 전용 `@Configuration`을 위해 레이어를 하나 만드는 것은 대칭을 위한
  구조일 뿐 소비자가 없다

### matching으로 이동하는 것

| 그룹 | 대상 | 현재 위치 |
|---|---|---|
| 커널 계산 클래스 | `InfiniteStrategy` · `ReverseInfiniteStrategy` · `VrStrategy` · `PrivacyStrategy` · `PriceCapPolicy` | `trading.domain.strategy` |
| 전략 패턴 | `CycleOrderStrategy` (+ nested `PlanContext` · `OrderPlan` · `PriceCapMode`) · `CycleOrderStrategies` · `InfiniteCycleOrderStrategy` · `PrivacyCycleOrderStrategy` · `VrCycleOrderStrategy` | `trading.domain.strategy` |
| 순수 값객체 | `InfinitePosition` · `VrPosition` · `ReverseModePosition` · `BootstrapPosition` · `PriceSnapshot` · `StrategyVrDetail` | `trading.domain.model` |
| 주문 어휘 enum | `OrderType` · `OrderTiming` · `OrderDirection` (현 `Order` nested → matching top-level) | `trading.domain.model.Order` |
| 신규 | `PlannedOrder` (아래 §PlannedOrder) | — |
| `AccountBalance` 분리분 | 순수 값 + `buyTotal` · `hasSufficientDepositFor` + `Fill` 인터페이스 + `applyExecutions` (아래 §AccountBalance) | `trading.domain.model` |

동반 이동 테스트: `InfinitePositionTest` (→ `com.kista.matching.domain.model`),
`InfiniteStrategyTypeTest` (→ `com.kista.matching.domain.strategy`), VR 전략/포지션 계열 단위테스트.
`AccountBalanceTest`는 분리에 맞춰 커널 몫(값·예산 계산)은 matching으로, `Fill.of(Execution)` 케이스는
trading에 잔류.

### trading에 잔류하는 것

- **`Order`** — record 유지. `orderType`/`timing`/`direction` 필드 타입이 `matching.OrderType`/
  `OrderTiming`/`OrderDirection` 참조로 변경. `OrderStatus` nested enum은 잔류(생명주기 전용, 커널 무접근).
  기존 `Order.plan(template, accountId, cycleId)`를 `Order.fromPlanned(PlannedOrder, accountId, cycleId)`
  승격 팩토리로 대체. `Order.planned(...)` 정적 팩토리군은 삭제(커널이 `PlannedOrder.of`를 씀) — 단
  `Order` 직접 생성이 필요한 잔여 호출부(`reorder`, `filledManual`, 테스트)는 유지
- **`StrategyInfiniteDetail`** — `{UUID strategyVersionId, int divisionCount}`. 커널은 이 타입을 참조하지
  않고 `int divisionCount`만 받는다. 관련 포트 `StrategyInfiniteDetailPort`도 잔류
- **생성 리졸버 5개** — `StrategyCreationResolver` · `StrategyCreationResolvers` ·
  `InfiniteCreationResolver` · `PrivacyCreationResolver` · `VrCreationResolver` · `StrategyCreationRequest`.
  `trading.domain.strategy` 잔류, package-info `@NamedInterface("domain")` 그대로. 이들은 전략 **등록 정책**
  해석(런타임 설정 → 파라미터)이며 소비자가 `StrategyService` 하나뿐, backtest와 무관하다 — "하나의 커널,
  두 소비자"라는 추출 논거 밖이다. `trading.domain.strategy` 패키지는 이 재구성으로 "matching 이동분" /
  "trading 잔류분"으로 갈라지지만, 잔류분이 package-info와 NamedInterface를 그대로 유지하므로 찢기는 것은
  없다
- **모든 아웃바운드 포트** — `StrategyVrDetailPort` · `StrategyInfiniteDetailPort` · `OrderPort` 등
  `trading.application.port.output` 잔류. `StrategyVrDetailPort`는 반환 타입만
  `matching.domain.model.StrategyVrDetail`로 변경 (`trading → matching` 참조, 허용)
- **`CycleStrategyBeanConfig`** — `trading.application.service` 잔류. matching 커널 타입(`InfiniteStrategy`,
  `VrStrategy`, 3개 `*CycleOrderStrategy`, `CycleOrderStrategies`)과 trading 잔류 리졸버 타입을 한 파일에서
  12개 `@Bean`으로 배선. 분리하지 않음
- **모든 application service / adapter** — `TradingService` · `CycleOrderComputer` ·
  `TradingOrderPlanner` · `BuyOrderPriceCapper` · `TradingOrderBudgetAllocator` ·
  `TradingBuyCompetitionSimulator` · `StrategyOrderPlanBuilder` · `TradingPreviewService` 등

## PlannedOrder

```java
package com.kista.matching.domain.model;

public record PlannedOrder(
        StrategyTicker ticker,      // sharedkernel
        LocalDate tradeDate,
        OrderType orderType,        // matching
        OrderTiming timing,         // matching
        OrderDirection direction,   // matching
        String orderLeg,            // 전략 주문 다리 식별자
        Integer quantity,           // nullable (SELL "잔량 전부")
        BigDecimal price
) { ... }
```

- 커널이 `Order.planned(...)` 대신 `PlannedOrder.of(...)` 정적 팩토리로 주문을 생성한다.
  `Order.leg(prefix, index)` 헬퍼와 `Order.UNKNOWN_LEG` 상수도 `PlannedOrder`로 이동
- compact 생성자: `orderLeg`가 null/blank면 `UNKNOWN_LEG` (현 `Order` 규칙 그대로)
- witther **`withPrice(BigDecimal)`** — `PriceCapMode.PRIVACY_SIMPLE` 캡 보정에서
  `BuyOrderPriceCapper`가 in-memory로 사용
- **`PlannedOrder.from(Order)`** demotion 정적 팩토리 — `BuyOrderPriceCapper`의 사후 보정 경로
  (`capIfNeeded`/`capIfNeededAtOpen`)가 DB에서 조회한 영속 `Order`를
  `InfiniteStrategy.buildCappedBuyOrders(position, tradeDate, List<PlannedOrder> buyOrders, cap)`에
  넘길 때 호출부에서 변환. (검증 결과: 커널은 입력 주문에서 `price`/`orderType`/`orderLeg`만 읽고
  `id`/`status`는 보지 않음 — demotion으로 안전하게 축소됨)

### OrderPlan / 파이프라인 타입 전환

- `CycleOrderStrategy.OrderPlan`의 `List<Order> orders` → `List<PlannedOrder> orders`
  (record는 `CycleOrderStrategy`와 함께 matching으로 이동)
- 저장 이전 파이프라인 전부 `PlannedOrder`로 흐른다:
  `CycleOrderComputer.compute()` → `BuyOrderPriceCapper.prepareForAllocation()` →
  `TradingOrderBudgetAllocator.allocate()` (`Candidate.orders`) → `TradingBuyCompetitionSimulator` →
  `TradingPreviewService`
- **승격 지점은 단 하나**: `TradingOrderPlanner.savePlannedOrders(List<PlannedOrder> templates,
  Account account, UUID strategyCycleId)`가 `Order.fromPlanned(t, account.id(), strategyCycleId)`로
  매핑 후 `orderPort.saveAll(...)`. 호출부 4곳(`TradingService`, `ManualTradingService`,
  `BuyOrderPriceCapper` ×2)은 시그니처 변경(`List<Order>` → `List<PlannedOrder>`)만 반영
- `AccountBalance.buyTotal` / `hasSufficientDepositFor`는 `List<PlannedOrder>`를 받도록 시그니처 변경
  (호출부는 전부 저장 이전 template 목록을 넘기고 있음)

## AccountBalance 분리

현재 `AccountBalance`(`trading.domain.model`)는 순수 잔고 값 + 주문 예산 계산 + 체결 반영 재계산 +
`broker.domain.model.Execution` 결합을 한 타입에 담고 있다. 커널은 순수 값과 예산 계산만 필요하다.

**matching으로:**

```java
package com.kista.matching.domain.model;

public record AccountBalance(int holdings, BigDecimal avgPrice, BigDecimal usdDeposit) {
    public static BigDecimal buyTotal(List<PlannedOrder> orders) { ... }
    public boolean hasSufficientDepositFor(List<PlannedOrder> orders, BigDecimal otherStrategyBuyTotal) { ... }
    public AccountBalance applyExecutions(List<? extends Fill> executions) { ... }  // 순수 산술 재계산

    public interface Fill {
        OrderDirection direction();   // matching
        int quantity();
        BigDecimal amountUsd();
    }
}
```

- `Fill.of(Execution)` · `Fill.listOf(List<Execution>)` 정적 브릿지는 **이동하지 않는다**. 호출부 3곳
  (`TradingReporter` = trading, `AdminTradeCorrectionService` = admin, `BacktestEngine` = stats)이 각자
  `Execution → AccountBalance.Fill` 변환을 인라인한다 (각 3~5줄, 세 파일 모두 이미 `broker.domain.model.
  Execution`을 import 중). 공용 매퍼가 필요하다고 판단되면 **trading**에 둔다 — `matching`이 `broker`를
  참조하게 만드는 것이 이 분리의 정확히 반대 방향이다
- **`isOrderValid(List<Order>)`는 삭제한다** (이동 아님). 호출부 0건, 死 코드 (검증 완료). 리뷰어가
  삭제를 우발적 스코프로 오독하지 않도록 스펙에 명시
- `sellTotal`은 도메인에 없다 (`TradingOrderBudgetAllocator` 로컬 private 헬퍼) — 변경 없음

## PlanContext — Strategy 제거

`PlanContext`의 `Strategy strategy` 컴포넌트를 `StrategyType type` + `StrategyTicker ticker`로 교체한다.

- 커널의 `ctx.strategy().ticker()` 4곳 → `ctx.ticker()`
- `CycleOrderStrategies.of(Strategy)` 오버로드 삭제 → 호출부 2곳(`StrategyOrderPlanBuilder`,
  `CycleOrderComputer`)이 `.of(strategy.type())` 로 전환 (`of(StrategyType)`는 이미 존재)
- `CycleOrderComputer`가 `PlanContext` 조립 시 `strategy.type()` + `strategy.ticker()`를 직접 넘김
- `BacktestEngine.syntheticStrategy(command)` 제거 — 커널이 `Strategy` 애그리게이트 전체를 요구해서
  가짜 인스턴스를 만들던 코드가 불필요해짐
- 결과: `matching`이 `trading.domain.model.Strategy`를 참조하는 곳 0

## stats 측 변경

- `BacktestEngine` import 전환: `com.kista.trading.domain.strategy.*` · `com.kista.trading.domain.model.
  {Order,AccountBalance,InfinitePosition,VrPosition,StrategyVrDetail}` → `com.kista.matching.domain.*`.
  `Order` → `PlannedOrder` (커널 출력), `syntheticStrategy` 제거, `Strategy` import 제거
- `FillSimulator` import 전환: `trading.domain.model.Order` → `matching.domain.model.PlannedOrder`.
  읽는 필드(`orderType`/`price`/`ticker`/`direction`/`quantity`/`orderLeg`)는 `PlannedOrder`에 전부 존재.
  `broker.domain.model.Direction` 변환(`toDirection`)은 `matching.OrderDirection` → `broker.Direction`로
  유지 (브릿지, #2 몫)
- `BacktestService` · `AccountStatisticsService` — `CycleOrderStrategies` / `CycleOrderStrategy` import를
  `com.kista.matching.domain.strategy`로 전환
- `BacktestCommand`의 미사용 `Strategy` import 제거

## 비목표 (스펙에 명시 — 리뷰어 질문 예상 지점)

1. **`InfiniteStrategy.buildCappedBuyOrders`의 `List<PlannedOrder>` 파라미터는 유지**한다. VR의 대응
   메서드는 `position`만으로 사다리를 재생성하는 비대칭이 있지만, 정규화하려면 INFINITE 사후 보정 공식을
   바꿔야 하고 이는 `constraints.md` "매매 공식 (변경 금지)"에 저촉된다. 이 재구성은 **타입 교체
   (`Order` → `PlannedOrder`)만** 한다
2. **`StrategyVrDetail.strategyVersionId`** — 영속 식별자가 커널 값 타입에 남는다 (`Order.id`와 같은
   냄새). 기존 wart이며 이 재구성으로 고치지 않는다 (스코프 확대 금지)
3. **privacy `PrivacyOrderDirection`/`PrivacyOrderType`, broker `Direction`/`OrderType`의 sharedkernel
   승격은 로드맵 #2 몫**이다. #1은 `matching.OrderType.valueOf(privacyType.name())` 같은
   byte-identical 이름 브릿지를 그대로 둔다. #2는 `matching.OrderType`이 커널의 canonical 어휘가 된 뒤
   자체 근거로 재판정한다 (advisor 판단: `OrderType`/`OrderDirection`이 커널 출력 알파벳이라
   sharedkernel의 정체성 enum과 다른 범주일 수 있어, matching 잔류가 영구 정답일 가능성도 있음)
4. **`FidaPlannedOrder`(privacy)를 `PlannedOrder`로 통합하지 않는다**. 로드맵의 "이미 이 plan value
   모양" 전제는 **모양의 우연이지 의미의 일치가 아니다** — `FidaPlannedOrder`는 FIDA 피드 **수신
   DTO(입력)**이고 (`@JsonAlias`, Jackson 역직렬화, `privacy_trade_base_orders` 저장), 커널이 소비하는
   privacy 타입은 `PrivacyTradeBase.PrivacyTrade`(6필드, `tradeDate`/`ticker` 포함)다. `FidaPlannedOrder`는
   trading이 전혀 참조하지 않으며 privacy 내부 전용이다. 통합하면 privacy의 외부 FIDA 계약이 커널 출력
   어휘에 묶이고 `privacy → matching` 방향 결합이 생긴다. 원장에서 실제로 "증상"인 항목은 enum 복제본이며
   그건 #2가 다룬다

## 유지 — 재구성하지 않음

- **`strategyconfig`를 trading에서 재분리하지 않는다** — 2026-09-07 실측 병합(13참조 : 0참조). 커널 seam은
  다른 절단면(계산 로직 vs 설정 애그리게이트)이며 이 병합을 건드리지 않는다
- **모듈 수 축소하지 않는다** — 이 재구성은 12 → 13
- **`web` 앱셸의 `TradingCycleController`** — 커널 추출 후 자연히 가벼워지지만 별도 조치 없음

## 수용 기준

1. **`ModulithArchitectureTest.verify()` GREEN** — 그리고 `matching` 모듈의 outbound 엣지가
   `com.kista.sharedkernel` + `com.kista.privacy`뿐임을 생성된 PlantUML / verify 출력으로 확인.
   `verify()`는 allowlist 없이 `@ApplicationModule`/`@NamedInterface`에서 전부 파생하는 실측 게이트다 —
   이 레포의 과거 이전에서 물리 이동 후 `verify()`에서만 지연 순환이 발견된 전례가 2회 있다
   (MEMORY 인덱스 기록). "컴파일 + 테스트 통과"가 아니라 이 조건이 acceptance다
2. `HexagonalArchitectureTest` GREEN — 특히 `domain_must_not_depend_on_outer_layers`가
   `com.kista.matching..domain..`도 커버 (matching은 domain-only라 자명하게 통과해야 함)
3. `./gradlew test` 전체 GREEN (최종 1회) — 특히 `com.kista.matching.domain.*`로 이동한 공식 단위테스트
4. `OrderEntity`의 `@Enumerated(STRING)` 필드 타입이 `matching.OrderType`/`OrderDirection`으로 바뀌어도
   상수명이 byte-identical이라 `ddl-auto: validate` 통과, `orders.order_type`/`direction` 컬럼 무변경
   (`Broker`/`StrategyType` sharedkernel 승격과 동일 패턴) — 컴파일 + persistence 슬라이스 테스트로 확인

## 문서 갱신 (같은 브랜치)

- `CLAUDE.md` — 모듈 수 12 → 13, 아키텍처 요약 문장에 `matching` 추가
- `docs/agents/architecture.md` — `com.kista.matching` 절 신설 (패키지 트리, "kernel" NamedInterface,
  이동 목록), `com.kista.trading` 절 축소 (`domain.strategy`가 리졸버만 남음, `domain.model`에서
  position 값객체·`AccountBalance` 이탈 반영), `com.kista.stats` 절의 `BacktestEngine` 서술 갱신
- `docs/agents/constraints.md` — "Spring Modulith 이전 중 신규 파일 배치"에 matching 항목 추가,
  "모듈 경계 own-type" 원장의 `Order.OrderType`/`OrderDirection` 주석에 "#1로 커널 어휘가 matching으로
  이동, #2가 sharedkernel 승격 재판정 예정" 반영, `AccountBalance` 분리 서술
- `AGENTS.md` (Codex 진입점) — CLAUDE.md와 동일 요약 변경 반영

## 실행 순서 (writing-plans에서 태스크로 분해)

파일 겹침이 크고 컴파일 단위가 서로 얽혀 있어 순차 진행이 안전하다. 대략:

1. matching 모듈 골격 + enum 3개 + `PlannedOrder` 신설, `Order`가 matching enum 참조하도록 전환
   (`Order` 잔류, 커널 미이동 — trading 컴파일 유지)
2. 순수 값객체 6개 + `StrategyVrDetail` → matching 이동, import 갱신
3. `AccountBalance` 분리 (matching 값 타입 + `Fill`/`applyExecutions`, trading 측 `Execution → Fill`
   인라인 3곳, `isOrderValid` 삭제)
4. 커널 계산 클래스 + 전략 패턴 → matching 이동, `OrderPlan`/`PlanContext` 이동, 커널이
   `PlannedOrder` 방출하도록 전환
5. `PlanContext`에서 `Strategy` 제거, `of(Strategy)` 삭제, `syntheticStrategy` 제거
6. 승격/demotion 배선 — `Order.fromPlanned`, `TradingOrderPlanner`, `BuyOrderPriceCapper`,
   저장 이전 파이프라인 타입 전환
7. stats `BacktestEngine`/`FillSimulator`/`BacktestService`/`AccountStatisticsService` import 재배선
8. `CycleStrategyBeanConfig` import 갱신, package-info + `@NamedInterface("kernel")`,
   테스트 이동, 문서 갱신
9. 최종 검증 — `verify()` + `HexagonalArchitectureTest` + `./gradlew test`

각 태스크는 diff 단위로 리뷰어 검수 후 진행 (커밋 전 검토 의무). 태스크별 리뷰를 전부 거치면 최종 전체
브랜치 리뷰는 생략을 기본으로 하고 사용자에게 확인한다.
