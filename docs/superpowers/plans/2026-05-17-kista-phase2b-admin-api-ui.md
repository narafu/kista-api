# KISTA Phase 2B — Admin API + UI 3종 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kista-api에 관리자 API 5종(사용자 목록/승인/거절/역할변경/삭제 + 대시보드 통계)을 구현하고, kista-ui에 관리자 전용 레이아웃과 3개 화면(Overview/Pending/Users)을 추가한다.

**Architecture:** Hexagonal Architecture (kista-api) — 새 UseCase 포트 3개 + AdminService + AdminUserController + AdminDashboardController. kista-ui는 Next.js App Router `(admin)` route group으로 일반 `(main)` 레이아웃과 분리. proxy.ts에 ADMIN role guard 추가.

**Tech Stack:** Java 21 + Spring Boot 3 (kista-api), Next.js 16 App Router + TypeScript + Tailwind (kista-ui). kista-api 브랜치: `feature/phase2a-admin-foundation`(이미 존재), kista-ui 브랜치: `feature/phase2b-admin-ui` 신규.

---

## Context

Phase 2A 완료 상태:
- `users.role` PostgreSQL ENUM (`USER`/`ADMIN`), `audit_logs` 테이블 생성됨
- `UserRole` enum, `AuditLogPort`/`AuditLogPersistenceAdapter` 구현됨
- JWT에 `"role"` claim 포함, `JwtAuthFilter`가 `ROLE_ADMIN` authorities 주입
- `SecurityConfig`: `/api/admin/**` → `hasRole("ADMIN")`
- `DevAuthController`: `POST /api/auth/dev-admin-token` → ADMIN JWT 발급
- `AdminPingController`: `GET /api/admin/_ping` 동작 확인됨

Phase 2B 작업 범위:
- **kista-api**: `feature/phase2a-admin-foundation` 브랜치 계속 사용
- **kista-ui**: `feature/phase2b-admin-ui` 신규 브랜치 (`main`에서 분기)

### 핵심 제약 (반드시 숙지)

**kista-api 코드 패턴:**
- `User` record: `(UUID id, String kakaoId, String nickname, UserStatus status, UserRole role, String telegramBotToken, String telegramChatId, Instant createdAt, Instant updatedAt, Instant lastReappliedAt)` — 10개 필드
- `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` + `@Enumerated(EnumType.STRING)` — PostgreSQL 네이티브 ENUM 컬럼에 필수
- 컨트롤러 패턴: `@Tag(name="...")` + `@RequiredArgsConstructor` + `@AuthenticationPrincipal UUID adminId`
- `@WebMvcTest`에서 role 인가 검증 시 `@Import({SecurityConfig.class, JwtAuthFilter.class})` 필수
- 주석 규칙: `//` 인라인만, Javadoc 금지

**kista-ui 코드 패턴:**
- `(main)` route group: ACTIVE 사용자 전용, DesktopSidebar + MobileBottomNav
- `(admin)` route group: ADMIN 사용자 전용, AdminSidebar + AdminTopBar
- API 호출: `lib/api/` 함수 경유, 컴포넌트 직접 fetch 금지
- Client Component API 호출: Route Handler 경유 (토큰 없이 `lib/api` 함수 호출)
- Server Component: `getAuthToken()` → token → `lib/api` 함수 직접 호출
- Route Handler URL: 반드시 `process.env.API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL`
- Server/Client 컴포넌트 분리: 인터랙션(버튼 클릭 등)은 `'use client'` 컴포넌트로 분리

---

## 파일 구조

### kista-api (신규/수정)

```
src/main/java/com/kista/
  domain/
    model/
      AdminStats.java                     ← 신규: 대시보드 통계 record
    port/in/
      AdminListUsersUseCase.java          ← 신규: 사용자 목록 UseCase
      AdminUserActionUseCase.java         ← 신규: 승인/거절/역할변경/삭제 UseCase
      AdminDashboardUseCase.java          ← 신규: 통계 UseCase
    port/out/
      UserRepository.java                 ← 수정: findAll, findAllByStatus, delete 추가
      AccountRepository.java              ← 수정: countAll 추가
  application/
    service/
      AdminService.java                   ← 신규: UseCase 구현체
  adapter/
    in/web/
      AdminUserController.java            ← 신규: /api/admin/users 5종 엔드포인트
      AdminDashboardController.java       ← 신규: /api/admin/dashboard/stats
      dto/
        AdminUserResponse.java            ← 신규: 관리자용 사용자 응답 DTO
        AdminDashboardResponse.java       ← 신규: 대시보드 통계 응답 DTO
      UserResponse.java                   ← 수정: role 필드 추가
    out/persistence/
      UserPersistenceAdapter.java         ← 수정: findAll, findAllByStatus, delete 구현
      UserJpaRepository.java              ← 수정: findAllByStatus 메서드 추가
      AccountPersistenceAdapter.java      ← 수정: countAll 구현
      AccountJpaRepository.java           ← 수정: count() — JpaRepository 기본 제공

src/test/java/com/kista/
  adapter/in/web/
    AdminUserControllerTest.java          ← 신규
    AdminDashboardControllerTest.java     ← 신규
  application/service/
    AdminServiceTest.java                 ← 신규
```

### kista-ui (신규/수정)

```
types/
  user.ts                                 ← 수정: UserRole 타입 + User.role 필드 추가
  admin.ts                                ← 신규: AdminUser, AdminStats 타입
lib/api/
  admin.ts                                ← 신규: admin API 함수들
app/
  api/admin/[[...path]]/route.ts          ← 신규: admin Route Handler
  (admin)/
    layout.tsx                            ← 신규: AdminSidebar + AdminTopBar
    admin/
      page.tsx                            ← 신규: Overview 페이지
      pending/
        page.tsx                          ← 신규: Pending 사용자 페이지
      users/
        page.tsx                          ← 신규: 사용자 목록 페이지
components/admin/
  AdminSidebar.tsx                        ← 신규
  AdminTopBar.tsx                         ← 신규
  ApproveRejectButtons.tsx                ← 신규 (Client Component)
  ChangeRoleButton.tsx                    ← 신규 (Client Component)
proxy.ts                                  ← 수정: ADMIN role guard + role cookie
```

---

## T1. UserRepository/AccountRepository 확장 + Persistence 구현

**Files:**
- Modify: `kista-api/src/main/java/com/kista/domain/port/out/UserRepository.java`
- Modify: `kista-api/src/main/java/com/kista/domain/port/out/AccountRepository.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/UserJpaRepository.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/UserPersistenceAdapter.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/AccountPersistenceAdapter.java`

- [ ] **Step 1**: `UserRepository`에 3개 메서드 추가

```java
// domain/port/out/UserRepository.java 전체
package com.kista.domain.port.out;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByKakaoId(String kakaoId);
    List<User> findAll();                           // 전체 사용자 목록 (관리자용)
    List<User> findAllByStatus(UserStatus status);  // 상태별 사용자 목록 (관리자용)
    User save(User user);
    void delete(UUID id);                           // 사용자 삭제 (관리자용)
}
```

- [ ] **Step 2**: `AccountRepository`에 `countAll()` 추가

```java
// AccountRepository에 추가할 줄 (기존 메서드들 유지):
long countAll(); // 전체 계좌 수 (대시보드 통계용)
```

- [ ] **Step 3**: `UserJpaRepository`에 `findAllByStatus` 추가

```java
// adapter/out/persistence/UserJpaRepository.java 전체
package com.kista.adapter.out.persistence;

import com.kista.domain.model.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByKakaoId(String kakaoId);
    List<UserEntity> findAllByStatus(UserStatus status); // 상태별 조회 (관리자용)
}
```

- [ ] **Step 4**: `UserPersistenceAdapter`에 3개 메서드 구현

```java
// UserPersistenceAdapter에 추가 (기존 메서드들 유지):

@Override
public List<User> findAll() {
    return jpaRepository.findAll().stream().map(this::toDomain).toList();
}

@Override
public List<User> findAllByStatus(UserStatus status) {
    return jpaRepository.findAllByStatus(status).stream().map(this::toDomain).toList();
}

@Override
public void delete(UUID id) {
    jpaRepository.deleteById(id); // 연관 데이터(accounts, audit_logs)는 FK ON DELETE CASCADE 처리
}
```

- [ ] **Step 5**: `AccountPersistenceAdapter`에 `countAll()` 구현

```java
// AccountPersistenceAdapter에 추가 (기존 메서드들 유지):

@Override
public long countAll() {
    return jpaRepository.count(); // JpaRepository 기본 제공 메서드
}
```

- [ ] **Step 6**: `./gradlew compileJava` — BUILD SUCCESSFUL 확인

- [ ] **Step 7**: 커밋: `feat(persistence): extend UserRepository and AccountRepository for admin use cases`

---

## T2. Admin UseCase 포트 + AdminStats domain record

**Files:**
- Create: `kista-api/src/main/java/com/kista/domain/model/AdminStats.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminListUsersUseCase.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminUserActionUseCase.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminDashboardUseCase.java`

- [ ] **Step 1**: `AdminStats` domain record 생성

```java
// domain/model/AdminStats.java
package com.kista.domain.model;

public record AdminStats(
    long totalUsers,     // 전체 사용자 수
    long pendingCount,   // 승인 대기 수
    long activeCount,    // 승인된 수
    long rejectedCount,  // 거절된 수
    long totalAccounts   // 전체 계좌 수
) {}
```

- [ ] **Step 2**: `AdminListUsersUseCase` 생성

```java
// domain/port/in/AdminListUsersUseCase.java
package com.kista.domain.port.in;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;

import java.util.List;

public interface AdminListUsersUseCase {
    List<User> listAll();
    List<User> listByStatus(UserStatus status);
}
```

- [ ] **Step 3**: `AdminUserActionUseCase` 생성

```java
// domain/port/in/AdminUserActionUseCase.java
package com.kista.domain.port.in;

import com.kista.domain.model.UserRole;

import java.util.UUID;

public interface AdminUserActionUseCase {
    void approveUser(UUID adminId, UUID targetUserId);              // 승인 + 감사 기록
    void rejectUser(UUID adminId, UUID targetUserId);               // 거절 + 감사 기록
    void changeRole(UUID adminId, UUID targetUserId, UserRole role); // 역할 변경 + 감사 기록
    void deleteUser(UUID adminId, UUID targetUserId);               // 삭제 + 감사 기록
}
```

- [ ] **Step 4**: `AdminDashboardUseCase` 생성

```java
// domain/port/in/AdminDashboardUseCase.java
package com.kista.domain.port.in;

import com.kista.domain.model.AdminStats;

public interface AdminDashboardUseCase {
    AdminStats getStats(); // 사용자 현황 + 계좌 수 통계
}
```

- [ ] **Step 5**: `./gradlew compileJava` — BUILD SUCCESSFUL 확인

- [ ] **Step 6**: 커밋: `feat(domain): add admin use case ports and AdminStats record`

---

## T3. AdminService 구현

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/service/AdminService.java`
- Create: `kista-api/src/test/java/com/kista/application/service/AdminServiceTest.java`

**Pattern reference:** `UserService.java` — `@Service @RequiredArgsConstructor @Transactional` 패턴, `ApproveUserUseCase` 인터페이스에 위임.

- [ ] **Step 1**: `AdminService` 구현

```java
// application/service/AdminService.java
package com.kista.application.service;

import com.kista.domain.model.AdminStats;
import com.kista.domain.model.User;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
import com.kista.domain.port.in.AdminDashboardUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import com.kista.domain.port.in.AdminUserActionUseCase;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminService implements AdminListUsersUseCase, AdminUserActionUseCase, AdminDashboardUseCase {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ApproveUserUseCase approveUserUseCase; // 승인/거절 위임 (알림 + SSE 포함)
    private final AuditLogPort auditLogPort;             // 감사 로그 기록

    @Override
    @Transactional(readOnly = true)
    public List<User> listAll() {
        return userRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> listByStatus(UserStatus status) {
        return userRepository.findAllByStatus(status);
    }

    @Override
    public void approveUser(UUID adminId, UUID targetUserId) {
        // 기존 UserService.approve() 위임 (텔레그램 알림 + SSE 포함)
        approveUserUseCase.approve(targetUserId);
        log.info("관리자 사용자 승인: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_APPROVE", "USER", targetUserId, null);
    }

    @Override
    public void rejectUser(UUID adminId, UUID targetUserId) {
        // 기존 UserService.reject() 위임 (텔레그램 알림 + SSE 포함)
        approveUserUseCase.reject(targetUserId);
        log.info("관리자 사용자 거절: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_REJECT", "USER", targetUserId, null);
    }

    @Override
    public void changeRole(UUID adminId, UUID targetUserId, UserRole role) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + targetUserId));
        User updated = new User(user.id(), user.kakaoId(), user.nickname(), user.status(), role,
                user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null, user.lastReappliedAt());
        userRepository.save(updated);
        log.info("관리자 역할 변경: adminId={}, targetUserId={}, role={}", adminId, targetUserId, role);
        auditLogPort.log(adminId, "USER_ROLE_CHANGE", "USER", targetUserId,
                Map.of("newRole", role.name()));
    }

    @Override
    public void deleteUser(UUID adminId, UUID targetUserId) {
        // 존재 확인 후 삭제 (FK ON DELETE CASCADE로 accounts/audit_logs 자동 삭제)
        userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + targetUserId));
        userRepository.delete(targetUserId);
        log.info("관리자 사용자 삭제: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_DELETE", "USER", targetUserId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStats getStats() {
        List<User> all = userRepository.findAll();
        long totalUsers = all.size();
        long pendingCount = all.stream().filter(u -> u.status() == UserStatus.PENDING).count();
        long activeCount = all.stream().filter(u -> u.status() == UserStatus.ACTIVE).count();
        long rejectedCount = all.stream().filter(u -> u.status() == UserStatus.REJECTED).count();
        long totalAccounts = accountRepository.countAll();
        return new AdminStats(totalUsers, pendingCount, activeCount, rejectedCount, totalAccounts);
    }
}
```

- [ ] **Step 2**: `AdminServiceTest` 작성

```java
// test/java/com/kista/application/service/AdminServiceTest.java
package com.kista.application.service;

import com.kista.domain.model.AdminStats;
import com.kista.domain.model.User;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserRepository userRepository;
    @Mock AccountRepository accountRepository;
    @Mock ApproveUserUseCase approveUserUseCase;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminService adminService;

    private User user(UUID id, UserStatus status) {
        return new User(id, "kakao-" + id, "테스트", status, UserRole.USER,
                null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void getStats_returnsCorrectCounts() {
        // given
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID(), id3 = UUID.randomUUID();
        when(userRepository.findAll()).thenReturn(List.of(
                user(id1, UserStatus.PENDING),
                user(id2, UserStatus.ACTIVE),
                user(id3, UserStatus.REJECTED)
        ));
        when(accountRepository.countAll()).thenReturn(5L);

        // when
        AdminStats stats = adminService.getStats();

        // then
        assertThat(stats.totalUsers()).isEqualTo(3);
        assertThat(stats.pendingCount()).isEqualTo(1);
        assertThat(stats.activeCount()).isEqualTo(1);
        assertThat(stats.rejectedCount()).isEqualTo(1);
        assertThat(stats.totalAccounts()).isEqualTo(5);
    }

    @Test
    void approveUser_delegatesAndLogsAudit() {
        // given
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();

        // when
        adminService.approveUser(adminId, targetId);

        // then
        verify(approveUserUseCase).approve(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_APPROVE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void changeRole_updatesRoleAndLogsAudit() {
        // given
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        User existing = user(targetId, UserStatus.ACTIVE);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(existing));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        adminService.changeRole(adminId, targetId, UserRole.ADMIN);

        // then
        verify(userRepository).save(any(User.class));
        verify(auditLogPort).log(eq(adminId), eq("USER_ROLE_CHANGE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void deleteUser_deletesAndLogsAudit() {
        // given
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user(targetId, UserStatus.ACTIVE)));

        // when
        adminService.deleteUser(adminId, targetId);

        // then
        verify(userRepository).delete(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_DELETE"), eq("USER"), eq(targetId), any());
    }
}
```

- [ ] **Step 3**: `./gradlew test --tests 'com.kista.application.service.AdminServiceTest'` — BUILD SUCCESSFUL 확인

- [ ] **Step 4**: 커밋: `feat(application): add AdminService implementing admin use cases`

---

## T4. AdminUserController + DTO + 테스트

**Files:**
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/dto/AdminUserResponse.java`
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminUserController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminUserControllerTest.java`

**Pattern reference:** `AdminPingControllerTest` — `@WebMvcTest` + `@Import({SecurityConfig.class, JwtAuthFilter.class})` + ADMIN 토큰 패턴.

- [ ] **Step 1**: `AdminUserResponse` DTO 작성

```java
// adapter/in/web/dto/AdminUserResponse.java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.User;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(
        @Schema(description = "사용자 고유 ID") UUID id,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "계정 상태") UserStatus status,
        @Schema(description = "역할") UserRole role,
        @Schema(description = "가입 일시") Instant createdAt
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.id(), user.nickname(), user.status(),
                user.role(), user.createdAt());
    }

    public static List<AdminUserResponse> fromList(List<User> users) {
        return users.stream().map(AdminUserResponse::from).toList();
    }
}
```

- [ ] **Step 2**: `AdminUserController` 작성

```java
// adapter/in/web/AdminUserController.java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminUserResponse;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
import com.kista.domain.port.in.AdminListUsersUseCase;
import com.kista.domain.port.in.AdminUserActionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Tag(name = "Admin - Users", description = "관리자 사용자 관리 API")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminListUsersUseCase listUsers;
    private final AdminUserActionUseCase userAction;

    // 전체 또는 상태별 사용자 목록 조회
    @Operation(summary = "사용자 목록 조회")
    @GetMapping
    public List<AdminUserResponse> listUsers(
            @RequestParam(required = false) UserStatus status,
            @AuthenticationPrincipal UUID adminId) {
        List<?> users = status == null ? listUsers.listAll() : listUsers.listByStatus(status);
        return AdminUserResponse.fromList(
                status == null ? listUsers.listAll() : listUsers.listByStatus(status));
    }

    // 사용자 승인
    @Operation(summary = "사용자 승인")
    @PostMapping("/{userId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.approveUser(adminId, userId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 사용자 거절
    @Operation(summary = "사용자 거절")
    @PostMapping("/{userId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejectUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.rejectUser(adminId, userId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 역할 변경 (USER ↔ ADMIN)
    @Operation(summary = "역할 변경")
    @PatchMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID userId,
                           @RequestBody RoleRequest body,
                           @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.changeRole(adminId, userId, body.role());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // 사용자 삭제
    @Operation(summary = "사용자 삭제")
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID userId, @AuthenticationPrincipal UUID adminId) {
        try {
            userAction.deleteUser(adminId, userId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    record RoleRequest(UserRole role) {} // 역할 변경 요청 body
}
```

Note: Fix the duplicate call bug in `listUsers()` — replace with:
```java
List<com.kista.domain.model.User> users = status == null 
    ? listUsers.listAll() 
    : listUsers.listByStatus(status);
return AdminUserResponse.fromList(users);
```

- [ ] **Step 3**: `AdminUserControllerTest` 작성

```java
// test/.../AdminUserControllerTest.java
package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.User;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
import com.kista.domain.port.in.AdminListUsersUseCase;
import com.kista.domain.port.in.AdminUserActionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminUserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminListUsersUseCase listUsers;
    @MockBean AdminUserActionUseCase userAction;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private UsernamePasswordAuthenticationToken adminToken() {
        return new UsernamePasswordAuthenticationToken(ADMIN_UUID, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private User sampleUser(UUID id) {
        return new User(id, "kakao-1", "테스트유저", UserStatus.PENDING, UserRole.USER,
                null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void listUsers_withAdminToken_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        when(listUsers.listAll()).thenReturn(List.of(sampleUser(userId)));

        mockMvc.perform(get("/api/admin/users").with(authentication(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void listUsers_withUserToken_returns403() throws Exception {
        var userToken = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mockMvc.perform(get("/api/admin/users").with(authentication(userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approveUser_withAdminToken_returns204() throws Exception {
        doNothing().when(userAction).approveUser(any(), any());

        mockMvc.perform(post("/api/admin/users/{id}/approve", UUID.randomUUID())
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }

    @Test
    void changeRole_withAdminToken_returns204() throws Exception {
        doNothing().when(userAction).changeRole(any(), any(), any());

        mockMvc.perform(patch("/api/admin/users/{id}/role", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}")
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUser_withAdminToken_returns204() throws Exception {
        doNothing().when(userAction).deleteUser(any(), any());

        mockMvc.perform(delete("/api/admin/users/{id}", UUID.randomUUID())
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 4**: `./gradlew test --tests 'com.kista.adapter.in.web.AdminUserControllerTest'` — 5건 통과 확인

- [ ] **Step 5**: 커밋: `feat(web): add AdminUserController with 5 endpoints and tests`

---

## T5. AdminDashboardController + UserResponse role 필드 추가

**Files:**
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/dto/AdminDashboardResponse.java`
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminDashboardController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminDashboardControllerTest.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/dto/UserResponse.java`

- [ ] **Step 1**: `AdminDashboardResponse` DTO 작성

```java
// adapter/in/web/dto/AdminDashboardResponse.java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.AdminStats;
import io.swagger.v3.oas.annotations.media.Schema;

public record AdminDashboardResponse(
        @Schema(description = "전체 사용자 수") long totalUsers,
        @Schema(description = "승인 대기 수") long pendingCount,
        @Schema(description = "승인된 수") long activeCount,
        @Schema(description = "거절된 수") long rejectedCount,
        @Schema(description = "전체 계좌 수") long totalAccounts
) {
    public static AdminDashboardResponse from(AdminStats stats) {
        return new AdminDashboardResponse(stats.totalUsers(), stats.pendingCount(),
                stats.activeCount(), stats.rejectedCount(), stats.totalAccounts());
    }
}
```

- [ ] **Step 2**: `AdminDashboardController` 작성

```java
// adapter/in/web/AdminDashboardController.java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.AdminDashboardResponse;
import com.kista.domain.port.in.AdminDashboardUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Admin - Dashboard", description = "관리자 대시보드 통계 API")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardUseCase dashboardUseCase;

    // 사용자 현황 통계 조회 (상태별 카운트 + 총 계좌 수)
    @Operation(summary = "대시보드 통계 조회")
    @GetMapping("/stats")
    public AdminDashboardResponse getStats(@AuthenticationPrincipal UUID adminId) {
        return AdminDashboardResponse.from(dashboardUseCase.getStats());
    }
}
```

- [ ] **Step 3**: `UserResponse`에 `role` 필드 추가

```java
// adapter/in/web/dto/UserResponse.java 전체 교체
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.User;
import com.kista.domain.model.UserRole;
import com.kista.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record UserResponse(
        @Schema(description = "사용자 고유 ID") UUID id,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "계정 상태 (PENDING/ACTIVE/REJECTED)") UserStatus status,
        @Schema(description = "역할 (USER/ADMIN)") UserRole role,
        @Schema(description = "텔레그램 알림 설정 여부") boolean hasTelegram
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.nickname(),
                user.status(),
                user.role(),
                user.telegramChatId() != null
        );
    }
}
```

- [ ] **Step 4**: `AdminDashboardControllerTest` 작성

```java
// test/.../AdminDashboardControllerTest.java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.AdminStats;
import com.kista.domain.port.in.AdminDashboardUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDashboardController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminDashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminDashboardUseCase dashboardUseCase;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void getStats_withAdminToken_returns200() throws Exception {
        when(dashboardUseCase.getStats()).thenReturn(new AdminStats(10, 3, 5, 2, 7));

        var adminToken = new UsernamePasswordAuthenticationToken(ADMIN_UUID, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(get("/api/admin/dashboard/stats").with(authentication(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(10))
                .andExpect(jsonPath("$.pendingCount").value(3))
                .andExpect(jsonPath("$.totalAccounts").value(7));
    }

    @Test
    void getStats_withUserToken_returns403() throws Exception {
        var userToken = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mockMvc.perform(get("/api/admin/dashboard/stats").with(authentication(userToken)))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 5**: `./gradlew test` — 전체 테스트 통과 확인 (기존 + 신규)

- [ ] **Step 6**: 커밋: `feat(web): add AdminDashboardController and add role field to UserResponse`

---

## T6. kista-ui 타입 정의

**Working directory:** `/Users/phs/workspace/kista/kista-ui`
**Branch:** `feature/phase2b-admin-ui` (main에서 신규 생성: `git checkout -b feature/phase2b-admin-ui`)

- [ ] **Step 1**: 브랜치 생성

```bash
cd /Users/phs/workspace/kista/kista-ui
git checkout main
git checkout -b feature/phase2b-admin-ui
```

- [ ] **Step 2**: `types/user.ts` 업데이트 — `UserRole` + `User.role` 추가

```typescript
// types/user.ts
export type UserStatus = 'PENDING' | 'ACTIVE' | 'REJECTED'
export type UserRole = 'USER' | 'ADMIN'

export interface User {
  id: string
  nickname: string
  status: UserStatus
  role: UserRole   // JWT role claim에서 반영, /api/auth/me 응답에 포함됨
  hasTelegram: boolean
}
```

- [ ] **Step 3**: `types/admin.ts` 생성

```typescript
// types/admin.ts
import type { UserStatus, UserRole } from './user'

export interface AdminUser {
  id: string
  nickname: string
  status: UserStatus
  role: UserRole
  createdAt: string  // ISO-8601 timestamp
}

export interface AdminStats {
  totalUsers: number
  pendingCount: number
  activeCount: number
  rejectedCount: number
  totalAccounts: number
}
```

- [ ] **Step 4**: `npm run typecheck` — 에러 확인 및 수정 (User.role 추가로 기존 코드에 영향 없어야 함 — role은 신규 필드이므로 기존 destructuring에서 무시됨)

- [ ] **Step 5**: 커밋: `feat(types): add UserRole to User and create AdminUser/AdminStats types`

---

## T7. lib/api/admin.ts + admin Route Handler

**Files:**
- Create: `kista-ui/lib/api/admin.ts`
- Create: `kista-ui/app/api/admin/[[...path]]/route.ts`

- [ ] **Step 1**: `lib/api/admin.ts` 작성

```typescript
// lib/api/admin.ts
import { apiFetch } from './client'
import type { AdminUser, AdminStats } from '@/types/admin'
import type { UserRole, UserStatus } from '@/types/user'

// 사용자 목록 조회 (status 필터 옵션)
export async function listAdminUsers(token: string, status?: UserStatus): Promise<AdminUser[]> {
  const query = status ? `?status=${status}` : ''
  return apiFetch<AdminUser[]>(`/api/admin/users${query}`, { method: 'GET' }, token)
}

// 사용자 승인 (Route Handler 경유 — Client Component에서 token 없이 호출)
export async function approveAdminUser(userId: string): Promise<void> {
  const res = await fetch(`/api/admin/users/${userId}/approve`, { method: 'POST' })
  if (!res.ok) throw new Error(`approve failed: ${res.status}`)
}

// 사용자 거절
export async function rejectAdminUser(userId: string): Promise<void> {
  const res = await fetch(`/api/admin/users/${userId}/reject`, { method: 'POST' })
  if (!res.ok) throw new Error(`reject failed: ${res.status}`)
}

// 역할 변경
export async function changeAdminUserRole(userId: string, role: UserRole): Promise<void> {
  const res = await fetch(`/api/admin/users/${userId}/role`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  })
  if (!res.ok) throw new Error(`changeRole failed: ${res.status}`)
}

// 사용자 삭제
export async function deleteAdminUser(userId: string): Promise<void> {
  const res = await fetch(`/api/admin/users/${userId}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`delete failed: ${res.status}`)
}

// 대시보드 통계 (Server Component에서 token으로 직접 호출)
export async function getAdminStats(token: string): Promise<AdminStats> {
  return apiFetch<AdminStats>('/api/admin/dashboard/stats', { method: 'GET' }, token)
}
```

- [ ] **Step 2**: admin Route Handler 작성

```typescript
// app/api/admin/[[...path]]/route.ts
import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'
import { getAuthToken } from '@/lib/auth/token'

const API_BASE_URL = process.env.API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL

type Params = { params: Promise<{ path?: string[] }> }

async function proxy(request: NextRequest, pathSegments: string[]) {
  const token = await getAuthToken()
  if (!token) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })

  const subPath = pathSegments.length > 0 ? `/${pathSegments.join('/')}` : ''
  const url = `${API_BASE_URL}/api/admin${subPath}${request.nextUrl.search}`
  const headers: HeadersInit = { Authorization: `Bearer ${token}` }
  let body: BodyInit | undefined

  if (request.method !== 'GET' && request.method !== 'DELETE') {
    const ct = request.headers.get('content-type')
    if (ct) headers['Content-Type'] = ct
    const text = await request.text()
    if (text) body = text
  }

  const res = await fetch(url, { method: request.method, headers, body })

  if (!res.ok) {
    const errBody = await res.text().catch(() => '')
    console.error(`[admin${subPath} ${request.method}] ${res.status}`, errBody)
    return NextResponse.json({ error: 'Failed' }, { status: res.status })
  }
  if (res.status === 204) return new NextResponse(null, { status: 204 })
  return NextResponse.json(await res.json(), { status: res.status })
}

export async function GET(req: NextRequest, { params }: Params) {
  return proxy(req, (await params).path ?? [])
}
export async function POST(req: NextRequest, { params }: Params) {
  return proxy(req, (await params).path ?? [])
}
export async function PATCH(req: NextRequest, { params }: Params) {
  return proxy(req, (await params).path ?? [])
}
export async function DELETE(req: NextRequest, { params }: Params) {
  return proxy(req, (await params).path ?? [])
}
```

- [ ] **Step 3**: `npm run typecheck` — 통과 확인

- [ ] **Step 4**: 커밋: `feat(api): add admin API functions and Route Handler`

---

## T8. proxy.ts ADMIN role guard + role 쿠키 저장

**File:** `kista-ui/proxy.ts`

**Goal:** `/admin/**` 경로는 ADMIN role이어야 접근 가능. `/me` 응답의 `role` 필드를 `kista-user-role` 쿠키에 캐싱.

- [ ] **Step 1**: `proxy.ts` 전체를 다음으로 교체

```typescript
import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

const STATUS_COOKIE = 'kista-user-status'
const ROLE_COOKIE = 'kista-user-role'
const KISTA_TOKEN_COOKIE = 'kista-token'
const VALID_STATUSES = new Set(['PENDING', 'REJECTED', 'ACTIVE'])
const COOKIE_OPTIONS = {
  httpOnly: true,
  secure: process.env.NODE_ENV === 'production',
  sameSite: 'lax' as const,
  maxAge: 604800,
  path: '/',
}
const PROTECTED_PREFIXES = ['/dashboard', '/accounts', '/settings', '/statistics']
const ADMIN_PREFIXES = ['/admin']

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl
  const response = NextResponse.next({ request: { headers: request.headers } })

  // API 라우트는 통과
  if (pathname.startsWith('/api/')) return response

  const token = request.cookies.get(KISTA_TOKEN_COOKIE)?.value

  if (!token) {
    const isProtected =
      PROTECTED_PREFIXES.some((p) => pathname.startsWith(p)) ||
      ADMIN_PREFIXES.some((p) => pathname.startsWith(p))
    if (isProtected) return redirectTo('/', request)
    return response
  }

  // 인증됨: status + role 캐시 확인
  const cachedStatus = request.cookies.get(STATUS_COOKIE)?.value
  const cachedRole = request.cookies.get(ROLE_COOKIE)?.value

  let status: string
  let role: string

  if (cachedStatus && VALID_STATUSES.has(cachedStatus) && cachedRole) {
    // 빠른 경로: 쿠키 캐시 사용
    status = cachedStatus
    role = cachedRole
  } else {
    // 느린 경로: kista-api /me 호출
    const isProtected =
      PROTECTED_PREFIXES.some((p) => pathname.startsWith(p)) ||
      ADMIN_PREFIXES.some((p) => pathname.startsWith(p))
    try {
      const apiUrl = process.env.API_BASE_URL ?? process.env.NEXT_PUBLIC_API_BASE_URL
      const meRes = await fetch(`${apiUrl}/api/auth/me`, {
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(5000),
      })

      if (!meRes.ok) {
        return isProtected ? redirectTo('/', request) : response
      }

      const userData = await meRes.json()
      status = userData.status
      role = userData.role ?? 'USER'

      // PENDING은 캐싱 금지 — 승인 후 캐시 히트 버그 방지
      if (status !== 'PENDING') {
        response.cookies.set(STATUS_COOKIE, status, COOKIE_OPTIONS)
        response.cookies.set(ROLE_COOKIE, role, COOKIE_OPTIONS)
      }
    } catch {
      return isProtected ? redirectTo('/', request) : response
    }
  }

  return routeByStatusAndRole(status, role, pathname, request, response)
}

function redirectTo(pathname: string, request: NextRequest): NextResponse {
  const url = request.nextUrl.clone()
  url.pathname = pathname
  return NextResponse.redirect(url)
}

function routeByStatusAndRole(
  status: string,
  role: string,
  pathname: string,
  request: NextRequest,
  response: NextResponse
): NextResponse {
  // /admin/** — ADMIN만 접근
  if (ADMIN_PREFIXES.some((p) => pathname.startsWith(p))) {
    return role === 'ADMIN' ? response : redirectTo('/dashboard', request)
  }

  if (status === 'PENDING') {
    if (pathname !== '/pending') return redirectTo('/pending', request)
    return response
  }
  if (status === 'REJECTED') {
    if (pathname !== '/rejected') return redirectTo('/rejected', request)
    return response
  }
  if (status === 'ACTIVE') {
    if (pathname === '/' || pathname === '/pending' || pathname === '/rejected') {
      return redirectTo('/dashboard', request)
    }
    return response
  }
  return response
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|.*\\..*).*)'],
}
```

- [ ] **Step 2**: `npm run typecheck` — 통과 확인

- [ ] **Step 3**: 커밋: `feat(proxy): add ADMIN role guard and kista-user-role cookie`

---

## T9. Admin 레이아웃 Shell — AdminSidebar + AdminTopBar

**Files:**
- Create: `kista-ui/components/admin/AdminSidebar.tsx`
- Create: `kista-ui/components/admin/AdminTopBar.tsx`
- Create: `kista-ui/app/(admin)/layout.tsx`

- [ ] **Step 1**: `components/admin/AdminSidebar.tsx` 작성

```tsx
// components/admin/AdminSidebar.tsx
'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { LayoutDashboard, Clock, Users, LogOut } from 'lucide-react'

const NAV_ITEMS = [
  { href: '/admin',          label: 'Overview',  icon: LayoutDashboard, exact: true },
  { href: '/admin/pending',  label: '승인 대기', icon: Clock },
  { href: '/admin/users',    label: '사용자',    icon: Users },
]

export function AdminSidebar() {
  const pathname = usePathname()
  const router = useRouter()

  async function handleLogout() {
    await fetch('/api/auth/logout', { method: 'POST' })
    router.push('/')
  }

  return (
    <aside className="hidden lg:flex flex-col w-[220px] min-h-screen shrink-0 border-r border-border px-4 py-6 bg-muted/30">
      {/* Logo */}
      <Link href="/admin" className="flex items-center gap-2 px-2 pb-6">
        <span className="font-extrabold text-lg tracking-wide text-rose-600">KISTA</span>
        <span className="text-xs font-semibold text-muted-foreground bg-rose-100 text-rose-600 px-1.5 py-0.5 rounded">ADMIN</span>
      </Link>

      {/* Nav */}
      <nav className="flex flex-col gap-0.5 flex-1">
        {NAV_ITEMS.map(({ href, label, icon: Icon, exact }) => {
          const active = exact ? pathname === href : pathname.startsWith(href)
          return (
            <Link
              key={href}
              href={href}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                active
                  ? 'bg-rose-50 text-rose-600'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground'
              }`}
            >
              <Icon className="size-[18px] shrink-0" />
              {label}
            </Link>
          )
        })}
      </nav>

      {/* 로그아웃 */}
      <button
        onClick={handleLogout}
        className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground transition-colors w-full text-left"
      >
        <LogOut className="size-[18px] shrink-0" />
        로그아웃
      </button>
    </aside>
  )
}
```

- [ ] **Step 2**: `components/admin/AdminTopBar.tsx` 작성 (모바일용)

```tsx
// components/admin/AdminTopBar.tsx
'use client'

import { usePathname, useRouter } from 'next/navigation'
import { LayoutDashboard, Clock, Users } from 'lucide-react'
import Link from 'next/link'

const NAV_ITEMS = [
  { href: '/admin',          label: 'Overview',  icon: LayoutDashboard, exact: true },
  { href: '/admin/pending',  label: '승인 대기', icon: Clock },
  { href: '/admin/users',    label: '사용자',    icon: Users },
]

export function AdminTopBar() {
  const pathname = usePathname()

  return (
    <header className="lg:hidden sticky top-0 z-10 border-b border-border bg-background">
      {/* 헤더 타이틀 */}
      <div className="flex items-center justify-between px-4 py-3">
        <span className="font-extrabold text-base text-rose-600">KISTA</span>
        <span className="text-xs font-semibold bg-rose-100 text-rose-600 px-2 py-0.5 rounded">ADMIN</span>
      </div>
      {/* 탭 네비게이션 */}
      <nav className="flex border-t border-border">
        {NAV_ITEMS.map(({ href, label, icon: Icon, exact }) => {
          const active = exact ? pathname === href : pathname.startsWith(href)
          return (
            <Link
              key={href}
              href={href}
              className={`flex-1 flex flex-col items-center gap-1 py-2 text-xs font-medium transition-colors ${
                active ? 'text-rose-600' : 'text-muted-foreground'
              }`}
            >
              <Icon className="size-4" />
              {label}
            </Link>
          )
        })}
      </nav>
    </header>
  )
}
```

- [ ] **Step 3**: `app/(admin)/layout.tsx` 작성

```tsx
// app/(admin)/layout.tsx
import { AdminSidebar } from '@/components/admin/AdminSidebar'
import { AdminTopBar } from '@/components/admin/AdminTopBar'

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen">
      <AdminSidebar />
      <div className="flex flex-col flex-1 min-w-0">
        <AdminTopBar />
        <main className="flex-1 p-4 lg:p-8 max-w-5xl w-full mx-auto">
          {children}
        </main>
      </div>
    </div>
  )
}
```

- [ ] **Step 4**: `npm run typecheck` + `npm run build` (또는 `npm run dev` 기동 후 `/admin` 접속 확인)

- [ ] **Step 5**: 커밋: `feat(admin): add AdminSidebar, AdminTopBar, and (admin) layout`

---

## T10. Overview 페이지

**Files:**
- Create: `kista-ui/app/(admin)/admin/page.tsx`

**Design:** Hero 통계 4개 카드(전체/대기/승인/거절) + 최근 PENDING 사용자 목록 (최대 5명) + 승인/거절 버튼.

- [ ] **Step 1**: `components/admin/ApproveRejectButtons.tsx` 작성 (Client Component — 인터랙션 분리)

```tsx
// components/admin/ApproveRejectButtons.tsx
'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { approveAdminUser, rejectAdminUser } from '@/lib/api/admin'
import { toast } from 'sonner'

interface Props {
  userId: string
  nickname: string
}

export function ApproveRejectButtons({ userId, nickname }: Props) {
  const router = useRouter()
  const [loading, setLoading] = useState<'approve' | 'reject' | null>(null)

  async function handleApprove() {
    setLoading('approve')
    try {
      await approveAdminUser(userId)
      toast.success(`${nickname} 승인 완료`)
      router.refresh()
    } catch {
      toast.error('승인 실패')
    } finally {
      setLoading(null)
    }
  }

  async function handleReject() {
    setLoading('reject')
    try {
      await rejectAdminUser(userId)
      toast.success(`${nickname} 거절 완료`)
      router.refresh()
    } catch {
      toast.error('거절 실패')
    } finally {
      setLoading(null)
    }
  }

  return (
    <div className="flex gap-2">
      <button
        onClick={handleApprove}
        disabled={loading !== null}
        className="px-3 py-1.5 text-xs font-semibold rounded-md bg-rose-600 text-white hover:bg-rose-700 disabled:opacity-50 transition-colors"
      >
        {loading === 'approve' ? '...' : '승인'}
      </button>
      <button
        onClick={handleReject}
        disabled={loading !== null}
        className="px-3 py-1.5 text-xs font-semibold rounded-md border border-border text-muted-foreground hover:bg-muted disabled:opacity-50 transition-colors"
      >
        {loading === 'reject' ? '...' : '거절'}
      </button>
    </div>
  )
}
```

- [ ] **Step 2**: `app/(admin)/admin/page.tsx` 작성

```tsx
// app/(admin)/admin/page.tsx
import { getAuthToken } from '@/lib/auth/token'
import { getAdminStats, listAdminUsers } from '@/lib/api/admin'
import { ApproveRejectButtons } from '@/components/admin/ApproveRejectButtons'
import { Users, Clock, CheckCircle, XCircle } from 'lucide-react'

export default async function AdminOverviewPage() {
  const token = await getAuthToken()
  const [stats, pendingUsers] = await Promise.all([
    token ? getAdminStats(token).catch(() => null) : null,
    token ? listAdminUsers(token, 'PENDING').catch(() => []) : [],
  ])

  const recentPending = pendingUsers.slice(0, 5) // 최대 5명만 표시

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">Overview</h1>
        <p className="text-sm text-muted-foreground mt-1">사용자 현황 및 최근 대기 목록</p>
      </div>

      {/* Hero 통계 카드 */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-10">
        <StatCard icon={<Users className="size-5 text-rose-500" />} label="전체" value={stats?.totalUsers ?? '-'} />
        <StatCard icon={<Clock className="size-5 text-amber-500" />} label="승인 대기" value={stats?.pendingCount ?? '-'} accent="amber" />
        <StatCard icon={<CheckCircle className="size-5 text-emerald-500" />} label="승인됨" value={stats?.activeCount ?? '-'} accent="emerald" />
        <StatCard icon={<XCircle className="size-5 text-slate-400" />} label="거절됨" value={stats?.rejectedCount ?? '-'} />
      </div>

      {/* 최근 대기 사용자 */}
      <section>
        <h2 className="text-base font-bold mb-4">
          승인 대기
          {stats?.pendingCount ? (
            <span className="ml-2 text-xs font-semibold bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full">
              {stats.pendingCount}명
            </span>
          ) : null}
        </h2>

        {recentPending.length === 0 ? (
          <div className="rounded-xl border border-border p-8 text-center text-sm text-muted-foreground">
            대기 중인 사용자가 없습니다
          </div>
        ) : (
          <div className="rounded-xl border border-border divide-y divide-border">
            {recentPending.map((user) => (
              <div key={user.id} className="flex items-center justify-between px-4 py-3">
                <div>
                  <p className="text-sm font-semibold">{user.nickname}</p>
                  <p className="text-xs text-muted-foreground">
                    {new Date(user.createdAt).toLocaleDateString('ko-KR')}
                  </p>
                </div>
                <ApproveRejectButtons userId={user.id} nickname={user.nickname} />
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

function StatCard({
  icon,
  label,
  value,
  accent,
}: {
  icon: React.ReactNode
  label: string
  value: number | string
  accent?: 'amber' | 'emerald'
}) {
  const bg = accent === 'amber' ? 'bg-amber-50' : accent === 'emerald' ? 'bg-emerald-50' : 'bg-muted/40'
  return (
    <div className={`rounded-xl border border-border p-4 ${bg}`}>
      <div className="flex items-center gap-2 mb-3">{icon}<span className="text-xs text-muted-foreground font-medium">{label}</span></div>
      <p className="text-3xl font-extrabold">{value}</p>
    </div>
  )
}
```

- [ ] **Step 3**: `npm run typecheck` — 통과 확인

- [ ] **Step 4**: 커밋: `feat(admin): add Overview page with stats hero and pending list`

---

## T11. Pending 페이지

**File:** `kista-ui/app/(admin)/admin/pending/page.tsx`

**Design:** 전체 PENDING 사용자 목록 (카드 형식), 각 카드에 승인/거절 버튼.

- [ ] **Step 1**: `app/(admin)/admin/pending/page.tsx` 작성

```tsx
// app/(admin)/admin/pending/page.tsx
import { getAuthToken } from '@/lib/auth/token'
import { listAdminUsers } from '@/lib/api/admin'
import { ApproveRejectButtons } from '@/components/admin/ApproveRejectButtons'
import { Clock } from 'lucide-react'

export default async function AdminPendingPage() {
  const token = await getAuthToken()
  const users = token ? await listAdminUsers(token, 'PENDING').catch(() => []) : []

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">승인 대기</h1>
        <p className="text-sm text-muted-foreground mt-1">
          {users.length > 0 ? `${users.length}명이 승인을 기다리고 있습니다` : '대기 중인 사용자가 없습니다'}
        </p>
      </div>

      {users.length === 0 ? (
        <div className="rounded-xl border border-border p-16 text-center">
          <Clock className="size-10 text-muted-foreground mx-auto mb-4" />
          <p className="text-sm text-muted-foreground">새로운 가입 신청이 없습니다</p>
        </div>
      ) : (
        <div className="grid gap-3">
          {users.map((user) => (
            <div
              key={user.id}
              className="rounded-xl border border-border bg-background px-5 py-4 flex items-center justify-between gap-4"
            >
              <div className="min-w-0">
                <p className="font-semibold truncate">{user.nickname}</p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  가입일 {new Date(user.createdAt).toLocaleDateString('ko-KR')}
                </p>
              </div>
              <ApproveRejectButtons userId={user.id} nickname={user.nickname} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2**: `npm run typecheck` — 통과 확인

- [ ] **Step 3**: 커밋: `feat(admin): add Pending page with full pending users list`

---

## T12. Users 페이지

**Files:**
- Create: `kista-ui/components/admin/ChangeRoleButton.tsx`
- Create: `kista-ui/app/(admin)/admin/users/page.tsx`

**Design:** 전체 사용자 테이블 (닉네임 | 상태 뱃지 | 역할 뱃지 | 가입일 | 액션).

- [ ] **Step 1**: `components/admin/ChangeRoleButton.tsx` 작성

```tsx
// components/admin/ChangeRoleButton.tsx
'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { changeAdminUserRole } from '@/lib/api/admin'
import type { UserRole } from '@/types/user'
import { toast } from 'sonner'

interface Props {
  userId: string
  currentRole: UserRole
}

export function ChangeRoleButton({ userId, currentRole }: Props) {
  const router = useRouter()
  const [loading, setLoading] = useState(false)
  const newRole: UserRole = currentRole === 'ADMIN' ? 'USER' : 'ADMIN'

  async function handleChange() {
    setLoading(true)
    try {
      await changeAdminUserRole(userId, newRole)
      toast.success(`역할을 ${newRole}로 변경했습니다`)
      router.refresh()
    } catch {
      toast.error('역할 변경 실패')
    } finally {
      setLoading(false)
    }
  }

  return (
    <button
      onClick={handleChange}
      disabled={loading}
      className="px-2.5 py-1 text-xs font-semibold rounded-md border border-border text-muted-foreground hover:bg-muted disabled:opacity-50 transition-colors"
    >
      {loading ? '...' : `→ ${newRole}`}
    </button>
  )
}
```

- [ ] **Step 2**: `app/(admin)/admin/users/page.tsx` 작성

```tsx
// app/(admin)/admin/users/page.tsx
import { getAuthToken } from '@/lib/auth/token'
import { listAdminUsers } from '@/lib/api/admin'
import { ChangeRoleButton } from '@/components/admin/ChangeRoleButton'
import type { UserStatus } from '@/types/user'

const STATUS_LABEL: Record<UserStatus, string> = {
  PENDING: '대기',
  ACTIVE: '승인',
  REJECTED: '거절',
}
const STATUS_COLOR: Record<UserStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  ACTIVE: 'bg-emerald-100 text-emerald-700',
  REJECTED: 'bg-slate-100 text-slate-600',
}

export default async function AdminUsersPage() {
  const token = await getAuthToken()
  const users = token ? await listAdminUsers(token).catch(() => []) : []

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">사용자 목록</h1>
        <p className="text-sm text-muted-foreground mt-1">전체 {users.length}명</p>
      </div>

      {users.length === 0 ? (
        <div className="rounded-xl border border-border p-12 text-center text-sm text-muted-foreground">
          등록된 사용자가 없습니다
        </div>
      ) : (
        <div className="rounded-xl border border-border overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-xs text-muted-foreground font-semibold">
              <tr>
                <th className="text-left px-4 py-3">닉네임</th>
                <th className="text-left px-4 py-3">상태</th>
                <th className="text-left px-4 py-3">역할</th>
                <th className="text-left px-4 py-3">가입일</th>
                <th className="text-left px-4 py-3">역할 변경</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.map((user) => (
                <tr key={user.id} className="hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3 font-medium">{user.nickname}</td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_COLOR[user.status]}`}>
                      {STATUS_LABEL[user.status]}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-semibold ${
                      user.role === 'ADMIN'
                        ? 'bg-rose-100 text-rose-700'
                        : 'bg-slate-100 text-slate-600'
                    }`}>
                      {user.role}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {new Date(user.createdAt).toLocaleDateString('ko-KR')}
                  </td>
                  <td className="px-4 py-3">
                    <ChangeRoleButton userId={user.id} currentRole={user.role} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 3**: `npm run typecheck` — 통과 확인

- [ ] **Step 4**: 커밋: `feat(admin): add Users page with role management table`

---

## T13. 최종 검증

- [ ] **Step 1**: kista-api 전체 테스트

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew test
```

Expected: BUILD SUCCESSFUL, 실패 0건

- [ ] **Step 2**: kista-api ArchUnit 테스트

```bash
./gradlew test --tests 'com.kista.architecture.*'
```

Expected: 5건 모두 통과

- [ ] **Step 3**: kista-ui typecheck + build

```bash
cd /Users/phs/workspace/kista/kista-ui
npm run typecheck
npm run build
```

Expected: 타입 에러 0건, build 성공

- [ ] **Step 4**: 로컬 기동 + 수동 검증

```bash
# kista-api (postgres 먼저 기동)
docker-compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'

# ADMIN 토큰 발급
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)

# 통계 조회
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/dashboard/stats | jq

# 사용자 목록 조회
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/users | jq

# USER 토큰으로 403 확인
USER_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $USER_TOKEN" localhost:8080/api/admin/users
# Expected: 403
```

- [ ] **Step 5**: kista-ui 로컬 기동 + `/admin` 접근 확인

```bash
cd /Users/phs/workspace/kista/kista-ui
npm run dev
# 브라우저에서 로그인 후 /admin 접근 → ADMIN이면 Overview 노출, USER이면 /dashboard 리다이렉트
```

- [ ] **Step 6**: 메모리 업데이트 — `project_phase2a_admin_foundation.md` → Phase 2B 완료 상태로 갱신

---

## 위험 요소

1. **AdminService의 `ApproveUserUseCase` 순환 의존**: `AdminService`(application.service)가 `ApproveUserUseCase`(domain.port.in) 인터페이스 주입 → ArchUnit 안전 (application → domain 허용).
2. **`UserResponse` role 추가로 kista-ui 타입 불일치**: kista-ui `types/user.ts`의 `User.role`이 옵셔널이 아니므로 모든 `User` 사용처에서 `role` 접근 가능. 기존 코드는 `role`을 사용 안 했으므로 영향 없음.
3. **proxy.ts `kista-user-role` 쿠키 캐싱**: ADMIN이 USER로 강등될 경우 쿠키 만료 전까지 `/admin` 접근 가능. 허용 범위 — 만료는 7일. 보안 민감 환경에서는 쿠키 없이 매 요청마다 /me 호출 방식으로 변경.
4. **삭제 CASCADE 주의**: `users.id` FK에 `ON DELETE CASCADE`가 걸린 테이블: `accounts`, `audit_logs`, `kis_tokens`, `trade_histories`, `portfolio_snapshots`. V8-V16 마이그레이션에서 모두 적용됨 — 사용자 삭제 시 자동 제거됨.
5. **`AdminUserController.listUsers()` 중복 호출 버그**: Step 2에 수정 지시 명시됨 — 수정 없이 구현 시 `listAll()` 2번 호출됨. 반드시 로컬 변수로 받아 `fromList(users)` 호출.

---

## 명시 비범위 (Phase 2C/D)

- Phase 2C: Admin 추가 화면 (Accounts, Trades, System, Audit, Statistics 5종)
- Phase 2C: 모바일 admin 3화면 (Overview, Pending, Anomalies)
- Phase 2D: 회원 탈퇴 백엔드 + UI
- Phase 2D: AccountEdit 변경이력 + test-connection 엔드포인트
- Phase 2D: 텔레그램 callback_query 제거
