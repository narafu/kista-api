## com.kista.api.benchmark (`:api`)

Spring Modulith 모듈(CLOSED) — 주택/ETF 벤치마크 비교와 KB Land/Alpaca 지수 시세 수집만 담당한다. 계좌·Toss 통계·백테스트·포트폴리오·summary/equity-curve/cycles는 `tradingcore.stats` 소유다. 옛 이름 `stats`와 충돌을 피해 개명했으므로 HTTP 경로는 `/api/stats/**` 그대로이고, 이벤트 FQCN이 바뀐 배포 절차는 `docker-infra.md` "배포 직전 EPR 정리 런북". `BenchmarkController`는 `/api/stats/housing-benchmark{,/series,/index-series,/etf-series,/regions}` 5개만 소유한다.

- `InvestmentPoint`/`StrategyRef`는 벤치마크 계산 입력용 narrowing이다. `InvestmentPointsHttpAdapter`가 내부 API 응답(`contract.stats.InvestmentPointsResponse`)에서 명시 매핑한다. TWR 계산은 `tradingcore.stats`의 `InvestmentIndexCalculator`가 맡고 여기는 일별 포인트만 받는다.
- `BenchmarkService`는 비교 본체를 `TtlResultCache`에 10분 캐시하고, 체결 시 `TradeEventBenchmarkCacheInvalidator`가 Redis `trade.event`를 notify와 별도로 구독해 그 사용자 키만 비운다.
- 비교 규칙: 아파트는 KB 주간 조사일에 as-of 스냅, ETF는 US 거래일+1 KST 날짜 교집합. 1년 미만 구간은 연환산을 null로 둔다(백테스트 CAGR과 동일 기준). 투자 쪽(전략 계좌 통화)과 벤치마크(HOUSING=KRW/ETF=USD)는 현지통화 그대로 비교하며 환율 변환은 없다. PORTFOLIO는 요청 `?currency=`(기본 USD) 계좌만 합산하고 STRATEGY는 currency를 무시하며, `investmentCurrency`·notice 표기와 캐시 키에 통화가 들어간다.
- `currentExchangeRate`는 요청마다 실시간 조회하는 정보성 필드일 뿐 수익률·공통월·summary에 반영하지 않는다. 조회 실패는 `CurrentExchangeRateHttpAdapter`가 warn 후 null로 흡수해 비교 응답이 200으로 유지된다(내부 API 응답은 bare `BigDecimal`이라 contract 대상 아님).
- KB Land 수집 실패는 `BenchmarkAlertRaisedEvent`를 발행하고(notify `AlertNotifier` 구독), `MarketIndexPriceSyncService`는 심볼별 실패를 격리해 순회한 뒤 실패가 있으면 `IllegalStateException`을 던진다(이벤트 없음).
- 스케줄러 3종(`KbLandHousingBenchmarkScheduler`/`KbLandPriceIndexScheduler`/`MarketIndexPriceSyncScheduler`)은 "schedule"로 공개되고 `web.AdminSchedulerController`가 수동 트리거용으로 주입한다. `MarketIndexPriceSyncScheduler`는 비거래일에 Alpaca가 빈 배열을 줘 무해한 no-op이라 요일 조건이 없다.
- Alpaca 빈 이름은 `alpacaRestClient`. `AlpacaIndexPriceAdapter`는 `feed=sip`(공식 종가 — iex 종가는 다르다)을 end 없이 받아(무료 키는 최근 15분에 걸린 end면 403) to 이하이면서 확정된 봉만 남긴다. 뉴욕 오늘 봉은 16:16 전이면 버린다(조기 마감일도 같은 시각, 다음 동기화가 받는다). KB Land는 아파트 5분위 매매평균가격(월간)과 주간 매매가격지수를 수집한다.
