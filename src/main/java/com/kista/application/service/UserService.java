package com.kista.application.service;

import com.kista.application.config.AdminBootstrapProperties;
import com.kista.application.event.NewUserRegisteredEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.DeleteMeUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import com.kista.domain.port.in.UpdateNotificationChannelUseCase;
import com.kista.domain.port.in.UpdateUserTelegramUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.TelegramBotInfoPort;
import com.kista.domain.port.out.TradingCyclePort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class UserService implements RegisterUserUseCase, ApproveUserUseCase, GetUserUseCase, UpdateUserTelegramUseCase, DeleteMeUseCase, UpdateNotificationChannelUseCase {

    private final UserPort userPort;
    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;
    private final UserNotificationPort notificationPort;
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림
    private final ApplicationEventPublisher eventPublisher; // 트랜잭션 커밋 후 이벤트 발행용
    private final AdminBootstrapProperties bootstrapProps; // ADMIN seed 목록
    private final TelegramBotInfoPort telegramBotInfoPort; // 봇 토큰 검증 + username 취득

    @Override
    public User register(String kakaoId, String nickname, UUID userId) {
        // 기존 사용자면 반환, 신규이면 역할/상태 결정 후 저장 + 관리자 알림
        return userPort.findByKakaoId(kakaoId).orElseGet(() -> {
            // ADMIN seed 여부에 따라 역할/상태 결정
            boolean isAdminSeed = bootstrapProps.isAdmin(kakaoId);
            User.UserRole role = isAdminSeed ? User.UserRole.ADMIN : User.UserRole.USER;
            User.UserStatus status = isAdminSeed ? User.UserStatus.ACTIVE : User.UserStatus.PENDING;
            User newUser = new User(userId, kakaoId, nickname, status, role,
                    null, null, null, null, NotificationChannel.TELEGRAM);
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
        notificationPort.notifyApproved(updated);
        realtimeNotificationPort.notifyStatusChange(userId, User.UserStatus.ACTIVE);
    }

    @Override
    public void reject(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        // REJECTED 전환 + 24h 카운트다운 시작 (lastReappliedAt = now)
        User updated = user.withStatus(User.UserStatus.REJECTED, Instant.now());
        userPort.save(updated);
        log.info("사용자 거절: userId={}", userId);
        notificationPort.notifyRejected(updated);
        realtimeNotificationPort.notifyStatusChange(userId, User.UserStatus.REJECTED);
    }

    @Override
    public void reapply(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        Instant now = Instant.now();

        // 상태별 쿨다운 검증
        switch (user.status()) {
            case PENDING -> {
                if (user.lastReappliedAt() != null &&
                        now.isBefore(user.lastReappliedAt().plus(1, ChronoUnit.HOURS)))
                    throw new Account.CooldownException(user.lastReappliedAt().plus(1, ChronoUnit.HOURS));
            }
            case REJECTED -> {
                if (user.lastReappliedAt() != null &&
                        now.isBefore(user.lastReappliedAt().plus(24, ChronoUnit.HOURS)))
                    throw new Account.CooldownException(user.lastReappliedAt().plus(24, ChronoUnit.HOURS));
            }
            default -> throw new IllegalStateException("재신청 불가 상태: " + user.status());
        }

        // PENDING 전환 + 재신청 시각 갱신
        User updated = user.withStatus(User.UserStatus.PENDING, now);
        userPort.save(updated);
        log.info("사용자 재신청: userId={}", userId);
        notificationPort.notifyNewUser(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userPort.findByIdOrThrow(id);
    }

    @Override
    @Transactional(readOnly = true)
    public User getByKakaoId(String kakaoId) {
        return userPort.findByKakaoId(kakaoId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + kakaoId));
    }


    @Override
    public void updateTelegram(UUID userId, String botToken, String chatId) {
        // botToken 유효성 검증 + username 취득 (실패 시 IllegalArgumentException)
        String botUsername = telegramBotInfoPort.getUsername(botToken);
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withTelegram(botToken, chatId, botUsername));
        log.info("텔레그램 설정 업데이트: userId={}, botUsername={}", userId, botUsername);
    }

    @Override
    public void removeTelegram(UUID userId) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withTelegram(null, null, null));
        log.info("텔레그램 설정 해제: userId={}", userId);
    }

    @Override
    public void deleteMe(UUID userId) {
        userPort.findByIdOrThrow(userId); // 존재 확인
        // 사이클 → 계좌 → 사용자 순으로 소프트 삭제 (FK CASCADE 대체)
        cyclePort.deleteByUserId(userId);
        accountPort.deleteByUserId(userId);
        userPort.delete(userId);
        log.info("사용자 탈퇴: userId={}", userId);
    }

    @Override
    public void updateNotificationChannel(UUID userId, NotificationChannel channel) {
        User user = userPort.findByIdOrThrow(userId);
        userPort.save(user.withNotificationChannel(channel));
        log.info("알림 채널 변경: userId={}, channel={}", userId, channel);
    }
}
