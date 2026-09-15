# kista-trading 분리 3단계(3a+3b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:api`(root) → `:trading-core`의 main 소스 컴파일 의존을 0으로 만든다 — `./gradlew :compileJava`가 trading-core import 없이 통과하고, 동시에 단일 `app.jar`는 여전히 trading-core 클래스를 번들해 현재의 2-role(`kista-api`/`kista-scheduler`) 단일 아티팩트 배포가 무변경으로 계속 동작한다.

**Architecture:** (3a) `sharedkernel`+`platform`을 신규 `:shared` Gradle 서브프로젝트로 기계적 추출 — `:api`·`:trading-core` 둘 다 `:shared`에 의존하는 3-서브프로젝트 그래프로 전환. (3b) root에 남아있는 12건의 실제 trading-core import를 own-type(값 복제) 또는 신규 내부 API(`/api/internal/**`, 기존 `InternalTokenAuthFilter` 재사용) 호출로 교체 — 이미 확립된 `AdminOrderView`/`TradingQueryPort`/`TradingCommandPort` 패턴 재사용. 마지막에 `implementation(project(":trading-core"))`를 `runtimeOnly(project(":trading-core"))`로 전환해 컴파일 클래스패스에서 완전히 제외한다(런타임 클래스패스엔 남아 `bootJar` 번들은 무변경).

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle 멀티프로젝트, JUnit5+Mockito+AssertJ, Flyway.

**Spec:** `docs/superpowers/specs/2026-09-11-kista-trading-service-split-design.md` (3단계 절, 커밋 `c79550cc`+`132dffd0`)

## Global Constraints

- 3b 종료 시점에도 `kista-api`/`kista-scheduler`는 여전히 같은 `app.jar` 하나에서 나온다 — 별도 포트·별도 배포 아티팩트를 만들지 않는다. 프로세스 분리는 4단계(DB 분리) 몫.
- 내부 API 호출은 전부 프로세스 내부 루프백(`INTERNAL_API_BASE_URL` 기본값 `http://localhost:8080`, 같은 JVM) — 신규 엔드포인트도 이 방식을 그대로 따른다.
- 신규 내부 API는 기존 `X-Internal-Token`(`InternalApiErrorDetails`, `INTERNAL_API_TOKEN` 환경변수) 인증을 그대로 재사용한다. 새 인증 방식 도입 금지.
- own-type 신규 파일은 `com.kista.admin.domain.model`/`com.kista.stats.domain.model` 등 **admin/stats/web 자기 소유 패키지**에만 생성한다. trading-core 쪽 원본 타입은 손대지 않는다(포트 시그니처만 admin 쪽 변경).
- 각 태스크 완료 후 `./gradlew test`(해당 모듈 좁혀서: `--tests` 옵션) 그린 확인 — 전체 스위트는 마지막 태스크(runtimeOnly 전환) 직후 1회만.
- 커밋 메시지는 한글, Conventional Commit 접두사, `narafu <narafu@kakao.com>` author.
- 주석 규칙(전역 CLAUDE.md 2.2): 신규 코드 필드/블록에 `//` 인라인 주석, Javadoc 금지.

---

### Task 1: `:shared` 서브프로젝트 추출

**Files:**
- Create: `shared/build.gradle.kts`
- Modify: `settings.gradle.kts` (또는 `settings.gradle` — 실제 확장자 확인 후 반영)
- Modify: `build.gradle.kts` (root — `:shared` 의존 추가)
- Modify: `trading-core/build.gradle.kts` (`:shared` 의존 추가)
- Move: `src/main/java/com/kista/sharedkernel/**` → `shared/src/main/java/com/kista/sharedkernel/**` (root에 있던 경우) 또는 `trading-core/src/main/java/com/kista/sharedkernel/**` → `shared/...`(실제 현재 위치는 `trading-core` 하위 — 1단계 커밋으로 이미 이동 완료된 상태이므로 `trading-core`에서 이동)
- Move: `trading-core/src/main/java/com/kista/platform/**` → `shared/src/main/java/com/kista/platform/**`
- Modify: `src/test/java/com/kista/architecture/HexagonalArchitectureTest.java` — `platform_must_not_depend_on_other_modules`/`sharedkernel_must_not_depend_on_other_modules` 대상 경로 갱신
- Modify: `src/test/java/com/kista/architecture/GradleModuleBoundaryTest.java` — 3-서브프로젝트 그래프(`:shared`/`:trading-core`/`:api`) 기준으로 검증 로직 갱신

**Interfaces:**
- Consumes: 없음(선행 태스크)
- Produces: `:shared` 서브프로젝트, `com.kista.sharedkernel.*`/`com.kista.platform.*` 전체 — 이후 모든 태스크가 이 위치를 그대로 소비(패키지 경로 불변, Gradle 서브프로젝트 소속만 변경)

- [ ] **Step 1: 이동 전 현재 위치·의존 확인**

```bash
find trading-core/src/main/java/com/kista/sharedkernel trading-core/src/main/java/com/kista/platform -name "*.java" | wc -l
grep -n "trading-core" settings.gradle.kts build.gradle.kts trading-core/build.gradle.kts
```

Expected: sharedkernel+platform 파일 목록이 나오고, `settings.gradle.kts`에 `include(":trading-core")`가 있어야 한다.

- [ ] **Step 2: `settings.gradle.kts`에 `:shared` 추가**

`include(":trading-core")` 옆에 `include(":shared")` 추가.

- [ ] **Step 3: `shared/build.gradle.kts` 신설**

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // platform이 Spring/JPA 바인딩을 갖고 있어 필요 — 정확한 좌표는 root build.gradle.kts의
    // 버전 카탈로그(libs.*) 표기를 그대로 따른다(directCoordinate 금지, libs.versions.toml 재사용)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security) // JwtIssuerService 등 platform 인증 유틸이 있다면
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
```

(실제 platform 패키지가 참조하는 Spring 모듈이 위 목록과 다르면 이동 후 컴파일 오류를 보고 추가 — `com.kista.platform.persistence`/`crypto`/`scheduling`/`metrics`/`time` 5개 서브패키지가 실제 쓰는 의존성을 `trading-core/build.gradle.kts`에서 확인하고 그대로 옮긴다.)

- [ ] **Step 4: 디렉터리 이동**

```bash
mkdir -p shared/src/main/java/com/kista
git mv trading-core/src/main/java/com/kista/sharedkernel shared/src/main/java/com/kista/sharedkernel
git mv trading-core/src/main/java/com/kista/platform shared/src/main/java/com/kista/platform
```

sharedkernel/platform 소속 테스트가 `trading-core/src/test/java/com/kista/{sharedkernel,platform}`에 있으면 동일하게 `shared/src/test/java/com/kista/{sharedkernel,platform}`로 이동.

- [ ] **Step 5: root·trading-core build.gradle.kts에 `:shared` 의존 추가**

`build.gradle.kts`(root)와 `trading-core/build.gradle.kts` 양쪽 `dependencies { }` 블록에:

```kotlin
implementation(project(":shared"))
```

- [ ] **Step 6: 컴파일 확인**

```bash
bash gradlew :shared:compileJava :trading-core:compileJava :compileJava
```

Expected: 세 태스크 모두 성공. 실패 시 Step 3의 의존성 목록을 실제 컴파일 오류(`package org.springframework.* does not exist` 등)에 맞춰 보강.

- [ ] **Step 7: ArchUnit 규칙 갱신**

`HexagonalArchitectureTest`에서 `platform_must_not_depend_on_other_modules`/`sharedkernel_must_not_depend_on_other_modules`가 검사하는 클래스 소스 루트가 여전히 `com.kista.platform`/`com.kista.sharedkernel` 패키지 기준이면(패키지명 불변이므로) 수정 불필요 — 단, `importedClasses` 스캔 대상 디렉터리를 `trading-core/build/classes`에서 `shared/build/classes`로 명시하는 코드가 있으면 갱신.

`GradleModuleBoundaryTest`가 `:trading-core→:api` 역방향만 검사하던 걸 `:shared→:trading-core`, `:shared→:api` 역방향도 함께 금지하도록(3개 노드 DAG: `shared ← trading-core ← api`, `shared ← api`) 조건 추가.

- [ ] **Step 8: 전체 테스트**

```bash
bash gradlew test
```

Expected: BUILD SUCCESSFUL, 실패 0.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(build): sharedkernel+platform을 :shared 서브프로젝트로 추출

:api -> :trading-core 컴파일 의존 0 게이트(3b) 실측 확인 결과 sharedkernel
242건·platform 72건이 root에서 직접 참조돼 쓰기 API 전환만으론 게이트에
도달 불가 — 두 모듈이 outbound-zero·상호독립임을 실측 확인해 :shared로
기계적 분리. :api/:trading-core 모두 :shared에 의존하는 3-서브프로젝트
그래프로 전환.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 2: `TradingQueryPort.StrategySummary` → `AdminStrategySummary` own-type

**Files:**
- Create: `src/main/java/com/kista/admin/domain/model/AdminStrategySummary.java`
- Modify: `src/main/java/com/kista/admin/application/port/output/TradingQueryPort.java`
- Modify: `src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminQueryService.java`
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapter.java`
- Test: `src/test/java/com/kista/admin/domain/model/AdminStrategySummaryTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `AdminStrategySummary(UUID strategyId, StrategyType strategyType)` — Task 여러 곳(없음, 이 태스크 자기완결)

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.kista.admin.domain.model;

import com.kista.sharedkernel.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminStrategySummaryTest {

    @Test
    void strategyId와_strategyType을_그대로_보관한다() {
        UUID strategyId = UUID.randomUUID();
        AdminStrategySummary summary = new AdminStrategySummary(strategyId, StrategyType.INFINITE);

        assertThat(summary.strategyId()).isEqualTo(strategyId);
        assertThat(summary.strategyType()).isEqualTo(StrategyType.INFINITE);
    }
}
```

- [ ] **Step 2: 실행 확인(컴파일 실패 — 타입 없음)**

```bash
bash gradlew test --tests "com.kista.admin.domain.model.AdminStrategySummaryTest"
```

Expected: FAIL(`cannot find symbol: class AdminStrategySummary`)

- [ ] **Step 3: `AdminStrategySummary` 신설**

```java
package com.kista.admin.domain.model;

import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// trading.domain.model.StrategySummary own-type — TradingQueryPort 시그니처가 root 소유 타입만
// 쓰도록 :api -> :trading-core 컴파일 의존을 없애기 위한 복제(값 shape 동일, JSON 필드명 일치)
public record AdminStrategySummary(
        UUID strategyId,
        StrategyType strategyType
) {
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.admin.domain.model.AdminStrategySummaryTest"
```

Expected: PASS

- [ ] **Step 5: 포트·유스케이스·서비스·어댑터 시그니처 교체**

`TradingQueryPort.java`:
```java
// 변경 전: Map<UUID, StrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds);
// 변경 후:
Map<UUID, AdminStrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds);
```
import를 `com.kista.trading.domain.model.StrategySummary`에서 `com.kista.admin.domain.model.AdminStrategySummary`로 교체.

`AdminQueryUseCase.java`/`AdminQueryService.java`도 동일하게 `StrategySummary` → `AdminStrategySummary` 타입 교체(메서드명 `getStrategySummariesByCycleIds` 불변).

`TradingQueryHttpAdapter.java`: HTTP 응답 역직렬화 대상 타입을 `AdminStrategySummary`로 변경(필드명이 `strategyId`/`strategyType`로 동일해 Jackson 기본 매핑 그대로 동작).

- [ ] **Step 6: 관련 테스트 갱신 후 통과 확인**

`AdminQueryServiceTest`(존재 시) 등에서 `StrategySummary` import를 `AdminStrategySummary`로 교체.

```bash
bash gradlew test --tests "com.kista.admin.*"
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kista/admin src/test/java/com/kista/admin
git commit -m "$(cat <<'EOF'
refactor(admin): StrategySummary own-type(AdminStrategySummary) 도입

TradingQueryPort가 trading.domain.model.StrategySummary를 시그니처에
직접 노출해 :api -> :trading-core 컴파일 의존 0 게이트를 막고 있었다 —
2필드(strategyId, strategyType) 동일 shape own-type으로 교체.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 3: `TradingCommandPort` own-type 전환 + 수동 매핑 코드 제거

**Files:**
- Create: `src/main/java/com/kista/admin/domain/model/AdminReorderTimingAvailability.java`
- Modify: `src/main/java/com/kista/admin/application/port/output/TradingCommandPort.java`
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapter.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminReorderService.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminTradeCorrectionService.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/ReorderTimingAvailabilityResponse.java`
- Test: `src/test/java/com/kista/admin/domain/model/AdminReorderTimingAvailabilityTest.java`
- Test: `src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java`(기존 파일 갱신)

**Interfaces:**
- Consumes: `AdminReorderCommand`/`AdminReorderResult`/`AdminManualTradeCorrectionCommand`/`AdminTradeCorrectionResult`(기존 admin own-type, `com.kista.admin.domain.model`)
- Produces: `AdminReorderTimingAvailability(boolean atOpen, boolean atClose, boolean immediate)`, `TradingCommandPort`의 새 시그니처 — `reorder(AdminReorderCommand): AdminReorderResult`, `correctManualFills(AdminManualTradeCorrectionCommand): AdminTradeCorrectionResult`, `reorderTimingAvailability(): AdminReorderTimingAvailability`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.kista.admin.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminReorderTimingAvailabilityTest {

    @Test
    void atOpen_atClose_immediate를_그대로_보관한다() {
        AdminReorderTimingAvailability avail = new AdminReorderTimingAvailability(true, true, false);

        assertThat(avail.atOpen()).isTrue();
        assertThat(avail.atClose()).isTrue();
        assertThat(avail.immediate()).isFalse();
    }
}
```

- [ ] **Step 2: 실행 확인(실패)**

```bash
bash gradlew test --tests "com.kista.admin.domain.model.AdminReorderTimingAvailabilityTest"
```

- [ ] **Step 3: `AdminReorderTimingAvailability` 신설**

```java
package com.kista.admin.domain.model;

// trading.domain.model.DstInfo.ReorderTimingAvailability own-type — admin은 이 3개 boolean만
// 쓰므로 DstInfo 자체를 admin이 들고 있을 필요가 없다(constraints.md "own-type 정당화 게이트" (a))
public record AdminReorderTimingAvailability(
        boolean atOpen,     // AT_OPEN 접수 가능 — 개장 전에만
        boolean atClose,    // AT_CLOSE 접수 가능 — 마감 전에만
        boolean immediate   // 즉시 접수 가능 — 정규장 중에만
) {
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.admin.domain.model.AdminReorderTimingAvailabilityTest"
```

- [ ] **Step 5: `TradingCommandPort` 시그니처를 admin own-type으로 교체**

```java
package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;

public interface TradingCommandPort {
    AdminReorderResult reorder(AdminReorderCommand command);
    AdminTradeCorrectionResult correctManualFills(AdminManualTradeCorrectionCommand command);
    AdminReorderTimingAvailability reorderTimingAvailability();
}
```

- [ ] **Step 6: `TradingCommandHttpAdapter` 시그니처·직렬화 대상 교체**

`ReorderCommand`/`ReorderResult`/`ManualTradeCorrectionCommand`/`ManualTradeCorrectionResult`/`DstInfo` import 4종을 제거하고 admin own-type으로 교체. `.body(command)`/`.body(AdminReorderResult.class)` 등 타입만 바뀌고 로직은 무변경(필드명 동일이라 JSON 계약 그대로 유지).

- [ ] **Step 7: `AdminReorderService`/`AdminTradeCorrectionService`에서 수동 매핑 코드 삭제**

`AdminReorderService.reorder()`에서 `ReorderCommand tradingCommand = new ReorderCommand(...)` 변환 블록을 삭제하고 `tradingCommandPort.reorder(command)`를 직접 호출(타입이 이미 `AdminReorderCommand`이므로 매핑 불필요). 반환값도 `result`를 그대로 쓰거나 필요한 필드만 뽑아 `AdminReorderResult`를 조립하던 부분을 제거하고 `tradingCommandPort.reorder(command)`의 반환값을 그대로 리턴.

`AdminTradeCorrectionService`도 동일하게 `toTradingFills`/`toTradingFill` 변환 메서드 전체와 `ManualTradeCorrectionCommand`/`ManualTradeCorrectionResult` import 삭제.

- [ ] **Step 8: `ReorderTimingAvailabilityResponse.from()` 입력 타입 교체**

```java
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
// ...
public static ReorderTimingAvailabilityResponse from(AdminReorderTimingAvailability avail) {
    return new ReorderTimingAvailabilityResponse(avail.atOpen(), avail.atClose(), avail.immediate());
}
```

- [ ] **Step 9: 기존 테스트 갱신 후 통과 확인**

`AdminReorderServiceTest`/`AdminTradeCorrectionServiceTest`에서 mock 반환 타입을 `ReorderCommand`/`ReorderResult` 등에서 admin own-type으로 교체.

```bash
bash gradlew test --tests "com.kista.admin.*"
```

Expected: PASS, 매핑 코드 삭제로 라인 수 감소.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/kista/admin src/test/java/com/kista/admin
git commit -m "$(cat <<'EOF'
refactor(admin): TradingCommandPort own-type 전환, 수동 필드매핑 제거

reorderTimingAvailability()의 DstInfo.ReorderTimingAvailability를
AdminReorderTimingAvailability(3-boolean)로 교체. TradingCommandPort
시그니처 자체를 admin own-type(AdminReorderCommand 등)으로 바꿔
AdminReorderService/AdminTradeCorrectionService의 trading 타입<->admin
타입 수동 매핑 코드를 제거 — JSON 필드명이 이미 동일해 매핑이 애초에
불필요했다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 4: `BrokerCredentialException`/`BrokerRateLimitException` own-type

**Files:**
- Create: `src/main/java/com/kista/admin/domain/model/AdminBrokerCredentialException.java`
- Create: `src/main/java/com/kista/admin/domain/model/AdminBrokerRateLimitException.java`
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapter.java`
- Modify: `src/main/java/com/kista/web/GlobalExceptionHandler.java`
- Test: `src/test/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapterTest.java`(존재 시 갱신, 없으면 스킵)

**Interfaces:**
- Consumes: 없음
- Produces: `AdminBrokerCredentialException`(422 매핑), `AdminBrokerRateLimitException`(429 매핑)

- [ ] **Step 1: 원본 예외 클래스 구조 확인**

```bash
grep -n "class BrokerCredentialException\|class BrokerRateLimitException" -A 5 trading-core/src/main/java/com/kista/broker/domain/model/BrokerCredentialException.java trading-core/src/main/java/com/kista/broker/domain/model/BrokerRateLimitException.java
```

생성자 시그니처(무인자 또는 message 포함)를 확인하고 동일하게 복제한다.

- [ ] **Step 2: 두 예외 클래스 신설**

```java
package com.kista.admin.domain.model;

// broker.domain.model.BrokerCredentialException own-type — TradingCommandHttpAdapter가 내부
// API 422 응답을 원래 예외 타입으로 되돌리기 위해 admin 쪽에서 생성·포착하는 표지 예외
public class AdminBrokerCredentialException extends RuntimeException {
    public AdminBrokerCredentialException() {
        super("증권사 자격증명 오류");
    }
}
```

```java
package com.kista.admin.domain.model;

// broker.domain.model.BrokerRateLimitException own-type — 429 응답 표지 예외
public class AdminBrokerRateLimitException extends RuntimeException {
    public AdminBrokerRateLimitException() {
        super("증권사 API 호출 한도 초과");
    }
}
```

(원본 생성자가 메시지를 다르게 받으면 Step 1 확인 결과에 맞춰 조정)

- [ ] **Step 3: `TradingCommandHttpAdapter`에서 예외 타입 교체**

```java
.onStatus(status -> status.value() == 422, (request, response) -> {
    throw new AdminBrokerCredentialException();
})
.onStatus(status -> status.value() == 429, (request, response) -> {
    throw new AdminBrokerRateLimitException();
})
```

`import com.kista.broker.domain.model.BrokerCredentialException;`/`BrokerRateLimitException` 삭제, admin own-type import로 교체.

- [ ] **Step 4: `GlobalExceptionHandler` 핸들러 대상 교체**

기존 `@ExceptionHandler(BrokerCredentialException.class)`/`@ExceptionHandler(BrokerRateLimitException.class)`가 있으면 admin own-type 클래스로 교체(응답 상태코드 422/429 매핑 그대로 유지). import도 교체.

- [ ] **Step 5: 컴파일·테스트 확인**

```bash
bash gradlew test --tests "com.kista.admin.*" --tests "com.kista.web.*"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/admin src/main/java/com/kista/web
git commit -m "$(cat <<'EOF'
refactor(admin): Broker 자격증명·rate-limit 예외 own-type 전환

TradingCommandHttpAdapter가 내부 API 422/429 응답을 broker.domain.model
예외로 직접 재구성하고 있어 컴파일 의존 0 게이트를 막고 있었다 —
admin 소유 표지 예외(AdminBrokerCredentialException/RateLimitException)
로 교체, GlobalExceptionHandler 매핑도 함께 갱신.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 5(수정판): `GlobalExceptionHandler`를 root/trading-core 두 advice로 분리

**RULING(Task 4 구현 중 발견, 원안 폐기 사유)**: 원래 Task 5는 `ManualTradingException`/`OrderCancelException`/`PrivacyTradeConflictException`을 "죽은 매핑"으로 보고 삭제하는 것이었으나, 그 실측 근거(`grep -rln ... src/main/java/com/kista`)가 **root 디렉터리만 검색**하고 `trading-core/src/main/java/com/kista`를 누락한 결함이 있었다. 재검증 결과 다섯 예외(`ManualTradingException`/`OrderCancelException`/`PrivacyTradeConflictException`/`BrokerCredentialException`/`BrokerRateLimitException`) 전부 `RuntimeException` 상속 + trading-core 자체 서비스 계층(`ManualTradingService`/`OrderCancelService`/`PrivacyUseCase.executeFidaOrder`/`AccountController`)에서 unchecked로 던져진다 — 즉 trading-core의 **네이티브 컨트롤러**(`TradingCycleController`/`OrderCancelController`/`FidaOrderController`/`AccountController`, 전부 같은 공유 Spring 컨텍스트에서 서빙됨)가 이 예외들이 Spring MVC까지 전파될 때 root의 `GlobalExceptionHandler`(현재 앱의 유일한 `@RestControllerAdvice`) 매핑에 의존한다. Task4에서 `BrokerCredentialException`/`BrokerRateLimitException`을 "교체"가 아닌 "추가"로 처리한 구현자의 판단이 맞았다 — 삭제했다면 `AccountController`의 실제 422/429 응답이 500으로 뭉개지는 회귀였다. 같은 논리가 나머지 3종에도 적용된다.

**결론**: 삭제가 아니라 **분리**가 정답이다 — root(`com.kista.web`)의 `GlobalExceptionHandler`와 trading-core 자체 소유의 신규 advice 두 개로 나눠, 각자 자기 컨트롤러 패키지만 담당하게 한다. `@RestControllerAdvice(basePackages=...)`로 두 advice의 적용 범위를 겹치지 않게 분리해야 Spring이 `AmbiguousMappingException`(같은 예외 타입에 두 핸들러가 동시 매치)을 던지지 않는다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/web/TradingExceptionHandler.java`
- Modify: `src/main/java/com/kista/web/GlobalExceptionHandler.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/in/web/TradingExceptionHandlerTest.java`
- Test: `src/test/java/com/kista/web/GlobalExceptionHandlerTest.java`(존재 시 관련 케이스 이관)

**Interfaces:**
- Consumes: 없음
- Produces: `TradingExceptionHandler`(trading-core 소유 `@RestControllerAdvice`) — root `GlobalExceptionHandler`와 컨트롤러 패키지 기준 상호 배타적으로 스코핑됨

- [ ] **Step 1: trading-core 네이티브 컨트롤러 패키지 전수 확인**

```bash
find trading-core/src/main/java -type d -name web | grep adapter/in
```

이 결과(예: `com.kista.trading.adapter.in.web`, `com.kista.account.adapter.in.web`, `com.kista.privacy.adapter.in.web`, `com.kista.matching.adapter.in.web`(Task6에서 신설된 경우))가 신규 advice의 `basePackages` 대상이자, root `GlobalExceptionHandler`의 `basePackages`에서 **제외**해야 할 목록이다.

- [ ] **Step 2: 현재 `GlobalExceptionHandler`의 각 핸들러가 실제로 root 컨트롤러용인지 trading-core 컨트롤러용인지 분류**

`grep -n "@ExceptionHandler" -A3 src/main/java/com/kista/web/GlobalExceptionHandler.java`로 전체 핸들러 목록을 뽑고, 각 예외 타입이 소속된 모듈(root: user/finance/admin own-type 등, trading-core: broker/trading/privacy)을 확인. `KisApiException`/`TossApiException`(broker)도 trading-core 네이티브(AccountController 연결 테스트)에서 던져지는지 재확인(Step1과 동일한 grep 패턴을 이 두 예외에도 적용).

- [ ] **Step 3: `TradingExceptionHandler` 신설(trading-core)**

`ManualTradingException`/`OrderCancelException`/`PrivacyTradeConflictException`/`BrokerCredentialException`/`BrokerRateLimitException`/`KisApiException`/`TossApiException`(Step2에서 trading-core 네이티브로 확인된 것 전부) 핸들러를 이 클래스로 이관 — 기존 `GlobalExceptionHandler`의 해당 메서드 본문(상태코드·ProblemDetail 구성 로직)을 그대로 복사.

```java
package com.kista.trading.adapter.in.web;

import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.broker.domain.model.BrokerRateLimitException;
import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.OrderCancelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// trading-core 네이티브 컨트롤러(TradingCycleController/OrderCancelController/
// FidaOrderController/AccountController 등) 전용 예외 매핑 — root GlobalExceptionHandler와
// basePackages로 상호 배타 스코핑되어 있어 같은 예외 타입이라도 중복 매치되지 않는다
@RestControllerAdvice(basePackages = {
        "com.kista.trading.adapter.in.web",
        "com.kista.account.adapter.in.web",
        "com.kista.privacy.adapter.in.web",
        "com.kista.matching.adapter.in.web",
        "com.kista.broker.adapter.in.web",
        "com.kista.marketcalendar.adapter.in.web"
        // Step1 실측 결과에 맞춰 실제 존재하는 패키지만 나열(존재하지 않는 패키지는 제외)
})
public class TradingExceptionHandler {

    // Step2에서 확인한 각 예외의 기존 상태코드·ProblemDetail 구성을 그대로 이관
    // (GlobalExceptionHandler 원본 메서드 본문 복사 — 로직 변경 없음)
}
```

- [ ] **Step 4: root `GlobalExceptionHandler`에서 이관된 핸들러 삭제 + `basePackages` 스코핑 추가**

Step3으로 옮긴 메서드·import를 `GlobalExceptionHandler.java`에서 삭제. 클래스 애너테이션에 `basePackages`를 추가해 trading-core 패키지를 제외:

```java
@RestControllerAdvice(basePackages = {
        "com.kista.admin", "com.kista.web", "com.kista.user",
        "com.kista.finance", "com.kista.stats", "com.kista.market"
        // Step1에서 확인한 trading-core adapter/in/web 패키지는 전부 제외
})
```

(전역 catch-all `@ExceptionHandler(Exception.class)`이 이렇게 스코핑되면 trading-core 컨트롤러의 미분류 예외가 매핑 없이 컨테이너 기본 500으로 떨어진다 — Step3의 `TradingExceptionHandler`에도 동일한 `@ExceptionHandler(Exception.class)` catch-all을 추가해 대칭을 맞춘다.)

- [ ] **Step 5: 테스트 이관 및 신규 작성**

`GlobalExceptionHandlerTest`에서 이관된 5~7개 예외 케이스를 제거하고, `TradingExceptionHandlerTest`(신규, trading-core)에 동일 시나리오로 이식(`@WebMvcTest` 슬라이스 또는 순수 단위 테스트 — 기존 `GlobalExceptionHandlerTest`의 테스트 방식을 그대로 따른다).

- [ ] **Step 6: 두 advice 간 실제 라우팅 검증**

```bash
bash gradlew test --tests "com.kista.web.GlobalExceptionHandlerTest" --tests "com.kista.trading.adapter.in.web.TradingExceptionHandlerTest"
```

Expected: PASS. 애매하면(두 advice가 같은 컨트롤러에 동시 매치되는지 불확실하면) 로컬 `bootRun`으로 실제 `AccountController`의 자격증명 오류 엔드포인트를 호출해 422가 여전히 나오는지, `AmbiguousMappingException`으로 기동 자체가 실패하지 않는지 수동 확인.

- [ ] **Step 7: Commit**

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/in/web trading-core/src/test/java/com/kista/trading/adapter/in/web src/main/java/com/kista/web src/test/java/com/kista/web
git commit -m "$(cat <<'EOF'
refactor(web): GlobalExceptionHandler를 root/trading-core 두 advice로 분리

원래 Task5는 ManualTradingException 등 3종을 "죽은 매핑"으로 보고
삭제할 계획이었으나, 그 근거였던 grep이 trading-core 디렉터리를
누락한 결함이 있었다(재검증: 5개 예외 전부 trading-core 자체 서비스가
unchecked로 던지고 네이티브 컨트롤러가 root의 공유
GlobalExceptionHandler에 의존해 전파됨 — 삭제 시 AccountController 등의
실제 4xx 응답이 500으로 회귀).

삭제 대신 basePackages로 상호 배타 스코핑된 두 advice로 분리:
trading-core 네이티브 컨트롤러 전용 TradingExceptionHandler(신설) +
root 전용으로 축소된 기존 GlobalExceptionHandler. 이걸로 root가 이
7개 예외 타입 import를 실제로 떨쳐낸다(원래 계획의 목표는 달성 —
방법만 삭제에서 이관으로 수정).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 5b(신규, Task5 리뷰 중 발견): `KisApiException`/`TossApiException` — 내부 API로 에러로그 전송 방향 역전

**RULING(Task5 리뷰에서 발견)**: `GlobalExceptionHandler`에 남은 `KisApiException`/`TossApiException` 핸들러는 `AppErrorLogPort`(root/admin 소유, `app_error_logs` 저장)를 호출하기 위해 root에 남아있다 — 두 예외 자체는 trading-core 소유(`com.kista.broker.domain.model.kis/toss`)라 import가 남는다. `TradingUserProfilePort`(Task12)와 같은 구조적 문제(trading-core가 root 포트를 구현할 방법이 컴파일 의존 0 이후 없음)이지만 방향이 반대다 — 여긴 trading-core가 이미 소유한 예외를 root가 잡아서 root 소유 포트를 호출하는 그림이라, 포트 역전이 아니라 **trading-core → root 내부 API 신설**(이 계획의 다른 모든 내부 API는 root→trading-core 방향이었던 것과 반대)로 푼다.

**Files:**
- Create: `src/main/java/com/kista/admin/adapter/in/web/ErrorLogInternalController.java`(root, `/api/internal/errors`)
- Create: `src/main/java/com/kista/admin/adapter/in/web/dto/ErrorLogRequest.java`(root, 요청 바디 — 예외 타입명/메시지/원인 요약 등 `AppErrorLogPort.save()`가 실제로 받는 필드만)
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/in/web/TradingExceptionHandler.java`
- Modify: `src/main/java/com/kista/web/GlobalExceptionHandler.java`(KisApiException/TossApiException 핸들러 + import 삭제)

**Interfaces:**
- Consumes: 없음
- Produces: `POST /api/internal/errors`(X-Internal-Token 인증, 기존 `InternalTokenAuthFilter` 재사용) — `TradingExceptionHandler`가 KisApiException/TossApiException을 잡을 때, 또는 Task5에서 이미 다룬 ManualTradingException의 broker-cause 케이스에서 이 엔드포인트를 호출

- [ ] **Step 1: `AppErrorLogPort.save()`/`ErrorLogAspect`가 실제로 받는 필드 확인**

```bash
grep -n "AppErrorLogPort\|interface AppErrorLogPort" -A 10 src/main/java/com/kista/admin/application/port/output/AppErrorLogPort.java
```

`GlobalExceptionHandler.saveErrorLog(ex)`가 이 포트에 실제로 넘기는 인자 확인.

- [ ] **Step 2: 신규 내부 API 컨트롤러(root)**

```java
package com.kista.admin.adapter.in.web;

import com.kista.admin.adapter.in.web.dto.ErrorLogRequest;
import com.kista.admin.application.port.output.AppErrorLogPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TradingExceptionHandler(trading-core)가 KisApiException/TossApiException 발생 시 호출하는
// 역방향 내부 API — 이 계획의 다른 내부 API는 전부 root->trading-core였으나, app_error_logs가
// root 소유 테이블이라 방향이 반대다
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/errors")
@RequiredArgsConstructor
public class ErrorLogInternalController {

    private final AppErrorLogPort appErrorLogPort;

    @PostMapping
    public void save(@RequestBody ErrorLogRequest request) {
        appErrorLogPort.save(request.toDomainArgs()); // Step1 확인 결과에 맞춰 실제 시그니처로 조정
    }
}
```

- [ ] **Step 3: `TradingExceptionHandler`에 KisApiException/TossApiException 핸들러 추가 + 내부 API 호출**

기존 root `handleAll`의 broker-cause 체크 로직(`ex.getCause() instanceof KisApiException || ... TossApiException`)을 `TradingExceptionHandler`로 이관하고, `RestClient internalApiRestClient`(기존 어댑터들과 동일한 빈 재사용)로 `POST /api/internal/errors` 호출.

- [ ] **Step 4: root `GlobalExceptionHandler`에서 KisApiException/TossApiException 핸들러·import 삭제**

`@ExceptionHandler(KisApiException.class)`/`@ExceptionHandler(TossApiException.class)` 메서드, `handleAll`의 broker-cause 체크, 관련 import 삭제.

- [ ] **Step 5: 테스트 갱신 후 통과 확인**

```bash
bash gradlew test --tests "com.kista.web.GlobalExceptionHandlerTest" --tests "com.kista.trading.adapter.in.web.TradingExceptionHandlerTest" --tests "com.kista.admin.adapter.in.web.ErrorLogInternalControllerTest"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(admin): KisApiException/TossApiException 처리를 내부 API로 역방향 이관

app_error_logs가 root 소유 테이블이라 KisApiException/TossApiException
(trading-core 소유)이 GlobalExceptionHandler에 남아있었다 — Task5 리뷰에서
발견한 Task13 게이트 잔여 leak. TradingUserProfilePort(Task12)와 같은
구조적 문제이나 방향이 반대(trading-core->root)라 포트 역전이 아니라
신규 내부 API(POST /api/internal/errors)로 해소.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 6: `MetaController`/`StrategyTypeMeta` — matching 직접 참조 제거

**Files:**
- Create: `trading-core/src/main/java/com/kista/matching/adapter/in/web/StrategyCapabilityInternalController.java`(신규 내부 API — matching 모듈에 `adapter/in/web`이 없으면 신설)
- Modify: `src/main/java/com/kista/web/dto/StrategyTypeMeta.java`
- Modify: `src/main/java/com/kista/web/MetaController.java`
- Modify: `src/main/java/com/kista/web/dto/TickerMeta.java` (죽은 `Strategy` import 제거)
- Test: `src/test/java/com/kista/web/dto/StrategyTypeMetaTest.java`(기존 파일 갱신)
- Test: `src/test/java/com/kista/web/MetaControllerTest.java`(기존 파일 갱신)

**Interfaces:**
- Consumes: 없음
- Produces: `GET /api/internal/matching/strategy-capabilities` → `List<StrategyCapabilityResponse>`(각 항목: `code`, `requiresPrivacyBase`, `supportsReverseMode`, `divisionCounts`)

- [ ] **Step 1: `CycleOrderStrategy`/`CycleOrderStrategies`가 이미 인바운드 web 어댑터를 갖는지 확인**

```bash
find trading-core/src/main/java/com/kista/matching -type d -name web
```

없으면 `trading-core/src/main/java/com/kista/matching/adapter/in/web/` 신설.

- [ ] **Step 2: 신규 내부 API 응답 DTO + 컨트롤러 작성(trading-core)**

```java
package com.kista.matching.adapter.in.web;

import com.kista.matching.domain.strategy.CycleOrderStrategy;

import java.util.List;

// MetaController(root)가 소비하는 내부 전용 응답 — matching 타입을 root에 노출하지 않기 위한 투영
public record StrategyCapabilityResponse(
        boolean requiresPrivacyBase,
        boolean supportsReverseMode,
        List<Integer> divisionCounts
) {
    public static StrategyCapabilityResponse from(CycleOrderStrategy strategy) {
        return new StrategyCapabilityResponse(
                strategy.requiresPrivacyBase(), strategy.supportsReverseMode(), strategy.availableDivisionCounts());
    }
}
```

```java
package com.kista.matching.adapter.in.web;

import com.kista.matching.domain.strategy.CycleOrderStrategies;
import com.kista.sharedkernel.StrategyType;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// MetaController(root)의 matching.CycleOrderStrategy 직접 참조를 없애기 위한 내부 전용 엔드포인트
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/matching")
@RequiredArgsConstructor
public class StrategyCapabilityInternalController {

    private final CycleOrderStrategies cycleStrategies;

    @GetMapping("/strategy-capabilities/{type}")
    public StrategyCapabilityResponse get(@PathVariable StrategyType type) {
        return StrategyCapabilityResponse.from(cycleStrategies.of(type));
    }
}
```

- [ ] **Step 3: root에 내부 API 클라이언트 + own-type 응답 신설**

```java
package com.kista.web.dto;

// StrategyCapabilityResponse(trading-core) own-type — matching 타입을 root로 노출하지 않기 위함
public record StrategyCapability(
        boolean requiresPrivacyBase,
        boolean supportsReverseMode,
        java.util.List<Integer> divisionCounts
) {
}
```

`MetaController`에 `RestClient internalApiRestClient` 필드를 추가하고(기존 admin/stats 어댑터와 동일한 빈 재사용), `getStrategyTypeList()`에서 `cycleStrategies.of(t)` 호출을 내부 API 호출로 교체:

```java
private StrategyCapability fetchCapability(StrategyType type) {
    return internalApiRestClient.get()
            .uri("/api/internal/matching/strategy-capabilities/{type}", type)
            .retrieve()
            .body(StrategyCapability.class);
}
```

- [ ] **Step 4: `StrategyTypeMeta.from()` 시그니처를 `StrategyCapability` 입력으로 교체**

```java
import com.kista.web.dto.StrategyCapability; // 같은 패키지면 import 불필요

public static StrategyTypeMeta from(StrategyType t, StrategyCapability capability) {
    List<String> tickers = t.availableTickers().stream().map(Enum::name).toList();
    return new StrategyTypeMeta(
            t.name(), t.getDescription(), tickers,
            capability.requiresPrivacyBase(),
            tickers.size() == 1,
            capability.supportsReverseMode(),
            capability.divisionCounts()
    );
}
```

`import com.kista.matching.domain.strategy.CycleOrderStrategy;` 삭제.

- [ ] **Step 5: `MetaController.getStrategyTypeList()` 갱신, `CycleOrderStrategies` 필드 삭제**

```java
private List<StrategyTypeMeta> getStrategyTypeList() {
    return Arrays.stream(StrategyType.values())
            .map(t -> StrategyTypeMeta.from(t, fetchCapability(t)))
            .toList();
}
```

생성자 필드에서 `CycleOrderStrategies cycleStrategies` 제거, `RestClient internalApiRestClient` 추가. `import com.kista.matching.domain.strategy.CycleOrderStrategies;` 삭제.

- [ ] **Step 6: `TickerMeta`의 죽은 `Strategy` import 제거**

`import com.kista.trading.domain.model.Strategy;` 삭제(파일 내 미사용 확인됨).

- [ ] **Step 7: 테스트 갱신**

`StrategyTypeMetaTest`: `CycleOrderStrategy` mock 대신 `StrategyCapability` 값 객체로 직접 생성.
`MetaControllerTest`: `CycleOrderStrategies` mock 대신 `RestClient`(또는 그 상위 추상화) mock으로 `fetchCapability` 응답 stub.

```bash
bash gradlew test --tests "com.kista.web.*" --tests "com.kista.matching.*"
```

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add trading-core/src/main/java/com/kista/matching/adapter/in/web src/main/java/com/kista/web src/test/java/com/kista/web
git commit -m "$(cat <<'EOF'
refactor(web): MetaController의 matching.CycleOrderStrategy 직접 참조 제거

StrategyTypeMeta.from()이 CycleOrderStrategy를 인자로 받아 matching
모듈을 root에 직접 노출하고 있었다 — 신규 내부 API
(GET /api/internal/matching/strategy-capabilities/{type})와 own-type
응답(StrategyCapability)으로 대체.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 7: `ActiveStrategyCountAdapter` — 신규 내부 API + HTTP 어댑터 전환

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/web/ActiveStrategyCountInternalController.java`
- Modify: `src/main/java/com/kista/web/trading/ActiveStrategyCountAdapter.java`
- Test: `src/test/java/com/kista/web/trading/ActiveStrategyCountAdapterTest.java`(기존 파일 갱신)

**Interfaces:**
- Consumes: 없음
- Produces: `GET /api/internal/trading/active-strategy-count?userId={uuid}` → `long`(응답 바디 순수 숫자)

- [ ] **Step 1: 신규 내부 API 컨트롤러 작성(trading-core)**

```java
package com.kista.trading.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// user.ActiveStrategyCountPort 구현체(web.trading.ActiveStrategyCountAdapter)가 소비하는
// 내부 전용 엔드포인트 — account/trading 타입을 root에 노출하지 않기 위함
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading")
@RequiredArgsConstructor
public class ActiveStrategyCountInternalController {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @GetMapping("/active-strategy-count")
    public long activeStrategyCount(@RequestParam UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .map(Account::id)
                .flatMap(accountId -> strategyPort.findByAccountId(accountId).stream())
                .filter(strategy -> strategy.isActive())
                .count();
    }
}
```

(로직은 기존 `ActiveStrategyCountAdapter.countActiveByUserId` 그대로 이관 — 계산 위치만 옮김)

- [ ] **Step 2: `ActiveStrategyCountAdapter`를 HTTP 어댑터로 교체**

```java
package com.kista.web.trading;

import com.kista.user.application.port.output.ActiveStrategyCountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@RequiredArgsConstructor
class ActiveStrategyCountAdapter implements ActiveStrategyCountPort {

    private final RestClient internalApiRestClient;

    @Override
    public long countActiveByUserId(UUID userId) {
        Long count = internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/trading/active-strategy-count")
                        .queryParam("userId", userId)
                        .build())
                .retrieve()
                .body(Long.class);
        return count != null ? count : 0L;
    }
}
```

`AccountPort`/`StrategyPort`/`Account` import 전부 삭제.

- [ ] **Step 3: 테스트 갱신**

기존 Mockito 기반 테스트(`AccountPort`/`StrategyPort` mock)를 `RestClient` 체인 mock으로 교체(기존 `AdminReorderServiceTest` 등에서 쓰는 `RestClient.RequestHeadersUriSpec` mock 패턴 참고) — 또는 `MockRestServiceServer`/`@RestClientTest` 슬라이스로 전환.

```bash
bash gradlew test --tests "com.kista.web.trading.ActiveStrategyCountAdapterTest" --tests "com.kista.trading.adapter.in.web.ActiveStrategyCountInternalControllerTest"
```

Expected: PASS(신규 컨트롤러 테스트는 이 태스크에서 함께 작성 — 기존 `ActiveStrategyCountAdapterTest`의 시나리오를 컨트롤러 테스트로 그대로 이관)

- [ ] **Step 4: Commit**

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/in/web src/main/java/com/kista/web/trading src/test/java/com/kista/web/trading
git commit -m "$(cat <<'EOF'
refactor(web): ActiveStrategyCountAdapter를 내부 API HTTP 어댑터로 전환

user 소유 ActiveStrategyCountPort 구현체가 account.AccountPort와
trading.StrategyPort를 직접 주입해 컴파일 의존 0 게이트를 막고
있었다 — 계산 로직을 trading-core 신규 내부 API
(GET /api/internal/trading/active-strategy-count)로 이관하고
어댑터는 HTTP 호출만 담당하도록 축소.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 8: `AdminAccountController`/`AdminQueryUseCase` — Account read leak own-type

**Files:**
- Create: `src/main/java/com/kista/admin/domain/model/AdminAccountView.java`
- Create: `src/main/java/com/kista/admin/application/port/output/AccountQueryPort.java`
- Create: `src/main/java/com/kista/admin/adapter/out/internal/AccountQueryHttpAdapter.java`
- Create: `trading-core/src/main/java/com/kista/account/adapter/in/web/AccountInternalController.java`
- Modify: `src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminQueryService.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminAccountController.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountItem.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountResponse.java`
- Modify: `src/main/java/com/kista/admin/domain/model/AdminAnomalies.java`
- Test: `src/test/java/com/kista/admin/application/service/AdminQueryServiceTest.java`(기존 파일 갱신)

**Interfaces:**
- Consumes: 없음
- Produces: `AdminAccountView`(필드: `Account`의 admin 노출 필드 전체 — id/userId/nickname/accountNo(마스킹 전 원본)/broker/createdAt 등, 정확한 필드는 Step 1에서 `Account` record 확인 후 1:1 복제), `AccountQueryPort.findByUserId/findAll/findById` 등(기존 `AdminQueryUseCase`가 실제 호출하는 메서드 목록 기준)

- [ ] **Step 1: `Account` record 전체 필드 확인**

```bash
grep -n "public record Account" -A 15 trading-core/src/main/java/com/kista/account/domain/model/Account.java
```

이후 Step에서 이 필드 목록을 `AdminAccountView`에 그대로 복제.

- [ ] **Step 2: `AdminQueryUseCase`/`AdminQueryService`에서 `Account` 실제 사용 메서드 전수 확인**

```bash
grep -n "Account\b" src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java src/main/java/com/kista/admin/application/service/AdminQueryService.java src/main/java/com/kista/admin/adapter/in/web/AdminAccountController.java src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountItem.java src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountResponse.java src/main/java/com/kista/admin/domain/model/AdminAnomalies.java
```

`listAccounts(from, to)`, `findAccount(accountId)`가 실제 반환·소비되는 필드(마스킹 로직에 쓰이는 `accountNo` 포함)를 전부 확인.

- [ ] **Step 3: `AdminAccountView` own-type 신설**

Step 1에서 확인한 `Account` 필드를 그대로 복제한 record 작성(정확한 필드명·타입은 Step 1 결과에 맞춰 채운다 — placeholder 없이 실제 필드로).

- [ ] **Step 4: `AccountQueryPort` 신설**

```java
package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminAccountView;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// admin이 정의하는 account 조회 포트 — AccountQueryHttpAdapter가 내부 API로 구현
public interface AccountQueryPort {
    List<AdminAccountView> findAll(LocalDate from, LocalDate to); // null = 전체
    Optional<AdminAccountView> findById(UUID accountId);
}
```

(Step 2에서 확인한 실제 필요 메서드에 맞춰 시그니처 조정 — `AdminQueryUseCase.listAccounts`/`findAccount`가 요구하는 것과 1:1 대응)

- [ ] **Step 5: trading-core에 신규 내부 API 컨트롤러**

`AccountInternalController`(`/api/internal/accounts`)에 `GET`(목록, from/to 쿼리파라미터) + `GET /{id}` 두 엔드포인트 작성, `AccountPort`(trading-core 기존 포트)를 그대로 호출해 `Account` → 응답 DTO(필드 동일하므로 `Account` 자체를 반환해도 Jackson이 admin 쪽 `AdminAccountView`로 역직렬화 가능 — 별도 매핑 불필요).

- [ ] **Step 6: `AccountQueryHttpAdapter` 신설**

`TradingQueryHttpAdapter`와 동일한 패턴(RestClient + onStatus 404 매핑)으로 `AccountQueryPort` 구현.

- [ ] **Step 7: `AdminQueryUseCase`/`AdminQueryService`/`AdminAccountController`/DTO들에서 `Account` → `AdminAccountView` 교체**

`listAccounts`/`findAccount` 반환 타입 교체, `AdminQueryService`에 `AccountQueryPort` 주입해 위임, `AdminAccountItem.from()`/`AdminAccountResponse.from()`의 파라미터 타입 교체, `AdminAnomalies`의 `Account` 필드도 교체.

- [ ] **Step 8: 테스트 갱신 후 통과 확인**

```bash
bash gradlew test --tests "com.kista.admin.*"
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(admin): Account 조회 own-type(AdminAccountView) + AccountQueryPort 신설

AdminQueryUseCase.listAccounts/findAccount가 account.domain.model.Account
를 포트 추상화 없이 직접 반환하고 있었다(read leak, 실측 확인) —
TradingQueryPort 선례와 동일한 패턴으로 AdminAccountView own-type +
AccountQueryPort/AccountQueryHttpAdapter 신설.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 9: `AdminPrivacyTradeService` — 직접 포트 주입 제거

**Files:**
- Create: `src/main/java/com/kista/admin/domain/model/AdminPrivacyTradeBaseView.java`
- Modify: `src/main/java/com/kista/admin/application/port/output/PrivacyQueryPort.java`
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/PrivacyQueryHttpAdapter.java`
- Modify: `src/main/java/com/kista/admin/application/usecase/AdminPrivacyTradeUseCase.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminPrivacyTradeService.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminPrivacyTradeController.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminPrivacyBaseResponse.java`
- Create: `trading-core/src/main/java/com/kista/privacy/adapter/in/web/PrivacyBaseInternalController.java`(`updateBase`/`updateOrder` 신규 엔드포인트 — `FidaOrderController`가 이미 서빙하는 `POST /api/internal/fida-orders`는 재사용, PATCH 2종만 신설)

**Interfaces:**
- Consumes: 기존 `FidaOrderController`의 `POST /api/internal/fida-orders`(createBase가 재사용)
- Produces: `PATCH /api/internal/privacy/trade-bases/{baseId}`, `PATCH /api/internal/privacy/trade-bases/{baseId}/orders/{orderId}` — `AdminPrivacyTradeBaseView` own-type 응답

- [ ] **Step 1: `PrivacyTradeBaseView`/`PrivacyBaseUpdateCommand`/`PrivacyOrderUpdateCommand`/`FidaOrderCommand` 필드 확인**

```bash
grep -n "public record" -A 15 trading-core/src/main/java/com/kista/privacy/domain/model/PrivacyTradeBaseView.java trading-core/src/main/java/com/kista/privacy/domain/model/PrivacyBaseUpdateCommand.java trading-core/src/main/java/com/kista/privacy/domain/model/PrivacyOrderUpdateCommand.java trading-core/src/main/java/com/kista/privacy/domain/model/FidaOrderCommand.java
```

- [ ] **Step 2: admin own-type 4종 신설**

`AdminPrivacyTradeBaseView`(응답), `AdminFidaOrderCommand`(createBase 요청 — 이미 있는지 확인, 없으면 신설), `AdminPrivacyBaseUpdateCommand`, `AdminPrivacyOrderUpdateCommand` — Step 1에서 확인한 필드 그대로 1:1 복제.

- [ ] **Step 3: `PrivacyQueryPort`를 쓰기 메서드 포함하도록 확장(또는 신규 `PrivacyCommandPort` 분리)**

```java
package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminFidaOrderCommand;
import com.kista.admin.domain.model.AdminPrivacyBaseUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyOrderUpdateCommand;
import com.kista.admin.domain.model.AdminPrivacyTradeBaseView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PrivacyQueryPort {
    List<AdminPrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate);
    AdminPrivacyTradeBaseView createBase(AdminFidaOrderCommand command); // 멱등 — 기존 fida-orders 엔드포인트 재사용
    AdminPrivacyTradeBaseView updateBase(UUID baseId, AdminPrivacyBaseUpdateCommand command);
    AdminPrivacyTradeBaseView updateOrder(UUID baseId, UUID orderId, AdminPrivacyOrderUpdateCommand command);
}
```

- [ ] **Step 4: `PrivacyQueryHttpAdapter`에 쓰기 메서드 3개 구현**

`createBase`는 기존 `POST /api/internal/fida-orders` 호출(응답 shape이 `created` 플래그를 포함하는지 Step 1에서 확인 — 포함 안 하면 `AdminPrivacyTradeUseCase.CreateResult`의 `created` 계산 방식을 조정). `updateBase`/`updateOrder`는 신규 엔드포인트(Step 6) 호출.

- [ ] **Step 5: `AdminPrivacyTradeService`를 `PrivacyQueryPort` 단일 의존으로 축소**

`PrivacyUseCase`/`PrivacyTradePort` 직접 주입 삭제, `privacyQueryPort` 하나만 주입해 위임.

- [ ] **Step 6: trading-core에 `PrivacyBaseInternalController` 신설**

`PATCH /api/internal/privacy/trade-bases/{baseId}`, `PATCH /api/internal/privacy/trade-bases/{baseId}/orders/{orderId}` — 기존 `PrivacyTradePort.updateBase`/`updateOrder`를 그대로 호출.

- [ ] **Step 7: DTO·컨트롤러 own-type 교체**

`AdminPrivacyTradeController`/`AdminPrivacyBaseResponse`/`AdminPrivacyTradeUseCase`의 `privacy.domain.model.*` import를 전부 admin own-type으로 교체.

- [ ] **Step 8: 테스트 갱신 후 통과 확인**

```bash
bash gradlew test --tests "com.kista.admin.*" --tests "com.kista.privacy.*"
```

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(admin): AdminPrivacyTradeService 직접 포트 주입 제거, own-type 전환

privacy.application.usecase.PrivacyUseCase/PrivacyTradePort를 admin이
직접 주입해 컴파일 의존 0 게이트를 막고 있었다 — PrivacyQueryPort를
쓰기 메서드까지 확장하고 admin own-type 4종으로 전환. updateBase/
updateOrder는 trading-core 신규 내부 API로 이관, createBase는 기존
POST /api/internal/fida-orders 재사용.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 10: `AdminSchedulerController` 분리

**Files:**
- Modify: `src/main/java/com/kista/web/AdminSchedulerController.java` → KbLand 트리거 2+1개만 남기고 클래스명 유지 여부 결정(아래 참고)
- Create: `src/main/java/com/kista/admin/adapter/in/web/AdminTradingSchedulerController.java`(신규 — trading 트리거 2개)
- Create: `src/main/java/com/kista/admin/application/port/output/TradingSchedulerCommandPort.java`
- Create: `src/main/java/com/kista/admin/adapter/out/internal/TradingSchedulerCommandHttpAdapter.java`
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/web/TradingSchedulerInternalController.java`

**Interfaces:**
- Consumes: 없음
- Produces: `POST /api/internal/trading/scheduler/open`, `POST /api/internal/trading/scheduler/close`(둘 다 202 ACCEPTED, 바디 없음)

- [ ] **Step 1: trading-core에 내부 트리거 엔드포인트 신설**

```java
package com.kista.trading.adapter.in.web;

import com.kista.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.trading.adapter.in.schedule.TradingOpenScheduler;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/scheduler")
@RequiredArgsConstructor
public class TradingSchedulerInternalController {

    private final TradingOpenScheduler openScheduler;
    private final TradingCloseScheduler closeScheduler;

    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }

    private void triggerAsync(String label, InterruptibleAction action) {
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} 수동 트리거 인터럽트", label);
            } catch (Exception e) {
                log.error("{} 수동 트리거 오류: {}", label, e.getMessage(), e);
            }
        });
    }

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        triggerAsync("개장 스케쥴러", openScheduler::runNow);
    }

    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        triggerAsync("마감 스케쥴러", closeScheduler::runNow);
    }
}
```

- [ ] **Step 2: admin에 `TradingSchedulerCommandPort` + HTTP 어댑터 신설**

```java
package com.kista.admin.application.port.output;

public interface TradingSchedulerCommandPort {
    void triggerOpen();
    void triggerClose();
}
```

`TradingSchedulerCommandHttpAdapter`는 `internalApiRestClient.post().uri("/api/internal/trading/scheduler/open").retrieve().toBodilessEntity()` 패턴으로 구현(2개 메서드).

- [ ] **Step 3: `AdminTradingSchedulerController` 신설(admin 소유, `scheduler.enabled` 게이팅 없음)**

```java
package com.kista.admin.adapter.in.web;

import com.kista.admin.application.port.output.TradingSchedulerCommandPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// 내부 API 호출이라 kista-api role에서도 항상 노출 가능 — 원격 trading-core 스케쥴러 존재 여부와
// 로컬 빈 게이팅이 무관해짐(AdminSchedulerController의 기존 @ConditionalOnProperty와 대비)
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
public class AdminTradingSchedulerController {

    private final TradingSchedulerCommandPort schedulerCommandPort;

    @Operation(summary = "개장 스케쥴러 수동 트리거")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        schedulerCommandPort.triggerOpen();
    }

    @Operation(summary = "마감 스케쥴러 수동 트리거")
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        schedulerCommandPort.triggerClose();
    }
}
```

- [ ] **Step 4: `AdminSchedulerController`(web)에서 trading 트리거 2개 제거**

`TradingOpenScheduler`/`TradingCloseScheduler` 필드·`triggerOpen()`/`triggerClose()` 메서드·관련 import 삭제. `@RequestMapping("/api/admin/scheduler")`가 Step 3의 신규 컨트롤러와 경로가 겹치므로, KbLand 전용 경로로 명확히 분리하거나(예: 그대로 `/api/admin/scheduler` 유지 — Spring은 서로 다른 컨트롤러의 겹치지 않는 하위 경로를 허용하므로 `/kbland-*` 서브경로만 남은 이 컨트롤러와 `/open`,`/close`만 있는 신규 컨트롤러는 공존 가능, 라우팅 충돌 없음 확인 후 진행) 클래스명은 `AdminSchedulerController` 그대로 유지.

- [ ] **Step 5: 테스트 갱신**

`AdminSchedulerControllerTest`(존재 시)에서 open/close 관련 테스트를 신규 `AdminTradingSchedulerControllerTest`로 이관.

```bash
bash gradlew test --tests "com.kista.web.AdminSchedulerControllerTest" --tests "com.kista.admin.adapter.in.web.AdminTradingSchedulerControllerTest" --tests "com.kista.trading.adapter.in.web.TradingSchedulerInternalControllerTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(admin): AdminSchedulerController를 trading/KbLand 트리거로 분리

trading 스케쥴러 트리거(개장/마감)를 admin 소유 신규 컨트롤러 +
내부 API 호출로 이관 — 원격 호출이라 kista-api role의
scheduler.enabled 게이팅과 무관해진다. KbLand 트리거는 기존
web.AdminSchedulerController에 게이팅 그대로 유지.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 11: `stats` 모듈 — `InvestmentPoint`/`BenchmarkGranularity` own-type

**Files:**
- Create: `src/main/java/com/kista/stats/domain/model/InvestmentPoint.java`
- Create: `src/main/java/com/kista/stats/domain/model/BenchmarkGranularity.java`
- Modify: `src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java`
- Modify: `src/main/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapter.java`
- Modify: `src/main/java/com/kista/stats/application/service/StatsService.java`
- Modify: `src/main/java/com/kista/stats/application/service/HousingBenchmarkComparisonBuilder.java`
- Test: 기존 `StatsServiceTest`/`HousingBenchmarkComparisonBuilderTest`/`InvestmentPointsHttpAdapterTest` 갱신

**Interfaces:**
- Consumes: 없음
- Produces: `com.kista.stats.domain.model.InvestmentPoint(LocalDate baseDate, BigDecimal investmentIndexUsd, BigDecimal periodReturn)`, `com.kista.stats.domain.model.BenchmarkGranularity{MONTHLY,DAILY,WEEKLY}`

- [ ] **Step 1: own-type 2종 신설**

```java
package com.kista.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// trading.stats.domain.model.InvestmentPoint own-type — InvestmentPointsPort가 root 소유
// 타입만 쓰도록 컴파일 의존을 없애기 위한 복제(값 shape 동일)
public record InvestmentPoint(
        LocalDate baseDate,
        BigDecimal investmentIndexUsd,
        BigDecimal periodReturn
) {
}
```

```java
package com.kista.stats.domain.model;

// trading.stats.domain.model.BenchmarkGranularity own-type
public enum BenchmarkGranularity { MONTHLY, DAILY, WEEKLY }
```

- [ ] **Step 2: `InvestmentPointsPort`의 import 교체**

```java
import com.kista.stats.domain.model.InvestmentPoint;
import com.kista.stats.domain.model.BenchmarkGranularity;
```

(패키지만 `com.kista.trading.stats.domain.model` → `com.kista.stats.domain.model`로 변경, 타입 사용 코드는 무변경)

- [ ] **Step 3: `InvestmentPointsHttpAdapter`의 import 교체**

동일하게 `BenchmarkGranularity` import만 교체(HTTP 호출 로직 자체는 이미 stage2에서 완성돼 있어 무변경).

- [ ] **Step 4: `StatsService`/`HousingBenchmarkComparisonBuilder`의 import 교체**

두 파일 상단의 `import com.kista.trading.stats.domain.model.{BenchmarkGranularity,InvestmentPoint};`를 `com.kista.stats.domain.model.*`로 교체. 본문 코드는 필드 접근자명이 동일해 무변경.

- [ ] **Step 5: 컴파일·테스트 확인**

```bash
bash gradlew test --tests "com.kista.stats.*"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/stats
git commit -m "$(cat <<'EOF'
refactor(stats): InvestmentPoint/BenchmarkGranularity own-type 전환

InvestmentPointsPort가 trading.stats.domain.model 타입을 시그니처에
직접 노출해 컴파일 의존 0 게이트를 막고 있었다(architecture.md가
"영구적으로 남는 설계"로 서술했던 부분 — 3단계 게이트와 충돌해 own-type
전환 대상으로 확정, 2026-09-13 스펙 정정 c79550cc/132dffd0). HTTP 호출
경로는 stage2에서 이미 완성돼 있어 타입 import만 교체.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 12: `user_notify_profile` 신설 + `TradingUserProfilePort` 구조적 교체 + `UserCascadeDeleter` 정리

**Files:**
- Create: `src/main/resources/db/migration/V10__create_user_notify_profile.sql`
- Create: `src/main/java/com/kista/user/domain/model/UserNotifyProfileChangedEvent.java`
- Modify: `src/main/java/com/kista/user/application/service/UserSettingsService.java`(notificationPrefs/balanceCheckEnabled 변경 시 이벤트 발행)
- Modify: `src/main/java/com/kista/user/application/service/UserService.java`(해당 필드 변경 지점이 있으면 함께)
- Modify: `src/main/java/com/kista/user/application/service/UserCascadeDeleter.java`(`AccountPort` 직접 호출 제거)
- Create: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/UserNotifyProfileEntity.java`
- Create: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/UserNotifyProfileJpaRepository.java`
- Create: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/UserNotifyProfilePersistenceAdapter.java`(`TradingUserProfilePort` 구현 — `TradingUserProfileAdapter` 대체)
- Create: `trading-core/src/main/java/com/kista/trading/application/service/UserNotifyProfileEventListener.java`(`UserNotifyProfileChangedEvent`/`UserDeletedEvent` 구독, upsert/delete)
- Create: `trading-core/src/main/java/com/kista/account/application/service/AccountUserCascadeListener.java`(`UserDeletedEvent` 구독, `AccountPort.deleteByUserId` 호출 — 기존 `AccountCascadeListener`와 동일 패턴)
- Delete: `src/main/java/com/kista/web/trading/TradingUserProfileAdapter.java`
- Delete: `src/test/java/com/kista/web/trading/TradingUserProfileAdapterTest.java`(존재 시)
- Test: `src/test/java/com/kista/user/application/service/UserCascadeDeleterTest.java`(기존 파일 갱신)
- Test: `trading-core/src/test/java/com/kista/trading/application/service/UserNotifyProfileEventListenerTest.java`
- Test: `trading-core/src/test/java/com/kista/account/application/service/AccountUserCascadeListenerTest.java`

**Interfaces:**
- Consumes: `UserDeletedEvent(UUID userId)`(sharedkernel, 기존), `AccountPort.deleteByUserId(UUID)`(trading-core 기존)
- Produces: `UserNotifyProfileChangedEvent(UUID userId, Map<NotificationType,Boolean> notificationPrefs, boolean balanceCheckEnabled)`, `user_notify_profile` 테이블

- [ ] **Step 1: `UserSettingsService`의 실제 변경 지점 확인**

```bash
grep -n "notificationPrefs\|balanceCheckEnabled" src/main/java/com/kista/user/application/service/UserSettingsService.java
```

`updateNotificationPref`/`updateBalanceCheckEnabled` 등 정확한 메서드명을 확인하고 이 메서드들 끝에 이벤트 발행을 추가.

- [ ] **Step 2: `UserNotifyProfileChangedEvent` 신설**

```java
package com.kista.user.domain.model;

import com.kista.sharedkernel.NotificationType;

import java.util.Map;
import java.util.UUID;

// trading-core의 user_notify_profile 읽기 전용 캐시 동기화용 — DB 분리(4단계) 전까지는
// 같은 DB 위 Modulith EPR(@TransactionalEventListener)로 전달된다
public record UserNotifyProfileChangedEvent(
        UUID userId,
        Map<NotificationType, Boolean> notificationPrefs,
        boolean balanceCheckEnabled
) {
}
```

- [ ] **Step 3: `UserSettingsService`의 변경 메서드에 이벤트 발행 추가**

각 변경 메서드(Step 1에서 확인한 메서드) 끝에 `eventPublisher.publishEvent(new UserNotifyProfileChangedEvent(userId, updated.notificationPrefs(), updated.balanceCheckEnabled()))` 추가(`ApplicationEventPublisher` 필드가 없으면 생성자에 주입 추가).

- [ ] **Step 4: 실패하는 테스트 작성(user 쪽 이벤트 발행 검증)**

```java
@Test
void updateNotificationPref_변경_시_UserNotifyProfileChangedEvent를_발행한다() {
    // given: UserSettingsPort stub, ApplicationEventPublisher mock
    // when: service.updateNotificationPref(userId, type, enabled) 호출
    // then: verify(eventPublisher).publishEvent(argThat(e ->
    //     e instanceof UserNotifyProfileChangedEvent evt && evt.userId().equals(userId)));
}
```

(정확한 메서드 시그니처는 Step 1 확인 결과에 맞춰 채운다)

- [ ] **Step 5: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.user.application.service.UserSettingsServiceTest"
```

- [ ] **Step 6: Flyway 마이그레이션 작성**

```sql
-- V10__create_user_notify_profile.sql
-- trading-core가 소유하는 사용자 알림·잔고검증 설정 읽기 전용 캐시.
-- 현재는 root와 같은 DB지만 4단계(DB 분리) 이후에도 그대로 남는 trading-core 전용 테이블이다.
-- notification_prefs는 UserSettings.notificationPrefs(Map<NotificationType,Boolean>)를 JSON
-- 텍스트로 저장 — 이 테이블은 캐시일 뿐 정규화된 소스오브트루스가 아니므로 별도 자식 테이블을
-- 두지 않는다(YAGNI).
CREATE TABLE user_notify_profile (
    user_id UUID PRIMARY KEY,
    notification_prefs TEXT NOT NULL DEFAULT '{}',
    balance_check_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

(flyway-migration 스킬의 named-constraint·컬럼순서 규칙 재확인 후 적용 — 이 테이블은 FK가 없어 named constraint 이슈 없음)

- [ ] **Step 7: `UserNotifyProfileEntity`/`JpaRepository`/`PersistenceAdapter` 작성(trading-core)**

```java
package com.kista.trading.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_notify_profile", schema = "public")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserNotifyProfileEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId; // 사용자 식별자

    @Column(name = "notification_prefs", nullable = false)
    private String notificationPrefsJson; // NotificationType->Boolean 직렬화 JSON

    @Column(name = "balance_check_enabled", nullable = false)
    private boolean balanceCheckEnabled; // 잔고검증 활성 여부

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt; // 마지막 동기화 시각
}
```

`UserNotifyProfileJpaRepository extends JpaRepository<UserNotifyProfileEntity, UUID>`.

`UserNotifyProfilePersistenceAdapter`는 `TradingUserProfilePort`를 구현 — `notificationPrefsJson`을 `ObjectMapper`(생성자 주입, Jackson 기본 빈 재사용)로 `Map<NotificationType,Boolean>`으로 역직렬화해 `TradingUserProfile` 조립. `findAllActive()`는 이 캐시 테이블에 status 컬럼이 없으므로 — **주의**: `findAllActive`가 필요로 하는 "ACTIVE 사용자"는 user 소유 상태값이라 이 캐시 테이블만으론 판별 불가. 캐시 테이블에 `is_active BOOLEAN` 컬럼을 추가하고 `UserApprovedEvent`/`UserRejectedEvent`(기존 sharedkernel 이벤트 없으면 user 소유 이벤트) 구독으로 함께 갱신하거나, `findAllActive()`의 실제 소비처(`MarketEventNotifier`)가 이 캐시 대신 다른 신호로 대체 가능한지 확인 후 결정 — **구현 전 반드시 `MarketEventNotifier.notify()`의 `findAllActive()` 실사용 맥락을 읽고 이 캐시 테이블 스키마에 상태 컬럼을 포함할지 확정**.

- [ ] **Step 8: `UserNotifyProfileEventListener` 작성(trading-core)**

```java
package com.kista.trading.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.user.domain.model.UserNotifyProfileChangedEvent; // sharedkernel 승격 여부 확인 후 import 경로 조정
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// user_notify_profile 캐시 동기화 — DB 분리 전까지 같은 Modulith EPR 경유
@Component
@RequiredArgsConstructor
class UserNotifyProfileEventListener {

    private final UserNotifyProfileWritePort writePort; // upsert/delete 전용 내부 포트(신설)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onChanged(UserNotifyProfileChangedEvent event) {
        writePort.upsert(event.userId(), event.notificationPrefs(), event.balanceCheckEnabled());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onDeleted(UserDeletedEvent event) {
        writePort.deleteByUserId(event.userId());
    }
}
```

(`UserNotifyProfileChangedEvent`가 `com.kista.user.domain.model` 소속이면 trading-core가 이를 import하는 순간 `user → trading-core` 역방향 컴파일 의존이 생긴다 — **이 이벤트 타입은 반드시 `com.kista.sharedkernel`에 정의**해야 한다. Step 2를 sharedkernel 패키지로 재조정)

- [ ] **Step 9: `AccountUserCascadeListener` 작성(trading-core, account 모듈)**

```java
package com.kista.account.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.sharedkernel.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// UserCascadeDeleter(user)의 accountPort.deleteByUserId 직접 호출을 대체 — strategy/finance
// cascade와 동일한 AFTER_COMMIT 이벤트 패턴으로 통일(동기 -> 비동기 전환, EPR 재시도 보장)
@Component
@RequiredArgsConstructor
class AccountUserCascadeListener {

    private final AccountPort accountPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onUserDeleted(UserDeletedEvent event) {
        accountPort.deleteByUserId(event.userId());
    }
}
```

- [ ] **Step 10: `UserCascadeDeleter`에서 `AccountPort` 직접 호출 제거**

`accountPort.deleteByUserId(userId)` 라인과 `AccountPort` 필드·import 삭제(이미 이 메서드 안에서 `UserDeletedEvent`를 발행하고 있다면 그대로 두고 직접 호출만 제거 — 발행 안 하고 있었다면 이 태스크에서 발행 추가).

- [ ] **Step 11: `TradingUserProfileAdapter`(web) 삭제**

```bash
git rm src/main/java/com/kista/web/trading/TradingUserProfileAdapter.java
git rm src/test/java/com/kista/web/trading/TradingUserProfileAdapterTest.java 2>/dev/null || true
```

- [ ] **Step 12: 전체 테스트**

```bash
bash gradlew test
```

Expected: BUILD SUCCESSFUL. `UserCascadeDeleterTest`의 기존 "계좌 삭제 확인" 단언이 동기 검증(직접 mock verify)이었다면 이벤트 발행 검증으로 수정.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(trading): user_notify_profile 신설, TradingUserProfilePort 구조 교체

TradingUserProfilePort는 trading-core 정의·root(web) 구현 역방향이라
컴파일 의존 0 이후 애초에 성립 불가능한 구조였다 — trading-core 자체
DB의 user_notify_profile 캐시 테이블 + UserNotifyProfilePersistenceAdapter
로 교체, TradingUserProfileAdapter(web) 삭제. 프로필 변경 동기화는
신규 UserNotifyProfileChangedEvent(sharedkernel), cascade 삭제는 기존
UserDeletedEvent 재사용 — 둘 다 같은 DB 위 Modulith EPR 경유(4단계 DB
분리 시 Redis Stream으로 교체 예정).

UserCascadeDeleter의 AccountPort.deleteByUserId 동기 직접호출도 같은
이유로 제거하고 AccountUserCascadeListener(AFTER_COMMIT)로 교체 —
계좌 cascade 삭제가 동기에서 비동기로 바뀐다(strategy/finance cascade와
동일 패턴이 되어 일관성 개선).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 13: `:shared` 2차 승격 — 포트 3종 + 순수 값타입 3종

**RULING(Task13 재조사, advisor 확인)**: 버킷(B)(`RuntimeSettingsService implements BrokerEnabledPort/StrategyCreationPolicyPort`, `AlpacaIndexPriceAdapter implements HistoricalCandlePort`)는 root가 trading-core 정의 인터페이스를 **implements**하는 구조라 own-type 복제로 풀 수 없다(타입 identity가 필요한 지점 — constraints.md "own-type 정당화 게이트" 일반화 서술 참고). 세 인터페이스 전부 시그니처가 sharedkernel+JDK 타입만 사용해 outbound-zero 조건을 그대로 만족하므로 `:shared`로 승격한다. `HistoricalCandlePort`가 쓰는 `DailyCandle`(JDK-only record)도 함께 승격해야 `:shared`의 outbound-zero 불변조건이 깨지지 않는다.

같은 성격의 순수 값타입 2종을 이번 태스크에 합친다:
- `ReturnMetrics`(trading-core `trading.stats.domain.model`): import가 `java.math`/`java.util.List`뿐인 순수 static 유틸(수익률·낙폭 계산). root `HousingBenchmarkComparisonBuilder`와 trading-core `BacktestEngine`/`BacktestService` 양쪽이 공용 소비 — own-type 복제 시 두 소비처의 계산 상수(`SCALE=10`/`HALF_UP`)가 갈라질 위험이 있어 이관이 아니라 **승격**으로 처리(architecture.md의 기존 "own-type 대신 trading.stats 이관" 결정은 root가 trading-core를 여전히 참조하던 시절 기준이라, 컴파일 의존 0 게이트 하에서는 이관 자체가 불가능해져 승격이 유일한 선택지다).
- `TradingReport`(trading-core `trading.domain.model`): 5필드(`date` LocalDate, `strategyType` StrategyType, `ticker` StrategyTicker, `totalBoughtUsd`/`totalSoldUsd` BigDecimal) 전부 sharedkernel enum + JDK — Task17(notify)이 `UserNotificationPort.notifyTradingReport()` 시그니처에서 이 타입을 그대로 쓰기 위한 선행 승격.

**Files:**
- Move: `trading-core/src/main/java/com/kista/account/application/port/output/BrokerEnabledPort.java` → `shared/src/main/java/com/kista/sharedkernel/port/BrokerEnabledPort.java`
- Move: `trading-core/src/main/java/com/kista/trading/application/port/output/StrategyCreationPolicyPort.java` → `shared/src/main/java/com/kista/sharedkernel/port/StrategyCreationPolicyPort.java`
- Move: `trading-core/src/main/java/com/kista/trading/stats/application/port/output/HistoricalCandlePort.java` → `shared/src/main/java/com/kista/sharedkernel/port/HistoricalCandlePort.java`
- Move: `trading-core/src/main/java/com/kista/trading/stats/domain/model/backtest/DailyCandle.java` → `shared/src/main/java/com/kista/sharedkernel/DailyCandle.java`
- Move: `trading-core/src/main/java/com/kista/trading/stats/domain/model/ReturnMetrics.java` → `shared/src/main/java/com/kista/sharedkernel/ReturnMetrics.java`
- Move: `trading-core/src/main/java/com/kista/trading/domain/model/TradingReport.java` → `shared/src/main/java/com/kista/sharedkernel/TradingReport.java`
- Modify: 위 6개 타입을 참조하는 모든 파일의 import(root+trading-core 양쪽) — Step 3에서 grep으로 전수 확인
- Modify: `docs/agents/constraints.md` — "포트 역전(DIP)" 목록에서 `BrokerEnabledPort`/`StrategyCreationPolicyPort` 서술 갱신
- Modify: `docs/agents/architecture.md` — sharedkernel 절에 `port` 서브패키지 신설 및 `TradingReport`/`ReturnMetrics`/`DailyCandle` 이관 사실 반영

**Interfaces:**
- Consumes: 없음(Task 1의 `:shared` 서브프로젝트만 선행조건)
- Produces: `com.kista.sharedkernel.port.{BrokerEnabledPort,StrategyCreationPolicyPort,HistoricalCandlePort}`, `com.kista.sharedkernel.{DailyCandle,ReturnMetrics,TradingReport}` — Task17이 `TradingReport` 소비

- [ ] **Step 1: 이동 전 각 타입의 참조처 전수 확인**

```bash
grep -rln "com\.kista\.account\.application\.port\.output\.BrokerEnabledPort" --include="*.java" . 2>/dev/null
grep -rln "com\.kista\.trading\.application\.port\.output\.StrategyCreationPolicyPort" --include="*.java" . 2>/dev/null
grep -rln "com\.kista\.trading\.stats\.application\.port\.output\.HistoricalCandlePort" --include="*.java" . 2>/dev/null
grep -rln "com\.kista\.trading\.stats\.domain\.model\.backtest\.DailyCandle" --include="*.java" . 2>/dev/null
grep -rln "com\.kista\.trading\.stats\.domain\.model\.ReturnMetrics" --include="*.java" . 2>/dev/null
grep -rln "com\.kista\.trading\.domain\.model\.TradingReport" --include="*.java" . 2>/dev/null
```

각 결과 목록을 기록 — Step 3에서 이 파일들의 import를 일괄 치환한다. 최소한 다음이 포함될 것으로 예상: `RuntimeSettingsService`(BrokerEnabledPort/StrategyCreationPolicyPort 구현), `AccountService`(BrokerEnabledPort 소비), strategy-config 리졸버 4종(StrategyCreationPolicyPort 소비), `AlpacaIndexPriceAdapter`(HistoricalCandlePort/DailyCandle), `BacktestEngine`/`FillSimulator`/`BacktestService`/`BacktestCommand`/`BacktestResult`(DailyCandle), `HousingBenchmarkComparisonBuilder`/`BacktestService`(ReturnMetrics), `TradingReporter`/`TradingReportNotifier`/영속성 어댑터(TradingReport).

- [ ] **Step 2: 디렉터리 이동 + 패키지 선언 변경**

```bash
mkdir -p shared/src/main/java/com/kista/sharedkernel/port
git mv trading-core/src/main/java/com/kista/account/application/port/output/BrokerEnabledPort.java shared/src/main/java/com/kista/sharedkernel/port/BrokerEnabledPort.java
git mv trading-core/src/main/java/com/kista/trading/application/port/output/StrategyCreationPolicyPort.java shared/src/main/java/com/kista/sharedkernel/port/StrategyCreationPolicyPort.java
git mv trading-core/src/main/java/com/kista/trading/stats/application/port/output/HistoricalCandlePort.java shared/src/main/java/com/kista/sharedkernel/port/HistoricalCandlePort.java
git mv trading-core/src/main/java/com/kista/trading/stats/domain/model/backtest/DailyCandle.java shared/src/main/java/com/kista/sharedkernel/DailyCandle.java
git mv trading-core/src/main/java/com/kista/trading/stats/domain/model/ReturnMetrics.java shared/src/main/java/com/kista/sharedkernel/ReturnMetrics.java
git mv trading-core/src/main/java/com/kista/trading/domain/model/TradingReport.java shared/src/main/java/com/kista/sharedkernel/TradingReport.java
```

각 이동한 파일의 `package` 선언을 새 위치에 맞춰 수정:
- `BrokerEnabledPort.java`/`StrategyCreationPolicyPort.java`/`HistoricalCandlePort.java`: `package com.kista.sharedkernel.port;` (기존 import인 `com.kista.sharedkernel.Broker`/`StrategyCreationSettings`/`StrategyType`는 이제 상위 패키지 참조가 되므로 `import com.kista.sharedkernel.Broker;` 등으로 명시 추가 — 같은 패키지가 아니므로 생략 불가. `HistoricalCandlePort`는 `import com.kista.sharedkernel.DailyCandle;` 추가)
- `DailyCandle.java`/`ReturnMetrics.java`/`TradingReport.java`: `package com.kista.sharedkernel;`(변경 없음 — 이미 flat 패키지 관례를 따름), 단 `TradingReport.java`는 `import com.kista.sharedkernel.StrategyType;`/`StrategyTicker` import가 같은 패키지로 이동하며 불필요해지므로 삭제

- [ ] **Step 3: Step1에서 찾은 모든 참조처 import 일괄 치환**

```bash
grep -rl "com\.kista\.account\.application\.port\.output\.BrokerEnabledPort" --include="*.java" . | xargs sed -i 's/com\.kista\.account\.application\.port\.output\.BrokerEnabledPort/com.kista.sharedkernel.port.BrokerEnabledPort/g'
grep -rl "com\.kista\.trading\.application\.port\.output\.StrategyCreationPolicyPort" --include="*.java" . | xargs sed -i 's/com\.kista\.trading\.application\.port\.output\.StrategyCreationPolicyPort/com.kista.sharedkernel.port.StrategyCreationPolicyPort/g'
grep -rl "com\.kista\.trading\.stats\.application\.port\.output\.HistoricalCandlePort" --include="*.java" . | xargs sed -i 's/com\.kista\.trading\.stats\.application\.port\.output\.HistoricalCandlePort/com.kista.sharedkernel.port.HistoricalCandlePort/g'
grep -rl "com\.kista\.trading\.stats\.domain\.model\.backtest\.DailyCandle" --include="*.java" . | xargs sed -i 's/com\.kista\.trading\.stats\.domain\.model\.backtest\.DailyCandle/com.kista.sharedkernel.DailyCandle/g'
grep -rl "com\.kista\.trading\.stats\.domain\.model\.ReturnMetrics" --include="*.java" . | xargs sed -i 's/com\.kista\.trading\.stats\.domain\.model\.ReturnMetrics/com.kista.sharedkernel.ReturnMetrics/g'
grep -rl "com\.kista\.trading\.domain\.model\.TradingReport" --include="*.java" . | xargs sed -i 's/com\.kista\.trading\.domain\.model\.TradingReport/com.kista.sharedkernel.TradingReport/g'
```

`RuntimeSettingsService`/`AlpacaIndexPriceAdapter`처럼 같은 파일에서 `implements BrokerEnabledPort, StrategyCreationPolicyPort` 등으로 짧은 이름만 쓰고 import 문에만 FQCN이 등장하는 경우 위 sed로 충분하다. FQCN을 인라인으로 쓰는 코드(드묾)가 있으면 Step1 목록을 다시 열어 수동 확인.

- [ ] **Step 4: 컴파일 확인**

```bash
bash gradlew :shared:compileJava :trading-core:compileJava :compileJava
```

Expected: 세 태스크 모두 성공. 실패하면 에러 메시지의 파일이 Step1 grep에서 누락된 참조처 — 해당 파일의 import를 수동으로 고치고 재실행.

- [ ] **Step 5: 전체 테스트**

```bash
bash gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 문서 갱신**

`docs/agents/constraints.md` "포트 역전(DIP)은 값 타입 복제가 아니므로..." 문단에서:
```
- BrokerEnabledPort(account 정의·admin 구현)
+ BrokerEnabledPort(sharedkernel 정의·admin 구현·account 소비)
```
```
- StrategyCreationPolicyPort(trading 정의·admin 구현)
+ StrategyCreationPolicyPort(sharedkernel 정의·admin 구현·trading 소비)
```
한 줄 추가: `HistoricalCandlePort`도 동일하게 `com.kista.sharedkernel.port`로 승격되어 admin/stats 어느 쪽 소유도 아니게 됐다는 점을 명시.

`docs/agents/architecture.md` sharedkernel 절 서두("outbound reference 0인 값 타입만 담아...")에 한 문장 추가: `port` 서브패키지에는 예외적으로 순수 인터페이스(BrokerEnabledPort/StrategyCreationPolicyPort/HistoricalCandlePort)가 있으며, 시그니처가 sharedkernel+JDK 타입만 사용해 outbound-zero 조건은 동일하게 유지된다는 점. `TradingReport`/`ReturnMetrics`/`DailyCandle`이 trading-core에서 이관됐다는 사실도 각 모듈 절(trading/stats)의 해당 타입 서술에서 "sharedkernel 소유로 이관"으로 갱신.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(build): 포트 3종+값타입 3종 :shared 2차 승격

RuntimeSettingsService/AlpacaIndexPriceAdapter가 trading-core 정의
인터페이스(BrokerEnabledPort/StrategyCreationPolicyPort/HistoricalCandlePort)를
implements하는 구조는 타입 identity가 필요해 own-type 복제로 풀 수 없다
(:api -> :trading-core 컴파일 의존 0 이후 root가 trading-core 인터페이스를
구현하는 것 자체가 불가능) — 세 인터페이스 모두 시그니처가 sharedkernel+JDK
타입만 사용해 outbound-zero를 만족하므로 :shared로 승격.

같은 이유로 DailyCandle(HistoricalCandlePort 페이로드)·ReturnMetrics(root
HousingBenchmarkComparisonBuilder와 trading-core BacktestEngine 공용 순수
계산 유틸 — own-type 복제 시 계산 상수 갈라질 위험)·TradingReport(Task17의
notify 포트 시그니처 선행조건, 5필드 전부 sharedkernel+JDK)도 함께 승격.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 14: admin 논-notify 내부API 3건

**Files:**
- Modify: `src/main/java/com/kista/admin/application/port/output/AccountQueryPort.java` — `countAll()` 메서드 추가
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/AccountQueryHttpAdapter.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminQueryService.java` — `AccountPort` 직접 참조 제거
- Create: `trading-core/src/main/java/com/kista/account/adapter/in/web/AccountInternalController.java`에 `GET /api/internal/accounts/count` 라우트 추가(기존 컨트롤러 확장)
- Modify: `src/main/java/com/kista/admin/application/port/output/TradingCommandPort.java` — `updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status)` 추가
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapter.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminStrategyService.java` — `AccountPort`/`StrategyPort`/`Account`/`Strategy` 직접 참조 제거
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/web/StrategyStatusInternalController.java`
- Modify: `src/main/java/com/kista/admin/application/port/output/TradingCommandPort.java` — `reorderTimingAvailability()`가 시장 개장 여부까지 판정하도록 계약 확장(응답에 `marketOpen` 없이, trading-core 쪽 구현이 개장 아니면 3개 boolean을 전부 false로 반환)
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/in/web/TradingSchedulerInternalController.java` 또는 별도 컨트롤러 — `reorderTimingAvailability()` 내부 API 구현 쪽에서 `MarketCalendarPort.isMarketOpen()` 판정 포함
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminTradeController.java` — `MarketCalendarPort` 직접 참조 제거
- Test: `src/test/java/com/kista/admin/application/service/AdminQueryServiceTest.java`(갱신)
- Test: `src/test/java/com/kista/admin/application/service/AdminStrategyServiceTest.java`(갱신)
- Test: `src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java`(갱신)

**Interfaces:**
- Consumes: 없음
- Produces: `AccountQueryPort.countAll(): long`, `TradingCommandPort.updateStrategyStatus(UUID, UUID, StrategyStatus): void`(내부에서 소유권 검증 후 저장, 불일치 시 404), `TradingCommandPort.reorderTimingAvailability()`가 비개장일에 항상 `AdminReorderTimingAvailability(false,false,false)` 반환하도록 계약 확정

- [ ] **Step 1: `AdminQueryService.getStats()` — `AccountPort.countAll()` → 내부 API로 전환**

`trading-core/src/main/java/com/kista/account/adapter/in/web/AccountInternalController.java`에 라우트 추가:

```java
@GetMapping("/count")
public long count() {
    return accountPort.countAll();
}
```

`src/main/java/com/kista/admin/application/port/output/AccountQueryPort.java`에 메서드 추가:
```java
long countAll(); // 전체 계좌 수 — AdminQueryService.getStats() 소비
```

`AccountQueryHttpAdapter.java`에 구현 추가:
```java
@Override
public long countAll() {
    Long count = internalApiRestClient.get()
            .uri("/api/internal/accounts/count")
            .retrieve()
            .body(Long.class);
    return count != null ? count : 0L;
}
```

`AdminQueryService.java`에서 `accountPort.countAll()` → `accountQueryPort.countAll()`로 교체, `AccountPort accountPort` 필드와 `import com.kista.account.application.port.output.AccountPort;` 삭제.

- [ ] **Step 2: `AdminStrategyService` pause/resume — 신규 내부 API로 전환**

`trading-core/src/main/java/com/kista/trading/adapter/in/web/StrategyStatusInternalController.java` 신설:

```java
package com.kista.trading.adapter.in.web;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.Strategy;
import com.kista.sharedkernel.StrategyStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// admin의 pauseStrategy/resumeStrategy가 소유권 검증(strategy.accountId == account.id)까지
// 포함해 위임하는 내부 전용 엔드포인트 — Account/Strategy를 root에 노출하지 않기 위함
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}")
@RequiredArgsConstructor
public class StrategyStatusInternalController {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @PatchMapping("/status")
    public void updateStatus(@PathVariable UUID accountId, @PathVariable UUID strategyId,
                              @RequestParam StrategyStatus status) {
        Account account = accountPort.findByIdOrThrow(accountId);
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
        strategyPort.save(strategy.withStatus(status));
    }
}
```

`TradingCommandPort.java`에 메서드 추가:
```java
void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status);
```

`TradingCommandHttpAdapter.java`에 구현 추가:
```java
@Override
public void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status) {
    internalApiRestClient.patch()
            .uri(uriBuilder -> uriBuilder
                    .path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/status")
                    .queryParam("status", status)
                    .build(accountId, strategyId))
            .retrieve()
            .toBodilessEntity();
}
```

`AdminStrategyService.java`를 다음과 같이 교체:
```java
package com.kista.admin.application.service;

import com.kista.admin.application.usecase.AdminStrategyUseCase;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.sharedkernel.StrategyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class AdminStrategyService implements AdminStrategyUseCase {

    private final TradingCommandPort tradingCommandPort;
    private final AuditLogPort auditLogPort;

    @Override
    public void pauseStrategy(UUID adminId, UUID accountId, UUID strategyId) {
        tradingCommandPort.updateStrategyStatus(accountId, strategyId, StrategyStatus.PAUSED);
        auditLogPort.record(adminId, "STRATEGY_PAUSE", strategyId.toString());
    }

    @Override
    public void resumeStrategy(UUID adminId, UUID accountId, UUID strategyId) {
        tradingCommandPort.updateStrategyStatus(accountId, strategyId, StrategyStatus.ACTIVE);
        auditLogPort.record(adminId, "STRATEGY_RESUME", strategyId.toString());
    }
}
```

(기존 `auditLogPort.record(...)` 실제 시그니처·호출 인자는 원본 파일 확인 후 그대로 유지 — 위 스켈레톤은 필드·의존성 축소만 보여준다. `@Transactional` 애너테이션은 삭제한다 — 이제 이 서비스는 순수 HTTP 위임이라 트랜잭션 경계가 무의미하고, `@Transactional 내부 외부 시스템 호출 금지` 규칙과도 부합하지 않았다.)

- [ ] **Step 3: `AdminTradeController.getReorderTiming()` — `MarketCalendarPort` 참조 제거**

`trading-core`의 `TradingCommandPort` 구현부(어느 컨트롤러가 `reorderTimingAvailability` 내부 API를 서빙하는지 확인):
```bash
grep -rn "reorder-timing\|reorderTimingAvailability" trading-core/src/main/java
```
해당 컨트롤러 메서드에 개장 여부 판정을 추가:
```java
@GetMapping("/reorder-timing-availability")
public AdminReorderTimingAvailabilityResponse get() {
    if (!marketCalendarPort.isMarketOpen(LocalDate.now(TimeZones.KST))) {
        return new AdminReorderTimingAvailabilityResponse(false, false, false);
    }
    return AdminReorderTimingAvailabilityResponse.from(dstInfo.reorderTimingAvailability());
}
```
(`marketCalendarPort` 필드 주입 추가 — trading-core 내부라 `com.kista.marketcalendar.application.port.output.MarketCalendarPort` 직접 의존 무방)

`AdminTradeController.java`에서 `getReorderTiming()`을 다음으로 교체:
```java
@GetMapping("/trades/reorder-timing")
public ReorderTimingAvailabilityResponse getReorderTiming() {
    return ReorderTimingAvailabilityResponse.from(tradingCommandPort.reorderTimingAvailability());
}
```
`MarketCalendarPort marketCalendarPort` 필드, `import com.kista.marketcalendar.application.port.output.MarketCalendarPort;`, `import com.kista.sharedkernel.TimeZones;`(이 컨트롤러에서만 쓰던 경우) 삭제.

- [ ] **Step 4: 테스트 갱신 및 통과 확인**

`AdminQueryServiceTest`: `AccountPort` mock → `AccountQueryPort.countAll()` mock.
`AdminStrategyServiceTest`: `AccountPort`/`StrategyPort` mock 전부 제거, `TradingCommandPort.updateStrategyStatus(...)` 호출 검증으로 교체.
`AdminTradeControllerTest`: `MarketCalendarPort` mock 제거.

```bash
bash gradlew test --tests "com.kista.admin.*"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kista/admin src/test/java/com/kista/admin trading-core/src/main/java/com/kista/account/adapter/in/web trading-core/src/main/java/com/kista/trading/adapter/in/web
git commit -m "$(cat <<'EOF'
refactor(admin): 논-notify 내부API 3건 전환

AdminQueryService.getStats()의 AccountPort.countAll(), AdminStrategyService
pause/resume의 AccountPort+StrategyPort 소유권검증+저장, AdminTradeController
getReorderTiming()의 MarketCalendarPort.isMarketOpen() — 3곳 전부 신규/확장
내부API(AccountInternalController /count, 신규 StrategyStatusInternalController,
기존 reorder-timing 엔드포인트에 개장판정 이관)로 교체해 admin의 잔여
trading-core 직접 import를 해소했다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 15: stats 내부API — 환율 조회

**Files:**
- Create: `src/main/java/com/kista/stats/application/port/output/CurrentExchangeRatePort.java`
- Create: `src/main/java/com/kista/stats/adapter/out/internal/CurrentExchangeRateHttpAdapter.java`
- Modify: `src/main/java/com/kista/stats/application/service/StatsService.java`
- Modify: `trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/TradingStatsInternalController.java` — `GET /exchange-rate` 라우트 추가
- Test: `src/test/java/com/kista/stats/adapter/out/internal/CurrentExchangeRateHttpAdapterTest.java`
- Test: `src/test/java/com/kista/stats/application/service/StatsServiceTest.java`(갱신)

**Interfaces:**
- Consumes: 없음
- Produces: `CurrentExchangeRatePort.getMidRate(): BigDecimal`(null 가능 — 조회 실패 시)

- [ ] **Step 1: trading-core 내부 API 라우트 추가**

`trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/TradingStatsInternalController.java`에 라우트 추가:
```java
@GetMapping("/exchange-rate")
public BigDecimal exchangeRate() {
    return exchangeRatePort.getExchangeRate().midRate();
}
```
(`ExchangeRatePort exchangeRatePort` 필드 주입 추가 — trading-core 내부라 `com.kista.broker.application.port.output.ExchangeRatePort` 직접 의존 무방. 컨트롤러 전체 경로가 `/api/internal/trading/stats`라면 최종 경로는 `/api/internal/trading/stats/exchange-rate`)

- [ ] **Step 2: root 포트·어댑터 신설**

```java
package com.kista.stats.application.port.output;

import java.math.BigDecimal;

// 현재 USD/KRW 매매기준율(TOSS_INVEST) 조회 — StatsService의 자산곡선 벤치마크 비교용,
// broker.domain.model.toss.TossExchangeRate(trading-core) 중 midRate 필드 하나만 필요해
// 포트 자체를 좁게 정의(narrowing) — StatsService는 rate() 필드를 쓰지 않는다
public interface CurrentExchangeRatePort {
    BigDecimal getMidRate(); // 조회 실패 시 null
}
```

```java
package com.kista.stats.adapter.out.internal;

import com.kista.stats.application.port.output.CurrentExchangeRatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
class CurrentExchangeRateHttpAdapter implements CurrentExchangeRatePort {

    private final RestClient internalApiRestClient;

    @Override
    public BigDecimal getMidRate() {
        try {
            return internalApiRestClient.get()
                    .uri("/api/internal/trading/stats/exchange-rate")
                    .retrieve()
                    .body(BigDecimal.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
```

- [ ] **Step 3: `StatsService.fetchCurrentExchangeRate()` 교체**

```java
private CurrentExchangeRate fetchCurrentExchangeRate() {
    try {
        BigDecimal midRate = currentExchangeRatePort.getMidRate();
        if (midRate == null || midRate.signum() <= 0) {
            return null;
        }
        return new CurrentExchangeRate(midRate, Instant.now(), "TOSS_INVEST");
    } catch (RuntimeException e) {
        log.warn("현재 USD/KRW 환율 조회 실패", e);
        return null;
    }
}
```

`private final CurrentExchangeRatePort currentExchangeRatePort;` 필드 추가, `ExchangeRatePort exchangeRatePort` 필드와 `import com.kista.broker.application.port.output.ExchangeRatePort;`/`import com.kista.broker.domain.model.toss.TossExchangeRate;` 삭제.

- [ ] **Step 4: 테스트 작성 및 통과 확인**

`CurrentExchangeRateHttpAdapterTest`: `MockRestServiceServer` 또는 `RestClient` mock으로 200/장애 케이스 2개.
`StatsServiceTest`: `ExchangeRatePort` mock → `CurrentExchangeRatePort.getMidRate()` mock.

```bash
bash gradlew test --tests "com.kista.stats.*"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kista/stats src/test/java/com/kista/stats trading-core/src/main/java/com/kista/trading/stats/adapter/in/web
git commit -m "$(cat <<'EOF'
refactor(stats): 환율 조회를 내부API로 전환

StatsService.fetchCurrentExchangeRate()가 broker.ExchangeRatePort/
TossExchangeRate를 직접 참조하고 있었다 — 실제로 쓰는 필드는 midRate
하나뿐이라 CurrentExchangeRatePort(BigDecimal 단일 반환)로 narrowing해
TradingStatsInternalController 신규 라우트(GET .../exchange-rate) 경유로
전환.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 16: market 내부API 4건 — marketcalendar/broker 참조 제거

**Files:**
- Modify: `src/main/java/com/kista/market/adapter/in/web/dto/TossCandleResponse.java` — own-type 필드로 직접 매핑(내부API 응답 own-type 소비)
- Create: `trading-core/src/main/java/com/kista/marketcalendar/adapter/in/web/MarketCalendarInternalController.java`
- Create: `src/main/java/com/kista/market/domain/model/MarketSession.java`(own-type — `DIRECT`/`BLOCKED` 2값 enum)
- Create: `src/main/java/com/kista/market/application/port/output/MarketCalendarQueryPort.java`
- Create: `src/main/java/com/kista/market/adapter/out/internal/MarketCalendarQueryHttpAdapter.java`
- Create: `trading-core/src/main/java/com/kista/broker/adapter/in/web/CandleInternalController.java`
- Modify: `src/main/java/com/kista/market/adapter/in/web/MarketHolidayController.java`
- Modify: `src/main/java/com/kista/market/application/service/MarketHolidayService.java`
- Modify: `src/main/java/com/kista/market/application/usecase/MarketUseCase.java`
- Test: `src/test/java/com/kista/market/adapter/out/internal/MarketCalendarQueryHttpAdapterTest.java`
- Test: `src/test/java/com/kista/market/application/service/MarketHolidayServiceTest.java`(갱신)

**Interfaces:**
- Consumes: 없음
- Produces: `MarketCalendarQueryPort.findHolidaysForMonth(int,int): List<LocalDate>`, `.isMarketOpen(LocalDate): boolean`, `.currentSession(): MarketSessionView(MarketSession session, boolean isDst)`(market own-type), `TossCandleResponse` own-type 매핑(변경 없음 — 이미 stats 사본과 byte-identical 전례, 이번엔 broker `CandlePort` 응답을 같은 방식으로 소비)

- [ ] **Step 1: marketcalendar 신규 내부 API 컨트롤러**

marketcalendar 모듈에 `adapter/in/web`이 없으므로 신설:

```java
package com.kista.marketcalendar.adapter.in.web;

import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.marketcalendar.domain.model.MarketSessionSnapshot;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// root market 모듈(공포탐욕지수)이 marketcalendar 값을 직접 참조하지 않도록 하는 내부 전용 엔드포인트
@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/marketcalendar")
@RequiredArgsConstructor
public class MarketCalendarInternalController {

    private final MarketCalendarPort marketCalendarPort;

    @GetMapping("/holidays")
    public List<LocalDate> holidays(@RequestParam int year, @RequestParam int month) {
        return marketCalendarPort.findHolidaysForMonth(year, month);
    }

    @GetMapping("/is-open")
    public boolean isOpen(@RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
        return marketCalendarPort.isMarketOpen(date);
    }

    @GetMapping("/session")
    public SessionResponse session() {
        MarketSessionSnapshot snapshot = MarketSessionSnapshot.now();
        return new SessionResponse(snapshot.session().name(), snapshot.isDst());
    }

    record SessionResponse(String session, boolean isDst) {}
}
```

- [ ] **Step 2: broker 신규 내부 API 컨트롤러(캔들)**

broker 모듈에도 `adapter/in/web`이 없으므로 신설:

```java
package com.kista.broker.adapter.in.web;

import com.kista.broker.application.port.output.CandlePort;
import com.kista.broker.domain.model.toss.TossCandle;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "내부 API", description = "서버 간 내부 호출 전용 엔드포인트 (X-Internal-Token 인증)")
@RestController
@RequestMapping("/api/internal/broker/candles")
@RequiredArgsConstructor
public class CandleInternalController {

    private final CandlePort candlePort;

    @GetMapping("/latest")
    public List<TossCandle> latest(@RequestParam String symbol, @RequestParam String interval, @RequestParam int count) {
        return candlePort.getLatestCandles(symbol, interval, count);
    }
}
```

- [ ] **Step 3: root own-type + 포트 + 어댑터**

```java
package com.kista.market.domain.model;

// marketcalendar.domain.model.MarketSessionSnapshot의 MarketSession own-type — 2값 뿐이라
// enum 그대로 복제(DIRECT: 정규장 직접 접수, BLOCKED: 개장전/장마감 등 접수 차단)
public enum MarketSession {
    DIRECT, BLOCKED
}
```

```java
package com.kista.market.application.port.output;

import com.kista.market.domain.model.MarketSession;

import java.time.LocalDate;
import java.util.List;

public interface MarketCalendarQueryPort {
    List<LocalDate> findHolidaysForMonth(int year, int month);
    boolean isMarketOpen(LocalDate date);
    SessionView currentSession();

    record SessionView(MarketSession session, boolean isDst) {}
}
```

```java
package com.kista.market.adapter.out.internal;

import com.kista.market.application.port.output.MarketCalendarQueryPort;
import com.kista.market.domain.model.MarketSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
class MarketCalendarQueryHttpAdapter implements MarketCalendarQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<LocalDate> findHolidaysForMonth(int year, int month) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/marketcalendar/holidays").queryParam("year", year).queryParam("month", month).build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<LocalDate>>() {});
    }

    @Override
    public boolean isMarketOpen(LocalDate date) {
        Boolean open = internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/marketcalendar/is-open").queryParam("date", date).build())
                .retrieve()
                .body(Boolean.class);
        return open != null && open;
    }

    @Override
    public SessionView currentSession() {
        SessionResponse response = internalApiRestClient.get()
                .uri("/api/internal/marketcalendar/session")
                .retrieve()
                .body(SessionResponse.class);
        return new SessionView(MarketSession.valueOf(response.session()), response.isDst());
    }

    record SessionResponse(String session, boolean isDst) {}
}
```

- [ ] **Step 4: `MarketHolidayController`/`MarketHolidayService`/`MarketUseCase` 갱신**

`MarketHolidayService.java`: `MarketCalendarPort marketCalendarPort` 필드를 `MarketCalendarQueryPort marketCalendarQueryPort`로 교체, `getMonthlyHolidays()`는 `marketCalendarQueryPort.findHolidaysForMonth(year, month)`로 위임(시그니처 동일, import만 교체). `import com.kista.marketcalendar.application.port.output.MarketCalendarPort;` 삭제.

`MarketHolidayController.java`의 `GET /api/market/session`:
```java
@GetMapping("/session")
public MarketSessionResponse getSession() {
    var session = marketCalendarQueryPort.currentSession();
    return new MarketSessionResponse(session.session().name(), session.isDst());
}
```
`import com.kista.marketcalendar.domain.model.MarketSessionSnapshot;` 삭제, `MarketCalendarQueryPort` 필드 추가.

`MarketUseCase.java`: `getDailyCandles(String symbol, int count): List<TossCandle>` 반환 타입을 그대로 유지(architecture.md의 기존 `TossCandleResponse` own-type 전례를 따라 `TossCandle` 자체는 이미 (b) 외부 계약 분리 게이트로 own-type 복제가 허용된 타입이다 — 단, 소비 경로만 broker `CandlePort`(trading-core) 직접 호출에서 신규 `CandleInternalController`(`/api/internal/broker/candles/latest`) HTTP 호출로 교체). `MarketHolidayService`(구현체 추정 — 실제 구현 클래스는 grep으로 확인)에서 `candlePort.getLatestCandles(symbol, "1d", count)` 호출을:
```java
List<TossCandle> candles = internalApiRestClient.get()
        .uri(b -> b.path("/api/internal/broker/candles/latest").queryParam("symbol", symbol).queryParam("interval", "1d").queryParam("count", count).build())
        .retrieve()
        .body(new ParameterizedTypeReference<List<TossCandle>>() {});
```
로 교체(단, 반환 타입 `List<TossCandle>`이 여전히 broker 소유 타입이라 실제로는 `TossCandleResponse`(market own-type)로 즉시 매핑해 반환하는 편이 컴파일 의존 0에 부합 — `MarketUseCase.getDailyCandles()`의 반환 타입 자체를 `List<TossCandleResponse>`로 바꾸고 호출부(`MarketHolidayController`의 관련 라우트)도 함께 갱신).

`TossCandleResponse.java`는 이미 own-type이라 변경 불필요 — 위 매핑에서 그대로 재사용.

- [ ] **Step 5: 테스트 작성 및 통과 확인**

```bash
bash gradlew test --tests "com.kista.market.*" --tests "com.kista.marketcalendar.*" --tests "com.kista.broker.*"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(market): marketcalendar/broker 잔여 참조 4건 내부API 전환

TossCandleResponse(이미 own-type이라 소비 경로만 교체), MarketHolidayController/
MarketHolidayService/MarketUseCase의 marketcalendar.MarketCalendarPort/
MarketSessionSnapshot, broker.CandlePort 직접 참조를 신규 내부API
(marketcalendar/broker 둘 다 adapter/in/web이 없어 신설)로 전환.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 17: notify — 이벤트 페이로드 narrowing + 리스너 전환 + TelegramBotService

**RULING(notify 조사 + advisor 확인)**: 처음 검토한 "own-type 뷰 4종 신설 + 리스너 9개 내부API 전환"은 과설계다. 실측 결과 `Account`는 `nickname()` 하나, `AccountBalance`는 `holdings`+`usdDeposit` 2개, `Strategy`는 `type`/`ticker`/`cycleSeedType` 3개(전부 sharedkernel enum)만 notify에서 쓰인다 — **이벤트 페이로드 자체를 이 스칼라들로 좁히면 리스너의 `AccountPort.findByIdOrThrow()` 재조회 4곳이 통째로 사라진다**(신규 포트·어댑터·컨트롤러 불필요). `TradingReport`는 Task13에서 이미 `:shared`로 승격했으므로 그대로 쓴다.

단, 이벤트 클래스 자체(`CycleEndedEvent`/`CycleCompletedEvent`/`NewCycleStartedEvent`/`InsufficientBalanceEvent`/`TradingReportReadyEvent`, 전부 `trading.application.event` 소유)를 리스너가 계속 import하는 한 필드를 아무리 좁혀도 root의 trading-core 컴파일 의존은 남는다 — narrowing 후 이벤트가 sharedkernel+JDK 타입만 담게 되므로, `UserDeletedEvent`/`UserNotifyProfileChangedEvent`(Task12)와 동일한 선례를 따라 **이벤트 클래스 자체를 `com.kista.sharedkernel`로 승격**한다.

`TradingReportReadyEvent.executions: List<Execution>`은 유일하게 narrowing 후에도 새 타입이 필요한 필드 — `Execution`(broker 소유, 필드 다수)에서 notify가 실제 쓰는 5필드만 담은 신규 sharedkernel 값타입 `TradeLegSummary`를 만든다.

이벤트 클래스 shape 변경은 `event_publication` 테이블(Modulith EPR)에 저장된 **미완료 상태 payload가 구 스키마**로 남는 배포 호환성 문제를 만든다 — 배포 direct 전에 미완료 publication을 확인한다.

**Files:**
- Create: `shared/src/main/java/com/kista/sharedkernel/TradeLegSummary.java`
- Move+Modify: `trading-core/src/main/java/com/kista/trading/application/event/CycleEndedEvent.java` → `shared/src/main/java/com/kista/sharedkernel/CycleEndedEvent.java`
- Move+Modify: `trading-core/src/main/java/com/kista/trading/application/event/CycleCompletedEvent.java` → `shared/src/main/java/com/kista/sharedkernel/CycleCompletedEvent.java`
- Move+Modify: `trading-core/src/main/java/com/kista/trading/application/event/NewCycleStartedEvent.java` → `shared/src/main/java/com/kista/sharedkernel/NewCycleStartedEvent.java`
- Move+Modify: `trading-core/src/main/java/com/kista/trading/application/event/InsufficientBalanceEvent.java` → `shared/src/main/java/com/kista/sharedkernel/InsufficientBalanceEvent.java`
- Move+Modify: `trading-core/src/main/java/com/kista/trading/application/event/TradingReportReadyEvent.java` → `shared/src/main/java/com/kista/sharedkernel/TradingReportReadyEvent.java`
- Modify: 위 5개 이벤트의 trading-core 쪽 발행 지점 전부(Step1에서 grep으로 확인)
- Modify: `src/main/java/com/kista/notify/application/port/output/NotifyPort.java`
- Modify: `src/main/java/com/kista/notify/application/port/output/UserNotificationPort.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/{CycleEndedNotifier,CycleLifecycleNotifier,TradingAlertNotifier,TradingReportNotifier,TelegramUserNotificationAdapter,FcmAdapter,TelegramAdapter}.java`
- Create: `src/main/java/com/kista/notify/application/port/output/PortfolioQueryPort.java`
- Create: `src/main/java/com/kista/notify/adapter/out/internal/PortfolioQueryHttpAdapter.java`
- Create: `trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/PortfolioQueryInternalController.java`
- Modify: `src/main/java/com/kista/notify/adapter/in/telegram/TelegramBotService.java`
- Modify: `docs/agents/constraints.md` — Flyway "2-role 배포 backward-compat" 절에 이벤트 스키마 변경 항목 추가
- Test: 위 리스너·어댑터 대응 테스트 전부 갱신

**Interfaces:**
- Consumes: `com.kista.sharedkernel.TradingReport`(Task13)
- Produces: `com.kista.sharedkernel.{CycleEndedEvent,CycleCompletedEvent,NewCycleStartedEvent,InsufficientBalanceEvent,TradingReportReadyEvent,TradeLegSummary}`, `NotifyPort.notifyInsufficientBalance(int holdings, BigDecimal usdDeposit, StrategyTicker ticker)`, `UserNotificationPort`의 narrowing된 시그니처(아래 Step4), `PortfolioQueryPort.getCurrent(UUID userId)`/`.getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker)`

- [ ] **Step 1: 배포 호환성 판단 — EPR 미완료 publication 확인**

```bash
grep -rn "CycleEndedEvent\|CycleCompletedEvent\|NewCycleStartedEvent\|InsufficientBalanceEvent\|TradingReportReadyEvent" trading-core/src/main/java --include="*.java" -l
```

이 목록이 이벤트별 발행 지점(publisher)이다. 각 발행 지점의 트랜잭션 커밋 이후 리스너가 처리하는 사이의 시간창은 짧고(같은 배치 사이클 내), 배포는 `deploy-api`가 시간대 무관하게 이뤄지므로(constraints.md) 배포 순간 이 5개 이벤트가 `event_publication` 테이블에 미완료 상태로 걸려있을 가능성은 이론상 존재하나 극히 낮다 — 그러나 무시하지 않는다. `docs/agents/constraints.md`의 "2-role 배포 backward-compat" 절에 다음을 추가:
```
- 이벤트 클래스 필드 shape 변경(예: Task17의 CycleEndedEvent 등 sharedkernel 승격 겸 narrowing)은
  event_publication 테이블에 남은 미완료 publication의 역직렬화를 깨뜨릴 수 있다 — 배포 직전
  `SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND event_type LIKE '%CycleEndedEvent%'`
  등으로 잔여 0건을 확인하거나, 배포 직후 짧은 시간 동안의 실패 알림(로그 ERROR)을 감수한다.
  후자를 택하면 배포 공지에 "이 배포 시점 진행 중이던 사이클 알림 일부가 유실될 수 있음"을 명시.
```
이 태스크 실행 시점(로컬/스테이징)에는 배포가 아니므로 이 확인은 문서화만 하고 다음 스텝으로 진행 — 실제 운영 배포 시점에 이 문서를 참고해 판단.

- [ ] **Step 2: `TradeLegSummary` 신설 + 5개 이벤트 이동·narrowing**

```java
package com.kista.sharedkernel;

import java.math.BigDecimal;

// broker.domain.model.Execution의 notify 전용 narrowing — TradingReportReadyEvent.executions가
// 이벤트 payload를 sharedkernel+JDK로만 구성하기 위해 필요한 최소 필드만 담는다
// (TradeEventView가 이미 같은 5필드로 리스너 내부에서 조립하던 것을 이벤트 정의 시점으로 당김)
public record TradeLegSummary(
        OrderDirection direction,
        StrategyTicker ticker,
        int quantity,
        BigDecimal price,
        BigDecimal amountUsd
) {
}
```

5개 이벤트 파일을 `git mv`로 `shared/src/main/java/com/kista/sharedkernel/`로 옮기고 `package com.kista.trading.application.event;` → `package com.kista.sharedkernel;`로 변경, 필드를 다음과 같이 좁힌다(각 record의 기존 필드 중 trading-core 타입만 교체, 나머지 유지):

```java
// CycleEndedEvent — 변경 전: (UUID userId, UUID accountId, Strategy strategy)
public record CycleEndedEvent(
        UUID userId, UUID accountId, String accountNickname,
        StrategyType strategyType, StrategyTicker ticker, StrategyCycleSeedType cycleSeedType
) {}

// CycleCompletedEvent — 동일 shape
public record CycleCompletedEvent(
        UUID userId, UUID accountId, String accountNickname,
        StrategyType strategyType, StrategyTicker ticker, StrategyCycleSeedType cycleSeedType
) {}

// NewCycleStartedEvent — 변경 전: (UUID userId, UUID accountId, Strategy strategy, BigDecimal initialUsdDeposit)
public record NewCycleStartedEvent(
        UUID userId, UUID accountId, String accountNickname,
        StrategyType strategyType, StrategyTicker ticker, BigDecimal initialUsdDeposit
) {}

// InsufficientBalanceEvent — 변경 전: (UUID userId, UUID accountId, AccountBalance b, StrategyTicker ticker, StrategyType strategyType)
public record InsufficientBalanceEvent(
        UUID userId, UUID accountId, String accountNickname,
        int holdings, BigDecimal usdDeposit, StrategyTicker ticker, StrategyType strategyType
) {}

// TradingReportReadyEvent — 변경 전: (UUID userId, UUID accountId, TradingReport report, List<Execution> executions, boolean reportEnabled)
public record TradingReportReadyEvent(
        UUID userId, UUID accountId, String accountNickname,
        TradingReport report, List<TradeLegSummary> executions, boolean reportEnabled
) {}
```

(각 record의 실제 필드 순서·추가 필드는 원본 파일을 먼저 Read해서 확인 후 위 shape에 맞춰 조정 — 위는 조사 결과 기준 최소 shape이다.)

- [ ] **Step 3: trading-core 발행 지점 갱신**

Step1의 grep 결과 각 파일에서 이벤트 생성 라인을 찾아 `Strategy strategy`/`Account account`/`AccountBalance b`/`List<Execution> executions` 인자를 풀어쓰기로 교체한다. 예시(`CycleEndedEvent` 발행 지점 — 실제 변수명은 grep 결과에 맞춰 조정):

```java
// 변경 전: eventPublisher.publishEvent(new CycleEndedEvent(userId, account.id(), strategy));
// 변경 후:
eventPublisher.publishEvent(new CycleEndedEvent(
        userId, account.id(), account.nickname(),
        strategy.type(), strategy.ticker(), strategy.cycleSeedType()));
```

`TradingReportReadyEvent` 발행 지점은 `executions`(`List<Execution>`)을 `TradeLegSummary`로 매핑:
```java
List<TradeLegSummary> legs = executions.stream()
        .map(e -> new TradeLegSummary(e.direction(), e.ticker(), e.quantity(), e.price(), e.amountUsd()))
        .toList();
eventPublisher.publishEvent(new TradingReportReadyEvent(
        userId, account.id(), account.nickname(), report, legs, reportEnabled));
```
(`Execution`의 실제 필드명은 원본 클래스 확인 후 맞춰 조정 — notify 조사에서 확인된 `direction()`/`ticker()`/`quantity()`/`price()`/`amountUsd()` 5개를 그대로 사용)

import를 `com.kista.trading.application.event.*` → `com.kista.sharedkernel.*`로 교체.

- [ ] **Step 4: `NotifyPort`/`UserNotificationPort` 시그니처 narrowing**

```java
// NotifyPort.java
void notifyInsufficientBalance(int holdings, BigDecimal usdDeposit, StrategyTicker ticker);
```
(`Account account` 파라미터 삭제 — `TelegramAdapter` 구현부에서 완전히 미사용이던 dead parameter였음이 조사로 확인됨)

```java
// UserNotificationPort.java
void notifyTradingReport(User user, String accountNickname, TradingReport report);
void notifyCycleCompleted(User user, String accountNickname, StrategyType strategyType, StrategyTicker ticker, StrategyCycleSeedType cycleSeedType);
void notifyNewCycleStarted(User user, String accountNickname, StrategyType strategyType, StrategyTicker ticker, BigDecimal initialUsdDeposit);
void notifyInsufficientBalance(User user, String accountNickname, StrategyType strategyType, StrategyTicker ticker);
void notifyBatchInterrupted(User user, String accountNickname);
```
(`Account account`/`Strategy strategy` 파라미터를 전부 스칼라로 교체 — `import com.kista.account.domain.model.Account;`/`import com.kista.trading.domain.model.Strategy;` 삭제. `TradingReport`는 Task13에서 `com.kista.sharedkernel.TradingReport`로 승격됐으므로 그대로 유지)

- [ ] **Step 5: 리스너·구현체 갱신**

`CycleEndedNotifier`/`CycleLifecycleNotifier`: `accountPort.findByIdOrThrow(event.accountId())` 호출 삭제, `event.accountNickname()`/`event.strategyType()`/`event.ticker()`/`event.cycleSeedType()`을 포트 호출에 직접 전달. `AccountPort accountPort` 필드와 import 삭제(단 `UserPort`는 여전히 필요 — `userPort.findByIdOrThrow(event.userId())`는 유지, `User`는 root 소유라 문제 없음).

`TradingAlertNotifier`(`onInsufficientBalance`/`onBatchInterrupted`): 동일하게 `accountPort` 호출 제거, `event.accountNickname()`으로 대체. `notifyPort.notifyInsufficientBalance(event.holdings(), event.usdDeposit(), event.ticker())` 호출로 교체(기존 `Account`/`AccountBalance` 조립 코드 삭제).

`TradingReportNotifier`: `accountPort.findByIdOrThrow` 삭제, `event.accountNickname()` 사용. `userNotificationPort.notifyTradingReport(user, event.accountNickname(), event.report())` 호출로 교체. `TradeEventView` 조립부는 `event.executions()`(이미 `List<TradeLegSummary>`)를 그대로 `TradeEventView.buy()/sell()` 팩토리에 매핑하도록 필드 접근자만 교체(`TradeLegSummary`가 이미 `TradeEventView`와 거의 동일 shape — 조립 로직 자체는 무변경, 입력 타입만 `Execution`→`TradeLegSummary`).

`TelegramUserNotificationAdapter`/`FcmAdapter`(둘 다 `UserNotificationPort` 구현체): 메서드 시그니처를 Step4에 맞춰 갱신, 본문에서 `account.nickname()` → 파라미터 `accountNickname` 직접 사용, `strategy.type()`/`.ticker()`/`.cycleSeedType()` → 파라미터로 직접 받은 `strategyType`/`ticker`/`cycleSeedType` 사용, `report.date()` 등은 `TradingReport`가 이제 `com.kista.sharedkernel.TradingReport`이므로 import만 교체.

`TelegramAdapter`(`NotifyPort` 구현체): `notifyInsufficientBalance(int holdings, BigDecimal usdDeposit, StrategyTicker ticker)`로 시그니처 갱신, 본문은 기존 `b.holdings()`/`b.usdDeposit()` 사용 부분을 파라미터로 직접 교체(`account` 파라미터 자체가 없어지므로 삭제할 코드 없음 — 원래도 미사용이었다).

`CompositeUserNotificationAdapter`: 위임만 하므로 메서드 시그니처만 Step4에 맞춰 갱신(본문 로직 무변경).

- [ ] **Step 6: `TelegramBotService` — `PortfolioUseCase` 직접 참조를 내부API로 전환**

```java
package com.kista.notify.application.port.output;

import com.kista.sharedkernel.StrategyTicker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// trading-core stats.PortfolioUseCase를 notify(TelegramBotService)가 직접 참조하지 않도록
// 필요한 조회만 담은 전용 포트
public interface PortfolioQueryPort {
    PortfolioCurrentView getCurrent(UUID userId);
    List<PortfolioOrderView> getHistory(UUID userId, LocalDate from, LocalDate to, StrategyTicker ticker);

    record PortfolioCurrentView(StrategyTicker ticker, int holdings, java.math.BigDecimal avgPrice, java.math.BigDecimal usdDeposit) {}
    record PortfolioOrderView(LocalDate tradeDate, StrategyTicker ticker, com.kista.sharedkernel.OrderDirection direction, int quantity, java.math.BigDecimal price) {}
}
```

(record 필드는 `TelegramBotService`가 실제로 메시지에 사용하는 `CyclePositionHistoryEntry`/`Order` 필드를 원본 클래스 Read 후 정확히 맞춘다 — 위는 조사에서 확인된 최소 후보)

```java
package com.kista.notify.adapter.out.internal;

import com.kista.notify.application.port.output.PortfolioQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class PortfolioQueryHttpAdapter implements PortfolioQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public PortfolioCurrentView getCurrent(UUID userId) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/stats/portfolio/current").queryParam("userId", userId).build())
                .retrieve()
                .body(PortfolioCurrentView.class);
    }

    @Override
    public List<PortfolioOrderView> getHistory(UUID userId, LocalDate from, LocalDate to, com.kista.sharedkernel.StrategyTicker ticker) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/stats/portfolio/history")
                        .queryParam("userId", userId).queryParam("from", from).queryParam("to", to).queryParam("ticker", ticker)
                        .build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<PortfolioOrderView>>() {});
    }
}
```

`trading-core/src/main/java/com/kista/trading/stats/adapter/in/web/PortfolioQueryInternalController.java` 신설 — 위 두 경로를 `PortfolioUseCase.getCurrent()`/`.getHistory()`에 위임하며 응답을 `PortfolioCurrentView`/`PortfolioOrderView`와 byte-identical한 필드명 record로 매핑.

`TelegramBotService.java`: `PortfolioUseCase portfolioUseCase` 필드를 `PortfolioQueryPort portfolioQueryPort`로 교체, `getCurrent(userId)`/`getHistory(...)` 호출부를 새 포트 메서드로 교체(반환 타입이 `CyclePositionHistoryEntry`/`Order`에서 `PortfolioCurrentView`/`PortfolioOrderView`로 바뀌므로 메시지 조립 코드의 필드 접근자도 함께 교체). `import com.kista.trading.stats.application.usecase.PortfolioUseCase;`/`import com.kista.trading.domain.model.Order;`/`import com.kista.trading.domain.model.CyclePositionHistoryEntry;` 삭제.

- [ ] **Step 7: 컴파일·테스트 확인**

```bash
bash gradlew :shared:compileJava :trading-core:compileJava :compileJava
bash gradlew test --tests "com.kista.notify.*" --tests "com.kista.trading.*"
```

Expected: BUILD SUCCESSFUL, PASS. 리스너·어댑터 테스트는 mock 대상 포트/이벤트 타입 변경에 맞춰 갱신(예: `CycleEndedNotifierTest`의 `AccountPort` mock 제거, 이벤트 생성자 인자를 새 shape로 교체).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(notify): 이벤트 페이로드 narrowing + sharedkernel 승격, 포트 시그니처 축소

CycleEndedEvent 등 5개 trading-core 이벤트가 Account/Strategy/
AccountBalance/List<Execution>을 그대로 담고 있어 notify 리스너가
컴파일 의존과 무관하게 재조회(AccountPort.findByIdOrThrow) 4곳을
안고 있었다 — 실측 결과 notify가 쓰는 필드는 Account.nickname 1개,
AccountBalance 2개, Strategy 3개뿐이라 이벤트 자체를 스칼라로
narrowing한 뒤 sharedkernel로 승격(UserDeletedEvent와 동일 선례) —
재조회가 통째로 사라진다. TradingReportReadyEvent.executions만
유일하게 신규 값타입(TradeLegSummary) 필요.

NotifyPort.notifyInsufficientBalance의 Account 파라미터는 구현체에서
완전 미사용(dead)이라 삭제. TelegramBotService의 PortfolioUseCase
직접 참조도 신규 PortfolioQueryPort(내부API)로 전환.

이벤트 shape 변경은 event_publication 미완료 레코드 역직렬화에
영향을 줄 수 있어 constraints.md에 배포 전 확인 절차를 추가했다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

### Task 18: 게이트 강제 — `implementation` → `runtimeOnly` 전환 + 최종 검증

**RULING(Task13 재조사 이후 Step1 방법 교체)**: 원래 Step1은 `grep -rlE`로 leak을 확인하는 방식이었으나, 이 방법이 두 번(원안 브리프, Task13 최초 시도) 모두 스코프 누락으로 실패했다(경로 하드코딩이 admin/web/stats/user 4개만 커버, market/notify가 누락돼 17개 파일을 놓침). Task13 재조사에서 유일하게 완전히 수렴한 방법은 **컴파일러 기반 정밀 인벤토리**(`build.gradle.kts`를 임시로 `runtimeOnly`로 바꾸고 `-Xmaxerrs 10000`을 추가해 `:compileJava`를 실행 — 실패한 import를 컴파일러가 전수 나열)였다. grep을 컴파일러 검증으로 교체한다.

**Files:**
- Modify: `build.gradle.kts`(root)

**Interfaces:**
- Consumes: Task 1~17에서 완료된 모든 own-type/내부API/승격 전환
- Produces: 없음(최종 게이트 태스크)

- [ ] **Step 1: 남은 leak 컴파일러 기준 최종 확인**

```bash
cp build.gradle.kts build.gradle.kts.bak
sed -i 's/implementation(project(":trading-core"))/runtimeOnly(project(":trading-core"))/' build.gradle.kts
bash gradlew :compileJava -Xmaxerrs 10000 2>&1 | tee /tmp/task18-precheck.log
grep -oE "^[^:]+\.java" /tmp/task18-precheck.log | sort -u
```

Expected: 컴파일 실패 목록이 비어있어야 한다(Task 1~17로 전부 해소됨). 파일이 남아있으면 원복(`mv build.gradle.kts.bak build.gradle.kts`) 후 해당 own-type/내부API 태스크로 되돌아가 처리 — grep 스코프 재조정이 아니라 이 컴파일러 방법 자체로 재확인해야 한다(3개 모듈이 이미 이 방식 누락으로 두 번 새어나간 전례가 있다).

- [ ] **Step 1b: 원복 확인**

```bash
mv build.gradle.kts.bak build.gradle.kts
git diff build.gradle.kts
```

Expected: `git diff` 결과 없음(Step1의 임시 전환이 완전히 원복됨) — `-Xmaxerrs` 등 임시 추가분이 실수로 커밋에 섞이지 않도록 이 시점에 확실히 확인.

- [ ] **Step 2: 의존 선언 전환**

`build.gradle.kts`의 `dependencies { }`에서:

```kotlin
// 변경 전: implementation(project(":trading-core"))
// 변경 후:
runtimeOnly(project(":trading-core"))
```

- [ ] **Step 3: 컴파일 확인(게이트 자체)**

```bash
bash gradlew :compileJava
```

Expected: BUILD SUCCESSFUL. 실패하면 Step 1이 놓친 leak이 있다는 뜻 — 에러 메시지의 파일을 해당 own-type 태스크로 되돌려 처리.

- [ ] **Step 4: bootJar 번들 확인(런타임 무변경 검증)**

```bash
bash gradlew :bootJar
unzip -l build/libs/app.jar | grep "com/kista/trading/" | head -5
unzip -l build/libs/app.jar | grep "com/kista/matching/" | head -5
```

Expected: 둘 다 클래스 파일 목록이 출력됨(trading-core 클래스가 여전히 jar에 번들되어 있음 확인).

- [ ] **Step 5: `GradleModuleBoundaryTest` 갱신**

`:api → :trading-core` 검증 로직이 `implementation` 구성만 검사하던 경우 `runtimeOnly`도 함께 검사 대상에서 "허용된 런타임 전용 의존"으로 명시 구분하도록 갱신(컴파일 클래스패스 기준 검증 로직은 그대로 두되, 검사 대상 configuration이 `compileClasspath`인지 재확인).

- [ ] **Step 6: 전체 테스트**

```bash
bash gradlew test
```

Expected: BUILD SUCCESSFUL, `ApplicationModules.verify()` GREEN 포함.

- [ ] **Step 7: 배포 형태 무변경 스모크(로컬)**

```bash
docker compose up -d postgres
bash gradlew bootRun --args='--spring.profiles.active=local' &
sleep 15
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/meta
curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken
```

Expected: `/api/meta` 200, admin 토큰 정상 발급(root+trading-core 양쪽 빈이 한 컨텍스트에서 정상 기동했다는 증거).

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts src/test/java/com/kista/architecture
git commit -m "$(cat <<'EOF'
refactor(build): :api -> :trading-core 컴파일 의존을 runtimeOnly로 전환

Task 1~17의 own-type/내부API/:shared 승격 전환 완료로
root(admin/web/stats/user/market/notify)의 trading-core 직접 import가
0건이 됐다 — implementation을 runtimeOnly로 바꿔 컴파일 클래스패스에서
완전히 제외(향후 회귀는 컴파일 실패로 즉시 드러남). bootJar 번들·
컴포넌트 스캔은 runtime 클래스패스 기준이라 무변경 — 단일 app.jar
배포, 내부 API 루프백 호출 방식 그대로 유지.

Step1 grep 스코프가 두 번(원안 브리프, Task13 최초 시도) 모두 market/
notify 누락으로 실패한 전례가 있어, 최종 검증은 컴파일러 기반
정밀 인벤토리(임시 runtimeOnly + -Xmaxerrs 10000 + :compileJava)로
교체했다.

3단계(3a+3b) 완료 게이트: `:api` 컴파일 의존 0, ./gradlew test 전체
그린, ApplicationModules.verify() GREEN.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
EOF
)"
```

---

## 실행 순서 요약

Task 1(:shared) → Task 2~11(own-type/포트정리, 서로 다른 파일이라 병렬 가능하나 Task 12는 Task 1 이후 아무 때나·Task 18 이전에 반드시 완료) → Task 12(user_notify_profile) → Task 13(버킷B :shared 2차 승격, Task 1 이후 아무 때나) → Task 14~16(admin/stats/market 내부API, 서로 다른 파일이라 병렬 가능) → Task 17(notify 이벤트 narrowing — Task13의 `:shared` ReturnMetrics 승격이 선행조건) → Task 18(게이트 전환, 반드시 마지막).

## 후속(이 계획 범위 밖)

- 4단계(DB 분리) 시점: `user_notify_profile` 동기화를 Redis Stream으로 교체, `notify` 모듈 분리 방식 결정, 전략 CRUD/수동실행/주문취소/VR재설정을 실제 프로세스 분리에 맞춰 내부 API로 전환(현재는 trading-core가 최종 사용자에게 같은 프로세스에서 직접 서빙 중이라 3단계 스코프 아님).
