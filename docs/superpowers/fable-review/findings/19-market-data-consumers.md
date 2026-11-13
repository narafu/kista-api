# 19. 공용 시장 데이터 소비처 측정 (Twelve Data/Alpaca 이전 대비)

측정일 2026-11-14. 읽기 전용(코드 + 운영 DB SELECT 1회). 이미 적힌 내용은 `findings/14-alpaca-market-data.md`(이하 "14번")·`guides/04-broker.md` 4절을 가리킨다. "추정"은 코드·DB로 확인하지 못한 값.
운영 DB 실측(`trading.strategy` join `trading.accounts`, 삭제 안 된 행): 활성 전략의 주식 종목은 **SOXL·TQQQ·USD·QLD 4개**, 브로커는 MERITZ·KIS·MOCK(토스 계좌 0건), 코인은 ETH(BINANCE) 1건. `StrategyTicker` enum은 8개(주식 6: MAGX·USD·TQQQ·SOXL·UPRO·QLD, 코인 2: BTC·ETH — `shared/.../StrategyTicker.java:15-22`).

## 1. 시세 포트·어댑터 전수

경로 약칭: `TC` = `trading-core/src/main/java/com/kista/tradingcore`, `API` = `src/main/java/com/kista/api`.

### 1-1. 계좌와 무관한 시장 데이터 (이전 대상)

| 포트 메서드 | 구현 (file:line) | 외부 엔드포인트 | 캐시 |
|---|---|---|---|
| `AdminMarketPricePort.getPrices/getPrevCloses/getClosingPrices` (`TC/broker/application/port/output/AdminMarketPricePort.java:13-19`) | `AdminMarketPriceAdapter` (`TC/broker/adapter/out/marketdata/AdminMarketPriceAdapter.java:18`) — `@Order` 순으로 Toss → KIS 관리자 소스를 돌며 누락 종목만 다음 소스로 | (아래 소스별) | 없음(소스별) |
| 위 포트의 Toss 소스 `TossAdminPriceSource` (`TC/broker/adapter/out/toss/TossAdminPriceSource.java:20`) → `TossPriceApi` | **현재가**: `GET /api/v1/prices?symbols=A,B,..` 1회 묶음(`TossPriceApi.java:40,65`, 관리자 토큰). **전일 종가**: 종목당 `GET /api/v1/candles?interval=1d&count=1&before=` (`fetchPrevCloseCached` `:152`). **확정 종가**: 종목당 `candles` `from=to=US 세션일` (`fetchConfirmedCandle` `:217`) | 현재가 **캐시 없음**. 전일 종가: JVM `ConcurrentHashMap`, 키=(종목, KST 날짜, ACTIVE/CLOSED 버킷), 만료 없음(`:47-48`), 실패(empty)도 캐싱. 확정 종가 캐시 없음 |
| 위 포트의 KIS 소스 `KisAdminPriceSource` (`TC/broker/adapter/out/kis/KisAdminPriceSource.java:23`) → `KisPriceApi` | KIS 해외시세(관리자 앱키 `KIS_ADMIN_APP_KEY` 설정 시만 활성, 미설정이면 건너뜀 `:25-26`) | 토큰만 Redis `kis:admin:token:<해시8>`(`KisAdminTokenCoordinator.java:26`) |
| `BrokerPricePort` 8개 메서드 (`TC/broker/application/port/output/BrokerPricePort.java:16-25`) — 소비처가 부르는 실제 진입점 | `BrokerRouter` (`TC/broker/application/service/BrokerRouter.java:81-122`): 관리자 체인 우선 → 누락분만 계좌 어댑터(`of(account)`) | 계좌 어댑터는 Toss(`TossBrokerAdapter.java:94-133`, 위와 같은 `TossPriceApi`)·KIS·Meritz(`MeritzPriceApi` 130줄)·Mock(`MockBrokerAdapter.java:58-97`, `TossPriceApi` 직접 주입 `:29`) | 없음 |
| `CandlePort.getCandles/getLatestCandles/getDailyCandlesBefore` (`TC/broker/application/port/output/CandlePort.java:10-13`) | `TossBrokerAdapter.java:72-83` → `TossCandleApi` (`TC/broker/adapter/out/toss/TossCandleApi.java:50,75,91`) | `GET /api/v1/candles` 200봉/요청 페이징. 최신 일봉 600봉=3페이지(`:36`), DCA용은 `adjusted=true` 250봉=2페이지 | `getLatestCandles` 일봉만 종목별 600봉을 `TtlResultCache` **2분**(`:40`, JVM 메모리, 600봉 다 찬 결과만). 범위 조회·`getDailyCandlesBefore`는 캐시 없음(DCA는 호출측 `DcaPlanInputCollector.closeCache`가 세션당 1회로 묶음) |
| `ExchangeRatePort.getExchangeRate()` (`TC/broker/application/port/output/ExchangeRatePort.java:7`) | `TossBrokerAdapter.java:87` → `TossHoldingsApi.getExchangeRate` (`TossHoldingsApi.java:144`) | `GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW` (`:38`, 관리자 토큰) | `UsdKrwRateCache` 60초 TTL, JVM 메모리, 성공 값만(`TossHoldingsApi.java:46`, `UsdKrwRateCache.java`) |
| `CurrentExchangeRatePort.getMidRate` (root `API/benchmark/application/port/output/CurrentExchangeRatePort.java`) | `CurrentExchangeRateHttpAdapter` (`API/benchmark/adapter/out/internal/CurrentExchangeRateHttpAdapter.java:14`) → trading-core `GET /api/internal/trading/stats/exchange-rate` (`TC/stats/adapter/in/web/TradingStatsInternalController.java:41`) | 위 Toss 환율 재사용 | root 측 캐시 없음(trading-core 60초) |
| (root→trading-core 캔들 중계) `CandleQueryPort` (`API/market/application/port/output/CandleQueryPort.java`) | `CandleQueryHttpAdapter` (`API/market/adapter/out/internal/CandleQueryHttpAdapter.java:25`) → `CandleInternalController` (`TC/broker/adapter/in/web/CandleInternalController.java:20,26,34`) → `CandlePort` | 위 Toss candles | 위 2분 캐시 |
| `HistoricalCandlePort.fetchDailyCandles` (`TC/stats/application/port/output/HistoricalCandlePort.java:10`) | `AlpacaCandleAdapter` (`TC/stats/adapter/out/alpaca/AlpacaCandleAdapter.java:21`, 78줄) | `GET data.alpaca.markets/v2/stocks/{sym}/bars` 1Day, split, feed=sip, 10000봉 — 14번 1절 | 없음 |
| `IndexPriceFeedPort` (`API/benchmark/application/port/output/IndexPriceFeedPort.java`) | `AlpacaIndexPriceAdapter` (`API/benchmark/adapter/out/alpaca/AlpacaIndexPriceAdapter.java:21`) | bars, feed=iex — 14번 1절 | 결과를 DB 저장(`MarketIndexPriceSyncScheduler` 매일 09:00 KST) |
| `HistoricalFxRatePort.getUsdKrwRates` (`TC/stats/application/port/output/HistoricalFxRatePort.java:11`) | `FrankfurterFxRateAdapter` (`TC/stats/adapter/out/frankfurter/FrankfurterFxRateAdapter.java:16`) `GET api.frankfurter.dev/v1/{from}..{to}?base=USD&symbols=KRW` | 이미 토스가 아닌 공개 API. **소비처는 코인 지표 `CryptoMarketIndicatorService`(`:60,116`)뿐 → 삭제 예정** | — |
| `MarketCalendarPort` (`TC/marketcalendar/application/port/output/MarketCalendarPort.java:10-26`) / `MarketCalendarRefreshPort` | 읽기=`MarketCalendarPersistenceAdapter`(DB), 갱신=`AlpacaCalendarAdapter` (`TC/marketcalendar/adapter/out/alpaca/AlpacaCalendarAdapter.java:25`, `GET /v2/calendar`) | Alpaca(이미 사용) | DB 저장, 연 1회·월 1회 갱신 |

호가·분봉 전용 포트는 없다. `CandlePort.getCandles`는 interval 인자를 받지만 실제 소비처는 일봉뿐이다(2절).

### 1-2. 공포탐욕지수 (시세 아님, 외부 호출)
3절에서 다룬다.

### 1-3. 크립토 시세 (삭제 예정으로만 표시)
`CryptoMarketDataPort` 계열: `BinanceMarketDataPort`·`UpbitMarketDataPort`·`CoinbaseMarketDataPort` (`TC/stats/application/port/output/`)와 어댑터 3개(`TC/stats/adapter/out/{binance,upbit,coinbase}/`), `CryptoMarketMetricsPort`, `CryptoMarketIndicatorService` (216줄), `CryptoExchanges.getCurrentPrices`(`AccountLedgerTotalsService.java:55`의 코인 분기), `CryptoFearGreedIndexPort`/`CryptoFearGreedPort` — 전부 **삭제 예정**.

## 2. 소비처 전수와 호출 빈도

### 2-1. 소비처 표 (포트 메서드 → 호출 지점)

| 데이터 | 소비처 (file:line) | 경로 유형 | 호출 단위 |
|---|---|---|---|
| 현재가 | `TradingPriceFetcher.loadPriceContext` `:54` → `TradingService.java:118`(마감), `:346`(개장); `reportRebuilt` `:195`(정지 전략·재개) | 배치 | 종목 묶음 1콜(`/prices`) |
| 현재가 | `TradingPriceFetcher.reloadPlacementPrices` `:90` → `TradingService.java:264`(마감 접수 직전), `:418`(개장 접수 직전) | 배치 | 묶음 1콜 |
| 현재가 | `ManualTradingService.java:145`(수동 실행 cap) | HTTP 사용자 | 1콜 |
| 현재가 | `VrReconfigureService.java:99` | HTTP 사용자 | 1콜 |
| 현재가 | `AccountLedgerTotalsService.java:56` → `AccountLedgerTotalsController`(`GET` 원장 합산, 보유 종목만, 보유 0이면 호출 없음) | HTTP 사용자 | 묶음 1콜, **캐시 없음** |
| 현재가 | `MockBrokerAdapter.getPresentBalance` `:207`(보유 종목마다 단건) → `AccountStatisticsService.getPresentBalance :45` → `StatisticsController.java:39` | HTTP 사용자(MOCK 계좌) | 종목당 1콜, 캐시 없음 |
| 현재가 | `MockBrokerAdapter.getExecutions` `:140-141`(확정 봉 없을 때 폴백) | 마감 리포트 | 드묾 |
| 전일 종가 | `loadPriceContext` `:62-67`(현재가 실패 종목 제외, 묶음→단건 폴백) | 배치 | 종목당 캔들 1콜(전일 종가 캐시 있음) |
| 전일 종가 | `TradingPreviewService.java:133`(미리보기), `StrategyOrderPlanBuilder.java:72`, `InfiniteMinSeedGuard.java:31`, `StrategyCreationService.java:159`, `StrategyHistoryQueryService.java:78`, `AccountStatisticsService.getPrices :96`(→ `StatisticsController.java:88`, tickers 1~10개) | HTTP 사용자 | 종목당 캔들 1콜, 위 JVM 캐시(하루 최대 종목×2버킷) |
| 확정 종가 | `TradingService.java:157`(마감 배치, 마감+10분), `:196`(정지 전략·재개 리포트) | 배치 | 종목당 캔들 1콜 |
| 확정 종가 | `VrCycleRolloverService.java:83` (due일 평가 종가, 롤오버 판정일만) | 배치 | 단건 |
| 확정 종가 OHLC | `MockBrokerAdapter.getExecutions` `:140` (`fetchConfirmedCandle`, MOCK 전략마다) | 마감 리포트 | 전략당 캔들 1콜, 캐시 없음 |
| 일봉 250봉(조정) | `DcaPlanInputCollector.java:142`(DCA SMA·RSI·평단 조건) | 배치(개장·마감 계획 단계) | 종목당 2페이지, 세션당 1회(`closeCache :`) |
| 일봉 600봉/범위 | `MarketHolidayController.java:65` `GET /api/market/candles`(UI 차트) → `MarketHolidayService.java:29,39` → 내부 HTTP → `CandlePort` | HTTP 사용자(공개 엔드포인트) | 최신 600봉 종목당 3콜(2분 캐시), 범위 조회는 최대 5년≈7콜 캐시 없음(`MarketHolidayService.java:36` 주석) |
| 일봉 과거 | `BacktestService.java:166`(Alpaca) | HTTP 사용자 | 요청당 1콜 |
| 환율 | `BenchmarkService.fetchCurrentExchangeRate :236`(주택 벤치마크 비교 응답의 원화 환산, 출처 라벨 `"TOSS_INVEST"` `:240`) | HTTP 사용자 | 요청당 root→trading-core 1콜(Toss 60초 캐시) |
| 환율 | `TossHoldingsApi.java:64,91`(Toss 계좌 잔고 조회의 USD→KRW) | HTTP 사용자(토스 계좌, **운영 0건**) | — |

환율의 소비처는 위 둘뿐이다. 주문·원장 계산에는 환율이 들어가지 않는다(`ExchangeRatePort` 호출처 grep: `BrokerStatisticsService.java:23` 하나). 14번/04 가이드의 "대시보드 원화 환산" 소비처는 코드에서 찾지 못했고, 확인된 것은 벤치마크 비교 응답뿐이다.

### 2-2. 하루 호출 수 추정 (현재 구조, 종목 4개, 평일 거래일 1일)

전제: 배치는 종목 단위 중복 제거 후 4개 종목, 현재가는 묶음 1콜, 캔들은 종목당 1콜. Toss 호출 수 기준(공급자별 과금 단위는 아래).

| 단계 | 계산 | Toss 호출 |
|---|---|---|
| 개장 배치 22:00 `loadPriceContext` | 현재가 1 + 전일 종가 4 | 5 |
| DCA 일봉(QLD DCA, 세션당 1회) | 2페이지 | 2 |
| 개장 접수 직전 `reloadPlacementPrices` | 현재가 1 | 1 |
| 마감 배치 시작(마감 30분 전) `loadPriceContext` | 현재가 1 + 전일 종가 4(버킷이 ACTIVE라 개장 배치 캐시와 다른 키) | 5 |
| 마감 접수 직전 `reloadPlacementPrices` | 1 | 1 |
| 마감+10분 확정 종가 | 종목당 캔들 | 4 |
| MOCK 체결 판정 `getExecutions` (MOCK 전략 4개, 종목 4개) | 전략당 확정 캔들 1콜, 캐시 없음 | 4 |
| **배치 합계** | | **약 22콜/일** |

사용자 요청 경로(추정, 트래픽은 모름): 원장 합산·MOCK 잔고는 요청마다 현재가 묶음 1콜(캐시 없음) → 상한 없음. 전일 종가 경로는 JVM 캐시로 하루 최대 8콜. UI 차트 최신 600봉은 종목당 2분 캐시라 이론 상한 3콜/2분/종목(보는 사람이 있을 때만).
→ **지금 구조 그대로 옮기면**: 배치만으로 약 22콜/일(Twelve Data 크레딧은 종목당 1크레딧이라 추정 약 35크레딧/일 — 현재가 묶음 3회×4 + 캔들 16 + DCA 1~2, 800 한도 대비 매우 작음). 한도를 건드리는 것은 일일량이 아니라 **분당 한도**다.
- 분당 최대: 마감 배치 시작 직후 `loadPriceContext`(현재가 4크레딧 + 전일 종가 4) + DCA 일봉 + 접수 직전 현재가 4 ≈ **13크레딧/분**(추정, 계획 단계가 수 초이므로 한 분 안에 몰림). 확정 종가 4 + MOCK 4 ≈ **8크레딧/분**. 8/분 한도(Twelve Data)면 마감 시작 구간이 초과하므로 호출 사이 대기가 필요하다. Alpaca 무료는 분당 200이라 해당 없음.

### 2-3. "1시간 1회 배치 저장 + 개장·마감 배치만 실시간" 시 (추정)

| 항목 | 계산 | 크레딧/일 |
|---|---|---|
| 1시간 배치(24회): 현재가 4 + 전일 종가(일봉 1봉) 4 | 24×8 | 192 |
| 개장 배치 실시간: 현재가 4(시작) + 4(접수 직전) | | 8 |
| 마감 배치 실시간: 현재가 4 + 4 | | 8 |
| 마감 확정 종가 4 + MOCK OHLC(저장 재사용 시 0) | | 4~8 |
| DCA 일봉 250봉(종목당 1회) | | 1 |
| **합계** | | **약 215~220/일** (800의 약 27%) |

분당 최대: 1시간 배치 한 번이 8크레딧(= 한도)이라 2분에 나눠 호출해야 하고, 개장·마감 실시간 구간은 위처럼 8~13/분이라 **같은 순서로 나눠 호출(호출 간 대기)** 해야 한다. 전일 종가는 하루에 한 번만 바뀌므로 매시 갱신은 낭비다(종목 4개 기준 시간당 4크레딧). 평일 장 시간대(KST 17:00~06:00)에만 배치를 돌리면 합계는 약 절반이다. 사용자 요청 경로(원장 합산·MOCK 잔고·수동 실행·VR 재설정)는 저장값을 읽게 바꾸면 외부 호출이 0이 되지만, 그 값이 최대 1시간 묵는다.

## 3. 직접 계산·외부 수신 지표

| 지표 | 계산/수신 위치 | 입력 | 소비처 | 줄 수 |
|---|---|---|---|---|
| SMA(n) | `DcaIndicators.sma` (`TC/matching/domain/model/DcaIndicators.java:15-20`) | 확정 종가 250봉(일봉, 분할·배당 조정 `adjusted=true`, 조회 US 세션 이전까지, `DcaPlanInputCollector.CANDLE_COUNT=250 :52`) | `DcaConditions.java:60` DCA 조건(규칙별 `smaPeriod`, 최대 200) | `DcaIndicators.java` 43줄 전체 |
| RSI(14) Wilder | `DcaIndicators.rsiWilder` (`:24-41`, `RSI_PERIOD=14 :11`) | 같은 250봉 | `DcaConditions.java:62` DCA 조건 | 〃(위 43줄에 포함), `DcaConditions` 113줄 |
| 평단 대비 조건(AVG_PRICE) | `DcaConditions.evaluateAvgPrice :71` | 전일 종가(= 250봉의 마지막 봉) + 원장 평단 | DCA 조건 | |
| 이동평균·차트 | **서버 계산 없음** — `GET /api/market/candles`가 일봉을 그대로 내려주고 UI가 계산(14번 3절: UI 600봉, MA200 계산용 400봉 전체) | 일봉 600봉 | UI 차트 | — |
| 공포탐욕지수 — **CNN**(주식) | root `CnnFearGreedAdapter` (`API/market/adapter/out/feargreed/CnnFearGreedAdapter.java:17`, 41줄, `production.dataviz.cnn.io/.../graphdata`) → `FearGreedScheduler` 매일 07:00·12:00 KST로 DB 스냅샷 저장(`FearGreedScheduler.java:27`) → `FearGreedQueryService`(93줄)로 조회 `GET /api/market/fear-greed` | CNN 응답 | UI + 별도로 trading-core `CnnFearGreedIndexAdapter` (`TC/trading/adapter/out/feargreed/CnnFearGreedIndexAdapter.java:19`, 42줄)가 **CNN을 직접 호출**해 DCA 조건 `FEAR_GREED`에 사용(`DcaPlanInputCollector.loadFearGreed :172`, 10분 메모 TTL `:54`) | root 어댑터·서비스·스케줄러 합 약 190줄(Crypto 제외), trading-core 42줄 |
| 공포탐욕지수 — **크립토**(alternative.me) | `CryptoFearGreedAdapter` (`API/.../CryptoFearGreedAdapter.java:23`), `FearGreedQueryService` 인메모리 캐시, trading-core `CryptoFearGreedIndexAdapter`(43줄) → `CryptoDcaRoundExecutor.java:67` | alternative.me | 코인 DCA·UI | **삭제 예정** |
| 코인 지표(업비트·바이낸스·코인베이스 시세, 환율 Frankfurter) | `CryptoMarketIndicatorService` (216줄) | | | **삭제 예정** |

시세 공급자를 바꿔도 지표 계산 코드(`DcaIndicators`)는 그대로고, 입력이 "250봉 조정 일봉(분할+배당)"이라는 것만 공급자가 맞춰야 한다(14번 3절 "DCA 지표 조정 주가", Alpaca는 `adjustment=all`). CNN은 시세 공급자와 무관하다. 단, CNN은 root와 trading-core가 각자 호출한다(하루 호출량은 root 2회 + DCA 규칙이 `FEAR_GREED`를 쓰는 배치 실행당 최대 1회, 추정).

## 4. 개장 배치의 시세 조회 흐름

정의: `loadPriceContext` `TC/trading/application/service/TradingPriceFetcher.java:54`, `reloadPlacementPrices` `:90`. 호출처: `loadPriceContext` — `TradingService.java:118`(마감), `:195`(리포트 재구성), `:346`(개장). `reloadPlacementPrices` — `:264`(마감 `placeAll`), `:418`(개장 `planAndPlaceAtOpen`).
`PriceContext`(`TradingPriceFetcher.java:41-50`) = {cycleTickers, priceAccount, `startPriceSnapshots`(종목→`PriceSnapshot(current, prevClose)`), privacyBase}.

### 4-1. 개장 배치 시간순 (`TradingService.placeOpenOrders`, `TradingOpenScheduler.java:46` 월~금 22:00 KST)

| 순서 | 동작 | 시세 | 왜 |
|---|---|---|---|
| 1 | `loadPriceContext(started, tradeDate)` `:346` | 현재가 묶음 1콜 + 전일 종가 종목당 1콜(현재가 실패 종목은 전일 종가 조회에서 제외 `:62`) | 계획 단계(대상 선별·skip 판정·예산·0회차 진입가)의 입력 |
| 2 | `placePreOpenDcaOrders` `:355` (KIS DCA MOO만) | 1번 값 재사용 | 개장 전 접수 |
| 3 | `waitFor("개장 시각")` `:359` | 없음 | **DST 30분, 비DST 90분 sleep**(`TradingOpenScheduler.java:46` 주석, `DstInfo.waitUntilMarketOpen`) |
| 4 | `planAndPlaceAtOpen` `:~395` → `collectOpenCandidates`(1번의 `startPriceSnapshots` 그대로) → `saveAllocatedOrders` | **대기 전 값(30~90분 묵음)** | 후보 수집·예산 배정·PLANNED 저장 |
| 5 | `reloadPlacementPrices(placeableStates)` `:418` | 현재가 묶음 1콜만(전일 종가는 재조회 안 함 `:88` 주석) | 접수 직전 BUY cap 판단용 최신가 |
| 6 | `orderExecutor.placeAtOpenOrders(.. placementPrice ..)` `:421-424` | 5번 값, 누락 종목은 `state.startPrice()`로 폴백 | 주문 접수 |

마감 배치(`placeAll`)는 같은 구조지만 1번과 5번 사이에 sleep이 없다: 마감 예약이 `마감-30분`에 발화하고(`TradingCloseScheduler.ORDER_MARGIN :56`) `waitUntilOrderTime`이 같은 시각이라 대기는 0에 가깝다(추정). 간격 = 계획·DB 저장·DCA 일봉 조회 수 초. 개장 배치만 **30~90분 간격**이 실재한다.

### 4-2. 필드 단위 구분

| 값 | 최초 `loadPriceContext` | `reloadPlacementPrices`가 덮어씀? | 소비처 (file:line) |
|---|---|---|---|
| 현재가 `PriceSnapshot.current` | 읽음 | **접수 단계에서만 덮어씀**(`placementPrice`). 계획 단계의 `price`는 첫 값 그대로 | 계획: `TradingCandidatePlanner.java:141` → `CycleOrderComputer` VR 기준가(`:101-102`)·DCA `currentPrice`(`DcaPlanInputCollector.java:~127`, 매수 기준가 `DcaCycleOrderStrategy.java:176-177`)·`priceCapper.prepareForAllocation(price)`(`TradingCandidatePlanner.java:~190`, 예산 배정 전 cap)·`CycleState.startPrice`(`:57`). 접수: `placementPrice` → `placeOrders/placeAtOpenOrders` BUY cap(현재가×1.05 — 04 가이드 4-3절 언급) |
| 현재가 없음(모든 단계 실패) | `startPriceSnapshots`에서 종목 제외(`TradingPriceFetcher.java:75-80`) | 재조회 결과에 없으면 `startPrice`로 폴백(`TradingService.java:~270`) | 대상 선별: 가격 없는 종목은 `price=null`로 후보 수집(`TradingCandidatePlanner.java:141`) |
| 전일 종가 `prevClose` | 읽음(실패 시 현재가로 대체 `:78`, 알림 `:alertLoadFailed`) | **안 덮어씀, 재조회 없음** | `TradingCandidatePlanner.java:142,160` → `CycleOrderComputer.java:77,79`(INFINITE 0회차 진입 방향·`InfinitePosition.prevClosePrice` `:14,38,120-126`), DCA 참조가(`DcaCycleOrderStrategy.java:91-100`, 단 DCA 조건용 종가는 별도 일봉 250봉의 마지막 봉), `InfiniteCycleOrderStrategy.java:113`(보유 0이고 전일 종가 없으면 계획 skip) |
| PRIVACY 기준 매매표 | 읽음(DB, 시세 아님) | 해당 없음 | `screenPrivacyBase` |
| 알림 | `alertLoadFailed`(`TradingErrorEvent` 1건), 재조회도 `alertFailed` | — | 관리자 알림 |

정리: **계획 단계(대상 선별·skip 판정·예산·0회차 진입가·VR 기준가)는 전부 첫 조회 값에만 의존**한다. 재조회는 접수 직전 BUY cap 가격 하나만 바꾼다. 개장 배치에서 이 첫 값은 22:00(프리마켓) 시점이고 개장은 30~90분 뒤다. 외부 공급자를 "1시간 배치 저장값"으로 바꿔도 계획 단계는 이미 이 정도 묵은 값을 쓰고 있다(개장 배치 한정). 마감 배치는 계획이 수 초 안에 끝나므로 첫 값이 신선하다는 전제다.

## 5. 마감 배치의 "현재가 체이닝"

### 5-1. 값별 체인

| 대상 | 진입점 | 1단계 | 2단계 | 3단계 | 4단계 | 최종 실패 |
|---|---|---|---|---|---|---|
| 확정 종가 (마감+10분) | `TradingService.java:157` → `TradingPriceFetcher.fetchClosingPrices :144` → `BrokerRouter.getClosingPrices :121` | Toss 관리자 확정 일봉 `fetchConfirmedClose`(종목별 캔들, 폴백 없음 — `TossAdminPriceSource.java:46`) | KIS 관리자(앱키 설정 시, 현 운영 설정은 추정 미확인) | 계좌 어댑터 `getClosingPrices(누락분)`: KIS 계좌=일봉(`KisPriceApi`), Meritz=`candles/days`, **Toss·MOCK 계좌=`TossPriceApi.getClosingPrice` → 일봉 없으면 현재가로 폴백**(`TossPriceApi.java:202-206`) | `fetchWithFallback` 종목별 단건 재시도(`TradingPriceFetcher.java:~180-195`) → `getClosingPrice` | `failed` 종목 `TradingErrorEvent`(`alertFailed`) 후 맵에서 null |
| 현재가 | `fetchCurrentPrices` | Toss 관리자 `/prices`(`fetchPricesPartial`) | KIS 관리자 | 계좌 어댑터(KIS/Meritz 자기 시세) | 종목별 단건 | 알림 + 종목 제외 |
| 전일 종가 | `loadPriceContext :62-67` | Toss 관리자 캔들(캐시) | KIS 관리자 `base`(D-2일 수 있음 — `workflow-ledger-price.md` 알려진 한계 (a)) | 계좌 어댑터 | 단건 → **현재가로 대체**(`TradingPriceFetcher.java:78`) | 알림 |
| (참고) Toss 단건 전일 종가 폴백 | `TossPriceApi.getPrevCloses :129-146` | 캔들 | 캔들 실패 종목만 현재가(`:140-144`) | | | 둘 다 실패 시 예외(테스트 `getPrevClose_candleAndPriceFallbackBothFail_throws`) |

"확정 종가 → 현재가 → 전일 종가 → 캐시" 형태의 **단일 체인은 없다**. 값마다 별도 체인이고, 전부 "관리자(Toss→KIS) → 계좌 어댑터 → 종목별 단건"이다. 확정 종가에서 현재가로 떨어지는 경로는 계좌 어댑터 단계(Toss·MOCK)뿐이고, 관리자 단계는 대체값을 쓰지 않는다(`workflow-ledger-price.md` "①②는 대체값을 쓰지 않는다").

### 5-2. 확정 종가가 쓰이는 곳

| 용도 | file:line | 값이 null이면 |
|---|---|---|
| 마감 리포트 원장 저장 — `cycle_position`의 종가·평가 | `TradingService.java:303` → `TradingReporter.java:77` → `CyclePositionPersistor.saveCyclePosition :36-49`(`CyclePosition.tradeSnapshot(.., price)`) | 저장 경로가 null을 그대로 받음(추정: 평가금액 계산이 null 허용인지는 미확인) |
| INFINITE 리버스모드 진입·종료 판정 | `CyclePositionPersistor.java:43,92-109`(`InfinitePosition`의 `prevClosePrice`로 사용) | 판정 불가 |
| VR 사이클 롤오버 | `VrCycleRolloverService.java:41,71-76` | **알림 후 롤오버 연기**("종가 없음, 다음 매매일 재시도") |
| VR due일 평가 종가 | `VrCycleRolloverService.java:83` (별도 `getClosingPrice` 호출) | 알림 후 연기 `:86-95` |
| 새 사이클 시작 금액·종료 금액 | `CycleSnapshotCreator.java:68,72,98,112,155-157` (`totalAssets = 예수금 + 종가×보유`) | — |
| MOCK 체결 판정 | `MockBrokerAdapter.getExecutions :140`(확정 봉 OHLC, 위 체인과 별개 호출) | 현재가 단일값 봉으로 대체 `:140-142` |

### 5-3. 각 단계가 필요한 상황 (근거)

| 단계 | 앞 단계 실패 상황 | 근거 |
|---|---|---|
| 관리자 Toss 확정 일봉 → KIS 관리자 | Toss 캔들 미발행·장애 | `workflow-ledger-price.md` "시세 출처" 문단, `AdminMarketPriceAdapter.java:48-59` 단계별 warn, 테스트 `closingPricesAdminFirstThenAccountForMissing`·`closingPricesExcludeFailure` |
| → 계좌 어댑터 | 관리자 둘 다 누락 | 〃 `BrokerRouter.adminFirst`, 테스트 `closingPriceSingleAdminThenAccount` |
| 계좌 어댑터(Toss/Mock)가 현재가로 폴백 | 일봉 봉 날짜가 기대 US 세션일과 다름 | `TossPriceApi.java:200-206` 주석 "KIS `KisPriceApi.getClosingPrice`와 동일 정책", 테스트 `getClosingPrice_noCandleForUsSession_fallsBackToLivePrice`, KIS 쪽 `getClosingPrice_fallsBackToLivePriceWhenBarDateMismatches`·`…DailyPriceEmpty` |
| 종목별 단건 재시도 | 묶음 호출 실패·일부 누락 | `TradingPriceFetcher.java:174-196` 주석 |
| "현재가 → 종가" 대체가 **운영에서 실제 일어났는지** | — | **근거 없음**(로그·이력 미조회) |
| 전일 종가 실패 → 현재가 대체 | 신규 상장·장애·개장 전 캔들 실패 | `workflow-ledger-price.md` 한계 (a), 테스트 `getPrevCloses_fallsBackToCurrentPriceOnlyForFailedCandle` |
| 캐시에 실패(empty)를 저장 | 같은 버킷 내 재시도 방지 | `TossPriceApi.java:149-151` 주석("허용된 트레이드오프") |

이전 시사점: 확정 종가는 LOC·MOC 체결 판정(MOCK)·VR 롤오버·원장 평가에 쓰인다. 단계 중 공급자 교체로 값이 바뀌는 것은 1단계(Toss 관리자)이고, 계좌 어댑터 단계(KIS·Meritz 원장 종가)는 유지되므로 "관리자 확정 종가를 없애고 계좌 어댑터에 맡기는" 구성이 체인 안에서 이미 가능하다(운영 계좌 MERITZ·KIS 기준). MOCK은 `TossPriceApi` 직접 의존(`MockBrokerAdapter.java:29`)이라 포트 주입으로 바꿔야 한다(04 가이드 2-2절의 시세 포트 분리).

## 6. 토스 관리자 키 의존 잔여

운영 계좌에 토스 계좌는 없다(1절 DB 실측). 코드상 Toss 계좌 어댑터(주문·잔고)는 남아 있다.

| 구성 | file:line | 줄 수 | 시세를 떼면 삭제? |
|---|---|---|---|
| 설정값 `toss.admin-client-id/secret` | `trading-core/src/main/resources/application.yml:93-94`(env `TOSS_ADMIN_CLIENT_ID/SECRET`), `.env.example`, `deploy/server/README.md` | 2 + 문서 | 삭제 가능. 단 `application-local.yml:40-41`(gitignored)에 실값처럼 보이는 키가 있다 — 삭제하고 키 폐기 검토 |
| `TossAuthApi` 관리자 부분 | `TossAuthApi.java:32-35`(필드), `:64-70`(`getAdminToken`/`recoverAdminToken`), `:96-100`(`issueAdminToken`) | 약 25(파일 162, 나머지는 계좌 토큰) | 부분 삭제(필드·메서드 3개). `issueOAuthToken`은 계좌용과 공유 → 유지 |
| `TossDistributedTokenCoordinator` admin | `:22`(`ADMIN_SCOPE`), `:72-79` | 약 8(파일 207) | 부분 삭제. lease·canonical 발급 골격은 계좌 토큰과 공유 → 유지 |
| `TossRedisTokenStore`/`TossTokenStore` | 229 / 39 | scope 문자열로 구분하는 범용 저장소 | **유지**(계좌 토큰 공유) |
| `TossHttpClient.getCommon` + 429 재시도 | `TossHttpClient.java:54-60`, `retryOnTooManyRequests :63-`, `executeWithBackoffRetry`(`:135-`, 계좌 호출과 공유) | `getCommon`+재시도 약 25(파일 209) | `getCommon`·`retryOnTooManyRequests` 삭제, `executeWithBackoffRetry`는 유지 |
| `TossPriceApi` | `TC/broker/adapter/out/toss/TossPriceApi.java` | 255 | 삭제 가능(전부 관리자 시세). `resolveMarketSessionAt`(DST 표 복제 `:174-`)도 함께 |
| `TossCandleApi` + `TossCandle` | `TossCandleApi.java` 174, `TossCandle` 21 | 195 | 삭제 가능(전부 `getCommon`) |
| `TossAdminPriceSource` | 59 | 59 | 삭제 가능 |
| `UsdKrwRateCache` + `TossHoldingsApi` 환율 | `UsdKrwRateCache.java` 46, `TossHoldingsApi.java:38,46,138-166` 약 35 | 약 80 | 환율을 옮기면 삭제 가능. 단 `TossHoldingsApi.java:64,91`(Toss 계좌 잔고의 KRW 환산)이 `fetchUsdToKrwRate`를 쓰므로 **Toss 계좌 어댑터를 유지하면 환율 호출이 남는다**(계좌 키가 아닌 관리자 토큰으로 호출됨) |
| `TossBrokerAdapter` 시세 위임 | `TossBrokerAdapter.java:72-133`(`CandlePort`·가격 8개·`ExchangeRatePort`) | 약 60 | 삭제 가능. 단 계좌 어댑터 3단계(`BrokerRouter.of(account).getPrice`)가 쓰므로 Toss 계좌 지원을 남기면 이 위임을 새 시세 포트로 바꿔 두어야 함 |
| `AdminMarketPriceAdapter`·`AdminPriceSource` | 68 + 24 | 92 | 소스가 KIS 하나만 남으면 단순화 가능, 새 공급자를 소스로 추가하는 자리이기도 함 |
| KIS 관리자 | `KisAdminPriceSource` 82, `KisAdminTokenCoordinator` 105, `application.yml:88-89` | 187 | **별개의 관리자 키**(KIS). 이전 대상에 포함할지는 결정 필요. `KisPriceApi`(261)는 KIS 계좌 시세와 공유 → 유지 |
| `MockBrokerAdapter` | `:29,58-97,140,207` | 215 중 시세 위임 | `TossPriceApi` 직접 주입 → 포트 주입으로 교체 필요(삭제 아님) |

합계(공유분 제외, 추정): Toss 관리자 전용으로 지울 수 있는 것은 `TossPriceApi`+`TossCandleApi`·`TossCandle`+`TossAdminPriceSource`+`UsdKrwRateCache`+환율 부분+인증·`getCommon` 부분 ≈ **약 700줄**(14번 5절 γ/δ 추정 585~685줄과 같은 범위). 토스 **주문 브로커(사용자 키)** 로 남는 공유분: `TossHttpClient` 본체·`TossAuthApi` 계좌 토큰·분산 토큰 골격·Redis 저장소·`TossOrderApi`·`TossHoldingsApi` 나머지·`TossBrokerAdapter` 주문부. 토스 계좌 지원 자체를 접으면(운영 0건) 이 공유분까지 지울 수 있으나 그것은 별도 결정이다.

## 7. 이전 시 확인할 점 (측정에서 나온 것만)

1. 분당 한도: 마감 배치 시작 구간이 현재 구조로 약 13크레딧/분(추정) — Twelve Data 8/분이면 호출 사이 대기 필수. Alpaca(200/분)는 무관.
2. 사용자 요청 경로 3곳이 **캐시 없는 현재가**를 직접 부른다: `AccountLedgerTotalsService.java:56`, `MockBrokerAdapter.java:207`, `ManualTradingService.java:145`·`VrReconfigureService.java:99`. 저장값으로 읽게 하면 외부 호출이 사라지나 값이 묵는다.
3. 개장 배치의 계획 단계는 이미 30~90분 묵은 가격을 쓴다(4-2절). 1시간 배치 저장값으로 바꿔도 개장 계획의 신선도는 같고, 접수 직전 재조회(5단계)만 실시간 호출이 필요하다.
4. 환율 소비처는 벤치마크 비교 응답(`BenchmarkService.java:236`) 하나다. 단일 값이라 일 1회 참고 환율로 충분하다는 04 가이드 판단과 코드가 일치한다.
5. Mock 체결 판정이 마감마다 종목당 확정 캔들을 캐시 없이 다시 부른다(전략 수만큼). 확정 종가를 저장하면 호출 0.
