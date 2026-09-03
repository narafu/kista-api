# Spring Modulith privacy 모듈 이전 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레거시 최상위(`com.kista.domain`/`application`/`adapter`, `Type.OPEN`)에 흩어진 FIDA 기준 매매표(PRIVACY 전략의 전역 SSOT 매매 계획) 애그리게이트를 신규 `com.kista.privacy` Spring Modulith `CLOSED` 모듈로 이전한다.

**Architecture:** finance/notify/broker/trading/market 5모듈 이전과 동일 패턴 — 모듈 내부는 기존 Hexagonal 레이어(`domain/application/adapter`)를 그대로 유지하고 최상위 패키지만 `com.kista.privacy`로 옮긴다. 순수 구조 이전이라 TDD red-green이 아닌 "이동 → import 정합화 → 컴파일/테스트 그린 → 커밋" 사이클. 단 privacy는 CLOSED 전환 시 드러나는 **모듈 순환 2건**(privacy↔trading 직접, privacy→notify→trading→privacy 전이)이 사전 실측으로 확인됐으므로, 물리 이전 전(Task 1)과 모듈 선언 직전(Task 4)에 각각 순환을 끊는 코드 변경 태스크를 둔다. `Spring Modulith`가 모듈 경계를, 기존 `HexagonalArchitectureTest`가 모듈 내부 레이어 방향을 검증한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle, JUnit 5

**Spec:** `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md` ("착수 순서 (실측 기반, v2)" 2단계 — privacy)

## Global Constraints

- **[스펙 정정] 스펙의 "privacy: trading.Order 직접 참조 4개 파일" 추정은 실측 결과 다르다.**
  - forward(privacy→trading): `trading.domain.model.Order`를 참조하는 privacy 파일은 **6개** — `PrivacyTradeBase`, `FidaOrderCommand`, `PrivacyTradeValidationService`, `PrivacyTradePersistenceAdapter`, `PrivacyTradeBaseOrderEntity`, `FidaOrderResponse`. 전부 `Order`의 nested enum `OrderType`(LOC/MOC/LIMIT)·`OrderDirection`(BUY/SELL)와 4개 필드(direction/orderType/quantity/price)만 사용. → Task 1에서 privacy 자체 소유 타입(`PrivacyOrderType`/`PrivacyOrderDirection`/`FidaPlannedOrder`)으로 교체.
  - backward(trading→privacy): `com.kista.domain.model.privacy.PrivacyTradeBase`(또는 `PrivacyTradePort`/`PrivacyTradeValidationReport`/`PrivacyTradeValidationUseCase`)를 import하는 trading 파일은 **18개**. 그중 **실제 코드 변경이 필요한 건 `PrivacyStrategy.java` 1개**(privacy enum → trading `Order.OrderType` 매핑). 나머지 17개는 import 경로 치환뿐(privacy CLOSED 전환 후 `com.kista.privacy.domain.model.*`를 "domain" NamedInterface로 소비 — 정상 단방향).
- **[스펙 정정] privacy→notify→trading→privacy 전이 순환이 존재한다.** 스펙 "결합도 실측" 표의 pairwise 검증이 놓쳤다(market의 `market→notify→trading→market`와 동일 유형 맹점). `PrivacyService`가 `NotifyPort.notifyError`/`notifyInfo`를 직접 호출(privacy→notify) + `TradingAlertNotifier`가 trading 이벤트 구독(notify→trading, 기존 설계) + trading 18개 파일이 privacy 참조(trading→privacy). → Task 4에서 `PrivacyService`의 notify 직접 호출을 `PrivacyAlertRaisedEvent` 발행으로 전환, notify가 `PrivacyAlertNotifier`로 구독(market의 `FearGreedFetchFailedEvent`/`MarketAlertNotifier`와 동일 패턴).
- **enum 상수명 byte-identical 유지 필수.** `privacy_trade_base_orders.direction`/`order_type` 컬럼이 `@Enumerated(EnumType.STRING)`으로 값을 그대로 저장(`BUY`/`SELL`, `LOC`/`MOC`/`LIMIT`). privacy 자체 소유 enum의 상수명이 `Order.OrderType`/`Order.OrderDirection`과 다르면 기존 운영 DB 행이 역직렬화 실패. `PrivacyStrategy`의 매핑도 `Enum.valueOf(name())` 기반이라 이름 불일치 시 런타임 `IllegalArgumentException`. broker의 `Direction`/`OrderType` 복제와 동일 계약.
- **FIDA 인바운드 JSON 계약은 안전하다(확인 완료).** `FidaOrderController`는 `/api/internal/**`(외부 FIDA 시스템이 POST). `FidaOrderCommand.orders`를 `List<Order>`(15필드)에서 privacy 4필드 record로 좁혀도: Spring Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false`라 FIDA가 보내는 추가 필드는 조용히 무시됨(프로젝트에 이 설정 override 없음). 응답 `FidaOrderResponse.OrderItem`은 이미 direction/orderType/quantity/price 4필드(String/String/Integer/BigDecimal)만 — 아웃바운드 계약 변화 0.
- **소유권 경계 — `AdminPrivacyTradeController`/`AdminPrivacyBaseResponse`는 레거시 잔류(admin 소유).** `AdminPrivacyTradeController`는 `AdminQueryUseCase`(admin 소유, 레거시 잔류)만 참조하고 privacy를 직접 참조하지 않는다. `AdminPrivacyBaseResponse`는 `PrivacyTradeBaseView`(privacy가 "domain"으로 공개)를 참조하므로 import 경로만 갱신. 이 둘을 privacy로 옮기면 `privacy→admin` 방향이 생기고, `AdminQueryService`가 이미 `PrivacyTradePort`를 소비(`admin→privacy`)하므로 admin 모듈 이전 시 `privacy↔admin` 순환을 미리 만들게 된다. finance의 `AdminFinanceCategoryController`가 finance에 남은 선례는 그 컨트롤러가 *finance 자신의* usecase를 소비했기 때문이라 여기 적용 안 됨. privacy는 자기 인바운드(`FidaOrderController`)만 가져간다.
- **문자열 리터럴 FQN 사전 스캔.** broker 이전 때 `application-prod.yml`의 Logback 로거 이름(`com.kista.adapter.out.toss.*`)이 조용히 깨진 사례. Task 2 시작 전 `grep -rn "com\.kista\.domain\.model\.privacy\|com\.kista\.application\.service\.privacy\|com\.kista\.adapter\.\(in\.web\.FidaOrderController\|out\.persistence\.privacy\)" src/main/resources/ src/main/java --include='*.yml' --include='*.xml'` + AOP 포인트컷(`@Around`/`@Pointcut` 문자열) grep 실행. 매치 시 해당 태스크에서 함께 갱신.
- **`ApplicationModules.verify()` 게이트를 Task 3 직후(모듈 선언 전)에 1회 실행.** 사전 실측으로 순환 2건을 특정했지만 pairwise 한계가 또 있을 수 있음(market 교훈). Task 4에서 순환 해소 후 재실행, Task 5에서 최종. verify가 예측 못한 3번째 순환을 보고하면 즉시 멈추고 보고(추측 수정 금지).
- **와일드카드 import 주의.** `import com.kista.domain.model.privacy.*;`를 쓰는 파일이 2개 확인됨(`PrivacyTradePersistenceAdapter`, `PrivacyTradePort`). sed 경로 치환은 `com.kista.domain.model.privacy.*` → `com.kista.privacy.domain.model.*` 패턴도 포함할 것.
- 커밋 전 검토자(리뷰어 서브에이전트) 검수 필수 — 전역 CLAUDE.md 규칙, 문서 전용 변경(Task 6) 제외.
- Git author `narafu <narafu@kakao.com>`, 커밋 메시지 한글 Conventional Commit.

---

## Task 1: privacy 자체 소유 주문 타입 신설 + privacy↔trading `Order` 직접 참조 제거

물리 이전은 하지 않는다(privacy는 레거시 위치 유지). `trading.domain.model.Order` 결합만 끊어, 이후 CLOSED 전환 시 `privacy↔trading` 직접 순환이 생기지 않게 한다.

**Files:**
- Create: `src/main/java/com/kista/domain/model/privacy/PrivacyOrderType.java`
- Create: `src/main/java/com/kista/domain/model/privacy/PrivacyOrderDirection.java`
- Create: `src/main/java/com/kista/domain/model/privacy/FidaPlannedOrder.java`
- Modify: `src/main/java/com/kista/domain/model/privacy/FidaOrderCommand.java`
- Modify: `src/main/java/com/kista/domain/model/privacy/PrivacyTradeBase.java`
- Modify: `src/main/java/com/kista/application/service/privacy/PrivacyTradeValidationService.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseOrderEntity.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradePersistenceAdapter.java`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/FidaOrderResponse.java`
- Modify: `src/main/java/com/kista/trading/domain/strategy/PrivacyStrategy.java`
- Test: `src/test/java/com/kista/adapter/in/web/FidaOrderControllerTest.java`, `src/test/java/com/kista/application/service/privacy/PrivacyServiceTest.java`, `src/test/java/com/kista/trading/domain/strategy/PrivacyStrategyTest.java`, `src/test/java/com/kista/adapter/out/persistence/privacy/PrivacyTradePersistenceAdapterTest.java`, `src/test/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseEntityTest.java`, `src/test/java/com/kista/domain/model/privacy/PrivacyTradeBaseTest.java`

**Interfaces:**
- Produces: `com.kista.domain.model.privacy.PrivacyOrderType{LOC,MOC,LIMIT}`, `com.kista.domain.model.privacy.PrivacyOrderDirection{BUY,SELL}`, `com.kista.domain.model.privacy.FidaPlannedOrder(PrivacyOrderDirection direction, PrivacyOrderType orderType, Integer quantity, BigDecimal price)` — Task 2가 이 3타입을 `com.kista.privacy.domain.model`로 옮긴다.
- `PrivacyTradeBase.PrivacyTrade`의 `orderType`/`direction` 필드 타입이 `PrivacyOrderType`/`PrivacyOrderDirection`로 바뀐다(생성자 시그니처 위치·개수 불변).
- `FidaOrderCommand.orders`가 `List<Order>` → `List<FidaPlannedOrder>`.

- [ ] **Step 1: privacy 자체 소유 enum 2개 작성**

```java
// src/main/java/com/kista/domain/model/privacy/PrivacyOrderType.java
package com.kista.domain.model.privacy;

// FIDA 기준 매매표 주문 유형 — trading.domain.model.Order.OrderType 값 집합만 동일한 privacy 자체 소유 타입.
// 모듈 경계상 privacy가 trading 타입을 참조할 수 없어 복제(broker의 Direction/OrderType 복제와 동일 패턴).
// 상수명은 Order.OrderType과 반드시 byte-identical — privacy_trade_base_orders.order_type이 이 이름을
// @Enumerated(STRING)으로 저장하고, PrivacyStrategy가 valueOf(name())으로 trading 타입에 매핑한다.
public enum PrivacyOrderType {
    LOC,   // Limit On Close
    MOC,   // Market On Close
    LIMIT  // 일반 지정가
}
```

```java
// src/main/java/com/kista/domain/model/privacy/PrivacyOrderDirection.java
package com.kista.domain.model.privacy;

// FIDA 기준 매매표 매매 방향 — trading.domain.model.Order.OrderDirection 복제. 상수명 byte-identical 유지 필수.
public enum PrivacyOrderDirection {
    BUY,
    SELL
}
```

- [ ] **Step 2: `FidaPlannedOrder` record 작성**

```java
// src/main/java/com/kista/domain/model/privacy/FidaPlannedOrder.java
package com.kista.domain.model.privacy;

import java.math.BigDecimal;

// FIDA 수신 계획 주문 1건 — trading.domain.model.Order 전체(15필드) 대신 FIDA가 실제로 보내고 privacy가
// 실제로 읽는 4필드만 담는다. Jackson 역직렬화 시 추가 필드는 무시된다(Spring Boot 기본 FAIL_ON_UNKNOWN=false).
public record FidaPlannedOrder(
        PrivacyOrderDirection direction, // 매수/매도
        PrivacyOrderType orderType,      // LOC / MOC / LIMIT
        Integer quantity,                // 주문 수량 (nullable — SELL "잔량 전부" 의미)
        BigDecimal price                 // 주문 가격
) {
}
```

- [ ] **Step 3: `FidaOrderCommand` 수정 — `Order` 참조 제거**

`import com.kista.trading.domain.model.Order;` 제거. `List<Order> orders` → `List<FidaPlannedOrder> orders`. `@AssertTrue` 본문의 `Order.OrderDirection.BUY` → `PrivacyOrderDirection.BUY`.

```java
package com.kista.domain.model.privacy;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kista.domain.model.strategy.Strategy.Ticker;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FidaOrderCommand(
        @NotNull @JsonAlias("tradeDate") LocalDate releaseDate, // FIDA 발행일 원본 (KST) — 거래일 아님
        @NotNull Ticker ticker,
        @NotNull @Positive BigDecimal currentCycleStart,
        @NotNull BigDecimal currentCycleRealizedPnl,
        @Nullable BigDecimal avgPrice,
        @PositiveOrZero int holdings,
        List<FidaPlannedOrder> orders
) {
    // quantity=null은 "남은 전부 매도"를 의미 — SELL 방향에서만 허용
    @AssertTrue(message = "BUY 주문의 quantity는 null일 수 없습니다")
    public boolean isBuyQuantityValid() {
        return orders == null || orders.stream()
                .filter(o -> o.direction() == PrivacyOrderDirection.BUY)
                .allMatch(o -> o.quantity() != null);
    }
}
```

- [ ] **Step 4: `PrivacyTradeBase` 수정 — nested `PrivacyTrade`의 enum 타입 교체**

`import com.kista.trading.domain.model.Order;` 제거. `PrivacyTrade` record의 `Order.OrderType orderType` → `PrivacyOrderType orderType`, `Order.OrderDirection direction` → `PrivacyOrderDirection direction`. (같은 패키지라 import 불필요.) 나머지 필드·생성자 검증(`currentCycleStart`)은 그대로.

```java
    public record PrivacyTrade(
            LocalDate tradeDate,             // 거래일
            Strategy.Ticker ticker,          // 거래 종목
            PrivacyOrderType orderType,      // 주문 유형 (LOC/MOC/LIMIT)
            PrivacyOrderDirection direction, // 매수/매도 방향
            Integer quantity,                // 주문 수량(nullable)
            BigDecimal price                 // 주문 가격 (LOC/MOC는 참고용)
    ) {
    }
```

- [ ] **Step 5: `PrivacyTradeValidationService` 수정**

`import com.kista.trading.domain.model.Order;` 제거. `Order.OrderDirection` → `PrivacyOrderDirection`(2곳: `sellOrders` 필터, `OrderLine` record 필드). `command.orders().stream().map(o -> new OrderLine(o.direction(), o.quantity()))`·`base.trades().stream().map(t -> new OrderLine(t.direction(), t.quantity()))`는 시그니처만 맞으면 그대로(둘 다 이제 `PrivacyOrderDirection` 반환).

```java
package com.kista.application.service.privacy;

import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyOrderDirection;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.application.usecase.PrivacyTradeValidationUseCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
class PrivacyTradeValidationService implements PrivacyTradeValidationUseCase {
    // ... inspect(FidaOrderCommand) / inspect(PrivacyTradeBase) 본문 그대로 ...

    private PrivacyTradeValidationReport inspect(int holdings, List<OrderLine> orders) {
        List<PrivacyTradeValidationReport.Issue> issues = new ArrayList<>();
        List<OrderLine> sellOrders = orders.stream()
                .filter(o -> o.direction() == PrivacyOrderDirection.SELL)
                .toList();
        // ... 나머지 4개 규칙 블록 그대로 ...
        return new PrivacyTradeValidationReport(issues);
    }

    private record OrderLine(
            PrivacyOrderDirection direction,
            Integer quantity
    ) {
    }
}
```

- [ ] **Step 6: `PrivacyTradeBaseOrderEntity` 수정 — enum 필드 타입 교체**

`import com.kista.trading.domain.model.Order;` 제거, `import com.kista.domain.model.privacy.PrivacyOrderDirection;`·`import com.kista.domain.model.privacy.PrivacyOrderType;` 추가. `@Column(length=5/10)`·`@Enumerated(EnumType.STRING)`은 그대로(상수명 동일이라 DDL·데이터 불변).

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private PrivacyOrderDirection direction;    // BUY / SELL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PrivacyOrderType orderType;         // LOC / MOC / LIMIT
```

- [ ] **Step 7: `PrivacyTradePersistenceAdapter` 수정**

`import com.kista.trading.domain.model.Order;` 제거(`import com.kista.domain.model.privacy.*;`가 이미 `FidaPlannedOrder`/`PrivacyOrderDirection` 포함). 치환:
- `orderSort` 제네릭: `Function<T, Order.OrderDirection>` → `Function<T, PrivacyOrderDirection>`, 내부 `Order.OrderDirection.BUY` → `PrivacyOrderDirection.BUY` (2곳).
- `Comparator<Order> ORDER_SORT` → `Comparator<FidaPlannedOrder> ORDER_SORT`, `orderSort(Order::direction, Order::price)` → `orderSort(FidaPlannedOrder::direction, FidaPlannedOrder::price)`.
- `BASE_ORDER_SORT`는 그대로(`PrivacyTradeBaseOrderEntity::getDirection`이 이제 `PrivacyOrderDirection` 반환).
- `saveBaseWithOrders`: `List<Order> sorted` → `List<FidaPlannedOrder> sorted`, `for (Order order : sorted)` → `for (FidaPlannedOrder order : sorted)`. `baseOrder.setDirection(order.direction())`·`setOrderType(order.orderType())`은 타입 일치(둘 다 privacy enum).
- `isIdentical`: `List<Order> incomingOrders` → `List<FidaPlannedOrder> incomingOrders`, `Order o = incomingOrders.get(i)` → `FidaPlannedOrder o`. `e.getDirection() != o.direction()`·`e.getOrderType() != o.orderType()` 그대로.
- `findTodayTrade`의 `new PrivacyTradeBase.PrivacyTrade(kstTradeDate, entity.getTicker(), p.getOrderType(), p.getDirection(), ...)` — `p.getOrderType()`/`p.getDirection()`이 이제 privacy enum이라 그대로 일치.

- [ ] **Step 8: `FidaOrderResponse` 수정**

`import com.kista.trading.domain.model.Order;` 제거, `import com.kista.domain.model.privacy.FidaPlannedOrder;` 추가. `OrderItem.from(Order order)` → `from(FidaPlannedOrder order)`. `order.direction().name()`·`order.orderType().name()`은 enum이라 그대로.

```java
        static OrderItem from(FidaPlannedOrder order) {
            return new OrderItem(
                    order.direction().name(),
                    order.orderType().name(),
                    order.quantity(),
                    order.price()
            );
        }
```

- [ ] **Step 9: `PrivacyStrategy`(trading) 수정 — privacy enum → trading `Order.OrderType` 매핑**

`import com.kista.domain.model.privacy.PrivacyOrderType;`·`import com.kista.domain.model.privacy.PrivacyOrderDirection;` 추가. `import static com.kista.trading.domain.model.Order.OrderDirection.BUY/SELL`·`AT_CLOSE`는 유지(SELL Order 생성에 계속 사용). 변경:

1. BUY/SELL 분리 루프의 `if (t.direction() == BUY)` → `if (t.direction() == PrivacyOrderDirection.BUY)` (line 42). **주의**: `import static com.kista.trading.domain.model.Order.OrderDirection.BUY`는 그대로 둔다 — 변경 후에도 `sortOrdersForStableLegs`(line 100 `order.direction() == BUY`, `order`는 `Order` 타입)와 `Order.planned(..., BUY, ...)`(line 87)에서 계속 쓰인다. 즉 이 파일에는 privacy 타입 비교(`t.direction()`)와 trading 타입 비교(`order.direction()`)가 공존하며 둘 다 의도된 것 — 리뷰어/구현자가 "중복 제거"로 static import를 지우지 않도록 line 42에 `// privacy enum 비교 — trading Order.OrderDirection.BUY(static import)와 별개` 주석 추가.
2. `buyEntries.add(new BuyEntry(t.price(), qty, t.orderType(), t.tradeDate(), t.ticker()))` → `t.orderType()`을 `toTradingType(t.orderType())`로 (line 49).
3. `buildSellOrders`의 `Order.planned(t.tradeDate(), t.ticker(), t.orderType(), SELL, qty, t.price(), AT_CLOSE)` → `toTradingType(t.orderType())` (line 128).
4. null SELL 템플릿: `Order.planned(nullTemplate.tradeDate(), nullTemplate.ticker(), nullTemplate.orderType(), SELL, remaining, nullTemplate.price(), AT_CLOSE)` → `toTradingType(nullTemplate.orderType())` (line 171~172).
5. `BuyEntry` 내부 클래스의 `Order.OrderType orderType` 필드·생성자 파라미터는 **그대로 유지**(line 234/238) — line 49에서 이미 `toTradingType`으로 변환해 전달하므로 line 87 `Order.planned(e.tradeDate, e.ticker, e.orderType, BUY, ...)`는 수정 불필요.
6. `sortOrdersForStableLegs` line 146~148의 `max.orderType()` — `max`는 `Order` 타입이라 수정 불필요.
7. 매핑 헬퍼 추가(클래스 하단, `BuyEntry` 위):

```java
    // privacy 자체 소유 주문유형 → trading Order.OrderType 매핑.
    // 두 enum의 상수명이 byte-identical(LOC/MOC/LIMIT)이라는 계약에 의존 — PrivacyOrderType 주석 참고.
    private static Order.OrderType toTradingType(PrivacyOrderType type) {
        return Order.OrderType.valueOf(type.name());
    }
```

- [ ] **Step 10: 테스트 파일 정합화**

각 테스트에서 `import com.kista.trading.domain.model.Order;` (및 `Order.*` static import)를 제거하고 privacy 타입으로 교체. 구조·assertion 스타일은 유지하고 생성 인자 타입만 바꾼다:

- `FidaOrderControllerTest.java`: `Order buyNullQuantity = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, null, ...)` → `FidaPlannedOrder buyNullQuantity = new FidaPlannedOrder(PrivacyOrderDirection.BUY, PrivacyOrderType.LIMIT, null, <price>)`. `FidaOrderCommand(...)` 생성 시 `List.of(buyNullQuantity)` 그대로.
- `PrivacyServiceTest.java`: `Order.planned(LocalDate.of(2026,6,30), Ticker.SOXL, Order.OrderType.LIMIT, Order.OrderDirection.BUY, 2, new BigDecimal("234.46"))` → `new FidaPlannedOrder(PrivacyOrderDirection.BUY, PrivacyOrderType.LIMIT, 2, new BigDecimal("234.46"))` (SELL 케이스도 동일 패턴). `@Mock NotifyPort notifyPort`는 **이 태스크에선 유지**(이벤트 전환은 Task 4).
- `PrivacyStrategyTest.java`: `PrivacyTradeBase.PrivacyTrade` 생성 인자 중 `Order.OrderType.LOC`/`LIMIT` → `PrivacyOrderType.*`, `Order.OrderDirection.BUY`/`SELL` → `PrivacyOrderDirection.*`. `buildOrders(...)` 결과를 `Order`로 검증하는 부분(반환값·leg·quantity)은 그대로.
- `PrivacyTradePersistenceAdapterTest.java`: `FidaOrderCommand`의 orders 인자를 `FidaPlannedOrder`로.
- `PrivacyTradeBaseEntityTest.java`: 엔티티 `setDirection`/`setOrderType` 인자를 privacy enum으로.
- `PrivacyTradeBaseTest.java`: `PrivacyTrade` 생성 인자를 privacy enum으로.

- [ ] **Step 11: 컴파일 + 관련 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음. 남으면 `git grep -n "com\.kista\.trading\.domain\.model\.Order" src/main/java/com/kista/domain/model/privacy src/main/java/com/kista/application/service/privacy src/main/java/com/kista/adapter/out/persistence/privacy src/main/java/com/kista/adapter/in/web/dto/FidaOrderResponse.java`로 잔존 참조 확인.

```bash
./gradlew test --tests 'com.kista.*Privacy*' --tests 'com.kista.*Fida*' --tests 'com.kista.trading.domain.strategy.PrivacyStrategyTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(privacy): FIDA 주문 타입을 trading.Order에서 privacy 자체 소유로 분리

PrivacyOrderType/PrivacyOrderDirection/FidaPlannedOrder 신설.
PrivacyTradeBase·FidaOrderCommand·검증·persistence·응답 DTO 6개 파일의
trading.domain.model.Order 직접 참조 제거. PrivacyStrategy는 privacy enum을
Order.OrderType으로 valueOf(name()) 매핑. privacy CLOSED 전환 시 생길
privacy↔trading 직접 순환의 forward 엣지를 사전 제거(broker Direction/OrderType
복제 패턴). enum 상수명은 DB @Enumerated(STRING) 호환 위해 동일 유지.
EOF
)"
```

---

## Task 2: 코어(domain + application) 물리 이전 + 전역 소비자 import 정합화

**Files:**
- Move: `src/main/java/com/kista/domain/model/privacy/*.java` (11개: `FidaOrderCommand`, `FidaPlannedOrder`, `PrivacyOrderType`, `PrivacyOrderDirection`, `PrivacyCurrentBase`, `PrivacyDates`, `PrivacyTradeBase`, `PrivacyTradeBaseView`, `PrivacyTradeConflictException`, `PrivacyTradeSaveResult`, `PrivacyTradeValidationReport`) → `src/main/java/com/kista/privacy/domain/model/`
- Move: `src/main/java/com/kista/application/service/privacy/{PrivacyService,PrivacyTradeValidationService}.java` → `src/main/java/com/kista/privacy/application/service/`
- Move: `src/main/java/com/kista/application/usecase/{PrivacyUseCase,PrivacyTradeValidationUseCase}.java` → `src/main/java/com/kista/privacy/application/usecase/`
- Move: `src/main/java/com/kista/application/port/output/PrivacyTradePort.java` → `src/main/java/com/kista/privacy/application/port/output/`
- Modify (import 경로만): 아래 Step 4의 전역 소비자 목록
- Move: `src/test/java/com/kista/domain/model/privacy/{PrivacyDatesTest,PrivacyTradeBaseTest}.java` → `src/test/java/com/kista/privacy/domain/model/`
- Move: `src/test/java/com/kista/application/service/privacy/PrivacyServiceTest.java` → `src/test/java/com/kista/privacy/application/service/`

**Interfaces:**
- Produces: `com.kista.privacy.domain.model.*`(11 타입), `com.kista.privacy.application.usecase.{PrivacyUseCase,PrivacyTradeValidationUseCase}`, `com.kista.privacy.application.port.output.PrivacyTradePort` — Task 3(어댑터)·Task 4(이벤트)·Task 5(모듈 선언)가 이 경로를 소비.

- [ ] **Step 0: 문자열 리터럴 FQN 사전 스캔**

```bash
# resources: Logback 로거 이름 등 (broker 이전 때 application-prod.yml이 조용히 깨진 그 카테고리)
grep -rn "com\.kista\.domain\.model\.privacy\|com\.kista\.application\.service\.privacy\|com\.kista\.application\.usecase\.Privacy\|com\.kista\.application\.port\.output\.PrivacyTradePort\|com\.kista\.adapter\.\(in\.web\.FidaOrderController\|out\.persistence\.privacy\)" src/main/resources/
# java: 문자열 리터럴 안의 FQN (AOP 포인트컷 문자열 등 — import는 컴파일러가 잡으므로 문자열만)
git grep -n '"[^"]*com\.kista\.[^"]*\(privacy\|FidaOrder\)' src/main/java
```
매치 있으면 Task 2 diff에 포함해 함께 갱신(로거 이름·포인트컷 문자열). 없으면 다음 스텝.

- [ ] **Step 1: 코어 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/privacy/domain/model
mkdir -p src/main/java/com/kista/privacy/application/service
mkdir -p src/main/java/com/kista/privacy/application/usecase
mkdir -p src/main/java/com/kista/privacy/application/port/output

git mv src/main/java/com/kista/domain/model/privacy/FidaOrderCommand.java            src/main/java/com/kista/privacy/domain/model/FidaOrderCommand.java
git mv src/main/java/com/kista/domain/model/privacy/FidaPlannedOrder.java            src/main/java/com/kista/privacy/domain/model/FidaPlannedOrder.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyOrderType.java            src/main/java/com/kista/privacy/domain/model/PrivacyOrderType.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyOrderDirection.java       src/main/java/com/kista/privacy/domain/model/PrivacyOrderDirection.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyCurrentBase.java          src/main/java/com/kista/privacy/domain/model/PrivacyCurrentBase.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyDates.java                src/main/java/com/kista/privacy/domain/model/PrivacyDates.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyTradeBase.java            src/main/java/com/kista/privacy/domain/model/PrivacyTradeBase.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyTradeBaseView.java        src/main/java/com/kista/privacy/domain/model/PrivacyTradeBaseView.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyTradeConflictException.java src/main/java/com/kista/privacy/domain/model/PrivacyTradeConflictException.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyTradeSaveResult.java      src/main/java/com/kista/privacy/domain/model/PrivacyTradeSaveResult.java
git mv src/main/java/com/kista/domain/model/privacy/PrivacyTradeValidationReport.java src/main/java/com/kista/privacy/domain/model/PrivacyTradeValidationReport.java
rmdir src/main/java/com/kista/domain/model/privacy

git mv src/main/java/com/kista/application/service/privacy/PrivacyService.java                src/main/java/com/kista/privacy/application/service/PrivacyService.java
git mv src/main/java/com/kista/application/service/privacy/PrivacyTradeValidationService.java src/main/java/com/kista/privacy/application/service/PrivacyTradeValidationService.java
rmdir src/main/java/com/kista/application/service/privacy

git mv src/main/java/com/kista/application/usecase/PrivacyUseCase.java               src/main/java/com/kista/privacy/application/usecase/PrivacyUseCase.java
git mv src/main/java/com/kista/application/usecase/PrivacyTradeValidationUseCase.java src/main/java/com/kista/privacy/application/usecase/PrivacyTradeValidationUseCase.java

git mv src/main/java/com/kista/application/port/output/PrivacyTradePort.java         src/main/java/com/kista/privacy/application/port/output/PrivacyTradePort.java

mkdir -p src/test/java/com/kista/privacy/domain/model
mkdir -p src/test/java/com/kista/privacy/application/service
git mv src/test/java/com/kista/domain/model/privacy/PrivacyDatesTest.java     src/test/java/com/kista/privacy/domain/model/PrivacyDatesTest.java
git mv src/test/java/com/kista/domain/model/privacy/PrivacyTradeBaseTest.java src/test/java/com/kista/privacy/domain/model/PrivacyTradeBaseTest.java
rmdir src/test/java/com/kista/domain/model/privacy
git mv src/test/java/com/kista/application/service/privacy/PrivacyServiceTest.java src/test/java/com/kista/privacy/application/service/PrivacyServiceTest.java
rmdir src/test/java/com/kista/application/service/privacy
```

- [ ] **Step 2: 이동 파일의 package 선언 + 내부 상호 import 치환**

```bash
find src/main/java/com/kista/privacy src/test/java/com/kista/privacy -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.domain\.model\.privacy;/package com.kista.privacy.domain.model;/' \
  -e 's/^package com\.kista\.application\.service\.privacy;/package com.kista.privacy.application.service;/' \
  -e 's/^package com\.kista\.application\.usecase;/package com.kista.privacy.application.usecase;/' \
  -e 's/^package com\.kista\.application\.port\.output;/package com.kista.privacy.application.port.output;/' \
  -e 's/com\.kista\.domain\.model\.privacy\./com.kista.privacy.domain.model./g' \
  -e 's/com\.kista\.application\.usecase\.PrivacyUseCase/com.kista.privacy.application.usecase.PrivacyUseCase/g' \
  -e 's/com\.kista\.application\.usecase\.PrivacyTradeValidationUseCase/com.kista.privacy.application.usecase.PrivacyTradeValidationUseCase/g' \
  -e 's/com\.kista\.application\.port\.output\.PrivacyTradePort/com.kista.privacy.application.port.output.PrivacyTradePort/g' \
  {} +
```

주의: sed는 macOS(`sed -i ''`). Linux면 `sed -i`. `PrivacyServiceTest.java`의 `package com.kista.application.service.privacy;`도 위 첫 규칙으로 함께 치환됨.

- [ ] **Step 3: privacy 패키지 내부 잔존 레거시 import 확인**

```bash
git grep -n "com\.kista\.domain\.model\.privacy\|com\.kista\.application\.\(service\|usecase\|port\.output\)\.\(Privacy\|privacy\)" src/main/java/com/kista/privacy src/test/java/com/kista/privacy
```
Expected: 결과 없음. `PrivacyTradePort.java`·`PrivacyTradePersistenceAdapter.java`의 `import com.kista.domain.model.privacy.*;` → `import com.kista.privacy.domain.model.*;`로 바뀌었는지 특히 확인(와일드카드).

- [ ] **Step 4: 전역 소비자 import 경로 치환 (이번 태스크에서 이동 안 하는 파일)**

trading 18개 + 비-trading 7개. 어댑터(`FidaOrderController`/`FidaOrderResponse`/persistence)는 Task 3에서 이동과 함께 처리하므로 **여기서는 제외**. `AdminPrivacyTradeController`는 privacy 직접 참조 없어 제외.

```bash
sed -i '' \
  -e 's#com\.kista\.domain\.model\.privacy\.#com.kista.privacy.domain.model.#g' \
  -e 's#com\.kista\.application\.usecase\.PrivacyTradeValidationUseCase#com.kista.privacy.application.usecase.PrivacyTradeValidationUseCase#g' \
  -e 's#com\.kista\.application\.port\.output\.PrivacyTradePort#com.kista.privacy.application.port.output.PrivacyTradePort#g' \
  src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java \
  src/main/java/com/kista/trading/application/service/TradingService.java \
  src/main/java/com/kista/trading/application/service/TradingReporter.java \
  src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java \
  src/main/java/com/kista/trading/application/service/ManualTradingService.java \
  src/main/java/com/kista/trading/application/service/StrategyOrderPlanBuilder.java \
  src/main/java/com/kista/trading/application/service/CycleRotationService.java \
  src/main/java/com/kista/trading/application/service/CycleOrderComputer.java \
  src/main/java/com/kista/trading/domain/strategy/PrivacyCycleOrderStrategy.java \
  src/main/java/com/kista/trading/domain/strategy/CycleOrderStrategy.java \
  src/main/java/com/kista/trading/domain/strategy/InfiniteCycleOrderStrategy.java \
  src/main/java/com/kista/trading/domain/strategy/PrivacyStrategy.java \
  src/main/java/com/kista/trading/domain/strategy/VrCycleOrderStrategy.java \
  src/main/java/com/kista/application/service/account/AccountStatisticsService.java \
  src/main/java/com/kista/application/service/admin/AdminQueryService.java \
  src/main/java/com/kista/application/service/backtest/BacktestService.java \
  src/main/java/com/kista/application/usecase/AdminQueryUseCase.java \
  src/main/java/com/kista/domain/backtest/BacktestEngine.java \
  src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java
```

그 다음 잔존 참조 전수 확인(누락 파일 색출):

```bash
git grep -ln "com\.kista\.domain\.model\.privacy\|com\.kista\.application\.usecase\.PrivacyTradeValidationUseCase\|com\.kista\.application\.port\.output\.PrivacyTradePort" src/main src/test \
  | grep -v "src/main/java/com/kista/privacy/\|src/test/java/com/kista/privacy/\|/FidaOrderController.java\|/FidaOrderResponse.java\|/adapter/out/persistence/privacy/"
```
Expected: 결과 없음. 나오면 그 파일도 위 sed 대상에 추가.

- [ ] **Step 5: 컴파일 확인 (완전 그린은 Task 3 이후)**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|FAILED"
```
Expected: `FidaOrderController`/`FidaOrderResponse`/`PrivacyTradePersistenceAdapter`/`PrivacyTradeBaseEntity`/`PrivacyTradeBaseJpaRepository`(아직 레거시 위치, 옛 import 보유)에서 `cannot find symbol` 다수 — 정상. 이 5개 파일 외의 것이 깨졌으면 Step 4 누락이므로 즉시 확인.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): privacy 모듈 코어(domain+application) 이전

FIDA 기준 매매표 도메인 11타입, PrivacyService/PrivacyTradeValidationService,
PrivacyUseCase/PrivacyTradeValidationUseCase, PrivacyTradePort를 com.kista.privacy로
이전. trading 18개 + 비-trading 7개 소비자 import 경로 정합화. 어댑터 레이어는
Task 3에서 이어서 이전 — 이 시점 컴파일 에러 5개 파일은 정상(레거시 경로 참조).
EOF
)"
```

---

## Task 3: 어댑터(in/out) 물리 이전 + 전체 컴파일 그린화

**Files:**
- Move: `src/main/java/com/kista/adapter/in/web/FidaOrderController.java` → `src/main/java/com/kista/privacy/adapter/in/web/FidaOrderController.java`
- Move: `src/main/java/com/kista/adapter/in/web/dto/FidaOrderResponse.java` → `src/main/java/com/kista/privacy/adapter/in/web/dto/FidaOrderResponse.java`
- Move: `src/main/java/com/kista/adapter/out/persistence/privacy/{PrivacyTradeBaseEntity,PrivacyTradeBaseJpaRepository,PrivacyTradeBaseOrderEntity,PrivacyTradePersistenceAdapter}.java` → `src/main/java/com/kista/privacy/adapter/out/persistence/`
- Modify (import 경로만): `src/main/java/com/kista/adapter/in/web/dto/AdminPrivacyBaseResponse.java` (레거시 잔류, `PrivacyTradeBaseView` 경로만)
- Move tests: `src/test/java/com/kista/adapter/in/web/FidaOrderControllerTest.java` → `src/test/java/com/kista/privacy/adapter/in/web/`; `src/test/java/com/kista/adapter/out/persistence/privacy/{PrivacyTradeBaseEntityTest,PrivacyTradePersistenceAdapterTest}.java` → `src/test/java/com/kista/privacy/adapter/out/persistence/`
- `src/test/java/com/kista/adapter/in/web/AdminPrivacyTradeControllerTest.java` — 레거시 잔류, import 경로만 갱신

**Interfaces:**
- Consumes: Task 2가 만든 `com.kista.privacy.{domain.model,application.usecase,application.port.output}.*`
- Produces: `com.kista.privacy.adapter.*` 전체 — Task 5 NamedInterface 대상 아님(internal 유지)

- [ ] **Step 1: 어댑터 파일 물리 이동**

```bash
mkdir -p src/main/java/com/kista/privacy/adapter/in/web/dto
mkdir -p src/main/java/com/kista/privacy/adapter/out/persistence

git mv src/main/java/com/kista/adapter/in/web/FidaOrderController.java        src/main/java/com/kista/privacy/adapter/in/web/FidaOrderController.java
git mv src/main/java/com/kista/adapter/in/web/dto/FidaOrderResponse.java      src/main/java/com/kista/privacy/adapter/in/web/dto/FidaOrderResponse.java

git mv src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseEntity.java        src/main/java/com/kista/privacy/adapter/out/persistence/PrivacyTradeBaseEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseJpaRepository.java src/main/java/com/kista/privacy/adapter/out/persistence/PrivacyTradeBaseJpaRepository.java
git mv src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseOrderEntity.java   src/main/java/com/kista/privacy/adapter/out/persistence/PrivacyTradeBaseOrderEntity.java
git mv src/main/java/com/kista/adapter/out/persistence/privacy/PrivacyTradePersistenceAdapter.java src/main/java/com/kista/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapter.java
rmdir src/main/java/com/kista/adapter/out/persistence/privacy

mkdir -p src/test/java/com/kista/privacy/adapter/in/web
mkdir -p src/test/java/com/kista/privacy/adapter/out/persistence
git mv src/test/java/com/kista/adapter/in/web/FidaOrderControllerTest.java src/test/java/com/kista/privacy/adapter/in/web/FidaOrderControllerTest.java
git mv src/test/java/com/kista/adapter/out/persistence/privacy/PrivacyTradeBaseEntityTest.java        src/test/java/com/kista/privacy/adapter/out/persistence/PrivacyTradeBaseEntityTest.java
git mv src/test/java/com/kista/adapter/out/persistence/privacy/PrivacyTradePersistenceAdapterTest.java src/test/java/com/kista/privacy/adapter/out/persistence/PrivacyTradePersistenceAdapterTest.java
rmdir src/test/java/com/kista/adapter/out/persistence/privacy
```

- [ ] **Step 2: package 선언 + import 치환 (이동 파일)**

```bash
find src/main/java/com/kista/privacy/adapter src/test/java/com/kista/privacy/adapter -name "*.java" -exec sed -i '' \
  -e 's/^package com\.kista\.adapter\.in\.web\.dto;/package com.kista.privacy.adapter.in.web.dto;/' \
  -e 's/^package com\.kista\.adapter\.in\.web;/package com.kista.privacy.adapter.in.web;/' \
  -e 's/^package com\.kista\.adapter\.out\.persistence\.privacy;/package com.kista.privacy.adapter.out.persistence;/' \
  -e 's#com\.kista\.adapter\.in\.web\.dto\.FidaOrderResponse#com.kista.privacy.adapter.in.web.dto.FidaOrderResponse#g' \
  -e 's#com\.kista\.adapter\.out\.persistence\.privacy\.#com.kista.privacy.adapter.out.persistence.#g' \
  {} +
```

주의: 이동한 persistence 파일들은 `import com.kista.adapter.out.persistence.BaseCreatedAtEntity;`를 계속 참조한다(레거시 `com.kista.adapter.out.persistence`의 `BaseCreatedAtEntity`/`BaseAuditEntity`는 `Type.OPEN` 공유 기반 클래스로 잔류 — finance/broker/trading/market 엔티티도 동일하게 최상위 패키지 경계를 넘어 상속 중이므로 이 import는 그대로 둔다).

- [ ] **Step 3: `AdminPrivacyBaseResponse` + `AdminPrivacyTradeControllerTest` import 경로 갱신 (레거시 잔류)**

```bash
sed -i '' 's#com\.kista\.domain\.model\.privacy\.PrivacyTradeBaseView#com.kista.privacy.domain.model.PrivacyTradeBaseView#g' \
  src/main/java/com/kista/adapter/in/web/dto/AdminPrivacyBaseResponse.java
git grep -n "com\.kista\.domain\.model\.privacy" src/test/java/com/kista/adapter/in/web/AdminPrivacyTradeControllerTest.java
```
`AdminPrivacyTradeControllerTest`에 매치가 있으면 동일 sed로 갱신.

- [ ] **Step 4: 전체 컴파일 + privacy 테스트 실행**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
```
Expected: 에러 없음. 실패 시 잔존 옛 경로:
```bash
git grep -n "com\.kista\.domain\.model\.privacy\|com\.kista\.application\.\(service\.privacy\|usecase\.Privacy\|port\.output\.PrivacyTradePort\)\|com\.kista\.adapter\.in\.web\.FidaOrderController\|com\.kista\.adapter\.in\.web\.dto\.FidaOrderResponse\|com\.kista\.adapter\.out\.persistence\.privacy" src/main src/test
```

```bash
./gradlew test --tests 'com.kista.privacy.*' --tests 'com.kista.trading.domain.strategy.PrivacyStrategyTest' --tests 'com.kista.adapter.in.web.AdminPrivacyTradeControllerTest' 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: `ApplicationModules.verify()` 사전 게이트 (모듈 선언 전, 예측 검증)**

privacy는 아직 `@ApplicationModule` 미선언이라 이 시점 `ModulithArchitectureTest`는 privacy를 레거시 OPEN의 일부로 본다 — 순환은 아직 안 잡힌다. 대신 수동으로 privacy의 잔존 CLOSED-모듈 의존을 확인:

```bash
git grep -hn "^import com\.kista\.\(trading\|notify\|broker\|finance\|market\)\." src/main/java/com/kista/privacy | sort -u
```
Expected: `com.kista.notify.application.port.output.NotifyPort`만 남아야 한다(`PrivacyService`). trading/broker/finance/market 참조가 있으면 Task 1이 놓친 것 — 멈추고 보고. `NotifyPort`는 Task 4에서 제거된다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): privacy 모듈 어댑터 이전 + 전체 컴파일 정합화

FidaOrderController/FidaOrderResponse, persistence 4종을 com.kista.privacy로
이전. AdminPrivacyTradeController/AdminPrivacyBaseResponse는 admin 소유라
레거시 잔류(경로만 갱신). 전체 컴파일·privacy 테스트 그린 확인. privacy의
잔존 CLOSED-모듈 의존은 notify(NotifyPort) 하나 — Task 4에서 이벤트로 전환.
EOF
)"
```

---

## Task 4: privacy→notify 이벤트 전환 + 모듈 선언(CLOSED + NamedInterface) — 순환 2건 최종 해소·검증

> **배경:** privacy CLOSED 전환 시 `privacy → notify`(`PrivacyService`가 `NotifyPort.notifyError`/`notifyInfo` 직접 호출) + `notify → trading`(`TradingAlertNotifier`가 trading 이벤트 구독, 기존 확립 설계) + `trading → privacy`(18개 파일이 `PrivacyTradeBase` 등 소비, Task 2에서 정상 단방향으로 정리됨) 세 변이 `privacy → notify → trading → privacy` 전이 순환을 만든다. `notify→trading`·`trading→privacy`는 정상 의존이라 건드리지 않고, 새로 문제되는 `privacy→notify` 하나만 이벤트로 끊는다 — market의 `FearGreedFetchFailedEvent`/`MarketAlertNotifier`와 동일 패턴.
>
> **이 태스크가 모듈 선언까지 포함하는 이유:** 전이 순환은 `@ApplicationModule` 선언 후 `ApplicationModules.verify()`로만 관찰 가능하다. 이벤트 전환(코드 변경)과 모듈 선언을 다른 태스크로 쪼개면 이벤트 전환 태스크가 "고쳤다는 증거 0"으로 커밋되고, 3번째 순환이 있으면 두 태스크치 변경을 bisect해야 한다. 한 태스크로 묶어 `verify()`를 이 태스크의 실질 게이트로 삼는다.

**Files:**
- Create: `src/main/java/com/kista/privacy/application/event/PrivacyAlertRaisedEvent.java`
- Create: `src/main/java/com/kista/privacy/application/event/package-info.java`
- Create: `src/main/java/com/kista/privacy/package-info.java`
- Create: `src/main/java/com/kista/privacy/domain/model/package-info.java`
- Create: `src/main/java/com/kista/privacy/application/port/output/package-info.java`
- Create: `src/main/java/com/kista/privacy/application/usecase/package-info.java`
- Modify: `src/main/java/com/kista/privacy/application/service/PrivacyService.java`
- Create: `src/main/java/com/kista/notify/adapter/out/gateway/PrivacyAlertNotifier.java`
- Modify: `src/test/java/com/kista/privacy/application/service/PrivacyServiceTest.java`
- Create: `src/test/java/com/kista/notify/adapter/out/gateway/PrivacyAlertNotifierTest.java`

**Interfaces:**
- Produces: `com.kista.privacy.application.event.PrivacyAlertRaisedEvent(Severity severity, String message)` + nested `enum Severity{BLOCKING,WARNING}` — "event" NamedInterface. `"domain"`/`"port"`/`"usecase"`/`"event"` 4개 NamedInterface 선언 완료 → trading 18곳·비-trading 7곳이 이 이름들로 소비.
- Consumes: 기존 `com.kista.notify.application.port.output.NotifyPort.{notifyError(Exception),notifyInfo(String)}` (notify 쪽에서만 계속 사용).

- [ ] **Step 1: 이벤트 record + package-info("event") 작성**

```java
// src/main/java/com/kista/privacy/application/event/PrivacyAlertRaisedEvent.java
package com.kista.privacy.application.event;

// FIDA 기준 매매표 검증 경보 — privacy→notify 직접 호출을 끊기 위한 이벤트(market FearGreedFetchFailedEvent와 동일 패턴).
// severity로 관리자 알림 채널 구분: BLOCKING=저장 차단(NotifyPort.notifyError), WARNING=경고 후 저장 진행(notifyInfo).
// Exception 자체는 EPR 직렬화 부적합이라 message(String)만 담는다 — 소비처(notify)가 문자열만 쓴다.
public record PrivacyAlertRaisedEvent(Severity severity, String message) {
    public enum Severity { BLOCKING, WARNING }
}
```

```java
// src/main/java/com/kista/privacy/application/event/package-info.java
// privacy 모듈의 공개 계약 일부 — PrivacyAlertRaisedEvent. notify 모듈이 @TransactionalEventListener로
// 구독한다(CLOSED↔CLOSED 모듈 간 이벤트 교차, trading/market.application.event와 동일 패턴). "event" 이름으로 공개.
@org.springframework.modulith.NamedInterface("event")
package com.kista.privacy.application.event;
```

- [ ] **Step 2: `PrivacyService`를 이벤트 발행으로 전환**

`private final NotifyPort notifyPort;` + `import com.kista.notify.application.port.output.NotifyPort;` 제거, `ApplicationEventPublisher`로 교체. 기존 동작 유지: BLOCKING이면 이벤트 발행 후 `IllegalArgumentException` throw(메시지 동일), WARNING이면 이벤트 발행 후 저장 진행.

```java
package com.kista.privacy.application.service;

import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.application.usecase.PrivacyUseCase;
import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyTradeSaveResult;
import com.kista.privacy.domain.model.PrivacyTradeValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class PrivacyService implements PrivacyUseCase {

    private final PrivacyTradePort privacyTradePort;
    private final PrivacyTradeValidationService validationService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command) {
        // FIDA 수신값은 KST 발행일 원본 — 변환 없이 그대로 검증·저장 (release_date는 거래일이 아님)
        PrivacyTradeValidationReport report = validationService.inspect(command);
        if (report.hasBlockingIssues()) {
            String message = "[FIDA] " + report.summary();
            log.error("[FIDA] 기준 매매표 저장 차단: {}", report.summary());
            eventPublisher.publishEvent(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.BLOCKING, message));
            throw new IllegalArgumentException(message);
        }
        if (report.hasIssues()) {
            eventPublisher.publishEvent(new PrivacyAlertRaisedEvent(
                    PrivacyAlertRaisedEvent.Severity.WARNING, "[PRIVACY] 기준 매매표 경고: " + report.summary()));
        }
        return privacyTradePort.saveBaseWithOrders(command);
    }
}
```

- [ ] **Step 3: notify에 리스너 추가 (`MarketAlertNotifier`와 동일 패턴)**

```java
// src/main/java/com/kista/notify/adapter/out/gateway/PrivacyAlertNotifier.java
package com.kista.notify.adapter.out.gateway;

import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// privacy가 발행하는 FIDA 기준 매매표 검증 경보를 구독해 기존 NotifyPort로 중계한다(MarketAlertNotifier와 동일 패턴).
// PrivacyService.executeFidaOrder()가 @Transactional 없이 이벤트 발행 직후 예외를 던지거나 저장을 진행하므로
// fallbackExecution=true로 트랜잭션이 없으면 발행 시점에 동기 실행되게 한다.
@Component
@RequiredArgsConstructor
public class PrivacyAlertNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onPrivacyAlert(PrivacyAlertRaisedEvent event) {
        if (event.severity() == PrivacyAlertRaisedEvent.Severity.BLOCKING) {
            notifyPort.notifyError(new RuntimeException(event.message()));
        } else {
            notifyPort.notifyInfo(event.message());
        }
    }
}
```

- [ ] **Step 4: `PrivacyServiceTest` 수정 — mock 대상을 `ApplicationEventPublisher`로 교체**

`@Mock NotifyPort notifyPort` → `@Mock ApplicationEventPublisher eventPublisher`. `@InjectMocks PrivacyService sut`는 유지(생성자 주입 자동). 기존 assertion 변환:
- `verify(notifyPort).notifyError(any())` (BLOCKING 케이스) → `verify(eventPublisher).publishEvent(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.BLOCKING, "[FIDA] " + <expected summary>))` — 정확한 문자열은 기존 테스트가 만든 `PrivacyTradeValidationReport` mock 반환값의 `summary()`에 맞춘다.
- `verify(notifyPort).notifyInfo(...)` (WARNING 케이스) → `verify(eventPublisher).publishEvent(argThat(e -> e instanceof PrivacyAlertRaisedEvent p && p.severity() == PrivacyAlertRaisedEvent.Severity.WARNING))`.
- `verify(notifyPort, never())...` (정상 저장 케이스) → `verify(eventPublisher, never()).publishEvent(any(Object.class))` 또는 `any(PrivacyAlertRaisedEvent.class)`.
- BLOCKING 케이스가 `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class)`로 예외를 검증하던 부분은 그대로 유지(메시지도 동일).
- `FidaOrderCommand` 생성 인자는 Task 1에서 이미 `FidaPlannedOrder`로 바뀐 상태.

- [ ] **Step 5: `PrivacyAlertNotifierTest` 신규 작성**

`MarketAlertNotifierTest`가 있으면 그 구조 참고. 없으면:

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PrivacyAlertNotifierTest {

    @Mock NotifyPort notifyPort;
    @InjectMocks PrivacyAlertNotifier sut;

    @Test
    void blocking_severity_routes_to_notifyError() {
        sut.onPrivacyAlert(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.BLOCKING, "차단 사유"));
        verify(notifyPort).notifyError(any(RuntimeException.class));
    }

    @Test
    void warning_severity_routes_to_notifyInfo() {
        sut.onPrivacyAlert(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.WARNING, "경고 메시지"));
        verify(notifyPort).notifyInfo(eq("경고 메시지"));
    }
}
```

- [ ] **Step 6: package-info 4개 작성 (모듈 선언)**

```java
// src/main/java/com/kista/privacy/package-info.java
// privacy 애그리게이트(FIDA 기준 매매표 — PRIVACY 전략의 전역 SSOT 매매 계획) 모듈 — domain.model·application.{port.output,usecase,event}만
// 공개 계약, application.service·adapter는 internal.
@org.springframework.modulith.ApplicationModule
package com.kista.privacy;
```

```java
// src/main/java/com/kista/privacy/domain/model/package-info.java
// privacy 모듈의 공개 계약 일부 — 불변 값 객체(record/enum) + 예외. "domain" 이름으로 공개(포트는 별도 "port"/"usecase" NamedInterface).
@org.springframework.modulith.NamedInterface("domain")
package com.kista.privacy.domain.model;
```

```java
// src/main/java/com/kista/privacy/application/port/output/package-info.java
// privacy 모듈의 공개 계약 일부 — *Port 접미사 출력 포트 인터페이스(PrivacyTradePort). "port" 이름으로 공개.
@org.springframework.modulith.NamedInterface("port")
package com.kista.privacy.application.port.output;
```

```java
// src/main/java/com/kista/privacy/application/usecase/package-info.java
// privacy 모듈의 공개 계약 일부 — 인바운드 UseCase 인터페이스(PrivacyUseCase는 FidaOrderController가, PrivacyTradeValidationUseCase는
// trading의 TradingOpenScheduler가 소비). "usecase" 이름으로 공개.
@org.springframework.modulith.NamedInterface("usecase")
package com.kista.privacy.application.usecase;
```

- [ ] **Step 7: 컴파일 + ArchUnit 검증 (이 태스크의 실질 게이트)**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|FAILED"
./gradlew test --tests 'com.kista.privacy.*' --tests 'com.kista.notify.*' --tests 'com.kista.architecture.*' 2>&1 | tail -80
```
Expected: 컴파일 에러 없음. `ModulithArchitectureTest`(`ApplicationModules.verify()`) + `HexagonalArchitectureTest` + privacy/notify 테스트 전부 통과. `ModulithArchitectureTest` 실패 시 순환 위반 메시지의 모듈-모듈 참조 확인 — 사전 실측 순환 2건(privacy↔trading, privacy→notify→trading→privacy)은 Task 1·이 태스크 Step 2에서 해소됐으므로, 3번째 순환이 나오면 이 세션 grep이 놓친 케이스다. **즉시 멈추고 보고**(추측 수정 금지). NamedInterface 미노출 위반이면 Step 6 package-info 경로/이름 확인.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(modulith): privacy 모듈 선언 + privacy→notify 이벤트 전환

PrivacyService의 NotifyPort 직접 호출을 PrivacyAlertRaisedEvent 발행으로 전환,
notify가 PrivacyAlertNotifier로 구독 — privacy→notify→trading→privacy 전이 순환
해소(broker/market 디커플링과 동일 패턴). @ApplicationModule CLOSED +
domain·port·usecase·event 4개 NamedInterface 선언. ApplicationModules.verify()·
HexagonalArchitectureTest 그린 확인. privacy는 finance→notify→broker→trading→
market에 이은 6번째 이전 모듈.
EOF
)"
```

---

## Task 5: 문서 갱신 + 전체 테스트 스위트 최종 검증

**Files:**
- Modify: `docs/agents/architecture.md`
- Modify: `docs/agents/constraints.md`
- Modify: `docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`
- Modify: `README.md` (해당 시)
- Modify: `docs/agents/testing.md` (privacy 테스트 분리 원칙 언급 있으면)

**Interfaces:** 없음(문서 전용).

- [ ] **Step 1: `architecture.md`에 privacy 모듈 절 추가**

기존 `com.kista.market/` 절과 동일 형식으로 `com.kista.privacy/` 트리(domain/model, application/{service,usecase,port/output,event}, adapter/{in/web(+dto), out/persistence})와 NamedInterface 구성("domain"+"port"+"usecase"+"event")을 기술. 레거시 `domain/`·`application/`·`adapter/` 절 본문에서 `domain/model/privacy`, `application/service/privacy`, `adapter/out/persistence/privacy`, `adapter/in/web`의 `FidaOrderController` 언급을 제거하고, `AdminPrivacyTradeController`/`AdminPrivacyBaseResponse`는 admin 소유로 레거시 잔류함을 명시. `PRIVACY 전략 패턴` 절의 `privacy_trade_bases`·`privacy_trade_base_orders` 언급에서 `PrivacyTradeBaseEntity`/`PrivacyTradeBaseOrderEntity` 경로를 `com.kista.privacy.adapter.out.persistence`로 갱신. `Spring Modulith 점진 도입` 절의 "finance✅ → notify✅ → broker✅ → trading✅ → market✅(5번째)" 뒤에 "→ privacy✅(6번째)" 추가. trading 절의 "17개 파일이 `PrivacyTradeBase` 참조"류 서술이 있으면 "privacy 모듈의 domain NamedInterface 소비"로 갱신. `PrivacyStrategy`가 `PrivacyOrderType`→`Order.OrderType` 매핑을 한다는 점, `com.kista.privacy.domain.model`이 자체 `PrivacyOrderType`/`PrivacyOrderDirection`을 소유(broker Direction/OrderType 복제와 동일)한다는 점을 trading 또는 privacy 절에 한 줄.

- [ ] **Step 2: `constraints.md` 갱신**

- "Spring Modulith 이전 중 신규 파일 배치" 절에 privacy 항목 추가(market 항목 형식): "FIDA 기준 매매표 애그리게이트는 `com.kista.privacy`로 이전됐다 — 신규 관련 코드도 `com.kista.privacy` 안에 추가. `domain/model`이 "domain", `application/port/output`이 "port", `application/usecase`가 "usecase", `application/event`가 "event"로 공개 — `application/service`·`adapter/*`는 internal. `AdminPrivacyTradeController`/`AdminPrivacyBaseResponse`는 admin 소유로 레거시 잔류."
- "모듈 경계 포트 시그니처" 절 근거 목록에 privacy 사례 추가: privacy가 `PrivacyOrderType`/`PrivacyOrderDirection`/`FidaPlannedOrder`를 자체 소유해 `trading.domain.model.Order` 참조를 끊었고, `PrivacyStrategy`가 `valueOf(name())`로 매핑. privacy→notify 직접 호출은 `PrivacyAlertRaisedEvent`로 전환.
- "PRIVACY 전략 패턴 (기준 매매표)" 절: `PrivacyTradeBaseEntity`/`PrivacyTradeBaseOrderEntity` 위치를 `com.kista.privacy.adapter.out.persistence`로, `PrivacyTradePersistenceAdapter` 정렬 처리 언급도 경로 갱신. `privacy_trade_base_orders`의 direction/orderType이 privacy 자체 enum(`PrivacyOrderDirection`/`PrivacyOrderType`, 상수명은 `Order.*`와 동일)임을 명시.
- "Java Enum ↔ DB 컬럼 매핑" 절과 상충 없음 확인(VARCHAR + `@Enumerated(STRING)` 그대로).

- [ ] **Step 3: spec 문서에 완료 표시**

`docs/superpowers/specs/2026-08-31-legacy-module-catalog-design.md`:
- "착수 순서 (실측 기반, v2)" 2번 privacy 항목에 "✅ 완료(2026-08-31, `2026-08-31-modulith-privacy-migration` 실행 계획 5개 태스크)" 각주.
- "결합도 실측 — 이미 CLOSED 4모듈과의 순환" 표의 privacy 행 정정: forward가 "4개 파일"이 아니라 6개 파일(Order nested enum 사용), backward는 18개 trading 파일(실제 코드 변경 1개=`PrivacyStrategy`), 그리고 pairwise가 놓친 `privacy→notify→trading→privacy` 전이 순환이 실재했음을 각주로(market과 동일 맹점 — 다음 모듈(user) 착수 시 실제 임시 `@ApplicationModule` + `verify()` 사전 실행 권장).

- [ ] **Step 4: `README.md` drift 확인**

```bash
grep -n "Privacy\|Fida\|FIDA\|privacy" README.md
```
옛 패키지 경로(`com.kista.adapter.out.persistence.privacy` 등) 언급 시 `com.kista.privacy.*`로 갱신. 아키텍처 다이어그램에 모듈 목록이 있으면 privacy 추가. 매치 없으면 스킵.

- [ ] **Step 5: 전체 테스트 스위트 최종 실행**

```bash
./gradlew test 2>&1 | grep -E "FAILED|BUILD"
```
Expected: `BUILD SUCCESSFUL`, 실패 0. 순수 이동 + 순환 해소라 총 테스트 개수는 Task 4 신규 `PrivacyAlertNotifierTest`(2케이스)만큼만 증가.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
docs(modulith): privacy 모듈 이전 반영 — architecture.md/constraints.md/스펙 갱신
EOF
)"
```

---

## Self-Review 메모 (계획 작성자 기준)

- **스펙 커버리지**: 스펙 "착수 순서" 2단계(privacy) 전체를 커버 — privacy 파일 20개(domain 11 + application 4 + adapter 5) + 테스트 8개 이동, trading 18곳 + 비-trading 7곳 import 정합화, 순환 2건 해소(Task 1: privacy↔trading, Task 4: privacy→notify→trading→privacy), Task 4에서 모듈 선언 4개 NamedInterface, Task 5 문서.
- **스펙과의 차이(의도된 정정, 3건)**: (1) forward 파일 4→6개, (2) 전이 순환 1건 추가 발견 → Task 4가 이벤트 전환+모듈 선언을 함께 수행(순환은 `verify()`로만 관찰되므로 분리 무의미), (3) `AdminPrivacyTradeController`/`AdminPrivacyBaseResponse` admin 잔류 결정(스펙엔 언급 없음). 전부 Global Constraints에 명시.
- **플레이스홀더 스캔**: 코드 스텝은 전부 실제 코드/명령. 테스트 파일 수정(Task 1 Step 10, Task 4 Step 4)은 "기존 구조 유지, 생성 인자 타입만 교체" — market 계획과 동일 수준의 위임(원본 테스트 코드가 계획에 없으므로 실행자가 파일 읽고 맞춤), 단 교체 규칙과 대표 예시는 명시.
- **타입 일관성**: Task 1이 만든 `PrivacyOrderType`(LOC/MOC/LIMIT)·`PrivacyOrderDirection`(BUY/SELL)·`FidaPlannedOrder(direction,orderType,quantity,price)` FQN이 Task 2에서 `com.kista.privacy.domain.model`로 이동, Task 3(persistence) 일관 참조. Task 4의 `PrivacyAlertRaisedEvent(Severity,String)` + `Severity{BLOCKING,WARNING}`가 같은 태스크 "event" NamedInterface 대상. `toTradingType(PrivacyOrderType)` 헬퍼는 `PrivacyStrategy` 내부에만 존재.
- **`MockitoExtension` strict 주의(Task 4 Step 4)**: `@Mock NotifyPort` → `@Mock ApplicationEventPublisher` 교체 시 `@InjectMocks PrivacyService` 생성자 파라미터 순서가 바뀐다. 기존 `lenient()` `@BeforeEach`(validation stub)는 그대로 두고, unused-stub 에러가 나면 lenient 블록이 아니라 mock 집합을 손본다.
- **스코프 경계**: PRIVACY *전략 실행* 로직(`PrivacyStrategy`/`PrivacyCycleOrderStrategy`/`CycleOrderComputer` 등 trading.domain.strategy)은 trading 소유로 유지 — privacy 모듈은 FIDA 기준 매매표(계획 데이터)만. `PrivacyDates`(발행일↔거래일 업무 규칙 헬퍼)는 privacy 소유로 이동(`BacktestService`가 소비 → "domain"으로 공개).
