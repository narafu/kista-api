# [V2-P3] 전략 중지/재개 API + 관리자 알림 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계좌별 전략 PAUSED/ACTIVE 상태 전환 PATCH API 구현 + 관리자 텔레그램 알림

**Architecture:** AccountService에 PauseStrategyUseCase/ResumeStrategyUseCase 구현. 소유권 검증 후 strategyStatus 변경, UserNotificationPort로 관리자 알림. AccountController에 PATCH 엔드포인트 추가.

**Tech Stack:** Spring Boot 3, Spring Security (SecurityContextHolder), Mockito(단위), @WebMvcTest(컨트롤러 슬라이스)

---

## 파일 목록

| 파일 | 작업 |
|------|------|
| `src/main/java/com/kista/application/service/AccountService.java` | 수정 — UserRepository/UserNotificationPort 주입, pause/resume 구현 |
| `src/main/java/com/kista/adapter/out/notify/TelegramAdapter.java` | 수정 — notifyStrategyChanged 메시지 형식 수정 |
| `src/main/java/com/kista/adapter/in/web/AccountController.java` | 수정 — PATCH /strategy/pause, /strategy/resume 추가 |
| `src/test/java/com/kista/application/service/AccountServiceTest.java` | 수정 — pause/resume 단위 테스트 추가 |
| `src/test/java/com/kista/adapter/out/notify/TelegramAdapterTest.java` | 수정 — notifyStrategyChanged 테스트 추가 |
| `src/test/java/com/kista/adapter/in/web/AccountControllerTest.java` | 신규 — PATCH 엔드포인트 슬라이스 테스트 |

---

## Task 1: AccountService pause/resume 구현

**Files:**
- Modify: `src/main/java/com/kista/application/service/AccountService.java`
- Modify: `src/test/java/com/kista/application/service/AccountServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성** — AccountServiceTest에 pause/resume 테스트 추가

```java
// AccountServiceTest.java 에 추가 (기존 @Mock AccountRepository 아래)
@Mock UserRepository userRepository;
@Mock UserNotificationPort notificationPort;
// @InjectMocks AccountService accountService;  // 이미 있음 — 추가 Mock이 자동 주입됨

// 헬퍼 추가
private User activeUser(UUID id) {
    return new User(id, "kakao-123", "홍길동", UserStatus.ACTIVE,
            null, null, Instant.now(), Instant.now());
}

@Test
@DisplayName("pause: ACTIVE → PAUSED + 관리자 알림")
void pause_changes_status_and_notifies() {
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(userId)));
    when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
    when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    accountService.pause(accountId, userId);

    verify(accountRepository).save(argThat(a -> a.strategyStatus() == StrategyStatus.PAUSED));
    verify(notificationPort).notifyStrategyChanged(any(), any(), eq("중지"));
}

@Test
@DisplayName("resume: PAUSED → ACTIVE + 관리자 알림")
void resume_changes_status_and_notifies() {
    Account paused = new Account(accountId, userId, "테스트계좌",
            "74420614", "appKey", "appSecret", "01",
            Strategy.INFINITE, StrategyStatus.PAUSED,
            null, null, Instant.now(), Instant.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(paused));
    when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
    when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    accountService.resume(accountId, userId);

    verify(accountRepository).save(argThat(a -> a.strategyStatus() == StrategyStatus.ACTIVE));
    verify(notificationPort).notifyStrategyChanged(any(), any(), eq("재개"));
}

@Test
@DisplayName("pause: 타 사용자 계좌 시 SecurityException(→403)")
void pause_by_non_owner_throws_forbidden() {
    UUID otherId = UUID.randomUUID();
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount(otherId)));

    assertThatThrownBy(() -> accountService.pause(accountId, userId))
            .isInstanceOf(SecurityException.class);

    verify(accountRepository, never()).save(any());
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

```bash
bash gradlew compileTestJava
```
Expected: `AccountService does not implement PauseStrategyUseCase` 오류

- [ ] **Step 3: AccountService 구현**

`AccountService.java` 클래스 선언 줄 수정:
```java
public class AccountService implements RegisterAccountUseCase, UpdateAccountUseCase,
        DeleteAccountUseCase, GetAccountUseCase, PauseStrategyUseCase, ResumeStrategyUseCase {
```

필드 추가 (`private final AccountRepository accountRepository;` 아래):
```java
private final UserRepository userRepository;       // pause/resume 시 사용자 조회용
private final UserNotificationPort notificationPort; // 관리자 알림용
```

import 추가:
```java
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserRepository;
import com.kista.domain.model.User;
```

메서드 추가 (`delete()` 아래):
```java
@Override
public void pause(UUID accountId, UUID requesterId) {
    Account account = findOrThrow(accountId);
    verifyOwner(account, requesterId);
    User user = findUserOrThrow(requesterId);
    Account paused = withStrategyStatus(account, StrategyStatus.PAUSED);
    accountRepository.save(paused);
    log.info("전략 중지: accountId={}, userId={}", accountId, requesterId);
    notificationPort.notifyStrategyChanged(user, paused, "중지");
}

@Override
public void resume(UUID accountId, UUID requesterId) {
    Account account = findOrThrow(accountId);
    verifyOwner(account, requesterId);
    User user = findUserOrThrow(requesterId);
    Account active = withStrategyStatus(account, StrategyStatus.ACTIVE);
    accountRepository.save(active);
    log.info("전략 재개: accountId={}, userId={}", accountId, requesterId);
    notificationPort.notifyStrategyChanged(user, active, "재개");
}
```

private 헬퍼 추가 (`verifyOwner()` 아래):
```java
private User findUserOrThrow(UUID userId) {
    return userRepository.findById(userId)
            .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
}

private Account withStrategyStatus(Account account, StrategyStatus status) {
    return new Account(account.id(), account.userId(), account.nickname(),
            account.accountNo(), account.kisAppKey(), account.kisSecretKey(),
            account.kisAccountType(), account.strategy(), status,
            account.telegramBotToken(), account.telegramChatId(),
            account.createdAt(), Instant.now());
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

```bash
bash gradlew test --tests "com.kista.application.service.AccountServiceTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/AccountService.java \
        src/test/java/com/kista/application/service/AccountServiceTest.java
git commit -m "feat: AccountService pause/resume 전략 상태 변경 + 관리자 알림"
```

---

## Task 2: TelegramAdapter 알림 메시지 수정 + 테스트

**Files:**
- Modify: `src/main/java/com/kista/adapter/out/notify/TelegramAdapter.java`
- Modify: `src/test/java/com/kista/adapter/out/notify/TelegramAdapterTest.java`

- [ ] **Step 1: 실패 테스트 작성** — TelegramAdapterTest에 notifyStrategyChanged 테스트 추가

```java
// TelegramAdapterTest.java에 추가 — import 필요: java.time.Instant, java.util.UUID

@Test
@SuppressWarnings("unchecked")
void notifyStrategyChanged_bodyContainsNicknameAndAction() {
    User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE,
            null, null, Instant.now(), Instant.now());
    Account account = new Account(UUID.randomUUID(), user.id(), "내SOXL계좌",
            "74420614", "key", "secret", "01",
            Strategy.INFINITE, StrategyStatus.ACTIVE,
            null, null, Instant.now(), Instant.now());

    ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    adapter.notifyStrategyChanged(user, account, "중지");

    verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
    String text = bodyCaptor.getValue().get("text");
    assertThat(text).contains("홍길동").contains("내SOXL계좌").contains("중지");
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인 (현재 메시지 형식 불일치)**

```bash
bash gradlew test --tests "com.kista.adapter.out.notify.TelegramAdapterTest"
```

- [ ] **Step 3: TelegramAdapter.notifyStrategyChanged 메시지 수정**

`TelegramAdapter.java`의 `notifyStrategyChanged()` 수정:
```java
@Override
public void notifyStrategyChanged(User user, Account account, String action) {
    String text = String.format("사용자 %s이 계좌 %s의 전략을 %s했습니다",
            user.nickname(), account.nickname(), action);
    send(text); // 관리자 봇으로 전송
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

```bash
bash gradlew test --tests "com.kista.adapter.out.notify.TelegramAdapterTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/notify/TelegramAdapter.java \
        src/test/java/com/kista/adapter/out/notify/TelegramAdapterTest.java
git commit -m "feat: TelegramAdapter 전략 변경 알림 메시지 형식 수정"
```

---

## Task 3: AccountController PATCH 엔드포인트 추가

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/AccountController.java`
- Create: `src/test/java/com/kista/adapter/in/web/AccountControllerTest.java`

- [ ] **Step 1: 실패 테스트 작성** — AccountControllerTest.java 생성

```java
package com.kista.adapter.in.web;

import com.kista.domain.port.in.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@WithMockUser(username = "00000000-0000-0000-0000-000000000001")
class AccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean RegisterAccountUseCase registerAccount;
    @MockBean UpdateAccountUseCase updateAccount;
    @MockBean DeleteAccountUseCase deleteAccount;
    @MockBean GetAccountUseCase getAccount;
    @MockBean PauseStrategyUseCase pauseStrategy;
    @MockBean ResumeStrategyUseCase resumeStrategy;

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Test
    void pause_returns_204_on_success() throws Exception {
        doNothing().when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void resume_returns_204_on_success() throws Exception {
        doNothing().when(resumeStrategy).resume(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/resume").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void pause_returns_403_when_not_owner() throws Exception {
        doThrow(new SecurityException("접근 불가")).when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void resume_returns_403_when_not_owner() throws Exception {
        doThrow(new SecurityException("접근 불가")).when(resumeStrategy).resume(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/resume").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void pause_returns_404_when_account_not_found() throws Exception {
        doThrow(new NoSuchElementException("없음")).when(pauseStrategy).pause(any(), any());

        mockMvc.perform(patch("/api/accounts/" + accountId + "/strategy/pause").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인 (404 — 엔드포인트 없음)**

```bash
bash gradlew test --tests "com.kista.adapter.in.web.AccountControllerTest"
```
Expected: FAIL (404 Not Found — endpoint not registered)

- [ ] **Step 3: AccountController PATCH 엔드포인트 추가**

`AccountController.java`에 필드 추가:
```java
private final PauseStrategyUseCase pauseStrategy;
private final ResumeStrategyUseCase resumeStrategy;
```

import 추가:
```java
import com.kista.domain.port.in.PauseStrategyUseCase;
import com.kista.domain.port.in.ResumeStrategyUseCase;
```

메서드 추가 (`delete()` 아래):
```java
// 전략 중지 (ACTIVE → PAUSED)
@PatchMapping("/{id}/strategy/pause")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void pauseStrategy(@PathVariable UUID id) {
    try {
        pauseStrategy.pause(id, currentUserId());
    } catch (SecurityException e) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (NoSuchElementException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
}

// 전략 재개 (PAUSED → ACTIVE)
@PatchMapping("/{id}/strategy/resume")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void resumeStrategy(@PathVariable UUID id) {
    try {
        resumeStrategy.resume(id, currentUserId());
    } catch (SecurityException e) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (NoSuchElementException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

```bash
bash gradlew test --tests "com.kista.adapter.in.web.AccountControllerTest"
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 전체 테스트 실행**

```bash
bash gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/AccountController.java \
        src/test/java/com/kista/adapter/in/web/AccountControllerTest.java
git commit -m "feat: 전략 중지/재개 PATCH API 엔드포인트 추가"
```
