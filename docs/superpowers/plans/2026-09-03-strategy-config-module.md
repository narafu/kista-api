# strategy-config 신모듈 선언 + 4개 모듈 순환 해소 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 얇아진 `Strategy` 애그리게이트로 `com.kista.strategyconfig` 모듈을 CLOSED 선언하고, 실측으로 발견한 4개 모듈 순환(admin/user/broker/trading, trading은 notify까지 연쇄)을 own-type 역전 포트로 해소한다.

**Architecture:** 순서 원칙 — `Strategy`가 아직 레거시 OPEN인 상태에서 역방향 엣지(admin/user/broker/trading+notify) 4건을 먼저 끊고 각각 좁은 테스트로 검증한 뒤, 마지막에 물리 이동 + `@ApplicationModule(CLOSED)` 선언 + `ApplicationModules.verify()`. trading 쪽 own-type(`StrategyRef`)은 신규 파일이라 먼저 정의하고, trading 내부 39개 파일을 스크립트로 일괄 치환한다(로직 변경 없음 — subproject A/B와 동일한 "이동/치환 → 컴파일 → 좁은 테스트 → 커밋" 사이클). 새로 만드는 strategy-config 소유 파일(포트·리스너)은 legacy 위치를 거치지 않고 처음부터 `com.kista.strategyconfig` 패키지에 놓는다 — 물리 이동(Task 10)은 기존 `Strategy`/`StrategyPort`/`StrategyUseCase` 등 레거시 파일에만 해당된다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5, Mockito, Python(파일 일괄 치환 스크립트 — 이 환경 BSD sed는 `\|` alternation을 리터럴 취급해 조용히 no-op하므로 사용 금지, subproject A/B에서 검증된 python 스크립트 패턴 재사용)

**Spec:** `docs/superpowers/specs/2026-09-03-strategy-config-module-design.md`

## Global Constraints

- 커밋 전 검토자 검수 필수(전역 CLAUDE.md 규칙) — 각 태스크 커밋 전 diff를 서브에이전트 리뷰어 또는 자체 검토로 확인한다.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit, 끝에 `Co-Authored-By`/`Claude-Session` 트레일러 포함.
- 전체 `./gradlew test`는 Task 11 완료 시 최종 1회만(전역 CLAUDE.md 규칙) — 그 전 태스크들은 관련 범위로 좁힌 `--tests` 실행으로 검증.
- `StrategyEntity`의 `@SQLRestriction("deleted_at IS NULL")`이 native/JPQL 쿼리에 자동 적용되지 않는 지점(`softDeleteByUserId` 등 이미 명시적으로 `deleted_at IS NULL` 조건을 단 쿼리) — 물리 이동 시 쿼리 문자열 변경 금지.
- 이동 대상 테이블(`strategy`)은 `kista` 스키마 — Flyway/DDL 변경 없음, 패키지 이동만.
- `\bStrategy\b` 워드바운더리 정규식만 사용 — `StrategyCycle`/`StrategyVersion`/`StrategyType`/`StrategyTicker`/`StrategyStatus`/`StrategyCycleSeedType`/`StrategyVrDetail`/`StrategyInfiniteDetail`/`StrategyCreationSettings`/`StrategyFieldSettings`/`StrategyCreationResolver(s)`/`StrategyDefaults` 등은 절대 매칭되면 안 됨(단어 뒤 문자가 word char라 경계 불성립 — 안전).

---

## File Structure (신설/이동 대상 요약)

```
com.kista.sharedkernel/
  StrategyDefaults.java                    ← 신규 (Task 2)

com.kista.trading.domain.model/
  StrategyRef.java                         ← 신규 (Task 6)

com.kista.trading.application.port.output/  (기존 9개 → 11개)
  StrategyLookupPort.java                  ← 신규 (Task 6)
  StrategyPausePort.java                   ← 신규 (Task 6)

com.kista.strategyconfig/                  ← 신설 시작 (Task 3부터 파일이 생기지만 @ApplicationModule 선언은 Task 11)
  application/port/output/
    StrategyCreationPolicyPort.java        ← 신규 (Task 3)
  application/service/
    StrategyLookupAdapter.java             ← 신규 (Task 6) — StrategyLookupPort/StrategyPausePort 구현
    ActiveStrategyCountAdapter.java        ← 신규 (Task 4) — ActiveStrategyCountPort 구현
    StrategyUserCascadeListener.java       ← 신규 (Task 4) — UserDeletedEvent 구독
  domain/model/                            ← Task 10에서 Strategy/StrategyDetail/RegisterStrategyCommand/UpdateStrategyCommand/StrategySeedPreview 이동
  application/usecase/                     ← Task 10에서 StrategyUseCase 이동
  application/port/output/                 ← Task 10에서 StrategyPort 이동
  application/service/                     ← Task 10에서 StrategyService/AccountCascadeListener 이동
  adapter/out/persistence/                 ← Task 10에서 StrategyEntity/StrategyJpaRepository/StrategyPersistenceAdapter/PersistenceSupport 이동

com.kista.user.application.port.output/
  ActiveStrategyCountPort.java             ← 신규 (Task 4)

com.kista.broker.application.port.output/
  MockSimulationDataPort.java              ← Modify (Task 5, 메서드 1개 추가)
com.kista.broker.domain.model/
  StrategyRefLite.java                     ← 신규 (Task 5)

com.kista.admin.application.service/
  RuntimeSettingsService.java              ← Modify (Task 3, StrategyCreationPolicyPort 구현 추가)

com.kista.trading.domain.strategy/
  StrategyCreationRequest.java             ← 신규 (Task 9)
```

---

## Task 1: 파킹된 minor 6건 정리 (B단계 리뷰 이월)

**Files:**
- Modify: `src/main/java/com/kista/trading/application/usecase/VrStrategyDetailUseCase.java`
- Modify: `src/main/java/com/kista/trading/application/service/VrStrategyLifecycle.java`
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java:354-355`
- Modify: `src/test/java/com/kista/trading/application/service/VrStrategyLifecycleTest.java:65-66,83-84`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/TradingCycleResponse.java:6,78`
- Modify: `docs/agents/constraints.md` (3곳: Strategy 4종 dotted 표기 통일, 날짜 오기재 정정, VrSummary "nested" 서술 갱신)

**Interfaces:** 없음(순수 정리, 다른 태스크가 소비할 신규 산출물 없음)

- [ ] **Step 1: `VrStrategyDetailUseCase.saveInitialCycleDetail`에서 미사용 파라미터 `initialUsdDeposit` 제거**

`src/main/java/com/kista/trading/application/usecase/VrStrategyDetailUseCase.java`에서:
```java
    // 등록 시점(경과 0주) 스냅샷 — gradientAt(0)/poolLimitRateAt(0)은 각각 initialGradient/initialPoolLimitRate와 동일
    StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                                  BigDecimal initialValue, StrategyVrDetail vrDetail);
```
→
```java
    // 등록 시점(경과 0주) 스냅샷 — gradientAt(0)/poolLimitRateAt(0)은 각각 initialGradient/initialPoolLimitRate와 동일
    StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialValue, StrategyVrDetail vrDetail);
```

- [ ] **Step 2: `VrStrategyLifecycle.saveInitialCycleDetail` 구현체 동기화**

`src/main/java/com/kista/trading/application/service/VrStrategyLifecycle.java`에서:
```java
    @Override
    public StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                                 BigDecimal initialValue, StrategyVrDetail vrDetail) {
        BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
        return strategyCycleVrPort.save(
                new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradientAt(0), vrDetail.poolLimitRateAt(0)));
    }
```
→
```java
    @Override
    public StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialValue, StrategyVrDetail vrDetail) {
        BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
        return strategyCycleVrPort.save(
                new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradientAt(0), vrDetail.poolLimitRateAt(0)));
    }
```

- [ ] **Step 3: 호출부 `StrategyService.saveInitialCycleAndPosition` 인자 정리**

`src/main/java/com/kista/application/service/strategy/StrategyService.java:353-355`에서:
```java
            StrategyCycleVrDetail savedCycleVr = vrStrategyLifecycle.saveInitialCycleDetail(
                    cycle.id(), normalizedInitialUsdDeposit, vrValue, vrDetail);
            return new InitialCycleResult(cycle, initialPosition, savedCycleVr);
```
→
```java
            StrategyCycleVrDetail savedCycleVr = vrStrategyLifecycle.saveInitialCycleDetail(
                    cycle.id(), vrValue, vrDetail);
            return new InitialCycleResult(cycle, initialPosition, savedCycleVr);
```
(`normalizedInitialUsdDeposit` 변수 자체는 `saveInitialCycleAndPosition` 내 다른 곳에서도 쓰이므로 삭제하지 않는다 — 이 호출 인자에서만 제거)

- [ ] **Step 4: 테스트 두 호출부 인자 정리**

`src/test/java/com/kista/trading/application/service/VrStrategyLifecycleTest.java:65-66`에서:
```java
        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, new BigDecimal("1000"), new BigDecimal("3000"), vrDetail);
```
→
```java
        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, new BigDecimal("3000"), vrDetail);
```

`:83-84`에서:
```java
        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, BigDecimal.ZERO, null, vrDetail);
```
→
```java
        StrategyCycleVrDetail result = vrStrategyLifecycle.saveInitialCycleDetail(
                cycleId, null, vrDetail);
```

- [ ] **Step 5: `TradingCycleResponse.java` 죽은 import + 낡은 주석 정리**

`src/main/java/com/kista/adapter/in/web/dto/TradingCycleResponse.java:6`에서 아래 줄 삭제(사용처 없음 — 파일 내 참조는 전부 fully-qualified name 또는 nested `VrSummary`):
```java
import com.kista.trading.domain.model.VrSummary;
```

`:78`에서:
```java
        // StrategyDetail.VrSummary → 응답 DTO 변환
```
→
```java
        // trading 소유 VrSummary(com.kista.trading.domain.model.VrSummary) → 응답 DTO 변환
```

- [ ] **Step 6: `constraints.md` 3곳 정정**

`docs/agents/constraints.md`의 "Account ↔ Strategy 분리" 절에서 `Strategy.Ticker/Type/Status/CycleSeedType` dotted 표기를 개명된 이름으로 통일:
```
- `Strategy`: `Type`/`Status`/`Ticker`/`CycleSeedType` — **이관 완료(2026-09-03, 커밋 `a81e76eb`)**: ...
```
→
```
- `Strategy`: `StrategyType`/`StrategyStatus`/`StrategyTicker`/`StrategyCycleSeedType` — **이관 완료(2026-09-02, 커밋 `a81e76eb`)**: ...
```
(날짜도 실제 커밋 날짜 2026-09-02로 정정 — `git log -1 --format=%ad --date=format:%Y-%m-%d a81e76eb` 확인 완료)

"VR 전략 패턴" 절 근처(constraints.md 102-104줄 부근)에서 `VrSummary`가 여전히 "nested"로 서술된 부분을 찾아 아래처럼 갱신:
```
- `StrategyDetail`: 최신 사이클·활성 버전·최신 포지션을 합쳐 만드는 응답 조립 DTO(`StrategyService.toDetail()`), `VrSummary` nested(VR 외 null)
```
→
```
- `StrategyDetail`: 최신 사이클·활성 버전·최신 포지션을 합쳐 만드는 응답 조립 DTO(`StrategyService.toDetail()`), `vr` 필드는 top-level `com.kista.trading.domain.model.VrSummary`(B단계에서 승격, VR 외 null)
- 설정 이력 계층: `StrategyVersion`(버전 부모) → `StrategyInfiniteDetail`(divisionCount) / `StrategyVrDetail`(...) — 이 셋은 B단계(2026-09-02~03)에서 `com.kista.trading.domain.model`로 이관됐다(trading "domain" NamedInterface 공개). `Strategy` 애그리게이트 자체는 여전히 레거시 위치(서브프로젝트 C 대상)
```
(정확한 삽입 위치는 파일을 Read해서 기존 "설정 이력 계층" 문장과 중복되지 않게 자연스럽게 병합할 것 — 이미 유사 문장이 있으면 갱신만 하고 중복 추가하지 않는다)

- [ ] **Step 7: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.trading.application.service.VrStrategyLifecycleTest' --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.adapter.in.web.TradingCycleControllerTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL` 전부.

- [ ] **Step 8: Commit**

```bash
git add -A -- src/main/java src/test/java docs/agents/constraints.md
git commit -m "$(cat <<'EOF'
docs(modulith): strategy-config C단계 착수 전 B단계 파킹 항목 정리

VrStrategyDetailUseCase.saveInitialCycleDetail의 미사용 파라미터
initialUsdDeposit 제거, TradingCycleResponse.java 죽은 import·낡은
주석 정리, constraints.md의 Strategy 4종 dotted 표기·날짜 오기재·
VrSummary nested 서술 정정. 로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

(파킹 항목 3번 — `StrategyServiceTest`의 `buildSummary` any() 매처 스텁 회귀탐지력 약화 — 은 `VrStrategyLifecycleTest`가 포뮬러를 독립 커버 중이라 스킵한다. 별도 작업 불필요)

---

## Task 2: `Strategy.DEFAULT_DIVISION_COUNT` sharedkernel 추출

**Files:**
- Create: `src/main/java/com/kista/sharedkernel/StrategyDefaults.java`
- Modify: `src/main/java/com/kista/domain/model/strategy/Strategy.java` (상수 필드 삭제)
- Modify: `src/main/java/com/kista/admin/domain/model/RuntimeSettings.java:4,44`
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java:501,560` (+import)
- Modify: `src/main/java/com/kista/trading/application/service/CycleRotationService.java:75` (+import)
- Modify: `src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java:80` (+import)
- Modify: `src/main/java/com/kista/trading/application/service/CycleOrderComputer.java:121,125` (+import)
- Modify: `src/main/java/com/kista/trading/domain/strategy/InfiniteCycleOrderStrategy.java:138,154` (+import, `Strategy` import 있으면 그대로 유지 — 다른 용도로 쓰는지 별도 확인)
- Modify: `src/main/java/com/kista/stats/domain/backtest/BacktestEngine.java:487` (+import)
- Modify: `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java:1585` (+import)

**Interfaces:**
- Produces: `com.kista.sharedkernel.StrategyDefaults.DEFAULT_DIVISION_COUNT`(int, 값 20) — Task 9(리졸버 축소)와 이후 모든 소비자가 이 상수를 사용한다.

- [ ] **Step 1: `StrategyDefaults.java` 신설**

```java
package com.kista.sharedkernel;

// Strategy 애그리게이트가 소유하던 기본값 상수 — PRIVACY/VR처럼 분할 수 설정이 없는 전략 타입의 고정 분할 수,
// admin(RuntimeSettings)·trading(리졸버/포지션 계산)·stats(BacktestEngine)가 공통으로 참조해 sharedkernel로 추출했다.
public final class StrategyDefaults {

    public static final int DEFAULT_DIVISION_COUNT = 20;

    private StrategyDefaults() {}
}
```

- [ ] **Step 2: 9개 소비 지점 일괄 치환 스크립트**

```python
import re

files = [
    "src/main/java/com/kista/admin/domain/model/RuntimeSettings.java",
    "src/main/java/com/kista/application/service/strategy/StrategyService.java",
    "src/main/java/com/kista/trading/application/service/CycleRotationService.java",
    "src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java",
    "src/main/java/com/kista/trading/application/service/CycleOrderComputer.java",
    "src/main/java/com/kista/trading/domain/strategy/InfiniteCycleOrderStrategy.java",
    "src/main/java/com/kista/stats/domain/backtest/BacktestEngine.java",
    "src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java",
]

for path in files:
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    new_content = re.sub(r"\bStrategy\.DEFAULT_DIVISION_COUNT\b", "StrategyDefaults.DEFAULT_DIVISION_COUNT", content)
    assert new_content != content, f"no occurrence replaced in {path}"
    if "import com.kista.sharedkernel.StrategyDefaults;" not in new_content:
        # 마지막 sharedkernel import 뒤에 삽입 (없으면 첫 com.kista import 뒤)
        lines = new_content.split("\n")
        insert_at = None
        for i, line in enumerate(lines):
            if line.startswith("import com.kista.sharedkernel."):
                insert_at = i + 1
        if insert_at is None:
            for i, line in enumerate(lines):
                if line.startswith("import com.kista."):
                    insert_at = i + 1
                    break
        lines.insert(insert_at, "import com.kista.sharedkernel.StrategyDefaults;")
        new_content = "\n".join(lines)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new_content)

print(f"updated {len(files)} files")
```

Run: `python3 <스크립트 경로>`
Expected: `updated 8 files` (assert가 실패하면 해당 파일에 패턴이 없다는 뜻 — 즉시 조사)

- [ ] **Step 3: `Strategy.java`에서 상수 필드 삭제**

`src/main/java/com/kista/domain/model/strategy/Strategy.java`에서:
```java
    public static final int DEFAULT_DIVISION_COUNT = 20;

```
삭제(빈 줄 포함).

- [ ] **Step 4: 잔존 참조 확인**

```bash
grep -rn "Strategy\.DEFAULT_DIVISION_COUNT" src/main/java src/test/java
```
Expected: 결과 없음(빈 출력).

- [ ] **Step 5: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.admin.*' --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.trading.application.service.CycleRotationServiceTest' --tests 'com.kista.trading.application.service.CyclePositionPersistorTest' --tests 'com.kista.trading.application.service.CycleOrderComputerTest' --tests 'com.kista.trading.domain.strategy.InfiniteCycleOrderStrategyTest' --tests 'com.kista.stats.application.service.BacktestServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL` (테스트 클래스명이 실제와 다르면 `find src/test -iname '*CycleOrderComputer*'` 등으로 확인 후 정확한 이름으로 재실행)

- [ ] **Step 6: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): Strategy.DEFAULT_DIVISION_COUNT sharedkernel 추출

strategy-config 이전 서브프로젝트 C 1/9 — admin(RuntimeSettings)·
trading(리졸버·포지션 계산 5개 파일)·stats(BacktestEngine)가 Strategy
전체를 import하는 유일한 이유였던 상수를 com.kista.sharedkernel.
StrategyDefaults로 추출한다. 이 소비자들은 이제 Strategy 애그리게이트
자체를 몰라도 된다 — strategy-config↔trading/admin/stats 결합면을
좁히는 선행 작업. 로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 3: admin↔strategy-config 순환 해소 — `StrategyCreationPolicyPort`

**Files:**
- Create: `src/main/java/com/kista/strategyconfig/application/port/output/StrategyCreationPolicyPort.java`
- Modify: `src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java`
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java` (필드 삭제 + 매핑 메서드 삭제 + `resolveCreationSettings` 교체)
- Modify: `src/test/java/com/kista/admin/application/service/RuntimeSettingsServiceTest.java` (신규 메서드 테스트 추가)
- Modify: `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java` (mock 교체)

**Interfaces:**
- Consumes: `com.kista.trading.domain.strategy.StrategyCreationSettings`(기존, trading "domain" NamedInterface), `com.kista.sharedkernel.StrategyType`(기존)
- Produces: `com.kista.strategyconfig.application.port.output.StrategyCreationPolicyPort` — `Optional<com.kista.trading.domain.strategy.StrategyCreationSettings> find(StrategyType type)`. Task 9/10이 이 포트를 그대로 소비한다.

- [ ] **Step 1: `StrategyCreationPolicyPort.java` 신설**

```java
package com.kista.strategyconfig.application.port.output;

import com.kista.sharedkernel.StrategyType;
import com.kista.trading.domain.strategy.StrategyCreationSettings;

import java.util.Optional;

// 전략 등록 시 타입별 생성 정책(활성화 여부·필드 허용값) 조회 — strategy-config가 admin의 RuntimeSettingsPort를
// 직접 참조하던 것을 own-type 포트 역전으로 해소(user ApprovalPolicyPort/account BrokerEnabledPort와 동일 패턴,
// 8번째 인스턴스). admin의 RuntimeSettingsService가 구현하며, admin 내부 타입을 이미 trading 소유
// StrategyCreationSettings로 매핑해 반환한다 — strategy-config는 admin을 전혀 참조하지 않는다.
public interface StrategyCreationPolicyPort {
    Optional<StrategyCreationSettings> find(StrategyType type);
}
```

- [ ] **Step 2: `RuntimeSettingsService`가 포트 구현 — 매핑 로직 이관**

`src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java`의 import 블록에 추가:
```java
import com.kista.strategyconfig.application.port.output.StrategyCreationPolicyPort;
```
클래스 선언 교체:
```java
class RuntimeSettingsService implements RuntimeSettingsUseCase, AdminSettingsUseCase, ApprovalPolicyPort, BrokerEnabledPort {
```
→
```java
class RuntimeSettingsService implements RuntimeSettingsUseCase, AdminSettingsUseCase, ApprovalPolicyPort, BrokerEnabledPort,
        StrategyCreationPolicyPort {
```

클래스 끝(`enabled(Broker broker)` 메서드 뒤, `updateSettings` 앞)에 아래 메서드 추가 — `StrategyService.toTradingSettings`/`mapField`/`mapRecurringField` 3개를 그대로 옮긴 것(로직 무변경, admin→trading 매핑이라 admin의 기존 forward 의존 그대로 사용):
```java
    // strategy-config가 소비하는 own-type 포트 구현 — admin 내부 StrategyCreationSettings를
    // trading 소유 타입으로 매핑해 반환한다(strategy-config↔admin 순환 해소).
    @Override
    @Transactional(readOnly = true)
    public Optional<com.kista.trading.domain.strategy.StrategyCreationSettings> find(StrategyType type) {
        com.kista.admin.domain.model.StrategyCreationSettings settings = settingsPort.load().strategies().get(type);
        return Optional.ofNullable(settings).map(RuntimeSettingsService::toTradingSettings);
    }

    // admin StrategyCreationSettings → trading 자체 타입. 필드 구조 동일, RecurringMode만 valueOf(name()).
    private static com.kista.trading.domain.strategy.StrategyCreationSettings toTradingSettings(
            com.kista.admin.domain.model.StrategyCreationSettings s) {
        return new com.kista.trading.domain.strategy.StrategyCreationSettings(
                s.enabled(),
                mapField(s.ticker()),
                mapField(s.divisionCount()),
                mapRecurringField(s.recurringMode()),
                mapField(s.bandWidth()),
                mapField(s.intervalWeeks()));
    }

    // 동일 원소 타입 필드는 그대로 재래핑 — trading StrategyFieldSettings 생성자가 List.copyOf로 불변 복제한다.
    private static <T> com.kista.trading.domain.strategy.StrategyFieldSettings<T> mapField(
            com.kista.admin.domain.model.StrategyFieldSettings<T> f) {
        if (f == null) return null;
        return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
                f.customizable(), f.allowedValues(), f.defaultValue());
    }

    // RecurringMode는 상수명 byte-identical이라 valueOf(name())으로 trading enum에 매핑한다.
    private static com.kista.trading.domain.strategy.StrategyFieldSettings<com.kista.trading.domain.strategy.RecurringMode> mapRecurringField(
            com.kista.admin.domain.model.StrategyFieldSettings<com.kista.admin.domain.model.RecurringMode> f) {
        if (f == null) return null;
        return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
                f.customizable(),
                f.allowedValues().stream().map(m -> com.kista.trading.domain.strategy.RecurringMode.valueOf(m.name())).toList(),
                com.kista.trading.domain.strategy.RecurringMode.valueOf(f.defaultValue().name()));
    }
```
(`Optional` import가 이미 있는지 확인 — 없으면 `import java.util.Optional;` 추가)

- [ ] **Step 3: `StrategyService`에서 admin 직접 참조 삭제**

`src/main/java/com/kista/application/service/strategy/StrategyService.java`에서 import 블록:
```java
import com.kista.admin.domain.model.StrategyCreationSettings;
```
```java
import com.kista.admin.application.port.output.RuntimeSettingsPort;
```
두 줄 삭제 후 추가:
```java
import com.kista.strategyconfig.application.port.output.StrategyCreationPolicyPort;
```

필드 교체:
```java
    private final RuntimeSettingsPort runtimeSettingsPort;          // 신규 전략 생성 허용값·기본값 조회
```
→
```java
    private final StrategyCreationPolicyPort strategyCreationPolicyPort; // 신규 전략 생성 허용값·기본값 조회
```

`resolveCreationSettings` 메서드 전체 교체:
```java
    // 등록 시점에만 런타임 생성 정책을 적용해 기존 전략 흐름과 설정 조회를 분리한다.
    // 전략 타입별 필드 해석은 CycleOrderStrategy와 동일한 capability 패턴(StrategyCreationResolvers)에 위임한다.
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        // 레거시 런타임 설정 타입 조회 (Task 2 이후: com.kista.admin.domain.model.StrategyCreationSettings)
        StrategyCreationSettings settings = runtimeSettingsPort.load().strategies().get(cmd.type());
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        // 모듈 경계 매핑 — resolver(trading)는 자체 타입만 받는다
        return creationResolvers.of(cmd.type()).resolve(cmd, toTradingSettings(settings));
    }

    // 레거시(추후 admin) StrategyCreationSettings → trading 자체 타입. 필드 구조 동일, RecurringMode만 valueOf(name()).
    private static com.kista.trading.domain.strategy.StrategyCreationSettings toTradingSettings(StrategyCreationSettings s) {
        return new com.kista.trading.domain.strategy.StrategyCreationSettings(
                s.enabled(),
                mapField(s.ticker()),
                mapField(s.divisionCount()),
                mapRecurringField(s.recurringMode()),
                mapField(s.bandWidth()),
                mapField(s.intervalWeeks()));
    }

    // 동일 원소 타입 필드는 그대로 재래핑 — trading StrategyFieldSettings 생성자가 List.copyOf로 불변 복제한다.
    private static <T> com.kista.trading.domain.strategy.StrategyFieldSettings<T> mapField(
            com.kista.admin.domain.model.StrategyFieldSettings<T> f) {
        if (f == null) return null;
        return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
                f.customizable(), f.allowedValues(), f.defaultValue());
    }

    // RecurringMode는 상수명 byte-identical이라 valueOf(name())으로 trading enum에 매핑한다.
    private static com.kista.trading.domain.strategy.StrategyFieldSettings<com.kista.trading.domain.strategy.RecurringMode> mapRecurringField(
            com.kista.admin.domain.model.StrategyFieldSettings<com.kista.admin.domain.model.RecurringMode> f) {
        if (f == null) return null;
        return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
                f.customizable(),
                f.allowedValues().stream().map(m -> com.kista.trading.domain.strategy.RecurringMode.valueOf(m.name())).toList(),
                com.kista.trading.domain.strategy.RecurringMode.valueOf(f.defaultValue().name()));
    }
```
→
```java
    // 등록 시점에만 런타임 생성 정책을 적용해 기존 전략 흐름과 설정 조회를 분리한다.
    // 전략 타입별 필드 해석은 CycleOrderStrategy와 동일한 capability 패턴(StrategyCreationResolvers)에 위임한다.
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        com.kista.trading.domain.strategy.StrategyCreationSettings settings = strategyCreationPolicyPort.find(cmd.type())
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 전략 생성 정책: " + cmd.type()));
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        return creationResolvers.of(cmd.type()).resolve(cmd, settings);
    }
```

- [ ] **Step 4: 테스트 교체**

`src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`에서 `@Mock RuntimeSettingsPort runtimeSettingsPort` 필드와 이를 참조하는 모든 stub(`when(runtimeSettingsPort.load())...`)을 `@Mock StrategyCreationPolicyPort strategyCreationPolicyPort` + `when(strategyCreationPolicyPort.find(StrategyType.XXX)).thenReturn(Optional.of(...))` 형태로 교체한다. 기존에 admin `StrategyCreationSettings`로 만들던 테스트 픽스처를 trading `StrategyCreationSettings`(`com.kista.trading.domain.strategy.StrategyCreationSettings`)로 직접 만들도록 바꾼다(이전엔 서비스가 매핑했지만 이제 포트가 매핑을 마친 값을 반환하므로 admin 타입을 몰라도 됨).

`src/test/java/com/kista/admin/application/service/RuntimeSettingsServiceTest.java`에 새 테스트 추가:
```java
    @Test
    @DisplayName("find() — 활성 전략 타입의 설정을 trading 소유 타입으로 매핑해 반환한다")
    void find_mapsAdminSettingsToTradingType() {
        RuntimeSettings settings = RuntimeSettings.defaults();
        when(settingsPort.load()).thenReturn(settings);

        Optional<com.kista.trading.domain.strategy.StrategyCreationSettings> result =
                runtimeSettingsService.find(StrategyType.INFINITE);

        assertThat(result).isPresent();
        assertThat(result.get().enabled()).isTrue();
        assertThat(result.get().divisionCount().defaultValue()).isEqualTo(StrategyDefaults.DEFAULT_DIVISION_COUNT);
    }
```
(`settingsPort`/`runtimeSettingsService` 필드명은 기존 테스트 클래스의 실제 필드명을 Read로 확인 후 맞출 것 — `import com.kista.sharedkernel.StrategyDefaults;`, `import com.kista.sharedkernel.StrategyType;`, `import java.util.Optional;` 필요 시 추가)

- [ ] **Step 5: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.admin.application.service.RuntimeSettingsServiceTest' --tests 'com.kista.application.service.strategy.StrategyServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): admin↔strategy-config 순환 해소 — StrategyCreationPolicyPort

strategy-config가 admin의 RuntimeSettingsPort를 직접 참조하던 것을
own-type 포트 역전(StrategyCreationPolicyPort, strategy-config 정의·
admin RuntimeSettingsService 구현)으로 끊는다(user ApprovalPolicyPort/
account BrokerEnabledPort와 동일 패턴, 8번째 인스턴스). admin→trading
매핑 로직(toTradingSettings 등 3개 메서드)을 admin으로 옮겨 strategy-
config는 admin을 전혀 참조하지 않는다. 로직 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 4: user↔strategy-config 순환 해소

**Files:**
- Create: `src/main/java/com/kista/user/application/port/output/ActiveStrategyCountPort.java`
- Create: `src/main/java/com/kista/strategyconfig/application/service/ActiveStrategyCountAdapter.java`
- Create: `src/main/java/com/kista/strategyconfig/application/service/StrategyUserCascadeListener.java`
- Modify: `src/main/java/com/kista/user/application/service/UserCascadeDeleter.java`
- Modify: `src/main/java/com/kista/user/application/service/UserSettingsService.java`
- Modify: `src/test/java/com/kista/user/application/service/UserCascadeDeleterTest.java`
- Modify: `src/test/java/com/kista/user/application/service/UserSettingsServiceTest.java`
- Create: `src/test/java/com/kista/strategyconfig/application/service/ActiveStrategyCountAdapterTest.java`
- Create: `src/test/java/com/kista/strategyconfig/application/service/StrategyUserCascadeListenerTest.java`

**Interfaces:**
- Consumes: `com.kista.account.application.port.output.AccountPort`(기존), `com.kista.application.port.output.StrategyPort`(레거시, Task 10 전까지 이 경로), `com.kista.user.application.event.UserDeletedEvent`(기존 user "event")
- Produces: `com.kista.user.application.port.output.ActiveStrategyCountPort` — `long countActiveByUserId(UUID userId)`

- [ ] **Step 1: `ActiveStrategyCountPort.java` 신설**

```java
package com.kista.user.application.port.output;

import java.util.UUID;

// 사용자의 전 계좌 ACTIVE 전략 총 개수 — 잔고검증 OFF→ON/ON→OFF 전환 경고 로그용.
// user가 strategy-config의 StrategyPort를 직접 참조하던 것을 own-type 포트 역전으로 해소
// (account BrokerEnabledPort/admin ApprovalPolicyPort와 동일 패턴). strategy-config가 구현한다.
public interface ActiveStrategyCountPort {
    long countActiveByUserId(UUID userId);
}
```

- [ ] **Step 2: `ActiveStrategyCountAdapter.java` 신설 (strategy-config 소유 구현)**

```java
package com.kista.strategyconfig.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.application.port.output.StrategyPort;
import com.kista.user.application.port.output.ActiveStrategyCountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// ActiveStrategyCountPort 구현 — user↔strategy-config 순환 해소 산물.
// strategy-config는 account에 이미 정상 forward 의존이 있어(등록 시 계좌 조회) AccountPort 조합이 문제없다.
@Component
@RequiredArgsConstructor
class ActiveStrategyCountAdapter implements ActiveStrategyCountPort {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @Override
    public long countActiveByUserId(UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .map(Account::id)
                .flatMap(accountId -> strategyPort.findByAccountId(accountId).stream())
                .filter(strategy -> strategy.isActive())
                .count();
    }
}
```

- [ ] **Step 3: `StrategyUserCascadeListener.java` 신설 (탈퇴 cascade 자기 데이터 자기 삭제)**

```java
package com.kista.strategyconfig.application.service;

import com.kista.application.port.output.StrategyPort;
import com.kista.user.application.event.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — strategy-config 소유 데이터(strategy)를 독립적으로 soft-delete.
// UserCascadeDeleter가 StrategyPort를 직접 호출하던 것을 이벤트 구독으로 전환(user↔strategy-config 순환 해소).
// finance/trading의 기존 cascade 리스너와 동일 패턴 — 자기 데이터는 자기 모듈이 지운다.
@Component
@RequiredArgsConstructor
class StrategyUserCascadeListener {

    private final StrategyPort strategyPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        strategyPort.deleteByUserId(event.userId());
    }
}
```
(`UserDeletedEvent`의 실제 accessor 이름이 `userId()`인지 Read로 확인 — `com.kista.user.application.event.UserDeletedEvent` 파일을 열어 필드명을 정확히 맞출 것)

- [ ] **Step 4: `UserCascadeDeleter`에서 `StrategyPort` 직접 참조 삭제**

`src/main/java/com/kista/user/application/service/UserCascadeDeleter.java`에서:
```java
import com.kista.application.port.output.StrategyPort;
```
삭제. 필드:
```java
    private final StrategyPort strategyPort;
```
삭제. 호출부:
```java
        strategyPort.deleteByUserId(userId);
```
삭제(해당 줄만 — 주변 다른 cascade 호출은 그대로 유지).

- [ ] **Step 5: `UserSettingsService`에서 `StrategyPort`/`AccountPort` 직접 참조를 `ActiveStrategyCountPort`로 교체**

`src/main/java/com/kista/user/application/service/UserSettingsService.java` 전체를 아래로 교체:
```java
package com.kista.user.application.service;

import com.kista.sharedkernel.NotificationType;
import com.kista.user.domain.model.UserSettings;
import com.kista.user.application.usecase.GetUserSettingsQuery;
import com.kista.user.application.usecase.UpdateBalanceCheckUseCase;
import com.kista.user.application.usecase.UpdateNotificationPrefUseCase;
import com.kista.user.application.usecase.UpdateStrategySuggestionsUseCase;
import com.kista.user.application.port.output.ActiveStrategyCountPort;
import com.kista.user.application.port.output.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class UserSettingsService implements GetUserSettingsQuery, UpdateNotificationPrefUseCase, UpdateBalanceCheckUseCase,
        UpdateStrategySuggestionsUseCase {

    private final UserSettingsPort userSettingsPort;
    private final ActiveStrategyCountPort activeStrategyCountPort;

    @Override
    public UserSettings getByUserId(UUID userId) {
        // 저장된 설정이 없으면 기본값 반환 (balanceCheckEnabled=true, 빈 알림 prefs)
        return userSettingsPort.findOrDefault(userId);
    }

    @Override
    @Transactional
    public void update(UpdateNotificationPrefCommand command) {
        UserSettings current = getByUserId(command.userId());
        // 기존 prefs에 변경 항목만 덮어씀
        Map<NotificationType, Boolean> updatedPrefs = new HashMap<>(current.notificationPrefs());
        updatedPrefs.put(command.type(), command.enabled());
        userSettingsPort.save(current.withNotificationPrefs(updatedPrefs));
        log.info("알림 설정 변경: userId={}, type={}, enabled={}", command.userId(), command.type(), command.enabled());
    }

    @Override
    @Transactional
    public void update(UpdateBalanceCheckCommand command) {
        UserSettings current = getByUserId(command.userId());
        boolean previous = current.balanceCheckEnabled();
        userSettingsPort.save(current.withBalanceCheckEnabled(command.enabled()));
        log.info("잔고 검증 설정 변경: userId={}, {}→{}", command.userId(), previous, command.enabled());

        // 활성 전략 수 계산 — 잔고검증 전환 시 경고 로그 출력
        long activeCount = activeStrategyCountPort.countActiveByUserId(command.userId());
        if (!previous && command.enabled() && activeCount > 0) {
            // OFF→ON 전환: 활성 전략 존재 시 시드 초과 가능성 경고
            log.warn("[잔고검증 OFF→ON] userId={} — 활성 전략 {}개. 시드가 실잔고 초과 시 다음 사이클에서 PAUSED됩니다.", command.userId(), activeCount);
        }
        if (previous && !command.enabled()) {
            // ON→OFF 전환: KIS 주문 거부 가능성 경고 (APBK0988)
            log.warn("[잔고검증 ON→OFF] userId={} — 활성 전략 {}개. 실잔고 초과 시드로 재등록 시 KIS 주문 거부 가능.", command.userId(), activeCount);
        }
    }

    @Override
    @Transactional
    public void update(UpdateStrategySuggestionsCommand command) {
        UserSettings current = getByUserId(command.userId());
        userSettingsPort.save(current.withStrategySuggestions(command.suggestions()));
        log.info("운영전략 추천 목록 변경: userId={}, count={}", command.userId(), command.suggestions().size());
    }
}
```
(`countActiveStrategies` private 메서드 자체가 `ActiveStrategyCountAdapter`로 통째 이관됐으므로 삭제)

- [ ] **Step 6: 테스트 갱신**

`src/test/java/com/kista/user/application/service/UserCascadeDeleterTest.java`에서 `@Mock StrategyPort strategyPort` 필드·`verify(strategyPort).deleteByUserId(...)` 단언 삭제.

`src/test/java/com/kista/user/application/service/UserSettingsServiceTest.java`에서 `@Mock StrategyPort strategyPort`/`@Mock AccountPort accountPort` 필드를 `@Mock ActiveStrategyCountPort activeStrategyCountPort`로 교체하고, 기존 `when(strategyPort.findByAccountId(...))`/`when(accountPort.findByUserId(...))` stub을 `when(activeStrategyCountPort.countActiveByUserId(userId)).thenReturn(N)` 형태로 교체.

`src/test/java/com/kista/strategyconfig/application/service/ActiveStrategyCountAdapterTest.java` 신설(Mockito 표준 패턴 — `AccountPort`/`StrategyPort` mock, `countActiveByUserId`가 여러 계좌의 ACTIVE 전략만 합산하는지 검증):
```java
package com.kista.strategyconfig.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.application.port.output.StrategyPort;
import com.kista.domain.model.strategy.Strategy;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveStrategyCountAdapterTest {

    @Mock
    private AccountPort accountPort;
    @Mock
    private StrategyPort strategyPort;

    private ActiveStrategyCountAdapter adapter;

    @Test
    void countActiveByUserId_사용자_전_계좌의_ACTIVE_전략만_합산한다() {
        adapter = new ActiveStrategyCountAdapter(accountPort, strategyPort);
        UUID userId = UUID.randomUUID();
        UUID accountId1 = UUID.randomUUID();
        UUID accountId2 = UUID.randomUUID();
        when(accountPort.findByUserId(userId)).thenReturn(List.of(
                mockAccount(accountId1), mockAccount(accountId2)));
        when(strategyPort.findByAccountId(accountId1)).thenReturn(List.of(
                strategy(accountId1, StrategyStatus.ACTIVE), strategy(accountId1, StrategyStatus.PAUSED)));
        when(strategyPort.findByAccountId(accountId2)).thenReturn(List.of(
                strategy(accountId2, StrategyStatus.ACTIVE)));

        long count = adapter.countActiveByUserId(userId);

        assertThat(count).isEqualTo(2);
    }

    private Account mockAccount(UUID id) {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.id()).thenReturn(id);
        return account;
    }

    private Strategy strategy(UUID accountId, StrategyStatus status) {
        return new Strategy(UUID.randomUUID(), accountId, StrategyType.INFINITE, status,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }
}
```

`src/test/java/com/kista/strategyconfig/application/service/StrategyUserCascadeListenerTest.java` 신설:
```java
package com.kista.strategyconfig.application.service;

import com.kista.application.port.output.StrategyPort;
import com.kista.user.application.event.UserDeletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StrategyUserCascadeListenerTest {

    @Mock
    private StrategyPort strategyPort;

    @Test
    void onUserDeleted_사용자ID로_전략을_soft_delete한다() {
        StrategyUserCascadeListener listener = new StrategyUserCascadeListener(strategyPort);
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(strategyPort).deleteByUserId(userId);
    }
}
```
(`UserDeletedEvent` 생성자 인자가 `userId` 하나뿐인지 Read로 확인 — 다르면 실제 시그니처에 맞출 것)

- [ ] **Step 7: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.user.application.service.*' --tests 'com.kista.strategyconfig.application.service.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): user↔strategy-config 순환 해소

UserCascadeDeleter의 StrategyPort.deleteByUserId 직접 호출을
strategy-config 자체 UserDeletedEvent 리스너(StrategyUserCascadeListener)로
전환(finance/trading 기존 cascade 리스너와 동일 패턴). UserSettingsService의
전략 카운트 조회는 own-type 포트 역전(ActiveStrategyCountPort, user
정의·strategy-config 구현, 9번째 own-type 인스턴스)으로 대체 — user는
StrategyPort/AccountPort를 더 이상 직접 참조하지 않는다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 5: broker↔strategy-config 순환 해소

**Files:**
- Create: `src/main/java/com/kista/broker/domain/model/StrategyRefLite.java`
- Modify: `src/main/java/com/kista/broker/application/port/output/MockSimulationDataPort.java`
- Modify: `src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java`
- Modify: `src/main/java/com/kista/trading/adapter/out/MockSimulationDataAdapter.java`
- Modify: `src/test/java/com/kista/broker/adapter/out/mock/MockBrokerAdapterTest.java`
- Modify: 트레이딩 쪽 `MockSimulationDataAdapter` 테스트(파일명은 Read로 확인)

**Interfaces:**
- Consumes: `com.kista.application.port.output.StrategyPort`(레거시, trading의 `MockSimulationDataAdapter`가 소비 — Task 10 전까지 이 경로. Task 10 이후엔 `com.kista.strategyconfig.application.port.output.StrategyPort`로 import 경로만 바뀜)
- Produces: `com.kista.broker.application.port.output.MockSimulationDataPort.findStrategiesByAccountId(UUID): List<StrategyRefLite>`

- [ ] **Step 1: `StrategyRefLite.java` 신설 (broker 소유 초경량 뷰)**

```java
package com.kista.broker.domain.model;

import com.kista.sharedkernel.StrategyTicker;

import java.util.UUID;

// MockBrokerAdapter 전용 — 계좌+ticker → 전략 해석에 필요한 최소 필드만 담은 broker 소유 뷰
// (PlacedOrderView/PositionView와 동일 패턴). Strategy 전체가 아닌 id/ticker만 노출한다.
public record StrategyRefLite(UUID id, StrategyTicker ticker) {}
```

- [ ] **Step 2: `MockSimulationDataPort`에 메서드 추가**

`src/main/java/com/kista/broker/application/port/output/MockSimulationDataPort.java`의 import 블록에 추가:
```java
import com.kista.broker.domain.model.StrategyRefLite;
```
인터페이스 본문 끝에 추가:
```java

    // 계좌에 속한 전략 목록(id+ticker만) — MockBrokerAdapter가 계좌+ticker로 전략을 해석할 때 사용
    List<StrategyRefLite> findStrategiesByAccountId(UUID accountId);
```

- [ ] **Step 3: `MockBrokerAdapter`에서 `StrategyPort` 완전 제거**

`src/main/java/com/kista/broker/adapter/out/mock/MockBrokerAdapter.java`에서 import 블록:
```java
import com.kista.application.port.output.StrategyPort;
import com.kista.domain.model.strategy.Strategy;
```
삭제, 필드:
```java
    private final StrategyPort strategyPort;                     // 계좌+ticker → strategy 해석 (legacy 공개 포트)
```
삭제.

`resolveStrategy` 메서드:
```java
    private Strategy resolveStrategy(BrokerAccountRef account, StrategyTicker ticker) {
        return strategyPort.findByAccountId(account.id()).stream()
                .filter(s -> s.ticker() == ticker)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌에 해당 종목 전략이 없습니다: accountId=" + account.id() + ", ticker=" + ticker));
    }
```
→
```java
    private StrategyRefLite resolveStrategy(BrokerAccountRef account, StrategyTicker ticker) {
        return mockSimulationDataPort.findStrategiesByAccountId(account.id()).stream()
                .filter(s -> s.ticker() == ticker)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "모의계좌에 해당 종목 전략이 없습니다: accountId=" + account.id() + ", ticker=" + ticker));
    }
```
(반환 타입 `Strategy` → `StrategyRefLite`, import 추가 `import com.kista.broker.domain.model.StrategyRefLite;`)

`resolveLatestPosition`, `sumUsdDepositAcrossStrategies`, `getExecutions`, `getPresentBalance` 4곳에서 `Strategy strategy = resolveStrategy(...)`/`List<Strategy> strategies = strategyPort.findByAccountId(...)`로 선언된 지역변수 타입을 `StrategyRefLite`로 교체(`strategy.id()`/`strategy.ticker()` 호출부는 필드명 동일하므로 코드 변경 없음):
```java
    private BigDecimal sumUsdDepositAcrossStrategies(BrokerAccountRef account) {
        return strategyPort.findByAccountId(account.id()).stream()
```
→
```java
    private BigDecimal sumUsdDepositAcrossStrategies(BrokerAccountRef account) {
        return mockSimulationDataPort.findStrategiesByAccountId(account.id()).stream()
```
```java
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        List<Strategy> strategies = strategyPort.findByAccountId(account.id());
```
→
```java
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        List<StrategyRefLite> strategies = mockSimulationDataPort.findStrategiesByAccountId(account.id());
```
그리고 그 아래 `for (Strategy strategy : strategies) {` → `for (StrategyRefLite strategy : strategies) {`.

- [ ] **Step 4: trading `MockSimulationDataAdapter`가 신규 메서드 구현**

`src/main/java/com/kista/trading/adapter/out/MockSimulationDataAdapter.java`(정확한 경로는 `find src/main/java/com/kista/trading -iname 'MockSimulationDataAdapter.java'`로 확인)에 아래 메서드 추가 — `StrategyPort`(현재 `com.kista.application.port.output.StrategyPort`, Task 10 이후 `com.kista.strategyconfig.application.port.output.StrategyPort`) 주입이 이미 있으면 재사용, 없으면 생성자에 추가:
```java
    @Override
    public List<StrategyRefLite> findStrategiesByAccountId(UUID accountId) {
        return strategyPort.findByAccountId(accountId).stream()
                .map(s -> new StrategyRefLite(s.id(), s.ticker()))
                .toList();
    }
```
(`import com.kista.broker.domain.model.StrategyRefLite;` 추가. 이 파일이 이미 `StrategyPort`를 주입받고 있는지 먼저 Read로 확인 — 없다면 생성자 파라미터에 `StrategyPort strategyPort` 추가하고 Lombok `@RequiredArgsConstructor`가 처리하게 한다)

- [ ] **Step 5: 테스트 갱신**

`MockBrokerAdapterTest`에서 `@Mock StrategyPort strategyPort` → `@Mock MockSimulationDataPort mockSimulationDataPort`(이미 있으면 재사용) 기준으로 stub을 `when(mockSimulationDataPort.findStrategiesByAccountId(accountId)).thenReturn(List.of(new StrategyRefLite(id, ticker)))` 형태로 교체. trading의 `MockSimulationDataAdapter` 테스트에 `findStrategiesByAccountId` 신규 테스트 추가(Mockito 표준 패턴, `StrategyPort.findByAccountId` stub → `StrategyRefLite` 매핑 검증).

- [ ] **Step 6: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.broker.adapter.out.mock.*' --tests 'com.kista.trading.adapter.out.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): broker↔strategy-config 순환 해소

MockBrokerAdapter가 StrategyPort/Strategy를 직접 참조하던 것을 제거하고
기존 역전 포트 MockSimulationDataPort(broker 정의, trading 구현)에
findStrategiesByAccountId(broker 소유 초경량 뷰 StrategyRefLite 반환)를
추가했다. 신규 own-type 없이 기존 역전 패턴 확장만으로 해소.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 6: trading own-type — `StrategyRef`/`StrategyLookupPort`/`StrategyPausePort`

**Files:**
- Create: `src/main/java/com/kista/trading/domain/model/StrategyRef.java`
- Create: `src/main/java/com/kista/trading/application/port/output/StrategyLookupPort.java`
- Create: `src/main/java/com/kista/trading/application/port/output/StrategyPausePort.java`
- Create: `src/main/java/com/kista/strategyconfig/application/service/StrategyLookupAdapter.java`
- Create: `src/test/java/com/kista/strategyconfig/application/service/StrategyLookupAdapterTest.java`

**Interfaces:**
- Produces: `StrategyRef(UUID id, UUID accountId, StrategyType type, StrategyStatus status, StrategyTicker ticker, StrategyCycleSeedType cycleSeedType)` + `isActive()/isInfinite()/isPrivacy()/isVr()`. `StrategyLookupPort`(6개 조회 메서드), `StrategyPausePort.pause(UUID)`. Task 7이 이 셋을 소비한다.

- [ ] **Step 1: `StrategyRef.java` 신설**

```java
package com.kista.trading.domain.model;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// strategy-config 소유 Strategy의 trading own-type 복제(broker BrokerAccountRef와 동일 패턴) — trading의
// 스케쥴러·실행 코어가 상시 참조하는 6필드만 읽기 전용으로 노출한다. 쓰기(일시정지)는 StrategyPausePort가 담당하므로
// isPaused()/withStatus() 등 trading에서 쓰이지 않는 메서드는 포함하지 않는다(YAGNI).
public record StrategyRef(UUID id, UUID accountId, StrategyType type, StrategyStatus status,
                           StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {

    public boolean isActive() {
        return status == StrategyStatus.ACTIVE;
    }

    public boolean isInfinite() {
        return type == StrategyType.INFINITE;
    }

    public boolean isPrivacy() {
        return type == StrategyType.PRIVACY;
    }

    public boolean isVr() {
        return type == StrategyType.VR;
    }
}
```

- [ ] **Step 2: `StrategyLookupPort.java` 신설**

```java
package com.kista.trading.application.port.output;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.model.StrategyRef;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

// trading이 정의하고 strategy-config가 구현하는 읽기 전용 포트(own-type 역전) — trading↔strategy-config 순환 해소.
// 조회(Query)만 담당 — 쓰기는 StrategyPausePort로 분리(ISP, ApprovalPolicyPort/BrokerEnabledPort와 동일하게
// 단일 책임 narrow 포트 관례를 따른다).
public interface StrategyLookupPort {
    List<StrategyRef> findAllActive();
    List<StrategyRef> findByAccountId(UUID accountId);
    Optional<StrategyRef> findById(UUID id);

    default StrategyRef findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> new NoSuchElementException("전략을 찾을 수 없습니다: " + id));
    }

    StrategyTicker findTickerById(UUID id);
    Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> ids);
}
```

- [ ] **Step 3: `StrategyPausePort.java` 신설**

```java
package com.kista.trading.application.port.output;

import java.util.UUID;

// 시스템 자동 일시정지 전용(CycleRotationService — 사이클 재등록 실패 시) — StrategyUseCase.pause()의
// 소유권검증 경로와 무관한 별도 포트. 읽기(StrategyLookupPort)와 쓰기를 섞지 않는다(ISP).
public interface StrategyPausePort {
    void pause(UUID strategyId);
}
```

- [ ] **Step 4: `StrategyLookupAdapter.java` 신설 — 두 포트를 strategy-config가 구현**

```java
package com.kista.strategyconfig.application.service;

import com.kista.application.port.output.StrategyPort;
import com.kista.domain.model.strategy.Strategy;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.application.port.output.StrategyLookupPort;
import com.kista.trading.application.port.output.StrategyPausePort;
import com.kista.trading.domain.model.StrategyRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// trading의 StrategyLookupPort/StrategyPausePort 구현 — strategy-config 내부 StrategyPort를 감싸
// Strategy를 StrategyRef로 매핑해 반환한다. trading은 strategy-config를 전혀 참조하지 않는다.
@Component
@RequiredArgsConstructor
class StrategyLookupAdapter implements StrategyLookupPort, StrategyPausePort {

    private final StrategyPort strategyPort;

    @Override
    public List<StrategyRef> findAllActive() {
        return strategyPort.findAllActive().stream().map(StrategyLookupAdapter::toRef).toList();
    }

    @Override
    public List<StrategyRef> findByAccountId(UUID accountId) {
        return strategyPort.findByAccountId(accountId).stream().map(StrategyLookupAdapter::toRef).toList();
    }

    @Override
    public Optional<StrategyRef> findById(UUID id) {
        return strategyPort.findById(id).map(StrategyLookupAdapter::toRef);
    }

    @Override
    public StrategyTicker findTickerById(UUID id) {
        return strategyPort.findById(id).map(Strategy::ticker).orElse(null);
    }

    @Override
    public Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> ids) {
        return strategyPort.findTickersByIds(ids);
    }

    @Override
    public void pause(UUID strategyId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED));
    }

    private static StrategyRef toRef(Strategy s) {
        return new StrategyRef(s.id(), s.accountId(), s.type(), s.status(), s.ticker(), s.cycleSeedType());
    }
}
```

- [ ] **Step 5: `StrategyLookupAdapterTest.java` 신설**

```java
package com.kista.strategyconfig.application.service;

import com.kista.application.port.output.StrategyPort;
import com.kista.domain.model.strategy.Strategy;
import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.trading.domain.model.StrategyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyLookupAdapterTest {

    @Mock
    private StrategyPort strategyPort;

    private StrategyLookupAdapter adapter;

    @Test
    void findByIdOrThrow은_StrategyRef로_매핑해_반환한다() {
        adapter = new StrategyLookupAdapter(strategyPort);
        UUID id = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Strategy strategy = new Strategy(id, accountId, StrategyType.INFINITE, StrategyStatus.ACTIVE,
                StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        when(strategyPort.findById(id)).thenReturn(Optional.of(strategy));

        StrategyRef ref = adapter.findByIdOrThrow(id);

        assertThat(ref.id()).isEqualTo(id);
        assertThat(ref.accountId()).isEqualTo(accountId);
        assertThat(ref.isInfinite()).isTrue();
    }

    @Test
    void pause는_전략_상태를_PAUSED로_저장한다() {
        adapter = new StrategyLookupAdapter(strategyPort);
        UUID id = UUID.randomUUID();
        Strategy strategy = new Strategy(id, UUID.randomUUID(), StrategyType.VR, StrategyStatus.ACTIVE,
                StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(id)).thenReturn(strategy);
        when(strategyPort.save(any(Strategy.class))).thenAnswer(inv -> inv.getArgument(0));

        adapter.pause(id);

        verify(strategyPort).save(argThatPaused());
    }

    private static Strategy argThatPaused() {
        return org.mockito.ArgumentMatchers.argThat(s -> s.status() == StrategyStatus.PAUSED);
    }
}
```

- [ ] **Step 6: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.strategyconfig.application.service.StrategyLookupAdapterTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
feat(modulith): trading own-type StrategyRef + StrategyLookupPort/StrategyPausePort

trading↔strategy-config 순환 해소 1/2 — trading 소유 읽기 전용
StrategyRef(broker BrokerAccountRef와 동일 패턴)와 조회/명령을 분리한
두 포트(StrategyLookupPort/StrategyPausePort, ISP)를 신설하고
strategy-config가 StrategyLookupAdapter로 구현한다. 아직 trading
내부 소비자는 교체하지 않았다(Task 7).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 7: trading 39개 파일 일괄 치환 + `CycleRotationService`/`CyclePositionPersistenceAdapter` 수동 반영

**Files:**
- Modify(스크립트): 아래 "대상 파일" 3개 그룹 — dead(12) + lookup-field(8) + pure-usage(19) = 39개(`src/main/java/com/kista/trading` 하위) + 대응 테스트 전체
- Modify(수동): `src/main/java/com/kista/trading/application/service/CycleRotationService.java`, `src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java`
- Modify(테스트, 수동): `CycleRotationServiceTest.java`, `CyclePositionPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: Task 6의 `StrategyRef`/`StrategyLookupPort`/`StrategyPausePort`
- Produces: trading 전 파일이 `Strategy`(legacy) 대신 `StrategyRef`(trading own-type)만 참조 — Task 8(notify)/Task 9(resolver)가 이어받는다.

**대상 파일 — dead import 삭제(12개, 본문에 `Strategy` 사용 없음)**
```
src/main/java/com/kista/trading/domain/model/BuyCompetitionPreview.java
src/main/java/com/kista/trading/domain/model/CyclePositionHistoryEntry.java
src/main/java/com/kista/trading/domain/model/InfinitePosition.java
src/main/java/com/kista/trading/domain/model/ReverseModePosition.java
src/main/java/com/kista/trading/domain/model/TradingReport.java
src/main/java/com/kista/trading/application/service/BuyPriorityOrdering.java
src/main/java/com/kista/trading/domain/strategy/PrivacyCycleOrderStrategy.java
src/main/java/com/kista/trading/domain/strategy/VrCycleOrderStrategy.java
src/main/java/com/kista/trading/domain/strategy/VrStrategy.java
src/main/java/com/kista/trading/domain/strategy/StrategyCreationResolvers.java
src/main/java/com/kista/trading/application/event/InsufficientBalanceEvent.java
src/main/java/com/kista/trading/application/service/BuyOrderPriceCapper.java
```

**대상 파일 — `StrategyPort strategyPort` 필드 → `StrategyLookupPort strategyPort`(8개, 필드명 유지 — 메서드명 동일해 호출부 변경 불필요)**
```
src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java
src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java
src/main/java/com/kista/trading/application/service/ManualTradingService.java
src/main/java/com/kista/trading/application/service/OrderCancelService.java
src/main/java/com/kista/trading/application/service/TradingPreviewService.java
src/main/java/com/kista/trading/application/service/TradingBuyCompetitionSimulator.java
src/main/java/com/kista/trading/application/service/VrReconfigureService.java
```
(`CyclePositionPersistenceAdapter.java`는 위 8개에 포함되지만 `findTickerById` 통합이 추가로 필요해 아래 "수동 반영"에서 별도 처리 — 이 스크립트 대상에서 제외)

**대상 파일 — 순수 타입 치환만(19개, `Strategy`를 파라미터/필드로만 사용, 포트 없음)**
```
src/main/java/com/kista/trading/adapter/in/schedule/BatchContextFactory.java
src/main/java/com/kista/trading/application/event/CycleCompletedEvent.java
src/main/java/com/kista/trading/application/event/CycleEndedEvent.java
src/main/java/com/kista/trading/application/event/NewCycleStartedEvent.java
src/main/java/com/kista/trading/application/service/CycleOrderComputer.java
src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java
src/main/java/com/kista/trading/application/service/SeedResolutionPolicy.java
src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java
src/main/java/com/kista/trading/application/service/TradingBalanceLoader.java
src/main/java/com/kista/trading/application/service/TradingExecutionFacade.java
src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java
src/main/java/com/kista/trading/application/service/TradingReporter.java
src/main/java/com/kista/trading/application/service/TradingSellSufficiencySimulator.java
src/main/java/com/kista/trading/application/service/TradingService.java
src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java
src/main/java/com/kista/trading/application/usecase/TradingExecutionUseCase.java
src/main/java/com/kista/trading/domain/model/BatchContext.java
src/main/java/com/kista/trading/domain/strategy/CycleOrderStrategies.java
src/main/java/com/kista/trading/domain/strategy/CycleOrderStrategy.java
```
(이 중 `TradingService.java`/`TradingReporter.java`/`CycleOrderComputer.java`/`ManualTradingService.java`/`CyclePositionPersistor.java`/`VrCycleRolloverService.java`는 `import com.kista.domain.model.strategy.*;` 와일드카드를 쓴다 — 이 6개는 해당 패키지의 다른 타입(`StrategyDetail`/`RegisterStrategyCommand` 등)을 쓰지 않는 것을 확인했으므로 와일드카드를 `import com.kista.trading.domain.model.StrategyRef;`로 그대로 교체해도 안전)

- [ ] **Step 1: dead import 12개 삭제**

```python
dead_files = [
    "src/main/java/com/kista/trading/domain/model/BuyCompetitionPreview.java",
    "src/main/java/com/kista/trading/domain/model/CyclePositionHistoryEntry.java",
    "src/main/java/com/kista/trading/domain/model/InfinitePosition.java",
    "src/main/java/com/kista/trading/domain/model/ReverseModePosition.java",
    "src/main/java/com/kista/trading/domain/model/TradingReport.java",
    "src/main/java/com/kista/trading/application/service/BuyPriorityOrdering.java",
    "src/main/java/com/kista/trading/domain/strategy/PrivacyCycleOrderStrategy.java",
    "src/main/java/com/kista/trading/domain/strategy/VrCycleOrderStrategy.java",
    "src/main/java/com/kista/trading/domain/strategy/VrStrategy.java",
    "src/main/java/com/kista/trading/domain/strategy/StrategyCreationResolvers.java",
    "src/main/java/com/kista/trading/application/event/InsufficientBalanceEvent.java",
    "src/main/java/com/kista/trading/application/service/BuyOrderPriceCapper.java",
]

for path in dead_files:
    with open(path, "r", encoding="utf-8") as fh:
        lines = fh.readlines()
    new_lines = [l for l in lines if l.strip() != "import com.kista.domain.model.strategy.Strategy;"]
    assert len(new_lines) == len(lines) - 1, f"import line not found (or found >1 times) in {path}"
    with open(path, "w", encoding="utf-8") as fh:
        fh.writelines(new_lines)

print(f"removed dead import from {len(dead_files)} files")
```

Run: `python3 <스크립트 경로>`
Expected: `removed dead import from 12 files`

- [ ] **Step 2: 순수 타입 치환 19개 + lookup-field 7개(CyclePositionPersistenceAdapter 제외) — import 정규화 후 word-boundary 치환**

```python
import re

# import 라인이 exact(단일 클래스)인 파일 — 그대로 StrategyRef로 치환
exact_import_files = [
    "src/main/java/com/kista/trading/adapter/in/schedule/BatchContextFactory.java",
    "src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java",
    "src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java",
    "src/main/java/com/kista/trading/application/event/CycleCompletedEvent.java",
    "src/main/java/com/kista/trading/application/event/CycleEndedEvent.java",
    "src/main/java/com/kista/trading/application/event/NewCycleStartedEvent.java",
    "src/main/java/com/kista/trading/application/service/OrderCancelService.java",
    "src/main/java/com/kista/trading/application/service/SeedResolutionPolicy.java",
    "src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java",
    "src/main/java/com/kista/trading/application/service/TradingBalanceLoader.java",
    "src/main/java/com/kista/trading/application/service/TradingBuyCompetitionSimulator.java",
    "src/main/java/com/kista/trading/application/service/TradingExecutionFacade.java",
    "src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java",
    "src/main/java/com/kista/trading/application/service/TradingPreviewService.java",
    "src/main/java/com/kista/trading/application/service/TradingSellSufficiencySimulator.java",
    "src/main/java/com/kista/trading/application/service/VrReconfigureService.java",
    "src/main/java/com/kista/trading/application/usecase/TradingExecutionUseCase.java",
    "src/main/java/com/kista/trading/domain/model/BatchContext.java",
    "src/main/java/com/kista/trading/domain/strategy/CycleOrderStrategies.java",
    "src/main/java/com/kista/trading/domain/strategy/CycleOrderStrategy.java",
]

# import 라인이 wildcard인 파일 — 해당 패키지의 다른 타입 미사용 확인 완료
wildcard_import_files = [
    "src/main/java/com/kista/trading/application/service/TradingService.java",
    "src/main/java/com/kista/trading/application/service/TradingReporter.java",
    "src/main/java/com/kista/trading/application/service/CycleOrderComputer.java",
    "src/main/java/com/kista/trading/application/service/ManualTradingService.java",
    "src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java",
    "src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java",
]

STRATEGY_REF_IMPORT = "import com.kista.trading.domain.model.StrategyRef;"

def normalize_import(content: str) -> str:
    content = content.replace("import com.kista.domain.model.strategy.Strategy;", STRATEGY_REF_IMPORT)
    content = content.replace("import com.kista.domain.model.strategy.*;", STRATEGY_REF_IMPORT)
    return content

changed = 0
for path in exact_import_files + wildcard_import_files:
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    content = normalize_import(content)
    new_content = re.sub(r"\bStrategy\b", "StrategyRef", content)
    # import 줄 자체가 이중 치환되지 않았는지 확인 (StrategyRef의 R이 word char라 안전하지만 방어적으로 검증)
    assert "StrategyRefRef" not in new_content, f"double substitution in {path}"
    if new_content == content and STRATEGY_REF_IMPORT not in content:
        raise AssertionError(f"no Strategy usage found in {path} — check manually")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new_content)
    changed += 1

print(f"changed {changed} files")
```

Run: `python3 <스크립트 경로>`
Expected: `changed 26 files`(exact 20개 + wildcard 6개)

- [ ] **Step 3: lookup-field 7개 — 포트 타입 교체 (필드명 `strategyPort` 유지, 메서드명 동일이라 호출부 무변경)**

```python
files = [
    "src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java",
    "src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java",
    "src/main/java/com/kista/trading/application/service/ManualTradingService.java",
    "src/main/java/com/kista/trading/application/service/OrderCancelService.java",
    "src/main/java/com/kista/trading/application/service/TradingPreviewService.java",
    "src/main/java/com/kista/trading/application/service/TradingBuyCompetitionSimulator.java",
    "src/main/java/com/kista/trading/application/service/VrReconfigureService.java",
]

for path in files:
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    new_content = content.replace(
        "import com.kista.application.port.output.StrategyPort;",
        "import com.kista.trading.application.port.output.StrategyLookupPort;")
    new_content = new_content.replace("StrategyPort strategyPort", "StrategyLookupPort strategyPort")
    assert new_content != content, f"no StrategyPort field/import found in {path}"
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new_content)

print(f"updated {len(files)} files")
```

Run: `python3 <스크립트 경로>`
Expected: `updated 7 files`

- [ ] **Step 4: `CyclePositionPersistenceAdapter.java` 수동 반영 (findTickerById 통합)**

`src/main/java/com/kista/trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java`에서 import:
```java
import com.kista.application.port.output.StrategyPort;
import com.kista.domain.model.strategy.Strategy;
```
→
```java
import com.kista.trading.application.port.output.StrategyLookupPort;
```
필드:
```java
    private final StrategyPort strategyPort;                 // ticker 조회: strategy_cycle → strategy (legacy strategy 패키지 소유 Entity라 포트 경유)
```
→
```java
    private final StrategyLookupPort strategyPort;            // ticker 조회: strategy_cycle → strategy (trading own-type 포트 경유)
```
두 호출부(`findByStrategyIdAndDateRange`/`findByStrategyIdWithCursor`) 각각:
```java
        StrategyTicker ticker = strategyPort.findById(strategyId)
                .map(Strategy::ticker).orElse(null);
```
→
```java
        StrategyTicker ticker = strategyPort.findTickerById(strategyId);
```
(2곳 모두 동일 패턴)

- [ ] **Step 5: `CycleRotationService.java` 수동 반영 (쓰기 경로 → StrategyPausePort)**

import 블록에서:
```java
import com.kista.application.port.output.StrategyPort;
```
```java
import com.kista.sharedkernel.StrategyStatus;
```
삭제 후 추가:
```java
import com.kista.trading.application.port.output.StrategyPausePort;
```
(`Strategy` import도 `StrategyRef`로 이미 Step 2 스크립트 대상이 아니었으므로 — 이 파일은 lookup-field 그룹에도 pure-usage 그룹에도 없었다. 별도로 `\bStrategy\b`→`StrategyRef` 치환을 이 Step에서 직접 적용한다)

필드:
```java
    private final StrategyPort strategyPort;                   // 전략 상태 갱신
```
→
```java
    private final StrategyPausePort strategyPausePort;         // 시스템 자동 일시정지(사이클 재등록 실패)
```
메서드 시그니처 3곳(`rotate`/`resolveTargetSeed`/`resolvePolicy`)의 `Strategy strategy` 파라미터를 `StrategyRef strategy`로 교체, `Strategy` 관련 나머지 바디 참조(`strategy.cycleSeedType()`/`.id()`/`.ticker()`/`.type()`)는 필드 접근이라 코드 변경 없음.

쓰기 호출 2곳:
```java
            strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED));
```
(55번째 줄)
→
```java
            strategyPausePort.pause(strategy.id());
```
```java
        strategyPort.save(strategy.withStatus(StrategyStatus.PAUSED));
```
(109번째 줄)
→
```java
        strategyPausePort.pause(strategy.id());
```
`Strategy.DEFAULT_DIVISION_COUNT` 참조는 이미 Task 2에서 `StrategyDefaults.DEFAULT_DIVISION_COUNT`로 바뀌어 있으므로 이 파일엔 더 이상 남아있지 않다(확인만).

`import com.kista.trading.domain.model.StrategyRef;` 필요 시 추가(이미 다른 trading 타입 wildcard/exact import에 묻어 있을 수 있음 — Read로 확인).

- [ ] **Step 6: 잔존 옛 참조 확인**

```bash
cd /Users/phs/workspace/kista/kista-api
grep -rn "com\.kista\.domain\.model\.strategy\.Strategy\b" src/main/java/com/kista/trading --include="*.java"
grep -rln "StrategyPort " src/main/java/com/kista/trading --include="*.java"
```
Expected: 첫 번째 결과 없음(빈 출력). 두 번째는 `CyclePositionPersistenceAdapter.java`/lookup-field 7개/CycleRotationService 어디에도 남아있으면 안 됨 — 있으면 해당 줄 수동 확인.

- [ ] **Step 7: trading 테스트 디렉토리에 동일 규칙 적용**

```bash
cd /Users/phs/workspace/kista/kista-api
grep -rl "com\.kista\.domain\.model\.strategy\.Strategy\b" src/test/java/com/kista/trading --include="*.java"
```
위 명령이 출력하는 각 테스트 파일에 대해 Step 1~5와 동일한 규칙(dead면 import만 삭제, pure-usage면 `\bStrategy\b`→`StrategyRef` + import 교체, lookup-field 테스트면 `@Mock StrategyPort`→`@Mock StrategyLookupPort`, `CycleRotationServiceTest`는 `@Mock StrategyPausePort` + `verify(strategyPausePort).pause(id)`로 교체)를 수동 적용한다. 파일 수가 많으면(예상 15~25개) `superpowers:subagent-driven-development`의 병렬 서브에이전트로 나눠 처리해도 되지만, 이 태스크 자체는 컨트롤러가 직접 좁혀서 처리하고 컴파일 그린을 확인한 뒤 다음 Step으로 넘어간다.

- [ ] **Step 8: 컴파일**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 2회 — 에러 나는 파일은 대부분 Step 7에서 놓친 테스트 파일이므로 에러 메시지의 파일:줄 번호를 보고 개별 수정.

- [ ] **Step 9: trading 전체 테스트 + 아키텍처 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test --tests 'com.kista.trading.*' --tests 'com.kista.broker.adapter.out.mock.*' --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): trading↔strategy-config 순환 해소 — 39개 파일 own-type 치환

trading↔strategy-config 순환 해소 2/2 — trading 내부 39개 파일(죽은
import 12개 삭제 + 조회 포트 8개 StrategyLookupPort 전환 + 순수 타입
19개 Strategy→StrategyRef 치환) + CycleRotationService 쓰기 경로를
StrategyPausePort로, CyclePositionPersistenceAdapter의 2단계 조회를
findTickerById 단건 호출로 정리했다. 로직 변경 없음 — 순수 타입 치환.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 8: notify 연쇄 치환

**Files:**
- Modify: `src/main/java/com/kista/notify/application/port/output/UserNotificationPort.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/FcmAdapter.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/CompositeUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/notify/application/port/output/NotifyPort.java` (죽은 import 삭제)
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TelegramAdapter.java` (죽은 import 삭제)
- Modify: 대응 테스트 전체(`UserNotificationPort` 구현체 테스트, `CycleLifecycleNotifier` 관련 테스트가 있으면 함께)

**Interfaces:**
- Consumes: Task 7이 만든 `com.kista.trading.domain.model.StrategyRef`(trading "domain" NamedInterface, 이미 notify가 다른 trading 이벤트를 구독하는 기존 forward 의존과 동일선상)

- [ ] **Step 1: 죽은 import 2개 삭제**

`src/main/java/com/kista/notify/application/port/output/NotifyPort.java`와 `src/main/java/com/kista/notify/adapter/out/gateway/TelegramAdapter.java`에서 아래 줄 삭제(둘 다 `StrategyTicker`만 실사용, `Strategy` 미사용):
```java
import com.kista.domain.model.strategy.Strategy;
```

- [ ] **Step 2: `UserNotificationPort` + 4개 구현체 타입 치환**

```python
files = [
    "src/main/java/com/kista/notify/application/port/output/UserNotificationPort.java",
    "src/main/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapter.java",
    "src/main/java/com/kista/notify/adapter/out/gateway/FcmAdapter.java",
    "src/main/java/com/kista/notify/adapter/out/gateway/CompositeUserNotificationAdapter.java",
]

for path in files:
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    content = content.replace(
        "import com.kista.domain.model.strategy.Strategy;",
        "import com.kista.trading.domain.model.StrategyRef;")
    import re
    new_content = re.sub(r"\bStrategy\b", "StrategyRef", content)
    assert new_content != content or "StrategyRef" in content, f"no change in {path}"
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new_content)

print(f"updated {len(files)} files")
```

Run: `python3 <스크립트 경로>`
Expected: `updated 4 files`

- [ ] **Step 3: 잔존 확인**

```bash
grep -rn "com\.kista\.domain\.model\.strategy\.Strategy\b" src/main/java/com/kista/notify --include="*.java"
```
Expected: 결과 없음.

- [ ] **Step 4: 테스트 갱신 + 컴파일 + 좁은 테스트**

`src/test/java/com/kista/notify` 하위에서 `Strategy` 타입을 직접 생성하는 테스트(주로 `TelegramUserNotificationAdapterTest`/`FcmAdapterTest`/`CompositeUserNotificationAdapterTest`)를 찾아 `StrategyRef`로 교체:
```bash
grep -rl "com\.kista\.domain\.model\.strategy\.Strategy\b\|new Strategy(" src/test/java/com/kista/notify --include="*.java"
```
각 파일에서 `new Strategy(...)` → `new StrategyRef(...)`(생성자 인자 순서 동일: id/accountId/type/status/ticker/cycleSeedType), import 교체.

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.notify.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): notify의 Strategy 참조를 trading own-type으로 정리

trading의 3개 이벤트(NewCycleStartedEvent/CycleCompletedEvent/
CycleEndedEvent)가 필드 타입을 StrategyRef로 바꾼 데 맞춰, 이를
구독하는 notify의 UserNotificationPort+구현체 3개도 동일 타입으로
치환한다(순수 타입 스왑, 읽는 필드 type/ticker/cycleSeedType 동일).
NotifyPort/TelegramAdapter의 죽은 Strategy import도 함께 정리.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 9: `StrategyCreationResolver` 시그니처 축소

**Files:**
- Create: `src/main/java/com/kista/trading/domain/strategy/StrategyCreationRequest.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/StrategyCreationResolver.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/InfiniteCreationResolver.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/PrivacyCreationResolver.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/VrCreationResolver.java`
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java` (`resolveCreationSettings` 호출부)
- Modify: 대응 테스트(`InfiniteCreationResolverTest`/`PrivacyCreationResolverTest`/`VrCreationResolverTest`가 있으면, 없으면 `StrategyServiceTest`의 관련 케이스만)

**Interfaces:**
- Produces: `com.kista.trading.domain.strategy.StrategyCreationRequest(StrategyTicker ticker, int divisionCount, Integer intervalWeeks, BigDecimal bandWidth, Integer recurringAmount)` — Task 10 이후 `StrategyService`가 계속 소비.

- [ ] **Step 1: `StrategyCreationRequest.java` 신설**

```java
package com.kista.trading.domain.strategy;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;

// 전략 등록 시 리졸버가 실제로 읽는 원시값만 담은 trading 소유 요청 타입 — strategy-config 소유
// RegisterStrategyCommand 전체를 리졸버에 넘기던 것을 대체한다(양방향 결합 해소, own-type 패턴).
// StrategyService가 RegisterStrategyCommand에서 값을 꺼내 이 타입으로 변환해 전달한다.
public record StrategyCreationRequest(
        StrategyTicker ticker,          // null이면 설정 기본값
        int divisionCount,               // 0 = 미입력 sentinel (INFINITE 전용)
        Integer intervalWeeks,           // VR 전용, null 허용
        BigDecimal bandWidth,            // VR 전용, null 허용
        Integer recurringAmount          // VR 전용, null 허용
) {}
```

- [ ] **Step 2: `StrategyCreationResolver` 인터페이스 시그니처 교체**

`src/main/java/com/kista/trading/domain/strategy/StrategyCreationResolver.java` 전체를 아래로 교체:
```java
package com.kista.trading.domain.strategy;

import java.math.BigDecimal;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// 전략 등록 시 런타임 설정(StrategyCreationSettings) 기반 필드 해석 진입점
// 각 구현체는 type()으로 자기 타입을 선언하며, 서비스는 Map<StrategyType, StrategyCreationResolver>로 주입받아 사용
public interface StrategyCreationResolver {

    // 이 리졸버가 담당하는 전략 타입
    StrategyType type();

    // ticker는 모든 전략 유형에서 생략 기본값·허용값·고정값 정책을 동일하게 적용한다.
    default ResolvedCreation resolve(StrategyCreationRequest request, StrategyCreationSettings settings) {
        StrategyTicker ticker = settings.ticker().resolve(request.ticker());
        return resolveTypeFields(request, settings, ticker);
    }

    // 전략 타입별 고유 필드(division count / VR 파라미터 등) 해석
    ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker);

    // signed recurringAmount를 런타임 설정의 UI 방향 enum으로 변환 — VR 리졸버 공용
    static RecurringMode recurringModeOf(int recurringAmount) {
        if (recurringAmount > 0) return RecurringMode.DEPOSIT;
        if (recurringAmount < 0) return RecurringMode.WITHDRAW;
        return RecurringMode.HOLD;
    }

    // 런타임 생성 정책을 적용한 뒤 저장·검증에 전달하는 값 묶음
    record ResolvedCreation(StrategyTicker ticker, int divisionCount, Integer intervalWeeks,
                             BigDecimal bandWidth, Integer recurringAmount) {}
}
```

- [ ] **Step 3: `InfiniteCreationResolver.java` 교체**

```java
package com.kista.trading.domain.strategy;

import org.springframework.stereotype.Component;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Component
public class InfiniteCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.INFINITE;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        // primitive 0은 요청 생략 sentinel이므로 설정 기본값으로 치환한다.
        Integer requestedDivisionCount = request.divisionCount() == 0 ? null : request.divisionCount();
        int divisionCount = settings.divisionCount().resolve(requestedDivisionCount);
        return new ResolvedCreation(ticker, divisionCount, null, null, null);
    }
}
```

- [ ] **Step 4: `PrivacyCreationResolver.java` 교체**

```java
package com.kista.trading.domain.strategy;

import com.kista.sharedkernel.StrategyDefaults;
import org.springframework.stereotype.Component;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Component
public class PrivacyCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.PRIVACY;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        // PRIVACY는 전략 고유 설정 필드가 없다 — 고정 분할 수만 적용한다.
        return new ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, null, null, null);
    }
}
```

- [ ] **Step 5: `VrCreationResolver.java` 교체**

```java
package com.kista.trading.domain.strategy;

import com.kista.sharedkernel.StrategyDefaults;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Component
public class VrCreationResolver implements StrategyCreationResolver {

    @Override
    public StrategyType type() {
        return StrategyType.VR;
    }

    @Override
    public ResolvedCreation resolveTypeFields(StrategyCreationRequest request, StrategyCreationSettings settings, StrategyTicker ticker) {
        int recurringAmount = resolveRecurringAmount(request, settings);
        BigDecimal bandWidth = settings.bandWidth().resolve(request.bandWidth());
        Integer intervalWeeks = settings.intervalWeeks().resolve(request.intervalWeeks());
        return new ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, intervalWeeks, bandWidth, recurringAmount);
    }

    // 방향(recurringMode)만 설정으로 검증하고 recurringAmount의 실제 크기는 기존 VR 자산 규칙(validateVrCommand)에 맡긴다.
    private int resolveRecurringAmount(StrategyCreationRequest request, StrategyCreationSettings settings) {
        if (!settings.recurringMode().customizable()) {
            int amount = request.recurringAmount() != null ? request.recurringAmount() : 0;
            if (amount != 0) {
                throw new IllegalArgumentException("고정 recurringMode는 recurringAmount 0만 허용합니다");
            }
            return 0;
        }
        if (request.recurringAmount() == null) {
            // 생략 시 설정된 기본 방향을 적용한다 — HOLD(=0)만 크기 없이 default 적용 가능하고,
            // 그 외 방향은 크기를 알 수 없어 명시 입력을 요구한다 (defaultValue는 방향만 의미, 금액은 의미하지 않음).
            RecurringMode defaultMode = settings.recurringMode().resolve(null);
            if (defaultMode != RecurringMode.HOLD) {
                throw new IllegalArgumentException("recurringAmount는 필수입니다 (기본 방향: " + defaultMode + ")");
            }
            return 0;
        }
        settings.recurringMode().resolve(StrategyCreationResolver.recurringModeOf(request.recurringAmount()));
        return request.recurringAmount();
    }
}
```

- [ ] **Step 6: `StrategyService.resolveCreationSettings` 호출부를 `StrategyCreationRequest`로 변환**

`src/main/java/com/kista/application/service/strategy/StrategyService.java`의 `resolveCreationSettings`(Task 3에서 이미 축약된 버전)를 아래로 교체:
```java
    private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
        com.kista.trading.domain.strategy.StrategyCreationSettings settings = strategyCreationPolicyPort.find(cmd.type())
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 전략 생성 정책: " + cmd.type()));
        if (!settings.enabled()) {
            throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
        }
        com.kista.trading.domain.strategy.StrategyCreationRequest request =
                new com.kista.trading.domain.strategy.StrategyCreationRequest(
                        cmd.ticker(), cmd.divisionCount(), cmd.intervalWeeks(), cmd.bandWidth(), cmd.recurringAmount());
        return creationResolvers.of(cmd.type()).resolve(request, settings);
    }
```

- [ ] **Step 7: 잔존 확인**

```bash
grep -rn "RegisterStrategyCommand" src/main/java/com/kista/trading --include="*.java"
```
Expected: 결과 없음(trading이 더 이상 `RegisterStrategyCommand`를 참조하지 않음).

- [ ] **Step 8: 테스트 갱신**

`InfiniteCreationResolverTest`/`PrivacyCreationResolverTest`/`VrCreationResolverTest`(파일 존재 여부는 `find src/test/java/com/kista/trading/domain/strategy -iname '*CreationResolver*Test.java'`로 확인)에서 `RegisterStrategyCommand` 생성 후 `resolver.resolve(cmd, settings)` 호출하던 부분을 `StrategyCreationRequest` 생성 후 `resolver.resolve(request, settings)`로 교체(필드 대응: `cmd.ticker()→request 첫 인자`, `cmd.divisionCount()→둘째`, `cmd.intervalWeeks()→셋째`, `cmd.bandWidth()→넷째`, `cmd.recurringAmount()→다섯째`).

- [ ] **Step 9: 컴파일 + 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
./gradlew test --tests 'com.kista.trading.domain.strategy.*' --tests 'com.kista.application.service.strategy.StrategyServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): StrategyCreationResolver 시그니처를 원시값 요청으로 축소

trading의 StrategyCreationResolver(+Infinite/Privacy/VrCreationResolver
구현체 3개)가 strategy-config 소유 RegisterStrategyCommand 전체를
받던 것을, 각 구현체가 실제로 읽는 5개 원시값만 담은 trading 소유
StrategyCreationRequest로 좁혔다(2차 결합 해소). trading은 이제
RegisterStrategyCommand를 전혀 참조하지 않는다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 10: strategy-config 물리 이전

**Files:**
- Move: `Strategy.java`/`StrategyDetail.java`/`RegisterStrategyCommand.java`/`UpdateStrategyCommand.java`/`StrategySeedPreview.java`(도메인 5개), `StrategyUseCase.java`(usecase), `StrategyPort.java`(port), `StrategyService.java`/`AccountCascadeListener.java`(service), `StrategyEntity.java`/`StrategyJpaRepository.java`/`StrategyPersistenceAdapter.java`/`PersistenceSupport.java`(persistence 4개) + 대응 테스트 전체
- Modify: 위 파일들을 import하는 저장소 전역 파일(스크립트로 일괄 치환)

**Interfaces:**
- Produces: `com.kista.strategyconfig.domain.model.{Strategy,StrategyDetail,RegisterStrategyCommand,UpdateStrategyCommand,StrategySeedPreview}`, `com.kista.strategyconfig.application.usecase.StrategyUseCase`, `com.kista.strategyconfig.application.port.output.StrategyPort` — Task 11의 `@ApplicationModule` 선언 대상.

- [ ] **Step 1: git mv — domain 5개 + usecase 1개 + port 1개**

```bash
cd /Users/phs/workspace/kista/kista-api
mkdir -p src/main/java/com/kista/strategyconfig/domain/model
mkdir -p src/main/java/com/kista/strategyconfig/application/usecase
mkdir -p src/main/java/com/kista/strategyconfig/application/port/output
mkdir -p src/main/java/com/kista/strategyconfig/application/service
mkdir -p src/main/java/com/kista/strategyconfig/adapter/out/persistence

git mv src/main/java/com/kista/domain/model/strategy/Strategy.java src/main/java/com/kista/strategyconfig/domain/model/Strategy.java
git mv src/main/java/com/kista/domain/model/strategy/StrategyDetail.java src/main/java/com/kista/strategyconfig/domain/model/StrategyDetail.java
git mv src/main/java/com/kista/domain/model/strategy/RegisterStrategyCommand.java src/main/java/com/kista/strategyconfig/domain/model/RegisterStrategyCommand.java
git mv src/main/java/com/kista/domain/model/strategy/UpdateStrategyCommand.java src/main/java/com/kista/strategyconfig/domain/model/UpdateStrategyCommand.java
git mv src/main/java/com/kista/domain/model/strategy/StrategySeedPreview.java src/main/java/com/kista/strategyconfig/domain/model/StrategySeedPreview.java
git mv src/main/java/com/kista/application/usecase/StrategyUseCase.java src/main/java/com/kista/strategyconfig/application/usecase/StrategyUseCase.java
git mv src/main/java/com/kista/application/port/output/StrategyPort.java src/main/java/com/kista/strategyconfig/application/port/output/StrategyPort.java
git mv src/main/java/com/kista/application/service/strategy/StrategyService.java src/main/java/com/kista/strategyconfig/application/service/StrategyService.java
git mv src/main/java/com/kista/application/service/strategy/AccountCascadeListener.java src/main/java/com/kista/strategyconfig/application/service/AccountCascadeListener.java
```

- [ ] **Step 2: git mv — persistence 4개 + 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyEntity.java src/main/java/com/kista/strategyconfig/adapter/out/persistence/StrategyEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyJpaRepository.java src/main/java/com/kista/strategyconfig/adapter/out/persistence/StrategyJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/StrategyPersistenceAdapter.java src/main/java/com/kista/strategyconfig/adapter/out/persistence/StrategyPersistenceAdapter.java
git mv src/main/java/com/kista/adapter/out/persistence/strategy/PersistenceSupport.java src/main/java/com/kista/strategyconfig/adapter/out/persistence/PersistenceSupport.java

# 테스트 — 실제 파일 목록은 아래 find로 재확인 후 동일 구조로 이동
find src/test/java/com/kista/application/service/strategy -type f
find src/test/java/com/kista/adapter/out/persistence/strategy -type f
```

위 `find` 결과에 맞춰(예상: `StrategyServiceTest.java`, `AccountCascadeListenerTest.java`, `StrategyPersistenceAdapterTest.java`) 동일 대상 패키지로 `git mv`:
```bash
mkdir -p src/test/java/com/kista/strategyconfig/application/service
mkdir -p src/test/java/com/kista/strategyconfig/adapter/out/persistence
git mv src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java src/test/java/com/kista/strategyconfig/application/service/StrategyServiceTest.java
git mv src/test/java/com/kista/application/service/strategy/AccountCascadeListenerTest.java src/test/java/com/kista/strategyconfig/application/service/AccountCascadeListenerTest.java
git mv src/test/java/com/kista/adapter/out/persistence/strategy/StrategyPersistenceAdapterTest.java src/test/java/com/kista/strategyconfig/adapter/out/persistence/StrategyPersistenceAdapterTest.java
```
(실제 테스트 파일이 위 3개와 다르면 find 결과대로 조정 — 예상과 다를 수 있음)

- [ ] **Step 3: package 선언 일괄 수정**

```python
moves = {
    "src/main/java/com/kista/strategyconfig/domain/model/Strategy.java": ("com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    "src/main/java/com/kista/strategyconfig/domain/model/StrategyDetail.java": ("com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    "src/main/java/com/kista/strategyconfig/domain/model/RegisterStrategyCommand.java": ("com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    "src/main/java/com/kista/strategyconfig/domain/model/UpdateStrategyCommand.java": ("com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    "src/main/java/com/kista/strategyconfig/domain/model/StrategySeedPreview.java": ("com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    "src/main/java/com/kista/strategyconfig/application/usecase/StrategyUseCase.java": ("com.kista.application.usecase", "com.kista.strategyconfig.application.usecase"),
    "src/main/java/com/kista/strategyconfig/application/port/output/StrategyPort.java": ("com.kista.application.port.output", "com.kista.strategyconfig.application.port.output"),
    "src/main/java/com/kista/strategyconfig/application/service/StrategyService.java": ("com.kista.application.service.strategy", "com.kista.strategyconfig.application.service"),
    "src/main/java/com/kista/strategyconfig/application/service/AccountCascadeListener.java": ("com.kista.application.service.strategy", "com.kista.strategyconfig.application.service"),
    "src/main/java/com/kista/strategyconfig/adapter/out/persistence/StrategyEntity.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.strategyconfig.adapter.out.persistence"),
    "src/main/java/com/kista/strategyconfig/adapter/out/persistence/StrategyJpaRepository.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.strategyconfig.adapter.out.persistence"),
    "src/main/java/com/kista/strategyconfig/adapter/out/persistence/StrategyPersistenceAdapter.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.strategyconfig.adapter.out.persistence"),
    "src/main/java/com/kista/strategyconfig/adapter/out/persistence/PersistenceSupport.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.strategyconfig.adapter.out.persistence"),
    "src/test/java/com/kista/strategyconfig/application/service/StrategyServiceTest.java": ("com.kista.application.service.strategy", "com.kista.strategyconfig.application.service"),
    "src/test/java/com/kista/strategyconfig/application/service/AccountCascadeListenerTest.java": ("com.kista.application.service.strategy", "com.kista.strategyconfig.application.service"),
    "src/test/java/com/kista/strategyconfig/adapter/out/persistence/StrategyPersistenceAdapterTest.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.strategyconfig.adapter.out.persistence"),
}

for path, (old_pkg, new_pkg) in moves.items():
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    new_content = content.replace(f"package {old_pkg};", f"package {new_pkg};", 1)
    assert new_content != content, f"package line not found in {path}"
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new_content)

print(f"package line fixed in {len(moves)} files")
```

Run: `python3 <스크립트 경로>`
Expected: `package line fixed in 16 files`(테스트 실제 목록이 다르면 dict를 그에 맞게 조정 후 재실행)

- [ ] **Step 4: 저장소 전역 import 경로 치환**

```python
import subprocess

symbols = [
    ("Strategy", "com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    ("StrategyDetail", "com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    ("RegisterStrategyCommand", "com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    ("UpdateStrategyCommand", "com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    ("StrategySeedPreview", "com.kista.domain.model.strategy", "com.kista.strategyconfig.domain.model"),
    ("StrategyUseCase", "com.kista.application.usecase", "com.kista.strategyconfig.application.usecase"),
    ("StrategyPort", "com.kista.application.port.output", "com.kista.strategyconfig.application.port.output"),
]

out = subprocess.run(["grep", "-rl", "-E",
    r"^import com\.kista\.(domain\.model\.strategy|application\.usecase|application\.port\.output)\.(Strategy|StrategyDetail|RegisterStrategyCommand|UpdateStrategyCommand|StrategySeedPreview|StrategyUseCase|StrategyPort);",
    "src/main/java", "src/test/java", "--include=*.java"],
    capture_output=True, text=True)
files = [f for f in out.stdout.splitlines() if f.strip()]
print(f"files to fix: {len(files)}")

changed = 0
for path in files:
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    new_content = content
    for sym, old_pkg, new_pkg in symbols:
        new_content = new_content.replace(f"import {old_pkg}.{sym};", f"import {new_pkg}.{sym};")
    if new_content != content:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(new_content)
        changed += 1
print(f"files changed: {changed}")
```

Run: `python3 <스크립트 경로>`
Expected: `files to fix`와 `files changed`가 동일한 숫자(불일치 시 부분 실패 — 즉시 조사)

- [ ] **Step 5: `com.kista.domain.model.strategy` 패키지에 남은 파일 확인**

```bash
find src/main/java/com/kista/domain/model/strategy -type f
find src/main/java/com/kista/application/service/strategy -type f
find src/main/java/com/kista/adapter/out/persistence/strategy -type f
```
`StrategyCreationResolver`/`InfiniteCreationResolver` 등은 이미 `com.kista.trading.domain.strategy`에 있으므로 이 디렉토리들엔 아무 파일도 남지 않아야 한다. 남은 파일이 있으면(예: 이 계획이 놓친 파일) 이동 대상에 추가해 Step 1~4를 반복한다.

- [ ] **Step 6: 잔존 옛 경로 확인**

```bash
grep -rn "com\.kista\.domain\.model\.strategy\.\(Strategy\|StrategyDetail\|RegisterStrategyCommand\|UpdateStrategyCommand\|StrategySeedPreview\)\b\|com\.kista\.application\.usecase\.StrategyUseCase\b\|com\.kista\.application\.port\.output\.StrategyPort\b" src/main/java src/test/java --include="*.java"
```
Expected: 결과 없음.

- [ ] **Step 7: `StrategyLookupAdapter`/`ActiveStrategyCountAdapter`/`StrategyUserCascadeListener`의 legacy import를 새 경로로 자동 반영 확인**

Step 4의 전역 치환이 Task 4/6에서 만든 파일(`StrategyLookupAdapter.java`/`ActiveStrategyCountAdapter.java`/`StrategyUserCascadeListener.java`)의 `import com.kista.application.port.output.StrategyPort;`도 함께 `import com.kista.strategyconfig.application.port.output.StrategyPort;`로 바꿨는지 확인:
```bash
grep -n "StrategyPort" src/main/java/com/kista/strategyconfig/application/service/StrategyLookupAdapter.java src/main/java/com/kista/strategyconfig/application/service/ActiveStrategyCountAdapter.java src/main/java/com/kista/strategyconfig/application/service/StrategyUserCascadeListener.java
```
Expected: 전부 `com.kista.strategyconfig.application.port.output.StrategyPort` 참조. 안 바뀌었으면 수동으로 import 한 줄씩 고친다(같은 패키지로 이동했으니 애초에 import 자체가 불필요해질 수도 있음 — 같은 패키지면 import 삭제).

- [ ] **Step 8: 컴파일**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
```
Expected: `BUILD SUCCESSFUL` 2회.

- [ ] **Step 9: 좁은 테스트**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test --tests 'com.kista.strategyconfig.*' --tests 'com.kista.admin.*' --tests 'com.kista.user.application.service.*' --tests 'com.kista.broker.adapter.out.mock.*' --tests 'com.kista.trading.*' --tests 'com.kista.stats.application.service.BacktestServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): Strategy 애그리게이트 com.kista.strategyconfig로 물리 이전

strategy-config 이전 서브프로젝트 C — Strategy/StrategyDetail/
RegisterStrategyCommand/UpdateStrategyCommand/StrategySeedPreview(domain),
StrategyUseCase(usecase), StrategyPort(port), StrategyService/
AccountCascadeListener(service), StrategyEntity/StrategyJpaRepository/
StrategyPersistenceAdapter/PersistenceSupport(persistence)를
com.kista.strategyconfig로 이동한다. admin/user/broker/trading 4개
역방향 엣지는 Task 3~9에서 이미 해소됐으므로 이 물리 이동은 패키지
선언과 import 경로만 바꾸는 순수 이동이다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

---

## Task 11: `@ApplicationModule(CLOSED)` 선언 + `verify()` + 최종 검증 + 문서 갱신

**Files:**
- Create: `src/main/java/com/kista/strategyconfig/package-info.java`
- Modify: `docs/agents/architecture.md`(신규 `com.kista.strategyconfig/` 절 + "Spring Modulith 점진 도입" 서술 갱신)
- Modify: `docs/agents/constraints.md`("Spring Modulith 이전 중 신규 파일 배치" 절에 strategy-config 항목 추가)
- Modify: `/Users/phs/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/project_strategy_config_migration.md`(완료 갱신)
- Modify: `/Users/phs/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/project_legacy_module_catalog.md`(strategy-config 완료 반영)

**Interfaces:** 없음(문서·검증 전용 마무리 태스크)

- [ ] **Step 1: `package-info.java` 신설**

```java
// strategy-config — 계좌별 영속 전략 설정(Strategy) 애그리게이트. "domain"(domain.model)·"usecase"
// (application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개 — application.service·
// adapter.out.persistence는 비공개(internal). event/schedule NamedInterface 없음(notify 직접 참조 없음,
// 스케쥴러 없음 — admin과 동일 사유).
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {},
        type = org.springframework.modulith.ApplicationModule.Type.CLOSED
)
package com.kista.strategyconfig;
```
(실제 `@ApplicationModule` 애노테이션 속성은 다른 CLOSED 모듈의 `package-info.java`(예: `com.kista.account`)를 먼저 Read해서 정확한 관례 — `allowedDependencies` 명시 여부, import static 사용 여부 등 — 그대로 맞출 것. NamedInterface 지정 방식도 기존 모듈의 `package-info.java`가 하는 방식을 그대로 따른다.)

각 NamedInterface 서브패키지에도 기존 CLOSED 모듈 관례에 따라 `package-info.java`가 필요하면 추가(다른 모듈, 예: `com.kista.account.domain.model`의 `package-info.java` 존재 여부를 Read로 먼저 확인 후 동일하게 처리).

- [ ] **Step 2: `ApplicationModules.verify()` 실행**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD|Cycle|violat"
```
Expected: `BUILD SUCCESSFUL`. 순환이나 위반이 나오면 에러 메시지가 지목하는 구체적 엣지를 Task 3~9 중 어느 것이 놓쳤는지 역추적 — account Task 4 1차 시도처럼 BLOCKED되면 여기서 멈추고 원인 엣지를 별도 own-type/이벤트로 추가 해소한 뒤 재실행한다(섣불리 강행하지 말 것).

- [ ] **Step 3: 전체 테스트 스위트 최종 1회**

```bash
cd /Users/phs/workspace/kista/kista-api
docker compose ps postgres 2>&1 | grep -q Up || docker compose up -d postgres
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: `architecture.md` 갱신**

`docs/agents/architecture.md`에 `com.kista.admin/` 절과 `com.kista.account/` 절 사이(또는 알파벳/도입 순서에 맞는 위치)에 새 절 `com.kista.strategyconfig/` 추가 — 다른 CLOSED 모듈 절과 동일한 서술 포맷(NamedInterface 구성, 내부 패키지, own-type 해소 내역)으로 작성:
```
com.kista.strategyconfig/ ← Spring Modulith 열한 번째 이전 모듈(CLOSED) — 계좌별 영속 전략 설정(Strategy) 애그리게이트, 위 레거시 4패키지와 별개 최상위. "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개 — application.service·adapter.out.persistence는 의도적으로 비공개(모듈 내부 구현). event/schedule NamedInterface 없음(notify 직접 참조 0건, 스케쥴러 없음 — admin과 동일 사유)
  domain/model/       ← Strategy/StrategyDetail/RegisterStrategyCommand/UpdateStrategyCommand/StrategySeedPreview 5개 — "domain" NamedInterface로 공개
  application/usecase/ ← StrategyUseCase(조회/등록/수정/삭제/일시정지/재개) — "usecase" NamedInterface로 공개
  application/port/output/ ← StrategyPort — "port" NamedInterface로 공개
  application/service/ ← internal(비공개) — StrategyService(StrategyUseCase 구현)/AccountCascadeListener(AccountDeletedEvent 구독)/StrategyLookupAdapter(trading own-type 포트 구현)/ActiveStrategyCountAdapter(user own-type 포트 구현)/StrategyUserCascadeListener(UserDeletedEvent 구독)
  adapter/out/persistence/ ← internal(비공개) — StrategyEntity + StrategyJpaRepository + StrategyPersistenceAdapter + PersistenceSupport(strategy-config 전용 복제본) 3+1종
  ── 물리 이전 과정에서 4개 모듈 순환이 실측 발견돼 해소한 뒤 재개했다(원래 브리핑은 admin 1건뿐이었음) — ① admin↔strategy-config: StrategyService의 RuntimeSettingsPort 직접 참조를 own-type 포트 역전(StrategyCreationPolicyPort, strategy-config 정의·admin RuntimeSettingsService 구현, 8번째 인스턴스)으로 해소. ② user↔strategy-config: UserCascadeDeleter의 삭제 cascade를 strategy-config 자체 UserDeletedEvent 리스너로, UserSettingsService의 활성 전략 카운트 조회를 own-type 포트 역전(ActiveStrategyCountPort, user 정의·strategy-config 구현, 9번째 인스턴스)으로 해소. ③ broker↔strategy-config: MockBrokerAdapter의 직접 참조를 기존 역전 포트 MockSimulationDataPort 확장(broker 소유 초경량 뷰 StrategyRefLite)으로 해소 — 신규 own-type 없음. ④ trading↔strategy-config: trading이 스케쥴러·실행 코어 39개 파일에서 Strategy를 상시 참조하던 것을 trading 소유 읽기 전용 StrategyRef + 조회/명령 분리 포트(StrategyLookupPort/StrategyPausePort, ISP)로 해소(10번째 인스턴스) — 연쇄로 notify의 trading 이벤트 구독부(UserNotificationPort)까지 타입이 갱신됐다. trading의 StrategyCreationResolver도 strategy-config 소유 RegisterStrategyCommand 대신 trading 소유 StrategyCreationRequest(원시값 5개)로 시그니처를 좁혔다
```
(정확한 "몇 번째 이전 모듈"인지는 architecture.md의 기존 서술을 Read로 확인해 순번을 맞출 것 — account가 10번째였으므로 strategy-config는 11번째가 될 가능성이 높으나 반드시 재확인)

"Spring Modulith 점진 도입" 절 마지막 문단에 strategy-config 완료 사실과 4개 순환 해소 요약을 다른 모듈들과 동일한 서술 톤으로 추가하고, "strategy-config는 이 4단계 스코프에서 제외됐다..." 이하 서술을 "strategy-config 이전이 A(sharedkernel enum)/B(Version·Detail→trading)/C(신모듈+4개 순환 해소, 이번 완료)로 마무리됐다"로 갱신.

- [ ] **Step 5: `constraints.md` 갱신**

"Spring Modulith 이전 중 신규 파일 배치" 절에 strategy-config 항목 추가(다른 모듈 항목과 동일 포맷):
```
- Strategy 애그리게이트(계좌별 영속 전략 설정)는 `com.kista.strategyconfig`로 이미 옮겨졌다 — 신규 관련 코드도 레거시 최상위가 아닌 `com.kista.strategyconfig` 안에 추가. `domain/model`이 "domain"으로, `application/usecase`가 "usecase"로, `application/port/output`이 "port"로 NamedInterface 공개 — `application/service`·`adapter/out/persistence`는 비공개(internal).
```

"모듈 경계 포트 시그니처" 절에 own-type 8·9·10번째 인스턴스(`StrategyCreationPolicyPort`/`ActiveStrategyCountPort`/`StrategyRef`+`StrategyLookupPort`/`StrategyPausePort`) 서술 추가 — 기존 broker↔account(7번째) 서술 뒤에 이어 붙인다.

- [ ] **Step 6: 메모리 갱신**

`project_strategy_config_migration.md`에 C 완료 사실과 최종 커밋 해시를 추가하고, `project_legacy_module_catalog.md`의 "남은 건 strategy-config뿐" 서술을 "strategy-config(11번째)까지 완료 — 레거시 OPEN 잔존물(AccountStatisticsService 등, 위 스펙 문서 "스코프 아웃" 참고)만 남음"으로 갱신.

- [ ] **Step 7: 최종 Commit**

```bash
cd /Users/phs/workspace/kista/kista-api
git add -A -- src/main/java docs/agents/architecture.md docs/agents/constraints.md
git commit -m "$(cat <<'EOF'
feat(modulith): strategy-config @ApplicationModule(CLOSED) 선언 완료

strategy-config 이전 서브프로젝트 C 마무리 — package-info.java로
CLOSED 선언, ApplicationModules.verify() 통과 확인, 전체 테스트
스위트 최종 검증. architecture.md/constraints.md에 신규 모듈 절과
4개 순환 해소 내역(own-type 8~10번째 인스턴스) 반영. 이걸로
strategy-config 이전(A/B/C)이 마무리됐다 — 레거시 OPEN 잔존물은
AccountStatisticsService 등 stats 재배치 대상만 남는다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0124FVgbsXcNyZx7hqHEyPpR
EOF
)"
```

(메모리 파일 수정은 git 추적 대상이 아니므로 별도 커밋 불필요 — Step 6에서 직접 파일만 갱신)

---

## Self-Review 체크

- [x] **스펙 커버리지**: spec의 4개 순환(admin/user/broker/trading+notify) 전부 Task 3/4/5/(6+7+8)에 매핑. trading 결합 실측 표의 4개 분류(죽은12/상수3/기계28/재설계1)가 Task 2(상수)+Task7(죽은+기계+재설계)+Task9(2차결합)에 전부 커버됨. 모듈 구조(Section 1)는 Task 10~11. 파킹된 6건은 Task 1(3번 항목은 명시적 스킵).
- [x] **placeholder 스캔**: "TBD"/"적절히 처리" 없음 — 모든 Step이 실행 가능한 코드/명령.
- [x] **타입 일관성**: `StrategyRef`(6필드+4메서드)가 Task 6 정의·Task 7 소비·Task 8(notify) 소비 전부 동일. `StrategyCreationRequest`(5필드)가 Task 9 정의·인터페이스·3구현체·StrategyService 호출부 전부 동일 순서. `StrategyCreationPolicyPort.find()` 반환 타입(`Optional<trading.StrategyCreationSettings>`)이 Task 3 정의·admin 구현·StrategyService 소비 동일. `ActiveStrategyCountPort.countActiveByUserId(UUID): long`이 Task 4 정의·구현·소비 동일.
- [x] **순서 검증**: Task 2(상수 추출)가 Task 7(trading 벌크 치환) 이전에 실행돼 `Strategy.DEFAULT_DIVISION_COUNT` 잔존 문제 없음. Task 3(admin)~Task 9(resolver)가 전부 Task 10(물리 이동) 이전 — spec의 "레거시 OPEN 상태에서 역방향 엣지 먼저" 순서 원칙 준수. Task 6(own-type 정의)이 Task 7(소비) 이전.
