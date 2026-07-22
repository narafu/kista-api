# Toss Token Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Toss 401 복구 시 동시 요청이 최근 발급 세대를 공유해 OAuth 토큰을 한 번만 갱신하고, 백오프 재시도에서는 같은 최신 토큰을 유지한다.

**Architecture:** `DoubleCheckedTokenCache`의 기존 계좌 락 안에서 현재 캐시 토큰 비교·최근 발급 세대 보호·조건부 무효화·OAuth 발급을 원자화한다. 관리자 토큰도 `adminLock`으로 같은 정책을 적용한다. `TossHttpClient`는 최초 401에서 복구 토큰을 먼저 얻고 300ms 대기하며, 후속 401은 같은 토큰으로 600ms를 추가 대기한다.

**Tech Stack:** Java 21, Spring Boot 3.4.4, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- KIS 토큰 재시도 정책은 변경하지 않는다.
- 계좌별 전체 API 요청을 직렬화하거나 별도 in-flight Future를 도입하지 않는다.
- 최초 401은 같은 발급 락에서 최신/최근 발급 세대를 확인한 뒤 필요한 경우에만 조건부 무효화·신규 발급한다.
- 복구 토큰을 얻은 뒤 300ms 대기하고, 후속 401은 같은 토큰으로 600ms를 추가 대기한다.
- 관리자 토큰도 `adminLock`에서 현재/최근 발급 세대를 보호한다.
- 429 debounce와 운영 실계좌 재현은 범위에서 제외한다.
- 신규 코드에는 프로젝트 `//` 주석 규칙을 적용한다.

---

### Task 1: 계좌 및 관리자 토큰 재시도 의미론 수정 (기초 작업)

> 아래 Task 1은 최초 구현의 이력을 보존한다. 최종 복구 API·락 정책·백오프 순서는 Task 2가 대체한다.

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

### Task 2: 동시 요청 간 신규 발급 세대 보호

**Files:**
- Modify: `src/test/java/com/kista/adapter/out/broker/DoubleCheckedTokenCacheTest.java`
- Modify: `src/test/java/com/kista/adapter/out/toss/TossAuthApiTest.java`
- Modify: `src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java`
- Modify: `src/main/java/com/kista/adapter/out/broker/DoubleCheckedTokenCache.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossAuthApi.java`
- Modify: `src/main/java/com/kista/adapter/out/toss/TossHttpClient.java`
- Modify: `docs/agents/toss-api.md`

**Interfaces:**
- Produces: 동일 계좌 락 안에서 rejected token을 최신/최근 발급 세대와 비교하고 최신 토큰을 반환하는 원자적 복구 API
- Consumes: `BrokerTokenCachePort.findValidToken`, `invalidateToken`, 기존 OAuth fetcher

- [ ] **Step 1: staggered concurrency RED 테스트**

계좌 테스트는 A가 token-0 401 후 token-1을 발급한 직후 C가 token-1로 시작해 401을 받는 순서를 latch로 고정한다. token-1이 최근 발급 세대인 동안 C가 token-2를 발급하지 않고 token-1을 재사용하며 OAuth fetch count가 1인지 검증한다. 관리자 경로도 같은 순서를 검증한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.broker.DoubleCheckedTokenCacheTest' --tests 'com.kista.adapter.out.toss.TossAuthApiTest' --tests 'com.kista.adapter.out.toss.TossHttpClientTest'`

Expected: 현재 요청별 `refreshed` 상태에서는 늦은 요청이 token-1을 무효화하거나 token-2를 조회해 테스트 실패.

- [ ] **Step 3: 동일 계좌 락 기반 발급 세대 보호 구현**

`DoubleCheckedTokenCache`가 자신이 발급한 token과 발급 시각을 계좌별로 기록한다. 401 복구 API는 기존 `locks`의 동일 `ReentrantLock` 안에서 다음 순서를 원자적으로 수행한다.

1. 캐시의 현재 토큰이 rejected token과 다르면 현재 토큰 반환
2. rejected token이 전파 유예 시간 내 최근 발급 세대이면 그대로 반환
3. 그 외에는 rejected token을 조건부 무효화하고 OAuth 발급·저장 후 신규 세대 기록

관리자 토큰은 `adminLock`과 발급 시각 필드로 같은 정책을 구현한다. 전파 유예 시간은 재시도 백오프 총합보다 길어야 하며 상수와 주석으로 의도를 명시한다.

- [ ] **Step 4: 백오프 순서 수정**

최초 401에서는 원자적 복구 API로 최신 토큰을 먼저 획득한 다음 300ms 대기한다. 후속 401은 같은 토큰을 유지하고 600ms 대기한다.

- [ ] **Step 5: GREEN 및 회귀 확인**

Run: `./gradlew test --tests 'com.kista.adapter.out.broker.DoubleCheckedTokenCacheTest' --tests 'com.kista.adapter.out.toss.TossAuthApiTest' --tests 'com.kista.adapter.out.toss.TossHttpClientTest'`

Expected: 모든 테스트 통과.

- [ ] **Step 6: 문서·whitespace·컴파일 검증**

`docs/agents/toss-api.md`에 발급 세대 보호와 실제 백오프 순서를 반영한다.

Run: `./gradlew compileJava && git diff --check`

Expected: `BUILD SUCCESSFUL`, whitespace 오류 없음.

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/broker/DoubleCheckedTokenCache.java \
        src/main/java/com/kista/adapter/out/toss/TossAuthApi.java \
        src/main/java/com/kista/adapter/out/toss/TossHttpClient.java \
        src/test/java/com/kista/adapter/out/broker/DoubleCheckedTokenCacheTest.java \
        src/test/java/com/kista/adapter/out/toss/TossAuthApiTest.java \
        src/test/java/com/kista/adapter/out/toss/TossHttpClientTest.java \
        docs/agents/toss-api.md \
        docs/superpowers/specs/2026-07-22-toss-token-retry-sse-error-handling-design.md \
        docs/superpowers/plans/2026-07-22-toss-token-retry.md
git commit -m "fix(toss): 동시 401에서 신규 토큰 발급 세대 보호"
```
