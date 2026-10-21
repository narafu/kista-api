# 리버스모드 쿼터매수 예산 소진 시 MOC 매도 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** INFINITE 리버스모드에서 쿼터매수 예산이 별지점 아래 1주 가격에도 못 미치면(예산 소진), 매수를 조용히 생략하는 대신 동일 수량의 MOC 매도로 전환해 청산을 가속한다.

**Architecture:** `ReverseModePosition`(도메인 record)에 예산 소진 여부를 판단하는 predicate `isQuotaBuyExhausted()`를 추가하고, `ReverseInfiniteStrategy.buildOrders()`가 이 predicate로 분기해 기존 LOC 매도+쿼터매수 대신 MOC 매도 단건을 생성한다. "Position은 상태 판단, Strategy는 주문 조립"이라는 기존 역할 분리를 그대로 따른다.

**Tech Stack:** Java 21, JUnit 5, AssertJ (Mockito 불필요 — 순수 도메인 클래스)

## Global Constraints

- `starPointPrice == null`(별지점 데이터 미계산)과 예산 소진(starPointPrice는 있으나 1주도 못 사는 경우)을 반드시 구분한다 — 데이터 부족 상황은 기존 동작(무주문)을 그대로 유지한다.
- 신규 `orderLeg` 값은 `REVERSE_INFINITE_QUOTA_MOC_SELL` — 기존 `REVERSE_INFINITE_MOC_SELL`(첫날), `REVERSE_INFINITE_LOC_SELL`, `REVERSE_INFINITE_LOC_BUY`와 겹치지 않아야 한다.
- MOC 매도 수량은 신규 계산식을 만들지 않고 기존 `calcLocSellQuantity()`를 그대로 재사용한다.
- 신규 주문은 기존 리버스모드 주문들과 동일하게 `Order.planned(tradeDate, ticker, orderType, direction, quantity, price, orderLeg)` 6-인자 오버로드를 사용해 `AT_CLOSE` 타이밍을 유지한다.
- `InfiniteCycleOrderStrategy.canSkipOrderComputation()`은 이번 작업 범위에서 수정하지 않는다 — 첫날 MOC와 동일하게 별도 인식 로직 없이 두는 것이 기존 패턴과 일관된다.
- `mocSellQuantity < 1`이면 예외 없이 `log.warn` 후 빈 리스트를 반환한다 — 첫날 `buildFirstDayOrders()`의 기존 처리와 동일 패턴을 따른다.
- 주석 규칙(`docs/agents/constraints.md` "주석 규칙"): 신규 필드/메서드에는 `//` 인라인 주석만 사용, Javadoc·블록 주석 금지.

---

## Task 1: `ReverseModePosition.isQuotaBuyExhausted()` predicate 추가

**Files:**
- Modify: `src/main/java/com/kista/domain/model/strategy/ReverseModePosition.java:43-50`
- Test: `src/test/java/com/kista/domain/model/strategy/ReverseModePositionTest.java` (신규)

**Interfaces:**
- Consumes: 없음 — `ReverseModePosition` record는 이미 존재하는 필드(`starPointPrice`, `calcLocBuyQuantity()`)만 사용한다.
- Produces: `public boolean isQuotaBuyExhausted()` — Task 2에서 `ReverseInfiniteStrategy.buildOrders()`가 이 메서드로 분기한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/domain/model/strategy/ReverseModePositionTest.java` 파일을 새로 만든다:

```java
package com.kista.domain.model.strategy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReverseModePosition 예산 소진 판정")
class ReverseModePositionTest {

    @Test
    @DisplayName("별지점이 있고 쿼터매수 예산으로 1주도 못 사면 예산 소진으로 판정한다")
    void isQuotaBuyExhausted_trueWhenBudgetTooSmall() {
        // usdDeposit=3.00 → 쿼터매수 예산 0.75, buyPrice=19.99 → floor(0.75/19.99)=0
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.isQuotaBuyExhausted()).isTrue();
    }

    @Test
    @DisplayName("별지점이 아직 계산되지 않았으면 예산 소진이 아니다 (데이터 부족과 구분)")
    void isQuotaBuyExhausted_falseWhenStarPointMissing() {
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, null, false);

        assertThat(position.isQuotaBuyExhausted()).isFalse();
    }

    @Test
    @DisplayName("쿼터매수 예산이 충분하면 예산 소진이 아니다")
    void isQuotaBuyExhausted_falseWhenBudgetSufficient() {
        // usdDeposit=1000.00 → 쿼터매수 예산 250.00, buyPrice=19.99 → floor(250/19.99)=12 (>0)
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.isQuotaBuyExhausted()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패(컴파일 오류) 확인**

Run: `./gradlew test --tests 'com.kista.domain.model.strategy.ReverseModePositionTest'`
Expected: `isQuotaBuyExhausted()` 메서드가 아직 없어 컴파일 실패 (`cannot find symbol`)

- [ ] **Step 3: `ReverseModePosition`에 predicate 구현**

`src/main/java/com/kista/domain/model/strategy/ReverseModePosition.java`의 `calcLocBuyQuantity()` 메서드(라인 43-50) 바로 다음에 추가:

```java
    // 예산 소진 판정: 별지점은 계산됐지만(데이터 존재) 쿼터매수 예산으로 1주도 못 사는 경우
    // starPointPrice==null(별지점 데이터 미계산)은 별개 상태이므로 제외
    public boolean isQuotaBuyExhausted() {
        return starPointPrice != null && starPointPrice.compareTo(BigDecimal.ZERO) > 0
                && calcLocBuyQuantity() == 0;
    }
```

파일 전체는 다음과 같아야 한다 (변경 후 43-56행):

```java
    // 쿼터매수 수량 — 별지점 아래에서 매수: 별지점 - TICK_SIZE($0.01)
    // starPointPrice가 null이거나 0 이하이면 매수 불가 (0 반환)
    public int calcLocBuyQuantity() {
        if (starPointPrice == null || starPointPrice.compareTo(BigDecimal.ZERO) <= 0) return 0;
        BigDecimal buyPrice = starPointPrice.subtract(InfinitePosition.TICK_SIZE);
        if (buyPrice.compareTo(BigDecimal.ZERO) <= 0) return 0;
        return calcLocBuyAmount().divide(buyPrice, 0, FLOOR).intValue();
    }

    // 예산 소진 판정: 별지점은 계산됐지만(데이터 존재) 쿼터매수 예산으로 1주도 못 사는 경우
    // starPointPrice==null(별지점 데이터 미계산)은 별개 상태이므로 제외
    public boolean isQuotaBuyExhausted() {
        return starPointPrice != null && starPointPrice.compareTo(BigDecimal.ZERO) > 0
                && calcLocBuyQuantity() == 0;
    }

    // 리버스모드 종료 조건: 종가 ≥ 평단 × (1 - targetProfitRate)
    // 종가가 평단 근처 이상으로 회복되면 일반모드로 복귀
    public boolean shouldExitReverseMode(BigDecimal closingPrice, BigDecimal targetProfitRate) {
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests 'com.kista.domain.model.strategy.ReverseModePositionTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/domain/model/strategy/ReverseModePosition.java src/test/java/com/kista/domain/model/strategy/ReverseModePositionTest.java
git commit -m "feat(strategy): 리버스모드 쿼터매수 예산 소진 판정 predicate 추가"
```

---

## Task 2: `ReverseInfiniteStrategy.buildOrders()` MOC 전환 분기

**Files:**
- Modify: `src/main/java/com/kista/domain/strategy/ReverseInfiniteStrategy.java:36-59`
- Modify: `src/test/java/com/kista/domain/strategy/ReverseInfiniteStrategyTest.java` (테스트 추가)

**Interfaces:**
- Consumes: `ReverseModePosition.isQuotaBuyExhausted()` (Task 1에서 정의), `ReverseModePosition.calcLocSellQuantity()`(기존), `ReverseModePosition.holdings()`/`ticker()`(기존)
- Produces: `buildOrders()`의 반환값 변경 없음(`List<Order>`) — 신규 private 메서드 `buildQuotaExhaustedOrders()`는 이 클래스 내부에서만 사용하므로 외부에 노출되지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/domain/strategy/ReverseInfiniteStrategyTest.java`에 기존 `buildOrders_assignsLocSellAndBuyLegs` 테스트 뒤에 다음 두 테스트를 추가한다:

```java
    @Test
    @DisplayName("쿼터매수 예산 소진 시 동일 수량 MOC SELL leg 한 건만 생성한다")
    void buildOrders_quotaBuyExhausted_producesMocSellOnly() {
        // usdDeposit=3.00 → 쿼터매수 예산 소진, holdings=100·divisionCount=20 → calcLocSellQuantity=10
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).hasSize(1);
        Order order = orders.get(0);
        assertThat(order.orderLeg()).isEqualTo("REVERSE_INFINITE_QUOTA_MOC_SELL");
        assertThat(order.orderType()).isEqualTo(Order.OrderType.MOC);
        assertThat(order.direction()).isEqualTo(Order.OrderDirection.SELL);
        assertThat(order.quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("쿼터매수 예산 소진이지만 매도 수량도 0이면 빈 리스트를 반환한다")
    void buildOrders_quotaBuyExhausted_emptyWhenSellQuantityZero() {
        // holdings=5·divisionCount=20 → calcLocSellQuantity=0
        ReverseModePosition position = new ReverseModePosition(
                5, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        List<Order> orders = strategy.buildOrders(position, TODAY);

        assertThat(orders).isEmpty();
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests 'com.kista.domain.strategy.ReverseInfiniteStrategyTest'`
Expected: FAIL — `buildOrders_quotaBuyExhausted_producesMocSellOnly`는 `orders`에 LOC SELL 1건만 담겨 `hasSize(1)`은 통과하지만 `orderLeg`가 `"REVERSE_INFINITE_LOC_SELL"`이라 `isEqualTo("REVERSE_INFINITE_QUOTA_MOC_SELL")`에서 실패. `buildOrders_quotaBuyExhausted_emptyWhenSellQuantityZero`는 매수만 생략되고 매도도 0이라 현재 코드로도 우연히 빈 리스트가 나와 통과할 수 있음 — 두 테스트를 함께 실행해 첫 번째가 실패하는 것을 확인한다.

- [ ] **Step 3: `ReverseInfiniteStrategy.buildOrders()`에 분기 구현**

`src/main/java/com/kista/domain/strategy/ReverseInfiniteStrategy.java`의 `buildOrders()` 메서드(라인 36-58)를 다음으로 교체한다:

```java
    // 두번째 날 이후: LOC 매도(별지점 위) + LOC 쿼터매수(별지점 아래)
    // 쿼터매수 예산 소진 시(별지점은 있으나 1주도 못 사는 경우) 매수 대신 동일 수량 MOC 매도로 청산 가속
    public List<Order> buildOrders(ReverseModePosition position, LocalDate tradeDate) {
        if (position.isQuotaBuyExhausted()) {
            return buildQuotaExhaustedOrders(position, tradeDate);
        }

        List<Order> orders = new ArrayList<>();

        // LOC 매도 — 별지점 위에서 (starPointPrice 가격으로 LOC)
        int locSellQuantity = position.calcLocSellQuantity();
        if (locSellQuantity >= 1 && position.starPointPrice() != null) {
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, SELL, locSellQuantity,
                    position.starPointPrice(), "REVERSE_INFINITE_LOC_SELL"));
            log.info("[리버스모드] LOC 매도 {}주 @ 별지점={}", locSellQuantity, position.starPointPrice());
        }

        // LOC 쿼터매수 — 별지점 아래에서 (starPointPrice - $0.01)
        int locBuyQuantity = position.calcLocBuyQuantity();
        if (locBuyQuantity >= 1 && position.starPointPrice() != null) {
            BigDecimal buyPrice = position.starPointPrice().subtract(InfinitePosition.TICK_SIZE);
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, locBuyQuantity,
                    buyPrice, "REVERSE_INFINITE_LOC_BUY"));
            log.info("[리버스모드] LOC 쿼터매수 {}주 @ {}", locBuyQuantity, buyPrice);
        }

        return orders;
    }

    // 쿼터매수 예산 소진 — 매수 생략, 동일 수량(quarter) MOC 매도로 청산 가속
    private List<Order> buildQuotaExhaustedOrders(ReverseModePosition position, LocalDate tradeDate) {
        int mocSellQuantity = position.calcLocSellQuantity();
        if (mocSellQuantity < 1) {
            log.warn("[리버스모드 예산소진] MOC 매도 수량 0 — holdings={}", position.holdings());
            return List.of();
        }
        log.info("[리버스모드 예산소진] 쿼터매수 불가 → MOC 매도 {}주", mocSellQuantity);
        return List.of(Order.planned(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity,
                BigDecimal.ZERO, "REVERSE_INFINITE_QUOTA_MOC_SELL"));
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests 'com.kista.domain.strategy.ReverseInfiniteStrategyTest'`
Expected: PASS (기존 2개 + 신규 2개 = 4 tests)

- [ ] **Step 5: 관련 도메인 테스트 전체 실행**

Run: `./gradlew test --tests 'com.kista.domain.strategy.*' --tests 'com.kista.domain.model.strategy.*'`
Expected: PASS, 0 failures

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/domain/strategy/ReverseInfiniteStrategy.java src/test/java/com/kista/domain/strategy/ReverseInfiniteStrategyTest.java
git commit -m "feat(strategy): 리버스모드 쿼터매수 예산 소진 시 MOC 매도 전환"
```

---

## Task 3: 전체 회귀 검증 및 문서 정합성 확인

**Files:**
- 변경 없음 (검증 전용 태스크)

**Interfaces:**
- Consumes: Task 1, 2에서 만든 모든 변경 사항
- Produces: 없음 — 최종 검증 결과만 확인

- [ ] **Step 1: INFINITE 전략 전체 테스트 실행**

Run: `./gradlew test --tests 'com.kista.domain.strategy.*' --tests 'com.kista.domain.model.strategy.*' --tests 'com.kista.application.service.trading.*'`
Expected: PASS, 0 failures — 특히 `CyclePositionPersistorTest`/`CycleOrderComputerTest` 계열(존재한다면)이 리버스모드 판정 흐름에서 회귀가 없는지 확인한다.

- [ ] **Step 2: 전체 컴파일 및 ArchUnit 검증**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS — 새 코드는 기존 `domain/model/strategy`, `domain/strategy` 패키지 내에서만 변경되므로 레이어 위반이 발생하지 않아야 한다.

- [ ] **Step 3: 실패 시 XML 기준 진단**

테스트 실패 시 stdout이 아닌 XML로 확인한다:

Run: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`

- [ ] **Step 4: 최종 커밋 여부 확인**

```bash
git status
git log --oneline -5
```

Expected: Task 1, 2에서 만든 두 커밋이 순서대로 보이고, working tree가 깨끗함(untracked/modified 없음).
