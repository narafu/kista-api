# VR Initial Bootstrap Design (Superseded)

This historical design is superseded by
[`2026-07-29-vr-pool-separation-design.md`](2026-07-29-vr-pool-separation-design.md).

The prior combined holdings-value/deposit pool-limit basis and V-only bootstrap
sell budget are obsolete. The current contract defines
`initialUsdDeposit` as the cycle opening pool, keeps `startAmount` as total
opening assets, and derives `poolLimit` from the opening pool and
`poolLimitRate`.
