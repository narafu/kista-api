# 바로주문 미리보기 BUY 예산 경쟁 시뮬레이션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TradingPreviewService.preview()`가 계좌 내 모든 활성 전략을 야간 배치(`TradingOrderBudgetAllocator`)와 동일한 우선순위(VR→INFINITE→PRIVACY, 동일 타입 내 금액 작은 순)로 시뮬레이션해, 대상 전략의 BUY가 실제로 승인될지 미리 판단하는 `competition` 필드를 응답에 추가한다.

**Architecture:** 기존 `preview()`의 "잔고 로드→prevClose→privacyBase→compute()" 오케스트레이션을 `StrategyOrderPlanBuilder`로 추출해 대상 전략과 경쟁 전략 계산에 재사용한다. 신규 `TradingBuyCompetitionSimulator`가 계좌 내 다른 ACTIVE 전략들의 오늘자 BUY 후보를 가상 계산하고, `TradingOrderBudgetAllocator`와 동일한 정렬 규칙(신규 공유 유틸 `BuyPriorityOrdering`)으로 정렬해 대상 전략 앞에 쌓이는 금액을 계산한다.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5 + Mockito + AssertJ, Lombok.

## Global Constraints

- 설계 스펙: `docs/superpowers/specs/2026-07-17-buy-competition-preview-design.md` (승인 완료) — 모든 세부 규칙의 SSOT.
- BUY만 다룬다. SELL 경쟁은 계좌당 종목 유일성 제약상 발생하지 않으므로 범위 제외.
- 4-space 들여쓰기, 불변 값은 record, 생성자 주입(`@RequiredArgsConstructor`), package-private 헬퍼 클래스 패턴 유지.
- 신규 코드에는 주석을 함께 작성 (필드: `// 역할 한 줄`, 비즈니스 로직 블록 직전 단계 설명 한 줄). Javadoc·블록 주석 금지, `//` 인라인만 사용.
- 커밋 메시지는 한글, Conventional Commit 접두사(`feat:`, `refactor:`, `test:`) 사용. author는 `narafu <narafu@kakao.com>`이어야 하며 각 태스크 시작 전 `git config user.name`/`user.email` 확인.
- `@WebMvcTest`/`@SpringBootTest` 슬라이스 없이 순수 Mockito 단위 테스트로 충분한 범위 — 컨트롤러 레이어 변경 없음(`NextOrdersResponse.from()` 시그니처 불변).
- 각 태스크 종료 시 `./gradlew compileJava compileTestJava`로 컴파일 검증 후 관련 테스트만 우선 실행.

---

### Task 1: 도메인 모델 — `BuyCompetitionPreview` + `NextOrdersPreview` 필드 추가

**Files:**
- Create: `src/main/java/com/kista/domain/model/order/BuyCompetitionPreview.java`
- Modify: `src/main/java/com/kista/domain/model/order/NextOrdersPreview.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingPreviewService.java:71,90,93` (생성자 호출 3곳에 `null` 인자 추가 — 이 태스크에서는 컴파일만 통과시키고 실제 경쟁 로직 연결은 Task 5에서 수행)
- Modify: `src/test/java/com/kista/application/service/trading/TradingExecutionFacadeTest.java:130`

**Interfaces:**
- Produces: `com.kista.domain.model.order.BuyCompetitionPreview` record (필드: `sufficientBudget: boolean`, `availableDeposit: BigDecimal`, `requiredForThisStrategy: BigDecimal`, `consumedByHigherPriority: BigDecimal`, `blockedByHigherPriority: List<CompetingStrategy>`, `uncertainStrategyIds: List<UUID>`), nested `CompetingStrategy(strategyId: UUID, type: Strategy.Type, ticker: Strategy.Ticker, requiredBuyUsd: BigDecimal, priority: int)`.
- Produces: `NextOrdersPreview`에 7번째 필드 `competition: BuyCompetitionPreview` (nullable) 추가.

이 태스크는 순수 데이터 모델 변경이라 TDD의 "동작 검증" 사이클 대신 컴파일 그린을 기준으로 진행한다.

- [ ] **Step 1: `BuyCompetitionPreview` 도메인 레코드 작성**

```java
package com.kista.domain.model.order;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// 바로주문 미리보기 시 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 — TradingBuyCompetitionSimulator 산출
// 실제 야간 배치(TradingOrderBudgetAllocator)와 동일한 우선순위 정렬을 재현한 근사치
public record BuyCompetitionPreview(
        boolean sufficientBudget,                        // 대상 전략 BUY가 실제 배치에서 승인될지 근사 판정
        BigDecimal availableDeposit,                      // 라이브 예수금 - 타 전략 당일 PLANNED BUY 합계
        BigDecimal requiredForThisStrategy,               // 대상 전략 오늘자 BUY 합계
        BigDecimal consumedByHigherPriority,              // 대상 전략보다 우선순위 앞선 경쟁 전략 필요금액 합
        List<CompetingStrategy> blockedByHigherPriority,  // 우선순위 정렬 순서(높은 순) 유지
        List<UUID> uncertainStrategyIds                   // 계산 실패/skip돼 0으로 처리된 전략 id
) {
    // 경쟁 전략 1건 — priority는 CycleOrderStrategy.allocationPriority() 값(작을수록 먼저 승인)
    public record CompetingStrategy(
            UUID strategyId,
            Strategy.Type type,
            Strategy.Ticker ticker,
            BigDecimal requiredBuyUsd,
            int priority
    ) {}
}
```

- [ ] **Step 2: `NextOrdersPreview`에 `competition` 필드 추가**

`src/main/java/com/kista/domain/model/order/NextOrdersPreview.java` 전체를 다음으로 교체:

```java
package com.kista.domain.model.order;

import com.kista.domain.model.strategy.InfinitePosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,                       // PRIVACY/skip 시 null
        List<Order> orders,                              // NO_CYCLE_HISTORY/NO_PRIVACY_BASE skip 시 빈 리스트
        SkipReason skipReason,                           // 정상이면 null
        List<Order> todayPlannedOrders,                  // 오늘 이미 등록된 PLANNED 주문 (없으면 빈 리스트)
        BigDecimal otherStrategiesPlannedBuyUsd,          // 계좌 내 타 전략 당일 PLANNED BUY 합계
        BuyCompetitionPreview competition                // 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (BUY 없으면 null)
) {
    public enum SkipReason {
        NO_CYCLE_HISTORY,   // 사이클 이력 없음 (신규)
        NO_PRIVACY_BASE     // PRIVACY 기준매매표 미수신
    }
}
```

- [ ] **Step 3: 생성자 호출 3곳에 `null` 인자 추가 (컴파일 통과용 임시 — 실제 값 연결은 Task 5)**

`src/main/java/com/kista/application/service/trading/TradingPreviewService.java`에서 아래 3개 라인을 각각 수정:

```java
// line 71 (BEFORE)
return new NextOrdersPreview(today, null, List.of(), load.skipReason(), todayPlannedOrders, otherStrategiesPlannedBuyUsd);
// (AFTER)
return new NextOrdersPreview(today, null, List.of(), load.skipReason(), todayPlannedOrders, otherStrategiesPlannedBuyUsd, null);
```

```java
// line 90 (BEFORE)
return new NextOrdersPreview(today, null, List.of(), SkipReason.NO_PRIVACY_BASE, todayPlannedOrders, otherStrategiesPlannedBuyUsd);
// (AFTER)
return new NextOrdersPreview(today, null, List.of(), SkipReason.NO_PRIVACY_BASE, todayPlannedOrders, otherStrategiesPlannedBuyUsd, null);
```

```java
// line 93 (BEFORE)
return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayPlannedOrders, otherStrategiesPlannedBuyUsd);
// (AFTER)
return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayPlannedOrders, otherStrategiesPlannedBuyUsd, null);
```

- [ ] **Step 4: `TradingExecutionFacadeTest` 생성자 호출 수정**

`src/test/java/com/kista/application/service/trading/TradingExecutionFacadeTest.java:130`:

```java
// BEFORE
NextOrdersPreview preview = new NextOrdersPreview(LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO);
// AFTER
NextOrdersPreview preview = new NextOrdersPreview(LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null);
```

- [ ] **Step 5: 컴파일 검증**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL (오류 없음)

- [ ] **Step 6: 관련 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingPreviewServiceTest' --tests 'com.kista.application.service.trading.TradingExecutionFacadeTest'`
Expected: 기존 테스트 전부 PASS (동작 변화 없음, `competition` 필드는 항상 null)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/domain/model/order/BuyCompetitionPreview.java \
        src/main/java/com/kista/domain/model/order/NextOrdersPreview.java \
        src/main/java/com/kista/application/service/trading/TradingPreviewService.java \
        src/test/java/com/kista/application/service/trading/TradingExecutionFacadeTest.java
git commit -m "$(cat <<'EOF'
feat: BuyCompetitionPreview 도메인 모델 추가

NextOrdersPreview에 competition 필드 추가 — 계좌 내 BUY 예산 경쟁
시뮬레이션 결과를 담을 자리 확보 (실제 계산 로직은 후속 태스크)
EOF
)"
```

---

### Task 2: `BuyPriorityOrdering` 공유 정렬 유틸 + `TradingOrderBudgetAllocator` 리팩터링

**Files:**
- Create: `src/main/java/com/kista/application/service/trading/BuyPriorityOrdering.java`
- Create: `src/test/java/com/kista/application/service/trading/BuyPriorityOrderingTest.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java:151-157` (`buyPriorityComparator()`만 교체)

**Interfaces:**
- Produces: `BuyPriorityOrdering.comparator(CycleOrderStrategies, Function<T,Strategy.Type>, Function<T,BigDecimal>, Function<T,UUID>, Function<T,UUID>): Comparator<T>` — Task 4(`TradingBuyCompetitionSimulator`)가 그대로 재사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/application/service/trading/BuyPriorityOrderingTest.java`:

```java
package com.kista.application.service.trading;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuyPriorityOrderingTest {

    @Mock CycleOrderStrategy vrStrategy;
    @Mock CycleOrderStrategy infiniteStrategy;

    // 정렬 대상 최소 형태 — strategyId/cycleId/type/amount만 있으면 됨
    record Candidate(UUID strategyId, UUID cycleId, Strategy.Type type, BigDecimal amount) {}

    @Test
    void comparator_ordersByPriorityThenAmountThenIds() {
        when(vrStrategy.cycleType()).thenReturn(Strategy.Type.VR);
        lenient().when(vrStrategy.allocationPriority()).thenReturn(0);
        when(infiniteStrategy.cycleType()).thenReturn(Strategy.Type.INFINITE);
        lenient().when(infiniteStrategy.allocationPriority()).thenReturn(1);
        CycleOrderStrategies strategies = new CycleOrderStrategies(List.of(vrStrategy, infiniteStrategy));

        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        Candidate infiniteBig = new Candidate(id3, id3, Strategy.Type.INFINITE, new BigDecimal("500"));
        Candidate vrSmall = new Candidate(id1, id1, Strategy.Type.VR, new BigDecimal("100"));
        Candidate vrBig = new Candidate(id2, id2, Strategy.Type.VR, new BigDecimal("300"));

        Comparator<Candidate> comparator = BuyPriorityOrdering.comparator(
                strategies, Candidate::type, Candidate::amount, Candidate::strategyId, Candidate::cycleId);

        List<Candidate> sorted = List.of(infiniteBig, vrBig, vrSmall).stream()
                .sorted(comparator)
                .toList();

        // VR(우선순위 0)이 INFINITE(1)보다 먼저, 동일 타입(VR) 내에서는 금액 작은 순
        assertThat(sorted).containsExactly(vrSmall, vrBig, infiniteBig);
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.BuyPriorityOrderingTest'`
Expected: FAIL — `BuyPriorityOrdering` 클래스 없음 (컴파일 오류)

- [ ] **Step 3: `BuyPriorityOrdering` 구현**

```java
package com.kista.application.service.trading;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategies;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Function;

// BUY 예산 배정 우선순위 정렬 규칙의 단일 소스
// TradingOrderBudgetAllocator(실제 야간 배치)와 TradingBuyCompetitionSimulator(미리보기 시뮬레이션)가
// 동일 규칙을 공유해야 두 결과가 어긋나지 않는다
// package-private — application/service/trading 패키지 전용
final class BuyPriorityOrdering {

    private BuyPriorityOrdering() {}

    // 타입 우선순위(작을수록 먼저) → 금액 오름차순 → strategyId → cycleId
    static <T> Comparator<T> comparator(CycleOrderStrategies cycleOrderStrategies,
                                         Function<T, Strategy.Type> typeFn,
                                         Function<T, BigDecimal> amountFn,
                                         Function<T, UUID> strategyIdFn,
                                         Function<T, UUID> cycleIdFn) {
        return Comparator
                .comparingInt((T t) -> cycleOrderStrategies.of(typeFn.apply(t)).allocationPriority())
                .thenComparing(amountFn)
                .thenComparing(strategyIdFn)
                .thenComparing(cycleIdFn);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.BuyPriorityOrderingTest'`
Expected: PASS

- [ ] **Step 5: `TradingOrderBudgetAllocator.buyPriorityComparator()`를 공유 유틸로 교체**

`src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java`의 `buyPriorityComparator()` 메서드(151-157행)를 다음으로 교체:

```java
// BEFORE
private Comparator<Candidate> buyPriorityComparator() {
    return Comparator
            .comparingInt((Candidate candidate) -> strategyPriority(candidate.ctx().strategy().type()))
            .thenComparing(candidate -> buyTotal(candidate.orders()))
            .thenComparing(candidate -> candidate.ctx().strategy().id())
            .thenComparing(candidate -> candidate.ctx().currentCycle().id());
}
```

```java
// AFTER
private Comparator<Candidate> buyPriorityComparator() {
    return BuyPriorityOrdering.comparator(cycleOrderStrategies,
            candidate -> candidate.ctx().strategy().type(),
            candidate -> buyTotal(candidate.orders()),
            candidate -> candidate.ctx().strategy().id(),
            candidate -> candidate.ctx().currentCycle().id());
}
```

(`sellPriorityComparator()`와 `strategyPriority(Strategy.Type)` private 메서드는 SELL 전용이라 변경하지 않는다 — SELL은 amount가 `int` 수량이라 이번 제네릭 유틸과 타입이 다름.)

- [ ] **Step 6: 회귀 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingOrderBudgetAllocatorTest'`
Expected: 기존 전체 테스트 PASS (정렬 결과 동일해야 함 — `allocate_prioritizesVrThenInfiniteThenPrivacyWithLimitedCash`, `allocate_sameStrategyTypeApprovesSmallerBuyTotalFirst` 포함)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/BuyPriorityOrdering.java \
        src/test/java/com/kista/application/service/trading/BuyPriorityOrderingTest.java \
        src/main/java/com/kista/application/service/trading/TradingOrderBudgetAllocator.java
git commit -m "$(cat <<'EOF'
refactor: BUY 우선순위 정렬 규칙을 BuyPriorityOrdering으로 단일화

TradingOrderBudgetAllocator의 정렬 로직을 제네릭 공유 유틸로 추출 —
후속 태스크의 미리보기 경쟁 시뮬레이션이 동일 규칙을 재사용해
실제 배치 결과와 어긋나지 않도록 함
EOF
)"
```

---

### Task 3: `StrategyOrderPlanBuilder` 추출 (전략 1건 주문 계획 계산 오케스트레이션)

**Files:**
- Create: `src/main/java/com/kista/application/service/trading/StrategyOrderPlanBuilder.java`
- Create: `src/test/java/com/kista/application/service/trading/StrategyOrderPlanBuilderTest.java`

**Interfaces:**
- Consumes: `TradingBalanceLoader.tryLoadBalance(Strategy): BalanceLoad`, `CycleOrderComputer.compute(AccountBalance, Strategy, BigDecimal, LocalDate, StrategyCycle, PrivacyTradeBase, String, BigDecimal): Optional<CycleOrderStrategy.OrderPlan>`, `CycleOrderStrategies.of(Strategy): CycleOrderStrategy`, `PrivacyTradePort.findBaseIfPrivacy(Strategy, LocalDate): PrivacyTradeBase`, `BrokerAdapterRegistry.require(Account, Class): T`.
- Produces: `StrategyOrderPlanBuilder.build(Strategy, Account, StrategyCycle, LocalDate, String): PlanResult`, `PlanResult(plan: CycleOrderStrategy.OrderPlan, skipReason: NextOrdersPreview.SkipReason)`, `PlanResult.isSkip(): boolean` — Task 4·5가 그대로 사용.

이 클래스는 현재 `TradingPreviewService.preview()`에 인라인된 로직을 그대로 옮기는 순수 추출이므로, 기존 동작을 그대로 재현하는 것이 테스트 목표다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/application/service/trading/StrategyOrderPlanBuilderTest.java`:

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyOrderPlanBuilderTest {

    @Mock TradingBalanceLoader balanceLoader;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort pricePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock CycleOrderComputer orderComputer;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleOrderStrategy orderStrategy;

    StrategyOrderPlanBuilder builder;

    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        builder = new StrategyOrderPlanBuilder(balanceLoader, registry, privacyTradePort, orderComputer, cycleOrderStrategies);
        lenient().when(cycleOrderStrategies.of(strategy)).thenReturn(orderStrategy);
        lenient().doReturn(pricePort).when(registry).require(any(Account.class), any());
    }

    @Test
    void build_returnsSkip_whenBalanceLoadIsSkip() {
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(null, SkipReason.NO_CYCLE_HISTORY));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isTrue();
        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        verifyNoInteractions(orderComputer);
    }

    @Test
    void build_fetchesPrevClose_whenStrategyRequiresIt() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(true);
        when(pricePort.getPrevClose(Ticker.SOXL, account)).thenReturn(new BigDecimal("21.00"));
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of());
        when(orderComputer.compute(balance, strategy, new BigDecimal("21.00"), today, cycle, null, "label", null))
                .thenReturn(Optional.of(plan));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isFalse();
        assertThat(result.plan()).isSameAs(plan);
    }

    @Test
    void build_skipsPrevCloseFetch_whenStrategyDoesNotRequireIt() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(false);
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of());
        when(orderComputer.compute(balance, strategy, null, today, cycle, null, "label", null))
                .thenReturn(Optional.of(plan));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isFalse();
        verify(pricePort, never()).getPrevClose(any(), any());
    }

    @Test
    void build_returnsSkipNoPrivacyBase_whenComputeReturnsEmpty() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(false);
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        when(orderComputer.compute(balance, strategy, null, today, cycle, null, "label", null))
                .thenReturn(Optional.empty());

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isTrue();
        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_PRIVACY_BASE);
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.StrategyOrderPlanBuilderTest'`
Expected: FAIL — `StrategyOrderPlanBuilder` 클래스 없음

- [ ] **Step 3: `StrategyOrderPlanBuilder` 구현**

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.application.service.broker.BrokerCallGuard;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

// 전략 1건의 오늘자 주문 계획 계산 오케스트레이션 — 잔고 로드 → prevClose(필요 시) → privacyBase(필요 시) → compute()
// TradingPreviewService(대상 전략 1건)와 TradingBuyCompetitionSimulator(경쟁 전략 N건 가상 계산)가 공유
// package-private — application/service/trading 패키지 전용
@Component
@RequiredArgsConstructor
class StrategyOrderPlanBuilder {

    private final TradingBalanceLoader balanceLoader;       // cycle_position 최신 스냅샷 기반 잔고 로드
    private final BrokerAdapterRegistry registry;            // 전일종가 조회용 브로커 라우팅
    private final PrivacyTradePort privacyTradePort;         // PRIVACY 기준매매표 조회
    private final CycleOrderComputer orderComputer;          // 전략 계산 + 유효성 검증
    private final CycleOrderStrategies cycleOrderStrategies; // 전략 타입별 capability 조회

    // 계산 결과 — 정상이면 plan non-null, skip이면 skipReason non-null
    record PlanResult(CycleOrderStrategy.OrderPlan plan, SkipReason skipReason) {
        boolean isSkip() {
            return plan == null;
        }
    }

    PlanResult build(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today, String label) {
        // 잔고 이력 없으면 계산 자체가 불가능한 skip
        TradingBalanceLoader.BalanceLoad load = balanceLoader.tryLoadBalance(strategy);
        if (load.isSkip()) {
            return new PlanResult(null, load.skipReason());
        }
        AccountBalance balance = load.balance();

        CycleOrderStrategy orderStrategy = cycleOrderStrategies.of(strategy);
        BigDecimal prevClosePrice = null;
        if (orderStrategy.requiresPrevClose()) {
            prevClosePrice = BrokerCallGuard.wrap("전일종가 조회",
                    () -> registry.require(account, BrokerPricePort.class).getPrevClose(strategy.ticker(), account));
        }
        PrivacyTradeBase privacyBase = privacyTradePort.findBaseIfPrivacy(strategy, today);

        CycleOrderStrategy.OrderPlan plan = orderComputer.compute(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, label, null)
                .orElse(null);
        if (plan == null) {
            return new PlanResult(null, SkipReason.NO_PRIVACY_BASE);
        }
        return new PlanResult(plan, null);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.StrategyOrderPlanBuilderTest'`
Expected: PASS (4개 테스트 모두)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/StrategyOrderPlanBuilder.java \
        src/test/java/com/kista/application/service/trading/StrategyOrderPlanBuilderTest.java
git commit -m "$(cat <<'EOF'
feat: StrategyOrderPlanBuilder 추출 — 전략 1건 주문 계획 계산 재사용 가능하게 분리

TradingPreviewService.preview()의 인라인 오케스트레이션을 별도 헬퍼로 추출.
아직 preview()에는 연결하지 않음 (Task 5에서 연결) — 이 태스크는 순수 신규 클래스 추가
EOF
)"
```

---

### Task 4: `TradingBuyCompetitionSimulator` 구현

**Files:**
- Create: `src/main/java/com/kista/application/service/trading/TradingBuyCompetitionSimulator.java`
- Create: `src/test/java/com/kista/application/service/trading/TradingBuyCompetitionSimulatorTest.java`

**Interfaces:**
- Consumes: `StrategyOrderPlanBuilder.build(...): PlanResult` (Task 3), `BuyPriorityOrdering.comparator(...)` (Task 2), `StrategyPort.findByAccountId(UUID): List<Strategy>`, `StrategyCyclePort.findLatestByStrategyId(UUID): Optional<StrategyCycle>`, `OrderPort.findPlannedOrPlacedByCycleAndDate(UUID, LocalDate): List<Order>`, `BrokerAdapterRegistry.require(Account, Class): T`, `LiveBalancePort.getLiveBalance(Account, Ticker): AccountBalance`, `AccountBalance.buyTotal(List<Order>): BigDecimal` (기존 static 헬퍼).
- Produces: `TradingBuyCompetitionSimulator.simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle, List<Order> currentBuyOrders, LocalDate today, BigDecimal otherStrategiesPlannedBuyUsd): BuyCompetitionPreview` — Task 5가 `TradingPreviewService.preview()`에서 호출.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/application/service/trading/TradingBuyCompetitionSimulatorTest.java`:

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingBuyCompetitionSimulatorTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock LiveBalancePort liveBalancePort;
    @Mock StrategyOrderPlanBuilder planBuilder;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleOrderStrategy vrOrderStrategy;
    @Mock CycleOrderStrategy infiniteOrderStrategy;

    TradingBuyCompetitionSimulator simulator;

    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    LocalDate today = LocalDate.now();

    Strategy currentStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    StrategyCycle currentCycle = new StrategyCycle(UUID.randomUUID(), currentStrategy.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    @BeforeEach
    void setUp() {
        simulator = new TradingBuyCompetitionSimulator(strategyPort, strategyCyclePort, orderPort, registry, planBuilder, cycleOrderStrategies);
        lenient().doReturn(liveBalancePort).when(registry).require(any(Account.class), any());
        lenient().when(cycleOrderStrategies.of(Strategy.Type.INFINITE)).thenReturn(infiniteOrderStrategy);
        lenient().when(cycleOrderStrategies.of(Strategy.Type.VR)).thenReturn(vrOrderStrategy);
        lenient().when(infiniteOrderStrategy.allocationPriority()).thenReturn(1);
        lenient().when(vrOrderStrategy.allocationPriority()).thenReturn(0);
    }

    private Order buyOrder(Ticker ticker, int quantity, BigDecimal price) {
        return Order.planned(today, ticker, Order.OrderType.LOC, Order.OrderDirection.BUY, quantity, price);
    }

    @Test
    void simulate_sufficientBudget_whenNoCompetitors() {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00"))); // 200 USD

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.sufficientBudget()).isTrue();
        assertThat(result.availableDeposit()).isEqualByComparingTo("1000.00");
        assertThat(result.requiredForThisStrategy()).isEqualByComparingTo("200.00");
        assertThat(result.consumedByHigherPriority()).isEqualByComparingTo("0");
        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.uncertainStrategyIds()).isEmpty();
    }

    @Test
    void simulate_excludesCompetitor_thatAlreadyHasOrdersToday() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today))
                .thenReturn(List.of(buyOrder(Ticker.TQQQ, 1, new BigDecimal("50.00"))));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, new BigDecimal("50.00"));

        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.availableDeposit()).isEqualByComparingTo("950.00"); // 1000 - 50(otherStrategiesPlannedBuyUsd)
        verify(planBuilder, never()).build(eq(vrStrategy), any(), any(), any(), anyString());
    }

    @Test
    void simulate_blocksCurrentStrategy_whenHigherPriorityCompetitorConsumesBudget() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);
        CycleOrderStrategy.OrderPlan vrPlan = new CycleOrderStrategy.OrderPlan(
                null, List.of(buyOrder(Ticker.TQQQ, 10, new BigDecimal("90.00")))); // 900 USD

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today)).thenReturn(List.of());
        when(planBuilder.build(eq(vrStrategy), eq(account), eq(vrCycle), eq(today), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(vrPlan, null));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00"))); // 200 USD

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.consumedByHigherPriority()).isEqualByComparingTo("900.00");
        assertThat(result.blockedByHigherPriority()).hasSize(1);
        assertThat(result.blockedByHigherPriority().get(0).strategyId()).isEqualTo(vrStrategy.id());
        assertThat(result.sufficientBudget()).isFalse(); // 900 + 200 > 1000
    }

    @Test
    void simulate_treatsFailedCompetitorAsZero_andRecordsUncertain() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today)).thenReturn(List.of());
        when(planBuilder.build(eq(vrStrategy), eq(account), eq(vrCycle), eq(today), anyString()))
                .thenThrow(new IllegalStateException("가격 조회 실패"));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.uncertainStrategyIds()).containsExactly(vrStrategy.id());
        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.sufficientBudget()).isTrue();
    }

    @Test
    void simulate_excludesPausedStrategy() {
        Strategy pausedVr = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.PAUSED, Ticker.TQQQ, Strategy.CycleSeedType.NONE);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, pausedVr));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.blockedByHigherPriority()).isEmpty();
        verifyNoInteractions(planBuilder);
        verify(strategyCyclePort, never()).findLatestByStrategyId(pausedVr.id());
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingBuyCompetitionSimulatorTest'`
Expected: FAIL — `TradingBuyCompetitionSimulator` 클래스 없음

- [ ] **Step 3: `TradingBuyCompetitionSimulator` 구현**

```java
package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 바로주문 미리보기에서 계좌 내 활성 전략 전체를 야간 배치와 동일한 우선순위로 시뮬레이션해
// 대상 전략의 BUY가 실제로 승인될지 근사 판정한다
// BUY 전용 — SELL은 계좌당 종목 유일성 제약상 같은 계좌 내에서 경쟁이 발생하지 않는다
// package-private — application/service/trading 패키지 전용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingBuyCompetitionSimulator {

    private final StrategyPort strategyPort;              // 계좌 내 전략 전체 조회
    private final StrategyCyclePort strategyCyclePort;    // 경쟁 전략의 현재 사이클 조회
    private final OrderPort orderPort;                    // 경쟁 전략의 당일 기존 주문 유무 확인
    private final BrokerAdapterRegistry registry;         // 라이브 예수금 조회
    private final StrategyOrderPlanBuilder planBuilder;   // 경쟁 전략 가상 계산
    private final CycleOrderStrategies cycleOrderStrategies; // 우선순위 조회

    // 정렬 대상 후보 — 대상 전략과 경쟁 전략을 동일한 형태로 취급
    private record RankedCandidate(UUID strategyId, UUID cycleId, Strategy.Type type,
                                    Strategy.Ticker ticker, BigDecimal requiredBuyUsd, boolean isCurrent) {}

    BuyCompetitionPreview simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle,
                                    List<Order> currentBuyOrders, LocalDate today,
                                    BigDecimal otherStrategiesPlannedBuyUsd) {
        BigDecimal requiredForThis = AccountBalance.buyTotal(currentBuyOrders);

        // 라이브 예수금에서 타 전략의 당일 PLANNED BUY만 차감 — 대상 전략 자신의 기존 예약분은
        // requiredForThis가 매번 전체 재계산이라 이미 반영돼 있으므로 이중 차감하지 않는다.
        // PLACED 주문은 브로커에 이미 접수돼 라이브 예수금 자체에 반영돼 있어 별도 차감 불필요.
        BigDecimal liveDeposit = registry.require(account, LiveBalancePort.class)
                .getLiveBalance(account, currentStrategy.ticker())
                .usdDeposit();
        BigDecimal availableDeposit = liveDeposit.subtract(otherStrategiesPlannedBuyUsd);

        List<UUID> uncertainStrategyIds = new ArrayList<>();
        List<RankedCandidate> ranked = new ArrayList<>();
        ranked.add(new RankedCandidate(currentStrategy.id(), currentCycle.id(), currentStrategy.type(),
                currentStrategy.ticker(), requiredForThis, true));

        for (Strategy other : strategyPort.findByAccountId(account.id())) {
            if (other.id().equals(currentStrategy.id()) || other.status() != Strategy.Status.ACTIVE) {
                continue;
            }
            StrategyCycle otherCycle = strategyCyclePort.findLatestByStrategyId(other.id()).orElse(null);
            if (otherCycle == null) {
                continue; // 사이클 없는 전략은 경쟁 대상이 될 수 없음
            }
            boolean alreadyOrdered = !orderPort.findPlannedOrPlacedByCycleAndDate(otherCycle.id(), today).isEmpty();
            if (alreadyOrdered) {
                continue; // PLANNED는 otherStrategiesPlannedBuyUsd에, PLACED는 라이브 예수금에 이미 반영됨
            }

            try {
                StrategyOrderPlanBuilder.PlanResult result =
                        planBuilder.build(other, account, otherCycle, today, "competition:" + other.id());
                if (result.isSkip()) {
                    continue;
                }
                BigDecimal required = AccountBalance.buyTotal(result.plan().orders());
                if (required.signum() > 0) {
                    ranked.add(new RankedCandidate(other.id(), otherCycle.id(), other.type(), other.ticker(), required, false));
                }
            } catch (Exception e) {
                log.warn("경쟁 시뮬레이션 실패, 0으로 처리: strategyId={}, error={}", other.id(), e.getMessage());
                uncertainStrategyIds.add(other.id());
            }
        }

        ranked.sort(BuyPriorityOrdering.comparator(cycleOrderStrategies,
                RankedCandidate::type, RankedCandidate::requiredBuyUsd, RankedCandidate::strategyId, RankedCandidate::cycleId));

        BigDecimal consumedByHigherPriority = BigDecimal.ZERO;
        List<BuyCompetitionPreview.CompetingStrategy> blocked = new ArrayList<>();
        for (RankedCandidate candidate : ranked) {
            if (candidate.isCurrent()) {
                break; // 대상 전략 도달 시 종료 — 이후 후보는 우선순위가 낮아 무관
            }
            consumedByHigherPriority = consumedByHigherPriority.add(candidate.requiredBuyUsd());
            blocked.add(new BuyCompetitionPreview.CompetingStrategy(
                    candidate.strategyId(), candidate.type(), candidate.ticker(),
                    candidate.requiredBuyUsd(), cycleOrderStrategies.of(candidate.type()).allocationPriority()));
        }

        boolean sufficient = consumedByHigherPriority.add(requiredForThis).compareTo(availableDeposit) <= 0;
        return new BuyCompetitionPreview(sufficient, availableDeposit, requiredForThis, consumedByHigherPriority, blocked, uncertainStrategyIds);
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingBuyCompetitionSimulatorTest'`
Expected: PASS (5개 테스트 모두)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingBuyCompetitionSimulator.java \
        src/test/java/com/kista/application/service/trading/TradingBuyCompetitionSimulatorTest.java
git commit -m "$(cat <<'EOF'
feat: TradingBuyCompetitionSimulator 구현

계좌 내 활성 전략 전체를 야간 배치와 동일한 우선순위로 시뮬레이션해
대상 전략 BUY의 승인 가능성을 근사 판정. 아직 preview()에는 미연결
EOF
)"
```

---

### Task 5: `TradingPreviewService.preview()` 연결 + 테스트 재작성

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingPreviewService.java` (전체 재작성)
- Modify: `src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java` (전체 재작성)

**Interfaces:**
- Consumes: `StrategyOrderPlanBuilder.build(...)` (Task 3), `TradingBuyCompetitionSimulator.simulate(...)` (Task 4).
- Produces: `TradingPreviewService.preview(UUID, UUID): NextOrdersPreview` — `competition` 필드가 실제로 채워짐. 생성자 시그니처가 `(AccountPort, StrategyPort, StrategyCyclePort, OrderPort, StrategyOrderPlanBuilder, TradingBuyCompetitionSimulator)`로 변경(6개 — 기존 9개에서 감소, Spring이 자동 재배선하므로 다른 프로덕션 코드는 영향 없음).

- [ ] **Step 1: `TradingPreviewService.java` 전체 교체**

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final StrategyOrderPlanBuilder planBuilder;
    private final TradingBuyCompetitionSimulator competitionSimulator;

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        // 현재 StrategyCycle — initialUsdDeposit 조회(PRIVACY) 및 경쟁 시뮬레이션에 사용
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategy.id()));

        // 스케쥴러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = DstInfo.nextTradeDate();

        // 오늘 이미 등록된 주문 조회 — PLANNED(취소 가능) + PLACED(AT_OPEN 선접수됨) 모두 포함
        List<Order> todayPlannedOrders =
                orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);

        // 계좌 내 타 전략 당일 PLANNED BUY 합계 (이 전략 분 제외 — 예수금 부족 계산에 사용)
        BigDecimal totalAccountPlannedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
        BigDecimal thisStrategyPlannedBuy = todayPlannedOrders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);

        StrategyOrderPlanBuilder.PlanResult result =
                planBuilder.build(strategy, account, currentCycle, today, "preview:" + strategyId);
        if (result.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayPlannedOrders, otherStrategiesPlannedBuyUsd, null);
        }
        CycleOrderStrategy.OrderPlan plan = result.plan();

        // 오늘자 계획에 BUY가 있을 때만 계좌 내 예산 경쟁 시뮬레이션 수행
        List<Order> buyOrders = plan.orders().stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .toList();
        BuyCompetitionPreview competition = buyOrders.isEmpty()
                ? null
                : competitionSimulator.simulate(strategy, account, currentCycle, buyOrders, today, otherStrategiesPlannedBuyUsd);

        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayPlannedOrders, otherStrategiesPlannedBuyUsd, competition);
    }
}
```

- [ ] **Step 2: `TradingPreviewServiceTest.java` 전체 교체**

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock StrategyOrderPlanBuilder planBuilder;
    @Mock TradingBuyCompetitionSimulator competitionSimulator;

    TradingPreviewService service;

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);

    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    @BeforeEach
    void setUp() {
        service = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, competitionSimulator);
        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT.id(), ACCOUNT.userId())).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void preview_returnsOrdersWithoutCompetition_whenPlanHasNoBuyOrders() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void preview_callsCompetitionSimulator_whenPlanHasBuyOrders() {
        Order buyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 5, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of());
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(buyOrder)), any(), eq(BigDecimal.ZERO)))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
    }

    @Test
    void preview_returnsSkip_whenPlanBuilderSkips() {
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(null, SkipReason.NO_CYCLE_HISTORY));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.orders()).isEmpty();
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void preview_throwsSecurityException_whenNotOwner() {
        UUID otherId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), otherId)).thenThrow(new SecurityException());

        assertThatThrownBy(() -> service.preview(STRATEGY.id(), otherId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preview_throwsNoSuchElementException_whenStrategyNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(unknownId))
                .thenThrow(new NoSuchElementException("전략 없음: " + unknownId));

        assertThatThrownBy(() -> service.preview(unknownId, ACCOUNT.userId()))
                .isInstanceOf(NoSuchElementException.class);
    }
}
```

- [ ] **Step 3: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingPreviewServiceTest'`
Expected: PASS (5개 테스트 모두)

- [ ] **Step 4: 전체 trading 패키지 컴파일·테스트 재확인**

Run: `./gradlew compileJava compileTestJava test --tests 'com.kista.application.service.trading.*'`
Expected: BUILD SUCCESSFUL, 전체 PASS (Task 1~5에서 건드린 모든 클래스 포함)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingPreviewService.java \
        src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java
git commit -m "$(cat <<'EOF'
feat: preview()에 BUY 예산 경쟁 시뮬레이션 연결

StrategyOrderPlanBuilder + TradingBuyCompetitionSimulator를 preview()에 배선해
competition 필드가 실제로 채워지도록 함. 테스트는 상위 컬래보레이터 레벨로
단순화(TradingBalanceLoader/CycleOrderComputer 실물 생성 불필요)
EOF
)"
```

---

### Task 6: `NextOrdersResponse` DTO — `competition` 응답 필드

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java`
- Create: `src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java`

**Interfaces:**
- Consumes: `com.kista.domain.model.order.BuyCompetitionPreview` (Task 1).
- Produces: `NextOrdersResponse.competition: BuyCompetitionSummary` (nullable), `NextOrdersResponse.BuyCompetitionSummary.from(BuyCompetitionPreview): BuyCompetitionSummary`. (도메인 타입과 이름 충돌을 피하기 위해 응답 DTO는 `BuyCompetitionSummary`로 명명 — 같은 파일에서 `import ...BuyCompetitionPreview`와 동일 simple name의 nested record를 함께 두면 컴파일러가 nested 선언을 우선해 도메인 타입 참조가 막힘)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java`:

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NextOrdersResponseTest {

    @Test
    void from_mapsCompetitionAsNull_whenPreviewCompetitionIsNull() {
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition()).isNull();
    }

    @Test
    void from_mapsCompetitionFields_whenPreviewCompetitionExists() {
        UUID competitorId = UUID.randomUUID();
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                false, new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("900.00"),
                List.of(new BuyCompetitionPreview.CompetingStrategy(
                        competitorId, Strategy.Type.VR, Ticker.TQQQ, new BigDecimal("900.00"), 0)),
                List.of());
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, competition);

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.competition()).isNotNull();
        assertThat(response.competition().sufficientBudget()).isFalse();
        assertThat(response.competition().availableDeposit()).isEqualByComparingTo("1000.00");
        assertThat(response.competition().blockedByHigherPriority()).hasSize(1);
        assertThat(response.competition().blockedByHigherPriority().get(0).strategyId()).isEqualTo(competitorId);
        assertThat(response.competition().blockedByHigherPriority().get(0).type()).isEqualTo(Strategy.Type.VR);
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.dto.NextOrdersResponseTest'`
Expected: FAIL — `NextOrdersPreview` 생성자 인자 개수 불일치 및 `response.competition()` 메서드 없음 (컴파일 오류)

- [ ] **Step 3: `NextOrdersResponse.java` 전체 교체**

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record NextOrdersResponse(
        @Schema(description = "다음 매매 예정일 (KST 기준)")
        LocalDate tradeDate,
        @Schema(description = "INFINITE 전략 포지션 스냅샷 (PRIVACY 전략 또는 skip 시 null)")
        PositionSnapshot position,                    // PRIVACY/skip 시 null
        @Schema(description = "생성 예정 주문 목록")
        List<OrderItem> orders,
        @Schema(description = "주문 생성 skip 사유 (정상이면 null)", example = "NO_PRIVACY_BASE")
        NextOrdersPreview.SkipReason skipReason,      // 정상이면 null
        @Schema(description = "오늘 이미 등록된 PLANNED·PLACED 주문 목록")
        List<TodayOrderItem> todayOrders,             // 오늘 이미 등록된 PLANNED·PLACED 주문
        @Schema(description = "계좌 내 타 전략의 당일 PLANNED BUY 합계 (USD)")
        BigDecimal otherStrategiesPlannedBuyUsd,       // 계좌 내 타 전략 당일 PLANNED BUY 합계
        @Schema(description = "계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (대상 전략에 BUY 주문이 없으면 null, 근사치)")
        BuyCompetitionSummary competition
) {
    public record PositionSnapshot(
            @Schema(description = "거래 종목")
            Ticker ticker,           // 거래 종목
            @Schema(description = "보유 수량")
            int holdings,            // 보유 수량
            @Schema(description = "평균 매입가 (0회차: 전일종가)")
            BigDecimal averagePrice, // 평균 매입가 (0회차: 전일종가)
            @Schema(description = "통합주문가능금액")
            BigDecimal usdDeposit,   // 통합주문가능금액
            @Schema(description = "가격 보정률 (전반: 양수, 후반: 0 이하)")
            BigDecimal priceOffsetRate, // 가격 보정률 (전반: >0, 후반: ≤0)
            @Schema(description = "현재 회차 (소수점 허용)", example = "3.5")
            double currentRound,     // 현재 회차 (소수점 허용)
            @Schema(description = "1회 매수 단위금액 (총자산 / 분할 수)")
            BigDecimal unitAmount,   // 1회 매수 단위금액 (총자산 / 20)
            @Schema(description = "기준가 (LOC 주문 가격 기준)")
            BigDecimal referencePrice,  // 기준가 (LOC 주문 가격 기준)
            @Schema(description = "목표가 (지정가 매도 기준)")
            BigDecimal targetPrice,  // 목표가 (지정가 매도 기준)
            @Schema(description = "총 자산 (예수금 + 매입금액)")
            BigDecimal totalAssets   // 총 자산 (예수금 + 매입금액)
    ) {
        public static PositionSnapshot from(InfinitePosition p) {
            return new PositionSnapshot(
                    p.ticker(),
                    p.holdings(),
                    p.averagePrice(),
                    p.usdDeposit(),
                    p.priceOffsetRate(),
                    p.currentRound(),
                    p.unitAmount(),
                    p.referencePrice(),
                    p.targetPrice(),
                    p.totalAssets()
            );
        }
    }

    // tradeDate·status·orderId는 preview에서 의미 없으므로 제외
    public record OrderItem(
            @Schema(description = "거래 종목")
            Ticker ticker,              // 거래 종목
            @Schema(description = "주문 유형", example = "LOC")
            Order.OrderType orderType,  // 주문 유형 (LOC/MOC/LIMIT)
            @Schema(description = "매수/매도 방향", example = "BUY")
            Order.OrderDirection direction, // 매수/매도 방향
            @Schema(description = "주문 수량")
            int quantity,               // 주문 수량
            @Schema(description = "주문 가격 (LOC/MOC는 참고용)")
            BigDecimal price            // 주문 가격 (LOC/MOC는 참고용)
    ) {
        public static OrderItem from(Order o) {
            return new OrderItem(o.ticker(), o.orderType(), o.direction(), o.quantity(), o.price());
        }
    }

    // 오늘 등록된 주문 항목 (PLANNED·PLACED 모두 포함 — status로 취소 가능 여부 판단)
    public record TodayOrderItem(
            @Schema(description = "주문 고유 ID")
            UUID id,
            @Schema(description = "거래 종목", example = "TQQQ")
            String ticker,
            @Schema(description = "매수/매도 방향", example = "BUY")
            String direction,
            @Schema(description = "주문 유형", example = "LOC")
            String orderType,
            @Schema(description = "주문 수량")
            int quantity,
            @Schema(description = "주문 가격")
            BigDecimal price,
            @Schema(description = "주문 상태 (취소 가능 여부 판단용)", example = "PLANNED")
            Order.OrderStatus status
    ) {
        public static TodayOrderItem from(Order o) {
            return new TodayOrderItem(
                    o.id(),
                    o.ticker().name(),
                    o.direction().name(),
                    o.orderType().name(),
                    o.quantity(),
                    o.price(),
                    o.status()
            );
        }
    }

    // BUY 예산 경쟁 시뮬레이션 결과 — TradingBuyCompetitionSimulator 산출을 그대로 노출
    // 야간 배치의 캐스케이딩 거절·가격 캡은 재현하지 않는 근사치 (docs 참고)
    public record BuyCompetitionSummary(
            @Schema(description = "대상 전략 BUY가 실제 배치에서 승인될지 근사 판정")
            boolean sufficientBudget,
            @Schema(description = "라이브 예수금 - 타 전략 당일 PLANNED BUY 합계")
            BigDecimal availableDeposit,
            @Schema(description = "대상 전략 오늘자 BUY 합계")
            BigDecimal requiredForThisStrategy,
            @Schema(description = "대상 전략보다 우선순위 앞선 경쟁 전략 필요금액 합")
            BigDecimal consumedByHigherPriority,
            @Schema(description = "우선순위가 앞선 경쟁 전략 목록 (우선순위 높은 순 정렬)")
            List<CompetingStrategy> blockedByHigherPriority,
            @Schema(description = "계산 실패/skip돼 0으로 처리된 전략 id 목록")
            List<UUID> uncertainStrategyIds
    ) {
        public record CompetingStrategy(
                @Schema(description = "경쟁 전략 ID")
                UUID strategyId,
                @Schema(description = "경쟁 전략 타입")
                Strategy.Type type,
                @Schema(description = "경쟁 전략 거래 종목")
                Ticker ticker,
                @Schema(description = "경쟁 전략 필요 매수금액")
                BigDecimal requiredBuyUsd,
                @Schema(description = "예산 배정 우선순위 (작을수록 먼저 승인)")
                int priority
        ) {
            public static CompetingStrategy from(BuyCompetitionPreview.CompetingStrategy c) {
                return new CompetingStrategy(c.strategyId(), c.type(), c.ticker(), c.requiredBuyUsd(), c.priority());
            }
        }

        public static BuyCompetitionSummary from(BuyCompetitionPreview c) {
            return new BuyCompetitionSummary(
                    c.sufficientBudget(),
                    c.availableDeposit(),
                    c.requiredForThisStrategy(),
                    c.consumedByHigherPriority(),
                    c.blockedByHigherPriority().stream().map(CompetingStrategy::from).toList(),
                    c.uncertainStrategyIds()
            );
        }
    }

    public static NextOrdersResponse from(NextOrdersPreview result) {
        return new NextOrdersResponse(
                result.tradeDate(),
                result.position() == null ? null : PositionSnapshot.from(result.position()),
                result.orders().stream().map(OrderItem::from).toList(),
                result.skipReason(),
                result.todayPlannedOrders().stream().map(TodayOrderItem::from).toList(),
                result.otherStrategiesPlannedBuyUsd(),
                result.competition() == null ? null : BuyCompetitionSummary.from(result.competition())
        );
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.dto.NextOrdersResponseTest'`
Expected: PASS (2개 테스트 모두)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java \
        src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java
git commit -m "$(cat <<'EOF'
feat(api): NextOrdersResponse에 competition 필드 추가

바로주문 미리보기 API 응답에 BUY 예산 경쟁 시뮬레이션 결과 노출.
kista-ui 연동은 별도 작업
EOF
)"
```

---

### Task 7: 전체 빌드 검증

**Files:** 없음 (검증 전용 태스크 — 코드 변경 없음)

**Interfaces:** 없음

- [ ] **Step 1: 클린 컴파일**

Run: `./gradlew clean compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 관련 전체 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.*' --tests 'com.kista.adapter.in.web.dto.*' --tests 'com.kista.architecture.*'`
Expected: 전체 PASS. `com.kista.architecture.*`(ArchUnit)는 신규 클래스들이 레이어 규칙(domain은 Spring/JPA 독립, application은 adapter 미의존 등)을 지키는지 검증 — `StrategyOrderPlanBuilder`/`TradingBuyCompetitionSimulator`/`BuyPriorityOrdering`은 모두 `application/service/trading` 패키지에 있고 `domain.port.out`/`domain.model`/`domain.strategy`만 참조하므로 통과해야 함

- [ ] **Step 3: 전체 테스트 스위트 실행 (다른 소비자 회귀 확인)**

Run: `./gradlew test`
Expected: 전체 PASS. 특히 `TradingExecutionFacadeTest`(Task 1에서 생성자 인자 수정)와 `TradingOrderBudgetAllocatorTest`(Task 2에서 정렬 로직 리팩터링)가 회귀 없이 통과하는지 확인

- [ ] **Step 4: 실패 시 진단**

테스트 실패 시 stdout보다 XML이 신뢰성 높음:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
실패한 XML의 스택트레이스를 확인해 해당 태스크로 돌아가 수정.

(이 태스크는 검증 전용이라 별도 커밋 없음 — 문제 발견 시 해당 원인 태스크에서 수정 후 그 태스크의 커밋에 포함하거나 별도 fix 커밋 생성)

---

## Self-Review 결과

- **스펙 커버리지**: 설계 문서의 도메인 모델(Task 1), 정렬 규칙 단일화(Task 2), 계산 오케스트레이션 추출(Task 3), 경쟁 시뮬레이터(Task 4, 이중차감 수정 반영), preview() 연결(Task 5), API 응답(Task 6) 모두 태스크로 매핑됨. "한계" 섹션(캐스케이딩 미재현, 가격 캡 미재현)은 코드 주석과 Swagger `@Schema` 설명에 반영.
- **플레이스홀더 스캔**: 전 스텝에 실제 코드/명령어 포함, "TBD"·"나중에" 등 없음.
- **타입 일관성**: `StrategyOrderPlanBuilder.PlanResult`, `TradingBuyCompetitionSimulator.simulate(...)` 시그니처가 Task 3→4→5에서 동일하게 사용됨을 재확인. `BuyCompetitionPreview`/`BuyCompetitionSummary` 필드명이 도메인(Task 1)↔어댑터(Task 6) 간 1:1 대응됨을 재확인.
