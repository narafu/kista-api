# 기술 스택

## 언어/런타임
- Java 21 (Virtual Thread 활성화: `spring.threads.virtual.enabled=true`)
- Spring Boot 3.4.x (Spring Framework 6.2)

## 빌드
- Gradle (Kotlin DSL: `build.gradle.kts`, `gradle/libs.versions.toml`)
- 실행 파일: `bootJar` → `app.jar`

## 주요 의존성
- Spring Data JPA + PostgreSQL + Flyway
- QueryDSL JPA (Jakarta EE 10, Q-class: `build/generated/querydsl`)
- Spring Security + OAuth2 Resource Server (NimbusJwtDecoder, EC P-256)
- JJWT (HS256 dev-token용)
- springdoc-openapi 2.8.4+ (Spring Boot 3.4.x 요구사항)
- ArchUnit (JUnit5): Hexagonal 레이어 의존성 빌드 시 검증
- Micrometer Prometheus
- Firebase Admin SDK
- Lombok

## 테스트
- JUnit 5 + Spring MVC Test + ArchUnit
- 병렬 실행 (`dynamic` strategy), 시스템 TZ=Asia/Seoul 고정

## 인프라
- DB: PostgreSQL (Supabase 운영, Docker 로컬)
- 서버: Render (`srv-d7sir2jbc2fs73cptpm0`)
- 프론트엔드: kista-ui (Next.js, `/Users/narafu/workspace/kista/kista-ui`)
- `ddl-auto: validate` — Hibernate DDL 자동 생성 비활성화
