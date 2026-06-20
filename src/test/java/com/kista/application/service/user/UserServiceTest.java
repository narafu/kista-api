package com.kista.application.service.user;

import com.kista.application.config.AdminBootstrapProperties;
import com.kista.application.event.NewUserRegisteredEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import com.kista.domain.port.out.KakaoOAuthPort;
import com.kista.domain.port.out.RefreshTokenPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.TelegramBotInfoPort;
import com.kista.domain.port.out.UserNotificationPort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    @Mock UserPort userPort;
    @Mock UserCascadeDeleter userCascadeDeleter;
    @Mock UserNotificationPort notificationPort;
    @Mock RealtimeNotificationPort realtimeNotificationPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminBootstrapProperties bootstrapProps;
    @Mock TelegramBotInfoPort telegramBotInfoPort;
    @Mock KakaoOAuthPort kakaoOAuthPort;
    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;
    @Mock BlacklistPort blacklistPort;
    @Mock RefreshTokenPort refreshTokenPort;

    @InjectMocks UserService userService;

    private User pendingUser(UUID id) {
        // lastReappliedAt=null → 쿨다운 없음 (신규 PENDING)
        return new User(id, "kakao-123", "홍길동", User.UserStatus.PENDING, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    private User rejectedUser(UUID id) {
        // 25h 전 거절 → 24h 쿨다운 경과
        return new User(id, "kakao-123", "홍길동", User.UserStatus.REJECTED, User.UserRole.USER,
                null, null, null,
                Instant.now().minus(25, ChronoUnit.HOURS), NotificationChannel.TELEGRAM);
    }

    private User pendingUserWithCooldown(UUID id, Instant lastReappliedAt) {
        return new User(id, "kakao-123", "홍길동", User.UserStatus.PENDING, User.UserRole.USER,
                null, null, null, lastReappliedAt, NotificationChannel.TELEGRAM);
    }

    private User rejectedUserWithCooldown(UUID id, Instant lastReappliedAt) {
        return new User(id, "kakao-123", "홍길동", User.UserStatus.REJECTED, User.UserRole.USER,
                null, null, null, lastReappliedAt, NotificationChannel.TELEGRAM);
    }

    @Test
    @DisplayName("신규 사용자 등록 시 PENDING 저장 + 커밋 후 알림 이벤트 발행")
    void register_new_user_saves_pending_and_publishes_event() {
        UUID uid = UUID.randomUUID();
        when(userPort.findByKakaoId("kakao-123")).thenReturn(Optional.empty());
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        when(userPort.findByKakaoId("kakao-123")).thenReturn(Optional.of(existing));

        User result = userService.register("kakao-123", "홍길동", uid);

        assertThat(result).isEqualTo(existing);
        verify(userPort, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("REJECTED 상태 사용자 재신청 시 PENDING 전환 + 관리자 재알림")
    void reapply_rejected_user_sets_pending_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(rejectedUser(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userPort).save(argThat(u ->
                u.status() == User.UserStatus.PENDING && u.lastReappliedAt() != null));
        verify(notificationPort).notifyNewUser(any());
    }

    @Test
    @DisplayName("PENDING 1시간 이내 재신청 시 CooldownException")
    void reapply_pending_within_1h_throws_cooldown() {
        UUID userId = UUID.randomUUID();
        // 30분 전에 마지막 재신청
        when(userPort.findByIdOrThrow(userId)).thenReturn(
                pendingUserWithCooldown(userId, Instant.now().minus(30, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> userService.reapply(userId))
                .isInstanceOf(Account.CooldownException.class);
    }

    @Test
    @DisplayName("PENDING 1시간 경과 후 재신청 성공 + 알림")
    void reapply_pending_after_1h_succeeds() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(
                pendingUserWithCooldown(userId, Instant.now().minus(2, ChronoUnit.HOURS)));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userPort).save(argThat(u ->
                u.status() == User.UserStatus.PENDING && u.lastReappliedAt() != null));
        verify(notificationPort).notifyNewUser(any());
    }

    @Test
    @DisplayName("PENDING lastReappliedAt=null 이면 즉시 재신청 허용")
    void reapply_pending_null_lastReappliedAt_succeeds() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(pendingUser(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userPort).save(argThat(u -> u.status() == User.UserStatus.PENDING));
    }

    @Test
    @DisplayName("REJECTED 24시간 이내 재신청 시 CooldownException")
    void reapply_rejected_within_24h_throws_cooldown() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(
                rejectedUserWithCooldown(userId, Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThatThrownBy(() -> userService.reapply(userId))
                .isInstanceOf(Account.CooldownException.class);
    }

    @Test
    @DisplayName("REJECTED lastReappliedAt=null 이면 즉시 재신청 허용 (기존 DB 사용자)")
    void reapply_rejected_null_lastReappliedAt_succeeds() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "kakao-123", "홍길동", User.UserStatus.REJECTED, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
        when(userPort.findByIdOrThrow(userId)).thenReturn(user);
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reapply(userId);

        verify(userPort).save(argThat(u -> u.status() == User.UserStatus.PENDING));
    }

    @Test
    @DisplayName("거절 시 lastReappliedAt 갱신 (24h 카운트다운 시작)")
    void reject_sets_lastReappliedAt() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(pendingUser(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reject(userId);

        verify(userPort).save(argThat(u ->
                u.status() == User.UserStatus.REJECTED && u.lastReappliedAt() != null));
    }

    @Test
    @DisplayName("승인 시 ACTIVE 전환 + 승인 알림")
    void approve_sets_active_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(pendingUser(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.approve(userId);

        verify(userPort).save(argThat(u -> u.status() == User.UserStatus.ACTIVE));
        verify(notificationPort).notifyApproved(any());
    }

    @Test
    @DisplayName("거절 시 REJECTED 전환 + 거절 알림")
    void reject_sets_rejected_and_notifies() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(pendingUser(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reject(userId);

        verify(userPort).save(argThat(u -> u.status() == User.UserStatus.REJECTED));
        verify(notificationPort).notifyRejected(any());
    }

    @Test
    @DisplayName("거절 시 블랙리스트 즉시 등재 (AT 만료까지 15분 차단)")
    void reject_blacklistsUser() {
        UUID userId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(userId)).thenReturn(pendingUser(userId));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.reject(userId);

        verify(blacklistPort).add(eq(userId), eq(Duration.ofMinutes(15)));
        verify(refreshTokenPort).deleteAllByUserId(userId);
    }

    @Test
    @DisplayName("ADMIN seed kakaoId 신규 등록 시 ACTIVE + ADMIN 역할로 생성")
    void register_adminSeed_returnsActiveAdmin() {
        // given
        UUID uid = UUID.randomUUID();
        when(bootstrapProps.isAdmin("12345")).thenReturn(true);
        when(userPort.findByKakaoId("12345")).thenReturn(Optional.empty());
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        when(userPort.findByKakaoId("99999")).thenReturn(Optional.empty());
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // when
        User result = userService.register("99999", "userName", uid);

        // then
        assertThat(result.role()).isEqualTo(User.UserRole.USER);
        assertThat(result.status()).isEqualTo(User.UserStatus.PENDING);
    }

    @Test
    @DisplayName("회원 탈퇴 시 userPort.delete 호출")
    void deleteMe_removesUser() {
        UUID id = UUID.randomUUID();
        when(userPort.findByIdOrThrow(id)).thenReturn(pendingUser(id));

        userService.deleteMe(id);

        verify(userCascadeDeleter).deleteCascade(id);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 탈퇴 시 NoSuchElementException")
    void deleteMe_userNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(userPort.findByIdOrThrow(id)).thenThrow(new NoSuchElementException("사용자를 찾을 수 없습니다: " + id));

        assertThatThrownBy(() -> userService.deleteMe(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("FCM 토큰 등록 시 fcmDeviceTokenPort.save 호출")
    void registerFcmToken_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        userService.registerFcmToken(userId, "token-abc", "WEB");
        verify(fcmDeviceTokenPort).save(userId, "token-abc", "WEB");
    }

    @Test
    @DisplayName("FCM 토큰 삭제 시 fcmDeviceTokenPort.delete 호출")
    void unregisterFcmToken_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        userService.unregisterFcmToken(userId, "token-abc");
        verify(fcmDeviceTokenPort).delete(userId, "token-abc");
    }

    // ─── login() 시나리오 ──────────────────────────────────────────────

    @Test
    @DisplayName("신규 사용자 login: findByKakaoId 결과 없으면 register 경로 → 새 User 반환")
    void login_newUser_registersAndReturns() {
        // given
        String code = "auth-code";
        String redirectUri = "https://example.com/callback";
        String kakaoId = "kakao-new-001";
        String accessToken = "kakao-access-token";
        UUID newUserId = UUID.randomUUID();

        when(kakaoOAuthPort.exchangeCodeForToken(code, redirectUri)).thenReturn(accessToken);
        when(kakaoOAuthPort.getUserInfo(accessToken)).thenReturn(new KakaoOAuthPort.KakaoUserInfo(kakaoId, "신규사용자"));
        when(bootstrapProps.isAdmin(kakaoId)).thenReturn(false);
        // register() 내부: findByKakaoId → empty → save 경로
        when(userPort.findByKakaoId(kakaoId)).thenReturn(Optional.empty());
        when(userPort.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            // UUID가 null이 아닌 User를 그대로 반환 (id는 register()에서 전달된 UUID)
            return u;
        });

        // when
        User result = userService.login(code, redirectUri);

        // then
        assertThat(result.kakaoId()).isEqualTo(kakaoId);
        assertThat(result.status()).isEqualTo(User.UserStatus.PENDING);
        assertThat(result.role()).isEqualTo(User.UserRole.USER);
        verify(userPort).save(any()); // 신규 저장 1회
        verify(eventPublisher).publishEvent(any(NewUserRegisteredEvent.class));
    }

    @Test
    @DisplayName("기존 사용자 login: findByKakaoId 결과 있으면 기존 User 반환 (register 내 save 미호출)")
    void login_existingUser_returnsExistingWithoutSave() {
        // given
        String code = "auth-code";
        String redirectUri = "https://example.com/callback";
        String kakaoId = "kakao-existing-001";
        String accessToken = "kakao-access-token";
        UUID existingId = UUID.randomUUID();
        User existingUser = new User(existingId, kakaoId, "기존사용자", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);

        when(kakaoOAuthPort.exchangeCodeForToken(code, redirectUri)).thenReturn(accessToken);
        when(kakaoOAuthPort.getUserInfo(accessToken)).thenReturn(new KakaoOAuthPort.KakaoUserInfo(kakaoId, "기존사용자"));
        when(bootstrapProps.isAdmin(kakaoId)).thenReturn(false);
        // register() 내부: findByKakaoId → 기존 사용자 반환 → orElseGet 미실행
        when(userPort.findByKakaoId(kakaoId)).thenReturn(Optional.of(existingUser));

        // when
        User result = userService.login(code, redirectUri);

        // then
        assertThat(result).isEqualTo(existingUser);
        verify(userPort, never()).save(any()); // 기존 사용자 → 저장 없음
        verify(eventPublisher, never()).publishEvent(any()); // 이벤트 발행 없음
    }

    @Test
    @DisplayName("중복 등록 경쟁 조건: save에서 DataIntegrityViolationException → findByKakaoId 재조회로 fallback")
    void login_duplicateRegistration_fallbacksToExistingUser() {
        // given
        String code = "auth-code";
        String redirectUri = "https://example.com/callback";
        String kakaoId = "kakao-race-001";
        String accessToken = "kakao-access-token";
        UUID existingId = UUID.randomUUID();
        User existingUser = new User(existingId, kakaoId, "경쟁사용자", User.UserStatus.PENDING, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);

        when(kakaoOAuthPort.exchangeCodeForToken(code, redirectUri)).thenReturn(accessToken);
        when(kakaoOAuthPort.getUserInfo(accessToken)).thenReturn(new KakaoOAuthPort.KakaoUserInfo(kakaoId, "경쟁사용자"));
        when(bootstrapProps.isAdmin(kakaoId)).thenReturn(false);
        // register() 내부: findByKakaoId → empty → save → DataIntegrityViolationException
        when(userPort.findByKakaoId(kakaoId))
                .thenReturn(Optional.empty())             // register() 내 첫 조회: empty
                .thenReturn(Optional.of(existingUser));  // catch 블록의 fallback 재조회
        when(userPort.save(any())).thenThrow(new DataIntegrityViolationException("UK constraint"));

        // when
        User result = userService.login(code, redirectUri);

        // then
        assertThat(result).isEqualTo(existingUser);
        verify(userPort, times(2)).findByKakaoId(kakaoId); // 1차(register내) + 2차(catch fallback)
    }
}
