## com.kista.api.user (`:api`)

Spring Modulith 모듈(CLOSED) — 가입·승인·프로필·설정 + JWT/RefreshToken/블랙리스트/카카오 OAuth. "domain"(domain.model+domain.auth 병합)·"port"·"event" NamedInterface + `UserService`(타입 단위 "api"), 나머지 service·adapter·config internal.

- `NotificationChannel`은 `com.kista.shared.kernel` 소유(notify 라우팅과 공유). `User.DEFAULT_CHANNEL = NotificationChannel.NONE`을 서비스·컨트롤러에서 하드코딩하지 않는다.
- `UserService.changeRole`은 역할 저장과 `BlacklistPort.markRoleChanged`(기존 AT 무효화)를 user가 캡슐화한다. 자기 강등·마지막 ADMIN 검증과 감사 로그는 admin `AdminService` 몫이다.
- `ApprovalPolicyPort`(가입 승인 필요 여부 FOR UPDATE 락 조회)는 user가 정의하고 admin이 구현하는 포트 역전이다. `ApprovalRequirementDisabledEvent`는 admin이 발행하고 user `ApprovalRequirementDisabledListener`(AFTER_COMMIT, REQUIRES_NEW)가 PENDING 사용자를 `UserService.approve`로 전원 승인한다 — admin이 순수 downstream sink라 이벤트 소유를 user에 둔다.
- `UserPort.findIdsByStatus`는 전 사용자 순회용이다(finance `UserModuleFinanceMemberAdapter`가 `FinanceMemberPort.activeMemberIds()`로 위임).
- `UserCascadeDeleter`(`deleteMe`/`AdminService.deleteUser` 공통)는 cascade를 전부 `UserDeletedEvent`로 처리한다(직접 포트 호출 0 — user↔trading·finance·account 순환 제거, EPR 재시도 보장). trading-core 쪽 cascade는 Redis Stream 경유 AFTER_COMMIT 비동기라 탈퇴 HTTP 응답 시점엔 계좌 소프트 삭제가 미완료일 수 있다(최종적 일관성).
- `AdminSeedPromoter`는 별도 빈이다 — `UserService.login()`이 `NOT_SUPPORTED`라 그 안에서 저장·발행하면 복제본 동기화 이벤트가 트랜잭션 없이 나가 리스너 예외가 로그인까지 전파되고 재시도해도 promote가 no-op이라 복제본이 영영 안 생긴다.
- `UserNotifyProfilePublisher`가 `UserNotifyProfileChangedEvent` 발행 SSOT다(`publishStatusChanged`/`publishSettingsChanged`). 저장 없는 재발행 `resyncNotifyProfile`은 로컬 `DevAuthController`가 이미 ACTIVE인 dev·admin·E2E 유저의 복제본 유실을 복구할 때 쓴다.
- `UserEventStreamPublisher`(trading-core 복제본 동기화 Redis Stream)는 XADD 실패를 삼키지 않는다 — 예외가 나야 publication이 FAILED로 남아 `web.UserEventResubmitScheduler`가 재발행한다(삼키면 완료로 기록돼 `user.deleted`가 영구 유실). 그래서 발행 지점은 전부 트랜잭션 안이어야 한다. 스냅샷 시각 `occurredAt`은 `UserNotifyProfilePublisher`가 찍는다(trading-core 버전 가드).
- `JwtAuthFilter`/`InternalTokenAuthFilter`/`SecurityConfig`/`JwtDecoderConfig`는 `:shared`의 `platform.security`에 있다 → `modules/shared/platform.md`. `RedisBlacklistAdapter`는 platform `RedisTokenBlacklistReader`를 상속한다(읽기 3종은 베이스, 쓰기 3종만 여기).

### 상태 종속 민감 필드 마스킹 (rejectReason 사례)
`User.rejectReason`은 REJECTED에서만 의미가 있어 `UserResponse.from()`이 `status() == REJECTED`일 때만 노출하고 그 외는 `null`을 강제한다. 특정 상태에서만 유효한 민감 필드는 DB엔 보존하고 응답 DTO `from()`에서 상태 조건부로 마스킹한다.
