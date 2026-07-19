# Seoul Housing Benchmark Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a statistics-page benchmark that compares KRW-adjusted portfolio or strategy performance with Seoul apartment quintile price growth.

**Architecture:** The API stores monthly Toss Invest USD/KRW mid-rates, calculates cash-flow-adjusted USD returns from cycle snapshots, converts the monthly index to KRW, and aligns it with persisted KB Land prices. The UI consumes the server-calculated indices and metrics in a lazy-loaded benchmark tab without recomputing financial results.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, Flyway, RestTemplate, JUnit 5, Mockito, Next.js 16, React 19, TanStack Query, Recharts, Vitest, Playwright.

## Global Constraints

- Work in isolated worktrees created with `superpowers:using-git-worktrees`; use paired API/UI feature branches.
- Use TDD for every behavioral task and run the focused failing test before implementation.
- The benchmark defaults are portfolio scope, Seoul third quintile, and five years.
- Investment results are KRW based using monthly USD/KRW `midRate`; never use the customer buy rate.
- The comparison endpoint reads persisted exchange rates only and never calls Toss Invest.
- Ratios in API responses are decimal fractions (`0.325`), not display percentages.
- Keep domain records free of Spring and JPA annotations.
- All output ports end in `Port`; Spring Data interfaces end in `JpaRepository` and remain package-private.
- Flyway migrations are append-only; implementation uses `V29` only after confirming no newer migration exists in the worktree.
- UI clients call the Next.js route handler, never `kista-api` directly.
- UI server state remains in React Query; do not copy it into `useState`.
- Do not add SPY/QQQ, dashboard cards, win counts, currency toggles, or an external-flow ledger in this scope.

---

### Task 1: Verify Toss historical exchange-rate behavior

**Files:**
- Create: `src/test/java/com/kista/adapter/out/toss/TossHistoricalExchangeRateApiIT.java`
- Modify: none

**Interfaces:**
- Consumes: existing `TossHttpClient.getNoAccountHeader(String, Account, MultiValueMap, ParameterizedTypeReference)`, `AccountPort`, and external local Spring configuration.
- Produces: verified request/response facts for `GET /api/v1/exchange-rate`: required-intersection `dateTime`, `rate`, `midRate`, omitted response `dateTime`, and weekend behavior.

- [ ] **Step 1: Add an opt-in integration probe**

Create an `@Tag("integration")` test that loads one owned Toss account through `AccountPort` and calls the three required business-day dates through `getNoAccountHeader()`. This account OAuth path is only for live endpoint and response-semantic verification; never print account credentials or access tokens. Retain the previously recorded weekend result in the contract report.

```java
@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
class TossHistoricalExchangeRateApiIT {
    @Autowired TossHttpClient client;
    @Autowired AccountPort accountPort;

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-06-16T16:00:00+09:00",
            "2026-06-23T16:00:00+09:00",
            "2026-06-30T16:00:00+09:00"
    })
    void historicalMidRateIsAvailable(String dateTime) {
        ExchangeRateResult result = request(dateTime);
        assertThat(result.midRate()).isPositive();
    }
}
```

- [ ] **Step 2: Run the probe against the local profile**

Run:

```bash
SPRING_CONFIG_ADDITIONAL_LOCATION='file:/Users/phs/workspace/kista/kista-api/src/main/resources/application-local.yml' \
./gradlew integration --tests 'com.kista.adapter.out.toss.TossHistoricalExchangeRateApiIT'
```

Expected: the three business-day requests in the earliest strategy month return positive `midRate` values and omit response `dateTime`. The successful requested date is the effective `exchangeRateDate`; it is not a claimed underlying market observation date. Verify and backfill only from the earliest strategy month across the actual intersection with KB data. If Toss lacks any month in that required intersection, stop implementation and select a different historical FX source. The completed spike also established that 2020 returns `exchange-rate-not-found`, but 2020 is outside the actual required intersection and does not block implementation. Do not search for a globally oldest supported date.

Local administrator credentials are absent, so this live probe does not verify the production authentication path. Task 3's `TossHistoricalExchangeRateAdapterTest` must verify that production uses administrator-token `getCommon()` with the same endpoint and query contract.

- [ ] **Step 3: Remove the live probe after recording the contract**

Delete `TossHistoricalExchangeRateApiIT.java`; live credentials and external availability must not become a permanent build dependency. Preserve the verified behavior in adapter unit tests in Task 3.

- [ ] **Step 4: Commit the verified design adjustment only if required**

If the effective request date or fallback differs from the design, patch the design and plan, then commit:

```bash
git add docs/superpowers/specs/2026-07-19-seoul-housing-benchmark-comparison-design.md docs/superpowers/plans/2026-07-19-seoul-housing-benchmark-comparison.md
git commit -m "docs: 토스 과거 환율 조회 조건 반영"
```

If the contract matches, proceed without an empty commit.

---

### Task 2: Persist monthly exchange rates

**Files:**
- Create: `src/main/resources/db/migration/V29__create_monthly_exchange_rates.sql`
- Create: `src/main/java/com/kista/domain/model/market/MonthlyExchangeRate.java`
- Create: `src/main/java/com/kista/domain/port/out/MonthlyExchangeRatePort.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/exchangerate/MonthlyExchangeRateEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/exchangerate/MonthlyExchangeRateJpaRepository.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/exchangerate/MonthlyExchangeRatePersistenceAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/persistence/exchangerate/MonthlyExchangeRatePersistenceAdapterTest.java`

**Interfaces:**
- Produces: `MonthlyExchangeRatePort.upsert(MonthlyExchangeRate)` and `findByCurrenciesAndBaseMonthBetween(String, String, LocalDate, LocalDate)`.

- [ ] **Step 1: Load the Flyway skill and confirm the migration number**

Read `.agents/skills/flyway-migration/SKILL.md`, then run:

```bash
ls src/main/resources/db/migration | sort -V | tail
```

Expected: `V28__shift_orders_trade_date_to_kst.sql` is latest. If another migration exists, use the next free version and update this plan filename reference before editing.

- [ ] **Step 2: Write the failing persistence test**

Cover insert, natural-key update, ordered range lookup, and exclusion of other currency pairs.

```java
MonthlyExchangeRate rate = new MonthlyExchangeRate(
        null, "TOSS_INVEST", "USD", "KRW",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
        new BigDecimal("1365.200000"), Instant.parse("2026-06-30T07:00:00Z"));
adapter.upsert(rate);
assertThat(adapter.findByCurrenciesAndBaseMonthBetween(
        "USD", "KRW", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1)))
        .singleElement().extracting(MonthlyExchangeRate::midRate)
        .isEqualByComparingTo("1365.200000");
```

- [ ] **Step 3: Run the persistence test and verify failure**

```bash
./gradlew test --tests 'com.kista.adapter.out.persistence.exchangerate.MonthlyExchangeRatePersistenceAdapterTest'
```

Expected: compilation fails because the exchange-rate types do not exist.

- [ ] **Step 4: Add migration and persistence implementation**

Use the exact natural key and named constraint:

```sql
CREATE TABLE monthly_exchange_rates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(20) NOT NULL,
    base_currency VARCHAR(3) NOT NULL,
    quote_currency VARCHAR(3) NOT NULL,
    base_month DATE NOT NULL,
    exchange_rate_date DATE NOT NULL,
    mid_rate NUMERIC(18,6) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_monthly_exchange_rates_source_pair_month
        UNIQUE (source, base_currency, quote_currency, base_month)
);

CREATE INDEX idx_monthly_exchange_rates_pair_month
    ON monthly_exchange_rates (base_currency, quote_currency, base_month);
```

The adapter uses `INSERT ... ON CONFLICT ... DO UPDATE`, passes `Timestamp.from(fetchedAt)`, and orders reads by `baseMonth ASC`.

- [ ] **Step 5: Run focused persistence and architecture tests**

```bash
./gradlew test --tests 'com.kista.adapter.out.persistence.exchangerate.MonthlyExchangeRatePersistenceAdapterTest' --tests 'com.kista.architecture.*'
```

Expected: both suites pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V29__create_monthly_exchange_rates.sql src/main/java/com/kista/domain/model/market/MonthlyExchangeRate.java src/main/java/com/kista/domain/port/out/MonthlyExchangeRatePort.java src/main/java/com/kista/adapter/out/persistence/exchangerate src/test/java/com/kista/adapter/out/persistence/exchangerate
git commit -m "feat(stats): 월별 환율 저장소 추가"
```

---

### Task 3: Fetch and backfill Toss monthly exchange rates

**Files:**
- Create: `src/main/java/com/kista/domain/port/out/HistoricalExchangeRateFeedPort.java`
- Create: `src/main/java/com/kista/domain/port/in/BackfillMonthlyExchangeRateUseCase.java`
- Create: `src/main/java/com/kista/adapter/out/toss/TossHistoricalExchangeRateAdapter.java`
- Create: `src/main/java/com/kista/application/service/market/MonthlyExchangeRateService.java`
- Create: `src/test/java/com/kista/adapter/out/toss/TossHistoricalExchangeRateAdapterTest.java`
- Create: `src/test/java/com/kista/application/service/market/MonthlyExchangeRateServiceTest.java`
- Modify: `src/main/java/com/kista/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java`
- Modify: `src/test/java/com/kista/adapter/in/schedule/KbLandHousingBenchmarkSchedulerTest.java`

**Interfaces:**
- Consumes: `MonthlyExchangeRatePort`, `TossHttpClient`, `FetchHousingBenchmarkUseCase`.
- Produces: `HistoricalExchangeRateFeedPort.fetchUsdKrw(LocalDate requestedDate)` and `BackfillMonthlyExchangeRateUseCase.backfill(LocalDate fromMonth, LocalDate toMonth)`.

- [ ] **Step 1: Write failing adapter tests**

Use `MockRestServiceServer` or mock `TossHttpClient` following existing Toss adapter tests. Assert query values and response mapping:

```java
MonthlyExchangeRate result = adapter.fetchUsdKrw(LocalDate.of(2026, 6, 30));
assertThat(result.baseCurrency()).isEqualTo("USD");
assertThat(result.quoteCurrency()).isEqualTo("KRW");
assertThat(result.midRate()).isEqualByComparingTo("1365.20");
assertThat(result.exchangeRateDate()).isEqualTo(LocalDate.of(2026, 6, 30));
verify(client).getCommon(eq("/api/v1/exchange-rate"), argThat(q ->
        q.getFirst("dateTime").startsWith("2026-06-30")
                && q.getFirst("baseCurrency").equals("USD")
                && q.getFirst("quoteCurrency").equals("KRW")), any());
```

- [ ] **Step 2: Write failing service tests**

Cover persisted-month skip, last calendar day success, weekend fallback one day at a time up to seven days, one failed month not aborting the rest, and rerun filling only missing months.

- [ ] **Step 3: Run tests and verify failure**

```bash
./gradlew test --tests 'com.kista.adapter.out.toss.TossHistoricalExchangeRateAdapterTest' --tests 'com.kista.application.service.market.MonthlyExchangeRateServiceTest'
```

Expected: compilation failure for missing ports and implementations.

- [ ] **Step 4: Implement adapter and backfill service**

The service iterates `YearMonth` inclusively and uses this fallback contract:

```java
private MonthlyExchangeRate fetchMonth(YearMonth month) {
    LocalDate requested = month.atEndOfMonth();
    for (int offset = 0; offset <= 7; offset++) {
        try {
            return feedPort.fetchUsdKrw(requested.minusDays(offset));
        } catch (TossApiException e) {
            if (offset == 7) throw e;
        }
    }
    throw new IllegalStateException("unreachable");
}
```

Save `baseMonth=month.atDay(1)`, set `exchangeRateDate` to the successful requested date, and notify errors through `NotifyPort` while continuing other months. Toss does not return an underlying market observation date, so do not infer or store one.

- [ ] **Step 5: Extend the KB scheduler sequence**

After KB collection succeeds, request exchange-rate backfill for the current KST month only. Keep the existing lock and 10th/20th schedule. Verify the scheduler test asserts KB fetch before FX fetch.

- [ ] **Step 6: Run focused tests and compile**

```bash
./gradlew compileJava test --tests 'com.kista.adapter.out.toss.TossHistoricalExchangeRateAdapterTest' --tests 'com.kista.application.service.market.MonthlyExchangeRateServiceTest' --tests 'com.kista.adapter.in.schedule.KbLandHousingBenchmarkSchedulerTest'
```

Expected: build succeeds.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kista/domain/port/out/HistoricalExchangeRateFeedPort.java src/main/java/com/kista/domain/port/in/BackfillMonthlyExchangeRateUseCase.java src/main/java/com/kista/adapter/out/toss/TossHistoricalExchangeRateAdapter.java src/main/java/com/kista/application/service/market/MonthlyExchangeRateService.java src/main/java/com/kista/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java src/test/java/com/kista/adapter/out/toss/TossHistoricalExchangeRateAdapterTest.java src/test/java/com/kista/application/service/market/MonthlyExchangeRateServiceTest.java src/test/java/com/kista/adapter/in/schedule/KbLandHousingBenchmarkSchedulerTest.java
git commit -m "feat(stats): 토스 월별 환율 수집 추가"
```

---

### Task 4: Calculate cash-flow-adjusted monthly KRW performance

**Files:**
- Create: `src/main/java/com/kista/domain/model/stats/MonthlyInvestmentPoint.java`
- Create: `src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java`
- Create: `src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java`

**Interfaces:**
- Produces: `List<MonthlyInvestmentPoint> calculate(List<StrategyCycle> cycles, List<CyclePosition> positions, List<MonthlyExchangeRate> rates, LocalDate from, LocalDate to)`.

- [ ] **Step 1: Write failing calculator tests**

Create fixtures for these exact behaviors: initial contribution starts at index 100; a 100-to-110 gain yields 10%; a new 50 contribution does not add return; 110 end to 110 next-cycle start is zero flow; 110 end to 90 next-cycle start records a 20 withdrawal; multiple strategies aggregate before return calculation; FX 1,300 to 1,430 turns a flat USD index into a 10% KRW gain; monthly MDD uses month-end indices.

- [ ] **Step 2: Run the calculator test and verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.stats.MonthlyReturnCalculatorTest'
```

Expected: compilation failure because `MonthlyReturnCalculator` does not exist.

- [ ] **Step 3: Implement the pure calculator**

Use `BigDecimal` with scale 10 for intermediate returns and `HALF_UP`. Split private responsibilities into focused methods:

```java
List<DailyValuation> buildDailyValuations(...);
Map<LocalDate, BigDecimal> buildExternalFlows(...);
List<MonthlyUsdIndex> compoundDailyReturns(...);
List<MonthlyInvestmentPoint> applyMonthlyFx(...);
```

Do not access ports or current time in this class. Omit a day when `previousValue + flow <= 0`; omit a month without both a USD index and FX rate.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests 'com.kista.application.service.stats.MonthlyReturnCalculatorTest'
```

Expected: all calculator cases pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kista/domain/model/stats/MonthlyInvestmentPoint.java src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java
git commit -m "feat(stats): 월별 원화 투자수익률 계산 추가"
```

---

### Task 5: Expose the housing benchmark comparison API

**Files:**
- Create: `src/main/java/com/kista/domain/model/stats/HousingBenchmarkComparison.java`
- Create: `src/main/java/com/kista/domain/model/stats/HousingBenchmarkPoint.java`
- Create: `src/main/java/com/kista/domain/model/stats/PerformanceComparisonSummary.java`
- Create: `src/main/java/com/kista/domain/model/stats/BenchmarkScope.java`
- Create: `src/main/java/com/kista/application/service/stats/HousingBenchmarkComparisonBuilder.java`
- Create: `src/main/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java`
- Modify: `src/main/java/com/kista/domain/port/in/UserStatsUseCase.java`
- Modify: `src/main/java/com/kista/application/service/stats/StatsService.java`
- Modify: `src/main/java/com/kista/domain/port/out/CyclePositionPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/adapter/in/web/StatsController.java`
- Modify: `src/test/java/com/kista/application/service/stats/StatsServiceTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/StatsControllerTest.java`

**Interfaces:**
- Produces: `UserStatsUseCase.getHousingBenchmarkComparison(UUID, BenchmarkScope, UUID, int, LocalDate, LocalDate)` and `GET /api/stats/housing-benchmark`.

- [ ] **Step 1: Write failing service tests**

Assert default Seoul region and metric, quintile-to-column mapping for all five values, portfolio scope, owned strategy scope, foreign strategy rejection, first-common-month normalization, cumulative return, annualized return, MDD, and the two empty reasons `NO_INVESTMENT_DATA` and `INSUFFICIENT_COMMON_MONTHS`.

- [ ] **Step 2: Write failing controller tests**

```java
mockMvc.perform(get("/api/stats/housing-benchmark")
        .principal(auth()))
        .andExpect(status().isOk());
verify(userStats).getHousingBenchmarkComparison(
        USER_ID, BenchmarkScope.PORTFOLIO, null, 3, null, null);
```

Also cover `scope=STRATEGY` without `strategyId` as 400, quintile 0/6 as 400, and DTO decimal values.

- [ ] **Step 3: Run tests and verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.stats.StatsServiceTest' --tests 'com.kista.adapter.in.web.StatsControllerTest'
```

Expected: compilation failure for the new method and DTO.

- [ ] **Step 4: Implement domain models and comparison builder**

The builder intersects by `baseMonth`, resets both first points to 100, and calculates:

```java
BigDecimal excessReturn = investmentCumulativeReturn.subtract(benchmarkCumulativeReturn);
BigDecimal annualized = BigDecimal.valueOf(
        Math.pow(lastIndex.divide(HUNDRED, 10, HALF_UP).doubleValue(), 12.0 / elapsedMonths) - 1.0);
```

Return decimal fractions in summary while chart indices remain around 100.

- [ ] **Step 5: Implement service orchestration and query extension**

Validate scope and ownership before reading positions. Fetch from `Instant.EPOCH` through the requested end so carry-forward and the preceding benchmark month are available. Select the quintile with an explicit switch expression; do not use reflection.

- [ ] **Step 6: Implement controller and response DTO**

Add `@RequestParam(defaultValue = "PORTFOLIO") BenchmarkScope scope`, optional `strategyId`, default quintile 3, and ISO dates. Response `from()` performs mapping only.

- [ ] **Step 7: Run API tests and architecture rules**

```bash
./gradlew compileJava test --tests 'com.kista.application.service.stats.*' --tests 'com.kista.adapter.in.web.StatsControllerTest' --tests 'com.kista.architecture.*'
```

Expected: build succeeds.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/kista/domain/model/stats src/main/java/com/kista/domain/port/in/UserStatsUseCase.java src/main/java/com/kista/domain/port/out/CyclePositionPort.java src/main/java/com/kista/application/service/stats src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java src/main/java/com/kista/adapter/in/web/StatsController.java src/main/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java src/test/java/com/kista/application/service/stats src/test/java/com/kista/adapter/in/web/StatsControllerTest.java
git commit -m "feat(stats): 서울 아파트 벤치마크 비교 API 추가"
```

---

### Task 6: Backfill exchange rates and verify local API data

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/AdminSchedulerController.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AdminSchedulerControllerTest.java`

**Interfaces:**
- Consumes: `BackfillMonthlyExchangeRateUseCase`.
- Produces: persisted monthly USD/KRW rows covering the intersection of local investment and KB data.

- [ ] **Step 1: Determine the local required range with read-only SQL**

Query the earliest KST strategy date and the KB month bounds using the running PostgreSQL container. Derive the backfill range as the actual month intersection beginning with the earliest strategy month; currently the earliest strategy date is 2026-06-16, so the first candidate month is 2026-06. Do not print credentials. Before backfill, verify that Toss can supply every month in this derived range; if any required month is unavailable, stop and select another source.

- [ ] **Step 2: Add a protected manual backfill trigger**

Add `POST /api/admin/scheduler/exchange-rate-backfill?fromMonth=YYYY-MM-01&toMonth=YYYY-MM-01` following the existing virtual-thread scheduler trigger pattern. Require ADMIN authorization, return 202 after starting the job, and validate `fromMonth <= toMonth` before starting it. Add controller slice tests for ADMIN 202, USER 403, and invalid range 400.

- [ ] **Step 3: Run the backfill locally**

Invoke the local authenticated admin endpoint and poll the DB until the expected range is complete or an error is logged. Expected: one row per month, with positive `mid_rate`, no duplicate natural keys, and `exchange_rate_date` no more than seven days before month end.

- [ ] **Step 4: Verify stored coverage**

Run SQL assertions for count, min/max month, duplicate natural keys, nonpositive rates, and missing months in the required range. Expected: no duplicates, no nonpositive rates, and no missing month used by the comparison.

- [ ] **Step 5: Smoke-test the comparison endpoint**

Call portfolio/third-quintile and one owned strategy. Expected: HTTP 200, first indices equal 100, `currencyBasis=KRW`, ascending months, and summary values consistent with the last indices.

- [ ] **Step 6: Commit the admin trigger**

```bash
git add src/main/java/com/kista/adapter/in/web/AdminSchedulerController.java src/test/java/com/kista/adapter/in/web/AdminSchedulerControllerTest.java
git commit -m "feat(admin): 월별 환율 백필 실행 추가"
```

---

### Task 7: Add the UI stats data contract and query hook

**Files (in `kista-ui` worktree):**
- Modify: `entities/stats/model/types.ts`
- Modify: `entities/stats/api/index.ts`
- Modify: `entities/stats/api/index.test.ts`
- Modify: `entities/stats/hooks/useStatsQueries.ts`
- Create: `entities/stats/hooks/useHousingBenchmarkQuery.test.tsx`
- Modify: `entities/stats/index.ts`
- Modify: `app/api/stats/[[...path]]/route.test.ts`
- Regenerate: `openapi.json`
- Regenerate: `shared/lib/api-types.ts`

**Interfaces:**
- Consumes: `GET /api/stats/housing-benchmark` from Task 5.
- Produces: `getHousingBenchmarkComparison(params, token?)` and `useHousingBenchmarkQuery(params, enabled)`.

- [ ] **Step 1: Refresh OpenAPI and generated types**

With the API running locally:

```bash
npm run fetch:spec
npm run gen:types
```

Expected: generated schema contains `/api/stats/housing-benchmark` and its response.

- [ ] **Step 2: Write failing API and hook tests**

Assert all parameters reach the route and query key:

```ts
expect(queryKey).toEqual([
  'housingBenchmark', 'PORTFOLIO', null, 3, '2021-07-01', '2026-07-01'
])
```

Assert `enabled=false` performs no request and `placeholderData` keeps previous chart data during filter changes.

- [ ] **Step 3: Run tests and verify failure**

```bash
npm run test:run -- entities/stats/api/index.test.ts entities/stats/hooks/useHousingBenchmarkQuery.test.tsx app/api/stats/[[...path]]/route.test.ts
```

Expected: tests fail because the API function and hook do not exist.

- [ ] **Step 4: Implement types, API function, hook, and exports**

Use these parameter types:

```ts
export interface HousingBenchmarkParams {
  scope: 'PORTFOLIO' | 'STRATEGY'
  strategyId?: string
  quintile: 1 | 2 | 3 | 4 | 5
  from?: string
  to?: string
}
```

The hook query key includes every parameter and uses `placeholderData: (previous) => previous`.

- [ ] **Step 5: Run tests and typecheck**

```bash
npm run test:run -- entities/stats/api/index.test.ts entities/stats/hooks/useHousingBenchmarkQuery.test.tsx app/api/stats/[[...path]]/route.test.ts
npm run typecheck
```

Expected: tests and typecheck pass.

- [ ] **Step 6: Commit in the UI repository**

```bash
git add openapi.json shared/lib/api-types.ts entities/stats app/api/stats/[[...path]]/route.test.ts
git commit -m "feat(stats): 주택 벤치마크 조회 계층 추가"
```

---

### Task 8: Build the benchmark comparison tab

**Files (in `kista-ui` worktree):**
- Create: `widgets/stats-overview/HousingBenchmarkComparison.tsx`
- Create: `widgets/stats-overview/HousingBenchmarkChart.tsx`
- Create: `widgets/stats-overview/HousingBenchmarkSummary.tsx`
- Create: `widgets/stats-overview/HousingBenchmarkInfo.tsx`
- Create: `widgets/stats-overview/housingBenchmarkContent.ts`
- Create: `widgets/stats-overview/HousingBenchmarkComparison.test.tsx`
- Modify: `widgets/stats-overview/StatsOverview.tsx`
- Modify: `widgets/stats-overview/StatsOverview.test.tsx`

**Interfaces:**
- Consumes: `useHousingBenchmarkQuery`, existing strategy/account entity queries, API indices and summary.
- Produces: `HousingBenchmarkComparison` lazy tab matching the approved mockup.

- [ ] **Step 1: Write failing widget tests**

Cover tab labels, default portfolio/third-quintile/five-year query, lazy query only after opening the tab, strategy selector visibility, filter changes, summary formatting, empty reason copy, section-only error, source update date, KRW notice, and quintile disclaimer.

- [ ] **Step 2: Run tests and verify failure**

```bash
npm run test:run -- widgets/stats-overview/HousingBenchmarkComparison.test.tsx widgets/stats-overview/StatsOverview.test.tsx
```

Expected: tests fail because the new components and tab do not exist.

- [ ] **Step 3: Add tab state and benchmark composition**

Use an accessible two-button segmented control with `aria-pressed`. Preserve the current operational stats tree unchanged behind `activeTab === 'OPERATIONS'`. Enable the benchmark query only after `activeTab === 'BENCHMARK'`.

- [ ] **Step 4: Implement chart and summary**

Use Recharts with two monthly lines:

```tsx
<Line dataKey="investmentIndexKrw" name={investmentLabel} stroke="var(--chart-1)" dot={false} />
<Line dataKey="benchmarkIndex" name={benchmark.label} stroke="var(--chart-3)" dot={false} />
```

The tooltip shows month, both indices, both monthly returns, USD/KRW mid-rate, and its observed date. Do not compute cumulative results in the browser.

- [ ] **Step 5: Add quintile content and disclaimer**

Create typed entries for all five quintiles with range label, representative areas, characteristics, and examples supplied by the user. Prefix examples with `해당 가격대에서 자주 언급되는 지역·단지 예시` and always render the non-fixed-membership disclaimer from the design.

- [ ] **Step 6: Implement responsive layout**

At mobile widths, stack selectors, render excess performance full-width above two comparison values, retain a three-column metrics table without horizontal scrolling, and keep chart height at least 240px. Use existing cards, tokens, and toggle patterns; do not add nested cards.

- [ ] **Step 7: Run focused tests and typecheck**

```bash
npm run test:run -- widgets/stats-overview/HousingBenchmarkComparison.test.tsx widgets/stats-overview/StatsOverview.test.tsx
npm run typecheck
```

Expected: tests and typecheck pass.

- [ ] **Step 8: Commit in the UI repository**

```bash
git add widgets/stats-overview
git commit -m "feat(stats): 서울 아파트 벤치마크 비교 화면 추가"
```

---

### Task 9: End-to-end verification and integration

**Files:**
- Modify: API/UI tests that reproduce any defect found during verification before changing production code.
- Update: `docs/agents/architecture.md` and `docs/agents/workflow.md` only through the project doc-sync process after implementation commits.

**Interfaces:**
- Consumes: completed API and UI feature branches.
- Produces: verified desktop/mobile feature and merge-ready commits.

- [ ] **Step 1: Run full focused API verification**

```bash
./gradlew compileJava test --tests 'com.kista.application.service.stats.*' --tests 'com.kista.application.service.market.*' --tests 'com.kista.adapter.out.toss.TossHistoricalExchangeRateAdapterTest' --tests 'com.kista.adapter.out.persistence.exchangerate.*' --tests 'com.kista.adapter.in.web.StatsControllerTest' --tests 'com.kista.adapter.in.schedule.KbLandHousingBenchmarkSchedulerTest' --tests 'com.kista.architecture.*'
```

Expected: build succeeds with zero failed tests.

- [ ] **Step 2: Run UI verification**

```bash
npm run test:run -- entities/stats widgets/stats-overview app/api/stats
npm run typecheck
npm run build
```

Expected: all tests, typecheck, and production build pass.

- [ ] **Step 3: Start both local servers**

API:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

UI:

```bash
npm run dev
```

Use alternate ports if 8080 or 3000 is occupied and report the actual URLs.

- [ ] **Step 4: Verify with Playwright at desktop and mobile**

Capture `/stats` at 1440x900 and 375x812. Verify no overlap or horizontal overflow; controls remain usable; chart is nonblank; portfolio/strategy, quintile, and period controls update the series; dark and light themes retain contrast.

- [ ] **Step 5: Verify calculations against SQL samples**

Pick at least three months and independently calculate apartment return, FX ratio, and KRW investment index from stored rows. Expected: API values match within the documented BigDecimal rounding tolerance.

- [ ] **Step 6: Run doc-sync and inspect both worktrees**

Ensure shared docs describe only implemented behavior. Run `git diff --check` and `git status --short` in both repositories. Expected: no whitespace errors and no unintended files.

- [ ] **Step 7: Use `superpowers:requesting-code-review`**

Request review of calculation correctness, ownership/security, migration/entity parity, API/UI contract, and missing tests. Address verified findings with focused tests and commits.

- [ ] **Step 8: Use `superpowers:finishing-a-development-branch`**

Present merge options for both feature branches. Merge only after the user chooses integration, then rerun the focused verification on the merged branches.
