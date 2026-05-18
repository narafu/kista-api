# CLAUDE.md

한국투자증권(KIS) REST API를 통한 SOXL 분할매매 자동화 서비스.
Java 21 + Spring Boot 3 기반 Hexagonal Architecture.

## 코드 철학

- 반복 코드보다 재사용성·가독성·최신 문법을 우선
- 디자인 패턴, 객체지향, SOLID 원칙 준수
- Hexagonal Architecture, EDD, DDD, CQRS, TDA 설계 지향

## 작업 방식

- 독립적으로 병렬 처리 가능한 작업은 서브에이전트를 적극 활용
- 요청이 모호하거나 구체적 지침이 필요한 경우 가정하고 진행하지 말고 먼저 질문

## Git 규칙
- `git push`는 사용자가 명시적으로 요청한 경우에만 실행 — 커밋 후 자동 푸시 금지
- 커밋 전 `git config user.name` / `git config user.email` 확인 — 올바른 author: `narafu <narafu@kakao.com>`

@docs/claude/commands.md
@docs/claude/architecture.md
@docs/claude/constraints.md
@docs/claude/testing.md
@docs/claude/kis-api.md
@docs/claude/docker-infra.md
@docs/claude/workflow.md
