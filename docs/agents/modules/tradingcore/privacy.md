## com.kista.tradingcore.privacy (`:trading-core`)

com.kista.tradingcore.privacy/   ← Spring Modulith 모듈(CLOSED) — FIDA 기준 매매표(PRIVACY 전략의 전역 SSOT 매매 계획). "domain"·"port"·"usecase"·"event" 4개 NamedInterface, service·adapter internal. PRIVACY *전략 실행* 로직은 matching/trading 소유 — 이 모듈은 계획 데이터만
  domain/model/       ← FidaOrderCommand/FidaPlannedOrder/PrivacyCurrentBase/PrivacyDates/PrivacyTradeBase/PrivacyTradeBaseView/PrivacyTradeConflictException/PrivacyTradeSaveResult/PrivacyTradeValidationReport 등. `PrivacyDates.releaseDateFor()/tradeDateOf()`는 FIDA 발행일↔거래일 업무 규칙 헬퍼(시간대 변환 아님). `PrivacyTradeBase.toPlan()`은 matching 커널 입력 `matching.domain.model.PrivacyPlan`(holdings·currentCycleStart·trades)으로 변환한다 — 데이터 소유자가 커널 계약에 맞춰 주는 방향이라 privacy → matching("kernel") 단방향 엣지이고 matching은 privacy를 참조하지 않는다. 호출부: trading `CycleOrderComputer`/`CycleRotationService`, tradingcore.stats `BacktestEngine`(null-safe 삼항으로 `toPlan()` 호출)
  application/port/output/ ← PrivacyTradePort
  application/usecase/ ← PrivacyUseCase(FidaOrderController)/PrivacyTradeValidationUseCase(TradingOpenScheduler)
  application/service/ ← internal — PrivacyService(notify 직접 호출 대신 `application/event/PrivacyAlertRaisedEvent`("event", tradingcore.notify가 소비) 발행)/PrivacyTradeValidationService
  adapter/in/web/     ← internal — FidaOrderController(`POST /api/internal/fida-orders`)/PrivacyInternalQueryController(`GET /api/internal/privacy/trade-bases`)/PrivacyBaseInternalController(`GET|PATCH .../trade-bases/{baseId}`, `POST .../orders`, `PATCH|DELETE .../orders/{orderId}` — 관리자 수동 보정)/PrivacyContractMapper(도메인↔contract 매핑)
  adapter/out/persistence/ ← PrivacyTradeBaseEntity + PrivacyTradeBaseOrderEntity + JpaRepository + PrivacyTradePersistenceAdapter

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trade_bases` (`PrivacyTradeBaseEntity`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음**
  - `(release_date, ticker)` UNIQUE (`uq_privacy_trade_bases_release_date_ticker`) — 하루에 종목당 기준 매매표 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trade_base_orders` (`PrivacyTradeBaseOrderEntity`): 기준 매매표 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - direction/orderType은 `sharedkernel.OrderDirection`{BUY/SELL} / `OrderType`{LOC/MOC/LIMIT} — VARCHAR + `@Enumerated(STRING)`
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
- 조회: `findTodayTrade(거래일)`은 발행일 == `releaseDateFor(거래일)` 정확 일치만 반환(없으면 empty — 미래 기준표를 끌어오지 않는다). `findSeedPreviewBase`만 `>=` 유지
- FIDA 수신 흐름: `(tradeDate, ticker)` 없음 → 201 / 내용 동일 → 200(멱등) / 내용 다름 → `PrivacyTradeConflictException` → 409
- 스케쥴러: `StrategyType.PRIVACY` → `PrivacyCycleOrderStrategy.plan()` → `PrivacyStrategy.buildOrders(balance, initialUsdDeposit, PrivacyPlan)` (`CycleOrderComputer`가 전략별 분기, 스케쥴러 배치 흐름 상세는 `docs/agents/workflow-batch.md`)

### 보유 초과·부족분 처리 (의도된 동작)
- 목표 보유량과 실제 보유량의 차이(diff)는 BUY 수량 조정으로만 흡수한다. 부족분은 가장 싼 BUY에 더하고, 초과분은 가장 비싼 BUY부터 줄인다. BUY가 없으면 조정하지 않는다. SELL은 diff로 늘리지 않는다. 실제 보유량을 넘는 SELL은 `capSellQuantitiesToHoldings`가 별도로 잘라낸다(SELL cap은 이 흡수와 별도로 동작한다).
- 근거:
  - PRIVACY는 FIDA 매매표를 따르는 전략이라, 매매표에 없는 매도를 내면 신호에서 벗어나고 불필요한 거래·세금이 생긴다.
  - 초과는 주로 수동 매수·반올림에서 생기며 BUY 감액과 매매표의 다음 SELL로 자연 해소된다.
  - 과매도 위험은 SELL cap이 막는다.
- 고정 테스트: `PrivacyStrategyTest`의 `decrease*`, `noBuyTrades`, `sellCap*`.
