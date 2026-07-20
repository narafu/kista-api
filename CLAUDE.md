# CLAUDE.md

한국투자증권(KIS)·토스증권 API 기반 해외주식 자동 분할매매 서비스.
Java 21 + Spring Boot 3 기반 Hexagonal Architecture.

이 파일은 Claude Code 진입점이다. Codex 진입점은 `AGENTS.md`이며, 실제 프로젝트 공통 지식은 `docs/agents/`에 둔다.

## 코드 철학

- 반복 코드보다 재사용성·가독성·최신 문법을 우선
- 4-space 들여쓰기, 불변 값은 record, 생성자 주입, 가능하면 package-private
- 코드 철학에 맞지 않는 코드·어색한 위치의 클래스를 발견하면 개선/리팩토링 제안
- **보일러플레이트 즉시 수정**: 반복 생성자 호출, 중복 null guard, `with*()` 대체 가능 코드 발견 시 제안 없이 즉시 수정

## RESTful 설계 원칙

- URI: 명사·복수형, 계층으로 소속 표현 (`/accounts/{id}/trading-cycles`)
- 응답: 도메인 객체 직접 반환 금지 → 전용 Response DTO (`from()` 팩토리)
- 생성 성공(201): `Location` 헤더에 신규 리소스 URI 포함
- 상태 전이 행위 예외: `/pause`, `/resume` — PATCH + 서브 리소스 경로 허용

## 환경 설정

빌드·실행·테스트 명령어는 `docs/agents/commands.md`(자동 로드) 참고.

필수 환경변수: `JWT_SIGNING_KEY`, `AES_ENCRYPTION_KEY`, `ADMIN_KAKAO_IDS` (쉼표 구분 카카오 ID — ADMIN 자동 승격), `INTERNAL_API_TOKEN` (서버 간 내부 인증, 미설정 시 `/api/internal/**` 항상 401), `CORS_ALLOWED_ORIGINS` (쉼표 구분, 기본값 `http://localhost:3000`)

로컬 환경: `src/main/resources/application-local.yml` (.gitignored) — `jwt.signing-key` EC JWK, `spring.datasource.*`, `kakao.*` 설정 필수

## 작업 방식

- 독립적으로 병렬 처리 가능한 작업은 서브에이전트를 적극 활용
- 요청이 모호하면 가정하고 진행하지 말고 먼저 질문
- **기존 오류 발견 시 즉시 수정**: 컴파일 오류·타입 오류·명백한 버그는 현재 작업과 무관해도 즉시 수정 (범위가 넓으면 먼저 언급)
- **의심 사항 즉시 제보**: 버그·설계 이상·불일치가 의심되면 "확실하지 않지만 X가 이상해 보입니다" 형태로 바로 언급
- **시간 기준 정책**: 거래일은 전 구간 KST 단일 기준(변환 없음), `release_date`는 FIDA 발행일 원본, US 기준 외부 데이터만 어댑터 내부 `UsTradeDates` 변환 (`docs/agents/constraints.md` 참고)
- **작업 완료 후 자동 커밋**: 요청된 작업 완료 시 스스로 커밋 생성
- **kista-ui 연계 작업 감지 시**: API 응답 형식 변경·인증/토큰 흐름 등이면 즉시 `../kista-ui/CLAUDE.md`를 Read로 확인 (자동 로드 안 됨)
- Git 규칙(push·author·커밋 메시지)은 `docs/agents/constraints.md` 참고

@docs/agents/commands.md
@docs/agents/architecture.md
@docs/agents/constraints.md
@docs/agents/testing.md

## 운영 도구

- **API 운영 로그**: flyio-cli — `fly logs -a kista-api` (상세 명령어 → `docs/agents/docker-infra.md`)
- **DB 작업**: supabase-cli — `supabase db query --linked` (상세 명령어 → `docs/agents/docker-infra.md`)

## 참고 문서 (필요시 Read)
- **매매·스케쥴러·주문 로직 작업 시 필수 Read**: `docs/agents/workflow.md` — 스케쥴러 실행 흐름, MarketSession(DIRECT|BLOCKED), BuyOrderPriceCapper 보정 주문, orders/cycle_position 기록 테이블 구분
- KIS API 작업: `docs/agents/kis-api.md` — TR ID, 오류 코드, 응답 필드, 어댑터 패턴
- 토스증권 API 작업: `docs/agents/toss-api.md`
- Docker/배포/인프라 작업: `docs/agents/docker-infra.md`
