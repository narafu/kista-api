## com.kista.web (`:api`)

com.kista.web/       ← Spring Modulith 앱셸 모듈. `@ApplicationModule(Type.CLOSED)` — NamedInterface **0개**. 아무 모듈도 web을 참조하지 않는 sink라 순환에 참여할 수 없다(`HexagonalArchitectureTest.web_must_stay_pure_inbound_sink`가 `web → ..application.service../..adapter.out..` 의존 금지로 강제). **신규 파일이 단일 모듈만 소비하거나 모듈 의존이 없으면 web에 두지 말고 소유 모듈로 보낼 것** — 여기엔 진짜 다중 모듈 fan-out과 순환 회피용 포트 구현체만
  trading/             ← ActiveStrategyCountAdapter(`user.ActiveStrategyCountPort` 구현) — trading-core 내부 API(`GET /api/internal/trading/active-strategy-count`)를 호출하는 순수 HTTP 어댑터, 타입 의존 없음(위치만 과거 잔재)
  GlobalExceptionHandler ← root 소유 컨트롤러(admin/user/finance/stats/market/web) 전용 범용 예외(`SecurityException`→403/`NoSuchElementException`→404/`IllegalArgumentException`→400 등) 매핑(`@RestControllerAdvice`). trading-core 예외 6종은 `TradingExceptionHandler`가 전담. `KisApiException`/`TossApiException`은 root/admin 소유 `AppErrorLogPort` 의존 때문에 root 잔류
  MetaController        ← `GET /api/meta` — enum 메타(label/description) 단일 번들. finance(`domain.model`)+matching(`"kernel"` — 내부 API 경유)+sharedkernel fan-out이라 단일 모듈 이관 불가, UI enum 리터럴 하드코딩 방지
  AdminSchedulerController ← stats "schedule" 소비 — KbLand 스케쥴러 수동 트리거(`/api/admin/scheduler/kbland-*`). 클래스 레벨 `@ConditionalOnProperty(scheduler.enabled)`라 kista-api role에선 빈 미등록(오라우팅 시 404)
  dto/                 ← MetaBundle·EnumMeta·StrategyTypeMeta·TickerMeta·StrategyCapability(matching 내부 API 응답 own-type)
