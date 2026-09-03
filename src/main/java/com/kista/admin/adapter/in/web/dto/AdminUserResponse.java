package com.kista.admin.adapter.in.web.dto;

import com.kista.user.domain.model.AdminUserView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

public record AdminUserResponse(
        @Schema(description = "사용자 고유 ID") UUID id,
        @Schema(description = "닉네임") String nickname,
        @Schema(description = "계정 상태") UserStatus status,
        @Schema(description = "역할") UserRole role,
        @Schema(description = "가입 일시") Instant createdAt
) {
    public static AdminUserResponse from(AdminUserView view) {
        return new AdminUserResponse(view.id(), view.nickname(), view.status(),
                view.role(), view.createdAt());
    }

    public static List<AdminUserResponse> fromList(List<AdminUserView> views) {
        return views.stream().map(AdminUserResponse::from).toList();
    }
}
