# own-type 허용 원장

신규 own-type 복제·게이트 판정 전 필수 Read (프로세스 경계 wire 타입은 own-type이 아니라 `com.kista.contract`에 둔다 — 이 원장은 그 밖의 모듈 간 값 타입 대상). 판정 기준((a)(b) 게이트, 증거 의무, 포트 역전 구분, 폐기된 전례, 복제본 필수 조건)은 `constraints.md` "모듈 경계 own-type — 정당화 게이트"에 있다 — 이 파일은 그 기준을 통과해 실제로 허용된 사례 원장이다.

**(a) 순환 불가피 목록은 소멸했다 (2026-09-30, `com.kista.contract` 도입)** — 과거 이 자리에는 root(`:api`)와 `:trading-core`가 서로의 클래스를 import할 수 없다는 Gradle 컴파일 경계 때문에 손으로 복제한 own-type 20쌍(`AdminOrderView`/`AdminStrategyView`/`AdminAccountView`/`AdminPrivacyTradeBaseView`/`Admin*Command`·`Admin*Result`/`InvestmentPoint`/`MarketSession`/`TossDailyCandle`/`TradeEventView` 등)이 있었다. 양쪽이 의존하는 `:shared`가 있는 이상 "순환 불가피"는 성립하지 않아, 내부 API 요청·응답·Redis payload 타입은 전부 `com.kista.contract`(→ `docs/agents/modules/contract.md`)로 옮겨 컴파일 타임에 공유한다. 공유 어휘 enum(`MarketSession`/`BenchmarkGranularity`)은 sharedkernel로 승격됐다. 손동기화 검증 `OwnTypeContractTest`는 삭제됐고 `InternalApiContractTest`가 대신한다. 상세 경위 → `modulith-migration-history.md` "Published Language 도입". **프로세스 경계를 넘는 새 wire 타입을 own-type으로 복제하지 말 것.**

**남아 있는 (a) 성격 own-type — 표지 예외**: `AdminBrokerCredentialException`/`AdminBrokerRateLimitException`/`AdminPrivacyTradeConflictException`(root `com.kista.admin.domain.model`) — trading-core 내부 API의 HTTP 상태코드(422/429/409)를 admin `GlobalExceptionHandler`가 매핑할 수 있는 예외로 되돌리는 표지 클래스. 값 타입이 아니라 예외라 contract 대상이 아니다.

**(b) 외부 계약 분리로 의도적 허용된 복제** (원장에 남는 것):
- DTO 이중복제: `TossCandleResponse`(market/tradingstats — root UI 응답과 trading-core UI 응답, 내부 wire가 아닌 각자의 외부 HTTP 응답 스키마), `CycleHistoryPageResponse`/`CycleHistoryResponse`(tradingstats/trading) — 각기 다른 HTTP 엔드포인트의 JSON 응답 계약. 통합 시 한 모듈의 엔드포인트 필드 추가가 다른 모듈 응답 스키마에 전이됨

**단일 소유 포트 시그니처 타입** (쌍둥이 없음 — own-type 게이트 대상 아님, 참고용 기록):
- broker `BrokerBalance`/`OrderInstruction`/`OrderResult`/`CancelInstruction`(`com.kista.broker.domain.model`): broker↔trading 간 `LiveBalancePort`/`BrokerOrderCorrectionPort.place()/cancel()` 요청·응답 shape. 복제본 없음 — trading이 직접 소비. `OrderInstruction`/`OrderResult`/`BrokerOrderCorrectionPort.place()` 직접 호출은 Task 7에서 trading-core `ReorderService`로 전부 이관됐다 — root `AdminReorderService`는 더 이상 이 타입들을 참조하지 않고, `TradingCommandPort`(HTTP 내부 API) 경유로 `ReorderCommand`/`ReorderResult`(trading own-type, 위 항목 참고)만 주고받는 얇은 요청/응답 매핑 + 감사 로그 프록시다. broker↔trading 순환은 이 타입들이 아니라 `BrokerAccountRef`(아래)로 끊는다
- broker `PriceSnapshot`: `BrokerPricePort` 반환 타입, broker 단독 소유(matching 사본 없음 — 커널 코드는 `BigDecimal` 스칼라만 받아 자체 타입 불필요, trading이 broker판을 직접 소비)

**narrowing projection** (own-type이되 값 복제가 아니라 애그리게이트 축소 노출 — 별도 트랙):
- `BrokerAccountRef`(broker, `Account`의 자격증명 투영): `Account→BrokerAccountRef` 변환은 `Account.toBrokerRef()` 1곳이 전담. `SellableQuantity`/`BrokerCredentialException`/`BrokerRateLimitException`은 broker 단독 소유 — account 측엔 대응 타입 없음. "복제"가 아니라 broker-native
- `StrategyRefLite`(broker, `MockSimulationDataPort` 확장용 초경량 뷰)
- `StrategyCreationRequest`(trading.domain.strategy, 원시값 5개): 리졸버 4종이 19필드 `RegisterStrategyCommand` 전체 대신 실제로 쓰는 필드만 받는 ISP 좁히기 — 모듈 경계용이 아니라 순수 인터페이스 설계
- `FidaPlannedOrder`(privacy.domain.model, 원시값 4필드): `trading.domain.model.Order`(15필드) 대신 FIDA가 실제로 보내고 privacy가 실제로 읽는 필드만 받는 ISP 좁히기 — 필드 타입은 `sharedkernel.OrderDirection`/`OrderType`
- `StrategyRef`(`com.kista.benchmark.domain.model` — 구 `stats`, `Strategy`의 id/type/ticker 3필드만 담는 narrowing): `InvestmentPointsPort.Result.selectedStrategy`와 `HousingBenchmarkComparison.strategy`가 공유. modulith-migration-history.md에 "소멸했다"고 기록된 과거의 동명 trading own-type `StrategyRef`(strategyconfig 병합으로 제거)와는 이름만 같은 별개 타입 — 혼동 금지
- NotificationRecipient(notify, com.kista.notify.domain.model — User 애그리게이트의 알림 발송용 6필드 투영: userId/nickname/notificationChannel/telegramBotToken/telegramChatId/rejectReason): 변환은 호출자(user 이벤트 리스너 CompositeUserNotificationAdapter, finance 리마인더)가 담당. notify가 User를 모르게 하는 목적 — trading-core TradingUserProfile과 같은 접근(2026-09-30, 5단계)
