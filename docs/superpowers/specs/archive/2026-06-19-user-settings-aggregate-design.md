# UserSettings Aggregate 설계

## 배경

기존 `User` 도메인에 `balanceCheckEnabled` 같은 설정 플래그가 산재해 있고, 알림 종류별 on/off 기능 추가 시 `users` 테이블 컬럼이 계속 늘어나는 구조적 문제가 있음. `UserSettings` aggregate를 신설해 사용자 설정을 한 곳으로 통합하고, 알림 타입 테이블(`user_notification_prefs`)로 확장성을 확보한다.

## 범위

- `UserSettings` aggregate 신설 (balanceCheckEnabled + 알림 prefs)
- `NotificationType` enum: `TRADING_ALERT` (초기)
- `PATCH /api/settings/notifications/{type}` — 알림 타입 토글 API
- `PATCH /api/settings/balance-check` — 기존 API를 새 aggregate 경유로 교체
- `GET /api/settings/user` — UserSettings 조회
- `balanceCheckEnabled` 컬럼을 `users` → `user_settings`로 이전

범위 밖: 시스템 점검 알림, 주간 리포트, FCM/Telegram 채널 설정 변경

## 도메인 모델

```java
public record UserSettings(
    UserId userId,
    boolean balanceCheckEnabled,
    Map<NotificationType, Boolean> notificationPrefs
) {
    public boolean isNotificationEnabled(NotificationType type) {
        return notificationPrefs.getOrDefault(type, true); // 기본값: 활성화
    }
}

public enum NotificationType {
    TRADING_ALERT
}
```

기본값 정책: 레코드 없는 타입은 `true`(활성) 반환 — 신규 유저는 모든 알림 수신.

## DB 스키마

```sql
-- V{n}__create_user_settings.sql
CREATE TABLE user_settings (
    user_id               BIGINT PRIMARY KEY REFERENCES users(id),
    balance_check_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO user_settings (user_id, balance_check_enabled)
SELECT id, balance_check_enabled FROM users;

ALTER TABLE users DROP COLUMN balance_check_enabled;

CREATE TABLE user_notification_prefs (
    user_id BIGINT      NOT NULL REFERENCES users(id),
    type    VARCHAR(50) NOT NULL,
    enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (user_id, type)
);
```

신규 유저가 `user_notification_prefs`에 레코드를 가지지 않아도 `getOrDefault(type, true)`로 기본 활성 처리.

## 포트

```java
// out
interface LoadUserSettingsPort {
    Optional<UserSettings> loadByUserId(UserId userId);
}

interface SaveUserSettingsPort {
    void save(UserSettings settings);
}

// in
interface GetUserSettingsQuery {
    UserSettings getByUserId(UserId userId); // 레코드 없으면 기본값 반환
}

interface UpdateNotificationPrefUseCase {
    void update(UpdateNotificationPrefCommand command);
}
record UpdateNotificationPrefCommand(UserId userId, NotificationType type, boolean enabled) {}

interface UpdateBalanceCheckUseCase {
    void update(UpdateBalanceCheckCommand command);
}
record UpdateBalanceCheckCommand(UserId userId, boolean enabled) {}
```

## 서비스

`UserSettingsService`가 위 인터페이스를 모두 구현. `LoadUserSettingsPort`로 조회 → 변경 → `SaveUserSettingsPort`로 저장.

기존 balance-check 관련 유스케이스/서비스는 `UserSettingsService`로 통합.

## JPA 어댑터

```java
@Entity @Table(name = "user_settings")
class UserSettingsJpaEntity {
    @Id Long userId;
    boolean balanceCheckEnabled;
}

@Entity @Table(name = "user_notification_prefs")
@IdClass(UserNotificationPrefId.class)
class UserNotificationPrefJpaEntity {
    @Id Long userId;
    @Id String type;
    boolean enabled;
}
```

`UserSettingsPersistenceAdapter`가 두 테이블을 각각 조회 후 `UserSettings` 도메인으로 조립.

## API

| Method | Path | Body | 설명 |
|--------|------|------|------|
| GET | `/api/settings/user` | — | UserSettings 조회 |
| PATCH | `/api/settings/notifications/{type}` | `{ "enabled": boolean }` | 알림 타입 토글 |
| PATCH | `/api/settings/balance-check` | `{ "enabled": boolean }` | 잔고 확인 토글 |

응답: 200 OK + 변경된 `UserSettings` DTO.

## 기존 코드 변경 지점

| 파일 | 변경 |
|------|------|
| `UserEntity.java` | `balanceCheckEnabled` 컬럼 제거 |
| `TradingReporter.java` | `user.balanceCheckEnabled()` → `userSettingsService.getByUserId(...)` |
| 기존 balance-check 유스케이스 | `UpdateBalanceCheckUseCase`로 교체 |

## 신규 파일

```
domain/model/user/UserSettings.java
domain/model/user/NotificationType.java
domain/port/in/GetUserSettingsQuery.java
domain/port/in/UpdateNotificationPrefUseCase.java
domain/port/in/UpdateBalanceCheckUseCase.java
domain/port/out/LoadUserSettingsPort.java
domain/port/out/SaveUserSettingsPort.java
application/service/UserSettingsService.java
adapter/out/persistence/UserSettingsPersistenceAdapter.java
adapter/out/persistence/entity/UserSettingsJpaEntity.java
adapter/out/persistence/entity/UserNotificationPrefJpaEntity.java
adapter/out/persistence/entity/UserNotificationPrefId.java
adapter/in/web/UserSettingsController.java
db/migration/V{n}__create_user_settings.sql
```

## 검증

- `./gradlew test` — 기존 테스트 회귀 없음
- `UserSettingsService` 단위 테스트: 기본값 반환, 토글, balance-check
- `UserSettingsPersistenceAdapter` 통합 테스트: 저장/조회 왕복
- API 테스트: PATCH → GET으로 변경 확인
