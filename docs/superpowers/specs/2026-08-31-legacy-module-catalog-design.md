# 레거시 최상위 패키지 모듈 카탈로그 재편 설계

> **개정 이력**: 최초 작성 시(v1) "strategy-config가 결합 가장 적어 즉시 착수 가능"으로 판단했으나, 실측 결과 틀린 판단이었음이 드러나 전면 재작성함(v2). grep 예측이 아닌 실제 import 그래프 전수 조사로 대체 — 아래 "결합도 실측" 절 참고.

## 배경/목적

Spring Modulith 4모듈(finance→notify→broker→trading) 이전([[project_modulith_migration]])이 2026-08-30 완료됐다. 원본 스펙(`2026-08-27-spring-modulith-migration-design.md`)의 "전체 모듈 카탈로그"엔 애초에 15개 모듈이 명시돼 있었다: `finance, notify, broker, kis, toss, user, account, strategy, trading, auth, market, stats, admin, privacy, settings`. kis/toss는 broker로 흡수됐으니 실질 이전 완료는 4/12, 나머지 8개(user/account/strategy/auth/market/stats/admin/privacy/settings — strategy는 실행 이력만 trading으로 갔고 설정 이력은 레거시 잔류)는 미착수 상태로 레거시 최상위 4패키지(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 377개 파일로 남아있다.

이 문서는 이 잔여분의 **목표 모듈 카탈로그**와 **실측 기반 착수 순서**를 정의한다.

## 결합도 실측 — 방법론

v1은 grep 스팟체크로 "어느 후보가 싼가"를 예측했는데, 이 방식 자체가 두 번 틀렸다:
1. 이미 CLOSED인 broker/notify/trading의 **포트 시그니처**가 `Account`/`Strategy`(전체 레코드) 및 그 nested enum(`Ticker`/`Type`/`Status`/`CycleSeedType`)을 직접 파라미터로 받고 있다는 걸 처음엔 놓침 — broker 8개 포트 인터페이스, notify 2개 포트, trading 3곳에서 확인됨.
2. 후보 간(user↔account) 순환도 처음엔 "1건 존재"로 잘못 나왔다가, 재확인 결과 `AccountUseCase.listByUser(UUID userId)`의 메서드명에 포함된 "User" 문자열을 grep이 타입 참조로 오탐한 것으로 판명 — 실제로는 순환 아님.

이후 조사는 각 후보의 **정확한 소속 파일 목록을 먼저 확정**하고, 이미 CLOSED인 4모듈 및 다른 6개 후보 각각과의 **양방향 import를 전수 확인**(정방향/역방향 각각 파일:라인 단위로)하는 방식으로 전환했다. 아래 결과는 이 방식으로 실측한 것이다.

## 결합도 실측 — 이미 CLOSED 4모듈과의 순환

| 후보 | 순환 여부 | 원인 | 해소 난이도 |
|---|---|---|---|
| **market** | 없음 | forward만 존재(FearGreedService→notify, MarketHolidayService→broker), backward 0건 | 즉시 착수 가능 |
| **stats**(+backtest+portfolio) | 없음 | forward 다수(broker/trading 전역 소비), backward 0건(usecase/port/persistence까지 정밀 확인 완료. notify의 `TelegramBotService`가 `PortfolioUseCase` 참조하지만 단방향이라 순환 아님) | 즉시 착수 가능 |
| **admin** | 없음 | forward 다수(broker/trading/account/strategy-config/stats까지), backward 0건(usecase 6개·port 2개까지 정밀 확인) | 즉시 착수 가능 |
| **privacy** | **있음**(v1에서 놓쳤던 발견) | privacy 4개 파일(`PrivacyTradeBase`/`FidaOrderCommand`/`PrivacyTradePersistenceAdapter`/`PrivacyTradeBaseOrderEntity`)이 `trading.domain.model.Order`를 그대로 빌려씀(forward) ↔ trading 13개 파일이 `PrivacyTradeBase`/`PrivacyTradePort` 참조(backward) | 작음 — privacy가 자체 경량 타입(`PlannedOrder` 등)으로 교체하면 forward 자체가 사라짐, 4개 파일만 수정 |
| **user**(+auth) | **있음**(notify만) | `User` 전체 레코드가 notify `UserNotificationPort` 13개 메서드에 직접 시그니처로 박혀있음(backward). forward는 `UserProfileService`→notify(`FcmDeviceTokenPort`/`TelegramBotInfoPort`) 1개 파일. `User`의 nested enum(UserRole/UserStatus/NotificationChannel)은 이미 CLOSED 4모듈 포트에 0건 — Strategy/Account보다 훨씬 깨끗함. user↔trading·user↔finance는 순환 아님(단방향) | 중간 — notify 13개 메서드를 EPR 이벤트 전환 때 이미 쓴 패턴(User 전체 대신 UUID+포트 재조회)으로 통일하면 해소 |
| **account** | **있음** | forward: `AccountService.register()`의 `BrokerConnectionTesters` 호출 1곳(정당) + `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`(통계 서비스, 성격상 stats 소유가 맞음) 3곳. backward: `Account` 전체 레코드가 broker 포트 8개(`BrokerPricePort`×6메서드/`LiveBalancePort`/`SellableQuantityPort`/`BrokerOrderCorrectionPort`×2/`BrokerAccountPort`/`MarginPort`×2/`ExecutionPort`) + notify 2개(`UserNotificationPort`×5/`NotifyPort`×1) + trading 1개(`TradingExecutionUseCase.execute`) 시그니처에 직접 박혀있음 | 중간 — 통계 서비스 3개를 stats로 재배치하면 forward가 1곳으로 축소(공짜). backward(broker 8개 포트)는 별도 리트로핏 필요 |
| **strategy-config** | **있음**(v1이 "가장 싸다"고 틀리게 판단했던 대상) | forward: `StrategyService`→broker(`BrokerAdapterRegistry`/`BrokerCallGuard`/`BrokerPricePort`/`MarginPort`, 등록 시 검증 — 정당) + trading(`StrategyCreationResolver(s)`, 등록 시 기본 종목 결정 — 정당), `VrStrategyLifecycle`→trading(`CyclePosition`/`StrategyCycle`/`StrategyCycleVrDetail`/`StrategyCycleVrPort` — 이건 사이클 상태 조작이라 trading 소유가 맞음). backward: `Strategy` 전체 레코드 3곳(notify 2+trading 1) + nested enum(주로 Ticker) 9개 인터페이스 약 15개 메서드(broker `StockInfoPort`/`SellableQuantityPort`/`ExecutionPort`/`LiveBalancePort`/`BrokerPricePort`×4, trading `OrderPort`×2, notify `UserNotificationPort`/`NotifyPort`) | 중간 — `VrStrategyLifecycle`을 trading으로 재배치하면 forward 절반 축소. backward(broker 9개 인터페이스)는 별도 리트로핏 필요 |

**규모 비용에 대한 정정**: `Strategy.Ticker`(176개 파일)/`Strategy.Type`(101)/`Strategy.Status`(48)/`Strategy.CycleSeedType`(48) 사용 파일 수는 대부분 기계적 import 경로 교체(2026-08-30 포트 위치 전환 때 103개 파일 규모로 이미 검증된 작업 유형)라 비용의 핵심이 아니다. 진짜 비용은 **포트 시그니처 소유권**(broker 8~9개 인터페이스, notify 2개, trading 3~4곳)이며 이 개수는 기존 `trading-broker-notify 디커플링`(2026-08-29, 8태스크)과 동급이거나 작다.

## 결합도 실측 — 후보 간(7개 후보 상호) 순환

| 쌍 | 결과 | 원인 |
|---|---|---|
| user↔account | **순환 아님**(재확인 결과 오탐 정정) | `AccountUseCase.listByUser(UUID userId)`의 메서드명 "User" 텍스트를 초기 grep이 오탐 |
| user↔admin | **순환**(settings 재분류로 해소) | 원인 파일 1개: `RuntimeSettingsService`(`RuntimeSettingsUseCase`+`AdminSettingsUseCase` 동시 구현, `AuditLogPort` 사용) — settings를 user가 아니라 admin으로 재분류하면 해소 |
| user↔strategy | **순환**(부분 해소 가능) | user→strategy 5개 중 3개는 settings 관련(위와 동일 재분류로 해소), 나머지는 `UserCascadeDeleter`(탈퇴 cascade가 `StrategyPort` 직접 호출) ↔ `StrategyService`(등록 시 `UserSettings.balanceCheckEnabled` 조회, 정당한 forward) — cascade 방향만 별도 처리 필요(아래 참고) |
| account↔strategy | **순환**(부분 해소 가능) | `AccountService`(계좌 삭제 cascade가 `StrategyPort.deleteByAccountId()` 호출) ↔ `StrategyService`(등록 시 `Account`/`AccountPort` 조회, 정당한 forward) — 마찬가지로 cascade 방향만 별도 처리 |
| privacy↔strategy, privacy↔account, privacy↔user, privacy↔admin | 순환 없음(단방향 또는 무관계) | |
| market↔나머지 전부 | 순환 없음 | |
| stats↔account/strategy/privacy, admin↔account/strategy/privacy/market | 순환 없음(forward만, 리프 확정) | |

**cascade 방향 순환에 대한 중요한 함정**: "DB `ON DELETE CASCADE`로 바꿔서 앱 레벨 포트 호출을 제거하면 되지 않나"는 직관은 **이 프로젝트에서 안 통한다** — `users`/`accounts`/`strategy`는 전부 소프트 삭제(`deleted_at` UPDATE, `@SQLRestriction`)라 실제 `DELETE` 문이 실행되지 않으므로 FK `ON DELETE CASCADE`가 발동하지 않는다(constraints.md "소프트 삭제 패턴" 참고). 해소하려면 `UserCascadeDeleter`/`AccountService`의 직접 순차 포트 호출을 **이벤트 발행/구독**(`UserDeletedEvent`/`AccountDeletedEvent`를 user/account가 발행하고 strategy-config가 구독해 자기 행을 스스로 soft-delete)으로 전환해야 한다 — Event Publication Registry([[project_event_publication_registry]])가 이미 이 인프라를 갖추고 있어 새 이벤트 타입 추가만 하면 됨.

## 목표 아키텍처 — 최종 7모듈 카탈로그 (v2, settings 재배치 반영)

- **user** (+auth 흡수, settings는 제외)
- **account**
- **strategy-config** (Strategy/StrategyVersion/StrategyInfiniteDetail/StrategyVrDetail/StrategyDetail/StrategySeedPreview/RegisterStrategyCommand/UpdateStrategyCommand)
- **privacy** (FIDA 기준 매매표 — PRIVACY 전략 실행 로직과는 별개 실체)
- **market**
- **stats** (+backtest, +portfolio 흡수)
- **admin** (+settings 흡수 — v1에서는 user에 붙였으나 실측 결과 admin이 맞음, "결합도 실측 — 후보 간 순환" 표 참고)

모듈 내부 구조 템플릿·포트 위치(`application/usecase`+`application/port/output`)·`common/` 비-모듈 유지 정책은 기존 4모듈과 동일하게 따른다.

### nested enum 정책 개정 — "공유 커널"이 아니라 "전역 공용 어휘"

`constraints.md`의 "Strategy: Type/Status/Ticker/CycleSeedType는 nested enum, 독립 파일 금지" 및 "User: UserRole/UserStatus/NotificationChannel도 nested enum" 규칙은 **레거시 최상위가 하나의 OPEN 패키지였던 시절의 전제**(nested여도 어차피 다 같은 패키지) 위에 서 있었다. Strategy를 진짜 CLOSED 모듈로 승격하는 순간 이 전제가 깨진다.

DDD Shared Kernel(스펙 원문의 "2~3개 모�듈만 합의한 도메인 개념")과는 성격이 다르다 — `Strategy.Ticker`는 176개 파일, 즉 사실상 전 모듈이 쓴다. 정확한 명칭은 **전역 공용 어휘(ubiquitous vocabulary)**: 자기 자신은 다른 모듈에 의존하지 않는(outbound reference 0) 순수 값 타입이라 `common/`과 동일한 "선언된 모듈 서브패키지 밖 = Modulith 검증 제외" 메커니즘을 그대로 쓸 수 있다.

- `Strategy.Ticker`/`Strategy.Type`/`Strategy.Status`/`Strategy.CycleSeedType`를 `Strategy` record 밖으로 꺼내 새 최상위 비-모듈 패키지(가칭 `com.kista.sharedkernel` 또는 `common/` 확장)에 독립 타입으로 선언
- `User.UserRole`/`User.UserStatus`/`User.NotificationChannel`도 동일 처리(이쪽은 이미 CLOSED 4모듈 포트에 0건이라 시급성은 낮지만, user 모듈 CLOSED 전환 시 일관성을 위해 같이 처리 권장)
- 이 개정은 `constraints.md`의 두 nested enum 규칙을 명시적으로 대체한다 — 향후 세션이 옛 규칙과 충돌해 재조사하지 않도록 이 스펙 승인 시 constraints.md도 함께 갱신

## 착수 순서 (실측 기반, v2)

1. **market, stats(+backtest+portfolio), admin(+settings)** — 순환 없음 확정, 즉시 착수. 셋 다 리프 성격이라 순서 무관, 병렬 진행 가능.
2. **privacy** — trading.Order 직접 참조 4개 파일을 자체 타입으로 교체 후 착수. 소규모.
3. **user(+auth)** — notify `UserNotificationPort` 13개 메서드 + `NotifyPort`류를 EPR 이벤트 전환 때 쓴 ID+포트재조회 패턴으로 통일 후 착수. nested enum 3개 sharedkernel 이관도 이 단계에서 함께.
4. **account, strategy-config** — 서로 얽혀있어 분리 착수 어려움, 묶어서 별도 서브스펙 진행:
   - broker 8~9개 포트 인터페이스를 own-type 패턴(Direction/OrderType 선례)으로 전환
   - `AccountStatisticsService`/`TossStatisticsService`/`BrokerStatisticsRouter`→stats 재배치, `VrStrategyLifecycle`→trading 재배치
   - 탈퇴/계좌삭제 cascade를 `UserDeletedEvent`/`AccountDeletedEvent` 발행-구독으로 전환
   - Strategy nested enum sharedkernel 이관(3번 user 단계와 별개로 여기서 완료)
   - 이 묶음은 규모·성격상 `trading-broker-notify 디커플링`(2026-08-29)과 동급 — 별도 스펙+계획 문서로 진행

## 스코프 아웃

- **스케쥴러 배포 분리**(kista-api 배포와 독립된 프로세스로 스케쥴러 실행) — 컴파일 경계가 아닌 배포 토폴로지 결정이라 이 스펙과 성격이 다름. 별도 브레인스토밍으로 다룬다.
- 4단계(account/strategy-config 묶음)의 구체 태스크 분해 — 별도 서브스펙에서 진행.

## 다음 단계

이 스펙 승인 후 `writing-plans`로 **1단계(market/stats/admin) 착수 대상부터** 실행 계획 작성. 어느 것부터/몇 개를 한 계획에 묶을지는 계획 작성 시점에 결정.
