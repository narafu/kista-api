# 06. 문서·불필요 파일 전수 조사 (읽기 전용)

> 조사일 2026-11-11, 대상 kista-api. 제외: `docs/superpowers/fable-review/`, `docs/reference/quantstack/`. 토큰은 추정치(한글 1자≈1토큰, 그 외 3.5자≈1토큰; 실제는 최대 1.5배). 스크립트: scratchpad `t06/`.

## A. 문서 지도

### A-1. 디렉토리별 md (git 추적, 265개 = 조사 대상)

| 디렉토리 | 개수 | 줄 | 바이트 |
|---|---:|---:|---:|
| docs/superpowers/plans/archive | 110 | 81,226 | 4,777,398 |
| docs/superpowers/specs/archive | 69 | 9,529 | 871,871 |
| docs/agents (루트 21개) | 21 | 2,249 | 353,663 |
| docs/agents/modules/tradingcore | 12 | 398 | 134,891 |
| docs/history | 1 | 241 | 72,115 |
| docs/agents/modules/api | 7 | 147 | 45,223 |
| docs/reviews | 2 | 353 | 47,348 |
| docs/agents/modules/shared | 3 | 71 | 25,236 |
| docs (architecture-map.md) | 1 | 283 | 17,007 |
| deploy/server (+schema-reorg) | 2 | 301 | 29,579 |
| 루트 (CLAUDE.md, README.md) | 2 | 130 | 13,013 |
| .claude/skills·agents·rules | 8 | 347 | 24,000 |
| 모듈 CLAUDE.md 포인터(5줄짜리) | 31 | 약 155 | 약 7,000 |

핵심: **archive 2곳이 md 바이트의 약 90%(5.6MB / 약 6.2MB)**. 줄은 짧은데 바이트가 큰 docs/agents는 한 줄이 매우 긴 문서(밀도 높음) — `modules/tradingcore/stats.md` 30줄 20KB, `trading.md` 90줄 35KB.

### A-2. 자동 로드 사슬

| 로드 조건 | 파일 | 줄 | 토큰(추정) |
|---|---|---:|---:|
| 항상 | /kista/CLAUDE.md (상위) | 22 | 245 |
| 항상 | kista-api/CLAUDE.md | 63 | 2,358 |
| 항상 (@import) | docs/agents/commands.md | 48 | 1,268 |
| 항상 (@import) | docs/agents/architecture.md | 72 | 2,846 |
| 항상 (@import) | docs/agents/constraints.md | 141 | 5,375 |
| 항상 (@import) | docs/agents/testing.md | 50 | 1,806 |
| **항상 합계** | | **396줄** | **약 13,900 (범위 14k~20k)** |
| `*.java` 편집 시 | .claude/rules/java-encoding.md | | 약 200 |
| stats 컨트롤러 편집 시 | .claude/rules/statistics-controllers.md | | 약 260 |

(전역 ~/.claude/CLAUDE.md·MEMORY.md는 별도로 항상 로드, 이 수치에 미포함.)

모듈 디렉토리 CLAUDE.md는 모두 5줄짜리 포인터이며 `@` 상대경로로 하나의 문서만 import한다(디렉토리 파일 접근 시 로드):

| 디렉토리 | import 대상 | 토큰 |
|---|---|---:|
| tradingcore/trading (main) | modules/tradingcore/trading.md | 10,504 |
| tradingcore/trading (test) | modules/tradingcore/trading-testing.md | 1,535 |
| tradingcore/matching | matching.md | 3,032 |
| tradingcore/broker | broker.md | 3,907 |
| tradingcore/stats | stats.md | 6,250 |
| tradingcore/privacy·account·notify·marketcalendar·web | 각 모듈 md | 1,380 / 1,749 / 1,228 / 538 / 1,048 |
| broker/adapter/out/{kis,toss,meritz,binance,upbit} | docs/agents/*-api.md | kis 18KB, toss 17KB, binance 13KB, meritz 12KB, upbit 9KB (바이트) |
| api/{admin,benchmark,finance,market,notify,user,web} | modules/api/*.md | 4.7~9.6KB |
| shared/{contract,kernel,platform} | modules/shared/*.md | platform 16KB |
| db/migration, db/migration-trading | docs/agents/flyway.md | 약 3,000 |
| .github | (`.github/CLAUDE.md` 11줄 직접 기술) | |

**trading 모듈 작업 시 추가 로드**: 기본 13.9k + trading.md 10.5k = **약 24.4k**. 테스트 디렉토리 포함 시 +1.5k, `trading` 하위에서 matching·broker·stats도 건드리면 +3.0k/+3.9k/+6.3k → 최대 약 39k.

**자동 로드 안 되는 문서(필요 시 직접 Read)**: modules/tradingcore/dca.md(13KB), trading-formulas.md(18KB), own-type-ledger.md, workflow*.md(5개), local-dev.md, docker-infra.md(32KB), scheduler-time-table.md, code-quality-backlog.md(140KB), history/, reviews/, architecture-map.md, README.md. 참고: dca.md는 어떤 CLAUDE.md도 import하지 않음(고아 — 해당 모듈 작업 시 자동 로드 안 됨).

## B. 중복·분산

방법: 40자 shingle 겹침 스크립트(`t06/dup.py`)로 md 간 문자 단위 복제를 찾고(결과: 거의 없음 — 문장 복사보다 "같은 사실의 재서술"이 대부분), 핵심 용어 전수 grep(`FilterRegistrationBean` 등)으로 보완. 대조 범위: constraints.md ↔ modules/*.md ↔ CLAUDE.md ↔ README.md ↔ architecture-map.md ↔ workflow*.md.

**총평**: 문장 단위 복붙은 8건 이하(아래). 대신 (1) constraints.md가 "요약 + → 상세는 모듈 md" 형태로 이미 SSOT 포인터화돼 있어 구조는 양호, (2) 같은 사실이 3~5곳에 서로 다른 말로 재서술된 주제가 12건, (3) 가장 큰 중복은 문서 간이 아니라 **문서 ↔ 코드/가드**(E절)다.

### B-1. 같은 규칙·설명이 2곳 이상 (더 상세한 쪽 = SSOT 후보)

| # | 주제 | 위치 (파일:줄) | 더 상세한 쪽 | 비고 |
|---|---|---|---|---|
| 1 | 필터 이중 등록 방지 `FilterRegistrationBean.setEnabled(false)` | constraints.md:110 ↔ modules/shared/platform.md:20 | platform.md | constraints는 1줄 요약, 의도된 요약+상세 구조 |
| 2 | AES 암호화 컬럼 raw INSERT는 `crypto.encrypt` | constraints.md:105 ↔ platform.md:33 | platform.md | 거의 동일 문장 |
| 3 | 시간 기준 정책(KST 단일) | CLAUDE.md, constraints.md:2곳, platform.md, kernel.md, docker-infra.md | platform.md | 5개 문서에 분산 |
| 4 | Flyway expand/contract | constraints.md, flyway.md:17, docker-infra.md:14, shared/contract.md:14, .github/CLAUDE.md:8 | flyway.md + docker-infra.md | 5곳. flyway.md가 SSOT면 나머지 포인터화 가능 |
| 5 | `REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART` 소유 | constraints.md, docker-infra.md, tradingcore/notify.md, history | docker-infra.md | constraints는 규칙만 |
| 6 | `stop_grace_period`·단일 인스턴스 배포 | constraints.md, docker-infra.md | docker-infra.md | `compose-invariants.bats`가 강제 |
| 7 | Redis 스트림 컨슈머 `RedisStreamSubscriber`/복구 `@Scheduled` | constraints.md:23, scheduler-time-table.md:55, platform.md, notify.md(api), admin.md, trading.md, docker-infra.md | scheduler-time-table.md | 7곳 |
| 8 | `TelegramHttpClient` 통합 설명 | api/notify.md:20 ↔ shared/platform.md:10, tradingcore/notify.md, admin.md | platform.md | api/notify.md에 5회 |
| 9 | 공유 enum(StrategyType 등) nested 재도입 금지 | constraints.md(User/Account/Strategy 공유 enum), trading.md:54, kernel.md, history:47 | constraints.md | 4곳 동문 |
| 10 | `own-type` 게이트 | constraints.md(5회), own-type-ledger.md(10), architecture.md(2), CLAUDE.md(2), flyway/trading/kernel/admin/market/web.md 각 1, history(16) | own-type-ledger.md | 원장+게이트 설명이 constraints에도 남음 |
| 11 | 로컬 dev 토큰·`seed.sh` 절차 | commands.md:22,27 ↔ local-dev.md:5,21 (줄 그대로 복제) | local-dev.md | commands.md(항상 로드) 쪽을 포인터 1줄로 줄일 수 있음 |
| 12 | 브로커 API 문서 공통 구조 | kis-api.md:10,73,89 ↔ meritz-api.md:7,64,84; binance-api.md:3,7,38 ↔ upbit-api.md:3,7,43 (429/418 표 동일) | 벤더별 | binance·upbit 오류표 동일 행, `SERVER_EGRESS_IP` 안내 공용 |
| 13 | ErrorCode(kista-ui 계약)·ProblemDetail 규칙 | constraints.md, api/web.md(4), tradingcore/web.md(2), platform.md, architecture.md, trading.md | api/web.md | 6곳 |
| 14 | `AccountNumberMasker` | constraints.md(2), shared/kernel.md | kernel.md | |
| 15 | Virtual Thread/@Async 금지 | constraints.md(2), workflow-batch.md | constraints.md | |
| 16 | `OwnedStrategy`·소유권 검증 | architecture.md, trading.md, code-quality-backlog.md | architecture.md | |
| 17 | `flows.yml` 동기화 규칙 | CLAUDE.md(2), constraints.md(3), README.md, architecture-map.md(4), backlog | constraints.md | CLAUDE.md는 포인터라 정상, 나머지는 재서술 |
| 18 | `*-api.md` 자동 로드 목록 | CLAUDE.md:60 등 5줄 ↔ tradingcore/broker.md:13 ↔ docs/agents/README.md contents 줄 | CLAUDE.md | "adapter/out/X 작업 시 자동 로드" 문구가 3곳에 반복 |
| 19 | 매매·VR 공식 Read 의무 | CLAUDE.md:52 ↔ trading.md:3 | trading.md | 의도적 이중 안전장치 |
| 20 | 아키텍처(모듈 목록·SSOT) | architecture.md ↔ architecture-map.md(L1·L1.5, "SSOT는 architecture.md"라 명시) ↔ README.md "구조" ↔ docs/agents/README.md | architecture.md | 맵은 그림+링크만이라는 자체 규정, 준수 중 |
| 21 | 마감 배치 흐름 | architecture-map.md L3(mermaid, :250~) ↔ workflow-batch.md:5~12 ↔ flows.yml ↔ scheduler-time-table.md | workflow-batch.md | 3중 서술 (그림·산문·YAML 수기) |
| 22 | 환경변수·로컬 설정 | CLAUDE.md:28 ↔ README.md:46 | CLAUDE.md | README가 포인터라 정상 |
| 23 | 모듈 패키지 트리 | 각 modules/*.md의 트리 ↔ 실제 코드 디렉토리 | 코드가 SSOT | E절 참조 |
| 24 | `web` 모듈 앱셸 설명 | api/web.md:3 ↔ tradingcore/web.md:4 ↔ architecture.md:54 | 각 web.md | 대칭 구조 설명 2중 |
| 25 | 리뷰/이력 3종 | modulith-migration-history.md ↔ reviews/2026-09-30-module-coupling-review.md(shingle 8건) ↔ reviews/2026-10-01-module-coupling-briefing.md ↔ code-quality-backlog.md | history | D절 |

### B-2. 판정 재료
- 진짜 문장 복제(제거 쉬움): #1, #2, #11, #12(binance↔upbit 오류표).
- "constraints → 모듈 md 포인터" 구조 자체는 건전. 다만 constraints.md(141줄, 5.4k토큰, 항상 로드)에는 가드로 강제되는 규칙(예: 포트 `*Port` 접미사, `@Aspect` 금지, 소프트 삭제 등)의 설명이 길게 남아 있어 축소 여지(E-3).
- docs/agents/README.md는 "Contents:" 한 줄이 CLAUDE.md의 참고 문서 목록과 중복되며, 이미 존재하지 않는 구조를 포함하는지는 C절 확인.

## C. 코드와의 드리프트

방법: (1) 백틱 토큰 전수 추출(`t06/c.py`) — 경로형·FQCN·`Class.member`·`method()`를 저장소 `*.java/yml/sql/sh/...` 코퍼스(md 제외)에서 단어 존재 확인. 대상은 archive·history·reviews 제외한 살아있는 md 약 70개. 1차 플래그 174건 → 표본 확인 결과 **오탐 약 155건**(외부 URL 경로 `/v1/accounts`·`/status`, 외부 라이브러리 클래스 `CompletableFuture`·`StaleObjectStateException`, TR ID `TTTT3014U`, 클래스명을 ext 없이 쓴 경로형 `adapter/in/event/AppErrorRaisedListener`(존재 확인 완료), 타 레포 경로 `kista-infra/...`·`state/*.yml`). (2) 별도로 `docs/·src/·deploy/·scripts/...` 형 경로 참조 전수 존재 확인.

### C-1. 살아있는 문서의 실제 깨진 참조 (확인된 것)

| 파일:줄 | 참조 | 상태 | 확신도 |
|---|---|---|---|
| docs/agents/modules/shared/kernel.md:3 | `HexagonalArchitectureTest.sharedkernel_must_not_depend_on_other_modules` | 실제 테스트명은 `kernel_must_not_depend_on_other_modules`(HexagonalArchitectureTest:69). 옛 이름 | 확실 |
| docs/agents/docker-infra.md:13 | `docs/superpowers/specs/2026-10-02-kista-api-blue-green-design.md` | 파일 없음(archive에도 없음) | 확실 |
| docs/agents/docker-infra.md:64 | `docs/superpowers/plans/2026-10-13-postgres-18-upgrade.md` | 파일 없음(archive에도 없음) | 확실 |
| docs/agents/docker-infra.md:11,160,162 | `deploy/bin/reconcile.sh`, `scripts/backup.sh` | 이 레포에 없음(kista-infra 소재로 보임; 레포 경로 표기가 모호) | 추정 |
| docs/architecture-map.md:68 | `docs/deployment-map.md`, `deployment-map.yml` | 타 레포(kista-infra) 링크, 이 레포엔 없음 | 추정(의도적 외부 링크) |
| docs/agents/modules/api/benchmark.md:3 | `com.kista.stats` | 개명 경위 서술용 옛 이름 — 의도된 이력 문장 | 오탐에 가까움 |
| docs/agents/code-quality-backlog.md | `StrategyStartAmountCalculator`(:172,923,967,969), `ReorderBuyBudgetService`/`ReorderService`(:236,443~446), `CapitalInjectionService`(:439,444,471,685,831,866,908), `StrategyDefaults.java`(:30), `Money.USD_SCALE`(:410), `PageSizeSelector`(:652) | 클래스 모두 삭제·개명됨(0건). 닫힌 항목의 역사 서술이라 의도적일 수 있으나 클래스 부재 | 확실(부재) |
| .claude/agents·skills | 외부 라이브러리 클래스(`ResponseStatusException`,`RestTemplate`,`CompletableFuture`)·`../kista-ui` | 정상 | 오탐 |

→ **현재 규칙 문서(CLAUDE.md·constraints·architecture·testing·commands·modules/*)의 진짜 깨진 참조는 kernel.md 1건뿐**. 문서 품질은 코드 대비 드리프트가 매우 낮다.

### C-2. 파일별 깨진 참조 수 (1차 플래그 / 확정)

| 파일 | 1차 플래그 | 확정 깨짐 |
|---|---:|---:|
| docs/agents/code-quality-backlog.md | 36 | 약 20 (삭제 클래스 서술) |
| docs/agents/docker-infra.md | 30 | 2 (+타 레포 경로 3) |
| deploy/server/README.md | 13 | 0 (타 레포·서버 경로) |
| docs/architecture-map.md | 8 | 0~1 (타 레포 링크) |
| docs/agents/modules/shared/kernel.md | 1 | 1 |
| 그 외 모든 live md | ≤7 | 0 |

### C-3. 이력성 문서의 경로 참조 (참고)
`docs/superpowers/plans|specs/archive` 111개 파일에서 `docs/·src/·trading-core/...` 형 경로 참조 **4,288건이 현재 존재하지 않는 파일**을 가리킨다(예: 2026-09-03-strategy-config-module.md 225건, 2026-09-13-kista-trading-stage2-own-type-cleanup.md 225건). 리팩토링으로 패키지가 바뀐 결과이며, 이 문서들이 "현재 코드 안내"로는 이미 쓸모없음을 정량적으로 보여준다. history/reviews는 3건.

## D. 이력성·완료 기록

주의: `git log -1 --format=%cs`는 archive 파일 대부분 2026-10/11(일괄 이동·정리 커밋)로 나와 실제 작성 시점을 반영하지 못한다. 아래는 파일명 날짜(작성 시점)를 병기한다. 참조 횟수는 이 레포 추적 파일(fable-review 제외) 중 해당 파일명을 포함하는 다른 파일 수.

### D-1. 규모 요약

| 대상 | 개수 | 줄 | 바이트 | 외부 참조 | 판정 |
|---|---:|---:|---:|---|---|
| docs/superpowers/plans/archive | 110 | 81,226 | 4.78MB | live 문서·코드에서 1개만 참조(db-schema-reorg ← history), **96개는 참조 0** | 코드가 이미 말해줌. 삭제 대상(git 이력에 남음) |
| docs/superpowers/specs/archive | 69 | 9,529 | 0.87MB | 4개만 live 참조, 14개 참조 0 | 아래 D-2의 4개만 유지 검토 |
| docs/agents/code-quality-backlog.md | 1 | 972 | 140KB | CLAUDE.md:40 + 문서 12곳 | D-3 |
| docs/history/modulith-migration-history.md | 1 | 241 | 72KB | architecture·constraints·README·flyway 4곳 포인터 | 이력 100% |
| docs/reviews/*(2건) | 2 | 353 | 47KB | **참조 2건/1건**(각각 자체 간 참조뿐) | 이력 100% |
| deploy/server/schema-reorg/RUNBOOK.md | 1 | 122 | 13KB | flyway.md·docker-infra·architecture 등 6곳 | 스키마 재편 이행 릴리스 런북, 이행 완료됨 |

plans/archive 작성월 분포(파일명): 2026-06:7, 07:31, 08:15, 09:21, 10:25, 11:11. specs: 06:4, 07:17, 08:12, 09:13, 10:15, 11:8. 최대 파일: 2026-08-29-trading-broker-notify-decoupling 4,389줄, 2026-10-17-crypto-upbit-dca-a-b 4,164줄, 2026-10-13-dca-stage1-unconditional 2,875줄, trading-split-stage3-compile-boundary 2,715줄. 레거시 패키지 이동(modulith 마이그레이션·port-location·trading split·common 해체 등) 계획 약 30개는 대상 경로가 전부 사라져 C-3의 4,288건 깨진 참조를 만든다. plans/archive 경로는 `docs/agents/README.md` 정책("archive 이동 후 docs/agents·코드 주석이 경로를 인용하면 제자리 유지")상 live 인용이 없는 것이 정상.

### D-2. archive 중 live에서 참조되는 것 (삭제 전 포인터 정리 필요)

| 파일(archive) | 참조하는 곳 | 비고 |
|---|---|---|
| plans/2026-09-21-db-schema-reorg.md (733줄) | docs/history/modulith-migration-history.md | history 쪽 링크 제거하면 해제 |
| specs/2026-08-30-port-location-migration-design.md (166줄) | 7개 파일(주로 archive 간 상호) | live는 1곳 |
| specs/2026-09-21-db-schema-reorg-design.md (78줄) | deploy/server/schema-reorg/RUNBOOK.md | |
| specs/2026-10-02-trading-batch-resume-design.md (224줄) | docs/agents/workflow-batch.md, deploy/server/README.md | 설계 SSOT로 인용됨 — workflow-batch로 흡수 시 삭제 가능 |
| specs/2026-10-16-order-submission-claim-design.md (129줄) | docs/agents/workflow-orders.md | 동일 |

### D-3. docs/agents/code-quality-backlog.md (972줄, 140KB — 항상 로드되진 않지만 CLAUDE.md:40이 grep 사용을 지시)

- `### CQ-00`~`CQ-72` 항목 72개: 상태 `done(<해시>)` 약 65 + 해소/closed 약 2 + 본문 서술형 done 5 — **전부 종결**(조사 결과와 일치). 항목 본문은 "문제 → 수정 완료 → 가드" 기록이라 코드와 가드 테스트가 이미 말해줌.
- 보존 가치 있는 부분: `## 유지 목록 (재제안 금지)` 578~704줄(약 125줄) + `## 의심` 22~38줄 + 운영 규칙 5~15줄. 메모리 `project_*review*`와 상호 참조되는 "재제안 금지" 근거.
- 닫힌 항목(~39~577, 705~972줄 약 800줄)은 삭제 대상: 삭제 클래스(StrategyStartAmountCalculator, CapitalInjectionService, ReorderBuyBudgetService 등) 서술이 다수.
- 문서에서 CQ-xx를 인용하는 곳: stats.md(CQ-67·68·69·70 등 약 5회), trading-formulas.md, 일부 모듈 md — 항목 삭제 시 인용 문구(`CQ-69` 등) 정리 필요. Java 소스의 `CQ-` 인용은 0건.
- CLAUDE.md:40의 "코드 품질 원장" 작업 규칙 자체도 원장 폐기 시 같이 수정/삭제 대상.

### D-4. 살아있는 문서 안의 이력성 문단 (삭제·축약 후보)

| 파일:줄 | 내용 | 종류 | 근거 |
|---|---|---|---|
| docs/agents/modules/tradingcore/trading-formulas.md:32 | "cap 배수 1.05 경위 (값 유지)" | 경위 | 7개 날짜/CQ 인용. 값은 코드·테스트가 고정 |
| docs/agents/flyway.md:10-11 | V1 스쿼시 이력, 옛 공용 flyway 이력 보존 | 이력 | 이후 운영 지침만 남기면 됨 |
| docs/agents/constraints.md:26 | "이관 경위는 history 참고" | 포인터 | history 삭제 시 함께 삭제 |
| docs/agents/architecture.md:15 | 같은 포인터 | 포인터 | |
| docs/agents/meritz-api.md:64 외 | 실측 사례(2026-10-29 SOXL 주문번호 1878526 등) 7곳 | 사고·실측 기록 | 근거 보존 가치 vs 길이 |
| docs/agents/binance-api.md, kis-api.md, toss-api.md | 날짜 실측 각 3~5곳 | 실측 | |
| docs/agents/modules/tradingcore/stats.md | CQ-67~70 인용 5곳 | 이력 | 규칙 본문과 섞임 |
| docs/agents/modules/api/benchmark.md:3 | "`com.kista.stats`에서 개명" | 이관 경위 | 이벤트 FQCN 변경 배포 절차 포인터 포함 |
| docs/agents/docker-infra.md | 3건 날짜 + 깨진 plan/spec 포인터 2건 | 이력 | C-1 |
| docs/agents/README.md "Plan/spec documents" 절 | plan/spec 운영 정책 | 운영 규칙 | plans 폐기 시 같이 정리 |

### D-5. history·reviews 상세
| 파일 | 줄 | 마지막 수정 | 참조 | 비고 |
|---|---:|---|---|---|
| docs/history/modulith-migration-history.md | 241 | 2026-11-01 | 8(live 5) | 레거시 패키지 해소 상세(7~44), 순환 해소 경위(45~), 결합도 재검토 1~7단계(158~232) 등 전부 이력. 옛 경로 `docs/agents/modules/trading.md`·`com.kista.contract` 등 이미 개명된 이름 다수 |
| docs/reviews/2026-09-30-module-coupling-review.md | 289 | 2026-10-21 | 2 | 결합도 재검토 보고서, 모든 단계 이행 완료(history 158~232가 결과) |
| docs/reviews/2026-10-01-module-coupling-briefing.md | 64 | 2026-11-01 | 1 | 브리핑 |

## E. 문서 유형별 판정 재료 (docs/agents/**)

비율은 비어있지 않은 줄 기준 휴리스틱 분류(`t06` 인라인 스크립트): 이력 = 날짜·CQ·경위·실측·개명·커밋해시 포함 줄, 규칙 = (이력 제외) 금지·필수·반드시·않는다 등 포함 줄, 설명 = 나머지(코드 구조·흐름 서술). 오차 ±10%p. "가드줄" = 가드·ArchUnit·테스트 가드 이름을 언급하는 줄.

| 파일 | 줄 | KB | 규칙% | 이력% | 설명% | 가드줄 | 비고 |
|---|---:|---:|---:|---:|---:|---:|---|
| **항상 로드** constraints.md | 107 | 17 | 43 | 4 | 51 | 7 | 가드 있는 규칙 설명 장문 잔존(아래 E-2) |
| architecture.md | 56 | 9 | 21 | 1 | 76 | 3 | 모듈 목록 SSOT. 설명 비중 큼 |
| testing.md | 37 | 5 | 51 | 2 | 45 | 3 | 규칙형, 양호 |
| commands.md | 41 | 4 | 12 | 2 | 85 | 2 | local-dev.md와 일부 줄 복제 |
| flyway.md | 32 | 8 | 31 | 25 | 43 | 0 | 이력 25% — 스쿼시 경위 |
| docker-infra.md | 147 | 31 | 13 | 14 | 72 | 4 | 설명(운영 절차) 중심, 깨진 링크 2 |
| local-dev.md | 43 | 6 | 11 | 2 | 86 | 0 | 절차 문서 |
| scheduler-time-table.md | 49 | 5 | 10 | 6 | 83 | 0 | 설명 중심 표 |
| own-type-ledger.md | 38 | 10 | 23 | 5 | 71 | 3 | 허용 원장(재제안 금지 근거) — 보존 가치 |
| workflow.md/-batch/-capper/-orders/-ledger-price | 9/33/36/15/7 | 1/13/9/8/6 | 22/45/30/20/28 | 0/9/5/33/14 | | 0~3 | 흐름 서술 = 코드·flows.yml과 3중 서술(B-21) |
| kis-api.md | 87 | 17 | 18 | 5 | 75 | 0 | 외부 API 사양 = 코드로 복원 불가한 지식, 보존 가치 |
| toss-api / meritz-api / binance-api / upbit-api | 58/64/77/53 | 16/11/12/8 | 36/15/28/18 | 8/17/11/3 | | 0 | 동 |
| modules/tradingcore/trading.md | 77 | 34 | 23 | 9 | 67 | 4 | **최대 항상성 비용 문서**: 패키지 트리·클래스 나열(설명 67%). 코드가 SSOT |
| modules/tradingcore/trading-formulas.md | 83 | 18 | 22 | 8 | 68 | 2 | 변경 금지 공식 SSOT — 보존 |
| modules/tradingcore/stats.md | 25 | 19 | 28 | **32** | 40 | 2 | 줄당 760B(초장문), CQ 인용·이력 혼재 — 최우선 정리 후보 |
| modules/tradingcore/dca.md | 23 | 12 | 73 | 4 | 21 | 1 | 규칙형. **어떤 CLAUDE.md도 import 안 함**(고아) |
| modules/tradingcore/broker.md | 32 | 12 | 12 | 0 | 87 | 1 | 클래스 나열 |
| modules/tradingcore/matching·privacy·account·notify·marketcalendar·web | 24/25/17/7/9/7 | 9/4/5/3/1/3 | | | 설명 위주 | 0~2 | web.md는 규칙 71%(소형) |
| modules/tradingcore/trading-testing.md | 15 | 4 | 53 | 6 | 40 | 0 | |
| modules/api/{admin,benchmark,finance,market,notify,user,web}.md | 29/16/14/15/18/20/16 | 9/4/4/3/8/5/7 | admin 37, notify 50, web 56 | ≤12 | | 1~4 | web.md 가드·규칙 비중 높음 |
| modules/shared/{contract,kernel,platform}.md | 13/12/39 | 3/5/16 | 30/16/48 | 0/16/10 | | 2/1/7 | platform.md: 규칙 48% + 가드줄 7 |
| code-quality-backlog.md | 884 | 136 | 13 | 28 | 57 | 77 | D-3 — 이력 100% |
| docs/history, docs/reviews | 241+353 | 119 | | 100 | | | D-5 |

### E-2. 가드/테스트로 이미 강제되는데 문서에 길게 적힌 곳
(가드 테스트 존재를 grep으로 확인한 것만)

| 문서:줄 | 규칙 | 강제 수단 | 비고 |
|---|---|---|---|
| constraints.md:24 | `@Aspect` 저장소 금지 | `no_aspects_in_codebase`(ArchUnit) | 1줄이라 짧음 |
| constraints.md:29 | `*Port` 접미사·인바운드/아웃바운드 위치 | `HexagonalArchitectureTest.outbound_port_interfaces_must_have_Port_suffix` 등 | 규칙 문장 중복 |
| constraints.md:40 | `*JpaRepository` package-private | `persistence_jpa_repositories_must_be_package_private` | |
| constraints.md:44 | 예외 메시지 한국어 "~니다." | `ExceptionMessageFormatTest` | |
| constraints.md:20,22~23 | 모듈 경계·`@Configuration`/`@Scheduled` 배치 | `HexagonalArchitectureTest.platform_must_not_depend…`·`matching_must_not_depend…` 등 | 장문 |
| platform.md:41,43 | `ZoneId.of` TimeZones 한정, `Clock` 주입 | `zoneId_of_must_only_be_called_in_TimeZones`, HexagonalArchitectureTest | |
| architecture.md:12,14 | 모듈 경계·Hexagonal 레이어 | GradleModuleBoundaryTest, HexagonalArchitectureTest, ModulithArchitectureTest | 존재 사실만 쓰면 충분 |
| testing.md:43 | broker 밖 벤더 타입 금지 | `TradingCoreTestVendorModelTest` | |
| flyway.md / docker-infra.md | 마이그레이션 불변·expand/contract, compose 불변식 | `.github/scripts/check-migrations.sh`, `.github/tests/compose-invariants.bats` | 중복 5곳(B-4) |
| 가드 이름 확인: ArchUnit·구조 가드 테스트 메서드 65개 존재 | | | 규칙 문서 줄 상당수가 "테스트가 잠금" 한 줄로 축약 가능 |

판정 요지: (1) 항상 로드 5문서 중 `constraints.md`(5.4k 토큰)와 `architecture.md`는 "규칙+가드명" 형태가 이미 충분히 압축돼 있고 줄이기 여지는 약 20~30%(가드 중복 설명·장문 예외). (2) 가장 큰 절감은 **모듈 md의 클래스·패키지 트리 서술**(trading.md 35KB, stats.md 20KB, platform.md 16KB, broker.md 13KB) — 코드가 SSOT라고 architecture.md가 스스로 선언한 영역이며, trading 작업 시 +10.5k 토큰을 차지한다. (3) 보존 가치가 높은 것: trading-formulas.md, own-type-ledger.md, *-api.md(외부 사양·실측), dca.md(규칙), flyway.md·docker-infra.md(운영 절차).

## F. 삭제 후보 전수

### F-1. 참조되지 않는 비-Java 파일 (추적 파일 67개 중 마이그레이션 V*.sql 제외)

방법: 각 파일명·디렉토리명을 `git grep -F`(plans/specs archive, fable-review 제외)로 참조 확인.

| 후보 | 근거 | 확신도 |
|---|---|---|
| `src/main/resources/static/index.html` (28줄, "KISTA Dashboard" 정적 스텁) | 참조 0건. `fetch`/`api/` 호출 없음(데이터 없는 껍데기), 마지막 수정 2026-07-03. kista-ui가 별도 레포로 존재. Spring Boot가 `/`로 서빙하는 죽은 스텁 | 확실(참조 0) / 제거 영향 `/` 응답 변경 확인 필요 → 확실~추정 |
| `deploy/server/schema-reorg/01-forward.sql`, `02-rollback.sql`, `schema-dump.sh` | 2026-09-21 DB 스키마 재편 1회성 이행 스크립트, 이행 완료(history·archive plan 모두 완료 표기). 참조는 RUNBOOK.md·`V1__init.sql` 주석·deploy-plan.bats(경로 문자열 비교)·flyway.md/docker-infra.md 서술뿐. 롤백 증거 목적으로 남긴 것으로 보임 | 추정(보존 의도 가능; 참조는 있음) |
| `deploy/server/schema-reorg/RUNBOOK.md` (122줄) | 위와 동일, 이행 런북. flyway.md:10이 "자동 롤백 불가 → RUNBOOK" 포인터 | 추정 |
| `deploy/server/bluegreen`, `readiness`, `roles` (각 200~300B, 확장자 없음) | 참조 있음(detect-deploy-scope.sh·compose-invariants.bats) — **삭제 불가, 생존** | 해당 없음 |
| `deploy/grafana/*.json`, `infra/prometheus/prometheus.yml`, `docker-compose.yml`, `.env.example`, `lombok.config`, `scripts/dev-seed/*`, `.github/**` 스크립트·bats·stub | 모두 참조 확인 — 생존 | 해당 없음 |

→ 비-Java 파일은 사실상 **죽은 파일이 거의 없다**(진짜 후보는 static/index.html 1개 + 완료된 schema-reorg 이행 산출물).

### F-2. main에서 참조 0인 Java 클래스

방법: 추적 `*.java` 중 main 1,160개의 클래스명을 주석·문자열·import 제거 후 전 소스에서 단어 검색(`t06` 인라인 스크립트). main 파일에서 자기 외 참조가 0인 클래스 100개 전수 분류:
- 100개 전부 Spring이 와이어링하는 클래스(`@Component`/`@Service`/`@RestController`/`@Configuration`(Config)/`@Scheduled`/`@ConfigurationProperties`/SpringBootApplication) — 포트 구현 어댑터, 컨트롤러, 설정 클래스. 참조 0이 정상.
- 어노테이션 없는 순수 클래스·record·interface 중 main 참조 0: **0개**(유일한 예외 `PlatformAutoConfiguration`은 `AutoConfiguration.imports`로 등록 — 생존).
- 즉 **죽은 클래스 후보 0**. 테스트 전용으로만 사는 main 클래스도 탐지되지 않음(클래스 단위). 메서드·필드 단위 미사용은 조사하지 않음(범위 밖, 필요 시 IDE inspection 권장).
- 의심 1건(확신도 낮음): `UserSummaryAdapter`(user/adapter/out/persistence/user, @Component, 테스트 0, main 참조 0) — 포트 구현이라 정상일 가능성이 높으나 해당 포트의 소비처 확인 필요(추정).

### F-3. Gradle 의존성·플러그인 의심

| 항목 | 근거 | 판정 |
|---|---|---|
| `build.gradle.kts:54 runtimeOnly(libs.gson)` + `libs.versions.toml` gson 버전 | 레포 전체 `com.google.gson` import 0건, 주석 없음. firebase-admin 전이 의존일 가능성(Firebase는 gson 계열 사용) | 추정 — 제거 시 FCM 초기화(FcmConfig) 확인 필요 |
| `spring-boot-http-client`/`spring-boot-restclient`/`spring-boot-resttestclient` (testImplementation 3종) | `TestRestTemplate` 사용 파일 1개뿐(`HousingBenchmarkApiDocsTest`) — 한 테스트를 위한 3개 의존 | 추정(범위: 테스트 1건 개조로 제거 가능) |
| `micrometer-otlp` / `micrometer-prometheus` | Java import 0건이지만 config 기반 레지스트리 로딩 + prometheus.yml이 `/actuator/prometheus` 스크랩 — 사용 중 | 생존 |
| `spring-modulith-docs` | root 테스트 1개(ArchitectureMapTest류)가 사용 | 생존 |
| `mockwebserver3:5.5.0` (root test) | 10개 테스트 사용. 버전이 카탈로그 밖 하드코딩 | 생존(정리 제안: 카탈로그화) |
| lombok / swagger / springdoc / firebase / redis / nimbus / archunit / validation / httpclient5 | import 확인, 모두 사용 | 생존 |
| 플러그인 | `spring-boot`, `dependency-management`, `java` 등 표준 | 이상 없음 |

### F-4. 저장소에 커밋된 산출물·임시·IDE 설정

- 추적 파일 중 `.log/.tmp/.bak/.iml/.DS_Store/.idea/build/` 해당 **0건**. `.gitignore`가 `.gradle/ build/ .idea/ logs/ bin/ .DS_Store .env* application-local.yml .claude/worktrees/ .superpowers/`를 이미 커버.
- 추적된 `.claude/`: agents 2·rules 2·skills 3·settings.json(`enabledPlugins: {}` 한 줄) — 의도된 공유 설정. `.claude/settings.json`은 사실상 빈 파일(삭제 가능, 확신도 추정).
- 관찰: `src/main/resources/db/migration/CLAUDE.md`·`trading-core/src/main/resources/db/migration-trading/CLAUDE.md`가 **리소스 디렉토리 안**에 있어 jar에 CLAUDE.md가 번들된다(`trading-core/build/resources/main/db/migration-trading/CLAUDE.md`로 확인). 기능 영향 없으나 불필요한 패키징 — 포인터를 `.claude/rules/`(paths 지정)로 옮기면 해소. 확실(번들 사실) / 영향 경미.
- `.gitignore` 보강 필요 항목: 없음. (`*.bat`가 포괄 ignore라 향후 `.bats`는 해당 없음 — 패턴이 `*.bat` 정확매치라 `.github/tests/*.bats`는 추적됨, 정상.)

### F-5. 로컬 미추적·ignored 잔재 (`git status --ignored`)

| 경로 | 크기 | 비고 |
|---|---:|---|
| build/ | 156MB | 재생성 가능 |
| trading-core/build/ | 109MB | 〃 |
| shared/build/ | 1.8MB | 〃 |
| .gradle/ | 18MB | 캐시 |
| .idea/ | 8.6MB | IDE |
| bin/ | 6.0MB | IDE(Eclipse/IntelliJ 컴파일 출력 default·generated·main·test) — gradle과 무관한 잔재, 삭제 가능(확실, 재생성 가능) |
| logs/ | 1.6MB | 로컬 서버 로그. `kista-api.log.*.tmp` 4개(2026-08-28·10-19·10-24·10-31, 롤링 중단 잔재) + `.gz` 다수 — 삭제 가능(확실) |
| trading-core/logs/ | 76KB | 10-28 로그 — 삭제 가능 |
| .superpowers/ | 1.1MB | `dca-stage1-ledger.md`, `plans/`(빈), `sdd/2026-10-17-*`, `2026-10-21-*` — SDD 진행 원장(스크래치, gitignore됨). 해당 plan 모두 archive 완료 → 삭제 가능(추정; 사용자 확인) |
| .env, .env.production | 소 | **시크릿 로컬 파일 — 삭제 금지 후보**(참고용) |
| src/main/resources/application-local.yml, trading-core/src/main/resources/application-local.yml | 소 | 로컬 설정 — 유지 |
| gradlew.bat | 소 | `*.bat` ignore로 의도적 미추적 |
| 각 디렉토리 .DS_Store (루트·docs·docs/superpowers·src·src/main·src/test) | 소 | macOS 잔재 |
| .claude/scheduled_tasks.lock, settings.local.json | 소 | 런타임 |
| 미추적(비-ignored): `docs/reference/` (quantstack, 제외 대상), `docs/superpowers/fable-review/findings/` | | 진행 중 작업물 |

### F-6. worktree
- `git worktree list`: `/Users/phs/workspace/kista/kista-api` 하나만(main). 
- `/Users/phs/workspace/kista/kista-api-worktrees`: **빈 디렉토리**(2026-10-18 생성, 내용 0). 삭제 가능(확실). `.claude/worktrees/`도 빈 디렉토리(ignore됨).
- 상위 `/Users/phs/workspace/kista/ARCHITECTURE.md`는 레포 밖 파일(git 없음) — 이번 조사 범위 밖, 존재만 보고.

### F-7. 브랜치
- 로컬: `main`만. 원격: `origin/main`만(`git ls-remote --heads` 확인). stash 0, 태그 0. 병합됐는데 남은 브랜치 **없음**. `git count-objects`: size-pack 21.9MB, garbage 0.

## G. 추가 기록 (마감 후 보충)

- **cross-repo 참조(재조사 안 함)**: docker-infra.md·deploy/server/README.md의 중복·낡은 서술은 `docs/superpowers/fable-review/cross-repo.md` A5(서버 초기 설정·백업 복구를 infra로, `.env` 키 목록은 `.env.example`로; `deploy/server/README.md:19-51,68-103,166-179`, `docker-infra.md:157-178`)·A6(`docker-infra.md:18` data_net 서술, `:12` 판정 파일 목록 누락, `SERVER_HOST` 언급)이 SSOT. 본 문서 B-1 #4·#5·#6과 C-1의 docker-infra 깨진 링크 2건(:13, :64)은 거기에 합산해서 처리.
- **레포 밖 낡은 문서**: `/Users/phs/workspace/kista/ARCHITECTURE.md`(git 없음)는 "Java 21 + Spring Boot 3, Fly.io 배포, Supabase PostgreSQL"로 현재(Java 25, Boot 4, OCI, 자체 PostgreSQL)와 완전히 어긋남. 상위 `/kista/CLAUDE.md`는 이를 참조하지 않으므로 삭제·재작성 후보(확실: 드리프트 / 사용처는 미확인).
- **검증 완료**: build/resources에 CLAUDE.md 번들 확인(1건). worktree·브랜치 항목 F-6·F-7은 실측.
- **미조사(한계)**: 메서드·필드 단위 미사용 코드, application*.yml의 미사용 설정 키, 문서 토큰 수는 휴리스틱 추정.
