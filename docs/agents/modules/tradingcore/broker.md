## com.kista.tradingcore.broker (`:trading-core`)

Spring Modulith 모듈(CLOSED) — Meritz/Toss/KIS/Mock 증권사 + Upbit/Binance 코인 거래소 연동. "domain"·"port"·"application" 3개 NamedInterface 공개, adapter/out은 비공개. 벤더별 사양·실측은 `docs/agents/vendors/<벤더>.md`(해당 어댑터 디렉토리 작업 시 자동 로드).

### 경계 규칙
- 벤더 전용 모델(`domain.model.kis/toss/meritz`)은 broker 밖에서 쓰지 않는다 (가드: `HexagonalArchitectureTest.vendor_models_must_not_leak_outside_broker`). 새 소비자가 벤더 값이 필요하면 `domain/model`에 벤더 중립 타입을 신설한다.
- broker는 `Account`를 참조하지 않는다 — 포트는 `BrokerAccountRef`를 받고 변환은 `Account.toBrokerRef()` 한 곳이 맡는다.
- 소비자는 `BrokerApiException`(벤더 중립 상위 타입) 하나만 catch하고 `BrokerProblemDetails`가 503으로 매핑한다. `isRejected()`는 "증권사가 접수하지 않았음이 확실"일 때만 true — KIS는 4xx·`rt_cd≠0`·5xx 중 EGW00201만, I/O·그 외 5xx·2xx 본문 없음·Toss 409 `request-in-progress`는 결과 불명이라 재전송하면 이중 주문이 될 수 있다. 판정 SSOT는 `BrokerApiException.isRejection(Throwable)`.
- 취소 결과 `CancelOutcome`: 이미 종결된 주문(ALREADY_FILLED/ALREADY_CANCELED)은 예외가 아니라 반환값이다. Toss는 409 + error.code, Meritz/KIS는 취소 전 원주문 잔량 조회로 판정한다. 소비자는 switch 식으로 전 상수를 다룬다.
- 브로커 호출 실패 정책(`wrap`/`tryGet`)은 `constraints.md` "파라미터 개수·브로커 호출 실패 정책".

### BrokerRouter
- 공통 6개 Port(Portfolio/Margin/SellableQuantity/BrokerOrderCorrection/Execution/BrokerPrice)를 `account.broker()`로 위임한다. `BrokerCapabilitiesPort`/`BrokerAdapterPort`는 구현하지 않는다(구현하면 자기 자신이 `List<BrokerCapabilitiesPort>`에 수집된다).
- 소비처는 Port 인터페이스를 그대로 주입한다 — `@Primary`가 Router를 고른다. 신규 브로커는 `BrokerCapabilitiesPort` 구현체 1개만 추가하면 되고 Router·switch는 고치지 않는다. 미등록 브로커는 400.
- 현재가·전일종가·확정종가는 `AdminMarketPricePort`(계좌 없는 관리자 시세: Toss → KIS 순, 단계별 누락분만 다음 단계) 우선, 누락분만 계좌 어댑터가 조회한다. 스냅샷은 계좌 어댑터 전용. 다건 조회에서 계좌 보충이 실패하면 관리자 결과가 있을 때 warn 후 부분 결과를 반환하고 없으면 예외를 전파한다. `BrokerCredentialException`은 항상 전파, `BrokerRateLimitException`은 관리자 결과가 있으면 부분 결과 우선, 단건은 항상 전파한다.
- 공용 시세 `AlpacaMarketDataAdapter`(`adapter/out/marketdata`, 타입 단위 NamedInterface `marketdata`, 인터페이스 없음): 현재가(iex·지연 통합 중 더 늦은 체결)·전일 종가·확정 일봉·일봉 이력. 날짜 인자는 미국 세션일. 아직 운영 경로는 쓰지 않고 `PriceSourceComparison`(trading)이 `[시세 비교]` 로그로 기존 값과 대조한다(fable 14 T2, T3 전환 때 삭제). 예외: 리버스모드 별지점은 일봉 종가를 쓴다(실패 시 원장 행 평균, `trading-formulas.md` 1-7). `BrokerPricePort.currentPrices`는 관리자 시세를 거치지 않는 계좌 증권사 현재가다.
- 통계 capability(Candle/ExchangeRate)는 `BrokerStatisticsPort`로 묶여 Toss만 구현한다. 미지원 증권사는 `BrokerStatisticsPorts.find`가 빈 Optional을 주고 400 사유는 소비자가 정한다.
- 코인은 `CryptoExchangePort`로 분리(`BrokerCapabilitiesPort`와 별개). 마지막 일봉 일자 계약은 `CryptoCandle.latestCompletedKstDate`.

### 어댑터 메모
- `MockSimulationDataPort`는 데이터를 필요로 하는 broker가 정의하고 가진 trading이 구현하는 포트 역전이다(broker는 trading 타입을 참조할 수 없다).
- Mock 체결 판정은 trading `FillSimulator`를 재구현한 것이다(broker→trading 참조 불가). 시세는 Toss 관리자 시세(`TossPriceApi`)를 직접 재사용한다.
- 토큰 제거(`AccountTokenEvictionPort`)는 브로커 무관 호출이다. KIS DB 삭제는 호출자 트랜잭션에 합류하고 Toss Redis `invalidate`는 afterCommit, 실패는 warn 후 진행한다(구현이 toss 패키지인 이유: `TossTokenStore` package-private).
- `adapter/out/internal`: 더블체크락(`DbTokenCoordinator`)·전일종가 캐시·확정종가 폴백·`getClosingPrices` 순회는 공용 유틸 없이 각 소비자에 인라인돼 있다. 공용 유틸 추출은 호출부가 2곳 이상일 때만 한다.
- `adapter/in/web/CandleInternalController`(`/api/internal/broker/candles/latest`, X-Internal-Token): root market이 `CandlePort`/`BrokerCandle`을 참조하지 않도록 `contract.broker.DailyCandleResponse`로 매핑해 노출한다.
- 업비트 시세(김프용 KRW-USDT·코인 일봉)는 stats `UpbitMarketDataAdapter`(무인증 공개 시세)가 맡아 업비트 거래를 제거해도 남는다.
- DCA 규칙: 일봉 페이징·Toss 캡가/0.01 → `vendors/toss.md`, MOO 매핑(KIS 매도 `31`/매수 `32`, 미국 매수는 MOO/MOC 불가) → `vendors/kis.md`, 업비트 → `vendors/upbit.md`·dca.md "코인 DCA 실행". `Broker.supportsOpenAuctionOrders()`가 개장 전 접수 대상 브로커를 가른다. KIS 계좌번호 저장 규칙 → `vendors/kis.md` "KIS 계좌번호 DB 저장 방식".
- 브로커별 분기가 필요하면 `account.broker() == Broker.TOSS`처럼 직접 비교한다
