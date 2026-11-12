# 12. 바이낸스 공식 API 대조 — KISTA 어댑터가 필요한 API만 쓰는가

조사일 2026-11-12, 읽기 전용(실계좌 호출 없음). 공식 문서는 developers.binance.com Spot REST(limits, request-security, trading/account/market-data/general endpoints, filters, errors, enums, CHANGELOG)와 Wallet `apiRestrictions`를 WebFetch로 읽었다. 코드 경로 약칭: `A`=`trading-core/.../broker/adapter/out/binance/BinanceExchangeAdapter.java`, `H`=같은 디렉토리 `BinanceHttpClient.java`, `R`=`BinanceSymbolRegistry.java`, `X`=`CryptoDcaRoundExecutor.java`, `S`=`CryptoOrderSynchronizer.java`(둘 다 `trading/application/service/`).

## 1. KISTA 필요 기능 (`CryptoExchangePort` 전수)

| # | 메서드(시그니처 요약) | 호출처 file:line | 용도 | 상태 |
|---|---|---|---|---|
| 1 | `supports()` | `CryptoExchanges.java:22` | 라우터 Map 키 | 구현 1개가 되면 라우터 자체가 불필요(5절) |
| 2 | `getBalance(ref)` → `CryptoBalance` | `UnassignedCashCalculator.java:64`(quoteAvailable만), `CryptoPortfolioService.java:44`(전체) | 미배정 예수금 계산, 포트폴리오 화면 | 사용 |
| 3 | `getTradingRules(ticker)` → 수수료율·차감쪽·minNotional·step | `X:102`(틱·계좌 메모), `A:137`(매도 수량 내림용 재호출) | 최소 주문 금액·수량 단위 | 사용 |
| 4 | `getCurrentPrices(tickers)` | `X:89`, `AccountLedgerTotalsService.java:55`, `CryptoPortfolioService.java:48,71` | 실행 기준가, 평가액, 시세 API | 사용 |
| 5 | `getCompletedDailyCandles(ticker, count)` | `X:165`(CANDLE_COUNT=250, `X:51`) | DCA 조건(이평·RSI 등) 종가 | 사용 |
| 6 | `buyMarketByAmount(ref, ticker, quoteAmount, clientOrderId)` | `X:189` | 금액 지정 시장가 매수 | 사용. 반환값은 `X:192-193`에서 `exchangeOrderId()`만 쓴다 |
| 7 | `sellMarketByVolume(ref, ticker, volume, clientOrderId)` | `X:190` | 수량 지정 시장가 매도 | 사용 (반환값 동일) |
| 8 | `findOrderByClientOrderId(ref, ticker, clientOrderId)` | `S:73` | SUBMITTING 복구(접수 불명 → 조회) | 사용 |
| 9 | `findOrderByExchangeOrderId(ref, ticker, exchangeOrderId)` | `S:87` | PLACED 체결 동기화 | 사용 |

미사용 메서드는 없다. 다만 소비처가 쓰지 않는 반환 필드가 있다: 6·7의 `CryptoOrderResult` 중 `exchangeOrderId` 외 전부, `CryptoBalance.Holding.avgBuyPrice`(바이낸스는 항상 null, `A:67`), `CryptoOrderResult.State.WATCH`(바이낸스 미생성), `CryptoTradingRules.feeRate/feeSide`(바이낸스는 `RECEIVED_ASSET`라 `quoteSpendFor`·`affordableAmount`가 항등, `shared/.../CryptoTradingRules.java:31-39`).

## 2. 대조표 (기능 × 현재 호출 × 공식 문서)

weight는 공식 문서(2026-11 조회) 값. 공식 Spot 한도: REQUEST_WEIGHT 6000/분(IP), ORDERS 10/초(enums 페이지), 미체결 주문 수는 계좌 단위.

| 기능 | 현재 호출 (코드) | 공식 문서 | 일치 | 더 적합한 API / 어긋남 |
|---|---|---|---|---|
| 잔고 | `GET /api/v3/account?omitZeroBalances=true` (`A:54-56`) | weight 20, USER_DATA, `balances[].asset/free/locked`, 응답에 `canTrade`·`commissionRates`·`permissions` 포함 | 일치 | Spot에 잔고만 주는 더 좁은 엔드포인트는 없음(Wallet `getUserAsset`은 더 무거움). 현재가 최선. 응답의 `canTrade`는 미사용 |
| 현재가 | 티커마다 `GET /api/v3/ticker/price?symbol=` (`A:75-80`) | symbol 1개 weight 2, `symbols` 배열은 개수 무관 4 | 일치 | 티커 2개(BTC·ETH)라 호출 수 이득 없음(2+2=4). 3개 이상이면 `symbols=[...]` 1회가 유리. 현 규모에선 변경 불필요 |
| 일봉 | `GET /api/v3/klines?symbol&interval=1d&limit=count+1` (`A:87-91`) | weight 2, limit 기본 500·최대 1000, 12필드 배열, `timeZone` 파라미터(기본 UTC) | 일치 | 진행 봉 1개 버리는 `limit+1` 방식은 문서와 부합. `timeZone=9`로 KST 00:00 봉을 받을 수 있으나 현재 정의(UTC 00:00 = KST 09:00 마감)와 지표값이 달라져 변경 금지 |
| 거래 규칙 | `GET /api/v3/exchangeInfo?symbol=` (`A:108-110`) | weight 20(symbol 지정해도 20), `symbols[].status/filters`, 필터 LOT_SIZE·MARKET_LOT_SIZE·NOTIONAL(`applyMinToMarket`)·MIN_NOTIONAL | 부분 | (a) MARKET 주문엔 `MARKET_LOT_SIZE`가 적용되는데 `LOT_SIZE`만 읽는다 (b) `minQty`·`maxQty` 미검증 (c) `NOTIONAL.applyMinToMarket=false`인 심볼이면 minNotional을 시장가에 강제할 필요 없는데 플래그를 무시한다. BTC/ETH USDT는 현재 문제 없음(문서 실측 `docs/agents/binance-api.md` 기준 min 5 USDT) |
| 시장가 매수 | `POST /api/v3/order` type=MARKET, `quoteOrderQty`, `newClientOrderId`, `newOrderRespType=FULL` (`A:127-131,144-152`) | MARKET은 `quantity` 또는 `quoteOrderQty`, quoteOrderQty는 "쓸 금액", LOT_SIZE를 깨지 않는 선에서 최대한 가깝게 체결, weight 1(성공 시 0 — 2026-04-02) | 일치 | DCA 금액 지정 매수에 가장 적합한 API. 어긋남 없음. `FULL` 응답의 `fills`는 `X`가 쓰지 않는다(4절) |
| 시장가 매도 | 동일 POST, `quantity`를 LOT_SIZE step으로 DOWN (`A:135-141`) | `quantity` 필수, step 배수·minQty 이상 | 일치 | `A:137`이 매도마다 `getTradingRules`(exchangeInfo weight 20)를 재호출한다. `X:102`가 이미 같은 틱에 규칙을 메모했는데 다시 받는다 |
| 주문 조회(clientOrderId) | `GET /api/v3/order?symbol&origClientOrderId` (`A:178-189,193`) | weight 4, `orderId` 또는 `origClientOrderId`, 둘 다 주면 orderId 우선 후 일치 검증, `cummulativeQuoteQty`가 음수면 "데이터 없음" | 일치 | 어긋남: `cummulativeQuoteQty` 음수(오래된 주문) 가드 없음 → `A:219-220`이 음수 평균가를 만든다. 실행 직후 조회라 현실 위험은 낮음 |
| 주문 조회(orderId) | `GET /api/v3/order?symbol&orderId` (`A:161-175`) | 위와 동일(orderId 단독이 더 빠름) | 일치 | 바이낸스는 clientOrderId가 곧 주문 UUID라 영구(90일) 조회 가능 → 두 조회 메서드 중 하나로 합칠 수 있다(5절) |
| 체결·수수료 | `GET /api/v3/myTrades?symbol&orderId` (`A:198-202`) | weight 5(orderId 지정 시, 2025-04-07 이후), limit 기본 500·최대 1000, `commission`·`commissionAsset` | 일치 | `limit=1000`으로 올리면 한 번에 2배 — `A:201` ponytail은 500건 초과만 문제. 시장가 DCA 1건이 500 체결을 넘을 일은 사실상 없음 |
| 키 권한 검증 | `GET /sapi/v1/account/apiRestrictions` (`BinanceAuthApi`) | weight 1, `ipRestrict/enableReading/enableWithdrawals/enableSpotAndMarginTrading`, 만료 필드 없음 | 일치 | 적합. 만료 필드 없음은 `ApiKeyExpirySource.NONE`과 일치(7절 단서) |

## 3. 주제별 점검

| 주제 | 현재 구현 (file:line) | 공식 문서 | 판정 |
|---|---|---|---|
| 서명 | HMAC-SHA256 hex, payload = 파라미터 + `recvWindow` + `timestamp`, `URLEncoder`로 퍼센트 인코딩한 문자열을 서명(`H:71-79,85-100`), `X-MBX-APIKEY` 헤더 | HMAC은 secret으로 hex, payload는 퍼센트 인코딩 후 서명(2026-01-15부터 미준수 시 -1022). POST 파라미터는 query·body 어느 쪽이든 허용, 같은 키가 양쪽이면 query 우선 | 적합. 현재 값(영숫자·`.`)은 `URLEncoder`(공백→`+`, `~`→`%7E`) 차이가 닿지 않는다. 미실측 단서는 6절 참고 |
| timestamp·recvWindow | `clock.millis()`, recvWindow 5000 (`H:33,74-75`) | recvWindow 기본 5000·최대 60000, 5000 이하 권장. 서버는 `timestamp < serverTime+1s` 그리고 `serverTime-timestamp <= recvWindow`일 때만 수락 | 적합. `recvWindow=5000`은 기본값과 같아 생략 가능(동작 동일) |
| 서버 시간 동기화 | 없음. -1021은 `rejected=true`(확정 거절)로 로그만 (`H:127-131`) | `GET /api/v3/time` weight 1로 오프셋 보정 가능. 미래 쪽 허용치가 1초뿐 | 의심: OCI 시계가 서버보다 1초 이상 앞서면 모든 서명 호출이 -1021로 영구 실패하는데 알림 경로가 ERROR 로그뿐. 확정 거절이라 DCA 라운드 전체가 SKIP 처리됨. 실측 빈도는 문서화된 바 없음(미검증) |
| rate limit | 429·418 → `BrokerRateLimitException` 단일 변환, 헤더 미사용 (`H:121`) | 429 시 즉시 backoff 의무, 반복하면 418 IP 차단(2분~3일), `Retry-After`(초) 제공, `X-MBX-USED-WEIGHT-1m` 헤더 | 추적·Retry-After 준수 없음. 현재 호출량(틱당 수십 weight)은 한도 6000/분의 1% 미만이라 실질 위험은 낮다. 단 `X`는 429 후에도 다음 틱에서 같은 호출을 재시도하고, 418이면 `Retry-After`만큼 침묵해야 하는데 알 방법이 없다. 필요 최소: `Retry-After`를 예외에 실어 라운드를 중단(헤더 추적은 불필요) |
| 429 의미 오해 | `docs/agents/binance-api.md`는 "성공 요청은 weight 0이라 헤더 추적 생략" | CHANGELOG 2026-03-27: weight 0이 되는 것은 **주문 생성·취소류(POST /order 등 13개)** 뿐, 실패 시엔 그대로 부과. `GET order`=4·`account`=20·`exchangeInfo`=20·`ticker/price`=2·`klines`=2·`myTrades`=5는 그대로 | 문서 오류(6절). 조회 호출은 계속 weight를 쓴다 |
| 주문 멱등성 | clientOrderId = 주문 UUID 32자 hex(`BinanceClientOrderIds.toWire`, 전송·조회 동일), 재전송 금지, SUBMITTING 유지 후 `origClientOrderId` 조회 (`A:149,181`) | `newClientOrderId`는 "열린 주문 중에서만 고유", 같은 ID는 이전 주문이 체결된 뒤에야 재수락. 자동 생성됨. 길이·문자 규칙은 이 문서 페이지에 없음 | 설계는 문서와 부합(재전송 금지가 맞다). 시장가는 즉시 체결돼 "열린 주문"에 거의 남지 않으므로 거래소 중복 방지는 사실상 없음 → 앱 단 SUBMITTING 유지가 유일한 방어 |
| 접수 불명 후 조회 -2013 | -2013이면 "없음 확정" → `S:73-78`이 FAILED 처리 → 재판정 (`A:186`) | 5xx·-1007은 "실패로 취급하지 말 것(체결됐을 수 있음)", 상태는 API로 조회. Memory→Database 순 조회로 지연이 있을 수 있음 | 의심(낮음): -1007/5xx 직후 다음 틱 조회가 DB 반영 전이면 -2013 → FAILED → 재매수 가능성. 동기화기가 틱 간격 뒤에 조회하고 문서에 구체 지연 수치가 없어 실측 필요(7절) |
| 주문 유형 | MARKET만. 매수 `quoteOrderQty`, 매도 `quantity` (`A:130,140,148`) | MARKET은 둘 중 하나. quoteOrderQty 시장가는 LOT_SIZE를 깨지 않는 범위에서 금액에 최대한 근접 | 적합. LIMIT·timeInForce 불필요(DCA는 시장가). 부분 체결 후 종료는 MARKET이 유동성 부족 시 `EXPIRED`+체결>0 — `toResult`가 CANCEL로 매핑하고 체결 판정을 `executedQty>0`으로 하므로 처리됨 |
| 심볼 필터 | exchangeInfo 실조회, 캐시 없음, `BinanceSymbolRegistry`는 심볼 매핑만 하드코딩(BTC·ETH, `R:15-18`) | 필터 값은 심볼별로 바뀔 수 있음 | 필터는 하드코딩이 아니라 조회 → 적합. 레지스트리의 BTCUSDT 등은 "새 코인 추가 시 양쪽 갱신" 용도로 `StrategyTicker`와 이중. base 자산은 `ticker.name()`(`R:27`)이라 심볼 문자열도 `ticker.name()+"USDT"`로 파생 가능(2코인만 있어 이득 작음) |
| 수량·금액 자릿수 | 매수 `stripTrailingZeros().toPlainString()`, 매도 step 자릿수 DOWN (`A:130,137-140`) | 정밀도 초과는 -1111 BAD_PRECISION, 수량은 stepSize 배수 필수 | 적합. `CryptoTradingRules.quantityScale()`이 10의 거듭제곱 step만 가정(ponytail 주석). BTC/ETH는 1e-5·1e-4 |
| 체결 확인 | 원장은 조회 결과만 사용(`S:73,87`). 닫힌 주문 + executedQty>0이면 myTrades로 Σqty 검증 (`A:196-208`) | `GET order`에는 수수료가 없고 `myTrades`(orderId, weight 5)에 `commission/commissionAsset`. POST FULL 응답에도 fills 있음 | 적합. POST 응답의 `fills`는 소비처가 없다(4절) |
| 수수료 | base 자산 수수료만 차감(`A:225-228`), 표준율 0.1% 상수는 표시용 (`A:30,119`) | 수수료는 받는 자산에서 차감, BNB 수수료 선택 시 BNB로 차감. 계정별 수수료는 `GET /account/commission`(weight 20) | 적합. BNB·혼합 수수료도 처리. 단 USDT 원장 정산이 "견적 통화로 낸 수수료만" 가정(`S:100-106`)이라 BNB 수수료의 USDT 환산 비용은 원장에 반영되지 않는다 — 의도된 설계로 보이나 확인 못 함 |
| 부분 체결·만료 | `PARTIALLY_FILLED`·`NEW`는 WAIT, `EXPIRED`·`EXPIRED_IN_MATCH`·`REJECTED`는 CANCEL (`A:212-217`) | enums: `PENDING_CANCEL`은 "currently unused", `EXPIRED`=규칙·거래소에 의한 취소, 2026-05-06부터 조회 응답에 `expiryReason` | 적합. `PENDING_CANCEL` 매핑은 죽은 분기. `expiryReason` 미사용(진단 편의만) |
| 오류 코드 매핑 | 429/418, {-2014,-2015,-1022}, 5xx/{-1006,-1007}, 그 외 확정 거절 (`H:34-35,117-132`) | -1006·-1007 = 실행 상태 불명, 5xx도 불명(문서가 명시), 403 = WAF 차단, -1003 = rate limit, -1015 = 주문 과다, -2010 = 매칭 엔진 거절 | 대체로 적합. 의심: (a) -1022를 자격 증명 오류로 묶으면 서명 버그(인코딩 규칙 변경 등)가 "키 만료"(`CryptoFailure.KEY_EXPIRED`)로 보인다 (b) 403(WAF)이 `code=null`로 `rejected=true`가 된다 — 주문 전 차단이라 대체로 안전하나 문서는 "rate limit 위반일 수 있음"이라고 하므로 429 계열(backoff)이 더 맞다 |
| API 키 권한·만료 | `apiRestrictions`로 출금 → IP → 권한 순 판정, `ApiKeyExpirySource.NONE` (`Broker.java:16`) | apiRestrictions에 만료 필드 없음. TRADE 권한은 기본 꺼짐 | 적합. IP 제한을 필수로 강제하므로 NONE과 일관. 미확인: IP 제한 없는 거래 키의 만료 정책(7절) |

## 4. 줄일 수 있는 것과 빠진 것

### 4-1. 줄일 수 있는 것 (문서 기준 불필요)

| # | 항목 | 근거 | 효과 |
|---|---|---|---|
| R1 | 주문 POST 응답을 `FULL`로 받고 `fills`로 수수료 계산(`A:150,155-157`) | `X:192-193`은 `exchangeOrderId()`만 쓴다. 원장은 항상 조회 경로(`S`)가 확정. 공식 문서상 MARKET 기본값이 FULL이라 `ACK`로 낮추면 응답이 `orderId`·`clientOrderId`만 | `placeOrder`의 `toResult`·`fills` 파싱 제거, 반환 타입을 `String exchangeOrderId`로 축소 가능(5절) |
| R2 | `sellMarketByVolume`의 `getTradingRules` 재호출(`A:137`) | exchangeInfo weight 20을 매도마다 추가 소비, `X:102`가 이미 같은 틱에 메모. 수량 내림은 호출 전(executor)이나 `quantityStep`을 인자로 받아 처리 가능 | 호출 1회 제거, 어댑터가 규칙을 모르게 됨 |
| R3 | `findOrderByExchangeOrderId`와 `findOrderByClientOrderId` 2벌(`A:161-189`) | clientOrderId는 주문 UUID로 항상 알고 있고 90일간 조회 가능(`-2026` 이후엔 아카이브). 공식 문서는 `orderId`가 더 빠르다고 하나 DCA 틱 단위 호출엔 차이 무의미. `-2026` 처리가 두 메서드에서 달라(CANCEL vs empty) 오해 소지 | 조회 1벌로 통합하면 `-2026` 분기 단일화. 단 `orderId` 영속은 유지(진단용) |
| R4 | `CryptoTradingRules.feeRate/feeSide`·`STANDARD_FEE_RATE`(`A:30,119`) | 바이낸스는 `RECEIVED_ASSET` 고정이라 `quoteSpendFor`·`affordableAmount`가 항등. `X:206`·`DcaCycleOrderStrategy.java:137`이 호출하지만 결과가 입력과 같다 | 업비트 삭제 후 제거 후보(5절) |
| R5 | stats `BinanceMarketDataAdapter.getSpotPrice`·`getSpotDailyClosesSince`(`stats/adapter/out/binance/BinanceMarketDataAdapter.java:39-65`)와 broker `A:75-102`가 같은 공개 엔드포인트를 각자 구현 | 둘 다 서명 없는 `/api/v3/ticker/price`·`/api/v3/klines`. 별도 `RestClient`(`stats BinanceConfig` 3s/7s vs broker 3s/10s), 하드코딩 base URL(`SPOT = "https://api.binance.com"`) vs 프로퍼티 `binance.base-url`. 모듈 경계상 직접 재사용은 불가하나 공개 시세 클라이언트를 `:shared`에 두는 방법을 검토할 수 있음 | 중복 제거 + 테스트넷 base URL 일관. 단 "같은 이유로 함께 바뀜"인지는 판단 필요(klines 범위 조회와 최근 N개 조회는 용도가 다름) |
| R6 | `recvWindow=5000` 명시(`H:33,74`) | 문서 기본값과 동일 | 제거해도 동작 동일, 이득 미미(명시를 유지해도 무방) |
| R7 | `getBalance`의 holdings 맵과 `Holding.avgBuyPrice`(`A:67`) | 바이낸스는 평단 미제공(null), 포트폴리오는 `CryptoPortfolioService.java:49-52`가 원장 평단으로 대체 | 업비트 삭제 후 `avgBuyPrice` 필드·`noExchangeAvg` 분기 제거 후보(5절) |
| R8 | `toResult`의 `PENDING_CANCEL` 매핑(`A:213`) | enums: "currently unused" | 무해한 죽은 분기, 방어적으로 유지해도 됨 |

### 4-2. 문서상 필요한데 빠진 것 (버그 의심은 표시)

| # | 항목 | 근거 | 등급 |
|---|---|---|---|
| M1 | **혼합 수수료 자산 합산 오류 (버그 의심)**: `paidFee`를 자산 구분 없이 합산하고 `feeAsset`은 첫 체결 자산만 (`A:221-223`) | SELL 중 BNB 잔고 소진으로 USDT+BNB 수수료가 섞이면 `S:100-106`의 `quoteFee`가 BNB 수량(예: 0.0003)까지 USDT로 더한다. 금액이 작아 영향은 미미하나 원칙상 틀린 합산. `A:222` ponytail은 "원장은 base 차감으로 정확"이라 했으나 이건 USDT 예수금 쪽 문제 | 낮음 |
| M2 | **-1021 대응 없음 (의심)**: 서버 시각 오프셋 보정 없음, -1021은 확정 거절 (`H:127-131`) | 서버보다 클라이언트가 1초 넘게 앞서면 모든 서명 요청이 영구 실패. `GET /api/v3/time` weight 1로 오프셋 계산 가능 | 중간(발생 시 라운드 전체 SKIP, 알림은 ERROR 로그뿐) |
| M3 | **Retry-After·418 차단 시간 무시** (`H:121`) | 문서: 429에서 backoff 의무, 418은 2분~3일, `Retry-After`로 해제 시각 제공 | 낮음(호출량이 한도의 1% 미만) |
| M4 | 403(WAF) 처리 (`H:131`) | 문서: 403 = WAF 규칙 위반(rate limit 위반 가능) | 낮음. 현재 `rejected=true` |
| M5 | `cummulativeQuoteQty` 음수 가드 없음 (`A:219-220`) | 문서: 오래된 주문은 음수 = 데이터 없음 | 낮음 |
| M6 | MARKET_LOT_SIZE·minQty·maxQty·applyMinToMarket 미사용 (`A:116-118`) | 문서 filters 페이지 | 낮음(현재 BTC/ETH는 step만 의미) |
| M7 | myTrades `limit` 기본 500(`A:201` ponytail 기록) | 문서: 최대 1000 | 사실상 무관 |

## 5. 구현 1개가 될 때 포트 단순화 여지 (업비트 일반화 흔적)

전제: `UpbitExchangeAdapter` 삭제 후 `CryptoExchangePort` 구현은 `BinanceExchangeAdapter` 1개. 아래는 "동작 변경 없이 줄일 수 있는 것"과 "판단이 필요한 것"으로 나눈다.

| # | 위치 (file:line) | 흔적 | 제안 | 구분 |
|---|---|---|---|---|
| P1 | `CryptoTradingRules.java:9-10,15-18,31-39` (`feeRate`·`feeSide`·`FeeSide`·`quoteSpendFor`·`affordableAmount`의 QUOTE_SURCHARGE 분기), `A:30,119`, `X:206`, `DcaCycleOrderStrategy.java:137`, `X:213` 주석 | 업비트 KRW 시장가 매수가 수수료를 견적 통화에 가산해서 생김. 바이낸스는 `RECEIVED_ASSET`라 항등 | 레코드를 `(minNotional, quantityStep)`로 축소. `quoteSpendFor`·`affordableAmount`의 호출부는 `amount`·`cash.setScale(scale, FLOOR)` 직접 사용. 단 다른 코인 거래소(예: 업비트 재추가)를 상정하면 유지 | 동작 보존 리팩토링(테스트 선행 필요) |
| P2 | `CryptoBalance.java:21` (`Holding.avgBuyPrice`), `CryptoPortfolioService.java:49-52,86`, `CryptoPortfolioResponse.java:25-30` | 업비트 `avg_buy_price` 노출용. 바이낸스는 항상 null → 원장 평단으로 대체 | `avgBuyPrice` 제거, 서비스는 항상 원장 평단 조회. DTO의 `avgBuyPrice`는 응답 필드(UI 계약)라 유지 | 소비처 정리 필요 |
| P3 | `CryptoOrderResult.java:14` 주석(`업비트 "KRW"`), `State.WATCH`(예약 대기, 바이낸스 미생성) | 업비트 주문 상태 | `WATCH` 상수 제거 가능(소비처는 `S:114-121`이 WAIT/WATCH를 "유지"로 묶음) | 동작 보존 |
| P4 | `S:115` 주석 "업비트 시장가 매수(금액 지정)는 전량 체결돼도 자투리 때문에 CANCEL로 끝남" | 업비트 특성. 바이낸스 시장가 매수는 정상 체결이면 FILLED이고 `EXPIRED`+체결>0은 유동성 부족 케이스 | 로직(체결량 있으면 CANCEL이어도 확정)은 바이낸스에도 유효(부분 체결 후 EXPIRED). 주석만 바이낸스 기준으로 교체 | 주석 |
| P5 | `CryptoExchangePort.java:35` 주석 "(업비트 영구, 바이낸스 열린 주문 중에서만)" | 거래소별 고유 범위 차이 | 바이낸스 한 줄로 축소 | 주석 |
| P6 | `CryptoExchangePort.java:31` "1~400" vs `A:34,86`의 `MAX_COUNT=999` | 업비트 일봉 상한(추정) 기준 계약. 어댑터는 999까지 허용해 포트 주석과 불일치 | 계약을 바이낸스 limit(최대 1000, 진행 봉 여유 1)에 맞춰 999로 정정 | 문서 정합 |
| P7 | `CryptoExchangePort.java:20`(`supports()`), `CryptoExchanges.java:17-30`, 소비처 `CryptoDcaRunner.java:91`·`AccountLedgerTotalsService.java:55`·`UnassignedCashCalculator.java:64`·`CryptoPortfolioService.java:44,70` | `Map<Broker, Port>` 라우터. 구현 1개면 `of(broker)`는 "코인 거래소가 아닌 증권사" 검증 역할만 남음 | 소비처가 포트를 직접 주입받고 broker 검증은 `Broker.isCrypto` 같은 도메인 판정으로 이전하는 안. 코인 거래소 추가 계획이 없을 때만 의미. 이전 결정("바이낸스는 USDT 현물 DCA 주력")과 일치하므로 판단 안건으로 올림 | 판단 필요 |
| P8 | `CryptoExchangePort.java:36,39` 반환 `CryptoOrderResult` | 업비트 응답도 전체 결과 형태로 맞추려 한 흔적 | `exchangeOrderId`만 쓰므로(`X:192-193`) 반환을 `String`으로 줄이고 `newOrderRespType=ACK`(R1) | 동작 보존(소비처 1곳) |
| P9 | `CryptoExchangePort.java:41-44` 조회 2벌 + `Optional` | 업비트는 uuid·identifier 양방향이 모두 필요했음 | R3의 통합. 바이낸스는 clientOrderId 하나로 충분(주문 UUID) | 판단 필요(`orderId` 영속 유지 여부) |
| P10 | `shared/.../Broker.java:14,16`·`Market.CRYPTO_KRW`·`Currency.KRW` 연계 코드 다수 (`grep KRW|UPBIT` 약 45개 파일, `stats/.../UpbitMarketDataAdapter` 등) | 업비트 마켓 | 이 조사 범위 밖. 업비트 매매 삭제와 별개로 stats의 김프용 업비트 시세(`UpbitMarketDataPort`)는 매매 어댑터 삭제 대상이 아닐 수 있음 → 삭제 범위 확정 시 별도 대조 필요 | 범위 확인 |

## 6. `docs/agents/binance-api.md`가 공식 문서와 다른 곳

| # | 문서 위치 | 현 서술 | 공식 | 조치 |
|---|---|---|---|---|
| D1 | "기본 사실 · 레이트" | "성공 요청은 weight 0(2026-04 변경)이라 `X-MBX-USED-WEIGHT-1M` 로깅은 생략" | weight 0은 **주문 생성·취소류만**(CHANGELOG 2026-03-27, 13개 REST), 실패 요청은 그대로 부과. `GET order`=4·`account`=20·`exchangeInfo`=20·`ticker/price`=2·`klines`=2·`myTrades`=5는 변함없음 | 문구 정정. 결론(헤더 추적 생략)은 호출량 때문에 여전히 합리적이나 근거가 틀렸다 |
| D2 | "거래 규칙" | "캐시 없음 … 성공 요청 weight 0" | 위와 동일: `exchangeInfo`는 weight 20 | 근거를 "호출량이 낮음"으로 교체 |
| D3 | "서명" | "2026-01 이후 -1022 규칙, 공식 문서 기준, 미실측" | CHANGELOG 2025-12-17: 2026-01-15 07:00 UTC부터 서명 전 payload를 percent-encode하지 않으면 -1022 (확인됨) | "미실측"만 유지, 날짜 정정 |
| D4 | "오류 → 예외" 3행 | "5xx·-1006·-1007 → 불명" | 공식 general-api-information은 5xx·-1007(10초 타임아웃)을 불명으로 명시, **-1006은 해당 페이지에 서술 없음**(errors 페이지엔 "unknown execution status") | 출처만 errors 페이지로 |
| D5 | "엔드포인트" | `exchangeInfo` 서명 없음 / `symbol` | 맞음. weight 20이라는 언급이 없다 | weight 표 추가 권장 |
| D6 | "clientOrderId 규칙" | "36자(하이픈) 수락 여부는 공식 문서에 명시 없음" | 맞음. 문서 페이지에 길이·문자 규칙이 없다. 웹 검색 결과(비공식 근거)는 Spot 규칙을 `^[a-zA-Z0-9-_]{1,36}$` 로 말하지만 확정 못 함(7절) | 테스트넷 IT 결과 대기 유지 |
| D7 | "주문 상태" | `PENDING_CANCEL`→WAIT | enums: "currently unused" | 주석 한 줄 |
| D8 | "연결 테스트" | `apiRestrictions` 한 번 | 맞음(weight 1). `enableReading`·`enableSpotAndMarginTrading` 필드명 일치 | 변경 없음 |
| D9 | "순수량·수수료" | 계정별 수수료 `/api/v3/account/commission`은 안 쓴다 | 맞음(weight 20, BNB 할인율 `discount` 포함) | 변경 없음 |
| D10 | "일봉" | `limit=count+1`, UTC 00:00 = KST 09:00 | klines `timeZone` 파라미터(기본 UTC)로 KST 봉도 가능. 문서에는 없는 선택지 | 언급 추가(비권장 이유: 지표 값 변경) |

## 7. 불명확·확인 못 한 것

- POST `/api/v3/order` FULL 응답 스키마·`GET /api/v3/order` 응답 필드는 WebFetch 요약이 잘려 문서 원문으로 직접 확인하지 못했다(`fills.commission/commissionAsset`는 account 페이지의 myTrades와 CHANGELOG 문맥으로만 확인).
- `newClientOrderId` 허용 문자·길이: Spot 문서 페이지에 없음. 비공식 검색 결과만 있음. 하이픈 36자 사용 가능성은 테스트넷 IT 미실행(키 없음)으로 확인 못 함.
- IP 제한이 없는 거래 키의 만료 정책(있다면 `ApiKeyExpirySource.NONE`의 전제가 IP 제한 필수 정책에 의존): 이번 문서에서 확인되지 않음. KISTA는 `ipRestrict=false`면 등록을 거부하므로(`BinanceAuthApi.checkRestrictions`) 현재는 일관되나, 이 가정은 문서로 확정 못 함.
- -1007·5xx 직후 `GET order`가 -2013을 돌려줄 수 있는 지연 구간의 크기(Memory→Database). 문서에 수치 없음, 실측 불가(쓰기·실주문 필요).
- `X-MBX-USED-WEIGHT` 실사용량·-1021 빈도: 운영 로그(`ssh kista-server`) 읽기 조회로 가능하나 이번 범위에서 수행하지 않았다.
- enums 페이지의 rate limit 값(REQUEST_WEIGHT 6000/분, RAW_REQUESTS 61000/5분)은 CHANGELOG(RAW_REQUESTS 300,000/5분, 2026-04-02)와 불일치 — 문서 내부 갱신 지연으로 보이며 어느 값이 현재인지 확정 못 함. 호출량이 훨씬 낮아 결론엔 영향 없음.
- 통합 테스트·단위 테스트·유지 목록(`docs/superpowers/specs/archive/2026-10-23-binance-spot-dca-design.md`, `dca.md`)은 이번엔 대조하지 않았다(`dca.md`는 clientOrderId·순수량 서술만 확인, binance-api.md와 일치).
