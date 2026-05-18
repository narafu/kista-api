# KISTA Phase 2A — 관리자 권한 토대 (Backend only) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kista-api에 ADMIN 권한 인프라(role 컬럼, JWT role claim, Security guard, audit_logs)를 구축하여 Phase 2B(admin 엔드포인트/UI)가 안전하게 얹힐 수 있는 토대 완성.

**Architecture:** PostgreSQL 네이티브 ENUM `user_role` + Hexagonal 구조 유지 — `UserRole` domain enum, `User` record에 role 필드 추가, `JwtIssuerService.issue(uuid, role)` claim 확장, `JwtAuthFilter`가 `ROLE_*` authorities 주입, `SecurityConfig`가 `/api/admin/**`를 `hasRole("ADMIN")`으로 가드. 첫 ADMIN seed는 환경변수 `ADMIN_KAKAO_IDS` 기반 자동 promote (prod 운영 가능). 감사로그는 별도 `audit_logs` 테이블 + JSONB payload + `AuditLogPort`.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Security 6, PostgreSQL 16 (네이티브 ENUM + JSONB), Hibernate 6 (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`), Flyway V17, Jackson, Nimbus JOSE JWT (ES256).

---

## Context

Phase 1 완료 상태: 사용자 화면 11종 + Trade SSE + StatisticsController normalizer가 정합되어 일반 사용자 흐름은 완전 동작. **관리자 권한은 0% 구현**:
- `User` record에 role 필드 없음, `JwtAuthFilter`는 authorities `List.of()` 비워둠
- `SecurityConfig`에 `hasRole` 매핑 없음, `audit_logs` 테이블 부재
- 디자인 핸드오프 admin 9 데스크탑 + 3 모바일은 JSX 컴포넌트로 존재하나 kista-ui 이식 미착수

Phase 2 전체(권한 토대 → 백엔드 API → UI 9+3 → 잔여 정리)를 하나의 plan으로 묶으면 50+ task, PR 거대화 위험. 사용자 결정으로 **Phase 2A 권한 토대만** 이번 plan에서 처리. Phase 2B(admin API + Overview/Pending/Users UI 3종), Phase 2C(나머지 5 데스크탑 + 모바일 3), Phase 2D(텔레그램 callback 제거 / 회원탈퇴 백엔드 / AccountEdit 변경이력 / test-connection / 옵티미스틱)는 후속 plan으로 분리.

핵심 위험: `User` record 필드 추가는 architecture.md "동시 수정 필요 파일 쌍" 표 마지막 행에 7곳 전파 표시됨 — `UserEntity` + `UserPersistenceAdapter` + `UserServiceTest` + `TelegramAdapterTest` + `TradingSchedulerTest` + `AccountServiceTest` + `TradingServiceTest`. JWT claim/authorities 변경은 인증 전체에 영향.

---

## 작업 순서 (의존성)

```
P0 (순차)
 ├─ T0. plan 파일을 kista-api 레포로 이동
 ├─ T1. Flyway V17: user_role + users.role + audit_logs 테이블
 ├─ T2. UserRole enum (domain/model)
 └─ T3. AuditLog record + AuditLogEntity + Repository

P1 (T1-T3 의존, 병렬 가능)
 ├─ T4. User record + UserEntity role 필드 + UserPersistenceAdapter 매핑 + 호출처 7곳
 └─ T5. AuditLogPort + AuditLogPersistenceAdapter

P2 (T4-T5 의존)
 ├─ T6. AdminBootstrapProperties + UserService.register() ADMIN seed 로직
 ├─ T7. JwtIssuerService.issue(UUID, UserRole) 시그니처 변경 + 호출처 전파
 └─ T8. JwtAuthFilter: role claim → SimpleGrantedAuthority

P3 (T6-T8 의존)
 ├─ T9. SecurityConfig: /api/admin/** hasRole("ADMIN")
 └─ T10. DevAuthController: /dev-admin-token

P4 (마지막, P3 의존)
 ├─ T11. 환경변수/Docker/문서 (application.yml + .env.example + docker-compose.yml + CLAUDE.md)
 ├─ T12. /api/admin/_ping 더미 컨트롤러로 가드 통합 테스트
 └─ T13. 전체 검증 (ArchUnit + ./gradlew test + 로컬 기동 + 토큰별 가드 확인)
```

---

## T0. Plan 파일을 실행 위치로 이동

**Files:**
- Create: `/Users/phs/workspace/kista/kista-api/docs/superpowers/plans/2026-05-17-kista-phase2a-admin-foundation.md`

- [ ] **Step 1**: 이 plan 파일 내용을 `kista-api/docs/superpowers/plans/2026-05-17-kista-phase2a-admin-foundation.md`로 복사
- [ ] **Step 2**: `mkdir -p docs/superpowers/plans` 필요 시 생성
- [ ] **Step 3**: 커밋: `docs: add Phase 2A admin foundation implementation plan`

---

## T1. Flyway V17 — user_role ENUM + users.role + audit_logs

**Files:**
- Create: `kista-api/src/main/resources/db/migration/V17__add_role_and_audit_logs.sql`

**Pattern reference:** `V6__create_users.sql` (`CREATE TYPE user_status AS ENUM`), `V16__fix_account_fk_cascades.sql` (FK ON DELETE CASCADE 패턴).

- [ ] **Step 1**: V17 SQL 작성

```sql
CREATE TYPE user_role AS ENUM ('USER', 'ADMIN');

ALTER TABLE users
  ADD COLUMN role user_role NOT NULL DEFAULT 'USER';

CREATE TABLE audit_logs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  action       VARCHAR(64) NOT NULL,
  target_type  VARCHAR(64),
  target_id    UUID,
  payload      JSONB,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_admin_created ON audit_logs(admin_id, created_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs(target_type, target_id);
```

- [ ] **Step 2**: 로컬 postgres 기동 후 마이그레이션 검증: `./gradlew flywayMigrate` 또는 `./gradlew bootRun --args='--spring.profiles.active=local'` (DB schema validate 통과)
- [ ] **Step 3**: `docker exec kista-api-postgres-1 psql -U kista -d kistadb -c "\d users"` 로 role 컬럼 + 기본값 확인
- [ ] **Step 4**: 커밋

---

## T2. UserRole enum

**Files:**
- Create: `kista-api/src/main/java/com/kista/domain/model/UserRole.java`

- [ ] **Step 1**: enum 작성 (UserStatus.java 패턴 동일)

```java
package com.kista.domain.model;

public enum UserRole {
    USER,
    ADMIN
}
```

- [ ] **Step 2**: `./gradlew compileJava` 통과 확인
- [ ] **Step 3**: 단일 파일 커밋

---

## T3. AuditLog record + Entity + JpaRepository

**Files:**
- Create: `kista-api/src/main/java/com/kista/domain/model/AuditLog.java`
- Create: `kista-api/src/main/java/com/kista/adapter/out/persistence/AuditLogEntity.java`
- Create: `kista-api/src/main/java/com/kista/adapter/out/persistence/AuditLogJpaRepository.java`

**Pattern reference:** `UserEntity.java` (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` 등) — domain.model은 외부 의존 0 원칙 준수 (`@Schema` 금지, jpa 어노테이션 금지).

- [ ] **Step 1**: `AuditLog` domain record

```java
package com.kista.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLog(
    UUID id,
    UUID adminId,
    String action,
    String targetType,
    UUID targetId,
    Map<String, Object> payload,
    Instant createdAt
) {}
```

- [ ] **Step 2**: `AuditLogEntity` JPA (JSONB는 `String` + `@JdbcTypeCode(SqlTypes.JSON)` 매핑)

```java
@Entity
@Table(name = "audit_logs")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false)
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
```

- [ ] **Step 3**: `AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID>` 빈 인터페이스
- [ ] **Step 4**: `./gradlew compileJava` 통과
- [ ] **Step 5**: 단일 커밋

---

## T4. User record + UserEntity role 필드 + 호출처 7곳 전파

**Files:**
- Modify: `kista-api/src/main/java/com/kista/domain/model/User.java` (마지막 필드로 `UserRole role` 추가)
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/UserEntity.java`
- Modify: `kista-api/src/main/java/com/kista/adapter/out/persistence/UserPersistenceAdapter.java`
- Modify (테스트 mock 갱신): `UserServiceTest`, `TelegramAdapterTest`, `TradingSchedulerTest`, `AccountServiceTest`, `TradingServiceTest`, 기타 `new User(...)` 호출 발견 시 모두

**Critical reference:** `kista-api/docs/claude/architecture.md` "동시 수정 필요 파일 쌍" 표 마지막 행 — 누락 시 컴파일 실패.

- [ ] **Step 1**: `User` record에 `UserRole role` 필드 추가 (status 다음, telegram 필드들 앞 — 호출 순서를 직관적으로)

```java
public record User(
    UUID id,
    Long kakaoId,
    String nickname,
    String profileImageUrl,
    UserStatus status,
    UserRole role,
    String telegramBotToken,
    String telegramChatId,
    String rejectionReason,
    Instant lastReappliedAt,
    Instant createdAt,
    Instant updatedAt
) {}
```

- [ ] **Step 2**: `UserEntity`에 role 컬럼 추가 (status 패턴 복사)

```java
@JdbcTypeCode(SqlTypes.NAMED_ENUM)
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 10)
private UserRole role;
```

- [ ] **Step 3**: `UserPersistenceAdapter`의 `toDomain` / `toEntity` 또는 인라인 매핑에 role 포함
- [ ] **Step 4**: `grep -rn "new User(" src/` 로 호출처 전수 조사. 각 호출에 `UserRole.USER` (테스트 디폴트) 또는 `UserRole.ADMIN` (의도된 경우) 삽입

**예시 (테스트):**
```java
new User(uuid, 123L, "tester", null, UserStatus.ACTIVE, UserRole.USER,
         null, null, null, null, Instant.now(), Instant.now())
```

- [ ] **Step 5**: `./gradlew compileJava compileTestJava` 통과 — record 필드 추가 시 `grep`보다 컴파일이 신뢰성 높음 (testing.md 명시)
- [ ] **Step 6**: `./gradlew test --tests 'com.kista.adapter.out.persistence.UserPersistenceAdapterTest'` (있다면) 또는 `./gradlew test --tests 'com.kista.application.service.UserServiceTest'` 통과
- [ ] **Step 7**: 커밋: `feat(domain): add UserRole to User record and propagate to entity/tests`

---

## T5. AuditLogPort + AuditLogPersistenceAdapter

**Files:**
- Create: `kista-api/src/main/java/com/kista/domain/port/out/AuditLogPort.java`
- Create: `kista-api/src/main/java/com/kista/adapter/out/persistence/AuditLogPersistenceAdapter.java`

**Pattern reference:** 다른 `*PersistenceAdapter` (생성자 주입, package-private 클래스 가능). Jackson ObjectMapper는 Spring Boot가 자동 제공.

- [ ] **Step 1**: `AuditLogPort` 인터페이스

```java
package com.kista.domain.port.out;

import com.kista.domain.model.AuditLog;
import java.util.Map;
import java.util.UUID;

public interface AuditLogPort {
    void log(UUID adminId, String action, String targetType, UUID targetId, Map<String, Object> payload);
    AuditLog findById(UUID id);
}
```

- [ ] **Step 2**: `AuditLogPersistenceAdapter` 구현 (ObjectMapper로 Map → JSON String)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogPersistenceAdapter implements AuditLogPort {
    private final AuditLogJpaRepository repo;
    private final ObjectMapper objectMapper;

    @Override
    public void log(UUID adminId, String action, String targetType, UUID targetId, Map<String, Object> payload) {
        AuditLogEntity e = new AuditLogEntity();
        e.setAdminId(adminId);
        e.setAction(action);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        try {
            e.setPayload(payload == null ? null : objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("audit payload serialization failed", ex);
        }
        repo.save(e);
    }

    @Override
    public AuditLog findById(UUID id) {
        return repo.findById(id).map(this::toDomain).orElseThrow();
    }

    private AuditLog toDomain(AuditLogEntity e) {
        Map<String, Object> p = null;
        if (e.getPayload() != null) {
            try {
                p = objectMapper.readValue(e.getPayload(), new TypeReference<>() {});
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("audit payload deserialization failed", ex);
            }
        }
        return new AuditLog(e.getId(), e.getAdminId(), e.getAction(), e.getTargetType(),
                            e.getTargetId(), p, e.getCreatedAt());
    }
}
```

- [ ] **Step 3**: ArchUnit 통과 검증: `./gradlew test --tests 'com.kista.architecture.*'`
- [ ] **Step 4**: 커밋

---

## T6. AdminBootstrapProperties + UserService.register() ADMIN seed

**Files:**
- Create: `kista-api/src/main/java/com/kista/application/config/AdminBootstrapProperties.java`
- Modify: `kista-api/src/main/java/com/kista/application/service/UserService.java` (`register()` 메서드)
- Modify: `kista-api/src/main/resources/application.yml` (T11에서 함께)

**Why this approach:** 환경변수 `ADMIN_KAKAO_IDS` 기반 자동 promote는 (1) prod에서 운영 가능, (2) 신규 가입과 기존 로그인 모두 idempotent하게 ADMIN 보장, (3) DB seed migration보다 안전(롤백 쉬움).

- [ ] **Step 1**: `AdminBootstrapProperties`

```java
@ConfigurationProperties(prefix = "admin")
public record AdminBootstrapProperties(List<Long> kakaoIds) {
    public AdminBootstrapProperties {
        kakaoIds = kakaoIds == null ? List.of() : List.copyOf(kakaoIds);
    }
    public boolean isAdmin(Long kakaoId) {
        return kakaoIds.contains(kakaoId);
    }
}
```

- [ ] **Step 2**: `KistaApiApplication` 또는 별도 `@Configuration`에 `@EnableConfigurationProperties(AdminBootstrapProperties.class)` 추가
- [ ] **Step 3**: `UserService` 생성자 의존성 추가 (`AdminBootstrapProperties bootstrapProps`)
- [ ] **Step 4**: `register()` 메서드: ADMIN seed 분기

```java
boolean isAdminSeed = bootstrapProps.isAdmin(kakaoId);
UserRole role = isAdminSeed ? UserRole.ADMIN : UserRole.USER;
UserStatus status = isAdminSeed ? UserStatus.ACTIVE : UserStatus.PENDING;
User newUser = new User(null, kakaoId, nickname, profileImageUrl, status, role,
                         null, null, null, null, null, null);
```

- [ ] **Step 5**: `KakaoLoginService.login()` 기존 사용자 분기에 promote 로직 추가 (idempotent)

```java
if (existing.role() != UserRole.ADMIN && bootstrapProps.isAdmin(existing.kakaoId())) {
    User promoted = /* record copy with role=ADMIN, status=ACTIVE */;
    userRepository.save(promoted);
    existing = promoted;
}
```

- [ ] **Step 6**: `UserServiceTest` — `AdminBootstrapProperties` `@Mock` 추가 + seed 분기 테스트 작성 (`kakaoId가 admin이면 register()가 ACTIVE+ADMIN 반환`)
- [ ] **Step 7**: `./gradlew test --tests 'com.kista.application.service.UserServiceTest'` 통과
- [ ] **Step 8**: 커밋

---

## T7. JwtIssuerService.issue(UUID, UserRole) + 호출처 전파

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/security/JwtIssuerService.java`
- Modify: `kista-api/src/main/java/com/kista/application/service/KakaoLoginService.java` (issue 호출)
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/DevAuthController.java` (issue 호출)
- Modify: 관련 테스트

- [ ] **Step 1**: `JwtIssuerService.issue()` 시그니처 변경

```java
public String issue(UUID userId, UserRole role) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(userId.toString())
        .claim("role", role.name())
        .issueTime(new Date())
        .expirationTime(new Date(System.currentTimeMillis() + TTL_MS))
        .build();
    // ... 기존 서명 로직
}
```

- [ ] **Step 2**: `KakaoLoginService.login()` 반환 직전 `jwtIssuerService.issue(user.id(), user.role())` 호출
- [ ] **Step 3**: `DevAuthController./dev-token`도 동일하게 user.role() 전달 (기본 테스트 유저는 USER)
- [ ] **Step 4**: `JwtIssuerServiceTest` (있다면) 또는 새 테스트로 role claim 포함 검증

```java
@Test
void issue_includesRoleClaim() throws Exception {
    String token = jwtIssuer.issue(uuid, UserRole.ADMIN);
    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("ADMIN");
}
```

- [ ] **Step 5**: `./gradlew test` 관련 테스트 통과
- [ ] **Step 6**: 커밋

---

## T8. JwtAuthFilter — role claim → SimpleGrantedAuthority

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/security/JwtAuthFilter.java`

- [ ] **Step 1**: 기존 `List.of()` 빈 authorities를 role claim 기반으로 교체

```java
String roleClaim = jwt.getClaimAsString("role");
List<SimpleGrantedAuthority> authorities = roleClaim == null
    ? List.of()
    : List.of(new SimpleGrantedAuthority("ROLE_" + roleClaim));
UsernamePasswordAuthenticationToken auth =
    new UsernamePasswordAuthenticationToken(userId, null, authorities);
SecurityContextHolder.getContext().setAuthentication(auth);
```

- [ ] **Step 2**: `JwtAuthFilterTest` (있다면) — ADMIN/USER 토큰별 authorities 검증 추가
- [ ] **Step 3**: 기존 401/403 catch 절(`Exception`) 유지 — constraints.md "JwtAuthFilter catch 절은 Exception으로" 준수
- [ ] **Step 4**: 커밋

---

## T9. SecurityConfig — /api/admin/** hasRole("ADMIN")

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/security/SecurityConfig.java`

- [ ] **Step 1**: authorizeHttpRequests 매핑 추가 (permitAll 다음, `.anyRequest().authenticated()` 앞)

```java
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

- [ ] **Step 2**: 미설정 시 403이 되도록 `.exceptionHandling()` + `authenticationEntryPoint` 유지 (constraints.md 명시)
- [ ] **Step 3**: 커밋

---

## T10. DevAuthController — /dev-admin-token

**Files:**
- Modify: `kista-api/src/main/java/com/kista/adapter/in/web/DevAuthController.java`

- [ ] **Step 1**: 새 엔드포인트 추가

```java
private static final UUID DEV_ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

@PostMapping("/dev-admin-token")
public TokenResponse devAdminToken() {
    User admin = userRepository.findById(DEV_ADMIN_UUID).orElseGet(() -> {
        User newAdmin = new User(DEV_ADMIN_UUID, 0L, "dev-admin", null,
            UserStatus.ACTIVE, UserRole.ADMIN, null, null, null, null, null, null);
        return userRepository.save(newAdmin);
    });
    // 기존 사용자가 role 안 박힌 상태로 있다면 promote (idempotent)
    if (admin.role() != UserRole.ADMIN) {
        admin = userRepository.save(/* record copy with role=ADMIN */);
    }
    String token = jwtIssuerService.issue(admin.id(), UserRole.ADMIN);
    return new TokenResponse(token, "bearer", 604800);
}
```

- [ ] **Step 2**: DevAuthControllerTest에 ADMIN 토큰 발급 + role claim 검증 추가
- [ ] **Step 3**: 커밋

---

## T11. 환경변수 / Docker / 문서

**Files:**
- Modify: `kista-api/src/main/resources/application.yml`
- Modify: `kista-api/.env.example`
- Modify: `kista-api/docker-compose.yml`
- Modify: `kista-api/CLAUDE.md` 또는 `kista-api/docs/claude/constraints.md`

- [ ] **Step 1**: `application.yml`에 admin 섹션 추가

```yaml
admin:
  kakao-ids: ${ADMIN_KAKAO_IDS:}
```

(쉼표 구분 List<Long> 자동 바인딩)

- [ ] **Step 2**: `.env.example`에 `ADMIN_KAKAO_IDS=` 추가 (주석: "쉼표 구분 카카오 ID 목록, 자동 ADMIN 승격")
- [ ] **Step 3**: `docker-compose.yml`의 app 서비스 `environment:` 섹션에 `ADMIN_KAKAO_IDS=${ADMIN_KAKAO_IDS}` 추가 (docker-infra.md "환경변수 추가/제거" 동시 수정 쌍 준수)
- [ ] **Step 4**: Render 환경변수 목록(`docs/claude/commands.md`)에 ADMIN_KAKAO_IDS 추가 (필수 15→16개)
- [ ] **Step 5**: `kista-api/docs/claude/constraints.md`에 새 섹션 추가:

```markdown
### ADMIN 권한 관리
- `users.role` PostgreSQL 네이티브 ENUM (`user_role`: USER/ADMIN) — V17
- ADMIN seed: 환경변수 `ADMIN_KAKAO_IDS` (쉼표 구분) — `UserService.register()` / `KakaoLoginService.login()`에서 idempotent promote
- JWT claim: `"role": "ADMIN"` — `JwtIssuerService.issue(uuid, role)`
- `JwtAuthFilter`: `ROLE_USER` / `ROLE_ADMIN` authorities 자동 부여
- `/api/admin/**` → `hasRole("ADMIN")` (SecurityConfig)
- `audit_logs`: 관리자 액션 영구 기록 (admin_id, action, target_type, target_id, payload JSONB)
- 로컬: `POST /api/auth/dev-admin-token` → 고정 UUID `...002` ADMIN 자동 발급
```

- [ ] **Step 6**: `kista-api/docs/claude/architecture.md` "동시 수정 필요 파일 쌍" 표에 row 추가:

```
| `User.role` 변경 또는 `UserRole` 추가 | `UserEntity` + `UserPersistenceAdapter` + 모든 `new User(...)` 호출처 + `JwtIssuerService` claim |
| `AdminBootstrapProperties.kakaoIds` 변경 | `application.yml` + `.env.example` + `docker-compose.yml` + Render env |
```

- [ ] **Step 7**: 커밋

---

## T12. /api/admin/_ping 더미 컨트롤러 + 가드 통합 테스트

**Files:**
- Create: `kista-api/src/main/java/com/kista/adapter/in/web/AdminPingController.java`
- Create: `kista-api/src/test/java/com/kista/adapter/in/web/AdminPingControllerTest.java`

**Why:** Phase 2A에서 실제 admin 엔드포인트는 없지만, 가드가 동작하는지 검증할 최소 컨트롤러 필요. Phase 2B에서 실제 엔드포인트 추가 시 그대로 둠.

- [ ] **Step 1**: AdminPingController

```java
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin")
public class AdminPingController {
    @GetMapping("/_ping")
    public Map<String, String> ping(@AuthenticationPrincipal UUID userId) {
        return Map.of("status", "ok", "adminId", userId.toString());
    }
}
```

- [ ] **Step 2**: `@WebMvcTest(AdminPingController.class)` 통합 테스트 — testing.md 패턴 (`@MockBean JwtDecoder`, `@Execution(SAME_THREAD)`)

```java
@Test
void ping_anonymous_returns401() throws Exception {
    mockMvc.perform(get("/api/admin/_ping")).andExpect(status().isUnauthorized());
}

@Test
void ping_user_returns403() throws Exception {
    mockMvc.perform(get("/api/admin/_ping")
        .with(authentication(token(USER_UUID, "ROLE_USER"))))
        .andExpect(status().isForbidden());
}

@Test
void ping_admin_returns200() throws Exception {
    mockMvc.perform(get("/api/admin/_ping")
        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
}

private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
    return new UsernamePasswordAuthenticationToken(uuid, null,
        List.of(new SimpleGrantedAuthority(role)));
}
```

- [ ] **Step 3**: `./gradlew test --tests '*AdminPingControllerTest'` 401/403/200 모두 통과
- [ ] **Step 4**: 커밋

---

## T13. 최종 검증

- [ ] **Step 1**: `./gradlew test --tests 'com.kista.architecture.*'` (ArchUnit) 통과
- [ ] **Step 2**: `./gradlew test` 전체 통과 (Docker 의존 PortfolioSnapshot/TradeHistory 2건은 infra 이슈, 코드 회귀 아님)
- [ ] **Step 3**: 로컬 기동 + 수동 가드 검증

```bash
docker-compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'

# 일반 사용자 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/_ping  # 403

# ADMIN 토큰
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/_ping  # 200

# audit_logs INSERT 확인 (수동)
docker exec kista-api-postgres-1 psql -U kista -d kistadb \
  -c "INSERT INTO audit_logs(admin_id, action) VALUES ('00000000-0000-0000-0000-000000000002', 'TEST');"
docker exec kista-api-postgres-1 psql -U kista -d kistadb \
  -c "SELECT id, admin_id, action, created_at FROM audit_logs;"
```

- [ ] **Step 4**: 401/403/200 모두 의도대로 응답, audit_logs INSERT 정상
- [ ] **Step 5**: 메모리 업데이트 — `project_phase2a_admin_foundation.md` 신규 작성

---

## 위험 요소

1. **User record 필드 전파 누락** — `grep "new User("`로 7곳 잡았지만 동적 호출 가능성. `./gradlew compileTestJava`로만 신뢰 (testing.md "string 값 grep보다 compileTestJava가 신뢰성 높음").
2. **PostgreSQL 네이티브 ENUM `@JdbcTypeCode` 누락** — constraints.md "user_status, strategy_type와 동일 패턴" 반드시 준수. 미적용 시 `column "role" is of type user_role but expression is of type character varying` 런타임 오류.
3. **첫 ADMIN seed 시점 race** — 카카오 로그인 시 promote 로직이 register와 login 양쪽에 있어야 함. login 분기에서 idempotent 보장.
4. **JWT claim breaking change** — 기존 발급된 토큰(role claim 없음)은 `roleClaim == null` 분기로 빈 authorities → `/api/admin/**` 403, 일반 엔드포인트는 통과 (안전). 사용자에게 재로그인 안내 불필요.
5. **DevAuthController dev-admin-token 운영 노출** — `@Profile("local")` 확인 필수. prod 미노출 (이미 DevAuthController 전체에 적용됨).
6. **ArchUnit `application → adapter` 위반 위험** — ObjectMapper는 Spring 기본 빈, AuditLogPersistenceAdapter는 adapter.out이므로 안전. AuditLogPort는 domain.port.out (Hexagonal 준수).
7. **JSONB Hibernate 매핑** — `@JdbcTypeCode(SqlTypes.JSON)` + String 컬럼 패턴 안전. 다른 패턴(`@Type(JsonType.class)`) 시도 시 hypersistence-utils 의존 추가 필요 — 피할 것.

---

## 명시 비범위 (Phase 2B/C/D)

**Phase 2B (다음 plan):**
- `/api/admin/users` GET (list with filter) / POST approve / POST reject / PATCH role / DELETE
- `/api/admin/dashboard/stats` GET
- `/api/admin/audit-logs` GET (조회 + 필터)
- kista-ui `app/admin/` 라우트 그룹 + `AdminShell`/`AdminSidebar`/`AdminTopBar`
- Overview V1 + Pending + Users 3화면
- kista-ui `lib/api/admin.ts` + `types/admin.ts`
- proxy.ts role 가드 (`/admin/**` ADMIN만 접근)

**Phase 2C:**
- Admin 5 화면 추가 (Accounts, Trades, System, Audit, Statistics)
- 모바일 3 화면 (Overview, Pending, Anomalies)
- 디자인 verifier 잔존 이슈 정리 (`AdminInvitesScreen`, dark sidebar)

**Phase 2D:**
- 회원 탈퇴 백엔드 (`UserService.delete` + `/api/users/me` DELETE, Settings의 disabled 버튼 활성화)
- 텔레그램 callback_query 제거 (`TelegramBotService.handleCallbackQuery` 삭제 → ApproveUserUseCase 의존 정리)
- AccountEdit 변경이력 실제 DB
- NewAccount Step 1 `/api/accounts/test-connection` 엔드포인트
- 옵티미스틱 업데이트 (SSE → store → Dashboard/Statistics 즉시 갱신)

---

## Critical Files

- `kista-api/src/main/resources/db/migration/V17__add_role_and_audit_logs.sql` — 신설
- `kista-api/src/main/java/com/kista/domain/model/User.java` — role 필드 추가 (7곳 전파)
- `kista-api/src/main/java/com/kista/adapter/in/web/security/JwtIssuerService.java` — claim 확장
- `kista-api/src/main/java/com/kista/adapter/in/web/security/JwtAuthFilter.java` — authorities 주입
- `kista-api/src/main/java/com/kista/adapter/in/web/security/SecurityConfig.java` — `/api/admin/**` 가드
- `kista-api/src/main/java/com/kista/application/service/UserService.java` — ADMIN seed
- `kista-api/src/main/java/com/kista/application/config/AdminBootstrapProperties.java` — 신설
- `kista-api/src/main/java/com/kista/adapter/out/persistence/AuditLogPersistenceAdapter.java` — 신설
- `kista-api/src/main/java/com/kista/adapter/in/web/AdminPingController.java` — 신설 (가드 검증 목적, Phase 2B에서도 유지)
- `kista-api/docs/claude/constraints.md` — ADMIN 권한 관리 섹션 추가
- `kista-api/docs/claude/architecture.md` — 동시 수정 파일 쌍 표 갱신

---

## End-to-End Verification

1. `docker-compose up -d postgres` → `./gradlew flywayMigrate` (또는 bootRun) → V17 적용 확인 (`\d users` + `\d audit_logs`)
2. `./gradlew test` 전체 통과 (Docker 의존 2건은 infra 이슈로 OK)
3. `./gradlew test --tests 'com.kista.architecture.*'` ArchUnit 통과
4. `./gradlew bootRun --args='--spring.profiles.active=local'` 정상 기동 (DDL validate OK, AdminBootstrapProperties 바인딩 OK)
5. `POST /api/auth/dev-token` → JWT에 `"role": "USER"` claim 포함 (jwt.io에서 디코드 확인)
6. `POST /api/auth/dev-admin-token` → JWT에 `"role": "ADMIN"` claim 포함
7. `GET /api/admin/_ping` — Bearer 없음 401, USER 토큰 403, ADMIN 토큰 200 (jsonPath `$.status == "ok"`)
8. (선택) `ADMIN_KAKAO_IDS=<본인kakaoId>` 설정 후 카카오 로그인 → 자동 ADMIN/ACTIVE 승격 확인
9. CLAUDE.md / docs/claude/* 갱신 사항이 모두 반영되었는지 grep으로 확인
