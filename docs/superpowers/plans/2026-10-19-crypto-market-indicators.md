# 코인 시장 지표(김프·펀딩비·OI) API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** trading-core에 `GET /api/crypto-market-indicators?days=200`을 추가한다. 코인 DCA 지원 종목별 김프(공식 환율·테더 두 기준)·펀딩비·OI와 테더 프리미엄을 현재값과 일별 추이로 응답한다.

**Architecture:** `tradingcore.stats`가 조회 전용 서비스로 조립한다. 외부 데이터는 세 경로로 받는다 — Upbit 시세는 broker `CryptoExchangePort`(KRW-USDT 메서드 추가), Binance·Frankfurter는 stats 소유 출력 포트를 `stats/adapter/out/{binance,frankfurter}`가 구현한다. 계산은 순수 static 함수(`CryptoPremiums`), 서비스는 지표별 실패 격리와 10분 JVM 캐시를 맡는다. DB·스케줄러는 없다.

**Tech Stack:** Java 25, Spring Boot 4, RestClient(`RestClients.withTimeouts`), JUnit 5 + AssertJ + Mockito, `MockRestServiceServer`, `@WebMvcTest`

**Spec:** `docs/superpowers/specs/2026-10-19-crypto-market-indicators-design.md`

## Global Constraints

- 대상 종목: `StrategyTicker` 중 `getMarket() == Market.CRYPTO_KRW`(현재 BTC·ETH). **`StrategyTicker`에 USDT 추가 금지**
- Binance 심볼 = `ticker.name() + "USDT"`, 별도 레지스트리 없음
- 날짜 키는 UTC 날짜. Upbit `CryptoCandle.kstDate`(KST 09:00 시작 = UTC 00:00)를 그대로 UTC 날짜로 쓴다. 조인은 날짜 키로만 하고, 인덱스 정렬은 금지
- 김프 % = (국내가 ÷ 해외 원화환산가 − 1) × 100, scale 4 HALF_UP. 펀딩비는 비율 원값(scale 8 HALF_UP 일평균)
- `days` 1~200(기본 200), 범위를 벗어나면 400. OI는 항상 최근 30일
- 캐시: TTL 10분, 200일 결과 1벌을 캐시하고 `days`는 잘라서 응답. 모든 섹션이 null인 결과는 캐시하지 않는다
- 응답 `Cache-Control: max-age=600, public`
- 주석은 `//` 인라인만 쓴다(Javadoc 금지). 필드마다 역할 주석을 단다. 커밋 메시지는 한글 Conventional Commit, author는 `narafu <narafu@kakao.com>`
- 테스트 명령은 반드시 `:trading-core:test --tests ...`로 서브프로젝트를 지정한다

## Review Focus

1. **Frankfurter 주말·휴일 공백**: 토요일 Upbit 일봉에도 공식 환율 김프가 있어야 한다(직전 금요일 환율). 창 시작일이 월요일이어도 직전 금요일 값을 쓴다 — Task 4 `fillForward` 테스트로 고정
2. **Binance 진행 중 봉**: kline 마지막 봉(오늘, 미완성)이 history에 섞이면 안 된다 — Task 4 `kimchiHistory` 교집합 테스트
3. **Binance 선물만 장애**: 김프는 그대로 나오고 `fundingRate`·`openInterest`만 null이어야 한다 — Task 5 테스트
4. **환율만 장애**: `official`·`usdtPremium`은 null, `tether`는 값이 있어야 한다 — Task 5 테스트
5. **전부 장애**: 200 응답에 모든 섹션 null, 그리고 다음 요청에서 재조회(캐시 안 됨)해야 한다 — Task 5 테스트

## 내 판단 (스펙 보정)

- 스펙의 "모든 섹션 실패 시 503"을 **200 + 전부 null**로 바꾼다. trading-core에는 브로커 외 범용 503 매핑이 없어서 새 예외·매핑을 만드는 것보다 단순하다. UI는 섹션별 null 처리만 하면 된다. Task 7에서 스펙 문구도 고친다

---

### Task 1: Upbit KRW-USDT 시세 (broker)

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/application/port/output/CryptoExchangePort.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitExchangeAdapter.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitMarketRegistry.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitExchangeAdapterTest.java`
- Modify: `docs/agents/modules/tradingcore/broker.md` ("업비트 코인 DCA 지원" 절과 port 목록의 CryptoExchangePort 설명)

**Interfaces:**
- Produces (`CryptoExchangePort`):
  - `BigDecimal getUsdtKrwPrice();` — 원화 USDT 현재가(공개 시세)
  - `List<CryptoCandle> getCompletedUsdtKrwDailyCandles(int count);` — 계약은 `getCompletedDailyCandles`와 같다(1~400, 완성 봉만, 오래된 순)
- `UpbitMarketRegistry`: `static final String USDT_MARKET = "KRW-USDT";` (package-private)

- [ ] **Step 1: 실패 테스트 작성** — `UpbitExchangeAdapterTest`에 추가하고 기존 `CLOCK`·`server` 셋업을 재사용한다
  - `USDT_원화_현재가를_KRW_USDT_마켓으로_조회한다`: `/v1/ticker?markets=KRW-USDT` → `[{"market":"KRW-USDT","trade_price":1452.0}]`. 결과 `isEqualByComparingTo("1452")`
  - `USDT_원화_완성_일봉은_진행중_봉을_제외한다`: `/v1/candles/days?market=KRW-USDT&count=3` → 봉 3개(진행 중 1개 포함). count=2 요청 결과가 완성 봉 2개(오래된 순)인지 확인
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.upbit.UpbitExchangeAdapterTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패
- [ ] **Step 3: 구현** — 기존 `getCompletedDailyCandles` 본문을 `private List<CryptoCandle> completedDailyCandles(String market, int count)`로 추출하고 두 공개 메서드가 위임하게 한다. `getUsdtKrwPrice`는 `/v1/ticker` 단건 호출의 첫 행 `tradePrice`를 쓰고, 빈 응답이면 `UpbitApiException`(기존 생성자 패턴)을 던진다
- [ ] **Step 4: 통과 확인** — 같은 명령 → `BUILD SUCCESSFUL`
- [ ] **Step 5: broker.md 갱신 후 커밋** — `feat(broker): 업비트 KRW-USDT 현재가·완성 일봉 조회를 CryptoExchangePort에 추가한다`

---

### Task 2: Binance 공개 시세 어댑터 (stats)

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/port/output/BinanceMarketDataPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/DatedValue.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/FundingRateRecord.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/FundingRateQuote.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/binance/BinanceConfig.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/binance/BinanceMarketDataAdapter.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/adapter/out/binance/BinanceMarketDataAdapterTest.java`

**Interfaces:**
- Produces (domain, `stats.domain.model`):
  - `record DatedValue(LocalDate date, BigDecimal value)`
  - `record FundingRateRecord(Instant fundingTime, BigDecimal rate)`
  - `record FundingRateQuote(BigDecimal rate, Instant nextFundingTime)`
- Produces (`BinanceMarketDataPort`):
  - `BigDecimal getSpotPrice(StrategyTicker ticker);` — `GET https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT` → `price`
  - `List<DatedValue> getSpotDailyCloses(StrategyTicker ticker, int limit);` — `GET https://api.binance.com/api/v3/klines?symbol=..&interval=1d&limit=..`. 각 행은 배열이고 `[0]`=openTime ms, `[4]`=close 문자열이다. date = openTime의 UTC 날짜, 오래된 순. **진행 중 봉을 포함한 그대로 반환한다**(제거는 Task 4 조인이 맡는다)
  - `FundingRateQuote getFundingRate(StrategyTicker ticker);` — `GET https://fapi.binance.com/fapi/v1/premiumIndex?symbol=..` → `lastFundingRate`, `nextFundingTime`(ms)
  - `List<FundingRateRecord> getFundingHistory(StrategyTicker ticker, Instant from);` — `GET https://fapi.binance.com/fapi/v1/fundingRate?symbol=..&startTime=<ms>&limit=1000`
  - `List<DatedValue> getOpenInterestHistory(StrategyTicker ticker);` — `GET https://fapi.binance.com/futures/data/openInterestHist?symbol=..&period=1d&limit=30` → date = `timestamp`의 UTC 날짜, value = `sumOpenInterestValue`(USD)
- Bean: `@Configuration("statsBinanceConfig")`에 `RestClient statsBinanceRestClient()` = `RestClients.withTimeouts(3_000, 7_000)`. 호스트 두 개는 어댑터 상수로 둔다(properties 불필요)

실측 응답 예시(2026-10-19) — 테스트 픽스처에 그대로 쓴다:
```
klines:        [[1792368000000,"82116.0","83158.0","82010.58","83034.01","6092.44",1792454399999,"...",1050886,"...","...","0"]]
premiumIndex:  {"symbol":"BTCUSDT","markPrice":"83008.9","lastFundingRate":"0.00010000","nextFundingTime":1792425600000,"time":1792402827000}
fundingRate:   [{"symbol":"BTCUSDT","fundingTime":1792368000000,"fundingRate":"0.00004958","markPrice":"82086.07"}]
openInterestHist: [{"symbol":"BTCUSDT","sumOpenInterest":"93130.501","sumOpenInterestValue":"7644724174.10721700","timestamp":1792368000000}]
```
(`1792368000000` = 2026-10-19T00:00:00Z)

- [ ] **Step 1: 실패 테스트 작성** — `AlpacaCandleAdapterTest` 패턴을 따른다(`RestClient.builder()` + `MockRestServiceServer.bindTo`). 어댑터 생성자는 `BinanceMarketDataAdapter(RestClient)`
  - `현물_현재가를_USDT_심볼로_조회한다`: `requestTo("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT")` → `{"symbol":"BTCUSDT","price":"83034.01"}`
  - `일봉은_openTime_UTC_날짜와_종가로_매핑한다`: 위 klines 픽스처 → `DatedValue(2026-10-19, 83034.01)`
  - `펀딩비_현재값과_다음_정산시각을_매핑한다`: → `rate=0.0001`, `nextFundingTime=Instant.ofEpochMilli(1792425600000L)`
  - `펀딩비_이력은_startTime_ms와_limit_1000으로_조회한다`: `queryParam("startTime","1790000000000")`, `queryParam("limit","1000")`
  - `OI_이력은_period_1d_limit_30으로_조회해_USD_명목가로_매핑한다`: → `DatedValue(2026-10-19, 7644724174.107217)`
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.adapter.out.binance.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
- [ ] **Step 3: 구현** — 응답 record는 package-private 중첩 record로 둔다(constraints.md "Adapter 내부 중첩 타입"). klines는 `List<List<Object>>`로 받아 `[0]`을 `Number`, `[4]`를 `String`으로 변환한다. null 응답은 빈 리스트로 처리한다
- [ ] **Step 4: 통과 확인** — 같은 명령 → `BUILD SUCCESSFUL`
- [ ] **Step 5: 커밋** — `feat(stats): Binance 현물·선물 공개 시세 어댑터를 추가한다`

---

### Task 3: Frankfurter 과거 환율 어댑터 (stats)

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/port/output/HistoricalFxRatePort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/frankfurter/FrankfurterConfig.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/frankfurter/FrankfurterFxRateAdapter.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/adapter/out/frankfurter/FrankfurterFxRateAdapterTest.java`

**Interfaces:**
- Produces: `Map<LocalDate, BigDecimal> getUsdKrwRates(LocalDate from, LocalDate to);` — 영업일 값만 담은 원본을 반환하고, 채우기는 하지 않는다. URL은 `https://api.frankfurter.dev/v1/{from}..{to}?base=USD&symbols=KRW`, 응답은 `{"rates":{"2026-09-01":{"KRW":1374.61},...}}`
- Bean: `@Configuration("statsFrankfurterConfig")`에 `RestClient statsFrankfurterRestClient()` = `RestClients.withTimeouts(3_000, 7_000)`

- [ ] **Step 1: 실패 테스트** — `기간_환율을_날짜별_KRW로_매핑한다`: 위 형태 응답 4일치(2026-09-01~04) → 맵 크기 4, `2026-09-01` → `1374.61`. `rates_없으면_빈_맵`: `{}` → 빈 맵
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.adapter.out.frankfurter.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
- [ ] **Step 3: 구현** — 응답은 `record RatesResponse(Map<LocalDate, Map<String, BigDecimal>> rates)`로 받는다
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(stats): Frankfurter 과거 USD/KRW 환율 어댑터를 추가한다`

---

### Task 4: 김프·펀딩비 계산 (순수 도메인)

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoPremiums.java` (static 유틸, `final class` + private 생성자)
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/DatedPremiumPair.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/domain/model/CryptoPremiumsTest.java`

**Interfaces:**
- Consumes: `DatedValue`, `FundingRateRecord`(Task 2), broker `CryptoCandle`
- Produces:
  - `record DatedPremiumPair(LocalDate date, BigDecimal official, BigDecimal tether)` — `official`은 nullable
  - `static BigDecimal premiumPercent(BigDecimal domestic, BigDecimal foreignKrw)` — (domestic ÷ foreignKrw − 1) × 100, 나눗셈은 `MathContext.DECIMAL64`, 결과 scale 4 HALF_UP
  - `static Map<LocalDate, BigDecimal> fillForward(Map<LocalDate, BigDecimal> raw, LocalDate from, LocalDate to)` — [from, to] 각 날짜에 그 날짜 이하의 최신 raw 값을 넣는다. 이전 값이 없는 날짜는 제외한다
  - `static List<DatedPremiumPair> kimchiHistory(List<CryptoCandle> upbitCoin, List<CryptoCandle> upbitUsdt, List<DatedValue> binanceCloses, Map<LocalDate, BigDecimal> fxByDate)` — 세 시계열 날짜의 교집합만, 오래된 순. tether = premiumPercent(coin, binance × usdt), official = fx가 있으면 premiumPercent(coin, binance × fx), 없으면 null
  - `static List<DatedValue> usdtPremiumHistory(List<CryptoCandle> upbitUsdt, Map<LocalDate, BigDecimal> fxByDate)` — 환율이 있는 날짜만, premiumPercent(usdt, fx)
  - `static List<DatedValue> fundingDailyAverage(List<FundingRateRecord> records)` — `fundingTime`의 UTC 날짜별 평균, scale 8 HALF_UP, 오래된 순

- [ ] **Step 1: 실패 테스트 작성**
  - `김프는_국내가를_해외_원화환산가로_나눈_초과율이다`: `premiumPercent(147_000_000, 140_000_000)` → `5.0000`
  - `fillForward는_주말에_직전_영업일_값을_쓴다`: raw {금 10-16: 1400}, from 10-17(토), to 10-19(월, raw 1410) → {17:1400, 18:1400, 19:1410}
  - `fillForward는_창_시작전_값이_없으면_그날을_제외한다`: raw {10-19:1410}, from 10-17 → {19:1410}만
  - `김프_추이는_세_시계열_날짜_교집합만_쓴다`: binance에 오늘 날짜(진행 중 봉)가 하나 더 있고 Upbit에는 없음 → 그 날짜 제외
  - `환율이_없는_날은_official만_null이다`
  - `테더김프_테더프리미엄_공식김프_곱셈관계가_성립한다`: (1+공식/100) ≈ (1+테더/100)(1+usdt/100), `within(0.0001)`
  - `펀딩비는_UTC_날짜별_평균이다`: 같은 날 3건(0.0001, 0.0002, 0.0003) + 다음 날 1건 → `[0.00020000, …]`
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.domain.model.CryptoPremiumsTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: 커밋** — `feat(stats): 김프·테더 프리미엄·펀딩비 일평균 계산을 추가한다`

---

### Task 5: 지표 조립 서비스 (격리·캐시)

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoMarketIndicators.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/usecase/CryptoMarketIndicatorUseCase.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoMarketIndicatorService.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/CryptoMarketIndicatorServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `CryptoExchangePort`(`CryptoExchanges.of(Broker.UPBIT)`), Task 2 `BinanceMarketDataPort`, Task 3 `HistoricalFxRatePort`, Task 4 `CryptoPremiums`, 기존 `BrokerStatisticsUseCase.currentExchangeRate().midRate()`(현재 공식 환율), `Clock` 빈
- Produces:
  ```java
  public record CryptoMarketIndicators(Instant asOf, Premium usdtPremium, List<TickerIndicators> tickers) {
      public record Premium(BigDecimal current, List<DatedValue> history) {}                 // 테더 프리미엄
      public record PremiumPair(BigDecimal official, BigDecimal tether) {}                    // official nullable
      public record KimchiPremium(PremiumPair current, List<DatedPremiumPair> history) {}
      public record FundingRate(BigDecimal current, Instant nextFundingTime, List<DatedValue> history) {}
      public record OpenInterest(List<DatedValue> history) {}
      public record TickerIndicators(StrategyTicker ticker, KimchiPremium kimchiPremium, FundingRate fundingRate, OpenInterest openInterest) {} // 섹션 nullable
      public CryptoMarketIndicators withDays(int days) { ... }  // 김프·펀딩·usdtPremium history를 마지막 days개로 자름, OI 유지
      public boolean isEmpty() { ... }                            // usdtPremium과 모든 종목 섹션이 null
  }
  ```
  - `CryptoMarketIndicatorUseCase`: `CryptoMarketIndicators get(int days);`

**동작:**
- 상수 `MAX_DAYS = 200`, `TTL = Duration.ofMinutes(10)`. 캐시 필드는 `volatile Cached(Instant expiresAt, CryptoMarketIndicators value)`이고, 동시 미스 때 중복 조회는 허용한다(`// ponytail:` 주석으로 한계 명시)
- 섹션별 try/catch(`Exception`)로 실패 시 null + `log.warn`. 섹션 단위는 다음과 같다:
  - (a) Upbit USDT 현재가·일봉: 실패하면 모든 김프·usdtPremium이 null
  - (b) 환율 현재값·Frankfurter: 실패하면 official·usdtPremium만 null
  - (c) 종목별 김프
  - (d) 종목별 펀딩비
  - (e) 종목별 OI
- Frankfurter 조회 창: from = 오늘(UTC) − 200일 − 7일, to = 오늘. `fillForward(raw, 오늘−200일, 오늘)`
- 일봉 개수: Upbit `MAX_DAYS`, Binance kline limit `MAX_DAYS + 1`. 펀딩 이력 from = 오늘 00:00Z − 200일
- `days`가 1~200을 벗어나면 `IllegalArgumentException("조회 기간은 1~200일입니다: " + days)` → 400(GENERIC 매핑)

- [ ] **Step 1: 실패 테스트 작성** — Mockito로 모든 포트를 mock하고, `CryptoExchanges`는 실제 인스턴스(`new CryptoExchanges(List.of(upbitMock))`, `upbitMock.supports()`=UPBIT)를 쓴다. `Clock.fixed(2026-10-19T03:00Z)`
  - `정상_조회시_종목별_세_지표와_테더프리미엄을_채운다`
  - `Binance_선물만_실패하면_김프는_유지하고_펀딩_OI만_null`
  - `환율만_실패하면_official과_usdtPremium만_null` (Frankfurter throw)
  - `모두_실패하면_전부_null이고_캐시하지_않는다`: 두 번 호출 시 `upbit.getUsdtKrwPrice()`가 `times(2)`
  - `10분_안의_재호출은_외부를_다시_부르지_않고_days만_자른다`: `get(200)` 후 `get(30)` → 외부 호출 1회, 김프 history 크기 ≤ 30
  - `days_범위_밖이면_IllegalArgumentException`: 0, 201
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.CryptoMarketIndicatorServiceTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과 확인** — 추가로 `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`(Modulith·Hexagonal 규칙)
- [ ] **Step 5: 커밋** — `feat(stats): 코인 시장 지표 조립 서비스를 추가한다`

---

### Task 6: 컨트롤러·응답 DTO

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/CryptoMarketIndicatorController.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/dto/CryptoMarketIndicatorsResponse.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/adapter/in/web/CryptoMarketIndicatorControllerTest.java`

**Interfaces:**
- Consumes: `CryptoMarketIndicatorUseCase.get(int)`(Task 5)
- Produces: `GET /api/crypto-market-indicators?days=200`(기본 200). 응답은 `CryptoMarketIndicatorsResponse.from(CryptoMarketIndicators)` — 도메인 구조를 그대로 미러링한 중첩 record다. JSON 키는 `asOf`, `usdtPremium{current,history[{date,value}]}`, `tickers[{ticker, kimchiPremium{current{official,tether},history[{date,official,tether}]}, fundingRate{current,nextFundingTime,history}, openInterest{history[{date,valueUsd}]}}]`. OI DTO만 `value` → `valueUsd`로 이름을 바꾼다. BigDecimal 직렬화는 기존 `PlainDecimalSerializer` 사용처를 따른다
- `@Tag(name = "코인 시장 지표")`, `@Operation` 설명, `ResponseEntity.ok().cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())`

- [ ] **Step 1: 실패 테스트** — 기존 trading-core `@WebMvcTest` 컨트롤러 테스트(예: `CryptoPortfolioController` 테스트)의 import·인증 패턴을 복제하고 `@Execution(SAME_THREAD)`를 붙인다
  - `지표를_조회하면_200과_캐시헤더`: `header().string("Cache-Control", containsString("max-age=600"))`, `jsonPath("$.tickers[0].openInterest.history[0].valueUsd")` 존재
  - `days_기본값은_200`: `verify(useCase).get(200)`
  - `섹션이_null이면_null로_직렬화`: `jsonPath("$.tickers[0].fundingRate").value(nullValue())`
- [ ] **Step 2~4: 실패 → 구현 → 통과** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.adapter.in.web.CryptoMarketIndicatorControllerTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
- [ ] **Step 5: 커밋** — `feat(stats): 코인 시장 지표 조회 API를 추가한다`

---

### Task 7: 문서·아키텍처 맵

**Files:**
- Modify: `docs/agents/modules/tradingcore/stats.md` — 신규 포트 2개, 어댑터 2개(binance·frankfurter), `CryptoMarketIndicatorService`·컨트롤러, 캐시·격리 규칙 한 줄
- Modify: `src/test/resources/architecture/flows.yml`:
  - `result` 흐름 `dashboard` 단계 뒤에 `crypto-market-indicators` 단계 추가: `lane: ui`, `to: [trading, broker]`, `code: [CryptoMarketIndicatorController#get, CryptoMarketIndicatorService#get]`, `fail: 지표·종목별로 실패한 섹션만 null(200), 10분 캐시`
  - `landscape` "사용자 기능"에 `{id: crypto-market-indicators, title: 코인 시장 지표(김프·펀딩비·OI), flow: result}` 추가
  - `result.summary`의 "공포탐욕"이 kista-api 담당이라는 문장은 유지하고, 코인 시장 지표가 kista-trading이라는 점을 덧붙인다
- Modify: `docs/superpowers/specs/2026-10-19-crypto-market-indicators-design.md` — "모든 섹션 실패하면 503" 문장을 "200 + 전부 null(미캐시)"로 바꾼다
- Check: `README.md` 외부 연동·기술 스택 목록에 Upbit·Binance가 나열돼 있으면 Binance·Frankfurter를 추가하고, 그런 목록이 없으면 변경하지 않는다

- [ ] **Step 1: 문서 편집**
- [ ] **Step 2: 맵 검증** — `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`
- [ ] **Step 3: 커밋** — `docs: 코인 시장 지표를 모듈 문서·아키텍처 맵에 반영한다`

---

### 최종 검증 (전체 1회)

- [ ] `./gradlew :trading-core:test 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`
- [ ] 로컬 trading-core를 기동하고(`docs/agents/commands.md` 2-프로세스 부팅, trading-core만으로 충분) `curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/api/crypto-market-indicators?days=5 | jq '.tickers[0].kimchiPremium.current, .usdtPremium.current'` 결과가 숫자 2개(대략 -5~+10 범위)인지 확인. 토큰은 `POST localhost:8080/api/auth/dev-token`(root 필요) — root를 띄우기 어려우면 이 단계는 생략하고 그 사실을 보고한다
