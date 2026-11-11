# 11. 브로커 API 적합성 조사 (메리츠·토스 공식 스펙 대 현재 어댑터)

조사일 2026-11-12, 읽기 전용. 근거 문서: 메리츠 `meritz_openapi_spec_20261112.json`(OAuth 2 + 계좌 6 + 국내 30 + 해외 35 + 실시간 14), 토스 `openapi.json` v1.2.28(`/Users/phs/Downloads/toss/*`는 md5 동일한 전체 스펙 사본 12개, `conditionalOrderHistory`만 크기가 다르다). 공식 스펙 인용은 "스펙", 운영 실측은 `docs/agents/*-api.md`의 기록이다. 코드 경로 접두어 `B/` = `trading-core/src/main/java/com/kista/tradingcore/broker/`, `T/` = `.../tradingcore/trading/application/service/`.

범례: ✔ 일치 / △ 부분 일치·주의 / ✘ 불일치·의심 / ? 문서로 확인 불가.

## 1. KISTA 필요 기능 목록 (포트 전수)

`BrokerRouter`(`B/application/service/BrokerRouter.java`)가 공통 6개 포트를 계좌 브로커로 위임한다. 현재가·전일종가·확정종가는 관리자 시세(`AdminMarketPricePort`, Toss 관리자 키 → KIS 관리자) 우선, 누락분만 계좌 어댑터.

### 1-1. 계좌 브로커 공통 6포트 (`BrokerCapabilitiesPort`)

| 포트.메서드 | 시그니처 | 호출처 (file:line) | 용도 | 상태 |
|---|---|---|---|---|
| PortfolioPort.getPresentBalance | `(BrokerAccountRef) → PresentBalanceResult` | `stats/application/service/AccountStatisticsService.java:48` | 계좌 대시보드(보유 종목+예수금 요약) | 사용 |
| MarginPort.getUsdBuyableAmount | `(BrokerAccountRef) → BigDecimal` | `T/support/UnassignedCashCalculator.java:65`, `T/TradingPreviewService.java:223` | 매수 가능 USD(미배정 현금·미리보기) | 사용 |
| MarginPort.getMargin | `(BrokerAccountRef) → List<MarginItem>` | 없음(어댑터·라우터 위임만, 테스트 외 호출 0) | — | **미사용** |
| SellableQuantityPort.getSellableQuantity | `(ticker, ref) → SellableQuantity` | `T/TradingPreviewService.java:253` | 수동 SELL 미리보기 검증(보유 수량) | 사용(1곳) |
| BrokerOrderCorrectionPort.place | `(OrderInstruction, ref) → OrderResult` | `T/OrderSubmitter.java:65`, `T/OrderSubmissionReconciler.java:78` | 주문 접수(LOC·MOC·LIMIT·MOO) | 사용 |
| BrokerOrderCorrectionPort.cancel | `(CancelInstruction, ref) → CancelOutcome` | `T/OrderSubmitter.java:131`, `T/OrderCancelService.java:119,200`, `T/TradingReporter.java:98` | 주문 취소(마감 전 잔여 PLACED 명시 취소 포함) | 사용 |
| ExecutionPort.getExecutions | `(from, to, ticker, ref) → List<Execution>` | `T/TradingReporter.java:58`, `T/CancelledOrderFillRecorder.java:38` | 마감 체결 조회·원장 기록 | 사용 |
| BrokerPricePort.getPrice | `(ticker, ref) → BigDecimal` | `T/TradingPriceFetcher.java:157`, `T/VrReconfigureService.java:99` | 현재가 단건 | 사용 |
| BrokerPricePort.getPrices | `(List, ref) → Map` | `T/TradingPriceFetcher.java:156`, `T/AccountLedgerTotalsService.java:56`, `stats/.../StatisticsController.java:88`(AccountStatisticsService 경유) | 현재가 일괄 | 사용 |
| BrokerPricePort.getPrevClose | `(ticker, ref) → BigDecimal` | `T/TradingPriceFetcher.java:67,139`, `T/InfiniteMinSeedGuard.java:31`, `T/support/StrategyHistoryQueryService.java:78`, `T/StrategyCreationService.java:159` | 전일 종가(주문가 산정 기준) | 사용 |
| BrokerPricePort.getPrevCloses | `(List, ref) → Map` | `T/TradingPriceFetcher.java:66,138`, `stats/.../AccountStatisticsService.java:96` | 전일 종가 일괄 | 사용 |
| BrokerPricePort.getClosingPrice | `(ticker, tradeDate, ref) → BigDecimal` | `T/TradingPriceFetcher.java:147`, `T/VrCycleRolloverService.java:84` | 정규장 확정 종가(마감 리포트·VR 롤오버) | 사용 |
| BrokerPricePort.getClosingPrices | `(List, tradeDate, ref) → Map` | `T/TradingPriceFetcher.java:146` | 확정 종가 일괄 | 사용 |
| BrokerPricePort.getPriceSnapshot / getPriceSnapshots | `→ PriceSnapshot(current, prevClose)` | 없음(테스트의 `never()` 검증만) | — | **미사용**(두 포트 메서드와 모든 어댑터 구현·`PriceSnapshot` 타입) |

### 1-2. 통계·시세 capability (`BrokerStatisticsPort` = Toss 전용)

| 포트.메서드 | 호출처 | 용도 | 상태 |
|---|---|---|---|
| CandlePort.getLatestCandles(symbol, interval, count) | `B/adapter/in/web/CandleInternalController.java:28` ← 루트 `CandleQueryHttpAdapter.java:34` ← `MarketHolidayController:/candles` | UI 캔들 차트 | 사용(HTTP 경유) |
| CandlePort.getCandles(symbol, interval, from, to) | `CandleInternalController.java:37` ← 루트 `CandleQueryHttpAdapter.java:48` | UI 기간 캔들 | 사용 |
| CandlePort.getDailyCandlesBefore(symbol, beforeUsSession, count) | `T/DcaPlanInputCollector.java:142` | DCA 지표용 일봉(코인 DCA 경로가 아니라 주식 DCA) | 사용 |
| ExchangeRatePort.getExchangeRate() | `stats/application/service/BrokerStatisticsService.java:23` ← 루트 `CurrentExchangeRateHttpAdapter.java:26` | 벤치마크 비교의 현재 환율 | 사용 |

### 1-3. 기타 브로커 포트

| 포트.메서드 | 호출처 | 용도 | 상태 |
|---|---|---|---|
| BrokerConnectionTestPort.verifyCredentials | `account/application/service/AccountService.java:204` | 키 검증(토큰 발급 시도) | 사용 |
| BrokerConnectionTestPort.verifyAccount | `AccountService.java:95,147` | 계좌 등록·키 교체 시 계좌 검증 | 사용 |
| AccountTokenEvictionPort.evict | `AccountService.java:168,189`, `AccountDeletionWriter.java:26` | 키 교체·삭제 시 토큰 캐시 제거 | 사용 |
| AdminMarketPricePort.getPrices / getPrevCloses / getClosingPrices | `BrokerRouter`만 | 관리자 키 시세 1단계 | 사용 |
| BrokerTokenCachePort(4메서드) | 메리츠 `DbTokenCoordinator`(KIS와 공용) | broker_tokens DB 캐시 | 사용(메리츠가 KIS 삭제 후 유일 소비자가 됨) |
| MockSimulationDataPort | Mock 어댑터 전용 | 모의 체결 시뮬레이션 | 사용(Mock) |
| CryptoExchangePort | 코인 DCA(바이낸스·업비트) | 범위 밖 | — |

요약: 주식 경로 호출 가능 기능은 **조회 7종(잔고요약·매수가능USD·보유수량·체결·현재가·전일종가·확정종가) + 주문 2종(place·cancel) + 계좌검증 2종**이다. 미사용 포트 메서드는 `getMargin`, `getPriceSnapshot(s)` 3개(+`MarginItem`·`PriceSnapshot` 타입, 각 어댑터 구현).

(이하 2~7절은 작성 중 — 절 단위로 추가한다)
