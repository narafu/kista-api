## 바이낸스 API

2026-10-23 공개 시세 실측 + 코드 기준. 구현은 `com.kista.tradingcore.broker.adapter.out.binance`(벤더 예외 `domain.model.binance.BinanceApiException`, internal). 현물 USDT 마켓 코인 DCA 전용이고 `Broker.BINANCE`는 관리자 설정에서 **기본 비활성**이다(허용 IP 안내값 `TradingPolicySettings.SERVER_EGRESS_IP`).

### 기본 사실
- Base `https://api.binance.com`(프로퍼티 `binance.base-url`, 기본값은 `BinanceHttpClient`의 `@Value` — yml에는 없다). 테스트넷은 `https://testnet.binance.vision`. `BinanceConfig`의 `binanceRestClient`는 baseUrl이 없다 — 서명된 절대 URI를 그대로 보내 템플릿 재인코딩(서명 불일치)을 피한다. 타임아웃 connect 3s·read 10s.
- 계좌번호가 없다: 키 쌍(`appKey`=API key, `secretKey`)만 쓰고 `accountNo`는 null이다. `Broker.isKeyOnlyCredentials()=true`라 중복 판정 키는 appKey(`Account.dedupeKey()`)이고, 키 만료일이 없어 `apiKeyExpiring=false`(`ApiKeyExpiryScheduler` 대상 아님)다. 시크릿은 서명에 필수다.
- 서명: HMAC-SHA256 hex, 헤더 `X-MBX-APIKEY`. 모든 파라미터는 **query string**에 넣고(POST 포함) body는 비운다 — 서명 경로를 1개로 만든다. payload = 호출 파라미터 + `recvWindow=5000` + `timestamp`(`Clock.millis()`) 순서이고, **퍼센트 인코딩된 그 문자열이 곧 서명 대상**이다(2026-01 이후 -1022 규칙). `signature`는 마지막에 붙인다. 공개 시세·exchangeInfo는 서명 없음.
- 키/시크릿이 비어 있으면 호출 전에 `BrokerCredentialException`이다.
- 레이트: IP 단위. 성공 요청은 weight 0(2026-04 변경)이라 `X-MBX-USED-WEIGHT-1M` 로깅은 생략하고 429/418만 `BrokerRateLimitException`으로 매핑한다.

### 엔드포인트
| 용도 | 호출 | 서명 | 필수 파라미터·메모 |
|---|---|---|---|
| 잔고 | `GET /api/v3/account` | O | `omitZeroBalances=true`. `balances[].asset/free/locked` — USDT는 예수금, 지원 코인은 보유(평단은 거래소 미제공 → null, 포트폴리오는 원장 평단) |
| 현재가 | `GET /api/v3/ticker/price` | X | `symbol`(티커마다 1회) |
| 일봉 | `GET /api/v3/klines` | X | `symbol`, `interval=1d`, `limit=count+1`. 행 = `[openTime, open, high, low, close, ...]` |
| 거래 규칙 | `GET /api/v3/exchangeInfo` | X | `symbol` |
| 주문 | `POST /api/v3/order` | O | `symbol`, `side`, `type=MARKET`, `newClientOrderId`, `newOrderRespType=FULL`, 매수 `quoteOrderQty` / 매도 `quantity` |
| 주문 조회 | `GET /api/v3/order` | O | `symbol` + `origClientOrderId` 또는 `orderId` |
| 체결 내역 | `GET /api/v3/myTrades` | O | `symbol`, `orderId` — 닫힌 주문이고 체결이 있을 때만 호출 |
| 키 제한 조회 | `GET /sapi/v1/account/apiRestrictions` | O | 파라미터 없음. `ipRestrict`·`enableReading`·`enableSpotAndMarginTrading`·`enableWithdrawals` |

- 매수는 `quoteOrderQty`(견적 통화 금액, `stripTrailingZeros().toPlainString()` — 지수 표기 금지, `10.50`→`10.5`). 소수 2자리 USDT가 그대로 나간다.
- 매도는 `quantity`를 거래 규칙의 수량 단위로 `RoundingMode.DOWN` 한 값이고, 0 이하면 `IllegalArgumentException`.
- 일봉: `openTime + 86_400_000 > now`인 진행 중 봉을 버리고 마지막 `count`개만 쓴다. UTC 00:00 = KST 09:00이라 `openTime`의 KST 일자가 곧 봉 일자다(`CryptoCandle.latestCompletedKstDate`와 일치). count 1~999.
- 지원 심볼: BTC→`BTCUSDT`, ETH→`ETHUSDT`(`BinanceSymbolRegistry`, 새 코인은 양쪽 갱신). 미지원 자산 잔고는 무시한다.

### 오류 → 예외 매핑 (`BinanceHttpClient.translate`, 위에서부터 첫 매치)
| 순서 | 조건 | 예외 | 비고 |
|---|---|---|---|
| 1 | HTTP 429 / 418 | `BrokerRateLimitException` | 재시도는 호출부 몫 |
| 2 | code ∈ {-2014, -2015, -1022} | `BrokerCredentialException` | -2015는 키·IP·권한을 구분하지 않는다("Invalid API-key, IP, or permissions for action.") — IP 미허용으로 단정하지 않는다. 실행 중에는 `CryptoFailure.KEY_EXPIRED`로 분류된다 |
| 3 | HTTP ≥ 500 또는 code ∈ {-1006, -1007} | `BinanceApiException(rejected=false)` | 실행 상태 불명 — 실패 취급·재전송 금지 |
| 4 | 그 외 (-1021 포함) | `BinanceApiException(rejected=true)` | 확정 거절. -1021(timestamp 범위 초과)은 재시도·시각 동기화 없이 ERROR 로그만 남긴다 |
| — | I/O 실패(`RestClientException`) | `BinanceApiException(rejected=false, status=0)` | 접수 여부 불명 |
- 오류 바디가 JSON이 아니면(WAF HTML 등) code 없이 상태 코드로만 판정한다. 원문 바디는 WARN 로그에만 남기고 예외 메시지에는 status·code만 싣는다.
- 주문 조회 -2013(없음)·-2026(90일 지난 무체결 종료 주문)만 "없음" 취급한다(아래 clientOrderId 규칙). 연결 테스트 예외 3종은 "연결 테스트" 절.

### 주문 상태 → `CryptoOrderResult.State` (`toResult`)
| 바이낸스 status | State |
|---|---|
| NEW, PENDING_NEW, PARTIALLY_FILLED, PENDING_CANCEL | WAIT |
| FILLED | DONE |
| CANCELED, EXPIRED, EXPIRED_IN_MATCH, REJECTED | CANCEL |
| 그 외 | `BinanceApiException(rejected=false)` — 알 수 없는 상태는 단정하지 않는다 |
- 체결 판정은 상태가 아니라 `executedVolume > 0`이다. 평균가 = `cummulativeQuoteQty / executedQty`(8자리 HALF_UP, 체결 없으면 null).
- exchangeOrderId 조회에서 -2026은 `State.CANCEL`(체결 0)로 확정해 PLACED 영구 정체를 막는다.

### clientOrderId 규칙
- `newClientOrderId`·`origClientOrderId` = 주문 UUID에서 하이픈을 뺀 **32자 소문자 hex**다. 변환은 `BinanceClientOrderIds` 한 곳(`toWire`/`fromWire`)에서만 한다. 응답의 32자 hex는 8-4-4-4-12로 복원해 `CryptoOrderResult.clientOrderId()`에 싣는다(바이낸스 자동 생성 ID는 그대로).
- 바이낸스 clientOrderId는 **열린 주문 중에서만 고유**하다 — 체결·취소된 뒤에는 같은 ID를 다시 쓸 수 있다. 그래서 같은 ID **재전송은 멱등 보장이 아니라 이중 매수**다. 업비트 `identifier`와 달리 주문 접수는 어떤 경우에도 재전송하지 않는다(호출부 `SUBMITTING` 유지 → 다음 틱 조회).
- 주문 조회에서 "없음"(`Optional.empty()`)은 **-2013(그리고 clientOrderId 조회의 -2026)일 때만**이다. 5xx·-1006·-1007·I/O는 `rejected=false` 예외로 올려 동기화기가 SUBMITTING을 유지한다. 전송 형태와 조회 형태가 다르면 -2013 → FAILED → 재판정 → 이중 매수가 되므로 둘 다 `toWire`를 쓴다.
- 32자 hex가 아닌 36자(하이픈 포함) 수락 여부는 공식 문서에 명시가 없다 — 테스트넷 IT `hyphenatedClientOrderIdAcceptance`가 기록용으로 출력한다(결정은 이 결과와 무관하게 32자 유지).

### 순수량·수수료 규칙
- 원장은 `findOrderBy*` 결과(체결 동기화)만 쓴다. 주문 응답(`FULL`)의 `fills`는 `placeOrder` 결과에만 쓰이고 원장에 닿지 않는다.
- 닫힌 주문(DONE/CANCEL)이고 `executedQty > 0`이면 `myTrades`를 조회해 `Σqty == executedQty`를 확인한다. 다르면 `BinanceApiException(rejected=false)`을 던져 순수량을 잘못 확정하지 않고 다음 틱에 재시도한다(접수 직후 일부만 보이는 경우 방어).
- `netExecutedVolume = executedQty − (commissionAsset == base 자산인 수수료 합)`. **받는 자산(base)으로 낸 수수료만** 차감한다 — BUY의 BTC 수수료는 차감, SELL(USDT 수수료)·BNB 수수료는 `net == executed`. `paidFee`는 전 체결 수수료 합, `feeAsset`은 첫 체결의 자산이다(BNB+BTC 혼합 수수료는 첫 자산만 표기, 원장은 base 차감으로 정확).
- 수수료율 상수 `STANDARD_FEE_RATE = 0.001`(표시·기록용), `FeeSide.RECEIVED_ASSET`이라 커널의 `quoteSpendFor`·`affordableAmount`는 수수료율을 쓰지 않는다. 계정별 수수료 조회(`/api/v3/account/commission`)는 하지 않는다.

### 거래 규칙 (`getTradingRules`)
- `LOT_SIZE.stepSize` → 수량 단위, `NOTIONAL.minNotional`(없으면 `MIN_NOTIONAL`) → 최소 주문 금액. 필터가 없으면 `IllegalStateException`.
- 심볼 `status != TRADING`(`CANCEL_ONLY` 포함)이면 확정 거절이 아닌 `BinanceApiException(rejected=false)` — 틱마다 재시도한다.
- 캐시 없음(설계 편차): 성공 요청 weight 0, 실행기가 계좌·틱 단위로 메모(`rulesMemo`), 호출이 실행 시각 규칙이 있는 틱에만 일어난다. 캐시가 없어 `CANCEL_ONLY` 같은 상태 변화도 즉시 반영된다. 계좌 수가 늘면 TTL 캐시(`// ponytail:` 주석).

### 연결 테스트 (`BinanceAuthApi`)
`GET /sapi/v1/account/apiRestrictions` 한 번으로 판정한다. 키·시크릿 오류는 호출 자체가 -2015/-1022라 `BrokerCredentialException`이다. 응답 판정 순서:
1. `enableWithdrawals` → `BrokerWithdrawalEnabledException`(422 `BROKER_WITHDRAWAL_ENABLED`) — 출금은 IP 제한이 있어야 켤 수 있어 먼저 판정한다. 신규 `ErrorCode`인 이유: kista-ui는 `code`별 고정 문구를 쓰는데 `BROKER_PERMISSION_DENIED`("권한을 켜세요")는 반대 뜻이라 재사용할 수 없다.
2. `!ipRestrict` → `BrokerIpNotAllowedException`(IP 제한 미설정).
3. `!enableSpotAndMarginTrading || !enableReading` → `BrokerPermissionDeniedException`.
`verifyAccount`도 같은 판정이고 `BrokerAccountVerification(null, null)`을 돌려준다. 연결 테스트는 등록·키 교체 시점에만 돈다(등록 후 출금 권한을 켠 경우의 감시는 범위 밖).

### 테스트넷 사용법·한계
- 테스트넷 키(`https://testnet.binance.vision`에서 발급)를 환경변수로 주고 IT를 실행한다: `BINANCE_TESTNET_API_KEY=… BINANCE_TESTNET_SECRET_KEY=… ./gradlew :trading-core:integration --tests '*BinanceTestnetIT' 2>&1 | grep -E "FAILED|BUILD|PASSED|SKIPPED"` — 키가 없으면 전체 SKIPPED. Spring 컨텍스트 없이 `BinanceHttpClient`+`BinanceExchangeAdapter`를 직접 조립한다. 각 케이스가 결과(주문 ID·상태·순수량·수수료 자산·소요 시간)를 stdout에 출력한다.
- **한계**: 테스트넷은 `/sapi`를 지원하지 않아 연결 테스트(apiRestrictions)는 검증할 수 없다. 수수료 체계(BNB 할인 등)가 실계좌와 다를 수 있다.

### 실측
- **2026-10-23 공개 시세(서명 없음, 개발 PC에서 조회)**: `GET /api/v3/ping` 200. `exchangeInfo?symbol=BTCUSDT` — `status=TRADING`, `LOT_SIZE.stepSize=0.00001000`(수량 단위 5자리), `NOTIONAL.minNotional=5.00000000`(최소 5 USDT), orderTypes에 `MARKET` 포함. `klines?symbol=BTCUSDT&interval=1d` — `openTime`이 UTC 00:00 정각(`1792627200000`=2026-10-22T00:00Z, `1792713600000`=2026-10-23T00:00Z).
- **테스트넷 IT: 미검증(키 없음, 2026-10-23 실행 시 6건 SKIPPED)**. 36자 clientOrderId 수락 여부, -2013 응답 실측, myTrades 반영 지연, 테스트넷 수수료 자산은 아직 기록이 없다. 키가 생기면 IT를 돌리고 이 절에 날짜와 함께 추가한다.

### 미검증 (실계좌·키가 필요한 항목)
- 연결 테스트 4가지(정상·출금 켜짐·IP 미제한·거래 권한 꺼짐) — `/sapi/v1/account/apiRestrictions` 필드명은 공식 문서 기준이며 실키 확인 전이다.
- 실계좌 실주문(매수 10.50 USDT·매도) 및 BNB 수수료 차감 계정의 `commissionAsset`.
- `myTrades` 반영 지연(Memory→Database) — 동기화기는 10분 뒤 조회하고 불일치면 예외 → 다음 틱 재시도로 방어한다. 실제 지연은 미실측.
- -1021 빈도 — OCI 시각 오차 실측 없음. 운영 로그에 -1021이 보이면 `/api/v3/time` 오프셋 보정을 추가한다.
- 운영 서버에서 서명 요청·IP 허용 목록 동작 — 공개 `ping`만 확인했다. 실키 등록 시 확인한다.
