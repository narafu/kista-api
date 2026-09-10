# com.kista.common 모듈 소멸 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.common`(임시 개방 `@ApplicationModule(Type.OPEN)` 패키지)의 4개 파일(`CycleLookups`/`Sha256`/`TimeZones`/`UsTradeDates`)을 각자 성격에 맞는 목적지(`StrategyCyclePort` default 메서드 / `com.kista.platform.crypto` / `com.kista.sharedkernel`)로 이전하고 패키지 자체를 소멸시킨다.

**Architecture:** `CycleLookups`는 유일하게 실제 순환 위험(common→trading 엣지)을 가진 헬퍼라 `StrategyCyclePort` default 메서드로 흡수한다. `Sha256`/`TimeZones`/`UsTradeDates`는 JDK-only 순수 유틸이라 위치만 옮기는 순수 정리이며, `UsTradeDates`는 이동과 동시에 사용처를 ArchUnit 클래스 단위 allowlist로 강제한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, ArchUnit, JUnit5 + Mockito

**Spec:** `docs/superpowers/specs/2026-09-10-common-module-dissolution-design.md`

## Global Constraints

- 커밋 author: `narafu <narafu@kakao.com>` (`git config user.name/user.email`로 확인)
- 커밋 메시지: 한글, Conventional Commit 접두사 + 명령형 제목, 끝에 `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01CmpgTDqKWivzmZjD6wd9AZ`
- `git push`는 사용자가 명시적으로 요청할 때만
- 각 태스크 완료 후 별도 검토자(리뷰어 서브에이전트) 검수 필수 — 실결함 발견 시 커밋 전 수정
- `docs/superpowers/plans/*.md`/`docs/superpowers/specs/*.md`는 과거 시점 기록이므로 이번 작업에서 갱신하지 않음(문서 동기화 대상 아님)
- 인코딩 주의: import 라인을 스크립트로 일괄 치환한 뒤 BOM 삽입 여부 확인 — `grep -rl $'\xef\xbb\xbf' src --include="*.java"`가 비어있어야 함(`.claude/rules/java-encoding.md`)

---

## Task 1: CycleLookups → StrategyCyclePort.requireLatestByStrategyId

**Files:**
- Modify: `src/main/java/com/kista/trading/application/port/output/StrategyCyclePort.java`
- Modify (call site + import 제거): `src/main/java/com/kista/admin/application/service/AdminReorderService.java:4,72`
- Modify: `src/main/java/com/kista/admin/application/service/AdminTradeCorrectionService.java:4,60`
- Modify: `src/main/java/com/kista/trading/adapter/out/MockSimulationDataAdapter.java:8,43`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java:3,100,339`
- Modify: `src/main/java/com/kista/trading/application/service/StrategyService.java:5,449`
- Modify: `src/main/java/com/kista/trading/application/service/ManualTradingService.java:5,66`
- Modify: `src/main/java/com/kista/trading/application/service/OrderCancelService.java:5,49`
- Modify: `src/main/java/com/kista/trading/application/service/VrReconfigureService.java:7,67`
- Modify (stub rename, 1:1): `src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java:201,220`
- Modify: `src/test/java/com/kista/admin/application/service/AdminTradeCorrectionServiceTest.java:90,129`
- Modify: `src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java:134,278`
- Modify: `src/test/java/com/kista/trading/application/service/OrderCancelServiceTest.java:103,127,156,178`
- Modify: `src/test/java/com/kista/trading/application/service/VrReconfigureServiceTest.java:124`
- Modify: `src/test/java/com/kista/trading/application/service/TradingServiceTest.java:247,319,353,1568`
- Modify (2줄만, 나머지 8곳은 무관): `src/test/java/com/kista/trading/application/service/StrategyServiceTest.java:323,343`
- Delete: `src/main/java/com/kista/common/CycleLookups.java`
- Delete: `src/test/java/com/kista/common/CycleLookupsTest.java`
- Create: `src/test/java/com/kista/trading/application/port/output/StrategyCyclePortTest.java`

**Interfaces:**
- Produces: `StrategyCyclePort.requireLatestByStrategyId(UUID strategyId): StrategyCycle` (default 메서드, 없으면 `IllegalStateException`)

**⚠ 핵심 함정 (advisor 검토로 확정):** `CycleLookups.requireLatestCycle`은 정적 메서드라 실제 실행되며 내부에서 mock의 `findLatestByStrategyId`를 호출해 기존 테스트 stub이 통했다. default 메서드로 옮기면 **Mockito가 default 메서드를 override해 null 반환** — `findLatestByStrategyId`만 stub하고 `requireLatestByStrategyId`를 stub하지 않으면 NPE. 아래는 실제 프로덕션 호출 경로를 하나씩 추적해 확정한 정확한 낙진 목록이다(추측 아님) — `AdminReorderService`/`AdminTradeCorrectionService`/`ManualTradingService`/`OrderCancelService`/`VrReconfigureService`/`TradingService` 6개 클래스는 `findLatestByStrategyId`를 `CycleLookups` 경유로만 쓰므로 해당 테스트의 stub 전부가 대상. `StrategyService`는 `toDetail()`이 `findLatestByStrategyId`를 **직접** 호출하는 별도 경로가 있어(line 464), `updateSeed()`(line 449, `update()`에 newSeed가 양수로 전달될 때만 실행)를 실제로 거치는 테스트 2개(`update_seed_with_holdings_throws`/`update_seed_change_with_no_holdings`)만 rename 대상이고, 나머지 8곳(`getById`/`listByUserId`/`update_without_newSeed`/`update_doesNotLoadCreationSettings`/`update_seed_zero_or_negative_throws`(newSeed=0이라 signum 가드에서 먼저 throw, CycleLookups 자체를 안 탐)/`update_vrSeed_throwsBeforePersistenceMutation`(VR 체크가 먼저 throw, 이미 `lenient()`))는 `toDetail()`/`getById`/`listByUserId` 경로만 타므로 **그대로 둔다**. `MockSimulationDataAdapterTest`는 `findActiveCycleId`(line 43 호출부)를 아예 테스트하지 않아 stub 자체가 없다 — 변경 없음.

- [ ] **Step 1: `StrategyCyclePort`에 default 메서드 추가**

`src/main/java/com/kista/trading/application/port/output/StrategyCyclePort.java`의 `findLatestByStrategyId` 선언 바로 아래에 추가:

```java
    // 전략의 현재 활성 사이클 조회, 없으면 IllegalStateException(400, GlobalExceptionHandler) — 구 CycleLookups.requireLatestCycle
    default StrategyCycle requireLatestByStrategyId(UUID strategyId) {
        return findLatestByStrategyId(strategyId)
                .orElseThrow(() -> new IllegalStateException("활성 사이클 없음: strategyId=" + strategyId));
    }
```

- [ ] **Step 2: 프로덕션 호출부 9곳 치환**

`AdminReorderService.java`: import 제거 + 치환
```java
// 제거
import com.kista.common.CycleLookups;
```
```java
// AS-IS (line 72)
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
// TO-BE
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
```

`AdminTradeCorrectionService.java`: import 제거 + 동일 패턴(line 60, `strategy.id()`).

`MockSimulationDataAdapter.java`: import 제거 +
```java
// AS-IS (line 43)
        return CycleLookups.requireLatestCycle(strategyCyclePort, strategyId).id();
// TO-BE
        return strategyCyclePort.requireLatestByStrategyId(strategyId).id();
```

`TradingService.java`: import 제거 + (line 100, 339 동일 텍스트 — 둘 다 치환)
```java
// AS-IS (2곳 동일)
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
// TO-BE
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
```

`StrategyService.java`: `CycleLookups` import만 제거(`TimeZones` import는 Task 3까지 유지) +
```java
// AS-IS (line 449)
        StrategyCycle cycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategyId);
// TO-BE
        StrategyCycle cycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
```

`ManualTradingService.java`: import 제거 +
```java
// AS-IS (line 66)
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
// TO-BE
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
```

`OrderCancelService.java`: import 제거 +
```java
// AS-IS (line 49)
        var currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
// TO-BE
        var currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
```

`VrReconfigureService.java`: import 제거 +
```java
// AS-IS (line 67)
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategyId);
// TO-BE
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategyId);
```

- [ ] **Step 3: 컴파일 확인 (테스트는 아직 깨진 상태)**

Run: `./gradlew compileJava`
Expected: SUCCESS (프로덕션 코드만 확인, 테스트는 Step 4~5에서 처리)

- [ ] **Step 4: 6개 단순 클래스 테스트 stub rename**

각 파일에서 `strategyCyclePort.findLatestByStrategyId(X)` → `strategyCyclePort.requireLatestByStrategyId(X)`로 **메서드명 치환 + 반환값 언래핑을 함께** 바꾼다 — `requireLatestByStrategyId`는 `StrategyCycle`을 직접 반환하므로 `when(...).thenReturn(Optional.of(cycle))` → `when(...).thenReturn(cycle)` (메서드명만 바꾸면 반환 타입 불일치로 컴파일 자체가 깨진다). 대상 6파일의 stub 전부 `Optional.of(...)` 형태임을 이미 확인했다(`Optional.empty()` 케이스 없음) — 분기 처리 불필요:

  - `AdminReorderServiceTest.java:201,220` — 둘 다 `Optional.of(cycle())` 패턴 → `cycle()` 직접 반환으로 변경
  - `AdminTradeCorrectionServiceTest.java:90,129` — `Optional.of(cycle)` → `cycle`
  - `ManualTradingServiceTest.java:134,278` — `Optional.of(CYCLE)`/`Optional.of(vrCycle)` → `CYCLE`/`vrCycle`
  - `OrderCancelServiceTest.java:103,127,156,178` — `Optional.of(currentCycle)` → `currentCycle`
  - `VrReconfigureServiceTest.java:124` — `Optional.of(currentCycle)` → `currentCycle`
  - `TradingServiceTest.java:247,319,353,1568` — `Optional.of(STRATEGY_CYCLE)` → `STRATEGY_CYCLE`

예시(`OrderCancelServiceTest.java` line 103):
```java
// AS-IS
        when(strategyCyclePort.findLatestByStrategyId(cycleId)).thenReturn(Optional.of(currentCycle));
// TO-BE
        when(strategyCyclePort.requireLatestByStrategyId(cycleId)).thenReturn(currentCycle);
```

- [ ] **Step 5: `StrategyServiceTest` 2곳만 rename (나머지 8곳은 그대로 둔다)**

```java
// line 323, AS-IS
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
// TO-BE
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);
```
```java
// line 343, AS-IS
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));
// TO-BE
        when(strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID)).thenReturn(CYCLE);
```

(line 303·638·1465·1516·1556·1585·1618·380은 `toDetail()`/`getById`/`listByUserId`/VR-우선-체크 경로만 타므로 무변경.)

- [ ] **Step 6: `CycleLookupsTest` 삭제, `StrategyCyclePortTest` 신규 작성**

`src/test/java/com/kista/common/CycleLookupsTest.java` 삭제.

`src/test/java/com/kista/trading/application/port/output/StrategyCyclePortTest.java` 신규 생성:

```java
package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

// StrategyCyclePort.requireLatestByStrategyId default 메서드 검증 — 구 CycleLookupsTest 이관.
// 순수 Mockito mock은 default 메서드를 override해 본문을 실행하지 않으므로(docs/agents/testing.md
// "Mockito + interface default 메서드 주의"), CALLS_REAL_METHODS로 default 본문이 실제 실행되게 한다.
@Execution(ExecutionMode.SAME_THREAD)
class StrategyCyclePortTest {

    private final StrategyCyclePort strategyCyclePort =
            mock(StrategyCyclePort.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

    // 활성 사이클 조회 시 참조할 전략 ID
    private static final UUID STRATEGY_ID = UUID.randomUUID();

    // 활성 사이클 존재 시 사용할 샘플 사이클
    private static final StrategyCycle CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY_ID, UUID.randomUUID(),
            BigDecimal.valueOf(1000), null,
            LocalDate.of(2026, 7, 1), null,
            null, null
    );

    @Test
    @DisplayName("활성 사이클이 존재하면 해당 사이클을 반환한다")
    void returnsCycleWhenPresent() {
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.of(CYCLE));

        StrategyCycle result = strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID);

        assertThat(result).isEqualTo(CYCLE);
    }

    @Test
    @DisplayName("활성 사이클이 없으면 strategyId를 포함한 IllegalStateException을 던진다")
    void throwsWhenAbsent() {
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> strategyCyclePort.requireLatestByStrategyId(STRATEGY_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("활성 사이클 없음")
                .hasMessageContaining(STRATEGY_ID.toString());
    }
}
```

- [ ] **Step 7: 삭제 대상 파일 제거**

```bash
rm src/main/java/com/kista/common/CycleLookups.java
```

- [ ] **Step 8: 관련 테스트 전체 실행**

Run: `./gradlew test --tests 'com.kista.trading.application.port.output.StrategyCyclePortTest' --tests 'com.kista.admin.application.service.AdminReorderServiceTest' --tests 'com.kista.admin.application.service.AdminTradeCorrectionServiceTest' --tests 'com.kista.trading.application.service.ManualTradingServiceTest' --tests 'com.kista.trading.application.service.OrderCancelServiceTest' --tests 'com.kista.trading.application.service.VrReconfigureServiceTest' --tests 'com.kista.trading.application.service.TradingServiceTest' --tests 'com.kista.trading.application.service.StrategyServiceTest' --tests 'com.kista.trading.adapter.out.MockSimulationDataAdapterTest'`
Expected: PASS 전체 — 실패 시 `build/test-results/test/TEST-*.xml`에서 `grep -oP 'failures="\K[^"]+'`로 실패 클래스 확인 후 스텁 대상 재검토

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(trading): CycleLookups를 StrategyCyclePort default 메서드로 흡수

com.kista.common 모듈 소멸 1/5 — 유일하게 순환 위험(common→trading)을
갖던 CycleLookups.requireLatestCycle을 StrategyCyclePort.
requireLatestByStrategyId default 메서드로 이동. Mockito가 default
메서드를 override하는 함정 때문에 9개 호출부에 딸린 기존 테스트
stub도 함께 rename(findLatestByStrategyId→requireLatestByStrategyId,
Optional 언래핑 제거).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmpgTDqKWivzmZjD6wd9AZ
EOF
)"
```

---

## Task 2: Sha256 → com.kista.platform.crypto

**Files:**
- Create: `src/main/java/com/kista/platform/crypto/Sha256.java`
- Delete: `src/main/java/com/kista/common/Sha256.java`
- Create: `src/test/java/com/kista/platform/crypto/Sha256Test.java`
- Delete: `src/test/java/com/kista/common/Sha256Test.java`
- Modify: `src/main/java/com/kista/user/application/service/TokenService.java:3`
- Modify: `src/main/java/com/kista/broker/adapter/out/toss/TossRedisTokenStore.java:3`

**Interfaces:**
- Produces: `com.kista.platform.crypto.Sha256.hex(String): String` (기존과 동일 시그니처, 패키지만 변경)

- [ ] **Step 1: `Sha256.java` 이동**

`src/main/java/com/kista/common/Sha256.java` 내용을 그대로 `src/main/java/com/kista/platform/crypto/Sha256.java`로 생성하되 패키지 선언만 변경:

```java
package com.kista.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// SHA-256 다이제스트를 16진 문자열로 변환하는 공용 헬퍼 — RT 해시(TokenService)·Toss token fingerprint(TossRedisTokenStore) 중복 구현 통합
public final class Sha256 {

    private Sha256() {}

    public static String hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘 없음", exception);
        }
    }
}
```

```bash
rm src/main/java/com/kista/common/Sha256.java
```

- [ ] **Step 2: 호출부 import 갱신**

`TokenService.java`:
```java
// AS-IS
import com.kista.common.Sha256;
// TO-BE
import com.kista.platform.crypto.Sha256;
```

`TossRedisTokenStore.java`: 동일 치환.

- [ ] **Step 3: 테스트 파일 이동**

`src/test/java/com/kista/common/Sha256Test.java`의 첫 줄 `package com.kista.common;`을 `package com.kista.platform.crypto;`로 바꾼 내용을 `src/test/java/com/kista/platform/crypto/Sha256Test.java`로 생성(나머지 내용 무변경), 원본 삭제:

```bash
mkdir -p src/test/java/com/kista/platform/crypto
```

(내용은 기존 `Sha256Test.java`를 그대로 복사 — 패키지 선언 한 줄만 다름. 실제로는 `git mv` 후 sed로 처리)

```bash
git mv src/test/java/com/kista/common/Sha256Test.java src/test/java/com/kista/platform/crypto/Sha256Test.java
sed -i '' 's/^package com\.kista\.common;/package com.kista.platform.crypto;/' src/test/java/com/kista/platform/crypto/Sha256Test.java
```

- [ ] **Step 4: 컴파일 + 테스트**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS

Run: `./gradlew test --tests 'com.kista.platform.crypto.Sha256Test'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(platform): Sha256을 com.kista.platform.crypto로 이동

com.kista.common 모듈 소멸 2/5 — JDK-only 순수 유틸이라 로직
무변경, 패키지만 AccountNoHasher 옆으로 이동.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmpgTDqKWivzmZjD6wd9AZ
EOF
)"
```

---

## Task 3: TimeZones → com.kista.sharedkernel

**Files:**
- Create: `src/main/java/com/kista/sharedkernel/TimeZones.java`
- Delete: `src/main/java/com/kista/common/TimeZones.java`
- Modify (import `com.kista.common.TimeZones` → `com.kista.sharedkernel.TimeZones`, 아래 전체 목록):
  - `src/main/java/com/kista/notify/adapter/in/telegram/TelegramBotService.java`
  - `src/main/java/com/kista/broker/adapter/out/kis/KisTokenCoordinator.java`
  - `src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java`
  - `src/main/java/com/kista/broker/adapter/out/kis/KisAuthApi.java`
  - `src/main/java/com/kista/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapter.java`
  - `src/main/java/com/kista/web/TradingCycleController.java`
  - `src/main/java/com/kista/admin/adapter/in/web/AdminTradeController.java`
  - `src/main/java/com/kista/admin/application/service/AdminQueryService.java`
  - `src/main/java/com/kista/admin/application/service/AdminReorderService.java`
  - `src/main/java/com/kista/admin/application/service/AdminService.java`
  - `src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java`
  - `src/main/java/com/kista/trading/application/service/TradingService.java`
  - `src/main/java/com/kista/trading/application/service/StrategyService.java`
  - `src/main/java/com/kista/trading/application/service/CycleSnapshotCreator.java`
  - `src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java`
  - `src/main/java/com/kista/trading/domain/model/StrategyCycle.java`
  - `src/main/java/com/kista/trading/domain/model/DstInfo.java`
  - `src/main/java/com/kista/stats/adapter/in/schedule/KbLandPriceIndexScheduler.java`
  - `src/main/java/com/kista/stats/adapter/in/schedule/MarketIndexPriceSyncScheduler.java`
  - `src/main/java/com/kista/stats/application/service/AccountStatisticsService.java`
  - `src/main/java/com/kista/stats/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java`
  - `src/main/java/com/kista/stats/application/service/MonthlyReturnCalculator.java`
  - `src/main/java/com/kista/stats/application/service/StatsService.java`
  - `src/main/java/com/kista/stats/application/service/MarketIndexPriceSyncService.java`
  - `src/main/java/com/kista/finance/adapter/in/schedule/FinanceRegistrationReminderScheduler.java`
  - `src/main/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshScheduler.java`
  - `src/main/java/com/kista/market/application/service/FearGreedQueryService.java`
  - `src/main/java/com/kista/market/domain/model/MarketSessionSnapshot.java`
  - `src/test/java/com/kista/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapterTest.java`
  - `src/test/java/com/kista/stats/application/service/MonthlyReturnCalculatorTest.java`
  - `src/test/java/com/kista/stats/application/service/StatsServiceTest.java`
  - `src/test/java/com/kista/trading/application/service/StrategyServiceTest.java`
  - `src/test/java/com/kista/trading/application/service/TradingServiceTest.java`
- Modify (static import, 별도 패턴): `src/test/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshSchedulerTest.java:13`
- Modify (FQN 인라인, import문 없음): `src/test/java/com/kista/stats/application/service/MarketIndexPriceSyncServiceTest.java:67`

**Interfaces:**
- Produces: `com.kista.sharedkernel.TimeZones.KST: ZoneId`, `com.kista.sharedkernel.TimeZones.KST_ID: String` (필드명 무변경)

- [ ] **Step 1: `TimeZones.java` 이동**

```java
package com.kista.sharedkernel;

import java.time.ZoneId;

// KST(Asia/Seoul) 단일 소스 — ZoneId.of("Asia/Seoul") 인라인 반복 금지
public final class TimeZones {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // @Scheduled(zone=...) 등 컴파일타임 상수 String이 필요한 위치 전용
    public static final String KST_ID = "Asia/Seoul";

    private TimeZones() {}
}
```

파일: `src/main/java/com/kista/sharedkernel/TimeZones.java` 신규 생성 후

```bash
rm src/main/java/com/kista/common/TimeZones.java
```

- [ ] **Step 2: 일반 import 34곳 일괄 치환**

아래 스크립트로 위 "Modify" 목록(정규 import 케이스, static import·FQN 인라인 2건 제외) 전체를 한 번에 치환한다. **치환 전 파일 목록이 실제와 일치하는지 diff로 먼저 검증** — 목록에 있지만 실제로는 import가 없는 파일은 sed가 조용히 no-op하고 아무 신호도 없으므로, 손으로 옮겨 적은 목록을 신뢰하지 않고 검증한다:

```bash
FILES=(
  src/main/java/com/kista/notify/adapter/in/telegram/TelegramBotService.java
  src/main/java/com/kista/broker/adapter/out/kis/KisTokenCoordinator.java
  src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java
  src/main/java/com/kista/broker/adapter/out/kis/KisAuthApi.java
  src/main/java/com/kista/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapter.java
  src/main/java/com/kista/web/TradingCycleController.java
  src/main/java/com/kista/admin/adapter/in/web/AdminTradeController.java
  src/main/java/com/kista/admin/application/service/AdminQueryService.java
  src/main/java/com/kista/admin/application/service/AdminReorderService.java
  src/main/java/com/kista/admin/application/service/AdminService.java
  src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java
  src/main/java/com/kista/trading/application/service/TradingService.java
  src/main/java/com/kista/trading/application/service/StrategyService.java
  src/main/java/com/kista/trading/application/service/CycleSnapshotCreator.java
  src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java
  src/main/java/com/kista/trading/domain/model/StrategyCycle.java
  src/main/java/com/kista/trading/domain/model/DstInfo.java
  src/main/java/com/kista/stats/adapter/in/schedule/KbLandPriceIndexScheduler.java
  src/main/java/com/kista/stats/adapter/in/schedule/MarketIndexPriceSyncScheduler.java
  src/main/java/com/kista/stats/application/service/AccountStatisticsService.java
  src/main/java/com/kista/stats/adapter/in/schedule/KbLandHousingBenchmarkScheduler.java
  src/main/java/com/kista/stats/application/service/MonthlyReturnCalculator.java
  src/main/java/com/kista/stats/application/service/StatsService.java
  src/main/java/com/kista/stats/application/service/MarketIndexPriceSyncService.java
  src/main/java/com/kista/finance/adapter/in/schedule/FinanceRegistrationReminderScheduler.java
  src/main/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshScheduler.java
  src/main/java/com/kista/market/application/service/FearGreedQueryService.java
  src/main/java/com/kista/market/domain/model/MarketSessionSnapshot.java
  src/test/java/com/kista/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapterTest.java
  src/test/java/com/kista/stats/application/service/MonthlyReturnCalculatorTest.java
  src/test/java/com/kista/stats/application/service/StatsServiceTest.java
  src/test/java/com/kista/trading/application/service/StrategyServiceTest.java
  src/test/java/com/kista/trading/application/service/TradingServiceTest.java
)
# 목록 검증 — 실제 import 보유 파일 집합과 정확히 일치해야 함(둘 다 비어야 diff 통과)
grep -rln "import com\.kista\.common\.TimeZones;" src | sort > /tmp/actual-timezones-files.txt
printf '%s\n' "${FILES[@]}" | sort > /tmp/planned-timezones-files.txt
diff /tmp/actual-timezones-files.txt /tmp/planned-timezones-files.txt
# diff 출력이 있으면 목록을 실제에 맞게 고치고 재실행 — 비어있을 때만 다음 치환 진행
for f in "${FILES[@]}"; do
  sed -i '' 's/import com\.kista\.common\.TimeZones;/import com.kista.sharedkernel.TimeZones;/' "$f"
done
```

- [ ] **Step 3: static import + FQN 인라인 2건 개별 치환**

```java
// src/test/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshSchedulerTest.java:13
// AS-IS
import static com.kista.common.TimeZones.KST;
// TO-BE
import static com.kista.sharedkernel.TimeZones.KST;
```

```java
// src/test/java/com/kista/stats/application/service/MarketIndexPriceSyncServiceTest.java:67
// AS-IS
        LocalDate today = LocalDate.now(com.kista.common.TimeZones.KST);
// TO-BE
        LocalDate today = LocalDate.now(com.kista.sharedkernel.TimeZones.KST);
```

- [ ] **Step 4: BOM 오염 확인 (java-encoding.md 규칙)**

Run: `grep -rl $'\xef\xbb\xbf' src --include="*.java"`
Expected: 출력 없음(비어있음) — 있으면 `sed -i '' '1s/^\xef\xbb\xbf//' <파일>`로 제거

- [ ] **Step 5: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS — `com.kista.common.TimeZones` 참조가 남아있으면 컴파일 에러로 즉시 드러남

- [ ] **Step 6: 관련 테스트 실행**

Run: `./gradlew test --tests 'com.kista.market.*' --tests 'com.kista.privacy.*' --tests 'com.kista.stats.*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(sharedkernel): TimeZones를 com.kista.sharedkernel로 이동

com.kista.common 모듈 소멸 3/5 — JDK-only 순수 상수, 도메인 클래스
(DstInfo/StrategyCycle/MarketSessionSnapshot)가 이미 참조 중이라
sharedkernel(outbound-zero) 이동이 전제를 명확히 함. 34개 호출부
import 경로만 갱신, 로직 무변경.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmpgTDqKWivzmZjD6wd9AZ
EOF
)"
```

---

## Task 4: UsTradeDates → com.kista.sharedkernel + ArchUnit allowlist 강제

**Files:**
- Create: `src/main/java/com/kista/sharedkernel/UsTradeDates.java`
- Delete: `src/main/java/com/kista/common/UsTradeDates.java`
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java:4`
- Modify: `src/main/java/com/kista/broker/adapter/out/kis/KisTradingApi.java:4`
- Modify: `src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java:7`
- Modify: `src/main/java/com/kista/market/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java:3`
- Modify: `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java` (신규 규칙 추가 + `platform_must_not_depend_on_other_modules` 예외 절 제거)
- Modify: `src/main/java/com/kista/sharedkernel/package-info.java`
- Modify: `src/main/java/com/kista/platform/package-info.java`

**Interfaces:**
- Produces: `com.kista.sharedkernel.UsTradeDates.toUsTradeDate/toKstTradeDate(LocalDate): LocalDate` (시그니처 무변경)

- [ ] **Step 1: `UsTradeDates.java` 이동**

```java
package com.kista.sharedkernel;

import java.time.LocalDate;

// KST 거래일 ↔ US 거래일 변환 — US 기준 외부 데이터(KIS API, 휴장일 캘린더)를 만나는 어댑터 내부 전용.
// KST 거래일(매매 정산 아침)은 항상 US 거래일 다음날이므로 단순 ±1일이 성립한다.
// 도메인·서비스·persistence(orders)에서는 사용 금지 — 전 구간 KST 단일 기준.
// 사용처는 HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters가
// 클래스 단위 allowlist로 강제한다.
public final class UsTradeDates {

    // KST 거래일 → US 거래일. 예: KST 5/27 → US 5/26
    public static LocalDate toUsTradeDate(LocalDate kstTradeDate) {
        return kstTradeDate.minusDays(1);
    }

    // US 거래일 → KST 거래일. 예: US 5/26 → KST 5/27
    public static LocalDate toKstTradeDate(LocalDate usTradeDate) {
        return usTradeDate.plusDays(1);
    }

    private UsTradeDates() {}
}
```

```bash
rm src/main/java/com/kista/common/UsTradeDates.java
```

- [ ] **Step 2: 4개 호출부 import 갱신**

```bash
for f in \
  src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java \
  src/main/java/com/kista/broker/adapter/out/kis/KisTradingApi.java \
  src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java \
  src/main/java/com/kista/market/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java \
; do
  sed -i '' 's/import com\.kista\.common\.UsTradeDates;/import com.kista.sharedkernel.UsTradeDates;/' "$f"
done
```

- [ ] **Step 3: `HexagonalArchitectureTest`에 allowlist 규칙 신설**

`platform_must_not_depend_on_other_modules` 테스트 메서드 바로 아래에 추가:

```java
    @Test
    @DisplayName("UsTradeDates는 4개 KIS/Toss/캘린더 어댑터에서만 사용한다 — 시간 기준 정책 allowlist 강제")
    void usTradeDates_must_only_be_used_by_allowlisted_adapters() {
        // constraints.md "시간 기준 정책" allowlist를 실제로 강제 — US 거래일 변환은 이 4개 어댑터 내부
        // 전용, 도메인·서비스·orders persistence에서 사용 금지. 클래스 단위 allowlist(메서드 단위 아님) —
        // TossPriceApi는 getClosingPrice 메서드만 실사용하지만 메서드 단위 강제는 ArchUnit 복잡도
        // 대비 이득이 낮음. ClassFileImporter가 테스트 클래스도 import하므로(DoNotIncludeTests 미지정),
        // 향후 어댑터 테스트가 기대값 계산에 UsTradeDates를 직접 쓰면 이 규칙이 함께 걸린다 —
        // 그때는 allowlist에 추가할지 검토할 것.
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.kista.sharedkernel..")
                .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.kis.KisTradingApi")
                .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.kis.KisPriceApi")
                .and().doNotHaveFullyQualifiedName("com.kista.broker.adapter.out.toss.TossPriceApi")
                .and().doNotHaveFullyQualifiedName("com.kista.market.adapter.out.persistence.calendar.MarketCalendarPersistenceAdapter")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.kista.sharedkernel.UsTradeDates");
        rule.check(classes);
    }
```

- [ ] **Step 4: `platform_must_not_depend_on_other_modules` 예외 절 제거**

```java
// AS-IS
    @Test
    @DisplayName("platform은 common 외 다른 com.kista 모듈에 의존하지 않는다 — 인프라 leaf 불변식")
    void platform_must_not_depend_on_other_modules() {
        // platform은 persistence base·crypto·스케쥴러 골격 등 순수 인프라만 담는다는 전제로 OPEN 선언됨 —
        // 이 패키지가 다른 애그리게이트 모듈을 참조하는 순간 인프라 leaf 전제가 깨진다 (sharedkernel과 동일 강제).
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.platform..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.platform.."))
                                .and(resideOutsideOfPackage("com.kista.common..")));
        rule.check(classes);
    }
// TO-BE
    @Test
    @DisplayName("platform은 다른 com.kista 모듈에 의존하지 않는다 — 인프라 leaf 불변식")
    void platform_must_not_depend_on_other_modules() {
        // platform은 persistence base·crypto·스케쥴러 골격 등 순수 인프라만 담는다는 전제로 OPEN 선언됨 —
        // 이 패키지가 다른 애그리게이트 모듈을 참조하는 순간 인프라 leaf 전제가 깨진다 (sharedkernel과 동일 강제).
        // com.kista.common 소멸(모듈 경계 재구성 #3)로 예외 절도 함께 제거 — 이제 sharedkernel과 완전히 동일한 outbound-zero.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.kista.platform..")
                .should().dependOnClassesThat(
                        resideInAPackage("com.kista..")
                                .and(resideOutsideOfPackage("com.kista.platform..")));
        rule.check(classes);
    }
```

- [ ] **Step 5: package-info 헌장 재작성 (advisor 검토로 발견된 문구 충돌 해소)**

`src/main/java/com/kista/sharedkernel/package-info.java`:
```java
// AS-IS
// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사하되
// 순수 값 타입(enum)만 담는다. common/과 달리 기술 유틸이 아닌 도메인 개념이라 별도 패키지로 분리.
// outbound reference 0인 타입만 여기 둔다 — 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
// TO-BE
// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사.
// outbound reference 0인 도메인 값 타입(enum) + JDK-only 유틸(TimeZones/UsTradeDates)만 둔다 —
// 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
```

`src/main/java/com/kista/platform/package-info.java`:
```java
// AS-IS
// 전역 인프라 leaf — persistence base entity, 대칭키 암호화, 스케쥴러 공통 골격.
// Spring/JPA 바인딩이 있어 com.kista.common(순수 유틸)과 분리한다. Type.OPEN이되
// HexagonalArchitectureTest.platform_must_not_depend_on_other_modules가 outbound-zero(→common만 허용)를 강제한다
// — sharedkernel과 동일하게 "OPEN은 outbound-zero를 증명할 때만 안전" 원칙.
// TO-BE
// 전역 인프라 leaf — persistence base entity, 대칭키 암호화, 스케쥴러 공통 골격.
// Spring/JPA 바인딩이 있어 sharedkernel(순수 JDK)과 분리한다. Type.OPEN이되
// HexagonalArchitectureTest.platform_must_not_depend_on_other_modules가 outbound-zero를 강제한다
// — sharedkernel과 동일하게 "OPEN은 outbound-zero를 증명할 때만 안전" 원칙.
```

- [ ] **Step 6: 컴파일 + 아키텍처 테스트**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS (신설 allowlist 규칙 포함, `ModulithArchitectureTest.verify()` GREEN)

Run: `./gradlew test --tests 'com.kista.broker.adapter.out.kis.*' --tests 'com.kista.broker.adapter.out.toss.*' --tests 'com.kista.market.adapter.out.persistence.calendar.*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(sharedkernel): UsTradeDates 이동 + 사용처 ArchUnit allowlist 강제

com.kista.common 모듈 소멸 4/5 — UsTradeDates를 sharedkernel로 옮기고,
constraints.md에 문서로만 있던 4클래스 allowlist(KisTradingApi/
KisPriceApi/TossPriceApi/MarketCalendarPersistenceAdapter)를
usTradeDates_must_only_be_used_by_allowlisted_adapters ArchUnit
규칙으로 실제 강제. platform_must_not_depend_on_other_modules의
common 예외 절 제거, sharedkernel/platform package-info 헌장 문구를
기술 유틸 포함 사실에 맞게 재작성(advisor 검토로 발견된 충돌 해소).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmpgTDqKWivzmZjD6wd9AZ
EOF
)"
```

---

## Task 5: com.kista.common 패키지 소멸 + 문서 동기화 + 최종 검증

**Files:**
- Delete: `src/main/java/com/kista/common/package-info.java`
- Delete 확인: `src/main/java/com/kista/common/` 디렉토리 자체(Task 1~4로 4파일 모두 이미 삭제됨 — 남는 건 이 package-info뿐)
- Modify: `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java` (죽은 `"com.kista.common.."` 항목 제거 — Task 4 리뷰 발견)
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/docker-infra.md`
- Modify: `README.md`
- Modify: `/Users/phs/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/project_module_boundary_roadmap.md` (메모리, 프로젝트 밖 경로)

- [ ] **Step 1: 전역 grep으로 잔존 참조 0건 확인**

Run: `grep -rn "com\.kista\.common" --exclude-dir=build --exclude-dir=.git . | grep -v "docs/superpowers/plans/\|docs/superpowers/specs/"`
Expected: 0건(Task 1~4가 모두 끝났다면 살아있는 코드·문서에는 참조가 없어야 함). 남아있으면 해당 파일을 먼저 고친다.

- [ ] **Step 2: `com.kista.common` 패키지 삭제**

```bash
rm -rf src/main/java/com/kista/common
```

- [ ] **Step 3: `docs/agents/architecture.md` 갱신**

**(Task 4 리뷰에서 발견된 플랜 결함 — Ruling으로 추가됨)** 파일 최상단(8~11번째 줄)에 `com.kista.common` 자체를 소개하는 트리 블록이 별도로 있다 — 앞선 grep(`com\.kista\.common`)이 접두사 없는 `common/` 표기를 놓쳐 이번 플랜 최초 작성 시 빠졌다. 이 블록 전체를 삭제한다:
```
// AS-IS (8~11번째 줄, 코드 펜스 바로 다음)
common/          ← 공통 유틸리티 (Spring/JPA 독립)
  UsTradeDates   — KST↔US 거래일 ±1일 변환. 사용 허용 위치: KisTradingApi(KIS API는 US 거래일 기준)·MarketCalendarPersistenceAdapter·KisPriceApi(dailyprice BYMD)·TossPriceApi(getClosingPrice — Toss 캔들 date는 US 세션일)뿐 — 도메인·서비스·orders persistence에서 사용 금지 (→ constraints.md "시간 기준 정책")

DB 스키마 3분리(kista/finance/reference): ...(다음 문단 그대로 유지)
// TO-BE
DB 스키마 3분리(kista/finance/reference): ...(다음 문단 그대로 유지)
```
(즉 `common/` 두 줄 + 그 뒤 빈 줄 하나를 삭제하고, "DB 스키마 3분리" 문단부터 코드 펜스 첫 내용으로 이어지게 한다.)

`com.kista.platform/` 절 첫 문장(현재 16번째 줄 근처):
```
// AS-IS
com.kista.platform/  ← 전역 인프라 leaf 모듈. `@ApplicationModule(Type.OPEN)` — sharedkernel과 동일하게 outbound-zero를 증명할 때만 안전한 OPEN이며, `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`가 이를 강제한다(`com.kista.common`만 허용, 그 외 `com.kista..`는 전부 금지 — `sharedkernel_must_not_depend_on_other_modules` 미러). Spring/JPA 바인딩이 있어 순수 Spring 비의존 유틸 패키지 `com.kista.common`과 분리한다.
// TO-BE
com.kista.platform/  ← 전역 인프라 leaf 모듈. `@ApplicationModule(Type.OPEN)` — sharedkernel과 동일하게 outbound-zero를 증명할 때만 안전한 OPEN이며, `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`가 이를 강제한다(`com.kista.platform..` 외 `com.kista..`는 전부 금지 — `sharedkernel_must_not_depend_on_other_modules` 미러). Spring/JPA 바인딩이 있어 sharedkernel(순수 JDK)과 분리한다.
```

`com.kista.sharedkernel/` 절 마지막에 이동한 두 유틸 반영(기존 문장 끝의 "broker의 `BrokerAccountRef`는 sharedkernel.Broker를 직접 참조한다(record 자체는 자격증명 투영 own-type으로 존속)" 뒤에 문장 추가):
```
 담긴 값 타입 외 JDK-only 유틸 2종도 함께 둔다: `TimeZones`(KST 단일 소스)·`UsTradeDates`(KST↔US 거래일 변환, 사용처는 `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`가 4클래스로 강제 — 구 `com.kista.common`, 모듈 경계 재구성 #3으로 이관).
```

`com.kista.platform/crypto/` 절(`crypto/` 줄)에 `Sha256` 추가:
```
// AS-IS
  crypto/        ← AesCryptoService(AES-256, persistence 경계에서만 사용)/AccountNoHasher(계좌번호 결정론적 HMAC-SHA256 해시 — 전역 중복 체크용)
// TO-BE
  crypto/        ← AesCryptoService(AES-256, persistence 경계에서만 사용)/AccountNoHasher(계좌번호 결정론적 HMAC-SHA256 해시 — 전역 중복 체크용)/Sha256(RT 해시·Toss token fingerprint 공용 헬퍼 — 구 com.kista.common, 모듈 경계 재구성 #3으로 이관)
```

`CycleLookups` 관련 서술이 architecture.md에 별도로 없으므로(코드베이스 맵에 헬퍼 단위까지는 안 나와 있음) 추가 수정 불필요.

- [ ] **Step 4: `docs/agents/constraints.md` 갱신**

`platform` 소개 문단(26번째 줄 근처):
```
// AS-IS
- persistence base entity(`BaseAuditEntity`/`BaseCreatedAtEntity`/`JpaAuditingConfig`)·대칭키 암호화(`AesCryptoService`/`AccountNoHasher`)·스케쥴러 공통 골격(`SchedulerJobRunner`/`SchedulerLockService`/`SchedulerLifecycleEvent`)은 `com.kista.platform`(`@ApplicationModule(Type.OPEN)`, 인프라 leaf)에 추가한다 — `com.kista.common` 외 다른 `com.kista` 모듈을 참조하면 `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`가 빌드를 깬다(outbound-zero 유지, sharedkernel과 동일 강제)
// TO-BE
- persistence base entity(`BaseAuditEntity`/`BaseCreatedAtEntity`/`JpaAuditingConfig`)·대칭키 암호화(`AesCryptoService`/`AccountNoHasher`/`Sha256`)·스케쥴러 공통 골격(`SchedulerJobRunner`/`SchedulerLockService`/`SchedulerLifecycleEvent`)은 `com.kista.platform`(`@ApplicationModule(Type.OPEN)`, 인프라 leaf)에 추가한다 — 다른 `com.kista` 모듈을 참조하면 `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`가 빌드를 깬다(outbound-zero 유지, sharedkernel과 동일 강제)
```

"시간 기준 정책 (KST 단일 기준)" 절의 `UsTradeDates` allowlist 문장에 강제 수단 한 줄 추가:
```
// AS-IS
- `UsTradeDates` 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter`, `KisPriceApi`(dailyprice BYMD 파라미터), `TossPriceApi.getClosingPrice`(Toss 일봉 캔들 `date()`는 US 세션일 기준) — 도메인·서비스·orders persistence에서 사용 금지
// TO-BE
- `UsTradeDates` 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter`, `KisPriceApi`(dailyprice BYMD 파라미터), `TossPriceApi.getClosingPrice`(Toss 일봉 캔들 `date()`는 US 세션일 기준) — 도메인·서비스·orders persistence에서 사용 금지. `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`가 이 4클래스 allowlist를 실제로 강제한다(모듈 경계 재구성 #3)
```

"Spring Modulith 이전 중 신규 파일 배치" 절에 `com.kista.common` 언급이 없으므로 추가 수정 불필요(확인 결과 이 절은 common을 직접 언급하지 않음).

**(Task 4 리뷰에서 발견된 죽은 코드 — Ruling으로 추가됨)** `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java`의 `matching_must_not_depend_on_other_modules` 금지 패키지 목록에 `"com.kista.common.."`이 남아있다 — 패키지 소멸로 대상이 없어져 무해하지만, `com.kista.common` 완전 소멸의 일부로 함께 정리한다:
```java
// AS-IS
                        "com.kista.web..", "com.kista.platform..", "com.kista.common..");
// TO-BE
                        "com.kista.web..", "com.kista.platform..");
```

- [ ] **Step 5: `docs/agents/docker-infra.md` 갱신**

```
// AS-IS (5번째 줄)
- 해결 정책(`45758166`): `KistaApplication.main()`의 전역 `TimeZone.setDefault()` 의존 제거 — 모든 `LocalDate.now()`/`LocalTime.now()` 호출부에 `TimeZones.KST`(`com.kista.common.TimeZones`)를 명시. 신규 호출부 추가 시 반드시 `LocalDate.now(TimeZones.KST)` 형태 사용, `KistaApplication`에 전역 설정 재도입 금지
// TO-BE
- 해결 정책(`45758166`): `KistaApplication.main()`의 전역 `TimeZone.setDefault()` 의존 제거 — 모든 `LocalDate.now()`/`LocalTime.now()` 호출부에 `TimeZones.KST`(`com.kista.sharedkernel.TimeZones`)를 명시. 신규 호출부 추가 시 반드시 `LocalDate.now(TimeZones.KST)` 형태 사용, `KistaApplication`에 전역 설정 재도입 금지
```

- [ ] **Step 6: `README.md` 갱신**

```
// AS-IS
레이어 의존 방향(`adapter → application → domain`)은 ArchUnit(`HexagonalArchitectureTest`)이 빌드 시 강제 검증한다. 아래 다이어그램은 레이어 관계를 보여주는 일반 도해다. 실제로는 10개 애그리게이트(`finance`·`notify`·`broker`·`trading`·`market`·`privacy`·`stats`·`admin`·`user`·`account`)가 전부 Spring Modulith 모듈로 이전됐고(`strategyconfig`는 2026-09-07 `trading`으로 병합), 레거시 최상위 shim(`com.kista.{domain,application,adapter}`)은 소멸했다 — 잔존물은 `com.kista.common`(순수 유틸)뿐이며, 크로스모듈 컨트롤러·전역 예외 핸들러는 `com.kista.web`(앱셸 CLOSED sink), persistence base·암호화·스케쥴러 골격은 `com.kista.platform`(인프라 leaf OPEN)에 있다. `ApplicationModules.verify()`가 모듈 경계까지 GREEN으로 검증한다 (상세 → `docs/agents/architecture.md` "Spring Modulith 모듈 구성", 마이그레이션 경위는 `docs/agents/modulith-migration-history.md`).
// TO-BE
레이어 의존 방향(`adapter → application → domain`)은 ArchUnit(`HexagonalArchitectureTest`)이 빌드 시 강제 검증한다. 아래 다이어그램은 레이어 관계를 보여주는 일반 도해다. 실제로는 11개 애그리게이트(`finance`·`notify`·`broker`·`trading`·`matching`·`market`·`privacy`·`stats`·`admin`·`user`·`account`)가 전부 Spring Modulith 모듈로 이전됐고(`strategyconfig`는 2026-09-07 `trading`으로 병합, `matching`은 주문생성 커널 추출로 신설), 레거시 최상위 shim(`com.kista.{domain,application,adapter,common}`)은 전부 소멸했다 — 크로스모듈 컨트롤러·전역 예외 핸들러는 `com.kista.web`(앱셸 CLOSED sink), persistence base·암호화·스케쥴러 골격·순수 공용 유틸은 `com.kista.platform`(인프라 leaf OPEN)·`com.kista.sharedkernel`(전역 공용 어휘 OPEN)에 있다. `ApplicationModules.verify()`가 모듈 경계까지 GREEN으로 검증한다 (상세 → `docs/agents/architecture.md` "Spring Modulith 모듈 구성", 마이그레이션 경위는 `docs/agents/modulith-migration-history.md`).
```

(⚠ "10개"→"11개" 및 모듈 목록에 `matching` 추가는 이번 작업 범위 밖의 기존 드리프트 수정이지만, common 문구를 고치는 같은 줄이라 함께 정정한다 — 스펙에 명시된 판단.)

- [ ] **Step 7: 로드맵 메모리 갱신**

`/Users/phs/.claude/projects/-Users-phs-workspace-kista-kista-api/memory/project_module_boundary_roadmap.md`에서 #3 항목을 "완료"로 갱신(파일을 Read 후 해당 항목만 수정 — 전체 내용 재작성 금지).

- [ ] **Step 8: 최종 컴파일 + 아키텍처 테스트**

Run: `./gradlew compileJava compileTestJava`
Expected: SUCCESS

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 9: 전체 테스트 스위트 최종 1회**

Run: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 클래스 특정

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): com.kista.common 모듈 소멸 완료

com.kista.common 모듈 소멸 5/5 — 4파일(CycleLookups/Sha256/
TimeZones/UsTradeDates) 이전 완료 후 패키지+@ApplicationModule
선언 삭제. architecture.md/constraints.md/docker-infra.md/
README.md를 실제 구조에 맞춰 동기화(README 애그리게이트 수 10→11
드리프트도 같은 줄에서 함께 정정).

모듈 경계 재구성 로드맵 #3 완료 — #1(matching 커널 추출)·#2(enum
sharedkernel 승격)·#4(notify 분리)에 이어 로드맵 전체 완료.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmpgTDqKWivzmZjD6wd9AZ
EOF
)"
```

---

## 검증 체크리스트 (self-review)

- [x] 스펙의 4파일 이동 전부 태스크로 커버됨(Task 1~4)
- [x] 스펙의 ArchUnit 신설/수정 규칙 전부 커버됨(Task 4)
- [x] 스펙의 문서 동기화 목록 전부 커버됨(Task 5) — `docs/superpowers/plans/*.md`는 스펙 판단대로 제외
- [x] Mockito 낙진 대상 파일·라인을 실제 프로덕션 호출 경로 추적으로 확정(추측 없음) — Task 1 Step 4/5
- [x] placeholder 없음 — 모든 스텝에 실제 AS-IS/TO-BE 코드 또는 정확한 스크립트
- [x] 타입/시그니처 일관성 — `requireLatestByStrategyId(UUID): StrategyCycle`가 Task 1 전체에서 동일하게 사용됨
