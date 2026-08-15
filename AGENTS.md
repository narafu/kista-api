# Agent Guidelines

This file is the Codex entrypoint. Claude Code uses `CLAUDE.md`.
Keep tool-specific behavior in the root entrypoint files and keep shared project knowledge under `docs/agents/`.

## Shared Context

Read the relevant shared documents before changing related code:

- `docs/agents/commands.md`: common Gradle, Docker, local auth, and operations commands.
- `docs/agents/architecture.md`: package map, hexagonal architecture rules, controller/service/adapter patterns.
- `docs/agents/constraints.md`: project-specific implementation constraints and known failure modes.
- `docs/agents/testing.md`: test patterns for WebMvc, Spring Boot, Mockito, integration tests, and security.
- `docs/agents/workflow.md`: scheduler and trading execution flow.
- `docs/agents/kis-api.md`: KIS adapter/API work.
- `docs/agents/toss-api.md`: Toss adapter/API work.
- `docs/agents/docker-infra.md`: OCI server, Docker, and deployment operations.

Claude-specific agents, hooks, and skills remain under `.claude/`. Codex does not execute those hooks automatically, so after Java edits run `./gradlew compileJava` or the focused test command explicitly when practical.

Project structure, build/test commands, coding style, and testing conventions are fully covered by the `docs/agents/*.md` files listed above (and `CLAUDE.md` at the repo root) — this file intentionally does not duplicate them. Skim `commands.md` and `testing.md` before running builds or writing tests.

## Commit & Pull Request Guidelines

Recent history follows Conventional Commit prefixes such as `fix:`, `feat(admin):`, `fix(kis):`, `docs:`, and `debug:`. Keep subjects imperative and scoped when useful. Pull requests should include a short behavior summary, linked issue or context, test commands run, and notes for migrations, configuration changes, or external API behavior.

## Security & Configuration Tips

Do not commit real secrets. Use `.env.example` as the template for local values. Keep profile-specific settings in `application-*.yml`, and document any new required environment variables in the PR.
