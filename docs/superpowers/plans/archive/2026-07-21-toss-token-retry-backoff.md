# Toss API 토큰 재시도 백오프 강화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TossHttpClient.executeWithRetry()`가 401 재시도 시 갓 재발급받은 토큰마저 즉시 거부되는 경우(운영 `app_error_logs`에서 관측된 `TossApiException: 토큰 재시도 실패`) 최소 지연을 두고 한 번 더 재시도해 자연 복구 가능성을 높인다.

**Architecture:** `TossHttpClient.executeWithRetry`의 재시도 횟수를 1회에서 2회(짧은 백오프 포함)로 늘린다. 재시도 사이 `Thread.sleep()`으로 Toss 서버측 토큰 전파 지연을 흡수한다(Virtual Thread 환경이라 안전). KIS(`KisHttpClient`)는 동일 구조지만 14일간 재시도 실패 로그가 0건이라 이번 변경 대상에서 제외 — Toss 고유 증상만 다룬다.

**Tech Stack:** Java 21, Spring `RestTemplate`, JUnit 5 + Mockito

**참고 — 진단 근거:**
- 운영 `app_error_logs` 14일치 전수 조회 결과 `TossApiException` 25건, 전부 `/api/v1/holdings` 또는 `/api/v1/buying-power` 401 "invalid-token", 호출 스택은 항상 `TradingCycleController.preview → TradingPreviewService.preview → TradingBuyCompetitionSimulator.simulate → TossBrokerAdapter.getLiveBalance → TossHoldingsApi.getBalance/fetchBuyingPower → TossHttpClient.executeWithRetry`.
- 계좌 테이블에 TOSS 계좌가 1개(`ab744a24-...`)뿐이라 재현 빈도가 낮아 보일 뿐, TOSS 브로커 계좌라면 동일하게 발생할 수 있는 구조적 문제로 판단.
- `KisHttpClient.executeWithRetry`는 동일한 "1회 재시도" 구조인데 같은 기간 재시도 실패 로그가 0건 — 재시도 로직 자체의 설계 결함(락·조건부 무효화 레이스)이 아니라 Toss 고유의 외부 동작(재발급 직후 토큰이 리소스 서버에 즉시 반영되지 않거나, 실제 만료 시각이 응답 `expires_in`보다 짧음)일 가능성이 가장 유력하다. **단, 이 메커니즘은 Toss 쪽 로그가 없어 확정할 수 없는 가설이며, 아래 수정은 "원인 자체를 고친다"가 아니라 "일시적 거부를 흡수할 여유를 늘린다"는 완화책이다.**
- `docs/agents/toss-api.md`가 명시한 "1회만 재시도" 정책을 이번 변경으로 "최대 2회 재시도(짧은 백오프 포함)"로 바꾸므로 문서도 함께 갱신한다.

**스코프 밖 — 별도 확인 필요:** `preview()`는 `@Transactional(readOnly = true)` 내부에서 실행되므로 재시도 백오프만큼 DB 커넥션을 더 오래 붙잡는다(최대 약 0.9초 추가). 이 자체는 허용 범위로 보이나, "재시도가 전부 실패해도 preview 전체를 503으로 실패시키지 않고 부분 응답으로 우아하게 degrade" 하는 방안은 `BuyCompetitionPreview` 응답 구조 변경이 필요한 별도 스코프라 이번 플랜에는 포함하지 않았다. 필요하면 후속 플랜으로 분리 제안.

---

### Task 1: `TossHttpClient` 재시도 정책 강화 (1회 → 최대 2회, 백오프 포함)

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/toss/TossHttpClient.java:119-139` (`executeWithRetry`)
- Test: `src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java` (신규 생성)
- Modify: `docs/agents/toss-api.md:18-21` ("토큰·인증" 섹션)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java` 신규 생성 (기존 `KisHttpClientTest.java` 패턴 참고 — `TossHttpClient`는 package-private이라 같은 패키지에 위치):

```java
package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossHttpClient 401 재시도·백오프 검증")
class TossHttpClientTest {

    @Mock RestTemplate tossRestTemplate;
    @Mock TossAuthApi tossAuthApi; // 구체 클래스 직접 mock

    private static final String PATH = "/api/v1/holdings";

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "12345678901", "cid", "csecret", "1",
            Account.Broker.TOSS, null
    );

    private TossHttpClient newClient() {
        return new TossHttpClient(tossRestTemplate, tossAuthApi, "http://toss.test");
    }

    private HttpClientErrorException unauthorized() {
        return HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    @DisplayName("401이 두 번(최초+1차 재시도) 발생해도 2차 재시도에서 성공")
    void retriesTwiceAfter401_thenSucceeds() {
        when(tossAuthApi.getToken(any(), anyString(), anyString()))
                .thenReturn("token-0", "token-1", "token-2");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {});

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).invalidateToken(ACCOUNT.id(), "token-0");
        verify(tossAuthApi).invalidateToken(ACCOUNT.id(), "token-1");
        verify(tossAuthApi, times(3)).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("401이 세 번(최초+2차 재시도 모두) 발생하면 TossApiException")
    void throwsTossApiException_when401Persists() {
        when(tossAuthApi.getToken(any(), anyString(), anyString()))
                .thenReturn("token-0", "token-1", "token-2");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(unauthorized());

        TossHttpClient client = newClient();
        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {}))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).invalidateToken(ACCOUNT.id(), "token-0");
        verify(tossAuthApi).invalidateToken(ACCOUNT.id(), "token-1");
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.out.toss.TossHttpClientTest'`
Expected: 두 테스트 모두 FAIL — 현재 코드는 재시도를 1회만 수행하므로 `retriesTwiceAfter401_thenSucceeds`는 두 번째 401에서 바로 `TossApiException`을 던져 성공 케이스가 깨지고, `throwsTossApiException_when401Persists`는 `getToken`/`exchange` 호출 횟수 검증(`times(3)`)이 실제 2회와 불일치해 실패한다.

- [ ] **Step 3: `executeWithRetry` 최소 구현 — 백오프 포함 최대 2회 재시도**

`TossHttpClient.java`의 `executeWithRetry`를 아래로 교체 (기존 119-139번 라인):

```java
    // 401 재시도 시 백오프 간격(ms) — 재발급 직후 토큰이 Toss 리소스 서버에 즉시 반영되지 않는 경우 대응
    private static final long RETRY_BACKOFF_MILLIS = 300;
    // 최초 시도 이후 허용하는 최대 401 재시도 횟수
    private static final int MAX_RETRY_ATTEMPTS = 2;

    // 401 → 실패한 요청의 토큰만 조건부 무효화 후 최신 토큰으로 최대 MAX_RETRY_ATTEMPTS회 재시도한다.
    // 재시도 사이 짧은 백오프를 둬 갓 재발급된 토큰의 리소스 서버 반영 지연을 흡수한다.
    private <T> T executeWithRetry(Account account, String path, java.util.function.Function<String, T> call) {
        String token = tossAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
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
                tossAuthApi.invalidateToken(account.id(), token);
                sleepBackoff(attempt);
                token = tossAuthApi.getToken(account.id(), account.appKey(), account.secretKey());
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

기존 `RestClientException` import는 그대로 유지된다(변경 없음).

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.out.toss.TossHttpClientTest'`
Expected: PASS (2 tests)

- [ ] **Step 5: 전체 Toss 어댑터 테스트로 회귀 확인**

Run: `bash gradlew test --tests 'com.kista.adapter.out.toss.*'`
Expected: 기존 `TossHoldingsApiTest`, `TossAuthApiTest`, `TossOrderApiTest`, `TossPriceApiTest`, `TossBrokerAdapterTest`, `TossResponseParserTest` 모두 PASS (executeWithRetry 내부만 변경했고 시그니처·호출 방식은 동일하므로 영향 없어야 함)

- [ ] **Step 6: `docs/agents/toss-api.md` 정책 설명 갱신**

`docs/agents/toss-api.md` 21번 라인:

```
- `TossHttpClient`는 조건부 무효화 후 캐시의 최신 토큰을 다시 읽어 동일 요청을 1회만 재시도한다. 헤더 빌더 내부에서 토큰을 재조회하지 말고, `executeWithRetry`가 고정한 시도별 토큰을 사용해야 한다.
```

아래로 교체:

```
- `TossHttpClient`는 조건부 무효화 후 캐시의 최신 토큰을 다시 읽어 동일 요청을 최대 2회(백오프 300ms/600ms 포함) 재시도한다 — 갓 재발급된 토큰이 Toss 리소스 서버에 즉시 반영되지 않아 재시도 직후에도 401이 나는 사례(운영 `app_error_logs` 관측)에 대응. 헤더 빌더 내부에서 토큰을 재조회하지 말고, `executeWithRetry`가 고정한 시도별 토큰을 사용해야 한다.
```

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/toss/TossHttpClient.java \
        src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java \
        docs/agents/toss-api.md
git commit -m "$(cat <<'EOF'
fix(toss): 401 재시도에 백오프·추가 재시도 1회 도입

운영 app_error_logs에서 재발급 토큰마저 즉시 401로 거부되는 사례(단일 TOSS
계좌 기준 14일간 25건, 전부 preview 미리보기 경로)를 확인. 재시도 직후에도
같은 회차 실패로 끝나는 구조라 짧은 백오프(300ms/600ms)를 두고 최대 2회까지
재시도하도록 완화한다. KIS는 동일 구조에서 같은 기간 재시도 실패 0건이라
이번 변경은 Toss 전용으로 한정.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

- **스펙 커버리지**: 진단된 유일한 증상(재시도까지 401 지속 → `TossApiException`)에 대한 완화 코드·테스트·문서 갱신을 Task 1에서 모두 다룸. `execute401Retry`(관리자 공통 토큰 경로)는 운영 로그에 해당 실패 사례가 없어 스코프에서 제외 — 필요 시 별도 플랜.
- **플레이스홀더 스캔**: 모든 스텝에 실제 코드·명령어·기대 결과 명시, TBD 없음.
- **타입 일관성**: `executeWithRetry` 시그니처(`Account`, `String path`, `Function<String,T>`)는 변경 없이 유지 — 호출부(`get`/`post`/`delete`/`executeGet`) 전부 그대로 재사용 가능.
