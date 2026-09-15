# kista-trading 4a단계(패키징+notify 분리) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:trading-core`가 자체 `bootJar`로 독립 기동 가능하게 하고(보안 스택 포함), 매매 텔레그램·SSE 알림을 trading-core 프로세스 자체에서 발송하도록 notify를 재설계해 4a→4b 사이 알림 유실 gap을 없앤다. DB는 아직 root와 공유한다.

**Architecture:** 보안 스택(SecurityConfig/JwtAuthFilter/JwtDecoderConfig/InternalTokenAuthFilter)을 `:shared`(`com.kista.platform.security`)로 승격해 두 bootJar가 공유. `TokenBlacklistPort`로 root의 Redis 블랙리스트 의존을 포트 역전. `TradingUserProfilePort`(기존)에 telegram 컬럼을 확장해 매매 알림 6종을 `com.kista.trading.notify`(신규)로 이관, `User` 도메인 객체 대신 `TradingUserProfile`을 받는 신규 `TradingUserNotificationPort`로 재정의. SSE는 Redis Pub/Sub(`trade.event`)로 프로세스 경계를 넘긴다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Security(OAuth2 Resource Server, JWT), Spring Data Redis, Spring Modulith, Gradle 멀티프로젝트(`:shared`/`:trading-core`/root).

**Spec:** `docs/superpowers/specs/2026-09-14-kista-trading-stage4-db-split-design.md` (4a단계 절 전체)

## Global Constraints

- 커밋 메시지: 한글, Conventional Commit 접두사(`feat(scope):`, `refactor(scope):`, `test(scope):` 등) + 명령형 제목, `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` 및 `Claude-Session` 라인 포함(세션 시스템 리마인더 값 그대로).
- 이 작업은 `worktree-kista-trading-gradle-split` 브랜치 위(HEAD `575e195a` 이후)에서 계속 진행 — main에 별도 merge하지 않는다.
- `git push`는 사용자가 명시적으로 요청할 때만.
- 신규 Java 파일: 4-space 들여쓰기, 불변 값은 record, 생성자 주입, package-private 우선. 필드/비즈니스 로직 블록에 `//` 인라인 주석(Javadoc 금지).
- 각 태스크 끝에 `./gradlew test` (전체가 아니라 관련 모듈만 — `./gradlew :shared:test`, `./gradlew :trading-core:test`, 또는 루트 `./gradlew test --tests '패턴'`)로 좁혀서 확인. 전체 스위트는 마지막 태스크에서 1회.
- Java 파일 인코딩: 서브에이전트가 import 수정 시 BOM 삽입 버그 이력 있음 — 의심되면 `grep -rl $'\xef\xbb\xbf' src trading-core/src shared/src --include="*.java"`로 확인.
- `ddl-auto: validate` — Hibernate DDL 자동 생성 없음. Flyway 마이그레이션은 기존 파일 절대 수정 금지, 새 파일은 다음 버전 번호(`V23`)부터.

---

### Task 1: `TokenBlacklistPort` 신설 + 보안 스택 4파일 `:shared` 이관

**Files:**
- Create: `shared/src/main/java/com/kista/platform/security/TokenBlacklistPort.java`
- Create (moved): `shared/src/main/java/com/kista/platform/security/SecurityConfig.java`
- Create (moved): `shared/src/main/java/com/kista/platform/security/JwtDecoderConfig.java`
- Create (moved): `shared/src/main/java/com/kista/platform/security/InternalTokenAuthFilter.java`
- Create (moved): `shared/src/main/java/com/kista/platform/security/JwtAuthFilter.java`
- Delete: `src/main/java/com/kista/user/adapter/in/web/security/SecurityConfig.java`
- Delete: `src/main/java/com/kista/user/adapter/in/web/security/JwtDecoderConfig.java`
- Delete: `src/main/java/com/kista/user/adapter/in/web/security/InternalTokenAuthFilter.java`
- Delete: `src/main/java/com/kista/user/adapter/in/web/security/JwtAuthFilter.java`
- Modify: `src/main/java/com/kista/user/application/service/BlacklistService.java`
- Modify: `shared/build.gradle.kts`
- Modify: 33개 테스트 파일(아래 Step 5의 sed 대상 — `grep -rl "com\.kista\.user\.adapter\.in\.web\.security\.\(SecurityConfig\|JwtAuthFilter\|JwtDecoderConfig\|InternalTokenAuthFilter\)" src trading-core/src`로 확정)

**Interfaces:**
- Produces: `com.kista.platform.security.TokenBlacklistPort`(`isBlacklisted(UUID)`, `isJtiBlacklisted(String)`, `roleChangedAt(UUID)`) — Task 2가 trading-core 구현체에서 이 인터페이스를 구현한다.
- Produces: `com.kista.platform.security.JwtAuthFilter`/`SecurityConfig`/`JwtDecoderConfig`/`InternalTokenAuthFilter` — Task 3이 `TradingApplication`에서 그대로 재사용(같은 클래스, 컴포넌트 스캔으로 등록).

- [ ] **Step 1: `TokenBlacklistPort` 작성**

```java
package com.kista.platform.security;

import java.time.Instant;
import java.util.UUID;

// JwtAuthFilter가 필요로 하는 읽기 전용 블랙리스트 조회 — 쓰기(add/addJti/markRoleChanged)는
// root의 BlacklistPort(로그아웃·강퇴 흐름 전용)가 그대로 담당, 이 포트는 필터의 읽기 3종만 좁힌 것.
public interface TokenBlacklistPort {
    boolean isBlacklisted(UUID userId);
    boolean isJtiBlacklisted(String jti);
    Instant roleChangedAt(UUID userId);
}
```

- [ ] **Step 2: `shared/build.gradle.kts`에 보안 의존성 추가**

`dependencies { ... }` 블록에 아래 두 줄 추가(기존 `implementation(libs.spring.boot.starter.data.jpa)` 아래):

```kotlin
    implementation(libs.spring.boot.starter.security)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // NimbusJwtDecoder
```

- [ ] **Step 3: 4개 보안 클래스를 `:shared`로 이동**

```bash
git mv src/main/java/com/kista/user/adapter/in/web/security/SecurityConfig.java shared/src/main/java/com/kista/platform/security/SecurityConfig.java
git mv src/main/java/com/kista/user/adapter/in/web/security/JwtDecoderConfig.java shared/src/main/java/com/kista/platform/security/JwtDecoderConfig.java
git mv src/main/java/com/kista/user/adapter/in/web/security/InternalTokenAuthFilter.java shared/src/main/java/com/kista/platform/security/InternalTokenAuthFilter.java
git mv src/main/java/com/kista/user/adapter/in/web/security/JwtAuthFilter.java shared/src/main/java/com/kista/platform/security/JwtAuthFilter.java
```

`SecurityConfig.java`/`JwtDecoderConfig.java`/`InternalTokenAuthFilter.java`는 `package com.kista.user.adapter.in.web.security;` 한 줄만 `package com.kista.platform.security;`로 바꾸면 끝(세 파일 다 `com.kista.user` import 0개, 이미 검증됨).

`JwtAuthFilter.java`는 패키지 선언 변경 + `import com.kista.user.application.usecase.BlacklistUseCase;`를 `import com.kista.platform.security.TokenBlacklistPort;`(같은 패키지라 실제로는 import 라인 자체를 삭제)로 바꾸고, 필드/사용처를 교체:

```java
package com.kista.platform.security;
// ... 나머지 import 동일 ...

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;
    private final TokenBlacklistPort tokenBlacklistPort; // Redis 블랙리스트 체크 (포트 역전 — user/trading 각자 구현)

    // doFilterInternal 본문의 blacklistUseCase.isJtiBlacklisted/isBlacklisted/roleChangedAt 호출을
    // tokenBlacklistPort.isJtiBlacklisted/isBlacklisted/roleChangedAt로 그대로 치환(메서드 시그니처 동일)
```

- [ ] **Step 4: root `BlacklistService`가 `TokenBlacklistPort`도 구현**

`src/main/java/com/kista/user/application/service/BlacklistService.java` 수정:

```java
package com.kista.user.application.service;

import com.kista.user.application.usecase.BlacklistUseCase;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.platform.security.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class BlacklistService implements BlacklistUseCase, TokenBlacklistPort {

    private final BlacklistPort blacklistPort;

    @Override
    public boolean isBlacklisted(UUID userId) {
        return blacklistPort.isBlacklisted(userId);
    }

    @Override
    public boolean isJtiBlacklisted(String jti) {
        return blacklistPort.isJtiBlacklisted(jti);
    }

    @Override
    public Instant roleChangedAt(UUID userId) {
        return blacklistPort.roleChangedAt(userId);
    }
}
```

(메서드 본문은 기존과 동일 — `implements` 절에 `TokenBlacklistPort` 추가만 하면 인터페이스 두 개를 같은 3개 메서드로 동시에 만족한다.)

- [ ] **Step 5: 33개 테스트 파일 import 경로 일괄 치환**

```bash
grep -rl "com\.kista\.user\.adapter\.in\.web\.security\.\(SecurityConfig\|JwtAuthFilter\|JwtDecoderConfig\|InternalTokenAuthFilter\)" src/test trading-core/src/test | while read f; do
  sed -i \
    -e 's/com\.kista\.user\.adapter\.in\.web\.security\.SecurityConfig/com.kista.platform.security.SecurityConfig/g' \
    -e 's/com\.kista\.user\.adapter\.in\.web\.security\.JwtAuthFilter/com.kista.platform.security.JwtAuthFilter/g' \
    -e 's/com\.kista\.user\.adapter\.in\.web\.security\.JwtDecoderConfig/com.kista.platform.security.JwtDecoderConfig/g' \
    -e 's/com\.kista\.user\.adapter\.in\.web\.security\.InternalTokenAuthFilter/com.kista.platform.security.InternalTokenAuthFilter/g' \
    "$f"
done
```

BOM 삽입 여부 확인: `grep -rl $'\xef\xbb\xbf' src/test trading-core/src/test --include="*.java"` (결과 있으면 `sed -i '1s/^\xef\xbb\xbf//'`로 제거).

- [ ] **Step 6: 컴파일+테스트 확인**

Run: `./gradlew compileJava compileTestJava :shared:compileJava :trading-core:compileJava :trading-core:compileTestJava`
Expected: BUILD SUCCESSFUL (아직 `TokenBlacklistPort` 구현체가 root뿐이라 trading-core는 컴파일만 확인, 부팅 테스트는 Task 2 이후)

Run: `./gradlew test --tests 'com.kista.user.*' --tests 'com.kista.admin.*'`
Expected: 기존 33개 테스트 파일 포함 전부 PASS(대상이 root 쪽 `@WebMvcTest`들)

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(security): 보안 스택을 :shared로 승격 — TokenBlacklistPort 포트 역전

SecurityConfig/JwtAuthFilter/JwtDecoderConfig/InternalTokenAuthFilter는
com.kista.user 의존이 0개임을 실측 확인, :shared로 이관해 trading-core도
자체 bootJar에서 재사용 가능하게 함. JwtAuthFilter의 유일한 외부 의존인
BlacklistUseCase는 읽기 3종만 좁힌 TokenBlacklistPort로 포트 역전 —
root BlacklistService가 그대로 구현.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 2: trading-core `RedisBlacklistAdapter` 신설

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/adapter/out/security/RedisBlacklistAdapter.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/out/security/RedisBlacklistAdapterTest.java`

**Interfaces:**
- Consumes: `com.kista.platform.security.TokenBlacklistPort`(Task 1)
- Produces: trading-core Spring 컨텍스트에 `TokenBlacklistPort` 빈 — Task 3의 `TradingApplication` 부팅 시 `JwtAuthFilter`가 이 빈을 주입받는다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.kista.trading.adapter.out.security;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class RedisBlacklistAdapterTest {

    private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
    private final RedisBlacklistAdapter adapter = new RedisBlacklistAdapter(redisTemplate);

    @Test
    void isBlacklisted_root와_동일_키_네임스페이스_사용() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.hasKey("blacklist:user:" + userId)).thenReturn(true);

        assertThat(adapter.isBlacklisted(userId)).isTrue();
    }

    @Test
    void roleChangedAt_값_없으면_null() {
        UUID userId = UUID.randomUUID();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blacklist:rolechange:" + userId)).thenReturn(null);

        assertThat(adapter.roleChangedAt(userId)).isNull();
    }

    @Test
    void roleChangedAt_epoch_seconds_파싱() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blacklist:rolechange:" + userId)).thenReturn(String.valueOf(now.getEpochSecond()));

        assertThat(adapter.roleChangedAt(userId)).isEqualTo(Instant.ofEpochSecond(now.getEpochSecond()));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.adapter.out.security.RedisBlacklistAdapterTest'`
Expected: FAIL — `RedisBlacklistAdapter` 클래스 없음

- [ ] **Step 3: 구현**

```java
package com.kista.trading.adapter.out.security;

import com.kista.platform.security.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

// root(com.kista.user.adapter.out.redis.RedisBlacklistAdapter)와 같은 Redis 인스턴스·같은 키 네임스페이스를
// 읽기 전용으로 조회 — 블랙리스트 등록(add/addJti/markRoleChanged)은 root의 로그아웃/강퇴 흐름 전용이라
// trading-core는 쓰지 않는다.
@Component
@RequiredArgsConstructor
class RedisBlacklistAdapter implements TokenBlacklistPort {

    private static final String KEY_PREFIX = "blacklist:user:";
    private static final String KEY_PREFIX_JTI = "blacklist:jti:";
    private static final String KEY_PREFIX_ROLE = "blacklist:rolechange:";
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isBlacklisted(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
    }

    @Override
    public boolean isJtiBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX_JTI + jti));
    }

    @Override
    public Instant roleChangedAt(UUID userId) {
        String v = redisTemplate.opsForValue().get(KEY_PREFIX_ROLE + userId);
        return v == null ? null : Instant.ofEpochSecond(Long.parseLong(v));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.adapter.out.security.RedisBlacklistAdapterTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/out/security trading-core/src/test/java/com/kista/trading/adapter/out/security
git commit -m "$(cat <<'EOF'
feat(trading): RedisBlacklistAdapter 신설 — TokenBlacklistPort 구현

trading-core 자체 bootJar에서 JwtAuthFilter가 쓸 블랙리스트 조회 어댑터.
root와 같은 Redis 인스턴스·같은 키 네임스페이스를 읽기 전용으로 조회한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 3: `TradingApplication` 신설 + 자체 bootJar

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/TradingApplication.java`
- Create: `trading-core/src/main/resources/application.yml`
- Modify: `trading-core/build.gradle.kts`
- Modify: `build.gradle.kts`(root)

**Interfaces:**
- Consumes: Task 1의 `:shared` 보안 스택, Task 2의 `RedisBlacklistAdapter`
- Produces: `com.kista.trading.TradingApplication` 메인 클래스 — Task 8(로컬 스모크)이 이 클래스를 기동 대상으로 삼는다.

- [ ] **Step 1: `TradingApplication.java` 작성**

```java
package com.kista.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// trading-core 전용 부팅 진입점. scanBasePackages는 root(com.kista.user/finance/admin/stats(벤치마크)/market/web)
// 패키지를 명시적으로 배제하기 위해 trading-core가 실제 소유한 최상위 패키지만 나열한다.
@SpringBootApplication(scanBasePackages = {
        "com.kista.trading",
        "com.kista.matching",
        "com.kista.broker",
        "com.kista.account",
        "com.kista.privacy",
        "com.kista.marketcalendar",
        "com.kista.sharedkernel",
        "com.kista.platform",
})
@EntityScan(basePackages = {
        "com.kista.trading",
        "com.kista.broker",
        "com.kista.account",
        "com.kista.privacy",
        "com.kista.marketcalendar",
})
@ConfigurationPropertiesScan(basePackages = {
        "com.kista.trading",
        "com.kista.broker",
        "com.kista.platform",
})
public class TradingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }
}
```

- [ ] **Step 2: `trading-core/src/main/resources/application.yml` 작성**

DB는 4a 동안 root와 동일(같은 `DB_URL`), Flyway는 4b까지 빈 디렉토리로 no-op:

```yaml
spring:
  application:
    name: kista-trading

  threads:
    virtual:
      enabled: true

  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 20000
      pool-name: HikariPool-KistaTrading
      connection-init-sql: "SET search_path TO kista, finance, reference, public"

  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100

  flyway:
    enabled: true
    locations: classpath:db/migration-trading
    baseline-on-migrate: false
    validate-on-migrate: true
    default-schema: public

  data:
    redis:
      url: ${REDIS_URL}

server:
  port: ${SERVER_PORT:8081}

internal:
  api:
    token: ${INTERNAL_API_TOKEN:}

cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}

jwt:
  signing-key: ${JWT_SIGNING_KEY}
```

`trading-core/src/main/resources/db/migration-trading/` 디렉토리를 빈 채로 생성(`.gitkeep` 파일 하나만 — Flyway가 빈 디렉토리를 no-op으로 처리하는지 Task 8 스모크에서 확인, 문제 있으면 이 태스크로 돌아와 `flyway.enabled: false`로 전환).

- [ ] **Step 3: `trading-core/build.gradle.kts` 수정**

`bootJar` 비활성화 블록 삭제(또는 `enabled = true`로 전환), `oauth2-resource-server`를 test에서 main으로 승격:

```kotlin
// 기존
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
```
위 블록을 완전히 삭제(기본값이 `enabled = true`).

`dependencies` 블록에서 `testImplementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")` 줄을 삭제하고, 그 위 `implementation(libs.spring.boot.starter.security)` 다음 줄에 추가:

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // JwtDecoderConfig(:shared)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
```

- [ ] **Step 4: root `build.gradle.kts`에서 `runtimeOnly(project(":trading-core"))` 제거**

`dependencies` 블록 맨 위:

```kotlin
// 삭제 대상
    runtimeOnly(project(":trading-core"))
    implementation(project(":shared"))
```
→
```kotlin
    implementation(project(":shared"))
```

(trading-core를 더 이상 런타임 클래스패스에 묶지 않는다 — root는 이제 정말로 자기 도메인만 담은 독립 jar가 된다. root가 trading-core 기능이 필요하면 기존처럼 내부 HTTP API로만 호출한다.)

- [ ] **Step 5: 두 서브프로젝트 모두 컴파일+bootJar 확인**

Run: `./gradlew clean compileJava :trading-core:compileJava bootJar :trading-core:bootJar`
Expected: BUILD SUCCESSFUL, `build/libs/*.jar`와 `trading-core/build/libs/*.jar` 둘 다 생성됨

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/TradingApplication.java trading-core/src/main/resources trading-core/build.gradle.kts build.gradle.kts
git commit -m "$(cat <<'EOF'
feat(trading): TradingApplication 신설 — :trading-core 자체 bootJar

scanBasePackages/@EntityScan으로 trading-core 소유 패키지만 한정, Flyway
locations를 db/migration-trading(4b까지 빈 디렉토리)으로 분리. root의
runtimeOnly(:trading-core) 제거 — 두 아티팩트가 완전히 독립된 jar가 됨.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 4: `user_notify_profile`에 telegram 컬럼 추가 + 발행 확장

**Files:**
- Create: `src/main/resources/db/migration/V23__add_telegram_to_user_notify_profile.sql`
- Modify: `shared/src/main/java/com/kista/sharedkernel/UserNotifyProfileChangedEvent.java`
- Modify: `src/main/java/com/kista/user/application/service/UserNotifyProfilePublisher.java`
- Modify: `src/main/java/com/kista/user/application/service/UserProfileService.java`
- Modify: `trading-core/src/main/java/com/kista/trading/domain/model/TradingUserProfile.java`
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/UserNotifyProfileEntity.java`
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/UserNotifyProfilePersistenceAdapter.java`
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/UserNotifyProfileSyncListener.java`
- Test: 기존 `UserNotifyProfilePublisherTest`/`UserNotifyProfileSyncListenerTest`/`UserNotifyProfilePersistenceAdapterTest`(파일명은 구현 착수 시 `find . -iname "UserNotifyProfile*Test.java"`로 확정) 수정

**Interfaces:**
- Produces: `TradingUserProfile(userId, notificationPrefs, balanceCheckEnabled, telegramBotToken, chatId)` — Task 6이 이 타입을 파라미터로 받는 `TradingUserNotificationPort`를 정의할 때 사용.

- [ ] **Step 1: Flyway 마이그레이션**

```sql
-- kista.user_notify_profile에 telegram 발송에 필요한 두 컬럼 추가.
-- root(users.telegram_bot_token/telegram_chat_id, AES-256)와 동일 규격 — VARCHAR(512).
ALTER TABLE kista.user_notify_profile
    ADD COLUMN telegram_bot_token VARCHAR(512),
    ADD COLUMN chat_id VARCHAR(64);

-- 기존 행 백필 — 비어 있으면 trading-core가 매매 텔레그램 알림을 못 보낸다(무증상 아님, 발송 시도 시
-- TelegramHttpClient.sendMessage가 botToken null → 조용히 스킵하므로 알림이 그냥 안 나감).
UPDATE kista.user_notify_profile p
SET telegram_bot_token = u.telegram_bot_token,
    chat_id = u.telegram_chat_id
FROM public.users u
WHERE u.id = p.user_id;
```

- [ ] **Step 2: `UserNotifyProfileChangedEvent`(sharedkernel) 필드 추가**

기존 레코드에 `telegramBotToken`/`chatId` 2개 필드 추가(record 컴포넌트 순서 끝에 추가 — 기존 필드 순서 유지):

```java
public record UserNotifyProfileChangedEvent(
        UUID userId,
        Map<NotificationType, Boolean> notificationPrefs,
        boolean balanceCheckEnabled,
        boolean active,
        String telegramBotToken, // AES 평문(이벤트 페이로드 — EPR 직렬화 시 암호화 유지 필요, 아래 확인)
        String chatId
) {}
```

**주의**: 기존 다른 필드(`notificationPrefs` 등)와 마찬가지로 평문으로 실리는지, 혹은 root `User` 자체가 이미 암호화된 값을 들고 있어 이벤트에도 암호문이 그대로 실리는지 구현 착수 시 `User.telegramBotToken` 필드 주석("AES-256 암호화 저장")과 `UserPersistenceAdapter`의 암복호화 지점을 재확인 — 암호문 그대로 이벤트에 실어 trading-core DB 컬럼에도 암호문으로 저장하는 것이 맞다(constraints.md "AES-256 암호화 위치: persistence adapter 경계에서만" 원칙 위반 소지 있으면 이 스텝에서 막고 사용자에게 확인).

- [ ] **Step 3: `UserNotifyProfilePublisher` 수정**

`publish()` 메서드가 `User`를 안 받으므로, telegram 값을 채우려면 두 호출부(`publishStatusChanged`/`publishSettingsChanged`)가 `User`를 조회하도록 조정:

```java
private void publish(UUID userId, UserSettings settings, boolean active, String telegramBotToken, String chatId) {
    eventPublisher.publishEvent(new UserNotifyProfileChangedEvent(
            userId, settings.notificationPrefs(), settings.balanceCheckEnabled(), active,
            telegramBotToken, chatId));
}

void publishStatusChanged(User user) {
    publish(user.id(), userSettingsPort.findOrDefault(user.id()), user.status() == UserStatus.ACTIVE,
            user.telegramBotToken(), user.telegramChatId());
}

void publishSettingsChanged(UserSettings settings) {
    User user = userPort.findById(settings.userId()).orElse(null);
    boolean active = user != null && user.status() == UserStatus.ACTIVE;
    publish(settings.userId(), settings, active,
            user == null ? null : user.telegramBotToken(),
            user == null ? null : user.telegramChatId());
}
```

- [ ] **Step 4: `UserProfileService`가 telegram 변경 시에도 발행**

`withTelegram(...)` 결과를 저장하는 지점 뒤에 `userNotifyProfilePublisher.publishStatusChanged(savedUser)` 호출 추가(구현 착수 시 정확한 메서드명·저장 지점은 `UserProfileService.java`를 Read해 확인 — 이미 주입돼 있는지, 신규 주입 필요한지 파악).

- [ ] **Step 5: `TradingUserProfile`/persistence 3파일 확장**

```java
public record TradingUserProfile(
        UUID userId,
        Map<NotificationType, Boolean> notificationPrefs,
        boolean balanceCheckEnabled,
        String telegramBotToken,
        String chatId
) {
    public boolean isNotificationEnabled(NotificationType type) {
        return notificationPrefs.getOrDefault(type, true);
    }
}
```

`UserNotifyProfileEntity`에 `telegramBotToken`/`chatId` 컬럼 매핑 추가(`@Column(name = "telegram_bot_token", length = 512)`/`@Column(name = "chat_id", length = 64)`), `UserNotifyProfilePersistenceAdapter`의 엔티티↔도메인 변환에 두 필드 추가, `UserNotifyProfileSyncListener`가 이벤트의 두 신규 필드를 엔티티에 반영.

- [ ] **Step 6: 관련 테스트 갱신 + 실행**

기존 3개 테스트 파일에 새 필드를 채운 fixture로 갱신.

Run: `./gradlew test --tests 'com.kista.user.application.service.UserNotifyProfilePublisherTest' :trading-core:test --tests '*UserNotifyProfile*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/db/migration/V23__add_telegram_to_user_notify_profile.sql shared/src/main/java/com/kista/sharedkernel/UserNotifyProfileChangedEvent.java src/main/java/com/kista/user/application/service/UserNotifyProfilePublisher.java src/main/java/com/kista/user/application/service/UserProfileService.java trading-core/src/main/java/com/kista/trading/domain/model/TradingUserProfile.java trading-core/src/main/java/com/kista/trading/adapter/out/persistence
git commit -m "$(cat <<'EOF'
feat(trading): user_notify_profile에 telegram 컬럼 추가

trading-core가 매매 텔레그램 알림을 직접 보내려면 봇 토큰·chatId 복제가
필요함(기존엔 없었음, 실측 확인). V23 마이그레이션 + 발행/구독 3단 갱신.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 5: `TradingUserNotificationPort` 신설 + root `UserNotificationPort` 축소

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/notify/application/port/output/TradingUserNotificationPort.java`
- Modify: `src/main/java/com/kista/notify/application/port/output/UserNotificationPort.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/CompositeUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/FcmAdapter.java`

**Interfaces:**
- Produces: `TradingUserNotificationPort`(`notifyTradingReport`/`notifyCycleCompleted`/`notifyNewCycleStarted`/`notifyInsufficientBalance`/`notifyError`/`notifyBatchInterrupted`/`notifyMarketOpen`/`notifyMarketClose`, 전부 `TradingUserProfile` 파라미터) — Task 6이 구현체(`TradingUserNotificationAdapter`)와 소비자(4개 이관 notifier)를 만든다.

- [ ] **Step 1: `TradingUserNotificationPort` 작성**

```java
package com.kista.trading.notify.application.port.output;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingReport;
import com.kista.trading.domain.model.TradingUserProfile;

import java.math.BigDecimal;

// root UserNotificationPort의 매매 관련 8개 메서드를 trading-core 소유로 재정의 — User 도메인 객체
// 대신 trading-core가 실제로 가진 TradingUserProfile을 받는다(실측: User는 root 전용, trading-core는
// 소유하지 않음).
public interface TradingUserNotificationPort {
    void notifyTradingReport(TradingUserProfile profile, String accountNickname, TradingReport report);
    void notifyCycleCompleted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                               StrategyTicker ticker, StrategyCycleSeedType cycleSeedType);
    void notifyNewCycleStarted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                               StrategyTicker ticker, BigDecimal initialUsdDeposit);
    void notifyInsufficientBalance(TradingUserProfile profile, String accountNickname, StrategyType strategyType, StrategyTicker ticker);
    void notifyError(TradingUserProfile profile, Exception e);
    void notifyBatchInterrupted(TradingUserProfile profile, String accountNickname);
    void notifyMarketOpen(TradingUserProfile profile);
    void notifyMarketClose(TradingUserProfile profile);
}
```

- [ ] **Step 2: root `UserNotificationPort` 축소**

8개 매매 메서드를 삭제, 5개만 남긴다:

```java
package com.kista.notify.application.port.output;

import com.kista.user.domain.model.User;

public interface UserNotificationPort {
    void notifyNewUser(User user);
    void notifyAutoApprovedUser(User user);
    void notifyApproved(User user);
    void notifyRejected(User user);
    void notifyFinanceRegistrationReminder(User user, String month);
}
```

- [ ] **Step 3: `CompositeUserNotificationAdapter` 축소**

`route()` 델리게이트 중 삭제된 8개 메서드 오버라이드 제거, `notifyFinanceRegistrationReminder`만 사용자 채널 라우팅으로 남긴다:

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Primary
@Component
@RequiredArgsConstructor
public class CompositeUserNotificationAdapter implements UserNotificationPort {

    private final TelegramUserNotificationAdapter telegram;
    private final FcmAdapter fcm;

    @Override public void notifyNewUser(User user)          { telegram.notifyNewUser(user); }
    @Override public void notifyAutoApprovedUser(User user) { telegram.notifyAutoApprovedUser(user); }
    @Override public void notifyApproved(User user)         { route(user, p -> p.notifyApproved(user)); }
    @Override public void notifyRejected(User user)         { route(user, p -> p.notifyRejected(user)); }
    @Override public void notifyFinanceRegistrationReminder(User user, String month) { route(user, p -> p.notifyFinanceRegistrationReminder(user, month)); }

    private void route(User user, Consumer<UserNotificationPort> action) {
        if (user.notificationChannel().includesTelegram()) action.accept(telegram);
        if (user.notificationChannel().includesFcm())      action.accept(fcm);
    }
}
```

`TelegramUserNotificationAdapter`/`FcmAdapter`도 `UserNotificationPort`의 삭제된 8개 메서드 구현을 제거(컴파일 에러로 드러남 — `@Override` 남은 메서드만 유지).

- [ ] **Step 4: 컴파일 확인 (아직 4개 notifier가 옛 시그니처를 부르므로 root는 컴파일 실패 — 의도된 중간 상태)**

Run: `./gradlew compileJava 2>&1 | grep -E "error:|ERROR"`
Expected: `TradingAlertNotifier`/`TradingReportNotifier`/`CycleLifecycleNotifier`/`CycleEndedNotifier` 4개 파일에서 `userNotificationPort.notifyXxx` 관련 컴파일 에러 — Task 6에서 이 4개를 이관하며 해소한다. 이 태스크는 여기서 커밋하지 않고 Task 6과 하나의 작업 단위로 묶는다(중간에 컴파일 깨진 상태를 커밋하면 `git bisect`가 깨짐).

---

### Task 6: 매매 알림 4종 이관(`TradingAlertNotifier`/`TradingReportNotifier`/`CycleLifecycleNotifier`/`CycleEndedNotifier`) + `TradingUserNotificationAdapter`

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TradingAlertNotifier.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TradingReportNotifier.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/CycleLifecycleNotifier.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/CycleEndedNotifier.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TradingUserNotificationAdapter.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/application/port/output/TradingNotifyPort.java`
- Delete: `src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java`
- Delete: `src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java`
- Delete: `src/main/java/com/kista/notify/adapter/out/gateway/CycleLifecycleNotifier.java`
- Delete: `src/main/java/com/kista/notify/adapter/out/gateway/CycleEndedNotifier.java`
- Test: 기존 4개 `*NotifierTest`를 `trading-core/src/test/java/com/kista/trading/notify/adapter/out/gateway/`로 이동+갱신

**Interfaces:**
- Consumes: `TradingUserNotificationPort`(Task 5), `TradingUserProfilePort`(기존), `TradingNotifyPort`(이 태스크 신설 — 관리자 알림, `NotifyPort`의 trading-core 판)
- Produces: 4개 notifier 클래스 — Task 7이 `TradingUserNotificationAdapter`/`TradingNotifyPort` 구현체를 채운다.

- [ ] **Step 1: `TradingNotifyPort`(관리자 알림, root `NotifyPort`의 trading-core 판) 작성**

기존 root `NotifyPort` 인터페이스를 Read해 `notifyError(Exception)`/`notifyInsufficientBalance(...)`/`notifyMarketClosed()`/`notifyInfo(String)` 등 이 4개 notifier가 실제로 쓰는 메서드만(전체 복제 아님 — ISP) trading-core 소유로 정의:

```java
package com.kista.trading.notify.application.port.output;

import com.kista.sharedkernel.StrategyTicker;
import java.math.BigDecimal;

// 관리자 텔레그램 알림 — root NotifyPort의 trading-core 판. Gradle 컴파일 경계(root→trading-core만
// 단방향) 때문에 root 타입을 참조할 수 없어 trading-core가 자기 필요분만 좁혀 재정의한다.
public interface TradingNotifyPort {
    void notifyError(Exception e);
    void notifyInsufficientBalance(BigDecimal holdings, BigDecimal usdDeposit, StrategyTicker ticker);
    void notifyMarketClosed();
}
```

- [ ] **Step 2: 4개 notifier를 trading-core로 이관하며 시그니처 교체**

`TradingAlertNotifier.java`(신규 위치)는 `UserPort.findByIdOrThrow` → `TradingUserProfilePort.findByUserId(...).orElseThrow(...)`, `NotifyPort` → `TradingNotifyPort`, `UserNotificationPort` → `TradingUserNotificationPort`로 치환:

```java
package com.kista.trading.notify.adapter.out.gateway;

import com.kista.trading.application.port.output.TradingUserProfilePort;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingNotifyPort;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import com.kista.sharedkernel.BatchInterruptedEvent;
import com.kista.sharedkernel.InsufficientBalanceEvent;
import com.kista.sharedkernel.MarketClosedEvent;
import com.kista.sharedkernel.MarketCloseEvent;
import com.kista.sharedkernel.MarketOpenEvent;
import com.kista.sharedkernel.TradingErrorEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.NoSuchElementException;

@Component
@RequiredArgsConstructor
public class TradingAlertNotifier {

    private final TradingNotifyPort notifyPort;
    private final TradingUserNotificationPort userNotificationPort;
    private final TradingUserProfilePort userProfilePort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingError(TradingErrorEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyError(new RuntimeException(event.message()));
        } else {
            TradingUserProfile profile = requireProfile(event.userId());
            userNotificationPort.notifyError(profile, new RuntimeException(event.message()));
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyInsufficientBalance(event.holdings(), event.usdDeposit(), event.ticker());
        } else {
            TradingUserProfile profile = requireProfile(event.userId());
            userNotificationPort.notifyInsufficientBalance(profile, event.accountNickname(), event.strategyType(), event.ticker());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClosed(MarketClosedEvent event) {
        notifyPort.notifyMarketClosed();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketOpen(MarketOpenEvent event) {
        userNotificationPort.notifyMarketOpen(requireProfile(event.userId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClose(MarketCloseEvent event) {
        userNotificationPort.notifyMarketClose(requireProfile(event.userId()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBatchInterrupted(BatchInterruptedEvent event) {
        userNotificationPort.notifyBatchInterrupted(requireProfile(event.userId()), event.accountNickname());
    }

    private TradingUserProfile requireProfile(java.util.UUID userId) {
        return userProfilePort.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("user_notify_profile 없음: " + userId));
    }
}
```

`TradingReportNotifier`/`CycleLifecycleNotifier`/`CycleEndedNotifier`도 같은 패턴(`UserPort.findByIdOrThrow` → `requireProfile` 헬퍼, `User` → `TradingUserProfile`)으로 이관. `TradingReportNotifier`는 추가로 `RealtimeNotificationPort.notifyTrade` 호출을 Task 8(trade.event pub/sub)까지 임시로 남겨두되 import만 `com.kista.trading.notify.application.port.output.TradingRealtimeNotificationPort`(이 태스크에서 시그니처만 만들고 구현은 Task 8) 신규 인터페이스로 교체:

```java
package com.kista.trading.notify.application.port.output;

import com.kista.trading.notify.domain.model.TradeEventView;
import java.util.UUID;

public interface TradingRealtimeNotificationPort {
    void notifyTrade(UUID userId, TradeEventView event);
}
```

`TradeEventView`도 `com.kista.notify.domain.model`에서 `com.kista.trading.notify.domain.model`로 복제(own-type, 필드 그대로 — 기존 root 파일 내용을 그대로 옮겨 패키지만 변경).

- [ ] **Step 3: root에서 4개 파일 삭제 + import 정리**

```bash
git rm src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java
git rm src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java
git rm src/main/java/com/kista/notify/adapter/out/gateway/CycleLifecycleNotifier.java
git rm src/main/java/com/kista/notify/adapter/out/gateway/CycleEndedNotifier.java
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava :trading-core:compileJava`
Expected: BUILD SUCCESSFUL (Task 5에서 깨졌던 root 컴파일이 이제 복구됨)

- [ ] **Step 5: 4개 notifier 테스트 이관 + 갱신**

기존 `src/test/.../TradingAlertNotifierTest.java` 등 4개 파일을 `trading-core/src/test/java/com/kista/trading/notify/adapter/out/gateway/`로 `git mv`, mock 대상을 `UserPort`→`TradingUserProfilePort`, `User` fixture→`TradingUserProfile` fixture, `NotifyPort`→`TradingNotifyPort`, `UserNotificationPort`→`TradingUserNotificationPort`로 갱신.

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.notify.*'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(notify): 매매 알림 4종을 trading-core로 이관 — TradingUserNotificationPort

TradingAlertNotifier/TradingReportNotifier/CycleLifecycleNotifier/
CycleEndedNotifier가 root의 User/UserNotificationPort/NotifyPort 대신
trading-core 소유 TradingUserProfile/TradingUserNotificationPort/
TradingNotifyPort를 쓰도록 재작성. 프로세스 분리 후에도 매매 알림이
발행자와 같은 프로세스에서 발송되게 하기 위함(4a→4b 사이 알림 유실
gap 방지).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 7: trading-core Telegram 전송 유틸 복제 + `TradingUserNotificationAdapter`/`TradingNotifyPort` 구현 + FCM 위임 이벤트

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TelegramHttpClient.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TelegramConfig.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TelegramProperties.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TradingUserNotificationAdapter.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/TradingNotifyAdapter.java`
- Create: `shared/src/main/java/com/kista/sharedkernel/UserPushNotificationRequestedEvent.java`
- Create: `src/main/java/com/kista/notify/adapter/out/gateway/PushNotificationRelayListener.java`

**Interfaces:**
- Consumes: `TradingUserNotificationPort`/`TradingNotifyPort`(Task 5/6)
- Produces: `UserPushNotificationRequestedEvent(UUID userId, String title, String body)`(sharedkernel) — root의 `PushNotificationRelayListener`가 구독해 기존 `FcmAdapter`로 발송.

- [ ] **Step 1: root `TelegramHttpClient`/`TelegramConfig`/`TelegramProperties`를 Read해 trading-core에 최소 복제**

기존 root 파일들(`src/main/java/com/kista/notify/adapter/out/gateway/TelegramHttpClient.java` 등)을 그대로 Read하고, 패키지 선언만 `com.kista.trading.notify.adapter.out.gateway`로 바꿔 trading-core에 새 파일로 생성(admin bot 관련 필드·메서드가 있으면 trading-core는 쓰지 않으므로 제외 — `sendMessage`/`sendWithInlineKeyboard` 두 메서드와 `RestClient` 빈 설정만 필요).

- [ ] **Step 2: `UserPushNotificationRequestedEvent`(sharedkernel) 작성**

```java
package com.kista.sharedkernel;

import java.util.UUID;

// trading-core가 FCM 발송을 root에 위임할 때 쓰는 이벤트 — fcm_device_tokens가 users FK라
// FCM 발송 자체는 root(FcmAdapter)가 계속 담당하고, trading-core는 이벤트만 던진다.
public record UserPushNotificationRequestedEvent(UUID userId, String title, String body) {
}
```

- [ ] **Step 3: `TradingUserNotificationAdapter` 구현**

```java
package com.kista.trading.notify.adapter.out.gateway;

import com.kista.sharedkernel.StrategyCycleSeedType;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.TradingReport;
import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import com.kista.trading.domain.model.TradingUserProfile;
import com.kista.trading.notify.application.port.output.TradingUserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// Telegram은 직접 발송, FCM은 root에 이벤트로 위임(fcm_device_tokens가 users FK라 trading-core가
// 직접 조회할 수 없음).
@Component
@RequiredArgsConstructor
class TradingUserNotificationAdapter implements TradingUserNotificationPort {

    private final TelegramHttpClient telegramHttpClient;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void notifyTradingReport(TradingUserProfile profile, String accountNickname, TradingReport report) {
        String text = "[" + accountNickname + "] 매매 리포트\n" + report;
        send(profile, "매매 리포트", text);
    }

    @Override
    public void notifyCycleCompleted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                      StrategyTicker ticker, StrategyCycleSeedType cycleSeedType) {
        send(profile, "사이클 종료", "[" + accountNickname + "] " + strategyType + "/" + ticker + " 사이클 종료");
    }

    @Override
    public void notifyNewCycleStarted(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                       StrategyTicker ticker, BigDecimal initialUsdDeposit) {
        send(profile, "새 사이클 시작", "[" + accountNickname + "] " + strategyType + "/" + ticker + " 신규 사이클 시작 (예수금 " + initialUsdDeposit + ")");
    }

    @Override
    public void notifyInsufficientBalance(TradingUserProfile profile, String accountNickname, StrategyType strategyType, StrategyTicker ticker) {
        send(profile, "예수금 부족", "[" + accountNickname + "] " + strategyType + "/" + ticker + " 예수금 부족");
    }

    @Override
    public void notifyError(TradingUserProfile profile, Exception e) {
        send(profile, "매매 오류", e.getMessage());
    }

    @Override
    public void notifyBatchInterrupted(TradingUserProfile profile, String accountNickname) {
        send(profile, "배치 중단", "[" + accountNickname + "] 스케쥴러 인터럽트 발생");
    }

    @Override
    public void notifyMarketOpen(TradingUserProfile profile) {
        send(profile, "장 개시", "장이 열렸습니다");
    }

    @Override
    public void notifyMarketClose(TradingUserProfile profile) {
        send(profile, "장 마감", "장이 마감됐습니다");
    }

    private void send(TradingUserProfile profile, String title, String body) {
        telegramHttpClient.sendMessage(profile.chatId(), body, profile.telegramBotToken());
        eventPublisher.publishEvent(new UserPushNotificationRequestedEvent(profile.userId(), title, body));
    }
}
```

**주의**: root의 기존 `TelegramUserNotificationAdapter`가 각 알림 유형별로 어떤 정확한 문구·인라인 버튼을 쓰는지 구현 착수 시 Read해서 그대로 재현할 것(위 텍스트는 골격 예시 — 기존 사용자에게 가던 실제 메시지 포맷을 임의로 바꾸면 안 됨).

- [ ] **Step 4: `TradingNotifyAdapter`(관리자 알림) 구현**

root `TelegramAdapter`(admin bot)를 참고해 trading-core용 최소 구현 — admin bot 토큰(`ADMIN_TELEGRAM_BOT_TOKEN`/`ADMIN_TELEGRAM_CHAT_ID` 등 기존 root가 쓰는 환경변수명을 구현 착수 시 `TelegramProperties`에서 확인 후 그대로 재사용):

```java
package com.kista.trading.notify.adapter.out.gateway;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.notify.application.port.output.TradingNotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
class TradingNotifyAdapter implements TradingNotifyPort {

    private final TelegramHttpClient telegramHttpClient;
    private final TelegramProperties telegramProperties; // adminChatId/adminBotToken

    @Override
    public void notifyError(Exception e) {
        telegramHttpClient.sendMessage(telegramProperties.adminChatId(), "매매 오류: " + e.getMessage(), telegramProperties.adminBotToken());
    }

    @Override
    public void notifyInsufficientBalance(BigDecimal holdings, BigDecimal usdDeposit, StrategyTicker ticker) {
        telegramHttpClient.sendMessage(telegramProperties.adminChatId(),
                "예수금 부족: " + ticker + " holdings=" + holdings + " usdDeposit=" + usdDeposit, telegramProperties.adminBotToken());
    }

    @Override
    public void notifyMarketClosed() {
        telegramHttpClient.sendMessage(telegramProperties.adminChatId(), "장 휴장", telegramProperties.adminBotToken());
    }
}
```

- [ ] **Step 5: root `PushNotificationRelayListener` 신설**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.sharedkernel.UserPushNotificationRequestedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// trading-core가 위임한 FCM 발송 요청을 받아 기존 FcmAdapter로 전달 —
// 4a에선 같은 프로세스 내 이벤트(같은 DB 공유), 4b DB 분리 이후엔 Redis pub/sub으로 교체 예정.
@Component
@RequiredArgsConstructor
class PushNotificationRelayListener {

    private final FcmAdapter fcmAdapter;
    private final UserPort userPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onUserPushNotificationRequested(UserPushNotificationRequestedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        if (user.notificationChannel().includesFcm()) {
            fcmAdapter.sendGeneric(user, event.title(), event.body()); // FcmAdapter에 신규 메서드 필요 — 기존 시그니처 확인 후 맞춰 추가
        }
    }
}
```

**주의**: 이 리스너는 4a 한정 임시 구현이다 — 4a는 아직 한 프로세스가 아니라 **두 프로세스**이므로, `ApplicationEventPublisher.publishEvent`는 trading-core 프로세스 로컬이라 root가 이 이벤트를 못 받는다. 즉 이 Step은 **틀렸다** — Task 8에서 Redis pub/sub으로 발행/구독하도록 다시 손대야 한다. 이 태스크에서는 클래스 골격과 `FcmAdapter.sendGeneric` 신규 메서드만 만들고, 실제 배선(발행측을 Redis publish로, 구독측을 Redis subscribe로)은 Task 8에서 완성한다 — 커밋 메시지에 "미배선, Task 8에서 Redis로 연결" 명시.

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew compileJava :trading-core:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(trading): TradingUserNotificationAdapter/TradingNotifyAdapter 구현

Telegram 발송 유틸을 trading-core에 최소 복제(admin bot과 공유하는
TelegramHttpClient는 root 잔류라 통째로 옮길 수 없음). FCM 절반은
UserPushNotificationRequestedEvent로 root에 위임 — 단, 이 커밋 시점엔
로컬 이벤트라 프로세스를 못 넘는다. 실제 배선은 다음 태스크(Redis
pub/sub)에서 완성한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 8: Redis Pub/Sub 배선 — `trade.event` + `UserPushNotificationRequestedEvent`

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/RedisTradeEventPublisher.java`
- Create: `trading-core/src/main/java/com/kista/trading/notify/adapter/out/gateway/RedisPushNotificationPublisher.java`
- Create: `src/main/java/com/kista/notify/adapter/out/sse/RedisTradeEventSubscriber.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/PushNotificationRelayListener.java` (Redis 구독으로 전환)
- Create: `shared/src/main/java/com/kista/platform/redis/RedisPubSubConfig.java`(양쪽이 재사용할 채널명 상수 + `RedisMessageListenerContainer` 공통 설정)
- Test: `trading-core/src/test/java/com/kista/trading/notify/adapter/out/gateway/RedisTradeEventPublisherTest.java`, `src/test/java/com/kista/notify/adapter/out/sse/RedisTradeEventSubscriberTest.java`

**Interfaces:**
- Consumes: `TradingRealtimeNotificationPort`(Task 6), `UserPushNotificationRequestedEvent`(Task 7)
- Produces: Redis 채널 `"trade.event"`/`"user.push-notification.requested"` — 양쪽 앱이 같은 채널명 상수(`RedisPubSubConfig.TRADE_EVENT_CHANNEL`)를 `:shared`에서 공유.

- [ ] **Step 1: `RedisPubSubConfig`(공유 채널명 상수) 작성**

```java
package com.kista.platform.redis;

// 두 bootJar(root/trading-core)가 같은 채널명 문자열을 쓰도록 강제하는 상수 홀더 — 오타로 인한
// 발행/구독 채널 불일치를 컴파일 타임에 방지.
public final class RedisPubSubConfig {
    public static final String TRADE_EVENT_CHANNEL = "trade.event";
    public static final String PUSH_NOTIFICATION_CHANNEL = "user.push-notification.requested";

    private RedisPubSubConfig() {
    }
}
```

- [ ] **Step 2: trading-core 발행 측 — `RedisTradeEventPublisher`**

```java
package com.kista.trading.notify.adapter.out.gateway;

import com.kista.platform.redis.RedisPubSubConfig;
import com.kista.trading.notify.application.port.output.TradingRealtimeNotificationPort;
import com.kista.trading.notify.domain.model.TradeEventView;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// fire-and-forget — SSE 유실은 UI 일시 끊김 정도라 Redis Stream(내구성)이 아니라 Pub/Sub 사용.
@Component
@RequiredArgsConstructor
class RedisTradeEventPublisher implements TradingRealtimeNotificationPort {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void notifyTrade(UUID userId, TradeEventView event) {
        String payload = objectMapper.writeValueAsString(new TradeEventPayload(userId, event));
        redisTemplate.convertAndSend(RedisPubSubConfig.TRADE_EVENT_CHANNEL, payload);
    }

    private record TradeEventPayload(UUID userId, TradeEventView event) {
    }
}
```

- [ ] **Step 3: root 구독 측 — `RedisTradeEventSubscriber`**

```java
package com.kista.notify.adapter.out.sse;

import com.kista.platform.redis.RedisPubSubConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTradeEventSubscriber implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final TradeSseEmitterRegistry tradeSseEmitterRegistry;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void subscribe() {
        listenerContainer.addMessageListener(this, new ChannelTopic(RedisPubSubConfig.TRADE_EVENT_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            TradeEventPayload payload = objectMapper.readValue(message.getBody(), TradeEventPayload.class);
            tradeSseEmitterRegistry.send(payload.userId(), payload.event());
        } catch (Exception e) {
            log.error("trade.event 역직렬화 실패", e);
        }
    }

    private record TradeEventPayload(UUID userId, com.kista.notify.domain.model.TradeEventView event) {
    }
}
```

(`RedisMessageListenerContainer` 빈이 root에 없으면 `:shared`의 `RedisPubSubConfig`에 `@Bean RedisMessageListenerContainer`를 추가 — 이미 `spring-boot-starter-data-redis`가 두 앱 모두에 있으므로 `RedisConnectionFactory`는 자동 구성됨.)

- [ ] **Step 4: `PushNotificationRelayListener`를 Redis 구독으로 전환**

Task 7의 `@TransactionalEventListener` 버전을 삭제하고, 위 `RedisTradeEventSubscriber`와 같은 패턴의 `MessageListener` 구현으로 교체(채널 `RedisPubSubConfig.PUSH_NOTIFICATION_CHANNEL`). trading-core 쪽엔 대응하는 `RedisPushNotificationPublisher`(`TradingUserNotificationAdapter`의 `eventPublisher.publishEvent(...)` 호출을 `redisTemplate.convertAndSend(RedisPubSubConfig.PUSH_NOTIFICATION_CHANNEL, ...)`로 교체) 신설.

- [ ] **Step 5: 로컬 Redis로 왕복 테스트**

```java
// trading-core 쪽 — 실제 Redis 필요, @Tag("integration")
@Tag("integration")
class RedisTradeEventPublisherTest {
    // Testcontainers Redis 컨테이너 기동 후 publish → StringRedisTemplate으로 직접 subscribe해
    // payload가 기대한 JSON 구조인지 확인 (프로젝트 기존 통합테스트 패턴 — DataJpaTestBase 아닌
    // 별도 RedisTestBase가 있는지 구현 착수 시 확인, 없으면 신설)
}
```

Run: `./gradlew integration --tests '*RedisTradeEventPublisherTest*' --tests '*RedisTradeEventSubscriberTest*'`
Expected: PASS(로컬 Redis 컨테이너 기동 확인 — `docker compose up -d redis` 선행 필요하면 명시)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(trading): trade.event/push-notification Redis Pub/Sub 배선

프로세스가 갈라진 뒤에도 실시간 매매 SSE와 FCM 위임이 동작하도록
Redis Pub/Sub 채널 2개 배선. 둘 다 fire-and-forget(내구성 불필요 —
SSE는 UI 일시 끊김 정도, FCM 위임도 재시도 없이 최선 노력).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 9: kista-ui 라우팅 분기 (별도 레포 커밋)

**Files** (레포: `../kista-ui`):
- Modify: `shared/lib/proxy/createProxyRoute.ts`
- Modify: `shared/lib/env.ts`
- Modify: 8개 route.ts — 구현 착수 시 `grep -rl "createProxyRoute" ../kista-ui/app/api --include=route.ts`로 전수 확인 후 trading-core 소유 컨트롤러(`trading-cycles`/`accounts`/`stats`/`backtest`/`dashboard`/`toss-statistics`/`statistics`/`order`)에 해당하는 파일만 수정

**Interfaces:**
- Consumes: 없음(프론트 전용)
- Produces: `TRADING_API_BASE_URL` env var(로컬 `.env.local`/배포 환경변수)

- [ ] **Step 1: `shared/lib/env.ts`에 trading base URL 추가**

```typescript
export function getTradingApiBaseUrl(): string {
  const url = process.env.TRADING_API_BASE_URL || process.env.NEXT_PUBLIC_TRADING_API_BASE_URL
  if (!url) throw new Error('TRADING_API_BASE_URL is not configured')
  return url
}
```

- [ ] **Step 2: `createProxyRoute.ts`에 `target` 옵션 추가**

```typescript
export type CreateProxyRouteOptions = {
  basePath: string
  requireAuth?: boolean
  target?: 'api' | 'trading' // 생략 시 'api'(기존 동작 그대로)
}

// proxy() 함수 내부, url 조립 직전:
const baseUrl = opts.target === 'trading' ? getTradingApiBaseUrl() : getApiBaseUrl()
const url = `${baseUrl}${opts.basePath}${subPath}${request.nextUrl.search}`
```

- [ ] **Step 3: 8개 route.ts에 `target: 'trading'` 추가**

각 파일의 `createProxyRoute({ basePath: '/api/...' })` 호출에 `target: 'trading'` 추가. 예:

```typescript
export const { GET, POST, PUT, PATCH, DELETE } = createProxyRoute({
  basePath: '/api/trading-cycles',
  target: 'trading',
})
```

- [ ] **Step 4: 기존 테스트 갱신**

`createProxyRoute.test.ts`에 `target: 'trading'` 케이스 추가(기존 mock을 `getApiBaseUrl`/`getTradingApiBaseUrl` 둘 다 stub하도록 갱신).

Run(kista-ui 레포에서): `npm test -- createProxyRoute`
Expected: PASS

- [ ] **Step 5: 로컬 `.env.local`에 `TRADING_API_BASE_URL=http://localhost:8081` 추가(문서화만 — 실제 `.env.local`은 gitignored)**

- [ ] **Step 6: 커밋 (kista-ui 레포)**

```bash
cd ../kista-ui
git add shared/lib/env.ts shared/lib/proxy/createProxyRoute.ts shared/lib/proxy/createProxyRoute.test.ts app/api
git commit -m "$(cat <<'EOF'
feat(proxy): trading-core 직접 호출 라우팅 분기 추가

kista-api 4a단계(패키징 분리)로 trading-cycles/accounts/stats/backtest 등
8개 엔드포인트가 별도 프로세스(kista-trading)로 이동. createProxyRoute에
target 옵션을 추가해 base URL을 분기.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ETHzugKWC3tuiESX4sfhyJ
EOF
)"
```

---

### Task 10: 로컬 2-프로세스 스모크 테스트 + 전체 테스트 스위트 (4a 최종 게이트)

**Files:** 없음(검증 전용 태스크)

- [ ] **Step 1: 전체 테스트 스위트 1회**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL(사전 존재 무관 결함 있으면 사용자에게 별도 보고, 이 플랜 범위 밖 이슈로 취급)

Run: `./gradlew :trading-core:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: `ApplicationModules.verify()` 확인**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS (모듈 경계·레이어 규칙 전부 GREEN)

- [ ] **Step 3: 로컬 DB 기동 + 두 jar 동시 기동**

```bash
docker compose up -d postgres redis
./gradlew bootJar :trading-core:bootJar
DB_URL=... DB_USERNAME=... DB_PASSWORD=... JWT_SIGNING_KEY=... INTERNAL_API_TOKEN=devtoken java -jar build/libs/*.jar &
DB_URL=... DB_USERNAME=... DB_PASSWORD=... JWT_SIGNING_KEY=... INTERNAL_API_TOKEN=devtoken SERVER_PORT=8081 java -jar trading-core/build/libs/*.jar &
```

(실제 값은 `application-local.yml`/`.env` 참고 — DB_URL 등은 같은 로컬 postgres를 가리켜야 함, 4a는 아직 1 DB)

- [ ] **Step 4: 인증 스택 검증**

```bash
curl -i http://localhost:8081/api/internal/trading/scheduler/open -H "X-Internal-Token: wrong"
# 기대: 401

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i http://localhost:8081/api/trading-cycles -H "Authorization: Bearer $TOKEN"
# 기대: 200 (또는 빈 배열 — 인증 자체는 통과해야 함)
```

- [ ] **Step 5: `trade.event` pub/sub 왕복 확인**

로컬 Redis CLI로 수동 발행 후 root SSE 연결에서 수신되는지 확인:
```bash
redis-cli PUBLISH trade.event '{"userId":"<test-uuid>","event":{"type":"BUY","ticker":"SOXL","quantity":1,"price":10.0,"amountUsd":10.0,"accountNickname":"test"}}'
```
(kista-ui 또는 curl로 `/api/trades/stream` SSE 연결해두고 위 명령 실행, 이벤트 수신 로그 확인)

- [ ] **Step 6: kista-ui 로컬 기동해 전체 플로우 확인**

`TRADING_API_BASE_URL=http://localhost:8081 API_BASE_URL=http://localhost:8080 npm run dev`(kista-ui 레포) — 로그인 → 계좌 조회 → 전략 조회 → 매매 사이클 조회 전 구간 브라우저로 확인.

- [ ] **Step 7: 두 프로세스 종료, 최종 커밋 없음(검증 전용 태스크) — 문제 발견 시 해당 태스크로 돌아가 수정**

---

## Self-Review 체크리스트 (계획 작성자용, 실행자는 무시)
- Spec 4a 절의 모든 항목(보안 스택 이관/자체 bootJar/kista-ui 라우팅/notify 이관/user_notify_profile 확장/trade.event)에 대응 태스크 있음 — Task 1,2,3 / 9 / 5,6,7 / 4 / 8.
- Task 5→6 사이 의도적 컴파일 실패 구간은 하나의 논리적 커밋 단위로 묶어 bisect 안전성 확보하도록 명시함.
- Task 7 Step 5의 "틀렸다" 노트는 플레이스홀더가 아니라 실제 다음 태스크로의 인계 지점 — Task 8이 이를 완성함.
