# BuyOrderPriceCapper 보정 주문

배치 실행 순서는 workflow-batch.md, 원장 상한은 workflow-ledger-price.md.

### BuyOrderPriceCapper 보정 주문

INFINITE/PRIVACY/VR 세 전략 모두 대상이며, 단일 진입점 `BuyOrderPriceCapper.capIfNeeded(type, atOpen, ...)`가 `type`(`StrategyType`: INFINITE/PRIVACY/VR)과 `atOpen`(AT_CLOSE/AT_OPEN 스코프) 두 파라미터로 처리한다 — AT_OPEN 스코프(`atOpen=true`)는 `findAtOpenPlannedByCycleAndDate`로 AT_OPEN PLANNED만 조회해 동일 사이클의 미도래 AT_CLOSE PLANNED를 건드리지 않는다. `capIfNeeded` 내부는 `cycleOrderStrategies.of(type)`으로 얻은 `CycleOrderStrategy`의 `capBuyOrders(buyOrders, cap, position, vrPosition, ticker, tradeDate)`(캡 초과 BUY 재산정 — INFINITE/VR은 사다리 전체 재구성, PRIVACY는 개별 가격 치환)와 `capsIndividualOrders()`(true=PRIVACY 개별 취소·재저장, false=INFINITE/VR 전체 취소·재저장)로 다형성 분기한다. AT_CLOSE 접수(`TradingOrderExecutor.placeOrders()`)와 AT_OPEN 접수(`placeAtOpenOrders()`) 양쪽에서 `cycleOrderStrategies.of(strategy.type()).needsCapCheck(position, vrPosition)` skip 가드는 호출측 `TradingOrderExecutor.applyCap`에 있다(`capIfNeeded`가 `@Transactional`이라 내부에 두면 skip 케이스마다 빈 트랜잭션이 열리기 때문).

#### INFINITE 전략 (전후반 공통, StrategyType.INFINITE)
- 신규 후보는 `prepareForAllocation`에서 cap 후 base BUY 재산정과 correction BUY 생성을 먼저 수행하며, 이 최종 BUY 총액이 원장 상한 판정 입력이 된다. 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.05`(`PriceCapPolicy`) 초과 시 가격 캡 후 수량 재산정 (`capIfNeeded(StrategyType.INFINITE, ...)`) — 재산정·보정 로직 자체는 `InfiniteCycleOrderStrategy.capBuyOrders()`가 `InfiniteStrategy.buildCappedBuyOrders()`에 위임 (아래 `computeEarlyBuys`/`computeLateBuys`/`CORRECTION_ORDER_COUNT`는 InfiniteStrategy 내부 심볼)
- 전반(buyOrders 2건): `computeEarlyBuys` — cappedAvg/cappedRef 기준 buy①② 재산정, 동가 시 병합
- 후반(buyOrders 1건): `computeLateBuys` — cappedPrice 기준 단일 LOC 수량 재산정
- **보정 주문 (전후반 공통)**: cap 경로 전용이 아니라 `InfiniteStrategy.buildOrders`가 일반모드 BUY(base) 바로 뒤에 상시 붙이고(isFinalRound·리버스 제외), cap 재산정 시 base와 함께 다시 계산한다. `CORRECTION_ORDER_COUNT`(=3)회 LOC 1주 추가
  - 가격 = `K / (누적수량 + 1)` (FLOOR, scale=2) — `buildOrders`에서도 `trimCorrectionsToBudget`을 적용한다 — 매 회 직전까지 추가된 주문 수량 합산 기준
  - 누적수량이 0이면 해당 회차 skip
  - 접수는 보정 주문을 마지막에 한다(`TradingOrderExecutor.placeEach`가 `INFINITE_CORRECTION_*`를 뒤로 안정 정렬) — 증권사는 접수 시 지정가 × 수량 전체를 주문가능금액에서 묶으므로(체결은 단위금액 안에서 끝나도) 예수금이 모자라면 거절이 보정에만 생기고 base는 접수된다. 미리보기 `accountCashCheck.correctionBuyUsd`는 `requiredBuyUsd` 중 보정 주문 지정가 합계(신규 + 저장된 PLANNED, SUBMITTING 제외)다
  - 보정 합계가 원장 예수금(`position.usdDeposit()`)을 넘으면 `InfiniteStrategy.trimCorrectionsToBudget`이 뒤쪽 보정(03→02→01)부터 생략 — base(수량·가격)는 불변, 지정가 합계가 원장 밖이면 원장 상한 판정이 BUY 전체를 매일 거절해 잔고가 그대로 남고 리버스모드(isFinalRound) 진입도 못 하는 교착이 되기 때문
  - `StrategyLedgerBudget.fit`은 원장 한도가 전체 BUY를 못 담을 때 `CycleOrderStrategy.fitBuysToBudget`(호출자는 `StrategyLedgerBudget` 하나, DCA override 없음)으로 1회 축소(INFINITE=보정 생략, VR=싼 단부터 rung 단위 절단·병합 단 수량 축소, PRIVACY=축소 없음)해 한도 안이면 축소안을 승인·저장하고, base조차 못 담을 때만 거절한다
  - 보정이 생략된 날은 `canSkipOrderComputation`의 correctionComplete(보정 3건 전부 존재)가 성립하지 않아 같은 날 재실행 시 재계산한다 — 같은 leg는 `excludeExisting`이 걸러 중복은 없다
  - 접수 직전 재캡(`capIfNeeded`)의 원장 가드: 재캡이 BUY 총액을 늘리지 않으면(PRIVACY 가격 치환 등) 이미 승인된 범위 안이라 그대로 반영한다. 늘리면 `capIfNeeded`가 아무것도 반영하지 않고 true를 반환하고, `TradingOrderExecutor.applyCap`이 `StrategyLedgerBudget.remaining(cycle, date)`(같은 날 저장된 PLANNED·SUBMITTING·PLACED BUY 차감 후 한도)를 예산으로 다시 호출한다 — capper가 자기 스코프 원본 PLANNED BUY를 되돌려 더하고, 예산 초과분은 `fitBuysToBudget`으로 축소하며 축소로도 못 담으면 재캡을 생략하고 기존 PLANNED를 유지한다. 증권사 live 잔고 조회는 없다. 이 구간(원장 조회→재캡 커밋)은 계좌별 JVM 락(`AccountBudgetLock`)으로 직렬화하고 접수는 락 밖에서 병렬 유지한다. 같은 락이 배치 `TradingCandidatePlanner`의 계좌별 태스크와 수동 실행 `ManualTradingService`의 판정~PLANNED 커밋도 감싼다 — 수동 실행은 세션 가드·배치 락 없이 마감(04:30)·개장(22:00) 배치와 겹칠 수 있어, 락이 없으면 같은 사이클의 판정과 재캡이 같은 한도를 이중으로 쓴다. 백테스트는 VR 사다리·bootstrap이 전략 잔고(pool) 이내로만 생성돼 한도 초과 자체가 없어 축소 훅을 타지 않는다
- 재산정 결과가 모두 비어있으면 BUY 주문 전체 제외 (log.warn)
- 재캡 멱등성: 전체 취소·재저장(`applyBatchCap`) 직전에 같은 사이클·거래일의 활성 주문(PLANNED 취소 대상 제외, SUBMITTING·PLACED)과 같은 슬롯은 `TradingOrderSlots.excludeExisting`으로 걸러 이미 접수된 leg(예: 평단가 매수①)를 다시 만들지 않는다
- INFINITE BUY는 항상 AT_CLOSE라 `capIfNeeded(StrategyType.INFINITE, atOpen=true, ...)`은 실질 no-op — 전략별 분기 대칭성 유지 목적으로만 존재

#### PRIVACY 전략 (StrategyType.PRIVACY)
- 신규 후보는 원장 상한 판정 전에 cap 초과 BUY 가격만 교체한 금액으로 한도를 검증하며, 이 단계에서는 영속화하지 않는다.
- 트리거: PLANNED BUY 주문가 중 하나라도 `currentPrice × 1.05`(`PriceCapPolicy`) 초과 시 (`capIfNeeded(StrategyType.PRIVACY, ...)`)
- **수량 재산정 없음** — cap 초과 BUY 주문가만 `currentPrice × 1.05`로 교체, 수량은 FIDA 원본 유지 (`PrivacyCycleOrderStrategy.capBuyOrders()`)
- `TradingOrderExecutor.placeOrders()`: PRIVACY는 `needsCapCheck()`가 기본값(항상 true)이라 position/vrPosition null 가드 대상이 아니며(`applyCap`의 skip 가드는 INFINITE/VR가 override한 `needsCapCheck()`에서만 실질적으로 걸림) 항상 `capIfNeeded` 호출
- `TradingService`: PRIVACY도 `startPrice = price`로 `CycleState`에 전달
- PRIVACY BUY도 항상 AT_CLOSE라 `capIfNeeded(StrategyType.PRIVACY, atOpen=true, ...)`은 실질 no-op (대칭성 유지 목적)
- `PrivacyCycleOrderStrategy.capsIndividualOrders() = true` — 값이 바뀐 행만 개별 취소·재저장(`BuyOrderPriceCapper.applyIndividualCap`), 변하지 않은 행은 DB에 그대로 둔다

#### VR 전략 (StrategyType.VR)
- bootstrap 주문(LOC+AT_CLOSE, `PriceCapPolicy.capFor(referencePrice)` = referencePrice×1.05)은 사다리 공식과 무관한 별도 산정식이라 보정 대상에서 제외한다 — BUY 중 하나라도 `OrderType.LOC`이면 이번 배치 전체를 bootstrap으로 판별해 skip(`VrCycleOrderStrategy.isVrBootstrapShaped`, private static — `capBuyOrders()` 내부에서 호출)
- 사다리(LIMIT+AT_OPEN) BUY만 보정 대상: cap 초과 시 기존 buyOrders 인자 없이 `VrPosition`+cap만으로 `VrStrategy.buildCappedBuyOrders()`가 사다리 전체를 자기완결적으로 재생성 — poolLimit·pool 한도 내로 자연 재수렴
- AT_CLOSE 스코프(`capIfNeeded(StrategyType.VR, atOpen=false, ...)`)는 사이클+거래일 전체 PLANNED BUY를, AT_OPEN 스코프(`atOpen=true`)는 `findAtOpenPlannedByCycleAndDate`로 사다리 BUY만 조회 — bootstrap이 같은 사이클에 공존해도 스코프 밖이라 자연히 제외됨
- `VrCycleOrderStrategy.capsIndividualOrders()`는 기본값(false) 유지 — 사다리 전체 취소·재저장(`BuyOrderPriceCapper.applyBatchCap`)
