## com.kista.api.admin (`:api`)

Spring Modulith 모듈(CLOSED) — 관리자 조회·정정·재정렬·감사로그·앱오류로그 + 런타임 설정. "domain"·"port" NamedInterface, service·adapter internal.

- 오류 로그(`app_error_logs`) 저장 입구는 `AppErrorRaisedListener`(동기 `@EventListener`: 발행 지점이 트랜잭션 밖이라 AFTER_COMMIT은 이벤트를 버린다) 하나다 — trading-core 발행분도 같은 애플리케이션이라 직접 받는다(옛 `stream:app.error` 경로는 합병으로 삭제).
- trading-core 내부 API 요청·응답은 own-type 복제 없이 `com.kista.shared.contract`를 그대로 쓴다. `AccountSummaryResponse`는 id/userId/accountNo/broker/createdAt 5필드로 의도적으로 좁힌 계약이다(appKey/secretKey 등 평문 비밀 제외). 잠금: `InternalApiContractTest`, 게이트·잔존 own-type → `decisions.md`. 표지 예외(`AdminBrokerCredentialException` 등)는 HTTP 상태코드 복원용 own-type이다.
- admin은 trading-core `AccountPort`에 직접 의존하지 않는다 — 계좌 조회는 전부 `AccountQueryPort`(내부 API)로 위임한다.
- `AdminService.changeRole`은 자기 강등과 마지막 ADMIN 강등을 막고(`UserPort.countByRole`) `UserService.changeRole`에 위임하며 감사 로그만 남긴다. 저장·AT 무효화(`BlacklistPort`)는 user가 캡슐화한다.
- 내부 API 호출: 짧은 스케줄러 트리거는 `internalApiRestClient`, 정정류는 `internalApiWriteRestClient`. `PrivacyQueryHttpAdapter.createBase`는 409를 `AdminPrivacyTradeConflictException`으로 되돌린다(없으면 catch-all 500).
- `AdminTradingSchedulerController`(`/api/admin/scheduler/{open,close}`, 202)는 `@ConditionalOnWorkerRole` 게이팅 없이 kista-api role에서도 항상 노출한다. `web.AdminSchedulerController`(`/kbland-*`)는 role-게이팅이라 "admin 컨트롤러는 항상 존재" 전제가 깨져 web에 남아 있고 두 컨트롤러는 prefix만 공유한다.
- 감사·앱오류·런타임 설정 엔티티는 플랫폼 공통 테이블이라 `@Table(schema="public")`를 유지한다.

### 런타임 설정 API 규칙
- `GET /api/runtime-config`는 로그인 전 UI가 가입·계좌·전략 생성 정책을 읽는 공개 엔드포인트다. 동적 설정이므로 `Cache-Control: no-store`. trading-core 정책 조회 실패 시 `TradingPolicySettings.defaults()`로 강등한다(`RuntimeConfigService`).
- `GET|PUT /api/admin/settings`는 ADMIN 전용, 응답 모두 `no-store`. 조회는 엄격하다(trading-core 장애가 그대로 오류로 드러나야 기본값을 실제 설정으로 오인하지 않는다). PUT은 전체 설정을 검증한 뒤 한 번에 교체하며 부분 갱신 API가 아니다.
- 갱신 순서는 trading 정책 PUT → root 저장 → 감사 로그이고 트랜잭션은 없다(HTTP를 DB 트랜잭션에 두지 않기 위함). 후자가 실패하면 정책만 바뀌지만 PUT이 멱등이라 재시도하면 수렴한다.
- PUT의 brokers는 부분 맵을 허용한다(생략한 증권사 키는 현재 값 유지 — 신규 증권사를 모르는 구 관리자 화면 호환). MERITZ·DCA는 기본 비활성.
- 증권사 활성화·전략 생성 정책의 **적용 규칙**은 정책 소유자인 trading-core가 집행한다 → `modules/tradingcore/trading.md` "런타임 매매 정책 적용". admin은 `TradingPolicyHttpAdapter`로 조회·교체만 중계한다.
- 응답은 `NON_NULL` 직렬화 — 전략 유형에 적용되지 않는 field(예: PRIVACY의 `divisionCount`)는 null이 아니라 생략한다.
- `approvalRequired`가 `true → false`로 바뀌면 그 시점의 모든 PENDING 사용자를 `UserService.approve()`로 활성화한다. 갱신은 `RUNTIME_SETTINGS_UPDATE` 감사 로그를 남긴다.

### ADMIN 권한
- ADMIN seed는 `ADMIN_KAKAO_IDS`이고 로그인 시 idempotent하게 promote한다. `/api/admin/**`는 `hasRole("ADMIN")`이며 관리자 액션은 `audit_logs`에 영구 기록한다.

### 텔레그램 봇 명령 채널 (`adapter.in.telegram`)
- 알림 발송이 아니라 인바운드 관리자 명령(`/status`·`/history`·`/help`, 신규가입 인라인 버튼 `approve:{userId}`/`reject:{userId}`)이라 notify가 아니라 admin이 소유한다. 전송 쪽 규칙(`TelegramHttpClient`)은 `notify.md`.
- `POST /telegram/webhook`은 permitAll, 경로 불변. 관리자 채팅방(`telegram.chat-id`) 외 chatId는 무시한다.
- 승인·거절은 `approveUserByTelegram/rejectUserByTelegram`이 감사 로그를 쓴다(chatId에 연결된 ADMIN이 없으면 `admin_id=null` + payload `actor=TELEGRAM_BOT`). 이미 ACTIVE면 `User.AlreadyActiveException`을 잡아 안내 답장한다(중복 클릭에도 webhook 200).
