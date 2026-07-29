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

VR rollover closes the old cycle with the same total-asset valuation. During
reconfiguration, the old cycle closes from the persisted pre-adjustment
deposit and holdings valued at the closing price; the new cycle starts from
the post-adjustment total assets. Capital injection and withdrawal are thus
external flows between cycles rather than old-cycle profit or loss.

## Consumers

- VR strategy detail returns the opening position deposit as
  `initialUsdDeposit`.
- `VrStrategyLifecycle` derives summary `poolLimit` from the opening deposit.
- `CycleOrderComputer` derives the trading `poolLimit` from the same opening
  deposit.
- Missing opening positions fail fast because a cycle without its opening
  snapshot is invalid persisted state.
- Generic strategy seed updates reject VR before persistence mutation and
  direct callers to VR reconfiguration.

## Compatibility And Tests

Existing cycles require no migration because their opening position already
contains the pool. Stats reads legacy VR opening principal from opening
position total assets when holdings can be valued at `closingPrice`. With
positive holdings and no opening closing price, Stats retains the stored
`startAmount`; using average cost would invent an opening market valuation.
Missing opening snapshots also retain the stored amount in Stats so historical
reporting remains available, while lifecycle/detail consumers fail fast.

Tests cover initial registration, detail lookup, order calculation,
opening-position persistence lookup, rollover/reconfiguration total assets,
and legacy VR Stats calculations. INFINITE and PRIVACY behavior remains
unchanged.
