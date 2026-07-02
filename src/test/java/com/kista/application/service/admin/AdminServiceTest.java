package com.kista.application.service.admin;

import com.kista.application.service.user.UserCascadeDeleter;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.AdminUserViewPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserPort userPort;
    @Mock AdminUserViewPort adminUserViewPort;
    @Mock UserCascadeDeleter userCascadeDeleter;
    @Mock UserUseCase userUseCase;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminService adminService;

    // User 헬퍼: User record 필드 순서에 맞게 작성
    // (id, kakaoId, nickname, status, role, telegramBotToken, telegramChatId, createdAt, updatedAt, lastReappliedAt)
    private User user(UUID id, User.UserStatus status) {
        return new User(id, "kakao-" + id, "테스트", status, User.UserRole.USER,
                null, null, null, null, NotificationChannel.TELEGRAM);
    }

    @Test
    void approveUser_delegatesAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();

        adminService.approveUser(adminId, targetId);

        verify(userUseCase).approve(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_APPROVE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void rejectUser_delegatesAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();

        adminService.rejectUser(adminId, targetId);

        verify(userUseCase).reject(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_REJECT"), eq("USER"), eq(targetId), any());
    }

    @Test
    void changeRole_updatesRoleAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        User existing = user(targetId, User.UserStatus.ACTIVE);
        when(userPort.findByIdOrThrow(targetId)).thenReturn(existing);
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.changeRole(adminId, targetId, User.UserRole.ADMIN);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userPort).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(User.UserRole.ADMIN);
        verify(auditLogPort).log(eq(adminId), eq("USER_ROLE_CHANGE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void changeRole_throwsWhenSelfDemotion() {
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> adminService.changeRole(adminId, adminId, User.UserRole.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
    }

    @Test
    void changeRole_throwsWhenLastAdmin() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.countByRole(User.UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> adminService.changeRole(adminId, targetId, User.UserRole.USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최소 1명");
    }

    @Test
    void changeRole_allowsDemotionWhenMultipleAdmins() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        User existing = user(targetId, User.UserStatus.ACTIVE);
        when(userPort.countByRole(User.UserRole.ADMIN)).thenReturn(2L);
        when(userPort.findByIdOrThrow(targetId)).thenReturn(existing);
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.changeRole(adminId, targetId, User.UserRole.USER);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userPort).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(User.UserRole.USER);
    }

    @Test
    void deleteUser_softDeletesCascadeAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(targetId)).thenReturn(user(targetId, User.UserStatus.ACTIVE));

        adminService.deleteUser(adminId, targetId);

        // cascade 삭제는 UserCascadeDeleter에 위임
        verify(userCascadeDeleter).deleteCascade(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_DELETE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void findUser_존재하는_사용자ID로_조회시_반환한다() {
        UUID targetId = UUID.randomUUID();
        AdminUserView view = new AdminUserView(targetId, "테스트", User.UserStatus.ACTIVE, User.UserRole.USER, Instant.now());
        when(adminUserViewPort.findAll()).thenReturn(List.of(view));

        Optional<AdminUserView> result = adminService.findUser(targetId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(targetId);
    }

    @Test
    void findUser_존재하지_않는_사용자ID로_조회시_empty를_반환한다() {
        UUID otherId = UUID.randomUUID();
        AdminUserView view = new AdminUserView(UUID.randomUUID(), "다른사용자", User.UserStatus.ACTIVE, User.UserRole.USER, Instant.now());
        when(adminUserViewPort.findAll()).thenReturn(List.of(view));

        Optional<AdminUserView> result = adminService.findUser(otherId);

        assertThat(result).isEmpty();
    }
}
