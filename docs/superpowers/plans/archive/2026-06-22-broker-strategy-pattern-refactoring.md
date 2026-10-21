# Broker Strategy Pattern Refactoring 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KIS/Toss 브로커 분기 로직을 3단계로 통합하여, 3번째 증권사 추가 시 기존 클래스 수정 없이 구현 가능한 구조로 리팩토링한다.

**Architecture:**
- Stage 1: `BrokerStatisticsRouter` 신설 — `AccountStatisticsService`의 `if (isToss())`·직접 포트 15개 주입 제거
- Stage 2: `TossStatisticsUseCase` + `TossStatisticsService` + `TossStatisticsController` 분리 — 도메인 포트에서 브로커명 제거
- Stage 3: 공통 capability 포트(`BrokerPortfolioPort`, `BrokerMarginPort`, `BrokerSellableQuantityPort`) 도입으로 `KisXxxPort`/`TossXxxPort` 이중 선언 해소, `TossSellableQuantity` → `SellableQuantity` 이동

**Tech Stack:** Java 21, Spring Boot 3, Hexagonal Architecture, Lombok, Gradle

## Global Constraints

- `domain` 레이어에 Spring / JPA / Lombok(`@Component`, `@Service` 등) 어노테이션 불가
- `domain/port/out/` 인터페이스명은 `*Port` suffix 필수, `*Repository` 금지
- Controller에 try/catch + `ResponseStatusException` 추가 금지 — `GlobalExceptionHandler` 자동 처리
- `@AuthenticationPrincipal UUID userId` 패턴 유지 (String 아님)
- ArchUnit 검증: `cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew test --tests 'com.kista.architecture.*'`
- 컴파일 검증: `cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava`
- 각 Task 완료 시 컴파일 검증 필수 — 다음 Task로 넘어가기 전에 BUILD SUCCESSFUL 확인
- package-private 클래스(`BrokerStatisticsRouter`, 서비스 구현체)는 `@Component`로 Spring이 관리
- BOM 삽입 방지: 파일 생성 후 `grep -l $'\xef\xbb\xbf' src --include="*.java" -r` 확인

---

## 변경 파일 맵

### Task 1 — BrokerStatisticsRouter 신설

| 동작 | 파일 |
|------|------|
| 신규 | `src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java` |
| 수정 | `src/main/java/com/kista/application/service/account/AccountStatisticsService.java` |

### Task 2 — UseCase 인터페이스 분리

| 동작 | 파일 |
|------|------|
| 수정 | `src/main/java/com/kista/domain/port/in/AccountStatisticsUseCase.java` |
| 신규 | `src/main/java/com/kista/domain/port/in/TossStatisticsUseCase.java` |

### Task 3 — TossStatisticsService + TossStatisticsController

| 동작 | 파일 |
|------|------|
| 신규 | `src/main/java/com/kista/application/service/account/TossStatisticsService.java` |
| 수정 | `src/main/java/com/kista/application/service/account/AccountStatisticsService.java` |
| 신규 | `src/main/java/com/kista/adapter/in/web/TossStatisticsController.java` |
| 수정 | `src/main/java/com/kista/adapter/in/web/StatisticsController.java` |

### Task 4 — 공통 Capability 포트 신설 + SellableQuantity 이동

| 동작 | 파일 |
|------|------|
| 신규 | `src/main/java/com/kista/domain/model/account/SellableQuantity.java` |
| 신규 | `src/main/java/com/kista/domain/port/out/BrokerPortfolioPort.java` |
| 신규 | `src/main/java/com/kista/domain/port/out/BrokerMarginPort.java` |
| 신규 | `src/main/java/com/kista/domain/port/out/BrokerSellableQuantityPort.java` |
| 수정 | `src/main/java/com/kista/adapter/out/kis/KisTradingApi.java` |
| 수정 | `src/main/java/com/kista/adapter/out/toss/TosHoldingsApi.java` |
| 수정 | `src/main/java/com/kista/adapter/in/web/dto/TossSellableQuantityResponse.java` |
| 수정 | `src/main/java/com/kista/domain/port/in/AccountStatisticsUseCase.java` |

### Task 5 — 구 포트 제거 + Router 업데이트

| 동작 | 파일 |
|------|------|
| 수정 | `src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java` |
| 수정 | `src/main/java/com/kista/application/service/trading/BrokerMarginRouter.java` |
| 삭제 | `src/main/java/com/kista/domain/port/out/KisPortfolioPort.java` |
| 삭제 | `src/main/java/com/kista/domain/port/out/TossPortfolioPort.java` |
| 삭제 | `src/main/java/com/kista/domain/port/out/KisMarginPort.java` |
| 삭제 | `src/main/java/com/kista/domain/port/out/TosMarginPort.java` |
| 삭제 | `src/main/java/com/kista/domain/port/out/KisSellableQuantityPort.java` |
| 삭제 | `src/main/java/com/kista/domain/port/out/TossSellableQuantityPort.java` |
| 삭제 | `src/main/java/com/kista/domain/model/toss/TossSellableQuantity.java` |

---

## Task 1: BrokerStatisticsRouter 신설 (Stage 1)

**Goal:** `AccountStatisticsService`의 직접 포트 주입 15개 → 6개로 축소. 브로커별 분기를 Router에 집중.

**Interfaces:**
- Produces: `BrokerStatisticsRouter` (package-private, `application.service.account`)
  - `PresentBalanceResult getPresentBalance(Account account)`
  - `List<MarginItem> getMargin(Account account)`
  - `TossSellableQuantity getSellableQuantity(Ticker ticker, Account account)`
  - `DailyTransactionResult getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to)`

---

- [ ] **Step 1: BrokerStatisticsRouter 파일 생성**

`src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java`

```java
package com.kista.application.service.account;

import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCommissionRate;
import com.kista.domain.model.toss.TossSellableQuantity;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisSellableQuantityPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TosMarginPort;
import com.kista.domain.port.out.TossCommissionsPort;
import com.kista.domain.port.out.TossPortfolioPort;
import com.kista.domain.port.out.TossSellableQuantityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// account.broker() 기반으로 KIS/Toss 통계 포트 선택 — AccountStatisticsService의 if(isToss()) 분기 제거
@Slf4j
@Component
@RequiredArgsConstructor
class BrokerStatisticsRouter {

    private final KisPortfolioPort kisPortfolioPort;
    private final TossPortfolioPort tossPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final TosMarginPort tosMarginPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisSellableQuantityPort kisSellableQuantityPort;
    private final TossSellableQuantityPort tossSellableQuantityPort;
    private final TossCommissionsPort tossCommissionsPort;
    private final BrokerExecutionRouter brokerExecutionRouter;
    private final StrategyPort strategyPort;

    // 체결기준현재잔고 — KIS: CTRP6504R + TTTC2101R 보정 / Toss: 보유종목+예수금 직접 산출
    PresentBalanceResult getPresentBalance(Account account) {
        if (account.isToss()) return tossPortfolioPort.getPresentBalance(account);
        // KIS: CTRP6504R은 예수금·환율 미제공 → TTTC2101R(margin)로 보정
        PresentBalanceResult portfolio = kisPortfolioPort.getPresentBalance(account);
        List<MarginItem> margins = kisMarginPort.getMargin(account);
        BigDecimal usdDeposit = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::purchasableAmount)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal rate = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::usdToKrwRate)
                .findFirst().orElse(BigDecimal.ZERO);
        return new PresentBalanceResult(
                portfolio.items(), portfolio.totalAssetUsd(), portfolio.totalEvalProfit(),
                portfolio.totalReturnRate(), usdDeposit, rate
        );
    }

    // 증거금 통화별 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
    List<MarginItem> getMargin(Account account) {
        return account.isToss() ? tosMarginPort.getMarginItems(account) : kisMarginPort.getMargin(account);
    }

    // 판매 가능 수량 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
    TossSellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return account.isToss()
                ? tossSellableQuantityPort.getSellableQuantity(ticker, account)
                : kisSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    // 일별 거래내역 — KIS: CTOS4001R / Toss: execution+commission 조합 구성
    DailyTransactionResult getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to) {
        if (!account.isToss()) return kisDailyTransactionPort.getDailyTransactions(from, to, account);
        return buildTossDailyTransactions(accountId, account, from, to);
    }

    // Toss 체결 내역 + 수수료율로 DailyTransactionResult 조립
    private DailyTransactionResult buildTossDailyTransactions(UUID accountId, Account account,
                                                               LocalDate from, LocalDate to) {
        Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
        if (ticker.isEmpty()) {
            return new DailyTransactionResult(List.of(), emptySummary());
        }
        List<Execution> executions = brokerExecutionRouter.getExecutions(from, to, ticker.get(), account);

        // US 수수료율 조회 — 실패 시 0으로 처리 (수수료 미표시)
        BigDecimal usCommissionRate = tossCommissionsPort.getCommissions(account).stream()
                .filter(c -> "US".equals(c.marketCountry()))
                .map(TossCommissionRate::rate)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Toss US 수수료율 조회 실패 — overseasFee=0으로 처리: accountId={}", account.id());
                    return BigDecimal.ZERO;
                });

        List<DailyTransaction> items = executions.stream()
                .map(e -> new DailyTransaction(
                        e.tradeDate().toString(),
                        null,              // Toss — 결제일 미제공
                        e.direction(),
                        e.ticker(),
                        e.ticker().name(), // Toss — 한글 종목명 미제공
                        e.quantity(),
                        e.price(),
                        e.amountUsd(),
                        BigDecimal.ZERO,   // Toss — KRW 정산금액 미제공
                        BigDecimal.ZERO,   // Toss — 체결 시점 환율 미제공
                        "USD"
                ))
                .toList();

        BigDecimal buyTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.BUY)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.SELL)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        // overseasFee = 전체 거래금액 × 수수료율(%) / 100
        BigDecimal overseasFee = buyTotal.add(sellTotal)
                .multiply(usCommissionRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        return new DailyTransactionResult(items, new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, overseasFee));
    }

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: AccountStatisticsService 교체**

`AccountStatisticsService.java` 전체를 아래로 교체. 핵심 변경:
- 주입 15개 → 6개 (`accountPort`, `strategyPort`, `cyclePositionPort`, `brokerExecutionRouter`, `brokerStatisticsRouter`, `brokerPriceRouter` + Toss 전용 5개는 Stage 2에서 제거)
- `getPresentBalance`, `getMargin`, `getSellableQuantity`, `getDailyTransactions` → Router 위임
- `getPrices` → `BrokerPriceRouter` 직접 사용 (이미 존재)
- `buildTossDailyTransactions` private 메서드 삭제 (Router로 이전됨)

```java
package com.kista.application.service.account;

import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossSellableQuantity;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TosCandlePort;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossExchangeRatePort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import com.kista.domain.port.out.TossStockInfoPort;
import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.application.service.trading.BrokerPriceRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class AccountStatisticsService implements AccountStatisticsUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final BrokerExecutionRouter brokerExecutionRouter;
    private final BrokerStatisticsRouter brokerStatisticsRouter;
    private final BrokerPriceRouter brokerPriceRouter;
    // Toss 전용 — Stage 2에서 TossStatisticsService로 이전 예정
    private final TosCandlePort tosCandlePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;

    @Override
    public List<Execution> getExecutions(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
        if (ticker.isEmpty()) return Collections.emptyList();
        return brokerExecutionRouter.getExecutions(from, to, ticker.get(), account);
    }

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getMargin(account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getDailyTransactions(accountId, account, from, to);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerPriceRouter.getPrices(tickers, account);
    }

    @Override
    public TossSellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getSellableQuantity(ticker, account);
    }

    @Override
    public List<CyclePositionHistoryEntry> getSnapshotsByAccount(UUID accountId, UUID requesterId,
                                                                  LocalDate from, LocalDate to) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        Instant fromInstant = from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH;
        Instant toInstant = (to != null ? to.plusDays(1) : LocalDate.now(TimeZones.KST).plusDays(1))
                .atStartOfDay(TimeZones.KST).toInstant();
        return cyclePositionPort.findByAccountId(accountId, fromInstant, toInstant);
    }

    @Override
    public CycleHistoryPage getByAccount(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        Instant fromInstant = resolveFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByAccountIdWithCursor(accountId, fromInstant, effectiveCursor, size + 1);
        return toPage(raw, size);
    }

    @Override
    public CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                           LocalDate from, LocalDate to,
                                           Instant cursor, int size) {
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        Instant fromInstant = resolveFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByStrategyIdWithCursor(strategyId, fromInstant, effectiveCursor, size + 1);
        return toPage(raw, size);
    }

    // ── Toss 전용 (Stage 2에서 TossStatisticsService로 이전) ──────────────────

    @Override
    public List<TossCandle> getTossCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval,
                                           LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tosCandlePort.getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getTossStockInfo(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossStockInfoPort.getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getTossExchangeRate(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossExchangeRatePort.getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getTossMarketCalendar(UUID accountId, UUID requesterId,
                                                         LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getTossAccountList(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossAccountListPort.getAccountList(account);
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private CycleHistoryPage toPage(List<CyclePositionHistoryEntry> raw, int size) {
        boolean hasMore = raw.size() > size;
        List<CyclePositionHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
        Instant nextCursor = hasMore ? items.get(items.size() - 1).createdAt() : null;
        return new CycleHistoryPage(items, nextCursor, hasMore);
    }

    private Instant resolveFrom(LocalDate from) {
        return from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
    }

    private Instant resolveTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(TimeZones.KST);
        return resolved.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
```

- [ ] **Step 3: 컴파일 검증**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` — 실패 시 import 누락·오타 수정 후 재실행

- [ ] **Step 4: 커밋**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api
git add src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java \
        src/main/java/com/kista/application/service/account/AccountStatisticsService.java
git commit -m "refactor(statistics): BrokerStatisticsRouter로 브로커 분기 집중화

AccountStatisticsService 주입 포트 15개 → 6개 축소.
getPresentBalance/getMargin/getSellableQuantity/getDailyTransactions
브로커 분기 로직을 BrokerStatisticsRouter로 이전.
getPrices는 기존 BrokerPriceRouter 재사용.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2: UseCase 인터페이스 분리 (Stage 2 — 도메인 레이어)

**Goal:** `AccountStatisticsUseCase`에서 Toss 전용 메서드를 제거하여 도메인 인바운드 포트에서 브로커명 탈피.

**Interfaces:**
- Produces:
  - `AccountStatisticsUseCase` — Toss 전용 메서드 6개 제거 후 8개만 유지
  - `TossStatisticsUseCase` — Toss 전용 6개 메서드

---

- [ ] **Step 1: TossStatisticsUseCase 신규 생성**

`src/main/java/com/kista/domain/port/in/TossStatisticsUseCase.java`

```java
package com.kista.domain.port.in;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Toss 전용 통계 기능 — Toss 계좌에서만 접근 가능 (비Toss 호출 시 IllegalStateException → 400)
public interface TossStatisticsUseCase {
    // GET /api/v1/candles — 캔들차트
    List<TossCandle> getCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval, LocalDate from, LocalDate to);
    // GET /api/v1/stocks — 종목 기본 정보
    TossStockInfo getStockInfo(UUID accountId, UUID requesterId, Ticker ticker);
    // GET /api/v1/exchange-rate — 환율 (USD/KRW)
    TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId);
    // GET /api/v1/market-calendar/US — 해외 장 운영 정보
    List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    // GET /api/v1/accounts — 계좌 목록
    List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId);
}
```

- [ ] **Step 2: AccountStatisticsUseCase에서 Toss 전용 메서드 제거**

`src/main/java/com/kista/domain/port/in/AccountStatisticsUseCase.java` — Toss 전용 import와 메서드 5개 삭제 (`getTossCandles`, `getTossStockInfo`, `getTossExchangeRate`, `getTossMarketCalendar`, `getTossAccountList`). `getSellableQuantity`는 공통이므로 유지.

```java
package com.kista.domain.port.in;

import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossSellableQuantity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// KIS/Toss 공통 통계 + trading_cycle_history 조회 인터페이스
public interface AccountStatisticsUseCase {
    List<Execution> getExecutions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
    List<MarginItem> getMargin(UUID accountId, UUID requesterId);
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
    Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers);
    TossSellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker);
    CycleHistoryPage getByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to, Instant cursor, int size);
    // 계좌 기준 스냅샷 조회 (차트용 — DB 기반, KIS API 미사용)
    List<CyclePositionHistoryEntry> getSnapshotsByAccount(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
}
```

- [ ] **Step 3: 컴파일 검증 (AccountStatisticsService가 미구현 메서드 없는지 확인)**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. 실패 시 `AccountStatisticsService`에 남아있는 `@Override`가 삭제된 메서드를 구현하는 경우 → 해당 메서드 제거.

- [ ] **Step 4: 커밋**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api
git add src/main/java/com/kista/domain/port/in/AccountStatisticsUseCase.java \
        src/main/java/com/kista/domain/port/in/TossStatisticsUseCase.java
git commit -m "refactor(port): AccountStatisticsUseCase에서 Toss 전용 메서드 분리

TossStatisticsUseCase 신설. 도메인 인바운드 포트에서
브로커명(Toss) 제거 — 증권사 추가 시 UseCase 수정 불필요.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3: TossStatisticsService + TossStatisticsController (Stage 2 — 구현)

**Goal:** Toss 전용 구현체와 컨트롤러를 분리하여 `AccountStatisticsService`와 `StatisticsController`를 공통 기능만 담당하게 한다.

**Interfaces:**
- Consumes: `TossStatisticsUseCase` (Task 2)
- Produces:
  - `TossStatisticsService implements TossStatisticsUseCase`
  - `TossStatisticsController` — `/api/accounts/{accountId}` 하위 Toss 전용 엔드포인트

---

- [ ] **Step 1: TossStatisticsService 신규 생성**

`src/main/java/com/kista/application/service/account/TossStatisticsService.java`

```java
package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.in.TossStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.TosCandlePort;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossExchangeRatePort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import com.kista.domain.port.out.TossStockInfoPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TossStatisticsService implements TossStatisticsUseCase {

    private final AccountPort accountPort;
    private final TosCandlePort tosCandlePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;

    @Override
    public List<TossCandle> getCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval,
                                       LocalDate from, LocalDate to) {
        requireTossAccount(accountId, requesterId);
        return tosCandlePort.getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getStockInfo(UUID accountId, UUID requesterId, Ticker ticker) {
        requireTossAccount(accountId, requesterId);
        return tossStockInfoPort.getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId) {
        requireTossAccount(accountId, requesterId);
        return tossExchangeRatePort.getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        requireTossAccount(accountId, requesterId);
        return tossMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossAccountListPort.getAccountList(account);
    }

    // 소유권 + Toss 계좌 여부 검증
    private void requireTossAccount(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
    }
}
```

- [ ] **Step 2: TossStatisticsController 신규 생성**

`src/main/java/com/kista/adapter/in/web/TossStatisticsController.java`

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TossAccountInfoResponse;
import com.kista.adapter.in.web.dto.TossCandleResponse;
import com.kista.adapter.in.web.dto.TossExchangeRateResponse;
import com.kista.adapter.in.web.dto.TossMarketSessionResponse;
import com.kista.adapter.in.web.dto.TossStockInfoResponse;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.TossStatisticsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "통계 (Toss 전용)", description = "Toss 증권 전용 API 엔드포인트")
@RestController
@RequestMapping("/api/accounts/{accountId}")
@RequiredArgsConstructor
public class TossStatisticsController {

    private final TossStatisticsUseCase tossStatistics;

    @Operation(summary = "캔들차트 조회 (Toss 전용)", description = "Toss API — 지정 종목의 OHLCV 캔들 데이터 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/candles")
    public List<TossCandleResponse> getCandles(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드", example = "SOXL") @RequestParam Ticker ticker,
            @Parameter(description = "간격 (1D/1W/1M)", example = "1D") @RequestParam String interval,
            @Parameter(description = "조회 시작일", example = "2025-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TossCandleResponse.fromList(tossStatistics.getCandles(accountId, userId, ticker, interval, from, to));
    }

    @Operation(summary = "종목 기본정보 조회 (Toss 전용)", description = "Toss API — 종목명·거래소·통화 등 기본 정보 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/stock-info")
    public TossStockInfoResponse getStockInfo(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "종목 코드", example = "SOXL") @RequestParam Ticker ticker) {
        return TossStockInfoResponse.from(tossStatistics.getStockInfo(accountId, userId, ticker));
    }

    @Operation(summary = "환율 조회 (Toss 전용)", description = "Toss API — USD/KRW 매수 환율 및 매매기준율 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/exchange-rate")
    public TossExchangeRateResponse getExchangeRate(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return TossExchangeRateResponse.from(tossStatistics.getExchangeRate(accountId, userId));
    }

    @Operation(summary = "장 운영 일정 조회 (Toss 전용)", description = "Toss API — 지정 기간 미국 시장 개장 여부 및 프리/정규/애프터마켓 시간 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/market-calendar")
    public List<TossMarketSessionResponse> getMarketCalendar(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId,
            @Parameter(description = "조회 시작일", example = "2025-01-01") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일", example = "2025-01-31") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return TossMarketSessionResponse.fromList(tossStatistics.getMarketCalendar(accountId, userId, from, to));
    }

    @Operation(summary = "증권사 계좌 목록 조회 (Toss 전용)", description = "Toss API — 연결된 증권사 계좌 일련번호·계좌번호 목록 조회.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "Toss 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "503", description = "Toss API 호출 실패")
    })
    @GetMapping("/broker-accounts")
    public List<TossAccountInfoResponse> getBrokerAccounts(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return TossAccountInfoResponse.fromList(tossStatistics.getAccountList(accountId, userId));
    }
}
```

- [ ] **Step 3: AccountStatisticsService에서 Toss 전용 메서드 및 주입 제거**

`AccountStatisticsService.java` — Toss 전용 5개 주입 필드와 구현 메서드 삭제:
- 삭제할 필드: `tosCandlePort`, `tossStockInfoPort`, `tossExchangeRatePort`, `tossMarketCalendarPort`, `tossAccountListPort`
- 삭제할 import: 위 5개 Port 및 `TossCandle`, `TossExchangeRate`, `TossMarketSession`, `TossStockInfo`, `TossAccountInfo`
- 삭제할 `@Override` 메서드: `getTossCandles`, `getTossStockInfo`, `getTossExchangeRate`, `getTossMarketCalendar`, `getTossAccountList`

최종 `AccountStatisticsService` 주입 필드:
```java
private final AccountPort accountPort;
private final StrategyPort strategyPort;
private final CyclePositionPort cyclePositionPort;
private final BrokerExecutionRouter brokerExecutionRouter;
private final BrokerStatisticsRouter brokerStatisticsRouter;
private final BrokerPriceRouter brokerPriceRouter;
```

- [ ] **Step 4: StatisticsController에서 Toss 전용 엔드포인트 제거**

`StatisticsController.java` — Toss 전용 블록 삭제:
- 삭제할 import: `TossAccountInfoResponse`, `TossCandleResponse`, `TossExchangeRateResponse`, `TossMarketSessionResponse`, `TossStockInfoResponse`
- 삭제할 메서드: `getCandles`, `getStockInfo`, `getExchangeRate`, `getMarketCalendar`, `getBrokerAccounts`

남은 엔드포인트: `getTrades`, `getPresentBalance`, `getMargin`, `getDailyTransactions`, `getPrices`, `getSellableQuantity`

- [ ] **Step 5: 컴파일 검증 + ArchUnit 검증**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
bash gradlew test --tests 'com.kista.architecture.*'
```

Expected: 두 명령 모두 `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api
git add src/main/java/com/kista/application/service/account/TossStatisticsService.java \
        src/main/java/com/kista/adapter/in/web/TossStatisticsController.java \
        src/main/java/com/kista/application/service/account/AccountStatisticsService.java \
        src/main/java/com/kista/adapter/in/web/StatisticsController.java
git commit -m "refactor(statistics): TossStatisticsService/Controller 분리

AccountStatisticsService 주입 15개 → 6개 완성.
Toss 전용 candles/stock-info/exchange-rate/market-calendar/broker-accounts
엔드포인트를 TossStatisticsController로 이전.
도메인 UseCase 인터페이스에 브로커명 완전 제거.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4: 공통 Capability 포트 신설 + SellableQuantity 이동 (Stage 3 — 도메인)

**Goal:** `KisXxxPort`/`TossXxxPort` 이중 선언을 공통 포트로 통합. `TossSellableQuantity`를 브로커 중립 위치로 이동.

**Interfaces:**
- Produces:
  - `domain/model/account/SellableQuantity` — record `(String symbol, int quantity)`
  - `BrokerPortfolioPort` — `getPresentBalance(Account) → PresentBalanceResult`
  - `BrokerMarginPort` — `getMargin(Account) → List<MarginItem>` + `getUsdBuyableAmount(Account) → BigDecimal`
  - `BrokerSellableQuantityPort` — `getSellableQuantity(Ticker, Account) → SellableQuantity`

---

- [ ] **Step 1: SellableQuantity 도메인 모델 생성**

`src/main/java/com/kista/domain/model/account/SellableQuantity.java`

```java
package com.kista.domain.model.account;

// 종목별 판매 가능 수량 — KIS/Toss 공통 응답 타입
public record SellableQuantity(
    String symbol,   // 종목 코드 (예: SOXL)
    int    quantity  // 판매 가능 수량
) {}
```

- [ ] **Step 2: BrokerPortfolioPort 생성**

`src/main/java/com/kista/domain/port/out/BrokerPortfolioPort.java`

```java
package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;

// 브로커 무관 체결기준현재잔고 조회 — KIS/Toss 공통 인터페이스
public interface BrokerPortfolioPort {
    PresentBalanceResult getPresentBalance(Account account);
}
```

- [ ] **Step 3: BrokerMarginPort 생성**

`src/main/java/com/kista/domain/port/out/BrokerMarginPort.java`

```java
package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// 브로커 무관 증거금 조회 — KIS: TTTC2101R / Toss: buying-power API
public interface BrokerMarginPort {
    // 통화별 증거금 전체 조회 (통계·UI 표시용)
    List<MarginItem> getMargin(Account account);
    // USD 매수가능금액 단건 조회 (거래 계산·시드 검증용 — 구현체별 효율적 방법 사용)
    BigDecimal getUsdBuyableAmount(Account account);
}
```

- [ ] **Step 4: BrokerSellableQuantityPort 생성**

`src/main/java/com/kista/domain/port/out/BrokerSellableQuantityPort.java`

```java
package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;

// 브로커 무관 판매 가능 수량 조회 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
public interface BrokerSellableQuantityPort {
    SellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
```

- [ ] **Step 5: KisTradingApi에 새 포트 구현 추가**

`KisTradingApi.java` — implements 선언에 3개 추가, 기존 메서드 시그니처 조정:

implements 라인 변경:
```java
public class KisTradingApi implements KisAccountPort, KisMarginPort, KisPortfolioPort,
        KisExecutionPort, KisDailyTransactionPort, KisSellableQuantityPort,
        BrokerPortfolioPort, BrokerMarginPort, BrokerSellableQuantityPort {
```

`getUsdBuyableAmount` 메서드 추가 (기존 private `fetchUsdDeposit` 로직을 @Override로 공개):
```java
// ── BrokerMarginPort ───────────────────────────────────────────────────────

@Override
public BigDecimal getUsdBuyableAmount(Account account) {
    return getMargin(account).stream()
            .filter(item -> Currency.USD == item.currency())
            .findFirst()
            .map(MarginItem::purchasableAmount)
            .orElse(BigDecimal.ZERO);
}
```

`getSellableQuantity` 반환 타입 변경 (`TossSellableQuantity` → `SellableQuantity`):
```java
// ── BrokerSellableQuantityPort ─────────────────────────────────────────────

@Override
public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
    PortfolioResponse response = kisHttpClient.tradingGet(
            PORTFOLIO_TR_ID, PORTFOLIO_PATH, account, PortfolioResponse.class,
            p -> {
                p.add("WCRC_FRCR_DVSN_CD", "02");
                p.add("NATN_CD", "000");
                p.add("TR_MKET_CD", "00");
                p.add("INQR_DVSN_CD", "00");
            });
    if (response == null || response.output1() == null) {
        return new SellableQuantity(ticker.name(), 0);
    }
    int quantity = response.output1().stream()
            .filter(o -> ticker.name().equals(o.pdno()))
            .findFirst()
            .map(o -> KisResponseParser.parseIntSafe(o.balanceQuantity13()))
            .orElse(0);
    return new SellableQuantity(ticker.name(), quantity);
}
```

import 추가:
```java
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.port.out.BrokerMarginPort;
import com.kista.domain.port.out.BrokerPortfolioPort;
import com.kista.domain.port.out.BrokerSellableQuantityPort;
```

기존 `fetchUsdDeposit` private 메서드 삭제 (getUsdBuyableAmount 공개 메서드로 대체됨), `TossSellableQuantity` import 삭제.

- [ ] **Step 6: TosHoldingsApi에 새 포트 구현 추가**

`TosHoldingsApi.java` — implements 선언에 3개 추가:

```java
public class TosHoldingsApi implements TosAccountPort, TosMarginPort, TossPortfolioPort,
        TossExchangeRatePort, TossSellableQuantityPort,
        BrokerPortfolioPort, BrokerMarginPort, BrokerSellableQuantityPort {
```

`BrokerMarginPort.getMargin()` 구현 추가 (기존 `getMarginItems()`를 위임):
```java
// ── BrokerMarginPort ───────────────────────────────────────────────────────

@Override
public List<MarginItem> getMargin(Account account) {
    return getMarginItems(account); // buying-power USD + KRW 각각 조회
}

@Override
public BigDecimal getUsdBuyableAmount(Account account) {
    return getBuyableAmount(account); // 단일 API 호출 — getMargin()보다 효율적
}
```

`BrokerSellableQuantityPort.getSellableQuantity()` 구현 추가 (반환 타입 `SellableQuantity`):
```java
// ── BrokerSellableQuantityPort ─────────────────────────────────────────────

@Override
public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
    var params = new LinkedMultiValueMap<String, String>();
    params.add("symbol", ticker.name());
    SellableQuantityWrapper wrapper = tossHttpClient.get(
            SELLABLE_QUANTITY_PATH, account, params, SellableQuantityWrapper.class);
    if (wrapper == null || wrapper.result() == null) {
        log.warn("Toss 판매 가능 수량 응답 없음: ticker={}", ticker);
        return new SellableQuantity(ticker.name(), 0);
    }
    SellableQuantityResult result = wrapper.result();
    int quantity = result.quantity() != null ? Integer.parseInt(result.quantity()) : 0;
    return new SellableQuantity(ticker.name(), quantity);
}
```

`BrokerPortfolioPort`는 이미 `getPresentBalance(Account)` 메서드를 가지므로 별도 추가 불필요. implements 선언만 추가.

import 추가:
```java
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.port.out.BrokerMarginPort;
import com.kista.domain.port.out.BrokerPortfolioPort;
import com.kista.domain.port.out.BrokerSellableQuantityPort;
```

- [ ] **Step 7: AccountStatisticsUseCase의 getSellableQuantity 반환 타입 변경**

`AccountStatisticsUseCase.java` — `TossSellableQuantity` → `SellableQuantity`:

```java
import com.kista.domain.model.account.SellableQuantity;
// TossSellableQuantity import 삭제

SellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker);
```

- [ ] **Step 8: TossSellableQuantityResponse의 from() 파라미터 타입 변경**

`src/main/java/com/kista/adapter/in/web/dto/TossSellableQuantityResponse.java`

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.account.SellableQuantity;

public record TossSellableQuantityResponse(
    String symbol,
    int    quantity
) {
    public static TossSellableQuantityResponse from(SellableQuantity q) {
        return new TossSellableQuantityResponse(q.symbol(), q.quantity());
    }
}
```

- [ ] **Step 9: 컴파일 검증**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. 오류 시 `TossSellableQuantity` 잔류 참조 확인:
```bash
grep -rn "TossSellableQuantity" src/main/java --include="*.java"
```
남아있는 파일은 다음 Task에서 제거 대상이므로 Task 4 완료 후 오류가 없어야 함.

- [ ] **Step 10: 커밋**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api
git add src/main/java/com/kista/domain/model/account/SellableQuantity.java \
        src/main/java/com/kista/domain/port/out/BrokerPortfolioPort.java \
        src/main/java/com/kista/domain/port/out/BrokerMarginPort.java \
        src/main/java/com/kista/domain/port/out/BrokerSellableQuantityPort.java \
        src/main/java/com/kista/adapter/out/kis/KisTradingApi.java \
        src/main/java/com/kista/adapter/out/toss/TosHoldingsApi.java \
        src/main/java/com/kista/adapter/in/web/dto/TossSellableQuantityResponse.java \
        src/main/java/com/kista/domain/port/in/AccountStatisticsUseCase.java
git commit -m "refactor(port): 공통 Capability 포트 신설 및 SellableQuantity 이동

BrokerPortfolioPort/BrokerMarginPort/BrokerSellableQuantityPort 도입.
KisTradingApi, TosHoldingsApi 모두 구현.
TossSellableQuantity → domain/model/account/SellableQuantity 이동 (브로커 중립).
BrokerMarginPort.getUsdBuyableAmount()로 Toss 효율적 단건 조회 유지.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5: 구 포트 제거 + Router 업데이트 (Stage 3 — 완결)

**Goal:** 이제 어댑터가 공통 포트를 구현했으므로, 구 브로커별 포트를 삭제하고 Router들을 단일 포트 사용으로 업데이트한다.

**Interfaces:**
- Consumes: `BrokerPortfolioPort`, `BrokerMarginPort`, `BrokerSellableQuantityPort` (Task 4)

---

- [ ] **Step 1: BrokerStatisticsRouter를 공통 포트로 교체**

`BrokerStatisticsRouter.java` — 6개 포트 주입 → 3개 공통 포트:

```java
package com.kista.application.service.account;

import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossCommissionRate;
import com.kista.domain.port.out.BrokerMarginPort;
import com.kista.domain.port.out.BrokerPortfolioPort;
import com.kista.domain.port.out.BrokerSellableQuantityPort;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TossCommissionsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// account.broker() 기반 통계 포트 라우터 — 단일 공통 포트로 브로커 무관 호출
@Slf4j
@Component
@RequiredArgsConstructor
class BrokerStatisticsRouter {

    private final BrokerPortfolioPort brokerPortfolioPort;
    private final BrokerMarginPort brokerMarginPort;
    private final BrokerSellableQuantityPort brokerSellableQuantityPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final TossCommissionsPort tossCommissionsPort;
    private final BrokerExecutionRouter brokerExecutionRouter;
    private final StrategyPort strategyPort;

    PresentBalanceResult getPresentBalance(Account account) {
        if (account.isToss()) return brokerPortfolioPort.getPresentBalance(account);
        // KIS: CTRP6504R은 예수금·환율 미제공 → margin으로 보정
        PresentBalanceResult portfolio = brokerPortfolioPort.getPresentBalance(account);
        List<MarginItem> margins = brokerMarginPort.getMargin(account);
        BigDecimal usdDeposit = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::purchasableAmount)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal rate = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::usdToKrwRate)
                .findFirst().orElse(BigDecimal.ZERO);
        return new PresentBalanceResult(
                portfolio.items(), portfolio.totalAssetUsd(), portfolio.totalEvalProfit(),
                portfolio.totalReturnRate(), usdDeposit, rate
        );
    }

    List<MarginItem> getMargin(Account account) {
        return brokerMarginPort.getMargin(account);
    }

    SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return brokerSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    DailyTransactionResult getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to) {
        if (!account.isToss()) return kisDailyTransactionPort.getDailyTransactions(from, to, account);
        return buildTossDailyTransactions(accountId, account, from, to);
    }

    private DailyTransactionResult buildTossDailyTransactions(UUID accountId, Account account,
                                                               LocalDate from, LocalDate to) {
        Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
        if (ticker.isEmpty()) {
            return new DailyTransactionResult(List.of(), emptySummary());
        }
        List<Execution> executions = brokerExecutionRouter.getExecutions(from, to, ticker.get(), account);

        BigDecimal usCommissionRate = tossCommissionsPort.getCommissions(account).stream()
                .filter(c -> "US".equals(c.marketCountry()))
                .map(TossCommissionRate::rate)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Toss US 수수료율 조회 실패 — overseasFee=0으로 처리: accountId={}", account.id());
                    return BigDecimal.ZERO;
                });

        List<DailyTransaction> items = executions.stream()
                .map(e -> new DailyTransaction(
                        e.tradeDate().toString(),
                        null,
                        e.direction(),
                        e.ticker(),
                        e.ticker().name(),
                        e.quantity(),
                        e.price(),
                        e.amountUsd(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "USD"
                ))
                .toList();

        BigDecimal buyTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.BUY)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.SELL)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overseasFee = buyTotal.add(sellTotal)
                .multiply(usCommissionRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        return new DailyTransactionResult(items, new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, overseasFee));
    }

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: AccountStatisticsService의 getSellableQuantity 반환 타입 변경**

`AccountStatisticsService.java` — `TossSellableQuantity` import 삭제, `SellableQuantity` import 추가:

```java
import com.kista.domain.model.account.SellableQuantity;
// TossSellableQuantity import 삭제

@Override
public SellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker) {
    Account account = accountPort.requireOwnedAccount(accountId, requesterId);
    return brokerStatisticsRouter.getSellableQuantity(ticker, account);
}
```

- [ ] **Step 3: BrokerMarginRouter를 BrokerMarginPort로 교체**

`src/main/java/com/kista/application/service/trading/BrokerMarginRouter.java`

```java
package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.BrokerMarginPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// USD 매수가능금액을 브로커 무관하게 단일 인터페이스로 제공
@Component
@RequiredArgsConstructor
public class BrokerMarginRouter {

    private final BrokerMarginPort brokerMarginPort;

    public BigDecimal getUsdBuyableAmount(Account account) {
        return brokerMarginPort.getUsdBuyableAmount(account);
    }
}
```

- [ ] **Step 4: 구 포트 파일 삭제 전 잔류 참조 확인**

```bash
grep -rn "KisPortfolioPort\|TossPortfolioPort\|KisMarginPort\|TosMarginPort\|KisSellableQuantityPort\|TossSellableQuantityPort\|TossSellableQuantity" \
  src/main/java --include="*.java"
```

Expected: `KisTradingApi.java`와 `TosHoldingsApi.java`에만 구 포트명이 남아있어야 함 (implements 선언). 다른 파일에 남아있으면 해당 파일을 먼저 수정.

- [ ] **Step 5: KisTradingApi/TosHoldingsApi에서 구 포트 implements 제거**

`KisTradingApi.java` implements 변경:
```java
public class KisTradingApi implements KisAccountPort,
        KisExecutionPort, KisDailyTransactionPort,
        BrokerPortfolioPort, BrokerMarginPort, BrokerSellableQuantityPort {
```
`KisMarginPort`, `KisPortfolioPort`, `KisSellableQuantityPort` 제거.
import도 삭제.

`TosHoldingsApi.java` implements 변경:
```java
public class TosHoldingsApi implements TosAccountPort,
        TossExchangeRatePort,
        BrokerPortfolioPort, BrokerMarginPort, BrokerSellableQuantityPort {
```
`TosMarginPort`, `TossPortfolioPort`, `TossSellableQuantityPort` 제거.
`TossSellableQuantity` import 삭제, `SellableQuantity` import 추가.
`TossSellableQuantityPort`의 `getSellableQuantity()` `@Override`는 `BrokerSellableQuantityPort`의 구현으로 유지.
기존 `getMarginItems()`, `getBuyableAmount()` 메서드는 내부 로직에서 여전히 사용하므로 **삭제하지 않음** (private 헬퍼 역할 유지, `public` → `private` 변경 가능하나 외부 참조가 없으면 그대로 유지).

- [ ] **Step 6: 구 포트 파일 삭제**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api
rm src/main/java/com/kista/domain/port/out/KisPortfolioPort.java
rm src/main/java/com/kista/domain/port/out/TossPortfolioPort.java
rm src/main/java/com/kista/domain/port/out/KisMarginPort.java
rm src/main/java/com/kista/domain/port/out/TosMarginPort.java
rm src/main/java/com/kista/domain/port/out/KisSellableQuantityPort.java
rm src/main/java/com/kista/domain/port/out/TossSellableQuantityPort.java
rm src/main/java/com/kista/domain/model/toss/TossSellableQuantity.java
```

- [ ] **Step 7: 컴파일 + ArchUnit 검증**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
bash gradlew test --tests 'com.kista.architecture.*'
```

Expected: 두 명령 모두 `BUILD SUCCESSFUL`. 실패 시 삭제된 타입을 참조하는 파일 탐색:
```bash
grep -rn "KisPortfolioPort\|TossPortfolioPort\|KisMarginPort\|TosMarginPort\|KisSellableQuantityPort\|TossSellableQuantityPort\|TossSellableQuantity" \
  src --include="*.java"
```

- [ ] **Step 8: 테스트 전체 실행**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew test
```

Expected: `BUILD SUCCESSFUL`. 실패 테스트 있으면 `build/reports/tests/test/index.html` 확인 또는:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

- [ ] **Step 9: 커밋**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api
git add -u
git add src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java \
        src/main/java/com/kista/application/service/account/AccountStatisticsService.java \
        src/main/java/com/kista/application/service/trading/BrokerMarginRouter.java
git commit -m "refactor(port): 구 브로커별 포트 제거 및 Router 공통 포트 전환

KisPortfolioPort/TossPortfolioPort → BrokerPortfolioPort
KisMarginPort/TosMarginPort → BrokerMarginPort
KisSellableQuantityPort/TossSellableQuantityPort → BrokerSellableQuantityPort
TossSellableQuantity → domain/model/account/SellableQuantity 삭제.
BrokerMarginRouter 단순화: switch 제거, 단일 포트 직접 호출.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## 완료 검증 체크리스트

- [ ] `bash gradlew compileJava` → `BUILD SUCCESSFUL`
- [ ] `bash gradlew test --tests 'com.kista.architecture.*'` → `BUILD SUCCESSFUL`
- [ ] `bash gradlew test` → `BUILD SUCCESSFUL`
- [ ] `grep -rn "TossSellableQuantity\|KisPortfolioPort\|TossPortfolioPort\|KisMarginPort\|TosMarginPort\|KisSellableQuantityPort\|TossSellableQuantityPort" src/main/java --include="*.java"` → 결과 없음
- [ ] `grep -rn "if.*isToss\|isToss()" src/main/java --include="*.java" | grep -v "Account.java\|BrokerStatisticsRouter\|TossStatisticsService"` → 결과 없음 (Account 도메인과 두 Router/Service 외에 isToss() 잔류 없음)
- [ ] `AccountStatisticsService`의 `private final` 필드 수: 6개 (`accountPort`, `strategyPort`, `cyclePositionPort`, `brokerExecutionRouter`, `brokerStatisticsRouter`, `brokerPriceRouter`)
- [ ] `BrokerMarginRouter`의 주입 필드: 1개 (`brokerMarginPort`)
- [ ] `domain/port/out/` 내 `Kis*Port`/`Toss*Port`는 `KisAccountPort`, `KisConnectionTestPort`, `KisTokenPort`, `KisDailyTransactionPort`, `KisExecutionPort`, `TosAccountPort`, `TossTokenPort`, `TossConnectionTestPort`, `TossCommissionsPort`, `TosCandlePort`, `TossStockInfoPort`, `TossExchangeRatePort`, `TossMarketCalendarPort`, `TossAccountListPort` 만 잔존 (기능상 불가피한 브로커 전용 API들)
