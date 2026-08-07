# 기술 스택 (포인터)

Java 21 (Virtual Thread) + Spring Boot 3 + Gradle Kotlin DSL. DB: PostgreSQL (운영 자체호스팅 `kista-postgres` 컨테이너 / 로컬 Docker), Flyway, QueryDSL.
배포: 자체호스팅 OCI 인스턴스(Docker Compose) — 과거 Fly.io/Render/Supabase 정보는 폐지됨. 프론트: kista-ui (Next.js, 별도 레포, 같은 OCI 인스턴스에서 호스팅).

상세 구조는 `docs/agents/architecture.md`, 인프라는 `docs/agents/docker-infra.md` 참고.
