# kista-trading 4b-1 (Redis Stream 동기화 + 드리프트 복구) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** root(kista-api)와 trading-core(kista-trading) 두 프로세스 간 `UserDeletedEvent`/`UserNotifyProfileChangedEvent` 전달을 Redis Stream(내구성 있는 발행/구독)으로 배선하고, 4a 배포 이후 이 전달 경로가 애초에 없었던 탓에 쌓인 운영 드리프트를 일회성으로 복구한다.

**Architecture:** root는 기존 로컬 이벤트 리스너(finance cascade/UserDeletedNotifier/UserFcmCleanupListener)를 그대로 두고, 같은 이벤트에 반응하는 신규 `@TransactionalEventListener`가 Redis Stream(`stream:user.deleted`, `stream:user.notify-profile.changed`)에 XADD로 발행만 추가한다. trading-core는 컨슈머 그룹(`trading-core`)으로 구독해 받은 메시지를 역직렬화한 뒤, 자기 프로세스 안에서 `ApplicationEventPublisher.publishEvent()`로 **로컬 재발행**한다 — 이러면 이미 존재하는 4개의 trading-core cascade 리스너(`UserCascadeListener`/`AccountUserCascadeListener`/`StrategyUserCascadeListener`/`UserNotifyProfileSyncListener`)를 한 줄도 고치지 않고 그대로 재사용할 수 있다. 재발행 호출은 `@Transactional`로 감싸 AFTER_COMMIT phase 리스너가 확실히 발화하게 만든다. 크래시 복구는 컨슈머 시작 시 자기 컨슈머 그룹의 오래된 pending 항목을 스스로 claim하는 self-heal과, 주기적 재확인 스케쥴러 두 겹으로 처리한다.

**Tech Stack:** Spring Data Redis(`StreamMessageListenerContainer`, `opsForStream()`), Spring Modulith(`@TransactionalEventListener`), Lettuce(기존 연결 팩토리 재사용).

**Spec:** `docs/superpowers/specs/2026-09-14-kista-trading-stage4-db-split-design.md`(4b-1 절) — 이 플랜은 그 스펙의 "Redis Stream 2종" + "사전 확인 완료" 절, 그리고 이번 조사에서 발견한 운영 드리프트(설계 문서에 없던 항목, 아래 배경 참고)를 함께 구현한다.

## 배경 — 왜 드리프트 복구가 이 플랜에 있나

4a 배포(`de4966bb`) 이후 `KistaApplication.scanBasePackages`가 trading-core 패키지를 스캔에서 제외하면서, root가 발행하는 `UserDeletedEvent`/`UserNotifyProfileChangedEvent`는 trading-core 프로세스 안의 4개 리스너에 **한 번도 전달되지 않았다**(Spring 로컬 이벤트는 JVM을 넘지 않음). 영향: 탈퇴해도 trading-core 쪽 `accounts`/`strategy`/`strategy_cycle`/`cycle_position`이 정리 안 됨(활성 전략이 남으면 스케쥴러가 탈퇴 계좌로 계속 주문 낼 수 있음), 신규 가입·설정변경이 `user_notify_profile` 복제본에 반영 안 됨(신규 전략 등록 거부 또는 무증상 알림 누락). Task 1이 이 드리프트를 일회성으로 복구하고, Task 2~5가 재발 방지 배선(Redis Stream)을 완성한다.

## Global Constraints

- 커밋 author: `narafu <narafu@kakao.com>` (프로젝트 CLAUDE.md)
- `git push`는 사용자가 명시적으로 요청할 때만
- 신규 주석은 `//` 인라인만, Javadoc/블록 주석 금지(kista-api CLAUDE.md "주석 규칙")
- `@Transactional` 내부에서 외부 시스템(RestTemplate 등) 호출 금지 — 여기선 해당 없음(Redis 호출은 허용 대상 아님, 도메인 규칙은 KIS/Toss/Telegram 외부 API 한정이나 Redis도 네트워크 I/O이므로 트랜잭션 경계 밖에서 최대한 짧게 유지)
- Virtual Thread 활성화 — `@Async`/`CompletableFuture` 금지, 대기는 `Thread.sleep()`
- 테스트: 단위 `*Test`, Docker 필요 통합은 `@Tag("integration")`(`./gradlew integration`) — Redis 관련 테스트는 기존 `RedisTradeEventPublisherTest` 패턴(로컬 `localhost:6379` 직접 연결) 그대로 따른다
- 배포 순서: `kista-api`(발행자) 먼저, `kista-trading` 나중 — 반대 순서면 스트림에 아무도 안 쓴 채로 무증상 drift 재발

---

### Task 1: 운영 드리프트 일회성 복구 (root, admin 트리거)

**Files:**
- Create: `src/main/java/com/kista/user/application/usecase/UserSyncBackfillUseCase.java`
- Create: `src/main/java/com/kista/user/application/service/UserSyncBackfillService.java`
- Modify: `src/main/java/com/kista/admin/adapter/in/web/AdminUserController.java` (신규 엔드포인트 1개 추가 — 실제 파일 위치는 admin 유저 관련 컨트롤러, 없으면 기존 admin 컨트롤러 중 유저 액션을 다루는 파일에 추가)
- Test: `src/test/java/com/kista/user/application/service/UserSyncBackfillServiceTest.java`

**Interfaces:**
- Consumes: `com.kista.user.application.port.output.UserPort.findById(UUID): Optional<User>`(기존), `com.kista.sharedkernel.UserDeletedEvent`(기존, 필드 `userId`), `com.kista.sharedkernel.UserNotifyProfileChangedEvent`(기존), `org.springframework.jdbc.core.JdbcTemplate`(Spring Boot 자동 설정 빈)
- Produces: `UserSyncBackfillUseCase.runOnce(): BackfillResult` — `record BackfillResult(int cascadeRepublished, int profileBackfilled)`. Task 2~5는 이 결과를 소비하지 않음(독립).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/kista/user/application/service/UserSyncBackfillServiceTest.java`:
```java
package com.kista.user.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSyncBackfillServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock UserPort userPort;
    @Mock UserSettingsPort userSettingsPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @Test
    void runOnce_republishesDeletedUsersAndBackfillsMissingProfiles() {
        UUID deletedUserId = UUID.randomUUID();
        UUID driftUserId = UUID.randomUUID();
        when(jdbcTemplate.queryForList("SELECT id FROM users WHERE deleted_at IS NOT NULL", UUID.class))
                .thenReturn(List.of(deletedUserId));
        when(jdbcTemplate.queryForList(
                "SELECT id FROM users u WHERE u.deleted_at IS NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM kista.user_notify_profile p WHERE p.user_id = u.id)", UUID.class))
                .thenReturn(List.of(driftUserId));
        User driftUser = User.builder().id(driftUserId).status(UserStatus.ACTIVE)
                .telegramBotToken(null).telegramChatId(null).build();
        when(userPort.findById(driftUserId)).thenReturn(Optional.of(driftUser));
        when(userSettingsPort.findOrDefault(driftUserId)).thenReturn(UserSettings.defaultFor(driftUserId));

        UserSyncBackfillService service = new UserSyncBackfillService(
                jdbcTemplate, userPort, userSettingsPort, eventPublisher);
        var result = service.runOnce();

        assertThat(result.cascadeRepublished()).isEqualTo(1);
        assertThat(result.profileBackfilled()).isEqualTo(1);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).anySatisfy(e ->
                assertThat(((UserDeletedEvent) e).userId()).isEqualTo(deletedUserId));
        assertThat(events.getAllValues()).anySatisfy(e ->
                assertThat(((UserNotifyProfileChangedEvent) e).userId()).isEqualTo(driftUserId));
    }
}
```

(`User.builder()`/`UserSettings.defaultFor()` 시그니처가 실제 코드와 다르면 기존 `UserServiceTest` 등에서 생성 패턴을 확인해 맞출 것 — record라면 정적 팩토리 또는 생성자 직접 호출로 대체)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests 'com.kista.user.application.service.UserSyncBackfillServiceTest'`
Expected: FAIL — `UserSyncBackfillService` 클래스 없음(컴파일 에러)

- [ ] **Step 3: usecase 인터페이스 작성**

`src/main/java/com/kista/user/application/usecase/UserSyncBackfillUseCase.java`:
```java
package com.kista.user.application.usecase;

// 4a 배포(2026-09-16 이전) 이후 root→trading-core 이벤트 전달 경로가 없어 쌓인 드리프트를
// 일회성으로 복구한다 — Redis Stream 배선(Task 2~5) 완료 후에는 재발 방지되므로 이 usecase는
// 배포 직후 admin이 한 번만 트리거하면 된다. 이미 정상 동기화된 사용자에게 재실행해도
// 멱등(cascade 재발행은 idempotent, 프로필 재발행은 upsert)하므로 여러 번 눌러도 안전하다.
public interface UserSyncBackfillUseCase {

    BackfillResult runOnce();

    record BackfillResult(int cascadeRepublished, int profileBackfilled) {
    }
}
```

- [ ] **Step 4: 서비스 구현**

`src/main/java/com/kista/user/application/service/UserSyncBackfillService.java`:
```java
package com.kista.user.application.service;

import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import com.kista.sharedkernel.UserStatus;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.user.application.usecase.UserSyncBackfillUseCase;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// 일회성 드리프트 복구 — DB가 아직 공유(4b-1) 상태라 kista.user_notify_profile을 JdbcTemplate
// 원시 SQL로 직접 들여다본다(trading-core 소유 테이블이지만 물리적으로 같은 DB). 4b-2 컷오버
// 이후엔 이 쿼리가 더 이상 유효하지 않으므로 재사용하지 말 것 — 일회성 도구로 남긴다.
@Service
@RequiredArgsConstructor
class UserSyncBackfillService implements UserSyncBackfillUseCase {

    private final JdbcTemplate jdbcTemplate;
    private final UserPort userPort;
    private final UserSettingsPort userSettingsPort;
    private final ApplicationEventPublisher eventPublisher;

    // @Transactional — AFTER_COMMIT 리스너(신규 Stream 발행자 포함)가 메서드 종료 시 한 번에 발화하도록
    @Override
    @Transactional
    public BackfillResult runOnce() {
        List<UUID> deletedUserIds = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE deleted_at IS NOT NULL", UUID.class);
        deletedUserIds.forEach(id -> eventPublisher.publishEvent(new UserDeletedEvent(id)));

        List<UUID> driftUserIds = jdbcTemplate.queryForList(
                "SELECT id FROM users u WHERE u.deleted_at IS NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM kista.user_notify_profile p WHERE p.user_id = u.id)", UUID.class);
        int backfilled = 0;
        for (UUID userId : driftUserIds) {
            User user = userPort.findById(userId).orElse(null);
            if (user == null) {
                continue;
            }
            UserSettings settings = userSettingsPort.findOrDefault(userId);
            eventPublisher.publishEvent(new UserNotifyProfileChangedEvent(
                    userId, settings.notificationPrefs(), settings.balanceCheckEnabled(),
                    user.status() == UserStatus.ACTIVE, user.telegramBotToken(), user.telegramChatId()));
            backfilled++;
        }
        return new BackfillResult(deletedUserIds.size(), backfilled);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.kista.user.application.service.UserSyncBackfillServiceTest'`
Expected: PASS

- [ ] **Step 6: admin 트리거 엔드포인트 추가**

기존 admin 유저 컨트롤러(`AdminUserController` 등 — 정확한 파일명은 `src/main/java/com/kista/admin/adapter/in/web/`에서 `AdminUserUseCase`를 주입하는 컨트롤러를 찾아 그 옆에 추가)에 필드와 엔드포인트를 추가한다:

```java
private final UserSyncBackfillUseCase userSyncBackfillUseCase;
private final AuditLogPort auditLogPort;

@PostMapping("/user-sync-backfill")
public ResponseEntity<UserSyncBackfillUseCase.BackfillResult> backfillUserSync(
        @AuthenticationPrincipal UUID adminId) {
    var result = userSyncBackfillUseCase.runOnce();
    auditLogPort.log(adminId, "USER_SYNC_BACKFILL", "USER", null,
            Map.of("cascadeRepublished", result.cascadeRepublished(), "profileBackfilled", result.profileBackfilled()));
    return ResponseEntity.ok(result);
}
```
(`AuditLogPort`가 이미 그 컨트롤러/서비스에 없다면 컨트롤러가 아니라 얇은 admin 서비스 메서드를 하나 새로 만들어 감사 로그를 거기서 남기는 편이 기존 패턴 — `AdminService.deleteUser` 등 참고. 컨트롤러에 직접 audit 호출을 넣지 말고 반드시 `application.service` 계층에 위치시킬 것)

이 단계는 컴파일 확인만(`./gradlew compileJava`)으로 충분 — 별도 컨트롤러 테스트는 생략(1회성 운영 도구, 기존 `@WebMvcTest` 슬라이스 추가는 이 태스크 범위 밖).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/kista/user/application/usecase/UserSyncBackfillUseCase.java \
        src/main/java/com/kista/user/application/service/UserSyncBackfillService.java \
        src/test/java/com/kista/user/application/service/UserSyncBackfillServiceTest.java \
        src/main/java/com/kista/admin/adapter/in/web/AdminUserController.java
git commit -m "$(cat <<'EOF'
fix(user): 4a 배포 이후 root-trading 이벤트 유실 드리프트 일회성 복구 도구 추가

KistaApplication의 trading-core 패키지 스캔 제외로 인해 UserDeletedEvent/
UserNotifyProfileChangedEvent가 4a 배포 이후 trading-core 리스너에 전달된
적이 없었다 — 탈퇴 cascade 미삭제, 신규 가입/설정변경 프로필 복제본 누락.
admin 트리거 1회성 복구 엔드포인트로 기존 이벤트를 재발행해 드리프트 해소.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HDWxKVwMQGaKoCMsDXJ5YQ
EOF
)"
```

---

### Task 2: Redis Stream 공용 인프라 — 키·그룹 상수 (`:shared`)

**Files:**
- Create: `shared/src/main/java/com/kista/platform/redis/RedisStreamConfig.java`

**Interfaces:**
- Produces: `RedisStreamConfig.USER_DELETED_STREAM`(String), `RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM`(String), `RedisStreamConfig.TRADING_CONSUMER_GROUP`(String) — Task 3(root 발행자)과 Task 4(trading-core 소비자) 양쪽이 이 상수를 참조해 문자열 불일치를 컴파일 타임에 방지.

- [ ] **Step 1: 상수 클래스 작성**

`shared/src/main/java/com/kista/platform/redis/RedisStreamConfig.java`:
```java
package com.kista.platform.redis;

// root(발행)와 trading-core(구독)가 같은 스트림 키·컨슈머 그룹 문자열을 쓰도록 강제하는 상수
// 홀더 — RedisPubSubConfig(채널명 상수)와 동일한 목적. Stream 자체(XADD/컨슈머 그룹 생성)는
// 각자 발행측/구독측 어댑터가 담당하고 여긴 문자열만 둔다.
public final class RedisStreamConfig {

    public static final String USER_DELETED_STREAM = "stream:user.deleted";
    public static final String USER_NOTIFY_PROFILE_CHANGED_STREAM = "stream:user.notify-profile.changed";
    public static final String TRADING_CONSUMER_GROUP = "trading-core";

    private RedisStreamConfig() {
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :shared:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add shared/src/main/java/com/kista/platform/redis/RedisStreamConfig.java
git commit -m "$(cat <<'EOF'
feat(shared): Redis Stream 키·컨슈머그룹 상수 추가

root/trading-core 양쪽이 참조할 stream:user.deleted, stream:user.notify-
profile.changed, trading-core 컨슈머 그룹명을 컴파일 타임 상수로 고정.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HDWxKVwMQGaKoCMsDXJ5YQ
EOF
)"
```

---

### Task 3: root 발행자 — Stream publish 리스너 2개

**Files:**
- Create: `src/main/java/com/kista/user/adapter/out/redis/UserEventStreamPublisher.java`
- Test: `src/test/java/com/kista/user/adapter/out/redis/UserEventStreamPublisherTest.java`

**Interfaces:**
- Consumes: `RedisStreamConfig.USER_DELETED_STREAM`/`USER_NOTIFY_PROFILE_CHANGED_STREAM`(Task 2), `StringRedisTemplate`(Spring Boot Redis 자동 설정 빈), `tools.jackson.databind.ObjectMapper`(기존 빈, `RedisTradeEventPublisher` 참고)
- Produces: 없음(터미널 어댑터) — Task 4가 이 발행 포맷(JSON 필드명)과 반드시 일치해야 함: `{"userId":"...","notificationPrefs":{...},"balanceCheckEnabled":bool,"active":bool,"telegramBotToken":"..."|null,"chatId":"..."|null}` (UserNotifyProfileChangedEvent record 필드 그대로 직렬화), `{"userId":"..."}` (UserDeletedEvent)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`src/test/java/com/kista/user/adapter/out/redis/UserEventStreamPublisherTest.java` (기존 `RedisTradeEventPublisherTest` 패턴 — 로컬 redis 필요):
```java
package com.kista.user.adapter.out.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 Redis(localhost:6379) 필요 — docker compose up -d redis 선행.
@Tag("integration")
@DisplayName("UserEventStreamPublisher XADD 발행 통합 테스트")
class UserEventStreamPublisherTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterEach
    void cleanStreams() {
        redisTemplate.delete(RedisStreamConfig.USER_DELETED_STREAM);
        redisTemplate.delete(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM);
    }

    @AfterAll
    static void disconnectRedis() {
        connectionFactory.destroy();
    }

    @Test
    void onUserDeleted_addsRecordToStream() {
        UserEventStreamPublisher publisher = new UserEventStreamPublisher(redisTemplate, new ObjectMapper());
        UUID userId = UUID.randomUUID();

        publisher.onUserDeleted(new UserDeletedEvent(userId));

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.from("0")));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue().get("payload").toString()).contains(userId.toString());
    }

    @Test
    void onProfileChanged_addsRecordToStream() {
        UserEventStreamPublisher publisher = new UserEventStreamPublisher(redisTemplate, new ObjectMapper());
        UUID userId = UUID.randomUUID();

        publisher.onProfileChanged(new UserNotifyProfileChangedEvent(
                userId, Map.of(), false, true, null, null));

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(StreamOffset.create(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, ReadOffset.from("0")));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue().get("payload").toString()).contains(userId.toString());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `docker compose up -d redis && ./gradlew test --tests 'com.kista.user.adapter.out.redis.UserEventStreamPublisherTest' -Dtags=integration`
Expected: FAIL — `UserEventStreamPublisher` 클래스 없음

- [ ] **Step 3: 발행자 구현**

`src/main/java/com/kista/user/adapter/out/redis/UserEventStreamPublisher.java`:
```java
package com.kista.user.adapter.out.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;

// 기존 로컬 리스너(finance cascade/UserDeletedNotifier/UserFcmCleanupListener,
// UserNotifyProfileSyncListener는 이제 trading-core 전용)는 그대로 두고, trading-core에
// 내구성 있게 전달하기 위한 Redis Stream 발행만 추가한다 — 교체가 아니라 추가.
// fallbackExecution=true: 트랜잭션 밖에서 발행되는 경우에도 유실 없이 발행(복제본 동기화는
// 조용한 유실보다 낫다는 기존 UserNotifyProfileSyncListener 판단과 동일).
@Component
@RequiredArgsConstructor
public class UserEventStreamPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onUserDeleted(UserDeletedEvent event) {
        add(RedisStreamConfig.USER_DELETED_STREAM, event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onProfileChanged(UserNotifyProfileChangedEvent event) {
        add(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, event);
    }

    private void add(String streamKey, Object event) {
        String payload = objectMapper.writeValueAsString(event);
        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(streamKey)
                .ofMap(Collections.singletonMap("payload", payload)));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests 'com.kista.user.adapter.out.redis.UserEventStreamPublisherTest' -Dtags=integration`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/user/adapter/out/redis/UserEventStreamPublisher.java \
        src/test/java/com/kista/user/adapter/out/redis/UserEventStreamPublisherTest.java
git commit -m "$(cat <<'EOF'
feat(user): UserDeletedEvent/UserNotifyProfileChangedEvent Redis Stream 발행 추가

trading-core로 내구성 있게 전달하기 위해 기존 로컬 리스너는 유지한 채
Redis Stream(stream:user.deleted, stream:user.notify-profile.changed)
XADD 발행 리스너를 신규 추가.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HDWxKVwMQGaKoCMsDXJ5YQ
EOF
)"
```

---

### Task 4: trading-core 소비 브릿지 — 컨슈머 그룹 + 로컬 재발행

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamBridge.java`
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamConsumerConfig.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/in/redis/UserEventStreamBridgeIT.java`

**Interfaces:**
- Consumes: `RedisStreamConfig.*`(Task 2), Task 3이 발행하는 JSON payload 포맷, 기존 `UserCascadeListener`/`AccountUserCascadeListener`/`StrategyUserCascadeListener`/`UserNotifyProfileSyncListener`(전부 무변경, `ApplicationEventPublisher`를 통해서만 간접 트리거)
- Produces: 없음(터미널 소비자) — Task 5(크래시 복구)가 같은 `UserEventStreamBridge`의 처리 메서드를 재사용

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`trading-core/src/test/java/com/kista/trading/adapter/in/redis/UserEventStreamBridgeIT.java`:
```java
package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 실제 로컬 Redis(localhost:6379) 필요 — docker compose up -d redis 선행.
// UserEventStreamBridge가 스트림 메시지를 실제로 로컬 이벤트로 재발행하는지만 검증하고,
// 기존 4개 cascade 리스너 자체의 동작(soft-delete 등)은 각 리스너의 기존 단위 테스트가 검증한다.
@Tag("integration")
@DisplayName("UserEventStreamBridge 스트림→로컬이벤트 재발행 통합 테스트")
class UserEventStreamBridgeIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(RedisStreamConfig.USER_DELETED_STREAM);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void handleUserDeletedRecord_republishesLocallyAndAcks() {
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        UserEventStreamBridge bridge = new UserEventStreamBridge(redisTemplate, new ObjectMapper(), eventPublisher);
        UUID userId = UUID.randomUUID();
        redisTemplate.opsForStream().createGroup(RedisStreamConfig.USER_DELETED_STREAM, "0", RedisStreamConfig.TRADING_CONSUMER_GROUP);
        var recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(RedisStreamConfig.USER_DELETED_STREAM)
                .ofMap(Collections.singletonMap("payload", "{\"userId\":\"" + userId + "\"}")));

        var records = redisTemplate.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, "test-consumer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty(),
                org.springframework.data.redis.connection.stream.StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM,
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));

        bridge.handleUserDeletedRecord(records.get(0));

        Mockito.verify(eventPublisher).publishEvent(new UserDeletedEvent(userId));
        var pending = redisTemplate.opsForStream().pending(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP);
        assertThat(pending.getTotalPendingMessages()).isZero(); // ack 완료 확인
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `docker compose up -d redis && ./gradlew :trading-core:test --tests 'com.kista.trading.adapter.in.redis.UserEventStreamBridgeIT' -Dtags=integration`
Expected: FAIL — `UserEventStreamBridge` 클래스 없음

- [ ] **Step 3: 브릿지 컴포넌트 구현**

`trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamBridge.java`:
```java
package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// Redis Stream 메시지를 trading-core 프로세스 안에서 로컬 이벤트로 재발행하는 브릿지 —
// 기존 4개 cascade/동기화 리스너(UserCascadeListener/AccountUserCascadeListener/
// StrategyUserCascadeListener/UserNotifyProfileSyncListener)를 한 줄도 고치지 않고 그대로
// 재사용하기 위해, 원격 전달 문제를 여기서만 해소한다. @Transactional로 감싸야
// AFTER_COMMIT phase 리스너가 실제로 발화한다(트랜잭션이 없으면 fallbackExecution 없는
// 리스너는 조용히 스킵됨).
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamBridge {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void handleUserDeletedRecord(MapRecord<String, Object, Object> record) {
        UUID userId = UUID.fromString(payloadNode(record).get("userId").asText());
        republishUserDeleted(new UserDeletedEvent(userId));
        ack(RedisStreamConfig.USER_DELETED_STREAM, record.getId());
    }

    public void handleProfileChangedRecord(MapRecord<String, Object, Object> record) {
        ObjectNode node = payloadNode(record);
        var event = objectMapper.convertValue(node, UserNotifyProfileChangedEvent.class);
        republishProfileChanged(event);
        ack(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, record.getId());
    }

    @Transactional
    void republishUserDeleted(UserDeletedEvent event) {
        eventPublisher.publishEvent(event);
    }

    @Transactional
    void republishProfileChanged(UserNotifyProfileChangedEvent event) {
        eventPublisher.publishEvent(event);
    }

    private ObjectNode payloadNode(MapRecord<String, Object, Object> record) {
        String payload = record.getValue().get("payload").toString();
        return (ObjectNode) objectMapper.readTree(payload);
    }

    private void ack(String streamKey, RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, recordId);
    }
}
```

(`import java.util.UUID;` 누락 시 컴파일 에러 — Step 4에서 컴파일 확인 시 추가)

- [ ] **Step 4: 컨슈머 그룹 생성 + 리스너 컨테이너 배선**

`trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamConsumerConfig.java`:
```java
package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// stream:user.deleted / stream:user.notify-profile.changed 컨슈머 그룹 구독 배선.
// 컨슈머 이름은 인스턴스마다 고유해야 pending 추적이 꼬이지 않으므로 프로세스 시작 시 UUID로 생성.
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamConsumerConfig implements DisposableBean {

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final UserEventStreamBridge bridge;

    private final String consumerName = "trading-core-" + UUID.randomUUID();
    private StreamMessageListenerContainer<String, org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> container;

    @PostConstruct
    void start() {
        ensureGroup(RedisStreamConfig.USER_DELETED_STREAM);
        ensureGroup(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM);

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(2))
                .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, consumerName),
                StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.lastConsumed()),
                bridge::handleUserDeletedRecord);
        container.receive(Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, consumerName),
                StreamOffset.create(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, ReadOffset.lastConsumed()),
                bridge::handleProfileChangedRecord);
        container.start();
        log.info("Redis Stream 컨슈머 시작 — consumer={}", consumerName);
    }

    // 스트림이 아직 없으면 MKSTREAM으로 함께 생성, 그룹이 이미 있으면(BUSYGROUP) 무시
    private void ensureGroup(String streamKey) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), RedisStreamConfig.TRADING_CONSUMER_GROUP);
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    @Override
    public void destroy() {
        if (container != null) {
            container.stop();
        }
    }
}
```

- [ ] **Step 5: 컴파일 확인 + 테스트 통과 확인**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL (Step 3의 `import java.util.UUID;` 누락을 여기서 잡아 추가)

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.adapter.in.redis.UserEventStreamBridgeIT' -Dtags=integration`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/in/redis/ \
        trading-core/src/test/java/com/kista/trading/adapter/in/redis/
git commit -m "$(cat <<'EOF'
feat(trading): Redis Stream 컨슈머 그룹 구독 + 로컬 이벤트 재발행 브릿지

stream:user.deleted / stream:user.notify-profile.changed을 trading-core
컨슈머 그룹으로 구독해, 받은 메시지를 로컬 UserDeletedEvent/
UserNotifyProfileChangedEvent로 재발행 — 기존 4개 cascade 리스너 무변경
재사용. AFTER_COMMIT 발화 보장을 위해 재발행을 @Transactional로 감쌈.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HDWxKVwMQGaKoCMsDXJ5YQ
EOF
)"
```

---

### Task 5: 크래시 복구 — 시작 시 self-heal + 주기적 재확인

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamConsumerConfig.java` (시작 시 self-heal claim 추가)
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamRecoveryScheduler.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/in/redis/UserEventStreamRecoveryIT.java`

**Interfaces:**
- Consumes: `UserEventStreamBridge.handleUserDeletedRecord/handleProfileChangedRecord`(Task 4, 그대로 재사용), `SchedulerJobRunner.run(String, Runnable)`(`:shared`, 기존)
- Produces: 없음(운영 안정성 보강, 다른 태스크가 소비하지 않음)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`trading-core/src/test/java/com/kista/trading/adapter/in/redis/UserEventStreamRecoveryIT.java`:
```java
package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 로컬 Redis(localhost:6379) 필요. 컨슈머 A가 읽고 ack 안 한 채(크래시 시뮬레이션) 종료된
// pending 항목을, 신규 컨슈머 B가 claim해 재처리+ack하는 시나리오 검증.
@Tag("integration")
@DisplayName("UserEventStreamRecoveryScheduler pending claim 재처리 통합 테스트")
class UserEventStreamRecoveryIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(RedisStreamConfig.USER_DELETED_STREAM);
        redisTemplate.opsForStream().createGroup(RedisStreamConfig.USER_DELETED_STREAM, "0", RedisStreamConfig.TRADING_CONSUMER_GROUP);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void reclaim_recoversPendingRecordAndAcks() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(RedisStreamConfig.USER_DELETED_STREAM)
                .ofMap(Collections.singletonMap("payload", "{\"userId\":\"" + userId + "\"}")));
        // 죽은 컨슈머가 읽기만 하고 ack 안 함 — pending 상태로 남김
        redisTemplate.opsForStream().read(Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, "dead-consumer"),
                StreamReadOptions.empty(), StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.lastConsumed()));

        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        UserEventStreamBridge bridge = new UserEventStreamBridge(redisTemplate, new ObjectMapper(), eventPublisher);
        UserEventStreamRecoveryScheduler scheduler = new UserEventStreamRecoveryScheduler(
                redisTemplate, bridge, Mockito.mock(com.kista.platform.scheduling.SchedulerJobRunner.class));

        scheduler.reclaimPending(Duration.ZERO); // 유휴시간 0으로 즉시 claim 대상 처리

        var pending = redisTemplate.opsForStream().pending(RedisStreamConfig.USER_DELETED_STREAM, RedisStreamConfig.TRADING_CONSUMER_GROUP);
        assertThat(pending.getTotalPendingMessages()).isZero();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `docker compose up -d redis && ./gradlew :trading-core:test --tests 'com.kista.trading.adapter.in.redis.UserEventStreamRecoveryIT' -Dtags=integration`
Expected: FAIL — `UserEventStreamRecoveryScheduler` 클래스 없음

- [ ] **Step 3: 재확인 스케쥴러 구현**

`trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamRecoveryScheduler.java`:
```java
package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.scheduling.SchedulerJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

// XAUTOCLAIM 대응 — 컨슈머가 메시지를 읽고 처리 전 죽으면(ack 안 됨) pending으로 남는다.
// 5분마다 유휴 60초 이상 pending 항목을 이 스케쥴러 전용 컨슈머로 claim해 재처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamRecoveryScheduler {

    private static final Duration IDLE_THRESHOLD = Duration.ofSeconds(60);
    private static final String RECOVERY_CONSUMER = "trading-core-recovery";

    private final StringRedisTemplate redisTemplate;
    private final UserEventStreamBridge bridge;
    private final SchedulerJobRunner schedulerJobRunner;

    @Scheduled(fixedDelay = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void run() {
        schedulerJobRunner.run("Redis Stream pending 복구", () -> reclaimPending(IDLE_THRESHOLD));
    }

    void reclaimPending(Duration idleThreshold) {
        reclaimStream(RedisStreamConfig.USER_DELETED_STREAM, idleThreshold, bridge::handleUserDeletedRecord);
        reclaimStream(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, idleThreshold, bridge::handleProfileChangedRecord);
    }

    private void reclaimStream(String streamKey, Duration idleThreshold, java.util.function.Consumer<MapRecord<String, Object, Object>> handler) {
        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP);
        if (summary.getTotalPendingMessages() == 0) {
            return;
        }
        PendingMessages pending = redisTemplate.opsForStream().pending(streamKey,
                org.springframework.data.redis.connection.stream.Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, RECOVERY_CONSUMER),
                org.springframework.data.redis.connection.RedisZSetCommands.Range.unbounded(), 100);
        List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, RECOVERY_CONSUMER, idleThreshold,
                pending.stream().map(org.springframework.data.redis.connection.stream.PendingMessage::getId).toArray(org.springframework.data.redis.connection.stream.RecordId[]::new));
        for (var record : claimed) {
            log.warn("Redis Stream pending 복구 처리 — stream={}, recordId={}", streamKey, record.getId());
            handler.accept(record);
        }
    }
}
```

(`redisTemplate.opsForStream().pending(streamKey, consumer, range, count)`/`.claim(...)` 시그니처는 Spring Data Redis 버전에 따라 오버로드가 다를 수 있음 — Step 4 컴파일 시 실제 시그니처로 맞출 것. 핵심은 "그룹의 idle 항목을 조회해 recovery consumer로 claim 후 기존 handler로 재처리+ack"이므로 API 세부 시그니처가 다르면 동등한 호출로 대체)

- [ ] **Step 4: 컴파일 확인 + 테스트 통과 확인**

Run: `./gradlew :trading-core:compileJava`
Expected: BUILD SUCCESSFUL (시그니처 불일치 시 Spring Data Redis 실제 API로 수정)

Run: `./gradlew :trading-core:test --tests 'com.kista.trading.adapter.in.redis.UserEventStreamRecoveryIT' -Dtags=integration`
Expected: PASS

- [ ] **Step 5: `@EnableScheduling` 확인**

`TradingApplication.java`에 `@EnableScheduling`이 없으면 추가(root `KistaApplication`은 이미 있음 — trading-core도 기존 스케쥴러(`TradingOpenScheduler` 등)가 동작 중이므로 이미 있을 가능성 높음, 없으면 추가하고 컴파일 재확인).

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/in/redis/UserEventStreamRecoveryScheduler.java \
        trading-core/src/test/java/com/kista/trading/adapter/in/redis/UserEventStreamRecoveryIT.java
git commit -m "$(cat <<'EOF'
feat(trading): Redis Stream pending 항목 주기적 claim 복구 스케쥴러 추가

컨슈머가 메시지를 읽고 ack 전 크래시하면 pending으로 남는 항목을 5분마다
유휴 60초 기준으로 claim해 재처리 — XAUTOCLAIM 대응.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HDWxKVwMQGaKoCMsDXJ5YQ
EOF
)"
```

---

### Task 6: 전체 스위트 + 로컬 2-프로세스 스모크 (4b-1 최종 게이트)

**Files:** 없음(검증 전용 태스크)

- [ ] **Step 1: 전체 테스트 스위트 1회**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :trading-core:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 통합 테스트 스위트(Redis 필요) 1회**

Run: `docker compose up -d postgres redis && ./gradlew integration`
Expected: BUILD SUCCESSFUL(신규 Task 3~5 통합 테스트 포함)

- [ ] **Step 3: `ApplicationModules.verify()` 확인**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS

- [ ] **Step 4: 로컬 2-프로세스 실트래픽 스모크**

`docs/agents/commands.md`의 2-프로세스 부팅 절차로 root(8080)+trading-core(8081) 동시 기동 후:
```bash
# 1) 가입/탈퇴 이벤트가 trading-core까지 도달하는지
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
# ... 계좌 생성(8081) → 탈퇴(8080, DevAuthController 경로) → 잠시 대기 →
# kista.accounts.deleted_at이 채워졌는지 psql로 직접 확인

# 2) 드리프트 복구 도구 동작 확인(Task 1)
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -X POST localhost:8080/api/admin/user-sync-backfill -H "Authorization: Bearer $ADMIN_TOKEN"
# 기대: 200, {"cascadeRepublished":N,"profileBackfilled":M}
```

- [ ] **Step 5: 두 프로세스 종료, 문제 발견 시 해당 태스크로 복귀해 수정**

---

## Self-Review 체크리스트 (계획 작성자용, 실행자는 무시)
- 스펙 4b-1 절(운영 드리프트 복구 배경, Redis Stream 2종, consumer group + XACK, XAUTOCLAIM 복구)에 대응하는 태스크 있음 — Task 1 / Task 2·3·4 / Task 4 / Task 5.
- Task 3의 발행 JSON 포맷과 Task 4의 역직렬화가 동일 필드명 사용 확인(`UserDeletedEvent{userId}`, `UserNotifyProfileChangedEvent{userId,notificationPrefs,balanceCheckEnabled,active,telegramBotToken,chatId}`).
- Task 4/5가 기존 4개 cascade 리스너 코드를 전혀 수정하지 않음 — 회귀 리스크 최소화 의도적 설계.
- 배포 순서(root 먼저) 제약은 Global Constraints에 명시, Task 6 스모크에서 실증.
- 4b-2(DB 컷오버)는 이 플랜 범위 밖 — 별도 플랜 문서로 분리 예정(스펙 4b-2 절 기준).

**Plan complete and saved to `docs/superpowers/plans/2026-09-16-kista-trading-stage4b1.md`.**
