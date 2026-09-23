## com.kista.admin (`:api`)

com.kista.admin/    ← Spring Modulith 모듈(CLOSED) — 관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정. "domain"·"usecase"·"port" 3개 NamedInterface, service·adapter internal. event/schedule NamedInterface 없음. `adapter/out/aop/ErrorLogAspect`(`NotifyPort.notifyError` 인터셉트 → `AppErrorLogPort` 저장, notify는 포인트컷 문자열이라 컴파일 의존 없음)도 internal
  domain/model/       ← 관리자 read-model/own-type(AdminAccountView/AdminAnomalies/AdminBrokerCredentialException/AdminBrokerRateLimitException/AdminManualTradeCorrectionCommand/AdminOrderView/AdminReorderCommand/AdminReorderResult/AdminReorderTimingAvailability/AdminStats/AdminStrategySummary/AdminStrategyView/AdminTradeCorrectionResult/AdminPrivacy* 계열/AppErrorLog/AuditLog) + 런타임 설정 3(RuntimeSettings/BenchmarkSettings/BenchmarkFieldSettings) — "domain". `AdminAccountView`는 id/userId/accountNo(마스킹 전)/broker/createdAt 5필드로 의도적으로 좁힌 read model(appKey/secretKey/nickname/brokerAccountCode 제외 — 내부 API 응답에 평문 비밀값이 실리는 것을 막기 위함). own-type 목록·게이트 → `docs/agents/own-type-ledger.md`, shape 드리프트는 `OwnTypeContractTest`
  application/usecase/ ← AdminQueryUseCase/AdminReorderUseCase/AdminStrategyUseCase/AdminTradeCorrectionUseCase/AdminUserUseCase/AdminSettingsUseCase/RuntimeSettingsUseCase — "usecase"
  application/port/output/ ← AccountQueryPort/AppErrorLogPort/AuditLogPort/PrivacyQueryPort/RuntimeSettingsPort/TradingCommandPort/TradingQueryPort/TradingSchedulerCommandPort(trigger only 2메서드) — "port"
  application/service/ ← internal — AdminService/AdminQueryService/AdminReorderService/AdminStrategyService/AdminTradeCorrectionService/AdminPrivacyTradeService/RuntimeSettingsService. AdminQueryService는 계좌 목록/단건을 accountQueryPort(내부 API)로 위임하고 `accountPort`(trading-core AccountPort 직접 의존)는 getStats()의 countAll() 집계에만 잔존
  adapter/in/web/     ← internal(AdminTradingSchedulerController 예외) — 컨트롤러 10개(AdminAccount/AdminDashboard/AdminObservability/AdminPing/AdminPrivacyTrade/AdminSettings/AdminTrade/AdminUser/RuntimeConfig/ClientErrorLog) + AdminUserViews + dto/ 21종. `AdminTradingSchedulerController`(`/api/admin/scheduler/{open,close}`, 202)는 `scheduler.enabled` 게이팅 없이 kista-api role에서도 상시 노출 — `com.kista.web.AdminSchedulerController`(`/kbland-*`)와 prefix 공유하되 하위 경로가 겹치지 않는다. KbLand 트리거는 role-게이팅이라 admin에 두면 "admin 컨트롤러는 항상 존재" 전제가 깨져 web에 잔류
  adapter/out/internal/ ← AccountQueryHttpAdapter/PrivacyQueryHttpAdapter/TradingCommandHttpAdapter/TradingQueryHttpAdapter/TradingSchedulerCommandHttpAdapter — 위 5개 포트를 내부 API(RestClient)로 구현. Scheduler 어댑터는 짧은 호출이라 공용 `internalApiRestClient`를 쓰고 reorder류는 `internalApiWriteRestClient` 사용. `PrivacyQueryHttpAdapter.createBase`는 409를 `onStatus`로 되돌려 `AdminPrivacyTradeConflictException`으로 변환(없으면 catch-all 500)
  adapter/out/persistence/audit/    ← AuditLogEntity/AppErrorLogEntity + JpaRepository + PersistenceAdapter 6파일
  adapter/out/persistence/settings/ ← RuntimeSettingsEntity + JpaRepository + PersistenceAdapter 3파일
  ── `@Table(schema="public")`: AuditLogEntity/AppErrorLogEntity/RuntimeSettingsEntity는 플랫폼 공통 테이블이라 public 명시 유지

### 런타임 설정 API 규칙
- `GET /api/runtime-config` → 로그인 전 UI가 가입·계좌·전략 생성 정책을 조회하는 공개 엔드포인트. 동적 설정이므로 `Cache-Control: no-store` 유지
- `GET|PUT /api/admin/settings` → ADMIN 전용. PUT은 auth/brokers/strategies 전체 설정을 검증한 뒤 한 번에 교체하며 부분 갱신 API로 취급하지 않음. 조회·갱신 응답 모두 `Cache-Control: no-store` 유지
- `brokers.<broker>.enabled=false`이면 해당 증권사의 신규 계좌 등록과 연결 테스트를 외부 API 호출 전에 400으로 차단. 기존 계좌의 조회·수정·매매는 영향받지 않음
- `StrategyService.register()`는 신규 전략에만 `strategies.<type>` 생성 정책을 적용: `enabled=false`면 400으로 차단하고, ticker·INFINITE divisionCount·VR recurringMode/bandWidth/intervalWeeks의 생략 기본값과 허용/고정값을 검증. 기존 전략 수정·실행에는 소급 적용하지 않음
- `RegisterStrategyCommand.divisionCount=0`은 INFINITE 신규 등록의 미입력 sentinel이며 런타임 기본값으로 치환. VR `recurringMode`는 `recurringAmount` 부호(DEPOSIT/HOLD/WITHDRAW)로만 검증하고 금액 크기는 기존 VR 자산 규칙에 맡김. `recurringMode.customizable=false` 설정은 기본값과 유일한 허용값이 모두 `HOLD`여야 함
- 런타임 설정 응답은 `NON_NULL` 직렬화 사용. 전략 유형에 적용되지 않는 field(예: PRIVACY의 `divisionCount`)는 `null`로 내리지 않고 JSON에서 생략
- `approvalRequired` 값이 `true → false`로 바뀌면 그 시점의 모든 PENDING 사용자를 기존 `UserUseCase.approve()` 흐름으로 활성화. 설정 갱신은 `RUNTIME_SETTINGS_UPDATE` 감사 로그 기록
