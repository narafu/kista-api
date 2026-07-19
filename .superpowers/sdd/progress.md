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
- Task 1, USD-local monthly return calculator: pending
- Task 2, comparison API and nullable current exchange rate: pending
- Task 3, UI data contract and query hook: pending
- Task 4, benchmark comparison tab: pending
- Task 5, end-to-end verification and integration: pending
