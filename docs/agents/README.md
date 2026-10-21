# Agent Documentation

This directory contains project knowledge shared by Claude Code and other coding agents.

Root entrypoints stay tool-specific:

- `CLAUDE.md`: Claude Code entrypoint and Claude-specific `@` references.
- `.claude/`: Claude-only hooks, agents, and skills.

When adding long-lived project rules, prefer this directory over tool-specific files. Put only bootstrap instructions and tool-specific behavior in the root entrypoint files.

Contents: `commands.md`, `architecture.md`, `constraints.md`, `testing.md`, `workflow.md`, `kis-api.md`, `toss-api.md`, `docker-infra.md`, `scheduler-time-table.md`, `own-type-ledger.md`, `modulith-migration-history.md` (history, not auto-loaded), `modules/<module>.md` (per-module details).

## Plan/spec documents (`docs/superpowers`)

- Checkboxes in plans are never ticked in practice, so they do not show progress. When a plan is implemented, add `> 상태: 완료 (YYYY-MM-DD, 커밋 <해시>)` under its title; a plan that is parked gets `> **[DEFERRED YYYY-MM-DD]** <재개 조건>`.
- Move finished plans/specs to the sibling `archive/` directory (`git mv`) once no `docs/agents` or code comment cites their path; cited ones stay in place.
- Do not cite plan/spec paths from `docs/agents` unless the document is the design SSOT for that behavior.
