## com.kista.notify (`:api`)

com.kista.notify/    ← Spring Modulith 모듈(CLOSED) — Telegram/FCM 알림 발송. 얇은 게이트웨이(자체 UseCase 없음) — `application.port.output`만 공개("port" NamedInterface), 나머지 internal. domain/model엔 SSE 값 객체 `TradeEventView` 하나뿐(NamedInterface 없음)
  application/port/output/ ← NotifyPort/UserNotificationPort/FcmDeviceTokenPort/RealtimeNotificationPort(SSE — `notifyStatusChange(UUID, UserStatus)`/`notifyTrade(UUID, TradeEventView)`) — "port". `TradeEventView`는 trading-core `com.kista.trading.notify.domain.model.TradeEventView`(발행측)와 필드 byte-identical own-type — Redis Pub/Sub(`RedisTradeEventSubscriber`)가 JSON 계약으로만 동기화(순환 불가피 (a), `OwnTypeContractTest`가 exact로 검증). `TradeLegSummary`(sharedkernel)와 별개 타입
  adapter/in/telegram/ ← TelegramWebhookController + TelegramBotService, TelegramApiClient(package-private) + TelegramUpdate
  adapter/in/web/     ← FcmController/TradeStreamController(매매 이벤트 SSE)/StatusStreamController(`GET /api/auth/status-stream`) + dto/FcmTokenRequest
  adapter/out/gateway/ ← TelegramAdapter(관리자봇), CompositeUserNotificationAdapter → TelegramUserNotificationAdapter + FcmAdapter(사용자 알림), TelegramBotInfoAdapter/TelegramHttpClient/TelegramConfig/TelegramProperties/FcmConfig + 이벤트 리스너 5종(UserDeletedNotifier/MarketAlertNotifier/StatsAlertNotifier/SchedulerNotifier/UserFcmCleanupListener)
  adapter/out/sse/    ← SseEmitterRegistry(사용자별)/TradeSseEmitterRegistry(매매 이벤트) — `sse` 경로 세그먼트 유지 필수(`HexagonalArchitectureTest.sse_emitter_registry_must_not_be_used_in_application_layer`의 `com.kista..adapter.out.sse..` 와일드카드)
  adapter/out/persistence/ ← FcmDeviceTokenEntity + FcmDeviceTokenJpaRepository + FcmDeviceTokenPersistenceAdapter

**매매 알림 6종(TradingAlertNotifier/CycleEndedNotifier/CycleLifecycleNotifier/OrderCancelFailureNotifier/TradingReportNotifier/PrivacyAlertNotifier)은 이 모듈이 아니라 `com.kista.trading.notify`(trading-core) 소유** → `docs/agents/modules/trading.md` "com.kista.trading.notify" 참고.
