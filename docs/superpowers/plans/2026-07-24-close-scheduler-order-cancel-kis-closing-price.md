# 마감 스케쥴러 잔여주문 취소 + KIS 확정종가 조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 마감 스케쥴러가 체결 조회 전 잔여 PLACED 주문을 증권사에 취소 요청해 애프터마켓 체결이 CANCELLED로 오기록되는 사고(Toss SOXL 사례)를 막고, KIS 마감 종가 조회를 라이브 현재가 대신 확정 종가(dailyprice)로 바꿔 정확도를 높인다.

**Architecture:** `TradingReporter.recordAndNotify()`에 체결 조회 직전 취소 단계를 추가해 순서를 "취소 → 체결 조회 → 상태 기록"으로 고정한다(기존 10분 버퍼 유지, 취소 실패는 개별 격리 + 관리자 알림). `BrokerPricePort`에 `getClosingPrice(s)` 신규 메서드를 추가해 KIS는 dailyprice(HHDFS76240000, 응답 봉 날짜 검증 후 불일치 시 라이브가로 fallback) 기반으로, Toss는 기존 라이브 조회 위임으로 구현한다. 기존 `getPrevClose(s)`(전일종가, 미리보기·통계 등 핫패스에서 재사용됨)는 건드리지 않아 새 dailyprice 호출량은 마감 리포트 1회로 격리된다.

**Tech Stack:** Java 21 + Spring Boot 3, Mockito/JUnit 5, KIS/Toss REST 어댑터

---

## 배경 (사고 원인)

`TradingReporter.markFilledOrders()`는 postClose(DST 05:10 / 비DST 06:10 KST) 시점에 체결 내역이 없는 PLACED 주문을 곧바로 `CANCELLED`로 기록한다. 이 로직은 "정규장 마감 후 미체결이면 브로커가 자동 취소한다"는 전제인데, Toss는 애프터마켓(~20:00 ET)에서도 지정가 주문이 체결될 수 있어 전제가 깨진다 — 실제로 어제 SOXL $164.54 6주 매도 주문이 DB엔 CANCELLED로 남았지만 Toss 앱에선 애프터마켓 체결이 확인됐다.

또한 143번 줄의 "종가 일괄 조회"는 `BrokerPricePort.getPrices()`(라이브 현재가)를 쓰고 있어 실제로는 종가가 아니다. KIS는 `dailyprice`(HHDFS76240000) TR을 과거에 썼다가 `b9996a5d`에서 단순성을 위해 제거했는데(prevClose 용도), 이번엔 별도의 "확정 종가" 메서드로 마감 리포트 전용으로만 재도입한다. Toss는 캔들 기반 `getPrevClose`가 이미 이 시점에 정확한 값을 주는 것으로 확인되어 변경하지 않는다.

---

## Task 1: TradingReporter — 체결 조회 전 잔여 PLACED 주문 취소

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingReporter.java`
- Test: `src/test/java/com/kista/application/service/trading/TradingReporterTest.java`

- [ ] **Step 1: 실패 테스트 작성 — 취소가 체결 조회보다 먼저 호출된다**

`TradingReporterTest.java` 상단 import에 추가:

```java
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import org.mockito.InOrder;
```

필드 선언에 추가 (`@Mock ExecutionPort executionPort;` 아래):

```java
    @Mock BrokerOrderCorrectionPort brokerOrderPort;
    @Mock NotifyPort notifyPort;
```

`setUp()`을 다음으로 교체:

```java
    @BeforeEach
    void setUp() {
        reporter = new TradingReporter(registry, orderPort, userNotificationPort,
                realtimeNotificationPort, userSettingsPort, cyclePositionPersistor, notifyPort);
        when(registry.require(ACCOUNT, ExecutionPort.class)).thenReturn(executionPort);
        lenient().when(registry.require(ACCOUNT, BrokerOrderCorrectionPort.class)).thenReturn(brokerOrderPort);
        lenient().when(userSettingsPort.findOrDefault(USER.id()))
                .thenReturn(UserSettings.defaultFor(USER.id())); // TRADING_ALERT 기본 활성
    }
```

파일 맨 아래 `}` 앞에 테스트 2개 추가:

```java
    @Test
    void 마감_리포트는_체결_조회_전에_잔여_PLACED_주문을_취소한다() {
        UUID orderId = UUID.randomUUID();
        Order order = placedOrder(orderId, "E1", 5);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT)).thenReturn(List.of());

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(order), null);

        InOrder inOrder = inOrder(brokerOrderPort, executionPort);
        inOrder.verify(brokerOrderPort).cancel(order, ACCOUNT);
        inOrder.verify(executionPort).getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT);
    }

    @Test
    void 취소_실패는_격리되고_관리자_알림으로_표면화되며_체결조회는_계속된다() {
        UUID orderId = UUID.randomUUID();
        Order order = placedOrder(orderId, "E1", 5);
        doThrow(new RuntimeException("이미 체결된 주문")).when(brokerOrderPort).cancel(order, ACCOUNT);
        when(executionPort.getExecutions(TODAY, TODAY, Ticker.SOXL, ACCOUNT))
                .thenReturn(List.of(buyExecution("E1", 5, "20.00")));

        reporter.recordAndNotify(TODAY, CTX, BALANCE, CLOSE, List.of(order), null);

        verify(notifyPort).notifyError(any());
        verify(orderPort).markFilled(orderId, 5, new BigDecimal("20.00"), Order.OrderStatus.FILLED);
    }
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패로 fail 확인**

Run: `bash gradlew test --tests 'com.kista.application.service.trading.TradingReporterTest' 2>&1 | tail -40`
Expected: FAIL — `TradingReporter(BrokerAdapterRegistry, OrderPort, UserNotificationPort, RealtimeNotificationPort, UserSettingsPort, CyclePositionPersistor)` cannot be applied to given types (인자 7개 vs 기존 6개)

- [ ] **Step 3: TradingReporter에 취소 단계 구현**

`TradingReporter.java` import 블록에 추가:

```java
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
```

클래스 필드에 추가 (`private final CyclePositionPersistor cyclePositionPersistor;` 다음 줄):

```java
    private final NotifyPort notifyPort;                            // 취소 실패 등 관리자 알림
```

`recordAndNotify` 메서드 본문 맨 앞, `Strategy strategy = ...` 3줄 다음에 삽입:

```java
        // 장마감 후에도 체결 가능한 잔여 PLACED 주문을 취소 — 애프터마켓 체결이 CANCELLED로 오기록되는 것을 방지
        cancelUnresolvedOrders(mainOrders, account);

```

`markFilledOrders` 메서드 앞에 새 private 메서드 추가:

```java
    // 체결 조회 전 잔여 PLACED 주문을 증권사에 취소 요청 — 이미 체결된 주문의 취소는 브로커가 거부(무시)한다.
    // 실패해도 흐름은 계속되며(다음 getExecutions로 실제 상태를 확정), 취소 자체 실패만 관리자에게 알린다.
    private void cancelUnresolvedOrders(List<Order> mainOrders, Account account) {
        for (Order order : mainOrders) {
            if (order.status() != Order.OrderStatus.PLACED || order.externalOrderId() == null) continue;
            try {
                registry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
            } catch (Exception e) {
                log.warn("[orderId={}] 마감 후 잔여 주문 취소 실패: {}", order.id(), e.getMessage());
                notifyPort.notifyError(e);
            }
        }
    }

```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `bash gradlew test --tests 'com.kista.application.service.trading.TradingReporterTest' 2>&1 | tail -40`
Expected: PASS (기존 5개 + 신규 2개, 총 7개 테스트)

- [ ] **Step 5: TradingReporter를 생성하는 다른 호출부 동기화**

`TradingServiceTest.java`에서 `new TradingReporter(` 호출부(153번 줄 부근)를 찾아 마지막 인자에 `notifyPort` 추가:

```java
        TradingReporter reporter = new TradingReporter(
                tradingRegistry, orderPort, userNotificationPort, realtimeNotificationPort,
                userSettingsPort, positionPersistor, notifyPort);
```

(`notifyPort` 필드는 이미 `@Mock NotifyPort notifyPort;`로 선언돼 있음 — 신규 mock 불필요. `tradingRegistry.require(any(Account.class), eq(BrokerOrderCorrectionPort.class))`도 이미 130번대에서 `brokerOrderPort`로 연결돼 있어 추가 스텁 불필요.)

- [ ] **Step 6: 컴파일 확인**

Run: `bash gradlew compileTestJava 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL (Task 6에서 나머지 테스트 스텁을 마저 고칠 예정이라 `test` 전체는 아직 실패할 수 있음 — 컴파일만 확인)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingReporter.java \
        src/test/java/com/kista/application/service/trading/TradingReporterTest.java
git commit -m "$(cat <<'EOF'
fix: 마감 리포트가 체결 조회 전 잔여 PLACED 주문을 취소하도록 변경

Toss 지정가 주문이 애프터마켓(~20:00 ET)에서도 체결될 수 있어, 정규장
마감 10분 후 조회 시점에 아직 미체결이면 CANCELLED로 오기록되는 사고가
발생했다(SOXL $164.54 6주 매도 건 — DB는 CANCELLED, 실제론 애프터마켓
체결). 체결 조회 직전 잔여 PLACED 주문을 브로커에 취소 요청해 애프터
마켓으로 넘어가는 것을 원천 차단한다. 이미 체결된 주문의 취소는 브로커가
거부하므로 안전하며, 취소 자체가 실패해도 이후 체결 조회로 최종 상태를
확정하고 실패만 관리자에게 알린다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: BrokerPricePort — 확정 종가 조회 메서드 추가

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/broker/BrokerPricePort.java`

- [ ] **Step 1: 인터페이스에 메서드 추가**

`BrokerPricePort.java` 전체를 다음으로 교체:

```java
package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 현재가·스냅샷 조회 — KIS: 계좌 토큰 사용 / Toss: 공통 API(account 파라미터 무시)
public interface BrokerPricePort {
    BigDecimal getPrice(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account);
    PriceSnapshot getPriceSnapshot(Ticker ticker, Account account);
    Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account);
    // 전일종가만 필요한 경우 전용 — Toss는 현재가 API 호출 없이 캔들 API만 호출 (KIS는 현재가와 응답이 묶여 있어 절감 없음)
    BigDecimal getPrevClose(Ticker ticker, Account account);
    Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account);
    // 정규장 확정 종가 — 마감 리포트 전용(getPrevClose와 별도). KIS는 dailyprice 확정 종가, Toss는 라이브 현재가 위임
    BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account);
    Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account);
}
```

- [ ] **Step 2: 컴파일 확인 (구현체 미완성이라 실패해야 정상)**

Run: `bash gradlew compileJava 2>&1 | tail -30`
Expected: FAIL — `KisBrokerAdapter`, `TossBrokerAdapter`가 `BrokerPricePort`를 구현하지 않음(추상 메서드 누락)

- [ ] **Step 3: 커밋 없이 다음 Task로 진행** (인터페이스 단독 커밋은 컴파일 깨짐 상태라 생략, Task 3~4와 함께 커밋)

---

## Task 3: Toss — getClosingPrice(s) 위임 구현

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/toss/TossBrokerAdapter.java`

- [ ] **Step 1: BrokerPricePort 블록에 위임 메서드 추가**

`TossBrokerAdapter.java`의 `getPrevCloses` 메서드 다음에 추가:

```java
    // Toss 일봉이 애프터마켓 체결을 포함하는지 미검증 — 검증 전까지는 기존 라이브 현재가를 그대로 유지
    @Override
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return tossPriceApi.getPrice(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        return tossPriceApi.getPrices(tickers); // 공통 API — account 불필요
    }
```

- [ ] **Step 2: 컴파일 확인 (KIS 미구현이라 여전히 실패해야 정상)**

Run: `bash gradlew compileJava 2>&1 | tail -30`
Expected: FAIL — `KisBrokerAdapter`만 남은 추상 메서드 오류

---

## Task 4: KIS — dailyprice 기반 확정 종가 조회 (봉 날짜 검증 + fallback)

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/kis/KisPriceApi.java`
- Modify: `src/main/java/com/kista/adapter/out/kis/KisBrokerAdapter.java`
- Test: `src/test/java/com/kista/adapter/out/kis/KisPriceApiTest.java`

> **주의:** dailyprice(HHDFS76240000) 응답의 영업일자 필드명은 KIS 공식 문서 기준 `xymd`로 추정했다(구 코드는 `clos`만 파싱해 날짜 필드를 쓴 적이 없음). 실제 응답에서 필드명이 다르거나 값이 기대와 다르면 아래 `fetchConfirmedClose`가 자동으로 라이브 현재가로 fallback하므로 운영 사고로 이어지지 않는다 — 다만 최초 배포 후 `app_error_logs`/로그에서 "확정 종가(dailyprice) 봉 날짜 불일치" 경고가 계속 찍히면 실제 KIS 응답을 캡처해 필드명을 교정해야 한다.

- [ ] **Step 1: 실패 테스트 작성**

`KisPriceApiTest.java`에 테스트 3개 추가 (파일 마지막 `}` 앞):

```java
    @Test
    @DisplayName("확정 종가(dailyprice) 봉 날짜가 기대 거래일과 일치하면 그 값을 사용")
    void getClosingPrice_usesDailyPriceWhenDateMatches() {
        var dailyResponse = new KisPriceApi.DailyPriceResponse(
                List.of(new KisPriceApi.DailyPriceResponse.Output2("20260723", "165.10")));
        when(kisHttpClient.pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any())).thenReturn(dailyResponse);

        // tradeDate(KST)=2026-07-24 → 기대 US 거래일=2026-07-23
        BigDecimal closingPrice = api.getClosingPrice(Ticker.TQQQ, java.time.LocalDate.of(2026, 7, 24), ACCOUNT);

        assertThat(closingPrice).isEqualByComparingTo("165.10");
    }

    @Test
    @DisplayName("확정 종가 봉 날짜가 기대 거래일과 다르면(미발행 등) 라이브 현재가로 fallback")
    void getClosingPrice_fallsBackToLivePriceWhenBarDateMismatches() {
        var dailyResponse = new KisPriceApi.DailyPriceResponse(
                List.of(new KisPriceApi.DailyPriceResponse.Output2("20260722", "160.00"))); // 하루 더 과거 봉
        when(kisHttpClient.pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any())).thenReturn(dailyResponse);
        var priceResponse = new KisPriceApi.PriceResponse(
                new KisPriceApi.PriceResponse.Output("165.10", "163.00"));
        when(kisHttpClient.pricingGet(eq("HHDFS00000300"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.PriceResponse.class), any())).thenReturn(priceResponse);

        BigDecimal closingPrice = api.getClosingPrice(Ticker.TQQQ, java.time.LocalDate.of(2026, 7, 24), ACCOUNT);

        assertThat(closingPrice).isEqualByComparingTo("165.10"); // 라이브 last 값으로 fallback
    }

    @Test
    @DisplayName("확정 종가 응답이 비어있으면 라이브 현재가로 fallback")
    void getClosingPrice_fallsBackToLivePriceWhenDailyPriceEmpty() {
        when(kisHttpClient.pricingGet(eq("HHDFS76240000"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.DailyPriceResponse.class), any()))
                .thenReturn(new KisPriceApi.DailyPriceResponse(List.of()));
        var priceResponse = new KisPriceApi.PriceResponse(
                new KisPriceApi.PriceResponse.Output("165.10", "163.00"));
        when(kisHttpClient.pricingGet(eq("HHDFS00000300"), anyString(), eq(ACCOUNT),
                eq(KisPriceApi.PriceResponse.class), any())).thenReturn(priceResponse);

        BigDecimal closingPrice = api.getClosingPrice(Ticker.TQQQ, java.time.LocalDate.of(2026, 7, 24), ACCOUNT);

        assertThat(closingPrice).isEqualByComparingTo("165.10");
    }
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패로 fail 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.out.kis.KisPriceApiTest' 2>&1 | tail -40`
Expected: FAIL — `KisPriceApi.DailyPriceResponse` cannot be resolved, `getClosingPrice` method not found

- [ ] **Step 3: KisPriceApi에 확정 종가 조회 구현**

`KisPriceApi.java` import 블록에 추가:

```java
import com.kista.common.UsTradeDates;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
```

클래스 상수에 추가 (`MULTI_TR_ID` 다음 줄):

```java
    private static final String DAILY_PATH  = "/uapi/overseas-price/v1/quotations/dailyprice";
    private static final String DAILY_TR_ID = "HHDFS76240000";
```

`getPrevCloses` 메서드 다음에 신규 메서드 추가:

```java
    // 정규장 확정 종가 — 마감 리포트 전용(dailyprice, HHDFS76240000). 응답 봉 날짜가 기대 거래일과 다르면
    // (미발행 등) 라이브 현재가로 fallback — "하루 전 종가를 오늘 종가로 오기록"하는 사고를 방지한다.
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return fetchConfirmedClose(ticker, tradeDate, account).orElseGet(() -> getPrice(ticker, account));
    }

    // dailyprice는 종목당 단건 TR이라 벌크 API 없음 — 종목 수만큼 순차 호출(마감 리포트 1일 1회라 허용)
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        Map<Ticker, BigDecimal> result = new LinkedHashMap<>();
        for (Ticker ticker : tickers) {
            result.put(ticker, getClosingPrice(ticker, tradeDate, account));
        }
        return result;
    }

    // dailyprice 확정 종가 조회 — 응답 봉 날짜(xymd)가 기대 US 거래일과 일치할 때만 신뢰
    private Optional<BigDecimal> fetchConfirmedClose(Ticker ticker, LocalDate tradeDate, Account account) {
        String expectedUsDate = UsTradeDates.toUsTradeDate(tradeDate).format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            DailyPriceResponse response = kisHttpClient.pricingGet(
                    DAILY_TR_ID, DAILY_PATH, account, DailyPriceResponse.class,
                    p -> {
                        p.add("EXCD", exchangeRegistry.excd(ticker));
                        p.add("SYMB", ticker.name());
                        p.add("GUBN", "0");
                        p.add("BYMD", expectedUsDate);
                        p.add("MODP", "0");
                    });
            if (response == null || response.output2() == null || response.output2().isEmpty()) {
                log.warn("확정 종가(dailyprice) 응답 없음 — 라이브 현재가로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            DailyPriceResponse.Output2 bar = response.output2().get(0);
            if (bar.clos() == null || bar.clos().isBlank()) {
                log.warn("확정 종가(dailyprice) 종가 빈값 — 라이브 현재가로 fallback: ticker={}", ticker);
                return Optional.empty();
            }
            if (bar.xymd() == null || !bar.xymd().equals(expectedUsDate)) {
                log.warn("확정 종가(dailyprice) 봉 날짜 불일치(기대={}, 응답={}) — 아직 미발행으로 보고 라이브 현재가로 fallback: ticker={}",
                        expectedUsDate, bar.xymd(), ticker);
                return Optional.empty();
            }
            return Optional.of(KisResponseParser.parseBd(bar.clos()));
        } catch (Exception e) {
            log.warn("확정 종가(dailyprice) 조회 실패 — 라이브 현재가로 fallback: ticker={}", ticker, e);
            return Optional.empty();
        }
    }
```

`record MultiPriceResponse(...)` 블록 다음(파일 맨 끝, 마지막 `}` 앞)에 응답 레코드 추가:

```java

    // 해외주식 기간별시세(일봉) 응답 — GUBN=0(일) + BYMD 기준 output2[0]이 해당 날짜의 봉.
    // xymd(영업일자, YYYYMMDD)는 KIS 공식 문서 기준 추정 필드명 — 실응답 검증 필요(위 Task 4 주의사항 참고)
    record DailyPriceResponse(@JsonProperty("output2") List<Output2> output2) {
        record Output2(
            @JsonProperty("xymd") String xymd, // 영업일자 (YYYYMMDD)
            @JsonProperty("clos") String clos  // 종가
        ) {}
    }
```

- [ ] **Step 4: KisBrokerAdapter에 위임 추가**

`KisBrokerAdapter.java`의 `getPrevCloses` 메서드 다음에 추가:

```java
    @Override
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return kisPriceApi.getClosingPrice(ticker, tradeDate, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        return kisPriceApi.getClosingPrices(tickers, tradeDate, account);
    }
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.out.kis.KisPriceApiTest' 2>&1 | tail -40`
Expected: PASS (기존 2개 + 신규 3개, 총 5개)

- [ ] **Step 6: 전체 컴파일 확인**

Run: `bash gradlew compileJava 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL (Task 2~4로 `BrokerPricePort` 구현체 2개 모두 완성)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/domain/port/out/broker/BrokerPricePort.java \
        src/main/java/com/kista/adapter/out/toss/TossBrokerAdapter.java \
        src/main/java/com/kista/adapter/out/kis/KisPriceApi.java \
        src/main/java/com/kista/adapter/out/kis/KisBrokerAdapter.java \
        src/test/java/com/kista/adapter/out/kis/KisPriceApiTest.java
git commit -m "$(cat <<'EOF'
feat(kis): 마감 리포트 전용 확정 종가(dailyprice) 조회 추가

TradingService 마감 리포트가 종가로 쓰던 값은 실제로는 BrokerPricePort.
getPrices()(라이브 현재가)였다. BrokerPricePort에 getClosingPrice(s)를
신규 추가해 KIS는 dailyprice(HHDFS76240000) 확정 종가를 쓰되, 응답 봉
날짜가 기대 거래일과 다르면(미발행 등) 라이브 현재가로 안전하게
fallback한다. Toss는 캔들 기반 getPrevClose가 이미 이 시점에 정확한
값을 주는 것으로 확인되어 라이브 조회를 그대로 위임한다.

기존 getPrevClose(s)(전일종가)는 미리보기·통계 등 핫패스에서 공유되므로
건드리지 않았다 — dailyprice는 벌크 API가 없어 신규 호출량을 마감
리포트 1일 1회로 격리하기 위함.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: TradingService — 마감 리포트 종가 조회를 fetchClosingPrices로 교체

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingPriceFetcher.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`

- [ ] **Step 1: TradingPriceFetcher에 fetchClosingPrices 추가**

`TradingPriceFetcher.java`의 `fetchPrevCloses` 메서드 다음에 추가:

```java
    // 정규장 확정 종가만 필요한 경우 (마감 리포트 전용)
    Map<Ticker, BigDecimal> fetchClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        return fetchWithFallback(tickers, account, "확정종가",
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getClosingPrices(t, tradeDate, acc),
                (t, acc) -> registry.require(acc, BrokerPricePort.class).getClosingPrice(t, tradeDate, acc));
    }
```

import 블록에 `java.time.LocalDate` 추가:

```java
import java.time.LocalDate;
```

- [ ] **Step 2: TradingService.executeBatch 143번 줄 교체**

`TradingService.java`에서:

```java
        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchPrices(priceCtx.cycleTickers(), priceCtx.priceAccount());
```

다음으로 교체:

```java
        // 장 마감 후 확정 종가 일괄 조회 (라이브 현재가 아님 — KIS는 dailyprice, Toss는 라이브 위임)
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchClosingPrices(priceCtx.cycleTickers(), today, priceCtx.priceAccount());
```

- [ ] **Step 3: 컴파일 확인**

Run: `bash gradlew compileJava 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/kista/application/service/trading/TradingPriceFetcher.java \
        src/main/java/com/kista/application/service/trading/TradingService.java
git commit -m "$(cat <<'EOF'
fix: 마감 리포트 종가 조회를 라이브 현재가 대신 확정 종가로 교체

TradingService.executeBatch()의 "종가 일괄 조회" 주석이 붙은 호출이
실제로는 fetchPrices(라이브 현재가)를 쓰고 있었다. 신규 fetchClosingPrices
로 교체해 cycle_position.closing_price·리버스모드 판정에 실제 확정
종가(KIS는 dailyprice, Toss는 기존 라이브 유지)가 반영되도록 한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: TradingServiceTest — 종가 스텁을 fetchClosingPrices 기준으로 동기화

**Files:**
- Modify: `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

Task 5로 `priceFetcher.fetchClosingPrices(...)`가 내부적으로 `BrokerPricePort.getClosingPrices(...)`를 호출하게 되어, 기존에 "종가"용으로 `kisPricePort.getPrices(anyList(), eq(ACCOUNT))`를 스텁하던 25곳 전부가 더 이상 매칭되지 않는다(호출 자체가 없어짐). 전부 `getClosingPrices(anyList(), any(LocalDate.class), eq(...))`로 교체한다.

- [ ] **Step 1: 일괄 치환**

Run:
```bash
sed -i -E 's/kisPricePort\.getPrices\(anyList\(\), eq\((ACCOUNT|failingAccount)\)\)/kisPricePort.getClosingPrices(anyList(), any(LocalDate.class), eq(\1))/g' \
  src/test/java/com/kista/application/service/trading/TradingServiceTest.java
```

- [ ] **Step 2: 치환 결과 확인**

Run: `grep -c "getClosingPrices(anyList" src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
Expected: `27` (기존 25개 `when` + 2개 `verify`)

Run: `grep -n "kisPricePort.*getPrices(anyList" src/test/java/com/kista/application/service/trading/TradingServiceTest.java`
Expected: 287번 줄 `verify(kisPricePort, never()).getPrices(anyList(), any());` 한 줄만 남아야 함(휴장일 스킵 검증 — 종가 조회 자체가 없다는 뜻이라 그대로 유지)

- [ ] **Step 3: TradingReporter 생성 호출부에 notifyPort 인자 추가 (Task 1 Step 5 미실행 시 여기서 수행)**

153번 줄 부근이 다음과 같은지 확인하고, 아니라면 수정:

```java
        TradingReporter reporter = new TradingReporter(
                tradingRegistry, orderPort, userNotificationPort, realtimeNotificationPort,
                userSettingsPort, positionPersistor, notifyPort);
```

- [ ] **Step 4: 전체 테스트 실행**

Run: `bash gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest' 2>&1 | tail -60`
Expected: PASS (전체 테스트, 237/1395번 줄의 `verify(kisPricePort).getClosingPrices(anyList(), any(LocalDate.class), eq(ACCOUNT))`도 포함)

- [ ] **Step 5: 관련 전체 스위트 실행**

Run: `bash gradlew test 2>&1 | tail -80`
Expected: BUILD SUCCESSFUL — 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 케이스 확인 후 수정

- [ ] **Step 6: 커밋**

```bash
git add src/test/java/com/kista/application/service/trading/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
test: TradingServiceTest 종가 스텁을 fetchClosingPrices 기준으로 동기화

143번 줄이 fetchPrices 대신 fetchClosingPrices(→ getClosingPrices)를
쓰도록 바뀌어(Task 5), 기존 kisPricePort.getPrices(anyList(), eq(ACCOUNT))
종가 스텁이 전부 매칭되지 않게 됐다. getClosingPrices(anyList(),
any(LocalDate.class), eq(...))로 일괄 치환.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 문서 동기화

**Files:**
- Modify: `docs/agents/constraints.md`
- Modify: `docs/agents/kis-api.md`

- [ ] **Step 1: constraints.md — UsTradeDates 허용 위치에 KisPriceApi 추가**

`docs/agents/constraints.md`에서:

```
- `UsTradeDates` 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter` — 도메인·서비스·orders persistence에서 사용 금지
```

다음으로 교체:

```
- `UsTradeDates` 사용 허용 위치: `KisTradingApi`(KIS API는 US 거래일 기준), `MarketCalendarPersistenceAdapter`, `KisPriceApi`(dailyprice BYMD 파라미터) — 도메인·서비스·orders persistence에서 사용 금지
```

- [ ] **Step 2: kis-api.md — dailyprice 재도입 배경 기록**

`docs/agents/kis-api.md`의 82번 줄(`- 과거엔 "base는 장 시작 전엔...` 문단) 바로 다음에 추가:

```
- 2026-07-24 마감 리포트 종가 정확도 문제(라이브 current가 종가로 오인됨)로 `getClosingPrice(s)`를 신규 추가하며 dailyprice를 재도입했다 — 단, 위에서 제거된 `getPrevClose(s)`(전일종가, 미리보기·통계 등 핫패스 공용)와는 별개 메서드로 격리해 호출량 증가가 마감 리포트 1일 1회로만 한정되도록 했다. 응답 봉 날짜가 기대 거래일과 다르면 라이브 현재가로 자동 fallback한다(`KisPriceApi.fetchConfirmedClose`).
```

- [ ] **Step 3: 커밋**

```bash
git add docs/agents/constraints.md docs/agents/kis-api.md
git commit -m "$(cat <<'EOF'
docs: dailyprice 재도입·UsTradeDates 허용 위치 문서 반영

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 최종 검증

- [ ] **Step 1: 전체 빌드 + 테스트**

Run: `bash gradlew clean test 2>&1 | tail -100`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: ArchUnit 규칙 확인 (레이어 위반 없는지)**

Run: `bash gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -40`
Expected: BUILD SUCCESSFUL — `BrokerOrderCorrectionPort`/`NotifyPort`를 `TradingReporter`(application layer)에서 쓰는 건 기존에도 허용된 `domain.port.out` 의존이라 위반 없어야 함

- [ ] **Step 3: 잔존 패턴 확인 — 143번 줄 fetchPrices 완전히 교체됐는지**

Run: `grep -n "fetchPrices(priceCtx.cycleTickers()" src/main/java/com/kista/application/service/trading/TradingService.java`
Expected: 매치 없음 (fetchClosingPrices로 교체 완료)

- [ ] **Step 4: 최종 커밋 로그 확인**

Run: `git log --oneline -8`
Expected: Task 1·4·5·6·7의 커밋 5개가 순서대로 보임
