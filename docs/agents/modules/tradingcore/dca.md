# DCA 실행

## 회차 실행 (다중 규칙)
- DCA 전략 1개 = 규칙 1~10개(`DcaStrategyConfig.rules`). 주문 leg는 `"DCA_" + ruleKey`(서버 발급 UUID 문자열)이고 같은 시점에 발동한 규칙은 각자 주문한다. 판정·주문 생성은 `DcaCycleOrderStrategy.decideRule`이고, 조건은 `matching.DcaConditions.check`를 공유한다.
- 매수 기준가 = max(전일 종가, 배치 시작 시점 현재가)(수량·캡 지정가·`reference_price`가 이 값을 쓴다). 조건 평가는 전일 종가. 마지막 봉이 직전 US 개장일이 아니면 `DATA_UNAVAILABLE`.
- 전 전략이 원장만 본다 — 브로커 잔고·판매가능수량은 조회하지 않는다(MOCK 포함). 상한 계산식은 `workflow-ledger-price.md` "전략별 원장 상한"이 SSOT.
- `CONDITION_NOT_MET`은 정상 대기라 이력(`dca_execution` UNIQUE 전략+규칙+거래일)만 남기고 이벤트를 발행하지 않는다. 그 외 skip은 이력 삽입에 성공했을 때만 `DcaRoundSkippedEvent`를 발행한다.
- 등록 시 `ruleKey`를 보내면 400. 수정 시 기존 키 재전송 = 유지(같은 날 재발동 방지), 생략 = 신규, 현재 버전에 없는 키 = 400.
- 주문유형은 MOO|MOC만, 평일만. 계좌 시장 ≠ 종목 시장이면 거부. 런타임 정책(기본 비활성)이 켜져 있어야 등록된다.
- 중간부터 시작: `initialHoldings`·`initialAvgPrice`·`initialDeposit`(계좌 통화, null=0)을 받고 `start_amount` = 예수금 + 평단×보유(통계 원금의 기초 → stats.md). 일반 수정 PUT은 DCA `newSeed`를 거부하고 DCA 설정 PUT은 예수금을 받지 않는다 — 진행 중 증감은 `capital-injection`/`capital-withdrawal`(예수금만, 주식 수량 지정 시 400 → trading.md).
- 주식 개장·마감 배치(`BatchContextFactory`)는 US_STOCK 계좌 전략만 담는다. 수동 실행은 DCA를 거부하고, 미리보기는 DCA를 `plan()`으로 포함한다. 미리보기는 쓰기를 하지 않으며 당일 이미 판정·주문된 규칙은 후보에서 빼고 `todayOrders`로만 노출한다(같은 규칙을 같은 날 두 번 판정하지 않기 위함).
- 개장 배치는 22:00 KST. 주식 DCA AT_OPEN(MOO) 주문도 다른 AT_OPEN처럼 개장 후 접수한다.
- 수량: `orders.quantity/filled_quantity`·`dca_execution.quantity`·`cycle_position.holdings`는 numeric(20,8), 도메인은 BigDecimal(`Quantities.normalize`). `PlannedOrder`·매칭 공식·privacy 표는 int이고 주식 경로는 `intValueExact()`/`AccountBalance.wholeShares()`(소수면 ArithmeticException)로 정수화한다.

## 자기 주문 체결 원칙 (`TradingReporter`, 전 전략)
- 원장·리포트 leg는 `externalOrderId`가 우리 주문에 속한 체결만 쓴다 = mainOrders ∪ 같은 사이클·거래일의 CANCELLED 주문(취소 전 부분 체결). `ManualTradeCorrectionService`의 filledManual FILLED 행은 cycle_position을 직접 쓰므로 제외하고, `externalOrderId`가 null인 체결은 우리 것이 아니다. 사용자가 앱에서 직접 낸 외부 체결은 수동 정정으로만 원장에 반영된다.
- 체결 금액 집계(`sumFilledBuyAmountByCycleId`·`findFilledNetInvestedByCycleIds` 등)는 status가 아니라 `filled_quantity > 0`으로 판정한다. 취소 경로(`OrderCancelService`)는 체결을 기록하지 않으므로, 리포트가 돌지 않는 취소(전략 삭제·일시정지)는 원장에도 반영되지 않아 일관된다.
