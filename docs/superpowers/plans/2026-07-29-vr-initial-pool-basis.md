# VR Initial Pool Basis Implementation Plan (Superseded)

> **Superseded on 2026-07-29.** Do not implement the former cash-only VR
> `startAmount` plan.

Use these replacement documents:

- Design: `docs/superpowers/specs/2026-07-29-vr-pool-separation-design.md`
- Plan: `docs/superpowers/plans/2026-07-29-vr-pool-separation.md`

The canonical contract keeps `StrategyCycle.startAmount` as total opening
assets for every strategy, stores the VR opening USD pool in the opening
`CyclePosition.usdDeposit`, and derives `poolLimit` from that pool.

The superseded task instructions were removed so they cannot be mistaken for
the active formula.
