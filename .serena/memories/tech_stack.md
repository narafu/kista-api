# 기술 스택 (포인터)

Java 21 (Virtual Thread) + Spring Boot 3 + Gradle Kotlin DSL. DB: PostgreSQL (운영 Supabase / 로컬 Docker), Flyway, QueryDSL.
배포: Fly.io `kista-api` 앱(nrt 도쿄) — 과거 Render 정보는 폐기됨. 프론트: kista-ui (Next.js, 별도 레포).

상세 구조는 `docs/agents/architecture.md`, 인프라는 `docs/agents/docker-infra.md` 참고.
