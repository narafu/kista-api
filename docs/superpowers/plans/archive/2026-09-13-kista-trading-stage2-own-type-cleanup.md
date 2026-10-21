# kista-trading 2단계 own-type 뒤처리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin/stats(root `:api`)가 여전히 `trading.domain.model.Order`/`Strategy`/`matching.domain.model.OrderTiming`을 직접 import하는 2단계 잔재를 own-type/sharedkernel 전환으로 정리한다.

**Architecture:** `Order.OrderStatus`와 `matching.domain.model.OrderTiming`을 `com.kista.sharedkernel`로 승격(값 자체는 무변경, admin·trading 양쪽이 공유하는 outbound-zero 값이라 `OrderDirection`/`OrderType`과 동일 패턴)한 뒤, admin 전용 read model(`AdminOrderView`/`AdminStrategyView`)을 신설해 조회 경로의 반환 타입을 전면 교체한다. `HousingBenchmarkComparison.StrategyInfo`(nested)는 최상위 `StrategyRef`로 승격해 `InvestmentPointsPort`와 공유하고, 중복된 `InvestmentPointsPort.Scope`는 삭제하고 `BenchmarkScope`로 통합한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Modulith, Gradle 멀티프로젝트(`:trading-core` + root `:api`), Jackson(HTTP 내부 API 직렬화).

**Spec:** `docs/superpowers/specs/2026-09-13-kista-trading-stage2-own-type-cleanup-design.md`

## Global Constraints

- DB `@Enumerated(STRING)` 컬럼 상수명은 `OrderStatus` 이동 전후 byte-identical 유지(`PLANNED`/`PLACED`/`FILLED`/`PARTIALLY_FILLED`/`FAILED`/`CANCELLED`).
- 각 태스크 완료 시 `bash gradlew test`(root) 전체 그린 — 대상 파일이 `trading-core`에만 있는 태스크는 `bash gradlew :trading-core:test`로도 충분하나, 마지막엔 반드시 `bash gradlew test`(루트, 양쪽 서브프로젝트 전체)로 재확인한다.
- `Order`/`Strategy`(trading-core 소유 원본)는 이 계획에서 필드를 추가·삭제·이름 변경하지 않는다 — 오직 `OrderStatus`/`OrderTiming` 필드의 "타입이 가리키는 패키지"만 바뀐다.
- Task 2/3(sharedkernel 승격)을 Task 4(admin own-type read model)보다 먼저 완료한다 — `AdminOrderView`/`AdminStrategyView`의 status/timing 필드 타입이 `sharedkernel` 값이어야 하므로 순서 의존이 있다.
- 이 계획이 다루는 범위는 전부 조회 경로다 — 브로커 호출, `@Transactional` 트랜잭션 경계 문제, 실주문 유발 로직은 없다.
- 커밋 메시지는 한글, Conventional Commit 접두사 + 명령형 제목, 끝에 다음 두 줄을 붙인다(이 세션의 attribution):
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01ENGX1WSPiJUESEMcaj4gN6
  ```

---

### Task 1: 죽은 import 8개 정리

**Files:**
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/StrategyStatusRequest.java`
- Modify: `src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java`
- Modify: `src/main/java/com/kista/admin/domain/model/AdminReorderCommand.java`
- Modify: `src/main/java/com/kista/admin/domain/model/AdminManualTradeCorrectionCommand.java`
- Modify: `src/main/java/com/kista/admin/domain/model/AdminTradeCorrectionResult.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminReorderRequest.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminManualTradeCorrectionRequest.java`
- Modify: `src/main/java/com/kista/stats/domain/model/HousingBenchmarkComparison.java`

**Interfaces:**
- Consumes: 없음(순수 삭제, 다른 태스크에 영향 없음)
- Produces: 없음

이 8개 파일은 `import com.kista.trading.domain.model.Order;` 또는 `import com.kista.trading.domain.model.Strategy;`를 갖고 있지만 파일 안 어디에서도 그 타입을 사용하지 않는다(Task 7/8 리팩토링 잔재). `AdminReorderCommand.java`/`AdminReorderRequest.java`는 `Order` import만 죽었을 뿐 같은 파일의 `import com.kista.matching.domain.model.OrderTiming;`은 실사용이므로 **그 줄은 건드리지 않는다**(Task 3에서 처리).

- [ ] **Step 1: 각 파일에서 미사용 import 한 줄만 삭제**

`StrategyStatusRequest.java` (전체 4줄 남음):
```java
package com.kista.admin.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.kista.sharedkernel.StrategyStatus;

// 관리자 전략 상태 변경 요청 DTO — ACTIVE(재개) / PAUSED(일시정지)
public record StrategyStatusRequest(
        @Schema(description = "변경할 전략 상태 — ACTIVE(재개) / PAUSED(일시정지)", example = "PAUSED")
        StrategyStatus status) {}
```
(`import com.kista.trading.domain.model.Strategy;` 삭제)

`RuntimeSettingsService.java`: 5번째 줄 `import com.kista.trading.domain.model.Strategy;` 삭제. 나머지 파일 내용은 무변경.

`AdminReorderCommand.java`: 3번째 줄 `import com.kista.trading.domain.model.Order;` 삭제. `import com.kista.matching.domain.model.OrderTiming;`(4번째 줄)은 유지.

`AdminManualTradeCorrectionCommand.java`: 3번째 줄 `import com.kista.trading.domain.model.Order;` 삭제.

`AdminTradeCorrectionResult.java`: 3번째 줄 `import com.kista.trading.domain.model.Strategy;` 삭제.

`AdminReorderRequest.java`: 3번째 줄 `import com.kista.trading.domain.model.Order;` 삭제. `import com.kista.matching.domain.model.OrderTiming;`(4번째 줄)은 유지.

`AdminManualTradeCorrectionRequest.java`: 3번째 줄 `import com.kista.trading.domain.model.Order;` 삭제.

`HousingBenchmarkComparison.java`: 3번째 줄 `import com.kista.trading.domain.model.Strategy;` 삭제(파일은 이미 `StrategyInfo` own-type record만 사용 — `Strategy` 타입 자체는 어디서도 쓰이지 않는다).

- [ ] **Step 2: 컴파일 확인**

Run: `bash gradlew compileJava :trading-core:compileJava`
Expected: BUILD SUCCESSFUL (미사용 import 삭제는 컴파일에 영향 없음 — 실패하면 삭제한 import가 실제로는 쓰이고 있었다는 뜻이니 grep으로 재확인)

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/admin/adapter/in/web/dto/StrategyStatusRequest.java \
        src/main/java/com/kista/admin/application/service/RuntimeSettingsService.java \
        src/main/java/com/kista/admin/domain/model/AdminReorderCommand.java \
        src/main/java/com/kista/admin/domain/model/AdminManualTradeCorrectionCommand.java \
        src/main/java/com/kista/admin/domain/model/AdminTradeCorrectionResult.java \
        src/main/java/com/kista/admin/adapter/in/web/dto/AdminReorderRequest.java \
        src/main/java/com/kista/admin/adapter/in/web/dto/AdminManualTradeCorrectionRequest.java \
        src/main/java/com/kista/stats/domain/model/HousingBenchmarkComparison.java
git commit -m "refactor(admin,stats): Task7/8 리팩토링 잔재 죽은 import 8건 정리"
```

---

### Task 2: `sharedkernel.OrderStatus` 승격

**Files:**
- Create: `trading-core/src/main/java/com/kista/sharedkernel/OrderStatus.java`
- Modify: `trading-core/src/main/java/com/kista/trading/domain/model/Order.java`
- Modify (sed 일괄 치환, 아래 "대상 파일 목록" 26개): `Order\.OrderStatus` → `OrderStatus` + `import com.kista.sharedkernel.OrderStatus;` 추가

**Interfaces:**
- Consumes: 없음
- Produces: `com.kista.sharedkernel.OrderStatus`(PLANNED/PLACED/FILLED/PARTIALLY_FILLED/FAILED/CANCELLED) — Task 4가 `AdminOrderView.status` 필드 타입으로 사용

- [ ] **Step 1: sharedkernel에 OrderStatus 생성**

`trading-core/src/main/java/com/kista/sharedkernel/OrderStatus.java`:
```java
package com.kista.sharedkernel;

// 주문 상태 — admin·trading 양쪽이 공유하는 순수 값(outbound-zero), OrderDirection/OrderType과 동일 패턴으로 승격
public enum OrderStatus {
    PLANNED,           // DB 저장, 증권사 접수 대기
    PLACED,            // 증권사 접수 완료
    FILLED,            // 전량 체결
    PARTIALLY_FILLED,  // 부분 체결 (filledQuantity < quantity)
    FAILED,            // 증권사 접수 실패
    CANCELLED          // 사용자 취소 또는 미체결로 취소 처리 완료
}
```

- [ ] **Step 2: `Order.java`에서 nested enum 제거하고 import 추가**

`trading-core/src/main/java/com/kista/trading/domain/model/Order.java`의 1-7번째 줄을 다음으로 교체:
```java
package com.kista.trading.domain.model;

import com.kista.sharedkernel.OrderDirection;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderType;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.StrategyTicker;
```
(`import com.kista.sharedkernel.OrderStatus;` 한 줄 추가, 나머지 6줄은 원래 순서 그대로)

44-51번째 줄의 nested enum 블록을 통째로 삭제:
```java
    public enum OrderStatus {
        PLANNED,           // DB 저장, 증권사 접수 대기
        PLACED,            // 증권사 접수 완료
        FILLED,            // 전량 체결
        PARTIALLY_FILLED,  // 부분 체결 (filledQuantity < quantity)
        FAILED,            // 증권사 접수 실패
        CANCELLED          // 사용자 취소 또는 미체결로 취소 처리 완료
    }

```
(이 블록만 삭제 — 앞뒤 나머지 코드는 무변경. 파일 안 `OrderStatus.PLANNED`/`OrderStatus.PLACED`/`OrderStatus.FILLED`/`OrderStatus.FAILED` 등 8곳의 호출부는 이름이 그대로 `OrderStatus.X`이므로 코드 변경 불필요 — import만으로 해소된다)

- [ ] **Step 3: 대상 파일 26개에서 `Order.OrderStatus` → `OrderStatus` 일괄 치환 + import 추가**

아래 26개 파일 전부가 대상이다(메인 10 + 테스트 16). 각 파일에서 정규식 치환 `Order\.OrderStatus` → `OrderStatus`를 적용하고, 파일에 `import com.kista.sharedkernel.OrderStatus;`가 없으면 package 선언 다음 줄에 추가한다. 기존 `import com.kista.trading.domain.model.Order;`는 `Order` 타입 자체(생성자·필드 접근)를 계속 쓰는 파일이 많으므로 **삭제하지 않는다**.

메인(10개):
- `src/main/java/com/kista/admin/domain/model/AdminReorderResult.java`
- `trading-core/src/main/java/com/kista/trading/adapter/in/web/dto/NextOrdersResponse.java`
- `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderEntity.java`
- `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderJpaRepository.java`
- `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapter.java`
- `trading-core/src/main/java/com/kista/trading/application/port/output/OrderPort.java`
- `trading-core/src/main/java/com/kista/trading/application/service/OrderCancelService.java`
- `trading-core/src/main/java/com/kista/trading/application/service/ReorderService.java`
- `trading-core/src/main/java/com/kista/trading/application/service/TradingReporter.java`
- `trading-core/src/main/java/com/kista/trading/domain/model/ReorderResult.java`

테스트(16개):
- `src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java`
- `src/test/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapterTest.java`
- `src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java`
- `src/test/java/com/kista/admin/AdminInternalApiIntegrationTest.java`
- `src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapterTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/OrderCancelServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/ReorderServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/SelectionChainTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingOrderPlannerTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingReporterTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/domain/model/OrderTest.java`

실행 커맨드(Git Bash, 이 저장소 루트에서):
```bash
FILES="src/main/java/com/kista/admin/domain/model/AdminReorderResult.java \
trading-core/src/main/java/com/kista/trading/adapter/in/web/dto/NextOrdersResponse.java \
trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderEntity.java \
trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderJpaRepository.java \
trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapter.java \
trading-core/src/main/java/com/kista/trading/application/port/output/OrderPort.java \
trading-core/src/main/java/com/kista/trading/application/service/OrderCancelService.java \
trading-core/src/main/java/com/kista/trading/application/service/ReorderService.java \
trading-core/src/main/java/com/kista/trading/application/service/TradingReporter.java \
trading-core/src/main/java/com/kista/trading/domain/model/ReorderResult.java \
src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java \
src/test/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapterTest.java \
src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java \
src/test/java/com/kista/admin/AdminInternalApiIntegrationTest.java \
src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java \
trading-core/src/test/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapterTest.java \
trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java \
trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/OrderCancelServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/ReorderServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/SelectionChainTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingOrderPlannerTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingReporterTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java \
trading-core/src/test/java/com/kista/trading/domain/model/OrderTest.java"

for f in $FILES; do
  sed -i 's/Order\.OrderStatus/OrderStatus/g' "$f"
  grep -q "^import com.kista.sharedkernel.OrderStatus;$" "$f" || \
    sed -i '0,/^import /s//import com.kista.sharedkernel.OrderStatus;\nimport /' "$f"
done
```

- [ ] **Step 4: BOM 오염 확인 (java-encoding.md 룰)**

Run: `grep -rl $'\xef\xbb\xbf' $FILES 2>/dev/null`
Expected: 출력 없음. 출력이 있으면 해당 파일에 `sed -i '1s/^\xef\xbb\xbf//' "$f"` 적용.

- [ ] **Step 5: 잔여 참조 확인**

Run: `grep -rn "Order\.OrderStatus" src trading-core --include="*.java"`
Expected: 매치 없음(`Order.java` 자신의 nested enum 정의도 이미 삭제했으므로 전무해야 한다).

- [ ] **Step 6: 전체 빌드 확인**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL, 모든 기존 테스트 그린. `OrderJpaRepository.java`의 `@Query`에 있는 문자열 리터럴(`'PLANNED'`)은 enum name() 값이라 이번 변경으로 안 바뀌므로 영향 없음.

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src/main/java/com/kista/sharedkernel/OrderStatus.java \
        trading-core/src/main/java/com/kista/trading/domain/model/Order.java \
        [Step 3의 26개 파일]
git commit -m "refactor(sharedkernel): Order.OrderStatus를 sharedkernel.OrderStatus로 승격"
```

---

### Task 3: `sharedkernel.OrderTiming` 승격

**Files:**
- Create: `trading-core/src/main/java/com/kista/sharedkernel/OrderTiming.java`
- Delete: `trading-core/src/main/java/com/kista/matching/domain/model/OrderTiming.java`
- Modify (sed 일괄 치환, 아래 "대상 파일 목록" 30개): `import com.kista.matching.domain.model.OrderTiming;` → `import com.kista.sharedkernel.OrderTiming;`

**Interfaces:**
- Consumes: 없음
- Produces: `com.kista.sharedkernel.OrderTiming`(AT_CLOSE/AT_OPEN/IMMEDIATE) — Task 4가 `AdminOrderView.timing` 필드 타입으로 사용

`OrderTiming`은 이미 최상위 클래스이므로 `OrderStatus`(nested enum 승격)보다 훨씬 단순하다 — 패키지만 옮기면 되고 코드 내 사용부(`OrderTiming.AT_OPEN` 등)는 전혀 바뀌지 않는다.

- [ ] **Step 1: sharedkernel에 OrderTiming 생성, matching의 원본 삭제**

`trading-core/src/main/java/com/kista/sharedkernel/OrderTiming.java`:
```java
package com.kista.sharedkernel;

// 주문 접수 시점 — admin·trading·matching이 공유하는 순수 값(outbound-zero)
public enum OrderTiming {
    AT_CLOSE,   // 마감 배치(04:30 KST)에 접수 — 기본값
    AT_OPEN,    // 개장 시점(22:30 KST)에 선접수
    IMMEDIATE   // 관리자 재주문 즉시 접수 (정규장 중에만 사용)
}
```

```bash
rm trading-core/src/main/java/com/kista/matching/domain/model/OrderTiming.java
```

- [ ] **Step 2: 대상 파일 30개에서 import 경로 일괄 치환**

메인(11개):
- `src/main/java/com/kista/admin/adapter/in/web/dto/AdminReorderRequest.java`
- `src/main/java/com/kista/admin/domain/model/AdminReorderCommand.java`
- `trading-core/src/main/java/com/kista/matching/domain/strategy/CycleOrderStrategy.java`
- `trading-core/src/main/java/com/kista/matching/domain/strategy/InfiniteCycleOrderStrategy.java`
- `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderEntity.java`
- `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderJpaRepository.java`
- `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapter.java`
- `trading-core/src/main/java/com/kista/trading/application/service/ManualTradeCorrectionService.java`
- `trading-core/src/main/java/com/kista/trading/application/service/TradingOrderSlots.java`
- `trading-core/src/main/java/com/kista/trading/domain/model/Order.java`
- `trading-core/src/main/java/com/kista/trading/domain/model/ReorderCommand.java`

테스트(19개):
- `src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java`
- `src/test/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapterTest.java`
- `src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java`
- `src/test/java/com/kista/admin/AdminInternalApiIntegrationTest.java`
- `src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/adapter/in/web/TradingInternalCommandControllerTest.java`
- `trading-core/src/test/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapterTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/OrderCancelServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/ReorderServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/SelectionChainTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingOrderBudgetAllocatorTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingOrderPlannerTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingPreviewServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingReporterTest.java`
- `trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java`
- `trading-core/src/test/java/com/kista/trading/domain/model/OrderTest.java`
- `trading-core/src/test/java/com/kista/trading/stats/application/service/BacktestServiceTest.java`

실행 커맨드:
```bash
FILES="src/main/java/com/kista/admin/adapter/in/web/dto/AdminReorderRequest.java \
src/main/java/com/kista/admin/domain/model/AdminReorderCommand.java \
trading-core/src/main/java/com/kista/matching/domain/strategy/CycleOrderStrategy.java \
trading-core/src/main/java/com/kista/matching/domain/strategy/InfiniteCycleOrderStrategy.java \
trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderEntity.java \
trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderJpaRepository.java \
trading-core/src/main/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapter.java \
trading-core/src/main/java/com/kista/trading/application/service/ManualTradeCorrectionService.java \
trading-core/src/main/java/com/kista/trading/application/service/TradingOrderSlots.java \
trading-core/src/main/java/com/kista/trading/domain/model/Order.java \
trading-core/src/main/java/com/kista/trading/domain/model/ReorderCommand.java \
src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java \
src/test/java/com/kista/admin/adapter/out/internal/TradingCommandHttpAdapterTest.java \
src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java \
src/test/java/com/kista/admin/AdminInternalApiIntegrationTest.java \
src/test/java/com/kista/admin/application/service/AdminReorderServiceTest.java \
trading-core/src/test/java/com/kista/trading/adapter/in/web/TradingInternalCommandControllerTest.java \
trading-core/src/test/java/com/kista/trading/adapter/out/persistence/OrderPersistenceAdapterTest.java \
trading-core/src/test/java/com/kista/trading/application/service/BuyOrderPriceCapperTest.java \
trading-core/src/test/java/com/kista/trading/application/service/ManualTradingServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/OrderCancelServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/ReorderServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/SelectionChainTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingOrderBudgetAllocatorTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingOrderPlannerTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingPreviewServiceTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingReporterTest.java \
trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java \
trading-core/src/test/java/com/kista/trading/domain/model/OrderTest.java \
trading-core/src/test/java/com/kista/trading/stats/application/service/BacktestServiceTest.java"

for f in $FILES; do
  sed -i 's/import com\.kista\.matching\.domain\.model\.OrderTiming;/import com.kista.sharedkernel.OrderTiming;/' "$f"
done
```

- [ ] **Step 3: BOM 오염 확인**

Run: `grep -rl $'\xef\xbb\xbf' $FILES 2>/dev/null`
Expected: 출력 없음(있으면 Task 2 Step 4와 동일하게 처리).

- [ ] **Step 4: 잔여 참조 확인**

Run: `grep -rn "matching.domain.model.OrderTiming" src trading-core --include="*.java"`
Expected: 매치 없음.

- [ ] **Step 5: ArchUnit + 전체 빌드 확인**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL. `HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`가 그린인지 특히 확인 — `sharedkernel`은 이미 허용 의존이므로 `CycleOrderStrategy`/`InfiniteCycleOrderStrategy`(matching.domain.strategy)가 `sharedkernel.OrderTiming`을 참조해도 통과해야 한다. 실패하면 이 규칙의 허용 목록을 먼저 읽고 원인을 진단할 것 — 이 계획 밖의 조치가 필요하면 진행을 멈추고 보고한다.

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src/main/java/com/kista/sharedkernel/OrderTiming.java \
        [Step 2의 30개 파일]
git commit -m "refactor(sharedkernel): matching.OrderTiming을 sharedkernel.OrderTiming으로 승격"
```

---

### Task 4: admin own-type read model 신설 + 조회 경로 시그니처 교체

**Files:**
- Create: `src/main/java/com/kista/admin/domain/model/AdminOrderView.java`
- Create: `src/main/java/com/kista/admin/domain/model/AdminStrategyView.java`
- Modify: `src/main/java/com/kista/admin/application/port/output/TradingQueryPort.java`
- Modify: `src/main/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapter.java`
- Modify: `src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java`
- Modify: `src/main/java/com/kista/admin/application/service/AdminQueryService.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminTradeController.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminAccountController.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminTradeResponse.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminStrategyResponse.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountResponse.java`
- Test Modify: `src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java`
- Test Modify: `src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java`
- Test Modify: `src/test/java/com/kista/admin/adapter/in/web/AdminAccountControllerTest.java`

**Interfaces:**
- Consumes: `sharedkernel.OrderStatus`(Task 2), `sharedkernel.OrderTiming`(Task 3)
- Produces: `AdminOrderView`(id/accountId/strategyCycleId/tradeDate/ticker/orderType/timing/direction/orderLeg/quantity/price/status/externalOrderId/filledQuantity/filledPrice — `Order` 15필드 전체 복제), `AdminStrategyView`(id/accountId/type/status/ticker/cycleSeedType — `Strategy` 5필드 전체 복제 + `isActive()`/`isPaused()` 헬퍼)

`trading-core`의 `TradingInternalQueryController`(서버 측, 이 태스크에서 무변경)는 `Order`/`Strategy` 원본을 그대로 JSON으로 반환한다. `AdminOrderView`/`AdminStrategyView`는 필드명·타입을 정확히 맞춰 Jackson이 자동으로 매핑하게 한다. `StrategySummary`는 이미 own-type 판정이 끝난 타입이라 이 태스크에서 건드리지 않는다.

- [ ] **Step 1: AdminOrderView 생성**

`src/main/java/com/kista/admin/domain/model/AdminOrderView.java`:
```java
package com.kista.admin.domain.model;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// trading.domain.model.Order의 admin own-type read model — TradingQueryHttpAdapter가
// trading-core 내부 API(/api/internal/trading/orders 등) 응답을 이 타입으로 역직렬화한다.
// 서버가 Order 전체(15필드)를 그대로 반환하므로 필드를 하나라도 빠뜨리면 Jackson의
// FAIL_ON_UNKNOWN_PROPERTIES 설정에 암묵 의존하게 된다 — orderLeg를 포함해 전체 필드를 복제한다.
public record AdminOrderView(
        UUID id,
        UUID accountId,
        UUID strategyCycleId,
        LocalDate tradeDate,
        StrategyTicker ticker,
        OrderType orderType,
        OrderTiming timing,
        OrderDirection direction,
        String orderLeg,
        Integer quantity,
        BigDecimal price,
        OrderStatus status,
        String externalOrderId,
        Integer filledQuantity,
        BigDecimal filledPrice
) {}
```

- [ ] **Step 2: AdminStrategyView 생성**

`src/main/java/com/kista/admin/domain/model/AdminStrategyView.java`:
```java
package com.kista.admin.domain.model;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// trading.domain.model.Strategy의 admin own-type read model — TradingQueryHttpAdapter가
// trading-core 내부 API(/api/internal/trading/accounts/{id}/strategies 등) 응답을 이 타입으로
// 역직렬화한다. isActive()/isPaused()는 AdminQueryService.getAnomalies가 Strategy::isActive/
// isPaused를 그대로 대체하기 위한 헬퍼다.
public record AdminStrategyView(
        UUID id,
        UUID accountId,
        StrategyType type,
        StrategyStatus status,
        StrategyTicker ticker,
        StrategyCycleSeedType cycleSeedType
) {
    public boolean isActive() {
        return status == StrategyStatus.ACTIVE;
    }

    public boolean isPaused() {
        return status == StrategyStatus.PAUSED;
    }
}
```

- [ ] **Step 3: TradingQueryPort 시그니처 교체**

`src/main/java/com/kista/admin/application/port/output/TradingQueryPort.java` 전체를 다음으로 교체:
```java
package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.trading.domain.model.StrategySummary;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// admin이 정의하는 trading-core 조회 포트 — TradingQueryHttpAdapter가 내부 API로 구현
public interface TradingQueryPort {
    List<AdminOrderView> findAllOrders(LocalDate from, LocalDate to);
    List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to);
    List<AdminStrategyView> findStrategiesByAccountId(UUID accountId);
    Map<UUID, List<AdminStrategyView>> findStrategiesByAccountIds(Set<UUID> accountIds);
    Map<UUID, StrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds);
    List<AdminOrderView> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);
    List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId);
}
```

- [ ] **Step 4: TradingQueryHttpAdapter 시그니처 교체**

`src/main/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapter.java` 전체를 다음으로 교체:
```java
package com.kista.admin.adapter.out.internal;

import com.kista.admin.application.port.output.TradingQueryPort;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.platform.internalapi.InternalApiErrorDetails;
import com.kista.trading.domain.model.StrategySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class TradingQueryHttpAdapter implements TradingQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<AdminOrderView> findAllOrders(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<AdminOrderView>>() {});
    }

    @Override
    public List<UUID> findDistinctAccountIds(LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/orders/distinct-account-ids").queryParam("from", from).queryParam("to", to).build())
                .retrieve().body(new ParameterizedTypeReference<List<UUID>>() {});
    }

    @Override
    public List<AdminStrategyView> findStrategiesByAccountId(UUID accountId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies", accountId)
                .retrieve().body(new ParameterizedTypeReference<List<AdminStrategyView>>() {});
    }

    @Override
    public Map<UUID, List<AdminStrategyView>> findStrategiesByAccountIds(Set<UUID> accountIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategies/by-account-ids")
                .body(accountIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, List<AdminStrategyView>>>() {});
    }

    @Override
    public Map<UUID, StrategySummary> findStrategySummariesByCycleIds(Set<UUID> cycleIds) {
        return internalApiRestClient.post()
                .uri("/api/internal/trading/strategy-summaries")
                .body(cycleIds)
                .retrieve().body(new ParameterizedTypeReference<Map<UUID, StrategySummary>>() {});
    }

    @Override
    public List<AdminOrderView> findStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate) {
        return internalApiRestClient.get()
                .uri(b -> b.path("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/orders")
                        .queryParam("tradeDate", tradeDate).build(accountId, strategyId))
                .retrieve()
                // trading 쪽 컨트롤러가 소유권 불일치 시 NoSuchElementException(→404)을 던진다 —
                // 여기서 되돌리지 않으면 admin의 GlobalExceptionHandler가 매핑하지 못하는
                // HttpClientErrorException.NotFound로 흘러 500(catch-all)으로 뭉개진다.
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "전략이 해당 계좌에 속하지 않습니다"));
                })
                .body(new ParameterizedTypeReference<List<AdminOrderView>>() {});
    }

    @Override
    public List<LocalDate> findStrategyTradeDates(UUID accountId, UUID strategyId) {
        return internalApiRestClient.get()
                .uri("/api/internal/trading/accounts/{accountId}/strategies/{strategyId}/trade-dates", accountId, strategyId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new NoSuchElementException(InternalApiErrorDetails.detailOrDefault(response, "전략이 해당 계좌에 속하지 않습니다"));
                })
                .body(new ParameterizedTypeReference<List<LocalDate>>() {});
    }
}
```

- [ ] **Step 5: AdminQueryUseCase 시그니처 교체**

`src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java`의 import 블록(3-11번째 줄)을 다음으로 교체:
```java
import com.kista.account.domain.model.Account;
import com.kista.admin.domain.model.AdminAnomalies;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStats;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.admin.domain.model.AppErrorLog;
import com.kista.admin.domain.model.AuditLog;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import com.kista.trading.domain.model.StrategySummary;
```
인터페이스 메서드 시그니처를 다음으로 교체(24-46번째 줄 범위):
```java
    List<Order> listTrades(LocalDate from, LocalDate to);       // null = 전체
```
→
```java
    List<AdminOrderView> listTrades(LocalDate from, LocalDate to);       // null = 전체
```
```java
    List<Strategy> listStrategies(UUID accountId);
```
→
```java
    List<AdminStrategyView> listStrategies(UUID accountId);
```
```java
    Map<UUID, List<Strategy>> listStrategiesByAccountIds(Set<UUID> accountIds);
```
→
```java
    Map<UUID, List<AdminStrategyView>> listStrategiesByAccountIds(Set<UUID> accountIds);
```
```java
    List<Order> listStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);
```
→
```java
    List<AdminOrderView> listStrategyOrders(UUID accountId, UUID strategyId, LocalDate tradeDate);
```
(`getStrategySummariesByCycleIds`는 `StrategySummary` 그대로 무변경)

- [ ] **Step 6: AdminQueryService 시그니처 교체**

`src/main/java/com/kista/admin/application/service/AdminQueryService.java`의 9-12번째 줄을 다음으로 교체:
```java
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import com.kista.trading.domain.model.StrategySummary;
```
77번째 줄 `public List<Order> listTrades(...)` → `public List<AdminOrderView> listTrades(...)`.
101번째 줄 `Map<UUID, List<Strategy>> strategiesByAccountId = ...` → `Map<UUID, List<AdminStrategyView>> strategiesByAccountId = ...`.
107번째 줄 `.anyMatch(Strategy::isPaused))` → `.anyMatch(AdminStrategyView::isPaused))`.
117번째 줄 `.anyMatch(Strategy::isActive))` → `.anyMatch(AdminStrategyView::isActive))`.
139번째 줄 `public List<Strategy> listStrategies(...)` → `public List<AdminStrategyView> listStrategies(...)`.
144번째 줄 `public Map<UUID, List<Strategy>> listStrategiesByAccountIds(...)` → `public Map<UUID, List<AdminStrategyView>> listStrategiesByAccountIds(...)`.
149번째 줄 `public List<Order> listStrategyOrders(...)` → `public List<AdminOrderView> listStrategyOrders(...)`.

- [ ] **Step 7: AdminTradeController 시그니처 교체**

`src/main/java/com/kista/admin/adapter/in/web/AdminTradeController.java`의 11·13번째 줄:
```java
import com.kista.trading.domain.model.StrategySummary;
import com.kista.trading.domain.model.Order;
```
→
```java
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.trading.domain.model.StrategySummary;
```
(이 컨트롤러는 `Strategy`를 직접 참조하지 않으므로 `AdminStrategyView` import는 추가하지 않는다)

79번째 줄 `List<Order> orders = ...` → `List<AdminOrderView> orders = ...`.
80번째 줄 `.filter(order -> accountId.equals(order.accountId()))` 무변경(메서드명 동일).
90번째 줄 `.map(Order::strategyCycleId)` → `.map(AdminOrderView::strategyCycleId)`.
130번째 줄 `private List<AdminTradeResponse> toResponses(List<Order> orders)` → `private List<AdminTradeResponse> toResponses(List<AdminOrderView> orders)`.
134번째 줄 `.map(Order::strategyCycleId)` → `.map(AdminOrderView::strategyCycleId)`.

- [ ] **Step 8: AdminAccountController 시그니처 교체**

`src/main/java/com/kista/admin/adapter/in/web/AdminAccountController.java`의 8번째 줄:
```java
import com.kista.trading.domain.model.Strategy;
```
→
```java
import com.kista.admin.domain.model.AdminStrategyView;
```
52번째 줄 `Map<UUID, List<Strategy>> strategyMap = ...` → `Map<UUID, List<AdminStrategyView>> strategyMap = ...`.

- [ ] **Step 9: AdminTradeResponse.from() 시그니처 교체**

`src/main/java/com/kista/admin/adapter/in/web/dto/AdminTradeResponse.java`의 2-8번째 줄(import 블록)을 다음으로 교체:
```java
import com.kista.account.domain.model.Account;
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.trading.domain.model.StrategySummary;
import com.kista.user.domain.model.AdminUserView;
import io.swagger.v3.oas.annotations.media.Schema;
```
`public static AdminTradeResponse from(Order t, ...)` → `public static AdminTradeResponse from(AdminOrderView t, ...)`(본문 로직은 무변경 — `t.id()`/`t.accountId()`/`t.strategyCycleId()`/`t.tradeDate()`/`t.ticker()`/`t.direction()`/`t.orderType()`/`t.timing()`/`t.quantity()`/`t.price()`/`t.status()`/`t.externalOrderId()`/`t.filledQuantity()`/`t.filledPrice()` 전부 `AdminOrderView`에 동일 이름으로 존재).

- [ ] **Step 10: AdminStrategyResponse.from() 시그니처 교체**

`src/main/java/com/kista/admin/adapter/in/web/dto/AdminStrategyResponse.java`의 3번째 줄:
```java
import com.kista.trading.domain.model.Strategy;
```
→
```java
import com.kista.admin.domain.model.AdminStrategyView;
```
`public static AdminStrategyResponse from(Strategy strategy)` → `public static AdminStrategyResponse from(AdminStrategyView strategy)`(본문 무변경).

- [ ] **Step 11: AdminAccountResponse.from() 시그니처 교체**

`src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountResponse.java`의 5번째 줄:
```java
import com.kista.trading.domain.model.Strategy;
```
→
```java
import com.kista.admin.domain.model.AdminStrategyView;
```
`public static AdminAccountResponse from(Account a, AdminUserView user, List<Strategy> strategies)` → `public static AdminAccountResponse from(Account a, AdminUserView user, List<AdminStrategyView> strategies)`(본문 무변경 — `strategies.stream().map(AdminStrategyResponse::from)`는 그대로 컴파일된다).

- [ ] **Step 12: TradingQueryHttpAdapterTest 갱신**

`src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java`의 1-9번째 줄(import)을 다음으로 교체:
```java
import com.kista.admin.domain.model.AdminOrderView;
import com.kista.admin.domain.model.AdminStrategyView;
import com.kista.trading.domain.model.StrategySummary;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderStatus;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
```
55번째 줄 `List<?> result = adapter.findAllOrders(from, to);` 무변경(타입 무관 빈 리스트 검증).
82번째 줄 `List<Order> result = adapter.findAllOrders(...)` → `List<AdminOrderView> result = adapter.findAllOrders(...)`.
85번째 줄 `Order order = result.get(0);` → `AdminOrderView order = result.get(0);`.
98번째 줄 `assertThat(order.status()).isEqualTo(Order.OrderStatus.PARTIALLY_FILLED);` → `assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);`.
136번째 줄 `var result = adapter.findStrategiesByAccountId(accountId);` 무변경(var 타입 추론).

- [ ] **Step 13: AdminTradeControllerTest 갱신**

`src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java`의 9·13번째 줄:
```java
import com.kista.trading.domain.model.Order;
```
```java
import com.kista.trading.domain.model.Strategy;
```
둘 다 삭제하고 다음 import 추가:
```java
import com.kista.admin.domain.model.AdminOrderView;
```
(`Strategy` import는 이 파일 안에서 다른 용도로 쓰이는지 먼저 `grep -n "\bStrategy\b" src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java`로 재확인 — `StrategyType`/`StrategyStatus`/`StrategyTicker`(sharedkernel) 매치만 나오면 순수 삭제 가능)

124-140번째 줄의 `new Order(...)` 호출을 `new AdminOrderView(...)`로 교체하되, `Order`의 14-arg 편의 생성자(orderLeg 자동 채움)가 아니라 `AdminOrderView`의 15-필드 canonical 생성자를 써야 하므로 `orderLeg` 인자를 명시적으로 추가한다:
```java
        when(adminQuery.listStrategyOrders(accountId, strategyId, LocalDate.of(2026, 7, 1))).thenReturn(List.of(
                new AdminOrderView(
                        UUID.fromString("00000000-0000-0000-0000-000000000050"),
                        accountId,
                        cycleId,
                        LocalDate.of(2026, 7, 1),
                        StrategyTicker.SOXL,
                        OrderType.LIMIT,
                        OrderTiming.AT_OPEN,
                        OrderDirection.SELL,
                        "SELL_01",
                        2,
                        new BigDecimal("267.37"),
                        OrderStatus.PLACED,
                        "BROKER-1",
                        null,
                        null
                )
        ));
```
(orderLeg 값 `"SELL_01"`은 임의값 — `AdminOrderView`는 `Order`처럼 orderLeg 자동보정 compact 생성자가 없으므로 반드시 non-null 문자열을 넣는다. 이 필드는 이 테스트의 어떤 assertion에서도 검증되지 않는다.)

Step 2(Task 2)에서 이미 이 파일의 `Order.OrderStatus.PLANNED` → `OrderStatus.PLANNED`(266-267번째 줄, `AdminReorderResult` 생성자 인자)와 `import com.kista.sharedkernel.OrderStatus;` 추가가 완료돼 있어야 한다 — 이 Step에서는 그 부분을 다시 건드리지 않는다.

- [ ] **Step 14: AdminAccountControllerTest 갱신**

`src/test/java/com/kista/admin/adapter/in/web/AdminAccountControllerTest.java`의 6번째 줄:
```java
import com.kista.trading.domain.model.Strategy;
```
→
```java
import com.kista.admin.domain.model.AdminStrategyView;
```
82-83번째 줄:
```java
                new Strategy(UUID.randomUUID(), accountId, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                        StrategyTicker.SOXL, StrategyCycleSeedType.MAX)));
```
→
```java
                new AdminStrategyView(UUID.randomUUID(), accountId, StrategyType.PRIVACY, StrategyStatus.ACTIVE,
                        StrategyTicker.SOXL, StrategyCycleSeedType.MAX)));
```
(`AdminStrategyView`는 `accountId` 필드가 있어 `Strategy`와 인자 개수·순서가 다르다는 점 주의 — `Strategy(id, accountId, type, status, ticker, cycleSeedType)`와 `AdminStrategyView(id, accountId, type, status, ticker, cycleSeedType)`는 필드 순서가 동일하므로 인자 순서 변경 없음)

- [ ] **Step 15: 전체 빌드 확인**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL. 특히 `AdminQueryServiceTest`(이 태스크가 건드리지 않는 파일)도 그린이어야 한다 — 그 테스트는 `TradingQueryPort`를 목업하지만 `Order`/`Strategy` 반환값을 직접 만들지 않으므로 영향받지 않는다(사전 확인 완료).

- [ ] **Step 16: 커밋**

```bash
git add src/main/java/com/kista/admin/domain/model/AdminOrderView.java \
        src/main/java/com/kista/admin/domain/model/AdminStrategyView.java \
        src/main/java/com/kista/admin/application/port/output/TradingQueryPort.java \
        src/main/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapter.java \
        src/main/java/com/kista/admin/application/usecase/AdminQueryUseCase.java \
        src/main/java/com/kista/admin/application/service/AdminQueryService.java \
        src/main/java/com/kista/admin/adapter/in/web/AdminTradeController.java \
        src/main/java/com/kista/admin/adapter/in/web/AdminAccountController.java \
        src/main/java/com/kista/admin/adapter/in/web/dto/AdminTradeResponse.java \
        src/main/java/com/kista/admin/adapter/in/web/dto/AdminStrategyResponse.java \
        src/main/java/com/kista/admin/adapter/in/web/dto/AdminAccountResponse.java \
        src/test/java/com/kista/admin/adapter/out/internal/TradingQueryHttpAdapterTest.java \
        src/test/java/com/kista/admin/adapter/in/web/AdminTradeControllerTest.java \
        src/test/java/com/kista/admin/adapter/in/web/AdminAccountControllerTest.java
git commit -m "refactor(admin): AdminOrderView/AdminStrategyView own-type 신설, 조회 경로 전면 교체"
```

---

### Task 5: `StrategyRef` 최상위 승격

**Files:**
- Create: `src/main/java/com/kista/stats/domain/model/StrategyRef.java`
- Modify: `src/main/java/com/kista/stats/domain/model/HousingBenchmarkComparison.java`
- Modify: `src/main/java/com/kista/stats/application/service/HousingBenchmarkComparisonBuilder.java`
- Modify: `src/main/java/com/kista/stats/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java`
- Modify: `src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java`
- Test Modify: `src/test/java/com/kista/stats/application/service/StatsServiceTest.java`
- Test Modify: `src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `com.kista.stats.domain.model.StrategyRef(UUID id, StrategyType type, StrategyTicker ticker)` — `InvestmentPointsPort.Result.selectedStrategy`와 `HousingBenchmarkComparison.strategy` 양쪽이 공유

trading-core 서버(`InvestmentPointsResponse`/`InvestmentPointsResult`)는 이 태스크 이후에도 `Strategy` 전체(6필드: id/accountId/type/status/ticker/cycleSeedType)를 그대로 JSON으로 반환한다 — `RuntimeSettingsPersistenceAdapterTest`가 이미 검증한 대로 Spring Boot 자동설정 `ObjectMapper`는 `FAIL_ON_UNKNOWN_PROPERTIES`를 기본 `false`로 두므로, `StrategyRef`(id/type/ticker 3필드)로 받아도 나머지 3필드(accountId/status/cycleSeedType)는 조용히 무시되고 안전하게 매핑된다. 서버 쪽 파일은 이 태스크에서 건드리지 않는다.

- [ ] **Step 1: StrategyRef 생성**

`src/main/java/com/kista/stats/domain/model/StrategyRef.java`:
```java
package com.kista.stats.domain.model;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.util.UUID;

// InvestmentPointsPort.Result.selectedStrategy와 HousingBenchmarkComparison.strategy가
// 공유하는 최소 전략 투영 — trading.domain.model.Strategy 전체(6필드) 중 벤치마크 비교 화면이
// 실제로 쓰는 3필드만 담는다. 서버(InvestmentPointsResponse)는 Strategy 전체를 그대로 반환하지만
// Jackson의 FAIL_ON_UNKNOWN_PROPERTIES 기본값(false)이 초과 필드를 조용히 무시한다.
public record StrategyRef(UUID id, StrategyType type, StrategyTicker ticker) {}
```

- [ ] **Step 2: HousingBenchmarkComparison에서 nested StrategyInfo 제거**

`src/main/java/com/kista/stats/domain/model/HousingBenchmarkComparison.java` 전체를 다음으로 교체(Task 1에서 이미 `import com.kista.trading.domain.model.Strategy;`를 삭제한 상태를 전제로 한다):
```java
package com.kista.stats.domain.model;

import java.time.LocalDate;
import java.util.List;

public record HousingBenchmarkComparison(
        BenchmarkScope scope,
        StrategyRef strategy,
        Benchmark benchmark,
        Period period,
        PerformanceComparisonSummary summary,
        List<HousingBenchmarkPoint> points,
        CurrentExchangeRate currentExchangeRate,
        String emptyReason
) {
    public record Benchmark(
            BenchmarkAssetType assetType,
            String regionCode,   // HOUSING 전용, ETF면 null
            String regionName,   // HOUSING 전용, ETF면 null
            String symbol,       // ETF 전용, HOUSING이면 null
            String label,
            LocalDate sourceUpdatedDate
    ) {}

    public record Period(LocalDate fromDate, LocalDate toDate, int pointCount) {}

    public HousingBenchmarkComparison withCurrentExchangeRate(CurrentExchangeRate rate) {
        return new HousingBenchmarkComparison(
                scope, strategy, benchmark, period, summary, points, rate, emptyReason);
    }
}
```
(`StrategyInfo` nested record 삭제, `strategy` 필드 타입을 `StrategyRef`로 — 같은 패키지 `com.kista.stats.domain.model` 소속이라 import 불필요. `UUID`/`StrategyType`/`StrategyTicker` import도 이 파일에서 더 이상 직접 쓰이지 않으므로 제거)

- [ ] **Step 3: HousingBenchmarkComparisonBuilder 교체**

`src/main/java/com/kista/stats/application/service/HousingBenchmarkComparisonBuilder.java`의 10번째 줄 `import com.kista.trading.domain.model.Strategy;` 삭제.

29-41번째 줄을 다음으로 교체:
```java
    HousingBenchmarkComparison build(
            BenchmarkScope scope,
            StrategyRef strategy,
            HousingBenchmarkComparison.Benchmark benchmark,
            List<InvestmentPoint> investmentPoints,
            Map<LocalDate, BigDecimal> benchmarkPrices,
            BenchmarkGranularity granularity) {
        if (investmentPoints.isEmpty()) {
            return empty(scope, strategy, benchmark, "NO_INVESTMENT_DATA");
        }
```
(`StrategyInfo` 변환 3줄이 사라진다 — 파라미터가 이미 `StrategyRef`이므로 `strategy`를 그대로 전달한다. 이후 41번째 줄부터 이어지는 본문에서 `strategyInfo` 변수를 참조하던 모든 곳을 `strategy`로 바꾼다: 54번째 줄 `return empty(scope, strategyInfo, benchmark, "INSUFFICIENT_COMMON_MONTHS");` → `return empty(scope, strategy, benchmark, "INSUFFICIENT_COMMON_MONTHS");`, 61번째 줄도 동일 패턴, 108-109번째 줄 `return new HousingBenchmarkComparison(scope, strategyInfo, benchmark, ...)` → `return new HousingBenchmarkComparison(scope, strategy, benchmark, ...)`)

119-128번째 줄의 `empty()` 헬퍼 시그니처도 교체:
```java
    private static HousingBenchmarkComparison empty(
            BenchmarkScope scope,
            StrategyRef strategy,
            HousingBenchmarkComparison.Benchmark benchmark,
            String reason) {
        return new HousingBenchmarkComparison(
                scope, strategy, benchmark,
                new HousingBenchmarkComparison.Period(null, null, 0),
                null, List.of(), null, reason);
    }
```

- [ ] **Step 4: HousingBenchmarkComparisonResponse.from() 타입 교체**

`src/main/java/com/kista/stats/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java`의 3번째 줄 뒤에 import 추가:
```java
import com.kista.stats.domain.model.StrategyRef;
```
92번째 줄:
```java
        HousingBenchmarkComparison.StrategyInfo strategy = comparison.strategy();
```
→
```java
        StrategyRef strategy = comparison.strategy();
```
(이 파일 자신의 nested record `HousingBenchmarkComparisonResponse.StrategyInfo`(응답 DTO, `@Schema(name = "HousingBenchmarkStrategyInfo")`)는 이름을 바꾸지 않는다 — OpenAPI 스키마 이름을 검증하는 `HousingBenchmarkApiDocsTest`/`HousingBenchmarkComparisonResponseSchemaTest`가 이 이름에 의존하므로 무변경 유지)

- [ ] **Step 5: InvestmentPointsPort.Result 타입 교체**

`src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java` 전체를 다음으로 교체:
```java
package com.kista.stats.application.port.output;

import com.kista.stats.domain.model.StrategyRef;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvestmentPointsPort {
    record Result(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, StrategyRef selectedStrategy) {}

    Result fetch(UUID userId, Scope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity);

    enum Scope { STRATEGY, PORTFOLIO }
}
```
(이 Step에서는 `Scope` enum을 아직 유지한다 — Task 6에서 삭제)

- [ ] **Step 6: StatsServiceTest 갱신**

`src/test/java/com/kista/stats/application/service/StatsServiceTest.java`의 5번째 줄:
```java
import com.kista.trading.domain.model.Strategy;
```
→
```java
import com.kista.stats.domain.model.StrategyRef;
```
58-60번째 줄:
```java
    private static final Strategy STRATEGY = new Strategy(
            STRATEGY_ID, ACCOUNT_ID, StrategyType.INFINITE, StrategyStatus.ACTIVE,
            StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
```
→
```java
    private static final StrategyRef STRATEGY = new StrategyRef(
            STRATEGY_ID, StrategyType.INFINITE, StrategyTicker.SOXL);
```
(`ACCOUNT_ID`/`StrategyStatus`/`StrategyCycleSeedType`가 이 파일 다른 곳에서 쓰이는지 `grep -n "ACCOUNT_ID\|StrategyStatus\|StrategyCycleSeedType" src/test/java/com/kista/stats/application/service/StatsServiceTest.java`로 확인 — 다른 사용처가 없다면 관련 import도 함께 정리하되, 있다면 그대로 둔다)

- [ ] **Step 7: InvestmentPointsHttpAdapterTest 갱신**

`src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java`의 4번째 줄:
```java
import com.kista.trading.domain.model.Strategy;
```
→
```java
import com.kista.stats.domain.model.StrategyRef;
```
80-83번째 줄:
```java
        Strategy expected = new Strategy(strategyId, accountId,
                com.kista.sharedkernel.StrategyType.INFINITE, com.kista.sharedkernel.StrategyStatus.ACTIVE,
                com.kista.sharedkernel.StrategyTicker.SOXL, com.kista.sharedkernel.StrategyCycleSeedType.NONE);
        assertThat(result.selectedStrategy()).isEqualTo(expected);
```
→
```java
        StrategyRef expected = new StrategyRef(strategyId,
                com.kista.sharedkernel.StrategyType.INFINITE, com.kista.sharedkernel.StrategyTicker.SOXL);
        assertThat(result.selectedStrategy()).isEqualTo(expected);
```
(61-72번째 줄의 JSON 목업 리터럴은 그대로 유지한다 — 서버가 실제로 `Strategy` 6필드 전체를 보내는 현실을 반영한 테스트이며, `accountId`/`status`/`cycleSeedType`이 조용히 무시되는지를 이 테스트 자체가 검증하는 셈이 된다)

- [ ] **Step 8: 전체 빌드 확인**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL. `HousingBenchmarkApiDocsTest`/`HousingBenchmarkComparisonResponseSchemaTest`가 여전히 그린인지 특히 확인(응답 DTO의 nested `StrategyInfo` 이름은 안 바뀌었으므로 영향 없어야 한다). `HousingBenchmarkComparisonBuilderTest`는 `build()` 호출 시 `strategy` 인자에 항상 `null`만 넘기므로 무변경으로 통과해야 한다.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/stats/domain/model/StrategyRef.java \
        src/main/java/com/kista/stats/domain/model/HousingBenchmarkComparison.java \
        src/main/java/com/kista/stats/application/service/HousingBenchmarkComparisonBuilder.java \
        src/main/java/com/kista/stats/adapter/in/web/dto/HousingBenchmarkComparisonResponse.java \
        src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java \
        src/test/java/com/kista/stats/application/service/StatsServiceTest.java \
        src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java
git commit -m "refactor(stats): HousingBenchmarkComparison.StrategyInfo를 최상위 StrategyRef로 승격"
```

---

### Task 6: `InvestmentPointsPort.Scope` 삭제, `BenchmarkScope`로 통합

**Files:**
- Modify: `src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java`
- Modify: `src/main/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapter.java`
- Modify: `src/main/java/com/kista/stats/application/service/StatsService.java`
- Test Modify: `src/test/java/com/kista/stats/application/service/StatsServiceTest.java`
- Test Modify: `src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java`

**Interfaces:**
- Consumes: `com.kista.stats.domain.model.BenchmarkScope`(기존 타입, 이 태스크에서 신규 생성 없음)
- Produces: `InvestmentPointsPort.fetch()`의 두 번째 파라미터 타입이 `BenchmarkScope`로 고정

`trading-core` 쪽 `InvestmentPointsQuery.Scope`(HTTP 서버 계약)는 이 태스크에서 건드리지 않는다 — Gradle 컴파일 경계상 trading-core가 stats.domain.model을 참조할 수 없어 통합이 불가능하고, own-type 게이트 (a) 순환 불가피에 해당한다. 값 이름(`STRATEGY`/`PORTFOLIO`)이 동일하므로 HTTP 쿼리스트링 직렬화·서버 측 파싱 동작은 무변경이다.

- [ ] **Step 1: InvestmentPointsPort에서 nested Scope 삭제**

`src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java` 전체를 다음으로 교체:
```java
package com.kista.stats.application.port.output;

import com.kista.stats.domain.model.BenchmarkScope;
import com.kista.stats.domain.model.StrategyRef;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvestmentPointsPort {
    record Result(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, StrategyRef selectedStrategy) {}

    Result fetch(UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity);
}
```
(`enum Scope { STRATEGY, PORTFOLIO }` 삭제)

- [ ] **Step 2: InvestmentPointsHttpAdapter 파라미터 타입 교체**

`src/main/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapter.java`의 4번째 줄 뒤에 import 추가:
```java
import com.kista.stats.domain.model.BenchmarkScope;
```
22번째 줄:
```java
    public Result fetch(UUID userId, Scope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
```
→
```java
    public Result fetch(UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
```
(26번째 줄 `.queryParam("scope", scope)`는 무변경 — `BenchmarkScope`도 `enum.toString()`이 `name()`과 동일하게 동작해 쿼리스트링 값은 이전과 같다)

- [ ] **Step 3: StatsService의 Scope.valueOf 변환 제거**

`src/main/java/com/kista/stats/application/service/StatsService.java`의 69-71번째 줄:
```java
        InvestmentPointsPort.Result ctx = investmentPointsPort.fetch(
                userId, InvestmentPointsPort.Scope.valueOf(scope.name()), strategyId, from, effectiveTo,
                BenchmarkGranularity.WEEKLY);
```
→
```java
        InvestmentPointsPort.Result ctx = investmentPointsPort.fetch(
                userId, scope, strategyId, from, effectiveTo,
                BenchmarkGranularity.WEEKLY);
```
128-130번째 줄도 동일 패턴으로 교체:
```java
        InvestmentPointsPort.Result ctx = investmentPointsPort.fetch(
                userId, InvestmentPointsPort.Scope.valueOf(scope.name()), strategyId, from, effectiveTo,
                BenchmarkGranularity.DAILY);
```
→
```java
        InvestmentPointsPort.Result ctx = investmentPointsPort.fetch(
                userId, scope, strategyId, from, effectiveTo,
                BenchmarkGranularity.DAILY);
```

- [ ] **Step 4: StatsServiceTest의 Scope 참조 10곳 치환**

`src/test/java/com/kista/stats/application/service/StatsServiceTest.java`에서 `InvestmentPointsPort.Scope.PORTFOLIO` → `BenchmarkScope.PORTFOLIO`, `InvestmentPointsPort.Scope.STRATEGY` → `BenchmarkScope.STRATEGY`로 전체 치환한다(4번째 줄이 이미 `import com.kista.stats.domain.model.*;`로 `BenchmarkScope`를 와일드카드 커버하므로 추가 import 불필요):

```bash
sed -i 's/InvestmentPointsPort\.Scope\.PORTFOLIO/BenchmarkScope.PORTFOLIO/g; s/InvestmentPointsPort\.Scope\.STRATEGY/BenchmarkScope.STRATEGY/g' \
    src/test/java/com/kista/stats/application/service/StatsServiceTest.java
```

- [ ] **Step 5: InvestmentPointsHttpAdapterTest의 Scope 참조 치환**

```bash
sed -i 's/InvestmentPointsPort\.Scope\.PORTFOLIO/BenchmarkScope.PORTFOLIO/g; s/InvestmentPointsPort\.Scope\.STRATEGY/BenchmarkScope.STRATEGY/g' \
    src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java
```
치환 후 `import com.kista.stats.domain.model.BenchmarkScope;`가 없으면 4번째 줄(`import com.kista.stats.application.port.output.InvestmentPointsPort;`) 다음 줄에 추가한다.

- [ ] **Step 6: BOM 오염 확인**

Run: `grep -l $'\xef\xbb\xbf' src/test/java/com/kista/stats/application/service/StatsServiceTest.java src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java`
Expected: 출력 없음(있으면 Task 2 Step 4와 동일하게 처리).

- [ ] **Step 7: 잔여 참조 확인**

Run: `grep -rn "InvestmentPointsPort.Scope" src --include="*.java"`
Expected: 매치 없음.

- [ ] **Step 8: 전체 빌드 확인**

Run: `bash gradlew test`
Expected: BUILD SUCCESSFUL. `InvestmentPointsHttpAdapterTest`의 HTTP 요청 쿼리파라미터 검증 테스트(요청 경로와 쿼리파라미터를 올바르게 구성한다)가 `scope=STRATEGY`/`scope=PORTFOLIO` 값을 그대로 통과하는지 재확인.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/stats/application/port/output/InvestmentPointsPort.java \
        src/main/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapter.java \
        src/main/java/com/kista/stats/application/service/StatsService.java \
        src/test/java/com/kista/stats/application/service/StatsServiceTest.java \
        src/test/java/com/kista/stats/adapter/out/internal/InvestmentPointsHttpAdapterTest.java
git commit -m "refactor(stats): InvestmentPointsPort.Scope 삭제, BenchmarkScope로 통합"
```

---

## 최종 검증

모든 태스크 완료 후 다음을 실행한다:

- [ ] `bash gradlew test` — 루트+trading-core 전체 테스트 그린, `ApplicationModules.verify()` 포함.
- [ ] `grep -rn "com.kista.trading.domain.model.Order\b\|com.kista.trading.domain.model.Strategy\b" src/main/java/com/kista/admin src/main/java/com/kista/stats --include="*.java"` — 매치 없음(남아있다면 이 계획이 놓친 참조가 있다는 뜻이니 원인 파악 후 처리).
- [ ] `grep -rn "matching.domain.model.OrderTiming" src trading-core --include="*.java"` — 매치 없음.
- [ ] spec 문서의 "미해결/후속" 절(AdminStrategyService pause/resume)이 여전히 유효한지 확인 — 이 계획에서 건드리지 않았으므로 그대로 남아있어야 한다.
