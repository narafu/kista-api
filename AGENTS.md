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
- `docs/agents/docker-infra.md`: Fly.io, Supabase, Docker, and deployment operations.

Claude-specific agents, hooks, and skills remain under `.claude/`. Codex does not execute those hooks automatically, so after Java edits run `./gradlew compileJava` or the focused test command explicitly when practical.

## Project Structure & Module Organization

This is a Java 21 Spring Boot API using hexagonal architecture. Application entrypoint is `src/main/java/com/kista/KistaApplication.java`.

- `src/main/java/com/kista/domain`: pure domain models, strategies, and `port/in` / `port/out` interfaces. Keep this layer free of Spring and JPA annotations except documented strategy exceptions.
- `src/main/java/com/kista/application`: use case implementations and orchestration services.
- `src/main/java/com/kista/adapter/in`: REST controllers, schedulers, Telegram webhook, and security filters.
- `src/main/java/com/kista/adapter/out`: persistence, broker APIs, notifications, Redis, crypto, and external service adapters.
- `src/main/resources/db/migration`: Flyway migrations named like `V13__fix_column_type.sql`.
- `src/test/java`: unit, slice, architecture, and integration tests. Test support lives in `src/test/java/com/kista/support`.

## Build, Test, and Development Commands

- `./gradlew bootRun --args='--spring.profiles.active=local'`: run the API locally.
- `./gradlew bootJar`: build `app.jar`.
- `./gradlew compileJava`: compile production code and generated QueryDSL sources.
- `./gradlew test`: run JUnit 5 tests excluding `@Tag("integration")`.
- `./gradlew integration`: run Testcontainers/PostgreSQL integration tests; start Docker first with `docker compose up -d postgres`.
- `./gradlew test --tests 'com.kista.architecture.*'`: run ArchUnit layer rules.

## Coding Style & Naming Conventions

Use 4-space indentation and existing Java package patterns. Prefer records for immutable domain values, constructor injection for Spring beans, and package-private services/helpers where possible. Controllers expose DTOs from `adapter/in/web/dto`; application services depend on ports, not adapters. Keep adapter-specific mapping at the boundary. Flyway migrations must be append-only and versioned.

## Testing Guidelines

Tests use JUnit 5, Spring Boot Test, Mockito, ArchUnit, and Testcontainers. Name unit tests `*Test` and integration tests `*IT`; tag Docker-backed tests with `@Tag("integration")`. For `@WebMvcTest`, include CSRF on POST requests and mock authentication with a `UUID` principal instead of `@WithMockUser`.

## Commit & Pull Request Guidelines

Recent history follows Conventional Commit prefixes such as `fix:`, `feat(admin):`, `fix(kis):`, `docs:`, and `debug:`. Keep subjects imperative and scoped when useful. Pull requests should include a short behavior summary, linked issue or context, test commands run, and notes for migrations, configuration changes, or external API behavior.

## Security & Configuration Tips

Do not commit real secrets. Use `.env.example` as the template for local values. Keep profile-specific settings in `application-*.yml`, and document any new required environment variables in the PR.
