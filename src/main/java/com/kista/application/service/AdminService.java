package com.kista.application.service;

import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminDashboardUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import com.kista.domain.port.in.AdminUserActionUseCase;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AdminUserViewPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AdminService implements AdminListUsersUseCase, AdminUserActionUseCase, AdminDashboardUseCase {

    private final UserPort userPort;
    private final AdminUserViewPort adminUserViewPort;   // 관리자 화면 전용 read-model
    private final AccountPort accountPort;
    private final UserCascadeDeleter userCascadeDeleter;
    private final ApproveUserUseCase approveUserUseCase; // 승인/거절 위임 (텔레그램 알림 + SSE 포함)
    private final AuditLogPort auditLogPort;             // 감사 로그 기록

    @Override
    @Transactional(readOnly = true)
    public List<AdminUserView> listAll() {
        return adminUserViewPort.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminUserView> listByStatus(User.UserStatus status) {
        return adminUserViewPort.findAllByStatus(status);
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
        User user = userPort.findByIdOrThrow(targetUserId);
        // role만 교체, updatedAt=null (JPA @LastModifiedDate 자동 처리)
        userPort.save(user.withRole(role));
        log.info("관리자 역할 변경: adminId={}, targetUserId={}, role={}", adminId, targetUserId, role);
        auditLogPort.log(adminId, "USER_ROLE_CHANGE", "USER", targetUserId,
                Map.of("newRole", role.name()));
    }

    @Override
    public void deleteUser(UUID adminId, UUID targetUserId) {
        userPort.findByIdOrThrow(targetUserId); // 존재 확인
        userCascadeDeleter.deleteCascade(targetUserId);
        log.info("관리자 사용자 삭제: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_DELETE", "USER", targetUserId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminStats getStats() {
        long totalUsers = userPort.countAll();
        long pendingCount = userPort.countByStatus(User.UserStatus.PENDING);
        long activeCount = userPort.countByStatus(User.UserStatus.ACTIVE);
        long rejectedCount = userPort.countByStatus(User.UserStatus.REJECTED);
        long totalAccounts = accountPort.countAll();
        return new AdminStats(totalUsers, pendingCount, activeCount, rejectedCount, totalAccounts);
    }
}
