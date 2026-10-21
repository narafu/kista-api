# 포트 위치 전환 — trading 청크 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.trading.domain.port.in`(2개)를 `com.kista.trading.application.usecase`로, `com.kista.trading.domain.port.out`(6개)를 `com.kista.trading.application.port.output`로 이전한다. 기존 `"domain"` NamedInterface(model+strategy+port.in+port.out 병합)가 model+strategy만으로 축소되고, `"usecase"`(application.usecase)·`"port"`(application.port.output) 신설. `"event"`·`"schedule"`은 변경 없음. **5개 청크 중 마지막**(레거시·finance·notify·broker 완료, main 병합됨 commit `193a96a5`).

**Architecture:** 이전 4개 청크와 동일한 sed 기법 재사용. port/in(2개)·port/out(6개) 합쳐 8개 파일, 소비자는 port/in 10개+port/out 71개(경로 다른 두 그룹, 겹침 없음). 규모상 broker(16개, 56소비자)보다 크므로 2태스크로 진행: **Task 1**(8개 파일 이동 + 전역 import 정합화 + compile 검증), **Task 2**(NamedInterface 재구성("domain" 축소, "usecase"+"port" 신설, "event"·"schedule" 유지) + ArchUnit·Modulith 검증 + 문서 갱신(모듈 루트 package-info.java 포함) + 전체 테스트 스위트 최종 검증). 로직 변경 없음.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-30-port-location-migration-design.md`

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — git worktree fallback으로 브랜치 `worktree-port-location-trading-migration` 신규 생성(로컬 `main`에서 분기 — `origin/main`이 뒤처져 있을 수 있으니 분기 전 `git merge-base --is-ancestor origin/main main`으로 확인) 후 그 위에서 진행
- `HexagonalArchitectureTest`는 레거시 청크에서 이미 프로젝트 전역 와일드카드로 재작성됐다 — **이 청크에서 수정하지 않는다**(포트만 옮기면 자동 커버). 단, 이 청크가 5개 청크 중 마지막이므로 병합 완료 후(이 계획 범위 밖) `com.kista..domain.port.out..`을 매칭하는 옛 규칙 줄이 죽은 코드가 된다 — 삭제는 별도 후속 항목으로 남기고 이번 계획에서는 손대지 않는다
- **모듈 루트 `package-info.java`(`src/main/java/com/kista/trading/package-info.java`) 코멘트 갱신을 Task 2의 파일 목록에 명시적으로 포함한다** — finance 청크에서 이 파일이 태스크 목록 누락으로 stale해진 사례가 있었음
- **비한정(unqualified) old-path grep sweep 필수(broker 청크 최종 리뷰 교훈)**: 지금까지 각 청크는 `<module>\.domain\.port` 식으로 모듈명을 포함한 qualified grep만 사용했는데, 이는 모듈명 없이 프로즈(주석 등)에 쓰인 경로 언급을 놓친다 — broker 청크에서 `TokenCoordinator.java`/`MockSimulationDataPort.java`의 주석 2건이 이 방식으로 실제 누락됐다가 최종 리뷰에서 잡혔다. Task 2 Step 6에서 `grep -rn "domain/port/out\|domain\.port\.out\|domain/port/in\|domain\.port\.in" src/ README.md AGENTS.md docs/agents/` (모듈명 없이, 비한정) 전체 재스캔을 반드시 수행 — trading은 port/in·port/out 둘 다 있어 이 드리프트가 가장 발생하기 쉬운 마지막 청크
- Task 1은 `compileJava`/`compileTestJava` 통과만 검증, 전체 테스트 스위트는 Task 2 최종 1회
- 커밋 메시지: 한글, Conventional Commit 접두사, author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고**
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용
- 파일 인코딩: BOM 삽입 주의

---

### Task 1: `trading/domain/port/{in,out}`(8개) → `trading/application/{usecase,port/output}` 이전 + 전역 import 정합화

**Files:**
- Move → `src/main/java/com/kista/trading/application/usecase/`: `TradingExecutionUseCase.java, VrReconfigureUseCase.java`(기존 `trading/domain/port/in/*` 2개)
- Move → `src/main/java/com/kista/trading/application/port/output/`: `CyclePositionInfiniteDetailPort.java, CyclePositionPort.java, OrderPort.java, StrategyCyclePort.java, StrategyCycleVrPort.java, TradingErrorReportPort.java`(기존 `trading/domain/port/out/*` 6개)
- Modify (import 경로 일괄 sed, 아래 Step 2/3): `com.kista.trading.domain.port.in.*`를 참조하는 전체 파일(착수 시점 기준 10개 — 레거시 `TradingCycleController`, trading 내부 스케쥴러 2종·컨트롤러 1종·서비스 2종, 대응 테스트 4개, 와일드카드 0건), `com.kista.trading.domain.port.out.*`를 참조하는 전체 파일(착수 시점 기준 71개 — 레거시 서비스 다수, trading 내부 서비스 다수, 와일드카드 11건: `AdminQueryService, UserService, UserCascadeDeleter, StatsService, StrategyService, TradingService, CyclePositionPersistor, TradingReporter, VrCycleRolloverService, CycleRotationService, ManualTradingService`의 main + 대응 test 다수)

**Interfaces:**
- Produces: `com.kista.trading.application.usecase.*`(2개) — Task 2에서 `"usecase"` NamedInterface로 공개
- Produces: `com.kista.trading.application.port.output.*`(6개) — Task 2에서 `"port"` NamedInterface로 공개

- [ ] **Step 0: 착수 직전 재확인**

```bash
find src/main/java/com/kista/trading/domain/port/in -name "*.java" ! -name "package-info.java" | wc -l
find src/main/java/com/kista/trading/domain/port/out -name "*.java" ! -name "package-info.java" | wc -l
```
Expected: `2`, `6`.

```bash
grep -rl "import com\.kista\.trading\.domain\.port\.in\." src/main/java src/test/java > /tmp/trading-usecase-consumers.txt
wc -l /tmp/trading-usecase-consumers.txt
grep -rl "import com\.kista\.trading\.domain\.port\.out\." src/main/java src/test/java > /tmp/trading-port-consumers.txt
wc -l /tmp/trading-port-consumers.txt
grep -rln "import com\.kista\.trading\.domain\.port\.in\.\*;" src/main/java src/test/java
grep -rln "import com\.kista\.trading\.domain\.port\.out\.\*;" src/main/java src/test/java
```
Expected: 10 근처(port.in), 71 근처(port.out), 와일드카드는 port.in 0건 / port.out 11건 근처(위 목록).

```bash
git grep -n "com\.kista\.trading\.domain\.port" -- '*.java' | grep -v "^src/main/java/com/kista/trading/domain/port/" | grep -v "import "
```
문자열 리터럴 FQN(AOP `@Around`/`@Pointcut` 등) 참조 확인 — 착수 전 사전 스캔 결과 0건. 재확인 필수(broker 청크 교훈).

- [ ] **Step 1: port/in 2개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/trading/application/usecase
git mv src/main/java/com/kista/trading/domain/port/in/TradingExecutionUseCase.java \
       src/main/java/com/kista/trading/application/usecase/TradingExecutionUseCase.java
git mv src/main/java/com/kista/trading/domain/port/in/VrReconfigureUseCase.java \
       src/main/java/com/kista/trading/application/usecase/VrReconfigureUseCase.java
rmdir src/main/java/com/kista/trading/domain/port/in
sed -i '' 's/^package com\.kista\.trading\.domain\.port\.in;/package com.kista.trading.application.usecase;/' src/main/java/com/kista/trading/application/usecase/*.java
```

- [ ] **Step 2: port/out 6개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/trading/application/port/output
for f in CyclePositionInfiniteDetailPort CyclePositionPort OrderPort StrategyCyclePort StrategyCycleVrPort TradingErrorReportPort; do
  git mv src/main/java/com/kista/trading/domain/port/out/${f}.java \
         src/main/java/com/kista/trading/application/port/output/${f}.java
done
rmdir src/main/java/com/kista/trading/domain/port/out
rmdir src/main/java/com/kista/trading/domain/port
sed -i '' 's/^package com\.kista\.trading\.domain\.port\.out;/package com.kista.trading.application.port.output;/' src/main/java/com/kista/trading/application/port/output/*.java
```

`trading/domain/port`는 `in`+`out` 두 하위 디렉토리가 유일한 내용물이라(`trading/domain`은 `model/`·`strategy/`가 남아있어 지우지 않는다) 이 이동으로 `trading/domain/port` 디렉토리가 비어야 정상이다. `rmdir`이 "Directory not empty"로 실패하면 예상 밖 파일이 남아있다는 뜻 — 중단하고 확인.

- [ ] **Step 3: 전역 import 경로 일괄 치환**

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/import com\.kista\.trading\.domain\.port\.in\.\*;/import com.kista.trading.application.usecase.*;/g' \
  -e 's/import com\.kista\.trading\.domain\.port\.in\.\([A-Za-z]*\);/import com.kista.trading.application.usecase.\1;/g' \
  -e 's/import com\.kista\.trading\.domain\.port\.out\.\*;/import com.kista.trading.application.port.output.*;/g' \
  -e 's/import com\.kista\.trading\.domain\.port\.out\.\([A-Za-z]*\);/import com.kista.trading.application.port.output.\1;/g'
```

- [ ] **Step 4: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.trading\.domain\.port\." src/main/java src/test/java
find src/main/java/com/kista/trading/domain/port -maxdepth 0 2>/dev/null
```
Expected: 둘 다 결과 없음(두 번째 명령은 `domain/port` 디렉토리 자체가 사라졌는지 확인).

- [ ] **Step 5: 이동 확인**

```bash
find src/main/java/com/kista/trading/application/usecase -name "*.java" | wc -l
find src/main/java/com/kista/trading/application/port/output -name "*.java" | wc -l
```
Expected: `2`, `6`.

- [ ] **Step 6: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(port): trading 모듈 포트 8개를 domain/port/{in,out}에서 application/{usecase,port/output}으로 이전

trading/domain/port/in의 UseCase 인터페이스 2개(TradingExecutionUseCase/
VrReconfigureUseCase)를 trading/application/usecase로, trading/domain/port/out의
*Port 인터페이스 6개(CyclePositionInfiniteDetailPort/CyclePositionPort/OrderPort/
StrategyCyclePort/StrategyCycleVrPort/TradingErrorReportPort)를
trading/application/port/output으로 물리 이동. 이를 참조하던 81개 파일
(레거시 서비스·컨트롤러, trading 내부 스케쥴러·서비스 다수, 와일드카드
import 11건 포함)의 import 경로를 일괄 갱신 — 로직 변경 없음. 이 이동으로
domain/port 디렉토리 전체가 제거됨(domain/model·domain/strategy는 유지).
NamedInterface 재구성은 Task 2에서 처리. 5개 청크 중 마지막.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01PGZMLuEWvQ21kQZHhWdqo9
EOF
)"
```

---

### Task 2: NamedInterface 재구성("domain" 축소, "usecase"+"port" 신설, "event"·"schedule" 유지) + ArchUnit·Modulith 검증 + 문서 갱신 + 최종 검증

**Files:**
- Delete: `src/main/java/com/kista/trading/domain/port/in/package-info.java`, `src/main/java/com/kista/trading/domain/port/out/package-info.java`(Task 1에서 디렉토리째 이미 삭제됨 — 확인만)
- Create: `src/main/java/com/kista/trading/application/usecase/package-info.java`, `src/main/java/com/kista/trading/application/port/output/package-info.java`
- Modify: `src/main/java/com/kista/trading/domain/model/package-info.java`, `src/main/java/com/kista/trading/domain/strategy/package-info.java`(병합 공개 서술에서 port.{in,out} 언급 제거)
- Modify: `src/main/java/com/kista/trading/package-info.java`(모듈 루트)
- Modify: `docs/agents/architecture.md`, `docs/agents/constraints.md`, `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`, `docs/agents/workflow.md`(포트 경로 언급 있으면)

**Interfaces:**
- Produces: trading 모듈의 `"usecase"`(application.usecase)·`"port"`(application.port.output) NamedInterface 신설 — `"domain"`은 domain.model+domain.strategy만으로 축소, `"event"`·`"schedule"`은 변경 없이 유지

- [ ] **Step 1: `application/usecase/package-info.java` 신규 작성**

`src/main/java/com/kista/trading/application/usecase/package-info.java`:

```java
// trading 모듈의 공개 계약 일부 — TradingExecutionUseCase/VrReconfigureUseCase. legacy TradingCycleController가 참조. "usecase" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.trading.application.usecase;
```

- [ ] **Step 2: `application/port/output/package-info.java` 신규 작성**

`src/main/java/com/kista/trading/application/port/output/package-info.java`:

```java
// trading 모듈의 공개 계약 일부 — *Port 접미사 출력 포트(OrderPort, CyclePositionPort 등). "port" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.trading.application.port.output;
```

- [ ] **Step 3: `domain/model/package-info.java`, `domain/strategy/package-info.java` 갱신 — "domain" 병합 대상에서 port.{in,out} 제거**

`src/main/java/com/kista/trading/domain/model/package-info.java`를 다음으로 교체:

```diff
- // trading 모듈의 공개 계약 일부 — 주문(Order 등)·사이클 실행 이력(StrategyCycle/CyclePosition 등) 불변 값 객체. domain.strategy·domain.port.{in,out}와 함께 "domain" 이름으로 병합 공개된다.
+ // trading 모듈의 공개 계약 일부 — 주문(Order 등)·사이클 실행 이력(StrategyCycle/CyclePosition 등) 불변 값 객체. domain.strategy와 함께 "domain" 이름으로 병합 공개된다. UseCase/Port는 별도 "usecase"/"port" 이름으로 공개.
  @org.springframework.modulith.NamedInterface("domain")
  package com.kista.trading.domain.model;
```

`src/main/java/com/kista/trading/domain/strategy/package-info.java`는 이미 "domain" 병합 공개 서술만 있고 port.{in,out}을 직접 언급하지 않으므로 수정 불필요(확인만 — 언급이 있다면 동일 패턴으로 제거).

- [ ] **Step 4: 모듈 루트 `package-info.java` 갱신**

`src/main/java/com/kista/trading/package-info.java`를 다음으로 교체:

```java
// trading 실행 엔진(주문/사이클 실행 이력/주문생성 전략 계열) 모듈 — "domain"(domain.model+domain.strategy)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개, application.service·adapter.out.*은 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.trading;
```

- [ ] **Step 5: `ModulithArchitectureTest` 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS. 실패하면 다른 모듈이 trading의 옛 `domain.port.{in,out}`(이제 없는 경로)을 여전히 참조하는 코드가 있다는 뜻 — Task 1의 import 치환 누락 가능성.

- [ ] **Step 6: `HexagonalArchitectureTest` 회귀 확인 + 비한정 old-path grep sweep**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — 추가 수정 불필요.

**비한정(unqualified) 재스캔 (broker 청크 최종 리뷰 교훈 — 필수)**:

```bash
grep -rn "domain/port/out\|domain\.port\.out\|domain/port/in\|domain\.port\.in" src/ README.md AGENTS.md docs/agents/
```

`<module>\.domain\.port` 식 qualified grep은 모듈명 없이 프로즈(주석)에 쓰인 경로 언급을 놓친다 — broker 청크에서 `TokenCoordinator.java`/`MockSimulationDataPort.java`의 주석 2건이 이 방식으로 실제 누락되고 최종 리뷰에서 잡혔다. 이번엔 Task 2 안에서 미리 걸러낼 것. 결과에 나온 항목 중 trading 관련(코드 주석·문서)은 전부 `application/{usecase,port/output}` 형태로 교체. legacy/finance/notify/broker 관련 언급이나 아직 이전하지 않은 다른 모듈 참조는 손대지 않는다(스코프 밖).

- [ ] **Step 7: architecture.md trading 섹션 갱신**

`docs/agents/architecture.md`의 `com.kista.trading/` 블록 첫 줄을 갱신:

```diff
- com.kista.trading/   ← Spring Modulith 4번째(마지막) 이전 모듈(CLOSED) — 주문/사이클 실행 이력/주문생성 전략 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"event"·"schedule" 3개 NamedInterface만 공개 — application.service·adapter.out.*은 의도적으로 비공개(모듈 내부 구현)
+ com.kista.trading/   ← Spring Modulith 4번째(마지막) 이전 모듈(CLOSED) — 주문/사이클 실행 이력/주문생성 전략 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"usecase"·"port"·"event"·"schedule" 5개 NamedInterface 공개 — application.service·adapter.out.*은 의도적으로 비공개(모듈 내부 구현)
```

`domain/port/in/`, `domain/port/out/` 라인을 찾아 각각 `application/usecase/`, `application/port/output/`으로 교체(설명 문구는 그대로 유지하되 경로·NamedInterface 이름만 갱신):

```diff
- domain/port/in/     ← TradingExecutionUseCase/VrReconfigureUseCase — legacy TradingCycleController가 참조. "domain" 이름으로 병합 공개
+ application/usecase/ ← TradingExecutionUseCase/VrReconfigureUseCase — legacy TradingCycleController가 참조. "usecase" 이름으로 공개
```

```diff
- domain/port/out/    ← OrderPort/CyclePositionPort/CyclePositionInfiniteDetailPort/StrategyCyclePort/StrategyCycleVrPort/TradingErrorReportPort — "domain" 이름으로 병합 공개
+ application/port/output/ ← OrderPort/CyclePositionPort/CyclePositionInfiniteDetailPort/StrategyCyclePort/StrategyCycleVrPort/TradingErrorReportPort — "port" 이름으로 공개
```

"Spring Modulith 점진 도입" 단락에서 trading 서술 갱신:

```diff
- `trading`이 네 번째(마지막) 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.strategy+domain.port.{in,out})·"event"(application.event)·"schedule"(adapter.in.schedule) 3개 NamedInterface 공개 — application.service·adapter.out.*은 비공개).
+ `trading`이 네 번째(마지막) 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.strategy)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개 — application.service·adapter.out.*은 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 domain.port.{in,out}을 "domain"에 병합 공개했었다).
```

Step 6의 비한정 grep 결과에 architecture.md 안의 다른 trading 관련 잔여 언급(예: 다른 절에서 `trading.domain.port` 언급)이 나오면 여기서 함께 교체.

- [ ] **Step 8: constraints.md 갱신**

`docs/agents/constraints.md`의 trading 관련 라인 갱신:

```diff
- 매매 코어 애그리게이트(주문/사이클 실행 이력/주문생성 전략)는 `com.kista.trading`으로 이미 옮겨졌다 — 신규 trading 관련 코드도 레거시 최상위가 아닌 `com.kista.trading` 안에 추가. `domain/{model,strategy,port/in,port/out}`이 "domain"으로, `application/event`가 "event"로, `adapter/in/schedule`이 "schedule"로 NamedInterface 공개 — `application/service`·`adapter/out/*`은 비공개(internal)
+ 매매 코어 애그리게이트(주문/사이클 실행 이력/주문생성 전략)는 `com.kista.trading`으로 이미 옮겨졌다 — 신규 trading 관련 코드도 레거시 최상위가 아닌 `com.kista.trading` 안에 추가. `domain/{model,strategy}`가 "domain"으로, `application/usecase`가 "usecase"로, `application/port/output`이 "port"로, `application/event`가 "event"로, `adapter/in/schedule`이 "schedule"로 NamedInterface 공개 — `application/service`·`adapter/out/*`은 비공개(internal)
```

Step 6의 비한정 grep 결과에 나온 다른 trading 관련 constraints.md 언급도 동일 패턴으로 교체(예: "모듈 경계 포트 시그니처" 절이 broker/notify만 언급하고 trading을 언급하지 않으면 손대지 않음 — trading은 이 절의 대상이 아니라 매핑 주체이므로).

- [ ] **Step 9: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 보류 1번 항목 갱신 — 실제 파일의 현재 문장을 먼저 확인한 뒤(broker 청크에서 브리프의 인용문이 실제 파일과 다를 수 있었던 전례 있음) 진행 상태 부분만 정확히 교체:

```bash
grep -n "포트 위치를 domain" docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
```

찾은 줄에서 "레거시·finance·notify·broker 완료, 나머지 1개(trading) 진행 예정" 부분을 "레거시·finance·notify·broker·trading 5개 청크 전부 완료"로 교체(문장 앞부분은 그대로 유지, 진행 상태 tail만 교체).

- [ ] **Step 10: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 결과 요약 라인(`BUILD SUCCESSFUL in Ns` 등)을 리포트에 그대로 포함할 것 — 요약 문장으로 대체하지 말 것.

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/kista/trading/application/usecase/package-info.java \
        src/main/java/com/kista/trading/application/port/output/package-info.java \
        src/main/java/com/kista/trading/domain/model/package-info.java \
        src/main/java/com/kista/trading/domain/strategy/package-info.java \
        src/main/java/com/kista/trading/package-info.java \
        docs/agents/architecture.md docs/agents/constraints.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
# Step 6/9에서 docs/agents/workflow.md 등 추가로 손댄 파일이 있으면 함께 add
git status --short
git commit -m "$(cat <<'EOF'
feat(modulith): trading 모듈 NamedInterface에 "usecase"·"port" 신설 + 문서 갱신

trading의 "domain" NamedInterface(domain.model+domain.strategy+domain.port.{in,out}를
병합 공개하던 것)를 Task 1의 포트 이동에 맞춰 domain.model+domain.strategy만
남도록 축소하고, 이동한 UseCase 2개·Port 6개를 위한 "usecase"(application.usecase)·
"port"(application.port.output) NamedInterface를 각각 신설했다. 기존
"event"(application.event)·"schedule"(adapter.in.schedule)은 변경 없이 유지.
모듈 루트 package-info.java도 함께 갱신. ModulithArchitectureTest·
HexagonalArchitectureTest 모두 통과 확인, 비한정 old-path grep sweep으로
잔여 참조 없음 확인.

architecture.md/constraints.md/상위 Modulith 스펙의 trading 관련 서술을 새
구조 기준으로 갱신 — 5개 청크(레거시·finance·notify·broker·trading) 전체
완료.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01PGZMLuEWvQ21kQZHhWdqo9
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점을 발견하면:
1. **임의로 고치지 않는다** — 이 계획의 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고

## 다음 청크

trading이 5개 청크 중 마지막이다. 이 청크가 main에 병합되면 포트 위치 전환 마이그레이션 전체가 완료된다. 후속 항목(별도 승인 필요, 이 계획 스코프 밖):
- `HexagonalArchitectureTest`의 `"com.kista..domain.port.out.."` 매처가 이제 모든 모듈에서 죽은 규칙이 됨 — legacy 청크 이후 최초로 이 파일을 정당하게 다시 손댈 시점(제거 또는 주석 처리 검토)
- `application.usecase` 인터페이스에 `*UseCase`/`*Query` 접미사 강제 ArchUnit 규칙 신규 추가는 설계 스펙에서 명시적으로 스코프 밖으로 분류됨 — 필요 시 별도 제안
