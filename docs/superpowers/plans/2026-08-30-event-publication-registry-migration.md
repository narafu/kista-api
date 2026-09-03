# Event Publication Registry 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `@TransactionalEventListener` 알림 패턴 전체를 Spring Modulith Event Publication Registry(EPR)로 전환해, 실패한 이벤트 발행을 재기동 시 자동 재시도할 수 있게 한다.

**Architecture:** `spring-modulith-events-jdbc` 백엔드를 추가해 `event_publication` 테이블에 모든 트랜잭션 이벤트 리스너 호출을 기록한다. 기존 리스너 annotation(`@TransactionalEventListener`, phase/`fallbackExecution` 설정)은 그대로 두고, 이벤트 payload만 `User`/`Account` 전체 객체에서 `UUID userId`/`UUID accountId`로 재설계해 평문 비밀값이 DB에 저장되지 않게 한다. 리스너는 실행 시점에 `UserPort`/`AccountPort`로 재조회한다.

**Tech Stack:** Spring Boot 4, Spring Modulith 2.1.1(`spring-modulith-events-api`+`spring-modulith-events-jdbc`), Flyway, PostgreSQL, JUnit5+Mockito

**Spec:** `docs/superpowers/specs/2026-08-30-event-publication-registry-migration-design.md`

## Global Constraints

- Spring Modulith 버전은 `libs.versions.toml`의 `spring-modulith = "2.1.1"` BOM을 그대로 따른다 — 개별 버전 지정 금지
- 영속화 백엔드는 `spring-modulith-events-jdbc`(JPA 아님) — jar 내 실제 스키마(v2, `useLegacyStructure` 기본 false)를 그대로 Flyway로 복사
- `User`/`Account`를 담던 모든 trading/user 이벤트 record는 `UUID userId`/`UUID accountId`로 교체 — `Strategy`/`TradingReport`/`Execution`/`AccountBalance`/`BigDecimal` 등 암호화 컬럼과 무관한 타입은 그대로 유지
- `@ApplicationModuleListener`로 전환하지 않는다 — 기존 `@TransactionalEventListener`(phase/`fallbackExecution` 그대로) 유지
- 재시도로 인한 중복 텔레그램/FCM 발송은 수용 — 멱등성 장치 추가 금지
- soft-delete로 재조회 실패(`NoSuchElementException`) 시 별도 가드 추가 금지 — publication incomplete 유지가 의도된 동작
- record 필드 변경 후 매 태스크마다 `./gradlew compileTestJava`로 컴파일 오류를 전부 잡아낸 뒤 진행 (`testing.md` "record 필드 수정 시 주의")

---

## Task 1: Modulith Events 의존성 추가 + 재시도 메커니즘 검증

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `src/test/java/com/kista/architecture/EventPublicationRegistryTest.java`

**Interfaces:**
- Produces: `spring-modulith-events-api`/`spring-modulith-events-jdbc` 의존성이 클래스패스에 존재. `org.springframework.modulith.events.IncompleteEventPublications`, `org.springframework.modulith.events.CompletedEventPublications` 빈이 자동 등록됨(`JdbcEventPublicationAutoConfiguration`)

이 태스크는 스펙의 "annotation 유지 근거" 가정(EPR 추적이 리스너 annotation과 무관하게 전역 적용된다)을 실증한다. 검증에는 이미 존재하는 실제 프로덕션 리스너(`TradingAlertNotifier.onMarketClosed`, `@TransactionalEventListener(fallbackExecution = true)`)를 그대로 사용한다 — throwaway 리스너를 만들지 않는다.

- [ ] **Step 1: `libs.versions.toml`에 의존성 좌표 추가**

`gradle/libs.versions.toml`의 Spring Modulith 라이브러리 블록(`spring-modulith-docs` 다음 줄)에 추가:

```toml
spring-modulith-events-api     = { module = "org.springframework.modulith:spring-modulith-events-api" }
spring-modulith-events-jdbc    = { module = "org.springframework.modulith:spring-modulith-events-jdbc" }
```

- [ ] **Step 2: `build.gradle.kts`에 의존성 추가**

`implementation(libs.spring.modulith.starter.core)` 바로 다음 줄에 추가:

```kotlin
    implementation(libs.spring.modulith.events.api)
    implementation(libs.spring.modulith.events.jdbc)
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (의존성만 추가한 상태라 기존 코드 영향 없음)

- [ ] **Step 4: EPR 추적 검증 테스트 작성**

`src/test/java/com/kista/architecture/EventPublicationRegistryTest.java`:

```java
package com.kista.architecture;

import com.kista.notify.application.port.output.NotifyPort;
import com.kista.trading.application.event.MarketClosedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// EPR이 리스너 annotation(@ApplicationModuleListener 아닌 기존 @TransactionalEventListener)과
// 무관하게 전역 추적되는지 실증 — 다음 태스크들의 annotation-미변경 결정의 전제 검증
@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class EventPublicationRegistryTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired IncompleteEventPublications incompleteEventPublications;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean NotifyPort notifyPort;

    @Test
    void 리스너_실패_시_incomplete_publication이_기록되고_재제출로_완료된다() {
        // TradingAlertNotifier.onMarketClosed(@TransactionalEventListener(fallbackExecution=true))가
        // MarketClosedEvent를 소비 — 이 리스너는 어떤 코드도 바꾸지 않은 기존 프로덕션 리스너다
        doThrow(new RuntimeException("강제 실패 1회차"))
                .doNothing()
                .when(notifyPort).notifyMarketClosed();

        try {
            eventPublisher.publishEvent(new MarketClosedEvent());
        } catch (RuntimeException ignored) {
            // fallbackExecution 동기 리스너 예외가 발행자까지 전파됨 — 이 테스트에서는 무시
        }

        Long incompleteCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Long.class);
        assertThat(incompleteCount).isEqualTo(1L);

        incompleteEventPublications.resubmitIncompletePublications(pub -> true);

        Long remainingIncomplete = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Long.class);
        assertThat(remainingIncomplete).isEqualTo(0L);
        verify(notifyPort, times(2)).notifyMarketClosed(); // 최초 실패 1회 + 재제출 성공 1회
    }
}
```

- [ ] **Step 5: 테스트 실행 — 아직 실패해야 함(테이블 없음)**

사전 조건: `docker-compose up -d postgres` (로컬 테스트 DB 기동)

Run: `./gradlew test --tests 'com.kista.architecture.EventPublicationRegistryTest'`
Expected: FAIL — `event_publication` 테이블이 아직 없어 컨텍스트 로딩 또는 쿼리 단계에서 오류(스키마 없음). 이 실패로 테이블이 실제로 필요함을 확인한다.

- [ ] **Step 6: 실제 jar에서 스키마 원본 추출**

```bash
curl -sSL -o /tmp/modulith-events-jdbc.jar \
  "https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-events-jdbc/2.1.1/spring-modulith-events-jdbc-2.1.1.jar"
unzip -p /tmp/modulith-events-jdbc.jar \
  org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql
rm /tmp/modulith-events-jdbc.jar
```

Expected output (이 내용을 Task 2의 Flyway 파일에 그대로 사용):

```sql
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
```

만약 실제 다운로드 결과가 위와 다르면(라이브러리 버전 차이 등), 실제 추출된 내용을 그대로 신뢰하고 Task 2에 반영한다 — 위 내용은 2026-08-30 시점 확인값이다.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts src/test/java/com/kista/architecture/EventPublicationRegistryTest.java
git commit -m "$(cat <<'EOF'
feat(modulith): Event Publication Registry 의존성 추가 + 추적 메커니즘 검증

spring-modulith-events-api/jdbc 추가. 기존 @TransactionalEventListener
(TradingAlertNotifier.onMarketClosed)가 annotation 변경 없이 EPR에
자동 추적되고 IncompleteEventPublications.resubmitIncompletePublications로
재시도 가능함을 통합 테스트로 실증 — 이후 태스크들의 "annotation 유지" 결정의 전제.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Flyway 마이그레이션 + 재기동 자동 재시도 설정

**Files:**
- Create: `src/main/resources/db/migration/V21__event_publication_registry.sql`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1에서 추출한 실제 schema-postgresql.sql 내용
- Produces: `public.event_publication` 테이블. `spring.modulith.events.republish-outstanding-events-on-restart: true` 설정

- [ ] **Step 1: Flyway 마이그레이션 파일 작성**

`src/main/resources/db/migration/V21__event_publication_registry.sql` — Task 1 Step 6에서 추출한 원본에 스키마 접두사만 명시(architecture.md의 3-스키마 분류상 `event_publication`은 플랫폼 공통 인프라 테이블이라 `public` 유지, `audit_logs`/`scheduler_locks`와 동급):

```sql
CREATE TABLE IF NOT EXISTS public.event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON public.event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON public.event_publication (completion_date);
```

(Task 1에서 추출한 실제 내용이 위와 다르면 그 내용 기준으로 `public.` 접두사만 붙여 작성한다.)

- [ ] **Step 2: `application.yml`에 modulith events 설정 추가**

`src/main/resources/application.yml`의 `flyway:` 블록(35-36행, `validate-on-migrate: true` / `default-schema: public`) 바로 다음, `task:` 블록 앞에 삽입:

```yaml
  modulith:
    events:
      jdbc:
        schema: public
      republish-outstanding-events-on-restart: true
```

- [ ] **Step 3: 부팅 검증**

Run: `docker-compose up -d postgres` (아직 안 띄웠다면)
Run: `./gradlew bootRun --args='--spring.profiles.active=local'`
Expected: Flyway가 V21 마이그레이션 적용, `ddl-auto: validate` 통과, 앱 정상 기동. 로그에서 `Migrating schema "public" to version "21"` 확인 후 Ctrl+C로 종료

- [ ] **Step 4: Task 1의 검증 테스트 재실행 — 이제 통과해야 함**

Run: `./gradlew test --tests 'com.kista.architecture.EventPublicationRegistryTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V21__event_publication_registry.sql src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(modulith): event_publication 테이블 + 재기동 자동 재시도 설정

Spring Modulith events-jdbc 실제 스키마(v2)를 Flyway로 반영하고
republish-outstanding-events-on-restart를 활성화한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 사용자 생애주기 이벤트 (가입/승인/거절/재신청) ID화

**Files:**
- Modify: `src/main/java/com/kista/application/event/NewUserRegisteredEvent.java`
- Modify: `src/main/java/com/kista/application/event/UserApprovedEvent.java`
- Modify: `src/main/java/com/kista/application/event/UserRejectedEvent.java`
- Modify: `src/main/java/com/kista/application/event/UserReappliedEvent.java`
- Modify: `src/main/java/com/kista/application/service/user/UserService.java:111,123,136,165,167`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/sse/SseEmitterRegistry.java`
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapterTest.java`

**Interfaces:**
- Produces: `NewUserRegisteredEvent(UUID userId)`, `UserApprovedEvent(UUID userId)`, `UserRejectedEvent(UUID userId)`, `UserReappliedEvent(UUID userId)`

- [ ] **Step 1: 이벤트 record 4개 수정**

`NewUserRegisteredEvent.java`:
```java
package com.kista.application.event;

import java.util.UUID;

// 신규 사용자 등록 성공 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record NewUserRegisteredEvent(UUID userId) {}
```

`UserApprovedEvent.java`:
```java
package com.kista.application.event;

import java.util.UUID;

// 사용자 승인 성공 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record UserApprovedEvent(UUID userId) {}
```

`UserRejectedEvent.java`:
```java
package com.kista.application.event;

import java.util.UUID;

// 사용자 거절 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record UserRejectedEvent(UUID userId) {}
```

`UserReappliedEvent.java`:
```java
package com.kista.application.event;

import java.util.UUID;

// 사용자 재신청 이벤트 — 트랜잭션 커밋 후에만 발행됨
public record UserReappliedEvent(UUID userId) {}
```

- [ ] **Step 2: `UserService.java` 발행 지점 5곳 수정**

line 111: `eventPublisher.publishEvent(new NewUserRegisteredEvent(saved));` → `eventPublisher.publishEvent(new NewUserRegisteredEvent(saved.id()));`

line 123: `eventPublisher.publishEvent(new UserApprovedEvent(updated));` → `eventPublisher.publishEvent(new UserApprovedEvent(updated.id()));`

line 136: `eventPublisher.publishEvent(new UserRejectedEvent(updated));` → `eventPublisher.publishEvent(new UserRejectedEvent(updated.id()));`

line 165: `eventPublisher.publishEvent(new UserReappliedEvent(updated));` → `eventPublisher.publishEvent(new UserReappliedEvent(updated.id()));`

line 167: `eventPublisher.publishEvent(new UserApprovedEvent(updated));` → `eventPublisher.publishEvent(new UserApprovedEvent(updated.id()));`

- [ ] **Step 3: `TelegramUserNotificationAdapter.java` 리스너 4개 수정**

`UserPort` import 추가 및 필드 주입(`@RequiredArgsConstructor`이 처리):

```java
import com.kista.application.port.output.UserPort;
```

```java
    private final TelegramHttpClient telegramHttpClient;
    private final TelegramProperties props;
    private final UserPort userPort; // 이벤트 payload가 ID만 담아 실행 시점 재조회
```

4개 리스너 메서드 수정:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewUserRegistered(NewUserRegisteredEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        if (user.role() == User.UserRole.ADMIN) {
            return;
        }
        if (user.status() == User.UserStatus.ACTIVE) {
            notifyAutoApprovedUser(user);
        } else {
            notifyNewUser(user);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserApproved(UserApprovedEvent event) {
        notifyApproved(userPort.findByIdOrThrow(event.userId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRejected(UserRejectedEvent event) {
        notifyRejected(userPort.findByIdOrThrow(event.userId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserReapplied(UserReappliedEvent event) {
        notifyNewUser(userPort.findByIdOrThrow(event.userId()));
    }
```

- [ ] **Step 4: `SseEmitterRegistry.java` 리스너 2개 수정**

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserApproved(UserApprovedEvent event) {
        notifyStatusChange(event.userId(), User.UserStatus.ACTIVE);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRejected(UserRejectedEvent event) {
        notifyStatusChange(event.userId(), User.UserStatus.REJECTED);
    }
```

(포트 주입 불필요 — 원래도 `event.user().id()`만 썼던 것이 `event.userId()`로 더 단순해짐)

- [ ] **Step 5: `TelegramUserNotificationAdapterTest.java` 수정**

`@Mock UserPort userPort;` 필드 추가, `setUp()`에서 어댑터 생성자에 주입:

```java
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    @Mock
    UserPort userPort;

    TelegramUserNotificationAdapter adapter;
```

```java
    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restClient);
        adapter = new TelegramUserNotificationAdapter(httpClient, PROPS, userPort);
    }
```

import 추가: `import com.kista.application.port.output.UserPort;`

`onNewUserRegistered_pending_sendsApprovalRequestWithButtons`:
```java
    @Test
    void onNewUserRegistered_pending_sendsApprovalRequestWithButtons() {
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), User.UserStatus.PENDING);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        adapter.onNewUserRegistered(new NewUserRegisteredEvent(user.id()));

        verify(restClient.post()).uri(contains("/sendMessage"));
    }
```

`onNewUserRegistered_activeNonAdmin_sendsAutoApprovedInfoMessage`:
```java
    @Test
    @SuppressWarnings("unchecked")
    void onNewUserRegistered_activeNonAdmin_sendsAutoApprovedInfoMessage() {
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), User.UserStatus.ACTIVE, User.UserRole.USER);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.onNewUserRegistered(new NewUserRegisteredEvent(user.id()));

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        assertThat(((Map<String, String>) bodyCaptor.getValue()).get("text")).contains("자동 승인");
    }
```

`onNewUserRegistered_activeAdmin_skipsNotification`:
```java
    @Test
    void onNewUserRegistered_activeAdmin_skipsNotification() {
        User user = DomainFixtures.userWithStatus(UUID.randomUUID(), User.UserStatus.ACTIVE, User.UserRole.ADMIN);
        when(userPort.findByIdOrThrow(user.id())).thenReturn(user);

        adapter.onNewUserRegistered(new NewUserRegisteredEvent(user.id()));

        verifyNoInteractions(restClient);
    }
```

`onNewUserRegistered_userNotFound_propagatesException` (soft-delete 등으로 재조회 대상이 사라진 경우 — 예외가 리스너 밖으로 전파돼야 EPR이 해당 publication을 incomplete로 유지한다, 스펙 "오류 처리" 절):
```java
    @Test
    void onNewUserRegistered_userNotFound_propagatesException() {
        UUID missingUserId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(missingUserId))
                .thenThrow(new NoSuchElementException("사용자를 찾을 수 없습니다: " + missingUserId));

        assertThatThrownBy(() -> adapter.onNewUserRegistered(new NewUserRegisteredEvent(missingUserId)))
                .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(restClient);
    }
```

import 추가: `java.util.NoSuchElementException`, `static org.assertj.core.api.Assertions.assertThatThrownBy`

- [ ] **Step 6: 컴파일 + 관련 테스트 실행**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL — `UserServiceTest`는 `any(NewUserRegisteredEvent.class)` 형태 matcher만 써서 record 필드 변경에 영향받지 않음(직접 확인됨). 다른 컴파일 오류가 나오면 이 태스크 범위(4개 이벤트) 안에서 발생한 것이므로 같은 패턴(`.user()`/`event.user()` 참조 → `.userId()`)으로 수정

Run: `./gradlew test --tests 'com.kista.notify.adapter.out.gateway.TelegramUserNotificationAdapterTest' --tests 'com.kista.adapter.out.sse.SseEmitterRegistryTest' --tests 'com.kista.application.service.user.UserServiceTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kista/application/event/NewUserRegisteredEvent.java \
        src/main/java/com/kista/application/event/UserApprovedEvent.java \
        src/main/java/com/kista/application/event/UserRejectedEvent.java \
        src/main/java/com/kista/application/event/UserReappliedEvent.java \
        src/main/java/com/kista/application/service/user/UserService.java \
        src/main/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapter.java \
        src/main/java/com/kista/adapter/out/sse/SseEmitterRegistry.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TelegramUserNotificationAdapterTest.java
git commit -m "$(cat <<'EOF'
refactor(notify): 사용자 생애주기 이벤트 payload를 ID 기반으로 재설계

NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/UserReappliedEvent가
User 전체 객체 대신 userId만 담도록 변경 — EPR이 이벤트를 JSON 직렬화해
DB에 저장하므로, User의 평문 telegramBotToken 등이 저장되지 않게 하기 위함.
리스너는 실행 시점에 UserPort로 재조회한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 사이클 생애주기 + 장 이벤트 (Cycle/NewCycle/BatchInterrupted/MarketOpen/MarketClose) ID화

**Files:**
- Modify: `src/main/java/com/kista/trading/application/event/CycleCompletedEvent.java`
- Modify: `src/main/java/com/kista/trading/application/event/CycleEndedEvent.java`
- Modify: `src/main/java/com/kista/trading/application/event/NewCycleStartedEvent.java`
- Modify: `src/main/java/com/kista/trading/application/event/BatchInterruptedEvent.java`
- Modify: `src/main/java/com/kista/trading/application/event/MarketOpenEvent.java`
- Modify: `src/main/java/com/kista/trading/application/event/MarketCloseEvent.java`
- Modify: `src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java:62`
- Modify: `src/main/java/com/kista/application/service/admin/AdminTradeCorrectionService.java:90`
- Modify: `src/main/java/com/kista/trading/application/service/CycleRotationService.java:86`
- Modify: `src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java:166`
- Modify: `src/main/java/com/kista/trading/application/service/VrReconfigureService.java:130`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java:239`
- Modify: `src/main/java/com/kista/trading/application/service/MarketEventNotifier.java:34,38`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/CycleEndedNotifier.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/CycleLifecycleNotifier.java`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java` (onMarketOpen/onMarketClose/onBatchInterrupted만, onTradingError/onInsufficientBalance는 Task 5/6)
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/CycleEndedNotifierTest.java`
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/CycleLifecycleNotifierTest.java`
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java`
- Test: `src/test/java/com/kista/trading/application/service/CyclePositionPersistorTest.java:168`
- Test: `src/test/java/com/kista/trading/application/service/VrCycleRolloverServiceTest.java:427`
- Test: `src/test/java/com/kista/trading/application/service/MarketEventNotifierTest.java:128,152,153`

**Interfaces:**
- Produces: `CycleCompletedEvent(UUID userId, UUID accountId, Strategy strategy)`, `CycleEndedEvent(UUID userId, UUID accountId, Strategy strategy)`, `NewCycleStartedEvent(UUID userId, UUID accountId, Strategy strategy, BigDecimal initialUsdDeposit)`, `BatchInterruptedEvent(UUID userId, UUID accountId)`, `MarketOpenEvent(UUID userId)`, `MarketCloseEvent(UUID userId)`
- Consumes: `UserPort.findByIdOrThrow(UUID)`, `AccountPort.findByIdOrThrow(UUID)` (Task 3에서 확인된 시그니처)

- [ ] **Step 1: 이벤트 record 6개 수정**

```java
// CycleCompletedEvent.java
package com.kista.trading.application.event;

import com.kista.domain.model.strategy.Strategy;

import java.util.UUID;

// 사이클 종료(holdings=0) 이벤트 — 발행처 트랜잭션 유무와 무관하게 리스너에서 알림 채널 라우팅 처리
public record CycleCompletedEvent(UUID userId, UUID accountId, Strategy strategy) {}
```

```java
// CycleEndedEvent.java
package com.kista.trading.application.event;

import com.kista.domain.model.strategy.Strategy;

import java.util.UUID;

// 관리자 수동 체결 보정으로 사이클이 종료됨 — 트랜잭션 커밋 후에만 발행됨 (사용자 알림용)
public record CycleEndedEvent(UUID userId, UUID accountId, Strategy strategy) {}
```

```java
// NewCycleStartedEvent.java
package com.kista.trading.application.event;

import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.util.UUID;

// 새 사이클 시작 이벤트 — 발행처 트랜잭션 유무와 무관하게 리스너에서 알림 채널 라우팅 처리
public record NewCycleStartedEvent(UUID userId, UUID accountId, Strategy strategy, BigDecimal initialUsdDeposit) {}
```

```java
// BatchInterruptedEvent.java
package com.kista.trading.application.event;

import java.util.UUID;

// 스케쥴러 인터럽트(배포·재기동) 사용자 알림 (UserNotificationPort.notifyBatchInterrupted)
public record BatchInterruptedEvent(UUID userId, UUID accountId) {}
```

```java
// MarketOpenEvent.java
package com.kista.trading.application.event;

import java.util.UUID;

// 사용자별 장 개시 알림 (UserNotificationPort.notifyMarketOpen) — MarketEventNotifier가 ACTIVE 사용자마다 1건씩 발행
public record MarketOpenEvent(UUID userId) {}
```

```java
// MarketCloseEvent.java
package com.kista.trading.application.event;

import java.util.UUID;

// 사용자별 장 마감 알림 (UserNotificationPort.notifyMarketClose) — MarketClosedEvent(관리자·휴장 알림)와는 별개 이벤트
public record MarketCloseEvent(UUID userId) {}
```

- [ ] **Step 2: 발행처 8곳 수정**

`CyclePositionPersistor.java:62`:
`eventPublisher.publishEvent(new CycleCompletedEvent(ctx.user(), ctx.account(), strategy));` → `eventPublisher.publishEvent(new CycleCompletedEvent(ctx.user().id(), ctx.account().id(), strategy));`

`AdminTradeCorrectionService.java:90`:
`eventPublisher.publishEvent(new CycleEndedEvent(user, account, updatedStrategy));` → `eventPublisher.publishEvent(new CycleEndedEvent(user.id(), account.id(), updatedStrategy));`

`CycleRotationService.java:86`:
`eventPublisher.publishEvent(new NewCycleStartedEvent(user, account, strategy, targetSeed));` → `eventPublisher.publishEvent(new NewCycleStartedEvent(user.id(), account.id(), strategy, targetSeed));`

`VrCycleRolloverService.java:166`:
`eventPublisher.publishEvent(new NewCycleStartedEvent(ctx.user(), ctx.account(), strategy, adjustedPool));` → `eventPublisher.publishEvent(new NewCycleStartedEvent(ctx.user().id(), ctx.account().id(), strategy, adjustedPool));`

`VrReconfigureService.java:130`:
`eventPublisher.publishEvent(new NewCycleStartedEvent(user, account, strategy, postBalance.usdDeposit()));` → `eventPublisher.publishEvent(new NewCycleStartedEvent(user.id(), account.id(), strategy, postBalance.usdDeposit()));`

`TradingService.java:239`:
`eventPublisher.publishEvent(new BatchInterruptedEvent(ctx.user(), ctx.account()));` → `eventPublisher.publishEvent(new BatchInterruptedEvent(ctx.user().id(), ctx.account().id()));`

`MarketEventNotifier.java:34,38`:
```java
    void notifyMarketOpen() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketOpenEvent(user.id())));
    }

    void notifyMarketClose() {
        notify(NotificationType.MARKET_ALERT, user -> eventPublisher.publishEvent(new MarketCloseEvent(user.id())));
    }
```

- [ ] **Step 3: `CycleEndedNotifier.java` 수정**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 관리자 수동 체결 보정으로 사이클이 종료됨을 트랜잭션 커밋 후 사용자에게 알림 (SSE/FCM/텔레그램 호출을 트랜잭션 밖으로 격리)
@Component
@RequiredArgsConstructor
public class CycleEndedNotifier {

    private final UserNotificationPort userNotificationPort;
    private final UserPort userPort;       // 이벤트 payload가 ID만 담아 실행 시점 재조회
    private final AccountPort accountPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCycleEnded(CycleEndedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyCycleCompleted(user, account, event.strategy());
    }
}
```

- [ ] **Step 4: `CycleLifecycleNotifier.java` 수정**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.trading.application.event.CycleCompletedEvent;
import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 사이클 종료/신규 시작 알림을 채널(Telegram/FCM) 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
@Component
@RequiredArgsConstructor
class CycleLifecycleNotifier {

    private final UserNotificationPort userNotificationPort;
    private final UserPort userPort;
    private final AccountPort accountPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onCycleCompleted(CycleCompletedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyCycleCompleted(user, account, event.strategy());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNewCycleStarted(NewCycleStartedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyNewCycleStarted(user, account, event.strategy(), event.initialUsdDeposit());
    }
}
```

- [ ] **Step 5: `TradingAlertNotifier.java`에 포트 필드 추가 + onMarketOpen/onMarketClose/onBatchInterrupted 수정**

`onTradingError`/`onInsufficientBalance`는 이 태스크에서 **손대지 않는다** — `TradingErrorEvent`/`InsufficientBalanceEvent` record는 Task 5/6까지 원래 필드(`user()`/`e()`/`account()`/`b()`)를 그대로 유지하므로, 이 두 메서드는 원본 그대로 두면 컴파일도 동작도 깨지지 않는다. 아래는 파일 전체 diff다:

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.trading.application.event.BatchInterruptedEvent;
import com.kista.trading.application.event.InsufficientBalanceEvent;
import com.kista.trading.application.event.MarketClosedEvent;
import com.kista.trading.application.event.MarketCloseEvent;
import com.kista.trading.application.event.MarketOpenEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.notify.application.port.output.NotifyPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TradingAlertNotifier {

    private final NotifyPort notifyPort;
    private final UserNotificationPort userNotificationPort;
    private final UserPort userPort;       // Task 4에서 신규 추가 — onMarketOpen/onMarketClose/onBatchInterrupted용
    private final AccountPort accountPort; // Task 4에서 신규 추가 — onBatchInterrupted용

    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingError(TradingErrorEvent event) {
        // 원본 그대로 유지 — Task 6에서 event.userId()/event.message() 기반으로 수정
        if (event.user() == null) {
            notifyPort.notifyError(event.e());
        } else {
            userNotificationPort.notifyError(event.user(), event.e());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        // 원본 그대로 유지 — Task 5에서 event.userId()/event.accountId() 기반으로 수정
        if (event.user() == null) {
            notifyPort.notifyInsufficientBalance(event.account(), event.b(), event.ticker());
        } else {
            userNotificationPort.notifyInsufficientBalance(event.user(), event.account(), event.strategyType(), event.ticker());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClosed(MarketClosedEvent event) {
        notifyPort.notifyMarketClosed();
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketOpen(MarketOpenEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        userNotificationPort.notifyMarketOpen(user);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onMarketClose(MarketCloseEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        userNotificationPort.notifyMarketClose(user);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBatchInterrupted(BatchInterruptedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyBatchInterrupted(user, account);
    }
}
```

이 상태로 `TradingAlertNotifier`는 완전히 동작하며(onTradingError/onInsufficientBalance는 기존 그대로, 나머지 3개는 새 방식), 이 태스크만으로 독립적으로 커밋·리뷰 가능하다.

- [ ] **Step 6: `CycleEndedNotifierTest.java` 수정**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleEndedNotifierTest {

    @Mock UserNotificationPort userNotificationPort;
    @Mock UserPort userPort;
    @Mock AccountPort accountPort;

    @Test
    void onCycleEnded_notifiesUserOfCycleCompletion() {
        CycleEndedNotifier notifier = new CycleEndedNotifier(userNotificationPort, userPort, accountPort);
        UUID userId = UUID.randomUUID();
        Account account = DomainFixtures.kisAccount(UUID.randomUUID(), userId);
        User user = DomainFixtures.activeUserWithTelegram(userId);
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.PRIVACY,
                Strategy.Status.PAUSED, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);
        CycleEndedEvent event = new CycleEndedEvent(userId, account.id(), strategy);

        notifier.onCycleEnded(event);

        verify(userNotificationPort).notifyCycleCompleted(user, account, strategy);
    }
}
```

- [ ] **Step 7: `CycleLifecycleNotifierTest.java` 수정**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.trading.application.event.CycleCompletedEvent;
import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.user.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleLifecycleNotifierTest {

    @Mock UserNotificationPort userNotificationPort;
    @Mock UserPort userPort;
    @Mock AccountPort accountPort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), USER_ID);
    private static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);
    private static final Strategy STRATEGY = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAINTAIN);

    @Test
    void onCycleCompleted_notifiesUserOfCycleCompletion() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(USER);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort, userPort, accountPort);
        CycleCompletedEvent event = new CycleCompletedEvent(USER_ID, ACCOUNT.id(), STRATEGY);

        notifier.onCycleCompleted(event);

        verify(userNotificationPort).notifyCycleCompleted(USER, ACCOUNT, STRATEGY);
    }

    @Test
    void onNewCycleStarted_notifiesUserOfNewCycle() {
        when(userPort.findByIdOrThrow(USER_ID)).thenReturn(USER);
        when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
        CycleLifecycleNotifier notifier = new CycleLifecycleNotifier(userNotificationPort, userPort, accountPort);
        BigDecimal initialUsdDeposit = new BigDecimal("1000.00");
        NewCycleStartedEvent event = new NewCycleStartedEvent(USER_ID, ACCOUNT.id(), STRATEGY, initialUsdDeposit);

        notifier.onNewCycleStarted(event);

        verify(userNotificationPort).notifyNewCycleStarted(USER, ACCOUNT, STRATEGY, initialUsdDeposit);
    }
}
```

- [ ] **Step 8: `TradingAlertNotifierTest.java`의 onMarketOpen/onMarketClose/onBatchInterrupted 3개 테스트만 수정**

`onTradingError_*`/`onInsufficientBalance_*` 4개 테스트는 이 태스크에서 손대지 않는다 — 프로덕션 코드가 원본 그대로라 그 4개 테스트도 원본 그대로 컴파일·통과한다(Task 5/6에서 이벤트 record가 바뀔 때 함께 수정).

```java
    @Mock NotifyPort notifyPort;
    @Mock UserNotificationPort userNotificationPort;
    @Mock UserPort userPort;
    @Mock AccountPort accountPort;

    private final UUID userId = UUID.randomUUID();
    private final Account account = DomainFixtures.kisAccount(UUID.randomUUID(), userId);
    private final User user = DomainFixtures.activeUserWithTelegram(userId);

    private TradingAlertNotifier notifier() {
        return new TradingAlertNotifier(notifyPort, userNotificationPort, userPort, accountPort);
    }
```

import 추가: `com.kista.application.port.output.AccountPort`, `com.kista.application.port.output.UserPort`

```java
    @Test
    void onMarketOpen_callsUserNotificationPort() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onMarketOpen(new MarketOpenEvent(userId));

        verify(userNotificationPort).notifyMarketOpen(user);
    }

    @Test
    void onMarketClose_callsUserNotificationPort() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onMarketClose(new MarketCloseEvent(userId));

        verify(userNotificationPort).notifyMarketClose(user);
    }

    @Test
    void onBatchInterrupted_callsUserNotificationPort() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);

        notifier().onBatchInterrupted(new BatchInterruptedEvent(userId, account.id()));

        verify(userNotificationPort).notifyBatchInterrupted(user, account);
    }
```

- [ ] **Step 9: `CyclePositionPersistorTest.java:168`, `VrCycleRolloverServiceTest.java:427`, `MarketEventNotifierTest.java:128,152,153` 수정**

`CyclePositionPersistorTest.java:168`:
`verify(eventPublisher).publishEvent(new CycleCompletedEvent(USER, ACCOUNT, strategy));` → `verify(eventPublisher).publishEvent(new CycleCompletedEvent(USER.id(), ACCOUNT.id(), strategy));`

`VrCycleRolloverServiceTest.java:427`:
`verify(eventPublisher).publishEvent(new NewCycleStartedEvent(USER, ACCOUNT, VR_STRATEGY, USD_DEPOSIT));` → `verify(eventPublisher).publishEvent(new NewCycleStartedEvent(USER.id(), ACCOUNT.id(), VR_STRATEGY, USD_DEPOSIT));`

`MarketEventNotifierTest.java:128`:
`doThrow(new RuntimeException("텔레그램 발송 실패")).when(eventPublisher).publishEvent(new MarketOpenEvent(failingUser));` → `doThrow(new RuntimeException("텔레그램 발송 실패")).when(eventPublisher).publishEvent(new MarketOpenEvent(failingUser.id()));`

`MarketEventNotifierTest.java:152-153`:
```java
        verify(eventPublisher, times(1)).publishEvent(new MarketOpenEvent(enabledUser.id()));
        verify(eventPublisher, never()).publishEvent(new MarketOpenEvent(disabledUser.id()));
```

- [ ] **Step 10: `./gradlew compileTestJava` 실행**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL — 오류가 나오면 이 태스크 범위(6개 이벤트) 안에서 놓친 참조가 있는 것이므로 같은 패턴으로 수정 후 재확인

- [ ] **Step 11: 이 태스크 범위 테스트 실행 (TradingAlertNotifierTest 포함, 전체 통과해야 함)**

Run: `./gradlew test --tests 'com.kista.notify.adapter.out.gateway.CycleEndedNotifierTest' --tests 'com.kista.notify.adapter.out.gateway.CycleLifecycleNotifierTest' --tests 'com.kista.notify.adapter.out.gateway.TradingAlertNotifierTest' --tests 'com.kista.trading.application.service.CyclePositionPersistorTest' --tests 'com.kista.trading.application.service.VrCycleRolloverServiceTest' --tests 'com.kista.trading.application.service.MarketEventNotifierTest'`
Expected: PASS 전체 — `TradingAlertNotifierTest`의 `onTradingError_*`/`onInsufficientBalance_*` 4개도 원본 그대로 통과한다

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/kista/trading/application/event/CycleCompletedEvent.java \
        src/main/java/com/kista/trading/application/event/CycleEndedEvent.java \
        src/main/java/com/kista/trading/application/event/NewCycleStartedEvent.java \
        src/main/java/com/kista/trading/application/event/BatchInterruptedEvent.java \
        src/main/java/com/kista/trading/application/event/MarketOpenEvent.java \
        src/main/java/com/kista/trading/application/event/MarketCloseEvent.java \
        src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java \
        src/main/java/com/kista/application/service/admin/AdminTradeCorrectionService.java \
        src/main/java/com/kista/trading/application/service/CycleRotationService.java \
        src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java \
        src/main/java/com/kista/trading/application/service/VrReconfigureService.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/main/java/com/kista/trading/application/service/MarketEventNotifier.java \
        src/main/java/com/kista/notify/adapter/out/gateway/CycleEndedNotifier.java \
        src/main/java/com/kista/notify/adapter/out/gateway/CycleLifecycleNotifier.java \
        src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java \
        src/test/java/com/kista/notify/adapter/out/gateway/CycleEndedNotifierTest.java \
        src/test/java/com/kista/notify/adapter/out/gateway/CycleLifecycleNotifierTest.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java \
        src/test/java/com/kista/trading/application/service/CyclePositionPersistorTest.java \
        src/test/java/com/kista/trading/application/service/VrCycleRolloverServiceTest.java \
        src/test/java/com/kista/trading/application/service/MarketEventNotifierTest.java
git commit -m "$(cat <<'EOF'
refactor(trading,notify): 사이클/장 이벤트 payload를 ID 기반으로 재설계

CycleCompletedEvent/CycleEndedEvent/NewCycleStartedEvent/BatchInterruptedEvent/
MarketOpenEvent/MarketCloseEvent가 User/Account 대신 ID만 담도록 변경.
TradingAlertNotifier의 onTradingError/onInsufficientBalance는 원본 그대로 유지
(대상 이벤트가 아직 안 바뀌어 손댈 필요 없음) — Task 5/6에서 이어서 전환한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: InsufficientBalanceEvent ID화

**Files:**
- Modify: `src/main/java/com/kista/trading/application/event/InsufficientBalanceEvent.java`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java:450`
- Modify: `src/main/java/com/kista/trading/application/service/CycleRotationService.java:75`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java` (onInsufficientBalance만)
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java` (onInsufficientBalance 2개)
- Test: `src/test/java/com/kista/trading/application/service/TradingServiceTest.java` (11개 발행 지점 + 1개 field-access)

**Interfaces:**
- Produces: `InsufficientBalanceEvent(UUID userId, UUID accountId, AccountBalance b, Strategy.Ticker ticker, Strategy.Type strategyType)`

- [ ] **Step 1: 이벤트 record 수정**

```java
package com.kista.trading.application.event;

import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.AccountBalance;

import java.util.UUID;

// 예수금 부족 알림 — userId==null이면 관리자 알림(NotifyPort.notifyInsufficientBalance(account,b,ticker),
// b 필수/strategyType 미사용), non-null이면 사용자 알림(UserNotificationPort.notifyInsufficientBalance(
// user,account,strategyType,ticker), strategyType 필수/b 미사용) — 두 포트 메서드의 파라미터 합집합을
// 한 이벤트에 담고, 발행처가 쓰지 않는 쪽 필드는 null로 둔다
public record InsufficientBalanceEvent(UUID userId, UUID accountId, AccountBalance b,
                                        Strategy.Ticker ticker, Strategy.Type strategyType) {}
```

- [ ] **Step 2: 발행처 2곳 수정**

`TradingService.java:450` 부근:
```java
            for (BatchContext ctx : rejectedContexts) {
                runSafely("예수금 부족 알림", ctx, () -> {
                        eventPublisher.publishEvent(new InsufficientBalanceEvent(
                                ctx.user().id(), ctx.account().id(), null, ctx.strategy().ticker(), ctx.strategy().type()));
                        return null;
                    });
            }
```

`CycleRotationService.java:75`:
```java
            eventPublisher.publishEvent(new InsufficientBalanceEvent(null, account.id(),
                    new AccountBalance(0, null, targetSeed), strategy.ticker(), null));
```

- [ ] **Step 3: `TradingAlertNotifier.onInsufficientBalance` 수정**

```java
    @TransactionalEventListener(fallbackExecution = true)
    public void onInsufficientBalance(InsufficientBalanceEvent event) {
        Account account = accountPort.findByIdOrThrow(event.accountId());
        if (event.userId() == null) {
            notifyPort.notifyInsufficientBalance(account, event.b(), event.ticker());
        } else {
            User user = userPort.findByIdOrThrow(event.userId());
            userNotificationPort.notifyInsufficientBalance(user, account, event.strategyType(), event.ticker());
        }
    }
```

- [ ] **Step 4: `TradingAlertNotifierTest.java`의 onInsufficientBalance 2개 테스트 수정**

```java
    @Test
    void onInsufficientBalance_adminPath_callsNotifyPortWithAccountBalance() {
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("100.00"));

        notifier().onInsufficientBalance(new InsufficientBalanceEvent(null, account.id(), balance, Ticker.SOXL, null));

        verify(notifyPort).notifyInsufficientBalance(account, balance, Ticker.SOXL);
        verify(userNotificationPort, never()).notifyInsufficientBalance(any(), any(), any(), any());
    }

    @Test
    void onInsufficientBalance_userPath_callsUserNotificationPortWithStrategyType() {
        when(accountPort.findByIdOrThrow(account.id())).thenReturn(account);
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onInsufficientBalance(
                new InsufficientBalanceEvent(userId, account.id(), null, Ticker.SOXL, Strategy.Type.INFINITE));

        verify(userNotificationPort).notifyInsufficientBalance(user, account, Strategy.Type.INFINITE, Ticker.SOXL);
        verify(notifyPort, never()).notifyInsufficientBalance(any(), any(), any());
    }
```

- [ ] **Step 5: `TradingServiceTest.java`의 InsufficientBalanceEvent 직접 생성 11곳 수정**

아래 각 줄에서 `USER`→`USER.id()`, `ACCOUNT`→`ACCOUNT.id()`, `failingUser`→`failingUser.id()`, `failingAccount`→`failingAccount.id()`, `succeedingUser`→`succeedingUser.id()`, `succeedingAccount`→`succeedingAccount.id()`로 치환(값은 그대로, 첫 두 인자만 `.id()` 추가):

- line 485: `new InsufficientBalanceEvent(USER.id(), ACCOUNT.id(), null, Ticker.SOXL, Strategy.Type.INFINITE)`
- line 514: 동일 패턴
- line 569: 동일 패턴
- line 604: 동일 패턴
- line 680: `new InsufficientBalanceEvent(USER.id(), ACCOUNT.id(), null, Ticker.SOXL, Strategy.Type.PRIVACY)`
- line 831: `new InsufficientBalanceEvent(USER.id(), ACCOUNT.id(), null, Ticker.SOXL, Strategy.Type.INFINITE)`
- line 876: 동일 패턴
- line 906: 동일 패턴
- line 1019: `new InsufficientBalanceEvent(failingUser.id(), failingAccount.id(), null, Ticker.SOXL, Strategy.Type.INFINITE)`
- line 1028: `new InsufficientBalanceEvent(succeedingUser.id(), succeedingAccount.id(), null, Ticker.SOXL, Strategy.Type.INFINITE)`
- line 1067: `new InsufficientBalanceEvent(USER.id(), ACCOUNT.id(), null, Ticker.SOXL, Strategy.Type.PRIVACY)`

그리고 line 1753(field-access, `never()`):
```java
        verify(eventPublisher, never()).publishEvent(argThat((Object ev) -> ev instanceof InsufficientBalanceEvent ibe
                && ibe.userId() == null && ACCOUNT.id().equals(ibe.accountId()) && ibe.b() != null && ibe.ticker() == Ticker.SOXL));
```

- [ ] **Step 6: 컴파일 + 전체 관련 테스트 실행**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL — `CycleRotationServiceTest`는 `any(InsufficientBalanceEvent.class)` matcher만 써서 영향 없음(확인됨)

Run: `./gradlew test --tests 'com.kista.notify.adapter.out.gateway.TradingAlertNotifierTest' --tests 'com.kista.trading.application.service.TradingServiceTest' --tests 'com.kista.trading.application.service.CycleRotationServiceTest'`
Expected: PASS 전체 — `TradingAlertNotifierTest`의 `onTradingError_*` 2개는 `TradingErrorEvent`가 아직 원본이라 영향받지 않고 그대로 통과한다

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kista/trading/application/event/InsufficientBalanceEvent.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/main/java/com/kista/trading/application/service/CycleRotationService.java \
        src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading,notify): InsufficientBalanceEvent payload를 ID 기반으로 재설계

TradingAlertNotifier.onTradingError는 TradingErrorEvent가 아직 원본이라
손대지 않았다 — Task 6에서 이어서 전환한다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: TradingErrorEvent ID화 + Exception→String 재설계

**Files:**
- Modify: `src/main/java/com/kista/trading/application/event/TradingErrorEvent.java`
- Modify: `src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java:93`
- Modify: `src/main/java/com/kista/trading/application/service/TradingService.java:474,559,564`
- Modify: `src/main/java/com/kista/trading/application/service/TradingErrorReporter.java:18`
- Modify: `src/main/java/com/kista/trading/application/service/ManualTradingService.java:135`
- Modify: `src/main/java/com/kista/trading/application/service/TradingReporter.java:88`
- Modify: `src/main/java/com/kista/trading/application/service/VrReconfigureService.java:133`
- Modify: `src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java:108,125`
- Modify: `src/main/java/com/kista/trading/application/service/CycleRotationService.java:134,141`
- Modify: `src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java:50,55,70,85,90,108,110,133,135`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java` (onTradingError만)
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java` (onTradingError 2개)
- Test: `src/test/java/com/kista/trading/application/service/TradingServiceTest.java` (9곳)
- Test: `src/test/java/com/kista/trading/application/service/VrCycleRolloverServiceTest.java` (7곳)
- Test: `src/test/java/com/kista/trading/application/service/CycleRotationServiceTest.java` (2곳)
- Test: `src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java` (1곳)

**Interfaces:**
- Produces: `TradingErrorEvent(UUID userId, String message)`

이 record는 소비처(`NotifyPort.notifyError(Exception)`/`UserNotificationPort.notifyError(User,Exception)`) 시그니처를 바꾸지 않는다 — 리스너가 `new RuntimeException(event.message())`로 재포장한다. 모든 소비처가 기존에도 `e.getMessage()`만 사용했음을 코드로 확인했다(`FcmAdapter`/`TelegramAdapter`/`TelegramUserNotificationAdapter`).

- [ ] **Step 1: 이벤트 record 수정**

```java
package com.kista.trading.application.event;

import java.util.UUID;

// 관리자/사용자 매매 오류 알림 — userId==null이면 관리자 전용(NotifyPort.notifyError(Exception)),
// non-null이면 사용자 알림(UserNotificationPort.notifyError(User,Exception)). 동일 오류를 관리자+사용자
// 양쪽에 알려야 하는 발행처는 이 이벤트를 두 번(null, 실제 userId) 각각 발행한다.
// message는 원본 Exception.getMessage() — 소비처 전부 메시지 텍스트만 사용해 정보 손실 없음
public record TradingErrorEvent(UUID userId, String message) {}
```

- [ ] **Step 2: 발행처 21곳 수정**

`TradingPriceFetcher.java:93-94`:
```java
        if (!failedTickers.isEmpty()) {
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    failedTickers + " " + label + " 조회 실패(일괄+단건 모두 실패)"));
        }
```

`TradingService.java:474-475`:
```java
        } catch (InterruptedException e) {
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[스케쥴러 인터럽트] " + label + " 대기 중 강제 종료 — PLANNED 주문 접수 미실행 가능"));
            throw e;
        }
```

`TradingService.java:559,564` (`notifyErrorSafely`):
```java
    private void notifyErrorSafely(BatchContext ctx, Exception e) {
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 관리자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
        try {
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user().id(), e.getMessage()));
        } catch (Exception notifyEx) {
            log.warn("[strategyId={}] 사용자 오류 알림 실패: {}", ctx.strategy().id(), notifyEx.getMessage());
        }
    }
```

`TradingErrorReporter.java:18`:
```java
    @Override
    public void reportError(Exception e) {
        eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
    }
```

`ManualTradingService.java:135`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`TradingReporter.java:88`:
```java
                    eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`VrReconfigureService.java:133`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`TradingOrderExecutor.java:108`:
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`TradingOrderExecutor.java:125-126`:
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null,
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + result.externalOrderId()));
```

`CycleRotationService.java:134-135`:
```java
                eventPublisher.publishEvent(new TradingErrorEvent(null,
                        "재등록 실패: USD 잔고 없음 strategyId=" + strategy.id()));
```

`CycleRotationService.java:141`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`VrCycleRolloverService.java:50`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`VrCycleRolloverService.java:55-56`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "VR 사이클 상세 누락 strategyId=" + strategy.id() + " cycleId=" + cycle.id()));
```

`VrCycleRolloverService.java:70-71`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "VR 롤오버 종가 없음 strategyId=" + strategy.id()));
```

`VrCycleRolloverService.java:85`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
```

`VrCycleRolloverService.java:90-91`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "VR 롤오버 due일 확정 종가 없음 strategyId=" + strategy.id() + " evaluationDate=" + evaluationDate));
```

`VrCycleRolloverService.java:108-109`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "VR 인출 반영 후 예수금 음수 — 롤오버 보류: strategyId=" + strategy.id() + " adjustedPool=" + adjustedPool));
```

`VrCycleRolloverService.java:110-111`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user().id(),
                    "VR 인출 금액이 예수금을 초과합니다 — 설정 조정 필요: strategyId=" + strategy.id()));
```

`VrCycleRolloverService.java:133-134`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "VR V′≤0 — 롤오버 보류: strategyId=" + strategy.id() + " newValue=" + newValue));
```

`VrCycleRolloverService.java:135-136`:
```java
            eventPublisher.publishEvent(new TradingErrorEvent(ctx.user().id(),
                    "VR V′≤0 — 설정 조정 필요: strategyId=" + strategy.id()));
```

- [ ] **Step 3: `TradingAlertNotifier.onTradingError` 수정**

```java
    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingError(TradingErrorEvent event) {
        if (event.userId() == null) {
            notifyPort.notifyError(new RuntimeException(event.message()));
        } else {
            User user = userPort.findByIdOrThrow(event.userId());
            userNotificationPort.notifyError(user, new RuntimeException(event.message()));
        }
    }
```

- [ ] **Step 4: `TradingAlertNotifierTest.java`의 onTradingError 2개 테스트 수정**

```java
    @Test
    void onTradingError_adminPath_callsNotifyPortWhenUserIsNull() {
        notifier().onTradingError(new TradingErrorEvent(null, "배치 오류"));

        verify(notifyPort).notifyError(argThat(e -> "배치 오류".equals(e.getMessage())));
        verify(userNotificationPort, never()).notifyError(any(), any());
    }

    @Test
    void onTradingError_userPath_callsUserNotificationPortWhenUserPresent() {
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);

        notifier().onTradingError(new TradingErrorEvent(userId, "사용자 매매 오류"));

        verify(userNotificationPort).notifyError(eq(user), argThat(e -> "사용자 매매 오류".equals(e.getMessage())));
        verify(notifyPort, never()).notifyError(any());
    }
```

import 추가 확인: `static org.mockito.ArgumentMatchers.eq`, `static org.mockito.ArgumentMatchers.argThat` (이미 `import static org.mockito.ArgumentMatchers.*;`로 와일드카드 임포트돼 있어 추가 불필요)

- [ ] **Step 5: `TradingServiceTest.java` 9곳 수정**

line 1021(생성):
```java
        doThrow(new RuntimeException("secondary user notify failure"))
                .when(eventPublisher).publishEvent(new TradingErrorEvent(failingUser.id(), notificationFailure.getMessage()));
```

line 540(field-access, `tee.user()`→`tee.userId()`, `tee.e()==X`→`tee.message().equals(X.getMessage())`):
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null && tee.message().equals(saveFailure.getMessage())));
```

line 947: 동일 패턴, `balanceFailure` 사용:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null && tee.message().equals(balanceFailure.getMessage())));
```

line 991: 동일 패턴, `saveFailure` 사용:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null && tee.message().equals(saveFailure.getMessage())));
```

line 1267-1268(`instanceof IllegalStateException` 체크는 message가 문자열이 되며 타입 정보가 없으므로 제거하고 메시지 내용 검사만 유지):
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null
                && tee.message().contains("전략 주문 leg 누락")));
```

line 1618-1619, `ex` 사용:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null && tee.message().equals(ex.getMessage())));
```

line 1657-1659(`instanceof IllegalStateException` 체크 제거, userId만 검증):
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null)); // 현재가·전일종가 null → 실패
```

line 1905:
```java
        verify(eventPublisher, atLeastOnce()).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee && tee.userId() == null));
```

- [ ] **Step 6: `VrCycleRolloverServiceTest.java` 7곳 수정**

`tee.user()==null`→`tee.userId()==null`, `USER.equals(tee.user())`→`USER.id().equals(tee.userId())` 패턴을 아래 각 라인에 적용:

line 282-283:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null)); // 관리자 알림
```

line 284-285:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && USER.id().equals(tee.userId()))); // 사용자 알림
```

line 314-315 (`never()` 케이스):
```java
        verify(eventPublisher, never()).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && USER.id().equals(tee.userId())));
```

line 359-361:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && USER.id().equals(tee.userId())));
```

line 376-377:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
```

line 390-391:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
```

- [ ] **Step 7: `CycleRotationServiceTest.java` 2곳 수정**

line 164-165:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null && tee.message().equals(kisError.getMessage())));
```

line 180-181:
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.userId() == null));
```

- [ ] **Step 8: `TradingOrderExecutorTest.java` 1곳 수정**

line 329-330(`[DB 불일치]` 케이스 — 메시지 내용 검사로 대체):
```java
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message() != null && tee.message().contains("DB 불일치")));
```

- [ ] **Step 9: 컴파일 확인**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL — 오류가 나오면 Task 4/5/6에서 다룬 6개 이벤트(`TradingErrorEvent`/`InsufficientBalanceEvent`/사이클·장 이벤트) 관련 놓친 참조이므로 같은 패턴으로 수정

- [ ] **Step 10: Task 4~6 전체 관련 테스트 한 번에 실행**

Run: `./gradlew test --tests 'com.kista.notify.adapter.out.gateway.TradingAlertNotifierTest' --tests 'com.kista.trading.application.service.TradingServiceTest' --tests 'com.kista.trading.application.service.VrCycleRolloverServiceTest' --tests 'com.kista.trading.application.service.CycleRotationServiceTest' --tests 'com.kista.trading.application.service.TradingOrderExecutorTest' --tests 'com.kista.trading.application.service.ManualTradingServiceTest' --tests 'com.kista.trading.application.service.TradingReporterTest' --tests 'com.kista.trading.application.service.VrReconfigureServiceTest'`
Expected: PASS 전체

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/kista/trading/application/event/TradingErrorEvent.java \
        src/main/java/com/kista/trading/application/service/TradingPriceFetcher.java \
        src/main/java/com/kista/trading/application/service/TradingService.java \
        src/main/java/com/kista/trading/application/service/TradingErrorReporter.java \
        src/main/java/com/kista/trading/application/service/ManualTradingService.java \
        src/main/java/com/kista/trading/application/service/TradingReporter.java \
        src/main/java/com/kista/trading/application/service/VrReconfigureService.java \
        src/main/java/com/kista/trading/application/service/TradingOrderExecutor.java \
        src/main/java/com/kista/trading/application/service/CycleRotationService.java \
        src/main/java/com/kista/trading/application/service/VrCycleRolloverService.java \
        src/main/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifier.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TradingAlertNotifierTest.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java \
        src/test/java/com/kista/trading/application/service/VrCycleRolloverServiceTest.java \
        src/test/java/com/kista/trading/application/service/CycleRotationServiceTest.java \
        src/test/java/com/kista/trading/application/service/TradingOrderExecutorTest.java
git commit -m "$(cat <<'EOF'
refactor(trading,notify): TradingErrorEvent payload를 ID+String 기반으로 재설계

Exception 필드는 JSON 직렬화에 부적합(스택트레이스·cause 체인)해 String
message로 교체 — 모든 소비처가 e.getMessage()만 쓰던 것을 코드로 확인해
정보 손실 없음. 이걸로 TradingAlertNotifier 4개 리스너 메서드
(onTradingError/onInsufficientBalance/onMarketOpen/onMarketClose/
onBatchInterrupted)가 Task 4~6에 걸쳐 전부 완성됐다.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: TradingReportReadyEvent ID화

**Files:**
- Modify: `src/main/java/com/kista/trading/application/event/TradingReportReadyEvent.java`
- Modify: `src/main/java/com/kista/trading/application/service/TradingReporter.java:67`
- Modify: `src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java`
- Test: `src/test/java/com/kista/notify/adapter/out/gateway/TradingReportNotifierTest.java`
- Test: `src/test/java/com/kista/trading/application/service/TradingServiceTest.java` (3곳, field-access)

**Interfaces:**
- Produces: `TradingReportReadyEvent(UUID userId, UUID accountId, TradingReport report, List<Execution> executions, boolean reportEnabled)`

- [ ] **Step 1: 이벤트 record 수정**

```java
package com.kista.trading.application.event;

import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradingReport;

import java.util.List;
import java.util.UUID;

// 매매 리포트/SSE 알림 발행 이벤트 — reportEnabled는 TRADING_ALERT 발송 여부만 제어, SSE는 항상 executions를 순회 발송
public record TradingReportReadyEvent(UUID userId, UUID accountId, TradingReport report,
                                       List<Execution> executions, boolean reportEnabled) {}
```

- [ ] **Step 2: `TradingReporter.java:67` 수정**

```java
        eventPublisher.publishEvent(new TradingReportReadyEvent(user.id(), account.id(), report, executions, reportEnabled));
```

- [ ] **Step 3: `TradingReportNotifier.java` 수정**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.TradeEvent;
import com.kista.domain.model.user.User;
import com.kista.application.port.output.RealtimeNotificationPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 매매 리포트 알림(Telegram/FCM)과 체결 건별 SSE 알림을 채널 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReportNotifier {

    private final UserNotificationPort userNotificationPort;
    private final RealtimeNotificationPort realtimeNotificationPort;
    private final UserPort userPort;
    private final AccountPort accountPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onTradingReportReady(TradingReportReadyEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());

        if (event.reportEnabled()) {
            userNotificationPort.notifyTradingReport(user, account, event.report());
            log.info("[{}] 리포트 발송 완료", account.nickname());
        } else {
            log.info("[{}] TRADING_ALERT 비활성 — 리포트 발송 생략", account.nickname());
        }

        for (Execution e : event.executions()) {
            TradeEvent tradeEvent = e.direction() == Direction.SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname());
            realtimeNotificationPort.notifyTrade(user.id(), tradeEvent);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", account.nickname(), event.executions().size());
    }
}
```

- [ ] **Step 4: `TradingReportNotifierTest.java` 수정**

```java
package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.trading.domain.model.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.application.port.output.RealtimeNotificationPort;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TradingReportNotifierTest {

    @Mock UserNotificationPort userNotificationPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock UserPort userPort;
    @Mock AccountPort accountPort;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), USER_ID);
    private static final User USER = DomainFixtures.activeUserWithTelegram(USER_ID);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);
    private static final TradingReport REPORT = new TradingReport(
            TODAY, Strategy.Type.INFINITE, Ticker.SOXL, new BigDecimal("100.00"), new BigDecimal("50.00"));

    private static Execution buyExecution() {
        return new Execution(TODAY, Ticker.SOXL, Direction.BUY,
                3, new BigDecimal("20.00"), new BigDecimal("60.00"), "E-BUY");
    }

    private static Execution sellExecution() {
        return new Execution(TODAY, Ticker.SOXL, Direction.SELL,
                2, new BigDecimal("21.00"), new BigDecimal("42.00"), "E-SELL");
    }

    @BeforeEach
    void setUp() {
        lenient().when(userPort.findByIdOrThrow(USER_ID)).thenReturn(USER);
        lenient().when(accountPort.findByIdOrThrow(ACCOUNT.id())).thenReturn(ACCOUNT);
    }

    @Test
    void reportEnabled_true이면_리포트를_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userPort, accountPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT.id(), REPORT, List.of(), true);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort).notifyTradingReport(USER, ACCOUNT, REPORT);
    }

    @Test
    void reportEnabled_false이면_리포트_발송을_생략한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userPort, accountPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT.id(), REPORT, List.of(), false);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void reportEnabled_false여도_SSE_알림은_항상_발송된다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userPort, accountPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(
                USER_ID, ACCOUNT.id(), REPORT, List.of(buyExecution(), sellExecution()), false);

        notifier.onTradingReportReady(event);

        verify(userNotificationPort, never()).notifyTradingReport(any(), any(), any());
        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void BUY_체결_건별로_SSE_알림을_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userPort, accountPort);
        Execution buy = buyExecution();
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT.id(), REPORT, List.of(buy), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(1)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void SELL_체결_건별로_SSE_알림을_발송한다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userPort, accountPort);
        Execution sell = sellExecution();
        TradingReportReadyEvent event = new TradingReportReadyEvent(USER_ID, ACCOUNT.id(), REPORT, List.of(sell), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(1)).notifyTrade(eq(USER.id()), any());
    }

    @Test
    void BUY와_SELL이_섞이면_체결건수만큼_SSE_알림이_발송된다() {
        TradingReportNotifier notifier = new TradingReportNotifier(userNotificationPort, realtimeNotificationPort, userPort, accountPort);
        TradingReportReadyEvent event = new TradingReportReadyEvent(
                USER_ID, ACCOUNT.id(), REPORT, List.of(buyExecution(), sellExecution()), true);

        notifier.onTradingReportReady(event);

        verify(realtimeNotificationPort, times(2)).notifyTrade(eq(USER.id()), any());
    }
}
```

(`@BeforeEach`에서 `lenient()`로 stub한 이유: `reportEnabled_false` 테스트는 `userPort.findByIdOrThrow`를 호출은 하지만 그 결과를 검증하지 않아 strict stubbing 경고가 날 수 있어 `lenient()`로 완화)

- [ ] **Step 5: `TradingServiceTest.java` 3곳(field-access) 수정**

line 281, 374, 1172 각각:
```java
        verify(eventPublisher).publishEvent(argThat((Object event) ->
                event instanceof TradingReportReadyEvent e
                        && e.userId().equals(USER.id()) && e.accountId().equals(ACCOUNT.id())));
```

- [ ] **Step 6: 컴파일 + 테스트 실행**

Run: `./gradlew compileTestJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test --tests 'com.kista.notify.adapter.out.gateway.TradingReportNotifierTest' --tests 'com.kista.trading.application.service.TradingServiceTest' --tests 'com.kista.trading.application.service.TradingReporterTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/kista/trading/application/event/TradingReportReadyEvent.java \
        src/main/java/com/kista/trading/application/service/TradingReporter.java \
        src/main/java/com/kista/notify/adapter/out/gateway/TradingReportNotifier.java \
        src/test/java/com/kista/notify/adapter/out/gateway/TradingReportNotifierTest.java \
        src/test/java/com/kista/trading/application/service/TradingServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(trading,notify): TradingReportReadyEvent payload를 ID 기반으로 재설계

이걸로 EPR 전환 대상 이벤트 9종(User 4 + trading 5그룹) 전부 완료.
남은 OrderCancelFailedEvent/MarketClosedEvent/UserDeletedEvent는 원래도
ID/스칼라만 담아 변경 불필요.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 최종 회귀 검증 + 문서 갱신

**Files:**
- Modify: `docs/agents/architecture.md`

**Interfaces:**
- Consumes: 전체 소스 트리(Task 1~7의 모든 산출물)

- [ ] **Step 1: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. 실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`로 실패 파일 특정 후 수정

- [ ] **Step 2: ArchUnit/Modulith 경계 규칙 재확인**

Run: `./gradlew test --tests 'com.kista.architecture.*'`
Expected: PASS — `ModulithArchitectureTest.verifyModularStructure()`가 notify가 새로 추가한 `UserPort`/`AccountPort`(레거시 OPEN 패키지) 참조를 순환 없이 통과시키는지 확인

- [ ] **Step 3: `architecture.md`의 `event/` 절 갱신**

`docs/agents/architecture.md`의 `application/event/` 설명 줄(레거시 4패키지 섹션)에 EPR 전환 사실 추가. 현재 문구:

```
event/         ← @TransactionalEventListener용 도메인 이벤트(application 레이어) — 사용자 승인/거부/재신청/신규가입, 사이클 종료/신규시작·매매리포트·주문취소실패 등은 com.kista.trading.application.event로 이전됨)
```

수정 후:
```
event/         ← 도메인 이벤트(application 레이어) — 사용자 승인/거부/재신청/신규가입, 사이클 종료/신규시작·매매리포트·주문취소실패 등은 com.kista.trading.application.event로 이전됨). 전부 Spring Modulith Event Publication Registry로 추적됨(`event_publication` 테이블, 재기동 시 미완료 이벤트 자동 재시도) — 리스너 annotation은 기존 @TransactionalEventListener 그대로, User/Account를 담던 이벤트는 평문 비밀값이 DB에 저장되지 않도록 ID(userId/accountId)만 담고 리스너가 UserPort/AccountPort로 재조회한다
```

`com.kista.trading/` 섹션의 `application/event/` 줄에도 동일 취지로 짧게 추가:
```
application/event/  ← trading 모듈의 공개 계약 — CycleCompletedEvent/CycleEndedEvent/NewCycleStartedEvent/OrderCancelFailedEvent/TradingReportReadyEvent/TradingErrorEvent/InsufficientBalanceEvent/MarketClosedEvent/MarketOpenEvent/MarketCloseEvent/BatchInterruptedEvent 11개 — notify 모듈이 `@TransactionalEventListener`로 구독(TradingAlertNotifier 등, EPR로 추적되어 재기동 시 실패분 자동 재시도). User/Account를 담던 이벤트는 userId/accountId만 담아 EPR 직렬화에 평문 비밀값이 노출되지 않게 함 — ...(이하 기존 문구 유지)
```

- [ ] **Step 4: Commit**

```bash
git add docs/agents/architecture.md
git commit -m "$(cat <<'EOF'
docs(modulith): Event Publication Registry 전환 완료 반영

architecture.md의 event/ 절에 EPR 추적·ID 기반 payload 설계를 반영.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## 참고

- `docs/superpowers/specs/2026-08-30-event-publication-registry-migration-design.md` — 이 계획의 설계 스펙
- `[[project_modulith_migration]]` (메모리) — 이 작업의 상위 계획, 완료 시 보류 항목 갱신 필요
