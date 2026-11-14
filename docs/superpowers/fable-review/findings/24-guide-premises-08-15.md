# 지침서 08~15 전제·가드 측정 (2026-11-14)

> **정정(Fable, 2026-11-14):** 아래 중 세 건은 검색 오류다. ① 14의 "`waitUntilPostClose` 0건" — `DstInfo.waitUntilPostClose`가 있다(`TradingService.java:151,174,240`). ② 15의 "`OrderType.MARKET` 거부 분기 미확인" — `MeritzOrderApi:82`·`TossOrderApi:183`·`KisOrderApi:159`의 `case MARKET`. ③ 08의 "`INFINITE_MOC_SELL` 0건" — `InfiniteStrategy.java:200`. 또 14의 환율 중단 조건은 `trading`·`matching` 패키지로 좁히면 0건이다. 지침서의 "실행 전 보정" 절에는 바로잡은 값을 썼다.

측정만 했다. 지침서·코드 수정, git add/commit 없음. 운영 서버 접속·빌드·테스트 실행 없음. 유형 건수는 아래 본문 표의 행을 센 근사치이고, 한 문장이 두 유형을 겸하면 주된 유형 하나로 셌다.

## 지침서 x 유형 건수 (남은 태스크 대상 행)

| 지침서 | U1 | U2 확인 | U2 어긋남/대상 없음 | U3 | U4 | U5 | U6 | U7 걸림 |
|---|---|---|---|---|---|---|---|---|
| 08 공식 (T7만 남음) | 0 | 0 | 0 | 0 | 1 | 2 | 1 | 0 |
| 09 문서 (T1~T9 전부) | 0 | 19 | 3 | 0 | 2 | 1 | 3 | 2 |
| 10 UI 계약 (T3~T12) | 0 | 11 | 2 | 2 | 1 | 2 | 2 | 2 |
| 11 DB (T1~T9) | 0 | 19 | 1 | 1 | 2 | 4 | 3 | 1 |
| 12 기능 (F1·F2·F4) | 2 | 12 | 2 | 1 | 0 | 2 | 0 | 2 |
| 13 미지정 영역 (U1~U9) | 0 | 25 | 6 | 1 | 2 | 3 | 3 | 3 |
| 14 시세 (T1~T7) | 4 | 24 | 3 | 1 | 2 | 3 | 3 | 4 |
| 15 크립토 (T0~T7) | 2 | 22 | 2 | 1 | 2 | 3 | 2 | 4 |

(U2 "확인"은 `파일:줄`·클래스·줄 수를 대조한 행 수. 정확한 개수가 아니라 규모 감각용이다.)

## 어긋난 U2 전제 (중요도 순)

| # | 지침서·태스크 | 지침서 서술 | 현재 코드 | 영향받는 단계 |
|---|---|---|---|---|
| 1 | 14 T3·T7 | `waitUntilPostClose` 기준을 바꿔 마감 리포트를 +20분으로 | 그 이름의 메서드 0건. 대기는 `TradingService.java:151,174`의 `waitFor("마감…")` | T3의 시각 변경 위치 특정. 06 판정표 상수도 아직 없음 |
| 2 | 15 T4 | `OrderType.MARKET` 호출처가 코인 규칙과 주식 어댑터 3곳의 거부 분기뿐 | 어댑터 디렉토리에서 `OrderType.MARKET` 리터럴 검색이 비어 3곳을 확인하지 못함(정적 import일 수 있음, 미확인) | T4의 MARKET 삭제와 8절 중단 조건 4 |
| 3 | 10 T11 | `GET /api/accounts/{id}`·`GET /api/trading-cycles/{id}` 단건 조회가 없다 | `GET /api/trading-cycles/{id}`는 이미 있음(`TradingCycleController.java:86`). 계좌 단건만 없음(`AccountController`의 GET은 목록 `:33` 하나) | T11의 절반은 대상 없음 |
| 4 | 09 T3 | `UserSummaryAdapter`(main 참조 0 의심) 삭제 판정 | 구현 대상 포트 `UserSummaryPort`가 main 2곳(`AdminService:31`, `UserModuleFinanceMemberAdapter:21`)에서 쓰임 | 삭제 불가. 의심 전제 틀림 |
| 5 | 13 U2·U9 | `findOrDefaultByUserIds`·`SYSTEM_LOAN_ID` 삭제 대상, `AuditAction` enum(지금 7종), `CryptoMarketIndicatorsOpenApiCustomizer` | 네 이름 모두 main에 0건(이미 삭제·개명이거나 지침서 명칭 오류) | 9절 표 4행·2절·3절 |
| 6 | 13 U2 | `BrokerApiException.isRejected` 테스트 호출 48곳 | 57곳 | 치환 범위만 |
| 7 | 13 U5 | `TradingErrorEvent` 직접 `new` 37곳, `fallbackExecution` 리스너 18개 | `new TradingErrorEvent` 38곳, `fallbackExecution = true` 23곳 | 분류 대상 수 |
| 8 | 11 전반 | 소프트 삭제 `@SQLRestriction` 14개 | 지금 32줄 선언(25파일) | T3 제거 범위 산정(중복 선언 여부 미분류) |
| 9 | 14 3-5 | `BuyOrderPriceCapper:44-45,79` 현재가 사용 | `:44-45`는 필드 선언(줄 번호 이동) | 근거 인용 위치만 |
| 10 | 09 4-1 | backlog 972줄, `deploy/server/README.md:129`, 포인터 `CLAUDE.md` 31개 | 970줄, `:128`, 29개 | 영향 없음 |
| 11 | 12 F2 | `GET /api/accounts/{accountId}/daily-trades` 삭제 | `@GetMapping("/daily-trades")`가 `DashboardController:53`·`StatisticsController:53` 두 곳. 어느 쪽이 계좌 범위인지 prefix 미확인 | F2의 삭제 대상 특정 |
| 12 | 14 T1 | "`AlpacaProperties`는 trading-core에 이미 있는 것" | 3벌(root benchmark, trading-core marketcalendar, trading-core stats). 05 Q-07로 통합이 단계 X·R로 미뤄짐 | T1에서 어느 것을 쓸지 미지정 |
| 13 | 10 T4·T3 | 커서 형식 사용처 | `CycleHistoryPageResponse`가 stats·trading 양쪽에 같은 이름으로 존재 | T3 통합 때 OpenAPI 스키마 이름 충돌 가드와 만날 수 있음(미실행) |

12 F2의 `_ping`: 일치하나 `AdminPingControllerTest`가 testing.md의 인가 테스트 본보기로 인용됨(삭제하면 문서 참조 끊김).

## 걸리는 U7 목록 (가드 예외·flows.yml `code:`에 박힌 것)

| # | 태스크 | 가드·참조 | 같이 고쳐야 하는 것 |
|---|---|---|---|
| 1 | 15 T4·T5 | `flows.yml` `code:` 참조 9줄(`:711,741,811,834,1629,1637,1646,1655,1662`)에 삭제 클래스(`UpbitAuthApi`·`BinanceAuthApi`·`CryptoMarketIndicator*`·`CryptoDca*`·`CryptoOrderSynchronizer`·`BinanceExchangeAdapter`·`CryptoTradeNotifier`·`HeartbeatPort#pingCrypto`). `ArchitectureMapTest`가 `code:` 존재를 검증 | 흐름 711·741·834는 흐름은 두고 `code:` 항목만 빼야 한다는 서술이 지침서에 없음. landscape `flows.yml:58`의 `api-key-expiry`가 `flow: crypto-dca`를 가리켜 흐름 삭제 시 참조가 끊김(지침서 누락) |
| 2 | 14 T5 | `flows.yml` `:118,172,188,315,372,672`의 `TradingPriceFetcher#loadPriceContext/reloadPlacementPrices/fetchClosingPrices`, `AdminMarketPriceAdapter`, `:531`의 `BrokerPricePort#getPrevClose` | 메서드·클래스 삭제와 같은 커밋에서 flows.yml 수정 필요(지침서 T5는 flows.yml을 문서 목록에만 적음) |
| 3 | 14 T1·T5 | `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters` `:112-130`: allowlist에 `TossPriceApi`(`:124`)·`MeritzPriceApi`(`:126`) FQCN | 신규 Alpaca 어댑터가 `UsTradeDates`를 쓰면 allowlist 추가 필요, `TossPriceApi` 삭제 시 주석 `:116`·목록 정리 |
| 4 | 15 T4 | `ExceptionResponseTableTest.java:73`(trading-core)에 `BrokerIpNotAllowedException` 행, `ErrorCode.java:8`, `BrokerProblemDetails.java:35` | C5로 ErrorCode 삭제 시 표 테스트 행도 삭제(지침서 6절 목록에 이 테스트 없음) |
| 5 | 09 T2 | `.github/tests/deploy-plan.bats:9`의 `deploy/server/schema-reorg/RUNBOOK.md` 경로 문자열, `V1__init.sql` 2개 주석(체크섬) | 지침서에 있음. `check-migrations.sh:54`가 V1을 면제해 주석 수정을 스크립트가 못 막음 |
| 6 | 13 U5 | 이벤트 클래스 이름 변경(`MarketClosedEvent`·`CycleEndedEvent` 등)과 EPR 고아 행 | 지침서에 있음(배포 직전 EPR 정리). `EventPublicationRegistryTest` 내용 미확인 |
| 7 | 13 U1 | PENDING 차단 시 `@WebMvcTest`의 `authentication(UUID)` 주입 테스트(role 없음)가 `hasAnyRole` 규칙에서 깨질 수 있음(미실행) | 지침서 10절은 정책 표 테스트만 언급 |
| 8 | 14·15·09·13 | 마이그레이션 번호: trading 다음 V21, root 다음 V7. 11 T1·T4, 15 4절, 14·13이 같은 번호를 가정 | 번호 경합(지침서에 번호 미기재) |

기타 크로스 충돌: 09 T3·13 U9의 `.env.example`에 `CMC_API_KEY` 추가 대 15의 제거. 15는 `HEARTBEAT_CRYPTO_URL`·`CMC_API_KEY`·`TOSS_ADMIN_*`(14)를 `.env.example`·`deploy/server/README.md`에서 뺀다.

## 지침서별 절 유무 (판단이 갈릴 때의 기본값 / 중단 조건 / 하지 말 것)

| 지침서 | 기본값 절 | 중단 조건 절 | 하지 말 것 절 |
|---|---|---|---|
| 08 | 없음 | 없음 | 6절 |
| 09 | 없음 | 없음 | 10절 |
| 10 | 없음 | 없음 | 6절 |
| 11 | 없음(8절 표의 "고르지 않으면" 열) | 5절 끝 | 6절 |
| 12 | 없음(8절 표의 "답이 없으면" 열) | 없음 | 9절 |
| 13 | 없음(3절에 통계 한정 기본값) | 11절 표 아래 | 12절 |
| 14 | 9절 | 9절 | 9절 |
| 15 | 8절 | 8절 | 8절 |

## 보조 사실

- 승인 주체·확인 SQL 본문이 지침서에 없는 U5: 08 T7(SELECT 본문), 11 T1·T8(확인·정리 SQL), 14 T2·T3(승인 주체), 15 T3·T4(확인 SQL, 롤백 불가 서술). 11 T6·T8의 운영 정리 SQL은 PROGRESS에서 "보여준 뒤 돌린다"로 승인됨.
- `/Users/phs/Downloads/toss/`는 비어 있어 14 T1의 토스 시세 계좌 토큰 호출 전제는 레포 밖 스펙으로 대조 불가. 메리츠 스펙 JSON은 존재.
- 08은 T1~T6 완료, 남은 건 T7 배포 승인 절차뿐. 12는 D1·D3·D4·D5 결정으로 F5~F8이 사라지고 F2·F4(+14로 이관된 F1)만 남음.

---


# 본문 (지침서별)

측정 2026-11-14. 유형: U1 외부 문서, U2 코드 전제, U3 스펙 공백, U4 2차 효과, U5 배포 승인, U6 중단 조건, U7 가드 충돌.
"대체 경로" 열: 지침서 안에 이미 있는 기본값·대체 경로의 절 번호. 없으면 "없음".

## 지침서 08 매매 공식

실행 상태: T1~T4·T6 완료(`66213af3c`·`480c456ff`·`831ed3ade`·`1050a42e3`·`0f137cff2`·`18e3ddd4c`). T5는 별도 커밋 없이 T6 문서와 `VrPosition.java:65` 주석으로 반영됨(확인: `grep velog trading-formulas.md VrPosition.java`). 남은 태스크는 **T7(배포 승인 절차) 하나**다. 배포 여부는 `git log`에 deploy 커밋이 없어 미배포로 본다(운영 확인은 하지 않음).

| 태스크 | 유형 | 지침서 문장 | 확인 결과 | 대체 경로 |
|---|---|---|---|---|
| T7 | U5 | 7절 "배포 직전 확인(읽기 전용 SELECT): … 0건" | 승인 주체(사용자, 2026-11-13)·시점(마감 리포트 뒤·22:00 전)·0건 조건은 있다. **SELECT 문 자체가 지침서에 없다**(Q-05 본문에만 조건 서술). `INFINITE_MOC_SELL`은 orders의 leg 값이라는 전제를 코드 grep으로 확인하지 못함(`grep -rn INFINITE_MOC_SELL`이 trading-core main에서 0건 — zsh glob 오류로 재확인 불가, 미확인) | 7절 "0건이 아니면 배포하지 않는다"(중단만, 이후 경로 없음) |
| T7 | U4 | 7절 "옛 가격 행이 하루 유지되거나 옛 MOC와 이중 주문" | 부작용을 지침서가 이미 적음 | 7절(0건 확인) |
| T7 | U6 | 7절 "0건이 아니면 배포하지 않는다" | 멈춘 뒤 대체 경로 없음(다음 거래일 재확인인지, 해당 주문 취소 후 진행인지 불명) | 없음 |
| T7 | U5 | 7절 "push는 사용자가 요청할 때 한다" | 배포 트리거(push→CI 자동 배포인지)와 "마감 리포트 뒤" 시각의 구체값이 없음. `docs/agents/scheduler-time-table.md`에 있는지 미확인 | 없음 |

U2 확인: 남은 태스크(T7)는 코드 전제가 없다(미적용 전제 없음).

- "판단이 갈릴 때의 기본값" 절: 없음. 가장 가까운 것은 3절(사용자 결정 표)과 4절 공통 규칙.
- "중단 조건" 절: 없음(7절 T7 문장 하나뿐).
- "하지 말 것" 절: 있음(6절).

## 지침서 09 문서 구조 개편과 불필요 파일 삭제

실행 상태: 전혀 시작 안 됨(`ls docs/superpowers/plans/archive`=110, `specs/archive`=69, backlog 970줄, `deploy/server/schema-reorg/` 4파일, `own-type-ledger.md`·`docs/history`·`docs/reviews` 모두 존재). 승인 A~G는 PROGRESS에 "전부 권고대로, G는 4-1 확인 뒤, F의 `.superpowers/`는 내용 확인 뒤"로 확정. 태스크 T1~T9 전부 남음.

### U2 코드·파일 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| T2 | 4-1 "archive 110파일 / specs 69파일" | `ls docs/superpowers/plans/archive \| wc -l` 등 | 일치(110/69) |
| T2 | 4-1 "backlog 972줄" | `wc -l docs/agents/code-quality-backlog.md` | 어긋남(지금 970줄, 영향 없음) |
| T2 | 4-1 "인용 3곳: `workflow-batch.md:18`·`deploy/server/README.md:129`·`workflow-orders.md:8`" | `git grep -n "trading-batch-resume\|order-submission-claim"` | 사실상 일치. README 인용은 `:128`(줄 번호 1 어긋남). 3곳 모두 `docs/superpowers/specs/archive/...`를 가리킴 |
| T2 | 4-1 "`.github/tests/deploy-plan.bats:9`의 경로 문자열 교체" | `grep -n schema-reorg .github/tests/deploy-plan.bats` | 일치(`:9`에 `deploy/server/schema-reorg/RUNBOOK.md`가 판정 입력 인자로 박혀 있음, 기대값은 `verify=false config=false ...`) |
| T2 | 4-1 "`flyway.md:10` 포인터" | `grep -n schema-reorg docs/agents/flyway.md` | 일치(`:10`) |
| T2 | 4-1 "`V1__init.sql` 2개 주석은 고치지 않는다" | `grep -rn schema-reorg src/main/resources/db trading-core/src/main/resources/db` | 일치(양쪽 `V1__init.sql:2`). `check-migrations.sh:54`는 `V1__*`를 제외하는 로직이 있어 주석 수정이 이 스크립트로는 안 잡힘(체크섬은 Flyway 기동 때만 잡힘) |
| T2 | 4-1 "`static/index.html` 28줄, `/` 응답 확인" | `wc -l`; `grep -rn '"/"' src/main/java` | 28줄 일치. `SecurityConfig`의 `/` permitAll 규칙은 grep에서 보이지 않음(미확인: SecurityConfig를 직접 읽지 않음). Caddy는 `/actuator/health/liveness`만 헬스로 씀(`kista-api.caddy:12,21,26`) → `/` 헬스체크 없음 |
| T2 | 4-1 "own-type-ledger·README.md 참조 정리" | `git grep -n "own-type-ledger\|code-quality-backlog"` | `CLAUDE.md:40,51`, `constraints.md:26,35,37`, `architecture.md:15`, `docs/agents/README.md:12`, `flyway.md:21,42`가 걸림(지침서가 든 `architecture.md:15`·`constraints.md:26`·`flyway.md` 포함, `CLAUDE.md`도 포함돼야 함) |
| T3 | 4-4 "`kernel.md:3` 테스트명 `sharedkernel_must_not...`" | `sed -n 3p kernel.md` | 일치(`HexagonalArchitectureTest.sharedkernel_must_not_depend_on_other_modules` 서술이 남아 있음) |
| T3 | 4-4 "`SERVER_HOST` 서술" | `grep -n SERVER_HOST` | 일치(`docker-infra.md:21,109,115`, `deploy/server/README.md:24`) |
| T3 | 4-4 "`.env.example`에 `CMC_API_KEY=` 추가" | `grep -n CMC .env.example` | 0건(추가 대상 맞음). 단 15(크립토 삭제)가 `CMC_API_KEY` 항목을 없앤다고 로드맵 8절이 적음 → **09 T3와 15 충돌** |
| T3 | 4-4 "`runtimeOnly(libs.gson)` 제거해 보고" | `grep -n gson build.gradle.kts` | 일치(`build.gradle.kts:54`, 카탈로그 `libs.versions.toml:9,45`) |
| T3 | 4-4 "`mockwebserver3:5.5.0` 하드코딩" | `grep -n mockwebserver` | 일치(`build.gradle.kts:97`, 카탈로그엔 없음) |
| T3 | 4-4 "`UserSummaryAdapter`(main 참조 0 의심)" | `git grep -n UserSummaryPort -- '*.java'` | **어긋남**: 포트 소비처가 main에 2곳(`AdminService.java:31`, `UserModuleFinanceMemberAdapter.java:21`)이고 테스트도 있음 → 삭제 대상 아님 |
| T4·T5 | 4-2 "포인터 CLAUDE.md 31개" | `git ls-files \| grep -c "CLAUDE.md$"` | 어긋남(29개, 루트 `CLAUDE.md`·`.github/CLAUDE.md` 포함) |
| T6 | 2-5 "리소스 디렉토리 `CLAUDE.md` 2개를 `.claude/rules/flyway.md`로" | `ls` 두 경로, `ls .claude/rules` | 2개 일치. `.claude/rules/`에는 `java-encoding.md`·`statistics-controllers.md`만 있고 flyway.md 없음(신설 대상) |
| T6 | 2-4 "항상 로드 5파일 약 44KB" | `wc -c` | 일치 근사(CLAUDE 7.7 + constraints 17.9 + architecture 9.6 + testing 6.0 + commands 4.2 = 약 45.4KB) |
| T6 | 4-3 "constraints 17.6KB → 9KB" | 위와 같음 | 17.9KB로 일치 근사. 단 14·15·05 실행으로 바뀌는 중 |
| T7 | 7절 `check-doc-budget.sh`·bats 신설 | `ls .github/scripts` | 스크립트 3개(`check-migrations.sh`·`detect-deploy-scope.sh`·`plan-deploy.sh`)만 있음. 신설이라 충돌 없음. 단 `deploy-plan.bats`의 경로 판정 입력에 새 스크립트 경로 규칙이 필요한지는 미확인 |
| T8 | 4-5 로컬 잔재 목록 | `ls -d bin logs trading-core/logs .superpowers ../kista-api-worktrees ../ARCHITECTURE.md` | 6개 전부 존재 |

### U7 가드·참조 충돌

| 대상 | 가드·참조 | 결과 |
|---|---|---|
| 문서 삭제 전반 | `src/test/java/com/kista/api/architecture/*` 12개 테스트 | 문서 경로를 읽는 가드 없음(`git grep docs/ -- src/test/java/.../architecture`가 0건) → 안 걸림 |
| 테스트 주석의 문서 경로 | `StrategyVrDetailTest.java:13`·`StrategyCyclePortTest.java:22` | 주석에서만 `docs/agents/modules/tra…`·`docs/agents/testing.md`를 가리킴. 모듈 문서 축소·이동 시 가리키는 절이 없어질 수 있음(컴파일·가드 영향 없음) |
| `deploy/server/schema-reorg/` 삭제 | `.github/tests/deploy-plan.bats:9` | **걸림**: 경로 문자열을 다른 문서 경로로 바꿔야 함(지침서 4-1에 있음) |
| `V1__init.sql` 주석 | Flyway 체크섬 | 건드리면 기동 실패(지침서가 금지 명시). `check-migrations.sh:54`가 V1을 면제하므로 스크립트가 막아 주지 않음 |
| `static/index.html` | `CaddyRoutingTest`(루트 architecture 테스트) | 이 테스트가 `/`를 보는지는 미확인(읽지 않음) |
| 문서 예산 CI 신설 | `.github/workflows/ci.yml`·`detect-deploy-scope.sh` | 문서만 바뀐 PR이 CI를 타는지 판정 스크립트(`scope docs/x.md → verify=false`, `deploy-plan.bats:9`)에 걸림 → bats 1개 추가가 문서 변경에도 돌려면 scope 규칙을 건드려야 할 수 있음(미확인: 스크립트 본문 미독) |
| `flows.yml` | `code:` 참조 | 문서 파일 참조 없음(`grep 'docs/' flows.yml` 결과 비어 있음) |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| T2 | U5 | 4-1 "삭제 커밋 직전 `git tag archive/docs-2026-11`(push는 사용자 요청 시)" | 승인(A~G)은 PROGRESS에 있음. 태그 push 시점·주체는 "사용자 요청 시"뿐 | 8절 승인표 |
| T2 | U6 | 4-1 `static/index.html` "삭제 후 `/` 응답 확인, 외부 모니터가 `/`를 보지 않는지 … kista-infra에 확인" | 확인 결과가 "모니터가 `/`를 본다"일 때 대체 경로 없음(승인 G도 "4-1 확인 뒤") | 8절 G(조건부 승인) |
| T3 | U6 | 4-4 gson "FCM 초기화가 전이 의존으로 받으면 삭제, 아니면 한 줄 주석으로 유지" | 양쪽 분기를 모두 지침서가 정함 | 4-4 해당 행 |
| T8 | U6 | 8절 F ".superpowers/는 내용 확인 후" | 확인 기준(무엇이 있으면 지우지 않는가) 없음 | 없음 |
| T1 | U4 | 2-3 "뒤집는다·소멸 판정 항목은 넣지 않는다" | 각 지침서 재심 표가 판정 원본. 13·14·15에는 재심 표 유무 미확인. 2-3의 열거는 01~08·10만 포함(11~15 빠짐) | 7절 T1 완료 판정(대조표) |
| T2 | U7·U4 | 4-1 "배포 전 이벤트·리스너 패키지 이동 필요" | 해당 없음(문서 전용) | — |
| T4 | U4 | 4-2 "이유는 코드 주석에 이미 있는지 확인하고 없으면 주석으로 옮긴 뒤 지운다" | 기준은 있음. 다른 지침서(01·05·06·14·15)가 같은 문서를 바꾸므로 순서 충돌은 11절 "단계 1은 먼저 할수록 좋다" | 11절 |

- "판단이 갈릴 때의 기본값" 절: 없음(2-1 "남기는 기준" 두 질문이 사실상 기본값).
- "중단 조건" 절: 없음.
- "하지 말 것" 절: 있음(10절).

## 지침서 10 UI 계약

실행 상태: T1·T2 완료(`1915ba682`, S3). `PlainDecimalSerializer` 사본은 코드에서 사라짐(`git grep -ln PlainDecimalSerializer -- '*.java'` 0건). P1(예외 처리기 통합)도 끝남(`0d23b822b`)이라 T7 선행 조건은 풀림. 남은 태스크: T3~T12 전부(`PageRequest`·`CursorPage`·`Items` 신설 없음 — `git grep CursorPage` 0건. `PageRequest`는 Spring Data 타입이 trading-core 어댑터 3곳에서 쓰일 뿐 — 신설 시 이름이 Spring의 `org.springframework.data.domain.PageRequest`와 겹쳐 import 혼동 가능).

### U2 코드 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| T11 | 5절 "단건 조회 `GET /api/accounts/{id}`, `GET /api/trading-cycles/{id}`. 지금은 UI가 목록을 받아 찾는다" | `grep -n Mapping AccountController.java TradingCycleController.java` | **어긋남**: `GET /api/trading-cycles/{id}`는 이미 있음(`TradingCycleController.java:86`). `GET /api/accounts/{id}`는 없음(`AccountController`의 GET은 `:33` 목록 하나뿐, `@PutMapping("/{id}")`만 `:62`). T11의 절반은 대상 없음 |
| T10 | 5절 "기존 `GET .../strategy-seed-preview`는 `derived`로 흡수" | `git grep strategy-seed-preview` | 일치(`TradingCycleController.java:228`, 컨트롤러 테스트 `:340,352`) |
| T10 | 5절 "`POST /api/accounts/{accountId}/trading-cycle-validations`" | `git grep trading-cycle-validations` | 아직 없음(신설 대상) |
| T4 | 5절 "`/stats/cycles`는 전량을 읽고 메모리에서 자른다" | `git grep nextCursor`로 커서 사용처 확인 | 커서 형식 사용처: `CycleHistoryPageResponse`(stats·trading 각 1), `StrategyHistoryQueryService`, `AccountStatisticsService`. 지침서 1절의 "커서 2개 엔드포인트"와 대응(응답 DTO가 2벌 — 같은 이름 `CycleHistoryPageResponse`가 stats·trading 양쪽에 있어 T3 "기존 커서 2개를 이 타입으로 옮긴다" 때 OpenAPI 스키마 이름 충돌 가드(`OpenApiSchemaNameCollisionTest`)와 부딪힐 수 있음, 미실행) |
| T4 | 5절 관리자 PRIVACY `AdminPrivacyTradeController.java:44` 파라미터 `range` | `sed -n 44p` | 일치(`@RequestParam(required = false) Integer range`) |
| T5 | 1·2-3절 "`MetaBundle` 18필드, `EnumMeta.description` 항상 null, `/api/runtime-config`에서 이관" | `git grep runtime-config`, `sed MetaBundle` | `RuntimeConfigController.java:23`(`@GetMapping("/api/runtime-config")`)은 root `admin` 소속, `VrRampInput.withDefaults`는 trading-core(`trading/domain/creation/VrRampInput.java:25`). **프로세스가 다르다**: 메타(`api.web.MetaController`, root)가 VR 램프 기본값표(trading-core)를 싣게 하면 root→trading-core HTTP 또는 `shared.contract` 경유가 필요한데 지침서에 경로 서술이 없음. `MetaBundle` 필드 수는 직접 세지 못함(미확인, 컴포넌트 구분 불가) |
| T5 | 2-3절 "응답·요청 enum ⊆ 메타 enum 가드" + "`userStatuses` REJECTED 라벨" | `git grep userStatuses -- src/main/java/com/kista/api/web` | 메타에 `userStatuses` 없음(신설). 가드 테스트는 아직 없음 |
| T6 | 2-4절 `allowedActions`·`apiKeyExpiry` | `git grep "allowedActions\|apiKeyExpiry\|blockedActions"` | 응답 필드로는 아직 없음. 연관: `Broker.apiKeyExpirySource`(`Broker.java:22`)·`BrokerMeta.apiKeyExpirySource`만 있음(업비트는 API 조회라는 주석 — 15 크립토 삭제 뒤 이 enum 분기가 바뀜) |
| T7 | 2-5절 "Bean Validation `[필드명: 기본메시지]` 조립" | `sed ProblemDetailExceptionHandler.java:58-61` | 일치하되 위치가 바뀜: 핸들러는 `shared/.../platform/web/ProblemDetailExceptionHandler.java:58-61`(`handleValidation`이 `ProblemDetailMappings.validationMessage(ex)` 사용). P1 이후라 지침서 서술(공용 advice)과 맞음 |
| T7 | 5절 "`ExceptionMessageFormatTest` 범위 확대" | `grep Pattern ExceptionMessageFormatTest.java` | 스캔 대상은 `application`·`domain` 경로의 `new IllegalArgumentException(`/`IllegalStateException(`만(`:29-30`). Bean Validation 한국어 `message`는 `adapter/in/web/dto`라 이 가드에 안 걸림. "범위 확대"가 새 규칙 신설인지 기존 스캔 확장인지 지침서에 없음 |
| T4 | 마이그레이션 필요 시 "Flyway 추가" | `ls db/migration-trading \| sort -V \| tail` | 다음 번호는 trading `V21`(최신 `V20__us_market_sessions.sql`), root `V7`(최신 `V6__user_settings_balance_check_enabled_drop.sql`). 지침서는 번호를 적지 않음 |

### U7 가드

| 태스크 | 가드 | 결과 |
|---|---|---|
| T3 | `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`(`:99`)와 platform 위치 | `PageRequest`·`CursorPage`는 platform leaf에 두면 안 걸림(JDK·Spring 전용이면). 도메인 모델(`CycleHistoryPage` trading-core domain)을 platform 타입으로 바꾸면 application→platform 의존이 생기는데 허용 방향이라 안 걸림(`shared ← trading-core`) |
| T3 | `OpenApiSchemaNameCollisionTest` | 같은 단순명 `CycleHistoryPageResponse` 2벌 존재 → 합칠 때 스키마 이름 관련 가드와 만남(내용 미확인) |
| T9 | 가드 "컬렉션 반환 컨트롤러는 `Items`/`CursorPage`만" 신설 | `failOnEmptyShould=true`(testing.md)라 규칙 대상이 비면 실패 → T4 전환 전에 넣으면 기존 메서드 전부가 위반(예외 목록 필요). T3 시점엔 아직 반환 타입이 전환 전 |
| T10 | 같은 resolver 경로 재사용 | 컨트롤러가 trading-core 안(`TradingCycleController`)이라 모듈 경계 영향 없음. 신규 엔드포인트라 `InternalApiContractTest` 영향 없음(내부 API 아님) |
| T5 | `InternalApiContractTest` | 메타에 trading-core 값을 싣기 위해 내부 API/`shared.contract`를 늘리면 이 가드가 wire 타입 위치를 강제(`shared.contract`에만 선언) |
| T5·T6 | `flows.yml` | `code:` 참조는 클래스명 존재만 검증(constraints.md). 이 태스크들이 옮기는 클래스(`RuntimeConfigController`·`MetaController`)가 `flows.yml`에 있는지 미확인 |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| T10 | U3 | 5절 "`derived`는 서버가 채울 기본값·파생값(램프 실효값, 필요 인출 자산, 시드 미리보기 등)" | 필드 목록이 "등"으로 열려 있음. 어느 값을 포함할지 기준 없음(ui 05가 요구하는 목록 참조도 없음) | 없음(5절 완료 판정은 "대칭 테스트"만) |
| T5 | U3 | 2-3절 "런타임에 바뀌는 값이면 … 캐시 정책은 T5에서 정한다" | 캐시 정책 기본값 없음. 5절은 "캐시 정책은 Opus"로 모델만 지정 | 없음 |
| T4 | U4 | 4절 "봉투 변경은 UI와 동시에 나가야 한다. … 같은 경로에 바로 적용하고 ui를 같은 날 배포" | 부작용(배포 사이 깨짐)을 알고 있고 방법(같은 날)은 있다. 롤백 방법(api만 되돌릴 때 ui 구버전과 맞는지)은 없음 | 4절 첫 항목 |
| T4 | U5 | 5절 "묶음마다 … ui 동시 배포" | 승인 주체·시점 없음. `cross-repo.md`에 묶음별 순서를 적는다고만 함. "내부 API 묶음은 05 결론 뒤"(7절) | 4절·7절(순서만) |
| T4·T6·T10 | U5 | 5절 태스크 전반 "api 먼저, ui 뒤따름" | 운영 데이터를 바꾸는 태스크는 아님. 응답 계약 변경이라 U5의 "누가 승인" 대신 "ui와 같은 날"만 있음 | 4절 |
| T8 | U6 | 5절 "가계부 집계 엔드포인트(fable-ui 명세 수신 후)" | PROGRESS 확정: "10 T8 명세만 답 없음". 명세가 안 오면 멈춘다는 문장 없이 대기만 → 대체 경로(받은 명세 없이 j-ui 문서 2-1절을 그대로 쓸지) 없음. 5절 마지막 문단은 "A2·A3·A5 → T8(명세는 … 2-1절 입출력을 그대로 쓴다)"로 부분 대체 | 5절 대응표(부분) |
| T4 | U6 | 7절 "④는 05의 결론 뒤에 한다" | 05 통합 방식이 개정돼 단계 M 진행 중이면 ④ 존속 여부는 05 완료 후라는 뜻. 05 단계 M 전에는 ④를 하지 않는다는 방향만 있고 05가 정해지지 않으면 계속 대기 | 7절 |
| T1·T2 | U1 | — | 완료. 해당 없음 | — |

- "판단이 갈릴 때의 기본값" 절: 없음.
- "중단 조건" 절: 없음(4절 "위험"이 비슷하나 중단 문장이 아님).
- "하지 말 것" 절: 있음(6절).

## 지침서 11 DB 테이블

실행 상태: T1~T9 전부 미실행(`git log`에 해당 커밋 없음, `@SQLRestriction` 선언 32곳이 그대로). 사용자 결정 확정(PROGRESS): D1=(가), D2 권고(맨 뒤), D3 삭제, D4 승인(SQL을 보여준 뒤 실행).

### U2 코드·DB 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| 전반 | 1-1 "소프트 삭제 테이블 실제 16개, `@SQLRestriction` 14개" | `git grep -h "@SQLRestriction" -- '*.java' \| wc -l` | **어긋남**: 지금 선언 32줄(25파일). 14개와 다름. 단 선언이 한 엔티티 안 중복·테스트일 수 있어 미확정(분류 미실시) |
| T1 | 2-2 "`OrderPersistenceAdapter.java:42,48` PLANNED 저장은 중복 검사 없이 INSERT" | `sed -n 42,48p` | 일치(`saveAll :42`, `save :48`, 검사 없음). `existsBy...ExternalOrderId`는 `:114`로 일치 |
| T1 | 2-2 "`order_leg` 컬럼, `UNKNOWN` 기본값" | `grep order_leg migration-trading/V1__init.sql` | 일치(`:103`, `DEFAULT 'UNKNOWN' NOT NULL`, varchar(50)). `PlannedOrder.UNKNOWN_LEG` 존재 |
| T1 | 2-2 인덱스 조건 `status IN (PLANNED,SUBMITTING,PLACED,PARTIALLY_FILLED,FILLED)` | `OrderStatus.java` | 상태 이름 존재 확인(SUBMITTING `:11`, PARTIALLY_FILLED `:14`). 나머지 상수·FAILED·CANCELLED 전체 목록은 미대조 |
| T1 | 2-2 `strategy_cycle.end_date IS NULL` | `StrategyCycleEntity.java:45` | 일치(`end_date`, 진행 중 null) |
| T1 | 2-2 "`AccountBudgetLock`(JVM 락)" | `git grep AccountBudgetLock` | 일치(`application/service/AccountBudgetLock.java`, `ManualTradingService`에서 사용) |
| T1·T4 | 마이그레이션 번호(지침서에 번호 없음, `MIG-T`·`MIG-R`) | `ls db/migration-trading \| sort -V \| tail -3` | trading 다음 번호 **V21**(최신 `V20__us_market_sessions.sql`), root 다음 **V7**(최신 `V6__user_settings_balance_check_enabled_drop.sql`). 단 14·15가 같은 디렉토리에 마이그레이션을 추가하면 번호 경합 |
| T3 | 1-1 `StrategyStateWriter.java:41-43` 포지션·사이클·전략 순 삭제 | `sed -n 41,43p` | 일치 |
| T3 | `UserTradingDataEraseService.java:64-67` | `sed -n 64,67p` | 일치(포지션→사이클→전략→계좌) |
| T3 | `CycleSnapshotCreator.java:147`, `DcaConfigService.java:47`의 `softDeleteActiveByStrategyId` | `sed -n` | 일치(둘 다 해당 호출) |
| T3 | `StrategyDetailAssembler.java:41-65` | `sed -n 41,65p` | 일치(`findLatestByStrategyId` 등 조회 다건) |
| T3 | `StrategyVrVersionJpaRepository.java:14-22` native JOIN, `StrategyInfiniteJpaRepository.java:12-20` | `sed` | VR 쪽 일치(`:14` 주석 "nativeQuery는 @SQLRestriction 미적용"). Infinite 쪽 줄은 미확인 |
| T3 | `StrategyCycleJpaRepository.java:24-40` "최신 1건" | `sed -n 24,40p` | 줄 범위는 일치하나 `:24-27`에 `findTop1...Desc/Asc` 2건만 있고 40줄까지 내용 없음(파일이 40줄 안팎) — 사실상 일치 |
| T5 | 1-1 `AdminQueryService.java:153` `appErrorLogPort.softDelete(id)` | `sed -n 153p` | 일치 |
| T5 | "`resolved_at` 컬럼 없음" | `git grep resolved_at -- src/main` | 0건(신설 대상 맞음) |
| T6 | `AccountJpaRepository.java:23` `deletedAt`만 찍는다 | `sed -n 23p` | 일치(`UPDATE AccountEntity a SET a.deletedAt = :now`) |
| T8 | `finance_groups.owner_user_id` 쓰기 중단 | `git grep ownerUserId -- src/main` | 쓰는 곳 `FinanceGroupEntity.java:29-30,38`(엔티티 매핑)뿐으로 보임. 서비스·도메인 `g.ownerUserId()` 호출은 `:38` 한 곳만 grep됨 → 도메인 record에도 필드가 있음 |
| T8 | "`event_publication` 완료 행 삭제 설정" | `git grep completion-mode -- '*.yml'` | 현재 설정 0건. `spring.modulith`는 `application.yml:39`(root·trading 둘 다)·`application-prod.yml:13`에 블록 존재 → 프로퍼티 추가 위치는 있음. Modulith 버전별 프로퍼티명은 gradle 캐시 sources jar 대조 필요(미실시) |
| T8 | 운영 행 수·고아 행(54·173·3) | 운영 DB 조회 금지 | 미확인 |
| T7 | `user_notify_profile` 05 X6에서 소멸 | `git grep user_notify_profile -- '*.java' \| wc -l` | 24곳 참조(아직 존재) |

### U7

| 태스크 | 가드 | 결과 |
|---|---|---|
| T3·T7 | `HexagonalArchitectureTest` `persistence_jpa_repositories_must_be_package_private` 등 | JpaRepository 삭제·메서드 제거는 안 걸림. 테이블 접기로 Entity를 지우면 `ModulithArchitectureTest`·`HexagonalArchitectureTest`는 참조 클래스 존재 규칙이 없어 안 걸림(미실행) |
| T3 | `flows.yml` 삭제 흐름 | T9가 갱신 대상으로 명시. 어떤 `code:` 참조가 `softDelete*`를 가리키는지 미확인 |
| T4 | `.github/scripts/check-migrations.sh`(`V1__*`만 제외) | 새 마이그레이션의 expand/contract 검사에 걸림: root의 `DROP COLUMN` `resolved_at` 전환은 두 배포로 분리해야 통과(지침서 T5에 반영). **trading 스키마는 검사 대상 아님**(`check-migrations.sh:10` "trading-core는 단일 프로세스 소유라 대상 아님") — 지침서 5절 서두의 "trading도 expand/contract를 따른다(05 D3)"는 스크립트가 강제하지 않는 자율 규칙 |
| T1 | EPR·FQCN 가드 | 유일 인덱스 추가는 이벤트 이동 없음. 해당 없음 |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| T1 | U6 | 5절 중단 조건 "같은 슬롯에 살아 있는 주문 둘이 정상인 경로" | 걸린 뒤 대체 경로 없음(`questions.md`에 적고 멈춤만) | 5절 중단 조건(멈춤만) |
| T3 | U6 | 중단 조건 "삭제되지 않은 버전이 최대 version_no가 아닌 전략이 운영에 있다" + 확인 쿼리 서술 | 쿼리는 **문장 서술**이고 완성 SQL 아님. 운영 조회 후 대체 경로 없음 | 5절 중단 조건 |
| T7 | U6 | "T7 ③에서 불일치가 1건이라도" | 대체 경로 없음 | 5절 중단 조건 |
| T1 | U4 | 2-2 "위반 시 동작을 정한다: PLANNED 저장 실패 → 해당 단계 실패, `TradingErrorEvent` 알림" | 부작용 처리 있음. 단 진행 중 주문(이미 PLACED 행)과 새 인덱스 충돌·`deletePlannedByCycleAndDate` 뒤 `saveAll`의 한 트랜잭션 보장은 테스트 3번으로만 | 2-2·4절 |
| T1 | U5 | 7절 "주문 경로라 매매 시간대 밖에 배포" | 승인 주체·배포 직전 확인 SQL(위반 0건 재확인)은 5절 T1 ②에 서술만, SQL 본문 없음 | 5절 T1 ② |
| T3·T4 | U5 | 5절 "T3 배포 뒤 2거래일 관찰 후 T4" | 누가 관찰·승인하는지 없음 | 5절 |
| T6 | U5 | "이미 삭제된 5행은 승인된 운영 SQL로 정리" | PROGRESS D3 승인됨. 실행 세션이 SQL을 사용자에게 보여주는 절차는 6절("SQL을 사용자에게 보여주고 승인") | 6절 |
| T8 | U5 | D4 SQL 2건 | PROGRESS "승인. 실행 세션이 SQL을 보여준 뒤 돌린다". SQL 본문은 지침서에 없음 | 6절 |
| T7 | U4 | 2-3 "NULL 허용, DB CHECK 안 건다" | 접은 뒤 타입별 컬럼 검증은 도메인에서. 롤백 시 옛 테이블 drop 후 복구 경로는 "두 배포로 나눔"뿐 | 5절 T7 ④ |
| T3 | U3 | 4절 "삭제된 사이클·포지션을 일부러 읽는 코드가 나온다 → 예외로 이미 알려져 있다" | 어떤 코드가 그 예외인지 특정 없음(MAX version_no만 언급) | 5절 중단 조건 |

- "판단이 갈릴 때의 기본값" 절: 없음(8절 사용자 결정 표의 "고르지 않으면" 열이 일부 대신함).
- "중단 조건" 절: 있음(5절 끝 "중단 조건").
- "하지 말 것" 절: 있음(6절).

## 지침서 12 기능

실행 상태: PROGRESS 기준 D0 (가), D1 유지(삭제 태스크 안 함), D2 지침서 안에서 삭제로 번복(15로 이동), D3 유지·동결, D4·D5 추가 안 함. F1은 14 T1로 이관. 남은 태스크: F2(작은 삭제), F4(조사), F5 없음(D1 유지), F6 없음(D0 가), F7→15 T4, F8 없음. 아래는 F1(상태 추적)·F2·F4·A1 대상.

### U2 코드 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| F1 | 3-2 "`AlpacaCandleAdapter.java:64`가 페이지네이션 미구현" | `sed -n 55,70p` | 줄은 일치(`:63-64` `nextPageToken != null`이면 warn만). 클래스 경로는 지침서 `TC/stats/adapter/out/alpaca/`, 실제 `trading-core/.../stats/adapter/out/alpaca/AlpacaCandleAdapter.java` 일치. 14 T1이 어댑터 신설로 이 파일을 대체할지 14에서 확인 |
| F2 | 3-8 "`GET /api/admin/_ping` 삭제, 호출처 없음" | `git grep _ping` | 일치(`AdminPingController.java:21`). 테스트 `AdminPingControllerTest`가 3건 있고 testing.md가 이 테스트를 `hasRole` 인가 검증 본보기로 가리킴("`AdminPingControllerTest` 참고") → **삭제하면 testing.md의 참조가 끊김** |
| F2 | B8 낡은 주석 4곳 | `grep 후속 …` | `TradingReportNotifier.java:22`, `TradingUserNotificationPort.java:18`은 줄 일치(`후속` 문구 있음), `TradingRealtimeNotificationPort.java:8`은 `/`로 시작하는 주석 존재 확인(내용 미확인). `KbLandHousingBenchmarkScheduler.java:14` 주석 "일요일 07:00" vs `:24` cron `0 0 8 * * SAT` → **일치(어긋남 그대로 남아 있음)** |
| F2 | 3-8 "`trade-dates` 삭제, root 컨트롤러·`TradingQueryHttpAdapter`·내부 API" | `git grep trade-dates` | 대상 존재: `AdminTradeController.java:56`, `TradingQueryHttpAdapter.java:65`, 내부 `TradingInternalQueryController.java:71`, 테스트 3곳. ui 확인은 cross-repo U5로 완료라고 지침서가 적음(미재확인) |
| F2 | 3-8 "`is-open` 삭제(호출처 없음)" | `git grep is-open` | 대상 존재(`MarketCalendarInternalController.java:36`, 테스트 `:67`). 호출처는 root 쪽 main에 없음을 grep으로 확인(0건). 05 X와 묶는다고 서술됨 |
| F2 | 3-8 "`GET /api/accounts/{accountId}/daily-trades` 삭제, 유저 범위로 대체" | `git grep daily-trades` | **어긋남**: 컨트롤러에 `@GetMapping("/daily-trades")`가 `DashboardController.java:53`와 `StatisticsController.java:53` 두 곳. 어느 쪽이 계좌 범위인지 매핑 prefix를 읽지 않아 미확정. `StatisticsControllerTest.java:113`이 `/api/ac…` 경로를 호출 |
| F4 | 1절 B6 "`OrderSubmissionReconciler.java:55` 접수 불명 복구가 토스에만" | `sed -n 55p` | 일치(`account.broker() == Broker.TOSS && age < TOSS_RESEND_LIMIT`). 메리츠·바이낸스는 미재조사 |
| F4 | 스펙 파일 `/Users/phs/Downloads/meritz_openapi_spec_20261112.json` | `ls -la` | 존재(1.1MB). `/Users/phs/Downloads/toss` 디렉토리는 **비어 있음**(F4는 메리츠·바이낸스만이라 영향 없음, 바이낸스 부분은 15로 대상 없음) |
| B3 | 4절 `TradingPreviewService.java:78` 코인 미리보기 없음, `ManualTradingService.java:57` | `sed` | 줄 일치(`isCryptoExchange` 예외, `isDca` 예외). D4 "추가 안 함"이라 태스크 없음, 15 T4로 코인 분기가 사라짐 |
| 3-2 | `BacktestService.java:92-94` DCA 미지원 | `sed -n 92,94p` | 일치(DCA면 IAE "백테스트를 지원하지 않습니다") |
| 3-7 | `ManualTradeCorrectionService.java:79` 방식 거절 | `sed -n 79p` | 일치(종료된 사이클은 지원하지 않음) |
| 3-4 | 코인 지표 어댑터(외부 6) | `git grep CryptoFearGreed\|UpbitMarketDataPort -- '*.java'` main 27줄 | 존재. 15가 삭제 담당 |

### U7

| 태스크 | 가드 | 결과 |
|---|---|---|
| F2 `_ping` 삭제 | `AdminPingControllerTest`(인가 본보기), testing.md 인용 | 가드는 아니나 문서 인용 1건 걸림. `flows.yml` `code:`에는 `AdminPing` 없음(`grep` 0건) |
| F2 `is-open`·`trade-dates` 삭제 | `InternalApiContractTest`(wire 타입), `flows.yml` | 컨트롤러 삭제 시 해당 내부 DTO가 `shared.contract`에 있으면 미사용이 되는데 이를 막는 가드는 미확인. `flows.yml`에서 `MarketCalendarInternalController`·`TradingInternalQueryController` 참조는 grep 결과 없음(검색 키 `MarketCalendarInternal`만 해봄) |
| 주택 벤치마크(D1 유지라 삭제 없음) | `flows.yml:1497` | 참고: `KbLandHousingBenchmarkScheduler#runNow` 등이 `code:`에 박혀 있음. 삭제하게 되면 같이 고쳐야 함. 현재는 해당 없음 |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| F1 | U1 | 6절 "Alpaca 문서로 `next_page_token` 규약 확인" | 외부 확인 필요: Alpaca Market Data API 문서(Historical bars, `page_token`·`next_page_token`). 레포 `findings/21-market-data-providers.md` 대조는 14에서 | 없음 |
| F4 | U3 | 6절 "가능하면 … 설계를 `questions.md`가 아니라 04의 단계 C에 항목으로 추가 요청" | 불가능할 때(조회 API가 접수 확정을 못 줄 때)의 기본값 없음 | 없음 |
| F4 | U1 | 메리츠 스펙 | 레포 밖 파일 존재 확인. 접수 확정 가능성은 미조사 | 없음 |
| F2 | U5 | 6절 F2 "`trade-dates`는 fable-ui 확인 뒤" | 확인 주체·완료 기록은 `cross-repo.md` B절 U5(2026-11-13 확인 완료로 서술). 삭제는 ui 배포 선행 필요 여부 서술 없음 | 3-8 표 |
| A1 | U5 | 5절 "수집 방식은 kista-infra 소관, `cross-repo.md`에 올린다" | F3로 Fable 완료 서술. 실행 세션 몫 없음 | 6절 F3 |
| F5 | U6 | D1 유지로 태스크 없음 | 해당 없음 | — |

- "판단이 갈릴 때의 기본값" 절: 8절 사용자 결정 표의 "답이 없으면" 열이 대신함(별도 절 없음).
- "중단 조건" 절: 없음.
- "하지 말 것" 절: 있음(9절).

## 지침서 13 지정하지 않은 영역

실행 상태: U1~U9 전부 미실행(해당 커밋 없음). 사용자 결정(PROGRESS): D0=(가)라 admin 리팩토링은 미루지 않음, 12 D2 코인 지표는 삭제(15)로 번복. 지침서 서두의 2026-11-14 갱신 문장이 코인 부분 제외를 이미 적음.

### U2 코드 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| U1 | 5절 `AuthController.java:55-57` 로그인이 상태 무관하게 AT 발급 | `sed -n 55,57p` | 일치(`jwtIssuerService.issue(user.id(), user.role())`, 상태 인자 없음) |
| U1 | 5절 `AuthController.java:103-105` rawRt가 null이면 jti 차단 안 함 | `sed -n 103,105p` | 일치(`if (rawRt != null) tokenUseCase.logout(...)`) |
| U1 | 5절 `InternalTokenAuthFilter.java:43` 비교 | `sed -n 43p` | 일치(`!internalApiToken.equals(token)`). 경로는 `shared/.../platform/security/` |
| U1 | 5절 `RootSecurityPolicy.java:28-36`, `JwtIssuerService.java:55-65`, `TokenService.java:31`, `RefreshTokenCookieHelper.java:14`, `SecurityConfig.java:55-58` | `git ls-files` | 파일 5개 모두 존재(줄 번호는 미대조) |
| U1 | 5절 "`JwtAuthFilter.java:45-84` 블랙리스트·역할 변경 시각만 본다" | `grep PENDING shared/.../security` | 보안 패키지에 `PENDING` 0건(상태 판정 없음 확인). 줄 번호 미대조 |
| U1 | "`approvalRequired=false`로 꺼져 있다" | `git grep approvalRequired -- '*.yml'` | yml에는 키 없음. `flows.yml:404`에 `ApprovalPolicyPort#approvalRequiredForUpdate`. 값은 운영 DB 설정이라 **미확인**(조회 금지) |
| U2 | 9절 `UserSettingsPort.findOrDefaultByUserIds` 삭제 대상 | `git grep findOrDefaultByUserIds` (비테스트) | **대상 없음**: main에 0건(이미 삭제됐거나 이름 변경) |
| U2 | 9절 `FinanceCategory.SYSTEM_LOAN_ID` | `git grep SYSTEM_LOAN_ID` | **대상 없음**: main 0건(`AssetSnapshotResponse.java:10` 주석에 `FinanceCate…`만) |
| U2 | 9절 `FinanceAccount.withAccountNo` | `git grep withAccountNo` | 일치(`FinanceAccount.java:35`). 비테스트 호출처 0건 |
| U2 | 9절 `BrokerApiException.isRejected`(인스턴스), 테스트 48곳 | `git grep -c "isRejected()" -- '*Test.java'` | 인스턴스 메서드 일치(`:42`). **테스트 호출 57곳**(48 아님) |
| U2 | 9절 `MarginItem.java:9-10`, `AccountBalance.java:61`, `ReverseModePosition.java:42`, `TossPriceApi.java:163-178` | `git ls-files` | 앞 3 파일 존재, 줄 번호 미대조. `TossPriceApi`는 14 T5가 삭제 |
| U4 | 3절 "`TradingStatsService` 435줄, 테스트 1,415줄" | `wc -l` | main 435줄 일치(`class TradingStatsService :47`). `InvestmentIndexCalculator` 335줄 일치. 테스트 1,415줄 미확인 |
| U4 | 3절 `TradingStatsService.buildPoints :326-393`, `InvestmentIndexCalculator.buildDailyValuations :51-111`, `InvestmentPointsQueryService.java:55-83` | `git ls-files` | 파일 존재. 줄 번호 미대조 |
| U4 | 3절 `CryptoMarketIndicatorsOpenApiCustomizer`(53줄) | `git ls-files \| grep OpenApiCustomizer` | **대상 없음**: 현재 `ErrorCodeOpenApiCustomizer`만 있고 지침서가 든 클래스명 없음(코인 지표 View 정리 대상은 15 소관이라 영향 낮음) |
| U5 | 4절 "`TradingErrorEvent` 38곳, 16개 파일, `TradingErrorReporter` 경유 11곳, 직접 `new` 37곳" | `git grep -n "new TradingErrorEvent" -- trading-core/src/main \| wc -l` | `new TradingErrorEvent` 38곳, 전체 `TradingErrorEvent(` 39곳. 지침서 "37곳"과 1 어긋남(`TradingErrorReporter` 내부 호출 1을 포함하는지 미분해). 11곳(Reporter 경유) 미확인 |
| U5 | 4절 "`@TransactionalEventListener(fallbackExecution = true)` 18, AFTER_COMMIT 9" | `git grep -c fallbackExecution` 합 | fallbackExecution 합 23(18과 어긋남. 14·15 등 신규·삭제 영향 가능) |
| U5 | 4절 "`toPlainText`가 이미 FCM용 태그 제거" | `git grep toPlainText` | 일치(`TradingUserNotificationAdapter.java:167,171`) |
| U5 | 4절 `MarketClosedEvent`·`MarketCloseEvent`·`CycleCompletedEvent`·`CycleEndedEvent` | `git ls-files` | 4개 모두 `trading/application/event/`에 존재(EPR 클래스명 변경 → 배포 직전 EPR 정리 필요) |
| U5 | 4절 `FcmAdapter` 문구 리터럴, `FcmAdapter.java:70` 예외 객체 미전달 | `sed -n 70p` | 일치(`log.error("FCM 전송 오류: {}", e.getMessage())`) |
| U7 | 2절 `AuditAction` "지금 7종" enum | `git grep AuditAction -- src/main` | **대상 없음**: `AuditAction` 이름의 타입 0건(지침서가 "enum `AuditAction`(지금 7종)"으로 적었으나 문자열 리터럴 상태일 가능성, 2절 문제 2와 모순되지는 않음) |
| U7 | 2절 `ApprovalCallback`(신설), `TelegramBotService.java:69-95`, `TelegramUserNotificationAdapter.java:26-27` | `git grep ApprovalCallback`, `ls` | 신설 대상(0건) 맞음. `TelegramBotService`는 `admin/adapter/in/telegram/`, 어댑터 파일 존재 |
| U7 | 2절 `AdminSettingsRequest.java:26-165` | `git ls-files` | 파일 존재(줄 미대조) |
| U8 | 8절 `StrategyService.java:97,117,160` `IllegalStateException`(400) | `git grep -n IllegalStateException StrategyService.java` | `:81,97,103,117` 확인, `:160`은 grep 결과에 없음(4건만 표시했으므로 미확정). 지침서 줄 일부가 어긋남 가능 |
| U8 | 8절 `BrokerCallGuard.java:32-33` | `sed -n 32,33p` | 일치(`throw new IllegalStateException("증권사 API 조회에 실패했습니다…", e)`) |
| U8 | 8절 `OrderStateConflictException`·`PrivacyValuationException` HTTP 매핑 없음 | `git grep` `*ProblemDetails*` | 둘 다 `IllegalStateException` 하위 클래스로 존재. 어떤 `*ProblemDetails`에도 참조 0건 → 일치(미매핑, 현재 400 취급) |
| U8 | 8절 `TradingPriceFetcher.java:177` raw catch | `sed -n 177p` | 줄 `:177`이 `} catch (Exception e) {`와 `log.error("FCM…` 중 어느 쪽인지 위 출력이 `FcmAdapter`와 섞임 — `TradingPriceFetcher:177`은 `} catch (Exception e) {` 형태 확인(미세 대조 안 함) |
| U9 | 6절 `AlpacaProperties` 3벌, `dataBaseUrl`, `.env.example`의 `CMC_API_KEY`·`KIS_ADMIN_APP_*` | `git grep CMC_API_KEY -- .env.example` | `.env.example`에 둘 다 0건(추가 대상 맞음). 단 15가 CMC 항목을 없앰 → **09 T3·13 U9·15 충돌** |

### U7 가드

| 태스크 | 가드 | 결과 |
|---|---|---|
| U5 이벤트 이름 변경·합병 | EPR 고아 행, `EventPublicationRegistryTest`(root architecture) | 이벤트·리스너 FQCN 변경은 배포 직전 EPR 정리가 필요(`docker-infra.md` "배포 직전 EPR 정리 런북", `constraints.md`/CLAUDE.md의 "리스너 패키지 이동 커밋 배포 전 필수 체크"). 지침서 U5에 "EPR 미완료 행이 없을 때 배포"가 있음. `EventPublicationRegistryTest`의 내용은 미확인 |
| U5 리스너 선언 통일 | `HexagonalArchitectureTest`의 `@TransactionalEventListener` 위치 규칙(`constraints.md` "저장 트리거 리스너=adapter/in/event"), `no_aspects_in_codebase` | 선언 방식 변경만이라 위치 가드는 안 걸림. 단 `constraints.md` 문구 개정(U9) 필요 |
| U1 PENDING 차단 | `HexagonalArchitectureTest` 무관, `@WebMvcTest`의 `hasRole` 검증(`testing.md`) | 정책 변경 후 슬라이스 테스트가 `@Import({SecurityConfig, JwtAuthFilter})` 없이는 403 판정을 못 함. 기존 컨트롤러 테스트는 `authentication(UUID)` 주입이라 role 없이 통과 → 새 규칙 `hasAnyRole`로 바뀌면 role 없는 인증을 쓰는 `@WebMvcTest`가 대량 깨질 수 있음(실행 안 함) |
| U7 설정 한 벌 | `GradleModuleBoundaryTest`(root↔trading-core 의존 금지), `InternalApiContractTest` | 설정 타입을 `shared.kernel`에 두는 것은 허용. 단 05 단계 X 이후여야 함(지침서 선행 표기됨) |
| U4 `DailyCycleBook` 신설 | `HexagonalArchitectureTest` `matching_must_not_depend_on_other_modules` | 순수 도메인을 stats 도메인에 두면 안 걸림. matching 커널에 두면 규칙 대상 |
| U2 미사용 삭제 | `ArchitectureMapTest`의 `code:` 참조 존재 검증 | `flows.yml`이 삭제 대상 클래스(`withAccountNo` 등 메서드)를 `Class#method`로 참조하는지 미확인(위 grep은 `flows.yml`을 대상으로 하지 않음) |
| U5 `TradingErrorEvent` 분류 | `ExceptionMessageFormatTest` | 이벤트 메시지는 IAE/ISE 아님. 안 걸림 |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| U4 | U6 | 11절 중단 조건 "규칙을 하나로 맞추면 운영 계좌 곡선·지수 값이 달라진다 → 사용자 확인" | 사용자 확인으로 넘김(주체 명확). 확인 못 받았을 때 기본값 없음 | 11절 중단 조건 |
| U5 | U6 | 중단 조건 "대응하는 종류 이벤트가 없는 것이 나온다 → 새 이벤트를 만들지 묻는다" | 질문만 있고 기본값 없음 | 같음 |
| U7 | U6 | 중단 조건 "두 설정 저장소의 값이 서로 어긋나 있다" | 대체 경로 없음 | 같음 |
| U1 | U4 | 5절 "승인 전환 시 기존 '역할 변경 시각' 장치가 옛 토큰을 막는다" | 진행 중 토큰 부작용: PENDING 토큰의 `role` 클레임 제거와 `hasAnyRole` 전환 시점에 기존 로그인 사용자(ACTIVE)는 role이 이미 있어 영향 없다고 서술은 없음(암묵) | 5절 |
| U1 | U5 | 5절 ui 요청 ①~④ | 배포 순서(ui 승인 대기 경로 목록 선행)는 `cross-repo.md` E절 참조. 승인 주체 서술 없음(D0=(가)로 사용자 결정 완료) | 5절·13절 |
| U8 | U5 | 13절 "응답 상태 코드가 바뀔 때(kista-ui와 같은 날 배포)" | ui가 400에 기대는지 확인 방법·주체는 "cross-repo 확인"뿐 | 13절 |
| U7 | U4·U5 | 11절 U7 "`trading_runtime_settings`는 다음 배포에서 drop, 운영 설정 값이 전환 전후 같은지 SELECT 비교" | 비교 SELECT 본문 없음, 승인 주체 없음 | 11절 완료 판정 |
| U5 | U4 | 4절 "재발행 시 알림이 중복될 수 있다는 점은 받아들인다" | 수용 결정 있음 | 4절 |
| U1 | U1 | 5절 jti 차단·`MessageDigest.isEqual` 등 | 외부 문서 전제 없음. 해당 없음 | — |
| U3 | U3 | 3절 `DailyCycleBook` 규칙 "기본값은 `createdAt` 비교, 유효 사이클 규칙은 T1 대조 테스트로 정한다" | 기본값이 일부만 있고 "유효 사이클 규칙" 기본값은 테스트 결과에 의존(결과가 갈릴 때 어느 쪽을 택할지 없음) | 3절·11절 중단 조건 |

- "판단이 갈릴 때의 기본값" 절: 없음(3절 "기본값은 …"이 통계에 한해 있음).
- "중단 조건" 절: 있음(11절 표 아래 "중단 조건").
- "하지 말 것" 절: 있음(12절).

## 지침서 14 시세 공급자 전환

실행 상태: T1~T7 전부 미실행(`MarketDataPort` 파일 0, `git log`에 시세 커밋 없음). 사용자 결정은 PROGRESS "2차 결정 기록"으로 확정(D1 Alpaca, D2·D3 권고, D4·D5 개정). 선행: T3은 T2 통과, T7은 06 T2·T3.

### U2 코드 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| T1 | 1절 `TradingPriceFetcher`(199줄) | `wc -l` | 일치(199) |
| T1·T5 | 4-4 `TossPriceApi` 255, `TossCandleApi`+`TossCandle` 195, `TossAdminPriceSource` 59 | `wc -l` | `TossPriceApi` 255 일치, `TossCandleApi` 174 + `TossCandle` 21 = 195 일치, `TossAdminPriceSource` 59 일치 |
| T1 | 4-1 "stats `AlpacaCandleAdapter`(78줄)" | `wc -l` | 일치(78) |
| T3·T5 | 1절 "`BrokerPricePort` 메서드 8개" | `grep -c` 인터페이스 선언 | 일치(8). 파일은 26줄 |
| T5 | 1절 `BrokerRouter.java:81-122`, 147줄 | `wc -l`, `sed -n 81,82p` | 파일 존재 147줄. `:81`이 `getPrice(...)` 단건 메서드 주석(관리자 → 계좌 단건) 시작이라 서술과 맞음 |
| T3 | 1절 `MockBrokerAdapter.java:29` `TossPriceApi priceFeed` 직접 주입 | `sed -n 29p` | 일치 |
| T3 | 4-3 `MockBrokerAdapter.java:140-142` 현재가 단일값 봉 대체 | `sed -n 140,142p` | 일치(`fetchConfirmedCandle(...).orElseGet(...)` 에서 현재가 봉 생성) |
| T3 | 4-2 "`TossPriceApi.java:149-151` 실패도 캐싱" | `sed -n 149,151p` | 일치(주석 "실패(empty)도 캐싱되어 …") |
| T3 | 3-4 "`TossPriceApi.java:202-206` 확정 종가 없으면 현재가 폴백" | `sed -n 202,206p` | 일치(`getClosingPrice … orElseGet … 현재가로 폴백`) |
| T3 | 4-3 `TossHoldingsApi.java:64,91` 환율 | `sed -n 64p` | `:64`에 `executor.submit(this::fetchUsdToKrwRate)` 일치. `:91` 미대조 |
| T3 | 4-3 `AccountLedgerTotalsService.java:56` 요청마다 외부 호출 | `sed -n 56p` | 일치(`brokerPricePort.getPrices(tickers, account.toBrokerRef())`) |
| T3 | 3-3 `TradingService.java:346 → :359 → :418` | `sed -n 346p,418p` | `:346` `loadPriceContext` 호출(`screenPrivacyBase(priceFetcher.loadPriceContext(`), `:418` `reloadPlacementPrices` 호출 일치. `:359` 미대조 |
| T3 | 3-5 `BuyOrderPriceCapper:44-45,79` | `sed -n 44,45p` | **어긋남**: `:44-45`는 `private final StrategyCyclePort strategyCyclePort;` 등 필드 선언. 현재가 사용부는 다른 줄(번호 이동). 영향: 근거 인용 위치만, 결론은 영향 없음(`git grep` 미실시) |
| T3 | 3-5 `DcaCycleOrderStrategy.referencePriceForBuy` | `grep -n` | 일치(`:100` 호출, `:176` 정의, max(전일 종가, 현재가) 서술은 미대조) |
| T3 | 3-5 `CycleOrderComputer.java:101-102` VR 기준가 현재가 | `sed -n 101,102p` | 일치(`vrReferencePrice = currentPrice != null ? currentPrice : prevClosePrice`) |
| T3 | 3-5 `TradingService.java:269` 계좌 병렬 | `sed 269` | 미확인(미대조) |
| T4 | 4-3 `BenchmarkService.java:240` `"TOSS_INVEST"` | `sed -n 240p` | 일치(`new CurrentExchangeRate(midRate, …, "TOSS_INVEST")`). 연관: `CurrentExchangeRatePort` 주석·`BenchmarkComparisonResponse.java:84` `allowableValues`에도 TOSS_INVEST 문자열 |
| T4 | 4-3 "`FrankfurterFxRateAdapter` 설정 재사용" | `git ls-files` | `FrankfurterFxRateAdapter`·`FrankfurterConfig`·`HistoricalFxRatePort` 모두 존재(stats). 15가 `HistoricalFxRatePort`를 지운다고 서술, 14가 `FrankfurterConfig` 보존을 15에 적어 둠(15 절에서 대조) |
| T1 | 4-1 `broker/adapter/out/marketdata/`(`AdminMarketPriceAdapter` 있는 자리) | `git ls-files` | 일치(`AdminMarketPriceAdapter`·`AdminPriceSource` 이 디렉토리). `MarketDataPort` 파일은 없음(신설) |
| T1 | 1절 "`AlpacaProperties`는 trading-core에 이미 있는 것" | `git ls-files` | **3벌**: root benchmark, trading-core marketcalendar, trading-core stats. `trading-core` 안에 2벌이라 "이미 있는 것"이 어느 쪽인지 지침서가 지정하지 않음(Q-07 답이 Alpaca 3→1벌을 단계 X·R로 미룸) |
| T1 | 환경 키 `ALPACA_API_KEY` | `git grep alpaca -- '*.yml'` | root `application.yml:119`, trading-core `application.yml:111-115` 둘 다 `alpaca:` 블록 있음. trading-core는 `base-url: https://paper-api.alpaca.markets`(주석: marketcalendar·stats 공용), `data-base-url` 있음 |
| T5 | 4-4 설정 `toss.admin-client-id/secret` | `git grep TOSS_ADMIN` | 일치: `trading-core/.../application.yml:93-94`, `.env.example:52-53`, `deploy/server/README.md:85-86` |
| T5 | 4-4 `KisAdminPriceSource`·`KisAdminTokenCoordinator`는 04 단계 A가 지움 | `git ls-files` | 두 파일·`KisPriceApi` 현재 존재(KIS 삭제 미실행) |
| T5 | 4-4 `TossPriceApi:163-199` DST 복제 | 13에서 `TossPriceApi.java:163-178` | 줄 범위가 13(163-178)과 14(163-199)로 다르게 적힘(미대조) |
| T3 | 4-3 `waitUntilPostClose` 기준 | `git grep waitUntilPostClose` | **이름 대상 없음**: 그 이름의 메서드 0건. 대기는 `TradingService.java:151,174` `waitFor("마감…")` 형태. 지침서가 든 메서드명이 지금 코드에 없음 → 4-3의 "마감 리포트 +20분" 변경 위치 특정에 영향 |
| T3 | 4-3 `StrategyCreationService#fetchMarketPrice` 등(flows.yml:531이 `BrokerPricePort#getPrevClose` 참조) | `grep flows.yml` | `BrokerPricePort#getPrevClose`가 `flows.yml:531`에 박혀 있음 → 메서드 7개 삭제 시 `ArchitectureMapTest` `code:` 참조 검증이 깨질 수 있음 |
| T7 | `reloadPlacementPrices`, `selectPriceAccount`, `loadPriceContext` | `git grep` | 세 이름 모두 존재(`TradingPriceFetcher.java:54,90,116`, `TradingService.java` 다수) |

### U7

| 태스크 | 가드 | 걸림 |
|---|---|---|
| T5 `TossPriceApi` 삭제 | `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`(`:112-130`) | **예외 목록에 FQCN이 박혀 있어 같이 고쳐야 함**: `...broker.adapter.out.toss.TossPriceApi`(`:124`)와 `...meritz.MeritzPriceApi`(`:126`, 시세 메서드 대부분 삭제하지만 클래스는 남음). 클래스 삭제 후 allowlist에 없는 FQCN은 무해(조건 `doNotHaveFullyQualifiedName`)이지만 문서 주석(`:116`)이 TossPriceApi를 언급 |
| T1 신규 Alpaca 어댑터 | 같은 가드 | 어댑터가 거래일 계산에 `UsTradeDates`를 쓰면 **allowlist에 신규 FQCN 추가 필요**(안 하면 위반). 지침서 4-2는 "세션일"을 인자로 받아 쓰지 않을 수도 있어 미정 |
| T1·T5 | `HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker`(`:236`) | `TossCandle`·`TossExchangeRate` 같은 벤더 모델은 broker 밖 참조 금지. 신규 `MarketDataPort`가 broker 포트이고 Alpaca 모델을 `broker.domain.model`에 둔다면 `domain.model.alpaca` 패키지 규칙 유무 미확인(가드 문구에 alpaca 미포함: kis/toss/upbit/meritz/binance) |
| T1 | `TradingCoreTestVendorModelTest`·`GradleModuleBoundaryTest` | `:trading-core`가 `:shared`만 의존. `MarketDataPort`는 trading-core broker 포트라 root(benchmark `AlpacaIndexPriceAdapter`)와 공유하지 못함(루트는 trading-core를 import 금지) → T6 지수 `feed=sip` 변경은 root 코드 별도 수정. 지침서는 T6을 독립으로 둠(일치) |
| T5 | `ArchitectureMapTest`(`flows.yml` `code:` 참조) | `flows.yml`에 `TradingPriceFetcher`·`AdminMarketPriceAdapter` 참조 6곳(`:118,172,188,315,372,672`), `BrokerPricePort#getPrevClose`(`:531`). `AdminMarketPriceAdapter` 삭제와 `TradingPriceFetcher#fetchClosingPrices`(`:188`)·`reloadPlacementPrices`(`:172,372`) 삭제는 **flows.yml 동시 수정이 없으면 테스트 실패**(`code:` 존재 검증) |
| T3 | 06 판정표·`CLOSE_REPORT` 창 | 06이 아직 미실행이라 지침서가 "06 판정표 상수"를 가리키는 대상이 현재 없음(`waitUntilPostClose` 부재와 같은 문제) |
| T5 | `.github/tests/*.bats` | `TOSS_ADMIN_*` 키 문자열이 bats에 있는지 `git grep TOSS_ADMIN -- .github` 0건(위 결과에 `.github` 없음) → 안 걸림 |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| T1 | U1 | 4-2 "`feed=sip` 일봉은 `end`를 넣지 않는다 … 무료 키 `end`가 최근 15분이면 403"(2026-11-14 실호출) | 레포 근거: `findings/21-market-data-providers.md` 8·9절과 PROGRESS "③". 대조는 하지 않음(findings 21 본문 미읽음). 외부 확인 필요: Alpaca Market Data 문서(Historical bars, subscription restriction) | 9절 중단 조건("`delayed_sip`이 403·400") |
| T1 | U1 | 4-2 `feed=delayed_sip` snapshot 무료 키 허용 | 공식 문서에 없다고 지침서가 스스로 적음(6절). 외부 확인 필요: Alpaca 문서 | 6절 위험 표(한쪽 피드 실패 시 다른 쪽) |
| T1 | U1 | 3-5 "토스 시세를 계좌 토큰으로 부를 수 있는지 미확인" | 레포 근거 `docs/agents/toss-api.md` 대조 가능하나 스펙 디렉토리 `/Users/phs/Downloads/toss/`가 **비어 있음**이라 대조 불가 | 6절 위험 표("안 되면 토스 계좌는 Mock처럼 `latestPrices` 사용") |
| T1 | U6 | 8절 T1 "토스 `GET /api/v1/prices` 계좌 토큰 호출 결과를 `questions.md`에 적는다(읽기 전용)" | **실계좌 호출이라 실행 세션이 하면 안 되는 작업일 수 있음**(운영 토스 계좌 없음, `findings`상 운영 토스 전략 0). 호출할 계좌·토큰이 없으면 대체 경로는 6절 위험 표 | 6절 |
| T2 | U3 | 8절 T2 통과 기준 "현재가(관리자 토스 대 계좌 증권사) 차이 0.5% 이내, 확정 종가 TQQQ 0.02달러 이내" | 수치 기준은 있음. "메리츠 계좌 하나의 증권사 값"이 병행 비교 때 어느 계좌인지 지정 없음 | 8절 T2 |
| T2 | U5 | 8절 T2 "3거래일 관찰, 결과를 `questions.md`에 표로" | 승인 주체 불명(실행 세션이 통과를 판정하는지 사용자인지). 배포 시간대는 공통 규칙 "KST 07:00~16:00" | 8절 서두 |
| T3 | U4 | 6절 "TQQQ 확정 종가 0.01~0.02달러 차이: 전환일 전후로 원장을 다시 계산하지 않는다" | 진행 중 사이클 부작용(VR 롤오버 V′·Mock 체결 판정) 언급. 진행 중 VR 전략 영향 크기 미서술 | 6절 위험 표, D3 |
| T3 | U4 | 4-3 마감 리포트 +10분 → +20분 | 시각 변경의 다음 날 부작용(마감 직전 접수 겹침 등) 서술 없음. 06 T2와 같은 배포 금지(9절) | 9절 하지 말 것 |
| T3 | U5 | 8절 T3 "2거래일 관찰, 접수 주문의 가격·수량, 리포트 시각과 종가, 알림을 전환 전날과 대조" | 승인 주체·배포 직전 확인 SQL 없음. 주문 경로 변경이라 U5 대상 | 8절 T3 |
| T5 | U5 | 8절 사용자 작업 "T5 배포 뒤 토스 관리자 키를 콘솔에서 폐기, 로컬 `application-local.yml` 값 삭제" | 사용자 작업 명시. 시크릿 제거 순서(운영 secret에서 `TOSS_ADMIN_*` 제거는 kista-infra) `cross-repo.md`에 적는다고 서술 | 8절 T5 |
| T3 | U6 | 9절 중단 조건 4개(T2 통과 기준 어긋남, `delayed_sip` 403·400, 환율 쓰는 곳 발견, 확정 종가 null 시 원장 저장 불명확) | 각 조건에 이후 대체 경로 서술 없음. `grep -rn "ExchangeRate\|exchangeRate" trading-core/src/main`은 지침서가 직접 명령으로 줌 — 현재 결과: `ExchangeRateQuote`·`PresentBalanceResult.exchangeRate`·`TossExchangeRate`·`TradingStatsInternalController`에서 사용(**이미 "주문·원장 계산이 환율을 쓰는 곳" 후보가 존재**하므로 중단 조건이 첫 실행에서 바로 걸릴 수 있음. 실제로 원장 계산인지는 미확인) | 없음 |
| T2·T3 | U6 | 중단 조건 "`CyclePositionPersistor.saveCyclePosition` 확정 종가 null 처리" | 위치 명시됨. 불명확할 때의 기본값 없음 | 없음 |
| T4 | U1 | Frankfurter 최신 환율 | 외부 확인 필요: Frankfurter API 문서(`api.frankfurter.dev`, 최신 환율 엔드포인트). 레포 `FrankfurterFxRateAdapter`가 과거 환율용인지 최신용인지 미확인 | 없음 |
| T1~T7 | U5(운영 데이터) | — | 운영 데이터 변경 없음(캐시·어댑터만). DB 스키마 영향 없음 | — |

- "판단이 갈릴 때의 기본값" 절: 있음(9절 "기본값").
- "중단 조건" 절: 있음(9절 "중단 조건").
- "하지 말 것" 절: 있음(9절 "하지 말 것").

## 지침서 15 크립토 삭제

실행 상태: 전부 미실행(`git log`에 T0~T7 커밋 없음, `BinanceExchangeAdapter`·`CryptoDcaRunner` 등 존재). 사용자 작업: PROGRESS "ETH DCA 전략 종료(2026-11-14 통보)"만 확인됨, 바이낸스·업비트 계좌 삭제와 관리자 설정 변경(단계 P 나머지)은 통보 없음. 결정 C1~C5는 PROGRESS 확정.

### U2 코드 전제 대조

| 태스크 | 지침서 문장 | 확인 명령 | 결과 |
|---|---|---|---|
| T0 | 2-4 `StrategyTicker` "상수 모양은 `QLD(BigDecimal.ZERO, "…", Market.US_STOCK)`" | `grep -n "QLD\|BTC\|ETH" StrategyTicker.java` | 일치(`:20` QLD, `:21-22` BTC·ETH). 상수 설명은 대문자 발행사 명칭 문자열 |
| T0 | 2-4 `StrategyType.java:31` DCA 허용 `EnumSet`, `:18` 주석 | `sed -n 18p,31p` | 일치(`:18` 주석 "UPRO/QLD(주식)·BTC/ETH(코인)", `:31` `EnumSet.of(UPRO, QLD, BTC, ETH)`) |
| T0 | 2-4 `TradingPolicySettings.java:45` DCA 허용 종목 기본값 | `sed -n 45p` | 일치(`field(true, List.of(UPRO, QLD, BTC, ETH), …`). 경로는 `shared/.../kernel/` 일치 |
| T0 | 2-4 `MeritzExchangeRegistry` QLD 매핑 | `grep -n QLD` | 일치(`:20` `StrategyTicker.QLD, new Mapping("0066", "AX")`). `meritz-api.md:42`에 AMEX `0066`·`AX` 서술 있음 |
| T0 | 2-4 `KisExchangeRegistry` AMEX 매핑 | `git ls-files`, `grep QLD` | 파일 존재(`:22` `StrategyTicker.QLD`). KIS 삭제(04 A) 미실행이므로 지금은 추가 대상 |
| T0 | 2-4 테스트 `StrategyTypeCapabilityTest:69`, `TradingPolicySettingsTest:55,112`(`containsExactly`), `MeritzExchangeRegistryTest:20` | `sed -n` 각 줄 | 전부 일치(`containsExactly(UPRO, QLD, BTC, ETH)` 순서 고정, 순서를 바꿔 넣으면 실패) |
| T0 | 2-4 문서 `README.md:13`, `meritz-api.md:42` | `sed -n` | 일치 |
| T0 | 2-4 "저장된 정책 JSON은 backfill 대상이 아니다(`TradingPolicySettingsPersistenceAdapter:60-85`는 빠진 enum 키만 채운다)" | `sed -n 60,85p` | 일치(`backfillMissingEnumKeys`·`backfillSection(brokers/strategies)` 존재). 단 이 메서드의 모르는 키 처리(`IllegalStateException`)는 해당 줄 구간만 읽어 미확인 |
| T4 | 2-3 "`DcaRoundSkippedEvent`는 주식 DCA(`DcaExecutionRecorder.java:87`)가 발행" | `sed -n 87p` | 일치 |
| T4 | 2-2 "`OrderType.MARKET` 호출처가 코인 규칙과 주식 어댑터 3곳의 거부 분기뿐" | `git grep OrderType.MARKET -- broker/adapter/out/*` | **미확인**: 어댑터 디렉토리에서 `OrderType.MARKET` 리터럴 검색 결과가 없음(정적 import 등 다른 표기일 수 있음). 3곳 주장을 지금 코드에서 확인하지 못함. 영향: T4의 `OrderType.MARKET` 삭제 단계와 중단 조건 4번(주식 경로가 쓰는 곳) 판정 |
| T4 | 2-2 `ErrorCode.BROKER_IP_NOT_ALLOWED`, `BrokerIpNotAllowedException` | `git grep` | 일치(`ErrorCode.java:8`, `BrokerProblemDetails.java:35`). **`ExceptionResponseTableTest.java:73`도 이 예외를 행으로 가짐** → 삭제 시 이 표 테스트 같이 수정(지침서 6절 3번 목록에 이 테스트 없음) |
| T4 | 2-2 설정 `heartbeat.crypto-url`·`HEARTBEAT_CRYPTO_URL` | `git grep` | 일치(`trading-core/.../application.yml:109`, `.env.example:58`, `deploy/server/README.md:100`) |
| T4 | 2-2 Caddy 경로 정규식 `crypto-market-indicators` | `git grep -n crypto-market-indicators` | 일치(`deploy/server/caddy/kista-api.caddy:18`). 같은 경로가 `TradingSecurityPolicy.java:20`과 `web/package-info.java:5`(주석)에도 있음 — 지침서에 `TradingSecurityPolicy`는 없음. `CaddyRoutingTest`에는 `crypto` 리터럴 0건(대조 방식 미확인) |
| T4 | 2-1 엔드포인트 3개 | `git grep` | `CryptoMarketIndicatorController`(`GET /api/crypto-market-indicators`) 존재. 나머지 2개(`crypto-portfolio`·`crypto-prices`)는 `CryptoPortfolioController.java:38,54`로 존재 확인 |
| T4 | 2-2 `Currency.USDT`, `Broker`의 `UPBIT`·`BINANCE`·`BROKER_API` | `grep` | 일치(`Currency.java:13`, `Broker.java:14,16`) |
| T4 | 2-3 "코인 전용 자릿수 상수 `CRYPTO_SCALE` 2곳" | `git grep CRYPTO_SCALE` | 일치(`BootstrapPosition.java`, `ManualTradeCorrectionService`). 정확히 2파일인지는 출력이 줄 단위로 잘려 확인 못함(4줄 표시) |
| T4 | 2-2 "`CryptoDcaRunner`·`CryptoOrderFilledEvent`·`CryptoTradeNotifier`" | `git grep` | 존재(`CryptoDcaRunner.java:38`, `CryptoTradeNotifier.java`, `CryptoDcaRoundExecutor.java`). `CryptoOrderFilledEvent` 클래스 파일은 grep 상 `CryptoTradeNotifier`·어댑터 참조뿐 |
| T4·T6 | 4절/단계 B "`strategy_dca_rule.execution_hour`" | `git grep execution_hour -- '*.sql'` | 컬럼은 `V12__dca_multi_rule.sql:53`(`smallint`). V9는 `dca_amount_execution_hour`(원 추가). main 참조 21줄. 개발 시드 `scripts/dev-seed/dev-seed.sql:254,340`도 사용 |
| T4 | 4절 "`broker_tokens`" 삭제 포함 | `git grep broker_tokens` | 테이블 `trading.broker_tokens` 존재(V1). 자식 순서·FK는 지침서가 실행 세션에 위임 |
| T4 | 4절 "trading-core 다음 번호" | `ls migration-trading \| sort -V \| tail -1` | **다음 번호 V21**(최신 V20). 11 T1(V21 후보)·14 T4 등과 경합 가능 |
| T1 | 1절 "통째 삭제 main 73/4,015, test 34/5,194" | 미측정 | 미확인(파일 수를 이번에 다시 세지 않음) |
| T4 | `findings/20` 6번 "사용자 삭제 cascade가 코인 계좌에서도 같은 경로인지" | `git grep UserDeletedEvent` | `TradingUserCascadeListener`가 삭제 연쇄 진입점(미독). T1이 닫을 항목 그대로 미해결 |

### U7 가드

| 대상 | 가드 | 결과 |
|---|---|---|
| `Broker`·`Currency`·`StrategyTicker`·`Market` enum 상수 삭제 | `ArchitectureMapTest`의 enum 상수 집합 검증 | `flows.yml`의 `lifecycles.enum`은 `UserStatus`·`StrategyStatus`·`OrderStatus`·`TradingBatchPhase`뿐(`:1676,1696,1724,1752`) → 삭제 대상 enum은 **안 걸림** |
| 코인 클래스 삭제 | `ArchitectureMapTest` `code:` 존재 검증 | **걸림**: `flows.yml` code 참조 중 삭제 대상 포함: `:711`(`UpbitAuthApi#verifyCredentials`/`BinanceAuthApi` 등 연결 테스트 흐름), `:741`(`…UpbitAuthApi#verifyAccount, BinanceAuthApi…`), `:811`(`CryptoMarketIndicatorController`·`Service`), `:834`(`getPresentBalance` 흐름 안 코인 포트폴리오 부분), `:1629`(`CryptoDcaScheduler`·`CryptoDcaRunner`·`HeartbeatPort#pingCrypto`), `:1637`, `:1646`, `:1655`, `:1662`. 지침서 T5는 "흐름 2개 삭제와 문구 약 20곳"이라 적었으나 **흐름 711·741(연결 테스트 흐름의 `code:`)과 834는 흐름 자체는 유지·code 항목만 제거해야 한다는 서술이 없음** |
| landscape 참조 | `flows.yml:57-58` | `api-key-expiry`(API 키 만료 알림)가 `flow: crypto-dca`를 가리킨다(`:58`). `crypto-dca` 흐름을 지우면 이 항목의 `flow:` 참조가 끊김 → **지침서에 없는 같이 고칠 곳** |
| 코인 지표 엔드포인트 삭제 | `CaddyRoutingTest`(컨트롤러 경로와 Caddy 정규식 대조 추정) | `kista-api.caddy:18` 정규식에서 `crypto-market-indicators`를 같은 커밋에서 빼야 함(지침서 2-2에 있음). `TradingSecurityPolicy.java:20`도 그 경로를 permitAll로 참조 |
| `Market` enum 삭제 | `MarketVocabularyTest`(지침서 6절) | 기대값에서 코인 값만 뺀다고 서술 있음. finance 모듈 `Market`은 별개(서술 있음) |
| 코인 어댑터·타입 삭제 | `HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker`(`:236-243`), `TradingCoreTestVendorModelTest`(`:27-34`) | 패키지 목록(`domain.model.kis/toss/upbit/meritz/binance`)에 삭제될 upbit·binance 패키지가 들어 있음. 패키지가 사라져도 `resideInAnyPackage`는 오류 없이 통과하지만 규칙 문구가 낡음. `failOnEmptyShould`는 `that()`(broker 밖 클래스)가 비지 않아 안 걸림 |
| UsTradeDates 허용 목록 | `HexagonalArchitectureTest:112-130` | 삭제 대상 코인 클래스는 allowlist에 없음 → 안 걸림. KIS(`KisPriceApi`·`KisTradingApi`)는 04 소관 |
| `ErrorCode` 삭제 | constraints.md "ErrorCode 상수 삭제 금지"(UI 계약), `ExceptionResponseTableTest`(trading-core, `:73`), `ProblemDetailMappings` | **걸림(문서·테스트)**: C5 승인된 예외. `ExceptionResponseTableTest:73` 행 삭제 필요 |
| `BrokerSettings.allowedIp` 삭제 | 정책 JSON 역직렬화 | 모르는 키를 만나면 예외(지침서 3절). 4절 3번 SQL이 키를 지운 뒤여야 안전 — 서술 있음 |
| 하트비트 | `.github/tests/compose-invariants.bats`, `deploy/server/docker-compose.yml` | `HEARTBEAT_CRYPTO_URL`이 compose·bats에 있는지는 `git grep -- . ':!docs'` 결과(`.env.example`, `deploy/server/README.md`, `application.yml`)에 compose 없음 → 안 걸림 |
| EPR | 미완료 `event_publication` 중 코인 이벤트 | 지침서 3절 P ③이 확인 단계로 규정. `.github/tests/epr-orphans.bats` 존재(내용 미확인) |

### U1·U3·U4·U5·U6

| 태스크 | 유형 | 지침서 문장 | 판정 | 대체 경로 |
|---|---|---|---|---|
| T0 | U1·U3 | 2-4 "메리츠 계좌로 두 종목의 현재가를 1회씩 조회해 확인한 뒤 넣는다" | 실계좌 호출(읽기 전용)이며 메리츠 계좌·토큰이 필요. 실행 세션이 호출할 수 있는지 서술 없음. 외부 확인 필요: 메리츠 OpenAPI 스펙(`/Users/phs/Downloads/meritz_openapi_spec_20261112.json` 존재, 해외주식 시세 거래소 코드 BITO·EETH 표는 스펙에 없을 가능성) | 8절 기본값("실측값을 쓴다, 조회 실패면 그 종목을 넣지 않고 `questions.md`") — 단 `questions.md`는 Fable 부재 시 답할 주체가 없음 |
| T0 | U1 | 2-4 "설명 문자열은 발행사의 현재 공식 명칭을 확인해 적는다" | 웹 확인 필요: ProShares Bitcoin Strategy ETF(BITO), EETH(Grayscale/…) 발행사 명칭. 지침서에 명칭 없음 | 없음 |
| T0 | U5 | 7절 T0 "배포 → 사용자가 관리자 설정에서 허용", 관찰 "Mock 계좌로 두 종목 DCA 전략을 만들어 개장 배치 1회" | 승인 주체(사용자가 허용)·관찰은 있음. 배포 시각(매매 시간대 밖)과 정책 JSON 갱신이 화면 불가 시 `db/migration-trading` jsonb 수정(2-4)은 대체 경로로 있음 | 2-4 |
| T3 | U5 | 단계 P ③ 운영 확인 조회 | 조회 항목은 문장 서술(살아 있는 코인 계좌·전략 0, 코인 미체결 주문 0, 미완료 `event_publication` 중 코인 이벤트 0)이고 **SQL 본문이 없음** | 3절 P ③(서술) |
| T4 | U5 | 4절 "덤프와 삭제 SQL·예상 행 수를 사용자에게 보여 준 뒤 진행(11의 D4와 같은 절차)" | 승인 주체 있음(사용자). 단계 A 배포 시점(매매 시간대 밖)은 15에 직접 서술 없음(공통 규칙이 14·08에만 있음) | 3절·4절 |
| T4 | U5 | 5절 위험 "실거래 중인 전략을 코드가 먼저 끊는다 → 단계 P가 선행 조건, 마이그레이션 가드" | 있음. 롤백 서술은 없음: `docker-infra.md`의 "바이낸스 배포·롤백 주의: kista-trading 이전 이미지 롤백 불가"는 BINANCE 생성 시 얘기이고, 삭제 후 이전 이미지 롤백이 데이터 삭제 때문에 불가능해진다는 점이 15에 없음 | 없음 |
| T4 | U4 | 2-2 "투자금 추가·인출·수동 보정의 '코인 계좌는 리포트 창 검사를 건너뛴다' 예외도 사라진다" | 부작용(주식 계좌 동작 변화 없음) 서술은 6절 "주식 케이스 기대값 불변"으로 갈음 | 6절 |
| T4 | U4 | 2-4 ETH→EETH 이동 | 사용자가 ETH 종료(2026-11-14 통보) 후 EETH DCA로 옮길지 여부는 사용자 계획. T0 배포가 먼저라는 PROGRESS 메모 있음 | PROGRESS 메모 |
| T4 | U6 | 8절 중단 조건 4개(가드 걸리는 살아 있는 코인 계좌, 주식 DCA 기대값 변경 필요, 삭제 표시 행을 읽는 기능, `OrderType.MARKET`·`executionHour` 주식 경로 사용) | 멈춘 뒤의 대체 경로 없음. 특히 첫 조건은 사용자가 단계 P를 안 했을 때의 대기만 있고, 마지막 조건은 위 U2에서 `OrderType.MARKET` 호출처가 현재 확인되지 않아 첫 실행에서 바로 걸릴 수 있음 | 8절(멈춤만) |
| T6 | U6 | 7절 T6 "T4 배포 후 1거래일, kista-ui의 메타 필드 제거 확인" | 확인 방법(ui 쪽 배포 기록 위치) 서술 없음. `cross-repo.md` G절 참조 | 7절 |
| T4 | U3 | 4절 2번 "대상 테이블과 순서는 실행 세션이 FK를 읽고 정하되 최소한 …를 포함한다" | 위임되어 있음. 기본값 있음(최소 목록) | 4절 |
| T3·T4 | U5 | C2 완전 삭제와 11의 D1(루트에만 삭제 표시)의 예외 | 사용자 결정 C2 "권고대로"로 확정, 지침서 9절이 11 관계를 명시 | 0절·9절 |

크로스 지침서 충돌 사실: ① 09 T3 "`.env.example`에 `CMC_API_KEY=` 추가" 대 15 단계 D "`CMC_API_KEY` 제거"(현재 `.env.example`에 `CMC_API_KEY` 0건) ② 13 U9 `.env.example`에 `CMC_API_KEY`·`KIS_ADMIN_APP_*` 추가 대 15·04의 삭제 ③ 15 T6의 `execution_hour` 삭제와 11 T7의 `strategy_version`류 접기는 지침서 9절이 순서만 정함(둘 다 V21 이후 번호 경합) ④ 12의 F2 `trade-dates` 삭제(내부 API)는 15와 무관.

- "판단이 갈릴 때의 기본값" 절: 있음(8절 "기본값").
- "중단 조건" 절: 있음(8절 "중단 조건").
- "하지 말 것" 절: 있음(8절 "하지 말 것").
