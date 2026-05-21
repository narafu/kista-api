package com.kista.domain.port.in;

import com.kista.domain.model.user.User;

import java.util.UUID;

public interface AdminUserActionUseCase {
    void approveUser(UUID adminId, UUID targetUserId);                    // 승인 + 감사 기록
    void rejectUser(UUID adminId, UUID targetUserId);                     // 거절 + 감사 기록
    void changeRole(UUID adminId, UUID targetUserId, User.UserRole role); // 역할 변경 + 감사 기록
    void deleteUser(UUID adminId, UUID targetUserId);                     // 삭제 + 감사 기록
}
