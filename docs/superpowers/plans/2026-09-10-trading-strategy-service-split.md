# TradingService/StrategyService God Class 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TradingService`/`StrategyService`의 확인된 중복·응집 단위(파이프라인 공통 가드, 후보수집+예산배정 블록, 캡 디스패치 중복, register 전용 로직, 조회 3종)를 별도 package-private 클래스로 추출한다. 동작은 1비트도 바꾸지 않는다 — 기존 테스트 스위트가 스펙이다.

**Architecture:** `com.kista.trading.application.service` 패키지 내부에 신규 클래스 4개(`TradingBatchGuard`, `TradingCandidatePlanner`, `StrategyCreationService`, `StrategyHistoryQueryService`)를 만들고, 기존 `TradingOrderExecutor`/`TradingPriceFetcher`는 내부 리팩터만 한다. `TradingService`/`StrategyService`는 각각 오케스트레이션/퍼사드로 축소되고, `StrategyUseCase`의 유일한 구현체는 `StrategyService`로 그대로 유지한다.

**Tech Stack:** Java 21, Spring Boot, Lombok(`@RequiredArgsConstructor`), JUnit5 + Mockito

**Spec:** `docs/superpowers/specs/2026-09-10-trading-strategy-service-split-design.md`

## Global Constraints

- 동작 변경 금지 — 순수 구조 리팩터. 테스트 assertion을 고치게 되면 그 자체가 회귀 신호.
- 신규 클래스는 전부 package-private(`class`, `@Component`/`@Service`, no `public`) — `com.kista.trading.application.service`는 internal 패키지.
- 주석 규칙(CLAUDE.md 3.2): 이전하는 코드의 기존 주석은 그대로 보존, 신규 클래스 헤더에 역할 한 줄 주석 추가.
- 각 작업 완료 후 좁은 스코프 테스트만 실행 (`./gradlew test --tests 'com.kista.trading.application.service.<클래스>Test'`). 전체 스위트(`com.kista.trading.*`)는 마지막 Task에서 1회만.
- 커밋 메시지: 한글 Conventional Commit(`refactor(trading):`, `refactor(strategy):`), `docs/agents/constraints.md`의 author/attribution 규칙 그대로.

---

### Task 1: TradingOrderExecutor 캡 디스패치 중복 제거

**Files:**
- Modify: `src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java:43-84`
- Test: `src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java` (기존 파일, 신규 테스트 불필요 — 기존 테스트로 회귀 검증)

**Interfaces:**
- Consumes: 없음(기존 필드 그대로)
- Produces: `placeOrders(...)`/`placeAtOpenOrders(...)` 시그니처 변경 없음 — 다른 Task가 이 클래스를 더 이상 건드리지 않음

- [ ] **Step 1: 현재 테스트 통과 확인 (베이스라인)**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.TradingOrderExecutorTest'`
Expected: PASS (수정 전 베이스라인)

- [ ] **Step 2: 캡 디스패치를 공용 private 메서드로 추출**

`TradingOrderExecutor.java`의 `placeAtOpenOrders`(43-63)와 `placeOrders`(68-84) 안의 `if (currentPrice != null) { ... 3분기 ... }` 블록은 `atOpen` 여부에 따라 `capIfNeededAtOpen`/`capIfNeeded`, `capPrivacyIfNeededAtOpen`/`capPrivacyIfNeeded`, `capVrIfNeededAtOpen`/`capVrIfNeeded`만 다르고 나머지는 동일하다. 아래로 교체:

```java
    // AT_OPEN PLANNED 주문 접수 — 개장 스케쥴러 선접수 + 개장 후 수동실행 공용
    // BUY cap 보정을 AT_OPEN 스코프(capIfNeededAtOpen/capPrivacyIfNeededAtOpen/capVrIfNeededAtOpen)로 적용한 뒤
    // AT_OPEN PLANNED만 재조회해 접수한다 — 동일 사이클에 공존 가능한 AT_CLOSE PLANNED(미도래)는 건드리지 않는다
    // (findPlannedByCycleAndDate를 그대로 쓰면 접수 전 AT_CLOSE 주문까지 캡 재산정 대상이 되는 버그가 발생함)
    List<Order> placeAtOpenOrders(LocalDate tradeDate, Account account, UUID strategyCycleId,
                                  BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        applyCap(true, tradeDate, account, strategyCycleId, currentPrice, position, vrPosition, strategy);
        List<Order> atOpenOrders = orderPort.findAtOpenPlannedByCycleAndDate(strategyCycleId, tradeDate);
        if (atOpenOrders.isEmpty()) {
            log.info("[{}] 개장 선접수할 주문 없음", account.nickname());
            return List.of();
        }
        List<Order> placed = placeEach(atOpenOrders, account);
        log.info("[{}] 개장 주문 {}건 선접수 (성공/{} 시도)", account.nickname(), placed.size(), atOpenOrders.size());
        return placed;
    }

    // capIfNeeded/capPrivacyIfNeeded/capVrIfNeeded 적용 여부는 전략의 priceCapMode()로 결정
    // INFINITE_POSITION이어도 position이 null(재계산 skip 케이스)이면 캡 미적용 — 기존 동작 그대로
    // VR_POSITION이어도 vrPosition이 null(재계산 skip 케이스)이면 캡 미적용 — 동일 원칙
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        applyCap(false, today, account, strategyCycleId, currentPrice, position, vrPosition, strategy);
        List<Order> planned = orderPort.findPlannedByCycleAndDate(strategyCycleId, today);
        List<Order> placed = placeEach(planned, account);
        log.info("[{}] 주문 {}건 접수 (성공/{} 시도)", account.nickname(), placed.size(), planned.size());
        return placed;
    }

    // AT_OPEN/AT_CLOSE 공용 BUY 가격 캡 디스패치 — atOpen에 따라 BuyOrderPriceCapper의 AtOpen 오버로드만 갈아탄다
    private void applyCap(boolean atOpen, LocalDate date, Account account, UUID strategyCycleId,
                          BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        if (currentPrice == null) return;
        CycleOrderStrategy.PriceCapMode mode = cycleOrderStrategies.of(strategy.type()).priceCapMode();
        if (mode == CycleOrderStrategy.PriceCapMode.INFINITE_POSITION && position != null) {
            if (atOpen) {
                buyOrderPriceCapper.capIfNeededAtOpen(date, account, strategyCycleId, currentPrice, position);
            } else {
                buyOrderPriceCapper.capIfNeeded(date, account, strategyCycleId, currentPrice, position);
            }
        } else if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
            if (atOpen) {
                buyOrderPriceCapper.capPrivacyIfNeededAtOpen(date, account, strategyCycleId, currentPrice);
            } else {
                buyOrderPriceCapper.capPrivacyIfNeeded(date, account, strategyCycleId, currentPrice);
            }
        } else if (mode == CycleOrderStrategy.PriceCapMode.VR_POSITION && vrPosition != null) {
            if (atOpen) {
                buyOrderPriceCapper.capVrIfNeededAtOpen(date, account, strategyCycleId, currentPrice, vrPosition, strategy.ticker());
            } else {
                buyOrderPriceCapper.capVrIfNeeded(date, account, strategyCycleId, currentPrice, vrPosition, strategy.ticker());
            }
        }
    }
```

- [ ] **Step 3: 테스트 재실행 — assertion 무변경 확인**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.TradingOrderExecutorTest'`
Expected: PASS, 기존과 동일한 테스트 케이스 그대로

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java
git commit -m "$(cat <<'EOF'
refactor(trading): TradingOrderExecutor BUY 캡 디스패치 중복 제거

placeOrders/placeAtOpenOrders의 PriceCapMode 3분기 로직을
applyCap(atOpen, ...) 공용 메서드로 통합. 동작 변경 없음.
EOF
)"
```

---

### Task 2: TradingBatchGuard 추출

**Files:**
- Create: `src/main/java/com/kista/trading/application/service/TradingBatchGuard.java`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java:98-577` (아래 명시된 부분만)
- Test: `src/test/java/com/kista/trading/application/service/TradingServiceTest.java`

**Interfaces:**
- Produces:
  - `<T> Optional<T> runSafely(String phase, BatchContext ctx, TradingBatchGuard.ThrowingSupplier<T> supplier) throws InterruptedException`
  - `void notifyErrorSafely(BatchContext ctx, Exception e)`
  - `void notifyBatchInterrupted(List<BatchContext> contexts)`
  - `@FunctionalInterface interface ThrowingSupplier<T> { T get() throws Exception; }` (package-private, `TradingBatchGuard` 안에 nested)
- Consumes(Task 4에서): `TradingCandidatePlanner`가 이 클래스를 주입받아 `runSafely` 사용

- [ ] **Step 1: 신규 파일 생성 — TradingService에서 그대로 이전**

`TradingService.java`의 `ThrowingSupplier` 인터페이스(548-551), `runSafely`(553-563), `notifyErrorSafely`(565-576), `notifyBatchInterrupted`(243-251)를 아래처럼 옮긴다. 로직은 원본과 완전히 동일 — `Exception notifyEx` catch, 로그 포맷 그대로.

```java
package com.kista.trading.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.TradingErrorEvent;

import java.util.List;
import java.util.Optional;

// 전략별 단계 실행 격리 가드 — 실패 시 로그+관리자/사용자 알림 후 Optional.empty() 반환 (InterruptedException은 예외적으로 재throw)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingBatchGuard {

    private final ApplicationEventPublisher eventPublisher;

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    <T> Optional<T> runSafely(String phase, BatchContext ctx, ThrowingSupplier<T> supplier) throws InterruptedException {
        try {
            return Optional.ofNullable(supplier.get());
        } catch (InterruptedException e) {
            throw e; // InterruptedException은 삼키지 않음
        } catch (Exception e) {
            log.error("[strategyId={}] {} 오류: {}", ctx.strategy().id(), phase, e.getMessage(), e);
            notifyErrorSafely(ctx, e);
            return Optional.empty();
        }
    }

    void notifyErrorSafely(BatchContext ctx, Exception e) {
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 관리자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user().id(), e.getMessage()));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 사용자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
    }

    // 인터럽트 시점에 아직 증권사 접수가 안 된 전략들에게 알림 (증권사 접수 완료된 전략은 대상 아님)
    void notifyBatchInterrupted(List<BatchContext> contexts) {
        contexts.forEach(ctx -> {
            try {
                eventPublisher.publishEvent(new BatchInterruptedEvent(ctx.user().id(), ctx.account().id()));
            } catch (Exception notifyEx) {
                log.warn("[strategyId={}] 인터럽트 알림 발송 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
            }
        });
    }
}
```

- [ ] **Step 2: TradingService에서 이전한 코드 제거 + TradingBatchGuard 주입**

`TradingService.java`:
- `private final TradingParallelRunner parallelRunner;` 아래에 `private final TradingBatchGuard batchGuard;` 필드 추가
- `ThrowingSupplier` 인터페이스(548-551), `runSafely`(553-563), `notifyErrorSafely`(565-576) 삭제
- `notifyBatchInterrupted`(243-251) 삭제
- 이 메서드들을 호출하던 모든 지점을 `batchGuard.` 접두사로 교체:
  - `notifyBatchInterrupted(...)` 호출 2곳(146, 367) → `batchGuard.notifyBatchInterrupted(...)`
  - `runSafely(...)` 호출 전부(170, 196, 224, 377, 397, 433, 442, 456 부근) → `batchGuard.runSafely(...)`
  - `waitFor` 안의 `eventPublisher.publishEvent(new TradingErrorEvent(...))` (482)는 그대로 유지 — `TradingBatchGuard`로 옮기지 않는다(가드 대상 실행이 아니라 대기 자체의 인터럽트 알림)

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL — 남은 `TradingService.java` 참조가 전부 `batchGuard.`로 정상 치환됐는지 확인

- [ ] **Step 4: TradingServiceTest 생성자 재배선**

`TradingServiceTest.java`에 `@Mock TradingBatchGuard batchGuard;` 필드 추가하고, `setUp()`에서 `new TradingService(...)` 호출 마지막에 `batchGuard` 인자 추가. `batchGuard.runSafely(...)`가 실제로 supplier를 실행하도록 스텁 필요 — 기존 로직과 동일하게 동작해야 하므로 아래 lenient 스텁 추가:

```java
lenient().when(batchGuard.runSafely(anyString(), any(BatchContext.class), any()))
        .thenAnswer(invocation -> {
            TradingBatchGuard.ThrowingSupplier<?> supplier = invocation.getArgument(2);
            try {
                return Optional.ofNullable(supplier.get());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                return Optional.empty();
            }
        });
lenient().doNothing().when(batchGuard).notifyBatchInterrupted(anyList());
```

(이 스텁은 `TradingBatchGuard`의 실제 구현을 그대로 흉내 — mock이 원본 동작을 대체하므로 기존 `TradingServiceTest`의 인터럽트/오류 격리 assertion이 변경 없이 통과해야 한다)

- [ ] **Step 5: 테스트 실행 — assertion 무변경 확인**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.TradingServiceTest'`
Expected: PASS, 51개+ 기존 테스트 케이스 assertion 그대로

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/TradingBatchGuard.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): 배치 실행 격리 가드를 TradingBatchGuard로 추출

runSafely/notifyErrorSafely/notifyBatchInterrupted를 TradingService에서
분리 — 다음 작업(TradingCandidatePlanner)이 동일 가드를 공유하기 위함.
동작 변경 없음.
EOF
)"
```

---

### Task 3: TradingPriceFetcher에 가격 컨텍스트 로딩 흡수

**Files:**
- Modify: `src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java`
- Test: `src/test/java/com/kista/trading/application/service/TradingPriceFetcherTest.java` (있으면 회귀 확인), `TradingServiceTest.java`

**Interfaces:**
- Produces (TradingPriceFetcher 신규 public 패키지 메서드):
  - `record PriceContext(List<StrategyTicker> cycleTickers, Account priceAccount, Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase)`
  - `PriceContext loadPriceContext(List<BatchContext> contexts, LocalDate date)`
  - `Map<StrategyTicker, BigDecimal> reloadPlacementPrices(List<TradingService.CycleState> states)` — **주의:** `CycleState`는 Task 4에서 `TradingCandidatePlanner`로 이전되며 package-private으로 승격되므로, 이 메서드 시그니처는 Task 4 완료 후 `TradingCandidatePlanner.CycleState`를 참조하도록 맞춘다. Task 3 시점엔 아직 `TradingService.CycleState`이므로 그 상태로 작성.
  - `Account selectPriceAccount(List<BatchContext> contexts)`

- [ ] **Step 1: TradingPriceFetcher에 privacyTradePort 필드 + 3개 메서드 추가**

`TradingPriceFetcher.java` 상단에 필드 추가:
```java
    private final com.kista.privacy.application.port.output.PrivacyTradePort privacyTradePort;
```
(Lombok `@RequiredArgsConstructor`가 생성자에 자동 반영)

클래스 하단(`fetchWithFallback` 위)에 아래 3개 추가 — `TradingService.java`의 `loadPriceContext`(508-524), `reloadPlacementPrices`(489-497), `selectPriceAccount`(499-506)를 그대로 옮기되 `priceFetcher.` 접두사를 `this.`로, 클래스 내부 참조라 접두사 제거:

```java
    // 배치 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 결과 — executeBatch/placeOpenOrders 공통
    record PriceContext(
            List<StrategyTicker> cycleTickers,
            Account priceAccount,
            Map<StrategyTicker, PriceSnapshot> startPriceSnapshots,
            com.kista.privacy.domain.model.PrivacyTradeBase privacyBase
    ) {}

    // 배치 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 — executeBatch/placeOpenOrders 공통
    // date: executeBatch는 today(당일), placeOpenOrders는 tradeDate(익일 US 거래일)
    PriceContext loadPriceContext(List<BatchContext> contexts, LocalDate date) {
        List<StrategyTicker> cycleTickers = contexts.stream()
                .map(c -> c.strategy().ticker())
                .distinct().toList();
        Account priceAccount = selectPriceAccount(contexts); // Toss 계좌 우선
        Map<StrategyTicker, PriceSnapshot> startPriceSnapshots = fetchPriceSnapshots(cycleTickers, priceAccount);

        boolean hasPrivacy = contexts.stream().anyMatch(c -> c.strategy().isPrivacy());
        com.kista.privacy.domain.model.PrivacyTradeBase privacyBase = hasPrivacy
                ? privacyTradePort.findTodayTrade(date).orElse(null)
                : null;

        return new PriceContext(cycleTickers, priceAccount, startPriceSnapshots, privacyBase);
    }

    // 증권사 접수 직전 ticker별 현재가 일괄 재조회 — fetchPrices가 ticker당 1회 배치 조회를 보장
    // prevClose는 필요 없으므로(cap 판단은 현재가만 사용) fetchPriceSnapshots가 아닌 fetchPrices 사용
    Map<StrategyTicker, BigDecimal> reloadPlacementPrices(List<TradingService.CycleState> states) {
        List<StrategyTicker> tickers = states.stream()
                .map(state -> state.ctx().strategy().ticker())
                .distinct().toList();
        Account priceAccount = selectPriceAccount(states.stream().map(TradingService.CycleState::ctx).toList());
        return fetchPrices(tickers, priceAccount);
    }

    // 가격 조회에 사용할 계좌 선택 — Toss 계좌가 있으면 우선 사용 (토스 시세 API 일관성)
    Account selectPriceAccount(List<BatchContext> contexts) {
        return contexts.stream()
                .map(BatchContext::account)
                .filter(a -> a.broker() == com.kista.sharedkernel.Broker.TOSS)
                .findFirst()
                .orElseGet(() -> contexts.getFirst().account());
    }
```

- [ ] **Step 2: TradingService에서 이전한 코드 제거 + 호출부 갱신**

`TradingService.java`:
- `loadPriceContext`(508-524), `reloadPlacementPrices`(489-497), `selectPriceAccount`(499-506) 삭제
- `PriceContext` record(90-96) 삭제 — 이제 `TradingPriceFetcher.PriceContext` 사용
- `private final PrivacyTradePort privacyTradePort;` 필드 삭제(더 이상 TradingService에서 직접 안 씀 — `TradingPriceFetcher`가 소유)
- 호출부 갱신: `loadPriceContext(contexts, today)` → `priceFetcher.loadPriceContext(contexts, today)` (두 곳: `executeBatch`, `placeOpenOrders`), `reloadPlacementPrices(states)` → `priceFetcher.reloadPlacementPrices(states)` (두 곳), `selectPriceAccount(...)` 직접 호출부는 없음(이미 위 두 메서드 내부에서만 쓰였음 — 그대로 삭제)
- 반환 타입이 `PriceContext` → `TradingPriceFetcher.PriceContext`로 바뀌는 지역변수 선언부(`PriceContext priceCtx = ...`) 타입도 `TradingPriceFetcher.PriceContext`로 수정

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: TradingServiceTest에서 privacyTradePort 스텁 위치 확인**

기존 `TradingServiceTest`가 `privacyTradePort.findTodayTrade(...)`를 직접 스텁하고 있었다면, 이제 `TradingPriceFetcher`가 소비하므로 `priceFetcher`가 실제 객체(mock 아님, 프로덕션 `TradingPriceFetcher` 인스턴스)로 생성돼 있는지 확인 — 기존 테스트가 `priceFetcher`를 `@Mock`으로 두고 있었다면 `TradingPriceFetcher`를 실제 생성자 호출로 바꾸고 `registry`/`eventPublisher`/`privacyTradePort` mock을 그 생성자에 전달해야 한다. `TradingServiceTest.java` 상단의 `priceFetcher` 선언부를 확인하고 필요 시:

```java
priceFetcher = new TradingPriceFetcher(tradingRegistry, eventPublisher, privacyTradePort);
```

형태로 실제 인스턴스화(이미 `MarketEventNotifier`/`TradingOrderBudgetAllocator`도 같은 패턴으로 실제 생성하고 있으므로 기존 관례 그대로 따름).

- [ ] **Step 5: 테스트 실행**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.TradingServiceTest'`
Expected: PASS, assertion 무변경

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): 가격 컨텍스트 로딩을 TradingPriceFetcher로 흡수

loadPriceContext/reloadPlacementPrices/selectPriceAccount를
TradingService에서 TradingPriceFetcher(기존 가격 조회 전담 클래스)로
이전. 동작 변경 없음.
EOF
)"
```

---

### Task 4: TradingCandidatePlanner 추출

**Files:**
- Create: `src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java`
- Test: `src/test/java/com/kista/trading/application/service/TradingServiceTest.java`

**Interfaces:**
- Produces (전부 package-private, `TradingCandidatePlanner` 소유로 이전):
  - `record CycleState(BatchContext ctx, AccountBalance balance, InfinitePosition position, VrPosition vrPosition, BigDecimal startPrice, PrivacyTradeBase privacyBase)`
  - `record CyclePlanCandidate(CycleState state, List<PlannedOrder> creatableOrders, boolean hasExistingOrders)`
  - `record SaveAllocationResult(Set<BatchContext> savedContexts)`
  - `List<CycleState> planAll(List<BatchContext> contexts, Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException`
  - `List<CyclePlanCandidate> collectCandidates(List<BatchContext> contexts, Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase, LocalDate tradeDate, Set<OrderTiming> creatableTimings) throws InterruptedException` (기존 `placeOpenOrders`의 인라인 for 루프 + `saveAllocatedOrders` 앞부분을 재사용 가능하게 별도로 뽑음 — Step 2 참고)
  - `SaveAllocationResult saveAllocatedOrders(List<CyclePlanCandidate> candidates, LocalDate tradeDate) throws InterruptedException`
- Consumes: `TradingBatchGuard`(Task 2), 기존 `orderPort`/`orderComputer`/`orderPlanner`/`priceCapper`/`cycleOrderStrategies`/`budgetAllocator`/`balanceLoader`

- [ ] **Step 1: 신규 파일 생성 — 후보수집+예산배정 블록 이전**

`TradingService.java`의 `planAll`(166-184), `collectCycleCandidate`(263-301), `buildCycleStateFromExistingOrders`(315-335), `filterCreatableOrders`(254-260), `validateConcreteOrderLegs`(303-311), `saveAllocatedOrders`(410-466), `loadBalance`(234-240)와 record `CycleState`(68-75)/`CyclePlanCandidate`(81-85)/`SaveAllocationResult`(88)를 아래 골격으로 옮긴다. 메서드 본문은 원본 그대로(내부 호출부만 `this.`로, `runSafely` → `batchGuard.runSafely`로 교체):

```java
package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.PriceSnapshot;
import com.kista.matching.domain.model.*;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 전략별 후보 수집(잔고 로드 + 전략 계산 + 가격 캡) + 계좌별 예산 배정 — executeBatch(AT_CLOSE)/placeOpenOrders(AT_OPEN) 공용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingCandidatePlanner {

    private final OrderPort orderPort;
    private final CycleOrderComputer orderComputer;
    private final TradingOrderPlanner orderPlanner;
    private final BuyOrderPriceCapper priceCapper;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final TradingOrderBudgetAllocator budgetAllocator;
    private final TradingBalanceLoader balanceLoader;
    private final ApplicationEventPublisher eventPublisher; // 예수금 부족 알림(InsufficientBalanceEvent)
    private final TradingBatchGuard batchGuard;

    // 슬롯별 후보 수집 결과: 전략별 잔고·전략 계산 상태
    record CycleState(
            BatchContext ctx,
            AccountBalance balance,
            InfinitePosition position,      // INFINITE만 non-null (신규 계산 시 — pre-existing skip 케이스는 null)
            VrPosition vrPosition,          // VR만 non-null (신규 계산 시 — BuyOrderPriceCapper VR_POSITION 보정용)
            BigDecimal startPrice,          // 배치 시작 시점 가격 — placeAll()에서 접수 직전 재조회(reloadPlacementPrices) 실패 시 폴백으로만 사용
            PrivacyTradeBase privacyBase    // PRIVACY만 non-null (rotation 시 최소금액 산정용)
    ) {}

    // 전략 계산 결과 중 신규 생성 가능한 주문만 allocator에 전달하기 위한 후보
    record CyclePlanCandidate(
            CycleState state,
            List<PlannedOrder> creatableOrders,
            boolean hasExistingOrders
    ) {}

    // 예산 배정 후 실제 저장된 컨텍스트만 다음 단계 진입 대상으로 사용한다
    record SaveAllocationResult(Set<BatchContext> savedContexts) {}

    // 전략별 후보를 먼저 수집하고 계좌별 예산 배정 후 AT_CLOSE 주문만 저장
    List<CycleState> planAll(List<BatchContext> contexts, Map<StrategyTicker, PriceSnapshot> startPriceSnapshots,
                              PrivacyTradeBase privacyBase, LocalDate today) throws InterruptedException {
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            batchGuard.runSafely("plan 후보 생성", ctx,
                    () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, today,
                            EnumSet.of(OrderTiming.AT_CLOSE)))
                    .ifPresent(candidates::add);
        }
        SaveAllocationResult result = saveAllocatedOrders(candidates, today);
        return candidates.stream()
                .filter(candidate -> candidate.hasExistingOrders()
                        || result.savedContexts().contains(candidate.state().ctx())
                        // 롤오버 판정이 필요한 전략(VR)은 당일 주문 0건이어도 마감 리포트까지 흘려보내야
                        // saveCyclePosition→rollIfDue가 매일 실행된다 (예수금 0으로 사다리를 못 만드는 날에도 롤오버는 계속 판정돼야 함)
                        || cycleOrderStrategies.of(candidate.state().ctx().strategy().type()).requiresRolloverCheck())
                .map(CyclePlanCandidate::state)
                .toList();
    }

    // 개장 후보 수집 — placeOpenOrders 전용, planAll과 달리 저장까지 이 메서드 밖(TradingService)에서 처리
    List<CyclePlanCandidate> collectOpenCandidates(List<BatchContext> contexts,
            Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase,
            LocalDate tradeDate) throws InterruptedException {
        List<CyclePlanCandidate> candidates = new ArrayList<>();
        for (BatchContext ctx : contexts) {
            batchGuard.runSafely("개장 order 후보 생성", ctx,
                    () -> collectCycleCandidate(ctx, startPriceSnapshots, privacyBase, tradeDate,
                            EnumSet.of(OrderTiming.AT_OPEN)))
                    .ifPresent(candidates::add);
        }
        return candidates;
    }

    // 잔고 로드 — KIS·Toss 모두 cycle_position DB 이력 사용 (전략 공식 기준)
    private AccountBalance loadBalance(Strategy strategy, Account account) {
        AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());
        return balance;
    }

    // creatableTimings 필터 후 TradingOrderSlots로 기존 주문과 동일 슬롯을 제외한다 (TradingPreviewService와 공유 기준)
    private List<PlannedOrder> filterCreatableOrders(List<PlannedOrder> plannedTemplates, List<Order> existingOrders,
                                              Set<OrderTiming> creatableTimings) {
        List<PlannedOrder> timingFiltered = plannedTemplates.stream()
                .filter(order -> creatableTimings.contains(order.timing()))
                .toList();
        return TradingOrderSlots.excludeExisting(timingFiltered, existingOrders);
    }

    // 사이클별 후보 수집 — 기존 주문은 보존하고 새 슬롯만 allocator 검증 대상으로 분리한다
    private CyclePlanCandidate collectCycleCandidate(BatchContext ctx,
            Map<StrategyTicker, PriceSnapshot> startPriceSnapshots, PrivacyTradeBase privacyBase,
            LocalDate tradeDate, Set<OrderTiming> creatableTimings) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        AccountBalance balance = loadBalance(strategy, account);
        PriceSnapshot priceSnapshot = startPriceSnapshots.get(strategy.ticker());
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
        List<Order> existingOrders = orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), tradeDate);
        CycleOrderStrategy strategyHandler = cycleOrderStrategies.of(strategy.type());
        if (!existingOrders.isEmpty()
                && strategyHandler.canSkipOrderComputation(
                        existingOrders.stream().map(Order::toPlanned).toList(), creatableTimings)) {
            CycleState existingState = buildCycleStateFromExistingOrders(
                    ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size(), false);
            return new CyclePlanCandidate(existingState, List.of(), true);
        }
        Optional<CycleOrderStrategy.OrderPlan> planOpt = orderComputer.compute(
                balance, strategy, prevClosePrice, tradeDate, ctx.currentCycle(), privacyBase, account.nickname(), price);
        if (planOpt.isEmpty()) {
            log.info("[{}] 전략 계산 skip (PRIVACY 기준 미수신 등)", account.nickname());
            if (existingOrders.isEmpty()) return null;
            CycleState existingState = buildCycleStateFromExistingOrders(
                    ctx, balance, priceSnapshot, privacyBase, tradeDate, existingOrders.size(), true);
            return new CyclePlanCandidate(existingState, List.of(), true);
        }

        // 예산 배정 전에 전략별 가격 cap을 반영해 최종 BUY 수량과 correction 주문까지 포함한다.
        List<PlannedOrder> preparedOrders = priceCapper.prepareForAllocation(
                planOpt.get().orders(), price, planOpt.get().position(), planOpt.get().vrPosition(), strategy.ticker(),
                cycleOrderStrategies.of(strategy.type()).priceCapMode(), tradeDate);
        validateConcreteOrderLegs(strategy, preparedOrders);
        List<PlannedOrder> creatableOrders = filterCreatableOrders(
                preparedOrders, existingOrders, creatableTimings);
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        CycleState state = new CycleState(ctx, balance, planOpt.get().position(), planOpt.get().vrPosition(), price, privacyBaseForState);
        return new CyclePlanCandidate(state, creatableOrders, !existingOrders.isEmpty());
    }

    private void validateConcreteOrderLegs(Strategy strategy, List<PlannedOrder> orders) {
        List<PlannedOrder> unknownLegOrders = orders.stream()
                .filter(order -> PlannedOrder.UNKNOWN_LEG.equals(order.orderLeg()))
                .toList();
        if (!unknownLegOrders.isEmpty()) {
            throw new IllegalStateException("전략 주문 leg 누락: strategyType="
                    + strategy.type() + ", count=" + unknownLegOrders.size());
        }
    }

    // 오늘 PLANNED·PLACED 주문이 이미 있을 때 캡 보정을 위해 position만 재계산 (저장 없음)
    // INFINITE: 필요할 때만 position 재계산, PRIVACY: privacyBase만 담아 반환
    private CycleState buildCycleStateFromExistingOrders(BatchContext ctx, AccountBalance balance,
            PriceSnapshot priceSnapshot, PrivacyTradeBase privacyBase, LocalDate today, int existingCount,
            boolean recalculateInfinitePosition) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        BigDecimal price = priceSnapshot != null ? priceSnapshot.current() : null;
        log.info("[{}] 오늘 주문 {}건 존재 — 재계산 skip", account.nickname(), existingCount);
        if (strategy.isInfinite() && recalculateInfinitePosition) {
            BigDecimal prevClosePrice = PriceSnapshot.prevCloseOrNull(priceSnapshot);
            InfinitePosition recalcPos = orderComputer.compute(
                    balance, strategy, prevClosePrice, today, ctx.currentCycle(), null, account.nickname(), price)
                    .map(CycleOrderStrategy.OrderPlan::position).orElse(null);
            return new CycleState(ctx, balance, recalcPos, null, price, null);
        }
        PrivacyTradeBase privacyBaseForState = strategy.isPrivacy() ? privacyBase : null;
        return new CycleState(ctx, balance, null, null, price, privacyBaseForState);
    }

    // allocator 승인 주문만 PLANNED 저장하고 BUY/SELL 거절 사이클에는 기존 잔고 부족 알림을 재사용한다
    SaveAllocationResult saveAllocatedOrders(List<CyclePlanCandidate> candidates, LocalDate tradeDate)
            throws InterruptedException {
        Map<UUID, List<TradingOrderBudgetAllocator.Candidate>> candidatesByAccount = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> !candidate.creatableOrders().isEmpty())
                .map(candidate -> new TradingOrderBudgetAllocator.Candidate(
                        candidate.state().ctx(), candidate.creatableOrders()))
                .forEach(candidate -> candidatesByAccount
                        .computeIfAbsent(candidate.ctx().account().id(), ignored -> new ArrayList<>())
                        .add(candidate));

        Set<BatchContext> savedContexts = new LinkedHashSet<>();
        List<TradingOrderBudgetAllocator.Allocation> allocations = new ArrayList<>();

        TradingOrderBudgetAllocator.LiveQuotes liveQuotes =
                budgetAllocator.fetchLiveQuotes(List.copyOf(candidatesByAccount.values()));

        for (List<TradingOrderBudgetAllocator.Candidate> accountCandidates : candidatesByAccount.values()) {
            BatchContext firstContext = accountCandidates.getFirst().ctx();
            UUID accountId = firstContext.account().id();
            Optional<TradingOrderBudgetAllocator.Allocation> allocation = batchGuard.runSafely("계좌 주문 예산 배정", firstContext,
                    () -> budgetAllocator.allocate(accountCandidates, tradeDate, liveQuotes.require(accountId)));
            if (allocation.isPresent()) {
                allocations.add(allocation.get());
            }
        }

        for (TradingOrderBudgetAllocator.Allocation allocation : allocations) {
            for (TradingOrderBudgetAllocator.Candidate approved : allocation.approved()) {
                Optional<BatchContext> saved = batchGuard.runSafely("계획 주문 저장", approved.ctx(), () -> {
                    orderPlanner.savePlannedOrders(
                            approved.orders(), approved.ctx().account(), approved.ctx().currentCycle().id());
                    return approved.ctx();
                });
                if (saved.isPresent()) {
                    savedContexts.add(saved.get());
                }
            }

            Set<BatchContext> rejectedContexts = Stream.concat(
                            allocation.rejectedBuy().stream(), allocation.rejectedSell().stream())
                    .map(TradingOrderBudgetAllocator.Candidate::ctx)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (BatchContext ctx : rejectedContexts) {
                batchGuard.runSafely("예수금 부족 알림", ctx, () -> {
                        eventPublisher.publishEvent(new InsufficientBalanceEvent(
                                ctx.user().id(), ctx.account().id(), null, ctx.strategy().ticker(), ctx.strategy().type()));
                        return null;
                    });
            }
        }

        return new SaveAllocationResult(Set.copyOf(savedContexts));
    }
}
```

- [ ] **Step 2: TradingService에서 이전한 코드 제거 + candidatePlanner 주입**

`TradingService.java`:
- `private final TradingBatchGuard batchGuard;` 아래에 `private final TradingCandidatePlanner candidatePlanner;` 필드 추가
- 삭제: `CycleState`/`CyclePlanCandidate`/`SaveAllocationResult` record, `planAll`, `collectCycleCandidate`, `buildCycleStateFromExistingOrders`, `filterCreatableOrders`, `validateConcreteOrderLegs`, `saveAllocatedOrders`, `loadBalance`
- 이제 필요 없어진 필드 삭제: `balanceLoader`, `orderComputer`, `orderPlanner`, `priceCapper`, `cycleOrderStrategies`, `budgetAllocator` (전부 `TradingCandidatePlanner`로 이전)
- `executeBatch`의 `planAll(contexts, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), today)` 호출 → `candidatePlanner.planAll(...)`으로 교체, 반환 타입 `List<CycleState>` → `List<TradingCandidatePlanner.CycleState>`
- `placeOpenOrders`의 인라인 후보수집 for 루프(375-381) → `candidatePlanner.collectOpenCandidates(contexts, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), tradeDate)` 호출로 교체
- `placeOpenOrders`의 `saveAllocatedOrders(candidates, tradeDate)` 호출 → `candidatePlanner.saveAllocatedOrders(candidates, tradeDate)`
- `placeAll`/`reportAll`/`placeAtOpenPlannedOrders`가 참조하는 `CycleState`/`CyclePlacedState` 타입 중 `CycleState`는 이제 `TradingCandidatePlanner.CycleState`를 가리키도록 타입 참조 수정(`CyclePlacedState`는 `TradingService` 소유로 유지 — `TradingCandidatePlanner.CycleState`를 감싸는 record이므로 필드 타입만 갱신)
- Task 3에서 만든 `TradingPriceFetcher.reloadPlacementPrices(List<TradingService.CycleState> states)` 시그니처를 `List<TradingCandidatePlanner.CycleState> states`로 갱신(Task 3 Step 1에서 남긴 TODO 해소)

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: TradingServiceTest 생성자 재배선**

`TradingServiceTest.java`:
- 삭제된 필드(`balanceLoader`, `orderComputer`, `orderPlanner`, `priceCapper`, `cycleOrderStrategies`, `budgetAllocator`)를 실제 `TradingCandidatePlanner` 인스턴스 생성에 전달하도록 재배선:

```java
TradingCandidatePlanner candidatePlanner = new TradingCandidatePlanner(
        orderPort, orderComputer, orderPlanner, priceCapper, cycleStrategies,
        budgetAllocator, balanceLoader, eventPublisher, batchGuard);
```

- `new TradingService(...)` 호출의 인자 목록을 새 필드 순서에 맞게 축소하고 `candidatePlanner` 추가
- 테스트가 `CycleState`/`CyclePlanCandidate` 타입을 직접 참조하고 있었다면 `TradingCandidatePlanner.CycleState` 등으로 import 경로 수정

- [ ] **Step 5: 테스트 실행**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.TradingServiceTest'`
Expected: PASS, assertion 무변경 (constructor 배선만 바뀜)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/TradingCandidatePlanner.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading): 후보수집+예산배정 블록을 TradingCandidatePlanner로 추출

executeBatch(AT_CLOSE)/placeOpenOrders(AT_OPEN)가 공유하던 collectCycleCandidate/
saveAllocatedOrders 등을 별도 클래스로 분리. TradingService는 최상위
오케스트레이션(대기·리포트 순서)만 남는다. 동작 변경 없음.
EOF
)"
```

---

### Task 5: StrategyCreationService 추출

**Files:**
- Create: `src/main/java/com/kista/trading/application/service/StrategyCreationService.java`
- Modify: `src/main/java/com/kista/trading/application/service/StrategyService.java`
- Test: `src/test/java/com/kista/trading/application/service/StrategyServiceTest.java`

**Interfaces:**
- Produces: `StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd)` — `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 그대로 이 메서드에 유지
- Consumes: 없음(신규)

- [ ] **Step 1: 신규 파일 생성 — register() 전체 + 전용 헬퍼 이전**

`StrategyService.java`의 `register`(71-132), `validateBootstrapPosition`(134-137), `resolveVrValue`(139-147), `resolveScheduledStart`(149-157), `fetchMarketPrice`(159-164), `resolveCreationSettings`(166-178), `validateVrCommand`(180-244), `normalizeVrRampParams`(246-266), `normalizeMoney`(268-271), `validateUniqueTicker`(274-278), `validateBalanceIfRequired`(281-291), `saveStrategyWithVersion`(295-313), `saveInitialCycleAndPosition`(318-342), `calcFreeCash`(440-450)와 record `SavedStrategyAndVersion`(345)/`InitialCycleResult`(348-349)/`VrRampParams`(352-354)를 그대로 옮긴다:

```java
package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.port.output.MarginPort;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.application.service.BrokerCallGuard;
import com.kista.matching.domain.model.BootstrapPosition;
import com.kista.sharedkernel.*;
import com.kista.trading.application.port.output.*;
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
import com.kista.trading.domain.model.*;
import com.kista.trading.domain.strategy.StrategyCreationResolver.ResolvedCreation;
import com.kista.trading.domain.strategy.StrategyCreationResolvers;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.UserSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

// 신규 전략 등록 전용 — VR 파라미터 검증, 잔고 검증, strategy/version/cycle/position 초기 저장 일체
@Slf4j
@Service
@RequiredArgsConstructor
class StrategyCreationService {

    private final StrategyPort strategyPort;
    private final StrategyVersionPort strategyVersionPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;
    private final VrStrategyDetailUseCase vrStrategyLifecycle;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final BrokerAdapterRegistry registry;
    private final UserSettingsPort userSettingsPort;
    private final StrategyCreationPolicyPort strategyCreationPolicyPort;
    private final StrategyCreationResolvers creationResolvers;

    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 잔고 검증 HTTP 호출 포함 — 트랜잭션 없이 실행 (각 DB 저장은 JPA auto-commit)
    StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, userId);
        ResolvedCreation resolved = resolveCreationSettings(cmd);

        int initialHoldings = validateBootstrapPosition(cmd);
        LocalDate scheduledStart = resolveScheduledStart(cmd);
        StrategyTicker resolvedTicker = resolved.ticker();

        validateUniqueTicker(accountId, resolvedTicker);
        validateBalanceIfRequired(account, accountId, userId, cmd.initialUsdDeposit());

        BigDecimal marketPrice = initialHoldings > 0 ? fetchMarketPrice(account, resolvedTicker) : null;
        BigDecimal initialStockValue = initialHoldings > 0
                ? marketPrice.multiply(BigDecimal.valueOf(initialHoldings)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        VrRampParams ramp = null;
        BigDecimal vrValue = null;
        if (cmd.type() == StrategyType.VR) {
            int normalizedRecurringAmount = resolved.recurringAmount() != null ? resolved.recurringAmount() : 0;
            vrValue = resolveVrValue(cmd, initialStockValue);
            ramp = normalizeVrRampParams(cmd, normalizedRecurringAmount);
            validateVrCommand(cmd, resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(),
                    vrValue, initialStockValue, ramp);
        }

        StrategyCycleSeedType seedType = cmd.type() == StrategyType.VR
                ? StrategyCycleSeedType.NONE
                : (cmd.cycleSeedType() != null ? cmd.cycleSeedType() : StrategyCycleSeedType.NONE);

        int divisionCount = resolved.divisionCount();

        var persisted = saveStrategyWithVersion(accountId, cmd.type(), resolvedTicker, seedType, divisionCount,
                resolved.intervalWeeks(), resolved.bandWidth(), resolved.recurringAmount(), ramp);

        InitialCycleResult initialResult = saveInitialCycleAndPosition(
                persisted.strategy(), persisted.version().id(), cmd.initialUsdDeposit(),
                initialHoldings, cmd.initialAvgPrice(), marketPrice, initialStockValue, vrValue,
                persisted.vrDetail(), scheduledStart);

        log.info("전략 등록: accountId={}, strategyId={}, type={}", accountId, persisted.strategy().id(), persisted.strategy().type());

        if (persisted.strategy().isVr()) {
            VrSummary vrSummary = vrStrategyLifecycle.buildSummary(
                    persisted.vrDetail(), initialResult.cycleVr(), initialResult.initialPosition().usdDeposit(),
                    initialResult.initialPosition().usdDeposit());
            return new StrategyDetail(persisted.strategy(), initialResult.initialPosition().usdDeposit(), initialResult.cycle().startDate(), null, false, null, initialHoldings, vrSummary);
        }
        return new StrategyDetail(persisted.strategy(), initialResult.cycle().startAmount(), initialResult.cycle().startDate(), divisionCount, false, 0.0, initialHoldings, null);
    }

    private int validateBootstrapPosition(RegisterStrategyCommand cmd) {
        return BootstrapPosition.validate(cmd.initialHoldings(), cmd.initialAvgPrice());
    }

    private BigDecimal resolveVrValue(RegisterStrategyCommand cmd, BigDecimal evaluatedStockValue) {
        BigDecimal explicit = cmd.initialVrValue();
        if (explicit != null && explicit.signum() < 0) {
            throw new IllegalArgumentException("VR 전략의 초기 V값(initialVrValue)은 0 이상이어야 합니다");
        }
        return explicit != null && explicit.signum() > 0 ? explicit : evaluatedStockValue;
    }

    private LocalDate resolveScheduledStart(RegisterStrategyCommand cmd) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        LocalDate scheduled = cmd.scheduledStartDate() != null ? cmd.scheduledStartDate() : today;
        if (scheduled.isBefore(today)) {
            throw new IllegalArgumentException("시작예정일(scheduledStartDate)은 오늘 이후여야 합니다");
        }
        return scheduled;
    }

    private BigDecimal fetchMarketPrice(Account account, StrategyTicker ticker) {
        return BrokerCallGuard.wrap("전일종가 조회",
                () -> registry.require(account.toBrokerRef(), BrokerPricePort.class).getPrevClose(ticker, account.toBrokerRef()));
    }

    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        StrategyCreationSettings settings = strategyCreationPolicyPort.find(cmd.type())
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 전략 생성 정책: " + cmd.type()));
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        com.kista.trading.domain.strategy.StrategyCreationRequest request =
                new com.kista.trading.domain.strategy.StrategyCreationRequest(
                        cmd.ticker(), cmd.divisionCount(), cmd.intervalWeeks(), cmd.bandWidth(), cmd.recurringAmount());
        return creationResolvers.of(cmd.type()).resolve(request, settings);
    }

    private void validateVrCommand(RegisterStrategyCommand cmd, Integer intervalWeeks,
                                   BigDecimal bandWidth, Integer recurringAmount,
                                   BigDecimal vrValue, BigDecimal evaluatedStockValue,
                                   VrRampParams ramp) {
        if (intervalWeeks == null || intervalWeeks <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
        }
        if (bandWidth == null || bandWidth.signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 밴드 폭(bandWidth)은 0보다 커야 합니다");
        }
        BigDecimal initialUsdDeposit = normalizeMoney(cmd.initialUsdDeposit());
        int normalizedRecurringAmount = recurringAmount != null ? recurringAmount : 0;
        BigDecimal initialAssets = vrValue.add(initialUsdDeposit);

        if (normalizedRecurringAmount <= 0 && initialAssets.signum() <= 0) {
            throw new IllegalArgumentException("VR 거치식/인출식은 초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
        }
        if (normalizedRecurringAmount < 0) {
            BigDecimal required = BigDecimal.valueOf(Math.abs((long) normalizedRecurringAmount))
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(4))
                    .divide(BigDecimal.valueOf(intervalWeeks), 2, RoundingMode.HALF_UP);
            BigDecimal evaluatedAssets = evaluatedStockValue.add(initialUsdDeposit);
            if (evaluatedAssets.compareTo(required) < 0) {
                throw new IllegalArgumentException("인출식 VR 전략의 초기 자산은 " + required + " 이상이어야 합니다");
            }
        }

        if (ramp.initialGradient() <= 0) {
            throw new IllegalArgumentException("VR 전략의 초기 gradient(initialGradient)는 0보다 커야 합니다");
        }
        if (ramp.gStepWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 스텝 주기(gStepWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.gGraceWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 유예 주수(gGraceWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.gStepWeeks() > 0 && ramp.gMax() < ramp.initialGradient()) {
            throw new IllegalArgumentException("VR 전략의 gradient 상한(gMax)은 initialGradient 이상이어야 합니다");
        }
        if (ramp.pStepWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 스텝 주기(pStepWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.pGraceWeeks() < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 유예 주수(pGraceWeeks)는 0 이상이어야 합니다");
        }
        if (ramp.initialPoolLimitRate().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("VR 전략의 초기 poolLimitRate(initialPoolLimitRate)는 1 이하여야 합니다");
        }
        if (ramp.poolLimitFloor().signum() < 0 || ramp.poolLimitFloor().compareTo(ramp.initialPoolLimitRate()) > 0) {
            throw new IllegalArgumentException(
                    "VR 전략의 poolLimitRate 하한(poolLimitFloor)은 0 이상 initialPoolLimitRate 이하여야 합니다");
        }
        if (ramp.pStepWeeks() > 0 && ramp.poolLimitFloor().signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 램프는 poolLimitFloor가 0보다 커야 합니다");
        }
    }

    private VrRampParams normalizeVrRampParams(RegisterStrategyCommand cmd, int normalizedRecurringAmount) {
        int defaultInitialGradient = normalizedRecurringAmount < 0 ? 40 : 10;
        int defaultGMax = normalizedRecurringAmount < 0 ? 50 : 20;
        BigDecimal defaultInitialPoolLimitRate = normalizedRecurringAmount > 0 ? BigDecimal.ONE
                : normalizedRecurringAmount == 0 ? new BigDecimal("0.75") : new BigDecimal("0.1");
        BigDecimal defaultPoolLimitFloor = normalizedRecurringAmount < 0 ? new BigDecimal("0.1") : new BigDecimal("0.5");

        int initialGradient = cmd.initialGradient() != null ? cmd.initialGradient() : defaultInitialGradient;
        int gGraceWeeks = cmd.gGraceWeeks() != null ? cmd.gGraceWeeks() : 52;
        int gStepWeeks = cmd.gStepWeeks() != null ? cmd.gStepWeeks() : 26;
        int gMax = cmd.gMax() != null ? cmd.gMax() : defaultGMax;
        BigDecimal initialPoolLimitRate = cmd.initialPoolLimitRate() != null
                ? cmd.initialPoolLimitRate() : defaultInitialPoolLimitRate;
        int pGraceWeeks = cmd.pGraceWeeks() != null ? cmd.pGraceWeeks() : 52;
        int pStepWeeks = cmd.pStepWeeks() != null ? cmd.pStepWeeks() : 26;
        BigDecimal poolLimitFloor = cmd.poolLimitFloor() != null ? cmd.poolLimitFloor() : defaultPoolLimitFloor;
        return new VrRampParams(initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor);
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private void validateUniqueTicker(UUID accountId, StrategyTicker ticker) {
        if (strategyPort.existsByAccountIdAndTicker(accountId, ticker)) {
            throw new IllegalStateException("이미 해당 종목으로 등록된 전략이 있습니다: " + ticker);
        }
    }

    private void validateBalanceIfRequired(Account account, UUID accountId, UUID userId, BigDecimal initialUsdDeposit) {
        userPort.findByIdOrThrow(userId);
        UserSettings settings = userSettingsPort.findOrDefault(userId);
        if (settings.balanceCheckEnabled() && initialUsdDeposit != null) {
            BigDecimal freeCash = calcFreeCash(account, accountId);
            if (initialUsdDeposit.compareTo(freeCash) > 0) {
                throw new IllegalArgumentException(
                        "다른 전략이 사용 중인 시드를 제외한 예수금(" + freeCash + ")을 초과했습니다");
            }
        }
    }

    private SavedStrategyAndVersion saveStrategyWithVersion(
            UUID accountId, StrategyType type, StrategyTicker ticker,
            StrategyCycleSeedType seedType, int divisionCount,
            Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount, VrRampParams ramp) {
        Strategy strategy = new Strategy(null, accountId, type, StrategyStatus.ACTIVE, ticker, seedType);
        Strategy saved = strategyPort.save(strategy);
        StrategyVersion version = strategyVersionPort.save(
                new StrategyVersion(null, saved.id(), strategyVersionPort.nextVersionNo(saved.id()), null, null)
        );
        StrategyVrDetail vrDetail = null;
        if (saved.isInfinite()) {
            strategyInfiniteDetailPort.save(new StrategyInfiniteDetail(version.id(), divisionCount));
        } else if (saved.isVr()) {
            vrDetail = vrStrategyLifecycle.saveVersionDetail(version.id(), intervalWeeks, bandWidth, recurringAmount,
                    ramp.initialGradient(), ramp.gGraceWeeks(), ramp.gStepWeeks(), ramp.gMax(),
                    ramp.initialPoolLimitRate(), ramp.pGraceWeeks(), ramp.pStepWeeks(), ramp.poolLimitFloor());
        }
        return new SavedStrategyAndVersion(saved, version, vrDetail);
    }

    private InitialCycleResult saveInitialCycleAndPosition(
            Strategy saved, UUID versionId, BigDecimal initialUsdDeposit,
            int initialHoldings, BigDecimal initialAvgPrice, BigDecimal marketPrice,
            BigDecimal initialStockValue, BigDecimal vrValue, StrategyVrDetail vrDetail, LocalDate scheduledStart) {
        BigDecimal normalizedInitialUsdDeposit = normalizeMoney(initialUsdDeposit);
        BigDecimal startAmount = normalizedInitialUsdDeposit.add(initialStockValue);
        StrategyCycle cycle = strategyCyclePort.save(StrategyCycle.start(saved.id(), versionId, startAmount, scheduledStart));

        CyclePosition initialPosition = initialHoldings > 0
                ? cyclePositionPort.save(CyclePosition.bootstrapSnapshot(
                        cycle.id(), normalizedInitialUsdDeposit, initialHoldings, initialAvgPrice, marketPrice))
                : cyclePositionPort.save(CyclePosition.initialSnapshot(cycle.id(), normalizedInitialUsdDeposit));

        if (saved.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(initialPosition.id(), false));
            return new InitialCycleResult(cycle, initialPosition, null);
        } else if (saved.isVr()) {
            StrategyCycleVrDetail savedCycleVr = vrStrategyLifecycle.saveInitialCycleDetail(
                    cycle.id(), vrValue, vrDetail);
            return new InitialCycleResult(cycle, initialPosition, savedCycleVr);
        } else {
            return new InitialCycleResult(cycle, initialPosition, null);
        }
    }

    private record SavedStrategyAndVersion(Strategy strategy, StrategyVersion version, StrategyVrDetail vrDetail) {}

    private record InitialCycleResult(StrategyCycle cycle, CyclePosition initialPosition,
                                      StrategyCycleVrDetail cycleVr) {}

    private record VrRampParams(int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                 BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks,
                                 BigDecimal poolLimitFloor) {}

    // 예수금 = 증권사 USD 매수가능금액 - 기존 전략들이 보유한 미투자 현금(usdDeposit) 합
    private BigDecimal calcFreeCash(Account account, UUID accountId) {
        BigDecimal kisUsdAmount = registry.require(account.toBrokerRef(), MarginPort.class).getUsdBuyableAmount(account.toBrokerRef());

        BigDecimal reserved = strategyPort.findByAccountId(accountId).stream()
                .map(s -> cyclePositionPort.findLatestOneByStrategyId(s.id())
                        .map(CyclePosition::usdDeposit)
                        .orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return kisUsdAmount.subtract(reserved);
    }
}
```

- [ ] **Step 2: StrategyService에서 이전한 코드 제거 + 위임**

`StrategyService.java`:
- 필드 목록 끝에 `private final StrategyCreationService creationService;` 추가
- `register(...)` 메서드 본문을 아래로 교체(어노테이션 그대로 유지):

```java
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategyDetail register(UUID userId, UUID accountId, RegisterStrategyCommand cmd) {
        return creationService.register(userId, accountId, cmd);
    }
```

- 삭제: `validateBootstrapPosition`, `resolveVrValue`, `resolveScheduledStart`, `fetchMarketPrice`, `resolveCreationSettings`, `validateVrCommand`, `normalizeVrRampParams`, `normalizeMoney`, `validateUniqueTicker`, `validateBalanceIfRequired`, `saveStrategyWithVersion`, `saveInitialCycleAndPosition`, `calcFreeCash`, record `SavedStrategyAndVersion`/`InitialCycleResult`/`VrRampParams`
- `updateSeed`(453-468)는 `normalizeMoney`를 쓰지 않으므로(직접 `newSeed.signum()`만 검사) 그대로 잔존 — 삭제 대상 아님, 확인만
- 더 이상 안 쓰는 필드 삭제 대상 확인: `strategyVersionPort`/`strategyInfiniteDetailPort`는 `saveStrategyWithVersion`에서만 쓰였으므로 StrategyService에서 삭제. `vrStrategyLifecycle`은 `toDetail`/`toDetails`(492-494, 521-522, 551-559)에서도 쓰이므로 **유지**. `registry`는 `calcFreeCash`(삭제)에서만 쓰였는지 확인 — grep 결과 `StrategyService.java` 내 다른 사용처 없으면 삭제, 있으면 유지. `strategyCreationPolicyPort`/`creationResolvers`는 `resolveCreationSettings`에서만 쓰였으므로 삭제. `userSettingsPort`는 `validateBalanceIfRequired`에서만 쓰였으므로 삭제(단, `UserSettings` import도 함께 정리)

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL — 미사용 필드/import 전부 정리됐는지 컴파일러 경고까지 확인

- [ ] **Step 4: StrategyServiceTest 생성자 재배선**

`StrategyServiceTest.java`:
- `StrategyCreationService creationService = new StrategyCreationService(strategyPort, strategyVersionPort, strategyInfiniteDetailPort, vrStrategyLifecycle, strategyCyclePort, cyclePositionPort, cyclePositionInfiniteDetailPort, accountPort, userPort, registry, userSettingsPort, strategyCreationPolicyPort, creationResolvers);` 형태로 실제 인스턴스 생성 후 `new StrategyService(...)` 호출에 Task 5에서 남긴 필드만(축소된 목록) + `creationService` 전달
- register() 관련 테스트(`@DisplayName("전략 등록: ...")` 등)는 여전히 `strategyService.register(...)`를 호출 — StrategyService가 내부 위임하므로 mock 스텁 대상(`strategyPort`, `accountPort` 등)은 그대로 유지, assertion 변경 불필요

- [ ] **Step 5: 테스트 실행**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.StrategyServiceTest'`
Expected: PASS, register 관련 테스트 assertion 무변경

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/StrategyCreationService.java \
        src/main/java/com/kista/trading/application/service/StrategyService.java \
        src/test/java/com/kista/trading/application/service/StrategyServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(strategy): 전략 등록 로직을 StrategyCreationService로 추출

register() 전체와 VR 파라미터 검증·잔고 검증·초기 저장 헬퍼를 분리.
StrategyUseCase의 유일한 구현체는 StrategyService로 유지(내부 위임).
architecture.md의 command/query 미분리 결정은 register가
toDetail/assemble을 쓰지 않아 이 분리와 무관함(스펙 문서 참고).
동작 변경 없음.
EOF
)"
```

---

### Task 6: StrategyHistoryQueryService 추출

**Files:**
- Create: `src/main/java/com/kista/trading/application/service/StrategyHistoryQueryService.java`
- Modify: `src/main/java/com/kista/trading/application/service/StrategyService.java`
- Test: `src/test/java/com/kista/trading/application/service/StrategyServiceTest.java`

**Interfaces:**
- Produces: `StrategySeedPreview strategySeedPreview(UUID accountId, UUID requesterId, StrategyType type, StrategyTicker ticker, int divisionCount)`, `CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size)`, `List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to)`
- Consumes: 없음(신규)

- [ ] **Step 1: 신규 파일 생성 — 조회 3종 이전**

`StrategyService.java`의 `strategySeedPreview`(589-621), `getByStrategy`(624-639), `getOrdersByStrategy`(642-648), `resolveHistoryFrom`(651-653), `resolveHistoryTo`(655-658)를 그대로 옮긴다:

```java
package com.kista.trading.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.domain.model.PrivacyCurrentBase;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.CycleHistoryPage;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategySeedPreview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 전략(사이클) 기준 조회 전용 — 시드 미리보기, 거래 이력, 주문 내역 (stats에서 이관됐던 전략 소유 read)
@Service
@RequiredArgsConstructor
class StrategyHistoryQueryService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final CycleOrderStrategies cycleStrategies;
    private final PrivacyTradePort privacyTradePort;
    private final BrokerAdapterRegistry registry;

    // 전략 등록/수정 폼용 최소시드·기준가 미리보기 — register()의 minRequiredDeposit 계산과 동일 경로
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    StrategySeedPreview strategySeedPreview(
            UUID accountId, UUID requesterId,
            StrategyType type, StrategyTicker ticker, int divisionCount) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);

        CycleOrderStrategy strategy = cycleStrategies.of(type);

        PrivacyCurrentBase currentBase = strategy.requiresPrivacyBase()
                ? privacyTradePort.findSeedPreviewBase().orElse(null)
                : null;
        if (strategy.requiresPrivacyBase() && currentBase == null) {
            return new StrategySeedPreview(ticker.name(), null, null, "NO_PRIVACY_BASE");
        }
        PrivacyTradeBase privacyBase = currentBase != null
                ? new PrivacyTradeBase(null, null, 0, currentBase.currentCycleStart(), List.of())
                : null;

        BigDecimal price = strategy.requiresPrivacyBase()
                ? null
                : registry.require(account.toBrokerRef(), BrokerPricePort.class).getPrevClose(ticker, account.toBrokerRef());
        BigDecimal basePrice = strategy.requiresPrivacyBase()
                ? privacyBase.currentCycleStart()
                : price;
        BigDecimal minSeed = strategy.minRequiredDeposit(price, privacyBase, divisionCount);

        return new StrategySeedPreview(ticker.name(), basePrice, minSeed, null);
    }

    // 전략(사이클) 기준 거래 이력 조회 — 커서 기반 페이지네이션
    @Transactional(readOnly = true)
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                   LocalDate from, LocalDate to,
                                   Instant cursor, int size) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        Instant fromInstant = resolveHistoryFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveHistoryTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByStrategyIdWithCursor(strategyId, fromInstant, effectiveCursor, size + 1);
        boolean hasMore = raw.size() > size;
        List<CyclePositionHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
        Instant nextCursor = hasMore ? items.get(items.size() - 1).createdAt() : null;
        return new CycleHistoryPage(items, nextCursor, hasMore);
    }

    // 전략(사이클) 기준 기간 내 주문 내역 조회 — 사용자 전략 상세 화면용
    @Transactional(readOnly = true)
    List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return orderPort.findByStrategyId(strategyId, from, to);
    }

    // 이력 조회 커서 경계 — KST 자정 (stats getByAccount와 동일 규칙, 모듈 경계라 헬퍼 중복 유지)
    private Instant resolveHistoryFrom(LocalDate from) {
        return from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH;
    }

    private Instant resolveHistoryTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(TimeZones.KST);
        return resolved.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
    }
}
```

(`CyclePositionPort`는 `com.kista.trading.application.port.output` 패키지 — StrategyService.java 기존 import에서 확인)

- [ ] **Step 2: StrategyService에서 이전한 코드 제거 + 위임**

`StrategyService.java`:
- 필드에 `private final StrategyHistoryQueryService historyQueryService;` 추가
- 3개 메서드 본문 교체(어노테이션 그대로 유지):

```java
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public StrategySeedPreview strategySeedPreview(
            UUID accountId, UUID requesterId,
            StrategyType type, StrategyTicker ticker, int divisionCount) {
        return historyQueryService.strategySeedPreview(accountId, requesterId, type, ticker, divisionCount);
    }

    @Override
    @Transactional(readOnly = true)
    public CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        return historyQueryService.getByStrategy(strategyId, requesterId, from, to, cursor, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to) {
        return historyQueryService.getOrdersByStrategy(strategyId, requesterId, from, to);
    }
```

- 삭제: `resolveHistoryFrom`, `resolveHistoryTo` 원본
- 필드 정리: `cycleStrategies`(`CycleOrderStrategies`)는 `strategySeedPreview`에서만 쓰였으므로 StrategyService에서 삭제, `privacyTradePort`는 동일하게 삭제. `orderPort`는 `getOrdersByStrategy`에서만 쓰였는지 확인(다른 곳에서 안 쓰면 삭제)

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: StrategyServiceTest 생성자 재배선**

`StrategyServiceTest.java`:
- `StrategyHistoryQueryService historyQueryService = new StrategyHistoryQueryService(accountPort, strategyPort, cyclePositionPort, orderPort, cycleStrategies, privacyTradePort, registry);` 실제 인스턴스 생성
- `new StrategyService(...)` 호출에 축소된 필드 목록 + `creationService` + `historyQueryService` 전달

- [ ] **Step 5: 테스트 실행**

Run: `bash gradlew test --tests 'com.kista.trading.application.service.StrategyServiceTest'`
Expected: PASS, assertion 무변경

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/trading/application/service/StrategyHistoryQueryService.java \
        src/main/java/com/kista/trading/application/service/StrategyService.java \
        src/test/java/com/kista/trading/application/service/StrategyServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(strategy): 전략 조회 3종을 StrategyHistoryQueryService로 추출

strategySeedPreview/getByStrategy/getOrdersByStrategy는 toDetail/assemble을
쓰지 않는 독립 블록이라 command/query 미분리 결정과 무관하게 분리 가능
(스펙 문서 참고). StrategyService는 delete/pause/resume/list*/getById/
update/toDetail/toDetails/assemble만 남는다. 동작 변경 없음.
EOF
)"
```

---

### Task 7: 전체 검증 + 문서 동기화

**Files:**
- Modify: `docs/agents/architecture.md` (StrategyService 줄수·필드수 서술 갱신)
- Test: 전체 trading 모듈 스위트

**Interfaces:** 없음(마무리 작업)

- [ ] **Step 1: 전체 trading 모듈 테스트 1회 실행**

Run: `bash gradlew test --tests 'com.kista.trading.*'`
Expected: BUILD SUCCESSFUL, 전 테스트 PASS

- [ ] **Step 2: ArchUnit 규칙 실행 (모듈 경계 회귀 확인)**

Run: `bash gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS — 신규 클래스가 전부 package-private `application.service` 내부라 `ModulithArchitectureTest`/`HexagonalArchitectureTest` 영향 없어야 함

- [ ] **Step 3: architecture.md의 StrategyService 서술 갱신**

`docs/agents/architecture.md`의 `com.kista.trading/` 절 중 `application/service/` 설명에서
"`StrategyService`(660줄·16필드)는 `toDetail`/`assemble`을 command·query가 공유해 서비스 분리 시
헬퍼 중복만 생긴다" 부분을, 실제 분리(register→`StrategyCreationService`, 조회 3종→
`StrategyHistoryQueryService`) 완료 후의 줄수·필드수로 갱신하고 "이 문서의 '분리 대상 아님' 판정은
`toDetail`/`assemble`을 공유하는 update/getById/list* 범위에 한정됨 — register·조회 3종은
2026-09-10 별도 클래스로 분리 완료(스펙: `docs/superpowers/specs/2026-09-10-trading-strategy-service-split-design.md`)"
문구를 추가한다. `TradingService` 설명(`application/service/` 절)에도 신규 클래스 3개
(`TradingBatchGuard`/`TradingCandidatePlanner` + `TradingOrderExecutor`/`TradingPriceFetcher` 내부 확장)를 한 줄씩 반영한다.

- [ ] **Step 4: 커밋**

```bash
git add docs/agents/architecture.md
git commit -m "$(cat <<'EOF'
docs(architecture): TradingService/StrategyService 분리 반영

TradingBatchGuard/TradingCandidatePlanner/StrategyCreationService/
StrategyHistoryQueryService 신설 및 각 서비스 잔존 범위를 문서에 반영.
EOF
)"
```
