# 토스증권 체결 조회 구현 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 토스 주문 조회 API(`GET /api/v1/orders`)의 `execution` 필드로 체결 내역을 조회해, KIS와 동일하게 `Execution` 도메인 모델로 변환하고 리포팅·포지션 갱신·사이클 회전이 정상 동작하도록 한다.

**Architecture:** `TosExecutionPort` 인터페이스를 신설하고 `TosOrderApi`가 이를 추가 구현한다. 브로커별 라우팅을 담당하는 `BrokerExecutionRouter`를 신설해 `TradingReporter`와 `AccountStatisticsService`에서 `KisExecutionPort` 직접 주입을 제거하고 라우터로 교체한다.

**Tech Stack:** Java 21, Spring Boot 3, Mockito, `TossHttpClient.get()`, `LinkedMultiValueMap`(쿼리 파라미터), `TradeDateConverter`

---

## 파일 구조

| 작업 | 파일 | 역할 |
|------|------|------|
| 신규 | `domain/port/out/TosExecutionPort.java` | 토스 체결 조회 아웃바운드 포트 |
| 수정 | `adapter/out/toss/TosOrderApi.java` | `TosExecutionPort` 추가 구현 + 응답 record 3개 |
| 신규 | `application/service/trading/BrokerExecutionRouter.java` | 브로커별 체결 조회 라우터 |
| 수정 | `application/service/trading/TradingReporter.java` | `kisExecutionPort` → `brokerExecutionRouter` 교체 |
| 수정 | `application/service/account/AccountStatisticsService.java` | `kisExecutionPort` → `brokerExecutionRouter` 교체 |
| 수정 | `adapter/out/toss/TosOrderApiTest.java` | `getExecutions` 단위 테스트 추가 |

---

## Task 1: `TosExecutionPort` 인터페이스 신설

**Files:**
- Create: `kista-api/src/main/java/com/kista/domain/port/out/TosExecutionPort.java`

- [ ] **Step 1: 파일 생성**

```java
package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.time.LocalDate;
import java.util.List;

public interface TosExecutionPort {
    // KisExecutionPort와 동일 시그니처 — 라우터가 브로커 무관하게 위임
    List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account);
}
```

- [ ] **Step 2: 컴파일 확인**

```
cd kista-api && bash gradlew compileJava
```
예상: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/TosExecutionPort.java
git commit -m "feat: add TosExecutionPort interface for execution query"
```

---

## Task 2: `TosOrderApi`에 체결 조회 구현 추가

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/out/toss/TosOrderApi.java`

`TosExecutionPort`를 추가로 구현(`implements TosOrderPort, TosExecutionPort`)하고, `getExecutions` 메서드와 응답 파싱용 package-private record 3개를 추가한다. OPEN+CLOSED 2회 호출 후 `filledQuantity > 0`인 주문만 변환한다.

- [ ] **Step 1: import 및 implements 확장**

`TosOrderApi.java` 상단을 아래와 같이 교체한다.

```java
package com.kista.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.common.TradeDateConverter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.TosExecutionPort;
import com.kista.domain.port.out.TosOrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

클래스 선언:
```java
@Component
@RequiredArgsConstructor
public class TosOrderApi implements TosOrderPort, TosExecutionPort {
```

- [ ] **Step 2: `getExecutions` 메서드 추가**

`cancel()` 메서드 뒤에 추가한다.

```java
    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        // CLOSED + OPEN 두 상태 모두 조회 — PARTIAL_FILLED는 OPEN에 속함
        List<Execution> result = new ArrayList<>();
        result.addAll(fetchExecutions("CLOSED", from, to, ticker, account));
        result.addAll(fetchExecutions("OPEN",   from, to, ticker, account));
        return result;
    }

    // status별 GET /api/v1/orders — 페이지네이션 루프 처리 (CLOSED), OPEN은 단일 응답
    private List<Execution> fetchExecutions(String status, LocalDate from, LocalDate to,
                                             Ticker ticker, Account account) {
        List<Execution> result = new ArrayList<>();
        String cursor = null;
        boolean hasNext = true;

        while (hasNext) {
            // 토스 from/to → TradeDateConverter.toUtc: KST 매매일을 US 거래일(UTC)로 변환
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("status", status);
            params.add("symbol", ticker.name());
            params.add("from",   TradeDateConverter.toUtc(from).toString());
            params.add("to",     TradeDateConverter.toUtc(to).toString());
            params.add("limit",  "100");
            if (cursor != null) params.add("cursor", cursor);

            OrdersResponse response = tossHttpClient.get(ORDER_PATH, account, params, OrdersResponse.class);
            if (response == null || response.orders() == null) break;

            // filledQuantity > 0인 주문만 Execution으로 변환
            for (OrderItem order : response.orders()) {
                if (order.execution() == null) continue;
                String filledQtyStr = order.execution().filledQuantity();
                if (filledQtyStr == null || filledQtyStr.isBlank()) continue;
                int filledQty = Integer.parseInt(filledQtyStr);
                if (filledQty <= 0) continue;

                String priceStr = order.execution().averageFilledPrice();
                BigDecimal price = (priceStr != null && !priceStr.isBlank())
                        ? new BigDecimal(priceStr)
                        : BigDecimal.ZERO;

                String amtStr = order.execution().filledAmount();
                BigDecimal amountUsd = (amtStr != null && !amtStr.isBlank())
                        ? new BigDecimal(amtStr)
                        : price.multiply(BigDecimal.valueOf(filledQty)); // nullable 가드

                // filledAt이 없으면 조회 from 날짜(KST)를 tradeDate로 사용
                LocalDate tradeDate = from;

                Order.OrderDirection direction = "BUY".equals(order.side())
                        ? Order.OrderDirection.BUY
                        : Order.OrderDirection.SELL;

                result.add(new Execution(tradeDate, ticker, direction, filledQty, price, amountUsd, order.orderId()));
            }

            hasNext = Boolean.TRUE.equals(response.hasNext()) && "CLOSED".equals(status);
            cursor  = response.nextCursor();
        }
        return result;
    }
```

- [ ] **Step 3: 응답 파싱 record 3개 추가**

기존 `OrderResponseWrapper`/`OrderResponse` record 아래에 추가한다.

```java
    // GET /api/v1/orders 응답 — package-private으로 테스트에서 직접 생성 가능
    record OrdersResponse(
        @JsonProperty("orders")    List<OrderItem> orders,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("hasNext")    Boolean hasNext
    ) {}

    record OrderItem(
        @JsonProperty("orderId")   String orderId,
        @JsonProperty("symbol")    String symbol,
        @JsonProperty("side")      String side,       // BUY / SELL
        @JsonProperty("status")    String status,
        @JsonProperty("execution") OrderExecutionItem execution
    ) {}

    record OrderExecutionItem(
        @JsonProperty("filledQuantity")      String filledQuantity,      // nullable
        @JsonProperty("averageFilledPrice")  String averageFilledPrice,  // nullable
        @JsonProperty("filledAmount")        String filledAmount,         // nullable
        @JsonProperty("filledAt")            String filledAt              // nullable
    ) {}
```

- [ ] **Step 4: 컴파일 확인**

```
bash gradlew compileJava
```
예상: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/toss/TosOrderApi.java
git commit -m "feat: implement TosExecutionPort in TosOrderApi (OPEN+CLOSED order execution query)"
```

---

## Task 3: `TosOrderApiTest`에 체결 조회 테스트 추가

**Files:**
- Modify: `kista-api/src/test/java/com/kista/adapter/out/toss/TosOrderApiTest.java`

- [ ] **Step 1: import 추가**

기존 import 블록 안에 추가한다.

```java
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order.OrderDirection;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: 체결 조회 테스트 메서드 4개 추가**

기존 `// --- helpers ---` 주석 위에 추가한다.

```java
    @Test
    @DisplayName("CLOSED 체결 → Execution 변환 (filledQuantity>0인 주문만)")
    void getExecutions_closed_convertsFilledOrders() {
        TosOrderApi.OrderExecutionItem exec = new TosOrderApi.OrderExecutionItem("3", "25.50", "76.50", null);
        TosOrderApi.OrderItem item = new TosOrderApi.OrderItem("oid-1", "SOXL", "BUY", "FILLED", exec);
        TosOrderApi.OrdersResponse closedResp = new TosOrderApi.OrdersResponse(List.of(item), null, false);
        TosOrderApi.OrdersResponse openResp   = new TosOrderApi.OrdersResponse(List.of(), null, false);

        // CLOSED 먼저, OPEN 두 번째로 반환
        when(tossHttpClient.get(anyString(), any(), any(), eq(TosOrderApi.OrdersResponse.class)))
            .thenReturn(closedResp)
            .thenReturn(openResp);

        List<Execution> executions = tosOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(executions).hasSize(1);
        Execution e = executions.get(0);
        assertThat(e.quantity()).isEqualTo(3);
        assertThat(e.price()).isEqualByComparingTo("25.50");
        assertThat(e.amountUsd()).isEqualByComparingTo("76.50");
        assertThat(e.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(e.externalOrderId()).isEqualTo("oid-1");
        assertThat(e.ticker()).isEqualTo(Ticker.SOXL);
    }

    @Test
    @DisplayName("filledQuantity=0 또는 null인 주문은 Execution에서 제외")
    void getExecutions_skipsUnfilledOrders() {
        TosOrderApi.OrderExecutionItem noFill  = new TosOrderApi.OrderExecutionItem("0",  null, null, null);
        TosOrderApi.OrderExecutionItem nullFill = new TosOrderApi.OrderExecutionItem(null, null, null, null);
        TosOrderApi.OrderItem unfilledItem  = new TosOrderApi.OrderItem("oid-2", "SOXL", "BUY", "PENDING", noFill);
        TosOrderApi.OrderItem nullFillItem  = new TosOrderApi.OrderItem("oid-3", "SOXL", "BUY", "PENDING", nullFill);
        TosOrderApi.OrdersResponse closedResp = new TosOrderApi.OrdersResponse(List.of(unfilledItem, nullFillItem), null, false);
        TosOrderApi.OrdersResponse openResp   = new TosOrderApi.OrdersResponse(List.of(), null, false);

        when(tossHttpClient.get(anyString(), any(), any(), eq(TosOrderApi.OrdersResponse.class)))
            .thenReturn(closedResp)
            .thenReturn(openResp);

        List<Execution> result = tosOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("OPEN 상태 부분 체결 → Execution 포함")
    void getExecutions_open_partialFilled_included() {
        TosOrderApi.OrderExecutionItem exec = new TosOrderApi.OrderExecutionItem("2", "30.00", "60.00", null);
        TosOrderApi.OrderItem partial = new TosOrderApi.OrderItem("oid-4", "SOXL", "SELL", "PARTIAL_FILLED", exec);
        TosOrderApi.OrdersResponse closedResp = new TosOrderApi.OrdersResponse(List.of(), null, false);
        TosOrderApi.OrdersResponse openResp   = new TosOrderApi.OrdersResponse(List.of(partial), null, false);

        when(tossHttpClient.get(anyString(), any(), any(), eq(TosOrderApi.OrdersResponse.class)))
            .thenReturn(closedResp)
            .thenReturn(openResp);

        List<Execution> result = tosOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).direction()).isEqualTo(OrderDirection.SELL);
        assertThat(result.get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("averageFilledPrice=null → price×quantity로 amountUsd fallback")
    void getExecutions_nullPrice_fallbackAmount() {
        // amountUsd="50.00", price=null → price=BigDecimal.ZERO, amountUsd="50.00"(명시값 우선)
        TosOrderApi.OrderExecutionItem exec = new TosOrderApi.OrderExecutionItem("2", null, "50.00", null);
        TosOrderApi.OrderItem item = new TosOrderApi.OrderItem("oid-5", "SOXL", "BUY", "FILLED", exec);
        TosOrderApi.OrdersResponse closedResp = new TosOrderApi.OrdersResponse(List.of(item), null, false);
        TosOrderApi.OrdersResponse openResp   = new TosOrderApi.OrdersResponse(List.of(), null, false);

        when(tossHttpClient.get(anyString(), any(), any(), eq(TosOrderApi.OrdersResponse.class)))
            .thenReturn(closedResp)
            .thenReturn(openResp);

        List<Execution> result = tosOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amountUsd()).isEqualByComparingTo("50.00");
    }
```

- [ ] **Step 3: 테스트 실행 확인**

```
bash gradlew test --tests 'com.kista.adapter.out.toss.TosOrderApiTest'
```
예상: `BUILD SUCCESSFUL`, 모든 테스트 GREEN

- [ ] **Step 4: 커밋**

```bash
git add src/test/java/com/kista/adapter/out/toss/TosOrderApiTest.java
git commit -m "test: add getExecutions unit tests for TosOrderApi"
```

---

## Task 4: `BrokerExecutionRouter` 신설

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/service/trading/BrokerExecutionRouter.java`

`BrokerMarginRouter` 패턴 그대로 복제. `AccountStatisticsService`(`application.service.account`)에서 import하므로 반드시 `public` 클래스여야 한다.

- [ ] **Step 1: 파일 생성**

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.TosExecutionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

// 브로커 무관하게 체결 내역 조회 — KIS/TOSS 분기 캡슐화
@Component
@RequiredArgsConstructor
public class BrokerExecutionRouter {

    private final KisExecutionPort kisExecutionPort;
    private final TosExecutionPort tosExecutionPort;

    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return switch (account.broker()) {
            case KIS  -> kisExecutionPort.getExecutions(from, to, ticker, account);
            case TOSS -> tosExecutionPort.getExecutions(from, to, ticker, account);
        };
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```
bash gradlew compileJava
```
예상: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/BrokerExecutionRouter.java
git commit -m "feat: add BrokerExecutionRouter for broker-agnostic execution query"
```

---

## Task 5: `TradingReporter` 수정 — KisExecutionPort → BrokerExecutionRouter

**Files:**
- Modify: `kista-api/src/main/java/com/kista/application/service/trading/TradingReporter.java`

- [ ] **Step 1: import 교체**

L17 `import com.kista.domain.port.out.KisExecutionPort;` 를 삭제하고 아래로 교체한다.

```java
import com.kista.application.service.trading.BrokerExecutionRouter;
```

- [ ] **Step 2: 필드 교체 (L43)**

```java
// 변경 전
private final KisExecutionPort kisExecutionPort;

// 변경 후
private final BrokerExecutionRouter brokerExecutionRouter;
```

- [ ] **Step 3: `recordAndNotify` 메서드 내 분기 제거 (L55-59)**

```java
// 변경 전
// Toss 계좌는 체결 조회 API 없음 (MVP) — 주문 PLACED 상태 유지
// today는 KST → KisTradingApi.getExecutions 내부에서 toUtc 변환됨
List<Execution> executions = account.isToss()
        ? Collections.emptyList()
        : kisExecutionPort.getExecutions(today, today, strategy.ticker(), account);

// 변경 후
// today는 KST — 브로커별 어댑터에서 toUtc 변환 처리
List<Execution> executions = brokerExecutionRouter.getExecutions(today, today, strategy.ticker(), account);
```

- [ ] **Step 4: 불필요한 import 정리**

`import java.util.Collections;` 가 이 클래스의 다른 곳에서 사용되지 않는다면 삭제한다. (실제로 `Collections`가 다른 메서드에서 사용되지 않음을 확인 후 제거)

현재 `TradingReporter.java`에서 `Collections`는 L29에 import되어 있고 이 분기 제거 후 미사용이 되므로 삭제한다.

- [ ] **Step 5: 컴파일 확인**

```
bash gradlew compileJava
```
예상: `BUILD SUCCESSFUL`

- [ ] **Step 6: 잔존 패턴 확인**

```
grep -n "account.isToss()" src/main/java/com/kista/application/service/trading/TradingReporter.java
```
예상: 아무 출력 없음 (완전 제거 확인)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingReporter.java
git commit -m "feat: replace KisExecutionPort with BrokerExecutionRouter in TradingReporter"
```

---

## Task 6: `AccountStatisticsService` 수정 — KisExecutionPort → BrokerExecutionRouter

**Files:**
- Modify: `kista-api/src/main/java/com/kista/application/service/account/AccountStatisticsService.java`

- [ ] **Step 1: import 교체**

L18 `import com.kista.domain.port.out.KisExecutionPort;` 를 삭제하고 아래를 추가한다.

```java
import com.kista.application.service.trading.BrokerExecutionRouter;
```

- [ ] **Step 2: 필드 교체 (L51)**

```java
// 변경 전
private final KisExecutionPort kisExecutionPort;

// 변경 후
private final BrokerExecutionRouter brokerExecutionRouter;
```

- [ ] **Step 3: `getExecutions` 메서드 수정 (L71-79)**

```java
// 변경 전
@Override
public List<Execution> getExecutions(UUID accountId, UUID requesterId,
                                      LocalDate from, LocalDate to) {
    Account account = accountPort.requireOwnedAccount(accountId, requesterId);
    // Toss 계좌는 체결 조회 API 미지원 — 빈 리스트 반환
    if (account.isToss()) return Collections.emptyList();
    Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
    if (ticker.isEmpty()) return Collections.emptyList();
    return kisExecutionPort.getExecutions(from, to, ticker.get(), account);
}

// 변경 후
@Override
public List<Execution> getExecutions(UUID accountId, UUID requesterId,
                                      LocalDate from, LocalDate to) {
    Account account = accountPort.requireOwnedAccount(accountId, requesterId);
    Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
    if (ticker.isEmpty()) return Collections.emptyList();
    return brokerExecutionRouter.getExecutions(from, to, ticker.get(), account);
}
```

- [ ] **Step 4: 컴파일 확인**

```
bash gradlew compileJava
```
예상: `BUILD SUCCESSFUL`

- [ ] **Step 5: 잔존 패턴 확인**

```
grep -n "isToss" src/main/java/com/kista/application/service/account/AccountStatisticsService.java
grep -n "kisExecutionPort" src/main/java/com/kista/application/service/account/AccountStatisticsService.java
```
예상: `getExecutions`의 `isToss` 분기는 없어야 함. (다른 메서드의 `isToss`는 유지)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/application/service/account/AccountStatisticsService.java
git commit -m "feat: replace KisExecutionPort with BrokerExecutionRouter in AccountStatisticsService"
```

---

## Task 7: 전체 검증

- [ ] **Step 1: 전체 컴파일**

```
bash gradlew compileJava
```
예상: `BUILD SUCCESSFUL`

- [ ] **Step 2: TosOrderApi 테스트**

```
bash gradlew test --tests 'com.kista.adapter.out.toss.*'
```
예상: `BUILD SUCCESSFUL`, TosOrderApiTest 8개 모두 GREEN

- [ ] **Step 3: ArchUnit 레이어 의존 검증**

```
bash gradlew test --tests 'com.kista.architecture.*'
```
예상: `BUILD SUCCESSFUL`
> `BrokerExecutionRouter`(`application.service.trading`) 가 `AccountStatisticsService`(`application.service.account`)에서 import되는데, 둘 다 `application` 레이어이므로 ArchUnit 위반 없음.

- [ ] **Step 4: 잔존 패턴 전수 조사**

```
grep -rn "account.isToss()" src/main/java/com/kista/application/service/trading/TradingReporter.java
grep -rn "kisExecutionPort.getExecutions" src/main/java/com/kista/application/service/
```
예상: 두 명령 모두 출력 없음

- [ ] **Step 5: 전체 테스트**

```
bash gradlew test
```
예상: `BUILD SUCCESSFUL`, 기존 테스트 회귀 없음

---

## 범위 외 (별도 작업으로 제안)

- `Execution` 모델이 `domain/model/kis/`에 위치하나 이제 KIS 전용이 아님 → `domain/model/order/` 또는 `domain/model/trade/`로 이동 리팩토링 권장
- 토스 `from/to` 타임존 정합성: 실계좌로 체결 조회 후 `filledAt` 필드와 대조해 `TradeDateConverter.toUtc` 적용이 맞는지 최종 확정 필요
