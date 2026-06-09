package com.kista.domain.port.in;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;

import java.util.List;
import java.util.UUID;

// 관리자 사용자 관리 — 조회 + 상태/역할 변경 + 삭제 통합
public interface AdminUserUseCase {
    List<AdminUserView> listAll();
    List<AdminUserView> listByStatus(User.UserStatus status);
    void approveUser(UUID adminId, UUID targetUserId);
    void rejectUser(UUID adminId, UUID targetUserId);
    void changeRole(UUID adminId, UUID targetUserId, User.UserRole role);
    void deleteUser(UUID adminId, UUID targetUserId);
}
