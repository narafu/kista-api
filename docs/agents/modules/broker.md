## com.kista.broker (`:trading-core`)

com.kista.broker/    ← Spring Modulith 모듈(CLOSED) — KIS/Toss/Mock 증권사 연동. "domain"·"port"·"application" 3개 NamedInterface 공개, adapter/out은 비공개. **broker는 `Account`를 전혀 참조하지 않는다** — 자체 타입만 사용, `Account→BrokerAccountRef` 변환은 `Account.toBrokerRef()` 1곳이 전담
  domain/model/       ← Currency/DailyTransaction*/Execution/MarginItem/PresentBalanceResult 등 공통 값 객체 + broker 단독 소유 타입(쌍둥이 없음): PriceSnapshot(`BrokerPricePort` 반환 + `prevCloseOrNull`)/BrokerBalance(LiveBalancePort 반환 — trading이 자신의 AccountBalance 구성에 사용)/OrderInstruction·OrderResult·CancelInstruction(`BrokerOrderCorrectionPort.place()/cancel()`)/PlacedOrderView·PositionView·StrategyRefLite(MockSimulationDataPort 반환용 얇은 뷰)/BrokerAccountRef(id/appKey/secretKey/accountNo/brokerAccountCode + broker — Account 전체를 포트에 노출하지 않기 위한 자격증명 투영)/SellableQuantity/BrokerCredentialException·BrokerRateLimitException(KIS/Toss 인증 실패 시 KisAuthApi/TossAuthApi가 throw, 422/429 매핑)
  domain/model/kis/·toss/ ← KIS/Toss 전용 도메인 모델 — domain/model과 함께 "domain"으로 병합 공개
  application/port/output/ ← 브로커 Capability `*Port` 16개 — 공통 7개(KIS/Toss/Mock 모두 구현) + BrokerAdapterPort(라우팅 마커) + BrokerConnectionTestPort(*AuthApi가 구현 — 계좌 등록 전 검증이라 Account 없이 broker enum으로 라우팅, verifyAccount→brokerAccountCode(KIS: null, Toss: accountSeq)) + BrokerTokenCachePort(KisTokenPersistenceAdapter 구현) + MockSimulationDataPort(MockBrokerAdapter 전용 — 데이터를 필요로 하는 broker가 정의하고 가진 trading이 `MockSimulationDataAdapter`로 구현하는 포트 역전) + Toss 전용 5개. 10개 포트는 `Account` 대신 `BrokerAccountRef`를 시그니처에 사용
  application/service/ ← BrokerAdapterRegistry(public, `require(BrokerAccountRef, Port.class)`/`find()`)/BrokerConnectionTesters(`of(Broker)`)/BrokerCallGuard — "application"
  adapter/in/web/     ← internal — CandleInternalController(`/api/internal/broker/candles/latest`, X-Internal-Token) — root market이 `CandlePort`/`TossCandle`을 참조하지 않도록 하는 내부 엔드포인트, own-type `CandleResponse`(컨트롤러 내부 record)로 매핑해 반환, `market.adapter.out.internal.CandleQueryHttpAdapter`가 소비
  adapter/out/kis/    ← KisHttpClient(공통 헤더 + executeWithRetry: 401 시 거절된 토큰을 조건부 무효화 후 최신 토큰으로 1회 재시도)/KisAuthApi/KisOrderApi/KisPriceApi/KisTradingApi/KisResponseParser/KisExchangeRegistry/KisConfig/KisTokenCoordinator/KisBrokerAdapter
  adapter/out/toss/   ← TossHttpClient/TossConfig/TossAuthApi/TossCandleApi/TossHoldingsApi/TossOrderApi/TossPriceApi/TossMarketApi/TossResponseParser/TossResult/TossMarketCalendarCache/TossStockInfoCache/UsdKrwRateCache
                        TossDistributedTokenCoordinator + TossRedisTokenStore(계좌·관리자 Redis canonical token; TTL owner lease+원자적 generation INCR; generation counter/canonical generation 비교 Lua fencing CAS; owner-safe unlock; SHA-256 최근 발급 fingerprint 2초 TTL) — 관리자 토큰은 Account가 없어 TokenCoordinator 범위 밖 별도 public 메서드(TossTokenStore)
                        TossBrokerAdapter(공통 7개 + Toss 전용 5개 Port 구현)
  adapter/out/mock/   ← MockBrokerAdapter — 증권사 API 호출 없이 DB(cycle_position/orders) 기반 잔고·체결 시뮬레이션. 시세는 `broker.adapter.out.marketdata.CommonMarketPriceFeed` 경유. `getLiveBalance()`의 usdDeposit은 계좌 내 전략 전체 합산값(TradingOrderBudgetAllocator가 대표 전략 1개로 계좌 전체 BUY 예산을 판단하는 계약에 맞춤 — 전략별 값을 반환하면 다른 전략 잔고로 오판정)
  adapter/out/internal/ ← TokenCoordinator(계좌 토큰 obtain/recover 공통 계약 — adapter 내부 인터페이스, 폴리모픽 주입 없음)/DoubleCheckedTokenCache(KisTokenCoordinator 전용 — 1차 조회 → miss 시 계좌별 락 → 2차 double-check → 신규 발급; `BrokerTokenCachePort.saveToken`/`invalidateToken`은 REQUIRES_NEW로 락 해제 전 독립 커밋)/PrevCloseCache(현재 사용처 TossPriceApi뿐)
  adapter/out/persistence/ ← KisTokenEntity + KisTokenJpaRepository + KisTokenPersistenceAdapter

### BrokerAdapter Registry 패턴
- `com.kista.broker.application.service.BrokerAdapterRegistry`: `Map<Broker, BrokerAdapterPort>`(sharedkernel.Broker) — Spring이 `List<BrokerAdapterPort>` 자동 수집
- `registry.require(account, XxxPort.class)` — 브로커가 Capability 미지원 시 `IllegalArgumentException` → GlobalExceptionHandler 400
- `registry.find(account, XxxPort.class)` — `Optional<T>`, 미지원 시 `Optional.empty()`
- 신규 브로커 추가: `BrokerAdapterPort` 구현체 1개만 추가 — Router/switch 수정 불필요
- `Account.isToss()` 삭제됨 — 브로커 분기 필요 시 `account.broker() == Broker.TOSS` 직접 비교
- `BrokerAdapterRegistry`는 `public` — 여러 모듈에서 "application" NamedInterface로 소비하는 예외적 공개 접근자
