# Cycle History Cursor-Based Pagination

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `trading_cycle_history` 조회 API에 커서 기반 페이지네이션을 도입해 대용량 데이터 조회 시 성능 문제를 방지한다.

**Architecture:** `createdAt DESC` 정렬 기준으로 ISO-8601 Instant 문자열을 커서로 사용한다. 첫 페이지는 커서 없이 요청하고, 응답의 `nextCursor`를 다음 요청에 `cursor` 파라미터로 전달한다. 백엔드는 `size+1`건을 조회해 `hasMore`를 판단하고 마지막 항목의 `createdAt`을 `nextCursor`로 반환한다. 프론트엔드는 `useInfiniteQuery`로 전환하고 "더 보기" 버튼으로 추가 로드한다.

**Tech Stack:** Spring Data JPA (Pageable), Java records, React Query `useInfiniteQuery`, TypeScript

---

## 파일 구조

### 백엔드 (kista-api)

| 파일 | 변경 유형 | 역할 |
|------|---------|------|
| `domain/model/tradingcycle/CycleHistoryPage.java` | **신규** | 페이지 결과 도메인 record |
| `domain/port/out/TradingCycleHistoryPort.java` | **수정** | 커서 조회 메서드 추가 |
| `domain/port/in/GetAccountStatisticsUseCase.java` | **수정** | 페이지네이션 파라미터 추가 |
| `adapter/out/persistence/tradingcycle/TradingCycleHistoryJpaRepository.java` | **수정** | 커서 쿼리 추가 |
| `adapter/out/persistence/tradingcycle/TradingCycleHistoryPersistenceAdapter.java` | **수정** | 커서 조회 구현 |
| `application/service/AccountStatisticsService.java` | **수정** | `toPage()` 헬퍼, 기존 메서드 → 페이지 반환 |
| `adapter/in/web/dto/CycleHistoryPageResponse.java` | **신규** | 컨트롤러 응답 DTO |
| `adapter/in/web/KisStatisticsController.java` | **수정** | `cursor`, `size` 파라미터 추가, 응답 타입 변경 |
| `adapter/in/web/TradingCycleController.java` | **수정** | 동일 |
| `adapter/in/web/StatisticsControllerTest.java` | **수정** | 페이지 응답 검증 테스트 |
| `adapter/in/web/TradingCycleControllerTest.java` | **수정** | 동일 |

### 프론트엔드 (kista-ui)

| 파일 | 변경 유형 | 역할 |
|------|---------|------|
| `entities/trade/model/types.ts` | **수정** | `CycleHistoryPage` 타입 추가 |
| `entities/trade/api/index.ts` | **수정** | 반환 타입 `CycleHistoryPage`로 변경 |
| `entities/trade/hooks/useCycleHistory.ts` | **수정** | `useInfiniteQuery`로 전환 |
| `widgets/account-detail/CycleHistoryTable.tsx` | **수정** | "더 보기" 버튼, `hasNextPage`/`isFetchingNextPage` prop 추가 |
| `widgets/account-detail/TradesTab.tsx` | **수정** | 새 훅 시그니처에 맞게 prop 전달 |
| `widgets/account-detail/StrategyTradesTab.tsx` | **수정** | 동일 |

---

## Task 1: 도메인 record — `CycleHistoryPage`

**Files:**
- Create: `kista-api/src/main/java/com/kista/domain/model/tradingcycle/CycleHistoryPage.java`

- [ ] **Step 1: 파일 생성**

```java
package com.kista.domain.model.tradingcycle;

import java.time.Instant;
import java.util.List;

// 커서 기반 페이지네이션 결과 — items + 다음 페이지 커서
public record CycleHistoryPage(
        List<AccountCycleHistoryEntry> items,
        Instant nextCursor,  // null이면 마지막 페이지
        boolean hasMore
) {}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 2: JPA Repository — 커서 쿼리 추가

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryJpaRepository.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`TradingCycleHistoryJpaRepository`는 package-private이라 직접 테스트하지 않는다. 대신 Adapter 레벨에서 통합 테스트하므로 컴파일 검증으로 대체한다.

- [ ] **Step 2: 커서 쿼리 두 개 추가**

기존 파일 마지막 `findBetweenDates` 쿼리 뒤에 추가:

```java
// 계좌 기준 커서 페이지네이션 — createdAt < cursor AND createdAt >= from, DESC
@Query("SELECT tch FROM TradingCycleHistoryEntity tch " +
       "WHERE tch.tradingCycleId IN " +
       "(SELECT tc.id FROM TradingCycleEntity tc WHERE tc.accountId = :accountId) " +
       "AND tch.createdAt >= :from AND tch.createdAt < :cursor " +
       "ORDER BY tch.createdAt DESC")
List<TradingCycleHistoryEntity> findByAccountIdWithCursor(
        @Param("accountId") UUID accountId,
        @Param("from") Instant from,
        @Param("cursor") Instant cursor,
        Pageable pageable);

// 전략(사이클) 기준 커서 페이지네이션
@Query("SELECT tch FROM TradingCycleHistoryEntity tch " +
       "WHERE tch.tradingCycleId = :cycleId " +
       "AND tch.createdAt >= :from AND tch.createdAt < :cursor " +
       "ORDER BY tch.createdAt DESC")
List<TradingCycleHistoryEntity> findByCycleIdWithCursor(
        @Param("cycleId") UUID cycleId,
        @Param("from") Instant from,
        @Param("cursor") Instant cursor,
        Pageable pageable);
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 3: Port 인터페이스 — 커서 조회 메서드 추가

**Files:**
- Modify: `kista-api/src/main/java/com/kista/domain/port/out/TradingCycleHistoryPort.java`

- [ ] **Step 1: 두 메서드 추가**

기존 `findByCycleIdAndDateRange` 아래에 추가:

```java
// 커서 기반 페이지 조회 — limit건 반환 (hasMore 판단용으로 limit+1 전달 권장)
List<AccountCycleHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from, Instant cursor, int limit);

List<AccountCycleHistoryEntry> findByCycleIdWithCursor(UUID cycleId, Instant from, Instant cursor, int limit);
```

- [ ] **Step 2: 컴파일 실패 확인 (Adapter 미구현)**

```bash
./gradlew compileJava
```

Expected: `FAILED` — `TradingCycleHistoryPersistenceAdapter` 미구현 오류

---

## Task 4: Persistence Adapter — 커서 조회 구현

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryPersistenceAdapter.java`

- [ ] **Step 1: 두 메서드 구현**

`findBetween` 메서드 뒤에 추가:

```java
@Override
public List<AccountCycleHistoryEntry> findByAccountIdWithCursor(UUID accountId, Instant from,
                                                                 Instant cursor, int limit) {
    Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapByAccountId(accountId);
    return jpaRepository.findByAccountIdWithCursor(accountId, from, cursor, PageRequest.of(0, limit))
            .stream().map(e -> toEntry(e, tickerMap)).toList();
}

@Override
public List<AccountCycleHistoryEntry> findByCycleIdWithCursor(UUID cycleId, Instant from,
                                                               Instant cursor, int limit) {
    TradingCycle.Ticker ticker = cycleJpaRepository.findById(cycleId)
            .map(TradingCycleEntity::getTicker).orElse(null);
    Map<UUID, TradingCycle.Ticker> tickerMap = ticker != null ? Map.of(cycleId, ticker) : Map.of();
    return jpaRepository.findByCycleIdWithCursor(cycleId, from, cursor, PageRequest.of(0, limit))
            .stream().map(e -> toEntry(e, tickerMap)).toList();
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 5: UseCase 인터페이스 — 페이지네이션 파라미터 추가

**Files:**
- Modify: `kista-api/src/main/java/com/kista/domain/port/in/GetAccountStatisticsUseCase.java`

- [ ] **Step 1: import 추가 및 메서드 시그니처 변경**

파일 상단 import에 추가:
```java
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import java.time.Instant;
```

기존 두 메서드를 아래로 교체:

```java
// cursor=null이면 to 기준으로 첫 페이지, size 최대 200
CycleHistoryPage getCycleHistory(UUID accountId, UUID requesterId,
                                  LocalDate from, LocalDate to,
                                  Instant cursor, int size);

CycleHistoryPage getStrategyCycleHistory(UUID strategyId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size);
```

- [ ] **Step 2: 컴파일 실패 확인 (Service 미구현)**

```bash
./gradlew compileJava
```

Expected: `FAILED` — `AccountStatisticsService` 미구현 오류

---

## Task 6: Service — 커서 페이지 로직 구현

**Files:**
- Modify: `kista-api/src/main/java/com/kista/application/service/AccountStatisticsService.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`StatisticsControllerTest.java`에 추가 (컨트롤러 레벨 테스트 — Task 7에서 함께 작성):

현재 단계에서는 컴파일 오류 수정이 목적이므로 구현 먼저 진행한다.

- [ ] **Step 2: 기존 메서드 교체 및 `toPage` 헬퍼 추가**

`getCycleHistory`, `getStrategyCycleHistory`, `resolveFrom`, `resolveTo`를 아래로 교체:

```java
@Override
public CycleHistoryPage getCycleHistory(UUID accountId, UUID requesterId,
                                         LocalDate from, LocalDate to,
                                         Instant cursor, int size) {
    Account account = requireOwnedAccount(accountId, requesterId);
    Instant fromInstant = resolveFrom(from);
    // cursor 없으면 to(=내일)가 상한 — cursor는 그 이전 지점으로 좁혀감
    Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
    List<AccountCycleHistoryEntry> raw =
            tradingCycleHistoryPort.findByAccountIdWithCursor(accountId, fromInstant, effectiveCursor, size + 1);
    return toPage(raw, size);
}

@Override
public CycleHistoryPage getStrategyCycleHistory(UUID strategyId, UUID requesterId,
                                                  LocalDate from, LocalDate to,
                                                  Instant cursor, int size) {
    var cycle = tradingCyclePort.findByIdOrThrow(strategyId);
    Account account = requireOwnedAccount(cycle.accountId(), requesterId);
    Instant fromInstant = resolveFrom(from);
    Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
    List<AccountCycleHistoryEntry> raw =
            tradingCycleHistoryPort.findByCycleIdWithCursor(strategyId, fromInstant, effectiveCursor, size + 1);
    return toPage(raw, size);
}

// size+1 조회 결과로 hasMore 판단 후 CycleHistoryPage 생성
private CycleHistoryPage toPage(List<AccountCycleHistoryEntry> raw, int size) {
    boolean hasMore = raw.size() > size;
    List<AccountCycleHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
    // 다음 커서 = 현재 페이지 마지막 항목의 createdAt (DESC 정렬이므로 가장 오래된 것)
    Instant nextCursor = hasMore ? items.get(items.size() - 1).createdAt() : null;
    return new CycleHistoryPage(items, nextCursor, hasMore);
}

// null이면 전체 기간 — Epoch(1970-01-01)부터 조회
private Instant resolveFrom(LocalDate from) {
    return from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
}

// null이면 오늘 + 1일 (오늘 데이터 포함)
private Instant resolveTo(LocalDate to) {
    var resolved = to != null ? to : LocalDate.now(ZoneOffset.UTC);
    return resolved.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
}
```

`CycleHistoryPage` import 추가:
```java
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 7: 응답 DTO — `CycleHistoryPageResponse`

**Files:**
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/dto/CycleHistoryPageResponse.java`

- [ ] **Step 1: DTO 생성**

```java
package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.domain.model.tradingcycle.CycleHistoryPage;

import java.util.List;

// 거래내역 페이지 응답 — items + 커서 기반 다음 페이지 정보
public record CycleHistoryPageResponse(
        List<CycleHistoryResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor, // null이면 마지막 페이지
        boolean hasMore
) {
    public static CycleHistoryPageResponse from(CycleHistoryPage page) {
        return new CycleHistoryPageResponse(
                page.items().stream().map(CycleHistoryResponse::from).toList(),
                page.nextCursor() != null ? page.nextCursor().toString() : null,
                page.hasMore()
        );
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 8: Controller — 파라미터 및 응답 타입 변경 + 테스트

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/KisStatisticsController.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/TradingCycleController.java`
- Modify: `kista-api/src/test/java/com/kista/adapter/in/web/StatisticsControllerTest.java`
- Modify: `kista-api/src/test/java/com/kista/adapter/in/web/TradingCycleControllerTest.java`

- [ ] **Step 1: `StatisticsControllerTest`에 실패하는 테스트 추가**

기존 `cycleHistory_returns_200_with_date_params`, `cycleHistory_returns_200_without_date_params` 테스트를 아래로 교체:

먼저 import 추가:
```java
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import java.time.Instant;
```

```java
@Test
void cycleHistory_returns_page_with_date_params() throws Exception {
    var page = new CycleHistoryPage(List.of(), null, false);
    when(statisticsUseCase.getCycleHistory(eq(ACCOUNT_ID), any(), any(), any(), isNull(), eq(50)))
            .thenReturn(page);

    mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                    .param("from", "2024-01-01").param("to", "2024-12-31")
                    .with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.hasMore").value(false));
}

@Test
void cycleHistory_returns_page_without_date_params() throws Exception {
    // '전체' 선택 시 from/to 없어도 200 반환
    var page = new CycleHistoryPage(List.of(), null, false);
    when(statisticsUseCase.getCycleHistory(eq(ACCOUNT_ID), any(), isNull(), isNull(), isNull(), eq(50)))
            .thenReturn(page);

    mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                    .with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
}

@Test
void cycleHistory_returns_nextCursor_when_hasMore() throws Exception {
    Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
    var page = new CycleHistoryPage(List.of(), cursor, true);
    when(statisticsUseCase.getCycleHistory(eq(ACCOUNT_ID), any(), any(), any(), any(), eq(50)))
            .thenReturn(page);

    mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                    .param("from", "2024-01-01").param("to", "2024-12-31")
                    .with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
}
```

- [ ] **Step 2: 테스트 실행 — RED 확인**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.StatisticsControllerTest.cycleHistory*'
```

Expected: `FAILED` (컨트롤러 반환 타입 불일치)

- [ ] **Step 3: `KisStatisticsController` — `getCycleHistory` 수정**

기존 `getCycleHistory` 메서드를 아래로 교체:

```java
@GetMapping("/cycle-history")
public CycleHistoryPageResponse getCycleHistory(
        @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        @PathVariable UUID accountId,
        @AuthenticationPrincipal UUID userId,
        @Parameter(description = "조회 시작일 (생략 시 전체)")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @Parameter(description = "조회 종료일 (생략 시 전체)")
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Parameter(description = "커서 (이전 응답의 nextCursor)")
        @RequestParam(required = false) String cursor,
        @Parameter(description = "페이지 크기 (기본 50, 최대 200)")
        @RequestParam(defaultValue = "50") int size) {
    try {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        // @Validated 없이는 @Max가 동작하지 않으므로 서비스 호출 전 직접 상한 적용
        CycleHistoryPage page = statisticsUseCase.getCycleHistory(accountId, userId, from, to, cursorInstant, Math.min(size, 200));
        return CycleHistoryPageResponse.from(page);
    } catch (SecurityException e) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    }
}
```

필요한 import 추가:
```java
import com.kista.adapter.in.web.dto.CycleHistoryPageResponse;
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import java.time.Instant;
```

- [ ] **Step 4: `TradingCycleController` — `getStrategyHistory` 수정**

기존 `getStrategyHistory` 메서드를 아래로 교체:

```java
@GetMapping("/api/trading-cycles/{strategyId}/history")
public CycleHistoryPageResponse getStrategyHistory(
        @PathVariable UUID strategyId,
        @AuthenticationPrincipal UUID userId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") int size) {
    try {
        Instant cursorInstant = cursor != null ? Instant.parse(cursor) : null;
        CycleHistoryPage page = statisticsUseCase.getStrategyCycleHistory(
                strategyId, userId, from, to, cursorInstant, Math.min(size, 200));
        return CycleHistoryPageResponse.from(page);
    } catch (SecurityException e) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (NoSuchElementException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
```

필요한 import 추가:
```java
import com.kista.adapter.in.web.dto.CycleHistoryPageResponse;
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import java.time.Instant;
```

- [ ] **Step 5: `TradingCycleControllerTest` — 실패하는 테스트 추가**

기존 `strategyHistory_returns_200_with_date_params`, `strategyHistory_returns_200_without_date_params` 테스트를 아래로 교체:

import 추가:
```java
import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import java.time.Instant;
```

```java
@Test
void strategyHistory_returns_page_with_date_params() throws Exception {
    var page = new CycleHistoryPage(List.of(), null, false);
    when(statisticsUseCase.getStrategyCycleHistory(eq(CYCLE_ID), any(), any(), any(), isNull(), eq(50)))
            .thenReturn(page);

    mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                    .param("from", "2024-01-01").param("to", "2024-12-31")
                    .with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.hasMore").value(false));
}

@Test
void strategyHistory_returns_page_without_date_params() throws Exception {
    var page = new CycleHistoryPage(List.of(), null, false);
    when(statisticsUseCase.getStrategyCycleHistory(eq(CYCLE_ID), any(), isNull(), isNull(), isNull(), eq(50)))
            .thenReturn(page);

    mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                    .with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
}

@Test
void strategyHistory_returns_nextCursor_when_hasMore() throws Exception {
    Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
    var page = new CycleHistoryPage(List.of(), cursor, true);
    when(statisticsUseCase.getStrategyCycleHistory(eq(CYCLE_ID), any(), any(), any(), any(), eq(50)))
            .thenReturn(page);

    mockMvc.perform(get("/api/trading-cycles/{id}/history", CYCLE_ID)
                    .param("from", "2024-01-01").param("to", "2024-12-31")
                    .with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
}
```

- [ ] **Step 6: 전체 테스트 실행 — GREEN 확인**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.StatisticsControllerTest' \
               --tests 'com.kista.adapter.in.web.TradingCycleControllerTest'
```

Expected: `BUILD SUCCESSFUL` (모든 테스트 통과)

- [ ] **Step 7: 전체 테스트 회귀 확인**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add src/
git commit -m "feat: cycle-history API 커서 기반 페이지네이션 (기본 50건, 최대 200건)

trading_cycle_history 조회 시 전체 데이터를 반환하던 구조를
커서 기반 페이지네이션으로 변경.
응답 형태: { items, nextCursor, hasMore }
cursor 파라미터로 다음 페이지 조회, 없으면 첫 페이지."
```

---

## Task 9: 프론트엔드 — 타입 및 API 함수 업데이트

**Files:**
- Modify: `kista-ui/entities/trade/model/types.ts`
- Modify: `kista-ui/entities/trade/api/index.ts`

- [ ] **Step 1: `types.ts`에 `CycleHistoryPage` 타입 추가**

기존 `CycleHistoryItem` 인터페이스 아래에 추가:

```typescript
export interface CycleHistoryPage {
  items: CycleHistoryItem[]
  nextCursor: string | null
  hasMore: boolean
}
```

- [ ] **Step 2: `api/index.ts` — `getAccountCycleHistory`, `getStrategyCycleHistory` 반환 타입 변경**

import 교체:
```typescript
import type {
  TradeHistory,
  Execution,
  CycleHistoryItem,
  CycleHistoryPage,
  DailyTransactionResult,
  PortfolioSnapshot,
  ProfitSummary,
  MarginItem,
} from '../model/types'
```

`buildDateQuery` 함수는 그대로 유지하고, 아래 함수를 **새로 추가**한다 (기존 `getTrades`, `getPortfolioSnapshots`는 `buildDateQuery`를 계속 사용):
```typescript
function buildCycleHistoryQuery(params: {
  from?: string
  to?: string
  cursor?: string
  size?: number
}): string {
  const q = new URLSearchParams()
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  if (params.cursor) q.set('cursor', params.cursor)
  if (params.size != null) q.set('size', String(params.size))
  return q.size ? `?${q}` : ''
}
```

`getAccountCycleHistory` 교체:
```typescript
export async function getAccountCycleHistory(
  accountId: string,
  params: { from?: string; to?: string; cursor?: string; size?: number },
  token?: string
): Promise<CycleHistoryPage> {
  const qs = buildCycleHistoryQuery(params)
  if (token) return apiFetch<CycleHistoryPage>(`/api/accounts/${accountId}/cycle-history${qs}`, { method: 'GET' }, token)
  return clientFetch<CycleHistoryPage>(`/api/accounts/${accountId}/cycle-history${qs}`)
}
```

`getStrategyCycleHistory` 교체:
```typescript
export async function getStrategyCycleHistory(
  strategyId: string,
  params: { from?: string; to?: string; cursor?: string; size?: number },
  token?: string
): Promise<CycleHistoryPage> {
  const qs = buildCycleHistoryQuery(params)
  if (token) return apiFetch<CycleHistoryPage>(`/api/trading-cycles/${strategyId}/history${qs}`, { method: 'GET' }, token)
  return clientFetch<CycleHistoryPage>(`/api/trading-cycles/${strategyId}/history${qs}`)
}
```

- [ ] **Step 3: 타입 검사**

```bash
npm run typecheck
```

Expected: 오류 없음 (훅에서 타입 불일치 오류 발생하면 Task 10에서 해결됨)

---

## Task 10: 프론트엔드 — 훅 `useInfiniteQuery`로 전환

**Files:**
- Modify: `kista-ui/entities/trade/hooks/useCycleHistory.ts`

- [ ] **Step 1: 훅 전체 교체**

```typescript
'use client'

import { useInfiniteQuery } from '@tanstack/react-query'
import { getAccountCycleHistory, getStrategyCycleHistory } from '../api'
import type { CycleHistoryItem, CycleHistoryPage } from '../model/types'

type DateParams = { from?: string; to?: string } | null

const EMPTY_PAGE: CycleHistoryPage = { items: [], nextCursor: null, hasMore: false }

export function useAccountCycleHistoryQuery(accountId: string, params: DateParams) {
  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useInfiniteQuery<CycleHistoryPage>({
      queryKey: ['accountCycleHistory', accountId, params],
      queryFn: ({ pageParam }) =>
        getAccountCycleHistory(accountId, {
          ...(params ?? {}),
          cursor: pageParam as string | undefined,
        }).catch(() => EMPTY_PAGE),
      initialPageParam: undefined,
      getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
      enabled: params !== null,
      placeholderData: (prev) => prev,
    })

  const cycleHistory: CycleHistoryItem[] = data?.pages.flatMap((p) => p.items) ?? []
  return { cycleHistory, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage }
}

export function useStrategyCycleHistoryQuery(strategyId: string | undefined, params: DateParams) {
  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useInfiniteQuery<CycleHistoryPage>({
      queryKey: ['strategyCycleHistory', strategyId, params],
      queryFn: ({ pageParam }) =>
        getStrategyCycleHistory(strategyId!, {
          ...(params ?? {}),
          cursor: pageParam as string | undefined,
        }).catch(() => EMPTY_PAGE),
      initialPageParam: undefined,
      getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
      enabled: params !== null && !!strategyId,
      placeholderData: (prev) => prev,
    })

  const cycleHistory: CycleHistoryItem[] = data?.pages.flatMap((p) => p.items) ?? []
  return { cycleHistory, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage }
}
```

- [ ] **Step 2: 타입 검사**

```bash
npm run typecheck
```

Expected: `hooks/useCycleHistory.ts` 오류 없음 (CycleHistoryTable prop 불일치는 Task 11에서 해결)

---

## Task 11: 프론트엔드 — `CycleHistoryTable` "더 보기" 버튼 추가

**Files:**
- Modify: `kista-ui/widgets/account-detail/CycleHistoryTable.tsx`

- [ ] **Step 1: Props 인터페이스 확장 및 "더 보기" 버튼 추가**

`Props` 인터페이스 교체:
```typescript
interface Props {
  title: string
  cycleHistory: CycleHistoryItem[]
  isLoading: boolean
  rangeType: RangeType
  setRangeType: (r: RangeType) => void
  customFrom: string
  setCustomFrom: (v: string) => void
  customTo: string
  setCustomTo: (v: string) => void
  hasNextPage?: boolean
  isFetchingNextPage?: boolean
  fetchNextPage?: () => void
}
```

함수 시그니처 교체:
```typescript
export function CycleHistoryTable({
  title,
  cycleHistory,
  isLoading,
  rangeType,
  setRangeType,
  customFrom,
  setCustomFrom,
  customTo,
  setCustomTo,
  hasNextPage,
  isFetchingNextPage,
  fetchNextPage,
}: Props) {
```

데이터가 있을 때 렌더링되는 `<>` 블록 안 모바일/데스크탑 div 아래에 "더 보기" 버튼을 추가한다.
`CardContent` 전체 구조는 아래와 같이 된다:

```tsx
<CardContent className="p-0">
  {isLoading ? (
    <div ...>로딩 중...</div>
  ) : cycleHistory.length === 0 ? (
    <p ...>거래 내역이 없습니다.</p>
  ) : (
    <>
      {/* 모바일: 카드 리스트 — max-h 제거 */}
      <div className="space-y-2 p-4 lg:hidden">
        {/* 기존 카드 목록 */}
      </div>
      {/* 데스크탑: 테이블 — max-h 제거 */}
      <div className="hidden lg:block">
        {/* 기존 테이블 */}
      </div>
      {/* 더 보기 버튼 — 모바일·데스크탑 공통 */}
      {(hasNextPage || isFetchingNextPage) && (
        <div className="flex justify-center py-4 border-t">
          <button
            type="button"
            onClick={fetchNextPage}
            disabled={isFetchingNextPage}
            className="px-4 py-2 text-sm font-medium text-rose-600 hover:text-rose-700 disabled:opacity-50"
          >
            {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
          </button>
        </div>
      )}
    </>
  )}
</CardContent>
```

기존 `max-h-[440px]`은 두 div 모두에서 제거한다 (페이지네이션이 생겼으므로 불필요).

- [ ] **Step 2: 타입 검사**

```bash
npm run typecheck
```

Expected: TradesTab·StrategyTradesTab에서 prop 불일치 오류 (Task 12에서 해결)

---

## Task 12: 프론트엔드 — Tab 컴포넌트 업데이트 + 최종 검증

**Files:**
- Modify: `kista-ui/widgets/account-detail/TradesTab.tsx`
- Modify: `kista-ui/widgets/account-detail/StrategyTradesTab.tsx`

- [ ] **Step 1: `TradesTab.tsx` 수정**

`useAccountCycleHistory` 훅 호출 결과에서 새 값 추출 후 전달:

```typescript
const { cycleHistory, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
  useAccountCycleHistory(accountId, params)

return (
  <CycleHistoryTable
    title="거래 내역 (계좌)"
    cycleHistory={cycleHistory}
    isLoading={isLoading}
    rangeType={rangeType}
    setRangeType={(r) => dispatch({ type: 'SET_RANGE', rangeType: r })}
    customFrom={customFrom}
    setCustomFrom={(v) => dispatch({ type: 'SET_CUSTOM_FROM', value: v })}
    customTo={customTo}
    setCustomTo={(v) => dispatch({ type: 'SET_CUSTOM_TO', value: v })}
    hasNextPage={hasNextPage}
    isFetchingNextPage={isFetchingNextPage}
    fetchNextPage={fetchNextPage}
  />
)
```

- [ ] **Step 2: `StrategyTradesTab.tsx` 수정**

`useStrategyCycleHistory` 훅 호출 부분:

```typescript
const { cycleHistory, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
  useStrategyCycleHistory(strategyId, params)
```

`CycleHistoryTable`에 동일하게 prop 전달:
```typescript
hasNextPage={hasNextPage}
isFetchingNextPage={isFetchingNextPage}
fetchNextPage={fetchNextPage}
```

- [ ] **Step 3: 최종 타입 검사**

```bash
npm run typecheck
```

Expected: 오류 없음

- [ ] **Step 4: re-export shim 확인**

```bash
grep -r "useCycleHistory\|getAccountCycleHistory\|getStrategyCycleHistory" \
  kista-ui/lib kista-ui/hooks 2>/dev/null
```

shim 파일이 있다면 반환 타입 변경 여파 확인. `lib/api/trades.ts`, `hooks/useCycleHistory.ts`는 re-export shim이므로 별도 수정 불필요.

- [ ] **Step 5: 커밋**

```bash
cd kista-ui
git add entities/trade/ widgets/account-detail/
git commit -m "feat: 거래내역 커서 기반 페이지네이션 — useInfiniteQuery + 더 보기 버튼"
```

---

## 검증

- [ ] 백엔드 서버 기동 후 `GET /api/accounts/{id}/cycle-history` — 응답에 `items`, `hasMore`, `nextCursor` 포함 확인
- [ ] `size=2` 파라미터로 2건만 조회되고 `hasMore: true`, `nextCursor` 반환 확인
- [ ] 반환된 `nextCursor` 값으로 다음 페이지 요청 시 이전 페이지와 겹치지 않는 결과 확인
- [ ] `from`/`to` 없이 요청 시 전체 기간 조회 (Task 8의 `전체` 버그 수정과 호환)
- [ ] 프론트엔드 "더 보기" 버튼 클릭 시 다음 페이지 데이터 append 확인
- [ ] 마지막 페이지에서 "더 보기" 버튼 미표시 확인
