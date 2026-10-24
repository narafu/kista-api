# 전략별 자기 원장 상한 (계좌 예산 배정 제거) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계좌 단위 예산 배정(`TradingOrderBudgetAllocator`)을 없애고, 전략마다 자기 원장(`cycle_position.usd_deposit`) 상한만 적용한다. 실계좌 잔고 부족은 증권사 거절로 처리한다(사용자 알림만).

**Architecture:** 계획·재캡·수동 실행·재주문·미리보기가 공용 컴포넌트 `StrategyLedgerBudget` 하나로 "사이클 남은 원장 − 같은 날 사이클 BUY"를 계산한다. 축소는 기존 `CycleOrderStrategy.fitBuysToBudget`로만 한다. 증권사 잔고 부족은 `BrokerApiException`의 하위 분류로 식별한다. `OrderSubmitter`가 이를 `INSUFFICIENT_FUNDS` 결과로 돌려주면 `TradingOrderExecutor`가 전략당 1건으로 묶어 알린다. live 잔고·판매가능수량 조회와 `balanceCheckEnabled`는 계획·등록 경로에서 모두 제거한다.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JPA/Flyway(PostgreSQL), JUnit 5 + Mockito + AssertJ, Gradle 서브프로젝트(`:trading-core`, `:shared`, 루트 `:api`)

**Spec:** `docs/superpowers/specs/2026-10-24-strategy-own-ledger-budget-design.md`

## Global Constraints

- 매매 공식(`docs/agents/modules/tradingcore/trading-formulas.md`)은 변경 금지다. 유일한 예외는 Task 8의 `isFinalRound` 확장이며, 사용자가 승인했다.
- `fitBuysToBudget` 구현(INFINITE·VR·PRIVACY)은 바꾸지 않는다. 호출 예산만 바꾼다. DCA override와 `PlannedOrder.partialAllowed`는 삭제한다.
- `BrokerApiException.isRejection()`의 의미는 바꾸지 않는다. 잔고 부족은 확정 거절의 하위 분류로만 둔다.
- 잔고 부족 분류는 **BUY 주문만** 한다. SELL 거절은 관리자 알림을 유지한다.
- 분류할 코드: KIS `msg_cd = APBK0988`, Toss HTTP 422 + `error.code = insufficient-buying-power`. Meritz는 분류하지 않는다.
- `DcaShortfallPolicy` enum, DB 컬럼, API 필드, 메타 라벨은 유지한다(주식 SELL·코인 BUY에서 사용).
- `@Transactional` 안에서 외부 HTTP 호출 금지. `@Async`·`CompletableFuture` 금지.
- 주석 규칙: `//` 인라인만 쓴다. Javadoc 금지.
- root `db/migration`에서는 DROP COLUMN 금지(expand/contract). root `user_settings.balance_check_enabled`는 이번에 코드 참조만 제거하고 컬럼은 남긴다.
- trading 마이그레이션 다음 버전은 `V13`이다.
- `--tests` 필터는 서브프로젝트를 지정한다(`:trading-core:test --tests ...`, `:test --tests ...`).
- verbose 출력은 `2>&1 | grep -E "FAILED|BUILD|ERROR|Tests"`로 거른다.
- 커밋 메시지는 한글 Conventional Commit이고, author는 `narafu <narafu@kakao.com>`이다. push 금지.

## Review Focus

1. 첫 거래일이라 사이클에 `cycle_position`이 없을 때 원장은 `StrategyCycle.startAmount()`로 대체해야 한다. 0으로 보면 모든 BUY가 거절된다. → Task 3에 테스트
2. 같은 날 재실행(마감 배치 재개, 수동 실행 뒤 배치)으로 같은 사이클의 PLANNED·SUBMITTING·PLACED BUY가 이미 있으면 그 금액을 한 번만 빼야 한다. 신규 후보가 이미 저장된 슬롯을 다시 세면 이중 차감이 된다. → Task 3에 테스트
3. VR bootstrap(LOC BUY)은 `fitBuysToBudget`이 원본을 그대로 돌려준다. 원장을 넘으면 축소 없이 거절돼야 한다. → Task 3에 테스트
4. Toss 422에 다른 `error.code`가 오거나 KIS가 `APBK0988` 아닌 코드로 실패하면 지금처럼 관리자 알림 + FAILED여야 한다. → Task 1·2에 테스트
5. SELL이 `APBK0988`이나 `insufficient-sellable-quantity`로 거절되면 사용자 알림이 아니라 관리자 알림이어야 한다. → Task 2에 테스트

---

### Task 1: 증권사 잔고 부족 거절 식별

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerApiException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/kis/KisApiException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/toss/TossApiException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisOrderApi.java:63-68`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossHttpClient.java:155-176`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/kis/KisOrderApiTest.java`, `.../toss/TossHttpClientTest.java`(없으면 기존 Toss 클라이언트 테스트 파일에 추가)
- Docs: `docs/agents/kis-api.md:37`, `docs/agents/toss-api.md`, `docs/agents/meritz-api.md`

**Interfaces:**
- Produces:
  - `BrokerApiException.isInsufficientFunds(): boolean`. 확정 거절이면서 어댑터가 잔고·가능수량 부족 코드로 식별한 경우만 true다.
  - `static boolean BrokerApiException.isInsufficientFundsRejection(Throwable)`
  - `KisApiException.rejectedInsufficientFunds(String message, Throwable cause)`
  - Toss는 `TossApiException` 생성 시 플래그를 전달한다.

- [ ] **Step 1: 실패 테스트 작성**
  - `KisOrderApiTest.placeRejectedWithApbk0988IsInsufficientFunds`: `rt_cd=1, msg_cd=APBK0988` 응답이면 `KisApiException`이고, `isRejected()`와 `isInsufficientFunds()`가 둘 다 true다.
  - `KisOrderApiTest.placeRejectedWithOtherCodeIsNotInsufficientFunds`: `msg_cd=APBK0919`이면 `isRejected()`는 true, `isInsufficientFunds()`는 false다.
  - Toss: 422 + 본문 `{"error":{"code":"insufficient-buying-power","message":"주문 가능 금액이 부족합니다."}}`이면 `isInsufficientFunds()`가 true다. 422 + `insufficient-sellable-quantity`이면 false다(SELL은 분류 대상 아님). 422 + `idempotency-key-conflict`이면 false다.
- [ ] **Step 2: 실행해 실패 확인**
  Run: `./gradlew :trading-core:test --tests '*KisOrderApiTest' --tests '*TossHttpClient*' 2>&1 | grep -E "FAILED|BUILD|Tests"`
  Expected: FAIL. 컴파일 오류 또는 `isInsufficientFunds` 단언 실패.
- [ ] **Step 3: 구현**
  - `BrokerApiException`에 `private final boolean insufficientFunds` 필드와, 이 값을 받는 protected 생성자 오버로드를 추가한다. 기존 생성자는 false를 위임한다.
  - KIS는 `"APBK0988".equals(code)`면 `rejectedInsufficientFunds`를 쓴다. Toss는 `status==422 && "insufficient-buying-power".equals(errorCode)`일 때 플래그를 true로 둔다.
- [ ] **Step 4: 통과 확인** (Step 2와 같은 명령) Expected: PASS
- [ ] **Step 5: 문서 갱신**
  - `kis-api.md:37`: APBK0988은 BUY만 잔고 부족으로 분류한다는 점을 적는다.
  - `toss-api.md`: `insufficient-buying-power` 분류와 공식 출처 `openapi.tossinvest.com/openapi-docs/overview.md`를 적는다.
  - `meritz-api.md`: 잔고 부족 `rsp_cd`가 미확인이라 관리자 알림으로 남는다는 갭을 적는다.
- [ ] **Step 6: Commit** `feat(broker): 증권사 잔고 부족 거절을 확정 거절 하위 분류로 식별한다`

---

### Task 2: 잔고 부족 거절은 사용자 알림 1건으로 묶기

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/OrderSubmitter.java:28-115`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingOrderExecutor.java:42-140`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/event/InsufficientBalanceEvent.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingAlertNotifier.java:45-51`, `TradingUserNotificationAdapter.java:63-69`, `TradingUserNotificationPort.java:24`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ReorderService.java:136-172`
- Test: `OrderSubmitterTest`, `TradingOrderExecutorTest`, `ReorderServiceTest`, `TradingAlertNotifier` 기존 테스트

**Interfaces:**
- Consumes: `BrokerApiException.isInsufficientFundsRejection(Throwable)` (Task 1)
- Produces:
  - `OrderSubmitter.Outcome.INSUFFICIENT_FUNDS`(FAILED로 기록, 관리자 이벤트 없음)
  - `InsufficientBalanceEvent`에 `int failedCount, boolean partiallyPlaced` 필드 추가(기존 필드 뒤). 기존 발행처 2곳(`CycleRotationService`와, Task 3에서 사라질 planner)은 `0, false`를 넘긴다.

- [ ] **Step 1: 실패 테스트 작성**
  - `OrderSubmitterTest.buyInsufficientFundsMarksFailedWithoutAdminEvent`: BUY 주문에서 place가 `isInsufficientFunds=true` 예외를 던지면 `markFailed`가 호출되고, `TradingErrorEvent`는 발행되지 않으며, outcome은 `INSUFFICIENT_FUNDS`다.
  - `OrderSubmitterTest.sellInsufficientFundsStaysAdminRejection`: 같은 예외가 SELL 주문에서 나면 outcome은 `REJECTED`이고 `TradingErrorEvent`가 1건 발행된다.
  - `TradingOrderExecutorTest.insufficientFundsPublishesOneEventPerCall`: BUY 3건 중 1건 PLACED, 2건 `INSUFFICIENT_FUNDS`이면 `InsufficientBalanceEvent`가 정확히 1건 나가고 `failedCount=2`, `partiallyPlaced=true`다.
  - `TradingOrderExecutorTest.dcaInsufficientFundsRecordsSkipInsteadOfEvent`: 전략 타입이 DCA(`recordsRounds()`)이면 `InsufficientBalanceEvent` 없이, 실패 leg마다 `DcaExecutionRecorder.recordSkipped(..., DcaSkipReason.INSUFFICIENT_BALANCE, ...)`를 호출한다.
  - `ReorderServiceTest.immediateInsufficientFundsReturnsFailedWithoutAlert`: 응답 상태는 FAILED이고 이벤트는 0건이다.
- [ ] **Step 2: 실패 확인**
  Run: `./gradlew :trading-core:test --tests '*OrderSubmitterTest' --tests '*TradingOrderExecutorTest' --tests '*ReorderServiceTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현**
  - `OrderSubmitter.rejected()`에서 `order.direction()==BUY && isInsufficientFundsRejection(e)`면 이벤트 없이 로그를 남기고 `markFailed`를 호출한 뒤 `INSUFFICIENT_FUNDS`를 반환한다.
  - `placeEach`는 결과를 모아 반환한다. 내부 record `PlaceResult(List<Order> placed, List<Order> insufficient)`를 둔다.
  - 이벤트·skip 기록은 `placeOrders`와 `placeAtOpenOrders`가 `strategy`와 `account`로 한다. `userId`·`accountId`·`nickname`은 `TradingAccount`, ticker와 type은 `Strategy`에서 얻는다.
  - DCA skip 기록에 필요한 `BatchContext`나 ruleKey(leg `"DCA_" + ruleKey`)는 `DcaExecutionRecorder.recordSkipped`의 현재 시그니처에 맞춰 구성한다. 필요하면 `recordSkipped`에 strategy·cycle 기반 오버로드를 추가한다.
  - 알림 문구는 `failedCount`로 분기한다.
    - `failedCount > 0`(증권사 거절): "⚠️ 증권사 주문 가능 금액 부족 — {계좌} {종목} {전략} 매수 {failedCount}건 실패{partiallyPlaced ? " (일부 접수)" : ""}. 예수금 확인 바랍니다."
    - `failedCount == 0`(Task 3의 원장 상한 거절): "⚠️ 전략 원장 예수금 부족 — {계좌} {종목} {전략} 오늘 매수 주문을 내지 않았습니다."
    - 알림 어댑터 테스트에 두 문구를 모두 단언한다.
  - `ReorderService`는 `INSUFFICIENT_FUNDS`를 FAILED로 매핑한다.
- [ ] **Step 4: 통과 확인** Expected: PASS
- [ ] **Step 5: Commit** `feat(trading): 증권사 잔고 부족 거절은 관리자 알림 없이 전략당 사용자 알림 1건으로 보낸다`

---

### Task 3: `StrategyLedgerBudget` 도입과 배치 allocator 교체

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyLedgerBudget.java`
- Create: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/StrategyLedgerBudgetTest.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingCandidatePlanner.java:39-51, 232-378`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java:68-72, 189-206`, `matching/domain/model/PlannedOrder.java:22, 65-75`
- Delete: `TradingOrderBudgetAllocator.java`, `TradingOrderBudgetAllocatorTest.java`
- Test: `TradingServiceTest`, `TradingCandidatePlannerDcaTest`, `DcaCycleOrderStrategyTest`

**Interfaces:**
- Consumes:
  - `CyclePositionPort.findLatestOne(UUID cycleId)` / `findLatestByCycleIds(Collection<UUID>)`
  - `OrderPort.findPlannedOrPlacedByCycleAndDate(UUID, LocalDate)`. 조회 상태에 SUBMITTING이 포함되는지 확인하고, 없으면 BUY 합계 계산에 SUBMITTING도 넣는다(`OrderJpaRepository.findByStrategyCycleIdAndTradeDateAndStatusIn` 호출부의 상태 목록 확인).
  - `CycleOrderStrategies.of(type).fitBuysToBudget(...)`, `occupiesSeed()`
- Produces (package-private `@Component`):
  - `BigDecimal remaining(StrategyCycle cycle, LocalDate tradeDate)`: 최신 `usd_deposit`(없으면 `cycle.startAmount()`)에서 같은 사이클·거래일의 BUY(PLANNED·SUBMITTING·PLACED) `price×quantity` 합을 뺀 값이다.
  - `LedgerFit fit(StrategyType type, List<PlannedOrder> orders, BigDecimal budget)`
    - `record LedgerFit(List<PlannedOrder> orders, BigDecimal requiredBuyUsd, boolean trimmed, boolean rejected)`
    - `orders`: SELL은 그대로, BUY는 원본·축소안·제거 중 하나로, 원래 상대 순서를 유지한다.
    - `requiredBuyUsd`: 원본 BUY 총액이다.
    - `occupiesSeed()=false`(DCA)면 항상 원본이고 `rejected=false`다.

- [ ] **Step 1: `StrategyLedgerBudgetTest` 실패 테스트 작성**
  - `buyWithinLedgerKeepsOrders`: 원장 500, BUY 304이면 원본 그대로, `trimmed=false`, `rejected=false`.
  - `privacyOverLedgerRejectsAllBuysKeepsSells`: PRIVACY, 원장 177.61, BUY 3주 @101.44(304.32)와 SELL 1건이면 BUY는 0건, SELL은 1건 남고 `rejected=true`.
  - `infiniteOverLedgerDropsCorrectionsFirst`: base 200 + 보정 3건(각 ~9), 원장 210이면 보정만 잘리고 `trimmed=true`.
  - `vrBootstrapOverLedgerRejected`: VR LOC BUY 총액이 원장보다 크면 `rejected=true`.
  - `dcaIgnoresLedger`: DCA, 원장 0, BUY 100이면 원본 그대로이고 `rejected=false`.
  - `remainingFallsBackToStartAmountWhenNoPosition`: `findLatestOne`이 empty이고 startAmount가 1000이면 1000.
  - `remainingSubtractsSameDayCycleBuys`: 원장 1000, 같은 날 PLACED BUY 300, SUBMITTING BUY 100, SELL 50이면 600.
- [ ] **Step 2: 실패 확인**
  Run: `./gradlew :trading-core:test --tests '*StrategyLedgerBudgetTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL(클래스 없음)
- [ ] **Step 3: `StrategyLedgerBudget` 구현** — 테스트로 결정되는 범위에서 최소 구현
- [ ] **Step 4: 통과 확인** Expected: PASS
- [ ] **Step 5: planner 교체용 회귀 테스트 작성**
  - `TradingServiceTest.ledgerCapIsPerStrategyNotAccount`(10-17 회귀): 같은 계좌에 INFINITE(원장 1800, BUY 90)와 PRIVACY(원장 177.61, BUY 304.32)가 있다. INFINITE BUY는 저장되고, PRIVACY BUY는 저장되지 않으며 `InsufficientBalanceEvent`가 1건 나간다(`failedCount=0`, `partiallyPlaced=false`, 문구는 원장 부족용). `LiveBalancePort`와 `SellableQuantityPort`는 호출되지 않는다(`verifyNoInteractions`).
  - `TradingServiceTest.sellApprovedWithoutSellableCheck`: 판매가능수량 stub 없이 SELL이 저장된다.
- [ ] **Step 6: `TradingCandidatePlanner` 교체**
  - `allocateAndSave`의 `budgetAllocator.allocate` 호출을 후보별 `ledgerBudget.fit(type, orders, ledgerBudget.remaining(cycle, date))`로 바꾼다. 기존 슬롯 재확인과 `budgetLock.call(accountId, ...)`은 유지한다(배치·수동 실행 중복 저장 방지).
  - 거절된 비-DCA 후보는 `InsufficientBalanceEvent(..., 0, false)`를 발행한다.
  - `recordDcaUnapproved`, `Candidate`, `Allocation` 의존은 제거한다.
  - `remaining`은 저장 직전, 즉 락 안에서 계산한다(이미 저장된 슬롯이 사이클 BUY 합계에 반영되도록).
- [ ] **Step 7: DCA BUY 예산 효과 제거**
  - `DcaCycleOrderStrategy.fitBuysToBudget` override와 `withPartialAllowed` 호출(:71)을 삭제한다.
  - `PlannedOrder.partialAllowed` 필드, 복사 메서드, `withPartialAllowed`를 삭제한다.
  - `TradingCandidatePlanner:303`과 `DcaCycleOrderStrategyTest:310-354`의 관련 단언을 정리한다. SELL·코인 경로의 `DcaShortfallPolicy` 판정(:116-139)은 그대로 둔다.
- [ ] **Step 8: allocator 삭제와 컴파일·관련 테스트**
  Run: `./gradlew :trading-core:test --tests '*TradingServiceTest' --tests '*TradingCandidatePlanner*' --tests '*DcaCycleOrderStrategyTest' --tests '*StrategyLedgerBudgetTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: PASS
- [ ] **Step 9: 변이 확인**
  `fit`이 항상 원본을 반환하도록 잠시 바꾸면 `ledgerCapIsPerStrategyNotAccount`가 실패하는지 확인한다. 확인 후 되돌린다.
- [ ] **Step 10: Commit** `refactor(trading): 계좌 예산 배정을 전략별 자기 원장 상한으로 교체한다`

---

### Task 4: 접수 직전 재캡 예산을 자기 원장으로

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingOrderExecutor.java:33, 70-110`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/BuyOrderPriceCapper.java:61-66`(파라미터 이름·주석만)
- Test: `TradingOrderExecutorTest`

**Interfaces:**
- Consumes: `StrategyLedgerBudget.remaining(StrategyCycle, LocalDate)` (Task 3)
- Produces: `capIfNeeded(..., BigDecimal budget)`. 마지막 파라미터 의미가 "사이클 원장 기준 예산"으로 바뀌고, `0 = live 조회 실패` 의미는 사라진다.

- [ ] **Step 1: 실패 테스트**
  - `TradingOrderExecutorTest.recapBudgetIsCycleLedgerPlusOwnScopeBuys`: 재캡이 BUY 총액을 늘리는 경우 `capIfNeeded` 두 번째 호출의 예산은 `remaining + 자기 스코프 원본 PLANNED BUY 합`이다. `LiveBalancePort`는 주입되지 않는다(생성자에서 제거).
- [ ] **Step 2: 실패 확인** Run: `./gradlew :trading-core:test --tests '*TradingOrderExecutorTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현**
  - `capWithBudget`의 `sumPlannedBuyByAccountAndDate`와 live 조회를 제거한다. 예산은 `ledgerBudget.remaining(cycle, date)`에 자기 스코프(atOpen 여부) 원본 PLANNED BUY 합을 더한 값이다.
  - `remaining`이 자기 PLANNED BUY까지 빼므로, 자기 스코프분을 되돌려 더해야 한다.
  - 사이클 객체가 없으면 `StrategyCyclePort`로 조회하거나, 호출자에서 `StrategyCycle`을 넘기도록 시그니처를 바꾼다.
  - live 실패 분기는 삭제한다. `accountBudgetLock`은 유지한다.
- [ ] **Step 4: 통과 확인** Expected: PASS
- [ ] **Step 5: Commit** `refactor(trading): 접수 직전 재캡 예산을 사이클 원장 기준으로 바꾼다`

---

### Task 5: 수동 실행(바로 주문)

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ManualTradingService.java:38, 100-130`
- Test: `ManualTradingServiceTest`

**Interfaces:**
- Consumes: `StrategyLedgerBudget.fit/remaining` (Task 3)

- [ ] **Step 1: 실패 테스트**
  - `ManualTradingServiceTest.rejectsWhenOverOwnLedger`: BUY가 원장을 넘으면 `ManualTradingException` 메시지가 정확히 `"전략 원장 예수금이 부족합니다."`이고 저장은 없다.
  - `ManualTradingServiceTest.sellSavedWithoutSellableCheck`: SELL만 있으면 판매가능수량 조회 없이 저장된다.
  - `ManualTradingServiceTest.trimmedBuysSaved`: INFINITE 보정이 잘린 축소안이 저장된다.
- [ ] **Step 2: 실패 확인** Run: `./gradlew :trading-core:test --tests '*ManualTradingServiceTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현** — `budgetLock` 안에서 중복 재검사를 한 뒤 `fit` → `rejected`면 예외, 아니면 `fit.orders()`를 저장한다. "보유 수량이 부족합니다" 분기는 삭제한다.
- [ ] **Step 4: 통과 확인** Expected: PASS
- [ ] **Step 5: Commit** `refactor(trading): 수동 실행 예산 검증을 전략 원장 상한으로 바꾼다`

---

### Task 6: 재주문 원장 상한과 사이클별 예산 조회

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/ReorderBuyBudget.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/usecase/ReorderBuyBudgetQuery.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ReorderBuyBudgetService.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/ReorderService.java:53-172`
- Modify: `shared/src/main/java/com/kista/shared/contract/trading/ReorderBuyBudgetResponse.java`, `TradingContractMapper`(trading-core)
- Modify: root `src/main/java/com/kista/api/admin/adapter/in/web/dto/ReorderBuyBudgetResponse.java`, `AdminTradeController.java:114` 응답 매핑
- Test: `ReorderBuyBudgetServiceTest`, `ReorderServiceTest`, `InternalApiContractTest`(수정 불필요한지 확인), root `AdminTradeControllerTest`

**Interfaces:**
- Consumes: `StrategyLedgerBudget.remaining` (Task 3)
- Produces:
  - `record ReorderBuyBudget(Map<UUID, CycleBudget> cycles, Map<UUID, BigDecimal> sourceRefunds)`
  - `record CycleBudget(BigDecimal remaining)`. 키는 `strategyCycleId`이고, `remaining`은 `StrategyLedgerBudget.remaining` 값이다.
  - 같은 계좌 제한은 유지한다. wire record도 같은 구조(`Map<UUID, BigDecimal> cycleRemaining`, `sourceRefunds`)로 바꾼다.

- [ ] **Step 1: 실패 테스트**
  - `ReorderBuyBudgetServiceTest.returnsRemainingPerCycle`: 원본 2건이 서로 다른 사이클이면 사이클별 remaining을 돌려주고 `LiveBalancePort`는 쓰지 않는다.
  - `ReorderServiceTest.immediateBuyOverLedgerRejected409`: IMMEDIATE BUY의 `가격×수량`이 `remaining + 원본 환불`보다 크면 `ManualTradingException("전략 원장 예수금이 부족합니다.")`을 던지고, 원본은 취소하지 않으며 증권사 호출도 없다.
  - `ReorderServiceTest.immediateBuyWithinLedgerProceeds`
- [ ] **Step 2: 실패 확인** Run: `./gradlew :trading-core:test --tests '*ReorderBuyBudgetServiceTest' --tests '*ReorderServiceTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현**
  - 원장 검증은 시점 검증과 함께 **원본 취소 전**에 한다(검증 실패 시 원본이 취소된 채 남지 않게).
  - 환불분은 `refundOf`를 재사용한다. 409 매핑은 기존 `ManualTradingException`의 409를 그대로 쓴다.
- [ ] **Step 4: root 쪽 계약 반영과 테스트**
  Run: `./gradlew :trading-core:test --tests '*Reorder*' --tests '*InternalApiContract*' 2>&1 | grep -E "FAILED|BUILD|Tests"` 이어서 `./gradlew :test --tests '*AdminTrade*' --tests '*InternalApiContract*' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: PASS
- [ ] **Step 5: Commit** `feat(trading): 재주문 BUY에 전략 원장 상한을 적용하고 예산 조회를 사이클별로 바꾼다`

---

### Task 7: 미리보기 원장 경고

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/TradingPreviewService.java:200-225`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/NextOrdersPreview.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/LedgerCheckPreview.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/NextOrdersResponse.java:22-40, 127-210`
- Delete: `TradingBuyCompetitionSimulator`, `TradingSellSufficiencySimulator`, `PreviewDepositCache`, `BuyCompetitionPreview`, `SellSufficiencyPreview` 및 각 Test
- Test: `TradingPreviewServiceTest`, `NextOrdersResponseTest`

**Interfaces:**
- Consumes: `StrategyLedgerBudget.remaining/fit` (Task 3)
- Produces:
  - `record LedgerCheckPreview(BigDecimal remainingLedger, BigDecimal requiredBuyUsd, boolean sufficient, boolean trimmed)`
  - `sufficient = !fit.rejected()`
  - `NextOrdersPreview`의 `competition`·`sellSufficiency` 두 필드를 `LedgerCheckPreview ledgerCheck` 하나로 바꾼다. DCA·BUY 없음·skip이면 null이다.
  - `NextOrdersResponse.ledgerCheck`는 같은 4필드 record다.

- [ ] **Step 1: 실패 테스트**
  - `TradingPreviewServiceTest.privacyOverLedgerShowsInsufficient`: `ledgerCheck.sufficient=false`, `requiredBuyUsd=304.32`, `remainingLedger=177.61`.
  - `TradingPreviewServiceTest.dcaHasNoLedgerCheck`: `ledgerCheck`가 null이다.
  - `TradingPreviewServiceTest.previewDoesNotQueryBroker`: `LiveBalancePort`·`SellableQuantityPort`와 상호작용이 없다.
- [ ] **Step 2: 실패 확인** Run: `./gradlew :trading-core:test --tests '*TradingPreviewServiceTest' --tests '*NextOrdersResponseTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현**과 시뮬레이터·캐시 삭제
- [ ] **Step 4: 통과 확인** Expected: PASS
- [ ] **Step 5: Commit** `feat(trading): 미리보기 잔고 경고를 전략 원장 기준으로 바꾸고 경쟁 시뮬레이션을 제거한다`

---

### Task 8: INFINITE 최종회차 판정 확장

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/InfinitePosition.java:93-95`
- Modify: `docs/agents/modules/tradingcore/trading-formulas.md:17`("후반 unitAmount>usdDeposit" 문구)
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/InfinitePositionTest.java:109-124`, `InfiniteStrategyTypeTest`, `BacktestEngineTest`

**Interfaces:**
- Produces: `isFinalRound() = unitAmount > usdDeposit || referencePrice > usdDeposit`. `nextReverseMode`와 `InfiniteStrategy:138`이 이 판정을 그대로 쓴다.

- [ ] **Step 1: 실패 테스트**
  - `InfinitePositionTest.finalRoundWhenOneShareAtReferenceExceedsDeposit`: 기준가 약 $30, divisionCount 20, totalAssets $440(unitAmount $22), usdDeposit $25이면 `isFinalRound()`가 true다. 입력은 `referencePrice`가 30 근처가 되도록 averagePrice·holdings·targetProfitRate로 구성하고, 테스트 안에서 `referencePrice() > usdDeposit()`을 먼저 단언한다.
  - `InfiniteStrategyTypeTest.lateStageOneShareOverDepositEmitsMocSellOnly`: 같은 포지션이면 주문이 `INFINITE_MOC_SELL` 1건뿐이다.
  - 기존 `unitAmount > usdDeposit` 경계 테스트는 그대로 통과해야 한다.
- [ ] **Step 2: 실패 확인** Run: `./gradlew :trading-core:test --tests '*InfinitePositionTest' --tests '*InfiniteStrategyTypeTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현** — 위 Produces 한 줄
- [ ] **Step 4: 통과 확인**과 백테스트 회귀
  Run: `./gradlew :trading-core:test --tests '*InfinitePositionTest' --tests '*InfiniteStrategyTypeTest' --tests '*BacktestEngineTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: PASS. 백테스트 기대값이 바뀌면 바뀐 케이스가 "기준가 1주 > 원장" 조건인지 확인한 뒤 기대값을 갱신한다.
- [ ] **Step 5: `trading-formulas.md` 갱신**
  - "**후반 unitAmount>usdDeposit 또는 referencePrice>usdDeposit**: MOC 매도(holdings/4)만"으로 바꾼다.
  - 리버스 진입 조건도 같은 판정이라고 한 줄 덧붙인다.
- [ ] **Step 6: Commit** `feat(matching): 기준가 1주가 원장을 넘으면 INFINITE 최종회차로 판정한다`

---

### Task 9: 전략 등록 경고와 사이클 재등록 원장 통일

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java:96, 263-290`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/StrategySeedPreview.java`, `support/StrategyHistoryQueryService.java:49-80`, `adapter/in/web/dto/StrategySeedPreviewResponse.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/CycleRotationService.java:43-160`
- Test: `StrategyServiceTest`(:887, :918 잔고검증 스텁), `StrategySeedPreviewServiceTest`, `CycleRotationServiceTest`

**Interfaces:**
- Produces:
  - `StrategySeedPreview(String ticker, BigDecimal basePrice, BigDecimal minSeed, String skipReason, BigDecimal availableCash)`, 응답 DTO도 같은 필드
  - `availableCash` = 기존 `calcFreeCash`(마진 − 계좌 내 전략별 최신 `usd_deposit` 합). MOCK이나 조회 예외면 null이다. `calcFreeCash`는 `StrategyHistoryQueryService`로 옮기거나 공용 private helper로 둔다.

- [ ] **Step 1: 실패 테스트**
  - `StrategyServiceTest.registerOverFreeCashIsAllowed`: 시드가 가용 예수금을 넘어도 등록이 성공한다. `MarginPort`는 호출되지 않는다.
  - `StrategySeedPreviewServiceTest.includesAvailableCash`: 마진 5000, 기존 전략 원장 합 1200이면 `availableCash=3800`.
  - `StrategySeedPreviewServiceTest.mockAccountHasNullAvailableCash`
  - `StrategySeedPreviewServiceTest.marginFailureGivesNullAvailableCash`
  - `CycleRotationServiceTest.maintainUsesMinOfStartAmountAndLedger`: MAINTAIN, startAmount 1000, 최종 원장 800이면 새 사이클 시드는 800이다. `MarginPort`는 호출되지 않는다.
  - `CycleRotationServiceTest.maintainAfterProfitKeepsStartAmount`: startAmount 1000, 원장 1200이면 1000.
  - `CycleRotationServiceTest.maxUsesLedger`: MAX이면 원장(1200).
- [ ] **Step 2: 실패 확인** Run: `./gradlew :trading-core:test --tests '*StrategyServiceTest' --tests '*StrategySeedPreview*' --tests '*CycleRotationServiceTest' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: FAIL
- [ ] **Step 3: 구현**
  - `validateBalanceIfRequired`를 삭제한다.
  - `resolveAvailableBalance`와 `fetchUsdBalance`를 삭제한다.
  - `resolveTargetSeed`는 MAX → `maxSeed`, MAINTAIN → `maintainSeed.min(maxSeed)`로 바꾼다. 강등·pause 분기는 원장 기준에서 도달할 수 없으면 정리한다. 최소금액 가드와 관리자 `InsufficientBalanceEvent(userId=null)`는 유지한다(Task 2의 새 필드는 `0, false`).
- [ ] **Step 4: 통과 확인** Expected: PASS
- [ ] **Step 5: Commit** `feat(trading): 전략 등록 잔고 차단을 시드 미리보기 경고로 바꾸고 재등록을 원장 기준으로 통일한다`

---

### Task 10: `balanceCheckEnabled` 제거

**Files:**
- trading-core:
  - `trading/domain/model/TradingUserProfile.java`
  - `trading/adapter/out/persistence/UserNotifyProfileEntity.java`, `UserNotifyProfileSyncListener.java`, `UserNotifyProfilePersistenceAdapter.java`
  - Create `trading-core/src/main/resources/db/migration-trading/V13__drop_user_notify_profile_balance_check.sql`
- shared: `shared/src/main/java/com/kista/shared/kernel/UserNotifyProfileChangedEvent.java`
- root (`src/main/java/com/kista/api/user/`):
  - `domain/model/UserSettings.java`, `application/usecase/UserSettingsUseCase.java`, `application/service/UserSettingsService.java`, `UserNotifyProfilePublisher.java`
  - `adapter/in/web/SettingsController.java`, `dto/BalanceCheckRequest.java`(삭제), `dto/UserResponse.java`, `AuthController.java`
  - `adapter/out/persistence/settings/UserSettingsJpaEntity.java`, `UserSettingsPersistenceAdapter.java`
- Tests: 사용처 목록(spec §4.8)의 root·trading-core 테스트 전부
- Docs: `docs/agents/modules/api/user.md:21-22`, `modules/tradingcore/trading.md` "잔고검증 토글" 절 삭제, `modules/shared/kernel.md:9`, `constraints.md:57`

**Interfaces:**
- Produces: `UserNotifyProfileChangedEvent`와 `TradingUserProfile`에서 `balanceCheckEnabled` 필드를 삭제한다. 생성자 인자가 줄어든다.

- [ ] **Step 1: 역직렬화 호환 테스트 작성**
  - 구버전 payload 호환: `UserNotifyProfileStreamConsumer` 또는 `UserEventStreamBridge` 테스트에서 `balanceCheckEnabled` 키가 포함된 JSON이 예외 없이 역직렬화되는지 단언한다.
  - 실패하면 이벤트 record에 `@JsonIgnoreProperties(ignoreUnknown = true)`를 단다.
- [ ] **Step 2: 필드 제거**
  - root `UserSettingsJpaEntity`에서 `balance_check_enabled` 매핑을 제거한다. 컬럼은 `NOT NULL DEFAULT true`라 insert가 안전하다. root 마이그레이션은 만들지 않는다.
  - `PATCH /api/settings/balance-check`와 `BalanceCheckRequest`를 삭제한다.
  - `UserResponse.balanceCheckEnabled`를 삭제한다.
- [ ] **Step 3: trading 마이그레이션 V13**
  ```sql
  -- 잔고검증 토글 제거 — 등록·재등록 잔고 검증이 원장 기준으로 바뀌어 복제 필드가 불필요
  ALTER TABLE user_notify_profile DROP COLUMN IF EXISTS balance_check_enabled;
  ```
- [ ] **Step 4: 컴파일·관련 테스트**
  Run: `./gradlew :trading-core:test --tests '*UserNotifyProfile*' --tests '*UserCascade*' 2>&1 | grep -E "FAILED|BUILD|Tests"` 이어서 `./gradlew :test --tests '*UserSettings*' --tests '*SettingsController*' --tests '*UserNotifyProfilePublisher*' --tests '*UserEventResubmit*' 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: PASS
- [ ] **Step 5: 문서 갱신**과 `grep -rn "balanceCheckEnabled\|balance_check_enabled\|BalanceCheck" src trading-core shared --include=*.java`이 0건(마이그레이션 SQL 제외)인지 확인
- [ ] **Step 6: Commit** `refactor!: 잔고검증 설정(balanceCheckEnabled)을 제거한다`

---

### Task 11: 잔여 정리·문서·전체 검증

**Files:**
- Delete/Modify(사용처가 0건이 된 것만):
  - `BuyPriorityOrdering`(+Test), `CycleOrderStrategy.allocationPriority()`와 구현 4종 override, `CycleOrderStrategyCapabilityTest` 관련 단언
  - `OrderPort.sumPlannedBuyByAccountAndDate`와 어댑터·JPA 쿼리
  - `OrderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker`는 `DcaPlanInputCollector`가 쓰므로 **유지**한다
  - `LiveBalancePort`·`MockBrokerAdapter.getLiveBalance` 원장 합산: trading 소비처가 0건이면 포트 메서드와 broker 구현을 정리한다. stats 등 남은 소비처가 있으면 유지한다.
- Docs:
  - `docs/agents/workflow.md`: :5·:7 예산 배정 → 원장 상한, :17, :35 수동 실행, :43-53 재캡 예산, :75 잔고 부족 분류
  - `docs/agents/modules/tradingcore/trading.md`: allocator·`PreviewDepositCache`·`ReorderBuyBudgetService` 설명, DCA 절의 allocator·`partialAllowed` 문구
  - `docs/agents/modules/tradingcore/matching.md`: `fitBuysToBudget` 설명(호출자=`StrategyLedgerBudget`, DCA override 없음)
  - `docs/agents/testing.md:67-72`: allocator 테스트 패턴 → `StrategyLedgerBudget`
  - `docs/architecture-map.md:260-271`
  - `src/test/resources/architecture/flows.yml`: :135-141, :357-363, :515-521, :941-988, :1095, :1197, :1431
  - README 드리프트 확인
- Test: 전체 스위트

- [ ] **Step 1: 사용처 grep 후 dead code 삭제**
  Run: `grep -rn "allocationPriority\|BuyPriorityOrdering\|sumPlannedBuyByAccountAndDate\|getLiveBalance\|LiveBalancePort" trading-core/src src shared/src`
  0건이 된 심볼만 삭제한다.
- [ ] **Step 2: 문서와 `flows.yml` 갱신** — 위 목록. `flows.yml`의 `code:` 참조가 삭제된 클래스를 가리키지 않게 한다.
- [ ] **Step 3: 아키텍처·전체 테스트 1회**
  Run: `./gradlew test integration 2>&1 | grep -E "FAILED|BUILD|Tests"` Expected: BUILD SUCCESSFUL. `ModulithArchitectureTest`, `HexagonalArchitectureTest`, `InternalApiContractTest`, `ArchitectureMapTest`, `ApplicationContextLoadTest`, `TradingApplicationContextLoadTest`가 포함된다.
  실패 진단: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml | grep -v ':0'`
- [ ] **Step 4: Commit** `docs: 전략 원장 상한 전환에 맞춰 흐름 문서·아키텍처 맵을 갱신한다`
- [ ] **Step 5: kista-ui 전달 메모 작성** — spec §7을 기준으로 실제 바뀐 응답 필드명을 정리해 최종 보고에 포함한다.
  - `ledgerCheck`, `availableCash`, `ReorderBuyBudget.cycleRemaining`
  - `balanceCheckEnabled`와 `PATCH /api/settings/balance-check` 삭제
  - 409 문구 변경
  - 전략 카드 UI 개선(사용자 승인): 금액에 "시작 시드" 라벨, "예수금 부족" 배지에 `ledgerCheck.remainingLedger`와 `requiredBuyUsd`를 표시한다.
