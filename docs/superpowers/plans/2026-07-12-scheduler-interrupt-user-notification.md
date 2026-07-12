# 스케쥴러 인터럽트 시 사용자 알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포·재기동으로 스케쥴러 배치가 `InterruptedException`으로 중단될 때, 아직 처리되지 않은 전략의 사용자에게 전용 알림을 보낸다.

**Architecture:** `UserNotificationPort`에 `notifyBatchInterrupted(User, Account)`를 신설하고 3개 어댑터(Composite/Telegram/Fcm)에 구현한다. `TradingService.executeBatch()`/`placeOpenOrders()`의 `waitFor()` 호출 중, 아직 아무 것도 처리되지 않은 상태에서 인터럽트될 수 있는 지점(주문 시각 대기, 개장 시각 대기)만 `try/catch`로 감싸 해당 시점에 아직 증권사 접수 전인 전략들의 사용자에게 알림을 보낸 뒤 원래 예외를 재던진다.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5 + Mockito.

## 설계 보정 (브레인스토밍 이후 코드 재확인 결과)

브레인스토밍에서는 "지역 Set으로 처리완료 추적 + 최상위 단일 catch"로 합의했으나, 계획 작성 중 `TradingService.java`와 `TradingOrderExecutor.java` 전체의 `Thread.sleep` 호출을 전수 확인한 결과 다음을 발견했다:
- `InterruptedException`이 `executeBatch`/`placeOpenOrders` 밖으로 실제 전파될 수 있는 지점은 `TradingService.waitFor()` 내부의 `Thread.sleep()` **뿐**이다. `planAll`/`placeAll`/`reportAll` 루프 자체에는 인터럽트 가능한 블로킹 호출이 없다 (`TradingOrderExecutor.markPlacedWithRetry()`의 `Thread.sleep(1000)`은 `InterruptedException`을 로컬에서 흡수하고 `Thread.currentThread().interrupt()`만 호출할 뿐 재던지지 않아 상위로 전파되지 않음).
- `executeBatch()`에는 `waitFor()` 호출이 2곳 있다: "주문 시각"(placeAll 이전)과 "마감 시각"(reportAll 이전). **"마감 시각" 대기 중 인터럽트되는 경우는 사용자 알림 대상이 아니다** — 그 시점의 `placedStates`는 이미 `placeAll`(증권사 접수)까지 완료된 상태라 "매매가 처리되지 않음"이 아니고, 단지 체결 리포트 발송이 지연될 뿐이기 때문이다(브레인스토밍에서 합의한 "아직 처리되지 않은 전략만" 원칙과 정확히 일치).
- 따라서 지역 Set 추적은 불필요하다 — 각 `waitFor()` 호출 시점에 이미 로컬 변수로 "아직 미처리인 목록"이 명확하게 존재한다(`executeBatch`의 "주문 시각" 대기 시점엔 `states`, `placeOpenOrders`의 "개장 시각" 대기 시점엔 `contexts` 전체). 이 발견은 사용자가 승인한 두 가지 결정(①미처리 사용자만 정확히 ②전용 알림 메서드)을 그대로 구현하되, 메커니즘을 더 단순하고 정확하게 만든다 — 최종 사용자 관찰 동작은 브레인스토밍에서 합의한 것과 동일하다.

## Global Constraints

- 동작 완전 불변 — 기존 알림 경로(관리자 알림, `runSafely()`의 개별 실패 알림)는 그대로 유지, 이번 추가는 새 알림 1건만 얹는다.
- 매매 공식·주문 생성 로직(`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`/`CycleOrderComputer` 내부 계산) 절대 변경 금지 — 이번 작업은 그 파일들을 건드리지 않는다.
- 알림 발송 자체의 예외가 원본 `InterruptedException` 전파를 막으면 안 된다 — 알림 발송은 개별 try/catch로 흡수.
- 커밋: 한글 메시지 + Conventional Commit 접두사, author `narafu <narafu@kakao.com>`, **push 금지**.
- 검증 게이트: `./gradlew compileJava` + `./gradlew test`.
- Java 파일 BOM(`\xef\xbb\xbf`) 삽입 금지.

---

## Task 1: `UserNotificationPort.notifyBatchInterrupted` 추가 + 3개 어댑터 구현

**Files:**
- Modify: `src/main/java/com/kista/domain/port/out/UserNotificationPort.java`
- Modify: `src/main/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/notify/FcmAdapter.java`
- Modify (테스트): `src/test/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapterTest.java`
- Modify (테스트): `src/test/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapterTest.java`

**Interfaces:**
- Produces: `UserNotificationPort.notifyBatchInterrupted(User user, Account account)` — Task 2가 `TradingService`에서 이 메서드를 호출한다.

- [ ] **Step 1: `UserNotificationPort`에 메서드 추가**

`src/main/java/com/kista/domain/port/out/UserNotificationPort.java`의 `notifyError` 선언 바로 다음 줄에 추가:

```java
    void notifyBatchInterrupted(User user, Account account);                                  // 사용자에게 스케쥴러 인터럽트(배포·재기동) 알림
```

전체 파일은 다음과 같아야 한다:

```java
package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;

import java.math.BigDecimal;

public interface UserNotificationPort {
    void notifyNewUser(User user);                                                          // 관리자에게 신규 가입 승인 요청 알림
    void notifyApproved(User user);                                                         // 사용자에게 승인 알림
    void notifyRejected(User user);                                                         // 사용자에게 거절 알림
    void notifyTradingReport(User user, Account account, TradingReport report);             // 사용자에게 매매 결과 알림
    void notifyCycleCompleted(User user, Account account, Strategy strategy);               // 사용자에게 사이클 종료(holdings=0) 알림
    void notifyNewCycleStarted(User user, Account account, Strategy strategy,
                               BigDecimal initialUsdDeposit);                              // 사용자에게 새 사이클 시작 알림
    void notifyInsufficientBalance(User user, Account account, Strategy.Type strategyType, Strategy.Ticker ticker); // 사용자에게 예수금 부족 알림
    void notifyError(User user, Exception e);                                              // 사용자에게 매매 오류 알림
    void notifyBatchInterrupted(User user, Account account);                                  // 사용자에게 스케쥴러 인터럽트(배포·재기동) 알림
    void notifyMarketOpen(User user);                                                        // 사용자에게 장 개시 알림
    void notifyMarketClose(User user);                                                       // 사용자에게 장 마감 알림
}
```

- [ ] **Step 2: `compileJava` 확인 (인터페이스만 바꾼 시점 — 구현체 미비로 실패 예상)**

Run: `./gradlew compileJava`
Expected: FAIL — `CompositeUserNotificationAdapter`, `TelegramUserNotificationAdapter`, `FcmAdapter`가 인터페이스 미구현으로 컴파일 오류.

- [ ] **Step 3: `CompositeUserNotificationAdapter`에 구현 추가**

`src/main/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapter.java`의 `notifyError` 라인(35번째 줄) 바로 다음에 추가:

```java
    @Override public void notifyBatchInterrupted(User user, Account account)                        { route(user, p -> p.notifyBatchInterrupted(user, account)); }
```

- [ ] **Step 4: `TelegramUserNotificationAdapter`에 구현 추가**

`src/main/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapter.java`의 `notifyError` 메서드(119-122줄) 바로 다음에 추가:

```java
    @Override
    public void notifyBatchInterrupted(User user, Account account) {
        String text = String.format(
                "⏸️ <b>매매 일시 중단</b> — %s%n"
                + "시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 필요 시 관리자에게 문의해주세요.",
                account.nickname());
        sendIfLinked(user, text);
    }
```

- [ ] **Step 5: `FcmAdapter`에 구현 추가**

`src/main/java/com/kista/adapter/out/notify/FcmAdapter.java`의 `notifyError` 메서드(72-75줄) 바로 다음에 추가:

```java
    @Override
    public void notifyBatchInterrupted(User user, Account account) {
        String body = String.format("[%s] 시스템 재배포로 오늘 매매가 일시 중단됐습니다.", account.nickname());
        send(user.id(), "⏸️ 매매 일시 중단", body);
    }
```

- [ ] **Step 6: `compileJava` 재확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 7: `CompositeUserNotificationAdapterTest`에 라우팅 테스트 추가**

`src/test/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapterTest.java`의 마지막 `@Test` 메서드(`notifyRejected_allChannel_routesToBoth`, 104-112줄) 바로 다음, 클래스 닫는 중괄호 전에 추가:

```java
    @Test
    void notifyBatchInterrupted_allChannel_routesToBoth() {
        User user = userWith(NotificationChannel.ALL);
        Account account = mock(Account.class);

        composite.notifyBatchInterrupted(user, account);

        verify(telegram).notifyBatchInterrupted(user, account);
        verify(fcm).notifyBatchInterrupted(user, account);
    }
```

- [ ] **Step 8: `TelegramUserNotificationAdapterTest`에 발송 테스트 추가**

`src/test/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapterTest.java`의 `notifyTradingReport_noUserBot_skips` 메서드(49-57줄) 바로 다음, `buildTestReport()` 헬퍼 전에 추가:

```java
    @Test
    void notifyBatchInterrupted_withUserBot_sendsToUserChatId() {
        User user = DomainFixtures.telegramUser(UUID.randomUUID(), "user-bot-token", "user-chat-789");
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");

        adapter.notifyBatchInterrupted(user, account);

        verify(restTemplate).postForObject(contains("/botuser-bot-token/sendMessage"), any(), eq(String.class));
    }
```

- [ ] **Step 9: 테스트 실행**

Run: `./gradlew test --tests 'com.kista.adapter.out.notify.*'`
Expected: 전부 통과 (신규 2개 테스트 포함)

- [ ] **Step 10: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 진단.

- [ ] **Step 11: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(notify): UserNotificationPort에 notifyBatchInterrupted 추가 — 스케쥴러 인터럽트 사용자 알림 기반

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `TradingService`에서 인터럽트 시 미처리 사용자 알림 발송

**Files:**
- Modify: `src/main/java/com/kista/application/service/trading/TradingService.java`
- Modify (테스트): `src/test/java/com/kista/application/service/trading/TradingServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `UserNotificationPort.notifyBatchInterrupted(User, Account)`.

- [ ] **Step 1: `executeBatch()`의 "주문 시각" 대기를 try/catch로 감싸기**

`src/main/java/com/kista/application/service/trading/TradingService.java`의 82-112번째 줄(`executeBatch(List<BatchContext> contexts, DstInfo dst)` 전체)을 다음으로 교체:

```java
    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void executeBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        if (contexts.isEmpty()) return;

        LocalDate today = LocalDate.now(TimeZones.KST);

        // 시장 개장 여부 확인 (1회) — 모든 전략 공통, 가격 조회 전 조기 반환
        if (!isMarketOpen(today)) return;

        // 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        PriceContext priceCtx = loadPriceContext(contexts, today);

        // planAndSaveOrders — 전략별: 잔고 로드 + PLANNED 주문 생성·저장 (이미 존재하면 skip)
        List<CycleState> states = planAll(contexts, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), today);
        if (states.isEmpty()) return;

        // 공통 대기 — 주문 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트 시 states(증권사 접수 전)는 전부 미처리 — 사용자 알림 대상
        try {
            waitFor("주문 시각", dst.waitUntilOrderTime(), dst);
        } catch (InterruptedException e) {
            notifyBatchInterrupted(states.stream().map(CycleState::ctx).toList());
            throw e;
        }

        // 증권사 접수 — 전략별: BUY 가격 보정 후 PLANNED → 증권사 접수
        List<CyclePlacedState> placedStates = placeAll(states, today);

        // 공통 대기 — 마감 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트는 사용자 알림 대상 아님 — placedStates는 이미 증권사 접수 완료, 체결 리포트만 지연됨
        waitFor("마감 시각", dst.waitUntilPostClose(), dst);
        marketEventNotifier.notifyMarketClose();

        // 장 마감 후 종가 일괄 조회
        Map<Ticker, BigDecimal> closingPrices = priceFetcher.fetchPrices(priceCtx.cycleTickers(), priceCtx.priceAccount());

        // recordAndNotifyExecutions — 전략별: 체결 조회 + 이력 저장 + 알림
        reportAll(placedStates, closingPrices, today);
    }
```

- [ ] **Step 2: `placeOpenOrders()`의 "개장 시각" 대기를 try/catch로 감싸기**

`src/main/java/com/kista/application/service/trading/TradingService.java`의 253-276번째 줄(`placeOpenOrders(List<BatchContext> contexts, DstInfo dst)` 전체)을 다음으로 교체:

```java
    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void placeOpenOrders(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        if (contexts.isEmpty()) return;

        LocalDate tradeDate = DstInfo.nextTradeDate(); // 장 개시 스케쥴러 전날 저녁 실행 — 내일이 US 거래일
        log.info("개장 order 생성 + INFINITE 매도 선접수 시작 — 거래일 {}", tradeDate);

        if (!isMarketOpen(tradeDate)) return;

        // 가격 스냅샷 + PRIVACY 기준 매매표 일괄 조회 (개장 전 현시점, 내일 기준 — FIDA가 미리 송신했을 경우)
        PriceContext priceCtx = loadPriceContext(contexts, tradeDate);

        // 개장 시각까지 대기 — 이 시점 인터럽트 시 contexts 전부가 미처리 — 사용자 알림 대상
        try {
            waitFor("개장 시각", dst.waitUntilMarketOpen(), dst);
        } catch (InterruptedException e) {
            notifyBatchInterrupted(contexts);
            throw e;
        }
        marketEventNotifier.notifyMarketOpen();

        // 전략별: order 생성·저장 + INFINITE 매도 선접수
        for (BatchContext ctx : contexts) {
            runSafely("개장 order+매도접수", ctx, () -> {
                planSaveAndPlaceSells(ctx, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), tradeDate);
                return null;
            });
        }

        log.info("개장 order 생성 + INFINITE 매도 선접수 완료");
    }
```

**주의**: 이 Step은 `placeOpenOrders` 메서드 본문만 교체한다 — 바로 다음에 이어지는 `planSaveAndPlaceSells` private 메서드는 건드리지 않는다.

- [ ] **Step 3: `notifyBatchInterrupted` private 헬퍼 추가**

`TradingService.java`에서 `loadBalance` private 메서드(163-169줄) 바로 다음에 추가:

```java
    // 인터럽트 시점에 아직 증권사 접수가 안 된 전략들에게 알림 (증권사 접수 완료된 전략은 대상 아님)
    private void notifyBatchInterrupted(List<BatchContext> contexts) {
        contexts.forEach(ctx -> {
            try {
                userNotificationPort.notifyBatchInterrupted(ctx.user(), ctx.account());
            } catch (Exception notifyEx) {
                log.warn("[strategyId={}] 인터럽트 알림 발송 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
            }
        });
    }
```

- [ ] **Step 4: `compileJava` 확인**

Run: `./gradlew compileJava`
Expected: SUCCESS

- [ ] **Step 5: `executeBatch` 인터럽트 테스트 작성**

`src/test/java/com/kista/application/service/trading/TradingServiceTest.java`에서 `executeBatch_oneCycleFails_continuesWithNextAndNotifiesAdmin` 테스트(576-602줄) 바로 다음에 추가:

```java
    @Test
    void executeBatch_interruptedAtOrderWait_notifiesPlannedUserAndRethrows() {
        // orderAt을 살짝 미래로 설정 → waitUntilOrderTime()이 양수 → Thread.sleep 실제 호출
        DstInfo interruptingDst = new DstInfo(true,
                Instant.now().plusMillis(300),
                Instant.now().minusSeconds(1800),
                Instant.now().minusSeconds(7200));

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));
        when(cycleHistoryPort.findLatestOneByStrategyId(STRATEGY.id())).thenReturn(Optional.of(NORMAL_HISTORY));
        when(infiniteStrategy.buildOrders(any(InfinitePosition.class), any(LocalDate.class))).thenReturn(List.of());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any(LocalDate.class)))
                .thenReturn(List.of()); // 오늘 주문 없음 → planAll이 신규 계산해 states에 담김

        // waitFor 진입 시 Thread.sleep이 즉시 InterruptedException을 던지도록 인터럽트 플래그 선-설정
        Thread.currentThread().interrupt();

        List<BatchContext> contexts = List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER));
        assertThrows(InterruptedException.class, () -> service.executeBatch(contexts, interruptingDst));

        verify(userNotificationPort).notifyBatchInterrupted(USER, ACCOUNT);
    }
```

`assertThrows`를 쓰려면 파일 상단 import 목록(34-36줄 부근)에 다음이 없으면 추가:
```java
import static org.junit.jupiter.api.Assertions.assertThrows;
```

- [ ] **Step 6: `placeOpenOrders` 인터럽트 테스트 작성**

같은 파일에서 `placeOpenOrders_noSellOrders_skipsKisPlace` 테스트(454줄 부근) 바로 다음에 추가:

```java
    @Test
    void placeOpenOrders_interruptedAtMarketOpenWait_notifiesAllContextUsersAndRethrows() {
        // marketOpen을 살짝 미래로 설정 → waitUntilMarketOpen()이 양수 → Thread.sleep 실제 호출
        DstInfo interruptingDst = new DstInfo(true,
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(1800),
                Instant.now().plusMillis(300));

        when(marketCalendarPort.isMarketOpen(any())).thenReturn(true);
        when(kisPricePort.getPriceSnapshots(anyList(), eq(ACCOUNT)))
                .thenReturn(Map.of(Ticker.SOXL, new PriceSnapshot(PRICE, PRICE)));

        Thread.currentThread().interrupt();

        List<BatchContext> contexts = List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER));
        assertThrows(InterruptedException.class, () -> service.placeOpenOrders(contexts, interruptingDst));

        verify(userNotificationPort).notifyBatchInterrupted(USER, ACCOUNT);
        // 대기 단계에서 인터럽트되므로 order 생성·접수 루프는 시작조차 하지 않아야 함
        verify(orderPort, never()).saveAll(anyList());
    }
```

- [ ] **Step 7: 신규 테스트만 우선 실행**

Run: `./gradlew test --tests 'com.kista.application.service.trading.TradingServiceTest'`
Expected: 전부 통과 (신규 2개 포함)

- [ ] **Step 8: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 진단.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(trading): 스케쥴러 인터럽트 시 미처리 전략 사용자에게 알림 발송

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## 최종 검증

1. `./gradlew test` 전체 통과 (712+2개 이상)
2. `git diff` 대상 파일이 계획에 명시된 파일 목록과 정확히 일치하는지 확인 (`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`/`CycleOrderComputer` 미등장 확인)
3. `TradingOpenScheduler`/`TradingCloseScheduler`가 `placeOpenOrders`/`executeBatch`를 호출하는 시그니처는 변경되지 않았으므로 스케쥴러 자체 코드는 무변경 — `grep -rn "placeOpenOrders\|executeBatch" src/main/java/com/kista/adapter/in/schedule/`로 호출부 시그니처 불일치 없는지 확인
