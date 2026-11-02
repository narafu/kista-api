# CQ-23 장중 취소 BUY 체결분 기록 · 원장 예산 반영 계획

Spec: 이 계획의 "설계"절(사용자 승인 2026-11-03, 채팅). 별도 spec 파일 없음.

## 배경
`StrategyLedgerBudget.remaining` = 최신 `cycle_position.usdDeposit` − 같은 거래일 PLANNED/SUBMITTING/PLACED BUY 약정. 장중 부분 체결 후 CANCELLED된 BUY의 체결분은 마감 리포트(`TradingReporter`) 전까지 장부에도 약정에도 없어서 재주문·배치·수동 실행이 원장을 초과 매수할 수 있다. `ReorderBuyBudgetService.refundOf`도 체결분만큼 과대 환급한다. 원인: 취소 시점 체결량을 DB가 모른다.

## 설계 (확정)
1. **취소 직후 체결 기록.** 증권사 취소 결과가 `CANCELLED`·`ALREADY_CANCELED`라서 `stateWriter.markCancelled`를 커밋한 직후, 트랜잭션 밖에서 `ExecutionPort.getExecutions(order.tradeDate(), order.tradeDate(), order.ticker(), brokerRef)`를 호출해 `externalOrderId`로 거르고 합산해 `orderPort.markFilled(id, qty, avg, OrderStatus.CANCELLED)`로 기록한다. 목록에 없으면 `qty=0`, `price=null`. 적용 지점 3곳: `OrderCancelService`(일괄 루프 `markCancelled` 직후, 단건 `cancelOrder` 끝), `ReorderService.cancelSource`(PLACED 분기). PLANNED 취소(`cancelPlanned`)·`deletePlanned`는 증권사 미접수라 조회하지 않는다. 일괄 취소는 (tradeDate, ticker)당 1회만 조회한다.
2. **조회 실패 = fail-open.** `BrokerCallGuard.tryGet`으로 감싸 실패 시 filled 값을 쓰지 않는다(null 유지 = "모름"). 취소 자체는 이미 성공했으므로 취소 응답·집계에 영향 없음. warn 로그만.
3. **합산 SSOT.** `TradingReporter`의 `sumQuantity`/`avgPrice`(Σ amountUsd ÷ Σ quantity, scale 2, HALF_UP)를 공용 함수로 추출해 리포트와 취소 기록이 같은 함수를 쓴다(리포트가 같은 주문을 다시 써도 값이 같다).
4. **예산 공식.** `remaining` = 원장 − 같은 날 PLANNED/SUBMITTING/PLACED BUY 약정 − "보류 체결분". 보류 체결분 = 같은 사이클·거래일, 그 거래일이 **리포트 전**(`TradingBatchRunPort.isReported(tradeDate, cycle.strategyId())`가 false)이고, `direction=BUY`·`externalOrderId != null`·`orderLeg != UNKNOWN_LEG`인 CANCELLED 주문 각각에 대해:
   - `filledQuantity == null`(모름) → `price × quantity`(주문 금액 전체 = 상한)
   - 그 외 → `filledQuantity × filledPrice`(filledPrice null이면 0)
   포트 버전·순수 버전(미리보기용) 모두 같은 공식. 순수 버전은 취소 행 목록도 인자로 받는다 — 공식은 `StrategyLedgerBudget` 한 곳.
5. **재주문 사전 검사.** `ReorderService`의 BUY 원장 검사(원본 취소 전)에서 원본이 PLACED BUY면 먼저 체결 조회로 현재 체결량 F를 구하고 환급분 = `price × (quantity − F)`. 조회 실패 시 재주문 거부(fail-closed, `BrokerCallGuard.wrap` 또는 동등 — 아직 아무것도 취소하지 않아 부작용 없음). PLANNED 원본은 F=0.
6. **재주문 예산 조회(경고용).** `ReorderBuyBudgetService`: PLACED BUY 원본들에 대해 종목당 1회 체결 조회(같은 계좌 `Account.toBrokerRef()`), 환급분 = `price × (quantity − F)`. 실패 시 F=0(지금처럼 전액, fail-open).
7. **인출.** `CapitalInjectionService.withdraw`의 "취소된 BUY 주문 금액 전체" 상한을 4번 정의(보류 체결분, 오늘·다음 거래일 각각 리포트 전인 날짜만)로 바꾼다. 400 문구도 정의에 맞게.
8. **문서.** CQ-23 done, `flows.yml`, `workflow-ledger-price.md`, `workflow-orders.md`, `modules/tradingcore/trading.md`(인출 항목·ReorderBuyBudgetService 항목).

## Global Constraints
- 커밋 금지: 서브에이전트는 `git add`/`git commit` 하지 않고 파일만 수정한다. 커밋은 컨트롤러가 리뷰 후 한다.
- 주석: `//` 인라인만(Javadoc·블록 금지), 신규 코드엔 단계 설명 주석. 4-space.
- IAE/ISE·`ManualTradingException` 메시지는 "~니다."로 끝난다(`ExceptionMessageFormatTest`).
- 브로커 HTTP는 `@Transactional` 밖. `OrderCancelStateWriter`는 DB 전용(@Transactional public 메서드).
- 현재 시각은 주입된 `Clock`. `@Async`/`CompletableFuture` 금지.
- 테스트 명령은 서브프로젝트 지정 + 로그 필터: `./gradlew :trading-core:test --tests '<FQCN>' 2>&1 | grep -E "FAILED|BUILD|ERROR|tests completed"`. 전체 스위트는 돌리지 않는다(컨트롤러가 마지막에 1회).
- 변이 확인: 핵심 신규 테스트는 구현의 해당 줄을 잠시 되돌려 실패하는지 확인하고 원복한다. 리포트에 결과를 적는다.
- broker 밖 테스트는 KIS/Toss 벤더 타입 금지(`TradingCoreTestVendorModelTest`), 외부 실패는 `StubBrokerApiException`.

## Task 1: 취소 직후 체결 기록 + 합산 함수 추출
설계 1·2·3.
- 새 package-private 컴포넌트(이름 예: `CancelledOrderFillRecorder`, `trading/application/service`): `ExecutionPort` + `OrderCancelStateWriter` 주입.
  - `record(List<Order> cancelled, BrokerAccountRef ref)`: (tradeDate, ticker)별로 묶어 `BrokerCallGuard.tryGet`으로 `getExecutions` 1회, externalOrderId별 합산 후 주문마다 `stateWriter.recordCancelledFill(orderId, qty, avgOrNull)`. 실패 그룹은 기록 생략 + warn.
  - `Optional<BigDecimal> currentFilledQuantity(Order, BrokerAccountRef)` 같은 단건 조회는 Task 3에서 쓴다 — 지금 만들지 말 것(Task 3이 추가).
- 합산: 정적 함수(예: record `OrderFill(BigDecimal quantity, @Nullable BigDecimal avgPrice)` + `static Map<String, OrderFill> byExternalOrderId(List<Execution>)` 또는 동등)로 추출. `TradingReporter.markFilledOrders`가 이를 쓰도록 바꾸되 동작·기록 값은 동일(정수 수량 `int` 합 → BigDecimal, avg scale 2 HALF_UP, qty 0이면 avg ZERO였던 리포트 동작은 리포트가 qty 0을 기록하지 않으므로 무관).
- `OrderCancelStateWriter`에 `@Transactional public void recordCancelledFill(UUID orderId, BigDecimal filledQuantity, @Nullable BigDecimal filledPrice)` → `orderPort.markFilled(orderId, filledQuantity, filledPrice, OrderStatus.CANCELLED)`. `markFilled` 어댑터가 CANCELLED 행에 쓰는 것을 막지 않는지 확인(리포트가 이미 같은 호출을 한다).
- 호출 지점: `OrderCancelService` 일괄 루프(성공적으로 markCancelled한 주문들을 모아 루프 뒤 1회 `record`), 단건 `cancelOrder` 끝, `ReorderService.cancelSource` PLACED 분기의 `markCancelled` 직후. 기록 실패가 취소 결과(예외·카운트)를 바꾸면 안 된다.
- 테스트: 신규 컴포넌트 단위 테스트(체결 있음/없음=0·null/조회 실패=기록 안 함/여러 주문 같은 종목 1회 조회), `OrderCancelServiceTest`·`ReorderServiceTest`에 기록 호출 검증, `TradingReporterTest` 기존 통과.

## Task 2: 예산 공식 + 미리보기 + 인출
설계 4·7.
- `StrategyLedgerBudget`에 `TradingBatchRunPort` 주입. 포트 버전 `remaining(cycle, tradeDate)`는 `orderPort.findCancelledByCycleAndDate(cycle.id(), tradeDate)`를 리포트 전일 때만 조회해 보류 체결분을 뺀다. 순수 버전 시그니처에 취소 목록 인자 추가(이미 리포트 필터된 목록), 공식은 순수 버전 한 곳. 보류 체결분 계산은 `StrategyLedgerBudget` 안의 한 메서드(인출도 재사용).
- 리포트 전 판정 + 취소 목록 조회를 묶은 헬퍼(예: `List<Order> unreportedCancelled(StrategyCycle, LocalDate)`)를 두고 `TradingPreviewService:286`과 인출이 재사용.
- `CapitalInjectionService.withdraw`: 기존 `cancelledBuys` 계산 제거, 오늘·다음 거래일 각각 헬퍼로 모은 취소 목록을 순수 `remaining`에 넘긴다. 주석·400 문구를 새 정의로 갱신(예: "장부 예수금에서 체결 전 매수 주문 금액과 장중 취소 전에 체결된 매수 금액을 뺀 만큼 인출할 수 있습니다." — 체결 조회 실패 건은 주문 금액 전체를 뺀다는 내용은 주석에).
- 테스트: `StrategyLedgerBudgetTest`(리포트 전 기록 체결분 차감, filled null=전액, 리포트 후 제외, SELL·externalOrderId null·UNKNOWN leg 제외, 가격만 바꾼 재주문 시 원본 0 체결이면 이중 차감 없음), `CapitalInjectionServiceTest` 갱신, `TradingPreviewServiceTest` 영향 수정. 변이 확인 필수.

## Task 3: 재주문 환급 체결분 반영
설계 5·6. Task 1의 컴포넌트에 단건/종목 단위 현재 체결 조회 메서드 추가(실패는 예외 전파 — 호출자가 정책 선택).
- `ReorderBuyBudgetService.refundOf(source, date)` → 체결량 인자를 받는 형태로(예: `refundOf(source, date, filledQuantity)` = `price × (quantity − filled)`, 음수 하한 0). `ReorderService` 사전 검사: PLACED BUY 원본이면 조회(실패 시 `ManualTradingException`, 문구 "~니다."로 끝나게 — 예: "원본 주문 체결 조회에 실패해 재주문할 수 없습니다. 잠시 후 다시 시도하시기 바랍니다."), PLANNED는 0.
- `ReorderBuyBudgetService.query`: 같은 계좌의 PLACED BUY 원본을 (tradeDate, ticker)로 묶어 1회 조회, 실패 시 0. 계좌 brokerRef는 `AccountPort`(trading이 이미 쓰는 포트)로.
- 테스트: `ReorderServiceTest`(부분 체결 원본 → 환급 축소로 거절/통과 경계, 조회 실패 → 거절·취소 호출 없음), `ReorderBuyBudgetServiceTest`(환급 축소, 실패 시 전액). 변이 확인.

## Task 4: 문서
설계 8. `docs/agents/code-quality-backlog.md` CQ-23 상태 done + 해소 요약 1줄, `src/test/resources/architecture/flows.yml` 취소·재주문·원장 판정 단계 desc, `docs/agents/workflow-ledger-price.md` remaining 정의, `docs/agents/workflow-orders.md` 취소 경로 체결 기록, `docs/agents/modules/tradingcore/trading.md` 인출 가능액·ReorderBuyBudgetService 설명. 한계 기록: 취소 처리 중(메리츠 ≈650ms) 들어온 체결·메리츠 history 반영 지연은 직후 조회에서 빠질 수 있고 리포트가 덮어써 원장은 맞춰진다(미검증, 실계좌 필요). `ArchitectureMapTest` 통과 확인.
