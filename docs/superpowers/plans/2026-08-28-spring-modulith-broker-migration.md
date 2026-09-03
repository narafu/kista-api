# Spring Modulith broker 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kis/toss/mock 브로커 어댑터와 공통 포트·모델을 `com.kista.broker` 단일 Spring Modulith 모듈로 이전한다.

**Architecture:** finance/notify 모듈 이전(main 병합 commit `4e5158ef`)과 동일한 패턴 — (1) 파일을 `git mv`로 물리 이동하고 전체 코드베이스 참조를 컴파일이 통과할 때까지 정합화, (2) `@ApplicationModule` 선언 + `NamedInterface`로 공개 계약 확정, (3) 관련 문서 갱신. finance/notify는 3태스크였지만 이번엔 이동 대상이 94개 파일로 훨씬 많아 이동 작업 자체를 2태스크로 쪼갠다: **Task 1**(domain+application "브로커 코어" 이동 + 전체 크로스모듈 import 정합화 — 가장 무거운 작업)과 **Task 2**(adapter 레이어 물리 이동 — kis/toss/mock 구체 클래스는 포트/레지스트리를 통해서만 외부에서 참조되므로 Task 1에서 import가 이미 정리된 뒤엔 패키지 선언만 바꾸면 되는 기계적 작업). 이어서 **Task 3**(모듈 선언), **Task 4**(문서+최종 검증).

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit5/Mockito, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-28-spring-modulith-broker-migration-design.md` (원칙 SSOT는 `2026-08-27-spring-modulith-migration-design.md`)

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — finance/notify가 썼던 `worktree-modulith-finance-migration`은 이미 main에 병합·삭제됨. `superpowers:using-git-worktrees` 스킬로 브랜치 `worktree-modulith-broker-migration` 신규 생성 후 그 위에서 진행
- 포트는 `domain/port/out` 위치 그대로 유지 — `application/port`로 전환하지 않음 (constraints.md "도메인 포트 인터페이스와 타입 위치 규칙")
- 커밋 메시지: 한글, Conventional Commit 접두사(`refactor:`/`feat:`/`docs:`), author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고** (이 작업이 모듈 분리+리팩토링 겸용이라고 이미 명시됨) — 스펙에 이미 반영된 항목(`adapter/out/broker`→`internal` 개명)은 예외
- 전체 테스트 스위트(`./gradlew test`)는 Task 4 완료 후 최종 1회만 — Task 1~3 진행 중엔 `--tests`로 좁혀서 검증
- 파일 인코딩: 서브에이전트가 import 수정 시 BOM 삽입 주의(constraints.md "파일 인코딩 주의")
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용 — GNU sed(`sed -i`)와 다름

---

### Task 1: broker 코어(domain+application) 이전 + 전체 크로스모듈 import 정합화

**Files:**
- Move → `src/main/java/com/kista/broker/domain/port/out/`: `BrokerAccountPort.java, BrokerAdapterPort.java, BrokerConnectionTestPort.java, BrokerMarketCalendarPort.java, BrokerOrderCorrectionPort.java, BrokerPricePort.java, CandlePort.java, ExchangeRatePort.java, ExecutionPort.java, LiveBalancePort.java, MarginPort.java, PortfolioPort.java, SellableQuantityPort.java, StockInfoPort.java`(기존 `domain/port/out/broker/*` 14개) + `BrokerTokenCachePort.java`(기존 `domain/port/out/` 최상위 낙오 파일)
- Move → `src/main/java/com/kista/broker/domain/model/`: `Currency.java, DailyTransaction.java, DailyTransactionResult.java, DailyTransactionSummary.java, Execution.java, MarginItem.java, PresentBalanceResult.java`(기존 `domain/model/broker/*` 7개)
- Move → `src/main/java/com/kista/broker/domain/model/kis/`: `KisApiException.java`
- Move → `src/main/java/com/kista/broker/domain/model/toss/`: `TossAccountInfo.java, TossApiException.java, TossCandle.java, TossExchangeRate.java, TossMarketSession.java, TossStockInfo.java`
- Move → `src/main/java/com/kista/broker/application/service/`: `BrokerAdapterRegistry.java`(public 유지), `BrokerConnectionTesters.java`, `BrokerCallGuard.java`
- Move → `src/test/java/com/kista/broker/domain/model/`: `PresentBalanceResultTest.java`
- Move → `src/test/java/com/kista/broker/application/service/`: `BrokerCallGuardTest.java`
- Modify (import 경로 일괄 sed, 아래 Step 8): kis/toss/mock/broker-internal 어댑터 32개(아직 물리 이동 전, Task 2에서 이동) + `AdminReorderService, AdminTradeCorrectionService, AccountStatisticsService, TossStatisticsService, BrokerStatisticsRouter, AccountService, StrategyService, TradingPriceFetcher, ManualTradingService, TradingReporter, PreviewDepositCache, TradingOrderBudgetAllocator, StrategyOrderPlanBuilder, VrCycleRolloverService, OrderCancelService, TradingSellSufficiencySimulator, TradingOrderExecutor, VrReconfigureService, CycleRotationService, GlobalExceptionHandler` 등 legacy 서비스 전체 + 대응 테스트 파일 전체(정확한 목록은 Step 7의 grep이 실시간으로 찾아냄 — 위 스펙의 "old top-level → broker" 절이 대표 목록)

**Interfaces:**
- Produces: `com.kista.broker.domain.port.out.*`(15개), `com.kista.broker.domain.model.*`(7개)+`.kis.KisApiException`+`.toss.*`(6개), `com.kista.broker.application.service.{BrokerAdapterRegistry,BrokerConnectionTesters,BrokerCallGuard}` — Task 3에서 Named Interface로 공개, Task 2의 kis/toss/mock 어댑터가 이 FQN들을 참조

- [ ] **Step 1: domain/port/out 15개 이동**

```bash
mkdir -p src/main/java/com/kista/broker/domain/port/out
git mv src/main/java/com/kista/domain/port/out/broker/BrokerAccountPort.java src/main/java/com/kista/broker/domain/port/out/BrokerAccountPort.java
git mv src/main/java/com/kista/domain/port/out/broker/BrokerAdapterPort.java src/main/java/com/kista/broker/domain/port/out/BrokerAdapterPort.java
git mv src/main/java/com/kista/domain/port/out/broker/BrokerConnectionTestPort.java src/main/java/com/kista/broker/domain/port/out/BrokerConnectionTestPort.java
git mv src/main/java/com/kista/domain/port/out/broker/BrokerMarketCalendarPort.java src/main/java/com/kista/broker/domain/port/out/BrokerMarketCalendarPort.java
git mv src/main/java/com/kista/domain/port/out/broker/BrokerOrderCorrectionPort.java src/main/java/com/kista/broker/domain/port/out/BrokerOrderCorrectionPort.java
git mv src/main/java/com/kista/domain/port/out/broker/BrokerPricePort.java src/main/java/com/kista/broker/domain/port/out/BrokerPricePort.java
git mv src/main/java/com/kista/domain/port/out/broker/CandlePort.java src/main/java/com/kista/broker/domain/port/out/CandlePort.java
git mv src/main/java/com/kista/domain/port/out/broker/ExchangeRatePort.java src/main/java/com/kista/broker/domain/port/out/ExchangeRatePort.java
git mv src/main/java/com/kista/domain/port/out/broker/ExecutionPort.java src/main/java/com/kista/broker/domain/port/out/ExecutionPort.java
git mv src/main/java/com/kista/domain/port/out/broker/LiveBalancePort.java src/main/java/com/kista/broker/domain/port/out/LiveBalancePort.java
git mv src/main/java/com/kista/domain/port/out/broker/MarginPort.java src/main/java/com/kista/broker/domain/port/out/MarginPort.java
git mv src/main/java/com/kista/domain/port/out/broker/PortfolioPort.java src/main/java/com/kista/broker/domain/port/out/PortfolioPort.java
git mv src/main/java/com/kista/domain/port/out/broker/SellableQuantityPort.java src/main/java/com/kista/broker/domain/port/out/SellableQuantityPort.java
git mv src/main/java/com/kista/domain/port/out/broker/StockInfoPort.java src/main/java/com/kista/broker/domain/port/out/StockInfoPort.java
rmdir src/main/java/com/kista/domain/port/out/broker
git mv src/main/java/com/kista/domain/port/out/BrokerTokenCachePort.java src/main/java/com/kista/broker/domain/port/out/BrokerTokenCachePort.java
sed -i '' 's/^package com\.kista\.domain\.port\.out\.broker;/package com.kista.broker.domain.port.out;/' \
  src/main/java/com/kista/broker/domain/port/out/BrokerAccountPort.java \
  src/main/java/com/kista/broker/domain/port/out/BrokerAdapterPort.java \
  src/main/java/com/kista/broker/domain/port/out/BrokerConnectionTestPort.java \
  src/main/java/com/kista/broker/domain/port/out/BrokerMarketCalendarPort.java \
  src/main/java/com/kista/broker/domain/port/out/BrokerOrderCorrectionPort.java \
  src/main/java/com/kista/broker/domain/port/out/BrokerPricePort.java \
  src/main/java/com/kista/broker/domain/port/out/CandlePort.java \
  src/main/java/com/kista/broker/domain/port/out/ExchangeRatePort.java \
  src/main/java/com/kista/broker/domain/port/out/ExecutionPort.java \
  src/main/java/com/kista/broker/domain/port/out/LiveBalancePort.java \
  src/main/java/com/kista/broker/domain/port/out/MarginPort.java \
  src/main/java/com/kista/broker/domain/port/out/PortfolioPort.java \
  src/main/java/com/kista/broker/domain/port/out/SellableQuantityPort.java \
  src/main/java/com/kista/broker/domain/port/out/StockInfoPort.java
sed -i '' 's/^package com\.kista\.domain\.port\.out;/package com.kista.broker.domain.port.out;/' src/main/java/com/kista/broker/domain/port/out/BrokerTokenCachePort.java
```

- [ ] **Step 2: domain/model 공통 7개 + kis 1개 + toss 6개 이동**

```bash
mkdir -p src/main/java/com/kista/broker/domain/model
git mv src/main/java/com/kista/domain/model/broker/Currency.java src/main/java/com/kista/broker/domain/model/Currency.java
git mv src/main/java/com/kista/domain/model/broker/DailyTransaction.java src/main/java/com/kista/broker/domain/model/DailyTransaction.java
git mv src/main/java/com/kista/domain/model/broker/DailyTransactionResult.java src/main/java/com/kista/broker/domain/model/DailyTransactionResult.java
git mv src/main/java/com/kista/domain/model/broker/DailyTransactionSummary.java src/main/java/com/kista/broker/domain/model/DailyTransactionSummary.java
git mv src/main/java/com/kista/domain/model/broker/Execution.java src/main/java/com/kista/broker/domain/model/Execution.java
git mv src/main/java/com/kista/domain/model/broker/MarginItem.java src/main/java/com/kista/broker/domain/model/MarginItem.java
git mv src/main/java/com/kista/domain/model/broker/PresentBalanceResult.java src/main/java/com/kista/broker/domain/model/PresentBalanceResult.java
rmdir src/main/java/com/kista/domain/model/broker
sed -i '' 's/^package com\.kista\.domain\.model\.broker;/package com.kista.broker.domain.model;/' src/main/java/com/kista/broker/domain/model/*.java

mkdir -p src/main/java/com/kista/broker/domain/model/kis
git mv src/main/java/com/kista/domain/model/kis/KisApiException.java src/main/java/com/kista/broker/domain/model/kis/KisApiException.java
rmdir src/main/java/com/kista/domain/model/kis
sed -i '' 's/^package com\.kista\.domain\.model\.kis;/package com.kista.broker.domain.model.kis;/' src/main/java/com/kista/broker/domain/model/kis/KisApiException.java

mkdir -p src/main/java/com/kista/broker/domain/model/toss
git mv src/main/java/com/kista/domain/model/toss/TossAccountInfo.java src/main/java/com/kista/broker/domain/model/toss/TossAccountInfo.java
git mv src/main/java/com/kista/domain/model/toss/TossApiException.java src/main/java/com/kista/broker/domain/model/toss/TossApiException.java
git mv src/main/java/com/kista/domain/model/toss/TossCandle.java src/main/java/com/kista/broker/domain/model/toss/TossCandle.java
git mv src/main/java/com/kista/domain/model/toss/TossExchangeRate.java src/main/java/com/kista/broker/domain/model/toss/TossExchangeRate.java
git mv src/main/java/com/kista/domain/model/toss/TossMarketSession.java src/main/java/com/kista/broker/domain/model/toss/TossMarketSession.java
git mv src/main/java/com/kista/domain/model/toss/TossStockInfo.java src/main/java/com/kista/broker/domain/model/toss/TossStockInfo.java
rmdir src/main/java/com/kista/domain/model/toss
sed -i '' 's/^package com\.kista\.domain\.model\.toss;/package com.kista.broker.domain.model.toss;/' src/main/java/com/kista/broker/domain/model/toss/*.java
```

- [ ] **Step 3: application/service/broker 3개 이동**

```bash
mkdir -p src/main/java/com/kista/broker/application/service
git mv src/main/java/com/kista/application/service/broker/BrokerAdapterRegistry.java src/main/java/com/kista/broker/application/service/BrokerAdapterRegistry.java
git mv src/main/java/com/kista/application/service/broker/BrokerConnectionTesters.java src/main/java/com/kista/broker/application/service/BrokerConnectionTesters.java
git mv src/main/java/com/kista/application/service/broker/BrokerCallGuard.java src/main/java/com/kista/broker/application/service/BrokerCallGuard.java
rmdir src/main/java/com/kista/application/service/broker
sed -i '' 's/^package com\.kista\.application\.service\.broker;/package com.kista.broker.application.service;/' src/main/java/com/kista/broker/application/service/*.java
```

- [ ] **Step 4: 대응 테스트 2개 이동**

```bash
mkdir -p src/test/java/com/kista/broker/domain/model
git mv src/test/java/com/kista/domain/model/broker/PresentBalanceResultTest.java src/test/java/com/kista/broker/domain/model/PresentBalanceResultTest.java
rmdir src/test/java/com/kista/domain/model/broker
sed -i '' 's/^package com\.kista\.domain\.model\.broker;/package com.kista.broker.domain.model;/' src/test/java/com/kista/broker/domain/model/PresentBalanceResultTest.java

mkdir -p src/test/java/com/kista/broker/application/service
git mv src/test/java/com/kista/application/service/broker/BrokerCallGuardTest.java src/test/java/com/kista/broker/application/service/BrokerCallGuardTest.java
rmdir src/test/java/com/kista/application/service/broker
sed -i '' 's/^package com\.kista\.application\.service\.broker;/package com.kista.broker.application.service;/' src/test/java/com/kista/broker/application/service/BrokerCallGuardTest.java
```

- [ ] **Step 5: 이동 확인**

```bash
find src/main/java/com/kista/broker src/test/java/com/kista/broker -name "*.java" | wc -l
```
Expected: main 32개(port 15 + model 7 + model.kis 1 + model.toss 6 + application 3) + test 2개 = 34

- [ ] **Step 6: 전역 import 경로 일괄 치환 (와일드카드+명시적 import 모두 커버)**

broker의 4개 서브패키지(`domain.model.broker`, `domain.model.kis`, `domain.model.toss`, `domain.port.out.broker`)는 소속 클래스 전부가 이동해 옛 패키지가 완전히 비므로, 와일드카드 import(`.*;`)도 명시적 import도 캡처그룹 하나로 안전하게 일괄 치환 가능하다(다른 타입이 남아있는 `domain.port.out`이나 `application.service.broker`와 달리 충돌 위험 없음). `application.service.broker`도 동일하게 전체 이동이라 안전. `BrokerTokenCachePort`만 원래 `domain.port.out` 최상위(다른 포트 다수와 공존)에 있던 낙오 파일이라 명시적 import 1줄만 별도 치환한다.

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/^import com\.kista\.domain\.model\.broker\.\*;/import com.kista.broker.domain.model.*;/' \
  -e 's/^import com\.kista\.domain\.model\.broker\.\([A-Za-z]*\);/import com.kista.broker.domain.model.\1;/' \
  -e 's/^import com\.kista\.domain\.model\.kis\.\*;/import com.kista.broker.domain.model.kis.*;/' \
  -e 's/^import com\.kista\.domain\.model\.kis\.\([A-Za-z]*\);/import com.kista.broker.domain.model.kis.\1;/' \
  -e 's/^import com\.kista\.domain\.model\.toss\.\*;/import com.kista.broker.domain.model.toss.*;/' \
  -e 's/^import com\.kista\.domain\.model\.toss\.\([A-Za-z]*\);/import com.kista.broker.domain.model.toss.\1;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.broker\.\*;/import com.kista.broker.domain.port.out.*;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.broker\.\([A-Za-z]*\);/import com.kista.broker.domain.port.out.\1;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.BrokerTokenCachePort;/import com.kista.broker.domain.port.out.BrokerTokenCachePort;/' \
  -e 's/^import com\.kista\.application\.service\.broker\.\([A-Za-z]*\);/import com.kista.broker.application.service.\1;/'
```

- [ ] **Step 7: 치환 후 잔여 old-path import 재스캔 (놓친 패턴 확인)**

```bash
grep -rn "import com\.kista\.domain\.model\.broker\.\|import com\.kista\.domain\.model\.kis\.\|import com\.kista\.domain\.model\.toss\.\|import com\.kista\.domain\.port\.out\.broker\.\|import com\.kista\.application\.service\.broker\." src/main/java src/test/java
grep -rn "import com\.kista\.domain\.port\.out\.BrokerTokenCachePort;" src/main/java src/test/java
```
Expected: 둘 다 결과 없음. 남아있으면(예: 이 스펙 작성 이후 신규 추가된 파일, 혹은 클래스명에 숫자·언더스코어가 섞여 `[A-Za-z]*` 패턴을 벗어난 경우) 해당 파일을 열어 수동으로 import 경로를 고친다.

- [ ] **Step 8: compileJava 실행, 에러 있으면 반복 수정**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`. `cannot find symbol` 에러가 나오면 해당 파일의 import 문을 확인 — Step 6 sed가 못 잡은 패턴(예: static import, 정규식 밖 공백 변형)일 수 있다.

- [ ] **Step 9: compileTestJava 실행, 에러 있으면 반복 수정**

```bash
./gradlew compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 처리 방식은 Step 8과 동일.

- [ ] **Step 10: 이동/영향 범위 테스트 실행**

kis/toss/mock 어댑터는 아직 물리 이동 전(Task 2)이라 기존 패키지 경로(`com.kista.adapter.out.{kis,toss,mock,broker}`, `com.kista.adapter.out.persistence.kistoken`)로 실행한다.

```bash
./gradlew test \
  --tests 'com.kista.broker.*' \
  --tests 'com.kista.adapter.out.kis.*' \
  --tests 'com.kista.adapter.out.toss.*' \
  --tests 'com.kista.adapter.out.mock.*' \
  --tests 'com.kista.adapter.out.broker.*' \
  --tests 'com.kista.adapter.out.persistence.kistoken.*' \
  --tests 'com.kista.application.service.trading.*' \
  --tests 'com.kista.application.service.account.*' \
  --tests 'com.kista.application.service.strategy.*' \
  --tests 'com.kista.application.service.admin.*' \
  --tests 'com.kista.adapter.in.web.GlobalExceptionHandlerTest'
```
Expected: 전부 PASS. 실패 시 `docs/agents/commands.md`의 "테스트 실패 진단" 절차(XML 기반) 사용.

- [ ] **Step 11: 커밋**

```bash
git add -A
git status --short   # 의도한 파일만 포함됐는지 확인 (특히 rename 인식 여부)
git commit -m "$(cat <<'EOF'
refactor(broker): broker 코어(domain+application)를 com.kista.broker 모듈로 이전

domain/port/out(15개, BrokerTokenCachePort 낙오 파일 포함), domain/model/{broker,kis,toss}
(14개), application/service/broker(3개) 및 대응 테스트 2개를 com.kista.broker 하위
self-contained 패키지로 이동. kis/toss/mock 구체 어댑터 클래스는 이 포트들을 참조만 할 뿐
물리적으로는 아직 옛 패키지에 있음(Task 2에서 이동) — 이번 태스크는 그 파일들의 import
경로만 신규 broker 패키지로 갱신했다.

domain.model.{broker,kis,toss}·domain.port.out.broker·application.service.broker
4개 서브패키지는 소속 타입 전부가 이동해 옛 패키지가 완전히 비므로, 이를 참조하던
전체 코드베이스(trading/account/strategy/admin 서비스 전체 + GlobalExceptionHandler
등)의 import 경로를 프로젝트 전역 sed로 일괄 갱신 — 로직 변경 없음.

com.kista.broker는 아직 @ApplicationModule 미선언 상태(Task 3 예정)라
ModulithArchitectureTest의 verify()는 이 태스크 시점엔 실행하지 않는다(이미
com.kista.broker가 모듈 후보로 잡혀 대상 패키지가 비어있는 것과 무관하게, kis/toss/mock이
아직 물리 이동 전이라 검증 자체가 이 태스크의 목적이 아님).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 2: adapter 레이어(kis/toss/mock/internal/persistence) 물리 이전

**Files:**
- Move → `src/main/java/com/kista/broker/adapter/out/kis/`: `KisAuthApi.java, KisBrokerAdapter.java, KisConfig.java, KisExchangeRegistry.java, KisHttpClient.java, KisOrderApi.java, KisPriceApi.java, KisResponseParser.java, KisTokenCoordinator.java, KisTradingApi.java`(10개, 기존 `adapter/out/kis/*`)
- Move → `src/main/java/com/kista/broker/adapter/out/toss/`: `TossAuthApi.java, TossBrokerAdapter.java, TossCandleApi.java, TossConfig.java, TossDistributedTokenCoordinator.java, TossHoldingsApi.java, TossHttpClient.java, TossMarketApi.java, TossMarketCalendarCache.java, TossOrderApi.java, TossPriceApi.java, TossRedisTokenStore.java, TossResponseParser.java, TossResult.java, TossStockInfoCache.java, TossTokenStore.java, UsdKrwRateCache.java`(17개, 기존 `adapter/out/toss/*`)
- Move → `src/main/java/com/kista/broker/adapter/out/mock/`: `MockAuthApi.java, MockBrokerAdapter.java`(기존 `adapter/out/mock/*`)
- Move → `src/main/java/com/kista/broker/adapter/out/internal/`: `DoubleCheckedTokenCache.java, PrevCloseCache.java, TokenCoordinator.java`(기존 `adapter/out/broker/*` — **개명**, 모듈명과 겹치는 stutter 회피)
- Move → `src/main/java/com/kista/broker/adapter/out/persistence/`: `KisTokenEntity.java, KisTokenJpaRepository.java, KisTokenPersistenceAdapter.java`(기존 `adapter/out/persistence/kistoken/*`, flat)
- Move → `src/test/java/com/kista/broker/adapter/out/kis/`: `KisPriceApiTest.java, KisTokenCoordinatorTest.java, KisOrderApiTest.java, KisTradingApiTest.java, KisAuthApiTest.java, KisHttpClientTest.java, KisResponseParserTest.java, KisBrokerAdapterTest.java`(8개)
- Move → `src/test/java/com/kista/broker/adapter/out/toss/`: `TossPriceApiTest.java, TossResponseParserTest.java, TossOrderApiTest.java, UsdKrwRateCacheTest.java, TossDistributedTokenCoordinatorTest.java, TossRedisTokenStoreIT.java, TossHttpClientTest.java, TossAuthApiTest.java, TossBrokerAdapterTest.java, TossMarketApiTest.java, TossAuthApiNoJpaTest.java, TossMarketCalendarCacheTest.java, TossHoldingsApiTest.java`(13개)
- Move → `src/test/java/com/kista/broker/adapter/out/mock/`: `MockBrokerAdapterTest.java`
- Move → `src/test/java/com/kista/broker/adapter/out/internal/`: `PrevCloseCacheTest.java, DoubleCheckedTokenCacheTest.java`
- Move → `src/test/java/com/kista/broker/adapter/out/persistence/`: `KisTokenPersistenceAdapterTest.java`
- Modify (내부 상호참조 import, 아래 Step 6): kis/toss 어댑터 중 `adapter/out/broker.{TokenCoordinator,DoubleCheckedTokenCache,PrevCloseCache}`를 참조하던 파일들(정확히 `KisAuthApi, KisTokenCoordinator, TossAuthApi, TossDistributedTokenCoordinator, TossPriceApi`와 대응 테스트 `KisAuthApiTest, KisHttpClientTest, KisTokenCoordinatorTest, TossAuthApiTest, TossHttpClientTest, TossDistributedTokenCoordinatorTest`)

**Interfaces:**
- Consumes: Task 1에서 이전한 `com.kista.broker.domain.port.out.*`, `com.kista.broker.domain.model.*`
- Produces: `com.kista.broker.adapter.out.{kis,toss,mock,internal,persistence}` — Task 3에서 `internal`로 선언(모듈 밖 비공개)

- [ ] **Step 1: kis 어댑터 10개 + 테스트 8개 이동**

```bash
mkdir -p src/main/java/com/kista/broker/adapter/out/kis
git mv src/main/java/com/kista/adapter/out/kis/*.java src/main/java/com/kista/broker/adapter/out/kis/
rmdir src/main/java/com/kista/adapter/out/kis
sed -i '' 's/^package com\.kista\.adapter\.out\.kis;/package com.kista.broker.adapter.out.kis;/' src/main/java/com/kista/broker/adapter/out/kis/*.java

mkdir -p src/test/java/com/kista/broker/adapter/out/kis
git mv src/test/java/com/kista/adapter/out/kis/*.java src/test/java/com/kista/broker/adapter/out/kis/
rmdir src/test/java/com/kista/adapter/out/kis
sed -i '' 's/^package com\.kista\.adapter\.out\.kis;/package com.kista.broker.adapter.out.kis;/' src/test/java/com/kista/broker/adapter/out/kis/*.java
```

- [ ] **Step 2: toss 어댑터 17개 + 테스트 13개 이동**

```bash
mkdir -p src/main/java/com/kista/broker/adapter/out/toss
git mv src/main/java/com/kista/adapter/out/toss/*.java src/main/java/com/kista/broker/adapter/out/toss/
rmdir src/main/java/com/kista/adapter/out/toss
sed -i '' 's/^package com\.kista\.adapter\.out\.toss;/package com.kista.broker.adapter.out.toss;/' src/main/java/com/kista/broker/adapter/out/toss/*.java

mkdir -p src/test/java/com/kista/broker/adapter/out/toss
git mv src/test/java/com/kista/adapter/out/toss/*.java src/test/java/com/kista/broker/adapter/out/toss/
rmdir src/test/java/com/kista/adapter/out/toss
sed -i '' 's/^package com\.kista\.adapter\.out\.toss;/package com.kista.broker.adapter.out.toss;/' src/test/java/com/kista/broker/adapter/out/toss/*.java
```

- [ ] **Step 3: mock 어댑터 2개 + 테스트 1개 이동**

```bash
mkdir -p src/main/java/com/kista/broker/adapter/out/mock
git mv src/main/java/com/kista/adapter/out/mock/*.java src/main/java/com/kista/broker/adapter/out/mock/
rmdir src/main/java/com/kista/adapter/out/mock
sed -i '' 's/^package com\.kista\.adapter\.out\.mock;/package com.kista.broker.adapter.out.mock;/' src/main/java/com/kista/broker/adapter/out/mock/*.java

mkdir -p src/test/java/com/kista/broker/adapter/out/mock
git mv src/test/java/com/kista/adapter/out/mock/*.java src/test/java/com/kista/broker/adapter/out/mock/
rmdir src/test/java/com/kista/adapter/out/mock
sed -i '' 's/^package com\.kista\.adapter\.out\.mock;/package com.kista.broker.adapter.out.mock;/' src/test/java/com/kista/broker/adapter/out/mock/*.java
```

- [ ] **Step 4: broker 공용 헬퍼 3개 + 테스트 2개 이동 (internal로 개명)**

```bash
mkdir -p src/main/java/com/kista/broker/adapter/out/internal
git mv src/main/java/com/kista/adapter/out/broker/*.java src/main/java/com/kista/broker/adapter/out/internal/
rmdir src/main/java/com/kista/adapter/out/broker
sed -i '' 's/^package com\.kista\.adapter\.out\.broker;/package com.kista.broker.adapter.out.internal;/' src/main/java/com/kista/broker/adapter/out/internal/*.java

mkdir -p src/test/java/com/kista/broker/adapter/out/internal
git mv src/test/java/com/kista/adapter/out/broker/*.java src/test/java/com/kista/broker/adapter/out/internal/
rmdir src/test/java/com/kista/adapter/out/broker
sed -i '' 's/^package com\.kista\.adapter\.out\.broker;/package com.kista.broker.adapter.out.internal;/' src/test/java/com/kista/broker/adapter/out/internal/*.java
```

- [ ] **Step 5: KIS 토큰 persistence 3개 + 테스트 1개 이동 (flat)**

```bash
mkdir -p src/main/java/com/kista/broker/adapter/out/persistence
git mv src/main/java/com/kista/adapter/out/persistence/kistoken/*.java src/main/java/com/kista/broker/adapter/out/persistence/
rmdir src/main/java/com/kista/adapter/out/persistence/kistoken
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.kistoken;/package com.kista.broker.adapter.out.persistence;/' src/main/java/com/kista/broker/adapter/out/persistence/*.java

mkdir -p src/test/java/com/kista/broker/adapter/out/persistence
git mv src/test/java/com/kista/adapter/out/persistence/kistoken/*.java src/test/java/com/kista/broker/adapter/out/persistence/
rmdir src/test/java/com/kista/adapter/out/persistence/kistoken
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.kistoken;/package com.kista.broker.adapter.out.persistence;/' src/test/java/com/kista/broker/adapter/out/persistence/*.java
```

- [ ] **Step 6: kis/toss → internal 헬퍼 참조 import 갱신**

Task 1 시점엔 `adapter/out/broker`가 아직 옛 위치였어서 손대지 않은 부분이다. 이제 `internal`로 옮겼으니 이를 참조하던 kis/toss 파일의 import를 고친다.

```bash
find src/main/java/com/kista/broker/adapter/out/kis src/main/java/com/kista/broker/adapter/out/toss \
     src/test/java/com/kista/broker/adapter/out/kis src/test/java/com/kista/broker/adapter/out/toss \
     -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/^import com\.kista\.adapter\.out\.broker\.TokenCoordinator;/import com.kista.broker.adapter.out.internal.TokenCoordinator;/' \
  -e 's/^import com\.kista\.adapter\.out\.broker\.DoubleCheckedTokenCache;/import com.kista.broker.adapter.out.internal.DoubleCheckedTokenCache;/' \
  -e 's/^import com\.kista\.adapter\.out\.broker\.PrevCloseCache;/import com.kista.broker.adapter.out.internal.PrevCloseCache;/'
```

- [ ] **Step 7: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.adapter\.out\.\(kis\|toss\|mock\|broker\)\.\|import com\.kista\.adapter\.out\.persistence\.kistoken\." src/main/java src/test/java
```
Expected: 결과 없음. 남아있으면 해당 파일 열어 수동 교정(이 시점엔 kis/toss/mock/broker 구체 클래스를 외부에서 직접 참조하는 코드가 없다고 스펙 단계에서 확인했으므로 나오면 안 됨 — 나온다면 스펙 작성 이후 추가된 신규 참조일 가능성이 높다).

- [ ] **Step 8: 이동 확인**

```bash
find src/main/java/com/kista/broker/adapter src/test/java/com/kista/broker/adapter -name "*.java" | wc -l
```
Expected: main 35개(kis 10 + toss 17 + mock 2 + internal 3 + persistence 3) + test 25개(kis 8 + toss 13 + mock 1 + internal 2 + persistence 1) = 60

- [ ] **Step 9: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` (둘 다). 에러 시 Step 6/7 패턴 누락 확인.

- [ ] **Step 10: broker 전체 테스트 실행**

```bash
./gradlew test --tests 'com.kista.broker.*'
```
Expected: 전부 PASS.

- [ ] **Step 11: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(broker): kis/toss/mock 어댑터를 com.kista.broker 모듈로 물리 이전

adapter/out/{kis(10),toss(17),mock(2)}, adapter/out/broker(3개, internal로 개명 —
모듈명과 겹치는 broker.broker stutter 회피), adapter/out/persistence/kistoken(3개,
flat)와 대응 테스트 25개를 com.kista.broker.adapter.out 하위로 이동.

이 구체 클래스들은 포트/레지스트리(Task 1에서 이미 이전)를 통해서만 외부에서
참조되므로 이번 이동은 각 파일의 패키지 선언과 kis/toss 상호 참조하는 internal
헬퍼(TokenCoordinator/DoubleCheckedTokenCache/PrevCloseCache) import 경로만
갱신하면 되는 기계적 작업이었다 — 로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 3: `@ApplicationModule` 선언 + Named Interface 2개 공개

**Files:**
- Create: `src/main/java/com/kista/broker/package-info.java`
- Create: `src/main/java/com/kista/broker/domain/model/package-info.java`
- Create: `src/main/java/com/kista/broker/domain/model/kis/package-info.java`
- Create: `src/main/java/com/kista/broker/domain/model/toss/package-info.java`
- Create: `src/main/java/com/kista/broker/domain/port/out/package-info.java`
- Create: `src/main/java/com/kista/broker/application/service/package-info.java`

**Interfaces:**
- Consumes: Task 1/2에서 이전한 모든 broker 하위 패키지
- Produces: `com.kista.broker` 모듈 경계 선언 — 이후 `ModulithArchitectureTest.verifyModularStructure()`가 이 모듈을 포함해 순환 검증. `adapter` 서브패키지(kis/toss/mock/internal/persistence)는 Named Interface 미선언 → 모듈 밖에서 접근 불가(internal)

- [ ] **Step 1: 루트 package-info.java 작성**

`src/main/java/com/kista/broker/package-info.java`:
```java
// broker 애그리게이트(KIS/Toss/Mock 증권사 API 연동) 모듈 — domain·application만 공개 계약, adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.broker;
```

- [ ] **Step 2: domain Named Interface 4개 작성**

`src/main/java/com/kista/broker/domain/model/package-info.java`:
```java
// broker 모듈의 공개 계약 일부 — 공통 불변 값 객체(Currency/DailyTransaction* 등). domain.model.kis/toss·domain.port.out·application.service와 함께 "domain"/"application" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.broker.domain.model;
```

`src/main/java/com/kista/broker/domain/model/kis/package-info.java`:
```java
// broker 모듈의 공개 계약 일부 — KIS 전용 도메인 모델(KisApiException). "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.broker.domain.model.kis;
```

`src/main/java/com/kista/broker/domain/model/toss/package-info.java`:
```java
// broker 모듈의 공개 계약 일부 — Toss 전용 도메인 모델. "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.broker.domain.model.toss;
```

`src/main/java/com/kista/broker/domain/port/out/package-info.java`:
```java
// broker 모듈의 공개 계약 일부 — *Port 접미사 출력 포트 인터페이스(BrokerAdapterPort, LiveBalancePort 등). domain.model과 함께 "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.broker.domain.port.out;
```

- [ ] **Step 3: application Named Interface 작성**

`src/main/java/com/kista/broker/application/service/package-info.java`:
```java
// broker 모듈의 공개 계약 — BrokerAdapterRegistry(브로커별 포트 라우팅)/BrokerConnectionTesters/BrokerCallGuard. "application" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("application")
package com.kista.broker.application.service;
```

- [ ] **Step 4: ModulithArchitectureTest 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS. 실패하면 에러 메시지의 위반 모듈/타입을 확인 — 대부분 old top-level 코드가 broker의 non-exposed 타입(예: `adapter.out.kis.KisBrokerAdapter` 구체 클래스)을 포트가 아닌 직접 타입으로 참조하는 경우인데, 스펙 단계에서 이런 참조가 없음을 확인했으므로 나오면 안 된다. 나오면 해당 참조를 포트/레지스트리 참조로 교정.

- [ ] **Step 5: HexagonalArchitectureTest 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — 이미 `..domain.port.out..` 등 와일드카드 일반화가 돼있어 추가 수정 불필요. 실패 시에만 조사.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/broker/package-info.java \
        src/main/java/com/kista/broker/domain/model/package-info.java \
        src/main/java/com/kista/broker/domain/model/kis/package-info.java \
        src/main/java/com/kista/broker/domain/model/toss/package-info.java \
        src/main/java/com/kista/broker/domain/port/out/package-info.java \
        src/main/java/com/kista/broker/application/service/package-info.java
git commit -m "$(cat <<'EOF'
feat(modulith): broker 모듈 선언 — CLOSED + domain·application 2개 Named Interface 공개

broker 애그리게이트를 Spring Modulith ApplicationModule(CLOSED, 기본값)로 선언하고,
domain.model(+kis/toss)/domain.port.out을 "domain"으로, application.service를
"application"으로 NamedInterface 공개한다. adapter.out.{kis,toss,mock,internal,
persistence}는 미공개(internal) — KisHttpClient/TossRedisTokenStore 등 구현 세부가
이번에 처음 모듈 경계로 진짜 은닉된다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 4: 문서 갱신 + 최종 전체 검증

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `CLAUDE.md`
- Modify: `docs/agents/kis-api.md`, `docs/agents/toss-api.md`(패키지 경로 언급 있는 경우만)
- Modify: `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`(진행 상태 갱신)

**Interfaces:** 없음 (문서 전용)

- [ ] **Step 1: architecture.md broker 섹션 갱신**

`docs/agents/architecture.md`의 `domain/port/out/`, `domain/model/`, `application/service/`, `adapter/out/` 설명에서 `domain/port/out/broker`, `domain/model/{broker,kis,toss}`, `application/service/broker`, `adapter/out/{kis,toss,mock,broker,persistence/kistoken}`를 참조하는 기존 서술을 `com.kista.broker.domain.port.out`, `com.kista.broker.domain.model.*`, `com.kista.broker.application.service`, `com.kista.broker.adapter.out.{kis,toss,mock,internal,persistence}`로 갱신. finance/notify 이전 때 추가된 서술(commit `a0ee1a30`/`c2718026`) 바로 아래에 broker 단락 추가 — 구조는 동일한 톤(패키지 요약 + "이미 모듈로 이전됨" 명시). "BrokerAdapter Registry 패턴" 절의 FQN도 함께 갱신.

- [ ] **Step 2: constraints.md 갱신**

```bash
grep -n "domain/port/out/broker\|domain/model/kis\|domain/model/toss\|adapter/out/kis\|adapter/out/toss\|adapter/out/mock\|adapter/out/broker\|persistence/kistoken\|application/service/broker" docs/agents/constraints.md
```
결과가 있는 라인들을 새 경로로 갱신(특히 "KIS 계좌번호 DB 저장 방식", "Adapter 내부 중첩 타입 접근 제어자" 절의 `KisAuthApi`/`KisOrderApi` 언급).

- [ ] **Step 3: CLAUDE.md 갱신**

```bash
git show a0ee1a30 -- CLAUDE.md
```
위 diff를 참고해 동일 위치에 broker 이전 완료 사실 반영.

- [ ] **Step 4: kis-api.md / toss-api.md 패키지 경로 확인**

```bash
grep -n "com\.kista\.adapter\.out\.\(kis\|toss\)\|com\.kista\.domain\.\(model\|port\.out\)\.\(kis\|toss\|broker\)" docs/agents/kis-api.md docs/agents/toss-api.md
```
결과가 있으면 `com.kista.broker.adapter.out.*`/`com.kista.broker.domain.*`로 갱신, 없으면 스킵.

- [ ] **Step 5: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 "이전 전략 → 순서" 절을 `finance✅ → notify✅ → broker✅ → trading 코어`로 갱신.

- [ ] **Step 6: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 테스트 특정 후 수정.

- [ ] **Step 7: 커밋**

```bash
git add docs/agents/architecture.md docs/agents/constraints.md CLAUDE.md \
        docs/agents/kis-api.md docs/agents/toss-api.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git commit -m "$(cat <<'EOF'
docs(modulith): broker 모듈 이전 반영 — architecture/constraints/CLAUDE.md/스펙 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점(우아하지 않은 코드, 중복, 더 단순한 구현 가능성 등)을 발견하면:
1. **임의로 고치지 않는다** — 이 계획의 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고(무엇을, 어디서, 왜 개선 여지가 있는지)
3. 스펙 문서의 "리팩토링 관찰" 섹션에 기록(사용자 승인 시)

이미 이 계획에 반영된 항목(`adapter/out/broker`→`internal` 개명)은 예외 — 브레인스토밍 단계에서 이미 승인됨.
