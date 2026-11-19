## com.kista.tradingcore.web (`:trading-core`)

com.kista.tradingcore.web/ ← Spring Modulith 앱셸 모듈. root `com.kista.api.web`과 대칭. `@ApplicationModule(Type.CLOSED)` — NamedInterface **0개**. 아무 모듈도 tradingcore.web을 참조하지 않는 sink라 순환에 참여할 수 없다(`HexagonalArchitectureTest.web_must_stay_pure_inbound_sink`가 `com.kista.api.web..`과 함께 `application.service`/`adapter.out` 의존 금지로 강제). trading-core 프로세스 전체(부팅·전역 web)에 걸치는 관심사만 두고, 특정 모듈 소유 코드는 소유 모듈로 보낼 것
  모듈 ID는 `trading-web`(api.web의 `web`과 겹치지 않게 — 한 애플리케이션 Modulith 모델). 합병 뒤 클래스가 없다 — 오류 보고는 `api.web` `ApiExceptionReporter` 하나가 맡는다(옛 `TradingExceptionReporter`·`stream:app.error` 경로 삭제)
  TradingApplication ← 운영 진입점이 아니다. `trading-core/src/test`의 테스트 전용 기동 클래스(`@SpringBootApplication @ConfigurationPropertiesScan @EnableScheduling @Modulithic(additionalPackages = "com.kista.shared")`, `main` 없음)로, trading-core 슬라이스·`@SpringBootTest`가 상향 탐색으로 찾는다. 운영 진입점은 root `ApiApplication`(`scanBasePackages`에 `com.kista.tradingcore` 포함)

예외 매핑 규칙(모듈 기여 SSOT·ProblemDetail 계약·`ErrorCode`)은 `docs/agents/modules/api/web.md` "예외 응답 자동 처리 (공용 advice)"와 동일하게 적용한다.
