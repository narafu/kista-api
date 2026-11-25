## com.kista.api.user (`:api`)

Spring Modulith 모듈(CLOSED) — 가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth. "domain"(domain.model+domain.auth 병합)·"port"·"event" NamedInterface + `UserService`(타입 단위 "api"), 나머지 service·adapter·config internal.

- `NotificationChannel`은 `com.kista.shared.kernel` 소유(notify 라우팅과 공유). `User.DEFAULT_CHANNEL = NotificationChannel.NONE`을 서비스·컨트롤러에서 하드코딩하지 않는다.
- `UserService.changeRole`은 역할 저장과 `BlacklistPort.markRoleChanged`(기존 AT 무효화)를 user가 캡슐화한다. 자기 강등·마지막 ADMIN 검증과 감사 로그는 admin `AdminService` 몫이다.
- `ApprovalPolicyPort`(가입 승인 필요 여부 FOR UPDATE 락 조회)는 user가 정의하고 admin이 구현하는 포트 역전이다. `ApprovalRequirementDisabledEvent`는 admin이 발행하고 user `ApprovalRequirementDisabledListener`(AFTER_COMMIT, REQUIRES_NEW)가 PENDING 사용자를 `UserService.approve`로 전원 승인한다 — admin이 순수 downstream sink라 이벤트 소유를 user에 둔다.
- `UserPort.findIdsByStatus`는 전 사용자 순회용이다(finance `UserModuleFinanceMemberAdapter`가 `FinanceMemberPort.activeMemberIds()`로 위임).
- `UserCascadeDeleter`(`deleteMe`/`AdminService.deleteUser` 공통)는 cascade를 전부 `UserDeletedEvent`로 처리한다(직접 포트 호출 0 — user↔trading·finance·account 순환 제거, EPR 재시도 보장). trading-core 쪽 cascade는 Redis Stream 경유 AFTER_COMMIT 비동기라 탈퇴 HTTP 응답 시점엔 계좌 소프트 삭제가 미완료일 수 있다(최종적 일관성).
- `AdminSeedPromoter`는 별도 빈이다 — `UserService.login()`이 `NOT_SUPPORTED`라 그 안에서 저장·발행하면 복제본 동기화 이벤트가 트랜잭션 없이 나가 리스너 예외가 로그인까지 전파되고 재시도해도 promote가 no-op이라 복제본이 영영 안 생긴다.
- `UserNotifyProfilePublisher`가 `UserNotifyProfileChangedEvent` 발행 SSOT다(`publishStatusChanged`/`publishSettingsChanged`). 저장 없는 재발행 `resyncNotifyProfile`은 로컬 `DevAuthController`가 이미 ACTIVE인 dev·admin·E2E 유저의 복제본 유실을 복구할 때 쓴다.
- `UserDeletedEvent`·`UserNotifyProfileChangedEvent`는 같은 애플리케이션의 trading-core 리스너(`TradingUserCascadeListener`·`UserNotifyProfileSyncListener`)가 직접 받는다 — 리스너 실패는 publication FAILED로 남아 trading `UserEventResubmitScheduler`(worker)가 재발행한다. 리스너가 `fallbackExecution=true`라 발행 지점은 전부 트랜잭션 안이어야 한다(밖이면 EPR 행 없이 인라인 실행). 스냅샷 시각 `occurredAt`은 `UserNotifyProfilePublisher`가 찍는다(trading-core 버전 가드).
- `JwtAuthFilter`/`InternalTokenAuthFilter`/`SecurityConfig`/`JwtDecoderConfig`는 `:shared`의 `platform.security`에 있다 → `modules/shared/platform.md`. `RedisBlacklistAdapter`는 platform `RedisTokenBlacklistReader`를 상속한다(읽기 3종은 베이스, 쓰기 3종만 여기).

### Refresh Token 회전(RTR)
- 후계 RT는 결정적이다: `RefreshTokenSuccessorDeriver`가 `base64url(HMAC(HMAC(aesKey, "kista-rt-successor-v1"), rawRT))`로 유도한다. 같은 RT를 재제시(응답 유실 재시도·동시 경쟁 패자)하면 회전 후 `AT_TTL`(1일) 안일 때 같은 후계를 반환하고 재-insert하지 않는다. 넘으면 이 기기만 401(전체 폐기 없음, 탈취 RT 노출 상한).
- 후계를 이미 쓴 뒤(`rotated_at` 있음) 회전 RT를 재제시하면 재사용 공격으로 보고 사용자 전체 세션을 폐기한다. 단 회전 후 `RT_GRACE`(60초) 안이면 연속 회전으로 보고 체인(최대 5단계)을 따라가 첫 미사용 후계를 반환한다(체인 도중 행이 없으면 이 기기만 401). 후계가 없으면(로그아웃·재로그인으로 삭제) 이 기기만 401이고 폐기하지 않는다.
- 회전 행은 `expires_at`까지 보존한다(재시도·탐지용 — 회전 RT 정리 스케쥴러 없음). 키가 AES 키 파생이라 AES 키를 교체하면 교체 전 회전 RT의 재시도는 이 기기 401이 된다.

### 상태 종속 민감 필드 마스킹 (rejectReason 사례)
`User.rejectReason`은 REJECTED에서만 의미가 있어 `UserResponse.from()`이 `status() == REJECTED`일 때만 노출하고 그 외는 `null`을 강제한다. 특정 상태에서만 유효한 민감 필드는 DB엔 보존하고 응답 DTO `from()`에서 상태 조건부로 마스킹한다.

- 재신청 쿨다운 SSOT는 `User`(`reapplyAvailableAt`·`requireReapplyAllowed`, PENDING 1h·REJECTED 24h). `UserResponse.reapplyAvailableAt`로 ui에 내려간다(승인 불필요면 null)
