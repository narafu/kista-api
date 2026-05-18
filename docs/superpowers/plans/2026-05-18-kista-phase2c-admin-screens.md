# Phase 2C Admin Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin 화면 4종 추가 — Accounts(계좌 현황), Trades(거래 내역), Audit(감사 로그), Anomalies(이상 징후) — 백엔드 4개 엔드포인트 + 프론트엔드 4개 페이지 + 네비게이션 확장.

**Architecture:** kista-api Hexagonal (domain → port → service → controller) + kista-ui Next.js 16 Server Component. 기존 Admin 인프라(JWT ROLE_ADMIN guard, audit_logs 테이블, Phase 2B AdminService)를 토대로 신규 서비스 4개 추가. 기존 `/api/admin/[[...path]]` catch-all Route Handler가 자동 프록시 — 프론트엔드 Route Handler 신규 불필요.

**Tech Stack:** Java 21, Spring Boot 3.4, Next.js 16, TypeScript, Tailwind CSS

---

## Context

Phase 2B 완료 상태:
- `AdminService` (application/service): `AdminListUsersUseCase`, `AdminUserActionUseCase`, `AdminDashboardUseCase` 구현
- `AdminUserController`, `AdminDashboardController`, `AdminPingController` 존재
- `kista-ui`: admin layout + Overview/Pending/Users 3페이지 + `AdminSidebar`(3항목) + `AdminTopBar`(3탭) + catch-all Route Handler

도메인 포트 현황:
- `AccountRepository`: `findAll()` 없음 (관리자용 필요)
- `AuditLogPort`: `findAll()` 없음 (목록 조회 필요)
- `TradeHistoryPort`: `findBy(from, to, symbol)` 있음, `findAll(from, to)` 없음 (전체 계좌 조회 필요)
- `AdminListUsersUseCase`: 기존 존재 (AdminService 구현) → 컨트롤러에서 ownerNickname 조회에 재사용

ownerNickname 조회 패턴: `Account.userId` → `UserRepository.findById` → `User.nickname`. 이 조회는 **서비스가 아닌 컨트롤러**에서 `AdminListUsersUseCase.listAll()`로 얻은 userMap을 통해 처리. 컨트롤러는 UseCase(domain.port.in)만 주입 가능 — 도메인 Port.out 직접 주입 금지(ArchUnit).

---

## 파일 변경 목록

### kista-api

| 타입 | 파일 |
|------|------|
| Modify | `domain/port/out/AccountRepository.java` |
| Modify | `domain/port/out/AuditLogPort.java` |
| Modify | `domain/port/out/TradeHistoryPort.java` |
| Modify | `adapter/out/persistence/AccountPersistenceAdapter.java` |
| Modify | `adapter/out/persistence/AuditLogJpaRepository.java` |
| Modify | `adapter/out/persistence/AuditLogPersistenceAdapter.java` |
| Modify | `adapter/out/persistence/TradeHistoryJpaRepository.java` |
| Modify | `adapter/out/persistence/TradeHistoryPersistenceAdapter.java` |
| Create | `domain/model/AdminAnomalies.java` |
| Create | `domain/port/in/AdminListAccountsUseCase.java` |
| Create | `domain/port/in/AdminListAuditLogsUseCase.java` |
| Create | `domain/port/in/AdminListTradesUseCase.java` |
| Create | `domain/port/in/AdminAnomaliesUseCase.java` |
| Create | `application/service/AdminAccountService.java` |
| Create | `application/service/AdminAuditService.java` |
| Create | `application/service/AdminTradeService.java` |
| Create | `application/service/AdminAnomaliesService.java` |
| Create | `adapter/in/web/AdminAccountController.java` |
| Create | `adapter/in/web/AdminAuditController.java` |
| Create | `adapter/in/web/AdminTradeController.java` |
| Create | `adapter/in/web/AdminAnomaliesController.java` |
| Create | `src/test/java/com/kista/adapter/in/web/AdminAccountControllerTest.java` |
| Create | `src/test/java/com/kista/adapter/in/web/AdminAuditControllerTest.java` |
| Create | `src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java` |
| Create | `src/test/java/com/kista/adapter/in/web/AdminAnomaliesControllerTest.java` |

### kista-ui

| 타입 | 파일 |
|------|------|
| Modify | `types/admin.ts` |
| Modify | `lib/api/admin.ts` |
| Create | `app/(admin)/admin/accounts/page.tsx` |
| Create | `app/(admin)/admin/audit/page.tsx` |
| Create | `app/(admin)/admin/trades/page.tsx` |
| Create | `app/(admin)/admin/anomalies/page.tsx` |
| Modify | `components/admin/AdminSidebar.tsx` |
| Modify | `components/admin/AdminTopBar.tsx` |

---

## T1. 포트 인터페이스 확장 + 도메인 타입 신규

**Files:**
- Modify: `kista-api/src/main/java/com/kista/domain/port/out/AccountRepository.java`
- Modify: `kista-api/src/main/java/com/kista/domain/port/out/AuditLogPort.java`
- Modify: `kista-api/src/main/java/com/kista/domain/port/out/TradeHistoryPort.java`
- Create: `kista-api/src/main/java/com/kista/domain/model/AdminAnomalies.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminListAccountsUseCase.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminListAuditLogsUseCase.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminListTradesUseCase.java`
- Create: `kista-api/src/main/java/com/kista/domain/port/in/AdminAnomaliesUseCase.java`

- [ ] **Step 1**: `AccountRepository.java` 끝에 `findAll()` 추가

```java
    List<Account> findAll(); // 전체 계좌 목록 (관리자용)
```

- [ ] **Step 2**: `AuditLogPort.java` 끝에 `findAll()` 추가

```java
    // 감사 로그 전체 조회 (최신순, 최대 100건) — 관리자 목록 화면용
    List<AuditLog> findAll();
```

- [ ] **Step 3**: `TradeHistoryPort.java` 끝에 `findAll(from, to)` 추가

```java
    // 기간 내 전체 계좌 거래 내역 조회 (symbol 필터 없음) — 관리자용
    List<TradeHistory> findAll(LocalDate from, LocalDate to);
```

- [ ] **Step 4**: `AdminAnomalies.java` 생성

```java
package com.kista.domain.model;

import java.util.List;

// 이상 징후 집계 도메인 모델 — 컨트롤러에서 DTO 변환
public record AdminAnomalies(
    List<TradeHistory> failedTrades,      // 최근 30일 FAILED 거래
    List<Account> pausedAccounts,         // 전략 PAUSED 계좌
    List<Account> inactiveAccounts        // 최근 7일 거래 없는 ACTIVE 전략 계좌
) {}
```

- [ ] **Step 5**: `AdminListAccountsUseCase.java` 생성

```java
package com.kista.domain.port.in;

import com.kista.domain.model.Account;
import java.util.List;

public interface AdminListAccountsUseCase {
    List<Account> listAll();
}
```

- [ ] **Step 6**: `AdminListAuditLogsUseCase.java` 생성

```java
package com.kista.domain.port.in;

import com.kista.domain.model.AuditLog;
import java.util.List;

public interface AdminListAuditLogsUseCase {
    List<AuditLog> listAll();
}
```

- [ ] **Step 7**: `AdminListTradesUseCase.java` 생성

```java
package com.kista.domain.port.in;

import com.kista.domain.model.TradeHistory;
import java.util.List;

public interface AdminListTradesUseCase {
    List<TradeHistory> listAll(); // 최근 30일 전체 계좌
}
```

- [ ] **Step 8**: `AdminAnomaliesUseCase.java` 생성

```java
package com.kista.domain.port.in;

import com.kista.domain.model.AdminAnomalies;

public interface AdminAnomaliesUseCase {
    AdminAnomalies getAnomalies();
}
```

- [ ] **Step 9**: 컴파일 확인 — `AccountPersistenceAdapter`, `AuditLogPersistenceAdapter`, `TradeHistoryPersistenceAdapter`에서 미구현 메서드로 컴파일 실패 예상

```bash
cd kista-api && ./gradlew compileJava 2>&1 | grep "error:"
```

예상 에러: `AdminListAccountsUseCase is not abstract and does not override abstract method ...` — 다음 태스크에서 구현

- [ ] **Step 10**: 커밋

```bash
git add src/main/java/com/kista/domain/
git commit -m "feat(domain): add admin port interfaces and AdminAnomalies model for Phase 2C"
```

---

## T2. Persistence Adapter 구현

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/AccountPersistenceAdapter.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/AuditLogJpaRepository.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/AuditLogPersistenceAdapter.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/TradeHistoryJpaRepository.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/TradeHistoryPersistenceAdapter.java`

- [ ] **Step 1**: `AccountPersistenceAdapter.java`에 `findAll()` 구현 추가 (기존 `countAll()` 이후)

```java
@Override
public List<Account> findAll() {
    // 전체 계좌 조회 — toDomain()이 각 계좌별 strategy N+1 쿼리 실행 (관리자용 소량 허용)
    return jpaRepository.findAll().stream()
            .map(this::toDomain)
            .toList();
}
```

- [ ] **Step 2**: `AuditLogJpaRepository.java`에 정렬 쿼리 추가

```java
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;

interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {
    // 최신순 상위 100건 — Spring Data 메서드 쿼리로 페이지 없이 제한
    List<AuditLogEntity> findTop100ByOrderByCreatedAtDesc();
}
```

- [ ] **Step 3**: `AuditLogPersistenceAdapter.java`에 `findAll()` 구현 추가

```java
@Override
public List<AuditLog> findAll() {
    // 최신순 상위 100건 조회 후 도메인 변환
    return repo.findTop100ByOrderByCreatedAtDesc().stream()
            .map(this::toDomain)
            .toList();
}
```

- [ ] **Step 4**: `TradeHistoryJpaRepository.java`에 전체 기간 쿼리 추가

기존 인터페이스:
```java
interface TradeHistoryJpaRepository extends JpaRepository<TradeHistoryEntity, UUID> {
    List<TradeHistoryEntity> findByTradeDateBetweenAndSymbol(LocalDate from, LocalDate to, String symbol);
}
```

추가할 메서드:
```java
    // symbol 필터 없이 기간 내 전체 거래 조회 (관리자용)
    List<TradeHistoryEntity> findByTradeDateBetween(LocalDate from, LocalDate to);
```

- [ ] **Step 5**: `TradeHistoryPersistenceAdapter.java`에 `findAll(from, to)` 구현 추가

```java
@Override
public List<TradeHistory> findAll(LocalDate from, LocalDate to) {
    return repository.findByTradeDateBetween(from, to)
            .stream()
            .map(this::toDomain)
            .toList();
}
```

- [ ] **Step 6**: 컴파일 확인

```bash
cd kista-api && ./gradlew compileJava
```

Expected: BUILD SUCCESSFUL (adapter 미구현 에러 없어짐)

- [ ] **Step 7**: 커밋

```bash
git add src/main/java/com/kista/adapter/out/persistence/
git commit -m "feat(persistence): add findAll methods for admin account/audit/trade queries"
```

---

## T3. AdminAccountService + AdminAccountController + 테스트

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/service/AdminAccountService.java`
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminAccountController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminAccountControllerTest.java`

### 3-1: AdminAccountService

- [ ] **Step 1**: `AdminAccountService.java` 생성

```java
package com.kista.application.service;

import com.kista.domain.model.Account;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.out.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAccountService implements AdminListAccountsUseCase {

    private final AccountRepository accountRepository; // 전체 계좌 조회용

    @Override
    public List<Account> listAll() {
        return accountRepository.findAll();
    }
}
```

### 3-2: AdminAccountController (테스트 먼저)

- [ ] **Step 2**: `AdminAccountControllerTest.java` 먼저 작성

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.*;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminAccountController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminAccountControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminListAccountsUseCase listAccounts;
    @MockBean AdminListUsersUseCase listUsers;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listAccounts_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAccounts_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/accounts")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAccounts_adminRole_returns200() throws Exception {
        when(listAccounts.listAll()).thenReturn(List.of());
        when(listUsers.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/accounts")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
```

- [ ] **Step 3**: 테스트 실패 확인 (컨트롤러 없으므로 컨텍스트 로드 실패 예상)

```bash
cd kista-api && ./gradlew test --tests '*AdminAccountControllerTest'
```

- [ ] **Step 4**: `AdminAccountController.java` 구현

```java
package com.kista.adapter.in.web;

import com.kista.domain.model.Account;
import com.kista.domain.model.User;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin - Accounts", description = "관리자 계좌 현황 API")
@RestController
@RequestMapping("/api/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminListAccountsUseCase listAccounts;
    private final AdminListUsersUseCase listUsers; // ownerNickname 조회용 사용자 목록

    @GetMapping
    public List<AdminAccountResponse> listAccounts() {
        // 사용자 맵 빌드 (userId → User) — N+1 방지 일괄 조회
        Map<UUID, User> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        return listAccounts.listAll().stream()
                .map(a -> AdminAccountResponse.from(a, userMap.get(a.userId())))
                .toList();
    }

    // 계좌 목록 응답 DTO
    record AdminAccountResponse(
            UUID id,
            UUID userId,
            String ownerNickname,     // User.nickname
            String accountNoMasked,   // "****1234"
            String ticker,            // Ticker.name()
            String strategyType,      // StrategyType.name()
            String strategyStatus     // StrategyStatus.name()
    ) {
        static AdminAccountResponse from(Account a, User user) {
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            String masked = "****" + a.accountNo().substring(
                    Math.max(0, a.accountNo().length() - 4));
            return new AdminAccountResponse(
                    a.id(), a.userId(), nickname, masked,
                    a.ticker().name(), a.strategyType().name(), a.strategyStatus().name());
        }
    }
}
```

- [ ] **Step 5**: 테스트 통과 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminAccountControllerTest'
```

Expected: 3개 테스트 모두 GREEN

- [ ] **Step 6**: 커밋

```bash
git add src/
git commit -m "feat(admin): add AdminAccountService and AdminAccountController"
```

---

## T4. AdminAuditService + AdminAuditController + 테스트

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/service/AdminAuditService.java`
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminAuditController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminAuditControllerTest.java`

### 4-1: AdminAuditService

- [ ] **Step 1**: `AdminAuditService.java` 생성

```java
package com.kista.application.service;

import com.kista.domain.model.AuditLog;
import com.kista.domain.port.in.AdminListAuditLogsUseCase;
import com.kista.domain.port.out.AuditLogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAuditService implements AdminListAuditLogsUseCase {

    private final AuditLogPort auditLogPort;

    @Override
    public List<AuditLog> listAll() {
        return auditLogPort.findAll(); // 최신순 100건
    }
}
```

### 4-2: AdminAuditController (테스트 먼저)

- [ ] **Step 2**: `AdminAuditControllerTest.java` 작성

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.port.in.AdminListAuditLogsUseCase;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminAuditController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminAuditControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminListAuditLogsUseCase listAuditLogs;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listAuditLogs_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAuditLogs_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAuditLogs_adminRole_returns200() throws Exception {
        when(listAuditLogs.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/audit-logs")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
```

- [ ] **Step 3**: 테스트 실패 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminAuditControllerTest'
```

- [ ] **Step 4**: `AdminAuditController.java` 구현

```java
package com.kista.adapter.in.web;

import com.kista.domain.model.AuditLog;
import com.kista.domain.port.in.AdminListAuditLogsUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin - Audit", description = "관리자 감사 로그 API")
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminListAuditLogsUseCase listAuditLogs;

    @GetMapping
    public List<AdminAuditLogResponse> listAuditLogs() {
        return listAuditLogs.listAll().stream()
                .map(AdminAuditLogResponse::from)
                .toList();
    }

    // 감사 로그 응답 DTO — payload는 Map으로 nested JSON 직렬화
    record AdminAuditLogResponse(
            UUID id,
            UUID adminId,
            String action,      // "USER_APPROVE" | "USER_REJECT" | "USER_ROLE_CHANGE" | "USER_DELETE"
            String targetType,  // "USER"
            UUID targetId,
            Map<String, Object> payload, // JSONB → nested JSON (null 허용)
            Instant createdAt
    ) {
        static AdminAuditLogResponse from(AuditLog log) {
            return new AdminAuditLogResponse(
                    log.id(), log.adminId(), log.action(), log.targetType(),
                    log.targetId(), log.payload(), log.createdAt());
        }
    }
}
```

- [ ] **Step 5**: 테스트 통과 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminAuditControllerTest'
```

Expected: 3개 테스트 모두 GREEN

- [ ] **Step 6**: 커밋

```bash
git add src/
git commit -m "feat(admin): add AdminAuditService and AdminAuditController"
```

---

## T5. AdminTradeService + AdminTradeController + 테스트

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/service/AdminTradeService.java`
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminTradeController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminTradeControllerTest.java`

### 5-1: AdminTradeService

- [ ] **Step 1**: `AdminTradeService.java` 생성

```java
package com.kista.application.service;

import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.out.TradeHistoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTradeService implements AdminListTradesUseCase {

    private final TradeHistoryPort tradeHistoryPort;

    @Override
    public List<TradeHistory> listAll() {
        // 최근 30일 전체 계좌 거래 내역 조회 (symbol 필터 없음)
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        return tradeHistoryPort.findAll(from, to);
    }
}
```

### 5-2: AdminTradeController (테스트 먼저)

- [ ] **Step 2**: `AdminTradeControllerTest.java` 작성

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminTradeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminListTradesUseCase listTrades;
    @MockBean AdminListAccountsUseCase listAccounts;
    @MockBean AdminListUsersUseCase listUsers;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listTrades_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/trades"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listTrades_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/trades")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listTrades_adminRole_returns200() throws Exception {
        when(listTrades.listAll()).thenReturn(List.of());
        when(listAccounts.listAll()).thenReturn(List.of());
        when(listUsers.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/trades")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
```

- [ ] **Step 3**: 테스트 실패 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminTradeControllerTest'
```

- [ ] **Step 4**: `AdminTradeController.java` 구현

```java
package com.kista.adapter.in.web;

import com.kista.domain.model.Account;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.model.User;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListTradesUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin - Trades", description = "관리자 거래 내역 API")
@RestController
@RequestMapping("/api/admin/trades")
@RequiredArgsConstructor
public class AdminTradeController {

    private final AdminListTradesUseCase listTrades;
    private final AdminListAccountsUseCase listAccounts; // accountId → userId 매핑용
    private final AdminListUsersUseCase listUsers;       // userId → nickname 매핑용

    @GetMapping
    public List<AdminTradeResponse> listTrades() {
        // 일괄 조회로 N+1 방지
        Map<UUID, Account> accountMap = listAccounts.listAll().stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        Map<UUID, User> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
        return listTrades.listAll().stream()
                .map(t -> AdminTradeResponse.from(t, accountMap, userMap))
                .toList();
    }

    // 거래 내역 응답 DTO
    record AdminTradeResponse(
            UUID id,
            UUID userId,
            String ownerNickname,
            LocalDate tradeDate,
            String ticker,         // TradeHistory.symbol
            String direction,      // BUY | SELL
            String orderType,      // LOC | MOC | LIMIT
            int qty,
            BigDecimal price,
            String status          // PLACED | FILLED | FAILED
    ) {
        static AdminTradeResponse from(TradeHistory t, Map<UUID, Account> accountMap, Map<UUID, User> userMap) {
            Account account = t.accountId() != null ? accountMap.get(t.accountId()) : null;
            UUID userId = account != null ? account.userId() : null;
            User user = userId != null ? userMap.get(userId) : null;
            String nickname = user != null ? user.nickname() : "(알 수 없음)";
            return new AdminTradeResponse(
                    t.id(), userId, nickname, t.tradeDate(), t.symbol(),
                    t.direction().name(), t.orderType().name(),
                    t.qty(), t.price(), t.status().name());
        }
    }
}
```

- [ ] **Step 5**: 테스트 통과 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminTradeControllerTest'
```

Expected: 3개 테스트 모두 GREEN

- [ ] **Step 6**: 커밋

```bash
git add src/
git commit -m "feat(admin): add AdminTradeService and AdminTradeController"
```

---

## T6. AdminAnomaliesService + AdminAnomaliesController + 테스트

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/service/AdminAnomaliesService.java`
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminAnomaliesController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminAnomaliesControllerTest.java`

### 6-1: AdminAnomaliesService

- [ ] **Step 1**: `AdminAnomaliesService.java` 생성

```java
package com.kista.application.service;

import com.kista.domain.model.*;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.TradeHistoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnomaliesService implements AdminAnomaliesUseCase {

    private final TradeHistoryPort tradeHistoryPort;
    private final AccountRepository accountRepository;

    @Override
    public AdminAnomalies getAnomalies() {
        LocalDate today = LocalDate.now();

        // 1. 최근 30일 전체 거래 조회
        List<TradeHistory> trades30d = tradeHistoryPort.findAll(today.minusDays(30), today);

        // 2. FAILED 거래 필터링
        List<TradeHistory> failedTrades = trades30d.stream()
                .filter(t -> t.status() == Order.OrderStatus.FAILED)
                .toList();

        // 3. 전체 계좌 조회
        List<Account> allAccounts = accountRepository.findAll();

        // 4. PAUSED 전략 계좌
        List<Account> pausedAccounts = allAccounts.stream()
                .filter(a -> a.strategyStatus() == StrategyStatus.PAUSED)
                .toList();

        // 5. 비활성 계좌: ACTIVE 전략이지만 최근 7일 거래 없는 계좌
        List<TradeHistory> trades7d = tradeHistoryPort.findAll(today.minusDays(7), today);
        Set<UUID> activeAccountIds = trades7d.stream()
                .filter(t -> t.accountId() != null)
                .map(TradeHistory::accountId)
                .collect(Collectors.toSet());

        List<Account> inactiveAccounts = allAccounts.stream()
                .filter(a -> a.strategyStatus() == StrategyStatus.ACTIVE)
                .filter(a -> !activeAccountIds.contains(a.id()))
                .toList();

        return new AdminAnomalies(failedTrades, pausedAccounts, inactiveAccounts);
    }
}
```

### 6-2: AdminAnomaliesController (테스트 먼저)

- [ ] **Step 2**: `AdminAnomaliesControllerTest.java` 작성

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.AdminAnomalies;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminAnomaliesController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminAnomaliesControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminAnomaliesUseCase anomaliesUseCase;
    @MockBean AdminListAccountsUseCase listAccounts;
    @MockBean AdminListUsersUseCase listUsers;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void getAnomalies_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/anomalies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAnomalies_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/anomalies")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAnomalies_adminRole_returns200() throws Exception {
        when(anomaliesUseCase.getAnomalies())
                .thenReturn(new AdminAnomalies(List.of(), List.of(), List.of()));
        when(listAccounts.listAll()).thenReturn(List.of());
        when(listUsers.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/anomalies")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedTrades").isArray())
                .andExpect(jsonPath("$.pausedAccounts").isArray())
                .andExpect(jsonPath("$.inactiveAccounts").isArray());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
```

- [ ] **Step 3**: 테스트 실패 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminAnomaliesControllerTest'
```

- [ ] **Step 4**: `AdminAnomaliesController.java` 구현

```java
package com.kista.adapter.in.web;

import com.kista.domain.model.Account;
import com.kista.domain.model.AdminAnomalies;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.model.User;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Admin - Anomalies", description = "관리자 이상 징후 API")
@RestController
@RequestMapping("/api/admin/anomalies")
@RequiredArgsConstructor
public class AdminAnomaliesController {

    private final AdminAnomaliesUseCase anomaliesUseCase;
    private final AdminListAccountsUseCase listAccounts; // failedTrades accountId → userId 매핑용
    private final AdminListUsersUseCase listUsers;       // userId → nickname 매핑용

    @GetMapping
    public AdminAnomaliesResponse getAnomalies() {
        AdminAnomalies anomalies = anomaliesUseCase.getAnomalies();
        Map<UUID, Account> accountMap = listAccounts.listAll().stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
        Map<UUID, User> userMap = listUsers.listAll().stream()
                .collect(Collectors.toMap(User::id, Function.identity()));

        List<AdminTradeController.AdminTradeResponse> failedTrades = anomalies.failedTrades().stream()
                .map(t -> AdminTradeController.AdminTradeResponse.from(t, accountMap, userMap))
                .toList();
        List<AdminAccountController.AdminAccountResponse> pausedAccounts = anomalies.pausedAccounts().stream()
                .map(a -> AdminAccountController.AdminAccountResponse.from(a, userMap.get(a.userId())))
                .toList();
        List<AdminAccountController.AdminAccountResponse> inactiveAccounts = anomalies.inactiveAccounts().stream()
                .map(a -> AdminAccountController.AdminAccountResponse.from(a, userMap.get(a.userId())))
                .toList();

        return new AdminAnomaliesResponse(failedTrades, pausedAccounts, inactiveAccounts);
    }

    record AdminAnomaliesResponse(
            List<AdminTradeController.AdminTradeResponse> failedTrades,
            List<AdminAccountController.AdminAccountResponse> pausedAccounts,
            List<AdminAccountController.AdminAccountResponse> inactiveAccounts
    ) {}
}
```

**주의**: `AdminTradeController.AdminTradeResponse`와 `AdminAccountController.AdminAccountResponse`를 재사용하기 위해 해당 record들을 `package-private`(현재) 에서 접근 가능한지 확인 필요. 두 컨트롤러가 같은 패키지(`com.kista.adapter.in.web`)이므로 package-private record 접근 가능. 단, 외부 record 접근이 불가능한 경우 `AdminAnomaliesController` 내부에 별도 record 정의로 대체.

- [ ] **Step 5**: 컴파일 확인

```bash
cd kista-api && ./gradlew compileJava
```

컴파일 에러 발생 시 수정:
- `AdminTradeController.AdminTradeResponse`와 `AdminAccountController.AdminAccountResponse`가 `record`이면 같은 패키지에서 접근 가능
- 접근 불가 시 `AdminAnomaliesController` 내부에 동일 구조 record 직접 정의 (코드 중복 허용)

- [ ] **Step 6**: 테스트 통과 확인

```bash
cd kista-api && ./gradlew test --tests '*AdminAnomaliesControllerTest'
```

Expected: 3개 테스트 모두 GREEN

- [ ] **Step 7**: 전체 테스트 통과 확인

```bash
cd kista-api && ./gradlew test
```

Expected: 전체 GREEN (Docker 없는 persistence 통합 테스트 2건 제외)

- [ ] **Step 8**: ArchUnit 통과 확인

```bash
cd kista-api && ./gradlew test --tests 'com.kista.architecture.*'
```

- [ ] **Step 9**: 커밋

```bash
git add src/
git commit -m "feat(admin): add AdminAnomaliesService and AdminAnomaliesController"
```

---

## T7. kista-ui — 타입 + API + 4개 페이지 + 네비게이션

**Files:**
- Modify: `kista-ui/types/admin.ts`
- Modify: `kista-ui/lib/api/admin.ts`
- Create: `kista-ui/app/(admin)/admin/accounts/page.tsx`
- Create: `kista-ui/app/(admin)/admin/audit/page.tsx`
- Create: `kista-ui/app/(admin)/admin/trades/page.tsx`
- Create: `kista-ui/app/(admin)/admin/anomalies/page.tsx`
- Modify: `kista-ui/components/admin/AdminSidebar.tsx`
- Modify: `kista-ui/components/admin/AdminTopBar.tsx`

### 7-1: types/admin.ts 확장

- [ ] **Step 1**: `types/admin.ts` 끝에 4개 인터페이스 추가

```typescript
export interface AdminAccount {
  id: string
  userId: string
  ownerNickname: string
  accountNoMasked: string
  ticker: string
  strategyType: string
  strategyStatus: 'ACTIVE' | 'PAUSED'
}

export interface AdminAuditLog {
  id: string
  adminId: string
  action: 'USER_APPROVE' | 'USER_REJECT' | 'USER_ROLE_CHANGE' | 'USER_DELETE'
  targetType: string
  targetId: string
  payload: Record<string, unknown> | null
  createdAt: string
}

export interface AdminTrade {
  id: string
  userId: string | null
  ownerNickname: string
  tradeDate: string
  ticker: string
  direction: 'BUY' | 'SELL'
  orderType: 'LOC' | 'MOC' | 'LIMIT'
  qty: number
  price: string
  status: 'PLACED' | 'FILLED' | 'FAILED'
}

export interface AdminAnomalies {
  failedTrades: AdminTrade[]
  pausedAccounts: AdminAccount[]
  inactiveAccounts: AdminAccount[]
}
```

### 7-2: lib/api/admin.ts 확장

- [ ] **Step 2**: `lib/api/admin.ts` 끝에 4개 함수 추가

```typescript
import type { AdminAccount, AdminAuditLog, AdminTrade, AdminAnomalies } from '@/types/admin'

// 계좌 현황 목록 — Server Component 전용 (token 필요)
export async function listAdminAccounts(token: string): Promise<AdminAccount[]> {
  return apiFetch<AdminAccount[]>('/api/admin/accounts', { method: 'GET' }, token)
}

// 감사 로그 목록 — Server Component 전용
export async function listAdminAuditLogs(token: string): Promise<AdminAuditLog[]> {
  return apiFetch<AdminAuditLog[]>('/api/admin/audit-logs', { method: 'GET' }, token)
}

// 거래 내역 목록 (최근 30일) — Server Component 전용
export async function listAdminTrades(token: string): Promise<AdminTrade[]> {
  return apiFetch<AdminTrade[]>('/api/admin/trades', { method: 'GET' }, token)
}

// 이상 징후 조회 — Server Component 전용
export async function getAdminAnomalies(token: string): Promise<AdminAnomalies> {
  return apiFetch<AdminAnomalies>('/api/admin/anomalies', { method: 'GET' }, token)
}
```

- [ ] **Step 3**: `types/admin.ts`의 import 확인 — `AdminAccount`, `AdminAuditLog`, `AdminTrade`, `AdminAnomalies`가 `lib/api/admin.ts`에서 import되는지 확인. `lib/api/admin.ts` 파일 상단 기존 import와 충돌 없는지 확인.

### 7-3: accounts 페이지

- [ ] **Step 4**: `app/(admin)/admin/accounts/page.tsx` 생성

```tsx
import { getAuthToken } from '@/lib/auth/token'
import { listAdminAccounts } from '@/lib/api/admin'
import type { AdminAccount } from '@/types/admin'

export default async function AdminAccountsPage() {
  const token = await getAuthToken()
  const accounts: AdminAccount[] = token
    ? await listAdminAccounts(token).catch(() => [])
    : []

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">계좌 현황</h1>
        <p className="text-sm text-muted-foreground mt-1">전체 등록 계좌 목록</p>
      </div>

      {accounts.length === 0 ? (
        <div className="rounded-xl border border-border p-8 text-center text-sm text-muted-foreground">
          계좌 없음
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-semibold">소유자</th>
                <th className="px-4 py-3 text-left font-semibold">계좌번호</th>
                <th className="px-4 py-3 text-left font-semibold">종목</th>
                <th className="px-4 py-3 text-left font-semibold">전략</th>
                <th className="px-4 py-3 text-left font-semibold">상태</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {accounts.map((a) => (
                <tr key={a.id} className="hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3 font-medium">{a.ownerNickname}</td>
                  <td className="px-4 py-3 font-mono text-xs">{a.accountNoMasked}</td>
                  <td className="px-4 py-3">{a.ticker}</td>
                  <td className="px-4 py-3 text-muted-foreground">{a.strategyType}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                      a.strategyStatus === 'ACTIVE'
                        ? 'bg-emerald-100 text-emerald-700'
                        : 'bg-amber-100 text-amber-700'
                    }`}>
                      {a.strategyStatus}
                    </span>
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

### 7-4: audit 페이지

- [ ] **Step 5**: `app/(admin)/admin/audit/page.tsx` 생성

```tsx
import { getAuthToken } from '@/lib/auth/token'
import { listAdminAuditLogs } from '@/lib/api/admin'
import type { AdminAuditLog } from '@/types/admin'

const ACTION_BADGE: Record<AdminAuditLog['action'], string> = {
  USER_APPROVE:     'bg-emerald-100 text-emerald-700',
  USER_REJECT:      'bg-slate-100 text-slate-600',
  USER_ROLE_CHANGE: 'bg-rose-100 text-rose-700',
  USER_DELETE:      'bg-red-100 text-red-700',
}

export default async function AdminAuditPage() {
  const token = await getAuthToken()
  const logs: AdminAuditLog[] = token
    ? await listAdminAuditLogs(token).catch(() => [])
    : []

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">감사 로그</h1>
        <p className="text-sm text-muted-foreground mt-1">관리자 액션 이력 (최신 100건)</p>
      </div>

      {logs.length === 0 ? (
        <div className="rounded-xl border border-border p-8 text-center text-sm text-muted-foreground">
          감사 로그 없음
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-semibold">날짜</th>
                <th className="px-4 py-3 text-left font-semibold">관리자 ID</th>
                <th className="px-4 py-3 text-left font-semibold">액션</th>
                <th className="px-4 py-3 text-left font-semibold">대상 ID</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {logs.map((log) => (
                <tr key={log.id} className="hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3 text-xs text-muted-foreground">
                    {new Date(log.createdAt).toLocaleString('ko-KR')}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{log.adminId.slice(0, 8)}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${ACTION_BADGE[log.action] ?? 'bg-muted text-muted-foreground'}`}>
                      {log.action}
                    </span>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{log.targetId?.slice(0, 8) ?? '-'}</td>
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

### 7-5: trades 페이지

- [ ] **Step 6**: `app/(admin)/admin/trades/page.tsx` 생성

```tsx
import { getAuthToken } from '@/lib/auth/token'
import { listAdminTrades } from '@/lib/api/admin'
import type { AdminTrade } from '@/types/admin'

export default async function AdminTradesPage() {
  const token = await getAuthToken()
  const trades: AdminTrade[] = token
    ? await listAdminTrades(token).catch(() => [])
    : []

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">거래 내역</h1>
        <p className="text-sm text-muted-foreground mt-1">최근 30일 전체 계좌 거래 기록</p>
      </div>

      {trades.length === 0 ? (
        <div className="rounded-xl border border-border p-8 text-center text-sm text-muted-foreground">
          거래 내역 없음
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-semibold">날짜</th>
                <th className="px-4 py-3 text-left font-semibold">소유자</th>
                <th className="px-4 py-3 text-left font-semibold">종목</th>
                <th className="px-4 py-3 text-left font-semibold">방향</th>
                <th className="px-4 py-3 text-left font-semibold">유형</th>
                <th className="px-4 py-3 text-right font-semibold">수량</th>
                <th className="px-4 py-3 text-left font-semibold">상태</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {trades.map((t) => (
                <tr key={t.id} className="hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3 text-xs text-muted-foreground">{t.tradeDate}</td>
                  <td className="px-4 py-3 font-medium">{t.ownerNickname}</td>
                  <td className="px-4 py-3">{t.ticker}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                      t.direction === 'BUY'
                        ? 'bg-emerald-100 text-emerald-700'
                        : 'bg-slate-100 text-slate-600'
                    }`}>
                      {t.direction}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs text-muted-foreground">{t.orderType}</td>
                  <td className="px-4 py-3 text-right">{t.qty}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                      t.status === 'FILLED' ? 'bg-emerald-100 text-emerald-700'
                      : t.status === 'FAILED' ? 'bg-rose-100 text-rose-700'
                      : 'bg-slate-100 text-slate-600'
                    }`}>
                      {t.status}
                    </span>
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

### 7-6: anomalies 페이지

- [ ] **Step 7**: `app/(admin)/admin/anomalies/page.tsx` 생성

```tsx
import { getAuthToken } from '@/lib/auth/token'
import { getAdminAnomalies } from '@/lib/api/admin'
import type { AdminAnomalies, AdminTrade, AdminAccount } from '@/types/admin'
import { CheckCircle2 } from 'lucide-react'

const EMPTY_ANOMALIES: AdminAnomalies = { failedTrades: [], pausedAccounts: [], inactiveAccounts: [] }

export default async function AdminAnomaliesPage() {
  const token = await getAuthToken()
  const anomalies: AdminAnomalies = token
    ? await getAdminAnomalies(token).catch(() => EMPTY_ANOMALIES)
    : EMPTY_ANOMALIES

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-extrabold">이상 징후</h1>
        <p className="text-sm text-muted-foreground mt-1">체결 실패, 전략 중지, 장기 미거래 감지</p>
      </div>

      <div className="flex flex-col gap-6">
        {/* 체결 실패 */}
        <AnomalySection title="체결 실패" count={anomalies.failedTrades.length} accentColor="rose">
          {anomalies.failedTrades.length === 0 ? (
            <EmptyOk />
          ) : (
            <div className="divide-y divide-border">
              {anomalies.failedTrades.map((t) => (
                <div key={t.id} className="flex items-center justify-between px-4 py-3">
                  <div>
                    <p className="text-sm font-semibold">{t.ownerNickname}</p>
                    <p className="text-xs text-muted-foreground">{t.tradeDate} · {t.ticker}</p>
                  </div>
                  <p className="text-sm font-mono">${t.price}</p>
                </div>
              ))}
            </div>
          )}
        </AnomalySection>

        {/* 전략 중지 계좌 */}
        <AnomalySection title="전략 중지 계좌" count={anomalies.pausedAccounts.length} accentColor="amber">
          {anomalies.pausedAccounts.length === 0 ? (
            <EmptyOk />
          ) : (
            <div className="divide-y divide-border">
              {anomalies.pausedAccounts.map((a) => (
                <div key={a.id} className="flex items-center justify-between px-4 py-3">
                  <p className="text-sm font-semibold">{a.ownerNickname}</p>
                  <p className="text-xs font-mono text-muted-foreground">{a.accountNoMasked}</p>
                </div>
              ))}
            </div>
          )}
        </AnomalySection>

        {/* 7일 미거래 계좌 */}
        <AnomalySection title="7일 미거래 계좌" count={anomalies.inactiveAccounts.length} accentColor="slate">
          {anomalies.inactiveAccounts.length === 0 ? (
            <EmptyOk />
          ) : (
            <div className="divide-y divide-border">
              {anomalies.inactiveAccounts.map((a) => (
                <div key={a.id} className="flex items-center justify-between px-4 py-3">
                  <p className="text-sm font-semibold">{a.ownerNickname}</p>
                  <p className="text-xs font-mono text-muted-foreground">{a.accountNoMasked}</p>
                </div>
              ))}
            </div>
          )}
        </AnomalySection>
      </div>
    </div>
  )
}

function AnomalySection({
  title, count, accentColor, children
}: {
  title: string
  count: number
  accentColor: 'rose' | 'amber' | 'slate'
  children: React.ReactNode
}) {
  const badgeClass = count > 0
    ? accentColor === 'rose' ? 'bg-rose-100 text-rose-700'
      : accentColor === 'amber' ? 'bg-amber-100 text-amber-700'
      : 'bg-slate-100 text-slate-600'
    : 'bg-emerald-100 text-emerald-700'

  return (
    <section className="rounded-xl border border-border overflow-hidden">
      <div className="px-4 py-3 bg-muted/30 border-b border-border flex items-center gap-2">
        <h2 className="text-sm font-bold">{title}</h2>
        <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${badgeClass}`}>
          {count}건
        </span>
      </div>
      {children}
    </section>
  )
}

function EmptyOk() {
  return (
    <div className="flex items-center gap-2 px-4 py-4 text-sm text-emerald-600">
      <CheckCircle2 className="size-4" />
      이상 없음
    </div>
  )
}
```

### 7-7: AdminSidebar 네비게이션 확장

- [ ] **Step 8**: `components/admin/AdminSidebar.tsx` 수정

기존 import에 아이콘 추가:
```typescript
import { LayoutDashboard, Clock, Users, LogOut, CreditCard, TrendingUp, FileText, AlertTriangle } from 'lucide-react'
```

기존 `NAV_ITEMS` 배열에 4개 항목 추가:
```typescript
const NAV_ITEMS = [
  { href: '/admin',            label: 'Overview',    icon: LayoutDashboard, exact: true },
  { href: '/admin/pending',    label: '승인 대기',   icon: Clock },
  { href: '/admin/users',      label: '사용자',      icon: Users },
  { href: '/admin/accounts',   label: '계좌',        icon: CreditCard },
  { href: '/admin/trades',     label: '거래 내역',   icon: TrendingUp },
  { href: '/admin/audit',      label: '감사 로그',   icon: FileText },
  { href: '/admin/anomalies',  label: '이상 징후',   icon: AlertTriangle },
]
```

### 7-8: AdminTopBar 네비게이션 확장

- [ ] **Step 9**: `components/admin/AdminTopBar.tsx` 수정

기존 import에 아이콘 추가:
```typescript
import { LayoutDashboard, Clock, Users, CreditCard, TrendingUp, FileText, AlertTriangle } from 'lucide-react'
```

기존 `NAV_ITEMS` 배열에 4개 항목 추가 (AdminSidebar와 동일):
```typescript
const NAV_ITEMS = [
  { href: '/admin',            label: 'Overview',    icon: LayoutDashboard, exact: true },
  { href: '/admin/pending',    label: '승인 대기',   icon: Clock },
  { href: '/admin/users',      label: '사용자',      icon: Users },
  { href: '/admin/accounts',   label: '계좌',        icon: CreditCard },
  { href: '/admin/trades',     label: '거래 내역',   icon: TrendingUp },
  { href: '/admin/audit',      label: '감사 로그',   icon: FileText },
  { href: '/admin/anomalies',  label: '이상 징후',   icon: AlertTriangle },
]
```

- [ ] **Step 10**: TypeScript 타입 검사

```bash
cd kista-ui && npm run typecheck
```

Expected: 에러 없음

- [ ] **Step 11**: 빌드 확인

```bash
cd kista-ui && npm run build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 12**: 커밋

```bash
cd kista-ui
git add types/admin.ts lib/api/admin.ts app/\(admin\)/ components/admin/
git commit -m "feat(admin-ui): add 4 admin pages (accounts/trades/audit/anomalies) and extend navigation"
```

---

## T8. 최종 검증

- [ ] **Step 1**: kista-api 전체 테스트

```bash
cd kista-api && ./gradlew test
```

Expected: 전체 GREEN

- [ ] **Step 2**: ArchUnit 확인

```bash
cd kista-api && ./gradlew test --tests 'com.kista.architecture.*'
```

- [ ] **Step 3**: kista-ui 타입 검사 + 빌드

```bash
cd kista-ui && npm run typecheck && npm run build
```

- [ ] **Step 4**: 로컬 기동 + 수동 확인 (Docker postgres 필요)

```bash
# kista-api 기동
cd kista-api
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'

# ADMIN 토큰 발급
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)

# 4개 엔드포인트 확인
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/accounts | jq .
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/audit-logs | jq .
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/trades | jq .
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/anomalies | jq .

# USER 토큰 → 403 확인
USER_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -si -H "Authorization: Bearer $USER_TOKEN" localhost:8080/api/admin/accounts | head -1
# Expected: HTTP/1.1 403

# 비인증 → 401 확인
curl -si localhost:8080/api/admin/accounts | head -1
# Expected: HTTP/1.1 401
```

- [ ] **Step 5**: kista-ui 개발 서버 기동 후 브라우저 확인

```bash
cd kista-ui && npm run dev
```

- ADMIN 토큰으로 로그인 후 `/admin/accounts`, `/admin/audit`, `/admin/trades`, `/admin/anomalies` 페이지 각각 접근
- AdminSidebar 7개 항목 표시 확인 (lg 화면)
- AdminTopBar 7개 탭 표시 확인 (모바일)
- 각 페이지 빈 상태 메시지 확인

---

## 완료 기준 체크리스트

- [ ] `GET /api/admin/accounts` → 전체 계좌 목록 반환 (ownerNickname, accountNoMasked 포함)
- [ ] `GET /api/admin/audit-logs` → 최신순 100건 반환
- [ ] `GET /api/admin/trades` → 최근 30일 전체 거래 반환 (ownerNickname 포함)
- [ ] `GET /api/admin/anomalies` → failedTrades/pausedAccounts/inactiveAccounts 3종 반환
- [ ] 비인증 → 401, USER 역할 → 403, ADMIN → 200
- [ ] kista-ui 4개 페이지 렌더링 (빈 상태 메시지 포함)
- [ ] AdminSidebar/TopBar 7개 항목 표시
- [ ] `./gradlew test` 전체 통과
- [ ] `npm run typecheck` + `npm run build` 전체 통과
