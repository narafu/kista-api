## com.kista.user (`:api`)

com.kista.user/     ← Spring Modulith 모듈(CLOSED) — 가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth. "domain"(domain.model+domain.auth 병합)·"usecase"·"port"·"event" 4개 NamedInterface, service·adapter·config internal
  domain/model/       ← User/UserSettings/UserSummary(id/nickname/status/role/createdAt 요약 read-model — 관리자 화면이 소비). `NotificationChannel`은 `com.kista.sharedkernel` 소유(notify 채널 라우팅과 공유 — `@Enumerated(STRING)` 컬럼 상수명 불변). `User.DEFAULT_CHANNEL = NotificationChannel.NONE`(domain 상수) — 서비스/컨트롤러 하드코딩 금지
  domain/auth/        ← RefreshToken/TokenRefreshResult/TokenConstants/InvalidRefreshTokenException
  application/usecase/ ← BlacklistUseCase/GetUserSettingsQuery/TokenUseCase/UserProfileUseCase/UserSettingsUseCase/UserUseCase(`changeRole(userId, role)` — 역할 저장 + `BlacklistPort.markRoleChanged`로 기존 AT 무효화를 user가 캡슐화. 자기 강등·마지막 ADMIN 검증과 감사 로그는 admin `AdminService`가 담당)
  application/port/output/ ← UserSummaryPort(읽기 투영 — admin 화면 + finance 닉네임 조회)/ApprovalPolicyPort/BlacklistPort/KakaoOAuthPort/RefreshTokenPort/TelegramBotInfoPort/UserPort(`findIdsByStatus(status)` — 전 사용자 순회 후 개별 처리하는 소비자용 id 목록 조회, finance `UserModuleFinanceMemberAdapter`가 `FinanceMemberPort.activeMemberIds()`로 위임해 리마인더에 제공)/UserSettingsPort/ActiveStrategyCountPort. `ApprovalPolicyPort`(가입 승인 필요 여부 FOR UPDATE 락 조회)는 user가 정의하고 admin이 구현하는 포트 역전
  application/event/  ← NewUserRegisteredEvent/UserApprovedEvent/UserRejectedEvent/UserReappliedEvent — notify 등이 구독. `ApprovalRequirementDisabledEvent`는 admin `RuntimeSettingsService`가 발행하고 user `ApprovalRequirementDisabledListener`(AFTER_COMMIT, REQUIRES_NEW)가 구독해 PENDING 사용자를 `UserUseCase.approve`로 전원 승인 — admin이 순수 downstream sink라 이벤트 소유를 user에 둔다(`UserDeletedEvent`는 sharedkernel 소유)
  application/service/ ← internal — TokenService/UserCascadeDeleter/UserProfileService/UserService/UserSettingsService/ApprovalRequirementDisabledListener/AdminSeedPromoter(ADMIN seed promote 전용 트랜잭션 경계 — `UserService.login()`이 `NOT_SUPPORTED`라 그 안에서 직접 저장·발행하면 복제본 동기화 이벤트가 트랜잭션 없이 나가므로 별도 빈으로 분리)(`BlacklistPort extends TokenBlacklistPort`로 순수 위임이라 별도 서비스 없음). `UserCascadeDeleter`(`UserService.deleteMe`/`AdminService.deleteUser` 공통 진입점)는 trading·finance·account·전략 설정 cascade를 전부 `UserDeletedEvent` 발행으로 처리(직접 포트 호출 0, 각 모듈이 자체 리스너로 정리, EPR 재시도 보장). 계좌 cascade는 `AccountUserCascadeListener`(AFTER_COMMIT) 비동기라 탈퇴 HTTP 응답 시점엔 계좌 소프트 삭제가 미완료일 수 있다(최종적 일관성). `UserNotifyProfilePublisher`(package-private)가 상태·설정 변경 시 `UserNotifyProfileChangedEvent`를 발행하는 SSOT — `publishStatusChanged`는 `UserService` 4곳·`UserProfileService` 2곳·`AdminSeedPromoter` 1곳, `publishSettingsChanged`는 `UserSettingsService` 2곳이 호출
  adapter/in/web/     ← internal — AuthController/DevAuthController(local 전용)/SettingsController + dto/
  adapter/in/web/security/ ← internal — JwtIssuerService/OpenApiConfig/RefreshTokenCookieHelper만 남는다(`JwtAuthFilter`/`InternalTokenAuthFilter`/`SecurityConfig`/`JwtDecoderConfig`는 `:shared`의 `com.kista.platform.security`로 이전 → `docs/agents/modules/platform.md`)
  adapter/in/schedule/ ← RefreshTokenCleanupScheduler(platform `SchedulerJobRunner` 재사용)
  adapter/out/kakao/  ← KakaoOAuthAdapter/KakaoConfig/KakaoProperties
  adapter/out/internal/ ← ActiveStrategyCountAdapter(`ActiveStrategyCountPort` 구현 — trading-core 내부 API `GET /api/internal/trading/active-strategy-count`를 호출하는 순수 HTTP 어댑터, 타입 의존 없음. 인바운드 패키지(`adapter.in`·`web`)는 RestClient 의존 금지라 HTTP 호출은 이 out 어댑터에 둔다)
  adapter/out/redis/  ← RedisBlacklistAdapter(platform `RedisTokenBlacklistReader` 상속 — 읽기 3종은 베이스, 쓰기 3종만 이 클래스) + UserEventStreamPublisher(trading-core 복제본 동기화용 Redis Stream 발행)
  adapter/out/persistence/user/    ← UserEntity + UserJpaRepository + UserPersistenceAdapter, UserSummaryAdapter(`UserSummaryPort` 구현)
  adapter/out/persistence/auth/    ← RefreshTokenEntity + JpaRepository + PersistenceAdapter
  adapter/out/persistence/settings/ ← UserSettingsJpaEntity/UserNotificationPrefJpaEntity/UserNotificationPrefId + JpaRepository + UserSettingsPersistenceAdapter
  config/             ← AdminBootstrapProperties(`admin.kakao-ids` 바인딩)/AdminConfig

### 잔고검증 토글 (UserSettings.balanceCheckEnabled)
`UserSettings` aggregate가 이 모듈 소유지만, 규칙이 실제로 부딪히는 곳(`StrategyService` 시드 등록/수정 한도 검증)은 trading이다 → `docs/agents/modules/trading.md` "잔고검증 토글" 참고.
