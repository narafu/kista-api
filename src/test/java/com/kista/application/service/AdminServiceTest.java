package com.kista.application.service;

import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.user.NotificationChannel;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.TradingCyclePort;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserPort userPort;
    @Mock AccountPort accountPort;
    @Mock TradingCyclePort cyclePort;
    @Mock ApproveUserUseCase approveUserUseCase;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminService adminService;

    // User 헬퍼: User record 필드 순서에 맞게 작성
    // (id, kakaoId, nickname, status, role, telegramBotToken, telegramChatId, createdAt, updatedAt, lastReappliedAt)
    private User user(UUID id, User.UserStatus status) {
        return new User(id, "kakao-" + id, "테스트", status, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null, NotificationChannel.TELEGRAM);
    }

    @Test
    void getStats_returnsCorrectCounts() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID(), id3 = UUID.randomUUID();
        when(userPort.findAll()).thenReturn(List.of(
                user(id1, User.UserStatus.PENDING),
                user(id2, User.UserStatus.ACTIVE),
                user(id3, User.UserStatus.REJECTED)
        ));
        when(accountPort.countAll()).thenReturn(5L);

        AdminStats stats = adminService.getStats();

        assertThat(stats.totalUsers()).isEqualTo(3);
        assertThat(stats.pendingCount()).isEqualTo(1);
        assertThat(stats.activeCount()).isEqualTo(1);
        assertThat(stats.rejectedCount()).isEqualTo(1);
        assertThat(stats.totalAccounts()).isEqualTo(5);
    }

    @Test
    void approveUser_delegatesAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();

        adminService.approveUser(adminId, targetId);

        verify(approveUserUseCase).approve(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_APPROVE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void rejectUser_delegatesAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();

        adminService.rejectUser(adminId, targetId);

        verify(approveUserUseCase).reject(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_REJECT"), eq("USER"), eq(targetId), any());
    }

    @Test
    void changeRole_updatesRoleAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        User existing = user(targetId, User.UserStatus.ACTIVE);
        when(userPort.findById(targetId)).thenReturn(Optional.of(existing));
        when(userPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.changeRole(adminId, targetId, User.UserRole.ADMIN);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userPort).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(User.UserRole.ADMIN);
        verify(auditLogPort).log(eq(adminId), eq("USER_ROLE_CHANGE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void deleteUser_softDeletesCascadeAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userPort.findById(targetId)).thenReturn(Optional.of(user(targetId, User.UserStatus.ACTIVE)));

        adminService.deleteUser(adminId, targetId);

        // 사이클 → 계좌 → 사용자 순 소프트 삭제 cascade 검증
        verify(cyclePort).deleteByUserId(targetId);
        verify(accountPort).deleteByUserId(targetId);
        verify(userPort).delete(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_DELETE"), eq("USER"), eq(targetId), any());
    }
}
