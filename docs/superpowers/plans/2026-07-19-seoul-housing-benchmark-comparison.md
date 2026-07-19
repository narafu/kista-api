# Seoul Housing Benchmark Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a statistics-page benchmark that compares USD-local portfolio or strategy performance with KRW-local Seoul apartment quintile price growth and shows the current USD/KRW mid-rate as nullable reference information.

**Architecture:** The API calculates cash-flow-adjusted USD returns from cycle snapshots and aligns them with persisted KB Land KRW price indices without FX conversion. After the comparison is calculated, it requests the current USD/KRW `midRate` through the existing Toss common API path and attaches it as optional response metadata; this call is never persisted and cannot change or fail the comparison. The UI consumes server-calculated indices and metrics in a lazy-loaded benchmark tab and treats the current rate as secondary information only.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, RestTemplate, JUnit 5, Mockito, Next.js 16, React 19, TanStack Query, Recharts, Vitest, Playwright.

## Global Constraints

- Work in isolated worktrees created with `superpowers:using-git-worktrees`; use paired API/UI feature branches.
- Use TDD for every behavioral task and run the focused failing test before implementation.
- The benchmark defaults are portfolio scope, Seoul third quintile, and five years.
- Investment indices and metrics remain USD-local; apartment indices and metrics remain KRW-local.
- Do not apply FX conversion to any point, return, CAGR, MDD, excess return, common-period boundary, or empty-state decision.
- `currentExchangeRate` is nullable informational response data with `midRate`, `fetchedAt`, and `source`.
- Fetch the current rate once per comparison API request through existing `ExchangeRatePort.getExchangeRate()`, which uses `TossHoldingsApi` and `TossHttpClient.getCommon()`.
- Never persist, backfill, schedule, cache as a monthly snapshot, or query a historical `dateTime` for exchange rates.
- An exchange-rate exception, missing response, null `midRate`, or nonpositive `midRate` produces `currentExchangeRate=null`; the comparison remains HTTP 200 when its own data is valid.
- Ratios in API responses are decimal fractions (`0.325`), not display percentages.
- Keep domain records free of Spring and JPA annotations.
- All output ports end in `Port`; do not add an exchange-rate persistence port.
- UI clients call the Next.js route handler, never `kista-api` directly.
- UI server state remains in React Query; do not copy it into `useState`.
- Do not add SPY/QQQ, dashboard cards, win counts, currency toggles, monthly FX tests, or an external-flow ledger in this scope.

---

### Task 1: Calculate cash-flow-adjusted monthly USD performance

**Files:**
- Create: `src/main/java/com/kista/domain/model/stats/MonthlyInvestmentPoint.java`
- Create: `src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java`
- Create: `src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java`

**Interfaces:**
- Produces: `List<MonthlyInvestmentPoint> calculate(List<StrategyCycle> cycles, List<CyclePosition> positions, LocalDate from, LocalDate to)`.
- Produces: `MonthlyInvestmentPoint(LocalDate baseMonth, BigDecimal investmentIndexUsd, BigDecimal monthlyReturn)`.

- [ ] **Step 1: Write failing calculator tests**

Create fixtures and explicit assertions for these behaviors:

```java
assertThat(calculate(singleCycle("100", "110")))
        .extracting(MonthlyInvestmentPoint::investmentIndexUsd)
        .containsExactly(new BigDecimal("100.0000000000"), new BigDecimal("110.0000000000"));

assertThat(calculate(withEndOfDayContribution("100", "110", "150")))
        .last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
        .isEqualTo(new BigDecimal("110.0000000000"));

assertThat(calculate(withFullReinvestment("100", "110", "110")))
        .last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
        .isEqualTo(new BigDecimal("110.0000000000"));

assertThat(calculate(withEndOfDayWithdrawal("100", "110", "90")))
        .last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
        .isEqualTo(new BigDecimal("110.0000000000"));
```

Also assert initial strategy capital uses the first complete valuation including holdings, VR rollover compares the old last and new first complete full valuations instead of cash-only `endAmount`, terminal strategy exit uses its last complete valuation on `endDate`, a survivor's same-day return is preserved, same-day replacement selects only the new cycle, multiple strategies aggregate before return calculation, the last same-day snapshot wins, missing snapshots carry forward, every active strategy must have a complete valuation, holdings without `closingPrice` do not fall back to `avgPrice`, an individual strategy ends after its last cycle, a nonpositive previous value omits the day, KST midnight separates dates, month boundaries use the last valid index, and MDD uses month-end USD indices. Do not create an exchange-rate fixture or parameter.

- [ ] **Step 2: Run the calculator test and verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.stats.MonthlyReturnCalculatorTest'
```

Expected: compilation fails because `MonthlyReturnCalculator` and `MonthlyInvestmentPoint` do not exist.

- [ ] **Step 3: Implement the pure calculator**

Use `BigDecimal` with scale 10 and `HALF_UP` for intermediate returns. Keep these responsibilities private and deterministic:

```java
List<DailyValuation> buildDailyValuations(
        List<StrategyCycle> cycles, List<CyclePosition> positions,
        LocalDate from, LocalDate to);

Map<LocalDate, BigDecimal> buildExternalFlows(
        List<StrategyCycle> cycles, List<CyclePosition> positions);

List<MonthlyInvestmentPoint> compoundDailyReturns(
        List<DailyValuation> valuations, Map<LocalDate, BigDecimal> flows);
```

Use end-of-day external-flow semantics: `dailyReturn = (currentValue - flow) / previousValue - 1`. Build one continuous flow timeline per strategy: first entry is the first cycle's first complete valuation, internal transitions are `new first complete value - old last complete value`, and final exit on `endDate` is the negative last complete value while the post-flow portfolio excludes that strategy. Never infer internal or final flows from cash-only `endAmount`. Do not access ports, current time, exchange rates, or currency conversion in this class. Omit a day when `previousValue <= 0` or any active strategy lacks a complete valuation.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests 'com.kista.application.service.stats.MonthlyReturnCalculatorTest'
```

Expected: all calculator cases pass and every result is unchanged by the absence of FX data because FX is not an input.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kista/domain/model/stats/MonthlyInvestmentPoint.java src/main/java/com/kista/application/service/stats/MonthlyReturnCalculator.java src/test/java/com/kista/application/service/stats/MonthlyReturnCalculatorTest.java
git commit -m "feat(stats): 월별 달러 투자수익률 계산 추가"
```

---

### Task 2: Expose the comparison API with optional current exchange rate

**Files:**
- Create: `src/main/java/com/kista/domain/model/stats/HousingBenchmarkComparison.java`
- Create: `src/main/java/com/kista/domain/model/stats/HousingBenchmarkPoint.java`
- Create: `src/main/java/com/kista/domain/model/stats/PerformanceComparisonSummary.java`
- Create: `src/main/java/com/kista/domain/model/stats/CurrentExchangeRate.java`
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
- Verify unchanged contract: `src/test/java/com/kista/adapter/out/toss/TossHoldingsApiTest.java`

**Interfaces:**
- Consumes: `MonthlyReturnCalculator`, `HousingBenchmarkPricePort`, existing `ExchangeRatePort.getExchangeRate()`, `CyclePositionPort`, and `StrategyCyclePort`.
- Produces: `UserStatsUseCase.getHousingBenchmarkComparison(UUID, BenchmarkScope, UUID, int, LocalDate, LocalDate)`.
- Produces: `GET /api/stats/housing-benchmark` with nullable `currentExchangeRate`.

- [ ] **Step 1: Write failing service tests for comparison behavior**

Assert default Seoul region `1100000000` and metric `APT_QTE_SALE_PRICE`, quintile-to-column mapping for all five values, portfolio scope, owned strategy scope, foreign strategy rejection, first-common-month normalization, cumulative return, annualized return, MDD, and the empty reasons `NO_INVESTMENT_DATA` and `INSUFFICIENT_COMMON_MONTHS`.

Use USD investment points directly:

```java
assertThat(result.points().getFirst().investmentIndexUsd())
        .isEqualByComparingTo("100.0");
assertThat(result.points().getLast().investmentIndexUsd())
        .isEqualByComparingTo("184.2");
assertThat(result.summary().investmentCumulativeReturn())
        .isEqualByComparingTo("0.842");
```

- [ ] **Step 2: Write failing service tests for current exchange-rate isolation**

Stub the existing port and verify success metadata:

```java
when(exchangeRatePort.getExchangeRate()).thenReturn(
        new TossExchangeRate(new BigDecimal("1370.00"), new BigDecimal("1365.20")));

HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
        USER_ID, BenchmarkScope.PORTFOLIO, null, 3, FROM, TO);

assertThat(result.currentExchangeRate().midRate()).isEqualByComparingTo("1365.20");
assertThat(result.currentExchangeRate().source()).isEqualTo("TOSS_INVEST");
assertThat(result.currentExchangeRate().fetchedAt()).isNotNull();
```

Capture a successful comparison result, then make `exchangeRatePort.getExchangeRate()` throw `TossApiException`; assert the second result has `currentExchangeRate=null` while `points`, `period`, `summary`, `benchmark`, and `emptyReason` equal the successful result. Repeat the nullable assertion for a null result, null `midRate`, zero, and a negative `midRate`.

- [ ] **Step 3: Write failing controller tests**

```java
mockMvc.perform(get("/api/stats/housing-benchmark")
        .principal(auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points[0].investmentIndexUsd").value(100.0))
        .andExpect(jsonPath("$.quality.investmentCurrency").value("USD"))
        .andExpect(jsonPath("$.quality.benchmarkCurrency").value("KRW"))
        .andExpect(jsonPath("$.currentExchangeRate.midRate").value(1365.2))
        .andExpect(jsonPath("$.currentExchangeRate.source").value("TOSS_INVEST"));
```

Add a response fixture with `currentExchangeRate=null` and assert `$.currentExchangeRate` has a null value while the status remains 200. Also cover `scope=STRATEGY` without `strategyId` as 400, quintile 0/6 as 400, owned strategy forwarding, and DTO decimal values.

- [ ] **Step 4: Run tests and verify failure**

```bash
./gradlew test --tests 'com.kista.application.service.stats.StatsServiceTest' --tests 'com.kista.adapter.in.web.StatsControllerTest'
```

Expected: compilation fails for the new use-case method, models, and DTO.

- [ ] **Step 5: Implement domain models and comparison builder**

Define the information-only record exactly:

```java
public record CurrentExchangeRate(
        BigDecimal midRate,
        Instant fetchedAt,
        String source
) {}
```

`HousingBenchmarkPoint` exposes `investmentIndexUsd`, not `investmentIndexKrw`. The builder intersects investment and apartment points by `baseMonth`, resets both first points to 100, and calculates:

```java
BigDecimal excessReturn = investmentCumulativeReturn.subtract(benchmarkCumulativeReturn);
BigDecimal annualized = BigDecimal.valueOf(
        Math.pow(lastIndex.divide(HUNDRED, 10, HALF_UP).doubleValue(), 12.0 / elapsedMonths) - 1.0);
```

Return decimal fractions in summary while chart indices remain around 100. The builder has no exchange-rate parameter.

- [ ] **Step 6: Implement service orchestration and query extension**

Validate scope and ownership before reading positions. Fetch from `Instant.EPOCH` through the requested end so carry-forward and the preceding benchmark month are available. Select the quintile with an explicit switch expression; do not use reflection.

After the comparison is fully built, obtain optional response information through the existing port:

```java
private CurrentExchangeRate fetchCurrentExchangeRate() {
    try {
        TossExchangeRate rate = exchangeRatePort.getExchangeRate();
        if (rate == null || rate.midRate() == null || rate.midRate().signum() <= 0) {
            return null;
        }
        return new CurrentExchangeRate(rate.midRate(), Instant.now(), "TOSS_INVEST");
    } catch (RuntimeException e) {
        log.warn("현재 USD/KRW 환율 조회 실패", e);
        return null;
    }
}
```

Call this method once per comparison request. Do not send `dateTime`, create a persistence model, modify a scheduler, or feed the returned rate back into the builder or calculator.

- [ ] **Step 7: Implement controller and response DTO**

Add `@RequestParam(defaultValue = "PORTFOLIO") BenchmarkScope scope`, optional `strategyId`, default quintile 3, and ISO dates. Response `from()` performs mapping only and emits this contract:

```json
{
  "points": [{ "investmentIndexUsd": 100.0, "benchmarkIndex": 100.0 }],
  "currentExchangeRate": {
    "midRate": 1365.2,
    "fetchedAt": "2026-07-19T01:30:00Z",
    "source": "TOSS_INVEST"
  },
  "quality": {
    "investmentCurrency": "USD",
    "benchmarkCurrency": "KRW"
  }
}
```

Keep `currentExchangeRate` as an explicit nullable field instead of omitting it through NON_NULL serialization.

- [ ] **Step 8: Run API, Toss contract, and architecture tests**

```bash
./gradlew compileJava test --tests 'com.kista.application.service.stats.*' --tests 'com.kista.adapter.in.web.StatsControllerTest' --tests 'com.kista.adapter.out.toss.TossHoldingsApiTest' --tests 'com.kista.architecture.*'
```

Expected: build succeeds; current-rate failure cases return comparison data; no historical or monthly FX test exists.

- [ ] **Step 9: Smoke-test the local API without making FX availability a gate**

Call portfolio/third-quintile and one owned strategy. Expected: HTTP 200, `investmentIndexUsd=100` and `benchmarkIndex=100` at the first common month, ascending months, and summary values consistent with the last indices. Accept either a populated `currentExchangeRate` or null; never block implementation or retry historical dates based on this field.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/kista/domain/model/stats src/main/java/com/kista/domain/port/in/UserStatsUseCase.java src/main/java/com/kista/domain/port/out/CyclePositionPort.java src/main/java/com/kista/application/service/stats src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java src/main/java/com/kista/adapter/in/web/StatsController.java src/main/java/com/kista/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java src/test/java/com/kista/application/service/stats src/test/java/com/kista/adapter/in/web/StatsControllerTest.java
git commit -m "feat(stats): 서울 아파트 벤치마크 비교 API 추가"
```

---

### Task 3: Add the UI stats data contract and query hook

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
- Consumes: `GET /api/stats/housing-benchmark` from Task 2.
- Produces: `getHousingBenchmarkComparison(params, token?)` and `useHousingBenchmarkQuery(params, enabled)`.

- [ ] **Step 1: Refresh OpenAPI and generated types**

With the API running locally:

```bash
npm run fetch:spec
npm run gen:types
```

Expected: generated schema contains `/api/stats/housing-benchmark`, `investmentIndexUsd`, and nullable `currentExchangeRate` with `midRate`, `fetchedAt`, and `source`.

- [ ] **Step 2: Write failing API and hook tests**

Assert all parameters reach the route and query key:

```ts
expect(queryKey).toEqual([
  'housingBenchmark', 'PORTFOLIO', null, 3, '2021-07-01', '2026-07-01'
])
```

Use both response forms in fixtures:

```ts
currentExchangeRate: {
  midRate: 1365.2,
  fetchedAt: '2026-07-19T01:30:00Z',
  source: 'TOSS_INVEST',
}
```

and `currentExchangeRate: null`. Assert `enabled=false` performs no request and `placeholderData` keeps previous chart data during filter changes.

- [ ] **Step 3: Run tests and verify failure**

```bash
npm run test:run -- entities/stats/api/index.test.ts entities/stats/hooks/useHousingBenchmarkQuery.test.tsx app/api/stats/[[...path]]/route.test.ts
```

Expected: tests fail because the API function, response types, and hook do not exist.

- [ ] **Step 4: Implement types, API function, hook, and exports**

Use these parameter and response types:

```ts
export interface HousingBenchmarkParams {
  scope: 'PORTFOLIO' | 'STRATEGY'
  strategyId?: string
  quintile: 1 | 2 | 3 | 4 | 5
  from?: string
  to?: string
}

export interface CurrentExchangeRate {
  midRate: number
  fetchedAt: string
  source: 'TOSS_INVEST'
}
```

The comparison type declares `currentExchangeRate: CurrentExchangeRate | null`, and each point declares `investmentIndexUsd`. The hook query key includes every request parameter and uses `placeholderData: (previous) => previous`.

- [ ] **Step 5: Run tests and typecheck**

```bash
npm run test:run -- entities/stats/api/index.test.ts entities/stats/hooks/useHousingBenchmarkQuery.test.tsx app/api/stats/[[...path]]/route.test.ts
npm run typecheck
```

Expected: tests and typecheck pass for both populated and null current-rate responses.

- [ ] **Step 6: Commit in the UI repository**

```bash
git add openapi.json shared/lib/api-types.ts entities/stats app/api/stats/[[...path]]/route.test.ts
git commit -m "feat(stats): 주택 벤치마크 조회 계층 추가"
```

---

### Task 4: Build the benchmark comparison tab

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
- Consumes: `useHousingBenchmarkQuery`, existing strategy/account entity queries, API indices, summary, and nullable current-rate metadata.
- Produces: `HousingBenchmarkComparison` lazy tab matching the approved mockup.

- [ ] **Step 1: Write failing widget tests**

Cover tab labels, default portfolio/third-quintile/five-year query, lazy query only after opening the tab, strategy selector visibility, filter changes, summary formatting, empty reason copy, section-only comparison error, source update date, local-currency notice, and quintile disclaimer.

Add these current-rate cases:

```tsx
expect(screen.getByText('1 USD = 1,365.20 KRW')).toBeInTheDocument()
expect(screen.getByText(/TOSS_INVEST/)).toBeInTheDocument()
```

For `currentExchangeRate: null`, assert the chart, summary, and comparison table remain visible and no exchange-rate error state appears.

- [ ] **Step 2: Run tests and verify failure**

```bash
npm run test:run -- widgets/stats-overview/HousingBenchmarkComparison.test.tsx widgets/stats-overview/StatsOverview.test.tsx
```

Expected: tests fail because the new components and tab do not exist.

- [ ] **Step 3: Add tab state and benchmark composition**

Use an accessible two-button segmented control with `aria-pressed`. Preserve the current operational stats tree unchanged behind `activeTab === 'OPERATIONS'`. Enable the benchmark query only after `activeTab === 'BENCHMARK'`.

- [ ] **Step 4: Implement chart and summary**

Use Recharts with the server-provided local-currency indices:

```tsx
<Line dataKey="investmentIndexUsd" name={`${investmentLabel} (USD)`} stroke="var(--chart-1)" dot={false} />
<Line dataKey="benchmarkIndex" name={`${benchmark.label} (KRW)`} stroke="var(--chart-3)" dot={false} />
```

The tooltip shows month, both indices, and both monthly returns. It does not show or apply an FX value and does not compute cumulative results in the browser.

- [ ] **Step 5: Add quintile, currency-basis, and current-rate information**

Create typed entries for all five quintiles with range label, representative areas, characteristics, and examples supplied by the user. Prefix examples with `해당 가격대에서 자주 언급되는 지역·단지 예시` and always render the non-fixed-membership disclaimer from the design.

Always render the notice `투자 성과는 USD, 서울 아파트는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다.` When `currentExchangeRate` exists, show its formatted `midRate`, localized `fetchedAt`, and `source` as secondary reference text. When null, omit only that reference row.

- [ ] **Step 6: Implement responsive layout**

At mobile widths, stack selectors, render excess performance full-width above two comparison values, retain a three-column metrics table without horizontal scrolling, and keep chart height at least 240px. Use existing cards, tokens, and toggle patterns; do not add nested cards.

- [ ] **Step 7: Run focused tests and typecheck**

```bash
npm run test:run -- widgets/stats-overview/HousingBenchmarkComparison.test.tsx widgets/stats-overview/StatsOverview.test.tsx
npm run typecheck
```

Expected: tests and typecheck pass, including null current-rate rendering.

- [ ] **Step 8: Commit in the UI repository**

```bash
git add widgets/stats-overview
git commit -m "feat(stats): 서울 아파트 벤치마크 비교 화면 추가"
```

---

### Task 5: End-to-end verification and integration

**Files:**
- Modify: API/UI tests that reproduce any defect found during verification before changing production code.
- Update: `docs/agents/architecture.md` and `docs/agents/workflow.md` only through the project doc-sync process after implementation commits.

**Interfaces:**
- Consumes: completed API and UI feature branches.
- Produces: verified desktop/mobile feature and merge-ready commits.

- [ ] **Step 1: Run full focused API verification**

```bash
./gradlew compileJava test --tests 'com.kista.application.service.stats.*' --tests 'com.kista.adapter.out.toss.TossHoldingsApiTest' --tests 'com.kista.adapter.in.web.StatsControllerTest' --tests 'com.kista.architecture.*'
```

Expected: build succeeds with zero failed tests. No migration, monthly exchange-rate persistence, scheduler linkage, backfill, historical probe, or monthly FX test is present.

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

Capture `/stats` at 1440x900 and 375x812. Verify no overlap or horizontal overflow; controls remain usable; chart is nonblank; portfolio/strategy, quintile, and period controls update the series; USD and KRW labels are visible; dark and light themes retain contrast. Verify both populated and null `currentExchangeRate` fixtures leave the comparison usable.

- [ ] **Step 5: Verify calculations against source samples**

Pick at least three months and independently calculate the USD investment return from position/cycle data and the KRW apartment return from `housing_benchmark_prices`. Expected: API indices match within the documented BigDecimal rounding tolerance. Confirm changing the mocked current `midRate` or making the call fail leaves every point and summary field byte-for-byte equal.

- [ ] **Step 6: Run doc-sync and inspect both worktrees**

Ensure shared docs describe only implemented behavior. Run `git diff --check` and `git status --short` in both repositories. Expected: no whitespace errors and no unintended files.

- [ ] **Step 7: Use `superpowers:requesting-code-review`**

Request review of calculation correctness, ownership/security, current-rate failure isolation, API/UI contract, and missing tests. Address verified findings with focused tests and commits.

- [ ] **Step 8: Use `superpowers:finishing-a-development-branch`**

Present merge options for both feature branches. Merge only after the user chooses integration, then rerun the focused verification on the merged branches.
