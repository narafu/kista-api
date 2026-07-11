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
            user = register(kakaoUser.kakaoId(), kakaoUser.nickname(), UUID.randomUUID());
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
            User.UserStatus status = isAdminSeed ? User.UserStatus.ACTIVE : User.UserStatus.PENDING;
            User newUser = new User(userId, kakaoId, nickname, status, role,
                    null, null, null, null, User.DEFAULT_CHANNEL);
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
    public void reject(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        // REJECTED 전환 + 24h 카운트다운 시작 (lastReappliedAt = now)
        User updated = user.withStatus(User.UserStatus.REJECTED, Instant.now());
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

        // 상태별 쿨다운 검증
        switch (user.status()) {
            case PENDING  -> requireCooldownPassed(user, PENDING_REAPPLY_COOLDOWN_HOURS, now);
            case REJECTED -> requireCooldownPassed(user, REJECTED_REAPPLY_COOLDOWN_HOURS, now);
            default -> throw new IllegalStateException("재신청 불가 상태: " + user.status());
        }

        // PENDING 전환 + 재신청 시각 갱신
        User updated = user.withStatus(User.UserStatus.PENDING, now);
        userPort.save(updated);
        log.info("사용자 재신청: userId={}", userId);
        // 커밋 성공 후 관리자 알림 — 롤백 시 알림 미발송
        eventPublisher.publishEvent(new UserReappliedEvent(updated));
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
