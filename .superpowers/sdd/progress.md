# Seoul Housing Benchmark SDD Progress

Plan: `docs/superpowers/plans/2026-07-19-seoul-housing-benchmark-comparison.md`
API worktree: `/Users/phs/workspace/kista/kista-api-seoul-benchmark`
UI worktree: `/Users/phs/workspace/kista/kista-ui-seoul-benchmark`

Baseline:
- API focused stats/controller/architecture tests: passed before feature implementation
- UI stats tests: 11 passed; typecheck passed before feature implementation

Scope correction approved on 2026-07-19:
- Performance comparison excludes FX conversion.
- Strategy returns remain USD-local; Seoul apartment returns remain KRW-local.
- Current USD/KRW `midRate` is nullable informational response data fetched live per comparison API request.
- Current FX is never persisted and cannot affect points, periods, empty states, or metrics.
- Current FX failure leaves the comparison successful with `currentExchangeRate=null`.
- The previous task allocation and numbering are invalidated by the corrected five-task plan.

Previous-plan status:
- Previous Task 1 historical Toss probe: superseded as an implementation gate; verified findings are background only and no live probe is required.
- Previous Task 2 monthly exchange-rate persistence: **SUPERSEDED, NOT COMPLETE**. Commit `2561a81a` was reverted by `bca566ef` using `git revert`.
- Previous Tasks 3 and 6 historical collection, scheduler linkage, and backfill: superseded and removed from scope.
- Previous Tasks 4, 5, 7, 8, and 9: replaced by corrected Tasks 1 through 5; no implementation completion is claimed.

Corrected-plan status:
- Task 1, USD-local monthly return calculator: complete (commits `e6bba3e7..b9b4b24a`, financial review clean)
- Task 2, comparison API and nullable current exchange rate: complete (commits `2213c5b2`, `90cac0d5`, `db033a67`; review approved)
- Task 3, UI data contract and query hook: complete (UI commits `8698d23`, `2456a6f`; review approved)
- Task 4, benchmark comparison tab: complete (UI commits `d18af133`, `f0e0bfb2`, `36be0b79`; review approved)
- Task 5, end-to-end verification and integration: steps 1-6 complete (2026-07-19 resumed session)
  - Step 1 (API focused verification): PASS — compileJava + stats/toss/StatsController/architecture tests all green (162 domain tests too)
  - Step 2 (UI verification): PASS — 34 tests, typecheck, `next build` all green
  - Step 3-4 (local servers + Playwright desktop/mobile, light/dark): PASS after seeding an isolated synthetic dataset under the local dev-token user (00000000-0000-0000-0000-000000000001, cleaned up afterward) — no console errors, no horizontal overflow, chart/summary/disclaimers render correctly in all 4 combinations. One transient 500 traced to a stale Next.js dev server process left running since the interrupted prior session (stale inlined `NEXT_PUBLIC_API_BASE_URL`); resolved by restarting the dev server — not a code defect.
  - Step 5 (calculation cross-check): PASS — hand-computed synthetic series (10000 * 1.012^k, 25 monthly points) matched API response exactly (investmentCumulativeReturn 0.331473 = 1.012^24-1; benchmarkIndex matched raw `housing_benchmark_prices.third_quintile_price` ratios). Contract checks also verified: STRATEGY scope, quintile switch, invalid quintile→400, missing strategyId→400, foreign strategyId→403.
  - Step 6 (doc-sync): PASS — `docs/agents/architecture.md` updated (commit `05ff579e`) to document StatsService.getHousingBenchmarkComparison, MonthlyReturnCalculator, HousingBenchmarkComparisonBuilder, StatsController; `git diff --check` clean in both worktrees; no unintended files.
  - Step 7 (final whole-branch code review): PASS — kista-api: no Critical/Important, "Ready to merge: Yes" (Minor cleanup only: dead `calculateMaxDrawdown`, redundant controller validation, unreachable negative-base `Math.pow` edge in annualizedReturn — none block merge). kista-ui: one Important finding (chart series `--chart-1`/`--chart-3` near-indistinguishable in dark theme) fixed in commit `f4c7ac6` (benchmark line moved to `--chart-2` + dashed stroke, legend swatch updated) and re-verified (18 tests + typecheck green, re-screenshotted in dark theme — solid vs dashed lines now clearly distinct). Mobile bottom-nav overlap I'd flagged for Playwright review was ruled out as a pre-existing layout-level (`pb-24 lg:pb-9` in `app/(main)/layout.tsx`) behavior, not a regression from this feature.
  - Step 8 (finishing-a-development-branch): in progress — merge base note: `main` in kista-api independently added the *original* (uncorrected) design/plan docs at the same paths (`dc67702d`, not an ancestor of this branch) — expect an add/add conflict on `docs/superpowers/specs/...` and `docs/superpowers/plans/...` when merging; resolve by keeping this branch's corrected versions. kista-ui main has no divergent commits, no conflict expected.
