# Phase 2D Feature Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회원탈퇴 백엔드·UI, KIS API test-connection 백엔드·UI, 텔레그램 callback_query 제거 — 3개 독립 기능 완성.

**Architecture:** kista-api (Hexagonal — UseCase port/in → Service → port/out → Adapter), kista-ui (Next.js 16 Server/Client Component + Route Handler). 실행 순서: T1(회원탈퇴 백엔드) → T2(회원탈퇴 UI) → T3(test-connection 백엔드) → T4(test-connection UI) → T5(텔레그램 제거) → T6(최종 검증).

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Security 6, RestTemplate, Next.js 16, TypeScript, Tailwind CSS

---

## Context

- **기준 브랜치**: `main` (kista-api + kista-ui 모두)
- **작업 디렉토리**: `/Users/phs/workspace/kista/kista-api` (백엔드), `/Users/phs/workspace/kista/kista-ui` (프론트)
- **User record 필드 순서**: `UUID id, String kakaoId, String nickname, UserStatus status, UserRole role, String telegramBotToken, String telegramChatId, Instant createdAt, Instant updatedAt, Instant lastReappliedAt`
- **userRepository.delete(UUID)**: 이미 `UserRepository.delete(UUID id)` 존재 (AdminService에서 사용 중)
- **UserService.findOrThrow()**: 기존 private 헬퍼 메서드 활용
- **KisProperties.baseUrl()**: `"https://openapi.koreainvestment.com:9443"`
- **kisRestTemplate 빈**: `KisConfig.java`에서 정의, KisConnectionTestAdapter에서 재사용
- **AccountController Route Handler**: `app/api/accounts/[[...path]]/route.ts`가 모든 HTTP 메서드 + path를 프록시 → `/api/accounts/test-connection` POST 자동 처리됨, 별도 Route Handler 불필요
- **logout route**: `app/api/auth/logout/route.ts` 패턴 참고 (쿠키 삭제 방식)
- **TelegramBotService 생성자**: Lombok `@RequiredArgsConstructor` — 필드 삭제 시 생성자 인자도 자동 감소

---

## Task 1: 회원탈퇴 백엔드 (kista-api)

**Files:**
- Create: `src/main/java/com/kista/domain/port/in/DeleteMeUseCase.java`
- Modify: `src/main/java/com/kista/application/service/UserService.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AuthController.java`
- Modify: `src/test/java/com/kista/application/service/UserServiceTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AuthControllerTest.java`

- [ ] **Step 1**: `DeleteMeUseCase` 인터페이스 생성

```java
// src/main/java/com/kista/domain/port/in/DeleteMeUseCase.java
package com.kista.domain.port.in;

import java.util.UUID;

public interface DeleteMeUseCase {
    // 본인 계정 삭제 — cascade로 accounts/kis_tokens/trade_histories/portfolio_snapshots 자동 삭제
    void deleteMe(UUID userId);
}
```

- [ ] **Step 2**: `UserServiceTest`에 실패하는 테스트 추가

`UserServiceTest.java`에 다음 import 추가:
```java
import com.kista.domain.port.in.DeleteMeUseCase;
```

테스트 메서드 추가 (기존 테스트 클래스 내부):
```java
@Test
@DisplayName("회원 탈퇴 시 userRepository.delete 호출")
void deleteMe_removesUser() {
    UUID id = UUID.randomUUID();
    when(userRepository.findById(id)).thenReturn(Optional.of(pendingUser(id)));

    userService.deleteMe(id);

    verify(userRepository).delete(id);
}

@Test
@DisplayName("존재하지 않는 사용자 탈퇴 시 NoSuchElementException")
void deleteMe_userNotFound_throws() {
    UUID id = UUID.randomUUID();
    when(userRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.deleteMe(id))
            .isInstanceOf(NoSuchElementException.class);
}
```

- [ ] **Step 3**: 테스트 실행 → 컴파일 실패 확인

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test --tests 'com.kista.application.service.UserServiceTest.deleteMe_removesUser' 2>&1 | tail -20
```

Expected: 컴파일 에러 (deleteMe 메서드 없음)

- [ ] **Step 4**: `UserService`에 `DeleteMeUseCase` 구현 추가

`UserService.java`의 클래스 선언 `implements` 목록에 `DeleteMeUseCase` 추가:
```java
public class UserService implements RegisterUserUseCase, ApproveUserUseCase, GetUserUseCase, UpdateUserTelegramUseCase, DeleteMeUseCase {
```

`UserService.java`에 메서드 추가 (기존 메서드 끝에):
```java
@Override
public void deleteMe(UUID userId) {
    findOrThrow(userId); // 존재 확인 — 없으면 NoSuchElementException
    userRepository.delete(userId);
    log.info("사용자 탈퇴: userId={}", userId);
}
```

- [ ] **Step 5**: UserService 테스트 통과 확인

```bash
./gradlew test --tests 'com.kista.application.service.UserServiceTest' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6**: `AuthControllerTest`에 실패하는 테스트 추가

`AuthControllerTest.java`에 import 추가:
```java
import com.kista.domain.port.in.DeleteMeUseCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
```

`@MockBean` 추가 (기존 `@MockBean` 목록 아래):
```java
@MockBean DeleteMeUseCase deleteMe;
```

테스트 메서드 추가:
```java
@Test
@DisplayName("회원 탈퇴 — 인증 후 204 반환")
void deleteMe_authenticated_returns204() throws Exception {
    mockMvc.perform(delete("/api/auth/me")
                    .with(csrf())
                    .with(authentication(auth())))
            .andExpect(status().isNoContent());
}

@Test
@DisplayName("회원 탈퇴 — 비인증 시 401 반환")
void deleteMe_anonymous_returns401() throws Exception {
    mockMvc.perform(delete("/api/auth/me").with(csrf()))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 7**: 테스트 실행 → 컴파일 실패 확인

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AuthControllerTest' 2>&1 | tail -20
```

Expected: 컴파일 에러 (DELETE /api/auth/me 없음)

- [ ] **Step 8**: `AuthController`에 `DELETE /api/auth/me` 추가

`AuthController.java`에 필드 추가 (기존 필드 목록 아래):
```java
private final DeleteMeUseCase deleteMe;
```

import 추가:
```java
import com.kista.domain.port.in.DeleteMeUseCase;
```

엔드포인트 추가 (기존 메서드 끝에):
```java
// 회원 탈퇴 — cascade로 계좌/거래내역/토큰 자동 삭제 (V16 FK CASCADE)
@Operation(summary = "회원 탈퇴", description = "본인 계정 및 모든 연관 데이터(계좌, 거래내역 등)를 즉시 삭제합니다.")
@ApiResponse(responseCode = "204", description = "탈퇴 성공")
@DeleteMapping("/me")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void deleteMe(@AuthenticationPrincipal UUID userId) {
    deleteMe.deleteMe(userId);
}
```

- [ ] **Step 9**: AuthController 테스트 통과 확인

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AuthControllerTest' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (3개 테스트 통과)

- [ ] **Step 10**: 커밋

```bash
cd /Users/phs/workspace/kista/kista-api
git add src/main/java/com/kista/domain/port/in/DeleteMeUseCase.java \
        src/main/java/com/kista/application/service/UserService.java \
        src/main/java/com/kista/adapter/in/web/AuthController.java \
        src/test/java/com/kista/application/service/UserServiceTest.java \
        src/test/java/com/kista/adapter/in/web/AuthControllerTest.java
git commit -m "feat(auth): add DELETE /api/auth/me for account deletion"
```

---

## Task 2: 회원탈퇴 프론트엔드 (kista-ui)

**Files:**
- Create: `app/api/auth/me/route.ts`
- Create: `components/settings/DeleteAccountButton.tsx`
- Modify: `app/(main)/settings/page.tsx`

- [ ] **Step 1**: Route Handler 생성

```typescript
// app/api/auth/me/route.ts
import { NextResponse } from 'next/server'
import { getAuthToken } from '@/lib/auth/token'

const API_BASE_URL = process.env.API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL
const TOKEN_COOKIE = 'kista-token'
const STATUS_COOKIE = 'kista-user-status'
const ROLE_COOKIE = 'kista-user-role'

export async function DELETE() {
  const token = await getAuthToken()
  if (!token) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })

  const res = await fetch(`${API_BASE_URL}/api/auth/me`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  })

  if (!res.ok) {
    console.error(`[DELETE /api/auth/me] ${res.status}`)
    return NextResponse.json({ error: 'Failed' }, { status: res.status })
  }

  // 탈퇴 성공 — 3개 인증 쿠키 삭제
  const response = new NextResponse(null, { status: 204 })
  response.cookies.set(TOKEN_COOKIE, '', { maxAge: 0, path: '/' })
  response.cookies.set(STATUS_COOKIE, '', { maxAge: 0, path: '/' })
  response.cookies.set(ROLE_COOKIE, '', { maxAge: 0, path: '/' })
  return response
}
```

- [ ] **Step 2**: `DeleteAccountButton` Client Component 생성

```typescript
// components/settings/DeleteAccountButton.tsx
'use client'

import { useState } from 'react'

export function DeleteAccountButton() {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)

  async function handleDelete() {
    setLoading(true)
    try {
      const res = await fetch('/api/auth/me', { method: 'DELETE' })
      if (res.ok) {
        window.location.href = '/'
      } else {
        alert('탈퇴 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="px-4 py-2 rounded-[var(--r-md)] border border-neg/50 text-neg text-sm font-semibold hover:bg-neg/5 transition-colors"
      >
        회원 탈퇴
      </button>
      <p className="text-[11px] text-muted-foreground mt-2">
        탈퇴 시 모든 계좌·거래 데이터가 즉시 삭제됩니다
      </p>

      {open && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-card border border-border rounded-[var(--r-lg)] p-6 w-[320px] shadow-lg">
            <h3 className="text-base font-bold text-neg mb-2">정말 탈퇴하시겠습니까?</h3>
            <p className="text-sm text-muted-foreground mb-6">
              모든 계좌, 거래 내역, 설정이 즉시 삭제되며 복구할 수 없습니다.
            </p>
            <div className="flex gap-3">
              <button
                onClick={() => setOpen(false)}
                className="flex-1 py-2 rounded-[var(--r-md)] border border-border text-sm font-semibold hover:bg-muted transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleDelete}
                disabled={loading}
                className="flex-1 py-2 rounded-[var(--r-md)] bg-neg text-white text-sm font-semibold hover:bg-neg/90 disabled:opacity-60 transition-colors"
              >
                {loading ? '처리 중...' : '탈퇴 확인'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
```

- [ ] **Step 3**: `settings/page.tsx`에서 탈퇴 버튼 교체

`app/(main)/settings/page.tsx` 상단 import 추가:
```typescript
import { DeleteAccountButton } from '@/components/settings/DeleteAccountButton'
```

기존 위험 구역 섹션 (line 134-145):
```tsx
{/* 위험 구역 */}
<section id="danger" className="rounded-[var(--r-lg)] border border-neg/30 p-6">
  <h2 className="font-bold text-base text-neg mb-1">위험 구역</h2>
  <p className="text-sm text-muted-foreground mb-4">되돌릴 수 없는 작업입니다.</p>
  <button
    disabled
    title="곧 제공됩니다 (Phase 2)"
    className="px-4 py-2 rounded-[var(--r-md)] border border-neg/50 text-neg text-sm font-semibold opacity-40 cursor-not-allowed"
  >
    회원 탈퇴
  </button>
  <p className="text-[11px] text-muted-foreground mt-2">곧 제공됩니다</p>
</section>
```

교체:
```tsx
{/* 위험 구역 */}
<section id="danger" className="rounded-[var(--r-lg)] border border-neg/30 p-6">
  <h2 className="font-bold text-base text-neg mb-1">위험 구역</h2>
  <p className="text-sm text-muted-foreground mb-4">되돌릴 수 없는 작업입니다.</p>
  <DeleteAccountButton />
</section>
```

- [ ] **Step 4**: typecheck 통과 확인

```bash
cd /Users/phs/workspace/kista/kista-ui
npm run typecheck 2>&1 | tail -10
```

Expected: 에러 없음

- [ ] **Step 5**: 커밋

```bash
cd /Users/phs/workspace/kista/kista-ui
git add "app/api/auth/me/route.ts" \
        "components/settings/DeleteAccountButton.tsx" \
        "app/(main)/settings/page.tsx"
git commit -m "feat(settings): activate account deletion with confirmation dialog"
```

---

## Task 3: test-connection 백엔드 (kista-api)

**Files:**
- Create: `src/main/java/com/kista/domain/port/in/KisConnectionTestUseCase.java`
- Create: `src/main/java/com/kista/domain/port/out/KisConnectionTestPort.java`
- Create: `src/main/java/com/kista/application/service/KisConnectionTestService.java`
- Create: `src/main/java/com/kista/adapter/out/kis/KisConnectionTestAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/kis/KisConnectionTestAdapterTest.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AccountController.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AccountControllerTest.java`

- [ ] **Step 1**: Port 인터페이스 2개 생성

```java
// src/main/java/com/kista/domain/port/in/KisConnectionTestUseCase.java
package com.kista.domain.port.in;

public interface KisConnectionTestUseCase {
    // KIS OAuth 토큰 발급 시도로 자격증명 유효성 검증
    boolean test(String appKey, String appSecret);
}
```

```java
// src/main/java/com/kista/domain/port/out/KisConnectionTestPort.java
package com.kista.domain.port.out;

public interface KisConnectionTestPort {
    boolean test(String appKey, String appSecret);
}
```

- [ ] **Step 2**: `KisConnectionTestAdapterTest` 작성 (TDD — 먼저 테스트)

```java
// src/test/java/com/kista/adapter/out/kis/KisConnectionTestAdapterTest.java
package com.kista.adapter.out.kis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisConnectionTestAdapter 단위 테스트")
class KisConnectionTestAdapterTest {

    @Mock RestTemplate kisRestTemplate;

    KisConnectionTestAdapter adapter;

    private static final KisProperties TEST_PROPS = new KisProperties(
            "https://openapi.koreainvestment.com:9443", "key", "secret"
    );

    @BeforeEach
    void setUp() {
        adapter = new KisConnectionTestAdapter(kisRestTemplate, TEST_PROPS);
    }

    @Test
    @DisplayName("KIS OAuth 2xx 응답 시 true 반환")
    void test_whenKisReturns2xx_returnsTrue() {
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"tok\"}"));

        assertThat(adapter.test("appKey", "appSecret")).isTrue();
    }

    @Test
    @DisplayName("KIS OAuth 4xx 응답 시 false 반환")
    void test_whenKisReturns4xx_returnsFalse() {
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, new byte[]{}, null));

        assertThat(adapter.test("badKey", "badSecret")).isFalse();
    }
}
```

- [ ] **Step 3**: 테스트 실행 → 컴파일 실패 확인

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test --tests 'com.kista.adapter.out.kis.KisConnectionTestAdapterTest' 2>&1 | tail -15
```

Expected: 컴파일 에러 (KisConnectionTestAdapter 클래스 없음)

- [ ] **Step 4**: `KisConnectionTestAdapter` 구현

```java
// src/main/java/com/kista/adapter/out/kis/KisConnectionTestAdapter.java
package com.kista.adapter.out.kis;

import com.kista.domain.port.out.KisConnectionTestPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisConnectionTestAdapter implements KisConnectionTestPort {

    private final RestTemplate kisRestTemplate;
    private final KisProperties kisProperties;

    @Override
    public boolean test(String appKey, String appSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "appkey", appKey,
                "appsecret", appSecret
        );
        try {
            // KIS OAuth 토큰 발급 시도 — 성공 시 true, 인증 실패/네트워크 오류 시 false
            kisRestTemplate.exchange(
                    kisProperties.baseUrl() + "/oauth2/tokenP",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            return true;
        } catch (RestClientException e) {
            log.debug("KIS 연결 테스트 실패: {}", e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 5**: Adapter 테스트 통과 확인

```bash
./gradlew test --tests 'com.kista.adapter.out.kis.KisConnectionTestAdapterTest' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6**: `KisConnectionTestService` 구현

```java
// src/main/java/com/kista/application/service/KisConnectionTestService.java
package com.kista.application.service;

import com.kista.domain.port.in.KisConnectionTestUseCase;
import com.kista.domain.port.out.KisConnectionTestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KisConnectionTestService implements KisConnectionTestUseCase {

    private final KisConnectionTestPort connectionTestPort;

    @Override
    public boolean test(String appKey, String appSecret) {
        return connectionTestPort.test(appKey, appSecret);
    }
}
```

- [ ] **Step 7**: `AccountControllerTest`에 실패하는 테스트 추가

`AccountControllerTest.java`에 import 추가:
```java
import com.kista.domain.port.in.KisConnectionTestUseCase;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.anyString;
```

`@MockBean` 추가 (기존 목록 끝에):
```java
@MockBean KisConnectionTestUseCase connectionTest;
```

테스트 메서드 추가:
```java
@Test
void testConnection_success_returns200() throws Exception {
    when(connectionTest.test(anyString(), anyString())).thenReturn(true);

    mockMvc.perform(post("/api/accounts/test-connection")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                    .with(csrf()).with(authentication(mockAuth())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
}

@Test
void testConnection_anonymous_returns401() throws Exception {
    mockMvc.perform(post("/api/accounts/test-connection")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"appKey\":\"testkey1234\",\"appSecret\":\"testsecret1234\"}")
                    .with(csrf()))
            .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 8**: 테스트 실행 → 컴파일 실패 확인

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AccountControllerTest' 2>&1 | tail -15
```

Expected: 컴파일 에러 (`/api/accounts/test-connection` 없음)

- [ ] **Step 9**: `AccountController`에 엔드포인트 추가

`AccountController.java`에 필드 추가 (기존 필드 목록 끝에):
```java
private final KisConnectionTestUseCase connectionTest;
```

import 추가:
```java
import com.kista.domain.port.in.KisConnectionTestUseCase;
```

엔드포인트 추가 (기존 메서드 끝에):
```java
// KIS API 자격증명 연결 테스트 — 계좌 등록 전 사전 검증
@Operation(summary = "KIS API 연결 테스트", description = "appKey + appSecret으로 KIS OAuth 토큰 발급을 시도하여 자격증명 유효성 검증.")
@ApiResponse(responseCode = "200", description = "검증 완료 (success 필드로 결과 확인)")
@PostMapping("/test-connection")
public TestConnectionResponse testConnection(@AuthenticationPrincipal UUID userId,
                                             @RequestBody TestConnectionRequest request) {
    boolean success = connectionTest.test(request.appKey(), request.appSecret());
    String message = success ? null : "KIS API 인증에 실패했습니다. appKey 또는 appSecret을 확인하세요.";
    return new TestConnectionResponse(success, message);
}

record TestConnectionRequest(String appKey, String appSecret) {}    // KIS 자격증명
record TestConnectionResponse(boolean success, String message) {}   // 검증 결과
```

- [ ] **Step 10**: AccountController 테스트 통과 확인

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AccountControllerTest' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 11**: ArchUnit 통과 확인

```bash
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 12**: 커밋

```bash
cd /Users/phs/workspace/kista/kista-api
git add src/main/java/com/kista/domain/port/in/KisConnectionTestUseCase.java \
        src/main/java/com/kista/domain/port/out/KisConnectionTestPort.java \
        src/main/java/com/kista/application/service/KisConnectionTestService.java \
        src/main/java/com/kista/adapter/out/kis/KisConnectionTestAdapter.java \
        src/main/java/com/kista/adapter/in/web/AccountController.java \
        src/test/java/com/kista/adapter/out/kis/KisConnectionTestAdapterTest.java \
        src/test/java/com/kista/adapter/in/web/AccountControllerTest.java
git commit -m "feat(accounts): add POST /api/accounts/test-connection for KIS credential validation"
```

---

## Task 4: test-connection 프론트엔드 (kista-ui)

**Files:**
- Modify: `components/accounts/steps/ApiStep.tsx`

- [ ] **Step 1**: `ApiStep.tsx` 전체 교체

```typescript
// components/accounts/steps/ApiStep.tsx
'use client'

import { useState } from 'react'
import { Eye, EyeOff, CheckCircle2, XCircle, Loader2 } from 'lucide-react'
import type { StepData } from '../NewAccountStepper'

interface Props {
  data: StepData
  onNext: (payload: Partial<StepData>) => void
}

type TestStatus = null | 'testing' | 'ok' | 'fail'

export function ApiStep({ data, onNext }: Props) {
  const [apiKey, setApiKey] = useState(data.apiKey)
  const [apiSecret, setApiSecret] = useState(data.apiSecret)
  const [showSecret, setShowSecret] = useState(false)
  const [testStatus, setTestStatus] = useState<TestStatus>(null)
  const [testMessage, setTestMessage] = useState('')

  const canTest = apiKey.length >= 10 && apiSecret.length >= 10

  function handleKeyChange(setter: (v: string) => void) {
    return (e: React.ChangeEvent<HTMLInputElement>) => {
      setter(e.target.value)
      setTestStatus(null) // 키 변경 시 테스트 결과 초기화
    }
  }

  async function handleTest() {
    setTestStatus('testing')
    setTestMessage('')
    try {
      const res = await fetch('/api/accounts/test-connection', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ appKey: apiKey, appSecret: apiSecret }),
      })
      const json = await res.json()
      if (json.success) {
        setTestStatus('ok')
      } else {
        setTestStatus('fail')
        setTestMessage(json.message ?? 'KIS API 연결에 실패했습니다.')
      }
    } catch {
      setTestStatus('fail')
      setTestMessage('네트워크 오류가 발생했습니다.')
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="text-lg font-bold mb-1">KIS API 키 입력</h2>
        <p className="text-sm text-muted-foreground">한국투자증권 Open API 자격증명을 입력하세요.</p>
      </div>
      <div className="flex flex-col gap-4">
        <div>
          <label className="text-sm font-semibold mb-1.5 block">App Key</label>
          <input
            value={apiKey}
            onChange={handleKeyChange(setApiKey)}
            placeholder="발급받은 App Key"
            className="w-full px-3 py-2.5 rounded-[var(--r-md)] border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-rose-400"
          />
        </div>
        <div>
          <label className="text-sm font-semibold mb-1.5 block">App Secret</label>
          <div className="relative">
            <input
              type={showSecret ? 'text' : 'password'}
              value={apiSecret}
              onChange={handleKeyChange(setApiSecret)}
              placeholder="발급받은 App Secret"
              className="w-full px-3 py-2.5 pr-10 rounded-[var(--r-md)] border border-border bg-background text-sm focus:outline-none focus:ring-2 focus:ring-rose-400"
            />
            <button
              type="button"
              onClick={() => setShowSecret(s => !s)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            >
              {showSecret ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
            </button>
          </div>
        </div>
      </div>

      {/* 연결 테스트 */}
      <div className="flex flex-col gap-2">
        <button
          disabled={!canTest || testStatus === 'testing'}
          onClick={handleTest}
          className="w-full h-10 rounded-[var(--r-md)] border border-border text-sm font-semibold hover:bg-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-2"
        >
          {testStatus === 'testing' ? (
            <><Loader2 className="size-4 animate-spin" /> 연결 확인 중...</>
          ) : (
            '연결 테스트'
          )}
        </button>
        {testStatus === 'ok' && (
          <div className="flex items-center gap-1.5 text-[12.5px] text-status-ok">
            <CheckCircle2 className="size-4" /> 연결 성공
          </div>
        )}
        {testStatus === 'fail' && (
          <div className="flex items-center gap-1.5 text-[12.5px] text-neg">
            <XCircle className="size-4" /> {testMessage}
          </div>
        )}
      </div>

      <button
        disabled={testStatus !== 'ok'}
        onClick={() => onNext({ apiKey, apiSecret })}
        className="w-full h-11 rounded-[var(--r-md)] bg-rose-600 text-white font-semibold text-sm hover:bg-rose-700 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
      >
        다음
      </button>
    </div>
  )
}
```

- [ ] **Step 2**: typecheck 통과 확인

```bash
cd /Users/phs/workspace/kista/kista-ui
npm run typecheck 2>&1 | tail -10
```

Expected: 에러 없음 (`CheckCircle2`, `XCircle`, `Loader2`는 `lucide-react`에 포함됨)

- [ ] **Step 3**: 커밋

```bash
cd /Users/phs/workspace/kista/kista-ui
git add "components/accounts/steps/ApiStep.tsx"
git commit -m "feat(accounts): add KIS API connection test in NewAccount Step 1"
```

---

## Task 5: 텔레그램 callback_query 제거 (kista-api)

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java`
- Modify: `src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java`

- [ ] **Step 1**: `TelegramBotServiceTest`에서 callback 관련 코드 제거

`TelegramBotServiceTest.java` 변경 내용:

**제거 대상 1** — `@Mock` 선언 (line 33):
```java
@Mock ApproveUserUseCase approveUserUseCase;
```

**제거 대상 2** — `setUp()` 생성자 인자 (line 41-42):

변경 전:
```java
sut = new TelegramBotService(String.valueOf(CHAT_ID), apiClient,
        getTradeHistoryUseCase, getPortfolioUseCase, approveUserUseCase);
```

변경 후:
```java
sut = new TelegramBotService(String.valueOf(CHAT_ID), apiClient,
        getTradeHistoryUseCase, getPortfolioUseCase);
```

**제거 대상 3** — `callbackUpdate()` 헬퍼 메서드 (line 51-55):
```java
private TelegramUpdate callbackUpdate(String callbackData) {
    TelegramUpdate.Message msg = new TelegramUpdate.Message(1L, new TelegramUpdate.Chat(CHAT_ID), null);
    TelegramUpdate.CallbackQuery cq = new TelegramUpdate.CallbackQuery("cq-id-123", callbackData, msg);
    return new TelegramUpdate(1L, null, cq);
}
```

**제거 대상 4** — callback 관련 테스트 3개 (line 156-184):
```java
@Test
void callback_approve_calls_approve_usecase_and_answers() { ... }

@Test
void callback_reject_calls_reject_usecase_and_answers() { ... }

@Test
void callback_always_answers_even_on_error() { ... }
```

**제거 대상 5** — import:
```java
import com.kista.domain.port.in.ApproveUserUseCase;
```

- [ ] **Step 2**: 테스트 컴파일 확인 (Service 수정 전)

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileTestJava 2>&1 | tail -20
```

Expected: 컴파일 에러 (생성자 인자 개수 불일치 — TelegramBotService가 아직 5개 인자 요구)

- [ ] **Step 3**: `TelegramBotService.java`에서 callback 관련 코드 제거

**제거 대상 1** — import (line 5):
```java
import com.kista.domain.port.in.ApproveUserUseCase;
```

**제거 대상 2** — 필드 선언 (line 29):
```java
private final ApproveUserUseCase approveUserUseCase; // 관리자 승인/거절 처리
```

**제거 대상 3** — `handle()` 내 callback_query 분기 (line 34-38):
```java
// 인라인 버튼 클릭(callback_query) 우선 처리
if (update.callbackQuery() != null) {
    handleCallbackQuery(update.callbackQuery());
    return;
}
```

**제거 대상 4** — `handleCallbackQuery()` 메서드 전체 (line 58-80):
```java
private void handleCallbackQuery(TelegramUpdate.CallbackQuery callbackQuery) {
    ...
}
```

수정 후 `handle()` 메서드:
```java
void handle(TelegramUpdate update) {
    if (update.message() == null || update.message().text() == null) return;
    long chatId = update.message().chat().id();
    String text = update.message().text().trim();

    if (!String.valueOf(chatId).equals(adminChatId)) {
        log.warn("Unauthorized webhook from chatId={}", chatId);
        return;
    }

    BotState state = stateMap.getOrDefault(chatId, BotState.IDLE);
    String reply = switch (state) {
        case IDLE -> handleIdle(chatId, text);
        case AWAITING_RUN_CONFIRM -> handleRunConfirm(chatId, text);
    };
    if (reply != null) {
        apiClient.sendMessage(String.valueOf(chatId), reply);
    }
}
```

- [ ] **Step 4**: 컴파일 및 테스트 통과 확인

```bash
./gradlew compileTestJava 2>&1 | tail -5
./gradlew test --tests 'com.kista.adapter.in.telegram.TelegramBotServiceTest' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (callback 제외 8개 테스트 통과)

- [ ] **Step 5**: ArchUnit 통과 확인

```bash
./gradlew test --tests 'com.kista.architecture.*' 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6**: 커밋

```bash
cd /Users/phs/workspace/kista/kista-api
git add "src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java" \
        "src/test/java/com/kista/adapter/in/telegram/TelegramBotServiceTest.java"
git commit -m "refactor(telegram): remove callback_query approval flow superseded by Admin UI"
```

---

## Task 6: 최종 검증

- [ ] **Step 1**: kista-api 전체 테스트

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (Docker 의존 PortfolioSnapshot/TradeHistory 2건은 인프라 이슈로 OK)

- [ ] **Step 2**: kista-ui typecheck

```bash
cd /Users/phs/workspace/kista/kista-ui
npm run typecheck 2>&1 | tail -10
```

Expected: 에러 없음

- [ ] **Step 3**: kista-ui 빌드

```bash
npm run build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (Settings, NewAccount 페이지 포함)

- [ ] **Step 4**: (선택) 로컬 동작 확인

```bash
# kista-api 기동
cd /Users/phs/workspace/kista/kista-api
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'

# ADMIN 토큰 발급
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)

# 회원탈퇴 엔드포인트 확인 (dev 사용자로 테스트)
USER_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -X DELETE -H "Authorization: Bearer $USER_TOKEN" localhost:8080/api/auth/me
# Expected: HTTP 204

# test-connection 확인 (임의 키로 false 확인)
curl -s -X POST -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"appKey":"badkey","appSecret":"badsecret"}' \
  localhost:8080/api/accounts/test-connection | jq
# Expected: {"success":false,"message":"KIS API 인증에 실패했습니다..."}
```

- [ ] **Step 5**: (선택) TelegramBotService에 ApproveUserUseCase 의존 없음 확인

```bash
grep -n "ApproveUserUseCase\|approveUserUseCase\|handleCallbackQuery" \
  src/main/java/com/kista/adapter/in/telegram/TelegramBotService.java
```

Expected: 아무 것도 출력되지 않음 (0 matches)

---

## Self-Review

**Spec coverage:**
- ✅ 회원탈퇴 백엔드: T1 (DeleteMeUseCase + UserService + AuthController + 테스트)
- ✅ 회원탈퇴 프론트: T2 (Route Handler + DeleteAccountButton + Settings 수정)
- ✅ test-connection 백엔드: T3 (Port/Adapter/Service + AccountController + 테스트)
- ✅ test-connection 프론트: T4 (ApiStep 연결 테스트 버튼 + 성공 후 다음 활성화)
- ✅ 텔레그램 callback_query 제거: T5 (TelegramBotService + TelegramBotServiceTest)
- ✅ CASCADE 삭제: V16 설정 완료, 별도 Flyway 불필요
- ✅ ArchUnit: T3 Step 11 + T5 Step 5에서 각각 검증

**Type consistency:**
- `TestConnectionRequest.appKey()`, `TestConnectionResponse.success()` — T3에서 정의, T4에서 `{ appKey, appSecret }` 일치
- `DeleteMeUseCase.deleteMe(UUID)` — T1에서 정의, AuthController에서 동일 시그니처 사용

**Placeholder scan:** 없음
