package com.kista.application.service;

import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserRole;
import com.kista.domain.model.user.UserStatus;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.UserRepository;
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

    @Mock UserRepository userRepository;
    @Mock AccountRepository accountRepository;
    @Mock ApproveUserUseCase approveUserUseCase;
    @Mock AuditLogPort auditLogPort;

    @InjectMocks AdminService adminService;

    // User 헬퍼: User record 필드 순서에 맞게 작성
    // (id, kakaoId, nickname, status, role, telegramBotToken, telegramChatId, createdAt, updatedAt, lastReappliedAt)
    private User user(UUID id, UserStatus status) {
        return new User(id, "kakao-" + id, "테스트", status, UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void getStats_returnsCorrectCounts() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID(), id3 = UUID.randomUUID();
        when(userRepository.findAll()).thenReturn(List.of(
                user(id1, UserStatus.PENDING),
                user(id2, UserStatus.ACTIVE),
                user(id3, UserStatus.REJECTED)
        ));
        when(accountRepository.countAll()).thenReturn(5L);

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
        User existing = user(targetId, UserStatus.ACTIVE);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(existing));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService.changeRole(adminId, targetId, UserRole.ADMIN);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRole.ADMIN);
        verify(auditLogPort).log(eq(adminId), eq("USER_ROLE_CHANGE"), eq("USER"), eq(targetId), any());
    }

    @Test
    void deleteUser_deletesAndLogsAudit() {
        UUID adminId = UUID.randomUUID(), targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user(targetId, UserStatus.ACTIVE)));

        adminService.deleteUser(adminId, targetId);

        verify(userRepository).delete(targetId);
        verify(auditLogPort).log(eq(adminId), eq("USER_DELETE"), eq("USER"), eq(targetId), any());
    }
}
