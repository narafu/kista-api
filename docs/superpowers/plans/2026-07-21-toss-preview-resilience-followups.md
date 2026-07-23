# Toss 미리보기 복원력 후속 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 앞선 `2026-07-21-toss-token-retry-backoff` 작업에서 스코프 밖으로 남겨둔 두 가지 후속 개선을 구현한다 — (1) 계좌 관리자(공통) 토큰 경로의 401 재시도를 계좌 토큰 경로와 동일한 백오프·재시도 횟수로 통일하고, (2) 바로주문 미리보기에서 라이브 예수금 조회가 재시도까지 모두 실패해도 전체 요청이 503으로 막히지 않고 주문 계획은 정상 반환하도록 경쟁 시뮬레이션만 우아하게 생략한다.

**Architecture:** `TossHttpClient`의 계좌 토큰 재시도 로직(`executeWithRetry`)과 관리자 토큰 재시도 로직(`executeCommon`/`execute401Retry`)을 공통 헬퍼 `executeWithBackoffRetry`로 통합해 중복을 제거하고 백오프·재시도 횟수를 동기화한다. `TradingBuyCompetitionSimulator.simulate()`는 대상 전략 자신의 라이브 예수금 조회를 try/catch로 감싸 브로커 예외 발생 시 `BuyCompetitionPreview.unavailable()` 팩토리로 만든 결과를 즉시 반환한다.

**Tech Stack:** Java 21, Spring Boot 3, Mockito, JUnit 5, AssertJ

---

### Task 1: TossHttpClient — 관리자(공통) 토큰 경로 401 재시도를 계좌 토큰 경로와 통합

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/toss/TossHttpClient.java`
- Test: `src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java`

**배경:** `getCommon()`(시세·환율·캔들 등 공통 API)은 여전히 `execute401Retry`를 사용해 401 발생 시 관리자 토큰 무효화 후 **1회만** 재시도한다. 계좌 토큰 경로(`executeWithRetry`)는 지난 작업에서 300ms/600ms 백오프를 포함한 **최대 2회** 재시도로 강화됐다. 운영 로그상 공통 API 경로에서 실패 사례는 아직 없지만, 동일한 Toss 토큰 재발급 지연 현상이 발생할 수 있는 동일 인프라이므로 두 경로의 재시도 정책을 통일해 향후 동일 원인의 실패를 사전에 방어한다.

- [ ] **Step 1: 실패하는 테스트 작성 — 공통 API도 401을 두 번 겪고 세 번째에 성공**

`src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java`의 `unauthorized()` 헬퍼 아래, 기존 두 테스트 뒤에 추가:

```java
    @Test
    @DisplayName("공통 API(getCommon) 401도 최대 2회까지 백오프 재시도 후 성공")
    void getCommon_retriesTwiceAfter401_thenSucceeds() {
        when(tossAuthApi.getAdminToken())
                .thenReturn("admin-token-0", "admin-token-1", "admin-token-2");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().getCommon(PATH, new LinkedMultiValueMap<>(), String.class);

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi, times(2)).invalidateAdminToken();
        verify(tossAuthApi, times(3)).getAdminToken();
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("공통 API 401이 세 번(최초+2차 재시도 모두) 발생하면 TossApiException")
    void getCommon_throwsTossApiException_when401Persists() {
        when(tossAuthApi.getAdminToken())
                .thenReturn("admin-token-0", "admin-token-1", "admin-token-2");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized());

        TossHttpClient client = newClient();
        assertThatThrownBy(() -> client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi, times(2)).invalidateAdminToken();
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `bash gradlew test --tests "com.kista.adapter.out.toss.TossHttpClientTest"`
Expected: FAIL — `tossAuthApi.getAdminToken()`이 stub된 3회 대신 1회만 호출되어 `UnnecessaryStubbingException` 또는 `times(3)` 검증 실패, `invalidateAdminToken()` 호출 횟수 불일치로 실패

- [ ] **Step 3: `TossHttpClient.java` 구현 — 공통 재시도 헬퍼로 통합**

`src/main/java/com/kista/adapter/out/toss/TossHttpClient.java`의 `getCommon` 두 오버로드(현재 41-48줄, 79-87줄), `executeWithRetry`(126-146줄), `buildAdminHeaders()`(158-164줄), `executeCommon`/`execute401Retry`(166-191줄)를 아래로 교체:

```java
    // Class<T> 응답용 공통 API — 관리자 토큰, 401 시 최대 MAX_RETRY_ATTEMPTS회 백오프 재시도
    public <T> T getCommon(String path, MultiValueMap<String, String> params, Class<T> responseType) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithBackoffRetry(path, tossAuthApi::getAdminToken, token -> tossAuthApi.invalidateAdminToken(),
                token -> {
                    HttpHeaders headers = buildAdminHeaders(token);
                    return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
                });
    }
```

(파일 79번째 줄 근처 `getCommon` ParameterizedTypeReference 오버로드는 그대로 두고, 아래 `getCommon(Class<T>)` 바로 다음 위치가 아니라 원래 위치에서 교체한다 — 두 `getCommon` 오버로드 모두 동일 패턴 적용)

```java
    // ParameterizedTypeReference<T> 응답용 공통 API (제네릭 래퍼 타입 역직렬화용)
    public <T> T getCommon(String path, MultiValueMap<String, String> params,
                           ParameterizedTypeReference<T> typeRef) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + path).queryParams(params).toUriString();
        return executeWithBackoffRetry(path, tossAuthApi::getAdminToken, token -> tossAuthApi.invalidateAdminToken(),
                token -> {
                    HttpHeaders headers = buildAdminHeaders(token);
                    return tossRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), typeRef).getBody();
                });
    }
```

`executeWithRetry`를 아래로 교체 (계좌 토큰 경로 — 공통 헬퍼에 위임):

```java
    // 계좌 토큰 재시도 — 공통 헬퍼(executeWithBackoffRetry)에 계좌별 토큰 조회/무효화만 주입
    private <T> T executeWithRetry(Account account, String path, java.util.function.Function<String, T> call) {
        return executeWithBackoffRetry(path,
                () -> tossAuthApi.getToken(account.id(), account.appKey(), account.secretKey()),
                token -> tossAuthApi.invalidateToken(account.id(), token),
                call);
    }
```

`buildAdminHeaders()`(무인자)를 아래로 교체:

```java
    // 관리자 토큰 헤더 — X-Tossinvest-Account 없이 Bearer 토큰만 (매 시도의 토큰을 인자로 받는다)
    private HttpHeaders buildAdminHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
```

`executeCommon`/`execute401Retry` 두 메서드를 삭제하고 아래 공통 헬퍼로 대체:

```java
    // 401 재시도 시 백오프 간격(ms) — 재발급 직후 토큰이 Toss 리소스 서버에 즉시 반영되지 않는 경우 대응
    private static final long RETRY_BACKOFF_MILLIS = 300;
    // 최초 시도 이후 허용하는 최대 401 재시도 횟수
    private static final int MAX_RETRY_ATTEMPTS = 2;

    // 401 → 실패한 요청의 토큰만 무효화 후 최신 토큰으로 최대 MAX_RETRY_ATTEMPTS회 재시도한다.
    // 재시도 사이 짧은 백오프를 둬 갓 재발급된 토큰의 리소스 서버 반영 지연을 흡수한다.
    // 계좌 토큰(executeWithRetry)·관리자 토큰(getCommon) 양쪽이 공유하는 재시도 골격.
    private <T> T executeWithBackoffRetry(String path, java.util.function.Supplier<String> tokenFetcher,
                                           java.util.function.Consumer<String> tokenInvalidator,
                                           java.util.function.Function<String, T> call) {
        String token = tokenFetcher.get();
        for (int attempt = 0; ; attempt++) {
            try {
                return call.apply(token);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() != 401) {
                    throw new TossApiException("Toss API 오류: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
                }
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    throw new TossApiException("Toss API 토큰 재시도 실패: " + e.getMessage(), e);
                }
                log.warn("Toss 401 — 토큰 무효화 후 재시도 {}/{}: path={}", attempt + 1, MAX_RETRY_ATTEMPTS, path);
                tokenInvalidator.accept(token);
                sleepBackoff(attempt);
                token = tokenFetcher.get();
            } catch (RestClientException e) {
                throw new TossApiException("Toss API 요청 실패: " + e.getMessage(), e);
            }
        }
    }

    // 재시도 간 백오프 — 인터럽트 시 상태만 복원하고 즉시 재시도 진행(대기 없이)
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
```

파일 전체에서 `execute401Retry`/`executeCommon`/무인자 `buildAdminHeaders()`를 참조하는 곳이 남아있지 않은지 확인한다 (둘 다 `getCommon` 오버로드 2곳에서만 호출되던 private 메서드).

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `bash gradlew test --tests "com.kista.adapter.out.toss.TossHttpClientTest"`
Expected: PASS (기존 2개 + 신규 2개 = 4개 테스트 모두 통과)

- [ ] **Step 5: Toss 패키지 전체 회귀 테스트**

Run: `bash gradlew test --tests "com.kista.adapter.out.toss.*"`
Expected: PASS (`TossAuthApiTest`, `TossHoldingsApiTest` 등 기존 테스트 영향 없음)

- [ ] **Step 6: 컴파일 검증**

Run: `bash gradlew compileJava`
Expected: BUILD SUCCESSFUL — orphan이 된 import(`java.util.function.Supplier` 등은 executeWithBackoffRetry 시그니처에서 계속 사용되므로 유지) 없는지 확인

- [ ] **Step 7: 문서 갱신**

`docs/agents/toss-api.md`에서 "`TossHttpClient`는 조건부 무효화 후..." 문단을 찾아, 계좌 토큰 경로 설명 뒤에 아래 문장을 추가한다:

```
- 관리자(공통) 토큰 경로(`getCommon`)도 동일한 `executeWithBackoffRetry` 헬퍼를 공유해 계좌 토큰 경로와 같은 백오프(300ms/600ms)·최대 2회 재시도 정책을 적용한다.
```

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/toss/TossHttpClient.java src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java docs/agents/toss-api.md
git commit -m "refactor(toss): 관리자 토큰 401 재시도를 계좌 토큰 경로와 통합"
```

---

### Task 2: TradingBuyCompetitionSimulator — 라이브 예수금 조회 실패 시 미리보기 전체 실패 방지

**Files:**
- Modify: `src/main/java/com/kista/domain/model/order/BuyCompetitionPreview.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingBuyCompetitionSimulator.java`
- Test: `src/test/java/com/kista/application/service/trading/TradingBuyCompetitionSimulatorTest.java`

**배경:** `TradingBuyCompetitionSimulator.simulate()`는 경쟁 전략(타 전략)의 계산 실패는 `catch (Exception e)`로 잡아 `uncertainStrategyIds`에 기록하고 0으로 취급하지만(현재 76-93줄), **대상 전략 자신의** 라이브 예수금 조회(`registry.require(account, LiveBalancePort.class).getLiveBalance(...)`, 현재 53-55줄)는 어떤 catch에도 감싸여 있지 않다. 이 호출에서 브로커 예외(`KisApiException`/`TossApiException`)가 던져지면 `TradingPreviewService.preview()`(`@Transactional(readOnly=true)`) 전체가 `GlobalExceptionHandler`에 의해 503으로 처리되어, 정상 계산 가능한 주문 계획(`plan.orders()`)까지 함께 유실된다. 이번 작업은 이 한 곳만 감싸 경쟁 시뮬레이션 결과를 "조회 불가" 상태로 대체하고, 주문 계획 자체는 정상 반환되게 한다.

- [ ] **Step 1: 실패하는 테스트 작성 — 라이브 예수금 조회 실패 시 unavailable 프리뷰 반환**

`src/test/java/com/kista/application/service/trading/TradingBuyCompetitionSimulatorTest.java`의 마지막 테스트(`simulate_excludesPausedStrategy`) 뒤에 추가:

```java
    @Test
    void simulate_returnsUnavailablePreview_whenLiveBalanceFetchFails() {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenThrow(new com.kista.domain.model.toss.TossApiException("Toss API 토큰 재시도 실패: 401", null));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.liveBalanceUnavailable()).isTrue();
        assertThat(result.sufficientBudget()).isTrue();
        assertThat(result.availableDeposit()).isNull();
        assertThat(result.requiredForThisStrategy()).isEqualByComparingTo("200.00");
        assertThat(result.consumedByHigherPriority()).isEqualByComparingTo("0");
        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.uncertainStrategyIds()).isEmpty();
        verifyNoInteractions(strategyPort); // 경쟁 전략 조회 자체를 시작하지 않고 즉시 반환
    }
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `bash gradlew test --tests "com.kista.application.service.trading.TradingBuyCompetitionSimulatorTest"`
Expected: FAIL — 컴파일 오류(`BuyCompetitionPreview.liveBalanceUnavailable()` 메서드 없음) 또는 `TossApiException`이 테스트 밖으로 전파되어 실패

- [ ] **Step 3: `BuyCompetitionPreview.java` 구현 — `liveBalanceUnavailable` 필드 + `unavailable()` 팩토리 추가**

`src/main/java/com/kista/domain/model/order/BuyCompetitionPreview.java` 전체를 아래로 교체:

```java
package com.kista.domain.model.order;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// 바로주문 미리보기 시 계좌 내 BUY 예산 경쟁 시뮬레이션 결과 — TradingBuyCompetitionSimulator 산출
// 실제 야간 배치(TradingOrderBudgetAllocator)와 동일한 우선순위 정렬을 재현한 근사치
public record BuyCompetitionPreview(
        boolean sufficientBudget,                        // 대상 전략 BUY가 실제 배치에서 승인될지 근사 판정 (liveBalanceUnavailable=true면 신뢰 불가)
        BigDecimal availableDeposit,                      // 라이브 예수금 - 타 전략 당일 PLANNED BUY 합계 (liveBalanceUnavailable=true면 null)
        BigDecimal requiredForThisStrategy,               // 대상 전략 오늘자 BUY 합계
        BigDecimal consumedByHigherPriority,              // 대상 전략보다 우선순위 앞선 경쟁 전략 필요금액 합
        List<CompetingStrategy> blockedByHigherPriority,  // 우선순위 정렬 순서(높은 순) 유지
        List<UUID> uncertainStrategyIds,                  // 계산 실패/skip돼 0으로 처리된 전략 id
        boolean liveBalanceUnavailable                    // true면 라이브 예수금 조회 자체가 실패해 경쟁 시뮬레이션을 생략함
) {
    // 라이브 예수금 조회 실패(브로커 토큰 재시도 소진 등) 시 사용 — 주문 계획은 정상 반환하되 경쟁 판정만 생략
    public static BuyCompetitionPreview unavailable(BigDecimal requiredForThisStrategy) {
        return new BuyCompetitionPreview(true, null, requiredForThisStrategy, BigDecimal.ZERO, List.of(), List.of(), true);
    }

    // 경쟁 전략 1건 — priority는 CycleOrderStrategy.allocationPriority() 값(작을수록 먼저 승인)
    public record CompetingStrategy(
            UUID strategyId,
            Strategy.Type type,
            Strategy.Ticker ticker,
            BigDecimal requiredBuyUsd,
            int priority
    ) {}
}
```

- [ ] **Step 4: `TradingBuyCompetitionSimulator.java` 구현 — 라이브 예수금 조회를 try/catch로 감싸기**

`src/main/java/com/kista/application/service/trading/TradingBuyCompetitionSimulator.java` 상단 import에 추가:

```java
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.toss.TossApiException;
```

`simulate()` 메서드 시작부(현재 47-56줄)를 아래로 교체:

```java
    BuyCompetitionPreview simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle,
                                    List<Order> currentBuyOrders, LocalDate today,
                                    BigDecimal otherStrategiesPlannedBuyUsd) {
        BigDecimal requiredForThis = AccountBalance.buyTotal(currentBuyOrders);

        BigDecimal liveDeposit;
        try {
            // 라이브 예수금에서 타 전략의 당일 PLANNED BUY만 차감 — 대상 전략 자신의 기존 예약분은
            // requiredForThis가 매번 전체 재계산이라 이미 반영돼 있으므로 이중 차감하지 않는다.
            // PLACED 주문은 브로커에 이미 접수돼 라이브 예수금 자체에 반영돼 있어 별도 차감 불필요.
            liveDeposit = registry.require(account, LiveBalancePort.class)
                    .getLiveBalance(account, currentStrategy.ticker())
                    .usdDeposit();
        } catch (KisApiException | TossApiException e) {
            // 브로커 라이브 예수금 조회 자체가 실패(토큰 재시도 소진 등) — 미리보기 전체를 503으로 막지 않고
            // 경쟁 시뮬레이션만 생략한 채 주문 계획(plan.orders())은 정상 반환한다
            log.warn("대상 전략 라이브 예수금 조회 실패, 경쟁 시뮬레이션 생략: strategyId={}, error={}", currentStrategy.id(), e.getMessage());
            return BuyCompetitionPreview.unavailable(requiredForThis);
        }
        BigDecimal availableDeposit = liveDeposit.subtract(otherStrategiesPlannedBuyUsd);
```

이후 기존 로직(`uncertainStrategyIds` 선언부터 끝까지)은 그대로 유지한다.

- [ ] **Step 5: `BuyCompetitionPreview` 생성자를 직접 호출하는 기존 테스트 3곳에 7번째 인자 추가**

필드 추가로 컴파일이 깨지는 기존 호출부를 아래와 같이 수정한다 (모두 정상 경로이므로 `liveBalanceUnavailable=false`).

`src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java:91-92`:
```java
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of(), false);
```

`src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java:117-118`:
```java
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of(), false);
```

`src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java:31-35`:
```java
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                false, new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("900.00"),
                List.of(new BuyCompetitionPreview.CompetingStrategy(
                        competitorId, Strategy.Type.VR, Ticker.TQQQ, new BigDecimal("900.00"), 0)),
                List.of(), false);
```

- [ ] **Step 6: 테스트 실행 — 통과 확인**

Run: `bash gradlew test --tests "com.kista.application.service.trading.TradingBuyCompetitionSimulatorTest"`
Expected: PASS (기존 6개 + 신규 1개 = 7개 테스트 모두 통과)

- [ ] **Step 7: trading·web.dto 패키지 전체 회귀 테스트**

Run: `bash gradlew test --tests "com.kista.application.service.trading.*" --tests "com.kista.adapter.in.web.dto.NextOrdersResponseTest"`
Expected: PASS

- [ ] **Step 8: 전체 컴파일 검증**

Run: `bash gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/domain/model/order/BuyCompetitionPreview.java src/main/java/com/kista/application/service/trading/TradingBuyCompetitionSimulator.java src/test/java/com/kista/application/service/trading/TradingBuyCompetitionSimulatorTest.java src/test/java/com/kista/application/service/trading/TradingPreviewServiceTest.java src/test/java/com/kista/adapter/in/web/dto/NextOrdersResponseTest.java
git commit -m "fix(trading): 라이브 예수금 조회 실패 시 미리보기 전체 503 대신 경쟁 시뮬레이션만 생략"
```

---

## 스코프 밖 (Out of Scope)

- **kista-ui 반영**: `BuyCompetitionPreview`에 `liveBalanceUnavailable` 필드가 추가되지만, 프론트엔드 타입(`entities/order/model/types.ts`)은 순수 TypeScript interface(런타임 스키마 검증 없음)라 필드 추가만으로는 깨지지 않는다. 다만 UI에서 이 값을 사용해 "예수금 확인 불가" 배지 등을 표시하려면 별도 UI 작업이 필요하다 — 이번 플랜의 범위 밖이다.
- **KIS(`KisHttpClient`) 401 재시도 강화**: 운영 로그상 KIS 경로에서 동일 실패 사례가 없어 그대로 유지한다.

## Self-Review

- **스펙 커버리지**: 이전 작업에서 명시적으로 남겨둔 두 항목(공통 토큰 경로 백오프 통일, 미리보기 그레이스풀 디그레이드) 모두 Task 1/2로 커버됨.
- **플레이스홀더 스캔**: 모든 스텝에 실제 코드/명령어 포함, "TODO"/"적절히 처리" 등 표현 없음.
- **타입 일관성**: `BuyCompetitionPreview` 생성자 필드 순서(`sufficientBudget, availableDeposit, requiredForThisStrategy, consumedByHigherPriority, blockedByHigherPriority, uncertainStrategyIds, liveBalanceUnavailable`)가 `unavailable()` 팩토리 및 신규 테스트 단언과 일치함을 확인. `executeWithBackoffRetry`의 파라미터 순서(`path, tokenFetcher, tokenInvalidator, call`)가 Task 1의 모든 호출부(계좌 경로 3-인자 → 4-인자, 공통 경로 2곳)에서 동일하게 사용됨을 확인.
