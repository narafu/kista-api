package com.kista.application.service;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserNotificationPort notificationPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks UserService userService;

    private User pendingUser(UUID id) {
        return new User(id, "kakao-123", "홍길동", UserStatus.PENDING,
                null, null, Instant.now(), Instant.now());
    }

    private User rejectedUser(UUID id) {
        return new User(id, "kakao-123", "홍길동", UserStatus.REJECTED,
                null, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("신규 사용자 등록 시 PENDING 저장 + 커밋 후 알림 이벤트 발행")
    void register_new_user_saves_pending_and_publishes_event() {
        UUID uid = UUID.randomUUID();
        when(userRepository.findByKakaoId("kakao-123")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.register("kakao-123", "홍길동", uid);

        assertThat(result.status()).isEqualTo(UserStatus.PENDING);
        assertThat(result.id()).isEqualTo(uid);
        verify(eventPublisher).publishEvent(any(NewUserRegisteredEvent.class));
        verify(notificationPort, never()).notifyNewUser(any()); // 직접 호출하지 않음
    }

    @Test
    @DisplayName("기존 사용자 등록 요청 시 기존 사용자 반환 (중복 저장 없음)")
    void register_existing_user_returns_without_saving() {
        UUID uid = UUID.randomUUID();
        User existing = pendingUser(uid);
        when(userRepository.findByKakaoId("kakao-123")).thenReturn(Optional.of(existing));

        User result = userService.register("kakao-123", "홍길동", uid);

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("REJECTED 상태 사용자 재신청 시 PENDING 전환 + 관리자 재알림")
    void reapply_rejected_user_sets_pending_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(rejectedUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userRepository).save(argThat(u -> u.status() == UserStatus.PENDING));
        verify(notificationPort).notifyNewUser(any());
    }

    @Test
    @DisplayName("PENDING 상태에서 재신청 시 예외 발생")
    void reapply_pending_user_throws_exception() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));

        assertThatThrownBy(() -> userService.reapply(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    @DisplayName("승인 시 ACTIVE 전환 + 승인 알림")
    void approve_sets_active_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.approve(userId);

        verify(userRepository).save(argThat(u -> u.status() == UserStatus.ACTIVE));
        verify(notificationPort).notifyApproved(any());
    }

    @Test
    @DisplayName("거절 시 REJECTED 전환 + 거절 알림")
    void reject_sets_rejected_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reject(userId);

        verify(userRepository).save(argThat(u -> u.status() == UserStatus.REJECTED));
        verify(notificationPort).notifyRejected(any());
    }
}
