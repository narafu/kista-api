# VR Initial Pool Basis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the initial VR pool and derived pool limit use only the strategy's USD deposit, excluding existing holdings value.

**Architecture:** Keep `StrategyCycle.startAmount` as the existing combined initial asset value for INFINITE and PRIVACY. For VR, store normalized `initialUsdDeposit`, matching the established rollover-cycle meaning; continue storing previous-close holdings value independently in `StrategyCycleVrDetail.value`.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- Preserve the unrelated gradient-ramp changes from commit `a3d96fda`.
- Do not add a database column or migration.
- Do not change INFINITE or PRIVACY initial cycle behavior.
- Derive VR `poolLimit` as `initialUsdDeposit * poolLimitRate`.

---

### Task 1: Correct Initial VR Pool Basis

**Files:**
- Modify: `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java`
- Modify: `docs/agents/constraints.md`

**Interfaces:**
- Consumes: `StrategyService.register(UUID, UUID, RegisterStrategyCommand)` and the existing `StrategyCycle.start(...)` factory.
- Produces: VR initial cycles whose `startAmount()` equals normalized `initialUsdDeposit`; no public signature changes.

- [ ] **Step 1: Extend the existing VR bootstrap test with the failing contract**

In `register_vr_bootstrap_computesValueFromMarketPrice`, capture the `StrategyCycle` passed to `strategyCyclePort.save`, return a cycle with the captured `startAmount`, and assert the observable registration result:

```java
when(strategyCyclePort.save(any(StrategyCycle.class))).thenAnswer(invocation -> {
    StrategyCycle cycle = invocation.getArgument(0);
    return new StrategyCycle(vrCycleId, vrStrategyId, STRATEGY_VERSION_ID,
            cycle.startAmount(), null, cycle.startDate(), null, null, null);
});

StrategyDetail result = strategyService.register(USER_ID, ACCOUNT_ID, cmd);

assertThat(result.initialUsdDeposit()).isEqualByComparingTo("1000.00");
assertThat(result.vr().value()).isEqualByComparingTo("600.00");
assertThat(result.vr().poolLimit()).isEqualByComparingTo("500.00");
```

The production mutation this catches is using `normalizedInitialUsdDeposit.add(initialStockValue)` as the VR cycle's `startAmount`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest.register_vr_bootstrap_computesValueFromMarketPrice'
```

Expected: FAIL because `initialUsdDeposit` is `1600.00` and `poolLimit` is `800.00` under the current implementation.

- [ ] **Step 3: Implement the minimal strategy-type branch**

In `saveInitialCycleAndPosition`, keep the shared stock-value calculation but choose the cycle start amount by strategy type:

```java
BigDecimal startAmount = saved.isVr()
        ? normalizedInitialUsdDeposit
        : normalizedInitialUsdDeposit.add(initialStockValue);
```

Do not change the initial `CyclePosition` or `StrategyCycleVrDetail` persistence calls.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest.register_vr_bootstrap_computesValueFromMarketPrice'
```

Expected: PASS.

- [ ] **Step 5: Update the VR contract documentation**

Change `docs/agents/constraints.md` so it states that VR `startAmount` is the USD pool for both initial and rollover cycles, while non-VR initial cycles retain total initial assets. State explicitly:

```text
initial VR poolLimit = initialUsdDeposit * poolLimitRate
```

- [ ] **Step 6: Run focused and project verification**

Run:

```bash
./gradlew test --tests 'com.kista.application.service.strategy.StrategyServiceTest'
./gradlew compileJava
./gradlew test
```

Expected: all commands exit successfully.

- [ ] **Step 7: Review the final diff without committing unrelated work**

Run:

```bash
git diff -- src/main/java/com/kista/application/service/strategy/StrategyService.java src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java docs/agents/constraints.md
git status --short
```

Expected: the pool-basis changes are present without overwriting the gradient-ramp changes from commit `a3d96fda`. Commit only when separately requested.
