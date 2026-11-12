# 지침서 09. 문서 구조 개편과 불필요 파일 삭제

작성 2026-11-12 (Fable). 근거 측정: `findings/06-docs-and-dead-files.md`, `cross-repo.md` A5·A6·사용자 결정 2·6, `guides/00-roadmap.md` 5절(다른 세션이 이미 처리한 것). 참조 현황은 2026-11-12에 `git grep`으로 다시 확인했다.

**헌장 6항: 삭제와 대량 개편은 사용자 승인 뒤에 실행한다.** 8절이 승인 요청 목록이다.

## 1. 현재 구조와 문제

- 추적 중인 md 265개 약 6.2MB 가운데 **약 90%(5.6MB, 179파일)가 `docs/superpowers/plans/archive`·`specs/archive`** 다. 그 안의 경로 참조 4,288건이 지금 없는 파일을 가리킨다. 살아 있는 문서가 인용하는 것은 4개뿐이다.
- `docs/agents/code-quality-backlog.md`(972줄 140KB)의 항목 72개가 전부 종결됐다. 쓸모가 남은 부분은 "유지 목록" 약 125줄과 운영 규칙이다.
- `docs/history/`(241줄 72KB)와 `docs/reviews/`(353줄 47KB)는 전부 이력이다.
- **항상 로드되는 문서가 약 13,900토큰(추정)이다**: `CLAUDE.md` 7.6KB + `constraints.md` 17.6KB + `architecture.md` 9.6KB + `testing.md` + `commands.md`. trading 모듈을 열면 `trading.md` 34.9KB(약 10,500토큰)가 더해지고, matching·broker·stats까지 건드리면 최대 약 39,000토큰이다.
- 모듈 문서의 본문 대부분이 패키지 트리와 클래스 나열이다(`trading.md` 설명 67%, `broker.md` 87%). `architecture.md`가 스스로 "클래스·필드 상세는 코드가 SSOT"라고 적은 영역이다. 지침서 01~06이 실행되면 이 서술은 전부 틀려진다.
- 같은 사실이 3~7곳에 재서술돼 있다(Redis 스트림 컨슈머 7곳, ErrorCode·ProblemDetail 6곳, 시간 기준 5곳, Flyway expand/contract 5곳). 마감 배치 흐름은 그림(`architecture-map.md` L3)·산문(`workflow-batch.md`)·YAML(`flows.yml`)의 3중이다.
- 가드가 이미 강제하는 규칙의 설명이 `constraints.md`에 길게 남아 있다(가드 테스트 메서드 65개).
- `modules/tradingcore/dca.md`(13KB, 규칙 73%)는 어떤 `CLAUDE.md`도 import하지 않는다.
- 죽은 파일은 적다: `src/main/resources/static/index.html`, 완료된 `deploy/server/schema-reorg/` 4파일, jar에 번들되는 리소스 디렉토리 안 `CLAUDE.md` 2개, 의심 의존성 `gson`.
- 드리프트 자체는 낮다. 규칙 문서의 깨진 참조는 `kernel.md:3` 1건이다. 문제는 정확성이 아니라 **양과 중복**이다.

## 2. 목표 구조

### 2-1. 문서에 남기는 기준

문장 하나마다 두 질문을 한다. **둘 다 "예"일 때만 남긴다.**

1. 코드·테스트·가드 이름을 읽어서는 알 수 없는가? (이유, 외부 사양, 운영 절차, 실측 사례, 하지 않기로 한 것)
2. 모르면 잘못 고치게 되는가?

여기서 나오는 규칙:

- 패키지 트리, 클래스 목록, "A가 B를 호출한다" 식 흐름 서술은 쓰지 않는다. 흐름은 `flows.yml`(생성 맵) 하나다.
- 가드가 강제하는 규칙은 `규칙 한 줄 (가드: 테스트명)`으로만 적는다. 배경이 필요하면 그 테스트의 주석에 둔다.
- 사실 하나는 한 곳에만 쓴다. 다른 문서는 링크한다. 요약을 붙여 다시 쓰지 않는다.
- 이력(언제 무엇을 왜 옮겼나)은 git이 갖는다. 문서에는 "지금의 규칙"과 "뒤집지 말아야 할 결정과 그 이유"만 둔다.
- 날짜·커밋 해시·CQ 번호는 결정 기록에만 쓴다.

### 2-2. 디렉토리

```
CLAUDE.md                         진입점. 코드 철학, 필수 환경변수, 이 레포 고유 작업 규칙, 문서 지도
docs/agents/
  constraints.md                  [항상] 가드가 못 잡는 규칙 + 가드 목록(한 줄씩)
  architecture.md                 [항상] 프로세스·모듈 원칙, 통신 규칙, DB 스키마 소유. 모듈 표는 생성물 링크(지침서 05 뒤)
  commands.md  testing.md         [항상]
  decisions.md                    [필요 시] 결정 기록: 유지·기각·트리거 보류. 신설
  flyway.md  local-dev.md  docker-infra.md  scheduler-time-table.md
  workflow-*.md                   flows.yml이 못 담는 불변식·이유만
  vendors/meritz.md toss.md binance.md      외부 API 사양·실측(코드로 복원 불가)
  modules/<모듈>.md               규칙·불변식·이유. 모듈당 예산 2-4
docs/reference/quantstack/        공식 기준 문헌(유지)
docs/superpowers/fable-review/    이 검토(실행이 끝나면 9절에 따라 정리)
```

없어지는 것: `plans/archive`, `specs/archive`, `code-quality-backlog.md`, `own-type-ledger.md`(→ `decisions.md`), `docs/history/`, `docs/reviews/`, `docs/agents/README.md`, `kis-api.md`·`upbit-api.md`(지침서 04 W1 뒤), `docs/architecture-map.md`(지침서 05 단계 R 뒤, 그 전에는 L3과 배포 구간만 삭제).

### 2-3. `decisions.md` — 재론 금지 목록을 한 곳으로

지금 "다시 논의하지 않는다"가 네 곳에 흩어져 있다: backlog 유지 목록, `own-type-ledger.md`, memory `project_*` 5~6개, 각 모듈 문서의 "유지" 문단. 한 파일로 모은다.

형식(항목당 2~3줄):

```
### <결정 한 줄>
이유: <한두 문장>. 재검토 조건: <무엇이 바뀌면 다시 보는가 / 없음>. (YYYY-MM, 근거 커밋·CQ 번호)
```

- 넣는 것: `findings/05-frozen-decisions.md`의 항목 중 **이 검토의 재심에서 "유지"로 남은 것**과 트리거 조건부 보류. 각 지침서의 "재심한 과거 결정" 표가 판정 원본이다(01 3절, 02 3절, 03 3절, 04 5절, 05 11절, 06 4절, 07 3절, 08 5절, 10 3절). "뒤집는다"·"소멸"로 판정된 항목은 넣지 않는다.
- own-type 허용 원장은 `decisions.md`의 한 절로 옮긴다. 지침서 05 단계 X·R이 대부분을 소멸시키므로 그 뒤 남은 것만.
- 이 검토에서 새로 내린 사용자 결정(`PROGRESS.md` "사용자 결정 기록")도 넣는다: 브로커 구성, 토스 주문 유지, 시세·환율 토스 유지, 통합 방식 D1~D4, F7·F8, fida 별도 레포.
- 자동 로드하지 않는다. `CLAUDE.md`에 "구조 변경·리팩토링을 제안하기 전에 `decisions.md`를 대상 클래스·주제어로 grep한다" 한 줄을 둔다(지금의 "코드 품질 원장" 규칙을 대체).
- memory의 `project_done_do_not_redo.md`, `project_2026_07_11_full_review.md`, `project_review_refactor_2026_10_31.md`, `project_infinite_sizing_reviewed.md`, `project_fable_review.md`는 `decisions.md`로 옮긴 뒤 지우고 `MEMORY.md`에 `decisions.md` 포인터 1줄만 남긴다. `feedback_*`·`reference_*`와 운영 사실(`project_broker_strategy.md`, `project_deploy_lag_404.md` 등)은 그대로 둔다.

### 2-4. 크기 예산

| 대상 | 지금 | 예산 |
|---|---:|---:|
| 항상 로드 5파일 합 (`CLAUDE.md` + constraints + architecture + testing + commands) | 약 44KB | **26KB 이하** |
| `modules/tradingcore/trading.md` | 34.9KB | 10KB |
| `stats.md` / `platform.md` / `broker.md` / `dca.md` / `matching.md` | 20 / 16 / 13 / 13 / 10KB | 각 6KB(`platform.md`만 8KB) |
| 그 밖의 모듈 문서 | 3~10KB | 각 5KB |
| `trading-formulas.md` | 18.6KB | 지침서 08 T6이 다시 쓴다. 예산 없음(공식 SSOT) |
| `vendors/*.md` | 11~17KB | 예산 없음. 외부 사양은 줄이지 않고 날짜별 실측 서술만 "현상 → 대응" 한 줄로 압축 |

예산은 CI에서 잠근다. `.github/scripts/check-doc-budget.sh`(약 15줄, `wc -c`로 위 표를 검사)와 bats 1개. 문서가 다시 불어나는 것이 이 주제의 유일한 재발 경로다.

### 2-5. 자동 로드 사슬

- 모듈 디렉토리의 5줄짜리 포인터 `CLAUDE.md`(31개)는 유지한다. 동작하고 있고, 지침서 05 단계 R의 패키지 이동 때 함께 옮기면 된다.
- 리소스 디렉토리 안의 2개(`src/main/resources/db/migration/CLAUDE.md`, `trading-core/src/main/resources/db/migration-trading/CLAUDE.md`)는 jar에 들어간다. `.claude/rules/flyway.md`(frontmatter `paths`로 두 마이그레이션 디렉토리 지정, 본문은 `docs/agents/flyway.md` 내용 포인터)로 옮기고 지운다. 기존 `.claude/rules/java-encoding.md`와 같은 방식이다.
- `dca.md`: 지침서 03의 `kind/dca/` 패키지가 생기면 그 디렉토리의 포인터가 import한다. 그 전에는 `trading.md`에 "DCA 작업 시 `dca.md`를 Read" 한 줄.
- `CLAUDE.md`의 "참고 문서" 절과 `docs/agents/README.md`의 Contents 줄이 같은 목록이다. `README.md`를 지우고 `CLAUDE.md`의 문서 지도 하나만 둔다.

### 기각한 대안

- **archive를 별도 브랜치·레포로 옮긴다**: git 이력이 이미 보존한다. 삭제 직전 커밋에 태그 `archive/docs-2026-11`을 달면 찾는 비용도 같다.
- **archive를 `.claudeignore`류로 가리기만 한다**: grep·탐색 결과에 계속 걸리고(깨진 참조 4,288건) 레포 크기도 그대로다.
- **모듈 문서를 Javadoc·`package-info`로 옮긴다**: 블록 주석 금지 규칙과 충돌하고, 자동 로드 사슬을 새로 만들어야 한다.
- **문서 생성 자동화(클래스 목록을 코드에서 생성)**: 생성물은 코드를 읽는 것과 같은 정보다. 필요한 것은 Modulith Documenter의 모듈 관계도 하나다(지침서 05).
- **ADR 파일을 결정마다 하나씩**: 결정이 100개 이상이다. 한 파일에서 grep하는 쪽이 싸다.

## 3. 이미 처리된 것 (다시 하지 않는다)

`00-roadmap.md` 5절 기준. 실행 세션은 시작 전에 `git log --oneline -20 -- docs deploy`로 한 번 더 확인한다.

- `required-env` 경로 제거, readiness 계약 파일 폐지(`deploy/server/readiness` 삭제됨), Docker healthcheck 전환.
- 배포 구성도 링크 정정(`architecture-map.md` "L0-1 배포 구성"이 kista-infra README를 가리킴)과 postgres 런북 경로 정정.
- kista-ui·kista-infra 쪽 archive·계약 파일 정리는 각 레포 세션 소관이다.

## 4. 단계 1 — 지금 실행할 수 있는 것 (리팩토링과 독립)

### 4-1. 대량 삭제

| 대상 | 규모 | 삭제 전 처리 |
|---|---|---|
| `docs/superpowers/plans/archive/` | 110파일 4.78MB | 없음(살아 있는 참조는 history 1곳, 함께 삭제됨) |
| `docs/superpowers/specs/archive/` | 69파일 0.87MB | 인용 3곳을 먼저 흡수: ① `workflow-batch.md:18`·`deploy/server/README.md:129`가 인용하는 `2026-10-02-trading-batch-resume-design.md` → 불변식만 `workflow-batch.md`에 옮기고 인용 삭제(지침서 06 T8이 이 문서를 다시 쓰므로 최소한만) ② `workflow-orders.md:8`의 `2026-10-16-order-submission-claim-design.md` → 같은 방식 ③ `schema-reorg/RUNBOOK.md:3`은 아래에서 함께 삭제 |
| `docs/agents/code-quality-backlog.md` | 972줄 | 유지 목록(L578-704)과 "의심"(L22-38) 중 아직 유효한 것을 `decisions.md`로. 문서의 `CQ-nn` 인용(`stats.md`, `trading.md`, `trading-formulas.md`, `workflow-*.md`, `toss-api.md`) 정리. **Java 주석·테스트 `@DisplayName`·`V17__*.sql`의 `CQ-nn`은 그대로 둔다**(마이그레이션 파일은 수정 금지, 나머지는 라벨로 무해) |
| `docs/history/`, `docs/reviews/` | 3파일 119KB | `architecture.md:15`·`constraints.md:26`·`flyway.md`·`README.md`의 포인터 삭제 |
| `deploy/server/schema-reorg/` | 4파일 | `flyway.md:10`의 포인터를 "스키마 재편 이행은 2026-09 완료, 절차는 git 태그 `archive/docs-2026-11` 참고"로. `.github/tests/deploy-plan.bats:9`의 경로 문자열을 다른 `deploy/server/` 문서 경로로 교체. **`V1__init.sql` 2개의 주석(`deploy/server/schema-reorg/ 참고`)은 고치지 않는다** — 체크섬이 바뀌어 기동이 실패한다 |
| `docs/agents/own-type-ledger.md`, `docs/agents/README.md` | | 2-3·2-5대로 흡수 후 삭제. `constraints.md`의 own-type 게이트 절은 3줄로 |
| `src/main/resources/static/index.html` | 28줄 | 삭제 후 `/` 응답이 무엇이 되는지(404 또는 401) 확인하고, Caddy 헬스체크·외부 모니터가 `/`를 보지 않는지 `deploy/server/caddy/kista-api.caddy`와 kista-infra에 확인 |

삭제 커밋 직전 `git tag archive/docs-2026-11`을 단다(push는 사용자 요청 시).

`CLAUDE.md` 규칙 교체: "완료된 계획 archive 이동" → "**구현·배포가 끝난 계획과 spec은 마지막 커밋에서 삭제한다.** 남길 가치가 있는 불변식·결정은 해당 `docs/agents` 문서나 `decisions.md`에 옮긴다." 전역 memory의 archive 관련 서술도 맞춘다.

### 4-2. 모듈 문서 축소

2-1의 기준으로 모듈 문서를 다시 쓴다. 지금 해도 되는 이유: 지우는 것이 대부분이고, 클래스 나열은 뒤따르는 리팩토링 세션이 매번 고쳐야 하는 짐이다.

- 순서(큰 것부터): `trading.md` → `stats.md` → `platform.md` → `broker.md` → `dca.md` → `matching.md` → `admin.md` → `notify.md`(api) → 나머지.
- 문서마다: ① 문단별로 2-1의 두 질문에 답해 남길 것만 표시 ② 남긴 문단에서 클래스 나열을 지우고 규칙·이유만 ③ 다른 문서와 겹치는 사실은 한쪽을 링크로 ④ 가드가 있는 규칙은 한 줄 + 가드명 ⑤ `wc -c`로 예산 확인.
- 지우려는 문단에 "이유"가 섞여 있으면(예: "A를 B보다 먼저 저장한다 — 중간 실패 시 다음 틱이 재처리") 그 이유는 **해당 코드의 주석에 이미 있는지 확인**하고, 없으면 주석으로 옮긴 뒤 지운다.
- `stats.md`는 이력 32%·줄당 760바이트다. CQ 인용을 걷어내고 "평가액 계산 2종의 용도 차이", "평균 수익률 정의" 같은 정의만 남긴다.
- 벤더 문서는 `docs/agents/vendors/`로 옮기고 어댑터 디렉토리 포인터 `CLAUDE.md`의 경로를 고친다. binance·upbit의 같은 오류표는 업비트 삭제로 해소된다.

### 4-3. 항상 로드 문서 축소

- `constraints.md`(17.6KB → 약 9KB): 가드가 잠그는 규칙을 "가드 목록" 한 절로 모아 한 줄씩(E-2의 9건 + 가드 65개 중 문서에 서술된 것). 모듈 문서가 SSOT인 절(`GlobalExceptionHandler`, AES, 보안·필터, 시간 기준, Flyway)은 지금도 요약 + 포인터 형태이므로 요약을 1줄로 줄인다. "Git 규칙"의 배포 문단은 `docker-infra.md` 링크로.
- `architecture.md`(9.6KB → 약 6KB): 모듈 표 2개는 지침서 05 단계 R까지 두되 설명 열을 줄인다. "JPA Auditing"·"소유권 검증 패턴"은 `platform.md`·`trading.md`로 내린다(해당 모듈 작업 때만 필요).
- `commands.md`: `local-dev.md`와 줄 단위로 겹치는 dev 토큰·시드 절을 포인터 1줄로. `SCHEDULER_ENABLED` 원복 확인 규칙은 남긴다(사고에서 나온 규칙).
- `CLAUDE.md`: 첫 줄 "한국투자증권(KIS)·토스증권 API 기반"을 현재 구성으로(지침서 04 W1과 무관하게 지금도 메리츠·바이낸스가 주력), "참고 문서" 절을 문서 지도 표 하나로.

### 4-4. 작은 수정 (전수)

| 위치 | 수정 |
|---|---|
| `modules/shared/kernel.md:3` | 테스트명 `sharedkernel_must_not_depend_on_other_modules` → `kernel_must_not_depend_on_other_modules` |
| `docker-infra.md` "서버 배포 방식"의 `data_net` 문장 | "kista-api만" → 3 role 전부(kista-api·kista-scheduler·kista-trading) (A6) |
| 같은 절의 변경 경로 게이팅 문장 | config 판정 파일 목록을 `detect-deploy-scope.sh`의 현재 패턴과 맞추거나, 목록을 지우고 스크립트를 가리킨다(권장: 후자) (A6) |
| `docker-infra.md`·`deploy/server/README.md`의 `SERVER_HOST` secret 서술 | 앱 레포는 SSH 키·호스트 secret을 갖지 않는다. 예약 IP 문단의 "GitHub Secret(`SERVER_HOST`)" 언급 삭제. 수동 접속 예시의 `<SERVER_HOST>` 자리표시는 `ssh kista-server`로 (A6) |
| `docker-infra.md`의 `API_DOMAIN` 문장 | "Caddy 컨테이너가 kista-infra의 infra.env에서 받아 `kista-api.caddy` 사이트 주소로 쓴다. kista-api 쪽 env에는 없다"로 (결정 6) |
| `deploy/server/README.md:19-51,68-103,166-179`, `docker-infra.md` 백업·복구·서버 초기 설정 구간 | 삭제하고 kista-infra 문서 링크로. 줄 번호는 그동안 밀렸을 수 있으니 제목으로 찾는다: 서버 초기 설정, `.env 내용`, 백업·복구 (A5) |
| `.env.example` | `CMC_API_KEY=` 추가(선택 키 주석 포함). 운영 키 목록의 원본을 이 파일로 하고 `deploy/server/README.md`의 `.env 내용` 절은 이 파일을 가리킨다. KIS 키는 추가하지 않는다 (A5, 결정 3) |
| `docker-infra.md`의 OCI 인스턴스 사양 문단 | kista-infra 소관이다. 링크로 대체(인스턴스 구성은 infra 지침서 03이 바꾼다) |
| `binance-api.md` "성공 요청은 weight 0" | 주문 생성·취소류만 해당, `exchangeInfo`는 weight 20 (지침서 04 3절) |
| 메리츠 `mod_stpr_cls_code` 주석, 토스 "US MARKET 주문 미지원" 주석 | 스펙대로(미지원은 MOC·MOO). 코드 주석과 벤더 문서 양쪽 (지침서 04 3절) |
| `docs/architecture-map.md` "L3 마감 매매 배치" | 절 삭제(`flows.yml` 생성 맵이 원본) |
| `build.gradle.kts`의 `runtimeOnly(libs.gson)` | 제거해 보고 `./gradlew :test --tests '*ApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"`와 `./gradlew dependencies --configuration runtimeClasspath | grep gson`으로 확인. FCM 초기화가 전이 의존으로 gson을 받으면 삭제, 아니면 한 줄 주석으로 이유를 남기고 유지 |
| `mockwebserver3:5.5.0` 하드코딩 | 버전 카탈로그로 |
| `UserSummaryAdapter`(main 참조 0 의심) | 구현하는 포트의 소비처를 확인. 없으면 포트와 함께 삭제 |

### 4-5. 로컬 잔재 (커밋 대상 아님, 사용자 승인 후 삭제)

`bin/`(6MB, IDE 출력), `logs/*.tmp` 4개와 오래된 `.gz`, `trading-core/logs/`, `.superpowers/`(1.1MB, 완료된 SDD 원장), 빈 디렉토리 `/Users/phs/workspace/kista/kista-api-worktrees`, 레포 밖 `/Users/phs/workspace/kista/ARCHITECTURE.md`(Java 21·Boot 3·Fly.io로 적혀 현재와 무관). `.env`·`.env.production`·`application-local.yml`은 **건드리지 않는다.**

## 5. 단계 2 — 리팩토링을 따라가는 것

각 지침서의 문서 태스크가 그 지침서의 마지막 커밋에서 한다. 이 표는 누락 점검용이다.

| 리팩토링 | 고칠 문서 |
|---|---|
| 01 헥사고날 | `constraints.md` "포트 인터페이스 위치·네이밍"·"도메인 Command 명명"을 새 원칙(인터페이스를 두는 조건 3개)으로, 모듈 문서의 NamedInterface 서술, `.claude/agents/architecture-guardian.md`, `.claude/skills/new-adapter` |
| 02 영속·DTO | `constraints.md`의 persistence·RESTful 절, `CLAUDE.md` "RESTful 설계 원칙"의 Response DTO 문장, `testing.md`의 엔티티 저장 패턴 |
| 03 전략 축 | `trading.md`·`matching.md`, `dca.md`의 자동 로드 연결 |
| 04 브로커 | `kis-api.md`·`upbit-api.md`와 어댑터 포인터 삭제, `broker.md`, `README.md`, `flows.yml`, `constraints.md`의 Ticker enum 절(`KisExchangeRegistry`) |
| 05 통합 | `architecture.md` 전면(모듈 표 → 생성물 링크), `constraints.md`의 own-type·contract·내부 API 절, `docker-infra.md`, `commands.md`·`local-dev.md`(2-프로세스 부팅 절 삭제), `flyway.md`, `README.md`, `flows.yml`, `docs/architecture-map.md` 삭제 |
| 06 배치 | `workflow-batch.md`, `scheduler-time-table.md`, `flows.yml` |
| 07 테스트 | `testing.md`, `trading-testing.md`, `.claude/agents/test-writer.md` |
| 08 공식 | `trading-formulas.md`(08 T6), "변경 금지" 문구의 범위(기본값 제외) |
| 10 UI 계약 | `modules/api/web.md`의 오류 문구 규칙, `ui-api-principles.md`를 `docs/agents/`로 상시화(10 T9) |

전부 끝난 뒤 한 번: `README.md` 전체를 현재 구조와 대조(기술 스택 배지, 구조 그림, 스케줄러 시각, 배포 방식), kista-ui `README.md` 영향 확인, 예산 재측정.

## 6. 예상 감량과 위험

- 추적 파일: md 약 190개 삭제, **약 5.9MB(md 바이트의 95%)**. 비-md 6파일.
- 자동 로드: 항상 약 13,900 → 약 8,000토큰(추정). trading 작업 시 약 24,400 → 약 11,000토큰. 세션마다 반복되는 비용이라 누적 효과가 가장 크다.
- 위험 1: 지운 문단에만 있던 "이유"가 사라진다. 4-2의 "주석으로 옮긴 뒤 지운다"와 `decisions.md`가 막는다. 모듈 문서 축소는 문서별 커밋으로 두어 되돌릴 수 있게 한다.
- 위험 2: 삭제한 파일을 가리키는 포인터가 남는다. 7절의 grep으로 잡는다.
- 위험 3: `V1__init.sql` 주석을 고쳐 체크섬을 깨는 것. 4-1에 금지로 적었다. `.github/scripts/check-migrations.sh`가 잡는지 실행 세션이 확인한다.
- 위험 4: 유지 목록을 `decisions.md`로 옮기며 재심 결과와 어긋나게 옮기는 것(뒤집힌 결정을 "유지"로 남김). 각 지침서의 재심 표와 한 줄씩 대조한다. Opus가 한다.
- 위험 5: `static/index.html` 삭제로 `/`의 응답이 바뀐다. 외부 모니터가 `/`를 200으로 기대하면 알림이 난다. 4-1의 확인을 먼저 한다.

## 7. 태스크

| # | 내용 | 모델 | 완료 판정 |
|---|---|---|---|
| T1 | `decisions.md` 작성(2-3). 입력: `findings/05`, 각 지침서 재심 표, `PROGRESS.md` 결정 기록, backlog 유지 목록, own-type 원장 | Opus | 각 지침서 재심 표의 "유지" 항목이 전부 있고 "뒤집는다" 항목이 없다(대조표를 보고에 첨부) |
| T2 | spec 인용 3곳 흡수 → 태그 → 4-1의 삭제 → 포인터 정리 → `CLAUDE.md` 규칙 교체 | Sonnet | `git grep -nE "plans/archive|specs/archive|code-quality-backlog|own-type-ledger|docs/history|docs/reviews|schema-reorg" -- ':!docs/superpowers/fable-review' ':!*.sql'`가 0건. `.github/tests` bats 통과(명령은 `.github/CLAUDE.md`) |
| T3 | 4-4의 작은 수정 전부 | Sonnet(gson·`UserSummaryAdapter` 판정은 Opus) | 표의 행마다 처리 결과를 보고. 컨텍스트 로드 테스트 통과 |
| T4 | 모듈 문서 축소(4-2): `trading.md`, `stats.md`, `platform.md` | Opus | 예산 충족, 지운 문단 중 "이유"를 주석으로 옮긴 목록을 보고 |
| T5 | 모듈 문서 축소: 나머지와 벤더 문서 이동 | Sonnet(T4의 결과물을 본보기로 준다) | 예산 충족, 포인터 `CLAUDE.md` 경로가 전부 존재(`grep -rh "^@" --include=CLAUDE.md . | …`로 경로 존재 확인) |
| T6 | 항상 로드 문서 축소(4-3)와 리소스 디렉토리 `CLAUDE.md` 이전(2-5) | Opus | 5파일 합 26KB 이하. `./gradlew bootJar :trading-core:bootJar` 뒤 jar에 `CLAUDE.md`가 없다(`unzip -l … | grep -c CLAUDE.md` = 0) |
| T7 | `check-doc-budget.sh`와 bats, CI 연결 | Sonnet | 예산을 일부러 넘겨 실패하는지 확인 |
| T8 | memory 정리(2-3)와 로컬 잔재 삭제(4-5) — 사용자 승인 항목만 | Sonnet | `MEMORY.md` 줄 수, 삭제 목록 보고 |
| T9 | 단계 2 마감 점검(5절 표, README 대조, 예산 재측정) | Opus | 5절 표의 행마다 반영 여부 |

T1 → T2 순서는 지킨다(유지 목록을 옮기기 전에 backlog를 지우지 않는다). T3~T7은 독립이지만 같은 파일(`constraints.md`, `docker-infra.md`)을 건드리는 태스크는 한 세션에 묶는다: **T2+T3+T6**, **T4+T5**.

문서 전용 변경이라 커밋 전 코드 리뷰는 필요 없다. T3의 gson·`UserSummaryAdapter`·`index.html`과 T7은 코드 변경이므로 리뷰를 거친다.

## 8. 사용자 승인 요청 목록

| # | 항목 | 권고 |
|---|---|---|
| A | plans/specs archive 179파일 삭제(태그로 보존)와 "완료된 계획은 삭제" 규칙으로 교체 | 승인 |
| B | `code-quality-backlog.md`·`own-type-ledger.md`·history·reviews 삭제, `decisions.md` 한 파일로 | 승인 |
| C | `deploy/server/schema-reorg/` 삭제 | 승인(이행 완료, 롤백 창 종료) |
| D | 모듈 문서의 패키지 트리·클래스 나열 삭제와 크기 예산 CI 가드 | 승인 |
| E | memory의 재론 금지 5파일을 `decisions.md`로 옮기고 삭제 | 승인 |
| F | 로컬 잔재(4-5)와 레포 밖 `ARCHITECTURE.md` 삭제 | 승인. `.superpowers/`는 내용 확인 후 |
| G | `static/index.html` 삭제 | 4-1의 확인 뒤 승인 |

## 9. 이 검토 산출물의 정리 (실행이 전부 끝난 뒤)

`docs/superpowers/fable-review/`도 계획 문서다. 2-1의 기준을 자신에게 적용한다: 지침서가 전부 실행되면 `decisions.md`에 결정을 옮기고, `ui-api-principles.md`는 `docs/agents/`로 올리고(10 T9), `findings/`·`guides/`·`PROGRESS.md`·`cross-repo.md`는 삭제한다. 그 전까지는 이 디렉토리를 예산·삭제 대상에서 뺀다.

## 10. 하지 말 것

- 적용된 마이그레이션 파일(`V*.sql`)을 주석 포함 어떤 이유로도 고치지 않는다.
- 문서를 줄이면서 규칙의 **내용**을 바꾸지 않는다. 내용 변경은 해당 리팩토링 지침서의 일이다.
- 지운 클래스 나열을 "요약"으로 다시 쓰지 않는다.
- `trading-formulas.md`와 `docs/reference/quantstack/`은 이 지침서에서 건드리지 않는다.
- 벤더 문서의 외부 사양(TR ID, 오류 코드, 필드 의미, 실측으로 알아낸 동작)을 지우지 않는다.
- `.env*`, `application-local.yml`, 시크릿 파일을 읽거나 지우지 않는다.
- kista-ui·kista-infra의 문서를 이 세션에서 고치지 않는다. 필요한 것은 `cross-repo.md`에 적고 해당 세션에 넘긴다.

## 11. 다른 지침서·레포와의 관계

- 단계 1은 어떤 지침서보다 먼저 해도 된다. **먼저 할수록 좋다**: 뒤따르는 실행 세션 전부의 자동 로드 비용이 줄고, 모듈 문서의 클래스 나열을 리팩토링마다 고치는 일이 없어진다.
- 01~08·10: 5절의 표.
- kista-infra: A5(서버 초기 설정·백업 복구 문서의 원본은 infra), 결정 3(운영 키 목록 원본은 앱 레포 `.env.example`). 링크 대상 경로는 infra 세션에 확인한다.
- kista-ui: `README.md` 영향만 확인.
