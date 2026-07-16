# UserSettings Aggregate 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `User` 도메인에서 `balanceCheckEnabled`를 분리하고 `UserSettings` aggregate를 신설해 알림 타입별 on/off(`TRADING_ALERT`)를 DB로 관리한다.

**Architecture:** `user_settings` + `user_notification_prefs` 두 테이블로 사용자 설정을 분리. 헥사고날 포트-어댑터 패턴으로 도메인 모델 `UserSettings`를 JPA 어댑터와 격리. `User` 레코드에서 `balanceCheckEnabled` 제거 후 `UserSettings`에서 로드.

**Tech Stack:** Java 21 records, Spring Boot 3, JPA/Hibernate, Flyway, JUnit 5 + Mockito

## Global Constraints

- 패키지 루트: `com.kista`
- Flyway 최신 버전: V6 → 다음은 `V7__`
- 테스트: `@ExtendWith(MockitoExtension.class)` (단위), `@WebMvcTest` (컨트롤러)
- 커밋 author: `narafu <narafu@kakao.com>`
- 빌드: `./gradlew test` (전체), `./gradlew test --tests "패키지.클래스"` (단일)

---

## 파일 맵

**신규 생성**
```
src/main/resources/db/migration/V7__create_user_settings.sql
src/main/java/com/kista/domain/model/user/NotificationType.java
src/main/java/com/kista/domain/model/user/UserSettings.java
src/main/java/com/kista/domain/port/out/LoadUserSettingsPort.java
src/main/java/com/kista/domain/port/out/SaveUserSettingsPort.java
src/main/java/com/kista/domain/port/in/GetUserSettingsQuery.java
src/main/java/com/kista/domain/port/in/UpdateNotificationPrefUseCase.java
src/main/java/com/kista/domain/port/in/UpdateBalanceCheckUseCase.java
src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsJpaEntity.java
src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefJpaEntity.java
src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefId.java
src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsJpaRepository.java
src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefJpaRepository.java
src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapter.java
src/main/java/com/kista/application/service/user/UserSettingsService.java
src/test/java/com/kista/domain/model/user/UserSettingsTest.java
src/test/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapterTest.java
src/test/java/com/kista/application/service/user/UserSettingsServiceTest.java
```

**수정**
```
src/main/java/com/kista/domain/model/user/User.java                              — balanceCheckEnabled 제거
src/main/java/com/kista/domain/port/in/UserUseCase.java                          — updateBalanceCheckEnabled 제거
src/main/java/com/kista/adapter/out/persistence/user/UserEntity.java             — balanceCheckEnabled 컬럼 제거
src/main/java/com/kista/adapter/out/persistence/user/UserPersistenceAdapter.java — encrypt/toDomain 수정
src/main/java/com/kista/application/service/user/UserService.java                — updateBalanceCheckEnabled 구현 제거
src/main/java/com/kista/application/service/trading/CycleRotationService.java    — LoadUserSettingsPort 주입
src/main/java/com/kista/application/service/trading/TradingReporter.java         — TRADING_ALERT pref 체크
src/main/java/com/kista/adapter/in/web/dto/UserResponse.java                     — notificationPrefs 추가, from(user, settings)
src/main/java/com/kista/adapter/in/web/AuthController.java                       — GetUserSettingsQuery 주입
src/main/java/com/kista/adapter/in/web/SettingsController.java                   — 알림 endpoint 추가
src/test/java/com/kista/application/service/trading/CycleRotationServiceTest.java — LoadUserSettingsPort mock
src/test/java/com/kista/adapter/in/web/AuthControllerTokenTest.java              — User 생성자 인자 수정
src/test/java/com/kista/adapter/in/web/DevAuthControllerTest.java                — User 생성자 인자 수정
src/test/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapterTest.java — 동일
src/test/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapterTest.java  — 동일
src/test/java/com/kista/adapter/out/notify/FcmAdapterTest.java                   — 동일
src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java        — 동일
src/test/java/com/kista/adapter/in/schedule/TradingCloseSchedulerTest.java       — 동일
src/test/java/com/kista/application/service/auth/TokenServiceTest.java           — 동일
src/test/java/com/kista/application/service/admin/AdminServiceTest.java          — 동일
src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java    — 동일
src/test/java/com/kista/application/service/user/UserServiceTest.java            — 동일
```

---

### Task 1: Flyway V7 마이그레이션

**Files:**
- Create: `src/main/resources/db/migration/V7__create_user_settings.sql`

- [ ] **Step 1: SQL 작성**

```sql
-- user_settings: users.balance_check_enabled 이전
CREATE TABLE user_settings (
    user_id               BIGINT PRIMARY KEY REFERENCES users(id),
    balance_check_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO user_settings (user_id, balance_check_enabled)
SELECT id, balance_check_enabled FROM users;

ALTER TABLE users DROP COLUMN balance_check_enabled;

-- user_notification_prefs: 알림 타입별 on/off
CREATE TABLE user_notification_prefs (
    user_id BIGINT      NOT NULL REFERENCES users(id),
    type    VARCHAR(50) NOT NULL,
    enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (user_id, type)
);
```

- [ ] **Step 2: 커밋**

```bash
git add src/main/resources/db/migration/V7__create_user_settings.sql
git commit --author="narafu <narafu@kakao.com>" -m "feat: V7 user_settings + user_notification_prefs 마이그레이션"
```

---

### Task 2: 도메인 모델 — NotificationType + UserSettings

**Files:**
- Create: `src/main/java/com/kista/domain/model/user/NotificationType.java`
- Create: `src/main/java/com/kista/domain/model/user/UserSettings.java`
- Create: `src/test/java/com/kista/domain/model/user/UserSettingsTest.java`

**Interfaces:**
- Produces: `UserSettings(UUID userId, boolean balanceCheckEnabled, Map<NotificationType, Boolean> notificationPrefs)`, `UserSettings.defaultFor(UUID)`, `UserSettings.isNotificationEnabled(NotificationType)`

- [ ] **Step 1: 실패 테스트 작성**

```java
// src/test/java/com/kista/domain/model/user/UserSettingsTest.java
package com.kista.domain.model.user;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UserSettingsTest {

    @Test
    void isNotificationEnabled_returns_true_when_no_pref_record() {
        UserSettings settings = new UserSettings(UUID.randomUUID(), true, Map.of());
        assertThat(settings.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
    }

    @Test
    void isNotificationEnabled_returns_stored_value() {
        UserSettings settings = new UserSettings(UUID.randomUUID(), true,
                Map.of(NotificationType.TRADING_ALERT, false));
        assertThat(settings.isNotificationEnabled(NotificationType.TRADING_ALERT)).isFalse();
    }

    @Test
    void defaultFor_has_balanceCheck_true_and_empty_prefs() {
        UUID userId = UUID.randomUUID();
        UserSettings settings = UserSettings.defaultFor(userId);
        assertThat(settings.balanceCheckEnabled()).isTrue();
        assertThat(settings.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "com.kista.domain.model.user.UserSettingsTest"
```
Expected: 컴파일 에러 (클래스 없음)

- [ ] **Step 3: NotificationType 구현**

```java
// src/main/java/com/kista/domain/model/user/NotificationType.java
package com.kista.domain.model.user;

public enum NotificationType {
    TRADING_ALERT
}
```

- [ ] **Step 4: UserSettings 구현**

```java
// src/main/java/com/kista/domain/model/user/UserSettings.java
package com.kista.domain.model.user;

import java.util.Map;
import java.util.UUID;

public record UserSettings(
        UUID userId,
        boolean balanceCheckEnabled,
        Map<NotificationType, Boolean> notificationPrefs
) {
    public boolean isNotificationEnabled(NotificationType type) {
        return notificationPrefs.getOrDefault(type, true);
    }

    public static UserSettings defaultFor(UUID userId) {
        return new UserSettings(userId, true, Map.of());
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.domain.model.user.UserSettingsTest"
```
Expected: 3개 PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/kista/domain/model/user/NotificationType.java \
        src/main/java/com/kista/domain/model/user/UserSettings.java \
        src/test/java/com/kista/domain/model/user/UserSettingsTest.java
git commit --author="narafu <narafu@kakao.com>" -m "feat: NotificationType enum + UserSettings aggregate 도메인 모델"
```

---

### Task 3: 포트 인터페이스 5종

**Files:**
- Create: `src/main/java/com/kista/domain/port/out/LoadUserSettingsPort.java`
- Create: `src/main/java/com/kista/domain/port/out/SaveUserSettingsPort.java`
- Create: `src/main/java/com/kista/domain/port/in/GetUserSettingsQuery.java`
- Create: `src/main/java/com/kista/domain/port/in/UpdateNotificationPrefUseCase.java`
- Create: `src/main/java/com/kista/domain/port/in/UpdateBalanceCheckUseCase.java`

**Interfaces:**
- Produces: `LoadUserSettingsPort.loadByUserId(UUID)`, `SaveUserSettingsPort.save(UserSettings)`, `GetUserSettingsQuery.getByUserId(UUID)`, `UpdateNotificationPrefUseCase.update(UpdateNotificationPrefCommand)`, `UpdateBalanceCheckUseCase.update(UpdateBalanceCheckCommand)`

- [ ] **Step 1: out 포트 작성**

```java
// src/main/java/com/kista/domain/port/out/LoadUserSettingsPort.java
package com.kista.domain.port.out;

import com.kista.domain.model.user.UserSettings;
import java.util.Optional;
import java.util.UUID;

public interface LoadUserSettingsPort {
    Optional<UserSettings> loadByUserId(UUID userId);
}
```

```java
// src/main/java/com/kista/domain/port/out/SaveUserSettingsPort.java
package com.kista.domain.port.out;

import com.kista.domain.model.user.UserSettings;

public interface SaveUserSettingsPort {
    void save(UserSettings settings);
}
```

- [ ] **Step 2: in 포트 작성**

```java
// src/main/java/com/kista/domain/port/in/GetUserSettingsQuery.java
package com.kista.domain.port.in;

import com.kista.domain.model.user.UserSettings;
import java.util.UUID;

public interface GetUserSettingsQuery {
    UserSettings getByUserId(UUID userId);
}
```

```java
// src/main/java/com/kista/domain/port/in/UpdateNotificationPrefUseCase.java
package com.kista.domain.port.in;

import com.kista.domain.model.user.NotificationType;
import java.util.UUID;

public interface UpdateNotificationPrefUseCase {
    void update(UpdateNotificationPrefCommand command);

    record UpdateNotificationPrefCommand(UUID userId, NotificationType type, boolean enabled) {}
}
```

```java
// src/main/java/com/kista/domain/port/in/UpdateBalanceCheckUseCase.java
package com.kista.domain.port.in;

import java.util.UUID;

public interface UpdateBalanceCheckUseCase {
    void update(UpdateBalanceCheckCommand command);

    record UpdateBalanceCheckCommand(UUID userId, boolean enabled) {}
}
```

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/kista/domain/port/
git commit --author="narafu <narafu@kakao.com>" -m "feat: UserSettings 포트 인터페이스 5종 추가"
```

---

### Task 4: JPA 어댑터 — 엔티티 + 영속성 어댑터

**Files:**
- Create: `src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsJpaEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefJpaEntity.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefId.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsJpaRepository.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefJpaRepository.java`
- Create: `src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapter.java`
- Create: `src/test/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapterTest.java`

**Interfaces:**
- Consumes: `LoadUserSettingsPort`, `SaveUserSettingsPort`, `UserSettings`, `NotificationType`
- Produces: `UserSettingsPersistenceAdapter` implementing both ports

- [ ] **Step 1: 실패 테스트 작성**

```java
// src/test/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapterTest.java
package com.kista.adapter.out.persistence.settings;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSettingsPersistenceAdapterTest {

    @Mock UserSettingsJpaRepository settingsRepo;
    @Mock UserNotificationPrefJpaRepository prefRepo;
    @InjectMocks UserSettingsPersistenceAdapter adapter;

    private final UUID USER_ID = UUID.randomUUID();

    @Test
    void loadByUserId_returns_empty_when_no_settings_row() {
        when(settingsRepo.findById(USER_ID)).thenReturn(Optional.empty());
        assertThat(adapter.loadByUserId(USER_ID)).isEmpty();
    }

    @Test
    void loadByUserId_assembles_settings_with_prefs() {
        UserSettingsJpaEntity entity = new UserSettingsJpaEntity(USER_ID, false);
        UserNotificationPrefJpaEntity pref = new UserNotificationPrefJpaEntity(USER_ID, "TRADING_ALERT", false);
        when(settingsRepo.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(prefRepo.findByUserId(USER_ID)).thenReturn(List.of(pref));

        Optional<UserSettings> result = adapter.loadByUserId(USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get().balanceCheckEnabled()).isFalse();
        assertThat(result.get().isNotificationEnabled(NotificationType.TRADING_ALERT)).isFalse();
    }

    @Test
    void save_persists_settings_and_prefs() {
        UserSettings settings = new UserSettings(USER_ID, true,
                Map.of(NotificationType.TRADING_ALERT, false));

        adapter.save(settings);

        verify(settingsRepo).save(argThat(e -> e.getUserId().equals(USER_ID) && e.isBalanceCheckEnabled()));
        verify(prefRepo).save(argThat(e -> e.getType().equals("TRADING_ALERT") && !e.isEnabled()));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "com.kista.adapter.out.persistence.settings.UserSettingsPersistenceAdapterTest"
```
Expected: 컴파일 에러 (클래스 없음)

- [ ] **Step 3: 복합키 클래스 작성**

```java
// src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefId.java
package com.kista.adapter.out.persistence.settings;

import java.io.Serializable;
import java.util.UUID;

public record UserNotificationPrefId(UUID userId, String type) implements Serializable {}
```

- [ ] **Step 4: 엔티티 2종 작성**

```java
// src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsJpaEntity.java
package com.kista.adapter.out.persistence.settings;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserSettingsJpaEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID userId;

    @Column(name = "balance_check_enabled", nullable = false)
    private boolean balanceCheckEnabled;
}
```

```java
// src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefJpaEntity.java
package com.kista.adapter.out.persistence.settings;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "user_notification_prefs")
@IdClass(UserNotificationPrefId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserNotificationPrefJpaEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID userId;

    @Id
    @Column(name = "type", length = 50)
    private String type;

    @Column(nullable = false)
    private boolean enabled;
}
```

- [ ] **Step 5: JPA 리포지토리 2종 작성**

```java
// src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsJpaRepository.java
package com.kista.adapter.out.persistence.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

interface UserSettingsJpaRepository extends JpaRepository<UserSettingsJpaEntity, UUID> {}
```

```java
// src/main/java/com/kista/adapter/out/persistence/settings/UserNotificationPrefJpaRepository.java
package com.kista.adapter.out.persistence.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

interface UserNotificationPrefJpaRepository extends JpaRepository<UserNotificationPrefJpaEntity, UserNotificationPrefId> {
    List<UserNotificationPrefJpaEntity> findByUserId(UUID userId);
}
```

- [ ] **Step 6: 어댑터 구현**

```java
// src/main/java/com/kista/adapter/out/persistence/settings/UserSettingsPersistenceAdapter.java
package com.kista.adapter.out.persistence.settings;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.SaveUserSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UserSettingsPersistenceAdapter implements LoadUserSettingsPort, SaveUserSettingsPort {

    private final UserSettingsJpaRepository settingsRepo;
    private final UserNotificationPrefJpaRepository prefRepo;

    @Override
    public Optional<UserSettings> loadByUserId(UUID userId) {
        return settingsRepo.findById(userId).map(entity -> {
            Map<NotificationType, Boolean> prefs = prefRepo.findByUserId(userId).stream()
                    .collect(Collectors.toMap(
                            p -> NotificationType.valueOf(p.getType()),
                            UserNotificationPrefJpaEntity::isEnabled
                    ));
            return new UserSettings(userId, entity.isBalanceCheckEnabled(), prefs);
        });
    }

    @Override
    public void save(UserSettings settings) {
        settingsRepo.save(new UserSettingsJpaEntity(settings.userId(), settings.balanceCheckEnabled()));
        settings.notificationPrefs().forEach((type, enabled) ->
                prefRepo.save(new UserNotificationPrefJpaEntity(settings.userId(), type.name(), enabled))
        );
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.out.persistence.settings.UserSettingsPersistenceAdapterTest"
```
Expected: 3개 PASS

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/kista/adapter/out/persistence/settings/ \
        src/test/java/com/kista/adapter/out/persistence/settings/
git commit --author="narafu <narafu@kakao.com>" -m "feat: UserSettings JPA 엔티티 + 영속성 어댑터"
```

---

### Task 5: UserSettingsService

**Files:**
- Create: `src/main/java/com/kista/application/service/user/UserSettingsService.java`
- Create: `src/test/java/com/kista/application/service/user/UserSettingsServiceTest.java`

**Interfaces:**
- Consumes: `LoadUserSettingsPort`, `SaveUserSettingsPort`, `AccountPort`, `StrategyPort`
- Produces: `UserSettingsService` implements `GetUserSettingsQuery`, `UpdateNotificationPrefUseCase`, `UpdateBalanceCheckUseCase`

- [ ] **Step 1: 실패 테스트 작성**

```java
// src/test/java/com/kista/application/service/user/UserSettingsServiceTest.java
package com.kista.application.service.user;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase.UpdateBalanceCheckCommand;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase.UpdateNotificationPrefCommand;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.SaveUserSettingsPort;
import com.kista.domain.port.out.StrategyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    @Mock LoadUserSettingsPort loadPort;
    @Mock SaveUserSettingsPort savePort;
    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @InjectMocks UserSettingsService service;

    private final UUID USER_ID = UUID.randomUUID();

    @Test
    void getByUserId_returns_defaults_when_no_record() {
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.empty());
        UserSettings result = service.getByUserId(USER_ID);
        assertThat(result.balanceCheckEnabled()).isTrue();
        assertThat(result.isNotificationEnabled(NotificationType.TRADING_ALERT)).isTrue();
    }

    @Test
    void getByUserId_returns_stored_settings() {
        UserSettings stored = new UserSettings(USER_ID, false, Map.of(NotificationType.TRADING_ALERT, false));
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.of(stored));
        assertThat(service.getByUserId(USER_ID)).isSameAs(stored);
    }

    @Test
    void updateNotificationPref_saves_updated_pref() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of());
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.of(existing));

        service.update(new UpdateNotificationPrefCommand(USER_ID, NotificationType.TRADING_ALERT, false));

        verify(savePort).save(argThat(s ->
                !s.isNotificationEnabled(NotificationType.TRADING_ALERT)));
    }

    @Test
    void updateBalanceCheck_saves_updated_value() {
        UserSettings existing = new UserSettings(USER_ID, true, Map.of());
        when(loadPort.loadByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(accountPort.findByUserId(USER_ID)).thenReturn(List.of());

        service.update(new UpdateBalanceCheckCommand(USER_ID, false));

        verify(savePort).save(argThat(s -> !s.balanceCheckEnabled()));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "com.kista.application.service.user.UserSettingsServiceTest"
```
Expected: 컴파일 에러 (클래스 없음)

- [ ] **Step 3: UserSettingsService 구현**

```java
// src/main/java/com/kista/application/service/user/UserSettingsService.java
package com.kista.application.service.user;

import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.GetUserSettingsQuery;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.LoadUserSettingsPort;
import com.kista.domain.port.out.SaveUserSettingsPort;
import com.kista.domain.port.out.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserSettingsService implements GetUserSettingsQuery, UpdateNotificationPrefUseCase, UpdateBalanceCheckUseCase {

    private final LoadUserSettingsPort loadPort;
    private final SaveUserSettingsPort savePort;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @Override
    public UserSettings getByUserId(UUID userId) {
        return loadPort.loadByUserId(userId).orElse(UserSettings.defaultFor(userId));
    }

    @Override
    @Transactional
    public void update(UpdateNotificationPrefCommand command) {
        UserSettings current = getByUserId(command.userId());
        Map<NotificationType, Boolean> updatedPrefs = new HashMap<>(current.notificationPrefs());
        updatedPrefs.put(command.type(), command.enabled());
        savePort.save(new UserSettings(command.userId(), current.balanceCheckEnabled(), updatedPrefs));
        log.info("알림 설정 변경: userId={}, type={}, enabled={}", command.userId(), command.type(), command.enabled());
    }

    @Override
    @Transactional
    public void update(UpdateBalanceCheckCommand command) {
        UserSettings current = getByUserId(command.userId());
        boolean previous = current.balanceCheckEnabled();
        savePort.save(new UserSettings(command.userId(), command.enabled(), current.notificationPrefs()));
        log.info("잔고 검증 설정 변경: userId={}, {}→{}", command.userId(), previous, command.enabled());

        long activeCount = countActiveStrategies(command.userId());
        if (!previous && command.enabled() && activeCount > 0) {
            log.warn("[잔고검증 OFF→ON] userId={} — 활성 전략 {}개. 시드가 실잔고 초과 시 다음 사이클에서 PAUSED됩니다.", command.userId(), activeCount);
        }
        if (previous && !command.enabled()) {
            log.warn("[잔고검증 ON→OFF] userId={} — 활성 전략 {}개. 실잔고 초과 시드로 재등록 시 KIS 주문 거부 가능.", command.userId(), activeCount);
        }
    }

    private long countActiveStrategies(UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .flatMap(account -> strategyPort.findByAccountId(account.id()).stream())
                .filter(Strategy::isActive)
                .count();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.application.service.user.UserSettingsServiceTest"
```
Expected: 4개 PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/kista/application/service/user/UserSettingsService.java \
        src/test/java/com/kista/application/service/user/UserSettingsServiceTest.java
git commit --author="narafu <narafu@kakao.com>" -m "feat: UserSettingsService — GetUserSettingsQuery + UpdateNotificationPrefUseCase + UpdateBalanceCheckUseCase"
```

---

### Task 6: User 리팩터 — balanceCheckEnabled 제거 + 전체 참조 정리

이 태스크는 `User` 레코드에서 `balanceCheckEnabled`를 제거하고 컴파일 에러를 한 번에 해소하는 원자적 변경이다.

**Files:**
- Modify: `src/main/java/com/kista/domain/model/user/User.java`
- Modify: `src/main/java/com/kista/domain/port/in/UserUseCase.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/user/UserEntity.java`
- Modify: `src/main/java/com/kista/adapter/out/persistence/user/UserPersistenceAdapter.java`
- Modify: `src/main/java/com/kista/application/service/user/UserService.java`
- Modify: `src/main/java/com/kista/application/service/trading/CycleRotationService.java`
- Modify: `src/main/java/com/kista/application/service/trading/TradingReporter.java`
- Modify: `src/main/java/com/kista/adapter/in/web/dto/UserResponse.java`
- Modify: `src/main/java/com/kista/adapter/in/web/AuthController.java`
- Modify: `src/main/java/com/kista/adapter/in/web/SettingsController.java`
- Modify: 테스트 파일 다수 (아래 각 Step에 명시)

**Interfaces:**
- Consumes: `GetUserSettingsQuery`, `LoadUserSettingsPort`, `UserSettings`, `NotificationType`

- [ ] **Step 1: User 레코드에서 balanceCheckEnabled 제거**

`src/main/java/com/kista/domain/model/user/User.java` 수정:
- 레코드 컴포넌트에서 `boolean balanceCheckEnabled` 줄 삭제
- `withBalanceCheckEnabled(boolean value)` 메서드 삭제
- 나머지 `with*` 메서드에서 `balanceCheckEnabled` 인자 제거

변경 후 레코드 시그니처:
```java
public record User(
        UUID id,
        String kakaoId,
        String nickname,
        UserStatus status,
        UserRole role,
        String telegramBotToken,
        String telegramChatId,
        String telegramBotUsername,
        Instant lastReappliedAt,
        NotificationChannel notificationChannel
) {
```

`with*` 메서드들 — `balanceCheckEnabled` 인자 제거:
```java
public User withStatus(UserStatus newStatus) {
    return new User(id, kakaoId, nickname, newStatus, role,
            telegramBotToken, telegramChatId, telegramBotUsername,
            lastReappliedAt, notificationChannel);
}

public User withStatus(UserStatus newStatus, Instant newLastReappliedAt) {
    return new User(id, kakaoId, nickname, newStatus, role,
            telegramBotToken, telegramChatId, telegramBotUsername,
            newLastReappliedAt, notificationChannel);
}

public User withTelegram(String botToken, String chatId, String botUsername) {
    return new User(id, kakaoId, nickname, status, role,
            botToken, chatId, botUsername, lastReappliedAt, notificationChannel);
}

public User withNotificationChannel(NotificationChannel channel) {
    return new User(id, kakaoId, nickname, status, role,
            telegramBotToken, telegramChatId, telegramBotUsername,
            lastReappliedAt, channel);
}

public User withRole(UserRole newRole) {
    return new User(id, kakaoId, nickname, status, newRole,
            telegramBotToken, telegramChatId, telegramBotUsername,
            lastReappliedAt, notificationChannel);
}

public User withNickname(String nickname) {
    return new User(id, kakaoId, nickname, status, role,
            telegramBotToken, telegramChatId, telegramBotUsername,
            lastReappliedAt, notificationChannel);
}
```

- [ ] **Step 2: UserUseCase에서 updateBalanceCheckEnabled 제거**

`src/main/java/com/kista/domain/port/in/UserUseCase.java`:
```java
// 아래 줄 삭제
void updateBalanceCheckEnabled(UUID userId, boolean enabled);
```

- [ ] **Step 3: UserEntity 수정**

`src/main/java/com/kista/adapter/out/persistence/user/UserEntity.java`:

`balanceCheckEnabled` 필드, `@Column` 어노테이션, `fromModel`과 `toModel`에서 해당 줄 제거:

```java
// 삭제할 필드
@Column(name = "balance_check_enabled", nullable = false)
private boolean balanceCheckEnabled;
```

`fromModel` — `e.balanceCheckEnabled = user.balanceCheckEnabled();` 줄 삭제

`toModel` — 마지막 인자 `balanceCheckEnabled` 제거:
```java
User toModel() {
    return new User(id, kakaoId, nickname, status, role,
            telegramBotToken, telegramChatId, telegramBotUsername, lastReappliedAt,
            notificationChannel != null ? notificationChannel : NotificationChannel.TELEGRAM);
}
```

- [ ] **Step 4: UserPersistenceAdapter 수정**

`encrypt` 메서드와 `toDomain` 메서드에서 `balanceCheckEnabled()` 인자 제거:

```java
private User encrypt(User user) {
    if (user.telegramBotToken() == null) return user;
    return new User(user.id(), user.kakaoId(), user.nickname(), user.status(), user.role(),
            crypto.encrypt(user.telegramBotToken()), user.telegramChatId(), user.telegramBotUsername(),
            user.lastReappliedAt(),
            user.notificationChannel() != null ? user.notificationChannel() : NotificationChannel.TELEGRAM);
}

private User toDomain(UserEntity e) {
    User raw = e.toModel();
    if (raw.telegramBotToken() == null) return raw;
    return new User(raw.id(), raw.kakaoId(), raw.nickname(), raw.status(), raw.role(),
            crypto.decrypt(raw.telegramBotToken()), raw.telegramChatId(), raw.telegramBotUsername(),
            raw.lastReappliedAt(),
            raw.notificationChannel() != null ? raw.notificationChannel() : NotificationChannel.TELEGRAM);
}
```

- [ ] **Step 5: UserService에서 updateBalanceCheckEnabled 구현 제거**

`src/main/java/com/kista/application/service/user/UserService.java`:
- `@Override public void updateBalanceCheckEnabled(...)` 메서드 전체 삭제
- `countActiveStrategies` private 메서드도 삭제 (UserSettingsService로 이전됨)
- 해당 메서드에서만 쓰이는 `strategyPort` 필드 및 생성자 주입 제거 (다른 곳에서 사용하면 유지)

`strategyPort`가 다른 곳에서도 쓰이는지 확인:
```bash
grep -n "strategyPort" src/main/java/com/kista/application/service/user/UserService.java
```
사용 위치가 `updateBalanceCheckEnabled`와 `countActiveStrategies`뿐이면 `StrategyPort strategyPort` 필드도 삭제.

또한 `UserService`가 `UserUseCase` 인터페이스를 `implements`하므로 `updateBalanceCheckEnabled` 구현 제거 후 컴파일 에러 없음 (Step 2에서 인터페이스도 제거했으므로).

- [ ] **Step 6: CycleRotationService 수정 — LoadUserSettingsPort 주입**

`src/main/java/com/kista/application/service/trading/CycleRotationService.java`:

import 추가:
```java
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.LoadUserSettingsPort;
```

필드 추가 (`@RequiredArgsConstructor`가 있으면 자동 주입):
```java
private final LoadUserSettingsPort loadUserSettingsPort;
```

`resolvePolicy` 메서드 — 파라미터 타입을 `User user`에서 `UserSettings settings`로 변경, 나머지 로직 동일:
```java
private SeedResolutionPolicy resolvePolicy(UserSettings settings, Account account, Strategy strategy) {
    if (!settings.balanceCheckEnabled()) {
        // OFF: 내부 원장만 사용 (브로커 조회 없음)
        return new SeedResolutionPolicy() {
            @Override
            public Optional<BigDecimal> resolveAvailableBalance(Strategy s, BigDecimal maintainSeed, BigDecimal maxSeed) {
                return Optional.of(s.cycleSeedType() == Strategy.CycleSeedType.MAX ? maxSeed : maintainSeed);
            }
            @Override
            public StrategyCycle.SeedResolvedBy seedResolvedBy() { return StrategyCycle.SeedResolvedBy.LEDGER_ONLY; }
        };
    }
    // ON: 브로커 실잔고 조회
    return new SeedResolutionPolicy() {
        @Override
        public Optional<BigDecimal> resolveAvailableBalance(Strategy s, BigDecimal maintainSeed, BigDecimal maxSeed) {
            return Optional.ofNullable(fetchUsdBalance(s, account));
        }
        @Override
        public StrategyCycle.SeedResolvedBy seedResolvedBy() { return StrategyCycle.SeedResolvedBy.BROKER_VERIFIED; }
    };
}
```

`rotate` 메서드 내 `resolvePolicy` 호출부:
```java
UserSettings settings = loadUserSettingsPort.loadByUserId(user.id())
        .orElse(UserSettings.defaultFor(user.id()));
SeedResolutionPolicy policy = resolvePolicy(settings, account, strategy);
```

- [ ] **Step 7: TradingReporter 수정 — TRADING_ALERT pref 체크**

`src/main/java/com/kista/application/service/trading/TradingReporter.java`:

import 추가:
```java
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.LoadUserSettingsPort;
```

필드 추가:
```java
private final LoadUserSettingsPort loadUserSettingsPort;
```

`recordAndNotify` 내 `notifyTradingReport` 호출부를 pref 체크로 감싸기:
```java
TradingReport report = buildReport(today, strategy.type(), strategy.ticker(), executions);
UserSettings settings = loadUserSettingsPort.loadByUserId(user.id())
        .orElse(UserSettings.defaultFor(user.id()));
if (settings.isNotificationEnabled(NotificationType.TRADING_ALERT)) {
    userNotificationPort.notifyTradingReport(user, account, report);
    log.info("[{}] 리포트 발송 완료", account.nickname());
} else {
    log.info("[{}] TRADING_ALERT 비활성 — 리포트 발송 생략", account.nickname());
}
```

- [ ] **Step 8: UserResponse 수정 — notificationPrefs 추가**

`src/main/java/com/kista/adapter/in/web/dto/UserResponse.java`:

```java
package com.kista.adapter.in.web.dto;

import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.UserSettings;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record UserResponse(
        @Schema(description = "사용자 고유 ID") UUID id,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "계정 상태 (PENDING/ACTIVE/REJECTED)") User.UserStatus status,
        @Schema(description = "텔레그램 알림 설정 여부") boolean hasTelegram,
        @Schema(description = "역할 (USER/ADMIN)") User.UserRole role,
        @Schema(description = "텔레그램 봇 username") String telegramBotUsername,
        @Schema(description = "알림 채널 (TELEGRAM/FCM/ALL/NONE)") NotificationChannel notificationChannel,
        @Schema(description = "실잔고 검증 여부") boolean balanceCheckEnabled,
        @Schema(description = "알림 타입별 on/off (예: {\"TRADING_ALERT\": true})") Map<String, Boolean> notificationPrefs
) {
    public static UserResponse from(User user, UserSettings settings) {
        Map<String, Boolean> prefs = settings.notificationPrefs().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        return new UserResponse(
                user.id(),
                user.nickname(),
                user.status(),
                user.telegramChatId() != null,
                user.role(),
                user.telegramBotUsername(),
                user.notificationChannel(),
                settings.balanceCheckEnabled(),
                prefs
        );
    }
}
```

- [ ] **Step 9: AuthController 수정 — GetUserSettingsQuery 주입**

`src/main/java/com/kista/adapter/in/web/AuthController.java`:

import 추가:
```java
import com.kista.domain.port.in.GetUserSettingsQuery;
import com.kista.domain.model.user.UserSettings;
```

필드 추가:
```java
private final GetUserSettingsQuery getUserSettingsQuery;
```

`kakaoCallback` 수정:
```java
UserSettings settings = getUserSettingsQuery.getByUserId(user.id());
return new KakaoLoginResponse(at, "bearer", jwtIssuerService.expiresInSeconds(), UserResponse.from(user, settings));
```

`me()` 수정:
```java
@GetMapping("/me")
public UserResponse me(@AuthenticationPrincipal UUID userId) {
    User user = userUseCase.getById(userId);
    UserSettings settings = getUserSettingsQuery.getByUserId(userId);
    return UserResponse.from(user, settings);
}
```

- [ ] **Step 10: SettingsController 수정 — balanceCheck 분리 + 알림 endpoint 추가**

`src/main/java/com/kista/adapter/in/web/SettingsController.java`:

import 추가:
```java
import com.kista.domain.port.in.UpdateBalanceCheckUseCase;
import com.kista.domain.port.in.UpdateBalanceCheckUseCase.UpdateBalanceCheckCommand;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase;
import com.kista.domain.port.in.UpdateNotificationPrefUseCase.UpdateNotificationPrefCommand;
import com.kista.domain.model.user.NotificationType;
```

필드 추가 (`@RequiredArgsConstructor`):
```java
private final UpdateBalanceCheckUseCase updateBalanceCheckUseCase;
private final UpdateNotificationPrefUseCase updateNotificationPrefUseCase;
```

`updateBalanceCheck` 메서드 수정:
```java
@PatchMapping("/balance-check")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void updateBalanceCheck(@AuthenticationPrincipal UUID userId,
                               @RequestBody BalanceCheckRequest body) {
    updateBalanceCheckUseCase.update(new UpdateBalanceCheckCommand(userId, body.enabled()));
}
```

알림 endpoint 추가:
```java
record NotificationPrefRequest(boolean enabled) {}

@Operation(summary = "알림 타입 on/off", description = "TRADING_ALERT 등 알림 타입별 활성화 여부. body: {\"enabled\": false}")
@ApiResponse(responseCode = "204", description = "변경 성공")
@ApiResponse(responseCode = "400", description = "알 수 없는 알림 타입")
@PatchMapping("/notifications/{type}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void updateNotificationPref(@AuthenticationPrincipal UUID userId,
                                    @PathVariable String type,
                                    @RequestBody NotificationPrefRequest body) {
    NotificationType notificationType;
    try {
        notificationType = NotificationType.valueOf(type.toUpperCase());
    } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("알 수 없는 알림 타입: " + type + ". 허용값: TRADING_ALERT");
    }
    updateNotificationPrefUseCase.update(new UpdateNotificationPrefCommand(userId, notificationType, body.enabled()));
}
```

`userUseCase.updateBalanceCheckEnabled(userId, body.enabled())` 호출부가 있으면 삭제 (위 수정으로 교체됨).

- [ ] **Step 11: 테스트 픽스처 일괄 수정 — new User(...) 마지막 인자 제거**

아래 파일들에서 `new User(...)` 생성자 호출의 마지막 `boolean` 인자(balanceCheckEnabled)를 제거.

각 파일에서 찾아야 할 패턴: `new User(id, kakaoId, nickname, status, role, ..., notificationChannel, true)` 또는 `..., false)` → 마지막 `, true` 또는 `, false` 제거.

```
src/test/java/com/kista/adapter/out/notify/CompositeUserNotificationAdapterTest.java
src/test/java/com/kista/adapter/out/notify/TelegramUserNotificationAdapterTest.java
src/test/java/com/kista/adapter/out/notify/FcmAdapterTest.java
src/test/java/com/kista/adapter/in/schedule/TradingOpenSchedulerTest.java
src/test/java/com/kista/adapter/in/schedule/TradingCloseSchedulerTest.java
src/test/java/com/kista/adapter/in/web/AuthControllerTokenTest.java
src/test/java/com/kista/adapter/in/web/DevAuthControllerTest.java
src/test/java/com/kista/application/service/auth/TokenServiceTest.java
src/test/java/com/kista/application/service/admin/AdminServiceTest.java
src/test/java/com/kista/application/service/strategy/StrategyServiceTest.java
src/test/java/com/kista/application/service/user/UserServiceTest.java
```

`CycleRotationServiceTest.java` 추가 수정:
- `new User(...)` 마지막 인자 제거
- `LoadUserSettingsPort` mock 추가 + `when(loadUserSettingsPort.loadByUserId(any())).thenReturn(Optional.of(UserSettings.defaultFor(USER_ID)))` stub 추가 (balanceCheck=true 기본값)

- [ ] **Step 12: 전체 테스트 통과 확인**

```bash
./gradlew test
```
Expected: 모든 기존 테스트 PASS (회귀 없음)

- [ ] **Step 13: 커밋**

```bash
git add -u
git commit --author="narafu <narafu@kakao.com>" -m "refactor: User에서 balanceCheckEnabled 제거 → UserSettings aggregate로 이전"
```

---

### Task 7: SettingsController 통합 테스트 + openapi 확인

**Files:**
- Modify: `src/test/java/com/kista/adapter/in/web/SettingsControllerTest.java`

- [ ] **Step 1: 새 endpoint 테스트 추가**

`SettingsControllerTest.java`에 아래 테스트 추가:

```java
@MockitoBean UpdateNotificationPrefUseCase updateNotificationPrefUseCase;
@MockitoBean UpdateBalanceCheckUseCase updateBalanceCheckUseCase;
```

기존 `@MockitoBean UserUseCase userUseCase;`는 유지 (다른 endpoint에서 사용).

```java
@Test
void patch_notifications_trading_alert_returns_204() throws Exception {
    mockMvc.perform(patch("/api/settings/notifications/TRADING_ALERT")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\": false}")
                    .with(csrf()).with(authentication(mockAuth())))
            .andExpect(status().isNoContent());

    verify(updateNotificationPrefUseCase).update(
            argThat(cmd -> cmd.type().name().equals("TRADING_ALERT") && !cmd.enabled()));
}

@Test
void patch_notifications_unknown_type_returns_400() throws Exception {
    mockMvc.perform(patch("/api/settings/notifications/UNKNOWN_TYPE")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\": false}")
                    .with(csrf()).with(authentication(mockAuth())))
            .andExpect(status().isBadRequest());
}

@Test
void patch_balance_check_calls_use_case() throws Exception {
    mockMvc.perform(patch("/api/settings/balance-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\": false}")
                    .with(csrf()).with(authentication(mockAuth())))
            .andExpect(status().isNoContent());

    verify(updateBalanceCheckUseCase).update(argThat(cmd -> !cmd.enabled()));
}
```

- [ ] **Step 2: 테스트 통과 확인**

```bash
./gradlew test --tests "com.kista.adapter.in.web.SettingsControllerTest"
```
Expected: 모든 케이스 PASS (기존 + 신규)

- [ ] **Step 3: 전체 빌드 확인**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/test/java/com/kista/adapter/in/web/SettingsControllerTest.java
git commit --author="narafu <narafu@kakao.com>" -m "test: SettingsController 알림 endpoint + balance-check 테스트 추가"
```
