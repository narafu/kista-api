package com.kista.admin.application.service;

import com.kista.user.domain.model.UserSummary;
import com.kista.user.application.usecase.UserUseCase;
import com.kista.user.application.port.output.UserSummaryPort;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.user.application.port.output.UserPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.kista.user.domain.model.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserPort userPort;
    @Mock UserSummaryPort userSummaryPort;
    @Mock UserUseCase userUseCase;
    @Mock AuditLogPort auditLogPort;

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
    void approveUserByTelegram_mappedAdmin_logsAdminIdWithTelegramActor() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userUseCase.findUserIdByTelegramChatId("777")).thenReturn(Optional.of(adminId));
        when(userPort.findById(adminId)).thenReturn(Optional.of(new User(adminId, "k", "n", null,
                UserStatus.ACTIVE, UserRole.ADMIN, null, null, null, null, null, User.DEFAULT_CHANNEL)));

        adminService.approveUserByTelegram("777", targetId);

        verify(userUseCase).approve(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_APPROVE"), eq("USER"), eq(targetId),
                eq(Map.of("actor", "TELEGRAM_BOT", "chatId", "777")));
    }

    @Test
    void rejectUserByTelegram_unmappedChat_logsNullAdminId() {
        UUID targetId = UUID.randomUUID();
        when(userUseCase.findUserIdByTelegramChatId("777")).thenReturn(Optional.empty());

        adminService.rejectUserByTelegram("777", targetId);

        verify(userUseCase).reject(targetId, null);
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogPort).log(eq(null), eq("USER_REJECT"), eq("USER"), eq(targetId), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("actor", "TELEGRAM_BOT").containsEntry("chatId", "777");
    }

    @Test
    void approveUserByTelegram_nonAdminChatOwner_logsNullAdminId() {
        UUID userId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userUseCase.findUserIdByTelegramChatId("777")).thenReturn(Optional.of(userId));
        when(userPort.findById(userId)).thenReturn(Optional.of(new User(userId, "k", "n", null,
                UserStatus.ACTIVE, UserRole.USER, null, null, null, null, null, User.DEFAULT_CHANNEL)));

        adminService.approveUserByTelegram("777", targetId);

        verify(auditLogPort).log(eq(null), eq("USER_APPROVE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void changeRole_delegatesToUserUseCaseAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();

        adminService.changeRole(adminId, targetId, UserRole.ADMIN);

        // 역할 저장 + AT 무효화는 user 모듈(UserUseCase.changeRole)이 담당 — admin은 검증과 감사 로그만
        verify(userUseCase).changeRole(targetId, UserRole.ADMIN);
        verify(auditLogPort).log(eq(adminId), eq("USER_ROLE_CHANGE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void changeRole_throwsWhenSelfDemotion() {
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> adminService.changeRole(adminId, adminId, UserRole.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
        verify(userUseCase, never()).changeRole(any(), any());
    }

    @Test
    void changeRole_throwsWhenLastAdmin() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(targetId)).thenReturn(DomainFixtures.userWithStatus(targetId, UserStatus.ACTIVE, UserRole.ADMIN));
        when(userPort.countByRole(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> adminService.changeRole(adminId, targetId, UserRole.USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최소 1명");
        verify(userUseCase, never()).changeRole(any(), any());
    }

    @Test
    void changeRole_allowsDemotionWhenMultipleAdmins() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(targetId)).thenReturn(DomainFixtures.userWithStatus(targetId, UserStatus.ACTIVE, UserRole.ADMIN));
        when(userPort.countByRole(UserRole.ADMIN)).thenReturn(2L);

        adminService.changeRole(adminId, targetId, UserRole.USER);

        verify(userUseCase).changeRole(targetId, UserRole.USER);
    }

    @Test
    void changeRole_toUserForNonAdminTarget_skipsLastAdminCheck() {
        // 대상이 이미 USER면 관리자 수가 줄지 않으므로 마지막 관리자 검사로 막지 않는다
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.findByIdOrThrow(targetId)).thenReturn(DomainFixtures.userWithStatus(targetId, UserStatus.ACTIVE, UserRole.USER));

        adminService.changeRole(adminId, targetId, UserRole.USER);

        verify(userPort, never()).countByRole(any());
        verify(userUseCase).changeRole(targetId, UserRole.USER);
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
        UserSummary view = new UserSummary(targetId, "테스트", UserStatus.ACTIVE, UserRole.USER, Instant.now());
        when(userSummaryPort.findById(targetId)).thenReturn(Optional.of(view));

        Optional<UserSummary> result = adminService.findUser(targetId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(targetId);
    }

    @Test
    void findUser_존재하지_않는_사용자ID로_조회시_empty를_반환한다() {
        UUID otherId = UUID.randomUUID();
        when(userSummaryPort.findById(otherId)).thenReturn(Optional.empty());

        Optional<UserSummary> result = adminService.findUser(otherId);

        assertThat(result).isEmpty();
    }
}
