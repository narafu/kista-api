# Broker 어댑터 정책 통합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `com.kista.broker`(trading-core) 내 KIS/Toss 어댑터 3건을 정리한다 — (1) 매수가능금액 조회 실패 처리를 KIS 기준으로 통일(동작 변경), (2) 에러 바디 코드 분류 기법의 우연한 중복을 공용 디코더로 제거(동작 보존), (3) "확정종가 실패 시 현재가 폴백" 제어흐름 중복을 공용 헬퍼로 제거(동작 보존).

**Architecture:** 세 작업 모두 `trading-core/src/main/java/com/kista/broker/adapter/out/{kis,toss}` 내부 파일만 건드리고, 공용 부분은 기존 `com.kista.broker.adapter.out.internal`(ClosingPriceLoop 등이 있는 패키지)에 `public final` 정적 유틸 클래스로 추가한다. 브로커별 실제 오류 코드 값·조회 함수는 각자 소유 유지 — 기법만 공유.

**Tech Stack:** Java 21, Spring Boot 4, Jackson 3(`tools.jackson.databind`), JUnit5 + Mockito + MockRestServiceServer.

**Spec:** 별도 스펙 문서 없음 — 다른 Claude 세션(kista-api-ed)이 2026-09-25 cross-session 메시지로 위임한 broker 모듈 클린코드 후속 리팩토링 3건(전체 레포 감사에서 "동작 변경 가능성 있어 별도 처리"로 분류됨)을 이 세션이 현재 코드로 재검증해 구체화한 계획. 각 태스크에 근거 파일:라인을 명시했다 — 그것이 이 계획의 스펙 원문이다. 항목1(동작 변경)은 사용자에게 별도 확인 후 진행 승인받음.

## Global Constraints

- 매매공식·VR공식 변경 금지 — `docs/agents/modules/trading-formulas.md` (이 3건 모두 순수 에러처리/정책 구조라 산술과 무관해야 한다)
- `com.kista.broker`는 `Account`를 전혀 참조하지 않는다(기존 원칙) — 이번 변경 3건 모두 `Account` 미참조 유지
- interface default 메서드로 공용 로직을 두지 말 것 — Mockito mock이 default 메서드를 override해 단건 메서드만 stub한 기존 테스트가 NPE로 깨진다. 선례: `com.kista.broker.adapter.out.internal.ClosingPriceLoop`(plain static 헬퍼, 커밋 25d31e91) — Task2·Task3 신규 유틸 모두 이 형태를 그대로 따른다
- 신규 코드 주석은 인라인 `//`만 — Javadoc·블록 주석 금지 (`docs/agents/constraints.md` "주석 규칙")
- 커밋: author `narafu <narafu@kakao.com>` 확인, 커밋 메시지 한글. **Task1은 동작 변경이므로 `fix:` 접두사로 별도 커밋, Task2·Task3은 `refactor:` 접두사**
- `git push`는 사용자가 명시적으로 요청할 때만 — 이 플랜 실행 중 자동 push 금지
- 서브에이전트가 import를 수정하면 BOM(`\xef\xbb\xbf`)이 파일 앞에 삽입되는 사례가 있었다 — 각 태스크 커밋 전 `grep -rl $'\xef\xbb\xbf' trading-core/src --include="*.java"`로 확인, 걸리면 `sed -i '1s/^\xef\xbb\xbf//' <file>`로 제거
- ObjectMapper는 `tools.jackson.databind.ObjectMapper`(Jackson 3, Spring Boot 4 기본) — 기존 사용 예: `UserEventStreamBridge`(`trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamBridge.java:12,28`). **테스트에서는 실제 인스턴스(`new tools.jackson.databind.ObjectMapper()`)를 사용하고 Mockito `@Mock`으로 만들지 말 것** — mock의 `readValue()`는 기본적으로 null을 반환해 디코딩이 항상 실패(빈 Optional)로 빠지고, 기존 rate-limit/conflict 테스트가 전부 깨진다
- 커밋 전 검토자 검수 필수 — SDD의 task review가 이를 담당한다

## Review Focus

1. **Task1 — 기존 테스트가 구 동작을 단언 중**: `TossHoldingsApiTest.getBalance_nullResponse_returnsZeroBalance()`(`trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHoldingsApiTest.java:88-100`)가 "매수가능금액 응답 null → usdDeposit=0"을 단언한다. Task1이 이 경로를 예외로 바꾸므로 이 테스트를 고치지 않으면 반드시 실패한다.
2. **Task1 — 예외 전파 범위가 잔고조회 전체로 넓어짐**: `TossHoldingsApi.getBalance()`(`:49-73`)가 내부에서 `getUsdBuyableAmount()`를 호출하므로, `LiveBalancePort.getBalance()`를 쓰는 모든 소비처(`TradingOrderBudgetAllocator`, `ManualTradingService`, `PreviewDepositCache` 등, `trading-core/src/main/java/com/kista/trading/application/service/`)가 Toss 계좌에서 새로 예외를 만날 수 있다. KIS는 이미 이 경로에서 throw하므로 신규 위험이 아니라 대칭화이지만, 구현자는 이 소비처들이 예외를 적절히 처리(catch 후 알림/skip 등)하는지 확인하고 보고서에 남긴다.
3. **Task2 — KIS·Toss 에러 바디 구조가 다르다**: KIS는 top-level `msg_cd` 필드(`{"rt_cd":"1",...,"msg_cd":"EGW00201",...}`), Toss는 중첩 `error.code` 필드(`{"error":{"code":"already-filled",...}}`). 디코딩 record를 하나로 합치려 하지 말 것 — 각자 별도 record.
4. **Task2 — Jackson unknown-property 기본값 차이**: Spring이 관리하는 `ObjectMapper` 빈은 Spring Boot가 `FAIL_ON_UNKNOWN_PROPERTIES=false`로 기본 설정하지만, 테스트에서 `new ObjectMapper()`로 직접 생성하면 Jackson 기본값(`true`)이 적용돼 알려지지 않은 필드가 하나라도 있으면 디코딩이 예외로 실패한다. 두 record 모두 `@JsonIgnoreProperties(ignoreUnknown = true)`를 명시해 양쪽 환경에서 안전하게 만든다.
5. **Task3 — Toss의 두 실패 사유가 서로 다른 로그 문구를 가진다**: "캔들 없음"과 "조회 예외"는 현재 각각 다른 `log.warn` 메시지를 남긴다(`TossPriceApi.java:194`, `:198`). 추출 후에도 두 문구 모두 보존해야 한다 — 하나로 합치지 말 것.

---

### Task 1: Toss 매수가능금액 조회 실패 처리를 KIS와 동일하게 예외로 통일 (fix)

**근거:** `KisTradingApi.getMargin()`(`trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisTradingApi.java:76-78`)은 응답이 null이면 `KisApiException`을 던진다. `TossHoldingsApi.fetchBuyingPower()`(`trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHoldingsApi.java:150-160`)는 동일 상황(매수가능금액 응답 없음)에서 `BigDecimal.ZERO`를 반환한다 — 같은 계약(매수가능금액 조회)에 정반대 실패 처리가 붙어있는 우연한 불일치.

**Files:**
- Modify: `trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHoldingsApi.java:150-160`
- Test: `trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHoldingsApiTest.java`

**Interfaces:**
- Consumes: 기존 `TossApiException(String message, Throwable cause)` 생성자(`trading-core/src/main/java/com/kista/broker/domain/model/toss/TossApiException.java:16-18`)
- Produces: `fetchBuyingPower()`의 실패 시그니처가 `BigDecimal.ZERO 반환` → `TossApiException throw`로 바뀐다. 이 메서드를 호출하는 `getUsdBuyableAmount()`, `getMargin()`(virtual thread Future 경유, `await()`가 `RuntimeException`을 그대로 재전파하므로 `TossApiException`도 그대로 전파됨), `getBalance()`, `getPresentBalance()` 전부가 영향받는다 — 이후 태스크는 없으니 후속 태스크에 영향 없음.

- [ ] **Step 1: 실패 케이스 실패 테스트 작성**

`trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHoldingsApiTest.java`의 기존 `getBalance_nullResponse_returnsZeroBalance()`(88-100행)를 아래 두 테스트로 교체한다(이름·내용 모두 교체 — 기존 테스트는 곧 바뀔 구 동작을 단언하므로 유지 불가):

```java
    @Test
    @DisplayName("보유 종목 응답 null: holdings=0, 매수가능금액은 정상 반영")
    void getBalance_holdingsResponseNull_buyingPowerValid_returnsZeroHoldingsWithDeposit() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse("500.00", "USD")));

        BrokerBalance balance = tossHoldingsApi.getBalance(ACCOUNT, StrategyTicker.SOXL);

        assertThat(balance.holdings()).isEqualTo(0);
        assertThat(balance.avgPrice()).isNull();
        assertThat(balance.usdDeposit()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("매수가능금액 응답 null: TossApiException 전파 (KIS getMargin()과 동일 처리)")
    void getBalance_buyingPowerResponseNull_throwsTossApiException() {
        when(tossHttpClient.get(eq("/api/v1/holdings"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.HoldingsResponse(List.of())));
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);

        assertThatThrownBy(() -> tossHoldingsApi.getBalance(ACCOUNT, StrategyTicker.SOXL))
            .isInstanceOf(TossApiException.class);
    }
```

그리고 기존 `getUsdBuyableAmount_returnsAmount()`(102-111행) 바로 아래에 아래 두 테스트를 추가한다:

```java
    @Test
    @DisplayName("getUsdBuyableAmount: wrapper null → TossApiException")
    void getUsdBuyableAmount_nullWrapper_throwsTossApiException() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(null);

        assertThatThrownBy(() -> tossHoldingsApi.getUsdBuyableAmount(ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("getUsdBuyableAmount: cashBuyingPower 필드 null → TossApiException")
    void getUsdBuyableAmount_nullCashBuyingPower_throwsTossApiException() {
        when(tossHttpClient.get(eq("/api/v1/buying-power"), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossHoldingsApi.BuyableAmountResponse(null, "USD")));

        assertThatThrownBy(() -> tossHoldingsApi.getUsdBuyableAmount(ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.toss.TossHoldingsApiTest'`
예상: 새로 추가/교체한 4개 테스트가 FAIL (구현이 아직 ZERO를 반환하므로 `assertThatThrownBy`가 예외 없음으로 실패, 첫 번째 신규 테스트는 기존 로직으로도 통과할 수 있으나 나머지 3개는 실패)

- [ ] **Step 3: 구현 변경**

`trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHoldingsApi.java`의 `fetchBuyingPower()`(150-160행)를 아래로 교체:

```java
    // currency 파라미터로 매수가능금액 단건 조회
    private BigDecimal fetchBuyingPower(BrokerAccountRef account, String currencyCode) {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("currency", currencyCode);
        TossResult<BuyableAmountResponse> wrapper = tossHttpClient.get(
                BUYING_POWER_PATH, account, params,
                new ParameterizedTypeReference<TossResult<BuyableAmountResponse>>() {});
        if (wrapper == null || wrapper.result() == null || wrapper.result().cashBuyingPower() == null) {
            // KIS KisTradingApi.getMargin()과 동일 처리 — 조용한 0 반환은 잔고 부족으로 오판정될 수 있어 예외로 전파
            throw new TossApiException("Toss 매수가능금액 응답 없음: currency=" + currencyCode, null);
        }
        return new BigDecimal(wrapper.result().cashBuyingPower());
    }
```

- [ ] **Step 4: 테스트 재실행해 통과 확인**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.toss.TossHoldingsApiTest'`
예상: 전체 PASS

- [ ] **Step 5: LiveBalancePort 소비처 확인 (Review Focus #2)**

`trading-core/src/main/java/com/kista/trading/application/service/TradingOrderBudgetAllocator.java`, `ManualTradingService.java`, `PreviewDepositCache.java`에서 `LiveBalancePort.getBalance()` 호출부를 읽고, Toss 계좌에서 새로 던져질 수 있는 `TossApiException`이 (a) 이미 상위에서 catch되어 계좌 단위 실패 격리·알림으로 처리되는지, (b) 처리되지 않아 배치 전체가 죽는 경로가 있는지 확인한다. 처리되지 않는 경로를 발견하면 코드를 고치지 말고 구현 보고서(NEEDS_CONTEXT 또는 DONE_WITH_CONCERNS)에 정확한 파일:라인과 함께 기록한다 — 컨트롤러가 판단한다.

- [ ] **Step 6: 전체 broker 테스트 컴파일·실행**

실행: `./gradlew :trading-core:test --tests 'com.kista.broker.*'`
예상: PASS (BUILD SUCCESSFUL)

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHoldingsApi.java trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHoldingsApiTest.java
git commit -m "$(cat <<'EOF'
fix(broker): Toss 매수가능금액 조회 실패 시 0 대신 예외 전파

KIS getMargin()은 이미 응답 없음을 KisApiException으로 처리하는데
Toss fetchBuyingPower()만 조용히 BigDecimal.ZERO를 반환해 같은 계약에
정반대 실패 처리가 붙어있었다. KIS 기준으로 통일한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AdkcvDuQC8TEtkzVrsY7N5
EOF
)"
```

---

### Task 2: KIS/Toss 에러 바디 코드 분류를 공용 디코더로 정리 (refactor, 동작 보존)

**근거:** `KisHttpClient.isRateLimited()`(`trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisHttpClient.java:176-178`)는 응답 바디 substring(`.contains("\"msg_cd\":\"EGW00201\"")`)으로 오류 코드를 분류한다. 같은 파일 주석(175행)이 이미 "Toss의 code 문자열 매칭과 동일 패턴"이라 명시. `TossHttpClient`의 409 분류 로직(`trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHttpClient.java:150-155`)도 동일 substring 기법의 거울상 구현. **분류 기법 자체는 우연한 중복(ACCIDENTAL), 브로커별 실제 코드값(EGW00201/already-filled/already-canceled)은 계약(CONTRACTUAL)** — 코드값을 하나로 합치지 않는다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/broker/adapter/out/internal/ErrorBodyDecoder.java`
- Modify: `trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisHttpClient.java`
- Modify: `trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHttpClient.java`
- Test: `trading-core/src/test/java/com/kista/broker/adapter/out/kis/KisHttpClientTest.java`
- Test: `trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHttpClientTest.java`

**Interfaces:**
- Produces: `ErrorBodyDecoder.decode(ObjectMapper objectMapper, String body, Class<T> type) : Optional<T>` — `com.kista.broker.adapter.out.internal` 패키지의 `public final` 정적 유틸(생성자 private). body가 null/blank거나 디코딩 실패 시 `Optional.empty()`.
- Consumes: `KisHttpClient`/`TossHttpClient` 둘 다 `@RequiredArgsConstructor`로 기존 필드 뒤에 `private final tools.jackson.databind.ObjectMapper objectMapper;`를 추가해 Spring이 주입하는 빈을 그대로 사용한다(신규 인스턴스 생성 금지).

- [ ] **Step 1: 공용 디코더 작성**

`trading-core/src/main/java/com/kista/broker/adapter/out/internal/ErrorBodyDecoder.java` 신규 생성:

```java
package com.kista.broker.adapter.out.internal;

import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

// KIS/Toss 오류 응답 바디에서 분류용 필드를 디코딩하는 공용 기법 — substring .contains() 매칭 대신
// 브로커별 작은 record로 JSON 디코딩한다. 실제 오류 코드 값(EGW00201/already-filled 등)은 각 어댑터
// 소유로 남는다 — 이 클래스는 "디코딩해서 비교한다"는 기법만 공유한다(ClosingPriceLoop와 동일 취지).
public final class ErrorBodyDecoder {

    private ErrorBodyDecoder() {
    }

    public static <T> Optional<T> decode(ObjectMapper objectMapper, String body, Class<T> type) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(objectMapper.readValue(body, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 2: KisHttpClient 기존 테스트로 회귀 기준 확보**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.kis.KisHttpClientTest'`
예상: 현재 상태에서 전체 PASS (이후 리팩토링이 이 결과를 바꾸지 않아야 한다)

- [ ] **Step 3: KisHttpClient 변경**

`trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisHttpClient.java` 상단 import에 추가:

```java
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.broker.adapter.out.internal.ErrorBodyDecoder;
import tools.jackson.databind.ObjectMapper;
```

필드 선언부(26-31행) `@Value("${kis.base-url}") private final String baseUrl;` 바로 아래에 추가:

```java
    private final ObjectMapper objectMapper;
```

`isRateLimited()`(176-178행)를 아래로 교체:

```java
    // KIS 게이트웨이가 초당 거래건수 제한으로 접수 전 거부한 응답 — msg_cd 필드 디코딩 후 정확 매칭
    // (substring 대신 JSON 필드 비교, 다른 오류 메시지 오탐 방지. Toss의 error.code 필드 매칭과 동일 기법)
    private boolean isRateLimited(HttpStatusCodeException e) {
        return ErrorBodyDecoder.decode(objectMapper, e.getResponseBodyAsString(), KisErrorBody.class)
                .map(body -> "EGW00201".equals(body.msgCd()))
                .orElse(false);
    }

    // KIS 오류 응답 바디 — msg_cd 필드만 사용, 그 외 필드(rt_cd/msg1/message 등)는 무시
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KisErrorBody(@JsonProperty("msg_cd") String msgCd) {}
```

(클래스 마지막 닫는 `}` 직전, `sleepRateLimitBackoff()` 메서드 뒤에 `KisErrorBody` record를 둔다.)

- [ ] **Step 4: KisHttpClientTest 생성자 호출부 수정**

`trading-core/src/test/java/com/kista/broker/adapter/out/kis/KisHttpClientTest.java` 상단 import에 추가:

```java
import tools.jackson.databind.ObjectMapper;
```

`newClient()`(61-63행)를 아래로 교체:

```java
    private KisHttpClient newClient() {
        return new KisHttpClient(restClientBuilder.build(), kisAuthApi, BASE_URL, new ObjectMapper());
    }
```

- [ ] **Step 5: KIS 회귀 테스트 재실행**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.kis.KisHttpClientTest'`
예상: Step2와 동일하게 전체 PASS (동작 보존 확인)

- [ ] **Step 6: TossHttpClient 기존 테스트로 회귀 기준 확보**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.toss.TossHttpClientTest'`
예상: 현재 상태에서 전체 PASS

- [ ] **Step 7: TossHttpClient 변경**

`trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHttpClient.java` 상단 import에 추가:

```java
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.broker.adapter.out.internal.ErrorBodyDecoder;
import tools.jackson.databind.ObjectMapper;
```

필드 선언부(26-31행) `@Value("${toss.base-url}") private final String baseUrl;` 바로 아래에 추가:

```java
    private final ObjectMapper objectMapper;
```

`executeWithBackoffRetry()` 내부 409 분류 블록(현재 148-155행, 아래 `body.contains(...)` 두 줄)을 교체한다. 교체 전 원본:

```java
                    TossApiException.Conflict conflict = TossApiException.Conflict.NONE;
                    if (e.getStatusCode().value() == 409) {
                        if (body.contains("\"code\":\"already-filled\"")) {
                            conflict = TossApiException.Conflict.ALREADY_FILLED;
                        } else if (body.contains("\"code\":\"already-canceled\"")) {
                            conflict = TossApiException.Conflict.ALREADY_CANCELED;
                        }
                    }
```

교체 후:

```java
                    TossApiException.Conflict conflict = TossApiException.Conflict.NONE;
                    if (e.getStatusCode().value() == 409) {
                        conflict = ErrorBodyDecoder.decode(objectMapper, body, TossErrorBody.class)
                                .map(TossErrorBody::error)
                                .map(TossErrorBody.ErrorDetail::code)
                                .map(code -> switch (code) {
                                    case "already-filled" -> TossApiException.Conflict.ALREADY_FILLED;
                                    case "already-canceled" -> TossApiException.Conflict.ALREADY_CANCELED;
                                    default -> TossApiException.Conflict.NONE;
                                })
                                .orElse(TossApiException.Conflict.NONE);
                    }
```

클래스 마지막(`buildHeaders()` 메서드 뒤, 닫는 `}` 직전)에 record 추가:

```java

    // Toss 오류 응답 바디 — error.code 필드만 사용, 그 외 필드(error.message 등)는 무시
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TossErrorBody(@JsonProperty("error") ErrorDetail error) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorDetail(@JsonProperty("code") String code) {}
    }
```

- [ ] **Step 8: TossHttpClientTest 생성자 호출부 3곳 수정**

`trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHttpClientTest.java` 상단 import에 추가:

```java
import tools.jackson.databind.ObjectMapper;
```

3개 생성 지점을 각각 4번째 인자 `new ObjectMapper()` 추가로 교체:

`newClient()`(76-79행):
```java
    private TossHttpClient newClient() {
        setUpServer();
        return new TossHttpClient(restClientBuilder.build(), tossAuthApi, BASE_URL, new ObjectMapper());
    }
```

366행·415행의 `TossHttpClient client = new TossHttpClient(sharedClient, realAuthApi, BASE_URL);`를 각각:
```java
        TossHttpClient client = new TossHttpClient(sharedClient, realAuthApi, BASE_URL, new ObjectMapper());
```

- [ ] **Step 9: Toss 회귀 테스트 재실행**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.toss.TossHttpClientTest'`
예상: Step6과 동일하게 전체 PASS (동작 보존 확인 — 특히 `get_409AlreadyCanceled_setsAlreadyCanceledConflictFlag`·`get_409AlreadyFilled_doesNotSetAlreadyCanceledConflictFlag`)

- [ ] **Step 10: broker 패키지 전체 컴파일·테스트**

실행: `./gradlew :trading-core:test --tests 'com.kista.broker.*'`
예상: BUILD SUCCESSFUL

- [ ] **Step 11: 커밋**

```bash
git add trading-core/src/main/java/com/kista/broker/adapter/out/internal/ErrorBodyDecoder.java trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisHttpClient.java trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossHttpClient.java trading-core/src/test/java/com/kista/broker/adapter/out/kis/KisHttpClientTest.java trading-core/src/test/java/com/kista/broker/adapter/out/toss/TossHttpClientTest.java
git commit -m "$(cat <<'EOF'
refactor(broker): KIS/Toss 에러 바디 코드 분류를 공용 디코더로 정리

substring .contains() 매칭 기법이 KIS(EGW00201)·Toss(already-filled/
already-canceled) 양쪽에 거울상으로 중복돼 있었다. 기법(JSON 필드
디코딩)만 ErrorBodyDecoder로 공유하고, 브로커별 실제 코드값은 각자
소유로 남긴다. 동작 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AdkcvDuQC8TEtkzVrsY7N5
EOF
)"
```

---

### Task 3: "확정종가 실패 시 현재가 폴백" 제어흐름을 공용 헬퍼로 정리 (refactor, 동작 보존)

**근거:** `KisPriceApi.getClosingPrice()`(`trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java:130-132`)와 `TossPriceApi.getClosingPrice()`(`trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java:186-201`) 둘 다 "확정 종가 조회 실패 시 현재가로 대체"라는 동일 정책을 각자 구현하고 있다. Toss 쪽 주석(184행)이 "KisPriceApi.fetchConfirmedClose와 동일 규칙"이라고 이미 명시. **정책(제어흐름)만 중복, 실제 조회 함수(브로커별 wire call)는 당연히 분리 유지.**

**Files:**
- Create: `trading-core/src/main/java/com/kista/broker/adapter/out/internal/ConfirmedCloseFallback.java`
- Modify: `trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java:130-132`
- Modify: `trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java:186-201`

**Interfaces:**
- Produces: `ConfirmedCloseFallback.resolve(Supplier<Optional<BigDecimal>> confirmedCloseLookup, Supplier<BigDecimal> livePriceLookup) : BigDecimal` — `com.kista.broker.adapter.out.internal` 패키지의 `public final` 정적 유틸(생성자 private). interface default 메서드로 만들지 않는다(Global Constraints 참고).
- Consumes: 없음(순수 함수형 인자 기반)

- [ ] **Step 1: 공용 헬퍼 작성**

`trading-core/src/main/java/com/kista/broker/adapter/out/internal/ConfirmedCloseFallback.java` 신규 생성:

```java
package com.kista.broker.adapter.out.internal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;

// KIS/Toss가 공유하는 "확정종가 조회 → 실패 시 현재가 폴백" 제어흐름 — 각 브로커의 실제 조회 함수는
// 함수형 인자로 주입받는다. BrokerPricePort default 메서드로 두지 않는 이유는 ClosingPriceLoop와 동일:
// Mockito mock이 default 메서드를 override해 단건 조회만 stub한 기존 테스트에서 연결이 끊긴다.
public final class ConfirmedCloseFallback {

    private ConfirmedCloseFallback() {
    }

    // confirmedCloseLookup: 브로커별 확정종가 조회(검증·예외처리를 자체 포함, 실패 시 Optional.empty())
    // livePriceLookup: 확정종가 조회 실패 시 대체할 라이브 현재가 조회
    public static BigDecimal resolve(Supplier<Optional<BigDecimal>> confirmedCloseLookup,
                                      Supplier<BigDecimal> livePriceLookup) {
        return confirmedCloseLookup.get().orElseGet(livePriceLookup);
    }
}
```

- [ ] **Step 2: KIS/Toss 기존 getClosingPrice 테스트로 회귀 기준 확보**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.kis.KisPriceApiTest' --tests 'com.kista.broker.adapter.out.toss.TossPriceApiTest'`
예상: 현재 상태에서 전체 PASS

- [ ] **Step 3: KisPriceApi 변경**

`trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java` 상단 import에 추가:

```java
import com.kista.broker.adapter.out.internal.ConfirmedCloseFallback;
```

`getClosingPrice()`(130-132행)를 아래로 교체:

```java
    // 정규장 확정 종가 — 마감 리포트 전용(dailyprice, HHDFS76240000). 실패/미발행 시 라이브 현재가로 폴백
    // (fetchConfirmedClose 내부 검증 → "하루 전 종가를 오늘 종가로 오기록"하는 사고 방지)
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        return ConfirmedCloseFallback.resolve(
                () -> fetchConfirmedClose(ticker, tradeDate, account),
                () -> getPrice(ticker, account));
    }
```

(`fetchConfirmedClose()` 메서드 본체는 이미 `Optional<BigDecimal>`을 반환하며 내부에서 자체 검증·warn 로그를 수행하므로 변경 없음.)

- [ ] **Step 4: KIS 회귀 테스트 재실행**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.kis.KisPriceApiTest'`
예상: Step2와 동일하게 전체 PASS (특히 `getClosingPrice_usesDailyPriceWhenDateMatches`·`getClosingPrice_fallsBackToLivePriceWhenBarDateMismatches`·`getClosingPrice_fallsBackToLivePriceWhenDailyPriceEmpty`)

- [ ] **Step 5: TossPriceApi 변경 — fetchConfirmedClose 추출**

`trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java` 상단 import에 추가:

```java
import com.kista.broker.adapter.out.internal.ConfirmedCloseFallback;
```

`getClosingPrice()`(186-201행)를 아래로 교체 — 기존 로직을 `fetchConfirmedClose()` private 메서드로 추출하고(두 warn 로그 문구는 그대로 보존), 공개 메서드는 `ConfirmedCloseFallback.resolve()` 호출로 축소:

```java
    // 특정 거래일 확정 종가 — 일봉 캔들에서 해당 날짜 봉의 종가를 직접 조회 (라이브 현재가와 무관)
    // 실패/미발행 시 현재가로 폴백 (KIS KisPriceApi.getClosingPrice와 동일 정책, ConfirmedCloseFallback 공용)
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate) {
        return ConfirmedCloseFallback.resolve(
                () -> fetchConfirmedClose(ticker, tradeDate),
                () -> getPrice(ticker));
    }

    // Toss 캔들 date()는 US 세션일 기준이라 KST 거래일 D → US 세션 D-1로 변환 (KIS fetchConfirmedClose와 동일 규칙)
    // 봉 날짜가 기대 US 세션일과 다르면(미발행 등) filter에서 탈락 → Optional.empty()
    private Optional<BigDecimal> fetchConfirmedClose(StrategyTicker ticker, LocalDate tradeDate) {
        LocalDate usSessionDate = UsTradeDates.toUsTradeDate(tradeDate);
        try {
            Optional<BigDecimal> close = tossCandleApi.getCandles(ticker.name(), "1d", usSessionDate, usSessionDate).stream()
                    .filter(c -> c.date().equals(usSessionDate))
                    .findFirst()
                    .map(TossCandle::close);
            if (close.isEmpty()) {
                log.warn("Toss {} 확정 종가 캔들 없음(기대 US세션일={}), 현재가로 폴백: tradeDate={}", ticker, usSessionDate, tradeDate);
            }
            return close;
        } catch (Exception e) {
            log.warn("Toss {} 확정 종가 조회 실패, 현재가로 폴백: tradeDate={}, error={}", ticker, tradeDate, e.getMessage());
            return Optional.empty();
        }
    }
```

- [ ] **Step 6: Toss 회귀 테스트 재실행**

실행: `./gradlew test --tests 'com.kista.broker.adapter.out.toss.TossPriceApiTest'`
예상: Step2와 동일하게 전체 PASS (특히 `getClosingPrice_returnsUsSessionCloseForKstTradeDate`·`getClosingPrice_noCandleForUsSession_fallsBackToLivePrice`)

- [ ] **Step 7: broker 패키지 전체 컴파일·테스트**

실행: `./gradlew :trading-core:test --tests 'com.kista.broker.*'`
예상: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add trading-core/src/main/java/com/kista/broker/adapter/out/internal/ConfirmedCloseFallback.java trading-core/src/main/java/com/kista/broker/adapter/out/kis/KisPriceApi.java trading-core/src/main/java/com/kista/broker/adapter/out/toss/TossPriceApi.java
git commit -m "$(cat <<'EOF'
refactor(broker): 확정종가 실패시 현재가 폴백 제어흐름을 공용화

KIS·Toss getClosingPrice()가 "확정종가 조회 실패 시 현재가로 대체"라는
동일 정책을 각자 구현하고 있었다. 정책만 ConfirmedCloseFallback으로
공유하고 브로커별 실제 조회 함수는 함수형 인자로 유지한다.
interface default 메서드 대신 static 헬퍼로 둔 이유는 ClosingPriceLoop와
동일(Mockito mock의 default 메서드 override 함정). 동작 변경 없음.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AdkcvDuQC8TEtkzVrsY7N5
EOF
)"
```
