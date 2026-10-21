# JWT AT/RT + Redis 블랙리스트 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AT 15분 + RT 120시간(RTR) + HttpOnly 쿠키 + Upstash Redis 블랙리스트로 즉각 취소 가능한 토큰 라이프사이클 구현

**Architecture:** AT는 기존 ES256 JWT(TTL 15분)로 stateless 유지. RT는 SHA-256 해시를 DB에 저장하는 불투명 토큰으로 HttpOnly 쿠키 전달. 로그아웃·탈퇴·거절 시 Upstash Redis 블랙리스트에 userId 등재, JwtAuthFilter에서 매 요청마다 인메모리 체크. RTR(Refresh Token Rotation)로 구 토큰 재사용 시 즉시 401.

**Tech Stack:** Spring Boot 3, spring-boot-starter-data-redis (Lettuce), Upstash Redis, JPA, Flyway V3

## Global Constraints

- Java 21, Spring Boot 3, Kotlin DSL (build.gradle.kts), Hexagonal Architecture
- **ArchUnit 엄수**: `adapter.in → domain.port.in`, `application → domain(model+port)`, `adapter.out → domain.port.out 구현`
- 커밋 author: `narafu <narafu@kakao.com>`
- AT TTL: `900_000ms` (15분) / AT_TTL_SECONDS: `900`
- RT TTL: `Duration.ofHours(120)` (5일)
- 블랙리스트 Redis 키: `blacklist:user:{userId}`, TTL: AT TTL(15분)과 동일
- RT 쿠키 이름: `refresh_token`, Path: `/api/auth`, HttpOnly, Secure(prod)/비Secure(local)
- RT 포맷: `SecureRandom` 32바이트 → Base64URL (raw), DB 저장: SHA-256 hex(64자)
- `@SQLRestriction` 미적용 nativeQuery에 수동 WHERE 조건 추가 불필요 (refresh_tokens는 soft-delete 없음)
- 테스트: JUnit 5 + Mockito, `@MockitoBean` 사용 권장

## Prerequisites (코드 작업 전 수동 설정)

1. [Upstash Console](https://console.upstash.com) → Redis 인스턴스 생성 → `REDIS_URL` 획득 (`rediss://...` 형식)
2. `flyctl secrets set REDIS_URL='rediss://...' -a kista-api`
3. 로컬 `application-local.yml`에 `spring.data.redis.url: redis://localhost:6379` 추가 (또는 Upstash URL 사용)

---

## 파일 구조

### 신규 생성
```
src/main/resources/db/migration/
  V3__add_refresh_tokens.sql

src/main/java/com/kista/
  domain/model/auth/
    RefreshToken.java               # RT 도메인 레코드
    TokenRefreshResult.java         # TokenUseCase refresh() 반환 타입
  domain/port/in/
    TokenUseCase.java               # issueRefreshToken / refresh / logout
    BlacklistUseCase.java           # isBlacklisted (JwtAuthFilter용)
  domain/port/out/
    RefreshTokenPort.java           # RT CRUD
    BlacklistPort.java              # Redis 블랙리스트 add/check
  application/service/auth/
    TokenService.java               # TokenUseCase 구현
    BlacklistService.java           # BlacklistUseCase 구현
  adapter/out/persistence/auth/
    RefreshTokenEntity.java
    RefreshTokenJpaRepository.java  # package-private
    RefreshTokenPersistenceAdapter.java
  adapter/out/redis/
    RedisBlacklistAdapter.java
  adapter/in/web/security/
    RefreshTokenCookieHelper.java   # 쿠키 생성/파싱/삭제 헬퍼

src/test/java/com/kista/
  application/service/auth/
    TokenServiceTest.java
    BlacklistServiceTest.java
  adapter/out/persistence/auth/
    RefreshTokenPersistenceAdapterTest.java
  adapter/out/redis/
    RedisBlacklistAdapterTest.java
  adapter/in/web/
    AuthControllerTokenTest.java    # refresh/logout 엔드포인트 테스트
```

### 수정
```
build.gradle.kts                              # redis starter 추가
src/main/resources/application.yml            # redis + cookie 설정
src/main/resources/application-local.yml      # 로컬 redis + cookie 설정
adapter/in/web/security/JwtIssuerService.java # TOKEN_TTL_MS: 7일 → 15분
adapter/in/web/security/JwtAuthFilter.java    # 블랙리스트 체크 추가
adapter/in/web/AuthController.java            # 카카오 RT 쿠키 + refresh/logout
adapter/in/web/DevAuthController.java         # dev-token RT 쿠키
application/service/user/UserService.java     # reject + deleteMe 블랙리스트
application/service/user/UserCascadeDeleter.java # RT 삭제 + 블랙리스트
src/test/java/.../UserServiceTest.java        # Mock 추가 + 테스트 보강
```

---

## Task 1: Gradle + Redis 의존성 + 설정

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml` (Edit 도구 직접 수정)

- [ ] **Step 1: build.gradle.kts에 Redis starter 추가**

`dependencies { }` 블록 내 `// Security & JWT` 섹션 바로 위에 추가:

```kotlin
// Redis (Upstash 블랙리스트 + 캐시)
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

- [ ] **Step 2: application.yml에 Redis + 쿠키 설정 추가**

`jwt:` 섹션 아래에 추가:

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}

app:
  cookie:
    secure: true        # prod: HTTPS 쿠키
    same-site: "None"   # prod: 크로스 오리진(Vercel→Fly.io)
```

- [ ] **Step 3: application-local.yml에 로컬 설정 추가**

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379

app:
  cookie:
    secure: false
    same-site: "Lax"
```

- [ ] **Step 4: 컴파일 검증**

```bash
cd /Users/phs/workspace/kista/kista-api
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add build.gradle.kts src/main/resources/application.yml
git -C /Users/phs/workspace/kista/kista-api commit -m "chore: add spring-boot-starter-data-redis + Upstash Redis 설정

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2: 도메인 레이어 — RefreshToken + 포트 인터페이스

**Files:**
- Create: `src/main/java/com/kista/domain/model/auth/RefreshToken.java`
- Create: `src/main/java/com/kista/domain/model/auth/TokenRefreshResult.java`
- Create: `src/main/java/com/kista/domain/port/out/RefreshTokenPort.java`
- Create: `src/main/java/com/kista/domain/port/out/BlacklistPort.java`
- Create: `src/main/java/com/kista/domain/port/in/TokenUseCase.java`
- Create: `src/main/java/com/kista/domain/port/in/BlacklistUseCase.java`

- [ ] **Step 1: RefreshToken 도메인 레코드 생성**

```java
// src/main/java/com/kista/domain/model/auth/RefreshToken.java
package com.kista.domain.model.auth;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
        UUID id,           // 생성 시 null (@GeneratedValue)
        UUID userId,
        String tokenHash,  // SHA-256 hex(rawToken) — 64자
        String userAgent,  // nullable, 디바이스 식별용
        Instant expiresAt,
        Instant createdAt  // 생성 시 null (DB DEFAULT now())
) {}
```

- [ ] **Step 2: TokenRefreshResult 레코드 생성**

```java
// src/main/java/com/kista/domain/model/auth/TokenRefreshResult.java
package com.kista.domain.model.auth;

import com.kista.domain.model.user.User;

import java.util.UUID;

// TokenUseCase.refresh() 반환 타입 — 컨트롤러가 AT 발급 + RT 쿠키 설정에 사용
public record TokenRefreshResult(UUID userId, User.UserRole userRole, String newRawRefreshToken) {}
```

- [ ] **Step 3: RefreshTokenPort 생성**

```java
// src/main/java/com/kista/domain/port/out/RefreshTokenPort.java
package com.kista.domain.port.out;

import com.kista.domain.model.auth.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenPort {
    void save(RefreshToken token);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId); // 탈퇴/거절 시 전체 세션 종료
}
```

- [ ] **Step 4: BlacklistPort 생성**

```java
// src/main/java/com/kista/domain/port/out/BlacklistPort.java
package com.kista.domain.port.out;

import java.time.Duration;
import java.util.UUID;

public interface BlacklistPort {
    void add(UUID userId, Duration ttl); // AT TTL과 동일 기간 차단
    boolean isBlacklisted(UUID userId);
}
```

- [ ] **Step 5: TokenUseCase 생성**

```java
// src/main/java/com/kista/domain/port/in/TokenUseCase.java
package com.kista.domain.port.in;

import com.kista.domain.model.auth.TokenRefreshResult;

import java.util.UUID;

public interface TokenUseCase {
    // 로그인 성공 후 RT 발급 — rawToken 반환 (컨트롤러가 쿠키로 전달)
    String issueRefreshToken(UUID userId, String userAgent);
    // RTR: 구 RT 삭제 + 새 RT 발급, 새 AT 발급에 필요한 userId/role 반환
    TokenRefreshResult refresh(String rawRefreshToken, String userAgent);
    // 로그아웃: RT 삭제 + userId 블랙리스트 등재
    void logout(String rawRefreshToken);
}
```

- [ ] **Step 6: BlacklistUseCase 생성 (JwtAuthFilter용 — adapter.in이 domain.port.in만 참조 가능)**

```java
// src/main/java/com/kista/domain/port/in/BlacklistUseCase.java
package com.kista.domain.port.in;

import java.util.UUID;

public interface BlacklistUseCase {
    boolean isBlacklisted(UUID userId);
}
```

- [ ] **Step 7: 컴파일 검증**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add src/main/java/com/kista/domain/
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: RT/블랙리스트 도메인 모델 + 포트 인터페이스 추가

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3: Flyway V3 + RefreshToken JPA 어댑터

**Files:**
- Create: `src/main/resources/db/migration/V4__add_refresh_tokens.sql`
- Create: `src/main/java/com/kista/adapter/out/persistence/auth/RefreshTokenEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/auth/RefreshTokenJpaRepository.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/auth/RefreshTokenPersistenceAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/persistence/auth/RefreshTokenPersistenceAdapterTest.java`

- [ ] **Step 1: Flyway V4 마이그레이션 작성**

```sql
-- src/main/resources/db/migration/V4__add_refresh_tokens.sql
-- 컬럼 순서 규칙: pk → fk → 비즈니스 컬럼 → created_at (updated_at/deleted_at 없음)
CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,  -- SHA-256 hex = 64자
    user_agent  VARCHAR(512),           -- nullable: 디바이스 식별용
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

- [ ] **Step 2: RefreshTokenEntity 작성**

```java
// src/main/java/com/kista/adapter/out/persistence/auth/RefreshTokenEntity.java
package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    static RefreshTokenEntity from(RefreshToken domain) {
        RefreshTokenEntity e = new RefreshTokenEntity();
        e.userId = domain.userId();
        e.tokenHash = domain.tokenHash();
        e.userAgent = domain.userAgent();
        e.expiresAt = domain.expiresAt();
        return e;
    }

    RefreshToken toDomain() {
        return new RefreshToken(id, userId, tokenHash, userAgent, expiresAt, createdAt);
    }
}
```

- [ ] **Step 3: RefreshTokenJpaRepository 작성 (package-private)**

```java
// src/main/java/com/kista/adapter/out/persistence/auth/RefreshTokenJpaRepository.java
package com.kista.adapter.out.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// package-private — 외부 패키지 직접 참조 금지 (constraints.md)
interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId);
}
```

- [ ] **Step 4: RefreshTokenPersistenceAdapter 작성**

```java
// src/main/java/com/kista/adapter/out/persistence/auth/RefreshTokenPersistenceAdapter.java
package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.port.out.RefreshTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class RefreshTokenPersistenceAdapter implements RefreshTokenPort {

    private final RefreshTokenJpaRepository repository;

    @Override
    @Transactional
    public void save(RefreshToken token) {
        repository.save(RefreshTokenEntity.from(token));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(RefreshTokenEntity::toDomain);
    }

    @Override
    @Transactional
    public void deleteByTokenHash(String tokenHash) {
        repository.deleteByTokenHash(tokenHash);
    }

    @Override
    @Transactional
    public void deleteAllByUserId(UUID userId) {
        repository.deleteAllByUserId(userId);
    }
}
```

- [ ] **Step 5: 테스트 작성**

```java
// src/test/java/com/kista/adapter/out/persistence/auth/RefreshTokenPersistenceAdapterTest.java
package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.port.out.RefreshTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenPersistenceAdapterTest {

    @Mock RefreshTokenJpaRepository repository;
    @InjectMocks RefreshTokenPersistenceAdapter adapter;

    @Test
    void save_persistsEntity() {
        RefreshToken token = new RefreshToken(null, UUID.randomUUID(), "hash64chars",
                "Mozilla/5.0", Instant.now().plusSeconds(432000), null);
        adapter.save(token);
        verify(repository).save(any(RefreshTokenEntity.class));
    }

    @Test
    void findByTokenHash_found_returnsDomain() {
        UUID userId = UUID.randomUUID();
        RefreshTokenEntity entity = RefreshTokenEntity.from(
                new RefreshToken(null, userId, "abc123", null, Instant.now().plusSeconds(1000), null));
        given(repository.findByTokenHash("abc123")).willReturn(Optional.of(entity));

        Optional<RefreshToken> result = adapter.findByTokenHash("abc123");

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(userId);
    }

    @Test
    void findByTokenHash_notFound_returnsEmpty() {
        given(repository.findByTokenHash("unknown")).willReturn(Optional.empty());
        assertThat(adapter.findByTokenHash("unknown")).isEmpty();
    }

    @Test
    void deleteByTokenHash_callsRepository() {
        adapter.deleteByTokenHash("somehash");
        verify(repository).deleteByTokenHash("somehash");
    }

    @Test
    void deleteAllByUserId_callsRepository() {
        UUID userId = UUID.randomUUID();
        adapter.deleteAllByUserId(userId);
        verify(repository).deleteAllByUserId(userId);
    }
}
```

- [ ] **Step 6: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.adapter.out.persistence.auth.*'
```

Expected: 5 tests PASSED

- [ ] **Step 7: 컴파일 + ArchUnit 통과 확인**

```bash
./gradlew test --tests 'com.kista.architecture.*'
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add src/main/resources/db/migration/V3__add_refresh_tokens.sql \
  src/main/java/com/kista/adapter/out/persistence/auth/ \
  src/test/java/com/kista/adapter/out/persistence/auth/
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: Flyway V3 refresh_tokens 테이블 + JPA 어댑터

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4: Redis 블랙리스트 어댑터 + BlacklistService

**Files:**
- Create: `src/main/java/com/kista/adapter/out/redis/RedisBlacklistAdapter.java`
- Create: `src/main/java/com/kista/application/service/auth/BlacklistService.java`
- Create: `src/test/java/com/kista/adapter/out/redis/RedisBlacklistAdapterTest.java`
- Create: `src/test/java/com/kista/application/service/auth/BlacklistServiceTest.java`

- [ ] **Step 1: RedisBlacklistAdapter 작성**

```java
// src/main/java/com/kista/adapter/out/redis/RedisBlacklistAdapter.java
package com.kista.adapter.out.redis;

import com.kista.domain.port.out.BlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class RedisBlacklistAdapter implements BlacklistPort {

    private static final String KEY_PREFIX = "blacklist:user:";
    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(UUID userId, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1", ttl);
    }

    @Override
    public boolean isBlacklisted(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
    }
}
```

- [ ] **Step 2: BlacklistService 작성**

```java
// src/main/java/com/kista/application/service/auth/BlacklistService.java
package com.kista.application.service.auth;

import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.BlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class BlacklistService implements BlacklistUseCase {

    private final BlacklistPort blacklistPort;

    @Override
    public boolean isBlacklisted(UUID userId) {
        return blacklistPort.isBlacklisted(userId);
    }
}
```

- [ ] **Step 3: RedisBlacklistAdapterTest 작성**

```java
// src/test/java/com/kista/adapter/out/redis/RedisBlacklistAdapterTest.java
package com.kista.adapter.out.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisBlacklistAdapterTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks RedisBlacklistAdapter adapter;

    @Test
    void add_setsKeyWithTtl() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        adapter.add(userId, Duration.ofMinutes(15));

        verify(valueOps).set("blacklist:user:" + userId, "1", Duration.ofMinutes(15));
    }

    @Test
    void isBlacklisted_keyExists_returnsTrue() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.hasKey("blacklist:user:" + userId)).willReturn(true);

        assertThat(adapter.isBlacklisted(userId)).isTrue();
    }

    @Test
    void isBlacklisted_keyAbsent_returnsFalse() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.hasKey("blacklist:user:" + userId)).willReturn(false);

        assertThat(adapter.isBlacklisted(userId)).isFalse();
    }

    @Test
    void isBlacklisted_nullFromRedis_returnsFalse() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.hasKey("blacklist:user:" + userId)).willReturn(null);

        assertThat(adapter.isBlacklisted(userId)).isFalse();
    }
}
```

- [ ] **Step 4: BlacklistServiceTest 작성**

```java
// src/test/java/com/kista/application/service/auth/BlacklistServiceTest.java
package com.kista.application.service.auth;

import com.kista.domain.port.out.BlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BlacklistServiceTest {

    @Mock BlacklistPort blacklistPort;
    @InjectMocks BlacklistService blacklistService;

    @Test
    void isBlacklisted_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        given(blacklistPort.isBlacklisted(userId)).willReturn(true);
        assertThat(blacklistService.isBlacklisted(userId)).isTrue();
    }
}
```

- [ ] **Step 5: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.adapter.out.redis.*' --tests 'com.kista.application.service.auth.BlacklistServiceTest'
```

Expected: 5 tests PASSED

- [ ] **Step 6: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add \
  src/main/java/com/kista/adapter/out/redis/ \
  src/main/java/com/kista/application/service/auth/BlacklistService.java \
  src/test/java/com/kista/adapter/out/redis/ \
  src/test/java/com/kista/application/service/auth/BlacklistServiceTest.java
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: Redis 블랙리스트 어댑터 + BlacklistService

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5: TokenService + AT TTL 변경

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/security/JwtIssuerService.java`
- Create: `src/main/java/com/kista/application/service/auth/TokenService.java`
- Create: `src/test/java/com/kista/application/service/auth/TokenServiceTest.java`

- [ ] **Step 1: JwtIssuerService AT TTL 15분으로 변경**

`JwtIssuerService.java`의 `TOKEN_TTL_MS` 상수 변경:

```java
// 변경 전
public static final long TOKEN_TTL_MS = 604_800_000L; // 7일 (밀리초)

// 변경 후
public static final long TOKEN_TTL_MS = 900_000L; // 15분 (밀리초)
```

- [ ] **Step 2: TokenService 작성**

```java
// src/main/java/com/kista/application/service/auth/TokenService.java
package com.kista.application.service.auth;

import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.model.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
class TokenService implements TokenUseCase {

    private static final Duration RT_TTL = Duration.ofHours(120); // 5일
    private static final Duration AT_TTL = Duration.ofMinutes(15);

    private final RefreshTokenPort refreshTokenPort;
    private final BlacklistPort blacklistPort;
    private final UserPort userPort;

    @Override
    public String issueRefreshToken(UUID userId, String userAgent) {
        // 로그인 시 새 RT 발급 — rawToken 반환, 컨트롤러가 HttpOnly 쿠키로 전달
        String rawToken = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, userId, sha256Hex(rawToken), userAgent,
                Instant.now().plus(RT_TTL), null
        ));
        return rawToken;
    }

    @Override
    public TokenRefreshResult refresh(String rawRefreshToken, String userAgent) {
        String hash = sha256Hex(rawRefreshToken);
        RefreshToken rt = refreshTokenPort.findByTokenHash(hash)
                .orElseThrow(() -> new NoSuchElementException("유효하지 않은 refresh token"));

        if (rt.expiresAt().isBefore(Instant.now())) {
            refreshTokenPort.deleteByTokenHash(hash); // 만료 토큰 즉시 정리
            throw new NoSuchElementException("만료된 refresh token");
        }

        // RTR: 구 RT 삭제 후 새 RT 발급
        refreshTokenPort.deleteByTokenHash(hash);
        User user = userPort.findByIdOrThrow(rt.userId());
        String newRaw = generateRawToken();
        refreshTokenPort.save(new RefreshToken(
                null, rt.userId(), sha256Hex(newRaw), userAgent,
                Instant.now().plus(RT_TTL), null
        ));

        return new TokenRefreshResult(user.id(), user.role(), newRaw);
    }

    @Override
    public void logout(String rawRefreshToken) {
        String hash = sha256Hex(rawRefreshToken);
        refreshTokenPort.findByTokenHash(hash).ifPresent(rt -> {
            refreshTokenPort.deleteByTokenHash(hash);
            blacklistPort.add(rt.userId(), AT_TTL); // 남은 AT 수명 동안 즉시 차단
        });
    }

    // package-private — TokenServiceTest에서 직접 테스트
    static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘 없음", e);
        }
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256비트 엔트로피
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 3: TokenServiceTest 작성**

```java
// src/test/java/com/kista/application/service/auth/TokenServiceTest.java
package com.kista.application.service.auth;

import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock RefreshTokenPort refreshTokenPort;
    @Mock BlacklistPort blacklistPort;
    @Mock UserPort userPort;
    @InjectMocks TokenService tokenService;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void issueRefreshToken_savesHashedToken() {
        String rawToken = tokenService.issueRefreshToken(USER_ID, "Mozilla/5.0");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenPort).save(captor.capture());

        RefreshToken saved = captor.getValue();
        assertThat(saved.userId()).isEqualTo(USER_ID);
        assertThat(saved.tokenHash()).isEqualTo(TokenService.sha256Hex(rawToken));
        assertThat(saved.id()).isNull(); // JPA가 생성
        assertThat(saved.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(119)));
    }

    @Test
    void refresh_validToken_rotatesAndReturnsResult() {
        String rawOld = "oldRawToken12345";
        String oldHash = TokenService.sha256Hex(rawOld);
        RefreshToken existing = new RefreshToken(UUID.randomUUID(), USER_ID, oldHash,
                "ua", Instant.now().plus(Duration.ofHours(120)), Instant.now());
        User user = mockUser(USER_ID, User.UserRole.USER);

        given(refreshTokenPort.findByTokenHash(oldHash)).willReturn(Optional.of(existing));
        given(userPort.findByIdOrThrow(USER_ID)).willReturn(user);

        TokenRefreshResult result = tokenService.refresh(rawOld, "ua");

        verify(refreshTokenPort).deleteByTokenHash(oldHash);            // 구 RT 삭제
        verify(refreshTokenPort).save(any(RefreshToken.class));          // 새 RT 저장
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.userRole()).isEqualTo(User.UserRole.USER);
        assertThat(result.newRawRefreshToken()).isNotEqualTo(rawOld);    // 새 토큰 발급
    }

    @Test
    void refresh_unknownToken_throws() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        assertThatThrownBy(() -> tokenService.refresh("unknown", "ua"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("유효하지 않은");
    }

    @Test
    void refresh_expiredToken_throwsAndCleansUp() {
        String raw = "expiredToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken expired = new RefreshToken(UUID.randomUUID(), USER_ID, hash,
                null, Instant.now().minusSeconds(1), Instant.now().minusSeconds(1000));
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> tokenService.refresh(raw, "ua"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("만료된");
        verify(refreshTokenPort).deleteByTokenHash(hash); // 만료 토큰 정리
    }

    @Test
    void logout_validToken_deletesAndBlacklists() {
        String raw = "logoutToken";
        String hash = TokenService.sha256Hex(raw);
        RefreshToken rt = new RefreshToken(UUID.randomUUID(), USER_ID, hash,
                null, Instant.now().plusSeconds(1000), Instant.now());
        given(refreshTokenPort.findByTokenHash(hash)).willReturn(Optional.of(rt));

        tokenService.logout(raw);

        verify(refreshTokenPort).deleteByTokenHash(hash);
        verify(blacklistPort).add(USER_ID, Duration.ofMinutes(15));
    }

    @Test
    void logout_unknownToken_noOp() {
        given(refreshTokenPort.findByTokenHash(any())).willReturn(Optional.empty());
        tokenService.logout("unknownToken"); // 예외 없이 종료
        verify(blacklistPort, never()).add(any(), any());
    }

    @Test
    void sha256Hex_deterministicAndLength64() {
        String hash = TokenService.sha256Hex("test");
        assertThat(hash).hasSize(64);
        assertThat(TokenService.sha256Hex("test")).isEqualTo(hash); // 결정론적
    }

    private User mockUser(UUID id, User.UserRole role) {
        return new User(id, "kakaoId", "닉네임",
                User.UserStatus.ACTIVE, role, null, null, null, null,
                User.NotificationChannel.FCM, true);
    }
}
```

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.application.service.auth.TokenServiceTest'
```

Expected: 7 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add \
  src/main/java/com/kista/adapter/in/web/security/JwtIssuerService.java \
  src/main/java/com/kista/application/service/auth/TokenService.java \
  src/test/java/com/kista/application/service/auth/TokenServiceTest.java
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: TokenService(RTR) + AT TTL 15분 변경

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 6: JwtAuthFilter — 블랙리스트 체크 추가

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/security/JwtAuthFilter.java`

- [ ] **Step 1: JwtAuthFilter에 BlacklistUseCase 주입 + 체크 추가**

`JwtAuthFilter.java` 전체 교체:

```java
package com.kista.adapter.in.web.security;

import com.kista.domain.port.in.BlacklistUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;
    private final BlacklistUseCase blacklistUseCase; // Redis 블랙리스트 체크 (adapter.in → domain.port.in)

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null) {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                UUID userId = UUID.fromString(jwt.getSubject()); // sub 클레임 = 사용자 UUID

                // 블랙리스트 체크 — 탈퇴·로그아웃·거절된 userId 즉시 차단
                if (blacklistUseCase.isBlacklisted(userId)) {
                    log.debug("블랙리스트 차단: userId={}", userId);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }

                // role claim → ROLE_* authority 변환 (claim 없으면 빈 authorities)
                String roleClaim = jwt.getClaimAsString("role");
                List<SimpleGrantedAuthority> authorities = roleClaim == null
                        ? List.of()
                        : List.of(new SimpleGrantedAuthority("ROLE_" + roleClaim));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, authorities)
                );
            } catch (Exception e) { // JwtException + NPE + IAE 등 모두 처리
                log.warn("JWT 인증 실패: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
```

- [ ] **Step 2: @WebMvcTest에서 BlacklistUseCase MockBean 추가**

기존 `@WebMvcTest` 테스트 클래스 중 `@Import(JwtAuthFilter.class)`를 사용하는 파일들을 검색하고, 각각에 `@MockitoBean BlacklistUseCase blacklistUseCase;` 추가:

```bash
grep -rl "JwtAuthFilter" /Users/phs/workspace/kista/kista-api/src/test --include="*.java"
```

검색된 각 파일에 아래 필드 추가 (기존 `@MockitoBean JwtDecoder` 바로 아래):
```java
@MockitoBean BlacklistUseCase blacklistUseCase;
```

- [ ] **Step 3: 전체 테스트 통과 확인**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL (기존 테스트 모두 통과)

- [ ] **Step 4: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add src/main/java/com/kista/adapter/in/web/security/JwtAuthFilter.java
git -C /Users/phs/workspace/kista/kista-api add src/test/  # MockitoBean 추가된 테스트 포함
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: JwtAuthFilter Redis 블랙리스트 체크 추가

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 7: RefreshTokenCookieHelper + AuthController RT 쿠키

**Files:**
- Create: `src/main/java/com/kista/adapter/in/web/security/RefreshTokenCookieHelper.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AuthController.java`
- Create: `src/test/java/com/kista/adapter/in/web/AuthControllerTokenTest.java`

- [ ] **Step 1: RefreshTokenCookieHelper 작성**

```java
// src/main/java/com/kista/adapter/in/web/security/RefreshTokenCookieHelper.java
package com.kista.adapter.in.web.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieHelper {

    private static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth"; // refresh/logout 엔드포인트에만 전송
    private static final long RT_MAX_AGE = 432_000L; // 120시간 = 5일

    @Value("${app.cookie.secure:true}")
    private boolean secure;

    @Value("${app.cookie.same-site:None}")
    private String sameSite;

    // RT를 HttpOnly 쿠키로 발급
    public ResponseCookie issue(String rawRefreshToken) {
        return ResponseCookie.from(COOKIE_NAME, rawRefreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(RT_MAX_AGE)
                .build();
    }

    // 로그아웃 시 쿠키 삭제 (maxAge=0)
    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    // 요청 쿠키에서 rawToken 추출
    public String extract(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
```

- [ ] **Step 2: AuthController 수정**

기존 `AuthController.java`를 아래로 교체:

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.KakaoLoginResponse;
import com.kista.adapter.in.web.dto.UserResponse;
import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.in.web.security.RefreshTokenCookieHelper;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Tag(name = "인증", description = "카카오 OAuth 로그인, 토큰 갱신, 사용자 정보 조회, 승인 신청")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserUseCase userUseCase;
    private final TokenUseCase tokenUseCase;
    private final JwtIssuerService jwtIssuerService;
    private final RefreshTokenCookieHelper cookieHelper;
    private final SseEmitterRegistry sseEmitterRegistry;

    record KakaoCallbackRequest(
            @Schema(description = "카카오 OAuth 인가 코드")
            String code,
            @Schema(description = "카카오 리다이렉트 URI")
            String redirectUri
    ) {}

    // 카카오 OAuth 인가 코드로 로그인 — AT(body) + RT(HttpOnly 쿠키) 발급
    @Operation(summary = "카카오 로그인")
    @ApiResponse(responseCode = "200", description = "로그인 성공")
    @PostMapping("/kakao/callback")
    @SecurityRequirements
    public KakaoLoginResponse kakaoCallback(@RequestBody KakaoCallbackRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        User user = userUseCase.login(request.code(), request.redirectUri());
        String rawRt = tokenUseCase.issueRefreshToken(user.id(), httpRequest.getHeader("User-Agent"));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(rawRt).toString());
        String at = jwtIssuerService.issue(user.id(), user.role());
        return new KakaoLoginResponse(at, "bearer", jwtIssuerService.expiresInSeconds(), UserResponse.from(user));
    }

    // RT 쿠키로 새 AT + RT 발급 (RTR)
    @Operation(summary = "토큰 갱신", description = "HttpOnly 쿠키의 refresh_token으로 새 AT + RT를 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "갱신 성공"),
            @ApiResponse(responseCode = "401", description = "유효하지 않은 refresh token")
    })
    @PostMapping("/refresh")
    @SecurityRequirements
    public KakaoLoginResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawRt = cookieHelper.extract(request);
        if (rawRt == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        var result = tokenUseCase.refresh(rawRt, request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(result.newRawRefreshToken()).toString());
        String newAt = jwtIssuerService.issue(result.userId(), result.userRole());
        return new KakaoLoginResponse(newAt, "bearer", jwtIssuerService.expiresInSeconds(),
                UserResponse.from(userUseCase.getById(result.userId())));
    }

    // 로그아웃 — RT 삭제 + userId 블랙리스트 + 쿠키 삭제
    @Operation(summary = "로그아웃", description = "refresh_token 쿠키를 무효화하고 AT를 즉시 차단합니다.")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String rawRt = cookieHelper.extract(request);
        if (rawRt != null) {
            tokenUseCase.logout(rawRt);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clear().toString());
    }

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UUID userId) {
        return UserResponse.from(userUseCase.getById(userId));
    }

    @Operation(summary = "승인 상태 SSE 스트림")
    @GetMapping(value = "/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter statusStream(@AuthenticationPrincipal UUID userId) {
        return sseEmitterRegistry.connect(userId);
    }

    @Operation(summary = "승인 재신청")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "재신청 성공"),
            @ApiResponse(responseCode = "400", description = "재신청 불가 상태"),
            @ApiResponse(responseCode = "429", description = "쿨다운 중")
    })
    @PostMapping("/approval-requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestApproval(@AuthenticationPrincipal UUID userId) {
        userUseCase.reapply(userId);
    }

    // 회원 탈퇴 — cascade로 계좌/거래내역/토큰 자동 삭제 (FK CASCADE + 블랙리스트 등재)
    @Operation(summary = "회원 탈퇴")
    @ApiResponse(responseCode = "204", description = "탈퇴 성공")
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal UUID userId, HttpServletResponse response) {
        userUseCase.deleteMe(userId);
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clear().toString());
    }
}
```

- [ ] **Step 3: AuthControllerTokenTest 작성**

```java
// src/test/java/com/kista/adapter/in/web/AuthControllerTokenTest.java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.*;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
         RefreshTokenCookieHelper.class})
class AuthControllerTokenTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserUseCase userUseCase;
    @MockitoBean TokenUseCase tokenUseCase;
    @MockitoBean JwtIssuerService jwtIssuerService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean com.kista.adapter.out.sse.SseEmitterRegistry sseEmitterRegistry;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void refresh_validCookie_returns200AndSetsNewCookie() throws Exception {
        var result = new TokenRefreshResult(USER_ID, User.UserRole.USER, "newRawRt");
        User user = stubUser(USER_ID);
        given(tokenUseCase.refresh(anyString(), any())).willReturn(result);
        given(userUseCase.getById(USER_ID)).willReturn(user);
        given(jwtIssuerService.issue(any(), any())).willReturn("newAt");
        given(jwtIssuerService.expiresInSeconds()).willReturn(900L);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "someRawRt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("newAt"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void refresh_noCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_invalidToken_returns404() throws Exception {
        given(tokenUseCase.refresh(anyString(), any()))
                .willThrow(new NoSuchElementException("유효하지 않은 refresh token"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "badToken")))
                .andExpect(status().isNotFound()); // GlobalExceptionHandler: NoSuchElementException → 404
    }

    @Test
    void logout_withCookie_returns204AndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "someRt")))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE)); // maxAge=0 쿠키
    }

    @Test
    void logout_noCookie_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    private User stubUser(UUID id) {
        return new User(id, "kakaoId", "닉네임",
                User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, User.NotificationChannel.FCM, true);
    }
}
```

- [ ] **Step 4: 테스트 실행**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.AuthControllerTokenTest'
```

Expected: 5 tests PASSED

- [ ] **Step 5: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add \
  src/main/java/com/kista/adapter/in/web/security/RefreshTokenCookieHelper.java \
  src/main/java/com/kista/adapter/in/web/AuthController.java \
  src/test/java/com/kista/adapter/in/web/AuthControllerTokenTest.java
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: AuthController RT 쿠키 발급 + refresh/logout 엔드포인트

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 8: UserService/UserCascadeDeleter 블랙리스트 등재

**Files:**
- Modify: `src/main/java/com/kista/application/service/user/UserService.java`
- Modify: `src/main/java/com/kista/application/service/user/UserCascadeDeleter.java`
- Modify: `src/test/java/com/kista/application/service/user/UserServiceTest.java`

- [ ] **Step 1: UserCascadeDeleter에 RT 삭제 + 블랙리스트 추가**

```java
package com.kista.application.service.user;

import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// UserService.deleteMe / AdminService.deleteUser 공통 cascade 삭제
@Component
@RequiredArgsConstructor
public class UserCascadeDeleter {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final AccountPort accountPort;
    private final UserPort userPort;
    private final RefreshTokenPort refreshTokenPort; // 모든 RT 삭제
    private final BlacklistPort blacklistPort;        // 남은 AT 즉시 차단

    private static final Duration AT_TTL = Duration.ofMinutes(15);

    public void deleteCascade(UUID userId) {
        // CyclePosition → StrategyCycle → Strategy → Account → User 순서로 소프트 삭제
        cyclePositionPort.deleteByUserId(userId);
        strategyCyclePort.deleteByUserId(userId);
        strategyPort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);
        userPort.delete(userId);
        // 인증 정리 — RT 전체 삭제 후 AT 즉시 차단
        refreshTokenPort.deleteAllByUserId(userId);
        blacklistPort.add(userId, AT_TTL);
    }
}
```

- [ ] **Step 2: UserService.reject()에 블랙리스트 추가**

`UserService.java`의 `reject()` 메서드에 필드와 로직 추가:

필드 추가 (기존 필드 목록 맨 아래):
```java
private final BlacklistPort blacklistPort;
```

`reject()` 메서드 마지막에 추가:
```java
blacklistPort.add(userId, Duration.ofMinutes(15)); // 거절 즉시 AT 차단
```

최종 `reject()` 메서드:
```java
@Override
public void reject(UUID userId) {
    User user = userPort.findByIdOrThrow(userId);
    User updated = user.withStatus(User.UserStatus.REJECTED, Instant.now());
    userPort.save(updated);
    log.info("사용자 거절: userId={}", userId);
    notificationPort.notifyRejected(updated);
    realtimeNotificationPort.notifyStatusChange(userId, User.UserStatus.REJECTED);
    blacklistPort.add(userId, Duration.ofMinutes(15)); // 거절 즉시 AT 차단
}
```

- [ ] **Step 3: UserServiceTest에 Mock 추가 + 테스트 보강**

`UserServiceTest.java`에 Mock 필드 추가:
```java
@Mock AccountPort accountPort;          // 기존 (이미 있음)
@Mock StrategyPort strategyPort;        // 기존
@Mock BlacklistPort blacklistPort;      // 신규 추가
```

`reject` 테스트에 검증 추가:
```java
@Test
void reject_blacklistsUser() {
    UUID userId = UUID.randomUUID();
    User user = // ... 기존 stub 방식과 동일
    given(userPort.findByIdOrThrow(userId)).willReturn(user);

    userService.reject(userId);

    verify(blacklistPort).add(eq(userId), eq(Duration.ofMinutes(15)));
}
```

`deleteMe` 테스트 — `userCascadeDeleter.deleteCascade()` 호출 검증은 기존 유지 (cascade 내부에서 blacklist 처리하므로 UserService 레벨 검증 불필요).

- [ ] **Step 4: 전체 테스트 실행**

```bash
./gradlew test --tests 'com.kista.application.service.user.*'
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add \
  src/main/java/com/kista/application/service/user/ \
  src/test/java/com/kista/application/service/user/UserServiceTest.java
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: 탈퇴/거절 시 RT 삭제 + 블랙리스트 즉시 등재

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 9: DevAuthController RT 쿠키 (local)

**Files:**
- Modify: `src/main/java/com/kista/adapter/in/web/DevAuthController.java`

- [ ] **Step 1: DevAuthController에 RT 쿠키 추가**

`DevAuthController.java` 수정 — `RefreshTokenCookieHelper` + `TokenUseCase` 주입 후 각 토큰 발급 엔드포인트에 RT 쿠키 설정:

```java
package com.kista.adapter.in.web;

import com.kista.adapter.in.web.dto.TokenResponse;
import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.in.web.security.RefreshTokenCookieHelper;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.UserPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "[DEV] 개발 도구", description = "로컬 프로파일 전용 — 운영 환경에서는 노출되지 않음")
@RestController
@RequestMapping("/api/auth")
@Profile("local")
@RequiredArgsConstructor
public class DevAuthController {

    private static final UUID   DEV_USER_ID    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID   DEV_ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String DEV_KAKAO_ID   = "dev-test-user";

    private final UserUseCase            userUseCase;
    private final JwtIssuerService       jwtIssuerService;
    private final UserPort               userPort;
    private final TokenUseCase           tokenUseCase;        // RT 발급
    private final RefreshTokenCookieHelper cookieHelper;      // RT 쿠키 설정

    @Operation(summary = "[DEV] UID로 사용자 승인 — 로컬 프로파일 전용")
    @ApiResponse(responseCode = "200", description = "승인 성공")
    @SecurityRequirements
    @PostMapping("/dev-approve/{userId}")
    public void devApprove(@Parameter @PathVariable UUID userId) {
        userUseCase.approve(userId);
    }

    @Operation(summary = "[DEV] 개발용 JWT 토큰 발급 — 로컬 프로파일 전용")
    @SecurityRequirements
    @PostMapping("/dev-token")
    public TokenResponse devToken(HttpServletRequest request, HttpServletResponse response) {
        User user = userUseCase.register(DEV_KAKAO_ID, "개발 테스트 유저", DEV_USER_ID);
        userUseCase.approve(user.id());
        String rawRt = tokenUseCase.issueRefreshToken(user.id(), request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(rawRt).toString());
        String at = jwtIssuerService.issue(user.id(), user.role());
        return new TokenResponse(at, "bearer", jwtIssuerService.expiresInSeconds());
    }

    @Operation(summary = "로컬 전용 ADMIN 테스트 토큰 발급")
    @SecurityRequirements
    @PostMapping("/dev-admin-token")
    public TokenResponse devAdminToken(HttpServletRequest request, HttpServletResponse response) {
        User admin = userPort.findById(DEV_ADMIN_UUID).orElseGet(() ->
                userPort.save(new User(DEV_ADMIN_UUID, "0", "dev-admin", User.UserStatus.ACTIVE, User.UserRole.ADMIN,
                        null, null, null, null, NotificationChannel.TELEGRAM, true)));
        if (admin.role() != User.UserRole.ADMIN) {
            admin = userPort.save(new User(admin.id(), admin.kakaoId(), admin.nickname(),
                    User.UserStatus.ACTIVE, User.UserRole.ADMIN, admin.telegramBotToken(), admin.telegramChatId(),
                    admin.telegramBotUsername(), admin.lastReappliedAt(),
                    admin.notificationChannel() != null ? admin.notificationChannel() : NotificationChannel.TELEGRAM,
                    admin.balanceCheckEnabled()));
        }
        String rawRt = tokenUseCase.issueRefreshToken(admin.id(), request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.issue(rawRt).toString());
        String at = jwtIssuerService.issue(admin.id(), User.UserRole.ADMIN);
        return new TokenResponse(at, "bearer", jwtIssuerService.expiresInSeconds()); // TTL을 expiresInSeconds()로 통일
    }
}
```

- [ ] **Step 2: 전체 테스트 + 컴파일 최종 확인**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 3: ArchUnit 통과 확인**

```bash
./gradlew test --tests 'com.kista.architecture.*'
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git -C /Users/phs/workspace/kista/kista-api add \
  src/main/java/com/kista/adapter/in/web/DevAuthController.java
git -C /Users/phs/workspace/kista/kista-api commit -m "feat: DevAuthController RT 쿠키 설정 (local)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Self-Review

### Spec Coverage

| 요구사항 | Task |
|---|---|
| AT 15분 | Task 5 (JwtIssuerService TTL) |
| RT 120시간 | Task 5 (TokenService.RT_TTL) |
| RTR | Task 5 (refresh: 구 삭제 → 새 발급) |
| HttpOnly 쿠키 | Task 7 (RefreshTokenCookieHelper) |
| 디바이스당 RT 1개 (device-based) | Task 5 (userAgent로 식별, DB 다중 행) |
| Upstash Redis 블랙리스트 | Task 4 (RedisBlacklistAdapter) |
| 탈퇴 즉시 차단 | Task 8 (UserCascadeDeleter) |
| 거절 즉시 차단 | Task 8 (UserService.reject) |
| 로그아웃 즉시 차단 | Task 5 (TokenService.logout) |
| SameSite=None/Lax 프로파일 분리 | Task 1 (application.yml) |
| ArchUnit 준수 | Task 2 (BlacklistUseCase in port.in) |

### 주요 설계 결정

- **RT `refresh` 실패 시 404**: `GlobalExceptionHandler`가 `NoSuchElementException → 404` 처리. UI는 4xx 전체를 "재로그인 필요"로 처리해야 함.
- **RTR 도난 감지**: 삭제된 RT 제시 시 단순 401 반환 (userId 특정 불가). 전체 세션 취소 없이 해당 요청만 차단.
- **`BlacklistUseCase` 분리**: `JwtAuthFilter(adapter.in)`가 `BlacklistPort(domain.port.out)`를 직접 참조하면 ArchUnit 위반 → `BlacklistUseCase(domain.port.in)` 경유.
