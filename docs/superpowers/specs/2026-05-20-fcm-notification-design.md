# FCM 알림 채널 추가 설계

날짜: 2026-05-20  
상태: 확정

## 목표

기존 Telegram 알림에 FCM(Firebase Cloud Messaging)을 추가한다. 사용자는 설정에서 알림 수단을 TELEGRAM / FCM / ALL 중 선택할 수 있다. 웹 브라우저(Web Push)와 모바일(Android/iOS) 플랫폼을 모두 지원하며, 사용자당 여러 디바이스 토큰을 관리한다.

---

## 아키텍처

Composite 어댑터 패턴을 사용한다. `TradingService`를 포함한 application 레이어는 `UserNotificationPort` 하나만 호출하며, 채널 라우팅은 `CompositeUserNotificationAdapter`가 전담한다.

```
TradingService / UserService
        ↓
UserNotificationPort (domain/port/out)
        ↓
CompositeUserNotificationAdapter (adapter/out/notify)
   ├── TelegramUserNotificationAdapter  (notificationChannel == TELEGRAM 또는 ALL)
   └── FcmAdapter                       (notificationChannel == FCM 또는 ALL)
```

관리자 전용 알림(`NotifyPort`: 오류, 결산, 장마감)은 기존 `TelegramAdapter`가 그대로 담당하며 변경 없다.

---

## 신규 파일 목록

### domain

| 파일 | 설명 |
|------|------|
| `domain/model/NotificationChannel.java` | enum: TELEGRAM / FCM / ALL |
| `domain/model/FcmDeviceToken.java` | record: id, userId, token, platform, createdAt |
| `domain/port/out/FcmDeviceTokenPort.java` | save / delete / findTokensByUserId |

### adapter/out/notify (분리 및 신규)

| 파일 | 설명 |
|------|------|
| `TelegramHttpClient.java` | package-private 헬퍼 — sendMessage / sendWithInlineKeyboard (기존 TelegramAdapter 헬퍼 추출) |
| `TelegramUserNotificationAdapter.java` | UserNotificationPort 구현 (TelegramAdapter에서 분리) |
| `FcmAdapter.java` | UserNotificationPort 구현, Firebase Admin SDK 사용 |
| `CompositeUserNotificationAdapter.java` | UserNotificationPort 구현 — 채널 라우터 |

기존 `TelegramAdapter`는 `NotifyPort`만 구현하도록 축소.

### adapter/out/persistence

| 파일 | 설명 |
|------|------|
| `FcmDeviceTokenEntity.java` | JPA 엔티티 |
| `FcmDeviceTokenJpaRepository.java` | Spring Data JPA |
| `FcmDeviceTokenPersistenceAdapter.java` | FcmDeviceTokenPort 구현 |

### adapter/in/web

| 파일 | 설명 |
|------|------|
| `FcmController.java` | POST /api/fcm/tokens, DELETE /api/fcm/tokens/{token} |

기존 `SettingsController`에 `PATCH /api/settings/notification-channel` 엔드포인트 추가.

---

## 데이터 모델

### V26 — fcm_device_tokens 테이블

```sql
CREATE TABLE fcm_device_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token       TEXT NOT NULL UNIQUE,
    platform    VARCHAR(10) NOT NULL,  -- WEB | ANDROID | IOS
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);
CREATE INDEX ON fcm_device_tokens(user_id);
```

### V27 — users 테이블 notification_channel 추가

```sql
ALTER TABLE users
    ADD COLUMN notification_channel VARCHAR(10) NOT NULL DEFAULT 'TELEGRAM';
```

Java에서 `@Enumerated(EnumType.STRING)`으로 `NotificationChannel` enum과 매핑. 네이티브 ENUM 미사용(추후 값 추가/삭제 시 Flyway DDL 불필요).

### User record 변경

`notification_channel` 필드 추가 → `User`, `UserEntity`, `UserPersistenceAdapter`, 관련 테스트 전파 필요 (`User` record 필드 추가 시 동시 수정 파일 쌍 규칙 적용).

---

## 어댑터 상세

### CompositeUserNotificationAdapter

`UserNotificationPort`의 메서드를 수신자 기준으로 두 그룹으로 분류한다.

| 메서드 | 수신자 | 채널 |
|--------|--------|------|
| `notifyNewUser` | 관리자 | 항상 Telegram (관리자 봇 인라인 버튼 필요) |
| `notifyStrategyChanged` | 관리자 | 항상 Telegram |
| `notifyApproved` | 사용자 | user.notificationChannel 에 따라 라우팅 |
| `notifyRejected` | 사용자 | user.notificationChannel 에 따라 라우팅 |
| `notifyTradingReport` | 사용자 | user.notificationChannel 에 따라 라우팅 |

```java
@Component
@RequiredArgsConstructor
public class CompositeUserNotificationAdapter implements UserNotificationPort {

    private final TelegramUserNotificationAdapter telegram;
    private final FcmAdapter fcm;

    // 관리자 알림 — 채널 무관, 항상 Telegram
    @Override
    public void notifyNewUser(User user) { telegram.notifyNewUser(user); }

    @Override
    public void notifyStrategyChanged(User user, Account account, String action) {
        telegram.notifyStrategyChanged(user, account, action);
    }

    // 사용자 알림 — notificationChannel 에 따라 라우팅
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
        return u.notificationChannel() == TELEGRAM || u.notificationChannel() == ALL;
    }
    private boolean usesFcm(User u) {
        return u.notificationChannel() == FCM || u.notificationChannel() == ALL;
    }
}
```

### FcmAdapter

- 의존성: `firebase-admin` (Google Firebase Admin SDK for Java)
- `FirebaseMessaging.getInstance().sendEachForMulticast(MulticastMessage)` 호출
- 응답에서 실패 토큰(registration-token-not-registered 등) 자동 삭제 — `FcmDeviceTokenPort.delete()` 호출
- 전송 대상 토큰 없으면 `log.warn` 후 생략

### TelegramAdapter 변경

`UserNotificationPort` 구현 제거 → `TelegramUserNotificationAdapter`로 이전.  
기존 `sendMessage` / `sendWithInlineKeyboard` 메서드는 package-private `TelegramHttpClient`로 추출해 두 어댑터가 공유.

---

## API 설계

### FCM 토큰 관리

```
POST   /api/fcm/tokens
Body:  { "token": "...", "platform": "WEB" }
Auth:  Bearer JWT (인증 필수)
→ 현재 사용자의 fcm_device_tokens에 upsert (중복 토큰 무시)

DELETE /api/fcm/tokens/{token}
Auth:  Bearer JWT (본인 토큰만 삭제)
→ 해당 토큰 삭제
```

### 알림 채널 변경

```
PATCH  /api/settings/notification-channel
Body:  { "channel": "FCM" }   -- TELEGRAM | FCM | ALL
Auth:  Bearer JWT
→ users.notification_channel 업데이트
```

---

## kista-ui 통합

### 필요 파일

```
public/firebase-messaging-sw.js   -- Service Worker (백그라운드 알림)
lib/firebase.ts                   -- Firebase 앱 초기화
lib/fcm.ts                        -- 토큰 요청 / 서버 등록 / 권한 요청
hooks/useFcmToken.ts              -- 알림 권한 요청 + 토큰 등록 훅
```

### 사용자 흐름

1. 설정 화면 "FCM 알림 사용" 토글 ON
2. 브라우저 `Notification.requestPermission()` → 허용
3. FCM 토큰 발급 → `POST /api/fcm/tokens` 등록
4. `PATCH /api/settings/notification-channel { channel: "FCM" }` 채널 변경
5. 이후 알림이 브라우저 푸시(또는 모바일 푸시)로 수신

### 환경변수

```bash
# kista-ui (.env.local)
NEXT_PUBLIC_FIREBASE_API_KEY=
NEXT_PUBLIC_FIREBASE_PROJECT_ID=
NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=
NEXT_PUBLIC_FIREBASE_APP_ID=
NEXT_PUBLIC_FIREBASE_VAPID_KEY=

# kista-api (.env / Render env)
FIREBASE_SERVICE_ACCOUNT_JSON=   # Firebase Admin SDK 서비스 계정 JSON (한 줄 직렬화)
```

---

## 모바일 지원

현재 네이티브 앱 없음. `POST /api/fcm/tokens` API는 platform 필드로 `ANDROID` / `IOS`를 받을 수 있도록 열려 있어, 추후 React Native 또는 Flutter 앱에서 동일 엔드포인트로 연동 가능.

---

## 동시 수정 필요 파일 쌍 (기존 아키텍처 문서 추가)

| 파일 A | 파일 B |
|--------|--------|
| `User` record `notificationChannel` 추가 | `UserEntity` + `UserPersistenceAdapter` + `UserServiceTest` + `TelegramAdapterTest` + `TradingSchedulerTest` + `AccountServiceTest` + `TradingServiceTest` |
| `UserNotificationPort` 구현을 `TelegramAdapter`에서 제거 | `TelegramUserNotificationAdapter` 신규 생성 + `CompositeUserNotificationAdapter` 빈 등록 확인 |
| `FcmAdapter` 신규 | `FcmAdapterTest` 단위 테스트 |
| `FcmController` 신규 | `FcmControllerTest` (`@WebMvcTest` + `@MockBean JwtDecoder`) |
| `SettingsController` 엔드포인트 추가 | `SettingsControllerTest` `@MockBean` 추가 |

---

## 제외 범위

- 관리자 알림(`NotifyPort`: 오류/결산/장마감) — Telegram 유지, FCM 미적용
- Firebase 프로젝트 생성 및 VAPID 키 발급 — 인프라 수작업
- 모바일 앱 구현 — 토큰 API만 준비
