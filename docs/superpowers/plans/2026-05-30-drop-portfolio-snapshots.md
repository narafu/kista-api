# portfolio_snapshots 제거 및 trading_cycle_history 통합 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `portfolio_snapshots` 테이블을 제거하고, `trading_cycle_history`에 `current_price` 컬럼을 추가해 두 테이블의 역할을 통합한다.

**Architecture:** `TradingCycleHistory`에 실행 시점 현재가(`currentPrice`)를 추가해 `marketValueUsd`/`totalAssetUsd`를 조회 시 computed value로 제공. `PortfolioSnapshotPort`를 완전 제거하고 `TradingCycleHistoryPort`의 신규 전역 조회 메서드가 `GetPortfolioUseCase`를 구현하는 `PortfolioService`를 통해 대시보드·텔레그램 봇에 데이터를 공급한다.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA (Hibernate 6), Flyway, PostgreSQL, Mockito

---

## 영향 파일 목록

### 생성
- `src/main/resources/db/migration/V49__add_current_price_to_trading_cycle_history.sql`
- `src/main/resources/db/migration/V50__drop_portfolio_snapshots.sql`

### 수정
- `src/main/java/com/kista/domain/model/tradingcycle/TradingCycleHistory.java`
- `src/main/java/com/kista/domain/model/tradingcycle/AccountCycleHistoryEntry.java`
- `src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryJpaRepository.java`
- `src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryPersistenceAdapter.java`
- `src/main/java/com/kista/domain/port/out/TradingCycleHistoryPort.java`
- `src/main/java/com/kista/domain/port/in/GetPortfolioUseCase.java`
- `src/main/java/com/kista/application/service/PortfolioService.java`
- `src/main/java/com/kista/adapter/in/web/dto/PortfolioSnapshotResponse.java`
- `src/main/java/com/kista/adapter/in/web/dto/CycleHistoryResponse.java`
- `src/main/java/com/kista/application/service/TradingService.java`
- `src/main/java/com/kista/application/service/TradingCycleService.java`
- `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java`
- `src/test/java/com/kista/application/service/PortfolioServiceTest.java`
- `src/test/java/com/kista/application/service/TradingServiceTest.java`
- `src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java`
- `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`

### 삭제
- `src/main/java/com/kista/domain/model/order/PortfolioSnapshot.java`
- `src/main/java/com/kista/domain/port/out/PortfolioSnapshotPort.java`
- `src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotEntity.java`
- `src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotJpaRepository.java`
- `src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotPersistenceAdapter.java`
- `src/test/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotPersistenceAdapterTest.java`

---

## Task 1: Flyway V49 — trading_cycle_history 재생성 (current_price 추가)

`current_price`를 `avg_price` 앞에 삽입한다. PostgreSQL은 `ADD COLUMN ... BEFORE/AFTER`를 지원하지 않으므로 테이블 재생성 패턴(V42/V47 패턴) 사용.  
현재 FK 이름: `trading_cycle_history_trading_cycle_id_fkey` (V47에서 명시됨)

**Files:**
- Create: `src/main/resources/db/migration/V49__add_current_price_to_trading_cycle_history.sql`

- [ ] **Step 1: V49 마이그레이션 작성**

```sql
-- V49: trading_cycle_history에 current_price 컬럼 추가 (avg_price 앞)
-- 컬럼 순서 목표: id, trading_cycle_id, usd_deposit, current_price, avg_price, holdings, created_at
-- 방식: FK 드롭 → 리네임 → 새 테이블 생성 → INSERT SELECT → DROP → FK + 인덱스 재생성

ALTER TABLE trading_cycle_history DROP CONSTRAINT trading_cycle_history_trading_cycle_id_fkey;

ALTER TABLE trading_cycle_history RENAME TO trading_cycle_history_old;

CREATE TABLE trading_cycle_history (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trading_cycle_id UUID          NOT NULL,
    usd_deposit      NUMERIC(20,2) NOT NULL,
    current_price    NUMERIC(12,2),
    avg_price        NUMERIC(20,2),
    holdings         INT           NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

INSERT INTO trading_cycle_history
       (id, trading_cycle_id, usd_deposit, current_price, avg_price, holdings, created_at)
SELECT  id, trading_cycle_id, usd_deposit, NULL,          avg_price, holdings, created_at
FROM trading_cycle_history_old;

DROP TABLE trading_cycle_history_old;

ALTER TABLE trading_cycle_history ADD CONSTRAINT trading_cycle_history_trading_cycle_id_fkey
    FOREIGN KEY (trading_cycle_id) REFERENCES trading_cycle(id) ON DELETE CASCADE;

CREATE INDEX idx_trading_cycle_history_cycle_id ON trading_cycle_history(trading_cycle_id);
```

- [ ] **Step 2: 컴파일·마이그레이션 검증 (앱 기동 확인)**

```bash
docker compose up -d postgres
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/resources/db/migration/V49__add_current_price_to_trading_cycle_history.sql
git commit -m "chore(db): trading_cycle_history에 current_price 컬럼 추가 (V49)"
```

---

## Task 2: 도메인 record 업데이트 — TradingCycleHistory + AccountCycleHistoryEntry

**Files:**
- Modify: `src/main/java/com/kista/domain/model/tradingcycle/TradingCycleHistory.java`
- Modify: `src/main/java/com/kista/domain/model/tradingcycle/AccountCycleHistoryEntry.java`

- [ ] **Step 1: TradingCycleHistory에 currentPrice 추가**

`avg_price` 앞, `usdDeposit` 뒤에 위치. nullable (`PRIVACY`는 현재가 미조회).

```java
package com.kista.domain.model.tradingcycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// TradingService.execute() 종료 시 1건 적재
public record TradingCycleHistory(
        UUID id,                // PK (null이면 @GeneratedValue)
        UUID tradingCycleId,    // FK → trading_cycle.id (UUID 간접 참조)
        BigDecimal usdDeposit,  // 통합주문가능금액 (매매 공식 B 계산 기준)
        BigDecimal currentPrice, // 실행 시점 현재가 (PRIVACY 또는 초기 등록 시 null)
        BigDecimal avgPrice,    // 평균 매입 단가 (보유수량 0이면 null)
        int holdings,           // 보유 수량
        Instant createdAt       // 생성 시각 (null이면 DB DEFAULT)
) {}
```

- [ ] **Step 2: AccountCycleHistoryEntry에 currentPrice 추가**

```java
package com.kista.domain.model.tradingcycle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// trading_cycle_history + trading_cycle join 조회 결과 (계좌 기준 이력 조회 전용)
public record AccountCycleHistoryEntry(
        UUID id,
        TradingCycle.Ticker ticker,     // trading_cycle.ticker
        BigDecimal usdDeposit,          // 통합주문가능금액
        BigDecimal currentPrice,        // 실행 시점 현재가 (null 가능)
        BigDecimal avgPrice,            // 평균 매입 단가 (보유수량 0이면 null)
        int holdings,                   // 보유 수량
        Instant createdAt              // 기록 시각
) {}
```

- [ ] **Step 3: 컴파일해서 참조 오류 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|ERROR" | head -30
```

Expected: `new TradingCycleHistory(...)` 호출처 3곳, `new AccountCycleHistoryEntry(...)` 호출처에서 컴파일 오류 발생 (다음 Task에서 수정).

---

## Task 3: TradingCycleHistoryEntity 업데이트

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryEntity.java`

- [ ] **Step 1: currentPrice 필드 추가 (avgPrice 앞)**

```java
package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "trading_cycle_history")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TradingCycleHistoryEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "trading_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID tradingCycleId; // FK → trading_cycle.id (UUID 간접 참조, ON DELETE CASCADE)

    @Column(name = "usd_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal usdDeposit; // 통합주문가능금액

    @Column(name = "current_price", precision = 12, scale = 2)
    private BigDecimal currentPrice; // 실행 시점 현재가 (PRIVACY 또는 초기 등록 시 null)

    @Column(name = "avg_price", precision = 20, scale = 2)
    private BigDecimal avgPrice; // 평균 매입 단가 (보유수량 0이면 null)

    @Column(name = "holdings", nullable = false)
    private int holdings; // 보유 수량 (양의 정수, 단주 단위)
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|ERROR" | head -20
```

Expected: entity 컴파일 통과. Adapter 쪽 오류는 다음 Task에서 수정.

---

## Task 4: TradingCycleHistoryPort + JpaRepository + PersistenceAdapter 업데이트

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/TradingCycleHistoryPort.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryJpaRepository.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/tradingcycle/TradingCycleHistoryPersistenceAdapter.java`

- [ ] **Step 1: TradingCycleHistoryPort에 전역 조회 메서드 추가**

```java
package com.kista.domain.port.out;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TradingCycleHistoryPort {
    TradingCycleHistory save(TradingCycleHistory history);

    List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit);

    // 계좌 ID 기준 이력 조회 (ticker 포함, 날짜 범위 필터)
    List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to);

    // 전체 이력 중 가장 최근 N건 (대시보드·텔레그램 현황 조회)
    List<AccountCycleHistoryEntry> findRecentGlobal(int limit);

    // 최근 N일 이력 전체 (차트용 시계열)
    List<AccountCycleHistoryEntry> findRecentDaysGlobal(int days);
}
```

- [ ] **Step 2: TradingCycleHistoryJpaRepository에 쿼리 추가**

```java
package com.kista.adapter.out.persistence.tradingcycle;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface TradingCycleHistoryJpaRepository extends JpaRepository<TradingCycleHistoryEntity, UUID> {

    List<TradingCycleHistoryEntity> findTop10ByTradingCycleIdOrderByCreatedAtDesc(UUID tradingCycleId);

    // 계좌 ID 기준 날짜 범위 조회 — TradingCycleEntity의 @SQLRestriction(deleted_at IS NULL)이 서브쿼리에도 적용됨
    @Query("SELECT tch FROM TradingCycleHistoryEntity tch " +
           "WHERE tch.tradingCycleId IN " +
           "(SELECT tc.id FROM TradingCycleEntity tc WHERE tc.accountId = :accountId) " +
           "AND tch.createdAt >= :from AND tch.createdAt < :to " +
           "ORDER BY tch.createdAt DESC")
    List<TradingCycleHistoryEntity> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // 전체 이력 최근 N건 — 대시보드·텔레그램 현황용
    List<TradingCycleHistoryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    // 특정 시점 이후 이력 전체 — 차트용 시계열
    @Query("SELECT tch FROM TradingCycleHistoryEntity tch " +
           "WHERE tch.createdAt >= :cutoff " +
           "ORDER BY tch.createdAt DESC")
    List<TradingCycleHistoryEntity> findRecentSinceCutoff(@Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 3: TradingCycleHistoryPersistenceAdapter 전체 교체**

```java
package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class TradingCycleHistoryPersistenceAdapter implements TradingCycleHistoryPort {

    private final TradingCycleHistoryJpaRepository jpaRepository;
    private final TradingCycleJpaRepository cycleJpaRepository; // ticker 조회용 (같은 패키지)

    @Override
    public TradingCycleHistory save(TradingCycleHistory history) {
        TradingCycleHistoryEntity entity = toEntity(history);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<TradingCycleHistory> findRecentByCycleId(UUID cycleId, int limit) {
        // limit 무시하고 top10 조회 — 단순 구현, 필요 시 @Query로 동적 limit 추가
        return jpaRepository.findTop10ByTradingCycleIdOrderByCreatedAtDesc(cycleId).stream()
                .limit(limit)
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findByAccountId(UUID accountId, Instant from, Instant to) {
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapByAccountId(accountId);
        return jpaRepository.findByAccountIdAndDateRange(accountId, from, to).stream()
                .map(e -> toEntry(e, tickerMap))
                .toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findRecentGlobal(int limit) {
        List<TradingCycleHistoryEntity> entities =
                jpaRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapFromEntities(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    @Override
    public List<AccountCycleHistoryEntry> findRecentDaysGlobal(int days) {
        Instant cutoff = Instant.now().atZone(ZoneId.of("Asia/Seoul")).minusDays(days).toInstant();
        List<TradingCycleHistoryEntity> entities = jpaRepository.findRecentSinceCutoff(cutoff);
        Map<UUID, TradingCycle.Ticker> tickerMap = buildTickerMapFromEntities(entities);
        return entities.stream().map(e -> toEntry(e, tickerMap)).toList();
    }

    // 계좌 ID로 사이클 목록을 조회해 cycleId → ticker 맵 구성
    private Map<UUID, TradingCycle.Ticker> buildTickerMapByAccountId(UUID accountId) {
        return cycleJpaRepository.findAllByAccountId(accountId).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));
    }

    // 이력 엔티티 목록의 cycleId 집합으로 ticker 맵 구성 (전역 조회용)
    private Map<UUID, TradingCycle.Ticker> buildTickerMapFromEntities(List<TradingCycleHistoryEntity> entities) {
        Set<UUID> cycleIds = entities.stream()
                .map(TradingCycleHistoryEntity::getTradingCycleId)
                .collect(Collectors.toSet());
        if (cycleIds.isEmpty()) return Map.of();
        return cycleJpaRepository.findAllById(cycleIds).stream()
                .collect(Collectors.toMap(TradingCycleEntity::getId, TradingCycleEntity::getTicker));
    }

    private AccountCycleHistoryEntry toEntry(TradingCycleHistoryEntity e,
                                             Map<UUID, TradingCycle.Ticker> tickerMap) {
        return new AccountCycleHistoryEntry(
                e.getId(),
                tickerMap.get(e.getTradingCycleId()),
                e.getUsdDeposit(),
                e.getCurrentPrice(),
                e.getAvgPrice(),
                e.getHoldings(),
                e.getCreatedAt()
        );
    }

    private TradingCycleHistory toDomain(TradingCycleHistoryEntity e) {
        return new TradingCycleHistory(
                e.getId(), e.getTradingCycleId(),
                e.getUsdDeposit(), e.getCurrentPrice(), e.getAvgPrice(),
                e.getHoldings(), e.getCreatedAt()
        );
    }

    private TradingCycleHistoryEntity toEntity(TradingCycleHistory h) {
        TradingCycleHistoryEntity e = new TradingCycleHistoryEntity();
        e.setId(h.id()); // null이면 @GeneratedValue가 UUID 생성
        e.setTradingCycleId(h.tradingCycleId());
        e.setUsdDeposit(h.usdDeposit());
        e.setCurrentPrice(h.currentPrice());
        e.setAvgPrice(h.avgPrice());
        e.setHoldings(h.holdings());
        return e;
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|ERROR" | head -30
```

Expected: 잔여 오류는 `new TradingCycleHistory(...)` 호출처와 Port 구현 미완료 서비스들. 다음 Task에서 해결.

---

## Task 5: GetPortfolioUseCase + PortfolioService 교체

**Files:**
- Modify: `src/main/java/com/kista/domain/port/in/GetPortfolioUseCase.java`
- Modify: `src/main/java/com/kista/application/service/PortfolioService.java`

- [ ] **Step 1: GetPortfolioUseCase 리턴 타입 변경**

```java
package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;

import java.util.List;

public interface GetPortfolioUseCase {
    AccountCycleHistoryEntry getCurrent();
    List<AccountCycleHistoryEntry> getSnapshots(int days);
}
```

- [ ] **Step 2: PortfolioService 교체 (PortfolioSnapshotPort → TradingCycleHistoryPort)**

```java
package com.kista.application.service;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class PortfolioService implements GetPortfolioUseCase {

    private final TradingCycleHistoryPort cycleHistoryPort;

    @Override
    public AccountCycleHistoryEntry getCurrent() {
        // 전체 이력 중 가장 최근 1건 반환
        return cycleHistoryPort.findRecentGlobal(1).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("포트폴리오 데이터가 없습니다."));
    }

    @Override
    public List<AccountCycleHistoryEntry> getSnapshots(int days) {
        return cycleHistoryPort.findRecentDaysGlobal(days);
    }
}
```

---

## Task 6: PortfolioSnapshotResponse DTO — AccountCycleHistoryEntry 기반으로 재작성

`marketValueUsd`/`totalAssetUsd`는 `currentPrice × holdings` / `marketValueUsd + usdDeposit`으로 computed. `currentPrice` null이면 0 처리 (PRIVACY/초기 등록 케이스).  
`snapshotDate`(LocalDate) 제거 — 대신 `createdAt`(Instant)에서 날짜 추출 가능.

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/dto/PortfolioSnapshotResponse.java`

- [ ] **Step 1: PortfolioSnapshotResponse 재작성**

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record PortfolioSnapshotResponse(
        @Schema(description = "이력 고유 ID")
        UUID id,
        @Schema(description = "거래 종목", example = "SOXL")
        Ticker ticker,
        @Schema(description = "보유 수량", example = "30")
        int holdings,
        @Schema(description = "실행 시점 현재가 (USD, null이면 PRIVACY 또는 초기 등록)", example = "26.00")
        BigDecimal currentPrice,
        @Schema(description = "평균매입단가 (USD, 보유수량 0이면 null)", example = "25.00")
        BigDecimal avgPrice,
        @Schema(description = "평가금액 (USD) = currentPrice × holdings (currentPrice null이면 0)", example = "780.00")
        BigDecimal marketValueUsd,
        @Schema(description = "예수금 (통합주문가능금액, USD)", example = "500.00")
        BigDecimal usdDeposit,
        @Schema(description = "총 자산 (평가금액 + 예수금, USD)", example = "1280.00")
        BigDecimal totalAssetUsd,
        @Schema(description = "기록 일시 (UTC)", example = "2025-01-15T07:00:00Z")
        Instant createdAt
) {
    public static PortfolioSnapshotResponse from(AccountCycleHistoryEntry e) {
        BigDecimal marketValueUsd = e.currentPrice() != null
                ? e.currentPrice().multiply(BigDecimal.valueOf(e.holdings())).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalAssetUsd = marketValueUsd.add(e.usdDeposit()).setScale(2, RoundingMode.HALF_UP);
        return new PortfolioSnapshotResponse(
                e.id(), e.ticker(), e.holdings(),
                e.currentPrice(), e.avgPrice(),
                marketValueUsd, e.usdDeposit(), totalAssetUsd,
                e.createdAt());
    }
}
```

---

## Task 7: CycleHistoryResponse — currentPrice 필드 추가

`GET /api/accounts/{id}/cycle-history` 응답에 현재가 포함.

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/dto/CycleHistoryResponse.java`

- [ ] **Step 1: CycleHistoryResponse에 currentPrice 추가**

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;

import java.math.BigDecimal;

public record CycleHistoryResponse(
        String createdAt,       // ISO-8601 문자열
        String ticker,          // 종목 코드 (TQQQ, SOXL 등)
        int holdings,           // 보유 수량
        BigDecimal currentPrice, // 실행 시점 현재가 (null 가능)
        BigDecimal avgPrice,    // 평균 매입 단가 (보유수량 0이면 null)
        BigDecimal usdDeposit   // 통합주문가능금액
) {
    public static CycleHistoryResponse from(AccountCycleHistoryEntry e) {
        return new CycleHistoryResponse(
                e.createdAt().toString(),
                e.ticker() != null ? e.ticker().name() : null,
                e.holdings(),
                e.currentPrice(),
                e.avgPrice(),
                e.usdDeposit()
        );
    }
}
```

---

## Task 8: TradingService — portfolioSnapshotPort 제거 + currentPrice 전달

**Files:**
- Modify: `src/main/java/com/kista/application/service/TradingService.java`

- [ ] **Step 1: portfolioSnapshotPort 필드 제거**

`TradingService` 클래스에서 아래 줄 삭제:
```java
// 삭제 대상 import
import com.kista.domain.model.order.PortfolioSnapshot;
import com.kista.domain.port.out.PortfolioSnapshotPort;

// 삭제 대상 필드 (line ~48)
private final PortfolioSnapshotPort portfolioSnapshotPort; // 포트폴리오 스냅샷 저장
```

- [ ] **Step 2: saveAndNotify에서 portfolioSnapshotPort.save() 블록 제거**

`saveAndNotify()` 메서드에서 아래 블록을 삭제:
```java
// 삭제 대상 블록
if (price != null) { // PRIVACY TODO: 현재가 미조회 시 포트폴리오 스냅샷 생략
    portfolioSnapshotPort.save(toSnapshot(balance, price, today, account, cycle.ticker()));
    log.info("[{}] 포트폴리오 스냅샷 저장 완료", account.nickname());
}
```

삭제 후 `saveCycleHistory` 호출 주석을 명확히:
```java
saveCycleHistory(balance, cycle, account, user, price, privacyTradeBase); // currentPrice 포함 이력 저장
```

- [ ] **Step 3: saveCycleHistory — currentPrice 포함하여 TradingCycleHistory 생성**

```java
// execute() 종료 시 1건 적재, 사이클 종료 시 연속 정책 처리
private void saveCycleHistory(AccountBalance balance, TradingCycle cycle,
                               Account account, User user, BigDecimal price, PrivacyTradeBase privacyTradeBase) {
    TradingCycleHistory history = new TradingCycleHistory(
            null, cycle.id(),
            balance.usdDeposit(), price,         // currentPrice (PRIVACY/초기 등록은 null)
            balance.avgPrice(), balance.holdings(), null
    );
    cycleHistoryPort.save(history);
    log.info("[cycleId={}] 거래 사이클 이력 저장 완료", cycle.id());

    if (history.holdings() == 0 && cycle.cycleSeedType().isConsecutive()) {
        log.info("[cycleId={}] 사이클 종료 — 연속 정책 실행: {}", cycle.id(), cycle.cycleSeedType());
        rotateCycleIfConsecutive(cycle, account, user, price, privacyTradeBase);
    } else if (history.holdings() == 0) {
        log.info("[cycleId={}] 사이클 종료 (연속 없음)", cycle.id());
    }
}
```

- [ ] **Step 4: rotateCycleIfConsecutive 내 TradingCycleHistory 생성 업데이트**

`rotateCycleIfConsecutive()` 내 사이클 재시작 이력 저장 부분을 찾아 `currentPrice` 추가:

```java
// 4. 새 시작점 이력 (holdings=0, avgPrice=null)
cycleHistoryPort.save(new TradingCycleHistory(
        null, cycle.id(), nextDeposit, price, null, 0, null
));
```

- [ ] **Step 5: toSnapshot() 메서드 삭제**

파일에서 `private PortfolioSnapshot toSnapshot(...)` 메서드 전체 삭제.

- [ ] **Step 6: 컴파일 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|ERROR" | head -30
```

Expected: TradingCycleService의 `new TradingCycleHistory(...)` 오류만 남음.

---

## Task 9: TradingCycleService — TradingCycleHistory 생성 업데이트

**Files:**
- Modify: `src/main/java/com/kista/application/service/TradingCycleService.java`

- [ ] **Step 1: register() 내 초기 이력 생성 업데이트**

사이클 등록 시 초기 이력 — currentPrice 없음(null):

```java
// 초기 스냅샷 저장: 입금액 기준, 보유 없음
cycleHistoryPort.save(new TradingCycleHistory(
        null, saved.id(), saved.initialUsdDeposit(), null, null, 0, null
        //                                            ↑ currentPrice=null (등록 시점엔 가격 미조회)
));
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|ERROR" | head -20
```

Expected: `BUILD SUCCESSFUL`

---

## Task 10: TelegramBotService — buildStatusMessage 업데이트

`GetPortfolioUseCase.getCurrent()` 리턴 타입이 `AccountCycleHistoryEntry`로 바뀌었으므로 메시지 포맷 조정.

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java`

- [ ] **Step 1: import 교체**

삭제:
```java
import com.kista.domain.model.order.PortfolioSnapshot;
```

추가:
```java
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import java.time.ZoneId;
import java.math.RoundingMode;
```

- [ ] **Step 2: buildStatusMessage() 업데이트**

```java
private String buildStatusMessage() {
    try {
        AccountCycleHistoryEntry e = getPortfolioUseCase.getCurrent();
        BigDecimal marketValue = e.currentPrice() != null
                ? e.currentPrice().multiply(BigDecimal.valueOf(e.holdings())).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalAsset = marketValue.add(e.usdDeposit()).setScale(2, RoundingMode.HALF_UP);
        return String.format(
                "<b>포트폴리오 현황 [%s]</b>%n보유: %d주 @ $%.4f%n평가액: $%.2f%n예수금: $%.2f%n총자산: $%.2f",
                e.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                e.holdings(),
                e.avgPrice() != null ? e.avgPrice() : BigDecimal.ZERO,
                marketValue, e.usdDeposit(), totalAsset);
    } catch (NoSuchElementException e) {
        return "포트폴리오 데이터가 없습니다.";
    }
}
```

- [ ] **Step 3: 전체 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 11: Flyway V50 — portfolio_snapshots 테이블 DROP

**Files:**
- Create: `src/main/resources/db/migration/V50__drop_portfolio_snapshots.sql`

- [ ] **Step 1: V50 마이그레이션 작성**

```sql
-- V50: portfolio_snapshots 테이블 제거
-- trading_cycle_history로 역할 통합 완료 (V49에서 current_price 컬럼 추가됨)
DROP TABLE IF EXISTS portfolio_snapshots;
```

---

## Task 12: 불필요 파일 삭제

**Files:**
- Delete: `src/main/java/com/kista/domain/model/order/PortfolioSnapshot.java`
- Delete: `src/main/java/com/kista/domain/port/out/PortfolioSnapshotPort.java`
- Delete: `src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotEntity.java`
- Delete: `src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotJpaRepository.java`
- Delete: `src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotPersistenceAdapter.java`

- [ ] **Step 1: 파일 삭제**

```bash
rm src/main/java/com/kista/domain/model/order/PortfolioSnapshot.java
rm src/main/java/com/kista/domain/port/out/PortfolioSnapshotPort.java
rm src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotEntity.java
rm src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotJpaRepository.java
rm src/main/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotPersistenceAdapter.java
```

- [ ] **Step 2: 컴파일로 잔여 참조 확인**

```bash
./gradlew compileJava 2>&1 | grep -E "error:|ERROR" | head -20
```

Expected: `BUILD SUCCESSFUL`. 오류가 남아 있으면 해당 파일의 import를 제거한다.

- [ ] **Step 3: 테스트 파일도 삭제**

```bash
rm src/test/java/com/kista/adapter/out/persistence/trade/PortfolioSnapshotPersistenceAdapterTest.java
```

- [ ] **Step 4: 전체 컴파일**

```bash
./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|ERROR" | head -30
```

Expected: 테스트 컴파일 오류는 다음 Task에서 수정.

---

## Task 13: PortfolioServiceTest 재작성

**Files:**
- Modify: `src/test/java/com/kista/application/service/PortfolioServiceTest.java`

- [ ] **Step 1: PortfolioServiceTest 전면 재작성**

```java
package com.kista.application.service;

import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock TradingCycleHistoryPort cycleHistoryPort;

    @InjectMocks PortfolioService sut;

    @Test
    @DisplayName("getCurrent: 가장 최근 이력 1건 반환")
    void getCurrent_returns_latest_entry() {
        AccountCycleHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findRecentGlobal(1)).thenReturn(List.of(entry));

        assertThat(sut.getCurrent()).isEqualTo(entry);
    }

    @Test
    @DisplayName("getCurrent: 이력 없으면 NoSuchElementException")
    void getCurrent_throws_when_no_history() {
        when(cycleHistoryPort.findRecentGlobal(1)).thenReturn(List.of());

        assertThatThrownBy(() -> sut.getCurrent())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("포트폴리오");
    }

    @Test
    @DisplayName("getSnapshots: days 파라미터를 findRecentDaysGlobal에 위임")
    void getSnapshots_delegates_days_to_port() {
        AccountCycleHistoryEntry entry = entry(new BigDecimal("26.00"));
        when(cycleHistoryPort.findRecentDaysGlobal(30)).thenReturn(List.of(entry));

        assertThat(sut.getSnapshots(30)).hasSize(1);
    }

    private AccountCycleHistoryEntry entry(BigDecimal currentPrice) {
        return new AccountCycleHistoryEntry(
                UUID.randomUUID(), Ticker.SOXL,
                new BigDecimal("1000.00"), currentPrice,
                new BigDecimal("25.00"), 30,
                Instant.now());
    }
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.application.service.PortfolioServiceTest'
```

Expected: 3개 테스트 모두 PASS.

---

## Task 14: TradingServiceTest 업데이트

**Files:**
- Modify: `src/test/java/com/kista/application/service/TradingServiceTest.java`

- [ ] **Step 1: portfolioSnapshotPort 관련 코드 제거**

삭제할 항목:
```java
// 삭제 대상 import
import com.kista.domain.model.order.PortfolioSnapshot;
import com.kista.domain.port.out.PortfolioSnapshotPort;

// 삭제 대상 @Mock 필드
@Mock PortfolioSnapshotPort portfolioSnapshotPort;

// 삭제 대상 생성자 인수 (setUp의 new TradingService(...) 호출에서)
tradeHistoryPort, portfolioSnapshotPort, notifyPort, ...
// → tradeHistoryPort, notifyPort, ... 로 변경
```

- [ ] **Step 2: TradingService 생성자 인수 순서 재정렬**

`setUp()` 메서드의 `new TradingService(...)` 호출에서 `portfolioSnapshotPort`를 제거. 현재:
```java
service = new TradingService(
    kisHolidayPort,
    kisPricePort, kisOrderPort, kisExecutionPort,
    infiniteStrategy, privacyStrategy, correctionStrategy,
    tradeHistoryPort, portfolioSnapshotPort, notifyPort, userNotificationPort,
    orderPort, realtimeNotificationPort, cycleHistoryPort,
    accountPort, cyclePort, privacyTradePort, kisMarginPort);
```

변경 후:
```java
service = new TradingService(
    kisHolidayPort,
    kisPricePort, kisOrderPort, kisExecutionPort,
    infiniteStrategy, privacyStrategy, correctionStrategy,
    tradeHistoryPort, notifyPort, userNotificationPort,
    orderPort, realtimeNotificationPort, cycleHistoryPort,
    accountPort, cyclePort, privacyTradePort, kisMarginPort);
```

- [ ] **Step 3: portfolioSnapshotPort.save() verify 제거, cycleHistoryPort verify 추가**

`execute_normalFlow_allPortsCalledInOrder` 테스트에서:
```java
// 삭제
verify(portfolioSnapshotPort).save(any(PortfolioSnapshot.class));

// 추가: currentPrice가 null이 아닌 이력이 저장됐는지 확인
verify(cycleHistoryPort).save(argThat(h -> h.currentPrice() != null));
```

`argThat` import 추가:
```java
import static org.mockito.ArgumentMatchers.argThat;
```

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.application.service.TradingServiceTest'
```

Expected: 모든 테스트 PASS.

---

## Task 15: DashboardControllerTest 업데이트

**Files:**
- Modify: `src/test/java/com/kista/adapter/in/web/DashboardControllerTest.java`

- [ ] **Step 1: import 교체 및 mock 반환값 변경**

삭제:
```java
import com.kista.domain.model.order.PortfolioSnapshot;
```

추가:
```java
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
```

`getPortfolioCurrent_returns_200` 테스트 수정:
```java
@Test
void getPortfolioCurrent_returns_200() throws Exception {
    AccountCycleHistoryEntry entry = new AccountCycleHistoryEntry(
            UUID.randomUUID(), Ticker.SOXL,
            new BigDecimal("1000.00"), new BigDecimal("26.00"),
            new BigDecimal("25.00"), 100,
            Instant.now());
    when(getPortfolioUseCase.getCurrent()).thenReturn(entry);

    mockMvc.perform(get("/api/portfolio/current"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticker").value("SOXL"))
            .andExpect(jsonPath("$.holdings").value(100));
}
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.DashboardControllerTest'
```

Expected: 3개 테스트 모두 PASS.

---

## Task 16: TelegramBotServiceTest 업데이트

**Files:**
- Modify: `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`

- [ ] **Step 1: import 교체 및 mock 반환값 변경**

삭제:
```java
import com.kista.domain.model.order.PortfolioSnapshot;
```

추가:
```java
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import java.time.Instant;
```

`buildStatusMessage` 관련 테스트들에서 `PortfolioSnapshot` → `AccountCycleHistoryEntry` 교체:

```java
// 기존 (buildStatusMessage 성공 테스트)
PortfolioSnapshot snap = new PortfolioSnapshot(UUID.randomUUID(), LocalDate.now(), Ticker.SOXL,
        100, new BigDecimal("25.0000"),
        new BigDecimal("2600.00"), new BigDecimal("1000.00"),
        new BigDecimal("3600.00"), null, Instant.now());
when(getPortfolioUseCase.getCurrent()).thenReturn(snap);

// 변경 후
AccountCycleHistoryEntry entry = new AccountCycleHistoryEntry(
        UUID.randomUUID(), Ticker.SOXL,
        new BigDecimal("1000.00"), new BigDecimal("26.00"),
        new BigDecimal("25.00"), 100,
        Instant.now());
when(getPortfolioUseCase.getCurrent()).thenReturn(entry);
```

- [ ] **Step 2: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.adapter.in.telegram.TelegramBotServiceTest'
```

Expected: 모든 테스트 PASS.

---

## Task 17: 전체 테스트 실행 및 최종 커밋

- [ ] **Step 1: 전체 테스트 실행**

```bash
./gradlew test
```

Expected: 전체 PASS. 실패 시 아래로 진단:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

- [ ] **Step 2: ArchUnit 규칙 검증**

```bash
./gradlew test --tests 'com.kista.architecture.*'
```

Expected: PASS.

- [ ] **Step 3: 최종 커밋**

```bash
git add -p  # 변경 파일 선택적 스테이징
git commit -m "refactor(portfolio): portfolio_snapshots 제거, trading_cycle_history에 current_price 통합

- V49: trading_cycle_history 재생성 — current_price 컬럼 추가 (avg_price 앞)
- V50: portfolio_snapshots 테이블 DROP
- TradingCycleHistory/AccountCycleHistoryEntry에 currentPrice 필드 추가
- TradingCycleHistoryPort에 findRecentGlobal/findRecentDaysGlobal 추가
- GetPortfolioUseCase 리턴 타입을 AccountCycleHistoryEntry로 교체
- PortfolioService: PortfolioSnapshotPort → TradingCycleHistoryPort 의존
- TradingService: portfolioSnapshotPort 제거, saveCycleHistory에 currentPrice 전달
- PortfolioSnapshotResponse: AccountCycleHistoryEntry 기반으로 재작성 (computed: marketValueUsd/totalAssetUsd)
- 불필요 파일 삭제: PortfolioSnapshot 도메인/Port/Entity/Adapter/Test"
```

---

## Self-Review

**Spec 커버리지:**
- [x] `current_price` 컬럼 추가 (V49, avg_price 앞) → Task 1
- [x] `portfolio_snapshots` 테이블 DROP → Task 11
- [x] `TradingCycleHistory` record에 `currentPrice` 추가 → Task 2
- [x] 실행 시점 현재가를 `saveCycleHistory`에서 저장 → Task 8
- [x] `GetPortfolioUseCase` 리턴 타입 교체 → Task 5
- [x] `PortfolioSnapshotResponse` computed 값으로 재작성 → Task 6
- [x] `TelegramBotService.buildStatusMessage` 업데이트 → Task 10
- [x] 불필요 파일 완전 삭제 → Task 12
- [x] 관련 테스트 전면 업데이트 → Task 13–16

**kista-ui 영향 (플랜 범위 외):**
- `GET /api/portfolio/current` 응답에서 `snapshotDate`(LocalDate) 필드가 제거됨. UI에서 해당 필드를 사용 중이면 `createdAt`(Instant)에서 날짜 추출로 교체 필요.
- `GET /api/accounts/{id}/cycle-history` 응답에 `currentPrice` 필드 추가됨 (additive change, 기존 UI 코드는 무영향).
