# 29. 구현 평가 2회차 — 지침서 대 커밋 (fb37117cc..HEAD)

측정일 2026-11-15, 기준 HEAD `252e63bfe`(작성 중 `b3f2c2238` docs 커밋 1개가 더 붙었다. findings/28·로드맵·08만 바꿔 범위 밖). 사실만 적는다. 확인 못 한 것은 "미확인". 빌드·테스트·ssh는 하지 않았다(테스트 통과 여부는 전부 커밋 메시지·로드맵 주장일 뿐 직접 확인하지 않았다).

## 1. 사실 측정

### S6 헥사고날 (지침서 01 — 단계 A `e72282b98`, 단계 B `012b0c362`~`775ec82be` + `5bfff390b`)

**지침서 대조 (단계 A)**

| 항목 | 지침서 | 커밋 |
|---|---|---|
| 삭제할 규칙 3개 | 3개 삭제 | 2개 삭제, `application_must_not_depend_on_adapter`는 `application_must_not_depend_on_inbound_adapters`로 좁혀 남김(9절 "단계 A 실행 결과"에 이유 기재) |
| 신설 규칙 3개 | `application_and_domain_must_not_see_infrastructure_types`, `stores_expose_domain_types_only`, `interfaces_must_earn_their_keep` | 3개 모두 신설. 인프라 타입 목록에 `org.springframework.data.redis..` 추가 |
| `interfaces_must_earn_their_keep` | 단계 C 끝에 켠다, 그 전 `@Disabled` — 9절 보정이 "허용 목록에 넣고 켠다"로 바꿈 | 켰다. 대상에 `application.usecase`도 포함(본문과 다름, 이유 기재), 구현 ≤1인 134개를 `single-impl-interfaces.txt`에 넣음 |
| 완료 판정 명령 | `compileJava compileTestJava` + 아키텍처 테스트 | 커밋 메시지에 "규칙마다 위반 클래스를 임시로 만들어 실패 확인"만 있고 위 명령 실행 결과는 메시지·로드맵에 없음(미확인) |

**지침서 대조 (단계 B)**

| 항목 | 커밋에서 한 것 |
|---|---|
| 모듈 순서 | privacy `012b0c362` → account `f2474c7ae` → market `b84d9f6c9` → benchmark `063ca91bd` → user `c20fd83d2` → admin `256c0e87b` → finance `70503a14f` → stats `1bc1e453b` → trading `19cc491d9`. 지침서 순서와 같고 marketcalendar·api.notify는 usecase 패키지가 없어 대상 없음 |
| 제거한 UseCase·Query | 커밋별 삭제 파일 수 privacy 2, account 1, market 3, benchmark 4, user 5, admin 7, finance 10, stats 8, trading 12 = 52 (`git show --diff-filter=D`로 센 값). base(`fb37117cc`)의 usecase 디렉토리 java는 60개 = 52 + package-info 8 |
| `package-info` | 지침서 보정은 "9개"라 했으나 base에는 8개(market에는 usecase `package-info`가 없었다). 8개 삭제, `"usecase"` NamedInterface 문자열은 HEAD에 0건 |
| `@NamedInterface("api")` 3개 | `UserService.java:35`, `AccountService.java:38`, `PrivacyTradeValidationService.java:15` |
| 되돌린 모듈 | 없음 |
| 필드 이름 | 별도 커밋 `775ec82be`(92개 파일)에서 `xxxUseCase`→`xxxService`. 남긴 것: `CryptoDcaScheduler.useCase`(타입 `CryptoDcaRunner`), `TradingCloseScheduler:41`·`TradingOpenScheduler:34`의 `useCase`(타입 `TradingExecutionFacade`), 테스트 메서드명·`DisplayName`·`TokenUseCaseTestConfig` |
| 리뷰 후속 `5bfff390b` | `notify_must_stay_pure_outbound_gateway` 금지 대상을 `application.service`로, `web_must_stay_pure_inbound_sink`를 `mainClasses`만 보게, admin 중복 record 2개를 `PrivacyBaseCreateResult` 하나로, `InvestmentPointsQuery.Scope`→`InvestmentPointsScope` |
| 문서 | 각 커밋이 모듈 문서·architecture.md 수정(예: `012b0c362`에 architecture.md·privacy.md·workflow-batch.md). 남은 usecase 언급: `.claude/agents/architecture-guardian.md:16`("기존 `application.usecase`"), `.claude/skills/api-audit/SKILL.md:20`("컨트롤러 → usecase → service") |
| 빈 디렉토리 | `trading-core/.../trading/application/usecase`가 디스크에 비어 남아 있다(git 미추적) |
| 완료 판정 | 지침서는 compile + 해당 모듈 테스트 + 아키텍처 테스트 통과를 요구. 모듈 커밋 메시지는 제목 한 줄과 attribution뿐이고 실행 결과 기록이 없다. 로드맵 S6 칸에도 통과 기록 없음(미확인) |
| 규모 | 단계 B 범위 diff: main 207파일 +1,047 −1,544, test 70파일 +473 −529 (`012b0c362~1..775ec82be`) |

**수치 (4번 항목)**
- 제거한 UseCase 인터페이스: 52. `implements ...UseCase` HEAD 0건.
- HEAD에서 `application/port` 아래 interface를 가진 파일(`git ls-files`+grep, 최상위 `interface`): root 42, trading-core 55, 합 97, 전부 `port/output`. `:shared`(`shared/src`)에는 `application/port` 경로가 없다.
- 가드 `interfaces_must_earn_their_keep`(`HexagonalArchitectureTest.java:420-445`)의 검사 대상: 패키지명에 `.application.port.output` 또는 `.application.usecase`가 들어가고 `com.kista.shared.`로 시작하지 않는 최상위 인터페이스 중 구현 클래스(비인터페이스 하위 타입)가 2개 미만인 것. `.application.usecase` 패키지는 HEAD에 없으니 실제 대상은 `port.output`뿐이다.
- 허용 목록: `src/test/resources/architecture/single-impl-interfaces.txt` 85줄 중 주석 3줄 제외 82항목(`PENDING_REMOVAL_CEILING = 82`, `:74`). 분류 기준은 파일에 없다. 머리 주석이 "구현이 하나뿐이라 제거를 기다리는 UseCase·아웃바운드 포트"라고만 적고 항목은 FQCN을 모듈 순서로 나열한다(`com.kista.api.admin.application.port.output.*` 등). 이와 별도로 의존 역전 4개가 테스트 상수 `DEPENDENCY_INVERSION_PORTS`(`:75-80`): `ApprovalPolicyPort`, `BrokerEnabledPort`, `AccountOpenOrderCancelPort`, `MockSimulationDataPort`. 목록이 상한보다 길거나 낡은 항목이 남으면 실패하는 단언이 있다(`:441-444`).
- 허용 목록 82 + 의존 역전 4 = 86이고 `port/output` 인터페이스 97개 중 나머지 11개는 구현이 2개 이상이다(지침서 01 본문 "구현 2개 이상 11개"와 같은 수). 내가 grep으로 센 "구현 <2" 값은 93으로 가드 값과 다르다(grep이 여러 줄 `implements`를 놓쳐 단일로 과대 계수. 가드의 ArchUnit 값이 정확).
- 가드 대상 밖의 단일 구현 인터페이스(세는 방법: `git ls-files`의 main java 1,063개에서 최상위 `interface` 151개를 뽑고 `implements <이름>` 줄 수로 구현 수를 셈, `port/output`·`*JpaRepository` 제외): `TokenBlacklistPort`(`shared/platform/security`, 구현 `RedisTokenBlacklistReader` 1개, 가드가 `com.kista.shared.`를 제외), `TossTokenStore`(`broker/adapter/out/toss`, 구현 `TossRedisTokenStore` 1개 + 테스트 fake), `ProblemResponder`(`shared/platform/web`, 함수형 — 구현 클래스 0, 람다), `DcaDecision`(sealed, 허용 하위 1개 record). 단일 구현 클래스 기준으로는 `TokenBlacklistPort`·`TossTokenStore` 2개다. `*JpaRepository` 인터페이스 약 40개는 Spring Data가 구현하므로 제외했다. 중첩(비최상위) 인터페이스는 세지 않았다(미확인).
- 9절 "단계 B 실행 결과" 마지막 항목 전문: "**단계 C로 넘김**: `interfaces_must_earn_their_keep`은 `application.port.output`·`application.usecase`만 본다. usecase 패키지가 사라졌으니 단일 구현 인터페이스를 다른 패키지(`application.service`, `adapter`)에 새로 만들면 잡히지 않는다. 단계 C 5번에서 가드를 켤 때 대상을 `com.kista`의 모든 인터페이스(:shared 포트·함수형 인터페이스 등은 허용 목록)로 넓힌다."

**스스로 정한 결정**

| 결정 | 9절 유형 | 근거 사실의 HEAD 확인 |
|---|---|---|
| 규칙 `application_must_not_depend_on_adapter`를 삭제하지 않고 inbound만 막게 좁힘 | 9-3(가드의 뜻과 부딪힘 → 약화 금지)에 가까움. 지침서 A-1 문장과 다르게 한 것으로 9절에 기재 | 규칙은 `HexagonalArchitectureTest`에 있음(미실행) |
| `interfaces_must_earn_their_keep` 대상에 usecase 포함·허용 목록 방식으로 켬 | 9-3("현재 위반 전체를 허용 목록에 넣고 켠다") 그대로 | 맞다. 단 HEAD에서 usecase 대상은 비어 있어 의미가 없다 |
| 서비스 3개에 타입 단위 `@NamedInterface("api")`, 메서드 전부 공개 | 9-6(동작 변화 없는 구조 선택) | 3개 파일에 존재 |
| `notify`·`web` 가드 문자열 수정 | 9-3(가드 문자열은 같은 커밋에서 고친다) | 맞다 |
| 필드 이름을 별도 커밋으로 개명 | 9-6 | 맞다 |
| 중첩 타입 2개 이동 | 9-6 | `PrivacyBaseCreateResult`·`InvestmentPointsScope` 파일 존재 |
| 가드 대상 확대를 단계 C로 넘김 | 9-3("뒤 단계로 미룸") | 위 수치 참조. 대상 밖 단일 구현 2~4개 존재 |

**배포 뒤 관찰**: 정의 없음. 지침서는 "동작 변경이 없어 승인 대상이 아니다", 로드맵 S6 칸은 "미배포 — 동작 변경 없음. 매매 시간대 밖 push"만 적음. 로드맵 S6 칸은 HEAD(`252e63bfe`)에서 "미배포"인데, 같은 시점 로드맵의 2fe2fa152 행(`b3f2c2238`에서 추가)은 "2026-11-15 배포 `252e63bfe`"라고 적는다. S6 단계 B 커밋은 `2fe2fa152`보다 뒤라 `252e63bfe` 배포가 사실이면 S6 단계 B도 나갔다. 두 칸이 서로 맞지 않는다(실제 배포 여부는 ssh 없이 미확인).

### S8 문서 정리 (지침서 09 — `c2484085a`~`579e8ac8d`)

**지침서 대조**

| 태스크 | 커밋 | 한 것 / 안 한 것 |
|---|---|---|
| T1 decisions.md | `c2484085a` | 1파일 +419줄. 지침서 완료 판정(재심 표 대조표를 보고에 첨부)의 첨부 여부는 커밋·로드맵에 없음(미확인) |
| T2 삭제 | `ac278e583` | 213파일 −93,092줄. 태그 `archive/docs-2026-11` 로컬에 존재. 완료 판정 grep(`plans/archive…schema-reorg`, 제외 경로 있음)을 HEAD에서 돌린 결과 0건. bats 통과는 미확인 |
| T3 작은 수정 | `ac278e583`, `74d59302e` | `UserSummaryAdapter` 유지(보정 절대로), `.env.example`에 `CMC_API_KEY` 추가하지 않음(grep 0, 보정 절대로), gson `runtimeOnly` 제거와 `okhttp` 버전 카탈로그로 이동, `static/index.html` 삭제(`src/main/resources/static` 없음), 메리츠 `mod_stpr_cls_code`·토스 주석 수정 |
| T4 trading·stats·platform | `3cdf3866b`(35.2→6.3KB)·`7fae356ae`(20.3→4.3KB)·`2f0059277`(18.2→7.4KB) | HEAD 바이트: trading 6,519 / stats 4,312 / platform 7,429. 이유를 코드 주석으로 옮긴 목록은 커밋 메시지에 파일명으로 기재 |
| T5 나머지 모듈·벤더 이동 | `7b8b9e9d2` | broker 5,530 / dca 5,978 / matching 5,642(예산 6,000). `docs/agents/vendors/{binance,kis,meritz,toss,upbit}.md` 존재 |
| T6 항상 로드 5파일 | `ac278e583`(+`579e8ac8d`가 규칙 복원) | HEAD 합 25,641B(예산 26,000). 리소스 디렉토리 CLAUDE.md 2개 삭제, `.claude/rules/flyway.md` 존재. jar에 CLAUDE.md 0건은 로드맵 주장일 뿐 빌드하지 않아 미확인 |
| T7 예산 가드 | `9ae228828` | `.github/scripts/check-doc-budget.sh`(32줄)·`.github/tests/doc-budget.bats`. 보정 절이 요구한 "`detect-deploy-scope.sh` verify=false 확인"은 커밋 메시지에 "별도 잡 불필요"로 기재 |
| T8 memory·로컬 잔재 | git 밖 | MEMORY.md 포인터는 이 세션 시작 시 컨텍스트에 있음. 삭제 목록은 로드맵 S8 칸에 기재(`.superpowers/` 남김). 레포 밖 실제 상태는 미확인 |
| T9 단계 2 마감 점검 | 없음 | 로드맵이 N11로 미룸 |
| 마감 점검 | `579e8ac8d` | 지침서에 없던 작업: 경로 대응표(로드맵 9-1 6항), 축소 중 빠진 규칙 복원(trading.md 거부 규칙, constraints.md 4항목, broker.md) |

**스스로 정한 결정**

| 결정 | 유형 | HEAD 확인 |
|---|---|---|
| Q-10 `mod_stpr_cls_code=1` 유지, 주석만 정정 (자체 결정 ①, Fable이 검토에서 확정) | 9-2 U1(외부 문서와 다름 → 문서 따름) + U3 성격(돈이 덜 움직이는 쪽) | 스펙 `/Users/phs/Downloads/meritz_openapi_spec_20261112.json`의 해외 일봉 `mod_stpr_cls_code`: required Y, "0 분할을 반영해 보정한 주가 / 1 보정 전 원래 주가". `MeritzPriceApi.java:106`은 `"1"`과 그 설명을 가진다. 맞다. 같은 스펙의 국내 일봉은 required N·"0 수정종가(기본)/1 원종가"라 정의가 따로 있고 스펙 requestExample은 해외에 `=0`을 쓴다 |
| gson 직접 의존 제거(전이 의존 2.13.2) | 9-6 | `libs.versions.toml`에서 gson 항목·버전 삭제 확인. gradle 캐시에 gson 2.11.0·2.13.2·2.14.0 존재. `*.java`에서 Gson 사용 0건. 컨텍스트 로드 테스트 통과는 미확인 |
| `static/index.html` 삭제(`/` 401) | 9-3 계열(보정 절 기본값 "Caddy가 `/`를 안 보면 지운다") | `deploy/server/caddy/kista-api.caddy` 1개 존재. 커밋 메시지는 "Caddy·compose 헬스는 actuator만"이라 기재. kista-infra는 미확인(이 레포 밖) |
| 모듈 문서 예산을 달성하며 트레이드오프로 일부 규칙 삭제 → 마감 점검에서 복원 | 지침서 밖 | `579e8ac8d`가 복원. 복원된 항목 외 삭제 규칙의 누락 여부는 미확인 |

**배포 뒤 관찰**: 로드맵 S8 칸이 "동작 변화는 gson 2.14.0→2.13.2와 `/`(index.html 삭제 → 401)뿐"이라고 적는다. 관찰 항목·기한은 정의 없음. 먼저 한 push `125c07d6b`는 검사 오탐으로 실패했고(`fd89d449f`가 pathspec을 `*.sql`로 한정해 수정) 이후 `e044e983c`로 배포했다고 로드맵이 적는다.

### S10 T1 (지침서 06 — `be6956778`)

| 항목 | 지침서 | 커밋 |
|---|---|---|
| 판정표 | `BatchStep`·`Decision`·`static decide(step, Optional<MarketSession>, TradingBatchPhase, now, trigger)`, calendar 모듈 | `trading.domain.model.TradingBatchStep`(117줄) record(`kind`, `tradeDate`), 인스턴스 메서드 `decide(Optional<MarketSessionTimes>, Optional<TradingBatchPhase>, Instant, Trigger)`. `Missed`에 `warnInterrupted` 추가, `Run`·`Reserve`·`Skip` 동일 |
| 위치 | marketcalendar | trading (06 12절에 이유 기재: 표가 `TradingBatchPhase`·`TradingBatchJob`을 읽음) |
| 수동 실행 | 완료 값 이상이면 Skip | 수동은 phase를 보지 않음(`openPlace`, `manualClosePlace`). 수동 개장은 `decide` 첫 줄에서 무조건 Run |
| 개장 창 끝 | `closeAt − 30분` | `min(closeAt − 30분, 거래일 04:30 KST)`(`TradingBatchStep.java` `openPlace`). `OPEN_TRADE_DATE_CUTOFF = KstTradeDates.SCHEDULER_RUN_TIME`로 `shared.platform.time`을 도메인에서 참조 |
| 표 테스트 | 기존 테스트 하나당 행 하나 + 대응표 | `TradingBatchStepTest`의 `row("` 68개. `row("경계` 25개. 06 12절은 이전 테스트 대응을 17+11+2+수동개장 1(=31)이라고 쓰고 커밋 메시지는 "30개 + 창 경계 25행"이라고 쓰는데 실제 비경계 행은 43개로 둘 다 합이 68과 맞지 않는다(행 원천 라벨별 개수: 코드 주석 구획 기준 Resumer 21·Close 18·Open 29) |
| 기존 코드 불변 | "아직 건드리지 않는다" | main 변경은 신규 파일 1개뿐(`git show --stat`: main 117 +, test 196 +, 지침서 25 +). 아직 사용처 없음(`TradingBatchStep` 참조는 자기 파일 밖에 없다) |
| 상수 중복 | 한 곳 | `TradingBatchStep`(`:17-22`)과 `TradingCloseScheduler:56-60`에 중복(12절에 기재) |
| 변이 확인 | 6절 4번 | 12절에 "`PLACEMENT_MARGIN`을 11분으로 바꾸면 2행 실패"라고 적음. 직접 확인하지 않음(미확인). "정지 전략을 리포트 대상에서 빼면 실패" 변이는 T2 범위라 해당 없음 |
| 대응표를 PR 설명에 | | 커밋 메시지에는 대응표 없음, 12절이 대신함 |
| 리포트 동등성 IT·단계 멱등 IT(6절 2·3번) | T2 선행 | 아직 없음 |

**스스로 정한 결정**: 12절의 5개 차이(위치, `Missed` 필드, 수동 phase, 수동 개장, 개장 창 끝)는 모두 9-2 U2(코드와 지침서가 다름 → 지금 코드 동작을 따름)에 해당. 근거 사실 확인: 12절이 말한 "`TradingOpenScheduler.canRun`의 04:30"은 `TradingOpenScheduler.java:96`에 `canRun`이 있음(상수 본문은 읽지 않았다 — 미확인). 상수 3개가 `TradingCloseScheduler:56-60`에 있음은 맞다.

**배포 뒤 관찰**: 정의 없음(지침서 "배포·관찰 없음", 로드맵 "아무도 쓰지 않는 새 클래스라 동작 변화 없음"). T2 이후 관찰(2거래일)은 지침서에 정의돼 있으나 T2는 미착수.

### S11 orders·열린 사이클 유일 제약 (지침서 11 T1 — `0cd178893`, `findings/27-s11-save-paths.md`)

**지침서 대조**

| 항목 | 지침서 | 커밋 |
|---|---|---|
| ① 저장 경로 전수표 | 표 작성 | `findings/27`(134줄). 커밋 시점 HEAD 문구는 `125c07d6b` 기준 |
| ② 운영 위반 0건 재확인 | 읽기 전용 SELECT | 커밋 메시지·V21 주석에 "2026-11-15 0건". SELECT 본문·결과 원문은 레포에 없음(미확인) |
| ③ 4절 테스트 3·4 | DbTest | `LedgerUniqueConstraintIT`(205줄, 커밋 메시지 "15건")로 작성. 지침서 완료 판정 명령은 `*DbTest`(`--tests '*Order*DbTest' '*StrategyCycle*DbTest'`)이고 IT는 `integration` 태스크라 명령이 다르다. 실행 결과는 로드맵에 "전체 스위트 통과" 문구가 없고 커밋 메시지는 "제약 전에는 위반 4건, 제약 뒤 flush 전에는 재저장 2건이 실패"만 적음 |
| ④ 마이그레이션 | 2-2절 DDL | `V21__ledger_unique_constraints.sql` (아래 전문) |
| ⑤ 위반 시 처리 | `TradingErrorEvent` 알림 | 새 코드 없이 기존 `TradingBatchGuard.runSafely` 경로가 충족한다고 10절이 주장. 코드 직접 확인은 안 했다(미확인) |
| 지침서에 없던 변경 | | flush 순서 수정 2곳: `OrderPersistenceAdapter`(`cancelPlanned`)·`StrategyCyclePersistenceAdapter`(`markEnded`) 합 +5줄. 기존 테스트 3개 수정 |

V21 전문(`trading-core/src/main/resources/db/migration-trading/V21__ledger_unique_constraints.sql`):
```sql
CREATE UNIQUE INDEX uq_orders_live_slot
    ON trading.orders (strategy_cycle_id, trade_date, timing, direction, order_leg)
    WHERE status IN ('PLANNED', 'SUBMITTING', 'PLACED', 'PARTIALLY_FILLED', 'FILLED')
      AND order_leg <> 'UNKNOWN';

CREATE UNIQUE INDEX uq_strategy_cycle_open
    ON trading.strategy_cycle (strategy_id)
    WHERE end_date IS NULL AND deleted_at IS NULL;
```
지침서 2-2절은 키를 `(strategy_cycle_id, trade_date, order_leg)`로, 사이클 인덱스를 "2-1 적용 전이면 `AND deleted_at IS NULL`을 붙인다"로 적었다. 사이클 인덱스는 지침서와 같고 주문 인덱스만 `timing`·`direction`이 더해졌다.

**스스로 정한 결정 (Q-11: 키에 timing·direction 추가)**
- 유형: 9-2 U2(코드가 지침서 가정과 다름) + 9-6(동작이 같은 범위 안 구조 선택). Q-11 본문은 "돈이 덜 움직이는 쪽이 아니라 정상 주문이 빠지지 않는 쪽"을 골랐다고 적는다(9-2 U3 기본값 원칙과 다른 기준).
- 근거 확인 1: `TradingOrderSlots.java:19`의 `ConcreteSlot`은 `(timing, direction, orderLeg)`이다. 맞다.
- 근거 확인 2: `VrStrategy.java:101-102`는 bootstrap 매수가 `LOC`·`AT_CLOSE`·`PlannedOrder.leg("VR_BUY", 1)`, `:170`·`:177` 사다리 매수가 `LIMIT`·`AT_OPEN`·`PlannedOrder.leg("VR_BUY", merged.size() + 1)`(첫 항목 `VR_BUY_01`). `PlannedOrder.leg`는 `"%s_%02d"`(`PlannedOrder.java:57-60`). 맞다. 사다리 매도는 `VR_SELL`.
- 근거 확인 3(Q-11 문구에 없는 사실): 앱의 슬롯 판정은 `OrderPersistenceAdapter.findPlannedOrPlacedByCycleAndDate`가 `OPEN_STATUSES`(PLANNED·SUBMITTING·PLACED)로 조회한 주문 기준이다(`:185-189`, `findings/27` §1). 인덱스의 "살아 있는" 집합은 FILLED·PARTIALLY_FILLED를 포함해 더 넓다. `findings/27` 의심3이 이 불일치를 "미확인(낮음)"으로 남겼고 Q-11의 "정상 경로가 제약에 걸릴 수 없다" 문장은 이 상태 집합 차이를 언급하지 않는다.
- 근거 확인 4: "같은 날 두 모드가 함께 살아 있는 경로를 찾지 못했다"는 findings/27 의심2 그대로이며 `VrStrategy.buildOrders:25-30`의 holdings>0 분기는 "미확인"으로 남아 있다.
- 사이클 인덱스에 `deleted_at IS NULL`을 붙인 것은 10절이 기재(T4에서 컬럼 drop 시 재생성 예정).

**배포 전·후 확인**: 지침서 9절에 배포 직전 SQL 2개(주문 슬롯 중복, 열린 사이클 중복)와 "0건이 아니면 배포 안 함" 규칙이 있다. 9-4 분류: "스키마 변경 + 주문 경로". 로드맵 S11 칸이 "직전에 11의 9절 SQL 재확인"을 적지만 배포 직전 실제 재실행 결과는 로드맵·PROGRESS.md·커밋 어디에도 없다(미확인). 관찰 정의는 있다: 11의 10절 "배포 뒤 2거래일 관찰: 관리자 알림과 `docker logs kista-trading`의 `uq_orders_live_slot`·`uq_strategy_cycle_open` 0건, PRIVACY 가격 보정(`BUY 가격 보정 완료(개별)`)과 VR 롤오버가 있던 날의 정상 완료". 로드맵 칸은 "관찰 대기(2026-11-16·17 거래일)". 리뷰는 PROGRESS.md에 "리뷰 1회(Opus)에서 차단 없음"으로 기재(S11·S10 합쳐). 지침서에 적힌 리뷰 지적 2건은 고치지 않고 기록만 했다(11의 10절: `reopenCycleIfEnded` 동시 호출 500, 옛 사이클 취소 행 FILLED 전이).

### S12 보안 (지침서 13 U1 — `09011d9bf`)

**지침서 대조**

| 항목 | 커밋 |
|---|---|
| 로그아웃 jti 차단 | 함. RT 쿠키가 없거나 DB에 RT가 없어도 AT jti를 막도록 `TokenService.logout`·`AuthController:104`를 고침(표가 적은 범위보다 넓음) |
| 내부 토큰 상수 시간 비교 | `InternalTokenAuthFilter`가 `MessageDigest.isEqual`로. `token == null` 가드 추가 |
| 공개 경로 명시 | `RootSecurityPolicy.java:31-35`: 공개는 `POST /api/auth/kakao/callback·refresh·logout`과 `POST /api/auth/dev-*`·`dev-approve/*`. `/api/trades/stream` 줄 삭제 |
| RT 수명 5일 한 곳 | `TokenConstants.RT_TTL`(`:7`) |
| 필터 순서 주석 | `SecurityConfig` 주석 수정 |
| PENDING 차단 | 하지 않음(`role` 클레임 제한·`hasAnyRole` 마지막 규칙·허용 경로 9개). 지침서 5절의 fable-ui 요청 ①~④(`status` 클레임, 상태 변경 시 역할 변경 시각, status-stream 삭제 후속, 반려 사유 삭제)도 이 커밋에 없다. 10절 아닌 14절 "U1 실행 결과"는 PENDING 차단만 "남았다"고 쓰고 ①~④의 상태는 따로 적지 않았다 |
| 완료 판정 | `./gradlew :test --tests '*Security*' --tests '*Auth*'`. 실행 결과 기록 없음(미확인). 테스트 4개 파일 수정·신설(`InternalTokenAuthFilterTest` 54줄 등) |

**스스로 정한 결정**
- jti 차단 범위 확대(RT가 DB에 없어도): 9-6(구조·범위 안). 근거 확인: `TokenService.logout`(HEAD)은 `rawRefreshToken != null`일 때만 RT 삭제, `jti != null && atExpiresAt != null`이면 항상 블랙리스트 등록. 맞다.
- `/api/auth/**` 목록 밖 경로가 토큰 없이 404·405에서 401로 바뀜: 9-6 "kista-ui가 읽는 상태 코드의 뜻 변경은 사용자에게 묻는다"에 닿는다. 커밋·`cross-repo.md` H절이 ui 영향 "없음, ui가 부르는 경로 6개의 인가 결과 그대로"로 처리. 근거 확인: kista-ui에서 `/api/auth` 호출을 grep한 결과 `refresh`·`me`·`logout`·`status-stream`·`kakao/callback`·`approval-requests`(`app/api/auth/reapply-done/route.ts:13`이 이 경로를 부름)와 dev 토큰 경로(e2e·스크립트)뿐이다. 맞다.
- 로그아웃 경로 무효 토큰 익명 통과 허용은 사용자 승인 대기로 남김(9-6의 "묻는다"): cross-repo H절. ui 프록시가 `Authorization`을 보내지 않아 jti 차단이 실제로 한 번도 일어나지 않았다는 사실도 H절이 기재(ui 코드는 이 측정에서 읽지 않음 — 미확인).

**배포 뒤 관찰·9-4**: 9-4 표에 이 변경 종류는 없다(인증 경로). 가장 가까운 칸은 9-6 우측 "ui가 읽는 상태 코드". 배포 전 확인표는 없다(cross-repo.md H절이 ui 영향 서술을 대신함). 관찰 정의: 지침서 14절 "U1은 인증 경로라 배포 뒤 로그인·토큰 갱신·로그아웃을 운영에서 한 번씩 해 본다(사용자에게 요청)", 로드맵 S12 칸 "배포 뒤 로그인·갱신·로그아웃을 운영에서 한 번씩 확인(사용자)". 실행 여부 기록은 없다.

### S14 조사·고정 테스트 (지침서 13 U3·U5 첫 단계, 12 F4 — `8732b3e37`)

| 항목 | 지침서 | 커밋 |
|---|---|---|
| U3 대조 테스트 | 시나리오 5종, 차이 목록을 `findings/20-stats-divergence.md`로 | `StatsCurveIndexDivergenceTest`(7 `@Test`, 288줄). 갈림 3건. 보고서는 `findings/25`(20번이 이미 사용 중이라 번호 변경, 25 머리에 기재). `findings/25`는 "권고 규칙"과 N15 판단을 포함(사실 보고 범위를 넘음) |
| 운영 빈도 | 지침서 요구 아님 | findings/25 §4에 운영 DB 읽기 전용 집계(2026-11-15): 갈림 조건 충족 시나리오 4는 0건, 시나리오 2는 1건(4일), 시나리오 5는 4건. 집계 SQL은 레포에 없음(미확인) |
| U5 첫 단계 문구 스냅샷 | "사용자 알림 23종" | 16종(26 `@Test`: root 6 + trading-core 20). 23→16 근거는 로드맵 S14 칸에만 있고 종류별 대조표는 레포에 없음(미확인) |
| F4 조사 | `findings/19-submitting-recovery.md`로 | `findings/26-submitting-recovery.md`(125줄). 지침서 04에 B10 추가(`04-broker.md:123`) |
| main 코드 | 불변 | 불변(커밋 6파일 모두 test·docs) |
| 실계좌 호출 | 금지 | 스펙 파일만 사용(findings/26 머리 "코드·실계좌 변경/호출 없음") |

**스스로 정한 결정**: 번호 변경(findings 25·26)과 스냅샷 16종은 9-2 U2. findings/25 §3은 지침서 3절 표의 두 문장("곡선은 입력 정렬에 의존", "사이클 순서 비교가 다르다")이 공개 경로에서는 결과 차이를 만들지 않는다고 정정한다. 근거 확인: `CapitalLedgerTimeline.java:48`에 `result.sort(Comparator.comparing(CyclePosition::createdAt))`가 있다. 맞다. 나머지 코드 줄 인용(`TradingStatsService.java:338-359`, `InvestmentIndexCalculator.java:267-288`)은 이 측정에서 열어 보지 않았다(미확인).

**배포 뒤 관찰**: 정의 없음(코드 불변). 단 U4(`DailyCycleBook`)가 값 변경을 일으키면 지침서 13 14절이 "규칙을 합치지 않고 계좌별 수치 표를 남기고 사용자 결정을 기다린다"를 정의함 — 미착수.

### S15 T1·T2 공용 시세 (지침서 14 — `f6d497105`)

**지침서 대조**

| 항목 | 지침서 | 커밋 |
|---|---|---|
| `MarketDataPort` 인터페이스 + Alpaca 구현 | 4-1절 | 인터페이스 없음. `broker/adapter/out/marketdata/AlpacaMarketDataAdapter`(public 클래스 `:44`, `@NamedInterface("marketdata")` `:43`, 273줄). 메서드 4개 `latestPrices`·`confirmedBars`·`closesBefore`·`dailyCandles`. 14의 4-1절에 "철회(2026-11-15)" 기재 |
| 반환 타입 | 새 값 타입은 없을 때만 | `BrokerCandle` 재사용, 신규 `DailyCandleQuery`(38줄) |
| `AlpacaProperties` | 11절: `shared.platform`에 한 벌, trading-core 두 벌 교체 | `shared/.../platform/alpaca/AlpacaProperties.java` 신설, trading-core marketcalendar·stats 두 벌 삭제. yml 키 `alpaca.*` 그대로(`application.yml:111-115`). root benchmark 것은 그대로 |
| `BrokerPricePort.currentPrices` | T3에서 8→1 메서드 축소. T2에서는 "메리츠 계좌 하나의 증권사 값" 필요 | 인터페이스에 `default currentPrices`(getPrices 위임) 추가, `BrokerRouter.currentPrices`가 `adminFirst`를 타지 않음. 기존 메서드·호출처 불변 |
| 7절 1번 어댑터 계약 테스트 | 5개 항목 | `AlpacaMarketDataAdapterTest` 9개(두 피드 더 늦은 체결, 한쪽 실패, 둘 다 실패, 전일 종가 end 없음, 확정 일봉 캐시 안 함, 16:16 전 캐시 안 함, 페이지네이션 2건 등). 변이 확인은 커밋·로드맵에 기록 없음(미기재) |
| 토스 `/api/v1/prices` 계좌 토큰 실호출 | T1에서 1회 | 하지 않음(Q-13) |
| T2 비교 로그 | `[시세 비교] ticker= kind= old= new= diffPct=` | `PriceSourceComparison`(118줄)·`PriceSourceComparisonTest`(9 메서드). `TradingService`의 5곳에서 호출: `:120`·`:358`(`afterLoad`), `:160`(`closes`), `:299`·`:442`(`current`). 비교 호출 실패는 `BrokerCallGuard.tryGet`(`PriceSourceComparison.java:113`). 수동 재실행 경로(`reportRebuilt`)에는 넣지 않음 |
| 메리츠 계좌 선택 | 보정 절: "생성이 가장 이른 것" | "UUID v7이 가장 작은 것"(배치 컨텍스트에 생성 시각이 없다는 이유, 14의 11절 T2 결과에 기재) |
| 경고 | "최근 체결이 N분보다 오래됨" | 넣지 않음(장외에 항상 오래됨, 기재) |
| `confirmedBars` 완결 전 호출 | | 거부하지 않음, 16:16(뉴욕) 전 값은 캐시만 안 함(기재) |
| 완료 판정 | `:trading-core:test --tests 'broker.*' 'trading.*'` + 아키텍처 | 실행 결과 기록 없음(미확인) |
| 수정 파일 | | 27파일 +979 −23. `TradingServiceTest`(+20) 등 기존 테스트 일부 수정 |

**스스로 정한 결정**

| 결정 | 유형 | HEAD 확인 |
|---|---|---|
| Q-12 인터페이스 대신 구체 클래스를 타입 단위 `@NamedInterface("marketdata")`로 공개 (자체 결정 ①). 근거: 가드 `interfaces_must_earn_their_keep`이 새 단일 구현 포트를 막고 제거 대기 목록이 상한 134로 차 있어 받지 못함. ②(의존 역전 목록에 더함)은 가드 약화라 기각 | 9-3(가드와 부딪힘 → 약화 금지, 뒤로 미루거나 피함). 14의 4-1 문장을 철회 | 가드 조건(`HexagonalArchitectureTest.java:430-440`): `port.output`의 최상위 인터페이스 중 구현 <2개는 `DEPENDENCY_INVERSION_PORTS`(4개)나 허용 목록에 있어야 함 — 맞다. 허용 목록은 당시 134줄·상한 134(`e72282b98`), 현재는 S6 단계 B 뒤 82줄·상한 82로 여전히 꽉 차 있다. 소비처: main에서 `PriceSourceComparison`(trading 모듈, `trading/application/service`) 1곳(`PriceSourceComparison.java:30,35,39`). test 2곳. 즉 모듈 밖(trading)이 `broker.adapter.out.marketdata` 클래스를 직접 의존한다. 지침서 01 단계 C 4번은 "포트가 NamedInterface로 모듈 밖에 공개돼 있고 구현이 `adapter/out` 아래라 모듈 밖에서 adapter를 참조하게 되는 경우는 인터페이스를 유지한다"라고 적었으나 이는 기존 포트를 지우는 단계 C의 문장이고 신규 시세 어댑터 얘기는 아니다. Q-12는 이 문장을 언급하지 않는다. 또 Q-12 근거는 "01의 유지 조건 셋에 14의 이유가 없다"인데, 유지 조건 2번(다른 모듈에 구현이 있어 순환을 끊는다)은 구현이 같은 broker 모듈이고 소비자가 trading이므로 해당 없음 |
| Q-13 토스 실호출 안 함, 토스 계좌는 T3에서 Alpaca 현재가 사용(자체 결정 ①) | 9-2 U3(실계좌 호출 금지) + 14의 6절 대체 경로 | 14의 6절 표: "T1에서 토스 계좌로 1회 호출해 확인한다. 안 되면 토스 계좌는 Mock처럼 `MarketDataPort.latestPrices`를 쓴다(운영에 토스 전략 0)". 11절 기본값: "아니면 하지 않고, 토스 계좌는 6절의 대체 경로로 만든다". 즉 호출하지 않을 때 대체 경로를 쓰는 것은 11절이 명시. `vendors/toss.md:71`에 해당 한 줄 존재. 같은 지침서 3-5절은 Alpaca 무료 현재가를 "주문 가격의 기준으로 약하다"고 평가하고 "Mock 계좌만 Alpaca"로 적었다 — 대체 경로를 쓰면 토스 계좌 주문 현재가가 이 약한 출처가 된다(두 문장이 같은 지침서에 공존). 토스 스펙 디렉토리 `/Users/phs/Downloads/toss/`는 9-1(2026-11-14)에 "비어 있음"이라 했으나 지금은 파일이 있고 `market/marketData.json`의 `GET /api/v1/prices`는 `security: oauth2ClientCredentials`, rate group `MARKET_DATA`로 적혀 있다. 스펙만으로 "계좌 토큰 호출 가능" 여부를 단정하지 못한다(실호출 안 함) — Q-13이 이 스펙 파일을 근거로 들지 않았다 |
| `UsTradeDates` 임시 헬퍼(`TradingService.java:169` 부근 "UsTradeDates.toUsTradeDate와 같은 규칙 — T3에서 삭제") | 9-3(가드 `usTradeDates_must_only_be_used_by_allowlisted_adapters` 회피) | 헬퍼 존재 |

**배포 뒤 관찰**: 정의 있음. 14의 8절 T2 행("3거래일, 통과 기준: 전일 종가 전부 일치, 현재가 차이 0.5% 이내, 확정 종가 SOXL·USD·QLD 일치·TQQQ 0.02달러 이내, 마감+20분에 확정 봉 조회")과 11절 "T2 실행 결과"의 관찰 명령(`ssh kista-server "docker logs kista-trading --since 24h 2>&1 | grep -F '[시세 비교]'"`). 로드맵 S15 칸: "관찰 대기(2026-11-16~18 거래일)". 9-4 분류: 주문 가격·수량·시각을 바꾸지 않는 추가(T1·T2) — 배포 전 확인표 없음. 단 비교 호출은 메리츠 실계좌를 접수 뒤에 부른다(`PriceSourceComparison` 5곳 중 현재가 2곳). 이 호출이 운영 메리츠 한도에 주는 영향은 미확인.

### S17 BITO·EETH (지침서 15 T0 — `fd433b432`)

| 항목 | 지침서 | 커밋 |
|---|---|---|
| `StrategyTicker` 상수 | QLD 모양 | `StrategyTicker.java:21-22`, 설명 "PROSHARES BITCOIN ETF"·"PROSHARES ETHER ETF". 줄 주석은 BITO "비트코인 선물 ETF", EETH "이더리움 선물 ETF"(Q-09 검토가 2024-09-27 이후 명칭에서 "Strategy"가 빠졌다고 적었다) |
| `StrategyType` DCA 허용 | 목록 **끝**에 추가 | `StrategyType.java:31`: UPRO, QLD, BITO, EETH, BTC, ETH — BTC·ETH 앞(Q-09에 이유 기재, 지침서 10절 "새 종목은 목록 끝에"와 다름) |
| `TradingPolicySettings` 기본값 | | `:45` 같은 순서 |
| 메리츠 거래소 코드 | **실측 뒤** 넣는다 | 실측 없이 QLD와 같은 `0066`/`AX`(`MeritzExchangeRegistry.java:21-22`). 10절 기본값은 "QLD와 같은 매핑을 넣고 배포 뒤 사용자가 확인"이라 지침서 보정 절과는 같고 본문 2-4절과는 다름 |
| KIS 매핑 | KIS 남아 있으면 `AMEX/AMS` | 추가(`KisExchangeRegistry.java:23-24`) |
| 테스트·문서 | `StrategyTypeCapabilityTest:69`, `TradingPolicySettingsTest:55,112`, `MeritzExchangeRegistryTest:20`, README, meritz 문서 | 모두 수정. 추가로 `AdminSettingsControllerTest`(16줄) 수정. 메리츠 문서는 당시 경로 `docs/agents/meritz-api.md`(이후 `vendors/meritz.md`로 이동, 현재 `:42`에 "실측 전" 기재) |
| 관찰: Mock 계좌로 두 종목 DCA 전략을 만들어 개장 배치 1회 | T0 표 | 커밋·로드맵·questions에 실행 기록 없음(미기재) |
| 완료 보고에 순서 기재 | "허용 전 현재가 확인" | 커밋 메시지·Q-08에 사용자 할 일 순서로 기재 |

**스스로 정한 결정**: Q-08(메리츠 매핑 미실측, 자체 결정 + Fable 검토가 유지로 동의) → 9-2 U3. 근거 확인: `MeritzExchangeRegistry.java:20`에 `QLD → ("0066","AX")`, 같은 클래스의 SOXL·USD·MAGX·UPRO 값도 같다는 서술은 `vendors/meritz.md:42`("SOXL·USD·MAGX·UPRO·QLD=AX/0066")와 일치. BITO·EETH 자체의 값은 미확인. Q-09(발행사 명칭·enum 위치) → 9-6. EETH 개명 공시 원문은 못 봤다고 Q-09 본문이 인정(2차 출처).

**배포 뒤 관찰**: 정의 있음(로드맵 S17 칸 "배포 뒤 순서: 메리츠 계좌 전략 등록 화면에서 두 종목 현재가 조회 확인 → 관리자 설정 DCA 허용 종목에 추가, 조회 실패 종목은 허용하지 않고 매핑 수정", Q-08 "사용자 할 일"). 운영 정책 JSON 자동 반영 안 됨(`TradingPolicySettingsPersistenceAdapter:60-85`는 이 측정에서 열지 않음 — 미확인). 9-4: 주문 가격·수량·시각이 바뀌지 않는 추가. 허용 전에는 새 종목 전략이 생기지 않으므로 운영 주문 영향 없음(이 문장은 Q-08 본문의 서술이며 직접 확인 안 함).

### 묶음 밖 커밋

| 커밋 | 내용 | 로드맵 행 |
|---|---|---|
| `2fe2fa152` | `InfinitePosition`·`InfiniteStrategy` 전반 배분을 별지점 쪽 먼저로 변경(main 2파일 +43 −… , 백테스트 골든 `infinite.txt` 230줄, 테스트 3개), `trading-formulas.md`·CHARTER·08 F5 문서 | 있음(`b3f2c2238`에서 추가, "판정 유지, 2026-11-15 배포 `252e63bfe`, 9-4 비교표 없이 배포돼 사후 판정"). 공식 판정은 `findings/28` |
| `afd3cc4a6` | `docs/reference` 정리: 라오어 원문 요약 추가, quantstack 49개 중 47개 삭제, `trading-formulas.md` 1절 조정 | 없음 |
| `029b3cb99` | `trading-formulas.md`·`infinite-v4.md` 해석 표시 | 없음 |
| `fd89d449f` | `.github/scripts/check-migrations.sh` pathspec을 `*.sql`로 한정, bats 8줄 | S6 칸에 "CI 오탐 수정 별도"로만 언급, 별도 행 없음 |
| docs(fable-review) `ebcbfa078`(22파일 +1,831 — 질문 유형화·지침서 보정 절), `fa907057f`·`03f6d25b3`·`dd822394e`(ui 회신 반영), `fb2f0ea35`·`125c07d6b`(S8·Q-10), `f51d70796`·`b227b3d62`·`e044e983c`·`252e63bfe`(로드맵·원장 상태 갱신) | 문서 전용 | 로드맵 자체 |

### 미확인 목록
- 모든 묶음의 컴파일·테스트·bats 통과(커밋 메시지·로드맵 주장만 있음).
- 운영 SELECT 결과(S11 위반 0건, S14 집계, S10 없음)와 배포 직전 재확인 결과(레포에 기록 없음).
- 실제 배포 여부와 시각(S6 칸 "미배포" 대 2fe2fa152 행 "배포 `252e63bfe`" 불일치).
- 토스 계좌 토큰 `/api/v1/prices` 호출 가능 여부, BITO·EETH 메리츠 거래소 코드, 메리츠 출발 IP 한도.
- kista-infra 쪽 `/` 모니터 유무, 레포 밖 로컬 잔재(T8).
- `TradingBatchStep`이 인용하는 `TradingOpenScheduler.canRun`의 04:30 상수 본문, findings/25의 `TradingStatsService`·`InvestmentIndexCalculator` 줄 번호, 알림 23종→16종 대조표.
- `TradingBatchGuard.runSafely` 경로가 유일 제약 위반을 관리자 알림으로 올린다는 S11 10절 주장.
- 최상위가 아닌 중첩 인터페이스 중 단일 구현 개수.

## 2. 판정 (Fable, 2026-11-15)

1절은 서브에이전트(Sonnet)의 사실 측정이다. 이 절부터는 그 사실과 운영 읽기 전용 조회(2026-11-15 11시 KST, `ssh kista-server`, SELECT·`docker logs`·`docker inspect`)로 판정한다. 빌드·테스트는 돌리지 않았다.

### 2-1. 묶음별 판정

| 묶음 | 지침서 의도대로인가 | 스스로 정한 결정 | 배포 뒤 관찰 |
|---|---|---|---|
| S6 단계 A | 그렇다. 규칙 하나를 지우지 않고 좁힌 것은 9-3(가드를 약화하지 않는다)에 맞다 | 맞다 | 필요 없음(테스트만 바뀜) |
| S6 단계 B | 그렇다. UseCase 52개 제거, 되돌린 모듈 없음. 완료 판정 명령의 실행 기록은 없다(미확인) | 맞다. 가드 대상 확대를 뒤로 넘긴 것에는 2-3에서 답한다 | **로드맵 표기가 틀렸다.** "미배포"라고 적혀 있지만 운영 `kista-trading`은 이미지 `252e63bfe`로 2026-11-15 10:56 KST에 기동했다(healthy). 매매 시간대 밖이라 조건은 지켰다. 관찰 항목이 없었으므로 "2026-11-16 개장 배치와 11-17 마감 리포트의 정상 완료"를 로드맵에 넣었다 |
| S8 | 그렇다. T9는 N11로 미뤘다 | Q-10 맞다(스펙 문장과 코드가 같다). gson·`index.html`도 맞다 | 정의가 없었다. 동작 변화 둘(gson 전이 의존, `/` 401)은 기동 healthy로 간접 확인됐다. 더 볼 것 없음 |
| S10 T1 | 그렇다. 사용처 없는 새 클래스와 표 테스트다. 지침서와 다른 다섯 가지는 전부 "지금 코드의 동작을 표로 옮긴다"는 T1의 목적에 맞춘 것이다 | 맞다(U2). 커밋 메시지의 행 수(30+25)는 실제(43+25)와 다르다. 12절의 대응 수(31)도 68과 맞지 않는다 — T2 착수 때 대응표를 다시 센다 | 필요 없음. T2부터 필요하다 |
| S11 | 그렇다. 키만 넓혔다(Q-11) | 맞다(2-2) | 정의돼 있다(11의 10절). 배포 직전 SQL의 재실행 기록은 없지만, 유일 인덱스는 위반 행이 있으면 생성되지 않으므로 V21이 성공한 것(운영 이력 2026-11-15 10:14 KST)이 0건의 증거다. 관찰은 11-16·17 거래일 그대로 |
| S12 | 부분. PENDING 차단은 남았고 로드맵에 적혀 있다. 지침서 13의 5절 fable-ui 요청 ①~④의 상태는 어디에도 적혀 있지 않다 | jti 차단 범위를 넓힌 것은 맞다(보안이 강해지는 쪽). `/api/auth/**`의 목록 밖 경로가 401로 바뀐 것은 9-6의 "ui가 읽는 상태 코드" 칸에 닿지만 ui가 부르는 경로 6개의 결과가 그대로임을 확인하고 넘어간 것이라 받아들인다 | 정의돼 있다(운영에서 로그인·갱신·로그아웃 1회, 사용자). 실행 기록이 없다 → 사용자 확인 대기로 둔다 |
| S14 | 그렇다(코드 불변, 조사와 고정 테스트). `findings/25`가 권고까지 쓴 것은 사실 보고 범위를 넘지만 해가 없다 | 맞다. 알림 23종 → 16종의 대조표가 없다 — U5 본 작업 전에 만든다 | 필요 없음 |
| S15 T1·T2 | 그렇다. 인터페이스 없이 구현한 것(Q-12), 토스 실호출 생략(Q-13) 외에는 지침서대로다 | 2-2 | 정의돼 있다(3거래일 비교 로그). 비교 호출이 메리츠 실계좌 시세를 추가로 부르므로 관찰 항목에 "메리츠 429·`BrokerRateLimitException` 로그 0건"을 더했다 |
| S17 | 그렇다(10절 기본값대로) | Q-08·Q-09는 `125c07d6b`에서 검토했다 | 사용자 확인 대기. 지침서 T0 표의 "Mock 계좌로 두 종목 DCA 전략을 만들어 개장 배치 1회"는 실행 기록이 없다. 관리자 설정에서 허용하기 전에는 전략을 만들 수 없으므로 순서상 사용자 확인 뒤의 일이다 |

### 2-2. 스스로 정한 결정 Q-11~Q-13

답의 원본은 `questions.md`의 각 항목 "검토" 줄이다. 요약만 적는다.

- **Q-11(유일 제약 키를 timing·direction까지) — 유지.** 앱의 슬롯 정의가 `(timing, direction, leg)`이고 제약이 그것과 같아야 정상 경로가 막히지 않는다. 운영 전체 이력에서 같은 (사이클·거래일·leg)에 슬롯이 둘 이상인 경우는 0건, 좁은 키로 본 살아 있는 중복도 0건이라 지금까지는 두 키가 같은 결과를 낸다. Q-11이 적지 않은 차이가 하나 있다: 인덱스는 FILLED·PARTIALLY_FILLED도 살아 있는 것으로 보는데 앱의 재계산 판정은 PLANNED·SUBMITTING·PLACED만 본다. 이 차이는 "체결된 슬롯에 같은 주문을 또 넣는 것"을 DB가 막는 방향이라 그대로 둔다(막히면 그 저장이 실패하고 주문이 나가지 않는다 — 돈이 덜 움직이는 쪽).
- **Q-12(`MarketDataPort` 없이 구체 클래스) — 유지, 후속 하나.** 01의 원칙(단일 구현 외부 연동은 구현 클래스를 직접 쓴다)과 같다. 다만 모듈 밖(trading)이 `broker.adapter.out.marketdata`의 벤더 이름 클래스를 본다. 소비처가 1곳인 지금 이름을 벤더 중립으로 바꾸는 것이 싸므로 14 T3의 첫 작업으로 넣었다(14의 11절).
- **Q-13(토스 실호출 생략) — 생략은 유지, T3의 토스 기본값은 조건을 단다.** 실계좌를 부르지 않은 것은 9-2 U3대로다. 그러나 대체 경로(토스 계좌 주문 현재가를 Alpaca로)는 2026-11-14 사용자 결정 "주문 경로의 현재가는 계좌 증권사"와 어긋난다. 운영에 토스 전략이 0건이라 지금은 영향이 없다. T3 착수 전에 사용자에게 1회 호출을 요청하고, 답이 없으면 대체 경로로 가되 "토스 전략이 등록되기 전에 다시 확인한다"를 14의 11절에 적었다.

### 2-3. 지침서 01 9절의 마지막 항목

01의 9절 "단계 B 실행 결과" 바로 아래에 답을 적었다. 요지: 대상을 넓힌다. 시점은 단계 C의 5번이 아니라 단계 C의 첫 커밋이다. 영구 예외는 이름 목록이 아니라 술어(프레임워크가 구현하는 인터페이스, sealed, `@FunctionalInterface`, 구현이 다른 모듈·프로젝트에 있는 것)로 두고, 이름 목록 `single-impl-interfaces.txt`는 줄어들기만 하는 "제거 대기" 한 종류만 남긴다. 지금 대상 밖의 단일 구현은 2개(`TokenBlacklistPort`, `TossTokenStore`)라 넓히는 비용이 작다.

### 2-4. 절차에서 어긋난 것

| 무엇 | 어긋난 규칙 | 결과 | 처리 |
|---|---|---|---|
| `2fe2fa152`(전반 배분 순서)가 전후 비교표와 사용자 승인 없이 배포됐다 | 9-4(주문 수량이 바뀌는 변경), 08의 6절(사용자 결정 없이 공식을 바꾸지 않는다). 08 F5의 "유지" 결정을 뒤집는 변경이기도 하다(9-6 우측 칸) | 사후 판정은 유지다(`findings/28` 0절). 진행 중 전략에 영향이 없었고 방향도 돈이 덜 움직이는 쪽이었다 | 08의 8절에 "같은 일을 되풀이하지 않는다"를 적었다. 커밋 메시지에 "소유자 결정"이 있었는지는 확인하지 못했다 — `trading-formulas.md` 1-3은 아래 단계 3건·지정가 시각만 "2026-11-15 소유자 결정"으로 적고 배분 순서에는 그 표시가 없다 |
| 같은 커밋이 백테스트 골든(`infinite.txt`)을 고쳤다 | 9-5 끝 문장은 "동작 보존 테스트의 기대값을 고치지 않는다"다 | 공식 변경 커밋이라 동작 보존 테스트가 아니다. 위반이 아니다 | 없음 |
| 로드맵 S6 "미배포" | 9-4 끝("관찰 결과를 로드맵 상태 칸에") | 실제로는 배포됨 | 로드맵을 고쳤다 |
| 로드맵 S2 "2026-11-15 배포" | 같다 | 거래일 2026-11-14 주문이 이미 S2 뒤의 값이다(`findings/28` 0-2). 실제 배포일은 그보다 앞이다 | 로드맵 S2 칸에 관찰 결과를 적었다. 배포 시각은 미확인 |
| S11·S15·S6의 완료 판정 명령 실행 기록이 커밋·로드맵에 없다 | 지침서의 "완료 판정" | 미확인. 배포 파이프라인을 통과해 기동했다는 것만 확인됐다 | 다음 묶음부터 완료 판정 명령과 결과 한 줄을 로드맵 상태 칸에 적는다(권고) |

### 2-5. 운영 조회 결과 (2026-11-15 11시 KST)

- 컨테이너: `kista-trading`은 이미지 `252e63bfe`, 10:56 KST 기동, healthy(`docker inspect`). `kista-api`·`kista-scheduler`도 같은 시각대에 기동해 healthy다(이미지 태그는 보지 않았다).
- trading Flyway 이력 최신: V21 `ledger unique constraints`, 성공.
- `uq_orders_live_slot`·`uq_strategy_cycle_open` 정의가 V21 파일과 같다.
- 살아 있는 상태의 (사이클·거래일·leg) 중복 0건, 같은 leg에 슬롯 둘 이상 0건(전체 이력), 열린 사이클이 둘 이상인 전략 0건.
- 기동 뒤 `kista-trading` 로그에 `uq_orders_live_slot`·`uq_strategy_cycle_open`·`ConstraintViolation` 0건. 기동 뒤 배치는 아직 돌지 않았다(첫 배치는 2026-11-16 22:00 KST).
- 진행 중 전략: PRIVACY 2, VR 2, DCA 1, INFINITE 1.

## 3. 후속 (지침서·로드맵에 적은 곳)

| 무엇 | 적은 곳 |
|---|---|
| 가드 대상 확대의 방향과 허용 기준 | 지침서 01의 9절 |
| 시세 어댑터 이름을 벤더 중립으로, 토스 현재가 출처 재확인 | 지침서 14의 11절 |
| 원문 재대조 후속 T8~T10, 공식 변경 절차 | 지침서 08의 8절, 로드맵 S18 |
| S6 배포 사실과 관찰, S2 관찰 결과, S15 관찰 항목 추가, S12 미기재 항목 | 로드맵 각 묶음 칸 |
| Q-11~Q-13 검토 | `questions.md` |
