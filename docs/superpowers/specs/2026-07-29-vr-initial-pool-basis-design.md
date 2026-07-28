# VR Initial Pool Basis Design

## Problem

When a VR strategy starts with existing holdings, the initial cycle currently stores
`startAmount` as the sum of the USD deposit and the holdings' market value. VR then
derives `poolLimit` from `startAmount * poolLimitRate`, so the holdings value is
incorrectly included in the pool.

The VR contract separates the two values:

- `V` is the existing holdings value at the previous close.
- `pool` is the USD deposit available to the strategy.

## Design

For initial VR cycle creation, store the normalized `initialUsdDeposit` as the
cycle `startAmount`. Continue storing the previous-close holdings value as the
initial VR `value`.

Keep the existing combined `startAmount = initialUsdDeposit + initialStockValue`
behavior for INFINITE and PRIVACY strategies. No schema change or new field is
needed because VR rollover cycles already store their post-rollover USD deposit
as `startAmount`.

The resulting VR formulas are:

```text
V = previousClose * initialHoldings
pool = initialUsdDeposit
poolLimit = pool * poolLimitRate
```

## Scope

- Change only initial cycle creation in `StrategyService`.
- Preserve initial position holdings, average price, closing price, and USD deposit.
- Preserve VR validation, ramp calculation, and rollover behavior.
- Update the VR contract documentation describing `startAmount` and `poolLimit`.

## Verification

Add a regression test for a VR strategy with a USD deposit of 1000, five shares,
a previous close of 120, and a pool limit rate of 0.50. The test must show that:

- `V` is 600.
- cycle `startAmount` is 1000, not 1600.
- `poolLimit` is 500, not 800.

Run the focused `StrategyServiceTest`, then compile production code and run the
full non-integration test suite.
