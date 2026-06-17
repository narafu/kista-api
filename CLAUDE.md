# CLAUDE.md

한국투자증권(KIS) REST API를 통한 SOXL 분할매매 자동화 서비스.
Java 21 + Spring Boot 3 기반 Hexagonal Architecture.

## 코드 철학

- 반복 코드보다 재사용성·가독성·최신 문법을 우선
- 디자인 패턴, 객체지향, SOLID 원칙 준수
- Hexagonal Architecture, EDD, DDD, CQRS, TDA 설계 지향
- 코드 철학에 맞지 않는 코드베이스를 발견하면 개선을 제안할 것 — 다른 작업 중 우연히 발견한 경우도 포함
- 개념적으로 어색한 위치에 있는 클래스(예: 이벤트 타입이 서비스 구현 패키지에 혼재, 도메인 타입이 어댑터 패키지에 위치 등)를 발견하면 리팩토링을 제안할 것

## RESTful 설계 원칙

- URI: 명사·복수형 (`/accounts`, `/trading-cycles`), 계층으로 소속 표현 (`/accounts/{id}/trading-cycles`), 동사 금지
- HTTP 메서드: GET=읽기(안전·멱등) / POST=생성 / PUT=전체교체(멱등) / PATCH=부분수정 / DELETE=삭제(멱등)
- 상태 코드: 200·201·204 성공 / 400·401·403·404·409·422·429 클라이언트 오류 / 503 외부 서비스 오류
- 무상태(Stateless): 서버 세션 금지 — 인증 상태는 JWT Bearer로 전달
- 응답: 도메인 객체 직접 반환 금지 → 전용 Response DTO 사용 (`from()` 팩토리)
- 생성 성공(201): `Location` 헤더에 신규 리소스 URI 포함
- 행위 표현 예외: `/pause`, `/resume` 등 상태 전이는 PATCH + 서브 리소스 경로 허용

## 빠른 시작

```bash
./gradlew bootRun --args='--spring.profiles.active=local'  # 로컬 실행
./gradlew test                                              # 전체 테스트
./gradlew compileJava                                       # 컴파일 검증
docker compose up -d postgres                               # DB만 기동
```

필수 환경변수: `JWT_SIGNING_KEY`, `AES_ENCRYPTION_KEY`, `ADMIN_KAKAO_IDS` (쉼표 구분 카카오 ID — ADMIN 자동 승격)

## 작업 방식

- 독립적으로 병렬 처리 가능한 작업은 서브에이전트를 적극 활용
- 요청이 모호하거나 구체적 지침이 필요한 경우 가정하고 진행하지 말고 먼저 질문
- **기존 오류 발견 시 적극 수정**: 코드베이스 탐색 중 발견한 컴파일 오류, 타입 오류, 명백한 버그는 현재 작업과 무관하더라도 별도 확인 없이 즉시 수정할 것 (범위가 넓으면 먼저 언급)
- **의심 사항 적극 제보**: 확실하지 않더라도 버그·설계 이상·불일치가 의심되면 묻어두지 말고 바로 언급할 것 — "확실하지 않지만 X가 이상해 보입니다" 형태로 제보
- **리팩토링 필요 적극 제보**: 코드 중복·불필요한 복잡도·Hexagonal 계층 위반·SOLID 원칙 불일치 등 리팩토링이 필요해 보이면 현재 작업과 무관하더라도 적극 언급할 것 (즉시 수정은 작업 완료 후 별도 진행)
- **작업 완료 후 자동 커밋**: 요청된 작업이 완전히 완료되면 스스로 커밋을 생성할 것 — 사용자가 별도로 "커밋해줘"라고 요청하지 않아도 됨

## Git 규칙
- `git push`는 사용자가 명시적으로 요청할 때만 실행 — 요청 없이 자동 푸시 금지, 요청하면 즉시 실행
- 커밋 전 `git config user.name` / `git config user.email` 확인 — 올바른 author: `narafu <narafu@kakao.com>`

@docs/claude/commands.md
@docs/claude/architecture.md
@docs/claude/constraints.md
@docs/claude/testing.md
@docs/claude/workflow.md

## 운영 도구

- **API 운영 로그**: flyio-cli — `fly logs -a kista-api` (상세 명령어 → `docs/claude/docker-infra.md`)
- **DB 작업**: supabase-cli — `supabase db query --linked` (상세 명령어 → `docs/claude/docker-infra.md`)

## 참고 문서 (필요시 Read)
- KIS API 작업: `docs/claude/kis-api.md` — TR ID, 오류 코드, 응답 필드, 어댑터 패턴
- 토스증권 API 작업: `docs/claude/toss-api.md`
- Docker/배포/인프라 작업: `docs/claude/docker-infra.md`
