# Spring Modulith trading 코어 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading 실행 엔진(주문/사이클 실행 이력/주문생성 전략 계열)을 `com.kista.trading` 단일 Spring Modulith 모듈로 이전한다.

**Architecture:** finance/notify/broker 이전과 동일한 3단계 패턴 — (1) 파일을 `git mv`로 물리 이동하고 전체 코드베이스 참조를 컴파일이 통과할 때까지 정합화, (2) `@ApplicationModule` 선언 + `NamedInterface`로 공개 계약 확정, (3) 관련 문서 갱신. 이번엔 `domain/model/strategy`·`domain/port/out` 두 패키지가 **부분 이전**(전체 클래스 중 일부만 이동, 나머지는 legacy 잔류)이라 broker 때처럼 패키지 전체를 통째로 옮기는 단순 리네임이 안 통한다 — 와일드카드 import는 "새 trading 와일드카드를 같은 줄에 추가"하는 방식으로, 명시적 import는 이동 대상 클래스명만 골라 치환하는 방식으로 분리 처리한다(이름 충돌 없음, 스펙에서 전수 확인 완료). 이동 규모가 커서(소스 97개) **Task 1**(domain 전체), **Task 2**(application 전체), **Task 3**(adapter 전체)로 쪼갠다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit5/Mockito, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-29-spring-modulith-trading-migration-design.md` (원칙 SSOT는 `2026-08-27-spring-modulith-migration-design.md`)

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — `superpowers:using-git-worktrees` 스킬로 브랜치 `worktree-modulith-trading-migration` 신규 생성 후 그 위에서 진행
- 포트는 `domain/port/out` 위치 그대로 유지 — `application/port`로 전환하지 않음
- 커밋 메시지: 한글, Conventional Commit 접두사(`refactor:`/`feat:`/`docs:`), author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고** — 스펙에 이미 반영된 항목(`PersistenceSupport` 복제)은 예외
- 전체 테스트 스위트(`./gradlew test`)는 Task 5 완료 후 최종 1회만 — Task 1~4 진행 중엔 `--tests`로 좁혀서 검증
- 파일 인코딩: 서브에이전트가 import 수정 시 BOM 삽입 주의
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용 — GNU sed(`sed -i`)와 다름
- **부분 이전 패키지 처리 원칙**(이 계획 전체에 적용): `domain.model.strategy`, `domain.port.out`은 일부 클래스만 이동한다. 와일드카드 import(`import com.kista.domain.model.strategy.*;` / `import com.kista.domain.port.out.*;`)는 원래 줄은 그대로 두고 같은 줄에 `import com.kista.trading.domain.model.*;` / `import com.kista.trading.domain.port.out.*;`를 이어 붙인다(자바는 한 줄에 여러 import 문 허용). 명시적 import는 이동 대상 클래스명만 새 경로로 치환하고, legacy 잔류 클래스명은 손대지 않는다. 반대로 `domain.model.order`, `domain.strategy`, `application.event` 중 이동 대상 5개는 **완전 이전**(패키지/이름이 통째로 비거나, 이동 대상 외 이름과 충돌 없음)이라 단순 접두사 치환으로 충분하다

---

### Task 1: domain(model+strategy+port.in+port.out) 이전 + 전체 코드베이스 domain-path import 정합화

**Files:**
- Move → `src/main/java/com/kista/trading/domain/model/` (flatten, 25개): `domain/model/order/*`(8개 전체) + `domain/model/strategy/*` 중 17개(`AccountBalance, BatchContext, BootstrapPosition, CycleHistoryPage, CyclePosition, CyclePositionHistoryEntry, CyclePositionInfiniteDetail, DstInfo, InfinitePosition, PriceSnapshot, ReconfigureVrCommand, ReverseModePosition, StrategyCycle, StrategyCycleVrDetail, TradingReport, TradingSnapshot, VrPosition`)
- Move → `src/main/java/com/kista/trading/domain/strategy/`: `domain/strategy/*` 14개 전체
- Move → `src/main/java/com/kista/trading/domain/port/in/`: `TradingExecutionUseCase.java, VrReconfigureUseCase.java`
- Move → `src/main/java/com/kista/trading/domain/port/out/`: `OrderPort.java, CyclePositionPort.java, CyclePositionInfiniteDetailPort.java, StrategyCyclePort.java, StrategyCycleVrPort.java`
- Move → `src/test/java/com/kista/trading/domain/model/`: `domain/model/{AccountBalanceTest,InfinitePositionTest,VrPositionTest}.java` + `domain/model/order/TradeEventSerializationTest.java` + `domain/model/strategy/{DstInfoTest,ReverseModePositionTest}.java`
- Move → `src/test/java/com/kista/trading/domain/strategy/`: `domain/strategy/{InfiniteStrategyTypeTest,ReverseInfiniteStrategyTest,VrStrategyTypeTest,PrivacyStrategyTest,CycleOrderStrategyCapabilityTest}.java`
- Modify (전역 import 정합화, Step 8): 프로젝트 전체 — 정확한 파일 목록은 Step 8의 grep이 실시간으로 찾아냄(legacy trading/account/strategy/admin 서비스, notify/broker 모듈, adapter/in/web dto 등 스펙의 "크로스모듈 의존" 절이 대표 목록)

**Interfaces:**
- Produces: `com.kista.trading.domain.model.*`(25개), `com.kista.trading.domain.strategy.*`(14개), `com.kista.trading.domain.port.in.{TradingExecutionUseCase,VrReconfigureUseCase}`, `com.kista.trading.domain.port.out.{OrderPort,CyclePositionPort,CyclePositionInfiniteDetailPort,StrategyCyclePort,StrategyCycleVrPort}` — Task 4에서 "domain" Named Interface로 공개. Task 2/3의 application/adapter 파일이 이 FQN들을 참조

- [ ] **Step 1: domain/model 25개 이동 (order 8개 전체 + strategy 17개 선별)**

```bash
mkdir -p src/main/java/com/kista/trading/domain/model

# order 전체 8개 — 디렉토리 통째로 이동 후 flatten
git mv src/main/java/com/kista/domain/model/order/*.java src/main/java/com/kista/trading/domain/model/
rmdir src/main/java/com/kista/domain/model/order

# strategy 중 실행 이력 17개만 선별 이동 (legacy config 8개는 그대로 둠)
git mv src/main/java/com/kista/domain/model/strategy/AccountBalance.java src/main/java/com/kista/trading/domain/model/AccountBalance.java
git mv src/main/java/com/kista/domain/model/strategy/BatchContext.java src/main/java/com/kista/trading/domain/model/BatchContext.java
git mv src/main/java/com/kista/domain/model/strategy/BootstrapPosition.java src/main/java/com/kista/trading/domain/model/BootstrapPosition.java
git mv src/main/java/com/kista/domain/model/strategy/CycleHistoryPage.java src/main/java/com/kista/trading/domain/model/CycleHistoryPage.java
git mv src/main/java/com/kista/domain/model/strategy/CyclePosition.java src/main/java/com/kista/trading/domain/model/CyclePosition.java
git mv src/main/java/com/kista/domain/model/strategy/CyclePositionHistoryEntry.java src/main/java/com/kista/trading/domain/model/CyclePositionHistoryEntry.java
git mv src/main/java/com/kista/domain/model/strategy/CyclePositionInfiniteDetail.java src/main/java/com/kista/trading/domain/model/CyclePositionInfiniteDetail.java
git mv src/main/java/com/kista/domain/model/strategy/DstInfo.java src/main/java/com/kista/trading/domain/model/DstInfo.java
git mv src/main/java/com/kista/domain/model/strategy/InfinitePosition.java src/main/java/com/kista/trading/domain/model/InfinitePosition.java
git mv src/main/java/com/kista/domain/model/strategy/PriceSnapshot.java src/main/java/com/kista/trading/domain/model/PriceSnapshot.java
git mv src/main/java/com/kista/domain/model/strategy/ReconfigureVrCommand.java src/main/java/com/kista/trading/domain/model/ReconfigureVrCommand.java
git mv src/main/java/com/kista/domain/model/strategy/ReverseModePosition.java src/main/java/com/kista/trading/domain/model/ReverseModePosition.java
git mv src/main/java/com/kista/domain/model/strategy/StrategyCycle.java src/main/java/com/kista/trading/domain/model/StrategyCycle.java
git mv src/main/java/com/kista/domain/model/strategy/StrategyCycleVrDetail.java src/main/java/com/kista/trading/domain/model/StrategyCycleVrDetail.java
git mv src/main/java/com/kista/domain/model/strategy/TradingReport.java src/main/java/com/kista/trading/domain/model/TradingReport.java
git mv src/main/java/com/kista/domain/model/strategy/TradingSnapshot.java src/main/java/com/kista/trading/domain/model/TradingSnapshot.java
git mv src/main/java/com/kista/domain/model/strategy/VrPosition.java src/main/java/com/kista/trading/domain/model/VrPosition.java

# 패키지 선언 일괄 정정 (order 계열 + strategy 계열 파일 모두 com.kista.trading.domain.model로)
sed -i '' \
  -e 's/^package com\.kista\.domain\.model\.order;/package com.kista.trading.domain.model;/' \
  -e 's/^package com\.kista\.domain\.model\.strategy;/package com.kista.trading.domain.model;/' \
  src/main/java/com/kista/trading/domain/model/*.java
```

- [ ] **Step 2: domain/strategy 14개 전체 이동**

```bash
mkdir -p src/main/java/com/kista/trading/domain/strategy
git mv src/main/java/com/kista/domain/strategy/*.java src/main/java/com/kista/trading/domain/strategy/
rmdir src/main/java/com/kista/domain/strategy
sed -i '' 's/^package com\.kista\.domain\.strategy;/package com.kista.trading.domain.strategy;/' src/main/java/com/kista/trading/domain/strategy/*.java
```

- [ ] **Step 3: domain/port/in 2개 + domain/port/out 5개 선별 이동**

```bash
mkdir -p src/main/java/com/kista/trading/domain/port/in
git mv src/main/java/com/kista/domain/port/in/TradingExecutionUseCase.java src/main/java/com/kista/trading/domain/port/in/TradingExecutionUseCase.java
git mv src/main/java/com/kista/domain/port/in/VrReconfigureUseCase.java src/main/java/com/kista/trading/domain/port/in/VrReconfigureUseCase.java
sed -i '' 's/^package com\.kista\.domain\.port\.in;/package com.kista.trading.domain.port.in;/' src/main/java/com/kista/trading/domain/port/in/*.java

mkdir -p src/main/java/com/kista/trading/domain/port/out
git mv src/main/java/com/kista/domain/port/out/OrderPort.java src/main/java/com/kista/trading/domain/port/out/OrderPort.java
git mv src/main/java/com/kista/domain/port/out/CyclePositionPort.java src/main/java/com/kista/trading/domain/port/out/CyclePositionPort.java
git mv src/main/java/com/kista/domain/port/out/CyclePositionInfiniteDetailPort.java src/main/java/com/kista/trading/domain/port/out/CyclePositionInfiniteDetailPort.java
git mv src/main/java/com/kista/domain/port/out/StrategyCyclePort.java src/main/java/com/kista/trading/domain/port/out/StrategyCyclePort.java
git mv src/main/java/com/kista/domain/port/out/StrategyCycleVrPort.java src/main/java/com/kista/trading/domain/port/out/StrategyCycleVrPort.java
sed -i '' 's/^package com\.kista\.domain\.port\.out;/package com.kista.trading.domain.port.out;/' src/main/java/com/kista/trading/domain/port/out/*.java
```

- [ ] **Step 4: 대응 테스트 8개 이동**

```bash
mkdir -p src/test/java/com/kista/trading/domain/model
git mv src/test/java/com/kista/domain/model/AccountBalanceTest.java src/test/java/com/kista/trading/domain/model/AccountBalanceTest.java
git mv src/test/java/com/kista/domain/model/InfinitePositionTest.java src/test/java/com/kista/trading/domain/model/InfinitePositionTest.java
git mv src/test/java/com/kista/domain/model/VrPositionTest.java src/test/java/com/kista/trading/domain/model/VrPositionTest.java
git mv src/test/java/com/kista/domain/model/order/TradeEventSerializationTest.java src/test/java/com/kista/trading/domain/model/TradeEventSerializationTest.java
rmdir src/test/java/com/kista/domain/model/order
git mv src/test/java/com/kista/domain/model/strategy/DstInfoTest.java src/test/java/com/kista/trading/domain/model/DstInfoTest.java
git mv src/test/java/com/kista/domain/model/strategy/ReverseModePositionTest.java src/test/java/com/kista/trading/domain/model/ReverseModePositionTest.java
sed -i '' \
  -e 's/^package com\.kista\.domain\.model;/package com.kista.trading.domain.model;/' \
  -e 's/^package com\.kista\.domain\.model\.order;/package com.kista.trading.domain.model;/' \
  -e 's/^package com\.kista\.domain\.model\.strategy;/package com.kista.trading.domain.model;/' \
  src/test/java/com/kista/trading/domain/model/*.java

mkdir -p src/test/java/com/kista/trading/domain/strategy
git mv src/test/java/com/kista/domain/strategy/InfiniteStrategyTypeTest.java src/test/java/com/kista/trading/domain/strategy/InfiniteStrategyTypeTest.java
git mv src/test/java/com/kista/domain/strategy/ReverseInfiniteStrategyTest.java src/test/java/com/kista/trading/domain/strategy/ReverseInfiniteStrategyTest.java
git mv src/test/java/com/kista/domain/strategy/VrStrategyTypeTest.java src/test/java/com/kista/trading/domain/strategy/VrStrategyTypeTest.java
git mv src/test/java/com/kista/domain/strategy/PrivacyStrategyTest.java src/test/java/com/kista/trading/domain/strategy/PrivacyStrategyTest.java
git mv src/test/java/com/kista/domain/strategy/CycleOrderStrategyCapabilityTest.java src/test/java/com/kista/trading/domain/strategy/CycleOrderStrategyCapabilityTest.java
sed -i '' 's/^package com\.kista\.domain\.strategy;/package com.kista.trading.domain.strategy;/' src/test/java/com/kista/trading/domain/strategy/*.java
```

- [ ] **Step 5: 이동 확인**

```bash
find src/main/java/com/kista/trading/domain src/test/java/com/kista/trading/domain -name "*.java" | wc -l
```
Expected: main 46개(model 25 + strategy 14 + port.in 2 + port.out 5) + test 13개(model 8 + strategy 5) = 59

- [ ] **Step 6: legacy domain/model/strategy에 8개(설정 계층) 남아있는지 확인**

```bash
ls src/main/java/com/kista/domain/model/strategy
```
Expected: `RegisterStrategyCommand.java, Strategy.java, StrategyDetail.java, StrategyInfiniteDetail.java, StrategySeedPreview.java, StrategyVersion.java, StrategyVrDetail.java, UpdateStrategyCommand.java` 8개만 남음. `domain/model/order`, `domain/strategy` 디렉토리는 완전히 비어 이미 삭제됐어야 함(Step 1/2에서 `rmdir`).

- [ ] **Step 7: 전역 import 경로 일괄 치환**

완전 이전 패키지(`domain.model.order`, `domain.strategy`)는 접두사 통째 치환. 부분 이전 패키지(`domain.model.strategy`, `domain.port.out`)는 와일드카드 줄에 trading 와일드카드를 이어 붙이고, 이동한 클래스명만 명시적으로 치환한다. `domain.port.in`은 이동한 2개 이름만 명시 치환(와일드카드 사용처 없음, 스펙에서 확인 완료).

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/com\.kista\.domain\.model\.order\./com.kista.trading.domain.model./g' \
  -e 's/com\.kista\.domain\.strategy\./com.kista.trading.domain.strategy./g' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.\*;$/import com.kista.domain.model.strategy.*; import com.kista.trading.domain.model.*;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.AccountBalance;$/import com.kista.trading.domain.model.AccountBalance;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.BatchContext;$/import com.kista.trading.domain.model.BatchContext;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.BootstrapPosition;$/import com.kista.trading.domain.model.BootstrapPosition;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.CycleHistoryPage;$/import com.kista.trading.domain.model.CycleHistoryPage;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.CyclePosition;$/import com.kista.trading.domain.model.CyclePosition;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.CyclePositionHistoryEntry;$/import com.kista.trading.domain.model.CyclePositionHistoryEntry;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.CyclePositionInfiniteDetail;$/import com.kista.trading.domain.model.CyclePositionInfiniteDetail;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.DstInfo;$/import com.kista.trading.domain.model.DstInfo;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.InfinitePosition;$/import com.kista.trading.domain.model.InfinitePosition;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.PriceSnapshot;$/import com.kista.trading.domain.model.PriceSnapshot;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.ReconfigureVrCommand;$/import com.kista.trading.domain.model.ReconfigureVrCommand;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.ReverseModePosition;$/import com.kista.trading.domain.model.ReverseModePosition;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.StrategyCycle;$/import com.kista.trading.domain.model.StrategyCycle;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.StrategyCycleVrDetail;$/import com.kista.trading.domain.model.StrategyCycleVrDetail;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.TradingReport;$/import com.kista.trading.domain.model.TradingReport;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.TradingSnapshot;$/import com.kista.trading.domain.model.TradingSnapshot;/' \
  -e 's/^import com\.kista\.domain\.model\.strategy\.VrPosition;$/import com.kista.trading.domain.model.VrPosition;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.\*;$/import com.kista.domain.port.out.*; import com.kista.trading.domain.port.out.*;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.OrderPort;$/import com.kista.trading.domain.port.out.OrderPort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.CyclePositionPort;$/import com.kista.trading.domain.port.out.CyclePositionPort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.CyclePositionInfiniteDetailPort;$/import com.kista.trading.domain.port.out.CyclePositionInfiniteDetailPort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.StrategyCyclePort;$/import com.kista.trading.domain.port.out.StrategyCyclePort;/' \
  -e 's/^import com\.kista\.domain\.port\.out\.StrategyCycleVrPort;$/import com.kista.trading.domain.port.out.StrategyCycleVrPort;/' \
  -e 's/^import com\.kista\.domain\.port\.in\.TradingExecutionUseCase;$/import com.kista.trading.domain.port.in.TradingExecutionUseCase;/' \
  -e 's/^import com\.kista\.domain\.port\.in\.VrReconfigureUseCase;$/import com.kista.trading.domain.port.in.VrReconfigureUseCase;/'
```

- [ ] **Step 8: 잔여 old-path 참조 재스캔**

```bash
grep -rn "com\.kista\.domain\.model\.order\.\|com\.kista\.domain\.strategy\." src/main/java src/test/java
grep -rn "import com\.kista\.domain\.model\.strategy\.\(AccountBalance\|BatchContext\|BootstrapPosition\|CycleHistoryPage\|CyclePosition\|CyclePositionHistoryEntry\|CyclePositionInfiniteDetail\|DstInfo\|InfinitePosition\|PriceSnapshot\|ReconfigureVrCommand\|ReverseModePosition\|StrategyCycle\|StrategyCycleVrDetail\|TradingReport\|TradingSnapshot\|VrPosition\);" src/main/java src/test/java
grep -rn "import com\.kista\.domain\.port\.out\.\(OrderPort\|CyclePositionPort\|CyclePositionInfiniteDetailPort\|StrategyCyclePort\|StrategyCycleVrPort\);" src/main/java src/test/java
grep -rn "import com\.kista\.domain\.port\.in\.\(TradingExecutionUseCase\|VrReconfigureUseCase\);" src/main/java src/test/java
```
Expected: 전부 결과 없음. 남아있으면(정규식이 못 잡은 변형 — static import, 줄바꿈 포함 import 등) 해당 파일을 열어 수동 교정.

- [ ] **Step 9: compileJava 실행, 에러 있으면 반복 수정**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`. `cannot find symbol`이 나오면 해당 파일에서 legacy 8개(`Strategy, StrategyVersion, StrategyInfiniteDetail, StrategyVrDetail, RegisterStrategyCommand, UpdateStrategyCommand, StrategySeedPreview, StrategyDetail`) 중 하나를 trading 클래스로 착각해 지웠는지, 또는 Step 7 sed가 놓친 변형(줄바꿈 낀 import 등)인지 확인.

- [ ] **Step 10: compileTestJava 실행, 에러 있으면 반복 수정**

```bash
./gradlew compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: 영향 범위 테스트 실행**

application/adapter 파일들은 아직 물리 이동 전(Task 2/3)이라 기존 경로로 실행한다.

```bash
./gradlew test \
  --tests 'com.kista.trading.domain.*' \
  --tests 'com.kista.application.service.trading.*' \
  --tests 'com.kista.application.service.strategy.*' \
  --tests 'com.kista.application.service.admin.*' \
  --tests 'com.kista.application.service.account.*' \
  --tests 'com.kista.application.service.stats.*' \
  --tests 'com.kista.application.service.backtest.*' \
  --tests 'com.kista.domain.backtest.*' \
  --tests 'com.kista.adapter.in.web.GlobalExceptionHandlerTest' \
  --tests 'com.kista.notify.adapter.out.gateway.*' \
  --tests 'com.kista.notify.adapter.in.telegram.*' \
  --tests 'com.kista.broker.adapter.out.*'
```
Expected: 전부 PASS. 실패 시 `docs/agents/commands.md`의 "테스트 실패 진단" 절차(XML 기반) 사용.

- [ ] **Step 12: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(trading): domain(model+strategy+port)을 com.kista.trading 모듈로 이전

domain/model/order(8개 전체) + domain/model/strategy 실행 이력(17개, 설정 계층
8개는 legacy 잔류) + domain/strategy(CycleOrderStrategy 계열 14개 전체) +
domain/port/{in(2),out(5)}을 com.kista.trading.domain 하위로 이동, 대응 테스트
13개 포함.

domain.model.strategy·domain.port.out은 부분 이전이라 와일드카드 import는 신규
trading 와일드카드를 같은 줄에 추가, 명시적 import는 이동한 클래스명만 치환 —
legacy 설정 계층(Strategy/StrategyVersion 등) 참조는 그대로 유지. 나머지
전체이전 패키지(domain.model.order, domain.strategy)는 접두사 일괄 치환.
로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 2: application(service+event) 이전 + application.event import 정합화

**Files:**
- Move → `src/main/java/com/kista/trading/application/service/`: `application/service/trading/*.java` 27개 전체
- Move → `src/main/java/com/kista/trading/application/event/`: `CycleCompletedEvent.java, CycleEndedEvent.java, NewCycleStartedEvent.java, OrderCancelFailedEvent.java, TradingReportReadyEvent.java`
- Move → `src/test/java/com/kista/trading/application/service/`: `application/service/trading/*.java` 테스트 23개 전체
- Modify (전역 import 정합화, Step 4): notify 모듈(`CycleEndedNotifier, CycleLifecycleNotifier, OrderCancelFailureNotifier, TradingReportNotifier`), `application/service/admin/AdminTradeCorrectionService`, `adapter/out/sse/SseEmitterRegistry` 등 — 정확한 목록은 Step 4의 grep이 실시간으로 찾아냄

**Interfaces:**
- Consumes: Task 1에서 이전한 `com.kista.trading.domain.*`
- Produces: `com.kista.trading.application.service.*`(외부 소비자 없음 — Named Interface 불필요), `com.kista.trading.application.event.*`(5개) — Task 4에서 "event" Named Interface로 공개, notify 모듈이 이 FQN들을 구독

- [ ] **Step 1: application/service 27개 + 테스트 23개 이동 (디렉토리 통째로 flatten)**

```bash
mkdir -p src/main/java/com/kista/trading/application/service
git mv src/main/java/com/kista/application/service/trading/*.java src/main/java/com/kista/trading/application/service/
rmdir src/main/java/com/kista/application/service/trading
sed -i '' 's/^package com\.kista\.application\.service\.trading;/package com.kista.trading.application.service;/' src/main/java/com/kista/trading/application/service/*.java

mkdir -p src/test/java/com/kista/trading/application/service
git mv src/test/java/com/kista/application/service/trading/*.java src/test/java/com/kista/trading/application/service/
rmdir src/test/java/com/kista/application/service/trading
sed -i '' 's/^package com\.kista\.application\.service\.trading;/package com.kista.trading.application.service;/' src/test/java/com/kista/trading/application/service/*.java
```

- [ ] **Step 2: application/event 5개 선별 이동**

```bash
mkdir -p src/main/java/com/kista/trading/application/event
git mv src/main/java/com/kista/application/event/CycleCompletedEvent.java src/main/java/com/kista/trading/application/event/CycleCompletedEvent.java
git mv src/main/java/com/kista/application/event/CycleEndedEvent.java src/main/java/com/kista/trading/application/event/CycleEndedEvent.java
git mv src/main/java/com/kista/application/event/NewCycleStartedEvent.java src/main/java/com/kista/trading/application/event/NewCycleStartedEvent.java
git mv src/main/java/com/kista/application/event/OrderCancelFailedEvent.java src/main/java/com/kista/trading/application/event/OrderCancelFailedEvent.java
git mv src/main/java/com/kista/application/event/TradingReportReadyEvent.java src/main/java/com/kista/trading/application/event/TradingReportReadyEvent.java
sed -i '' 's/^package com\.kista\.application\.event;/package com.kista.trading.application.event;/' src/main/java/com/kista/trading/application/event/*.java
```

- [ ] **Step 3: 이동 확인**

```bash
find src/main/java/com/kista/trading/application src/test/java/com/kista/trading/application -name "*.java" | wc -l
```
Expected: main 32개(service 27 + event 5) + test 23개 = 55

- [ ] **Step 4: 전역 import 경로 일괄 치환 (application.event 5개, 완전 이전이므로 접두사 치환 안전)**

`application.event`엔 와일드카드 import 사용처가 없음(스펙에서 확인 완료) — 이동한 5개 클래스명만 명시 치환하면 됨. `UserApprovedEvent` 등 나머지 legacy 이벤트는 여전히 `com.kista.application.event`에 남으므로 접두사 전체 치환은 금지, 이름 단위로만 치환한다.

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/^import com\.kista\.application\.event\.CycleCompletedEvent;$/import com.kista.trading.application.event.CycleCompletedEvent;/' \
  -e 's/^import com\.kista\.application\.event\.CycleEndedEvent;$/import com.kista.trading.application.event.CycleEndedEvent;/' \
  -e 's/^import com\.kista\.application\.event\.NewCycleStartedEvent;$/import com.kista.trading.application.event.NewCycleStartedEvent;/' \
  -e 's/^import com\.kista\.application\.event\.OrderCancelFailedEvent;$/import com.kista.trading.application.event.OrderCancelFailedEvent;/' \
  -e 's/^import com\.kista\.application\.event\.TradingReportReadyEvent;$/import com.kista.trading.application.event.TradingReportReadyEvent;/'
```

- [ ] **Step 5: 잔여 old-path 참조 재스캔**

```bash
grep -rn "import com\.kista\.application\.event\.\(CycleCompletedEvent\|CycleEndedEvent\|NewCycleStartedEvent\|OrderCancelFailedEvent\|TradingReportReadyEvent\);" src/main/java src/test/java
```
Expected: 결과 없음.

- [ ] **Step 6: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다. 에러 시 Step 4/5 패턴 누락 확인 — 특히 `application.service.trading` 27개 파일 내부에서 서로를 참조하던 코드는 같은 새 패키지(`com.kista.trading.application.service`)로 함께 이동했으므로 import 없이도 컴파일돼야 정상이다(같은 패키지 클래스 간 import는 원래 없었을 것).

- [ ] **Step 7: trading + notify 전체 테스트 실행**

```bash
./gradlew test --tests 'com.kista.trading.*' --tests 'com.kista.notify.*' --tests 'com.kista.application.service.admin.*'
```
Expected: 전부 PASS.

- [ ] **Step 8: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(trading): application(service+event)을 com.kista.trading 모듈로 이전

application/service/trading 27개 전체(외부 소비자 없음 확인됨 — application
Named Interface 불필요) + application/event 중 CycleCompletedEvent/
CycleEndedEvent/NewCycleStartedEvent/OrderCancelFailedEvent/
TradingReportReadyEvent 5개(notify 모듈이 리스너로 구독 중)를
com.kista.trading.application 하위로 이동, 대응 테스트 23개 포함.

application.event는 부분 이전(UserApprovedEvent 등 나머지 legacy 이벤트는
잔류)이라 이동한 5개 클래스명만 명시 치환. 로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 3: adapter(schedule+web+persistence) 이전 + PersistenceSupport 복제

**Files:**
- Move → `src/main/java/com/kista/trading/adapter/in/schedule/`: `TradingOpenScheduler.java, TradingCloseScheduler.java, BatchContextFactory.java`
- Move → `src/main/java/com/kista/trading/adapter/in/web/`: `OrderCancelController.java`
- Move → `src/main/java/com/kista/trading/adapter/out/persistence/`: `OrderEntity.java, OrderJpaRepository.java, OrderPersistenceAdapter.java`(기존 `adapter/out/persistence/trade/*`) + `CyclePositionEntity.java, CyclePositionJpaRepository.java, CyclePositionPersistenceAdapter.java, CyclePositionInfiniteEntity.java, CyclePositionInfiniteJpaRepository.java, CyclePositionInfiniteDetailPersistenceAdapter.java, StrategyCycleEntity.java, StrategyCycleJpaRepository.java, StrategyCyclePersistenceAdapter.java, StrategyCycleVrEntity.java, StrategyCycleVrJpaRepository.java, StrategyCycleVrPersistenceAdapter.java`(기존 `adapter/out/persistence/strategy/*` 중 12개)
- Create: `src/main/java/com/kista/trading/adapter/out/persistence/PersistenceSupport.java`(legacy `adapter/out/persistence/strategy/PersistenceSupport.java` 내용 복제, package-private)
- Move → `src/test/java/com/kista/trading/adapter/in/schedule/`: `TradingOpenSchedulerTest.java, TradingCloseSchedulerTest.java, BatchContextFactoryTest.java`
- Move → `src/test/java/com/kista/trading/adapter/in/web/`: `OrderCancelControllerTest.java`
- Move → `src/test/java/com/kista/trading/adapter/out/persistence/`: `OrderPersistenceAdapterTest.java, OrderPersistenceAdapterDbTest.java`(기존 `adapter/out/persistence/trade/*`) + `CyclePositionPersistenceAdapterTest.java, StrategyCyclePersistenceAdapterTest.java, StrategyCycleVrPersistenceAdapterTest.java`(기존 `adapter/out/persistence/strategy/*` 중 3개)

**Interfaces:**
- Consumes: Task 1의 `com.kista.trading.domain.*`, Task 2의 `com.kista.trading.application.*`
- Produces: `com.kista.trading.adapter.{in,out}.*` — Task 4에서 Named Interface 미선언(모듈 밖 비공개), 외부에서 이미 참조하는 곳 없음(스펙에서 확인 완료)

- [ ] **Step 1: schedule 3개 + 테스트 3개 선별 이동**

```bash
mkdir -p src/main/java/com/kista/trading/adapter/in/schedule
git mv src/main/java/com/kista/adapter/in/schedule/TradingOpenScheduler.java src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java
git mv src/main/java/com/kista/adapter/in/schedule/TradingCloseScheduler.java src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java
git mv src/main/java/com/kista/adapter/in/schedule/BatchContextFactory.java src/main/java/com/kista/trading/adapter/in/schedule/BatchContextFactory.java
sed -i '' 's/^package com\.kista\.adapter\.in\.schedule;/package com.kista.trading.adapter.in.schedule;/' src/main/java/com/kista/trading/adapter/in/schedule/*.java

mkdir -p src/test/java/com/kista/trading/adapter/in/schedule
git mv src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java src/test/java/com/kista/trading/adapter/in/schedule/TradingOpenSchedulerTest.java
git mv src/test/java/com/kista/adapter/in/schedule/TradingCloseSchedulerTest.java src/test/java/com/kista/trading/adapter/in/schedule/TradingCloseSchedulerTest.java
git mv src/test/java/com/kista/adapter/in/schedule/BatchContextFactoryTest.java src/test/java/com/kista/trading/adapter/in/schedule/BatchContextFactoryTest.java
sed -i '' 's/^package com\.kista\.adapter\.in\.schedule;/package com.kista.trading.adapter.in.schedule;/' src/test/java/com/kista/trading/adapter/in/schedule/*.java
```

- [ ] **Step 2: web 1개 + 테스트 1개 이동**

```bash
mkdir -p src/main/java/com/kista/trading/adapter/in/web
git mv src/main/java/com/kista/adapter/in/web/OrderCancelController.java src/main/java/com/kista/trading/adapter/in/web/OrderCancelController.java
sed -i '' 's/^package com\.kista\.adapter\.in\.web;/package com.kista.trading.adapter.in.web;/' src/main/java/com/kista/trading/adapter/in/web/OrderCancelController.java

mkdir -p src/test/java/com/kista/trading/adapter/in/web
git mv src/test/java/com/kista/adapter/in/web/OrderCancelControllerTest.java src/test/java/com/kista/trading/adapter/in/web/OrderCancelControllerTest.java
sed -i '' 's/^package com\.kista\.adapter\.in\.web;/package com.kista.trading.adapter.in.web;/' src/test/java/com/kista/trading/adapter/in/web/OrderCancelControllerTest.java
```

- [ ] **Step 3: persistence/trade 3개 전체 이동 (디렉토리 통째로 flatten)**

```bash
mkdir -p src/main/java/com/kista/trading/adapter/out/persistence
git mv src/main/java/com/kista/adapter/out/persistence/trade/*.java src/main/java/com/kista/trading/adapter/out/persistence/
rmdir src/main/java/com/kista/adapter/out/persistence/trade
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.trade;/package com.kista.trading.adapter.out.persistence;/' src/main/java/com/kista/trading/adapter/out/persistence/OrderEntity.java src/main/java/com/kista/trading/adapter/out/persistence/OrderJpaRepository.java src/main/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapter.java

mkdir -p src/test/java/com/kista/trading/adapter/out/persistence
git mv src/test/java/com/kista/adapter/out/persistence/trade/*.java src/test/java/com/kista/trading/adapter/out/persistence/
rmdir src/test/java/com/kista/adapter/out/persistence/trade
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.trade;/package com.kista.trading.adapter.out.persistence;/' src/test/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapterTest.java src/test/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapterDbTest.java
```

- [ ] **Step 4: persistence/strategy 중 실행 이력 12개 + 테스트 3개 선별 이동**

```bash
git mv src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionEntity.java src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionJpaRepository.java src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapter.java src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionInfiniteEntity.java src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionInfiniteEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionInfiniteJpaRepository.java src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionInfiniteJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/CyclePositionInfiniteDetailPersistenceAdapter.java src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionInfiniteDetailPersistenceAdapter.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleEntity.java src/main/java/com/kista/trading/adapter/out/persistence/StrategyCycleEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleJpaRepository.java src/main/java/com/kista/trading/adapter/out/persistence/StrategyCycleJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCyclePersistenceAdapter.java src/main/java/com/kista/trading/adapter/out/persistence/StrategyCyclePersistenceAdapter.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleVrEntity.java src/main/java/com/kista/trading/adapter/out/persistence/StrategyCycleVrEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleVrJpaRepository.java src/main/java/com/kista/trading/adapter/out/persistence/StrategyCycleVrJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyCycleVrPersistenceAdapter.java src/main/java/com/kista/trading/adapter/out/persistence/StrategyCycleVrPersistenceAdapter.java
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.strategy;/package com.kista.trading.adapter.out.persistence;/' \
  src/main/java/com/kista/trading/adapter/out/persistence/CyclePosition*.java \
  src/main/java/com/kista/trading/adapter/out/persistence/StrategyCycle*.java

git mv src/test/java/com/kista/adapter/out/persistence/strategy/CyclePositionPersistenceAdapterTest.java src/test/java/com/kista/trading/adapter/out/persistence/CyclePositionPersistenceAdapterTest.java
git mv src/test/java/com/kista/adapter/out/persistence/strategy/StrategyCyclePersistenceAdapterTest.java src/test/java/com/kista/trading/adapter/out/persistence/StrategyCyclePersistenceAdapterTest.java
git mv src/test/java/com/kista/adapter/out/persistence/strategy/StrategyCycleVrPersistenceAdapterTest.java src/test/java/com/kista/trading/adapter/out/persistence/StrategyCycleVrPersistenceAdapterTest.java
sed -i '' 's/^package com\.kista\.adapter\.out\.persistence\.strategy;/package com.kista.trading.adapter.out.persistence;/' \
  src/test/java/com/kista/trading/adapter/out/persistence/CyclePositionPersistenceAdapterTest.java \
  src/test/java/com/kista/trading/adapter/out/persistence/StrategyCyclePersistenceAdapterTest.java \
  src/test/java/com/kista/trading/adapter/out/persistence/StrategyCycleVrPersistenceAdapterTest.java
```

- [ ] **Step 5: legacy persistence/strategy에 설정 계층 11개 + PersistenceSupport 남아있는지 확인**

```bash
ls src/main/java/com/kista/adapter/out/persistence/strategy
```
Expected: `PersistenceSupport.java, StrategyEntity.java, StrategyInfiniteDetailPersistenceAdapter.java, StrategyInfiniteEntity.java, StrategyInfiniteJpaRepository.java, StrategyJpaRepository.java, StrategyPersistenceAdapter.java, StrategyVersionEntity.java, StrategyVersionJpaRepository.java, StrategyVersionPersistenceAdapter.java, StrategyVrDetailPersistenceAdapter.java, StrategyVrVersionEntity.java, StrategyVrVersionJpaRepository.java` 13개만 남음.

- [ ] **Step 6: PersistenceSupport를 trading에 복제 생성 (모듈 경계상 공유 불가 — 스펙 승인된 중복)**

```bash
cat src/main/java/com/kista/adapter/out/persistence/strategy/PersistenceSupport.java
```

위 내용을 확인한 뒤, 패키지 선언만 `com.kista.trading.adapter.out.persistence;`로 바꿔 아래 파일을 새로 만든다:

```java
package com.kista.trading.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.function.Supplier;

// strategy 패키지 내 upsert 공용 헬퍼 — 패키지 내부 전용 (com.kista.adapter.out.persistence.strategy.PersistenceSupport 복제본, 모듈 경계상 공유 불가)
final class PersistenceSupport {

    private PersistenceSupport() {}

    // id가 null이면 새 엔티티 생성, non-null이면 DB에서 조회 후 없으면 새로 생성 (find-or-create)
    static <T, ID> T findOrCreate(ID id, JpaRepository<T, ID> repo, Supplier<T> creator) {
        return id != null ? repo.findById(id).orElseGet(creator) : creator.get();
    }
}
```

이 파일은 `Write` 도구로 `src/main/java/com/kista/trading/adapter/out/persistence/PersistenceSupport.java`에 생성한다(legacy 원본과 내용이 100% 동일해야 함 — 위 `cat` 출력과 반드시 대조).

- [ ] **Step 7: 이동 확인**

```bash
find src/main/java/com/kista/trading/adapter src/test/java/com/kista/trading/adapter -name "*.java" | wc -l
```
Expected: main 20개(schedule 3 + web 1 + persistence 15 + PersistenceSupport 복제본 1) + test 7개(schedule 3 + web 1 + persistence 3) = 27

- [ ] **Step 8: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다. `OrderCancelController`가 참조하는 `TradingExecutionUseCase`는 Task 1에서 이미 `com.kista.trading.domain.port.in`으로 옮겨졌고 import도 Task 1 Step 7에서 정합화됐으므로 추가 수정 불필요할 것 — 에러 시 해당 import 재확인.

- [ ] **Step 9: trading 전체 테스트 실행**

```bash
./gradlew test --tests 'com.kista.trading.*'
```
Expected: 전부 PASS.

- [ ] **Step 10: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(trading): adapter(schedule+web+persistence)를 com.kista.trading 모듈로 이전

adapter/in/schedule 중 TradingOpenScheduler/TradingCloseScheduler/
BatchContextFactory(3개, SchedulerJobRunner/SchedulerLockService는 다른
스케쥴러도 공유하는 범용 인프라라 legacy 잔류), adapter/in/web 중
OrderCancelController(1개, TradingCycleController는 3개 아그리게이트가
뒤섞인 파사드라 legacy 잔류), adapter/out/persistence/trade 전체(3개) +
adapter/out/persistence/strategy 중 실행 이력 12개를
com.kista.trading.adapter 하위로 이동, 대응 테스트 7개 포함.

PersistenceSupport(5줄 upsert 헬퍼)는 legacy 설정 엔티티와 trading 실행
엔티티 양쪽이 공유하던 package-private 유틸이라 모듈 경계상 공유 불가 —
trading에 동일 내용으로 복제 생성(스펙에서 사전 승인됨), legacy 원본은
그대로 유지. 로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 4: `@ApplicationModule` 선언 + Named Interface 2개 공개

**Files:**
- Create: `src/main/java/com/kista/trading/package-info.java`
- Create: `src/main/java/com/kista/trading/domain/model/package-info.java`
- Create: `src/main/java/com/kista/trading/domain/strategy/package-info.java`
- Create: `src/main/java/com/kista/trading/domain/port/in/package-info.java`
- Create: `src/main/java/com/kista/trading/domain/port/out/package-info.java`
- Create: `src/main/java/com/kista/trading/application/event/package-info.java`

**Interfaces:**
- Consumes: Task 1/2/3에서 이전한 모든 trading 하위 패키지
- Produces: `com.kista.trading` 모듈 경계 선언 — 이후 `ModulithArchitectureTest.verifyModularStructure()`가 이 모듈을 포함해 순환 검증. `application.service`, `adapter.{in,out}`은 Named Interface 미선언 → 모듈 밖에서 접근 불가(internal)

- [ ] **Step 1: 루트 package-info.java 작성**

`src/main/java/com/kista/trading/package-info.java`:
```java
// trading 실행 엔진(주문/사이클 실행 이력/주문생성 전략 계열) 모듈 — domain(model+strategy+port)과 event만 공개 계약, application.service·adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.trading;
```

- [ ] **Step 2: domain Named Interface 4개 작성**

`src/main/java/com/kista/trading/domain/model/package-info.java`:
```java
// trading 모듈의 공개 계약 일부 — 주문(Order 등)·사이클 실행 이력(StrategyCycle/CyclePosition 등) 불변 값 객체. domain.strategy·domain.port.{in,out}와 함께 "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.domain.model;
```

`src/main/java/com/kista/trading/domain/strategy/package-info.java`:
```java
// trading 모듈의 공개 계약 일부 — CycleOrderStrategy 계열(주문생성 로직). "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.domain.strategy;
```

`src/main/java/com/kista/trading/domain/port/in/package-info.java`:
```java
// trading 모듈의 공개 계약 일부 — TradingExecutionUseCase/VrReconfigureUseCase. legacy TradingCycleController가 참조. "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.domain.port.in;
```

`src/main/java/com/kista/trading/domain/port/out/package-info.java`:
```java
// trading 모듈의 공개 계약 일부 — *Port 접미사 출력 포트(OrderPort, CyclePositionPort 등). "domain" 이름으로 병합 공개된다.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.trading.domain.port.out;
```

- [ ] **Step 3: event Named Interface 작성**

`src/main/java/com/kista/trading/application/event/package-info.java`:
```java
// trading 모듈의 공개 계약 — CycleCompletedEvent/CycleEndedEvent/NewCycleStartedEvent/OrderCancelFailedEvent/TradingReportReadyEvent. notify 모듈이 @TransactionalEventListener로 구독한다(CLOSED↔CLOSED 모듈 간 이벤트 교차 최초 사례). "event" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("event")
package com.kista.trading.application.event;
```

- [ ] **Step 4: ModulithArchitectureTest 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS. 실패하면 에러 메시지의 위반 모듈/타입 확인 — 대부분 old top-level 코드가 trading의 non-exposed 타입(예: `application.service.TradingService` 구체 클래스, `adapter.out.persistence.OrderEntity`)을 직접 참조하는 경우인데, 스펙 단계에서 이런 참조가 없음을 확인했으므로 나오면 안 된다. notify → trading.event 방향이 단방향(trading이 발행, notify가 구독, notify를 trading이 참조하는 코드 없음)인지도 이 시점에 확인된다 — 순환 있으면 실패.

- [ ] **Step 5: HexagonalArchitectureTest 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — `com.kista..domain.strategy..` 와일드카드가 새 경로(`com.kista.trading.domain.strategy`)를 자동 커버하므로 추가 수정 불필요. 실패 시에만 조사.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/trading/package-info.java \
        src/main/java/com/kista/trading/domain/model/package-info.java \
        src/main/java/com/kista/trading/domain/strategy/package-info.java \
        src/main/java/com/kista/trading/domain/port/in/package-info.java \
        src/main/java/com/kista/trading/domain/port/out/package-info.java \
        src/main/java/com/kista/trading/application/event/package-info.java
git commit -m "$(cat <<'EOF'
feat(modulith): trading 모듈 선언 — CLOSED + domain·event 2개 Named Interface 공개

trading 애그리게이트를 Spring Modulith ApplicationModule(CLOSED, 기본값)로
선언하고, domain.model/domain.strategy/domain.port.{in,out}을 "domain"으로,
application.event를 "event"로 NamedInterface 공개한다. application.service·
adapter.{in,out}은 미공개(internal). "event"는 notify 모듈(CLOSED)이 구독하는
CLOSED↔CLOSED 모듈 간 이벤트 교차 최초 사례.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Ck6j7xDUcMb5nY8ACL6h4h
EOF
)"
```

---

### Task 5: 문서 갱신 + 최종 전체 검증

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `CLAUDE.md`
- Modify: `docs/agents/workflow.md`(패키지 경로 언급 있는 경우만)
- Modify: `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`(진행 상태 갱신)

**Interfaces:** 없음 (문서 전용)

- [ ] **Step 1: architecture.md trading 섹션 갱신**

`docs/agents/architecture.md`의 `domain/model/order`, `domain/model/strategy`, `domain/strategy`, `domain/port/{in,out}`, `application/service/trading`, `adapter/in/{schedule,web}`, `adapter/out/persistence/{trade,strategy}` 설명에서 이번에 이동한 항목을 `com.kista.trading.domain.model`, `com.kista.trading.domain.strategy`, `com.kista.trading.domain.port.{in,out}`, `com.kista.trading.application.{service,event}`, `com.kista.trading.adapter.{in,out}`로 갱신. finance/notify/broker 이전 때 추가된 서술 바로 아래에 trading 단락 추가(동일 톤 — 패키지 요약 + "이미 모듈로 이전됨" 명시). legacy에 남는 Strategy 설정 계층(`Strategy, StrategyVersion, StrategyInfiniteDetail, StrategyVrDetail` 등)은 "향후 strategy 모듈 후보"로 명시. "Spring Modulith 점진 도입" 절의 순서 서술을 `finance✅ → notify✅ → broker✅ → trading✅`로 갱신.

- [ ] **Step 2: constraints.md 갱신**

```bash
grep -n "domain/model/order\|domain/model/strategy\|domain\.strategy\|application/service/trading\|adapter/in/schedule\|adapter/out/persistence/trade\|adapter/out/persistence/strategy" docs/agents/constraints.md
```
결과가 있는 라인들을 새 경로로 갱신. 특히 "매매 공식", "VR 공식", "Account ↔ Strategy 분리" 절은 `StrategyCycle`/`CyclePosition`/`AccountBalance` 등을 언급하므로 FQN이 아닌 클래스명 레퍼런스는 그대로 두되, 패키지 경로가 명시된 곳만 교정.

- [ ] **Step 3: CLAUDE.md 갱신**

```bash
git show 88964101 -- CLAUDE.md
```
위 diff(broker 이전 때 CLAUDE.md 반영 커밋)를 참고해 동일 위치에 trading 이전 완료 사실 반영.

- [ ] **Step 4: workflow.md 패키지 경로 확인**

```bash
grep -n "com\.kista\.application\.service\.trading\|com\.kista\.domain\.model\.\(order\|strategy\)\|com\.kista\.domain\.strategy\|com\.kista\.adapter\.in\.schedule\|com\.kista\.adapter\.out\.persistence\.\(trade\|strategy\)" docs/agents/workflow.md
```
결과가 있으면 `com.kista.trading.*`로 갱신, 없으면 스킵.

- [ ] **Step 5: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 "이전 전략 → 순서" 절을 `finance✅ → notify✅ → broker✅ → trading 코어✅`로 갱신.

- [ ] **Step 6: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 테스트 특정 후 수정.

- [ ] **Step 7: 커밋**

```bash
git add docs/agents/architecture.md docs/agents/constraints.md CLAUDE.md \
        docs/agents/workflow.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git commit -m "$(cat <<'EOF'
docs(modulith): trading 모듈 이전 반영 — architecture/constraints/CLAUDE.md/스펙 갱신

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

이미 이 계획에 반영된 항목(`PersistenceSupport` 복제)은 예외 — 브레인스토밍 단계에서 이미 승인됨.
