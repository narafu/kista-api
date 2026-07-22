# Toss Token Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Toss 401 복구 시 OAuth 토큰을 한 번만 갱신하고 이후 백오프 재시도에서는 같은 신규 토큰을 유지한다.

**Architecture:** 기존 `DoubleCheckedTokenCache`와 DB 조건부 무효화는 그대로 사용한다. `TossHttpClient`의 요청별 재시도 상태만 최초 401 갱신 단계와 신규 토큰 전파 대기 단계로 분리하고, 관리자 토큰에도 거절 토큰 일치 가드를 추가한다.

**Tech Stack:** Java 21, Spring Boot 3.4.4, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- KIS 토큰 재시도 정책은 변경하지 않는다.
- 계좌별 전체 API 요청을 직렬화하거나 별도 in-flight Future를 도입하지 않는다.
- 최초 401에서만 거절 토큰을 조건부 무효화하고 신규 토큰을 조회한다.
- 신규 토큰의 후속 401은 같은 토큰으로 백오프 재시도한다.
- 관리자 토큰은 거절 토큰과 현재 토큰이 일치할 때만 무효화한다.
- 429 debounce와 운영 실계좌 재현은 범위에서 제외한다.
- 신규 코드에는 프로젝트 `//` 주석 규칙을 적용한다.

---

### Task 1: 계좌 및 관리자 토큰 재시도 의미론 수정

**Files:**
- Modify: `src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java`
- Modify: `src/test/java/com/kista/adapter/out/toss/TossAuthApiTest.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossHttpClient.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossAuthApi.java`
- Modify: `docs/agents/toss-api.md`

**Interfaces:**
- Consumes: `TossAuthApi.getToken(UUID, String, String)`, `DoubleCheckedTokenCache.getOrFetch(...)`
- Produces: `TossAuthApi.invalidateAdminToken(String rejectedAccessToken)` and refresh-once `TossHttpClient.executeWithBackoffRetry(...)`

- [ ] **Step 1: 계좌 토큰을 한 번만 갱신하는 실패 테스트 작성**

`TossHttpClientTest.retriesSameFreshToken_whenPropagationIsDelayed()`에서 `getToken()`은 `token-0`, `token-1`만 반환하게 하고 HTTP 호출은 401, 401, 성공 순서로 설정한다. 각 `HttpEntity`의 Authorization 헤더를 캡처해 `Bearer token-0`, `Bearer token-1`, `Bearer token-1` 순서를 단언한다. `invalidateToken`은 `token-0`에 대해 한 번, `getToken`은 두 번 호출됐음을 단언한다.

`throwsAfterRetryLimit_withoutInvalidatingFreshToken()`에서도 세 번 모두 401을 반환하되 `token-0`만 무효화되고 `token-1`은 무효화되지 않음을 단언한다.

관리자 경로 테스트도 동일하게 최초 토큰과 신규 토큰 두 개만 사용하고 `invalidateAdminToken("admin-token-0")`이 한 번 호출되는 계약으로 변경한다.

- [ ] **Step 2: 집중 테스트를 실행해 RED 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.toss.TossHttpClientTest'`

Expected: 현재 구현이 `token-1`을 다시 무효화하고 세 번째 `getToken()`을 호출하므로 호출 횟수 또는 Authorization 헤더 순서 단언 실패.

- [ ] **Step 3: stale 관리자 401 보호 실패 테스트 작성**

`TossAuthApiTest`에 관리자 OAuth 발급을 `admin-token-1`로 stub하고 다음 흐름을 검증한다.

```java
String current = api.getAdminToken();
api.invalidateAdminToken("stale-admin-token");
String preserved = api.getAdminToken();

assertThat(current).isEqualTo("admin-token-1");
assertThat(preserved).isEqualTo("admin-token-1");
verify(tossRestTemplate, times(1)).exchange(
        anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class));
```

- [ ] **Step 4: 관리자 토큰 테스트를 실행해 RED 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.toss.TossAuthApiTest'`

Expected: `invalidateAdminToken(String)` 메서드가 없어 테스트 컴파일 실패.

- [ ] **Step 5: 최초 401에서만 갱신하도록 최소 구현**

`TossHttpClient.executeWithBackoffRetry`에 요청별 `boolean refreshed` 상태를 둔다. 401 한도 검사 후 `refreshed == false`이면 토큰 무효화, 백오프, 최신 토큰 조회, 상태 갱신을 수행한다. `refreshed == true`이면 토큰을 바꾸지 않고 백오프만 수행한다. 관리자 호출부는 `tossAuthApi::invalidateAdminToken`에 거절 토큰을 전달한다.

핵심 상태 전이는 다음과 같아야 한다.

```java
if (!refreshed) {
    log.warn("Toss 401 — {} 토큰 무효화 후 갱신 재시도 {}/{}: path={}",
            tokenKind, attempt + 1, MAX_RETRY_ATTEMPTS, path);
    tokenInvalidator.accept(token);
    sleepBackoff(attempt);
    token = tokenFetcher.get();
    refreshed = true;
} else {
    log.warn("Toss 401 — {} 신규 토큰 전파 대기 재시도 {}/{}: path={}",
            tokenKind, attempt + 1, MAX_RETRY_ATTEMPTS, path);
    sleepBackoff(attempt);
}
```

- [ ] **Step 6: 관리자 토큰 조건부 무효화 최소 구현**

`TossAuthApi.invalidateAdminToken`을 다음 계약으로 변경한다.

```java
public void invalidateAdminToken(String rejectedAccessToken) {
    adminLock.lock();
    try {
        if (java.util.Objects.equals(adminAccessToken, rejectedAccessToken)) {
            adminExpiresAt = OffsetDateTime.MIN;
        }
    } finally {
        adminLock.unlock();
    }
}
```

- [ ] **Step 7: 집중 테스트 GREEN 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.toss.TossHttpClientTest' --tests 'com.kista.adapter.out.toss.TossAuthApiTest' --tests 'com.kista.adapter.out.broker.DoubleCheckedTokenCacheTest' --tests 'com.kista.adapter.out.persistence.kistoken.KisTokenPersistenceAdapterTest'`

Expected: 모든 테스트 통과. persistence 테스트 DB가 없어서 실행 불가하면 앞의 세 단위 테스트를 통과시키고 DB 필요 사항을 보고한다.

- [ ] **Step 8: Toss 문서 동기화**

`docs/agents/toss-api.md`의 토큰 설명을 "최초 401에서만 조건부 무효화·갱신하고, 후속 백오프는 같은 신규 토큰을 재사용한다"로 변경한다. 관리자 토큰도 거절 토큰 일치 가드를 사용한다고 명시한다.

- [ ] **Step 9: 컴파일과 diff 검증**

Run: `./gradlew compileJava && git diff --check`

Expected: `BUILD SUCCESSFUL`, whitespace 오류 없음.

- [ ] **Step 10: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/toss/TossHttpClient.java \
        src/main/java/com/kista/adapter/out/toss/TossAuthApi.java \
        src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java \
        src/test/java/com/kista/adapter/out/toss/TossAuthApiTest.java \
        docs/agents/toss-api.md
git commit -m "fix(toss): 신규 토큰 전파 재시도에서 토큰 재사용"
```

