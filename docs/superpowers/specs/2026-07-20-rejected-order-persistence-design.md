# 예산 배정 거절 사실 기록 — 매수/매도 거절 배너 (v2, 단순화)

> v1(개별 REJECTED 주문 row를 `orders`에 leg 단위로 영속화하는 안)은 advisor 검토를 거치며 leg-scoped delete·`todayOrders`/`rejectedOrders` 필드 분리·취소버튼 가드·이력탭 렌더링까지 변경 범위가 계속 불어났다. 사용자 피드백("바로 주문 영역이 너무 복잡해지고 있다")에 따라 훨씬 가벼운 v2로 재설계한다. **v1의 문제 인식(배경)은 유효하므로 그대로 유지하고, 해결 방식만 바꾼다.**

## 배경

토스증권 PRIVACY 전략에서 예수금 부족 상태로 개장 스케쥴러(22:30 KST)가 실행되면, `TradingOrderBudgetAllocator`가 예산 부족으로 거절한 BUY 후보는 `TradingService.saveAllocatedOrders`에서 알림만 발송하고 DB에 전혀 흔적을 남기지 않는다. 같은 사이클의 SELL이 승인되면 정상 저장되므로, 사용자는 "매도만 접수되고 매수는 아무 정보도 없는" 화면을 보게 된다.

kista-ui의 `BuyCompetitionNotice` 배지는 조회 시점 라이브 잔고로 매번 새로 재시뮬레이션하는 근사치라, 스케쥴러 실행 시각과 화면 조회 시각 사이 잔고가 조금만 바뀌어도 실제 거절과 어긋나 배지가 안 뜰 수 있다 — 이번 실제 사례가 이 케이스로 추정된다.

## 설계 방향 전환

v1은 "거절된 각 주문의 수량·가격까지 SELL처럼 정확히 이력화"하는 데 초점을 맞췄고, 그러다 보니 `orders` 테이블의 슬롯 점유·재시도 의미론과 계속 충돌해 leg 단위 삭제·필드 분리 같은 방어 로직이 늘어났다.

v2는 목표를 낮춘다: 사용자가 알아야 할 건 **"오늘 예수금(또는 판매가능수량) 부족으로 매수(또는 매도)가 몇 건 접수 안 됐다"**는 사실이지, 개별 주문의 정확한 가격까지는 아니다. 이 사실은 **사이클 + 거래일 + 방향** 단위로 최대 2건(BUY 1, SELL 1)만 있으면 충분하므로, `orders`와 완전히 분리된 작은 테이블 하나로 표현한다.

이 전환으로 사라지는 것:
- `Order.OrderStatus.REJECTED` 신규 상태 — 불필요
- leg 단위 delete-replace, `orderLeg` 기반 dedup — 불필요 (방향 단위라 upsert 하나로 끝남)
- `NextOrdersPreview.todayOrders`/`rejectedOrders` 필드 분리, kista-ui `mode`/카운트/취소버튼 가드 — 불필요 (주문 목록 자체를 안 건드림)
- `OrderRows.tsx`/이력 탭에서 REJECTED 상태 렌더링 — 불필요
- 슬롯 점유·재시도 쿼리(`findPlannedOrPlacedByCycleAndDate`)와의 상호작용 — 애초에 무관해짐 (별도 테이블이라 orders 조회에 전혀 안 걸림)

## 범위

- BUY/SELL, 전략 타입(INFINITE/PRIVACY/VR) 구분 없이 동일 적용.
- 개장·마감 스케쥴러 모두 `saveAllocatedOrders`를 공유하므로 자동 적용.
- 화면엔 기존 주문 목록(`todayOrders`, `OrderRows`)을 전혀 건드리지 않고, "다음 주문" 카드에 배너 한 줄만 추가한다.

## 도메인 모델

`domain/model/order/OrderRejection.java` (신규):

```java
public record OrderRejection(
        UUID id,
        UUID strategyCycleId,
        LocalDate tradeDate,
        Order.OrderDirection direction,  // BUY=예수금 부족, SELL=판매가능수량 부족
        int orderCount                   // 이번 배정에서 거절된 주문 후보 건수
) {}
```

`domain/port/out/OrderRejectionPort.java` (신규):

```java
public interface OrderRejectionPort {
    // (strategyCycleId, tradeDate, direction) 자연키 upsert — 개장·마감이 같은 방향을 반복 거절해도 최신 건수로 덮어쓸 뿐 중복 행이 안 생긴다
    void upsert(UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction, int orderCount);

    // 재시도로 해당 방향이 승인되면 호출 — 더 이상 거절 상태가 아니므로 배너에서 사라져야 한다
    void deleteIfExists(UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction);

    List<OrderRejection> findByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);
}
```

## 영속성

신규 테이블 `order_rejections` (Flyway `V31__create_order_rejections.sql` — 실제 파일 작성 시 `ls src/main/resources/db/migration`으로 최신 버전 재확인 필수):

```sql
CREATE TABLE order_rejections (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,
    direction         VARCHAR(10)   NOT NULL,
    order_count       INTEGER       NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT order_rejections_strategy_cycle_id_fkey
        FOREIGN KEY (strategy_cycle_id) REFERENCES strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT uq_order_rejections_cycle_date_direction
        UNIQUE (strategy_cycle_id, trade_date, direction)
);

CREATE INDEX idx_order_rejections_cycle_date ON order_rejections(strategy_cycle_id, trade_date);
```

`OrderRejectionEntity` + `OrderRejectionJpaRepository`(package-private) + `OrderRejectionPersistenceAdapter`는 기존 `adapter/out/persistence/trade/` 3종 구성 패턴 그대로 따른다. `upsert`는 `INSERT ... ON CONFLICT (strategy_cycle_id, trade_date, direction) DO UPDATE SET order_count = EXCLUDED.order_count, updated_at = now()` native query로 구현 — JPA merge보다 자연키 upsert 의도가 명확하다.

## 애플리케이션 레이어

### `TradingService.saveAllocatedOrders`

```java
for (TradingOrderBudgetAllocator.Candidate approved : allocation.approved()) {
    Optional<BatchContext> saved = runSafely("계획 주문 저장", approved.ctx(), () -> {
        orderPlanner.savePlannedOrders(approved.orders(), approved.ctx().account(), approved.ctx().currentCycle().id());
        return approved.ctx();
    });
    if (saved.isPresent()) {
        savedContexts.add(saved.get());
        // 이번 run에서 승인된 각 방향에 대해 과거 거절 배너를 해소한다
        Set<Order.OrderDirection> approvedDirections = approved.orders().stream()
                .map(Order::direction).collect(Collectors.toSet());
        for (Order.OrderDirection direction : approvedDirections) {
            runSafely("거절 배너 해소", approved.ctx(), () -> {
                orderRejectionPort.deleteIfExists(approved.ctx().currentCycle().id(), tradeDate, direction);
                return null;
            });
        }
    }
}

for (TradingOrderBudgetAllocator.Candidate candidate : allocation.rejectedBuy()) {
    runSafely("거절 배너 기록", candidate.ctx(), () -> {
        orderRejectionPort.upsert(candidate.ctx().currentCycle().id(), tradeDate, Order.OrderDirection.BUY, candidate.orders().size());
        return null;
    });
}
for (TradingOrderBudgetAllocator.Candidate candidate : allocation.rejectedSell()) {
    runSafely("거절 배너 기록", candidate.ctx(), () -> {
        orderRejectionPort.upsert(candidate.ctx().currentCycle().id(), tradeDate, Order.OrderDirection.SELL, candidate.orders().size());
        return null;
    });
}

Set<BatchContext> rejectedContexts = ...; // 기존 알림 발송 로직 변경 없음
```

**주의**: `allocation.approved()`(`mergeApproved`)는 방향별로 분리되지 않는다 — 한 사이클에서 BUY·SELL이 같은 run에 함께 승인되면 병합된 `Candidate.orders()`에 두 방향이 섞여 들어온다(`TradingOrderBudgetAllocator.java` 주석: "Approved contains only approved directions per candidate" — direction을 필터링해서 포함할 뿐 단일 방향으로 축소하지 않는다는 뜻). 그래서 해소 루프는 `getFirst().direction()`이 아니라 **`orders()`에 실제 존재하는 방향 집합을 순회**해야 한다. 반대로 `rejectedBuy`/`rejectedSell`은 `allocateBuysForAccount`/`allocateSellsForAccountTicker` 단계에서 이미 방향별로 분리된 리스트이므로 어느 리스트에서 왔는지로 방향이 확정된다 — 별도 파생 불필요.

저장·해소·기록 모두 기존과 동일하게 `runSafely`로 개별 격리한다.

### `TradingPreviewService.preview()`

```java
List<OrderRejection> rejections = orderRejectionPort.findByCycleAndDate(currentCycle.id(), today);
return new NextOrdersPreview(today, plan.position(), plan.orders(), null,
        todayPlannedOrders, otherStrategiesPlannedBuyUsd, competition, rejections);
```

`todayPlannedOrders`(PLANNED/PLACED 목록)는 v1 이전과 완전히 동일 — 변경 없음. `NextOrdersPreview`에 `List<OrderRejection> rejections` 필드만 추가.

## 어댑터 — API 응답

`NextOrdersResponse`에 `rejections: List<RejectionResponse>` 필드 추가:

```java
public record RejectionResponse(String direction, int orderCount) {
    public static RejectionResponse from(OrderRejection r) { return new RejectionResponse(r.direction().name(), r.orderCount()); }
}
```

## 프론트엔드 (kista-ui)

- `useStrategyOrderPreviewQuery` 반환 타입에 `rejections?: { direction: 'BUY'|'SELL'; orderCount: number }[]` 추가.
- `StrategyDetail.tsx`: "다음 주문" 카드 헤더에 `rejections`가 있으면 방향별로 한 줄씩 배너 렌더링 — "예수금 부족으로 매수 3건 미접수" / "판매가능수량 부족으로 매도 2건 미접수". **`mode`(preview/executed)와 무관하게 항상 렌더링** — 기존 주문 목록(`OrderRows`, `placedOrders`, 취소 로직)은 전혀 건드리지 않으므로 mode 분기 자체가 필요 없다.
- `StrategyDetail.tsx`의 헤더 인라인 `BuyCompetitionNotice`(`:229-231`)는 **제거**한다 — 새 거절 배너가 사실 그대로의 단일 정보원이 되므로, 근사치 재시뮬레이션 배지를 "다음 주문" 카드에 남겨두면 같은 자리에서 서로 다른 근거(라이브 재계산 vs 실측 이력)로 같은 말을 반복해 오히려 복잡도를 늘린다.
  - `competition`/`hasDeficit` 계산 로직 자체와 `preview.competition` 필드는 그대로 둔다 — **`StrategyCard.tsx`(전략 목록 카드)가 같은 `competition`을 목록 카드 테두리 색상(예수금 부족 시 빨간 테두리)에 별도로 쓰고 있어**, 이번 변경 범위(다음 주문 카드)를 벗어나 목록 페이지까지 건드리지 않는다. 백엔드 `TradingBuyCompetitionSimulator`/`BuyCompetitionPreview`도 변경 없음.
  - `CardContent` 내부의 row-variant 배지(`StrategyDetail.tsx:308-310`, `orders.length===0` preview 분기 안에 위치)는 그대로 유지 — 이건 "바로 주문" 버튼을 누르기 전 미리보기 상황에서만 쓰이므로 이번 변경과 별개다.
- 이력 탭(`StrategyOrderHistory`)은 이번 변경과 무관 — REJECTED 개념 자체가 `orders`에 없으므로 아무것도 안 해도 된다.

## 에러 처리

- `upsert`/`deleteIfExists` 실패는 `runSafely`로 격리되어 다른 사이클의 저장·알림에 영향 없음.
- `notifyInsufficientBalance` 알림은 기존 동작 그대로 유지.

## 테스트 계획

- `TradingServiceTest`:
  - 예수금 부족으로 BUY 거절 시 `order_rejections`에 `(cycle, date, BUY, count)`가 upsert되는지
  - SELL 거절도 동일하게 기록되는지
  - 같은 사이클에서 개장 때 거절, 마감 때도 다시 거절되면 행이 1건으로 유지되고 `orderCount`가 최신값으로 갱신되는지 (upsert 회귀)
  - 거절 후 재시도로 승인되면 해당 방향의 거절 행이 삭제되는지 (`deleteIfExists` 호출 검증)
  - **같은 사이클에서 BUY 과거 거절 이력이 있는 상태로, 이번 run에 BUY+SELL이 동시에(병합된 하나의 `Candidate.orders()`로) 승인될 때 BUY 거절 배너가 정상 해소되는지** — `mergeApproved`가 방향을 병합한다는 사실을 놓치면 `getFirst().direction()`만 보고 SELL만 해소하고 BUY 배너가 안 지워지는 회귀가 생긴다
  - 거절 기록 실패가 다른 사이클의 SELL 저장·알림 발송을 막지 않는지 (`runSafely` 격리)
- `OrderRejectionPersistenceAdapterTest`: upsert 자연키 충돌 시 덮어쓰기, `deleteIfExists`가 존재하지 않아도 예외 없이 no-op인지
- `TradingPreviewServiceTest`: `rejections` 필드가 `findByCycleAndDate` 결과를 그대로 반영하는지, 기존 `todayPlannedOrders`/예산 계산 관련 테스트는 무변경으로 통과하는지(회귀)
- kista-ui:
  - `StrategyDetail`에서 `rejections` 배너가 `mode`와 무관하게 렌더링되는지
  - `StrategyDetail` 헤더에서 `BuyCompetitionNotice`가 더 이상 렌더링되지 않는지(제거 회귀) — `CardContent` 내부 preview 분기의 row-variant 배지는 기존 그대로 유지되는지 별도 확인
  - `StrategyCard`(목록 카드) 테두리 색상 로직은 이번 변경으로 회귀가 없는지(무변경 확인용 스냅샷)
