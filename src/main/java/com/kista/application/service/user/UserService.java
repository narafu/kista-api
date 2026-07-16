package com.kista.application.service.user;

import com.kista.application.config.AdminBootstrapProperties;
import com.kista.application.event.NewUserRegisteredEvent;
import com.kista.application.event.UserApprovedEvent;
import com.kista.application.event.UserRejectedEvent;
import com.kista.application.event.UserReappliedEvent;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class UserService implements UserUseCase {

    private static final Duration REJECT_BLACKLIST_TTL = Duration.ofMinutes(15); // 거절 직후 AT 차단 기간
    private static final long PENDING_REAPPLY_COOLDOWN_HOURS = 1;               // PENDING 상태 재신청 쿨다운 (시간)
    private static final long REJECTED_REAPPLY_COOLDOWN_HOURS = 24;             // REJECTED 상태 재신청 쿨다운 (시간)

    private final UserPort userPort;
    private final UserCascadeDeleter userCascadeDeleter;
    private final ApplicationEventPublisher eventPublisher; // 트랜잭션 커밋 후 이벤트 발행용
    private final AdminBootstrapProperties bootstrapProps; // ADMIN seed 목록
    private final KakaoOAuthPort kakaoOAuthPort;           // 카카오 OAuth 토큰 교환 + 사용자 정보 조회
    private final BlacklistPort blacklistPort;              // 거절 즉시 AT 차단
    private final RefreshTokenPort refreshTokenPort;        // RT 삭제 (탈퇴/거절 시 전체 세션 종료)
    private final RuntimeSettingsPort runtimeSettingsPort;  // 가입 승인 런타임 설정 조회
    private final ObjectProvider<UserUseCase> userUseCaseProvider; // OAuth 이후 트랜잭션 프록시 재진입

    @Override
    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userPort.findByIdOrThrow(id);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findUserIdByTelegramChatId(String chatId) {
        return userPort.findByTelegramChatId(chatId).map(User::id);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // OAuth HTTP 호출 + 신규 사용자 저장 — 트랜잭션 불필요 (단건 저장은 JPA auto-commit)
    public User login(String code, String redirectUri) {
        // 인가 코드를 카카오 액세스 토큰으로 교환
        String kakaoAccessToken = kakaoOAuthPort.exchangeCodeForToken(code, redirectUri);
        // 카카오 액세스 토큰으로 사용자 정보 조회
        KakaoOAuthPort.KakaoUserInfo kakaoUser = kakaoOAuthPort.getUserInfo(kakaoAccessToken);

        User user;
        try {
            // 신규 사용자 등록 시도 (기존 사용자면 그대로 반환)
            // 외부 OAuth 호출이 끝난 뒤 프록시를 통해 가입 트랜잭션만 시작한다.
            user = userUseCaseProvider.getObject()
                    .register(kakaoUser.kakaoId(), kakaoUser.nickname(), UUID.randomUUID());
        } catch (DataIntegrityViolationException e) {
            // 동시 가입 경쟁 조건 → 기존 사용자 직접 조회 (self-invocation 방지)
            log.debug("중복 가입 시도 → 기존 사용자 반환: kakaoId={}", kakaoUser.kakaoId());
            user = userPort.findByKakaoId(kakaoUser.kakaoId())
                    .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + kakaoUser.kakaoId()));
        }
        // ADMIN seed인데 아직 USER이면 idempotent promote (seed 목록 사후 추가 케이스 포함)
        if (bootstrapProps.isAdmin(user.kakaoId()) && user.role() != User.UserRole.ADMIN) {
            log.info("기존 사용자 ADMIN promote: kakaoId={}", user.kakaoId());
            user = userPort.save(user.withStatus(User.UserStatus.ACTIVE).withRole(User.UserRole.ADMIN));
        }
        return user;
    }

    @Override
    public User register(String kakaoId, String nickname, UUID userId) {
        // 기존 사용자면 반환, 신규이면 역할/상태 결정 후 저장 + 관리자 알림
        return userPort.findByKakaoId(kakaoId).orElseGet(() -> {
            // ADMIN seed 여부에 따라 역할/상태 결정
            boolean isAdminSeed = bootstrapProps.isAdmin(kakaoId);
            User.UserRole role = isAdminSeed ? User.UserRole.ADMIN : User.UserRole.USER;
            // 관리자는 항상 활성화하고 일반 사용자는 현재 승인 설정에 따라 초기 상태를 결정한다.
            // 잠금 조회 필수 — 관리자의 승인설정 OFF 전환(RuntimeSettingsService.updateSettings)과 같은 행을
            // 잠가 직렬화해야, 전환 중 INSERT된 PENDING 사용자가 전환 시점의 일괄 활성화에서 누락되지 않는다
            // (RuntimeSettingsApprovalConcurrencyIT로 검증됨). 처리량보다 "PENDING 누락 없음" 보장이 우선.
            boolean approvalRequired = runtimeSettingsPort.loadForUpdate().approvalRequired();
            User.UserStatus status = isAdminSeed || !approvalRequired
                    ? User.UserStatus.ACTIVE
                    : User.UserStatus.PENDING;
            User newUser = new User(userId, kakaoId, nickname, status, role,
                    null, null, null, null, null, User.DEFAULT_CHANNEL);
            User saved = userPort.save(newUser);
            log.info("신규 사용자 등록: kakaoId={}, userId={}", kakaoId, userId);
            // 트랜잭션 커밋 성공 후에만 알림 발송 (race condition 시 롤백된 트랜잭션은 알림 미발송)
            eventPublisher.publishEvent(new NewUserRegisteredEvent(saved));
            return saved;
        });
    }

    @Override
    public void approve(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        User updated = user.withStatus(User.UserStatus.ACTIVE);
        userPort.save(updated);
        log.info("사용자 승인: userId={}", userId);
        // 커밋 성공 후 알림 + SSE — 롤백 시 알림 미발송
        eventPublisher.publishEvent(new UserApprovedEvent(updated));
    }

    @Override
    public void reject(UUID userId, String reason) {
        User user = userPort.findByIdOrThrow(userId);
        // reason 정규화: trim 후 blank -> null
        String normalizedReason = (reason == null || reason.isBlank()) ? null : reason.trim();
        // REJECTED 전환 + 사유 세팅 + 24h 카운트다운 시작 (lastReappliedAt = now)
        User updated = user.withRejection(normalizedReason);
        userPort.save(updated);
        log.info("사용자 거절: userId={}", userId);
        // 커밋 성공 후 알림 + SSE — 롤백 시 알림 미발송
        eventPublisher.publishEvent(new UserRejectedEvent(updated));
        blacklistPort.add(userId, REJECT_BLACKLIST_TTL); // 거절 즉시 AT 차단
        refreshTokenPort.deleteAllByUserId(userId); // RT 전체 삭제 (거절된 사용자 세션 종료)
    }

    @Override
    public void reapply(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        Instant now = Instant.now();
        // register()와 동일한 이유로 잠금 조회 필수 — 관리자의 승인설정 전환과 직렬화한다.
        boolean approvalRequired = runtimeSettingsPort.loadForUpdate().approvalRequired();

        // 상태별 쿨다운 검증
        switch (user.status()) {
            case PENDING  -> {
                // 승인 불필요 전환 직후 남아 있는 PENDING 사용자는 쿨다운 없이 즉시 활성화한다.
                if (approvalRequired) requireCooldownPassed(user, PENDING_REAPPLY_COOLDOWN_HOURS, now);
            }
            case REJECTED -> requireCooldownPassed(user, REJECTED_REAPPLY_COOLDOWN_HOURS, now);
            default -> throw new IllegalStateException("재신청 불가 상태: " + user.status());
        }

        // 승인 설정에 맞는 상태로 전환하고 기존 재신청 시각과 이벤트 흐름을 보존한다.
        User.UserStatus status = approvalRequired ? User.UserStatus.PENDING : User.UserStatus.ACTIVE;
        User updated = user.withStatus(status, now);
        userPort.save(updated);
        log.info("사용자 재신청: userId={}", userId);
        // 결과 상태에 맞춰 관리자 승인 요청 또는 사용자 승인 알림과 SSE를 한 번만 발행한다.
        if (approvalRequired) {
            eventPublisher.publishEvent(new UserReappliedEvent(updated));
        } else {
            eventPublisher.publishEvent(new UserApprovedEvent(updated));
        }
    }

    // 쿨다운 미경과 시 CooldownException 발생 — PENDING/REJECTED 공통 판정
    private void requireCooldownPassed(User user, long cooldownHours, Instant now) {
        if (user.lastReappliedAt() != null &&
                now.isBefore(user.lastReappliedAt().plus(cooldownHours, ChronoUnit.HOURS)))
            throw new User.CooldownException(user.lastReappliedAt().plus(cooldownHours, ChronoUnit.HOURS));
    }

    @Override
    public void deleteMe(UUID userId) {
        userPort.findByIdOrThrow(userId); // 존재 확인
        userCascadeDeleter.deleteCascade(userId);
        log.info("사용자 탈퇴: userId={}", userId);
    }
}
