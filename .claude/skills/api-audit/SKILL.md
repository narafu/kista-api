---
name: api-audit
description: 엔드포인트·모듈·클래스 또는 현재 diff 단위로 API 미흡점을 고정 체크리스트로 점검하고 file:line 표로 보고한다. 기본은 조사만(수정 없음). 예: /api-audit "POST /api/accounts/{id}/strategies", /api-audit tradingcore.trading --focus 주문안전,조용한실패, /api-audit --diff
user-invocable: true
---

인자: `<대상> [--focus <관점,...>] [--fix]` 또는 `--diff [--focus ...] [--fix]`

- `<대상>`: 엔드포인트(`POST /api/accounts/{id}/strategies`), 모듈 ID(`tradingcore.trading`, `api.finance`), 패키지·클래스·파일 중 하나. 생략 시 사용자에게 물어본다.
- `--diff`: 현재 브랜치 변경분(`git diff main...HEAD` + 미커밋 변경)만 대상으로 한다. 커밋 전 검수용.
- `--focus`: 아래 관점 번호 또는 이름(예: `보안,시간기준`). 생략 시 기본 관점 전부.
- `--fix`: 지정 시에만 보고 후 수정까지 진행. 기본은 **조사만 하고 코드는 수정하지 않는다**.

## 1. 대상 범위 확정

- 엔드포인트면 컨트롤러 → usecase → service → port → adapter 순으로 호출 체인을 따라 실제 실행되는 클래스를 목록화한다. root 어댑터가 내부 API(`/api/internal/**`)·Redis로 trading-core를 부르면 경계 너머 핸들러까지 따라간다.
- 모듈이면 `docs/agents/modules/<루트>/<ID>.md`를 먼저 읽고 공개 NamedInterface·adapter 중심으로 목록화한다.
- 목록을 먼저 짧게 보여주고 점검한다. 파일이 15개를 넘으면 사용자에게 좁힐지 물어본다.

레이어 경계(Hexagonal·Modulith·contract)는 체크리스트에서 보지 않는다 — ArchUnit이 강제하므로 범위에 해당하는 서브프로젝트의 architecture 테스트를 1회 실행해 결과만 첨부한다(`:test --tests 'com.kista.api.architecture.*'` / `:trading-core:test --tests 'com.kista.tradingcore.architecture.*'`).

## 2. 체크리스트

규칙 원문은 문서에 있다 — 여기 복사하지 말고 해당 절을 읽고 대조한다. `constraints.md`는 `docs/agents/constraints.md`.

### 기본 관점

| # | 관점 | 기준 문서 / 확인 방법 |
|---|---|---|
| 1 | 주문안전 | `constraints.md` "@Transactional 내부 외부 시스템 호출 금지"·"Virtual Thread" 절, `docs/agents/workflow.md`(재기동 종료·재개, 예산 배정). 트랜잭션 안 증권사·텔레그램 호출, 트랜잭션 없는 발행 지점의 `AFTER_COMMIT` 리스너(이벤트 유실), `TradingBatchResumer` 재개·재시도 시 같은 거래일 중복 주문 가능성, 예산·예약량 판정 전 접수, `@Async`/`CompletableFuture` |
| 2 | 조용한실패 | `constraints.md` "모듈 경계 own-type" 절의 복제본 필수 조건(빈 결과 실패 양상 3종). 빈 Optional·빈 리스트를 정상 응답으로 흘려 기능이 멈추는 경로, catch 후 로그·`errorReportPort`·`TradingErrorEvent` 없이 삼키는 경로, 배치에서 한 계좌 실패가 다른 계좌를 멈추는지, 알림이 조건 미충족으로 무음 skip되는 경로 |
| 3 | 보안 | `constraints.md` "계좌번호 마스킹"·"상태 종속 민감 필드 마스킹"·"AES-256 암호화 위치"·"서버 간 내부 인증" 절, `architecture.md` "소유권 검증 패턴". `requireOwnedAccount`/전략 accountId 대조 누락, `@AuthenticationPrincipal UUID` 외 userId 추출, 마스킹 우회, 로그·예외 메시지에 시크릿·계좌번호 노출 |
| 4 | 영속성 | `constraints.md` "Flyway"·"소프트 삭제"·"Java Enum ↔ DB 컬럼 매핑"·"AES-256 암호화 컬럼 크기" 절, `architecture.md` DB 스키마 5분리. Entity `nullable`과 마이그레이션 NOT NULL 대조(validate가 못 잡음), nativeQuery·JdbcTemplate의 `deleted_at IS NULL` 누락, `@Table(schema)` 누락, 다른 서비스 소유 스키마 참조 |
| 5 | 시간기준 | `constraints.md` "시간 기준 정책" 절. 인라인 `.minusDays(1)`/`.plusDays(1)`, `ZoneOffset.UTC` 자정 경계, `DstInfo.nextTradeDate()` 대신 직접 계산, `release_date`를 거래일로 취급. 대상에서 grep으로 먼저 훑는다 |
| 6 | 외부계약 | CLAUDE.md "RESTful 설계 원칙", `constraints.md` "GlobalExceptionHandler 자동 예외 처리" 절. 도메인 객체 직접 반환, 201 `Location` 누락, ProblemDetail `detail`에 영어·내부 정보, UI가 분기해야 하는데 `ErrorCode` 없음, Response DTO 필드 변경 시 "UI 영향" 표시(`../kista-ui` 소비처 grep) |

### 조건부 관점

| # | 관점 | 켜지는 조건 | 확인 방법 |
|---|---|---|---|
| 7 | 테스트공백 | `--diff`일 때만 | 바뀐 분기·계산에 대응 테스트가 있는지. `docs/agents/testing.md` 패턴(`@WithMockUser` 금지, `SAME_THREAD`, UUID principal) 위반 |
| 8 | 드리프트 | `--diff`일 때만 | `constraints.md` "아키텍처 맵 flows.yml 동기화" 절, CLAUDE.md README 드리프트 규칙, 해당 모듈 문서(`docs/agents/modules/...`)가 변경과 어긋나는지 |
| 9 | 코드철학 | `--focus`로 지정할 때만 | CLAUDE.md "코드 철학", `constraints.md` "주석 규칙"·"도메인 Command 명명"·"application/port/output/ 네이밍" 절. setter·getter 남용, 보일러플레이트, 명명 |

## 3. 보고 형식

```
| # | 위치(file:line) | 관점 | 현상 | 수정 제안 | 심각도(상/중/하) |
```

- 심각도: 상 = 돈·주문에 영향, 데이터 유실·오염, 권한 우회, 장애를 조용히 숨김 / 중 = 계약·일관성 결함, 운영 중에야 드러날 오류 / 하 = 정리·리팩토링 후보
- 의도된 예외로 판단해 제외한 항목은 표 아래 "제외(사유)"로 따로 적는다 — 조용히 빼지 않는다
- 정적 분석만 했는지, 테스트 실행(architecture 테스트 등)까지 했는지 마지막 줄에 명시한다
- 같은 문제가 대상 밖에도 반복되는 것으로 보이면 "전역 후보"로 표시한다
- 6번 관점에서 응답 형식이 바뀌면 "UI 영향" 열거(kista-ui 파일 경로)를 표 아래에 붙인다

## 4. `--fix`일 때

보고 후 상/중 항목만 수정한다(하는 목록만). 공용 헬퍼(`com.kista.shared.*`, 포트 default 메서드 등)를 바꿔야 하면 먼저 그것부터 고치고 소비처를 맞춘다. Flyway 변경이 필요하면 `flyway-migration` 스킬을 따른다. 수정 후 관련 테스트만 `--tests`로 좁혀 돌리고(서브프로젝트 접두사 필수), 커밋 전 검토자 검수 규칙을 따른다.
