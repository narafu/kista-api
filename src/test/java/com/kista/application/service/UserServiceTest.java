package com.kista.application.service;

import com.kista.application.config.AdminBootstrapProperties;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.TelegramBotInfoPort;
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
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
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
    @Mock AdminBootstrapProperties bootstrapProps;
    @Mock TelegramBotInfoPort telegramBotInfoPort;

    @InjectMocks UserService userService;

    private User pendingUser(UUID id) {
        // lastReappliedAt=null → 쿨다운 없음 (신규 PENDING)
        return new User(id, "kakao-123", "홍길동", User.UserStatus.PENDING, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
    }

    private User rejectedUser(UUID id) {
        // 25h 전 거절 → 24h 쿨다운 경과
        return new User(id, "kakao-123", "홍길동", User.UserStatus.REJECTED, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(),
                Instant.now().minus(25, ChronoUnit.HOURS), NotificationChannel.TELEGRAM);
    }

    private User pendingUserWithCooldown(UUID id, Instant lastReappliedAt) {
        return new User(id, "kakao-123", "홍길동", User.UserStatus.PENDING, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), lastReappliedAt, NotificationChannel.TELEGRAM);
    }

    private User rejectedUserWithCooldown(UUID id, Instant lastReappliedAt) {
        return new User(id, "kakao-123", "홍길동", User.UserStatus.REJECTED, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), lastReappliedAt, NotificationChannel.TELEGRAM);
    }

    @Test
    @DisplayName("신규 사용자 등록 시 PENDING 저장 + 커밋 후 알림 이벤트 발행")
    void register_new_user_saves_pending_and_publishes_event() {
        UUID uid = UUID.randomUUID();
        when(userRepository.findByKakaoId("kakao-123")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.register("kakao-123", "홍길동", uid);

        assertThat(result.status()).isEqualTo(User.UserStatus.PENDING);
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

        verify(userRepository).save(argThat(u ->
                u.status() == User.UserStatus.PENDING && u.lastReappliedAt() != null));
        verify(notificationPort).notifyNewUser(any());
    }

    @Test
    @DisplayName("PENDING 1시간 이내 재신청 시 CooldownException")
    void reapply_pending_within_1h_throws_cooldown() {
        UUID userId = UUID.randomUUID();
        // 30분 전에 마지막 재신청
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                pendingUserWithCooldown(userId, Instant.now().minus(30, ChronoUnit.MINUTES))));

        assertThatThrownBy(() -> userService.reapply(userId))
                .isInstanceOf(Account.CooldownException.class);
    }

    @Test
    @DisplayName("PENDING 1시간 경과 후 재신청 성공 + 알림")
    void reapply_pending_after_1h_succeeds() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                pendingUserWithCooldown(userId, Instant.now().minus(2, ChronoUnit.HOURS))));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userRepository).save(argThat(u ->
                u.status() == User.UserStatus.PENDING && u.lastReappliedAt() != null));
        verify(notificationPort).notifyNewUser(any());
    }

    @Test
    @DisplayName("PENDING lastReappliedAt=null 이면 즉시 재신청 허용")
    void reapply_pending_null_lastReappliedAt_succeeds() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userRepository).save(argThat(u -> u.status() == User.UserStatus.PENDING));
    }

    @Test
    @DisplayName("REJECTED 24시간 이내 재신청 시 CooldownException")
    void reapply_rejected_within_24h_throws_cooldown() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                rejectedUserWithCooldown(userId, Instant.now().minus(1, ChronoUnit.HOURS))));

        assertThatThrownBy(() -> userService.reapply(userId))
                .isInstanceOf(Account.CooldownException.class);
    }

    @Test
    @DisplayName("REJECTED lastReappliedAt=null 이면 즉시 재신청 허용 (기존 DB 사용자)")
    void reapply_rejected_null_lastReappliedAt_succeeds() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "kakao-123", "홍길동", User.UserStatus.REJECTED, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userRepository).save(argThat(u -> u.status() == User.UserStatus.PENDING));
    }

    @Test
    @DisplayName("거절 시 lastReappliedAt 갱신 (24h 카운트다운 시작)")
    void reject_sets_lastReappliedAt() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reject(userId);

        verify(userRepository).save(argThat(u ->
                u.status() == User.UserStatus.REJECTED && u.lastReappliedAt() != null));
    }

    @Test
    @DisplayName("승인 시 ACTIVE 전환 + 승인 알림")
    void approve_sets_active_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.approve(userId);

        verify(userRepository).save(argThat(u -> u.status() == User.UserStatus.ACTIVE));
        verify(notificationPort).notifyApproved(any());
    }

    @Test
    @DisplayName("거절 시 REJECTED 전환 + 거절 알림")
    void reject_sets_rejected_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser(userId)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reject(userId);

        verify(userRepository).save(argThat(u -> u.status() == User.UserStatus.REJECTED));
        verify(notificationPort).notifyRejected(any());
    }

    @Test
    @DisplayName("ADMIN seed kakaoId 신규 등록 시 ACTIVE + ADMIN 역할로 생성")
    void register_adminSeed_returnsActiveAdmin() {
        // given
        UUID uid = UUID.randomUUID();
        when(bootstrapProps.isAdmin("12345")).thenReturn(true);
        when(userRepository.findByKakaoId("12345")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        User result = userService.register("12345", "adminName", uid);

        // then
        assertThat(result.role()).isEqualTo(User.UserRole.ADMIN);
        assertThat(result.status()).isEqualTo(User.UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("일반 사용자 신규 등록 시 PENDING + USER 역할로 생성")
    void register_normalUser_returnsPendingUser() {
        // given
        UUID uid = UUID.randomUUID();
        when(bootstrapProps.isAdmin("99999")).thenReturn(false);
        when(userRepository.findByKakaoId("99999")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        User result = userService.register("99999", "userName", uid);

        // then
        assertThat(result.role()).isEqualTo(User.UserRole.USER);
        assertThat(result.status()).isEqualTo(User.UserStatus.PENDING);
    }

    @Test
    @DisplayName("회원 탈퇴 시 userRepository.delete 호출")
    void deleteMe_removesUser() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(pendingUser(id)));

        userService.deleteMe(id);

        verify(userRepository).delete(id);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 탈퇴 시 NoSuchElementException")
    void deleteMe_userNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteMe(id))
                .isInstanceOf(NoSuchElementException.class);
    }
}
