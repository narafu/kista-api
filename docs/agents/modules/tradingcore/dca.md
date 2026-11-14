# DCA 실행 (주식·코인)

## 회차 실행 (다중 규칙)
- DCA 전략 1개 = 규칙 1~10개(`DcaStrategyConfig.rules`). 주문 leg는 `"DCA_" + ruleKey`(서버 발급 UUID 문자열)이고 같은 시점에 발동한 규칙은 각자 주문한다. 판정·주문 생성은 주식·코인 공통 `DcaCycleOrderStrategy.decideRule`이고, 조건은 `matching.DcaConditions.check`를 공유한다.
- 매수 기준가 = max(전일 종가, 배치 시작 시점 현재가)(수량·캡 지정가·`reference_price`가 이 값을 쓴다). 조건 평가는 전일 종가. 마지막 봉이 직전 US 개장일이 아니면 `DATA_UNAVAILABLE`.
- 전 전략이 원장만 본다 — 브로커 잔고·판매가능수량은 조회하지 않는다(MOCK 포함). 상한 계산식은 `workflow-ledger-price.md` "전략별 원장 상한"이 SSOT. 코인은 거래소 잔고 없이 원장에서 열린 주문·같은 틱 지출을 뺀다.
- `CONDITION_NOT_MET`은 정상 대기라 이력(`dca_execution` UNIQUE 전략+규칙+거래일)만 남기고 이벤트를 발행하지 않는다. 그 외 skip은 이력 삽입에 성공했을 때만 `DcaRoundSkippedEvent`를 발행한다.
- 등록 시 `ruleKey`를 보내면 400. 수정 시 기존 키 재전송 = 유지(같은 날 재발동 방지), 생략 = 신규, 현재 버전에 없는 키 = 400.
- 시장 규칙(코인: MARKET만·`executionHour` 필수 / 주식: MOO|MOC만·평일만)은 `DcaRule.requireCompatible(market)`이 SSOT. 계좌 시장 ≠ 종목 시장이면 거부, 코인 계좌는 DCA만. 런타임 정책(기본 비활성)이 켜져 있어야 등록된다.
- 중간부터 시작: `initialHoldings`·`initialAvgPrice`·`initialDeposit`(계좌 통화, null=0)을 받고 `start_amount` = 예수금 + 평단×보유(통계 원금의 기초 → stats.md). 일반 수정 PUT은 DCA `newSeed`를 거부하고 DCA 설정 PUT은 예수금을 받지 않는다 — 진행 중 증감은 `capital-injection`/`capital-withdrawal`(예수금만, 주식 수량 지정 시 400 → trading.md).
- 주식 개장·마감 배치(`BatchContextFactory`)는 US_STOCK 계좌 전략만 담고 코인은 조용히 제외한다(코인은 별도 스케줄러). 수동 실행은 DCA를 거부하고, 미리보기는 주식 DCA만 `plan()`으로 포함한다(코인은 `DcaManualExecutionUnsupportedException`). 미리보기는 쓰기를 하지 않으며 당일 이미 판정·주문된 규칙은 후보에서 빼고 `todayOrders`로만 노출한다(같은 규칙을 같은 날 두 번 판정하지 않기 위함).
- 개장 배치는 22:00 KST. 개장 전 접수는 `Broker.supportsOpenAuctionOrders()` 브로커만 대상이고, 접수 후 체크포인트를 STARTED로 되돌리며 이 단계 실패는 격리한다.
- 수량: `orders.quantity/filled_quantity`·`dca_execution.quantity`·`cycle_position.holdings`는 numeric(20,8), 도메인은 BigDecimal(`Quantities.normalize`). `PlannedOrder`·매칭 공식·privacy 표는 int이고 주식 경로는 `intValueExact()`/`AccountBalance.wholeShares()`(소수면 ArithmeticException)로 정수화한다. `StrategyDetail.currentHoldings`는 Integer라 CRYPTO_KRW DCA는 null — 코인에 `wholeShares()`를 호출하지 않는다.

## 자기 주문 체결 원칙 (`TradingReporter`, 전 전략)
- 원장·리포트 leg는 `externalOrderId`가 우리 주문에 속한 체결만 쓴다 = mainOrders ∪ 같은 사이클·거래일의 CANCELLED 주문(취소 전 부분 체결). `ManualTradeCorrectionService`의 filledManual FILLED 행은 cycle_position을 직접 쓰므로 제외하고, `externalOrderId`가 null인 체결은 우리 것이 아니다. 사용자가 앱에서 직접 낸 외부 체결은 수동 정정으로만 원장에 반영된다.
- 체결 금액 집계(`sumFilledBuyAmountByCycleId`·`findFilledNetInvestedByCycleIds` 등)는 status가 아니라 `filled_quantity > 0`으로 판정한다. 취소 경로(`OrderCancelService`)는 체결을 기록하지 않으므로, 리포트가 돌지 않는 취소(전략 삭제·일시정지)는 원장에도 반영되지 않아 일관된다.

## 코인 DCA 실행 (업비트·바이낸스)
- 진입 `CryptoDcaScheduler`(cron `0 */10 * * * *` KST, 락 `crypto-dca` 9분) → 계좌별 `CryptoOrderSynchronizer.sync`(기존 주문 동기화) 뒤 `CryptoDcaRoundExecutor.executeAccount`.
- 주문 상태 PLANNED → SUBMITTING → PLACED → FILLED/FAILED/CANCELLED, clientOrderId = 주문 UUID. **바이낸스는 clientOrderId가 열린 주문 중에서만 고유하고 체결 후 재사용이 허용돼 재전송이 곧 이중 매수다** — 접수는 재전송하지 않고(결과 불명이면 SUBMITTING 유지·다음 틱 조회), 조회의 "없음"은 -2013(과 -2026)만 `Optional.empty()`, 5xx·-1006·-1007·I/O는 예외다(→ `vendors/binance.md` "clientOrderId 규칙"). 바이낸스 원장은 받은 자산 수수료를 뺀 순수량(`netExecutedVolume`) 기준. 체결 판정은 state와 무관하게 `executedVolume > 0`.
- 매수 = min(설정 금액, `CryptoTradingRules.affordableAmount`(원장 예수금 − 열린 BUY 주문 − 같은 틱 BUY 지출(수수료 포함 `quoteSpendFor`))). 같은 틱은 SELL 규칙을 먼저 처리하고 매도 대금을 예수금에 더하지 않는다. 매도 수량은 거래소 수량 단위로 FLOOR 후 원장 보유 − 열린 SELL − 같은 틱 앞 규칙 매도분 이하. 수수료·최소 금액·수량 단위는 `CryptoExchangePort.getTradingRules`, 등록 시 최소 금액은 `Market.minOrderAmount()`.
- 체결 확정 시 `CryptoLedgerRecorder#recordFill`이 같은 트랜잭션에서 원장을 갱신한다 — 예수금은 실제 견적 통화 지출·수령액(총체결 ± 계좌 통화 수수료 `paidFee`), 보유는 순수량. 이어 `CryptoOrderFilledEvent`를 발행한다(→ notify.md).
- `OrderCancelService`: 코인 계좌는 PLANNED 주문만 삭제하고 SUBMITTING/PLACED가 있으면 일시정지·전략·계좌 삭제를 409 `ORDER_NOT_CANCELLABLE`로 거부한다.
