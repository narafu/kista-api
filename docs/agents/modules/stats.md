## com.kista.stats (`:api`)

com.kista.stats/    ← Spring Modulith 모듈(CLOSED, root) — 주택/ETF 벤치마크 비교·KB Land/Alpaca 지수 시세 수집만 담당(계좌·Toss 통계·백테스트·포트폴리오·summary/equity-curve/cycles는 `com.kista.trading.stats` 소유). "domain"·"usecase"·"port"·"event"·"schedule" 5개 NamedInterface, service·adapter.in.web·adapter.out.*은 internal. root `StatsController`는 housing/ETF 벤치마크 비교 6개 라우트만 소유
  domain/model/       ← 벤치마크 11타입(BenchmarkAssetType/BenchmarkScope/CurrentExchangeRate/EtfBenchmarkSymbol/HousingBenchmarkComparison/HousingBenchmarkPoint/HousingBenchmarkPrice/HousingBenchmarkRegion/HousingPriceIndex/IndexPrice/PerformanceComparisonSummary) + own-type InvestmentPoint/BenchmarkGranularity/StrategyRef(`Strategy`의 id/type/ticker 3필드 narrowing — 과거 trading own-type `StrategyRef`와 이름만 같은 별개 타입) — "domain"
  application/usecase/ ← UserStatsUseCase(벤치마크 비교 6개 메서드)/FetchHousingBenchmarkUseCase/FetchHousingPriceIndexUseCase/SyncMarketIndexPricesUseCase
  application/port/output/ ← HousingBenchmarkFeedPort/HousingBenchmarkPricePort/HousingPriceIndexPort/IndexPriceFeedPort/IndexPricePort/InvestmentPointsPort(trading-core 내부 API 호출 — `Result` 시그니처가 own-type). `HistoricalCandlePort`는 sharedkernel 소유, 구현체 `AlpacaIndexPriceAdapter`만 이 모듈에 남음
  application/event/  ← StatsAlertRaisedEvent(String message) — notify `StatsAlertNotifier`가 구독. "event"
  application/service/ ← internal — StatsService(벤치마크 비교 6개)/StatsResultCache(벤치마크 10분 인메모리 TTL 캐시, 단일 인스턴스 전제 — 다중 인스턴스 시 인스턴스별 캐시가 최대 TTL만큼 상이 가능)/HousingBenchmarkComparisonBuilder(Spring·포트 비의존 순수 계산 — 정규화 비교 조립, ETF 비교에도 재사용. TWR 계산은 trading 소유 `MonthlyReturnCalculator`가 담당하고 결과 InvestmentPoint만 전달받음)/HousingBenchmarkService/HousingPriceIndexService/MarketIndexPriceSyncService(수집 실패 시 `StatsAlertRaisedEvent` 발행)
                         getHousingBenchmarkComparison: currentExchangeRate는 요청마다 실시간 조회하는 정보성 필드일 뿐 수익률·공통월·summary에는 미반영(조회 실패 시 null, 200 정상) — 투자(USD)·벤치마크(HOUSING=KRW/ETF=USD) 현지통화 그대로 비교, 환율 변환 없음
  adapter/in/web/     ← internal — StatsController + openapi/HousingBenchmarkOpenApiCustomizer + dto/ 5종
  adapter/in/schedule/ ← KbLandHousingBenchmarkScheduler/KbLandPriceIndexScheduler/MarketIndexPriceSyncScheduler — `com.kista.web.AdminSchedulerController`가 KbLand 2개를 수동 트리거용으로 주입(공개 "schedule"). MarketIndexPriceSyncScheduler는 비거래일에도 Alpaca 빈 배열 반환으로 무해한 no-op이라 요일 조건 없음
  adapter/out/alpaca/  ← AlpacaIndexPriceAdapter/AlpacaConfig/AlpacaProperties (빈 이름 `alpacaRestClient`)
  adapter/out/kbland/  ← KbLandHousingBenchmarkAdapter/KbLandConfig/KbLandProperties (아파트 5분위 매매평균가격(월간) + 주간 매매가격지수)
  adapter/out/internal/ ← InvestmentPointsHttpAdapter(trading-core 내부 API)
  adapter/out/persistence/housingbenchmark/ ← HousingBenchmarkPriceEntity/HousingPriceIndexEntity + JpaRepository + PersistenceAdapter 6개
  adapter/out/persistence/marketindex/ ← MarketIndexPriceEntity + JpaRepository + PersistenceAdapter 3개
