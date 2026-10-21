# Spring Modulith user(+auth) 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 흩어진 user 애그리게이트(가입·승인·설정)와 auth 애그리게이트(JWT·RT·블랙리스트·카카오 OAuth)를 신규 `com.kista.user` Spring Modulith `CLOSED` 모듈로 이전한다.

**Architecture:** finance/notify/broker/trading/market/privacy/stats/admin 8모듈 이전과 동일 패턴 — 모듈 내부는 기존 Hexagonal 레이어(`domain/application/adapter`)를 그대로 유지하고 최상위 패키지만 `com.kista.user`로 옮긴다. 순수 구조 이전이라 TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클. user는 CLOSED 전환 시 **4개 모듈 순환**(user↔notify는 실측 재확인 결과 순환 아님으로 정정, user↔admin/user↔trading/user↔finance가 실순환)이 사전 실측으로 확인됐으므로, 물리 이전 **전에** 순환을 끊는 코드 변경 태스크 3개(Task 1~3)를 먼저 둔다. 이 프로젝트에서 admin에 이어 두 번째로 "이전 선언 전 사전 해소"에 성공한 사례다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` ("착수 순서 v2" 3단계 = user(+auth)) — 단 스펙의 순환 예측("user는 notify만 순환")은 사전 실측 결과 대부분 틀렸다(admin/stats/privacy/market 때도 매번 스펙이 틀렸던 패턴의 5번째 반복). 이 계획의 "Global Constraints"가 스펙을 대체하는 실측 SSOT다.

## Global Constraints

### 실측 순환 4건과 그 판정

사전 조사(3개 병렬 서브에이전트 + 컨트롤러 직접 코드 확인)로 다음을 확정했다:

1. **user↔notify — 순환 아님(스펙과 반대 결론)**: notify의 `UserNotificationPort`(13개 메서드, 전부 `User`를 파라미터로 받음)는 notify 내부 게이트웨이(`CompositeUserNotificationAdapter`/`TelegramUserNotificationAdapter`/`FcmAdapter`)가 서로를 호출하는 구조다. 외부에서 이 포트를 호출하는 유일한 코드는 `com.kista.finance.application.service.FinanceRegistrationReminderNotifier`(finance 모듈 소속, `notifyFinanceRegistrationReminder(user, month)` 1건)이며 이는 `finance→notify` 엣지이지 `user→notify`가 아니다. user 파일 집합 안에서 notify를 참조하는 곳은 `UserProfileService`(`FcmDeviceTokenPort`/`TelegramBotInfoPort`, `user→notify` forward)와 `RefreshTokenCleanupScheduler`(`NotifyPort`, `user→notify` forward)뿐이고, notify가 user를 참조하는 곳(`TelegramUserNotificationAdapter`/`CycleEndedNotifier`/`CycleLifecycleNotifier`/`TradingAlertNotifier`/`TradingReportNotifier`/`UserDeletedNotifier`가 `UserPort`로 재조회, `TelegramBotService`가 `UserUseCase.approve/reject/findUserIdByTelegramChatId` 호출, `notify→user`)는 이미 EPR 패턴(ID만 담은 이벤트 + 포트 재조회)이라 **User 타입 자체가 시그니처에 없다** — user가 "port"/"usecase" NamedInterface로 `UserPort`/`UserUseCase`를 공개하면 이건 정상 단방향 forward다. **`UserNotificationPort`는 손대지 않는다** — 13개 메서드의 `User user` 파라미터를 ID로 바꾸는 재설계는 스코프 아웃(순환 해소에 불필요, notify는 어차피 `UserPort`로 채널 정보(`notificationChannel`)를 포함한 전체 `User`가 필요해 ID화하면 오히려 매 메서드에서 재조회가 늘어난다).
2. **user↔trading**: `user→trading`은 `UserCascadeDeleter`가 `cyclePositionPort.deleteByUserId(userId)`/`strategyCyclePort.deleteByUserId(userId)`를 직접 호출(둘 다 trading 소유 포트). `trading→user`는 `MarketEventNotifier`/`TradingReporter`/`CycleRotationService`가 `User`/`UserSettings`/`UserPort`/`UserSettingsPort`/`NotificationType`을 참조하고, `BatchContext`(trading domain record)가 `User user` 필드를 가지며 `TradingExecutionUseCase.execute(Strategy, Account, User)`가 `User`를 파라미터로 받는다(단, 이 3-arg `execute`의 실제 호출자는 `TradingExecutionFacade.execute()` 내부 1곳뿐이고 컨트롤러/스케쥴러는 전부 `executeBatch(List<BatchContext>)` 계열만 씀 — `TradingExecutionUseCase` 인터페이스 시그니처 자체는 존치, 손대지 않는다).
3. **user↔finance**: `user→finance`는 `UserCascadeDeleter`가 `FinanceGroup`/`FinanceGroupMember` 도메인 타입 + `FinanceTransactionPort`/`AssetSnapshotPort`/`FinanceAccountPort`/`FinanceCategoryPort`/`FinanceBudgetPort`/`FinanceGroupPort` 6개 포트를 직접 호출(가장 무거운 cascade 경로, 그룹 소유권 승계 로직 포함). `finance→user`는 `FinanceRegistrationReminderNotifier`가 `User`/`User.UserStatus`/`UserSettings`/`NotificationType`/`UserPort`/`UserSettingsPort`를 참조.
4. **user↔admin**: `user→admin`은 `UserService.register()`/`reapply()`가 `RuntimeSettingsPort.loadForUpdate().approvalRequired()`를 비관적 락으로 읽는다(가입 승인 정책 확인, 락 필수 — 동시 승인설정 전환과 직렬화). `admin→user`는 셋: ① `AdminService`가 user 모듈 **internal** 클래스인 `UserCascadeDeleter`를 필드로 직접 주입(다른 CLOSED 모듈 어디에도 없는 캡슐화 위반 — 순환이기 이전에 그 자체로 문제), ② `AdminService`/`AdminQueryService`가 `User.UserStatus`/`User.UserRole` + `UserPort`/`UserUseCase`/`BlacklistPort` 참조, `RuntimeSettingsService`가 `UserPort.findAllByStatus(PENDING)` + `UserUseCase.approve()` 반복 호출(승인설정 OFF 전환 시 대기자 일괄 승인), ③ `AdminUserView.status`/`role`이 `User.UserStatus`/`User.UserRole`을 필드 타입으로 가지고 `AdminUserViewPort.findAllByStatus(User.UserStatus)`가 파라미터로 받으며, 이 포트를 구현하는 `AdminUserViewAdapter`(현재 레거시 `adapter/out/persistence/user/`, `UserJpaRepository`를 직접 쓰므로 이번에 user로 이동)가 admin의 `AdminUserViewPort`를 구현 — user가 admin "port" NamedInterface를 구현하는 정상 포트 역전이지만 위 ①②와 합쳐지면 다방향 순환.

### 순환 해소 방향 (사용자 승인 완료)

- **cascade는 전부 이벤트 팬아웃으로 전환** — `UserCascadeDeleter`의 trading 2포트 + finance 6포트 직접 호출을 제거하고, 기존 `UserDeletedEvent`(이미 발행되고 있음, 현재 리스너는 notify의 `UserDeletedNotifier` 하나뿐)를 trading·finance가 각각 `@TransactionalEventListener(phase = AFTER_COMMIT)`로 구독해 자기 소유 데이터를 스스로 soft-delete하게 만든다. 이 프로젝트에서 이미 확립된 EPR 인프라(`event_publication` 테이블, 재기동 시 자동 재시도)를 그대로 탄다. **트랜잭션 의미가 단일 트랜잭션 cascade에서 다중 트랜잭션 최종일관성으로 바뀐다** — 리스너가 실패하면 EPR이 재기동 시 재시도하지만, 그 사이 user 행은 이미 삭제되고 trading/finance 잔존 데이터가 소프트 삭제 대기 상태로 남을 수 있다(사용자 승인 사항). account/strategy-config는 레거시 OPEN이라 순환이 아니므로 **이번엔 그대로 직접 호출 유지**(4단계 account/strategy-config 이전 때 함께 이벤트화 검토).
- **admin↔user는 포트 역전 + 캡슐화 정리로 해소**: ① `AdminService`가 `UserCascadeDeleter`를 직접 참조하는 대신 `UserUseCase.deleteMe(userId)`(이미 존재하는 usecase 메서드, "usecase" NamedInterface로 공개될 예정)를 호출하도록 변경. ② user가 신규 아웃바운드 포트 `ApprovalPolicyPort`(1메서드: `boolean approvalRequiredForUpdate()`, 비관적 락 조회)를 자체 정의하고 admin의 `RuntimeSettingsService`가 이를 구현 — `RuntimeSettingsPort`를 admin이 공개하되 user는 이제 이 신규 포트만 쓰고 admin 패키지를 직접 참조하지 않는다(broker의 `MockSimulationDataPort` 패턴과 동일: 데이터를 필요로 하는 쪽이 포트를 정의하고, 데이터를 가진 쪽이 구현). ③ `RuntimeSettingsService`가 대기자 일괄 승인 때 쓰는 `UserPort.findAllByStatus`/`UserUseCase.approve`는 user가 "port"/"usecase" NamedInterface로 공개하면 정상 forward이므로 그대로 둔다. ④ `AdminUserView.status`/`role` + `AdminUserViewPort.findAllByStatus` 파라미터를 sharedkernel로 이관된 `UserStatus`/`UserRole`로 교체(Task 1 선행) — admin이 이제 sharedkernel만 참조, user 직접 참조 소거.
- 이 세 방향(cascade 이벤트화, 포트 역전, sharedkernel enum) 전부 처리하면 남는 엣지는: `admin→user`(`AdminService`가 `UserUseCase.deleteMe`/`UserPort`/`UserUseCase.approve`/`BlacklistPort` 소비), `trading→user`(`MarketEventNotifier` 등이 `UserPort`/`UserSettingsPort` 소비), `finance→user`(`FinanceRegistrationReminderNotifier`가 `UserPort`/`UserSettingsPort` 소비), `notify→user`(위 1번 그대로) — 전부 단방향. `user→admin`/`user→trading`/`user→finance` 직접 참조는 소거된다.

### sharedkernel enum 추출 (스펙 "nested enum 정책 개정" 이행)

- `User`의 nested enum 3개(`UserRole{USER,ADMIN}`, `UserStatus{PENDING,ACTIVE,REJECTED}`, `NotificationChannel{NONE,TELEGRAM,FCM,ALL}` + `tryParse`/`includesTelegram`/`includesFcm` 헬퍼)와 `domain/model/user/NotificationType`(`{TRADING_ALERT,MARKET_ALERT,FINANCE_REMINDER}`, 독립 파일)을 새 최상위 비모듈 패키지 `com.kista.sharedkernel`로 추출한다. 기존 `com.kista.common`(`TimeZones`/`Sha256`/`UsTradeDates`/`CycleLookups`, 이미 `@ApplicationModule(Type.OPEN)`)에 합치지 않고 **별도 패키지**로 만드는 이유: `common`은 기술 유틸(Modulith 공식 문서상 "shared"), sharedkernel enum은 여러 애그리게이트가 합의한 도메인 어휘라 스펙이 이미 이 명칭·구분을 못박아 두었다(`docs/agents/constraints.md` "Account ↔ Strategy 분리" 절도 향후 `Strategy.Ticker`/`Type`/`Status`/`CycleSeedType`를 `com.kista.sharedkernel`로 옮길 계획이라 명시 — 같은 패키지를 재사용해 4단계에서 합류시킨다).
- `com.kista.sharedkernel`도 `common`과 동일하게 `@ApplicationModule(type = Type.OPEN)`으로 선언한다(진짜 모듈이 아니라 outbound reference 0인 순수 값 타입 보관소이므로 CLOSED 선언 대상이 아님, 향후 다른 sharedkernel 후보가 늘어나며 자연스럽게 채워짐).
- DB `@Enumerated(EnumType.STRING)` 3개 컬럼(`UserEntity.status`/`role`/`notificationChannel`)과 `NotificationType`(`user_notification_prefs.type`, VARCHAR + `valueOf` round-trip)은 상수명 **byte-identical** 유지 필수 — 패키지만 옮기고 이름·값 순서·상수명은 절대 변경하지 않는다.
- `User.DEFAULT_CHANNEL` 상수는 `User` record에 그대로 유지(참조하는 `NotificationChannel.NONE`만 새 패키지에서 import) — 굳이 옮길 이유 없음. `User.CooldownException`은 enum이 아니므로 sharedkernel 이관 대상 아님, `User` 도메인에 유지.
- 이 추출로 admin의 `AdminUserView`/`AdminUserViewPort`/`AdminRoleRequest`/`AdminStatusRequest`/`AdminUserResponse`/`AdminUserController`(`:34`, `@RequestParam User.UserStatus`)는 `User` 직접 참조를 완전히 제거하고 sharedkernel만 참조하게 된다 — admin이 남긴 IOU(architecture.md step-3 언급) 정산.

### 기존 결함 정정: FCM 디바이스 토큰 cascade 누락

- `UserCascadeDeleter.deleteCascade()`를 코드로 직접 읽은 결과, 탈퇴 cascade 목록(포지션→사이클→전략→계좌→재무→그룹→사용자→RT→블랙리스트)에 **FCM 디바이스 토큰 삭제가 없다** — 이번 이전 스코프에 포함해 함께 고친다(기존에도 있던 결함이지 이번 리팩터가 만든 결함 아님, "기존 오류 발견 시 즉시 수정" 원칙 적용). `notify.application.port.output.FcmDeviceTokenPort`에 `deleteAllByUserId(UUID userId)` 메서드를 신설하고, user가 발행하는 `UserDeletedEvent`를 notify가 구독해(신규 리스너 `UserFcmCleanupListener`) 삭제하도록 한다 — cascade 직접 호출이 아니라 이미 채택한 이벤트 패턴을 그대로 재사용(notify는 어차피 user가 발행하는 이벤트를 구독하는 게 정상 방향).

### FCM 컨트롤러 리팩터 (사용자 결정)

- `FcmController`는 레거시 `adapter/in/web`에 남되(user 애그리게이트가 아니라 순수 알림 채널 관리), `UserProfileUseCase.registerFcmToken`/`unregisterFcmToken`으로 위임하던 것을 notify의 `FcmDeviceTokenPort`(`save`/`delete`)를 직접 주입해 호출하도록 변경한다. `UserProfileUseCase`/`UserProfileService`에서 `registerFcmToken`/`unregisterFcmToken` 메서드를 삭제한다.

### 이동/유지 경계

**MOVE 대상(레거시 잔류 없음 — 이번이 이전 admin/stats 계획과 다른 점)**: 아래 "File Structure" 절이 SSOT. `SettingsController`/`ClientErrorLogController`/`UserSettings*`/`UserNotificationPref*`/`AdminUserViewAdapter`/`AdminConfig`/`AdminBootstrapProperties` 전부 이번에 `com.kista.user`로 이동한다(admin 계획 때는 user 모듈이 아직 없어 레거시 잔류였던 것들의 최종 목적지).

**레거시 잔류(이번 스코프 아님)**: `FcmController`+`FcmTokenRequest`(위 리팩터 후에도 잔류, notify 포트를 직접 쓰는 순수 web 어댑터), `GlobalExceptionHandler`(user 예외 3종 매핑을 계속하되 import 경로만 갱신), `ErrorLogAspect`(무관, notify 포인트컷), `AccountService`/`StrategyService`(user 포트 소비는 유지, import 경로만 갱신), `SseEmitterRegistry`(`RealtimeNotificationPort` 구현체, `User.UserStatus` 참조는 sharedkernel로 자동 정리), `MetaController`(사전 확인: user/auth enum 직렬화 0건, 무변경).

### 사전 실측: 문자열 리터럴 FQN / YAML / AOP — 0건

`grep -rn`으로 `src/main/resources/**`(`*.yml`) 및 Java 문자열 리터럴에서 `com.kista.domain.model.user`/`.auth`/`.adapter.in.web.security`/`.adapter.out.kakao`/`.adapter.out.persistence.user`/`.application.service.{user,auth}`/`JwtAuthFilter`/`SecurityConfig` 참조를 확인 — **0건**(Logback 로거는 `com.kista` 상위 prefix만 있어 하위 패키지 이동에 영향 없음, 유일한 AOP 포인트컷 `ErrorLogAspect`는 notify `NotifyPort` 전용이라 무관, `@ComponentScan`/`GroupedOpenApi` 등 패키지 하드코딩도 0건). 각 물리 이전 태스크에서 Step 0으로 재확인만 하고 매치 시 갱신.

### 와일드카드 import 사전 확인 (반복 실수 패턴 — 매 계획서 교훈)

사전 실측으로 다음 파일들에 와일드카드가 확인됨. 각 태스크에서 `git grep -n "^import com\.kista.*\*;"`로 재확인 후 명시 분리 처리한다:
- `UserService.java:11` — 한 줄에 `import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;` (두 번째는 실제로 아무 타입도 안 씀 — **dead import**, 코드 확인 완료. 이동 시 삭제)
- `UserCascadeDeleter.java:6` — 한 줄에 `import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;`, `:7`에 `import com.kista.finance.application.port.output.*;` (Task 3에서 trading/finance 포트 참조 자체가 사라지므로 이 두 와일드카드는 삭제, `com.kista.application.port.output.*`만 `AccountPort`/`RefreshTokenPort`/`BlacklistPort` 등 남은 legacy 참조용으로 유지)
- 대응 테스트 `UserServiceTest.java`/`UserCascadeDeleterTest.java`도 동일 패턴 — Task 5/Task 3에서 함께 처리

### BSD sed 함정 (필수 준수)

이 계획의 sed 명령 중 `\(A\|B\)` 형태 alternation은 GNU sed 전용 — macOS 기본 sed(BSD)는 `\|`를 리터럴로 취급해 **조용히 no-op**한다. 이 환경은 macOS(Darwin)이므로 모든 alternation 치환은 `perl -pi -e 's/.../.../g'`를 사용한다(아래 각 Step의 명령은 이미 `perl -pi -e`로 작성됨). 치환 직후 반드시 `git grep`으로 잔존 옛 경로 0건을 확인한다(각 Step에 포함).

### 공통 규칙

- 커밋 전 검토자(리뷰어 서브에이전트) 검수 필수 — 전역 CLAUDE.md 규칙, 문서 전용 태스크(Task 8) 제외.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit.
- `ApplicationModules.verify()` 게이트는 모듈 선언(Task 7) 시점에만 유효 — user가 `@ApplicationModule` 미선언인 동안은 레거시 OPEN의 일부로 취급돼 순환이 안 잡힌다. Task 1~3에서 순환 3건을 사전 해소했지만, pairwise 한계로 놓친 전이 순환이 있을 수 있다(market/privacy/stats 교훈 — 매번 최소 1건씩 실측에서 드러남). **Task 7에서 `verify()`가 예측 못한 순환을 보고하면 즉시 멈추고 보고**(추측 수정 금지).

---

## File Structure (최종 `com.kista.user` 트리)

```
com.kista.sharedkernel/                   ← 신규 비모듈 패키지 (Task 1)
  package-info.java                       ← @ApplicationModule(Type.OPEN)
  UserRole.java                           ← enum {USER, ADMIN}
  UserStatus.java                         ← enum {PENDING, ACTIVE, REJECTED}
  NotificationChannel.java                ← enum {NONE, TELEGRAM, FCM, ALL} + tryParse/includesTelegram/includesFcm
  NotificationType.java                   ← enum {TRADING_ALERT, MARKET_ALERT, FINANCE_REMINDER}

com.kista.user/
  package-info.java                       ← @ApplicationModule (Task 7)
  domain/
    model/                                ← "domain" NamedInterface (Task 7)
      User.java                           ← UserRole/UserStatus/NotificationChannel sharedkernel import, DEFAULT_CHANNEL·CooldownException 유지
      UserSettings.java                   ← NotificationType sharedkernel import
    auth/                                 ← "domain"과 함께 병합 공개 (Task 7)
      RefreshToken.java
      TokenRefreshResult.java             ← User.UserRole → sharedkernel UserRole
      TokenConstants.java
      InvalidRefreshTokenException.java
  application/
    usecase/                              ← "usecase" NamedInterface (Task 7) — 8개
      UserUseCase, UserProfileUseCase, GetUserSettingsQuery, UpdateBalanceCheckUseCase,
      UpdateNotificationPrefUseCase, UpdateStrategySuggestionsUseCase, BlacklistUseCase, TokenUseCase
    port/output/                          ← "port" NamedInterface (Task 7) — 6개
      UserPort, UserSettingsPort, BlacklistPort, KakaoOAuthPort, RefreshTokenPort, ApprovalPolicyPort(신규, Task 2)
    event/                                ← "event" NamedInterface (Task 7) — 5개
      NewUserRegisteredEvent, UserApprovedEvent, UserRejectedEvent, UserReappliedEvent, UserDeletedEvent
    service/                              ← internal — 6개
      UserService, UserProfileService, UserSettingsService, UserCascadeDeleter, TokenService, BlacklistService
  adapter/
    in/web/                               ← internal — 4개 컨트롤러
      AuthController, DevAuthController(local 전용), SettingsController, ClientErrorLogController
    in/web/dto/                           ← internal — 12개
      KakaoCallbackRequest, KakaoLoginResponse, RefreshResponse, TokenResponse, UserResponse,
      BalanceCheckRequest, NicknameRequest, NotificationChannelRequest, NotificationPrefRequest,
      StrategySuggestionsRequest, ClientErrorLogRequest, TelegramUpdateRequest
    in/web/security/                      ← internal — 7개 (전체 이동, 사용자 결정)
      JwtAuthFilter, JwtIssuerService, JwtDecoderConfig, InternalTokenAuthFilter, SecurityConfig,
      RefreshTokenCookieHelper, OpenApiConfig
    in/schedule/                          ← internal — RefreshTokenCleanupScheduler
    out/persistence/user/                 ← internal — UserEntity, UserJpaRepository, UserPersistenceAdapter, AdminUserViewAdapter
    out/persistence/settings/             ← internal — UserSettingsJpaEntity, UserSettingsJpaRepository,
      UserNotificationPrefJpaEntity, UserNotificationPrefId, UserNotificationPrefJpaRepository, UserSettingsPersistenceAdapter
    out/persistence/auth/                 ← internal — RefreshTokenEntity, RefreshTokenJpaRepository, RefreshTokenPersistenceAdapter
    out/kakao/                            ← internal — KakaoConfig, KakaoProperties, KakaoOAuthAdapter
    out/redis/                            ← internal — RedisBlacklistAdapter
  config/                                 ← internal — AdminBootstrapProperties(ADMIN_KAKAO_IDS seed, admin 모듈과 무관 — 이름만 겹침), AdminConfig
```

admin이 남긴 IOU 정산: `AdminUserViewAdapter`가 user로 이동하며 `AdminUserViewPort`(admin "port") 구현이 CLOSED↔CLOSED 정상 포트 역전이 된다. `RuntimeSettingsService`가 신규 `ApprovalPolicyPort`(user "port")를 구현하는 것도 동일 패턴(admin→user 방향, `MockSimulationDataPort`의 broker→trading 역방향 적용과 같은 선례).

### 레거시 잔류 (경로만 갱신 — user로 안 옮김)
- `com.kista.adapter.in.web.FcmController` + `com.kista.adapter.in.web.dto.FcmTokenRequest` — notify `FcmDeviceTokenPort` 직접 소비로 리팩터(Task 4)
- `com.kista.adapter.in.web.GlobalExceptionHandler` — `User.CooldownException`/`InvalidRefreshTokenException` import 경로 갱신
- `com.kista.adapter.out.sse.SseEmitterRegistry` — `User.UserStatus` → sharedkernel `UserStatus`, `UserApprovedEvent`/`UserRejectedEvent`(user "event") import 경로 갱신
- `com.kista.application.service.strategy.StrategyService`, `com.kista.application.service.account.AccountService` — `UserSettingsPort`/`UserPort`(user "port") 소비, import 경로만 갱신
- `com.kista.adapter.in.web.MetaController` — 사전 실측: user/auth enum 직렬화 0건, 무변경

---

## Task 1: sharedkernel enum 추출 — User nested enum 3개 + NotificationType 독립 타입화

> **배경:** admin의 `AdminUserView`/`AdminUserViewPort` 등이 `User.UserStatus`/`User.UserRole`을 직접 참조하는 걸 이번에 sharedkernel로 대체해야 admin이 user를 참조하지 않게 된다. trading의 `MarketEventNotifier` 등이 쓰는 `NotificationType`도 마찬가지. **User 도메인 record 자체는 아직 옮기지 않는다** — 이 태스크는 nested enum만 꺼낸다.

**Files:**
- Create: `src/main/java/com/kista/sharedkernel/package-info.java`
- Create: `src/main/java/com/kista/sharedkernel/UserRole.java`
- Create: `src/main/java/com/kista/sharedkernel/UserStatus.java`
- Create: `src/main/java/com/kista/sharedkernel/NotificationChannel.java`
- Move: `src/main/java/com/kista/domain/model/user/NotificationType.java` → `src/main/java/com/kista/sharedkernel/NotificationType.java`
- Modify: `src/main/java/com/kista/domain/model/user/User.java` (nested enum 3개 제거, import로 대체)
- Modify (import 경로만): 아래 Step 5가 색출하는 전체 소비 파일(admin 11개, finance 2개, trading 4개, notify 2개, legacy 다수, 테스트 다수)

**Interfaces:**
- Produces: `com.kista.sharedkernel.{UserRole,UserStatus,NotificationChannel,NotificationType}` — 이후 모든 태스크가 이 경로를 참조.
- Consumes: 없음(순수 값 타입, outbound reference 0).

- [ ] **Step 1: sharedkernel 패키지 생성 + package-info**

```bash
mkdir -p src/main/java/com/kista/sharedkernel
```

`src/main/java/com/kista/sharedkernel/package-info.java`:
```java
// 여러 애그리게이트가 합의한 전역 공용 어휘(ubiquitous vocabulary) — DDD Shared Kernel과 유사하되
// 순수 값 타입(enum)만 담는다. common/과 달리 기술 유틸이 아닌 도메인 개념이라 별도 패키지로 분리.
// outbound reference 0인 타입만 여기 둔다 — 이 패키지가 다른 모듈을 참조하는 순간 sharedkernel 전제가 깨진다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.kista.sharedkernel;
```

- [ ] **Step 2: enum 3개 신설 (User.java에서 그대로 복제 후 독립 파일화)**

`src/main/java/com/kista/sharedkernel/UserRole.java`:
```java
package com.kista.sharedkernel;

// 사용자 권한 — User 도메인 nested enum에서 sharedkernel로 이관(constraints.md "nested enum 정책 개정").
// 상수명 byte-identical 유지 필수 — UserEntity.role @Enumerated(STRING) DB 컬럼과 직결.
public enum UserRole { USER, ADMIN }
```

`src/main/java/com/kista/sharedkernel/UserStatus.java`:
```java
package com.kista.sharedkernel;

// 계정 상태 — User 도메인 nested enum에서 sharedkernel로 이관.
// 상수명 byte-identical 유지 필수 — UserEntity.status @Enumerated(STRING) DB 컬럼과 직결.
public enum UserStatus {
    PENDING,  // 관리자 승인 대기 중
    ACTIVE,   // 승인 완료, 서비스 이용 가능
    REJECTED  // 거절됨 (재신청 가능)
}
```

`src/main/java/com/kista/sharedkernel/NotificationChannel.java`:
```java
package com.kista.sharedkernel;

import java.util.Optional;

// 알림 수단 — User 도메인 nested enum에서 sharedkernel로 이관.
// 상수명 byte-identical 유지 필수 — UserEntity.notificationChannel @Enumerated(STRING) DB 컬럼과 직결.
public enum NotificationChannel {
    NONE,       // 알림 없음
    TELEGRAM,   // 텔레그램 봇 알림
    FCM,        // Firebase Cloud Messaging 푸시
    ALL;        // 텔레그램 + FCM 동시 발송

    // 안전한 파싱 — 대소문자 무시, 불일치 시 empty 반환
    public static Optional<NotificationChannel> tryParse(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean includesTelegram() { return this == TELEGRAM || this == ALL; }
    public boolean includesFcm()      { return this == FCM      || this == ALL; }
}
```

- [ ] **Step 3: NotificationType 이동 (패키지만 변경, 내용 동일)**

```bash
git mv src/main/java/com/kista/domain/model/user/NotificationType.java src/main/java/com/kista/sharedkernel/NotificationType.java
perl -pi -e 's/^package com\.kista\.domain\.model\.user;/package com.kista.sharedkernel;/' src/main/java/com/kista/sharedkernel/NotificationType.java
```

- [ ] **Step 4: User.java에서 nested enum 3개 제거 + import 대체**

`src/main/java/com/kista/domain/model/user/User.java` 수정 — 파일 상단 import 블록에 추가:
```java
import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
```

레코드 필드 타입 `UserStatus status` / `UserRole role` / `NotificationChannel notificationChannel`은 그대로 두되(nested 참조 `User.UserStatus` → 단순 `UserStatus`로 컴파일러가 자동 해석되진 않으므로), 본문에서 nested enum 정의 3블록(`public enum UserRole {...}`, `public enum UserStatus {...}`, `public enum NotificationChannel {...}` 전체, `tryParse`/`includesTelegram`/`includesFcm` 포함)을 삭제한다. `DEFAULT_CHANNEL` 상수와 `CooldownException`은 그대로 유지:

```java
package com.kista.domain.model.user;

import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,
        String kakaoId,
        String nickname,
        String email,
        UserStatus status,
        UserRole role,
        String telegramBotToken,
        String telegramChatId,
        String telegramBotUsername,
        String rejectReason,
        Instant lastReappliedAt,
        NotificationChannel notificationChannel
) {
    public static final NotificationChannel DEFAULT_CHANNEL = NotificationChannel.NONE;

    // 재신청 쿨다운 미경과 시 발생 — GlobalExceptionHandler에서 429(Retry-After) 매핑
    public static class CooldownException extends RuntimeException {
        private final Instant retryAfter;

        public CooldownException(Instant retryAfter) {
            super("재신청 대기 중입니다. 가능 시각: " + retryAfter);
            this.retryAfter = retryAfter;
        }

        public Instant getRetryAfter() { return retryAfter; }
    }

    public boolean hasTelegramBot() {
        return telegramBotToken != null && !telegramBotToken.isBlank() && telegramChatId != null;
    }

    public User withStatus(UserStatus newStatus) {
        return new User(id, kakaoId, nickname, email, newStatus, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                lastReappliedAt, notificationChannel);
    }

    public User withStatus(UserStatus newStatus, Instant newLastReappliedAt) {
        return new User(id, kakaoId, nickname, email, newStatus, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                newLastReappliedAt, notificationChannel);
    }

    public User withRejection(String reason) {
        return new User(id, kakaoId, nickname, email, UserStatus.REJECTED, role,
                telegramBotToken, telegramChatId, telegramBotUsername, reason,
                Instant.now(), notificationChannel);
    }

    public User withTelegram(String botToken, String chatId, String botUsername) {
        return new User(id, kakaoId, nickname, email, status, role,
                botToken, chatId, botUsername, rejectReason, lastReappliedAt, notificationChannel);
    }

    public User withNotificationChannel(NotificationChannel channel) {
        return new User(id, kakaoId, nickname, email, status, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                lastReappliedAt, channel);
    }

    public User withRole(UserRole newRole) {
        return new User(id, kakaoId, nickname, email, status, newRole,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                lastReappliedAt, notificationChannel);
    }

    public User withNickname(String nickname) {
        return new User(id, kakaoId, nickname, email, status, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                lastReappliedAt, notificationChannel);
    }

    public User withEmail(String email) {
        return new User(id, kakaoId, nickname, email, status, role,
                telegramBotToken, telegramChatId, telegramBotUsername, rejectReason,
                lastReappliedAt, notificationChannel);
    }
}
```

- [ ] **Step 5: 전 소비 파일 색출 + `User.UserStatus`/`User.UserRole`/`User.NotificationChannel`/`NotificationType` 참조 치환**

```bash
git grep -ln "User\.UserStatus\|User\.UserRole\|User\.NotificationChannel\|domain\.model\.user\.NotificationType" -- src/main src/test | sort
```

색출된 각 파일에서 nested 참조를 단순 참조 + import로 치환:

```bash
FILES=$(git grep -ln "User\.UserStatus\|User\.UserRole\|User\.NotificationChannel" -- src/main src/test)
for f in $FILES; do
  perl -pi -e 's/\bUser\.UserStatus\b/UserStatus/g; s/\bUser\.UserRole\b/UserRole/g; s/\bUser\.NotificationChannel\b/NotificationChannel/g' "$f"
done

# import 추가 — 각 파일에 실제 쓰인 타입만 골라 import 라인 삽입 (파일마다 어떤 조합을 쓰는지 다르므로 컴파일 에러로 확인 후 수동 추가)
NT_FILES=$(git grep -ln "com\.kista\.domain\.model\.user\.NotificationType\b" -- src/main src/test)
for f in $NT_FILES; do
  perl -pi -e 's/^import com\.kista\.domain\.model\.user\.NotificationType;$/import com.kista.sharedkernel.NotificationType;/' "$f"
done
```

컴파일해서 `UserStatus`/`UserRole`/`NotificationChannel` cannot find symbol이 뜨는 파일마다 import 3종 중 필요한 것만 추가:
```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "cannot find symbol" -A2 | grep -oE "^/[^:]+\.java" | sort -u
```
위 출력된 파일마다 상단 import 블록에 필요한 조합을 추가한다:
```java
import com.kista.sharedkernel.NotificationChannel;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
```

와일드카드 import(`import com.kista.domain.model.user.*;`) 쓰는 파일이 있으면 이미 `User`/`UserSettings`만 남기고 위 3개 심볼은 못 찾을 것이므로 동일하게 명시 import를 추가한다.

- [ ] **Step 6: 컴파일 + 전체 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음.

```bash
git grep -n "com\.kista\.domain\.model\.user\.NotificationType\b" -- src/main src/test
```
Expected: 출력 없음(전부 sharedkernel로 치환됨).

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): User nested enum 3종 + NotificationType sharedkernel 이관

User.UserRole/UserStatus/NotificationChannel(nested enum) +
domain/model/user/NotificationType을 신규 com.kista.sharedkernel
독립 타입으로 추출. Strategy 4종 nested enum과 함께 스펙이 명시한
"전역 공용 어휘" 이관 정책 1차 적용 — admin의 AdminUserView 등이
User를 직접 참조하지 않고 sharedkernel만 참조하게 되는 전제 작업.
DB @Enumerated(STRING) 컬럼 상수명 byte-identical 유지.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: admin↔user 순환 해소 — ApprovalPolicyPort 포트 역전 + UserCascadeDeleter 캡슐화 정리

**Files:**
- Create: `src/main/java/com/kista/application/port/output/ApprovalPolicyPort.java`
- Modify: `src/main/java/com/kista/application/service/user/UserService.java` (RuntimeSettingsPort → ApprovalPolicyPort)
- Modify: `src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java` (ApprovalPolicyPort 구현 추가)
- Modify: `src/main/java/com/kista/admin/application/service/AdminService.java` (UserCascadeDeleter 직접 참조 제거 → UserUseCase.deleteMe 위임)
- Modify: `src/main/java/com/kista/admin/domain/model/AdminUserView.java` (User.UserStatus/UserRole → sharedkernel)
- Modify: `src/main/java/com/kista/admin/application/port/output/AdminUserViewPort.java` (파라미터 타입 sharedkernel)
- Modify: `src/main/java/com/kista/admin/application/usecase/AdminUserUseCase.java` (시그니처 sharedkernel)
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminUserController.java` (`:34` 파라미터 타입)
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/{AdminUserResponse,AdminRoleRequest,AdminStatusRequest}.java`
- Test: `src/test/java/com/kista/admin/application/service/AdminServiceTest.java`, `RuntimeSettingsServiceTest.java`, `src/test/java/com/kista/application/service/user/UserServiceTest.java`, 관련 `@WebMvcTest`

**Interfaces:**
- Produces: `com.kista.application.port.output.ApprovalPolicyPort`(`boolean approvalRequiredForUpdate()`) — user가 소유, Task 5에서 `com.kista.user.application.port.output`으로 물리 이동.
- Consumes: 없음(admin의 `RuntimeSettingsService`가 구현).

- [ ] **Step 1: `ApprovalPolicyPort` 신규 정의**

`src/main/java/com/kista/application/port/output/ApprovalPolicyPort.java` (Task 5에서 `com.kista.user.application.port.output`로 물리 이동 예정 — 지금은 레거시 위치에 임시로 둔다, admin의 `RuntimeSettingsPort`와 동일 레벨):
```java
package com.kista.application.port.output;

// 가입 승인 필요 여부를 잠금 조회로 확인하는 포트 — UserService.register()/reapply()가 관리자의
// 승인설정 전환(RuntimeSettingsService.updateSettings)과 직렬화하기 위해 FOR UPDATE 락이 필요하다.
// user 모듈이 admin의 RuntimeSettingsPort를 직접 참조하지 않도록, user가 필요한 만큼만 담은
// 전용 포트를 자체 정의하고 admin이 구현한다(broker MockSimulationDataPort와 동일한 포트 역전 패턴).
public interface ApprovalPolicyPort {
    boolean approvalRequiredForUpdate(); // FOR UPDATE 락 조회 — 동시 승인설정 전환과 직렬화 필수
}
```

- [ ] **Step 2: `RuntimeSettingsService`가 `ApprovalPolicyPort` 구현**

`src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java`의 클래스 선언에 `implements` 추가:

```java
class RuntimeSettingsService implements RuntimeSettingsUseCase, AdminSettingsUseCase, ApprovalPolicyPort {
```

import 추가:
```java
import com.kista.application.port.output.ApprovalPolicyPort;
```

메서드 추가(기존 `settingsPort.loadForUpdate().approvalRequired()` 호출부와 동일 로직 재사용):
```java
    @Override
    @Transactional
    public boolean approvalRequiredForUpdate() {
        return settingsPort.loadForUpdate().approvalRequired();
    }
```

- [ ] **Step 3: `UserService`가 `RuntimeSettingsPort` 대신 `ApprovalPolicyPort` 사용**

`src/main/java/com/kista/application/service/user/UserService.java`에서:
```java
import com.kista.admin.application.port.output.RuntimeSettingsPort;
```
삭제하고:
```java
import com.kista.application.port.output.ApprovalPolicyPort;
```
추가. 필드:
```java
private final ApprovalPolicyPort approvalPolicyPort;  // 가입 승인 필요 여부 (admin RuntimeSettingsService가 구현)
```
`register()`(`:103`)와 `reapply()`(`:147`) 내부의 `runtimeSettingsPort.loadForUpdate().approvalRequired()` 호출부 2곳을 `approvalPolicyPort.approvalRequiredForUpdate()`로 교체. `runtimeSettingsPort` 필드는 삭제.

또한 `:11`의 dead wildcard import 삭제:
```java
import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
```
→
```java
import com.kista.application.port.output.*;
```
(trading 포트는 `UserService`가 실제로 쓰지 않음 — 사전 코드 확인 완료)

- [ ] **Step 4: `AdminService`가 `UserCascadeDeleter` 대신 `UserUseCase.deleteMe` 사용**

`src/main/java/com/kista/admin/application/service/AdminService.java`에서:
```java
import com.kista.application.service.user.UserCascadeDeleter;
```
삭제. 필드:
```java
private final UserCascadeDeleter userCascadeDeleter;
```
삭제 — 이미 존재하는 `userUseCase` 필드(`UserUseCase`, `:10`에 이미 import됨)를 재사용.

`deleteUser()` 메서드(`:100-105`) 수정:
```java
    @Override
    public void deleteUser(UUID adminId, UUID targetUserId) {
        userPort.findByIdOrThrow(targetUserId); // 존재 확인
        userUseCase.deleteMe(targetUserId);
        log.info("관리자 사용자 삭제: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_DELETE", "USER", targetUserId, null);
    }
```
(`UserUseCase.deleteMe(UUID)`는 이미 "본인 탈퇴" 의미로 명명돼 있으나 구현체(`UserService.deleteMe`)는 `userPort.findByIdOrThrow` + `userCascadeDeleter.deleteCascade` 호출만 하는 순수 cascade 트리거라 관리자 대리 삭제에도 그대로 재사용 가능 — 메서드명 변경은 스코프 아웃, 인터페이스 시그니처 유지)

- [ ] **Step 5: `AdminUserView`/`AdminUserViewPort`/`AdminUserUseCase`/`AdminUserController` sharedkernel 전환**

`src/main/java/com/kista/admin/domain/model/AdminUserView.java`:
```java
package com.kista.admin.domain.model;

import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

import java.time.Instant;
import java.util.UUID;

// 관리자 화면 전용 read-model — User record와 분리하여 가입 일시를 안전하게 노출
public record AdminUserView(
        UUID id,
        String nickname,
        UserStatus status,
        UserRole role,
        Instant createdAt
) {}
```

`src/main/java/com/kista/admin/application/port/output/AdminUserViewPort.java`:
```java
package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminUserView;
import com.kista.sharedkernel.UserStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserViewPort {
    List<AdminUserView> findAll();
    List<AdminUserView> findAllByStatus(UserStatus status);
    Optional<AdminUserView> findById(UUID userId);
}
```

`src/main/java/com/kista/admin/application/usecase/AdminUserUseCase.java` — `import com.kista.domain.model.user.User;` 삭제하고 `import com.kista.sharedkernel.{UserRole,UserStatus};` 추가, `User.UserStatus`→`UserStatus`, `User.UserRole`→`UserRole`로 시그니처 전체 치환:
```java
package com.kista.admin.application.usecase;

import com.kista.admin.domain.model.AdminUserView;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserUseCase {
    List<AdminUserView> listAll(LocalDate from, LocalDate to);
    List<AdminUserView> listByStatus(UserStatus status, LocalDate from, LocalDate to);
    void approveUser(UUID adminId, UUID targetUserId);
    void rejectUser(UUID adminId, UUID targetUserId, String reason);
    void changeRole(UUID adminId, UUID targetUserId, UserRole role);
    void deleteUser(UUID adminId, UUID targetUserId);
    Optional<AdminUserView> findUser(UUID userId);
}
```

`AdminService.java`의 관련 메서드 시그니처(`listByStatus`/`changeRole`)와 본문의 `User.UserStatus`/`User.UserRole` 참조도 동일하게 `UserStatus`/`UserRole`로 치환하고 `import com.kista.domain.model.user.User;`를 `import com.kista.sharedkernel.{UserRole,UserStatus};`로 교체(단 `User user = userPort.findByIdOrThrow(...)` 등 `User` 타입 자체를 쓰는 곳은 `User` import 유지 필요 — 컴파일 에러로 확인).

`src/main/java/com/kista/admin/adapter/in/web/AdminUserController.java:34` — `@RequestParam User.UserStatus` → `@RequestParam UserStatus`, import 교체.

`src/main/java/com/kista/admin/adapter/in/web/dto/AdminUserResponse.java`/`AdminRoleRequest.java`/`AdminStatusRequest.java` — `User.UserStatus`/`User.UserRole` 참조를 sharedkernel로 교체.

- [ ] **Step 6: 색출 + 컴파일**

```bash
git grep -n "com\.kista\.admin\.application\.port\.output\.RuntimeSettingsPort" -- src/main/java/com/kista/application/service/user
```
Expected: 출력 없음(Step 3에서 제거됨).

```bash
git grep -n "com\.kista\.application\.service\.user\.UserCascadeDeleter" -- src/main/java/com/kista/admin
```
Expected: 출력 없음(Step 4에서 제거됨).

```bash
git grep -n "com\.kista\.domain\.model\.user\.User\b" -- src/main/java/com/kista/admin/domain/model/AdminUserView.java src/main/java/com/kista/admin/application/port/output/AdminUserViewPort.java src/main/java/com/kista/admin/application/usecase/AdminUserUseCase.java
```
Expected: 출력 없음.

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 테스트 파일 쪽에서 `@Mock UserCascadeDeleter`/`RuntimeSettingsPort` mock 잔존으로 인한 에러 다수 — Step 7에서 처리.

- [ ] **Step 7: 테스트 갱신**

`src/test/java/com/kista/admin/application/service/AdminServiceTest.java` — `@Mock UserCascadeDeleter userCascadeDeleter` 제거, `verify(userCascadeDeleter).deleteCascade(...)` 검증을 `verify(userUseCase).deleteMe(...)`로 교체. `User.UserStatus`/`User.UserRole` 리터럴은 sharedkernel import로 교체.

`src/test/java/com/kista/admin/application/service/RuntimeSettingsServiceTest.java` — `approvalRequiredForUpdate()` 신규 메서드에 대한 테스트 케이스 추가:
```java
@Test
void approvalRequiredForUpdate_delegatesToLoadForUpdate() {
    when(settingsPort.loadForUpdate()).thenReturn(settingsWithApprovalRequired(true));

    boolean result = runtimeSettingsService.approvalRequiredForUpdate();

    assertThat(result).isTrue();
    verify(settingsPort).loadForUpdate();
}
```
(`settingsWithApprovalRequired` 헬퍼가 이미 파일에 있으면 재사용, 없으면 기존 `getSettings`/`updateSettings` 테스트가 쓰는 `RuntimeSettings` 픽스처 생성 패턴을 그대로 따라 만든다)

`src/test/java/com/kista/application/service/user/UserServiceTest.java` — `@Mock RuntimeSettingsPort runtimeSettingsPort` → `@Mock ApprovalPolicyPort approvalPolicyPort`로 교체, `when(runtimeSettingsPort.loadForUpdate()).thenReturn(...)` stub을 `when(approvalPolicyPort.approvalRequiredForUpdate()).thenReturn(true/false)`로 교체(register/reapply 테스트 전부).

- [ ] **Step 8: 컴파일 + 대상 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.admin.application.service.*' --tests 'com.kista.application.service.user.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, `BUILD SUCCESSFUL`.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): admin↔user 순환 사전 해소 — ApprovalPolicyPort 포트 역전

UserService가 admin의 RuntimeSettingsPort를 직접 참조하던 것을
자체 ApprovalPolicyPort(1메서드)로 교체하고 admin의
RuntimeSettingsService가 구현(broker MockSimulationDataPort와 동일
포트 역전 패턴). AdminService의 UserCascadeDeleter(user internal)
직접 참조를 UserUseCase.deleteMe 위임으로 교체해 캡슐화 위반 해소.
AdminUserView/AdminUserViewPort/AdminUserUseCase의 User.UserStatus/
UserRole 참조를 sharedkernel로 전환 — admin이 user를 완전히
참조하지 않는 상태로 정리(user CLOSED 전환 전 사전 해소).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: user↔trading·user↔finance 순환 해소 — UserCascadeDeleter 이벤트 팬아웃 전환 + FCM cascade 결함 수정

**Files:**
- Modify: `src/main/java/com/kista/application/service/user/UserCascadeDeleter.java` (trading 2포트 + finance 6포트 직접 호출 제거, 순서 보존을 위해 UserDeletedEvent를 트랜잭션 커밋 후 발행)
- Create: `src/main/java/com/kista/trading/adapter/out/UserCascadeListener.java` (신규 리스너 — cyclePosition/strategyCycle/strategy/account cascade)
- Create: `src/main/java/com/kista/finance/adapter/out/UserCascadeListener.java` (신규 리스너 — finance 6포트 + 그룹 승계 로직)
- Modify: `src/main/java/com/kista/notify/application/port/output/FcmDeviceTokenPort.java` (`deleteAllByUserId` 메서드 추가)
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/FcmAdapter.java` (구현 추가 — 실제로는 persistence adapter가 구현하는지 확인 필요, 아래 Step 참고)
- Create: `src/main/java/com/kista/notify/adapter/out/gateway/UserFcmCleanupListener.java`
- Test: `src/test/java/com/kista/application/service/user/UserCascadeDeleterTest.java`, 신규 `TradingUserCascadeListenerTest`, `FinanceUserCascadeListenerTest`, `UserFcmCleanupListenerTest`

**Interfaces:**
- Consumes: 기존 `UserDeletedEvent(UUID userId)` — 스키마 변경 없음, 리스너만 추가.
- Produces: 없음(순수 리스너 추가).

> **주의 — 계좌/전략 삭제 순서 보존.** 기존 `UserCascadeDeleter.deleteCascade()`는 단일 트랜잭션 안에서 `cyclePosition → strategyCycle → strategy → account → finance(5종) → group승계 → user → refreshToken → blacklist` 순서를 보장했다. 이벤트 리스너로 쪼개면 이 순서가 "user 삭제 커밋 후, 등록된 리스너들이 각자 실행"으로 바뀌어 **순서가 리스너 등록 순서·스레드 스케줄링에 의존**하게 된다. 이 계획에서는 순서를 강제로 유지하지 않는다 — 사용자 승인 사항("전부 이벤트 팬아웃 수용")대로 각 리스너가 자기 소유 데이터를 독립적으로 정리하며, 어떤 순서로 실행되어도 최종 상태가 같도록 설계한다(FK 없이 소프트 삭제라 순서 무관하게 안전 — `strategy`가 아직 안 지워진 채로 `cyclePosition`이 지워져도, 반대여도 최종적으로 둘 다 지워지면 데이터 정합성에 문제 없음). account/strategy(legacy)는 이번 스코프에서 이벤트화하지 않고 **그대로 동기 호출 유지**.

- [ ] **Step 1: `UserCascadeDeleter`에서 trading/finance 직접 호출 제거**

`src/main/java/com/kista/application/service/user/UserCascadeDeleter.java` 전체를 아래로 교체:

```java
package com.kista.application.service.user;

import com.kista.application.event.UserDeletedEvent;
import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.BlacklistPort;
import com.kista.application.port.output.RefreshTokenPort;
import com.kista.application.port.output.StrategyPort;
import com.kista.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제 진입점.
// trading(cyclePosition/strategyCycle)·finance(6종+그룹승계)는 UserDeletedEvent 발행 후
// 각 모듈이 독립 리스너로 자체 soft-delete한다(EPR 재시도 보장) — 모듈 경계를 넘는 직접 포트
// 호출을 없애 user↔trading·user↔finance 순환을 제거했다. account/strategy는 아직 레거시 OPEN이라
// 순환이 아니므로 이번 스코프에서는 직접 호출을 유지한다(4단계 account/strategy-config 이전 때 검토).
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final Duration AT_TTL = Duration.ofMinutes(15);

    public void deleteCascade(UUID userId) {
        strategyPort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);

        userPort.delete(userId);
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
        // 커밋 후 발행 — trading/finance/notify 리스너가 각자 소유 데이터를 독립적으로 정리
        eventPublisher.publishEvent(new UserDeletedEvent(userId));
    }
}
```

- [ ] **Step 2: trading 리스너 신설**

`src/main/java/com/kista/trading/adapter/out/UserCascadeListener.java`:
```java
package com.kista.trading.adapter.out;

import com.kista.application.event.UserDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — trading 소유 데이터(cycle_position/strategy_cycle)를 독립적으로 soft-delete.
// UserCascadeDeleter가 직접 포트를 호출하던 것을 이벤트 구독으로 전환(user↔trading 순환 해소).
@Component
@RequiredArgsConstructor
public class UserCascadeListener {

    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        cyclePositionPort.deleteByUserId(event.userId());
        strategyCyclePort.deleteByUserId(event.userId());
    }
}
```

- [ ] **Step 3: finance 리스너 신설 (그룹 승계 로직 이관)**

`src/main/java/com/kista/finance/adapter/out/UserCascadeListener.java` — `UserCascadeDeleter`에서 삭제한 그룹 승계 로직을 그대로 옮긴다:
```java
package com.kista.finance.adapter.out;

import com.kista.application.event.UserDeletedEvent;
import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceAccountPort;
import com.kista.finance.application.port.output.FinanceBudgetPort;
import com.kista.finance.application.port.output.FinanceCategoryPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;

// 사용자 탈퇴 cascade — finance 소유 재무 기록(userId 축) + 그룹 멤버십 정리를 독립적으로 처리.
// UserCascadeDeleter가 직접 6개 포트를 호출하던 것을 이벤트 구독으로 전환(user↔finance 순환 해소).
// 그룹 소유권 승계 로직은 원본 UserCascadeDeleter 구현을 그대로 이관.
@Component
@RequiredArgsConstructor
public class UserCascadeListener {

    private final FinanceTransactionPort financeTransactionPort;
    private final AssetSnapshotPort assetSnapshotPort;
    private final FinanceAccountPort financeAccountPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceBudgetPort financeBudgetPort;
    private final FinanceGroupPort financeGroupPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        var userId = event.userId();

        // 재무 기록 — userId(입력자) 단위로만 정리한다. 그룹을 통째로 지우면 배우자 등 다른
        // 그룹원의 데이터까지 함께 삭제되므로, 소유 축인 group_id가 아니라 입력자 축으로 스코프한다.
        financeTransactionPort.softDeleteByUserId(userId);
        assetSnapshotPort.softDeleteByUserId(userId);
        financeAccountPort.softDeleteByUserId(userId);
        financeCategoryPort.softDeleteByUserId(userId);
        financeBudgetPort.deleteByUserId(userId);

        // 그룹 멤버십 정리 — 이 사용자가 속한 모든 그룹(개인 그룹 포함)에서 멤버십을 소프트 삭제하고,
        // 그 결과 활성 멤버가 0명이 된 그룹은 그룹 자체도 소프트 삭제한다.
        for (var group : financeGroupPort.findByMemberUserId(userId)) {
            boolean wasOwner = financeGroupPort.findRole(group.id(), userId)
                    .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                    .isPresent();
            financeGroupPort.softDeleteMembership(group.id(), userId);
            var remaining = financeGroupPort.findActiveMembers(group.id());
            if (remaining.isEmpty()) {
                financeGroupPort.softDelete(group.id());
            } else if (wasOwner && remaining.stream().noneMatch(m -> m.role() == FinanceGroup.MemberRole.OWNER)) {
                FinanceGroupMember successor = remaining.stream()
                        .min(Comparator.comparing(FinanceGroupMember::joinedAt))
                        .orElseThrow();
                financeGroupPort.updateMemberRole(group.id(), successor.userId(), FinanceGroup.MemberRole.OWNER);
            }
        }
    }
}
```

- [ ] **Step 4: FCM cascade 결함 수정 — `FcmDeviceTokenPort` 확장 + notify 리스너 신설**

`src/main/java/com/kista/notify/application/port/output/FcmDeviceTokenPort.java`에 메서드 추가:
```java
public interface FcmDeviceTokenPort {
    void save(UUID userId, String token, String platform);
    void delete(UUID userId, String token);
    List<String> findTokensByUserId(UUID userId);
    void deleteAllByUserId(UUID userId); // 탈퇴 cascade — 사용자 소유 FCM 디바이스 토큰 전체 삭제
}
```

`FcmDeviceTokenPort` 구현체(persistence adapter) 확인 후 구현 추가 — 먼저 구현체 위치 확인:
```bash
git grep -ln "implements FcmDeviceTokenPort" -- src/main/java/com/kista/notify
```
해당 구현체(`FcmDeviceTokenPersistenceAdapter`)에 메서드 추가 — 기존 JPA 리포지토리에 `deleteAllByUserId` 파생 쿼리 메서드가 있는지 확인 후 없으면 추가:
```bash
git grep -n "interface FcmDeviceTokenJpaRepository" -A15 src/main/java/com/kista/notify/adapter/out/persistence/FcmDeviceTokenJpaRepository.java
```
리포지토리에 다음 메서드 추가(존재하지 않으면):
```java
void deleteAllByUserId(UUID userId);
```
어댑터에 구현 추가:
```java
    @Override
    public void deleteAllByUserId(UUID userId) {
        jpaRepository.deleteAllByUserId(userId);
    }
```

신규 리스너 `src/main/java/com/kista/notify/adapter/out/gateway/UserFcmCleanupListener.java`:
```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.event.UserDeletedEvent;
import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — FCM 디바이스 토큰 삭제 (기존 UserCascadeDeleter에 누락돼 있던 결함 수정,
// 발견 시점 함께 수정: 탈퇴 후에도 FCM 토큰이 남아 존재하지 않는 사용자에게 알림을 계속 시도하던 상태였음)
@Component
@RequiredArgsConstructor
class UserFcmCleanupListener {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        fcmDeviceTokenPort.deleteAllByUserId(event.userId());
    }
}
```

- [ ] **Step 5: `UserCascadeDeleterTest` 갱신 — trading/finance mock 제거**

`src/test/java/com/kista/application/service/user/UserCascadeDeleterTest.java`에서 `@Mock CyclePositionPort`/`StrategyCyclePort`/`FinanceTransactionPort`/`AssetSnapshotPort`/`FinanceAccountPort`/`FinanceCategoryPort`/`FinanceBudgetPort`/`FinanceGroupPort` 및 그룹 승계 시나리오 테스트 전부 제거. 남는 테스트는 `strategyPort.deleteByUserId`/`accountPort.deleteByUserId`/`userPort.delete`/`refreshTokenPort.deleteAllByUserId`/`blacklistPort.add`/`UserDeletedEvent` 발행 검증만:

```java
@Test
void deleteCascade_softDeletesAndPublishesEvent() {
    UUID userId = UUID.randomUUID();

    deleter.deleteCascade(userId);

    verify(strategyPort).deleteByUserId(userId);
    verify(accountPort).deleteByUserId(userId);
    verify(userPort).delete(userId);
    verify(refreshTokenPort).deleteAllByUserId(userId);
    verify(blacklistPort).add(eq(userId), any(Duration.class));
    verify(eventPublisher).publishEvent(new UserDeletedEvent(userId));
}
```

- [ ] **Step 6: 신규 리스너 테스트**

`src/test/java/com/kista/trading/adapter/out/UserCascadeListenerTest.java`:
```java
package com.kista.trading.adapter.out;

import com.kista.application.event.UserDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserCascadeListenerTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;

    @Test
    void onUserDeleted_deletesCyclePositionAndStrategyCycle() {
        UserCascadeListener listener = new UserCascadeListener(cyclePositionPort, strategyCyclePort);
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(cyclePositionPort).deleteByUserId(userId);
        verify(strategyCyclePort).deleteByUserId(userId);
    }
}
```

`src/test/java/com/kista/finance/adapter/out/UserCascadeListenerTest.java` — `UserCascadeDeleterTest`에서 제거한 그룹 승계 시나리오(활성 멤버 0명 → 그룹 소프트삭제, OWNER 탈퇴 + 잔여 멤버 존재 → 승계)를 그대로 이 파일로 이관해 검증한다. 원본 `UserCascadeDeleterTest`에 있던 `FinanceGroupPort` mock 기반 테스트 케이스 전체(그룹 없음/그룹 있고 마지막 멤버/OWNER 탈퇴 승계 3케이스 이상)를 이 클래스에 옮겨쓴다 — 픽스처는 원본 테스트 파일의 `FinanceGroup`/`FinanceGroupMember` 생성 패턴을 그대로 재사용.

`src/test/java/com/kista/notify/adapter/out/gateway/UserFcmCleanupListenerTest.java`:
```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.event.UserDeletedEvent;
import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserFcmCleanupListenerTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;

    @Test
    void onUserDeleted_deletesAllFcmTokens() {
        UserFcmCleanupListener listener = new UserFcmCleanupListener(fcmDeviceTokenPort);
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(fcmDeviceTokenPort).deleteAllByUserId(userId);
    }
}
```

- [ ] **Step 7: `FcmDeviceTokenPort` 구현체 테스트 갱신**

`deleteAllByUserId` 구현을 추가한 persistence adapter의 기존 테스트 파일에 케이스 추가(파일 위치는 Step 4에서 확인한 구현체 기준):
```java
@Test
void deleteAllByUserId_deletesAllTokensForUser() {
    // given: userId로 저장된 토큰 2개 + 다른 userId 토큰 1개
    // when: deleteAllByUserId(userId) 호출
    // then: userId 소유 토큰만 삭제, 다른 사용자 토큰은 유지
}
```
(정확한 given/when/then 코드는 해당 어댑터 테스트 파일의 기존 패턴 — `@DataJpaTest` 또는 Mockito 방식 — 을 그대로 따른다. Step 4에서 실제 파일을 읽은 뒤 기존 스타일에 맞춰 작성)

- [ ] **Step 8: 컴파일 + 대상 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.application.service.user.*' --tests 'com.kista.trading.adapter.out.*' --tests 'com.kista.finance.adapter.out.*' --tests 'com.kista.notify.adapter.out.gateway.UserFcmCleanupListenerTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, `BUILD SUCCESSFUL`.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): user↔trading·user↔finance 순환 해소 — cascade 이벤트 팬아웃

UserCascadeDeleter가 trading(cyclePosition/strategyCycle)·finance
(6포트+그룹승계) 포트를 직접 호출하던 것을 UserDeletedEvent 발행 +
각 모듈 독립 리스너(trading/finance adapter.out.UserCascadeListener)
구독으로 전환. EPR 재시도 인프라를 그대로 활용하며, 단일 트랜잭션
cascade에서 다중 트랜잭션 최종일관성으로 전환됨(사용자 승인 사항).
account/strategy는 레거시 OPEN이라 순환 아니므로 직접 호출 유지.

부수 수정: 탈퇴 cascade에 FCM 디바이스 토큰 삭제가 누락돼 있던
기존 결함을 FcmDeviceTokenPort.deleteAllByUserId + notify
UserFcmCleanupListener로 수정.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: FcmController — UserProfileUseCase 위임 제거, FcmDeviceTokenPort 직접 소비

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/FcmController.java`
- Modify: `src/main/java/com/kista/application/usecase/UserProfileUseCase.java` (registerFcmToken/unregisterFcmToken 제거)
- Modify: `src/main/java/com/kista/application/service/user/UserProfileService.java` (동일 메서드 제거 + FcmDeviceTokenPort 필드 제거)
- Test: `src/test/java/com/kista/adapter/in/web/FcmControllerTest.java`, `src/test/java/com/kista/application/service/user/UserProfileServiceTest.java`

**Interfaces:**
- Consumes: `com.kista.notify.application.port.output.FcmDeviceTokenPort`(기존 `save`/`delete`, Task 3에서 추가된 `deleteAllByUserId`는 이 태스크와 무관).
- Produces: 없음(제거만).

- [ ] **Step 1: `FcmController`가 `FcmDeviceTokenPort` 직접 주입**

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.FcmTokenRequest;
import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "FCM", description = "FCM 디바이스 토큰 관리")
@RestController
@RequestMapping("/api/fcm")
@RequiredArgsConstructor
public class FcmController {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;

    @Operation(summary = "FCM 토큰 등록", description = "body: {\"token\": \"...\", \"platform\": \"WEB\"}")
    @ApiResponse(responseCode = "204", description = "등록 성공")
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerToken(@AuthenticationPrincipal UUID userId,
                              @Valid @RequestBody FcmTokenRequest body) {
        fcmDeviceTokenPort.save(userId, body.token(), body.platform());
    }

    @Operation(summary = "FCM 토큰 삭제", description = "로그아웃 또는 알림 비활성화 시 호출")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @DeleteMapping("/tokens/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterToken(@AuthenticationPrincipal UUID userId,
                                @PathVariable String token) {
        fcmDeviceTokenPort.delete(userId, token);
    }
}
```

- [ ] **Step 2: `UserProfileUseCase`/`UserProfileService`에서 FCM 메서드 제거**

`src/main/java/com/kista/application/usecase/UserProfileUseCase.java`:
```java
package com.kista.application.usecase;

import com.kista.domain.model.user.User.NotificationChannel;

import java.util.UUID;

public interface UserProfileUseCase {
    void updateTelegram(UUID userId, String botToken, String chatId);
    void removeTelegram(UUID userId);
    void updateNotificationChannel(UUID userId, NotificationChannel channel);
    void updateNickname(UUID userId, String nickname);
}
```

`src/main/java/com/kista/application/service/user/UserProfileService.java`에서 `registerFcmToken`/`unregisterFcmToken` 메서드 삭제 + `FcmDeviceTokenPort` 필드/import 삭제:
```java
package com.kista.application.service.user;

import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.application.usecase.UserProfileUseCase;
import com.kista.notify.application.port.output.TelegramBotInfoPort;
import com.kista.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class UserProfileService implements UserProfileUseCase {

    private final UserPort userPort;
    private final TelegramBotInfoPort telegramBotInfoPort;

    @Override
    public void updateTelegram(UUID userId, String botToken, String chatId) {
        String botUsername = telegramBotInfoPort.getUsername(botToken);
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withTelegram(botToken, chatId, botUsername));
        log.info("텔레그램 설정 업데이트: userId={}, botUsername={}", userId, botUsername);
    }

    @Override
    public void removeTelegram(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withTelegram(null, null, null));
        log.info("텔레그램 설정 해제: userId={}", userId);
    }

    @Override
    public void updateNotificationChannel(UUID userId, NotificationChannel channel) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withNotificationChannel(channel));
        log.info("알림 채널 변경: userId={}, channel={}", userId, channel);
    }

    @Override
    public void updateNickname(UUID userId, String nickname) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withNickname(nickname.strip()));
        log.info("닉네임 변경: userId={}", userId);
    }
}
```

- [ ] **Step 3: 테스트 갱신**

`src/test/java/com/kista/adapter/in/web/FcmControllerTest.java` — `@MockitoBean UserProfileUseCase` → `@MockitoBean FcmDeviceTokenPort`, `verify(userProfileUseCase).registerFcmToken(...)` → `verify(fcmDeviceTokenPort).save(...)`, 마찬가지로 `unregisterToken`:
```java
package com.kista.adapter.in.web;

import com.kista.application.usecase.BlacklistUseCase;
import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.admin.application.port.output.AppErrorLogPort;

@WebMvcTest(FcmController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FcmControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean FcmDeviceTokenPort fcmDeviceTokenPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void registerToken_returns204() throws Exception {
        mockMvc.perform(post("/api/fcm/tokens")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-abc\",\"platform\":\"WEB\"}"))
                .andExpect(status().isNoContent());
        verify(fcmDeviceTokenPort).save(eq(USER_ID), eq("fcm-token-abc"), eq("WEB"));
    }

    @Test
    void unregisterToken_returns204() throws Exception {
        mockMvc.perform(delete("/api/fcm/tokens/fcm-token-abc")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());
        verify(fcmDeviceTokenPort).delete(eq(USER_ID), eq("fcm-token-abc"));
    }
}
```

`src/test/java/com/kista/application/service/user/UserProfileServiceTest.java`에서 `registerFcmToken`/`unregisterFcmToken` 테스트 케이스와 `@Mock FcmDeviceTokenPort` 제거.

- [ ] **Step 4: 컴파일 + 대상 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.adapter.in.web.FcmControllerTest' --tests 'com.kista.application.service.user.UserProfileServiceTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: 에러 없음, `BUILD SUCCESSFUL`.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(modulith): FcmController가 FcmDeviceTokenPort 직접 소비

UserProfileUseCase.registerFcmToken/unregisterFcmToken을 거치던
단순 위임 경로를 제거하고 FcmController가 notify의
FcmDeviceTokenPort를 직접 주입해 호출하도록 변경. user 모듈이
FCM 토큰 관리라는 무관한 책임을 갖지 않도록 정리.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 코어(domain + application) 물리 이전 + 전역 소비자 import 정합화

**Files:**
- Move: `src/main/java/com/kista/domain/model/user/{User,UserSettings}.java` → `src/main/java/com/kista/user/domain/model/`
- Move: `src/main/java/com/kista/domain/model/auth/*.java` (4개) → `src/main/java/com/kista/user/domain/auth/`
- Move: `src/main/java/com/kista/application/usecase/{UserUseCase,UserProfileUseCase,GetUserSettingsQuery,UpdateBalanceCheckUseCase,UpdateNotificationPrefUseCase,UpdateStrategySuggestionsUseCase,BlacklistUseCase,TokenUseCase}.java` → `src/main/java/com/kista/user/application/usecase/`
- Move: `src/main/java/com/kista/application/port/output/{UserPort,UserSettingsPort,BlacklistPort,KakaoOAuthPort,RefreshTokenPort,ApprovalPolicyPort}.java` → `src/main/java/com/kista/user/application/port/output/`
- Move: `src/main/java/com/kista/application/service/user/{UserService,UserProfileService,UserSettingsService,UserCascadeDeleter}.java` → `src/main/java/com/kista/user/application/service/`
- Move: `src/main/java/com/kista/application/service/auth/{TokenService,BlacklistService}.java` → `src/main/java/com/kista/user/application/service/`
- Move: `src/main/java/com/kista/application/event/{NewUserRegisteredEvent,UserApprovedEvent,UserRejectedEvent,UserReappliedEvent,UserDeletedEvent}.java` → `src/main/java/com/kista/user/application/event/`
- Move: `src/main/java/com/kista/application/config/{AdminConfig,AdminBootstrapProperties}.java` → `src/main/java/com/kista/user/config/`
- Move tests: 대응하는 `src/test/java/com/kista/domain/model/{user,auth}/*.java`, `src/test/java/com/kista/application/service/{user,auth}/*.java` → `src/test/java/com/kista/user/{domain/model,domain/auth,application/service}/`

**Interfaces:**
- Produces: `com.kista.user.domain.model.{User,UserSettings}`, `com.kista.user.domain.auth.*`, `com.kista.user.application.usecase.*`(8개), `com.kista.user.application.port.output.*`(6개), `com.kista.user.application.event.*`(5개) — Task 6(어댑터)·Task 7(모듈 선언)이 이 경로를 소비.
- Consumes: Task 1의 `com.kista.sharedkernel.*`, Task 2의 `ApprovalPolicyPort`(레거시 위치에서 이동해옴), Task 3의 `UserDeletedEvent` 발행 로직(이미 반영됨).

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔 (재확인)**

```bash
grep -rn "com\.kista\.\(domain\.model\.\(user\|auth\)\|application\.service\.\(user\|auth\)\|application\.usecase\.\(User\|GetUserSettings\|UpdateBalanceCheck\|UpdateNotificationPref\|UpdateStrategySuggestions\|Blacklist\|Token\)\|application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|application\.event\.\(NewUserRegistered\|UserApproved\|UserRejected\|UserReapplied\|UserDeleted\)\)" src/main/resources/ --include='*.yml'
```
Expected: 0건(Task 착수 전 재확인 완료, 결과 없으면 진행).

- [ ] **Step 1: 와일드카드 import 재확인**

```bash
git grep -n "^import com\.kista.*\*;" -- \
  src/main/java/com/kista/domain/model/user src/main/java/com/kista/domain/model/auth \
  src/main/java/com/kista/application/service/user src/main/java/com/kista/application/service/auth \
  src/main/java/com/kista/application/usecase src/main/java/com/kista/application/port/output \
  src/main/java/com/kista/application/event
```
알려진 것: `UserService.java`(Task 2에서 dead wildcard 이미 제거됨 — 확인만), `UserCascadeDeleter.java`(Task 3에서 trading/finance wildcard 제거됨 — `com.kista.application.port.output.*`만 남아있어야 함).

- [ ] **Step 2: 코어 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/user/domain/model
mkdir -p src/main/java/com/kista/user/domain/auth
mkdir -p src/main/java/com/kista/user/application/usecase
mkdir -p src/main/java/com/kista/user/application/port/output
mkdir -p src/main/java/com/kista/user/application/service
mkdir -p src/main/java/com/kista/user/application/event
mkdir -p src/main/java/com/kista/user/config

git mv src/main/java/com/kista/domain/model/user/User.java src/main/java/com/kista/user/domain/model/User.java
git mv src/main/java/com/kista/domain/model/user/UserSettings.java src/main/java/com/kista/user/domain/model/UserSettings.java
rmdir src/main/java/com/kista/domain/model/user

git mv src/main/java/com/kista/domain/model/auth/*.java src/main/java/com/kista/user/domain/auth/
rmdir src/main/java/com/kista/domain/model/auth

for f in UserUseCase UserProfileUseCase GetUserSettingsQuery UpdateBalanceCheckUseCase UpdateNotificationPrefUseCase UpdateStrategySuggestionsUseCase BlacklistUseCase TokenUseCase; do
  git mv "src/main/java/com/kista/application/usecase/$f.java" "src/main/java/com/kista/user/application/usecase/$f.java"
done

for f in UserPort UserSettingsPort BlacklistPort KakaoOAuthPort RefreshTokenPort ApprovalPolicyPort; do
  git mv "src/main/java/com/kista/application/port/output/$f.java" "src/main/java/com/kista/user/application/port/output/$f.java"
done

git mv src/main/java/com/kista/application/service/user/UserService.java src/main/java/com/kista/user/application/service/UserService.java
git mv src/main/java/com/kista/application/service/user/UserProfileService.java src/main/java/com/kista/user/application/service/UserProfileService.java
git mv src/main/java/com/kista/application/service/user/UserSettingsService.java src/main/java/com/kista/user/application/service/UserSettingsService.java
git mv src/main/java/com/kista/application/service/user/UserCascadeDeleter.java src/main/java/com/kista/user/application/service/UserCascadeDeleter.java
rmdir src/main/java/com/kista/application/service/user

git mv src/main/java/com/kista/application/service/auth/TokenService.java src/main/java/com/kista/user/application/service/TokenService.java
git mv src/main/java/com/kista/application/service/auth/BlacklistService.java src/main/java/com/kista/user/application/service/BlacklistService.java
rmdir src/main/java/com/kista/application/service/auth

for f in NewUserRegisteredEvent UserApprovedEvent UserRejectedEvent UserReappliedEvent UserDeletedEvent; do
  git mv "src/main/java/com/kista/application/event/$f.java" "src/main/java/com/kista/user/application/event/$f.java"
done

git mv src/main/java/com/kista/application/config/AdminConfig.java src/main/java/com/kista/user/config/AdminConfig.java
git mv src/main/java/com/kista/application/config/AdminBootstrapProperties.java src/main/java/com/kista/user/config/AdminBootstrapProperties.java
# application/config 디렉토리에 다른 파일이 남는지 확인 (남으면 STOP, 없으면 rmdir)
ls src/main/java/com/kista/application/config/ 2>/dev/null && echo "STOP: 잔존 파일 확인 필요" || rmdir src/main/java/com/kista/application/config

mkdir -p src/test/java/com/kista/user/domain/model
mkdir -p src/test/java/com/kista/user/domain/auth
mkdir -p src/test/java/com/kista/user/application/service
git mv src/test/java/com/kista/domain/model/user/UserSettingsTest.java src/test/java/com/kista/user/domain/model/UserSettingsTest.java
rmdir src/test/java/com/kista/domain/model/user
git mv src/test/java/com/kista/application/service/user/UserServiceTest.java src/test/java/com/kista/user/application/service/UserServiceTest.java
git mv src/test/java/com/kista/application/service/user/UserProfileServiceTest.java src/test/java/com/kista/user/application/service/UserProfileServiceTest.java
git mv src/test/java/com/kista/application/service/user/UserSettingsServiceTest.java src/test/java/com/kista/user/application/service/UserSettingsServiceTest.java
git mv src/test/java/com/kista/application/service/user/UserCascadeDeleterTest.java src/test/java/com/kista/user/application/service/UserCascadeDeleterTest.java
rmdir src/test/java/com/kista/application/service/user
git mv src/test/java/com/kista/application/service/auth/TokenServiceTest.java src/test/java/com/kista/user/application/service/TokenServiceTest.java
git mv src/test/java/com/kista/application/service/auth/BlacklistServiceTest.java src/test/java/com/kista/user/application/service/BlacklistServiceTest.java
git mv src/test/java/com/kista/application/service/auth/TokenUseCaseTestConfig.java src/test/java/com/kista/user/application/service/TokenUseCaseTestConfig.java
rmdir src/test/java/com/kista/application/service/auth
```

- [ ] **Step 3: 이동 파일의 package 선언 + 상호 import 치환**

```bash
find src/main/java/com/kista/user/domain src/main/java/com/kista/user/application src/main/java/com/kista/user/config \
     src/test/java/com/kista/user/domain src/test/java/com/kista/user/application -name "*.java" -print0 | \
xargs -0 perl -pi -e '
  s/^package com\.kista\.domain\.model\.user;/package com.kista.user.domain.model;/;
  s/^package com\.kista\.domain\.model\.auth;/package com.kista.user.domain.auth;/;
  s/^package com\.kista\.application\.usecase;/package com.kista.user.application.usecase;/;
  s/^package com\.kista\.application\.port\.output;/package com.kista.user.application.port.output;/;
  s/^package com\.kista\.application\.service\.user;/package com.kista.user.application.service;/;
  s/^package com\.kista\.application\.service\.auth;/package com.kista.user.application.service;/;
  s/^package com\.kista\.application\.event;/package com.kista.user.application.event;/;
  s/^package com\.kista\.application\.config;/package com.kista.user.config;/;
  s/com\.kista\.domain\.model\.auth\./com.kista.user.domain.auth./g;
  s/com\.kista\.domain\.model\.user\./com.kista.user.domain.model./g;
  s/com\.kista\.application\.usecase\.(UserUseCase|UserProfileUseCase|GetUserSettingsQuery|UpdateBalanceCheckUseCase|UpdateNotificationPrefUseCase|UpdateStrategySuggestionsUseCase|BlacklistUseCase|TokenUseCase)/com.kista.user.application.usecase.$1/g;
  s/com\.kista\.application\.port\.output\.(UserPort|UserSettingsPort|BlacklistPort|KakaoOAuthPort|RefreshTokenPort|ApprovalPolicyPort)/com.kista.user.application.port.output.$1/g;
  s/com\.kista\.application\.event\.(NewUserRegisteredEvent|UserApprovedEvent|UserRejectedEvent|UserReappliedEvent|UserDeletedEvent)/com.kista.user.application.event.$1/g;
'
```

> **주의:** `com.kista.domain.model.user.` → `com.kista.user.domain.model.` 치환은 `NotificationType`(이미 Task 1에서 sharedkernel로 옮겨짐)에는 영향 없어야 한다 — Task 1 완료 후 이 패턴은 `User`/`UserSettings`만 남아있음을 Step 1 결과로 재확인했으므로 안전.

- [ ] **Step 4: `UserService`/`UserCascadeDeleter` 잔존 와일드카드 확인**

```bash
git grep -n "^import com\.kista\.application\.port\.output\.\*;" -- src/main/java/com/kista/user/application/service/UserService.java src/main/java/com/kista/user/application/service/UserCascadeDeleter.java
```
이 와일드카드는 이제 `com.kista.user.application.port.output.*`로 Step 3에서 치환되지 **않는다**(패턴이 특정 6개 타입명만 치환) — 여전히 레거시 `com.kista.application.port.output.*`를 가리키므로, `UserService`/`UserCascadeDeleter`가 남은 레거시 포트(`AccountPort`/`StrategyPort`/`RefreshTokenPort`... 단 `RefreshTokenPort`는 이미 이동했으므로 명시 import 필요)를 실제로 쓰는지 컴파일로 확인 후 명시 import로 교체:
```bash
./gradlew compileJava 2>&1 | grep -E "(UserService|UserCascadeDeleter).*cannot find symbol" -A2
```
`cannot find symbol: RefreshTokenPort`/`BlacklistPort`/`UserPort` 등이 뜨면(이미 `com.kista.user.application.port.output`으로 옮겨진 타입인데 레거시 wildcard가 더는 못 찾음) 해당 파일에 명시 import 추가:
```java
import com.kista.user.application.port.output.RefreshTokenPort;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.UserPort;
```
(정확히 어떤 타입이 필요한지는 컴파일 에러가 알려준다 — 이 두 파일은 각자 자기 모듈 포트만 쓰므로 레거시 wildcard(`com.kista.application.port.output.*`)는 완전히 불필요해질 가능성이 높다. 그 경우 wildcard import 라인 자체를 삭제)

- [ ] **Step 5: 전역 소비자 import 경로 치환 (이번 태스크에서 이동 안 하는 파일)**

```bash
git grep -ln "com\.kista\.domain\.model\.user\.\(User\|UserSettings\)\b\|com\.kista\.domain\.model\.auth\.\|com\.kista\.application\.usecase\.\(UserUseCase\|UserProfileUseCase\|GetUserSettingsQuery\|UpdateBalanceCheckUseCase\|UpdateNotificationPrefUseCase\|UpdateStrategySuggestionsUseCase\|BlacklistUseCase\|TokenUseCase\)\|com\.kista\.application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|com\.kista\.application\.event\.\(NewUserRegisteredEvent\|UserApprovedEvent\|UserRejectedEvent\|UserReappliedEvent\|UserDeletedEvent\)" -- src/main src/test \
  | grep -v "src/main/java/com/kista/user/\|src/test/java/com/kista/user/" \
  | grep -vE "/(AuthController|DevAuthController|SettingsController|ClientErrorLogController|FcmController)\.java" \
  | grep -vE "adapter/in/web/dto/(Kakao|Refresh|Token|UserResponse|BalanceCheck|Nickname|NotificationChannel|NotificationPref|StrategySuggestions|ClientErrorLog|TelegramUpdate)" \
  | grep -vE "adapter/in/web/security/" \
  | grep -vE "adapter/(in/schedule/RefreshTokenCleanupScheduler|out/persistence/(user|settings|auth)/|out/kakao/|out/redis/)" \
  | sort -u
```

현재 실측 기준 이 목록에 남을 것(레거시/다른 모듈 잔류, import만 갱신):
- `src/main/java/com/kista/application/service/strategy/StrategyService.java` — `UserSettings`/`UserPort`/`UserSettingsPort`
- `src/main/java/com/kista/application/service/account/AccountService.java` — 확인 필요(사전 조사에서 명시적 참조 미확인 — 컴파일로 재확인)
- `src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java` — `User.CooldownException`, `InvalidRefreshTokenException`
- `src/main/java/com/kista/adapter/out/sse/SseEmitterRegistry.java` — `User`, `UserApprovedEvent`, `UserRejectedEvent`, `RealtimeNotificationPort`(자체 소유, 무관)
- `src/main/java/com/kista/application/port/output/RealtimeNotificationPort.java` — `User.UserStatus` → Task 1에서 이미 sharedkernel로 치환됨(재확인만)
- `src/main/java/com/kista/notify/**` 다수 — `UserPort`, `User`, 이벤트 5종(위 Task 2/3에서 이미 일부 정리됨 — 잔여 확인)
- `src/main/java/com/kista/trading/**` 다수 — `UserPort`, `UserSettingsPort`, `User`, `UserSettings`, `BatchContext`
- `src/main/java/com/kista/finance/application/service/FinanceRegistrationReminderNotifier.java` — `UserPort`, `UserSettingsPort`, `User`, `UserSettings`
- `src/main/java/com/kista/admin/application/service/{AdminService,AdminQueryService,RuntimeSettingsService}.java` — `UserPort`, `UserUseCase`, `BlacklistPort`
- test 다수(각 CLOSED 모듈의 `UserPort`/`UserSettingsPort` mock)

명시 import 파일에 perl 치환 적용:
```bash
FILES=$(git grep -ln "com\.kista\.domain\.model\.user\.\(User\|UserSettings\)\b\|com\.kista\.domain\.model\.auth\.\|com\.kista\.application\.usecase\.\(UserUseCase\|UserProfileUseCase\|GetUserSettingsQuery\|UpdateBalanceCheckUseCase\|UpdateNotificationPrefUseCase\|UpdateStrategySuggestionsUseCase\|BlacklistUseCase\|TokenUseCase\)\|com\.kista\.application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|com\.kista\.application\.event\.\(NewUserRegisteredEvent\|UserApprovedEvent\|UserRejectedEvent\|UserReappliedEvent\|UserDeletedEvent\)" -- src/main src/test \
  | grep -v "src/main/java/com/kista/user/\|src/test/java/com/kista/user/" \
  | grep -vE "/(AuthController|DevAuthController|SettingsController|ClientErrorLogController|FcmController)\.java" \
  | grep -vE "adapter/in/web/dto/(Kakao|Refresh|Token|UserResponse|BalanceCheck|Nickname|NotificationChannel|NotificationPref|StrategySuggestions|ClientErrorLog|TelegramUpdate)" \
  | grep -vE "adapter/in/web/security/" \
  | grep -vE "adapter/(in/schedule/RefreshTokenCleanupScheduler|out/persistence/(user|settings|auth)/|out/kakao/|out/redis/)")

for f in $FILES; do
  perl -pi -e '
    s/com\.kista\.domain\.model\.auth\./com.kista.user.domain.auth./g;
    s/com\.kista\.domain\.model\.user\.(User|UserSettings)\b/com.kista.user.domain.model.$1/g;
    s/com\.kista\.application\.usecase\.(UserUseCase|UserProfileUseCase|GetUserSettingsQuery|UpdateBalanceCheckUseCase|UpdateNotificationPrefUseCase|UpdateStrategySuggestionsUseCase|BlacklistUseCase|TokenUseCase)/com.kista.user.application.usecase.$1/g;
    s/com\.kista\.application\.port\.output\.(UserPort|UserSettingsPort|BlacklistPort|KakaoOAuthPort|RefreshTokenPort|ApprovalPolicyPort)/com.kista.user.application.port.output.$1/g;
    s/com\.kista\.application\.event\.(NewUserRegisteredEvent|UserApprovedEvent|UserRejectedEvent|UserReappliedEvent|UserDeletedEvent)/com.kista.user.application.event.$1/g;
  ' "$f"
done
```

와일드카드 파일(`import com.kista.domain.model.user.*;` 또는 `import com.kista.application.port.output.*;` 등)이 위 색출에 걸리면 명시 import로 개별 추가 후 컴파일로 검증 — 이 프로젝트 전례상 `MarketEventNotifier`/`TradingReporter`/`CycleRotationService`/`FinanceRegistrationReminderNotifier`가 `User`/`UserSettings`를 명시 import로 쓰고 있음을 사전 확인했으므로(코드 직접 열람 완료) 이들은 위 sed로 처리된다. `TradingReporter.java`의 `import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;`(한 줄) 패턴은 `UserSettingsPort`가 첫 wildcard 대상이므로, 컴파일 에러로 `UserSettingsPort cannot find symbol`이 뜨면 명시 import `import com.kista.user.application.port.output.UserSettingsPort;`를 별도 라인으로 추가(wildcard는 그대로 둠, 명시 import가 우선 해석됨).

- [ ] **Step 6: `AdminUserViewAdapter` 물리 이동은 Task 6(어댑터)으로 미룸 — 여기선 import만 확인**

```bash
git grep -n "com\.kista\.application\.port\.output\.UserPort\|com\.kista\.domain\.model\.user\.User\b" -- src/main/java/com/kista/adapter/out/persistence/user/AdminUserViewAdapter.java
```
`AdminUserViewAdapter`는 `UserJpaRepository`(같은 패키지)만 쓰고 `User`/`UserPort`를 직접 참조하지 않으므로(사전 확인 완료) 이 Step은 확인용 — 출력 없으면 정상.

- [ ] **Step 7: 색출 재확인 + 컴파일**

```bash
git grep -ln "com\.kista\.domain\.model\.user\.\(User\|UserSettings\)\b\|com\.kista\.domain\.model\.auth\.\|com\.kista\.application\.usecase\.\(UserUseCase\|UserProfileUseCase\|GetUserSettingsQuery\|UpdateBalanceCheckUseCase\|UpdateNotificationPrefUseCase\|UpdateStrategySuggestionsUseCase\|BlacklistUseCase\|TokenUseCase\)\|com\.kista\.application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|com\.kista\.application\.event\.\(NewUserRegisteredEvent\|UserApprovedEvent\|UserRejectedEvent\|UserReappliedEvent\|UserDeletedEvent\)" -- src/main src/test | grep -v "com/kista/user/" | sort -u
```
남는 것은 Task 6 이동 대상 어댑터/DTO/security/persistence만이어야 함.

```bash
./gradlew compileJava 2>&1 | grep -E "error:|FAILED"
```
Expected: Task 6 이동 대상 어댑터(컨트롤러/DTO/security/persistence/kakao/redis)에서 `cannot find symbol` 다수 — 정상. 그 외 파일이 깨졌으면 Step 5 누락이므로 즉시 확인.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): user 모듈 코어(domain+application) 이전

User/UserSettings + auth 도메인 4종, usecase 8개, output port 6개
(UserPort/UserSettingsPort/BlacklistPort/KakaoOAuthPort/
RefreshTokenPort/ApprovalPolicyPort), 서비스 6개(User/UserProfile/
UserSettings/UserCascadeDeleter + Token/BlacklistService), 이벤트
5개, AdminBootstrapProperties(ADMIN_KAKAO_IDS seed, admin 모듈과
무관)를 com.kista.user로 이전. 어댑터 레이어는 Task 6에서 이어서
이전 — 이 시점 컴파일 에러(이동 대상 어댑터의 레거시 경로 참조)는
정상.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 어댑터(in/web + in/web/security + in/schedule + out/persistence + out/kakao + out/redis) 물리 이전 + 전체 컴파일 그린

> **SDD 오케스트레이터 주의:** 이 태스크는 컨트롤러 4 + DTO 12 + security 7 + persistence 12 + kakao 3 + redis 1 + 교차모듈 test import ~21(security) = 이번 계획에서 가장 크다. admin/stats 이전 때 유사 규모 태스크가 세션 한도로 중단됐고 `SendMessage`로 **동일 에이전트 재개**(부분 산출물 유지, 콜드 재디스패치 안 함)로 복구된 전례가 있다. 이 태스크 실행 중 에이전트가 죽으면 `ListAgents`로 상태 확인 후 `SendMessage`로 먼저 재개를 시도할 것.

**Files:**
- Move: `src/main/java/com/kista/adapter/in/web/{AuthController,DevAuthController,SettingsController,ClientErrorLogController}.java` → `src/main/java/com/kista/user/adapter/in/web/`
- Move: `src/main/java/com/kista/adapter/in/web/dto/{KakaoCallbackRequest,KakaoLoginResponse,RefreshResponse,TokenResponse,UserResponse,BalanceCheckRequest,NicknameRequest,NotificationChannelRequest,NotificationPrefRequest,StrategySuggestionsRequest,ClientErrorLogRequest,TelegramUpdateRequest}.java` → `src/main/java/com/kista/user/adapter/in/web/dto/`
- Move: `src/main/java/com/kista/adapter/in/web/security/*.java` (7개) → `src/main/java/com/kista/user/adapter/in/web/security/`
- Move: `src/main/java/com/kista/adapter/in/schedule/RefreshTokenCleanupScheduler.java` → `src/main/java/com/kista/user/adapter/in/schedule/`
- Move: `src/main/java/com/kista/adapter/out/persistence/user/{UserEntity,UserJpaRepository,UserPersistenceAdapter,AdminUserViewAdapter}.java` → `src/main/java/com/kista/user/adapter/out/persistence/user/`
- Move: `src/main/java/com/kista/adapter/out/persistence/settings/*.java` (6개) → `src/main/java/com/kista/user/adapter/out/persistence/settings/`
- Move: `src/main/java/com/kista/adapter/out/persistence/auth/*.java` (3개) → `src/main/java/com/kista/user/adapter/out/persistence/auth/`
- Move: `src/main/java/com/kista/adapter/out/kakao/{KakaoConfig,KakaoProperties,KakaoOAuthAdapter}.java` → `src/main/java/com/kista/user/adapter/out/kakao/`
- Move: `src/main/java/com/kista/adapter/out/redis/RedisBlacklistAdapter.java` → `src/main/java/com/kista/user/adapter/out/redis/`
- Modify (import 경로만, 레거시 잔류): `GlobalExceptionHandler.java`, `SseEmitterRegistry.java`, `FcmController.java`(무관, 확인만), `AccountService.java`, `StrategyService.java`, 21개 security `@Import` 테스트 파일, 각 CLOSED 모듈의 `UserPort`/`UserSettingsPort`/`UserUseCase`/`BlacklistPort` mock 테스트
- Move tests: `src/test/java/com/kista/adapter/in/web/{AuthControllerTest,AuthControllerTokenTest,DevAuthControllerTest,SettingsControllerTest,ClientErrorLogControllerTest}.java` (5개) → `src/test/java/com/kista/user/adapter/in/web/`; `src/test/java/com/kista/adapter/in/web/dto/UserResponseTest.java` → `src/test/java/com/kista/user/adapter/in/web/dto/`; `src/test/java/com/kista/adapter/in/web/security/{JwtAuthFilterTest,JwtIssuerServiceTest}.java` → `src/test/java/com/kista/user/adapter/in/web/security/`; `src/test/java/com/kista/adapter/in/schedule/RefreshTokenCleanupSchedulerTest.java` → `src/test/java/com/kista/user/adapter/in/schedule/`; `src/test/java/com/kista/adapter/out/persistence/user/{UserJpaRepositoryCountTest,UserPersistenceAdapterTest}.java` → `src/test/java/com/kista/user/adapter/out/persistence/user/`; `src/test/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapterTest.java` → `src/test/java/com/kista/user/adapter/out/persistence/settings/`; `src/test/java/com/kista/adapter/out/persistence/auth/{RefreshTokenPersistenceAdapterTest,RefreshTokenPortTestConfig,TokenServiceRotationRollbackIT}.java` → `src/test/java/com/kista/user/adapter/out/persistence/auth/`; `src/test/java/com/kista/adapter/out/kakao/KakaoOAuthAdapterTest.java` → `src/test/java/com/kista/user/adapter/out/kakao/`; `src/test/java/com/kista/adapter/out/redis/RedisBlacklistAdapterTest.java` → `src/test/java/com/kista/user/adapter/out/redis/`

**Interfaces:**
- Consumes: Task 5가 만든 `com.kista.user.{domain,application}.*`
- Produces: `com.kista.user.adapter.*` 전체 — Task 7 NamedInterface 대상 아님(internal 유지)

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔 (어댑터 경로)**

```bash
grep -rn "com\.kista\.adapter\.\(in\.web\.\(Auth\|DevAuth\|Settings\|ClientErrorLog\)\|in\.web\.security\.\|in\.schedule\.RefreshTokenCleanupScheduler\|out\.persistence\.\(user\|settings\|auth\)\|out\.kakao\|out\.redis\)" src/main/resources/ src/main/java --include='*.yml' --include='*.xml'
git grep -n '"[^"]*com\.kista\.adapter\.\(in\.web\.security\|out\.\(kakao\|redis\)\)' -- src/main/java
```
매치 시 함께 갱신(사전 실측 0건 확인됨 — 재확인용). 특히 `application-*.yml`의 Logback 로거·CORS·JWT 관련 property key 확인.

- [ ] **Step 1: 어댑터 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/user/adapter/in/web/dto
mkdir -p src/main/java/com/kista/user/adapter/in/web/security
mkdir -p src/main/java/com/kista/user/adapter/in/schedule
mkdir -p src/main/java/com/kista/user/adapter/out/persistence/user
mkdir -p src/main/java/com/kista/user/adapter/out/persistence/settings
mkdir -p src/main/java/com/kista/user/adapter/out/persistence/auth
mkdir -p src/main/java/com/kista/user/adapter/out/kakao
mkdir -p src/main/java/com/kista/user/adapter/out/redis

for f in AuthController DevAuthController SettingsController ClientErrorLogController; do
  git mv "src/main/java/com/kista/adapter/in/web/$f.java" "src/main/java/com/kista/user/adapter/in/web/$f.java"
done

for f in KakaoCallbackRequest KakaoLoginResponse RefreshResponse TokenResponse UserResponse BalanceCheckRequest NicknameRequest NotificationChannelRequest NotificationPrefRequest StrategySuggestionsRequest ClientErrorLogRequest TelegramUpdateRequest; do
  git mv "src/main/java/com/kista/adapter/in/web/dto/$f.java" "src/main/java/com/kista/user/adapter/in/web/dto/$f.java"
done

git mv src/main/java/com/kista/adapter/in/web/security/*.java src/main/java/com/kista/user/adapter/in/web/security/
rmdir src/main/java/com/kista/adapter/in/web/security

git mv src/main/java/com/kista/adapter/in/schedule/RefreshTokenCleanupScheduler.java src/main/java/com/kista/user/adapter/in/schedule/RefreshTokenCleanupScheduler.java

for f in UserEntity UserJpaRepository UserPersistenceAdapter AdminUserViewAdapter; do
  git mv "src/main/java/com/kista/adapter/out/persistence/user/$f.java" "src/main/java/com/kista/user/adapter/out/persistence/user/$f.java"
done
rmdir src/main/java/com/kista/adapter/out/persistence/user

git mv src/main/java/com/kista/adapter/out/persistence/settings/*.java src/main/java/com/kista/user/adapter/out/persistence/settings/
rmdir src/main/java/com/kista/adapter/out/persistence/settings

git mv src/main/java/com/kista/adapter/out/persistence/auth/*.java src/main/java/com/kista/user/adapter/out/persistence/auth/
rmdir src/main/java/com/kista/adapter/out/persistence/auth

git mv src/main/java/com/kista/adapter/out/kakao/*.java src/main/java/com/kista/user/adapter/out/kakao/
rmdir src/main/java/com/kista/adapter/out/kakao

git mv src/main/java/com/kista/adapter/out/redis/RedisBlacklistAdapter.java src/main/java/com/kista/user/adapter/out/redis/RedisBlacklistAdapter.java
# adapter/out/redis 디렉토리에 RedisBlacklistAdapter 하나뿐이었는지 확인 (남으면 STOP)
ls src/main/java/com/kista/adapter/out/redis/ 2>/dev/null && echo "STOP: 잔존 파일" || rmdir src/main/java/com/kista/adapter/out/redis
```

- [ ] **Step 2: 테스트 파일 물리 이동**

```bash
mkdir -p src/test/java/com/kista/user/adapter/in/web/dto
mkdir -p src/test/java/com/kista/user/adapter/in/web/security
mkdir -p src/test/java/com/kista/user/adapter/in/schedule
mkdir -p src/test/java/com/kista/user/adapter/out/persistence/user
mkdir -p src/test/java/com/kista/user/adapter/out/persistence/settings
mkdir -p src/test/java/com/kista/user/adapter/out/persistence/auth
mkdir -p src/test/java/com/kista/user/adapter/out/kakao
mkdir -p src/test/java/com/kista/user/adapter/out/redis

for f in AuthControllerTest AuthControllerTokenTest DevAuthControllerTest SettingsControllerTest ClientErrorLogControllerTest; do
  git mv "src/test/java/com/kista/adapter/in/web/$f.java" "src/test/java/com/kista/user/adapter/in/web/$f.java"
done
git mv src/test/java/com/kista/adapter/in/web/dto/UserResponseTest.java src/test/java/com/kista/user/adapter/in/web/dto/UserResponseTest.java
git mv src/test/java/com/kista/adapter/in/web/security/JwtAuthFilterTest.java src/test/java/com/kista/user/adapter/in/web/security/JwtAuthFilterTest.java
git mv src/test/java/com/kista/adapter/in/web/security/JwtIssuerServiceTest.java src/test/java/com/kista/user/adapter/in/web/security/JwtIssuerServiceTest.java
rmdir src/test/java/com/kista/adapter/in/web/security
git mv src/test/java/com/kista/adapter/in/schedule/RefreshTokenCleanupSchedulerTest.java src/test/java/com/kista/user/adapter/in/schedule/RefreshTokenCleanupSchedulerTest.java

git mv src/test/java/com/kista/adapter/out/persistence/user/UserJpaRepositoryCountTest.java src/test/java/com/kista/user/adapter/out/persistence/user/UserJpaRepositoryCountTest.java
git mv src/test/java/com/kista/adapter/out/persistence/user/UserPersistenceAdapterTest.java src/test/java/com/kista/user/adapter/out/persistence/user/UserPersistenceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/persistence/user
git mv src/test/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapterTest.java src/test/java/com/kista/user/adapter/out/persistence/settings/UserSettingsPersistenceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/persistence/settings
git mv src/test/java/com/kista/adapter/out/persistence/auth/RefreshTokenPersistenceAdapterTest.java src/test/java/com/kista/user/adapter/out/persistence/auth/RefreshTokenPersistenceAdapterTest.java
git mv src/test/java/com/kista/adapter/out/persistence/auth/RefreshTokenPortTestConfig.java src/test/java/com/kista/user/adapter/out/persistence/auth/RefreshTokenPortTestConfig.java
git mv src/test/java/com/kista/adapter/out/persistence/auth/TokenServiceRotationRollbackIT.java src/test/java/com/kista/user/adapter/out/persistence/auth/TokenServiceRotationRollbackIT.java
rmdir src/test/java/com/kista/adapter/out/persistence/auth
git mv src/test/java/com/kista/adapter/out/kakao/KakaoOAuthAdapterTest.java src/test/java/com/kista/user/adapter/out/kakao/KakaoOAuthAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/kakao
git mv src/test/java/com/kista/adapter/out/redis/RedisBlacklistAdapterTest.java src/test/java/com/kista/user/adapter/out/redis/RedisBlacklistAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/redis
```

- [ ] **Step 3: package 선언 + import 일괄 치환 (이동 파일)**

```bash
find src/main/java/com/kista/user/adapter src/test/java/com/kista/user/adapter -name "*.java" -print0 | \
xargs -0 perl -pi -e '
  s/^package com\.kista\.adapter\.in\.web\.dto;/package com.kista.user.adapter.in.web.dto;/;
  s/^package com\.kista\.adapter\.in\.web\.security;/package com.kista.user.adapter.in.web.security;/;
  s/^package com\.kista\.adapter\.in\.web;/package com.kista.user.adapter.in.web;/;
  s/^package com\.kista\.adapter\.in\.schedule;/package com.kista.user.adapter.in.schedule;/;
  s/^package com\.kista\.adapter\.out\.persistence\.user;/package com.kista.user.adapter.out.persistence.user;/;
  s/^package com\.kista\.adapter\.out\.persistence\.settings;/package com.kista.user.adapter.out.persistence.settings;/;
  s/^package com\.kista\.adapter\.out\.persistence\.auth;/package com.kista.user.adapter.out.persistence.auth;/;
  s/^package com\.kista\.adapter\.out\.kakao;/package com.kista.user.adapter.out.kakao;/;
  s/^package com\.kista\.adapter\.out\.redis;/package com.kista.user.adapter.out.redis;/;
  s/com\.kista\.adapter\.in\.web\.dto\.(KakaoCallbackRequest|KakaoLoginResponse|RefreshResponse|TokenResponse|UserResponse|BalanceCheckRequest|NicknameRequest|NotificationChannelRequest|NotificationPrefRequest|StrategySuggestionsRequest|ClientErrorLogRequest|TelegramUpdateRequest)/com.kista.user.adapter.in.web.dto.$1/g;
  s/com\.kista\.adapter\.in\.web\.security\./com.kista.user.adapter.in.web.security./g;
  s/com\.kista\.domain\.model\.auth\./com.kista.user.domain.auth./g;
  s/com\.kista\.domain\.model\.user\.(User|UserSettings)\b/com.kista.user.domain.model.$1/g;
  s/com\.kista\.application\.usecase\.(UserUseCase|UserProfileUseCase|GetUserSettingsQuery|UpdateBalanceCheckUseCase|UpdateNotificationPrefUseCase|UpdateStrategySuggestionsUseCase|BlacklistUseCase|TokenUseCase)/com.kista.user.application.usecase.$1/g;
  s/com\.kista\.application\.port\.output\.(UserPort|UserSettingsPort|BlacklistPort|KakaoOAuthPort|RefreshTokenPort|ApprovalPolicyPort)/com.kista.user.application.port.output.$1/g;
  s/com\.kista\.application\.event\.(NewUserRegisteredEvent|UserApprovedEvent|UserRejectedEvent|UserReappliedEvent|UserDeletedEvent)/com.kista.user.application.event.$1/g;
  s/com\.kista\.admin\.domain\.model\.AdminUserView/com.kista.admin.domain.model.AdminUserView/g;
'
```

와일드카드 확인:
```bash
git grep -n "^import com\.kista.*\*;" -- src/main/java/com/kista/user/adapter src/test/java/com/kista/user/adapter
```
잔존 와일드카드가 있으면 수동으로 `com.kista.user.*` 로 교체 후 컴파일로 검증.

- [ ] **Step 4: `GlobalExceptionHandler`/`SseEmitterRegistry`/`AccountService`/`StrategyService` import 경로 갱신**

```bash
git grep -n "com\.kista\.domain\.model\.user\.User\b\|com\.kista\.domain\.model\.auth\.\|com\.kista\.application\.event\.\(UserApproved\|UserRejected\)Event" \
  src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java \
  src/main/java/com/kista/adapter/out/sse/SseEmitterRegistry.java \
  src/main/java/com/kista/application/service/account/AccountService.java \
  src/main/java/com/kista/application/service/strategy/StrategyService.java
```
매치되는 각 파일에 perl 치환 적용:
```bash
perl -pi -e '
  s/com\.kista\.domain\.model\.auth\./com.kista.user.domain.auth./g;
  s/com\.kista\.domain\.model\.user\.(User|UserSettings)\b/com.kista.user.domain.model.$1/g;
  s/com\.kista\.application\.port\.output\.(UserPort|UserSettingsPort)/com.kista.user.application.port.output.$1/g;
  s/com\.kista\.application\.event\.(UserApprovedEvent|UserRejectedEvent)/com.kista.user.application.event.$1/g;
' src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java src/main/java/com/kista/adapter/out/sse/SseEmitterRegistry.java src/main/java/com/kista/application/service/account/AccountService.java src/main/java/com/kista/application/service/strategy/StrategyService.java
```

- [ ] **Step 5: 21개 security `@Import` 테스트 파일 + 나머지 전역 소비자 색출/치환**

```bash
git grep -ln "com\.kista\.adapter\.in\.web\.security\." -- src/test src/main | grep -v "com/kista/user/"
```
색출된 전체 파일에 일괄 치환:
```bash
FILES=$(git grep -ln "com\.kista\.adapter\.in\.web\.security\." -- src/test src/main | grep -v "com/kista/user/")
for f in $FILES; do
  perl -pi -e 's/com\.kista\.adapter\.in\.web\.security\./com.kista.user.adapter.in.web.security./g' "$f"
done
```

나머지 전역 소비자(각 CLOSED 모듈의 `UserPort`/`UserSettingsPort`/`UserUseCase`/`BlacklistPort` mock, 명시 import) 색출 및 재확인:
```bash
git grep -ln "com\.kista\.domain\.model\.user\.\(User\|UserSettings\)\b\|com\.kista\.domain\.model\.auth\.\|com\.kista\.application\.usecase\.\(UserUseCase\|UserProfileUseCase\|GetUserSettingsQuery\|UpdateBalanceCheckUseCase\|UpdateNotificationPrefUseCase\|UpdateStrategySuggestionsUseCase\|BlacklistUseCase\|TokenUseCase\)\|com\.kista\.application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|com\.kista\.application\.event\.\(NewUserRegisteredEvent\|UserApprovedEvent\|UserRejectedEvent\|UserReappliedEvent\|UserDeletedEvent\)" -- src/main src/test | grep -v "com/kista/user/" | sort -u
```
남는 전체 목록에 동일 치환 적용:
```bash
FILES=$(git grep -ln "com\.kista\.domain\.model\.user\.\(User\|UserSettings\)\b\|com\.kista\.domain\.model\.auth\.\|com\.kista\.application\.usecase\.\(UserUseCase\|UserProfileUseCase\|GetUserSettingsQuery\|UpdateBalanceCheckUseCase\|UpdateNotificationPrefUseCase\|UpdateStrategySuggestionsUseCase\|BlacklistUseCase\|TokenUseCase\)\|com\.kista\.application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|com\.kista\.application\.event\.\(NewUserRegisteredEvent\|UserApprovedEvent\|UserRejectedEvent\|UserReappliedEvent\|UserDeletedEvent\)" -- src/main src/test | grep -v "com/kista/user/")
for f in $FILES; do
  perl -pi -e '
    s/com\.kista\.domain\.model\.auth\./com.kista.user.domain.auth./g;
    s/com\.kista\.domain\.model\.user\.(User|UserSettings)\b/com.kista.user.domain.model.$1/g;
    s/com\.kista\.application\.usecase\.(UserUseCase|UserProfileUseCase|GetUserSettingsQuery|UpdateBalanceCheckUseCase|UpdateNotificationPrefUseCase|UpdateStrategySuggestionsUseCase|BlacklistUseCase|TokenUseCase)/com.kista.user.application.usecase.$1/g;
    s/com\.kista\.application\.port\.output\.(UserPort|UserSettingsPort|BlacklistPort|KakaoOAuthPort|RefreshTokenPort|ApprovalPolicyPort)/com.kista.user.application.port.output.$1/g;
    s/com\.kista\.application\.event\.(NewUserRegisteredEvent|UserApprovedEvent|UserRejectedEvent|UserReappliedEvent|UserDeletedEvent)/com.kista.user.application.event.$1/g;
  ' "$f"
done
```

- [ ] **Step 6: 전체 컴파일 + 대상 테스트**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음. 실패 시 잔존 옛 경로 재검색:
```bash
git grep -n "com\.kista\.domain\.model\.\(user\|auth\)\|com\.kista\.application\.service\.\(user\|auth\)\|com\.kista\.application\.usecase\.\(UserUseCase\|UserProfileUseCase\|GetUserSettingsQuery\|UpdateBalanceCheckUseCase\|UpdateNotificationPrefUseCase\|UpdateStrategySuggestionsUseCase\|BlacklistUseCase\|TokenUseCase\)\|com\.kista\.application\.port\.output\.\(UserPort\|UserSettingsPort\|BlacklistPort\|KakaoOAuthPort\|RefreshTokenPort\|ApprovalPolicyPort\)\|com\.kista\.application\.event\.\(NewUserRegisteredEvent\|UserApprovedEvent\|UserRejectedEvent\|UserReappliedEvent\|UserDeletedEvent\)\|com\.kista\.adapter\.in\.web\.security\.\|com\.kista\.adapter\.out\.\(kakao\|redis\)\." src/main src/test
```

```bash
./gradlew test --tests 'com.kista.user.*' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL` — 전체 스위트(security 21개 교차모듈 테스트 포함) 그린 확인. 이 태스크가 크므로 전체 스위트를 여기서 1회 돌려 교차 파급을 조기 확인한다(전역 CLAUDE.md "전체 스위트는 최종 1회" 원칙의 예외 — 이 태스크가 이번 계획에서 가장 넓은 파급 범위라 조기 검증이 실익 있음, Task 8에서도 최종 1회 더 돈다).

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): user 모듈 어댑터 이전 (in/web, security, schedule, persistence, kakao, redis)

AuthController/DevAuthController/SettingsController/
ClientErrorLogController + DTO 12종, JWT/Security 인프라 7개
(JwtAuthFilter/JwtIssuerService/JwtDecoderConfig/
InternalTokenAuthFilter/SecurityConfig/RefreshTokenCookieHelper/
OpenApiConfig) 전체, RefreshTokenCleanupScheduler,
persistence(user/settings/auth) 12개, kakao 3개, redis 1개를
com.kista.user로 이전. security 패키지 이동에 따라 전 8모듈에
분산된 @Import(JwtAuthFilter/SecurityConfig) 테스트 21개 + UserPort/
UserSettingsPort/UserUseCase/BlacklistPort mock 테스트 다수의
import 경로 일괄 갱신. 전체 컴파일·전체 테스트 스위트 그린 확인.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 모듈 선언(CLOSED + NamedInterface) + ApplicationModules.verify() 검증

**Files:**
- Create: `src/main/java/com/kista/user/package-info.java`
- Modify (필요 시): `src/test/java/com/kista/architecture/ModulithArchitectureTest.java`(참고용 확인, 통상 수정 불필요 — `ApplicationModules.of(KistaApplication.class)`가 자동 스캔)

**Interfaces:**
- Produces: `com.kista.user` CLOSED 모듈, "domain"(domain.model + domain.auth 병합)/"usecase"(application.usecase)/"port"(application.port.output)/"event"(application.event) 4개 NamedInterface.
- Consumes: 없음(선언 태스크).

- [ ] **Step 1: `package-info.java` 작성**

```java
// user(+auth) 애그리게이트 — 가입·승인·설정 + JWT/RT/블랙리스트/카카오 OAuth.
// "domain"(domain.model + domain.auth 병합)·"usecase"(application.usecase)·
// "port"(application.port.output)·"event"(application.event) 4개 NamedInterface 공개 —
// application.service·adapter.*는 의도적으로 비공개(모듈 내부 구현).
// User nested enum 3종(UserRole/UserStatus/NotificationChannel) + NotificationType은
// com.kista.sharedkernel로 이관됨 — 이 모듈은 sharedkernel을 소비할 뿐 소유하지 않는다.
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.CLOSED,
    allowedDependencies = {}
)
package com.kista.user;
```

> **실행자 주의:** `allowedDependencies`를 명시적으로 비워두면 Spring Modulith가 기본적으로 다른 모듈의 공개 NamedInterface 전체를 허용하는지, 아니면 빈 배열이 "의존 금지"로 해석되는지는 기존 8개 모듈의 `package-info.java`를 먼저 열어 정확한 어노테이션 속성 패턴을 그대로 베낀다(`admin`/`stats`/`privacy` 등 최근 것 중 하나를 템플릿으로). 이 프로젝트는 지금까지 8개 모듈 모두 동일한 어노테이션 형태를 써왔으므로 새로 설계하지 말고 그대로 복제한다.

```bash
cat src/main/java/com/kista/admin/package-info.java
```
위 출력된 정확한 어노테이션 속성(NamedInterface 등록 방식 포함)을 그대로 참고해 `com.kista.user/package-info.java`를 완성한다 — "domain"/"usecase"/"port"/"event" 4개 NamedInterface를 admin/stats 패턴과 동일하게 등록.

- [ ] **Step 2: `domain`/`application` 서브패키지에 NamedInterface 등록용 `package-info.java` 필요 여부 확인**

```bash
find src/main/java/com/kista/admin -name "package-info.java" | xargs -I{} sh -c 'echo "== {} =="; cat {}'
```
admin의 패턴(각 NamedInterface 서브패키지마다 `@NamedInterface("이름")` 붙은 `package-info.java`가 있는지, 아니면 모듈 루트 `package-info.java` 하나에 전부 등록하는지)을 그대로 확인 후 `com.kista.user`의 `domain/model`, `domain/auth`, `application/usecase`, `application/port/output`, `application/event` 서브패키지에 동일 패턴 적용:

```bash
mkdir -p src/main/java/com/kista/user/domain/model src/main/java/com/kista/user/domain/auth  # 이미 존재
```
(admin이 서브패키지별 `package-info.java`를 쓰는 패턴이면 아래처럼 각각 생성 — 정확한 어노테이션은 Step 1에서 확인한 admin 패턴을 그대로 복제)

`src/main/java/com/kista/user/domain/model/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("domain")
package com.kista.user.domain.model;
```

`src/main/java/com/kista/user/domain/auth/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("domain")
package com.kista.user.domain.auth;
```

`src/main/java/com/kista/user/application/usecase/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.user.application.usecase;
```

`src/main/java/com/kista/user/application/port/output/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("port")
package com.kista.user.application.port.output;
```

`src/main/java/com/kista/user/application/event/package-info.java`:
```java
@org.springframework.modulith.NamedInterface("event")
package com.kista.user.application.event;
```

- [ ] **Step 3: `ApplicationModules.verify()` 실행**

```bash
./gradlew test --tests 'com.kista.architecture.ModulithArchitectureTest' 2>&1 | grep -E "FAILED|BUILD|Violat"
```

**예측되는 순환 상황**: Task 1~3에서 사전 해소한 3개 순환(admin↔user, trading↔user, finance↔user) 외에 pairwise 분석이 놓친 전이 순환이 있을 수 있다(market `market→notify→trading→market`, privacy `privacy→notify→trading→privacy`, stats `stats↔notify` 교훈 — 매 모듈마다 최소 1건씩 실측에서 드러난 패턴). **여기서 `verify()`가 예측 못한 순환을 보고하면 즉시 멈추고 정확한 위반 내용을 보고할 것 — 추측으로 수정하지 않는다.**

Expected(사전 해소가 완전했다면): `BUILD SUCCESSFUL`, 위반 0건.

- [ ] **Step 4: `HexagonalArchitectureTest` 재확인**

```bash
./gradlew test --tests 'com.kista.architecture.HexagonalArchitectureTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL` — 와일드카드 매처(`..domain..` 등)가 새 모듈 구조를 자동 커버하므로 이 파일 자체는 수정 불필요(포트 위치 전환 마이그레이션 완료 이후 8개 모듈 전부 무수정으로 통과해온 전례).

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): user 모듈 선언(@ApplicationModule CLOSED)

com.kista.user를 9번째 Spring Modulith CLOSED 모듈로 선언.
"domain"(domain.model+domain.auth)·"usecase"·"port"·"event" 4개
NamedInterface 공개 — application.service·adapter.*는 비공개.
ApplicationModules.verify() 통과 확인 — Task 1~3에서 사전 해소한
admin↔user·trading↔user·finance↔user 순환 외 추가 전이 순환 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 문서 갱신 + 전체 테스트 스위트 최종 검증

> 문서 전용 태스크 — 전역 CLAUDE.md 규칙에 따라 리뷰어 검수 생략, 전체 테스트 스위트 통과로 대체 검증.

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`

**Interfaces:** 없음(문서만).

- [ ] **Step 1: `architecture.md`에 `com.kista.user` 절 추가**

기존 8개 모듈(finance/notify/broker/trading/market/privacy/stats/admin) 절과 동일한 형식으로 `com.kista.user` 절을 admin 절 다음에 삽입 — domain/application/adapter 트리 요약, NamedInterface 4개 설명, sharedkernel 이관 사실, cascade 이벤트화 사실, ApprovalPolicyPort 포트 역전 사실을 기록. "Spring Modulith 점진 도입" 문단 말미에 `admin✅(8번째) → user✅(9번째)`로 갱신하고 순환 해소 요약 문장 추가(market/privacy/stats/admin과 동일한 각주 스타일 — "실측 정정" 언급 포함, 스펙이 "user는 notify만 순환"이라 했으나 실측 결과 notify는 순환 아니고 admin/trading/finance가 순환이었다는 정정).

- [ ] **Step 2: `constraints.md` 갱신**

"Spring Modulith 이전 중 신규 파일 배치" 절에 user 항목 추가(다른 8개 모듈과 동일 형식 — 신규 user/auth 코드는 `com.kista.user` 안에 추가할 것, NamedInterface 4개 명시).

"User nested enum 패턴" 절 전체를 다음으로 교체(정정 각주 포함, admin의 `RuntimeSettings` 트리 own-type 정정 각주와 동일 스타일):
```markdown
### User nested enum 패턴 — sharedkernel 이관 완료 (2026-09-01 정정)
- ~~`User.UserRole`/`UserStatus`/`NotificationChannel` — 독립 enum 파일 금지, `User` record 내 nested enum~~ **폐기**: user 모듈 CLOSED 전환 시 `com.kista.sharedkernel.{UserRole,UserStatus,NotificationChannel}` 독립 타입으로 이관 완료(Strategy 4종보다 먼저 이관됨 — Strategy.Ticker/Type/Status/CycleSeedType은 4단계 account+strategy-config 이전 때 동일 패키지에 합류 예정). DB `@Enumerated(STRING)` 컬럼 상수명은 byte-identical 유지.
- 신규 유저 기본 알림 채널: `User.DEFAULT_CHANNEL = NotificationChannel.NONE`(domain 상수, `User`에 유지) — 서비스/컨트롤러에서 직접 하드코딩 금지
```

"모듈 경계 포트 시그니처 — 각 모듈은 자기 소유 타입만 사용" 절에 admin↔user 포트 역전 사례(`ApprovalPolicyPort`)를 broker `Direction`/privacy `PrivacyOrderType`/settings `RecurringMode` 계열에 이은 own-type 5번째 인스턴스로 추가.

- [ ] **Step 3: 스펙 문서에 실측 정정 각주 추가**

`docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`의 "착수 순서 (실측 기반, v2)" 절 3번 항목에 완료 각주 추가(market/privacy/stats/admin과 동일 스타일):
```markdown
3. **user(+auth)** — ✅ 완료(2026-09-01, `2026-09-01-modulith-user-migration` 실행 계획 8개 태스크). CLOSED 9번째 모듈, 4개 NamedInterface("domain"/"usecase"/"port"/"event"). **실측 정정**: 이 표는 "user는 notify만 순환"으로 판정했으나 사전 실측 결과 정반대였다 — `user↔notify`는 순환이 아니었고(notify의 `UserNotificationPort` 호출자는 finatce뿐, notify→user는 이미 EPR ID+재조회 패턴), 대신 `user↔admin`(AdminService의 UserCascadeDeleter 직접 참조 + RuntimeSettingsPort 상호소비)·`user↔trading`(UserCascadeDeleter의 cyclePosition/strategyCycle 직접 호출)·`user↔finance`(UserCascadeDeleter의 finance 6포트+그룹승계 직접 호출) 3개가 실순환이었다. admin의 `[^6]` 각주(이전 선언 전 사전 실측으로 특정)와 동일하게 물리 이전 전 3개 태스크(sharedkernel enum 추출·ApprovalPolicyPort 포트 역전·cascade 이벤트 팬아웃)로 전부 사전 해소 — mid-migration 발견 0건. **다음 모듈(account+strategy-config) 착수 시 교훈**: "스펙 표가 어느 방향을 안전하다고 적었든" 신뢰하지 말고 사전 조사 서브에이전트로 forward/backward import를 직접 코드까지 열어 재확인할 것 — 이번에도 스펙의 순환 판정 방향 자체가 뒤집혔다(5번째 반복: market/privacy/stats/admin에 이어).
```

- [ ] **Step 4: 전체 테스트 스위트 최종 검증**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`, 실패 0건.

```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0' | head
```
Expected: 출력 없음(전부 failures="0").

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): user 모듈 이전 반영 — architecture.md/constraints.md/스펙 갱신

com.kista.user를 9번째 CLOSED 모듈로 architecture.md에 반영.
constraints.md "User nested enum 패턴" 규칙을 sharedkernel 이관
완료로 정정(Strategy 4종보다 먼저 이관). 스펙 문서에 "user는
notify만 순환" 판정이 실측과 정반대였음을 정정 각주로 기록
(admin/trading/finance가 실순환, notify는 아니었음).
전체 테스트 스위트 최종 검증 통과.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 체크리스트 (계획 작성자 자체 확인 완료)

- **스펙 커버리지**: 스펙 "착수 순서 v2" 3단계(user+auth) 전체, "nested enum 정책 개정" 절(sharedkernel 이관), "cascade 방향 순환 함정" 절(이벤트 전환) 모두 Task 1~3에서 다룸. ✅
- **순환 해소 완결성**: 실측된 3개 순환(admin/trading/finance) 전부 Task 2/3에서 해소, notify는 순환 아님을 재확인해 스코프 아웃 처리. ✅
- **플레이스홀더 스캔**: "TBD"/"나중에" 등 문구 없음, 모든 코드 블록이 실제 파일 내용(사전 코드 열람 기반) 기준으로 작성됨. ✅
- **타입 일관성**: `ApprovalPolicyPort.approvalRequiredForUpdate()` 메서드명이 Task 2 전체(포트 정의/구현/UserService 소비/테스트)에서 동일, `UserCascadeListener`(trading·finance 양쪽 동명 클래스, 패키지로 구분)와 `UserFcmCleanupListener` 클래스명이 Task 3 전체에서 일관. ✅
- **Interfaces 블록**: 각 태스크가 Produces/Consumes로 다음 태스크가 참조할 정확한 FQN을 명시. ✅
