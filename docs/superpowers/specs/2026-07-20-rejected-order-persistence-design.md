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

```java
// 예산 배정 거절 후보를 REJECTED로 기록 (브로커 미접수 이력 보존)
void saveRejectedOrders(List<Order> templates, Account account, UUID strategyCycleId) {
    List<Order> rejected = templates.stream()
            .map(o -> Order.rejected(o, account.id(), strategyCycleId))
            .toList();
    orderPort.saveAll(rejected);
    log.info("[{}] 거절 주문 {}건 기록 (REJECTED)", account.nickname(), rejected.size());
}
```

### `TradingService.saveAllocatedOrders`

거절 루프를 개별 `Candidate`(현재는 `BatchContext`로 dedup되어 있어 `orders()`에 접근 못 함) 단위로 순회하도록 변경한다:

```java
List<TradingOrderBudgetAllocator.Candidate> rejected = Stream.concat(
                allocation.rejectedBuy().stream(), allocation.rejectedSell().stream())
        .toList();

for (TradingOrderBudgetAllocator.Candidate candidate : rejected) {
    runSafely("거절 주문 기록", candidate.ctx(), () -> {
        orderPlanner.saveRejectedOrders(
                candidate.orders(), candidate.ctx().account(), candidate.ctx().currentCycle().id());
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

신규 조회 메서드 추가 — **기존 `findPlannedOrPlacedByCycleAndDate`는 변경하지 않는다**:

```java
List<Order> findRejectedByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);
```

`OrderPersistenceAdapter`/`OrderJpaRepository`에 `findByStrategyCycleIdAndTradeDateAndStatusIn(..., List.of(REJECTED))` 패턴으로 구현 (기존 `findPlannedOrPlacedByCycleAndDate`와 동일한 헬퍼 재사용).

### `TradingPreviewService.preview()`

예산 계산에 쓰이는 `activeOrders`(PLANNED/PLACED)와 화면 표시용 목록을 분리한다:

```java
List<Order> activeOrders = orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today); // 예산 계산 입력 — 변경 없음
List<Order> rejectedOrders = orderPort.findRejectedByCycleAndDate(currentCycle.id(), today);

// 같은 leg가 이후 재시도로 성공(PLANNED/PLACED 존재)했으면 당일 카드에서는 REJECTED 숨김
Set<String> activeLegs = activeOrders.stream().map(Order::orderLeg).collect(Collectors.toSet());
List<Order> displayOrders = Stream.concat(
        activeOrders.stream(),
        rejectedOrders.stream().filter(o -> !activeLegs.contains(o.orderLeg())))
        .toList();
```

`otherStrategiesPlannedBuyUsd`/`thisStrategyPlannedBuy` 등 예산 관련 계산은 계속 `activeOrders`만 사용한다 — REJECTED가 섞이면 이미 거절된 금액이 "당일 예약분"으로 잘못 합산돼 예산 판정이 왜곡된다. `NextOrdersPreview.todayOrders` 필드에는 `displayOrders`를 전달한다.

## 어댑터 — API 응답

`adapter/in/web/dto/NextOrdersResponse.java`의 order 항목에 `status` 필드가 이미 있다면 그대로 노출(REJECTED 포함), 없다면 추가한다. 별도 스키마 변경은 이 필드 하나로 충분하다.

## 프론트엔드 (kista-ui)

### `OrderRows.tsx`

- `status === 'REJECTED'`일 때: 회색/취소선 스타일 + "예수금 부족으로 미접수" 라벨(방향 배지 옆 또는 별도 텍스트).
- 취소 버튼: 현재 `o.id` 존재 여부로만 렌더링하는데, REJECTED는 `id`가 있어도 취소 대상이 아니므로 `status`가 `PLANNED`/`PLACED`일 때만 취소 버튼을 그리도록 조건 추가.

### `StrategyDetail.tsx`

- 헤더 배지 문구를 "N건 접수됨" → REJECTED 포함 시 "N건 접수됨 · M건 거절"로 합산 표기.
- `StrategyOrderHistory`(이력 탭)는 이미 status 필터 없는 조회를 쓰고 있어 REJECTED가 자동으로 노출됨 — 상태 라벨 매핑(`REJECTED` → "거절됨")만 추가하면 된다.

## 에러 처리

- REJECTED 저장 실패는 `runSafely`로 격리되어 다른 사이클의 SELL 저장·PLANNED 저장·알림 발송에 영향을 주지 않는다.
- `notifyInsufficientBalance` 알림은 기존 동작 그대로 유지(REJECTED 저장 성공 여부와 무관하게 발송).

## 테스트 계획

- `TradingServiceTest`:
  - 예수금 부족으로 BUY 거절 시 REJECTED row가 저장되는지, orderLeg·direction·quantity·price가 원본 후보와 일치하는지
  - SELL 거절도 동일하게 REJECTED로 저장되는지
  - 같은 사이클에서 SELL 승인 + BUY 거절이 동시에 일어날 때 SELL은 PLANNED, BUY는 REJECTED로 각각 정확히 저장되는지
  - REJECTED 저장이 예외를 던져도 같은 배치의 다른 사이클 처리가 계속되는지 (`runSafely` 격리)
- `OrderPersistenceAdapterTest`:
  - `findRejectedByCycleAndDate`가 REJECTED만 반환하는지
  - `findPlannedOrPlacedByCycleAndDate`가 REJECTED를 여전히 배제하는 회귀 테스트(가장 중요 — 슬롯 재시도가 걸려있음)
- `TradingPreviewServiceTest`:
  - REJECTED만 있고 재시도 성공(activeOrders)이 없으면 `todayOrders`에 REJECTED가 포함되는지
  - 같은 orderLeg로 재시도 성공한 경우 `todayOrders`에서 REJECTED가 제외되는지
  - 예산 계산(`otherStrategiesPlannedBuyUsd` 등)이 REJECTED 존재 여부와 무관하게 동일한지(회귀)
- kista-ui: `OrderRows` REJECTED 렌더링 스냅샷 테스트, 취소 버튼 미노출 테스트
