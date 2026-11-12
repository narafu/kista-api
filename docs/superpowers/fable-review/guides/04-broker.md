# 지침서 04. 브로커 축 — 비주력 삭제, API 문서 기준 정리, 시세 공급자 분리

작성 2026-11-11, **개정 2026-11-12** (Fable). 근거: `findings/03-parallel-structures.md` B절, `findings/11-broker-api-fit.md`(메리츠·토스 공식 스펙 대조), `findings/12-binance-api-fit.md`(바이낸스 공식 문서 대조), 운영 DB 조회(2026-11-11).

API 문서 원본: 메리츠 `/Users/phs/Downloads/meritz_openapi_spec_20261112.json`, 토스 `/Users/phs/Downloads/toss/`(파일들은 같은 전체 스펙의 사본), 바이낸스 https://developers.binance.com/en/docs/catalog . **구현자는 벤더 동작을 바꾸는 태스크 전에 해당 스펙을 직접 확인한다**(findings는 스펙 대조만 했고 실호출 검증은 하지 않았다).

## 0. 사용자 결정

- **KIS 삭제.** 활성 전략 1건의 이전이 선행 조건이다.
- **업비트 매매 어댑터 삭제.** 김프 지표용 업비트 공개 시세(`stats`)는 남긴다.
- **토스 주문 유지**(2026-11-12). 대중적인 증권사라 연결을 남긴다. 메리츠를 우선하는 이유는 환율·매매 수수료다. 따라서 매매 브로커는 메리츠·토스(주식), 바이낸스(코인), Mock이다.
- 공용 시장 데이터(2026-11-12 최종, 4-3절): 기본 마켓 데이터는 토스 유지. 환율만 Frankfurter(예비 fawazahmed0). 메리츠 관리자 키·Alpaca 시세 이전은 하지 않는다.

운영 현황: MERITZ 계좌 3·활성 전략 2, BINANCE 1·1, KIS 1·1, MOCK 1·4, TOSS 1·0, UPBIT 1·0.

## 1. 현재 구조와 문제

`trading-core/.../broker` 7,632줄. 토스 2,059, KIS 1,634(+130), 메리츠 1,081, 바이낸스 505, 업비트 471, Mock 243.

1. 삭제 확정인 KIS·업비트가 약 2,260줄이다.
2. 공통 골격이 없다. HTTP 클라이언트가 같은 책임을 각자 구현한다.
3. facade(`*BrokerAdapter`)가 위임만 한다(359줄).
4. 시세 공급자와 매매 브로커가 섞여 벤더 이름이 공용 모델에 샜다(`TossHolding`, `TossCandle`, `TossExchangeRate`, root의 `TossCandleResponse`, `MockBrokerAdapter` → `TossPriceApi` 직접 의존).
5. **스펙과 어긋난 코드가 있다**(3절). 버그 의심 9건, 빠진 처리 다수.
6. 토스 토큰 관리가 Redis 분산 구조 약 690줄이다. 스펙이 요구하는 것은 "client당 토큰 1개, 재발급 시 이전 토큰 무효화"에 대한 발급 직렬화뿐이다.

## 2. 목표 구조

```
broker/
  domain/model/            공용 모델만. 벤더 이름 없음
  application/port/output/ 매매 포트(ExecutionPort 등), 코인 거래소 포트, 시세 포트(MarketDataPort 계열)
  application/service/     BrokerRouter, BrokerCallGuard
  adapter/out/
    http/                  공통 골격: 요청 실행, 간격 게이트, 재시도 판정 인터페이스, 토큰 확보
    meritz/  toss/  binance/  mock/     매매
    marketdata/            시세·캔들·환율 공급자 구현(4절 결정에 따라 메리츠 또는 토스)
```

### 2-1. 삭제

- `adapter/out/kis/` 전체, `adapter/out/persistence/KisToken*`, `KisExchangeRegistry`, `KisAdminPriceSource`·`KisAdminTokenCoordinator`, `domain/model/kis`, `KisRateLimitException`, `Broker.KIS`.
- `adapter/out/upbit/` 전체, `domain/model/upbit`, `Broker.UPBIT`. `stats`의 `UpbitMarketDataPort`와 그 구현은 broker 어댑터와 독립인지 확인하고 남긴다.
- `Broker.supportsOpenAuctionOrders()`(KIS만 true)와 그 분기(개장 경매 사전 접수 경로). 동작 경로 삭제라 단계 B에서 따로 다룬다.
- 관리자 시세 체인: KIS가 빠지면 `AdminMarketPriceAdapter`·`AdminPriceSource`의 체인 단계가 1개다. 체인 추상을 지우고 시세 포트 구현 하나로 둔다.
- 문서: `docs/agents/kis-api.md`, `upbit-api.md`, 어댑터 디렉토리 `CLAUDE.md`, 루트 `CLAUDE.md`·`README.md`·`flows.yml`의 KIS·업비트 서술. `CLAUDE.md` 첫 줄 "한국투자증권(KIS)·토스증권 API 기반"도 고친다.

### 2-2. 벤더 이름 제거와 시세 포트

- `TossHolding` → `BrokerHolding`, `TossCandle` → `BrokerCandle`에 흡수, `TossExchangeRate` → `ExchangeRate`, root `TossCandleResponse` → `CandleResponse`(`@Schema(name=...)`으로 기존 스키마 이름을 유지해 UI 변경 0. 지침서 02 8절).
- `BenchmarkService.java:240`의 환율 출처 라벨 `"TOSS_INVEST"`는 공급자에서 받아 온다(하드코딩 제거).
- 시세·캔들·환율은 매매 브로커와 무관한 포트로 묶는다. `MockBrokerAdapter`는 이 포트를 주입받는다(`TossPriceApi` 직접 의존 제거).
- 세션(정규장 진행 여부) 판정은 `TossPriceApi:163-206`의 DST 표 복제를 지우고 `calendar` 모듈(지침서 05)에서 받는다. 조기 마감 미반영 의심도 함께 해소된다.

### 2-3. HTTP 공통 골격 (범위를 좁힌다)

스펙 대조 결과 메리츠와 토스는 성공 판정, 오류 형식, 페이징이 서로 달라 공통화할 수 없다(메리츠는 HTTP 500에도 본문 `rsp_cd`로 성공을 판정하고, 토스는 HTTP 상태와 `result` 래퍼다). 공통 골격에 넣는 것은 다음뿐이다.

| 공통 | 벤더별로 남김 |
|---|---|
| 호출 간격 게이트(정책 값: 메리츠 110ms, 토스·바이낸스 0) | 성공 판정과 오류 매퍼 |
| 재시도 판정 인터페이스 `isRateLimited(response)`, `retryAfter(response)`와 재시도 루프 | 페이징 |
| "토큰 확보 → 401이면 1회 재발급 후 재시도" 흐름 | 요청 서명(헤더 주입) |
| rate limit을 `BrokerRateLimitException`으로 변환 | 주문 body 구성 |

단계 D의 4번(골격이 벤더 합보다 작아지지 않으면 원복) 조건은 유지한다.

### 2-4. 토큰 관리 단순화

지침서 05 개정으로 매매 엔진은 worker 한 프로세스(단일 인스턴스, stop-first)에서만 돈다. 그 전제에서:

- **토스**: Redis lease·fencing·지문 TTL을 걷어내고 "client별 락 1개 + 메모리 캐시 + 401 `token-revoked`/`expired-token` 시 1회 재발급"으로 줄인다. 재기동 직후 1회 재발급은 스펙상 문제없다(이전 토큰이 무효화될 뿐이다). memory `project_toss_redis_store_outage`(Redis 토큰 저장소 빈 오류로 인한 장애)의 원인 구조가 함께 사라진다.
- **메리츠**: 재발급이 같은 토큰을 돌려줘(실측) 비파괴다. `DbTokenCoordinator`(DB 저장)를 유지할지, 토스와 같은 메모리 캐시로 통일할지는 "재기동 때마다 재발급해도 되는가"로 정한다. 비파괴이므로 **메모리 캐시로 통일**하고 토큰 테이블을 지운다. 단, 토큰 응답의 `expires_date`는 스펙에 없는 필드인데 코드가 여기에만 의존한다. `expires_in` 폴백을 넣는다.
- **전제가 깨지는 경우**: 지침서 05의 단계 C(브로커 호출을 web에서도 실행)를 하게 되면 분산 토큰이 다시 필요하다. 그 단계의 선행 조건에 적어 둔다.
- 토스 `verifyAccount`(연결 테스트)는 같은 client_id로 발급해 **운영 중인 토큰을 무효화한다**(주석은 "건드리지 않는다"고 적혀 있다). 이미 등록된 계좌의 키로 연결 테스트를 하면 진행 중인 배치의 다음 호출이 401이 된다. 위 "401 시 1회 재발급"이 이것도 흡수하지만, 연결 테스트가 캐시에 새 토큰을 넣도록 고친다.

### 2-5. facade 제거

`*BrokerAdapter`가 `BrokerCapabilitiesPort`를 구현해 메서드마다 위임하는 대신, 벤더 `Config`가 기능 포트 구현을 record 묶음으로 등록한다. `BrokerRouter`의 공개 메서드는 그대로 둔다.

```java
record BrokerCapabilities(Broker broker, ExecutionPort execution, PortfolioPort portfolio,
                          MarginPort margin, SellableQuantityPort sellable,
                          BrokerOrderCorrectionPort correction) {}
```

### 2-6. 코인 거래소 포트 (바이낸스 1개 구현)

업비트가 빠지면 `CryptoExchangePort`의 구현은 바이낸스 하나다. 지침서 01의 기준으로는 인터페이스를 지울 대상이지만, 사용자 방침(memory `project_broker_strategy`: 코인 거래소 추가 가능성)과 Mock·테스트 대역을 고려해 **포트는 유지하고 업비트 때문에 생긴 일반화만 걷어낸다.**

- `CryptoTradingRules`의 `feeRate`·`feeSide`·`quoteSpendFor`·`affordableAmount`(바이낸스에서는 항등), 주문 결과의 `avgBuyPrice`(바이낸스에서 항상 null), `State.WATCH`(생성되지 않음)를 지운다.
- `CryptoExchanges` 라우터(구현 1개)를 지우고 포트를 직접 주입한다.
- 주문 접수 반환은 `exchangeOrderId` 문자열만 쓴다. 주문 POST 응답 유형을 `ACK`로 받는다.
- 조회 메서드 2벌을 합치고, stats와 broker의 바이낸스 공개 시세 호출·`BinanceConfig` 2벌을 정리한다.

### 기각한 대안

- 전 브로커 유지 + 공통 골격만: 사용자가 삭제로 결정.
- 주식 포트와 코인 포트 통합: 코인 현물은 LOC·MOC·증거금이 없고 24시간 시장이라 모양이 다르다. 실행 파이프라인 이원화는 지침서 06.
- 토스 주문 삭제: 사용자가 유지로 결정.

## 3. 스펙 대조에서 나온 결함·누락

전부 서브에이전트의 스펙 대조 결과다. **구현 전에 스펙 원문과 코드를 다시 확인하고, 재현 테스트를 먼저 쓴다.** 상세와 file:line은 `findings/11` 5-3절, `findings/12` 3·4절.

| # | 벤더 | 내용 | 위험 |
|---|---|---|---|
| B1 | 메리츠 | 취소 시 기간 목록 폴백이 미체결 수량 `ncnt_odqt`를 믿는다. 스펙은 `oder_dqty − cntg_dqty`로 계산하라고 한다. 0으로 오면 **살아 있는 주문의 취소를 건너뛴다**(`MeritzOrderApi.java:61,163`) | 높음(주문 안전) |
| B2 | 메리츠 | 잔고 조회가 `rmnd_clcd`(일반·소수·대출) 행을 합치지 않고 소수부를 버린다(`MeritzTradingApi.java:92,138`) | 중간(원장 불일치) |
| B3 | 바이낸스 | HTTP 403(방화벽 차단)을 주문 확정 거절로 처리한다. 접수 여부를 모르는 상태를 거절로 단정한다 | 높음(이중 주문 또는 누락) |
| B4 | 바이낸스 | `-1021`(시각 오차)에 서버 시각 보정이 없다. 서버 시계가 1초 넘게 앞서면 서명 호출이 계속 실패한다 | 중간 |
| B5 | 토스 | `Integer.parseInt(quantity)`가 소수 수량에서 실패해 보유 조회 전체가 죽는다(`TossHoldingsApi.java:108`) | 중간 |
| B6 | 토스 | 체결 조회에서 `PARTIAL_FILLED`가 OPEN·CLOSED 양쪽 목록에 나올 수 있는데 `orderId` 중복 제거가 없다(`TossOrderApi.java:86-87`) | 중간(체결 이중 집계) |
| B7 | 토스 | `verifyAccount`가 운영 토큰을 무효화한다(2-4절) | 중간 |
| B8 | 바이낸스 | 수수료가 여러 자산으로 부과될 때 그대로 합산한다 | 낮음 |
| B9 | 메리츠 | `EGW00202`를 REST 권한 오류로 분류한다. 스펙에서는 WebSocket 접두어 누락 코드다 | 낮음 |

빠진 처리(결함은 아님): 토스·바이낸스의 `Retry-After` 무시, 토스 401 본문 코드 미사용, 토스 취소 409·422 미분류, 바이낸스 `MARKET_LOT_SIZE`·`applyMinToMarket` 미확인, 메리츠 `expires_in` 폴백 없음.

줄일 수 있는 것: 호출처 0인 `BrokerPricePort.getPriceSnapshot(s)`·`PriceSnapshot`, 메리츠 `SUCCESS_CODES`의 국내 전용 코드(`5620`·`5822`), 메리츠 주문 body `Map` → record 2개, 종목마다 반복하는 `getExecutions`를 계좌·일자당 1회로(`TradingReporter.java:58`, `CancelledOrderFillRecorder.java:38`), 토스 `nextBefore` 미사용 + 자체 `before` 계산, 토스 매수가능금액 중복 호출, 바이낸스 매도마다 거래 규칙 재조회(`BinanceExchangeAdapter.java:137`).

**지우면 안 되는 방어**(스펙이 함정으로 명시): 메리츠의 HTTP 500 + `rsp_cd` 본문 판정, `0000`이 오류에도 오는 경우, 연속조회 키 오류 시 첫 페이지 반환에 대한 `MAX_PAGES` 가드, 가격 0 검사.

문서 드리프트: `binance-api.md`의 "성공 요청은 weight 0"은 틀렸다(주문 생성·취소류만 해당, `exchangeInfo`는 weight 20). 메리츠 `mod_stpr_cls_code` 주석, 토스 "US MARKET 주문 미지원" 주석(미지원은 MOC·MOO)도 스펙과 다르다. 지침서 09에서 고친다.

## 4. 공용 시장 데이터를 메리츠로 옮기는 안

현재 토스 관리자 키로 받는 것: 현재가, 전일 종가, 확정 종가, 일봉 캔들(UI 차트·DCA 지표), 환율, Mock 시세. 휴장일은 토스가 아니라 Alpaca에서 받는다.

**핵심 제약: 메리츠에는 계좌와 무관한 공용 키가 없다.** 시세 API도 토큰이 필요하고 앱키 1개가 계좌 1개에 묶인다. 서버 공용으로 쓰려면 **운영자 본인의 메리츠 계좌 앱키를 관리자 키로 등록**해야 한다. 사용자 키로 시세를 부르는 것은 하지 않는다(사용자 한도 잠식, 사용자 키 만료가 시세 장애로 번짐).

| 항목 | 메리츠 대체 | 근거 |
|---|---|---|
| 현재가 + 전일 종가 | 가능(조건부) | `prices` 1회 호출이 `prpr`와 `prdy_clpr`를 함께 준다. 토스 쪽 세션 판정·캐시가 사라진다. 장 마감 직후·프리마켓 경계에서 `prdy_clpr`의 의미는 스펙에 없다 |
| 확정 종가(마감 리포트·VR 롤오버·Mock) | 가능 | `candles/days`. 메리츠 계좌 경로에서 이미 쓰고 있다 |
| 환율 | 가능 | 고시환율 또는 시장환율 API. 토스 `rate`(매수 환율)와 의미를 맞춰야 한다 |
| Mock 시세 | 가능 | 일봉 OHLC |
| 일봉 캔들(UI 600봉, 최대 5년, DCA는 배당·분할 조정) | 부분 가능 | 개수 파라미터·연속조회가 없고 범위 상한이 스펙에 없다. 조정은 분할만 언급(배당 없음) |
| 휴장일 목록 | 불가 | 메리츠에 없다. Alpaca 유지 |

### 4-1. Alpaca 대안 (2026-11-12 추가 검토, `findings/14-alpaca-market-data.md`)

KISTA는 이미 Alpaca를 쓴다(휴장일 캘린더, 벤치마크 지수 일봉. paper 계정 키, 무료 등급, 헤더 인증). 사용자가 준 `issuetokens` 문서는 새 인증 API의 OAuth 토큰 발급이고 KISTA 용도에는 필요 없다(키 헤더 방식의 폐지 예고는 문서에 없다).

| 항목 | Alpaca(무료 등급) | 메리츠(관리자 앱키) |
|---|---|---|
| 키 | 계좌와 무관, 이미 보유 | 운영자 계좌 앱키 필요 |
| 매매 호출과의 한도 분리 | 완전 분리 | 같은 키를 매매에 쓰면 110ms 게이트 공유 |
| 일봉 캔들 | **가능.** 1회 최대 10,000봉(5년 약 1,260봉), 2016년 이후, 분할·배당 조정 지원 | 범위 상한·배당 조정이 스펙에 없음 |
| 현재가 | 실시간은 IEX 한 거래소 체결가뿐(전체 거래량의 약 2.5%). 통합 시세는 유료 | 증권사 시세 |
| 확정 종가 | **신뢰도 문제.** 일봉 close가 공식 종가(마감 경매가)와 같은지 문서로 확인되지 않는다. Alpaca FAQ에 IEX 일봉 종가와 통합 종가가 다른 예(171.29 대 171.21)가 있다 | 증권사 원장 종가. 메리츠 계좌 경로에서 이미 사용 |
| 환율(USD/KRW) | 불가(KRW 지원 미확인) | 가능 |
| 장 상태·휴장일 | 가능(이미 사용) | 휴장일 목록 없음 |
| 약관 | "Alpaca 데이터는 재배포할 수 없다"(공식 지원 문서, 2022-11). 다수 사용자에게 표시하는 것이 허용되는지는 확정하지 못했다. 지금의 벤치마크 지수 표시도 같은 문제를 안고 있다 | 해당 없음(자기 계좌 시세) |

KISTA는 LOC·MOC 주문, 마감 리포트, VR 롤오버, Mock 체결 판정에 **확정 종가**를 쓴다. 몇 센트 차이가 LOC 체결 판정과 원장을 바꾼다. 그래서 확정 종가와 주문 기준 가격은 증권사 값을 써야 하고, Alpaca로 옮길 수 있는 것은 차트·지표용 일봉 캔들이다.

### 4-3. 사용자 결정 갱신 (2026-11-12 밤) — 4-2를 대체한다

- **기본 마켓 데이터(현재가, 전일 종가, 확정 종가, 일봉 캔들, Mock 시세)는 지금처럼 토스에 둔다.** Alpaca 약관(데이터 재배포 제한) 위험을 지금 지지 않기 위해서다. Alpaca는 지금 쓰는 범위(휴장일 캘린더, 벤치마크 지수)만 유지한다.
- 환율만 Frankfurter(예비 fawazahmed0)로 옮긴다. 이 결정은 Alpaca와 무관하다(4-2절의 환율 내용은 그대로 유효).
- 따라서 토스 관리자 키는 남는다. 2-2절의 시세 포트 분리(벤더 이름 제거, `MockBrokerAdapter`가 포트 주입)는 그대로 한다 — 공급자를 나중에 바꿀 수 있게 하는 구조 정리라서다.
- 재검토 조건: 토스 관리자 키에 장애가 잦아지거나, Alpaca 유료 데이터 계약을 검토할 때.

### 4-2. 사용자 결정 (2026-11-12, 4-3으로 대체됨 — 환율 부분만 유효)

- **메리츠 관리자 키는 쓰지 않는다.** 앱키가 계좌에 묶여 공용 키로 쓸 수 없다. 위 표의 메리츠 열은 폐기한다.
- **현재가·전일 종가는 Alpaca로 옮긴다. Alpaca로 안 되는 것은 지금처럼 토스에 둔다.**
- **환율은 공개 API로 옮긴다.** 1순위 Frankfurter, 예비 fawazahmed0 Currency API.

확정 구성:

| 데이터 | 공급자 | 상태 |
|---|---|---|
| 일봉 캔들(UI 차트, DCA 지표) | Alpaca | 이전 확정 |
| 현재가, 전일 종가 | Alpaca | 이전 확정. 단 아래 "가격 품질 확인"을 통과해야 주문 경로에 쓴다 |
| 환율(USD/KRW) | Frankfurter → 예비 fawazahmed0 | 이전 확정 |
| 확정 종가(마감 리포트, VR 롤오버, Mock 체결 판정) | 토스 유지 | Alpaca 일봉 종가가 표본에서 토스 확정 종가와 전부 일치하면 옮긴다. 옮기면 토스 관리자 키가 사라진다 |
| 휴장일·세션 | Alpaca(현행) | 변경 없음 |

환율 API 확인(2026-11-12 실호출):
- `GET https://api.frankfurter.dev/v2/rate/USD/KRW` → `{"date":"2026-11-12","base":"USD","quote":"KRW","rate":1336.06}`. 키 불필요.
- `GET https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json` → `{"date":"2026-11-11","usd":{"krw":1333.52...}}`. 키 불필요.
- 둘 다 **하루 1회 갱신되는 참고 환율**이다. 토스 `exchange-rate`가 주던 것은 실시간 `midRate`와 토스의 매수 환율(`rate`)이다. 소비처 두 곳의 의미가 바뀐다: ① 벤치마크의 원화 환산(`midRate`) — 일 단위 참고 환율로 충분하다 ② 대시보드의 원화 환산 표시 — 실제 환전 금액이 아니라 참고 환산임을 화면에 표기한다(UI 문구는 `cross-repo.md`에 기록). 주문·원장 계산에는 환율이 들어가지 않는다는 것을 구현 전에 `grep -rn "ExchangeRate\|exchangeRate" trading-core/src/main`으로 다시 확인한다. 들어가는 곳이 있으면 그 경로는 옮기지 않고 보고한다.
- 구현: 응답을 하루 캐시하고 날짜를 함께 내려준다. Frankfurter 실패 시 예비 API, 둘 다 실패하면 마지막 캐시 값. 출처 라벨(`BenchmarkService.java:240`의 `"TOSS_INVEST"`)은 공급자에서 받는다.

가격 품질 확인(Alpaca 현재가·전일 종가를 주문 경로에 쓰기 전):
- 현재가는 접수 직전 가격 상한(`현재가 × 1.05`)과 관리자 시세에 쓰인다. Alpaca 무료 등급의 실시간 체결가는 IEX 한 거래소의 것이다(`findings/14`). TQQQ·SOXL처럼 거래가 많은 종목은 통합 시세와 차이가 작을 것으로 보지만 문서로 보장되지 않는다. 사용자는 Alpaca에서 현재가·전일 종가가 가능하다고 확인했다.
- 전일 종가는 보유 0일 때 첫 매수 가격의 기준이다(지침서 08 F4). snapshot의 `prevDailyBar.c`가 공식 종가와 같은지가 관건이다.
- 병행 비교 기간을 둔다: 주문에는 토스 값을 쓰면서 Alpaca 값을 함께 조회해 차이를 로그로 남긴다(10거래일). 현재가 차이가 상한 계산에 영향을 주지 않는 수준(0.1% 이내)이고 전일 종가가 전부 일치하면 전환한다. 어긋나면 그 항목은 토스에 둔다.

## 5. 재심한 과거 결정

| 결정 | 재심 결과 |
|---|---|
| S-11 "브로커 capability 포트 14개 유지" | 포트는 유지, 묶음 인터페이스와 위임 facade는 record 묶음으로 |
| S-48 broker 밖 기능은 `*Port`로 노출, `adapter/out`은 비공개 | 유지 |
| memory `project_broker_strategy` "토스는 대시보드 중심, 업비트·KIS 제거 가능" | 갱신: KIS·업비트 삭제 실행, 토스는 주문 유지 |
| memory `project_toss_redis_store_outage`, 토스 Redis 분산 토큰 | 단일 프로세스 전제에서 단순화(2-4) |
| C-02 `TossPriceApi`의 세션 계산 복제 유지 | 뒤집는다(2-2) |
| broker.md "더블체크락·전일종가 캐시·확정종가 폴백은 인라인" | 4절 1단계에서 전일종가 캐시가 사라진다. 나머지는 소비자 수로 재판정 |

## 6. 예상 감량과 위험

- KIS 약 1,764 + 업비트 약 495 + facade 약 250 + 토스 토큰 단순화 약 450 + 시세 이전 약 400(캔들까지면 약 680) + 코인 포트 정리 약 100 + 미사용 정리 약 150 = **약 3,600~3,900줄(추정), broker 모듈의 약 절반**.
- 위험 1(데이터): `accounts.broker`는 문자열 enum이다. `KIS`·`UPBIT` 행이 남은 채 상수를 지우면 그 행을 읽는 순간 예외가 난다. native 쿼리와 관리자 조회는 소프트 삭제 필터를 타지 않는다.
- 위험 2(동작): 3절 B1~B7 수정과 4절 시세 이전은 동작 변경이다. 리팩토링 커밋과 섞지 않는다.
- 위험 3(계약): `Broker` enum은 `/api/meta`로 나간다. UI의 KIS·업비트 전용 코드(KIS 약 40줄, 업비트 약 30줄, 테스트·픽스처 38파일)를 같은 배포 묶음에서 지운다.
- 위험 4(시세 의미 변화): 공급자가 바뀌면 전일 종가·현재가의 미세한 의미 차이가 주문 가격(cap, 첫 매수)에 들어간다. 실측과 병행 비교 기간을 둔다(단계 F).

## 7. 선행 조건

1. KIS 활성 전략 1건을 종료하거나 메리츠 계좌에 새로 등록한다(원장 이전 기능은 만들지 않는다).
2. KIS·업비트 계좌를 삭제한다.
3. 삭제 직전 커밋에 태그: `git tag archive/broker-kis-upbit`.
4. 운영 DB 확인(읽기 전용): `select broker, count(*) filter (where deleted_at is null) live, count(*) total from trading.accounts group by 1;` — KIS·UPBIT의 live가 0.

## 8. 태스크

공통 완료 판정: 컴파일, `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' 2>&1 | grep -E "FAILED|BUILD"`, 아키텍처 테스트(root·trading-core). 단계 끝에 전체 스위트 1회. 실계좌 통합 테스트(`*IT`)는 돌리지 않는다.

### 단계 A. KIS·업비트 삭제 (Sonnet, 판정은 Opus)

1. (Sonnet) 삭제 범위 전수표: `Kis|KIS|Upbit|UPBIT`를 main·test·resources·docs·`flows.yml`·`.github`·`deploy`·`scripts`에서 grep해 파일별로 "삭제 / 수정 / 유지(김프 지표)"로 분류. 테스트 줄 수도 센다.
2. (Opus) 전수표 검토. `TradingOrderExecutor`의 KIS 언급, `shared`의 `InternalApiClientConfig`·`UsTradeDates`의 KIS 참조, `MeritzAuthApi`의 `KisAuthApi` 언급이 주석인지 의존인지 확인. `DbTokenCoordinator`는 메리츠도 쓰므로 단계 D 전까지 남긴다.
3. (Sonnet) Flyway(trading 소유): KIS 토큰 테이블 drop. 같은 마이그레이션 앞에 "KIS·UPBIT live 계좌가 있으면 실패" 가드. 소프트 삭제된 KIS·UPBIT 계좌 행의 처리(하드 삭제 또는 보존)는 Opus가 정한다. `flyway-migration` 스킬 사용.
4. (Sonnet) 코드·테스트·문서 삭제, `Broker` 상수 2개 삭제, 관리자 시세 체인 단순화(2-1).
5. (Sonnet) `.env.example`·`application*.yml`의 KIS·업비트 키 삭제, 운영 secret에서 뺄 키를 `cross-repo.md`에 기록.
6. memory `project_broker_strategy.md` 갱신.

### 단계 B. 개장 경매 경로 정리 (Opus)

`supportsOpenAuctionOrders()` 호출처를 전부 읽고 KIS 전용 분기·주문 유형·스케줄 단계를 식별 → 메리츠·토스·Mock 경로를 고정하는 테스트 확인(없으면 추가) → 분기 삭제 → `flows.yml`·`workflow-batch.md`·`scheduler-time-table.md` 갱신.

### 단계 C. 스펙 결함 수정 (Opus, 항목별 커밋)

3절 B1 → B3 → B2 → B6 → B5 → B7 → B4 순(위험 순). 항목마다: 스펙 원문 확인 → 재현 테스트(벤더 응답 JSON을 스펙 예시로 구성) → 수정 → 변이 확인(수정을 되돌리면 테스트가 실패하는지). B1·B3은 다른 단계를 기다리지 않고 **가장 먼저** 해도 된다(삭제와 독립).

### 단계 D. 벤더 이름 제거, 토큰 단순화, 공통 골격 (Opus 설계, Sonnet 적용)

1. 2-2의 개명과 시세 포트 분리(`MockBrokerAdapter` 포함).
2. 2-4 토큰 단순화. **지침서 05의 단계 A(매매 엔진 단일 프로세스 확정) 뒤에 한다.** 그 전에는 현재 kista-trading이 단일 인스턴스이므로 전제는 이미 성립하지만, 05의 방향이 바뀌면 이 태스크를 재검토한다.
3. 2-3 공통 골격: 메리츠 → 바이낸스 → 토스 순. 기존 어댑터 테스트가 그대로 통과해야 한다. 끝나면 줄 수를 재서 골격이 실제로 줄였는지 확인하고, 아니면 원복한다.
4. 빠진 처리 반영: `Retry-After` 준수, 토스 401 코드 구분, 메리츠 `expires_in` 폴백.

### 단계 E. facade 제거와 코인 포트 정리 (Sonnet)

2-5, 2-6. 3절의 "줄일 수 있는 것" 목록도 여기서 처리한다.

### 단계 F. 환율 공급자 이전 (4-3절, Opus)

1. 주문·원장 계산에 환율이 들어가지 않는지 확인: `grep -rn "ExchangeRate\|exchangeRate" trading-core/src/main src/main`. 들어가는 곳이 있으면 그 경로는 옮기지 않고 보고한다.
2. `adapter/out/marketdata`에 환율 구현 추가: Frankfurter `GET https://api.frankfurter.dev/v2/rate/USD/KRW` → 실패 시 fawazahmed0 → 둘 다 실패하면 마지막 캐시 값. 하루 캐시, 응답에 기준 날짜를 함께 싣는다. 출처 라벨은 공급자에서 받는다(`BenchmarkService.java:240`의 `"TOSS_INVEST"` 하드코딩 제거).
3. 토스 환율 코드(`UsdKrwRateCache`, `TossExchangeRate`, `TossHoldingsApi`의 환율 부분) 삭제.
4. 대시보드 원화 환산 표시가 "일 단위 참고 환율"이 된다는 점을 `cross-repo.md`에 적어 UI 문구에 반영되게 한다.

## 9. 하지 말 것

- 선행 조건 4번 확인 없이 enum 상수를 지우지 않는다.
- 메리츠·바이낸스·토스의 주문·재시도 동작을 리팩토링 커밋에서 바꾸지 않는다(단계 C·F만 동작 변경).
- 3절의 "지우면 안 되는 방어"를 지우지 않는다.
- `stats`의 김프 지표용 업비트 공개 시세를 지우지 않는다.
- 사용자 키로 공용 시세를 부르지 않는다.
- 병행 비교를 건너뛰고 주문 경로의 가격 공급자를 바꾸지 않는다.

## 10. 다른 지침서와의 관계

- **04를 가장 먼저 실행한다**(다른 주제의 표면이 줄어든다). 단계 A·B·C는 다른 지침서와 독립이다.
- 05: 매매 엔진 단일 프로세스 전제(2-4), `calendar` 모듈(2-2).
- 01: `BrokerRouter`·매매 포트는 다형성이 있어 인터페이스 유지.
- 06: 주식·코인 실행 파이프라인 이원화.
- 09: 벤더 문서 드리프트, KIS·업비트 문서 삭제.
- `cross-repo.md`: `Broker` 메타 변경, 운영 secret 키, UI의 KIS·업비트 코드.
