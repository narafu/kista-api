# 아파트 벤치마크 비교 주간 지수 교체 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/stats/housing-benchmark`(HOUSING)의 데이터 소스를 `housing_benchmark_prices`(5분위, 월간, quintile 파라미터)에서 `housing_price_indices`(KB Land 주간 매매가격지수, regionCode 파라미터)로 교체해 2~3주 운용만으로도 비교가 성립하게 한다.

**Architecture:** `BenchmarkGranularity`에 `WEEKLY`를 추가하고 기존 `granularity == DAILY` 분기 5곳을 `!= MONTHLY`로 일반화한다. `StatsService`는 KB 주간 조사일에 투자지수를 as-of 스냅해 `HousingBenchmarkComparisonBuilder`에 넘긴다. `HousingBenchmarkComparisonBuilder`는 as-of 스냅과 무관하게 순수 함수로 남고, `HousingPriceIndexPort`에 읽기 메서드 2개를 추가한다.

**Tech Stack:** Java 21, Spring Boot 3, JPA(Spring Data), JUnit 5 + Mockito + AssertJ, MockMvc.

## Global Constraints

- 신규 코드에 주석: 필드는 `// 역할 한 줄`, 비즈니스 로직 블록 직전에 단계 설명 한 줄. Javadoc·블록 주석 금지, `//` 인라인만.
- 4-space 들여쓰기. 불변 값은 record. 생성자 주입.
- 커밋 메시지는 한글, Conventional Commit 접두사(`feat(scope):`, `fix:`, `test:` 등) + 명령형 제목. author `narafu <narafu@kakao.com>` 확인.
- `git push`는 요청 시에만.
- 커밋 전 리뷰어 서브에이전트 검수 필수(문서 전용 변경 제외) — 이 계획을 실행하는 워커는 `superpowers:subagent-driven-development`의 리뷰 단계를 그대로 따른다.
- `/housing-benchmark/series`, `/housing-benchmark/regions`(엔드포인트 자체는 유지, 내부 구현은 Task 4에서 전환), `StatsService`의 `getSummary`/`getEquityCurve`/`getCyclePerformances`는 이번 범위 밖 — 건드리지 않는다.

---

### Task 1: `BenchmarkGranularity`에 `WEEKLY` 추가 + `DAILY` 분기를 `!= MONTHLY`로 일반화

**Files:**
- Modify: `src/main/java/com/kista/domain/model/stats/BenchmarkGranularity.java`
- Modify: `src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java`
- Modify: `src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java`
- Modify: `src/main/java/com/kista/application/service/stats/StatsService.java`
- Create: `src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java`
- Modify: `src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java`

**Interfaces:**
- Produces: `BenchmarkGranularity.WEEKLY` — 이후 모든 태스크가 사용.

- [ ] **Step 1: `BenchmarkGranularity`에 `WEEKLY` 추가**

```java
package com.kista.domain.model.stats;

public enum BenchmarkGranularity { MONTHLY, DAILY, WEEKLY }
```

- [ ] **Step 2: `MonthlyReturnCalculatorTest`에 WEEKLY 케이스 추가 (실패 예상 없음 — 기존 DAILY 테스트를 그대로 복제하는 것이므로 이 시점엔 이미 통과함. WEEKLY가 아직 DAILY와 동일 분기를 안 타므로 실제로는 컴파일 후 실패한다)**

`src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java`의 395번째 줄(`DAILY_granularity는_매_유효_평가일마다_포인트를_방출한다` 메서드 닫는 `}`) 바로 뒤에 추가:

```java

    @Test
    void WEEKLY_granularity는_DAILY와_동일하게_매_유효_평가일마다_포인트를_방출한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "100"),
                        position(cycle, FEBRUARY_1, "110"),
                        position(cycle, FEBRUARY_2, "121")),
                JANUARY_31, FEBRUARY_2, BenchmarkGranularity.WEEKLY);

        assertThat(result)
                .extracting(InvestmentPoint::baseDate, InvestmentPoint::investmentIndexUsd, InvestmentPoint::periodReturn)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                JANUARY_31, new BigDecimal("100.0000000000"), new BigDecimal("0.0000000000")),
                        org.assertj.core.groups.Tuple.tuple(
                                FEBRUARY_1, new BigDecimal("110.0000000000"), new BigDecimal("0.1000000000")),
                        org.assertj.core.groups.Tuple.tuple(
                                FEBRUARY_2, new BigDecimal("121.0000000000"), new BigDecimal("0.1000000000")));
    }
```

- [ ] **Step 3: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.MonthlyReturnCalculatorTest' --rerun-tasks`
Expected: `WEEKLY_granularity는_DAILY와_동일하게_매_유효_평가일마다_포인트를_방출한다` FAIL (WEEKLY가 아직 MONTHLY와 같은 취급을 받아 결과가 비어있거나 다름)

- [ ] **Step 4: `MonthlyReturnCalculator.compoundDailyReturns`의 `DAILY` 분기를 `!= MONTHLY`로 일반화**

`src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java`의 246번째 줄 근처:

```java
            if (validIndex) {
                if (granularity == BenchmarkGranularity.DAILY) {
```
를
```java
            if (validIndex) {
                if (granularity != BenchmarkGranularity.MONTHLY) {
```
로 변경.

259번째 줄 근처:
```java
        return granularity == BenchmarkGranularity.DAILY
                ? List.copyOf(dailyPoints) : List.copyOf(monthlyPoints.values());
```
를
```java
        return granularity != BenchmarkGranularity.MONTHLY
                ? List.copyOf(dailyPoints) : List.copyOf(monthlyPoints.values());
```
로 변경.

- [ ] **Step 5: 테스트 실행해 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.MonthlyReturnCalculatorTest' --rerun-tasks`
Expected: PASS (전체)

- [ ] **Step 6: 신규 `HousingBenchmarkComparisonBuilderTest` 작성 (WEEKLY computeReturn + 3주 구간 예외 없음 검증) — 실패 예상**

`src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java` 신규 생성:

```java
package com.kista.application.service.stats;

import com.kista.domain.model.stats.BenchmarkAssetType;
import com.kista.domain.model.stats.BenchmarkGranularity;
import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import com.kista.domain.model.stats.InvestmentPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class HousingBenchmarkComparisonBuilderTest {

    private final HousingBenchmarkComparisonBuilder builder = new HousingBenchmarkComparisonBuilder();

    private static final HousingBenchmarkComparison.Benchmark BENCHMARK = new HousingBenchmarkComparison.Benchmark(
            BenchmarkAssetType.HOUSING, "1100000000", "서울", null, "서울 아파트 매매가격지수", null);

    private static InvestmentPoint point(LocalDate date, String indexUsd) {
        return new InvestmentPoint(date, new BigDecimal(indexUsd), null);
    }

    private static Map<LocalDate, BigDecimal> prices(LocalDate d1, String v1, LocalDate d2, String v2) {
        Map<LocalDate, BigDecimal> map = new TreeMap<>();
        map.put(d1, new BigDecimal(v1));
        map.put(d2, new BigDecimal(v2));
        return map;
    }

    @Test
    void WEEKLY_구간은_KB_결측_주가_있어도_인접_공통_포인트마다_수익률을_계산한다() {
        // 1/5 -> 2/16 (결측 주 존재, 정확히 1개월 뒤가 아님)까지도 항상 수익률이 나와야 한다
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 2, 16);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "110"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "121");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);

        assertThat(result.points().getLast().investmentPeriodReturn()).isEqualByComparingTo("0.1");
        assertThat(result.points().getLast().benchmarkPeriodReturn()).isEqualByComparingTo("0.21");
    }

    @Test
    void WEEKLY_3주_구간에서는_예외_없이_결과를_반환한다() {
        // 과거 MONTHS.between==0으로 Infinity 예외가 나던 케이스의 회귀 방지
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 1, 26);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "101"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "102");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);

        assertThat(result.emptyReason()).isNull();
        assertThat(result.summary()).isNotNull();
    }
}
```

주의: `BENCHMARK` 상수는 `HousingBenchmarkComparison.Benchmark`의 **6-arg** 생성자(quintile 필드 제거 후 형태)를 미리 사용한다. 이 시점(Task 1)에서는 도메인 레코드가 아직 7-arg(quintile 포함)이므로 **컴파일 에러**가 난다 — Task 4에서 `Benchmark` 레코드가 6-arg로 바뀌면 자동으로 해소된다. 지금은 임시로 quintile 자리에 `null`을 넣은 7-arg로 작성해 우선 컴파일이 되게 하고, Task 4에서 6-arg로 되돌린다:

```java
    private static final HousingBenchmarkComparison.Benchmark BENCHMARK = new HousingBenchmarkComparison.Benchmark(
            BenchmarkAssetType.HOUSING, "1100000000", "서울", null, null, "서울 아파트 매매가격지수", null);
```

(위 7-arg 버전으로 이 Step에서는 작성한다. Task 4 Step에서 6-arg로 수정하는 것을 잊지 말 것 — Task 4의 파일 목록에 이 파일이 포함되어 있다.)

- [ ] **Step 7: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.HousingBenchmarkComparisonBuilderTest' --rerun-tasks`
Expected: `WEEKLY_구간은_KB_결측_주가_있어도_인접_공통_포인트마다_수익률을_계산한다`은 FAIL(수익률이 null), `WEEKLY_3주_구간에서는_예외_없이_결과를_반환한다`은 FAIL(`BigDecimal.valueOf(Infinity)` 예외)

- [ ] **Step 8: `HousingBenchmarkComparisonBuilder`의 `DAILY` 분기를 `!= MONTHLY`로 일반화**

`src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java`의 74-75번째 줄:

```java
            boolean computeReturn = previousDate != null
                    && (granularity == BenchmarkGranularity.DAILY || date.equals(previousDate.plusMonths(1)));
```
를
```java
            boolean computeReturn = previousDate != null
                    && (granularity != BenchmarkGranularity.MONTHLY || date.equals(previousDate.plusMonths(1)));
```
로 변경.

91-93번째 줄:
```java
        double periodsPerYear = granularity == BenchmarkGranularity.DAILY
                ? DAYS_PER_YEAR / ChronoUnit.DAYS.between(firstDate, points.getLast().baseDate())
                : 12.0 / ChronoUnit.MONTHS.between(firstDate, points.getLast().baseDate());
```
를
```java
        double periodsPerYear = granularity == BenchmarkGranularity.MONTHLY
                ? 12.0 / ChronoUnit.MONTHS.between(firstDate, points.getLast().baseDate())
                : DAYS_PER_YEAR / ChronoUnit.DAYS.between(firstDate, points.getLast().baseDate());
```
로 변경.

- [ ] **Step 9: 테스트 실행해 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.HousingBenchmarkComparisonBuilderTest' --rerun-tasks`
Expected: PASS (전체)

- [ ] **Step 10: `StatsService`의 `completedMonthEnd`/`effectiveFrom` `DAILY` 분기를 `!= MONTHLY`로 일반화**

`src/main/java/com/kista/application/service/stats/StatsService.java`의 `completedMonthEnd` 메서드(441번째 줄 근처):

```java
    private static LocalDate completedMonthEnd(LocalDate requestedTo, BenchmarkGranularity granularity) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        if (granularity == BenchmarkGranularity.DAILY) {
            return requestedTo != null ? requestedTo : today;
        }
```
를
```java
    private static LocalDate completedMonthEnd(LocalDate requestedTo, BenchmarkGranularity granularity) {
        LocalDate today = LocalDate.now(TimeZones.KST);
        if (granularity != BenchmarkGranularity.MONTHLY) {
            return requestedTo != null ? requestedTo : today;
        }
```
로 변경.

`buildInvestmentContext`의 `effectiveFrom` 삼항식(315-318번째 줄 근처):
```java
        LocalDate effectiveFrom = from != null
                ? (granularity == BenchmarkGranularity.DAILY ? from : from.withDayOfMonth(1))
                : cycles.stream().map(StrategyCycle::startDate).min(LocalDate::compareTo)
                        .orElse(effectiveTo).withDayOfMonth(1);
```
를
```java
        LocalDate effectiveFrom = from != null
                ? (granularity != BenchmarkGranularity.MONTHLY ? from : from.withDayOfMonth(1))
                : cycles.stream().map(StrategyCycle::startDate).min(LocalDate::compareTo)
                        .orElse(effectiveTo).withDayOfMonth(1);
```
로 변경.

- [ ] **Step 11: 전체 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL (Step 6에서 언급한 7-arg `Benchmark` 임시 우회 덕분에 컴파일 통과)

- [ ] **Step 12: 커밋**

```bash
git add src/main/java/com/kista/domain/model/stats/BenchmarkGranularity.java \
        src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java \
        src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java \
        src/main/java/com/kista/application/service/stats/StatsService.java \
        src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java \
        src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java
git commit -m "feat(stats): BenchmarkGranularity에 WEEKLY 추가, DAILY 분기를 !=MONTHLY로 일반화

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: 짧은 구간 연환산 수익률 억제 (WEEKLY·DAILY 공통, 90일 미만 null)

**Files:**
- Modify: `src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java`
- Modify: `src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java`

**Interfaces:**
- Consumes: Task 1의 `HousingBenchmarkComparisonBuilder.build(...)` 시그니처(변경 없음).

- [ ] **Step 1: 실패하는 테스트 3개 추가**

`HousingBenchmarkComparisonBuilderTest.java`의 마지막 `}` 직전에 추가:

```java

    @Test
    void 짧은_구간에서는_연환산_수익률을_null로_억제한다() {
        // 21일(< 90일) 구간 — WEEKLY(아파트)와 DAILY(ETF) 둘 다 동일 규칙 적용
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 1, 26);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "101"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "102");

        HousingBenchmarkComparison weekly = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);
        HousingBenchmarkComparison daily = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.DAILY);

        assertThat(weekly.summary().investmentAnnualizedReturn()).isNull();
        assertThat(weekly.summary().benchmarkAnnualizedReturn()).isNull();
        assertThat(daily.summary().investmentAnnualizedReturn()).isNull();
        assertThat(daily.summary().benchmarkAnnualizedReturn()).isNull();
    }

    @Test
    void 긴_구간에서는_연환산_수익률을_계산한다() {
        // 119일(>= 90일) 구간
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 5, 4);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "184.2"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "400");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);

        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 365.0 / 119.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
    }

    @Test
    void MONTHLY_구간에서는_짧은_구간이어도_연환산_수익률을_그대로_계산한다() {
        // MONTHLY는 이번 변경의 영향을 받지 않아야 한다 (회귀 방지)
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate d2 = LocalDate.of(2026, 2, 1);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "184.2"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "400");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.MONTHLY);

        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 12.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
    }
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.HousingBenchmarkComparisonBuilderTest' --rerun-tasks`
Expected: `짧은_구간에서는_연환산_수익률을_null로_억제한다` FAIL(현재는 null이 아닌 극단값), 나머지 2개는 이미 PASS(회귀 확인용)

- [ ] **Step 3: `PerformanceComparisonSummary` 생성부에 억제 로직 추가**

`HousingBenchmarkComparisonBuilder.java`의 `build()` 메서드에서 (Step 8 적용 후) 아래 블록:

```java
        double periodsPerYear = granularity == BenchmarkGranularity.MONTHLY
                ? 12.0 / ChronoUnit.MONTHS.between(firstDate, points.getLast().baseDate())
                : DAYS_PER_YEAR / ChronoUnit.DAYS.between(firstDate, points.getLast().baseDate());
        PerformanceComparisonSummary summary = new PerformanceComparisonSummary(
                investmentCumulativeReturn,
                benchmarkCumulativeReturn,
                investmentCumulativeReturn.subtract(benchmarkCumulativeReturn).setScale(SCALE, HALF_UP),
                annualizedReturn(lastInvestmentIndex, periodsPerYear),
                annualizedReturn(lastBenchmarkIndex, periodsPerYear),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::investmentIndexUsd).toList()),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::benchmarkIndex).toList()));
```

를

```java
        // MONTHLY 이외 granularity는 짧은 구간(90일 미만)에서 연환산 시 극단값이 나오므로 null로 억제한다
        boolean suppressAnnualized = granularity != BenchmarkGranularity.MONTHLY
                && ChronoUnit.DAYS.between(firstDate, points.getLast().baseDate()) < 90;
        double periodsPerYear = granularity == BenchmarkGranularity.MONTHLY
                ? 12.0 / ChronoUnit.MONTHS.between(firstDate, points.getLast().baseDate())
                : DAYS_PER_YEAR / ChronoUnit.DAYS.between(firstDate, points.getLast().baseDate());
        PerformanceComparisonSummary summary = new PerformanceComparisonSummary(
                investmentCumulativeReturn,
                benchmarkCumulativeReturn,
                investmentCumulativeReturn.subtract(benchmarkCumulativeReturn).setScale(SCALE, HALF_UP),
                suppressAnnualized ? null : annualizedReturn(lastInvestmentIndex, periodsPerYear),
                suppressAnnualized ? null : annualizedReturn(lastBenchmarkIndex, periodsPerYear),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::investmentIndexUsd).toList()),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::benchmarkIndex).toList()));
```

로 변경.

- [ ] **Step 4: 테스트 실행해 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.stats.HousingBenchmarkComparisonBuilderTest' --rerun-tasks`
Expected: PASS (전체 5개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java \
        src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java
git commit -m "feat(stats): 90일 미만 구간 연환산 수익률을 null로 억제(WEEKLY·DAILY 공통)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: `HousingPriceIndexPort` 읽기 메서드 2개 추가

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/HousingPriceIndexPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexJpaRepository.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexPersistenceAdapter.java`
- Modify: `src/test/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexPersistenceAdapterTest.java`

**Interfaces:**
- Produces: `HousingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(String metricCode, String regionCode, LocalDate from, LocalDate to)` — `List<HousingPriceIndex>` 반환, Task 4가 사용.
- Produces: `HousingPriceIndexPort.findDistinctRegions(String metricCode)` — `List<HousingBenchmarkRegion>` 반환, Task 4가 사용.

- [ ] **Step 1: `HousingPriceIndexPersistenceAdapterTest`에 실패하는 테스트 2개 추가**

`src/test/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexPersistenceAdapterTest.java` 상단 import에 추가:

```java
import com.kista.domain.model.stats.HousingBenchmarkRegion;
```

마지막 `}` 직전(기존 `index(...)` private 헬퍼 뒤)에 추가:

```java

    @Test
    void findByMetricCodeAndRegionCodeAndBaseDateBetween_returnsRowsOrderedByBaseDateAscending() {
        LocalDate week1 = LocalDate.of(2026, 1, 5);
        LocalDate week2 = LocalDate.of(2026, 1, 12);
        adapter.upsertAll(List.of(
                index("서울", "1100000000", week2, "101.500000000000"),
                index("서울", "1100000000", week1, "100.000000000000"),
                index("서울", "1100000000", LocalDate.of(2020, 1, 6), "50.000000000000")));

        List<HousingPriceIndex> result = adapter.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "1100000000", week1, week2);

        assertThat(result).extracting(HousingPriceIndex::baseDate).containsExactly(week1, week2);
    }

    @Test
    void findDistinctRegions_returnsRegionCodeAndNameOrderedByCode() {
        adapter.upsertAll(List.of(
                index("서울", "1100000000", LocalDate.of(2026, 1, 5), "100.000000000000"),
                index("부산", "2600000000", LocalDate.of(2026, 1, 5), "90.000000000000")));

        List<HousingBenchmarkRegion> result =
                adapter.findDistinctRegions(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX);

        assertThat(result).extracting(HousingBenchmarkRegion::regionCode)
                .containsExactly("1100000000", "2600000000");
    }
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
docker compose up -d postgres
./gradlew test --tests 'com.kista.adapter.out.persistence.housingbenchmark.HousingPriceIndexPersistenceAdapterTest' --rerun-tasks
```
Expected: 신규 2개 테스트 컴파일 에러(`findByMetricCodeAndRegionCodeAndBaseDateBetween`/`findDistinctRegions` 메서드 없음) — 포트에 메서드 추가 전이라 컴파일부터 실패한다.

- [ ] **Step 3: `HousingPriceIndexPort`에 메서드 2개 추가**

`src/main/java/com/kista/domain/port/out/HousingPriceIndexPort.java` 전체를 다음으로 교체:

```java
package com.kista.domain.port.out;

import com.kista.domain.model.stats.HousingBenchmarkRegion;
import com.kista.domain.model.stats.HousingPriceIndex;

import java.time.LocalDate;
import java.util.List;

public interface HousingPriceIndexPort {
    // 자연키(source+metric+region+baseDate) 기준 저장 또는 갱신
    void upsertAll(List<HousingPriceIndex> indices);

    // 통계 화면 연결을 위한 저장분 조회
    List<HousingPriceIndex> findByMetricCodeAndRegionCodeAndBaseDateBetween(
            String metricCode, String regionCode, LocalDate from, LocalDate to);

    // 실제 수집된 지역 카탈로그 (source+metric 기준 distinct region_code/region_name)
    List<HousingBenchmarkRegion> findDistinctRegions(String metricCode);
}
```

- [ ] **Step 4: `HousingPriceIndexJpaRepository`에 쿼리 메서드 추가**

`src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexJpaRepository.java` 전체를 다음으로 교체:

```java
package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.domain.model.stats.HousingBenchmarkRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface HousingPriceIndexJpaRepository extends JpaRepository<HousingPriceIndexEntity, UUID> {
    List<HousingPriceIndexEntity> findByMetricCodeAndRegionCodeAndBaseDateBetweenOrderByBaseDateAsc(
            String metricCode, String regionCode, LocalDate from, LocalDate to);

    // 지표별 실제 수집된 지역 카탈로그 — KB Land 응답이 결정하는 값이라 하드코딩 금지
    @Query("select distinct new com.kista.domain.model.stats.HousingBenchmarkRegion(e.regionCode, e.regionName) "
            + "from HousingPriceIndexEntity e where e.metricCode = :metricCode order by e.regionCode")
    List<HousingBenchmarkRegion> findDistinctRegionsByMetricCode(@Param("metricCode") String metricCode);
}
```

- [ ] **Step 5: `HousingPriceIndexPersistenceAdapter`에 구현 추가**

`src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexPersistenceAdapter.java` 전체를 다음으로 교체:

```java
package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.domain.model.stats.HousingBenchmarkRegion;
import com.kista.domain.model.stats.HousingPriceIndex;
import com.kista.domain.port.out.HousingPriceIndexPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class HousingPriceIndexPersistenceAdapter implements HousingPriceIndexPort {

    private static final int BATCH_SIZE = 1000; // 주간 지수는 지역 25개 × 20년치라 약 21,900행 — 단일 batchUpdate 방지 위해 청킹

    private final HousingPriceIndexJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void upsertAll(List<HousingPriceIndex> indices) {
        // KB Land는 과거 기준일 값이 보정될 수 있어 자연키 충돌 시 최신 응답으로 갱신한다.
        String sql = """
                INSERT INTO housing_price_indices (
                    source,
                    metric_code,
                    region_code,
                    region_name,
                    base_date,
                    index_value,
                    source_updated_date,
                    fetched_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, metric_code, region_code, base_date) DO UPDATE
                   SET region_name = EXCLUDED.region_name,
                       index_value = EXCLUDED.index_value,
                       source_updated_date = EXCLUDED.source_updated_date,
                       fetched_at = EXCLUDED.fetched_at,
                       updated_at = now()
                """;

        // batchSize를 넘기면 JdbcTemplate이 내부적으로 그 크기마다 flush하므로 별도 청킹 루프 불필요
        jdbcTemplate.batchUpdate(sql, indices, BATCH_SIZE, (ps, index) -> {
            ps.setString(1, index.source());
            ps.setString(2, index.metricCode());
            ps.setString(3, index.regionCode());
            ps.setString(4, index.regionName());
            ps.setObject(5, index.baseDate());
            ps.setBigDecimal(6, index.indexValue());
            ps.setObject(7, index.sourceUpdatedDate());
            ps.setTimestamp(8, Timestamp.from(index.fetchedAt()));
        });
    }

    @Override
    public List<HousingPriceIndex> findByMetricCodeAndRegionCodeAndBaseDateBetween(
            String metricCode, String regionCode, LocalDate from, LocalDate to) {
        return repository.findByMetricCodeAndRegionCodeAndBaseDateBetweenOrderByBaseDateAsc(
                        metricCode, regionCode, from, to)
                .stream()
                .map(HousingPriceIndexEntity::toDomain)
                .toList();
    }

    @Override
    public List<HousingBenchmarkRegion> findDistinctRegions(String metricCode) {
        return repository.findDistinctRegionsByMetricCode(metricCode);
    }
}
```

- [ ] **Step 6: `HousingPriceIndexEntity.toDomain()`을 package-private에서 접근 가능한지 확인**

`src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexEntity.java`를 열어 `toDomain()`이 이미 package-private(수정자 없음)으로 선언돼 있는지 확인한다 — 같은 패키지(`adapter.out.persistence.housingbenchmark`)의 `HousingPriceIndexPersistenceAdapter`에서 그대로 호출 가능해야 한다. 이미 그렇게 선언돼 있으면(직전 커밋에서 이미 그렇게 작성됨) 수정 불필요.

- [ ] **Step 7: 테스트 실행해 통과 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.persistence.housingbenchmark.HousingPriceIndexPersistenceAdapterTest' --rerun-tasks`
Expected: PASS (전체)

- [ ] **Step 8: 전체 컴파일 확인** (다른 곳에서 `HousingPriceIndexPort`를 구현하는 mock/stub이 없는지 — 있다면 새 메서드 미구현으로 컴파일 에러)

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/HousingPriceIndexPort.java \
        src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexJpaRepository.java \
        src/main/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexPersistenceAdapter.java \
        src/test/java/com/kista/adapter/out/persistence/housingbenchmark/HousingPriceIndexPersistenceAdapterTest.java
git commit -m "feat(stats): HousingPriceIndexPort에 조회 메서드 2개 추가

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: `StatsService` 아파트 비교를 quintile→regionCode/WEEKLY로 재작성

이 태스크가 가장 크다 — 도메인 모델(`Benchmark.quintile` 제거), `UserStatsUseCase`/`StatsService` 시그니처, `computeHousingComparisonBody` 전면 재작성, `/regions` 엔드포인트의 데이터소스 전환을 한 번에 다룬다. `StatsServiceTest`의 아파트 비교 관련 테스트 전체(약 20개)를 WEEKLY/regionCode 기준으로 재작성한다. `StatsControllerTest`/DTO는 Task 5에서 다룬다 — 이 태스크에서는 `StatsController.java`가 여전히 `int quintile`을 넘기므로 **Task 4 완료 시점엔 `StatsController.java`가 컴파일 에러 상태**가 된다(Task 5 완료까지 일시적). 이 계획을 실행하는 워커는 Task 4~5를 연속으로 진행해야 한다(중간에 별도 브랜치로 반쪽 상태를 커밋하지 말 것 — 커밋은 Task 단위로 하되, Task 4 커밋 시점에 컴파일이 깨져 있다면 Task 5까지 마친 뒤 한 번에 커밋해도 무방하다. 아래 Step에도 이 점을 명시한다).

**Files:**
- Modify: `src/main/java/com/kista/domain/model/stats/HousingBenchmarkComparison.java`
- Modify: `src/main/java/com/kista/domain/port/in/UserStatsUseCase.java`
- Modify: `src/main/java/com/kista/application/service/stats/StatsService.java`
- Modify: `src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java` (Task 1 Step 6의 7-arg `BENCHMARK`를 6-arg로 되돌림)
- Modify: `src/test/java/com/kista/application/service/stats/StatsServiceTest.java`

**Interfaces:**
- Consumes: Task 3의 `HousingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween`/`findDistinctRegions`. Task 1의 `BenchmarkGranularity.WEEKLY`.
- Produces: `UserStatsUseCase.getHousingBenchmarkComparison(UUID, BenchmarkScope, UUID, String regionCode, LocalDate, LocalDate)` — Task 5가 사용.

- [ ] **Step 1: `HousingBenchmarkComparison.Benchmark`에서 `quintile` 필드 제거**

`src/main/java/com/kista/domain/model/stats/HousingBenchmarkComparison.java`의 `Benchmark` record를:

```java
    public record Benchmark(
            BenchmarkAssetType assetType,
            String regionCode,   // HOUSING 전용, ETF면 null
            String regionName,   // HOUSING 전용, ETF면 null
            Integer quintile,    // HOUSING 전용, ETF면 null
            String symbol,       // ETF 전용, HOUSING이면 null
            String label,
            LocalDate sourceUpdatedDate
    ) {}
```

를

```java
    public record Benchmark(
            BenchmarkAssetType assetType,
            String regionCode,   // HOUSING 전용, ETF면 null
            String regionName,   // HOUSING 전용, ETF면 null
            String symbol,       // ETF 전용, HOUSING이면 null
            String label,
            LocalDate sourceUpdatedDate
    ) {}
```

로 변경. (이 시점부터 `HousingBenchmarkComparisonBuilderTest`의 `BENCHMARK` 상수를 포함해 quintile 인자를 넘기던 모든 곳이 컴파일 에러가 난다 — 다음 Step들에서 순서대로 해소한다.)

- [ ] **Step 2: `HousingBenchmarkComparisonBuilderTest`의 `BENCHMARK` 상수를 6-arg로 수정**

`src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java`:

```java
    private static final HousingBenchmarkComparison.Benchmark BENCHMARK = new HousingBenchmarkComparison.Benchmark(
            BenchmarkAssetType.HOUSING, "1100000000", "서울", null, null, "서울 아파트 매매가격지수", null);
```

를

```java
    private static final HousingBenchmarkComparison.Benchmark BENCHMARK = new HousingBenchmarkComparison.Benchmark(
            BenchmarkAssetType.HOUSING, "1100000000", "서울", "서울 아파트 매매가격지수", null);
```

로 변경.

- [ ] **Step 3: `UserStatsUseCase.getHousingBenchmarkComparison` 시그니처 변경**

`src/main/java/com/kista/domain/port/in/UserStatsUseCase.java`의:

```java
    HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            int quintile, LocalDate from, LocalDate to);
```

를

```java
    HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            String regionCode, LocalDate from, LocalDate to);
```

로 변경.

- [ ] **Step 4: `StatsService` 재작성**

`src/main/java/com/kista/application/service/stats/StatsService.java`에서 아래 부분들을 순서대로 변경한다.

4-1. 생성자 주입 필드에 `housingPriceIndexPort` 추가 — 48-58번째 줄 근처, `private final HousingBenchmarkPricePort housingBenchmarkPricePort;` 바로 아래에:

```java
    private final HousingBenchmarkPricePort housingBenchmarkPricePort;
    private final HousingPriceIndexPort housingPriceIndexPort;
```

(`@RequiredArgsConstructor`가 생성자를 자동 생성하므로 별도 생성자 코드 작성 불필요.)

4-2. `BenchmarkComparisonKey`의 `Integer quintile` → `String regionCode` (66-69번째 줄):

```java
    private record BenchmarkComparisonKey(
            UUID userId, BenchmarkAssetType assetType, BenchmarkScope scope, UUID strategyId,
            Integer quintile, String symbol, LocalDate from, LocalDate to) {}
```

를

```java
    private record BenchmarkComparisonKey(
            UUID userId, BenchmarkAssetType assetType, BenchmarkScope scope, UUID strategyId,
            String regionCode, String symbol, LocalDate from, LocalDate to) {}
```

로 변경.

4-3. `getHousingBenchmarkComparison`(153-164번째 줄)을:

```java
    @Override
    public HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            int quintile, LocalDate from, LocalDate to) {
        validateComparisonRequest(scope, strategyId, quintile, from, to);
        // 병렬 조회(본체∥환율) 이전에 소유권을 동기적으로 검증 — 인가 실패 시 외부 환율 API가 호출되지 않도록 보장
        authorizeIfStrategyScope(scope, strategyId, userId);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.HOUSING, scope, strategyId, quintile, null, from, to);
        return comparisonWithExchangeRate(key,
                () -> computeHousingComparisonBody(userId, scope, strategyId, quintile, from, to));
    }
```

로 교체:

```java
    @Override
    public HousingBenchmarkComparison getHousingBenchmarkComparison(
            UUID userId, BenchmarkScope scope, UUID strategyId,
            String regionCode, LocalDate from, LocalDate to) {
        validateComparisonRequest(scope, strategyId, regionCode, from, to);
        // 병렬 조회(본체∥환율) 이전에 소유권을 동기적으로 검증 — 인가 실패 시 외부 환율 API가 호출되지 않도록 보장
        authorizeIfStrategyScope(scope, strategyId, userId);
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.HOUSING, scope, strategyId, regionCode, null, from, to);
        return comparisonWithExchangeRate(key,
                () -> computeHousingComparisonBody(userId, scope, strategyId, regionCode, from, to));
    }
```

4-4. `computeHousingComparisonBody`(166-195번째 줄)를:

```java
    private HousingBenchmarkComparison computeHousingComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, int quintile, LocalDate from, LocalDate to) {
        InvestmentContext ctx = buildInvestmentContext(userId, scope, strategyId, from, to, BenchmarkGranularity.MONTHLY);

        LocalDate benchmarkFrom = ctx.effectiveFrom().minusMonths(1).withDayOfMonth(1);
        LocalDate benchmarkTo = ctx.effectiveTo().withDayOfMonth(1);
        List<HousingBenchmarkPrice> benchmarkRows =
                housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                        HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                        SEOUL_REGION_CODE, benchmarkFrom, benchmarkTo);
        Map<LocalDate, BigDecimal> selectedBenchmarkPrices = benchmarkRows.stream()
                .collect(Collectors.toMap(
                        HousingBenchmarkPrice::baseMonth,
                        price -> selectQuintilePrice(price, quintile),
                        (left, right) -> right,
                        TreeMap::new));
        LocalDate sourceUpdatedDate = benchmarkRows.stream()
                .map(HousingBenchmarkPrice::sourceUpdatedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.HOUSING, SEOUL_REGION_CODE, SEOUL_REGION_NAME, quintile, null,
                SEOUL_REGION_NAME + " 아파트 " + quintile + "분위", sourceUpdatedDate);

        return comparisonBuilder.build(
                scope, ctx.selectedStrategy(), benchmark, ctx.investmentPoints(), selectedBenchmarkPrices,
                BenchmarkGranularity.MONTHLY);
    }
```

로 교체:

```java
    private HousingBenchmarkComparison computeHousingComparisonBody(
            UUID userId, BenchmarkScope scope, UUID strategyId, String regionCode, LocalDate from, LocalDate to) {
        InvestmentContext ctx = buildInvestmentContext(userId, scope, strategyId, from, to, BenchmarkGranularity.WEEKLY);

        List<HousingPriceIndex> indexRows = housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, regionCode,
                ctx.effectiveFrom(), ctx.effectiveTo());
        NavigableMap<LocalDate, BigDecimal> indexByDate = indexRows.stream()
                .collect(Collectors.toMap(HousingPriceIndex::baseDate, HousingPriceIndex::indexValue,
                        (left, right) -> right, TreeMap::new));

        // 투자 일별 지수를 KB 주간 조사일에 as-of(그 날짜 이하 최근값)로 스냅한다 — 조사일과
        // 투자 평가일이 어긋나는 날(미국 휴일, KB 결측 주)이 정확한 날짜 일치 교집합에서 조용히
        // 사라지는 것을 방지한다.
        NavigableMap<LocalDate, InvestmentPoint> investmentByDate = ctx.investmentPoints().stream()
                .collect(Collectors.toMap(InvestmentPoint::baseDate, Function.identity(),
                        (left, right) -> right, TreeMap::new));
        List<InvestmentPoint> snappedPoints = new ArrayList<>();
        for (LocalDate surveyDate : indexByDate.keySet()) {
            var asOf = investmentByDate.floorEntry(surveyDate);
            if (asOf == null) continue; // 투자 시작 전 조사일은 스킵
            snappedPoints.add(new InvestmentPoint(surveyDate, asOf.getValue().investmentIndexUsd(), null));
        }

        String regionName = indexRows.stream().findFirst().map(HousingPriceIndex::regionName).orElse(null);
        LocalDate sourceUpdatedDate = indexRows.stream()
                .map(HousingPriceIndex::sourceUpdatedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.HOUSING, regionCode, regionName, null,
                (regionName != null ? regionName : regionCode) + " 아파트 매매가격지수", sourceUpdatedDate);

        return comparisonBuilder.build(
                scope, ctx.selectedStrategy(), benchmark, snappedPoints, indexByDate,
                BenchmarkGranularity.WEEKLY);
    }
```

4-5. `getHousingBenchmarkRegions`(343-346번째 줄)를:

```java
    @Override
    public List<HousingBenchmarkRegion> getHousingBenchmarkRegions() {
        return housingBenchmarkPricePort.findDistinctRegions(HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE);
    }
```

로 교체:

```java
    @Override
    public List<HousingBenchmarkRegion> getHousingBenchmarkRegions() {
        return housingPriceIndexPort.findDistinctRegions(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX);
    }
```

로 변경. (5분위 14개 지역 대신 주간 지수 25개 지역 카탈로그를 반환하게 된다 — 비교 가능한 지역 전체를 선택기에 노출하기 위함. `/housing-benchmark/series`는 별도 메서드(`getHousingBenchmarkSeries`)라 영향받지 않는다.)

4-6. `validateComparisonRequest`(401-418번째 줄)를:

```java
    private static void validateComparisonRequest(
            BenchmarkScope scope, UUID strategyId, int quintile, LocalDate from, LocalDate to) {
        if (scope == null) {
            throw new IllegalArgumentException("scope은 필수입니다");
        }
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (quintile < 1 || quintile > 5) {
            throw new IllegalArgumentException("quintile은 1부터 5까지여야 합니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
    }
```

로 교체:

```java
    private static void validateComparisonRequest(
            BenchmarkScope scope, UUID strategyId, String regionCode, LocalDate from, LocalDate to) {
        if (scope == null) {
            throw new IllegalArgumentException("scope은 필수입니다");
        }
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode는 비어있을 수 없습니다");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to 이후일 수 없습니다");
        }
    }
```

4-7. `selectQuintilePrice`(453-462번째 줄) 메서드 전체 삭제 — 호출자가 없어졌다:

```java
    private static BigDecimal selectQuintilePrice(HousingBenchmarkPrice price, int quintile) {
        return switch (quintile) {
            case 1 -> price.firstQuintilePrice();
            case 2 -> price.secondQuintilePrice();
            case 3 -> price.thirdQuintilePrice();
            case 4 -> price.fourthQuintilePrice();
            case 5 -> price.fifthQuintilePrice();
            default -> throw new IllegalArgumentException("quintile은 1부터 5까지여야 합니다");
        };
    }
```

이 메서드 블록을 통째로 삭제.

4-8. `computeEtfComparisonBody`의 `Benchmark` 생성(230-232번째 줄, Step 1에서 6-arg로 바뀐 레코드에 맞게 수정)을:

```java
        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.ETF, null, null, null, symbol.name(),
                symbol.name() + " (" + symbol.description() + ")", sourceUpdatedDate);
```

로 교체:

```java
        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                BenchmarkAssetType.ETF, null, null, symbol.name(),
                symbol.name() + " (" + symbol.description() + ")", sourceUpdatedDate);
```

4-9. `getEtfBenchmarkComparison`의 `BenchmarkComparisonKey` 생성(205-206번째 줄, `quintile` 위치가 `regionCode`로 이름만 바뀐 것을 반영)을:

```java
        BenchmarkComparisonKey key = new BenchmarkComparisonKey(
                userId, BenchmarkAssetType.ETF, scope, strategyId, null, symbol.name(), from, to);
```

그대로 유지(필드명만 `quintile`→`regionCode`로 바뀌었을 뿐 여기서 넘기는 값은 이미 `null`이라 변경 불필요 — 컴파일러가 타입을 `String`으로 추론하므로 문제없다).

- [ ] **Step 5: 전체 컴파일 확인 (StatsController.java는 아직 컴파일 에러 — 예상된 상태)**

Run: `./gradlew compileJava`
Expected: `StatsController.java`에서 `int` → `String` 타입 불일치로 컴파일 에러. **이 시점의 에러는 정상이다** — Task 5에서 해소한다. 여기서는 `StatsService.java`/`HousingBenchmarkComparison.java`/`UserStatsUseCase.java` 자체에 새로운 에러가 없는지만 에러 메시지로 확인한다(모두 `StatsController.java` 관련 에러여야 함).

- [ ] **Step 6: `StatsServiceTest`의 헬퍼 메서드 교체**

`src/test/java/com/kista/application/service/stats/StatsServiceTest.java`:

6-1. `@Mock` 필드 추가 (43번째 줄 근처, `@Mock HousingBenchmarkPricePort housingBenchmarkPricePort;` 바로 아래):

```java
    @Mock HousingBenchmarkPricePort housingBenchmarkPricePort;
    @Mock HousingPriceIndexPort housingPriceIndexPort;
```

6-2. `stubPortfolioComparison` 메서드(118-129번째 줄)를 삭제하고 그 자리에 새 헬퍼 3개로 교체:

```java
    private static HousingPriceIndex weeklyIndex(LocalDate baseDate, String value) {
        return new HousingPriceIndex(
                HousingPriceIndex.SOURCE_KBLAND, HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                "1100000000", "서울", baseDate, new BigDecimal(value),
                LocalDate.of(2026, 2, 15), Instant.parse("2026-02-16T00:00:00Z"));
    }

    private static List<HousingPriceIndex> weeklyIndices() {
        return List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 23), "400"));
    }

    private StrategyCycle stubWeeklyPortfolioComparison(String startValue, String endValue) {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), startValue, "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), endValue, "2026-02-23T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());
        return cycle;
    }
```

`benchmarkPrice`/`benchmarkPrices` 헬퍼(101-116번째 줄)는 **삭제하지 않는다** — `/series` 테스트(736-794번째 줄 부근)가 여전히 사용한다.

- [ ] **Step 7: 아파트 비교 테스트 전체 재작성**

아래는 `StatsServiceTest.java`의 415-807번째 줄 구간(`포트폴리오와_서울_아파트를_첫_공통_월_100으로_비교한다`부터 `지역_카탈로그는_port_결과를_그대로_반환한다` 직전까지, `/series` 테스트 제외)에 있던 아파트-비교 관련 테스트 전체를 **아래 코드로 통째로 교체**한다. `// ── ETF 벤치마크 비교 ──` 구분 주석(809번째 줄)은 그대로 남기고 그 이전까지를 교체 대상으로 한다. `/series` 3개 테스트(`시계열_조회는_from_to를_그대로_port에_전달한다` 등, 736-794번째 줄)는 이 구간 안에 있지만 **변경하지 않는다** — 그 3개 테스트는 그대로 두고 나머지만 교체한다.

```java
    @Test
    void 포트폴리오와_아파트_지수를_첫_KB_조사일_100으로_비교한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-23T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 23), "400")));
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.scope()).isEqualTo(BenchmarkScope.PORTFOLIO);
        assertThat(result.strategy()).isNull();
        assertThat(result.benchmark().regionCode()).isEqualTo("1100000000");
        assertThat(result.benchmark().regionName()).isEqualTo("서울");
        assertThat(result.benchmark().label()).isEqualTo("서울 아파트 매매가격지수");
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().getFirst().investmentIndexUsd()).isEqualByComparingTo("100.0");
        assertThat(result.points().getFirst().benchmarkIndex()).isEqualByComparingTo("100.0");
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("184.2");
        assertThat(result.points().getLast().benchmarkIndex()).isEqualByComparingTo("400.0");
        assertThat(result.summary().investmentCumulativeReturn()).isEqualByComparingTo("0.842");
        assertThat(result.summary().benchmarkCumulativeReturn()).isEqualByComparingTo("3.0");
        assertThat(result.summary().excessReturn()).isEqualByComparingTo("-2.158");
        // 49일 구간(< 90일)이라 연환산 수익률은 억제된다
        assertThat(result.summary().investmentAnnualizedReturn()).isNull();
        assertThat(result.summary().benchmarkAnnualizedReturn()).isNull();
        assertThat(result.currentExchangeRate().midRate()).isEqualByComparingTo("1365.20");
        assertThat(result.currentExchangeRate().source()).isEqualTo("TOSS_INVEST");
        assertThat(result.currentExchangeRate().fetchedAt()).isNotNull();
        assertThat(result.emptyReason()).isNull();

        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                "1100000000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        verify(exchangeRatePort, times(1)).getExchangeRate();
    }

    @Test
    void 90일_이상_구간에서는_연환산_수익률을_계산한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-05-04T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 5, 4), "400")));
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 5, 4));

        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 365.0 / 119.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
        assertThat(result.summary().benchmarkAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(4.0, 365.0 / 119.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
    }

    @Test
    void KB_결측_주가_있어도_구간_수익률이_비지_않는다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "110.00", "2026-02-16T01:00:00Z")));
        // 2026-01-12 조사 주가 KB 결측(추석 등)으로 비어 있어 1/5 -> 2/16으로 3주 이상 건너뛴다
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 16), "121")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 16));

        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 16));
        // 옛 MONTHLY 로직이었다면 정확히 1개월 뒤가 아니라는 이유로 null이 됐을 구간이지만,
        // WEEKLY는 결측 주와 무관하게 인접 공통 포인트끼리 항상 수익률을 계산한다
        assertThat(result.points().getLast().investmentPeriodReturn()).isEqualByComparingTo("0.1");
        assertThat(result.points().getLast().benchmarkPeriodReturn()).isEqualByComparingTo("0.21");
        assertThat(result.summary().investmentCumulativeReturn()).isEqualByComparingTo("0.1");
        assertThat(result.summary().benchmarkCumulativeReturn()).isEqualByComparingTo("0.21");
    }

    @Test
    void 아파트_벤치마크는_지정된_regionCode의_지수를_조회하고_지역명을_동적으로_채운다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-23T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                eq(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX), eq("2600000000"), any(), any()))
                .thenReturn(List.of(
                        new HousingPriceIndex(HousingPriceIndex.SOURCE_KBLAND,
                                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                                "2600000000", "부산", LocalDate.of(2026, 1, 5), new BigDecimal("100"),
                                null, Instant.parse("2026-02-16T00:00:00Z")),
                        new HousingPriceIndex(HousingPriceIndex.SOURCE_KBLAND,
                                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                                "2600000000", "부산", LocalDate.of(2026, 2, 23), new BigDecimal("110"),
                                null, Instant.parse("2026-02-16T00:00:00Z"))));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "2600000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.benchmark().regionCode()).isEqualTo("2600000000");
        assertThat(result.benchmark().regionName()).isEqualTo("부산");
        assertThat(result.benchmark().label()).isEqualTo("부산 아파트 매매가격지수");
        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "2600000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
    }

    @Test
    void 벤치마크_전체_포트폴리오는_모의계좌를_제외한다() {
        UUID mockAccountId = UUID.randomUUID();
        Account mockAccount = new Account(mockAccountId, USER_ID, "모의계좌",
                "00000000", "key", "secret", null, Account.Broker.MOCK, null);
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of(testAccount(), mockAccount));
        when(strategyPort.findByAccountIds(List.of(ACCOUNT_ID))).thenReturn(Map.of(ACCOUNT_ID, List.of(STRATEGY)));
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        ArgumentCaptor<Set<UUID>> strategyIdsCaptor = ArgumentCaptor.forClass(Set.class);
        when(strategyCyclePort.findByStrategyIds(strategyIdsCaptor.capture())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-23T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.points().getFirst().investmentIndexUsd()).isEqualByComparingTo("100.0");
        assertThat(strategyIdsCaptor.getValue()).containsExactly(STRATEGY_ID);
        verify(strategyPort, never()).findByAccountIds(argThat(ids -> ids.contains(mockAccountId)));
    }

    @Test
    void 투자_시작_전_KB_조사일은_스킵하고_이후_조사일부터_비교한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-02-02");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-02-02T01:00:00Z"),
                depositSnapshot(cycle.id(), "110.00", "2026-02-23T01:00:00Z")));
        // 1/5 조사일은 투자 시작(2/2) 이전이라 as-of 값이 없어 스킵된다
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "90"),
                weeklyIndex(LocalDate.of(2026, 2, 2), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 23), "121")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 23));
    }

    @Test
    void 주간_지수의_고점_대비_최대낙폭을_계산한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "80.00", "2026-01-19T01:00:00Z"),
                depositSnapshot(cycle.id(), "120.00", "2026-02-02T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 1, 19), "90"),
                weeklyIndex(LocalDate.of(2026, 2, 2), "135")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 2));

        assertThat(result.summary().investmentMaxDrawdown()).isEqualByComparingTo("-0.2");
        assertThat(result.summary().benchmarkMaxDrawdown()).isEqualByComparingTo("-0.1");
    }

    @Test
    void 소유한_개별_전략만_조회하고_전략_메타데이터를_반환한다() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(testAccount());
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any()))
                .thenReturn(List.of(
                        depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                        depositSnapshot(cycle.id(), "110.00", "2026-02-23T01:00:00Z")));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.strategy().id()).isEqualTo(STRATEGY_ID);
        assertThat(result.strategy().type()).isEqualTo(Strategy.Type.INFINITE);
        assertThat(result.strategy().ticker()).isEqualTo(Strategy.Ticker.SOXL);
        verify(cyclePositionPort).findByStrategyAndRange(eq(STRATEGY_ID), eq(Instant.EPOCH), any());
        verify(cyclePositionPort, never()).findByUserAndRange(any(), any(), any());
    }

    @Test
    void 소유하지_않은_전략은_포지션을_읽기_전에_거부한다() {
        UUID otherUserId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(STRATEGY);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(new Account(
                ACCOUNT_ID, otherUserId, "타인계좌", "1", "key", "secret", null,
                Account.Broker.KIS, null));

        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, "1100000000", FROM, TO))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(cyclePositionPort, housingPriceIndexPort, exchangeRatePort);
    }

    @Test
    void 역전된_기간은_데이터를_읽기_전에_거부한다() {
        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountPort, strategyPort, strategyCyclePort,
                cyclePositionPort, housingPriceIndexPort, exchangeRatePort);
    }

    @Test
    void 투자_데이터가_없으면_NO_INVESTMENT_DATA를_반환한다() {
        stubUserWithStrategy();
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of());
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of());
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", FROM, TO);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("NO_INVESTMENT_DATA");
        verify(exchangeRatePort).getExchangeRate();
    }

    @Test
    void 공통_조사일이_두_개_미만이면_INSUFFICIENT_COMMON_MONTHS를_반환한다() {
        stubWeeklyPortfolioComparison("100.00", "110.00");
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any()))
                .thenReturn(List.of(weeklyIndex(LocalDate.of(2026, 1, 5), "100")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("INSUFFICIENT_COMMON_MONTHS");
    }

    @Test
    void 환율_예외는_완성된_비교_결과에서_환율만_null로_격리한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate())
                .thenReturn(new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")))
                .thenThrow(new TossApiException("환율 조회 실패", null));

        HousingBenchmarkComparison success = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        HousingBenchmarkComparison isolated = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(isolated.currentExchangeRate()).isNull();
        assertThat(isolated.points()).isEqualTo(success.points());
        assertThat(isolated.period()).isEqualTo(success.period());
        assertThat(isolated.summary()).isEqualTo(success.summary());
        assertThat(isolated.benchmark()).isEqualTo(success.benchmark());
        assertThat(isolated.emptyReason()).isEqualTo(success.emptyReason());
        verify(exchangeRatePort, times(2)).getExchangeRate();
    }
```

- [ ] **Step 8: `지역_카탈로그는_port_결과를_그대로_반환한다` 테스트 교체**

`src/test/java/com/kista/application/service/stats/StatsServiceTest.java`의 796-807번째 줄:

```java
    @Test
    void 지역_카탈로그는_port_결과를_그대로_반환한다() {
        List<HousingBenchmarkRegion> regions = List.of(
                new HousingBenchmarkRegion("1100000000", "서울"),
                new HousingBenchmarkRegion("2600000000", "부산"));
        when(housingBenchmarkPricePort.findDistinctRegions(HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE))
                .thenReturn(regions);

        List<HousingBenchmarkRegion> result = statsService.getHousingBenchmarkRegions();

        assertThat(result).isEqualTo(regions);
    }
```

를

```java
    @Test
    void 지역_카탈로그는_주간_지수_port_결과를_그대로_반환한다() {
        List<HousingBenchmarkRegion> regions = List.of(
                new HousingBenchmarkRegion("1100000000", "서울"),
                new HousingBenchmarkRegion("2600000000", "부산"));
        when(housingPriceIndexPort.findDistinctRegions(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX))
                .thenReturn(regions);

        List<HousingBenchmarkRegion> result = statsService.getHousingBenchmarkRegions();

        assertThat(result).isEqualTo(regions);
    }
```

로 교체.

- [ ] **Step 9: 캐시/환율/예외 테스트 3개 교체**

`src/test/java/com/kista/application/service/stats/StatsServiceTest.java`에서 아래 3개 테스트를 찾아 교체한다(각각 `stubPortfolioComparison`/`quintile`/`housingBenchmarkPricePort`를 쓰던 것을 weekly 버전으로 교체):

`누락되거나_0_이하인_현재_환율은_null로_격리한다`(959-976번째 줄)를:

```java
    @Test
    void 누락되거나_0_이하인_현재_환율은_null로_격리한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                null,
                new TossExchangeRate(new BigDecimal("1370.00"), null),
                new TossExchangeRate(BigDecimal.ZERO, BigDecimal.ZERO),
                new TossExchangeRate(new BigDecimal("-1"), new BigDecimal("-1")));

        for (int invocation = 0; invocation < 4; invocation++) {
            HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                    USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                    LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
            assertThat(result.currentExchangeRate()).isNull();
            assertThat(result.points()).isNotEmpty();
        }

        verify(exchangeRatePort, times(4)).getExchangeRate();
    }
```

`벤치마크_비교는_같은_파라미터_재조회시_본체는_캐시하고_환율은_매번_재조회한다`(1073-1091번째 줄)를:

```java
    @Test
    void 벤치마크_비교는_같은_파라미터_재조회시_본체는_캐시하고_환율은_매번_재조회한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")));

        HousingBenchmarkComparison first = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        HousingBenchmarkComparison second = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(second.points()).isEqualTo(first.points());
        // 본체(DB) 조회는 캐시 hit이라 1회만 — cyclePositionPort/housingPriceIndexPort 둘 다 검증
        verify(cyclePositionPort, times(1)).findByUserAndRange(any(), any(), any());
        verify(housingPriceIndexPort, times(1)).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any());
        // 환율은 캐시 대상이 아니라 응답마다 후결합 재조회
        verify(exchangeRatePort, times(2)).getExchangeRate();
    }

    @Test
    void 벤치마크_비교는_regionCode가_다르면_별도_캐시_키로_본체를_재계산한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(exchangeRatePort.getExchangeRate()).thenReturn(
                new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")));

        statsService.getHousingBenchmarkComparison(USER_ID, BenchmarkScope.PORTFOLIO, null,
                "1100000000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        statsService.getHousingBenchmarkComparison(USER_ID, BenchmarkScope.PORTFOLIO, null,
                "2600000000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        // regionCode가 캐시 키에 포함되므로 본체(DB)가 각각 다시 조회된다
        verify(cyclePositionPort, times(2)).findByUserAndRange(any(), any(), any());
        verify(housingPriceIndexPort, times(2)).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any());
    }
```

(기존 `벤치마크_비교는_quintile이_다르면_별도_캐시_키로_본체를_재계산한다`은 위 새 테스트로 대체됐으므로 원래 자리의 것을 삭제한다.)

`벤치마크_본체_계산_실패는_병렬_래핑_없이_원본_예외를_그대로_전파한다`(파일 끝, 1108-1123번째 줄)를:

```java
    @Test
    void 벤치마크_본체_계산_실패는_병렬_래핑_없이_원본_예외를_그대로_전파한다() {
        stubUserWithStrategy();
        StrategyCycle cycle = activeCycle("100.00", "2026-01-05");
        when(strategyCyclePort.findByStrategyIds(any())).thenReturn(List.of(cycle));
        when(cyclePositionPort.findByUserAndRange(eq(USER_ID), eq(Instant.EPOCH), any())).thenReturn(List.of(
                depositSnapshot(cycle.id(), "100.00", "2026-01-05T01:00:00Z"),
                depositSnapshot(cycle.id(), "184.20", "2026-02-23T01:00:00Z")));
        RuntimeException boom = new IllegalStateException("벤치마크 조회 실패");
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23)))
                .isSameAs(boom);
    }
```

- [ ] **Step 10: `StatsServiceTest`만 실행해 결과 확인 (StatsController가 아직 깨져 있어 클래스별 실행만 가능)**

Run: `./gradlew compileTestJava 2>&1 | grep -v StatsController` 로 `StatsServiceTest` 관련 컴파일 에러가 없는지 우선 확인한 뒤,
Run: `./gradlew test --tests 'com.kista.application.service.stats.StatsServiceTest' --rerun-tasks`
Expected: `StatsController.java` 컴파일 에러 때문에 전체 컴파일은 여전히 실패한다 — **이 Step에서는 통과를 기대하지 않는다.** Task 5 Step 1~2(컨트롤러/DTO 수정)까지 마친 뒤에야 이 테스트를 실제로 실행할 수 있다. 여기서는 `StatsServiceTest.java` 자체에 문법 오류가 없는지 IDE/grep으로 육안 확인만 한다.

- [ ] **Step 11: Task 4는 별도 커밋하지 않고 Task 5로 이어간다** (컴파일 가능한 완결 상태가 아니므로)

---

### Task 5: `StatsController` + `HousingBenchmarkComparisonResponse` DTO 전환, 전체 컴파일·테스트 통과, 커밋

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/StatsController.java`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java`
- Modify: `src/test/java/com/kista/adapter/in/web/StatsControllerTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponseTest.java`

**Interfaces:**
- Consumes: Task 4의 `UserStatsUseCase.getHousingBenchmarkComparison(UUID, BenchmarkScope, UUID, String regionCode, LocalDate, LocalDate)`.

- [ ] **Step 1: `StatsController` 파라미터 전환**

`src/main/java/com/kista/adapter/in/web/StatsController.java`의 66-101번째 줄:

```java
    @Operation(summary = "벤치마크 비교 (서울 아파트 · ETF)",
            description = "USD 투자 성과와 벤치마크(서울 아파트 분위 가격 또는 SPY/QQQ/QLD/IBIT/ETHA ETF)를 비교합니다. "
                    + "benchmarkType=ETF면 symbol이 필수이며 quintile은 무시됩니다.")
    @GetMapping("/housing-benchmark")
    public HousingBenchmarkComparisonResponse getHousingBenchmarkComparison(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "PORTFOLIO") BenchmarkScope scope,
            @RequestParam(required = false) UUID strategyId,
            @RequestParam(defaultValue = "HOUSING") BenchmarkAssetType benchmarkType,
            @RequestParam(defaultValue = "3") int quintile,
            @RequestParam(required = false) EtfBenchmarkSymbol symbol,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (benchmarkType == BenchmarkAssetType.ETF) {
            if (symbol == null) {
                throw new IllegalArgumentException("benchmarkType=ETF에는 symbol이 필요합니다");
            }
            return HousingBenchmarkComparisonResponse.from(
                    userStats.getEtfBenchmarkComparison(userId, scope, strategyId, symbol, from, to));
        }
        if (quintile < 1 || quintile > 5) {
            throw new IllegalArgumentException("quintile은 1부터 5까지여야 합니다");
        }
        return HousingBenchmarkComparisonResponse.from(
                userStats.getHousingBenchmarkComparison(
                        userId, scope, strategyId, quintile, from, to));
    }
```

를

```java
    @Operation(summary = "벤치마크 비교 (아파트 매매가격지수 · ETF)",
            description = "USD 투자 성과와 벤치마크(아파트 지역 매매가격지수 또는 SPY/QQQ/QLD/IBIT/ETHA ETF)를 비교합니다. "
                    + "benchmarkType=ETF면 symbol이 필수이며 regionCode는 무시됩니다.")
    @GetMapping("/housing-benchmark")
    public HousingBenchmarkComparisonResponse getHousingBenchmarkComparison(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "PORTFOLIO") BenchmarkScope scope,
            @RequestParam(required = false) UUID strategyId,
            @RequestParam(defaultValue = "HOUSING") BenchmarkAssetType benchmarkType,
            @RequestParam(defaultValue = "1100000000") String regionCode,
            @RequestParam(required = false) EtfBenchmarkSymbol symbol,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // 서비스도 동일 검증을 하지만 여기서 fast-fail — 잘못된 파라미터로 서비스·DB 조회를 타지 않도록 의도적 중복
        if (scope == BenchmarkScope.STRATEGY && strategyId == null) {
            throw new IllegalArgumentException("STRATEGY scope에는 strategyId가 필요합니다");
        }
        if (scope == BenchmarkScope.PORTFOLIO && strategyId != null) {
            throw new IllegalArgumentException("PORTFOLIO scope에는 strategyId를 지정할 수 없습니다");
        }
        if (benchmarkType == BenchmarkAssetType.ETF) {
            if (symbol == null) {
                throw new IllegalArgumentException("benchmarkType=ETF에는 symbol이 필요합니다");
            }
            return HousingBenchmarkComparisonResponse.from(
                    userStats.getEtfBenchmarkComparison(userId, scope, strategyId, symbol, from, to));
        }
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode는 비어있을 수 없습니다");
        }
        return HousingBenchmarkComparisonResponse.from(
                userStats.getHousingBenchmarkComparison(
                        userId, scope, strategyId, regionCode, from, to));
    }
```

로 교체. (구 프론트가 여전히 `quintile=3`을 보내도 `@RequestParam`에 선언되지 않은 쿼리 파라미터는 Spring MVC가 조용히 무시하므로 400이 나지 않는다.)

`/housing-benchmark/regions`의 `@Operation` description(120-121번째 줄)도 갱신:

```java
    @Operation(summary = "서울 아파트 등 KB 지역 카탈로그",
            description = "5분위 시계열 조회에 사용 가능한 지역 코드·명 목록.")
```

를

```java
    @Operation(summary = "KB 지역 카탈로그",
            description = "아파트 벤치마크 비교(regionCode)에 사용 가능한 지역 코드·명 목록. "
                    + "5분위 시계열(`/housing-benchmark/series`)의 14개 지역은 이 목록(25개)의 부분집합이다.")
```

로 변경.

- [ ] **Step 2: `HousingBenchmarkComparisonResponse` DTO 전환**

`src/main/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java`의 32-49번째 줄:

```java
    private static final String HOUSING_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 서울 아파트는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다.";
    private static final String ETF_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자와 ETF 벤치마크 모두 USD 기준이며 환율 변수가 없습니다.";

    @Schema(name = "HousingBenchmarkStrategyInfo")
    public record StrategyInfo(UUID id, String type, String ticker) {}

    @Schema(name = "HousingBenchmarkDefinition")
    public record Benchmark(
            String assetType,
            @Schema(types = {"string", "null"}) String regionCode,
            @Schema(types = {"string", "null"}) String regionName,
            @Schema(types = {"integer", "null"}) Integer quintile,
            @Schema(types = {"string", "null"}) String symbol,
            String label,
            @Schema(types = {"string", "null"}, format = "date") LocalDate sourceUpdatedDate
    ) {}
```

를

```java
    private static final String HOUSING_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 아파트 매매가격지수는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다. "
                    + "벤치마크 시점은 KB Land 주간 조사일(매주 월요일) 기준입니다.";
    private static final String ETF_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자와 ETF 벤치마크 모두 USD 기준이며 환율 변수가 없습니다.";

    @Schema(name = "HousingBenchmarkStrategyInfo")
    public record StrategyInfo(UUID id, String type, String ticker) {}

    @Schema(name = "HousingBenchmarkDefinition")
    public record Benchmark(
            String assetType,
            @Schema(types = {"string", "null"}) String regionCode,
            @Schema(types = {"string", "null"}) String regionName,
            @Schema(types = {"string", "null"}) String symbol,
            String label,
            @Schema(types = {"string", "null"}, format = "date") LocalDate sourceUpdatedDate
    ) {}
```

로 교체.

`from()` 메서드의 `Benchmark` 생성부(100-103번째 줄):

```java
                new Benchmark(
                        benchmark.assetType().name(), benchmark.regionCode(), benchmark.regionName(),
                        benchmark.quintile(), benchmark.symbol(),
                        benchmark.label(), benchmark.sourceUpdatedDate()),
```

를

```java
                new Benchmark(
                        benchmark.assetType().name(), benchmark.regionCode(), benchmark.regionName(),
                        benchmark.symbol(), benchmark.label(), benchmark.sourceUpdatedDate()),
```

로 교체.

- [ ] **Step 3: 전체 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: 메인 소스는 BUILD SUCCESSFUL. 테스트 소스는 `StatsControllerTest.java`/`HousingBenchmarkComparisonResponseTest.java`가 아직 `quintile`을 참조해 컴파일 에러 — 다음 Step에서 해소.

- [ ] **Step 4: `HousingBenchmarkComparisonResponseTest` 수정**

`src/test/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponseTest.java` 전체를 다음으로 교체:

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.BenchmarkAssetType;
import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HousingBenchmarkComparisonResponseTest {
    private static final String HOUSING_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 아파트 매매가격지수는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다. "
                    + "벤치마크 시점은 KB Land 주간 조사일(매주 월요일) 기준입니다.";
    private static final String ETF_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자와 ETF 벤치마크 모두 USD 기준이며 환율 변수가 없습니다.";

    @Test
    void quality는_성과_근사치와_통화_기준을_단일_notice로_안내한다() {
        HousingBenchmarkComparison comparison = new HousingBenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.HOUSING, "1100000000", "서울",
                        null, "서울 아파트 매매가격지수", null),
                new HousingBenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_COMMON_MONTHS");

        HousingBenchmarkComparisonResponse response =
                HousingBenchmarkComparisonResponse.from(comparison);

        assertThat(response.quality().notice()).isEqualTo(HOUSING_NOTICE);
        assertThat(response.quality().benchmarkCurrency()).isEqualTo("KRW");
    }

    @Test
    void ETF_비교는_USD_통화와_전용_notice를_반환한다() {
        HousingBenchmarkComparison comparison = new HousingBenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.ETF, null, null, "SPY",
                        "SPY (SPDR S&P 500 ETF Trust)", null),
                new HousingBenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_COMMON_MONTHS");

        HousingBenchmarkComparisonResponse response =
                HousingBenchmarkComparisonResponse.from(comparison);

        assertThat(response.quality().notice()).isEqualTo(ETF_NOTICE);
        assertThat(response.quality().benchmarkCurrency()).isEqualTo("USD");
        assertThat(response.benchmark().assetType()).isEqualTo("ETF");
        assertThat(response.benchmark().symbol()).isEqualTo("SPY");
    }
}
```

- [ ] **Step 5: `StatsControllerTest` 수정**

`src/test/java/com/kista/adapter/in/web/StatsControllerTest.java`에서 아래 5개 지점을 수정한다.

5-1. `서울_아파트_벤치마크_비교를_기본값과_통화_기준으로_반환한다`(115-142번째 줄)를:

```java
    @Test
    void 아파트_벤치마크_비교를_기본값과_통화_기준으로_반환한다() throws Exception {
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", null, null))
                .thenReturn(comparison(new CurrentExchangeRate(
                        new BigDecimal("1365.20"), Instant.parse("2026-07-19T01:30:00Z"), "TOSS_INVEST")));

        mockMvc.perform(get("/api/stats/housing-benchmark").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PORTFOLIO"))
                .andExpect(jsonPath("$.benchmark.regionCode").value("1100000000"))
                .andExpect(jsonPath("$.period.pointCount").value(2))
                .andExpect(jsonPath("$.points[0].investmentIndexUsd").value(100.0))
                .andExpect(jsonPath("$.points[1].benchmarkIndex").value(110.0))
                .andExpect(jsonPath("$.points[1].investmentPeriodReturn").value(0.1))
                .andExpect(jsonPath("$.summary.investmentCumulativeReturn").value(0.1))
                .andExpect(jsonPath("$.summary.excessReturn").value(0.0))
                .andExpect(jsonPath("$.quality.method").value("ESTIMATED_TIME_WEIGHTED_RETURN"))
                .andExpect(jsonPath("$.quality.investmentCurrency").value("USD"))
                .andExpect(jsonPath("$.quality.benchmarkCurrency").value("KRW"))
                .andExpect(jsonPath("$.quality.notice").value(
                        "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 아파트 매매가격지수는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다. "
                                + "벤치마크 시점은 KB Land 주간 조사일(매주 월요일) 기준입니다."))
                .andExpect(jsonPath("$.currentExchangeRate.midRate").value(1365.2))
                .andExpect(jsonPath("$.currentExchangeRate.fetchedAt").value("2026-07-19T01:30:00Z"))
                .andExpect(jsonPath("$.currentExchangeRate.source").value("TOSS_INVEST"))
                .andExpect(jsonPath("$.emptyReason").doesNotExist());
    }
```

로 교체.

5-2. `현재_환율이_없어도_명시적_null과_함께_200을_반환한다`(144-154번째 줄)의 mock 시그니처를:

```java
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, 3, null, null))
                .thenReturn(comparison(null));
```

를

```java
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", null, null))
                .thenReturn(comparison(null));
```

로 변경.

5-3. `분위는_1부터_5까지만_허용한다`(166-178번째 줄)를 삭제하고 아래로 교체:

```java
    @Test
    void regionCode가_빈_문자열이면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("regionCode", "")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userStats);
    }
```

5-4. `소유_전략과_기간_파라미터를_서비스에_전달한다`(180-200번째 줄)를:

```java
    @Test
    void 소유_전략과_기간_파라미터를_서비스에_전달한다() throws Exception {
        UUID strategyId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2021, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 1);
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, strategyId, "2600000000", from, to))
                .thenReturn(comparison(null));

        mockMvc.perform(get("/api/stats/housing-benchmark")
                        .param("scope", "STRATEGY")
                        .param("strategyId", strategyId.toString())
                        .param("regionCode", "2600000000")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .with(authentication(auth())))
                .andExpect(status().isOk());

        verify(userStats).getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, strategyId, "2600000000", from, to);
    }
```

로 교체.

5-5. `benchmarkType_생략시_기존_HOUSING_동작을_그대로_따른다`(229-241번째 줄)를:

```java
    @Test
    void benchmarkType_생략시_기존_HOUSING_동작을_그대로_따른다() throws Exception {
        when(userStats.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", null, null))
                .thenReturn(comparison(null));

        mockMvc.perform(get("/api/stats/housing-benchmark").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benchmark.assetType").value("HOUSING"));

        verify(userStats).getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", null, null);
    }
```

로 교체.

5-6. `etfComparison()` 헬퍼(243-266번째 줄)의 `Benchmark` 생성(247-249번째 줄)을:

```java
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.ETF, null, null, null, "QLD",
                        "QLD (ProShares Ultra QQQ (2x 레버리지))", LocalDate.of(2026, 7, 18)),
```

를

```java
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.ETF, null, null, "QLD",
                        "QLD (ProShares Ultra QQQ (2x 레버리지))", LocalDate.of(2026, 7, 18)),
```

로 교체.

5-7. `comparison(...)` 헬퍼(324-347번째 줄)의 `Benchmark` 생성(328-330번째 줄)을:

```java
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.HOUSING, "1100000000", "서울", 3, null,
                        "서울 아파트 3분위", LocalDate.of(2026, 6, 15)),
```

를

```java
                new HousingBenchmarkComparison.Benchmark(
                        BenchmarkAssetType.HOUSING, "1100000000", "서울", null,
                        "서울 아파트 매매가격지수", LocalDate.of(2026, 6, 15)),
```

로 교체.

- [ ] **Step 6: 전체 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 관련 테스트 전체 실행**

```bash
docker compose up -d postgres
./gradlew test --rerun-tasks \
  --tests 'com.kista.application.service.stats.*' \
  --tests 'com.kista.adapter.in.web.StatsControllerTest' \
  --tests 'com.kista.adapter.in.web.dto.HousingBenchmarkComparisonResponseTest' \
  --tests 'com.kista.adapter.in.web.dto.HousingBenchmarkComparisonResponseSchemaTest' \
  --tests 'com.kista.adapter.out.persistence.housingbenchmark.*' \
  --tests 'com.kista.architecture.*'
```
Expected: PASS (전체). 실패 시:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
로 원인 파악 후 수정.

`HousingBenchmarkApiDocsTest`(SpringBootTest, RANDOM_PORT — DB 필요)도 함께 확인:
```bash
./gradlew test --tests '*HousingBenchmarkApiDocsTest*' --rerun-tasks
```
Expected: PASS. `quintile` 필드가 스키마에서 사라지고 `regionCode`가 그대로 있는지 실패 로그로 확인(별도 assertion 수정 불필요 — 이 테스트는 quintile을 직접 언급하지 않는다).

- [ ] **Step 8: 전체 테스트 스위트 실행 (회귀 확인)**

```bash
./gradlew test --rerun-tasks
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
Expected: 실패 0건.

- [ ] **Step 9: 문서 드리프트 확인**

`docs/agents/architecture.md`에서 `StatsController`/`StatsService` 설명에 `quintile` 언급이 있는지 `grep -n "quintile" docs/agents/architecture.md`로 확인. 있으면 `regionCode` 기준으로 갱신(현재 architecture.md의 `Stats(...)` 설명은 `benchmarkType`만 언급하고 `quintile`을 직접 언급하지 않을 가능성이 높다 — 있는 경우에만 수정).

- [ ] **Step 10: 리뷰어 서브에이전트 검수**

CLAUDE.md 규칙에 따라 커밋 전 별도 리뷰어(코드 리뷰 서브에이전트 또는 code-review 계열 skill)의 검수를 받는다. 발견된 실제 결함은 커밋 전에 수정하고 재검증(Step 7~8 재실행)한다.

- [ ] **Step 11: Task 4 + Task 5 커밋 (하나로 묶어 커밋 — Task 4만으로는 컴파일이 안 되는 중간 상태였으므로)**

```bash
git add src/main/java/com/kista/domain/model/stats/HousingBenchmarkComparison.java \
        src/main/java/com/kista/domain/port/in/UserStatsUseCase.java \
        src/main/java/com/kista/application/service/stats/StatsService.java \
        src/main/java/com/kista/adapter/in/web/StatsController.java \
        src/main/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java \
        src/test/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilderTest.java \
        src/test/java/com/kista/application/service/stats/StatsServiceTest.java \
        src/test/java/com/kista/adapter/in/web/StatsControllerTest.java \
        src/test/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponseTest.java
git commit -m "feat(stats): 아파트 벤치마크 비교를 quintile/월간에서 regionCode/주간 지수로 교체

GET /api/stats/housing-benchmark(HOUSING)이 housing_benchmark_prices(5분위,
월간) 대신 housing_price_indices(KB Land 주간 매매가격지수)를 사용한다.
투자지수는 KB 주간 조사일에 as-of 스냅되며, /housing-benchmark/regions는
주간 지수 카탈로그(25개 지역)로 전환된다. /housing-benchmark/series는
변경하지 않는다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## 최종 검증

```bash
./gradlew clean compileJava compileTestJava
./gradlew test --rerun-tasks
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

로컬 E2E (선택):
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/stats/housing-benchmark?regionCode=1100000000" | jq .
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/stats/housing-benchmark/regions" | jq '.regions | length'
# 25개 지역이 나오는지 확인 (기존 5분위는 14개)
```

## kista-ui 후속 작업 참고사항 (이번 계획 범위 밖)

- `regionCode` 파라미터로 전환됐고 기본값은 `1100000000`(서울). `quintile` 파라미터는 더 이상 API 계약에 없다.
- `/housing-benchmark/regions`가 이제 25개 지역(주간 지수 카탈로그)을 반환한다 — `/housing-benchmark/series`(5분위, 14개 지역)의 선택기와 공유하고 있었다면 별도 데이터소스가 필요할 수 있다.
- `summary.investmentAnnualizedReturn`/`benchmarkAnnualizedReturn`이 90일 미만 구간에서 `null`이 될 수 있다(ETF 포함) — 프론트 `fmtSignedPercent(null)`이 이미 `-`를 렌더하는지 확인.
- `quality.notice` 문구가 바뀌었다.
