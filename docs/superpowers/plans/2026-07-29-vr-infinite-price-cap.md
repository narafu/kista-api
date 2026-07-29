# VR/INFINITE Price Cap Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make VR BUY preview and order creation use the same reference-price plus placement-price cap flow as INFINITE while preserving strategy-specific formulas.

**Architecture:** `TradingService` keeps price lookup batched by ticker and refreshes placement prices immediately before order placement. `BuyOrderPriceCapper` remains the common cap orchestration point, while INFINITE, PRIVACY, and VR keep separate correction formulas through `PriceCapMode`.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Gradle.

## Global Constraints

- Write failing tests before production changes.
- Keep price lookup batched by ticker, not per strategy.
- Use `PriceCapPolicy` as the single cap multiplier source.
- Do not change SELL cap behavior.
- Do not change VR pool, rollover, or core value rebalancing formulas beyond BUY reference/cap separation.

---

### Task 1: VR Preview Uses Previous Close for Bootstrap BUY

**Files:**
- Modify: `src/test/java/com/kista/application/service/trading/StrategyOrderPlanBuilderTest.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/application/service/trading/CycleOrderComputer.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrStrategy.java`

**Interfaces:**
- Consumes: `CycleOrderStrategy.requiresPrevClose()`
- Produces: VR first-cycle BUY preview can be generated when current price is not passed from UI.

- [ ] **Step 1: Write failing preview test**

Add a test showing VR preview calls previous-close lookup and returns bootstrap BUY when current price is absent.

- [ ] **Step 2: Run focused test and verify RED**

Run: `./gradlew test --tests 'com.kista.application.service.trading.StrategyOrderPlanBuilderTest'`
Expected: FAIL because VR does not require previous close or still passes `null` to bootstrap.

- [ ] **Step 3: Implement reference price path**

Set VR `requiresPrevClose()` to true and pass previous close into VR order inputs as the BUY reference price.

- [ ] **Step 4: Run focused test and verify GREEN**

Run: `./gradlew test --tests 'com.kista.application.service.trading.StrategyOrderPlanBuilderTest'`
Expected: PASS.

### Task 2: VR BUY Cap Moves to Common Capper

**Files:**
- Modify: `src/test/java/com/kista/domain/strategy/CycleOrderStrategyCapabilityTest.java`
- Modify: `src/test/java/com/kista/domain/strategy/VrStrategyTypeTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/BuyOrderPriceCapperTest.java`
- Modify: `src/main/java/com/kista/domain/strategy/CycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrCycleOrderStrategy.java`
- Modify: `src/main/java/com/kista/domain/strategy/VrStrategy.java`
- Modify: `src/main/java/com/kista/application/service/trading/BuyOrderPriceCapper.java`

**Interfaces:**
- Consumes: `PriceCapPolicy.capFor(BigDecimal currentPrice)`
- Produces: `PriceCapMode.VR_POSITION` and a common capper path that can correct VR BUY PLANNED orders.

- [ ] **Step 1: Write failing capability and capper tests**

Assert VR reports `VR_POSITION`, generated BUY ladder is not capped at creation time, and `BuyOrderPriceCapper` can cap VR BUY orders.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew test --tests 'com.kista.domain.strategy.CycleOrderStrategyCapabilityTest' --tests 'com.kista.domain.strategy.VrStrategyTypeTest' --tests 'com.kista.application.service.trading.BuyOrderPriceCapperTest'`
Expected: FAIL because `VR_POSITION` does not exist and VR caps in `VrStrategy`.

- [ ] **Step 3: Implement VR cap mode and correction path**

Add `VR_POSITION`, remove VR creation-time cap for normal BUY ladder, and add a VR correction path in `BuyOrderPriceCapper`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same focused command.
Expected: PASS.

### Task 3: Placement Prices Refresh Once Per Ticker Before Close Placement

**Files:**
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java`

**Interfaces:**
- Consumes: `TradingPriceFetcher.fetchPriceSnapshots(List<Ticker>, Account)`
- Produces: `placeAll` uses refreshed placement current prices instead of stale start prices.

- [ ] **Step 1: Write failing close-placement refresh test**

Assert close placement refreshes current prices once per ticker after waiting and passes refreshed prices to cap correction.

- [ ] **Step 2: Run focused test and verify RED**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'`
Expected: FAIL because placement uses `state.startPrice()`.

- [ ] **Step 3: Implement close placement refresh**

Collect placement tickers from states, refresh snapshots once, and use refreshed current prices during `placeOrders`.

- [ ] **Step 4: Run focused test and verify GREEN**

Run the same focused command.
Expected: PASS.

### Task 4: AT_OPEN Placement Uses Same BUY Cap Policy

**Files:**
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingOrderExecutorTest.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingOrderExecutor.java`
- Modify: `src/main/java/com/kista/application/service/trading/ManualTradingService.java`

**Interfaces:**
- Consumes: `TradingOrderExecutor.placeOrders(...)`
- Produces: AT_OPEN planned orders pass through the same BUY cap policy before broker placement.

- [ ] **Step 1: Write failing AT_OPEN cap test**

Assert open scheduler placement invokes cap correction before placing AT_OPEN PLANNED orders.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest' --tests 'com.kista.application.service.trading.TradingOrderExecutorTest'`
Expected: FAIL because `placeGiven()` bypasses cap correction.

- [ ] **Step 3: Implement shared placement path**

Route AT_OPEN placement through an executor method that receives strategy, cycle id, position, and placement price, applies cap, then places planned orders.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the same focused command.
Expected: PASS.

### Task 5: Final Verification and Docs

**Files:**
- Modify as needed: `docs/agents/workflow.md`
- Modify as needed: `docs/agents/architecture.md`
- Modify as needed: `docs/superpowers/specs/2026-07-29-vr-infinite-price-cap-design.md`

**Interfaces:**
- Consumes: completed implementation.
- Produces: compiled and tested branch with shared docs updated if behavior changed.

- [ ] **Step 1: Run full verification**

Run: `./gradlew test` and `./gradlew compileJava`
Expected: PASS.

- [ ] **Step 2: Update docs if implementation changes shared behavior text**

Update workflow/architecture docs only for behavior that changed from the current published docs.

- [ ] **Step 3: Commit final implementation**

Commit with a conventional subject such as `fix(vr): align buy price cap flow`.
