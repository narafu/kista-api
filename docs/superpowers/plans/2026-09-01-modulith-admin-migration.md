# Spring Modulith admin(+settings) 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 흩어진 admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그) + settings 애그리게이트(런타임 설정)를 신규 `com.kista.admin` Spring Modulith `CLOSED` 모듈로 이전한다.

**Architecture:** finance/notify/broker/trading/market/privacy/stats 7모듈 이전과 동일 패턴 — 모듈 내부는 기존 Hexagonal 레이어(`domain/application/adapter`)를 그대로 유지하고 최상위 패키지만 `com.kista.admin`으로 옮긴다. 순수 구조 이전이라 TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클. **단 사전 실측으로 `trading ↔ admin` 하드 순환 1건이 확인**됐으므로(trading `*CreationResolver` 4파일이 `domain/model/settings`의 전략생성정책 타입을 소비 ↔ admin이 trading `Order`/`StrategyCycle` 등 소비), 코어 이전 **전에** 이 순환을 끊는 코드 변경 태스크를 Task 1에 둔다: trading이 `StrategyCreationSettings`/`StrategyFieldSettings`/`RecurringMode`를 자체 소유(broker `Direction`/privacy `PrivacyOrderType` 선례)하고 레거시 `StrategyService`가 경계에서 매핑. `admin → notify` 엣지가 0건이라 market/privacy/stats에 있던 이벤트 전환 태스크는 **불필요**하다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` ("착수 순서 (실측 기반, v2)" 1단계 잔여분 중 admin — 마지막)

## Global Constraints

- **[스펙 정정 1 — settings 도메인 모델] `domain/model/settings/` 6개는 admin으로 옮기되, trading이 소비하는 3개(`StrategyCreationSettings`/`StrategyFieldSettings`/`RecurringMode`)는 trading이 자체 복제본을 소유한다.** 사전 실측: `com.kista.trading.domain.strategy.{StrategyCreationResolver, InfiniteCreationResolver, PrivacyCreationResolver, VrCreationResolver}` (CLOSED)가 `com.kista.domain.model.settings.{StrategyCreationSettings, RecurringMode}` (+ `StrategyFieldSettings` 전이)를 소비. admin이 이 타입을 소유하면 기존의 무거운 `admin → trading`(`Order`/`StrategyCycle`/`AccountBalance`/`CyclePosition`/`DstInfo` + 3개 port + `CycleEndedEvent`)과 맞물려 양방향 순환. → Task 1에서 trading이 자체 타입 소유, `StrategyService.resolveCreationSettings()`가 경계 매핑. `RuntimeSettings`/`BenchmarkSettings`/`BenchmarkFieldSettings`는 trading 역참조 0이므로 admin이 온전히 소유.
- **[스펙 정정 2 — user↔admin 원인] 스펙의 "`RuntimeSettingsService`가 user↔admin 결합의 유일 원인, settings 재분류로 해소"는 부정확.** 사전 실측으로 추가 경로 2건 확인: ① `AdminUserViewAdapter`(미래 user 모듈)가 `implements AdminUserViewPort` + `AdminUserView` import → 미래 `user → admin`; `AdminService`가 `User`/`UserPort`/`UserUseCase`/`UserCascadeDeleter` import → `admin → user`. ② `UserService.java:43` `private final RuntimeSettingsPort runtimeSettingsPort` → 미래 `user → admin`(settings 서비스를 admin으로 옮겨도 이 포트 의존은 안 사라짐). **이 세 건은 전부 user가 아직 레거시 `Type.OPEN`이라 지금은 하드 순환이 아니다.** step 3(user) 착수 시 own-type/이벤트로 처리. Task 5에서 스펙 문구 정정 + architecture.md에 step-3 IOU로 명시.
- **[스펙 정정 3 — SettingsController] `SettingsController`는 admin으로 옮기지 않는다(스펙은 이전 지정).** 전 의존이 user 애그리게이트: `UpdateBalanceCheckUseCase`, `UpdateNotificationPrefUseCase`, `UpdateStrategySuggestionsUseCase`, `UserProfileUseCase`, `NotificationType`, `User.NotificationChannel` + user DTO 6개. admin 소유 의존 0건. `/api/settings/*`는 사용자 셀프서비스 엔드포인트 → 미래 user 모듈. 레거시 `com.kista.adapter.in.web` 잔류.
- **[AppErrorLog 계열 admin 이전] `AppErrorLogPort` + `AppErrorLog`(domain) + `AppErrorLog{Entity,JpaRepository,PersistenceAdapter}`(persistence trio)를 admin으로 이전(스펙대로).** `GlobalExceptionHandler`(`:42` `private final AppErrorLogPort appErrorLogPort`)가 레거시 잔류라 **모든 CLOSED 모듈의 `@WebMvcTest` 슬라이스(finance 8 + market 2 + notify 1 + privacy 1 + stats 2 + trading 1 + 레거시 다수 = 총 ~42개 test 파일)가 `@MockitoBean AppErrorLogPort`로 admin 포트를 mock하게 된다.** `ModulithArchitectureTest.verify()`는 `ApplicationModules.of(KistaApplication.class)`로 main 소스만 스캔하므로 하드 순환 아님. `HexagonalArchitectureTest`는 `importPackages("com.kista")`로 test 포함하나 규칙 대상이 `..adapter..`/`..domain..`/`..application..` 패턴 + 포트는 interface라 위반 아님. Task 4에서 실제 확인.
- **[AdminUserView User enum 참조 유지] `AdminUserView.status`/`role`(`User.UserStatus`/`User.UserRole`)와 `AdminUserViewPort.findAllByStatus(User.UserStatus)`는 그대로 둔다.** `AdminService`/`AdminUserUseCase`/`AdminUserController`도 동일 enum을 시그니처에 이미 씀 — 포트만 자체 enum으로 바꾸면 반쪽. 하드 순환 아님(user 레거시 OPEN). step 3에서 admin↔user 경계 전체 + `User` nested enum sharedkernel 이관을 함께 처리. Task 5 architecture.md에 IOU 명시.
- **와일드카드 import 전수 처리 필수.** 사전 실측 확인: `AdminQueryService.java:15` = `import com.kista.privacy.application.port.output.PrivacyTradePort; import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;` (한 줄 3-import — `com.kista.application.port.output.*`가 `AuditLogPort`/`AppErrorLogPort` 해석). `StrategyService.java:12` = `import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;` (`RuntimeSettingsPort` 해석). `StrategyService.java:9` = `import com.kista.domain.model.strategy.*; import com.kista.trading.domain.model.*;`. sed 개별 치환은 이 와일드카드를 못 건드리므로 각 태스크에서 `git grep -n "^import com\.kista.*\*;"`로 대상 파일 먼저 훑고 수동 분리.
- **문자열 리터럴 FQN 사전 스캔.** 각 물리 이동 태스크 Step 0에서 `grep -rn`으로 `resources/**`(`*.yml`/`*.xml`) 와 java 문자열 리터럴(AOP `@Around`/`@Pointcut`) 내 admin/settings/audit 경로 참조 확인. 사전 실측 결과 **0건**(broker 때 prod Logback 로거 깨진 사례 대비 — 이번엔 클린). `ErrorLogAspect`의 유일한 `@Around` 포인트컷은 `com.kista.notify...NotifyPort+.notifyError`라 admin 이전과 무관. `SecurityConfig`의 `/api/admin/**`·`/api/runtime-config`는 URL 경로 문자열이라 패키지 이동 무관.
- **`ApplicationModules.verify()` 게이트는 모듈 선언(Task 4) 시점에만 유효.** admin이 `@ApplicationModule` 미선언인 동안은 레거시 OPEN의 일부로 취급돼 순환이 안 잡힌다. 사전 실측으로 순환 1건(`trading↔admin`)을 특정하고 Task 1에서 해소하지만, pairwise 한계로 놓친 전이 순환이 있을 수 있다(market `market→notify→trading→market`, privacy `privacy→notify→trading→privacy`, stats `stats↔notify` 교훈). **Task 4에서 `verify()`가 예측 못한 순환을 보고하면 즉시 멈추고 보고**(추측 수정 금지).
- **이동/유지 경계 정밀 목록은 아래 "File Structure" 절이 SSOT.** `GetUserSettingsQuery`(user 애그리게이트), `UserSettings*`/`UserNotificationPref*` persistence 6개, `UserSettingsPort`, `ClientErrorLogController`+`ClientErrorLogRequest`, `SettingsController`는 **레거시 잔류**.
- 커밋 전 검토자(리뷰어 서브에이전트) 검수 필수 — 전역 CLAUDE.md 규칙, 문서 전용 변경(Task 5) 제외.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit. macOS sed는 `sed -i ''`, Linux는 `sed -i`.
- **sed 이식성**: 이 계획의 sed 명령 중 `\(A\|B\)` 형태 alternation은 GNU sed 전용(BSD/macOS 기본 sed는 `\|`를 리터럴로 취급). BSD sed 환경이면 `perl -pi -e 's/.../.../g'`(동일 정규식) 또는 `gsed`로 치환하거나 alternation을 개별 `-e` 절/`for` 루프로 풀 것. 치환 후 반드시 `git grep`으로 잔존 옛 경로 0건 확인.

---

## File Structure (최종 `com.kista.admin` 트리)

```
com.kista.admin/
  package-info.java                       ← @ApplicationModule (Task 4)
  domain/
    model/                                ← "domain" NamedInterface (Task 4) — 16개 (flat)
      (admin 10: AdminAnomalies, AdminCycleStrategySummary, AdminManualTradeCorrectionCommand,
       AdminReorderCommand, AdminReorderResult, AdminStats, AdminTradeCorrectionResult,
       AdminUserView, AppErrorLog, AuditLog)
      (settings 6: RuntimeSettings, StrategyCreationSettings, StrategyFieldSettings, RecurringMode,
       BenchmarkSettings, BenchmarkFieldSettings)
  application/
    usecase/                              ← "usecase" NamedInterface (Task 4) — 7개
      AdminQueryUseCase, AdminReorderUseCase, AdminStrategyUseCase, AdminTradeCorrectionUseCase,
      AdminUserUseCase, AdminSettingsUseCase, RuntimeSettingsUseCase
    port/output/                          ← "port" NamedInterface (Task 4) — 4개
      AuditLogPort, AdminUserViewPort, AppErrorLogPort, RuntimeSettingsPort
    service/                              ← internal — 8개
      AdminService, AdminQueryService, AdminReorderService, AdminStrategyService,
      AdminTradeCorrectionService, AdminCycleCloser, AdminSelectionChain, RuntimeSettingsService
  adapter/
    in/web/                               ← internal — 11개 컨트롤러 + AdminUserViews 헬퍼
      AdminAccountController, AdminDashboardController, AdminObservabilityController,
      AdminPingController, AdminSchedulerController, AdminSettingsController, AdminTradeController,
      AdminUserController, AdminPrivacyTradeController, RuntimeConfigController, AdminUserViews
    in/web/dto/                           ← internal — 20개
      AdminAccountItem, AdminAccountResponse, AdminDashboardResponse, AdminManualTradeCorrectionRequest,
      AdminPrivacyBaseResponse, AdminReorderRequest, AdminReorderResponse, AdminRoleRequest,
      AdminSettingsRequest, AdminStatusRequest, AdminStrategyResponse, AdminTradeCorrectionResponse,
      AdminTradeResponse, AdminUserResponse, AnomaliesResponse, AuditLogResponse, ErrorLogResponse,
      ReorderTimingAvailabilityResponse, RuntimeSettingsResponse, StrategyStatusRequest
    out/persistence/audit/                ← internal — 6개
      AppErrorLogEntity, AppErrorLogJpaRepository, AppErrorLogPersistenceAdapter,
      AuditLogEntity, AuditLogJpaRepository, AuditLogPersistenceAdapter
    out/persistence/settings/             ← internal — 3개
      RuntimeSettingsEntity, RuntimeSettingsJpaRepository, RuntimeSettingsPersistenceAdapter
```

admin은 `adapter` 레이어에 **NamedInterface가 없다** — trading/stats의 "schedule"과 달리 `AdminSchedulerController`는 `adapter.in.web`(스케쥴러 아님)이고 trading/stats 스케쥴러의 *소비자*지 생산자가 아니다. adapter 전체 internal.

### trading 자체 소유(Task 1 신설)
```
com.kista.trading.domain.strategy/
  StrategyCreationSettings.java   ← 신규(레거시 복제) — resolver 4파일 + interface가 소비
  StrategyFieldSettings.java      ← 신규(레거시 복제) — generic <T> 정책 wrapper
  RecurringMode.java              ← 신규(레거시 복제) — enum {DEPOSIT, HOLD, WITHDRAW} 상수명 byte-identical
```

### 레거시 잔류 (경로만 갱신 — admin으로 안 옮김)
- `com.kista.adapter.in.web.SettingsController` + `SettingsControllerTest` (Global Constraint 3)
- `com.kista.adapter.in.web.ClientErrorLogController` + `ClientErrorLogRequest`(DTO) + `ClientErrorLogControllerTest` — 사용자 클라이언트 오류 수집, admin 아님. `AppErrorLogPort`(admin) 소비 = `legacy → admin` forward
- `com.kista.adapter.in.web.GlobalExceptionHandler` — `AppErrorLogPort`(admin) 필드
- `com.kista.adapter.in.aop.ErrorLogAspect` + `ErrorLogAspectTest`/`ErrorLogAspectPointcutTest` — `AppErrorLogPort`(admin) 필드
- `com.kista.application.usecase.GetUserSettingsQuery` — user 애그리게이트
- `com.kista.application.port.output.UserSettingsPort` — user 애그리게이트
- `com.kista.adapter.out.persistence.settings.{UserSettingsJpaEntity, UserSettingsJpaRepository, UserSettingsPersistenceAdapter, UserNotificationPrefId, UserNotificationPrefJpaEntity, UserNotificationPrefJpaRepository}` + `UserSettingsPersistenceAdapterTest` — user 애그리게이트
- `com.kista.application.service.user.UserSettingsService` — user 애그리게이트
- `com.kista.adapter.out.persistence.user.AdminUserViewAdapter` — `AdminUserViewPort`(admin) 구현체, 미래 user 모듈. `legacy → admin` forward
- `com.kista.adapter.out.persistence.strategy.StrategyPersistenceAdapter` + `com.kista.application.port.output.StrategyPort` — `AdminCycleStrategySummary`(admin) 반환. `legacy → admin` forward
- `com.kista.adapter.in.web.MetaController` — 사전 실측: admin/settings enum 직렬화 **0건**, 무변경
- `com.kista.application.service.strategy.StrategyService` / `com.kista.application.service.account.AccountService` / `com.kista.application.service.user.UserService` — `RuntimeSettingsPort`(admin) 소비. `legacy → admin` forward

### 레거시 공유 기반 클래스 계속 참조(그대로 둠)
`com.kista.adapter.out.persistence.{BaseAuditEntity, BaseCreatedAtEntity, JpaAuditingConfig}` — 전부 `public`, `Type.OPEN`. `RuntimeSettingsEntity`/`AuditLogEntity`가 `BaseAuditEntity` 상속, `AppErrorLogEntity`가 `BaseCreatedAtEntity` 상속 — finance/broker/trading/market/stats 어댑터도 동일하게 경계 넘어 참조 중. **audit_logs/app_error_logs/admin_runtime_settings 테이블은 `@Table(schema="public")` 명시됨**(사전 실측 확인 — 플랫폼 공통이라 정확).

---

## Task 1: trading 전략생성정책 자체 소유 — `trading ↔ admin` 순환 사전 해소

> **배경:** admin CLOSED 전환 시 `domain/model/settings`의 `StrategyCreationSettings`/`RecurringMode`(+`StrategyFieldSettings` 전이)가 admin으로 옮겨가면, 이 타입을 소비하는 trading `*CreationResolver` 4파일 + `StrategyCreationResolver` 인터페이스가 `trading → admin` 엣지를 만들고, 기존 `admin → trading`(무거움)과 맞물려 양방향 순환. broker `Direction`/`OrderType`·privacy `PrivacyOrderType`/`PrivacyOrderDirection` 선례대로 trading이 자체 복제본을 소유하고, 유일한 호출 경계인 `StrategyService.resolveCreationSettings()`가 매핑한다. **이 시점 settings 모델은 아직 레거시 `com.kista.domain.model.settings`에 있다**(Task 2에서 admin으로 이동) — Task 1은 trading의 소비를 레거시에서 끊는 것.

**Files:**
- Create: `src/main/java/com/kista/trading/domain/strategy/StrategyCreationSettings.java`
- Create: `src/main/java/com/kista/trading/domain/strategy/StrategyFieldSettings.java`
- Create: `src/main/java/com/kista/trading/domain/strategy/RecurringMode.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/StrategyCreationResolver.java` (import 제거 — 동일 패키지)
- Modify: `src/main/java/com/kista/trading/domain/strategy/InfiniteCreationResolver.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/PrivacyCreationResolver.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/VrCreationResolver.java`
- Modify: `src/main/java/com/kista/application/service/strategy/StrategyService.java` (경계 매핑 추가)
- Modify (테스트, 실측 필요): `src/test/java/com/kista/trading/domain/strategy/*CreationResolver*Test.java` 존재 시, `src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java`

**Interfaces:**
- Produces: `com.kista.trading.domain.strategy.StrategyCreationSettings` (record, 필드·시그니처는 레거시 `com.kista.domain.model.settings.StrategyCreationSettings`와 byte-identical), `com.kista.trading.domain.strategy.StrategyFieldSettings<T>` (record + `T resolve(T)` 메서드), `com.kista.trading.domain.strategy.RecurringMode` (enum {DEPOSIT, HOLD, WITHDRAW}) — Task 2 이후에도 trading 내부 전용, admin과 무관.
- Consumes (Task 1 시점): 레거시 `com.kista.domain.model.settings.StrategyCreationSettings` (StrategyService가 매핑 소스로), `com.kista.domain.model.strategy.Strategy.Ticker`/`Strategy.Type` (레거시, 그대로).

- [ ] **Step 1: 레거시 3개 파일 내용 확인**

```bash
cat src/main/java/com/kista/domain/model/settings/StrategyCreationSettings.java
cat src/main/java/com/kista/domain/model/settings/StrategyFieldSettings.java
cat src/main/java/com/kista/domain/model/settings/RecurringMode.java
```

- [ ] **Step 2: trading 패키지에 3개 복제본 생성**

`RecurringMode.java` (레거시와 동일, package만 변경):
```java
package com.kista.trading.domain.strategy;

// 전략 등록 시 VR 정기 입출금 방향 — 레거시 com.kista.admin.domain.model.RecurringMode(admin 소유)의
// trading 자체 복제본. 상수명 byte-identical이라 매핑은 valueOf(name())으로 충분.
// broker Direction/OrderType·privacy PrivacyOrderType와 동일한 모듈 경계 own-type 패턴
// (constraints.md "모듈 경계 포트 시그니처 — 각 모듈은 자기 소유 타입만 사용").
public enum RecurringMode {
    DEPOSIT, // 정기 적립
    HOLD, // 정기 입출금 없음
    WITHDRAW // 정기 인출
}
```

`StrategyFieldSettings.java` — 레거시 `com.kista.domain.model.settings.StrategyFieldSettings` 본문을 그대로 복제하되 `package com.kista.trading.domain.strategy;` 로 변경하고, 클래스 상단에 한 줄 주석 추가:
```java
package com.kista.trading.domain.strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

// 전략 생성 필드 정책 wrapper(허용값·기본값·고정여부) — admin RuntimeSettings 트리의 trading 자체 복제본.
// resolve()/valuesEqual() 로직은 레거시 원본과 byte-identical 유지.
public record StrategyFieldSettings<T>(
        boolean customizable,
        List<T> allowedValues,
        T defaultValue
) {
    // ↓↓↓ 레거시 com.kista.domain.model.settings.StrategyFieldSettings 의 compact 생성자 + resolve() + valuesEqual() 를
    //     그대로 복사한다. Step 1 cat 출력과 diff 0 이어야 함(package·상단주석만 차이).
}
```

`StrategyCreationSettings.java`:
```java
package com.kista.trading.domain.strategy;

import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;

// 전략별 신규 생성 정책 — admin RuntimeSettings.strategies() 값 타입의 trading 자체 복제본.
// StrategyService.resolveCreationSettings()가 admin 타입 → 이 타입으로 매핑해 resolver에 전달한다.
public record StrategyCreationSettings(
        boolean enabled,
        StrategyFieldSettings<Ticker> ticker,
        StrategyFieldSettings<Integer> divisionCount,
        StrategyFieldSettings<RecurringMode> recurringMode,
        StrategyFieldSettings<BigDecimal> bandWidth,
        StrategyFieldSettings<Integer> intervalWeeks
) {
    // ↓↓↓ 레거시 원본의 compact 생성자(recurringMode 고정정책 검증)를 그대로 복사.
    //     레거시가 참조하는 RecurringMode 는 이 파일과 동일 패키지의 trading RecurringMode 로 자동 해석됨.
}
```

- [ ] **Step 3: resolver 4파일 import 정리**

`StrategyCreationResolver.java`, `InfiniteCreationResolver.java`, `PrivacyCreationResolver.java`, `VrCreationResolver.java` 에서 아래 import 두 줄을 **삭제**(동일 패키지가 되어 불필요):
```
import com.kista.domain.model.settings.RecurringMode;
import com.kista.domain.model.settings.StrategyCreationSettings;
```
(`StrategyFieldSettings`는 resolver가 직접 import하지 않음 — `StrategyCreationSettings` 필드로만 접근.) 본문 코드는 변경 없음.

```bash
sed -i '' '/^import com\.kista\.domain\.model\.settings\.\(RecurringMode\|StrategyCreationSettings\);$/d' \
  src/main/java/com/kista/trading/domain/strategy/StrategyCreationResolver.java \
  src/main/java/com/kista/trading/domain/strategy/InfiniteCreationResolver.java \
  src/main/java/com/kista/trading/domain/strategy/PrivacyCreationResolver.java \
  src/main/java/com/kista/trading/domain/strategy/VrCreationResolver.java
```

- [ ] **Step 4: `StrategyService.resolveCreationSettings()` 경계 매핑 추가**

현재 (`src/main/java/com/kista/application/service/strategy/StrategyService.java:150-156`):
```java
private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
    StrategyCreationSettings settings = runtimeSettingsPort.load().strategies().get(cmd.type());
    if (!settings.enabled()) {
        throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
    }
    return creationResolvers.of(cmd.type()).resolve(cmd, settings);
}
```

`import com.kista.domain.model.settings.StrategyCreationSettings;` (`:8`) 를 유지하되, resolver 호출 인자를 trading 타입으로 매핑. `StrategyService.java:15-16` 의 `import com.kista.trading.domain.strategy.StrategyCreationResolver;` 옆에 있는 심볼들과 이름 충돌을 피하기 위해 **trading 타입은 FQN으로 참조**한다:

```java
private ResolvedCreation resolveCreationSettings(RegisterStrategyCommand cmd) {
    // 레거시 런타임 설정 타입 조회 (Task 2 이후: com.kista.admin.domain.model.StrategyCreationSettings)
    StrategyCreationSettings settings = runtimeSettingsPort.load().strategies().get(cmd.type());
    if (!settings.enabled()) {
        throw new IllegalArgumentException("비활성화된 전략 유형은 새로 등록할 수 없습니다: " + cmd.type());
    }
    // 모듈 경계 매핑 — resolver(trading)는 자체 타입만 받는다
    return creationResolvers.of(cmd.type()).resolve(cmd, toTradingSettings(settings));
}

// admin(현재는 레거시) StrategyCreationSettings → trading 자체 타입. 필드 구조 동일, RecurringMode만 valueOf(name()).
private static com.kista.trading.domain.strategy.StrategyCreationSettings toTradingSettings(StrategyCreationSettings s) {
    return new com.kista.trading.domain.strategy.StrategyCreationSettings(
            s.enabled(),
            mapField(s.ticker(), t -> t),
            mapField(s.divisionCount(), i -> i),
            mapRecurringField(s.recurringMode()),
            mapField(s.bandWidth(), b -> b),
            mapField(s.intervalWeeks(), i -> i));
}

private static <T> com.kista.trading.domain.strategy.StrategyFieldSettings<T> mapField(
        com.kista.domain.model.settings.StrategyFieldSettings<T> f, java.util.function.UnaryOperator<T> id) {
    if (f == null) return null;
    return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
            f.customizable(), f.allowedValues().stream().map(id).toList(), f.defaultValue());
}

private static com.kista.trading.domain.strategy.StrategyFieldSettings<com.kista.trading.domain.strategy.RecurringMode> mapRecurringField(
        com.kista.domain.model.settings.StrategyFieldSettings<com.kista.domain.model.settings.RecurringMode> f) {
    if (f == null) return null;
    return new com.kista.trading.domain.strategy.StrategyFieldSettings<>(
            f.customizable(),
            f.allowedValues().stream().map(m -> com.kista.trading.domain.strategy.RecurringMode.valueOf(m.name())).toList(),
            com.kista.trading.domain.strategy.RecurringMode.valueOf(f.defaultValue().name()));
}
```

> **실행자 주의:** `StrategyService`의 실제 현재 본문·import 블록을 읽고 위 패턴을 맞춰 넣을 것. `mapField`의 `id` 파라미터는 `allowedValues` 리스트 복제용(제네릭 항등 매핑) — `StrategyFieldSettings` 생성자가 `List.copyOf` 하므로 스트림 없이 `f.allowedValues()` 직접 전달도 가능하나, `List<T>` 불변 보장을 위해 유지.

- [ ] **Step 5: trading에 settings 잔존 참조 0 확인**

```bash
git grep -n "com\.kista\.domain\.model\.settings" -- src/main/java/com/kista/trading src/test/java/com/kista/trading
```
Expected: 출력 없음.

- [ ] **Step 6: 컴파일 + 관련 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.trading.domain.strategy.*' --tests 'com.kista.application.service.strategy.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, `BUILD SUCCESSFUL`. `StrategyServiceTest`가 레거시 `StrategyCreationSettings`/`StrategyFieldSettings`/`RecurringMode`로 stub을 만든다면(사전 실측: `:7-10` import 있음) 그대로 둔다 — `runtimeSettingsPort.load()` mock 반환이 레거시 타입이고 `toTradingSettings`가 내부 변환하므로 테스트 수정 불필요. 실패 시 실제 실패 메시지 기준 최소 수정.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): trading 전략생성정책 자체 소유 — admin 이전 전 순환 해소

StrategyCreationResolver 계열이 소비하던 domain/model/settings의
StrategyCreationSettings/StrategyFieldSettings/RecurringMode를 trading이
자체 복제본으로 소유(broker Direction/privacy PrivacyOrderType 선례).
StrategyService.resolveCreationSettings()가 경계에서 매핑. settings 모델이
admin CLOSED로 옮겨갈 때 trading↔admin 양방향 순환이 생기는 것을 사전 차단.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 코어(domain + application) 물리 이전 + 전역 소비자 import 정합화

**Files:**
- Move: `src/main/java/com/kista/domain/model/admin/*.java` (10개) → `src/main/java/com/kista/admin/domain/model/`
- Move: `src/main/java/com/kista/domain/model/settings/{RuntimeSettings,StrategyCreationSettings,StrategyFieldSettings,RecurringMode,BenchmarkSettings,BenchmarkFieldSettings}.java` (6개) → `src/main/java/com/kista/admin/domain/model/`
- Move: `src/main/java/com/kista/application/usecase/{AdminQueryUseCase,AdminReorderUseCase,AdminStrategyUseCase,AdminTradeCorrectionUseCase,AdminUserUseCase,AdminSettingsUseCase,RuntimeSettingsUseCase}.java` (7개) → `src/main/java/com/kista/admin/application/usecase/`
- Move: `src/main/java/com/kista/application/port/output/{AuditLogPort,AdminUserViewPort,AppErrorLogPort,RuntimeSettingsPort}.java` (4개) → `src/main/java/com/kista/admin/application/port/output/`
- Move: `src/main/java/com/kista/application/service/admin/*.java` (7개) → `src/main/java/com/kista/admin/application/service/`
- Move: `src/main/java/com/kista/application/service/settings/RuntimeSettingsService.java` → `src/main/java/com/kista/admin/application/service/`
- Move tests: `src/test/java/com/kista/domain/model/settings/RuntimeSettingsTest.java` → `src/test/java/com/kista/admin/domain/model/`; `src/test/java/com/kista/application/service/admin/*.java` (7개) → `src/test/java/com/kista/admin/application/service/`; `src/test/java/com/kista/application/service/settings/{RuntimeSettingsServiceTest,RuntimeSettingsApprovalConcurrencyIT}.java` → `src/test/java/com/kista/admin/application/service/`
- Modify (import 경로만, 이동 안 함): Step 5의 전역 소비자 목록

**Interfaces:**
- Produces: `com.kista.admin.domain.model.*` (16개), `com.kista.admin.application.usecase.*` (7개), `com.kista.admin.application.port.output.*` (4개) — Task 3(어댑터)·Task 4(모듈 선언)가 이 경로를 소비.
- Consumes: Task 1의 `com.kista.trading.domain.strategy.{StrategyCreationSettings,...}` (StrategyService 매핑 소스가 이제 `com.kista.admin.domain.model.StrategyCreationSettings`로 바뀜).

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔**

```bash
grep -rn "com\.kista\.\(domain\.model\.admin\|domain\.model\.settings\|application\.service\.admin\|application\.service\.settings\|application\.usecase\.\(Admin\|RuntimeSettings\)\|application\.port\.output\.\(AuditLog\|AdminUserView\|AppErrorLog\|RuntimeSettings\)\)" src/main/resources/ --include='*.yml' --include='*.xml'
git grep -n '"[^"]*com\.kista\.[^"]*\(admin\|settings\|[Aa]udit\|RuntimeSettings\)' -- src/main/java
```
Expected: 0건(사전 실측 확인). 매치 있으면 이 태스크 diff에 포함해 갱신.

- [ ] **Step 1: 와일드카드 import 사전 확인**

```bash
git grep -n "^import com\.kista.*\*;" -- \
  src/main/java/com/kista/domain/model/admin src/main/java/com/kista/domain/model/settings \
  src/main/java/com/kista/application/service/admin src/main/java/com/kista/application/service/settings \
  src/main/java/com/kista/application/usecase src/main/java/com/kista/application/port/output
```
알려진 것: `AdminQueryService.java:15` (3-import 한 줄). Step 4에서 수동 처리.

- [ ] **Step 2: 코어 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/admin/domain/model
mkdir -p src/main/java/com/kista/admin/application/usecase
mkdir -p src/main/java/com/kista/admin/application/port/output
mkdir -p src/main/java/com/kista/admin/application/service

git mv src/main/java/com/kista/domain/model/admin/*.java src/main/java/com/kista/admin/domain/model/
rmdir src/main/java/com/kista/domain/model/admin

for f in RuntimeSettings StrategyCreationSettings StrategyFieldSettings RecurringMode BenchmarkSettings BenchmarkFieldSettings; do
  git mv "src/main/java/com/kista/domain/model/settings/$f.java" "src/main/java/com/kista/admin/domain/model/$f.java"
done
# domain/model/settings 는 UserSettings 관련이 남으면 안 됨 — 이 디렉토리엔 원래 이 6개뿐이므로 비어야 함
rmdir src/main/java/com/kista/domain/model/settings

for f in AdminQueryUseCase AdminReorderUseCase AdminStrategyUseCase AdminTradeCorrectionUseCase AdminUserUseCase AdminSettingsUseCase RuntimeSettingsUseCase; do
  git mv "src/main/java/com/kista/application/usecase/$f.java" "src/main/java/com/kista/admin/application/usecase/$f.java"
done

for f in AuditLogPort AdminUserViewPort AppErrorLogPort RuntimeSettingsPort; do
  git mv "src/main/java/com/kista/application/port/output/$f.java" "src/main/java/com/kista/admin/application/port/output/$f.java"
done

git mv src/main/java/com/kista/application/service/admin/*.java src/main/java/com/kista/admin/application/service/
rmdir src/main/java/com/kista/application/service/admin
git mv src/main/java/com/kista/application/service/settings/RuntimeSettingsService.java src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java
# application/service/settings 에 뭐가 남는지 확인 (없어야 함 — RuntimeSettingsService 하나뿐이었음)
ls src/main/java/com/kista/application/service/settings/ 2>/dev/null && echo "STOP: 잔존 파일" || rmdir src/main/java/com/kista/application/service/settings

mkdir -p src/test/java/com/kista/admin/domain/model
mkdir -p src/test/java/com/kista/admin/application/service
git mv src/test/java/com/kista/domain/model/settings/RuntimeSettingsTest.java src/test/java/com/kista/admin/domain/model/RuntimeSettingsTest.java
rmdir src/test/java/com/kista/domain/model/settings
git mv src/test/java/com/kista/application/service/admin/*.java src/test/java/com/kista/admin/application/service/
rmdir src/test/java/com/kista/application/service/admin
git mv src/test/java/com/kista/application/service/settings/RuntimeSettingsServiceTest.java src/test/java/com/kista/admin/application/service/RuntimeSettingsServiceTest.java
git mv src/test/java/com/kista/application/service/settings/RuntimeSettingsApprovalConcurrencyIT.java src/test/java/com/kista/admin/application/service/RuntimeSettingsApprovalConcurrencyIT.java
rmdir src/test/java/com/kista/application/service/settings
```

- [ ] **Step 3: 이동 파일의 package 선언 + 상호 import 치환**

```bash
find src/main/java/com/kista/admin src/test/java/com/kista/admin -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.domain\.model\.admin;/package com.kista.admin.domain.model;/' \
  -e 's/^package com\.kista\.domain\.model\.settings;/package com.kista.admin.domain.model;/' \
  -e 's/^package com\.kista\.application\.usecase;/package com.kista.admin.application.usecase;/' \
  -e 's/^package com\.kista\.application\.port\.output;/package com.kista.admin.application.port.output;/' \
  -e 's/^package com\.kista\.application\.service\.admin;/package com.kista.admin.application.service;/' \
  -e 's/^package com\.kista\.application\.service\.settings;/package com.kista.admin.application.service;/' \
  -e 's/com\.kista\.domain\.model\.admin\./com.kista.admin.domain.model./g' \
  -e 's/com\.kista\.domain\.model\.settings\.\(RuntimeSettings\|StrategyCreationSettings\|StrategyFieldSettings\|RecurringMode\|BenchmarkSettings\|BenchmarkFieldSettings\)/com.kista.admin.domain.model.\1/g' \
  -e 's/com\.kista\.application\.usecase\.\(AdminQueryUseCase\|AdminReorderUseCase\|AdminStrategyUseCase\|AdminTradeCorrectionUseCase\|AdminUserUseCase\|AdminSettingsUseCase\|RuntimeSettingsUseCase\)/com.kista.admin.application.usecase.\1/g' \
  -e 's/com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)/com.kista.admin.application.port.output.\1/g' \
  {} +
```

> **주의:** `domain.model.settings` 치환은 **이동 6개 타입만** 명시(alternation) — `UserSettings`/`NotificationType`은 `com.kista.domain.model.user`라 이 패턴에 안 걸리지만, 혹시 `com.kista.domain.model.settings.` 로 시작하는 다른 참조가 있으면 안 되므로 개별 이름으로 제한.

- [ ] **Step 4: `AdminQueryService.java` 와일드카드 import 수동 분리**

`AdminQueryService.java:15` (실측): 한 줄에
```
import com.kista.privacy.application.port.output.PrivacyTradePort; import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
```
Edit 도구로 3줄 분리 + 첫 와일드카드를 admin 경로로:
```
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.admin.application.port.output.*;
import com.kista.trading.application.port.output.*;
```
그 뒤 컴파일로 `com.kista.application.port.output` 의 비-admin 포트(예: `AccountPort`/`StrategyPort`)가 `AdminQueryService`에 필요한지 확인:
```bash
./gradlew compileJava 2>&1 | grep -E "AdminQueryService.*cannot find symbol" | head
```
`cannot find symbol`이 뜨면 그 포트만 명시 import 추가 (`com.kista.application.port.output.StrategyPort` 등). 사전 실측: `AdminQueryService`는 `AuditLogPort`/`AppErrorLogPort`(admin) + `StrategyPort`(레거시, `AdminCycleStrategySummary` 조회) 조합 — `StrategyPort` 명시 import 필요할 가능성 높음.

- [ ] **Step 5: 전역 소비자 import 경로 치환 (이번 태스크에서 이동 안 하는 파일)**

대상 색출(어댑터·DTO는 Task 3에서 이동과 함께 처리하므로 제외):

```bash
git grep -ln "com\.kista\.domain\.model\.admin\|com\.kista\.domain\.model\.settings\.\(RuntimeSettings\|StrategyCreationSettings\|StrategyFieldSettings\|RecurringMode\|BenchmarkSettings\|BenchmarkFieldSettings\)\|com\.kista\.application\.usecase\.\(AdminQueryUseCase\|AdminReorderUseCase\|AdminStrategyUseCase\|AdminTradeCorrectionUseCase\|AdminUserUseCase\|AdminSettingsUseCase\|RuntimeSettingsUseCase\)\|com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)" -- src/main src/test \
  | grep -v "src/main/java/com/kista/admin/\|src/test/java/com/kista/admin/" \
  | grep -vE "/(Admin[A-Za-z]*Controller|AdminUserViews|RuntimeConfigController|AdminPrivacyTradeController)\.java" \
  | grep -vE "adapter/in/web/dto/(Admin|Audit|ErrorLog|RuntimeSettings|Anomalies|Reorder|StrategyStatus)" \
  | grep -vE "adapter/out/persistence/(audit|settings)/" \
  | sort -u
```

현재 실측 기준 이 목록에 남을 것(레거시 잔류, import만 갱신):
- `src/main/java/com/kista/application/service/strategy/StrategyService.java` — `RuntimeSettingsPort`(와일드카드 `:12`), `StrategyCreationSettings`(`:8`, 매핑 소스)
- `src/main/java/com/kista/application/service/account/AccountService.java` — `RuntimeSettingsPort`(`:9`)
- `src/main/java/com/kista/application/service/user/UserService.java` — `RuntimeSettingsPort`(와일드카드 `:10`)
- `src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java` — `AppErrorLogPort`(`:15`)
- `src/main/java/com/kista/adapter/in/aop/ErrorLogAspect.java` — `AppErrorLogPort`
- `src/main/java/com/kista/adapter/in/web/ClientErrorLogController.java` — `AppErrorLogPort`
- `src/main/java/com/kista/adapter/out/persistence/user/AdminUserViewAdapter.java` — `AdminUserView`, `AdminUserViewPort`
- `src/main/java/com/kista/adapter/out/persistence/strategy/StrategyPersistenceAdapter.java` — `AdminCycleStrategySummary`
- `src/main/java/com/kista/application/port/output/StrategyPort.java` — `AdminCycleStrategySummary`
- test 다수(특히 `AppErrorLogPort` mock ~42개 — Task 3에서 처리하는 게 아니라 여기서 함께: 위 색출이 test도 포함)

명시 import 파일에 sed 적용(와일드카드 파일은 제외하고 별도 처리):

```bash
# 위 색출 결과에서 와일드카드 파일(StrategyService/UserService)을 뺀 목록에 적용
sed -i '' \
  -e 's#com\.kista\.domain\.model\.admin\.#com.kista.admin.domain.model.#g' \
  -e 's#com\.kista\.domain\.model\.settings\.\(RuntimeSettings\|StrategyCreationSettings\|StrategyFieldSettings\|RecurringMode\|BenchmarkSettings\|BenchmarkFieldSettings\)#com.kista.admin.domain.model.\1#g' \
  -e 's#com\.kista\.application\.usecase\.\(AdminQueryUseCase\|AdminReorderUseCase\|AdminStrategyUseCase\|AdminTradeCorrectionUseCase\|AdminUserUseCase\|AdminSettingsUseCase\|RuntimeSettingsUseCase\)#com.kista.admin.application.usecase.\1#g' \
  -e 's#com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)#com.kista.admin.application.port.output.\1#g' \
  <색출된 명시-import 파일 목록>
```

와일드카드 파일 3개 수동 처리:
- `StrategyService.java:12` — `import com.kista.application.port.output.*;` 가 `RuntimeSettingsPort` + 다른 레거시 포트(`AccountPort`/`StrategyPort`/`UserPort` 등)를 함께 해석. **와일드카드는 그대로 두고** `import com.kista.admin.application.port.output.RuntimeSettingsPort;` 를 별도 라인으로 추가(명시 import가 와일드카드보다 우선). `:8` 의 `import com.kista.domain.model.settings.StrategyCreationSettings;` → `import com.kista.admin.domain.model.StrategyCreationSettings;` (Task 1 Step 4에서 이 타입을 매핑 소스로 씀 — 경로만 갱신).
- `UserService.java:10` — 동일: `import com.kista.admin.application.port.output.RuntimeSettingsPort;` 별도 라인 추가.
- `AccountService.java:9` — 명시 import라 위 sed가 처리(색출 목록에 포함).

- [ ] **Step 6: 색출 재확인 + 컴파일 (완전 그린은 Task 3 이후)**

```bash
git grep -ln "com\.kista\.domain\.model\.admin\|com\.kista\.application\.usecase\.\(AdminQuery\|AdminReorder\|AdminStrategy\|AdminTradeCorrection\|AdminUser\|AdminSettings\|RuntimeSettings\)UseCase\|com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)" -- src/main src/test | grep -v "com/kista/admin/" | sort -u
```
남는 것은 Task 3 이동 대상 어댑터/DTO만이어야 함(`Admin*Controller`, `admin` DTO, `audit`/`settings` persistence).

```bash
./gradlew compileJava 2>&1 | grep -E "error:|FAILED"
```
Expected: Task 3 이동 대상 어댑터에서 `cannot find symbol` 다수 — 정상. 그 외 파일이 깨졌으면 Step 5 누락이므로 즉시 확인.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): admin 모듈 코어(domain+application) 이전 + settings 흡수

domain/model/admin 10타입 + domain/model/settings 6타입, usecase 7개,
output port 4개(AuditLog/AdminUserView/AppErrorLog/RuntimeSettings),
서비스 8개(Admin* 7 + RuntimeSettingsService)를 com.kista.admin으로 이전.
어댑터 레이어는 Task 3에서 이어서 이전 — 이 시점 컴파일 에러(이동 대상
어댑터의 레거시 경로 참조)는 정상. SettingsController/ClientErrorLogController/
UserSettings*·GetUserSettingsQuery는 user 애그리게이트라 레거시 잔류.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 어댑터(in/web + out/persistence) 물리 이전 + 전체 컴파일 그린

> **SDD 오케스트레이터 주의:** 이 태스크는 컨트롤러 11 + DTO 20 + persistence 9 + 교차모듈 test import ~42 = 이번 계획에서 가장 크다. privacy 이전 때 유사 규모 태스크가 세션 한도로 중단됐고 `SendMessage`로 **동일 에이전트 재개**(부분 산출물 유지, 콜드 재디스패치 안 함)로 복구됐다. 이 태스크 실행 중 에이전트가 죽으면 `ListAgents`로 상태 확인 후 `SendMessage`로 먼저 재개를 시도할 것.

**Files:**
- Move: `src/main/java/com/kista/adapter/in/web/{AdminAccountController,AdminDashboardController,AdminObservabilityController,AdminPingController,AdminSchedulerController,AdminSettingsController,AdminTradeController,AdminUserController,AdminPrivacyTradeController,RuntimeConfigController,AdminUserViews}.java` (11개) → `src/main/java/com/kista/admin/adapter/in/web/`
- Move: `src/main/java/com/kista/adapter/in/web/dto/{AdminAccountItem,AdminAccountResponse,AdminDashboardResponse,AdminManualTradeCorrectionRequest,AdminPrivacyBaseResponse,AdminReorderRequest,AdminReorderResponse,AdminRoleRequest,AdminSettingsRequest,AdminStatusRequest,AdminStrategyResponse,AdminTradeCorrectionResponse,AdminTradeResponse,AdminUserResponse,AnomaliesResponse,AuditLogResponse,ErrorLogResponse,ReorderTimingAvailabilityResponse,RuntimeSettingsResponse,StrategyStatusRequest}.java` (20개) → `src/main/java/com/kista/admin/adapter/in/web/dto/`
- Move: `src/main/java/com/kista/adapter/out/persistence/audit/*.java` (6개) → `src/main/java/com/kista/admin/adapter/out/persistence/audit/`
- Move: `src/main/java/com/kista/adapter/out/persistence/settings/{RuntimeSettingsEntity,RuntimeSettingsJpaRepository,RuntimeSettingsPersistenceAdapter}.java` (3개) → `src/main/java/com/kista/admin/adapter/out/persistence/settings/`
- Modify (import 경로만, 레거시 잔류): `GlobalExceptionHandler.java`, `ErrorLogAspect.java`, `ClientErrorLogController.java`, `SettingsController.java`(admin DTO 참조 시), `StrategyPersistenceAdapter.java`, `StrategyPort.java`, `AdminUserViewAdapter.java`, `MetaController.java`(참조 시), + Step 4가 색출하는 ~42개 `@MockitoBean AppErrorLogPort` test 파일
- Move tests: `src/test/java/com/kista/adapter/in/web/{AdminAccountControllerTest,AdminDashboardControllerTest,AdminObservabilityControllerTest,AdminPingControllerTest,AdminSchedulerControllerTest,AdminSchedulerControllerDisabledTest,AdminSettingsControllerTest,AdminTradeControllerTest,AdminUserControllerTest,AdminPrivacyTradeControllerTest,RuntimeConfigControllerTest}.java` (11개) → `src/test/java/com/kista/admin/adapter/in/web/`; `src/test/java/com/kista/adapter/out/persistence/audit/{AppErrorLogPersistenceAdapterTest,AuditLogPersistenceAdapterTest,AuditLogPersistenceAdapterIT}.java` (3개) → `src/test/java/com/kista/admin/adapter/out/persistence/audit/`; `src/test/java/com/kista/adapter/out/persistence/settings/{RuntimeSettingsPersistenceAdapterTest,RuntimeSettingsPersistenceAdapterIT}.java` (2개) → `src/test/java/com/kista/admin/adapter/out/persistence/settings/`

**Interfaces:**
- Consumes: Task 2가 만든 `com.kista.admin.{domain.model,application.usecase,application.port.output}.*`
- Produces: `com.kista.admin.adapter.*` 전체 — Task 4 NamedInterface 대상 아님(internal 유지)

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔 (어댑터 경로)**

```bash
grep -rn "com\.kista\.adapter\.\(in\.web\.\(Admin\|RuntimeConfig\)\|in\.web\.dto\.\(Admin\|Audit\|ErrorLog\|RuntimeSettings\|Anomalies\|Reorder\|StrategyStatus\)\|out\.persistence\.\(audit\|settings\)\)" src/main/resources/ src/main/java --include='*.yml' --include='*.xml'
git grep -n '"[^"]*com\.kista\.adapter\.\(in\.web\.\(Admin\|RuntimeConfig\)\|out\.persistence\.\(audit\|settings\)\)' -- src/main/java
```
매치 시 함께 갱신. 특히 `application-*.yml`의 Logback 로거·`springdoc`/openapi group path·`scheduler` cron property key 확인.

- [ ] **Step 1: 어댑터 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/admin/adapter/in/web/dto
mkdir -p src/main/java/com/kista/admin/adapter/out/persistence/audit
mkdir -p src/main/java/com/kista/admin/adapter/out/persistence/settings

for f in AdminAccountController AdminDashboardController AdminObservabilityController AdminPingController AdminSchedulerController AdminSettingsController AdminTradeController AdminUserController AdminPrivacyTradeController RuntimeConfigController AdminUserViews; do
  git mv "src/main/java/com/kista/adapter/in/web/$f.java" "src/main/java/com/kista/admin/adapter/in/web/$f.java"
done

for f in AdminAccountItem AdminAccountResponse AdminDashboardResponse AdminManualTradeCorrectionRequest AdminPrivacyBaseResponse AdminReorderRequest AdminReorderResponse AdminRoleRequest AdminSettingsRequest AdminStatusRequest AdminStrategyResponse AdminTradeCorrectionResponse AdminTradeResponse AdminUserResponse AnomaliesResponse AuditLogResponse ErrorLogResponse ReorderTimingAvailabilityResponse RuntimeSettingsResponse StrategyStatusRequest; do
  git mv "src/main/java/com/kista/adapter/in/web/dto/$f.java" "src/main/java/com/kista/admin/adapter/in/web/dto/$f.java"
done

git mv src/main/java/com/kista/adapter/out/persistence/audit/*.java src/main/java/com/kista/admin/adapter/out/persistence/audit/
rmdir src/main/java/com/kista/adapter/out/persistence/audit

for f in RuntimeSettingsEntity RuntimeSettingsJpaRepository RuntimeSettingsPersistenceAdapter; do
  git mv "src/main/java/com/kista/adapter/out/persistence/settings/$f.java" "src/main/java/com/kista/admin/adapter/out/persistence/settings/$f.java"
done
# settings persistence 에 UserSettings*/UserNotificationPref* 6개가 남아야 정상
ls src/main/java/com/kista/adapter/out/persistence/settings/
```

- [ ] **Step 2: 테스트 파일 물리 이동**

```bash
mkdir -p src/test/java/com/kista/admin/adapter/in/web
mkdir -p src/test/java/com/kista/admin/adapter/out/persistence/audit
mkdir -p src/test/java/com/kista/admin/adapter/out/persistence/settings

for f in AdminAccountControllerTest AdminDashboardControllerTest AdminObservabilityControllerTest AdminPingControllerTest AdminSchedulerControllerTest AdminSchedulerControllerDisabledTest AdminSettingsControllerTest AdminTradeControllerTest AdminUserControllerTest AdminPrivacyTradeControllerTest RuntimeConfigControllerTest; do
  git mv "src/test/java/com/kista/adapter/in/web/$f.java" "src/test/java/com/kista/admin/adapter/in/web/$f.java"
done
for f in AppErrorLogPersistenceAdapterTest AuditLogPersistenceAdapterTest AuditLogPersistenceAdapterIT; do
  git mv "src/test/java/com/kista/adapter/out/persistence/audit/$f.java" "src/test/java/com/kista/admin/adapter/out/persistence/audit/$f.java"
done
rmdir src/test/java/com/kista/adapter/out/persistence/audit
for f in RuntimeSettingsPersistenceAdapterTest RuntimeSettingsPersistenceAdapterIT; do
  git mv "src/test/java/com/kista/adapter/out/persistence/settings/$f.java" "src/test/java/com/kista/admin/adapter/out/persistence/settings/$f.java"
done
```

- [ ] **Step 3: package 선언 + import 일괄 치환 (이동 파일)**

```bash
find src/main/java/com/kista/admin/adapter src/test/java/com/kista/admin/adapter -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.adapter\.in\.web\.dto;/package com.kista.admin.adapter.in.web.dto;/' \
  -e 's/^package com\.kista\.adapter\.in\.web;/package com.kista.admin.adapter.in.web;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.audit;/package com.kista.admin.adapter.out.persistence.audit;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.settings;/package com.kista.admin.adapter.out.persistence.settings;/' \
  -e 's/com\.kista\.domain\.model\.admin\./com.kista.admin.domain.model./g' \
  -e 's/com\.kista\.domain\.model\.settings\.\(RuntimeSettings\|StrategyCreationSettings\|StrategyFieldSettings\|RecurringMode\|BenchmarkSettings\|BenchmarkFieldSettings\)/com.kista.admin.domain.model.\1/g' \
  -e 's/com\.kista\.application\.usecase\.\(AdminQueryUseCase\|AdminReorderUseCase\|AdminStrategyUseCase\|AdminTradeCorrectionUseCase\|AdminUserUseCase\|AdminSettingsUseCase\|RuntimeSettingsUseCase\)/com.kista.admin.application.usecase.\1/g' \
  -e 's/com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)/com.kista.admin.application.port.output.\1/g' \
  -e 's/com\.kista\.adapter\.in\.web\.dto\.\(AdminAccountItem\|AdminAccountResponse\|AdminDashboardResponse\|AdminManualTradeCorrectionRequest\|AdminPrivacyBaseResponse\|AdminReorderRequest\|AdminReorderResponse\|AdminRoleRequest\|AdminSettingsRequest\|AdminStatusRequest\|AdminStrategyResponse\|AdminTradeCorrectionResponse\|AdminTradeResponse\|AdminUserResponse\|AnomaliesResponse\|AuditLogResponse\|ErrorLogResponse\|ReorderTimingAvailabilityResponse\|RuntimeSettingsResponse\|StrategyStatusRequest\)/com.kista.admin.adapter.in.web.dto.\1/g' \
  -e 's/com\.kista\.adapter\.in\.web\.AdminUserViews/com.kista.admin.adapter.in.web.AdminUserViews/g' \
  -e 's/com\.kista\.adapter\.out\.persistence\.audit\./com.kista.admin.adapter.out.persistence.audit./g' \
  -e 's/com\.kista\.adapter\.out\.persistence\.settings\.\(RuntimeSettingsEntity\|RuntimeSettingsJpaRepository\|RuntimeSettingsPersistenceAdapter\)/com.kista.admin.adapter.out.persistence.settings.\1/g' \
  {} +
```

와일드카드 확인:
```bash
git grep -n "^import com\.kista.*\*;" -- src/main/java/com/kista/admin/adapter src/test/java/com/kista/admin/adapter
```
`com.kista.domain.model.admin.*` / `com.kista.application.port.output.*` 등 잔존 와일드카드가 있으면 개별 치환이 커버 못 한 것 — 수동으로 `com.kista.admin.*` 로 교체 후 컴파일로 검증.

- [ ] **Step 4: 레거시 잔류 소비자 import 갱신 (Task 2 색출에 안 걸린 어댑터 참조 + ~42 test)**

```bash
# admin DTO / AdminUserViews / audit persistence / RuntimeSettingsEntity 를 참조하는 레거시 잔류 파일 색출
git grep -ln "com\.kista\.adapter\.in\.web\.dto\.\(Admin\|Audit\|ErrorLog\|RuntimeSettings\|Anomalies\|Reorder\|StrategyStatus\)\|com\.kista\.adapter\.in\.web\.AdminUserViews\|com\.kista\.adapter\.out\.persistence\.audit\.\|com\.kista\.adapter\.out\.persistence\.settings\.RuntimeSettings\|com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)\|com\.kista\.domain\.model\.admin\." -- src/main src/test \
  | grep -v "com/kista/admin/" | sort -u
```

이 목록(주로 `GlobalExceptionHandler`, `ErrorLogAspect`, `ClientErrorLogController`, `SettingsController`, `MetaController`, `StrategyPort`, `StrategyPersistenceAdapter`, `AdminUserViewAdapter` + `@MockitoBean AppErrorLogPort` test ~42개 + `@MockitoBean AdminUserViewPort`/`AuditLogPort`/`RuntimeSettingsUseCase` test)에 sed:

```bash
sed -i '' \
  -e 's#com\.kista\.domain\.model\.admin\.#com.kista.admin.domain.model.#g' \
  -e 's#com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)#com.kista.admin.application.port.output.\1#g' \
  -e 's#com\.kista\.application\.usecase\.\(AdminQueryUseCase\|AdminReorderUseCase\|AdminStrategyUseCase\|AdminTradeCorrectionUseCase\|AdminUserUseCase\|AdminSettingsUseCase\|RuntimeSettingsUseCase\)#com.kista.admin.application.usecase.\1#g' \
  -e 's#com\.kista\.adapter\.in\.web\.dto\.\(AdminAccountItem\|AdminAccountResponse\|AdminDashboardResponse\|AdminManualTradeCorrectionRequest\|AdminPrivacyBaseResponse\|AdminReorderRequest\|AdminReorderResponse\|AdminRoleRequest\|AdminSettingsRequest\|AdminStatusRequest\|AdminStrategyResponse\|AdminTradeCorrectionResponse\|AdminTradeResponse\|AdminUserResponse\|AnomaliesResponse\|AuditLogResponse\|ErrorLogResponse\|ReorderTimingAvailabilityResponse\|RuntimeSettingsResponse\|StrategyStatusRequest\)#com.kista.admin.adapter.in.web.dto.\1#g' \
  -e 's#com\.kista\.adapter\.in\.web\.AdminUserViews#com.kista.admin.adapter.in.web.AdminUserViews#g' \
  -e 's#com\.kista\.adapter\.out\.persistence\.audit\.#com.kista.admin.adapter.out.persistence.audit.#g' \
  -e 's#com\.kista\.adapter\.out\.persistence\.settings\.\(RuntimeSettingsEntity\|RuntimeSettingsJpaRepository\|RuntimeSettingsPersistenceAdapter\)#com.kista.admin.adapter.out.persistence.settings.\1#g' \
  <색출된 파일 목록>
```

와일드카드 소비자 재확인:
```bash
git grep -ln "^import com\.kista\.\(domain\.model\.admin\|application\.port\.output\)\.\*;" -- src/main src/test | grep -v "com/kista/admin/"
```
매치 시(예: 어떤 test가 `import com.kista.application.port.output.*;` 로 `AppErrorLogPort` 해석) 해당 파일에 `import com.kista.admin.application.port.output.AppErrorLogPort;` 명시 라인 추가.

- [ ] **Step 5: `SettingsController` 확인 (레거시 잔류인데 admin DTO 참조하나?)**

```bash
grep -n "^import" src/main/java/com/kista/adapter/in/web/SettingsController.java src/test/java/com/kista/adapter/in/web/SettingsControllerTest.java
```
admin DTO/usecase import이 있으면 위 sed가 처리(색출 목록 포함). 없으면 무변경. `SettingsController` 자체는 이동 안 함(Global Constraint 3).

- [ ] **Step 6: 전체 컴파일 + admin 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음. 실패 시 잔존 옛 경로 재검색:
```bash
git grep -n "com\.kista\.domain\.model\.admin\|com\.kista\.application\.service\.\(admin\|settings\)\|com\.kista\.application\.usecase\.\(AdminQuery\|AdminReorder\|AdminStrategy\|AdminTradeCorrection\|AdminUser\|AdminSettings\|RuntimeSettings\)UseCase\|com\.kista\.application\.port\.output\.\(AuditLogPort\|AdminUserViewPort\|AppErrorLogPort\|RuntimeSettingsPort\)\|com\.kista\.adapter\.in\.web\.\(Admin[A-Za-z]*Controller\|AdminUserViews\|RuntimeConfigController\)\|com\.kista\.adapter\.out\.persistence\.audit\|com\.kista\.adapter\.out\.persistence\.settings\.RuntimeSettings" -- src/main src/test | grep -v "com/kista/admin/"
```
Expected: 출력 없음.

```bash
./gradlew test --tests 'com.kista.admin.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`, admin 테스트 클래스(~25개) 전부 통과.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): admin 모듈 어댑터 이전 + audit·RuntimeSettings persistence 이전

Admin* 컨트롤러 11개 + RuntimeConfigController + AdminUserViews 헬퍼,
응답/요청 DTO 20개, audit persistence 6개, RuntimeSettings persistence 3개를
com.kista.admin으로 이전. AppErrorLogPort가 admin으로 옮겨가며 레거시 잔류
GlobalExceptionHandler/ErrorLogAspect/ClientErrorLogController + 전 CLOSED
모듈 @WebMvcTest 슬라이스(~42개)의 import 경로 갱신. UserSettings*/
UserNotificationPref* persistence 6개는 user 애그리게이트라 레거시 잔류.
전체 컴파일·admin 테스트 그린 확인.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 모듈 선언(`@ApplicationModule` CLOSED + NamedInterface) + `verify()` 게이트

> **이 태스크가 게이트인 이유:** 전이 순환은 `@ApplicationModule` 선언 후 `ApplicationModules.verify()`로만 관찰 가능하다(market `market→notify→trading→market`, privacy `privacy→notify→trading→privacy`, stats `stats↔notify` — 전부 pairwise 사전 분석이 놓쳤고 이 게이트에서 잡혔다). Task 1이 사전 실측된 `trading↔admin` 순환을 해소했지만, 놓친 전이 순환이 있으면 여기서 드러난다.

**Files:**
- Create: `src/main/java/com/kista/admin/package-info.java`
- Create: `src/main/java/com/kista/admin/domain/model/package-info.java`
- Create: `src/main/java/com/kista/admin/application/usecase/package-info.java`
- Create: `src/main/java/com/kista/admin/application/port/output/package-info.java`

**Interfaces:**
- Produces: `com.kista.admin` `@ApplicationModule` (CLOSED, 기본값) + "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface. `application.service`·`adapter.*` 전부 internal.
- Consumes: 없음 (선언만).

- [ ] **Step 1: package-info 4개 작성**

```java
// src/main/java/com/kista/admin/package-info.java
// admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정) 모듈 —
// domain.model·application.{usecase,port.output}만 공개 계약, application.service·adapter 전체 internal.
// notify 직접 호출 0건이라 event NamedInterface 없음. AdminSchedulerController는 trading/stats "schedule"의
// 소비자지 생산자가 아니라 adapter NamedInterface도 없음.
@org.springframework.modulith.ApplicationModule
package com.kista.admin;
```

```java
// src/main/java/com/kista/admin/domain/model/package-info.java
// admin 모듈의 공개 계약 일부 — 불변 값 객체(record/enum). 관리자 read-model(AdminUserView/AdminStats 등),
// 감사·오류 로그 도메인(AuditLog/AppErrorLog), 런타임 설정(RuntimeSettings 트리). "domain" 이름으로 공개된다.
// AdminUserView.status/role의 User.UserStatus/UserRole 참조와 RuntimeSettings의 Strategy/Account nested enum
// 참조는 user/strategy-config/account가 아직 레거시라 forward — 각 모듈 CLOSED 전환(step 3/4) 시 정리 예정.
@org.springframework.modulith.NamedInterface("domain")
package com.kista.admin.domain.model;
```

```java
// src/main/java/com/kista/admin/application/usecase/package-info.java
// admin 모듈의 공개 계약 일부 — UseCase/Query 인터페이스. "usecase" 이름으로 공개된다.
// (GetUserSettingsQuery는 user 애그리게이트라 레거시 잔류 — 이 패키지 아님.)
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.admin.application.usecase;
```

```java
// src/main/java/com/kista/admin/application/port/output/package-info.java
// admin 모듈의 공개 계약 일부 — *Port 접미사 출력 포트. "port" 이름으로 공개된다.
// AuditLogPort/AppErrorLogPort/AdminUserViewPort/RuntimeSettingsPort. AdminUserViewPort는 미래 user 모듈이,
// RuntimeSettingsPort는 미래 account/strategy-config/user가 구현·소비 — 현재는 레거시 forward.
@org.springframework.modulith.NamedInterface("port")
package com.kista.admin.application.port.output;
```

- [ ] **Step 2: `ModulithArchitectureTest` — 순환 검증**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | tail -60
```
Expected: 통과. `ApplicationModules.verify()`가 `Cycle detected`를 보고하면:
- `admin -> trading -> admin` 만 남았으면 Task 1 매핑 누락 — `git grep -n "com\.kista\.admin" src/main/java/com/kista/trading` 재확인.
- **예측 못한 3번째 모듈이 순환에 끼어있으면(예: `admin -> X -> ... -> admin`) 즉시 멈추고 보고** — 이 세션의 사전 실측이 놓친 케이스이므로 추측으로 고치지 않는다(market/privacy/stats 전례).
- "non-exposed type" 경고: 레거시 소비자가 admin의 비공개 패키지(`application.service`/`adapter.*`) 타입을 참조하면 발생. 사전 실측상 레거시 참조는 전부 공개 대상(`domain.model`/`usecase`/`port`)만 — 발생하면 해당 소비자 조정 후 재실행. `AdminSchedulerController`(admin `adapter.in.web`, internal)를 레거시가 참조하지 않는지도 확인(사전 실측: `AdminSchedulerController`의 소비자는 없음 — 그 자신이 trading/stats 스케쥴러를 주입하는 쪽).

- [ ] **Step 3: `HexagonalArchitectureTest` + 전체 아키텍처 스위트**

```bash
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -40
```
Expected: 전부 통과. `HexagonalArchitectureTest`가 `importPackages("com.kista")`로 test 클래스를 포함하므로, ~42개 CLOSED 모듈 test가 `com.kista.admin.application.port.output.AppErrorLogPort`(interface, `..application.port.output..`)를 import하는 것이 레이어 규칙(인바운드 어댑터 → `application.service` 금지)에 안 걸리는지 확인. 걸리면(예상 밖) 규칙 문구/스코프를 보고.

- [ ] **Step 4: 전체 컴파일 재확인 + admin/영향 모듈 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.admin.*' --tests 'com.kista.application.service.strategy.*' --tests 'com.kista.application.service.user.*' --tests 'com.kista.application.service.account.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, 전부 통과.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): admin 모듈 선언(@ApplicationModule CLOSED)

com.kista.admin을 8번째 CLOSED 모듈로 선언 — domain·usecase·port 3개
NamedInterface 공개, application.service·adapter 전체 internal. notify 직접
호출 0이라 event 없음. ApplicationModules.verify()·HexagonalArchitectureTest
그린 확인 — trading↔admin 순환은 Task 1(trading own-type)에서 사전 해소됨.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 문서 갱신 + 전체 테스트 스위트 최종 검증

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`
- Modify: `README.md` (드리프트 시)

**Interfaces:** 없음(문서 전용, 코드 변경 없음)

- [ ] **Step 1: architecture.md에 admin 모듈 절 추가**

기존 `market`/`privacy`/`stats` 절과 동일 형식으로 `com.kista.admin/` 트리 구조(위 "File Structure")와 NamedInterface 구성("domain"/"usecase"/"port")을 기술. 포함할 내용:
- adapter NamedInterface 없음(스케쥴러 생산 안 함, notify 이벤트 없음) — market/stats와 다른 점
- **step-3/4 IOU 명시**: ① `AdminUserView.status`/`role` + `AdminUserViewPort.findAllByStatus`가 `User.UserStatus`/`UserRole`(레거시) 직접 참조 — user 모듈 CLOSED 전환 시 own-type 또는 `User` nested enum sharedkernel 이관과 함께 정리. ② `UserService`/`AccountService`/`StrategyService`가 `RuntimeSettingsPort`(admin "port") 소비 — 미래 `user`/`account`/`strategy-config` → admin forward, step 3/4에서 이벤트/own-read로 전환 검토. ③ `AdminUserViewAdapter`(레거시→미래 user)가 `AdminUserViewPort` 구현, `StrategyPersistenceAdapter`(레거시→미래 strategy-config)가 `AdminCycleStrategySummary` 생산 — 정상 포트 역전(구현자가 admin "port"/"domain" 소비)
- **trading own-type 기록**: `trading.domain.strategy.{StrategyCreationSettings,StrategyFieldSettings,RecurringMode}`는 admin `RuntimeSettings` 트리의 trading 자체 복제본, `StrategyService.resolveCreationSettings()`가 경계 매핑(broker `Direction`/privacy `PrivacyOrderType` 패턴)
- `Spring Modulith 점진 도입` 절의 "→ stats✅(7번째)" 뒤에 "→ admin✅(8번째)" 추가. 이전 과정에서 `trading↔admin` 순환 1건이 사전 실측 발견돼 trading own-type로 해소, `admin→notify` 0건이라 이벤트 전환 태스크는 없었음을 market/privacy/stats와 같은 줄에 요약

레거시 절에서 제거할 언급:
- `domain/model/` 서술의 `admin`·`settings` 서브패키지
- `application/usecase`/`application/port/output` 레거시 개수에서 admin 7 usecase + 4 port 차감(옛 수치 근거 서술이 있으면)
- `application/service/` 서술의 `admin`·`settings` 서브패키지
- `adapter/in/web` 컨트롤러 나열에서 `Admin*`·`RuntimeConfig` 제거(단 `AdminPrivacyTradeController`는 privacy 절에서 "admin 소유 레거시 잔류"로 서술돼 있으니 "admin 모듈로 이전"으로 갱신, `SettingsController`·`ClientErrorLogController`는 레거시 유지로 남김)
- `adapter/out/persistence` 서술의 `audit`, `settings`(단 settings는 `UserSettings*`/`UserNotificationPref*`가 레거시 잔류하므로 "RuntimeSettings 3개만 admin 이전, UserSettings 계열은 잔류"로 정정)
- `GlobalExceptionHandler`/`ErrorLogAspect` 서술에 `AppErrorLogPort`가 이제 admin 소유임을 반영(있으면)

- [ ] **Step 2: constraints.md — "Spring Modulith 이전 중 신규 파일 배치"에 admin 추가**

market/privacy/stats 문단과 같은 형식으로: "admin 애그리게이트(관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정)는 `com.kista.admin`으로 이미 옮겨졌다 — 신규 관련 코드도 레거시 최상위가 아닌 `com.kista.admin` 안에 추가. `domain/model`이 "domain", `application/usecase`가 "usecase", `application/port/output`이 "port"로 NamedInterface 공개 — `application/service`·`adapter/*`는 비공개(internal). event/adapter NamedInterface 없음(notify 직접 호출 0, 스케쥴러 생산 안 함). `SettingsController`·`ClientErrorLogController`·`GetUserSettingsQuery`·`UserSettings*`/`UserNotificationPref*` persistence는 user 애그리게이트라 레거시 잔류. `AdminUserView`/`RuntimeSettings`의 `User`/`Strategy`/`Account` nested enum 참조는 step 3/4 정리 대상."
+ "모듈 경계 포트 시그니처" 절에 trading own-type 사례 한 줄 추가: "settings 사례(동일 패턴): trading `*CreationResolver`가 `domain/model/settings`(→admin) `StrategyCreationSettings`/`StrategyFieldSettings`/`RecurringMode`를 빌려쓰던 것을 trading `com.kista.trading.domain.strategy` 자체 소유로 끊었고 `StrategyService.resolveCreationSettings()`가 매핑(`RecurringMode`는 `valueOf(name())`, 상수명 byte-identical)."

- [ ] **Step 3: 스펙 문서에 완료 표시 + 정정**

`docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`:
- "착수 순서 (실측 기반, v2)" 1단계 admin 옆에 "✅ 완료(2026-09-01, `2026-09-01-modulith-admin-migration` 실행 계획 5개 태스크)" 각주. CLOSED 8번째, 3개 NamedInterface("domain"/"usecase"/"port")
- "결합도 실측 — 이미 CLOSED 4모듈과의 순환" 표 admin 행 정정: "없음" → 실측 결과 `trading↔admin` 순환 실재(`*CreationResolver` 4파일이 `domain/model/settings` 소비 ↔ admin이 trading `Order`/`StrategyCycle` 소비). Task 1에서 trading own-type로 해소(broker `Direction`/privacy `PrivacyOrderType` 3번째 인스턴스). pairwise가 놓친 게 아니라 "settings를 admin으로 옮기면 방향이 생긴다"는 stats [^5]와 동류 — 이동 대상 타입의 backward 참조 재확인 원칙 재확인
- "결합도 실측 — 후보 간 순환" 표 `user↔admin` 행 정정: "settings 재분류로 해소"는 부정확 — `RuntimeSettingsService` 외에 `AdminUserViewAdapter implements AdminUserViewPort`(미래 `user→admin`) + `UserService.runtimeSettingsPort`(미래 `user→admin`, settings를 admin으로 옮겨도 안 사라짐)도 있음. 셋 다 현재 user 레거시 OPEN이라 하드 순환 아니지만 step 3에서 own-type/이벤트 처리 필요. 스펙 v1이 순환에 대해 틀린 4번째 사례
- "목표 아키텍처 7모듈 카탈로그" admin 항목: "settings 흡수"를 "settings API/service/persistence(RuntimeSettings 계열)만 흡수 — `domain/model/settings` 6타입 중 trading 소비 3개는 trading own-type, `UserSettings*`는 user 몫으로 레거시 잔류, `SettingsController`는 user 셀프서비스라 레거시 잔류"로 정밀화

- [ ] **Step 4: README.md drift 확인**

```bash
grep -n "admin\|Admin\|settings\|Settings\|RuntimeSettings\|audit\|Audit\|AppErrorLog" README.md
```
매치가 옛 패키지 경로(`com.kista.adapter.in.web.Admin*` 등)나 아키텍처 다이어그램 속 클래스/패키지명이면 갱신. 매치 없거나 무관하면 스킵.

- [ ] **Step 5: 전체 테스트 스위트 최종 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`, 실패 0. (stats 완료 시점 1833개 — 이번은 순수 이동 + trading own-type 3클래스(테스트 없이 기존 resolver 테스트가 커버) + AdminUserView 무변경이라 총 개수는 거의 불변.)

XML 교차확인:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```
Expected: 출력 없음.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): admin 모듈 이전 반영 — architecture.md/constraints.md/스펙 갱신

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 메모 (계획 작성자 기준)

- **스펙 커버리지**: 스펙 "착수 순서 v2" 1단계 잔여분(admin) 전체 커버 — admin 서비스 8 + domain 16(admin 10 + settings 6) + usecase 7 + port 4 + 컨트롤러 11 + DTO 20 + audit persistence 6 + RuntimeSettings persistence 3 = 이동 ~75 main 파일 + ~30 test 파일. **스펙과의 3가지 명시적 편차**(Global Constraints 1~3): ① settings 도메인 3타입은 trading own-type(순환), ② SettingsController 레거시 잔류(user 애그리게이트), ③ user↔admin 원인이 스펙 주장보다 많음(step-3 IOU). 전부 사전 실측 근거로 문서화, Task 5에서 스펙 정정.
- **placeholder 스캔**: Task 1 Step 2의 `StrategyFieldSettings`/`StrategyCreationSettings` compact 생성자 본문은 "레거시 원본 복사"로 위임 — 실행자가 Step 1 `cat` 출력을 그대로 복제(package·주석만 차이). 이건 순수 복제라 전체 재기술이 오히려 오류원. 나머지 Step은 실행 가능한 정확한 명령/경로.
- **타입 일관성**: Task 1이 만든 `com.kista.trading.domain.strategy.{StrategyCreationSettings,StrategyFieldSettings,RecurringMode}` 3개가 Task 2에서 `StrategyService` 매핑 소스 경로만 `com.kista.domain.model.settings` → `com.kista.admin.domain.model`로 바뀔 뿐 재사용. Task 2가 만든 `com.kista.admin.{domain.model,application.usecase,application.port.output}` FQN이 Task 3(어댑터)·Task 4(package-info)에서 동일 재사용. `AuditLogPort`/`AppErrorLogPort`/`AdminUserViewPort`/`RuntimeSettingsPort` 이름이 Task 2 이동·Task 3 소비자 갱신·Task 4 NamedInterface 문구에서 일치.
- **순환 리스크**: 사전 실측으로 `trading↔admin` 1건 특정 → Task 1 해소. `admin→notify` 0건 확인 → `X→notify→trading→X` 전이 순환 패턴 구조적 불성립(market/privacy/stats와 다름). pairwise 맹점 대비 — Task 4 Step 2에서 `verify()`가 예측 못한 순환 보고 시 즉시 중단 규정 명시.
- **와일드카드**: `AdminQueryService.java:15`(3-import 한 줄) = Task 2 Step 4 전용 처리. `StrategyService.java:12`/`UserService.java:10`(`com.kista.application.port.output.*`) = 와일드카드 유지 + `RuntimeSettingsPort` 명시 라인 추가(Task 2 Step 5). Task 3 Step 3/4에서 이동·잔류 양쪽 와일드카드 재확인 스텝 포함.
- **문자열 리터럴 FQN**: Task 2 Step 0 / Task 3 Step 0에서 resources(yml/xml Logback 로거) + java 문자열 스캔. 사전 실측 0건이나 각 태스크에서 재확인.
- **AppErrorLog 42-test 파장**: Global Constraint에 명시, Task 3 Step 4에서 색출 sed로 일괄. `ModulithArchitectureTest`는 main-only라 하드 순환 아님(Task 4 Step 2/3에서 실증).
- **audit_logs 스키마**: `@Table(schema="public")` 3개 엔티티(audit_logs/app_error_logs/admin_runtime_settings) 사전 실측 확인 — 이동해도 스키마 명시 유지되므로 `ddl-auto: validate` 무영향.
