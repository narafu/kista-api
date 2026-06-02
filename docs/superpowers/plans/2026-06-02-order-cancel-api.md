# 주문 취소 API 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 수동 실행(`POST /api/trading-cycles/{id}/execute`)으로 PLACED된 주문을 KIS에 취소 접수하는 두 엔드포인트 구현 — 사이클 전체 취소(`DELETE /api/trading-cycles/{id}/execute`)와 개별 주문 취소(`DELETE /api/orders/{orderId}`).

**Architecture:** KIS `order-rvsecncl` API(TR: `TTTT1004U`)를 새 `KisOrderPort.cancel()` 메서드로 감싸고, `OrderCancelService`가 소유권 검증 → PLACED 주문 조회 → KIS 취소 → DB 상태 갱신(CANCELLED) 순으로 처리한다. 부분 실패는 best-effort로 처리하며 성공/실패 건수를 응답에 포함한다.

**Tech Stack:** Java 21, Spring Boot 3, Hexagonal Architecture, Mockito(단위 테스트), KIS REST API

---

## 파일 목록

| 작업 | 파일 |
|---|---|
| Modify | `src/main/java/com/kista/domain/model/order/Order.java` |
| Create | `src/main/java/com/kista/domain/port/in/CancelOrderUseCase.java` |
| Modify | `src/main/java/com/kista/domain/port/out/KisOrderPort.java` |
| Modify | `src/main/java/com/kista/domain/port/out/OrderPort.java` |
| Create | `src/main/java/com/kista/application/service/OrderCancelService.java` |
| Modify | `src/main/java/com/kista/adapter/out/kis/KisOrderAdapter.java` |
| Modify | `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java` |
| Create | `src/main/java/com/kista/adapter/in/web/dto/CancelOrdersResponse.java` |
| Modify | `src/main/java/com/kista/adapter/in/web/TradingCycleController.java` |
| Create | `src/main/java/com/kista/adapter/in/web/OrderCancelController.java` |
| Modify | `src/test/java/com/kista/adapter/out/kis/KisOrderAdapterTest.java` |
| Create | `src/test/java/com/kista/application/service/OrderCancelServiceTest.java` |
| Modify | `src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java` |
| Create | `src/test/java/com/kista/adapter/in/web/OrderCancelControllerTest.java` |

---

## Task 1: 도메인 확장 — CANCELLED 상태 + 포트 인터페이스

**Files:**
- Modify: `src/main/java/com/kista/domain/model/order/Order.java`
- Create: `src/main/java/com/kista/domain/port/in/CancelOrderUseCase.java`
- Modify: `src/main/java/com/kista/domain/port/out/KisOrderPort.java`
- Modify: `src/main/java/com/kista/domain/port/out/OrderPort.java`

- [ ] **Step 1: Order.OrderStatus에 CANCELLED 추가**

`src/main/java/com/kista/domain/model/order/Order.java` 의 `OrderStatus` enum을:

```java
public enum OrderStatus {
    PLANNED,    // DB 저장, KIS 접수 대기
    PLACED,     // KIS 접수 완료
    FILLED,     // 체결 완료
    FAILED,     // 실패
    CANCELLED   // 사용자 취소 요청으로 KIS 취소 접수 완료
}
```

- [ ] **Step 2: CancelOrderUseCase 인터페이스 생성**

새 파일 `src/main/java/com/kista/domain/port/in/CancelOrderUseCase.java`:

```java
package com.kista.domain.port.in;

import java.util.UUID;

public interface CancelOrderUseCase {

    // 오늘 수동 실행으로 PLACED된 사이클 주문 전체 취소 (best-effort)
    // 예외: SecurityException(403), NoSuchElementException(404)
    CancelResult cancelByCycle(UUID cycleId, UUID requesterId);

    // 특정 주문 1건 취소
    // 예외: SecurityException(403), NoSuchElementException(404), IllegalStateException(PLACED 아닌 경우→409)
    void cancelOrder(UUID orderId, UUID requesterId);

    record CancelResult(int cancelledCount, int failedCount) {}
}
```

- [ ] **Step 3: KisOrderPort에 cancel 추가**

`src/main/java/com/kista/domain/port/out/KisOrderPort.java`:

```java
package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;

public interface KisOrderPort {
    Order place(Order order, Account account);
    // KIS 취소 주문 접수 (TTTT1004U). 실패 시 RuntimeException 전파.
    void cancel(Order order, Account account);
}
```

- [ ] **Step 4: OrderPort에 markCancelled, findById 추가**

`src/main/java/com/kista/domain/port/out/OrderPort.java`에 아래 두 메서드 추가:

```java
// 취소 완료 → CANCELLED 상태로 변경
void markCancelled(UUID orderId);

// 개별 주문 단건 조회 (취소 전 상태 확인용)
Optional<Order> findById(UUID orderId);
```

파일 상단 import에 `Optional` 추가 (`java.util.Optional`).

- [ ] **Step 5: 컴파일 확인**

```bash
bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` (KisOrderAdapter, OrderPersistenceAdapter가 아직 미구현이므로 컴파일 오류 발생 — Task 2~3에서 해소됨. 인터페이스 파일만 확인.)

> 실제로 KisOrderAdapter와 OrderPersistenceAdapter가 인터페이스를 구현하므로 이 시점에 컴파일 오류가 발생한다. 오류 내용이 `KisOrderAdapter does not override cancel` 및 `OrderPersistenceAdapter does not override markCancelled/findById`임을 확인하고 다음 Task로 진행.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/domain/model/order/Order.java \
        src/main/java/com/kista/domain/port/in/CancelOrderUseCase.java \
        src/main/java/com/kista/domain/port/out/KisOrderPort.java \
        src/main/java/com/kista/domain/port/out/OrderPort.java
git commit -m "feat: Order.CANCELLED 상태 추가 및 취소 포트 인터페이스 정의

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2: KIS 취소 어댑터 구현

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/kis/KisOrderAdapter.java`
- Modify: `src/test/java/com/kista/adapter/out/kis/KisOrderAdapterTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`KisOrderAdapterTest.java`에 아래 두 테스트 추가 (기존 테스트 아래에 추가):

```java
@Test
@DisplayName("cancel: TTTT1004U 사용, RVSE_CNCL_DVSN_CD=02, OVRS_ORD_UNPR=0")
void cancel_sendsCancelRequest() {
    Order order = new Order(
            UUID.randomUUID(), UUID.randomUUID(), TRADE_DATE,
            Ticker.SOXL, Order.OrderType.LOC, Order.OrderDirection.BUY,
            10, new BigDecimal("25.50"), Order.OrderStatus.PLACED, "ORD99999"
    );
    when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    adapter.cancel(order, ACCOUNT);

    verify(kisHttpClient).buildHeaders(eq("TTTT1004U"), eq(ACCOUNT));
    verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
    Map<?, ?> body = bodyCaptor.getValue();
    assertThat(body.get("RVSE_CNCL_DVSN_CD")).isEqualTo("02"); // 취소 코드
    assertThat(body.get("ORGN_ODNO")).isEqualTo("ORD99999");   // 원주문번호
    assertThat(body.get("OVRS_ORD_UNPR")).isEqualTo("0");      // 취소 시 가격=0 고정
    assertThat(body.get("PDNO")).isEqualTo("SOXL");
    assertThat(body.get("OVRS_EXCG_CD")).isEqualTo("AMEX");
}

@Test
@DisplayName("cancel: KIS 응답 상관없이 예외 없으면 정상 반환")
void cancel_noExceptionOnSuccess() {
    Order order = new Order(
            UUID.randomUUID(), UUID.randomUUID(), TRADE_DATE,
            Ticker.SOXL, Order.OrderType.LOC, Order.OrderDirection.SELL,
            5, new BigDecimal("30.00"), Order.OrderStatus.PLACED, "ORD88888"
    );
    when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

    // 예외 없이 완료돼야 함
    assertThatNoException().isThrownBy(() -> adapter.cancel(order, ACCOUNT));
}
```

import에 `assertThatNoException` 추가: `import static org.assertj.core.api.Assertions.assertThatNoException;`

- [ ] **Step 2: 테스트 실패 확인**

```bash
bash gradlew test --tests "com.kista.adapter.out.kis.KisOrderAdapterTest" 2>&1 | tail -20
```

Expected: 컴파일 오류 (`cancel` 메서드 없음) 또는 테스트 실패.

- [ ] **Step 3: KisOrderAdapter에 cancel 구현**

`KisOrderAdapter.java`에 상수와 메서드 추가:

```java
private static final String PATH_CANCEL   = "/uapi/overseas-stock/v1/trading/order-rvsecncl";
private static final String CANCEL_TR_ID  = "TTTT1004U"; // 해외주식 정정취소주문
```

`place()` 메서드 아래에 추가:

```java
@Override
public void cancel(Order order, Account account) {
    HttpHeaders headers = kisHttpClient.buildHeaders(CANCEL_TR_ID, account);

    Map<String, String> body = new LinkedHashMap<>();
    body.put("CANO", account.accountNo());
    body.put("ACNT_PRDT_CD", account.kisAccountType());
    body.put("OVRS_EXCG_CD", order.ticker().getExchangeCode().name());
    body.put("PDNO", order.ticker().name());
    body.put("ORGN_ODNO", order.kisOrderId());       // 원주문번호 (PLACED 시 저장된 KIS 주문 ID)
    body.put("RVSE_CNCL_DVSN_CD", "02");             // 02=취소
    body.put("ORD_QTY", String.valueOf(order.quantity()));
    body.put("OVRS_ORD_UNPR", "0");                  // 취소 시 0 고정
    body.put("MGCO_APTM_ODNO", "");
    body.put("ORD_SVR_DVSN_CD", "0");

    kisHttpClient.post(PATH_CANCEL, headers, body, CancelResponse.class);
}

record CancelResponse(@JsonProperty("output") CancelOutput output) {
    record CancelOutput(
            @JsonProperty("KRX_FWDG_ORD_ORGNO") String krxOrderOrgNo,
            @JsonProperty("ODNO") String odno,
            @JsonProperty("ORD_TMD") String orderTime
    ) {}
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.adapter.out.kis.KisOrderAdapterTest"
```

Expected: `BUILD SUCCESSFUL`, 모든 테스트 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/kis/KisOrderAdapter.java \
        src/test/java/com/kista/adapter/out/kis/KisOrderAdapterTest.java
git commit -m "feat: KisOrderAdapter에 cancel 메서드 구현 (TTTT1004U)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3: 영속성 어댑터 확장 — markCancelled, findById

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`

- [ ] **Step 1: OrderPersistenceAdapter에 두 메서드 구현**

`OrderPersistenceAdapter.java`에 기존 `markPlaced()` 아래에 추가:

```java
@Override
public void markCancelled(UUID orderId) {
    // markPlaced와 동일 패턴 — CANCELLED 상태로 변경
    OrderEntity e = repository.findById(orderId)
            .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    e.setStatus(Order.OrderStatus.CANCELLED);
    repository.save(e);
}

@Override
public Optional<Order> findById(UUID orderId) {
    return repository.findById(orderId).map(this::toDomain);
}
```

파일 상단 import에 `Optional` 추가 (`java.util.Optional`).

- [ ] **Step 2: 컴파일 확인**

```bash
bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` (이 시점에 KisOrderAdapter, OrderPersistenceAdapter 모두 구현 완료됨).

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java
git commit -m "feat: OrderPersistenceAdapter에 markCancelled, findById 구현

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4: OrderCancelService 구현 (핵심 비즈니스 로직)

**Files:**
- Create: `src/main/java/com/kista/application/service/OrderCancelService.java`
- Create: `src/test/java/com/kista/application/service/OrderCancelServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

새 파일 `src/test/java/com/kista/application/service/OrderCancelServiceTest.java`:

```java
package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.port.in.CancelOrderUseCase;
import com.kista.domain.port.out.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCancelService 취소 비즈니스 로직 검증")
class OrderCancelServiceTest {

    @Mock TradingCyclePort cyclePort;
    @Mock AccountPort accountPort;
    @Mock OrderPort orderPort;
    @Mock KisOrderPort kisOrderPort;

    OrderCancelService service;

    private static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CYCLE_ID   = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID ORDER_ID   = UUID.fromString("00000000-0000-0000-0000-000000000030");

    private static final Account ACCOUNT = new Account(
            ACCOUNT_ID, USER_ID, "테스트계좌", "74420614",
            "appKey", "appSecret", "01", Account.Broker.KIS
    );

    private static final TradingCycle CYCLE = new TradingCycle(
            CYCLE_ID, ACCOUNT_ID, TradingCycle.Type.INFINITE,
            TradingCycle.Status.ACTIVE, TradingCycle.Ticker.SOXL,
            new BigDecimal("10000"), null, null
    );

    private static final Order PLACED_ORDER = new Order(
            ORDER_ID, ACCOUNT_ID, LocalDate.now(),
            TradingCycle.Ticker.SOXL, Order.OrderType.LOC,
            Order.OrderDirection.BUY, 10, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "KIS_ORD_001"
    );

    @BeforeEach
    void setUp() {
        service = new OrderCancelService(cyclePort, accountPort, orderPort, kisOrderPort);
    }

    // ───── cancelByCycle ─────

    @Test
    @DisplayName("사이클 취소: PLACED 주문 전체 KIS 취소 후 CANCELLED 상태로 변경")
    void cancelByCycle_allSuccess() {
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);
        when(orderPort.findPlacedByAccountAndDate(eq(ACCOUNT_ID), any(LocalDate.class)))
                .thenReturn(List.of(PLACED_ORDER));
        doNothing().when(kisOrderPort).cancel(any(), any());

        CancelOrderUseCase.CancelResult result = service.cancelByCycle(CYCLE_ID, USER_ID);

        assertThat(result.cancelledCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(0);
        verify(kisOrderPort).cancel(PLACED_ORDER, ACCOUNT);
        verify(orderPort).markCancelled(ORDER_ID);
    }

    @Test
    @DisplayName("사이클 취소: PLACED 주문 없으면 0,0 반환")
    void cancelByCycle_noPlacedOrders_returnsZero() {
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);
        when(orderPort.findPlacedByAccountAndDate(eq(ACCOUNT_ID), any(LocalDate.class)))
                .thenReturn(List.of());

        CancelOrderUseCase.CancelResult result = service.cancelByCycle(CYCLE_ID, USER_ID);

        assertThat(result.cancelledCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        verifyNoInteractions(kisOrderPort);
    }

    @Test
    @DisplayName("사이클 취소: KIS 취소 실패 주문은 failedCount 증가, 나머지 계속 처리")
    void cancelByCycle_partialKisFailure_bestEffort() {
        Order order2 = new Order(UUID.randomUUID(), ACCOUNT_ID, LocalDate.now(),
                TradingCycle.Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.SELL, 3, new BigDecimal("28.00"),
                Order.OrderStatus.PLACED, "KIS_ORD_002");

        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);
        when(orderPort.findPlacedByAccountAndDate(eq(ACCOUNT_ID), any(LocalDate.class)))
                .thenReturn(List.of(PLACED_ORDER, order2));
        doThrow(new RuntimeException("KIS 취소 실패")).when(kisOrderPort).cancel(eq(PLACED_ORDER), any());
        doNothing().when(kisOrderPort).cancel(eq(order2), any());

        CancelOrderUseCase.CancelResult result = service.cancelByCycle(CYCLE_ID, USER_ID);

        assertThat(result.cancelledCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        verify(orderPort, never()).markCancelled(ORDER_ID);       // 실패한 것은 상태 변경 안 함
        verify(orderPort).markCancelled(order2.id());             // 성공한 것만 상태 변경
    }

    @Test
    @DisplayName("사이클 취소: 소유권 불일치 시 SecurityException")
    void cancelByCycle_notOwner_throwsSecurityException() {
        UUID otherId = UUID.randomUUID();
        when(cyclePort.findByIdOrThrow(CYCLE_ID)).thenReturn(CYCLE);
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);

        assertThatThrownBy(() -> service.cancelByCycle(CYCLE_ID, otherId))
                .isInstanceOf(SecurityException.class);
    }

    // ───── cancelOrder ─────

    @Test
    @DisplayName("개별 취소: PLACED 주문 KIS 취소 후 CANCELLED 상태 변경")
    void cancelOrder_success() {
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(PLACED_ORDER));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);
        doNothing().when(kisOrderPort).cancel(any(), any());

        assertThatNoException().isThrownBy(() -> service.cancelOrder(ORDER_ID, USER_ID));

        verify(kisOrderPort).cancel(PLACED_ORDER, ACCOUNT);
        verify(orderPort).markCancelled(ORDER_ID);
    }

    @Test
    @DisplayName("개별 취소: 주문 없으면 NoSuchElementException")
    void cancelOrder_notFound_throws() {
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrder(ORDER_ID, USER_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("개별 취소: PLACED 아닌 주문은 IllegalStateException")
    void cancelOrder_notPlaced_throwsIllegalState() {
        Order filledOrder = new Order(ORDER_ID, ACCOUNT_ID, LocalDate.now(),
                TradingCycle.Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 10, new BigDecimal("25.00"),
                Order.OrderStatus.FILLED, "KIS_ORD_001");
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(filledOrder));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);

        assertThatThrownBy(() -> service.cancelOrder(ORDER_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLACED");
    }

    @Test
    @DisplayName("개별 취소: 소유권 불일치 시 SecurityException")
    void cancelOrder_notOwner_throwsSecurityException() {
        when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(PLACED_ORDER));
        when(accountPort.findByIdOrThrow(ACCOUNT_ID)).thenReturn(ACCOUNT);

        assertThatThrownBy(() -> service.cancelOrder(ORDER_ID, UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
bash gradlew test --tests "com.kista.application.service.OrderCancelServiceTest" 2>&1 | tail -20
```

Expected: 컴파일 오류 (`OrderCancelService` 클래스 없음).

- [ ] **Step 3: OrderCancelService 구현**

새 파일 `src/main/java/com/kista/application/service/OrderCancelService.java`:

```java
package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.CancelOrderUseCase;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCancelService implements CancelOrderUseCase {

    private final TradingCyclePort cyclePort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final KisOrderPort kisOrderPort;

    @Override
    public CancelResult cancelByCycle(UUID cycleId, UUID requesterId) {
        // 소유권 검증
        var cycle   = cyclePort.findByIdOrThrow(cycleId);
        var account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);

        // 오늘 PLACED 주문 조회
        var placed = orderPort.findPlacedByAccountAndDate(account.id(), LocalDate.now());
        if (placed.isEmpty()) {
            return new CancelResult(0, 0);
        }

        // Best-effort: 개별 주문 KIS 취소 시도
        int cancelled = 0;
        int failed    = 0;
        for (Order order : placed) {
            try {
                kisOrderPort.cancel(order, account);
                orderPort.markCancelled(order.id());
                cancelled++;
                log.info("주문 취소 완료: orderId={}, kisOrderId={}", order.id(), order.kisOrderId());
            } catch (Exception e) {
                failed++;
                log.warn("주문 취소 실패 (기존 상태 유지): orderId={}, reason={}", order.id(), e.getMessage());
            }
        }
        return new CancelResult(cancelled, failed);
    }

    @Override
    public void cancelOrder(UUID orderId, UUID requesterId) {
        // 주문 조회
        var order = orderPort.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("주문을 찾을 수 없습니다: " + orderId));

        // 소유권 검증
        Account account = accountPort.findByIdOrThrow(order.accountId());
        account.verifyOwnedBy(requesterId);

        // PLACED 상태 확인
        if (order.status() != Order.OrderStatus.PLACED) {
            throw new IllegalStateException(
                    "PLACED 상태의 주문만 취소할 수 있습니다. 현재 상태: " + order.status());
        }

        // KIS 취소 접수 — 실패 시 예외 전파 (503)
        kisOrderPort.cancel(order, account);
        orderPort.markCancelled(orderId);
        log.info("개별 주문 취소 완료: orderId={}, kisOrderId={}", orderId, order.kisOrderId());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.OrderCancelServiceTest"
```

Expected: `BUILD SUCCESSFUL`, 8개 테스트 모두 통과.

- [ ] **Step 5: 전체 테스트 확인**

```bash
bash gradlew test
```

Expected: `BUILD SUCCESSFUL`, 기존 테스트 전체 통과.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/application/service/OrderCancelService.java \
        src/test/java/com/kista/application/service/OrderCancelServiceTest.java
git commit -m "feat: OrderCancelService 구현 — 사이클 전체/개별 주문 취소 (best-effort)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5: 컨트롤러 + DTO 추가

**Files:**
- Create: `src/main/java/com/kista/adapter/in/web/dto/CancelOrdersResponse.java`
- Modify: `src/main/java/com/kista/adapter/in/web/TradingCycleController.java`
- Create: `src/main/java/com/kista/adapter/in/web/OrderCancelController.java`
- Modify: `src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java`
- Create: `src/test/java/com/kista/adapter/in/web/OrderCancelControllerTest.java`

- [ ] **Step 1: CancelOrdersResponse DTO 생성**

새 파일 `src/main/java/com/kista/adapter/in/web/dto/CancelOrdersResponse.java`:

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.port.in.CancelOrderUseCase;

public record CancelOrdersResponse(int cancelledCount, int failedCount) {
    public static CancelOrdersResponse from(CancelOrderUseCase.CancelResult result) {
        return new CancelOrdersResponse(result.cancelledCount(), result.failedCount());
    }
}
```

- [ ] **Step 2: TradingCycleController에 DELETE 엔드포인트 추가**

`TradingCycleController.java`에 필드 추가:

```java
private final CancelOrderUseCase cancelOrder; // 수동 실행 취소
```

import 추가:
```java
import com.kista.adapter.in.web.dto.CancelOrdersResponse;
import com.kista.domain.port.in.CancelOrderUseCase;
```

기존 `executeManually` 메서드 아래에 추가:

```java
// 오늘 수동 실행으로 PLACED된 주문 전체 취소 (best-effort)
@Operation(summary = "수동 실행 취소 (INFINITE 전용)")
@DeleteMapping("/api/trading-cycles/{id}/execute")
public ResponseEntity<CancelOrdersResponse> cancelExecution(
        @PathVariable UUID id,
        @AuthenticationPrincipal UUID userId) {
    try {
        CancelOrderUseCase.CancelResult result = cancelOrder.cancelByCycle(id, userId);
        return ResponseEntity.ok(CancelOrdersResponse.from(result));
    } catch (SecurityException e) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    }
}
```

- [ ] **Step 3: TradingCycleControllerTest에 @MockBean 및 테스트 추가**

`TradingCycleControllerTest.java`에 추가:

필드에:
```java
@MockBean CancelOrderUseCase cancelOrder;
```

import 추가:
```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.kista.domain.port.in.CancelOrderUseCase;
```

테스트 메서드 추가:

```java
@Test
void cancelExecution_success_returns200WithCounts() throws Exception {
    when(cancelOrder.cancelByCycle(any(), any()))
            .thenReturn(new CancelOrderUseCase.CancelResult(3, 1));

    mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                    .with(csrf()).with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cancelledCount").value(3))
            .andExpect(jsonPath("$.failedCount").value(1));
}

@Test
void cancelExecution_anonymous_returns401() throws Exception {
    mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                    .with(csrf()))
            .andExpect(status().isUnauthorized());
}

@Test
void cancelExecution_notOwner_returns403() throws Exception {
    doThrow(new SecurityException("소유권 불일치"))
            .when(cancelOrder).cancelByCycle(any(), any());

    mockMvc.perform(delete("/api/trading-cycles/{id}/execute", CYCLE_ID)
                    .with(csrf()).with(authentication(mockAuth())))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 4: OrderCancelController 생성**

새 파일 `src/main/java/com/kista/adapter/in/web/OrderCancelController.java`:

> 기존 `OrderController`가 `/api/accounts/{accountId}` class-level mapping을 가지므로 개별 주문 취소 엔드포인트는 별도 컨트롤러로 분리한다.

```java
package com.kista.adapter.in.web;

import com.kista.domain.port.in.CancelOrderUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "주문", description = "주문 취소")
@RestController
@RequiredArgsConstructor
@Slf4j
public class OrderCancelController {

    private final CancelOrderUseCase cancelOrder;

    // 개별 주문 1건 취소 (PLACED 상태만 가능)
    @Operation(summary = "주문 개별 취소")
    @DeleteMapping("/api/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal UUID userId) {
        try {
            cancelOrder.cancelOrder(orderId, userId);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); // 409: PLACED 아님
        } catch (Exception e) {
            log.error("주문 취소 실패: orderId={}, reason={}", orderId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "KIS API 취소 실패");
        }
    }
}
```

- [ ] **Step 5: OrderCancelControllerTest 생성**

새 파일 `src/test/java/com/kista/adapter/in/web/OrderCancelControllerTest.java`:

```java
package com.kista.adapter.in.web;

import com.kista.domain.port.in.CancelOrderUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderCancelController.class)
@Execution(ExecutionMode.SAME_THREAD)
class OrderCancelControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JwtDecoder jwtDecoder;
    @MockBean CancelOrderUseCase cancelOrder;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    void cancelOrder_success_returns204() throws Exception {
        doNothing().when(cancelOrder).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNoContent()); // 204
    }

    @Test
    void cancelOrder_anonymous_returns401() throws Exception {
        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()))
                .andExpect(status().isUnauthorized()); // 401
    }

    @Test
    void cancelOrder_notOwner_returns403() throws Exception {
        doThrow(new SecurityException("소유권 불일치"))
                .when(cancelOrder).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isForbidden()); // 403
    }

    @Test
    void cancelOrder_notFound_returns404() throws Exception {
        doThrow(new NoSuchElementException("주문 없음"))
                .when(cancelOrder).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isNotFound()); // 404 — GlobalExceptionHandler 처리
    }

    @Test
    void cancelOrder_notPlaced_returns409() throws Exception {
        doThrow(new IllegalStateException("PLACED 상태의 주문만 취소할 수 있습니다"))
                .when(cancelOrder).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isConflict()); // 409
    }

    @Test
    void cancelOrder_kisApiFailure_returns503() throws Exception {
        doThrow(new RuntimeException("KIS 연결 오류"))
                .when(cancelOrder).cancelOrder(any(), any());

        mockMvc.perform(delete("/api/orders/{orderId}", ORDER_ID)
                        .with(csrf()).with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable()); // 503
    }
}
```

- [ ] **Step 6: 전체 테스트 실행**

```bash
bash gradlew test
```

Expected: `BUILD SUCCESSFUL`, 신규 + 기존 테스트 모두 통과.

- [ ] **Step 7: 컴파일 + 잔존 패턴 확인**

```bash
bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/dto/CancelOrdersResponse.java \
        src/main/java/com/kista/adapter/in/web/TradingCycleController.java \
        src/main/java/com/kista/adapter/in/web/OrderCancelController.java \
        src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java \
        src/test/java/com/kista/adapter/in/web/OrderCancelControllerTest.java
git commit -m "feat: 주문 취소 API 추가 — DELETE /api/trading-cycles/{id}/execute, DELETE /api/orders/{orderId}

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## 검증 체크리스트

- [ ] `bash gradlew test` 전체 통과
- [ ] 로컬 서버 기동: `bash gradlew bootRun --args='--spring.profiles.active=local'`
- [ ] Swagger (`http://localhost:8080/swagger-ui.html`) 에서 확인:
  - `DELETE /api/trading-cycles/{id}/execute` 엔드포인트 노출
  - `DELETE /api/orders/{orderId}` 엔드포인트 노출
- [ ] 실제 수동 실행 후 취소 시나리오 (개발 토큰 사용):
  1. `POST /api/trading-cycles/{id}/execute` → 202 확인
  2. `DELETE /api/trading-cycles/{id}/execute` → 200 `{"cancelledCount":N,"failedCount":0}` 확인
  3. 동일 취소 재시도 → 200 `{"cancelledCount":0,"failedCount":0}` (PLACED 주문 없음)
