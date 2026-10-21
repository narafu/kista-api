# VR Initial Pool Basis Design (Superseded)

This design is superseded by
[`2026-07-29-vr-pool-separation-design.md`](2026-07-29-vr-pool-separation-design.md).

The corrected contract keeps `startAmount` as total opening assets for every
strategy. For VR, `initialUsdDeposit` is the cycle opening pool and `poolLimit`
is the opening pool multiplied by `poolLimitRate`.
