## com.kista.api.notify (`:api`)

Spring Modulith 모듈(CLOSED) — Telegram/FCM **순수 아웃바운드 알림 게이트웨이**. 자체 UseCase 없음, 다른 모듈 유스케이스·trading-core HTTP 호출 0 (가드: `HexagonalArchitectureTest.notify_must_stay_pure_outbound_gateway` — `..application.usecase..`·`org.springframework.web.client..` 의존 금지). "port"·"domain" 공개, 나머지 internal.

- `NotificationRecipient`는 notify가 실제로 읽는 필드만 담은 notify 소유 투영이다. notify 안에서 `User`/`UserSettings`는 gateway 어댑터에서만 import하고 변환은 `NotificationRecipients.from(User)` 한 곳이 맡는다.
- 알림 유형 무관 사용자 채널 라우팅은 `UserChannelNotifier`가 단일 지점이다 — 설정 게이트(`UserSettings.isNotificationEnabled`) 뒤 텔레그램·FCM leg를 각자 try/catch로 격리해 한쪽 실패가 다른 쪽·호출자로 전파되지 않는다. 새 알림 유형의 리스너는 (유형, 제목, 본문) 매핑만 하고 여기에 위임하며 라우팅을 복제하지 않는다. FCM 라우팅 판정은 `UserPushNotificationAdapter`(`PushNotificationPort`)가 단일 지점이고 `FcmAdapter.send`는 package-private이다.
- `TelegramAdapter.notifyError`는 발송 전 `AppErrorRaisedEvent`를 발행해 admin 리스너가 `app_error_logs`에 저장하게 한다(trading-core `TradingNotifyAdapter.notifyError`와 동일 구조). 그래서 `notify → admin` 의존은 만들지 않는다.
- 허용되는 `notify → user` 의존: user 이벤트 4종 구독(`NewUserRegisteredEvent`/`UserApprovedEvent`/`UserRejectedEvent`/`UserReappliedEvent`), `UserPort`/`UserSettingsPort`, `TelegramBotInfoPort` 구현. `notify → finance`는 `FinanceRegistrationReminderDueEvent` 구독 1건뿐이다(`finance → notify` 의존이 없어 순환 없음).
- `SseEmitterRegistry`/`TradeSseEmitterRegistry`는 포트 없이 구체 클래스를 직접 주입한다(호출자가 `adapter.in.web` 컨트롤러 2곳뿐이라 포트 역전 불필요). 패키지 세그먼트 `sse`를 유지해야 한다 (가드: `HexagonalArchitectureTest.sse_emitter_registry_must_not_be_used_in_application_layer`). SSE wire 타입은 `shared.contract`의 `TradeEventMessage`/`TradeEventEnvelope`이고 `TradeLegSummary`(sharedkernel)와 별개다.
- `PushNotificationStreamConsumer`(`stream:user.push-notification.requested`, 그룹 `root`): Pub/Sub와 달리 root가 잠시 내려가도 기동 후 이어서 소비한다. 보존 상한 `RedisStreamConfig.PUSH_NOTIFICATION_MAXLEN`=10,000건.
- `SchedulerNotifier`는 trading-core 잡 이름(`TRADING_JOBS`)을 건너뛴다. 이름 목록은 `SchedulerNotifierTradingJobsTest`가 trading-core 소스와 대조한다.
- **텔레그램 봇 명령 채널은 admin 소유** → `admin.md`. **매매 알림 6종은 `com.kista.tradingcore.notify` 소유** → `modules/tradingcore/notify.md`.

### FCM 디바이스 토큰 저장 규칙
한 사용자가 같은 플랫폼의 여러 디바이스 토큰을 가질 수 있다. 신규 토큰을 저장할 때 같은 사용자·플랫폼 토큰을 일괄 삭제하지 않는다. 동일 토큰의 기존 소유 레코드만 삭제한 뒤 현재 사용자에게 저장한다.

### 텔레그램 전송 (`TelegramHttpClient`)
- 텔레그램 HTTP 전송은 `com.kista.shared.platform.telegram.TelegramHttpClient` 하나로 root와 trading-core가 공용한다. 새 텔레그램 API 호출은 이 클래스에 메서드를 추가하고 notify·admin에 `RestClient`를 직접 쓰지 않는다. 사용자 botToken 호출이 필요하면 소유 모듈 포트(예: user `TelegramBotInfoPort`)를 `adapter/out/gateway` 어댑터(`TelegramBotInfoAdapter`)가 구현·위임한다.
- `RestClient` 빈이 여러 개라 빈 이름은 `telegramRestClient`/`telegramHttpClient`다. 주입은 필드명 `telegramRestClient` 또는 `TelegramHttpClient` 타입으로만 받는다(불일치 시 `NoUniqueBeanDefinitionException`).
- 사용자 봇 알림 규칙은 `modules/tradingcore/notify.md` "사용자 봇 발송 규칙"과 `architecture.md` "텔레그램 알림".
