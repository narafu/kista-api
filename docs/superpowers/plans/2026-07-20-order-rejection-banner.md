# 예산 배정 거절 배너 Implementation Plan (v2, DEFERRED)

> **[DEFERRED 2026-07-21]** 이 계획은 작성 완료됐지만 실행되지 않았다. 사용자가 "최대한 심플하게"를 재차 요청해, 백엔드 변경이 전혀 없는 v3(프론트엔드 `preview.orders` vs `todayOrders` 방향별 diff)로 이번 라운드는 대체됐다. v3가 감지 못하는 "당일 매수·매도 전량 거절" 케이스나 "당일이 지나면 이력이 안 남는" 한계가 실제로 문제될 때 이 계획을 다시 꺼내 실행한다. 대응 스펙: `docs/superpowers/specs/2026-07-20-rejected-order-persistence-design.md`(마찬가지로 DEFERRED). v3 스펙: `docs/superpowers/specs/2026-07-21-order-rejection-banner-frontend-diff-design.md`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예수금(BUY) 또는 판매가능수량(SELL) 부족으로 스케쥴러가 거절한 주문 후보를, `orders` 테이블을 전혀 건드리지 않고 사이클+거래일+방향 단위의 별도 사실 테이블(`order_rejections`)로 기록해 "다음 주문" 카드에 배너로 노출한다.

**Architecture:** `TradingOrderBudgetAllocator.Allocation`의 `rejectedBuy`/`rejectedSell`을 `TradingService.saveAllocatedOrders`에서 방향별 자연키 upsert로 `order_rejections`에 기록하고, 같은 방향이 이후 재시도로 승인되면 해당 행을 삭제한다. `TradingPreviewService`가 이를 조회해 `NextOrdersPreview.rejections`로 얹고, kista-ui `StrategyDetail.tsx`가 기존 주문 목록과 완전히 독립된 배너로 렌더링한다. 기존 `BuyCompetitionNotice`(재시뮬레이션 배지)는 두 사용처를 모두 제거하고 컴포넌트 자체를 삭제한다.

**Tech Stack:** Java 21 + Spring Boot 3 (Hexagonal Architecture), PostgreSQL + Flyway, JPA(package-private JpaRepository) + JdbcTemplate(native upsert), Next.js/React + Vitest, Mockito 단위 테스트 + `DataJpaTestBase`(로컬 Postgres) 통합 테스트.

## Global Constraints

- 스펙 문서: `docs/superpowers/specs/2026-07-20-rejected-order-persistence-design.md` (v2) — 모든 태스크는 이 문서의 결정을 따른다.
- **`OrderPort.findPlannedOrPlacedByCycleAndDate`와 관련 예산 계산 쿼리는 절대 변경하지 않는다** — 슬롯 재시도 로직의 SSOT.
- 커밋 메시지: 한글, Conventional Commit 접두사(`feat:`/`test:`/`fix:` 등) + 명령형 제목. Author `narafu <narafu@kakao.com>`.
- DB 컬럼: `VARCHAR` + `@Enumerated(EnumType.STRING)`, 네이티브 PostgreSQL ENUM 금지.
- Java: 4-space 들여쓰기, record 우선, 생성자 주입(`@RequiredArgsConstructor`).
- 주석: 신규 코드에 `// 역할 한 줄` 인라인 주석 (Javadoc/블록 주석 금지).
- 테스트: `@Execution(ExecutionMode.SAME_THREAD)` 필수(`@WebMvcTest`/`DataJpaTestBase` 상속 클래스), 서비스 필드 추가 시 해당 테스트에 `@Mock` 추가 필수.

---

## Task 1: Flyway 마이그레이션 — `order_rejections` 테이블

**Files:**
- Create: `src/main/resources/db/migration/V31__create_order_rejections.sql` (실행 직전 `ls src/main/resources/db/migration | sort -V | tail -3`로 최신 버전 재확인 — V30이 최신이면 V31 사용)

**Interfaces:**
- Produces: `order_rejections` 테이블 — `(id, strategy_cycle_id, trade_date, direction, order_count, created_at, updated_at)`, UNIQUE `(strategy_cycle_id, trade_date, direction)`

- [ ] **Step 1: 최신 마이그레이션 버전 확인**

Run: `ls src/main/resources/db/migration | sort -V | tail -3`
Expected: `V30__create_market_index_prices.sql`가 최신이면 이번 파일은 `V31__create_order_rejections.sql`. 만약 이미 V31 이상이 존재하면 그다음 번호 사용.

- [ ] **Step 2: 마이그레이션 파일 작성**

```sql
-- order_rejections: 예산 배정 거절 사실 기록 (orders 테이블과 완전히 분리)
-- 사이클+거래일+방향 단위 자연키 upsert — 개장·마감 스케쥴러가 같은 방향을 반복 거절해도 중복 행이 생기지 않는다
CREATE TABLE order_rejections (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID          NOT NULL,
    trade_date        DATE          NOT NULL,
    direction         VARCHAR(5)    NOT NULL,
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

- [ ] **Step 3: 로컬 DB에 적용 확인**

Run: `docker compose up -d postgres` (기동 안 되어 있으면) 후 `./gradlew bootRun --args='--spring.profiles.active=local'`로 앱을 잠깐 띄워 Flyway 자동 적용 확인, 또는 `./gradlew test --tests 'com.kista.architecture.*'`로 컴파일·마이그레이션 파싱 오류만 우선 확인.
Expected: 앱 기동 로그에 `Migrating schema "public" to version "31 - create order rejections"` 출력, 에러 없음.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V31__create_order_rejections.sql
git commit -m "feat(db): order_rejections 테이블 추가 — 예산 배정 거절 사실 기록"
```

---

## Task 2: 도메인 모델 `OrderRejection` + 포트 `OrderRejectionPort`

**Files:**
- Create: `src/main/java/com/kista/domain/model/order/OrderRejection.java`
- Create: `src/main/java/com/kista/domain/port/out/OrderRejectionPort.java`

**Interfaces:**
- Produces: `OrderRejection` record, `OrderRejectionPort` 인터페이스(`upsert`/`deleteIfExists`/`findByCycleAndDate`) — Task 3(영속성), Task 4(TradingService), Task 5(TradingPreviewService)가 소비

- [ ] **Step 1: `OrderRejection` 도메인 record 작성**

```java
package com.kista.domain.model.order;

import java.time.LocalDate;
import java.util.UUID;

// 예산 배정 거절 사실 — orders 테이블과 분리된 별도 개념, 사이클+거래일+방향 단위 최대 1건
public record OrderRejection(
        UUID id,
        UUID strategyCycleId,
        LocalDate tradeDate,
        Order.OrderDirection direction,  // BUY=예수금 부족, SELL=판매가능수량 부족
        int orderCount                   // 이번 배정에서 거절된 주문 후보 건수
) {}
```

- [ ] **Step 2: `OrderRejectionPort` 인터페이스 작성**

```java
package com.kista.domain.port.out;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderRejection;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface OrderRejectionPort {
    // (strategyCycleId, tradeDate, direction) 자연키 upsert — 개장·마감이 같은 방향을 반복 거절해도 최신 건수로 덮어쓸 뿐 중복 행이 안 생긴다
    void upsert(UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction, int orderCount);

    // 재시도로 해당 방향이 승인되면 호출 — 더 이상 거절 상태가 아니므로 배너에서 사라져야 한다
    void deleteIfExists(UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction);

    List<OrderRejection> findByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate);
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (아직 구현체가 없어도 인터페이스·record만으로는 컴파일 성공)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/kista/domain/model/order/OrderRejection.java src/main/java/com/kista/domain/port/out/OrderRejectionPort.java
git commit -m "feat(order): OrderRejection 도메인 모델 + OrderRejectionPort 추가"
```

---

## Task 3: 영속성 — `OrderRejectionEntity` + `OrderRejectionJpaRepository` + `OrderRejectionPersistenceAdapter`

**Files:**
- Create: `src/main/java/com/kista/adapter/out/persistence/trade/OrderRejectionEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/trade/OrderRejectionJpaRepository.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/trade/OrderRejectionPersistenceAdapter.java`
- Test: `src/test/java/com/kista/adapter/out/persistence/trade/OrderRejectionPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: `OrderRejectionPort`(Task 2), `Order.OrderDirection`, `BaseAuditEntity`(`adapter.out.persistence`)
- Produces: `OrderRejectionPersistenceAdapter implements OrderRejectionPort` — Task 4/5가 Spring Bean으로 주입받아 사용

- [ ] **Step 1: Entity 작성**

```java
package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.order.Order;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "order_rejections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class OrderRejectionEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyCycleId; // FK → strategy_cycle.id

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate; // KST 거래일

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Column(name = "order_count", nullable = false)
    private int orderCount; // 이번 배정에서 거절된 주문 후보 건수
}
```

- [ ] **Step 2: JpaRepository 작성**

```java
package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface OrderRejectionJpaRepository extends JpaRepository<OrderRejectionEntity, UUID> {
    // 사이클+거래일 기준 당일 거절 배너 조회 (최대 2건: BUY, SELL)
    List<OrderRejectionEntity> findByStrategyCycleIdAndTradeDate(UUID strategyCycleId, LocalDate tradeDate);

    // 재시도로 승인되면 해당 방향 배너 해소
    void deleteByStrategyCycleIdAndTradeDateAndDirection(
            UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction);
}
```

- [ ] **Step 3: PersistenceAdapter 작성 (upsert는 JdbcTemplate native query)**

```java
package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderRejection;
import com.kista.domain.port.out.OrderRejectionPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // OrderRejectionJpaRepository가 package-private
public class OrderRejectionPersistenceAdapter implements OrderRejectionPort {

    private final OrderRejectionJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void upsert(UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction, int orderCount) {
        // 자연키(strategy_cycle_id, trade_date, direction) 충돌 시 최신 건수로 덮어쓴다 — 개장·마감 반복 거절 대응
        jdbcTemplate.update("""
                INSERT INTO order_rejections (strategy_cycle_id, trade_date, direction, order_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (strategy_cycle_id, trade_date, direction) DO UPDATE
                   SET order_count = EXCLUDED.order_count,
                       updated_at = now()
                """,
                strategyCycleId, Date.valueOf(tradeDate), direction.name(), orderCount);
    }

    @Override
    public void deleteIfExists(UUID strategyCycleId, LocalDate tradeDate, Order.OrderDirection direction) {
        // 존재하지 않아도 no-op — 재시도로 승인된 방향의 과거 거절 배너 해소
        repository.deleteByStrategyCycleIdAndTradeDateAndDirection(strategyCycleId, tradeDate, direction);
    }

    @Override
    public List<OrderRejection> findByCycleAndDate(UUID strategyCycleId, LocalDate tradeDate) {
        return repository.findByStrategyCycleIdAndTradeDate(strategyCycleId, tradeDate).stream()
                .map(this::toDomain)
                .toList();
    }

    private OrderRejection toDomain(OrderRejectionEntity e) {
        return new OrderRejection(e.getId(), e.getStrategyCycleId(), e.getTradeDate(), e.getDirection(), e.getOrderCount());
    }
}
```

- [ ] **Step 4: 실패하는 통합 테스트 작성** (`DataJpaTestBase` — 로컬 Postgres 필요)

```java
package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.strategy.StrategyCyclePersistenceAdapter;
import com.kista.adapter.out.persistence.strategy.StrategyPersistenceAdapter;
import com.kista.adapter.out.persistence.strategy.StrategyVersionPersistenceAdapter;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderRejection;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class,
        StrategyCyclePersistenceAdapter.class,
        OrderRejectionPersistenceAdapter.class
})
@Execution(ExecutionMode.SAME_THREAD)
class OrderRejectionPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StrategyPersistenceAdapter strategyAdapter;
    @Autowired StrategyVersionPersistenceAdapter strategyVersionAdapter;
    @Autowired StrategyCyclePersistenceAdapter strategyCycleAdapter;
    @Autowired OrderRejectionPersistenceAdapter adapter;

    private UUID cycleId;
    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");

        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyCycle cycle = strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(), new BigDecimal("1000.00"), null, TODAY, null, null, null));
        cycleId = cycle.id();
    }

    @Test
    void upsert_insertsNewRejection() {
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.BUY, 3);

        List<OrderRejection> result = adapter.findByCycleAndDate(cycleId, TODAY);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(result.getFirst().orderCount()).isEqualTo(3);
    }

    @Test
    void upsert_naturalKeyConflict_overwritesOrderCount() {
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.BUY, 3);

        adapter.upsert(cycleId, TODAY, Order.OrderDirection.BUY, 5); // 마감 재거절 — 건수 갱신

        List<OrderRejection> result = adapter.findByCycleAndDate(cycleId, TODAY);
        assertThat(result).hasSize(1); // 중복 행 없음
        assertThat(result.getFirst().orderCount()).isEqualTo(5);
    }

    @Test
    void upsert_differentDirectionsSameCycleDate_keepsBothRows() {
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.BUY, 3);
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.SELL, 2);

        List<OrderRejection> result = adapter.findByCycleAndDate(cycleId, TODAY);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(OrderRejection::direction)
                .containsExactlyInAnyOrder(Order.OrderDirection.BUY, Order.OrderDirection.SELL);
    }

    @Test
    void deleteIfExists_removesOnlyMatchingDirection() {
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.BUY, 3);
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.SELL, 2);

        adapter.deleteIfExists(cycleId, TODAY, Order.OrderDirection.BUY);

        List<OrderRejection> result = adapter.findByCycleAndDate(cycleId, TODAY);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().direction()).isEqualTo(Order.OrderDirection.SELL);
    }

    @Test
    void deleteIfExists_noMatchingRow_isNoOp() {
        adapter.deleteIfExists(cycleId, TODAY, Order.OrderDirection.BUY); // 존재하지 않음

        List<OrderRejection> result = adapter.findByCycleAndDate(cycleId, TODAY);
        assertThat(result).isEmpty(); // 예외 없이 빈 결과
    }

    @Test
    void findByCycleAndDate_differentDate_notReturned() {
        adapter.upsert(cycleId, TODAY, Order.OrderDirection.BUY, 3);

        List<OrderRejection> result = adapter.findByCycleAndDate(cycleId, TODAY.minusDays(1));

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 5: 로컬 Postgres 기동 후 테스트 실행 확인**

Run: `docker compose up -d postgres && ./gradlew test --tests 'com.kista.adapter.out.persistence.trade.OrderRejectionPersistenceAdapterTest' --rerun-tasks`
Expected: 6개 테스트 모두 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/adapter/out/persistence/trade/OrderRejectionEntity.java \
        src/main/java/com/kista/adapter/out/persistence/trade/OrderRejectionJpaRepository.java \
        src/main/java/com/kista/adapter/out/persistence/trade/OrderRejectionPersistenceAdapter.java \
        src/test/java/com/kista/adapter/out/persistence/trade/OrderRejectionPersistenceAdapterTest.java
git commit -m "feat(persistence): OrderRejectionPersistenceAdapter 구현 — 자연키 upsert/삭제/조회"
```

---

## Task 4: `TradingService.saveAllocatedOrders` — 거절 배너 기록·해소

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java:36-51`(필드), `:372-422`(`saveAllocatedOrders`)
- Test: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Consumes: `OrderRejectionPort`(Task 2/3), `TradingOrderBudgetAllocator.Allocation`(기존 — `approved()`/`rejectedBuy()`/`rejectedSell()`)
- Produces: 변경 없음(외부 API 불변) — 내부 부수효과로 `order_rejections` upsert/delete

- [ ] **Step 1: 실패하는 테스트 작성 — BUY 거절 시 upsert**

`src/test/java/com/kista/application/service/trading/TradingServiceTest.java`의 `@Mock` 목록(45-71행)에 추가:

```java
    @Mock OrderRejectionPort orderRejectionPort;
```

`setUp()`의 `service = new TradingService(...)` 호출(190-195행) 마지막 인자로 추가:

```java
        service = new TradingService(
                marketCalendarPort, notifyPort, userNotificationPort,
                orderPort, privacyTradePort, strategyCyclePort,
                balanceLoader, orderComputer, orderPlanner,
                priceFetcher, orderExecutor, reporter,
                marketEventNotifier, budgetAllocator, priceCapper, cycleOrderStrategies,
                orderRejectionPort);
```

`placeOpenOrders_insufficientBalance_notifiesUserAndSkipsSave` 테스트(422-450행) 바로 뒤에 신규 테스트 추가:

```java
    @Test
    void placeOpenOrders_insufficientBalance_recordsRejectionBanner() throws InterruptedException {
        BigDecimal prevClose = new BigDecimal("19.00");
        Order bigBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_BIG_BUY");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(new BigDecimal("500.00"), prevClose)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(LOW_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(bigBuy));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("10.00")));

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 거절된 BUY 후보 1건 → (cycle, today, BUY, 1) upsert
        verify(orderRejectionPort).upsert(eq(STRATEGY_CYCLE.id()), any(LocalDate.class), eq(Order.OrderDirection.BUY), eq(1));
        verify(orderRejectionPort, never()).deleteIfExists(any(), any(), any());
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest.placeOpenOrders_insufficientBalance_recordsRejectionBanner'`
Expected: FAIL — `orderRejectionPort` 필드가 `TradingService` 생성자에 없어 컴파일 오류(`constructor TradingService cannot be applied to given types`)

- [ ] **Step 3: `TradingService` 필드 추가**

`TradingService.java:36-51` 필드 목록 마지막(`cycleOrderStrategies` 다음)에 추가:

```java
    private final OrderRejectionPort orderRejectionPort;      // 예산 배정 거절 배너 upsert/삭제
```

(`import com.kista.domain.port.out.*;` 와일드카드 임포트로 이미 `OrderRejectionPort`가 잡히므로 추가 import 불필요)

- [ ] **Step 4: `saveAllocatedOrders` 수정**

`TradingService.java:396-419`(기존 승인 저장 루프 + 거절 알림 루프)를 아래로 교체:

```java
        for (TradingOrderBudgetAllocator.Allocation allocation : allocations) {
            for (TradingOrderBudgetAllocator.Candidate approved : allocation.approved()) {
                Optional<BatchContext> saved = runSafely("계획 주문 저장", approved.ctx(), () -> {
                    orderPlanner.savePlannedOrders(
                            approved.orders(), approved.ctx().account(), approved.ctx().currentCycle().id());
                    return approved.ctx();
                });
                if (saved.isPresent()) {
                    savedContexts.add(saved.get());
                    // mergeApproved는 방향을 병합할 수 있다 — 한 사이클에서 BUY·SELL이 같은 run에 함께
                    // 승인되면 orders()에 두 방향이 섞여 들어오므로, 실제 존재하는 방향 집합을 순회해 각각 해소한다
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
                    orderRejectionPort.upsert(candidate.ctx().currentCycle().id(), tradeDate,
                            Order.OrderDirection.BUY, candidate.orders().size());
                    return null;
                });
            }
            for (TradingOrderBudgetAllocator.Candidate candidate : allocation.rejectedSell()) {
                runSafely("거절 배너 기록", candidate.ctx(), () -> {
                    orderRejectionPort.upsert(candidate.ctx().currentCycle().id(), tradeDate,
                            Order.OrderDirection.SELL, candidate.orders().size());
                    return null;
                });
            }

            Set<BatchContext> rejectedContexts = Stream.concat(
                            allocation.rejectedBuy().stream(), allocation.rejectedSell().stream())
                    .map(TradingOrderBudgetAllocator.Candidate::ctx)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (BatchContext ctx : rejectedContexts) {
                runSafely("예수금 부족 알림", ctx, () -> {
                        userNotificationPort.notifyInsufficientBalance(
                                ctx.user(), ctx.account(), ctx.strategy().type(), ctx.strategy().ticker());
                        return null;
                    });
            }
        }
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'`
Expected: 전체 PASS (기존 테스트는 `orderRejectionPort` mock이 void 메서드라 별도 stub 없이도 no-op으로 통과)

- [ ] **Step 6: BUY+SELL 동시 거절(같은 run) 테스트 추가** — `executeBatch_bothSidesRejectedWithoutExistingOrders_skipsPlacementAndReporting`(741-769행) 바로 뒤에 추가

```java
    @Test
    void executeBatch_bothSidesRejectedSameRun_upsertsBothDirectionBanners() throws InterruptedException {
        Order rejectedBuy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 100, new BigDecimal("500.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_REJECTED_BUY");
        Order rejectedSell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, 101, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_REJECTED_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(rejectedBuy, rejectedSell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        when(liveBalancePort.getLiveBalance(eq(ACCOUNT), eq(Ticker.SOXL)))
                .thenReturn(new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("10.00")));
        when(sellableQuantityPort.getSellableQuantity(eq(Ticker.SOXL), eq(ACCOUNT)))
                .thenReturn(new SellableQuantity("SOXL", 100));

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        // 같은 run에서 BUY·SELL Candidate가 각각 독립적으로 upsert되어 서로 덮어쓰지 않는다
        verify(orderRejectionPort).upsert(eq(STRATEGY_CYCLE.id()), any(LocalDate.class), eq(Order.OrderDirection.BUY), eq(1));
        verify(orderRejectionPort).upsert(eq(STRATEGY_CYCLE.id()), any(LocalDate.class), eq(Order.OrderDirection.SELL), eq(1));
    }

    @Test
    void placeOpenOrders_approvedBuyAndSellMergedInOneCandidate_resolvesBothRejectionBanners() throws InterruptedException {
        // mergeApproved가 BUY+SELL을 하나의 Candidate로 병합하는 경우를 재현 —
        // getFirst().direction()만 보면 한쪽 배너만 해소되는 회귀를 잡는다
        Order buy = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_MERGED_BUY");
        Order sell = new Order(null, null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, new BigDecimal("25.00"),
                Order.OrderStatus.PLANNED, null, null, null).withLeg("TEST_MERGED_SELL");

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, new BigDecimal("19.00"))));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class)))
                .thenReturn(List.of(buy, sell));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of());
        when(sellableQuantityPort.getSellableQuantity(eq(Ticker.SOXL), eq(ACCOUNT)))
                .thenReturn(new SellableQuantity("SOXL", 1)); // 둘 다 승인될 정도로 충분

        service.placeOpenOrders(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), PAST_DST);

        verify(orderPort).saveAll(argThat(saved -> saved.size() == 2));
        // 병합된 approved Candidate에 BUY+SELL이 함께 있어도 두 방향 모두 해소돼야 한다
        verify(orderRejectionPort).deleteIfExists(eq(STRATEGY_CYCLE.id()), any(LocalDate.class), eq(Order.OrderDirection.BUY));
        verify(orderRejectionPort).deleteIfExists(eq(STRATEGY_CYCLE.id()), any(LocalDate.class), eq(Order.OrderDirection.SELL));
    }
```

- [ ] **Step 7: 전체 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'`
Expected: 전체 PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/kista/application/service/trading/TradingService.java \
        src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "feat(trading): 예산 배정 거절 시 방향별 거절 배너 upsert/해소"
```

---

## Task 5: `NextOrdersPreview` + `TradingPreviewService` — `rejections` 필드 연결

**Files:**
- Modify: `src/main/java/com/kista/domain/model/order/NextOrdersPreview.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingPreviewService.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/trading/TradingExecutionFacadeTest.java:130`
- Modify: `src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java:20-21,36-37`(생성자 인자 수 컴파일 수정만 — 실제 DTO 매핑 테스트는 Task 6)

**Interfaces:**
- Consumes: `OrderRejectionPort`(Task 2/3)
- Produces: `NextOrdersPreview.rejections()` — Task 6(DTO), kista-ui가 최종 소비

- [ ] **Step 1: `NextOrdersPreview`에 필드 추가**

`NextOrdersPreview.java` 전체를 아래로 교체:

```java
package com.kista.domain.model.order;

import com.kista.domain.model.strategy.InfinitePosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,                       // PRIVACY/skip 시 null
        List<Order> orders,                              // NO_CYCLE_HISTORY/NO_PRIVACY_BASE skip 시 빈 리스트
        SkipReason skipReason,                           // 정상이면 null
        List<Order> todayPlannedOrders,                  // 오늘 이미 등록된 PLANNED 주문 (없으면 빈 리스트)
        BigDecimal otherStrategiesPlannedBuyUsd,          // 계좌 내 타 전략 당일 PLANNED BUY 합계
        BuyCompetitionPreview competition,                // 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (BUY 없으면 null)
        List<OrderRejection> rejections                  // 당일 예산 배정 거절 이력 (없으면 빈 리스트)
) {
    public enum SkipReason {
        NO_CYCLE_HISTORY,   // 사이클 이력 없음 (신규)
        NO_PRIVACY_BASE     // PRIVACY 기준매매표 미수신
    }
}
```

- [ ] **Step 2: 컴파일 확인 (기존 생성 호출부가 깨지는지 확인)**

Run: `./gradlew compileTestJava`
Expected: FAIL — `TradingPreviewService.java`, `TradingExecutionFacadeTest.java`, `NextOrdersResponseTest.java`에서 `NextOrdersPreview` 생성자 인자 수 불일치(`no suitable constructor found`)

- [ ] **Step 3: `TradingPreviewService` 수정**

`TradingPreviewService.java` 전체를 아래로 교체:

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderRejection;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.OrderRejectionPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final StrategyOrderPlanBuilder planBuilder;
    private final TradingBuyCompetitionSimulator competitionSimulator;
    private final OrderRejectionPort orderRejectionPort;    // 당일 예산 배정 거절 배너 조회

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        // 현재 StrategyCycle — initialUsdDeposit 조회(PRIVACY) 및 경쟁 시뮬레이션에 사용
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategy.id()));

        // 스케쥴러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = DstInfo.nextTradeDate();

        // 오늘 이미 등록된 주문 조회 — PLANNED(취소 가능) + PLACED(AT_OPEN 선접수됨) 모두 포함
        List<Order> todayPlannedOrders =
                orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);

        // 당일 예산 배정 거절 이력 — orders와 완전히 분리된 별도 개념, mode/카운트/취소 로직에 영향 없음
        List<OrderRejection> rejections = orderRejectionPort.findByCycleAndDate(currentCycle.id(), today);

        // 계좌 내 타 전략 당일 PLANNED BUY 합계 (이 전략 분 제외 — 예수금 부족 계산에 사용)
        BigDecimal totalAccountPlannedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
        BigDecimal thisStrategyPlannedBuy = todayPlannedOrders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);

        StrategyOrderPlanBuilder.PlanResult result =
                planBuilder.build(strategy, account, currentCycle, today, "preview:" + strategyId);
        if (result.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayPlannedOrders,
                    otherStrategiesPlannedBuyUsd, null, rejections);
        }
        CycleOrderStrategy.OrderPlan plan = result.plan();

        // 오늘자 계획에 BUY가 있을 때만 계좌 내 예산 경쟁 시뮬레이션 수행
        List<Order> buyOrders = plan.orders().stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .toList();
        BuyCompetitionPreview competition = buyOrders.isEmpty()
                ? null
                : competitionSimulator.simulate(strategy, account, currentCycle, buyOrders, today, otherStrategiesPlannedBuyUsd);

        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayPlannedOrders,
                otherStrategiesPlannedBuyUsd, competition, rejections);
    }
}
```

- [ ] **Step 4: `TradingPreviewServiceTest` 수정 — 필드 추가 및 신규 테스트**

`@Mock` 목록에 추가:

```java
    @Mock OrderRejectionPort orderRejectionPort;
```

`setUp()` 수정:

```java
    @BeforeEach
    void setUp() {
        service = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, competitionSimulator, orderRejectionPort);
        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT.id(), ACCOUNT.userId())).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(orderRejectionPort.findByCycleAndDate(any(), any())).thenReturn(List.of());
    }
```

기존 두 테스트 메서드 끝(현재 `preview_returnsOrdersWithoutCompetition_whenPlanHasNoBuyOrders`, `preview_callsCompetitionSimulator_whenPlanHasBuyOrders`) 뒤에 신규 테스트 추가:

```java
    @Test
    void preview_returnsRejectionsFromPort() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        OrderRejection rejection = new OrderRejection(UUID.randomUUID(), STRATEGY_CYCLE.id(), LocalDate.now(),
                Order.OrderDirection.BUY, 2);
        when(orderRejectionPort.findByCycleAndDate(eq(STRATEGY_CYCLE.id()), any())).thenReturn(List.of(rejection));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.rejections()).containsExactly(rejection);
    }

    @Test
    void preview_noRejections_returnsEmptyList() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.rejections()).isEmpty();
    }
```

파일 상단 import에 추가:

```java
import com.kista.domain.model.order.OrderRejection;
import com.kista.domain.port.out.OrderRejectionPort;
```

- [ ] **Step 5: `TradingExecutionFacadeTest.java:130` 컴파일 수정**

```java
        NextOrdersPreview preview = new NextOrdersPreview(LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, List.of());
```

- [ ] **Step 6: `NextOrdersResponseTest.java:20-21,36-37` 컴파일 수정**

```java
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, List.of());
```

```java
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, competition, List.of());
```

(Task 6에서 `NextOrdersResponse`가 `rejections` 필드를 소비하도록 확장하기 전까지는, 이 두 파일은 컴파일만 통과하면 된다.)

- [ ] **Step 7: 전체 테스트 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingPreviewServiceTest' --tests 'com.kista.application.service.trading.TradingExecutionFacadeTest'`
Expected: 전체 PASS (`NextOrdersResponseTest`는 Task 6에서 재검증)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/kista/domain/model/order/NextOrdersPreview.java \
        src/main/java/com/kista/application/service/trading/TradingPreviewService.java \
        src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java \
        src/test/java/com/kista/application/service/trading/TradingExecutionFacadeTest.java \
        src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java
git commit -m "feat(trading): NextOrdersPreview.rejections 필드 연결"
```

---

## Task 6: `NextOrdersResponse` DTO — `rejections` 응답 노출

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java`
- Modify: `src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java`

**Interfaces:**
- Consumes: `NextOrdersPreview.rejections()`(Task 5)
- Produces: `NextOrdersResponse.rejections()` — kista-ui가 소비하는 최종 API 응답 필드

- [ ] **Step 1: 실패하는 테스트 작성**

`NextOrdersResponseTest.java`에 신규 테스트 추가 (기존 두 테스트 뒤):

```java
    @Test
    void from_mapsRejectionsList() {
        OrderRejection rejection = new OrderRejection(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(),
                Order.OrderDirection.BUY, 3);
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, List.of(rejection));

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.rejections()).hasSize(1);
        assertThat(response.rejections().getFirst().direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(response.rejections().getFirst().orderCount()).isEqualTo(3);
    }

    @Test
    void from_emptyRejections_mapsToEmptyList() {
        NextOrdersPreview preview = new NextOrdersPreview(
                LocalDate.now(), null, List.of(), null, List.of(), BigDecimal.ZERO, null, List.of());

        NextOrdersResponse response = NextOrdersResponse.from(preview);

        assertThat(response.rejections()).isEmpty();
    }
```

파일 상단 import에 추가(없다면):

```java
import com.kista.domain.model.order.OrderRejection;
import java.util.UUID;
```

(이미 `Order`/`LocalDate`/`List`/`BigDecimal` import는 기존 파일에 존재)

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.dto.NextOrdersResponseTest.from_mapsRejectionsList'`
Expected: FAIL — `NextOrdersResponse`에 `rejections()` 메서드 없음(컴파일 오류)

- [ ] **Step 3: `NextOrdersResponse`에 `RejectionResponse` 추가**

`NextOrdersResponse.java` 상단 import에 추가:

```java
import com.kista.domain.model.order.OrderRejection;
```

레코드 헤더의 `BuyCompetitionSummary competition` 다음에 필드 추가:

```java
public record NextOrdersResponse(
        @Schema(description = "다음 매매 예정일 (KST 기준)")
        LocalDate tradeDate,
        @Schema(description = "INFINITE 전략 포지션 스냅샷 (PRIVACY 전략 또는 skip 시 null)")
        PositionSnapshot position,
        @Schema(description = "생성 예정 주문 목록")
        List<OrderItem> orders,
        @Schema(description = "주문 생성 skip 사유 (정상이면 null)", example = "NO_PRIVACY_BASE")
        NextOrdersPreview.SkipReason skipReason,
        @Schema(description = "오늘 이미 등록된 PLANNED·PLACED 주문 목록")
        List<TodayOrderItem> todayOrders,
        @Schema(description = "계좌 내 타 전략의 당일 PLANNED BUY 합계 (USD)")
        BigDecimal otherStrategiesPlannedBuyUsd,
        @Schema(description = "계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (대상 전략에 BUY 주문이 없으면 null, 근사치)")
        BuyCompetitionSummary competition,
        @Schema(description = "당일 예산 배정 거절 이력 (방향별 최대 1건, 재시도로 해소되면 사라짐)")
        List<RejectionResponse> rejections
) {
```

`OrderItem` record 다음, `TodayOrderItem` record 앞(또는 뒤 아무 위치)에 신규 record 추가:

```java
    // 당일 예산 배정 거절 배너 — orders와 분리된 별도 개념
    public record RejectionResponse(
            @Schema(description = "매수/매도 방향", example = "BUY")
            Order.OrderDirection direction,
            @Schema(description = "거절된 주문 후보 건수")
            int orderCount
    ) {
        public static RejectionResponse from(OrderRejection r) {
            return new RejectionResponse(r.direction(), r.orderCount());
        }
    }
```

`from()` 팩토리 메서드를 아래로 교체:

```java
    public static NextOrdersResponse from(NextOrdersPreview result) {
        return new NextOrdersResponse(
                result.tradeDate(),
                result.position() == null ? null : PositionSnapshot.from(result.position()),
                result.orders().stream().map(OrderItem::from).toList(),
                result.skipReason(),
                result.todayPlannedOrders().stream().map(TodayOrderItem::from).toList(),
                result.otherStrategiesPlannedBuyUsd(),
                result.competition() == null ? null : BuyCompetitionSummary.from(result.competition()),
                result.rejections().stream().map(RejectionResponse::from).toList()
        );
    }
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.dto.NextOrdersResponseTest'`
Expected: 전체 PASS

- [ ] **Step 5: 전체 백엔드 테스트 + ArchUnit 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (ArchUnit `HexagonalArchitectureTest` 포함 — `OrderRejection`이 `domain/model/order/`에 위치해 레이어 위반 없음)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java \
        src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java
git commit -m "feat(api): NextOrdersResponse에 rejections 필드 노출"
```

---

## Task 7: kista-ui 타입 + API 정규화

**Files:**
- Modify: `/Users/phs/workspace/kista/kista-ui/entities/order/model/types.ts`
- Modify: `/Users/phs/workspace/kista/kista-ui/entities/order/api/index.ts`

**Interfaces:**
- Consumes: `GET /api/trading-cycles/{id}/preview`의 `rejections` 필드(Task 6)
- Produces: `OrderRejection` TS 타입, `NextOrderPreview.rejections` — Task 8이 소비

- [ ] **Step 1: `entities/order/model/types.ts`에 타입 추가**

`NextOrderPreview` 인터페이스 바로 앞에 추가:

```ts
export interface OrderRejection {
  direction: 'BUY' | 'SELL'
  orderCount: number
}
```

`NextOrderPreview` 인터페이스 마지막 필드에 추가:

```ts
export interface NextOrderPreview {
  tradeDate: string
  position: NextOrderPositionSnapshot | null
  orders: NextOrderItem[]
  skipReason: SkipReason | null
  todayOrders: PlacedOrder[]               // 오늘 이미 등록된 PLANNED + PLACED 주문 (없으면 빈 배열)
  otherStrategiesPlannedBuyUsd: string     // 계좌 내 타 전략 당일 PLANNED BUY 합계
  competition: BuyCompetitionSummary | null // 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (BUY 없으면 null)
  rejections: OrderRejection[]              // 당일 예산 배정 거절 이력 (없으면 빈 배열)
}
```

- [ ] **Step 2: `entities/order/api/index.ts`에 정규화 함수 추가**

파일 상단 import 수정:

```ts
import { clientFetch } from '@shared/lib/api-client'
import type { BuyCompetitionSummary, CompetingStrategy, NextOrderPreview, OrderRejection, SkipReason, StrategyOrder } from '../model/types'
```

`normalizeCompetition` 함수 다음에 추가:

```ts
function normalizeRejections(raw: unknown): OrderRejection[] {
  return ((raw as unknown[]) ?? []).map((r) => {
    const item = r as Record<string, unknown>
    return {
      direction: String(item.direction) as 'BUY' | 'SELL',
      orderCount: Number(item.orderCount),
    }
  })
}
```

`normalizePreview` 함수 내부, `const competition = normalizeCompetition(r.competition)` 다음 줄에 추가하고 반환문 수정:

```ts
  const otherStrategiesPlannedBuyUsd = String(r.otherStrategiesPlannedBuyUsd ?? '0')
  const competition = normalizeCompetition(r.competition)
  const rejections = normalizeRejections(r.rejections)
  return { tradeDate: String(r.tradeDate), position, orders, skipReason, todayOrders, otherStrategiesPlannedBuyUsd, competition, rejections }
```

- [ ] **Step 3: 타입 체크**

Run: `cd /Users/phs/workspace/kista/kista-ui && npx tsc --noEmit`
Expected: 에러 없음(단, Task 8 이전이라 `StrategyDetail.tsx`/`StrategyCard.tsx`가 `NextOrderPreview`를 소비하는 곳에서 `rejections` 누락 관련 오류는 없음 — optional 아님이지만 `Partial<>` 목 데이터를 쓰는 테스트 파일들은 Task 9에서 처리)

- [ ] **Step 4: Commit**

```bash
cd /Users/phs/workspace/kista/kista-ui
git add entities/order/model/types.ts entities/order/api/index.ts
git commit -m "feat(order): OrderRejection 타입 + preview 응답 정규화 추가"
```

---

## Task 8: kista-ui `StrategyDetail.tsx` — 거절 배너 렌더링 + `BuyCompetitionNotice` 제거

**Files:**
- Modify: `/Users/phs/workspace/kista/kista-ui/widgets/strategy-detail/StrategyDetail.tsx`
- Delete: `/Users/phs/workspace/kista/kista-ui/widgets/strategy-detail/BuyCompetitionNotice.tsx`
- Delete: `/Users/phs/workspace/kista/kista-ui/widgets/strategy-detail/BuyCompetitionNotice.test.tsx`

**Interfaces:**
- Consumes: `NextOrderPreview.rejections`(Task 7)
- Produces: "다음 주문" 카드 상단 거절 배너 (mode 무관 렌더링)

- [ ] **Step 1: import 수정 — `BuyCompetitionNotice` 제거**

`StrategyDetail.tsx:34` 삭제:

```ts
import { BuyCompetitionNotice } from './BuyCompetitionNotice'
```

- [ ] **Step 2: `rejections` 파생 값 추가**

`StrategyDetail.tsx:71-76`(`hasBuyOrders`~`deficitUsd` 선언부) 바로 뒤에 추가:

```ts
  const rejections = preview?.rejections ?? []
  const totalRejectedCount = rejections.reduce((sum, r) => sum + r.orderCount, 0)
```

- [ ] **Step 3: 헤더 인라인 `BuyCompetitionNotice` 제거 + 거절 배너 추가**

`StrategyDetail.tsx:226-232`를 아래로 교체:

```jsx
        <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-[var(--brand-fg-soft)]">
          <span
            data-testid="strategy-status-accent"
            className="size-2 rounded-full shrink-0"
            style={{ background: strategyStatusAccent(strategy.status) }}
          />
          전략 정보
        </p>
```

(이 블록은 헤더 스트립이라 그대로 두고, 실제로 지울 대상은 "다음 주문" `CardHeader` 내부다. 아래가 정확한 교체 대상이다.)

`StrategyDetail.tsx:226-232`(`CardTitle`~`</div>` 직전, `BuyCompetitionNotice` 렌더 블록)를 아래로 교체:

```jsx
            <div>
              <CardTitle className="text-base lg:text-lg">다음 주문</CardTitle>
              <p className="text-sm lg:text-base text-muted-foreground mt-0.5">매 거래일 개장 시 자동실행</p>
              {rejections.length > 0 && (
                <div className="flex flex-col gap-0.5 mt-1.5">
                  {rejections.map((r) => (
                    <p key={r.direction} className="text-sm lg:text-base text-warn">
                      {r.direction === 'BUY' ? '예수금 부족으로 매수' : '판매가능수량 부족으로 매도'} {r.orderCount}건 미접수
                    </p>
                  ))}
                </div>
              )}
            </div>
```

- [ ] **Step 4: "N건 접수됨" 카운트에 거절 건수 합산 표기**

`StrategyDetail.tsx:272`(현재 `{placedOrders.length > 0 ? \`${placedOrders.length}건 접수됨\` : '접수됨'}`)를 아래로 교체:

```jsx
                <p className="text-sm lg:text-base uppercase tracking-widest font-semibold text-warn">
                  {placedOrders.length > 0 ? `${placedOrders.length}건 접수됨` : '접수됨'}
                  {totalRejectedCount > 0 ? ` · ${totalRejectedCount}건 거절` : ''}
                </p>
```

- [ ] **Step 5: `CardContent` 내부 row-variant `BuyCompetitionNotice` 제거**

`StrategyDetail.tsx:306-312`(현재):

```jsx
          ) : (
            <div>
              {hasDeficit && competition && (
                <BuyCompetitionNotice competition={competition} deficitUsd={deficitUsd} variant="row" />
              )}
              <OrderRows orders={orders} />
            </div>
          )}
```

아래로 교체:

```jsx
          ) : (
            <OrderRows orders={orders} />
          )}
```

- [ ] **Step 6: `hasDeficit`/`competition`/`deficitUsd` 계산은 유지 확인**

`StrategyDetail.tsx:70-76`(파생 값 선언)와 `:235-237`(소형 배지), `:245-247`(토스트 가드)은 **변경하지 않는다** — `StrategyCard.tsx`와 무관하게 이 파일 자체에서도 여전히 쓰인다.

- [ ] **Step 7: 죽은 코드 삭제**

```bash
cd /Users/phs/workspace/kista/kista-ui
rm widgets/strategy-detail/BuyCompetitionNotice.tsx widgets/strategy-detail/BuyCompetitionNotice.test.tsx
```

- [ ] **Step 8: 남은 참조 확인**

Run: `cd /Users/phs/workspace/kista/kista-ui && grep -rl "BuyCompetitionNotice" --include="*.tsx" . | grep -v node_modules`
Expected: 결과 없음 (아무 파일도 참조하지 않음)

- [ ] **Step 9: Commit**

```bash
git add widgets/strategy-detail/StrategyDetail.tsx
git rm widgets/strategy-detail/BuyCompetitionNotice.tsx widgets/strategy-detail/BuyCompetitionNotice.test.tsx
git commit -m "feat(strategy-detail): 거절 배너 렌더링 추가, BuyCompetitionNotice 제거"
```

(이 시점에서 `StrategyDetail.test.tsx`는 아직 깨져 있다 — Task 9에서 수정)

---

## Task 9: kista-ui `StrategyDetail.test.tsx` — 목 데이터·테스트 재정비

**Files:**
- Modify: `/Users/phs/workspace/kista/kista-ui/widgets/strategy-detail/StrategyDetail.test.tsx`

**Interfaces:**
- Consumes: Task 8에서 변경된 `StrategyDetail.tsx`

- [ ] **Step 1: 기본 mock 데이터에 `rejections: []` 추가**

`StrategyDetail.test.tsx:75-80`을 아래로 교체:

```ts
const mockPreviewQuery = vi.fn(() => ({
  data: { todayOrders: [], position: null, orders: [], skipReason: 'NO_CYCLE_HISTORY', otherStrategiesPlannedBuyUsd: '0', competition: null, rejections: [] } as Partial<NextOrderPreview>,
  isLoading: false,
  isError: false,
  error: null as unknown,
}))
```

- [ ] **Step 2: 기존 "buy competition notice" describe 블록을 "rejection banner" 블록으로 교체**

`StrategyDetail.test.tsx:198-254`(`describe('StrategyDetail buy competition notice', ...)` 전체)를 아래로 교체:

```ts
describe('StrategyDetail rejection banner', () => {
  it('shows the rejection banner text when a direction was rejected today', () => {
    mockPreviewQuery.mockReturnValueOnce({
      data: {
        todayOrders: [
          { id: 'o1', ticker: 'TSLA', direction: 'SELL', orderType: 'LIMIT', quantity: 1, price: '25.00', status: 'PLACED' },
        ],
        position: null,
        orders: [],
        skipReason: null,
        otherStrategiesPlannedBuyUsd: '0',
        competition: null,
        rejections: [{ direction: 'BUY', orderCount: 3 }],
      },
      isLoading: false,
      isError: false,
      error: null,
    })

    render(<StrategyDetail accountId="account-1" strategy={baseStrategy} />)

    expect(screen.getByText(/예수금 부족으로 매수 3건 미접수/)).toBeInTheDocument()
  })

  it('shows both directions when BUY and SELL are both rejected', () => {
    mockPreviewQuery.mockReturnValueOnce({
      data: {
        todayOrders: [],
        position: null,
        orders: [],
        skipReason: 'NO_CYCLE_HISTORY',
        otherStrategiesPlannedBuyUsd: '0',
        competition: null,
        rejections: [
          { direction: 'BUY', orderCount: 2 },
          { direction: 'SELL', orderCount: 1 },
        ],
      },
      isLoading: false,
      isError: false,
      error: null,
    })

    render(<StrategyDetail accountId="account-1" strategy={baseStrategy} />)

    expect(screen.getByText(/예수금 부족으로 매수 2건 미접수/)).toBeInTheDocument()
    expect(screen.getByText(/판매가능수량 부족으로 매도 1건 미접수/)).toBeInTheDocument()
  })

  it('does not show the rejection banner when there are no rejections', () => {
    render(<StrategyDetail accountId="account-1" strategy={baseStrategy} />)

    expect(screen.queryByText(/미접수/)).not.toBeInTheDocument()
  })

  it('never renders the removed BuyCompetitionNotice component', () => {
    mockPreviewQuery.mockReturnValueOnce({
      data: {
        todayOrders: [],
        position: null,
        orders: [{ ticker: 'TSLA', orderType: 'LOC', direction: 'BUY', quantity: 5, price: '20.00' }],
        skipReason: null,
        otherStrategiesPlannedBuyUsd: '0',
        competition: {
          sufficientBudget: false,
          availableDeposit: '1000',
          requiredForThisStrategy: '200',
          consumedByHigherPriority: '900',
          blockedByHigherPriority: [
            { strategyId: 'vr-1', type: 'VR', ticker: 'TQQQ', requiredBuyUsd: '900', priority: 0 },
          ],
          uncertainStrategyIds: [],
        },
        rejections: [],
      },
      isLoading: false,
      isError: false,
      error: null,
    })

    render(<StrategyDetail accountId="account-1" strategy={baseStrategy} />)

    // 자세히 보기 토글은 BuyCompetitionNotice 전용 UI였다 — 더 이상 존재하지 않아야 한다
    expect(screen.queryByText('자세히 ▾')).not.toBeInTheDocument()
    expect(screen.queryByText(/부족 \(우선순위 전략/)).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 3: 테스트 실행**

Run: `cd /Users/phs/workspace/kista/kista-ui && npx vitest run widgets/strategy-detail/StrategyDetail.test.tsx`
Expected: 전체 PASS

- [ ] **Step 4: 관련 전체 테스트 스위트 확인**

Run: `cd /Users/phs/workspace/kista/kista-ui && npx vitest run widgets/strategy-detail/`
Expected: 전체 PASS (`OrderRows.test.tsx`는 이번 변경과 무관하므로 그대로 통과해야 함)

- [ ] **Step 5: 타입 체크**

Run: `cd /Users/phs/workspace/kista/kista-ui && npx tsc --noEmit`
Expected: 에러 없음

- [ ] **Step 6: Commit**

```bash
cd /Users/phs/workspace/kista/kista-ui
git add widgets/strategy-detail/StrategyDetail.test.tsx
git commit -m "test(strategy-detail): 거절 배너 테스트로 buy competition notice 테스트 교체"
```

---

## 최종 검증

- [ ] **kista-api 전체**: `./gradlew clean test` — BUILD SUCCESSFUL (ArchUnit 포함)
- [ ] **kista-ui 전체**: `cd /Users/phs/workspace/kista/kista-ui && npx vitest run && npx tsc --noEmit` — 전체 PASS, 타입 에러 없음
- [ ] **수동 확인** (로컬 서버 기동 후, `docs/agents/commands.md`의 dev-token 발급 절차 참고): PRIVACY 전략 계좌에서 예수금을 인위적으로 부족하게 만들고 개장 스케쥴러(또는 `executeBatch` 수동 트리거)를 실행해 "다음 주문" 카드에 "예수금 부족으로 매수 N건 미접수" 배너가 뜨는지, 이후 예수금을 채우고 마감 스케쥴러가 재시도해 배너가 사라지고 실제 주문이 접수되는지 눈으로 확인
