# 바로주문/미리보기 정리 (API) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `NextOrdersPreview`의 도메인 필드명을 DTO(`NextOrdersResponse.todayOrders`)와 일치시켜 경계를 넘을 때 이름이 바뀌는 지점을 없앤다.

**Architecture:** 순수 리네임 — 동작 변화 없음. `domain/model/order/NextOrdersPreview.java`의 record 필드 `todayPlannedOrders` → `todayOrders`, 이를 참조하는 `application/service/trading/TradingPreviewService.java`(생성자 호출)와 `adapter/in/web/dto/NextOrdersResponse.java`(accessor 호출)만 따라간다. JSON 응답 키(`todayOrders`)는 이미 동일해서 변경 없음 — kista-ui 영향 없음.

**Tech Stack:** Java 21, Spring Boot 3, Gradle.

## Global Constraints

- 이번 작업은 순수 리네임이라 동작 변경이 없다 — 새 테스트를 억지로 추가하지 않고, 기존 테스트 스위트가 그대로 통과하는지로 검증한다(TDD의 "동작 변경 없는 리팩터" 케이스).
- record 생성자 호출은 모두 위치 기반(positional)이라 필드 순서를 바꾸지 않는 한 시그니처 자체는 그대로다 — 이름만 바뀐다.
- 커밋 메시지는 한글, `refactor:` 접두사.

---

### Task 1: `todayPlannedOrders` → `todayOrders` 필드명 통일

**Files:**
- Modify: `src/main/java/com/kista/domain/model/order/NextOrdersPreview.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingPreviewService.java`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java`

**Interfaces:**
- 변경 없음 — `NextOrdersPreview`의 record 필드 순서·타입·`NextOrdersResponse.from()`의 시그니처 모두 그대로. 필드 접근자 이름만 `todayPlannedOrders()` → `todayOrders()`로 바뀐다.

- [ ] **Step 1: 현재 참조 지점 확인**

Run: `grep -rn "todayPlannedOrders" src/`
Expected: 아래 5곳만 출력됨 (그 외 있으면 이 단계에서 발견해 아래 Step에 추가로 반영)
```
src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java:169:                result.todayPlannedOrders().stream().map(TodayOrderItem::from).toList(),
src/main/java/com/kista/application/service/trading/TradingPreviewService.java:51:        List<Order> todayPlannedOrders =
src/main/java/com/kista/application/service/trading/TradingPreviewService.java:56:        BigDecimal thisStrategyPlannedBuy = todayPlannedOrders.stream()
src/main/java/com/kista/application/service/trading/TradingPreviewService.java:65:            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayPlannedOrders, otherStrategiesPlannedBuyUsd, null);
src/main/java/com/kista/domain/model/order/NextOrdersPreview.java:14:        List<Order> todayPlannedOrders,                  // 오늘 이미 등록된 PLANNED 주문 (없으면 빈 리스트)
src/main/java/com/kista/application/service/trading/TradingPreviewService.java:77:        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayPlannedOrders, otherStrategiesPlannedBuyUsd, competition);
```

- [ ] **Step 2: `NextOrdersPreview.java` record 필드 리네임**

`src/main/java/com/kista/domain/model/order/NextOrdersPreview.java` 현재:
```java
public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,                       // PRIVACY/skip 시 null
        List<Order> orders,                              // NO_CYCLE_HISTORY/NO_PRIVACY_BASE skip 시 빈 리스트
        SkipReason skipReason,                             // 정상이면 null
        List<Order> todayPlannedOrders,                  // 오늘 이미 등록된 PLANNED 주문 (없으면 빈 리스트)
        BigDecimal otherStrategiesPlannedBuyUsd,          // 계좌 내 타 전략 당일 PLANNED BUY 합계
        BuyCompetitionPreview competition                // 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (BUY 없으면 null)
) {
```

변경 후:
```java
public record NextOrdersPreview(
        LocalDate tradeDate,
        InfinitePosition position,                       // PRIVACY/skip 시 null
        List<Order> orders,                              // NO_CYCLE_HISTORY/NO_PRIVACY_BASE skip 시 빈 리스트
        SkipReason skipReason,                             // 정상이면 null
        List<Order> todayOrders,                          // 오늘 이미 등록된 PLANNED·PLACED 주문 (없으면 빈 리스트) — DTO(NextOrdersResponse.todayOrders)와 이름 통일
        BigDecimal otherStrategiesPlannedBuyUsd,          // 계좌 내 타 전략 당일 PLANNED BUY 합계
        BuyCompetitionPreview competition                // 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 (BUY 없으면 null)
) {
```

- [ ] **Step 3: `TradingPreviewService.java` 로컬 변수·생성자 호출 리네임**

`src/main/java/com/kista/application/service/trading/TradingPreviewService.java` 현재(51행):
```java
        List<Order> todayPlannedOrders =
                orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
```
변경 후:
```java
        List<Order> todayOrders =
                orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
```

56행 현재:
```java
        BigDecimal thisStrategyPlannedBuy = todayPlannedOrders.stream()
```
변경 후:
```java
        BigDecimal thisStrategyPlannedBuy = todayOrders.stream()
```

65행 현재:
```java
            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayPlannedOrders, otherStrategiesPlannedBuyUsd, null);
```
변경 후:
```java
            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayOrders, otherStrategiesPlannedBuyUsd, null);
```

77행 현재:
```java
        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayPlannedOrders, otherStrategiesPlannedBuyUsd, competition);
```
변경 후:
```java
        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayOrders, otherStrategiesPlannedBuyUsd, competition);
```

- [ ] **Step 4: `NextOrdersResponse.java` accessor 호출 리네임**

`src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java` 169행 현재:
```java
                result.todayPlannedOrders().stream().map(TodayOrderItem::from).toList(),
```
변경 후:
```java
                result.todayOrders().stream().map(TodayOrderItem::from).toList(),
```

- [ ] **Step 5: 잔여 참조 없는지 재확인**

Run: `grep -rn "todayPlannedOrders" src/`
Expected: 출력 없음 (전부 치환 완료)

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 기존 테스트 스위트 회귀 없는지 확인**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingPreviewServiceTest' --tests 'com.kista.adapter.in.web.dto.NextOrdersResponseTest' --tests 'com.kista.application.service.trading.TradingExecutionFacadeTest'`
Expected: BUILD SUCCESSFUL, 3개 테스트 클래스 모두 기존과 동일하게 통과(신규 실패 없음) — 이 파일들은 `new NextOrdersPreview(...)`를 위치 기반으로 호출하므로 리네임 자체로는 깨지지 않아야 정상.

- [ ] **Step 8: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/kista/domain/model/order/NextOrdersPreview.java \
        src/main/java/com/kista/application/service/trading/TradingPreviewService.java \
        src/main/java/com/kista/adapter/in/web/dto/NextOrdersResponse.java
git commit -m "$(cat <<'EOF'
refactor(order): NextOrdersPreview.todayPlannedOrders를 todayOrders로 통일

DTO(NextOrdersResponse.todayOrders)와 이름이 어긋나던 도메인 필드를
통일 — JSON 응답 키는 이미 todayOrders라 kista-ui 영향 없음.
EOF
)"
```

## Self-Review

- 스펙 커버리지: 스펙의 "필드명 통일" 요구사항 하나뿐이며 Task 1이 전부 커버. `TradingBuyCompetitionSimulator`/`BuyCompetitionPreview`/컨트롤러/엔드포인트는 스펙에서도 무변경으로 명시 — 별도 태스크 불필요.
- 플레이스홀더 없음, 모든 Step에 실제 코드·명령어 포함.
- 타입 일관성: record 필드 순서·타입 불변, 시그니처 변경 없음 — 이 리네임으로 깨질 수 있는 곳은 accessor 호출(`.todayPlannedOrders()`) 뿐이며 Step 1에서 grep으로 전수 확인.
