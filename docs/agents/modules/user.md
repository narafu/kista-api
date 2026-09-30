## com.kista.user (`:api`)

com.kista.user/     ← Spring Modulith 모듈(CLOSED) — 가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth. "domain"(domain.model+domain.auth 병합)·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter·config internal
  domain/model/       ← User/UserSettings/UserSummary(id/nickname/status/role/createdAt 요약 read-model — 관리자 화면이 소비, 2026-09-30 `AdminUserView`에서 개명). `NotificationChannel`은 2026-09-30 `com.kista.sharedkernel`로 승격(notify 채널 라우팅과 공유 — 상수명 byte-identical, `@Enumerated(STRING)` 컬럼 불변). `User.DEFAULT_CHANNEL = NotificationChannel.NONE`(domain 상수) — 서비스/컨트롤러 하드코딩 금지
  domain/auth/        ← RefreshToken/TokenRefreshResult/TokenConstants/InvalidRefreshTokenException
  application/usecase/ ← BlacklistUseCase/GetUserSettingsQuery/TokenUseCase/UserProfileUseCase/UserSettingsUseCase/UserUseCase(`changeRole(userId, role)` — 역할 저장 + `BlacklistPort.markRoleChanged`로 기존 AT 무효화를 user가 캡슐화. 자기 강등·마지막 ADMIN 검증과 감사 로그는 admin `AdminService`가 담당)
  application/port/output/ ← UserSummaryPort(읽기 투영 — admin 화면 + finance 닉네임 조회)/ApprovalPolicyPort/BlacklistPort/KakaoOAuthPort/RefreshTokenPort/TelegramBotInfoPort/UserPort(`findIdsByStatus(status)` — 전 사용자 순회 후 개별 처리하는 소비자용 id 목록 조회, finance `UserModuleFinanceMemberAdapter`가 `FinanceMemberPort.activeMemberIds()`로 위임해 리마인더에 제공)/UserSettingsPort/ActiveStrategyCountPort. `ApprovalPolicyPort`(가입 승인 필요 여부 FOR UPDATE 락 조회)는 user가 정의하고 admin이 구현하는 포트 역전
  application/event/  ← NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/UserReappliedEvent — notify 등이 구독(`UserDeletedEvent`는 sharedkernel 소유)
  application/service/ ← internal — TokenService/UserCascadeDeleter/UserProfileService/UserService/UserSettingsService(`BlacklistPort extends TokenBlacklistPort`로 순수 위임이라 별도 서비스 없음). `UserCascadeDeleter`(`UserService.deleteMe`/`AdminService.deleteUser` 공통 진입점)는 trading·finance·account·전략 설정 cascade를 전부 `UserDeletedEvent` 발행으로 처리(직접 포트 호출 0, 각 모듈이 자체 리스너로 정리, EPR 재시도 보장). 계좌 cascade는 `AccountUserCascadeListener`(AFTER_COMMIT) 비동기라 탈퇴 HTTP 응답 시점엔 계좌 소프트 삭제가 미완료일 수 있다(최종적 일관성). `UserNotifyProfilePublisher`(package-private)가 상태·설정 변경 시 `UserNotifyProfileChangedEvent`를 발행하는 SSOT — `UserService` 상태 전이 5곳과 `UserSettingsService` 알림/잔고검증 변경 2곳이 호출
  adapter/in/web/     ← internal — AuthController/DevAuthController(local 전용)/SettingsController + dto/
  adapter/in/web/security/ ← internal — JwtIssuerService/OpenApiConfig/RefreshTokenCookieHelper만 남는다(`JwtAuthFilter`/`InternalTokenAuthFilter`/`SecurityConfig`/`JwtDecoderConfig`는 `:shared`의 `com.kista.platform.security`로 이전 → `docs/agents/modules/platform.md`)
  adapter/in/schedule/ ← RefreshTokenCleanupScheduler(platform `SchedulerJobRunner` 재사용)
  adapter/out/kakao/  ← KakaoOAuthAdapter/KakaoConfig/KakaoProperties
  adapter/out/internal/ ← ActiveStrategyCountAdapter(`ActiveStrategyCountPort` 구현 — trading-core 내부 API `GET /api/internal/trading/active-strategy-count`를 호출하는 순수 HTTP 어댑터, 타입 의존 없음. 과거 `com.kista.web.trading`에 있던 것을 2026-09-30 여기로 이전 — 인바운드 패키지의 RestClient 금지 규칙 때문)
  adapter/out/redis/  ← RedisBlacklistAdapter(platform `RedisTokenBlacklistReader` 상속 — 읽기 3종은 베이스, 쓰기 3종만 이 클래스) + UserEventStreamPublisher(trading-core 복제본 동기화용 Redis Stream 발행)
  adapter/out/persistence/user/    ← UserEntity + UserJpaRepository + UserPersistenceAdapter, UserSummaryAdapter(`UserSummaryPort` 구현)
  adapter/out/persistence/auth/    ← RefreshTokenEntity + JpaRepository + PersistenceAdapter
  adapter/out/persistence/settings/ ← UserSettingsJpaEntity/UserNotificationPrefJpaEntity/UserNotificationPrefId + JpaRepository + UserSettingsPersistenceAdapter
  config/             ← AdminBootstrapProperties(`admin.kakao-ids` 바인딩)/AdminConfig

### 잔고검증 토글 (UserSettings.balanceCheckEnabled)
`UserSettings` aggregate가 이 모듈 소유지만, 규칙이 실제로 부딪히는 곳(`StrategyService` 시드 등록/수정 한도 검증)은 trading이다 → `docs/agents/modules/trading.md` "잔고검증 토글" 참고.
