# Task 6 report
- Reorder BUY (any timing) now checked before source cancel: price*qty <= StrategyLedgerBudget.remaining(currentCycle, tradeDate) + refundOf(source, tradeDate); else ManualTradingException("전략 원장 예수금이 부족합니다.") (409). No trimming.
- refundOf only for same-date PLANNED/PLACED BUY source (remaining already subtracts it) -> no double add.
- Added StrategyCyclePort.findById.
- Wire (kista-ui): GET /api/admin/trades/reorder-buy-budget -> { cycleRemaining: {strategyCycleId: number}, sourceRefunds: {orderId: number} }. plannedBuy/liveOrderable removed. Available = cycleRemaining[order.strategyCycleId] + sum(sourceRefunds of rows reordered).
- Tests: ReorderBuyBudgetServiceTest(4), ReorderServiceTest(24 incl. immediateBuyOverLedgerRejected409, immediateBuyWithinLedgerProceeds, sell-origin no refund), controller/adapter/AdminTrade/InternalApiContract pass. RED was compile failure (new API).

## Fix round 1
- Added StrategyLedgerBudget.applies(StrategyType) (= occupiesSeed), reused by fit(), ReorderService (BUY ledger check skipped for DCA) and ReorderBuyBudgetService (non-seed cycles omitted from cycleRemaining; missing key = no cap; now takes StrategyPort).
- ponytail comment on missing AccountBudgetLock at the reorder check.
- Tests: nonSeedCycleOmittedFromCycles, dcaBuyReorderSkipsLedgerCap. Command: ./gradlew :trading-core:compileTestJava :trading-core:test --tests '*ReorderServiceTest' --tests '*ReorderBuyBudgetServiceTest' -> BUILD SUCCESSFUL (5 + 25 tests, 0 failures).
