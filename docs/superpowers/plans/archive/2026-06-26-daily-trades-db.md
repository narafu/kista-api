# Daily Trades DB 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/accounts/{id}/daily-trades` 엔드포인트가 브로커 API(KIS CTOS4001R / Toss executions)를 실시간 호출하는 대신 DB `orders` 테이블의 FILLED/PARTIALLY_FILLED 주문을 조회하도록 전환한다.

**Architecture:** `AccountStatisticsService.getDailyTransactions`에서 `BrokerStatisticsRouter` 대신 `OrderPort.findFilledByAccount`를 호출한다. 응답 DTO 형식(`DailyTransactionResponse`)은 그대로 유지되므로 UI 변경 없음. 변경으로 고아가 된 `DailyTradePort` / `KisDailyTransactionPort` 인터페이스와 브로커 어댑터 구현을 제거한다.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, PostgreSQL

## Global Constraints

- `orders.trade_date`는 UTC(US 거래일) 저장. `TradeDateConverter.toUtc(KST)` = KST-1일. `TradeDateConverter.toKst(UTC)` = UTC+1일
- `OrderPersistenceAdapter.toDomain()`은 이미 `TradeDateConverter.toKst()` 변환을 포함 → 도메인 `Order.tradeDate()`는 KST
- `symbolName`: DB에 종목 표시명 없음 → `ticker.name()`으로 대체 (`WeeklyMarketCalendar`는 symbolName 미사용이므로 영향 없음)
- `settlementDate`, `settlementAmountKrw`, `exchangeRate`: DB 미저장 → null 허용 (DTO는 이미 nullable)
- `currency`: 항상 `"USD"`
- 수수료(`domesticFee`, `overseasFee`): DB 미저장 → `BigDecimal.ZERO`
- `tradeAmountUsd`: `filledPrice * filledQuantity` (실제 체결가 × 체결수량)
- UI(`useWeeklyTradeSummaryQuery`)는 `tradeDate`, `direction`, `tradeAmountUsd` 세 필드만 사용 → 나머지 필드 변경 영향 없음

---

## 수정 대상 파일

### 추가/수정

| 파일 | 역할 |
|---|---|
| `domain/port/out/OrderPort.java` | `findFilledByAccount(UUID, LocalDate, LocalDate)` 메서드 추가 |
| `adapter/out/persistence/trade/OrderJpaRepository.java` | `findByAccountIdAndTradeDateBetweenAndStatusIn` JPA 파생 메서드 추가 |
| `adapter/out/persistence/trade/OrderPersistenceAdapter.java` | `findFilledByAccount` 구현 추가 |
| `application/service/account/AccountStatisticsService.java` | `OrderPort` 주입, `getDailyTransactions` 로직 교체 |
| `application/service/account/BrokerStatisticsRouter.java` | `getDailyTransactions` 메서드 및 관련 import 제거 |
| `adapter/out/kis/KisBrokerAdapter.java` | `DailyTradePort` implements 제거, `kisDailyTransactionPort` 필드·메서드 제거 |
| `adapter/out/kis/KisTradingApi.java` | `KisDailyTransactionPort` implements 제거, `getDailyTransactions` 메서드 제거 |
| `adapter/out/toss/TossBrokerAdapter.java` | `DailyTradePort` implements 제거, `strategyPort`·`tossCommissionsPort` 필드·`getDailyTransactions`·`emptySummary` 제거 |

### 삭제

| 파일 | 이유 |
|---|---|
| `domain/port/out/broker/DailyTradePort.java` | 브로커 어댑터에서 구현체 제거 후 참조 없음 |
| `domain/port/out/KisDailyTransactionPort.java` | `KisBrokerAdapter` 제거 후 참조 없음 |

---

## Task 1: OrderPort — DB 조회 메서드 추가

**Files:**
- Modify: `domain/port/out/OrderPort.java`
- Modify: `adapter/out/persistence/trade/OrderJpaRepository.java`
- Modify: `adapter/out/persistence/trade/OrderPersistenceAdapter.java`

**Interfaces:**
- Produces: `OrderPort.findFilledByAccount(UUID accountId, LocalDate from, LocalDate to): List<Order>` — 반환 Order의 `tradeDate()`는 KST, `filledPrice()`는 nullable이 아님(FILLED는 항상 설정)

---

- [ ] **Step 1: `OrderPort`에 메서드 추가**

`domain/port/out/OrderPort.java` — 파일 끝 `markFilled` 아래에 추가:

```java
// 계좌 기준 FILLED/PARTIALLY_FILLED 주문 조회 (일별 거래내역 달력용)
List<Order> findFilledByAccount(UUID accountId, LocalDate from, LocalDate to);
```

- [ ] **Step 2: `OrderJpaRepository`에 JPA 파생 메서드 추가**

`adapter/out/persistence/trade/OrderJpaRepository.java` — 파일 끝에 추가:

```java
// 계좌·날짜범위·상태 복합 조건 조회 (daily-trades DB 전환용)
List<OrderEntity> findByAccountIdAndTradeDateBetweenAndStatusIn(
        UUID accountId, LocalDate from, LocalDate to,
        List<Order.OrderStatus> statuses);
```

- [ ] **Step 3: `OrderPersistenceAdapter`에 구현 추가**

`adapter/out/persistence/trade/OrderPersistenceAdapter.java` — `sumPlannedBuyByAccountAndDate` 아래에 추가:

```java
@Override
public List<Order> findFilledByAccount(UUID accountId, LocalDate from, LocalDate to) {
    return repository.findByAccountIdAndTradeDateBetweenAndStatusIn(
            accountId,
            TradeDateConverter.toUtc(from),
            TradeDateConverter.toUtc(to),
            List.of(Order.OrderStatus.FILLED, Order.OrderStatus.PARTIALLY_FILLED)
    ).stream().map(this::toDomain).toList();
}
```

> `toDomain()`은 이미 `TradeDateConverter.toKst()`를 적용하므로 반환된 `Order.tradeDate()`는 KST.

- [ ] **Step 4: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git -C /mnt/c/Users/USER/workspace/kista/kista-api add \
  src/main/java/com/kista/domain/port/out/OrderPort.java \
  src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java \
  src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java
git -C /mnt/c/Users/USER/workspace/kista/kista-api commit -m "$(cat <<'EOF'
feat(order): DB에서 계좌별 FILLED 주문 조회 메서드 추가

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: AccountStatisticsService — DB 기반으로 전환

**Files:**
- Modify: `application/service/account/AccountStatisticsService.java`

**Interfaces:**
- Consumes: `OrderPort.findFilledByAccount(UUID, LocalDate, LocalDate): List<Order>` (Task 1)
- Produces: `DailyTransactionResult` — 기존 반환 타입 유지, 브로커 미호출

---

- [ ] **Step 1: import 추가 및 `OrderPort` 필드 주입**

`AccountStatisticsService.java` 상단 import 블록:

```java
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.out.OrderPort;
```

클래스 필드에 추가 (`@RequiredArgsConstructor`가 생성자 주입 처리):

```java
private final OrderPort orderPort;
```

- [ ] **Step 2: `getDailyTransactions` 교체**

기존 메서드 전체를 아래로 교체:

```java
@Override
public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                    LocalDate from, LocalDate to) {
    accountPort.requireOwnedAccount(accountId, requesterId);
    List<Order> filled = orderPort.findFilledByAccount(accountId, from, to);

    List<DailyTransaction> items = filled.stream()
            .filter(o -> o.filledQuantity() != null && o.filledQuantity() > 0)
            .map(o -> {
                BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                int qty = o.filledQuantity();
                BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
                return new DailyTransaction(
                        o.tradeDate().toString(), // KST ISO date (toDomain이 이미 변환)
                        null,                     // settlementDate — DB 미저장
                        o.direction(),
                        o.ticker(),
                        o.ticker().name(),        // symbolName — DB 미저장, ticker code 대체
                        qty,
                        price,
                        amount,
                        null,                     // settlementAmountKrw — DB 미저장
                        null,                     // exchangeRate — DB 미저장
                        "USD"
                );
            })
            .toList();

    BigDecimal buyTotal = items.stream()
            .filter(t -> t.direction() == Order.OrderDirection.BUY)
            .map(DailyTransaction::tradeAmountUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sellTotal = items.stream()
            .filter(t -> t.direction() == Order.OrderDirection.SELL)
            .map(DailyTransaction::tradeAmountUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new DailyTransactionResult(items,
            new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, BigDecimal.ZERO));
}
```

- [ ] **Step 3: 고아 import 정리**

`BrokerStatisticsRouter` 관련 import가 `getDailyTransactions`에서만 사용되지 않는지 확인 후, 여전히 `getPresentBalance`/`getMargin`/`getSellableQuantity`에서 사용되므로 **유지**. 단, `Instant`·`ZoneOffset`·`TimeZones` 등 나머지 import는 다른 메서드가 사용하므로 건드리지 않음.

- [ ] **Step 4: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git -C /mnt/c/Users/USER/workspace/kista/kista-api add \
  src/main/java/com/kista/application/service/account/AccountStatisticsService.java
git -C /mnt/c/Users/USER/workspace/kista/kista-api commit -m "$(cat <<'EOF'
feat(daily-trades): 브로커 API 대신 DB orders 테이블 조회로 전환

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 브로커 dead code 제거

변경으로 고아가 된 `DailyTradePort`, `KisDailyTransactionPort` 관련 코드를 전부 제거한다.

**Files:**
- Modify: `application/service/account/BrokerStatisticsRouter.java`
- Modify: `adapter/out/kis/KisBrokerAdapter.java`
- Modify: `adapter/out/kis/KisTradingApi.java`
- Modify: `adapter/out/toss/TossBrokerAdapter.java`
- Delete: `domain/port/out/broker/DailyTradePort.java`
- Delete: `domain/port/out/KisDailyTransactionPort.java`

---

- [ ] **Step 1: `BrokerStatisticsRouter` — `getDailyTransactions` 제거**

아래 내용을 삭제:
- `getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to)` 메서드 전체
- import `com.kista.domain.port.out.broker.DailyTradePort`
- import `com.kista.domain.model.kis.DailyTransactionResult`
- import `java.util.UUID` (다른 메서드에서 미사용 시)

> `LocalDate` import는 다른 메서드 파라미터에서 사용되므로 확인 후 유지.

- [ ] **Step 2: `KisBrokerAdapter` 정리**

아래 내용을 삭제:
- `implements` 절에서 `DailyTradePort` 제거
- `private final KisDailyTransactionPort kisDailyTransactionPort;` 필드 제거
- `getDailyTransactions(LocalDate from, LocalDate to, Account account)` 메서드 전체 제거
- import `com.kista.domain.model.kis.DailyTransactionResult` (다른 메서드에서 미사용 시)
- import `java.time.LocalDate` (다른 메서드 파라미터에서 사용 — `getExecutions`에 있으므로 **유지**)

- [ ] **Step 3: `KisTradingApi` — `getDailyTransactions` 제거**

`KisTradingApi`는 `KisDailyTransactionPort` 구현체. 아래 내용을 삭제:
- `implements` 절에서 `KisDailyTransactionPort` 제거
- `// ── KisDailyTransactionPort` 섹션 전체 (`getDailyTransactions` 메서드 포함)
- `private static DailyTransactionSummary emptySummary()` (getDailyTransactions에서만 사용한다면 제거)
- 관련 import 정리: `DailyTransaction`, `DailyTransactionResult`, `DailyTransactionSummary`, `Order`, `RoundingMode`, `TradeDateConverter`

> **주의:** `KisTradingApi`에 `emptySummary()`가 다른 메서드에서도 사용 중인지 확인 후 제거.

- [ ] **Step 4: `TossBrokerAdapter` 정리**

아래 내용을 삭제:
- `implements` 절에서 `DailyTradePort` 제거
- `private final StrategyPort strategyPort;` 필드 제거 (`getDailyTransactions`에서만 사용)
- `private final TossCommissionsPort tossCommissionsPort;` 필드 제거 (`getDailyTransactions`에서만 사용)
- `getDailyTransactions(LocalDate from, LocalDate to, Account account)` 메서드 전체 제거
- `private static DailyTransactionSummary emptySummary()` 제거 (`getDailyTransactions`에서만 사용)
- import `com.kista.domain.model.kis.DailyTransaction` 제거
- import `com.kista.domain.model.kis.DailyTransactionResult` 제거
- import `com.kista.domain.model.kis.DailyTransactionSummary` 제거
- import `com.kista.domain.port.out.StrategyPort` 제거
- import `com.kista.domain.port.out.TossCommissionsPort` 제거
- import `com.kista.domain.model.toss.TossCommissionRate` 제거
- import `java.math.RoundingMode` 제거 (다른 메서드에서 미사용 시)

> `Order.OrderDirection` import는 `getExecutions` 등에서 계속 필요할 수 있으므로 확인.

- [ ] **Step 5: 인터페이스 파일 삭제**

```bash
rm /mnt/c/Users/USER/workspace/kista/kista-api/src/main/java/com/kista/domain/port/out/broker/DailyTradePort.java
rm /mnt/c/Users/USER/workspace/kista/kista-api/src/main/java/com/kista/domain/port/out/KisDailyTransactionPort.java
```

- [ ] **Step 6: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 잔존 참조 확인**

```bash
grep -rn "DailyTradePort\|KisDailyTransactionPort" \
  /mnt/c/Users/USER/workspace/kista/kista-api/src/main/java --include="*.java"
```

Expected: 출력 없음

- [ ] **Step 8: 커밋**

```bash
git -C /mnt/c/Users/USER/workspace/kista/kista-api add -u
git -C /mnt/c/Users/USER/workspace/kista/kista-api commit -m "$(cat <<'EOF'
refactor: DailyTradePort 브로커 어댑터 구현 제거 (DB 전환 후 dead code)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## 최종 검증

- [ ] 전체 빌드: `cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew build`
- [ ] kista-api 서버 기동 후 `GET /api/accounts/{id}/daily-trades?from=2026-06-23&to=2026-06-29` 호출 → DB 주문 기반 응답 확인
- [ ] 대시보드 캘린더에서 거래 날짜/방향/금액이 DB 주문 데이터와 일치하는지 확인
