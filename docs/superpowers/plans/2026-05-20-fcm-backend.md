# FCM 알림 — 백엔드(kista-api) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kista-api에 Firebase Admin SDK를 통한 FCM 알림 채널을 추가하고, Composite 어댑터 패턴으로 Telegram과 FCM 중 사용자가 선택한 채널로 알림을 라우팅한다.

**Architecture:** `UserNotificationPort`를 구현하는 `CompositeUserNotificationAdapter`가 `User.notificationChannel`(TELEGRAM/FCM/ALL)에 따라 `TelegramUserNotificationAdapter` 또는 `FcmAdapter`에 위임. `TelegramAdapter`는 `NotifyPort`(관리자 전용)만 유지한다.

**Tech Stack:** Java 21, Spring Boot 3.4.4, Hexagonal Architecture, Firebase Admin SDK 9.4.1, JPA/Flyway, Mockito/JUnit 5

---

### Task 1: Firebase Admin SDK 의존성 추가

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: libs.versions.toml에 firebase-admin 버전 추가**

`gradle/libs.versions.toml`의 `[versions]` 섹션에 추가:
```toml
firebase-admin = "9.4.1"
```

`[libraries]` 섹션에 추가:
```toml
firebase-admin = { module = "com.google.firebase:firebase-admin", version.ref = "firebase-admin" }
```

- [ ] **Step 2: build.gradle.kts에 의존성 추가**

`dependencies` 블록에 추가:
```kotlin
implementation(libs.firebase.admin)
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: firebase-admin 9.4.1 의존성 추가"
```

---

### Task 2: 도메인 모델 신규 추가

**Files:**
- Create: `src/main/java/com/kista/domain/model/user/NotificationChannel.java`
- Create: `src/main/java/com/kista/domain/model/user/FcmDeviceToken.java`
- Create: `src/main/java/com/kista/domain/port/out/FcmDeviceTokenPort.java`

- [ ] **Step 1: NotificationChannel enum 작성**

```java
package com.kista.domain.model.user;

public enum NotificationChannel {
    TELEGRAM,   // 텔레그램 봇 알림
    FCM,        // Firebase Cloud Messaging 푸시
    ALL         // 텔레그램 + FCM 동시 발송
}
```

- [ ] **Step 2: FcmDeviceToken record 작성**

```java
package com.kista.domain.model.user;

import java.time.Instant;
import java.util.UUID;

public record FcmDeviceToken(
        UUID id,
        UUID userId,
        String token,       // FCM 등록 토큰
        String platform,    // WEB | ANDROID | IOS
        Instant createdAt
) {}
```

- [ ] **Step 3: FcmDeviceTokenPort 인터페이스 작성**

```java
package com.kista.domain.port.out;

import java.util.List;
import java.util.UUID;

public interface FcmDeviceTokenPort {
    void save(UUID userId, String token, String platform);
    void delete(UUID userId, String token);
    List<String> findTokensByUserId(UUID userId);
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/domain/
git commit -m "feat: NotificationChannel, FcmDeviceToken, FcmDeviceTokenPort 도메인 추가"
```

---

### Task 3: Flyway 마이그레이션 추가

**Files:**
- Create: `src/main/resources/db/migration/V26__create_fcm_device_tokens.sql`
- Create: `src/main/resources/db/migration/V27__add_notification_channel_to_users.sql`

- [ ] **Step 1: V26 마이그레이션 작성**

```sql
-- fcm_device_tokens: 사용자당 FCM 디바이스 토큰 (다중 디바이스 지원)
CREATE TABLE fcm_device_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    platform    VARCHAR(10) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX idx_fcm_device_tokens_user_id ON fcm_device_tokens(user_id);
```

- [ ] **Step 2: V27 마이그레이션 작성**

```sql
-- notification_channel: 알림 수단 선택 (TELEGRAM / FCM / ALL)
ALTER TABLE users
    ADD COLUMN notification_channel VARCHAR(20) NOT NULL DEFAULT 'TELEGRAM';
```

- [ ] **Step 3: Docker postgres 기동 후 마이그레이션 통과 확인**

```bash
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local' &
# 앱 기동 확인 후 중단 (Ctrl+C)
# 또는 flyway migrate 직접 실행
```

- [ ] **Step 4: 커밋**

```bash
git add src/main/resources/db/migration/
git commit -m "db: V26 fcm_device_tokens 테이블 추가, V27 users.notification_channel 컬럼 추가"
```

---

### Task 4: FcmDeviceToken JPA 퍼시스턴스

**Files:**
- Create: `src/main/java/com/kista/adapter/out/persistence/fcm/FcmDeviceTokenEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/fcm/FcmDeviceTokenJpaRepository.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/fcm/FcmDeviceTokenPersistenceAdapter.java`

- [ ] **Step 1: FcmDeviceTokenEntity 작성**

```java
package com.kista.adapter.out.persistence.fcm;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fcm_device_tokens")
class FcmDeviceTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;

    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String token; // FCM 등록 토큰

    @Column(nullable = false, length = 10)
    private String platform; // WEB | ANDROID | IOS

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected FcmDeviceTokenEntity() {}

    static FcmDeviceTokenEntity of(UUID userId, String token, String platform) {
        FcmDeviceTokenEntity e = new FcmDeviceTokenEntity();
        e.userId = userId;
        e.token = token;
        e.platform = platform;
        return e;
    }

    UUID getUserId() { return userId; }
    String getToken() { return token; }
    String getPlatform() { return platform; }
    Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: FcmDeviceTokenJpaRepository 작성**

```java
package com.kista.adapter.out.persistence.fcm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FcmDeviceTokenJpaRepository extends JpaRepository<FcmDeviceTokenEntity, UUID> {
    List<FcmDeviceTokenEntity> findAllByUserId(UUID userId);
    Optional<FcmDeviceTokenEntity> findByUserIdAndToken(UUID userId, String token);
    void deleteByUserIdAndToken(UUID userId, String token);
}
```

- [ ] **Step 3: FcmDeviceTokenPersistenceAdapter 작성**

```java
package com.kista.adapter.out.persistence.fcm;

import com.kista.domain.port.out.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FcmDeviceTokenPersistenceAdapter implements FcmDeviceTokenPort {

    private final FcmDeviceTokenJpaRepository repository;

    @Override
    @Transactional
    public void save(UUID userId, String token, String platform) {
        // 중복 토큰은 무시 (UNIQUE 제약)
        if (repository.findByUserIdAndToken(userId, token).isEmpty()) {
            repository.save(FcmDeviceTokenEntity.of(userId, token, platform));
        }
    }

    @Override
    @Transactional
    public void delete(UUID userId, String token) {
        repository.deleteByUserIdAndToken(userId, token);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findTokensByUserId(UUID userId) {
        return repository.findAllByUserId(userId).stream()
                .map(FcmDeviceTokenEntity::getToken)
                .toList();
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/persistence/fcm/
git commit -m "feat: FcmDeviceToken JPA 퍼시스턴스 추가"
```

---

### Task 5: User record notificationChannel 필드 추가 + 전파

**Files:**
- Modify: `src/main/java/com/kista/domain/model/user/User.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/user/UserEntity.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/user/UserPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/application/service/UserService.java`
- Modify: `src/test/java/com/kista/adapter/out/notify/TelegramAdapterTest.java`
- Modify: `src/test/java/com/kista/adapter/in/schedule/TradingSchedulerTest.java`
- Modify: `src/test/java/com/kista/application/service/AccountServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/AdminServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/TradingServiceTest.java`
- Modify: `src/test/java/com/kista/application/service/UserServiceTest.java`
- Modify: `src/test/java/com/kista/adapter/in/web/AdminUserControllerTest.java`

- [ ] **Step 1: 현재 컴파일 실패 확인 (User 필드 추가 전)**

```bash
./gradlew compileTestJava 2>&1 | head -5
```
Expected: `BUILD SUCCESSFUL` (아직 변경 전)

- [ ] **Step 2: User record에 notificationChannel 추가**

`src/main/java/com/kista/domain/model/user/User.java`:
```java
package com.kista.domain.model.user;

import java.time.Instant;
import java.util.UUID;

public record User(
        UUID id,
        String kakaoId,
        String nickname,
        UserStatus status,
        UserRole role,
        String telegramBotToken,        // AES-256 암호화 저장, null 가능
        String telegramChatId,          // null 가능
        String telegramBotUsername,     // 평문, null 가능
        Instant createdAt,
        Instant updatedAt,
        Instant lastReappliedAt,        // nullable — 쿨다운 기준 시각
        NotificationChannel notificationChannel  // 알림 수단 (기본: TELEGRAM)
) {}
```

- [ ] **Step 3: 컴파일 실패 확인 (전파 대상 파악)**

```bash
./gradlew compileTestJava 2>&1 | grep "error:" | head -20
```
Expected: `constructor User in class User cannot be applied to given types` 여러 곳

- [ ] **Step 4: UserEntity에 notificationChannel 추가**

`src/main/java/com/kista/adapter/out/persistence/user/UserEntity.java`의 `lastReappliedAt` 필드 다음에 추가:
```java
@Enumerated(EnumType.STRING)
@Column(name = "notification_channel", nullable = false, length = 20)
private NotificationChannel notificationChannel; // 알림 수단 (기본: TELEGRAM)
```

`fromModel()` 메서드에 추가:
```java
e.notificationChannel = user.notificationChannel() != null
        ? user.notificationChannel() : NotificationChannel.TELEGRAM;
```

`toModel()` 호출 수정 (마지막 인수 추가):
```java
return new User(id, kakaoId, nickname, status, role,
        telegramBotToken, telegramChatId, telegramBotUsername,
        createdAt, updatedAt, lastReappliedAt,
        notificationChannel != null ? notificationChannel : NotificationChannel.TELEGRAM);
```

필요한 import 추가:
```java
import com.kista.domain.model.user.NotificationChannel;
```

- [ ] **Step 5: UserPersistenceAdapter — toModel() 내 User 생성자 수정**

`src/main/java/com/kista/adapter/out/persistence/user/UserPersistenceAdapter.java`에서 `new User(...)` 직접 생성 부분(있을 경우)을 찾아 `notificationChannel` 인수 추가. 일반적으로 `entity.toModel()` 경유이므로 Step 4 수정으로 충분함.

- [ ] **Step 6: UserService.register()에서 새 User 생성 시 TELEGRAM 기본값 설정**

`src/main/java/com/kista/application/service/UserService.java`의 `register()` 메서드에서 `new User(...)` 호출:
```java
User newUser = new User(userId, kakaoId, nickname, status, role,
        null, null, null, null, null, null, NotificationChannel.TELEGRAM);
```

필요한 import 추가:
```java
import com.kista.domain.model.user.NotificationChannel;
```

- [ ] **Step 7: 테스트 파일 7개의 new User(...) 호출 수정**

각 파일에서 `new User(` 검색 후 12번째 인수로 `NotificationChannel.TELEGRAM` 추가. 각 파일에서 아래 패턴을 찾아 수정:

**패턴 (기존 11개 인수):**
```java
new User(uuid, kakaoId, nickname, status, role, telegramToken, chatId, username, createdAt, updatedAt, lastReapplied)
```
**수정 후 (12개 인수):**
```java
new User(uuid, kakaoId, nickname, status, role, telegramToken, chatId, username, createdAt, updatedAt, lastReapplied, NotificationChannel.TELEGRAM)
```

대상 파일 7개:
- `TelegramAdapterTest.java` — User 생성 4곳
- `TradingSchedulerTest.java`
- `AccountServiceTest.java`
- `AdminServiceTest.java`
- `TradingServiceTest.java`
- `UserServiceTest.java`
- `AdminUserControllerTest.java`

각 파일 상단에 import 추가:
```java
import com.kista.domain.model.user.NotificationChannel;
```

- [ ] **Step 8: 컴파일 통과 확인**

```bash
./gradlew compileTestJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: 기존 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.adapter.out.notify.*' --tests 'com.kista.application.service.*'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: 커밋**

```bash
git add src/
git commit -m "feat: User.notificationChannel 필드 추가 및 전파"
```

---

### Task 6: TelegramAdapter 리팩토링 — TelegramHttpClient 추출 + UserNotificationPort 분리

**Files:**
- Create: `src/main/java/com/kista/adapter/out/notify/TelegramHttpClient.java`
- Create: `src/main/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapter.java`
- Modify: `src/main/java/com/kista/adapter/out/notify/TelegramAdapter.java`
- Modify: `src/test/java/com/kista/adapter/out/notify/TelegramAdapterTest.java`
- Create: `src/test/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapterTest.java`

- [ ] **Step 1: TelegramHttpClient 작성 (package-private)**

```java
package com.kista.adapter.out.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
class TelegramHttpClient {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate telegramRestTemplate;

    void sendMessage(String chatId, String text, String botToken) {
        if (botToken == null || botToken.isBlank()) return;
        try {
            String url = API_BASE + "/bot" + botToken + "/sendMessage";
            Map<String, String> body = Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML");
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }

    void sendWithInlineKeyboard(String chatId, String text, String botToken,
                                List<Map<String, String>> buttons) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("botToken 미설정 — 인라인 버튼 메시지 생략");
            return;
        }
        try {
            String url = API_BASE + "/bot" + botToken + "/sendMessage";
            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML",
                    "reply_markup", Map.of("inline_keyboard", List.of(buttons))
            );
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 인라인 버튼 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: TelegramAdapter를 NotifyPort 전용으로 축소**

`src/main/java/com/kista/adapter/out/notify/TelegramAdapter.java`를 아래로 교체:
```java
package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort {

    private final TelegramHttpClient telegramHttpClient;
    private final TelegramProperties props;

    @Override
    public void notifyReport(TradingReport r) {
        String text = String.format(
                "<b>매매 결산 [%s]</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.snapshot().holdings(), r.snapshot().averagePrice(),
                r.snapshot().priceOffsetRate(), r.snapshot().targetPrice());
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }

    @Override
    public void notifyMarketClosed() {
        telegramHttpClient.sendMessage(props.chatId(), "오늘은 휴장일입니다. 매매를 건너뜁니다.", props.botToken());
    }

    @Override
    public void notifyInsufficientBalance(Account account, AccountBalance b) {
        String text = String.format("잔고 부족: %s %d주, 예수금 $%.2f. 매매를 건너뜁니다.",
                account.ticker().name(), b.holdings(), b.usdDeposit());
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }

    @Override
    public void notifyError(Exception e) {
        telegramHttpClient.sendMessage(props.chatId(),
                String.format("<b>⚠️ 매매 오류 발생</b>%n%s", e.getMessage()), props.botToken());
    }
}
```

> **주의:** `TradingSnapshot` 필드명이 `quantity` → `holdings`로 변경된 경우 `r.snapshot().holdings()` 사용. 실제 메서드명은 기존 코드 확인 후 맞출 것.

- [ ] **Step 3: TelegramUserNotificationAdapter 작성**

```java
package com.kista.adapter.out.notify;

import com.kista.application.service.NewUserRegisteredEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramUserNotificationAdapter implements UserNotificationPort {

    private final TelegramHttpClient telegramHttpClient;
    private final TelegramProperties props;

    // UserService가 발행한 이벤트를 커밋 성공 후에만 수신
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewUserRegistered(NewUserRegisteredEvent event) {
        notifyNewUser(event.user());
    }

    @Override
    public void notifyNewUser(User user) {
        // 관리자에게 신규 가입 알림 + [승인]/[거절] 인라인 버튼
        String text = String.format("🆕 <b>신규 가입 신청</b>%n닉네임: %s%nUID: %s",
                user.nickname(), user.id());
        telegramHttpClient.sendWithInlineKeyboard(props.chatId(), text, props.botToken(),
                List.of(
                        Map.of("text", "✅ 승인", "callback_data", "approve:" + user.id()),
                        Map.of("text", "❌ 거절", "callback_data", "reject:" + user.id())
                ));
    }

    @Override
    public void notifyApproved(User user) {
        if (user.telegramChatId() != null && user.telegramBotToken() != null) {
            telegramHttpClient.sendMessage(user.telegramChatId(), "✅ 가입이 승인되었습니다.",
                    user.telegramBotToken());
        }
    }

    @Override
    public void notifyRejected(User user) {
        if (user.telegramChatId() != null && user.telegramBotToken() != null) {
            telegramHttpClient.sendMessage(user.telegramChatId(), "❌ 가입 신청이 거절되었습니다.",
                    user.telegramBotToken());
        }
    }

    @Override
    public void notifyStrategyChanged(User user, Account account, String action) {
        String text = String.format("사용자 %s이 계좌 %s의 전략을 %s했습니다",
                user.nickname(), account.nickname(), action);
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport r) {
        if (user.telegramBotToken() == null || user.telegramBotToken().isBlank()
                || user.telegramChatId() == null) {
            log.warn("[{}] 텔레그램 미설정 — 매매 리포트 생략", account.nickname());
            return;
        }
        String text = String.format(
                "<b>매매 결산 [%s] — %s</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(), account.nickname(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.snapshot().holdings(), r.snapshot().averagePrice(),
                r.snapshot().priceOffsetRate(), r.snapshot().targetPrice());
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }
}
```

- [ ] **Step 4: TelegramConfig에 TelegramHttpClient 빈 등록 확인**

`src/main/java/com/kista/adapter/out/notify/TelegramConfig.java`를 열어 `RestTemplate` 빈 이름이 `telegramRestTemplate`인지 확인. `TelegramHttpClient`가 `@Component`가 아닌 package-private 클래스이므로, `TelegramConfig`에서 빈으로 수동 등록:

```java
@Bean
TelegramHttpClient telegramHttpClient(RestTemplate telegramRestTemplate, TelegramProperties telegramProperties) {
    return new TelegramHttpClient(telegramRestTemplate);
}
```

> **또는** `TelegramHttpClient`에 `@Component`와 package-private `@RequiredArgsConstructor` 패턴 사용 가능. `TelegramConfig.java` 내용을 확인 후 판단.

- [ ] **Step 5: TelegramAdapterTest 수정 — NotifyPort 메서드만 남김**

`notifyStrategyChanged`와 `notifyTradingReport` 관련 테스트를 파일에서 제거(새 파일로 이동).
`TelegramAdapter` 생성자에서 `TelegramHttpClient` 사용하도록 setUp 수정:
```java
TelegramHttpClient httpClient;
TelegramAdapter adapter;

@BeforeEach
void setUp() {
    httpClient = new TelegramHttpClient(restTemplate);
    adapter = new TelegramAdapter(httpClient, PROPS);
}
```

- [ ] **Step 6: TelegramUserNotificationAdapterTest 작성**

```java
package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.StrategyStatus;
import com.kista.domain.model.account.StrategyType;
import com.kista.domain.model.account.Ticker;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.TradingSnapshot;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserRole;
import com.kista.domain.model.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramUserNotificationAdapterTest {

    @Mock RestTemplate restTemplate;

    TelegramUserNotificationAdapter adapter;

    static final TelegramProperties PROPS = new TelegramProperties("admin-token", "admin-chat");

    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restTemplate);
        adapter = new TelegramUserNotificationAdapter(httpClient, PROPS);
    }

    @Test
    void notifyStrategyChanged_sendsToAdminChat() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE, UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("내계좌");

        adapter.notifyStrategyChanged(user, account, "중지");

        verify(restTemplate).postForObject(contains("/botadmin-token/sendMessage"), any(), eq(String.class));
    }

    @Test
    void notifyTradingReport_withUserBot_sendsToUserChatId() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE, UserRole.USER,
                "user-bot-token", "user-chat-789", null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("SOXL계좌");
        TradingSnapshot snapshot = new TradingSnapshot(10,
                new BigDecimal("20.00"), new BigDecimal("0.1733"), new BigDecimal("24.00"));
        TradingReport report = new TradingReport(
                LocalDate.of(2024, 6, 15), snapshot, List.of(), List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        adapter.notifyTradingReport(user, account, report);

        verify(restTemplate).postForObject(contains("/botuser-bot-token/sendMessage"), any(), eq(String.class));
    }

    @Test
    void notifyTradingReport_noUserBot_skips() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE, UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);
        when(account.nickname()).thenReturn("노봇계좌");
        TradingSnapshot snapshot = new TradingSnapshot(10,
                new BigDecimal("20.00"), new BigDecimal("0.1733"), new BigDecimal("24.00"));
        TradingReport report = new TradingReport(
                LocalDate.of(2024, 6, 15), snapshot, List.of(), List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        adapter.notifyTradingReport(user, account, report);

        verify(restTemplate, never()).postForObject(any(), any(), any());
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.adapter.out.notify.*'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add src/
git commit -m "refactor: TelegramAdapter → TelegramHttpClient + TelegramUserNotificationAdapter 분리"
```

---

### Task 7: FcmConfig + FcmAdapter 구현

**Files:**
- Create: `src/main/java/com/kista/adapter/out/notify/FcmConfig.java`
- Create: `src/main/java/com/kista/adapter/out/notify/FcmAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/notify/FcmAdapterTest.java`

- [ ] **Step 1: FcmConfig 작성**

```java
package com.kista.adapter.out.notify;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
class FcmConfig {

    @Bean
    FirebaseMessaging firebaseMessaging(
            @Value("${firebase.service-account-json:}") String serviceAccountJson) throws IOException {
        // 환경변수 미설정 시 FCM 비활성화 (Optional<FirebaseMessaging>으로 주입받는 어댑터는 생략됨)
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("firebase.service-account-json 미설정 — FCM 알림 비활성화");
            return null;
        }
        if (FirebaseApp.getApps().isEmpty()) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8)));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
        }
        return FirebaseMessaging.getInstance();
    }
}
```

- [ ] **Step 2: application.yml에 firebase 프로퍼티 추가**

`src/main/resources/application.yml`에 추가:
```yaml
firebase:
  service-account-json: ${FIREBASE_SERVICE_ACCOUNT_JSON:}
```

- [ ] **Step 3: .env.example + docker-compose.yml 환경변수 추가**

`.env.example`:
```
FIREBASE_SERVICE_ACCOUNT_JSON=  # Firebase Admin SDK 서비스 계정 JSON (한 줄 직렬화, 미설정 시 FCM 비활성화)
```

`docker-compose.yml`의 app 서비스 `environment:` 섹션에 추가:
```yaml
FIREBASE_SERVICE_ACCOUNT_JSON: ${FIREBASE_SERVICE_ACCOUNT_JSON:}
```

- [ ] **Step 4: FcmAdapter 작성**

```java
package com.kista.adapter.out.notify;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcmAdapter implements UserNotificationPort {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;
    private final Optional<FirebaseMessaging> firebaseMessaging; // null-safe — 미설정 시 empty

    @Override
    public void notifyNewUser(User user) {
        // 신규 가입 알림은 관리자 전용 — FCM에서는 생략 (CompositeAdapter에서 항상 Telegram 경유)
    }

    @Override
    public void notifyApproved(User user) {
        send(user.id(), "KISTA 알림", "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(User user) {
        send(user.id(), "KISTA 알림", "❌ 가입 신청이 거절되었습니다.");
    }

    @Override
    public void notifyStrategyChanged(User user, Account account, String action) {
        // 전략 변경 알림은 관리자 전용 — FCM에서는 생략
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport r) {
        String body = String.format("[%s] 매수 $%.2f / 매도 $%.2f", account.nickname(),
                r.totalBoughtUsd(), r.totalSoldUsd());
        send(user.id(), "매매 결산", body);
    }

    private void send(UUID userId, String title, String body) {
        if (firebaseMessaging.isEmpty()) {
            log.warn("FCM 미설정 — 알림 생략");
            return;
        }
        List<String> tokens = fcmDeviceTokenPort.findTokensByUserId(userId);
        if (tokens.isEmpty()) {
            log.warn("[{}] FCM 토큰 없음 — 알림 생략", userId);
            return;
        }
        MulticastMessage message = MulticastMessage.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .addAllTokens(tokens)
                .build();
        try {
            var result = firebaseMessaging.get().sendEachForMulticast(message);
            // 등록 만료된 토큰 자동 삭제
            for (int i = 0; i < result.getResponses().size(); i++) {
                if (!result.getResponses().get(i).isSuccessful()) {
                    String failedToken = tokens.get(i);
                    log.warn("FCM 토큰 전송 실패, 삭제: {}", failedToken);
                    fcmDeviceTokenPort.delete(userId, failedToken);
                }
            }
        } catch (FirebaseMessagingException e) {
            log.error("FCM 전송 오류: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 5: FcmAdapterTest 작성**

```java
package com.kista.adapter.out.notify;

import com.google.firebase.messaging.FirebaseMessaging;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserRole;
import com.kista.domain.model.user.UserStatus;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FcmAdapterTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;
    @Mock FirebaseMessaging firebaseMessaging;

    // FcmAdapter를 직접 생성 (Optional 주입)
    FcmAdapter adapter;

    static User user(UUID id) {
        return new User(id, "kakao-1", "홍길동", UserStatus.ACTIVE, UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.FCM);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        adapter = new FcmAdapter(fcmDeviceTokenPort, Optional.of(firebaseMessaging));
    }

    @Test
    void send_noTokens_skips() {
        UUID userId = UUID.randomUUID();
        when(fcmDeviceTokenPort.findTokensByUserId(userId)).thenReturn(List.of());

        adapter.notifyApproved(user(userId));

        // 토큰 없으면 FirebaseMessaging 미호출
        verifyNoInteractions(firebaseMessaging);
    }

    @Test
    void send_firebaseEmpty_skips() {
        FcmAdapter noFirebaseAdapter = new FcmAdapter(fcmDeviceTokenPort, Optional.empty());
        UUID userId = UUID.randomUUID();

        noFirebaseAdapter.notifyApproved(user(userId));

        verifyNoInteractions(fcmDeviceTokenPort);
    }
}
```

- [ ] **Step 6: 컴파일 + 테스트 통과 확인**

```bash
./gradlew compileTestJava
./gradlew test --tests 'com.kista.adapter.out.notify.FcmAdapterTest'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
git add src/
git commit -m "feat: FcmConfig + FcmAdapter 구현 (Firebase Admin SDK 기반 FCM 발송)"
```

---

### Task 8: CompositeUserNotificationAdapter 구현

**Files:**
- Create: `src/main/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapterTest.java`

- [ ] **Step 1: CompositeUserNotificationAdapter 작성**

```java
package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
@RequiredArgsConstructor
public class CompositeUserNotificationAdapter implements UserNotificationPort {

    private final TelegramUserNotificationAdapter telegram;
    private final FcmAdapter fcm;

    // 관리자 알림 — 채널 무관, 항상 Telegram (인라인 버튼 필요)
    @Override
    public void notifyNewUser(User user) {
        telegram.notifyNewUser(user);
    }

    @Override
    public void notifyStrategyChanged(User user, Account account, String action) {
        telegram.notifyStrategyChanged(user, account, action);
    }

    // 사용자 알림 — notificationChannel에 따라 라우팅
    @Override
    public void notifyApproved(User user) {
        if (usesTelegram(user)) telegram.notifyApproved(user);
        if (usesFcm(user))      fcm.notifyApproved(user);
    }

    @Override
    public void notifyRejected(User user) {
        if (usesTelegram(user)) telegram.notifyRejected(user);
        if (usesFcm(user))      fcm.notifyRejected(user);
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport report) {
        if (usesTelegram(user)) telegram.notifyTradingReport(user, account, report);
        if (usesFcm(user))      fcm.notifyTradingReport(user, account, report);
    }

    private boolean usesTelegram(User u) {
        return u.notificationChannel() == NotificationChannel.TELEGRAM
                || u.notificationChannel() == NotificationChannel.ALL;
    }

    private boolean usesFcm(User u) {
        return u.notificationChannel() == NotificationChannel.FCM
                || u.notificationChannel() == NotificationChannel.ALL;
    }
}
```

- [ ] **Step 2: CompositeUserNotificationAdapterTest 작성**

```java
package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.TradingSnapshot;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserRole;
import com.kista.domain.model.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeUserNotificationAdapterTest {

    @Mock TelegramUserNotificationAdapter telegram;
    @Mock FcmAdapter fcm;

    CompositeUserNotificationAdapter composite;

    @BeforeEach
    void setUp() {
        composite = new CompositeUserNotificationAdapter(telegram, fcm);
    }

    static User userWith(NotificationChannel channel) {
        return new User(UUID.randomUUID(), "k", "nick", UserStatus.ACTIVE, UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, channel);
    }

    static TradingReport report() {
        TradingSnapshot snap = new TradingSnapshot(5, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("12"));
        return new TradingReport(LocalDate.now(), snap, List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void telegramChannel_routesToTelegramOnly() {
        User user = userWith(NotificationChannel.TELEGRAM);
        Account account = mock(Account.class);

        composite.notifyTradingReport(user, account, report());

        verify(telegram).notifyTradingReport(user, account, any());
        verify(fcm, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void fcmChannel_routesToFcmOnly() {
        User user = userWith(NotificationChannel.FCM);
        Account account = mock(Account.class);

        composite.notifyTradingReport(user, account, report());

        verify(fcm).notifyTradingReport(user, account, any());
        verify(telegram, never()).notifyTradingReport(any(), any(), any());
    }

    @Test
    void allChannel_routesToBoth() {
        User user = userWith(NotificationChannel.ALL);
        Account account = mock(Account.class);

        composite.notifyTradingReport(user, account, report());

        verify(telegram).notifyTradingReport(user, account, any());
        verify(fcm).notifyTradingReport(user, account, any());
    }

    @Test
    void notifyNewUser_alwaysGoesToTelegram() {
        // 채널 무관 — 관리자 알림은 항상 Telegram
        User fcmUser = userWith(NotificationChannel.FCM);

        composite.notifyNewUser(fcmUser);

        verify(telegram).notifyNewUser(fcmUser);
        verify(fcm, never()).notifyNewUser(any());
    }
}
```

- [ ] **Step 3: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.adapter.out.notify.CompositeUserNotificationAdapterTest'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: ArchUnit 통과 확인**

```bash
./gradlew test --tests 'com.kista.architecture.*'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add src/
git commit -m "feat: CompositeUserNotificationAdapter — Telegram/FCM 채널 라우팅"
```

---

### Task 9: UpdateNotificationChannelUseCase + SettingsController 엔드포인트 추가

**Files:**
- Create: `src/main/java/com/kista/domain/port/in/UpdateNotificationChannelUseCase.java`
- Modify: `src/main/java/com/kista/application/service/UserService.java`
- Modify: `src/main/java/com/kista/adapter/in/web/SettingsController.java`
- Modify: `src/test/java/com/kista/adapter/in/web/SettingsControllerTest.java`

- [ ] **Step 1: UpdateNotificationChannelUseCase 작성**

```java
package com.kista.domain.port.in;

import com.kista.domain.model.user.NotificationChannel;

import java.util.UUID;

public interface UpdateNotificationChannelUseCase {
    void updateNotificationChannel(UUID userId, NotificationChannel channel);
}
```

- [ ] **Step 2: UserService에 구현 추가**

`UserService` 클래스 선언에 `UpdateNotificationChannelUseCase` 추가:
```java
public class UserService implements RegisterUserUseCase, ApproveUserUseCase, GetUserUseCase,
        UpdateUserTelegramUseCase, DeleteMeUseCase, UpdateNotificationChannelUseCase {
```

메서드 구현 추가:
```java
@Override
public void updateNotificationChannel(UUID userId, NotificationChannel channel) {
    User user = userRepository.findByIdOrThrow(userId);
    userRepository.save(new User(user.id(), user.kakaoId(), user.nickname(), user.status(), user.role(),
            user.telegramBotToken(), user.telegramChatId(), user.telegramBotUsername(),
            user.createdAt(), null, user.lastReappliedAt(), channel));
}
```

- [ ] **Step 3: SettingsController에 PATCH 엔드포인트 추가**

`SettingsController.java`에 `UpdateNotificationChannelUseCase` 필드 추가 및 엔드포인트:
```java
private final UpdateNotificationChannelUseCase updateNotificationChannel;

@Operation(summary = "알림 채널 변경", description = "TELEGRAM / FCM / ALL 중 선택. body: {\"channel\": \"FCM\"}")
@ApiResponse(responseCode = "204", description = "변경 성공")
@PatchMapping("/notification-channel")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void updateNotificationChannel(@AuthenticationPrincipal UUID userId,
                                       @RequestBody Map<String, String> body) {
    try {
        NotificationChannel channel = NotificationChannel.valueOf(body.get("channel").toUpperCase());
        updateNotificationChannel.updateNotificationChannel(userId, channel);
    } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 채널: " + body.get("channel"));
    }
}
```

필요한 import:
```java
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.port.in.UpdateNotificationChannelUseCase;
```

- [ ] **Step 4: SettingsControllerTest에 @MockBean 추가**

`SettingsControllerTest.java`에 추가:
```java
@MockBean UpdateNotificationChannelUseCase updateNotificationChannel;
```

그리고 새 엔드포인트 테스트 추가:
```java
@Test
void updateNotificationChannel_fcm_returns204() throws Exception {
    mockMvc.perform(patch("/api/settings/notification-channel")
                    .with(csrf())
                    .with(authentication(new UsernamePasswordAuthenticationToken(
                            UUID.fromString("00000000-0000-0000-0000-000000000001"), null, List.of())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"channel\":\"FCM\"}"))
            .andExpect(status().isNoContent());
    verify(updateNotificationChannel).updateNotificationChannel(any(), eq(NotificationChannel.FCM));
}

@Test
void updateNotificationChannel_invalidValue_returns400() throws Exception {
    mockMvc.perform(patch("/api/settings/notification-channel")
                    .with(csrf())
                    .with(authentication(new UsernamePasswordAuthenticationToken(
                            UUID.fromString("00000000-0000-0000-0000-000000000001"), null, List.of())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"channel\":\"INVALID\"}"))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.SettingsControllerTest'
./gradlew test --tests 'com.kista.application.service.UserServiceTest'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add src/
git commit -m "feat: PATCH /api/settings/notification-channel 알림 채널 변경 API 추가"
```

---

### Task 10: FCM 토큰 CRUD — UseCase + Service + Controller

**Files:**
- Create: `src/main/java/com/kista/domain/port/in/RegisterFcmTokenUseCase.java`
- Create: `src/main/java/com/kista/domain/port/in/UnregisterFcmTokenUseCase.java`
- Create: `src/main/java/com/kista/application/service/FcmTokenService.java`
- Create: `src/main/java/com/kista/adapter/in/web/FcmController.java`
- Create: `src/test/java/com/kista/adapter/in/web/FcmControllerTest.java`

- [ ] **Step 1: UseCase 인터페이스 작성**

```java
// RegisterFcmTokenUseCase.java
package com.kista.domain.port.in;

import java.util.UUID;

public interface RegisterFcmTokenUseCase {
    void register(UUID userId, String token, String platform);
}
```

```java
// UnregisterFcmTokenUseCase.java
package com.kista.domain.port.in;

import java.util.UUID;

public interface UnregisterFcmTokenUseCase {
    void unregister(UUID userId, String token);
}
```

- [ ] **Step 2: FcmTokenService 작성 + 실패 테스트**

먼저 테스트 작성:
```java
// src/test/java/com/kista/application/service/FcmTokenServiceTest.java
package com.kista.application.service;

import com.kista.domain.port.out.FcmDeviceTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FcmTokenServiceTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;
    @InjectMocks FcmTokenService service;

    @Test
    void register_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        service.register(userId, "token-abc", "WEB");
        verify(fcmDeviceTokenPort).save(userId, "token-abc", "WEB");
    }

    @Test
    void unregister_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        service.unregister(userId, "token-abc");
        verify(fcmDeviceTokenPort).delete(userId, "token-abc");
    }
}
```

테스트 실행 후 실패 확인:
```bash
./gradlew test --tests 'com.kista.application.service.FcmTokenServiceTest'
```
Expected: FAIL (`FcmTokenService` 미존재)

- [ ] **Step 3: FcmTokenService 구현**

```java
package com.kista.application.service;

import com.kista.domain.port.in.RegisterFcmTokenUseCase;
import com.kista.domain.port.in.UnregisterFcmTokenUseCase;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class FcmTokenService implements RegisterFcmTokenUseCase, UnregisterFcmTokenUseCase {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;

    @Override
    public void register(UUID userId, String token, String platform) {
        fcmDeviceTokenPort.save(userId, token, platform);
    }

    @Override
    public void unregister(UUID userId, String token) {
        fcmDeviceTokenPort.delete(userId, token);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.application.service.FcmTokenServiceTest'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: FcmController 실패 테스트 작성**

```java
// src/test/java/com/kista/adapter/in/web/FcmControllerTest.java
package com.kista.adapter.in.web;

import com.kista.domain.port.in.RegisterFcmTokenUseCase;
import com.kista.domain.port.in.UnregisterFcmTokenUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FcmController.class)
class FcmControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RegisterFcmTokenUseCase registerFcmToken;
    @MockitoBean UnregisterFcmTokenUseCase unregisterFcmToken;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void registerToken_returns204() throws Exception {
        mockMvc.perform(post("/api/fcm/tokens")
                        .with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-abc\",\"platform\":\"WEB\"}"))
                .andExpect(status().isNoContent());
        verify(registerFcmToken).register(eq(USER_ID), eq("fcm-token-abc"), eq("WEB"));
    }

    @Test
    void unregisterToken_returns204() throws Exception {
        mockMvc.perform(delete("/api/fcm/tokens/fcm-token-abc")
                        .with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()))))
                .andExpect(status().isNoContent());
        verify(unregisterFcmToken).unregister(eq(USER_ID), eq("fcm-token-abc"));
    }
}
```

테스트 실패 확인:
```bash
./gradlew test --tests 'com.kista.adapter.in.web.FcmControllerTest'
```
Expected: FAIL

- [ ] **Step 6: FcmController 구현**

```java
package com.kista.adapter.in.web;

import com.kista.domain.port.in.RegisterFcmTokenUseCase;
import com.kista.domain.port.in.UnregisterFcmTokenUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "FCM", description = "FCM 디바이스 토큰 관리")
@RestController
@RequestMapping("/api/fcm")
@RequiredArgsConstructor
public class FcmController {

    private final RegisterFcmTokenUseCase registerFcmToken;
    private final UnregisterFcmTokenUseCase unregisterFcmToken;

    // FCM 디바이스 토큰 등록 (WEB | ANDROID | IOS)
    @Operation(summary = "FCM 토큰 등록", description = "body: {\"token\": \"...\", \"platform\": \"WEB\"}")
    @ApiResponse(responseCode = "204", description = "등록 성공")
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerToken(@AuthenticationPrincipal UUID userId,
                              @RequestBody Map<String, String> body) {
        registerFcmToken.register(userId, body.get("token"), body.get("platform"));
    }

    // FCM 디바이스 토큰 삭제
    @Operation(summary = "FCM 토큰 삭제", description = "로그아웃 또는 알림 비활성화 시 호출")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @DeleteMapping("/tokens/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterToken(@AuthenticationPrincipal UUID userId,
                                @PathVariable String token) {
        unregisterFcmToken.unregister(userId, token);
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests 'com.kista.adapter.in.web.FcmControllerTest'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: 커밋**

```bash
git add src/
git commit -m "feat: FCM 토큰 등록/삭제 API (POST/DELETE /api/fcm/tokens)"
```

---

### Task 11: 전체 테스트 + ArchUnit 통과 확인

- [ ] **Step 1: 전체 테스트 실행**

```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`
실패 시 `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'` 로 실패 케이스 확인.

- [ ] **Step 2: ArchUnit 통과 확인**

```bash
./gradlew test --tests 'com.kista.architecture.*'
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 최종 커밋**

```bash
git add src/ .env.example
git commit -m "chore: FCM 알림 채널 백엔드 구현 완료 — 전체 테스트 통과"
```

---

## 파일 변경 요약

| 파일 | 작업 |
|------|------|
| `gradle/libs.versions.toml` | firebase-admin 9.4.1 추가 |
| `build.gradle.kts` | firebase-admin 의존성 추가 |
| `domain/model/user/NotificationChannel.java` | 신규 |
| `domain/model/user/FcmDeviceToken.java` | 신규 |
| `domain/model/user/User.java` | `notificationChannel` 필드 추가 |
| `domain/port/out/FcmDeviceTokenPort.java` | 신규 |
| `domain/port/in/RegisterFcmTokenUseCase.java` | 신규 |
| `domain/port/in/UnregisterFcmTokenUseCase.java` | 신규 |
| `domain/port/in/UpdateNotificationChannelUseCase.java` | 신규 |
| `db/migration/V26__create_fcm_device_tokens.sql` | 신규 |
| `db/migration/V27__add_notification_channel_to_users.sql` | 신규 |
| `adapter/out/persistence/fcm/` (3 files) | 신규 |
| `adapter/out/persistence/user/UserEntity.java` | `notificationChannel` 필드 추가 |
| `adapter/out/persistence/user/UserPersistenceAdapter.java` | 매핑 업데이트 |
| `adapter/out/notify/TelegramHttpClient.java` | 신규 (헬퍼 추출) |
| `adapter/out/notify/TelegramAdapter.java` | `NotifyPort`만 유지 |
| `adapter/out/notify/TelegramUserNotificationAdapter.java` | 신규 |
| `adapter/out/notify/FcmConfig.java` | 신규 |
| `adapter/out/notify/FcmAdapter.java` | 신규 |
| `adapter/out/notify/CompositeUserNotificationAdapter.java` | 신규 |
| `adapter/in/web/FcmController.java` | 신규 |
| `adapter/in/web/SettingsController.java` | `PATCH /notification-channel` 추가 |
| `application/service/UserService.java` | `UpdateNotificationChannelUseCase` 구현 |
| `application/service/FcmTokenService.java` | 신규 |
| `application.yml` + `.env.example` + `docker-compose.yml` | `FIREBASE_SERVICE_ACCOUNT_JSON` 추가 |
| 테스트 파일 7개 | `new User(...)` 12번째 인수 추가 |
| 신규 테스트 4개 | `TelegramUserNotificationAdapterTest`, `FcmAdapterTest`, `CompositeUserNotificationAdapterTest`, `FcmControllerTest` |
