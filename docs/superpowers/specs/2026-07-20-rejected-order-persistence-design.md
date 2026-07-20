# 예산 배정 거절 주문(REJECTED) 영속화 — 매수/매도 거절 이력 표시

## 배경

토스증권 PRIVACY 전략에서 예수금 부족 상태로 개장 스케쥴러(22:30 KST)가 실행되면, `TradingOrderBudgetAllocator`가 예산 부족으로 거절한 BUY 후보는 `TradingService.saveAllocatedOrders`에서 알림만 발송하고 **`orders` 테이블에 전혀 저장하지 않는다**. 반면 같은 사이클의 SELL이 승인되면 정상적으로 PLANNED/PLACED로 저장된다. 그 결과 사용자는 "매도만 접수됐고 매수는 아무 흔적도 없는" 화면을 보게 된다.

kista-ui `StrategyDetail.tsx`에는 이런 상황을 위한 `BuyCompetitionNotice` 배지가 이미 있지만, 이는 **조회 시점의 라이브 잔고로 매번 새로 재시뮬레이션**(`TradingBuyCompetitionSimulator`)한 근사치다. 스케쥴러 실행 시각과 사용자가 화면을 보는 시각 사이에 라이브 잔고나 경쟁 전략 상태가 조금이라도 바뀌면, 실제로는 거절됐던 매수가 재시뮬레이션에서 "충분함"으로 나와 배지 자체가 뜨지 않을 수 있다. 이번에 확인된 실제 사례가 이 케이스로 추정된다.

이 설계는 "그날 실제로 거절이 있었다"는 사실 자체를 SELL과 동일하게 `orders` 테이블에 영속화해, 재시뮬레이션의 정확도에 의존하지 않고 사용자에게 정확한 이력을 보여준다.

## 범위

- BUY/SELL, 전략 타입(INFINITE/PRIVACY/VR) 구분 없이 **예산 배정 거절 후보는 모두 동일하게 REJECTED로 기록**한다 — `TradingOrderBudgetAllocator`가 이미 BUY/SELL을 방향 독립적으로, 전략 타입 무관하게 처리하므로 저장 단계에서 방향·타입별로 분기할 이유가 없다.
- 개장(`TradingOpenScheduler`)·마감(`TradingCloseScheduler`) 양쪽 경로 모두 `saveAllocatedOrders`를 공유하므로 자동으로 동일 적용된다.
- **슬롯 점유·재시도 판단 로직은 절대 변경하지 않는다.** `findPlannedOrPlacedByCycleAndDate`(PLANNED/PLACED만 필터)는 그대로 유지해, 장마감 전 예수금이 채워지면 REJECTED된 슬롯도 "비어있음"으로 재계산·재승인·재접수되는 기존 동작을 보존한다.
- DB 마이그레이션 불필요 — `orders.status`는 `VARCHAR(20)` + CHECK 제약 없음.

## 도메인 모델

`domain/model/order/Order.java`:

```java
public enum OrderStatus {
    PLANNED, PLACED, FILLED, PARTIALLY_FILLED, FAILED, CANCELLED,
    REJECTED   // 신규 — 예산 배정 단계에서 거절, 브로커 접수 시도조차 안 됨
}

// 예산 배정 거절 이력 기록용 — Order.plan()과 동일 패턴, status만 REJECTED
public static Order rejected(Order template, UUID accountId, UUID strategyCycleId) {
    return new Order(null, accountId, strategyCycleId, template.tradeDate(), template.ticker(),
            template.orderType(), template.timing(), template.direction(), template.orderLeg(),
            template.quantity(), template.price(), OrderStatus.REJECTED, null, null, null);
}
```

## 애플리케이션 레이어

### `TradingOrderPlanner`

`saveAllocatedOrders`는 개장(`TradingOpenScheduler`)·마감(`TradingCloseScheduler`) 양쪽에서 모두 호출된다. 개장에서 거절된 leg가 마감에서 재계산돼 다시 거절되면(`findPlannedOrPlacedByCycleAndDate`가 REJECTED를 점유로 안 보므로 매번 새 후보로 잡힘), 같은 `(strategyCycleId, tradeDate, orderLeg)`에 REJECTED 행이 **중복 저장**될 수 있다. `orders`에는 이 조합에 대한 UNIQUE 제약이 없으므로, 저장 전 해당 사이클·거래일의 기존 REJECTED를 지우고 최신 결과로 교체하는 replace 패턴을 쓴다(성공(PLANNED/PLACED)한 leg의 과거 REJECTED 이력은 이 삭제 대상이 아니다 — 애초에 그 leg는 이번 run에서 거절 후보로 다시 안 들어오므로 `saveRejectedOrders` 호출 자체가 그 leg를 건드리지 않는다):

```java
// 예산 배정 거절 후보를 REJECTED로 기록 (브로커 미접수 이력 보존)
// 같은 (cycle, tradeDate)에 대한 이전 REJECTED는 지우고 최신 거절 결과로 교체 —
// 개장·마감 스케쥴러가 같은 leg를 반복 거절해도 중복 행이 쌓이지 않는다
void saveRejectedOrders(List<Order> templates, Account account, UUID strategyCycleId, LocalDate tradeDate) {
    orderPort.deleteRejectedByCycleAndDate(strategyCycleId, tradeDate);
    List<Order> rejected = templates.stream()
            .map(o -> Order.rejected(o, account.id(), strategyCycleId))
            .toList();
    orderPort.saveAll(rejected);
    log.info("[{}] 거절 주문 {}건 기록 (REJECTED)", account.nickname(), rejected.size());
}
```

`OrderPort`에 `void deleteRejectedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);` 신규 추가.

### `TradingService.saveAllocatedOrders`

거절 루프를 개별 `Candidate`(현재는 `BatchContext`로 dedup되어 있어 `orders()`에 접근 못 함) 단위로 순회하도록 변경한다:

```java
List<TradingOrderBudgetAllocator.Candidate> rejected = Stream.concat(
                allocation.rejectedBuy().stream(), allocation.rejectedSell().stream())
        .toList();

for (TradingOrderBudgetAllocator.Candidate candidate : rejected) {
    runSafely("거절 주문 기록", candidate.ctx(), () -> {
        orderPlanner.saveRejectedOrders(
                candidate.orders(), candidate.ctx().account(), candidate.ctx().currentCycle().id(), tradeDate);
        return null;
    });
}

Set<BatchContext> rejectedContexts = rejected.stream()
        .map(TradingOrderBudgetAllocator.Candidate::ctx)
        .collect(Collectors.toCollection(LinkedHashSet::new));
for (BatchContext ctx : rejectedContexts) {
    runSafely("예수금 부족 알림", ctx, () -> {
        userNotificationPort.notifyInsufficientBalance(
                ctx.user(), ctx.account(), ctx.strategy().type(), ctx.strategy().ticker());
        return null;
    });
}
```

`rejectedBuy`와 `rejectedSell`에 같은 `ctx`가 동시에 나타날 수 있으나(같은 사이클의 BUY·SELL이 모두 거절된 경우) 각각 다른 `orders()`를 가진 별개의 `Candidate`이므로 중복 저장이 아니다. 저장·알림 모두 기존과 동일하게 `runSafely`로 개별 격리해, 한 사이클의 REJECTED 기록 실패가 다른 사이클의 SELL 저장이나 알림 발송을 막지 않는다.

### `OrderPort` / 영속성

신규 메서드 추가 — **기존 `findPlannedOrPlacedByCycleAndDate`는 변경하지 않는다**:

```java
List<Order> findRejectedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);
void deleteRejectedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);
```

`OrderPersistenceAdapter`/`OrderJpaRepository`에 `findByStrategyCycleIdAndTradeDateAndStatusIn(..., List.of(REJECTED))` / `deleteByStrategyCycleIdAndTradeDateAndStatus(..., REJECTED)` 패턴으로 구현 (기존 `findPlannedOrPlacedByCycleAndDate`와 동일한 헬퍼 재사용).

### `TradingPreviewService.preview()`

**`todayOrders`는 PLANNED/PLACED만 담는 기존 의미를 그대로 유지한다.** REJECTED는 별도 필드로 분리한다 — `todayOrders`는 kista-ui에서 `mode`(preview/executed) 판정·"N건 접수됨" 카운트·취소 대상 목록의 입력으로 쓰이므로, 여기에 REJECTED를 섞으면 거절 건이 "접수됨"으로 잘못 집계되고, 승인된 주문이 하나도 없이 전량 거절된 날엔 `mode`가 `preview`로 남아야 하는데 REJECTED 존재만으로 `executed`로 잘못 전환된다.

```java
List<Order> todayOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today); // 기존과 동일 — PLANNED/PLACED만
List<Order> rejectedOrders = orderPort.findRejectedByCycleAndDate(currentCycle.id(), today);

// 같은 leg가 이후 재시도로 성공(PLANNED/PLACED 존재)했으면 당일 카드에서는 REJECTED 숨김
Set<String> activeLegs = todayOrders.stream().map(Order::orderLeg).collect(Collectors.toSet());
List<Order> visibleRejectedOrders = rejectedOrders.stream()
        .filter(o -> !activeLegs.contains(o.orderLeg()))
        .toList();
```

`otherStrategiesPlannedBuyUsd`/`thisStrategyPlannedBuy` 등 예산 관련 계산은 계속 `todayOrders`(PLANNED/PLACED)만 사용 — 변경 없음. `NextOrdersPreview`에 `rejectedOrders` 필드를 신규 추가하고 `visibleRejectedOrders`를 담는다(`todayOrders`는 손대지 않음).

```java
public record NextOrdersPreview(
    LocalDate tradeDate, InfinitePosition position, List<Order> orders, SkipReason skipReason,
    List<Order> todayOrders, BigDecimal otherStrategiesPlannedBuyUsd, BuyCompetitionPreview competition,
    List<Order> rejectedOrders   // 신규 — 당일 예산 배정 거절 이력(재시도 성공 leg는 제외된 상태)
) { ... }
```

## 어댑터 — API 응답

`adapter/in/web/dto/NextOrdersResponse.java`에 `rejectedOrders` 필드를 `todayOrders`와 별도로 추가(같은 order 항목 DTO 재사용, `status` 필드 포함).

## 프론트엔드 (kista-ui)

### 타입 / 데이터

`useStrategyOrderPreviewQuery`가 반환하는 타입에 `rejectedOrders?: OrderRowData[]` 추가. `todayOrders`(→ `serverOrders`)는 기존 의미(PLANNED/PLACED) 그대로 유지되므로 `mode`/`hasServerOrders`/취소 로직은 **변경 없음**.

### `OrderRows.tsx`

- `status === 'REJECTED'`일 때: 회색/취소선 스타일 + "예수금 부족으로 미접수" 라벨(방향 배지 옆 또는 별도 텍스트).
- 취소 버튼: 현재 `o.id` 존재 여부로만 렌더링하는데, REJECTED는 `id`가 있어도 취소 대상이 아니므로 `status`가 `PLANNED`/`PLACED`일 때만 취소 버튼을 그리도록 조건 추가.

### `StrategyDetail.tsx`

- `mode === 'executed'`일 때만 `rejectedOrders`를 `placedOrders`와 합쳐 `OrderRows`에 전달(사용자가 선택한 "같은 목록에 섞어서, 상태만 다르게 표시"). `mode === 'preview'`(오늘 아직 승인된 주문이 하나도 없는 상태)에서는 `preview.orders`(재계산된 살아있는 계획)가 더 유용하므로 `rejectedOrders`를 목록에 섞지 않는다 — 전량 거절돼 `todayOrders`가 비어 있던 날에도 REJECTED 이력 자체는 이력 탭에서 항상 확인 가능하다.
- 헤더 배지 문구: `mode === 'executed'`이고 `rejectedOrders.length > 0`이면 "N건 접수됨" → "N건 접수됨 · M건 거절"로 합산 표기. `placedOrders.length`(REJECTED 미포함) 기준 카운트는 그대로 유지.
- `StrategyOrderHistory`(이력 탭)는 이미 status 필터 없는 조회를 쓰고 있어 REJECTED가 자동으로 노출됨 — 상태 라벨 매핑(`REJECTED` → "거절됨")만 추가하면 된다.

### `BuyCompetitionNotice` 처리 방침

기존 배지는 라이브 재시뮬레이션 근사치라 이번에 확인된 사례처럼 실제 거절과 어긋날 수 있다(가설, 미확정). REJECTED 영속화 이후에도 완전히 대체되는 건 아니므로 용도를 분리한다:
- **`mode === 'preview'`**(아직 오늘 확정된 결과가 없어 "지금 바로 주문하면 될까"가 유효한 질문인 상황): 기존 로직 그대로 유지.
- **`mode === 'executed'`**(REJECTED 등 실제 결과가 이미 화면에 사실 그대로 표시되는 상황): 헤더 인라인 배지(`StrategyDetail.tsx:229-231`)는 렌더링하지 않는다 — 근사치와 실측 이력이 동시에 노출되어 서로 다른 말을 하는 혼선을 피한다. 조건을 `hasDeficit && competition && mode === 'preview'`로 변경.

## 에러 처리

- REJECTED 저장 실패는 `runSafely`로 격리되어 다른 사이클의 SELL 저장·PLANNED 저장·알림 발송에 영향을 주지 않는다.
- `notifyInsufficientBalance` 알림은 기존 동작 그대로 유지(REJECTED 저장 성공 여부와 무관하게 발송).

## 테스트 계획

- `TradingServiceTest`:
  - 예수금 부족으로 BUY 거절 시 REJECTED row가 저장되는지, orderLeg·direction·quantity·price가 원본 후보와 일치하는지
  - SELL 거절도 동일하게 REJECTED로 저장되는지
  - 같은 사이클에서 SELL 승인 + BUY 거절이 동시에 일어날 때 SELL은 PLANNED, BUY는 REJECTED로 각각 정확히 저장되는지
  - REJECTED 저장이 예외를 던져도 같은 배치의 다른 사이클 처리가 계속되는지 (`runSafely` 격리)
  - **개장에서 거절된 leg가 마감에서도 다시 거절될 때 REJECTED 행이 1건으로 유지되는지(중복 저장 회귀)** — `saveRejectedOrders` 호출 전 `deleteRejectedByCycleAndDate`가 먼저 실행되는지 검증
- `OrderPersistenceAdapterTest`:
  - `findRejectedByCycleAndDate`가 REJECTED만 반환하는지
  - `deleteRejectedByCycleAndDate`가 REJECTED만 삭제하고 같은 사이클·거래일의 PLANNED/PLACED/FILLED 등 다른 상태는 건드리지 않는지
  - `findPlannedOrPlacedByCycleAndDate`가 REJECTED를 여전히 배제하는 회귀 테스트(가장 중요 — 슬롯 재시도가 걸려있음)
- `TradingPreviewServiceTest`:
  - **`todayOrders`에는 REJECTED가 절대 섞이지 않는지(회귀)** — PLANNED/PLACED만 있던 기존 테스트가 그대로 통과해야 함
  - REJECTED만 있고 재시도 성공(todayOrders)이 없으면 `rejectedOrders`에 노출되는지
  - 같은 orderLeg로 재시도 성공한 경우 `rejectedOrders`에서 제외되는지
  - 예산 계산(`otherStrategiesPlannedBuyUsd` 등)이 REJECTED 존재 여부와 무관하게 동일한지(회귀)
- kista-ui:
  - `OrderRows` REJECTED 렌더링 스냅샷 테스트, 취소 버튼 미노출 테스트
  - `StrategyDetail`: 전량 거절(승인 0건)인 날 `mode`가 `preview`로 유지되는지, "N건 접수됨" 카운트에 REJECTED가 포함되지 않는지, `executed` 모드에서 `BuyCompetitionNotice` 헤더 배지가 렌더링되지 않는지
