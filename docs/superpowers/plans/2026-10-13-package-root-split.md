# Package Root Split (B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:api`·`:trading-core`·`:shared` 세 서브프로젝트에 고유 루트 패키지(`com.kista.api`·`com.kista.tradingcore`·`com.kista.shared`)를 주고, 공유 루트 때문에 생긴 스캔 목록·앱셸 우회 코드를 Spring Boot 기본 동작으로 대체한다.

**Architecture:** 서브프로젝트 단위로 `git mv` + 스크립트 치환(기계적 커밋)과 설정 정리(수작업 커밋)를 번갈아 수행한다. 중간 커밋에서도 Modulith 검증이 살아 있도록 Task 0에서 모듈 ID를 `@ApplicationModule(id=...)`로 고정하고 감지 전략을 `explicitly-annotated`로 바꾼다. 마지막에 프로세스별 Modulith 모델 2개(`@Modulithic(additionalPackages = "com.kista.shared")`)로 전환한다.

**Tech Stack:** Java 25, Gradle 9.8.1, Spring Boot 4.1.1, Spring Modulith 2.1.1, ArchUnit, JUnit 5, bash + perl.

**Spec:** `docs/superpowers/specs/2026-10-13-package-root-split-design.md` — 실행자는 spec과 이 계획을 함께 읽는다.

## Global Constraints

- 브랜치 `refactor/package-root-split` 하나에서 진행. push·배포는 사용자가 요청할 때만.
- 커밋 author `narafu <narafu@kakao.com>`, 메시지는 한글 + Conventional Commit 접두사, 끝에 아래 두 줄:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9
  ```
- 매 커밋은 `./gradlew compileJava compileTestJava` 통과 + 해당 시점의 아키텍처 테스트 통과.
- 기계적 커밋(스크립트 결과만)과 수작업 커밋을 섞지 않는다. 기계적 커밋 메시지 접두사 `refactor(package):`, 수작업 `refactor(boot):`/`test(architecture):`/`docs:` 등.
- 치환 후 BOM 확인: `git grep -l $'\xef\xbb\xbf' -- '*.java'` 결과가 비어야 한다(`.claude/rules/java-encoding.md`).
- verbose 빌드 출력은 `2>&1 | grep -E "FAILED|BUILD|ERROR|error:|tests completed"`로 걸러 받는다.
- 전체 `test`·`integration`은 Task 9에서 1회만. 중간에는 좁힌 `--tests` 필터만.
- 서브프로젝트 `--tests` 필터는 반드시 `:test`/`:trading-core:test` 접두사를 붙인다(접두사 없으면 `:shared`가 "No tests found"로 실패).
- 이미 적용된 Flyway 마이그레이션 파일은 수정 금지(이번 작업은 마이그레이션을 건드리지 않는다).
- 최종 패키지·모듈 ID(spec 4절):

  | 서브프로젝트 | 패키지 | 최종 모듈 ID |
  |---|---|---|
  | `:shared` | `com.kista.shared.{kernel,contract,platform}` | `kernel`, `contract`, `platform` |
  | `:trading-core` | `com.kista.tradingcore.{trading,matching,broker,account,privacy,marketcalendar,stats,notify,web}` | 같은 이름 |
  | `:api` | `com.kista.api.{admin,finance,market,notify,benchmark,user,web}` | 같은 이름 |
  | 앱 클래스 | `com.kista.api.ApiApplication`, `com.kista.tradingcore.TradingApplication` | — |

## Review Focus

1. **슬라이스 테스트의 빈 구성 변화** — shared 빈이 컴포넌트 스캔에서 자동구성으로 넘어가면 `@WebMvcTest`가 지금까지 스캔으로 받던 `JwtAuthFilter`·`InternalTokenAuthFilter`를 잃는다. 401/403 기대 테스트가 바뀐 이유 없이 통과·실패하면 안 된다 → Task 2 Step 6에서 컨트롤러 테스트 전부 실행.
2. **Boot 기본 빈 중복** — 자동구성 순서가 Boot의 `@ConditionalOnMissingBean`(기본 `SecurityFilterChain`, `JwtDecoder`)보다 늦으면 기본 필터 체인이 하나 더 등록돼 인증이 우회될 수 있다. 부팅은 성공하므로 눈에 안 띈다 → Task 0에서 컨텍스트 로드 테스트에 `SecurityFilterChain` 빈 개수 단언, Task 2에서 빈 이름 전후 diff.
3. **조용한 검증 누락** — Modulith는 모듈이 빠져도 통과하고, ArchUnit은 `failOnEmptyShould=false`라 낡은 패키지 문자열에도 통과한다 → Task 0에서 모듈 ID 집합 단언 + `failOnEmptyShould=true`.
4. **JPA 리포지토리 누락** — `JpaRepositoryConfig`/`RootJpaScanConfig` 삭제 후 리포지토리가 등록되지 않으면 슬라이스 테스트는 통과하고 부팅만 깨진다 → Task 0에서 두 앱의 컨텍스트 로드 테스트에 리포지토리 수 단언(root 21, trading 16 — Task 0에서 실측 확정).
5. **접두사 충돌 치환** — `trading`/`tradingstats`/`tradingnotify`/`tradingweb`, `market`/`marketcalendar`. 잘못 치환되면 컴파일은 실패하지만 문자열(ArchUnit 패턴·`flows.yml`)은 조용히 틀린다 → 매 기계적 Task의 잔여 grep + Task 7 문자열 검토.

## 공용 스크립트

### `pkgmove.sh`

실행자는 아래 원문을 scratchpad(`$SCRATCH/pkgmove.sh`)에 저장해 쓴다. 레포에는 커밋하지 않는다.

```bash
#!/usr/bin/env bash
# pkgmove.sh <shared|tradingcore|api|docs> — 패키지 git mv + 참조 치환 (Package root split B)
# 이동표의 항목은 "옛FQN 새FQN". 대문자로 시작하는 마지막 세그먼트는 클래스(파일 이동), 그 외는 패키지(디렉토리 이동).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
phase=${1:?phase}
ROOTS=(. trading-core shared)
SETS=(main test testFixtures)

map_for() {
  case "$1" in
    shared) printf '%s\n' \
      "com.kista.sharedkernel com.kista.shared.kernel" \
      "com.kista.contract com.kista.shared.contract" \
      "com.kista.platform com.kista.shared.platform" ;;
    tradingcore) printf '%s\n' \
      "com.kista.support.TradingFixtures com.kista.tradingcore.support.TradingFixtures" \
      "com.kista.support.TradingCoreJpaTestConfig com.kista.tradingcore.support.TradingCoreJpaTestConfig" \
      "com.kista.support.StubBrokerApiException com.kista.tradingcore.support.StubBrokerApiException" \
      "com.kista.architecture.TradingCoreTestVendorModelTest com.kista.tradingcore.architecture.TradingCoreTestVendorModelTest" \
      "com.kista.tradingstats com.kista.tradingcore.stats" \
      "com.kista.tradingnotify com.kista.tradingcore.notify" \
      "com.kista.tradingweb com.kista.tradingcore.web" \
      "com.kista.trading com.kista.tradingcore.trading" \
      "com.kista.matching com.kista.tradingcore.matching" \
      "com.kista.broker com.kista.tradingcore.broker" \
      "com.kista.account com.kista.tradingcore.account" \
      "com.kista.privacy com.kista.tradingcore.privacy" \
      "com.kista.marketcalendar com.kista.tradingcore.marketcalendar" ;;
    api) printf '%s\n' \
      "com.kista.KistaApplication com.kista.api.ApiApplication" \
      "com.kista.ApplicationContextLoadTest com.kista.api.ApplicationContextLoadTest" \
      "com.kista.SchedulerDisabledContextTest com.kista.api.SchedulerDisabledContextTest" \
      "com.kista.SchedulerRoleConfigTest com.kista.api.SchedulerRoleConfigTest" \
      "com.kista.support.DomainFixtures com.kista.api.support.DomainFixtures" \
      "com.kista.architecture com.kista.api.architecture" \
      "com.kista.admin com.kista.api.admin" \
      "com.kista.finance com.kista.api.finance" \
      "com.kista.marketcalendar com.kista.marketcalendar" \
      "com.kista.market com.kista.api.market" \
      "com.kista.notify com.kista.api.notify" \
      "com.kista.benchmark com.kista.api.benchmark" \
      "com.kista.user com.kista.api.user" \
      "com.kista.web com.kista.api.web" ;;
  esac
}

is_class() { [[ ${1##*.} =~ ^[A-Z] ]]; }

if [[ $phase == docs ]]; then
  MAP=$( { map_for shared; map_for tradingcore; map_for api; } )
  FILES=$(git ls-files -- '*.md' ':!docs/superpowers/**' ':!docs/agents/modulith-migration-history.md')
else
  MAP=$(map_for "$phase")
  # 1) 이동 — 세 서브프로젝트 × main/test/testFixtures 전부
  while read -r old new; do
    [[ $old == "$new" ]] && continue
    for r in "${ROOTS[@]}"; do for s in "${SETS[@]}"; do
      base="$r/src/$s/java"
      if is_class "$old"; then
        src="$base/${old//.//}.java"; dst="$base/${new//.//}.java"
        [[ -f $src ]] || continue
        mkdir -p "$(dirname "$dst")"; git mv "$src" "$dst"
        perl -pi -e "s/^package [\\w.]+;/package ${new%.*};/" "$dst"
      else
        src="$base/${old//.//}"; dst="$base/${new//.//}"
        [[ -d $src ]] || continue
        mkdir -p "$(dirname "$dst")"; git mv "$src" "$dst"
      fi
    done; done
  done <<<"$MAP"
  FILES=$(git ls-files -- '*.java' '*.kts' '*.yml' '*.yaml' '*.properties' '*.imports' '*.factories' '*.sh' '*.js' '*.html' \
            ':!.github/tests/**' ':!deploy/hooks/**' ':!docs/**')
fi

# 2) 치환 — 점 표기와 슬래시 표기 모두, 세그먼트 경계 정확 매칭(접두사 충돌 방지). 이동표 순서대로(클래스 항목이 먼저)
while read -r old new; do
  [[ $old == "$new" ]] && continue
  o=${old//./\\.}; os=${old//.//}; ns=${new//.//}
  os=${os//\//\\/}; ns=${ns//\//\\/}
  # shellcheck disable=SC2086
  printf '%s\n' $FILES | xargs perl -pi -e "s/\\b${o}(?![\\w])/${new}/g; s/\\b${os}(?![\\w])/${ns}/g"
done <<<"$MAP"

# 3) 클래스 개명(단순 이름이 바뀌는 항목)
if [[ $phase == api || $phase == docs ]]; then
  printf '%s\n' $FILES | xargs perl -pi -e 's/\bKistaApplication\b/ApiApplication/g'
fi
echo "pkgmove $phase done"
```

주의:
- `api` 이동표의 `"com.kista.marketcalendar com.kista.marketcalendar"`는 no-op 가드다(이 시점엔 이미 `tradingcore.marketcalendar`라 매칭되지 않지만, `market` 규칙이 `marketcalendar`를 건드리지 않음을 명시). `(?![\w])` 부정 전방탐색이 실제 방어선이다.
- `tradingcore` 단계에서 `com.kista.tradingweb.TradingApplication`은 `com.kista.tradingcore.web.TradingApplication`이 된다. 루트로 옮기는 것은 import 추가가 필요해 Task 4(수작업)에서 한다.
- `docs` 단계는 세 이동표를 합쳐 `*.md`에 적용한다(역사 문서 `docs/superpowers/**`, `modulith-migration-history.md` 제외).

### `fix-claude-md.py` (Task 8에서 사용)

```python
#!/usr/bin/env python3
# 모듈 디렉토리 CLAUDE.md의 @상대경로 import를 현재 위치 기준으로 재계산한다
import os, re, subprocess, sys
root = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
os.chdir(root)
# 모듈 패키지 경로 → 문서 경로
DOCS = {
    "com/kista/api/": "docs/agents/modules/api/",
    "com/kista/tradingcore/": "docs/agents/modules/tradingcore/",
    "com/kista/shared/": "docs/agents/modules/shared/",
}
files = subprocess.check_output(["git", "ls-files", "*/CLAUDE.md"], text=True).split()
for f in files:
    if "/src/main/java/com/kista/" not in f:
        continue
    d = os.path.dirname(f)
    module = os.path.basename(d)
    target = next(v for k, v in DOCS.items() if k in f + "/") + module + ".md"
    if not os.path.exists(target):
        sys.exit(f"missing doc: {target} for {f}")
    rel = os.path.relpath(target, d)
    text = open(f, encoding="utf-8").read()
    new = re.sub(r"^@\S+\.md$", "@" + rel, text, flags=re.M)
    open(f, "w", encoding="utf-8").write(new)
    print(f, "->", rel)
```

---

## Task 0: 검증 기준선 고정

전 단계가 "같은 테스트 수"로는 못 잡는 누락을 잡도록, 이동 전에 영구 단언을 추가한다.

**Files:**
- Modify: 19개 `package-info.java` (`src/main/java/com/kista/*/package-info.java`, `trading-core/src/main/java/com/kista/*/package-info.java`, `shared/src/main/java/com/kista/*/package-info.java` 중 `@ApplicationModule`이 붙은 것)
- Modify: `src/main/resources/application.yml`, `trading-core/src/main/resources/application.yml`
- Modify: `src/test/java/com/kista/architecture/ModulithArchitectureTest.java`
- Modify: `src/test/java/com/kista/architecture/ArchitectureMapTest.java:38`
- Modify: `src/test/resources/archunit.properties`
- Create: `trading-core/src/test/resources/archunit.properties`
- Modify: `src/test/java/com/kista/ApplicationContextLoadTest.java`
- Create: `trading-core/src/test/java/com/kista/tradingweb/TradingApplicationContextLoadTest.java`
- Create: `docs/superpowers/plans/2026-10-13-package-root-split-baseline.md`

**Interfaces:**
- Produces: 모듈 ID 집합 단언(후속 Task 6이 프로세스별로 갱신), `ApplicationModules.of("com.kista")` 진입점(Task 1~5 동안 유지), 컨텍스트 로드 테스트의 리포지토리 수·`SecurityFilterChain` 수·trading-core 빈 누출 단언.

- [ ] **Step 1: 현재 아키텍처 테스트 기준선 기록**

```bash
./gradlew :test --tests 'com.kista.architecture.*' :trading-core:test --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
for d in build/test-results/test trading-core/build/test-results/test; do
  echo "$d: $(grep -ho 'tests="[0-9]*"' $d/TEST-com.kista.architecture.*.xml | grep -o '[0-9]*' | paste -sd+ | bc)"
done
```
Expected: BUILD SUCCESSFUL. 두 숫자를 baseline 파일에 기록.

- [ ] **Step 2: 모듈 ID 집합 단언 테스트를 먼저 작성 (현행 감지 전략 상태에서 실행)**

`ModulithArchitectureTest.verifyModularStructure` 본문을 아래로 바꾼다.

```java
    @Test
    @DisplayName("모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        // 패키지 이동 중에도 모델 루트가 흔들리지 않도록 앱 클래스가 아닌 루트 패키지로 고정 — 프로세스별 모델 전환은 패키지 이동 완료 후
        var modules = ApplicationModules.of("com.kista").verify();

        // 모듈이 조용히 빠지거나 합쳐지는 것을 막는 기준선(19개)
        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("account", "admin", "benchmark", "broker", "contract", "finance", "market",
                        "marketcalendar", "matching", "notify", "platform", "privacy", "sharedkernel", "trading",
                        "tradingnotify", "tradingstats", "tradingweb", "user", "web");

        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
```
import 추가: `import static org.assertj.core.api.Assertions.assertThat;`, `KistaApplication` import 제거.

Run: `./gradlew :test --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | grep -E "FAILED|BUILD|Expecting|but"`
Expected: FAIL — 실제 집합에 `support`(testFixtures)가 끼어 있거나 정확히 19개면 PASS. 결과(실제 ID 목록)를 baseline 파일에 기록. `support` 외의 차이가 있으면 멈추고 보고.

- [ ] **Step 3: 모듈 ID 고정 + 감지 전략 전환**

19개 `package-info.java`의 `@ApplicationModule`에 현재 이름 그대로 `id`를 추가한다. 형태 예:

```java
@org.springframework.modulith.ApplicationModule(id = "trading")
package com.kista.trading;
```
```java
@org.springframework.modulith.ApplicationModule(
    id = "sharedkernel",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.sharedkernel;
```
대상 목록 확인: `git grep -l '@org.springframework.modulith.ApplicationModule' -- '*package-info.java'` (정확히 19개여야 한다).

두 `application.yml`의 기존 `spring.modulith:` 블록에 추가:
```yaml
  modulith:
    detection-strategy: explicitly-annotated   # @ApplicationModule(id) 붙은 패키지만 모듈 — 패키지 깊이와 무관하게 ID 고정
```

Run: `./gradlew :test --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: PASS (19개, `support` 사라짐).

- [ ] **Step 4: `ArchitectureMapTest`도 같은 진입점으로**

`ArchitectureMapTest.java:38`:
```java
        var modules = ModuleGraphExporter.graph(ApplicationModules.of("com.kista"));
```
`KistaApplication` import 제거.

Run: `./gradlew :test --tests 'com.kista.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: PASS.

- [ ] **Step 5: ArchUnit 빈 규칙 차단**

`src/test/resources/archunit.properties`:
```properties
archRule.failOnEmptyShould=true
```
`trading-core/src/test/resources/archunit.properties` 같은 내용으로 생성.

Run: `./gradlew :test --tests 'com.kista.architecture.*' :trading-core:test --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD|failed to check any classes"`
Expected: PASS가 목표. 실패하는 규칙이 있으면 규칙별로 판정해 고친다:
- 대상 패키지 문자열이 틀림(예: 이미 없어진 패키지) → 실제 패키지로 수정
- 규칙이 원래 공허함(대상 클래스가 생길 수 있는 정상 규칙, 예: `adapter.out.sse` 계층 규칙) → 해당 규칙에만 `.allowEmptyShould(true)` + `// 대상이 없을 수 있는 예방 규칙` 주석
각 판정을 baseline 파일에 한 줄씩 기록.

- [ ] **Step 6: 컨텍스트 로드 테스트에 빈 구성 단언 (root)**

`src/test/java/com/kista/ApplicationContextLoadTest.java`의 테스트 메서드 뒤에 추가:

```java
    @Test
    void 루트_컨텍스트는_JPA_리포지토리_전부와_보안_필터체인_하나만_갖고_trading_core_빈은_없다() {
        // 리포지토리 스캔 범위 회귀 가드 — RootJpaScanConfig 제거 후에도 같은 수여야 한다
        assertThat(context.getBeanNamesForType(JpaRepository.class)).hasSize(ROOT_JPA_REPOSITORIES);
        // shared 자동구성 순서 회귀 가드 — Boot 기본 필터 체인이 함께 등록되면 2개가 된다
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
        // root 테스트 classpath에 trading-core가 있어도 컨텍스트에 섞이지 않는다
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(Objects::nonNull)
                .map(Class::getPackageName)
                .filter(p -> TRADING_CORE_PACKAGES.stream().anyMatch(p::startsWith)))
                .isEmpty();
    }

    private static final int ROOT_JPA_REPOSITORIES = 21; // Step 6 실행 결과로 확정
    // trading-core 소유 패키지 — 패키지 이동 후 com.kista.tradingcore 하나로 줄어든다
    private static final List<String> TRADING_CORE_PACKAGES = List.of(
            "com.kista.trading.", "com.kista.tradingstats.", "com.kista.tradingnotify.", "com.kista.tradingweb.",
            "com.kista.matching.", "com.kista.broker.", "com.kista.account.", "com.kista.privacy.",
            "com.kista.marketcalendar.");
```
import: `org.springframework.data.jpa.repository.JpaRepository`, `org.springframework.security.web.SecurityFilterChain`, `java.util.Arrays`, `java.util.List`, `java.util.Objects`.

`ROOT_JPA_REPOSITORIES` 교차 확인: `grep -rhE 'interface \w+ extends JpaRepository' src/main --include='*.java' | wc -l`. 테스트가 다른 수를 보고하면 grep 수와 대조해 원인을 확인한 뒤 실측값으로 고정.

Run (postgres·redis 기동 필요: `docker compose up -d postgres redis`): `./gradlew :test --tests 'com.kista.ApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD|Expected|but"`
Expected: PASS.

- [ ] **Step 7: trading-core 컨텍스트 로드 테스트 신설**

`trading-core/src/test/java/com/kista/tradingweb/TradingApplicationContextLoadTest.java`:

```java
package com.kista.tradingweb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// kista-trading 전체 부팅 스모크 — trading-core에는 슬라이스 테스트만 있어 JPA 리포지토리 미등록 같은 부팅 결함을
// 로컬 2-프로세스 부팅 전까지 못 잡았다(JpaRepositoryConfig 도입 경위). 스캔 설정 변경의 회귀 가드
@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 컨텍스트 로드
class TradingApplicationContextLoadTest {

    private static final int TRADING_JPA_REPOSITORIES = 16; // Step 7 실행 결과로 확정

    @Autowired
    private ApplicationContext context; // 컨텍스트 로드 성공 자체가 1차 검증

    @Test
    void 트레이딩_컨텍스트는_JPA_리포지토리_전부와_보안_필터체인_하나를_갖는다() {
        assertThat(context.getBeanNamesForType(JpaRepository.class)).hasSize(TRADING_JPA_REPOSITORIES);
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
    }
}
```
교차 확인: `grep -rhE 'interface \w+ extends JpaRepository' trading-core/src/main --include='*.java' | wc -l`.

컨텍스트가 필수 프로퍼티 누락으로 실패하면 누락 키만 `@SpringBootTest(properties = {"키=테스트값", ...})`로 채운다(`application-test.yml`은 root와 공유되므로 trading 전용 값을 넣지 않는다). 외부 호출이 부팅 중 일어나 실패하면 멈추고 보고.

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingweb.TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD|Expected|but|Caused by"`
Expected: PASS.

- [ ] **Step 8: baseline 파일 작성**

`docs/superpowers/plans/2026-10-13-package-root-split-baseline.md`:
```markdown
# Package root split — 기준선 (Task 0)
- 아키텍처 테스트 수: root <N>, trading-core <M>  (Step 1)
- Task 0 이후 아키텍처 테스트 수: root <N'>, trading-core <M'>
- Modulith 모듈 ID(전략 전환 전): <Step 2 실제 목록>
- failOnEmptyShould 판정: <규칙명 — 수정/allowEmptyShould 사유>
- JPA 리포지토리: root 21, trading 16 (grep·테스트 일치)
- integration 기대 수: root 14, trading-core 5
```

- [ ] **Step 9: 검토 후 커밋**

리뷰어 검수(SDD 태스크 리뷰) 통과 후:
```bash
git add -A && git commit -m "test(architecture): 패키지 루트 분리 전 검증 기준선을 고정한다

모듈 ID를 @ApplicationModule(id)로 고정하고 감지 전략을 explicitly-annotated로 바꿔
패키지 깊이가 변해도 Modulith 검증이 유지되게 한다. ArchUnit 빈 규칙 통과를 막고,
두 앱 컨텍스트에 JPA 리포지토리 수·보안 필터체인 수 단언을 추가한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
```

---

## Task 1: `:shared` 패키지 이동 (기계적)

**Files:**
- Move: `shared/src/{main,test}/java/com/kista/{sharedkernel,contract,platform}` → `.../com/kista/shared/{kernel,contract,platform}`
- Move: `trading-core/src/test/java/com/kista/platform` → `.../com/kista/shared/platform` (SchedulerLockService DB 테스트 2개 — 같은 패키지 접근이 필요해 trading-core에 남는다. Task 7 경계 규칙의 명시 예외)
- Modify(치환): 레포 전체 `*.java`/`*.kts`/`*.yml`/`*.properties`/`*.sh`/`*.js`/`*.html`

**Interfaces:**
- Consumes: Task 0의 `ApplicationModules.of("com.kista")` + 고정 ID.
- Produces: `com.kista.shared.kernel.*`, `com.kista.shared.contract.*`, `com.kista.shared.platform.*` FQCN.

- [ ] **Step 1: 스크립트 실행**

```bash
bash "$SCRATCH/pkgmove.sh" shared
```
Expected: `pkgmove shared done`.

- [ ] **Step 2: 잔여 참조 0건 확인**

```bash
git grep -nE 'com[./]kista[./](sharedkernel|contract|platform)\b' -- ':!docs/**' ':!*.md' ':!.github/tests/**' ':!deploy/hooks/**'
git grep -l $'\xef\xbb\xbf' -- '*.java'
```
Expected: 두 명령 모두 출력 없음.

- [ ] **Step 3: 컴파일 + 아키텍처 테스트**

```bash
./gradlew compileJava compileTestJava :test --tests 'com.kista.architecture.*' :trading-core:test --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD|error:"
```
Expected: BUILD SUCCESSFUL. 테스트 수가 baseline(Task 0 이후 값)과 같은지 Task 0 Step 1의 집계 명령으로 확인.

- [ ] **Step 4: 커밋 (검토 후)**

```bash
git add -A && git commit -m "refactor(package): :shared를 com.kista.shared 루트로 옮긴다

sharedkernel→shared.kernel, contract→shared.contract, platform→shared.platform.
git mv + 스크립트 치환만 포함.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
git show --stat HEAD | grep -c '=>'   # rename 감지 확인 — 0이면 멈추고 보고
```

---

## Task 2: `:shared` 자동구성 + 테스트 지원 이전 (수작업)

**Files:**
- Create: `shared/src/main/java/com/kista/shared/platform/PlatformAutoConfiguration.java`
- Create: `shared/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `src/main/java/com/kista/KistaApplication.java` (scanBasePackages에서 `com.kista.shared.*` 제거)
- Modify: `src/main/java/com/kista/web/RootJpaScanConfig.java` (같은 항목 제거)
- Modify: `trading-core/src/main/java/com/kista/tradingweb/TradingApplication.java` (scanBasePackages·@ConfigurationPropertiesScan에서 `com.kista.shared.*` 제거)
- Move: `trading-core/src/testFixtures/java/com/kista/support/{DataJpaTestBase,WebMvcTestSupport}.java` → `shared/src/testFixtures/java/com/kista/shared/support/`
- Move: `trading-core/src/testFixtures/resources/application-test.yml` → `shared/src/testFixtures/resources/application-test.yml`
- Modify: `shared/build.gradle.kts`, `trading-core/build.gradle.kts`, `build.gradle.kts`
- Modify(import): `com.kista.support.DataJpaTestBase`/`WebMvcTestSupport` 사용처 약 70개
- Test: 컨트롤러 슬라이스 테스트 전부, 두 컨텍스트 로드 테스트

**Interfaces:**
- Consumes: Task 1의 `com.kista.shared.platform.*` 클래스.
- Produces: `com.kista.shared.platform.PlatformAutoConfiguration`(앱은 shared를 스캔하지 않는다), `com.kista.shared.support.DataJpaTestBase`, `com.kista.shared.support.WebMvcTestSupport`, `testFixtures(project(":shared"))`.

- [ ] **Step 1: 변경 전 빈 이름 스냅샷**

`ApplicationContextLoadTest`와 `TradingApplicationContextLoadTest`의 첫 테스트 메서드에 임시로 추가(Step 9에서 제거):
```java
        java.nio.file.Files.write(java.nio.file.Path.of("build/beans-before.txt"),
                java.util.Arrays.stream(context.getBeanDefinitionNames()).sorted().toList());
```
Run: `./gradlew :test --tests 'com.kista.ApplicationContextLoadTest' :trading-core:test --tests 'com.kista.tradingweb.TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: PASS, `build/beans-before.txt`, `trading-core/build/beans-before.txt`(trading-core test `workingDir`가 루트라면 루트 `build/`에 덮어쓴다 — 그 경우 파일명을 `beans-before-trading.txt`로 구분).

- [ ] **Step 2: 등록 대상 목록 확정**

```bash
git grep -lE '@(Configuration|Component|Service|ConfigurationProperties|EnableConfigurationProperties)\b' -- 'shared/src/main/**/*.java'
git grep -nE '^(public )?(final )?(abstract )?class|^record' -- 'shared/src/main/java/com/kista/shared/platform/**/*.java' | grep -vE 'public'
```
첫 목록이 자동구성에 넣을 대상이다(2026-10-13 기준: `MetricsConfig`, `AccountNoHasher`, `AesCryptoService`, `SchedulerJobRunner`, `SchedulerLockService`, `SecurityConfig`, `JwtDecoderConfig`, `InternalTokenAuthFilter`, `JwtAuthFilter`, `RedisPubSubConfig`, `TelegramProperties`, `TelegramConfig`, `JpaAuditingConfig`, `InternalApiProperties`, `InternalApiClientConfig`). 대상 중 package-private 클래스는 `@Import`가 다른 패키지에서 참조할 수 없으므로 `public`으로 올린다(platform은 OPEN 모듈). `@ConfigurationProperties` 클래스가 이미 어떤 설정 클래스의 `@EnableConfigurationProperties`로 등록되고 있으면 자동구성에서 중복 선언하지 않는다.

- [ ] **Step 3: 자동구성 작성**

`shared/src/main/java/com/kista/shared/platform/PlatformAutoConfiguration.java` (Step 2 목록에 맞춰 조정):
```java
package com.kista.shared.platform;

import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.shared.platform.crypto.AesCryptoService;
import com.kista.shared.platform.internalapi.InternalApiClientConfig;
import com.kista.shared.platform.internalapi.InternalApiProperties;
import com.kista.shared.platform.metrics.MetricsConfig;
import com.kista.shared.platform.persistence.JpaAuditingConfig;
import com.kista.shared.platform.redis.RedisPubSubConfig;
import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.JwtDecoderConfig;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.platform.telegram.TelegramConfig;
import com.kista.shared.platform.telegram.TelegramProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

// :shared 인프라 빈 등록 — 앱(root·trading-core)은 com.kista.shared를 컴포넌트 스캔하지 않는다.
// HIGHEST_PRECEDENCE: Boot 자동구성의 @ConditionalOnMissingBean(기본 SecurityFilterChain·JwtDecoder 등)보다
// 먼저 평가돼야 기본 빈이 함께 등록되지 않는다(컨텍스트 로드 테스트가 SecurityFilterChain 1개를 단언)
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties({TelegramProperties.class, InternalApiProperties.class})
@Import({
        SecurityConfig.class, JwtDecoderConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
        RedisPubSubConfig.class, TelegramConfig.class, InternalApiClientConfig.class, MetricsConfig.class,
        AesCryptoService.class, AccountNoHasher.class, SchedulerJobRunner.class, SchedulerLockService.class,
        JpaAuditingConfig.class,
})
public class PlatformAutoConfiguration {
}
```
`shared/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.kista.shared.platform.PlatformAutoConfiguration
```

- [ ] **Step 4: 앱의 shared 스캔 항목 제거**

- `KistaApplication.scanBasePackages`에서 `"com.kista.shared.kernel"`, `"com.kista.shared.platform"` 제거(주석의 ":shared(sharedkernel/platform)" 언급도 "shared는 자동구성으로 등록"으로 갱신).
- `RootJpaScanConfig`의 두 목록에서 같은 두 항목 제거.
- `TradingApplication.scanBasePackages`에서 `"com.kista.shared.kernel"`, `"com.kista.shared.platform"` 제거, `@ConfigurationPropertiesScan`에서 `"com.kista.shared.platform", // TelegramProperties` 제거.

- [ ] **Step 5: 테스트 지원을 `:shared` testFixtures로**

```bash
mkdir -p shared/src/testFixtures/java/com/kista/shared/support shared/src/testFixtures/resources
git mv trading-core/src/testFixtures/java/com/kista/support/DataJpaTestBase.java shared/src/testFixtures/java/com/kista/shared/support/
git mv trading-core/src/testFixtures/java/com/kista/support/WebMvcTestSupport.java shared/src/testFixtures/java/com/kista/shared/support/
git mv trading-core/src/testFixtures/resources/application-test.yml shared/src/testFixtures/resources/
perl -pi -e 's/^package com\.kista\.support;/package com.kista.shared.support;/' shared/src/testFixtures/java/com/kista/shared/support/*.java
git ls-files '*.java' | xargs perl -pi -e 's/\bcom\.kista\.support\.(DataJpaTestBase|WebMvcTestSupport)\b/com.kista.shared.support.$1/g'
```
같은 패키지(`com.kista.support`)에서 import 없이 쓰던 곳(`TradingFixtures`, `TradingCoreJpaTestConfig` 등)은 컴파일 오류로 드러난다 — import를 추가한다.

`shared/build.gradle.kts`:
```kotlin
plugins {
    // 기존 플러그인 유지 +
    `java-test-fixtures`
}
dependencies {
    // DataJpaTestBase/WebMvcTestSupport + application-test.yml — root·trading-core 테스트가 소비
    testFixturesImplementation(libs.spring.boot.starter.data.jpa)
    testFixturesImplementation(libs.spring.boot.starter.data.jpa.test)
    testFixturesImplementation(libs.spring.boot.starter.security)
}
```
`trading-core/build.gradle.kts`: `testImplementation(testFixtures(project(":shared")))` 추가. testFixtures 주석을 "TradingFixtures(trading 전용)"로 갱신하고, `TradingFixtures` 컴파일에 더는 필요 없는 `testFixturesImplementation` 의존은 컴파일 확인 후 제거.
`build.gradle.kts`(root): `testImplementation(testFixtures(project(":shared")))` 추가, `testImplementation(testFixtures(project(":trading-core")))` 제거(root 테스트는 `TradingFixtures`를 쓰지 않는다 — `git grep TradingFixtures -- src/test` 결과 0건 확인 후).

Run: `./gradlew compileJava compileTestJava 2>&1 | grep -E "BUILD|error:"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 슬라이스 테스트 전수 실행 (Review Focus 1)**

```bash
WEBMVC=$(git grep -l '@WebMvcTest' -- 'src/test/**/*.java' | sed 's|.*/java/||; s|\.java$||; s|/|.|g')
TCWEBMVC=$(git grep -l '@WebMvcTest' -- 'trading-core/src/test/**/*.java' | sed 's|.*/java/||; s|\.java$||; s|/|.|g')
./gradlew :test $(printf -- "--tests %s " $WEBMVC) :trading-core:test $(printf -- "--tests %s " $TCWEBMVC) 2>&1 | grep -E "FAILED|BUILD|tests completed"
```
Expected: 전부 PASS. 실패 테스트는 원인별로:
- 필터 빈(`JwtAuthFilter`/`InternalTokenAuthFilter`)을 스캔으로 받던 테스트 → 기존 패턴대로 `@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})` + `@MockitoBean JwtDecoder` 명시(testing.md "@WebMvcTest + Spring Security 패턴").
- 단언 의미가 바뀌어 통과하는 경우를 막기 위해, 수정한 테스트는 수정 전 실패 메시지를 커밋 메시지 본문에 한 줄씩 남긴다.

- [ ] **Step 7: DataJpa 슬라이스 실행**

```bash
JPA=$(git grep -lE 'extends (DataJpaTestBase|RootDataJpaTestBase)' -- '*.java' | grep -v testFixtures)
```
root 쪽과 trading-core 쪽을 나눠 `--tests`로 실행(Step 6과 같은 방식). Expected: PASS.

- [ ] **Step 8: 빈 이름 diff (Review Focus 2)**

Step 1 스냅샷 코드를 `beans-after*.txt`로 바꿔 두 컨텍스트 로드 테스트 실행 후:
```bash
diff build/beans-before.txt build/beans-after.txt
diff build/beans-before-trading.txt build/beans-after-trading.txt
```
Expected: 차이는 (a) `com.kista.shared.platform.PlatformAutoConfiguration` 추가, (b) `@Import`로 등록된 빈의 이름이 단순 이름(`jwtAuthFilter`)에서 FQCN(`com.kista.shared.platform.security.JwtAuthFilter`)으로 바뀐 것뿐이어야 한다. 그 외 추가·누락이 있으면 멈추고 원인을 판정(누락 = 등록 대상 빠짐, 추가 = Boot 기본 빈 중복).

빈 이름이 FQCN으로 바뀌어 `@Qualifier("jwtAuthFilter")`처럼 이름으로 주입하던 곳이 깨지면(constraints.md: `telegramRestClient`/`telegramHttpClient` 이름 의존), 해당 클래스는 `@Import` 대신 설정 클래스의 `@Bean` 메서드 이름 그대로 남는지 확인하고 필요하면 `@Component("이름")`으로 이름을 고정한다.

- [ ] **Step 9: 임시 스냅샷 코드 제거 후 커밋 (검토 후)**

두 컨텍스트 로드 테스트 재실행 PASS 확인 후:
```bash
git add -A && git commit -m "refactor(boot): :shared 인프라 빈을 자동구성으로 등록한다

앱은 com.kista.shared를 스캔하지 않고 PlatformAutoConfiguration이 명시 등록한다.
DataJpaTestBase·WebMvcTestSupport·application-test.yml을 :shared testFixtures로 옮긴다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
```

---

## Task 3: `:trading-core` 패키지 이동 (기계적)

**Files:**
- Move: `trading-core/src/{main,test,testFixtures}/java/com/kista/{trading,matching,broker,account,privacy,marketcalendar}` → `.../com/kista/tradingcore/<같은 이름>`
- Move: `.../com/kista/{tradingstats,tradingnotify,tradingweb}` → `.../com/kista/tradingcore/{stats,notify,web}` (모듈 CLAUDE.md 포함)
- Move: `com.kista.support.{TradingFixtures,TradingCoreJpaTestConfig,StubBrokerApiException}` → `com.kista.tradingcore.support`
- Move: `com.kista.architecture.TradingCoreTestVendorModelTest` → `com.kista.tradingcore.architecture`
- Modify(치환): 레포 전체(root 테스트의 trading-core import, `flows.yml`, ArchUnit 문자열, `TradingApplication` 목록 포함)

**Interfaces:**
- Consumes: Task 2 결과.
- Produces: `com.kista.tradingcore.*` FQCN. `TradingApplication`은 임시로 `com.kista.tradingcore.web.TradingApplication`(Task 4가 루트로 옮긴다). 모듈 ID는 Task 0 값 유지(`tradingnotify` 등 — Task 6에서 최종 ID로).

- [ ] **Step 1: 스크립트 실행**

```bash
bash "$SCRATCH/pkgmove.sh" tradingcore
```

- [ ] **Step 2: 잔여 참조 0건 확인**

```bash
git grep -nE 'com[./]kista[./](trading|tradingstats|tradingnotify|tradingweb|matching|broker|account|privacy|marketcalendar)\b' -- ':!docs/**' ':!*.md' ':!.github/tests/**' ':!deploy/hooks/**'
git grep -nE 'com[./]kista[./]support[./](TradingFixtures|TradingCoreJpaTestConfig|StubBrokerApiException)' -- ':!docs/**'
git grep -nE 'tradingcore\.(trading|tradingcore)\.tradingcore|tradingcore\.tradingcore' -- ':!docs/**'   # 이중 치환 감지
ls trading-core/src/test/java/com/kista trading-core/src/testFixtures/java/com/kista
git grep -l $'\xef\xbb\xbf' -- '*.java'
```
Expected: grep 3개·BOM 출력 없음. `ls`는 `shared tradingcore`(test)와 `tradingcore`(testFixtures)만.

- [ ] **Step 3: 컴파일 + 아키텍처 테스트**

```bash
./gradlew compileJava compileTestJava :test --tests 'com.kista.architecture.*' :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD|error:"
```
Expected: BUILD SUCCESSFUL, 테스트 수 baseline과 동일.

- [ ] **Step 4: 커밋 (검토 후)**

```bash
git add -A && git commit -m "refactor(package): :trading-core를 com.kista.tradingcore 루트로 옮긴다

tradingstats→tradingcore.stats, tradingnotify→tradingcore.notify, tradingweb→tradingcore.web,
나머지 모듈은 이름 유지. git mv + 스크립트 치환만 포함.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
git show --stat HEAD | grep -c '=>'
```

---

## Task 4: `TradingApplication` 루트 이동 + trading-core 스캔 설정 제거 (수작업)

**Files:**
- Move: `trading-core/src/main/java/com/kista/tradingcore/web/TradingApplication.java` → `trading-core/src/main/java/com/kista/tradingcore/TradingApplication.java`
- Delete: `trading-core/src/main/java/com/kista/tradingcore/web/JpaRepositoryConfig.java`
- Delete: `trading-core/src/test/java/com/kista/tradingcore/support/TradingCoreJpaTestConfig.java`
- Move: `trading-core/src/test/java/com/kista/tradingcore/web/TradingApplicationContextLoadTest.java` → `trading-core/src/test/java/com/kista/tradingcore/TradingApplicationContextLoadTest.java`
- Modify: `@ContextConfiguration(classes = TradingApplication.class)` 22곳, `@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)` 17곳 제거
- Modify: `trading-core/build.gradle.kts` (bootJar `mainClass` 지정이 있으면 갱신 — 2026-10-13 기준 없음)

**Interfaces:**
- Consumes: Task 3의 `com.kista.tradingcore.web.TradingApplication`.
- Produces: `com.kista.tradingcore.TradingApplication`(최종 위치). trading-core 테스트는 `@SpringBootConfiguration` 상향 탐색으로 이 클래스를 찾는다.

- [ ] **Step 1: 루트로 이동하고 목록 제거**

```bash
git mv trading-core/src/main/java/com/kista/tradingcore/web/TradingApplication.java trading-core/src/main/java/com/kista/tradingcore/TradingApplication.java
git mv trading-core/src/test/java/com/kista/tradingcore/web/TradingApplicationContextLoadTest.java trading-core/src/test/java/com/kista/tradingcore/TradingApplicationContextLoadTest.java
```
`TradingApplication.java` 전체를 아래로:
```java
package com.kista.tradingcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// kista-trading 프로세스 부팅 진입점 — 루트 패키지 com.kista.tradingcore에 두어 컴포넌트·엔티티·리포지토리 스캔이
// 기본값(자기 루트)으로 trading-core 전체를 덮는다. :shared 빈은 PlatformAutoConfiguration이 등록한다
@SpringBootApplication
@ConfigurationPropertiesScan // AlpacaProperties 등 trading-core 소유 @ConfigurationProperties
@EnableScheduling
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
```
`TradingApplicationContextLoadTest`의 `package`를 `com.kista.tradingcore`로.

- [ ] **Step 2: JPA 설정 삭제 후 부팅 검증 (Review Focus 4)**

```bash
git rm trading-core/src/main/java/com/kista/tradingcore/web/JpaRepositoryConfig.java
./gradlew :trading-core:test --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD|Expected|but"
```
Expected: PASS(리포지토리 16개). FAIL(0개)이면 spec 5절 대안 적용 — `com.kista.tradingcore.web`에 아래 클래스를 두고 재실행:
```java
// Redis 리포지토리 모듈이 함께 있어 JPA 리포지토리 자동구성이 활성화되지 않는다(TradingApplicationContextLoadTest로 실측) —
// 패키지 목록 없이 선언해 기본 범위(TradingApplication 루트)를 쓴다. @WebMvcTest 슬라이스에 걸리지 않도록 앱 클래스와 분리
@Configuration
@EnableJpaRepositories(basePackageClasses = TradingApplication.class)
class JpaRepositoryConfig {
}
```
어느 쪽인지 baseline 파일에 기록.

- [ ] **Step 3: 테스트의 명시 설정 제거**

```bash
git rm trading-core/src/test/java/com/kista/tradingcore/support/TradingCoreJpaTestConfig.java
git grep -lE '@ContextConfiguration\(classes = (TradingApplication|TradingCoreJpaTestConfig)\.class\)' -- 'trading-core/**/*.java' \
  | xargs perl -0pi -e 's/^\@ContextConfiguration\(classes = (?:TradingApplication|TradingCoreJpaTestConfig)\.class\)[^\n]*\n//mg; s/^import com\.kista\.tradingcore\.(?:web\.TradingApplication|support\.TradingCoreJpaTestConfig);\n//mg; s/^import org\.springframework\.test\.context\.ContextConfiguration;\n//mg if !/\@ContextConfiguration/'
git grep -nE 'TradingCoreJpaTestConfig|tradingcore\.web\.TradingApplication' -- '*.java' '*.kts'
```
Expected: 마지막 grep 출력 없음. `@ContextConfiguration`이 다른 클래스를 함께 지정하던 경우(`classes = {TradingApplication.class, X.class}`)는 perl이 건드리지 않으므로 `git grep -n 'ContextConfiguration' -- 'trading-core/**'`로 남은 곳을 확인해 수동으로 `X`만 남긴다.

- [ ] **Step 4: 슬라이스·부팅 테스트**

```bash
./gradlew compileTestJava 2>&1 | grep -E "BUILD|error:"
```
Task 2 Step 6·7과 같은 방식으로 trading-core `@WebMvcTest`·`DataJpaTestBase` 상속 테스트 전부 + `TradingApplicationContextLoadTest` + `:trading-core:test --tests 'com.kista.tradingcore.architecture.*'` + `:test --tests 'com.kista.architecture.*'` 실행.
Expected: 전부 PASS. `@DataJpaTest`가 `TradingApplication`의 `@EnableScheduling`·`@ConfigurationPropertiesScan`을 끌어와 실패하면(spec W2) `@EnableScheduling`을 `com.kista.tradingcore.web.SchedulingConfig`(`@Configuration @EnableScheduling`)로 분리하고 재실행.

- [ ] **Step 5: 커밋 (검토 후)**

```bash
git add -A && git commit -m "refactor(boot): TradingApplication을 com.kista.tradingcore 루트로 옮기고 스캔 목록을 없앤다

scanBasePackages·@EntityScan·JpaRepositoryConfig·TradingCoreJpaTestConfig·
테스트 @ContextConfiguration을 제거하고 기본 스캔 범위를 쓴다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
```

---

## Task 5: `:api` 패키지 이동 + `ApiApplication` 개명 (기계적)

**Files:**
- Move: `src/{main,test}/java/com/kista/{admin,finance,market,notify,benchmark,user,web}` → `.../com/kista/api/<같은 이름>`
- Move: `src/test/java/com/kista/architecture` → `src/test/java/com/kista/api/architecture`
- Move: `src/main/java/com/kista/KistaApplication.java` → `src/main/java/com/kista/api/ApiApplication.java`
- Move: `src/test/java/com/kista/{ApplicationContextLoadTest,SchedulerDisabledContextTest,SchedulerRoleConfigTest}.java` → `src/test/java/com/kista/api/`
- Move: `src/testFixtures/java/com/kista/support/DomainFixtures.java` → `src/testFixtures/java/com/kista/api/support/DomainFixtures.java`
- Modify(치환): 레포 전체(`.github/scripts/detect-deploy-scope.sh`의 `AdminSchedulerController` 경로 포함)

**Interfaces:**
- Consumes: Task 4 결과.
- Produces: `com.kista.api.*` FQCN, `com.kista.api.ApiApplication`.

- [ ] **Step 1: 스크립트 실행**

```bash
bash "$SCRATCH/pkgmove.sh" api
```

- [ ] **Step 2: 잔여 참조 0건 확인**

```bash
git grep -nE 'com[./]kista[./](admin|finance|market|notify|benchmark|user|web|architecture|support)\b' -- ':!docs/**' ':!*.md' ':!.github/tests/**' ':!deploy/hooks/**'
git grep -nE '\bKistaApplication\b|^package com\.kista;' -- '*.java' '*.kts' '*.yml' '*.sh'
git grep -nE 'api\.api\.|com\.kista\.api\.marketcalendar' -- ':!docs/**'
for r in . trading-core shared; do for s in main test testFixtures; do d=$r/src/$s/java/com/kista; [ -d $d ] && echo "$d: $(ls $d | tr '\n' ' ')"; done; done
git grep -l $'\xef\xbb\xbf' -- '*.java'
```
Expected: grep 3개·BOM 출력 없음. 디렉토리 목록이 정확히 `./src/*: api`, `trading-core/src/main|testFixtures: tradingcore`, `trading-core/src/test: shared tradingcore`, `shared/src/*: shared`.

- [ ] **Step 3: 컴파일 + 아키텍처 테스트 + root 부팅**

```bash
./gradlew compileJava compileTestJava :test --tests 'com.kista.api.architecture.*' --tests 'com.kista.api.ApplicationContextLoadTest' :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD|error:"
```
Expected: BUILD SUCCESSFUL. `ApplicationContextLoadTest`의 `TRADING_CORE_PACKAGES` 목록이 스크립트로 `com.kista.tradingcore.*` 항목들이 됐는지 확인(Task 3에서 치환됨). 아직 `RootJpaScanConfig` 목록이 남아 있지만 스크립트가 `com.kista.api.*`로 치환해 유효해야 한다.

- [ ] **Step 4: 커밋 (검토 후)**

```bash
git add -A && git commit -m "refactor(package): :api를 com.kista.api 루트로 옮기고 KistaApplication을 ApiApplication으로 바꾼다

git mv + 스크립트 치환만 포함.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
git show --stat HEAD | grep -c '=>'
```

---

## Task 6: 루트 스캔 정리 + 프로세스별 Modulith 모델 + 아키텍처 맵 (수작업)

**Files:**
- Modify: `src/main/java/com/kista/api/ApiApplication.java`
- Delete: `src/main/java/com/kista/api/web/RootJpaScanConfig.java`
- Modify: `src/test/java/com/kista/api/web/RootDataJpaTestBase.java`(`RootJpaScanConfig` 참조 제거)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/TradingApplication.java` (`@Modulithic`)
- Modify: 4개 `package-info.java` 최종 ID(`tradingcore.stats`→`stats`, `tradingcore.notify`→`notify`, `tradingcore.web`→`web`, `shared.kernel`→`kernel`)
- Modify: `src/test/java/com/kista/api/architecture/ModulithArchitectureTest.java`
- Create: `trading-core/src/test/java/com/kista/tradingcore/architecture/ModulithArchitectureTest.java`
- Modify: `trading-core/build.gradle.kts` (modulith test 의존)
- Modify: `src/test/java/com/kista/api/architecture/ModuleGraphExporter.java`, `ArchitectureMapExporter.java`(`moduleOf`), `ArchitectureMapTest.java`
- Modify: `src/test/resources/architecture/map/views/structure.js:11`, `src/test/resources/architecture/map/index.html:31`

**Interfaces:**
- Consumes: Task 5의 `com.kista.api.ApiApplication`, Task 4의 `com.kista.tradingcore.TradingApplication`.
- Produces: 프로세스별 모델 — api 모델 ID `{admin, benchmark, finance, market, notify, user, web, kernel, contract, platform}`, trading 모델 ID `{account, broker, marketcalendar, matching, privacy, trading, stats, notify, web, kernel, contract, platform}`. 아키텍처 맵 모듈 키 = `com.kista.` 뒤 두 세그먼트(`api.notify`, `tradingcore.trading`, `shared.kernel`).

- [ ] **Step 1: 실패하는 Modulith 테스트 먼저 (root)**

`ModulithArchitectureTest`(root) 본문:
```java
    @Test
    @DisplayName("kista-api 프로세스 모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        // 프로세스별 모델 — root와 trading-core는 같은 JVM에서 돌지 않으므로 각자 검증한다(trading-core는 자기 테스트에서)
        var modules = ApplicationModules.of(ApiApplication.class).verify();

        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("admin", "benchmark", "finance", "market", "notify", "user", "web",
                        "kernel", "contract", "platform");

        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
```
Run: `./gradlew :test --tests 'com.kista.api.architecture.ModulithArchitectureTest' 2>&1 | grep -E "FAILED|BUILD|Expecting|but"`
Expected: FAIL(아직 `@Modulithic` 없음 → shared 모듈 누락, `sharedkernel` ID).

- [ ] **Step 2: 앱 클래스·ID 최종화**

`ApiApplication.java` 전체:
```java
package com.kista.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

// kista-api·kista-scheduler 프로세스 부팅 진입점 — 루트 패키지 com.kista.api에 두어 스캔이 기본값(자기 루트)으로
// root 전체를 덮는다. :shared 빈은 PlatformAutoConfiguration이 등록하고, Modulith 검증 모델에만 shared를 포함한다
@SpringBootApplication
@Modulithic(additionalPackages = "com.kista.shared")
@EnableScheduling
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
```
`TradingApplication`에 `@Modulithic(additionalPackages = "com.kista.shared")` 추가(import `org.springframework.modulith.Modulithic`), 주석에 같은 설명 한 줄.

`git rm src/main/java/com/kista/api/web/RootJpaScanConfig.java` 후 `git grep -n RootJpaScanConfig`로 참조(`RootDataJpaTestBase` 등)를 찾아 제거. `RootDataJpaTestBase`가 `RootJpaScanConfig`를 실으려고 존재했다면, 이제 `@DataJpaTest` 상향 탐색이 `ApiApplication`(루트 `com.kista.api`)을 찾아 엔티티 범위가 자동으로 root로 좁혀지므로 클래스 주석을 그 사실로 갱신한다(클래스 자체는 `DataJpaTestBase` 상속 경로 유지 목적이면 남긴다).

package-info 4개 `id` 변경: `"tradingstats"`→`"stats"`, `"tradingnotify"`→`"notify"`, `"tradingweb"`→`"web"`, `"sharedkernel"`→`"kernel"`.

Run: `./gradlew :test --tests 'com.kista.api.architecture.ModulithArchitectureTest' --tests 'com.kista.api.ApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD|Expecting|but"`
Expected: PASS(리포지토리 21개 유지 — 실패 시 Task 4 Step 2와 같은 대안을 `com.kista.api.web`에 적용하고 기록).

- [ ] **Step 3: trading-core Modulith 테스트**

`trading-core/build.gradle.kts` dependencies에 `testImplementation(libs.spring.modulith.starter.test)` 추가.

`trading-core/src/test/java/com/kista/tradingcore/architecture/ModulithArchitectureTest.java`:
```java
package com.kista.tradingcore.architecture;

import com.kista.tradingcore.TradingApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("kista-trading 프로세스 Spring Modulith 모듈 경계 규칙")
class ModulithArchitectureTest {

    @Test
    @DisplayName("모듈 간 의존이 허용된 방향으로만 존재하고 순환이 없다")
    void verifyModularStructure() {
        var modules = ApplicationModules.of(TradingApplication.class).verify();

        // 모듈이 조용히 빠지거나 합쳐지는 것을 막는 기준선
        assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
                .containsExactlyInAnyOrder("account", "broker", "marketcalendar", "matching", "privacy", "trading",
                        "stats", "notify", "web", "kernel", "contract", "platform");
    }
}
```
Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD|Expecting|but"`
Expected: PASS. `verify()`가 지금까지 통합 모델에선 안 보이던 위반을 보고하면 멈추고 보고(프로세스 분리로 새로 드러난 결함일 수 있다).

- [ ] **Step 4: 아키텍처 맵 실패 테스트 먼저**

`ArchitectureMapTest`의 모듈 조립과 단언:
```java
        var modules = ModuleGraphExporter.graph(
                ApplicationModules.of(ApiApplication.class), ApplicationModules.of(TradingApplication.class));
        // 단계 code: 클래스는 전부 모듈 소속이어야 하고, 오버레이 회귀 가드 — 마감 배치 락 단계는 trading을 거친다
        var moduleNames = modules.stream().map(ModuleGraphExporter.Module::name).collect(Collectors.toSet());
        assertThat(moduleNames).contains("api.notify", "tradingcore.notify", "shared.kernel"); // 동명 모듈이 프로세스로 구분된다
        assertThat(ArchitectureMapExporter.moduleViolations(map, classes, moduleNames)).isEmpty();
        var stepModules = ArchitectureMapExporter.stepModules(map, classes);
        assertThat(stepModules.get("close-batch").get("lock")).contains("tradingcore.trading");
```
import `com.kista.tradingcore.TradingApplication`.
Run: `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 FAIL(`graph` 시그니처).

- [ ] **Step 5: exporter 구현**

`ModuleGraphExporter`에서 `EXCLUDED`·`project(ApplicationModule)` URI 판별을 지우고:
```java
    // 프로세스별 모델을 합친다 — 모듈 키는 com.kista 아래 두 세그먼트(api.notify / tradingcore.notify)라 동명 모듈이 충돌하지 않고,
    // 두 모델에 모두 있는 shared 모듈은 한 번만 남긴다
    static List<Module> graph(ApplicationModules... models) {
        var merged = new TreeMap<String, Module>();
        for (var modules : models) {
            modules.forEach(m -> merged.putIfAbsent(name(m), toModule(m, modules)));
        }
        return List.copyOf(merged.values());
    }

    private static Module toModule(ApplicationModule module, ApplicationModules modules) {
        // 클래스 단위 의존 수집
        var deps = module.getDirectDependencies(modules).stream()
                .map(d -> new Dependency(name(d.getTargetModule()), d.getDependencyType().name(),
                        d.getSourceType().getSimpleName(), d.getTargetType().getSimpleName()))
                .collect(Collectors.toCollection(TreeSet::new));

        var name = name(module);
        return new Module(
                name,
                project(name),
                module.getNamedInterfaces().stream().filter(NamedInterface::isNamed).map(NamedInterface::getName).sorted().toList(),
                module.getEventsListenedTo(modules).stream().map(JavaClass::getSimpleName).sorted().distinct().toList(),
                List.copyOf(deps));
    }

    // com.kista.<루트>.<모듈> → "<루트>.<모듈>" — ArchitectureMapExporter.moduleOf와 같은 규칙
    private static String name(ApplicationModule module) {
        return module.getBasePackage().getName().replaceFirst("^com\\.kista\\.", "");
    }

    // 모듈 키의 루트 세그먼트로 Gradle 서브프로젝트 판별
    private static String project(String name) {
        return switch (name.substring(0, name.indexOf('.'))) {
            case "tradingcore" -> ":trading-core";
            case "shared" -> ":shared";
            default -> ":api";
        };
    }
```
필요 import(`java.util.TreeMap`) 추가, 안 쓰게 된 import 제거.

`ArchitectureMapExporter.moduleOf`:
```java
    // com.kista.<루트>.<모듈>.… 의 앞 두 세그먼트 — 잡·단계 모두 이 규칙(ModuleGraphExporter.name과 동일)
    static String moduleOf(JavaClass owner) {
        var parts = owner.getPackageName().replaceFirst("^com\\.kista\\.", "").split("\\.");
        return parts.length < 2 ? parts[0] : parts[0] + "." + parts[1];
    }
```
`structure.js:11`: `const INFRA = new Set(['shared.platform', 'shared.kernel']); // 거의 모든 모듈이 참조하는 노이즈 모듈`
`index.html:31`: 라벨 텍스트 `platform·sharedkernel 숨김` → `platform·kernel 숨김`.
`git grep -nE "'(trading|broker|account|user|admin|notify|web)'" -- src/test/resources/architecture/map` 로 다른 하드코딩 모듈 이름이 없는지 확인.

Run: `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|Expecting|but"`
Expected: PASS. `build/architecture-map/index.html`을 브라우저로 열어 구조 탭에 `api.notify`·`tradingcore.notify`가 각 프로젝트 박스에 하나씩, `shared.*` 3개가 가운데 한 번씩 보이는지 육안 확인(스크린샷 불필요, 결과만 보고).

- [ ] **Step 6: 전체 아키텍처 테스트 + 커밋 (검토 후)**

```bash
./gradlew :test --tests 'com.kista.api.architecture.*' :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
git add -A && git commit -m "refactor(boot): 루트 스캔 설정을 없애고 Modulith 검증을 프로세스별 모델로 나눈다

ApiApplication·TradingApplication에 @Modulithic(additionalPackages=com.kista.shared)를 두고
모듈 ID를 최종 이름(stats/notify/web/kernel)으로 바꾼다. 아키텍처 맵은 두 모델을 합쳐
루트.모듈 키로 렌더링한다. RootJpaScanConfig 제거.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
```

---

## Task 7: 서브프로젝트 경계 규칙 + ArchUnit 문자열 정리 (수작업)

**Files:**
- Modify: `src/test/java/com/kista/api/architecture/GradleModuleBoundaryTest.java`
- Modify: `src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java` (주석·규칙 이름)
- Modify: `src/test/java/com/kista/api/ApplicationContextLoadTest.java` (`TRADING_CORE_PACKAGES` 단순화)
- Modify: `.github/tests/deploy-plan.bats:14`

**Interfaces:**
- Consumes: 최종 패키지 구조.
- Produces: 서브프로젝트 접두사 규칙(삭제된 스캔 allowlist의 대체 가드).

- [ ] **Step 1: 실패하는 접두사 규칙 먼저**

`GradleModuleBoundaryTest`에 추가:
```java
    // 서브프로젝트마다 고유 루트 패키지를 갖는다 — 스캔 목록 없이 기본 스캔(자기 루트)만으로 프로세스가 분리되는 근거.
    // 다른 루트에 클래스를 두면 root 테스트 classpath의 trading-core 빈이 root 컨텍스트에 섞이는 경로가 다시 생긴다
    @ParameterizedTest
    @CsvSource({
            "build/classes/java/main, com.kista.api..",
            "build/classes/java/test, com.kista.api..",
            "build/classes/java/testFixtures, com.kista.api..",
            "trading-core/build/classes/java/main, com.kista.tradingcore..",
            "trading-core/build/classes/java/testFixtures, com.kista.tradingcore..",
            "shared/build/classes/java/main, com.kista.shared..",
            "shared/build/classes/java/test, com.kista.shared..",
            "shared/build/classes/java/testFixtures, com.kista.shared..",
    })
    void subprojectClassesStayUnderOwnRootPackage(String classesDir, String rootPackage) {
        var importedClasses = new ClassFileImporter().importPath(Path.of(classesDir));
        assertThat(importedClasses).isNotEmpty(); // 산출물 미생성 시 공허 통과 방지

        ArchRuleDefinition.classes().should().resideInAPackage(rootPackage).check(importedClasses);
    }

    // trading-core 테스트는 자기 루트 + SchedulerLockService DB 테스트(같은 패키지 접근 필요, scheduler_locks는
    // trading 마이그레이션이 만든다 — :shared 테스트로 옮기면 테이블이 없다)만 예외로 둔다
    @Test
    void tradingCoreTestClassesStayUnderOwnRootPackage() {
        var importedClasses = new ClassFileImporter().importPath(Path.of("trading-core/build/classes/java/test"));
        assertThat(importedClasses).isNotEmpty();

        ArchRuleDefinition.classes().should()
                .resideInAnyPackage("com.kista.tradingcore..", "com.kista.shared.platform.scheduling..")
                .check(importedClasses);
    }
```
import: `org.junit.jupiter.params.ParameterizedTest`, `org.junit.jupiter.params.provider.CsvSource`(root 테스트에 `junit-jupiter-params`가 없으면 `build.gradle.kts`에 `testImplementation("org.junit.jupiter:junit-jupiter-params")` 추가 — BOM 관리 버전).

Run: `./gradlew compileJava compileTestJava :trading-core:compileTestFixturesJava :shared:compileTestFixturesJava :shared:compileTestJava :test --tests 'com.kista.api.architecture.GradleModuleBoundaryTest' 2>&1 | grep -E "FAILED|BUILD|was violated"`
Expected: PASS(이전 Task들이 구조를 이미 맞췄다). 실패하면 위반 클래스를 옮긴다 — 이 Task가 잡으려는 바로 그 결함이다. 규칙이 실제로 잡는지 확인: 임시로 `shared/src/test/java/com/kista/Probe.java`(`package com.kista; class Probe {}`)를 만들고 재실행 → FAIL 확인 후 삭제.

- [ ] **Step 2: 기존 경계 규칙 단순화**

치환으로 `"com.kista.api.user..", "com.kista.api.admin..", …`처럼 나열된 세 규칙을 루트 접두사로 바꾼다:
- `tradingCoreMustNotDependOnApiOnlyModules`/`tradingCoreTestsMustNotDependOnApiOnlyModules`: `.should().dependOnClassesThat().resideInAPackage("com.kista.api..")`
- `sharedMustNotDependOnTradingCoreOrApiModules`: `.should().dependOnClassesThat().resideInAnyPackage("com.kista.api..", "com.kista.tradingcore..")`
메서드 주석의 모듈 나열도 "com.kista.api 전체"로 갱신.

- [ ] **Step 3: HexagonalArchitectureTest·컨텍스트 테스트 정리**

```bash
git grep -nE 'tradingweb|tradingnotify|tradingstats|sharedkernel|KistaApplication' -- 'src/test/**/*.java' 'trading-core/src/test/**/*.java'
```
남은 주석·메서드 이름·`@DisplayName`을 새 이름으로 고친다(예: `web_must_stay_pure_inbound_sink` 주석의 `com.kista.tradingweb` → `com.kista.tradingcore.web`). 문자열 규칙은 이미 치환됐으므로 패턴 의미만 검토: `"com.kista.api.web..", "com.kista.tradingcore.web.."`가 두 앱셸을, `"com.kista..domain.."` 같은 와일드카드가 세 루트 모두를 덮는지.

`ApplicationContextLoadTest.TRADING_CORE_PACKAGES`를 `List.of("com.kista.tradingcore.")`로 단순화.

`.github/tests/deploy-plan.bats:14`의 경로를 `src/main/java/com/kista/api/benchmark/adapter/in/schedule/S.java`로.

- [ ] **Step 4: 아키텍처 테스트 + 커밋 (검토 후)**

```bash
./gradlew :test --tests 'com.kista.api.architecture.*' --tests 'com.kista.api.ApplicationContextLoadTest' :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
git add -A && git commit -m "test(architecture): 서브프로젝트 루트 패키지 경계 규칙을 추가한다

삭제한 스캔 allowlist 대신 각 서브프로젝트 산출물이 자기 루트 패키지 아래에만 있음을 강제하고,
교차 의존 규칙을 루트 접두사로 단순화한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
```

---

## Task 8: 문서 동기화

**Files:**
- Move: `docs/agents/modules/*.md` → `docs/agents/modules/{api,tradingcore,shared}/<모듈ID>.md`
- Modify: 모든 `*.md`(역사 문서 제외) — `pkgmove.sh docs`
- Modify: 19개 모듈 `CLAUDE.md` — `fix-claude-md.py`
- Modify(수작업): `docs/agents/architecture.md`, `constraints.md`, `testing.md`, `commands.md`, `docker-infra.md`, `modules/tradingcore/web.md`, `modules/shared/platform.md`, `own-type-ledger.md`, `README.md`, `docs/architecture-map.md`, `AGENTS.md`, `CLAUDE.md`(kista-api), `.claude/agents/*.md`, `.claude/skills/new-adapter/SKILL.md`, `.claude/rules/statistics-controllers.md`

**Interfaces:**
- Consumes: 최종 코드 구조.
- Produces: 문서가 코드와 일치.

- [ ] **Step 1: 모듈 문서 재배치**

```bash
cd docs/agents/modules && mkdir -p api tradingcore shared
for m in admin benchmark finance market notify user web; do git mv $m.md api/$m.md; done
for m in account broker marketcalendar matching privacy trading trading-formulas; do git mv $m.md tradingcore/$m.md; done
git mv tradingstats.md tradingcore/stats.md; git mv tradingnotify.md tradingcore/notify.md; git mv tradingweb.md tradingcore/web.md
for m in contract platform; do git mv $m.md shared/$m.md; done
git mv sharedkernel.md shared/kernel.md
cd - >/dev/null
```
문서 경로 참조 치환(역사 문서 제외):
```bash
FILES=$(git ls-files -- '*.md' ':!docs/superpowers/**' ':!docs/agents/modulith-migration-history.md')
printf '%s\n' $FILES | xargs perl -pi -e '
  s{modules/(admin|benchmark|finance|market|user)\.md}{modules/api/$1.md}g;
  s{modules/(account|broker|marketcalendar|matching|privacy|trading-formulas|trading)\.md}{modules/tradingcore/$1.md}g;
  s{modules/tradingstats\.md}{modules/tradingcore/stats.md}g;
  s{modules/tradingnotify\.md}{modules/tradingcore/notify.md}g;
  s{modules/tradingweb\.md}{modules/tradingcore/web.md}g;
  s{modules/(contract|platform)\.md}{modules/shared/$1.md}g;
  s{modules/sharedkernel\.md}{modules/shared/kernel.md}g;
  s{modules/notify\.md}{modules/api/notify.md}g;
  s{modules/web\.md}{modules/api/web.md}g;'
```
주의: `modules/notify.md`·`modules/web.md` 치환은 `tradingnotify`/`tradingweb` 치환 뒤에 와야 한다(위 순서 유지). 치환 후 `tradingcore` 문서 안에서 자기 자신을 `modules/api/notify.md`로 가리키게 된 곳이 없는지 `git grep -n 'modules/api/notify.md' -- docs/agents/modules/tradingcore` 로 확인.

- [ ] **Step 2: FQCN·패키지 경로 치환 + CLAUDE.md import 재계산**

```bash
bash "$SCRATCH/pkgmove.sh" docs
python3 "$SCRATCH/fix-claude-md.py"
git ls-files '*/CLAUDE.md' | grep '/src/main/java/' | while read f; do p=$(grep -oE '^@\S+' "$f" | cut -c2-); [ -f "$(dirname "$f")/$p" ] || echo "BROKEN $f -> $p"; done
```
Expected: `BROKEN` 출력 없음, 19개 처리 출력.

- [ ] **Step 3: 수작업 갱신**

각 문서에서 아래 서술을 현재 사실로 바꾼다(구 서술 삭제, 이력은 `modulith-migration-history.md`에만).
- `architecture.md`
  - "Gradle 구조": `TradingApplication`은 `com.kista.tradingcore` 루트, 테스트는 `@SpringBootConfiguration` 상향 탐색으로 앱 클래스를 찾고 `@ContextConfiguration`·`TradingCoreJpaTestConfig`는 없다. 테스트 지원은 `com.kista.shared.support`(`:shared` testFixtures: `DataJpaTestBase`/`WebMvcTestSupport`/`application-test.yml`), `com.kista.tradingcore.support`(`TradingFixtures`/`StubBrokerApiException`), `com.kista.api.support`(`DomainFixtures`).
  - 새 소절 "루트 패키지": 서브프로젝트별 루트(`com.kista.api`/`com.kista.tradingcore`/`com.kista.shared`), 앱 클래스는 각 루트에 있고 스캔 목록이 없다, shared 빈은 `PlatformAutoConfiguration`, 경계는 `GradleModuleBoundaryTest.subprojectClassesStayUnderOwnRootPackage`.
  - "모듈 한눈에 보기": 프로세스별 두 표(api 7 + shared 3 / trading-core 9 + shared 3), 각 행의 문서 경로를 `modules/<루트>/<ID>.md`로.
  - "Spring Modulith 모듈 구성": 모델은 프로세스별 2개(`@Modulithic(additionalPackages = "com.kista.shared")`), ID는 `package-info`의 `@ApplicationModule(id = ...)`로 고정하고 감지 전략은 `explicitly-annotated`(application.yml) — 새 모듈은 `id`를 반드시 지정. 두 프로세스에 같은 ID(`notify`, `web`)가 있으며 문서·아키텍처 맵에선 `api.notify`/`tradingcore.notify`로 쓴다.
- `modules/tradingcore/web.md`: `JpaRepositoryConfig`·스캔 목록·`@ContextConfiguration` 서술 삭제, `TradingApplication`은 이 모듈이 아니라 `com.kista.tradingcore` 루트에 있다고 명시. Task 4 Step 2에서 `JpaRepositoryConfig`를 남겼다면 그 근거를 기록.
- `modules/shared/platform.md`: `PlatformAutoConfiguration` 등록 방식, 새 빈 추가 시 `@Import` 목록에 넣어야 한다는 규칙.
- `constraints.md`: Git 규칙의 "`KistaApplication.scanBasePackages`가 trading-core 패키지를 원천 배제" → "root 루트 패키지(`com.kista.api`)가 trading-core(`com.kista.tradingcore`)를 포함하지 않아 kista-scheduler엔 매매 빈이 없다". 신규 파일 배치 절의 패키지 예시. EPR 고아 절에 "패키지 루트 분리(2026-10) 같은 루트 이동은 모든 미완료 row를 고아로 만든다" 한 줄.
- `testing.md`: 지원 클래스 위치, `RootDataJpaTestBase` 서술(Task 6 결과에 맞게), `@ContextConfiguration` 관련 문장 삭제.
- `commands.md`: 테스트 필터 예시(`:test --tests 'com.kista.api.architecture.*'`, `:trading-core:test --tests 'com.kista.tradingcore.trading.domain.*'` 등 — docs 치환 결과 확인), 로컬 2-프로세스 부팅의 `tradingweb.TradingApplication` 언급.
- `docker-infra.md` "배포 직전 EPR 정리 런북": 아래 "배포 런북" 절을 "패키지 루트 분리 배포" 소절로 추가.
- `CLAUDE.md`(kista-api)·`AGENTS.md`·`README.md`·`docs/architecture-map.md`: 패키지·앱 클래스 이름, 아키텍처 다이어그램 노드 이름(`KistaApplication`→`ApiApplication`, `tradingweb`→`tradingcore.web` 등).
- `.claude/agents/architecture-guardian.md`·`test-writer.md`·`.claude/skills/new-adapter/SKILL.md`·`.claude/rules/statistics-controllers.md`: docs 치환 결과 검토, `@ContextConfiguration` 안내가 있으면 삭제.

- [ ] **Step 4: 잔여 검사**

```bash
git grep -nE 'com\.kista\.(sharedkernel|contract|platform|trading|tradingstats|tradingnotify|tradingweb|matching|broker|account|privacy|marketcalendar|admin|finance|market|notify|benchmark|user|web|support|architecture)\b|KistaApplication|scanBasePackages|TradingCoreJpaTestConfig|RootJpaScanConfig' -- '*.md' ':!docs/superpowers/**' ':!docs/agents/modulith-migration-history.md'
```
Expected: 출력 없음, 또는 남은 줄이 의도된 이력 서술(예: "과거 `scanBasePackages`로 배제했다")뿐 — 각 줄을 확인.

- [ ] **Step 5: 커밋**

문서 전용 변경이라 리뷰어 검수 예외(전역 규칙). 단 Step 4 결과를 자체 확인한 뒤:
```bash
git add -A && git commit -m "docs: 패키지 루트 분리에 맞춰 문서와 모듈 문서 위치를 갱신한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Kwh4BPotPTLtSx4GdUi5d9"
```

---

## Task 9: 최종 검증

**Files:** 없음(검증 실패 시 원인 Task로 돌아가 수정 커밋 추가).

- [ ] **Step 1: 전체 테스트 1회**

```bash
docker compose up -d postgres redis
./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"
grep -ohE 'failures="[0-9]+"|errors="[0-9]+"' build/test-results/test/*.xml trading-core/build/test-results/test/*.xml shared/build/test-results/test/*.xml | sort | uniq -c
```
Expected: BUILD SUCCESSFUL, failures/errors 전부 0.

- [ ] **Step 2: integration 1회 + 실제 실행 수 확인**

```bash
./gradlew integration :trading-core:integration 2>&1 | grep -E "FAILED|BUILD|NO-SOURCE"
ls build/test-results/integration/*.xml | wc -l; ls trading-core/build/test-results/integration/*.xml | wc -l
```
Expected: NO-SOURCE 없음, 14 / 5.

- [ ] **Step 3: 배포 스크립트 테스트 (Docker)**

commands.md "배포 스크립트 검증" 절의 bats·shellcheck 명령 실행(macOS에선 `MSYS_NO_PATHCONV`·`cygpath` 대신 `-v "$PWD:/code"`).
Expected: 전부 통과(`deploy-plan.bats`의 `AdminSchedulerController` 경로 케이스 포함).

- [ ] **Step 4: docker build 1회**

```bash
docker build -t kista-api:package-root-split . 2>&1 | tail -3
docker run --rm --entrypoint sh kista-api:package-root-split -c 'unzip -p /app/trading-core.jar META-INF/MANIFEST.MF | grep Start-Class; unzip -p /app/app.jar META-INF/MANIFEST.MF | grep Start-Class'
```
Expected: 빌드 성공, `Start-Class: com.kista.tradingcore.TradingApplication`, `Start-Class: com.kista.api.ApiApplication`(jar 경로가 다르면 Dockerfile의 `COPY` 대상 경로로 조정).

- [ ] **Step 5: 로컬 2-프로세스 부팅 1회**

commands.md "로컬 2-프로세스 부팅" 절대로 두 jar 기동 후:
```bash
grep -E "Found [0-9]+ JPA repository" logs/kista-api.log logs/kista-trading.log   # 파일 로그가 없으면 기동 콘솔 출력에서
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)        # root→trading 사용자 복제본 동기화(Redis Stream)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:8080/api/accounts   # root 내부 API → trading-core
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:8081/api/strategies  # trading-core 직접
redis-cli XINFO GROUPS stream:user.notify-profile.changed | grep -A1 lag
grep -E "ClassNotFoundException|APPLICATION FAILED|BeanCreationException" logs/kista-api.log logs/kista-trading.log
```
Expected: 리포지토리 수 root 21·trading 16(Task 0 값), 두 curl 200, 스트림 lag 0, 마지막 grep 출력 없음. 경로가 다르면 `git grep -n '@RequestMapping' -- '*Controller.java'`로 실제 경로를 확인해 대체한다. 종료: `jps -l`로 PID 확인 후 `kill`.

- [ ] **Step 6: 최종 보고**

baseline 파일과 실측 비교(테스트 수, 모듈 ID, 리포지토리 수, integration 수)를 사용자에게 보고하고, 전체 브랜치 리뷰 수행 여부를 묻는다(전역 규칙: 태스크별 리뷰를 모두 거쳤으면 기본 생략 — 단 이 작업은 세 서브프로젝트를 교차 변경하므로 수행을 권장한다고 함께 적는다). push·배포는 요청 시에만.

---

## 배포 런북 (사용자 요청 시에만 실행)

DB·Flyway·Redis 키/페이로드는 변하지 않는다. 런타임 위험은 EPR 고아 row뿐이다.

1. **배포 전 확인** (`ssh kista-server`):
   ```bash
   for s in trading public; do
     docker exec kista-postgres psql -U kista -d kistadb -c \
       "SELECT event_type, listener_id, count(*) FROM $s.event_publication WHERE completion_date IS NULL GROUP BY 1,2;"
   done
   ```
2. **Drain**: 매매 배치 시간대(docs/agents/scheduler-time-table.md)를 피한다. 미완료 row가 있으면 재발행 완료를 기다린다. 영구 실패 row는 `docker-infra.md` "배포 직전 EPR 정리 런북"대로 내용을 백업한 뒤 **삭제**한다. `completion_date`를 채워 완료로 위장하지 않는다.
3. **배포**: 같은 커밋으로 kista-api·kista-scheduler·kista-trading 세 role을 함께 교체한다. `deploy/hooks/pre-apply.sh`가 고아 row를 fail-closed로 차단하면 2번으로 돌아간다.
4. **교체 직후 재조회**: 1번 쿼리 재실행. blue/green 겹침 동안 구 kista-api가 hook 이후에 쓴 구 FQCN row가 있으면 2번 절차로 정리한다.
5. **확인**: `docker logs`(CLAUDE.md "API 운영 로그")에서 `ClassNotFoundException`·EPR 재발행 오류가 없고, 두 스키마 미완료 row가 0인지.
6. **롤백**: 구 이미지로 되돌리면 신 FQCN 미완료 row가 반대로 고아가 된다. 1→2→3→4를 역방향으로 수행한다.

## 실행 모델 가이드

| Task | 성격 | 구현 모델 | 리뷰 모델 |
|---|---|---|---|
| 0 | 판단(빈 규칙 판정, 기준선) | opus | sonnet |
| 1, 3, 5 | 기계적(스크립트) | sonnet | sonnet |
| 2, 4, 6 | 판단(자동구성·부팅·Modulith) | opus | opus |
| 7 | 판단(규칙 설계) | sonnet | sonnet |
| 8 | 문서 | sonnet | 생략(문서 전용) |
| 9 | 검증 | sonnet | — |
