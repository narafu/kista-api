## 업비트 API

코드 기준. 구현은 `com.kista.tradingcore.broker.adapter.out.upbit`(벤더 예외 `domain.model.upbit.UpbitApiException`, internal). KRW 마켓 코인 DCA 전용(`Broker.UPBIT`, `Market.CRYPTO_KRW`)이고, 관리자 설정에서 **기본 비활성**이다(허용 IP 안내값 `TradingPolicySettings.SERVER_EGRESS_IP` — 업비트·바이낸스 공용).

### 어댑터 구조

`com.kista.tradingcore.broker.adapter.out.upbit` 구성:

- `UpbitConfig` — `upbitRestClient`(baseUrl 고정, 타임아웃 connect 3s·read 10s, Toss와 같은 `HttpClientTimeouts.jdkTimeouts`).
- `UpbitHttpClient` — 요청마다 HS512 JWT 서명(JDK `Mac`, 토큰 캐시 없음)과 오류 바디 `error.name` → 벤더 중립 예외 매핑. `getPublic`(무인증)·`get`·`post`(인증) 3개 진입점.
- `UpbitMarketRegistry` — `StrategyTicker` ↔ 마켓 코드(BTC→`KRW-BTC`, ETH→`KRW-ETH`)와 `/v1/accounts` currency·`/v1/ticker` market → 지원 종목 역변환. 새 코인은 양쪽 갱신. KRW-USDT 마켓은 김프 전용이라 stats `UpbitMarketDataAdapter`로 이전했다.
- `UpbitExchangeAdapter` — `CryptoExchangePort`(잔고·현재가·완성 일봉·시장가 매수/매도·주문 조회·거래 규칙).
- `UpbitAuthApi` — `BrokerConnectionTestPort`(`/v1/accounts` 호출 + `/v1/api_keys`로 키 만료일 조회).

김프용 공개 시세(KRW-USDT·코인 KRW 현재가/일봉)는 이 어댑터가 아니라 stats `UpbitMarketDataAdapter`(무인증)가 맡는다 — 업비트 거래를 제거해도 남는다. 모듈 구성 전체 → `modules/tradingcore/broker.md`.

### 기본 사실
- Base `https://api.upbit.com`(프로퍼티 `upbit.base-url` — trading-core `application.yml`에 선언, 미설정 시 `UpbitConfig`의 `@Value` 기본값 동일). 호출부는 상대 경로만 쓴다.
- 계좌번호가 없다: 키 쌍(`appKey`=access key, `secretKey`)만 쓰고 `accountNo`는 null이다. `Broker.isKeyOnlyCredentials()=true`라 중복 판정 키는 appKey(`Account.dedupeKey()`)이다. 등록 API는 `AccountRequest.accountNo`의 `@Pattern`을 그대로 두되 서비스가 값을 버려 null로 저장한다(스펙 "오면 무시"에서 수용된 편차 — 형식이 틀린 값은 400). `account_no`는 NULL 허용이고 관리자 계좌 응답의 `accountNoMasked`·`accountNo`는 null이다.
- 인증: 인증 호출은 `Authorization: Bearer <JWT>`다. JWT는 HS512이고 payload는 `access_key`·`nonce`(매 요청 새 UUID)이며, 쿼리·본문이 있으면 `query_hash`(SHA512 hex)와 `query_hash_alg=SHA512`를 더한다. `query_hash`는 **비인코딩** `key=value&...` 문자열에서 만든다 — GET은 실제 쿼리 문자열, POST는 JSON 본문을 같은 순서의 key=value 쌍으로 펼친 문자열이다(값이 마켓 코드·UUID·숫자라 인코딩 대상 문자가 없다). Nimbus `MACSigner`는 HS512에 512bit 미만 키를 거부해서 JDK `Mac`을 직접 쓴다. 공개 시세(티커·캔들)는 JWT 없이 호출한다.
- 키·시크릿이 비어 있으면 `UpbitAuthApi.verifyCredentials`가 서명 전에 `BrokerCredentialException`을 던진다.
- 레이트: HTTP 429·418만 `BrokerRateLimitException`으로 매핑한다. 재시도는 호출부 몫이다(어댑터에 재시도·헤더 로깅 없음).

### 엔드포인트
| 용도 | 호출 | 인증 | 필수 파라미터·메모 |
|---|---|---|---|
| 잔고 | `GET /v1/accounts` | O | `KRW` 행은 예수금(balance·locked), 지원 코인은 보유. `unit_currency`가 KRW가 아닌 행·미지원 코인은 제외. 평단 `avg_buy_price` 사용 |
| 현재가 | `GET /v1/ticker` | X | `markets`(쉼표 구분, 1회 호출) → `trade_price` |
| 일봉 | `GET /v1/candles/days` | X | `market`, `count`(최대 200), `to`(페이징) |
| 주문 | `POST /v1/orders` | O | 본문 `market`·`side`·`ord_type`·`identifier` + 매수 `price` / 매도 `volume` |
| 주문 조회 | `GET /v1/order` | O | `uuid`(거래소 주문 ID) 또는 `identifier`(주문 UUID) |
| 키 목록 | `GET /v1/api_keys` | O | `access_key`·`expire_at`로 해당 키 만료일 조회(연결 테스트 시) |

- 매수는 `side=bid`·`ord_type=price`(시장가, price=총액, volume 생략), 금액은 `stripTrailingZeros().toPlainString()`. 0 이하면 `IllegalArgumentException`.
- 매도는 `side=ask`·`ord_type=market`(volume 필수, price 생략), 수량은 소수 8자리 `RoundingMode.DOWN`(보유 초과 방지), 0 이하면 `IllegalArgumentException`.
- 거래 규칙(`getTradingRules`)은 코드 상수 1개다: 수수료 0.05% **원화 가산**(`FeeSide.QUOTE_SURCHARGE`)·최소 주문 5,000원·수량 단위 1e-8. 조회 API 없음, 종목별 차이 없음.
- 일봉(`getCompletedDailyCandles`): count 1~400. 한 번에 최대 200봉이라 `to`(직전 응답 중 가장 오래된 봉의 UTC 시작, exclusive)로 최대 3페이지까지 페이징한다. 첫 페이지는 진행 중 봉을 버릴 여유로 `count+1`을 요청한다. 진행 중 봉 포함 여부가 문서에 없어 직접 보장한다 — 봉 시작(KST)+1일이 현재 시각 이하일 때만 완성 봉으로 쓰고, KST 일자 기준 중복 제거·오래된 순 정렬 후 마지막 `count`개를 반환한다. 가장 오래된 봉에 UTC 시각이 없으면 페이징을 경고 로그와 함께 중단한다.
- 지원 심볼: BTC→`KRW-BTC`, ETH→`KRW-ETH`(`UpbitMarketRegistry`). 매핑이 없으면 `IllegalArgumentException`.

### 오류 → 예외 매핑 (`UpbitHttpClient.translate`, 위에서부터 첫 매치)
| 순서 | 조건 | 예외 | 비고 |
|---|---|---|---|
| 1 | HTTP 429 / 418 | `BrokerRateLimitException` | 재시도는 호출부 몫 |
| 2 | `error.name` = `no_authorization_ip` | `BrokerIpNotAllowedException` | 허용 IP 미등록 |
| 3 | `error.name` = `out_of_scope` | `BrokerPermissionDeniedException` | 키 권한 부족 |
| 4 | `error.name` ∈ {`invalid_access_key`, `jwt_verification`, `expired_access_key`} | `BrokerCredentialException` | 422 `BROKER_CREDENTIAL_INVALID` |
| 5 | 그 외 | `UpbitApiException(status, name, rejected = 4xx)` | 4xx는 확정 거절(`rejected=true`), 그 외 상태는 `rejected=false` |
| — | I/O 실패(`RestClientException`) | `UpbitApiException(rejected=false, status=0)` | 접수 여부 불명 |
- 오류 바디가 JSON이 아니면 `error.name` 없이 상태 코드로만 판정한다. 원문 바디는 WARN 로그에만 남기고 예외 메시지에는 status·name만 싣는다. `UpbitApiException`은 503(BROKER_UNAVAILABLE)로 매핑된다.
- 주문 조회에서 `order_not_found`만 `Optional.empty()`("없음" — 미접수, 재실행 복구 판단 근거)다. 그 외 `UpbitApiException`은 그대로 올린다.

### 주문 상태 → `CryptoOrderResult.State` (`toResult`)
- 업비트 `state`를 대문자로 바꿔 `CryptoOrderResult.State`로 그대로 해석한다. 해석할 수 없는 값은 접수 여부 불명이라 `UpbitApiException(rejected=false)`로 올린다.
- 체결 수량은 `executed_volume`을 기준으로 삼는다(→ dca.md "코인 DCA 실행"). 평균가는 응답에 필드가 없어 `trades`가 있으면 Σfunds/Σvolume(8자리 HALF_UP)로 계산하고, 체결이 없으면 null이다. 순 체결 수량 = 체결 수량이고, 수수료는 `paid_fee`·수수료 자산은 `KRW`다(KRW 마켓 수수료는 원화).

### identifier 규칙
- `identifier` = 주문 UUID다. 같은 `identifier` 재전송으로 멱등을 얻는다 — 바이낸스 `clientOrderId`는 열린 주문 중에서만 고유해 재전송이 이중 매수가 되는 것과 다르다(→ binance-api.md "clientOrderId 규칙").
- 조회도 `identifier`로 할 수 있어 전송·조회 형태가 같다(변환 계층 없음).

### 연결 테스트 (`UpbitAuthApi`)
- `verifyCredentials`: `GET /v1/accounts` 1회 — 키 오류·허용 IP·자산조회 권한은 `UpbitHttpClient`가 위 매핑대로 422(IP/권한/키)·429·503으로 변환한다.
- `verifyAccount`: `/v1/accounts` + `/v1/api_keys`로 해당 `access_key`의 `expire_at`(예: `2026-06-25T11:22:54+09:00`)을 `Instant`로 읽어 `BrokerAccountVerification(null, expiresAt)`로 반환한다(계좌번호 없음 → 첫 값 null). 일치하는 키가 없거나 `expire_at`이 없으면 만료일 null. 주문하기 권한은 실주문 없이 확인할 수 없어 검증 밖이다.

### API 키 만료
- 만료일(`Account.apiKeyExpiresAt`, 컬럼 `api_key_expires_at`)은 연결 테스트·키 교체 시 위 `/v1/api_keys` 값으로 저장한다. 점검 스케줄러·이벤트·알림 → `modules/tradingcore/account.md` "업비트 API 키 만료 점검", 실행 시각 → `scheduler-time-table.md`.
- 코인 DCA 실행 흐름(10분 틱, 수량·금액 규칙, 신선도) → `modules/tradingcore/dca.md` "코인 DCA 실행".
