# 주문 취소 API 설계

**날짜**: 2026-06-02  
**배경**: `POST /api/trading-cycles/{id}/execute` 수동 실행의 역작업으로, PLACED 주문을 KIS에 취소 접수하는 API 추가.

---

## 개요

수동 실행으로 PLACED된 주문을 취소하는 두 가지 엔드포인트를 제공한다.

| 엔드포인트 | 설명 |
|---|---|
| `DELETE /api/trading-cycles/{id}/execute` | 오늘 PLACED된 사이클 주문 전체 취소 |
| `DELETE /api/orders/{orderId}` | 특정 주문 1건 취소 |

---

## KIS API

- **Path**: `POST /uapi/overseas-stock/v1/trading/order-rvsecncl`
- **TR ID**: `TTTT1004U` (실전)
- **취소 시 고정 파라미터**: `RVSE_CNCL_DVSN_CD=02`, `OVRS_ORD_UNPR=0`
- **필수 파라미터**: `ORGN_ODNO` (기존 `kisOrderId`), `PDNO` (ticker), `OVRS_EXCG_CD`, `ORD_QTY`
- **응답**: `ODNO` (취소 주문번호), `KRX_FWDG_ORD_ORGNO`, `ORD_TMD`

---

## 도메인 변경

### Order.OrderStatus — CANCELLED 추가

```java
public enum OrderStatus {
    PLANNED, PLACED, FILLED, FAILED, CANCELLED
}
```

`orders.status` 컬럼은 `VARCHAR(10)`이므로 Flyway 마이그레이션 불필요.

### CancelOrderUseCase (domain/port/in)

```java
public interface CancelOrderUseCase {
    CancelResult cancelByCycle(UUID cycleId, UUID requesterId);  // 사이클 전체
    void cancelOrder(UUID orderId, UUID requesterId);             // 개별 1건
}

record CancelResult(int cancelledCount, int failedCount) {}
```

### KisOrderPort — cancel 추가 (domain/port/out)

```java
void cancel(Order order, Account account);
```

---

## 서비스: OrderCancelService (application/service)

`CancelOrderUseCase` 구현체.

### cancelByCycle 흐름

1. `cycleRepository.findByIdOrThrow(cycleId)` → `accountRepository.findByIdOrThrow(cycle.accountId())` → `account.verifyOwnedBy(requesterId)` (403)
2. `orderPort.findPlacedByAccountAndDate(accountId, LocalDate.now())` — 오늘 PLACED 주문 조회
3. 주문 없으면 `CancelResult(0, 0)` 즉시 반환
4. 각 주문 개별 try-catch:
   - `kisOrderPort.cancel(order, account)` 호출
   - 성공 → `orderPort.markCancelled(order.id())`
   - 실패 → `log.warn` + failedCount 증가 (기존 상태 유지)
5. `CancelResult(cancelledCount, failedCount)` 반환

### cancelOrder 흐름

1. `orderPort.findById(orderId)` — 없으면 `NoSuchElementException` (404)
2. `accountRepository.findByIdOrThrow(order.accountId())` → `account.verifyOwnedBy(requesterId)` (403)
3. `order.status() != PLACED` → `IllegalStateException` (409)
4. `kisOrderPort.cancel(order, account)` — 실패 시 예외 전파 (503)
5. `orderPort.markCancelled(orderId)` (204)

---

## KIS 어댑터: KisOrderAdapter — cancel 추가 (adapter/out/kis)

```java
@Override
public void cancel(Order order, Account account) {
    HttpHeaders headers = kisHttpClient.buildHeaders("TTTT1004U", account);
    Map<String, String> body = new LinkedHashMap<>();
    body.put("CANO", account.accountNo());
    body.put("ACNT_PRDT_CD", account.kisAccountType());
    body.put("OVRS_EXCG_CD", order.ticker().getExchangeCode().name());
    body.put("PDNO", order.ticker().name());
    body.put("ORGN_ODNO", order.kisOrderId());
    body.put("RVSE_CNCL_DVSN_CD", "02");          // 취소
    body.put("ORD_QTY", String.valueOf(order.quantity()));
    body.put("OVRS_ORD_UNPR", "0");               // 취소 시 0 고정
    body.put("MGCO_APTM_ODNO", "");
    body.put("ORD_SVR_DVSN_CD", "0");
    kisHttpClient.post(PATH_CANCEL, headers, body, CancelResponse.class);
}
```

`PATH_CANCEL = "/uapi/overseas-stock/v1/trading/order-rvsecncl"`

---

## 영속성: OrderPersistenceAdapter — markCancelled 추가

`OrderPort`에 `markCancelled(UUID orderId)` 추가.  
구현은 `markPlaced`와 동일 패턴 — `setStatus(CANCELLED)` + `save`.

`OrderJpaRepository`에 `findById(UUID)` — 이미 `JpaRepository` 기본 제공.  
`OrderPort`에 `findById(UUID) → Optional<Order>` 추가.

---

## 컨트롤러

### TradingCycleController — DELETE /api/trading-cycles/{id}/execute

```java
@DeleteMapping("/api/trading-cycles/{id}/execute")
public ResponseEntity<CancelOrdersResponse> cancelExecution(
        @PathVariable UUID id,
        @AuthenticationPrincipal UUID userId) {
    // SecurityException → 403, NoSuchElementException → 404
}
```

응답: `200 OK` + `CancelOrdersResponse { cancelledCount, failedCount }`

### OrderController — DELETE /api/orders/{orderId}

```java
@DeleteMapping("/api/orders/{orderId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void cancelOrder(
        @PathVariable UUID orderId,
        @AuthenticationPrincipal UUID userId) {
    // SecurityException → 403, NoSuchElementException → 404, IllegalStateException → 409
}
```

응답: `204 No Content`

---

## 응답 DTO

```java
// adapter/in/web/dto/CancelOrdersResponse.java
public record CancelOrdersResponse(int cancelledCount, int failedCount) {
    public static CancelOrdersResponse from(CancelResult result) {
        return new CancelOrdersResponse(result.cancelledCount(), result.failedCount());
    }
}
```

---

## 동시 수정 필요 파일

| 파일 A | 파일 B |
|---|---|
| `Order.OrderStatus` | `OrderEntity.status` 컬럼 (VARCHAR — 마이그레이션 불필요) |
| `KisOrderPort.cancel` | `KisOrderAdapter.cancel` |
| `OrderPort.markCancelled` / `findById` | `OrderPersistenceAdapter` |
| `CancelOrderUseCase` | `OrderCancelService` |
| `TradingCycleController` 새 엔드포인트 | `TradingCycleControllerTest` `@MockBean CancelOrderUseCase` 추가 |
| `OrderController` 새 엔드포인트 | `OrderControllerTest` `@MockBean CancelOrderUseCase` 추가 |

---

## 검증 계획

1. `bash gradlew compileJava` — 컴파일 오류 없음
2. `bash gradlew test` — 기존 테스트 전체 통과
3. 로컬 서버 기동 후 Swagger에서:
   - 수동 실행(`POST /api/trading-cycles/{id}/execute`) → 202
   - 사이클 취소(`DELETE /api/trading-cycles/{id}/execute`) → 200 `{cancelledCount, failedCount}`
   - 개별 취소(`DELETE /api/orders/{orderId}`) → 204
   - 이미 취소된 주문 재취소 → 409
