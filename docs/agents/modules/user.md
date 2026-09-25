## com.kista.user (`:api`)

com.kista.user/     ← Spring Modulith 모듈(CLOSED) — 가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth. "domain"(domain.model+domain.auth 병합)·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter·config internal
  domain/model/       ← User/UserSettings/AdminUserView/NotificationChannel. `User.DEFAULT_CHANNEL = NotificationChannel.NONE`(domain 상수) — 서비스/컨트롤러 하드코딩 금지
  domain/auth/        ← RefreshToken/TokenRefreshResult/TokenConstants/InvalidRefreshTokenException
  application/usecase/ ← BlacklistUseCase/GetUserSettingsQuery/TokenUseCase/UserProfileUseCase/UserSettingsUseCase/UserUseCase
  application/port/output/ ← AdminUserViewPort/ApprovalPolicyPort/BlacklistPort/KakaoOAuthPort/RefreshTokenPort/TelegramBotInfoPort/UserPort/UserSettingsPort/ActiveStrategyCountPort. `ApprovalPolicyPort`(가입 승인 필요 여부 FOR UPDATE 락 조회)는 user가 정의하고 admin이 구현하는 포트 역전
  application/event/  ← NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/UserReappliedEvent — notify 등이 구독(`UserDeletedEvent`는 sharedkernel 소유)
  application/service/ ← internal — BlacklistService/TokenService/UserCascadeDeleter/UserProfileService/UserService/UserSettingsService. `UserCascadeDeleter`(`UserService.deleteMe`/`AdminService.deleteUser` 공통 진입점)는 trading·finance·account·전략 설정 cascade를 전부 `UserDeletedEvent` 발행으로 처리(직접 포트 호출 0, 각 모듈이 자체 리스너로 정리, EPR 재시도 보장). 계좌 cascade는 `AccountUserCascadeListener`(AFTER_COMMIT) 비동기라 탈퇴 HTTP 응답 시점엔 계좌 소프트 삭제가 미완료일 수 있다(최종적 일관성). `UserNotifyProfilePublisher`(package-private)가 상태·설정 변경 시 `UserNotifyProfileChangedEvent`를 발행하는 SSOT — `UserService` 상태 전이 5곳과 `UserSettingsService` 알림/잔고검증 변경 2곳이 호출
  adapter/in/web/     ← internal — AuthController/DevAuthController(local 전용)/SettingsController + dto/
  adapter/in/web/security/ ← internal — JwtAuthFilter/InternalTokenAuthFilter/SecurityConfig/JwtDecoderConfig/JwtIssuerService/OpenApiConfig/RefreshTokenCookieHelper
  adapter/in/schedule/ ← RefreshTokenCleanupScheduler(platform `SchedulerJobRunner` 재사용)
  adapter/out/kakao/  ← KakaoOAuthAdapter/KakaoConfig/KakaoProperties
  adapter/out/redis/  ← RedisBlacklistAdapter + UserEventStreamPublisher(trading-core 복제본 동기화용 Redis Stream 발행)
  adapter/out/persistence/user/    ← UserEntity + UserJpaRepository + UserPersistenceAdapter, AdminUserViewAdapter
  adapter/out/persistence/auth/    ← RefreshTokenEntity + JpaRepository + PersistenceAdapter
  adapter/out/persistence/settings/ ← UserSettingsJpaEntity/UserNotificationPrefJpaEntity/UserNotificationPrefId + JpaRepository + UserSettingsPersistenceAdapter
  config/             ← AdminBootstrapProperties(`admin.kakao-ids` 바인딩)/AdminConfig

### 잔고검증 토글 (UserSettings.balanceCheckEnabled)
`UserSettings` aggregate가 이 모듈 소유지만, 규칙이 실제로 부딪히는 곳(`StrategyService` 시드 등록/수정 한도 검증)은 trading이다 → `docs/agents/modules/trading.md` "잔고검증 토글" 참고.
