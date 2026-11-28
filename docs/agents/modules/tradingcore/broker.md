## com.kista.tradingcore.broker (`:trading-core`)

Spring Modulith 모듈(CLOSED) — Meritz/Toss/Mock 증권사 연동. "domain"·"port"·"application" 3개 NamedInterface 공개, adapter/out은 비공개. 벤더별 사양·실측은 `docs/agents/vendors/<벤더>.md`(해당 어댑터 디렉토리 작업 시 자동 로드).

### 경계 규칙
- 벤더 전용 모델(`domain.model.toss/meritz`)은 broker 밖에서 쓰지 않는다 (가드: `HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker`). 새 소비자가 벤더 값이 필요하면 `domain/model`에 벤더 중립 타입을 신설한다.
- broker는 `Account`를 참조하지 않는다 — 포트는 `BrokerAccountRef`를 받고 변환은 `Account.toBrokerRef()` 한 곳이 맡는다.
- 소비자는 `BrokerApiException`(벤더 중립 상위 타입) 하나만 catch하고 `BrokerProblemDetails`가 503으로 매핑한다. `BrokerApiException.isRejection(e)`는 "증권사가 접수하지 않았음이 확실"일 때만 true — I/O·타임아웃·Toss 409 `request-in-progress`는 결과 불명이라 재전송하면 이중 주문이 될 수 있다. 판정 SSOT는 `BrokerApiException.isRejection(Throwable)`.
- 취소 결과 `CancelOutcome`: 이미 종결된 주문(ALREADY_FILLED/ALREADY_CANCELED)은 예외가 아니라 반환값이다. Toss는 409 + error.code, Meritz는 취소 전 원주문 잔량 조회로 판정한다. 소비자는 switch 식으로 전 상수를 다룬다.
- 브로커 호출 실패 정책(`wrap`/`tryGet`)은 `constraints.md` "파라미터 개수·브로커 호출 실패 정책".

### BrokerRouter
- 공통 6개 Port(Portfolio/Margin/SellableQuantity/BrokerOrderCorrection/Execution/BrokerPrice)를 `account.broker()`로 위임한다. `BrokerCapabilitiesPort`/`BrokerAdapterPort`는 구현하지 않는다(구현하면 자기 자신이 `List<BrokerCapabilitiesPort>`에 수집된다).
- 소비처는 Port 인터페이스를 그대로 주입한다 — `@Primary`가 Router를 고른다. 신규 브로커는 `BrokerCapabilitiesPort` 구현체 1개만 추가하면 되고 Router·switch는 고치지 않는다. 미등록 브로커는 400.
- 주문 경로의 현재가는 `BrokerPricePort.currentPrices(account, tickers)` 하나다. `BrokerRouter`가 그 계좌 증권사로만 보낸다(Mock 계좌만 공용 시세 `latestPrices` — Mock은 증권사가 없다. Toss는 계좌 토큰 `/api/v1/prices`, `vendors/toss.md`). 토스 관리자 키는 환율에만 쓴다(T4 Frankfurter 전환 뒤 T5b에서 제거). `BrokerCredentialException`은 항상 전파한다.
- 공용 시세 `MarketDataClient`(`adapter/out/marketdata`, 타입 단위 NamedInterface `marketdata`, 인터페이스 없음, 공급자 Alpaca): `latestPrices`(iex·지연 통합 중 더 늦은 체결)·`latestCloses`/`requireLatestClose`(전일 종가)·`confirmedBars`/`requireConfirmedClose`(확정 일봉)·`closesBefore`·`dailyCandles`(`DailyCandleQuery`, 조정 RAW·SPLIT·ALL). 날짜 인자는 미국 세션일(KST 거래일 변환은 호출부의 `MarketCalendarPort.usSessionDateOf`). `closesBefore`는 뉴욕 16:16 전에는 그 뉴욕 당일 봉을 고르지 않고(미정산 봉 제외) 결과를 정산 시각까지만 캐시한다. 전송 오류는 `MarketDataApiException`(`BrokerApiException` 하위, 라벨 "시세")이라 503 `BROKER_UNAVAILABLE`로 나간다. 소비처: 전일 종가·확정 종가·등록·미리보기·롤오버·DCA 지표(`adjustment=all`)·차트(RAW, 개수 1~600)·백테스트(SPLIT)·통계 `GET /prices`·원장 합산. 예외: 리버스모드 별지점은 일봉 종가를 쓴다(실패 시 원장 행 평균, `trading-formulas.md` 1-7).
- 통계 capability는 `BrokerStatisticsPort`(`ExchangeRatePort`뿐)이며 Toss만 구현한다. 미지원 증권사는 `BrokerStatisticsPorts.find`가 빈 Optional을 주고 400 사유는 소비자가 정한다.

### 어댑터 메모
- `MockSimulationDataPort`는 데이터를 필요로 하는 broker가 정의하고 가진 trading이 구현하는 포트 역전이다(broker는 trading 타입을 참조할 수 없다).
- Mock 체결 판정은 trading `FillSimulator`를 재구현한 것이다(broker→trading 참조 불가). 시세는 공용 시세(`MarketDataClient`)와 `usSessionDateOf`를 쓰며, 확정 일봉이 없으면 `getExecutions`가 예외를 던진다(현재가로 대체하지 않는다).
- 토큰 제거(`AccountTokenEvictionPort`)는 브로커 무관 호출이다. `broker_tokens`(Meritz) DB 삭제는 호출자 트랜잭션에 합류하고 Toss Redis `invalidate`는 afterCommit, 실패는 warn 후 진행한다(구현이 toss 패키지인 이유: `TossTokenStore` package-private).
- `adapter/out/internal`: 더블체크락(`DbTokenCoordinator`)은 공용 유틸 없이 각 소비자에 인라인돼 있다. 공용 유틸 추출은 호출부가 2곳 이상일 때만 한다.
- `adapter/in/web/CandleInternalController`(`/api/internal/broker/candles/latest`, X-Internal-Token): `MarketDataClient` 일봉을 `contract.broker.DailyCandleResponse`로 매핑해 노출한다.
- DCA 규칙: 일봉 페이징·Toss 캡가/0.01 → `vendors/toss.md`.
- 브로커별 분기가 필요하면 `account.broker() == Broker.TOSS`처럼 직접 비교한다
