package com.kista.admin.application.service;

import com.kista.user.domain.model.AdminUserView;
import com.kista.user.domain.model.User;
import com.kista.user.application.usecase.UserUseCase;
import com.kista.user.application.port.output.AdminUserViewPort;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.user.application.port.output.BlacklistPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserPort userPort;
    @Mock AdminUserViewPort adminUserViewPort;
    @Mock UserUseCase userUseCase;
    @Mock AuditLogPort auditLogPort;
    @Mock BlacklistPort blacklistPort;

    @InjectMocks AdminService adminService;

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

        adminService.rejectUser(adminId, targetId, null);

        verify(userUseCase).reject(targetId, null);
        verify(auditLogPort).log(eq(adminId), eq("USER_REJECT"), eq("USER"), eq(targetId), any());
    }

    @Test
    void rejectUser_withReason_passesReasonToUseCaseAndAuditLog() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        String reason = "허위 정보 기재";

        adminService.rejectUser(adminId, targetId, reason);

        verify(userUseCase).reject(targetId, reason);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogPort).log(eq(adminId), eq("USER_REJECT"), eq("USER"), eq(targetId), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("reason", reason);
    }

    @Test
    void changeRole_updatesRoleAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        User existing = DomainFixtures.userWithStatus(targetId, UserStatus.ACTIVE);
        when(userPort.findByIdOrThrow(targetId)).thenReturn(existing);
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.changeRole(adminId, targetId, UserRole.ADMIN);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userPort).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRole.ADMIN);
        verify(auditLogPort).log(eq(adminId), eq("USER_ROLE_CHANGE"), eq("USER"), eq(targetId), any());
        // role 변경 시각 기록 — JwtAuthFilter가 이전 발급 AT를 stale로 판정하는 기준
        verify(blacklistPort).markRoleChanged(eq(targetId), any(Instant.class), any(Duration.class));
    }

    @Test
    void changeRole_throwsWhenSelfDemotion() {
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> adminService.changeRole(adminId, adminId, UserRole.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
    }

    @Test
    void changeRole_throwsWhenLastAdmin() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.countByRole(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> adminService.changeRole(adminId, targetId, UserRole.USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최소 1명");
    }

    @Test
    void changeRole_allowsDemotionWhenMultipleAdmins() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        User existing = DomainFixtures.userWithStatus(targetId, UserStatus.ACTIVE);
        when(userPort.countByRole(UserRole.ADMIN)).thenReturn(2L);
        when(userPort.findByIdOrThrow(targetId)).thenReturn(existing);
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.changeRole(adminId, targetId, UserRole.USER);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userPort).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRole.USER);
    }

    @Test
    void deleteUser_softDeletesCascadeAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(targetId)).thenReturn(DomainFixtures.userWithStatus(targetId, UserStatus.ACTIVE));

        adminService.deleteUser(adminId, targetId);

        // cascade 삭제는 UserUseCase.deleteMe로 위임
        verify(userUseCase).deleteMe(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_DELETE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void findUser_존재하는_사용자ID로_조회시_반환한다() {
        UUID targetId = UUID.randomUUID();
        AdminUserView view = new AdminUserView(targetId, "테스트", UserStatus.ACTIVE, UserRole.USER, Instant.now());
        when(adminUserViewPort.findById(targetId)).thenReturn(Optional.of(view));

        Optional<AdminUserView> result = adminService.findUser(targetId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(targetId);
    }

    @Test
    void findUser_존재하지_않는_사용자ID로_조회시_empty를_반환한다() {
        UUID otherId = UUID.randomUUID();
        when(adminUserViewPort.findById(otherId)).thenReturn(Optional.empty());

        Optional<AdminUserView> result = adminService.findUser(otherId);

        assertThat(result).isEmpty();
    }
}
