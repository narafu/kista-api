## com.kista.privacy (`:trading-core`)

com.kista.privacy/   ← Spring Modulith 모듈(CLOSED) — FIDA 기준 매매표(PRIVACY 전략의 전역 SSOT 매매 계획). "domain"·"port"·"usecase" 3개 NamedInterface, service·adapter internal. PRIVACY *전략 실행* 로직은 matching/trading 소유 — 이 모듈은 계획 데이터만
  domain/model/       ← FidaOrderCommand/FidaPlannedOrder/PrivacyCurrentBase/PrivacyDates/PrivacyTradeBase/PrivacyTradeBaseView/PrivacyTradeConflictException/PrivacyTradeSaveResult/PrivacyTradeValidationReport 등. `PrivacyDates.releaseDateFor()/tradeDateOf()`는 FIDA 발행일↔거래일 업무 규칙 헬퍼(시간대 변환 아님)
  application/port/output/ ← PrivacyTradePort
  application/usecase/ ← PrivacyUseCase(FidaOrderController)/PrivacyTradeValidationUseCase(TradingOpenScheduler)
  application/service/ ← internal — PrivacyService(notify 직접 호출 대신 sharedkernel `PrivacyAlertRaisedEvent` 발행)/PrivacyTradeValidationService
  adapter/in/web/     ← internal — FidaOrderController(`POST /api/internal/fida-orders`)/PrivacyInternalQueryController(`GET /api/internal/privacy/trade-bases`)/PrivacyBaseInternalController(`GET|PATCH .../trade-bases/{baseId}`, `PATCH .../orders/{orderId}` — 관리자 수동 보정) + dto/FidaOrderResponse
  adapter/out/persistence/ ← PrivacyTradeBaseEntity + PrivacyTradeBaseOrderEntity + JpaRepository + PrivacyTradePersistenceAdapter

### PRIVACY 전략 패턴 (기준 매매표)
- `privacy_trade_bases` (`PrivacyTradeBaseEntity`): 전역 SSOT — 모든 PRIVACY 계좌가 공유, **account_id 없음**
  - `(release_date, ticker)` UNIQUE (`uq_privacy_trade_bases_release_date_ticker`) — 하루에 종목당 기준 매매표 1건
  - `updated_at` 없음 — `BaseCreatedAtEntity` 상속 (`createdAt`만)
- `privacy_trade_base_orders` (`PrivacyTradeBaseOrderEntity`): 기준 매매표 1행에 대한 계획 주문 세트 (direction/orderType/quantity/price)
  - direction/orderType은 `sharedkernel.OrderDirection`{BUY/SELL} / `OrderType`{LOC/MOC/LIMIT} — VARCHAR + `@Enumerated(STRING)`
  - 저장 순서: **BUY → SELL**, BUY는 price **내림차순**, SELL은 price **오름차순** — `PrivacyTradePersistenceAdapter` 정렬 처리
- FIDA 수신 흐름: `(tradeDate, ticker)` 없음 → 201 / 내용 동일 → 200(멱등) / 내용 다름 → `PrivacyTradeConflictException` → 409
- 스케쥴러: `StrategyType.PRIVACY` → `PrivacyCycleOrderStrategy.plan()` → `PrivacyStrategy.buildOrders()` (`CycleOrderComputer`가 전략별 분기, 스케쥴러 배치 흐름 상세는 `docs/agents/workflow.md`)
