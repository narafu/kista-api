# Admin Order Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add admin-only order correction flows for `PLANNED`, `PLACED`, and `FILLED` strategy orders, including broker cancel/reorder for `PLACED`.

**Architecture:** Extend the admin web layer with order lookup and correction endpoints, add a dedicated order-correction use case/service, and split correction behavior by order status. Reuse existing `OrderPort`, `CyclePositionPort`, and manual-fill correction patterns for DB consistency, while introducing a broker-facing correction port for `PLACED` cancel/reorder so external broker state and internal DB state stay aligned.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5, Mockito, Spring WebMvcTest

## Global Constraints

- Keep KST trade-date input semantics unchanged.
- Preserve historical order rows; only `PLANNED` supports in-place mutation.
- `PLACED_REPLACE` must not mutate DB before broker cancel succeeds.
- `FILLED_CORRECTION` must preserve the original filled order and add compensating fills instead of overwriting history.
- All admin correction actions must be under `/api/admin/**` and require admin auth.
- All correction actions must write audit logs.
- Use TDD: add failing tests before production changes for each task.

---

### Task 1: Admin Order Lookup API

**Files:**
- Modify: `src/main/java/com/kista/domain/port/in/AdminQueryUseCase.java`
- Modify: `src/main/java/com/kista/application/service/admin/AdminQueryService.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AdminTradeController.java`
- Modify: `src/main/java/com/kista/domain/port/out/OrderPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderJpaRepository.java`
- Test: `src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java`
- Test: `src/test/java/com/kista/application/service/admin/AdminQueryServiceTest.java`

**Interfaces:**
- Consumes: `List<Order> AdminQueryUseCase.listTrades(LocalDate from, LocalDate to)`, `List<Account> AdminQueryUseCase.listAccounts(LocalDate from, LocalDate to)`
- Produces: `List<Order> AdminQueryUseCase.listStrategyOrders(UUID strategyId, LocalDate tradeDate)` and `GET /api/admin/accounts/{accountId}/strategies/{strategyId}/orders?tradeDate=YYYY-MM-DD`

- [ ] **Step 1: Write the failing query-service test**

```java
@Test
void listStrategyOrders_filtersByStrategyAndTradeDate() {
    UUID strategyId = UUID.randomUUID();
    LocalDate tradeDate = LocalDate.of(2026, 7, 1);
    when(orderPort.findByStrategyId(strategyId, tradeDate, tradeDate)).thenReturn(List.of());

    service.listStrategyOrders(strategyId, tradeDate);

    verify(orderPort).findByStrategyId(strategyId, tradeDate, tradeDate);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminQueryServiceTest'`
Expected: FAIL because `listStrategyOrders` does not exist yet.

- [ ] **Step 3: Write the failing controller test**

```java
@Test
void listStrategyOrders_adminRole_returns200() throws Exception {
    UUID accountId = UUID.randomUUID();
    UUID strategyId = UUID.randomUUID();
    when(adminQuery.listStrategyOrders(strategyId, LocalDate.of(2026, 7, 1))).thenReturn(List.of());

    mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies/{strategyId}/orders", accountId, strategyId)
                    .param("tradeDate", "2026-07-01")
                    .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.AdminTradeControllerTest'`
Expected: FAIL because the endpoint does not exist.

- [ ] **Step 5: Implement the minimal query API**

```java
// AdminQueryUseCase
List<Order> listStrategyOrders(UUID strategyId, LocalDate tradeDate);

// AdminQueryService
@Override
public List<Order> listStrategyOrders(UUID strategyId, LocalDate tradeDate) {
    return orderPort.findByStrategyId(strategyId, tradeDate, tradeDate);
}
```

- [ ] **Step 6: Implement the controller endpoint**

```java
@GetMapping("/accounts/{accountId}/strategies/{strategyId}/orders")
public List<AdminStrategyOrderResponse> listStrategyOrders(
        @PathVariable UUID accountId,
        @PathVariable UUID strategyId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate) {
    return adminQuery.listStrategyOrders(strategyId, tradeDate).stream()
            .map(AdminStrategyOrderResponse::from)
            .toList();
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminQueryServiceTest' --tests 'com.kista.adapter.in.web.AdminTradeControllerTest'`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/kista/domain/port/in/AdminQueryUseCase.java \
        src/main/java/com/kista/application/service/admin/AdminQueryService.java \
        src/main/java/com/kista/adapter/in/web/AdminTradeController.java \
        src/test/java/com/kista/application/service/admin/AdminQueryServiceTest.java \
        src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java
git commit -m "feat(admin): add strategy order lookup endpoint"
```

### Task 2: Planned Edit and Filled Correction Service

**Files:**
- Create: `src/main/java/com/kista/domain/model/admin/AdminOrderCorrectionCommand.java`
- Create: `src/main/java/com/kista/domain/model/admin/AdminOrderCorrectionResult.java`
- Create: `src/main/java/com/kista/domain/port/in/AdminOrderCorrectionUseCase.java`
- Create: `src/main/java/com/kista/application/service/admin/AdminOrderCorrectionService.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AdminTradeController.java`
- Modify: `src/main/java/com/kista/domain/port/out/OrderPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/trade/OrderPersistenceAdapter.java`
- Test: `src/test/java/com/kista/application/service/admin/AdminOrderCorrectionServiceTest.java`
- Test: `src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java`

**Interfaces:**
- Consumes: `Optional<Order> OrderPort.findById(UUID orderId)`, `Strategy StrategyPort.findByIdOrThrow(UUID strategyId)`, `List<CyclePosition> CyclePositionPort.findLatestByCycleId(UUID cycleId, int limit)`
- Produces: `AdminOrderCorrectionResult correctOrder(UUID adminId, AdminOrderCorrectionCommand command)` for modes `PLANNED_EDIT` and `FILLED_CORRECTION`

- [ ] **Step 1: Write the failing service test for planned edit**

```java
@Test
void correctOrder_plannedEdit_updatesPriceAndQuantity() {
    when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(plannedOrder()));

    service.correctOrder(ADMIN_ID, plannedEditCommand());

    verify(orderPort).updatePlannedOrder(ORDER_ID, new BigDecimal("250.00"), 3);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminOrderCorrectionServiceTest'`
Expected: FAIL because correction types and service do not exist.

- [ ] **Step 3: Write the failing service test for filled correction**

```java
@Test
void correctOrder_filledCorrection_addsCompensatingFillAndUpdatesCyclePosition() {
    when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(filledOrder()));
    when(cyclePositionPort.findLatestByCycleId(CYCLE_ID, 1)).thenReturn(List.of(latestPosition()));

    service.correctOrder(ADMIN_ID, filledCorrectionCommand());

    verify(orderPort).saveAll(any());
    verify(cyclePositionPort).save(argThat(p -> p.holdings() == 0));
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminOrderCorrectionServiceTest'`
Expected: FAIL because filled correction logic is missing.

- [ ] **Step 5: Write the failing controller test**

```java
@Test
void correctOrder_adminRole_returns200() throws Exception {
    when(adminOrderCorrection.correctOrder(eq(ADMIN_UUID), any())).thenReturn(sampleResult());

    mockMvc.perform(post("/api/admin/trades/order-corrections")
                    .with(csrf())
                    .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN")))
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("PLANNED_EDIT"));
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.adapter.in.web.AdminTradeControllerTest'`
Expected: FAIL because the endpoint does not exist.

- [ ] **Step 7: Implement minimal command, result, and service**

```java
public enum Mode { PLANNED_EDIT, PLACED_REPLACE, FILLED_CORRECTION }

public record AdminOrderCorrectionCommand(
        UUID userId, UUID accountId, UUID strategyId, UUID orderId,
        Mode mode, LocalDate tradeDateKst, BigDecimal price, int quantity, String memo) {}
```

```java
if (command.mode() == Mode.PLANNED_EDIT) {
    orderPort.updatePlannedOrder(command.orderId(), command.price(), command.quantity());
}
if (command.mode() == Mode.FILLED_CORRECTION) {
    orderPort.saveAll(List.of(compensatingFill));
    cyclePositionPort.save(CyclePosition.tradeSnapshot(cycleId, balanceAfterCorrection, command.price()));
}
```

- [ ] **Step 8: Implement the controller endpoint**

```java
@PostMapping("/order-corrections")
public AdminOrderCorrectionResponse correctOrder(
        @AuthenticationPrincipal UUID adminId,
        @RequestBody @Valid AdminOrderCorrectionRequest request) {
    return AdminOrderCorrectionResponse.from(
            adminOrderCorrection.correctOrder(adminId, request.toCommand()));
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminOrderCorrectionServiceTest' --tests 'com.kista.adapter.in.web.AdminTradeControllerTest'`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/kista/domain/model/admin/AdminOrderCorrectionCommand.java \
        src/main/java/com/kista/domain/model/admin/AdminOrderCorrectionResult.java \
        src/main/java/com/kista/domain/port/in/AdminOrderCorrectionUseCase.java \
        src/main/java/com/kista/application/service/admin/AdminOrderCorrectionService.java \
        src/main/java/com/kista/adapter/in/web/AdminTradeController.java \
        src/test/java/com/kista/application/service/admin/AdminOrderCorrectionServiceTest.java \
        src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java
git commit -m "feat(admin): add planned and filled order correction"
```

### Task 3: Broker Cancel and Replace for Placed Orders

**Files:**
- Create: `src/main/java/com/kista/domain/port/out/broker/BrokerOrderCorrectionPort.java`
- Modify: `src/main/java/com/kista/application/service/broker/BrokerAdapterRegistry.java`
- Modify: `src/main/java/com/kista/adapter/out/kis/KisBrokerAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossBrokerAdapter.java`
- Modify: `src/main/java/com/kista/application/service/admin/AdminOrderCorrectionService.java`
- Test: `src/test/java/com/kista/application/service/admin/AdminOrderCorrectionServiceTest.java`

**Interfaces:**
- Consumes: `Account AccountPort.findByIdOrThrow(UUID accountId)`, `Order OrderPort.findById(UUID orderId)`, `BrokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class)`
- Produces: `CancelResult cancelPlacedOrder(Account account, Order order)` and `Order placeReplacementOrder(Account account, Order replacement)`

- [ ] **Step 1: Write the failing placed-replace service test**

```java
@Test
void correctOrder_placedReplace_cancelsAtBrokerAndCreatesReplacement() {
    when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(placedOrder()));
    when(registry.require(account, BrokerOrderCorrectionPort.class)).thenReturn(correctionPort);
    when(correctionPort.cancelPlacedOrder(account, placedOrder())).thenReturn(true);
    when(correctionPort.placeReplacementOrder(account, any())).thenReturn("NEW-ORDER-ID");

    service.correctOrder(ADMIN_ID, placedReplaceCommand());

    verify(orderPort).markCancelled(ORDER_ID);
    verify(orderPort).saveAll(any());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminOrderCorrectionServiceTest'`
Expected: FAIL because broker correction port does not exist.

- [ ] **Step 3: Write the failing rollback test**

```java
@Test
void correctOrder_placedReplace_cancelFailure_keepsDbUntouched() {
    when(orderPort.findById(ORDER_ID)).thenReturn(Optional.of(placedOrder()));
    when(correctionPort.cancelPlacedOrder(account, placedOrder())).thenReturn(false);

    assertThatThrownBy(() -> service.correctOrder(ADMIN_ID, placedReplaceCommand()))
            .isInstanceOf(IllegalStateException.class);

    verify(orderPort, never()).markCancelled(any());
    verify(orderPort, never()).saveAll(any());
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminOrderCorrectionServiceTest'`
Expected: FAIL because placed replace failure handling is missing.

- [ ] **Step 5: Implement the broker correction port**

```java
public interface BrokerOrderCorrectionPort {
    boolean cancelPlacedOrder(Account account, Order order);
    String placeReplacementOrder(Account account, Order replacementOrder);
}
```

- [ ] **Step 6: Implement service integration**

```java
BrokerOrderCorrectionPort port = registry.require(account, BrokerOrderCorrectionPort.class);
boolean cancelled = port.cancelPlacedOrder(account, existingOrder);
if (!cancelled) throw new IllegalStateException("브로커 주문 취소 실패");
orderPort.markCancelled(existingOrder.id());
String externalOrderId = port.placeReplacementOrder(account, replacementOrder);
orderPort.saveAll(List.of(replacementOrder.withPlaced(externalOrderId)));
```

- [ ] **Step 7: Implement KIS/TOSS adapters with minimal delegation**

```java
@Override
public boolean cancelPlacedOrder(Account account, Order order) {
    cancelApi.cancel(account, order.externalOrderId());
    return true;
}
```

```java
@Override
public String placeReplacementOrder(Account account, Order replacementOrder) {
    return orderApi.place(account, replacementOrder).externalOrderId();
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.kista.application.service.admin.AdminOrderCorrectionServiceTest'`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/kista/domain/port/out/broker/BrokerOrderCorrectionPort.java \
        src/main/java/com/kista/application/service/broker/BrokerAdapterRegistry.java \
        src/main/java/com/kista/adapter/out/kis/KisBrokerAdapter.java \
        src/main/java/com/kista/adapter/out/toss/TossBrokerAdapter.java \
        src/main/java/com/kista/application/service/admin/AdminOrderCorrectionService.java \
        src/test/java/com/kista/application/service/admin/AdminOrderCorrectionServiceTest.java
git commit -m "feat(admin): add placed order replace flow"
```

## Self-Review

- Spec coverage:
  - 주문 조회 API: Task 1
  - `PLANNED_EDIT`: Task 2
  - `FILLED_CORRECTION`: Task 2
  - `PLACED_REPLACE`: Task 3
  - 브로커 취소/재주문: Task 3
  - 감사로그: Tasks 2 and 3
- Placeholder scan:
  - No `TBD`, `TODO`, or implicit “write tests later” steps remain.
- Type consistency:
  - `AdminOrderCorrectionCommand.Mode` is used consistently across Task 2 and Task 3.
  - `listStrategyOrders(UUID strategyId, LocalDate tradeDate)` is introduced in Task 1 and reused unchanged afterward.
