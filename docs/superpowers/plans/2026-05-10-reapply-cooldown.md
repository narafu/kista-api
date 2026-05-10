# Reapply 쿨다운 정책 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PENDING 사용자는 1시간마다, REJECTED 사용자는 24시간 후에 재신청할 수 있도록 쿨다운 정책을 추가한다.

**Architecture:** `CooldownException`(domain)을 신규 생성하고, `User` record에 `lastReappliedAt` 필드를 추가한다. `reject()` 호출 시 이 필드를 `now`로 초기화해 24시간 카운트다운을 시작하며, `reapply()` 성공 시에도 갱신한다. 쿨다운 위반은 `CooldownException`으로 전파되고 컨트롤러가 429로 변환한다.

**Tech Stack:** Java 21, Spring Boot 3.4, Flyway, JPA/Hibernate, JUnit 5, Mockito, MockMvc

---

## 파일 목록

| 상태 | 파일 |
|------|------|
| 생성 | `src/main/java/com/kista/domain/model/CooldownException.java` |
| 생성 | `src/main/resources/db/migration/V12__add_last_reapplied_at_to_users.sql` |
| 생성 | `src/test/java/com/kista/adapter/in/web/AuthControllerTest.java` |
| 수정 | `src/main/java/com/kista/domain/model/User.java` |
| 수정 | `src/main/java/com/kista/domain/port/in/ApproveUserUseCase.java` |
| 수정 | `src/main/java/com/kista/application/service/UserService.java` |
| 수정 | `src/main/java/com/kista/adapter/out/persistence/UserEntity.java` |
| 수정 | `src/main/java/com/kista/adapter/out/persistence/UserPersistenceAdapter.java` |
| 수정 | `src/main/java/com/kista/adapter/in/web/AuthController.java` |
| 수정 | `src/test/java/com/kista/application/service/UserServiceTest.java` |

---

## Task 1: CooldownException 생성

**Files:**
- Create: `src/main/java/com/kista/domain/model/CooldownException.java`

- [ ] **Step 1: CooldownException 작성**

```java
package com.kista.domain.model;

import java.time.Instant;

public class CooldownException extends RuntimeException {

    private final Instant retryAfter; // 재신청 가능 시각

    public CooldownException(Instant retryAfter) {
        super("재신청 대기 중입니다. 가능 시각: " + retryAfter);
        this.retryAfter = retryAfter;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/kista/domain/model/CooldownException.java
git commit -m "feat: CooldownException 도메인 예외 추가"
```

---

## Task 2: User record에 lastReappliedAt 추가 + 전파

이 태스크는 컴파일 오류를 한 번에 해소하는 구조 변경이다.

**Files:**
- Modify: `src/main/java/com/kista/domain/model/User.java`
- Modify: `src/main/java/com/kista/domain/port/in/ApproveUserUseCase.java`
- Modify: `src/main/java/com/kista/application/service/UserService.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/UserEntity.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/UserPersistenceAdapter.java`
- Create: `src/main/resources/db/migration/V12__add_last_reapplied_at_to_users.sql`
- Modify: `src/test/java/com/kista/application/service/UserServiceTest.java` (헬퍼 메서드만)

- [ ] **Step 1: User.java — lastReappliedAt 필드 추가 (9번째)**

```java
package com.kista.domain.model;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,                    // Supabase Auth UID와 동기화
        String kakaoId,             // 카카오 고유 ID
        String nickname,            // 카카오 닉네임
        UserStatus status,          // 계정 상태
        String telegramBotToken,    // 전체 계좌 텔레그램 봇 토큰 (AES-256 암호화 저장, null 가능)
        String telegramChatId,      // 전체 계좌 텔레그램 Chat ID (null 가능)
        Instant createdAt,
        Instant updatedAt,
        Instant lastReappliedAt     // nullable — 마지막 reapply()/reject() 호출 시점 (쿨다운 기준)
) {}
```

- [ ] **Step 2: ApproveUserUseCase.java — 주석 업데이트**

```java
package com.kista.domain.port.in;

import java.util.UUID;

public interface ApproveUserUseCase {
    void approve(UUID userId);  // PENDING/REJECTED → ACTIVE
    void reject(UUID userId);   // PENDING → REJECTED (lastReappliedAt 갱신)
    void reapply(UUID userId);  // REJECTED(24h)/PENDING(1h) → PENDING
}
```

- [ ] **Step 3: UserService.java — new User(...) 9개 인자로 수정 (reapply/reject는 Task 3에서)**

`register()`, `updateTelegram()`, `removeTelegram()`, `withStatus()` 모두 9번째 인자 추가:

```java
// register() 내부
User newUser = new User(supabaseUid, kakaoId, nickname, UserStatus.PENDING,
        null, null, null, null, null);

// updateTelegram()
User updated = new User(user.id(), user.kakaoId(), user.nickname(), user.status(),
        botToken, chatId, user.createdAt(), null, user.lastReappliedAt());

// removeTelegram()
User updated = new User(user.id(), user.kakaoId(), user.nickname(), user.status(),
        null, null, user.createdAt(), null, user.lastReappliedAt());

// withStatus() 헬퍼
private User withStatus(User user, UserStatus newStatus) {
    return new User(user.id(), user.kakaoId(), user.nickname(), newStatus,
            user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null,
            user.lastReappliedAt());
}
```

(reapply(), reject() 메서드 본체는 Task 3에서 교체)

- [ ] **Step 4: UserEntity.java — lastReappliedAt 필드 추가**

```java
@Column(name = "last_reapplied_at")
private Instant lastReappliedAt; // nullable — 쿨다운 기준 시각
```

`fromModel()` 에 추가:
```java
e.lastReappliedAt = user.lastReappliedAt();
```

`toModel()` 수정:
```java
User toModel() {
    return new User(id, kakaoId, nickname, status,
            telegramBotToken, telegramChatId, createdAt, updatedAt, lastReappliedAt);
}
```

- [ ] **Step 5: UserPersistenceAdapter.java — encrypt()/toDomain() 9번째 인자 추가**

```java
private User encrypt(User user) {
    if (user.telegramBotToken() == null) return user;
    return new User(user.id(), user.kakaoId(), user.nickname(), user.status(),
            crypto.encrypt(user.telegramBotToken()), user.telegramChatId(),
            user.createdAt(), user.updatedAt(), user.lastReappliedAt());
}

private User toDomain(UserEntity e) {
    User raw = e.toModel();
    if (raw.telegramBotToken() == null) return raw;
    return new User(raw.id(), raw.kakaoId(), raw.nickname(), raw.status(),
            crypto.decrypt(raw.telegramBotToken()), raw.telegramChatId(),
            raw.createdAt(), raw.updatedAt(), raw.lastReappliedAt());
}
```

- [ ] **Step 6: V12 마이그레이션 파일 생성**

```sql
ALTER TABLE users ADD COLUMN last_reapplied_at TIMESTAMPTZ;
```

- [ ] **Step 7: UserServiceTest.java — 헬퍼 메서드 시그니처 수정 (9번째 인자)**

```java
import java.time.temporal.ChronoUnit;

private User pendingUser(UUID id) {
    // lastReappliedAt=null → 쿨다운 없음 (신규 PENDING)
    return new User(id, "kakao-123", "홍길동", UserStatus.PENDING,
            null, null, Instant.now(), Instant.now(), null);
}

private User rejectedUser(UUID id) {
    // 25h 전 거절 → 24h 쿨다운 경과
    return new User(id, "kakao-123", "홍길동", UserStatus.REJECTED,
            null, null, Instant.now(), Instant.now(),
            Instant.now().minus(25, ChronoUnit.HOURS));
}

// 아래 두 헬퍼는 신규 추가
private User pendingUserWithCooldown(UUID id, Instant lastReappliedAt) {
    return new User(id, "kakao-123", "홍길동", UserStatus.PENDING,
            null, null, Instant.now(), Instant.now(), lastReappliedAt);
}

private User rejectedUserWithCooldown(UUID id, Instant lastReappliedAt) {
    return new User(id, "kakao-123", "홍길동", UserStatus.REJECTED,
            null, null, Instant.now(), Instant.now(), lastReappliedAt);
}
```

- [ ] **Step 8: 컴파일 확인**

```bash
./gradlew compileTestJava
```

Expected: BUILD SUCCESSFUL (기존 테스트 컴파일 오류 없음)

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/kista/domain/model/User.java \
        src/main/java/com/kista/domain/port/in/ApproveUserUseCase.java \
        src/main/java/com/kista/application/service/UserService.java \
        src/main/java/com/kista/adapter/out/persistence/UserEntity.java \
        src/main/java/com/kista/adapter/out/persistence/UserPersistenceAdapter.java \
        src/main/resources/db/migration/V12__add_last_reapplied_at_to_users.sql \
        src/test/java/com/kista/application/service/UserServiceTest.java
git commit -m "feat: User record에 lastReappliedAt 필드 추가 + 전파"
```

---

## Task 3: UserService 로직 변경 + 테스트

**Files:**
- Modify: `src/main/java/com/kista/application/service/UserService.java`
- Modify: `src/test/java/com/kista/application/service/UserServiceTest.java`

- [ ] **Step 1: 실패 테스트 작성 — 기존 테스트 1개 수정 + 신규 5개 추가**

`UserServiceTest.java`에서 `reapply_pending_user_throws_exception` 테스트를 교체하고 아래 테스트들을 추가한다:

```java
// 기존 reapply_pending_user_throws_exception 테스트를 아래로 교체
@Test
@DisplayName("PENDING 1시간 이내 재신청 시 CooldownException")
void reapply_pending_within_1h_throws_cooldown() {
    UUID userId = UUID.randomUUID();
    // 30분 전에 마지막 재신청
    when(userRepository.findById(userId)).thenReturn(Optional.of(
            pendingUserWithCooldown(userId, Instant.now().minus(30, ChronoUnit.MINUTES))));

    assertThatThrownBy(() -> userService.reapply(userId))
            .isInstanceOf(CooldownException.class);
}

@Test
@DisplayName("PENDING 1시간 경과 후 재신청 성공 + 알림")
void reapply_pending_after_1h_succeeds() {
    UUID userId = UUID.randomUUID();
    // 2시간 전에 마지막 재신청
    when(userRepository.findById(userId)).thenReturn(Optional.of(
            pendingUserWithCooldown(userId, Instant.now().minus(2, ChronoUnit.HOURS))));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    userService.reapply(userId);

    verify(userRepository).save(argThat(u ->
            u.status() == UserStatus.PENDING && u.lastReappliedAt() != null));
    verify(notificationPort).notifyNewUser(any());
}

@Test
@DisplayName("PENDING lastReappliedAt=null 이면 즉시 재신청 허용")
void reapply_pending_null_lastReappliedAt_succeeds() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    userService.reapply(userId);

    verify(userRepository).save(argThat(u -> u.status() == UserStatus.PENDING));
}

@Test
@DisplayName("REJECTED 24시간 이내 재신청 시 CooldownException")
void reapply_rejected_within_24h_throws_cooldown() {
    UUID userId = UUID.randomUUID();
    // 1시간 전에 거절됨
    when(userRepository.findById(userId)).thenReturn(Optional.of(
            rejectedUserWithCooldown(userId, Instant.now().minus(1, ChronoUnit.HOURS))));

    assertThatThrownBy(() -> userService.reapply(userId))
            .isInstanceOf(CooldownException.class);
}

@Test
@DisplayName("REJECTED lastReappliedAt=null 이면 즉시 재신청 허용 (기존 DB 사용자)")
void reapply_rejected_null_lastReappliedAt_succeeds() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "kakao-123", "홍길동", UserStatus.REJECTED,
            null, null, Instant.now(), Instant.now(), null);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    userService.reapply(userId);

    verify(userRepository).save(argThat(u -> u.status() == UserStatus.PENDING));
}

@Test
@DisplayName("거절 시 lastReappliedAt 갱신 (24h 카운트다운 시작)")
void reject_sets_lastReappliedAt() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    userService.reject(userId);

    verify(userRepository).save(argThat(u ->
            u.status() == UserStatus.REJECTED && u.lastReappliedAt() != null));
}
```

기존 `reapply_rejected_user_sets_pending_and_notifies` 테스트의 `verify` 라인도 수정:
```java
// 기존
verify(userRepository).save(argThat(u -> u.status() == UserStatus.PENDING));
// 변경
verify(userRepository).save(argThat(u ->
        u.status() == UserStatus.PENDING && u.lastReappliedAt() != null));
```

또한 `UserServiceTest` 상단에 import 추가:
```java
import com.kista.domain.model.CooldownException;
import java.time.temporal.ChronoUnit;
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests 'com.kista.application.service.UserServiceTest' --rerun-tasks 2>&1 | tail -30
```

Expected: 새로 추가한 테스트들 FAILED (구현 전)

- [ ] **Step 3: UserService.java — reapply() 교체**

```java
import com.kista.domain.model.CooldownException;
import java.time.temporal.ChronoUnit;
// (기존 imports 유지)

@Override
public void reapply(UUID userId) {
    User user = findOrThrow(userId);
    Instant now = Instant.now();

    // 상태별 쿨다운 검증
    switch (user.status()) {
        case PENDING -> {
            if (user.lastReappliedAt() != null &&
                    now.isBefore(user.lastReappliedAt().plus(1, ChronoUnit.HOURS)))
                throw new CooldownException(user.lastReappliedAt().plus(1, ChronoUnit.HOURS));
        }
        case REJECTED -> {
            if (user.lastReappliedAt() != null &&
                    now.isBefore(user.lastReappliedAt().plus(24, ChronoUnit.HOURS)))
                throw new CooldownException(user.lastReappliedAt().plus(24, ChronoUnit.HOURS));
        }
        default -> throw new IllegalStateException("재신청 불가 상태: " + user.status());
    }

    // PENDING 전환 + 재신청 시각 갱신
    User updated = new User(user.id(), user.kakaoId(), user.nickname(), UserStatus.PENDING,
            user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null, now);
    userRepository.save(updated);
    log.info("사용자 재신청: userId={}", userId);
    notificationPort.notifyNewUser(updated);
}
```

- [ ] **Step 4: UserService.java — reject() 교체**

```java
@Override
public void reject(UUID userId) {
    User user = findOrThrow(userId);
    // REJECTED 전환 + 24h 카운트다운 시작 (lastReappliedAt = now)
    User updated = new User(user.id(), user.kakaoId(), user.nickname(), UserStatus.REJECTED,
            user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null, Instant.now());
    userRepository.save(updated);
    log.info("사용자 거절: userId={}", userId);
    notificationPort.notifyRejected(updated);
    realtimeNotificationPort.notifyStatusChange(userId, UserStatus.REJECTED);
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.application.service.UserServiceTest' --rerun-tasks 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/application/service/UserService.java \
        src/test/java/com/kista/application/service/UserServiceTest.java
git commit -m "feat: reapply() PENDING(1h)/REJECTED(24h) 쿨다운 적용, reject() lastReappliedAt 갱신"
```

---

## Task 4: AuthController 429 처리 + 테스트

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/AuthController.java`
- Create: `src/test/java/com/kista/adapter/in/web/AuthControllerTest.java`

- [ ] **Step 1: 실패 테스트 작성 — AuthControllerTest.java 신규 생성**

```java
package com.kista.adapter.in.web;

import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.CooldownException;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ApproveUserUseCase approveUser;
    @MockBean RegisterUserUseCase registerUser;
    @MockBean GetUserUseCase getUser;
    @MockBean SseEmitterRegistry sseEmitterRegistry;
    @MockBean JwtDecoder jwtDecoder; // profile-conditional bean — WebMvcTest에서 명시 필요

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Authentication auth() {
        // @AuthenticationPrincipal UUID 바인딩을 위해 principal을 UUID로 설정
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    @DisplayName("쿨다운 중 재신청 시 429 Too Many Requests 반환")
    void reapply_cooldown_returns_429() throws Exception {
        Instant retryAfter = Instant.now().plus(1, ChronoUnit.HOURS);
        doThrow(new CooldownException(retryAfter)).when(approveUser).reapply(USER_ID);

        mockMvc.perform(post("/api/auth/reapply")
                        .with(csrf())
                        .with(authentication(auth())))
                .andExpect(status().isTooManyRequests());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AuthControllerTest' --rerun-tasks 2>&1 | tail -20
```

Expected: FAILED — 현재 컨트롤러가 CooldownException을 처리하지 않아 500 반환

- [ ] **Step 3: AuthController.java — CooldownException catch 추가**

```java
import com.kista.domain.model.CooldownException;
// (기존 imports 유지)

// reapply() 메서드 교체
@PostMapping("/reapply")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void reapply(@AuthenticationPrincipal UUID userId) {
    try {
        approveUser.reapply(userId);
    } catch (CooldownException e) {
        // 쿨다운 중 — 재신청 가능 시각을 body에 포함
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getRetryAfter().toString());
    } catch (IllegalStateException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AuthControllerTest' --rerun-tasks 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 전체 테스트 실행**

```bash
./gradlew test --rerun-tasks 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/adapter/in/web/AuthController.java \
        src/test/java/com/kista/adapter/in/web/AuthControllerTest.java
git commit -m "feat: reapply 쿨다운 위반 시 429 응답 처리"
```

---

## 검증

```bash
# 전체 테스트
./gradlew test

# 아키텍처 규칙 (CooldownException이 domain에 위치하는지 확인)
./gradlew test --tests 'com.kista.architecture.*'
```

도메인 레이어(`CooldownException`)가 adapter나 spring 의존성 없이 순수 Java인지 ArchUnit이 검증한다.
