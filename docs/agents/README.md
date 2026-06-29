# Agent Documentation

This directory contains project knowledge shared by Codex, Claude Code, and other coding agents.

Root entrypoints stay tool-specific:

- `AGENTS.md`: Codex entrypoint and concise repository guide.
- `CLAUDE.md`: Claude Code entrypoint and Claude-specific `@` references.
- `.claude/`: Claude-only hooks, agents, and skills.

When adding long-lived project rules, prefer this directory over tool-specific files. Put only bootstrap instructions and tool-specific behavior in the root entrypoint files.
