# CLAUDE.md

메리츠증권·토스증권 API 기반 해외주식 자동 분할매매 서비스(KIS·바이낸스·업비트는 삭제 예정 — `docs/agents/decisions.md` 6절).
Java 25 + Spring Boot 4, Hexagonal Architecture + Spring Modulith, Gradle 서브프로젝트 `:api`·`:trading-core`·`:shared`(루트 패키지 `com.kista.api`·`com.kista.tradingcore`·`com.kista.shared`). 모듈 목록은 `docs/agents/architecture.md`가 SSOT. 이 파일은 진입점이고 공통 지식은 `docs/agents/`에 둔다.

## 코드 철학

- 재사용성·가독성·최신 문법 우선, 4-space 들여쓰기, 불변 값은 record, 생성자 주입, 가능하면 package-private
- **리팩토링 우선순위**: 비일관성(같은 문제를 다르게 푼 코드) > 모듈 간 중복 > 보일러플레이트 > OOP 위반 > 과설계. 비일관성·중복은 주제를 정해 서브프로젝트 전체를 가로로 grep해 비교한다(파일 하나만 읽고 판단 금지)
- **추출 기준**: 같은 로직 3곳 이상 + 같은 이유로 함께 바뀜 → 추출. 2곳이거나 바뀌는 이유가 다르면 유지. 해법이 여럿이면 기존 SSOT(constraints.md 규칙·공용 헬퍼)로 통일하고 새 패턴을 추가하지 않는다
- **보일러플레이트 즉시 수정**: 반복 생성자 호출, 중복 null guard, `with*()` 대체 가능 코드는 제안 없이 즉시 수정. 그 외 코드 철학에 맞지 않는 코드·어색한 위치의 클래스는 개선 제안
- **재발 방지·기록**: 반복 유형은 수정·제안과 함께 constraints.md 규칙 또는 ArchUnit 가드를 제안한다. 유지로 판단한 구조는 `decisions.md`에 이유를 남겨 재제안하지 않는다
- **동작 보존**: 리팩토링 커밋은 동작 변경 금지 — 대상 규칙이 테스트로 고정돼 있지 않으면 테스트부터 추가한다

## RESTful 설계 원칙

- URI: 명사·복수형, 계층으로 소속 표현(`/accounts/{id}/trading-cycles`)
- 응답: 도메인 객체 직접 반환 금지 → 전용 Response DTO(`from()` 팩토리)
- 생성 성공(201): `Location` 헤더에 신규 리소스 URI
- 상태 전이 행위 예외: `/pause`, `/resume` — PATCH + 서브 리소스 경로 허용

## 환경 설정

빌드·실행·테스트 명령은 `docs/agents/commands.md`(자동 로드).

필수 환경변수(누락·빈값이면 기동 실패): `JWT_SIGNING_KEY`, `AES_ENCRYPTION_KEY`, `INTERNAL_API_TOKEN`, `TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID`(root·trading-core 공통), `KAKAO_CLIENT_ID`(root만), `CORS_ALLOWED_ORIGINS`(쉼표 구분 — root는 항상, trading-core는 prod만). 선택: `ADMIN_KAKAO_IDS`(로그인 시 ADMIN 자동 승격 seed), `INTERNAL_API_BASE_URL`(root→trading-core, 기본 `http://localhost:8081`). 운영 키 목록의 원본은 `.env.example`.

로컬: `src/main/resources/application-local.yml`(.gitignored) — `jwt.signing-key` EC JWK, `spring.datasource.*`, `kakao.*` 필수

## 작업 방식

공통 작업 방식(서브에이전트, 커밋 전 검토, 자동 커밋, 모호하면 질문 등)은 전역 `~/.claude/CLAUDE.md`. 여기는 이 프로젝트 고유 규칙만.

- **직접 확인 우선**: 확인할 수 있는 것은 추정·"미검증"으로 남기지 않는다 — 라이브러리 동작은 gradle 캐시의 sources jar로, 트랜잭션·이벤트(EPR)·Redis 경로는 실 DB/Redis 통합 테스트(`*IT`)로, 고친 테스트가 회귀를 잡는지는 변이 확인으로, 운영 상태는 읽기 전용 조회(`ssh kista-server` 후 SELECT·`docker logs`)로. "미검증"은 쓰기·실계좌 주문·배포가 필요한 것만, 이유와 함께 보고
- **결정 기록 확인**: 구조 변경·리팩토링을 제안하기 전에 `docs/agents/decisions.md`를 대상 클래스·주제어로 grep한다. 유지·기각된 항목은 재제안하지 않는다
- **kista-ui 연계**: API 응답 형식·인증/토큰 흐름을 바꾸면 `../kista-ui/CLAUDE.md`를 Read(자동 로드 안 됨)
- **드리프트 동기화**: 코드 변경이 `README.md`(배지 버전, 아키텍처 그림의 클래스명, 스케쥴러 시각, 배포 방식)나 `flows.yml`(흐름·상태 전이·통신 경로·기능 목록 — `constraints.md` "아키텍처 맵 flows.yml 동기화")과 어긋나면 같은 작업에서 고친다. kista-ui README 영향도 확인
- **계획·spec 정리**: 구현·배포가 끝난 `docs/superpowers/plans/`·`specs/`의 계획과 spec은 마지막 커밋에서 삭제한다. 남길 불변식·결정은 해당 `docs/agents` 문서나 `decisions.md`로 옮긴다. 후속 작업이 남았으면 지우지 않는다
- Git 규칙(push·author·커밋 메시지·배포 불변식)은 `docs/agents/constraints.md` "Git 규칙"이 SSOT

@docs/agents/commands.md
@docs/agents/architecture.md
@docs/agents/constraints.md
@docs/agents/testing.md

## 문서 지도

모듈 디렉토리의 `CLAUDE.md`가 `docs/agents/modules/<루트>/<ID>.md`를 import한다(그 모듈 파일을 다룰 때 자동 로드). 나머지는 필요할 때 Read — 모두 `docs/agents/` 아래:
- `decisions.md` 하지 않기로 한 것(유지·기각·트리거 보류, own-type 허용 원장)
- `modules/tradingcore/trading-formulas.md` 매매·VR 공식 SSOT, `modules/tradingcore/dca.md` DCA
- `workflow.md` 매매·스케쥴러·주문 로직 인덱스(`workflow-batch`·`-ledger-price`·`-capper`·`-orders`)
- 증권사 API `vendors/meritz.md`·`vendors/toss.md`·`vendors/kis.md`·`vendors/binance.md`·`vendors/upbit.md`, `flyway.md`(이상 해당 디렉토리 작업 시 자동 로드)
- `local-dev.md` 로컬 개발, `docker-infra.md` 배포·운영 로그·DB 접속, `scheduler-time-table.md` 스케줄러 시각
- 전체 그림: `docs/architecture-map.md`, `build/architecture-map/index.html`(`ArchitectureMapTest` 생성, 원본 `flows.yml`)
