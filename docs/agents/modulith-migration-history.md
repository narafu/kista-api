# Spring Modulith 마이그레이션 이력 (2026-08 ~ 2026-09)

이 문서는 레거시 최상위 `com.kista.domain`/`com.kista.application`/`com.kista.adapter` 패키지를 Spring Modulith 애그리게이트 모듈(finance→notify→broker→trading→market→privacy→stats→admin→user→account→strategyconfig(이후 trading 병합)→platform→web→matching(커널 추출로 신설)) 13개로 해체한 마이그레이션의 이전 순서·순환 해소 사실 기록이다. **자동 로드되지 않음** — 모듈 경계 이력을 조사하거나 유사한 향후 마이그레이션을 계획할 때만 필요 시 Read. 현재 아키텍처(패키지 위치·NamedInterface 구성)는 `docs/agents/architecture.md`가 SSOT.

---

## 레거시 최상위 domain/application/adapter 패키지 해소 상세

```
domain/          ← (레거시 최상위 com.kista.domain 패키지 자체가 소멸 — package-info까지 삭제됨. 아래는 이전 이력)
  model/         ← (폐지됨 — 레거시 최상위 domain/model 디렉토리 자체가 소멸. 마지막 잔류였던 account/strategy도 각각 com.kista.account.domain.model/com.kista.strategyconfig.domain.model로 이전 완료: broker/kis/toss는 com.kista.broker.domain.model로, order 전체 및 strategy 실행 이력 17개는 com.kista.trading.domain.model로, market(공포탐욕지수·시장휴장일)은 com.kista.market.domain.model로, privacy(FIDA 기준 매매표 + PrivacyDates 발행일↔거래일 헬퍼)는 com.kista.privacy.domain.model로, stats(통계 20타입) + backtest(커맨드·결과 5타입)는 com.kista.stats.domain.model(+.backtest)로, admin(관리자 read-model 9 + 런타임 설정 6, 총 15타입)은 com.kista.admin.domain.model(flat — admin/settings 서브패키지 없음)로, user(User/UserSettings)+auth(RefreshToken 등)는 com.kista.user.domain.{model,auth}로 이전됨 — User.UserRole/UserStatus/NotificationChannel nested enum + NotificationType은 com.kista.sharedkernel 독립 타입으로 별도 이관, 아래 "com.kista.sharedkernel/" 참고)
                   Strategy.Type/Status/Ticker/CycleSeedType 등 nested enum도 com.kista.sharedkernel 독립 타입으로 이관 완료 (→ constraints.md "Account ↔ Strategy 분리")
  (strategy/ 디렉토리 없음 — 전략 구현 클래스(Infinite/ReverseInfinite/Privacy/VrStrategy) 14개 전체가 com.kista.trading.domain.strategy로 이전됨, 아래 "com.kista.trading/" 참고)
  (domain/port/in, domain/port/out 폐지됨 — 레거시 포트는 application/usecase, application/port/output로 이전됨, 아래 application/ 절 참고)

application/        ← (레거시 최상위 com.kista.application 패키지 자체가 소멸 — package-info까지 삭제됨. 아래는 이전 이력)
  usecase/       ← (비어 소멸 — 마지막 잔류였던 MarketUseCase가 com.kista.market.application.usecase로 이전됨. 각 모듈의 usecase는 해당 모듈 절 참고)
  port/output/   ← (소멸 — 마지막 잔류였던 RealtimeNotificationPort가 com.kista.notify.application.port.output으로 이전돼 notify "port" NamedInterface에 합류(SSE 레지스트리와 함께 이동). HeartbeatPort는 그 전에 com.kista.trading.application.port.output으로 이전됨. 각 모듈의 port는 해당 모듈 절 참고)
  MetricsConfig  ← (소멸 — com.kista.web.config로 이전됨, 이후 2026-09-10 캐치올 정리로 com.kista.platform.metrics로 재이전)
  service/       ← (폐지됨 — 레거시 최상위 application/service 디렉토리 자체가 소멸, 전부 각 모듈로 이전 완료: broker는 com.kista.broker.application.service로, trading은 com.kista.trading.application.service로, market은 com.kista.market.application.service로, privacy는 com.kista.privacy.application.service로, stats/backtest/portfolio는 com.kista.stats.application.service로, admin/settings(RuntimeSettings 계열)는 com.kista.admin.application.service로, user/auth는 com.kista.user.application.service로, account는 com.kista.account.application.service로, strategy(Strategy 애그리게이트)는 com.kista.strategyconfig.application.service로 이전됨)
  event/         ← (폐지됨 — 사용자 승인/거부/재신청/신규가입/탈퇴 이벤트 5종은 com.kista.user.application.event로 이전, "event" NamedInterface로 공개. 사이클 종료/신규시작·매매리포트·주문취소실패 등 매매 관련 이벤트는 com.kista.trading.application.event로 이전됨) 전부 Spring Modulith Event Publication Registry로 추적됨(`event_publication` 테이블, 재기동 시 미완료 이벤트 자동 재시도) — 리스너 annotation은 기존 @TransactionalEventListener 그대로, User/Account를 담던 이벤트는 평문 비밀값이 DB에 저장되지 않도록 ID(userId/accountId)만 담고 리스너가 UserPort/AccountPort로 재조회한다

adapter/in/         ← (레거시 최상위 com.kista.adapter 패키지 자체가 소멸 — package-info까지 삭제됨. 아래는 이전 이력)
  schedule/      ← (소멸 — 공통 골격 SchedulerJobRunner/SchedulerLockService/SchedulerLifecycleEvent는 com.kista.platform.scheduling으로 이전됨. RefreshTokenCleanupScheduler는 com.kista.user.adapter.in.schedule로, 매매 스케쥴러 TradingOpenScheduler/TradingCloseScheduler/BatchContextFactory는 com.kista.trading.adapter.in.schedule로, 공포탐욕지수·시장휴장일 캘린더 갱신 스케쥴러(FearGreedScheduler/MarketCalendarRefreshScheduler)는 com.kista.market.adapter.in.schedule로, KB Land·시장지수 동기화 스케쥴러(KbLandHousingBenchmarkScheduler/KbLandPriceIndexScheduler/MarketIndexPriceSyncScheduler)는 com.kista.stats.adapter.in.schedule로 이전됨)
  web/           ← (소멸 — com.kista.adapter 소멸로 크로스모듈 컨트롤러도 전부 재배치: TradingCycleController(3모듈 오케스트레이터)/MetaController(enum SSOT)는 com.kista.web으로, DashboardController/StatisticsController(KIS 전용 live)/TossStatisticsController(Toss 전용 live)는 com.kista.stats.adapter.in.web으로, FcmController/TradeStreamController(SSE) + 신규 StatusStreamController(`GET /api/auth/status-stream`)는 com.kista.notify.adapter.in.web으로 이전됨. 그 외 컨트롤러(Account/Asset/Auth/Settings/DevAuth/ClientErrorLog/OrderCancel/FearGreed/MarketHoliday/FidaOrder/Stats/Backtest/Admin* 등)는 이미 각 애그리게이트 모듈로 이전됨)
  web/security/  ← (폐지됨 — JwtAuthFilter/InternalTokenAuthFilter/SecurityConfig/JwtDecoderConfig/JwtIssuerService 등 전부 com.kista.user.adapter.in.web.security로 이전됨)

adapter/out/
  (broker/kis/toss/mock/persistence/kistoken은 com.kista.broker.adapter.out.{internal,kis,toss,mock,persistence}로 이전됨)
  (marketdata/ 폐지됨 — CommonMarketPriceFeed는 com.kista.broker.adapter.out.marketdata로 이전됨 — broker 모듈 내부라 NamedInterface 미공개)
  (feargreed/·persistence/calendar/·persistence/feargreed/ 폐지됨 — com.kista.market.adapter.out.{feargreed,persistence.calendar,persistence.feargreed}로 이전됨)
  (persistence/privacy/ 폐지됨 — com.kista.privacy.adapter.out.persistence로 이전됨)
  (kbland/·alpaca/·persistence/housingbenchmark/·persistence/marketindex/ 폐지됨 — com.kista.stats.adapter.out.{kbland,alpaca,persistence.housingbenchmark,persistence.marketindex}로 이전됨. AlpacaIndexPriceAdapter가 stats로 옮겨가며 레거시 adapter/out/alpaca 디렉토리 소멸 — market판 AlpacaCalendarAdapter만 남고 빈 이름 충돌은 계속 marketAlpacaConfig로 회피)
  (persistence/audit/·persistence/settings/·persistence/user/·persistence/auth/ 전부 폐지됨 — RuntimeSettings 3파일만 com.kista.admin.adapter.out.persistence.{audit,settings}로, UserSettings*/UserNotificationPref* 6파일·UserEntity 3파일·RefreshTokenEntity 3파일은 com.kista.user.adapter.out.persistence.{settings,user,auth}로 이전됨)
  (redis/ 폐지됨 — RedisBlacklistAdapter는 com.kista.user.adapter.out.redis로 이전됨)
  (persistence/ 소멸 — 레거시 최상위 공통 persistence base(BaseAuditEntity/BaseCreatedAtEntity/JpaAuditingConfig)는 com.kista.platform.persistence로 이전됨. 어그리게이트별 서브패키지는 각 모듈로 이전 완료)
  (sse/ 소멸 — SseEmitterRegistry(사용자별 SSE)/TradeSseEmitterRegistry(매매 이벤트 SSE)는 com.kista.notify.adapter.out.sse로 이전됨)
  (kakao/ 폐지됨 — KakaoOAuthAdapter는 com.kista.user.adapter.out.kakao로 이전됨)
  (heartbeat/ 폐지됨 — HeartbeatAdapter는 유일 소비자가 trading 스케쥴러라 com.kista.trading.adapter.out.heartbeat로 이전됨)
  (crypto/ 소멸 — AesCryptoService/AccountNoHasher는 com.kista.platform.crypto로 이전됨)
```

---

## 모듈별 순환 발견·해소 경위

**sharedkernel**: UserRole/UserStatus/NotificationChannel(옛 User nested enum) + NotificationType이 먼저 이관됐고, StrategyType/StrategyStatus/StrategyTicker/StrategyCycleSeedType(옛 Strategy nested enum, 커밋 a81e76eb), Broker(옛 Account nested enum, `BrokerAccountRef.Broker` byte-identical 복제 삭제)도 합류.

**broker**: `broker↔trading`/`notify↔trading`/`broker→trading→notify→broker` 순환 — broker는 자기 소유 타입(Direction/PriceSnapshot/BrokerBalance 등)만 포트 시그니처에 사용하고 trading이 매핑, notify는 trading이 발행하는 이벤트를 구독(TradingAlertNotifier가 TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/MarketCloseEvent/BatchInterruptedEvent 6종 구독)하는 방식으로 단방향화.

**market**: ① `market→notify→trading→market` 전이 순환 — `FearGreedService`의 notify 직접 호출을 `FearGreedFetchFailedEvent` 발행으로 전환(`MarketAlertNotifier` 구독). ② `market→trading` — `MarketHolidayController`가 쓰던 `trading.domain.model.DstInfo` 참조를 market 자체 소유 `MarketSessionSnapshot`으로 대체.

**privacy**: ① `privacy↔trading` — privacy가 `trading.domain.model.Order`를 빌려 쓰던 것을 `PrivacyOrderType`/`PrivacyOrderDirection`/`FidaPlannedOrder` 자체 소유로 전환(`PrivacyStrategy`가 `Order.OrderType.valueOf(name())`으로 매핑, 상수명 byte-identical). ② `privacy→notify→trading→privacy` 전이 순환 — `PrivacyService`의 notify 직접 호출을 `PrivacyAlertRaisedEvent` 발행으로 전환(`PrivacyAlertNotifier` 구독).

**stats**: `stats↔notify` 2-cycle — `stats→notify`(`HousingBenchmarkService`/`HousingPriceIndexService`)를 `StatsAlertRaisedEvent` 발행으로 전환(`StatsAlertNotifier` 구독). 통계 서비스 3종(AccountStatisticsService/TossStatisticsService/BrokerStatisticsRouter)은 account+strategy-config 이전 완료 후 순환 없음을 확인해 stats로 합류.

**admin**: `trading↔admin` — 레거시 `*CreationResolver`/`StrategyService`가 `domain/model/settings`를 소비 ↔ admin이 trading `Order`/`StrategyCycle` 소비하던 순환을 trading이 `StrategyCreationSettings`/`StrategyFieldSettings`/`RecurringMode`를 `trading.domain.strategy`에 자체 소유하고 `StrategyService.resolveCreationSettings()`가 매핑하는 방식으로 끊었다. `admin→notify` 참조는 0건이라 event NamedInterface 없음.
- `AdminUserView`/`AdminUserViewPort`(관리자 read-model)는 admin에서 user 소유로 이관 — admin은 user "domain"/"port" NamedInterface로 소비만 한다.
- `RuntimeSettings`의 `Strategy`/`Account` nested enum 참조 소멸, `RuntimeSettingsPort` 직접 소비는 3개 own-type 포트 역전(`ApprovalPolicyPort`/`BrokerEnabledPort`/`StrategyCreationPolicyPort`)으로 대체.
- `@Table(schema="public")`: AuditLogEntity/AppErrorLogEntity/RuntimeSettingsEntity 3개는 플랫폼 공통 테이블이라 이전 후에도 public 스키마 명시 유지.
- ClientErrorLogController는 원래 소속인 admin에 위치.

**user**(+auth): admin↔user — `RuntimeSettingsPort`/`ApprovalPolicyPort` 포트 역전. trading↔user·finance↔user — `UserCascadeDeleter`의 cyclePosition/strategyCycle 직접 호출(trading) 및 finance 6포트+그룹승계 직접 호출(finance)을 `UserDeletedEvent` 이벤트 팬아웃으로 전환(EPR 기반). notify↔user — `RefreshTokenCleanupScheduler`의 `NotifyPort` 직접 주입을 `SchedulerJobRunner` 공통 골격 재사용으로 전환, `TelegramBotInfoPort`를 notify 소유에서 user 소유로 이관.
- account/strategy-config는 각자 이전 시 재검토·해소됨(아래 항목 참고).

**account**: ① `account↔strategy-config` — `AccountService.delete()`가 strategy-config 소유 `StrategyPort.deleteByAccountId()`를 직접 호출하던 것을 `AccountDeletedEvent` 발행으로 전환. ② `admin↔account` — `AccountService.requireBrokerEnabled()`의 `RuntimeSettingsPort` 직접 참조를 `BrokerEnabledPort`(admin `RuntimeSettingsService` 구현)로 교체. ③ `broker↔account` — `account→broker`(`AccountService`의 `BrokerConnectionTesters` 직접 호출)와 `broker→account`(11개 포트+KIS/Toss/Mock 어댑터의 `Account` 직접 참조)가 맞물린 순환을 broker own-type(`BrokerAccountRef`/`SellableQuantity`/`BrokerCredentialException`/`BrokerRateLimitException`) 전환으로 해소.

**strategyconfig**(이후 trading 병합): ① admin↔strategyconfig — `StrategyCreationPolicyPort`(strategyconfig 정의·admin RuntimeSettingsService 구현) 포트 역전. ② user↔strategyconfig — `UserCascadeDeleter`를 strategyconfig 자체 `UserDeletedEvent` 리스너로, 활성 전략 카운트 조회를 `ActiveStrategyCountPort`(user 정의·strategyconfig 구현) 포트 역전으로 해소. ③ broker↔strategyconfig — `MockBrokerAdapter`의 직접 참조를 기존 `MockSimulationDataPort` 확장(`StrategyRefLite`)으로 해소. ④ trading↔strategyconfig — trading이 상시 참조하던 `Strategy`를 읽기 전용 `StrategyRef` + 조회/명령 분리 포트(`StrategyLookupPort`/`StrategyPausePort`)로 해소(이후 병합으로 소멸).
- strategyconfig↔admin — `StrategyPort.findSummariesByCycleIds`가 admin 소유 `AdminCycleStrategySummary`를 직접 반환하던 것을 strategyconfig own-type `StrategySummary`로 좁히고 admin `AdminQueryService`가 매핑.
- trading↔strategyconfig — `VrReconfigureService`가 strategyconfig의 `StrategyUseCase`/`StrategyDetail`을 직접 참조하던 것을 `VrReconfigureUseCase.reconfigure()` 반환 타입을 `void`로 좁히고 재조회를 `TradingCycleController`로 이동해 해소.
- privacy↔strategyconfig — `PrivacyTradePort.findBaseIfPrivacy(Strategy, LocalDate)`가 실측 결과 프로덕션 호출부 0건인 죽은 코드였음이 확인돼 삭제.

strategy-config 이전은 A(Strategy nested enum 4종 sharedkernel 이관, 커밋 `a81e76eb`) → B(`StrategyVersion`/`StrategyInfiniteDetail`/`StrategyVrDetail`+`VrStrategyLifecycle`을 trading 소유로 이관) → C(남는 `Strategy` 애그리게이트로 strategyconfig 모듈 신설 + 위 순환 해소)로 진행됐다.

**platform/web (레거시 adapter/application 최종 해소)**: `com.kista.platform`(인프라 leaf)·`com.kista.web`(앱셸)는 애그리게이트 모듈이 아니라 레거시 `adapter`/`application` shim을 최종 해소하며 신설됐다. SSE 뭉치(SseEmitterRegistry/TradeSseEmitterRegistry + RealtimeNotificationPort + FcmController/TradeStreamController/StatusStreamController)는 notify로, `CommonMarketPriceFeed`는 broker로, 통계 live 컨트롤러 3종은 stats로 이동했다. `SchedulerJobRunner`의 `NotifyPort` 직접 주입은 `SchedulerLifecycleEvent` 팬아웃(notify `SchedulerNotifier` 구독)으로 전환.

---

## Spring Modulith 점진 도입 순서 및 NamedInterface 구성 변천

`finance`가 첫 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개 — application.service·adapter는 비공개. 포트 위치 전환(2026-08-30, `2026-08-30-port-location-migration-design.md`) 이전엔 "domain" 하나에 model+port를 병합 공개했었다). 레거시 최상위 shim 패키지 중 `domain`·`application`·`adapter`는 전부 소멸했다(각각 package-info까지 삭제 — `domain`이 첫 소멸, `application`/`adapter`가 마지막). 마지막 잔존물이던 `application`의 MetricsConfig/RealtimeNotificationPort와 `adapter`의 크로스모듈 컨트롤러·SSE 레지스트리·공통 persistence base·crypto·스케쥴러 골격은 각각 `com.kista.web`(앱셸 CLOSED sink)·`com.kista.platform`(인프라 leaf OPEN)·`com.kista.notify`로 재배치됐다. `com.kista.common`(순수 Spring 비의존 유틸 shim)도 2026-09-10 잔여 4파일이 각자 목적지로 이전되며 완전 소멸했다 — 레거시 최상위 shim은 이제 하나도 남지 않았다. `ApplicationModules.verify()`(`ModulithArchitectureTest`)와 일반화된 `HexagonalArchitectureTest`(`..domain..` 등 와일드카드 매처로 옛 최상위 구조·새 모듈 구조를 규칙 하나로 동시 커버) 둘 다 `com.kista.architecture` 패키지에서 실행된다 — 전자는 모듈 **간** 경계, 후자는 모듈 **내부** 레이어 방향을 각각 담당하는 직교 축.

`notify`가 두 번째 이전 모듈이다(`@ApplicationModule` CLOSED, 자체 domain/model 없이 application.port.output만 "port" NamedInterface로 공개 — 포트 위치 전환 이전엔 domain/port/out을 "domain" 이름으로 공개했었다).

`broker`가 세 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain/model+domain/model.kis+domain/model.toss)·"port"(application/port/output)·"application"(application/service) 3개 NamedInterface 공개 — adapter/out은 KIS/Toss/Mock 연동 구현 디테일이라 의도적으로 비공개. 포트 위치 전환 이전엔 domain/port/out을 "domain"에 병합 공개했었다).

`trading`이 네 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.strategy)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개 — application.service·adapter.out.*은 비공개. 포트 위치 전환 이전엔 domain.port.{in,out}을 "domain"에 병합 공개했었다).

`market`이 다섯 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"port"(application.port.output)·"event"(application.event) 3개 NamedInterface 공개 — application.{usecase,service}·adapter는 비공개).

`privacy`가 여섯 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"port"(application.port.output)·"usecase"(application.usecase)·"event"(application.event) 4개 NamedInterface 공개 — application.service·adapter는 비공개).

`stats`가 일곱 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.model.backtest)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event)·"schedule"(adapter.in.schedule) 5개 NamedInterface 공개 — application.service·domain.backtest·adapter.in.web·adapter.out.*는 비공개).

`admin`이 여덟 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model, flat 15타입)·"usecase"(application.usecase, 7개)·"port"(application.port.output, 3개) 3개 NamedInterface 공개 — application.service·adapter는 비공개).

`user`(+auth)가 아홉 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model+domain.auth)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event) 4개 NamedInterface 공개 — application.service·adapter.*·config는 비공개).

`account`가 열 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output)·"event"(application.event) 4개 NamedInterface 공개 — application.service·adapter.*는 비공개).

`strategyconfig`가 열한 번째 이전 모듈이다(`@ApplicationModule` CLOSED, "domain"(domain.model)·"usecase"(application.usecase)·"port"(application.port.output) 3개 NamedInterface 공개 — application.service·adapter.out.persistence는 비공개. event/schedule NamedInterface 없음, admin과 동일 사유).

`com.kista.platform`(인프라 leaf, 열두 번째)·`com.kista.web`(앱셸, 열세 번째)은 애그리게이트 모듈이 아니라 레거시 `adapter`/`application` shim 최종 해소로 신설됐다(위 "platform/web" 절 참고).

---

## strategyconfig → trading 모듈 병합 (2026-09-07)

13개 모듈 CLOSED 전환 완료 후, 모듈 경계 own-type 부채(`docs/agents/constraints.md` "모듈 경계 own-type — 정당화 게이트")를 전수 재검토하는 과정에서 `strategyconfig`(열한 번째 이전 모듈)가 own-type을 통해서만 존속하는 얇은 위성 모듈이었음이 드러났다. 판정 기준(constraints.md "모듈 경계 own-type — 정당화 게이트")을 적용한 결과 `strategyconfig↔trading` 관계는 (a) 순환 불가피도, (b) 외부 계약 분리도 성립하지 않았다 — `strategyconfig→trading` 13참조 vs `trading→strategyconfig` 0(주석 2줄뿐), 외부 소비자(admin 21/stats 16/web 10)는 전부 이미 trading을 단방향 참조 중이었다. 병합해도 새 순환이 생기지 않음을 `user→trading`/`account→trading`(둘 다 0건) 실측으로 사전 확인한 뒤 `com.kista.strategyconfig`(22파일) 전체를 `com.kista.trading` 내부로 흡수했다.

병합으로 own-type 우회 장치 4개(`StrategyRef`/`StrategyLookupPort`/`StrategyPausePort`/`StrategyLookupAdapter`)가 통째로 소멸했다 — `StrategyRef`는 병합 전 strategyconfig 소유 `Strategy`와 필드가 완전히 동일한 복제였고, 나머지 3개는 그 복제를 매개하던 ISP 분리 포트·어댑터였다. `StrategyPort`(옛 strategyconfig 소유, 병합 후 trading 내부 포트)가 `findTickerById`/`pause` default 메서드를 흡수해 그대로 대체했다. 이 사례는 이 프로젝트에서 처음으로 "own-type 부채 자체가 잘못된 모듈 구획의 증상"이었던 케이스다 — 이전까지의 own-type들은 진짜 모듈 순환을 막기 위한 정당한 설계(broker/privacy/trading order enum, DTO 삼중복제 — 전부 (b) 외부 계약 분리)였던 것과 대비된다.

같은 재검토에서 발견된 진짜 부채 2건도 함께 정리했다: admin↔trading own-type이던 settings 3종(`StrategyCreationSettings`/`StrategyFieldSettings`/`RecurringMode`, 검증 로직까지 byte-identical 복제)은 외부 계약이 없어 `com.kista.sharedkernel`로 승격, `RuntimeSettingsService`의 양방향 매핑 코드가 통째로 소멸했다. `AdminCycleStrategySummary`(admin)는 strategyconfig `StrategySummary`와 byte-identical 쌍둥이 + 죽은 import였다는 게 확인돼 삭제하고 admin이 `StrategySummary`를 직접 소비하도록 전환했다.

모듈 수는 13 → 12로 줄었다(finance/notify/broker/trading/market/privacy/stats/admin/user/account/platform/web). 나머지 own-type(broker/privacy/trading 3종 order enum, DTO 삼중복제, narrowing projection들)은 게이트 재검토 결과 정당한 설계로 확인돼 유지했다 — 상세는 constraints.md 참고. 고아 스캔(own-type 대응 원본 타입 전수 참조 실측)도 이 재검토에서 함께 수행했으며 잔존 고아는 0건이었다(직전 세션에서 발견된 4건은 이미 정리된 상태).
