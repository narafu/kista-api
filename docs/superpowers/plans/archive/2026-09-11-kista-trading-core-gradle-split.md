# kista-trading Gradle 분리 (1단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 단일 Gradle 프로젝트인 kista-api를 `:trading-core`(매매 실행 도메인)/`:api`(그 외) 두 서브프로젝트로 나눈다. 배포는 여전히 `:api`의 `bootJar` 하나 — 런타임·배포 형태 무변경, 컴파일 경계만 생긴다.

**Architecture:** `sharedkernel`, `platform`, `privacy`, `matching`, `broker`, `account`, `trading`, 신설 `marketcalendar` 8개 패키지를 물리적으로 `:trading-core`로 옮긴다. `:api`가 `implementation project(":trading-core")`로 단방향 의존한다. `stats`/`admin`/`notify`/`user`는 지금은 옮기지 않는다 — 이들은 `trading`의 공개 NamedInterface(`port`/`usecase`/`event`)만 소비하므로 `:api → :trading-core` 의존만으로 지금처럼 컴파일된다. 물리 이동은 2·3단계(읽기/쓰기 결합을 내부 API로 끊을 때)로 미룬다.

**Tech Stack:** Java 21, Spring Boot 4, Gradle Kotlin DSL, Spring Modulith, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-11-kista-trading-service-split-design.md`

## Global Constraints

- 매 태스크 종료 시 `./gradlew test` 전체 그린 (별도 지시 없는 한).
- 패키지 이름(`com.kista.trading` 등)은 유지한다 — 파일 물리 위치만 `src/main/java/...` → `trading-core/src/main/java/...`로 옮긴다. Java는 소스 루트 경로와 패키지명이 일치해야 하므로 디렉터리 이동 시 `com/kista/trading/...` 하위 구조는 그대로 복사한다.
- DB 마이그레이션(`db/migration`)·`application*.yml`은 이 단계에서 옮기지 않는다 — `:api`가 유일한 배포 아티팩트이므로 리소스는 `:api`에 남는다.
- 소유권 표는 스펙(`2026-09-11-...-design.md`)의 표가 SSOT. 이 계획은 "최종 소유"가 아니라 "1단계에서 실제로 옮기는 것"만 다룬다.

---

### Task 1: `TradingUserProfile` 포트 역전 — trading→user 결합 절단

**Files:**
- Create: `src/main/java/com/kista/trading/domain/model/TradingUserProfile.java`
- Create: `src/main/java/com/kista/trading/application/port/output/TradingUserProfilePort.java`
- Create: `src/main/java/com/kista/web/trading/TradingUserProfileAdapter.java` (package-private) — **주의: 초안은 `com.kista.user.application.service`를 지목했으나 실측(`ApplicationModules.verify()`)으로 기각됨.** `trading`이 이미 `ActiveStrategyCountAdapter`로 `user→trading` 역전을 갖고 있어, `user`가 `TradingUserProfilePort`를 구현하면 양방향 순환이 되어 Spring Modulith가 무조건 거부한다(`Cycle detected: Slice trading -> Slice user -> Slice trading`, allowlist 없음). `com.kista.web`은 NamedInterface 0개의 순수 싱크 모듈로 이미 trading·user 양쪽에 의존하므로 유일하게 순환 없이 이 어댑터를 호스팅할 수 있다.
- Modify: `src/main/java/com/kista/trading/domain/model/BatchContext.java` — `User user` → `TradingUserProfile userProfile`
- Modify: `src/main/java/com/kista/trading/application/usecase/TradingExecutionUseCase.java`, `TradingExecutionFacade.java`, `TradingService.java` — `User` 파라미터 타입 교체
- Modify: `src/main/java/com/kista/trading/application/service/MarketEventNotifier.java`, `ManualTradingService.java`, `CycleRotationService.java`, `StrategyCreationService.java`, `TradingReporter.java`, `VrReconfigureService.java` — `UserPort`/`UserSettingsPort`/`User`/`UserSettings` 참조를 `TradingUserProfilePort`/`TradingUserProfile`로 교체
- Modify: `src/main/java/com/kista/trading/adapter/in/schedule/BatchContextFactory.java` — `UserPort.findAll()` → `TradingUserProfilePort.findAll()` (또는 동등 배치 조회)
- Test: 각 수정 클래스의 기존 `*Test.java`(신규 테스트 파일 없음 — 기존 mock 타입을 `TradingUserProfile`로 교체)

**Interfaces:**
- Produces (실제 반영값 — 초안 대비 정정): `TradingUserProfile(UUID userId, Map<NotificationType, Boolean> notificationPrefs, boolean balanceCheckEnabled)` — record(텔레그램 필드는 아무 소비자도 없어 최종 리뷰에서 제거됨, `notificationPrefs`는 `UserSettings.notificationPrefs()`와 동일 shape인 `Map<NotificationType, Boolean>`이 맞다 — `NotificationChannel`이 아니다). `TradingUserProfilePort`: `Optional<TradingUserProfile> findByUserId(UUID)`, `Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID>)`(배치 조회, `BatchContextFactory`의 N+1 방지 패턴 유지) + `List<TradingUserProfile> findAllActive()`(구현 중 `MarketEventNotifier`가 필요로 해 추가됨).
- Consumes: 없음(이 태스크가 경계의 출발점).

이건 "실패하는 테스트 → 구현" TDD가 아니라 **타입 치환 리팩토링**이다 — 순서를 지켜라: 포트/타입 정의 → user 쪽 구현 → trading 쪽 소비자 전환 → 컴파일 에러 0.

- [ ] **Step 1: `TradingUserProfile` record와 `TradingUserProfilePort` 인터페이스를 작성한다**

```java
// src/main/java/com/kista/trading/domain/model/TradingUserProfile.java
package com.kista.trading.domain.model;

import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.NotificationType;

import java.util.Map;
import java.util.UUID;

// trading이 필요로 하는 사용자 알림·잔고검증 설정만 담은 투영 — user.domain.model.User/UserSettings 전체를 참조하지 않기 위한 포트 역전
public record TradingUserProfile(
        UUID userId,
        String telegramBotToken,
        String telegramChatId,
        Map<NotificationType, NotificationChannel> notificationPrefs,
        boolean balanceCheckEnabled
) {
}
```
(초안 코드 — 최종 리뷰에서 정정됨: 텔레그램 필드 2개는 소비자가 없어 삭제, `notificationPrefs`는 `Map<NotificationType, Boolean>`이 맞다(`NotificationChannel` 아님) — 실제 시그니처는 위 Interfaces 섹션 참고)

```java
// src/main/java/com/kista/trading/application/port/output/TradingUserProfilePort.java
package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.TradingUserProfile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TradingUserProfilePort {
    Optional<TradingUserProfile> findByUserId(UUID userId);
    Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID> userIds);
}
```
(초안 코드 — 실제로는 `findAllActive()`가 3번째 메서드로 추가됨, 위 Interfaces 섹션 참고)

주의: `NotificationChannel`은 architecture.md 기준 `com.kista.user.domain.model` 소유 독립 파일이다(sharedkernel 미포함). import를 `com.kista.user.domain.model.NotificationChannel`로 정정하라 — 이 record가 user 타입 하나(enum)를 참조하는 건 값 타입 재사용이라 문제없다(포트 역전은 서비스/애그리게이트 참조를 끊는 것이지 sharedkernel급 enum까지 금지하는 게 아니다). `NotificationType`은 실제로 sharedkernel 소속이 맞는지 `grep -n "enum NotificationType" -r src/main/java`로 확인 후 import 경로를 확정한다. (실측 결과 이 문단 자체가 기각됨: `notificationPrefs`는 `NotificationChannel`이 아니라 `UserSettings.notificationPrefs()`와 동일한 `Map<NotificationType, Boolean>`으로 최종 구현됨 — 텔레그램 필드는 전부 삭제)

- [ ] **Step 2: `com.kista.web`에 `TradingUserProfilePort` 구현체를 작성한다 (user 모듈이 아님 — 위 Files 섹션의 정정 사유 참고)**

```java
// src/main/java/com/kista/web/trading/TradingUserProfileAdapter.java
package com.kista.web.trading;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
class TradingUserProfileAdapter implements TradingUserProfilePort {

    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort;

    @Override
    public Optional<TradingUserProfile> findByUserId(UUID userId) {
        return userPort.findById(userId).map(this::toProfile);
    }

    @Override
    public Map<UUID, TradingUserProfile> findAllByUserIds(List<UUID> userIds) {
        return userPort.findAllByIds(userIds).stream()
                .collect(Collectors.toMap(User::id, this::toProfile));
    }

    private TradingUserProfile toProfile(User user) {
        UserSettings settings = userSettingsPort.findByUserId(user.id())
                .orElseGet(() -> UserSettings.defaultFor(user.id()));
        return new TradingUserProfile(
                user.id(), user.telegramBotToken(), user.telegramChatId(),
                settings.notificationPrefs(), settings.balanceCheckEnabled());
    }
}
```

`UserPort.findAllByIds(List<UUID>)`가 없으면 `findAll()`을 쓰되 `BatchContextFactory`가 넘기는 id 목록으로 필터링한다 — 실제 `UserPort` 인터페이스를 `Read` 도구로 먼저 확인하고 있는 메서드에 맞춰라(이 스텝의 시그니처는 존재 여부 확인 전 초안이다).

- [ ] **Step 3: `./gradlew compileJava`로 이 시점까지 컴파일되는지 확인한다**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL (trading은 아직 옛 `User` 타입을 쓰고 있어 컴파일은 되지만 아직 아무것도 안 바뀐 상태)

- [ ] **Step 4: trading 쪽 소비자를 `TradingUserProfilePort`로 하나씩 전환한다 — `BatchContext`부터**

`BatchContext.java`의 `User user` 필드를 `TradingUserProfile userProfile`로 바꾸고, `BatchContextFactory.buildAll()`의 `userPort.findAll()` 호출을 `tradingUserProfilePort.findAllByUserIds(...)`로 교체한다(대상 id는 accounts에서 얻은 `userId` 목록).

- [ ] **Step 5: 나머지 8개 파일(`TradingExecutionUseCase`, `TradingExecutionFacade`, `TradingService`, `MarketEventNotifier`, `ManualTradingService`, `CycleRotationService`, `StrategyCreationService`, `TradingReporter`, `VrReconfigureService`)을 같은 패턴으로 전환한다**

각 파일에서 `import com.kista.user.*` 삭제 → `TradingUserProfile`/`TradingUserProfilePort` import로 교체. `UserSettings.balanceCheckEnabled()` 호출은 `TradingUserProfile.balanceCheckEnabled()`로. (초안은 텔레그램 발송용 `user.telegramBotToken()`도 `userProfile.telegramBotToken()`으로 옮기는 걸 전제했으나, 실제로는 그 필드를 읽는 소비자가 없어 최종 리뷰에서 `TradingUserProfile`에서 텔레그램 필드 자체가 삭제됨)

- [ ] **Step 6: `grep -rn "import com.kista.user\." src/main/java/com/kista/trading`로 잔여 참조가 0인지 확인한다**

Run: `grep -rn "import com.kista.user\." src/main/java/com/kista/trading`
Expected: 빈 출력 (단, `ActiveStrategyCountAdapter`가 구현하는 `com.kista.user.application.port.output.ActiveStrategyCountPort`는 예외 — 이건 trading이 user의 포트를 구현하는 올바른 방향이라 남아 있어야 한다. `UserCascadeListener`/`StrategyUserCascadeListener`의 `UserDeletedEvent` import도 예외 — 이벤트 구독이라 컴파일 의존이 아니다. 이 둘 외에 남은 게 있으면 놓친 것)

- [ ] **Step 7: 전체 테스트 실행**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL. 실패하는 테스트는 옛 `User`/`UserSettings` mock을 쓰던 테스트들 — `TradingUserProfile` mock으로 교체.

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/kista/trading src/main/java/com/kista/user src/test/java/com/kista/trading
git commit -m "refactor(trading): TradingUserProfile 포트 역전 — trading→user 컴파일 의존 절단"
```

---

### Task 2: `market` 캘린더/공포탐욕지수 패키지 분리

**Files:**
- Create: `src/main/java/com/kista/marketcalendar/` (신규 최상위 패키지 — `domain/model`, `application/port/output`, `adapter/in/schedule`, `adapter/out/alpaca`, `adapter/out/persistence`)
- Move into it: `MarketSessionSnapshot`(domain), `MarketCalendarPort`/`MarketCalendarRefreshPort`/`MarketHolidayStorePort`(port), `MarketCalendarRefreshScheduler`(schedule), `AlpacaCalendarAdapter`/`AlpacaConfig`/`AlpacaProperties`(alpaca — 단, 빈 이름 `marketAlpacaConfig`/`marketAlpacaRestClient` 충돌 방지 개명은 이제 불필요해질 수 있음, stats의 `AlpacaIndexPriceAdapter`와 같은 패키지가 아니게 되므로 — 이동 후 빈 이름 충돌 재확인), `UsMarketHolidayEntity`/`UsMarketHolidayJpaRepository`/`MarketCalendarPersistenceAdapter`(persistence)
- Modify: `src/main/java/com/kista/market/` — `FearGreedRating`/`FearGreedSnapshot`(domain), `CnnFearGreedPort`/`CryptoFearGreedPort`/`FearGreedSnapshotPort`(port), `FearGreedFetchFailedEvent`(event), `FearGreedController`(web), `FearGreedScheduler`(schedule), `CnnFearGreedAdapter`/`CryptoFearGreedAdapter`/`FearGreedConfig`(feargreed), `FearGreedSnapshotEntity`+어댑터만 잔류
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java:13`, `VrCycleRolloverService.java:7` — import 경로를 `com.kista.market.application.port.output.MarketCalendarPort` → `com.kista.marketcalendar.application.port.output.MarketCalendarPort`로 변경
- Modify: `src/main/java/com/kista/market/adapter/in/web/MarketHolidayController.java`(있다면) — `MarketUseCase`가 캘린더/캔들 조회를 함께 다루는데, 캔들 조회는 어느 쪽인지 architecture.md `MarketUseCase` 설명 재확인 후 배치. 애매하면 캘린더 조회 전용 메서드만 `marketcalendar`로 옮기고 나머지는 `market`에 둔다.
- Test: `src/test/java/com/kista/market/**`, `src/test/java/com/kista/architecture/ModulithArchitectureTest.java`

**Interfaces:**
- Produces: `com.kista.marketcalendar` 새 `@ApplicationModule`(package-info.java 신설), NamedInterface `"port"`(MarketCalendarPort 등)를 공개해 `trading`이 소비.
- Consumes: 기존 `market`이 갖던 `sharedkernel`/`platform` 의존 그대로.

- [ ] **Step 1: `com.kista.marketcalendar` 패키지 디렉터리를 만들고 위 클래스들을 `git mv`로 이동한다**

```bash
mkdir -p src/main/java/com/kista/marketcalendar/{domain/model,application/port/output,adapter/in/schedule,adapter/out/alpaca,adapter/out/persistence}
git mv src/main/java/com/kista/market/domain/model/MarketSessionSnapshot.java src/main/java/com/kista/marketcalendar/domain/model/
git mv src/main/java/com/kista/market/application/port/output/MarketCalendarPort.java src/main/java/com/kista/marketcalendar/application/port/output/
git mv src/main/java/com/kista/market/application/port/output/MarketCalendarRefreshPort.java src/main/java/com/kista/marketcalendar/application/port/output/
git mv src/main/java/com/kista/market/application/port/output/MarketHolidayStorePort.java src/main/java/com/kista/marketcalendar/application/port/output/
git mv src/main/java/com/kista/market/adapter/in/schedule/MarketCalendarRefreshScheduler.java src/main/java/com/kista/marketcalendar/adapter/in/schedule/
git mv src/main/java/com/kista/market/adapter/out/alpaca/AlpacaCalendarAdapter.java src/main/java/com/kista/marketcalendar/adapter/out/alpaca/
git mv src/main/java/com/kista/market/adapter/out/alpaca/AlpacaConfig.java src/main/java/com/kista/marketcalendar/adapter/out/alpaca/
git mv src/main/java/com/kista/market/adapter/out/alpaca/AlpacaProperties.java src/main/java/com/kista/marketcalendar/adapter/out/alpaca/
git mv src/main/java/com/kista/market/adapter/out/persistence/calendar/UsMarketHolidayEntity.java src/main/java/com/kista/marketcalendar/adapter/out/persistence/
git mv src/main/java/com/kista/market/adapter/out/persistence/calendar/UsMarketHolidayJpaRepository.java src/main/java/com/kista/marketcalendar/adapter/out/persistence/
git mv src/main/java/com/kista/market/adapter/out/persistence/calendar/MarketCalendarPersistenceAdapter.java src/main/java/com/kista/marketcalendar/adapter/out/persistence/
```

각 파일의 `package com.kista.market...;` 선언을 `package com.kista.marketcalendar...;`로 Edit 도구로 일괄 수정한다(파일 수가 적어 하나씩).

- [ ] **Step 2: `com.kista.marketcalendar` 각 서브패키지에 `package-info.java`를 작성한다**

```java
// src/main/java/com/kista/marketcalendar/application/port/output/package-info.java
@org.springframework.modulith.NamedInterface("port")
package com.kista.marketcalendar.application.port.output;
```

`domain/model`도 동일 패턴(`NamedInterface("domain")`). 루트 `com/kista/marketcalendar/package-info.java`에 `@ApplicationModule`.

- [ ] **Step 3: `trading`의 import 경로 2곳을 수정한다**

`TradingService.java:13`, `VrCycleRolloverService.java:7`의 `import com.kista.market.application.port.output.MarketCalendarPort;`를 `import com.kista.marketcalendar.application.port.output.MarketCalendarPort;`로 Edit.

- [ ] **Step 4: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL. 실패하면 `AlpacaConfig`/`AlpacaRestClient` 빈 이름 충돌(stats의 동명 클래스와) 여부부터 확인 — architecture.md에 기록된 기존 충돌 회피 개명(`marketAlpacaConfig`)이 지금도 필요한지 재확인.

- [ ] **Step 5: `ModulithArchitectureTest`(`ApplicationModules.verify()`) 실행**

Run: `bash gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest'`
Expected: PASS — 새 모듈이 구조적으로 올바르게 인식됨.

- [ ] **Step 6: 전체 테스트**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/marketcalendar src/main/java/com/kista/market src/main/java/com/kista/trading
git commit -m "refactor(market): 캘린더를 com.kista.marketcalendar로 분리 — trading의 유일한 market 의존을 좁힌다"
```

---

### Task 3: `:trading-core` Gradle 서브프로젝트 골격

**Files:**
- Modify: `settings.gradle.kts`
- Create: `trading-core/build.gradle.kts`
- Modify: `build.gradle.kts` (루트, `:api`가 됨 — 실제로는 루트 프로젝트 이름은 유지하고 `:trading-core`만 서브프로젝트로 추가하는 편이 마이그레이션이 작다. 아래 설계는 "루트=:api 역할, 신규 서브프로젝트=:trading-core" 방식)

빈 서브프로젝트를 먼저 만들어 빌드가 깨지지 않는지 확인한 뒤 Task 4~7에서 소스를 채운다.

- [ ] **Step 1: `settings.gradle.kts`에 서브프로젝트 선언 추가**

```kotlin
rootProject.name = "kista-api"

include("trading-core")

// gradle/libs.versions.toml 은 Gradle 7.4+ 에서 자동으로 'libs' 카탈로그로 감지됩니다
```

- [ ] **Step 2: `trading-core/build.gradle.kts` 작성**

```kotlin
plugins {
    java
    alias(libs.plugins.spring.dependency.mgmt)
}

group = "com.kista"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    runtimeOnly(libs.postgresql)

    implementation("org.springframework.boot:spring-boot-starter-data-redis") // broker/TossRedisTokenStore

    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.events.api)

    implementation("org.apache.httpcomponents.client5:httpclient5") // broker KIS/Toss HTTP 클라이언트

    // 웹 레이어(Task 5에서 실측 발견 — privacy.adapter.in.web.FidaOrderController가 필요로 함, Task 6/7의 account/trading 컨트롤러도 동일하게 필요)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.springdoc.openapi.webmvc.ui)
    // Task 6에서 실측 발견 — account.AccountController가 @AuthenticationPrincipal(spring-security-core) 사용
    implementation(libs.spring.boot.starter.security)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
    systemProperty("user.timezone", "Asia/Seoul")
}
```

**정정(Task 4 실행 중 실측으로 뒤집힘 — 수동 BOM import는 Spring Boot 플러그인이 해주는 JUnit Platform 버전 정렬을 대체하지 못해 `NoSuchMethodError`가 남):** `alias(libs.plugins.spring.boot)`는 그대로 적용한다(의존성 버전관리·JUnit 정렬용, root와 동일). 대신 `bootJar` 산출을 막기 위해 아래 태스크 설정을 추가한다:
```kotlin
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
```
"`:trading-core`는 bootJar를 만들지 않는 라이브러리 서브프로젝트다"라는 목적 자체는 유지 — 달성 수단만 "플러그인 미적용"에서 "플러그인 적용 + bootJar 태스크 비활성화"로 바뀐다. 이게 Gradle 멀티모듈 Spring Boot 프로젝트의 표준 관용구.

- [ ] **Step 3: 루트 `build.gradle.kts`에 `:trading-core` 의존 추가**

```kotlin
dependencies {
    implementation(project(":trading-core"))
    // ... 기존 의존성 그대로
}
```

- [ ] **Step 4: 빈 상태로 빌드 확인**

Run: `bash gradlew build`
Expected: BUILD SUCCESSFUL (아직 아무 소스도 안 옮겼으므로 사실상 무변화 검증).

- [ ] **Step 5: 커밋**

```bash
git add settings.gradle.kts build.gradle.kts trading-core/build.gradle.kts
git commit -m "build: :trading-core 빈 서브프로젝트 골격 추가"
```

---

### Task 4: `sharedkernel` + `platform` 이동

**Files:**
- Move: `src/main/java/com/kista/sharedkernel/**` → `trading-core/src/main/java/com/kista/sharedkernel/**`
- Move: `src/main/java/com/kista/platform/**` → `trading-core/src/main/java/com/kista/platform/**`
- Test: 대응하는 `src/test/java/com/kista/{sharedkernel,platform}/**`도 함께 이동

두 모듈은 outbound-zero(다른 `com.kista.*` 패키지를 참조하지 않음, `HexagonalArchitectureTest`가 강제)라 가장 안전하게 먼저 옮긴다.

- [ ] **Step 1: 디렉터리 이동**

```bash
mkdir -p trading-core/src/main/java/com/kista trading-core/src/test/java/com/kista
git mv src/main/java/com/kista/sharedkernel trading-core/src/main/java/com/kista/sharedkernel
git mv src/main/java/com/kista/platform trading-core/src/main/java/com/kista/platform
git mv src/test/java/com/kista/sharedkernel trading-core/src/test/java/com/kista/sharedkernel 2>/dev/null || true
git mv src/test/java/com/kista/platform trading-core/src/test/java/com/kista/platform 2>/dev/null || true
```

(`sharedkernel`/`platform`에 전용 테스트 디렉터리가 없으면 마지막 두 명령은 무시 — `|| true`로 실패 허용)

- [ ] **Step 2: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL — 패키지명 불변이라 나머지 코드는 `implementation(project(":trading-core"))` 경유로 그대로 참조된다.

- [ ] **Step 3: 전체 테스트**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 커밋**

```bash
git add -A
git commit -m "refactor(build): sharedkernel·platform을 trading-core로 이동"
```

---

### Task 5: `privacy` + `matching` 이동

**Files:**
- Move: `src/main/java/com/kista/privacy/**` → `trading-core/src/main/java/com/kista/privacy/**`
- Move: `src/main/java/com/kista/matching/**` → `trading-core/src/main/java/com/kista/matching/**`
- Test: 대응 `src/test/java/com/kista/{privacy,matching}/**`

`matching`은 `privacy`에 의존하므로(architecture.md) 같은 태스크로 묶어 순서 문제를 없앤다.

- [ ] **Step 1: 디렉터리 이동**

```bash
git mv src/main/java/com/kista/privacy trading-core/src/main/java/com/kista/privacy
git mv src/main/java/com/kista/matching trading-core/src/main/java/com/kista/matching
git mv src/test/java/com/kista/privacy trading-core/src/test/java/com/kista/privacy
git mv src/test/java/com/kista/matching trading-core/src/test/java/com/kista/matching
```

- [ ] **Step 2: 컴파일 + 전체 테스트**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add -A
git commit -m "refactor(build): privacy·matching을 trading-core로 이동"
```

---

### Task 6: `broker` + `account` 이동

**Files:**
- Move: `src/main/java/com/kista/broker/**` → `trading-core/src/main/java/com/kista/broker/**`
- Move: `src/main/java/com/kista/account/**` → `trading-core/src/main/java/com/kista/account/**`
- Test: 대응 `src/test/java/com/kista/{broker,account}/**`

`account`가 `broker`(`BrokerConnectionTesters`)에 의존하므로 같은 태스크로 묶는다.

**정정(Task 6 실행 중 실측으로 발견):** `src/test/java/com/kista/support/`(DataJpaTestBase/DomainFixtures/WebMvcTestSupport)는 루트에 남는 테스트 91개와 trading-core로 옮겨지는 테스트 양쪽 다 참조하는 진짜 공유 인프라라 어느 한쪽 git mv 대상에도 넣을 수 없다. 해결: 루트에 `java-test-fixtures` 플러그인 적용, 이 3파일을 `src/test/java/...` → `src/testFixtures/java/...`로 이동(패키지명 불변), `trading-core/build.gradle.kts`에 `testImplementation(testFixtures(project(":")))` 추가 — 양쪽 다 동일 소스 공유. Task 7(trading 테스트 이동)도 이 설정을 그대로 재사용한다(추가 조치 불필요).

- [ ] **Step 1: 디렉터리 이동**

```bash
git mv src/main/java/com/kista/broker trading-core/src/main/java/com/kista/broker
git mv src/main/java/com/kista/account trading-core/src/main/java/com/kista/account
git mv src/test/java/com/kista/broker trading-core/src/test/java/com/kista/broker
git mv src/test/java/com/kista/account trading-core/src/test/java/com/kista/account
```

- [ ] **Step 2: `account`의 `BrokerEnabledPort` 구현체(admin `RuntimeSettingsService`) 확인**

`account.application.port.output.BrokerEnabledPort`는 admin이 구현한다(포트 역전, admin은 `:api`에 남음). `account`가 `:trading-core`로 이동해도 인터페이스만 참조하므로 컴파일엔 문제없다 — 런타임에 `:api`가 `:trading-core`를 포함하는 하나의 Spring 컨텍스트이므로 빈 주입도 그대로 동작한다. 이 스텝은 실제 코드 변경 없음, 회귀 확인용 메모.

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL. 실패 시 `account`가 `admin`/`broker` 외 다른 `:api`측 패키지를 참조하는지 `grep -rn "import com.kista\.\(admin\|user\|stats\|finance\|notify\|market\|web\)\." src/main/java/com/kista/account trading-core/src/main/java/com/kista/account`로 확인(태스크 1처럼 놓친 결합이 있을 수 있음).

- [ ] **Step 4: 전체 테스트**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor(build): broker·account를 trading-core로 이동"
```

---

### Task 7: `marketcalendar` + `trading` 이동

**Files:**
- Move: `src/main/java/com/kista/marketcalendar/**` → `trading-core/src/main/java/com/kista/marketcalendar/**`
- Move: `src/main/java/com/kista/trading/**` → `trading-core/src/main/java/com/kista/trading/**`
- Test: 대응 `src/test/java/com/kista/{marketcalendar,trading}/**`

가장 큰 덩어리이자 마지막 — 앞선 6개 태스크가 전부 끝나야 `trading`의 의존(account/broker/matching/platform/privacy/sharedkernel/marketcalendar)이 전부 `:trading-core` 안에 있게 된다.

**정정(Task 7 실행 중 실측으로 발견 — Task 1이 남긴 구멍):** Task 1은 `trading→user` 결합을 전부 끊지 않고 3개 파일(`ActiveStrategyCountAdapter`, `UserCascadeListener`, `StrategyUserCascadeListener`)을 "허용된 예외"로 남겼다 — 당시엔 단일 Gradle 프로젝트라 Modulith 단방향 의존으로 문제없었다. 이제 `trading`이 실제로 `:trading-core`로 물리 이동하면 root(`:api`)가 이미 `:trading-core`에 의존하는 상태에서 trading-core가 root 소유 `user`를 참조하면 진짜 Gradle 순환이 된다. 해결: (1) `UserDeletedEvent`(순수 `record(UUID)`)를 `com.kista.sharedkernel`로 승격, 발행처(`UserCascadeDeleter`)·구독처(finance/notify/trading 전부) import 경로 갱신. (2) `ActiveStrategyCountAdapter`를 Task 1과 동일 패턴으로 `com.kista.web.trading`으로 이동(`ActiveStrategyCountPort` 인터페이스는 user 소유 유지).

- [ ] **Step 1: 디렉터리 이동**

```bash
git mv src/main/java/com/kista/marketcalendar trading-core/src/main/java/com/kista/marketcalendar
git mv src/main/java/com/kista/trading trading-core/src/main/java/com/kista/trading
git mv src/test/java/com/kista/marketcalendar trading-core/src/test/java/com/kista/marketcalendar
git mv src/test/java/com/kista/trading trading-core/src/test/java/com/kista/trading
```

- [ ] **Step 2: 컴파일 확인**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL. 실패 시 `grep -rn "import com.kista\.\(admin\|user\|stats\|finance\|notify\|web\)\." trading-core/src/main/java/com/kista/trading`로 Task 1에서 놓친 `user` 참조나 새로 드러난 `notify`/`admin` 참조를 찾는다 — architecture.md 기준 `trading`이 `notify`를 참조하는 곳은 없어야 한다(이벤트만 발행, import 없음).

- [ ] **Step 3: `HexagonalArchitectureTest`/`ModulithArchitectureTest` 실행**

Run: `bash gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS. 이 두 테스트 클래스는 `:api`(루트) 테스트 소스셋에 남아 있고, `:trading-core`가 `implementation` 의존이라 `:api`의 테스트 클래스패스에 `:trading-core`의 `main` 소스가 포함돼 `ApplicationModules.verify()`가 두 서브프로젝트를 합쳐서 스캔한다. 만약 `:trading-core`의 클래스가 스캔 대상에서 빠진 것처럼 보이면(모듈 수가 줄어든 걸로 나오면) `ApplicationModules.of(KistaApplication.class)` 호출부가 실제로 전체 클래스패스를 보는지 — 조용히 좁아진 검증이 아닌지 — 로그로 확인한다.

- [ ] **Step 4: 전체 테스트**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor(build): marketcalendar·trading을 trading-core로 이동"
```

---

### Task 8: 의존 방향 고정 + 문서 갱신 + 최종 검증

**Files:**
- Create: `src/test/java/com/kista/architecture/GradleModuleBoundaryTest.java`
- Modify: `docs/agents/architecture.md` — Gradle 서브프로젝트 구조(`:trading-core`/`:api`) 한 문단 추가
- Modify: `docs/agents/commands.md` — `:trading-core`만 테스트하는 명령(`./gradlew :trading-core:test`) 추가
- Modify: `README.md` — 아키텍처 다이어그램에 Gradle 멀티프로젝트 반영 (CLAUDE.md의 README 드리프트 규칙)

**Interfaces:** 없음 — 검증 전용 태스크.

- [ ] **Step 1: `:trading-core`가 `:api` 전용 패키지를 참조하지 않는지 고정하는 ArchUnit 규칙 작성**

```java
// src/test/java/com/kista/architecture/GradleModuleBoundaryTest.java
package com.kista.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class GradleModuleBoundaryTest {

    // trading-core 서브프로젝트 소스만 스캔해, api 전용 모듈(user/admin/stats/finance/notify/market/web)을
    // 컴파일 타임에 참조하지 않는지 고정한다. 이 테스트가 실패하면 :api → :trading-core 단방향이 깨진 것.
    @Test
    void tradingCoreMustNotDependOnApiOnlyModules() {
        var importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of("trading-core/build/classes/java/main"));

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("com.kista..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kista.user..", "com.kista.admin..", "com.kista.stats..",
                        "com.kista.finance..", "com.kista.notify..", "com.kista.market..",
                        "com.kista.web..")
                .check(importedClasses);
    }
}
```

`importPath`가 `trading-core/build/classes/java/main`을 가리키므로 이 테스트를 돌리기 전 `bash gradlew :trading-core:compileJava`가 먼저 실행돼 있어야 한다 — `test` 태스크 의존성에 `:trading-core:compileJava`가 포함되는지 확인(보통 Gradle이 자동으로 처리한다).

- [ ] **Step 2: 신규 테스트 실행**

Run: `bash gradlew :trading-core:compileJava test --tests 'com.kista.architecture.GradleModuleBoundaryTest'`
Expected: PASS. 실패하면 Task 1~7에서 놓친 역방향 참조가 남은 것 — 실패 메시지의 구체 클래스:라인으로 되짚어 고친다.

- [ ] **Step 3: 문서 갱신**

`docs/agents/architecture.md` 맨 위 패키지 트리 설명 앞에 한 문단 추가:

```markdown
## Gradle 구조

`:trading-core`(매매 실행 도메인 — trading/matching/broker/account/privacy/marketcalendar/sharedkernel/platform)와 `:api`(그 외 전부, bootJar 산출) 두 서브프로젝트. `:api`가 `:trading-core`를 단방향 `implementation` 의존한다. 배포는 `:api`의 `app.jar` 하나 — 이 분리는 컴파일 경계일 뿐 런타임 분리가 아니다(런타임 프로세스 분리는 `kista-api`/`kista-scheduler` 2-role이 이미 별도로 담당).
```

`docs/agents/commands.md`의 Gradle 섹션에 추가:

```
./gradlew :trading-core:test                                    # trading-core 서브프로젝트만 테스트
```

`README.md`의 아키텍처 다이어그램·기술 스택 설명에 Gradle 멀티프로젝트 구조 반영(기존 다이어그램 형식 유지, 패키지 배치만 갱신).

- [ ] **Step 4: 전체 빌드 + 전체 테스트 최종 확인**

Run: `bash gradlew clean build`
Expected: BUILD SUCCESSFUL, `:api:bootJar`가 `build/libs/app.jar`로 산출됨(기존과 동일 산출물 이름·위치).

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/kista/architecture/GradleModuleBoundaryTest.java docs/agents/architecture.md docs/agents/commands.md README.md
git commit -m "test(architecture): :trading-core→:api 역방향 의존 금지 규칙 + Gradle 구조 문서화"
```

---

## Verification (전체 플랜 완료 후)

1. `bash gradlew clean build` — BUILD SUCCESSFUL, `app.jar` 산출 확인.
2. `bash gradlew test` — 전체 그린(기존 테스트 수와 동일해야 함, `grep -c '@Test'` 등으로 비교 가능).
3. 로컬에서 `bash gradlew bootRun --args='--spring.profiles.active=local'`로 기동 → `/api/auth/dev-token` 발급 → 계좌 등록 → 전략 등록까지 수동 확인(런타임 동작 무변경 검증 — 이 플랜은 순수 컴파일 경계 재구성이므로 기능 회귀가 있으면 안 된다).
4. `git log --oneline -8`로 8개 태스크가 각각 독립 커밋으로 남았는지 확인.
