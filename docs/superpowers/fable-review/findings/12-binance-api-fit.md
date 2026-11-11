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
| 수수료 | base 자산 수수료만 차감(`A:225-228`), 표준율 0.1% 상수는 표시용 (`A:30,119`) | 수수료는 받는 자산에서 차감, BNB 수수료 선택 시 BNB로 차감. 계정별 수수료는 `GET /account/commission`(weight 20) | 적합. BNB·혼합 수수료도 처리. 단 USDT 원장 정산이 "견적 통화로 낸 수수료만" 가정(`S:150` 부근)이라 BNB 수수료의 USDT 환산 비용은 원장에 반영되지 않는다 — 의도된 설계로 보이나 확인 못 함 |
| 부분 체결·만료 | `PARTIALLY_FILLED`·`NEW`는 WAIT, `EXPIRED`·`EXPIRED_IN_MATCH`·`REJECTED`는 CANCEL (`A:212-217`) | enums: `PENDING_CANCEL`은 "currently unused", `EXPIRED`=규칙·거래소에 의한 취소, 2026-05-06부터 조회 응답에 `expiryReason` | 적합. `PENDING_CANCEL` 매핑은 죽은 분기. `expiryReason` 미사용(진단 편의만) |
| 오류 코드 매핑 | 429/418, {-2014,-2015,-1022}, 5xx/{-1006,-1007}, 그 외 확정 거절 (`H:34-35,117-132`) | -1006·-1007 = 실행 상태 불명, 5xx도 불명(문서가 명시), 403 = WAF 차단, -1003 = rate limit, -1015 = 주문 과다, -2010 = 매칭 엔진 거절 | 대체로 적합. 의심: (a) -1022를 자격 증명 오류로 묶으면 서명 버그(인코딩 규칙 변경 등)가 "키 만료"(`CryptoFailure.KEY_EXPIRED`)로 보인다 (b) 403(WAF)이 `code=null`로 `rejected=true`가 된다 — 주문 전 차단이라 대체로 안전하나 문서는 "rate limit 위반일 수 있음"이라고 하므로 429 계열(backoff)이 더 맞다 |
| API 키 권한·만료 | `apiRestrictions`로 출금 → IP → 권한 순 판정, `ApiKeyExpirySource.NONE` (`Broker.java:16`) | apiRestrictions에 만료 필드 없음. TRADE 권한은 기본 꺼짐 | 적합. IP 제한을 필수로 강제하므로 NONE과 일관. 미확인: IP 제한 없는 거래 키의 만료 정책(7절) |
