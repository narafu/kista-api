# 아파트 벤치마크 비교를 주간 매매가격지수로 교체하는 설계

날짜: 2026-08-11
범위: `kista-api` (kista-ui 소비 작업은 별도 후속)
관련 선행 작업: `2026-07-19-seoul-housing-benchmark-comparison-design.md`(기존 월간 5분위 비교 설계), commit `39f1ed23`(KB Land 주간 아파트 매매가격지수 수집 파이프라인 — `housing_price_indices` 테이블 적재까지 완료)

## 목적

기존 아파트 벤치마크 비교(`GET /api/stats/housing-benchmark`, HOUSING)는 `housing_benchmark_prices`(5분위, 월별)를 사용해 최소 2개월치 운용 기록이 있어야 비교가 성립했다. 이미 적재 중인 `housing_price_indices`(KB Land 주간 아파트 매매가격지수, `WEEKLY_APT_SALE_PRICE_INDEX`)로 데이터 소스를 교체해 2~3주만 운용해도 비교가 성립하도록 한다. `benchmarkType=ETF` 경로와 `HousingBenchmarkComparisonBuilder`의 교집합 로직 자체는 건드리지 않는다.

## 현재 코드와의 정합성 (검증 완료)

아래 4개 지점 모두 `granularity == BenchmarkGranularity.DAILY` 조건으로 "월 단위 vs 그 외"를 분기하고 있음을 실제 소스로 확인했다:

- `HousingBenchmarkComparisonBuilder.build()`의 `computeReturn` 판정(74-75행): `granularity == DAILY || date.equals(previousDate.plusMonths(1))` — `WEEKLY`가 그대로 `else`로 떨어지면 KB 결측 주 때문에 거의 모든 포인트의 구간 수익률이 비게 된다.
- 같은 메서드의 `periodsPerYear`(91-93행): `DAILY`가 아니면 `12.0 / ChronoUnit.MONTHS.between(first, last)`. 3주 구간이면 `MONTHS.between == 0` → `Infinity` → `BigDecimal.valueOf(Infinity)`에서 예외.
- `StatsService.completedMonthEnd`(441-451행): `granularity == DAILY`일 때만 당월 clamp를 건너뛴다.
- `StatsService.buildInvestmentContext`의 `effectiveFrom` 삼항식(315-318행): `granularity == DAILY`일 때만 `from`을 그대로 쓰고, 아니면 월초로 내린다.
- `MonthlyReturnCalculator.compoundDailyReturns`(246, 259행): `granularity == DAILY`일 때만 일별 포인트를 그대로 방출하고, 아니면 월말 1포인트로 집계한다.

`HousingPriceIndexPort`는 현재 `upsertAll` 하나만 있고(`domain/port/out/HousingPriceIndexPort.java`), `HousingPriceIndexEntity.toDomain()`과 `HousingPriceIndexJpaRepository`는 이미 존재하지만 호출부가 없는 상태다(직전 커밋에서 향후 조회 경로를 위해 의도적으로 남겨둔 스캐폴드).

`HousingBenchmarkComparisonBuilder.build()`는 `investmentPoints`의 `baseDate`/`investmentIndexUsd`만 읽고 `periodReturn` 필드는 전혀 참조하지 않는다 — as-of 스냅 시 `periodReturn=null`로 채워도 안전하다는 뜻이다.

## 설계 결정

### 1. `BenchmarkGranularity`에 `WEEKLY` 추가, `DAILY` 분기를 `!= MONTHLY`로 일반화

```java
public enum BenchmarkGranularity { MONTHLY, DAILY, WEEKLY }
```

위 5개 지점의 `granularity == DAILY` 조건을 전부 `granularity != MONTHLY`로 뒤집는다. `WEEKLY` 전용 분기를 새로 추가하지 않고 기존 "월 단위 집계 vs 포인트 단위 방출" 이분법을 그대로 확장하는 방식이며, `WEEKLY`를 `DAILY`와 동일하게 처리하는 것과 결과는 같지만 변경 지점이 더 적다.

### 2. 연환산 수익률 억제 — WEEKLY·DAILY(ETF) 공통 적용

3주 구간에서 1% 상승이 연 19%로 표시되는 문제는 구조상 `MONTHLY`가 아닌 모든 granularity(WEEKLY뿐 아니라 기존 ETF의 DAILY 경로도)에 이미 잠재해 있다 — ETF 전략을 3일 전에 시작한 사용자도 동일하게 극단적인 연환산 수치를 보게 된다. `HousingBenchmarkComparisonBuilder.build()`에서 `granularity != MONTHLY && ChronoUnit.DAYS.between(firstDate, lastDate) < 90`이면 `investmentAnnualizedReturn`/`benchmarkAnnualizedReturn`을 `null`로 반환한다(사용자 확인: MONTHLY 이외 전부 적용). `periodsPerYear` 계산 자체를 건너뛰어 `annualizedReturn(...)` 호출을 하지 않는 방식으로 구현한다. 프론트의 `fmtSignedPercent(null)`이 이미 `-`를 렌더하므로 kista-ui 쪽 변경은 불필요.

### 3. 투자지수를 KB 주간 조사일에 스냅

비교 포인트 집합을 KB 주간 조사일(`housing_price_indices.base_date`, 항상 월요일)로 고정하고, 각 조사일의 투자지수는 **as-of(그 날짜 이하 가장 최근 값)**로 붙인다. 스냅은 `StatsService`에서 수행하고 빌더에는 이미 같은 날짜 키로 맞춰진 두 컬렉션을 넘긴다 — 빌더의 교집합 로직과 ETF 경로는 변경하지 않는다.

이유: 스냅 없이 정확한 날짜 일치로 교집합을 잡으면, KB 조사일(한국 월요일)과 투자 평가일이 어긋나는 날(미국 휴일과 겹치는 월요일, KB 결측 주)이 조용히 사라진다.

`StatsService.computeHousingComparisonBody` 재작성:

```java
InvestmentContext ctx = buildInvestmentContext(userId, scope, strategyId, from, to, BenchmarkGranularity.WEEKLY);
List<HousingPriceIndex> indexRows = housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
        HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, regionCode,
        ctx.effectiveFrom(), ctx.effectiveTo());
NavigableMap<LocalDate, BigDecimal> indexByDate = indexRows.stream()
        .collect(Collectors.toMap(HousingPriceIndex::baseDate, HousingPriceIndex::indexValue,
                (l, r) -> r, TreeMap::new));

NavigableMap<LocalDate, InvestmentPoint> investmentByDate = ctx.investmentPoints().stream()
        .collect(Collectors.toMap(InvestmentPoint::baseDate, Function.identity(), (l, r) -> r, TreeMap::new));

List<InvestmentPoint> snappedPoints = new ArrayList<>();
for (LocalDate surveyDate : indexByDate.keySet()) {
    var asOf = investmentByDate.floorEntry(surveyDate);
    if (asOf == null) continue; // 투자 시작 전 조사일은 스킵
    snappedPoints.add(new InvestmentPoint(surveyDate, asOf.getValue().investmentIndexUsd(), null));
}

String regionName = indexRows.stream().findFirst().map(HousingPriceIndex::regionName).orElse(null);
LocalDate sourceUpdatedDate = indexRows.stream().map(HousingPriceIndex::sourceUpdatedDate)
        .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
        BenchmarkAssetType.HOUSING, regionCode, regionName, null,
        (regionName != null ? regionName : regionCode) + " 아파트 매매가격지수", sourceUpdatedDate);

return comparisonBuilder.build(scope, ctx.selectedStrategy(), benchmark, snappedPoints, indexByDate, BenchmarkGranularity.WEEKLY);
```

- `effectiveFrom`/`effectiveTo`에 별도 lookback 패딩(기존 월간 경로의 `.minusMonths(1).withDayOfMonth(1)`)이 필요 없다 — `WEEKLY`는 `buildInvestmentContext`에서 `effectiveFrom`을 정확한 날짜로 유지하고(변경 1), 조사일 순회 시 투자 시작 전 날짜는 `floorEntry == null`로 자연 스킵되기 때문이다.
- `regionName`은 더 이상 상수(`SEOUL_REGION_NAME`)가 아니라 조회된 행에서 동적으로 채운다 — `regionCode`가 이제 호출자 파라미터이기 때문.

### 4. API 계약 변경

- `HousingPriceIndexPort`에 다음 2개 메서드 추가 (기존 `HousingBenchmarkPricePort` 패턴 그대로):
  ```java
  List<HousingPriceIndex> findByMetricCodeAndRegionCodeAndBaseDateBetween(
          String metricCode, String regionCode, LocalDate from, LocalDate to);
  List<HousingBenchmarkRegion> findDistinctRegions(String metricCode);
  ```
  `findDistinctRegions`는 `HousingPriceIndexJpaRepository`에 `HousingBenchmarkPriceJpaRepository.findDistinctRegionsByMetricCode`와 동일한 `@Query`(distinct regionCode/regionName, order by regionCode)로 추가하고, `HousingPriceIndexPersistenceAdapter`에 구현을 추가한다.
- **`GET /housing-benchmark/regions`가 새 카탈로그로 전환**: `StatsService.getHousingBenchmarkRegions()`가 `housingBenchmarkPricePort.findDistinctRegions(APT_QTE_SALE_PRICE)`(14개 지역) 대신 `housingPriceIndexPort.findDistinctRegions(WEEKLY_APT_SALE_PRICE_INDEX)`(25개 지역)를 호출하도록 교체한다. 비교 가능한 지역 전체를 선택기에 노출하기 위함.
  - **알려진 부작용**: `GET /housing-benchmark/series`는 변경 없이 5분위 테이블(14개 지역)을 그대로 쓴다. `/regions`가 이제 25개 지역을 반환하므로, 만약 kista-ui가 동일한 `/regions` 응답으로 `/series`의 지역 선택기까지 겸용하고 있었다면 5분위 테이블에 없는 지역 11개를 선택 시 `/series`가 빈 결과를 반환한다(400 아님, 기존 emptyReason 패턴과 동일하게 비파괴적). kista-ui 후속 작업 시 이 두 엔드포인트가 지역 선택기를 공유하는지 확인 필요 — 공유한다면 `/series`용 선택기는 별도 데이터소스가 필요할 수 있음.
- `HousingBenchmarkComparison.Benchmark`에서 `Integer quintile` 필드 제거. `regionCode`/`regionName`은 이미 범용 필드라 그대로 유지.
- `StatsService.BenchmarkComparisonKey`: `Integer quintile` → `String regionCode`.
- `UserStatsUseCase`/`StatsService.getHousingBenchmarkComparison`: `int quintile` → `String regionCode`.
- `validateComparisonRequest`에서 quintile 1~5 검증 제거, `regionCode` blank 검증으로 교체. DB 화이트리스트 대조는 하지 않는다 — 잘못된 코드는 `indexByDate`가 빈 채로 나와 기존 `NO_INVESTMENT_DATA`/`INSUFFICIENT_COMMON_MONTHS` emptyReason 경로로 자연 처리된다.
- `selectQuintilePrice`는 호출자가 없어져 삭제.
- `StatsController`: `@RequestParam(defaultValue = "3") int quintile` + 1~5 검증 블록을 `@RequestParam(defaultValue = "1100000000") String regionCode`로 교체. 구 프론트가 여전히 `quintile=3`을 보내도 Spring이 미선언 쿼리 파라미터를 기본적으로 무시하므로 400이 나지 않는다 — 구현 시 이 프로젝트에 별도 strict binding 설정이 없는지 재확인한다.
- `HousingBenchmarkComparisonResponse`: `Benchmark.quintile` 필드 제거, `HOUSING_NOTICE`를 다음으로 갱신:
  > "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 아파트 매매가격지수는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다. 벤치마크 시점은 KB Land 주간 조사일(매주 월요일) 기준입니다."
- `HousingBenchmarkOpenApiCustomizer`는 `strategy`/`summary`/`currentExchangeRate` nullable union만 다루므로 변경 없음. `HousingBenchmarkComparisonResponseSchemaTest`/`HousingBenchmarkApiDocsTest`는 `quintile` 필드 제거에 따른 스키마 변화만 갱신.
- `GET /housing-benchmark/series`는 변경하지 않는다(5분위 테이블 그대로).

## 범위 밖

- kista-ui 소비 작업(타입 재생성, `/benchmark` 아파트 탭 UI) — API 계약이 확정된 뒤 별도 세션.
- `BenchmarkGranularity.MONTHLY`의 호출자가 이 교체로 0이 된다(HOUSING→WEEKLY, ETF→DAILY). `MonthlyReturnCalculator`의 월별 집계 경로와 그 테스트가 도달 불가가 되지만, 이번 작업에서는 삭제하지 않고 별도 리팩토링으로 제안한다.
- `/housing-benchmark/series`의 지역 선택기 데이터소스 정합성(위 "알려진 부작용" 참고)은 kista-ui 후속 작업에서 실제 사용 여부 확인 후 필요 시 별도로 다룬다.

## 테스트

- 신규 `HousingBenchmarkComparisonBuilderTest`(현재 존재하지 않음) — as-of 스냅 없이 빌더 자체의 단위 테스트로: WEEKLY computeReturn이 매 포인트마다 계산되는지, 90일 미만 구간에서 annualizedReturn이 null인지(WEEKLY·DAILY 둘 다), 90일 이상 구간은 유한한 값인지, 3주(2포인트) 구간에서 예외 없이 결과가 나오는지(회귀 방지, 과거 `MONTHS.between==0` 예외 케이스).
- `MonthlyReturnCalculatorTest`에 WEEKLY 케이스 추가 — WEEKLY가 DAILY와 동일하게 일별 포인트를 그대로 방출하는지.
- `StatsServiceTest` — as-of 스냅(조사일에 정확히 일치하는 투자 포인트가 없어도 직전 값으로 포인트가 생성됨, 투자 시작 전 조사일은 스킵됨), quintile 관련 기존 케이스를 regionCode/WEEKLY로 교체, `/regions`가 새 포트를 호출하는지.
- `StatsControllerTest` — quintile 1~5 검증 테스트 제거, `regionCode` 파라미터·기본값 테스트 추가, quintile을 여전히 보내도 400이 아닌지 확인.
- `HousingBenchmarkComparisonResponseTest`, `HousingBenchmarkComparisonResponseSchemaTest`, `HousingBenchmarkApiDocsTest` — quintile 필드 제거·notice 문구 갱신 반영.
- `HousingPriceIndexPersistenceAdapterTest` — 신규 `findByMetricCodeAndRegionCodeAndBaseDateBetween`/`findDistinctRegions` 조회 테스트 추가.
