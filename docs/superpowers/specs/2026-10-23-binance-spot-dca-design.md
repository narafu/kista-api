# 바이낸스 현물 코인 DCA 설계

> 상태: 설계 (2026-10-23)

## 의도

KISTA 사용자 전체가 업비트(KRW 마켓)와 같은 방식으로 바이낸스(USDT 마켓) 현물에서 BTC·ETH DCA를 돌릴 수 있게 한다. 동작은 업비트 코인 DCA와 동일하다 — 10분 틱, 다중 규칙, 원장(자기 주문 체결) 기준 보유·평단, 체결·키 알림.

이번 작업은 "두 번째 코인 거래소"를 붙이는 일이지만, 현재 코드는 코인 = 업비트 = KRW 전제가 리터럴로 흩어져 있다(api 약 20곳, kista-ui 약 25곳 — 아래 "일반화 대상"). 사용자는 비용이 크더라도 구조를 제대로 고치는 쪽을 선호한다. 그래서 **바이낸스를 붙이기 전에 행동 변경 없는 구조 개선 단계(Phase 0)를 먼저 둔다** — 그 뒤엔 바이낸스(나아가 빗썸·다른 USDT 거래소)가 "구현체 + enum 상수" 추가만으로 붙어야 한다.

## 목표

- `Broker.BINANCE` 계좌 등록(API key/secret, 연결 테스트), 바이낸스 현물 BTCUSDT·ETHUSDT DCA 등록·실행·동기화·원장 반영·알림.
- 회차 금액은 USDT로 사용자가 직접 입력(소수 2자리).
- 포트폴리오·전략 상세·이력·관리자 화면에서 USDT로 표시. 원화 계좌와 합산하지 않음.
- 코인 시장/통화/거래소 차이를 enum capability와 포트 계약으로 표현해 리터럴 분기를 없앤다.

## 비목표

- 선물·마진·크립토론(레버리지 DCA)은 이번 범위 밖 — 향후 단계 제안 참고.
- 입금(주소 표시·입금 도착 알림) — 향후 단계.
- 원화 환산 합산 포트폴리오, 김치 프리미엄 지표의 바이낸스 연동 변경.
- 업비트 동작 변경. Phase 0 전후로 업비트 경로의 관찰 가능한 동작은 같아야 한다.

## 안티 목표 (기술적으로 돌아가도 실패로 본다)

- 바이낸스 금액이 주식 USD 통계 합계에 섞이는 것(현재 `CRYPTO_KRW` 제외 방식 그대로면 `CRYPTO_USDT`가 USD 합계에 조용히 들어간다 — `TradingStatsService.java:179`, `InvestmentPointsQueryService.java:60`, `CyclePositionPersistenceAdapter.java:30-32`).
- USDT 금액이 원 단위 반올림(`setScale(0)`)으로 잘리는 것(10.5 → 10).
- 같은 회차가 두 번 매수되는 것(멱등 위반).
- 출금 권한이 켜진 키를 받는 것.
- 원장 보유량이 실제보다 많아지는 것(바이낸스 매수 수수료 차감 미반영).

## 제약

- 아키텍처: `matching`은 `shared.kernel` 외 의존 금지, broker 벤더 타입은 broker 밖 노출 금지, 프로세스 경계 wire 타입은 `shared.contract`. own-type 신설 시 `docs/agents/own-type-ledger.md` 게이트.
- enum 상수명은 DB·API 계약 — `Market.CRYPTO_KRW`·`Currency.KRW`·`Broker.UPBIT`·`StrategyTicker.BTC/ETH` 이름은 바꾸지 않는다.
- DB: 마이그레이션 불필요로 판단(계좌 `account_no` nullable·`api_key_expires_at` 이미 존재, `accounts.broker` CHECK 제약 없음 확인). 금액 numeric(20,2)·가격 numeric(12,2)·수량 numeric(20,8)은 BTC·ETH USDT 값에 충분(가격 2자리라 1달러 미만 코인은 정밀도 손실 — 향후 알트 추가 시 재검토).
- EPR: `CryptoOrderFilledEvent`는 `trading.event_publication`에 JSON으로 저장된다. 필드 이름을 바꾸면 배포 시점 미완료 row가 역직렬화에 실패한다(아래 "배포").
- kista-ui는 별도 배포. API 응답 필드 개명은 expand/contract(새 필드 추가 → UI 전환 → 옛 필드 제거).
- kista-ui 진행 중 작업과 충돌: DCA 다중 규칙 UI(미배포), 메리츠 UI(다른 세션)가 `api-schema.ts`·`dcaFormSchema.ts`·`DcaSettingsSection.tsx`를 건드린다. 바이낸스 UI는 그 뒤에 순서를 잡는다.
- 규제 리스크(사용자 인지 필요): 국내 미신고 해외 거래소에서 타인 계좌 주문을 자동 실행하는 서비스는 특금법·투자일임 이슈 여지가 있다. 이 설계는 기술 범위만 다룬다.

## 확정된 결정 (사용자)

- 현물 먼저, 레버리지는 나중(하게 되면 격리 마진, LTV ≤ 50% 제한 + 청산 근접 알림만, 자동 조치 없음).
- 회차 금액 USDT 직접 입력.
- 그 외 동작은 업비트와 동일.
- 비용이 커도 더 나은 구조로 고치는 것을 선호, 개선점은 단계와 함께 제안.

## 설계

### 1. 시장·통화·거래소 어휘 (shared.kernel) — Phase 0

현재: `Broker`와 `Market`·`Currency`가 각자 값을 들고 비교는 리터럴로 한다.

- `Market`에 capability를 둔다: `isCrypto()`, `quoteCurrency()`(US_STOCK→USD, CRYPTO_KRW→KRW, 신규 CRYPTO_USDT→USDT). `Broker.currency`는 `market.quoteCurrency()`에서 파생해 중복 제거(빌더 판단: 필드 제거 vs 위임 — 외부 노출 `BrokerMeta.currency`는 유지).
- `Currency`에 `USDT` 추가, `amountScale()`(KRW 0, USD·USDT 2)을 금액 반올림의 단일 출처로 둔다. 현재 흩어진 `setScale(0, …)`(`CryptoOrderSynchronizer.java:116`, `TradingUserNotificationAdapter.java:115,129-131`, `CryptoPortfolioService.java:68`, `DcaCycleOrderStrategy.java:127-128`)은 계좌 통화의 scale을 쓴다.
- `Broker`에 capability 플래그: 자격증명 형태(계좌번호 없이 키만 — UPBIT·MERITZ·BINANCE), 코인 거래소 여부(= `market.isCrypto()`), API 키 만료일 보유(UPBIT만). 다음 리터럴을 대체한다: `AccountService.java:57,120`·`Account.java:29`(`UPBIT || MERITZ`), `OrderCancelService.java:58,94,152`(`== UPBIT`), `ApiKeyExpiryScheduler.java:40`.
- `market == CRYPTO_KRW` 비교 전부를 `isCrypto()`로 바꾼다(목록은 "일반화 대상").

### 2. 종목 ↔ 시장 (Phase 1)

현재 `StrategyTicker`가 `Market` 하나를 final 필드로 가져 BTC·ETH가 `CRYPTO_KRW`에 고정되고, `StrategyCreationService.java:48`이 계좌 시장과 다르면 거부한다.

결정(내 판단): **BTC·ETH 종목을 두 거래소가 공유한다.** 종목은 자신이 거래될 수 있는 시장 집합(또는 자산군 CRYPTO)을 갖고, 실제 시장은 항상 **계좌의 브로커**에서 정한다. `BTC_USDT` 같은 종목 신설은 DB 값·메타·티커 레지스트리를 중복시키므로 택하지 않는다.

- 검증: `ticker.tradableOn(account.broker().getMarket())`.
- `ticker.getMarket()`를 "그 전략의 시장"으로 쓰는 곳은 계좌 시장으로 바꾼다: `DcaPlanInputCollector.java:116`, `StrategyService.java:239,321,345`, `TradingPreviewService.java:58,85`, `StrategyCreationService.java:83,137`, `ManualTradeCorrectionService.java:130`, `StrategyDetail.java:22`(compact 생성자에 계좌가 없으므로 시장을 인자로 넘기도록 변경), `CryptoPortfolioService.java:49`, `CryptoDcaRoundExecutor.java:120`(현재 `Market.CRYPTO_KRW` 하드코딩). `AccountStatisticsService.java:74`·`ReorderService.java:65`는 주문의 종목 시장이 US_STOCK인지로 판정하는데, 주식 종목은 시장이 하나라 그대로 둬도 되지만 같은 원칙으로 통일할지 빌더가 판단.
- 메타 계약: `TickerMeta.market`(`src/main/java/com/kista/api/web/dto/TickerMeta.java:20`)는 kista-ui 계약이다. `markets`(배열)를 추가하고 `market`은 한 릴리스 유지 후 제거(expand/contract).
- 계좌당 종목 1개 규칙(`validateUniqueTicker`)은 계좌 단위라 업비트 BTC와 바이낸스 BTC가 공존 가능 — 변경 없음.

### 3. 거래 규칙을 커널 입력으로 (Phase 0)

현재 `DcaRule.CRYPTO_MIN_AMOUNT = 5000`, `CRYPTO_FEE_FACTOR = 1.0005`가 matching 커널 상수이고 원 정수 규칙이 `requireCompatible`에 박혀 있다.

- 등록 시점 정적 규칙(계좌 시장만 알고 거래소 호출 불가): 시장별 최소 주문 금액·금액 소수 자릿수를 `shared.kernel`의 시장 capability로 둔다(CRYPTO_KRW 5,000원·0자리, CRYPTO_USDT 5 USDT·2자리 — 바이낸스 5 USDT는 실측 `NOTIONAL` 값). `requireCompatible(market)`의 메시지도 통화에 맞춘다.
- 실행 시점 동적 규칙(종목별): 수수료율, 수수료가 차감되는 쪽(견적 통화에서 추가 차감 = 업비트 / 받은 자산에서 차감 = 바이낸스), 최소 명목가, 수량 step. 거래소 포트가 공급하고 실행기가 `DcaRuleInputs`로 커널에 넘긴다. broker 쪽 타입과 커널 쪽 타입이 둘 다 생기면 own-type이므로 ledger 게이트를 거친다(커널이 정의하고 trading이 broker 값에서 매핑하는 방식이 기본안).
- `CryptoDcaRoundExecutor.java:210`의 지갑 차감 `amount × FEE_FACTOR`도 이 규칙을 따른다(바이낸스는 견적 통화 추가 차감 없음).

### 4. 거래소 포트 정리 (Phase 0)

- `CryptoExchangePort`에서 김치 프리미엄용 `getUsdtKrwPrice()`·`getCompletedUsdtKrwDailyCandles()`를 분리해 별도 참조 시세 포트로 옮긴다(소비자 `CryptoMarketIndicatorService`만, 구현 업비트). 그러지 않으면 바이낸스 구현이 예외를 던지는 메서드를 갖게 된다.
- 원화 이름을 견적 통화 중립으로: `buyMarketByAmount(…, krwAmount, …)` → quote 금액, `CryptoBalance.krwAvailable/krwLocked` → quote 가용·잠금, `CryptoOrderResult` 주석·`CryptoPortfolio` 필드.
- 주문 조회에 종목을 넘긴다: 바이낸스 `GET /api/v3/order`는 `symbol`이 필수라 현재 시그니처 `findOrderByIdentifier(account, identifier)`·`findOrderByUuid(account, uuid)`로는 구현 불가. `Order.ticker()`가 있으므로 동기화기에서 전달.
- 일봉 신선도("KST 09:00 마감 → 09시 이후 오늘−1, 이전 오늘−2", `CryptoDcaRoundExecutor.java:172-174`)를 실행기에서 포트 계약으로 옮긴다. 포트는 "완성된 최신 봉의 KST 일자" 계약을 지키고, 실행기는 기대 일자를 포트에서 받거나 계약 문서화된 규칙을 쓴다. 바이낸스 1d 봉은 UTC 00:00 시작 = KST 09:00이라 업비트와 경계가 같다(구현 시 `openTime`으로 확인).
- 체결 결과에 순수량을 싣는다: `CryptoOrderResult`가 원장에 넣을 순 체결 수량(수수료가 받은 자산에서 빠졌다면 차감 후)과 수수료(통화 포함)를 표현한다. 업비트는 순수량 = 체결 수량.

### 5. 바이낸스 어댑터 (Phase 2)

위치: `com.kista.tradingcore.broker.adapter.out.binance`(벤더 모델은 `broker.domain.model.binance`, internal). 기존 `stats.adapter.out.binance.BinanceMarketDataAdapter`(공개 시세)는 stats 소유라 재사용하지 않고 별도 둔다(빌더가 RestClient 설정 공유 여부 판단 — 모듈 경계상 broker가 stats를 참조할 수 없다).

- 서명: HMAC-SHA256(query+body), `X-MBX-APIKEY`, `timestamp`·`recvWindow`. 서버 시각 차이 오류(-1021) 처리.
- 키 유형(내 판단): **바이낸스 발급 HMAC 키만** 받는다. Ed25519·RSA 자가 생성 키는 서명 방식·입력 형태·UI 안내가 달라지고, RSA PEM(약 1.7KB)은 암호화 후 `secret_key` VARCHAR(512)를 넘어 마이그레이션이 필요해진다. Ed25519(PKCS8 PEM 약 120자)는 512에 들어가므로 향후 추가 후보.
- 매수: `POST /api/v3/order` `MARKET` + `quoteOrderQty`(문서: 매수 시 쓸 견적 통화 금액, LOT_SIZE를 깨지 않음) + `newClientOrderId` = 주문 UUID(36자, 허용 문자 범위는 구현 시 확인) + `newOrderRespType=FULL`(`fills[].commission/commissionAsset`로 순수량 계산).
- 매도: `MARKET` + `quantity`(LOT_SIZE stepSize로 FLOOR).
- 조회: `GET /api/v3/order`(`symbol` + `orderId` 또는 `origClientOrderId`). 미존재 응답(-2013)은 `Optional.empty()`, 그 외 오류는 예외(동기화기가 TRANSIENT로 다음 틱 재시도). 조회 응답엔 수수료가 없으므로, 복구 경로에서 순수량이 필요하면 `GET /api/v3/myTrades?symbol=&orderId=`로 보충.
- 상태 매핑: `NEW`·`PARTIALLY_FILLED`·`PENDING_NEW` → 대기, `FILLED` → 완료, `CANCELED`·`EXPIRED`·`EXPIRED_IN_MATCH`·`REJECTED` → 취소/종료. 체결 판정은 기존처럼 상태 무관 `executedQty > 0`. 전체 상태 목록은 구현 시 enums 문서로 확인.
- 잔고: `GET /api/v3/account`의 `balances[]` — USDT free/locked, BTC·ETH free/locked. 평단은 없음 → 포트폴리오 평단은 원장 값(아래 7).
- 시세·일봉: `GET /api/v3/ticker/price`, `GET /api/v3/klines?interval=1d`(1000봉 한도, 250봉이면 1회).
- 거래 규칙: `GET /api/v3/exchangeInfo?symbols=` 필터(`LOT_SIZE`, `NOTIONAL`/`MIN_NOTIONAL`), 프로세스 내 캐시(TTL은 빌더 판단).
- 오류 매핑: 벤더 예외 `BinanceApiException extends BrokerApiException` + `isRejection` 판정(4xx·명시 거부 코드 = 확정 거절, 타임아웃·5xx = 결과 불명). 키 무효(-2014/-2015) → `BrokerCredentialException`, 레이트리밋 429/418 → `BrokerRateLimitException`, IP 미허용 → `BrokerIpNotAllowedException`.
- 레이트리밋: 10분 틱·계좌당 수 건이라 여유. `X-MBX-USED-WEIGHT-1M` 로깅만.

### 6. 멱등·이중 주문 방지

현재 흐름(확인함): 실행기가 PLANNED → SUBMITTING 선점 후 접수, 결과 불명(TRANSIENT)이면 SUBMITTING 유지(`CryptoDcaRoundExecutor.java:199-203`). 다음 틱 동기화기가 identifier로 조회해 있으면 PLACED, **없으면 FAILED이며 재전송하지 않는다**(`CryptoOrderSynchronizer.java:69-75`). 같은 실행 시간대면 실행기가 새 주문 UUID로 다시 판정한다.

바이낸스 차이: `newClientOrderId`는 "열린 주문 중 고유"이고 "이전 주문이 체결되면 같은 ID 재접수 허용"이다(공식 문서). 업비트 identifier처럼 영구 고유가 아니므로, 같은 ID로 재전송하면 시장가 주문이 두 번 체결될 수 있다. 규칙:

- 어떤 경로도 같은 주문 UUID로 바이낸스에 재전송하지 않는다(현재 코드가 이미 그렇다 — 이 불변식을 바이낸스 어댑터·동기화기 테스트로 잠근다).
- "조회 결과 없음"은 -2013 확정 응답일 때만 empty로 본다. 네트워크 오류·5xx를 empty로 바꾸면 FAILED → 같은 시간대 재판정 → 이중 매수가 된다.

### 7. 원장·포트폴리오·알림 (Phase 2~3)

- 원장: `CryptoLedgerRecorder.recordFill`은 순 체결 수량을 쓴다. 바이낸스에서 수수료가 BNB로 빠지면 순수량 = 체결 수량, 받은 자산(BTC)으로 빠지면 차감.
- 평단 원가 기준(내 판단 — 업비트와 일관): **수수료는 원가에 넣지 않는다.** 업비트 원장은 원가를 `체결수량 × 평균가`로 잡고 `paidFee`를 반영하지 않는다. 바이낸스도 원가 = `순수량 × 체결 평균가`(fills 가중 평균)로 둔다 — `cummulativeQuoteQty`를 그대로 원가로 쓰면 BTC로 낸 수수료가 평단에 암묵적으로 섞여 업비트와 기준이 달라진다. BNB 수수료도 원가에 넣지 않는다. 평단은 `AVG_PRICE` 매매 판정 입력이라 두 거래소 기준이 같아야 한다.
- 포트폴리오: 바이낸스 평단은 원장 값. `CryptoPortfolioResponse`에 통화 중립 필드(현금·평가금액·총자산) + `currency`를 추가하고, `krwCash`·`evalAmountKrw`·`totalAssetKrw`는 UI 전환 후 제거.
- 알림: `TradingUserNotificationAdapter`의 "…원" 문구·`krw()` 포맷을 계좌 통화 포맷으로, 키 만료 문구의 "업비트"를 브로커 라벨로. 바이낸스 키는 만료일이 없어 만료 알림 대상 아님(capability 플래그).
- SSE `TradeEventMessage`는 통화가 없다 — 소비자(kista-ui) 확인 후 통화 필드 추가 여부 결정(빌더).

### 8. 계좌 등록·연결 테스트 (Phase 2)

- 메리츠 추가 체크리스트를 따른다: `Broker` 상수, `TradingPolicySettings` 기본값(비활성 + 허용 IP), 키 전용 분기(capability), `dedupeKeyOf`, `AccountRequest` allowableValues, `BrokerConnectionTestPort` 구현(`supports()`), 아키텍처 테스트 allowlist, flows.yml, README·docs.
- 연결 테스트 `GET /sapi/v1/account/apiRestrictions`: `enableSpotAndMarginTrading=true` 필수, `enableWithdrawals=true`면 거부, `ipRestrict=false`면 거부(업비트처럼 허용 IP 안내 — 서버 IP는 OCI 춘천). 만료일은 null.
- 서버 IP 접근: 2026-10-23 `ssh kista-server`에서 `GET /api/v3/ping` 200 확인(지역 차단 없음). stats 모듈의 공개 시세 호출도 이미 운영에서 돈다.
- 공개 범위: 사용자 전체(사용자 결정). 노출 제어는 기존 브로커 정책 on/off(기본 비활성)로 한다 — 사용자별 화이트리스트는 두지 않는다.

### 9. 통계 (Phase 0 선행 — 정확성 전제)

`CRYPTO_KRW`를 빼는 방식의 집계를 "통화별 집계" 또는 `isCrypto()` 제외로 바꾼다. 현재 업비트 동작(코인 제외)을 유지하는 것이 기본안. 대상: `TradingStatsService.java:179`, `InvestmentPointsQueryService.java:60`, `CyclePositionPersistenceAdapter.java:30-32`, `CryptoMarketIndicatorService.java:61`.

### 10. kista-ui (Phase 3, 다중 규칙·메리츠 UI 뒤)

착수 전 `../kista-ui/CLAUDE.md` Read.

- `'CRYPTO_KRW'` 리터럴 분기 → `isCryptoMarket`/메타 기반(`api-schema.ts:65-104`, `dcaFormSchema.ts`, `buildDcaPayload.ts:23`, `StrategyForm.tsx:98`, `DcaRuleEditor.tsx:49`, `DcaRuleList.tsx:36`, `DcaConditionsSection.tsx:50,65`, `useStrategyForm*`).
- 통화 포맷: `fmtMoney`에 USDT(`format/index.ts:98,109`), 계좌 통화로 포맷(`dcaFormat.ts:72`, `DcaStrategyDetail.tsx:39,52` — `Math.round` 제거, `DcaExecutionHistory.tsx:25`, `CycleRows.tsx:24-27`, `AdminTradesTable.tsx:57`, `AccountSummaryCard.tsx:54-59`, `CryptoHoldingsCard.tsx:49-51`).
- DCA 폼: 금액 라벨·단위·최소 금액·소수 자릿수를 시장 메타에서(하드코딩 `MIN_CRYPTO_ORDER_KRW` 제거), "업비트 일봉" 문구 → 거래소 중립.
- 계좌 등록: 바이낸스 브로커 카드, 키 입력 안내(필요 권한·출금 금지·IP 제한·허용 IP), `brokerCredentials` 문구.
- `gen:types` 재생성.

## 단계

| 단계 | 내용 | 시점 |
|---|---|---|
| 0 | 구조 개선(행동 변경 없음, 업비트 테스트 그대로 통과): 1·3·4·9 | 선행 |
| 1 | 종목 ↔ 다중 시장, 시장은 계좌에서(2) + `TickerMeta.markets` 추가 | 선행 |
| 2 | 바이낸스 어댑터·연결 테스트·계좌 등록·정책 기본값·원장 순수량·알림(5~8) | 본 작업 |
| 3 | API 계약 expand(포트폴리오 중립 필드·currency) → kista-ui(10) → 옛 필드 contract | 본 작업(UI는 순서 조정) |

Phase 0·1은 업비트만 있는 상태에서 배포해도 무해해야 한다.

## 배포

- `CryptoOrderFilledEvent.amountKrw` 개명은 `@JsonAlias("amountKrw")`를 한 릴리스 유지하거나, kista-trading 교체 직전 `docker-infra.md` "배포 직전 EPR 정리 런북"으로 미완료 row를 확인·정리한다. 이벤트 클래스·리스너 패키지는 옮기지 않는다.
- API 계약 필드(`TickerMeta.market`, `CryptoPortfolioResponse.krw*`)는 새 필드 추가 → UI 배포 → 다음 릴리스 제거.
- `Broker.BINANCE` 정책 기본값 비활성 — 배포 후 관리자가 켠다.

## 빌더에게 맡기는 것

- 각 capability의 정확한 이름·위치(`Market`/`Currency`/`Broker` 메서드 vs 별도 record), `Broker.currency` 필드 제거 여부.
- 거래 규칙 타입의 소유(커널 정의 + 매핑)와 own-type 게이트 판정 기록.
- exchangeInfo 캐시 TTL, RestClient 설정, 서명 유틸 구조.
- 일봉 신선도 규칙을 포트 계약으로 옮기는 구체 형태.
- `TradeEventMessage` 통화 필드 추가 여부.

## 구현 시 문서로 확인할 바이낸스 사실

공식 문서로 확인함: `quoteOrderQty` 의미, `newClientOrderId` 고유 범위(열린 주문·체결 후 재사용 허용), `apiRestrictions` 응답 필드(`enableWithdrawals`는 IP 제한이 있어야 켤 수 있음).

운영 서버에서 실측(2026-10-23): `ping` 200. `exchangeInfo` — BTCUSDT `LOT_SIZE` stepSize 0.00001(minQty 0.00001), ETHUSDT stepSize 0.0001(minQty 0.0001), 둘 다 `NOTIONAL` minNotional 5 USDT, `PRICE_FILTER` tickSize 0.01. 1d kline `openTime` 1792713600000 = UTC 00:00 = KST 09:00(업비트 경계와 같음).

구현 시 확인: `FULL` 응답 `fills` 필드, `GET /api/v3/order` 미존재 코드(-2013)·보존 기간, 주문 상태 전체 목록, `newClientOrderId` 길이·문자 규칙.

## 향후 제안 (이번 범위 밖)

- **입금 주소 표시 + 입금 도착 알림**(`/sapi/v1/capital/deposit/address`, `deposit/hisrec`) — 작음, 바이낸스 안정화 후.
- **격리 마진 레버리지 DCA**(LTV ≤ 50%, 청산 근접 알림만) — 현물 어댑터(서명·호스트 공유) 위에 `sideEffectType=AUTO_BORROW_REPAY` 추가. 별도 설계.
- **가격 컬럼 정밀도**: `orders.price`·`filled_price`·`cycle_position.closing_price`·`dca_execution.reference_price`가 numeric(12,2) — 1달러 미만 코인 추가 전 확장 필요. `cycle_position.avg_price` numeric(20,2)는 계산(4자리)보다 정밀도가 낮다.
- **주문 행에 통화 없음**: 현재 계좌 브로커에서 파생 — 계좌 브로커가 바뀌지 않는 한 무해, 유지.
- **`CryptoMarketIndicatorService` 생성자에서 업비트 포트 조회**: 참조 시세 포트 분리(4)로 함께 정리.
