# own-type 허용 원장

신규 own-type 복제·게이트 판정 전 필수 Read (이 원장은 모듈 간 값 타입 대상). 판정 기준((a)(b) 게이트, 증거 의무, 포트 역전 구분)은 `constraints.md` "모듈 경계 own-type — 정당화 게이트"에 있다 — 이 파일은 그 기준을 통과해 실제로 허용된 사례 원장이다.

**프로세스 경계를 넘는 wire 타입(내부 API 요청·응답·Redis payload)은 own-type으로 복제하지 않고 `com.kista.shared.contract`에 둔다**(→ `docs/agents/modules/shared/contract.md`, 강제는 `InternalApiContractTest`). 공유 어휘 enum(`MarketSession` 등)은 sharedkernel.

**남아 있는 (a) 성격 own-type — 표지 예외**: `AdminBrokerCredentialException`/`AdminBrokerRateLimitException`/`AdminPrivacyTradeConflictException`(root `com.kista.api.admin.domain.model`) — trading-core 내부 API의 HTTP 상태코드(422/429/409)를 admin `GlobalExceptionHandler`가 매핑할 수 있는 예외로 되돌리는 표지 클래스. 값 타입이 아니라 예외라 contract 대상이 아니다.

**(b) 외부 계약 분리로 의도적 허용된 복제** (원장에 남는 것):
- DTO 이중복제: `CycleHistoryPageResponse`/`CycleHistoryResponse`(tradingcore.stats/trading) — 각기 다른 HTTP 엔드포인트의 JSON 응답 계약. 통합 시 한 모듈의 엔드포인트 필드 추가가 다른 모듈 응답 스키마에 전이됨

**단일 소유 포트 시그니처 타입** (쌍둥이 없음 — own-type 게이트 대상 아님, 참고용 기록):
- broker `OrderInstruction`/`OrderResult`/`CancelInstruction`(`com.kista.tradingcore.broker.domain.model`): broker↔trading 간 `BrokerOrderCorrectionPort.place()/cancel()` 요청·응답 shape. 복제본 없음 — trading이 직접 소비. `OrderInstruction`/`OrderResult`/`BrokerOrderCorrectionPort.place()` 직접 호출은 trading-core `ReorderService`가 전담한다 — root `AdminReorderService`는 이 타입들을 참조하지 않고 `TradingCommandPort`(HTTP 내부 API) 경유로 `contract.trading.ReorderRequest`/`ReorderResponse`만 주고받는 감사 로그 프록시다(trading 내부 `ReorderCommand`/`ReorderResult`는 `com.kista.tradingcore.trading.domain.model` 소유로 root에 복제본 없음). broker↔trading 순환은 이 타입들이 아니라 `BrokerAccountRef`(아래)로 끊는다
- broker `PriceSnapshot`: `BrokerPricePort` 반환 타입, broker 단독 소유(matching 사본 없음 — 커널 코드는 `BigDecimal` 스칼라만 받아 자체 타입 불필요, trading이 broker판을 직접 소비)

**narrowing projection** (own-type이되 값 복제가 아니라 애그리게이트 축소 노출 — 별도 트랙):
- `BrokerAccountRef`(broker, `Account`의 자격증명 투영): `Account→BrokerAccountRef` 변환은 `Account.toBrokerRef()` 1곳이 전담. `SellableQuantity`/`BrokerCredentialException`/`BrokerRateLimitException`은 broker 단독 소유 — account 측엔 대응 타입 없음. "복제"가 아니라 broker-native
- `StrategyRefLite`(broker, `MockSimulationDataPort` 확장용 초경량 뷰)
- `StrategyCreationRequest`(trading.domain.creation, 원시값 5개): 리졸버 4종이 19필드 `RegisterStrategyCommand` 전체 대신 실제로 쓰는 필드만 받는 ISP 좁히기 — 모듈 경계용이 아니라 순수 인터페이스 설계
- `FidaPlannedOrder`(privacy.domain.model, 원시값 4필드): `trading.domain.model.Order`(15필드) 대신 FIDA가 실제로 보내고 privacy가 실제로 읽는 필드만 받는 ISP 좁히기 — 필드 타입은 `sharedkernel.OrderDirection`/`OrderType`
- `StrategyRef`(`com.kista.api.benchmark.domain.model`, `Strategy`의 id/type/ticker 3필드만 담는 narrowing): `InvestmentPointsPort.Result.selectedStrategy`와 `BenchmarkComparison.strategy`가 공유
- `NotificationRecipient`(notify, `com.kista.api.notify.domain.model` — User 애그리게이트의 알림 발송용 6필드 투영: userId/nickname/notificationChannel/telegramBotToken/telegramChatId/rejectReason): 변환은 gateway 어댑터 경계의 `NotificationRecipients.from(User)`가 맡는다(`modules/api/notify.md`). notify가 User를 모르게 하는 목적 — trading-core `TradingUserProfile`(`trading.domain.model`)과 같은 접근
- `TradingAccount`(trading, `com.kista.tradingcore.trading.domain.model` — `Account`의 id/userId/nickname/brokerRef 4필드 투영): 변환 `TradingAccount.from(Account)`는 정의 1곳, 호출 경계 3곳(`BatchContextFactory`/`ManualTradingService`/`TradingPreviewService`). `from(Account)`를 도메인 record에 두는 이유: trading 도메인→account 도메인 의존은 허용 범위(리뷰 F8)이고, 3곳에 변환을 복제하는 것보다 1곳 정의가 낫다. `BatchContext`·배치/프리뷰/리포트 경로가 `Account` 애그리게이트 대신 이 투영만 받는다 — 타입 표면 축소이지 자격증명 은닉은 아니다(`brokerRef`(`BrokerAccountRef`)가 자격증명을 그대로 담고, `BrokerAccountRef.toString()`만 마스킹해 로그·예외 메시지 평문 노출을 막는다). 소유권 검증(`verifyOwnedBy`)이 필요한 요청 경로만 `Account`를 유지하고 배치 경로 진입 시 변환. `HexagonalArchitectureTest.trading_batch_path_must_not_carry_account_aggregate`가 잠금(`application.service` 전체 + `BatchContext` 대상 denylist 방식, Account를 정당하게 들고 있는 요청 경로 클래스만 제외)

## 복제본·제3자 구현 설계 규칙 (constraints.md 게이트에서 이관)

**제3자(정의자도 데이터 소유자도 아닌 모듈, 예: web) 구현을 새 설계의 선례로 삼지 말 것** — root(`:api`)는 `:trading-core`를 컴파일 의존하지 않으므로 trading-core 정의 인터페이스를 root가 구현할 수 없다(스타일 문제가 아니라 컴파일 불가). trading-core는 자기 스키마(`trading.user_notify_profile`)에 사용자 알림·활성여부 복제본을 두고 자기 포트를 자기가 구현하며(`UserNotifyProfilePersistenceAdapter`), user는 `UserNotifyProfileChangedEvent`(sharedkernel)/`UserDeletedEvent`만 발행한다. 모듈 경계를 넘는 데이터 의존은 (1) 소유자가 데이터를 밀어주는 이벤트 + 소비자 소유 복제본, 또는 (2) 내부 HTTP API(`TradingCommandPort`/`TradingQueryPort` 계열) 중에서 고른다(후자의 예: `TradingCommandHttpAdapter` — trading-core 내부 HTTP 호출).

**복제본을 쓸 때의 필수 조건 2가지**(user_notify_profile 사례에서 실측):
- **(a) 신규 복제 테이블은 마이그레이션에서 기존 데이터를 반드시 백필한다.** 복제본이 비면 소비처가 빈 결과를 정상 응답으로 받아 기능이 멈춘다. `TradingUserProfilePort` 3개 메서드의 실제 실패 양상이 서로 다르다: `findAllByUserIds()` 빈 맵 → `BatchContextFactory`가 전략마다 `NoSuchElementException`을 던지고 잡아 `errorReportPort.reportError()`로 관리자 알림을 내보낸다(**시끄럽게** 전면 중단 — 전략 수만큼 알림이 쏟아진다). `findByUserId()` 빈 Optional → 전략 등록이 "사용자를 찾을 수 없습니다"로 거부된다. `findAllActive()` 빈 리스트 → `MarketEventNotifier`가 **조용히** 아무에게도 안 보낸다(예외·로그 없음). 셋 중 마지막이 발견이 가장 늦다.
- **(b) 원본의 모든 쓰기 지점을 전수 확인하고 발행을 건다.** 상태값은 `withStatus`/`withRejection` 같은 도메인 메서드명으로, 설정값은 필드명으로 grep한다. 포트 메서드명만 보고 판단하지 말 것 — `findAllActive()`가 실제로 `UserStatus.ACTIVE` 필터라는 사실은 소비처(`MarketEventNotifier`)와 구 어댑터를 읽어야만 드러났고, 이걸 놓쳤다면 복제본에 `is_active` 컬럼이 빠져 개장·마감 알림이 비활성 사용자에게까지 나갔을 것이다.

## 포트 역전(DIP) 사례

포트 역전은 "포트를 필요로 하는 쪽이 정의하고 데이터를 가진 쪽이 구현"하는 정상 설계라 값 타입 복제가 아니다. own-type 번호를 부여하지 않는다.
- `ApprovalPolicyPort`: user 정의, admin 구현
- `BrokerEnabledPort`: account 정의, trading `TradingPolicyService` 구현
- `AccountOpenOrderCancelPort`: account 정의, trading `AccountOpenOrderCanceller` 구현
- `MockSimulationDataPort`: broker 정의, trading 구현
- `HistoricalCandlePort`·`TradingUserProfilePort`는 구현과 소비가 모두 trading-core라 포트 역전이 아닌 일반 출력 포트다. `HistoricalCandlePort`는 `com.kista.tradingcore.stats.application.port.output` 소유(구현 `AlpacaCandleAdapter`, 소비 `BacktestService`)다.
