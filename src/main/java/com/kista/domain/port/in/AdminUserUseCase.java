package com.kista.domain.port.in;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 관리자 사용자 관리 — 조회 + 상태/역할 변경 + 삭제 통합
public interface AdminUserUseCase {
    List<AdminUserView> listAll(LocalDate from, LocalDate to);        // null = 전체
    List<AdminUserView> listByStatus(User.UserStatus status, LocalDate from, LocalDate to);
    void approveUser(UUID adminId, UUID targetUserId);
    void rejectUser(UUID adminId, UUID targetUserId);
    void changeRole(UUID adminId, UUID targetUserId, User.UserRole role);
    void deleteUser(UUID adminId, UUID targetUserId);

    // 단일 사용자 뷰 조회 — listStrategyOrders 전용 (전체 조회 후 ID 필터)
    Optional<AdminUserView> findUser(UUID userId);
}
