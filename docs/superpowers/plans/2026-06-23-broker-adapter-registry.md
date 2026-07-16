# BrokerAdapter Registry Pattern Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브로커별 `switch(account.broker())` 분기와 `isToss()` 브랜치를 제거하고, `BrokerAdapterRegistry` + Capability 인터페이스 패턴으로 교체하여 새 증권사 추가 시 단일 Adapter 클래스만 작성하면 되는 구조로 개선한다.

**Architecture:**
현재 `BrokerPortfolioRouter` / `BrokerMarginRouter` / `BrokerSellableQuantityRouter` 3개의 Router @Component가 각각 switch를 갖고 있고 `BrokerStatisticsRouter`와 `TradingService`에 `isToss()` 분기가 남아 있다. 이를 `BrokerAdapter` (identity만 담당) + 12개 Capability 인터페이스 + `BrokerAdapterRegistry` (`require` / `find`) 구조로 교체한다. 기존 Kis/Toss 개별 포트(`KisPortfolioPort` 등)는 adapter 내부에서만 사용하도록 좁히고, 서비스 레이어는 Registry를 통해 Capability만 소비한다.

**Tech Stack:** Java 21, Spring Boot 3, Hexagonal Architecture (ArchUnit 검증), Lombok

## Global Constraints

- ArchUnit 규칙 준수: `domain → 외부 없음`, `application → domain만`, `adapter.out → domain.port.out 구현`
- 새 파일은 반드시 기존 코드에서 컴파일 통과 확인 후 다음 태스크 진행: `cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava`
- 전체 테스트는 마지막 태스크에서만 실행: `bash gradlew test`
- 커밋 전 `git config user.name` / `git config user.email` 확인 — 올바른 author: `narafu <narafu@kakao.com>`
- `git push`는 절대 금지 (사용자가 별도 요청)
- Capability 인터페이스 패키지: `com.kista.domain.port.out.broker`
- BrokerAdapterRegistry 패키지: `com.kista.application.service.broker`
- KisBrokerAdapter: `com.kista.adapter.out.kis`, TossBrokerAdapter: `com.kista.adapter.out.toss`
- Lombok `@RequiredArgsConstructor` + `private final` 필드 패턴 유지
- 주석: `// 한 줄 인라인` 형식만, Javadoc/블록 주석 금지
- 인터페이스 메서드명은 아래 Task 1에 명시된 서명 그대로 사용 — 자의적 변경 금지

---

## 현재 코드 현황 (구현 시작 전 필독)

### 삭제 대상 (Task 5에서 제거)
- `application/service/trading/BrokerPortfolioRouter.java` — implements `BrokerPortfolioPort`, switch(KIS→KisPortfolioPort, TOSS→TossPortfolioPort)
- `application/service/trading/BrokerMarginRouter.java` — implements `BrokerMarginPort`, switch(KIS→KisMarginPort, TOSS→TosMarginPort)
- `application/service/trading/BrokerSellableQuantityRouter.java` — implements `BrokerSellableQuantityPort`, switch(KIS→KisSellableQuantityPort, TOSS→TossSellableQuantityPort)
- `domain/port/out/BrokerPortfolioPort.java`
- `domain/port/out/BrokerMarginPort.java`
- `domain/port/out/BrokerSellableQuantityPort.java`

### isToss() 브랜치 위치 (Task 5에서 제거)
1. `application/service/account/BrokerStatisticsRouter.java:49` — `getPresentBalance()`: Toss는 그대로, KIS는 margin 보정 로직 추가
2. `application/service/account/BrokerStatisticsRouter.java:79` — `getDailyTransactions()`: KIS는 CTOS4001R, Toss는 execution+commission 조합
3. `application/service/account/TossStatisticsService.java:70` — `requireTossAccount()` 내 Toss 계좌 여부 체크

> ~~`application/service/trading/TradingService.java` — `loadBalance()` isToss()~~ **커밋 c9a72fb에서 이미 제거됨** (KIS·Toss 모두 `balanceLoader.loadBalanceOrThrow()` DB 이력 통일)

### BrokerAccountRouter (주의)
`application/service/trading/BrokerAccountRouter.java` — `getLiveBalance()` 메서드가 `switch(account.broker())` 구조 유지 중.
이 클래스는 **주문 저장 직전 live 잔고 유효성 검사** 전용으로, TradingService.loadBalance()와 별개임.
플랜 Task 5에서 `BrokerAccountRouter`를 `BalanceCapable` Registry 방식으로 교체하지 않는다 — live 잔고 검사 특성상 DB 스냅샷과 다른 시그니처(`(Account, Ticker)`)가 필요하고 Java switch 표현식이 새 Broker 추가 시 컴파일 오류로 누락을 방지하므로 이 switch는 수용 가능한 패턴임.

### KisTradingApi implements (현재)
```
KisAccountPort, KisExecutionPort, KisDailyTransactionPort,
KisPortfolioPort, KisMarginPort, KisSellableQuantityPort
```

### TosHoldingsApi implements (현재)
`TosMarginPort`, `TossPortfolioPort`, `TossSellableQuantityPort`, `TosAccountPort`
(그 외 Toss API: TosOrderApi, TosCandleApi, TossMarketApi, TossCommissionsApi, TossAuthApi)

---

## Task 1: Capability 인터페이스 + BrokerAdapter 정의

**파일:**
- Create: `src/main/java/com/kista/domain/port/out/broker/BrokerAdapter.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/PortfolioCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/MarginCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/SellableQuantityCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/DailyTradeCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/ExecutionCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/CandleCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/ExchangeRateCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/StockInfoCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/MarketCalendarCapable.java`
- Create: `src/main/java/com/kista/domain/port/out/broker/BrokerAccountCapable.java`

**Interfaces:**
- Produces: 12개 인터페이스 — Task 2, 3, 4가 의존

- [ ] **Step 1: `BrokerAdapter.java` 작성**

```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;

// 브로커 어댑터 루트 인터페이스 — identity(어떤 브로커인지)만 정의
// 실제 기능은 Capability 인터페이스를 추가 구현하여 선언
public interface BrokerAdapter {
    Account.Broker supports();
}
```

- [ ] **Step 2: 공통 Capability 인터페이스 6개 작성**

`PortfolioCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;

// 체결기준현재잔고 조회 — KIS: CTRP6504R+TTTC2101R 보정 포함 / Toss: 보유종목+예수금 직접 산출
public interface PortfolioCapable {
    PresentBalanceResult getPresentBalance(Account account);
}
```

`MarginCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// 증거금 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
public interface MarginCapable {
    List<MarginItem> getMargin(Account account);
    BigDecimal getUsdBuyableAmount(Account account);
}
```

`SellableQuantityCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;

// 판매 가능 수량 조회 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
public interface SellableQuantityCapable {
    SellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
```

`DailyTradeCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;

import java.time.LocalDate;

// 일별 거래내역 조회 — KIS: CTOS4001R / Toss: execution+commission 조합
public interface DailyTradeCapable {
    DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account);
}
```

`ExecutionCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.time.LocalDate;
import java.util.List;

// 체결 내역 조회 — KIS: TTTS3035R / Toss: /api/v1/executions
public interface ExecutionCapable {
    List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account);
}
```

`BalanceCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;

// 잔고 실시간 조회 — Toss만 구현 (KIS는 DB cycle_position 스냅샷 사용)
// BrokerAdapterRegistry.find()로 Optional로 조회 — 미지원 브로커는 empty 반환
public interface BalanceCapable {
    AccountBalance getBalance(Strategy strategy, Account account);
}
```

- [ ] **Step 3: Toss 전용 Capability 인터페이스 5개 작성**

> ~~`BalanceCapable.java`~~ **불필요 — 커밋 c9a72fb에서 `TradingService.loadBalance()`가 KIS·Toss 모두 DB 이력으로 통일됨.** Toss live 잔고는 `BrokerAccountRouter.getLiveBalance()` (switch 방식) 유지.

`CandleCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.toss.TossCandle;

import java.time.LocalDate;
import java.util.List;

// 캔들 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface CandleCapable {
    List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to);
    List<TossCandle> getLatestCandles(String symbol, String interval, int count);
}
```

`ExchangeRateCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.toss.TossExchangeRate;

// 환율 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface ExchangeRateCapable {
    TossExchangeRate getExchangeRate();
}
```

`StockInfoCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossStockInfo;

// 종목 정보 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface StockInfoCapable {
    TossStockInfo getStockInfo(Ticker ticker);
}
```

`MarketCalendarCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.toss.TossMarketSession;

import java.time.LocalDate;
import java.util.List;

// 시장 캘린더 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface MarketCalendarCapable {
    List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to);
}
```

`BrokerAccountCapable.java`:
```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossAccountInfo;

import java.util.List;

// 브로커 계좌 목록 조회 (Toss 전용) — 계좌 토큰 필요
public interface BrokerAccountCapable {
    List<TossAccountInfo> getAccountList(Account account);
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/broker/
git commit -m "feat(broker-registry): Capability 인터페이스 + BrokerAdapter 기반 인터페이스 정의

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2: KisBrokerAdapter 구현

**파일:**
- Create: `src/main/java/com/kista/adapter/out/kis/KisBrokerAdapter.java`

**Interfaces:**
- Consumes (Task 1): `BrokerAdapter`, `PortfolioCapable`, `MarginCapable`, `SellableQuantityCapable`, `DailyTradeCapable`, `ExecutionCapable`
- Consumes (기존 KIS 포트): `KisPortfolioPort`, `KisMarginPort`, `KisSellableQuantityPort`, `KisDailyTransactionPort`, `KisExecutionPort`
- Produces: `Account.Broker.KIS` 계좌 전용 `BrokerAdapter` Spring 빈

**중요 구현 포인트:**
- `PortfolioCapable.getPresentBalance()`: 현재 `BrokerStatisticsRouter.getPresentBalance()`의 KIS 분기 로직(margin 보정) 전체를 여기로 이동
- `BalanceCapable` 미구현 — KIS는 DB 스냅샷 방식이므로 Registry.find()에서 empty 반환

- [ ] **Step 1: `KisBrokerAdapter.java` 작성**

```java
package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.Currency;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisSellableQuantityPort;
import com.kista.domain.port.out.broker.BrokerAdapter;
import com.kista.domain.port.out.broker.DailyTradeCapable;
import com.kista.domain.port.out.broker.ExecutionCapable;
import com.kista.domain.port.out.broker.MarginCapable;
import com.kista.domain.port.out.broker.PortfolioCapable;
import com.kista.domain.port.out.broker.SellableQuantityCapable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// KIS 브로커 어댑터 — 공통 6개 Capability 구현, BalanceCapable 미구현(DB 스냅샷 사용)
@Component
@RequiredArgsConstructor
public class KisBrokerAdapter implements BrokerAdapter,
        PortfolioCapable, MarginCapable, SellableQuantityCapable,
        DailyTradeCapable, ExecutionCapable {

    private final KisPortfolioPort kisPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final KisSellableQuantityPort kisSellableQuantityPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisExecutionPort kisExecutionPort;

    @Override
    public Account.Broker supports() {
        return Account.Broker.KIS;
    }

    // CTRP6504R 결과에 TTTC2101R(margin)에서 예수금·환율 보정
    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
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

    @Override
    public List<MarginItem> getMargin(Account account) {
        return kisMarginPort.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return kisMarginPort.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return kisSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account) {
        return kisDailyTransactionPort.getDailyTransactions(from, to, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return kisExecutionPort.getExecutions(from, to, ticker, account);
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/kis/KisBrokerAdapter.java
git commit -m "feat(broker-registry): KisBrokerAdapter — 공통 5 Capability 구현, margin 보정 통합

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3: TossBrokerAdapter 구현

**파일:**
- Create: `src/main/java/com/kista/adapter/out/toss/TossBrokerAdapter.java`

**Interfaces:**
- Consumes (Task 1): `BrokerAdapter` + 10개 Capability (Toss 전용 5개 포함, ~~BalanceCapable 제외~~)
- Consumes (기존 Toss 포트): `TossPortfolioPort`, `TosMarginPort`, `TossSellableQuantityPort`, `TosExecutionPort`, `TossCommissionsPort`, `StrategyPort`, `TosCandlePort`, `TossExchangeRatePort`, `TossStockInfoPort`, `TossMarketCalendarPort`, `TossAccountListPort`
- Produces: `Account.Broker.TOSS` 계좌 전용 `BrokerAdapter` Spring 빈

> ~~`TosAccountPort` 주입 불필요~~ — live 잔고는 `BrokerAccountRouter`가 담당. `TossBrokerAdapter`는 `BalanceCapable` 미구현.

**중요 구현 포인트:**
- `DailyTradeCapable.getDailyTransactions()`: 현재 `BrokerStatisticsRouter.buildTossDailyTransactions()` 로직 전체를 여기로 이동 (내부 private 메서드로 동일하게 구현)
- `StrategyPort` 주입: `account.id()`로 ticker 조회 (`strategyPort.findActiveTicker(accountId)`)

- [ ] **Step 1: `TossBrokerAdapter.java` 작성**

주의: `buildTossDailyTransactions` 로직을 그대로 복사 — `accountId`는 `account.id()`로 대체

```java
package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossCommissionRate;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossCommissionsPort;
import com.kista.domain.port.out.TossExchangeRatePort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import com.kista.domain.port.out.TossPortfolioPort;
import com.kista.domain.port.out.TossStockInfoPort;
import com.kista.domain.port.out.TosCandlePort;
import com.kista.domain.port.out.TosExecutionPort;
import com.kista.domain.port.out.TosMarginPort;
import com.kista.domain.port.out.TossSellableQuantityPort;
import com.kista.domain.port.out.broker.BrokerAccountCapable;
import com.kista.domain.port.out.broker.BrokerAdapter;
import com.kista.domain.port.out.broker.CandleCapable;
import com.kista.domain.port.out.broker.DailyTradeCapable;
import com.kista.domain.port.out.broker.ExchangeRateCapable;
import com.kista.domain.port.out.broker.ExecutionCapable;
import com.kista.domain.port.out.broker.MarginCapable;
import com.kista.domain.port.out.broker.MarketCalendarCapable;
import com.kista.domain.port.out.broker.PortfolioCapable;
import com.kista.domain.port.out.broker.SellableQuantityCapable;
import com.kista.domain.port.out.broker.StockInfoCapable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// Toss 브로커 어댑터 — 공통 5개 + Toss 전용 5개 Capability 구현 (BalanceCapable 미구현 — BrokerAccountRouter 담당)
@Slf4j
@Component
@RequiredArgsConstructor
public class TossBrokerAdapter implements BrokerAdapter,
        PortfolioCapable, MarginCapable, SellableQuantityCapable,
        DailyTradeCapable, ExecutionCapable,
        CandleCapable, ExchangeRateCapable, StockInfoCapable,
        MarketCalendarCapable, BrokerAccountCapable {

    private final TossPortfolioPort tossPortfolioPort;
    private final TosMarginPort tosMarginPort;
    private final TossSellableQuantityPort tossSellableQuantityPort;
    private final TosExecutionPort tosExecutionPort;
    private final TossCommissionsPort tossCommissionsPort;
    private final StrategyPort strategyPort;
    private final TosCandlePort tosCandlePort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;

    @Override
    public Account.Broker supports() {
        return Account.Broker.TOSS;
    }

    // --- 공통 Capability ---

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        return tossPortfolioPort.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(Account account) {
        return tosMarginPort.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return tosMarginPort.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return tossSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    // Toss 체결 내역 + 수수료율로 DailyTransactionResult 조립
    @Override
    public DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account) {
        Optional<Ticker> ticker = strategyPort.findActiveTicker(account.id());
        if (ticker.isEmpty()) {
            return new DailyTransactionResult(List.of(), emptySummary());
        }
        List<Execution> executions = tosExecutionPort.getExecutions(from, to, ticker.get(), account);

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

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return tosExecutionPort.getExecutions(from, to, ticker, account);
    }

    // --- Toss 전용 Capability ---

    @Override
    public List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        return tosCandlePort.getCandles(symbol, interval, from, to);
    }

    @Override
    public List<TossCandle> getLatestCandles(String symbol, String interval, int count) {
        return tosCandlePort.getLatestCandles(symbol, interval, count);
    }

    @Override
    public TossExchangeRate getExchangeRate() {
        return tossExchangeRatePort.getExchangeRate();
    }

    @Override
    public TossStockInfo getStockInfo(Ticker ticker) {
        return tossStockInfoPort.getStockInfo(ticker);
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to) {
        return tossMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(Account account) {
        return tossAccountListPort.getAccountList(account);
    }

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/toss/TossBrokerAdapter.java
git commit -m "feat(broker-registry): TossBrokerAdapter — 공통 6 + Toss 전용 5 Capability 구현

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4: BrokerAdapterRegistry 구현

**파일:**
- Create: `src/main/java/com/kista/application/service/broker/BrokerAdapterRegistry.java`

**Interfaces:**
- Consumes (Task 1): `BrokerAdapter` (domain/port/out/broker/)
- Produces: `BrokerAdapterRegistry @Component` — `require(account, Class<T>)` + `find(account, Class<T>)`

**ArchUnit 확인:** `application/service/broker` → `domain/port/out/broker` 의존 — 허용 ✓. `KisBrokerAdapter` / `TossBrokerAdapter` 는 `BrokerAdapter` 인터페이스로만 DI 받으므로 `application → adapter` 위반 없음 ✓.

- [ ] **Step 1: `BrokerAdapterRegistry.java` 작성**

```java
package com.kista.application.service.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.broker.BrokerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 브로커 어댑터 레지스트리 — account.broker()로 BrokerAdapter 조회 후 Capability 캐스팅
@Slf4j
@Component
public class BrokerAdapterRegistry {

    private final Map<Account.Broker, BrokerAdapter> registry;

    BrokerAdapterRegistry(List<BrokerAdapter> adapters) {
        registry = adapters.stream()
                .collect(Collectors.toMap(BrokerAdapter::supports, Function.identity()));
        log.info("BrokerAdapterRegistry 초기화: {}", registry.keySet());
    }

    // 지원하지 않으면 UnsupportedOperationException — GlobalExceptionHandler → 400
    public <T> T require(Account account, Class<T> capability) {
        BrokerAdapter adapter = getAdapter(account);
        if (!capability.isInstance(adapter)) {
            throw new UnsupportedOperationException(
                    account.broker() + " 브로커는 " + capability.getSimpleName() + "를 지원하지 않습니다");
        }
        return capability.cast(adapter);
    }

    // 지원하지 않으면 Optional.empty() — 호출자가 fallback 처리
    public <T> Optional<T> find(Account account, Class<T> capability) {
        BrokerAdapter adapter = registry.get(account.broker());
        if (adapter == null || !capability.isInstance(adapter)) return Optional.empty();
        return Optional.of(capability.cast(adapter));
    }

    private BrokerAdapter getAdapter(Account account) {
        BrokerAdapter adapter = registry.get(account.broker());
        if (adapter == null) {
            throw new UnsupportedOperationException("지원하지 않는 브로커: " + account.broker());
        }
        return adapter;
    }
}
```

- [ ] **Step 2: ArchUnit 테스트 실행 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew test --tests 'com.kista.architecture.*'
```
Expected: `BUILD SUCCESSFUL` (ArchUnit 레이어 위반 없음)

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/application/service/broker/BrokerAdapterRegistry.java
git commit -m "feat(broker-registry): BrokerAdapterRegistry — require/find Capability 조회

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5: Router 교체 + isToss() 제거 + 삭제

**파일:**
- Modify: `src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java`
- Modify: `src/main/java/com/kista/application/service/account/TossStatisticsService.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Delete: `src/main/java/com/kista/application/service/trading/BrokerPortfolioRouter.java`
- Delete: `src/main/java/com/kista/application/service/trading/BrokerMarginRouter.java`
- Delete: `src/main/java/com/kista/application/service/trading/BrokerSellableQuantityRouter.java`
- Delete: `src/main/java/com/kista/domain/port/out/BrokerPortfolioPort.java`
- Delete: `src/main/java/com/kista/domain/port/out/BrokerMarginPort.java`
- Delete: `src/main/java/com/kista/domain/port/out/BrokerSellableQuantityPort.java`

**Interfaces:**
- Consumes (Task 4): `BrokerAdapterRegistry`
- Consumes (Task 1): Capability 인터페이스들

**중요:** 삭제 전에 반드시 해당 클래스를 다른 곳에서 import/inject 하는지 grep으로 확인:
```bash
grep -r "BrokerPortfolioRouter\|BrokerMarginRouter\|BrokerSellableQuantityRouter\|BrokerPortfolioPort\|BrokerMarginPort\|BrokerSellableQuantityPort" src/ --include="*.java" -l
```

- [ ] **Step 1: `BrokerStatisticsRouter.java` 리팩토링**

기존 필드: `BrokerPortfolioPort`, `BrokerMarginPort`, `BrokerSellableQuantityPort`, `KisDailyTransactionPort`, `TossCommissionsPort`, `BrokerExecutionRouter`, `StrategyPort`

변경 후 필드: `BrokerAdapterRegistry`, `BrokerExecutionRouter` (체결조회는 이전과 동일하게 유지 — ExecutionCapable는 AccountStatisticsService가 직접 사용)

```java
package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.broker.DailyTradeCapable;
import com.kista.domain.port.out.broker.MarginCapable;
import com.kista.domain.port.out.broker.PortfolioCapable;
import com.kista.domain.port.out.broker.SellableQuantityCapable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// account.broker() 기반 통계 라우터 — BrokerAdapterRegistry 경유
@Slf4j
@Component
@RequiredArgsConstructor
class BrokerStatisticsRouter {

    private final BrokerAdapterRegistry registry;
    private final BrokerExecutionRouter brokerExecutionRouter; // 체결 조회는 별도 라우터 유지

    PresentBalanceResult getPresentBalance(Account account) {
        return registry.require(account, PortfolioCapable.class).getPresentBalance(account);
    }

    List<MarginItem> getMargin(Account account) {
        return registry.require(account, MarginCapable.class).getMargin(account);
    }

    SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return registry.require(account, SellableQuantityCapable.class).getSellableQuantity(ticker, account);
    }

    DailyTransactionResult getDailyTransactions(UUID accountId, Account account, LocalDate from, LocalDate to) {
        return registry.require(account, DailyTradeCapable.class).getDailyTransactions(from, to, account);
    }
}
```

주의: 기존 `getDailyTransactions(UUID accountId, Account account, ...)` 시그니처 유지 — 상위 `AccountStatisticsService`가 이 메서드를 호출하므로 파라미터 변경 금지. 내부 구현에서 `accountId`는 더 이상 필요 없으나 (TossBrokerAdapter가 `account.id()` 사용), 시그니처는 하위 호환 유지.

- [ ] **Step 2: `TossStatisticsService.java` 리팩토링**

기존 6개 포트 필드 → `AccountPort` + `BrokerAdapterRegistry`로 교체

```java
package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.in.TossStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.broker.BrokerAccountCapable;
import com.kista.domain.port.out.broker.CandleCapable;
import com.kista.domain.port.out.broker.ExchangeRateCapable;
import com.kista.domain.port.out.broker.MarketCalendarCapable;
import com.kista.domain.port.out.broker.StockInfoCapable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TossStatisticsService implements TossStatisticsUseCase {

    private final AccountPort accountPort;
    private final BrokerAdapterRegistry registry;

    @Override
    public List<TossCandle> getCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval,
                                       LocalDate from, LocalDate to) {
        Account account = requireTossAccount(accountId, requesterId);
        return registry.require(account, CandleCapable.class).getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getStockInfo(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = requireTossAccount(accountId, requesterId);
        return registry.require(account, StockInfoCapable.class).getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getExchangeRate(UUID accountId, UUID requesterId) {
        Account account = requireTossAccount(accountId, requesterId);
        return registry.require(account, ExchangeRateCapable.class).getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(UUID accountId, UUID requesterId,
                                                     LocalDate from, LocalDate to) {
        Account account = requireTossAccount(accountId, requesterId);
        return registry.require(account, MarketCalendarCapable.class).getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(UUID accountId, UUID requesterId) {
        Account account = requireTossAccount(accountId, requesterId);
        return registry.require(account, BrokerAccountCapable.class).getAccountList(account);
    }

    // 소유권 + Toss 계좌 여부 검증 (require()에서 UnsupportedOperationException 자동 처리)
    private Account requireTossAccount(UUID accountId, UUID requesterId) {
        return accountPort.requireOwnedAccount(accountId, requesterId);
        // registry.require(account, CandleCapable.class) 에서 KIS 계좌면 UnsupportedOperationException → 400
    }
}
```

주의: `requireTossAccount()`에서 더 이상 `isToss()` 체크를 하지 않는다. 대신 `registry.require(account, XxxCapable.class)`가 KIS 계좌면 `UnsupportedOperationException`을 던져 `GlobalExceptionHandler` → 400으로 처리됨. 단, 기존에는 `IllegalStateException` → 400이었는데 `UnsupportedOperationException`도 `IllegalArgumentException`과 같은 방식으로 처리되는지 `GlobalExceptionHandler`를 확인해야 함. 만약 `UnsupportedOperationException`이 처리되지 않는다면 `IllegalArgumentException`으로 변경하거나, `BrokerAdapterRegistry.require()`에서 `IllegalArgumentException`을 던지도록 수정.

- [ ] **Step 3: GlobalExceptionHandler에서 UnsupportedOperationException 처리 여부 확인**

```bash
grep -n "UnsupportedOperation\|IllegalArgument\|IllegalState" \
  src/main/java/com/kista/adapter/in/web/GlobalExceptionHandler.java
```

`UnsupportedOperationException` 미처리 시 `BrokerAdapterRegistry.require()`를 `IllegalArgumentException`으로 변경:
```java
throw new IllegalArgumentException(account.broker() + " 브로커는 " + capability.getSimpleName() + "를 지원하지 않습니다");
```

- [x] **Step 4: `TradingService.java`의 `loadBalance()` 수정** *(커밋 c9a72fb에서 이미 완료)*

`isToss()` 분기가 제거되고 KIS·Toss 모두 `balanceLoader.loadBalanceOrThrow(strategy).balance()` (DB 이력) 통일됨.
`tosAccountPort` 필드는 `brokerAccountRouter`로 교체됨 (live 잔고 유효성 검사에 사용). `BrokerAdapterRegistry` 주입 불필요.

- [ ] **Step 5: Router/Port 클래스 삭제 전 참조 검사**

```bash
grep -r "BrokerPortfolioRouter\|BrokerMarginRouter\|BrokerSellableQuantityRouter" \
  src/ --include="*.java"
grep -r "BrokerPortfolioPort\|BrokerMarginPort\|BrokerSellableQuantityPort" \
  src/ --include="*.java"
```

참조가 없으면 삭제:
```bash
git rm src/main/java/com/kista/application/service/trading/BrokerPortfolioRouter.java
git rm src/main/java/com/kista/application/service/trading/BrokerMarginRouter.java
git rm src/main/java/com/kista/application/service/trading/BrokerSellableQuantityRouter.java
git rm src/main/java/com/kista/domain/port/out/BrokerPortfolioPort.java
git rm src/main/java/com/kista/domain/port/out/BrokerMarginPort.java
git rm src/main/java/com/kista/domain/port/out/BrokerSellableQuantityPort.java
```

- [ ] **Step 6: 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: isToss() 잔존 여부 확인**

```bash
grep -rn "isToss\(\)" src/main/java/ --include="*.java"
```
Expected: 출력 없음 (모든 isToss() 분기 제거됨)

- [ ] **Step 8: 커밋**

```bash
git add -u
git add src/main/java/com/kista/application/service/account/BrokerStatisticsRouter.java
git add src/main/java/com/kista/application/service/account/TossStatisticsService.java
git add src/main/java/com/kista/application/service/trading/TradingService.java
git commit -m "refactor(broker-registry): Router 클래스 제거 및 Registry 기반으로 전환, isToss() 분기 완전 제거

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 6: 최종 검증 및 테스트 수정

**파일:**
- Modify: 영향받은 테스트 파일들 (컴파일 오류 해결)
- Verify: ArchUnit 규칙 통과

**이 태스크에서 해야 할 일:**
1. 전체 `compileTestJava` 실행 → 오류 있는 테스트 파일 특정
2. 각 테스트 파일의 `@MockBean` / `@Mock` 수정 (삭제된 클래스 참조 제거)
3. 전체 테스트 실행

- [ ] **Step 1: 테스트 컴파일 확인**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew compileTestJava 2>&1 | grep -E "error:|cannot find"
```

오류 발생 시 해당 파일 Read 후 수정:
- `TossStatisticsServiceTest`가 존재한다면 `@Mock TosCandlePort`, `@Mock TossStockInfoPort` 등 → `@Mock BrokerAdapterRegistry`로 교체
- `BrokerStatisticsRouterTest`가 존재한다면 `@Mock BrokerPortfolioPort` 등 → `@Mock BrokerAdapterRegistry`로 교체
- `TradingServiceTest`가 존재한다면 커밋 c9a72fb에서 이미 수정됨 (`TosAccountPort` → `BrokerAccountRouter`). 추가 변경 불필요.

- [ ] **Step 2: ArchUnit 테스트 실행**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew test --tests 'com.kista.architecture.*'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 전체 테스트 실행**

```bash
cd /mnt/c/Users/USER/workspace/kista/kista-api && bash gradlew test
```
Expected: `BUILD SUCCESSFUL`

실패 시 실패 테스트 확인:
```bash
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

- [ ] **Step 4: 잔존 dead code 확인**

```bash
# KisXxxPort, TossXxxPort 등 기존 브로커별 포트가 어댑터 외부에서 참조되는지 확인
grep -rn "KisPortfolioPort\|TossPortfolioPort\|KisMarginPort\|TosMarginPort\|KisSellableQuantityPort\|TossSellableQuantityPort" \
  src/main/java/com/kista/application/ src/main/java/com/kista/adapter/in/ --include="*.java"
```
Expected: 출력 없음 (이들은 이제 adapter/out/kis/, adapter/out/toss/ 내부에서만 사용됨)

- [ ] **Step 5: 최종 커밋**

```bash
git add -u
git commit -m "test(broker-registry): 레지스트리 전환에 따른 테스트 Mock 수정

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## 구현 완료 후 확인 체크리스트

```bash
# 1. isToss() 완전 제거 확인
grep -rn "isToss" src/main/java/ --include="*.java"
# Expected: 출력 없음

# 2. 삭제된 Router 클래스 참조 없음 확인
grep -rn "BrokerPortfolioRouter\|BrokerMarginRouter\|BrokerSellableQuantityRouter" src/ --include="*.java"
# Expected: 출력 없음

# 3. 삭제된 Port 참조 없음 확인
grep -rn "import.*BrokerPortfolioPort\|import.*BrokerMarginPort\|import.*BrokerSellableQuantityPort" src/ --include="*.java"
# Expected: 출력 없음

# 4. Registry 사용처 확인
grep -rn "BrokerAdapterRegistry" src/main/java/ --include="*.java"
# Expected: BrokerStatisticsRouter, TossStatisticsService 2곳에서 inject (TradingService는 BrokerAccountRouter 유지)

# 5. 새 증권사 추가 시 필요한 작업 확인 (아무것도 수정할 필요 없어야 함)
# → Account.Broker enum에 새 브로커 추가 + NewBrokerAdapter @Component 작성만으로 완료
```
