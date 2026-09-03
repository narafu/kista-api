# StrategyVersion/InfiniteDetail/VrDetail trading 이관 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **이 세션에서는 사용자 지시로 서브에이전트를 쓰지 않는다 — executing-plans 방식(인라인, 체크포인트별 검증)으로 진행한다.**

**Goal:** `StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail`(레거시 `com.kista.domain.model.strategy`)과 대응 포트·persistence·`VrStrategyLifecycle`을 `com.kista.trading` 소유로 이관해, `CycleSnapshotCreator.reconfigureVrCycle()`의 strategy-config↔trading 모듈 경계 결합을 trading 내부 호출로 바꾼다.

**Architecture:** 순수 구조 이전 — 로직 변경 없음. TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클(account/user 선례와 동일). trading은 이미 CLOSED 모듈이라 신규 `@ApplicationModule` 선언이나 NamedInterface 신설이 필요 없다 — 기존 "domain"/"port"/"usecase" NamedInterface에 항목이 늘어날 뿐이다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5, Python(이동 스크립트 — 이 환경 BSD sed는 `\|` alternation을 리터럴 취급해 조용히 no-op하므로 사용 금지, subproject A에서 검증된 python 스크립트 패턴 재사용)

**Spec:** `docs/superpowers/specs/2026-09-02-strategy-version-detail-to-trading-design.md`

## Global Constraints

- 커밋 전 검토자 검수 필수(전역 CLAUDE.md 규칙) — 이 세션은 서브에이전트 금지 지시가 있으므로 컨트롤러(나) 자신이 diff를 직접 검토한다(subproject A 때와 동일 방식: 중복 import·잔존 옛 경로 grep + 컴파일 + 전체 테스트로 확인).
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit.
- `StrategyVersionEntity`의 `@SQLRestriction("deleted_at IS NULL")`이 `findMaxVersionNoByStrategyId`(JPQL, non-native)에 자동 적용된다 — `CycleSnapshotCreator.reconfigureVrCycle()`의 "nextVersionNo는 반드시 소프트 삭제보다 먼저 계산" 주석과 호출 순서를 그대로 보존한다(로직·순서 변경 금지).
- 모든 이동 대상 테이블(`strategy_version`/`strategy_infinite_version`/`strategy_vr_version`)은 `kista` 스키마 — Flyway/DDL 변경 없음, 패키지 이동만.
- 전체 `./gradlew test`는 Task 2 완료 후 최종 1회만(전역 CLAUDE.md 규칙) — Task 1/2 각각은 관련 범위로 좁힌 `--tests` 실행으로 검증.
- 이 계획 완료가 strategy-config 이전 전체(서브프로젝트 C)의 완료를 의미하지 않는다 — `Strategy`/`StrategyPort`/`StrategyUseCase`/`RegisterStrategyCommand`/`UpdateStrategyCommand`/`StrategySeedPreview`는 레거시 위치 그대로 잔류.

---

## File Structure (이동/신설 대상)

```
com.kista.trading.domain.model/          (기존 패키지에 추가)
  StrategyVersion.java                    ← MOVE (com.kista.domain.model.strategy)
  StrategyInfiniteDetail.java             ← MOVE
  StrategyVrDetail.java                   ← MOVE
  VrSummary.java                          ← 신규 (StrategyDetail.VrSummary 승격, Task 2)

com.kista.trading.application.port.output/  (기존 6개 → 9개)
  StrategyVersionPort.java                ← MOVE (com.kista.application.port.output)
  StrategyInfiniteDetailPort.java         ← MOVE
  StrategyVrDetailPort.java               ← MOVE

com.kista.trading.application.usecase/   (기존 패키지에 추가)
  VrStrategyDetailUseCase.java            ← 신규 (Task 2, VrStrategyLifecycle 6개 메서드 계약화)

com.kista.trading.application.service/   (기존 패키지에 추가, internal)
  VrStrategyLifecycle.java                ← MOVE+구현 (com.kista.application.service.strategy, Task 2)

com.kista.trading.adapter.out.persistence/  (기존 패키지에 추가, internal)
  StrategyVersionEntity.java              ← MOVE
  StrategyVersionJpaRepository.java       ← MOVE
  StrategyVersionPersistenceAdapter.java  ← MOVE (public 유지)
  StrategyInfiniteEntity.java             ← MOVE
  StrategyInfiniteJpaRepository.java      ← MOVE
  StrategyInfiniteDetailPersistenceAdapter.java ← MOVE
  StrategyVrVersionEntity.java            ← MOVE
  StrategyVrVersionJpaRepository.java     ← MOVE
  StrategyVrDetailPersistenceAdapter.java ← MOVE

src/test/java/com/kista/trading/domain/model/
  StrategyVrDetailTest.java               ← MOVE (com/kista/domain/model/strategy)

src/test/java/com/kista/trading/adapter/out/persistence/
  StrategyVersionPersistenceAdapterTest.java ← MOVE
  StrategyVrDetailPersistenceAdapterTest.java ← MOVE
  StrategyVrSchemaTest.java                ← MOVE

src/test/java/com/kista/trading/application/service/
  VrStrategyLifecycleTest.java            ← MOVE (Task 2)
```

**잔류(레거시, 이번 스코프 아님)**: `Strategy.java`/`StrategyDetail.java`(단, `vr` 필드 타입 변경, Task 2)/`RegisterStrategyCommand.java`/`UpdateStrategyCommand.java`/`StrategySeedPreview.java` + `StrategyEntity`/`StrategyJpaRepository`/`StrategyPersistenceAdapter`/`PersistenceSupport.java`(레거시, `com.kista.adapter.out.persistence.strategy`) + `StrategyPersistenceAdapterTest.java`.

---

## Task 1: domain + port + persistence 물리 이전

**Files:**
- Move: 위 File Structure의 domain 3개 + port 3개 + persistence 9개 + 테스트 4개(`StrategyVrDetailTest`/`StrategyVersionPersistenceAdapterTest`/`StrategyVrDetailPersistenceAdapterTest`/`StrategyVrSchemaTest`)
- Modify (import 경로만): 아래 "Step 3" 스크립트가 처리하는 전체 소비 파일(`StrategyService.java`/`VrStrategyLifecycle.java`(레거시 위치, Task 2에서 다시 손댐)/`CycleSnapshotCreator.java`/`CycleRotationService.java`/`CyclePositionPersistor.java`/`CycleOrderComputer.java`/`VrCycleRolloverService.java`/`VrReconfigureService.java`/`BacktestEngine.java` + 대응 테스트 전체)
- Modify (수동, 스크립트 범위 밖): `StrategyPersistenceAdapterTest.java`(import 추가), `CyclePositionPersistenceAdapterTest.java`/`StrategyCycleVrPersistenceAdapterTest.java`(자기참조 import 삭제)

**Interfaces:**
- Produces: `com.kista.trading.domain.model.{StrategyVersion,StrategyInfiniteDetail,StrategyVrDetail}`, `com.kista.trading.application.port.output.{StrategyVersionPort,StrategyInfiniteDetailPort,StrategyVrDetailPort}` — Task 2가 그대로 소비.

- [ ] **Step 1: git mv — domain 3개 + port 3개**

```bash
cd /Users/phs/workspace/kista/kista-api
git mv src/main/java/com/kista/domain/model/strategy/StrategyVersion.java src/main/java/com/kista/trading/domain/model/StrategyVersion.java
git mv src/main/java/com/kista/domain/model/strategy/StrategyInfiniteDetail.java src/main/java/com/kista/trading/domain/model/StrategyInfiniteDetail.java
git mv src/main/java/com/kista/domain/model/strategy/StrategyVrDetail.java src/main/java/com/kista/trading/domain/model/StrategyVrDetail.java
git mv src/main/java/com/kista/application/port/output/StrategyVersionPort.java src/main/java/com/kista/trading/application/port/output/StrategyVersionPort.java
git mv src/main/java/com/kista/application/port/output/StrategyInfiniteDetailPort.java src/main/java/com/kista/trading/application/port/output/StrategyInfiniteDetailPort.java
git mv src/main/java/com/kista/application/port/output/StrategyVrDetailPort.java src/main/java/com/kista/trading/application/port/output/StrategyVrDetailPort.java
```

- [ ] **Step 2: git mv — persistence 9개 + 테스트 4개**

```bash
cd /Users/phs/workspace/kista/kista-api
for f in StrategyVersionEntity StrategyVersionJpaRepository StrategyVersionPersistenceAdapter \
         StrategyInfiniteEntity StrategyInfiniteJpaRepository StrategyInfiniteDetailPersistenceAdapter \
         StrategyVrVersionEntity StrategyVrVersionJpaRepository StrategyVrDetailPersistenceAdapter; do
  git mv "src/main/java/com/kista/adapter/out/persistence/strategy/${f}.java" "src/main/java/com/kista/trading/adapter/out/persistence/${f}.java"
done
git mv src/test/java/com/kista/domain/model/strategy/StrategyVrDetailTest.java src/test/java/com/kista/trading/domain/model/StrategyVrDetailTest.java
git mv src/test/java/com/kista/adapter/out/persistence/strategy/StrategyVersionPersistenceAdapterTest.java src/test/java/com/kista/trading/adapter/out/persistence/StrategyVersionPersistenceAdapterTest.java
git mv src/test/java/com/kista/adapter/out/persistence/strategy/StrategyVrDetailPersistenceAdapterTest.java src/test/java/com/kista/trading/adapter/out/persistence/StrategyVrDetailPersistenceAdapterTest.java
git mv src/test/java/com/kista/adapter/out/persistence/strategy/StrategyVrSchemaTest.java src/test/java/com/kista/trading/adapter/out/persistence/StrategyVrSchemaTest.java
```

- [ ] **Step 3: package 선언 일괄 수정 (moved 파일 16개)**

```python
import re

moves = {
    "src/main/java/com/kista/trading/domain/model/StrategyVersion.java": ("com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    "src/main/java/com/kista/trading/domain/model/StrategyInfiniteDetail.java": ("com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    "src/main/java/com/kista/trading/domain/model/StrategyVrDetail.java": ("com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    "src/main/java/com/kista/trading/application/port/output/StrategyVersionPort.java": ("com.kista.application.port.output", "com.kista.trading.application.port.output"),
    "src/main/java/com/kista/trading/application/port/output/StrategyInfiniteDetailPort.java": ("com.kista.application.port.output", "com.kista.trading.application.port.output"),
    "src/main/java/com/kista/trading/application/port/output/StrategyVrDetailPort.java": ("com.kista.application.port.output", "com.kista.trading.application.port.output"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyVersionEntity.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyVersionJpaRepository.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyVersionPersistenceAdapter.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyInfiniteEntity.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyInfiniteJpaRepository.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyInfiniteDetailPersistenceAdapter.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyVrVersionEntity.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyVrVersionJpaRepository.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/main/java/com/kista/trading/adapter/out/persistence/StrategyVrDetailPersistenceAdapter.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/test/java/com/kista/trading/domain/model/StrategyVrDetailTest.java": ("com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    "src/test/java/com/kista/trading/adapter/out/persistence/StrategyVersionPersistenceAdapterTest.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/test/java/com/kista/trading/adapter/out/persistence/StrategyVrDetailPersistenceAdapterTest.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
    "src/test/java/com/kista/trading/adapter/out/persistence/StrategyVrSchemaTest.java": ("com.kista.adapter.out.persistence.strategy", "com.kista.trading.adapter.out.persistence"),
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

Run: `python3 <위 스크립트 저장 경로>`
Expected: `package line fixed in 19 files`

- [ ] **Step 4: 저장소 전역 import 경로 치환 (6개 심볼)**

```python
import re, subprocess

root = "."
symbols = [
    ("StrategyVersion", "com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    ("StrategyInfiniteDetail", "com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    ("StrategyVrDetail", "com.kista.domain.model.strategy", "com.kista.trading.domain.model"),
    ("StrategyVersionPort", "com.kista.application.port.output", "com.kista.trading.application.port.output"),
    ("StrategyInfiniteDetailPort", "com.kista.application.port.output", "com.kista.trading.application.port.output"),
    ("StrategyVrDetailPort", "com.kista.application.port.output", "com.kista.trading.application.port.output"),
]

out = subprocess.run(["grep", "-rl", "-E",
    r"^import com\.kista\.(domain\.model\.strategy|application\.port\.output)\.(StrategyVersion|StrategyInfiniteDetail|StrategyVrDetail|StrategyVersionPort|StrategyInfiniteDetailPort|StrategyVrDetailPort);",
    f"{root}/src/main/java", f"{root}/src/test/java", "--include=*.java"],
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

Run: `python3 <위 스크립트 저장 경로>`
Expected: `files to fix` 와 `files changed`가 동일 숫자로 출력 (mismatch면 즉시 조사 — 부분 실패 의미)

- [ ] **Step 5: `StrategyVersionPersistenceAdapter.java` 주석 갱신 (public 유지 이유 확장)**

파일: `src/main/java/com/kista/trading/adapter/out/persistence/StrategyVersionPersistenceAdapter.java`

```java
// com.kista.trading.adapter.out.persistence의 CyclePositionPersistenceAdapterTest/StrategyCycleVrPersistenceAdapterTest가
// @DataJpaTest 픽스처로 직접 @Import/@Autowired하고, 레거시 com.kista.adapter.out.persistence.strategy의
// StrategyPersistenceAdapterTest도 크로스모듈로 동일하게 픽스처 삼으므로 public 유지 (모듈 경계상 레거시는 OPEN이라 안전)
```

(기존 주석의 "com.kista.trading.adapter.out.persistence의 ... 픽스처로 직접 @Import/@Autowired하므로" 줄을 위 내용으로 교체 — public 접근제어자와 `@Component` 선언 자체는 그대로 유지)

- [ ] **Step 6: 테스트 파일 자기참조 import 2개 삭제**

`src/test/java/com/kista/trading/adapter/out/persistence/CyclePositionPersistenceAdapterTest.java`와 `StrategyCycleVrPersistenceAdapterTest.java`에서 아래 줄을 삭제(Step 2 이동 후 같은 패키지가 돼 불필요):

```java
import com.kista.adapter.out.persistence.strategy.StrategyVersionPersistenceAdapter;
```

- [ ] **Step 7: `StrategyPersistenceAdapterTest.java`에 크로스모듈 import 추가**

파일: `src/test/java/com/kista/adapter/out/persistence/strategy/StrategyPersistenceAdapterTest.java` — import 블록에 아래 줄 추가(다른 `com.kista.*` import 마지막 줄 다음):

```java
import com.kista.trading.adapter.out.persistence.StrategyVersionPersistenceAdapter;
```

- [ ] **Step 8: 잔존 옛 경로 확인**

```bash
grep -rn "com\.kista\.domain\.model\.strategy\.StrategyVersion\b\|com\.kista\.domain\.model\.strategy\.StrategyInfiniteDetail\b\|com\.kista\.domain\.model\.strategy\.StrategyVrDetail\b\|com\.kista\.application\.port\.output\.StrategyVersionPort\b\|com\.kista\.application\.port\.output\.StrategyInfiniteDetailPort\b\|com\.kista\.application\.port\.output\.StrategyVrDetailPort\b" src/main/java src/test/java --include="*.java"
```

Expected: 결과 없음(빈 출력). 결과가 있으면 해당 줄 확인 후 수동 수정.

- [ ] **Step 9: 컴파일**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `BUILD SUCCESSFUL` 2회(compileJava, compileTestJava 각각) — 에러 있으면 해당 파일 import 문 직접 확인 후 수정(대부분 와일드카드 미비 케이스).

- [ ] **Step 10: 관련 테스트만 좁혀 실행**

```bash
./gradlew test --tests 'com.kista.trading.*' --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.adapter.out.persistence.strategy.StrategyPersistenceAdapterTest' --tests 'com.kista.stats.application.service.BacktestServiceTest' --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: Commit**

```bash
git add -A -- src/main/java src/test/java
git commit -m "$(cat <<'EOF'
refactor(modulith): StrategyVersion/InfiniteDetail/VrDetail trading 이관

strategy-config 이전 서브프로젝트 B 1/2 — 버전별 실행 파라미터
(StrategyVersion/StrategyInfiniteDetail/StrategyVrDetail)와 대응 포트·
persistence를 trading 소유로 옮긴다. CycleSnapshotCreator.reconfigureVrCycle()이
이 타입들을 trading 내부에서만 다루게 돼 모듈 경계를 넘는 결합이 사라진다.
로직 변경 없음 — 순수 패키지 이전.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Sn8G139yswRobsPb97YnFB
EOF
)"
```

---

## Task 2: VrStrategyLifecycle → VrStrategyDetailUseCase 승격 + VrSummary 승격

**Files:**
- Create: `src/main/java/com/kista/trading/application/usecase/VrStrategyDetailUseCase.java`
- Create: `src/main/java/com/kista/trading/domain/model/VrSummary.java`
- Move+Modify: `src/main/java/com/kista/application/service/strategy/VrStrategyLifecycle.java` → `src/main/java/com/kista/trading/application/service/VrStrategyLifecycle.java`
- Move: `src/test/java/com/kista/application/service/strategy/VrStrategyLifecycleTest.java` → `src/test/java/com/kista/trading/application/service/VrStrategyLifecycleTest.java`
- Modify: `src/main/java/com/kista/domain/model/strategy/StrategyDetail.java` (nested `VrSummary` 제거, `vr` 필드 타입 변경)
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java` (필드 타입 1곳, 타입 참조 4곳)
- Modify: `src/main/java/com/kista/trading/application/service/CycleSnapshotCreator.java` (필드 타입 1곳)
- Modify: `src/main/java/com/kista/adapter/in/web/dto/TradingCycleResponse.java` (파라미터 타입 1곳)
- Modify: `src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java` (타입 참조 2곳)

**Interfaces:**
- Consumes: Task 1의 `com.kista.trading.domain.model.{StrategyVersion,StrategyInfiniteDetail,StrategyVrDetail}`, `com.kista.trading.application.port.output.{StrategyVersionPort,StrategyInfiniteDetailPort,StrategyVrDetailPort}`, 기존 `com.kista.trading.application.port.output.StrategyCycleVrPort`, `com.kista.trading.domain.model.{CyclePosition,StrategyCycle,StrategyCycleVrDetail}`.
- Produces: `com.kista.trading.application.usecase.VrStrategyDetailUseCase`(public interface, "usecase" NamedInterface) — `saveVersionDetail(UUID, Integer, BigDecimal, Integer, int, int, int, int, BigDecimal, int, int, BigDecimal): StrategyVrDetail`, `saveInitialCycleDetail(UUID, BigDecimal, BigDecimal, StrategyVrDetail): StrategyCycleVrDetail`, `findSummary(UUID, Optional<StrategyCycle>, Optional<CyclePosition>, Optional<CyclePosition>): Optional<VrSummary>`, `findVrDetailsByVersionIds(Collection<UUID>): Map<UUID, StrategyVrDetail>`, `findCycleVrDetailsByCycleIds(Collection<UUID>): Map<UUID, StrategyCycleVrDetail>`, `buildSummary(StrategyVrDetail, StrategyCycleVrDetail, BigDecimal, BigDecimal): VrSummary`. `com.kista.trading.domain.model.VrSummary`(record, "domain" NamedInterface).

- [ ] **Step 1: `VrSummary.java` 신설 — `StrategyDetail.VrSummary` 필드 그대로 top-level 승격**

파일: `src/main/java/com/kista/trading/domain/model/VrSummary.java`

```java
package com.kista.trading.domain.model;

import java.math.BigDecimal;

// VR 전략 조회 응답 요약 — StrategyVrDetail + StrategyCycleVrDetail 합산 (옛 StrategyDetail.VrSummary 승격)
public record VrSummary(
        BigDecimal value,        // 사이클 시작 시 V값 (실력 기준선)
        BigDecimal bandWidth,    // 밴드 폭 (%, 예: 15.00)
        int intervalWeeks,       // 리밸런싱 주기 (주 단위)
        int recurringAmount,     // 주기당 추가 예수금 (USD, 음수=인출)
        BigDecimal poolLimit,    // 사이클 pool 상한 금액 (USD, 개장 pool×poolLimitRate 파생값)
        BigDecimal currentPool,  // 최신 cycle_position 기준 현재 pool(예수금, USD) — 개장값(initialUsdDeposit)과 다름
        BigDecimal poolLimitRate, // 사이클에 고정된 pool 상한 비율(0~1) — 현재 사이클 고정 스냅샷
        int gradient,            // 실력공식 경사 계수 (G) — 현재 사이클 고정 스냅샷
        // 램프 설정값 — StrategyVrDetail 원본 그대로 노출 (gradientAt/poolLimitRateAt 재계산에 필요)
        int initialGradient,             // 램프 시작 시점(경과 0주)의 gradient(G) 값
        int gGraceWeeks,                 // gradient 램프 시작 전 유예 주수
        int gStepWeeks,                  // gradient가 한 단계 상승하는 주기 (주 단위)
        int gMax,                        // gradient 램프의 상한값
        BigDecimal initialPoolLimitRate, // 램프 시작 시점(경과 0주)의 poolLimitRate 값
        int pGraceWeeks,                 // poolLimitRate 램프 시작 전 유예 주수
        int pStepWeeks,                  // poolLimitRate가 한 단계 하강하는 주기 (주 단위)
        BigDecimal poolLimitFloor        // poolLimitRate 램프의 하한값
) {}
```

- [ ] **Step 2: `StrategyDetail.java` 수정 — nested `VrSummary` 제거, `vr` 필드 타입 교체**

파일: `src/main/java/com/kista/domain/model/strategy/StrategyDetail.java` — 전체를 아래로 교체:

```java
package com.kista.domain.model.strategy;

import com.kista.trading.domain.model.VrSummary;

import java.math.BigDecimal;
import java.time.LocalDate;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialUsdDeposit,
        LocalDate startDate,    // 사이클 시작일(예정) — 미래면 아직 매매 시작 전
        Integer divisionCount,
        boolean isReverseMode,
        Double currentRound,    // INFINITE 전략만 non-null, 이력 없으면 null
        Integer currentHoldings, // 최신 cycle_position 기준 보유 수량
        VrSummary vr            // VR 전략만 non-null, 비VR은 null (com.kista.trading.domain.model.VrSummary)
) {}
```

- [ ] **Step 3: `VrStrategyDetailUseCase.java` 신설**

파일: `src/main/java/com/kista/trading/application/usecase/VrStrategyDetailUseCase.java`

```java
package com.kista.trading.application.usecase;

import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.trading.domain.model.StrategyVrDetail;
import com.kista.trading.domain.model.VrSummary;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// VR 전략의 버전 상세·사이클 상세 저장과 요약 조립 — 레거시 StrategyService(향후 strategy-config)가 크로스모듈로 소비
public interface VrStrategyDetailUseCase {

    // 램프 8필드는 호출측(StrategyService)이 이미 null 정규화를 마친 값이라고 가정한다
    StrategyVrDetail saveVersionDetail(UUID strategyVersionId, Integer intervalWeeks,
                                        BigDecimal bandWidth, Integer recurringAmount,
                                        int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                        BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks,
                                        BigDecimal poolLimitFloor);

    // 등록 시점(경과 0주) 스냅샷 — gradientAt(0)/poolLimitRateAt(0)은 각각 initialGradient/initialPoolLimitRate와 동일
    StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                                  BigDecimal initialValue, StrategyVrDetail vrDetail);

    // openingPosition: 호출측(StrategyService.toDetail)이 이미 조회한 개장 포지션 — 여기서 재조회하지 않는다
    // latestPosition: 라이브 pool(currentPool) 노출용 — 없으면(이력 없음) currentPool=null
    Optional<VrSummary> findSummary(UUID strategyId, Optional<StrategyCycle> latestCycle,
                                     Optional<CyclePosition> openingPosition,
                                     Optional<CyclePosition> latestPosition);

    // 목록 조립(StrategyService.toDetails) 전용 배치 조회
    Map<UUID, StrategyVrDetail> findVrDetailsByVersionIds(Collection<UUID> strategyVersionIds);

    Map<UUID, StrategyCycleVrDetail> findCycleVrDetailsByCycleIds(Collection<UUID> cycleIds);

    // openingPool: 조회 대상 사이클 개장 포지션의 USD pool — poolLimit 달러 파생(openingPool × poolLimitRate)에 사용
    // currentPool: 최신 cycle_position 기준 현재 pool — null이면(이력 없음) 그대로 null 노출
    VrSummary buildSummary(StrategyVrDetail vrDetail, StrategyCycleVrDetail cycleVr,
                            BigDecimal openingPool, BigDecimal currentPool);
}
```

- [ ] **Step 4: `VrStrategyLifecycle.java` 이동 + 구현체 전환**

```bash
cd /Users/phs/workspace/kista/kista-api
git mv src/main/java/com/kista/application/service/strategy/VrStrategyLifecycle.java src/main/java/com/kista/trading/application/service/VrStrategyLifecycle.java
git mv src/test/java/com/kista/application/service/strategy/VrStrategyLifecycleTest.java src/test/java/com/kista/trading/application/service/VrStrategyLifecycleTest.java
```

`src/main/java/com/kista/trading/application/service/VrStrategyLifecycle.java` 전체를 아래로 교체(패키지 변경 + `implements VrStrategyDetailUseCase` + 전 메서드 `public` + `VrSummary` 타입 사용 + `@Override` 추가):

```java
package com.kista.trading.application.service;

import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyCycleVrDetail;
import com.kista.trading.domain.model.StrategyVrDetail;
import com.kista.trading.domain.model.VrSummary;
import com.kista.trading.application.port.output.StrategyCycleVrPort;
import com.kista.trading.application.port.output.StrategyVrDetailPort;
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// VR 전략의 버전 상세·사이클 상세 저장과 요약 조립을 담당 — VrStrategyDetailUseCase 구현체
@Component
@RequiredArgsConstructor
class VrStrategyLifecycle implements VrStrategyDetailUseCase {

    private final StrategyVrDetailPort strategyVrDetailPort;
    private final StrategyCycleVrPort strategyCycleVrPort;

    @Override
    public StrategyVrDetail saveVersionDetail(UUID strategyVersionId, Integer intervalWeeks,
                                       BigDecimal bandWidth, Integer recurringAmount,
                                       int initialGradient, int gGraceWeeks, int gStepWeeks, int gMax,
                                       BigDecimal initialPoolLimitRate, int pGraceWeeks, int pStepWeeks,
                                       BigDecimal poolLimitFloor) {
        int normalizedRecurringAmount = recurringAmount != null ? recurringAmount : 0;
        return strategyVrDetailPort.save(
                new StrategyVrDetail(strategyVersionId, intervalWeeks, bandWidth, normalizedRecurringAmount,
                        initialGradient, gGraceWeeks, gStepWeeks, gMax,
                        initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor));
    }

    @Override
    public StrategyCycleVrDetail saveInitialCycleDetail(UUID cycleId, BigDecimal initialUsdDeposit,
                                                 BigDecimal initialValue, StrategyVrDetail vrDetail) {
        BigDecimal initialV = initialValue != null ? initialValue : BigDecimal.ZERO;
        return strategyCycleVrPort.save(
                new StrategyCycleVrDetail(cycleId, initialV, vrDetail.gradientAt(0), vrDetail.poolLimitRateAt(0)));
    }

    @Override
    public Optional<VrSummary> findSummary(UUID strategyId, Optional<StrategyCycle> latestCycle,
                                                    Optional<CyclePosition> openingPosition,
                                                    Optional<CyclePosition> latestPosition) {
        return strategyVrDetailPort.findActiveByStrategyId(strategyId)
                .flatMap(vrDetail -> latestCycle
                        .flatMap(cycle -> strategyCycleVrPort.findByCycleId(cycle.id())
                                .map(cycleVr -> {
                                    BigDecimal openingPool = openingPosition
                                            .map(CyclePosition::usdDeposit)
                                            .orElseThrow(() -> new IllegalStateException(
                                                    "VR 시작 포지션 없음: cycleId=" + cycle.id()));
                                    BigDecimal currentPool = latestPosition.map(CyclePosition::usdDeposit).orElse(null);
                                    return buildSummary(vrDetail, cycleVr, openingPool, currentPool);
                                })));
    }

    @Override
    public Map<UUID, StrategyVrDetail> findVrDetailsByVersionIds(Collection<UUID> strategyVersionIds) {
        return strategyVrDetailPort.findByStrategyVersionIds(strategyVersionIds);
    }

    @Override
    public Map<UUID, StrategyCycleVrDetail> findCycleVrDetailsByCycleIds(Collection<UUID> cycleIds) {
        return strategyCycleVrPort.findByCycleIds(cycleIds);
    }

    @Override
    public VrSummary buildSummary(StrategyVrDetail vrDetail, StrategyCycleVrDetail cycleVr,
                                          BigDecimal openingPool, BigDecimal currentPool) {
        if (vrDetail == null || cycleVr == null) return null;
        if (openingPool == null) {
            throw new IllegalStateException("VR 시작 포지션 없음: openingPool=null");
        }
        BigDecimal poolLimit = openingPool.multiply(cycleVr.poolLimitRate())
                .setScale(2, RoundingMode.HALF_UP);
        return new VrSummary(
                cycleVr.value(), vrDetail.bandWidth(), vrDetail.intervalWeeks(),
                vrDetail.recurringAmount(), poolLimit, currentPool, cycleVr.poolLimitRate(), cycleVr.gradient(),
                vrDetail.initialGradient(), vrDetail.gGraceWeeks(), vrDetail.gStepWeeks(), vrDetail.gMax(),
                vrDetail.initialPoolLimitRate(), vrDetail.pGraceWeeks(), vrDetail.pStepWeeks(), vrDetail.poolLimitFloor());
    }
}
```

`src/test/java/com/kista/trading/application/service/VrStrategyLifecycleTest.java` 상단 import 블록에서 아래 치환(그 외 테스트 바디는 변경 없음 — 구체 클래스 `VrStrategyLifecycle`을 `@InjectMocks`로 그대로 씀):

```
package com.kista.application.service.strategy;   →   package com.kista.trading.application.service;
import com.kista.trading.domain.model.CyclePosition;      (유지)
import com.kista.trading.domain.model.StrategyCycle;      (유지)
import com.kista.trading.domain.model.StrategyCycleVrDetail;  (유지)
import com.kista.domain.model.strategy.StrategyDetail;    →   삭제 (더 이상 참조 없음)
import com.kista.domain.model.strategy.StrategyVrDetail;  →   import com.kista.trading.domain.model.StrategyVrDetail;
import com.kista.trading.application.port.output.StrategyCycleVrPort;  (유지)
import com.kista.application.port.output.StrategyVrDetailPort;  →  import com.kista.trading.application.port.output.StrategyVrDetailPort;
```

파일 내 `StrategyDetail.VrSummary` 타입 참조가 있으면 `VrSummary`로 교체하고 `import com.kista.trading.domain.model.VrSummary;` 추가(테스트가 반환값 타입을 직접 언급하는 지점 — 실제 치환 후 컴파일 에러로 잡히면 그 지점만 수정).

- [ ] **Step 5: `StrategyService.java` 수정 — 필드 타입 1곳 + 타입 참조 4곳**

파일: `src/main/java/com/kista/application/service/strategy/StrategyService.java`

import 블록에 추가:
```java
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
import com.kista.trading.domain.model.VrSummary;
```

필드 선언 교체:
```java
private final VrStrategyLifecycle vrStrategyLifecycle;          // VR 전략 전용 상세 저장·조회
```
→
```java
private final VrStrategyDetailUseCase vrStrategyLifecycle;      // VR 전략 전용 상세 저장·조회
```

`StrategyDetail.VrSummary` 4곳(라인 117/511/569/586 부근, 정확한 라인은 컴파일러 에러로 재확인)을 전부 `VrSummary`로 치환 — 예:
```java
StrategyDetail.VrSummary vrSummary = vrStrategyLifecycle.buildSummary(
```
→
```java
VrSummary vrSummary = vrStrategyLifecycle.buildSummary(
```
`assemble()` 메서드 파라미터 선언도 동일하게:
```java
Integer divisionCount, boolean isReverseMode, StrategyDetail.VrSummary vrSummary) {
```
→
```java
Integer divisionCount, boolean isReverseMode, VrSummary vrSummary) {
```

- [ ] **Step 6: `CycleSnapshotCreator.java` 필드 타입 교체**

파일: `src/main/java/com/kista/trading/application/service/CycleSnapshotCreator.java`

```java
import com.kista.application.service.strategy.VrStrategyLifecycle;
```
→
```java
import com.kista.trading.application.usecase.VrStrategyDetailUseCase;
```

```java
private final VrStrategyLifecycle vrStrategyLifecycle;  // VR 재설정 시 새 버전 상세 저장
```
→
```java
private final VrStrategyDetailUseCase vrStrategyLifecycle;  // VR 재설정 시 새 버전 상세 저장
```

(동일 모듈 내부이므로 인터페이스 경유 여부는 필수는 아니나, trading의 다른 서비스가 usecase를 통해 서로를 소비하는 기존 관례와 맞춘다 — `StrategyVrDetail vrStrategyLifecycle.saveVersionDetail(...)` 호출부는 시그니처 동일해 변경 없음)

- [ ] **Step 7: `TradingCycleResponse.java` 파라미터 타입 교체**

파일: `src/main/java/com/kista/adapter/in/web/dto/TradingCycleResponse.java`

import 블록에 추가: `import com.kista.trading.domain.model.VrSummary;`

```java
static VrSummary from(StrategyDetail.VrSummary s) {
```
→
```java
static VrSummary from(com.kista.trading.domain.model.VrSummary s) {
```

(내부 nested record 이름도 `VrSummary`라 파라미터 타입은 완전정규명으로 명시해 컴파일러가 nested `VrSummary`와 혼동하지 않게 한다 — 필드 접근 코드(`s.value()` 등)는 변경 없음)

- [ ] **Step 8: `TradingCycleControllerTest.java` 타입 참조 2곳 교체**

파일: `src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java`

import 블록에 추가: `import com.kista.trading.domain.model.VrSummary;`

2곳의
```java
StrategyDetail.VrSummary vrSummary = new StrategyDetail.VrSummary(
```
을
```java
VrSummary vrSummary = new VrSummary(
```
로 교체.

- [ ] **Step 9: 잔존 옛 참조 확인**

```bash
grep -rn "StrategyDetail\.VrSummary\|com\.kista\.application\.service\.strategy\.VrStrategyLifecycle" src/main/java src/test/java --include="*.java"
```

Expected: 결과 없음.

- [ ] **Step 10: 컴파일**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `BUILD SUCCESSFUL` 2회.

- [ ] **Step 11: 관련 테스트 + 아키텍처 테스트**

```bash
./gradlew test --tests 'com.kista.trading.*' --tests 'com.kista.application.service.strategy.StrategyServiceTest' --tests 'com.kista.adapter.in.web.TradingCycleControllerTest' --tests 'com.kista.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 12: 전체 스위트 최종 검증**

```bash
docker compose ps postgres 2>&1 | grep -q Up || docker compose up -d postgres
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 13: 문서 갱신**

`docs/agents/architecture.md`의 `com.kista.trading/` 절에 `domain/model` 목록에 `StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail`/`VrSummary` 추가, `application/port/output` 목록에 3개 포트 추가(6→9), `application/usecase` 목록에 `VrStrategyDetailUseCase` 추가, `adapter/out/persistence`에 9개 파일 언급 추가. `docs/agents/constraints.md`의 "Spring Modulith 이전 중 신규 파일 배치" trading 항목에 이번 이관 사실 한 줄 추가. `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`는 수정하지 않음(카탈로그 스펙은 4단계 항목 텍스트 그대로 두고, strategy-config C단계에서 최종 갱신).

- [ ] **Step 14: Commit**

```bash
git add -A -- src/main/java src/test/java docs/agents/architecture.md docs/agents/constraints.md
git commit -m "$(cat <<'EOF'
refactor(modulith): VrStrategyLifecycle→VrStrategyDetailUseCase 승격 + VrSummary trading 이전

strategy-config 이전 서브프로젝트 B 2/2 — VrStrategyLifecycle을 trading
"usecase" NamedInterface로 공개(VrStrategyDetailUseCase)하고, StrategyDetail의
nested VrSummary를 trading 소유 top-level 타입으로 승격한다. 레거시
StrategyService는 이제 이 usecase만 소비 — 모든 필드가 trading 데이터인
값 타입을 nested record로 strategy-config 쪽에 둘 이유가 없었다. 로직 변경
없음. 이걸로 CycleSnapshotCreator.reconfigureVrCycle()의 모듈 경계 결합이
완전히 trading 내부 호출로 정리됐다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Sn8G139yswRobsPb97YnFB
EOF
)"
```

---

## Self-Review 체크

- [x] 스펙 커버리지: domain 3+VrSummary 이관/port 3개 이관/persistence 9개 이관/VrStrategyLifecycle usecase 승격/검증 절차 — 스펙의 모든 절이 Task 1~2에 매핑됨.
- [x] placeholder 스캔: "TBD"/"적절히 처리" 없음 — 전 Step이 실행 가능한 명령·코드.
- [x] 타입 일관성: `VrStrategyDetailUseCase` 메서드 시그니처가 인터페이스(Task 2 Step 3)·구현체(Step 4)·소비자(StrategyService/CycleSnapshotCreator, Step 5~6)에서 동일. `VrSummary` 필드 16개가 신설 타입(Step 1)·구 nested record·소비자(TradingCycleResponse, Step 7) 전부 동일 순서.
