# 포트 위치 전환 — broker 청크 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.broker.domain.port.out`(16개 포트 인터페이스)를 `com.kista.broker.application.port.output`로 이전한다. broker는 domain/port/in이 없어(port/out만 존재) `"usecase"` NamedInterface는 불필요 — 기존 `"domain"`(domain.model+kis+toss+port.out 병합) NamedInterface가 model-only로 축소되고 `"port"`(application.port.output) 신설, 기존 `"application"`(application.service)은 변경 없이 유지. 5개 청크 중 네 번째(레거시·finance·notify 완료, main 병합됨 commit `1d02faea`).

**Architecture:** 레거시·finance·notify 청크와 동일한 sed 기법 재사용. 규모는 notify(4개)보다 크고 finance(16개)와 파일 수는 같지만 소비자(56개)·와일드카드 import(4개)가 많아 2태스크로 진행: **Task 1**(16개 파일 이동 + 전역 import 정합화 + compile 검증), **Task 2**(NamedInterface 재구성("domain" 축소, "port" 신설, "application" 유지) + ArchUnit·Modulith 검증 + 문서 갱신(모듈 루트 package-info.java 포함) + 전체 테스트 스위트 최종 검증). 로직 변경 없음.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-30-port-location-migration-design.md`

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — `EnterWorktree`(또는 `superpowers:using-git-worktrees`)로 브랜치 `worktree-port-location-broker-migration` 신규 생성 후 그 위에서 진행
- `HexagonalArchitectureTest`는 레거시 청크에서 이미 프로젝트 전역 와일드카드로 재작성됐다 — **이 청크에서 수정하지 않는다**(포트만 옮기면 자동 커버)
- **모듈 루트 `package-info.java`(`src/main/java/com/kista/broker/package-info.java`) 코멘트 갱신을 Task 2의 파일 목록에 명시적으로 포함한다** — finance 청크 최종 리뷰에서 이 파일이 태스크 목록 누락으로 stale해진 사례가 있었음(별도 fix wave로 사후 수정), notify 청크는 미리 포함해 문제 없었음
- **AOP 포인트컷 등 문자열 리터럴 FQN 재스캔 필수** — Task 1 Step 0에서 `git grep "com\.kista\.broker\.domain\.port"` 확장자 필터 없이 재실행. 착수 전 사전 스캔 결과: broker 관련 `@Around`/`@Pointcut` 문자열 리터럴 참조는 발견되지 않음(0건) — 단, 재확인은 계획대로 수행할 것(notify 청크에서 사전 판단이 틀렸던 전례 있음)
- Task 1은 `compileJava`/`compileTestJava` 통과만 검증, 전체 테스트 스위트는 Task 2 최종 1회
- 커밋 메시지: 한글, Conventional Commit 접두사, author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고**
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용
- 파일 인코딩: BOM 삽입 주의

---

### Task 1: `broker/domain/port/out`(16개) → `broker/application/port/output` 이전 + 전역 import 정합화

**Files:**
- Move → `src/main/java/com/kista/broker/application/port/output/`: `BrokerAccountPort.java, BrokerAdapterPort.java, BrokerConnectionTestPort.java, BrokerMarketCalendarPort.java, BrokerOrderCorrectionPort.java, BrokerPricePort.java, BrokerTokenCachePort.java, CandlePort.java, ExchangeRatePort.java, ExecutionPort.java, LiveBalancePort.java, MarginPort.java, MockSimulationDataPort.java, PortfolioPort.java, SellableQuantityPort.java, StockInfoPort.java`(기존 `broker/domain/port/out/*` 16개)
- Modify (import 경로 일괄 sed, 아래 Step 2): `com.kista.broker.domain.port.out.*`를 참조하는 전체 파일 — 착수 시점 기준 56개(레거시 최상위 서비스/컨트롤러, broker 내부 kis/toss/mock/internal/persistence 어댑터, trading의 application.service 다수 포함), 와일드카드 import 4건(`TossBrokerAdapter`, `MockBrokerAdapter`, `KisBrokerAdapter`, `TossStatisticsService`)

**Interfaces:**
- Produces: `com.kista.broker.application.port.output.*`(16개) — Task 2에서 `"port"` NamedInterface로 공개

- [ ] **Step 0: 착수 직전 재확인**

```bash
find src/main/java/com/kista/broker/domain/port/out -name "*.java" ! -name "package-info.java" | wc -l
```
Expected: `16`.

```bash
grep -rl "import com\.kista\.broker\.domain\.port\.out\." src/main/java src/test/java | grep -v "^src/main/java/com/kista/broker/domain/port/" > /tmp/broker-port-consumers.txt
wc -l /tmp/broker-port-consumers.txt
grep -rln "import com\.kista\.broker\.domain\.port\.out\.\*;" src/main/java src/test/java
```
Expected: 56 근처, 와일드카드 4건(위 목록).

```bash
git grep -n "com\.kista\.broker\.domain\.port" -- '*.java'
```
문자열 리터럴 FQN(AOP `@Around`/`@Pointcut` 등) 참조가 있는지 확인 — import 문 외에 코드 문자열 안에서 발견되면 이 이동만으로 갱신되지 않으니 별도 sed 대상에 추가. 사전 스캔 결과 없었으나(위 Global Constraints 참고) 재확인은 필수.

- [ ] **Step 1: 16개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/broker/application/port/output
for f in BrokerAccountPort BrokerAdapterPort BrokerConnectionTestPort BrokerMarketCalendarPort \
         BrokerOrderCorrectionPort BrokerPricePort BrokerTokenCachePort CandlePort ExchangeRatePort \
         ExecutionPort LiveBalancePort MarginPort MockSimulationDataPort PortfolioPort \
         SellableQuantityPort StockInfoPort; do
  git mv src/main/java/com/kista/broker/domain/port/out/${f}.java \
         src/main/java/com/kista/broker/application/port/output/${f}.java
done
rmdir src/main/java/com/kista/broker/domain/port/out
rmdir src/main/java/com/kista/broker/domain/port
sed -i '' 's/^package com\.kista\.broker\.domain\.port\.out;/package com.kista.broker.application.port.output;/' src/main/java/com/kista/broker/application/port/output/*.java
```

`broker/domain/port`는 `out` 하위 16개 파일이 유일한 내용물이라 이 이동으로 `broker/domain/port` 디렉토리가 비어야 정상이다(`broker/domain`은 `model/`이 남아있어 지우지 않는다). `rmdir`이 "Directory not empty"로 실패하면 예상 밖 파일이 남아있다는 뜻 — 중단하고 확인.

- [ ] **Step 2: 전역 import 경로 일괄 치환**

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/import com\.kista\.broker\.domain\.port\.out\.\*;/import com.kista.broker.application.port.output.*;/g' \
  -e 's/import com\.kista\.broker\.domain\.port\.out\.\([A-Za-z]*\);/import com.kista.broker.application.port.output.\1;/g'
```

- [ ] **Step 3: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.broker\.domain\.port\." src/main/java src/test/java
find src/main/java/com/kista/broker/domain/port -maxdepth 0 2>/dev/null
```
Expected: 둘 다 결과 없음(두 번째 명령은 `domain/port` 디렉토리 자체가 사라졌는지 확인).

- [ ] **Step 4: 이동 확인**

```bash
find src/main/java/com/kista/broker/application/port/output -name "*.java" | wc -l
```
Expected: `16`.

- [ ] **Step 5: compileJava, compileTestJava 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 둘 다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git status --short
git commit -m "$(cat <<'EOF'
refactor(port): broker 모듈 포트 16개를 domain/port/out에서 application/port/output으로 이전

broker/domain/port/out의 *Port 인터페이스 16개(BrokerAccountPort/BrokerAdapterPort/
BrokerConnectionTestPort/BrokerMarketCalendarPort/BrokerOrderCorrectionPort/
BrokerPricePort/BrokerTokenCachePort/CandlePort/ExchangeRatePort/ExecutionPort/
LiveBalancePort/MarginPort/MockSimulationDataPort/PortfolioPort/
SellableQuantityPort/StockInfoPort)를 broker/application/port/output으로
물리 이동. 이를 참조하던 56개 파일(레거시 서비스, broker 내부 kis/toss/mock/
internal/persistence 어댑터, trading application.service 다수 포함, 와일드카드
import 4건)의 import 경로를 일괄 갱신 — 로직 변경 없음. broker는
domain/port/in이 애초에 없는 모듈이라 이 이동으로 domain/port 디렉토리 전체가
제거됨(domain/model은 유지). NamedInterface 재구성은 Task 2에서 처리.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01PGZMLuEWvQ21kQZHhWdqo9
EOF
)"
```

---

### Task 2: NamedInterface 재구성("domain" 축소, "port" 신설, "application" 유지) + ArchUnit·Modulith 검증 + 문서 갱신 + 최종 검증

**Files:**
- Delete: `src/main/java/com/kista/broker/domain/port/out/package-info.java`(Task 1에서 디렉토리째 이미 삭제됨 — 확인만)
- Create: `src/main/java/com/kista/broker/application/port/output/package-info.java`
- Modify: `src/main/java/com/kista/broker/domain/model/package-info.java`(병합 공개 서술에서 domain.port.out 언급 제거)
- Modify: `src/main/java/com/kista/broker/package-info.java`(모듈 루트)
- Modify: `docs/agents/architecture.md`, `docs/agents/constraints.md`, `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`

**Interfaces:**
- Produces: broker 모듈의 `"port"`(application.port.output) NamedInterface 신설 — `"domain"`은 domain.model(+kis+toss)만으로 축소, `"application"`(application.service)은 변경 없이 유지

- [ ] **Step 1: `application/port/output/package-info.java` 신규 작성**

`src/main/java/com/kista/broker/application/port/output/package-info.java`:

```java
// broker 모듈의 공개 계약 일부 — *Port 접미사 출력 포트 인터페이스(BrokerAdapterPort, LiveBalancePort 등). "port" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.broker.application.port.output;
```

- [ ] **Step 2: `domain/model/package-info.java` 갱신 — "domain" 병합 대상에서 port.out 제거**

`src/main/java/com/kista/broker/domain/model/package-info.java`를 다음으로 교체:

```diff
- // broker 모듈의 공개 계약 일부 — 공통 불변 값 객체(Currency/DailyTransaction* 등). domain.model.kis/toss·domain.port.out·application.service와 함께 "domain"/"application" 이름으로 병합 공개된다.
+ // broker 모듈의 공개 계약 일부 — 공통 불변 값 객체(Currency/DailyTransaction* 등). domain.model.kis/toss와 함께 "domain" 이름으로 병합 공개된다. application.service는 별도 "application" 이름으로 공개.
  @org.springframework.modulith.NamedInterface("domain")
  package com.kista.broker.domain.model;
```

`domain/model/kis/package-info.java`·`domain/model/toss/package-info.java`는 이미 "domain" 병합 공개 서술만 있고 port.out을 언급하지 않으므로 수정 불필요(확인만).

- [ ] **Step 3: 모듈 루트 `package-info.java` 갱신**

`src/main/java/com/kista/broker/package-info.java`를 다음으로 교체:

```java
// broker 애그리게이트(KIS/Toss/Mock 증권사 API 연동) 모듈 — "domain"(domain.model+kis+toss)·"port"(application.port.output)·"application"(application.service) 3개 NamedInterface 공개, adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.broker;
```

- [ ] **Step 4: `ModulithArchitectureTest` 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS. 실패하면 다른 모듈이 broker의 옛 `domain.port.out`(이제 없는 경로)을 여전히 참조하는 코드가 있다는 뜻 — Task 1의 import 치환 누락 가능성.

- [ ] **Step 5: `HexagonalArchitectureTest` 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — 추가 수정 불필요.

- [ ] **Step 6: architecture.md broker 섹션 갱신**

`docs/agents/architecture.md`의 `com.kista.broker/` 블록 첫 줄을 갱신:

```diff
- com.kista.broker/    ← Spring Modulith 3번째 이전 모듈(CLOSED) — KIS/Toss/Mock 증권사 연동 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"application" 두 NamedInterface만 공개, adapter/out은 의도적으로 비공개(모듈 내부 구현)
+ com.kista.broker/    ← Spring Modulith 3번째 이전 모듈(CLOSED) — KIS/Toss/Mock 증권사 연동 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"·"port"·"application" 3개 NamedInterface 공개, adapter/out은 의도적으로 비공개(모듈 내부 구현)
```

`domain/port/out/` 라인을 찾아 `application/port/output/`으로 교체(15개→16개 정정 포함):

```diff
- domain/port/out/    ← 브로커 Capability 인터페이스(*Port) 총 16개 — 공통 7개(KIS/Toss/Mock 모두 구현) + BrokerAdapterPort(라우팅 마커) + BrokerConnectionTestPort(*AuthApi 클래스가 구현) + BrokerTokenCachePort(KisTokenPersistenceAdapter가 구현) + MockSimulationDataPort(MockBrokerAdapter 전용 — AlpacaCalendarAdapter→MarketHolidayStorePort 패턴의 역방향 적용: 데이터를 필요로 하는 broker가 포트를 정의하고, 데이터를 가진 trading이 `trading.adapter.out.MockSimulationDataAdapter`로 구현) + Toss 전용 5개. BrokerConnectionTestPort: 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅 — verifyAccount→brokerAccountCode(KIS: null, Toss: accountSeq)
+ application/port/output/ ← 브로커 Capability 인터페이스(*Port) 총 16개 — 공통 7개(KIS/Toss/Mock 모두 구현) + BrokerAdapterPort(라우팅 마커) + BrokerConnectionTestPort(*AuthApi 클래스가 구현) + BrokerTokenCachePort(KisTokenPersistenceAdapter가 구현) + MockSimulationDataPort(MockBrokerAdapter 전용 — AlpacaCalendarAdapter→MarketHolidayStorePort 패턴의 역방향 적용: 데이터를 필요로 하는 broker가 포트를 정의하고, 데이터를 가진 trading이 `trading.adapter.out.MockSimulationDataAdapter`로 구현) + Toss 전용 5개. "port" NamedInterface로 공개. BrokerConnectionTestPort: 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅 — verifyAccount→brokerAccountCode(KIS: null, Toss: accountSeq)
```

domain/model 라인의 "domain" NamedInterface 서술도 확인 후 `+kis+toss` 병합 상태만 유지되면 그대로 둔다(이미 port.out 언급 없음).

"Spring Modulith 점진 도입" 단락에서 broker 서술 갱신:

```diff
- `broker`가 세 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain/model+domain/model.kis+domain/model.toss+domain/port/out)과 "application"(application/service) 두 NamedInterface만 공개 — adapter/out은 KIS/Toss/Mock 연동 구현 디테일이라 의도적으로 비공개).
+ `broker`가 세 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain/model+domain/model.kis+domain/model.toss)·"port"(application/port/output)·"application"(application/service) 3개 NamedInterface 공개 — adapter/out은 KIS/Toss/Mock 연동 구현 디테일이라 의도적으로 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 domain/port/out을 "domain"에 병합 공개했었다).
```

"BrokerAdapter Registry 패턴" 절의 FQN도 확인(`com.kista.broker.domain.port.out.BrokerAdapterPort` 등 언급 있으면 `application.port.output`로 교체) — grep으로 재확인:

```bash
grep -n "domain\.port\.out\.Broker\|domain/port/out" docs/agents/architecture.md
```
남은 항목 전부 위와 동일 패턴으로 교체.

- [ ] **Step 7: constraints.md 갱신**

`docs/agents/constraints.md`의 broker 관련 라인 갱신:

```diff
- 브로커 애그리게이트(KIS/Toss/Mock 연동)는 `com.kista.broker`로 이미 옮겨졌다 — 신규 KIS/Toss/Mock 코드는 `com.kista.broker.adapter.out.*` 안에 추가. finance/notify와 달리 이 adapter/out은 NamedInterface로 공개되지 않아 모듈 밖에서 완전히 접근 불가 — 레거시 코드가 새 기능을 호출해야 하면 adapter 패키지에 직접 접근하지 말고 `com.kista.broker.domain.port.out`에 신규 `*Port` 인터페이스를 만들어 노출한다("domain" NamedInterface로 공개)
+ 브로커 애그리게이트(KIS/Toss/Mock 연동)는 `com.kista.broker`로 이미 옮겨졌다 — 신규 KIS/Toss/Mock 코드는 `com.kista.broker.adapter.out.*` 안에 추가. finance/notify와 달리 이 adapter/out은 NamedInterface로 공개되지 않아 모듈 밖에서 완전히 접근 불가 — 레거시 코드가 새 기능을 호출해야 하면 adapter 패키지에 직접 접근하지 말고 `com.kista.broker.application.port.output`에 신규 `*Port` 인터페이스를 만들어 노출한다("port" NamedInterface로 공개)
```

```diff
- 참고: `com.kista.broker.adapter.out.kis.KisAuthApi` → `com.kista.broker.domain.port.out.BrokerTokenCachePort` → `com.kista.broker.adapter.out.persistence.KisTokenPersistenceAdapter`
+ 참고: `com.kista.broker.adapter.out.kis.KisAuthApi` → `com.kista.broker.application.port.output.BrokerTokenCachePort` → `com.kista.broker.adapter.out.persistence.KisTokenPersistenceAdapter`
```

```diff
- broker/notify 포트(broker는 `domain/port/out/*Port`, notify는 `application/port/output/*Port`)는 시그니처(파라미터·반환 타입)에 trading 타입을 직접 참조하지 않는다
+ broker/notify 포트(둘 다 `application/port/output/*Port`)는 시그니처(파라미터·반환 타입)에 trading 타입을 직접 참조하지 않는다
```

같은 섹션 아래 broker 자체 소유 타입 서술(`com.kista.broker.domain.model`)에 `domain/port/out` 경로 언급이 더 있는지 확인:

```bash
grep -n "broker\.domain\.port\|broker.*domain/port" docs/agents/constraints.md
```
남은 항목 전부 `application.port.output`/`application/port/output`으로 교체.

- [ ] **Step 8: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 보류 1번 항목 갱신:

```diff
- 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(`docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시·finance·notify 완료, 나머지 2개(broker/trading) 진행 예정
+ 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(`docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시·finance·notify·broker 완료, 나머지 1개(trading) 진행 예정
```

- [ ] **Step 9: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 결과 요약 라인(`BUILD SUCCESSFUL in Ns` 등)을 리포트에 그대로 포함할 것 — 요약 문장으로 대체하지 말 것(finance 청크 최종 리뷰에서 "증거 부족" Minor 지적을 받은 바 있음).

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/kista/broker/application/port/output/package-info.java \
        src/main/java/com/kista/broker/domain/model/package-info.java \
        src/main/java/com/kista/broker/package-info.java \
        docs/agents/architecture.md docs/agents/constraints.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git status --short
git commit -m "$(cat <<'EOF'
feat(modulith): broker 모듈 NamedInterface에 "port" 신설 + 문서 갱신

broker의 "domain" NamedInterface(domain.model+kis+toss+domain.port.out을
병합 공개하던 것)를 Task 1의 포트 이동에 맞춰 domain.model(+kis+toss)만
남도록 축소하고, 이동한 16개 포트를 위한 "port"(application.port.output)
NamedInterface를 신설했다. 기존 "application"(application.service)은
변경 없이 유지. 모듈 루트 package-info.java도 함께 갱신(finance 청크에서
이 파일이 태스크 목록 누락으로 최종 리뷰 fix wave가 필요했던 사례의 재발
방지). ModulithArchitectureTest·HexagonalArchitectureTest 모두 통과 확인.

architecture.md/constraints.md/상위 Modulith 스펙의 broker 관련 서술을 새
구조 기준으로 갱신.

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

이 broker 청크가 main에 병합되면, trading 청크(8개 포트 — `domain/port/in` 2개 TradingExecutionUseCase/VrReconfigureUseCase + `domain/port/out` 6개 CyclePositionInfiniteDetailPort/CyclePositionPort/OrderPort/StrategyCyclePort/StrategyCycleVrPort/TradingErrorReportPort. 기존 "domain"(model+strategy+port/in+port/out)이 model+strategy만으로 축소, "usecase"+"port" 신설, "event"·"schedule"은 변경 없음)이 5개 청크 중 마지막이다. trading 청크의 Task 2에도 모듈 루트 package-info.java 갱신을 반드시 파일 목록에 포함할 것.
