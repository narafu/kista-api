# Spring Modulith notify 모듈 이전 설계

원칙 SSOT는 [2026-08-27-spring-modulith-migration-design.md](2026-08-27-spring-modulith-migration-design.md) (모듈 템플릿, common 정책, 포트 위치, 테스트 전략). 이 문서는 notify 모듈(2번 타깃)의 구체 파일 인벤토리·크로스모듈 의존 분석만 다룬다.

## 전제

finance 모듈 이전 완료(브랜치 `worktree-modulith-finance-migration`, 미병합). 이번 작업은 그 위에서 이어간다. 이 브랜치도 모듈 구분뿐 아니라 리팩토링을 겸한다 — 작업 중 발견되는 개선 지점은 임의 수정하지 말고 적극적으로 보고할 것.

## 이동 대상

- `domain/port/out/{NotifyPort, UserNotificationPort, FcmDeviceTokenPort, TelegramBotInfoPort}.java` (4개)
- `adapter/out/notify/*` 전부 (14개) → `com.kista.notify.adapter.out.gateway`: TelegramConfig, TelegramProperties, TelegramHttpClient, TelegramAdapter, TelegramBotInfoAdapter, TelegramUserNotificationAdapter, FcmConfig, FcmAdapter, CompositeUserNotificationAdapter, CycleEndedNotifier, CycleLifecycleNotifier, TradingReportNotifier, UserDeletedNotifier, OrderCancelFailureNotifier — `notify.notify` 중복 명명 회피를 위해 `gateway`로 개명(외부 알림 전송 어댑터), Notifier류를 `adapter/in/event`로 재분류하는 신규 구조 도입은 안 함(스코프 밖 리팩토링)
- `adapter/out/persistence/fcm/*` (3개) → `com.kista.notify.adapter.out.persistence` (flat, finance 관례와 동일): FcmDeviceTokenPersistenceAdapter, FcmDeviceTokenEntity, FcmDeviceTokenJpaRepository
- `adapter/in/telegram/*` (4개): TelegramUpdate, TelegramBotService, TelegramWebhookController, TelegramApiClient
- 대응 테스트 파일 전체 (동일 패키지 구조로 이동)

## 이번 스코프 제외 (판단 근거)

- `application/event/*` (이벤트 클래스 10개 전부) — publisher(trading/user 등)가 아직 미이전. 이벤트 타입은 발행 모듈이 소유하는 게 원칙이라 notify로 끌어오지 않는다. 각 이벤트의 실제 소속 모듈이 이전될 때 그쪽으로 옮긴다.
- `domain/model/user/NotificationType.java` — 사용자 알림 채널 설정값(user 도메인 개념), notify는 참조만 한다.
- `domain/port/out/RealtimeNotificationPort.java` + `adapter/out/sse/*`(SseEmitterRegistry, TradeSseEmitterRegistry) — 구현체가 TradeSseEmitterRegistry와 물리적으로 얽혀 있고 AuthController/TradeStreamController가 직접 소비. SSE 소유권은 별도 판단 필요, 이번 스코프 아님. `TradingReportNotifier`는 기존처럼 old top-level 경로로 계속 import.
- `FcmController` + `dto/FcmTokenRequest` — `UserProfileUseCase`를 호출하는 user 쪽 인바운드 어댑터라 notify 소유 아님. 그대로 둔다.

## 모듈 내부 구조

finance와 동일한 템플릿:

```
com.kista.notify/
├── domain/
│   └── port/out/        ← NotifyPort, UserNotificationPort, FcmDeviceTokenPort, TelegramBotInfoPort
├── adapter/
│   ├── in/telegram/      ← TelegramWebhookController 등
│   └── out/{gateway,persistence}  ← gateway: 외부 알림 전송(Telegram/FCM), persistence: flat(finance 관례와 동일, notify 중복 명명 회피)
└── package-info.java     ← @ApplicationModule
```

domain/model, domain/port/in 둘 다 없음 — FcmDeviceToken 삭제로 domain/model 자체가 불필요해짐(notify 자체 도메인 모델 없음). port/in도 없음(notify 자체 UseCase 없음, 모두 이벤트 리스너 또는 다른 모듈이 호출하는 out 포트).

## Named Interface

finance 패턴과 동일한 이름 규칙 유지: `domain/port/out`을 `"domain"` 이름으로 공개(`@org.springframework.modulith.NamedInterface("domain")`). 병합 대상 패키지가 하나뿐이라도(domain/model 없음) 다른 모듈과의 명명 일관성을 위해 같은 이름 사용. application/adapter는 internal.

## 크로스모듈 의존

### notify → old top-level (기존 finance 패턴과 동일 방향, 허용)

- `application.event.*` (CycleEndedEvent, CycleCompletedEvent, NewCycleStartedEvent, TradingReportReadyEvent, UserDeletedEvent, OrderCancelFailedEvent, NewUserRegisteredEvent, UserApprovedEvent, UserRejectedEvent, UserReappliedEvent)
- `domain.model.{order.TradeEvent, order.Order, broker.Execution, user.User, user.NotificationType, account.Account, strategy.Strategy, strategy.Strategy.Ticker, strategy.AccountBalance, strategy.TradingReport, strategy.CyclePositionHistoryEntry}`
- `domain.port.out.RealtimeNotificationPort`
- `domain.port.in.{UserUseCase, PortfolioUseCase}` (TelegramBotService가 사용)

### old top-level → notify (신규 방향 — import 경로만 변경, 로직 변경 없음)

`NotifyPort` 사용처 (17개): `adapter/in/schedule/{BatchContextFactory, SchedulerJobRunner, TradingOpenScheduler, RefreshTokenCleanupScheduler}`, `adapter/in/aop/ErrorLogAspect`, `application/service/privacy/PrivacyService`, `application/service/market/FearGreedService`, `application/service/stats/{HousingPriceIndexService, HousingBenchmarkService}`, `application/service/trading/{TradingPriceFetcher, TradingService, TradingReporter, ManualTradingService, CycleRotationService, VrCycleRolloverService, VrReconfigureService, TradingOrderExecutor}`

`UserNotificationPort` 사용처 (3개, NotifyPort와 중복 제외): `application/service/trading/MarketEventNotifier`, `application/service/trading/TradingService`(중복), `application/service/trading/VrCycleRolloverService`(중복)

`FcmDeviceTokenPort`/`TelegramBotInfoPort` 사용처: `application/service/user/UserProfileService`

**finance 잔여 항목 정리**: `finance/application/service/FinanceRegistrationReminderNotifier`의 `UserNotificationPort` import를 새 notify 경로로 갱신 — finance 스펙이 "user/notify 모듈 이전 시점에 재정의"하기로 미뤄둔 부분을 이번에 닫는다.

## 테스트

- `ModulithArchitectureTest.verifyModularStructure()` — notify 추가 후에도 순환 없어야 함
- `HexagonalArchitectureTest` — 이미 와일드카드 일반화 완료, 추가 변경 불필요
- 이동 대상 테스트 파일 전체 동일 패키지 구조로 이동(기계적)

## DB

변경 없음. `fcm_device_tokens` 등은 이미 public 스키마.

## 문서

`docs/agents/architecture.md`, `constraints.md`, `CLAUDE.md`의 notify 관련 서술을 finance 이전 때(commit a0ee1a30)와 동일한 방식으로 갱신.

## 리팩토링 관찰 (구현 중 추가 발견 시 갱신)

- `domain/model/user/FcmDeviceToken.java` record — 코드베이스 전체에서 사용처 0건 확인(Port/Entity/Adapter 모두 UUID/String primitive로만 동작, 이 record를 생성·참조하는 코드 없음). dead code로 판단해 삭제 확정(사용자 승인) — 결과적으로 notify 모듈엔 domain/model 서브패키지 자체가 없음
- `com.kista.notify.adapter.out.gateway` 명명 — 스펙 초안의 `adapter/out/notify`가 모듈명과 중복(`notify.notify`)돼 `gateway`로 개명(사용자 승인)
- 그 외 발견되는 개선 지점은 임의 수정하지 말고 구현 중 사용자에게 즉시 보고

## 미해결 확인 필요 항목

없음.
