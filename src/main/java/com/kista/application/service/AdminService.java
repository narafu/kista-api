package com.kista.application.service;

import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminDashboardUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import com.kista.domain.port.in.AdminUserActionUseCase;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.out.AccountRepository;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminService implements AdminListUsersUseCase, AdminUserActionUseCase, AdminDashboardUseCase {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final ApproveUserUseCase approveUserUseCase; // 승인/거절 위임 (텔레그램 알림 + SSE 포함)
    private final AuditLogPort auditLogPort;             // 감사 로그 기록

    @Override
    @Transactional(readOnly = true)
    public List<User> listAll() {
        return userRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> listByStatus(User.UserStatus status) {
        return userRepository.findAllByStatus(status);
    }

    @Override
    public void approveUser(UUID adminId, UUID targetUserId) {
        // 기존 ApproveUserUseCase 위임 (텔레그램 알림 + SSE 포함)
        approveUserUseCase.approve(targetUserId);
        log.info("관리자 사용자 승인: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_APPROVE", "USER", targetUserId, null);
    }

    @Override
    public void rejectUser(UUID adminId, UUID targetUserId) {
        // 기존 ApproveUserUseCase 위임 (텔레그램 알림 + SSE 포함)
        approveUserUseCase.reject(targetUserId);
        log.info("관리자 사용자 거절: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_REJECT", "USER", targetUserId, null);
    }

    @Override
    public void changeRole(UUID adminId, UUID targetUserId, User.UserRole role) {
        // 사용자 존재 확인
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + targetUserId));
        // role만 교체, updatedAt=null (JPA @LastModifiedDate 자동 처리)
        User updated = new User(
                user.id(),
                user.kakaoId(),
                user.nickname(),
                user.status(),
                role,
                user.telegramBotToken(),
                user.telegramChatId(),
                user.telegramBotUsername(),
                user.createdAt(),
                null,
                user.lastReappliedAt()
        );
        userRepository.save(updated);
        log.info("관리자 역할 변경: adminId={}, targetUserId={}, role={}", adminId, targetUserId, role);
        auditLogPort.log(adminId, "USER_ROLE_CHANGE", "USER", targetUserId,
                Map.of("newRole", role.name()));
    }

    @Override
    public void deleteUser(UUID adminId, UUID targetUserId) {
        // 존재 확인 후 삭제 (FK ON DELETE CASCADE로 accounts/audit_logs 자동 삭제)
        userRepository.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + targetUserId));
        userRepository.delete(targetUserId);
        log.info("관리자 사용자 삭제: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_DELETE", "USER", targetUserId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStats getStats() {
        List<User> all = userRepository.findAll();
        long totalUsers = all.size();
        long pendingCount = all.stream().filter(u -> u.status() == User.UserStatus.PENDING).count();
        long activeCount = all.stream().filter(u -> u.status() == User.UserStatus.ACTIVE).count();
        long rejectedCount = all.stream().filter(u -> u.status() == User.UserStatus.REJECTED).count();
        long totalAccounts = accountRepository.countAll();
        return new AdminStats(totalUsers, pendingCount, activeCount, rejectedCount, totalAccounts);
    }
}
