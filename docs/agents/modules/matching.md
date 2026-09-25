## com.kista.matching (`:trading-core`)

com.kista.matching/  ← Spring Modulith 모듈(CLOSED) — 주문생성 커널(순수 계산). `domain/model` + `domain/strategy` 두 패키지가 `"kernel"` NamedInterface로 병합 공개. outbound 엣지는 `sharedkernel`·`privacy`뿐(`HexagonalArchitectureTest.matching_must_not_depend_on_other_modules` — PRIVACY 전략이 `FidaPlannedOrder` 등 privacy 계획 데이터를 읽어야 함). domain은 Spring 비의존 — 빈 배선은 `trading`의 `CycleStrategyBeanConfig`가 전담, `stats`의 `BacktestEngine`은 순수 도메인 원칙으로 직접 `new`
  domain/model/       ← `AccountBalance`(순수 잔고 record — `buyTotal`/`hasSufficientDepositFor`/`applyExecutions`; `Execution→Fill` 변환은 호출부 TradingReporter/AdminTradeCorrectionService/BacktestEngine이 담당)/`BootstrapPosition`/`InfinitePosition`/`PlannedOrder`(direction/orderType은 sharedkernel 참조, `OrderTiming`도 sharedkernel)/`ReverseModePosition`/`StrategyVrDetail`(`gradientAt`/`poolLimitRateAt` — VR 공식 SSOT)/`VrPosition`. 현재가+전일종가는 `broker.PriceSnapshot`을 쓴다(커널은 `BigDecimal` 스칼라만 받음)
  domain/strategy/    ← `CycleOrderStrategy` 계열(Infinite/ReverseInfinite/Privacy/VrStrategy + `*CycleOrderStrategy` 4종) 전체 — capability 패턴 SSOT(아래 "CycleOrderStrategy Capability 패턴"), `PriceCapPolicy`(매수 가격 캡 배수 SSOT + `replaceBuysPreservingOrder` — `trading.application.service.BuyOrderPriceCapper`/`stats.domain.backtest.BacktestEngine` 공용 BUY 치환 헬퍼). `PlanContext`는 `StrategyTicker ticker`만 보유 — matching↔trading 순환 방지
  adapter/in/web/     ← internal — `StrategyCapabilityInternalController`(`GET /api/internal/matching/strategy-capabilities/{type}`) + `StrategyCapabilityResponse`(own-type — root `web.dto.StrategyCapability`가 소비). root `MetaController`의 `CycleOrderStrategy` 직접 import를 이 내부 API 호출로 대체. `cycleStrategies` 빈은 여전히 `trading`의 `CycleStrategyBeanConfig`가 배선

### CycleOrderStrategy Capability 패턴
- SSOT 위치: `com.kista.matching.domain.strategy`. `CycleOrderStrategy` 인터페이스: 전략 타입별 동작(basePrice 소스, 전일종가 필요 여부, 분할수, 리버스모드 지원, 청산 시 사이클 종료 여부, 최소시드, 예산배정 우선순위, compute skip, 롤오버 판정 필요 여부, BUY 가격 캡 보정 방식 등)을 캡슐화하는 다형성 계층 — 메서드별 상세는 코드가 SSOT
  - `canSkipOrderComputation()`은 기본 false이며 INFINITE만 complete concrete leg 또는 direction-aware legacy UNKNOWN 점유를 보수적으로 판단한다
  - `priceCapMode()`: VR도 생성 시점 cap을 적용하지 않고 접수 전 `BuyOrderPriceCapper`가 `VrStrategy.buildCappedBuyOrders()`로 보정한다
- `CycleOrderStrategies`: `Map<StrategyType, CycleOrderStrategy>` 라우터 — `of(type)`으로 구현체 조회
- **프론트 capability 소비**: `GET /api/meta`의 `StrategyTypeMeta`에 capability 7필드(code/description/availableTickers/requiresPrivacyBase/tickerFixed/supportsReverseMode/divisionCounts) 직렬화 → 프론트는 `isInfinite` 휴리스틱 대신 `divisionCounts`/`requiresPrivacyBase` 직접 소비
- **최소시드 미리보기**: `GET /api/accounts/{id}/strategy-seed-preview?type=&ticker=&divisionCount=` → `StrategySeedPreviewResponse { ticker, basePrice, minSeed, skipReason }`
  - `StrategyService.strategySeedPreview()`(trading) — `BrokerAdapterRegistry`(BrokerPricePort) + `PrivacyTradePort` + `CycleOrderStrategies.minRequiredDeposit` 조합. 브로커 HTTP 호출 포함이라 `@Transactional(NOT_SUPPORTED)` (register()와 동일)
  - PRIVACY + 기준 매매표 없는 날 → `skipReason="NO_PRIVACY_BASE"` (basePrice/minSeed=null)
- **신규 전략 타입 추가 시**: `StrategyType` enum case + `CycleOrderStrategy` 구현체 1개만 추가하면 메타 capability·최소시드·UI 자동 반영

매매 공식(변경 금지 SSOT)은 이 모듈이 소비하지만 `docs/agents/modules/trading-formulas.md`에 별도 보관한다(matching/trading 양쪽 shim에서 import).
