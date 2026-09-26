package com.kista.admin.application.usecase;

import com.kista.user.domain.model.AdminUserView;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

// 관리자 사용자 관리 — 조회 + 상태/역할 변경 + 삭제 통합
public interface AdminUserUseCase {
    List<AdminUserView> listAll(LocalDate from, LocalDate to);        // null = 전체
    List<AdminUserView> listByStatus(UserStatus status, LocalDate from, LocalDate to);
    void approveUser(UUID adminId, UUID targetUserId);
    void rejectUser(UUID adminId, UUID targetUserId, String reason);
    void changeRole(UUID adminId, UUID targetUserId, UserRole role);
    void deleteUser(UUID adminId, UUID targetUserId);

    // 단일 사용자 뷰 조회 — listStrategyOrders 전용 (전체 조회 후 ID 필터)
    Optional<AdminUserView> findUser(UUID userId);
}
