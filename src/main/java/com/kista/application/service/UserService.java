package com.kista.application.service;

import com.kista.domain.model.CooldownException;
import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.RegisterUserUseCase;
import com.kista.domain.port.in.UpdateUserTelegramUseCase;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserRepository;
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
public class UserService implements RegisterUserUseCase, ApproveUserUseCase, GetUserUseCase, UpdateUserTelegramUseCase {

    private final UserRepository userRepository;
    private final UserNotificationPort notificationPort;
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림
    private final ApplicationEventPublisher eventPublisher; // 트랜잭션 커밋 후 이벤트 발행용

    @Override
    public User register(String kakaoId, String nickname, UUID userId) {
        // 기존 사용자면 반환, 신규이면 PENDING 저장 + 관리자 알림
        return userRepository.findByKakaoId(kakaoId).orElseGet(() -> {
            User newUser = new User(userId, kakaoId, nickname, UserStatus.PENDING,
                    null, null, null, null, null);
            User saved = userRepository.save(newUser);
            log.info("신규 사용자 등록: kakaoId={}, userId={}", kakaoId, userId);
            // 트랜잭션 커밋 성공 후에만 알림 발송 (race condition 시 롤백된 트랜잭션은 알림 미발송)
            eventPublisher.publishEvent(new NewUserRegisteredEvent(saved));
            return saved;
        });
    }

    @Override
    public void approve(UUID userId) {
        User user = findOrThrow(userId);
        User updated = withStatus(user, UserStatus.ACTIVE);
        userRepository.save(updated);
        log.info("사용자 승인: userId={}", userId);
        notificationPort.notifyApproved(updated);
        realtimeNotificationPort.notifyStatusChange(userId, UserStatus.ACTIVE);
    }

    @Override
    public void reject(UUID userId) {
        User user = findOrThrow(userId);
        // REJECTED 전환 + 24h 카운트다운 시작 (lastReappliedAt = now)
        User updated = new User(user.id(), user.kakaoId(), user.nickname(), UserStatus.REJECTED,
                user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null, Instant.now());
        userRepository.save(updated);
        log.info("사용자 거절: userId={}", userId);
        notificationPort.notifyRejected(updated);
        realtimeNotificationPort.notifyStatusChange(userId, UserStatus.REJECTED);
    }

    @Override
    public void reapply(UUID userId) {
        User user = findOrThrow(userId);
        Instant now = Instant.now();

        // 상태별 쿨다운 검증
        switch (user.status()) {
            case PENDING -> {
                if (user.lastReappliedAt() != null &&
                        now.isBefore(user.lastReappliedAt().plus(1, ChronoUnit.HOURS)))
                    throw new CooldownException(user.lastReappliedAt().plus(1, ChronoUnit.HOURS));
            }
            case REJECTED -> {
                if (user.lastReappliedAt() != null &&
                        now.isBefore(user.lastReappliedAt().plus(24, ChronoUnit.HOURS)))
                    throw new CooldownException(user.lastReappliedAt().plus(24, ChronoUnit.HOURS));
            }
            default -> throw new IllegalStateException("재신청 불가 상태: " + user.status());
        }

        // PENDING 전환 + 재신청 시각 갱신
        User updated = new User(user.id(), user.kakaoId(), user.nickname(), UserStatus.PENDING,
                user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null, now);
        userRepository.save(updated);
        log.info("사용자 재신청: userId={}", userId);
        notificationPort.notifyNewUser(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public User getByKakaoId(String kakaoId) {
        return userRepository.findByKakaoId(kakaoId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + kakaoId));
    }

    private User findOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }

    @Override
    public void updateTelegram(UUID userId, String botToken, String chatId) {
        User user = findOrThrow(userId);
        User updated = new User(user.id(), user.kakaoId(), user.nickname(), user.status(),
                botToken, chatId, user.createdAt(), null, user.lastReappliedAt());
        userRepository.save(updated);
        log.info("텔레그램 설정 업데이트: userId={}", userId);
    }

    @Override
    public void removeTelegram(UUID userId) {
        User user = findOrThrow(userId);
        User updated = new User(user.id(), user.kakaoId(), user.nickname(), user.status(),
                null, null, user.createdAt(), null, user.lastReappliedAt());
        userRepository.save(updated);
        log.info("텔레그램 설정 해제: userId={}", userId);
    }

    private User withStatus(User user, UserStatus newStatus) {
        return new User(user.id(), user.kakaoId(), user.nickname(), newStatus,
                user.telegramBotToken(), user.telegramChatId(), user.createdAt(), null,
                user.lastReappliedAt());
    }
}
