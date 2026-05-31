# trade_histories 제거 — corrections → orders 편입 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `trade_histories` 테이블과 전체 계층을 제거하고, corrections 주문을 `orders` 테이블에 저장하며, FAILED 거래 감지 dead logic을 삭제한다.

**Architecture:** `TradeHistory` 도메인 모델·포트·어댑터·서비스를 전량 제거한다. `OrderPort`에 날짜범위 조회를 추가해 대시보드·관리자 API가 `orders` 테이블에서 직접 조회한다. corrections는 `applyCorrections()` 내에서 accountId를 주입한 뒤 `orderPort.saveAll()`로 저장한다. `AdminAnomaliesService`의 FAILED 거래 감지는 실제로 FAILED가 저장된 적 없는 dead logic이므로 `AdminAnomalies.failedTrades` 필드까지 함께 제거한다.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Mockito, Flyway

---

## 파일 맵

### 신규 생성
- `src/main/resources/db/migration/V49__drop_trade_histories.sql`

### 수정
- `src/main/java/com/kista/domain/port/out/OrderPort.java` — `findBy()`, `findAll()` 추가
- `src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java` — 쿼리 메서드 추가
- `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java` — 구현 추가
- `src/main/java/com/kista/application/service/TradingService.java` — `tradeHistoryPort` 제거, corrections 편입, `toHistory()` 제거
- `src/main/java/com/kista/domain/port/in/GetTradeHistoryUseCase.java` — 반환 타입 `List<Order>`
- `src/main/java/com/kista/domain/port/in/AdminListTradesUseCase.java` — 반환 타입 `List<Order>`
- `src/main/java/com/kista/application/service/TradeHistoryService.java` — `OrderPort` 사용
- `src/main/java/com/kista/application/service/AdminTradeService.java` — `OrderPort` 사용
- `src/main/java/com/kista/application/service/AdminAnomaliesService.java` — `OrderPort` 사용 + FAILED dead logic 제거
- `src/main/java/com/kista/domain/model/admin/AdminAnomalies.java` — `failedTrades` 필드 제거
- `src/main/java/com/kista/adapter/in/web/AdminAnomaliesController.java` — `failedTrades` 제거
- `src/main/java/com/kista/adapter/in/web/AdminTradeController.java` — `TradeHistory` → `Order`
- `src/main/java/com/kista/adapter/in/web/dto/TradeHistoryResponse.java` — `from(Order)`, `amountUsd`/`strategy` 제거
- `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java` — `List<TradeHistory>` → `List<Order>`
- `src/main/resources/static/index.html` — `amountUsd` 참조 제거
- `src/test/java/com/kista/application/service/TradingServiceTest.java`
- `src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java`
- `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`

### 삭제
- `src/main/java/com/kista/domain/model/order/TradeHistory.java`
- `src/main/java/com/kista/domain/port/out/TradeHistoryPort.java`
- `src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryJpaRepository.java`
- `src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryPersistenceAdapter.java`
- `src/test/java/com/kista/adapter/out/persistence/trade/TradeHistoryPersistenceAdapterTest.java`
- `src/test/java/com/kista/application/service/TradeHistoryServiceTest.java`

---

## Task 1: OrderPort 날짜범위 조회 추가

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/OrderPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`

- [ ] **Step 1: OrderPort에 조회 메서드 2개 추가**

`src/main/java/com/kista/domain/port/out/OrderPort.java`:
```java
package com.kista.domain.port.out;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface OrderPort {
    // 계획 주문 일괄 저장 (신규 PLANNED 상태)
    void saveAll(List<Order> orders);

    // 특정 계좌·날짜의 PLANNED 주문 조회 (waitForOrderTime 이후 실행 단계에서 호출)
    List<Order> findPlannedByAccountAndDate(UUID accountId, LocalDate tradeDate);

    // kisOrderPort.place() 완료 후 PLACED 상태 + kisOrderId 기록
    void markPlaced(UUID orderId, String kisOrderId);

    // 기간+종목 필터 조회 (대시보드·텔레그램 이력 조회용)
    List<Order> findBy(LocalDate from, LocalDate to, Ticker ticker);

    // 기간 내 전체 계좌 조회 ticker 필터 없음 (관리자·이상징후 감지용)
    List<Order> findAll(LocalDate from, LocalDate to);
}
```

- [ ] **Step 2: OrderJpaRepository에 쿼리 메서드 추가**

`src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java`:
```java
package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {
    // account_id + trade_date + status = PLANNED 조건으로 실행 대상 조회
    List<OrderEntity> findByAccountIdAndTradeDateAndStatus(
            UUID accountId, LocalDate tradeDate, Order.OrderStatus status);

    // 기간+종목 필터 (대시보드용)
    List<OrderEntity> findByTradeDateBetweenAndTicker(LocalDate from, LocalDate to, Ticker ticker);

    // 기간 전체 (관리자용)
    List<OrderEntity> findByTradeDateBetween(LocalDate from, LocalDate to);
}
```

- [ ] **Step 3: OrderPersistenceAdapter에 구현 추가**

`src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`의 기존 3개 메서드 아래에 추가:
```java
    @Override
    public List<Order> findBy(LocalDate from, LocalDate to, Ticker ticker) {
        return repository
                .findByTradeDateBetweenAndTicker(
                        TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to), ticker)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Order> findAll(LocalDate from, LocalDate to) {
        return repository
                .findByTradeDateBetween(TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to))
                .stream()
                .map(this::toDomain)
                .toList();
    }
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/OrderPort.java \
        src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java \
        src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java
git commit -m "feat(order): OrderPort에 날짜범위 조회 메서드 추가

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2: TradingService — corrections 편입 + tradeHistoryPort 제거

**Files:**
- Modify: `src/main/java/com/kista/application/service/TradingService.java`
- Modify: `src/test/java/com/kista/application/service/TradingServiceTest.java`

- [ ] **Step 1: TradingService 필드·임포트 정리**

`TradingService.java`에서 다음을 제거:
```java
// 제거할 import
import com.kista.domain.model.order.TradeHistory;
import com.kista.domain.port.out.TradeHistoryPort;

// 제거할 필드
private final TradeHistoryPort tradeHistoryPort;           // 거래 이력 저장
```

- [ ] **Step 2: applyCorrections() — accountId 주입 + orderPort 저장**

기존:
```java
private List<Order> applyCorrections(InfinitePosition position, BigDecimal closingPrice,
                                     List<Execution> executions, LocalDate today, Account account) {
    List<Order> corrections = correctionStrategy.correct(position, closingPrice, executions, today)
            .stream()
            .map(o -> kisOrderPort.place(o, account))
            .toList();
    log.info("[{}] 보정 주문 {}건", account.nickname(), corrections.size());
    return corrections;
}
```

변경 후:
```java
private List<Order> applyCorrections(InfinitePosition position, BigDecimal closingPrice,
                                     List<Execution> executions, LocalDate today, Account account) {
    List<Order> corrections = correctionStrategy.correct(position, closingPrice, executions, today)
            .stream()
            .map(o -> kisOrderPort.place(o, account))
            .map(o -> new Order(null, account.id(), o.tradeDate(), o.ticker(), // accountId 주입
                                o.orderType(), o.direction(), o.quantity(), o.price(),
                                o.status(), o.kisOrderId()))
            .toList();
    if (!corrections.isEmpty()) {
        orderPort.saveAll(corrections); // corrections를 orders 테이블에 저장
    }
    log.info("[{}] 보정 주문 {}건", account.nickname(), corrections.size());
    return corrections;
}
```

- [ ] **Step 3: saveAndNotify() — tradeHistoryPort 호출 2줄 제거**

기존 `saveAndNotify()` 내:
```java
mainOrders.forEach(o -> tradeHistoryPort.save(toHistory(o, account.id())));
corrections.forEach(o -> tradeHistoryPort.save(toHistory(o, account.id())));
log.info("[{}] 거래 이력 {}건 저장", account.nickname(), mainOrders.size() + corrections.size());
```
위 3줄을 완전히 삭제한다.

- [ ] **Step 4: toHistory() 헬퍼 메서드 삭제**

`TradingService.java`에서 다음 메서드 전체 삭제:
```java
private TradeHistory toHistory(Order o, java.util.UUID accountId) {
    BigDecimal amountUsd = o.price().multiply(BigDecimal.valueOf(o.quantity()))
            .setScale(2, HALF_UP);
    return new TradeHistory(
            null, o.tradeDate(), o.ticker(), "SOXL_DIVISION",
            o.orderType(), o.direction(), o.quantity(), o.price(),
            amountUsd, o.status(), o.kisOrderId(), accountId, null);
}
```

- [ ] **Step 5: TradingServiceTest — tradeHistoryPort mock 제거 + 생성자 수정**

`TradingServiceTest.java`에서:

제거:
```java
@Mock TradeHistoryPort tradeHistoryPort;
```

제거할 import:
```java
import com.kista.domain.model.order.TradeHistory;
import com.kista.domain.port.out.TradeHistoryPort;
```

생성자 호출에서 `tradeHistoryPort,` 제거 (portfolioSnapshotPort 앞):
```java
// 변경 전
service = new TradingService(kisHolidayPort, kisPricePort, kisOrderPort, kisExecutionPort,
        infiniteStrategy, privacyStrategy, correctionStrategy,
        tradeHistoryPort, portfolioSnapshotPort, notifyPort, userNotificationPort,
        orderPort, realtimeNotificationPort, cycleHistoryPort,
        accountPort, cyclePort, privacyTradePort, kisMarginPort);

// 변경 후
service = new TradingService(kisHolidayPort, kisPricePort, kisOrderPort, kisExecutionPort,
        infiniteStrategy, privacyStrategy, correctionStrategy,
        portfolioSnapshotPort, notifyPort, userNotificationPort,
        orderPort, realtimeNotificationPort, cycleHistoryPort,
        accountPort, cyclePort, privacyTradePort, kisMarginPort);
```

- [ ] **Step 6: TradingServiceTest — 테스트 메서드 수정**

`execute_tradeHistories_savedForMainAndCorrectionOrders` 테스트:

기존:
```java
verify(tradeHistoryPort, times(2)).save(any(TradeHistory.class));
```
변경 후:
```java
// Phase A saveAll(planned) 1회 + applyCorrections saveAll(corrections) 1회 = 2회
verify(orderPort, times(2)).saveAll(anyList());
```

테스트 메서드명도 변경:
```
execute_tradeHistories_savedForMainAndCorrectionOrders
→ execute_corrections_savedToOrders_withAccountId
```

`execute_insufficientBalance_notifiesAndSkipsTrading` 테스트에서 제거:
```java
verify(tradeHistoryPort, never()).save(any());
```

`executeBatch_getPricesFails_cycleFailsAndNotifiesAdmin` 테스트에서 제거:
```java
verify(tradeHistoryPort, never()).save(any()); // 현재가 조회 실패로 주문 없음
```

- [ ] **Step 7: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.application.service.TradingServiceTest'
```
Expected: `BUILD SUCCESSFUL`, 모든 테스트 PASS

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/kista/application/service/TradingService.java \
        src/test/java/com/kista/application/service/TradingServiceTest.java
git commit -m "refactor(trading): corrections를 orders 테이블에 저장, tradeHistoryPort 제거

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3: UseCase 인터페이스 + 서비스 → Order 기반으로 전환

**Files:**
- Modify: `src/main/java/com/kista/domain/port/in/GetTradeHistoryUseCase.java`
- Modify: `src/main/java/com/kista/domain/port/in/AdminListTradesUseCase.java`
- Modify: `src/main/java/com/kista/application/service/TradeHistoryService.java`
- Modify: `src/main/java/com/kista/application/service/AdminTradeService.java`

- [ ] **Step 1: GetTradeHistoryUseCase 반환 타입 변경**

`src/main/java/com/kista/domain/port/in/GetTradeHistoryUseCase.java`:
```java
package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.time.LocalDate;
import java.util.List;

public interface GetTradeHistoryUseCase {
    List<Order> getHistory(LocalDate from, LocalDate to, Ticker ticker);
}
```

- [ ] **Step 2: AdminListTradesUseCase 반환 타입 변경**

`src/main/java/com/kista/domain/port/in/AdminListTradesUseCase.java`:
```java
package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import java.util.List;

public interface AdminListTradesUseCase {
    List<Order> listAll(); // 최근 30일 전체 계좌
}
```

- [ ] **Step 3: TradeHistoryService — OrderPort 사용**

`src/main/java/com/kista/application/service/TradeHistoryService.java`:
```java
package com.kista.application.service;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeHistoryService implements GetTradeHistoryUseCase {

    private final OrderPort orderPort;

    @Override
    public List<Order> getHistory(LocalDate from, LocalDate to, Ticker ticker) {
        return orderPort.findBy(from, to, ticker);
    }
}
```

- [ ] **Step 4: AdminTradeService — OrderPort 사용**

`src/main/java/com/kista/application/service/AdminTradeService.java`:
```java
package com.kista.application.service;

import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTradeService implements AdminListTradesUseCase {

    private final OrderPort orderPort; // 거래 내역 조회 포트

    @Override
    public List<Order> listAll() {
        // 최근 30일 전체 계좌 거래 내역 조회
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        return orderPort.findAll(from, to);
    }
}
```

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/domain/port/in/GetTradeHistoryUseCase.java \
        src/main/java/com/kista/domain/port/in/AdminListTradesUseCase.java \
        src/main/java/com/kista/application/service/TradeHistoryService.java \
        src/main/java/com/kista/application/service/AdminTradeService.java
git commit -m "refactor(history): GetTradeHistoryUseCase·AdminListTradesUseCase를 Order 기반으로 전환

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4: AdminAnomaliesService — OrderPort 전환 + FAILED dead logic 제거

**Files:**
- Modify: `src/main/java/com/kista/application/service/AdminAnomaliesService.java`
- Modify: `src/main/java/com/kista/domain/model/admin/AdminAnomalies.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AdminAnomaliesController.java`

- [ ] **Step 1: AdminAnomalies record에서 failedTrades 제거**

`src/main/java/com/kista/domain/model/admin/AdminAnomalies.java`:
```java
package com.kista.domain.model.admin;

import com.kista.domain.model.account.Account;

import java.util.List;

// 이상 징후 집계 도메인 모델 — 컨트롤러에서 DTO 변환
public record AdminAnomalies(
    List<Account> pausedAccounts,         // 전략 PAUSED 계좌
    List<Account> inactiveAccounts        // 최근 7일 거래 없는 ACTIVE 전략 계좌
) {}
```

- [ ] **Step 2: AdminAnomaliesService — OrderPort 사용, FAILED 로직 제거**

`src/main/java/com/kista/application/service/AdminAnomaliesService.java`:
```java
package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.TradingCyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnomaliesService implements AdminAnomaliesUseCase {

    private final OrderPort orderPort;
    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;

    @Override
    public AdminAnomalies getAnomalies() {
        LocalDate today = LocalDate.now();

        List<Account> allAccounts = accountPort.findAll();

        // PAUSED 사이클이 있는 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> cyclePort.findByAccountId(a.id()).stream()
                        .anyMatch(c -> c.status() == TradingCycle.Status.PAUSED))
                .toList();

        // 최근 7일 거래 있는 accountId 집합
        Set<UUID> activeAccountIds = orderPort.findAll(today.minusDays(7), today)
                .stream().map(Order::accountId).collect(Collectors.toSet());

        // ACTIVE 사이클이 있지만 7일 내 거래 없는 계좌
        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> cyclePort.findByAccountId(a.id()).stream()
                        .anyMatch(c -> c.status() == TradingCycle.Status.ACTIVE))
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(pausedAccounts, inactiveAccounts);
    }
}
```

- [ ] **Step 3: AdminAnomaliesController — failedTrades 제거**

`src/main/java/com/kista/adapter/in/web/AdminAnomaliesController.java` 전체를 다음으로 교체:
```java
package com.kista.adapter.in.web;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/anomalies")
@RequiredArgsConstructor
public class AdminAnomaliesController {

    private final AdminAnomaliesUseCase anomaliesUseCase;
    private final AdminListAccountsUseCase listAccounts; // userId → nickname 역방향 조회용
    private final AdminListUsersUseCase listUsers;

    @GetMapping
    public AdminAnomaliesResponse getAnomalies() {
        AdminAnomalies anomalies = anomaliesUseCase.getAnomalies();

        Map<UUID, AdminUserView> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));

        List<AccountItem> pausedAccounts = anomalies.pausedAccounts().stream()
                .map(a -> AccountItem.from(a, userMap))
                .toList();

        List<AccountItem> inactiveAccounts = anomalies.inactiveAccounts().stream()
                .map(a -> AccountItem.from(a, userMap))
                .toList();

        return new AdminAnomaliesResponse(pausedAccounts, inactiveAccounts);
    }

    record AdminAnomaliesResponse(
            List<AccountItem> pausedAccounts,
            List<AccountItem> inactiveAccounts
    ) {}

    record AccountItem(
            UUID id,
            UUID userId,
            String ownerNickname,
            String accountNoMasked,
            String broker
    ) {
        static AccountItem from(Account a, Map<UUID, AdminUserView> userMap) {
            AdminUserView user = a.userId() != null ? userMap.get(a.userId()) : null;
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            String masked = a.accountNo() != null
                    ? "****" + a.accountNo().substring(Math.max(0, a.accountNo().length() - 4))
                    : "****";
            return new AccountItem(
                    a.id(), a.userId(), nickname, masked,
                    a.broker() != null ? a.broker().name() : null);
        }
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/AdminAnomaliesService.java \
        src/main/java/com/kista/domain/model/admin/AdminAnomalies.java \
        src/main/java/com/kista/adapter/in/web/AdminAnomaliesController.java
git commit -m "refactor(admin): FAILED dead logic 제거, AdminAnomalies에서 failedTrades 필드 삭제

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5: 응답 DTO + 컨트롤러 + TelegramBotService 업데이트

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/dto/TradeHistoryResponse.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AdminTradeController.java`
- Modify: `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java`
- Modify: `src/main/resources/static/index.html`
- Modify: `src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java`
- Modify: `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`

- [ ] **Step 1: TradeHistoryResponse — amountUsd·strategy 제거, from(Order)**

`src/main/java/com/kista/adapter/in/web/dto/TradeHistoryResponse.java`:
```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TradeHistoryResponse(
        @Schema(description = "거래 내역 고유 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,
        @Schema(description = "거래 날짜", example = "2025-01-15")
        LocalDate tradeDate,
        @Schema(description = "거래 종목", example = "SOXL")
        Ticker ticker,
        @Schema(description = "주문 유형 (LOC/MOC/LIMIT)", example = "LOC")
        Order.OrderType orderType,
        @Schema(description = "매매 방향 (BUY/SELL)", example = "BUY")
        Order.OrderDirection direction,
        @Schema(description = "주문 수량", example = "5")
        int quantity,
        @Schema(description = "주문 단가 (USD)", example = "85.50")
        BigDecimal price,
        @Schema(description = "주문 상태 (PLACED/FAILED)", example = "PLACED")
        Order.OrderStatus status,
        @Schema(description = "증권사 주문번호", example = "0000123456")
        String orderId,
        @Schema(description = "생성 일시 (UTC)", example = "2025-01-15T07:00:01Z")
        Instant createdAt
) {
    public static TradeHistoryResponse from(Order o) {
        return new TradeHistoryResponse(
                o.id(), o.tradeDate(), o.ticker(),
                o.orderType(), o.direction(), o.quantity(), o.price(),
                o.status(), o.kisOrderId(), o.createdAt());
    }
}
```

`Order` record에 `createdAt()` 접근자가 없으므로 `OrderEntity`가 `BaseAuditEntity`를 상속해 `createdAt`을 가짐. `Order` record에 `createdAt` 필드를 추가해야 한다.

`src/main/java/com/kista/domain/model/order/Order.java`에 `Instant createdAt` 필드 추가:
```java
package com.kista.domain.model.order;

import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Order(
        UUID id,                   // PK (null = 신규 PLANNED)
        UUID accountId,            // FK → accounts.id
        LocalDate tradeDate,       // 거래일
        Ticker ticker,             // 거래 종목
        OrderType orderType,       // 주문 유형 (LOC/MOC/LIMIT)
        OrderDirection direction,  // 매수/매도 방향
        Integer quantity,          // 주문 수량(nullable)
        BigDecimal price,          // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,        // 주문 상태
        String kisOrderId,         // KIS 시스템 부여 주문 번호 (ODNO), PLANNED 상태에서는 null
        Instant createdAt          // 생성 일시 (null = 신규)
) {
    public enum OrderType {
        LOC,   // Limit On Close: 종가 지정가 주문
        MOC,   // Market On Close: 종가 시장가 주문
        LIMIT  // 일반 지정가 주문
    }

    public enum OrderDirection {BUY, SELL}

    public enum OrderStatus {
        PLANNED,  // DB 저장, KIS 접수 대기
        PLACED,   // KIS 접수 완료
        FILLED,   // 체결 완료
        FAILED    // 실패
    }

    // 전략 계산 결과(template)를 특정 계좌의 PLANNED 주문으로 변환
    public static Order plan(Order template, UUID accountId) {
        return new Order(null, accountId, template.tradeDate(), template.ticker(),
                template.orderType(), template.direction(), template.quantity(),
                template.price(), OrderStatus.PLANNED, null, null);
    }
}
```

`OrderPersistenceAdapter.toDomain()`에 `e.getCreatedAt()` 추가:
```java
private Order toDomain(OrderEntity e) {
    return new Order(
            e.getId(), e.getAccountId(), TradeDateConverter.toKst(e.getTradeDate()), e.getTicker(),
            e.getOrderType(), e.getDirection(), e.getQuantity(), e.getPrice(),
            e.getStatus(), e.getKisOrderId(), e.getCreatedAt()
    );
}
```

`OrderPersistenceAdapter.toEntity()`는 `createdAt`을 set하지 않는다 — `BaseAuditEntity`의 `@CreatedDate`가 자동 처리.

`applyCorrections()`의 새 Order 생성부에 `null` createdAt 추가:
```java
.map(o -> new Order(null, account.id(), o.tradeDate(), o.ticker(),
                    o.orderType(), o.direction(), o.quantity(), o.price(),
                    o.status(), o.kisOrderId(), null))
```

`CorrectionStrategy.correct()`의 Order 생성부에도 `null` createdAt 추가:
```java
return List.of(new Order(null, null, tradeDate, position.ticker(),
        LIMIT, BUY, quantity, closingPrice, PLACED, null, null));
```

`Order.plan()` 이미 수정됨.

`TradingServiceTest`에서 `new Order(...)` 직접 생성하는 모든 곳에 마지막 인자로 `null` 추가 (createdAt). 해당 파일의 `Order` 생성자 호출을 전수 확인해야 함.

- [ ] **Step 2: AdminTradeController — Order 기반으로 교체**

`src/main/java/com/kista/adapter/in/web/AdminTradeController.java`에서 `AdminTradeResponse.from()`을 `TradeHistory` → `Order` 기반으로:
```java
record AdminTradeResponse(
        UUID id,
        UUID userId,
        String ownerNickname,
        LocalDate tradeDate,
        String ticker,
        String direction,
        String orderType,
        int quantity,
        BigDecimal price,
        String status
) {
    static AdminTradeResponse from(Order t, Map<UUID, Account> accountMap, Map<UUID, AdminUserView> userMap) {
        Account account = t.accountId() != null ? accountMap.get(t.accountId()) : null;
        UUID userId = account != null ? account.userId() : null;
        AdminUserView user = userId != null ? userMap.get(userId) : null;
        String nickname = user != null ? user.nickname() : "(알 수 없음)";
        return new AdminTradeResponse(
                t.id(), userId, nickname, t.tradeDate(), t.ticker().name(),
                t.direction().name(), t.orderType().name(),
                t.quantity(), t.price(), t.status().name());
    }
}
```

import에서 `TradeHistory` 제거, `Order` 추가.
`listTrades()` 메서드의 `listTrades.listAll()` 반환 타입이 이미 `List<Order>`이므로 컴파일됨.

- [ ] **Step 3: TelegramBotService — List<Order> 사용**

`src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java`에서 `buildHistoryMessage()`:
```java
private String buildHistoryMessage(int days) {
    LocalDate to = LocalDate.now();
    LocalDate from = to.minusDays(days);
    List<Order> list = getTradeHistoryUseCase.getHistory(from, to, Ticker.SOXL);
    if (list.isEmpty()) return "최근 " + days + "일 거래 내역이 없습니다.";
    StringBuilder sb = new StringBuilder("<b>최근 " + days + "일 거래 내역</b>\n");
    list.forEach(h -> sb.append(String.format("%s %s %s %d주 $%.4f%n",
            h.tradeDate(), h.direction(), h.orderType(), h.quantity(), h.price())));
    return sb.toString().trim();
}
```

import에서 `TradeHistory` 제거, `Order` 추가.

- [ ] **Step 4: index.html — amountUsd 참조 수정**

`src/main/resources/static/index.html`에서 `h.amountUsd` 표시를 `price × quantity`로 교체:

기존:
```js
tr.appendChild(cell('$' + parseFloat(h.amountUsd).toFixed(2)));
```
변경 후:
```js
tr.appendChild(cell('$' + (parseFloat(h.price) * h.quantity).toFixed(2)));
```

- [ ] **Step 5: DashboardControllerTest 수정**

`src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java`:

```java
@Test
void getTrades_returns_200_with_list() throws Exception {
    Order o = new Order(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
            new BigDecimal("25.00"), Order.OrderStatus.PLACED, "KIS001", Instant.now());
    when(getTradeHistoryUseCase.getHistory(any(), any(), any())).thenReturn(List.of(o));

    mockMvc.perform(get("/api/trades"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ticker").value("SOXL"))
            .andExpect(jsonPath("$[0].quantity").value(10));
}
```

import에서 `TradeHistory` 제거, `Order`, `Instant` 추가.

- [ ] **Step 6: TelegramBotServiceTest 수정**

`src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`에서 `getHistory` stub이 반환하는 타입을 `List<Order>`로:

기존:
```java
when(getTradeHistoryUseCase.getHistory(any(), any(), eq(Ticker.SOXL))).thenReturn(List.of());
```
변경 후: 타입이 `List<Order>`이므로 `List.of()`를 그대로 사용하면 됨 (빈 리스트는 타입 무관). import에서 `TradeHistory` 제거.

- [ ] **Step 7: TradingServiceTest — Order 생성자 인자 수 수정**

TradingServiceTest의 모든 `new Order(...)` 호출에 마지막 인자 `null` (createdAt) 추가:

검색 명령:
```bash
grep -n "new Order(" src/test/java/com/kista/application/service/TradingServiceTest.java
```
각 호출마다 기존 10개 인자 → 11개 인자 (`null` createdAt 추가).

다른 테스트 파일도 동일하게 확인:
```bash
grep -rn "new Order(" src/test/ --include="*.java"
```

- [ ] **Step 8: 전체 테스트 실행**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.DashboardControllerTest' \
               --tests 'com.kista.adapter.in.telegram.TelegramBotServiceTest' \
               --tests 'com.kista.application.service.TradingServiceTest'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/dto/TradeHistoryResponse.java \
        src/main/java/com/kista/adapter/in/web/AdminTradeController.java \
        src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java \
        src/main/java/com/kista/domain/model/order/Order.java \
        src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java \
        src/main/java/com/kista/domain/strategy/CorrectionStrategy.java \
        src/main/resources/static/index.html \
        src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java \
        src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java \
        src/test/java/com/kista/application/service/TradingServiceTest.java
git commit -m "refactor(dto): TradeHistoryResponse·컨트롤러·TelegramBotService를 Order 기반으로 교체

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 6: TradeHistory 계층 전체 삭제

**Files:**
- Delete: `src/main/java/com/kista/domain/model/order/TradeHistory.java`
- Delete: `src/main/java/com/kista/domain/port/out/TradeHistoryPort.java`
- Delete: `src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryEntity.java`
- Delete: `src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryJpaRepository.java`
- Delete: `src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryPersistenceAdapter.java`
- Delete: `src/test/java/com/kista/adapter/out/persistence/trade/TradeHistoryPersistenceAdapterTest.java`
- Delete: `src/test/java/com/kista/application/service/TradeHistoryServiceTest.java`

- [ ] **Step 1: 파일 삭제**

```bash
rm src/main/java/com/kista/domain/model/order/TradeHistory.java
rm src/main/java/com/kista/domain/port/out/TradeHistoryPort.java
rm src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryEntity.java
rm src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryJpaRepository.java
rm src/main/java/com/kista/adapter/out/persistence/trade/TradeHistoryPersistenceAdapter.java
rm src/test/java/com/kista/adapter/out/persistence/trade/TradeHistoryPersistenceAdapterTest.java
rm src/test/java/com/kista/application/service/TradeHistoryServiceTest.java
```

- [ ] **Step 2: 전체 컴파일 + 테스트 확인**

```bash
./gradlew compileJava compileTestJava
```
Expected: `BUILD SUCCESSFUL` (TradeHistory 참조 잔여 시 컴파일 오류로 즉시 발견됨)

```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add -u  # 삭제된 파일 스테이징
git commit -m "chore: TradeHistory 도메인·포트·어댑터·테스트 전량 삭제

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 7: V49 Flyway 마이그레이션 — trade_histories 테이블 삭제

**Files:**
- Create: `src/main/resources/db/migration/V49__drop_trade_histories.sql`

- [ ] **Step 1: 마이그레이션 파일 생성**

`src/main/resources/db/migration/V49__drop_trade_histories.sql`:
```sql
-- corrections는 orders 테이블에 저장되므로 trade_histories 테이블 제거
DROP TABLE IF EXISTS trade_histories;
```

- [ ] **Step 2: 로컬 DB에 마이그레이션 적용 확인**

```bash
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local' &
# 앱 기동 로그에서 "Successfully applied 1 migration" 확인 후 종료
```

- [ ] **Step 3: 커밋**

```bash
git add src/main/resources/db/migration/V49__drop_trade_histories.sql
git commit -m "chore(db): V49 — trade_histories 테이블 삭제

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- ✅ corrections를 orders 테이블에 저장 (Task 2)
- ✅ trade_histories 계층 전량 제거 (Task 6)
- ✅ FAILED dead logic 삭제 (Task 4)
- ✅ AdminAnomalies.failedTrades 필드 제거 (Task 4)
- ✅ amountUsd 컬럼 미추가 (DTO에서도 제거, index.html 계산으로 대체)
- ✅ V49 Flyway migration (Task 7)

**타입 일관성:**
- `Order` record에 `createdAt` 필드가 Task 5에서 추가됨 — Task 2의 `applyCorrections()` 수정에서 이미 `null` 인자 11번째로 반영됨
- `Order.plan()`도 Task 5에서 `null` createdAt 추가
- `CorrectionStrategy.correct()`에서도 Task 5에서 11개 인자로 수정

**주의 사항:**
- `Order` record에 `createdAt` 추가로 인해 `new Order(...)` 직접 생성하는 모든 테스트에서 인자 수 업데이트 필요 — Task 5 Step 7에서 grep으로 전수 확인
- CLAUDE.md 동시 수정 필요 파일 쌍: `Order` record 필드 추가 → `OrderEntity` + `OrderPersistenceAdapter` + 테스트들 — 이미 플랜에 반영됨
