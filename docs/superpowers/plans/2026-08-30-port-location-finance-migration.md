# 포트 위치 전환 — finance 청크 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.finance.domain.port.{in,out}`(16개 포트 인터페이스)를 `com.kista.finance.application.usecase`/`com.kista.finance.application.port.output`로 이전하고, finance 모듈의 `"domain"` NamedInterface(현재 model+port.in+port.out 병합 공개)를 `"domain"`(model만)+`"usecase"`+`"port"` 3개로 재구성한다. 5개 청크 중 두 번째(레거시 완료, main 병합됨 commit `0ddddf52`).

**Architecture:** 레거시 청크와 동일한 sed 기법 재사용(finance 하위 두 패키지가 이번 이동으로 완전히 비므로 와일드카드+개별 import 모두 안전하게 일괄 치환 가능). 레거시보다 규모가 작아(16개 vs 59개, 소비자 46개 vs 178+113개) 2태스크로 충분: **Task 1**(16개 파일 이동 + 전역 import 정합화 + compile 검증), **Task 2**(NamedInterface 3개 재구성 + ArchUnit·Modulith 검증 + 문서 갱신 + 전체 테스트 스위트 최종 검증). 로직 변경 없음 — 패키지 위치·NamedInterface 선언만 바뀐다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-30-port-location-migration-design.md`

## Global Constraints

- 작업 위치: **새 worktree 생성 필요** — `superpowers:using-git-worktrees`(또는 `EnterWorktree`) 스킬로 브랜치 `worktree-port-location-finance-migration` 신규 생성 후 그 위에서 진행
- 레거시 청크에서 이미 `HexagonalArchitectureTest`의 두 규칙(`adapter.in` 비의존 범위, `*Port` 접미사 대상)을 `com.kista..application.service..`/`com.kista..application.port.output..`처럼 프로젝트 전역 와일드카드로 재작성해뒀다 — 이 청크는 포트만 옮기면 그 규칙이 자동으로 커버한다. **ArchUnit 테스트 파일은 이 청크에서 수정하지 않는다** (레거시 청크의 최종 리뷰에서 `*Port` 규칙이 `application.port.output..`와 `domain.port.out..` 둘 다 OR로 검사하도록 이미 확장돼 있음 — finance 이동 후 `domain.port.out..` 매칭 대상이 그만큼 줄어들 뿐, 규칙 자체는 무손상으로 계속 유효)
- 이 작업은 로직 변경이 없는 순수 위치 이동이므로, Task 1은 `compileJava`/`compileTestJava` 통과만 검증 기준으로 삼고 전체 테스트 스위트 실행은 Task 2 최종 1회로 미룬다(전역 CLAUDE.md "빌드/테스트 전체 스위트는 최종 1회만" 원칙)
- 커밋 메시지: 한글, Conventional Commit 접두사(`refactor:`/`docs:`), author `narafu <narafu@kakao.com>` 확인 필수
- `git push`는 사용자가 명시적으로 요청할 때만
- **리팩토링 기회 발견 시 임의 수정 금지, 즉시 사용자에게 보고**
- macOS(`darwin`) 기준 `sed -i ''` 문법 사용
- 파일 인코딩: 서브에이전트가 import 수정 시 BOM 삽입 주의(constraints.md "파일 인코딩 주의")

---

### Task 1: `finance/domain/port/{in,out}`(16개) → `finance/application/{usecase,port/output}` 이전 + 전역 import 정합화

**Files:**
- Move → `src/main/java/com/kista/finance/application/usecase/`: `AssetSnapshotUseCase.java, BulkFinanceRegisterUseCase.java, FinanceAccountUseCase.java, FinanceBudgetUseCase.java, FinanceCategoryUseCase.java, FinanceGroupUseCase.java, FinanceRegistrationReminderUseCase.java, FinanceTransactionUseCase.java, MonthlyClosingUseCase.java`(기존 `finance/domain/port/in/*` 9개, 전부 `*UseCase` 접미사)
- Move → `src/main/java/com/kista/finance/application/port/output/`: `AssetSnapshotPort.java, FinanceAccountPort.java, FinanceBudgetPort.java, FinanceCategoryPort.java, FinanceGroupPort.java, FinanceTransactionPort.java, MonthlyClosingPort.java`(기존 `finance/domain/port/out/*` 7개)
- Modify (import 경로 일괄 sed, 아래 Step 2): `com.kista.finance.domain.port.{in,out}.*`를 참조하는 전체 파일 — 착수 시점 기준 46개(finance 모듈 내부 컨트롤러/서비스/어댑터 대부분 + 레거시 `UserCascadeDeleter`/`UserServiceTest`/`UserCascadeDeleterTest` 3개 와일드카드 import 포함)

**Interfaces:**
- Produces: `com.kista.finance.application.usecase.*`(9개), `com.kista.finance.application.port.output.*`(7개) — Task 2에서 NamedInterface로 재공개

- [ ] **Step 0: 착수 직전 재확인**

```bash
find src/main/java/com/kista/finance/domain/port/in -name "*.java" ! -name "package-info.java" | wc -l
find src/main/java/com/kista/finance/domain/port/out -name "*.java" ! -name "package-info.java" | wc -l
```
Expected: `9`, `7`.

```bash
grep -rl "import com\.kista\.finance\.domain\.port\.\(in\|out\)\." src/main/java src/test/java | grep -v "^src/main/java/com/kista/finance/domain/port/" > /tmp/finance-port-consumers.txt
wc -l /tmp/finance-port-consumers.txt
grep -rln "import com\.kista\.finance\.domain\.port\.\(in\|out\)\.\*;" src/main/java src/test/java
```
Expected: 46 근처. 와일드카드 3개(`UserCascadeDeleter.java`, `UserServiceTest.java`, `UserCascadeDeleterTest.java`).

- [ ] **Step 1: 16개 파일 이동 + 패키지 선언 변경**

```bash
mkdir -p src/main/java/com/kista/finance/application/usecase
git mv src/main/java/com/kista/finance/domain/port/in/AssetSnapshotUseCase.java src/main/java/com/kista/finance/application/usecase/AssetSnapshotUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/BulkFinanceRegisterUseCase.java src/main/java/com/kista/finance/application/usecase/BulkFinanceRegisterUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/FinanceAccountUseCase.java src/main/java/com/kista/finance/application/usecase/FinanceAccountUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/FinanceBudgetUseCase.java src/main/java/com/kista/finance/application/usecase/FinanceBudgetUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/FinanceCategoryUseCase.java src/main/java/com/kista/finance/application/usecase/FinanceCategoryUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/FinanceGroupUseCase.java src/main/java/com/kista/finance/application/usecase/FinanceGroupUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/FinanceRegistrationReminderUseCase.java src/main/java/com/kista/finance/application/usecase/FinanceRegistrationReminderUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/FinanceTransactionUseCase.java src/main/java/com/kista/finance/application/usecase/FinanceTransactionUseCase.java
git mv src/main/java/com/kista/finance/domain/port/in/MonthlyClosingUseCase.java src/main/java/com/kista/finance/application/usecase/MonthlyClosingUseCase.java
rmdir src/main/java/com/kista/finance/domain/port/in
sed -i '' 's/^package com\.kista\.finance\.domain\.port\.in;/package com.kista.finance.application.usecase;/' src/main/java/com/kista/finance/application/usecase/*.java

mkdir -p src/main/java/com/kista/finance/application/port/output
git mv src/main/java/com/kista/finance/domain/port/out/AssetSnapshotPort.java src/main/java/com/kista/finance/application/port/output/AssetSnapshotPort.java
git mv src/main/java/com/kista/finance/domain/port/out/FinanceAccountPort.java src/main/java/com/kista/finance/application/port/output/FinanceAccountPort.java
git mv src/main/java/com/kista/finance/domain/port/out/FinanceBudgetPort.java src/main/java/com/kista/finance/application/port/output/FinanceBudgetPort.java
git mv src/main/java/com/kista/finance/domain/port/out/FinanceCategoryPort.java src/main/java/com/kista/finance/application/port/output/FinanceCategoryPort.java
git mv src/main/java/com/kista/finance/domain/port/out/FinanceGroupPort.java src/main/java/com/kista/finance/application/port/output/FinanceGroupPort.java
git mv src/main/java/com/kista/finance/domain/port/out/FinanceTransactionPort.java src/main/java/com/kista/finance/application/port/output/FinanceTransactionPort.java
git mv src/main/java/com/kista/finance/domain/port/out/MonthlyClosingPort.java src/main/java/com/kista/finance/application/port/output/MonthlyClosingPort.java
rmdir src/main/java/com/kista/finance/domain/port/out
rmdir src/main/java/com/kista/finance/domain/port
sed -i '' 's/^package com\.kista\.finance\.domain\.port\.out;/package com.kista.finance.application.port.output;/' src/main/java/com/kista/finance/application/port/output/*.java
```

- [ ] **Step 2: 전역 import 경로 일괄 치환**

`finance/domain/port/{in,out}`가 이번 이동으로 완전히 비므로, 와일드카드와 개별 import 모두 안전하게 일괄 치환 가능(레거시 청크 Task 1/2와 동일 기법).

```bash
find src/main/java src/test/java -name "*.java" -print0 | xargs -0 sed -i '' \
  -e 's/import com\.kista\.finance\.domain\.port\.in\.\*;/import com.kista.finance.application.usecase.*;/g' \
  -e 's/import com\.kista\.finance\.domain\.port\.in\.\([A-Za-z]*\);/import com.kista.finance.application.usecase.\1;/g' \
  -e 's/import com\.kista\.finance\.domain\.port\.out\.\*;/import com.kista.finance.application.port.output.*;/g' \
  -e 's/import com\.kista\.finance\.domain\.port\.out\.\([A-Za-z]*\);/import com.kista.finance.application.port.output.\1;/g'
```

- [ ] **Step 3: 잔여 old-path import 재스캔**

```bash
grep -rn "import com\.kista\.finance\.domain\.port\." src/main/java src/test/java
grep -rn "com\.kista\.finance\.domain\.port" src/main/java src/test/java
```
Expected: 둘 다 결과 없음. 남아있으면 해당 파일을 열어 수동 교정(중첩 타입 참조 등 `[A-Za-z]*` 패턴을 벗어난 경우).

- [ ] **Step 4: 이동 확인**

```bash
find src/main/java/com/kista/finance/application/usecase -name "*.java" | wc -l
find src/main/java/com/kista/finance/application/port/output -name "*.java" | wc -l
find src/main/java/com/kista/finance/domain/port -maxdepth 0 2>/dev/null
```
Expected: `9`, `7`, 세 번째 명령은 아무 출력 없음(디렉토리 없어짐).

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
refactor(port): finance 모듈 포트 16개를 domain/port에서 application/{usecase,port.output}으로 이전

finance/domain/port/in의 UseCase 인터페이스 9개를 finance/application/usecase로,
finance/domain/port/out의 *Port 인터페이스 7개를 finance/application/port/output으로
물리 이동. 이를 참조하던 finance 모듈 내부 46개 파일(레거시 UserCascadeDeleter 등
와일드카드 import 3건 포함)의 import 경로를 일괄 갱신 — 인터페이스 이름·시그니처·
로직 변경 없음, 패키지 위치만 이동. finance/domain/port 디렉토리 자체가 완전히
비어 함께 제거됨. NamedInterface 재구성은 Task 2에서 처리.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

### Task 2: NamedInterface 3개 재구성 + ArchUnit·Modulith 검증 + 문서 갱신 + 최종 검증

**Files:**
- Modify: `src/main/java/com/kista/finance/domain/model/package-info.java`
- Create: `src/main/java/com/kista/finance/application/usecase/package-info.java`
- Create: `src/main/java/com/kista/finance/application/port/output/package-info.java`
- Delete: `src/main/java/com/kista/finance/domain/port/in/package-info.java`, `src/main/java/com/kista/finance/domain/port/out/package-info.java`(Task 1에서 디렉토리째 이미 삭제됨 — 확인만)
- Modify: `docs/agents/architecture.md`, `docs/agents/constraints.md`, `docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`

**Interfaces:**
- Produces: finance 모듈의 `"domain"`(model만), `"usecase"`(application.usecase), `"port"`(application.port.output) 3개 NamedInterface — 다른 모듈이 `com.kista.finance.application.usecase.*`/`com.kista.finance.application.port.output.*`를 참조 가능해짐

- [ ] **Step 1: `domain/model/package-info.java` 갱신 — model만 남은 "domain" NamedInterface**

`src/main/java/com/kista/finance/domain/model/package-info.java`를 다음으로 교체:

```java
// finance 모듈의 공개 계약 일부 — 불변 값 객체(record). "domain" 이름으로 공개된다(포트는 Task 2 이후 별도 "usecase"/"port" NamedInterface로 분리 공개).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.finance.domain.model;
```

- [ ] **Step 2: `application/usecase/package-info.java` 신규 작성**

`src/main/java/com/kista/finance/application/usecase/package-info.java`:

```java
// finance 모듈의 공개 계약 일부 — UseCase 인터페이스(입력 포트). "usecase" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.finance.application.usecase;
```

- [ ] **Step 3: `application/port/output/package-info.java` 신규 작성**

`src/main/java/com/kista/finance/application/port/output/package-info.java`:

```java
// finance 모듈의 공개 계약 일부 — *Port 접미사 출력 포트 인터페이스. "port" 이름으로 공개된다.
@org.springframework.modulith.NamedInterface("port")
package com.kista.finance.application.port.output;
```

- [ ] **Step 4: `application/service/package-info.java` 존재 여부 확인**

finance는 `application/service`가 internal(비공개)이라 별도 NamedInterface가 없다 — 이 파일이 새로 생기지 않도록, 있다면 건드리지 않는다(원래도 없었을 가능성 높음, 확인만).

```bash
find src/main/java/com/kista/finance/application/service -maxdepth 1 -name "package-info.java"
```
Expected: 결과 없음(있다면 무엇이 선언돼 있는지 확인 후 보고, 수정하지 않음).

- [ ] **Step 5: `ModulithArchitectureTest` 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'
```
Expected: PASS. 실패하면 다른 모듈이 finance의 `domain.port.{in,out}`(이제 없는 경로)를 여전히 직접 타입으로 참조하는 코드가 있다는 뜻 — Task 1의 import 치환이 놓친 지점일 가능성. 위 스펙 단계에서 finance 포트를 참조하는 외부 코드는 레거시(OPEN 모듈)의 `UserCascadeDeleter` 계열뿐이라고 확인됐으므로, OPEN 모듈은 애초에 이 검증 대상이 아니라 나오면 안 된다.

- [ ] **Step 6: `HexagonalArchitectureTest` 회귀 확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest'
```
Expected: PASS — 레거시 청크에서 이미 `application.port.output..`(+`domain.port.out..`) 와일드카드로 일반화해뒀으므로 추가 수정 불필요. `*Port` 접미사 규칙이 finance의 7개 파일도 자동으로 검증 대상에 포함시키는지 확인(실패하면 나머지 6개 파일명이 `*Port`로 끝나는지 재확인 — 스펙 단계에서 이미 확인됨, 나오면 안 됨).

- [ ] **Step 7: architecture.md finance 섹션 갱신**

`docs/agents/architecture.md`에서 finance 모듈 블록(`com.kista.finance/` 아래) 중 다음 두 라인을 찾아 갱신:

```diff
- domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — domain/port/{in,out}와 함께 "domain" NamedInterface로 병합 공개
- domain/port/in/    ← UseCase 인터페이스, domain/port/out/ ← *Port 접미사
+ domain/model/      ← AssetSnapshot/FinanceAccount/FinanceBudget/FinanceCategory/FinanceGroup/FinanceTransaction/MonthlyClosing 등 record + Command — "domain" NamedInterface로 공개
```

바로 아래 `application/service/` 라인 앞에 신규 라인 추가:

```
application/usecase/  ← UseCase 인터페이스(9개, 옛 domain/port/in에서 이전) — "usecase" NamedInterface로 공개
application/port/output/ ← *Port 접미사 포트(7개, 옛 domain/port/out에서 이전) — "port" NamedInterface로 공개
```

"Spring Modulith 점진 도입" 단락에서 `finance`가 첫 이전 모듈이다(...) 서술 부분도 갱신:

```diff
- `finance`가 첫 이전 모듈이다(`@ApplicationModule` CLOSED, `domain` 레이어만 `@NamedInterface("domain")`으로 공개).
+ `finance`가 첫 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개 — application.service·adapter는 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 "domain" 하나에 model+port를 병합 공개했었다).
```

- [ ] **Step 8: constraints.md 갱신**

`docs/agents/constraints.md`의 "Spring Modulith 이전 중 신규 파일 배치" 섹션에서 finance 관련 첫 불릿을 교체:

```diff
- - 진행 중인 Modulith 이전으로 finance 애그리게이트는 `com.kista.finance`로 이미 옮겨졌다 — 신규 finance 관련 코드는 레거시 `com.kista.domain`/`com.kista.application`/`com.kista.adapter`가 아닌 `com.kista.finance` 안에 추가하고, 내부 `domain/{model,port/in,port/out}` + `application/service` + `adapter/{in,out}` 서브구조를 그대로 따른다 (→ architecture.md "Spring Modulith 점진 도입")
+ - 진행 중인 Modulith 이전으로 finance 애그리게이트는 `com.kista.finance`로 이미 옮겨졌다 — 신규 finance 관련 코드는 레거시 `com.kista.domain`/`com.kista.application`/`com.kista.adapter`가 아닌 `com.kista.finance` 안에 추가하고, 내부 `domain/model` + `application/{usecase,port/output,service}` + `adapter/{in,out}` 서브구조를 그대로 따른다 — 포트는 `domain/port/{in,out}`이 아닌 `application/{usecase,port/output}`에 위치 (→ architecture.md "Spring Modulith 점진 도입", constraints.md "포트 인터페이스 위치 규칙")
```

- [ ] **Step 9: 상위 스펙 문서 진행 상태 갱신**

`docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md`의 보류 1번 항목 갱신:

```diff
- 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(레거시 청크 완료, `docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시 완료, 나머지 4개 진행 예정
+ 1. 포트 위치를 domain → application(`usecase`/`port/output`)으로 전환. constraints.md "도메인 포트 인터페이스와 타입 위치 규칙" 개정 필요 — **착수함**(`docs/superpowers/specs/2026-08-30-port-location-migration-design.md` 참고). 레거시→finance→notify→broker→trading 5개 청크 중 레거시·finance 완료, 나머지 3개(notify/broker/trading) 진행 예정
```

- [ ] **Step 10: 최종 전체 테스트 스위트 1회 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 테스트 특정 후 수정.

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/kista/finance/domain/model/package-info.java \
        src/main/java/com/kista/finance/application/usecase/package-info.java \
        src/main/java/com/kista/finance/application/port/output/package-info.java \
        docs/agents/architecture.md docs/agents/constraints.md \
        docs/superpowers/specs/2026-08-27-spring-modulith-migration-design.md
git status --short
git commit -m "$(cat <<'EOF'
feat(modulith): finance 모듈 NamedInterface를 domain/usecase/port 3개로 재구성 + 문서 갱신

finance의 "domain" NamedInterface가 domain.model+domain.port.{in,out}을 병합
공개하던 것을, Task 1에서 포트가 application 하위로 이동함에 따라 "domain"(model만)·
"usecase"(application.usecase)·"port"(application.port.output) 3개로 분리 재선언.
ModulithArchitectureTest·HexagonalArchitectureTest 모두 통과 확인.

architecture.md/constraints.md/상위 Modulith 스펙의 finance 관련 서술을 새 구조
기준으로 갱신.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01D1GHxCK7iaGfxVVb6zQvY5
EOF
)"
```

---

## 리팩토링 관찰 체크포인트 (모든 태스크 공통)

각 태스크 실행 중 스펙에 없는 개선 지점을 발견하면:
1. **임의로 고치지 않는다** — 이 계획의 스코프 밖 변경은 사용자 승인 필요
2. 발견 즉시 사용자에게 짧게 보고

## 다음 청크

이 finance 청크가 main에 병합되면, notify 청크(4개 포트, `domain/port/out`만 — port/in 없어 `"usecase"` NamedInterface 불필요) 계획을 `writing-plans`로 새로 작성한다.
