# VR Pool Separation Design

## Contract

VR uses three distinct amounts:

```text
initialUsdDeposit = cycle opening USD deposit = pool
V = previous close * holdings
startAmount = initialUsdDeposit + holdings market value
poolLimit = initialUsdDeposit * poolLimitRate
```

`startAmount` remains total opening assets for every strategy. The API field
`initialUsdDeposit` must expose the cycle opening deposit rather than aliasing
`StrategyCycle.startAmount` for VR.

## Storage And Lookup

Do not add a schema column. Every cycle already saves an opening
`CyclePosition`; its `usdDeposit` is the canonical pool. Add a port operation
that returns the oldest non-deleted position for a cycle, ordered by
`createdAt ASC`, and use it for VR pool lookup.

Initial registration saves `startAmount = deposit + previousClose * holdings`.
VR rollover and reconfiguration also save total opening assets, calculated as
`postBalance.usdDeposit + closingPrice * postBalance.holdings`.

## Consumers

- VR strategy detail returns the opening position deposit as
  `initialUsdDeposit`.
- `VrStrategyLifecycle` derives summary `poolLimit` from the opening deposit.
- `CycleOrderComputer` derives the trading `poolLimit` from the same opening
  deposit.
- Missing opening positions fail fast because a cycle without its opening
  snapshot is invalid persisted state.

## Compatibility And Tests

Existing cycles require no migration because their opening position already
contains the pool. Tests cover initial registration, detail lookup, order
calculation, opening-position persistence lookup, and VR rollover total assets.
INFINITE and PRIVACY behavior remains unchanged.
