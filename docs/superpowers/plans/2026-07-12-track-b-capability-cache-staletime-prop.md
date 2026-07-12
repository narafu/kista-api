# 트랙 B: CycleOrderStrategy Capability 확장 + prevClose 캐싱 + staleTime + CycleHistoryTable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. api 트랙(Task 1→2→3)과 ui 트랙(Task 4→5)은 레포 간 병렬, 레포 내 순차 실행.

**Goal:** (1) `CyclePositionPersistor`/`TradingOrderExecutor`의 전략 타입 직접 분기를 `CycleOrderStrategy` capability 메서드로 대체, (2) KIS/Toss의 전일종가 조회를 하루 단위로 캐싱, (3) React Query staleTime 3건 정정, (4) `CycleHistoryTable`의 12개 prop을 4개로 축소.

**Architecture:** api 쪽은 `CycleOrderStrategy` 인터페이스에 순수 값 반환 capability 메서드 3개를 추가(도메인→애플리케이션 의존 없음)하고, `adapter/out/broker/`에 `DoubleCheckedTokenCache`와 동일한 패턴의 신규 `PrevCloseCache`를 추가해 KIS/Toss 어댑터가 각자 필드로 소유한다. ui 쪽은 3개 hook의 staleTime 값만 조정하고, `CycleHistoryTable`이 `useRangeFilterState()`+쿼리 호출을 직접 소유하도록 전환해 부모(`StrategyTradesTab`/`TradesTab`)를 얇은 래퍼로 축소한다.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5 + Mockito + AssertJ (api) / Next.js 16, React Query, TypeScript (ui)

## Global Constraints

- 동작 완전 불변 — 승인된 예외 없음, 전부 순수 리팩토링/설정값 조정.
- 매매 공식·주문 생성 로직(`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`/`CycleOrderComputer` 내부 계산) 절대 변경 금지.
- domain→application 의존 생성 금지 — capability 메서드는 순수 값(boolean/enum) 반환만, 부수효과 호출은 여전히 application 레이어(`CyclePositionPersistor`/`TradingOrderExecutor`)에 남는다.
- Java 파일 BOM(`\xef\xbb\xbf`) 삽입 금지.
- 커밋: 한글 메시지 + Conventional Commit 접두사, author `narafu <narafu@kakao.com>`, **push 금지**.
- kista-api 게이트: `./gradlew compileJava` + `./gradlew test`. kista-ui 게이트: `npm run typecheck` + `npm run test`.

---

## Task 1 [api, sonnet]: CycleOrderStrategy capability 메서드 3개 추가

**Files:**
- Modify: `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/InfiniteCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java`
- Modify (테스트): `src/test/java/com/kista/domain/strategy/CycleOrderStrategyCapabilityTest.java`

**Interfaces:**
- Produces: `CycleOrderStrategy.tracksReverseMode()`(boolean, 기본 false), `CycleOrderStrategy.requiresRolloverCheck()`(boolean, 기본 false), `CycleOrderStrategy.priceCapMode()`(nested enum `PriceCapMode { NONE, INFINITE_POSITION, PRIVACY_SIMPLE }`, 기본 NONE) — Task 2가 이 3개를 소비한다.

- [ ] **Step 1: `CycleOrderStrategy.java`에 nested enum + 3개 default 메서드 추가**

`endsCycleOnLiquidation()` 선언(41번째 줄) 바로 다음에 추가:

```java
    // holdings=0(전량 청산) 시 사이클 종료 여부 — VR만 false(사이클 유지), 나머지 true(종료)
    default boolean endsCycleOnLiquidation() { return true; }

    // 리버스모드(소진 후 모드) 상태를 cycle_position_infinite_detail에 저장할지 여부 (INFINITE만 true)
    default boolean tracksReverseMode() { return false; }

    // 포지션 저장 후 N주 롤오버 판정을 수행할지 여부 (VR만 true)
    default boolean requiresRolloverCheck() { return false; }

    // BUY 가격 사후 보정(post-hoc cap) 방식 — NONE: 미적용, INFINITE_POSITION: InfinitePosition 기반, PRIVACY_SIMPLE: 단순 가격 치환
    // VR은 buildOrders 단계에서 이미 캡을 적용하므로 NONE(기본값)
    enum PriceCapMode { NONE, INFINITE_POSITION, PRIVACY_SIMPLE }
    default PriceCapMode priceCapMode() { return PriceCapMode.NONE; }
```

전체 인터페이스 파일에서 이 메서드들의 위치가 `endsCycleOnLiquidation()` 바로 다음, `plan(PlanContext)` 및 nested record들 이전이어야 한다.

- [ ] **Step 2: `InfiniteCycleOrderStrategy.java`에 override 2개 추가**

`supportsReverseMode()` 메서드(39-40번째 줄) 바로 다음에 추가:

```java
    @Override
    public boolean supportsReverseMode() { return true; }

    @Override
    public boolean tracksReverseMode() { return true; }
```

그리고 `availableDivisionCounts()` 메서드(42-43번째 줄) 다음, `plan(PlanContext ctx)` 메서드 이전에 추가:

```java
    @Override
    public List<Integer> availableDivisionCounts() { return List.of(20); }

    @Override
    public PriceCapMode priceCapMode() { return PriceCapMode.INFINITE_POSITION; }
```

(참고: `PriceCapMode`는 `CycleOrderStrategy` 인터페이스의 nested enum이므로 `CycleOrderStrategy.PriceCapMode`로 완전히 명시하거나, 이 클래스가 `implements CycleOrderStrategy`이므로 `PriceCapMode`로 바로 참조 가능하다 — 둘 다 컴파일된다, 기존 클래스 스타일대로 짧은 이름 사용.)

- [ ] **Step 3: `PrivacyCycleOrderStrategy.java`에 override 1개 추가**

`cycleType()` 메서드(25-26번째 줄) 바로 다음에 추가:

```java
    @Override
    public Strategy.Type cycleType() { return Strategy.Type.PRIVACY; }

    @Override
    public PriceCapMode priceCapMode() { return PriceCapMode.PRIVACY_SIMPLE; }
```

- [ ] **Step 4: `VrCycleOrderStrategy.java`에 override 1개 추가**

`endsCycleOnLiquidation()` 메서드(40-41번째 줄) 바로 다음에 추가:

```java
    // VR은 전량 청산 후에도 사이클을 유지하며 다시 매수 사다리를 생성
    @Override
    public boolean endsCycleOnLiquidation() { return false; }

    @Override
    public boolean requiresRolloverCheck() { return true; }
```

- [ ] **Step 5: `compileJava` 확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 6: `CycleOrderStrategyCapabilityTest.java`에 신규 capability 검증 추가**

3개 테스트 메서드 각각에 아래 라인을 추가한다(기존 라인은 그대로 두고 추가만):

`infinite_capabilities()` 메서드 마지막 줄(`assertThat(infinite.endsCycleOnLiquidation()).isTrue();`) 다음에:
```java
        assertThat(infinite.tracksReverseMode()).isTrue();
        assertThat(infinite.requiresRolloverCheck()).isFalse(); // 기본값
        assertThat(infinite.priceCapMode()).isEqualTo(com.kista.domain.strategy.CycleOrderStrategy.PriceCapMode.INFINITE_POSITION);
```

`privacy_capabilities()` 메서드 마지막 줄(`assertThat(privacy.endsCycleOnLiquidation()).isTrue();`) 다음에:
```java
        assertThat(privacy.tracksReverseMode()).isFalse(); // 기본값
        assertThat(privacy.requiresRolloverCheck()).isFalse(); // 기본값
        assertThat(privacy.priceCapMode()).isEqualTo(com.kista.domain.strategy.CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE);
```

`vr_capabilities()` 메서드 마지막 줄(`assertThat(vr.endsCycleOnLiquidation()).isFalse();`) 다음에:
```java
        assertThat(vr.tracksReverseMode()).isFalse(); // 기본값
        assertThat(vr.requiresRolloverCheck()).isTrue();
        assertThat(vr.priceCapMode()).isEqualTo(com.kista.domain.strategy.CycleOrderStrategy.PriceCapMode.NONE); // 기본값
```

- [ ] **Step 7: 테스트 실행**

Run: `./gradlew test --tests 'com.kista.domain.strategy.*'`
Expected: 전부 통과

- [ ] **Step 8: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(strategy): CycleOrderStrategy capability 메서드 3개 추가 — 타입 분기 대체 기반 마련

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 [api, sonnet]: CyclePositionPersistor/TradingOrderExecutor의 타입 분기를 capability 조회로 교체

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/CyclePositionPersistor.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java`
- Modify (테스트): `src/test/java/com/kista/application/service/trading/TradingOrderExecutorTest.java`
- Modify (테스트): `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `tracksReverseMode()`/`requiresRolloverCheck()`/`priceCapMode()`.

- [ ] **Step 1: `CyclePositionPersistor.java`의 타입 분기 교체**

`saveCyclePosition()` 메서드(31-68번째 줄) 안에서 3곳을 교체한다. 현재:
```java
        boolean newReverseMode = false;
        if (strategy.isInfinite()) {
            newReverseMode = computeNewReverseMode(currentCycle, strategy, balance, price);
        }
```
를:
```java
        boolean newReverseMode = false;
        if (cycleOrderStrategies.of(strategy.type()).tracksReverseMode()) {
            newReverseMode = computeNewReverseMode(currentCycle, strategy, balance, price);
        }
```
로 교체.

현재:
```java
        if (strategy.isInfinite()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(savedPosition.id(), newReverseMode));
        }
```
를:
```java
        if (cycleOrderStrategies.of(strategy.type()).tracksReverseMode()) {
            cyclePositionInfiniteDetailPort.save(new CyclePositionInfiniteDetail(savedPosition.id(), newReverseMode));
        }
```
로 교체.

현재:
```java
        // VR 전략: 포지션 저장 직후 N주 롤오버 판정 (매일 1회 — due 미도래면 내부에서 no-op)
        if (strategy.isVr()) {
            vrCycleRolloverService.rollIfDue(ctx, balance, price, today);
        }
```
를:
```java
        // 포지션 저장 직후 N주 롤오버 판정 (매일 1회 — due 미도래면 내부에서 no-op)
        if (cycleOrderStrategies.of(strategy.type()).requiresRolloverCheck()) {
            vrCycleRolloverService.rollIfDue(ctx, balance, price, today);
        }
```
로 교체 (주석에서 "VR 전략:" 문구 제거 — 이제 타입 특정이 아니라 capability 기반이므로).

`cycleOrderStrategies` 필드는 이미 존재하므로(27번째 줄) 생성자 변경 불필요.

- [ ] **Step 2: `TradingOrderExecutor.java`에 `CycleOrderStrategies` 필드 추가 + 가격 캡 분기 교체**

파일 상단 import에 추가:
```java
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
```

필드 선언부(27-30번째 줄)를:
```java
    private final OrderPort orderPort;
    private final BrokerAdapterRegistry registry;
    private final BuyOrderPriceCapper buyOrderPriceCapper;
    private final NotifyPort notifyPort;
```
에서 아래로 교체(필드 1개 추가):
```java
    private final OrderPort orderPort;
    private final BrokerAdapterRegistry registry;
    private final BuyOrderPriceCapper buyOrderPriceCapper;
    private final NotifyPort notifyPort;
    private final CycleOrderStrategies cycleOrderStrategies;
```

`placeOrders()` 메서드(42-54번째 줄)의 캡 분기 부분을 현재:
```java
    // INFINITE: position 있을 때만 capIfNeeded / PRIVACY: position 없어도 currentPrice 있으면 capPrivacyIfNeeded
    // VR: 가격 캡은 buildOrders 단계에서 이미 적용 — post-hoc 캡 불필요 (strategy.isPrivacy() 가드로 제외)
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position, Strategy strategy) {
        if (currentPrice != null && position != null) {
            buyOrderPriceCapper.capIfNeeded(today, account, strategyCycleId, currentPrice, position);
        } else if (currentPrice != null && strategy.isPrivacy()) {
            // PRIVACY만: InfinitePosition 없이 단순 가격 캡 적용 (VR은 buildOrders에서 자체 처리)
            buyOrderPriceCapper.capPrivacyIfNeeded(today, account, strategyCycleId, currentPrice);
        }
        List<Order> planned = orderPort.findPlannedByCycleAndDate(strategyCycleId, today);
        List<Order> placed = placeEach(planned, account);
        log.info("[{}] 주문 {}건 접수 (성공/{} 시도)", account.nickname(), placed.size(), planned.size());
        return placed;
    }
```
를 아래로 교체 (**중요**: `INFINITE_POSITION` 모드여도 `position`이 null이면(사이클 재계산 skip 케이스) 캡을 호출하지 않는 원래 동작을 그대로 보존해야 한다 — `position != null` 조건을 반드시 유지):
```java
    // capIfNeeded/capPrivacyIfNeeded 적용 여부는 전략의 priceCapMode()로 결정
    // INFINITE_POSITION이어도 position이 null(재계산 skip 케이스)이면 캡 미적용 — 기존 동작 그대로
    // VR: 가격 캡은 buildOrders 단계에서 이미 적용 — priceCapMode()가 NONE이라 post-hoc 캡 불필요
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position, Strategy strategy) {
        if (currentPrice != null) {
            CycleOrderStrategy.PriceCapMode mode = cycleOrderStrategies.of(strategy.type()).priceCapMode();
            if (mode == CycleOrderStrategy.PriceCapMode.INFINITE_POSITION && position != null) {
                buyOrderPriceCapper.capIfNeeded(today, account, strategyCycleId, currentPrice, position);
            } else if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
                buyOrderPriceCapper.capPrivacyIfNeeded(today, account, strategyCycleId, currentPrice);
            }
        }
        List<Order> planned = orderPort.findPlannedByCycleAndDate(strategyCycleId, today);
        List<Order> placed = placeEach(planned, account);
        log.info("[{}] 주문 {}건 접수 (성공/{} 시도)", account.nickname(), placed.size(), planned.size());
        return placed;
    }
```

- [ ] **Step 3: `compileJava` 확인 (실패 예상 — 테스트의 생성자 호출부 미수정)**

Run: `./gradlew compileTestJava`
Expected: FAIL — `TradingOrderExecutorTest`/`TradingServiceTest`의 `new TradingOrderExecutor(...)` 호출이 인자 4개라 컴파일 오류.

- [ ] **Step 4: `TradingOrderExecutorTest.java` 수정**

파일 상단 import에 추가:
```java
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.domain.strategy.VrCycleOrderStrategy;
```

`VR_STRATEGY` 상수 선언(59-60번째 줄) 바로 다음에 추가:
```java
    // 실제 capability 구현체로 CycleOrderStrategies 조립 — priceCapMode() 실제 값 검증
    static final CycleOrderStrategies CYCLE_STRATEGIES = new CycleOrderStrategies(List.of(
            new InfiniteCycleOrderStrategy(null, null),
            new PrivacyCycleOrderStrategy(null),
            new VrCycleOrderStrategy(null)));
```

`executor()` 헬퍼 메서드(68-70번째 줄)를 현재:
```java
    private TradingOrderExecutor executor() {
        return new TradingOrderExecutor(orderPort, registry, buyOrderPriceCapper, notifyPort);
    }
```
에서 아래로 교체:
```java
    private TradingOrderExecutor executor() {
        return new TradingOrderExecutor(orderPort, registry, buyOrderPriceCapper, notifyPort, CYCLE_STRATEGIES);
    }
```

- [ ] **Step 5: `TradingServiceTest.java` 수정**

`setUp()` 메서드 안에서 `TradingOrderExecutor orderExecutor = new TradingOrderExecutor(orderPort, tradingRegistry, priceCapper, notifyPort);` 라인을 찾아 아래로 교체(이 파일은 이미 `CycleOrderStrategies cycleStrategies = new CycleOrderStrategies(List.of(new InfiniteCycleOrderStrategy(infiniteStrategy, reverseStrategy), new PrivacyCycleOrderStrategy(privacyStrategy), new VrCycleOrderStrategy(vrStrategy)))`를 앞서 조립해두었으므로 그 변수를 재사용):
```java
        TradingOrderExecutor orderExecutor = new TradingOrderExecutor(orderPort, tradingRegistry, priceCapper, notifyPort, cycleStrategies);
```

- [ ] **Step 6: `compileTestJava` 재확인**

Run: `./gradlew compileTestJava`
Expected: SUCCESS

- [ ] **Step 7: 관련 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.*'`
Expected: 전부 통과 — `CyclePositionPersistorTest`는 수정 없이도 통과해야 한다(실제 capability 구현체를 이미 조립해 쓰고 있어 새 capability 메서드가 자동으로 올바른 값을 반환하기 때문).

- [ ] **Step 8: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(trading): CyclePositionPersistor·TradingOrderExecutor 타입 분기를 capability 조회로 교체

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 [api, sonnet]: KIS/Toss prevClose 캐싱

**Files:**
- Create: `src/main/java/com/kista/adapter/out/broker/PrevCloseCache.java`
- Modify: `src/main/java/com/kista/adapter/out/kis/KisPriceApi.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossPriceApi.java`
- Modify (테스트): `src/test/java/com/kista/adapter/out/kis/KisPriceApiTest.java`
- Modify (테스트): `src/test/java/com/kista/adapter/out/toss/TossPriceApiTest.java`

이 Task는 Task 1/2와 파일이 겹치지 않으므로 독립적으로 진행 가능하나, 같은 저장소 규칙(레포 내 순차)에 따라 Task 2 완료 후 실행한다.

- [ ] **Step 1: `PrevCloseCache.java` 신규 생성**

```java
package com.kista.adapter.out.broker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

// 전일종가(prevClose) 캐시 — 종목+거래일(KST) 단위로 하루 내 재조회 방지. KIS dailyprice / Toss candle API 공용.
// DoubleCheckedTokenCache와 동일하게 각 어댑터가 필드로 자체 인스턴스 소유(Spring bean 아님).
// 만료 로직 없음 — 날짜가 바뀌면 키가 자연히 달라지고, 종목 수가 적어(4개) 메모리 증가는 무시 가능.
public final class PrevCloseCache {

    private final ConcurrentMap<CacheKey, Optional<BigDecimal>> cache = new ConcurrentHashMap<>();

    private record CacheKey(String symbol, LocalDate date) {}

    // fetcher: 캐시 miss 시 실제 조회를 수행하는 함수 (실패 시 Optional.empty() 반환 관례)
    public Optional<BigDecimal> getOrFetch(String symbol, LocalDate date, Supplier<Optional<BigDecimal>> fetcher) {
        return cache.computeIfAbsent(new CacheKey(symbol, date), k -> fetcher.get());
    }
}
```

- [ ] **Step 2: `KisPriceApi.java`에 캐시 적용**

파일 상단 import에 추가:
```java
import com.kista.adapter.out.broker.PrevCloseCache;
```

필드 선언부(33-34번째 줄) 다음에 추가:
```java
    private final KisHttpClient kisHttpClient;
    private final KisExchangeRegistry exchangeRegistry;
    private final PrevCloseCache prevCloseCache = new PrevCloseCache();
```

`fetchLatestClose()` 메서드(163-188번째 줄)를 현재:
```java
    // 가장 최근 확정 거래일의 일봉 종가 조회 (KIS price API의 base는 미국장 개장 전 하루 더 과거 종가일 수 있음)
    private Optional<BigDecimal> fetchLatestClose(Ticker ticker, String excd, Account account) {
        try {
            DailyPriceResponse response = kisHttpClient.pricingGet(
                    DAILY_TR_ID, DAILY_PATH, account, DailyPriceResponse.class,
                    p -> {
                        p.add("EXCD", excd);
                        p.add("SYMB", ticker.name());
                        p.add("GUBN", "0");
                        p.add("BYMD", LocalDate.now(TimeZones.KST).format(DateTimeFormatter.BASIC_ISO_DATE));
                        p.add("MODP", "0");
                    });
            if (response == null || response.output2() == null || response.output2().isEmpty()) {
                log.warn("일별시세(전일종가) 조회 응답 없음 — base 필드로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            String clos = response.output2().get(0).clos();
            if (clos == null || clos.isBlank()) {
                log.warn("일별시세(전일종가) 응답 종가 빈값 — base 필드로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            return Optional.of(KisResponseParser.parseBd(clos));
        } catch (Exception e) {
            log.warn("일별시세(전일종가) 조회 실패 — base 필드로 fallback: ticker={}", ticker, e);
            return Optional.empty();
        }
    }
```
아래로 교체(원본 로직을 `fetchLatestCloseUncached`로 이름만 바꿔 보존하고, 캐시 래퍼를 새 `fetchLatestClose`로 추가):
```java
    // 가장 최근 확정 거래일의 일봉 종가 조회 — 같은 (ticker, KST 날짜) 재조회는 캐시 히트
    private Optional<BigDecimal> fetchLatestClose(Ticker ticker, String excd, Account account) {
        return prevCloseCache.getOrFetch(ticker.name(), LocalDate.now(TimeZones.KST),
                () -> fetchLatestCloseUncached(ticker, excd, account));
    }

    // KIS price API의 base는 미국장 개장 전 하루 더 과거 종가일 수 있음 — dailyprice로 확정 종가 조회
    private Optional<BigDecimal> fetchLatestCloseUncached(Ticker ticker, String excd, Account account) {
        try {
            DailyPriceResponse response = kisHttpClient.pricingGet(
                    DAILY_TR_ID, DAILY_PATH, account, DailyPriceResponse.class,
                    p -> {
                        p.add("EXCD", excd);
                        p.add("SYMB", ticker.name());
                        p.add("GUBN", "0");
                        p.add("BYMD", LocalDate.now(TimeZones.KST).format(DateTimeFormatter.BASIC_ISO_DATE));
                        p.add("MODP", "0");
                    });
            if (response == null || response.output2() == null || response.output2().isEmpty()) {
                log.warn("일별시세(전일종가) 조회 응답 없음 — base 필드로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            String clos = response.output2().get(0).clos();
            if (clos == null || clos.isBlank()) {
                log.warn("일별시세(전일종가) 응답 종가 빈값 — base 필드로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            return Optional.of(KisResponseParser.parseBd(clos));
        } catch (Exception e) {
            log.warn("일별시세(전일종가) 조회 실패 — base 필드로 fallback: ticker={}", ticker, e);
            return Optional.empty();
        }
    }
```

- [ ] **Step 3: `TossPriceApi.java`에 캐시 적용**

파일 상단 import에 추가:
```java
import com.kista.adapter.out.broker.PrevCloseCache;
import com.kista.common.TimeZones;
import java.time.LocalDate;
```

필드 선언부(31-32번째 줄) 다음에 추가:
```java
    private final TossHttpClient tossHttpClient;
    private final TossCandleApi tossCandleApi; // 전일종가 캔들 조회
    private final PrevCloseCache prevCloseCache = new PrevCloseCache();
```

`fetchPrevClose()` 메서드(73-84번째 줄)를 현재:
```java
    // 일봉 최신 2개 조회 → 오름차순 [0]이 확정 전일종가, 실패 시 current fallback
    private BigDecimal fetchPrevClose(String symbol, BigDecimal fallback) {
        try {
            List<TossCandle> candles = tossCandleApi.getLatestCandles(symbol, "1d", 2);
            if (candles.size() >= 2) {
                return candles.get(0).close();
            }
            log.warn("Toss 캔들 부족({}개), prevClose=current 사용: symbol={}", candles.size(), symbol);
        } catch (Exception e) {
            log.warn("Toss 전일종가 조회 실패, prevClose=current 사용: symbol={}, error={}", symbol, e.getMessage());
        }
        return fallback;
    }
```
아래로 교체:
```java
    // 일봉 최신 2개 조회 → 오름차순 [0]이 확정 전일종가, 실패 시 current fallback
    // 같은 (symbol, KST 날짜) 재조회는 캐시 히트 — 실패(empty)도 캐싱되어 같은 날 재시도하지 않음(허용된 트레이드오프)
    private BigDecimal fetchPrevClose(String symbol, BigDecimal fallback) {
        return prevCloseCache.getOrFetch(symbol, LocalDate.now(TimeZones.KST),
                () -> fetchPrevCloseUncached(symbol)).orElse(fallback);
    }

    private Optional<BigDecimal> fetchPrevCloseUncached(String symbol) {
        try {
            List<TossCandle> candles = tossCandleApi.getLatestCandles(symbol, "1d", 2);
            if (candles.size() >= 2) {
                return Optional.of(candles.get(0).close());
            }
            log.warn("Toss 캔들 부족({}개), prevClose=current 사용: symbol={}", candles.size(), symbol);
        } catch (Exception e) {
            log.warn("Toss 전일종가 조회 실패, prevClose=current 사용: symbol={}, error={}", symbol, e.getMessage());
        }
        return Optional.empty();
    }
```

`TossPriceApi.java` 파일 상단에 이미 `import java.util.Optional;`이 없다면 추가 확인 — 현재 import 목록에 없으므로 추가 필요:
```java
import java.util.Optional;
```

- [ ] **Step 4: `compileJava` 확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 5: `KisPriceApiTest.java`에 캐시 히트 테스트 추가**

파일 상단 import에 추가:
```java
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
```
(기존 `import static org.mockito.Mockito.when;` 다음 줄에 추가하거나, 3개를 한 줄에 묶어도 무방 — 컴파일만 되면 됨)

`getPriceSnapshot_fallsBackToBaseWhenDailyPriceFails()` 테스트(54-70번째 줄) 다음에 추가:
```java
    @Test
    @DisplayName("같은 종목·같은 날짜 재조회 시 dailyprice API 1회만 호출 (캐시 히트)")
    void getPriceSnapshot_sameTickerSameDay_callsDailyPriceOnce() {
        var priceResponse = new KisPriceApi.PriceResponse(
                new KisPriceApi.PriceResponse.Output("73.72", "76.27"));
        when(kisHttpClient.pricingGet(eq("HHDFS00000300"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.PriceResponse.class), any())).thenReturn(priceResponse);

        var dailyResponse = new KisPriceApi.DailyPriceResponse(
                List.of(new KisPriceApi.DailyPriceResponse.Output2("73.72")));
        when(kisHttpClient.pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any())).thenReturn(dailyResponse);

        api.getPriceSnapshot(Ticker.TQQQ, ACCOUNT);
        api.getPriceSnapshot(Ticker.TQQQ, ACCOUNT);

        verify(kisHttpClient, times(1)).pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any());
        verify(kisHttpClient, times(2)).pricingGet(eq("HHDFS00000300"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.PriceResponse.class), any()); // 현재가는 캐싱 대상 아님 — 매번 호출
    }
```

- [ ] **Step 6: `TossPriceApiTest.java`에 캐시 히트 테스트 추가**

파일 상단 import에 추가:
```java
import com.kista.domain.model.toss.TossCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
```
(`java.math.BigDecimal`은 이미 12번째 줄에 있으므로 중복 추가하지 말 것 — 없는 것만 추가)

`getPriceSnapshot_prevCloseEqualsCurrent()` 테스트(63-74번째 줄) 다음에 추가:
```java
    @Test
    @DisplayName("같은 종목·같은 날짜 재조회 시 캔들 API 1회만 호출 (캐시 히트)")
    void getPriceSnapshot_sameSymbolSameDay_callsCandleApiOnce() {
        var item = new TossPriceApi.PriceItem("SOXL", "25.50", "USD");
        when(tossHttpClient.getCommon(eq("/api/v1/prices"), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(wrap(item));
        List<TossCandle> candles = List.of(
                new TossCandle(LocalDate.now(), new BigDecimal("24.00"), new BigDecimal("24.50"),
                        new BigDecimal("23.50"), new BigDecimal("24.20"), 1000L),
                new TossCandle(LocalDate.now().minusDays(1), new BigDecimal("23.00"), new BigDecimal("23.50"),
                        new BigDecimal("22.50"), new BigDecimal("23.20"), 900L));
        when(tossCandleApi.getLatestCandles("SOXL", "1d", 2)).thenReturn(candles);

        tossPriceApi.getPriceSnapshot(Ticker.SOXL);
        tossPriceApi.getPriceSnapshot(Ticker.SOXL);

        verify(tossCandleApi, times(1)).getLatestCandles("SOXL", "1d", 2);
    }
```

- [ ] **Step 7: 신규 테스트 실행**

Run: `./gradlew test --tests 'com.kista.adapter.out.kis.KisPriceApiTest,com.kista.adapter.out.toss.TossPriceApiTest'`
Expected: 전부 통과

- [ ] **Step 8: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
perf(broker): KIS·Toss 전일종가 조회 하루 단위 캐싱 — PrevCloseCache 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 [ui, haiku]: React Query staleTime 3건 수정

**Files:**
- Modify: `entities/market/hooks/useMarketQueries.ts`
- Modify: `entities/order/hooks/useOrderQueries.ts`

- [ ] **Step 1: Fear&Greed staleTime 정정**

`entities/market/hooks/useMarketQueries.ts`에서 `useFearGreedQuery` 함수의 현재:
```ts
export function useFearGreedQuery(days = CHART_CANDLE_COUNT) {
  return useQuery<FearGreed | null>({
    queryKey: ['fearGreed', days],
    queryFn: () => getFearGreedClient(days).catch((): FearGreed | null => null),
    staleTime: 1000 * 60 * 30, // 30분 — 하루 1회 갱신 데이터
  })
}
```
를 아래로 교체:
```ts
export function useFearGreedQuery(days = CHART_CANDLE_COUNT) {
  return useQuery<FearGreed | null>({
    queryKey: ['fearGreed', days],
    queryFn: () => getFearGreedClient(days).catch((): FearGreed | null => null),
    staleTime: 1000 * 60 * 60 * 6, // 6시간 — 서버 갱신 주기(KST 00:00/12:00, 12시간)의 절반
  })
}
```

- [ ] **Step 2: 시장 휴일 캘린더 staleTime 상향**

같은 파일 `useMonthlyHolidaysQuery` 함수의 현재:
```ts
    staleTime: initialData ? 1000 * 60 * 60 : 0,
```
를:
```ts
    staleTime: initialData ? 1000 * 60 * 60 * 24 : 0, // 24시간 — 서버는 월 1회만 갱신
```
로 교체.

- [ ] **Step 3: 체결 이력 staleTime 추가**

`entities/order/hooks/useOrderQueries.ts`에서 `useStrategyOrdersQuery` 함수의 현재:
```ts
export function useStrategyOrdersQuery(
  strategyId: string,
  from: string | undefined,
  to: string | undefined,
  options?: { enabled?: boolean },
) {
  return useQuery<StrategyOrder[]>({
    queryKey: ['strategy-orders', strategyId, from ?? '', to ?? ''],
    queryFn: () => listStrategyOrders(strategyId, from, to),
    enabled: options?.enabled !== false,
  })
}
```
를 아래로 교체:
```ts
export function useStrategyOrdersQuery(
  strategyId: string,
  from: string | undefined,
  to: string | undefined,
  options?: { enabled?: boolean },
) {
  return useQuery<StrategyOrder[]>({
    queryKey: ['strategy-orders', strategyId, from ?? '', to ?? ''],
    queryFn: () => listStrategyOrders(strategyId, from, to),
    enabled: options?.enabled !== false,
    staleTime: 1000 * 60, // 1분 — 과거 체결 이력, 매 마운트마다 재요청할 필요 없음
  })
}
```

- [ ] **Step 4: 검증**

Run: `npm run typecheck`
Expected: 에러 없음

Run: `npm run test`
Expected: 전부 통과 (설정값 변경이라 관련 단위 테스트 없음 — 기존 스위트 회귀만 확인)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
fix(entities): React Query staleTime 3건 정정 — Fear&Greed·시장휴일·체결이력

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5 [ui, sonnet]: CycleHistoryTable prop drilling 해소

**Files:**
- Modify: `entities/trade/hooks/useCycleHistory.ts`
- Modify: `widgets/cycle-history/CycleHistoryTable.tsx`
- Modify: `widgets/cycle-history/StrategyTradesTab.tsx`
- Modify: `widgets/account-detail/TradesTab.tsx`

**Interfaces:**
- Consumes: `useStrategyCycleHistoryQuery(strategyId: string | undefined, params: DateParams)`, `useAccountCycleHistoryQuery(accountId: string, params: DateParams)` — 둘 다 `{cycleHistory, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage}` 반환.
- Produces: `CycleHistoryTable`의 신규 Props `{ title, id, useHistoryQuery, emptyIdMessage? }`.

- [ ] **Step 1: `DateParams` 타입 export**

`entities/trade/hooks/useCycleHistory.ts`에서 현재:
```ts
type DateParams = { from?: string; to?: string; size?: number } | null
```
를:
```ts
export type DateParams = { from?: string; to?: string; size?: number } | null
```
로 교체(`export` 추가만).

- [ ] **Step 2: `CycleHistoryTable.tsx`에 상태·쿼리 소유권 이동**

파일 상단 import를 현재:
```tsx
'use client'

import { Fragment } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { fmtUsd, fmtDate } from '@shared/lib/format'
import type { CycleHistoryItem } from '@entities/trade'
import { EmptyState } from '@shared/ui/EmptyState'
import { Badge } from '@shared/ui/Badge'
import { TableHeadCell } from '@shared/ui/TableHeadCell'
import { RangeFilterControls } from '@shared/ui/range-filter/RangeFilterControls'
import type { RangePreset } from '@shared/lib/date-range'

interface Props {
  title: string
  cycleHistory: CycleHistoryItem[]
  isLoading: boolean
  rangeType: RangePreset
  setRangeType: (r: RangePreset) => void
  customFrom: string
  setCustomFrom: (v: string) => void
  customTo: string
  setCustomTo: (v: string) => void
  pageSize: string
  setPageSize: (s: string) => void
  hasNextPage?: boolean
  isFetchingNextPage?: boolean
  fetchNextPage?: () => void
}

export function CycleHistoryTable({
  title,
  cycleHistory,
  isLoading,
  rangeType,
  setRangeType,
  customFrom,
  setCustomFrom,
  customTo,
  setCustomTo,
  pageSize,
  setPageSize,
  hasNextPage,
  isFetchingNextPage,
  fetchNextPage,
}: Props) {
```
아래로 교체:
```tsx
'use client'

import { Fragment } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { fmtUsd, fmtDate } from '@shared/lib/format'
import type { CycleHistoryItem } from '@entities/trade'
import type { DateParams } from '@entities/trade/hooks/useCycleHistory'
import { EmptyState } from '@shared/ui/EmptyState'
import { Badge } from '@shared/ui/Badge'
import { TableHeadCell } from '@shared/ui/TableHeadCell'
import { RangeFilterControls } from '@shared/ui/range-filter/RangeFilterControls'
import { useRangeFilterState } from '@shared/lib/hooks/use-range-filter-state'
import { resolveRangeStrict } from '@shared/lib/date-range'

interface HistoryQueryResult {
  cycleHistory: CycleHistoryItem[]
  isLoading: boolean
  fetchNextPage: () => void
  hasNextPage?: boolean
  isFetchingNextPage?: boolean
}

interface Props {
  title: string
  id: string | undefined
  useHistoryQuery: (id: string | undefined, params: DateParams) => HistoryQueryResult
  emptyIdMessage?: string
}

export function CycleHistoryTable({ title, id, useHistoryQuery, emptyIdMessage }: Props) {
  const { rangeType, customFrom, customTo, pageSize, setRangeType, setCustomFrom, setCustomTo, setPageSize } =
    useRangeFilterState()
  const baseParams = resolveRangeStrict(rangeType, customFrom, customTo)
  const params = baseParams !== null ? { ...baseParams, size: Number(pageSize) } : null
  const { cycleHistory, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } = useHistoryQuery(id, params)

  if (!id && emptyIdMessage) {
    return (
      <Card className="overflow-hidden">
        <CardHeader className="pb-3">
          <CardTitle className="text-base">{title}</CardTitle>
        </CardHeader>
        <CardContent>
          <EmptyState variant="text" message={emptyIdMessage} />
        </CardContent>
      </Card>
    )
  }
```

`CycleHistoryTable.tsx`의 나머지 부분(`const groups = ...`부터 파일 끝까지)은 **변경하지 않는다** — `title`/`cycleHistory`/`isLoading`/`rangeType`/`setRangeType`/`customFrom`/`setCustomFrom`/`customTo`/`setCustomTo`/`pageSize`/`setPageSize`/`hasNextPage`/`isFetchingNextPage`/`fetchNextPage` 변수명이 이미 위 교체 코드에서 동일하게 지역 변수로 선언돼 있으므로 마크업·렌더링 로직은 그대로 참조된다.

- [ ] **Step 3: `StrategyTradesTab.tsx` 축소**

전체 파일을 아래로 교체:
```tsx
'use client'

import { useStrategyCycleHistoryQuery } from '@entities/trade'
import { CycleHistoryTable } from './CycleHistoryTable'

interface Props {
  strategyId: string | undefined
}

export function StrategyTradesTab({ strategyId }: Props) {
  return (
    <CycleHistoryTable
      title="잔고 이력"
      id={strategyId}
      useHistoryQuery={useStrategyCycleHistoryQuery}
      emptyIdMessage="전략이 없습니다."
    />
  )
}
```

(`useStrategyCycleHistoryQuery`의 첫 파라미터 타입이 이미 `string | undefined`이므로 `HistoryQueryResult` 타입과 그대로 호환 — 별도 래퍼 불필요.)

- [ ] **Step 4: `TradesTab.tsx` 축소**

전체 파일을 아래로 교체 (`useAccountCycleHistoryQuery`의 첫 파라미터가 `string`(non-optional)이라 `id: string | undefined`를 받는 `useHistoryQuery` 타입과 맞추기 위해 인라인 래퍼 함수 필요 — accountId는 이 위젯 진입 조건상 항상 정의됨):
```tsx
'use client'

import { useAccountCycleHistoryQuery } from '@entities/trade'
import { CycleHistoryTable } from '@widgets/cycle-history'
import type { DateParams } from '@entities/trade/hooks/useCycleHistory'

interface Props {
  accountId: string
}

export function TradesTab({ accountId }: Props) {
  return (
    <CycleHistoryTable
      title="잔고 이력"
      id={accountId}
      useHistoryQuery={(id: string | undefined, params: DateParams) => useAccountCycleHistoryQuery(id!, params)}
    />
  )
}
```

- [ ] **Step 5: 검증**

Run: `npm run typecheck`
Expected: 에러 없음

Run: `npm run test`
Expected: 전부 통과

Run: `grep -rn "CycleHistoryTable" --include="*.tsx" widgets/ app/ features/`
Expected: `StrategyTradesTab.tsx`, `TradesTab.tsx`(또는 `index.ts` re-export) 외 다른 곳에서 옛 prop 시그니처로 호출하는 곳이 없어야 함 — 있다면 동일하게 교체.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(widgets): CycleHistoryTable가 필터·쿼리 상태를 직접 소유하도록 전환 — prop 12개→4개

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## 최종 검증

1. kista-api: `./gradlew test` 전체 통과, `git diff --name-only <base>..HEAD`에 매매 공식 파일(`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`/`CycleOrderComputer`) 미등장 확인.
2. kista-ui: `npm run typecheck` + `npm run test` 전체 통과.
3. 두 레포 모두 working tree clean, 미푸시 확인.
