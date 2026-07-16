# kista-api 코어

KIS·토스증권 REST API 기반 해외주식(SOXL/TQQQ 등) 자동 분할매매 SaaS.
Java 21 + Spring Boot 3 + Hexagonal Architecture (ArchUnit이 빌드 시 레이어 의존 강제 검증).

## SSOT 안내

프로젝트 공통 지식의 SSOT는 `docs/agents/`다. 메모리에 세부 내용을 중복 기록하지 않는다
(2026-06 메모리 전면 stale 사고 후 포인터 체계로 전환 — 중복 기록은 재-stale를 유발).

- 패키지 맵·레이어 규칙·컨트롤러/어댑터/전략 패턴: `docs/agents/architecture.md`
- 구현 제약·매매/VR 공식·Flyway·JPA·tradeDate 변환: `docs/agents/constraints.md`
- 빌드/실행/테스트 명령어·dev 토큰: `docs/agents/commands.md`
- 테스트 패턴 (@WebMvcTest·Mockito·통합): `docs/agents/testing.md`
- 스케쥴러·매매 실행 흐름·주문 예산 배정: `docs/agents/workflow.md`
- KIS API 패턴·오류 코드: `docs/agents/kis-api.md` / 토스증권: `docs/agents/toss-api.md`
- Docker·Fly.io 배포·Supabase 운영: `docs/agents/docker-infra.md`

## 보조 메모리

- 작업 완료 체크리스트(동시 수정 필요 파일 쌍): `mem:task_completion`
- 메모리 관리 원칙: `mem:memory_maintenance`
