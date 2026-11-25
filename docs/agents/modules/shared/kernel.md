## com.kista.shared.kernel (`:shared`)

순수 어휘 패키지. `@ApplicationModule(Type.OPEN)`이며 outbound reference 0인 값 타입만 담는다 (가드: `HexagonalArchitectureTest.kernel_must_not_depend_on_other_modules`). user/account 모듈은 소비만 하고 소유하지 않는다. 자체 검증 로직을 가진 record도 outbound-zero면 포함한다.

- **어휘와 wire의 경계**: 어휘(enum·정책 값)는 kernel, 프로세스 경계 wire 스키마(필드명이 곧 계약)는 같은 `:shared`의 `shared.contract` → `contract.md`.
- 공유 enum은 nested enum으로 재도입하지 않는다(`constraints.md` "User/Account/Strategy 공유 enum"). 브로커 wire 매핑은 어댑터가 담당하며 enum 값 집합엔 외부 계약이 없다. `Currency`는 금액 반올림·표기 SSOT이고 `Broker.getCurrency()`가 계좌 통화를 정한다.
- 전략 capability SSOT = `StrategyCapability` + `StrategyType.capability()`(INFINITE: 전체 티커·리버스모드·분할 20/30/40 / PRIVACY: SOXL 고정·기준표 필요 / VR: TQQQ 고정). matching `CycleOrderStrategy`와 root `MetaController`가 프로세스 간 HTTP 없이 같은 상수를 공유한다. `availableTickers`는 EnumSet 순서를 유지한다(UI 티커 정렬). `StrategyDefaults.DEFAULT_DIVISION_COUNT=20`은 분할 수 설정이 없는 PRIVACY/VR의 고정값이다.
- 유틸: `Quantities`(수량 normalize — 매칭 커널이 쓰므로 trading 도메인에서 이곳으로 옮겼다), `TimeZones`(KST 단일 소스), `AccountNumberMasker`. `UsTradeDates`는 어댑터 전용이라 `platform.time` 소속이다.
- 값 타입은 kernel enum+JDK 타입만 참조한다: `ReturnMetrics`(root `BenchmarkComparisonBuilder`·trading-core `BacktestEngine` 공용), `TradeLegSummary`(`broker.Execution`의 notify 전용 narrowing). `TradingReport`/`DailyCandle`은 trading-core 전용이다.
- 이벤트: `UserDeletedEvent`·`UserNotifyProfileChangedEvent`·`UserPushNotificationRequestedEvent`(trading-core→root FCM, Redis Stream `stream:user.push-notification.requested`), `AppErrorRaisedEvent`(root·trading-core 발행분 모두 admin `AppErrorRaisedListener`가 로컬 이벤트로 저장). 소비자가 한 모듈 안뿐인 매매·privacy 알림 이벤트는 각 모듈 `application.event`가 소유한다. 이벤트 FQCN을 옮기면 `event_publication`에 `ClassNotFoundException`이 남는다 → `docker-infra.md` "배포 직전 EPR 정리 런북".
- `TradingPolicySettings(brokers, strategies)`/`BrokerSettings`는 trading-core가 소유·집행하고 root admin이 내부 API(PUT `/api/internal/trading/policy-settings`) body로 편집하는 매매 런타임 정책이라 프로세스 간 계약으로 kernel에 둔다. `SERVER_EGRESS_IP`는 증권사 API 키 허용 IP 안내값이다.
- 포트는 그것을 필요로 하는 모듈이 소유한다(`BrokerEnabledPort`는 account, `StrategyCreationPolicyPort`는 trading).
